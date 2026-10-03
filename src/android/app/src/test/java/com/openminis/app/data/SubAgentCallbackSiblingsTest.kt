package com.openminis.app.data

import com.openminis.app.agent.jobs.AgentCallback
import com.openminis.app.agent.jobs.AgentJobRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-sub-agents-sibling-status] Every callback tells the parent about the rest
 * of the batch.
 *
 * Without this a model that fanned out three delegations and got one result
 * back can quite reasonably summarise as if it had them all — the parent sees
 * one result at a time and nothing else says whether the rest is still coming,
 * already lost, or never started.
 */
class SubAgentCallbackSiblingsTest {

    private fun cb(siblings: String?, agent: String? = null) = AgentCallback(
        kind = AgentCallback.Kind.FINISHED,
        jobId = "job-1",
        childSessionId = "child-1",
        title = "Survey the repo",
        status = "done",
        agentName = agent,
        summary = "tools shell_execute x2 · turns 3",
        siblings = siblings,
        body = "the answer",
    )

    @Test
    fun `the sibling line is emitted as its own element`() {
        val xml = cb("Other sub agents in this conversation: 2 still running.").xml
        assertTrue(xml.contains("<other_sub_agents>"))
        assertTrue(xml.contains("2 still running"))
    }

    @Test
    fun `a lone sub agent emits no sibling element at all`() {
        val xml = cb(null).xml
        assertFalse(xml.contains("other_sub_agents"))
    }

    @Test
    fun `a callback round-trips through parse unchanged`() {
        // The envelope is re-parsed when a transcript is reloaded, so a value
        // that survives emission but not parsing would silently vanish.
        val original = cb("Other sub agents in this conversation: 1 queued.", agent = "Researcher")
        val parsed = AgentCallback.parse(original.xml)
        assertNotNull(parsed)
        assertEquals(original.siblings, parsed!!.siblings)
        assertEquals(original.agentName, parsed.agentName)
        assertEquals(original.jobId, parsed.jobId)
        assertEquals(original.status, parsed.status)
        assertEquals(original.body, parsed.body)
    }

    @Test
    fun `text needing escaping survives the round trip`() {
        val tricky = "Other sub agents: 1 running <not a tag> & \"quoted\"."
        val parsed = AgentCallback.parse(cb(tricky).xml)
        assertEquals(tricky, parsed?.siblings)
    }

    @Test
    fun `the named agent is an attribute and the built-in omits it`() {
        assertTrue(cb(null, agent = "Researcher").xml.contains("agent=\"Researcher\""))
        assertFalse(cb(null, agent = null).xml.contains(" agent="))
    }

    // ── The summary text itself ─────────────────────────────────────────────

    @Test
    fun `a parent with nothing else running gets no line`() {
        assertNull(AgentJobRegistry.siblingSummary("no-such-parent", "job-1", interruptedCount = 0))
    }

    @Test
    fun `interrupted runs are reported with how to recover them`() {
        val line = AgentJobRegistry.siblingSummary("no-such-parent", "job-1", interruptedCount = 2)
        assertNotNull(line)
        assertTrue(line!!.contains("2 interrupted"))
        // Naming the recovery is the point: otherwise the model has no way to
        // know those runs can be restarted rather than re-delegated.
        assertTrue(line.contains("action=resume"))
    }

    @Test
    fun `the line reads as one sentence listing each category`() {
        val line = AgentJobRegistry.siblingSummary("no-such-parent", "job-1", interruptedCount = 1)
        assertTrue(line!!.startsWith("Other sub agents in this conversation:"))
        assertTrue(line.endsWith("."))
    }
}
