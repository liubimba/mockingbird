package com.company.pauldekarin;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import org.jnetpcap.BpFilter;
import org.jnetpcap.Pcap;
import org.jnetpcap.PcapException;
import org.jnetpcap.PcapHandler;
import org.jnetpcap.PcapHeader;

public class RTSPServer {
  DatagramSocket RTPSocket;

  final static int RTSPPort = 5554;
  final static String CRLF = "\r\n";

  static String RTSPid = UUID.randomUUID().toString();

  final static int INIT = 0;
  final static int READY = 1;
  final static int PLAYING = 2;

  final static int SETUP = 3;
  final static int PLAY = 4;
  final static int PAUSE = 5;
  final static int TEARDOWN = 6;
  final static int DESCRIBE = 7;
  final static int OPTIONS = 8;

  /** Port the audio track was delivered to in the capture (RTP payload type 96, mpeg4-generic). */
  final static int CAPTURE_AUDIO_PORT = 49188;
  /** Port the video track was delivered to in the capture (RTP payload type 97, H264). */
  final static int CAPTURE_VIDEO_PORT = 49190;

  /** Ethernet (14) + IPv4 (20) + UDP (8) headers sit in front of the RTP payload. */
  final static int RTP_PAYLOAD_OFFSET = 42;
  /** UDP destination port sits after Ethernet (14) + IPv4 (20) + the 2-byte source port. */
  final static int UDP_DST_PORT_OFFSET = 36;

  int RTSPSeqNb = 0;
  List<Integer> RTPDestPorts;

  static int state = -1;

  Socket RTSPSocket;
  InetAddress IPAddr;
  static BufferedReader RTSPBufferedReader;
  static BufferedWriter RTSPBufferedWriter;
  final static String PCAP_FILEPATH = System.getProperty("user.dir").concat("/bunny.pcapng");

  static Pcap pcap;

  private final ReplayClock clock = new ReplayClock();
  private Thread sender;

  public RTSPServer() {
    RTPDestPorts = new ArrayList<Integer>();
  }

  /**
   * Streams the capture on a background thread until the replay ends or {@link #stopStreaming()}
   * interrupts it. Reading packets has to leave the RTSP thread free to accept PAUSE and TEARDOWN
   * while playback is under way.
   */
  private void startStreaming() {
    clock.reset();
    sender = new Thread(() -> pcap.loop(-1, this::replayPacket, "rtp"), "rtp-sender");
    sender.setDaemon(true);
    sender.start();
  }

