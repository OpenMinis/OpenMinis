package com.openminis.app.agent.jobs

import com.openminis.app.BuildConfig
import com.openminis.app.ProductionSources
import com.openminis.app.scheduled.ScheduledAgentRunner
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * [T-new-limits] 6 session slots, and sub agents capped at 3 PER PARENT
 * conversation (not a global 3), outside those 6 slots.
 *
 * What these pin: parents count independently, a full parent at the head of
 * the delegation queue cannot starve another parent's entries behind it, the
 * queue bound is per parent, a scheduled child-of-current fire waits for its
 * parent's slot instead of overrunning it, and session slots pair — including
 * a turn cancelled while it was still waiting for one.
 */
class NewLimitsTest {

    private val p = "P-" + UUID.randomUUID()
    private val q = "Q-" + UUID.randomUUID()

    @Before
    fun setUp() {
        AgentJobRegistry.resetForTest()
        AgentJobRegistry.debugSetMaxConcurrentChildJobs(null)
    }

    @After
    fun tearDown() {
        listOf(p, q).forEach {
            AgentJobRegistry.dropQueuedDelegations(it, "test")
            AgentJobRegistry.unregisterQueuedStarter(it)
        }
        AgentJobRegistry.debugSetMaxConcurrentChildJobs(null)
        AgentJobRegistry.resetForTest()
        SessionConcurrencyManager.debugSetMaxConcurrent(null)
    }

    private fun child(parent: String) = AgentJobRegistry.register(
        title = "t", origin = AgentJobOrigin.TOOL, trigger = AgentJobTrigger.Immediate,
        target = AgentJobTarget.ChildOfCurrent(parent, "tu-" + UUID.randomUUID()),
        prompt = "do", then = AgentJobThen.None,
    )

    private fun queued(parent: String, tool: String) =
        AgentJobRegistry.QueuedDelegation(parentSessionId = parent, argsJson = "{}", toolUseId = tool)

    // ── Constants ───────────────────────────────────────────────────────────

    @Test
    fun `the shipped caps are 6 sessions, 3 sub agents per parent, 10 queued per parent`() {
        assertEquals(6, SessionConcurrencyManager.MAX_CONCURRENT)
        assertEquals(3, AgentJobRegistry.MAX_CONCURRENT_CHILD_JOBS_PER_PARENT)
        assertEquals(10, AgentJobRegistry.MAX_QUEUED_DELEGATIONS_PER_PARENT)
    }

    // ── Per-parent counting ────────────────────────────────────────────────

    @Test
    fun `a parent's children count only against that parent`() {
        repeat(3) { child(p) }
        assertFalse("P is full at 3", AgentJobRegistry.canStartChildJob(p))
        assertEquals(3, AgentJobRegistry.runningChildJobCount(p))
        assertTrue("Q is untouched by P's children", AgentJobRegistry.canStartChildJob(q))
        assertEquals(0, AgentJobRegistry.runningChildJobCount(q))
        assertEquals(3, AgentJobRegistry.totalRunningChildJobCount)
        assertEquals(mapOf(p to 3), AgentJobRegistry.runningChildJobCountsByParent())
    }

    @Test
    fun `two full parents run six children and free slots independently`() {
        val pJobs = (1..3).map { child(p) }
        repeat(3) { child(q) }
        assertEquals("6 children live at once — the old global cap stopped at 3",
            6, AgentJobRegistry.totalRunningChildJobCount)
        assertFalse(AgentJobRegistry.canStartChildJob(p))
        assertFalse(AgentJobRegistry.canStartChildJob(q))
        AgentJobRegistry.finish(pJobs[0].id, AgentJobState.DONE, "ok")
        assertTrue("P's finished child freed P's slot", AgentJobRegistry.canStartChildJob(p))
        assertFalse("and not Q's", AgentJobRegistry.canStartChildJob(q))
    }

    @Test
    fun `the debug override applies per parent`() {
        if (BuildConfig.DEV_TOOLS) {
            assertEquals(1, AgentJobRegistry.debugSetMaxConcurrentChildJobs(1))
            child(p)
            assertFalse(AgentJobRegistry.canStartChildJob(p))
            assertTrue(AgentJobRegistry.canStartChildJob(q))
            assertEquals(3, AgentJobRegistry.debugSetMaxConcurrentChildJobs(null))
            assertTrue(AgentJobRegistry.canStartChildJob(p))
        } else {
            // Without developer tools the override is ignored, and admission
            // must still enforce the shipped three-child cap independently.
            assertEquals(3, AgentJobRegistry.debugSetMaxConcurrentChildJobs(1))
            repeat(3) {
                assertTrue("P retains each of its three default slots", AgentJobRegistry.canStartChildJob(p))
                child(p)
            }
            assertFalse("P is full at the shipped cap", AgentJobRegistry.canStartChildJob(p))
            assertTrue("Q still has its own slots", AgentJobRegistry.canStartChildJob(q))
            assertEquals(3, AgentJobRegistry.debugSetMaxConcurrentChildJobs(null))
            assertFalse("reset cannot open a fourth slot", AgentJobRegistry.canStartChildJob(p))
        }
    }

