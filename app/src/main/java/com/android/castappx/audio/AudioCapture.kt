package com.android.castappx.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import com.android.castappx.socket.AudioProtocol
import java.util.concurrent.Executors

/** Owns a recorder and releases it on its worker after stop unblocks read(). */
internal class AudioCapture private constructor(
    private val record: AudioRecord,
    private val onPcm: (ByteArray, Int) -> Unit,
    private val onError: () -> Unit
) {
    @Volatile
    private var running = true
    private val worker = Executors.newSingleThreadExecutor()

    init {
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED)
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        } catch (error: Exception) {
            record.release()
            worker.shutdown()
            throw error
        }
        worker.execute {
            try {
                val bytes = ByteArray(AudioProtocol.PCM_BYTES)
                while (running) {
                    val count = record.read(bytes, 0, bytes.size, AudioRecord.READ_BLOCKING)
                    if (count < 0) error("Audio read failed: $count")
                    if (running && count > 0) onPcm(bytes, count)
                }
            } catch (_: Exception) {
                if (running) onError()
            } finally {
                record.release()
                worker.shutdown()
            }
        }
    }

    fun close() {
        running = false
        runCatching { record.stop() }
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun start(
            projection: MediaProjection? = null,
            onError: () -> Unit,
            onPcm: (ByteArray, Int) -> Unit
        ): AudioCapture {
            val format = AudioFormat.Builder().setSampleRate(AudioProtocol.SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val minimum = AudioRecord.getMinBufferSize(
                AudioProtocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            check(minimum > 0)
            val builder = AudioRecord.Builder().setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimum, AudioProtocol.PCM_BYTES * 4))
            if (projection == null) builder.setAudioSource(MediaRecorder.AudioSource.MIC)
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setAudioPlaybackCaptureConfig(
                    AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
                )
            } else error("Device audio capture requires Android 10")
            return AudioCapture(builder.build(), onPcm, onError)
        }
    }
}
