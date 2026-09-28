package net.afdahl.jetlink.pixel

object NativeRuntime {
  init { System.loadLibrary("pixel_runtime") }
  external fun create(path: String, libs: String): Long
  external fun write(handle: Long, index: Int, data: Any, floats: Boolean)
  external fun run(handle: Long): FloatArray
  external fun destroy(handle: Long)
  external fun roundHalf(values: FloatArray)
}
