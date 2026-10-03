package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.gemini.GeminiProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-gemini-unsigned-narration] Port of iOS 1a33fc85f.
 *
 * Gemini 3.x needs a thoughtSignature on every historical functionCall, so a
 * call without one (another model's, before a switch) is sent as history text.
 * That text used to be `[Called shell_execute with: {...}]` /
 * `[Result of shell_execute: ...]`, a bracketed marker Gemini imitates: it
 * started writing `[Called ...]` as its answer instead of calling the tool.
 */
class GeminiUnsignedNarrationTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun gemini3() = GeminiProvider(
        apiKey = "k",
        model = LLMModel("gemini-3-flash-preview", "Gemini 3 Flash", "Google Gemini"),
        basePath = server.url("/").toString().trimEnd('/'),
    )

    /** The request body Gemini receives for [history]. */
    private fun body(history: List<LLMMessage>): JSONObject {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""),
        )
        runBlocking { gemini3().sendMessage(history, "sys", 512) }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    private fun parts(body: JSONObject, content: Int): JSONArray =
        body.getJSONArray("contents").getJSONObject(content).getJSONArray("parts")

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(32)

    /** A DeepSeek-style turn: the call carries no Gemini signature. */
    private fun history(result: AgentContentPart.ToolResult) = listOf(
        LLMMessage(LLMMessage.Role.USER, "list the files"),
        LLMMessage(
            LLMMessage.Role.ASSISTANT, "",
            contentParts = listOf(
                AgentContentPart.ToolUse(
                    "call_1", "shell_execute",
                    JSONObject().put("tool_title", "List").put("command", "ls -la"),
                    thoughtSignature = null,
                ),
            ),
        ),
        LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(result)),
        LLMMessage(LLMMessage.Role.USER, "and now?"),
    )

    @Test
    fun `an unsigned tool call and its result go out as prose, never as a bracketed marker`() {
        val b = body(history(AgentContentPart.ToolResult("call_1", "shell_execute", "a.txt\nb.txt")))
        val wire = b.toString()
        assertFalse(wire.contains("[Called"))
        assertFalse(wire.contains("[Result of"))
        assertFalse(wire.contains("[Error from"))
        val call = parts(b, 1).getJSONObject(0).getString("text")
        val result = parts(b, 2).getJSONObject(0).getString("text")
        assertFalse("no leading bracket to imitate", call.startsWith("[") || result.startsWith("["))
        assertEquals(
            "Earlier in this conversation, the shell_execute tool was run with arguments " +
                "{\"command\":\"ls -la\",\"tool_title\":\"List\"}.",
            call,
        )
        assertEquals("It returned:\na.txt\nb.txt", result)
        assertFalse("no structured call may survive", wire.contains("functionCall") || wire.contains("functionResponse"))
    }

    @Test
    fun `an image returned by a downgraded result is still sent`() {
        val b = body(history(AgentContentPart.ToolResult("call_1", "read_image", "[img]", imageData = jpeg, imageMimeType = "image/jpeg")))
        val p = parts(b, 2)
        assertEquals(2, p.length())
        assertTrue(p.getJSONObject(1).has("inlineData"))
    }

    @Test
    fun `an error result says it failed, still in prose`() {
        val b = body(history(AgentContentPart.ToolResult("call_1", "shell_execute", "no such file", isError = true)))
        assertEquals("It failed:\nno such file", parts(b, 2).getJSONObject(0).getString("text"))
    }

    // ── the builders ──────────────────────────────────────────────────────

    @Test
    fun `long results keep 2000 characters and say so in words`() {
        val long = "x".repeat(14_284)
        val text = GeminiProvider.narratedToolResult(long)
        assertTrue(text.startsWith("It returned (showing the first 2000 of 14284 characters):\n"))
        assertEquals(2000, text.substringAfter(":\n").length)
        assertFalse(text.contains("…"))
        val exact = "y".repeat(2000)
        assertEquals("It returned:\n$exact", GeminiProvider.narratedToolResult(exact))
    }

    @Test
    fun `empty results are described, not left blank`() {
        assertEquals("It returned no output.", GeminiProvider.narratedToolResult(""))
        assertEquals("It failed with no output.", GeminiProvider.narratedToolResult("", isError = true))
    }

    @Test
    fun `arguments serialize with sorted keys at every level, so the prompt is stable`() {
        val a = JSONObject().put("b", 1).put("a", JSONObject().put("z", true).put("y", JSONArray().put("q").put(JSONObject().put("n", 2).put("m", 1))))
        val b = JSONObject().put("a", JSONObject().put("y", JSONArray().put("q").put(JSONObject().put("m", 1).put("n", 2))).put("z", true)).put("b", 1)
        val expected = "{\"a\":{\"y\":[\"q\",{\"m\":1,\"n\":2}],\"z\":true},\"b\":1}"
        assertEquals(expected, GeminiProvider.canonicalJson(a))
        assertEquals(expected, GeminiProvider.canonicalJson(b))
        assertEquals("{}", GeminiProvider.canonicalJson(JSONObject()))
        assertEquals("{\"n\":null,\"s\":\"a\\\"b\"}",
            GeminiProvider.canonicalJson(JSONObject().put("s", "a\"b").put("n", JSONObject.NULL)))
    }

    @Test
    fun `a signed call is untouched by all this`() {
        val signed = listOf(
            LLMMessage(LLMMessage.Role.USER, "go"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(
                AgentContentPart.ToolUse("c", "shell_execute", JSONObject().put("command", "ls"), thoughtSignature = "SIG"),
            )),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult("c", "shell_execute", "ok"))),
        )
        val b = body(signed)
        assertTrue(parts(b, 1).getJSONObject(0).has("functionCall"))
        assertTrue(parts(b, 2).getJSONObject(0).has("functionResponse"))
    }
}
