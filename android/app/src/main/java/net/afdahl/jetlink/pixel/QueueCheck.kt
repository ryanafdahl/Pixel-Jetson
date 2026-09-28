package net.afdahl.jetlink.pixel

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Deterministic wrap/reset fixture, compared against the original numpy queues. */
object QueueCheck {
  fun run(verifyNative: (PolicyQueues) -> Unit = {}): JSONArray {
    val queue = PolicyQueues()
    val results = JSONArray()
    val payload = ByteArray(PolicyQueues.PAYLOAD_BYTES)
    val bytes = Wire.little(payload)
    for (frame in 1..150) {
      bytes.clear(); bytes.putInt(frame).putInt(if (frame == 141) 1 else 0)
      for (i in 0 until PolicyQueues.IMAGE_ROW) payload[8 + i] = (frame + i).toByte()
      for (i in 0 until PolicyQueues.IMAGE_ROW) payload[8 + PolicyQueues.IMAGE_ROW + i] = (3 * frame + i).toByte()
      bytes.position(8 + 2 * PolicyQueues.IMAGE_ROW)
      for (i in 0 until 8) bytes.putFloat(if ((frame + i) % 11 == 0) 1f else 0f)
      bytes.putFloat(1f).putFloat(0f).putFloat(frame / 7f).putFloat(-frame / 11f)
      for (i in 0 until PolicyQueues.FEATURE_ROW) bytes.putFloat(((frame * 37 + i) % 10007) / 1000f - 5f)
      queue.step(payload, payload.size)
      if (frame in setOf(1, 4, 5, 128, 132, 140, 141, 142, 150)) {
        verifyNative(queue)
        val row = JSONObject().put("frame", frame)
        fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        row.put("img", hash(queue.img)); row.put("big_img", hash(queue.bigImg))
        for ((name, values) in mapOf("features_buffer" to queue.features, "desire_pulse" to queue.desire,
          "traffic_convention" to queue.traffic, "action_t" to queue.action)) {
          val data = ByteArray(values.size * 4); Wire.little(data).asFloatBuffer().put(values)
          row.put(name, hash(data))
        }
        results.put(row)
      }
    }
    return results
  }
}
