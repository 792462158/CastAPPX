package com.android.castappx.socket

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.ByteBuffer

@RunWith(JUnit4::class)
class VideoProtocolTest {
    private fun frame(id: Int, size: Int = 22001) = VideoProtocol.Frame(
        id, 640, 480,
        id.toLong() * 66000, VideoProtocol.KEY_FRAME, ByteArray(size) { (it * 31).toByte() })

    private fun index(packet: ByteArray) = ByteBuffer.wrap(packet).getInt(16)

    @Test
    fun reconstructsReorderedPacketsAndIgnoresDuplicates() {
        val source = frame(1)
        val packets = VideoProtocol.packets(source, 123)
        val receiver = VideoProtocol.Assembler(123)
        var complete: VideoProtocol.Frame? = null
        packets.reversed().forEach { packet ->
            receiver.accept(packet, packet.size, 1)?.let { complete = it }
            assertNull(receiver.accept(packet, packet.size, 1))
        }
        assertArrayEquals(source.bytes, complete!!.bytes)
        assertEquals(source.ptsUs, complete!!.ptsUs)
    }

    @Test
    fun repairsOneLostDataPacketInEveryGroup() {
        val source = frame(2)
        val packets =
            VideoProtocol.packets(source, 1).filter { index(it) < 0 || index(it) % 8 != 0 }
        val receiver = VideoProtocol.Assembler(1)
        var complete: VideoProtocol.Frame? = null
        packets.reversed()
            .forEach { receiver.accept(it, it.size, 1)?.let { result -> complete = result } }
        assertArrayEquals(source.bytes, complete!!.bytes)
    }

    @Test
    fun incompleteFrameDoesNotBlockTheNextFrame() {
        val receiver = VideoProtocol.Assembler(1)
        VideoProtocol.packets(frame(1), 1).filter { index(it) != 0 && index(it) != 1 }
            .forEach { assertNull(receiver.accept(it, it.size, 0)) }
        var complete: VideoProtocol.Frame? = null
        VideoProtocol.packets(frame(2), 1)
            .forEach { receiver.accept(it, it.size, 1)?.let { result -> complete = result } }
        assertEquals(2, complete!!.id)
        val old = VideoProtocol.packets(frame(1), 1).first()
        assertNull(receiver.accept(old, old.size, 2))
    }

    @Test
    fun adjacentFramesCanBeInterleaved() {
        val receiver = VideoProtocol.Assembler(1)
        val first = VideoProtocol.packets(frame(1, 1000), 1).filter { index(it) >= 0 }
        val second = VideoProtocol.packets(frame(2, 1000), 1).filter { index(it) >= 0 }
        assertNull(receiver.accept(first[0], first[0].size, 0))
        assertNull(receiver.accept(second[0], second[0].size, 1))
        assertEquals(1, receiver.accept(first[1], first[1].size, 2)!!.id)
        assertEquals(2, receiver.accept(second[1], second[1].size, 3)!!.id)
    }

    @Test
    fun validatesSessionSizesAndParityIndicesBeforeAllocation() {
        val packet = VideoProtocol.packets(frame(1), 1).first()
        val receiver = VideoProtocol.Assembler(1)
        assertNull(VideoProtocol.Assembler(2).accept(packet, packet.size, 0))
        assertNull(receiver.accept(packet, 3, 0))
        assertNull(receiver.accept(packet, packet.size - 1, 0))
        val huge = packet.copyOf(); ByteBuffer.wrap(huge).putInt(20, Int.MAX_VALUE)
        assertNull(receiver.accept(huge, huge.size, 0))
        val invalid = packet.copyOf(); ByteBuffer.wrap(invalid).putInt(16, Int.MIN_VALUE)
        assertNull(receiver.accept(invalid, invalid.size, 0))
        val wrongSize = packet.copyOf(); ByteBuffer.wrap(wrongSize).putInt(28, Int.MAX_VALUE)
        assertNull(receiver.accept(wrongSize, wrongSize.size, 0))
    }

    @Test
    fun expiredPartsCannotCompleteAFrame() {
        val receiver = VideoProtocol.Assembler(1)
        val data = VideoProtocol.packets(frame(1, 1000), 1).filter { index(it) >= 0 }
        assertNull(receiver.accept(data[0], data[0].size, 0))
        assertNull(receiver.accept(data[1], data[1].size, 501))
    }

    @Test
    fun pacedLargeKeyframeCanFinishAfterHalfASecond() {
        val source = frame(3, 32000)
        val packets = VideoProtocol.packets(source, 1)
        val receiver = VideoProtocol.Assembler(1)
        var complete: VideoProtocol.Frame? = null
        packets.forEachIndexed { index, packet ->
            receiver.accept(packet, packet.size, index * 12L)?.let { complete = it }
        }
        assertArrayEquals(source.bytes, complete!!.bytes)
    }

    @Test
    fun oversizedKeyframeBudgetStillExpiresWithinTwoSeconds() {
        val packets = VideoProtocol.packets(frame(1, 200000), 1).filter { index(it) >= 0 }
        val receiver = VideoProtocol.Assembler(1)
        assertNull(receiver.accept(packets.first(), packets.first().size, 0))
        var complete: VideoProtocol.Frame? = null
        packets.drop(1)
            .forEach { receiver.accept(it, it.size, 2001)?.let { result -> complete = result } }
        assertNull(complete)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedFrames() {
        VideoProtocol.packets(frame(1, VideoProtocol.MAX_FRAME + 1), 1)
    }
}
