package net.afdahl.jetlink.pixel

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Dedicated reply storage also preserves the exact previous packet for duplicates. */
class InferenceReply {
  private var data = ByteBuffer.allocateDirect(131072).order(ByteOrder.LITTLE_ENDIAN)
  private var length = 0
  fun encode(packetSize: Int, sequence: Int, frame: Int, status: Int, inferUs: Int, queueUs: Int,
             totalUs: Int, values: FloatArray, telemetry: ByteArray) {
    val payloadSize = 20 + values.size * 4 + telemetry.size
    val padding = if ((32 + payloadSize) % packetSize == 0) 1 else 0
    length = 32 + payloadSize + padding
    if (data.capacity() < length) data = ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN)
    val bytes = data.apply { clear(); limit(length) }
    bytes.putInt(Wire.MAGIC).putShort(2).putShort(9).putInt(sequence)
      .putInt(if (padding == 1) 128 else 0).putInt(payloadSize).putLong(0).putInt(0)
    bytes.putInt(frame).putInt(status).putInt(inferUs).putInt(queueUs).putInt(totalUs)
    bytes.asFloatBuffer().put(values)
    bytes.position(52 + values.size * 4)
    bytes.put(telemetry)
    if (padding == 1) bytes.put(0)
  }
  fun send(link: Link) { check(length > 0); data.position(0); data.limit(length); link.writeFully(data) }
}
