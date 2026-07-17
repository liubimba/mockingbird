package io.github.liubimba.mockingbird;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the streams found in a capture into the session description a client needs to play them.
 *
 * <p>Most of an SDP can be recovered from the packets: how many streams there are, their payload
 * types, and which port each used. The codec behind a <em>dynamic</em> payload type cannot —
 * 96..127 mean whatever the original session agreed they meant, and that agreement lived in an
 * SDP that no capture contains. Rather than guess and hand a client a stream it will decode into
 * noise, this refuses and says what it needs.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4566">RFC 4566</a>
 */
final class SdpBuilder {

  private static final String CRLF = "\r\n";

  /** Encoding names that mean audio even though they are not in the RFC 3551 registry. */
  private static final Set<String> KNOWN_AUDIO_ENCODINGS =
      Set.of("opus", "aac", "mpeg4-generic", "speex", "vorbis", "ac3", "pcm", "g711", "amr", "ilbc");

  private final String sessionName;

  SdpBuilder(String sessionName) {
    this.sessionName = sessionName;
  }

  /**
   * Describes the tracks, in the order given. That order is the contract with the client: it sets
   * each track's {@code trackID}, which is how a later SETUP says which stream it means.
   *
   * @param encodings "encoding/clock rate" per payload type, needed for dynamic ones
   * @param formatParameters optional {@code a=fmtp} value per payload type
   * @throws IllegalArgumentException if a dynamic payload type has no encoding supplied
   */
  String build(List<RtpTrack> tracks, Map<Integer, String> encodings,
      Map<Integer, String> formatParameters) {
    StringBuilder sdp = new StringBuilder();
    sdp.append("v=0").append(CRLF);
    sdp.append("o=- 0 0 IN IP4 127.0.0.1").append(CRLF);
    sdp.append("s=").append(sessionName).append(CRLF);
    sdp.append("c=IN IP4 127.0.0.1").append(CRLF);
    sdp.append("t=0 0").append(CRLF);
    sdp.append("a=control:*").append(CRLF);

    int trackId = 1;
    for (RtpTrack track : tracks) {
      int payloadType = track.payloadType();
      String encoding = encodingFor(payloadType, encodings);

      sdp.append("m=").append(mediaKindFor(payloadType, encoding)).append(" 0 RTP/AVP ")
          .append(payloadType).append(CRLF);
      sdp.append("a=rtpmap:").append(payloadType).append(' ').append(encoding).append(CRLF);

      String fmtp = formatParameters.get(payloadType);
      if (fmtp != null) {
        sdp.append("a=fmtp:").append(payloadType).append(' ').append(fmtp).append(CRLF);
      }

      sdp.append("a=control:trackID=").append(trackId++).append(CRLF);
    }

    return sdp.toString();
  }

  private static String encodingFor(int payloadType, Map<Integer, String> encodings) {
    String supplied = encodings.get(payloadType);
    if (supplied != null) {
      return supplied;
    }

    String known = RtpTrack.staticEncodingOf(payloadType);
    if (known != null) {
      return known;
    }

    throw new IllegalArgumentException(
        "payload type " + payloadType + " is dynamic, so the capture cannot say what codec it is."
            + " Supply it, for example: --map " + payloadType + ":H264/90000");
  }

  /**
   * Decides whether a track is audio or video: from the RFC 3551 registry when the payload type
   * is static, otherwise from the encoding name the caller supplied.
   */
  private static String mediaKindFor(int payloadType, String encoding) {
    String known = RtpTrack.staticMediaKindOf(payloadType);
    if (known != null) {
      return known;
    }

    String name = encoding.split("/")[0].toLowerCase();
    return KNOWN_AUDIO_ENCODINGS.contains(name) ? "audio" : "video";
  }
}
