package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T19] Outbound fields are gated per gateway — a field one vendor requires is a
 * hard 400 on another, so every one of these has to be asserted BOTH present
 * where it belongs and absent where it does not.
 *
 * Field reports behind each row: GH#247/#259/#268 (`prompt_cache_key` rejected by
 * a strict-schema gateway — NVIDIA), GH#86 (Venice rejects a root `thinking` key
 * before model dispatch), GH#177 (`reasoning_effort` placement differs per
 * protocol: root on Chat, `reasoning.effort` on Responses / OpenRouter,
 * `output_config.effort` on Anthropic adaptive), GH#87 (Mistral rejects every
 * thinking field), T-responses-include (`include: reasoning.encrypted_content` is
 * Codex-OAuth-only), GH#191 (OpenRouter `cache_control` only for anthropic/ ids).
 *
 * All rows drive the real provider into a MockWebServer whose path carries the
 * vendor literal the production URL predicate greps for, and read the serialized
 * request — the production gates are private, so this is the only honest seam.
 */
class OutboundFieldGatingTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun model(
        id: String,
        supportsReasoning: Boolean? = true,
        reasoningEffortValues: List<String>? = null,
    ) = LLMModel(
        id = id,
        displayName = id,
        provider = "Test",
        supportsReasoning = supportsReasoning,
        reasoningEffortValues = reasoningEffortValues,
    )

    private fun capture(
        m: LLMModel,
        level: ThinkingLevel,
        path: String = "/v1",
        responses: Boolean = false,
        firstUserText: String = "question",
    ): JSONObject {
        val ok = if (responses) {
            """{"type":"response.completed","response":{"output":[],"status":"completed"}}"""
        } else {
            """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        }
        repeat(4) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok))
        }
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = m,
            basePath = server.url(path).toString().trimEnd('/'),
            useResponsesAPI = responses,
        )
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = listOf(LLMMessage(LLMMessage.Role.USER, firstUserText)),
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

    private fun captureAnthropic(m: LLMModel, level: ThinkingLevel): JSONObject {
        val ok = """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""
        repeat(4) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok))
        }
        val provider = AnthropicProvider(
            apiKey = "test-key",
            model = m,
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

    // ============================================================ prompt_cache_key

    /**
     * GH#247/#259/#268: a strict-schema gateway (NVIDIA NIM) answers an unknown
     * request field with `400 UNKNOWN_FIELD`, failing the whole request. On the
     * Chat Completions path a custom base therefore never gets `prompt_cache_key`.
     */
    @Test
    fun `chat completions on a custom base sends no prompt_cache_key`() {
        for (path in listOf("/v1", "/integrate.api.nvidia.com/v1", "/api.venice.ai/api/v1")) {
            val body = capture(model("meta/llama-3.1-70b-instruct", supportsReasoning = false), ThinkingLevel.OFF, path = path)
            assertFalse("no prompt_cache_key on $path: $body", body.has("prompt_cache_key"))
        }
    }

    /**
     * The Responses path is an explicit opt-in ("this base speaks the Responses
     * API"), and relays like sub2api NEED the key because they synthesize no
     * server-side fallback — same decision as iOS (T-ios-prompt-cache-key-400).
     * The key must be stable across turns of one chat and distinct between chats.
     */
    @Test
    fun `responses opt-in keeps a stable per-conversation prompt_cache_key`() {
        val a1 = capture(model("gpt-5.3"), ThinkingLevel.OFF, responses = true, firstUserText = "chat A")
        val a2 = capture(model("gpt-5.3"), ThinkingLevel.OFF, responses = true, firstUserText = "chat A")
        val b = capture(model("gpt-5.3"), ThinkingLevel.OFF, responses = true, firstUserText = "chat B")
        assertTrue(a1.getString("prompt_cache_key").startsWith("minis-"))
        assertEquals("same first user message → same key", a1.getString("prompt_cache_key"), a2.getString("prompt_cache_key"))
        assertNotEquals("different chat → different key", a1.getString("prompt_cache_key"), b.getString("prompt_cache_key"))
    }

    /** T-responses-include: `include: reasoning.encrypted_content` is ChatGPT-backend-only. */
    @Test
    fun `api-key responses path sends no include field`() {
        val body = capture(model("gpt-5.3"), ThinkingLevel.HIGH, responses = true)
        assertFalse("include is Codex-OAuth-only; third-party Responses relays 400 on it: $body", body.has("include"))
    }

    // ============================================================ Venice root thinking

    /**
     * GH#86: Venice's ChatCompletionRequest is `additionalProperties:false`, so a
     * root `thinking` key is rejected during schema validation. The deepseek id
     * is the exact trigger — its vendor rule WOULD emit `thinking:{}` elsewhere.
     */
    @Test
    fun `venice never receives a root thinking key`() {
        for (level in listOf(ThinkingLevel.OFF, ThinkingLevel.HIGH)) {
            val body = capture(
                model("deepseek-v4-flash", reasoningEffortValues = listOf("low", "high", "max")),
                level,
                path = "/api.venice.ai/api/v1",
            )
            assertFalse("root thinking on Venice at $level: $body", body.has("thinking"))
        }
        val on = capture(
            model("deepseek-v4-flash", reasoningEffortValues = listOf("low", "high", "max")),
            ThinkingLevel.HIGH,
            path = "/api.venice.ai/api/v1",
        )
        assertEquals("Venice takes the tier as root reasoning_effort", "high", on.getString("reasoning_effort"))
    }

    // ============================================================ reasoning_effort placement

    /** GH#177 — Chat Completions: ROOT `reasoning_effort`, no `reasoning` object. */
    @Test
    fun `chat completions puts reasoning_effort at the root`() {
        val body = capture(model("gpt-5.3", reasoningEffortValues = listOf("low", "medium", "high")), ThinkingLevel.HIGH)
        assertEquals("high", body.getString("reasoning_effort"))
        assertFalse("no nested reasoning object on chat: $body", body.has("reasoning"))
    }

    /** GH#177 — Responses: nested `reasoning.effort`, no root `reasoning_effort`. */
    @Test
    fun `responses api nests the effort under reasoning`() {
        val body = capture(model("gpt-5.3", reasoningEffortValues = listOf("low", "medium", "high")), ThinkingLevel.HIGH, responses = true)
        assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
        assertFalse("no root reasoning_effort on Responses: $body", body.has("reasoning_effort"))
    }

    /** OpenRouter speaks Chat Completions but takes the NESTED shape. */
    @Test
    fun `openrouter chat nests the effort under reasoning`() {
        val body = capture(
            model("anthropic/claude-sonnet-4-6", reasoningEffortValues = listOf("low", "medium", "high")),
            ThinkingLevel.MEDIUM,
            path = "/openrouter.ai/api/v1",
        )
        assertEquals("medium", body.getJSONObject("reasoning").getString("effort"))
        assertFalse(body.has("reasoning_effort"))
    }

    /** Anthropic adaptive (4.6+): `thinking.type=adaptive` + `output_config.effort`. */
    @Test
    fun `anthropic adaptive puts the effort under output_config`() {
        val body = captureAnthropic(model("claude-opus-4-8"), ThinkingLevel.HIGH)
        assertEquals("adaptive", body.getJSONObject("thinking").getString("type"))
        assertEquals("high", body.getJSONObject("output_config").getString("effort"))
        assertFalse("reasoning_effort is an OpenAI field: $body", body.has("reasoning_effort"))
        assertFalse(body.has("reasoning"))
        assertFalse("adaptive models reject temperature", body.has("temperature"))
    }

    /** Legacy Anthropic (≤4.5): `thinking.type=enabled` + `budget_tokens`, no effort anywhere. */
    @Test
    fun `anthropic legacy uses budget_tokens and no effort`() {
        val body = captureAnthropic(model("claude-haiku-4-5"), ThinkingLevel.MEDIUM)
        val thinking = body.getJSONObject("thinking")
        assertEquals("enabled", thinking.getString("type"))
        assertTrue(thinking.getInt("budget_tokens") in 1 until 4096)
        assertFalse(body.has("output_config"))
        assertEquals("legacy thinking requires temperature=1", 1, body.getInt("temperature"))
    }

    // ============================================================ Mistral

    /** GH#87 — Mistral takes NOTHING, on either API flavour. */
    @Test
    fun `mistral gets no thinking control on either path`() {
        val watched = listOf("reasoning_effort", "reasoning", "thinking", "enable_thinking", "thinking_budget", "extra_body")
        val chat = capture(model("mistral-large-latest", reasoningEffortValues = listOf("low", "high")), ThinkingLevel.HIGH, path = "/mistral.ai/v1")
        assertEquals(emptyList<String>(), watched.filter { chat.has(it) })
        val resp = capture(model("mistral-large-latest", reasoningEffortValues = listOf("low", "high")), ThinkingLevel.HIGH, path = "/mistral.ai/v1", responses = true)
        assertEquals(emptyList<String>(), watched.filter { resp.has(it) })
    }

    // ============================================================ OpenRouter cache_control

    /** GH#191 — top-level `cache_control` only for OpenRouter + `anthropic/` ids. */
    @Test
    fun `openrouter cache_control is scoped to anthropic ids`() {
        val claude = capture(model("anthropic/claude-sonnet-4-6"), ThinkingLevel.OFF, path = "/openrouter.ai/api/v1")
        assertEquals("ephemeral", claude.getJSONObject("cache_control").getString("type"))
        val gpt = capture(model("openai/gpt-4o", supportsReasoning = false), ThinkingLevel.OFF, path = "/openrouter.ai/api/v1")
        assertFalse("non-anthropic OpenRouter model must not carry cache_control: $gpt", gpt.has("cache_control"))
        val direct = capture(model("anthropic/claude-sonnet-4-6"), ThinkingLevel.OFF, path = "/v1")
        assertFalse("the field is OpenRouter-only: $direct", direct.has("cache_control"))
    }

    /** OpenRouter takes `max_tokens`; everyone else on this path takes `max_completion_tokens`. */
    @Test
    fun `max tokens field name follows the gateway`() {
        val or = capture(model("openai/gpt-4o", supportsReasoning = false), ThinkingLevel.OFF, path = "/openrouter.ai/api/v1")
        assertEquals(4096, or.getInt("max_tokens"))
        assertFalse(or.has("max_completion_tokens"))
        val plain = capture(model("gpt-4o", supportsReasoning = false), ThinkingLevel.OFF)
        assertEquals(4096, plain.getInt("max_completion_tokens"))
        assertFalse(plain.has("max_tokens"))
    }
}
