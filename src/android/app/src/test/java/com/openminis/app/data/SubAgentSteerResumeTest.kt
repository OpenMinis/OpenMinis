package com.openminis.app.data

import com.openminis.app.agent.jobs.AgentJobOrigin
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.AgentJobState
import com.openminis.app.agent.jobs.AgentJobTarget
import com.openminis.app.agent.jobs.AgentJobTrigger
import com.openminis.app.agent.jobs.HelperRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-sub-agents-steer] / [T-sub-agents-resume] Course-correcting a running sub
 * agent, and restarting one the app lost.
 */
class SubAgentSteerResumeTest {

    private fun newJob(parent: String = "p1") = AgentJobRegistry.register(
        title = "t",
        origin = AgentJobOrigin.TOOL,
        trigger = AgentJobTrigger.Immediate,
        target = AgentJobTarget.ChildOfCurrent(parent, "tool-1"),
        prompt = "work",
        agentName = "Researcher",
    )

    // ── Steer ───────────────────────────────────────────────────────────────

    @Test
    fun `a steer reaches the child through its hook`() {
        val job = newJob()
        val delivered = mutableListOf<String>()
        AgentJobRegistry.registerSteerHook(job.id) { delivered.add(it); true }

        assertTrue(AgentJobRegistry.steer(job.id, "focus on pricing"))
        assertEquals(listOf("focus on pricing"), delivered)
        AgentJobRegistry.finish(job.id, AgentJobState.DONE, "done")
    }

    @Test
    fun `steering a job with no hook fails rather than pretending`() {
        // The parent must learn its correction did not land; silently
        // succeeding would let it assume the sub agent changed course.
        val job = newJob()
        assertFalse(AgentJobRegistry.steer(job.id, "too late"))
        AgentJobRegistry.finish(job.id, AgentJobState.DONE, null)
    }

    @Test
    fun `steering an unknown job fails`() {
        assertFalse(AgentJobRegistry.steer("no-such-job", "hello"))
    }

    @Test
    fun `a child that refuses the steer reports failure`() {
        val job = newJob()
        AgentJobRegistry.registerSteerHook(job.id) { false }
        assertFalse(AgentJobRegistry.steer(job.id, "nope"))
        AgentJobRegistry.finish(job.id, AgentJobState.DONE, null)
    }

    @Test
    fun `steers the child never read are recorded when the job closes`() {
        // The parent believes the correction landed, so a steer that arrived
        // after the last turn has to be reported rather than dropped.
        val job = newJob()
        AgentJobRegistry.registerSteerHook(job.id) { true }
        AgentJobRegistry.registerMissedSteerDrain(job.id) { listOf("unread one", "unread two") }

        AgentJobRegistry.finish(job.id, AgentJobState.DONE, "result")

        assertEquals(listOf("unread one", "unread two"), AgentJobRegistry.missedSteersFor(job.id))
    }

    @Test
    fun `a job that read everything reports no missed steers`() {
        val job = newJob()
        AgentJobRegistry.registerMissedSteerDrain(job.id) { emptyList() }
        AgentJobRegistry.finish(job.id, AgentJobState.DONE, "result")
        assertTrue(AgentJobRegistry.missedSteersFor(job.id).isEmpty())
    }

    @Test
    fun `the steer hook is dropped once the job closes`() {
        val job = newJob()
        AgentJobRegistry.registerSteerHook(job.id) { true }
        AgentJobRegistry.finish(job.id, AgentJobState.DONE, "r")
        // A finished job cannot be steered — the hook is gone with it.
        assertFalse(AgentJobRegistry.steer(job.id, "after the fact"))
    }

    // ── Steer at loop end [T-subagent-steer-continues-loop] ────────────────

    @Test
    fun `a steer pending when the final answer ends buys one more turn`() {
        // The reported case: the correction arrived while the child was
        // writing its answer, the turn had no tool calls, and the loop broke —
        // leaving the steer to be reported as "never seen".
        assertTrue(HelperRunner.shouldContinueForSteer(isHelper = true, isEmptyTurn = false, turn = 3, turnCap = 20, pendingSteers = 1))
    }

    @Test
    fun `no pending steer means the child finishes as before`() {
        assertFalse(HelperRunner.shouldContinueForSteer(isHelper = true, isEmptyTurn = false, turn = 3, turnCap = 20, pendingSteers = 0))
    }

    @Test
    fun `a main conversation never continues for a steer`() {
        assertFalse(HelperRunner.shouldContinueForSteer(isHelper = false, isEmptyTurn = false, turn = 3, turnCap = 20, pendingSteers = 1))
    }

    @Test
    fun `the last permitted round does not continue into the turn-limit error`() {
        // turn is 0-based: turn 19 of a 20-turn cap is the last one. Another
        // iteration does not exist; the steer is reported as missed instead.
        assertFalse(HelperRunner.shouldContinueForSteer(isHelper = true, isEmptyTurn = false, turn = 19, turnCap = 20, pendingSteers = 1))
        assertTrue(HelperRunner.shouldContinueForSteer(isHelper = true, isEmptyTurn = false, turn = 18, turnCap = 20, pendingSteers = 1))
    }

    @Test
    fun `an empty turn that already gave up does not continue`() {
        assertFalse(HelperRunner.shouldContinueForSteer(isHelper = true, isEmptyTurn = true, turn = 3, turnCap = 20, pendingSteers = 1))
    }

    @Test
    fun `the loop consults the steer queue before its no-tool-call exit`() {
        // Source guard: the decision above only matters if runAgentLoop asks
        // it on the path that ends a turn with no tool calls, BEFORE breaking.
        val vm = com.openminis.app.ProductionSources.read("ui/chat/ChatViewModel.kt")
        val loop = vm.substring(vm.indexOf("for (turn in 0 until turnCap)"))
        val check = loop.indexOf("HelperRunner.shouldContinueForSteer(")
        assertTrue("runAgentLoop must call shouldContinueForSteer", check > 0)
        val exit = loop.indexOf("loopExitedNormally = true\n                break")
        assertTrue("the no-tool-call exit must still exist", exit > 0)
        assertTrue("the steer check must come before the normal exit", check < exit)
        assertTrue(
            "it must pass the real queue size",
            loop.substring(check, check + 400).contains("pendingSteers = pendingSteerMessages.size"),
        )
    }

    // ── Resume ──────────────────────────────────────────────────────────────

    @Test
    fun `the resume notice says what survived the restart and what did not`() {
        // The child's transcript still shows tool results whose side effects
        // are gone; without this it would keep referring to a closed tab.
        // Wording is iOS's verbatim, so the child reads the same brief on both
        // platforms.
        val n = HelperRunner.resumeNotice()
        assertTrue(n.contains("interrupted and has been resumed"))
        assertTrue("files survive", n.contains("Files in the workspace are still there"))
        assertTrue("tabs do not", n.contains("browser tabs are closed"))
        assertTrue("shell processes do not", n.contains("shell processes have ended"))
        assertTrue("tool results remain usable", n.contains("tool results above are still valid"))
    }

    @Test
    fun `a resumed job carries its agent name for the callback`() {
        val job = newJob()
        assertEquals("Researcher", job.agentName)
        AgentJobRegistry.finish(job.id, AgentJobState.DONE, null)
    }
}
