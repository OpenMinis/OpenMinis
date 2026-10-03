package com.openminis.app.data

import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.HelperRunner
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-sub-agents-queue] Delegations past the limits queue instead of being
 * refused.
 *
 * The queue is the difference between a model that fans out five tasks getting
 * five results, and getting three results plus two rejections it has to notice
 * and retry. These pin the parts that are easy to get subtly wrong: the backlog
 * bound, the per-parent scoping, and the re-entry marker whose absence is a
 * permanent deadlock rather than a slow path.
 */
class SubAgentQueueTest {

    private val parent = "parent-session"

    private fun item(tool: String, p: String = parent) =
        AgentJobRegistry.QueuedDelegation(
            parentSessionId = p,
            argsJson = """{"tool_title":"t","task":"work"}""",
            toolUseId = tool,
        )

    @Before
    fun setUp() {
        AgentJobRegistry.dropQueuedDelegations(parent, "test-setup")
        AgentJobRegistry.dropQueuedDelegations("other", "test-setup")
    }

    @After
    fun tearDown() {
        AgentJobRegistry.dropQueuedDelegations(parent, "test-teardown")
        AgentJobRegistry.dropQueuedDelegations("other", "test-teardown")
    }

    @Test
    fun `a queued delegation is tracked by its tool use id`() {
        assertFalse(AgentJobRegistry.isQueued("tool-1"))
        assertTrue(AgentJobRegistry.enqueueDelegation(item("tool-1")))
        assertTrue(AgentJobRegistry.isQueued("tool-1"))
        assertEquals(1, AgentJobRegistry.queuedCount(parent))
    }

    @Test
    fun `the backlog is bounded and says so rather than silently dropping`() {
        repeat(AgentJobRegistry.MAX_QUEUED_DELEGATIONS) { i ->
            assertTrue("slot $i should accept", AgentJobRegistry.enqueueDelegation(item("tool-$i")))
        }
        // Past the bound the caller must be told it was NOT queued, so the
        // model re-delegates later instead of waiting for a result that will
        // never come.
        assertFalse(AgentJobRegistry.enqueueDelegation(item("tool-overflow")))
        assertFalse(AgentJobRegistry.isQueued("tool-overflow"))
    }

    @Test
    fun `the queue is scoped per parent conversation`() {
        AgentJobRegistry.enqueueDelegation(item("mine", parent))
        AgentJobRegistry.enqueueDelegation(item("theirs", "other"))
        assertEquals(1, AgentJobRegistry.queuedCount(parent))
        assertEquals(1, AgentJobRegistry.queuedCount("other"))

        // Dropping one parent's backlog must not touch another's.
        AgentJobRegistry.dropQueuedDelegations(parent, "test")
        assertEquals(0, AgentJobRegistry.queuedCount(parent))
        assertEquals(1, AgentJobRegistry.queuedCount("other"))
    }

    @Test
    fun `dropping a parent's backlog clears every one of its entries`() {
        repeat(3) { AgentJobRegistry.enqueueDelegation(item("t$it")) }
        AgentJobRegistry.dropQueuedDelegations(parent, "user-stopped")
        assertEquals(0, AgentJobRegistry.queuedCount(parent))
        for (i in 0 until 3) assertFalse(AgentJobRegistry.isQueued("t$i"))
    }

    // ── The re-entry marker ─────────────────────────────────────────────────

    @Test
    fun `the re-entry marker survives a round trip through the args`() {
        // Without this flag the positional per-turn test re-fires on every
        // drain. `priorDelegates` counts the call's position among the turn's
        // delegate blocks, and that position never changes — so a task queued
        // for being the 4th of five is still the 4th every time a slot frees,
        // and it is re-queued forever. That was a permanent deadlock for
        // anything past the third delegation of a turn (iOS f02b68b4e), not a
        // slow path.
        val args = JSONObject("""{"tool_title":"t","task":"work"}""")
            .put(HelperRunner.QUEUED_REENTRY_KEY, true)
            .toString()
        assertTrue(JSONObject(args).optBoolean(HelperRunner.QUEUED_REENTRY_KEY, false))
    }

    @Test
    fun `an ordinary call carries no re-entry marker`() {
        val args = JSONObject("""{"tool_title":"t","task":"work"}""")
        assertFalse(args.optBoolean(HelperRunner.QUEUED_REENTRY_KEY, false))
    }

    @Test
    fun `the marker key matches the iOS wire name`() {
        assertEquals("__from_queue", HelperRunner.QUEUED_REENTRY_KEY)
    }

    // ── The queued payload ──────────────────────────────────────────────────

    @Test
    fun `a queued result reports success so the model does not treat it as failure`() {
        // ok=true is deliberate: the delegation was accepted, it just has not
        // started. Reporting failure would invite an immediate re-delegation,
        // which is the behaviour the queue exists to prevent.
        val p = JSONObject(HelperRunner.queuedJson(2, "queued behind others"))
        assertTrue(p.optBoolean("ok"))
        assertEquals("queued", p.optString("status"))
        assertEquals(2, p.optInt("queued_behind"))
        assertEquals("queued behind others", p.optString("detail"))
    }

    @Test
    fun `a negative queue depth is never reported`() {
        // The count is derived from running + queued - 1 and could go negative
        // in a race; a negative "queued behind" would read as nonsense.
        assertEquals(0, JSONObject(HelperRunner.queuedJson(-3, "d")).optInt("queued_behind"))
    }

    // ── Concurrency accounting ──────────────────────────────────────────────

    @Test
    fun `the concurrency limit matches the documented contract`() {
        // The tool description promises "Only 3 run at once"; if this constant
        // moves, that text is a lie.
        assertEquals(3, AgentJobRegistry.MAX_CONCURRENT_CHILD_JOBS)
        assertEquals(3, HelperRunner.MAX_PER_ASSISTANT_TURN)
    }

    @Test
    fun `draining a stalled queue with no free slot is a no-op`() {
        AgentJobRegistry.enqueueDelegation(item("tool-x"))
        // No starter is registered for this parent, so the drain cannot start
        // it; the important part is that it does not throw or lose the entry
        // when nothing can run it.
        AgentJobRegistry.drainIfStalled()
        // The entry is dropped rather than left dangling, because no live view
        // model can ever re-enter it.
        assertEquals(0, AgentJobRegistry.queuedCount(parent))
    }
}
