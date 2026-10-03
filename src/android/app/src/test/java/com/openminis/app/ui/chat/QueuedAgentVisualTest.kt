package com.openminis.app.ui.chat

import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.HelperRunner
import org.junit.After
import org.junit.Before
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-queued-visual] A queued sub agent must be distinguishable from one
 * that is about to run.
 *
 * Reported from a side-by-side screenshot of a five-way fan-out: three agents
 * were running and two were waiting for a slot, and the two waiting ones wore
 * the SAME generic Groups avatar and the same muted grey second line as the
 * others. The only thing separating "held" from "starting" was the word itself.
 * iOS draws `pause.fill` in the avatar (HelperBlockView.swift:589) and paints
 * the status in the agent accent (:313).
 *
 * The composables need a live composition, so what is pinned here is the phase
 * classification the avatar and the status line switch on — a queued payload
 * must parse to [HelperPhase.Queued] and nothing else, since every visual
 * decision hangs off that.
 */
class QueuedAgentVisualTest {

    /**
     * A `status: queued` payload only reads as Queued while the delegation is
     * ACTUALLY still in the registry's queue — otherwise it is a task the
     * process lost and parses as NeverStarted
     * ([T-android-subagent-control-not-lost] documents why those two must stay
     * apart). The first run of this test asserted the wrong thing and caught
     * that: enqueue the block so the phase under test is reachable at all.
     */
    @Before
    fun enqueue() {
        AgentJobRegistry.dropQueuedDelegations("parent", "test-setup")
        AgentJobRegistry.enqueueDelegation(
            AgentJobRegistry.QueuedDelegation(
                parentSessionId = "parent", argsJson = "{}", toolUseId = "b1",
            ),
        )
    }

    @After
    fun cleanup() {
        AgentJobRegistry.dropQueuedDelegations("parent", "test-teardown")
    }

    private fun block(json: String) = AssistantBlock(
        id = "b1", kind = "tool_use", content = json,
        toolName = HelperRunner.TOOL_NAME, toolTitle = "调研 2025 苹果发布与销售",
    )

    /**
     * The queued payload the delegate path writes when all slots are busy.
     * `queued_behind` is what the card can use to say how many are ahead.
     */
    @Test
    fun `a queued delegation parses as the Queued phase`() {
        val info = parseHelperBlock(
            block("""{"ok":true,"status":"queued","queued_behind":2,"detail":"All 3 slots are busy."}"""),
        )
        val phase = info.phase
        assertTrue("expected Queued, got $phase", phase is HelperPhase.Queued)
        assertEquals(2, (phase as HelperPhase.Queued).behind)
    }

    /**
     * The distinction the screenshot showed collapsing: a delegation that has
     * STARTED is a different phase, so it keeps the spinner and its own colour.
     * If these two ever parsed alike, the avatar fix would be undone.
     */
    @Test
    fun `a starting delegation is not the Queued phase`() {
        val info = parseHelperBlock(
            block("""{"ok":true,"status":"starting","child_session_id":"c1"}"""),
        )
        assertTrue("starting must not read as queued", info.phase !is HelperPhase.Queued)
    }

    @Test
    fun `a running delegation is not the Queued phase`() {
        val info = parseHelperBlock(
            block("""{"ok":true,"status":"running","child_session_id":"c1","tool":"shell_execute"}"""),
        )
        assertTrue("running must not read as queued", info.phase !is HelperPhase.Queued)
    }

    /**
     * A finished run must never reach the queued branch either — the avatar
     * switches on the finished status FIRST, and a pause glyph on a completed
     * agent would be worse than the generic one it replaced.
     */
    @Test
    fun `a finished delegation is not the Queued phase`() {
        val info = parseHelperBlock(
            block("""{"ok":true,"status":"completed","result":"done","child_session_id":"c1"}"""),
        )
        assertTrue("completed must not read as queued", info.phase is HelperPhase.Finished)
    }

    /**
     * `queued_behind` is optional — an older payload omits it, and the card must
     * still render as queued rather than falling through to a generic phase.
     */
    @Test
    fun `a queued payload without a position still parses as queued`() {
        val info = parseHelperBlock(block("""{"ok":true,"status":"queued"}"""))
        assertTrue(info.phase is HelperPhase.Queued)
        assertEquals(0, (info.phase as HelperPhase.Queued).behind)
    }
}
