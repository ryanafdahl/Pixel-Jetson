package net.afdahl.jetlink.pixel

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.graphics.Color
import android.view.WindowManager
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity() {
  private val screenBackground = Color.rgb(11, 18, 32)
  private val primaryText = Color.rgb(241, 245, 249)
  private val secondaryText = Color.rgb(203, 213, 225)
  private val waitingText = Color.rgb(253, 230, 138)
  private val stop = AtomicBoolean(false)
  private val sessionActive = AtomicBoolean(false)
  private var engine: PixelEngine? = null
  private var server: ServerSocket? = null
  private var activeLink: Link? = null
  private val engineMutex = Any()
  private lateinit var text: TextView
  private lateinit var powerText: TextView
  private lateinit var budgetText: TextView
  private lateinit var phoneText: TextView
  private var pendingUsbAttach = false
  private val telemetry by lazy { PowerTelemetry(this) }
  private val updatePower = object : Runnable {
    override fun run() {
      if (!stop.get()) { powerText.text = telemetry.display(); powerText.postDelayed(this, 2000) }
    }
  }
  private val logText = StringBuilder()
  private val usb by lazy { getSystemService(USB_SERVICE) as UsbManager }
  private val permissionAction = "net.afdahl.jetlink.pixel.USB_PERMISSION"
  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action == permissionAction) {
        val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        if (device != null && usb.hasPermission(device)) connectUsb(device)
        else report("USB permission was not granted")
      } else if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) { pendingUsbAttach = true; scanUsb() }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setTurnScreenOn(true)
    setShowWhenLocked(true)
    window.decorView.setBackgroundColor(screenBackground)
    window.statusBarColor = screenBackground
    window.navigationBarColor = screenBackground
    window.insetsController?.setSystemBarsAppearance(0,
      WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    getExternalFilesDir(null)!!.mkdirs()
    val layout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(screenBackground)
      val margin = (16 * resources.displayMetrics.density).toInt()
      setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        view.setPadding(margin + bars.left, margin + bars.top, margin + bars.right, margin + bars.bottom)
        insets
      }
    }
    layout.addView(TextView(this).apply { text = "JetLink Pixel · Parked validation"; textSize = 24f; setTextColor(primaryText) })
    layout.addView(TextView(this).apply { text = "Model accuracy is still being validated. Keep the vehicle parked and this app open."; textSize = 16f; setTextColor(waitingText) })
    budgetText = TextView(this).apply {
      text = "Waiting for measured USB exchange\n50 ms budget • no result yet"; textSize = 22f
      setTextColor(waitingText); setBackgroundColor(Color.rgb(24, 34, 53))
      val p = (12 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
    }
    phoneText = TextView(this).apply { text = "Phone work: waiting for frames"; textSize = 18f; setTextColor(Color.rgb(103, 232, 249)) }
    layout.addView(budgetText)
    layout.addView(phoneText)
    powerText = TextView(this).apply { textSize = 16f; setTextColor(primaryText) }
    layout.addView(powerText)
    layout.addView(Button(this).apply {
      text = "Connect comma USB"; setTextColor(Color.WHITE)
      backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(29, 78, 216))
      setOnClickListener { scanUsb(requestPermission = true) }
    })
    text = TextView(this).apply { textSize = 14f; setTextColor(secondaryText); setTextIsSelectable(true) }
    layout.addView(ScrollView(this).apply { addView(text) }, LinearLayout.LayoutParams(-1, 0, 1f))
    setContentView(layout)
    powerText.post(updatePower)
    registerReceiver(receiver, IntentFilter().apply { addAction(permissionAction); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED) }, RECEIVER_NOT_EXPORTED)
    thread(name = "pixel-initialize") {
      synchronized(engineMutex) {
      try {
        report("Checking history queues…")
        File(getExternalFilesDir(null), "queue-check.json").writeText(QueueCheck.run().toString(2))
        report("Verifying model checksum and loading ${Build.SOC_MODEL}…")
        val loaded = PixelEngine(this)
        if (stop.get()) { loaded.close(); return@thread }
        engine = loaded
        if (File(loaded.directory, "fixture").isDirectory) {
          report("Running reference fixture…")
          val timings = JSONArray()
          for (i in 0 until 21) {
            val start = System.nanoTime()
            val output = loaded.fixture()
            require(output.all { it.isFinite() }) { "Non-finite fixture output" }
            if (i == 0) loaded.saveOutput("fixture-output.bin", output)
            else timings.put((System.nanoTime() - start) / 1e6)
            if (i == 20) loaded.saveOutput("fixture-output-last.bin", output)
          }
          File(loaded.reports, "fixture-timings.json").writeText(JSONObject().put("milliseconds", timings).toString(2))
        }
        if (stop.get()) { loaded.close(); engine = null; return@thread }
        report("Ready for isolated parked test. Local TCP :8765.")
        startTcp()
        runOnUiThread { scanUsb() }
      } catch (e: Exception) { report("START FAILED: ${e.javaClass.simpleName}: ${e.message}"); Log.e("PixelJetLink", "startup", e) }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
      pendingUsbAttach = true
      scanUsb()
    }
  }

  private fun dashboard(phone: String?, budget: String?) {
    runOnUiThread {
      if (phone != null) phoneText.text = phone
      if (budget != null) {
        budgetText.text = budget
        budgetText.setTextColor(when {
          budget.contains("OVER 50") -> Color.rgb(253, 164, 175)
          budget.contains("WITHIN 50") -> Color.rgb(134, 239, 172)
          else -> waitingText
        })
      }
    }
  }

  private fun report(message: String) {
    Log.i("PixelJetLink", message)
    synchronized(logText) {
      logText.append(message).append('\n')
      if (logText.length > 12000) logText.delete(0, logText.length - 10000)
      val snapshot = logText.toString()
      File(getExternalFilesDir(null), "status.txt").writeText(snapshot)
      runOnUiThread { text.text = snapshot }
    }
  }

  private fun serve(link: Link, kind: String) {
    if (!sessionActive.compareAndSet(false, true)) { link.close(); return }
    activeLink = link
    thread(name = "pixel-$kind") {
      try { synchronized(engineMutex) { if (!stop.get()) { report("$kind connected"); Session(engine!!, telemetry, kind, ::report, ::dashboard).run(link, stop) } } }
      catch (e: Exception) { if (!stop.get()) report("$kind ended: ${e.message}") }
      finally {
        runCatching { link.close() }; activeLink = null; sessionActive.set(false)
        runOnUiThread { if (!stop.get() && pendingUsbAttach) scanUsb() }
      }
    }
  }

  private fun startTcp() {
    server = ServerSocket(8765, 1, InetAddress.getByName("127.0.0.1"))
    thread(name = "pixel-listen") {
      try { while (!stop.get()) serve(TcpLink(server!!.accept()), "TCP") }
      catch (e: Exception) { if (!stop.get()) report("TCP listener: ${e.message}") }
    }
  }

  private fun scanUsb(requestPermission: Boolean = false) {
    if (engine == null) { report("Model is not loaded yet"); return }
    if (sessionActive.get()) { report("A test session is already active"); return }
    val device = usb.deviceList.values.firstOrNull { it.vendorId == 0x1209 && it.productId == 0x0001 }
    if (device == null) { report("Waiting for comma USB (1209:0001)"); return }
    if (usb.hasPermission(device)) { pendingUsbAttach = false; connectUsb(device) }
    else if (requestPermission) usb.requestPermission(device, PendingIntent.getBroadcast(this, 0, Intent(permissionAction).setPackage(packageName), PendingIntent.FLAG_MUTABLE))
    else report("Comma detected. Choose JetLink Pixel Test and Always use in Android's USB dialog once. Connect comma USB is a manual fallback.")
  }

  private fun connectUsb(device: UsbDevice) {
    if (sessionActive.get()) return
    try {
      val intf = (0 until device.interfaceCount).map(device::getInterface).single {
        it.interfaceClass == 255 && it.interfaceSubclass == 255 && it.interfaceProtocol == 255 && it.endpointCount == 2
      }
      val connection = usb.openDevice(device) ?: error("Cannot open USB device")
      val link = try { UsbLink(connection, intf) } catch (e: Exception) { connection.close(); throw e }
      serve(link, "USB")
    } catch (e: Exception) { report("USB failed: ${e.message}") }
  }

  override fun onDestroy() {
    stop.set(true)
    powerText.removeCallbacks(updatePower)
    runCatching { server?.close() }; runCatching { activeLink?.close() }
    unregisterReceiver(receiver)
    thread(name = "pixel-cleanup") { synchronized(engineMutex) { engine?.close(); engine = null } }
    super.onDestroy()
  }
}
