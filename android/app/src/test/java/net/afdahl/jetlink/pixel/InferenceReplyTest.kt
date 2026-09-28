package net.afdahl.jetlink.pixel

import org.junit.Assert.*
import org.junit.Test

class InferenceReplyTest {
  private class Capture : Link {
    override val receiveAlignment = 0
    var bytes = ByteArray(0)
    override fun readFully(destination: ByteArray, offset: Int, length: Int) = error("unused")
    override fun writeFully(source: ByteArray) { bytes = source.copyOf() }
    override fun close() {}
  }
  @Test fun bulkPackedReplyMatchesOriginalWireAndDuplicateRemainsExact() {
    val values = FloatArray(18452) { it / 7f - 50f }
    val telemetry = "{\"frames_served\":3}".toByteArray()
    val payload = ByteArray(20 + values.size * 4 + telemetry.size)
    val old = Wire.little(payload).putInt(3).putInt(0).putInt(31000).putInt(1800).putInt(33000)
    values.forEach { old.putFloat(it) }; old.put(telemetry)
    val expected = Capture(); Wire.send(expected, 9, 8, payload)
    val reply = InferenceReply()
    reply.encode(1024, 8, 3, 0, 31000, 1800, 33000, values, telemetry)
    val actual = Capture(); reply.send(actual)
    assertArrayEquals(expected.bytes, actual.bytes)
    values.fill(0f) // Caller can reuse NPU output without changing cached reply.
    reply.send(actual)
    assertArrayEquals(expected.bytes, actual.bytes)
  }
  @Test fun shorterReplyDoesNotLeakPreviousBufferTail() {
    val reply = InferenceReply()
    reply.encode(512, 1, 1, 0, 1, 1, 1, FloatArray(18452), ByteArray(0))
    reply.encode(512, 2, 2, 2, 0, 0, 0, FloatArray(0), ByteArray(0))
    val actual = Capture(); reply.send(actual)
    assertEquals(52, actual.bytes.size)
    assertEquals(20, Wire.little(actual.bytes).getInt(16))
  }
  @Test fun packetAlignedReplyIncludesFlaggedShortPacketByte() {
    val reply = InferenceReply()
    reply.encode(512, 1, 1, 0, 1, 1, 1, FloatArray(115), ByteArray(0))
    val actual = Capture(); reply.send(actual)
    assertEquals(513, actual.bytes.size)
    assertEquals(128, Wire.little(actual.bytes).getInt(12))
  }
}
