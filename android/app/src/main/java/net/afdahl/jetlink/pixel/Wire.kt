package net.afdahl.jetlink.pixel

import android.hardware.usb.*
import java.io.Closeable
import java.io.EOFException
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Message(val type: Int, val sequence: Int, val flags: Int, val payload: ByteArray)

interface Link : Closeable {
  val receiveAlignment: Int
  val transmitPacketSize: Int get() = 1024
  fun readFully(destination: ByteArray, offset: Int, length: Int)
  fun writeFully(source: ByteArray)
}

class TcpLink(private val socket: Socket) : Link {
  override val receiveAlignment = 0
  init { socket.tcpNoDelay = true; socket.soTimeout = 5000 }
  override fun readFully(destination: ByteArray, offset: Int, length: Int) {
    var done = 0
    while (done < length) {
      val n = socket.getInputStream().read(destination, offset + done, length - done)
      if (n < 0) throw EOFException()
      done += n
    }
  }
  override fun writeFully(source: ByteArray) { socket.getOutputStream().write(source) }
  override fun close() = socket.close()
}

class UsbLink(private val connection: UsbDeviceConnection, private val intf: UsbInterface) : Link {
  override val receiveAlignment = 16384
  private val endpoints = (0 until intf.endpointCount).map(intf::getEndpoint).filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
  private val incoming = endpoints.single { it.direction == UsbConstants.USB_DIR_IN }
  private val outgoing = endpoints.single { it.direction == UsbConstants.USB_DIR_OUT }
  override val transmitPacketSize: Int get() = outgoing.maxPacketSize
  private val buffer = ByteArray(16384)
  private var begin = 0
  private var end = 0
  init { require(connection.claimInterface(intf, true)) { "Cannot claim JetLink USB interface" } }
  override fun readFully(destination: ByteArray, offset: Int, length: Int) {
    var done = 0
    while (done < length) {
      if (begin == end) {
        end = connection.bulkTransfer(incoming, buffer, buffer.size, 5000)
        begin = 0
        if (end <= 0) throw EOFException("USB read timed out or disconnected; reconnect required")
      }
      val n = minOf(length - done, end - begin)
      buffer.copyInto(destination, offset + done, begin, begin + n)
      begin += n; done += n
    }
  }
  override fun writeFully(source: ByteArray) {
    UsbWrites.send(source.size) { offset, length, timeout ->
      connection.bulkTransfer(outgoing, source, offset, length, timeout)
    }
  }
  override fun close() { connection.releaseInterface(intf); connection.close() }
}

/** Bound individual requests and the whole reply; never retry ambiguous errors. */
object UsbWrites {
  fun send(size: Int, transfer: (Int, Int, Int) -> Int) {
    val started = System.nanoTime()
    val deadline = started + 1_000_000_000L
    var offset = 0
    while (offset < size) {
      val remaining = deadline - System.nanoTime()
      if (remaining <= 0) throw EOFException("USB reply deadline at $offset/$size bytes")
      val length = minOf(16384, size - offset)
      val timeout = ((remaining + 999999) / 1000000).toInt()
      val n = transfer(offset, length, timeout)
      if (n <= 0 || n > length) {
        val elapsed = (System.nanoTime() - started) / 1_000_000
        throw EOFException("USB write failed: result=$n offset=$offset/$size request=$length elapsed=${elapsed}ms; reconnect required")
      }
      offset += n
    }
  }
}

object Wire {
  const val MAGIC = 0x4b4e4c4a
  const val VERSION = 2
  const val MAX_PAYLOAD = 4 * 1024 * 1024 + 8
  fun little(data: ByteArray): ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
  fun receive(link: Link): Message {
    val header = ByteArray(32)
    link.readFully(header, 0, 32)
    val h = little(header)
    require(h.int == MAGIC && h.short.toInt() == VERSION) { "Wrong JetLink magic/version" }
    val type = h.short.toInt() and 0xffff
    val sequence = h.int
    val flags = h.int
    val length = h.int
    require(length in 0..MAX_PAYLOAD) { "Oversized or negative payload" }
    val payload = ByteArray(length)
    link.readFully(payload, 0, length)
    val padding = if (link.receiveAlignment > 0) (-(32 + length)).mod(link.receiveAlignment) else if (flags and 128 != 0) 1 else 0
    if (padding > 0) link.readFully(ByteArray(padding), 0, padding)
    return Message(type, sequence, flags, payload)
  }
  fun send(link: Link, type: Int, sequence: Int, payload: ByteArray) {
    val padding = if ((32 + payload.size) % link.transmitPacketSize == 0) 1 else 0
    val data = ByteArray(32 + payload.size + padding)
    little(data).putInt(MAGIC).putShort(VERSION.toShort()).putShort(type.toShort()).putInt(sequence)
      .putInt(if (padding > 0) 128 else 0).putInt(payload.size).putLong(0).putInt(0).put(payload)
    link.writeFully(data)
  }
}
