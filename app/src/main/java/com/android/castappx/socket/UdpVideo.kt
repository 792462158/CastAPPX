package com.android.castappx.socket

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/** One transport per TCP session. Encoding/sending is called only from capture worker threads. */
internal class UdpVideo(
    localPort: Int,
    private val address: InetAddress,
    private val remotePort: Int,
    private val token: Long,
    private val onFrame: ((Bitmap) -> Unit)? = null
) {
    val isSender get() = onFrame == null
    private val socket = DatagramSocket(localPort)
    private val receiver = if (onFrame != null) Executors.newSingleThreadExecutor() else null
    private var sequence = 0
    private var lastSent = 0L

    init {
        receiver?.execute {
            val assembler = VideoProtocol.Assembler(token)
            val buffer = ByteArray(VideoProtocol.HEADER + VideoProtocol.PAYLOAD)
            try {
                while (!socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (packet.address != address) continue
                    val bytes =
                        assembler.accept(buffer, packet.length, SystemClock.elapsedRealtime())
                            ?: continue
                    // Inspect dimensions before allocating pixel memory.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth !in 1..960 || bounds.outHeight !in 1..960) continue
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { onFrame?.invoke(it) }
                }
            } catch (_: Exception) {
                // Closing the TCP session closes this socket and interrupts receive().
            }
        }
    }

    @Synchronized
    fun send(bitmap: Bitmap) {
        val now = SystemClock.elapsedRealtime()
        if (!isSender || socket.isClosed || now - lastSent < 125) return
        lastSent = now
        val scale = minOf(1f, 960f / maxOf(bitmap.width, bitmap.height))
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            maxOf(1, (bitmap.width * scale).toInt()),
            maxOf(1, (bitmap.height * scale).toInt()),
            true
        )
        try {
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 65, output)
            val bytes = output.toByteArray()
            if (bytes.size > VideoProtocol.MAX_FRAME) return
            VideoProtocol.packets(bytes, token, sequence++).forEach { data ->
                socket.send(DatagramPacket(data, data.size, address, remotePort))
            }
        } catch (_: Exception) {
            // UDP is best effort; a dropped frame is replaced by the next captured frame.
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    fun close() {
        socket.close()
        receiver?.shutdownNow()
    }
}
