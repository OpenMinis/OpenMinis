package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-cerebras-reasoning-400] GH OpenMinis#361.
 *
 * Cerebras' assistant message is a CLOSED schema, exactly like Mistral's
 * ([MistralReasoningFieldTest], #87). Any prior assistant turn carrying
 * `reasoning_content` is rejected with
 * `400 … property 'messages.N.assistant.reasoning_content' is unsupported`,
 * which is why the reporter saw turn 1 succeed and turn 2 fail every time —
 * turn 1 has no assistant history to echo.
 *
 * The gate cannot be capability-driven, for the same reason as Mistral's:
 * MiMo/DeepSeek REQUIRE the field's presence on multi-turn history while
 * Cerebras FORBIDS it, and neither advertises the difference via /v1/models.
 * Hence the base-URL vendor flag under test.
 *
 * `isCerebras` is `basePath.contains("cerebras.ai")`, so pointing MockWebServer
 * at a path containing that literal exercises the real production predicate
 * without needing a Cerebras key or network access.
 */
class CerebrasReasoningFieldTest {

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

    /**
     * One of the two models the issue names, and one the bundled models-dev
     * catalog lists under the `cerebras` provider. Reasoning-capable, so the
     * echo gate would otherwise be ON.
     */
    private val reasoningModel = LLMModel(
        id = "qwen-3.8-27b",
        displayName = "Qwen 3.8 27B",
        provider = "Cerebras",
        supportsReasoning = true,
    )

    /** History with a prior assistant turn that captured reasoning — the 400 trigger. */
    private fun historyWithReasoning(): List<LLMMessage> = listOf(
        LLMMessage(LLMMessage.Role.USER, "first question"),
        LLMMessage(
            LLMMessage.Role.ASSISTANT,
            "first answer",
        ).copy(reasoningContent = "some captured chain of thought"),
        LLMMessage(LLMMessage.Role.USER, "second question"),
    )

    private fun capture(basePath: String, model: LLMModel = reasoningModel): JSONObject {
        // Enqueue several identical responses: the provider may retry, and a
        // drained queue surfaces as a confusing "empty response" TransientError
        // rather than the assertion we actually care about. We only ever read
        // the FIRST recorded request below.
        val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        repeat(4) {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(ok),
            )
        }
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = model,
            basePath = basePath,
        )
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = historyWithReasoning(),
                    systemPrompt = null,
                    maxTokens = 1024,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = ThinkingLevel.MEDIUM,
                )
            }
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    private fun anyMessageHasReasoning(body: JSONObject): Boolean {
        val msgs = body.getJSONArray("messages")
        for (i in 0 until msgs.length()) {
            if (msgs.getJSONObject(i).has("reasoning_content")) return true
        }
        return false
    }

    @Test
    fun `cerebras endpoint never sends reasoning_content`() {
        val body = capture(server.url("/cerebras.ai/v1").toString().trimEnd('/'))
        assertFalse(
            "reasoning_content must not be sent to Cerebras (400 unsupported): $body",
            anyMessageHasReasoning(body),
        )
    }

    @Test
    fun `cerebras detection is case-insensitive`() {
        // Hosts are case-insensitive and iOS lowercases before the same
        // contains() test; an uppercased URL must not slip past the guard.
        val body = capture(server.url("/API.CEREBRAS.AI/v1").toString().trimEnd('/'))
        assertFalse(
            "uppercase cerebras.ai must still suppress reasoning_content: $body",
            anyMessageHasReasoning(body),
        )
    }

    @Test
    fun `cerebras hosted qwen does not receive enable_thinking`() {
        // The cross case (cerebras x qwen). `qwen-3.8-27b` matches the `*qwen*`
        // model-name rule, which emits Qwen's native enable_thinking — a field
        // Cerebras does not accept. The endpoint rule is registered ABOVE that
        // one, and stage A stops at the first scope match, so position is the
        // behaviour. Cerebras' documented control is root reasoning_effort,
        // which is exactly what the bundled catalog declares for this model.
        val body = capture(server.url("/cerebras.ai/v1").toString().trimEnd('/'))
        assertFalse(
            "enable_thinking must not be sent to Cerebras: $body",
            body.has("enable_thinking"),
        )
        assertFalse(
            "thinking_budget must not be sent to Cerebras: $body",
            body.has("thinking_budget"),
        )
        assertFalse(
            "the DashScope extra_body envelope must not be sent to Cerebras: $body",
            body.has("extra_body"),
        )
    }

    @Test
    fun `non-cerebras endpoint still echoes reasoning_content`() {
        // Negative control: the suppression must be scoped to Cerebras only.
        // MiMo / DeepSeek return 400 when multi-turn history LACKS this field,
        // so over-broad suppression would break them.
        val body = capture(server.url("/v1").toString().trimEnd('/'))
        assertTrue(
            "reasoning_content should still be echoed for non-Cerebras vendors: $body",
            anyMessageHasReasoning(body),
        )
    }

    @Test
    fun `non-cerebras qwen keeps its native thinking mechanism`() {
        // The other half of the negative control: a qwen-named model on some
        // other endpoint must keep matching the `*qwen*` rule. If the endpoint
        // rule were registered unscoped, this would silently lose its thinking
        // mechanism — the exact class of regression the resolver's
        // "ORDER IS LOAD-BEARING" note records.
        val body = capture(server.url("/v1").toString().trimEnd('/'))
        assertTrue(
            "a non-Cerebras qwen should still get its native thinking field: $body",
            body.has("enable_thinking") || body.has("extra_body"),
        )
    }
}