  /** Stops the replay and waits for the sender to settle, leaving the capture where it stopped. */
  private void stopStreaming() {
    if (sender == null) {
      return;
    }
    pcap.breakloop();
    try {
      sender.join(1_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    sender = null;
  }

  /**
   * Releases one captured packet at the point in time the capture says it belongs, then forwards
   * its RTP payload to the port the client negotiated for that track.
   */
  private void replayPacket(String user, MemorySegment header, MemorySegment packet) {
    try {
      PcapHeader pcapHeader = new PcapHeader(header);
      long capturedAtMicros = ReplayClock.toMicros(pcapHeader.tvSec(), pcapHeader.tvUsec());

      long waitNanos = clock.delayNanosFor(capturedAtMicros, System.nanoTime());
      if (waitNanos > 0) {
        LockSupport.parkNanos(waitNanos);
      }

      int destPort = destPortFor(extractPortUDP(packet));
      byte[] payload = packet.asSlice(RTP_PAYLOAD_OFFSET).toArray(ValueLayout.JAVA_BYTE);

      RTPSocket.send(new DatagramPacket(payload, payload.length, IPAddr, destPort));
    } catch (Exception e) {
      // The client hung up, or the capture holds a frame we cannot read. Either way this replay
      // is over; unwind the loop instead of killing the JVM.
      pcap.breakloop();
    }
  }

  /**
   * Maps a track's port in the capture onto the RTP port the client asked for. The SDP announces
   * audio first, so the client sets audio up first and its port lands at the head of the list.
   */
  private int destPortFor(int capturedDstPort) {
    if (capturedDstPort == CAPTURE_VIDEO_PORT) {
      return RTPDestPorts.get(RTPDestPorts.size() - 1);
    }
    return RTPDestPorts.get(0);
  }

  static public void main(String[] args) throws IOException, PcapException {
    RTSPServer rtspServer = new RTSPServer();

    pcap = Pcap.openOffline(PCAP_FILEPATH);

    BpFilter filter = pcap.compile("udp", true);
    pcap.setFilter(filter);

    ServerSocket masterSocket = new ServerSocket(RTSPPort);
    rtspServer.RTSPSocket = masterSocket.accept();
    masterSocket.close();

    rtspServer.IPAddr = rtspServer.RTSPSocket.getInetAddress();

    RTSPBufferedReader =
        new BufferedReader(new InputStreamReader(rtspServer.RTSPSocket.getInputStream()));
    RTSPBufferedWriter =
        new BufferedWriter(new OutputStreamWriter(rtspServer.RTSPSocket.getOutputStream()));

    state = INIT;

    int reqType;
    boolean done = false;

    while (!done) {
      reqType = rtspServer.parseRTSPRequest();

      if (reqType == OPTIONS) {
        rtspServer.sendResponse("Public: DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE, OPTIONS");
      } else if (reqType == DESCRIBE) {
        rtspServer.sendDescribe();
      } else if (reqType == SETUP) {
        if (rtspServer.RTPSocket == null) {
          rtspServer.RTPSocket = new DatagramSocket();
        }
        // Header block only: sendResponse adds the blank line that ends it.
        String resp = "Transport: RTP/AVP;unicast;client_port="
            + Integer.toString(rtspServer.RTPDestPorts.getLast()) + CRLF + "Session: " + RTSPid
            + ";timeout=60";
        rtspServer.sendResponse(resp);

        state = READY;
      } else if (reqType == PLAY) {
        if (state == READY) {
          rtspServer.sendResponse();
          rtspServer.startStreaming();

          state = PLAYING;
        }
      } else if (reqType == TEARDOWN) {
        rtspServer.sendResponse();
        rtspServer.stopStreaming();
        rtspServer.RTSPSocket.close();
        rtspServer.RTPSocket.close();
        done = true;
      } else if (reqType == PAUSE) {
        if (state == PLAYING) {
          rtspServer.sendResponse();
          rtspServer.stopStreaming();
          state = READY;
        }
      }
    }
  }

  /** Reads the UDP destination port, which the capture stores big-endian. */
  public static int extractPortUDP(MemorySegment packet) {
    return Short.toUnsignedInt(
        Short.reverseBytes(packet.asSlice(UDP_DST_PORT_OFFSET, 2).get(ValueLayout.JAVA_SHORT, 0)));
  }
  private static void displayMemorySegment(MemorySegment seg) {
    StringBuilder hexBuilder = new StringBuilder();
    Integer hexCount = 0;
    for (byte b : seg.toArray(ValueLayout.JAVA_BYTE)) {
      hexBuilder.append(String.format("%02x ", b));
      if (hexCount++ >= 15) {
        hexBuilder.append('\n');
        hexCount = 0;
      }
    }
    System.out.println(hexBuilder.toString());
  }

  private void sendResponse() {
    try {
      RTSPBufferedWriter.write("RTSP/1.0 200 OK" + CRLF);
      RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);
      RTSPBufferedWriter.write("Server: localhost" + CRLF);
      RTSPBufferedWriter.write("Cache: no-cache" + CRLF);
      RTSPBufferedWriter.write("Session: " + RTSPid + CRLF);
      RTSPBufferedWriter.write(CRLF);
      RTSPBufferedWriter.flush();
    } catch (IOException e) {
    }
  }
  private void sendResponse(String msg) {
    try {
      RTSPBufferedWriter.write("RTSP/1.0 200 OK" + CRLF);
      RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);
      RTSPBufferedWriter.write("Server: localhost" + CRLF);
      RTSPBufferedWriter.write("Cache: no-cache" + CRLF);
      RTSPBufferedWriter.write(msg + CRLF);
      RTSPBufferedWriter.write(CRLF);
      RTSPBufferedWriter.flush();
    } catch (IOException e) {
    }
  }

