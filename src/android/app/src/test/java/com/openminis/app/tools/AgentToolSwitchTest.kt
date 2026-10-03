package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-tools-granular-switches] The switch → tool mapping and the schema gate.
 *
 * The Context-bound halves (isEnabled / setEnabled / migrateLegacyIfNeeded)
 * need SharedPreferences, and this module has no Robolectric or mocking
 * library, so they are not covered here. What IS covered is the part a
 * refactor is most likely to get wrong silently: which tool each switch
 * governs, and whether turning one off actually removes the tool from the
 * schema the model sees.
 */
class AgentToolSwitchTest {

    @Test
    fun `each optional tool is governed by the expected switch`() {
        assertEquals(AgentToolSwitch.BROWSER, AgentToolSwitch.governing("browser_use"))
        assertEquals(AgentToolSwitch.AGENTS, AgentToolSwitch.governing("delegate_task"))
        // agent_status must ride with delegate_task: inspecting agents you can
        // no longer create is a dead tool in the schema.
        assertEquals(AgentToolSwitch.AGENTS, AgentToolSwitch.governing("agent_status"))
    }

    @Test
    fun `core tools are ungoverned and therefore always available`() {
        // A null here means "always on". If a future edit accidentally routed
        // one of these through a switch, the agent could lose shell or file
        // access from a settings toggle that never claimed to do that.
        for (core in listOf("shell_execute", "file_read", "file_write", "file_edit", "memory_get")) {
            assertNull("$core must not be gated by a tool switch", AgentToolSwitch.governing(core))
        }
    }

    @Test
    fun `browser defaults on and keys match the iOS contract`() {
        // Byte-identical keys are what let a future settings sync carry the
        // user's choice across platforms; a typo here would silently split them.
        assertEquals("agent.tools.browser.enabled", AgentToolSwitch.BROWSER.key)
        assertEquals("agent.tools.agents.enabled", AgentToolSwitch.AGENTS.key)
        assertTrue("browser_use is mature and ships on", AgentToolSwitch.BROWSER.defaultValue)
    }

    @Test
    fun `disabling a switch removes its tools from the schema`() {
        fun names(browser: Boolean, delegate: Boolean) =
            AgentTools.makeAgentTools(browserEnabled = browser, delegateEnabled = delegate)
                .map { it.name }.toSet()

        // [T-sub-agents-v1] delegate_task + agent_status became the single
        // subagent_task tool; the switch still governs the whole capability.
        val all = names(browser = true, delegate = true)
        assertTrue(all.containsAll(listOf("browser_use", "subagent_task")))

        val noBrowser = names(browser = false, delegate = true)
        assertFalse("browser_use must be absent, not merely refused", "browser_use" in noBrowser)
        assertTrue("the agents tool is independent", "subagent_task" in noBrowser)

        val noAgents = names(browser = true, delegate = false)
        assertFalse("subagent_task" in noAgents)
        assertTrue("browser_use is independent", "browser_use" in noAgents)

        // Both off: only the core tools remain, and shell/file access survives.
        val neither = names(browser = false, delegate = false)
        assertTrue("shell_execute" in neither && "file_read" in neither)
        for (gated in listOf("browser_use", "subagent_task")) {
            assertFalse(gated in neither)
        }
    }

    @Test
    fun `a helper never gets delegation even with the switch on`() {
        // Depth cap: helpers must not spawn helpers. The switch must not be
        // able to re-open that path.
        val helperTools = AgentTools.makeAgentTools(isHelper = true, delegateEnabled = true)
            .map { it.name }
        assertFalse("delegate_task" in helperTools)
        assertFalse("agent_status" in helperTools)
    }
}
