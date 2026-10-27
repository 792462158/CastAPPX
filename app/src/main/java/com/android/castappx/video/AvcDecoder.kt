package com.android.castappx.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.android.castappx.socket.VideoProtocol
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Hardware decode directly to the receiver TextureView; no Bitmap readback per frame. */
internal class AvcDecoder(
    private val onFrame: (Int, Int) -> Unit, private val requestKeyFrame: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val queue = ArrayBlockingQueue<VideoProtocol.Frame>(2)
    private val dropped = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile
    private var closed = false
    @Volatile
    private var surface: Surface? = null
    private var currentSurface: Surface? = null
    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0
    private var lastId = -1
    private var waitingForKey = true
    private var lastRequest = 0L
    private var reportedAt = SystemClock.elapsedRealtime()
    private var outputFrames = 0

    init {
        worker.execute {
            try {
                while (!closed) {
                    val frame = queue.poll(10, TimeUnit.MILLISECONDS)
                    val target = surface
                    if (target !== currentSurface) {
                        releaseCodec(); currentSurface = target; waitingForKey = true
                    }
                    if (dropped.getAndSet(false)) waitingForKey = true
                    if (frame != null) {
                        if (lastId >= 0 && frame.id != lastId + 1) waitingForKey = true
                        lastId = frame.id
                        if (frame.width != width || frame.height != height) waitingForKey = true
                        if (target != null && target.isValid && (!waitingForKey || frame.isKeyFrame)) {
                            if (waitingForKey || codec == null) configure(frame, target)
                            val picture = AvcAccessUnit.picture(frame.bytes)
                                ?: error("Invalid AVC picture data")
                            val index = codec!!.dequeueInputBuffer(0)
                            if (index >= 0) {
                                val input = codec!!.getInputBuffer(index)!!
                                input.clear()
                                if (input.remaining() < picture.size) error("AVC access unit exceeds decoder buffer")
                                input.put(picture)
                                codec!!.queueInputBuffer(index, 0, picture.size, frame.ptsUs, 0)
                            } else waitingForKey = true
                        }
                    }
                    drain()
                    if (waitingForKey && target != null && SystemClock.elapsedRealtime() - lastRequest > 1000) {
                        lastRequest = SystemClock.elapsedRealtime(); requestKeyFrame()
                    }
                }
            } catch (error: Exception) {
                if (!closed) {
                    Log.e("AvcDecoder", "Video decoding failed", error); onError(error.toString())
                }
            } finally {
                releaseCodec(); queue.clear(); worker.shutdown()
            }
        }
    }

    private fun configure(frame: VideoProtocol.Frame, target: Surface) {
        releaseCodec()
        val setup = AvcAccessUnit.configuration(frame.bytes)
            ?: error("AVC keyframe is missing SPS/PPS")
        width = frame.width; height = frame.height
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter {
                !it.isEncoder && it.supportedTypes.any { type ->
                    type.equals(
                        "video/avc",
                        true
                    )
                }
            }
            .sortedBy {
                if (Build.VERSION.SDK_INT >= 29) !it.isHardwareAccelerated
                else it.name.startsWith("OMX.google.") || it.name.startsWith("c2.android.")
            }
        var failure: Exception? = null
        for (info in candidates) {
            val capabilities =
                runCatching { info.getCapabilitiesForType("video/avc") }.getOrNull() ?: continue
            if (capabilities.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)) continue
            val supportsLowLatency = Build.VERSION.SDK_INT >= 30 &&
                    capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            // Even an advertised feature can fail on a vendor codec: retry a fresh
            // instance with ordinary settings before considering the next decoder.
            for (lowLatency in if (supportsLowLatency) listOf(true, false) else listOf(false)) {
                var candidate: MediaCodec? = null
                try {
                    candidate = MediaCodec.createByCodecName(info.name)
                    val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, VideoProtocol.MAX_FRAME)
                        setByteBuffer("csd-0", ByteBuffer.wrap(setup.sps))
                        setByteBuffer("csd-1", ByteBuffer.wrap(setup.pps))
                        if (Build.VERSION.SDK_INT >= 30 && lowLatency) setInteger(
                            MediaFormat.KEY_LOW_LATENCY,
                            1
                        )
                    }
                    candidate.configure(format, target, null, 0)
                    candidate.start()
                    codec = candidate
                    waitingForKey = false
                    Log.i(
                        "AvcDecoder",
                        "Decoder ${info.name}: ${width}x$height, lowLatency=$lowLatency"
                    )
                    return
                } catch (error: Exception) {
                    failure = error
                    runCatching { candidate?.release() }
                    Log.w(
                        "AvcDecoder",
                        "Cannot initialize ${info.name}, lowLatency=$lowLatency",
                        error
                    )
                }
            }
        }
        throw java.io.IOException("No compatible AVC decoder for ${width}x$height", failure)
    }

    private fun drain() {
        val decoder = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (!closed) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            if (index >= 0) {
                decoder.releaseOutputBuffer(index, true)
                onFrame(width, height)
                outputFrames++
                val now = SystemClock.elapsedRealtime()
                if (now - reportedAt >= 5000) {
                    Log.d("AvcDecoder", "Rendered $outputFrames frames in ${now - reportedAt} ms")
                    outputFrames = 0; reportedAt = now
                }
            } else if (index != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) return
        }
    }

    fun offer(frame: VideoProtocol.Frame) {
        if (closed) return
        if (!queue.offer(frame)) {
            queue.poll(); dropped.set(true); queue.offer(frame)
        }
    }

    fun setSurface(value: Surface?) {
        surface = value
    }

    private fun releaseCodec() {
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
    }

    fun close() {
        closed = true; worker.shutdownNow()
    }
}
