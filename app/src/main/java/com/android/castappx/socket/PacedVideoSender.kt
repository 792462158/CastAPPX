package com.android.castappx.socket

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/** Network pacing never blocks codec draining. A dropped reference requires a new IDR. */
internal class PacedVideoSender(
    private val token: Long,
    private val writePacket: (ByteArray) -> Unit,
    private val requestKeyFrame: () -> Unit,
    private val onError: (Exception) -> Unit,
    private val pause: () -> Unit = { LockSupport.parkNanos(VideoProtocol.PACKET_INTERVAL_NS) }
) {
    private val queue = ArrayBlockingQueue<VideoProtocol.Frame>(4)
    private val worker = Executors.newSingleThreadExecutor()
    private val needsKey = AtomicBoolean(false)
    private var waitingForKey = true
    val discarded = AtomicInteger()
    @Volatile
    private var closed = false

    init {
        worker.execute {
            try {
                while (!closed) {
                    val frame = queue.poll(100, TimeUnit.MILLISECONDS)
                    if (frame == null) {
                        if (needsKey.getAndSet(false)) requestKeyFrame()
                        continue
                    }
                    try {
                        for (packet in VideoProtocol.packets(frame, token)) {
                            if (closed) break
                            writePacket(packet)
                            pause()
                        }
                    } catch (error: Exception) {
                        if (!closed) {
                            recover(); onError(error)
                        }
                    }
                    // Wait until the in-flight unit finishes before asking for another
                    // IDR, so a large keyframe cannot trigger a keyframe request storm.
                    if (!closed && needsKey.getAndSet(false)) requestKeyFrame()
                }
            } catch (_: InterruptedException) {
                // Session shutdown unblocks poll().
            } finally {
                queue.clear(); worker.shutdown()
            }
        }
    }

    @Synchronized
    fun offer(frame: VideoProtocol.Frame) {
        if (closed) return
        if (waitingForKey && !frame.isKeyFrame) {
            discarded.incrementAndGet(); return
        }
        if (frame.isKeyFrame) {
            waitingForKey = false; needsKey.set(false)
        }
        if (queue.offer(frame)) return
        recover()
        if (frame.isKeyFrame) {
            waitingForKey = false; needsKey.set(false); queue.offer(frame)
        } else discarded.incrementAndGet()
    }

    @Synchronized
    private fun recover() {
        while (queue.poll() != null) discarded.incrementAndGet()
        waitingForKey = true
        needsKey.set(true)
    }

    fun close() {
        closed = true; worker.shutdownNow(); queue.clear()
    }
}