    // ── Queue: per-parent bound and no head-of-line blocking ───────────────

    @Test
    fun `the queue bound is per parent`() {
        repeat(AgentJobRegistry.MAX_QUEUED_DELEGATIONS_PER_PARENT) {
            assertTrue(AgentJobRegistry.enqueueDelegation(queued(p, "p$it")))
        }
        assertFalse("P's 11th is refused (NOT queued)", AgentJobRegistry.enqueueDelegation(queued(p, "p-over")))
        assertFalse(AgentJobRegistry.isQueued("p-over"))
        assertTrue("Q still has its own 10", AgentJobRegistry.enqueueDelegation(queued(q, "q0")))
        assertEquals(1, AgentJobRegistry.queuedCount(q))
    }

    @Test
    fun `a full parent at the head of the queue does not starve another parent`() {
        repeat(3) { child(p) }
        val started = mutableListOf<String>()
        AgentJobRegistry.registerQueuedStarter(p) { started += it.toolUseId; true }
        AgentJobRegistry.registerQueuedStarter(q) { started += it.toolUseId; true }
        AgentJobRegistry.enqueueDelegation(queued(p, "p1"))
        AgentJobRegistry.enqueueDelegation(queued(p, "p2"))
        AgentJobRegistry.enqueueDelegation(queued(q, "q1"))

        AgentJobRegistry.drainIfStalled()

        assertEquals("Q's entry behind P's two starts; P's stay queued", listOf("q1"), started)
        assertTrue(AgentJobRegistry.isQueued("p1"))
        assertTrue(AgentJobRegistry.isQueued("p2"))
    }

    @Test
    fun `a freed slot starts that parent's EARLIEST entry, one per drain`() {
        val pJobs = (1..3).map { child(p) }
        val started = mutableListOf<String>()
        AgentJobRegistry.registerQueuedStarter(p) { started += it.toolUseId; true }
        AgentJobRegistry.enqueueDelegation(queued(p, "p1"))
        AgentJobRegistry.enqueueDelegation(queued(p, "p2"))

        AgentJobRegistry.finish(pJobs[0].id, AgentJobState.DONE, "ok")

        // A queued re-entry takes its slot asynchronously, so one drain must not
        // start the parent's whole backlog on a single free slot.
        assertEquals(listOf("p1"), started)
        assertTrue(AgentJobRegistry.isQueued("p2"))
    }

    @Test
    fun `drainIfStalled leaves a queue alone when every queued parent is full`() {
        repeat(3) { child(p) }
        var calls = 0
        AgentJobRegistry.registerQueuedStarter(p) { calls++; true }
        AgentJobRegistry.enqueueDelegation(queued(p, "p1"))
        AgentJobRegistry.drainIfStalled()
        assertEquals(0, calls)
        assertTrue(AgentJobRegistry.isQueued("p1"))
    }

    @Test
    fun `awaitChildSlot returns when the parent's own slot frees, and times out otherwise`() = runBlocking {
        val pJobs = (1..3).map { child(p) }
        assertTrue("room already → immediate", AgentJobRegistry.awaitChildSlot(q, 10))
        assertFalse("full and nothing finishes → false", AgentJobRegistry.awaitChildSlot(p, 50))
        val waiter = async(Dispatchers.Default) { AgentJobRegistry.awaitChildSlot(p, 5_000) }
        delay(50)
        repeat(3) { child(q) }  // another parent's children do not free P
        delay(50)
        assertFalse(waiter.isCompleted)
        AgentJobRegistry.finish(pJobs[1].id, AgentJobState.DONE, "ok")
        assertTrue(withTimeout(5_000) { waiter.await() })
    }

    // ── Scheduled child-of-current path ────────────────────────────────────

    private fun task(mode: ScheduledTargetMode) = ScheduledTask(
        label = "t", timeOfDayHour = 9, timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.DAILY, prompt = "x", targetMode = mode,
    )

