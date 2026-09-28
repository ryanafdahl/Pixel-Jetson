package net.afdahl.jetlink.pixel

import org.junit.Assert.*
import org.junit.Test

class FrameStatsTest {
  @Test fun averageBelowBudgetDoesNotHideSlowTail() {
    assertTrue(FrameStats.exchange(40.0, 60.0, 115, false).contains("OVER 50"))
    assertTrue(FrameStats.exchange(40.0, 49.0, 115, true).contains("WITHIN 50"))
  }
  @Test fun warmupAndOldFramesDoNotPolluteRollingAverage() {
    val stats = FrameStats()
    repeat(5) { stats.add(1000.0) }
    repeat(120) { stats.add(30.0) }
    assertTrue(stats.add(30.0).contains("30.0 ms"))
    assertEquals(126, stats.frames)
  }
  @Test(expected = IllegalArgumentException::class)
  fun invalidMeasurementCannotShowWithinBudget() { FrameStats.exchange(Double.NaN, 30.0, 10, false) }
}
