package net.afdahl.jetlink.pixel

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class PixelEngine(context: Context) : AutoCloseable {
  val manifest = JSONObject(context.assets.open("model.json").bufferedReader().use { it.readText() })
  val spec = manifest.getJSONObject("spec")
  val directory = context.filesDir
  val reports = context.getExternalFilesDir(null)!!
  private var handle: Long = 0
  private val names = listOf("img", "big_img", "desire_pulse", "traffic_convention", "action_t", "features_buffer")
  val queues = PolicyQueues()

  init {
    require(Build.SOC_MODEL == manifest.getString("soc")) { "Compiled model is for ${manifest.getString("soc")}, phone is ${Build.SOC_MODEL}" }
    val file = File(directory, "model.tflite")
    require(file.length() == manifest.getLong("compiled_bytes")) { "Missing or incomplete TPU model" }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val block = ByteArray(1024 * 1024)
      while (true) { val n = input.read(block); if (n < 0) break; digest.update(block, 0, n) }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    require(actual == manifest.getString("compiled_sha256")) { "TPU model checksum mismatch" }
    // Native input ordering is independently verified during preparation.
    require(manifest.getJSONArray("input_order").let { a -> (0 until a.length()).map(a::getString) } == names)
    handle = NativeRuntime.create(file.absolutePath, context.applicationInfo.nativeLibraryDir)
  }

  fun infer(): FloatArray {
    NativeRuntime.write(handle, 0, queues.img, false); NativeRuntime.write(handle, 1, queues.bigImg, false)
    NativeRuntime.write(handle, 2, queues.desire, true); NativeRuntime.write(handle, 3, queues.traffic, true)
    NativeRuntime.write(handle, 4, queues.action, true); NativeRuntime.write(handle, 5, queues.features, true)
    return NativeRuntime.run(handle)
  }
  fun fixture(): FloatArray {
    val hashes = JSONObject()
    names.forEachIndexed { index, name ->
      val data = File(directory, "fixture/$name.raw").readBytes()
      hashes.put(name, MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })
      if (index < 2) NativeRuntime.write(handle, index, data, false)
      else {
        // Kotlin buffers expose logical shape; the C++ command-line fixture
        // contains extra dispatch alignment bytes, which are not tensor values.
        val values = FloatArray(when (index) { 2 -> 264; 3, 4 -> 2; else -> 524288 })
        Wire.little(data).asFloatBuffer().get(values)
        NativeRuntime.write(handle, index, values, true)
      }
    }
    File(reports, "fixture-input-hashes.json").writeText(hashes.toString(2))
    return NativeRuntime.run(handle)
  }
  fun saveOutput(name: String, values: FloatArray) {
    val data = ByteArray(values.size * 4)
    Wire.little(data).asFloatBuffer().put(values)
    File(reports, name).writeBytes(data)
  }
  override fun close() { if (handle != 0L) { NativeRuntime.destroy(handle); handle = 0 } }
}
