package com.android.castappx.socket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(JUnit4::class)
class PacedVideoSenderTest {
    @Test
    fun networkStallDoesNotBlockEncodingAndOverflowWaitsForANewKeyframe() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requested = CountDownLatch(1)
        val recovered = CountDownLatch(2)
        val first = AtomicBoolean(true)
        val sent = Collections.synchronizedList(mutableListOf<Int>())
        val error = AtomicReference<Exception?>()
        val sender = PacedVideoSender(1, { packet ->
            if (first.getAndSet(false)) {
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            }
            val id = ByteBuffer.wrap(packet).getInt(12)
            sent.add(id)
            if (id == 10) recovered.countDown()
        }, { requested.countDown() }, { error.set(it) }, pause = {})

        fun frame(id: Int, key: Boolean) = VideoProtocol.Frame(
            id, 640, 480, id.toLong(),
            if (key) VideoProtocol.KEY_FRAME else 0, byteArrayOf(1)
        )
        try {
            sender.offer(frame(0, true))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            // These offers must return even while the socket write is blocked.
            for (id in 1..6) sender.offer(frame(id, false))
            assertEquals(1L, requested.count)
            release.countDown()
            assertTrue(requested.await(3, TimeUnit.SECONDS))
            sender.offer(frame(10, true))
            assertTrue(recovered.await(3, TimeUnit.SECONDS))
            assertTrue(sent.all { it == 0 || it == 10 })
            assertEquals(6, sender.discarded.get())
            assertNull(error.get())
        } finally {
            release.countDown(); sender.close()
        }
    }
}
