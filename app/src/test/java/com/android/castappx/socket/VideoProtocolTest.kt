package com.android.castappx.socket

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.ByteBuffer

@RunWith(JUnit4::class)
class VideoProtocolTest {
    @Test
    fun reconstructsReorderedPacketsAndIgnoresDuplicates() {
        val source = ByteArray(75001) { (it * 31).toByte() }
        val packets = VideoProtocol.packets(source, 123L, 1)
        val assembler = VideoProtocol.Assembler(123L)
        assertNull(assembler.accept(packets.last(), packets.last().size, 0))
        assertNull(assembler.accept(packets.last(), packets.last().size, 0))
        var completed: ByteArray? = null
        packets.dropLast(1).reversed().forEach { completed = assembler.accept(it, it.size, 10) }
        assertArrayEquals(source, completed)
        assertNull(assembler.accept(packets.first(), packets.first().size, 20))
    }

    @Test
    fun rejectsWrongSessionMalformedAndOversizedPackets() {
        val packet = VideoProtocol.packets(ByteArray(2000), 12L, 1).first()
        val assembler = VideoProtocol.Assembler(12L)
        assertNull(VideoProtocol.Assembler(13L).accept(packet, packet.size, 0))
        assertNull(assembler.accept(packet, 5, 0))
        assertNull(assembler.accept(packet, packet.size - 1, 0))
        val oversized = packet.copyOf()
        ByteBuffer.wrap(oversized).putInt(20, VideoProtocol.MAX_FRAME + 1)
        assertNull(assembler.accept(oversized, oversized.size, 0))
        val invalidIndex = packet.copyOf()
        ByteBuffer.wrap(invalidIndex).putInt(16, Int.MAX_VALUE)
        assertNull(assembler.accept(invalidIndex, invalidIndex.size, 0))
    }

    @Test
    fun discardsIncompleteExpiredAndOlderFramesThenRecovers() {
        val assembler = VideoProtocol.Assembler(1L)
        val old = VideoProtocol.packets(ByteArray(2000), 1L, 1)
        assertNull(assembler.accept(old[0], old[0].size, 0))
        assertNull(assembler.accept(old[1], old[1].size, 1600))
        val next = VideoProtocol.packets(byteArrayOf(1, 2, 3), 1L, 2).single()
        assertArrayEquals(byteArrayOf(1, 2, 3), assembler.accept(next, next.size, 1700))
        assertNull(assembler.accept(old[1], old[1].size, 1800))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedOutgoingFrames() {
        VideoProtocol.packets(ByteArray(VideoProtocol.MAX_FRAME + 1), 1L, 1)
    }
}
