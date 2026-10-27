package com.android.castappx.socket

import java.nio.ByteBuffer

/** H.264 access units, plus one XOR repair packet for every eight data packets. */
internal object VideoProtocol {
    const val FRAME_INTERVAL_MS = 66L
    const val MAX_FRAME = 256 * 1024
    const val MAX_DIMENSION = 960
    const val PAYLOAD = 500
    const val HEADER = 48
    const val KEY_FRAME = 1
    const val PACKET_INTERVAL_NS = 12_000_000L
    private const val MAGIC = 0x43584832
    private const val GROUP = 8

    data class Frame(
        val id: Int, val width: Int, val height: Int, val ptsUs: Long,
        val flags: Int, val bytes: ByteArray
    ) {
        val isKeyFrame get() = flags and KEY_FRAME != 0
    }

    fun packets(frame: Frame, token: Long): List<ByteArray> {
        require(frame.bytes.size in 1..MAX_FRAME && frame.id >= 0 && frame.ptsUs >= 0)
        require(frame.width in 16..MAX_DIMENSION && frame.height in 16..MAX_DIMENSION)
        val parts = (frame.bytes.size + PAYLOAD - 1) / PAYLOAD
        fun packet(index: Int, bytes: ByteArray): ByteArray =
            ByteBuffer.allocate(HEADER + bytes.size).putInt(MAGIC).putLong(token)
                .putInt(frame.id).putInt(index).putInt(frame.bytes.size).putInt(bytes.size)
                .putInt(frame.width).putInt(frame.height).putLong(frame.ptsUs).putInt(frame.flags)
                .put(bytes).array()

        val result = ArrayList<ByteArray>()
        for (group in 0 until (parts + GROUP - 1) / GROUP) {
            val parity = ByteArray(PAYLOAD)
            for (index in group * GROUP until minOf(parts, (group + 1) * GROUP)) {
                val offset = index * PAYLOAD
                val data =
                    frame.bytes.copyOfRange(offset, minOf(frame.bytes.size, offset + PAYLOAD))
                data.forEachIndexed { i, b ->
                    parity[i] = (parity[i].toInt() xor b.toInt()).toByte()
                }
                result.add(packet(index, data))
            }
            result.add(packet(-group - 1, parity))
        }
        return result
    }

    class Assembler(private val token: Long) {
        private class Pending(val frame: Frame, val started: Long) {
            val seen = BooleanArray((frame.bytes.size + PAYLOAD - 1) / PAYLOAD)
            val parity = arrayOfNulls<ByteArray>((seen.size + GROUP - 1) / GROUP)
            var count = 0

            // A paced IDR can legitimately take longer than 500 ms to arrive.
            // Give it its transmission budget plus jitter, capped at two seconds.
            val lifetime = if (frame.isKeyFrame) maxOf(
                500L, minOf(
                    2000L,
                    (seen.size + parity.size) * (PACKET_INTERVAL_NS / 1_000_000) + 250L
                )
            ) else 500L

            fun repair(group: Int) {
                val xor = parity[group] ?: return
                val range = group * GROUP until minOf(seen.size, (group + 1) * GROUP)
                val missing = range.filter { !seen[it] }
                if (missing.size != 1) return
                val index = missing.single()
                val restored = xor.copyOf()
                range.filter { seen[it] }.forEach { part ->
                    val start = part * PAYLOAD
                    for (i in 0 until minOf(PAYLOAD, frame.bytes.size - start)) {
                        restored[i] =
                            (restored[i].toInt() xor frame.bytes[start + i].toInt()).toByte()
                    }
                }
                restored.copyInto(
                    frame.bytes, index * PAYLOAD, 0,
                    minOf(PAYLOAD, frame.bytes.size - index * PAYLOAD)
                )
                seen[index] = true
                count++
            }
        }

        private val pending = sortedMapOf<Int, Pending>()
        private var newest = -1
        private var displayed = -1

        fun accept(packet: ByteArray, length: Int, now: Long): Frame? {
            if (length !in HEADER + 1..HEADER + PAYLOAD || length > packet.size) return null
            val b = ByteBuffer.wrap(packet, 0, length)
            if (b.int != MAGIC || b.long != token) return null
            val id = b.int;
            val index = b.int;
            val total = b.int;
            val size = b.int
            val width = b.int;
            val height = b.int;
            val pts = b.long;
            val flags = b.int
            if (id < 0 || id <= displayed || id.toLong() < newest.toLong() - 2 ||
                total !in 1..MAX_FRAME || size != length - HEADER ||
                width !in 16..MAX_DIMENSION || height !in 16..MAX_DIMENSION ||
                width % 2 != 0 || height % 2 != 0 || pts < 0 || flags !in 0..KEY_FRAME
            ) return null
            val parts = (total + PAYLOAD - 1) / PAYLOAD
            val groups = (parts + GROUP - 1) / GROUP
            if (index >= 0) {
                if (index >= parts || size != minOf(PAYLOAD, total - index * PAYLOAD)) return null
            } else if (index.toLong() !in -groups.toLong()..-1L || size != PAYLOAD) return null
            newest = maxOf(newest, id)
            pending.entries.removeAll { it.key.toLong() < newest.toLong() - 2 || now - it.value.started > it.value.lifetime }
            val frame = pending.getOrPut(id) {
                Pending(
                    Frame(
                        id,
                        width,
                        height,
                        pts,
                        flags,
                        ByteArray(total)
                    ), now
                )
            }
            if (frame.frame.width != width || frame.frame.height != height || frame.frame.ptsUs != pts ||
                frame.frame.flags != flags || frame.frame.bytes.size != total
            ) return null
            val group = if (index < 0) -index - 1 else index / GROUP
            if (index < 0) {
                if (frame.parity[group] != null) return null
                frame.parity[group] = ByteArray(size).also { b.get(it) }
            } else {
                if (frame.seen[index]) return null
                b.get(frame.frame.bytes, index * PAYLOAD, size)
                frame.seen[index] = true
                frame.count++
            }
            frame.repair(group)
            if (frame.count != parts) return null
            displayed = id
            pending.keys.removeAll { it <= id }
            return frame.frame
        }
    }
}
