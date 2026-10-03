package com.openminis.app.agent.jobs

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T25] Sub-agent lifecycle, as one table.
 *
 * The rules this pins were shipped one at a time and live in three places
 * (AgentJobRegistry, HelperRunner, AgentCallback). Android commits:
 * c21fa4b0d (Stop on one card stops the batch; a finished sibling cannot re-wake
 * the stopped parent), 140844023 (every callback reaches the parent — one per
 * sub agent, never coalesced; iOS b56101f3b), 8d320582c (control-call
 * classification: `child_session_id` is tested before `resumed`), and the
 * resume path (iOS 4a1ddb032: a resumed run keeps its named sub agent).
 *
 * Each row is a full scenario against the REAL registry (reset per row). The
 * table runs as one test so the whole state machine is read in one place; a
 * failing row names itself.
 */
class SubAgentLifecycleTableTest {

    private val parent = "P-lifecycle"

    @Before
    fun reset() {
        AgentJobRegistry.resetForTest()
        AgentJobRegistry.dropQueuedDelegations(parent, "test-reset")
        AgentJobRegistry.unregisterQueuedStarter(parent)
    }

    @After
    fun tearDown() {
        AgentJobRegistry.followUpDispatcher = null
        AgentJobRegistry.unregisterQueuedStarter(parent)
        AgentJobRegistry.dropQueuedDelegations(parent, "test-teardown")
        AgentJobRegistry.resetForTest()
    }

    private fun child(
        title: String,
        agentName: String? = null,
        wasResumed: Boolean = false,
        runSessionId: String? = null,
    ): AgentJob = AgentJobRegistry.register(
        title = title,
        origin = AgentJobOrigin.TOOL,
        trigger = AgentJobTrigger.Immediate,
        target = AgentJobTarget.ChildOfCurrent(parent, "tu-$title"),
        prompt = "do $title",
        then = AgentJobThen.FollowUpParent(null),
        agentName = agentName,
        runSessionId = runSessionId,
        wasResumed = wasResumed,
    )

    private fun state(id: String) = AgentJobRegistry.job(id)?.state

    private class Row(val name: String, val body: () -> Unit)

