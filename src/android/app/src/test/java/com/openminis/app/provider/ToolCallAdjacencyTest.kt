package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-tool-call-adjacency / GH#342] Drives a real request through the
 * provider and asserts the outbound Chat Completions `messages` array upholds
 * the invariant the API enforces:
 *
 *   every assistant `tool_calls` entry is IMMEDIATELY followed by a matching
 *   role:"tool" reply for its `tool_call_id`.
 *
 * A violation is a hard 400 ("An assistant message with 'tool_calls' must be
 * followed by tool messages responding to each 'tool_call_id'") that wedges
 * the session until retry. Mirrors iOS
 * OpenAIAgentProvider.sanitizeToolCallAdjacency.
 */
class ToolCallAdjacencyTest {
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

    private fun assistant(vararg ids: String) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = "",
        contentParts = ids.map { AgentContentPart.ToolUse(it, "shell_execute", JSONObject()) },
    )

    private fun toolResult(id: String, content: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(AgentContentPart.ToolResult(id, "shell_execute", content)),
    )

    private fun userText(text: String) = LLMMessage(role = LLMMessage.Role.USER, content = text)

    /** Send [messages] and return the parsed outbound body. */
    private fun capture(messages: List<LLMMessage>): JSONObject {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""",
            ),
        )
        runBlocking { provider.sendMessage(messages, null, 1024) }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    private fun roles(body: JSONObject): List<String> {
        val messages = body.getJSONArray("messages")
        return (0 until messages.length()).map { messages.getJSONObject(it).getString("role") }
    }

    private fun toolIds(messages: JSONArray): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            if (m.optString("role") == "tool") out.add(m.getString("tool_call_id"))
        }
        return out
    }

    @Test
    fun `reply appearing later in history is spliced back adjacent to its call`() {
        val body = capture(
            listOf(
                userText("hi"),
                assistant("call_A", "call_B"),
                toolResult("call_A", "ok A"),
                userText("queued question"),
                toolResult("call_B", "ok B"),
            ),
        )

        assertEquals(
            listOf("user", "assistant", "tool", "tool", "user"),
            roles(body),
        )
        val messages = body.getJSONArray("messages")
        assertEquals(listOf("call_A", "call_B"), toolIds(messages))
    }

    @Test
    fun `missing reply gets a placeholder and empty reply gets filled in`() {
        val body = capture(
            listOf(
                userText("hi"),
                assistant("call_A", "call_B"),
                toolResult("call_A", ""),
            ),
        )

        val messages = body.getJSONArray("messages")
        assertEquals(listOf("user", "assistant", "tool", "tool"), roles(body))
        assertEquals(listOf("call_A", "call_B"), toolIds(messages))

        val firstTool = messages.getJSONObject(2)
        assertTrue(
            "an empty tool reply must be replaced with non-empty content",
            firstTool.getString("content").isNotBlank(),
        )
        val secondTool = messages.getJSONObject(3)
        assertTrue(
            "a missing tool reply must be synthesized with non-empty content",
            secondTool.getString("content").isNotBlank(),
        )
    }

    @Test
    fun `orphan reply with no claiming tool_call is dropped`() {
        val body = capture(
            listOf(
                userText("hi"),
                assistant("call_A"),
                toolResult("call_GHOST", "unclaimed"),
            ),
        )

        assertEquals(listOf("user", "assistant", "tool"), roles(body))
        assertEquals(listOf("call_A"), toolIds(body.getJSONArray("messages")))
    }

    @Test
    fun `already-adjacent history passes through unchanged`() {
        val body = capture(
            listOf(
                userText("hi"),
                assistant("call_A"),
                toolResult("call_A", "ok A"),
            ),
        )

        assertEquals(listOf("user", "assistant", "tool"), roles(body))
        assertEquals(listOf("call_A"), toolIds(body.getJSONArray("messages")))
    }
}
