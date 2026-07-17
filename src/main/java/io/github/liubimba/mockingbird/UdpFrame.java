package io.github.liubimba.mockingbird;

/**
 * A UDP datagram located inside a captured Ethernet frame.
 *
 * <p>The offsets are worked out per frame rather than assumed. A capture off a real network
 * will not always hand over the textbook 14 + 20 + 8 layout: IPv4 headers carry options, and
 * switches leave 802.1Q tags behind. Reading a fixed offset works right up until it silently
 * reads the wrong bytes.
 *
 * <p>Only Ethernet + IPv4 + UDP is understood. IPv6, tunnelling and non-Ethernet link types
 * are out of scope and come back as {@code null} rather than as a wrong answer.
 */
record UdpFrame(int sourcePort, int destinationPort, int payloadOffset, int payloadLength) {

  private static final int ETHERNET_HEADER_LENGTH = 14;
  private static final int ETHERTYPE_OFFSET = 12;
  private static final int VLAN_TAG_LENGTH = 4;

  private static final int ETHERTYPE_IPV4 = 0x0800;
  private static final int ETHERTYPE_VLAN = 0x8100;

  private static final int IPV4_PROTOCOL_OFFSET = 9;
  private static final int IPV4_MIN_HEADER_WORDS = 5;
  private static final int PROTOCOL_UDP = 17;

  private static final int UDP_HEADER_LENGTH = 8;

  /** Parses the frame, or returns {@code null} if it does not carry a UDP datagram we can read. */
  static UdpFrame parse(byte[] frame) {
    int ipOffset = ipv4Offset(frame);
    if (ipOffset < 0) {
      return null;
    }

    int headerWords = frame[ipOffset] & 0x0F;
    if (headerWords < IPV4_MIN_HEADER_WORDS) {
      return null;
    }

    int ipHeaderLength = headerWords * 4;
    if (frame.length < ipOffset + ipHeaderLength) {
      return null;
    }
    if ((frame[ipOffset + IPV4_PROTOCOL_OFFSET] & 0xFF) != PROTOCOL_UDP) {
      return null;
    }

    int udpOffset = ipOffset + ipHeaderLength;
    int payloadOffset = udpOffset + UDP_HEADER_LENGTH;
    if (frame.length < payloadOffset) {
      return null;
    }

    return new UdpFrame(
        readUnsignedShort(frame, udpOffset),
        readUnsignedShort(frame, udpOffset + 2),
        payloadOffset,
        frame.length - payloadOffset);
  }

  /** Returns where the IPv4 header starts, stepping over a VLAN tag, or -1 if this is not IPv4. */
  private static int ipv4Offset(byte[] frame) {
    if (frame.length < ETHERNET_HEADER_LENGTH) {
      return -1;
    }

    int etherType = readUnsignedShort(frame, ETHERTYPE_OFFSET);
    int ipOffset = ETHERNET_HEADER_LENGTH;

    if (etherType == ETHERTYPE_VLAN) {
      if (frame.length < ETHERNET_HEADER_LENGTH + VLAN_TAG_LENGTH) {
        return -1;
      }
      etherType = readUnsignedShort(frame, ETHERTYPE_OFFSET + VLAN_TAG_LENGTH);
      ipOffset += VLAN_TAG_LENGTH;
    }

    if (etherType != ETHERTYPE_IPV4 || frame.length <= ipOffset) {
      return -1;
    }
    return ipOffset;
  }

  /** Reads a big-endian 16-bit field, the byte order every header here uses. */
  private static int readUnsignedShort(byte[] bytes, int offset) {
    return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
  }
}
