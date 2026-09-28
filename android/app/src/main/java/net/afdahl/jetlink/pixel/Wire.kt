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
  fun writeFully(source: ByteArray, length: Int) { writeFully(source.copyOf(length)) }
  fun writeFully(source: ByteBuffer) {
    val bytes = ByteArray(source.remaining()); source.get(bytes); writeFully(bytes)
  }
}

class TcpLink(private val socket: Socket) : Link {
  private val sendScratch = ByteArray(131072)
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
  override fun writeFully(source: ByteArray, length: Int) { socket.getOutputStream().write(source, 0, length) }
  override fun writeFully(source: ByteBuffer) {
    while (source.hasRemaining()) {
      val n = minOf(sendScratch.size, source.remaining())
      source.get(sendScratch, 0, n); socket.getOutputStream().write(sendScratch, 0, n)
    }
  }
  override fun close() = socket.close()
}

class UsbLink(private val connection: UsbDeviceConnection, private val intf: UsbInterface) : Link {
  override val receiveAlignment = 16384
  private val endpoints = (0 until intf.endpointCount).map(intf::getEndpoint).filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
  private val incoming = endpoints.single { it.direction == UsbConstants.USB_DIR_IN }
  private val outgoing = endpoints.single { it.direction == UsbConstants.USB_DIR_OUT }
  override val transmitPacketSize: Int get() = outgoing.maxPacketSize
  init { require(connection.claimInterface(intf, true)) { "Cannot claim JetLink USB interface" } }
  private val io = QueuedUsbIo(AndroidUsbQueue(connection, intf, incoming, outgoing))
  override fun readFully(destination: ByteArray, offset: Int, length: Int) {
    io.readFully(destination, offset, length)
  }
  override fun writeFully(source: ByteArray) {
    io.writeFully(source, source.size)
  }
  override fun writeFully(source: ByteArray, length: Int) { io.writeFully(source, length) }
  override fun writeFully(source: ByteBuffer) { io.writeFully(source) }
  override fun close() = io.close()
}

object Wire {
  const val MAGIC = 0x4b4e4c4a
  const val VERSION = 2
  const val MAX_PAYLOAD = 4 * 1024 * 1024 + 8
  fun little(data: ByteArray): ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
  fun receive(link: Link, header: ByteArray = ByteArray(32), inferencePayload: ByteArray? = null,
              paddingBuffer: ByteArray = ByteArray(16384)): Message {
    link.readFully(header, 0, 32)
    val h = little(header)
    require(h.int == MAGIC && h.short.toInt() == VERSION) { "Wrong JetLink magic/version" }
    val type = h.short.toInt() and 0xffff
    val sequence = h.int
    val flags = h.int
    val length = h.int
    require(length in 0..MAX_PAYLOAD) { "Oversized or negative payload" }
    val payload = if (type == 8 && inferencePayload?.size == length) inferencePayload else ByteArray(length)
    link.readFully(payload, 0, length)
    val padding = if (link.receiveAlignment > 0) (-(32 + length)).mod(link.receiveAlignment) else if (flags and 128 != 0) 1 else 0
    if (padding > 0) link.readFully(paddingBuffer, 0, padding)
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
