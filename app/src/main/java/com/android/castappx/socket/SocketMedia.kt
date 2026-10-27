package com.android.castappx.socket

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.android.castappx.audio.AacCodec
import com.android.castappx.audio.AudioPlayback
import com.android.castappx.video.AvcDecoder
import com.android.castappx.video.AvcEncoder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Hardware H.264 + UDP repair packets. Audio has its own send socket to avoid contention. */
internal class SocketMedia(
    localPort: Int,
    private val address: InetAddress,
    private val remotePort: Int,
    private val token: Long,
    onError: (String) -> Unit,
    requestKeyFrame: () -> Unit = {},
    onFrame: ((Int, Int) -> Unit)? = null
) {
    val isSender = onFrame == null
    private val socket = DatagramSocket(null).apply {
        receiveBufferSize = 2 * 1024 * 1024
        sendBufferSize = 128 * 1024
        bind(java.net.InetSocketAddress(localPort))
    }
    private val audioSocket = if (isSender) DatagramSocket() else null
    private val encoded = AtomicInteger()
    private val sentPackets = AtomicInteger()
    private val syncPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val videoSender = if (isSender) PacedVideoSender(token, { data ->
        socket.send(DatagramPacket(data, data.size, address, remotePort))
        sentPackets.incrementAndGet()
    }, { syncPending.set(true) }, { error ->
        if (!socket.isClosed) Log.w("SocketMedia", "Unable to send video", error)
    }) else null
    private val encoder = if (isSender) AvcEncoder({ frame ->
        encoded.incrementAndGet()
        videoSender?.offer(frame)
    }, onError) else null
    private val decoder =
        if (onFrame != null) AvcDecoder(onFrame, requestKeyFrame, onError) else null
    private val audio = if (isSender) null else AudioPlayback()
    private val audioCodec = AacCodec(isSender, { bytes ->
        if (isSender) sendEncodedAudio(bytes) else audio?.offer(bytes)
    }, onError)
    private val receiver = if (isSender) null else Executors.newSingleThreadExecutor()
    private val audioSequence = AtomicInteger()

    init {
        receiver?.execute {
            val video = VideoProtocol.Assembler(token)
            val sound = AudioProtocol.Receiver(token)
            val buffer = ByteArray(
                maxOf(
                    VideoProtocol.HEADER + VideoProtocol.PAYLOAD,
                    AudioProtocol.HEADER + AudioProtocol.MAX_ENCODED_BYTES
                ) + 1
            )
            var audioPackets = 0
            var videoPackets = 0
            var completed = 0
            var reportAt = SystemClock.elapsedRealtime()
            try {
                while (!socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (packet.address != address) continue
                    sound.accept(buffer, packet.length)
                        ?.let { audioPackets++; audioCodec.offer(it) }
                    if (packet.length >= VideoProtocol.HEADER) videoPackets++
                    video.accept(buffer, packet.length, SystemClock.elapsedRealtime())
                        ?.let { completed++; decoder?.offer(it) }
                    val now = SystemClock.elapsedRealtime()
                    if (now - reportAt >= 5000) {
                        Log.d(
                            "SocketMedia",
                            "Received $videoPackets packets, $audioPackets audio, $completed AVC frames in ${now - reportAt} ms"
                        )
                        reportAt = now; videoPackets = 0; audioPackets = 0; completed = 0
                    }
                }
            } catch (error: Exception) {
                if (!socket.isClosed) {
                    Log.e("SocketMedia", "Media receive stopped", error); onError(error.toString())
                }
            }
        }
    }

    fun statistics(): String =
        "encoded=${encoded.get()} videoPacketsSent=${sentPackets.get()} droppedBeforeSend=${videoSender?.discarded?.get() ?: 0}"

    fun send(bitmap: Bitmap) {
        if (syncPending.getAndSet(false)) encoder?.requestKeyFrame()
        encoder?.offer(bitmap)
    }

    fun requestKeyFrame() {
        encoder?.requestKeyFrame()
    }

    fun setSurface(surface: Surface?) {
        decoder?.setSurface(surface)
    }

    fun sendAudio(pcm: ByteArray, size: Int) {
        if (isSender) audioCodec.offer(pcm, size)
    }

    private fun sendEncodedAudio(bytes: ByteArray) {
        if (bytes.size !in 1..AudioProtocol.MAX_ENCODED_BYTES) return
        val transport = audioSocket ?: return
        if (transport.isClosed) return
        try {
            val data =
                AudioProtocol.packet(bytes, bytes.size, token, audioSequence.getAndIncrement())
            transport.send(DatagramPacket(data, data.size, address, remotePort))
        } catch (_: Exception) { /* Do not retransmit stale audio. */
        }
    }

    fun setMuted(muted: Boolean) {
        audio?.setMute(muted)
    }

    fun close() {
        socket.close(); audioSocket?.close()
        videoSender?.close(); encoder?.close(); decoder?.close(); audioCodec.close(); receiver?.shutdownNow(); audio?.close()
    }
}
