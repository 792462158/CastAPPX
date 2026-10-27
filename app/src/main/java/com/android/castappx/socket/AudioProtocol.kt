package com.android.castappx.socket

import java.nio.ByteBuffer

/** AAC-LC access units, mono 16 kHz. PCM capture chunks remain 20 ms. */
internal object AudioProtocol {
    const val SAMPLE_RATE = 16000
    const val PCM_BYTES = 640
    const val MAX_ENCODED_BYTES = 1024
    const val HEADER = 16
    private const val MAGIC = 0x43584132

    fun packet(pcm: ByteArray, size: Int, token: Long, sequence: Int): ByteArray {
        require(size in 1..MAX_ENCODED_BYTES && size <= pcm.size)
        require(sequence >= 0)
        return ByteBuffer.allocate(HEADER + size).putInt(MAGIC).putLong(token)
            .putInt(sequence).put(pcm, 0, size).array()
    }

    class Receiver(private val token: Long) {
        private var lastSequence = -1
        fun accept(packet: ByteArray, length: Int): ByteArray? {
            if (length !in HEADER + 1..HEADER + MAX_ENCODED_BYTES || length > packet.size) return null
            val buffer = ByteBuffer.wrap(packet, 0, length)
            if (buffer.int != MAGIC || buffer.long != token) return null
            val sequence = buffer.int
            if (sequence < 0 || sequence <= lastSequence) return null
            lastSequence = sequence
            return ByteArray(length - HEADER).also { buffer.get(it) }
        }
    }
}
