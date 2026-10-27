package com.android.castappx.socket

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterInputStream

class ChatProtocolTest {
    @Test
    fun preservesUnicodeNewlinesAndConsecutiveMessagesAcrossPartialReads() {
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        val messages = listOf("你好 👋\n第二行", "second message", "界".repeat(20000))
        messages.forEach { ChatProtocol.write(output, it) }
        val fragmented = object : FilterInputStream(ByteArrayInputStream(bytes.toByteArray())) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, minOf(len, 2))
        }
        val input = DataInputStream(fragmented)
        messages.forEach { assertEquals(it, ChatProtocol.read(input)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedIncomingFrame() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).writeInt(ChatProtocol.MAX_BYTES + 1)
        ChatProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
    }

    @Test(expected = EOFException::class)
    fun detectsTruncatedFrame() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeInt(10); writeByte(1) }
        ChatProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
    }
}
