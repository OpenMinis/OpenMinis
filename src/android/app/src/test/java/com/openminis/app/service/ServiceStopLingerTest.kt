package com.openminis.app.service

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-fgs-stop-linger] Contract for the deferred foreground-service stop.
 *
 * The linger closes an install-time ForegroundServiceDidNotStartInTimeException
 * (crash-2026-10-15_01-00-01): a probe finishing in ~7ms drove
 * setInactive → stopService() to cross in flight with the next command's
 * startForegroundService(), and the platform armed the 5-10s foreground
 * deadline against a service instance that had not been created yet.
 *
 * Virtual-time tests (no real sleeps): schedule fires after the window,
 * cancel prevents it, rescheduling restarts the window.
 */
class ServiceStopLingerTest {
    private val lingerMs = 10_000L

    @Test
    fun `stop fires exactly at the end of the linger window`() = runTest {
        val fired = AtomicInteger(0)
        val linger = ServiceStopLinger(backgroundScope, lingerMs, onStop = { fired.incrementAndGet() })

        linger.schedule()
        assertTrue(linger.isPending)

        advanceTimeBy(lingerMs - 1)
        assertEquals(0, fired.get())

        // advanceTimeBy runs tasks due strictly BEFORE the target; a task
        // landing exactly on the boundary needs runCurrent() to execute.
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, fired.get())
        assertFalse(linger.isPending)
    }

    @Test
    fun `cancel prevents the stop entirely`() = runTest {
        val fired = AtomicInteger(0)
        val linger = ServiceStopLinger(backgroundScope, lingerMs, onStop = { fired.incrementAndGet() })

        linger.schedule()
        advanceTimeBy(lingerMs / 2)
        linger.cancel()

        // Far past the original deadline — nothing may fire after a cancel:
        // the next command's startForegroundService() must never meet a
        // teardown it cancelled.
        advanceTimeBy(lingerMs * 10)
        assertEquals(0, fired.get())
        assertFalse(linger.isPending)
    }

    @Test
    fun `rescheduling restarts the quiet window`() = runTest {
        val fired = AtomicInteger(0)
        val linger = ServiceStopLinger(backgroundScope, lingerMs, onStop = { fired.incrementAndGet() })

        linger.schedule()
        advanceTimeBy(lingerMs - 1_000)
        // A second emptiness event (e.g. the next component in an assembly
        // finishing) — the window measures quiet time since the LAST event.
        linger.schedule()
        advanceTimeBy(lingerMs - 1_000)
        // Past the ORIGINAL deadline; the restart must have held the stop.
        assertEquals(0, fired.get())

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, fired.get())
    }

    @Test
    fun `double schedule fires exactly one stop`() = runTest {
        val fired = AtomicInteger(0)
        val linger = ServiceStopLinger(backgroundScope, lingerMs, onStop = { fired.incrementAndGet() })

        linger.schedule()
        linger.schedule()
        advanceTimeBy(lingerMs + 1_000)
        assertEquals(1, fired.get())
    }
}
