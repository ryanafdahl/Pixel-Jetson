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
  val narrowHistory: ByteBuffer = ByteBuffer.allocateDirect(5 * IMAGE_ROW)
  val wideHistory: ByteBuffer = ByteBuffer.allocateDirect(5 * IMAGE_ROW)
  private val imageScratch by lazy { ByteArray(2 * IMAGE_ROW) }
  private val wideScratch by lazy { ByteArray(2 * IMAGE_ROW) }
  val img: ByteArray get() = imageReference(narrowHistory, imageScratch)
  val bigImg: ByteArray get() = imageReference(wideHistory, wideScratch)
  private fun imageReference(history: ByteBuffer, result: ByteArray): ByteArray {
    history.position(imageHead * IMAGE_ROW); history.get(result, 0, IMAGE_ROW)
    history.position(((imageHead + 4) % 5) * IMAGE_ROW); history.get(result, IMAGE_ROW, IMAGE_ROW)
    return result
  }
  // Gather directly into the LiteRT input in native code during inference.
  val featureStorage: ByteBuffer = ByteBuffer.allocateDirect(128 * FEATURE_ROW * 4).order(ByteOrder.LITTLE_ENDIAN)
  private val featureHistory = featureStorage.asFloatBuffer()
  private val featureScratch by lazy { FloatArray(32 * FEATURE_ROW) }
  val features: FloatArray
    get() {
      for (i in 0 until 32) {
        featureHistory.position(((featureHead + 4 * i) % 128) * FEATURE_ROW)
        featureHistory.get(featureScratch, i * FEATURE_ROW, FEATURE_ROW)
      }
      return featureScratch
    }
  val desire = FloatArray(264)
  val traffic = FloatArray(2)
  val action = FloatArray(2)
  private val desireHistory = Array(132) { FloatArray(8) }
  var imageHead = 0
    private set
  var featureHead = 0
    private set
  private var desireHead = 0
  private val packed = FloatArray(PACKED_FLOATS)

  fun reset() {
    for (i in 0 until narrowHistory.capacity()) { narrowHistory.put(i, 0); wideHistory.put(i, 0) }
    for (i in 0 until featureHistory.capacity()) featureHistory.put(i, 0f)
    desireHistory.forEach { it.fill(0f) }
    imageHead = 0; featureHead = 0; desireHead = 0
  }

  fun step(payload: ByteArray, length: Int) {
    require(length == PAYLOAD_BYTES) { "Unexpected inference payload length" }
    val scalars = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    val flags = scalars.getInt(4)
    if ((flags and 1) != 0) reset()
    narrowHistory.position(imageHead * IMAGE_ROW); narrowHistory.put(payload, 8, IMAGE_ROW)
    wideHistory.position(imageHead * IMAGE_ROW); wideHistory.put(payload, 8 + IMAGE_ROW, IMAGE_ROW)
    imageHead = (imageHead + 1) % 5
    scalars.position(8 + 2 * IMAGE_ROW)
    scalars.asFloatBuffer().get(packed)
    // Match Jetson's fp16 queue storage with ARM vector rounding.
    NativeRuntime.roundHalf(packed)
    packed.copyInto(desireHistory[desireHead], 0, 0, 8)
    desireHead = (desireHead + 1) % 132
    packed.copyInto(traffic, 0, 8, 10); packed.copyInto(action, 0, 10, 12)
    featureHistory.position(featureHead * FEATURE_ROW)
    featureHistory.put(packed, 12, FEATURE_ROW)
    featureHead = (featureHead + 1) % 128
    for (i in 0 until 33) for (j in 0 until 8) {
      var value = Float.NEGATIVE_INFINITY
      for (k in 0 until 4) value = maxOf(value, desireHistory[(desireHead + i * 4 + k) % 132][j])
      desire[i * 8 + j] = value
    }
  }
}
