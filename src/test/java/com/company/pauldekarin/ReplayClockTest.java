package com.company.pauldekarin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReplayClockTest {

  private static final long WALL_START = 1_000_000_000L; // arbitrary nanoTime origin

  @Test
  void firstPacketIsReleasedImmediately() {
    ReplayClock clock = new ReplayClock();

    long delay = clock.delayNanosFor(5_000_000L, WALL_START);

    assertEquals(0L, delay, "the first packet anchors the clock and must not be delayed");
  }

  @Test
  void secondPacketWaitsTheCaptureGap() {
    ReplayClock clock = new ReplayClock();
    clock.delayNanosFor(5_000_000L, WALL_START);

    // captured 20 ms after the first packet, asked for at the very same wall instant
    long delay = clock.delayNanosFor(5_020_000L, WALL_START);

    assertEquals(20_000_000L, delay, "a 20 ms capture gap must become a 20 ms delay");
  }

  @Test
  void delayShrinksByTimeAlreadySpent() {
    ReplayClock clock = new ReplayClock();
    clock.delayNanosFor(5_000_000L, WALL_START);

    // 20 ms gap in the capture, but 8 ms of wall time already went by
    long delay = clock.delayNanosFor(5_020_000L, WALL_START + 8_000_000L);

    assertEquals(12_000_000L, delay, "delay must account for time already elapsed");
  }

  @Test
  void latePacketIsNotDelayed() {
    ReplayClock clock = new ReplayClock();
    clock.delayNanosFor(5_000_000L, WALL_START);

    // we are already 50 ms behind a packet captured 20 ms in
    long delay = clock.delayNanosFor(5_020_000L, WALL_START + 50_000_000L);

    assertEquals(0L, delay, "a packet we are late for must go out at once, never a negative sleep");
  }

  @Test
  void pacingIsAnchoredToStartSoItDoesNotDrift() {
    ReplayClock clock = new ReplayClock();
    clock.delayNanosFor(0L, WALL_START);

    // Each packet is 10 ms apart in the capture. Every release runs 5 ms late.
    // Deadlines must stay anchored to the start, so lateness must not accumulate.
    clock.delayNanosFor(10_000L, WALL_START + 15_000_000L);
    long delay = clock.delayNanosFor(20_000L, WALL_START + 25_000_000L);

    // packet 3 is due at start+20ms; now is start+25ms -> already late, no sleep
    assertEquals(0L, delay, "deadlines are absolute, so drift must not compound");
  }

  @Test
  void resetReanchorsTheClock() {
    ReplayClock clock = new ReplayClock();
    clock.delayNanosFor(5_000_000L, WALL_START);

    clock.reset(); // e.g. the session was paused

    // after a reset the next packet becomes the new anchor, whatever its timestamp
    long delay = clock.delayNanosFor(9_000_000L, WALL_START + 3_600_000_000L);

    assertEquals(0L, delay, "reset must drop the old anchor so a pause is not slept off");
  }

  @Test
  void secondsAndMicrosecondsCombineIntoMicroseconds() {
    assertEquals(1_500_000L, ReplayClock.toMicros(1L, 500_000L));
  }

  @Test
  void combiningTimestampsDoesNotOverflowOnRealEpochValues() {
    long micros = ReplayClock.toMicros(1_760_000_000L, 999_999L);

    assertTrue(micros > 1_760_000_000_000_000L, "epoch seconds must be widened before scaling");
  }
}
