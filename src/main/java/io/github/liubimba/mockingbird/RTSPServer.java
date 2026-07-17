package io.github.liubimba.mockingbird;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

/**
 * Serves a pcap capture over RTSP by replaying the RTP it holds.
 *
 * <p>What to send is worked out from the capture itself: {@link CaptureAnalyzer} finds the
 * streams, {@link SdpBuilder} describes them, and {@link ReplayClock} decides when each packet is
 * due. Nothing about any particular capture is baked in here.
 */
public class RTSPServer {

  static final String CRLF = "\r\n";

  private static final int INIT = 0;
  private static final int READY = 1;
  private static final int PLAYING = 2;

  private static final int SETUP = 3;
  private static final int PLAY = 4;
  private static final int PAUSE = 5;
  private static final int TEARDOWN = 6;
  private static final int DESCRIBE = 7;
  private static final int OPTIONS = 8;
  private static final int UNKNOWN = -1;

  private final Options options;
  private final Capture capture;
  private final List<RtpTrack> tracks;
  private final String sdp;

  /** Where each track's packets went in the capture, against where this client wants them. */
  private final Map<Integer, Integer> captureToClientPort = new HashMap<>();

  private final ReplayClock clock = new ReplayClock();
  private final String sessionId = UUID.randomUUID().toString();

  private Socket rtspSocket;
  private InetAddress clientAddress;
  private DatagramSocket rtpSocket;
  private BufferedReader in;
  private BufferedWriter out;

  private volatile boolean streaming;
  private Thread sender;
  private int cSeq;
  private int state = INIT;

  RTSPServer(Options options, Capture capture, List<RtpTrack> tracks, String sdp) {
    this.options = options;
    this.capture = capture;
    this.tracks = tracks;
    this.sdp = sdp;
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 0 || List.of(args).contains("--help")) {
      System.out.print(Options.USAGE);
      return;
    }

    Options options;
    Capture capture;
    List<RtpTrack> tracks;
    String sdp;
    try {
      options = Options.parse(args);
      capture = Capture.at(options.pcap());

      tracks = capture.findTracks();
      if (tracks.isEmpty()) {
        System.err.println("No RTP streams found in " + options.pcap());
        System.exit(1);
        return;
      }

      sdp = options.sdp() != null
          ? Files.readString(options.sdp())
          : new SdpBuilder(options.sessionName())
              .build(tracks, options.encodings(), options.formatParameters());
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      System.exit(2);
      return;
    }

