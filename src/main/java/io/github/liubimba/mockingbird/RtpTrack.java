package io.github.liubimba.mockingbird;

import java.util.Map;

/**
 * One RTP stream found in a capture: the port it was delivered to, its payload type and SSRC,
 * and how many packets carried it.
 *
 * <p>A capture holds packets, not the session description that gave them meaning. So the port,
 * payload type, SSRC and timing can all be recovered here, but the codec behind a
 * <em>dynamic</em> payload type (96..127) cannot: that mapping lived in the SDP the original
 * client and server exchanged, and it is simply not in the packets. Static payload types are
 * different — RFC 3551 assigns those globally, so they can be named from the registry below.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3551#section-6">RFC 3551 section 6</a>
 */
record RtpTrack(int port, int payloadType, long ssrc, int packetCount) {

  private static final int RTP_HEADER_LENGTH = 12;
  private static final int VERSION_2 = 2;

  /** RTCP packet types 200..204 land on these values once the marker bit is masked off. */
  private static final int RTCP_RANGE_START = 72;
  private static final int RTCP_RANGE_END = 76;

  /** The registry of RFC 3551 static payload types, as "encoding/clock rate". */
  private static final Map<Integer, String> STATIC_ENCODINGS = Map.ofEntries(
      Map.entry(0, "PCMU/8000"),
      Map.entry(3, "GSM/8000"),
      Map.entry(4, "G723/8000"),
      Map.entry(5, "DVI4/8000"),
      Map.entry(6, "DVI4/16000"),
      Map.entry(7, "LPC/8000"),
      Map.entry(8, "PCMA/8000"),
      Map.entry(9, "G722/8000"),
      Map.entry(10, "L16/44100"),
      Map.entry(11, "L16/44100"),
      Map.entry(12, "QCELP/8000"),
      Map.entry(13, "CN/8000"),
      Map.entry(14, "MPA/90000"),
      Map.entry(15, "G728/8000"),
      Map.entry(16, "DVI4/11025"),
      Map.entry(17, "DVI4/22050"),
      Map.entry(18, "G729/8000"),
      Map.entry(25, "CelB/90000"),
      Map.entry(26, "JPEG/90000"),
      Map.entry(28, "nv/90000"),
      Map.entry(31, "H261/90000"),
      Map.entry(32, "MPV/90000"),
      Map.entry(33, "MP2T/90000"),
      Map.entry(34, "H263/90000"));

  /** Static payload types RFC 3551 lists as audio. PT 33 is left out: it is "AV", not either. */
  private static final Map<Integer, String> STATIC_MEDIA_KINDS = Map.ofEntries(
      Map.entry(0, "audio"),
      Map.entry(3, "audio"),
      Map.entry(4, "audio"),
      Map.entry(5, "audio"),
      Map.entry(6, "audio"),
      Map.entry(7, "audio"),
      Map.entry(8, "audio"),
      Map.entry(9, "audio"),
      Map.entry(10, "audio"),
      Map.entry(11, "audio"),
      Map.entry(12, "audio"),
      Map.entry(13, "audio"),
      Map.entry(14, "audio"),
      Map.entry(15, "audio"),
      Map.entry(16, "audio"),
      Map.entry(17, "audio"),
      Map.entry(18, "audio"),
      Map.entry(25, "video"),
      Map.entry(26, "video"),
      Map.entry(28, "video"),
      Map.entry(31, "video"),
      Map.entry(32, "video"),
      Map.entry(34, "video"));

  /** True when the bytes plausibly hold an RTP packet rather than RTCP or something else. */
  static boolean looksLikeRtp(byte[] packet) {
    if (packet.length < RTP_HEADER_LENGTH) {
      return false;
    }
    if (((packet[0] & 0xFF) >>> 6) != VERSION_2) {
      return false;
    }

    int payloadType = payloadTypeOf(packet);
    return payloadType < RTCP_RANGE_START || payloadType > RTCP_RANGE_END;
  }

  /** Reads the payload type, masking off the marker bit that shares its byte. */
  static int payloadTypeOf(byte[] packet) {
    return packet[1] & 0x7F;
  }

  /** Reads the synchronisation source that identifies the stream. */
  static long ssrcOf(byte[] packet) {
    return ((long) (packet[8] & 0xFF) << 24)
        | ((packet[9] & 0xFF) << 16)
        | ((packet[10] & 0xFF) << 8)
        | (packet[11] & 0xFF);
  }

  /**
   * Returns "encoding/clock rate" for a payload type RFC 3551 assigns globally, or {@code null}
   * for a dynamic one, whose meaning only the original SDP knew.
   */
  static String staticEncodingOf(int payloadType) {
    return STATIC_ENCODINGS.get(payloadType);
  }

  /** Returns "audio" or "video" for a static payload type, or {@code null} when unknowable. */
  static String staticMediaKindOf(int payloadType) {
    return STATIC_MEDIA_KINDS.get(payloadType);
  }

  /** True when the codec behind this track has to be supplied from outside the capture. */
  boolean needsExternalDescription() {
    return staticEncodingOf(payloadType) == null;
  }
}
