package io.github.liubimba.mockingbird;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class UdpFrameTest {

  private static final int UDP = 17;
  private static final int TCP = 6;

  /**
   * Builds an Ethernet + IPv4 + UDP frame.
   *
   * @param ihlWords IPv4 header length in 32-bit words; 5 means no options
   * @param vlan whether to insert an 802.1Q tag ahead of the EtherType
   */
  private static byte[] frame(int ihlWords, int protocol, int srcPort, int dstPort,
      int payloadLength, boolean vlan) {
    int ethLen = vlan ? 18 : 14;
    int ipLen = ihlWords * 4;
    byte[] f = new byte[ethLen + ipLen + 8 + payloadLength];

    if (vlan) {
      f[12] = (byte) 0x81; // 802.1Q tag
      f[13] = (byte) 0x00;
      f[16] = (byte) 0x08; // EtherType IPv4, after the tag
      f[17] = (byte) 0x00;
    } else {
      f[12] = (byte) 0x08;
      f[13] = (byte) 0x00;
    }

    f[ethLen] = (byte) (0x40 | ihlWords); // version 4, header length
    f[ethLen + 9] = (byte) protocol;

    int udp = ethLen + ipLen;
    f[udp] = (byte) (srcPort >>> 8);
    f[udp + 1] = (byte) srcPort;
    f[udp + 2] = (byte) (dstPort >>> 8);
    f[udp + 3] = (byte) dstPort;
    return f;
  }

  @Test
  void readsPortsFromAPlainFrame() {
    UdpFrame parsed = UdpFrame.parse(frame(5, UDP, 5000, 49190, 100, false));

    assertNotNull(parsed);
    assertEquals(5000, parsed.sourcePort());
    assertEquals(49190, parsed.destinationPort());
  }

  @Test
  void payloadStartsAfterEthernetIpAndUdpHeaders() {
    UdpFrame parsed = UdpFrame.parse(frame(5, UDP, 1, 2, 100, false));

    // 14 Ethernet + 20 IPv4 + 8 UDP
    assertEquals(42, parsed.payloadOffset());
    assertEquals(100, parsed.payloadLength());
  }

  @Test
  void ipv4OptionsPushThePayloadAlong() {
    // IHL 6 means one 32-bit word of options, so the IPv4 header is 24 bytes, not 20
    UdpFrame parsed = UdpFrame.parse(frame(6, UDP, 1, 2, 50, false));

    assertNotNull(parsed);
    assertEquals(46, parsed.payloadOffset());
    assertEquals(50, parsed.payloadLength());
  }

  @Test
  void readsThroughAVlanTag() {
    UdpFrame parsed = UdpFrame.parse(frame(5, UDP, 1234, 49188, 20, true));

    assertNotNull(parsed);
    assertEquals(49188, parsed.destinationPort());
    assertEquals(46, parsed.payloadOffset()); // 14 + 4 VLAN + 20 + 8
  }

  @Test
  void rejectsFramesThatAreNotIpv4() {
    byte[] arp = frame(5, UDP, 1, 2, 10, false);
    arp[12] = (byte) 0x08;
    arp[13] = (byte) 0x06; // EtherType ARP

    assertNull(UdpFrame.parse(arp));
  }

  @Test
  void rejectsFramesThatAreNotUdp() {
    assertNull(UdpFrame.parse(frame(5, TCP, 1, 2, 10, false)));
  }

  @Test
  void rejectsFramesTooShortToParse() {
    assertNull(UdpFrame.parse(new byte[20]));
  }

  @Test
  void rejectsAnImpossibleHeaderLength() {
    // IHL below 5 would mean an IPv4 header shorter than its own fixed fields
    assertNull(UdpFrame.parse(frame(4, UDP, 1, 2, 10, false)));
  }

  @Test
  void rejectsAFrameTruncatedInsideItsUdpHeader() {
    byte[] truncated = new byte[40]; // room for Ethernet + IPv4 but not all of UDP
    truncated[12] = (byte) 0x08;
    truncated[13] = (byte) 0x00;
    truncated[14] = (byte) 0x45;
    truncated[23] = (byte) UDP;

    assertNull(UdpFrame.parse(truncated));
  }
}