    describeFindings(tracks);
    new RTSPServer(options, capture, tracks, sdp).serve();
  }

  /** Reports what the capture holds, so a surprise is visible before a client trips over it. */
  private static void describeFindings(List<RtpTrack> tracks) {
    System.out.println("Found " + tracks.size() + " RTP stream(s):");
    for (int i = 0; i < tracks.size(); i++) {
      RtpTrack track = tracks.get(i);
      System.out.printf("  trackID=%d  port %d  payload type %d  ssrc %d  %d packets%n",
          i + 1, track.port(), track.payloadType(), track.ssrc(), track.packetCount());
    }
  }

  /** Accepts one RTSP session and answers requests until the client tears it down. */
  void serve() throws IOException {
    try (ServerSocket listener = new ServerSocket(options.port())) {
      System.out.println("Serving rtsp://localhost:" + options.port() + "/" + options.sessionName());
      rtspSocket = listener.accept();
    }

    clientAddress = rtspSocket.getInetAddress();
    in = new BufferedReader(new InputStreamReader(rtspSocket.getInputStream(), US_ASCII));
    out = new BufferedWriter(new OutputStreamWriter(rtspSocket.getOutputStream(), US_ASCII));

    boolean done = false;
    while (!done) {
      Request request = readRequest();
      if (request == null) {
        break; // the client went away without a TEARDOWN
      }
      done = handle(request);
    }

    stopStreaming();
    close(rtspSocket);
    if (rtpSocket != null) {
      rtpSocket.close();
    }
  }

  /** Answers one request. Returns true once the session is over. */
  private boolean handle(Request request) throws IOException {
    switch (request.method()) {
      case OPTIONS -> respond("Public: DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE, OPTIONS");
      case DESCRIBE -> respondWithDescription();
      case SETUP -> setUp(request);
      case PLAY -> {
        if (state == READY) {
          respond();
          startStreaming();
          state = PLAYING;
        }
      }
      case PAUSE -> {
        if (state == PLAYING) {
          respond();
          stopStreaming();
          state = READY;
        }
      }
      case TEARDOWN -> {
        respond();
        return true;
      }
      default -> { /* not something we serve */ }
    }
    return false;
  }

  /**
   * Binds one of the capture's tracks to the port this client wants it on.
   *
   * <p>The track comes from the SETUP's control URL rather than from the order SETUPs arrive in,
   * so a client that sets its tracks up out of order still gets each stream on the right port.
   */
  private void setUp(Request request) throws IOException {
    int trackId = RtspHeaders.trackId(request.line());
    int clientPort = RtspHeaders.clientPort(request.headers());

    if (trackId < 1 || trackId > tracks.size() || clientPort < 0) {
      respondWith("RTSP/1.0 455 Method Not Valid In This State", "");
      return;
    }

    if (rtpSocket == null) {
      rtpSocket = new DatagramSocket();
    }
    captureToClientPort.put(tracks.get(trackId - 1).port(), clientPort);

    respond("Transport: RTP/AVP;unicast;client_port=" + clientPort + CRLF
        + "Session: " + sessionId + ";timeout=60");
    state = READY;
  }

  private void startStreaming() {
    clock.reset();
    streaming = true;
    sender = new Thread(this::replay, "rtp-sender");
    sender.setDaemon(true);
    sender.start();
  }

  private void stopStreaming() {
    streaming = false;
    if (sender == null) {
      return;
    }
    try {
      sender.join(1_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    sender = null;
  }

  /** Reads the capture on its own thread so PAUSE and TEARDOWN stay answerable while it plays. */
  private void replay() {
    try {
      capture.replay(this::sendFrame, () -> streaming);
    } catch (Exception e) {
      System.err.println("replay stopped: " + e.getMessage());
    }
  }

  /** Waits until the frame is due, then forwards its RTP to the port the client asked for. */
  private void sendFrame(byte[] frame, long capturedAtMicros) {
    UdpFrame udp = UdpFrame.parse(frame);
    if (udp == null) {
      return;
    }

    Integer clientPort = captureToClientPort.get(udp.destinationPort());
    if (clientPort == null) {
      return; // a stream this client never set up, or not media at all
    }

    long waitNanos = clock.delayNanosFor(capturedAtMicros, System.nanoTime());
    if (waitNanos > 0) {
      LockSupport.parkNanos(waitNanos);
    }

    byte[] payload = new byte[udp.payloadLength()];
    System.arraycopy(frame, udp.payloadOffset(), payload, 0, payload.length);

    try {
      rtpSocket.send(new DatagramPacket(payload, payload.length, clientAddress, clientPort));
    } catch (IOException e) {
      streaming = false; // the client is gone; unwind rather than shout about every packet
    }
  }

  private void respondWithDescription() throws IOException {
    respondWith("RTSP/1.0 200 OK",
        "Content-Base: rtsp://localhost:" + options.port() + "/" + options.sessionName() + "/" + CRLF
            + "Content-Type: application/sdp" + CRLF
            + "Content-Length: " + sdp.getBytes(US_ASCII).length,
        sdp);
  }

  private void respond() throws IOException {
    respond("");
  }

  private void respond(String headers) throws IOException {
    respondWith("RTSP/1.0 200 OK", headers);
  }

  private void respondWith(String status, String headers) throws IOException {
    respondWith(status, headers, "");
  }

  /**
   * Writes one response. {@code headers} holds extra header lines with no trailing terminator; the
   * blank line that ends the header block is added here, exactly once.
   */
  private void respondWith(String status, String headers, String body) throws IOException {
    out.write(status + CRLF);
    out.write("CSeq: " + cSeq + CRLF);
    out.write("Server: mockingbird" + CRLF);
    out.write("Cache-Control: no-cache" + CRLF);
    out.write("Session: " + sessionId + ";timeout=60" + CRLF);
    if (!headers.isEmpty()) {
      out.write(headers + CRLF);
    }
    out.write(CRLF);
    if (!body.isEmpty()) {
      out.write(body);
    }
    out.flush();
  }

  /** Reads one request, or returns {@code null} if the client closed the connection. */
  private Request readRequest() throws IOException {
    String requestLine = in.readLine();
    if (requestLine == null) {
      return null;
    }
    if (requestLine.isEmpty()) {
      return new Request(UNKNOWN, "", List.of());
    }

    List<String> headers = new ArrayList<>();
    for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
      headers.add(line);
    }

    cSeq = RtspHeaders.cSeq(headers);
    return new Request(methodCodeOf(requestLine), requestLine, headers);
  }

  private static int methodCodeOf(String requestLine) {
    return switch (RtspHeaders.method(requestLine)) {
      case "SETUP" -> SETUP;
      case "PLAY" -> PLAY;
      case "PAUSE" -> PAUSE;
      case "TEARDOWN" -> TEARDOWN;
      case "DESCRIBE" -> DESCRIBE;
      case "OPTIONS" -> OPTIONS;
      default -> UNKNOWN;
    };
  }

  private static void close(Socket socket) {
    try {
      socket.close();
    } catch (IOException e) {
      // closing on the way out; nothing left to do about it
    }
  }

  /** One parsed RTSP request. */
  private record Request(int method, String line, List<String> headers) {}
}
