package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
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
 * [T-android-incomplete-keep-partial] `response.incomplete` used to fail the
 * whole turn, throwing away text that had already streamed.
 *
 * Field report: a Responses-format relay proxying Claude spent the entire
 * output budget inside the reasoning phase, emitted a single space of visible
 * text, then closed with
 *
 *     {"type":"response.incomplete", …,
 *      "usage":{"input_tokens":171671,"output_tokens":0,…},
 *      "incomplete_details":{"reason":"max_output_tokens"}}
 *
 * The provider threw `LLMError.ProviderError`, the agent loop reported
 * "all fallbacks exhausted", and the turn was lost. Raising Max Output Tokens
 * could not help (the budget went to reasoning; the relay reports
 * output_tokens=0 either way), so 128k / 32k / 16k all failed identically.
 *
 * Truncation is a normal terminal condition — Chat Completions models it as
 * `finish_reason=length` and ends the stream cleanly. These tests pin the two
 * halves of the corrected behaviour:
 *
 *   - real partial text  → keep it, finish as "length", no throw;
 *   - nothing usable     → still throw, because a bubble holding one space is
 *                          indistinguishable from a bug.
 */
class ResponsesIncompletePartialTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun responsesChunks(body: String): List<LLMStreamChunk> {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = server.url("/v1").toString().trimEnd('/'),
            useResponsesAPI = true,
        )
        return runBlocking {
            provider.streamMessageClamped(
                messages = listOf(LLMMessage(LLMMessage.Role.USER, "hi")),
                systemPrompt = null,
                maxTokens = 256,
                temperature = null,
                imageParts = emptyList(),
                tools = emptyList(),
                thinkingLevel = ThinkingLevel.OFF,
            ).toList()
        }
    }

    private fun incompleteEvent(reason: String) =
        """data: {"type":"response.incomplete","response":{"status":"incomplete",""" +
            """"usage":{"input_tokens":171671,"output_tokens":0,"total_tokens":171671},""" +
            """"incomplete_details":{"reason":"$reason"}},"sequence_number":10}"""

    // ── Partial output is preserved ───────────────────────────────────────

    @Test
    fun `truncated reply keeps its text and finishes as length`() {
        val chunks = responsesChunks(
            """
            data: {"type":"response.output_text.delta","delta":"Here is the first half of the ans"}

            ${incompleteEvent("max_output_tokens")}

            """.trimIndent(),
        )

        val text = chunks.filterIsInstance<LLMStreamChunk.Text>()
            .joinToString("") { it.text }
        assertEquals("Here is the first half of the ans", text)

        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>()
        assertEquals("expected exactly one Finished, got $chunks", 1, finished.size)
        // "length" is the Chat Completions spelling for a budget-truncated
        // answer; reusing it means downstream needs no new branch.
        assertEquals("length", finished.first().stopReason)
    }

    @Test
    fun `content_filter truncation is also preserved when text arrived`() {
        // The reason string must not change the keep-vs-throw decision — the
        // question is only whether the user has something to read.
        val chunks = responsesChunks(
            """
            data: {"type":"response.output_text.delta","delta":"partial before filter"}

            ${incompleteEvent("content_filter")}

            """.trimIndent(),
        )
        assertEquals(
            "partial before filter",
            chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text },
        )
        assertEquals("length", chunks.filterIsInstance<LLMStreamChunk.Finished>().first().stopReason)
    }

    // ── Nothing usable still fails ────────────────────────────────────────

    @Test
    fun `whitespace-only output still throws — the reported case`() {
        // Byte-for-byte the shape from the field report: one space of visible
        // text. A bubble containing a space reads as a broken reply, so this
        // must stay an error rather than become a blank turn.
        val ex = runCatching {
            responsesChunks(
                """
                data: {"type":"response.output_text.delta","delta":" "}

                ${incompleteEvent("max_output_tokens")}

                """.trimIndent(),
            )
        }.exceptionOrNull()

        assertTrue("expected a throw, got none", ex != null)
        // The flow cancels with a generic "Stream error" and carries the real
        // LLMError as the cause, so walk the chain rather than reading
        // `ex.message` (which is just the wrapper).
        val msg = generateSequence(ex) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
        assertTrue("message should name the reason, got: $msg", msg.contains("max_output_tokens"))
        // The old copy said only "raise the model's Max Output Tokens", which
        // was actively misleading here — the reporter tried 128k/32k/16k with
        // identical results because the budget went to reasoning.
        assertTrue(
            "message should point at the thinking budget, got: $msg",
            msg.contains("output budget") || msg.contains("Thinking"),
        )
    }

    @Test
    fun `no output at all still throws`() {
        val ex = runCatching {
            responsesChunks("${incompleteEvent("max_output_tokens")}\n\n")
        }.exceptionOrNull()
        assertTrue("expected a throw for a bodyless incomplete", ex != null)
    }

    @Test
    fun `reasoning-only output still throws — reasoning is not an answer`() {
        // The exact upstream behaviour: the whole budget went to reasoning and
        // no visible text was produced. Reasoning deltas must not count as
        // usable output, or the user gets an empty bubble.
        val ex = runCatching {
            responsesChunks(
                """
                data: {"type":"response.reasoning_summary_text.delta","delta":"thinking hard about it"}

                ${incompleteEvent("max_output_tokens")}

                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue("reasoning alone must not suppress the error", ex != null)
    }

    // ── The healthy path is unchanged ─────────────────────────────────────

    @Test
    fun `normal completion is unaffected`() {
        val chunks = responsesChunks(
            """
            data: {"type":"response.output_text.delta","delta":"full answer"}

            data: {"type":"response.completed","response":{"status":"completed"}}

            """.trimIndent(),
        )
        assertEquals(
            "full answer",
            chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text },
        )
        assertEquals("stop", chunks.filterIsInstance<LLMStreamChunk.Finished>().first().stopReason)
    }
}
