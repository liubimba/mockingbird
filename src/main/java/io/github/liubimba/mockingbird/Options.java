package io.github.liubimba.mockingbird;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the server was asked to serve, read off the command line.
 *
 * <p>The {@code --map} option exists because of a hard limit rather than a design preference: a
 * capture cannot reveal what codec sits behind a dynamic payload type. See {@link SdpBuilder}.
 */
final class Options {

  static final String USAGE =
      """
      Usage: mockingbird --pcap <file> [options]

        --pcap <file>        capture to serve (required)
        --port <n>           RTSP port to listen on (default 5554)
        --name <text>        session name in the SDP (default: the capture's file name)
        --map <pt>:<enc>     encoding for a dynamic payload type, e.g. --map 97:H264/90000
        --fmtp <pt>:<params> format parameters for a payload type
        --sdp <file>         use this session description verbatim instead of building one
        --help               show this message

      Payload types 96-127 are dynamic: what they mean was agreed in the original session's
      SDP, which a capture does not contain. Describe them with --map, or supply the whole
      description with --sdp.
      """;

  private final Path pcap;
  private final Path sdp;
  private final int port;
  private final String sessionName;
  private final Map<Integer, String> encodings;
  private final Map<Integer, String> formatParameters;

  private Options(Path pcap, Path sdp, int port, String sessionName,
      Map<Integer, String> encodings, Map<Integer, String> formatParameters) {
    this.pcap = pcap;
    this.sdp = sdp;
    this.port = port;
    this.sessionName = sessionName;
    this.encodings = encodings;
    this.formatParameters = formatParameters;
  }

  Path pcap() {
    return pcap;
  }

  /** The session description to use verbatim, or {@code null} to build one from the capture. */
  Path sdp() {
    return sdp;
  }

  int port() {
    return port;
  }

  String sessionName() {
    return sessionName;
  }

  Map<Integer, String> encodings() {
    return encodings;
  }

  Map<Integer, String> formatParameters() {
    return formatParameters;
  }

  /**
   * Reads the command line.
   *
   * @throws IllegalArgumentException with a message meant for the user, if anything is missing,
   *     unknown or malformed
   */
  static Options parse(String[] args) {
    Path pcap = null;
    Path sdp = null;
    int port = 5554;
    String sessionName = null;
    Map<Integer, String> encodings = new LinkedHashMap<>();
    Map<Integer, String> formatParameters = new LinkedHashMap<>();

    for (int i = 0; i < args.length; i++) {
      String option = args[i];
      switch (option) {
        case "--pcap" -> pcap = Path.of(valueOf(args, ++i, option));
        case "--sdp" -> sdp = Path.of(valueOf(args, ++i, option));
        case "--port" -> port = parsePort(valueOf(args, ++i, option));
        case "--name" -> sessionName = valueOf(args, ++i, option);
        case "--map" -> putPayloadTypeEntry(encodings, valueOf(args, ++i, option), option);
        case "--fmtp" -> putPayloadTypeEntry(formatParameters, valueOf(args, ++i, option), option);
        default -> throw new IllegalArgumentException("unknown option: " + option);
      }
    }

    if (pcap == null) {
      throw new IllegalArgumentException("--pcap is required: there is nothing to serve without it");
    }
    if (sessionName == null) {
      sessionName = stripExtension(pcap.getFileName().toString());
    }

    return new Options(pcap, sdp, port, sessionName, encodings, formatParameters);
  }

  private static String valueOf(String[] args, int index, String option) {
    if (index >= args.length) {
      throw new IllegalArgumentException(option + " needs a value");
    }
    return args[index];
  }

  private static int parsePort(String value) {
    try {
      int port = Integer.parseInt(value);
      if (port < 1 || port > 65535) {
        throw new IllegalArgumentException("--port must be between 1 and 65535, got " + port);
      }
      return port;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("--port must be a number, got " + value);
    }
  }

  /** Splits "97:H264/90000" on its first colon only: the value may hold colons of its own. */
  private static void putPayloadTypeEntry(Map<Integer, String> into, String argument, String option) {
    int colon = argument.indexOf(':');
    if (colon < 1 || colon == argument.length() - 1) {
      throw new IllegalArgumentException(
          option + " wants <payload type>:<value>, got " + argument);
    }

    String payloadType = argument.substring(0, colon);
    try {
      into.put(Integer.valueOf(payloadType), argument.substring(colon + 1));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          option + " wants a numeric payload type, got " + payloadType);
    }
  }

  private static String stripExtension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }
}
