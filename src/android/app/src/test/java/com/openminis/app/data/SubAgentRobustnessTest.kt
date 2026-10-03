package com.openminis.app.data

import com.openminis.app.agent.jobs.AgentCallback
import com.openminis.app.agent.jobs.AgentJobOrigin
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.AgentJobState
import com.openminis.app.agent.jobs.AgentJobTarget
import com.openminis.app.agent.jobs.AgentJobTrigger
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-sub-agents-v1] Failure modes found auditing the port's runtime behaviour.
 *
 * Each of these is reachable from ordinary use — a typed message, a resumed
 * run, one odd row in a transcript — and each was silent: no crash report, just
 * a wrong answer or a permanently consumed resource.
 */
class SubAgentRobustnessTest {

    // ── Parsing must not crash on text a user can type ──────────────────────

    @Test
    fun `a bare callback tag with no attributes does not crash the parser`() {
        // isCallbackText accepts "<agent_callback>", for which the attribute
        // span is empty and the substring bounds inverted. A user typing that
        // literal string used to take the renderer down with it.
        assertNull(AgentCallback.parse("<agent_callback>"))
        assertNull(AgentCallback.parse("<agent_callback>hello</agent_callback>"))
    }

    @Test
    fun `a well-formed callback still parses`() {
        val xml = AgentCallback(
            kind = AgentCallback.Kind.FINISHED, jobId = "j", childSessionId = "c",
            title = "t", status = "done", body = "answer",
        ).xml
        assertNotNull(AgentCallback.parse(xml))
    }

    // ── One malformed part must not lose the child's deliverable ────────────

    @Test
    fun `a malformed part does not discard the rest of the child's answer`() {
        // This text is returned verbatim to the parent as the result. Aborting
        // the whole row on one odd element meant the parent got the "no result"
        // note instead of the answer the child had written.
        val row = MessageEntity(
            id = "m1",
            sessionId = "s1",
            role = "assistant",
            // A bare string where an object is expected, then the real text.
            partsJson = """["junk",{"type":"text","value":"the deliverable"}]""",
            createdAt = 0L,
            sortOrder = 0,
        )
        val facts = HelperRunner.childRunFacts(listOf(row))
        assertTrue(
            "expected the deliverable to survive, got '${facts.lastText}'",
            facts.lastText.contains("the deliverable"),
        )
    }

    @Test
    fun `tool names are still collected around a malformed part`() {
        val row = MessageEntity(
            id = "m1", sessionId = "s1", role = "assistant",
            partsJson = """[1,{"type":"toolUse","value":{"name":"shell_execute"}}]""",
            createdAt = 0L, sortOrder = 0,
        )
        assertEquals(listOf("shell_execute"), HelperRunner.childRunFacts(listOf(row)).toolNames)
    }

    // ── A resumed run must own its child immediately ────────────────────────

    @Test
    fun `a job claims its child session from registration, not from first run`() {
        // interruptedChildIds treats a child with no live job as lost. If the
        // claim waited for markRunning, a second resume in that window would
        // start a duplicate run on the same session — two configs on one child
        // view model, and a job that never finishes holding a slot forever.
        val job = AgentJobRegistry.register(
            title = "t",
            origin = AgentJobOrigin.TOOL,
            trigger = AgentJobTrigger.Immediate,
            target = AgentJobTarget.ChildOfCurrent("p1", "tool-1"),
            prompt = null,
            runSessionId = "child-42",
        )
        assertEquals(job.id, AgentJobRegistry.jobForSession("child-42")?.id)
        AgentJobRegistry.finish(job.id, AgentJobState.DONE, null)
    }

    @Test
    fun `an ordinary delegation still claims its child only when it starts`() {
        val job = AgentJobRegistry.register(
            title = "t",
            origin = AgentJobOrigin.TOOL,
            trigger = AgentJobTrigger.Immediate,
            target = AgentJobTarget.ChildOfCurrent("p1", "tool-2"),
            prompt = "work",
        )
        assertNull(AgentJobRegistry.jobForSession("not-claimed"))
        AgentJobRegistry.finish(job.id, AgentJobState.DONE, null)
    }
}
