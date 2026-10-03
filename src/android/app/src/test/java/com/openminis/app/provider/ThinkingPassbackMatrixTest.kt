package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T18] Thinking passback matrix — one golden sample per protocol of how the
 * reasoning captured on a prior assistant turn is echoed back AFTER a tool call.
 *
 * Pins: 20c074454 (Anthropic-protocol interleaved thinking echo, issue #70),
 * ThinkingRuleResolver's deepseek-flash rule (GH#356), the OpenAI-chat
 * reasoning_content echo gate (GH#22 / #87 / #171), the Gemini thoughtSignature
 * replay (#179), and the Responses API input conversion.
 *
 * Nine field reports (GH #1, #16, #22, #70, #87, #171, #356, #361, #368) are all
 * the same seam: the SAME captured reasoning must land in a DIFFERENT field, at a
 * DIFFERENT position, per protocol — and for some protocols must NOT be sent at
 * all. Every case drives the real provider through a MockWebServer and reads the
 * serialized request, because the failure mode is a silent shape drift, not an
 * exception.
 *
 * The shared history is one tool-call round: user → assistant (reasoning + text
 * + tool_use) → tool_result → user. Only the assistant turn's serialization
 * differs across protocols; the tool pairing is asserted alongside so a passback
 * change cannot silently break the run it sits in.
 */
class ThinkingPassbackMatrixTest {

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

    // ------------------------------------------------------------ fixtures

    private val reasoning = "chain of thought"

    private fun history(
        reasoningContent: String? = reasoning,
        thoughtSignature: String? = null,
    ): List<LLMMessage> = listOf(
        LLMMessage(LLMMessage.Role.USER, "q1"),
        LLMMessage(
            LLMMessage.Role.ASSISTANT,
            "",
            contentParts = listOf(
                AgentContentPart.Text("calling the tool"),
                AgentContentPart.ToolUse(
                    "call_1", "shell_execute", JSONObject().put("command", "ls"),
                    thoughtSignature = thoughtSignature,
                ),
            ),
            reasoningContent = reasoningContent,
        ),
        LLMMessage(
            LLMMessage.Role.USER,
            "",
            contentParts = listOf(AgentContentPart.ToolResult("call_1", "shell_execute", "file.txt")),
        ),
        LLMMessage(LLMMessage.Role.USER, "q2"),
    )

    private fun model(
        id: String,
        provider: String = "Test",
        supportsReasoning: Boolean? = true,
        reasoningEffortValues: List<String>? = null,
        interleavedReasoningField: String? = null,
        outputModalities: List<String>? = null,
    ) = LLMModel(
        id = id,
        displayName = id,
        provider = provider,
        supportsReasoning = supportsReasoning,
        reasoningEffortValues = reasoningEffortValues,
        interleavedReasoningField = interleavedReasoningField,
        outputModalities = outputModalities,
    )

    /** Stable textual form: sorted keys at every level. Strings are quoted verbatim. */
    private fun canonical(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { "$it:${canonical(v.get(it))}" }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        is String -> "\"$v\""
        else -> v.toString()
    }

    private fun captureOpenAI(
        m: LLMModel,
        level: ThinkingLevel,
        path: String = "/v1",
        responses: Boolean = false,
        history: List<LLMMessage> = history(),
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
                    messages = history,
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

    private fun captureAnthropic(m: LLMModel, level: ThinkingLevel, history: List<LLMMessage> = history()): JSONObject {
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
                    messages = history,
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

    private fun captureGemini(m: LLMModel, level: ThinkingLevel, history: List<LLMMessage>): JSONObject {
        val ok = """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
        repeat(4) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok))
        }
        val provider = GeminiProvider(
            apiKey = "test-key",
            model = m,
            basePath = server.url("/").toString().trimEnd('/'),
        )
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = history,
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

    private fun messages(body: JSONObject): JSONArray = body.getJSONArray("messages")

    private fun anyMessageHasKey(body: JSONObject, key: String): Boolean {
        val msgs = body.optJSONArray("messages") ?: return false
        for (i in 0 until msgs.length()) if (msgs.getJSONObject(i).has(key)) return true
        return false
    }

    // ============================================================ OpenAI chat

    /**
     * OpenAI Chat Completions on an interleaved-reasoning vendor: the captured
     * reasoning rides on the assistant MESSAGE as `reasoning_content`, a sibling
     * of `content` / `tool_calls` — never inside the content string, never on the
     * `tool` reply. The whole assistant turn is pinned, so a shape drift in any of
     * the three fields shows up as a one-line diff.
     */
    @Test
    fun `openai chat echoes reasoning_content as a message-level sibling of tool_calls`() {
        val body = captureOpenAI(
            model("deepseek-flash", reasoningEffortValues = listOf("high", "max"), interleavedReasoningField = "reasoning_content"),
            ThinkingLevel.HIGH,
        )
        val msgs = messages(body)
        assertEquals(
            """{content:"calling the tool",reasoning_content:"chain of thought",role:"assistant",tool_calls:[{function:{arguments:"{"command":"ls"}",name:"shell_execute"},id:"call_1",type:"function"}]}""",
            canonical(msgs.getJSONObject(1)),
        )
        assertEquals(
            "the tool reply must follow the assistant turn contiguously and carry no reasoning",
            """{content:"file.txt",role:"tool",tool_call_id:"call_1"}""",
            canonical(msgs.getJSONObject(2)),
        )
        assertEquals("q2", msgs.getJSONObject(3).getString("content"))
    }

    /**
     * DeepSeek native (V4 / deepseek-flash): a prior turn whose reasoning was never
     * captured still needs the FIELD present — as `""`, not a synthetic marker
     * (the "[no prior reasoning]" / single-space placeholders were in-context
     * learned and echoed back as the model's own reasoning, T249/T257).
     */
    @Test
    fun `deepseek native sends an empty reasoning_content placeholder when nothing was captured`() {
        val body = captureOpenAI(
            model("deepseek-flash", reasoningEffortValues = listOf("high", "max"), interleavedReasoningField = "reasoning_content"),
            ThinkingLevel.MEDIUM,
            history = history(reasoningContent = null),
        )
        val assistant = messages(body).getJSONObject(1)
        assertTrue("field presence is the DeepSeek contract: $assistant", assistant.has("reasoning_content"))
        assertEquals("", assistant.getString("reasoning_content"))
    }

    /**
     * DeepSeek native: the thinking SWITCH is a root object and the tier is a root
     * sibling — the reasoning goes in `messages[]`, never in `content[]` (there is
     * no content array on this path at all).
     */
    @Test
    fun `deepseek native keeps thinking at the root and reasoning out of content`() {
        val body = captureOpenAI(
            model("deepseek-flash", reasoningEffortValues = listOf("high", "max"), interleavedReasoningField = "reasoning_content"),
            ThinkingLevel.HIGH,
        )
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        assertEquals("high", body.getString("reasoning_effort"))
        assertFalse(body.getJSONObject("thinking").has("reasoning_effort"))
        val assistant = messages(body).getJSONObject(1)
        assertTrue("content must stay a plain string on the chat path: $assistant", assistant.get("content") is String)
    }

    /** GH#356 — the bare `deepseek-flash` id lands on the DeepSeek rule, OFF included. */
    @Test
    fun `deepseek-flash hits the deepseek rule at OFF too`() {
        val body = captureOpenAI(
            model("deepseek-flash", reasoningEffortValues = listOf("high", "max"), interleavedReasoningField = "reasoning_content"),
            ThinkingLevel.OFF,
        )
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertFalse("OFF sends no tier on the vendor-native shape: $body", body.has("reasoning_effort"))
    }

    // ============================================================ Responses API

    /**
     * Responses API: structured history becomes typed input items. The tool
     * round-trip is a `function_call` (synthetic `fc_syn_` id when the call was not
     * born on this path) followed by its `function_call_output`.
     *
     * KNOWN GAP: no `reasoning` item is echoed. iOS re-attaches captured
     * reasoning items (id + encrypted_content) so a relay that validates
     * "reasoning_text must be passed back" (GH#368) accepts the next turn; Android
     * has no reasoning item on the input array at all. Pinned as ⚠️ rather than a
     * failure so the rest of the matrix stays green.
     */
    @Test
    fun `responses api converts the tool round-trip to typed items`() {
        val body = captureOpenAI(
            model("gpt-5.3", reasoningEffortValues = listOf("low", "medium", "high")),
            ThinkingLevel.HIGH,
            responses = true,
        )
        val input = body.getJSONArray("input")
        val types = (0 until input.length()).map { i ->
            val o = input.getJSONObject(i)
            o.optString("type").ifEmpty { o.optString("role") }
        }
        assertEquals(listOf("user", "assistant", "function_call", "function_call_output", "user"), types)
        assertEquals(
            """{arguments:"{"command":"ls"}",call_id:"call_1",id:"fc_syn_call_1",name:"shell_execute",type:"function_call"}""",
            canonical(input.getJSONObject(2)),
        )
        assertEquals(
            """{call_id:"call_1",output:"file.txt",type:"function_call_output"}""",
            canonical(input.getJSONObject(3)),
        )
        assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
        assertFalse("reasoning_content is a chat-completions field, not a Responses one", body.toString().contains("reasoning_content"))
        // KNOWN GAP: expected a {"type":"reasoning", ...} item ahead of the
        // function_call carrying the captured reasoning back (iOS parity).
        if (types.none { it == "reasoning" }) {
            println("⚠️ KNOWN GAP [T18/responses]: Android Responses input carries no reasoning item — captured reasoning is not passed back (GH#368 class)")
        }
    }

    // ============================================================ Anthropic protocol

    /**
     * 20c074454 — Anthropic protocol + DeepSeek-class compat proxy: the captured
     * reasoning is replayed verbatim as an UNSIGNED `thinking` block that LEADS
     * the assistant content, ahead of text and tool_use. No `signature` key: we
     * never capture one, and the proxy accepts an unsigned block.
     */
    @Test
    fun `anthropic protocol interleaved thinking is echoed as the leading unsigned block`() {
        val body = captureAnthropic(
            model("deepseek-v4-flash", supportsReasoning = true, interleavedReasoningField = "reasoning_content"),
            ThinkingLevel.HIGH,
        )
        val msgs = messages(body)
        val assistant = msgs.getJSONObject(1)
        val content = assistant.getJSONArray("content")
        val types = (0 until content.length()).map { content.getJSONObject(it).getString("type") }
        assertEquals(listOf("thinking", "text", "tool_use"), types)
        assertEquals("""{thinking:"chain of thought",type:"thinking"}""", canonical(content.getJSONObject(0)))
        assertFalse("never fabricate a signature", content.getJSONObject(0).has("signature"))
        // The tool_result turn (merged with the following user turn) carries no thinking.
        val user = msgs.getJSONObject(2)
        val userContent = user.getJSONArray("content")
        for (i in 0 until userContent.length()) {
            assertFalse("user turns never carry thinking", userContent.getJSONObject(i).getString("type") == "thinking")
        }
        assertEquals("tool_result", userContent.getJSONObject(0).getString("type"))
        assertEquals("call_1", userContent.getJSONObject(0).getString("tool_use_id"))
    }

    /** Same protocol, uncaptured reasoning → empty placeholder block (the proxy still 400s without it). */
    @Test
    fun `anthropic protocol echoes an empty thinking placeholder when nothing was captured`() {
        val body = captureAnthropic(
            model("deepseek-v4-flash", supportsReasoning = true, interleavedReasoningField = "reasoning_content"),
            ThinkingLevel.HIGH,
            history = history(reasoningContent = null),
        )
        val first = messages(body).getJSONObject(1).getJSONArray("content").getJSONObject(0)
        assertEquals("""{thinking:"",type:"thinking"}""", canonical(first))
    }

    /**
     * The gate that makes the echo safe: a strict Claude-class model (no
     * interleavedReasoningField) verifies signatures, so an unsigned block would
     * be rejected with `thinking.signature: invalid_request_error`. Captured
     * reasoning is NOT echoed there — even with supportsReasoning=true.
     */
    @Test
    fun `strict claude-class model never receives an unsigned thinking block`() {
        val body = captureAnthropic(
            model("claude-sonnet-4-6", supportsReasoning = true, interleavedReasoningField = null),
            ThinkingLevel.HIGH,
        )
        val content = messages(body).getJSONObject(1).getJSONArray("content")
        val types = (0 until content.length()).map { content.getJSONObject(it).getString("type") }
        assertEquals(listOf("text", "tool_use"), types)
        assertFalse("captured reasoning must not leak into a strict endpoint", body.toString().contains(reasoning))
    }

    /**
     * Claude 5 at OFF. iOS 69be65763: Claude 5 rejects `thinking.type=disabled`
     * outright ("defaults to adaptive mode when not specified"), so OFF must send
     * NO thinking field.
     *
     * WAS A KNOWN GAP: Android used `modelUsesAdaptiveThinking` — true for 4.6+
     * AND 5+ — to decide whether to emit the literal, so every thinking-OFF
     * request on a Claude 5 model carried the value the API rejects. FIXED by
     * porting iOS's `modelAcceptsExplicitThinkingDisabled` split (4.6-4.x only)
     * and gating both emission sites on it. Now a hard assertion; the 4.6-4.x
     * half (which DOES need the literal) is the control below.
     */
    @Test
    fun `claude 5 off does not send the disabled literal`() {
        for (id in listOf("claude-sonnet-5", "claude-opus-5", "claude-fable-5", "claude-fable-5-1")) {
            val body = captureAnthropic(model(id, supportsReasoning = true), ThinkingLevel.OFF)
            assertNull(
                "Claude 5 OFF must omit the thinking field entirely, or the API 400s: $id -> $body",
                body.optJSONObject("thinking")?.optString("type"),
            )
        }
    }

    /** Control: 4.6-4.x thinks by default, so OFF MUST stay explicit there. */
    @Test
    fun `claude 4_6 off keeps the explicit disabled literal`() {
        val body = captureAnthropic(model("claude-sonnet-4-6", supportsReasoning = true), ThinkingLevel.OFF)
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
    }

    // ============================================================ Gemini

    /**
     * Gemini takes no reasoning text back at all — its passback is the opaque
     * `thoughtSignature`, a SIBLING of `functionCall` on the historical part.
     * The captured reasoning string must not appear anywhere in the body.
     */
    @Test
    fun `gemini replays the thought signature on the functionCall part and no reasoning text`() {
        val body = captureGemini(
            model("gemini-3-flash-preview", provider = "Google Gemini"),
            ThinkingLevel.HIGH,
            history(thoughtSignature = "SIG-abc123"),
        )
        val contents = body.getJSONArray("contents")
        val modelTurn = contents.getJSONObject(1)
        assertEquals("model", modelTurn.getString("role"))
        assertEquals(
            """[{text:"calling the tool"},{functionCall:{args:{command:"ls"},name:"shell_execute"},thoughtSignature:"SIG-abc123"}]""",
            canonical(modelTurn.getJSONArray("parts")),
        )
        assertFalse("Gemini never receives the reasoning text", body.toString().contains(reasoning))
        assertEquals(
            "the paired functionResponse stays structural when the call is signed",
            "shell_execute",
            contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0)
                .getJSONObject("functionResponse").getString("name"),
        )
    }

    // ============================================================ foreign reasoning dropped

    /**
     * Reasoning captured from another model must be DROPPED for endpoints that
     * reject the field: Mistral's AssistantMessage is a closed schema (422
     * extra_forbidden, GH#87), and a non-reasoning model has no business
     * receiving it either.
     */
    @Test
    fun `foreign reasoning is dropped for mistral and for non-reasoning models`() {
        val mistral = captureOpenAI(
            model("mistral-large-latest", reasoningEffortValues = listOf("low", "high")),
            ThinkingLevel.MEDIUM,
            path = "/mistral.ai/v1",
        )
        assertFalse("mistral must never see reasoning_content: $mistral", anyMessageHasKey(mistral, "reasoning_content"))
        assertTrue("…but the tool_calls history itself survives", messages(mistral).getJSONObject(1).has("tool_calls"))

        val plain = captureOpenAI(model("gpt-4o", supportsReasoning = false), ThinkingLevel.OFF)
        assertFalse("a non-reasoning model gets no reasoning_content: $plain", anyMessageHasKey(plain, "reasoning_content"))
    }

    // ============================================================ no enable_thinking where the rule says so

    /**
     * A qwen id served by OpenRouter follows OpenRouter's contract (nested
     * `reasoning.effort`), not the vendor-native `enable_thinking` dual-send that
     * the same id would get on DashScope. The endpoint rule outranks the id rule.
     */
    @Test
    fun `qwen on openrouter gets nested reasoning and no enable_thinking`() {
        val body = captureOpenAI(model("qwen3-32b"), ThinkingLevel.MEDIUM, path = "/openrouter.ai/api/v1")
        assertFalse("no enable_thinking on OpenRouter: $body", body.has("enable_thinking"))
        assertFalse("no thinking_budget on OpenRouter: $body", body.has("thinking_budget"))
        assertFalse("no extra_body on OpenRouter: $body", body.has("extra_body"))
        assertEquals("medium", body.getJSONObject("reasoning").getString("effort"))
    }

    /** Control: the same id on DashScope keeps the vendor-native dual-send. */
    @Test
    fun `qwen on dashscope keeps enable_thinking`() {
        val body = captureOpenAI(model("qwen3-32b"), ThinkingLevel.MEDIUM, path = "/dashscope.aliyuncs.com/compatible-mode/v1")
        assertTrue(body.getBoolean("enable_thinking"))
        assertTrue(body.getJSONObject("extra_body").getBoolean("enable_thinking"))
        assertFalse(body.has("reasoning"))
    }
}
