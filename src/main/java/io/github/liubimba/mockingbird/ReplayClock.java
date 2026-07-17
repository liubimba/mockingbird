package io.github.liubimba.mockingbird;

/**
 * Works out how long to wait before releasing a captured packet, so that a pcap replay
 * reproduces the timing of the original stream.
 *
 * <p>The first packet anchors the clock: its capture timestamp is pinned to the wall-clock
 * instant it was released. Every later packet is then due at that anchor plus the gap
 * recorded in the capture. Deadlines are absolute rather than relative, so a packet that
 * goes out late does not push back the ones behind it and lateness cannot accumulate.
 *
 * <p>Not thread safe: a replay is driven by a single sender.
 */
final class ReplayClock {

  private static final long NANOS_PER_MICRO = 1_000L;
  private static final long MICROS_PER_SECOND = 1_000_000L;

  private static final long NO_ANCHOR = -1L;

  private long anchorPacketMicros = NO_ANCHOR;
  private long anchorWallNanos;

  /**
   * Combines the seconds and microseconds halves of a pcap timestamp into plain
   * microseconds since the epoch.
   *
   * <p>Kept separate from the jnetpcap header API on purpose: {@code PcapHeader.timestamp()}
   * is documented as a packed value but actually returns arithmetic microseconds, and
   * {@code toEpochMilli()} returns microseconds despite its name. Reading {@code tvSec()} and
   * {@code tvUsec()} and combining them here leaves no room for that ambiguity.
   */
  static long toMicros(long seconds, long microseconds) {
    return seconds * MICROS_PER_SECOND + microseconds;
  }

  /**
   * Returns how many nanoseconds to wait before sending the packet captured at
   * {@code packetMicros}, given the current {@code nowNanos} reading of the wall clock.
   *
   * @param packetMicros the packet's capture timestamp, in microseconds
   * @param nowNanos a {@link System#nanoTime()} style reading taken by the caller
   * @return the wait in nanoseconds, never negative
   */
  long delayNanosFor(long packetMicros, long nowNanos) {
    if (anchorPacketMicros == NO_ANCHOR) {
      anchorPacketMicros = packetMicros;
      anchorWallNanos = nowNanos;
      return 0L;
    }

    long elapsedInCaptureNanos = (packetMicros - anchorPacketMicros) * NANOS_PER_MICRO;
    long dueAtNanos = anchorWallNanos + elapsedInCaptureNanos;
    long waitNanos = dueAtNanos - nowNanos;

    return Math.max(0L, waitNanos);
  }

  /**
   * Drops the anchor so the next packet starts a fresh timeline. Used when a session pauses:
   * without this the replay would try to "catch up" on the whole pause.
   */
  void reset() {
    anchorPacketMicros = NO_ANCHOR;
  }
}
