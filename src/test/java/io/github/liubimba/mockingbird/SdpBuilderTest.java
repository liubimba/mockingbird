package io.github.liubimba.mockingbird;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdpBuilderTest {

  private static RtpTrack track(int port, int payloadType) {
    return new RtpTrack(port, payloadType, 1L, 100);
  }

  @Test
  void describesAStaticPayloadTypeFromTheRegistry() {
    String sdp = new SdpBuilder("test").build(List.of(track(5000, 0)), Map.of(), Map.of());

    assertTrue(sdp.contains("m=audio 0 RTP/AVP 0"), sdp);
    assertTrue(sdp.contains("a=rtpmap:0 PCMU/8000"), sdp);
  }

  @Test
  void announcesOneMediaSectionPerTrack() {
    String sdp =
        new SdpBuilder("test").build(List.of(track(5000, 0), track(5002, 26)), Map.of(), Map.of());

    assertTrue(sdp.contains("m=audio 0 RTP/AVP 0"), sdp);
    assertTrue(sdp.contains("m=video 0 RTP/AVP 26"), sdp);
  }

  @Test
  void numbersTracksInTheOrderTheyAreAnnounced() {
    String sdp =
        new SdpBuilder("test").build(List.of(track(5000, 26), track(5002, 0)), Map.of(), Map.of());

    int firstControl = sdp.indexOf("a=control:trackID=1");
    int secondControl = sdp.indexOf("a=control:trackID=2");
    assertTrue(firstControl > 0 && secondControl > firstControl, sdp);
    // trackID=1 must belong to the video section, which was announced first
    assertTrue(sdp.indexOf("m=video") < firstControl, sdp);
    assertTrue(sdp.indexOf("m=audio") < secondControl, sdp);
  }

  @Test
  void takesTheEncodingOfADynamicPayloadTypeFromTheCaller() {
    String sdp =
        new SdpBuilder("test").build(List.of(track(49190, 97)), Map.of(97, "H264/90000"), Map.of());

    assertTrue(sdp.contains("m=video 0 RTP/AVP 97"), sdp);
    assertTrue(sdp.contains("a=rtpmap:97 H264/90000"), sdp);
  }

  @Test
  void addsFormatParametersWhenGiven() {
    String sdp = new SdpBuilder("test")
        .build(List.of(track(49190, 97)), Map.of(97, "H264/90000"),
            Map.of(97, "packetization-mode=1"));

    assertTrue(sdp.contains("a=fmtp:97 packetization-mode=1"), sdp);
  }

  @Test
  void refusesToGuessADynamicPayloadType() {
    // 96..127 mean whatever the original session's SDP said, which the capture does not hold
    SdpBuilder builder = new SdpBuilder("test");
    List<RtpTrack> tracks = List.of(track(49190, 97));

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> builder.build(tracks, Map.of(), Map.of()));

    assertTrue(thrown.getMessage().contains("97"), thrown.getMessage());
  }

  @Test
  void readsMediaKindFromTheCallersEncoding() {
    String sdp =
        new SdpBuilder("test").build(List.of(track(49188, 96)), Map.of(96, "opus/48000/2"), Map.of());

    // "opus" is not in the static registry, so the kind has to come from the encoding name
    assertTrue(sdp.contains("m=audio 0 RTP/AVP 96"), sdp);
  }

  @Test
  void startsWithTheMandatorySessionLines() {
    String sdp = new SdpBuilder("bunny").build(List.of(track(5000, 0)), Map.of(), Map.of());

    assertTrue(sdp.startsWith("v=0\r\n"), sdp);
    assertTrue(sdp.contains("s=bunny"), sdp);
    assertTrue(sdp.contains("a=control:*"), sdp);
  }

  @Test
  void endsEveryLineWithCrlfAsSdpRequires() {
    String sdp = new SdpBuilder("test").build(List.of(track(5000, 0)), Map.of(), Map.of());

    for (String line : sdp.split("\r\n")) {
      assertEquals(line.strip(), line, "no stray whitespace around SDP lines");
    }
    assertTrue(sdp.endsWith("\r\n"), "the last line needs its terminator too");
  }
}
