package com.android.castappx.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.android.castappx.socket.AudioProtocol
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors

/** Bounded latency: a slow speaker never blocks UDP receive or grows an unbounded queue. */
internal class AudioPlayback {
    private val queue = ArrayBlockingQueue<ByteArray>(2)
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile
    private var closed = false
    @Volatile
    var muted = false
    @Volatile
    private var track: AudioTrack? = null

    init {
        worker.execute {
            var output: AudioTrack? = null
            try {
                val minimum = AudioTrack.getMinBufferSize(
                    AudioProtocol.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                check(minimum > 0)
                output = AudioTrack.Builder().setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(AudioProtocol.SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    )
                    .setBufferSizeInBytes(maxOf(minimum, AudioProtocol.PCM_BYTES * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                track = output
                if (!closed) output.play()
                while (!closed) {
                    val pcm = queue.take()
                    output.setVolume(if (muted) 0f else 1f)
                    output.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                }
            } catch (_: Exception) {
                // Session shutdown interrupts take(); unsupported audio output leaves video usable.
            } finally {
                closed = true
                track = null
                output?.release()
                worker.shutdown()
            }
        }
    }

    fun offer(bytes: ByteArray) {
        if (closed) return
        if (!queue.offer(bytes)) {
            queue.poll()
            queue.offer(bytes)
        }
    }

    fun setMute(value: Boolean) {
        muted = value
        runCatching { track?.setVolume(if (value) 0f else 1f) }
    }

    fun close() {
        closed = true
        queue.clear()
        runCatching { track?.stop() }
        worker.shutdownNow()
    }
}
