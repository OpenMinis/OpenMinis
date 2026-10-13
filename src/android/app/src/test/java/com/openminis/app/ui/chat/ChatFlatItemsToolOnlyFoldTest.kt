package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the user report: with AI-process folding enabled, a turn
 * consisting ONLY of tool calls (no reply text) rendered a large blank band —
 * the standalone AssistantHeader row above an otherwise empty collapsed turn.
 *
 * [T-android-round-collapse] Under the round model the blank band is
 * structurally impossible for single-message rounds (folding needs ≥ 3
 * foldable steps and always keeps the latest 2), so the header-hidden path
 * only fires for a message whose blocks all folded into a multi-message
 * round's bar — which is what these tests pin down.
 */
class ChatFlatItemsToolOnlyFoldTest {

    private fun assistant(
        id: String = "m1",
        blocks: List<AssistantBlock>,
        role: String = "assistant",
    ) = ChatMessage(
        id = id,
        role = role,
        content = "",
        isStreaming = false,
        toolBlocks = blocks,
    )

    private fun user(id: String = "u1") = ChatMessage(id = id, role = "user", content = "hi")

    private fun tool(id: String, status: ToolBlockStatus = ToolBlockStatus.SUCCESS) = AssistantBlock(
        id = id,
        kind = "tool_use",
        content = "",
        toolName = "bash",
        toolTitle = "bash",
        toolStatus = status,
    )

    private fun build(vararg messages: ChatMessage) = buildFlatChatItems(
        messages.toList(),
        foldAiProcess = true,
    )

    private fun kinds(items: List<FlatChatItem>): List<String> = items.map { item ->
        when (item) {
            is FlatChatItem.UserBubble -> "user"
            is FlatChatItem.AssistantHeader -> "header"
            is FlatChatItem.AssistantText -> "text"
            is FlatChatItem.AssistantMarkdownBlock -> "md"
            is FlatChatItem.AssistantProcessSummary -> "summary"
            is FlatChatItem.AssistantThinking -> "thinking"
            is FlatChatItem.AssistantToolUse -> "tool:${item.block.toolName}"
            is FlatChatItem.AssistantInfo -> "info"
            is FlatChatItem.AssistantTyping -> "typing"
            is FlatChatItem.AssistantError -> "error"
            is FlatChatItem.AssistantLegacyContent -> "legacy"
        }
    }

    @Test
    fun `collapsed tool-only round renders summary without header`() {
        // a1's three tools all fold (the round's keep-2 tail lands on a2's
        // newest tools), so a1's header hides — the original blank-band repro
        // at round scope. a2 is a resume continuation and never had its own
        // header, so the round renders with zero headers.
        val items = build(
            user(),
            assistant(id = "a1", blocks = listOf(tool("t1"), tool("t2"), tool("t3"))),
            assistant(id = "a2", blocks = listOf(tool("t4"), tool("t5"), tool("t6"))),
        )
        val k = kinds(items)
        assertTrue("summary expected, got $k", k.contains("summary"))
        assertFalse("headers must be hidden, got $k", k.contains("header"))
        // keep-2: the round's newest two tools stay in the list.
        assertEquals(2, k.count { it.startsWith("tool:") })
    }

    @Test
    fun `expanded round restores header and tool cards`() {
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(tool("t1"), tool("t2"), tool("t3")))),
            foldAiProcess = true,
            expandedProcessIds = setOf("m1"),
        )
        val k = kinds(items)
        assertTrue("header expected, got $k", k.contains("header"))
        assertTrue("tool cards expected, got $k", k.any { it.startsWith("tool:") })
        assertTrue(k.contains("summary"))
    }

    @Test
    fun `turn with reply text keeps header when folded`() {
        val msg = assistant(
            blocks = listOf(
                tool("t1"),
                tool("t2"),
                tool("t3"),
                AssistantBlock(id = "x1", kind = "text", content = "done"),
            ),
        )
        val k = kinds(build(msg))
        assertTrue("header expected, got $k", k.contains("header"))
        assertTrue(k.contains("summary"))
    }

    @Test
    fun `failed tool still flags summary`() {
        val items = build(
            assistant(blocks = listOf(tool("t1"), tool("t2", ToolBlockStatus.FAILED), tool("t3"))),
        )
        val summary = items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().first()
        assertTrue(summary.hasFailure)
    }
}
