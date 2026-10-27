package com.android.castappx.socket

import java.nio.ByteBuffer

/** Bounded JPEG frames split into MTU-sized UDP datagrams. No partial frame is displayed. */
internal object VideoProtocol {
    const val HEADER = 28
    const val PAYLOAD = 1200
    const val MAX_FRAME = 2 * 1024 * 1024
    private const val MAGIC = 0x43585031

    fun packets(bytes: ByteArray, token: Long, frame: Int): List<ByteArray> {
        require(bytes.size in 1..MAX_FRAME)
        return (0 until (bytes.size + PAYLOAD - 1) / PAYLOAD).map { index ->
            val offset = index * PAYLOAD
            val length = minOf(PAYLOAD, bytes.size - offset)
            ByteBuffer.allocate(HEADER + length).putInt(MAGIC).putLong(token)
                .putInt(frame).putInt(index).putInt(bytes.size).putInt(length)
                .put(bytes, offset, length).array()
        }
    }

    class Assembler(private val token: Long) {
        private var frame = -1
        private var bytes = ByteArray(0)
        private var received = BooleanArray(0)
        private var count = 0
        private var startedAt = 0L

        fun accept(packet: ByteArray, length: Int, now: Long): ByteArray? {
            if (length !in HEADER + 1..HEADER + PAYLOAD || length > packet.size) return null
            val buffer = ByteBuffer.wrap(packet, 0, length)
            if (buffer.int != MAGIC || buffer.long != token) return null
            val id = buffer.int
            val index = buffer.int
            val total = buffer.int
            val size = buffer.int
            if (id < 0 || total !in 1..MAX_FRAME) return null
            val parts = (total + PAYLOAD - 1) / PAYLOAD
            if (index !in 0 until parts || size != minOf(PAYLOAD, total - index * PAYLOAD) ||
                length != HEADER + size
            ) return null
            if (id < frame) return null
            if (id != frame) {
                frame = id
                bytes = ByteArray(total)
                received = BooleanArray(parts)
                count = 0
                startedAt = now
            }
            if (total != bytes.size || now - startedAt > 1500 || received[index]) return null
            buffer.get(bytes, index * PAYLOAD, size)
            received[index] = true
            count++
            return if (count == received.size) bytes else null
        }
    }
}
