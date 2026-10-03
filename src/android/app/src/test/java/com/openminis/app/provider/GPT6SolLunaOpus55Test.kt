package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-gpt6-sol-luna] [T-anthropic-opus55-catalog] The three models added
 * alongside the iOS change: `gpt-6-sol`, `gpt-6-luna` (Codex OAuth) and
 * `claude-opus-5-5` (Anthropic).
 *
 * Modelled on `GPT6AstraReasoningTest`, which exists because a brand-new model
 * id is a DATA gap, not a logic bug: the id is too new for the bundled
 * models.dev catalog, so nothing enriches `supportsReasoning`, and the
 * Responses builder's "Codex OAuth requires a reasoning object" fallback
 * hard-codes effort "low". The user's thinking level is then silently ignored.
 * Every guard below is one literal in a list of model declarations, and
 * deleting any of them would look entirely unremarkable in review.
 */
class GPT6SolLunaOpus55Test {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun codex(id: String): LLMModel =
        OpenAIModelsApi.fetchModelsOAuth().first { it.id == id }

    private val opus55: LLMModel get() = LLMModel.claudeOpus55

    // ── Static catalog ───────────────────────────────────────────────────

    @Test
    fun `the new codex entries are shipped in the oauth list`() {
        val ids = OpenAIModelsApi.fetchModelsOAuth().map { it.id }
        assertTrue("gpt-6-sol missing from the Codex OAuth list", ids.contains("gpt-6-sol"))
        assertTrue("gpt-6-luna missing from the Codex OAuth list", ids.contains("gpt-6-luna"))
    }

    @Test
    fun `the new codex entries declare supportsReasoning`() {
        // THE load-bearing flag. Without it the Responses builder falls back to
        // effort "low" and the user's thinking level never reaches the wire.
        for (id in listOf("gpt-6-sol", "gpt-6-luna")) {
            assertEquals(
                "$id must declare supportsReasoning=true in the static Codex OAuth list — " +
                    "neither id is in the bundled models.dev snapshot, so nothing fills " +
                    "it in later",
                true,
                codex(id).supportsReasoning,
            )
        }
    }

    @Test
    fun `opus 5 5 is shipped in the anthropic list and declares supportsReasoning`() {
        assertTrue(
            "claude-opus-5-5 missing from allAnthropic",
            LLMModel.allAnthropic.any { it.id == "claude-opus-5-5" },
        )
        assertEquals(true, opus55.supportsReasoning)
        assertEquals(1_000_000, opus55.contextWindow)
        assertEquals(128_000, opus55.maxOutputTokens)
    }

    // ── Thinking ceilings ────────────────────────────────────────────────

    @Test
    fun `catalog ceiling is max for all three`() {
        assertEquals(ThinkingLevel.MAX, codex("gpt-6-sol").catalogMaxThinkingLevel)
        assertEquals(ThinkingLevel.MAX, codex("gpt-6-luna").catalogMaxThinkingLevel)
        assertEquals(ThinkingLevel.MAX, opus55.catalogMaxThinkingLevel)
    }

