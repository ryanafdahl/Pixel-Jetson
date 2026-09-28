package net.afdahl.jetlink.pixel

import android.hardware.usb.*
import java.io.EOFException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

class AndroidUsbQueue(private val connection: UsbDeviceConnection, private val intf: UsbInterface,
                      private val incoming: UsbEndpoint, private val outgoing: UsbEndpoint) : UsbQueueBackend {
  private val requests = ConcurrentHashMap<UsbSlot, UsbRequest>()
  private var closed = false
  @Synchronized override fun queue(slot: UsbSlot): Boolean {
    if (closed) return false
    val request = requests.computeIfAbsent(slot) {
      UsbRequest().apply {
        if (!initialize(connection, if (slot.input) incoming else outgoing)) {
          close()
          error("USB request initialization failed")
        }
        clientData = slot
      }
    }
    return request.queue(slot.buffer)
  }
  override fun await(timeoutMs: Long): UsbSlot? = try {
    val request = connection.requestWait(timeoutMs) ?: throw EOFException("USB request failed or disconnected")
    request.clientData as UsbSlot
  } catch (_: TimeoutException) { null }
  @Synchronized override fun close() {
    if (closed) return
    closed = true
    requests.values.forEach { runCatching { it.cancel() } }
    connection.releaseInterface(intf)
    connection.close()
    requests.values.forEach { runCatching { it.close() } }
  }
}
