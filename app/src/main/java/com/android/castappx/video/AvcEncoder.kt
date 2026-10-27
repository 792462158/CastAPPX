package com.android.castappx.video

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.android.castappx.socket.VideoProtocol
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class AvcEncoder(
    private val onFrame: (VideoProtocol.Frame) -> Unit,
    private val onError: (String) -> Unit
) {
    private data class Input(val bitmap: Bitmap, val pts: Long)

    private val queue = ArrayBlockingQueue<Input>(1)
    private val worker = Executors.newSingleThreadExecutor()
    private val sync = AtomicBoolean(true)
    @Volatile
    private var closed = false
    private var codec: MediaCodec? = null
    private var gl: EncoderGl? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var config = ByteArray(0)
    private var sequence = 0
    private var stage = "initializing"
    private var hasInput = false
    private var bitrateAdjusted = false

    init {
        worker.execute {
            try {
                while (!closed) {
                    val input = queue.poll(10, TimeUnit.MILLISECONDS)
                    if (input != null) {
                        try {
                            val scale =
                                minOf(1f, 960f / maxOf(input.bitmap.width, input.bitmap.height))
                            val w = maxOf(16, (input.bitmap.width * scale).toInt() / 16 * 16)
                            val h = maxOf(16, (input.bitmap.height * scale).toInt() / 16 * 16)
                            if (codec == null || width != w || height != h) configure(w, h)
                            if (hasInput && sync.getAndSet(false)) {
                                stage =
                                    "requesting keyframe"; codec!!.setParameters(Bundle().apply {
                                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                                })
                            }
                            drain()
                            stage = "submitting encoder surface"
                            gl!!.draw(input.bitmap, width, height, input.pts)
                            hasInput = true
                        } finally {
                            input.bitmap.recycle()
                        }
                    }
                    drain()
                }
            } catch (error: Exception) {
                if (!closed) {
                    Log.e("AvcEncoder", "Video encoding failed", error); onError("$stage: $error")
                }
            } finally {
                closed = true
                releaseCodec()
                clearQueue()
                worker.shutdown()
            }
        }
    }

    private fun configure(w: Int, h: Int) {
        releaseCodec()
        width = w; height = h; config = ByteArray(0)
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            it.isEncoder && it.supportedTypes.any { type -> type.equals("video/avc", true) } &&
                    (if (Build.VERSION.SDK_INT >= 29) it.isHardwareAccelerated
                    else !it.name.startsWith("OMX.google.") && !it.name.startsWith("c2.android.")) &&
                    it.getCapabilitiesForType("video/avc").colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
            .sortedBy { if (rateMode(it) == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) 1 else 0 }
            .firstOrNull() ?: error("No hardware H.264 encoder available")
        codec = MediaCodec.createByCodecName(info.name)
        val format = MediaFormat.createVideoFormat("video/avc", w, h).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 200_000)
            rateMode(info)?.let { setInteger(MediaFormat.KEY_BITRATE_MODE, it) }
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_FRAME_RATE, 15)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        stage = "configuring ${info.name} ${w}x$h"
        codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        stage = "creating encoder surface"
        surface = codec!!.createInputSurface()
        gl = EncoderGl(surface!!)
        stage = "starting ${info.name}"
        codec!!.start()
        hasInput = false
        bitrateAdjusted = false
        sync.set(false)
        Log.i("AvcEncoder", "Hardware encoder ${info.name}: ${w}x$h, 200 kbps")
    }

    private fun drain() {
        val encoder = codec ?: return
        val info = MediaCodec.BufferInfo()
        stage = "draining encoded data"
        while (!closed) {
            val index = encoder.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = encoder.outputFormat
                    val negotiated =
                        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(
                            MediaFormat.KEY_BIT_RATE
                        ) else 200_000
                    Log.i("AvcEncoder", "${encoder.name} negotiated bitrate=$negotiated")
                    // Android 12+ can raise a VBR configure request to its quality floor.
                    // Apply the streaming budget through the public runtime bitrate API
                    // after output starts; never assume the configure request was honored.
                    if (!bitrateAdjusted && negotiated > 200_000) {
                        bitrateAdjusted = true
                        try {
                            encoder.setParameters(Bundle().apply {
                                putInt(
                                    MediaCodec.PARAMETER_KEY_VIDEO_BITRATE,
                                    200_000
                                )
                            })
                            Log.i("AvcEncoder", "Runtime bitrate updated to 200000")
                        } catch (error: Exception) {
                            Log.w("AvcEncoder", "Runtime bitrate update rejected", error)
                        }
                    }
                    val updatedConfig =
                        (0..1).mapNotNull { format.getByteBuffer("csd-$it")?.duplicate() }
                            .fold(ByteArray(0)) { result, b ->
                                result + ByteArray(b.remaining()).also {
                                    b.get(
                                        it
                                    )
                                }
                            }
                    if (updatedConfig.isNotEmpty()) config = updatedConfig
                }

                index >= 0 -> {
                    try {
                        val buffer = encoder.getOutputBuffer(index) ?: continue
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size).also { buffer.get(it) }
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            if (bytes.isNotEmpty()) config = bytes
                        } else if (bytes.isNotEmpty()) {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val accessUnit = if (key) config + bytes else bytes
                            if (accessUnit.size <= VideoProtocol.MAX_FRAME) onFrame(
                                VideoProtocol.Frame(
                                    sequence++,
                                    width,
                                    height,
                                    info.presentationTimeUs,
                                    if (key) VideoProtocol.KEY_FRAME else 0,
                                    accessUnit
                                )
                            )
                            else sync.set(true)
                        }
                    } finally {
                        encoder.releaseOutputBuffer(index, false)
                    }
                }

                else -> return
            }
        }
    }

    private fun rateMode(info: MediaCodecInfo): Int? {
        val modes = info.getCapabilitiesForType("video/avc").encoderCapabilities ?: return null
        if (modes.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR))
            return MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        if (Build.VERSION.SDK_INT >= 31 && modes.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD))
            return MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD
        return MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR.takeIf {
            modes.isBitrateModeSupported(
                it
            )
        }
    }

    @Synchronized
    fun offer(bitmap: Bitmap) {
        if (closed) return
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val input = Input(copy, SystemClock.elapsedRealtimeNanos() / 1000)
        if (!queue.offer(input)) {
            queue.poll()?.bitmap?.recycle(); if (!queue.offer(input)) copy.recycle()
        }
        if (closed) clearQueue()
    }

    fun requestKeyFrame() {
        sync.set(true)
    }

    private fun clearQueue() {
        while (true) (queue.poll() ?: break).bitmap.recycle()
    }

    private fun releaseCodec() {
        gl?.close(); gl = null
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        surface?.release(); surface = null
    }

    fun close() {
        closed = true; worker.shutdownNow()
    }
}
