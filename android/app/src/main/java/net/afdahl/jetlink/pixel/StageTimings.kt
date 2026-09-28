package net.afdahl.jetlink.pixel

/** Nanosecond stage totals. First five accepted frames are warmup. */
class StageTimings {
  companion object {
    val names = listOf("receive_with_client_wait", "history", "input_write", "npu_invoke", "output_read",
      "validation", "telemetry", "reply_pack", "reply_send")
  }
  private val sums = DoubleArray(names.size)
  private val maxima = LongArray(names.size)
  var accepted = 0
    private set
  val samples: Int get() = maxOf(0, accepted - 5)
  fun record(values: LongArray) {
    require(values.size == names.size && values.all { it >= 0 })
    accepted++
    if (accepted <= 5) return
    values.forEachIndexed { i, n -> sums[i] += n; maxima[i] = maxOf(maxima[i], n) }
  }
  fun meanMs(i: Int): Double = if (samples == 0) 0.0 else sums[i] / samples / 1e6
  fun maxMs(i: Int): Double = maxima[i] / 1e6
}
