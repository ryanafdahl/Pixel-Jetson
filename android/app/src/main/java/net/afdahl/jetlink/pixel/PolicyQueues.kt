package net.afdahl.jetlink.pixel

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fixed TGC-v2 signature. History ordering matches jetlink.queues.PolicyQueues. */
class PolicyQueues {
  companion object {
    const val IMAGE_ROW = 6 * 128 * 256
    const val FEATURE_ROW = 16384
    const val PACKED_FLOATS = 8 + 2 + 2 + FEATURE_ROW
    const val PAYLOAD_BYTES = 8 + 2 * IMAGE_ROW + 4 * PACKED_FLOATS
  }
  val img = ByteArray(2 * IMAGE_ROW)
  val bigImg = ByteArray(2 * IMAGE_ROW)
  val features = FloatArray(32 * FEATURE_ROW)
  val desire = FloatArray(264)
  val traffic = FloatArray(2)
  val action = FloatArray(2)
  private val narrowHistory = Array(5) { ByteArray(IMAGE_ROW) }
  private val wideHistory = Array(5) { ByteArray(IMAGE_ROW) }
  private val featureHistory = Array(128) { FloatArray(FEATURE_ROW) }
  private val desireHistory = Array(132) { FloatArray(8) }
  private var imageHead = 0
  private var featureHead = 0
  private var desireHead = 0
  private val packed = FloatArray(PACKED_FLOATS)

  fun reset() {
    narrowHistory.forEach { it.fill(0) }; wideHistory.forEach { it.fill(0) }
    featureHistory.forEach { it.fill(0f) }; desireHistory.forEach { it.fill(0f) }
    imageHead = 0; featureHead = 0; desireHead = 0
  }

  fun step(payload: ByteArray, length: Int) {
    require(length == PAYLOAD_BYTES) { "Unexpected inference payload length" }
    val scalars = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    val flags = scalars.getInt(4)
    if ((flags and 1) != 0) reset()
    payload.copyInto(narrowHistory[imageHead], 0, 8, 8 + IMAGE_ROW)
    payload.copyInto(wideHistory[imageHead], 0, 8 + IMAGE_ROW, 8 + 2 * IMAGE_ROW)
    imageHead = (imageHead + 1) % 5
    narrowHistory[imageHead].copyInto(img, 0)
    narrowHistory[(imageHead + 4) % 5].copyInto(img, IMAGE_ROW)
    wideHistory[imageHead].copyInto(bigImg, 0)
    wideHistory[(imageHead + 4) % 5].copyInto(bigImg, IMAGE_ROW)
    scalars.position(8 + 2 * IMAGE_ROW)
    scalars.asFloatBuffer().get(packed)
    // Match Jetson's fp16 queue storage with ARM vector rounding.
    NativeRuntime.roundHalf(packed)
    packed.copyInto(desireHistory[desireHead], 0, 0, 8)
    desireHead = (desireHead + 1) % 132
    packed.copyInto(traffic, 0, 8, 10); packed.copyInto(action, 0, 10, 12)
    packed.copyInto(featureHistory[featureHead], 0, 12, PACKED_FLOATS)
    featureHead = (featureHead + 1) % 128
    for (i in 0 until 32) featureHistory[(featureHead + 4 * i) % 128].copyInto(features, i * FEATURE_ROW)
    for (i in 0 until 33) for (j in 0 until 8) {
      var value = Float.NEGATIVE_INFINITY
      for (k in 0 until 4) value = maxOf(value, desireHistory[(desireHead + i * 4 + k) % 132][j])
      desire[i * 8 + j] = value
    }
  }
}
