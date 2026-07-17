package io.github.liubimba.mockingbird;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.List;
import org.jnetpcap.Pcap;
import org.jnetpcap.PcapException;
import org.jnetpcap.PcapHeader;

/**
 * A pcap file on disk, read through libpcap.
 *
 * <p>Everything native lives here. The rest of the server works on plain byte arrays and
 * timestamps, so it stays testable without libpcap installed.
 *
 * <p>A capture is read twice over its life: once to find out what streams it holds, and again
 * to replay them. libpcap offline handles have no rewind, so each pass opens the file afresh.
 */
final class Capture {

  /** How many packets a stream needs before it counts as media rather than a stray. */
  private static final int MIN_PACKETS_FOR_MEDIA = 10;

  private final Path path;

  private Capture(Path path) {
    this.path = path;
  }

  /**
   * Points at a capture file.
   *
   * @throws IllegalArgumentException if the file is missing or unreadable, which is worth
   *     catching here rather than as an opaque native failure later
   */
  static Capture at(Path path) {
    if (!Files.isReadable(path)) {
      throw new IllegalArgumentException("capture is not readable: " + path);
    }
    return new Capture(path);
  }

  Path path() {
    return path;
  }

  /** Reads the whole capture and reports the RTP streams worth announcing, busiest first. */
  List<RtpTrack> findTracks() throws PcapException {
    CaptureAnalyzer analyzer = new CaptureAnalyzer();

    try (Pcap pcap = openFiltered()) {
      pcap.loop(-1, (String user, MemorySegment header, MemorySegment packet) -> {
        analyzer.observe(toBytes(packet, new PcapHeader(header)));
      }, "analyze");
    }

    return analyzer.mediaTracks(MIN_PACKETS_FOR_MEDIA);
  }

  /**
   * Reads the capture again, handing every UDP frame to {@code onFrame} along with the moment it
   * was captured, in microseconds. Returns when the capture runs out or {@code keepGoing} says to
   * stop, so a paused or departed client does not leave a reader running.
   */
  void replay(FrameHandler onFrame, BooleanSupplier keepGoing) throws PcapException {
    try (Pcap pcap = openFiltered()) {
      pcap.loop(-1, (String user, MemorySegment header, MemorySegment packet) -> {
        if (!keepGoing.getAsBoolean()) {
          pcap.breakloop();
          return;
        }

        PcapHeader pcapHeader = new PcapHeader(header);
        try {
          long capturedAtMicros = ReplayClock.toMicros(pcapHeader.tvSec(), pcapHeader.tvUsec());
          onFrame.handle(toBytes(packet, pcapHeader), capturedAtMicros);
        } catch (Exception e) {
          pcap.breakloop();
        }
      }, "replay");
    }
  }

  private Pcap openFiltered() throws PcapException {
    Pcap pcap = Pcap.openOffline(path.toString());
    pcap.setFilter(pcap.compile("udp", true));
    return pcap;
  }

  /**
   * Copies a captured frame out of native memory.
   *
   * <p>The segment handed to a libpcap callback is only valid for the duration of that call, and
   * its length is whatever libpcap actually captured — which a snaplen may have cut short.
   */
  private static byte[] toBytes(MemorySegment packet, PcapHeader header) {
    try {
      return packet.reinterpret(header.captureLength()).toArray(ValueLayout.JAVA_BYTE);
    } catch (Exception e) {
      return new byte[0];
    }
  }

  /** Receives one captured frame and the microsecond timestamp it was captured at. */
  @FunctionalInterface
  interface FrameHandler {
    void handle(byte[] frame, long capturedAtMicros);
  }
}
