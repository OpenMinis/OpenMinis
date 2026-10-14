package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P-process-card] Fold contracts under the process-card model.
 *
 * A round = one user message + the assistant/system messages after it up to
 * the next user message. Inside a round, reply text splits the process into
 * runs, and each run of ≥ 2 steps (thinking or tool, including a tool that
 * is still running) becomes one card. A single step stays inline.
 *
 * Cards own their steps: collapsed or expanded, the steps render inside the
 * card, never back in the list. The expansion key is the card's own key
 * (`<round key>--<first step id>`), not the message id, so opening one card
 * never opens another. A running card reports the live step; reply text ends
 * the card immediately.
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
    fun `blank text between thinking and tool does not split the run`() {
        // [P-process-card] Regression: providers emit a bare "\n\n" before a
        // tool call; restored sessions turn it into a text block. The old
        // isNotEmpty() check treated it as reply text, flushed the segment
        // while it held only the thinking block (below the 2-step card
        // threshold) and dropped it — every card read "思考 0". Blank text is
        // not reply text and must not close a card.
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    blocks = listOf(
                        thinking(id = "th1"),
                        text(id = "tx1", content = "\n\n"),
                        tool(id = "t1"),
                        thinking(id = "th2"),
                        text(id = "tx2", content = " \n "),
                        tool(id = "t2"),
                        text(id = "tx3", content = "done"),
                    ),
                ),
            ),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val summary = items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single()
        assertEquals(2, summary.thinkingCount)
        assertEquals(2, summary.toolCount)
    }

    @Test
    fun `a two step run becomes a card and a single step stays inline`() {
        // [P-process-card] The card threshold is the RUN, not the round:
        // two process steps are enough to card, one step is noise and stays
        // inline. The old round rule (≤ 2 steps never fold) predates cards.
        val carded = buildFlatChatItems(
            listOf(assistant(blocks = listOf(thinking(), tool(), text()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val k = kinds(carded)
        assertEquals(listOf("header", "summary", "md"), k.filter { it in setOf("header", "summary", "md", "thinking", "tool:bash") })
        assertFalse(k.contains("thinking"))
        assertFalse(k.contains("tool:bash"))

        val inline = buildFlatChatItems(
            listOf(assistant(blocks = listOf(thinking(), text()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val ik = kinds(inline)
        assertFalse(ik.contains("summary"))
        assertTrue(ik.contains("thinking"))
        assertTrue(ik.contains("md"))
    }

    @Test
    fun `fold on finished round folds the whole process run into one card`() {
        // [P-process-card] A run of ≥ 2 steps becomes one card placed before
        // the reply text; no step stays in the list.
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
        // Card sits before the reply text it precedes.
        assertEquals(listOf("header", "summary", "md", "md"), k)
        val summary = items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single()
        assertEquals(1, summary.thinkingCount)
        assertEquals(2, summary.toolCount)
        assertFalse(summary.running)
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
        // One step is below the card threshold, so it stays inline.
        assertFalse(k.contains("summary"))
        assertTrue(k.contains("thinking"))
    }

    @Test
    fun `reply text settles the card while the message keeps streaming`() {
        // The card model drops "输出中": the run's reply text IS its end, so
        // the card reports settled even mid-stream and its steps stay inside.
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
        // Reply text ends the card the moment it arrives, even while the
        // message is still streaming — "输出中" was dropped because it would
        // flash for one frame. The steps render inside the card.
        assertEquals(listOf("header", "summary", "md"), k.filter { it in setOf("header", "summary", "thinking", "tool:bash", "md") })
        val summary = items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single()
        assertFalse(summary.running)
    }

    @Test
    fun `running card settles when the reply text arrives`() {
        // A run with a live step is a live card: it auto-expands, the header
        // names what is happening, the duration ticks. Its reply text ends
        // the run — the card settles there and its header shows the
        // settled counts.
        val live = buildFlatChatItems(
            listOf(assistant(streaming = true, blocks = listOf(thinking(), tool(id = "t1"), tool(id = "t2", status = ToolBlockStatus.RUNNING)))),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val liveRows = kinds(live)
        // Everything the run owns is in the card; no reply text yet, so the
        // turn has nothing left to earn a header.
        assertEquals("live rows: $liveRows", listOf("summary"), liveRows.filter { it in setOf("header", "summary", "thinking", "tool:bash") })
        assertTrue(live.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single().running)

        val finished = buildFlatChatItems(
            listOf(assistant(streaming = false, blocks = listOf(thinking(), tool(id = "t1"), tool(id = "t2"), text()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val finishedRows = kinds(finished)
        assertEquals("settled rows: $finishedRows", listOf("header", "summary", "md"), finishedRows.filter { it in setOf("header", "summary", "thinking", "tool:bash", "md") })
        assertFalse(finished.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single().running)
    }

    @Test
    fun `awaiting keeps the run live until its reply text settles it`() {
        // Awaiting (between tool rounds) is a running round: with no reply
        // text yet the run can still take steps, so the card is live.
        val live = buildFlatChatItems(
            listOf(assistant(awaiting = true, blocks = listOf(thinking(), tool()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val liveRows = kinds(live)
        assertEquals("live rows: $liveRows", listOf("summary"), liveRows.filter { it in setOf("header", "summary", "thinking", "tool:bash") })
        assertTrue(live.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single().running)

        // The reply text ends the run and the card header shows the
        // settled counts.
        val settled = buildFlatChatItems(
            listOf(assistant(awaiting = true, blocks = listOf(thinking(), tool(), text()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val settledRows = kinds(settled)
        assertEquals("settled rows: $settledRows", listOf("header", "summary", "md"), settledRows.filter { it in setOf("header", "summary", "thinking", "tool:bash", "md") })
        assertFalse(settled.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single().running)
    }

    @Test
    fun `tap expand restores thinking and completed tools`() {
        // The toggle stores the card's own key (round key + first step),
        // never the bare message id — one message can hold several cards.
        val collapsed = buildFlatChatItems(
            listOf(assistant(blocks = listOf(thinking(), tool(id = "t1"), tool(id = "t2"), text()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
        ).filterIsInstance<FlatChatItem.AssistantProcessSummary>().single()
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(thinking(), tool(id = "t1"), tool(id = "t2"), text()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
            expandedProcessIds = setOf(collapsed.messageId),
        )
        val k = kinds(items)
        // Expanding renders the steps INSIDE the card, never back in the list.
        assertTrue(k.contains("summary"))
        assertFalse(k.contains("thinking"))
        assertFalse(k.contains("tool:bash"))
        assertTrue(k.contains("md"))
        val summary = items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single()
        assertTrue(summary.expanded)
        assertEquals(listOf("thinking", "tool_use", "tool_use"), summary.steps.map { it.kind })
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
        // step, so it stays in the list while the run folds into the card.
        assertTrue(k.contains("tool:ask_user_question"))
        assertFalse(k.contains("thinking"))
        assertFalse(k.contains("tool:bash"))
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
    fun `multi-message round folds into one card spanning both messages`() {
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
        // One process run: a1's thinking+tool and a2's two tools are
        // contiguous, and the reply text in a2 ends the run.
        assertEquals("cards=${summaries.size} rows=${kinds(items)}", 1, summaries.size)
        val summary = summaries.single()
        assertEquals(1, summary.thinkingCount)
        assertEquals(3, summary.toolCount)
        val k = kinds(items)
        assertFalse(k.contains("thinking"))
        assertFalse(k.any { it.startsWith("tool:") })
        assertTrue(k.contains("md"))
        // a1 has nothing left to show once its steps folded, so its header
        // hides; a2 is a resume continuation and never had one.
        assertEquals(0, k.count { it == "header" })
    }

    @Test
    fun `reply text splits a round into one card per process run`() {
        // a1 ends with reply text, so its run closes there; a2's tools are a
        // second run closed by its own reply. Two cards, not one.
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
        assertEquals(2, summaries.size)
        // First run: a1's thinking + one tool, closed by "part".
        assertEquals(1, summaries[0].thinkingCount)
        assertEquals(1, summaries[0].toolCount)
        // Second run: a2's two tools, closed by "done".
        assertEquals(0, summaries[1].thinkingCount)
        assertEquals(2, summaries[1].toolCount)
    }

    @Test
    fun `expanding a card uses the card's own key`() {
        val items = buildFlatChatItems(
            listOf(
                user(),
                assistant(id = "a1", blocks = listOf(thinking(), tool(id = "a1t1"))),
                assistant(id = "a2", blocks = listOf(tool(id = "a2t1"), tool(id = "a2t2"), text())),
            ),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val collapsedCards = items.filterIsInstance<FlatChatItem.AssistantProcessSummary>()
        assertEquals("cards=${collapsedCards.size} rows=${kinds(items)}", 1, collapsedCards.size)
        val collapsed = collapsedCards[0]
        assertFalse(collapsed.expanded)
        // The card's key is what the toggle stores, so expanding one card
        // never expands another.
        val opened = buildFlatChatItems(
            listOf(
                user(),
                assistant(id = "a1", blocks = listOf(thinking(), tool(id = "a1t1"))),
                assistant(id = "a2", blocks = listOf(tool(id = "a2t1"), tool(id = "a2t2"), text())),
            ),
            showCompletedToolCards = false,
            foldAiProcess = true,
            expandedProcessIds = setOf(collapsed.messageId),
        )
        val summary = opened.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single()
        assertTrue(summary.expanded)
        // Steps stay inside the card either way.
        assertFalse(kinds(opened).contains("thinking"))
        assertEquals(3, summary.steps.count { it.kind == "tool_use" })
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
        val cardKey = buildFlatChatItems(
            listOf(assistant(blocks = blocks)),
            showCompletedToolCards = false,
            foldAiProcess = true,
        ).filterIsInstance<FlatChatItem.AssistantProcessSummary>().single().messageId
        val expanded = buildFlatChatItems(
            listOf(assistant(blocks = blocks)),
            showCompletedToolCards = false,
            foldAiProcess = true,
            expandedProcessIds = setOf(cardKey),
        )
        val k = kinds(expanded)
        val summary = expanded.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single()

        // Expanding never puts steps back in the list; they live in the card.
        assertFalse(k.contains("thinking"))
        assertFalse(k.contains("tool:bash"))
        assertTrue(k.contains("summary"))
        assertTrue(summary.expanded)
        assertEquals(listOf("thinking", "tool_use", "tool_use"), summary.steps.map { it.kind })

        // The card precedes the reply it summarises.
        assertEquals(
            listOf("header", "summary", "md"),
            k.filter { it in setOf("header", "summary", "md") },
        )
    }

    @Test
    fun `collapsed fold keeps no steps in the list`() {
        // Every step of a run of ≥ 2 goes into the card.
        val k = kinds(
            buildFlatChatItems(
                listOf(assistant(blocks = listOf(thinking(), tool(id = "t1"), tool(id = "t2"), tool(id = "t3"), text()))),
                showCompletedToolCards = false,
                foldAiProcess = true,
            ),
        )
        assertFalse(k.contains("thinking"))
        assertEquals(0, k.count { it == "tool:bash" })
        assertTrue(k.contains("summary"))
        assertTrue(k.contains("md"))
    }

    @Test
    fun `a running tool is reported by the card instead of the list`() {
        // The card is the only copy: a running tool renders inside it and the
        // header names it, so nothing teleports when it finishes. The run has
        // no reply text yet — text would end the card (see the settle tests).
        val blocks = listOf(thinking(), tool(id = "t2", status = ToolBlockStatus.RUNNING))
        val running = buildFlatChatItems(
            listOf(assistant(streaming = true, blocks = blocks)),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val k = kinds(running)
        assertFalse("running tool must not duplicate in the list", k.contains("tool:bash"))
        val summary = running.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single()
        assertTrue("card must be live while one of its steps runs", summary.running)
        assertEquals("tool", summary.activity)
        assertTrue(summary.steps.any { it.id == "t2" && it.running })
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
    fun `card header switches between live collapsed live expanded and settled`() {
        // Running, collapsed: fixed label — the live state belongs to the
        // expanded header, not the collapsed one.
        assertEquals(
            "AI 过程 · 思考 2 · 工具 3 · 12s",
            processCardHeader(2, 3, running = true, expanded = false, activity = "thinking", activityTitle = "", duration = "12s"),
        )
        assertEquals(
            "AI 过程 · 思考 1 · 工具 4 · 8s",
            processCardHeader(1, 4, running = true, expanded = false, activity = "tool", activityTitle = "搜索代码", duration = "8s"),
        )
        // Running, expanded: the steps are on screen, so only the state remains.
        assertEquals(
            "思考中",
            processCardHeader(2, 3, running = true, expanded = true, activity = "thinking", activityTitle = "", duration = "12s"),
        )
        // Settled (reply text arrived, or the round ended): the check mark.
        assertEquals(
            "思考 2 · 工具 3 · 47s",
            processCardHeader(2, 3, running = false, expanded = false, activity = "", activityTitle = "", duration = "47s"),
        )
        // No recorded duration omits the suffix rather than printing "0s".
        assertEquals(
            "思考 1 · 工具 2",
            processCardHeader(1, 2, running = false, expanded = false, activity = "", activityTitle = "", duration = null),
        )
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
