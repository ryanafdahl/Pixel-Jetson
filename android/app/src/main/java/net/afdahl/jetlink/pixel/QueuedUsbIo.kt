package net.afdahl.jetlink.pixel

import java.io.Closeable
import java.io.EOFException
import java.nio.ByteBuffer
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class UsbSlot(val input: Boolean, var buffer: ByteBuffer) {
  var sequence = 0L
  var expected = 0
  @Volatile var sent = 0
  @Volatile var done = CountDownLatch(0)
}

/** Backend returns only completed requests; it must not expose an in-flight buffer. */
interface UsbQueueBackend : Closeable {
  fun queue(slot: UsbSlot): Boolean
  fun await(timeoutMs: Long): UsbSlot? // null means poll timeout, not disconnect
}

/** Four reads remain queued while inference runs. One owner drains both endpoints. */
class QueuedUsbIo(private val backend: UsbQueueBackend, private val writeTimeoutMs: Long = 1000) : Closeable {
  private val stopped = AtomicBoolean(false)
  @Volatile private var failure: Exception? = null
  private val sequence = AtomicLong()
  private val received = LinkedBlockingQueue<UsbSlot>(4)
  private val inputs = List(4) { UsbSlot(true, ByteBuffer.allocateDirect(16384)) }
  private var copyBuffer = ByteBuffer.allocateDirect(131072)
  private val output = UsbSlot(false, copyBuffer)
  private var current: UsbSlot? = null
  private lateinit var worker: Thread

  init {
    try {
      inputs.forEach(::queueInput)
      worker = thread(name = "pixel-usb-completions", isDaemon = true) {
        val pending = TreeMap<Long, UsbSlot>()
        var next = 0L
        try {
          while (!stopped.get()) {
            val slot = backend.await(250) ?: continue
            val count = slot.buffer.position()
            if (slot.input) {
              if (count <= 0) throw EOFException("USB input completed without data")
              slot.buffer.flip()
              check(pending.put(slot.sequence, slot) == null) { "Duplicate USB completion" }
              while (pending.containsKey(next)) {
                check(received.offer(pending.remove(next++)!!)) { "USB receive pool overflow" }
              }
            } else {
              slot.sent = count
              slot.done.countDown()
            }
          }
        } catch (e: Exception) { failure = e; output.done.countDown() }
      }
    } catch (e: Exception) { backend.close(); throw e }
  }

  private fun healthy() {
    failure?.let { throw EOFException("USB completion failed: ${it.message}") }
    if (stopped.get()) throw EOFException("USB connection closed")
  }
  private fun queueInput(slot: UsbSlot) {
    healthy()
    slot.buffer.clear()
    slot.sequence = sequence.getAndIncrement()
    if (!backend.queue(slot)) throw EOFException("Cannot queue USB input")
  }
  fun readFully(destination: ByteArray, offset: Int, length: Int) {
    val deadline = System.nanoTime() + 5_000_000_000L
    var copied = 0
    while (copied < length) {
      healthy()
      if (current == null) {
        current = received.poll(20, TimeUnit.MILLISECONDS)
        if (current == null) {
          if (System.nanoTime() >= deadline) throw EOFException("USB input deadline; reconnect required")
          continue
        }
      }
      val slot = current!!
      val n = minOf(length - copied, slot.buffer.remaining())
      slot.buffer.get(destination, offset + copied, n)
      copied += n
      if (!slot.buffer.hasRemaining()) { current = null; queueInput(slot) }
    }
  }
  fun writeFully(source: ByteArray, length: Int) {
    healthy()
    require(length in 1..source.size)
    if (copyBuffer.capacity() < length) copyBuffer = ByteBuffer.allocateDirect(length)
    copyBuffer.clear(); copyBuffer.put(source, 0, length); copyBuffer.flip()
    writeFully(copyBuffer)
  }
  /** Caller owns a dedicated reply buffer and cannot reuse it until this returns. */
  fun writeFully(source: ByteBuffer) {
    healthy()
    require(source.isDirect && source.position() == 0 && source.hasRemaining())
    val length = source.remaining()
    output.buffer = source
    output.expected = length; output.sent = 0; output.done = CountDownLatch(1)
    if (!backend.queue(output)) throw EOFException("Cannot queue USB reply ($length bytes)")
    if (!output.done.await(writeTimeoutMs, TimeUnit.MILLISECONDS)) {
      // The kernel may have sent a prefix. Never retry or reuse this buffer.
      failure = EOFException("USB reply deadline ($length bytes); reconnect required")
      throw failure!!
    }
    healthy()
    if (output.sent != length) {
      failure = EOFException("USB short reply ${output.sent}/$length bytes; reconnect required")
      throw failure!!
    }
  }
  override fun close() {
    if (!stopped.compareAndSet(false, true)) return
    backend.close()
    output.done.countDown()
    if (::worker.isInitialized && Thread.currentThread() != worker) worker.join(1000)
  }
}
