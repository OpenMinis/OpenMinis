package com.openminis.app.data

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
                    is AgentContentPart.ToolUse -> toolUseIds.add(com.openminis.app.provider.ToolPairing.key(part.id))
                    is AgentContentPart.ToolResult -> toolResultIds.add(com.openminis.app.provider.ToolPairing.key(part.id))
                    else -> {}
                }
            }
        }
        val orphanedResults = toolResultIds - toolUseIds
        val orphanedUses = HashSet(toolUseIds - toolResultIds)

        val last = history.lastOrNull()
        if (last != null && last.role == LLMMessage.Role.ASSISTANT) {
            for (part in last.contentParts) {
                if (part is AgentContentPart.ToolUse) orphanedUses.remove(com.openminis.app.provider.ToolPairing.key(part.id))
            }
        }
        if (orphanedResults.isEmpty() && orphanedUses.isEmpty()) return history

        val cleaned = ArrayList<LLMMessage>(history.size)
        for (msg in history) {
            val kept = msg.contentParts.filter { part ->
                if (part is AgentContentPart.ToolResult) !orphanedResults.contains(com.openminis.app.provider.ToolPairing.key(part.id)) else true
            }
            if (kept.isEmpty() && msg.contentParts.isNotEmpty()) continue
            cleaned.add(if (kept.size == msg.contentParts.size) msg else msg.copy(contentParts = kept))

            if (msg.role != LLMMessage.Role.ASSISTANT) continue
            val unanswered = kept.filterIsInstance<AgentContentPart.ToolUse>()
                .filter { orphanedUses.contains(com.openminis.app.provider.ToolPairing.key(it.id)) }
            if (unanswered.isNotEmpty()) {
                cleaned.add(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = unanswered.map {
                            AgentContentPart.ToolResult(
                                id = it.id,
                                name = it.name,
                                content = "Tool execution was interrupted by an unexpected error.",
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

    // ═══════════════════════════════════════════════════════════════════════
    // [T03] Interrupted-then-continued rounds, Responses-API orphans, and the
    // tool_call_id constraints (2626b215f, 942199034, 02c1d2934, 228192fc1;
    // issues #293 #222 #352).
    //
    // The four cases below go one layer further than the ones above: after the
    // sweep, the history is pushed through the REAL OpenAIProvider request
    // builders (`buildRequestBody` for Chat Completions,
    // `buildResponsesAPIBody` for Responses) so the wire shape is what is
    // asserted — that is what the provider 400s on.
    // ═══════════════════════════════════════════════════════════════════════

    private fun chatProvider(responses: Boolean = false) = OpenAIProvider(
        apiKey = "test-key",
        model = LLMModel("gpt-4o-mini", "GPT-4o Mini", "OpenAI"),
        basePath = "https://example.invalid/v1",
        useResponsesAPI = responses,
    )

    private fun chatMessages(history: List<LLMMessage>): JSONArray =
        chatProvider().buildRequestBody(
            messages = history,
            systemPrompt = null,
            maxTokens = 512,
            stream = false,
            temperature = null,
            imageParts = emptyList(),
        ).getJSONArray("messages")

    private fun responsesInput(history: List<LLMMessage>): JSONArray =
        chatProvider(responses = true).buildResponsesAPIBody(
            messages = history,
            systemPrompt = null,
            maxTokens = 512,
            stream = false,
        ).getJSONArray("input")

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).map { getJSONObject(it) }

    /**
     * The OpenAI-compatible contract: every `tool` reply must directly follow
     * the assistant `tool_calls` message that claimed its id, and every
     * claimed id must be answered before the next non-tool message.
     */
    private fun assertToolPairingOnWire(msgs: List<JSONObject>) {
        var pending = mutableSetOf<String>()
        for ((i, m) in msgs.withIndex()) {
            when (m.optString("role")) {
                "assistant" -> {
                    assertTrue("assistant at $i while calls still unanswered: $pending", pending.isEmpty())
                    val calls = m.optJSONArray("tool_calls") ?: continue
                    pending = calls.objects().map { it.getString("id") }.toMutableSet()
                }
                "tool" -> {
                    val id = m.getString("tool_call_id")
                    assertTrue(
                        "role 'tool' at $i (id=$id) does not follow a tool_calls message claiming it " +
                            "— this is the `role 'tool' must follow tool_calls` 400",
                        pending.remove(id),
                    )
                }
                else -> assertTrue("message at $i interrupts an unanswered tool run: $pending", pending.isEmpty())
            }
        }
        assertTrue("history ends with unanswered calls: $pending", pending.isEmpty())
    }

    /**
     * Case 1 — Stop mid tool_use, then Continue. While the call is in flight the
     * tail is exempt (see above). The moment the user continues, that call is
     * no longer the tail: it is a genuine orphan and must be paired BEFORE the
     * user's new text, or Chat Completions answers with
     * `messages[N] role 'tool' must be a response to a preceding tool_calls`.
     */
    @Test
    fun `stop during a tool_use then continue leaves no dangling call on the wire`() {
        val stopped = listOf(
            userText("build it"),
            assistantToolUse("call_STOPPED"),  // stream was cut here
        )
        // In-flight: untouched (the exemption).
        assertEquals(stopped, dropOrphanedToolParts(stopped))

        // Continue: a new user turn lands after the stopped call.
        val continued = stopped + userText("continue")
        val repaired = dropOrphanedToolParts(continued)
        val (uses, results) = allIds(repaired)
        assertEquals(setOf("call_STOPPED"), uses)
        assertEquals("the stopped call is answered, not deleted", setOf("call_STOPPED"), results)
        assertEquals("synthesised result sits BEFORE the continuation text", 4, repaired.size)
        assertTrue(repaired[2].contentParts.any { it is AgentContentPart.ToolResult })
        assertEquals("continue", repaired[3].content)

        // And the wire shape is legal.
        assertToolPairingOnWire(chatMessages(repaired).objects())
    }

    /**
     * Case 2 — Responses API. `buildResponsesAPIBody` emits a
     * `function_call_output` for EVERY ToolResult it is handed; it has no
     * orphan check of its own. So when compaction cuts away the
     * `function_call`, the sweep is the only thing that stops the output from
     * going out alone (`No tool call found for function call output with
     * call_id …`).
     */
    @Test
    fun `Responses API - a compacted-away function_call takes its function_call_output with it`() {
        val slice = listOf(
            toolResult("call_GONE"),  // its function_call was in compacted pre-history
            userText("what next?"),
        )
        // [T-android-responses-orphan-tool-output] The builder now drops a
        // stranded output itself too (ResponsesToolPairing, eef7195db), so the
        // sweep and the wire gate each keep this shape off the wire.
        val rawTypes = responsesInput(slice).objects().map { it.optString("type") }
        assertFalse("the wire gate drops the orphan on its own", rawTypes.contains("function_call_output"))

        val repaired = dropOrphanedToolParts(slice)
        val items = responsesInput(repaired).objects()
        assertFalse(
            "no function_call_output may be sent without its function_call",
            items.any { it.optString("type") == "function_call_output" },
        )
        assertFalse(items.any { it.optString("type") == "function_call" })
        assertEquals("the user's text still goes", 1, items.count { it.optString("role") == "user" })
    }

    /**
     * Case 3 — the same call id reused by two assistant messages (DB-reloaded
     * history, or a gateway that re-issues ids). `globallyDedupeToolCallIds`
     * renames the SECOND claim to `{id}-2` and rewrites its reply in lockstep,
     * so the request has unique ids AND every pair still matches.
     */
    @Test
    fun `a call id reused across two assistant messages is renamed on the second`() {
        val history = listOf(
            userText("first"),
            assistantToolUse("call_dup"),
            toolResult("call_dup"),
            userText("again"),
            assistantToolUse("call_dup"),
            toolResult("call_dup"),
        )
        val msgs = chatMessages(history).objects()
        val callIds = msgs.filter { it.has("tool_calls") }
            .flatMap { it.getJSONArray("tool_calls").objects() }.map { it.getString("id") }
        val replyIds = msgs.filter { it.optString("role") == "tool" }.map { it.getString("tool_call_id") }

        assertEquals(listOf("call_dup", "call_dup-2"), callIds)
        assertEquals("replies renamed in lockstep with their claims", callIds, replyIds)
        assertEquals("ids are unique across the whole messages array", callIds.size, callIds.toSet().size)
        assertToolPairingOnWire(msgs)
    }

    /**
     * Case 4 — ids over 64 chars (`Invalid 'input[2].call_id': string too
     * long`). The cap must be DETERMINISTIC: the assistant claim and the tool
     * reply are capped independently, so if they ever disagreed the pair would
     * break — a different 400.
     */
    @Test
    fun `an id over 64 chars is capped and the tool reply uses the same capped value`() {
        val longId = "call_" + "x".repeat(76)  // 81 chars, the reported length
        assertTrue(longId.length > 64)
        val history = listOf(userText("go"), assistantToolUse(longId), toolResult(longId))
        val msgs = chatMessages(history).objects()
        val claim = msgs.first { it.has("tool_calls") }.getJSONArray("tool_calls").getJSONObject(0).getString("id")
        val reply = msgs.first { it.optString("role") == "tool" }.getString("tool_call_id")

        assertTrue("capped claim must be <= 64 chars, got ${claim.length}", claim.length <= 64)
        assertNotEquals("the raw id must not be sent", longId, claim)
        assertTrue("capped id still reads as a call id", claim.startsWith("call_"))
        assertEquals("claim and reply must agree byte-for-byte", claim, reply)
        assertToolPairingOnWire(msgs)

        // A Responses-origin combined id ("call_…|fc_…") collapses to its call half.
        val combined = "call_abc123|fc_" + "y".repeat(60)
        val msgs2 = chatMessages(listOf(userText("go"), assistantToolUse(combined), toolResult(combined))).objects()
        val claim2 = msgs2.first { it.has("tool_calls") }.getJSONArray("tool_calls").getJSONObject(0).getString("id")
        assertEquals("call_abc123", claim2)
        assertEquals(claim2, msgs2.first { it.optString("role") == "tool" }.getString("tool_call_id"))
    }

    /**
     * Drift guard for the ported sweep: the production body must still carry
     * the lines the port above depends on. If this fails, re-sync the port.
     */
    @Test
    fun `drift guard - production sweep and provider caps still exist`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("private fun dropOrphanedToolParts(history: List<LLMMessage>): List<LLMMessage>"))
        assertTrue("in-flight exemption removed?", vm.contains("if (part is AgentContentPart.ToolUse) orphanedUses.remove(com.openminis.app.provider.ToolPairing.key(part.id))"))
        assertTrue("sweep no longer on the outgoing path?", vm.contains("dropOrphanedToolParts(effectiveAgentHistoryUncounted())"))
        assertTrue(vm.contains("Tool execution was interrupted by an unexpected error."))

        val oai = ProductionSources.read("provider/openai/OpenAIProvider.kt")
        assertTrue(oai.contains("globallyDedupeToolCallIds(messagesArray)"))
        assertTrue(oai.contains("put(\"id\", capChatToolCallId(tu.id))"))
        assertTrue(oai.contains("put(\"tool_call_id\", capChatToolCallId(tr.id))"))
        assertTrue(oai.contains("put(\"call_id\", capResponsesId(callId))"))
    }
}
