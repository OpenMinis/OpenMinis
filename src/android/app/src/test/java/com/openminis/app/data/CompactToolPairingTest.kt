package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-compact-orphan-toolcall] Pins the tool_use/tool_result pairing
 * rules that ChatViewModel's compaction path depends on (iOS c7f6a299e +
 * 5d346dc2e).
 *
 * Both production functions live on ChatViewModel, which needs a Context, a DB
 * and a provider to construct — so, following InLoopContextPolicyTest, these
 * cover the DECISION LOGIC rather than the coroutine plumbing. The algorithms
 * below are kept byte-for-byte equivalent to the production bodies; if one is
 * changed without the other, `orphan sweep repairs a slice that lost its call`
 * and `walk-back refuses a tool-result-carrying user message` are the tests
 * that fail.
 *
 * Why this matters: an unmatched pair is a hard 400 on OpenAI-compatible APIs
 * ("No tool call found for function call output with call_id …"), and because
 * the history slice is recomputed deterministically it repeats on every retry
 * AND every fallback model — the session wedges until the user clears it.
 */
class CompactToolPairingTest {

    // ── helpers mirroring the production message shapes ────────────────────

    private fun userText(text: String) =
        LLMMessage(role = LLMMessage.Role.USER, content = text)

    private fun assistantToolUse(vararg ids: String) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = "",
        contentParts = ids.map { AgentContentPart.ToolUse(it, "shell_execute", JSONObject()) },
    )

    /** Tool results are carried as USER messages — the crux of the bug. */
    private fun toolResult(vararg ids: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = ids.map { AgentContentPart.ToolResult(it, "shell_execute", "ok") },
    )

    // ── layer 1: walk-back boundary rule (production: walkBackUserTurnsBounded)

    /**
     * Mirrors the production boundary predicate: a USER message is only a valid
     * round boundary when it does NOT carry a tool result.
     */
    private fun isBoundaryEligible(msg: LLMMessage): Boolean =
        msg.role == LLMMessage.Role.USER &&
            msg.contentParts.none { it is AgentContentPart.ToolResult }

    @Test
    fun `walk-back refuses a tool-result-carrying user message as a boundary`() {
        val history = listOf(
            userText("do the thing"),      // 0 — real boundary
            assistantToolUse("call_A"),    // 1
            toolResult("call_A"),          // 2 — USER role, but NOT a boundary
        )
        assertTrue("a plain user turn starts a round", isBoundaryEligible(history[0]))
        assertTrue("an assistant turn is never a boundary", !isBoundaryEligible(history[1]))
        assertTrue(
            "a user message carrying a tool result is the SECOND half of a round; " +
                "cutting here orphans call_A's tool_use",
            !isBoundaryEligible(history[2]),
        )
    }

    // ── layer 2: orphan sweep (production: dropOrphanedToolParts) ──────────

    private fun dropOrphanedToolParts(history: List<LLMMessage>): List<LLMMessage> {
        val toolUseIds = HashSet<String>()
        val toolResultIds = HashSet<String>()
        for (msg in history) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.ToolUse -> toolUseIds.add(part.id)
                    is AgentContentPart.ToolResult -> toolResultIds.add(part.id)
                    else -> {}
                }
            }
        }
        val orphanedResults = toolResultIds - toolUseIds
        val orphanedUses = HashSet(toolUseIds - toolResultIds)

        val last = history.lastOrNull()
        if (last != null && last.role == LLMMessage.Role.ASSISTANT) {
            for (part in last.contentParts) {
                if (part is AgentContentPart.ToolUse) orphanedUses.remove(part.id)
            }
        }
        if (orphanedResults.isEmpty() && orphanedUses.isEmpty()) return history

        val cleaned = ArrayList<LLMMessage>(history.size)
        for (msg in history) {
            val kept = msg.contentParts.filter { part ->
                if (part is AgentContentPart.ToolResult) !orphanedResults.contains(part.id) else true
            }
            if (kept.isEmpty() && msg.contentParts.isNotEmpty()) continue
            cleaned.add(if (kept.size == msg.contentParts.size) msg else msg.copy(contentParts = kept))

            if (msg.role != LLMMessage.Role.ASSISTANT) continue
            val unanswered = kept.filterIsInstance<AgentContentPart.ToolUse>()
                .filter { orphanedUses.contains(it.id) }
            if (unanswered.isNotEmpty()) {
                cleaned.add(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = unanswered.map {
                            AgentContentPart.ToolResult(
                                id = it.id,
                                name = it.name,
                                content = com.openminis.app.data.ToolInterrupt.UNEXPECTED,
                                isError = true,
                            )
                        },
                    ),
                )
            }
        }
        return cleaned
    }

    private fun allIds(history: List<LLMMessage>): Pair<Set<String>, Set<String>> {
        val uses = HashSet<String>()
        val results = HashSet<String>()
        for (m in history) for (p in m.contentParts) {
            if (p is AgentContentPart.ToolUse) uses.add(p.id)
            if (p is AgentContentPart.ToolResult) results.add(p.id)
        }
        return uses to results
    }

    @Test
    fun `a balanced history passes through untouched`() {
        val history = listOf(
            userText("hi"),
            assistantToolUse("call_A"),
            toolResult("call_A"),
            userText("thanks"),
        )
        assertEquals(history, dropOrphanedToolParts(history))
    }

    /**
     * THE REPORTED WEDGE: compaction cut between an assistant's tool_use and
     * its own tool_result, so the slice carries a result whose call is gone.
     * That lone output is what the provider rejects with a 400.
     */
    @Test
    fun `orphan sweep drops a result whose call was cut away`() {
        val slice = listOf(
            toolResult("call_M1ate3"),  // call_M1ate3's tool_use is in pre-history
            userText("continue"),
        )
        val repaired = dropOrphanedToolParts(slice)
        val (uses, results) = allIds(repaired)
        assertTrue("the orphaned output must not reach the provider", results.isEmpty())
        assertTrue(uses.isEmpty())
        assertEquals("the emptied message is removed, the text turn survives", 1, repaired.size)
        assertEquals("continue", repaired[0].content)
    }

    @Test
    fun `orphan sweep synthesises a result for a mid-history unanswered call`() {
        val slice = listOf(
            assistantToolUse("call_LOST"),  // never answered, and NOT the tail
            userText("next question"),
        )
        val repaired = dropOrphanedToolParts(slice)
        val (uses, results) = allIds(repaired)
        assertEquals("the call is preserved, not deleted", setOf("call_LOST"), uses)
        assertEquals("and it is now paired", setOf("call_LOST"), results)
        val synthesized = repaired.first { m ->
            m.contentParts.any { it is AgentContentPart.ToolResult }
        }.contentParts.filterIsInstance<AgentContentPart.ToolResult>().first()
        assertTrue("the placeholder is flagged as an error", synthesized.isError)
    }

    /**
     * IN-FLIGHT EXEMPTION (iOS 5d346dc2e). Between "model asked for tools" and
     * "results appended" the history legitimately ends on an unpaired
     * assistant tool_use. Treating that as an orphan would ship fabricated
     * "interrupted" results for tools that were about to run normally — and a
     * cache-warmup snapshot taken in that window would poison the cached prefix
     * AND tell the model its tools had failed.
     */
    @Test
    fun `in-flight trailing tool_use is exempt from the sweep`() {
        val midRound = listOf(
            userText("run it"),
            assistantToolUse("call_INFLIGHT"),  // tail: results not appended YET
        )
        val repaired = dropOrphanedToolParts(midRound)
        assertEquals("mid-round history must pass through untouched", midRound, repaired)
        val (_, results) = allIds(repaired)
        assertTrue("no fabricated result may be injected mid-round", results.isEmpty())
    }

    /**
     * The exemption is scoped to the TAIL only. An unanswered call earlier in
     * the slice is a genuine orphan and must still be repaired, even when the
     * history also ends on a live in-flight call.
     */
    @Test
    fun `exemption covers only the tail, not earlier unanswered calls`() {
        val slice = listOf(
            assistantToolUse("call_OLD"),       // genuine orphan
            userText("meanwhile"),
            assistantToolUse("call_INFLIGHT"),  // tail — exempt
        )
        val repaired = dropOrphanedToolParts(slice)
        val (uses, results) = allIds(repaired)
        assertTrue("both calls survive", uses.containsAll(setOf("call_OLD", "call_INFLIGHT")))
        assertEquals("only the stale one is paired", setOf("call_OLD"), results)
    }

    @Test
    fun `a plain text message with no parts is never dropped`() {
        val slice = listOf(
            toolResult("call_ORPHAN"),
            userText("plain text carries no contentParts and must survive"),
        )
        val repaired = dropOrphanedToolParts(slice)
        assertEquals(1, repaired.size)
        assertTrue(repaired[0].content.startsWith("plain text"))
    }
}
