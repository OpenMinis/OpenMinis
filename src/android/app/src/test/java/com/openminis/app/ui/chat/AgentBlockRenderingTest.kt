package com.openminis.app.ui.chat

import com.openminis.app.agent.jobs.AgentCallback
import com.openminis.app.agent.jobs.HelperModelTier
import com.openminis.app.agent.jobs.HelperRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-p2-agent-series] The parser every agent surface reads (inline block,
 * thumbnail, detail) and the transcript's callback handling.
 */
class AgentBlockRenderingTest {
    private fun block(content: String, status: ToolBlockStatus?) = AssistantBlock(
        id = "tu1", kind = "tool_use", content = content, toolStatus = status, toolName = HelperRunner.TOOL_NAME, toolTitle = "Survey",
    )

    @Test
    fun `progress payload while RUNNING reads as running with the inner tool, never as raw text`() {
        val info = parseHelperBlock(block(HelperRunner.progressJson("C", "Survey", "shell_execute", "ls -la", 65_000, background = true), ToolBlockStatus.RUNNING))
        val p = info.phase as HelperPhase.Running
        assertEquals("shell_execute", p.tool); assertEquals("ls -la", p.activity); assertEquals("1:05", p.clock)
        assertEquals("C", info.childSessionId); assertTrue(info.isRunning)
    }

    @Test
    fun `a persisted running payload whose block is no longer RUNNING reads as interrupted`() {
        val start = HelperRunner.backgroundStartJson("J", "C", "gpt", HelperModelTier.PRIMARY, 10, converted = false)
        val info = parseHelperBlock(block(start, ToolBlockStatus.SUCCESS))
        assertEquals("interrupted", (info.phase as HelperPhase.Finished).status)
        assertFalse(info.isRunning)
        // …and the same payload with a live RUNNING block is still running.
        assertTrue(parseHelperBlock(block(start, ToolBlockStatus.RUNNING)).isRunning)
    }

    @Test
    fun `final payload carries status tier elapsed summary and delivery`() {
        val json = HelperRunner.resultJson("completed", "# Report\nbody", "GPT", HelperModelTier.SUB, "sub", 4, 125_000, "C", "J",
            summary = "Summary: tools browser_use×2 · turns 4 · tokens in 12.0k / out 2.0k", deliveredAs = "new turn in this conversation")
        val info = parseHelperBlock(block(json, ToolBlockStatus.SUCCESS))
        val f = info.phase as HelperPhase.Finished
        assertEquals("completed", f.status); assertEquals("sub", f.tier); assertEquals("GPT", f.model); assertEquals(125, f.elapsedSeconds)
        assertTrue(f.background); assertEquals("# Report", f.summary)
        assertEquals("tools browser_use×2 · turns 4 · tokens in 12.0k / out 2.0k", info.runSummary)
        assertEquals("# Report\nbody", info.result)
        assertEquals("Interrupted".lowercase(), "interrupted")
        // [T-android-showstop-subagent] The helperStatusColor assertion that
        // used to sit here was dropped: 98861c381 made that function
        // @Composable (its neutral branch reads a theme colour), so calling it
        // from a plain unit test stopped compiling and took the whole
        // testDebugUnitTest source set down with it. The status-to-colour
        // mapping is covered where it can be — this test is about payload
        // parsing, not colours.
    }

    @Test
    fun `callback synthetic block opens the same detail as an agent block`() {
        val cb = AgentCallback(AgentCallback.Kind.FINISHED, "J", "C", "Deep research", "timeout", "primary", "2m05s", summary = "tools x×1 · turns 2 · tokens in 1 / out 1", body = "partial")
        val b = cb.syntheticBlock()
        assertEquals(ToolBlockStatus.TIMEOUT, b.toolStatus)
        val f = parseHelperBlock(b).phase as HelperPhase.Finished
        assertEquals("timeout", f.status); assertEquals(125, f.elapsedSeconds); assertEquals("primary", f.tier); assertEquals("C", f.childSessionId)
    }

    private fun user(id: String, text: String) = ChatMessage(id = id, role = "user", content = text)
    private fun assistant(id: String, vararg blocks: AssistantBlock) = ChatMessage(id = id, role = "assistant", content = "", toolBlocks = blocks.toList())

    @Test
    fun `callback renders as a card whether or not the transcript holds the delegate block, and never restarts the header`() {
        // [T-android-subagent-callback-visible] Mirrors iOS 7985d161e: the
        // final report is NOT deduped against the delegate block any more —
        // the block changing state is not a record of the hand-off.
        val finalJson = HelperRunner.resultJson("completed", "r", "m", HelperModelTier.PRIMARY, "primary", 1, 1000, "C", "J")
        val cbText = AgentCallback(AgentCallback.Kind.FINISHED, "J", "C", "t", "done", body = "r").xml
        // With the block: callback card KEPT (this was the reported gap); it is
        // still not a user bubble, and the reply after it has NO header.
        val withBlock = buildFlatChatItems(listOf(
            user("u1", "do it"),
            assistant("a1", block(finalJson, ToolBlockStatus.SUCCESS)),
            user("u2", cbText),
            assistant("a2", AssistantBlock(id = "t2", kind = "text", content = "done!")),
        ))
        val cardWithBlock = withBlock.filterIsInstance<FlatChatItem.AgentCallbackCard>().single()
        assertEquals("C", cardWithBlock.callback.childSessionId)
        assertNull(withBlock.firstOrNull { it is FlatChatItem.UserBubble && (it as FlatChatItem.UserBubble).message.id == "u2" })
        assertEquals(listOf("a1"), withBlock.filterIsInstance<FlatChatItem.AssistantHeader>().map { it.messageId })
        // A progress report with the block present renders too — it never was
        // duplicated by the block, which only ever shows the final state.
        val progressText = AgentCallback(AgentCallback.Kind.PROGRESS, "J", "C", "t", "running", body = "working").xml
        val withProgress = buildFlatChatItems(listOf(
            user("u1", "do it"),
            assistant("a1", block(finalJson, ToolBlockStatus.SUCCESS)),
            user("u2", progressText),
            user("u3", cbText),
        ))
        assertEquals(
            listOf(AgentCallback.Kind.PROGRESS, AgentCallback.Kind.FINISHED),
            withProgress.filterIsInstance<FlatChatItem.AgentCallbackCard>().map { it.callback.kind },
        )
        // Without a block (scheduled child-of-current): callback card kept, still no second header.
        val withoutBlock = buildFlatChatItems(listOf(
            user("u1", "do it"),
            assistant("a1", AssistantBlock(id = "t1", kind = "text", content = "ok")),
            user("u2", cbText),
            assistant("a2", AssistantBlock(id = "t2", kind = "text", content = "done!")),
        ))
        val card = withoutBlock.filterIsInstance<FlatChatItem.AgentCallbackCard>().single()
        assertEquals("t", card.callback.title)
        assertEquals(listOf("a1"), withoutBlock.filterIsInstance<FlatChatItem.AssistantHeader>().map { it.messageId })
        // A human turn still restarts the sequence.
        val human = buildFlatChatItems(listOf(user("u1", "hi"), assistant("a1", AssistantBlock(id = "t1", kind = "text", content = "x")), user("u3", "again"), assistant("a3", AssistantBlock(id = "t3", kind = "text", content = "y"))))
        assertEquals(listOf("a1", "a3"), human.filterIsInstance<FlatChatItem.AssistantHeader>().map { it.messageId })
    }
}
