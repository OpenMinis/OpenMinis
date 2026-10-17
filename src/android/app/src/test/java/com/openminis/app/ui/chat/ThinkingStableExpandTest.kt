package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-thinking-stable-expand] Contract tests for the thinking
 * show/hide flicker fix ("排队消息后思考块显隐闪烁") and the thinking-phase
 * queued-prompt interrupt wiring.
 *
 * The flicker root cause: the auto-collapse signal for a thinking block was
 * "this block is the LAST block of any kind in the message" — in a
 * multi-round tool loop every round's boundary flipped it (new thinking /
 * tool_use block arrives → trailing status lost → collapse → next round's
 * thinking re-expands), so the thinking box visibly pulsed once per round.
 *
 * The fix replaces it with a per-phase signal: "the message is streaming AND
 * no readable reply text follows this block" (hasReplyTextAfter). That flips
 * exactly twice per reasoning phase — when the reply starts, and when the
 * stream ends — never per tool round.
 *
 * Part 1 pins hasReplyTextAfter's truth table (pure function).
 * Part 2 pins the production wiring with source guards: both renderers fold
 * the signal into their streaming flag, the 100ms content poll is gone, the
 * page-level follow tuple excludes thinking growth, and the
 * queued-prompt-interrupt cut sits in the right places with its dedicated
 * catch BEFORE the generic one.
 */
class ThinkingStableExpandTest {

    private fun thinking(id: String) = AssistantBlock(id = id, kind = "thinking")
    private fun text(content: String, id: String = "t") = AssistantBlock(id = id, kind = "text", content = content)
    private fun tool(id: String = "tool") = AssistantBlock(id = id, kind = "tool_use")

    // ── 1. hasReplyTextAfter truth table ───────────────────────────────

    @Test
    fun `no blocks after - reasoning phase still open`() {
        val blocks = listOf(thinking("th1"))
        assertFalse(hasReplyTextAfter(blocks, 0))
    }

    @Test
    fun `tool_use after thinking does not close the phase - multi-round loop`() {
        // Round 2's thinking follows the tool result; the reasoning phase of
        // THIS block is not over just because a tool call came next.
        val blocks = listOf(thinking("th1"), tool())
        assertFalse(hasReplyTextAfter(blocks, 0))
    }

    @Test
    fun `readable text after thinking closes the phase`() {
        val blocks = listOf(thinking("th1"), text("answer"))
        assertTrue(hasReplyTextAfter(blocks, 0))
    }

    @Test
    fun `blank text after thinking does not close the phase`() {
        // Models emit "\n\n" before a tool call — that is not reply text
        // (matches flushSegment's isNotBlank() contract).
        val blocks = listOf(thinking("th1"), text("\n\n"), tool())
        assertFalse(hasReplyTextAfter(blocks, 0))
    }

    @Test
    fun `text before the block does not count - only blocks after`() {
        val blocks = listOf(text("earlier"), thinking("th1"), tool())
        assertFalse(hasReplyTextAfter(blocks, 1))
    }

    @Test
    fun `second-round thinking after first reply is open again`() {
        // th1's phase closed (text follows it); th2 — the next round's
        // reasoning — has no text after it yet, so IT is still streaming.
        val blocks = listOf(thinking("th1"), text("partial"), tool(), thinking("th2"))
        assertTrue(hasReplyTextAfter(blocks, 0))
        assertFalse(hasReplyTextAfter(blocks, 3))
    }

    @Test
    fun `blank-only text blocks never close a phase`() {
        val blocks = listOf(
            thinking("th1"),
            text("  \n"),
            thinking("th2"),
            text("\n\t"),
            tool(),
        )
        for (i in blocks.indices) {
            if (blocks[i].kind == "thinking") {
                assertFalse("idx=$i must be open", hasReplyTextAfter(blocks, i))
            }
        }
    }

    // ── 2. production wiring guards ────────────────────────────────────

    @Test
    fun `flat item builder folds the phase signal into messageIsStreaming`() {
        val src = ProductionSources.read("ui/chat/ChatFlatItems.kt")
        assertTrue(
            "builder must use message.isStreaming && !hasReplyTextAfter(blocks, index)",
            "messageIsStreaming = message.isStreaming && !hasReplyTextAfter(blocks, index)," in src,
        )
        assertFalse(
            "the per-round trailing-block signal must be gone (it caused the flicker)",
            "isLastBlockOverall" in src,
        )
    }

