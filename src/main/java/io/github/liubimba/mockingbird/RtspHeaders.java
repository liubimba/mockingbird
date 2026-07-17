package io.github.liubimba.mockingbird;

import java.util.List;

/**
 * Reads the few RTSP header fields this server cares about.
 *
 * <p>RFC 2326 puts no order on header fields and makes their names case-insensitive, so every
 * lookup here scans for a name rather than trusting a line number. Clients really do differ:
 * ffmpeg sends {@code Accept} ahead of {@code CSeq}, others send it after.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2326#section-12">RFC 2326 section 12</a>
 */
final class RtspHeaders {

  private static final String CSEQ = "cseq:";
  private static final String CLIENT_PORT = "client_port=";
  private static final String TRACK_ID = "trackid=";

  private RtspHeaders() {}

  /** Returns the request method, or an empty string if the request line carries none. */
  static String method(String requestLine) {
    String trimmed = requestLine.strip();
    if (trimmed.isEmpty()) {
      return "";
    }

    int firstSpace = trimmed.indexOf(' ');
    return firstSpace < 0 ? trimmed : trimmed.substring(0, firstSpace);
  }

  /**
   * Returns the track a SETUP is for, taken from the control URL the SDP handed the client, or
   * {@code -1} if the URL names none.
   *
   * <p>Asking the URL is what makes a track's identity explicit. The alternative — assuming
   * clients SETUP in the order the SDP announced — holds for every client until it doesn't.
   */
  static int trackId(String requestLine) {
    int start = requestLine.toLowerCase().indexOf(TRACK_ID);
    if (start < 0) {
      return -1;
    }

    String value = requestLine.substring(start + TRACK_ID.length());
    int end = 0;
    while (end < value.length() && Character.isDigit(value.charAt(end))) {
      end++;
    }
    if (end == 0) {
      return -1;
    }
    return Integer.parseInt(value.substring(0, end));
  }

  /** Returns the {@code CSeq} of the request, or {@code 0} when the client omitted it. */
  static int cSeq(List<String> headers) {
    for (String header : headers) {
      if (header.stripLeading().toLowerCase().startsWith(CSEQ)) {
        String value = header.substring(header.indexOf(':') + 1).strip();
        return parseIntOrDefault(value, 0);
      }
    }
    return 0;
  }

  /**
   * Returns the first port of the {@code client_port} range the client asked RTP to be sent to,
   * or {@code -1} when no Transport header carries one.
   */
  static int clientPort(List<String> headers) {
    for (String header : headers) {
      int start = header.toLowerCase().indexOf(CLIENT_PORT);
      if (start < 0) {
        continue;
      }

      String value = header.substring(start + CLIENT_PORT.length());
      int end = indexOfFirst(value, '-', ';');
      if (end >= 0) {
        value = value.substring(0, end);
      }
      return parseIntOrDefault(value.strip(), -1);
    }
    return -1;
  }

  private static int indexOfFirst(String text, char a, char b) {
    int first = text.indexOf(a);
    int second = text.indexOf(b);
    if (first < 0) {
      return second;
    }
    if (second < 0) {
      return first;
    }
    return Math.min(first, second);
  }

  private static int parseIntOrDefault(String value, int fallback) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
