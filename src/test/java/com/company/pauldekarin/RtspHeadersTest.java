package com.company.pauldekarin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class RtspHeadersTest {

  @Test
  void findsCSeqWhereverItSits() {
    // ffmpeg puts Accept before CSeq; RFC 2326 allows headers in any order
    List<String> headers = List.of("Accept: application/sdp", "CSeq: 2", "User-Agent: Lavf60.16.100");

    assertEquals(2, RtspHeaders.cSeq(headers));
  }

  @Test
  void findsCSeqOnTheFirstLine() {
    assertEquals(7, RtspHeaders.cSeq(List.of("CSeq: 7", "Session: abc")));
  }

  @Test
  void matchesHeaderNameCaseInsensitively() {
    // RFC 2326 header names are case-insensitive
    assertEquals(3, RtspHeaders.cSeq(List.of("cseq: 3")));
  }

  @Test
  void toleratesMissingCSeq() {
    assertEquals(0, RtspHeaders.cSeq(List.of("Session: abc")));
  }

  @Test
  void readsFirstClientPortOfTheRange() {
    List<String> headers =
        List.of("CSeq: 3", "Transport: RTP/AVP/UDP;unicast;client_port=49188-49189");

    assertEquals(49188, RtspHeaders.clientPort(headers));
  }

  @Test
  void findsClientPortAmongOtherTransportParameters() {
    List<String> headers =
        List.of("Transport: RTP/AVP;unicast;mode=play;client_port=5000-5001;ttl=127");

    assertEquals(5000, RtspHeaders.clientPort(headers));
  }

  @Test
  void reportsNoClientPortWhenTransportIsAbsent() {
    assertEquals(-1, RtspHeaders.clientPort(List.of("CSeq: 4")));
  }

  @Test
  void readsMethodFromRequestLine() {
    assertEquals("DESCRIBE", RtspHeaders.method("DESCRIBE rtsp://localhost:5554/bunny RTSP/1.0"));
  }

  @Test
  void toleratesBlankRequestLine() {
    assertEquals("", RtspHeaders.method("   "));
  }
}
