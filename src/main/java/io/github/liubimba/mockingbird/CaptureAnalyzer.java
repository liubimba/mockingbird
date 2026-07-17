package io.github.liubimba.mockingbird;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out which RTP streams a capture holds.
 *
 * <p>Frames are handed in one at a time rather than read from a file here, which keeps the
 * analysis independent of libpcap and testable on synthetic frames. Reading the capture is
 * somebody else's job.
 *
 * <p>Streams are grouped by the port they were delivered to, because that is the unit RTSP
 * negotiates: each track gets its own {@code client_port}, so the port is what a replay has to
 * route by.
 */
final class CaptureAnalyzer {

  private final Map<Integer, Accumulator> byPort = new LinkedHashMap<>();

  /** Takes one captured frame into account, ignoring anything that is not RTP. */
  void observe(byte[] frame) {
    UdpFrame udp = UdpFrame.parse(frame);
    if (udp == null) {
      return;
    }

    byte[] payload =
        Arrays.copyOfRange(frame, udp.payloadOffset(), udp.payloadOffset() + udp.payloadLength());
    if (!RtpTrack.looksLikeRtp(payload)) {
      return;
    }

    byPort.computeIfAbsent(
            udp.destinationPort(),
            port -> new Accumulator(port, RtpTrack.payloadTypeOf(payload), RtpTrack.ssrcOf(payload)))
        .count++;
  }

  /** Every RTP stream seen, busiest first. */
  List<RtpTrack> tracks() {
    List<RtpTrack> tracks = new ArrayList<>();
    for (Accumulator a : byPort.values()) {
      tracks.add(new RtpTrack(a.port, a.payloadType, a.ssrc, a.count));
    }
    tracks.sort(Comparator.comparingInt(RtpTrack::packetCount).reversed());
    return tracks;
  }

  /**
   * The streams substantial enough to be actual media, busiest first.
   *
   * <p>A capture picks up strays — a probe, a keepalive, one packet of something unrelated that
   * happens to parse as RTP. Announcing those as tracks would produce an SDP no client can use,
   * so a stream has to carry its weight to count.
   *
   * @param minPackets how many packets a stream needs before it is treated as media
   */
  List<RtpTrack> mediaTracks(int minPackets) {
    return tracks().stream().filter(track -> track.packetCount() >= minPackets).toList();
  }

  /** Mutable tally for one port while the capture is being read. */
  private static final class Accumulator {
    private final int port;
    private final int payloadType;
    private final long ssrc;
    private int count;

    private Accumulator(int port, int payloadType, long ssrc) {
      this.port = port;
      this.payloadType = payloadType;
      this.ssrc = ssrc;
    }
  }
}
