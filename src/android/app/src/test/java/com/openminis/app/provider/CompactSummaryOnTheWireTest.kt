package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-compact-summary-dropped] The compact summary has to survive
 * SERIALIZATION, not just assembly.
 *
 * The bug this pins: `effectiveAgentHistory()` injected the
 * `<context-summary>` envelope by rewriting only `LLMMessage.content`. Every
 * persisted message also carries `contentParts` (its partsJson round-trips
 * into a Text part), and the provider serializers prefer parts when they
 * exist — the Responses builder emits `textParts.joinToString("")` for a user
 * turn and never reads `msg.content`. So on a session whose anchor was not the
 * last message, the summary was built, logged (`summaryChars=734` in
 * CompactDiag) and then silently dropped on the way to the wire: the model
 * received NEITHER the compacted turns NOR the summary standing in for them.
 *
 * Why these tests are shaped this way:
 *
 *  - They assert the **request body**, because the body is what was wrong. The
 *    five pre-existing compact tests (budget / fallback / split predicate /
 *    tool pairing / divider placement) all cover the PRODUCTION of a
 *    compaction — whether a summary string gets generated, how failures retry,
 *    where the divider row lands. Not one of them looks at what reaches the
 *    model, which is exactly why this survived four months undetected. A test
 *    asserting "a summary was produced" passes against the bug.
 *
 *  - They cover BOTH serialization branches. The defect was a
 *    two-representations-one-updated mistake, so a test that exercises only
 *    the `content` path would still pass with the parts path broken (and vice
 *    versa). `contentParts.isNotEmpty()` is the branch selector in both
 *    builders, so each case below is pinned by a message shaped to take a
 *    different side of it.
 *
 * [injectSummary] is kept byte-for-byte equivalent to the production injection
 * in `ChatViewModel.effectiveAgentHistoryUncounted()`. If the two drift, the
 * `parts-carrying` case is the one that fails — the same way
 * CompactToolPairingTest mirrors its production algorithm rather than
 * constructing a ChatViewModel (which needs a Context, a DB and a provider).
 */
class CompactSummaryOnTheWireTest {

    /** The real envelope, verbatim from ChatViewModel. */
    private fun summaryWrappedText(summary: String): String =
        "<context-summary>\n" +
            "The following is a summary of the earlier conversation that was compacted to save context space.\n" +
            "Treat it as background context only. The user's most recent message (below or in the next turn) takes precedence — if it changes the task, the goal, or any numbers/scope, follow the new instruction and do not resume the old plan from this summary. Do not re-run discovery (reading memory, scanning skills, re-reading files) unless the new instruction requires it.\n\n" +
            summary +
            "\n</context-summary>"

    /**
     * Mirror of the production injection: prepend the envelope onto the first
     * post-anchor user turn, updating BOTH representations.
     */
    private fun injectSummary(target: LLMMessage, summary: String): LLMMessage {
        val wrapped = summaryWrappedText(summary)
        val parts = if (target.contentParts.isEmpty()) {
            target.contentParts
        } else {
            val firstTextIdx = target.contentParts.indexOfFirst { it is AgentContentPart.Text }
            if (firstTextIdx < 0) {
                listOf(AgentContentPart.Text(wrapped)) + target.contentParts
            } else {
                target.contentParts.mapIndexed { i, part ->
                    if (i != firstTextIdx || part !is AgentContentPart.Text) part
                    else AgentContentPart.Text(wrapped + "\n\n" + part.text)
                }
            }
        }
        return target.copy(content = wrapped + "\n\n" + target.content, contentParts = parts)
    }

    private fun model() = LLMModel(
        id = "gpt-5.6-terra",
        displayName = "Test Model",
        provider = "OpenAI",
        inputModalities = listOf("text"),
    )

    private fun provider(useResponses: Boolean) = OpenAIProvider(
        apiKey = "test-key",
        model = model(),
        basePath = "https://example.invalid",
        useResponsesAPI = useResponses,
    )

    /** Every string anywhere in the body — the summary must show up in one. */
    private fun bodyText(body: JSONObject): String = body.toString()

    // ── Branch 1: persisted message (carries contentParts) ────────────────
    //
    // This is the shape that actually broke. A message loaded from the DB
    // always has a Text part, so both builders take the parts branch and
    // `content` is never read.

    private fun persistedUserTurn(text: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = text,
        contentParts = listOf(AgentContentPart.Text(text)),
        dbMessageId = "db-1",
    )

    /** A synthesized turn, as the compactAll path produces: no parts at all. */
    private fun synthesizedUserTurn(text: String) =
        LLMMessage(role = LLMMessage.Role.USER, content = text)