    @Test
    fun `opus 5 5 is not left on the conservative default`() {
        // The pre-existing Anthropic rule is anchored on "claude-opus-4", so
        // Opus 5.5 matched NOTHING and fell through to the XHIGH default —
        // quietly denying the user the `max` tier the model accepts. This is
        // the assertion that catches a revert of the 5-series rule.
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("claude-opus-5-5"))
        assertNotEquals(ThinkingLevel.XHIGH, opus55.catalogMaxThinkingLevel)
        // And it must keep matching the rest of the 5 line without another edit.
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("claude-opus-5-6"))
    }

    @Test
    fun `an undeclared flag still reaches max rather than collapsing to off`() {
        // Mirrors GPT6AstraReasoningTest: the ceiling bails only on an explicit
        // `false`, so a null flag must still reach MAX via the id rule.
        for (id in listOf("gpt-6-sol", "gpt-6-luna")) {
            val m = codex(id)
            assertEquals(ThinkingLevel.MAX, m.copy(supportsReasoning = null).catalogMaxThinkingLevel)
            assertEquals(ThinkingLevel.OFF, m.copy(supportsReasoning = false).catalogMaxThinkingLevel)
        }
    }

    // ── Codex wire: every level survives to `reasoning.effort` ───────────

    /**
     * Capture the Responses body the production builder emits.
     *
     * Uses `useResponsesAPI = true` on an API-key provider rather than the
     * OAuth constructor: the Codex branch hard-codes
     * chatgpt.com/backend-api/codex/responses and cannot be pointed at a mock,
     * but both branches serialize the SAME body before the URL split — so the
     * reasoning block under test is identical. (Same approach as
     * GPT6AstraReasoningTest.)
     */
    private fun responsesBody(model: LLMModel, level: ThinkingLevel): JSONObject {
        val ok = """{"type":"response.completed","response":{"output":[],"status":"completed"}}"""
        repeat(4) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok))
        }
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = model,
            basePath = server.url("/v1").toString().trimEnd('/'),
            useResponsesAPI = true,
        )
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = listOf(LLMMessage(LLMMessage.Role.USER, "question")),
                    systemPrompt = null,
                    maxTokens = 4096,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = level,
                )
            }
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    private fun effortOf(body: JSONObject): String? =
        body.optJSONObject("reasoning")?.optString("effort")?.takeIf { it.isNotEmpty() }

    @Test
    fun `every thinking level reaches the codex wire as itself`() {
        // The assertion that would have caught the reported Astra bug: under
        // it, every level produced "low".
        val expected = mapOf(
            ThinkingLevel.LOW to "low",
            ThinkingLevel.MEDIUM to "medium",
            ThinkingLevel.HIGH to "high",
            ThinkingLevel.XHIGH to "xhigh",
            // MAX and ULTRA both serialize to "max" — ULTRA is a client-side
            // "max + orchestration" concept, never a wire effort. This is also
            // why Sol dropping "ultra" from its declared levels needs no
            // special case here.
            ThinkingLevel.MAX to "max",
            ThinkingLevel.ULTRA to "max",
        )
        for (id in listOf("gpt-6-sol", "gpt-6-luna")) {
            val model = codex(id)
            for ((level, wire) in expected) {
                assertEquals(
                    "$id at $level must put effort=$wire on the wire",
                    wire,
                    effortOf(responsesBody(model, level)),
                )
            }
        }
    }

    // ── Anthropic wire: Opus 5.5 must never be sent thinking.type=disabled ──

    private fun anthropicBody(model: LLMModel, level: ThinkingLevel): JSONObject {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"msg_1","type":"message","role":"assistant","content":[],""" +
                    """"model":"${model.id}","stop_reason":"end_turn",""" +
                    """"usage":{"input_tokens":1,"output_tokens":1}}""",
            ),
        )
        val provider = AnthropicProvider(
            apiKey = "test-key",
            model = model,
            basePath = server.url("/").toString().trimEnd('/'),
        )
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = listOf(LLMMessage(LLMMessage.Role.USER, "question")),
                    systemPrompt = null,
                    maxTokens = 4096,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = level,
                )
            }
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    @Test
    fun `opus 5 5 is never sent the literal thinking disabled`() {
        // Opus 5.5 answers `thinking.type="disabled"` with a 400
        // ("not supported for this model") and treats an ABSENT thinking field
        // as adaptive — which is exactly what OFF wants. The predicate is
        // version-keyed (`major == 4 && minor >= 6`), so a major=5 id falls
        // through by construction; this pins that so a future "simplification"
        // to `>= 4.6` cannot silently re-break it.
        assertFalse(
            "claude-opus-5-5 must NOT accept the explicit disabled literal",
            AnthropicProvider.modelAcceptsExplicitThinkingDisabled("claude-opus-5-5"),
        )
        // The 4.6-4.x generation still needs it sent explicitly.
        assertTrue(AnthropicProvider.modelAcceptsExplicitThinkingDisabled("claude-opus-4-8"))

        val body = anthropicBody(opus55, ThinkingLevel.OFF)
        val thinking = body.optJSONObject("thinking")
        assertNotEquals(
            "OFF on Opus 5.5 must not put thinking.type=disabled on the wire — the " +
                "model 400s on it",
            "disabled",
            thinking?.optString("type"),
        )
    }

    @Test
    fun `opus 5 5 uses adaptive thinking with an effort tier`() {
        assertTrue(AnthropicProvider.modelUsesAdaptiveThinking("claude-opus-5-5"))
        val body = anthropicBody(opus55, ThinkingLevel.MAX)
        val thinking = body.optJSONObject("thinking")
        assertNotNull("adaptive thinking must be present at MAX", thinking)
        assertEquals("adaptive", thinking!!.optString("type"))
        assertEquals(
            "MAX must reach the wire as effort=max",
            "max",
            body.optJSONObject("output_config")?.optString("effort"),
        )
    }

    @Test
    fun `opus 5 5 does not send temperature`() {
        // 4.6+ reject it; parseClaudeVersion already covers major=5.
        assertTrue(AnthropicProvider.modelRejectsTemperature("claude-opus-5-5"))
    }
}
