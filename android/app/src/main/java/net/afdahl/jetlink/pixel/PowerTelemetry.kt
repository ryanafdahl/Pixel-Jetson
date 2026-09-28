package net.afdahl.jetlink.pixel

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject

class PowerTelemetry(private val context: Context) {
  private var sampled = -2000L
  private var cached = JSONObject()
  @Synchronized fun snapshot(): JSONObject {
    val now = SystemClock.elapsedRealtime()
    if (now - sampled < 2000) return cached
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
    val current = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    val thermal = power.currentThermalStatus
    cached = JSONObject().put("elapsed_ms", now)
      .put("battery_percent", manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
      .put("wireless_powered", plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0)
      .put("usb_powered", plugged and BatteryManager.BATTERY_PLUGGED_USB != 0)
      .put("ac_powered", plugged and BatteryManager.BATTERY_PLUGGED_AC != 0)
      .put("battery_status", battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1)
      .put("battery_temperature_c", if (temperature == Int.MIN_VALUE) JSONObject.NULL else temperature / 10.0)
      .put("battery_current_ua", if (current == Int.MIN_VALUE) JSONObject.NULL else current)
      .put("thermal_status", thermal)
      .put("thermal_label", listOf("Normal", "Light", "Moderate", "Severe", "Critical", "Emergency", "Shutdown").getOrElse(thermal) { "Unknown" })
    sampled = now
    return cached
  }

  fun display(): String {
    val state = snapshot()
    val source = if (state.getBoolean("wireless_powered")) "Wireless" else if (state.getBoolean("usb_powered")) "USB" else if (state.getBoolean("ac_powered")) "AC" else "Battery"
    return "Power: $source · ${state.optInt("battery_percent")}% · ${state.opt("battery_temperature_c")}°C\nThermal: ${state.optString("thermal_label")}"
  }
}
