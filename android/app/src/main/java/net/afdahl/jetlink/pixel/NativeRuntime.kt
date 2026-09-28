package net.afdahl.jetlink.pixel

object NativeRuntime {
  init { System.loadLibrary("pixel_runtime") }
  external fun create(path: String, libs: String, performanceMode: Int): Long
  external fun write(handle: Long, index: Int, data: Any, floats: Boolean)
  external fun writeImageHistory(handle: Long, index: Int, history: java.nio.ByteBuffer, head: Int)
  external fun verifyImageInput(handle: Long, index: Int, expected: ByteArray): Boolean
  external fun writeFeatureHistory(handle: Long, history: java.nio.ByteBuffer, head: Int)
  external fun verifyFeatureInput(handle: Long, expected: FloatArray): Boolean
  external fun run(handle: Long, output: FloatArray, timingsNs: LongArray)
  external fun destroy(handle: Long)
  external fun roundHalf(values: FloatArray)
}
