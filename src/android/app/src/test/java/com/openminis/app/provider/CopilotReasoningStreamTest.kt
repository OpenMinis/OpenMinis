package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [M23] The third spelling of a reasoning delta: `delta.reasoning_text`.
 *
 * Pins 317475ab4 / 44e574584, cause #3. GitHub Copilot streams its reasoning as
 * a field name the chat-completions branch had never seen — it accepted
 * `reasoning_content` and `reasoning` only:
 *
 *   "delta":{"content":null,"role":"assistant",
 *            "reasoning_text":" this is a standard mod"}
 *
 * Captured from claude-sonnet-5 via api.githubcopilot.com: 274 such deltas
 * arrived on one turn and every one was discarded (`reasoningLen=0` at stream
 * end). So Deep Thinking was on, the model genuinely reasoned, and the user saw
 * nothing at all. The name already existed elsewhere in the same file for the
 * Responses API (`response.reasoning_text.delta`); only this branch was blind
 * to it.
 *
 * WHAT WAS ALREADY COVERED, and why this file is the remainder. That commit had
 * three independent causes and `CopilotDeviceFlowTest` covers the first two —
 * the `/models` fetch being gated on an api key Copilot does not have, and the
 * capability being read from `supports.thinking`, a key Copilot never sends
 * (it states `adaptive_thinking` / `reasoning_effort` / `max_thinking_budget`
 * instead). Cause #3 is on the STREAM side and had no Android test: the fixes
 * for #1 and #2 only get the request sent correctly, and with #3 outstanding the
 * user still sees an empty thinking pane. Hence a wire-level test.
 *
 * Driven through a real SSE response rather than a parser unit, because the bug
 * was a missing field name in a specific parse branch — the only honest seam is
 * the branch itself.
 */
class CopilotReasoningStreamTest {

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

    private fun model(id: String = "claude-sonnet-5") = LLMModel(
        id = id,
        displayName = id,
        provider = "GitHub Copilot",
        supportsReasoning = true,
        reasoningEffortValues = listOf("low", "medium", "high", "xhigh", "max"),
    )

    /** Enqueue one SSE body and collect the chunks the provider emits for it. */
    private fun stream(vararg dataLines: String): List<LLMStreamChunk> {
        val body = buildString {
            for (line in dataLines) {
                appendLine("data: $line")
                appendLine()
            }
            appendLine("data: [DONE]")
            appendLine()
        }
        server.enqueue(
            MockResponse().setBody(body).setHeader("Content-Type", "text/event-stream"),
        )
        val provider = OpenAIProvider(
            apiKey = "k",
            model = model(),
            basePath = server.url("/api.githubcopilot.com").toString().trimEnd('/'),
        )
        return runBlocking {
            provider.streamMessage(
                listOf(LLMMessage(LLMMessage.Role.USER, "why?")),
                null,
                1024,
            ).toList()
        }
    }

    private fun thinking(chunks: List<LLMStreamChunk>) =
        chunks.filterIsInstance<LLMStreamChunk.ThinkingDelta>().joinToString("") { it.text }