    @Test
    fun `a scheduled child-of-current fire needs a slot only when its parent is full`() {
        assertNull(ScheduledAgentRunner.needsChildSlot(task(ScheduledTargetMode.ChildOfCurrent(p))))
        repeat(3) { child(p) }
        assertEquals(p, ScheduledAgentRunner.needsChildSlot(task(ScheduledTargetMode.ChildOfCurrent(p))))
        assertNull("another parent is unaffected",
            ScheduledAgentRunner.needsChildSlot(task(ScheduledTargetMode.ChildOfCurrent(q))))
        assertNull("a new-session task is not a child",
            ScheduledAgentRunner.needsChildSlot(task(ScheduledTargetMode.NewSession)))
    }

    @Test
    fun `the scheduled run defers on bgScope before creating anything`() {
        val src = ProductionSources.read("scheduled/ScheduledAgentRunner.kt")
        val run = src.substringAfter("needsChildSlot(task)?.let { parentSid ->")
        val gate = run.substringBefore("val firedAt = System.currentTimeMillis()")
        assertTrue("the gate sits before the fire is stamped / a session resolved",
            gate.length < run.length)
        assertTrue("non-waiting callers (alarm goAsync) defer on bgScope",
            gate.contains("if (!waitForCompletion) {\n                bgScope.launch {"))
        assertTrue(gate.contains("registry.awaitChildSlot(parentSid, CHILD_SLOT_WAIT_MS)"))
        assertTrue("the deferred fire re-enters run() once a slot frees",
            gate.contains("run(context, task, waitForCompletion = false, scheduledFire = scheduledFire)"))
    }

    // ── Session slots ──────────────────────────────────────────────────────

    @Test
    fun `a turn cancelled while waiting for a slot does not let an extra turn in`() = runBlocking {
        val max = SessionConcurrencyManager.maxConcurrent
        val base = SessionConcurrencyManager.runningSessions.value.size
        val holders = (1..(max - base)).map { "hold-" + UUID.randomUUID() }
        val cancelled = "cancelled-" + UUID.randomUUID()
        val next = "next-" + UUID.randomUUID()
        try {
            holders.forEach { SessionConcurrencyManager.acquireSlot(it) }
            assertEquals(max, SessionConcurrencyManager.runningSessions.value.size)

            // Mirrors a stream job: acquire inside try, release in finally.
            val waiting = launch(Dispatchers.Default) {
                try { SessionConcurrencyManager.acquireSlot(cancelled) }
                finally { SessionConcurrencyManager.releaseSlot(cancelled) }
            }
            var admitted = false
            val nextJob = launch(Dispatchers.Default) {
                SessionConcurrencyManager.acquireSlot(next); admitted = true
            }
            withTimeout(5_000) {
                while (!SessionConcurrencyManager.isSuspended(cancelled) ||
                    !SessionConcurrencyManager.isSuspended(next)) delay(10)
            }
            waiting.cancel()
            waiting.join()
            delay(100)
            assertFalse("the cancelled waiter's release held nothing and must not admit one",
                admitted)
            assertEquals(max, SessionConcurrencyManager.runningSessions.value.size)

            SessionConcurrencyManager.releaseSlot(holders.first())
            withTimeout(5_000) { nextJob.join() }
            assertTrue(admitted)
            assertEquals("a real release admits exactly one", max,
                SessionConcurrencyManager.runningSessions.value.size)
        } finally {
            (holders + cancelled + next).forEach { SessionConcurrencyManager.releaseSlot(it) }
        }
    }

    @Test
    fun `every stream path acquires and releases a session slot under the same child bypass`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val acquires = Regex("""SessionConcurrencyManager\.acquireSlot\(""").findAll(vm).count()
        val releases = Regex("""SessionConcurrencyManager\.releaseSlot\(""").findAll(vm).count()
        val guardedAcq = Regex("""if \(holdsSlot\) SessionConcurrencyManager\.acquireSlot\(""").findAll(vm).count()
        val guardedRel = Regex("""if \(holdsSlot\) SessionConcurrencyManager\.releaseSlot\(""").findAll(vm).count()
        val captures = Regex("""val holdsSlot = helperConfig == null""").findAll(vm).count()
        assertEquals("every acquire is child-gated", acquires, guardedAcq)
        assertEquals("every release is child-gated", releases, guardedRel)
        assertEquals("acquire / release pair one-to-one", guardedAcq, guardedRel)
        assertEquals("one captured decision per stream path", guardedAcq, captures)
        assertTrue(guardedAcq >= 5)
    }

    @Test
    fun `the model-facing tool text reads the per-parent constant`() {
        val tools = ProductionSources.read("tools/AgentTools.kt")
        assertTrue(tools.contains("AgentJobRegistry.MAX_CONCURRENT_CHILD_JOBS_PER_PARENT} run at once in this conversation"))
    }
}
