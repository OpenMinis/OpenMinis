package com.openminis.app.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayComposeBusTest {

    @Test
    fun stickyConsumesOnce() {
        OverlayComposeBus.submit("sid-1", "  hello  ")
        assertEquals("sid-1", OverlayComposeBus.peekStickySessionId())
        assertEquals("hello", OverlayComposeBus.consumeSticky("sid-1"))
        assertNull(OverlayComposeBus.consumeSticky("sid-1"))
    }

    @Test
    fun wrongSessionDoesNotConsume() {
        OverlayComposeBus.submit("sid-2", "keep")
        assertNull(OverlayComposeBus.consumeSticky("other"))
        assertEquals("keep", OverlayComposeBus.consumeSticky("sid-2"))
    }

    @Test
    fun concurrentConsumeHasExactlyOneWinner() {
        OverlayComposeBus.consumeSticky("sid-race")
        OverlayComposeBus.submit("sid-race", "once")

        val start = CountDownLatch(1)
        val done = CountDownLatch(16)
        val winners = AtomicInteger(0)
        repeat(16) {
            Thread {
                start.await()
                if (OverlayComposeBus.consumeSticky("sid-race") != null) winners.incrementAndGet()
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, winners.get())    }
}
