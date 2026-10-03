package com.openminis.app.ui.chat

import com.openminis.app.agent.jobs.HelperModelTier
import com.openminis.app.agent.jobs.HelperRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-subagent-badge] The card's chip should name the sub agent instead
 * of always reading "Agent".
 *
 * These also document the TIMING, which is the part that needed a decision:
 * Android's running payload (progressJson) carries no `agent` key at all —
 * only the final resultJson does. So the name can only appear when the run
 * ends, and the card must fall back to the generic label until then.
 */
class SubAgentBadgeTest {

    private fun block(json: String) = AssistantBlock(
        id = "b1", kind = "tool_use", content = json,
        toolStatus = ToolBlockStatus.SUCCESS, toolName = HelperRunner.TOOL_NAME,
    )

    @Test
    fun `a finished run carries the sub agent's name`() {
        val json = HelperRunner.resultJson(
            "completed", "body", "GPT", HelperModelTier.PRIMARY, "primary", 2, 1_000, "C", "J",
            summary = "", agentName = "svg-drawing-agent",
        )
        assertEquals("svg-drawing-agent", parseHelperBlock(block(json)).agent)
    }

    /**
     * The built-in agent writes NO name (ChatViewModel passes
     * `takeIf { !subAgent.isBuiltIn }`), so its card keeps the generic label.
     * That differs from iOS, which shows "General Sub Agent" — recorded here
     * rather than papered over, because changing it means changing what goes
     * on the wire, not what the card renders.
     */
    @Test
    fun `the built-in agent writes no name and keeps the generic label`() {
        val json = HelperRunner.resultJson(
            "completed", "body", "GPT", HelperModelTier.PRIMARY, "primary", 2, 1_000, "C", "J",
            summary = "", agentName = null,
        )
        assertNull(parseHelperBlock(block(json)).agent)
    }

    /**
     * The timing constraint. A running payload has no `agent`, so the badge
     * must not assume one is available mid-run.
     */
    @Test
    fun `a running payload carries no name`() {
        val running = HelperRunner.progressJson("C", "title", "browser_use", "working", 5_000)
        val info = parseHelperBlock(
            AssistantBlock(
                id = "b2", kind = "tool_use", content = running,
                toolStatus = ToolBlockStatus.RUNNING, toolName = HelperRunner.TOOL_NAME,
            ),
        )
        assertNull("progressJson has no agent key — the badge stays generic", info.agent)
    }
}
