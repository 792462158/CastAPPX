package com.android.castappx.socket

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class AudioProtocolTest {
    @Test
    fun acceptsPcmAndSkipsDuplicatesLatePacketsAndWrongSessions() {
        val receiver = AudioProtocol.Receiver(123)
        val pcm = ByteArray(640) { it.toByte() }
        val packet = AudioProtocol.packet(pcm, pcm.size, 123, 2)
        assertArrayEquals(pcm, receiver.accept(packet, packet.size))
        assertNull(receiver.accept(packet, packet.size))
        val late = AudioProtocol.packet(pcm, 640, 123, 1)
        assertNull(receiver.accept(late, late.size))
        val foreign = AudioProtocol.packet(pcm, 640, 124, 100)
        assertNull(receiver.accept(foreign, foreign.size))
        val next = AudioProtocol.packet(pcm, 320, 123, 4)
        assertArrayEquals(pcm.copyOf(320), receiver.accept(next, next.size))
    }

    @Test
    fun rejectsMalformedAndVideoPacketsWithoutPoisoningSequence() {
        val receiver = AudioProtocol.Receiver(1)
        val packet = AudioProtocol.packet(ByteArray(640), 640, 1, 0)
        assertNull(receiver.accept(packet, 4))
        assertNull(receiver.accept(packet, packet.size + 2))
        val negative = packet.copyOf()
        ByteBuffer.wrap(negative).putInt(12, -1)
        assertNull(receiver.accept(negative, negative.size))
        val video =
            VideoProtocol.packets(VideoProtocol.Frame(1, 640, 480, 0, 0, ByteArray(20)), 1).first()
        assertNull(receiver.accept(video, video.size))
        assertArrayEquals(ByteArray(640), receiver.accept(packet, packet.size))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedOutgoingAudio() {
        AudioProtocol.packet(
            ByteArray(AudioProtocol.MAX_ENCODED_BYTES + 1),
            AudioProtocol.MAX_ENCODED_BYTES + 1,
            1,
            0
        )
    }

    @Test
    fun acceptsOddLengthEncodedAudio() {
        val packet = AudioProtocol.packet(byteArrayOf(1, 2, 3), 3, 1, 0)
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            AudioProtocol.Receiver(1).accept(packet, packet.size)
        )
    }
}
