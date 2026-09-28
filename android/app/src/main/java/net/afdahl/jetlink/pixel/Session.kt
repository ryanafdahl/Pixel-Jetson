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
  private var previousReply = false
  private val replyBuffer = InferenceReply()
  private val inputBuffer = ByteArray(PolicyQueues.PAYLOAD_BYTES)
  private val headerBuffer = ByteArray(32)
  private val paddingBuffer = ByteArray(16384)
  private var lastFrame = 0
  private var stats = FrameStats()
  private var stages = StageTimings()
  private val stageNs = LongArray(9)

  private fun profile(): JSONObject {
    val result = JSONObject().put("samples", stages.samples)
    StageTimings.names.forEachIndexed { i, name -> result.put(name, JSONObject().put("mean_ms", stages.meanMs(i)).put("max_ms", stages.maxMs(i))) }
    return result
  }

  private fun state() = JSONObject().put("device", "${Build.MODEL} / ${Build.SOC_MODEL}")
    .put("validation", "parked_only").put("frames_served", frames)
    .put("power", power.snapshot()).put("usb_host_session", linkKind == "USB")
    .put("usb_io", "queued-direct-reply-v2").put("phone_pipeline", "direct-history-v3").put("performance_mode", engine.performanceMode)

  private fun json(link: Link, type: Int, seq: Int, data: JSONObject) =
    Wire.send(link, type, seq, data.toString().toByteArray(Charsets.UTF_8))

  fun run(link: Link, stopped: AtomicBoolean) {
    engine.queues.reset()
    while (!stopped.get()) {
      val receiveStart = System.nanoTime()
      val msg = Wire.receive(link, headerBuffer, inputBuffer, paddingBuffer)
      val receivedAt = System.nanoTime()
      when (msg.type) {
        1 -> json(link, 2, msg.sequence, state().put("protocol", 2).put("engine_state", "parked_test_only")
          .put("loaded", engine.spec.getString("sha256")))
        3 -> {
          val request = JSONObject(String(msg.payload, Charsets.UTF_8))
          ready = request.optString("validation_mode") == "parked" &&
            request.optString("sha256") == engine.spec.getString("sha256") &&
            request.optLong("nbytes") == engine.spec.getLong("nbytes") && request.optInt("frame_skip", 4) == 4
          engine.queues.reset(); lastSequence = null; previousReply = false
          if (ready) {
            frames = 0; stats = FrameStats(); stages = StageTimings()
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
          if (msg.sequence == lastSequence && previousReply) {
            replyBuffer.send(link); continue
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
          val validatedAt = System.nanoTime()
          val totalUs = ((validatedAt - start) / 1000).toInt()
          val wantState = msg.payload.size >= 8 && Wire.little(msg.payload).getInt(4) and 2 != 0
          val telemetry = if (wantState) state().toString().toByteArray() else ByteArray(0)
          val telemetryAt = System.nanoTime()
          replyBuffer.encode(link.transmitPacketSize, msg.sequence, frame, status, inferUs, queueUs, totalUs, values, telemetry)
          val packedAt = System.nanoTime()
          if (linkKind == "USB" && frames == 0) report("First frame $frame: status=$status, outputs=${values.size}, TPU=${inferUs / 1000.0} ms; async USB reply")
          replyBuffer.send(link)
          val sentAt = System.nanoTime()
          lastSequence = msg.sequence; previousReply = true; lastFrame = frame
          if (status == 0) {
            stageNs[0] = receivedAt - receiveStart
            stageNs[1] = queueUs * 1000L
            stageNs[2] = engine.inputWriteNs
            stageNs[3] = engine.nativeTimingsNs[0]
            stageNs[4] = engine.nativeTimingsNs[1]
            stageNs[5] = maxOf(0L, validatedAt - start - (queueUs + inferUs) * 1000L)
            stageNs[6] = telemetryAt - validatedAt
            stageNs[7] = packedAt - telemetryAt
            stageNs[8] = sentAt - packedAt
            stages.record(stageNs)
            frames++
            stats.record(totalUs / 1000.0)
            if (frames <= 5 || frames % 10 == 0) dashboard(stats.display(), null)
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
          json(link, 13, msg.sequence, state().put("last_frame", lastFrame).put("phone_profile", profile()))
        }
        15 -> Wire.send(link, 16, msg.sequence, msg.payload)
        17 -> json(link, 18, msg.sequence, JSONObject().put("ok", false).put("detail", "Close the Android app to stop"))
        else -> json(link, 14, msg.sequence, JSONObject().put("error", "unsupported_message"))
      }
    }
  }
}
