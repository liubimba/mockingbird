package com.company.pauldekarin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RtpTrackTest {

  /** version 2, no padding/extension, payload type 97, then seq/timestamp/ssrc. */
  private static byte[] rtpPacket(int payloadType, long ssrc) {
    byte[] p = new byte[12];
    p[0] = (byte) 0x80; // version 2
    p[1] = (byte) payloadType;
    p[8] = (byte) (ssrc >>> 24);
    p[9] = (byte) (ssrc >>> 16);
    p[10] = (byte) (ssrc >>> 8);
    p[11] = (byte) ssrc;
    return p;
  }

  @Test
  void readsPayloadTypeAndSsrc() {
    byte[] packet = rtpPacket(97, 0xDEADBEEFL);

    assertTrue(RtpTrack.looksLikeRtp(packet));
    assertEquals(97, RtpTrack.payloadTypeOf(packet));
    assertEquals(0xDEADBEEFL, RtpTrack.ssrcOf(packet));
  }

  @Test
  void ignoresTheMarkerBitWhenReadingPayloadType() {
    byte[] packet = rtpPacket(97, 1L);
    packet[1] |= (byte) 0x80; // marker bit set on the last packet of a frame

    assertEquals(97, RtpTrack.payloadTypeOf(packet));
  }

  @Test
  void rejectsPacketsThatAreNotRtpVersionTwo() {
    byte[] packet = rtpPacket(97, 1L);
    packet[0] = 0x40; // version 1

    assertFalse(RtpTrack.looksLikeRtp(packet));
  }

  @Test
  void rejectsPacketsTooShortToHoldAnRtpHeader() {
    assertFalse(RtpTrack.looksLikeRtp(new byte[] {(byte) 0x80, 97, 0, 0}));
  }

  @Test
  void rejectsRtcpMasqueradingAsRtp() {
    // A real RTCP sender report carries packet type 200 in the byte RTP reads as marker+PT,
    // which lands on PT 72. RTCP shares the version bits and the port range, so 72..76 have
    // to be treated as "not RTP".
    byte[] senderReport = rtpPacket(0, 1L);
    senderReport[1] = (byte) 200;

    assertFalse(RtpTrack.looksLikeRtp(senderReport));
  }

  @Test
  void namesStaticPayloadTypesFromTheRfc3551Registry() {
    assertEquals("PCMU/8000", RtpTrack.staticEncodingOf(0));
    assertEquals("PCMA/8000", RtpTrack.staticEncodingOf(8));
    assertEquals("JPEG/90000", RtpTrack.staticEncodingOf(26));
    assertEquals("MP2T/90000", RtpTrack.staticEncodingOf(33));
  }

  @Test
  void hasNoNameForDynamicPayloadTypes() {
    // 96..127 are assigned by the session's own SDP, so a capture cannot reveal them
    assertEquals(null, RtpTrack.staticEncodingOf(96));
    assertEquals(null, RtpTrack.staticEncodingOf(97));
  }

  @Test
  void guessesMediaKindFromStaticPayloadType() {
    assertEquals("audio", RtpTrack.staticMediaKindOf(0));
    assertEquals("video", RtpTrack.staticMediaKindOf(26));
    assertEquals(null, RtpTrack.staticMediaKindOf(97));
  }
}
