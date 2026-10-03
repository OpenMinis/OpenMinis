package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-platform consistency check for the iOS 404 outage introduced by
 * commit `04c80e68e` — Android side.
 *
 * ## The iOS failure
 *
 * `04c80e68e` (fixing issue #368) added a "synthesized reasoning head": when a
 * tool-calling turn had no real reasoning echo to replay, iOS invented a
 * reasoning item and gave it the id `rs_syn_<last 24 of the tool call id>`.
 * Every subsequent request in an affected session then died with
 *
 *     [404] Item with id 'rs_syn_…' not found. Items are not persisted when
 *     store is set to false. Try again with store set to true, or remove this
 *     item from your input
 *
 * reported against Codex OAuth ("OpenAI PLUS (iCloud)"), which is why this
 * test pins that provider shape specifically.
 *
 * The root cause is a category error, not a formatting slip: `rs_…` is a
 * SERVER-OWNED resource key, and these requests send `store: false`, so an id
 * the server never minted is always a dangling reference. iOS measured this
 * against the live endpoint (gpt-5.6-sol, `store:false`):
 *
 *   * synthetic `rs_syn_…` id    → 404  (the reported failure)
 *   * well-formed but foreign id → 404  (so no invented id is "safe")
 *   * NO id, `summary: []`       → 200
 *   * no reasoning item at all   → 200  ← what Android does
 *
 * ## Android's position
 *
 * Android never had the defect. `buildResponsesAPIBody` emits only
 * `function_call`, `function_call_output` and input content blocks; it never
 * constructs a `reasoning` item and never captures an `rs_` id from the
 * stream to replay, so there is nothing to fabricate an id for. Per the last
 * row of the table above, "no reasoning item at all" is a 200.
 *
 * Android does synthesize `fc_syn_…` for `function_call.id` when history
 * arrived without one. That is deliberately NOT the same hazard, and iOS says
 * so explicitly at OpenAIAgentProvider.swift:1888 — `function_call.id` is a
 * per-request label whose real pairing key is `call_id`, whereas `rs_…` is a
 * lookup into server-side storage. This test asserts `fc_syn_` keeps working
 * so a future "remove all synthetic ids" sweep does not over-correct and break
 * replay of Chat-Completions-sourced history.
 *
 * These are wire-body assertions because the wire body is what 404'd. A test
 * that only checked "the call succeeded" would have passed against the bug.
 */
class CodexOAuthNoSyntheticReasoningIdTest {

    /**
     * The exact reported shape: Codex OAuth (no API key, no custom base URL),
     * which routes to the ChatGPT backend Responses API.
     */
    private fun codexOAuthProvider(): OpenAIProvider = OpenAIProvider(
        oauthTokenProvider = { "test-oauth-token" },
        model = LLMModel(
            id = "gpt-5.6-sol",
            displayName = "GPT-5.6 Sol",
            provider = "OpenAI",
        ),
        codexAccountId = "acct_test",
    )

    /**
     * A turn that called a tool and carries NO reasoning echo — the condition
     * that made iOS fabricate an id. `reasoningContent` is left null on
     * purpose; that absence is the whole trigger.
     */
    private fun toolTurnWithoutReasoningEcho(): List<LLMMessage> = listOf(
        LLMMessage(LLMMessage.Role.USER, "list the files"),
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolUse(
                    id = "call_abc123def456ghi789jkl012",
                    name = "shell_execute",
                    input = JSONObject().put("command", "ls"),
                ),
            ),
        ),
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolResult(
                    id = "call_abc123def456ghi789jkl012",
                    name = "shell_execute",
                    content = "a.txt\nb.txt",
                ),
            ),
        ),
        LLMMessage(LLMMessage.Role.USER, "now summarize them"),
    )

    private fun buildBody(): JSONObject = codexOAuthProvider().buildResponsesAPIBody(
        messages = toolTurnWithoutReasoningEcho(),
        systemPrompt = "you are a helpful agent",
        maxTokens = 4096,
        stream = true,
        imageParts = emptyList(),
        tools = listOf(
            AgentToolDefinition(
                name = "shell_execute",
                description = "run a shell command",
                parameters = mapOf(
                    "command" to AgentToolParam(type = "string", description = "the command"),
                ),
                required = listOf("command"),
            ),
        ),
        thinkingLevel = ThinkingLevel.MEDIUM,
    )

    private fun inputItems(body: JSONObject): List<JSONObject> {
        val input: JSONArray? = body.optJSONArray("input")
        assertNotNull("request has no `input` array: $body", input)
        return (0 until input!!.length()).map { input.getJSONObject(it) }
    }

    @Test
    fun `no input item carries a synthetic rs_ reasoning id`() {
        val body = buildBody()
        val offenders = inputItems(body).filter { item ->
            val id = item.optString("id", "")
            id.startsWith("rs_")
        }
        assertTrue(
            "An `rs_…` id in the request is a reference into server-side storage " +
                "that cannot exist under store:false — this is the iOS 04c80e68e 404. " +
                "Offending items: $offenders",
            offenders.isEmpty(),
        )
        // Belt and braces: the literal prefix must not appear anywhere in the
        // body, including nested inside a reasoning item's fields.
        assertFalse(
            "request body contains a synthesized rs_syn_ id: $body",
            body.toString().contains("rs_syn_"),
        )
    }

    @Test
    fun `a tool turn with no reasoning echo emits no reasoning item at all`() {
        // iOS measured "no reasoning item at all → 200" against the live
        // endpoint. Android relies on that row: it has no reasoning echo to
        // replay on this path, so the correct request is one without the item,
        // NOT one with an item bearing an invented id.
        val reasoningItems = inputItems(buildBody()).filter { it.optString("type") == "reasoning" }
        assertTrue(
            "Android does not capture an `rs_` id to replay, so it must not emit a " +
                "reasoning item here; emitting one would require an id it cannot " +
                "legitimately produce. Found: $reasoningItems",
            reasoningItems.isEmpty(),
        )
    }

    @Test
    fun `store stays false, which is what makes a fabricated id fatal`() {
        // Pinned because the two facts are only dangerous together: it is
        // `store:false` that guarantees the server never minted the id. If a
        // future change flips this, the reasoning above needs revisiting
        // rather than silently becoming wrong.
        assertEquals(
            "Responses requests are expected to send store:false",
            false,
            buildBody().opt("store"),
        )
    }

    @Test
    fun `the function_call label is still synthesized when history lacks one`() {
        // The deliberate counterpart: `fc_syn_…` is NOT the same hazard.
        // `function_call.id` is a per-request label whose real pairing key is
        // `call_id` (iOS OpenAIAgentProvider.swift:1888), so replaying history
        // that arrived without an fc_ id depends on this. Guards against a
        // future "strip every synthetic id" sweep over-correcting.
        val calls = inputItems(buildBody()).filter { it.optString("type") == "function_call" }
        assertEquals("expected exactly one function_call item", 1, calls.size)
        val call = calls.single()
        assertEquals("call_abc123def456ghi789jkl012", call.optString("call_id"))
        assertTrue(
            "history with no fc_ id must still get a deterministic label, got: ${call.optString("id")}",
            call.optString("id").startsWith("fc_syn_"),
        )
    }

    @Test
    fun `the synthetic function_call label is deterministic across rebuilds`() {
        // An unstable id would defeat prompt caching on every replay of the
        // same history — the reason iOS derived its ids from the call id
        // rather than a random value.
        fun fcIds() = inputItems(buildBody())
            .filter { it.optString("type") == "function_call" }
            .map { it.optString("id") }
        assertEquals(fcIds(), fcIds())
    }
}
