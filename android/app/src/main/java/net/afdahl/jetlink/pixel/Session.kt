package net.afdahl.jetlink.pixel

import org.json.JSONObject
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

/** Only isolated parked clients can request this unqualified model. */
class Session(private val engine: PixelEngine, private val power: PowerTelemetry, private val linkKind: String,
              private val report: (String) -> Unit, private val dashboard: (String?, String?) -> Unit) {
  private var ready = false
  private var frames = 0
  private var lastSequence: Int? = null
  private var previousReply: ByteArray? = null
  private var lastFrame = 0
  private var stats = FrameStats()

  private fun state() = JSONObject().put("device", "${Build.MODEL} / ${Build.SOC_MODEL}")
    .put("validation", "parked_only").put("frames_served", frames)
    .put("power", power.snapshot()).put("usb_host_session", linkKind == "USB")

  private fun json(link: Link, type: Int, seq: Int, data: JSONObject) =
    Wire.send(link, type, seq, data.toString().toByteArray(Charsets.UTF_8))

  fun run(link: Link, stopped: AtomicBoolean) {
    engine.queues.reset()
    while (!stopped.get()) {
      val msg = Wire.receive(link)
      when (msg.type) {
        1 -> json(link, 2, msg.sequence, state().put("protocol", 2).put("engine_state", "parked_test_only")
          .put("loaded", engine.spec.getString("sha256")))
        3 -> {
          val request = JSONObject(String(msg.payload, Charsets.UTF_8))
          ready = request.optString("validation_mode") == "parked" &&
            request.optString("sha256") == engine.spec.getString("sha256") &&
            request.optLong("nbytes") == engine.spec.getLong("nbytes") && request.optInt("frame_skip", 4) == 4
          engine.queues.reset(); lastSequence = null; previousReply = null
          if (ready) {
            frames = 0; stats = FrameStats()
            dashboard("Phone work: waiting for frames", "Connected • waiting for measured USB exchange\n50 ms budget • no result yet")
          }
          val reply = JSONObject().put("state", if (ready) "ready" else "failed")
            .put("detail", if (ready) "Isolated parked validation only" else "Model accuracy unqualified: parked test client and exact model required")
            .put("sha256", engine.spec.getString("sha256")).put("validation", "parked_only")
          if (ready) reply.put("spec", engine.spec)
          json(link, 4, msg.sequence, reply)
          report(if (ready) "Parked test connected" else "Driving request refused: model validation pending")
        }
        8 -> {
          // Replaying a reply must not advance recurrent history a second time.
          if (msg.sequence == lastSequence && previousReply != null) {
            Wire.send(link, 9, msg.sequence, previousReply!!); continue
          }
          if (lastSequence != null && Integer.compareUnsigned(msg.sequence, lastSequence!!) < 0) {
            json(link, 14, msg.sequence, JSONObject().put("error", "stale_sequence")); continue
          }
          val start = System.nanoTime()
          val frame = if (msg.payload.size >= 4) Wire.little(msg.payload).int else 0
          var status = if (!ready) 1 else if (msg.payload.size != PolicyQueues.PAYLOAD_BYTES) 2 else 0
          var queueUs = 0; var inferUs = 0
          var values = FloatArray(0)
          if (status == 0) {
            try {
              engine.queues.step(msg.payload, msg.payload.size)
              val queued = System.nanoTime()
              queueUs = ((queued - start) / 1000).toInt()
              values = engine.infer()
              inferUs = ((System.nanoTime() - queued) / 1000).toInt()
              if (values.any { !it.isFinite() }) { status = 4; values = FloatArray(0); ready = false }
            } catch (e: Exception) {
              status = 3; ready = false; values = FloatArray(0)
              report("Inference failed: ${e.message}")
            }
          }
          val totalUs = ((System.nanoTime() - start) / 1000).toInt()
          val wantState = msg.payload.size >= 8 && Wire.little(msg.payload).getInt(4) and 2 != 0
          val telemetry = if (wantState) state().toString().toByteArray() else ByteArray(0)
          val reply = ByteArray(20 + values.size * 4 + telemetry.size)
          val bytes = Wire.little(reply)
          bytes.putInt(frame).putInt(status).putInt(inferUs).putInt(queueUs).putInt(totalUs)
          values.forEach { bytes.putFloat(it) }; bytes.put(telemetry)
          if (linkKind == "USB" && frames == 0) report("First frame $frame: status=$status, outputs=${values.size}, TPU=${inferUs / 1000.0} ms; sending ${reply.size + 32} bytes")
          Wire.send(link, 9, msg.sequence, reply)
          lastSequence = msg.sequence; previousReply = reply; lastFrame = frame
          if (status == 0) {
            frames++
            val display = stats.add(totalUs / 1000.0)
            if (frames <= 5 || frames % 10 == 0) dashboard(display, null)
            if (frames % 20 == 0) report("Parked frames: $frames | TPU ${inferUs / 1000.0} ms | queue ${queueUs / 1000.0} ms")
          }
        }
        12 -> {
          if (ready && msg.payload.isNotEmpty()) {
            val metrics = JSONObject(String(msg.payload, Charsets.UTF_8)).optJSONObject("bench_metrics")
            if (metrics != null && linkKind == "USB") {
              val display = FrameStats.exchange(metrics.getDouble("mean_ms"), metrics.getDouble("p95_ms"),
                metrics.getInt("samples"), metrics.optBoolean("complete"))
              dashboard(null, display)
              if (metrics.optBoolean("complete")) report(display.replace('\n', ' '))
            }
          }
          json(link, 13, msg.sequence, state().put("last_frame", lastFrame))
        }
        15 -> Wire.send(link, 16, msg.sequence, msg.payload)
        17 -> json(link, 18, msg.sequence, JSONObject().put("ok", false).put("detail", "Close the Android app to stop"))
        else -> json(link, 14, msg.sequence, JSONObject().put("error", "unsupported_message"))
      }
    }
  }
}
