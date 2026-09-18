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
 * [GH OpenMinis#361] Cerebras' Chat Completions message schema rejects the
 * non-standard `messages[].assistant.reasoning_content` property:
 *
 *   HTTP 400 `messages.2.assistant.reasoning_content: property ... is unsupported`
 *   (code `wrong_api_format`)
 *
 * The FIRST turn succeeds; every later turn fails because the history echoes the
 * reasoning captured from the previous answer. This is a schema constraint, not
 * a capability one, so it cannot be inferred from model metadata — the vendor is
 * identified by base URL, the same mechanism as the Mistral guard.
 *
 * `isCerebras` is `basePath.contains("cerebras")`, so pointing MockWebServer at
 * a path containing that literal exercises the real production predicate
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

    /** A reasoning-capable model, so the echo gate would otherwise be ON. */
    private val reasoningModel = LLMModel(
        id = "gpt-oss-120b",
        displayName = "GPT-OSS 120B",
        provider = "CPA",
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

    private fun capture(basePath: String): JSONObject {
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
            model = reasoningModel,
            basePath = basePath,
        )
        runCatching { runBlocking {
            provider.sendMessageClamped(
                messages = historyWithReasoning(),
                systemPrompt = null,
                maxTokens = 1024,
                temperature = null,
                imageParts = emptyList(),
                tools = emptyList(),
                thinkingLevel = ThinkingLevel.MEDIUM,
            )
        } }
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
        val body = capture(server.url("/cerebras/v1").toString().trimEnd('/'))
        assertFalse(
            "reasoning_content must not be sent to Cerebras (400 is unsupported): $body",
            anyMessageHasReasoning(body),
        )
    }

    @Test
    fun `cerebras detection is case-insensitive`() {
        // Hosts are case-insensitive and the predicate lowercases before the
        // contains() test; an uppercased URL must not slip past the guard.
        val body = capture(server.url("/API.CEREBRAS.AI/v1").toString().trimEnd('/'))
        assertFalse(
            "uppercase cerebras host must still suppress reasoning_content: $body",
            anyMessageHasReasoning(body),
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
}