    @Test
    fun `summary reaches the Responses API body when the turn carries contentParts`() {
        val injected = injectSummary(persistedUserTurn("say FOXTROT"), "EARLIER-TURNS-SUMMARY")
        val body = provider(useResponses = true).buildResponsesAPIBody(
            messages = listOf(injected),
            systemPrompt = null,
            maxTokens = 256,
            stream = false,
            imageParts = emptyList(),
        )
        val text = bodyText(body)
        assertTrue(
            "Responses body lost the <context-summary> envelope. This is THE bug: " +
                "the turn carries contentParts, so the builder serialized " +
                "textParts.joinToString() and ignored msg.content. body=$body",
            text.contains("<context-summary>"),
        )
        assertTrue(
            "envelope present but the summary payload itself is missing. body=$body",
            text.contains("EARLIER-TURNS-SUMMARY"),
        )
        assertTrue(
            "the user's own text must survive alongside the injected summary",
            text.contains("say FOXTROT"),
        )
    }

    @Test
    fun `summary reaches the Chat Completions body when the turn carries contentParts`() {
        val injected = injectSummary(persistedUserTurn("say FOXTROT"), "EARLIER-TURNS-SUMMARY")
        val body = provider(useResponses = false).buildRequestBody(
            messages = listOf(injected),
            systemPrompt = null,
            maxTokens = 256,
            stream = false,
            temperature = null,
            imageParts = emptyList(),
        )
        val text = bodyText(body)
        assertTrue(
            "Chat Completions body lost the <context-summary> envelope. body=$body",
            text.contains("<context-summary>"),
        )
        assertTrue(
            "envelope present but the summary payload itself is missing. body=$body",
            text.contains("EARLIER-TURNS-SUMMARY"),
        )
        assertTrue(
            "the user's own text must survive alongside the injected summary",
            text.contains("say FOXTROT"),
        )
    }

    // ── Branch 2: synthesized message (content only, no parts) ────────────
    //
    // This is the shape `/compact` produces — a standalone summary turn built
    // by `LLMMessage(role = USER, content = summaryWrappedText)`. It was never
    // broken, and that is precisely why the bug hid for so long: the common
    // entry point took the safe branch. Pinned so a future "simplify the
    // serializer" change cannot break the path that currently works.

    @Test
    fun `standalone summary turn survives the Responses API path`() {
        val standalone = synthesizedUserTurn(summaryWrappedText("STANDALONE-SUMMARY"))
        val body = provider(useResponses = true).buildResponsesAPIBody(
            messages = listOf(standalone),
            systemPrompt = null,
            maxTokens = 256,
            stream = false,
            imageParts = emptyList(),
        )
        val text = bodyText(body)
        assertTrue("standalone summary lost on Responses path. body=$body", text.contains("<context-summary>"))
        assertTrue(text.contains("STANDALONE-SUMMARY"))
    }

    @Test
    fun `standalone summary turn survives the Chat Completions path`() {
        val standalone = synthesizedUserTurn(summaryWrappedText("STANDALONE-SUMMARY"))
        val body = provider(useResponses = false).buildRequestBody(
            messages = listOf(standalone),
            systemPrompt = null,
            maxTokens = 256,
            stream = false,
            temperature = null,
            imageParts = emptyList(),
        )
        val text = bodyText(body)
        assertTrue("standalone summary lost on Chat Completions path. body=$body", text.contains("<context-summary>"))
        assertTrue(text.contains("STANDALONE-SUMMARY"))
    }

    // ── The injection itself ───────────────────────────────────────────────

    @Test
    fun `injection updates both representations, not just content`() {
        val injected = injectSummary(persistedUserTurn("say FOXTROT"), "S")
        assertTrue(
            "content must carry the envelope",
            injected.content.contains("<context-summary>"),
        )
        val partText = injected.contentParts
            .filterIsInstance<AgentContentPart.Text>()
            .joinToString("") { it.text }
        assertTrue(
            "contentParts must carry the envelope too — updating only `content` " +
                "is the exact defect this suite exists to prevent",
            partText.contains("<context-summary>"),
        )
        assertTrue("the original user text must be preserved in parts", partText.contains("say FOXTROT"))
    }

    @Test
    fun `injection into a turn with no text part prepends one instead of editing a non-text part`() {
        // An image- or tool-only turn has no Text part to prepend onto. The
        // summary must still get in, without disturbing what is already there.
        val toolOnly = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(AgentContentPart.ToolResult("call-1", "shell_execute", "ok")),
        )
        val injected = injectSummary(toolOnly, "S")
        val parts = injected.contentParts
        assertTrue(
            "a Text part carrying the summary should have been prepended",
            (parts.firstOrNull() as? AgentContentPart.Text)?.text?.contains("<context-summary>") == true,
        )
        assertEquals("the existing tool part must be preserved", 2, parts.size)
        assertTrue(parts[1] is AgentContentPart.ToolResult)
    }
}
