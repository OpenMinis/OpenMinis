package com.openminis.app.agent.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-showstop-subagent] The composer's Stop button must appear whenever
 * the CONVERSATION is working, not only while this VM is streaming.
 *
 * The button's own condition lives in a composable and cannot be unit-tested
 * here, so this pins the two things it rests on: the rule itself as a pure
 * predicate, and the registry behaviour that makes pressing it actually stop
 * something.
 */
class ShowStopSubAgentTest {

    @Before fun reset() = AgentJobRegistry.resetForTest()

    private fun child(parent: String = "P") = AgentJobRegistry.register(
        title = "t", origin = AgentJobOrigin.TOOL, trigger = AgentJobTrigger.Immediate,
        target = AgentJobTarget.ChildOfCurrent(parent, "tu"), prompt = "do", then = AgentJobThen.None,
    )

    /** The rule as ChatScreen applies it: streaming OR this session has agent work. */
    private fun showStop(isStreaming: Boolean, sessionId: String, hasContent: Boolean): Boolean {
        // hasAgentWork(), not parentsWithAgentWork.value: the flow is fed by a
        // collector on a background dispatcher, so its .value trails its inputs
        // in a unit test. That is exactly why A6 exposed a synchronous
        // accessor alongside it. ChatScreen uses the FLOW, because there the
        // point is to recompose when the set changes; the two answer the same
        // question about the same registry state.
        val busy = isStreaming || AgentJobRegistry.hasAgentWork(sessionId)
        return busy && !hasContent
    }

    @Test
    fun `stop shows while only a sub agent is running`() {
        val j = child("P")
        AgentJobRegistry.markRunning(j.id, "S-child")
        // The exact reported state: the parent has stopped streaming and is
        // waiting on the callback.
        assertTrue(
            "the button must survive the parent going idle",
            showStop(isStreaming = false, sessionId = "P", hasContent = false),
        )
        AgentJobRegistry.finish(j.id, AgentJobState.DONE, "ok")
        assertFalse(
            "and go away once the work is done",
            showStop(isStreaming = false, sessionId = "P", hasContent = false),
        )
    }

    /** Unchanged behaviour: typed text still means Send/Enqueue, never Stop. */
    @Test
    fun `typed content still wins over stop`() {
        val j = child("P")
        AgentJobRegistry.markRunning(j.id, "S-child")
        assertFalse(
            "with text in the box the button is Send/Enqueue, not Stop",
            showStop(isStreaming = false, sessionId = "P", hasContent = true),
        )
    }

    /** Another conversation's agents must not light up this composer. */
    @Test
    fun `another session's agent work does not show stop here`() {
        val j = child("Q")
        AgentJobRegistry.markRunning(j.id, "S-other")
        assertFalse(showStop(isStreaming = false, sessionId = "P", hasContent = false))
        assertTrue(showStop(isStreaming = false, sessionId = "Q", hasContent = false))
    }

    /**
     * The point of the whole task: a button that shows but does nothing is
     * worse than no button. cancelStream calls cancelAll(activeSessionId),
     * which filters on the registry's own active jobs and never consults
     * streaming state — so it stops the fan-out even with the parent idle.
     */
    @Test
    fun `cancelling the session stops sub agents with the parent idle`() {
        val a = child("P"); val b = child("P")
        AgentJobRegistry.markRunning(a.id, "SA") {}
        AgentJobRegistry.markRunning(b.id, "SB") {}
        assertEquals(2, AgentJobRegistry.activeChildren("P").size)

        // Exactly what cancelStream does for the agent half.
        AgentJobRegistry.muteDelegationResults("P")
        AgentJobRegistry.dropQueuedDelegations("P", "user-stopped")
        AgentJobRegistry.cancelAll("P", "user-stopped")

        assertTrue(
            "pressing Stop must really stop them",
            AgentJobRegistry.activeChildren("P").isEmpty(),
        )
        assertFalse(
            "and the button must then disappear",
            showStop(isStreaming = false, sessionId = "P", hasContent = false),
        )
    }
}
