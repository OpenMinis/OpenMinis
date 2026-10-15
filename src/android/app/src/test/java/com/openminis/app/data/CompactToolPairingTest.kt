package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.ui.chat.dropOrphanedToolParts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-compact-orphan-toolcall] Pins the tool_use/tool_result pairing
 * rules that ChatViewModel's compaction path depends on (iOS c7f6a299e +
 * 5d346dc2e).
 *
 * `dropOrphanedToolParts` is the production function (ChatViewModelHistoryExt).
 * The walk-back predicate below is still mirrored, because that decision sits
 * inside ChatViewModel and needs a session to construct. If the production
 * walk-back changes, `walk-back refuses a tool-result-carrying user message`
 * is the test that must move with it.
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

    // ── layer 2: orphan sweep uses production dropOrphanedToolParts ────────

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
     * MIXED ID FORMS (upstream bc142f750 / [T-android-responses-tool-id-normalize]).
     * The Responses path stores "<call_id>|<fc_id>" on the pair, but a model
     * switch, a DB reload or a relay can leave the bare call_id on one half.
     * Raw comparison calls that a mismatch: the sweep would DELETE the real
     * result and fabricate an "interrupted" error for a tool that had worked.
     */
    @Test
    fun `mixed id forms pair - combined use, bare result`() {
        val history = listOf(
            userText("hi"),
            assistantToolUse("call_A|fc_A"),
            toolResult("call_A"),
            userText("thanks"),
        )
        assertEquals("a correctly paired round must pass through untouched", history, dropOrphanedToolParts(history))
    }

    @Test
    fun `mixed id forms pair - bare use, combined result`() {
        val history = listOf(
            userText("hi"),
            assistantToolUse("call_A"),
            toolResult("call_A|fc_A"),
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
    fun `result before use is dropped even when a later call has same id`() {
        val slice = listOf(toolResult("call_ORDER"), assistantToolUse("call_ORDER"))
        val repaired = dropOrphanedToolParts(slice)
        assertTrue("the earlier result is invalid", repaired.none { m ->
            m.contentParts.any { it is AgentContentPart.ToolResult }
        })
        assertTrue("the trailing in-flight call is retained", repaired.last().contentParts.any {
            it is AgentContentPart.ToolUse && it.id == "call_ORDER"
        })
    }

    @Test
    fun `a result with a mismatched name does not pair`() {
        val slice = listOf(
            assistantToolUse("call_NAME"),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(AgentContentPart.ToolResult(id = "call_NAME", name = "different_tool", content = "ok")),
            ),
        )
        val repaired = dropOrphanedToolParts(slice)
        assertTrue(repaired.any { it.contentParts.any { p -> p is AgentContentPart.ToolResult && p.name == "shell_execute" && p.isError } })
        assertTrue(repaired.none { it.contentParts.any { p -> p is AgentContentPart.ToolResult && p.name == "different_tool" } })
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
