package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T15] Compaction loop: transcript framing, split-and-merge, and old-task
 * bleed (9f4edcc7c, 655a8709d, b2353df64, 6732e39eb, 92370d9ab, cfa475937;
 * issues #275 #235).
 *
 * User report: the summary after /compact had nothing to do with the
 * conversation — a fast model "answered" the last user turn of the transcript
 * instead of summarising it (15-char summary), and after a compaction the
 * agent resumed an OLD task the summary described as a goal.
 *
 * `generateCompactSummaryWithSplitting`, `generateCompactSummary`'s framing
 * and `buildConversationTextForSummary` live on ChatViewModel; they are
 * ported verbatim here with the leaf LLM call injected, and the framing /
 * prompt wording is pinned by source grep. The real predicate
 * `ChatViewModel.shouldSplitOnError` and the real budget
 * `ChatViewModel.MAX_COMPACT_LLM_CALLS` are used, not copies.
 *
 * Divider placement after an in-loop compaction is already pinned by
 * CompactDividerPlacementTest (cfa475937) and is not duplicated here.
 */
// ── ports (file-level so the nested Splitter can share them) ───────────────

/** Port of buildConversationTextForSummary (ChatViewModel ~L4465). */
private fun buildConversationTextForSummary(history: List<LLMMessage>): String = buildString {
    for (msg in history) {
        val role = msg.role.name.lowercase()
        val text = msg.content.take(500)
        if (text.isNotEmpty()) append(role).append(": ").append(text).append('\n')
        for (part in msg.contentParts) {
            when (part) {
                is AgentContentPart.Text -> append(role).append(": ").append(part.text.take(500)).append('\n')
                is AgentContentPart.ToolUse -> append(role).append(" [tool:").append(part.name).append("]: ")
                    .append(part.input.toString().take(200)).append('\n')
                is AgentContentPart.ToolResult -> append(role).append(" [result:").append(part.name).append("]: ")
                    .append(part.content.take(500)).append('\n')
                is AgentContentPart.ImageData -> append(role).append(" [image: ").append(part.mimeType).append("]\n")
            }
        }
    }
}

private const val FRAME_HEAD = "Compact this conversation into a context summary:\n\n"
private const val FRAME_TAIL = "\n\n---\nEND OF CONVERSATION TO COMPACT.\n\n"
private const val FRAME_INSTRUCTION =
    "Now generate a structured context summary following the system prompt " +
        "instructions. Do NOT continue the conversation above — summarize it. " +
        "Write everything in past tense, framed as \"what was discussed / what " +
        "was done\", NOT as an ongoing goal or todo list."

/** Port of the user-message construction in generateCompactSummary (~L4593). */
private fun compactUserMessage(conversationText: String): String =
    FRAME_HEAD + conversationText + FRAME_TAIL + FRAME_INSTRUCTION

/**
 * Port of generateCompactSummaryWithSplitting (~L4506). [leaf] receives the
 * FRAMED user message (what the model sees) and returns the summary text. The
 * real `ChatViewModel.shouldSplitOnError` and `MAX_COMPACT_LLM_CALLS` are used.
 */
private class Splitter(private val leaf: (String) -> String) {
    val callsIssued = AtomicInteger(0)
    val leafInputs = mutableListOf<String>()

    fun summarize(messages: List<LLMMessage>, previousSummary: String? = null, depth: Int = 0): String {
        val transcript = buildConversationTextForSummary(messages)
        val conversationText = if (previousSummary.isNullOrBlank()) transcript
        else "Previous context summary:\n$previousSummary\n\nNew conversation to merge:\n$transcript"
        val spent = callsIssued.incrementAndGet()
        if (spent > ChatViewModel.MAX_COMPACT_LLM_CALLS) {
            throw IllegalStateException("compaction exceeded its budget of ${ChatViewModel.MAX_COMPACT_LLM_CALLS} model calls")
        }
        return try {
            val framed = compactUserMessage(conversationText)
            leafInputs.add(framed)
            leaf(framed)
        } catch (e: Exception) {
            if (!ChatViewModel.shouldSplitOnError(e) || messages.size < 2 || depth >= 3) throw e
            if (callsIssued.get() + 2 > ChatViewModel.MAX_COMPACT_LLM_CALLS) throw e
            val mid = messages.size / 2
            val s1 = summarize(messages.subList(0, mid).toList(), null, depth + 1)
            val s2 = summarize(messages.subList(mid, messages.size).toList(), null, depth + 1)
            s1 + "\n\n" + s2
        }
    }
}

class CompactLoopFramingTest {

    private fun u(t: String) = LLMMessage(LLMMessage.Role.USER, t)
    private fun a(t: String) = LLMMessage(LLMMessage.Role.ASSISTANT, t)

    /** A fake model that refuses anything over [maxChars] as an untyped provider error. */
    private fun sizeLimitedLeaf(maxChars: Int, onOk: (String) -> String): (String) -> String = { framed ->
        if (framed.length > maxChars) throw LLMError.ProviderError("[400] context_length_exceeded: input too long", httpStatus = 400)
        onOk(framed)
    }

    /** Names that only appear in the framed input's transcript body. */
    private fun topicsIn(framed: String): List<String> =
        listOf("TOPIC-ALPHA", "TOPIC-BETA", "TOPIC-GAMMA").filter { framed.contains(it) }

    // ── case 1: over threshold -> split -> merge ───────────────────────────

    @Test
    fun `an input the model refuses is halved, each half summarised, then joined`() {
        val history = (0 until 8).map { i -> if (i % 2 == 0) u("TOPIC-ALPHA q$i " + "x".repeat(400)) else a("TOPIC-ALPHA a$i " + "y".repeat(400)) }
        val whole = compactUserMessage(buildConversationTextForSummary(history)).length
        val s = Splitter(sizeLimitedLeaf(maxChars = whole - 1) { "SUM(" + topicsIn(it).joinToString("+") + ")" })
        val out = s.summarize(history)
        assertEquals("1 refused + 2 halves", 3, s.callsIssued.get())
        assertEquals("SUM(TOPIC-ALPHA)\n\nSUM(TOPIC-ALPHA)", out)
        assertEquals(3, s.leafInputs.size)
        assertTrue("halves are strictly smaller than the whole", s.leafInputs.drop(1).all { it.length < whole })
    }

    @Test
    fun `size-independent failures do not split and the call budget bounds the fan-out`() {
        val history = (0 until 8).map { u("m$it") }
        // 429: never split, one call, rethrown.
        val rl = Splitter { throw LLMError.RateLimited() }
        try { rl.summarize(history); assertTrue("must throw", false) } catch (e: LLMError.RateLimited) { }
        assertEquals(1, rl.callsIssued.get())
        // Always-failing provider error: splits, but never past the budget.
        val always = Splitter { throw LLMError.ProviderError("too long") }
        try { always.summarize(history); assertTrue("must throw", false) } catch (e: Exception) { }
        assertTrue("calls=${always.callsIssued.get()}", always.callsIssued.get() <= ChatViewModel.MAX_COMPACT_LLM_CALLS)
    }

    // ── case 2: framing markers ────────────────────────────────────────────

    @Test
    fun `framing markers wrap the transcript and never leak into the summary`() {
        val history = listOf(u("please build TOPIC-ALPHA"), a("done"))
        val transcript = buildConversationTextForSummary(history)
        assertFalse("transcript itself carries no frame", transcript.contains("END OF CONVERSATION TO COMPACT"))
        val framed = compactUserMessage(transcript)
        assertTrue(framed.startsWith(FRAME_HEAD))
        assertTrue(framed.contains(FRAME_TAIL))
        assertTrue(framed.endsWith(FRAME_INSTRUCTION))
        assertTrue("transcript sits between head and tail",
            framed.indexOf(transcript) > framed.indexOf(FRAME_HEAD) && framed.indexOf(transcript) < framed.indexOf(FRAME_TAIL))
        // The last user turn is NOT the last thing the model reads — the
        // instruction is, which is what stops "answering" it.
        assertTrue(framed.lastIndexOf("user: please build") < framed.lastIndexOf("Do NOT continue the conversation above"))

        // A leaf that echoes only what sits between the markers yields a summary free of them.
        val s = Splitter { f -> f.substringAfter(FRAME_HEAD).substringBefore(FRAME_TAIL).lines().first() }
        val summary = s.summarize(history)
        assertEquals("user: please build TOPIC-ALPHA", summary)
        assertFalse(summary.contains("END OF CONVERSATION"))
        assertFalse(summary.contains("Compact this conversation"))
    }

    // ── case 3: two topics -> both kept, no third, no bleed ────────────────

    @Test
    fun `two unrelated halves both survive the merge and neither half sees the other`() {
        val alpha = (0 until 4).map { i -> if (i % 2 == 0) u("TOPIC-ALPHA step$i " + "x".repeat(300)) else a("TOPIC-ALPHA ok$i " + "y".repeat(300)) }
        val beta = (0 until 4).map { i -> if (i % 2 == 0) u("TOPIC-BETA step$i " + "x".repeat(300)) else a("TOPIC-BETA ok$i " + "y".repeat(300)) }
        val history = alpha + beta
        val whole = compactUserMessage(buildConversationTextForSummary(history)).length
        val s = Splitter(sizeLimitedLeaf(whole - 1) { "SUM[" + topicsIn(it).joinToString(",") + "]" })
        val out = s.summarize(history)
        assertEquals("SUM[TOPIC-ALPHA]\n\nSUM[TOPIC-BETA]", out)
        assertTrue(out.contains("TOPIC-ALPHA") && out.contains("TOPIC-BETA"))
        assertFalse("no fabricated third topic", out.contains("TOPIC-GAMMA"))
        // Bleed check: the ALPHA leaf never saw BETA and vice versa, and neither
        // half was handed a "Previous context summary" of the other.
        val halves = s.leafInputs.drop(1)
        assertEquals(listOf("TOPIC-ALPHA"), topicsIn(halves[0]))
        assertEquals(listOf("TOPIC-BETA"), topicsIn(halves[1]))
        assertTrue(halves.none { it.contains("Previous context summary:") })
        // Oldest-first order is preserved in the join.
        assertTrue(out.indexOf("TOPIC-ALPHA") < out.indexOf("TOPIC-BETA"))
    }

    @Test
    fun `a previous summary is merged as background, not re-summarised as a goal`() {
        val s = Splitter { it }
        val framed = s.summarize(listOf(u("TOPIC-BETA next"), a("ok")), previousSummary = "Earlier: TOPIC-ALPHA was completed.")
        assertTrue(framed.contains("Previous context summary:\nEarlier: TOPIC-ALPHA was completed."))
        assertTrue(framed.contains("New conversation to merge:\nuser: TOPIC-BETA next"))
        assertTrue(framed.indexOf("Previous context summary:") < framed.indexOf("New conversation to merge:"))
    }

    // ── drift guard ────────────────────────────────────────────────────────

    @Test
    fun `drift guard - framing, past-tense prompt and summary wrapper still in production`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("append(\"Compact this conversation into a context summary:\\n\\n\")"))
        assertTrue(vm.contains("append(\"\\n\\n---\\nEND OF CONVERSATION TO COMPACT.\\n\\n\")"))
        assertTrue(vm.contains("Do NOT continue the conversation above — summarize it."))
        assertTrue(vm.contains("NOT as an ongoing goal or todo list."))
        // System prompt: past events, no carried-over objectives (old-task bleed).
        assertTrue(vm.contains("Do not invent ongoing objectives or carry-over tasks from old turns"))
        assertTrue(vm.contains("NOT a \"todo\" or \"pending\" list"))
        // Summary wrapper at read time: newest user instruction wins.
        assertTrue(vm.contains("The user's most recent message (below or in the next turn) takes precedence"))
        assertTrue(vm.contains("do not resume the old plan from this summary"))
        // Split-and-merge: textual join, halves get NO previous summary, depth cap 3.
        assertTrue(vm.contains("val summary1 = generateCompactSummaryWithSplitting(firstHalf, null, depth + 1)"))
        assertTrue(vm.contains("val summary2 = generateCompactSummaryWithSplitting(secondHalf, null, depth + 1)"))
        assertTrue(vm.contains("summary1 + \"\\n\\n\" + summary2"))
        assertTrue(vm.contains("messages.size < 2 || depth >= 3"))
        assertTrue(vm.contains("Previous context summary:\\n\$previousSummary\\n\\n"))
    }
}
