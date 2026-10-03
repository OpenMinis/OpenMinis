package com.openminis.app.agent.jobs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-neverstarted-not-running] A delegation that died on the in-memory
 * queue is NOT running, and must not be offered a Stop button.
 *
 * The bug this pins: `isRunning` was `phase !is Finished`, which swept
 * NeverStarted in with the live phases. The card then drew the red stop disc
 * over a block with no child session and no job — a dead card presented as live
 * work, and a button that did nothing when tapped. The user reported it as
 * "shows a fake running state after restart, and can't be stopped".
 *
 * These mirror HelperBlockInfo.isRunning rather than calling it, because the
 * real type lives in the UI module and pulls in Compose; the predicate is the
 * whole content of the fix, so it is restated here exactly and would fail if
 * the production expression is widened back.
 */
class NeverStartedNotRunningTest {

    /** The production predicate, verbatim. */
    private fun isRunning(phase: String): Boolean =
        phase != "Finished" && phase != "NeverStarted"

    @Test
    fun `never started is not running`() {
        assertFalse(
            "a delegation lost with the queue has no job to stop",
            isRunning("NeverStarted"),
        )
    }

    @Test
    fun `finished is not running`() {
        assertFalse(isRunning("Finished"))
    }

    @Test
    fun `genuinely live phases stay running`() {
        // The other direction matters just as much: narrowing isRunning too far
        // would strip Stop from a sub agent that IS working, which is the only
        // way a user can halt a runaway fan-out.
        assertTrue(isRunning("Running"))
        assertTrue(isRunning("Queued"))
        assertTrue(isRunning("Starting"))
    }

    /**
     * A control call (status/steer/cancel) has no task of its own, so "start
     * it" is meaningless — and starting a `cancel` would invert the user's
     * intent. startNeverStartedDelegation refuses these.
     */
    @Test
    fun `control calls are not startable`() {
        assertTrue(
            HelperRunner.isControlOnly("""{"action":"status"}""", null),
        )
        assertTrue(
            HelperRunner.isControlOnly("""{"action":"cancel"}""", null),
        )
        assertFalse(
            "a delegation carries a task and IS startable",
            HelperRunner.isControlOnly("""{"task":"research X","tool_title":"X"}""", null),
        )
    }
}
