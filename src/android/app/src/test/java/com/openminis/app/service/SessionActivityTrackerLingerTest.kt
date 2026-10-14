package com.openminis.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-fgs-stop-linger] Tracker-level wiring of the deferred stop.
 *
 * The tracker is a plain object with StateFlows; its start/stop helpers
 * no-op without an initialized appContext (unit tests never initialize it),
 * so the observable contract here is the PENDING state of the deferred stop:
 * emptiness schedules it, any re-activation cancels it. This pins the exact
 * wiring that closes the stopService()/startForegroundService() crossing
 * behind crash-2026-10-15_01-00-01 — the primitive itself is covered by
 * [ServiceStopLingerTest].
 */
class SessionActivityTrackerLingerTest {

    private fun cleanup(vararg ids: String) {
        ids.forEach {
            SessionActivityTracker.setInactive(it)
            SessionActivityTracker.setAbsent(it)
        }
        SessionActivityTracker.clearPresence()
    }

    @Test
    fun `last active session leaving schedules a deferred stop`() {
        SessionActivityTracker.setActive("linger-a")
        assertFalse(SessionActivityTracker.isStopPendingForTest)

        SessionActivityTracker.setInactive("linger-a")
        assertTrue(SessionActivityTracker.isStopPendingForTest)

        cleanup("linger-a")
    }

    @Test
    fun `a new active session cancels the pending stop`() {
        SessionActivityTracker.setActive("linger-b1")
        SessionActivityTracker.setInactive("linger-b1")
        assertTrue(SessionActivityTracker.isStopPendingForTest)

        // The rapid follow-up command — the exact sequence that used to cross
        // stopService() with the next startForegroundService() at AM.
        SessionActivityTracker.setActive("linger-b2")
        assertFalse(SessionActivityTracker.isStopPendingForTest)

        cleanup("linger-b1", "linger-b2")
    }

    @Test
    fun `presence cancels the pending stop too`() {
        SessionActivityTracker.setActive("linger-c")
        SessionActivityTracker.setInactive("linger-c")
        assertTrue(SessionActivityTracker.isStopPendingForTest)

        // User lands back on the chat screen while the stop is pending —
        // presence must re-hold the service, not let it be torn down.
        SessionActivityTracker.setPresent("linger-c")
        assertFalse(SessionActivityTracker.isStopPendingForTest)

        cleanup("linger-c")
    }

    @Test
    fun `last presence leaving schedules a deferred stop`() {
        SessionActivityTracker.setPresent("linger-d")
        assertFalse(SessionActivityTracker.isStopPendingForTest)

        SessionActivityTracker.setAbsent("linger-d")
        assertTrue(SessionActivityTracker.isStopPendingForTest)

        cleanup("linger-d")
    }

    @Test
    fun `a still-active session keeps the stop unscheduled`() {
        SessionActivityTracker.setActive("linger-e1")
        SessionActivityTracker.setActive("linger-e2")
        SessionActivityTracker.setInactive("linger-e2")
        assertFalse(SessionActivityTracker.isStopPendingForTest)

        cleanup("linger-e1", "linger-e2")
    }
}
