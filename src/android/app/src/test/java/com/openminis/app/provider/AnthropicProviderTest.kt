package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.anthropic.AnthropicProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnthropicProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: AnthropicProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = AnthropicProvider(
            apiKey = "test-key",
            model = LLMModel.claudeHaiku45,
            basePath = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // -- sendMessage response parsing --

    @Test
    fun `sendMessage parses response correctly`() = runBlocking {
        val responseBody = """
        {
            "content": [{"type": "text", "text": "Hello from Claude!"}],
            "stop_reason": "end_turn",
            "usage": {"input_tokens": 10, "output_tokens": 5}
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody).setHeader("Content-Type", "application/json"))

        val messages = listOf(LLMMessage(LLMMessage.Role.USER, "Hi"))
        val response = provider.sendMessage(messages, null, 1024)

        assertEquals("Hello from Claude!", response.text)
        assertEquals("end_turn", response.stopReason)
        assertEquals(10, response.usage?.inputTokens)
        assertEquals(5, response.usage?.outputTokens)
    }

    @Test
    fun `sendMessage parses multiple content blocks`() = runBlocking {
        val responseBody = """
        {
            "content": [
                {"type": "text", "text": "Hello "},
                {"type": "text", "text": "world!"}
            ],
            "stop_reason": "end_turn",
            "usage": {"input_tokens": 10, "output_tokens": 5}
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))

        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
        assertEquals("Hello world!", response.text)
    }

    @Test
    fun `sendMessage parses cache usage tokens`() = runBlocking {
        val responseBody = """
        {
            "content": [{"type": "text", "text": "ok"}],
            "stop_reason": "end_turn",
            "usage": {
                "input_tokens": 100,
                "output_tokens": 20,
                "cache_creation_input_tokens": 50,
                "cache_read_input_tokens": 30
            }
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))

        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
        assertEquals(50, response.usage?.cacheCreationInputTokens)
        assertEquals(30, response.usage?.cacheReadInputTokens)
    }

    @Test
    fun `sendMessage returns null cache tokens when zero`() = runBlocking {
        val responseBody = """
        {
            "content": [{"type": "text", "text": "ok"}],
            "stop_reason": "end_turn",
            "usage": {
                "input_tokens": 10,
                "output_tokens": 5,
                "cache_creation_input_tokens": 0,
                "cache_read_input_tokens": 0
            }
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))

        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
        assertNull(response.usage?.cacheCreationInputTokens)
        assertNull(response.usage?.cacheReadInputTokens)
    }

    // -- Request construction --

    @Test
    fun `sendMessage includes correct headers`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[],"usage":{"input_tokens":0,"output_tokens":0}}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), "system", 100)

        val request = server.takeRequest()
        // MockWebServer uses custom base URL, so Bearer auth is used
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        assertEquals("POST", request.method)
        assertTrue(request.path!!.contains("/v1/messages"))
    }

    @Test
    fun `sendMessage includes system prompt in body`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[],"usage":{"input_tokens":0,"output_tokens":0}}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), "You are helpful", 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        // System prompt is now a JSONArray with cache_control
        val system = body.getJSONArray("system")
        assertEquals(1, system.length())
        assertEquals("You are helpful", system.getJSONObject(0).getString("text"))
    }

    @Test
    fun `sendMessage omits system when null`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[],"usage":{"input_tokens":0,"output_tokens":0}}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertTrue(!body.has("system"))
    }

    @Test
    fun `sendMessage includes temperature when set`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[],"usage":{"input_tokens":0,"output_tokens":0}}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100, temperature = 0.7)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(0.7, body.getDouble("temperature"), 0.001)
    }

    @Test
    fun `sendMessage omits temperature when null`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[],"usage":{"input_tokens":0,"output_tokens":0}}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100, temperature = null)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertTrue(!body.has("temperature"))
    }

    @Test
    fun `sendMessage uses correct model and max_tokens`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[],"usage":{"input_tokens":0,"output_tokens":0}}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 2048)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(LLMModel.claudeHaiku45.id, body.getString("model"))
        assertEquals(2048, body.getInt("max_tokens"))
    }

    @Test
    fun `sendMessage maps roles correctly`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[],"usage":{"input_tokens":0,"output_tokens":0}}"""))

        provider.sendMessage(
            listOf(
                LLMMessage(LLMMessage.Role.USER, "hello"),
                LLMMessage(LLMMessage.Role.ASSISTANT, "hi"),
                LLMMessage(LLMMessage.Role.USER, "bye"),
            ), null, 100
        )

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val messages = body.getJSONArray("messages")
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        assertEquals("user", messages.getJSONObject(2).getString("role"))
    }

    // -- Streaming --

    @Test
    fun `streamMessage parses SSE events`() = runBlocking {
        val sseBody = buildString {
            appendLine("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":5}}}")
            appendLine()
            appendLine("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}")
            appendLine()
            appendLine("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\" world\"}}")
            appendLine()
            appendLine("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}")
            appendLine()
        }

        server.enqueue(
            MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream")
        )

        val messages = listOf(LLMMessage(LLMMessage.Role.USER, "Hi"))
        val chunks = provider.streamMessage(messages, null, 1024).toList()

        assertTrue(chunks.any { it is LLMStreamChunk.Started })
        val textChunks = chunks.filterIsInstance<LLMStreamChunk.Text>()
        assertEquals("Hello", textChunks[0].text)
        assertEquals(" world", textChunks[1].text)

        val usageChunks = chunks.filterIsInstance<LLMStreamChunk.Usage>()
        assertTrue(usageChunks.isNotEmpty())
        // message_start usage
        assertEquals(5, usageChunks[0].usage.inputTokens)
        // message_delta usage
        assertEquals(3, usageChunks[1].usage.outputTokens)

        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>()
        assertEquals(1, finished.size)
        assertEquals("end_turn", finished[0].stopReason)
    }

    @Test
    fun `streamMessage ignores non-data lines`() = runBlocking {
        val sseBody = buildString {
            appendLine("event: message_start")
            appendLine("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1}}}")
            appendLine()
            appendLine("event: content_block_delta")
            appendLine("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}")
            appendLine()
            appendLine(": comment line")
            appendLine("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}")
            appendLine()
        }

        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()
        val texts = chunks.filterIsInstance<LLMStreamChunk.Text>()
        assertEquals(1, texts.size)
        assertEquals("ok", texts[0].text)
    }

    @Test
    fun `streamMessage includes temperature in request`() = runBlocking {
        val sseBody = buildString {
            appendLine("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1}}}")
            appendLine()
            appendLine("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}")
            appendLine()
        }
        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024, temperature = 0.5).toList()

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(0.5, body.getDouble("temperature"), 0.001)
        assertTrue(body.getBoolean("stream"))
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
        server.enqueue(MockResponse().setResponseCode(429).setBody("Too many requests"))
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        Unit
    }

    @Test
    fun `sendMessage parses error body for ProviderError`() = runBlocking {
        val errorBody = """{"error":{"type":"invalid_request_error","message":"max_tokens too large"}}"""
        server.enqueue(MockResponse().setResponseCode(400).setBody(errorBody))

        try {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        } catch (e: LLMError.ProviderError) {
            assertTrue(e.message!!.contains("invalid_request_error"))
            assertTrue(e.message!!.contains("max_tokens too large"))
            return@runBlocking
        }
        throw AssertionError("Expected ProviderError")
    }

    // -- Claude version parsing (temperature rejection / thinking) --

    // [T-anthropic-temp-claude5-android] Single-segment ids (claude-fable-5)
    // must parse as (major, 0): previously the parser required a major-minor
    // PAIR, returned null for the whole 5-series, temperature was sent, and
    // the API answered 400 "temperature is deprecated for this model".
    @Test
    fun `modelRejectsTemperature handles single-segment and paired Claude versions`() {
        // 5-series single-segment ids — were null pre-fix, now (5,0).
        assertTrue(AnthropicProvider.modelRejectsTemperature("claude-fable-5"))
        assertTrue(AnthropicProvider.modelRejectsTemperature("claude-opus-5"))
        // [T-anthropic-fable51-android] Fable 5.1 parses as the PAIR (5,1),
        // not (5,0) — a different branch of the optional-minor group than the
        // single-segment ids above, so it is worth asserting separately.
        assertTrue(AnthropicProvider.modelRejectsTemperature("claude-fable-5-1"))
        // major=5 with a date suffix in the minor slot — major wins.
        assertTrue(AnthropicProvider.modelRejectsTemperature("claude-sonnet-5-20260301"))
        // Existing 4.6+ pairs — unchanged.
        assertTrue(AnthropicProvider.modelRejectsTemperature("claude-opus-4-8"))
        assertTrue(AnthropicProvider.modelRejectsTemperature("claude-sonnet-4-6-thinking"))
        assertTrue(AnthropicProvider.modelRejectsTemperature("anthropic/claude-opus-4.7"))
        // The greedy optional minor must keep the 3-5 PAIR winning — (3,5), not (3,0).
        assertEquals(false, AnthropicProvider.modelRejectsTemperature("claude-3-5-sonnet"))
        // (3,0) now parses where it used to be null — same outcome (sends temperature).
        assertEquals(false, AnthropicProvider.modelRejectsTemperature("claude-3-haiku-20240307"))
        // Non-Claude ids stay null — temperature sent.
        assertEquals(false, AnthropicProvider.modelRejectsTemperature("gpt-4o"))
    }

    @Test
    fun `supportsThinking and adaptive thinking cover the 5-series`() {
        assertTrue(AnthropicProvider.modelUsesAdaptiveThinking("claude-fable-5"))
        assertTrue(AnthropicProvider.supportsThinking("claude-fable-5"))
        assertTrue(AnthropicProvider.supportsThinking("claude-opus-5"))
        // [T-anthropic-fable51-android] Fable 5.1 is adaptive-thinking only —
        // it must take the SAME branch as the rest of the 5-series. If it ever
        // fell through to the hybrid path, a manual thinking budget would be
        // sent and the server would reject the request.
        assertTrue(AnthropicProvider.modelUsesAdaptiveThinking("claude-fable-5-1"))
        assertTrue(AnthropicProvider.supportsThinking("claude-fable-5-1"))
        // Unchanged below the cutoffs.
        assertEquals(false, AnthropicProvider.modelUsesAdaptiveThinking("claude-3-5-sonnet"))
        assertEquals(false, AnthropicProvider.supportsThinking("claude-3-5-sonnet"))
    }

    // -- Fable 5.1 catalog entry --

    @Test
    fun `Fable 5point1 is catalogued with Fable 5's specs and listed first`() {
        // [T-anthropic-fable51-android] The id is what every capability check
        // derives from (parseClaudeVersion), so a typo here would silently
        // disable adaptive thinking and re-enable temperature.
        val m = LLMModel.claudeFable51
        assertEquals("claude-fable-5-1", m.id)
        assertEquals("Claude Fable 5.1", m.displayName)
        assertEquals("Anthropic", m.provider)
        // Same spec as Fable 5 — pinned against that entry rather than
        // repeating literals, so the two cannot drift apart.
        assertEquals(LLMModel.claudeFable5.contextWindow, m.contextWindow)
        assertEquals(LLMModel.claudeFable5.maxOutputTokens, m.maxOutputTokens)
        assertEquals(LLMModel.claudeFable5.supportsReasoning, m.supportsReasoning)
        // Must be reachable from the picker, newest first (iOS ordering).
        assertTrue(LLMModel.allAnthropic.contains(m))
        assertEquals(m, LLMModel.allAnthropic.first())
    }

    // -- Provider metadata --

    @Test
    fun `provider name is Anthropic`() {
        assertEquals("Anthropic", provider.name)
    }

    @Test
    fun `provider model can be changed`() {
        provider.model = LLMModel.claudeHaiku45
        assertEquals(LLMModel.claudeHaiku45, provider.model)
    }

    // -- Thinking budget (legacy budget_tokens path) --

    @Test
    fun `thinking budget stays strictly below max tokens`() {
        // Anthropic rejects budget_tokens == max_tokens with a 400. Every level
        // that would clamp to exactly maxTokens must pull back to maxTokens - 1.
        val maxTokens = 40_000
        // HIGH: min(maxTokens, 65536) == maxTokens here → must be maxTokens-1.
        assertEquals(maxTokens - 1, AnthropicProvider.thinkingBudget(maxTokens, ThinkingLevel.HIGH))
        // Top tiers take the full budget → also pulled back by one.
        for (level in listOf(ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA)) {
            val budget = AnthropicProvider.thinkingBudget(maxTokens, level)
            assertTrue("$level budget $budget must be < $maxTokens", budget < maxTokens)
            assertEquals(maxTokens - 1, budget)
        }
    }

    @Test
    fun `thinking budget keeps sub-ceiling levels intact`() {
        // When the cap is genuinely below maxTokens the value is unchanged.
        val maxTokens = 100_000
        assertEquals(0, AnthropicProvider.thinkingBudget(maxTokens, ThinkingLevel.OFF))
        assertEquals(8192, AnthropicProvider.thinkingBudget(maxTokens, ThinkingLevel.LOW))
        assertEquals(32768, AnthropicProvider.thinkingBudget(maxTokens, ThinkingLevel.MEDIUM))
        // HIGH caps at 65536, well under 100k → unchanged.
        assertEquals(65536, AnthropicProvider.thinkingBudget(maxTokens, ThinkingLevel.HIGH))
    }

    // -- ThinkingLevelCatalog: Claude Opus matching --

    @Test
    fun `catalog matches all claude opus 4x variants at MAX`() {
        // The real LLMModel.id uses hyphenated minor versions; the old dotted
        // startsWith rules never matched. Both spellings must resolve to MAX,
        // and future 4.x versions (4.8+) too.
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("claude-opus-4-8"))
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("claude-opus-4-6"))
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("claude-opus-4.7"))
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("claude-opus-4-9-preview"))
        // Non-Opus / older Claude stays uncovered (falls through to default).
        assertNull(ThinkingLevelCatalog.declaredMaxLevel("claude-3-5-sonnet"))
    }

    // -- Interleaved thinking-block echo (issue #70) --

    /** A DeepSeek-style compat model: reasons, needs interleaved (unsigned)
     *  reasoning echoed back. Served from the MockWebServer (custom endpoint). */
    private val deepseekCompatModel = LLMModel(
        id = "deepseek-v4-flash",
        displayName = "DeepSeek V4 Flash",
        provider = "Anthropic",
        supportsReasoning = true,
        interleavedReasoningField = "reasoning_content",
    )

    /** Multi-turn tool history: user → assistant(thinking + tool_use) →
     *  user(tool_result) → user(follow-up). The assistant turn carries
     *  reasoningContent captured on the prior round. */
    private fun toolHistory(): List<LLMMessage> = listOf(
        LLMMessage(LLMMessage.Role.USER, "what's the weather?"),
        LLMMessage(
            LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolUse("toolu_1", "get_weather", JSONObject().put("city", "SF")),
            ),
            reasoningContent = "I should call the weather tool.",
        ),
        LLMMessage(
            LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolResult("toolu_1", "get_weather", "sunny, 20C"),
            ),
        ),
    )

    private fun firstAssistantContent(body: JSONObject): org.json.JSONArray {
        val messages = body.getJSONArray("messages")
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            if (m.getString("role") == "assistant") return m.getJSONArray("content")
        }
        throw AssertionError("no assistant message in body")
    }

    @Test
    fun `interleaved compat model echoes thinking block first on assistant turn`() = runBlocking {
        provider.model = deepseekCompatModel
        server.enqueue(MockResponse().setBody("""{"content":[],"usage":{"input_tokens":0,"output_tokens":0}}"""))

        provider.sendMessage(toolHistory(), null, 1024, thinkingLevel = ThinkingLevel.MEDIUM)

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val content = firstAssistantContent(body)
        // Thinking block must lead the assistant turn, carrying the captured text.
        assertEquals("thinking", content.getJSONObject(0).getString("type"))
        assertEquals("I should call the weather tool.", content.getJSONObject(0).getString("thinking"))
        // tool_use still present, after the thinking block.
        var sawToolUse = false
        for (i in 0 until content.length()) {
            if (content.getJSONObject(i).getString("type") == "tool_use") sawToolUse = true
        }
        assertTrue("tool_use block preserved", sawToolUse)
    }

    @Test
    fun `interleaved compat model injects empty thinking placeholder when reasoning missing`() = runBlocking {
        provider.model = deepseekCompatModel
        server.enqueue(MockResponse().setBody("""{"content":[],"usage":{"input_tokens":0,"output_tokens":0}}"""))

        // Assistant turn with NO captured reasoning (e.g. history from before
        // thinking was on). Still must carry a thinking block or the proxy 400s.
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "hi"),
            LLMMessage(
                LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolUse("toolu_9", "noop", JSONObject()),
                ),
                reasoningContent = null,
            ),
            LLMMessage(
                LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(AgentContentPart.ToolResult("toolu_9", "noop", "ok")),
            ),
        )
        provider.sendMessage(history, null, 1024, thinkingLevel = ThinkingLevel.MEDIUM)

        val content = firstAssistantContent(JSONObject(server.takeRequest().body.readUtf8()))
        assertEquals("thinking", content.getJSONObject(0).getString("type"))
        assertEquals("", content.getJSONObject(0).getString("thinking"))
    }

    @Test
    fun `interleaved compat model echoes thinking on plain-text assistant turn`() = runBlocking {
        provider.model = deepseekCompatModel
        server.enqueue(MockResponse().setBody("""{"content":[],"usage":{"input_tokens":0,"output_tokens":0}}"""))

        // Assistant turn with a plain string reply (no structured contentParts).
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "hi"),
            LLMMessage(
                LLMMessage.Role.ASSISTANT,
                content = "Hello there.",
                reasoningContent = "The user greeted me.",
            ),
            LLMMessage(LLMMessage.Role.USER, "how are you?"),
        )
        provider.sendMessage(history, null, 1024, thinkingLevel = ThinkingLevel.MEDIUM)

        val content = firstAssistantContent(JSONObject(server.takeRequest().body.readUtf8()))
        assertEquals("thinking", content.getJSONObject(0).getString("type"))
        assertEquals("The user greeted me.", content.getJSONObject(0).getString("thinking"))
        assertEquals("text", content.getJSONObject(1).getString("type"))
        assertEquals("Hello there.", content.getJSONObject(1).getString("text"))
    }

    @Test
    fun `non-interleaved model does NOT echo thinking block`() = runBlocking {
        // A reasoning model WITHOUT interleavedReasoningField (Claude-class):
        // real Anthropic verifies signatures and rejects unsigned thinking, so
        // we must never echo. Served from the same custom endpoint to prove the
        // gate keys off interleavedReasoningField, not the endpoint alone.
        provider.model = LLMModel(
            id = "claude-sonnet-5",
            displayName = "Claude Sonnet 5",
            provider = "Anthropic",
            supportsReasoning = true,
            interleavedReasoningField = null,
        )
        server.enqueue(MockResponse().setBody("""{"content":[],"usage":{"input_tokens":0,"output_tokens":0}}"""))

        provider.sendMessage(toolHistory(), null, 1024, thinkingLevel = ThinkingLevel.MEDIUM)

        val content = firstAssistantContent(JSONObject(server.takeRequest().body.readUtf8()))
        for (i in 0 until content.length()) {
            assertTrue(
                "no thinking block should be emitted for non-interleaved model",
                content.getJSONObject(i).getString("type") != "thinking",
            )
        }
    }

    // -- Thinking summary visibility (iOS parity: display=summarized + no redact beta) --

    @Test
    fun `adaptive thinking request sets display summarized so summary text is returned`() = runBlocking {
        // Mirrors iOS 13ca19dd (T-anthropic-thinking-display). Adaptive-thinking
        // models (Claude 4.6+) default `display` to "omitted" on newer tiers,
        // which blanks the thinking block (signature only). We must pin
        // display=summarized so the readable thinking summary comes back.
        provider.model = LLMModel.claudeSonnet5 // major 5 → adaptive thinking
        server.enqueue(MockResponse().setBody("""{"content":[],"usage":{"input_tokens":0,"output_tokens":0}}"""))

        provider.sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 4096,
            thinkingLevel = ThinkingLevel.MEDIUM,
        )

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val thinking = body.getJSONObject("thinking")
        assertEquals("adaptive", thinking.getString("type"))
        assertEquals("summarized", thinking.getString("display"))
    }

    @Test
    fun `OAuth request omits redact-thinking beta so thinking text is not blanked`() = runBlocking {
        // Mirrors iOS 958ee16c (T-anthropic-redact-thinking). The Claude-Code
        // mimicry beta set must NOT carry `redact-thinking-2026-02-12`; with it,
        // the server blanks thinking text (signature only) even though the model
        // reasons. Omitting it == showThinkingSummaries:true.
        val oauthProvider = AnthropicProvider(
            apiKey = "test-oauth-token",
            model = LLMModel.claudeSonnet5,
            basePath = server.url("/").toString().trimEnd('/'),
            isOAuth = true,
        )
        server.enqueue(MockResponse().setBody("""{"content":[],"usage":{"input_tokens":0,"output_tokens":0}}"""))

        oauthProvider.sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 4096,
            thinkingLevel = ThinkingLevel.MEDIUM,
        )

        val beta = server.takeRequest().getHeader("anthropic-beta") ?: ""
        assertTrue(
            "anthropic-beta must not contain redact-thinking; was: $beta",
            !beta.contains("redact-thinking"),
        )
        // Sanity: the OAuth OAuth betas we DO expect are still present.
        assertTrue("oauth beta present", beta.contains("oauth-2025-04-20"))
    }
}
