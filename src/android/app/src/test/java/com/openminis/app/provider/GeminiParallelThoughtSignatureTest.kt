package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-gemini3-parallel-thoughtsig] Which historical tool calls may be
 * replayed to Gemini 3.x as real `functionCall` parts.
 *
 * Gemini emits ONE thoughtSignature for a whole parallel batch, attached to the
 * FIRST functionCall; the rest legitimately carry none. The original per-call
 * rule ([T-android-gemini3-thoughtsig] #179) therefore replayed the signed call
 * as a functionCall and downgraded its siblings to text, splitting a batch the
 * signature describes as a whole — and Gemini answered
 * `400 "Corrupted thought signature."`.
 *
 * Reproduced on a Pixel 6 (gemini-3.8-flash): one assistant message holding five
 * parallel `subagent_task` calls, exactly one with a signature of length 4096
 * and four null. Every `retryLast` after an app restart hit the 400, because a
 * restart is what forces the history to be rebuilt from the persisted parts.
 *
 * The rule is now all-or-nothing per MESSAGE, since a signature belongs to the
 * model turn rather than to an individual call. This pins the decision itself;
 * the request-body assembly around it is unchanged.
 */
class GeminiParallelThoughtSignatureTest {

    private fun toolUse(id: String, sig: String?) =
        AgentContentPart.ToolUse(id, "subagent_task", JSONObject(), thoughtSignature = sig)

    /** Mirrors the provider's `unsignedToolCallIds` rule. */
    private fun unsigned(messages: List<LLMMessage>): Set<String> = buildSet {
        for (msg in messages) {
            val toolUses = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
            if (toolUses.isEmpty()) continue
            if (toolUses.any { it.thoughtSignature.isNullOrEmpty() }) {
                toolUses.forEach { add(it.id) }
            }
        }
    }

    private fun assistant(vararg parts: AgentContentPart) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = parts.toList())

    /**
     * The device case, exactly: 1 signed + 4 unsigned in one message. Before the
     * fix only the four unsigned were downgraded, leaving the signed one to be
     * sent alone — the corrupted-signature 400.
     */
    @Test
    fun `a parallel batch with one signature is downgraded whole`() {
        val msg = assistant(
            toolUse("c1", "SIG".repeat(1365)),
            toolUse("c2", null),
            toolUse("c3", null),
            toolUse("c4", null),
            toolUse("c5", null),
        )
        val u = unsigned(listOf(msg))
        assertEquals(
            "the signed call must go down with its batch, not be replayed alone",
            setOf("c1", "c2", "c3", "c4", "c5"), u,
        )
    }

    /** A fully-signed turn keeps the fast path — nothing is downgraded. */
    @Test
    fun `a fully signed batch is replayed as function calls`() {
        val msg = assistant(toolUse("c1", "sig-a"), toolUse("c2", "sig-b"))
        assertTrue("nothing may be downgraded when every call is signed", unsigned(listOf(msg)).isEmpty())
    }

    /** The single-call case that worked before must keep working, both ways. */
    @Test
    fun `a lone call is judged on its own signature`() {
        assertTrue(unsigned(listOf(assistant(toolUse("c1", "sig")))).isEmpty())
        assertEquals(setOf("c1"), unsigned(listOf(assistant(toolUse("c1", null)))))
    }

    /**
     * The condemnation must not spread. An older turn with no signatures cannot
     * drag down a later, fully-signed one — that would downgrade history that
     * replays perfectly well and lose the model's own reasoning context.
     */
    @Test
    fun `an unsigned turn does not condemn a signed one`() {
        val old = assistant(toolUse("old1", null), toolUse("old2", null))
        val new = assistant(toolUse("new1", "sig-a"), toolUse("new2", "sig-b"))
        assertEquals(setOf("old1", "old2"), unsigned(listOf(old, new)))
    }

    /** An empty signature string is as unusable as a null one. */
    @Test
    fun `an empty signature counts as unsigned`() {
        assertEquals(setOf("c1", "c2"), unsigned(listOf(assistant(toolUse("c1", ""), toolUse("c2", "sig")))))
    }

    /** Messages carrying no tool calls contribute nothing either way. */
    @Test
    fun `a text-only message is ignored`() {
        val textOnly = assistant(AgentContentPart.Text("hello"))
        assertTrue(unsigned(listOf(textOnly)).isEmpty())
    }
}
