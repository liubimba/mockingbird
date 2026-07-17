package io.github.liubimba.mockingbird;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CaptureAnalyzerTest {

  /** Builds an Ethernet + IPv4 + UDP frame carrying an RTP packet. */
  private static byte[] rtpFrame(int dstPort, int payloadType, long ssrc) {
    return frame(dstPort, 17, rtpHeader(payloadType, ssrc));
  }

  private static byte[] rtpHeader(int payloadType, long ssrc) {
    byte[] rtp = new byte[12];
    rtp[0] = (byte) 0x80; // version 2
    rtp[1] = (byte) payloadType;
    rtp[8] = (byte) (ssrc >>> 24);
    rtp[9] = (byte) (ssrc >>> 16);
    rtp[10] = (byte) (ssrc >>> 8);
    rtp[11] = (byte) ssrc;
    return rtp;
  }

  private static byte[] frame(int dstPort, int protocol, byte[] payload) {
    byte[] f = new byte[42 + payload.length];
    f[12] = (byte) 0x08; // EtherType IPv4
    f[13] = (byte) 0x00;
    f[14] = (byte) 0x45; // version 4, no options
    f[23] = (byte) protocol;
    f[36] = (byte) (dstPort >>> 8);
    f[37] = (byte) dstPort;
    System.arraycopy(payload, 0, f, 42, payload.length);
    return f;
  }

  @Test
  void findsNothingInAnEmptyCapture() {
    assertTrue(new CaptureAnalyzer().tracks().isEmpty());
  }

  @Test
  void groupsPacketsIntoOneTrackPerPort() {
    CaptureAnalyzer analyzer = new CaptureAnalyzer();
    analyzer.observe(rtpFrame(49188, 96, 111L));
    analyzer.observe(rtpFrame(49188, 96, 111L));
    analyzer.observe(rtpFrame(49190, 97, 222L));

    assertEquals(2, analyzer.tracks().size());
  }

  @Test
  void recordsPortPayloadTypeSsrcAndCount() {
    CaptureAnalyzer analyzer = new CaptureAnalyzer();
    analyzer.observe(rtpFrame(49190, 97, 0xCAFEL));
    analyzer.observe(rtpFrame(49190, 97, 0xCAFEL));
    analyzer.observe(rtpFrame(49190, 97, 0xCAFEL));

    RtpTrack track = analyzer.tracks().get(0);
    assertEquals(49190, track.port());
    assertEquals(97, track.payloadType());
    assertEquals(0xCAFEL, track.ssrc());
    assertEquals(3, track.packetCount());
  }

  @Test
  void ordersTracksByHowMuchTheyCarry() {
    CaptureAnalyzer analyzer = new CaptureAnalyzer();
    analyzer.observe(rtpFrame(1000, 96, 1L)); // one audio packet
    analyzer.observe(rtpFrame(2000, 97, 2L));
    analyzer.observe(rtpFrame(2000, 97, 2L)); // two video packets

    List<RtpTrack> tracks = analyzer.tracks();
    assertEquals(2000, tracks.get(0).port(), "the busiest track comes first");
    assertEquals(1000, tracks.get(1).port());
  }

  @Test
  void ignoresFramesThatAreNotUdp() {
    CaptureAnalyzer analyzer = new CaptureAnalyzer();
    analyzer.observe(frame(49190, 6, rtpHeader(97, 1L))); // TCP

    assertTrue(analyzer.tracks().isEmpty());
  }

  @Test
  void ignoresUdpThatIsNotRtp() {
    CaptureAnalyzer analyzer = new CaptureAnalyzer();
    analyzer.observe(frame(53, 17, new byte[] {1, 2, 3, 4})); // too short for RTP

    assertTrue(analyzer.tracks().isEmpty());
  }

  @Test
  void ignoresRtcpSharingTheCapture() {
    CaptureAnalyzer analyzer = new CaptureAnalyzer();
    byte[] senderReport = rtpHeader(0, 1L);
    senderReport[1] = (byte) 200; // RTCP packet type 200 reads as payload type 72

    analyzer.observe(frame(49191, 17, senderReport));

    assertTrue(analyzer.tracks().isEmpty(), "RTCP must not be mistaken for a media track");
  }

  @Test
  void keepsOnlyTracksBusyEnoughToBeMedia() {
    CaptureAnalyzer analyzer = new CaptureAnalyzer();
    analyzer.observe(rtpFrame(9999, 96, 1L)); // a lone stray packet
    for (int i = 0; i < 10; i++) {
      analyzer.observe(rtpFrame(49190, 97, 2L));
    }

    List<RtpTrack> media = analyzer.mediaTracks(5);
    assertEquals(1, media.size());
    assertEquals(49190, media.get(0).port());
  }
}
