package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAIProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // -- sendMessage response parsing --

    @Test
    fun `sendMessage parses ChatCompletions response`() = runBlocking {
        val responseBody = """
        {
            "choices": [{
                "message": {"role": "assistant", "content": "Hello from GPT!"},
                "finish_reason": "stop"
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 5}
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))

        val response = provider.sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "Hi")),
            null, 1024,
        )

        assertEquals("Hello from GPT!", response.text)
        assertEquals("stop", response.stopReason)
        assertEquals(10, response.usage?.inputTokens)
        assertEquals(5, response.usage?.outputTokens)
    }

    @Test
    fun `sendMessage parses cached tokens from prompt_tokens_details`() = runBlocking {
        val responseBody = """
        {
            "choices": [{"message": {"content": "ok"}, "finish_reason": "stop"}],
            "usage": {
                "prompt_tokens": 100,
                "completion_tokens": 10,
                "prompt_tokens_details": {"cached_tokens": 50}
            }
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))
        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)

        // [T-usage-cache-hit-rate] prompt_tokens 含缓存，inputTokens 必须是
        // fresh-only（100 - 50 = 50），缓存只住在 cacheReadInputTokens 里。
        // 旧断言期望 100（全量）把重复计数钉成了契约，使用量页
        // totalInput = input + cacheRead 把缓存算两遍、命中率被稀释。
        assertEquals(50, response.usage?.inputTokens)
        assertEquals(10, response.usage?.outputTokens)
        assertEquals(50, response.usage?.cacheReadInputTokens)
        assertNull(response.usage?.cacheCreationInputTokens)
        // 上下文大小仍是全量 prompt —— 这是另一个字段的职责。
        assertEquals(100, response.usage?.latestContextTokens)
    }

    @Test
    fun `sendMessage returns null cacheReadInputTokens when zero`() = runBlocking {
        val responseBody = """
        {
            "choices": [{"message": {"content": "ok"}, "finish_reason": "stop"}],
            "usage": {
                "prompt_tokens": 10,
                "completion_tokens": 5,
                "prompt_tokens_details": {"cached_tokens": 0}
            }
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))
        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
        assertNull(response.usage?.cacheReadInputTokens)
    }

    @Test
    fun `sendMessage handles empty choices`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":0}}"""))

        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
        assertEquals("", response.text)
        assertNull(response.stopReason)
    }

    // -- Request construction --

    @Test
    fun `sendMessage includes Bearer auth header`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":0,"completion_tokens":0}}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertTrue(request.path!!.contains("/chat/completions"))
    }

    @Test
    fun `sendMessage includes system prompt as system message`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":0,"completion_tokens":0}}"""))

        provider.sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "test")),
            "You are helpful", 100,
        )

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val messages = body.getJSONArray("messages")
        // System message should be first
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("You are helpful", messages.getJSONObject(0).getString("content"))
        // User message follows
        assertEquals("user", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun `sendMessage omits system message when null`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":0,"completion_tokens":0}}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
    }

    @Test
    fun `sendMessage includes temperature when set`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":0,"completion_tokens":0}}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100, temperature = 0.8)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(0.8, body.getDouble("temperature"), 0.001)
    }

    @Test
    fun `sendMessage omits temperature when null`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":0,"completion_tokens":0}}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100, temperature = null)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertTrue(!body.has("temperature"))
    }

    @Test
    fun `sendMessage uses max_completion_tokens for OpenAI`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":0,"completion_tokens":0}}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 2048)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(2048, body.getInt("max_completion_tokens"))
        assertTrue(!body.has("max_tokens"))
    }

    @Test
    fun `sendMessage sets stream false for non-streaming`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":0,"completion_tokens":0}}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(false, body.getBoolean("stream"))
        assertTrue(!body.has("stream_options"))
    }

    // -- Streaming --

    @Test
    fun `streamMessage parses SSE events with DONE`() = runBlocking {
        val sseBody = buildString {
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}")
            appendLine()
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}")
            appendLine()
            appendLine("data: {\"choices\":[{\"delta\":{}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }

        server.enqueue(
            MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream")
        )

        val chunks = provider.streamMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "Hi")),
            null, 1024,
        ).toList()

        assertTrue(chunks.any { it is LLMStreamChunk.Started })
        val texts = chunks.filterIsInstance<LLMStreamChunk.Text>()
        assertEquals("Hello", texts[0].text)
        assertEquals(" world", texts[1].text)

        val usageChunks = chunks.filterIsInstance<LLMStreamChunk.Usage>()
        assertEquals(1, usageChunks.size)
        assertEquals(5, usageChunks[0].usage.inputTokens)
        assertEquals(2, usageChunks[0].usage.outputTokens)

        assertTrue(chunks.any { it is LLMStreamChunk.Finished })
    }

    @Test
    fun `streamMessage includes stream_options with include_usage`() = runBlocking {
        val sseBody = buildString {
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }
        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertTrue(body.getBoolean("stream"))
        val streamOptions = body.getJSONObject("stream_options")
        assertTrue(streamOptions.getBoolean("include_usage"))
    }

    @Test
    fun `streamMessage includes temperature in request`() = runBlocking {
        val sseBody = buildString {
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }
        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024, temperature = 1.0).toList()

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(1.0, body.getDouble("temperature"), 0.001)
    }

    @Test
    fun `streamMessage parses cached tokens in usage`() = runBlocking {
        val sseBody = buildString {
            appendLine("""data: {"choices":[{"delta":{"content":"ok"}}]}""")
            appendLine()
            appendLine("""data: {"choices":[{"delta":{}}],"usage":{"prompt_tokens":100,"completion_tokens":10,"prompt_tokens_details":{"cached_tokens":50}}}""")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }

        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()

        val usageChunks = chunks.filterIsInstance<LLMStreamChunk.Usage>()
        assertEquals(1, usageChunks.size)
        assertEquals(50, usageChunks[0].usage.cacheReadInputTokens)
    }

    // -- Error handling --

    @Test(expected = LLMError.InvalidApiKey::class)
    fun `sendMessage throws InvalidApiKey on 401`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        Unit
    }

    @Test(expected = LLMError.InvalidApiKey::class)
    fun `sendMessage throws InvalidApiKey on 403`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        Unit
    }

    @Test(expected = LLMError.RateLimited::class)
    fun `sendMessage throws RateLimited on 429`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("Rate limited"))
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        Unit
    }

    @Test
    fun `sendMessage throws ProviderError on 429 with no available channel`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("无可用渠道"))
        try {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        } catch (e: LLMError.ProviderError) {
            assertTrue(e.detail.contains("[429]"))
            assertTrue(e.detail.contains("无可用渠道"))
            return@runBlocking
        }
        throw AssertionError("Expected ProviderError")
    }

    @Test
    fun `sendMessage parses error body for ProviderError`() = runBlocking {
        val errorBody = """{"error":{"message":"The model does not exist","type":"invalid_request_error"}}"""
        server.enqueue(MockResponse().setResponseCode(400).setBody(errorBody))

        try {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        } catch (e: LLMError.ProviderError) {
            assertTrue(e.message!!.contains("400"))
            assertTrue(e.message!!.contains("The model does not exist"))
            return@runBlocking
        }
        throw AssertionError("Expected ProviderError")
    }

    // -- Provider metadata --

    @Test
    fun `provider name is OpenAI`() {
        assertEquals("OpenAI", provider.name)
    }

    @Test
    fun `provider model can be changed`() {
        provider.model = LLMModel.gpt4o
        assertEquals(LLMModel.gpt4o, provider.model)
    }

    // -- [T-reasoning-effort-data-driven] reasoning_effort on the wire --
    //
    // Regression guard for the GLM-via-relay report: a model whose id contains
    // "glm" used to hit a hardcoded deepseek/glm/kimi/minimax skip list and get
    // NO thinking field at all, while an otherwise identical non-matching id
    // (Hermes) got reasoning_effort. These assert the ACTUAL request body
    // captured off MockWebServer, not the injector in isolation.

    /** Drive one request through the real stack and return the parsed body. */
    private fun captureBody(model: LLMModel, level: ThinkingLevel): JSONObject {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"ok"},
                   "finish_reason":"stop"}]}""".trimIndent(),
            ),
        )
        provider.model = model
        runBlocking {
            provider.sendMessageClamped(
                messages = listOf(LLMMessage(LLMMessage.Role.USER, "Hi")),
                systemPrompt = null,
                maxTokens = 1024,
                temperature = null,
                imageParts = emptyList(),
                tools = emptyList(),
                thinkingLevel = level,
            )
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    @Test
    fun `glm with declared effort tiers sends reasoning_effort`() {
        // zhipuai's real catalog declaration for glm-5.2.
        val glm = LLMModel(
            id = "glm-5.2", displayName = "GLM 5.2", provider = "CPA",
            supportsReasoning = true,
            reasoningEffortValues = listOf("high", "max"),
        )
        val body = captureBody(glm, ThinkingLevel.XHIGH)
        // Was absent entirely before the fix.
        assertTrue(
            "reasoning_effort missing for GLM: $body",
            body.has("reasoning_effort"),
        )
        // xhigh is NOT in ["high","max"] — must clamp DOWN to high, not 400.
        assertEquals("high", body.getString("reasoning_effort"))
    }

    @Test
    fun `glm without declared effort tiers keeps the legacy skip`() {
        val glm = LLMModel(
            id = "glm-4.5", displayName = "GLM 4.5", provider = "CPA",
            supportsReasoning = true,
            reasoningEffortValues = null,
        )
        val body = captureBody(glm, ThinkingLevel.XHIGH)
        assertTrue(
            "undeclared GLM should stay on the native self-reasoning path: $body",
            !body.has("reasoning_effort"),
        )
    }

    @Test
    fun `hermes on the same relay still sends reasoning_effort`() {
        val hermes = LLMModel(
            id = "hermes-4-405b", displayName = "Hermes 4", provider = "CPA",
            supportsReasoning = true,
        )
        val body = captureBody(hermes, ThinkingLevel.XHIGH)
        assertEquals("xhigh", body.getString("reasoning_effort"))
    }

    @Test
    fun `clampEffort snaps onto declared tiers`() {
        // Down to the nearest declared tier at or below the request.
        assertEquals("high", provider.clampEffort("xhigh", listOf("high", "max")))
        assertEquals("high", provider.clampEffort("xhigh", listOf("low", "medium", "high")))
        // Exact matches pass through.
        assertEquals("xhigh", provider.clampEffort("xhigh", listOf("high", "xhigh")))
        // Nothing at-or-below: step up to the lowest declared tier.
        assertEquals("high", provider.clampEffort("low", listOf("high", "max")))
        // No declaration: untouched (pre-existing behavior).
        assertEquals("xhigh", provider.clampEffort("xhigh", null))
        assertEquals("xhigh", provider.clampEffort("xhigh", emptyList()))
    }
}
