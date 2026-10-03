package com.openminis.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-overlay-completion-survives-stop] The completion capsule must
 * outlive the stream that produced it.
 *
 * Reported: "任务结束后悬浮窗就退出了，没有展示完成状态和 last message". The
 * linger itself was implemented (AgentForegroundService renders an outcome
 * glyph, a localized completion word and the reply excerpt when `!isRunning`),
 * and the reply is published BEFORE `setInactive` at all five agent-loop exit
 * sites — so the state was right. What killed it was ownership: the capsule is
 * a WindowManager view owned by AgentForegroundService, and the end-of-turn
 * `setInactive` could stop that service in the same call, whose `onDestroy`
 * unconditionally hides the capsule and cancels the observer scope.
 *
 * These are pure-JVM assertions about the keep-alive predicate. The real
 * `shouldRunService()` is private to the tracker and touches a Context, so the
 * predicate is restated here; `the restated predicate matches the source`
 * guards the copy against drift.
 */
class OverlayCompletionKeepAliveTest {

    /** Mirrors SessionActivityTracker.shouldRunService(). */
    private fun shouldRunService(
        activeSessions: Int,
        presentSessions: Int,
        overlayCompletionPending: Boolean,
    ): Boolean = activeSessions > 0 || presentSessions > 0 || overlayCompletionPending

    @Test
    fun `the reported case - turn ends with the Activity already gone`() {
        // Swiped from recents (or OEM-destroyed while backgrounded), so
        // presence was released. Then the turn finishes: activeSessions empties.
        // Before the fix this was (0, 0) -> false -> stopService() -> onDestroy()
        // -> controller.hide(), and the user never saw the completion capsule.
        assertFalse(
            "precondition: without the pending flag the service stops here",
            shouldRunService(activeSessions = 0, presentSessions = 0, overlayCompletionPending = false),
        )
        assertTrue(
            "a pending completion capsule must keep the service alive",
            shouldRunService(activeSessions = 0, presentSessions = 0, overlayCompletionPending = true),
        )
    }

    @Test
    fun `the home-button case already worked and must keep working`() {
        // Home leaves the Activity STOPPED, not destroyed, so presence is still
        // held and the service survived on its own. The new flag must not be
        // required for this path (it is a widening, not a replacement).
        assertTrue(shouldRunService(activeSessions = 0, presentSessions = 1, overlayCompletionPending = false))
    }

    @Test
    fun `clearing the flag releases the last keep-alive reason`() {
        // Tap / X / foreground / linger-timeout all clear it. With nothing else
        // holding the service, it must become stoppable again — otherwise the
        // fix trades a vanishing capsule for a pinned foreground service.
        assertTrue(shouldRunService(0, 0, true))
        assertFalse(shouldRunService(0, 0, false))
    }

    @Test
    fun `a live stream still dominates`() {
        // The flag must never be the reason a BUSY service is judged stoppable.
        assertTrue(shouldRunService(activeSessions = 1, presentSessions = 0, overlayCompletionPending = false))
        assertTrue(shouldRunService(activeSessions = 1, presentSessions = 0, overlayCompletionPending = true))
    }

    @Test
    fun `the restated predicate matches the source`() {
        val src = java.io.File(
            "src/main/java/com/openminis/app/service/SessionActivityTracker.kt",
        ).readText()
        val body = src.substringAfter("private fun shouldRunService(): Boolean =")
            .substringBefore("\n\n")
        for (term in listOf("_activeSessions", "_presentSessions", "_overlayCompletionPending")) {
            assertTrue(
                "shouldRunService() no longer reads $term — update this test's mirror",
                body.contains(term),
            )
        }
    }
}
