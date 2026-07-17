package io.github.liubimba.mockingbird;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OptionsTest {

  @Test
  void readsTheCapturePath() {
    Options options = Options.parse(new String[] {"--pcap", "bunny.pcapng"});

    assertEquals("bunny.pcapng", options.pcap().toString());
  }

  @Test
  void servesOn5554UnlessToldOtherwise() {
    assertEquals(5554, Options.parse(new String[] {"--pcap", "x.pcap"}).port());
    assertEquals(8554, Options.parse(new String[] {"--pcap", "x.pcap", "--port", "8554"}).port());
  }

  @Test
  void namesTheSessionAfterTheCaptureByDefault() {
    Options options = Options.parse(new String[] {"--pcap", "/captures/bunny.pcapng"});

    assertEquals("bunny", options.sessionName());
  }

  @Test
  void takesAnExplicitSessionName() {
    Options options = Options.parse(new String[] {"--pcap", "x.pcap", "--name", "camera-1"});

    assertEquals("camera-1", options.sessionName());
  }

  @Test
  void readsAPayloadTypeMapping() {
    Options options = Options.parse(new String[] {"--pcap", "x.pcap", "--map", "97:H264/90000"});

    assertEquals("H264/90000", options.encodings().get(97));
  }

  @Test
  void readsSeveralMappings() {
    Options options = Options.parse(new String[] {
        "--pcap", "x.pcap", "--map", "97:H264/90000", "--map", "96:mpeg4-generic/12000/2"});

    assertEquals("H264/90000", options.encodings().get(97));
    assertEquals("mpeg4-generic/12000/2", options.encodings().get(96));
  }

  @Test
  void readsFormatParametersKeepingTheirColons() {
    // fmtp values contain colons and equals signs, so only the first colon separates the key
    Options options = Options.parse(
        new String[] {"--pcap", "x.pcap", "--fmtp", "97:packetization-mode=1;profile-level-id=42C01E"});

    assertEquals("packetization-mode=1;profile-level-id=42C01E", options.formatParameters().get(97));
  }

  @Test
  void demandsACapture() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {}));

    assertTrue(thrown.getMessage().contains("--pcap"), thrown.getMessage());
  }

  @Test
  void rejectsAnOptionMissingItsValue() {
    assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"--pcap"}));
  }

  @Test
  void rejectsAMappingThatIsNotPayloadTypeThenEncoding() {
    assertThrows(IllegalArgumentException.class,
        () -> Options.parse(new String[] {"--pcap", "x.pcap", "--map", "H264"}));
  }

  @Test
  void rejectsAnUnknownOption() {
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
        () -> Options.parse(new String[] {"--pcap", "x.pcap", "--turbo"}));

    assertTrue(thrown.getMessage().contains("--turbo"), thrown.getMessage());
  }

  @Test
  void takesAnSdpFileToUseVerbatim() {
    Options options = Options.parse(new String[] {"--pcap", "x.pcap", "--sdp", "stream.sdp"});

    assertEquals("stream.sdp", options.sdp().toString());
  }
}
