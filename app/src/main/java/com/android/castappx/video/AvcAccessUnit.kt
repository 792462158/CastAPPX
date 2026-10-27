package com.android.castappx.video

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Normalize AVC NALs and separate codec setup data from picture data. */
internal object AvcAccessUnit {
    data class Configuration(val sps: ByteArray, val pps: ByteArray)

    private val startCode = byteArrayOf(0, 0, 0, 1)

    fun configuration(bytes: ByteArray): Configuration? {
        val units = units(bytes) ?: return null
        val sps = units.lastOrNull { it[0].toInt() and 31 == 7 } ?: return null
        val pps = units.lastOrNull { it[0].toInt() and 31 == 8 } ?: return null
        return Configuration(startCode + sps, startCode + pps)
    }

    fun picture(bytes: ByteArray): ByteArray? {
        val units = units(bytes) ?: return null
        val output = ByteArrayOutputStream(bytes.size)
        units.filter { it[0].toInt() and 31 !in 7..8 }.forEach {
            output.write(startCode)
            output.write(it)
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun prefix(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 > bytes.size || bytes[offset] != 0.toByte() || bytes[offset + 1] != 0.toByte()) return 0
        if (bytes[offset + 2] == 1.toByte()) return 3
        if (offset + 4 <= bytes.size && bytes[offset + 2] == 0.toByte() && bytes[offset + 3] == 1.toByte()) return 4
        return 0
    }

    private fun units(bytes: ByteArray): List<ByteArray>? {
        if (bytes.isEmpty()) return null
        if (prefix(bytes, 0) != 0) {
            val result = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < bytes.size) {
                val size = prefix(bytes, offset)
                if (size == 0) return null
                val start = offset + size
                var end = start
                while (end < bytes.size && prefix(bytes, end) == 0) end++
                if (end == start) return null
                result.add(bytes.copyOfRange(start, end))
                offset = end
            }
            return result
        }
        // Some encoder integrations expose four-byte AVCC lengths instead of Annex B.
        val buffer = ByteBuffer.wrap(bytes)
        val result = mutableListOf<ByteArray>()
        while (buffer.hasRemaining()) {
            if (buffer.remaining() < 4) return null
            val size = buffer.int
            if (size !in 1..buffer.remaining()) return null
            result.add(ByteArray(size).also { buffer.get(it) })
        }
        return result
    }
}
