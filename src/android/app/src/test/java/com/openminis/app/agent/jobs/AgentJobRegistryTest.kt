package com.openminis.app.agent.jobs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [T-p1-delegate-task] Lifecycle + limit semantics of the in-process registry. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentJobRegistryTest {

    @Before fun reset() = AgentJobRegistry.resetForTest()

    private fun child(parent: String = "P") = AgentJobRegistry.register(
        title = "t", origin = AgentJobOrigin.TOOL, trigger = AgentJobTrigger.Immediate,
        target = AgentJobTarget.ChildOfCurrent(parent, "tu"), prompt = "do", then = AgentJobThen.None,
    )

    @Test
    fun `child job limit counts pending and running, not finished`() {
        val a = child(); val b = child(); val c = child()
        assertFalse("3 live children must block a 4th", AgentJobRegistry.canStartChildJob)
        AgentJobRegistry.finish(a.id, AgentJobState.DONE, "ok")
        assertTrue("a finished child frees its slot", AgentJobRegistry.canStartChildJob)
        assertEquals(2, AgentJobRegistry.runningChildJobCount)
        assertEquals(setOf(b.id, c.id), AgentJobRegistry.activeChildren("P").map { it.id }.toSet())
    }

    @Test
    fun `cancel runs the hook once and finishes the job`() {
        var hooks = 0
        val j = child()
        AgentJobRegistry.markRunning(j.id, "S1") { hooks++ }
        assertEquals(j.id, AgentJobRegistry.jobForSession("S1")?.id)
        AgentJobRegistry.cancel(j.id, "test")
        AgentJobRegistry.cancel(j.id, "test-again")
        assertEquals("hook must fire exactly once", 1, hooks)
        assertEquals(AgentJobState.CANCELLED, AgentJobRegistry.job(j.id)?.state)
        assertEquals("the session still resolves to the (finished) job so the sheet can show history",
            AgentJobState.CANCELLED, AgentJobRegistry.jobForSession("S1")?.state)
    }

    @Test
    fun `finish is idempotent — first terminal state wins`() {
        val j = child()
        AgentJobRegistry.finish(j.id, AgentJobState.DONE, "r1")
        AgentJobRegistry.finish(j.id, AgentJobState.FAILED, "r2")
        val got = AgentJobRegistry.job(j.id)!!
        assertEquals(AgentJobState.DONE, got.state)
        assertEquals("r1", got.resultText)
    }

    @Test
    fun `cancelAll only touches the given parent's children`() {
        val mine = child("P"); val other = child("Q")
        AgentJobRegistry.cancelAll("P", "test")
        assertEquals(AgentJobState.CANCELLED, AgentJobRegistry.job(mine.id)?.state)
        assertEquals(AgentJobState.PENDING, AgentJobRegistry.job(other.id)?.state)
    }

    @Test
    fun `finish runs the completion hook once, then dispatches the parent follow-up as a callback envelope`() {
        val j = AgentJobRegistry.register(
            title = "Survey", origin = AgentJobOrigin.TOOL, trigger = AgentJobTrigger.Immediate,
            target = AgentJobTarget.ChildOfCurrent("P", "tu"), prompt = "do", then = AgentJobThen.FollowUpParent(null),
        )
        AgentJobRegistry.setTierUsed(j.id, "sub")
        AgentJobRegistry.markRunning(j.id, "S1")
        var hooks = 0
        AgentJobRegistry.setCompletionHook(j.id) { hooks++ }
        val dispatched = mutableListOf<Triple<String, String, String>>()
        AgentJobRegistry.followUpDispatcher = { p, t, id -> dispatched += Triple(p, t, id) }
        AgentJobRegistry.finish(j.id, AgentJobState.TIMEOUT, "partial")
        AgentJobRegistry.finish(j.id, AgentJobState.DONE, "again")
        assertEquals(1, hooks)
        assertEquals(1, dispatched.size)
        val (parent, text, jobId) = dispatched[0]
        assertEquals("P", parent); assertEquals(j.id, jobId)
        val cb = AgentCallback.parse(text)!!
        assertEquals(AgentCallback.Kind.FINISHED, cb.kind); assertEquals("timeout", cb.status); assertEquals("sub", cb.tier)
        assertEquals("S1", cb.childSessionId); assertEquals("partial", cb.body)
    }

    @Test
    fun `an empty result is replaced by the status note so the parent never gets nothing`() {
        val j = child()
        AgentJobRegistry.setThen(j.id, AgentJobThen.FollowUpParent(null))
        var text = ""
        AgentJobRegistry.followUpDispatcher = { _, t, _ -> text = t }
        AgentJobRegistry.finish(j.id, AgentJobState.CANCELLED, "")
        assertTrue(AgentCallback.parse(text)!!.body.startsWith("(the agent ended with status cancelled"))
    }

    @Test
    fun `a delete cascade that drops then before cancel dispatches nothing, a plain cancel reports cancelled`() {
        val dispatched = mutableListOf<String>()
        AgentJobRegistry.followUpDispatcher = { _, t, _ -> dispatched += t }
        val deleted = child("P1"); AgentJobRegistry.setThen(deleted.id, AgentJobThen.FollowUpParent(null)); AgentJobRegistry.markRunning(deleted.id, "S1")
        AgentJobRegistry.setThen(deleted.id, AgentJobThen.None)
        AgentJobRegistry.cancelAll("P1", "parent-deleted")
        assertEquals(0, dispatched.size)
        val stopped = child("P2"); AgentJobRegistry.setThen(stopped.id, AgentJobThen.FollowUpParent(null)); AgentJobRegistry.markRunning(stopped.id, "S2")
        AgentJobRegistry.cancel(stopped.id, "user-stop")
        assertEquals(1, dispatched.size)
        assertEquals("cancelled", AgentCallback.parse(dispatched[0])!!.status)
    }

    @Test
    fun `a stop hook that wakes a watcher calling finish(DONE) cannot override CANCELLED`() {
        val j = child()
        AgentJobRegistry.markRunning(j.id, "S1") {
            // What the child's stream flip does in production: the watcher
            // observes idle and closes the job as DONE.
            AgentJobRegistry.finish(j.id, AgentJobState.DONE, "late")
        }
        AgentJobRegistry.cancel(j.id, "user-stop")
        assertEquals(AgentJobState.CANCELLED, AgentJobRegistry.job(j.id)?.state)
    }

    @Test
    fun `wait-mode jobs (then=None) never dispatch`() {
        val j = child()
        var calls = 0
        AgentJobRegistry.followUpDispatcher = { _, _, _ -> calls++ }
        AgentJobRegistry.finish(j.id, AgentJobState.DONE, "r")
        assertEquals(0, calls)
    }

    @Test
    fun `completion is emitted once per finish`() = runTest {
        val seen = mutableListOf<String>()
        val j = child()
        val collector = launch(Dispatchers.Unconfined) {
            AgentJobRegistry.completions.collect { seen += it.jobId }
        }
        AgentJobRegistry.finish(j.id, AgentJobState.DONE, null)
        AgentJobRegistry.finish(j.id, AgentJobState.DONE, null)
        collector.cancel()
        assertEquals(listOf(j.id), seen)
    }

    // ── [T-android-stop-sibling-subagent] Stopping the batch ────────────

    /**
     * Stopping one card stops the whole fan-out. Before this, cancel() took a
     * single job id, so the siblings the user could not see kept running and
     * kept spending tokens after the card said stopped.
     */
    @Test
    fun `stopping one sub agent cancels its same-batch siblings`() {
        val a = child(); val b = child(); val c = child()
        AgentJobRegistry.markRunning(a.id, "SA") {}
        AgentJobRegistry.markRunning(b.id, "SB") {}
        AgentJobRegistry.markRunning(c.id, "SC") {}

        AgentJobRegistry.cancelSiblings("SA", "user-stop")

        assertTrue("every sibling of the batch must stop", AgentJobRegistry.activeChildren("P").isEmpty())
        for (id in listOf(a.id, b.id, c.id)) {
            assertEquals(AgentJobState.CANCELLED, AgentJobRegistry.jobs.value[id]?.state)
        }
    }

    /** A different parent's run is a different batch and must survive. */
    @Test
    fun `stopping a batch leaves another conversation's agents alone`() {
        val mine = child("P"); val other = child("Q")
        AgentJobRegistry.markRunning(mine.id, "SM") {}
        AgentJobRegistry.markRunning(other.id, "SO") {}

        AgentJobRegistry.cancelSiblings("SM", "user-stop")

        assertEquals(AgentJobState.CANCELLED, AgentJobRegistry.jobs.value[mine.id]?.state)
        assertEquals(
            "another parent's batch is untouched",
            1, AgentJobRegistry.activeChildren("Q").size,
        )
    }

    /**
     * The ordering guard. finish() drains the queue on every terminal state,
     * so cancelling a running job frees a slot — without dropping the queue
     * FIRST, stopping the batch would start one of the very delegations being
     * stopped.
     */
    @Test
    fun `stopping a batch does not start a queued delegation`() {
        val a = child(); val b = child(); val c = child()
        AgentJobRegistry.markRunning(a.id, "SA") {}
        AgentJobRegistry.markRunning(b.id, "SB") {}
        AgentJobRegistry.markRunning(c.id, "SC") {}
        var started = 0
        AgentJobRegistry.registerQueuedStarter("P") { started++; true }
        AgentJobRegistry.enqueueDelegation(
            AgentJobRegistry.QueuedDelegation(parentSessionId = "P", argsJson = "{}", toolUseId = "tu-q"),
        )

        AgentJobRegistry.cancelSiblings("SA", "user-stop")

        assertEquals("a queued delegation must not start because a slot freed", 0, started)
    }

    /**
     * A sibling that had ALREADY finished still owns a pending callback.
     * Delivering it after the stop restarts the parent's turn, which reads as
     * the Stop button not working. The job still finishes and still records
     * its result; only the parent-waking hop is suppressed.
     */
    @Test
    fun `a finished sibling cannot re-wake a stopped parent`() {
        var dispatched = 0
        AgentJobRegistry.followUpDispatcher = { _, _, _ -> dispatched++ }
        val late = AgentJobRegistry.register(
            title = "t", origin = AgentJobOrigin.TOOL, trigger = AgentJobTrigger.Immediate,
            target = AgentJobTarget.ChildOfCurrent("P", "tu"), prompt = "do",
            then = AgentJobThen.FollowUpParent(null),
        )
        AgentJobRegistry.markRunning(late.id, "SL") {}

        AgentJobRegistry.muteDelegationResults("P")
        AgentJobRegistry.finish(late.id, AgentJobState.DONE, "done after the stop")

        assertEquals("a stopped parent must not be woken", 0, dispatched)
        assertEquals(
            "the job still finishes and keeps its result",
            AgentJobState.DONE, AgentJobRegistry.jobs.value[late.id]?.state,
        )
    }

    /** New work in the session lifts the stop. */
    @Test
    fun `clearing the mute lets later delegations drive the parent again`() {
        var dispatched = 0
        AgentJobRegistry.followUpDispatcher = { _, _, _ -> dispatched++ }
        AgentJobRegistry.muteDelegationResults("P")
        AgentJobRegistry.clearDelegationMute("P")

        val j = AgentJobRegistry.register(
            title = "t", origin = AgentJobOrigin.TOOL, trigger = AgentJobTrigger.Immediate,
            target = AgentJobTarget.ChildOfCurrent("P", "tu"), prompt = "do",
            then = AgentJobThen.FollowUpParent(null),
        )
        AgentJobRegistry.markRunning(j.id, "SN") {}
        AgentJobRegistry.finish(j.id, AgentJobState.DONE, "ok")

        assertEquals(1, dispatched)
    }


    // ── [T-android-sidebar-subagent-running] Sidebar attribution ────────

    /**
     * The sidebar's question is "does THIS conversation have work in flight",
     * and a sub agent's activity is recorded under its own hidden child
     * session id — an id no sidebar row carries. The query has to attribute it
     * back to the parent, or a session that delegated everything out looks
     * idle while it is still working.
     */
    @Test
    fun `a running sub agent marks its parent as having work`() {
        val j = child("P")
        AgentJobRegistry.markRunning(j.id, "S-child")
        assertTrue("the PARENT is what the sidebar shows", AgentJobRegistry.hasAgentWork("P"))
        assertFalse("the hidden child is not a sidebar row", AgentJobRegistry.hasAgentWork("S-child"))

        AgentJobRegistry.finish(j.id, AgentJobState.DONE, "ok")
        assertFalse("and it clears when the work ends", AgentJobRegistry.hasAgentWork("P"))
    }

    /** A delegation waiting for a slot is still this conversation having work outstanding. */
    @Test
    fun `a queued delegation also marks its parent`() {
        AgentJobRegistry.enqueueDelegation(
            AgentJobRegistry.QueuedDelegation(parentSessionId = "P", argsJson = "{}", toolUseId = "tu-q"),
        )
        assertTrue(AgentJobRegistry.hasAgentWork("P"))
    }

}