  /**
   * Describes the two tracks held in the capture. The parameters below are the ones the original
   * stream was encoded with, so they have to match the packets that get replayed.
   *
   * <p>Audio is announced first, which is what makes the client set it up first and lets
   * {@link #destPortFor(int)} tell the two tracks apart by the order of their SETUPs.
   */
  private void sendDescribe() {
    String sdp = "v=0" + CRLF + "o=- 1823687535 1823687535 IN IP4 127.0.0.1" + CRLF
        + "s=BigBuckBunny_115k.mov" + CRLF + "c=IN IP4 127.0.0.1" + CRLF + "t=0 0" + CRLF
        + "a=sdplang:en" + CRLF + "a=range:npt=0- 596.48" + CRLF + "a=control:*" + CRLF
        + "m=audio 0 RTP/AVP 96" + CRLF + "a=rtpmap:96 mpeg4-generic/12000/2" + CRLF
        + "a=fmtp:96 profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1490"
        + CRLF + "a=control:trackID=1" + CRLF + "m=video 0 RTP/AVP 97" + CRLF
        + "a=rtpmap:97 H264/90000" + CRLF
        + "a=fmtp:97 packetization-mode=1;profile-level-id=42C01E;sprop-parameter-sets=Z0LAHtkDxWhAAAADAEAAAAwDxYuS,aMuMsg=="
        + CRLF + "a=cliprect:0,0,160,240" + CRLF + "a=framesize:97 240-160" + CRLF
        + "a=framerate:24.0" + CRLF + "a=control:trackID=2" + CRLF;

    try {
      RTSPBufferedWriter.write("RTSP/1.0 200 OK" + CRLF);
      RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);
      RTSPBufferedWriter.write("Server: rtsp-pcap-server" + CRLF);
      RTSPBufferedWriter.write("Cache-Control: no-cache" + CRLF);
      RTSPBufferedWriter.write("Session: " + RTSPid + ";timeout=60" + CRLF);
      RTSPBufferedWriter.write("Content-Base: rtsp://localhost:" + RTSPPort + "/bunny/" + CRLF);
      RTSPBufferedWriter.write("Content-Type: application/sdp" + CRLF);
      RTSPBufferedWriter.write("Content-Length: " + sdp.getBytes(US_ASCII).length + CRLF);
      RTSPBufferedWriter.write(CRLF);
      RTSPBufferedWriter.write(sdp);
      RTSPBufferedWriter.flush();
    } catch (IOException ex) {
      ex.printStackTrace();
    }
  }
  private int parseRTSPRequest() {
    int reqType = -1;
    try {
      String RequestLine = RTSPBufferedReader.readLine();

      if (RequestLine == null || RequestLine.isEmpty())
        return reqType;
      System.out.println("*".repeat(10));
      System.out.println("[\033[32mRequestLine\033[0m]: " + RequestLine);

      reqType = switch (RtspHeaders.method(RequestLine)) {
        case "SETUP" -> SETUP;
        case "PLAY" -> PLAY;
        case "PAUSE" -> PAUSE;
        case "TEARDOWN" -> TEARDOWN;
        case "DESCRIBE" -> DESCRIBE;
        case "OPTIONS" -> OPTIONS;
        default -> -1;
      };

      // Read the whole header block first: RFC 2326 fixes no order, so nothing can be
      // read off a line number.
      List<String> headers = new ArrayList<>();
      for (String line = RTSPBufferedReader.readLine();
          line != null && !line.isEmpty();
          line = RTSPBufferedReader.readLine()) {
        headers.add(line);
        System.out.println("[\033[32mheader\033[0m]: " + line);
      }

      RTSPSeqNb = RtspHeaders.cSeq(headers);

      if (reqType == SETUP) {
        int clientPort = RtspHeaders.clientPort(headers);
        if (clientPort > 0) {
          RTPDestPorts.add(clientPort);
        }
      }
      System.out.println("*".repeat(10));

    } catch (IOException e) {
      return -1;
    }
    return (reqType);
  }
}
