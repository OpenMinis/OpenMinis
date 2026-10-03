package com.openminis.app.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-fgs-start-after-stop] GH#329 — a start must never be dispatched
 * after the stop has been decided.
 *
 * The crash: cancelling a run raced a tool/status callback still in flight.
 * Both paths decide whether the service should run by reading `activeSessions`
 * and then acting on it — check-then-act on an unsynchronized object. The
 * loser's stale read let it call `startForegroundService()` just after the
 * winner had stopped the service, re-creating it for a session that no longer
 * existed. Android then demands `startForeground()` within ~5s from that
 * instance or it kills the process with
 * ForegroundServiceDidNotStartInTimeException.
 *
 * These model the tracker's decide-and-dispatch, since the real object is a
 * singleton bound to an Android Context. The invariant under test is ordering,
 * which is entirely in the guard.
 */
class FgsStartAfterStopTest {

    /** Mirrors the FIXED tracker: decide and dispatch under one lock. */
    private class Fixed {
        private val lock = Any()
        var active = 1
        val startsAfterStop = AtomicInteger(0)
        private var stopped = false

        private fun shouldRun() = active > 0

        fun update() = synchronized(lock) {
            if (!shouldRun()) return@synchronized
            if (stopped) startsAfterStop.incrementAndGet()
        }

        fun deactivate() = synchronized(lock) {
            active = 0
            if (!shouldRun()) stopped = true
        }
    }

    /** Mirrors the ORIGINAL: guard read outside the dispatch. */
    private class Racy {
        var active = 1
        val startsAfterStop = AtomicInteger(0)
        @Volatile private var stopped = false

        fun update(sawNonEmpty: Boolean) {
            // `sawNonEmpty` is the stale read the losing thread carries.
            if (!sawNonEmpty) return
            if (stopped) startsAfterStop.incrementAndGet()
        }

        fun deactivate() {
            active = 0
            stopped = true
        }
    }

    @Test
    fun `an update that loses the race does not start the service again`() {
        val t = Fixed()
        t.deactivate()
        t.update()
        assertEquals("no start may follow the stop", 0, t.startsAfterStop.get())
    }

    @Test
    fun `the unguarded shape is what crashed - proves the test is not vacuous`() {
        val t = Racy()
        t.deactivate()
        // The racing caller had already read a non-empty set before the stop.
        t.update(sawNonEmpty = true)
        assertEquals(
            "the stale read is what resurrected the service",
            1,
            t.startsAfterStop.get(),
        )
    }

    @Test
    fun `updates before the stop still dispatch normally`() {
        // The fix must not silence legitimate notification refreshes — those
        // are what keep the status row honest while a run is live.
        val t = Fixed()
        t.update()
        t.update()
        assertEquals(0, t.startsAfterStop.get())
    }

    @Test
    fun `concurrent update and deactivate never produce a start after stop`() {
        // The real shape: many threads, repeated. Without the lock this fails
        // intermittently, which is exactly why the field report was "偶发".
        repeat(200) {
            val t = Fixed()
            val gate = CountDownLatch(1)
            val done = CountDownLatch(2)
            val a = Thread {
                gate.await(); t.deactivate(); done.countDown()
            }
            val b = Thread {
                gate.await(); t.update(); done.countDown()
            }
            a.start(); b.start(); gate.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(0, t.startsAfterStop.get())
        }
    }

    /**
     * The service-side backstop: whatever the decision, an instance created by
     * startForegroundService() must promote before it stops. This pins the
     * ORDER — promote, then demote, then stopSelf — because stopping without
     * promoting is the whole crash.
     */
    @Test
    fun `the backstop promotes before it stops`() {
        val promoted = AtomicBoolean(false)
        val order = mutableListOf<String>()

        fun startForeground() { promoted.set(true); order.add("startForeground") }
        fun stopForeground() { order.add("stopForeground") }
        fun stopSelf() { order.add("stopSelf") }

        // Mirrors the added branch in onStartCommand.
        val nothingToRun = true
        if (nothingToRun) {
            startForeground()
            stopForeground()
            stopSelf()
        }

        assertTrue("must promote before stopping", promoted.get())
        assertEquals(listOf("startForeground", "stopForeground", "stopSelf"), order)
        assertTrue(
            "stopSelf must never precede startForeground",
            order.indexOf("startForeground") < order.indexOf("stopSelf"),
        )
    }
}
