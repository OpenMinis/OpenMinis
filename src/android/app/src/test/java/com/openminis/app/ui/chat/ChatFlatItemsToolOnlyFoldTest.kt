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
 * [P-process-card] A tool-only run of ≥ 2 steps is one card and the message
 * has nothing left to render outside it, so the header hides. Expanding the
 * card keeps the steps inside the card — the header stays hidden. That is
 * the path these tests pin down.
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
        // Both messages' tools are one contiguous run with no reply text, so
        // the whole round is one card and neither message has a header left.
        val items = build(
            user(),
            assistant(id = "a1", blocks = listOf(tool("t1"), tool("t2"), tool("t3"))),
            assistant(id = "a2", blocks = listOf(tool("t4"), tool("t5"), tool("t6"))),
        )
        val k = kinds(items)
        assertEquals("rows: $k", listOf("user", "summary"), k.filter { it in setOf("user", "header", "summary") })
        assertFalse("headers must be hidden, got $k", k.contains("header"))
        // Every tool of the run is inside the card.
        assertEquals(0, k.count { it.startsWith("tool:") })
        assertEquals(6, items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single().toolCount)
    }

    @Test
    fun `expanded round restores header and tool cards`() {
        val cardKey = buildFlatChatItems(
            listOf(assistant(blocks = listOf(tool("t1"), tool("t2"), tool("t3")))),
            foldAiProcess = true,
        ).filterIsInstance<FlatChatItem.AssistantProcessSummary>().single().messageId
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(tool("t1"), tool("t2"), tool("t3")))),
            foldAiProcess = true,
            expandedProcessIds = setOf(cardKey),
        )
        val k = kinds(items)
        // Expanding keeps the steps inside the card, so the header stays
        // hidden — the message still has nothing to render outside the card.
        assertFalse("header stays hidden, got $k", k.contains("header"))
        assertFalse("tools stay inside the card, got $k", k.any { it.startsWith("tool:") })
        assertTrue(k.contains("summary"))
        assertTrue(items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single().expanded)
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