    @Test
    fun `legacy renderer folds the same phase signal`() {
        val src = ProductionSources.read("ui/chat/ChatAssistantMessageUI.kt")
        assertTrue(
            "AssistantMessageView must use the same complete signal as the flat path",
            "isStreaming = message.isStreaming && !hasReplyTextAfter(message.toolBlocks, index)," in src,
        )
    }

    @Test
    fun `thinking content poll is gone - batcher paces recomposition`() {
        val src = ProductionSources.read("ui/chat/ChatAssistantMessageUI.kt")
        assertFalse(
            "rememberUpdatedState poll loop removed - it only added lag on top of the batcher",
            "rememberUpdatedState" in src,
        )
        assertTrue(
            "ThinkingBlock reads block.content directly",
            "val renderedContent = block.content" in src,
        )
    }

    @Test
    fun `chat screen follow tuple excludes thinking growth`() {
        val src = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(
            "growth must skip thinking content length (page must not scroll per token)",
            "val isThinking = b.kind == \"thinking\"" in src &&
                "if (!isThinking) growth += b.content.length.toLong()" in src,
        )
        assertTrue(
            "thinking block signature must skip the content-length term too",
            "if (!isThinking) {\n                    blockSig = blockSig xor b.content.length.toLong()" in src,
        )
        assertTrue(
            "renderer consumes the folded signal, not a trailing-block flag",
            "isStreaming = item.messageIsStreaming," in src,
        )
    }

    @Test
    fun `queued prompt interrupt cut exists in both thinking branches`() {
        val loopSrc = ProductionSources.read("ui/chat/ChatViewModelAgentLoopExt.kt")
        val guardSrc = ProductionSources.read("ui/chat/ChatViewModelQueueInjectExt.kt")
        // The guard itself is a shared pure predicate (single definition in
        // QueueInjectExt); both thinking branches in the collector call it.
        assertEquals(
            "the shared guard must be defined exactly once",
            1,
            Regex(Regex.escape("toolCalls.isEmpty() && turnTextSb.isEmpty() && _promptQueue.value.isNotEmpty()")).findAll(guardSrc).count(),
        )
        assertEquals(
            "the cut must sit in exactly the ThinkingDelta and ReasoningContent branches",
            2,
            Regex("hasQueuedPromptDuringPureThinking\\(toolCalls, turnTextSb\\)").findAll(loopSrc).count(),
        )
        assertEquals(
            "one throw site: both branches funnel through cutForQueuedPrompt",
            1,
            Regex("throw QueuedPromptInterrupt\\(\\)").findAll(loopSrc).count(),
        )
    }

    @Test
    fun `dedicated interrupt catch sits before the generic exception catch`() {
        val src = ProductionSources.read("ui/chat/ChatViewModelAgentLoopExt.kt")
        val dedicated = src.indexOf("catch (e: QueuedPromptInterrupt)")
        val generic = src.indexOf("catch (e: Exception)")
        assertTrue("dedicated catch must exist", dedicated >= 0)
        assertTrue("generic catch must exist", generic >= 0)
        assertTrue(
            "QueuedPromptInterrupt must be caught BEFORE Exception, or the retry/" +
                "fallback machinery would 'retry' a turn the user just re-steered",
            dedicated < generic,
        )
    }

    @Test
    fun `post-loop injection swaps loop state to the new bubble`() {
        val src = ProductionSources.read("ui/chat/ChatViewModelAgentLoopExt.kt")
        assertTrue(
            "the queuedPromptInterrupted block must run after the stream loop",
            "if (queuedPromptInterrupted) {" in src,
        )
        assertEquals(
            "two swap sites (tool-close boundary + thinking-phase cut) share the extracted swapLoopStateToInjectedBubble",
            2,
            Regex("swapLoopStateToInjectedBubble\\(handled\\)").findAll(src).count(),
        )
    }

    @Test
    fun `inject path seals the retired bubble in the batcher`() {
        val src = ProductionSources.read("ui/chat/ChatViewModelQueueInjectExt.kt")
        assertTrue(
            "injectQueuedPromptsAsNewTurn must seal the finished id before finalizing on Main",
            "uiBatcher.seal(finishedAssistantId)" in src,
        )
    }

    @Test
    fun `interrupt signal carries no stack trace`() {
        val src = ProductionSources.read("ui/chat/QueuedPromptInterrupt.kt")
        assertTrue(
            "thrown at token cadence - must not capture stack frames",
            "override fun fillInStackTrace(): Throwable = this" in src,
        )
    }
}
