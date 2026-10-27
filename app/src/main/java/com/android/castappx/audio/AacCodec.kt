package com.android.castappx.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import com.android.castappx.socket.AudioProtocol
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** AAC-LC, mono 16 kHz / 32 kbps. Independent audio frames tolerate skipped UDP packets. */
internal class AacCodec(
    private val encode: Boolean, private val output: (ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {
    private val queue = ArrayBlockingQueue<ByteArray>(if (encode) 5 else 2)
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile
    private var closed = false

    init {
        worker.execute {
            var codec: MediaCodec? = null
            try {
                codec = if (encode) MediaCodec.createEncoderByType("audio/mp4a-latm")
                else MediaCodec.createDecoderByType("audio/mp4a-latm")
                val format =
                    MediaFormat.createAudioFormat("audio/mp4a-latm", AudioProtocol.SAMPLE_RATE, 1)
                        .apply {
                            setInteger(
                                MediaFormat.KEY_AAC_PROFILE,
                                MediaCodecInfo.CodecProfileLevel.AACObjectLC
                            )
                            setInteger(MediaFormat.KEY_BIT_RATE, 32000)
                            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
                            if (!encode) setByteBuffer(
                                "csd-0",
                                ByteBuffer.wrap(byteArrayOf(0x14, 0x08))
                            )
                        }
                codec.configure(
                    format,
                    null,
                    null,
                    if (encode) MediaCodec.CONFIGURE_FLAG_ENCODE else 0
                )
                codec.start()
                val pcm = ByteArray(2048)
                var fill = 0
                var samples = 0L
                val info = MediaCodec.BufferInfo()
                fun drain() {
                    while (!closed) {
                        val index = codec.dequeueOutputBuffer(info, 0)
                        if (index >= 0) {
                            try {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    val buffer = codec.getOutputBuffer(index)!!
                                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                    output(ByteArray(info.size).also { buffer.get(it) })
                                }
                            } finally {
                                codec.releaseOutputBuffer(index, false)
                            }
                        } else if (index != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) return
                    }
                }

                fun submit(bytes: ByteArray) {
                    drain()
                    val index = codec.dequeueInputBuffer(0)
                    if (index < 0) return // Keep latency bounded when a codec cannot keep up.
                    codec.getInputBuffer(index)!!.apply { clear(); put(bytes) }
                    val pts =
                        if (encode) samples * 1_000_000 / AudioProtocol.SAMPLE_RATE else SystemClock.elapsedRealtimeNanos() / 1000
                    codec.queueInputBuffer(index, 0, bytes.size, pts, 0)
                    if (encode) samples += bytes.size / 2
                }
                while (!closed) {
                    val bytes = queue.poll(10, TimeUnit.MILLISECONDS)
                    if (bytes != null) {
                        if (!encode) submit(bytes)
                        else {
                            var offset = 0
                            while (offset < bytes.size) {
                                val count = minOf(pcm.size - fill, bytes.size - offset)
                                bytes.copyInto(pcm, fill, offset, offset + count)
                                fill += count; offset += count
                                if (fill == pcm.size) {
                                    submit(pcm); fill = 0
                                }
                            }
                        }
                    }
                    drain()
                }
            } catch (error: Exception) {
                if (!closed) {
                    Log.e("AacCodec", "Audio codec failed", error); onError(error.toString())
                }
            } finally {
                closed = true
                runCatching { codec?.stop() }; runCatching { codec?.release() }
                queue.clear(); worker.shutdown()
            }
        }
    }

    fun offer(bytes: ByteArray, size: Int = bytes.size) {
        if (closed) return
        val copy = bytes.copyOf(size)
        if (!queue.offer(copy)) {
            queue.poll(); queue.offer(copy)
        }
    }

    fun close() {
        closed = true; worker.shutdownNow()
    }
}
