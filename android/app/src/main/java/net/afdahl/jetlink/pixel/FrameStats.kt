package net.afdahl.jetlink.pixel

import java.util.Locale

/** Phone work is not the complete USB exchange; never conflate their budgets. */
class FrameStats {
  private val times = ArrayDeque<Double>()
  var frames = 0
    private set
  fun add(ms: Double): String {
    require(ms.isFinite() && ms >= 0)
    frames++
    if (frames > 5) { times.addLast(ms); if (times.size > 120) times.removeFirst() }
    return if (times.isEmpty()) "Phone work: warming up ($frames/5)"
    else String.format(Locale.US, "Phone work avg %.1f ms • %d frames", times.average(), frames)
  }
  companion object {
    fun exchange(avg: Double, p95: Double, samples: Int, complete: Boolean): String {
      require(avg.isFinite() && p95.isFinite() && avg >= 0 && p95 >= 0 && samples > 0)
      val status = if (avg <= 50 && p95 <= 50) "WITHIN" else "OVER"
      return String.format(Locale.US,
        "%s • USB exchange\nAvg %.1f ms • p95 %.1f ms\n%s 50 ms budget • %d measured frames\nSynthetic test; camera processing excluded",
        if (complete) "TEST COMPLETE" else "LIVE PARKED TEST", avg, p95, status, samples)
    }
  }
}
