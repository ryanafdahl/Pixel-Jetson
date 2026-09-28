package net.afdahl.jetlink.pixel

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class PixelEngine(context: Context, val performanceMode: Int = 5) : AutoCloseable {
  val manifest = JSONObject(context.assets.open("model.json").bufferedReader().use { it.readText() })
  val spec = manifest.getJSONObject("spec")
  val directory = context.filesDir
  val reports = context.getExternalFilesDir(null)!!
  private var handle: Long = 0
  private val names = listOf("img", "big_img", "desire_pulse", "traffic_convention", "action_t", "features_buffer")
  val queues = PolicyQueues()
  private val output = FloatArray(18452)
  val nativeTimingsNs = LongArray(2)
  var inputWriteNs = 0L
    private set

  init {
    require(performanceMode in 3..5) { "Unsupported test performance mode" }
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
    handle = NativeRuntime.create(file.absolutePath, context.applicationInfo.nativeLibraryDir, performanceMode)
  }

  fun infer(): FloatArray {
    val start = System.nanoTime()
    NativeRuntime.writeImageHistory(handle, 0, queues.narrowHistory, queues.imageHead)
    NativeRuntime.writeImageHistory(handle, 1, queues.wideHistory, queues.imageHead)
    NativeRuntime.write(handle, 2, queues.desire, true); NativeRuntime.write(handle, 3, queues.traffic, true)
    NativeRuntime.write(handle, 4, queues.action, true); NativeRuntime.writeFeatureHistory(handle, queues.featureStorage, queues.featureHead)
    inputWriteNs = System.nanoTime() - start
    NativeRuntime.run(handle, output, nativeTimingsNs)
    return output
  }
  fun verifyHistories(queue: PolicyQueues) {
    NativeRuntime.writeImageHistory(handle, 0, queue.narrowHistory, queue.imageHead)
    NativeRuntime.writeImageHistory(handle, 1, queue.wideHistory, queue.imageHead)
    check(NativeRuntime.verifyImageInput(handle, 0, queue.img)) { "Native narrow image history mismatch" }
    check(NativeRuntime.verifyImageInput(handle, 1, queue.bigImg)) { "Native wide image history mismatch" }
    NativeRuntime.writeFeatureHistory(handle, queue.featureStorage, queue.featureHead)
    check(NativeRuntime.verifyFeatureInput(handle, queue.features)) { "Native feature gather differs from reference history" }
  }
  // Read/hash fixture files once; 21 repeated NPU runs need no repeated disk I/O.
  private val fixtureInputs: List<Any> by lazy {
    val hashes = JSONObject()
    val inputs = names.mapIndexed { index, name ->
      val data = File(directory, "fixture/$name.raw").readBytes()
      hashes.put(name, MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })
      if (index < 2) data else {
        // Read logical tensor values, excluding dispatch alignment padding.
        val values = FloatArray(when (index) { 2 -> 264; 3, 4 -> 2; else -> 524288 })
        Wire.little(data).asFloatBuffer().get(values)
        values
      }
    }
    File(reports, "fixture-input-hashes.json").writeText(hashes.toString(2))
    inputs
  }
  fun fixture(): FloatArray {
    fixtureInputs.forEachIndexed { index, data -> NativeRuntime.write(handle, index, data, index >= 2) }
    NativeRuntime.run(handle, output, nativeTimingsNs)
    return output
  }
  fun saveOutput(name: String, values: FloatArray) {
    val data = ByteArray(values.size * 4)
    Wire.little(data).asFloatBuffer().put(values)
    File(reports, name).writeBytes(data)
  }
  override fun close() { if (handle != 0L) { NativeRuntime.destroy(handle); handle = 0 } }
}
