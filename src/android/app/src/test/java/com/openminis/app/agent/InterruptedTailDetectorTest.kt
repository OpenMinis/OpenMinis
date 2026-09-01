package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which session tails offer a Resume affordance.
 *
 * This rule decides whether a user who was killed mid-turn can recover the
 * session at all. Both directions are load-bearing and neither is visible in
 * code review:
 *  - too strict and the session is unrecoverable (GH#262/#263: no PAUSED
 *    badge, no Resume, and retryLast() bailing on its own guard);
 *  - too loose and a turn that is merely still waiting grows a Resume banner.
 *
 * Liveness ("is a request in flight right now") is deliberately NOT part of
 * this function — the caller gates on `!isStreaming && !trackerActive`. These
 * tests therefore pin SHAPE only.
 */
class InterruptedTailDetectorTest {

    private fun user(vararg parts: AgentContentPart) =
        LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = parts.toList())

    private fun assistant(vararg parts: AgentContentPart) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = parts.toList())

    private fun text(s: String) = AgentContentPart.Text(s)

    private fun toolResult(id: String = "t1") =
        AgentContentPart.ToolResult(id = id, name = "shell_execute", content = "ok")

    private fun toolUse(id: String = "t1") =
        AgentContentPart.ToolUse(id = id, name = "shell_execute", input = JSONObject())

    // MARK: - Case D, the shape this fix added

    /** The reported bug: a plain user turn that never got an answer. */
    @Test
    fun `plain text user tail with no reply is recoverable`() {
        assertEquals(
            InterruptedTailShape.UNANSWERED_USER_TURN,
            InterruptedTailDetector.classify(user(text("what is 2+2?"))),
        )
    }

    @Test
    fun `multi part user tail with text is still an unanswered turn`() {
        val m = user(text("look at this"), text("and this"))
        assertEquals(InterruptedTailShape.UNANSWERED_USER_TURN, InterruptedTailDetector.classify(m))
    }

    /**
     * An EMPTY user turn has nothing to answer, and re-sending it would post a
     * content-less message the API rejects — so it must NOT be resumable, even
     * though it is also "a user tail with no reply".
     */
    @Test
    fun `empty user tail is not recoverable`() {
        assertEquals(InterruptedTailShape.NONE, InterruptedTailDetector.classify(user()))
        assertFalse(InterruptedTailDetector.isInterrupted(user()))
    }

    // MARK: - The pre-existing shapes must keep their own identity

    @Test
    fun `all tool results tail is case A, not the new case`() {
        val m = user(toolResult("a"), toolResult("b"))
        assertEquals(InterruptedTailShape.TOOL_RESULT_TAIL, InterruptedTailDetector.classify(m))
    }

    @Test
    fun `continue reminder tail is case C, not the new case`() {
        val m = user(text("<system-reminder>${InterruptedTailDetector.CONTINUE_REMINDER_MARKER} but now wants to continue.</system-reminder>"))
        assertEquals(InterruptedTailShape.CONTINUE_REMINDER, InterruptedTailDetector.classify(m))
    }

    @Test
    fun `assistant tail requesting tools is case B`() {
        assertEquals(
            InterruptedTailShape.ASSISTANT_TOOL_USE,
            InterruptedTailDetector.classify(assistant(text("running…"), toolUse())),
        )
    }

    /**
     * Ordering guard: a tail that is BOTH all-tool-results and text-free must
     * report as Case A. Case D is the fallback, so if the branches were
     * reordered this test fails rather than silently relabelling every
     * completed tool turn.
     */
    @Test
    fun `tool result tail is classified before the unanswered fallback`() {
        val m = user(toolResult())
        assertEquals(InterruptedTailShape.TOOL_RESULT_TAIL, InterruptedTailDetector.classify(m))
    }

    // MARK: - Not interrupted

    /** A finished reply is the normal end state — the common case by far. */
    @Test
    fun `plain assistant reply is not interrupted`() {
        assertEquals(InterruptedTailShape.NONE, InterruptedTailDetector.classify(assistant(text("4"))))
        assertFalse(InterruptedTailDetector.isInterrupted(assistant(text("4"))))
    }

    @Test
    fun `empty history is not interrupted`() {
        assertEquals(InterruptedTailShape.NONE, InterruptedTailDetector.classify(null))
        assertFalse(InterruptedTailDetector.isInterrupted(null))
    }

    @Test
    fun `mixed user tail with a tool result and text is an unanswered turn`() {
        // Not ALL tool results, so Case A does not apply; there is real content
        // to answer, so this is recoverable.
        val m = user(toolResult(), text("also, explain why"))
        assertEquals(InterruptedTailShape.UNANSWERED_USER_TURN, InterruptedTailDetector.classify(m))
    }

    @Test
    fun `isInterrupted agrees with classify for every shape`() {
        assertTrue(InterruptedTailDetector.isInterrupted(user(text("hi"))))
        assertTrue(InterruptedTailDetector.isInterrupted(user(toolResult())))
        assertTrue(InterruptedTailDetector.isInterrupted(assistant(toolUse())))
        assertFalse(InterruptedTailDetector.isInterrupted(assistant(text("done"))))
    }
}
