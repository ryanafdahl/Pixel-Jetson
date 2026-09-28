package net.afdahl.jetlink.pixel

import org.junit.Assert.*
import org.junit.Test

class StageTimingsTest {
  @Test fun excludesWarmupAndKeepsIndependentStageTotals() {
    val stats = StageTimings()
    repeat(5) { stats.record(LongArray(9) { 999_000_000 }) }
    assertEquals(0, stats.samples)
    stats.record(LongArray(9) { (it + 1) * 1_000_000L })
    stats.record(LongArray(9) { (it + 1) * 3_000_000L })
    assertEquals(2, stats.samples)
    assertEquals(2.0, stats.meanMs(0), 0.00001)
    assertEquals(18.0, stats.meanMs(8), 0.00001)
    assertEquals(27.0, stats.maxMs(8), 0.00001)
  }
  @Test(expected = IllegalArgumentException::class)
  fun negativeDurationIsRejected() { StageTimings().record(LongArray(9) { -1 }) }
}
