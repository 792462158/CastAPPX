package com.android.castappx.socket

import java.io.DataInputStream
import java.io.DataOutputStream

/** Length-prefixed UTF-8 preserves message boundaries, including newlines, over TCP. */
internal object ChatProtocol {
    const val MAX_BYTES = 65536

    fun read(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 1..MAX_BYTES) { "Invalid message length" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    fun write(output: DataOutputStream, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_BYTES) { "Invalid message length" }
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }
}
