package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.gemini.GeminiProvider
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

class GeminiProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: GeminiProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = GeminiProvider(
            apiKey = "test-key",
            model = LLMModel.gemini25Flash,
            basePath = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // -- sendMessage response parsing --

    @Test
    fun `sendMessage parses Gemini response`() = runBlocking {
        val responseBody = """
        {
            "candidates": [{
                "content": {
                    "parts": [{"text": "Hello from Gemini!"}],
                    "role": "model"
                },
                "finishReason": "STOP"
            }],
            "usageMetadata": {"promptTokenCount": 8, "candidatesTokenCount": 4}
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))

        val response = provider.sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "Hi")),
            null, 1024,
        )

        assertEquals("Hello from Gemini!", response.text)
        assertEquals("end_turn", response.stopReason)
        assertEquals(8, response.usage?.inputTokens)
        assertEquals(4, response.usage?.outputTokens)
    }

    @Test
    fun `sendMessage maps STOP to end_turn`() = runBlocking {
        val responseBody = """
        {
            "candidates": [{
                "content": {"parts": [{"text": "done"}]},
                "finishReason": "STOP"
            }]
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))
        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 100)
        assertEquals("end_turn", response.stopReason)
    }

    @Test
    fun `sendMessage maps MAX_TOKENS to max_tokens`() = runBlocking {
        val responseBody = """
        {
            "candidates": [{
                "content": {"parts": [{"text": "truncated"}]},
                "finishReason": "MAX_TOKENS"
            }]
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))
        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 10)
        assertEquals("max_tokens", response.stopReason)
    }

    @Test
    fun `sendMessage defaults to end_turn when no finishReason`() = runBlocking {
        val responseBody = """
        {
            "candidates": [{
                "content": {"parts": [{"text": "ok"}]}
            }]
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))
        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 100)
        assertEquals("end_turn", response.stopReason)
    }

    @Test
    fun `sendMessage parses multiple text parts`() = runBlocking {
        val responseBody = """
        {
            "candidates": [{
                "content": {
                    "parts": [{"text": "Hello "}, {"text": "world!"}]
                }
            }]
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))
        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 100)
        assertEquals("Hello world!", response.text)
    }

    // -- Request construction --

    @Test
    fun `sendMessage includes API key in URL`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        assertTrue(request.path!!.contains("key=test-key"))
        assertTrue(request.path!!.contains("generateContent"))
    }

    @Test
    fun `sendMessage maps roles correctly`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))

        provider.sendMessage(
            listOf(
                LLMMessage(LLMMessage.Role.USER, "hello"),
                LLMMessage(LLMMessage.Role.ASSISTANT, "hi"),
                LLMMessage(LLMMessage.Role.USER, "how are you"),
            ),
            null, 100,
        )

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val contents = body.getJSONArray("contents")
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        assertEquals("user", contents.getJSONObject(2).getString("role"))
    }

    @Test
    fun `sendMessage includes system instruction`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), "Be concise", 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val sysInstruction = body.getJSONObject("systemInstruction")
        val text = sysInstruction.getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals("Be concise", text)
    }

    @Test
    fun `sendMessage omits system instruction when null`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertTrue(!body.has("systemInstruction"))
    }

    @Test
    fun `sendMessage includes temperature in generationConfig`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100, temperature = 0.9)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val config = body.getJSONObject("generationConfig")
        assertEquals(0.9, config.getDouble("temperature"), 0.001)
        assertEquals(100, config.getInt("maxOutputTokens"))
    }

    @Test
    fun `sendMessage omits temperature when null`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100, temperature = null)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val config = body.getJSONObject("generationConfig")
        assertTrue(!config.has("temperature"))
    }

    // -- [T-gemini-empty-part-oneof-400] empty text part never shipped --

    // Gemini rejects {"text": ""} with a 400: contents[N].parts[M].data:
    // required oneof field 'data' must have one initialized field. An empty
    // user/model text must be substituted (never an empty string, never an
    // empty parts[]). Regression for the Gemini 3.5 Flash "tool format errors".
    @Test
    fun `sendMessage never sends empty text part for empty content`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "")), null, 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertTrue("parts must not be empty", parts.length() > 0)
        val text = parts.getJSONObject(0).getString("text")
        assertTrue("text part must never be empty (empty {\"text\":\"\"} → Gemini oneof 400)", text.isNotEmpty())
    }

    @Test
    fun `sendMessage keeps real content intact`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hello world")), null, 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val text = body.getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals("hello world", text)
    }

    // -- Streaming --

    @Test
    fun `streamMessage parses SSE chunks`() = runBlocking {
        val sseBody = buildString {
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"Hello"}]}}]}""")
            appendLine()
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":" world"}]}}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2}}""")
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
        assertTrue(chunks.any { it is LLMStreamChunk.Finished })
    }

    @Test
    fun `streamMessage extracts finishReason from SSE`() = runBlocking {
        val sseBody = buildString {
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"Hi"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":1}}""")
            appendLine()
        }

        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()

        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>()
        assertEquals(1, finished.size)
        assertEquals("end_turn", finished[0].stopReason)
    }

    @Test
    fun `streamMessage maps MAX_TOKENS finishReason`() = runBlocking {
        val sseBody = buildString {
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"truncated"}]},"finishReason":"MAX_TOKENS"}]}""")
            appendLine()
        }

        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 10).toList()

        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>()
        assertEquals(1, finished.size)
        assertEquals("max_tokens", finished[0].stopReason)
    }

    @Test
    fun `streamMessage defaults to end_turn when no finishReason in SSE`() = runBlocking {
        val sseBody = buildString {
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""")
            appendLine()
        }

        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()

        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>()
        assertEquals(1, finished.size)
        assertEquals("end_turn", finished[0].stopReason)
    }

    @Test
    fun `streamMessage includes temperature in request`() = runBlocking {
        val sseBody = buildString {
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""")
            appendLine()
        }
        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024, temperature = 0.3).toList()

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(0.3, body.getJSONObject("generationConfig").getDouble("temperature"), 0.001)
        assertTrue(request.path!!.contains("streamGenerateContent"))
        assertTrue(request.path!!.contains("alt=sse"))
    }

    @Test
    fun `streamMessage parses usage metadata`() = runBlocking {
        val sseBody = buildString {
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"Hi"}]}}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3}}""")
            appendLine()
        }

        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()

        val usageChunks = chunks.filterIsInstance<LLMStreamChunk.Usage>()
        assertEquals(1, usageChunks.size)
        assertEquals(10, usageChunks[0].usage.inputTokens)
        assertEquals(3, usageChunks[0].usage.outputTokens)
    }

    // -- Error handling --

    @Test(expected = LLMError.InvalidApiKey::class)
    fun `sendMessage throws on 401`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        Unit
    }

    @Test(expected = LLMError.InvalidApiKey::class)
    fun `sendMessage throws on 403`() = runBlocking {
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
    fun `sendMessage throws TransientError with message on 500`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        try {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        } catch (e: LLMError.TransientError) {
            assertTrue(e.message!!.contains("500"))
            return@runBlocking
        }
        throw AssertionError("Expected TransientError")
    }

    // -- Provider metadata --

    @Test
    fun `provider name is Google`() {
        assertEquals("Google", provider.name)
    }

    // ── [T21] thoughtSignature round-trip / parallel batch / audio_output ───
    //
    // Pins e7cf8331a (all-or-nothing per message, GH#179 / #155), the
    // functionCall→ToolCallComplete signature capture, and 4b6121833 (no
    // thinkingConfig / systemInstruction for audio-output models, OpenMinis#226).

    private fun gemini(id: String, outputModalities: List<String>? = null) = GeminiProvider(
        apiKey = "test-key",
        model = LLMModel(id, id, "Google Gemini", outputModalities = outputModalities),
        basePath = server.url("/").toString().trimEnd('/'),
    )

    private fun okJson() = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}""")

    private fun toolUse(id: String, sig: String?) =
        AgentContentPart.ToolUse(id, "subagent_task", JSONObject().put("task", id), thoughtSignature = sig)

    private fun toolResult(id: String) = AgentContentPart.ToolResult(id, "subagent_task", "done $id")

    private fun capturedBody(p: GeminiProvider, history: List<LLMMessage>, level: ThinkingLevel = ThinkingLevel.OFF): JSONObject {
        server.enqueue(okJson())
        runBlocking { p.sendMessage(history, "Be brief", 512, thinkingLevel = level) }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    private fun partKinds(parts: org.json.JSONArray): List<String> = (0 until parts.length()).map { i ->
        val o = parts.getJSONObject(i)
        when {
            o.has("functionCall") -> if (o.has("thoughtSignature")) "functionCall+sig" else "functionCall"
            o.has("functionResponse") -> "functionResponse"
            o.has("text") -> "text"
            else -> "?"
        }
    }

    /**
     * Round trip: the signature streamed on a functionCall part (a SIBLING of
     * `functionCall`, not nested) is captured on ToolCallComplete, and once
     * persisted on the ToolUse part it is replayed at the same position.
     */
    @Test
    fun `functionCall thoughtSignature is captured from the stream and replayed on history`() = runBlocking {
        val p = gemini("gemini-3-flash-preview")
        val sse = buildString {
            appendLine("""data: {"candidates":[{"content":{"parts":[{"thoughtSignature":"SIG-1","functionCall":{"name":"subagent_task","args":{"task":"a"}}}]},"finishReason":"STOP"}]}""")
            appendLine()
        }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val chunks = p.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "go")), null, 512).toList()
        server.takeRequest()
        val complete = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>().single()
        assertEquals("subagent_task", complete.name)
        assertEquals("a", complete.args.getString("task"))
        assertEquals("SIG-1", complete.thoughtSignature)

        // Replay: the persisted signature goes back as a sibling of functionCall.
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "go"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(toolUse(complete.id, complete.thoughtSignature))),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(toolResult(complete.id))),
        )
        val body = capturedBody(p, history)
        val modelParts = body.getJSONArray("contents").getJSONObject(1).getJSONArray("parts")
        assertEquals(listOf("functionCall+sig"), partKinds(modelParts))
        val part = modelParts.getJSONObject(0)
        assertEquals("SIG-1", part.getString("thoughtSignature"))
        assertTrue("the signature is a sibling, never nested inside functionCall", !part.getJSONObject("functionCall").has("thoughtSignature"))
        assertEquals(listOf("functionResponse"), partKinds(body.getJSONArray("contents").getJSONObject(2).getJSONArray("parts")))
    }

    /**
     * e7cf8331a — one signature for a parallel batch of three: the batch is
     * replayed WHOLE or downgraded WHOLE. Sending the signed call alone with its
     * siblings as text is what Gemini rejects as "Corrupted thought signature".
     */
    @Test
    fun `a parallel batch with one signature is downgraded whole, a fully signed one replays whole`() = runBlocking {
        val p = gemini("gemini-3-flash-preview")
        fun batch(sigs: List<String?>): List<LLMMessage> = listOf(
            LLMMessage(LLMMessage.Role.USER, "fan out"),
            LLMMessage(
                LLMMessage.Role.ASSISTANT, "",
                contentParts = sigs.mapIndexed { i, s -> toolUse("c$i", s) },
            ),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = sigs.indices.map { toolResult("c$it") }),
        )

        val mixed = capturedBody(p, batch(listOf("SIG-only-first", null, null)))
        val mixedModel = mixed.getJSONArray("contents").getJSONObject(1).getJSONArray("parts")
        assertEquals("mixed batch → every call becomes text", listOf("text", "text", "text"), partKinds(mixedModel))
        assertTrue(mixedModel.getJSONObject(0).getString("text").startsWith("Earlier in this conversation, the subagent_task tool was run"))
        assertTrue("no signature may survive a downgrade", !mixed.toString().contains("SIG-only-first"))
        val mixedUser = mixed.getJSONArray("contents").getJSONObject(2).getJSONArray("parts")
        assertEquals("…and every paired result becomes text too", listOf("text", "text", "text"), partKinds(mixedUser))

        val signed = capturedBody(p, batch(listOf("S0", "S1", "S2")))
        val signedModel = signed.getJSONArray("contents").getJSONObject(1).getJSONArray("parts")
        assertEquals(listOf("functionCall+sig", "functionCall+sig", "functionCall+sig"), partKinds(signedModel))
        assertEquals(listOf("S0", "S1", "S2"), (0 until 3).map { signedModel.getJSONObject(it).getString("thoughtSignature") })
        val signedUser = signed.getJSONArray("contents").getJSONObject(2).getJSONArray("parts")
        assertEquals(listOf("functionResponse", "functionResponse", "functionResponse"), partKinds(signedUser))
    }

    /** The whole special case stays behind `gemini-3`: 2.x replays unsigned calls structurally. */
    @Test
    fun `gemini 2_x replays unsigned calls as functionCall without a signature`() = runBlocking {
        val p = gemini("gemini-2.5-flash")
        val body = capturedBody(
            p,
            listOf(
                LLMMessage(LLMMessage.Role.USER, "go"),
                LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(toolUse("c0", null))),
                LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(toolResult("c0"))),
            ),
        )
        val modelParts = body.getJSONArray("contents").getJSONObject(1).getJSONArray("parts")
        assertEquals(listOf("functionCall"), partKinds(modelParts))
    }

    /**
     * OpenMinis#226 — an audio-output (TTS) model rejects both `thinkingConfig`
     * and `systemInstruction` with 400, even at a non-OFF level and even though
     * its id also matches a family pattern. It gets `responseModalities:["AUDIO"]`.
     */
    @Test
    fun `audio_output model gets no thinkingConfig and no systemInstruction`() = runBlocking {
        for (id in listOf("gemini-2.5-flash-preview-tts", "gemini-3.1-flash-tts-preview")) {
            val p = gemini(id, outputModalities = listOf("audio"))
            val body = capturedBody(p, listOf(LLMMessage(LLMMessage.Role.USER, "say hi")), ThinkingLevel.HIGH)
            val config = body.getJSONObject("generationConfig")
            assertTrue("$id must carry no thinkingConfig: $body", !config.has("thinkingConfig"))
            assertTrue("$id must carry no systemInstruction: $body", !body.has("systemInstruction"))
            assertEquals("AUDIO", config.getJSONArray("responseModalities").getString(0))
        }
    }

    /** Control: a text model at the same level DOES get its thinkingConfig and system instruction. */
    @Test
    fun `text model at HIGH keeps thinkingConfig and systemInstruction`() = runBlocking {
        val p = gemini("gemini-3-flash-preview")
        val body = capturedBody(p, listOf(LLMMessage(LLMMessage.Role.USER, "hi")), ThinkingLevel.HIGH)
        assertEquals("high", body.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"))
        assertTrue(body.has("systemInstruction"))
        assertTrue(!body.getJSONObject("generationConfig").has("responseModalities"))
    }

    // ── [GH#384] usageMetadata: thoughts tokens + context cache ──────────────
    // Two defects in the old six-line extractUsage: only `candidatesTokenCount`
    // was read as output (Gemini 3.x bills thinking separately in
    // `thoughtsTokenCount`), and `cachedContentTokenCount` was never read while
    // `promptTokenCount` — the FULL input — went straight through as
    // `inputTokens`. Numbers below are real captures against gemini-3.8-flash.

    private fun usage(json: String) = GeminiProvider.parseUsageMetadata(JSONObject(json))

    @Test
    fun `extractUsage combines candidates and thoughts tokens for output`() {
        // Captured: prompt 11, candidates 20, thoughts 310, total 341.
        val u = usage("""{"promptTokenCount":11,"candidatesTokenCount":20,
                          "totalTokenCount":341,"thoughtsTokenCount":310}""")
        assertEquals("thinking tokens must not be dropped", 330, u.outputTokens)
        assertEquals(11, u.inputTokens)
        assertNull("no cache in this capture", u.cacheReadInputTokens)
        // The API's own invariant: total = prompt + candidates + thoughts.
        assertEquals(341, u.inputTokens + u.outputTokens)
    }

    @Test
    fun `extractUsage calculates fresh input when cache hit present`() {
        // Captured final chunk of a 49k-token prompt.
        val u = usage("""{"promptTokenCount":49016,"candidatesTokenCount":7,
                          "totalTokenCount":49527,"cachedContentTokenCount":45026,
                          "thoughtsTokenCount":504}""")
        assertEquals("cache read is surfaced", 45026, u.cacheReadInputTokens)
        assertEquals("input is the fresh remainder, 49016 - 45026", 3990, u.inputTokens)
        assertEquals("output = 7 + 504", 511, u.outputTokens)
        assertEquals("context size stays the full prompt", 49016, u.latestContextTokens)

        // The point of subtracting: hit rate is cacheRead / (input + cacheRead),
        // so passing the full prompt through would halve 91.8% to 47.8%.
        val rate = u.cacheReadInputTokens!!.toDouble() / (u.inputTokens + u.cacheReadInputTokens!!)
        assertTrue("hit rate ~91.8%, got $rate", kotlin.math.abs(rate - 0.918) < 0.001)
    }

    @Test
    fun `extractUsage stays backward compatible without cache or thoughts`() {
        // Gemini 1.5 / non-thinking: neither new field present.
        val u = usage("""{"promptTokenCount":100,"candidatesTokenCount":40,"totalTokenCount":140}""")
        assertEquals(100, u.inputTokens)
        assertEquals(40, u.outputTokens)
        assertNull("no cache invented", u.cacheReadInputTokens)
        assertEquals(100, u.latestContextTokens)
    }

    @Test
    fun `extractUsage treats a zero cache count as absent`() {
        // Otherwise every uncached call would render a 0% cache row.
        val u = usage("""{"promptTokenCount":100,"candidatesTokenCount":40,"cachedContentTokenCount":0}""")
        assertNull(u.cacheReadInputTokens)
        assertEquals("input untouched", 100, u.inputTokens)
    }

    @Test
    fun `extractUsage clamps input when a relay reports an over-large cache`() {
        val u = usage("""{"promptTokenCount":100,"candidatesTokenCount":5,"cachedContentTokenCount":250}""")
        assertEquals("never negative", 0, u.inputTokens)
        assertEquals(250, u.cacheReadInputTokens)
    }

    @Test
    fun `extractUsage handles empty and thoughts-only metadata`() {
        val empty = usage("{}")
        assertEquals(0, empty.inputTokens)
        assertEquals(0, empty.outputTokens)
        assertNull(empty.cacheReadInputTokens)

        val onlyThoughts = usage("""{"promptTokenCount":11,"thoughtsTokenCount":310}""")
        assertEquals("a thoughts-only chunk still reports output", 310, onlyThoughts.outputTokens)
    }

    @Test
    fun `streamMessage preserves the late cache across multi-chunk events`() = runBlocking {
        // Gemini emits usageMetadata on EVERY chunk but names
        // cachedContentTokenCount only on the last one. ChatViewModel keeps the
        // last usage chunk, so the final values are what the UI shows.
        val sse = buildString {
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"a"}]}}],"usageMetadata":{"promptTokenCount":49016,"candidatesTokenCount":3,"thoughtsTokenCount":504}}""")
            appendLine()
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"b"}]}}],"usageMetadata":{"promptTokenCount":49016,"candidatesTokenCount":7,"thoughtsTokenCount":504}}""")
            appendLine()
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":""}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":49016,"candidatesTokenCount":7,"cachedContentTokenCount":45026,"thoughtsTokenCount":504}}""")
            appendLine()
        }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))

        val chunks = provider.streamMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024,
        ).toList()
        val usages = chunks.filterIsInstance<LLMStreamChunk.Usage>().map { it.usage }
        assertTrue("every usage-bearing chunk surfaces", usages.size >= 3)

        val last = usages.last()
        assertEquals("the late cache reaches the consumer", 45026, last.cacheReadInputTokens)
        assertEquals("…with the matching fresh input", 3990, last.inputTokens)
        assertEquals("…and the full prompt as context", 49016, last.latestContextTokens)

        // The earlier chunks legitimately report no cache and the FULL prompt —
        // which is why a consumer must not fold input with max() across them.
        assertNull("an early chunk reports no cache", usages.first().cacheReadInputTokens)
        assertEquals("…and the full prompt as input", 49016, usages.first().inputTokens)
    }

}