    private fun text(chunks: List<LLMStreamChunk>) =
        chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text }

    // ══════════════════════════════════════ the regression itself

    /**
     * THE BUG. Copilot's exact delta shape — `content: null` alongside
     * `reasoning_text` — must surface as thinking, not be dropped. The null
     * content is part of the shape and is why the delta looked empty to the old
     * parser.
     */
    @Test
    fun `reasoning_text deltas surface as thinking`() {
        val chunks = stream(
            """{"choices":[{"delta":{"content":null,"role":"assistant","reasoning_text":" this is a"}}]}""",
            """{"choices":[{"delta":{"content":null,"reasoning_text":" standard mod"}}]}""",
            """{"choices":[{"delta":{"content":"Here is the answer."}}]}""",
        )
        assertEquals(
            "both reasoning_text deltas must reach the thinking stream",
            " this is a standard mod",
            thinking(chunks),
        )
        assertEquals("…and the visible answer is unaffected", "Here is the answer.", text(chunks))
    }

    /**
     * Many small deltas, concatenated in ORDER. The field report was 274 deltas
     * on a single turn, so ordering and completeness are the properties that
     * matter — a parser that caught the field but re-ordered or deduplicated
     * would produce scrambled thinking text.
     */
    @Test
    fun `a long run of reasoning_text deltas concatenates in order`() {
        val words = (1..40).map { "w$it " }
        val chunks = stream(
            *words.map { """{"choices":[{"delta":{"content":null,"reasoning_text":"$it"}}]}""" }.toTypedArray(),
        )
        assertEquals(words.joinToString(""), thinking(chunks))
    }

    // ═════════════════════════ the other two spellings still work

    /**
     * The fix ADDED a spelling; it must not have replaced one. `reasoning_content`
     * is DeepSeek / Kimi / GLM and `reasoning` is what several relays send, so a
     * regression here would silently break every other reasoning vendor on the
     * chat path while Copilot worked.
     */
    @Test
    fun `the two older spellings are still parsed`() {
        assertEquals(
            "deepseek/kimi spelling",
            "thought A",
            thinking(stream("""{"choices":[{"delta":{"reasoning_content":"thought A"}}]}""")),
        )
        assertEquals(
            "relay spelling",
            "thought B",
            thinking(stream("""{"choices":[{"delta":{"reasoning":"thought B"}}]}""")),
        )
    }

    /**
     * PRECEDENCE among the three. The production chain is
     * `reasoning_content` → `reasoning` → `reasoning_text`, each falling through
     * only on an EMPTY value, so a delta carrying more than one must yield
     * exactly one copy of the text — not a concatenation of all three. Worth
     * pinning because the fallback is written with `ifEmpty`, and rewriting it as
     * three independent appends would double text on any vendor that sends a
     * field twice under different names.
     */
    @Test
    fun `a delta carrying several spellings yields the text once, by precedence`() {
        assertEquals(
            "reasoning_content wins over the others",
            "first",
            thinking(
                stream(
                    """{"choices":[{"delta":{"reasoning_content":"first","reasoning":"second","reasoning_text":"third"}}]}""",
                ),
            ),
        )
        assertEquals(
            "an EMPTY earlier spelling falls through to the next",
            "second",
            thinking(
                stream("""{"choices":[{"delta":{"reasoning_content":"","reasoning":"second"}}]}"""),
            ),
        )
        assertEquals(
            "…and through both, to reasoning_text",
            "third",
            thinking(
                stream("""{"choices":[{"delta":{"reasoning_content":"","reasoning":"","reasoning_text":"third"}}]}"""),
            ),
        )
    }

    /**
     * An EMPTY `reasoning_text` must still count as "this model reasons" without
     * emitting a delta. DeepSeek V4 round-trips `reasoning_content: ""` and the
     * parser tracks the KEY's presence separately from its value for that reason;
     * the third spelling has to join that tracking or Copilot's first (often
     * empty) delta would be the one that decides the turn carried no reasoning.
     */
    @Test
    fun `an empty reasoning_text emits nothing but is not an error`() {
        val chunks = stream(
            """{"choices":[{"delta":{"content":null,"reasoning_text":""}}]}""",
            """{"choices":[{"delta":{"content":"answer"}}]}""",
        )
        assertEquals("no empty thinking delta", "", thinking(chunks))
        assertEquals("answer", text(chunks))
        assertTrue("the turn must still finish cleanly", chunks.any { it is LLMStreamChunk.Finished })
    }

    /**
     * A JSON `null` for the field is not a string and must not become the literal
     * "null" in the thinking pane — Copilot sends `content: null` in the very same
     * delta, so a naive `optString` on the wrong key is a live hazard here.
     */
    @Test
    fun `a null reasoning field never becomes the literal null`() {
        val chunks = stream(
            """{"choices":[{"delta":{"content":null,"reasoning_text":null}}]}""",
            """{"choices":[{"delta":{"content":"answer"}}]}""",
        )
        assertEquals("", thinking(chunks))
        assertEquals("answer", text(chunks))
    }

    /**
     * Reasoning and visible text interleaved, which is the real shape of a turn:
     * the model reasons, answers, reasons again (tool-use turns do this). The two
     * streams must stay SEPARATE — thinking text leaking into the answer is the
     * complementary bug to this one and just as user-visible.
     */
    @Test
    fun `interleaved reasoning and content stay in separate streams`() {
        val chunks = stream(
            """{"choices":[{"delta":{"content":null,"reasoning_text":"think1 "}}]}""",
            """{"choices":[{"delta":{"content":"answer1 "}}]}""",
            """{"choices":[{"delta":{"content":null,"reasoning_text":"think2"}}]}""",
            """{"choices":[{"delta":{"content":"answer2"}}]}""",
        )
        assertEquals("think1 think2", thinking(chunks))
        assertEquals("answer1 answer2", text(chunks))
    }

    // ══════════════════════════════════════════════ drift guard

    /**
     * SOURCE-GREP DRIFT GUARD. The three spellings are a fallback chain inside
     * one SSE branch, and the branch is long — a refactor that splits the delta
     * handling could drop the third name again without any other test noticing,
     * since it is the only vendor that uses it.
     */
    @Test
    fun `the chat-completions branch still knows all three reasoning spellings`() {
        val src = ProductionSources.read("provider/openai/OpenAIProvider.kt")
        for (needle in listOf(
            """d.safeOptString("reasoning_content", "")""",
            """.ifEmpty { d.safeOptString("reasoning", "") }""",
            """.ifEmpty { d.safeOptString("reasoning_text", "") }""",
            """val hasReasoningTextKey = d.has("reasoning_text")""",
        )) {
            assertTrue("the reasoning fallback chain lost: $needle", src.contains(needle))
        }
        assertTrue(
            "presence of ANY of the three must mark the turn as having carried reasoning",
            src.contains("if (hasRcKey || hasReasoningKey || hasReasoningTextKey)"),
        )
        assertTrue(
            "the Responses path's own spelling of the same name must stay too",
            src.contains("response.reasoning_text.delta"),
        )
    }

    /**
     * The capability half of the same commit, pinned by source because
     * CopilotDeviceFlowTest owns the behavioural rows: Copilot's reasoning
     * signals are `adaptive_thinking` and a `reasoning_effort` tier list, NOT
     * `supports.thinking`. Kept here so the two halves of "Copilot reasoning
     * works" fail together — a parser that streams reasoning for a model the
     * catalog says cannot reason is still a blank pane, because every thinking
     * gate in the app tests `== true` and a null reads as "no".
     */
    @Test
    fun `the Copilot model parser still reads the fields Copilot actually sends`() {
        val src = ProductionSources.read("auth/CopilotDeviceFlow.kt")
        assertTrue(
            "adaptive_thinking is one of the two real reasoning signals",
            src.contains("adaptive_thinking"),
        )
        assertTrue(
            "…and a reasoning_effort tier list is the other",
            src.contains("reasoning_effort"),
        )
        assertTrue(
            "the legacy key is kept only as a FALLBACK — Copilot never sends it",
            src.contains("thinking"),
        )
    }
}
