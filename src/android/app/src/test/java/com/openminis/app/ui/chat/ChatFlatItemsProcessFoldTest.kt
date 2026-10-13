package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-round-collapse] Fold contracts under the round model.
 *
 * A round = one user message + the assistant/system messages after it up to
 * the next user message. Fold rules:
 *  1. rounds with ≤ 2 foldable steps never fold (a bar for two rows is noise);
 *  2. a RUNNING round is always fully open (effectiveExpanded =
 *     isRunning || expanded) — completion auto-collapses with no trigger;
 *  3. a collapsed round keeps its LATEST 2 steps visible;
 *  4. one bar per round, owned by the round's LAST assistant message
 *     (messageId == that message's id, which is also the expansion key).
 */
class ChatFlatItemsProcessFoldTest {

    private fun thinking(id: String = "th1") =
        AssistantBlock(id = id, kind = "thinking", content = "reason")

    private fun text(id: String = "tx1", content: String = "hello") =
        AssistantBlock(id = id, kind = "text", content = content)

    private fun tool(
        id: String = "tool1",
        name: String = "bash",
        status: ToolBlockStatus = ToolBlockStatus.SUCCESS,
    ) = AssistantBlock(
        id = id,
        kind = "tool_use",
        toolName = name,
        toolStatus = status,
        toolTitle = name,
    )

    private fun assistant(
        streaming: Boolean = false,
        awaiting: Boolean = false,
        blocks: List<AssistantBlock>,
        role: String = "assistant",
        id: String = "m1",
    ) = ChatMessage(
        id = id,
        role = role,
        content = "",
        isStreaming = streaming,
        isAwaitingModelResponse = awaiting,
        toolBlocks = blocks,
    )

    private fun user(id: String = "u1") = ChatMessage(id = id, role = "user", content = "hi")

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
    fun `fold off keeps thinking tools and text with no summary`() {
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(thinking(), tool(), text()))),
            showCompletedToolCards = true,
            foldAiProcess = false,
        )
        assertFalse(kinds(items).contains("summary"))
        assertTrue(kinds(items).contains("thinking"))
        assertTrue(kinds(items).contains("tool:bash"))
        assertTrue(kinds(items).contains("md"))
    }

    @Test
    fun `round with at most two foldable steps never folds`() {
        // Rule 1: a fold bar summarising two rows is noise — the round stays
        // open even with folding enabled.
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(thinking(), tool(), text()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val k = kinds(items)
        assertFalse(k.contains("summary"))
        assertTrue(k.contains("thinking"))
        assertTrue(k.contains("tool:bash"))
        assertTrue(k.contains("md"))
    }

    @Test
    fun `fold on finished round folds oldest steps and keeps latest two`() {
        // 3 foldable steps: thinking folds, the two newest tools stay (rule 3).
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    blocks = listOf(
                        thinking(),
                        tool(id = "t1"),
                        tool(id = "t2"),
                        text(id = "tx1", content = "one"),
                        text(id = "tx2", content = "two"),
                    ),
                ),
            ),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val k = kinds(items)
        assertEquals(listOf("header", "tool:bash", "tool:bash", "md", "md", "summary"), k)
        val summary = items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single()
        assertEquals(1, summary.thinkingCount)
        assertEquals(2, summary.toolCount)
        assertFalse(summary.expanded)
        assertFalse(summary.hasFailure)
        val blocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertFalse(blocks[0].showTranslate)
        assertTrue(blocks[1].showTranslate)
        assertTrue(blocks[1].translateWholeReply)
        assertEquals("one\n\ntwo", blocks[1].segmentText)
    }

    @Test
    fun `fold off translates each text block instead of the whole reply`() {
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    blocks = listOf(
                        text(id = "tx1", content = "one"),
                        text(id = "tx2", content = "two"),
                    ),
                ),
            ),
            showCompletedToolCards = true,
            foldAiProcess = false,
        )
        val blocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertEquals(2, blocks.size)
        assertTrue(blocks[0].showTranslate)
        assertFalse(blocks[0].translateWholeReply)
        assertEquals("one", blocks[0].segmentText)
        assertTrue(blocks[1].showTranslate)
        assertEquals("two", blocks[1].segmentText)
    }

    @Test
    fun `fold on live thinking stays expanded until a later block arrives`() {
        val items = buildFlatChatItems(
            listOf(assistant(streaming = true, blocks = listOf(thinking()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val k = kinds(items)
        assertFalse(k.contains("summary"))
        assertTrue(k.contains("thinking"))
    }

    @Test
    fun `fold on streaming keeps the whole running round open`() {
        // Rule 2: a running round is fully open — completed thinking stays
        // visible alongside the in-flight tool until the round finishes.
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    streaming = true,
                    blocks = listOf(thinking(), tool(status = ToolBlockStatus.RUNNING), text()),
                ),
            ),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val k = kinds(items)
        assertFalse(k.contains("summary"))
        assertTrue(k.contains("thinking"))
        assertTrue(k.contains("tool:bash"))
        assertTrue(k.contains("md"))
    }

    @Test
    fun `running round stays open and folds when it finishes`() {
        // The auto-collapse needs no trigger of its own: the round simply
        // stops forcing itself open the moment it stops running.
        val blocks = listOf(thinking(), tool(id = "t1"), tool(id = "t2"), text())
        val running = buildFlatChatItems(
            listOf(assistant(streaming = true, blocks = blocks)),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val rk = kinds(running)
        assertFalse(rk.contains("summary"))
        assertTrue(rk.contains("thinking"))
        assertTrue(rk.contains("tool:bash"))

        val finished = buildFlatChatItems(
            listOf(assistant(streaming = false, blocks = blocks)),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val fk = kinds(finished)
        assertTrue(fk.contains("summary"))
        assertFalse(fk.contains("thinking"))
    }

    @Test
    fun `fold on awaiting keeps the running round open`() {
        // Awaiting (between tool rounds) is still a running round.
        val items = buildFlatChatItems(
            listOf(assistant(awaiting = true, blocks = listOf(thinking(), tool(), text()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val k = kinds(items)
        assertFalse(k.contains("summary"))
        assertTrue(k.contains("thinking"))
        assertTrue(k.contains("tool:bash"))
        assertTrue(k.contains("md"))
    }

    @Test
    fun `tap expand restores thinking and completed tools`() {
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(thinking(), tool(id = "t1"), tool(id = "t2"), text()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
            expandedProcessIds = setOf("m1"),
        )
        val k = kinds(items)
        assertTrue(k.contains("summary"))
        assertTrue(k.contains("thinking"))
        assertTrue(k.contains("tool:bash"))
        assertTrue(k.contains("md"))
        assertTrue(items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single().expanded)
    }

    @Test
    fun `ask_user_question stays visible while process is folded`() {
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    blocks = listOf(
                        thinking(),
                        tool(id = "q", name = "ask_user_question"),
                        tool(id = "t1"),
                        tool(id = "t2"),
                        text(),
                    ),
                ),
            ),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val k = kinds(items)
        assertTrue(k.contains("summary"))
        // ask_user_question is always visible and never counts as a foldable
        // step; the two newest bash tools are the kept tail.
        assertTrue(k.contains("tool:ask_user_question"))
        assertFalse(k.contains("thinking"))
        assertTrue(k.contains("md"))
    }

    @Test
    fun `system info rows are not folded`() {
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    role = "system",
                    blocks = listOf(AssistantBlock(id = "i1", kind = "info", content = "note")),
                ),
            ),
            foldAiProcess = true,
        )
        val k = kinds(items)
        assertFalse(k.contains("summary"))
        assertTrue(k.contains("info"))
    }

    @Test
    fun `failed tool is flagged on the summary`() {
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(thinking(), tool(id = "t1"), tool(status = ToolBlockStatus.FAILED)))),
            foldAiProcess = true,
        )
        val summary = items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single()
        assertTrue(summary.hasFailure)
        assertEquals(2, summary.toolCount)
        assertEquals(1, summary.thinkingCount)
    }

    // ── Round model ──────────────────────────────────────────────────────

    @Test
    fun `multi-message round folds into one summary with round totals`() {
        val items = buildFlatChatItems(
            listOf(
                user(),
                assistant(id = "a1", blocks = listOf(thinking(), tool(id = "a1t1"))),
                assistant(id = "a2", blocks = listOf(tool(id = "a2t1"), tool(id = "a2t2"), text())),
            ),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val summaries = items.filterIsInstance<FlatChatItem.AssistantProcessSummary>()
        assertEquals(1, summaries.size)
        val summary = summaries.single()
        // Rule 4: the bar is owned by the round's LAST assistant message.
        assertEquals("a2", summary.messageId)
        assertEquals(1, summary.thinkingCount)
        assertEquals(3, summary.toolCount)
        // Keep-2 lands on the round's latest steps (a2t1, a2t2); a1's
        // thinking/tool fold, and a1's header hides with no visible payload
        // left (a2 is a resume continuation, so it never had its own header).
        val k = kinds(items)
        assertFalse(k.contains("thinking"))
        assertEquals(2, k.count { it == "tool:bash" })
        assertTrue(k.contains("md"))
        assertEquals(0, k.count { it == "header" })
    }

    @Test
    fun `resume continuation joins the previous round`() {
        // No user message between a1 and a2 → same round, one bar, round-wide
        // totals — the old per-message model sprouted one bar per message.
        val items = buildFlatChatItems(
            listOf(
                user(),
                assistant(id = "a1", blocks = listOf(thinking(), tool(id = "a1t1"), text(id = "tx1", content = "part"))),
                assistant(id = "a2", blocks = listOf(tool(id = "a2t1"), tool(id = "a2t2"), text(id = "tx2", content = "done"))),
            ),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val summaries = items.filterIsInstance<FlatChatItem.AssistantProcessSummary>()
        assertEquals(1, summaries.size)
        assertEquals("a2", summaries.single().messageId)
        assertEquals(3, summaries.single().toolCount)
        assertEquals(1, summaries.single().thinkingCount)
    }

    @Test
    fun `expanding a multi-message round uses the last assistant message id`() {
        val items = buildFlatChatItems(
            listOf(
                user(),
                assistant(id = "a1", blocks = listOf(thinking(), tool(id = "a1t1"))),
                assistant(id = "a2", blocks = listOf(tool(id = "a2t1"), tool(id = "a2t2"), text())),
            ),
            showCompletedToolCards = false,
            foldAiProcess = true,
            expandedProcessIds = setOf("a2"),
        )
        val k = kinds(items)
        assertTrue(k.contains("thinking"))
        assertTrue(k.contains("summary"))
        assertTrue(items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single().expanded)
    }

    // ── Overlay (single channel) ─────────────────────────────────────────

    @Test
    fun `floating overlay is fold-off only`() {
        val done = tool(status = ToolBlockStatus.SUCCESS)
        val running = tool(id = "t2", status = ToolBlockStatus.RUNNING)
        assertTrue(isFloatingProcessTool(done, foldAiProcess = false))
        assertFalse(isFloatingProcessTool(done, foldAiProcess = true))
        // [T-android-round-collapse] Single channel: a running round renders
        // every step in-list, so nothing floats when folding is on.
        assertFalse(isFloatingProcessTool(running, foldAiProcess = true))
        assertFalse(isFloatingProcessTool(thinking(), foldAiProcess = false))
    }

    @Test
    fun `folded summary still exposes tool chips for detail`() {
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(thinking(), tool(id = "t1"), tool(id = "t2"), text()))),
            foldAiProcess = true,
        )
        val summary = items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single()
        assertEquals(2, summary.processTools.size)
        assertEquals(listOf("t1", "t2"), summary.processTools.map { it.id })
        assertEquals(listOf("bash", "bash"), summary.processTools.map { it.title })
    }

    @Test
    fun `detail sheet helper keeps completed tools when fold hides overlay`() {
        val done = tool(id = "done1", status = ToolBlockStatus.SUCCESS)
        val running = tool(id = "run1", status = ToolBlockStatus.RUNNING)
        val msgs = listOf(assistant(blocks = listOf(thinking(), done, running, text())))
        val all = assistantToolUseBlocks(msgs)
        assertEquals(listOf("done1", "run1"), all.map { it.id })
        assertTrue(isDetailProcessTool(done))
        assertFalse(isFloatingProcessTool(done, foldAiProcess = true))
        assertFalse(isFloatingProcessTool(running, foldAiProcess = true))
    }

    @Test
    fun `expanding restores thinking and tool rows at their own positions`() {
        val blocks = listOf(
            thinking(),
            tool(id = "tool1", name = "bash", status = ToolBlockStatus.SUCCESS),
            tool(id = "tool2", name = "bash", status = ToolBlockStatus.SUCCESS),
            text(),
        )
        val expanded = buildFlatChatItems(
            listOf(assistant(blocks = blocks)),
            showCompletedToolCards = false,
            foldAiProcess = true,
            expandedProcessIds = setOf("m1"),
        )
        val k = kinds(expanded)

        assertTrue("thinking must come back", k.contains("thinking"))
        assertTrue("completed tools must come back", k.contains("tool:bash"))
        assertTrue("fold bar stays", k.contains("summary"))

        // Position correspondence: out is oldest-first, so within the turn the
        // chronological order must be thinking -> tools -> reply text, with the
        // fold bar appended last (visually below the reply under reverseLayout).
        assertEquals(
            listOf("header", "thinking", "tool:bash", "tool:bash", "md", "summary"),
            k.filter { it in setOf("header", "thinking", "tool:bash", "md", "summary") },
        )
    }

    @Test
    fun `collapsed fold keeps only the latest two steps in the list`() {
        // 4 foldable steps: thinking + t1 fold, t2 + t3 stay as the tail.
        val k = kinds(
            buildFlatChatItems(
                listOf(assistant(blocks = listOf(thinking(), tool(id = "t1"), tool(id = "t2"), tool(id = "t3"), text()))),
                showCompletedToolCards = false,
                foldAiProcess = true,
            ),
        )
        assertFalse(k.contains("thinking"))
        assertEquals(2, k.count { it == "tool:bash" })
        assertTrue(k.contains("summary"))
        assertTrue(k.contains("md"))
    }

    @Test
    fun `a running tool stays in the list at its position`() {
        // The card the user is watching must not teleport: a running round is
        // fully open, so the in-list row is the only copy and holds its place
        // through RUNNING -> SUCCESS.
        val blocks = listOf(thinking(), tool(id = "t2", status = ToolBlockStatus.RUNNING), text())
        val running = kinds(
            buildFlatChatItems(
                listOf(assistant(streaming = true, blocks = blocks)),
                showCompletedToolCards = false,
                foldAiProcess = true,
            ),
        )
        assertTrue("running tool must be in the list", running.contains("tool:bash"))
    }

    // ── [T-process-summary-duration] aggregated fold-bar duration ──

    @Test
    fun `folded summary sums block durations`() {
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    blocks = listOf(
                        thinking("t1"),
                        tool("a").copy(durationMs = 1500L),
                        tool("b").copy(durationMs = 3200L),
                        text(id = "tx1", content = "done"),
                    ),
                ),
            ),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val summary = items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single()
        assertEquals(4700L, summary.totalMs)
    }

    @Test
    fun `summary total is zero when no block recorded a duration`() {
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(thinking(), tool(id = "t1"), tool(id = "t2"), text()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val summary = items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single()
        assertEquals(0L, summary.totalMs)
        assertNull(formatProcessDuration(summary.totalMs))
    }

    @Test
    fun `process duration formats as seconds minutes and hours`() {
        assertNull(formatProcessDuration(0L))
        assertNull(formatProcessDuration(-5L))
        assertEquals("47s", formatProcessDuration(47_000L))
        assertEquals("1m 23s", formatProcessDuration(83_000L))
        assertEquals("1h 5m", formatProcessDuration(3_900_000L))
        // 59.9s truncates to whole seconds, never rounds up to "60s"
        assertEquals("59s", formatProcessDuration(59_900L))
    }
}