    private val table: List<Row> = listOf(

        // ── Stop: one card, whole batch ─────────────────────────────────
        Row("cancel one of a batch of 3 → all 3 cancelled, each hook fired once") {
            val a = child("a"); val b = child("b"); val c = child("c")
            val hooks = mutableMapOf<String, Int>()
            for ((j, sid) in listOf(a to "S-a", b to "S-b", c to "S-c")) {
                AgentJobRegistry.markRunning(j.id, sid) { hooks[j.id] = (hooks[j.id] ?: 0) + 1 }
            }
            AgentJobRegistry.cancelSiblings("S-a", "user-stopped")
            assertEquals(AgentJobState.CANCELLED, state(a.id))
            assertEquals(AgentJobState.CANCELLED, state(b.id))
            assertEquals(AgentJobState.CANCELLED, state(c.id))
            assertEquals(mapOf(a.id to 1, b.id to 1, c.id to 1), hooks)
            assertTrue("the parent is muted so late results cannot restart it", AgentJobRegistry.isDelegationMuted(parent))
        },

        Row("cancelling the batch drops its queued delegations BEFORE freeing slots") {
            val a = child("a")
            AgentJobRegistry.markRunning(a.id, "S-a") {}
            var started = 0
            AgentJobRegistry.registerQueuedStarter(parent) { started++; true }
            assertTrue(AgentJobRegistry.enqueueDelegation(AgentJobRegistry.QueuedDelegation(parent, """{"task":"queued"}""", "tu-q")))
            assertEquals(1, AgentJobRegistry.queuedCount(parent))
            AgentJobRegistry.cancelSiblings("S-a", "user-stopped")
            assertEquals("the queue is emptied, not drained into a fresh start", 0, AgentJobRegistry.queuedCount(parent))
            assertEquals("no queued delegation may start from the batch being stopped", 0, started)
        },

        Row("a run with no delegating parent stops only itself") {
            val lone = AgentJobRegistry.register(
                title = "lone", origin = AgentJobOrigin.CLI, trigger = AgentJobTrigger.Immediate,
                target = AgentJobTarget.New, prompt = "x", then = AgentJobThen.None,
            )
            val other = child("other")
            AgentJobRegistry.markRunning(lone.id, "S-lone") {}
            AgentJobRegistry.markRunning(other.id, "S-other") {}
            AgentJobRegistry.cancelSiblings("S-lone", "user-stopped")
            assertEquals(AgentJobState.CANCELLED, state(lone.id))
            assertEquals("an unrelated child is untouched", AgentJobState.RUNNING, state(other.id))
        },

        // ── A finished sibling cannot re-wake a stopped parent ──────────
        Row("parent stopped, sibling finishes → its callback is NOT dispatched") {
            val dispatched = mutableListOf<String>()
            AgentJobRegistry.followUpDispatcher = { _, _, jobId -> dispatched += jobId }
            val a = child("a"); val b = child("b")
            AgentJobRegistry.markRunning(a.id, "S-a") {}
            AgentJobRegistry.markRunning(b.id, "S-b") {}
            // Stop's FIRST step is the mute; b's completion lands in the window
            // before the cancel reaches it (the 48ms race in the iOS log).
            AgentJobRegistry.muteDelegationResults(parent)
            AgentJobRegistry.finish(b.id, AgentJobState.DONE, "late result")
            assertEquals("the stopped parent must stay quiet", emptyList<String>(), dispatched)
            assertEquals("…but the job still records its result for the transcript", "late result", AgentJobRegistry.job(b.id)?.resultText)
            assertEquals(AgentJobState.DONE, state(b.id))
            AgentJobRegistry.cancelSiblings("S-a", "user-stopped")
            assertEquals(AgentJobState.CANCELLED, state(a.id))
            assertEquals("a finished sibling is not re-labelled by the stop", AgentJobState.DONE, state(b.id))
            assertEquals(emptyList<String>(), dispatched)
        },

        Row("new work in the session lifts the mute") {
            val dispatched = mutableListOf<String>()
            AgentJobRegistry.followUpDispatcher = { _, _, jobId -> dispatched += jobId }
            AgentJobRegistry.muteDelegationResults(parent)
            AgentJobRegistry.clearDelegationMute(parent)
            val a = child("a")
            AgentJobRegistry.markRunning(a.id, "S-a") {}
            AgentJobRegistry.finish(a.id, AgentJobState.DONE, "r")
            assertEquals(listOf(a.id), dispatched)
        },

        // ── Resume keeps the named sub agent ────────────────────────────
        Row("interrupted → resumed run keeps its sub agent name end to end") {
            val resumed = child("Survey", agentName = "LENS", wasResumed = true, runSessionId = "S-child")
            assertEquals("LENS", resumed.agentName)
            assertTrue(resumed.wasResumed)
            assertEquals("the child is claimed at register time, before markRunning", resumed.id, AgentJobRegistry.jobForSession("S-child")?.id)
            AgentJobRegistry.markRunning(resumed.id, "S-child") {}
            AgentJobRegistry.finish(resumed.id, AgentJobState.DONE, "report")
            val cb = AgentJobRegistry.completionCallback(AgentJobRegistry.job(resumed.id)!!, "report")
            assertEquals("LENS", cb.agentName)
            assertEquals("LENS", AgentCallback.parse(cb.xml)!!.agentName)
            assertTrue(cb.xml.contains("agent=\"LENS\""))
        },

        Row("the resumed run's payload names its agent and says it was resumed") {
            val payload = JSONObject(
                HelperRunner.resultJson(
                    status = "completed", result = "done", modelLabel = "m", tierUsed = HelperModelTier.PRIMARY,
                    tierRequested = "auto", turns = 2, elapsedMs = 5_000, childSessionId = "S-child", jobId = "J",
                    agentName = "LENS", wasResumed = true,
                ),
            )
            assertEquals("LENS", payload.getString("agent"))
            assertTrue(payload.getBoolean("resumed"))
            assertEquals(HelperRunner.RESUMED_NOTE, payload.getString("resumed_note"))
            assertEquals("S-child", payload.getString("child_session_id"))
        },

        // ── Steer reaches the child; a finished child rejects it ────────
        Row("steer is delivered to a running child and refused once it finished") {
            val a = child("a")
            AgentJobRegistry.markRunning(a.id, "S-a") {}
            val received = mutableListOf<String>()
            AgentJobRegistry.registerSteerHook(a.id) { received += it; true }
            assertTrue(AgentJobRegistry.steer(a.id, "focus on 2023"))
            assertEquals(listOf("focus on 2023"), received)
            AgentJobRegistry.finish(a.id, AgentJobState.DONE, "r")
            assertFalse("a finished child cannot take a steer", AgentJobRegistry.steer(a.id, "too late"))
            assertEquals("nothing delivered after the finish", 1, received.size)
        },

        // ── Every completion wakes the parent — once each ───────────────
        Row("3 sub agents finishing → 3 callbacks, never one coalesced batch") {
            val dispatched = mutableListOf<Pair<String, String>>()
            AgentJobRegistry.followUpDispatcher = { p, text, jobId -> dispatched += jobId to text; check(p == parent) }
            val jobs = listOf(child("a"), child("b"), child("c"))
            jobs.forEachIndexed { i, j -> AgentJobRegistry.markRunning(j.id, "S-$i") {} }
            AgentJobRegistry.finish(jobs[1].id, AgentJobState.DONE, "second")
            assertEquals("the first result to land wakes the parent at once, with siblings still running", 1, dispatched.size)
            AgentJobRegistry.finish(jobs[0].id, AgentJobState.TIMEOUT, "partial")
            AgentJobRegistry.finish(jobs[2].id, AgentJobState.DONE, "third")
            assertEquals(3, dispatched.size)
            assertEquals(jobs.map { it.id }.toSet(), dispatched.map { it.first }.toSet())
            // Each envelope is a parseable callback for ITS job, carrying its own status.
            val parsed = dispatched.map { AgentCallback.parse(it.second)!! }
            assertEquals(listOf("done", "timeout", "done"), parsed.map { it.status })
            assertEquals(dispatched.map { it.first }, parsed.map { it.jobId })
        },

        Row("finish is idempotent — a second terminal state adds no second callback") {
            var n = 0
            AgentJobRegistry.followUpDispatcher = { _, _, _ -> n++ }
            val a = child("a")
            AgentJobRegistry.markRunning(a.id, "S-a") {}
            AgentJobRegistry.finish(a.id, AgentJobState.DONE, "r1")
            AgentJobRegistry.finish(a.id, AgentJobState.FAILED, "r2")
            AgentJobRegistry.cancel(a.id, "late")
            assertEquals(1, n)
            assertEquals(AgentJobState.DONE, state(a.id))
        },

        // ── Control-call classification order ───────────────────────────
        Row("resumed=true with child_session_id is still a delegation card") {
            val payload = HelperRunner.resultJson(
                status = "completed", result = "x", modelLabel = "m", tierUsed = HelperModelTier.PRIMARY,
                tierRequested = "auto", turns = 1, elapsedMs = 1_000, childSessionId = "c1", jobId = "j1",
                wasResumed = true,
            )
            assertEquals(HelperRunner.SubAgentCall.DELEGATE, HelperRunner.classifyCall(null, payload))
            assertFalse(HelperRunner.isControlOnly(null, payload))
        },

        Row("child_session_id is tested before every control-shape marker") {
            val hybrid = """{"child_session_id":"c1","resumed":true,"agents":[{}],"child_session_ids":["c1"],"status":"queued","job_id":"j"}"""
            assertEquals(HelperRunner.SubAgentCall.DELEGATE, HelperRunner.classifyCall(null, hybrid))
        },

        Row("control calls classify by their action first, then by result shape") {
            val byAction = mapOf(
                "status" to HelperRunner.SubAgentCall.STATUS,
                "steer" to HelperRunner.SubAgentCall.STEER,
                "cancel" to HelperRunner.SubAgentCall.CANCEL,
                "resume" to HelperRunner.SubAgentCall.RESUME,
                "delegate" to HelperRunner.SubAgentCall.DELEGATE,
            )
            for ((action, want) in byAction) {
                assertEquals(action, want, HelperRunner.classifyCall("""{"action":"$action"}""", null))
            }
            val byShape = mapOf(
                """{"agents":[]}""" to HelperRunner.SubAgentCall.STATUS,
                """{"child_session_ids":["a"]}""" to HelperRunner.SubAgentCall.RESUME,
                """{"status":"queued","job_id":"j"}""" to HelperRunner.SubAgentCall.STEER,
                """{"ok":false,"reason":"already_finished"}""" to HelperRunner.SubAgentCall.STEER,
                """{"child_session_id":"","agents":[]}""" to HelperRunner.SubAgentCall.STATUS,
                """{"ok":true}""" to HelperRunner.SubAgentCall.DELEGATE,
            )
            for ((shape, want) in byShape) {
                assertEquals(shape, want, HelperRunner.classifyCall(null, shape))
            }
            assertTrue(HelperRunner.isControlOnly("""{"action":"status"}""", """{"agents":[{"child_session_id":"c1"}]}"""))
        },

        // ── The sibling line on every callback ───────────────────────────
        Row("a callback tells the parent about the rest of the batch") {
            val a = child("a"); val b = child("b"); val c = child("c")
            AgentJobRegistry.markRunning(a.id, "S-a") {}
            AgentJobRegistry.markRunning(b.id, "S-b") {}
            // c stays PENDING (waiting for a slot).
            AgentJobRegistry.finish(a.id, AgentJobState.DONE, "r")
            val cb = AgentJobRegistry.completionCallback(AgentJobRegistry.job(a.id)!!, "r")
            val siblings = cb.siblings ?: ""
            // One running + one pending are both "still running" from the parent's side.
            assertTrue("running and pending siblings must be reported: '$siblings'", siblings.contains("2 still running"))
            assertEquals(
                "Other sub agents in this conversation: 2 still running.",
                AgentJobRegistry.siblingSummary(parent, a.id, 0),
            )
            assertTrue(cb.xml.contains("<other_sub_agents>"))
            assertEquals(siblings, AgentCallback.parse(cb.xml)!!.siblings)
            assertEquals(AgentJobState.PENDING, state(c.id))
        },
    )

    @Test
    fun `sub agent lifecycle table`() {
        val failures = mutableListOf<String>()
        var passed = 0
        for (row in table) {
            reset()
            try {
                row.body()
                passed++
            } catch (t: Throwable) {
                failures += "✗ ${row.name}: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                tearDown()
            }
        }
        println("[T25] lifecycle rows passed: $passed/${table.size}")
        assertTrue(
            "lifecycle rows failed:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
        assertEquals(table.size, passed)
    }
}
