package com.openminis.app.agent.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-subagent-control-capsule] / [T-android-subagent-control-not-lost]
 *
 * A `subagent_task` call carrying an action other than `delegate` is the model
 * operating on sub agents it already started. It gets the ordinary tool capsule
 * rather than a delegation card — and, critically, must not be judged by the
 * delegation queue's rules.
 *
 * The bug this pins: a steer's result is `{"status":"queued", ...}`, and the
 * card asked "is this tool_use id still in the delegation queue?" to decide
 * between "Queued" and "Never started". A steer is never in that queue — it
 * waits in the CHILD's pending list — so the answer was false from the very
 * first read, and every steer was badged yellow "Never started" while its own
 * result text said "Queued. The sub agent reads it at its next turn." The badge
 * and the body asserted opposite things about the same call.
 */
class SubAgentControlCapsuleTest {

    private fun args(action: String) = """{"action":"$action","tool_title":"t"}"""

    @Test
    fun `a delegation is not a control call`() {
        assertFalse(HelperRunner.isControlOnly(args("delegate"), null))
        // Absent action defaults to delegate — the schema says so.
        assertFalse(HelperRunner.isControlOnly("""{"tool_title":"t"}""", null))
    }

    @Test
    fun `every non-delegate action is a control call`() {
        for (a in listOf("status", "steer", "cancel", "resume")) {
            assertTrue("$a must classify as control", HelperRunner.isControlOnly(args(a), null))
        }
    }

    @Test
    fun `each action is classified as itself`() {
        assertEquals(HelperRunner.SubAgentCall.DELEGATE, HelperRunner.classifyCall(args("delegate"), null))
        assertEquals(HelperRunner.SubAgentCall.STATUS, HelperRunner.classifyCall(args("status"), null))
        assertEquals(HelperRunner.SubAgentCall.STEER, HelperRunner.classifyCall(args("steer"), null))
        assertEquals(HelperRunner.SubAgentCall.CANCEL, HelperRunner.classifyCall(args("cancel"), null))
        assertEquals(HelperRunner.SubAgentCall.RESUME, HelperRunner.classifyCall(args("resume"), null))
    }

    /**
     * The result-shape fallback matters because a block restored from the
     * database may have lost its arguments while keeping its result. A steer's
     * result is the shape below — and this is exactly the payload that used to
     * be badged "Never started".
     */
    @Test
    fun `a steer is recognised from its result when the arguments are gone`() {
        val steerResult = """{"ok":true,"status":"queued","job_id":"abc12345",""" +
            """"detail":"Queued. The sub agent reads it at its next turn."}"""
        assertEquals(HelperRunner.SubAgentCall.STEER, HelperRunner.classifyCall(null, steerResult))
        assertTrue(
            "a steer must be control-only, or the queue-loss check will badge it Never started",
            HelperRunner.isControlOnly(null, steerResult),
        )
    }

    /**
     * The other side of the same coin: a real DELEGATION whose result says
     * queued IS waiting on the delegation queue, so it must stay subject to
     * the loss check. Losing that distinction would replace one wrong badge
     * with another.
     */
    @Test
    fun `a queued delegation remains a delegation`() {
        val delegateQueued = """{"ok":true,"status":"queued","queued_behind":2,""" +
            """"detail":"All 3 slots are busy. This task is QUEUED and will start automatically."}"""
        assertFalse(HelperRunner.isControlOnly(args("delegate"), delegateQueued))
    }

    @Test
    fun `garbage arguments do not make a block a control call`() {
        assertFalse(HelperRunner.isControlOnly("not json", null))
        assertFalse(HelperRunner.isControlOnly(null, null))
    }
}
