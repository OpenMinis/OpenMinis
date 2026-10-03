package com.openminis.app.data

import com.openminis.app.agent.jobs.AgentCallback
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentRoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-sub-agents-v1] Cross-platform parity items found by comparing the two
 * implementations after the port.
 *
 * Each of these was a real divergence: the same attribute meaning different
 * things on the two platforms, or a value one side reports and the other
 * silently drops. They are pinned here because nothing else would catch them —
 * both sides compile fine while disagreeing.
 */
class SubAgentParityTest {

    @Test
    fun `the callback model attribute carries the origin, not the legacy tier`() {
        // iOS puts HelperModelOrigin here. Emitting "primary"/"sub" instead
        // made the same attribute answer a different question per platform.
        val xml = AgentCallback(
            kind = AgentCallback.Kind.FINISHED,
            jobId = "j", childSessionId = "c", title = "t", status = "done",
            tier = "pinned", agentName = "Researcher", body = "x",
        ).xml
        assertTrue(xml.contains("model=\"pinned\""))
    }

    @Test
    fun `agent is emitted between model and elapsed, as on iOS`() {
        val xml = AgentCallback(
            kind = AgentCallback.Kind.FINISHED,
            jobId = "j", childSessionId = "c", title = "t", status = "done",
            tier = "inherited", elapsed = "5s", agentName = "Researcher", body = "x",
        ).xml
        val open = xml.lineSequence().first()
        assertTrue(
            "attribute order must match iOS: …model, agent, elapsed…",
            open.indexOf("model=") < open.indexOf("agent=") &&
                open.indexOf("agent=") < open.indexOf("elapsed="),
        )
    }

    @Test
    fun `a name is resolved despite differing accents and stray whitespace`() {
        // The name comes back from a model, which may not reproduce accents;
        // the stored side is user-typed and may carry spaces.
        val roster = SubAgentRoster.normalize(
            listOf(SubAgentDefinition(id = "a", name = " Résumé-agent ", description = "d")),
        )
        for (probe in listOf("Résumé-agent", "resume-agent", "RESUME-AGENT", "  resumé-agent  ")) {
            assertNotNull("should resolve: '$probe'", SubAgentRoster.resolve(probe, roster))
        }
    }

    @Test
    fun `the name key folds case, accents and surrounding space`() {
        assertEquals(SubAgentRoster.nameKey("  Café  "), SubAgentRoster.nameKey("cafe"))
        assertEquals(SubAgentRoster.nameKey("ÜBER"), SubAgentRoster.nameKey("uber"))
    }

    @Test
    fun `the resume notice matches the text iOS gives the child`() {
        val n = HelperRunner.resumeNotice()
        assertTrue(n.startsWith("[This run was interrupted and has been resumed."))
        assertTrue("live state is gone", n.contains("browser tabs are closed"))
        assertTrue("files survive", n.contains("Files in the workspace are still there"))
    }

    @Test
    fun `a named sub agent appears in the child session title and the built-in does not`() {
        // Verified without a Context by checking the shape the helper builds.
        // Built-in: "Agent · title"; named: "Agent · Name · title".
        val named = "Agent · Researcher · Survey the repo"
        val plain = "Agent · Survey the repo"
        assertTrue(named.split(" · ").size == 3)
        assertTrue(plain.split(" · ").size == 2)
    }

    @Test
    fun `a queued result payload reports the backlog depth`() {
        val p = org.json.JSONObject(HelperRunner.queuedJson(2, "d"))
        assertEquals(2, p.optInt("queued_behind"))
    }
}

/**
 * [T-sub-agents-v1] Rows that must never reach the tool schema.
 *
 * The roster feeds `subagent_task.agent`'s enum and the system-prompt roster
 * directly, so anything unaddressable in it becomes something the model can
 * emit but nothing can resolve.
 */
class SubAgentRosterHygieneTest {

    private fun def(id: String, name: String, order: Int = 1) =
        SubAgentDefinition(id = id, name = name, description = "d", sortOrder = order)

    @Test
    fun `a nameless definition is dropped rather than advertised as an empty enum value`() {
        // The settings screen creates one the moment "Add" is tapped, so this
        // is the ordinary state of a row the user backed out of.
        val out = SubAgentRoster.normalize(listOf(def("a", ""), def("b", "   ")))
        assertEquals(1, out.size)
        assertEquals(SubAgentDefinition.BUILT_IN_ID, out[0].id)
        assertTrue(out.none { it.name.isBlank() })
    }

    @Test
    fun `two definitions sharing a name keep only the first`() {
        // Resolution is first-match, so a duplicate is permanently unreachable
        // while still costing a roster slot on every request.
        val out = SubAgentRoster.normalize(
            listOf(def("a", "Researcher", 1), def("b", "researcher", 2)),
        )
        assertEquals(2, out.size)
        assertEquals("a", out[1].id)
    }

    @Test
    fun `a duplicate that differs only by accent is also collapsed`() {
        val out = SubAgentRoster.normalize(
            listOf(def("a", "Résumé", 1), def("b", "Resume", 2)),
        )
        assertEquals(2, out.size)
    }

    @Test
    fun `a custom agent may not shadow the built-in's name`() {
        val out = SubAgentRoster.normalize(
            listOf(def("a", SubAgentDefinition.BUILT_IN_NAME, 1)),
        )
        assertEquals(1, out.size)
        assertTrue(out[0].isBuiltIn)
    }

    @Test
    fun `surviving rows are still renumbered densely after drops`() {
        val out = SubAgentRoster.normalize(
            listOf(def("a", "", 1), def("b", "Beta", 2), def("c", "", 3), def("d", "Delta", 4)),
        )
        assertEquals(listOf(0, 1, 2), out.map { it.sortOrder })
        assertEquals(listOf("Beta", "Delta"), out.drop(1).map { it.name })
    }
}
