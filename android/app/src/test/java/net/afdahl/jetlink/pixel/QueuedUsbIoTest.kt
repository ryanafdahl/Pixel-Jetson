package net.afdahl.jetlink.pixel

import java.io.EOFException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class QueuedUsbIoTest {
  private class Fake : UsbQueueBackend {
    val reads = LinkedBlockingQueue<UsbSlot>()
    val completions = LinkedBlockingQueue<UsbSlot>()
    val writes = mutableListOf<ByteArray>()
    var shortWrite = false
    var stallWrite = false
    var closed = false
    override fun queue(slot: UsbSlot): Boolean {
      if (slot.input) reads.add(slot)
      else {
        val data = ByteArray(slot.buffer.remaining())
        slot.buffer.get(data)
        writes.add(data)
        if (shortWrite) slot.buffer.position(slot.buffer.position() - 1)
        if (!stallWrite) completions.add(slot)
      }
      return true
    }
    override fun await(timeoutMs: Long): UsbSlot? = completions.poll(timeoutMs, TimeUnit.MILLISECONDS)
    override fun close() { closed = true }
    fun complete(slot: UsbSlot, value: Byte) {
      slot.buffer.put(ByteArray(16384) { value }); completions.add(slot)
    }
  }
  @Test fun receivesInSubmissionOrderAndRequeuesConsumedBuffers() {
    val backend = Fake()
    QueuedUsbIo(backend).use { io ->
      assertEquals(4, backend.reads.size)
      val first = backend.reads.remove(); val second = backend.reads.remove()
      backend.complete(second, 2); backend.complete(first, 1)
      val data = ByteArray(32768)
      io.readFully(data, 0, data.size)
      assertTrue(data.take(16384).all { it == 1.toByte() })
      assertTrue(data.drop(16384).all { it == 2.toByte() })
      assertEquals(4, backend.reads.size)
    }
    assertTrue(backend.closed)
  }
  @Test fun queuedReadsDoNotStealOrBlockReplyCompletion() {
    val backend = Fake()
    QueuedUsbIo(backend).use { io ->
      repeat(4) { backend.complete(backend.reads.remove(), it.toByte()) }
      val reply = ByteArray(74210) { (it % 251).toByte() }
      io.writeFully(reply, reply.size)
      assertArrayEquals(reply, backend.writes.single())
      // RX buffers must not be requeued before the consumer finishes with them.
      assertEquals(0, backend.reads.size)
      io.readFully(ByteArray(65536), 0, 65536)
      assertEquals(4, backend.reads.size)
    }
  }
  @Test fun partialCompletionAbandonsReplyWithoutRetry() {
    val backend = Fake().apply { shortWrite = true }
    QueuedUsbIo(backend).use { io ->
      try { io.writeFully(ByteArray(74000), 74000); fail("Expected short write failure") }
      catch (e: EOFException) { assertTrue(e.message!!.contains("73999/74000")) }
      assertEquals(1, backend.writes.size)
    }
  }
  @Test fun stalledWritePoisonsSessionAndDoesNotReuseBuffer() {
    val backend = Fake().apply { stallWrite = true }
    QueuedUsbIo(backend, writeTimeoutMs = 20).use { io ->
      repeat(2) {
        try { io.writeFully(ByteArray(74000), 74000); fail("Expected timeout") }
        catch (_: EOFException) {}
      }
      assertEquals(1, backend.writes.size)
    }
  }

  @Test fun directReplyRemainsIdenticalAcrossInterveningControlWrite() {
    val backend = Fake()
    QueuedUsbIo(backend).use { io ->
      val bytes = ByteArray(74000) { (it % 251).toByte() }
      val direct = java.nio.ByteBuffer.allocateDirect(bytes.size)
      direct.put(bytes); direct.flip()
      io.writeFully(direct)
      io.writeFully(byteArrayOf(9, 8, 7), 3)
      direct.position(0)
      io.writeFully(direct)
      assertArrayEquals(bytes, backend.writes[0])
      assertArrayEquals(byteArrayOf(9, 8, 7), backend.writes[1])
      assertArrayEquals(bytes, backend.writes[2])
    }
  }
  @Test fun directReplyTimeoutPreventsSubsequentBufferReuse() {
    val backend = Fake().apply { stallWrite = true }
    QueuedUsbIo(backend, writeTimeoutMs = 20).use { io ->
      val direct = java.nio.ByteBuffer.allocateDirect(74000)
      repeat(2) {
        direct.position(0)
        try { io.writeFully(direct); fail("Expected timeout") }
        catch (_: EOFException) {}
      }
      assertEquals(1, backend.writes.size)
    }
  }
}
