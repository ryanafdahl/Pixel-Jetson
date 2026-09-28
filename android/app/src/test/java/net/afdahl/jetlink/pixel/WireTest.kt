package net.afdahl.jetlink.pixel

import java.io.ByteArrayOutputStream
import java.io.EOFException
import org.junit.Assert.*
import org.junit.Test

class WireTest {
  private class MemoryLink(private val data: ByteArray, override val receiveAlignment: Int = 0,
                           override val transmitPacketSize: Int = 1024) : Link {
    var offset = 0
    val written = ByteArrayOutputStream()
    override fun readFully(destination: ByteArray, offset: Int, length: Int) {
      if (this.offset + length > data.size) throw EOFException()
      data.copyInto(destination, offset, this.offset, this.offset + length)
      this.offset += length
    }
    override fun writeFully(source: ByteArray) { written.write(source) }
    override fun close() {}
  }

  @Test fun hostPacketMultipleUsesOnePaddingByte() {
    val link = MemoryLink(ByteArray(0))
    Wire.send(link, 16, 0x12345678, ByteArray(992) { 0x42 })
    val data = link.written.toByteArray()
    assertEquals(1025, data.size)
    // Protocol.py uses <IHHIIIQ4x>; flags and payload length are at 12 and 16.
    assertArrayEquals(byteArrayOf(0x4a, 0x4c, 0x4e, 0x4b, 2, 0, 16, 0), data.copyOfRange(0, 8))
    assertEquals(128, Wire.little(data).getInt(12))
    assertEquals(992, Wire.little(data).getInt(16))
    assertEquals(0, data.last().toInt())
  }

  @Test fun consumesWholeGadgetBurstBeforeNextHeader() {
    // Two independently padded FunctionFS messages; payload crosses a burst.
    val wire = ByteArrayOutputStream()
    for ((seq, size) in listOf(7 to 19000, 8 to 11)) {
      val frame = ByteArray(((32 + size + 16383) / 16384) * 16384)
      Wire.little(frame).putInt(0x4b4e4c4a).putShort(2).putShort(15).putInt(seq).putInt(0).putInt(size)
      for (i in 0 until size) frame[32 + i] = seq.toByte()
      wire.write(frame)
    }
    val link = MemoryLink(wire.toByteArray(), 16384)
    val first = Wire.receive(link)
    val second = Wire.receive(link)
    assertEquals(7, first.sequence); assertEquals(19000, first.payload.size)
    assertTrue(first.payload.all { it == 7.toByte() })
    assertEquals(8, second.sequence); assertEquals(11, second.payload.size)
    assertEquals(49152, link.offset)
  }

  @Test fun highSpeedReplyEndsWithShortPacket() {
    val link = MemoryLink(ByteArray(0), transmitPacketSize = 512)
    Wire.send(link, 9, 1, ByteArray(480))
    val data = link.written.toByteArray()
    assertEquals(513, data.size)
    assertEquals(128, Wire.little(data).getInt(12))
    assertEquals(480, Wire.little(data).getInt(16))
  }

  @Test fun scratchInputIsUsedOnlyForMatchingInferenceFrames() {
    val encoded = MemoryLink(ByteArray(0))
    Wire.send(encoded, 8, 1, ByteArray(100) { 7 })
    Wire.send(encoded, 15, 2, ByteArray(100) { 9 })
    val source = MemoryLink(encoded.written.toByteArray())
    val scratch = ByteArray(100)
    val inference = Wire.receive(source, inferencePayload = scratch)
    assertSame(scratch, inference.payload)
    val ping = Wire.receive(source, inferencePayload = scratch)
    assertNotSame(scratch, ping.payload)
    assertTrue(scratch.all { it == 7.toByte() })
    assertTrue(ping.payload.all { it == 9.toByte() })
  }

  @Test(expected = IllegalArgumentException::class) fun rejectsOversizedLengthBeforeAllocating() {
    val header = ByteArray(32)
    Wire.little(header).putInt(0x4b4e4c4a).putShort(2).putShort(8).putInt(1).putInt(0).putInt(Int.MAX_VALUE)
    Wire.receive(MemoryLink(header))
  }
}
