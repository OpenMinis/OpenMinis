package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-android-empty-turn-reminder-any-tail] Decides how the agent loop recovers
 * from an EMPTY model turn — one where the model produced no text and no tool
 * call and simply stopped.
 *
 * Background: `runAgentLoop` classifies a turn as empty via
 * `turnText.isEmpty() && toolCalls.isEmpty()`. An empty turn is high-
 * probability whenever the conversation ends on a STATEMENT the model owes no
 * reply to. The one that triggered this work is a finished sub-agent callback
 * (`<agent_callback kind="finished">…</agent_callback>`): a "here is a completed
 * result" notice with no instruction. Measured against the live Codex endpoint
 * on GPT-5.6 Terra, a request ending on such a callback returned an empty turn
 * 7/10 times, so a user tapping Retry saw "Model returned an empty response"
 * over and over. A tool_result tail (the case the loop already handled) is just
 * one shape of the same problem.
 *
 * The recovery is: remove the empty assistant turn, append a one-shot
 * `<system-reminder>` nudging the model to continue, and retry ONE round. This
 * object decides WHERE the reminder goes; the loop performs the mutation and
 * owns the once-per-run guard. Keeping the decision pure lets it be tested
 * without a live ViewModel (mirrors ImageInputPreflight / FallbackPulseGate).
 */
internal object EmptyTurnRecovery {

    /** The reminder text appended to the history tail before the retry. */
    const val REMINDER: String =
        "\n\n<system-reminder>The previous response was empty. You MUST continue: " +
            "if the last message was a completed sub-agent result or a notice, acknowledge it " +
            "and take the next step or give the user a final answer; if more work is needed, " +
            "respond with the next tool call(s). Do not return an empty response.</system-reminder>"

    /**
     * [T-android-empty-turn-stopreason] Whether a finished turn should be
     * classified as EMPTY (and thus routed to recovery/hint).
     *
     * This encodes the loop's gate `!hasVisibleContent && finishedCleanly` in
     * one testable place, and — following the iOS reasoning-only guidance
     * (AIChatViewModel.swift isEmptyResponse) — makes the stop-reason
     * NARROWING explicit rather than implicit:
     *
     *  - A turn that produced text or a tool call is never empty.
     *  - Only a terminal "done" stop (`stop` / `end_turn`, or a null stop from
     *    a dropped stream that the caller has already decided to treat as
     *    empty) qualifies. `length` (truncated / max_output_tokens — often the
     *    reasoning phase) does NOT: it kept partial output.
     *  - `tool_calls` / `tool_use` with no tool call and no text IS empty
     *    ([T-android-tooluse-stop-no-calls]): there is nothing to continue the
     *    loop with, so without this the run just stopped silently.
     *  - REASONING-ONLY is the subtle case. Interleaved thinking emits a
     *    reasoning block and then CALLS A TOOL — that turn ends on `tool_use`
     *    and has [hasToolCall] = true, so it is excluded here (and in the loop
     *    it never even reaches this gate: the block is inside
     *    `if (toolCalls.isEmpty())`). A reasoning-only turn that ends on
     *    `stop` / `end_turn` with no text and no tool is the stall the user
     *    sees as "the run just ended" — it IS empty and must be recovered.
     *    So `hasReasoning` is deliberately NOT an exemption on this API:
     *    reasoning that "led somewhere" already shows up as a tool call.
     *
     * @param hasText the turn produced non-blank assistant text.
     * @param hasToolCall the turn produced at least one tool call.
     * @param stopReason the turn's finish reason (provider string, or null).
     */
    fun shouldClassifyAsEmptyTurn(hasText: Boolean, hasToolCall: Boolean, stopReason: String?): Boolean {
        if (hasText || hasToolCall) return false
        if (stopReason == null || stopReason == "stop" || stopReason == "end_turn") return true
        // [T-android-tooluse-stop-no-calls] A turn that ended on
        // `tool_calls` / `tool_use` but delivered NO tool call (and no text) is
        // empty too. The stop reason promises a tool round, yet there is nothing
        // to execute, so the loop's `toolCalls.isEmpty()` branch breaks out and
        // the run ended silently with a blank bubble — no recovery, no hint.
        // Seen when a provider (or our parser) drops the tool-call payload but
        // keeps the finish reason. Routing it through empty-turn recovery
        // gives it the one-shot nudge and then the empty-response hint.
        // Mirrors iOS 910708619.
        return stopReason == "tool_calls" || stopReason == "tool_use"
    }

    sealed class Plan {
        /**
         * Append [REMINDER] onto the content-part at [tailIndex]/[partIndex] of
         * the tail user turn (a tool_result part, or a text part).
         */
        data class NudgeExistingPart(val tailIndex: Int, val partIndex: Int, val isToolResult: Boolean) : Plan()

        /** Append a fresh standalone user turn carrying [REMINDER]. */
        object AppendStandalone : Plan()

        /** No user turn to nudge (e.g. tail is an assistant row) — fall through to the error hint. */
        object GiveUp : Plan()
    }

    /**
     * @param history the FULL agentHistory, whose LAST entry is the empty
     *   assistant turn the loop just appended. The tail being replied to is at
     *   `history[size - 2]`.
     * @param alreadyFired the once-per-run guard: when true, always [GiveUp].
     */
    fun plan(history: List<LLMMessage>, alreadyFired: Boolean): Plan {
        if (alreadyFired) return Plan.GiveUp
        if (history.size < 2) return Plan.GiveUp
        val tailIdx = history.size - 2
        val tail = history[tailIdx]
        if (tail.role != LLMMessage.Role.USER) return Plan.GiveUp

        // Prefer a trailing tool_result (original behaviour, byte-identical for
        // that case), else a trailing text part (the callback / statement tail).
        val trPartIdx = tail.contentParts.indexOfLast { it is AgentContentPart.ToolResult }
        if (trPartIdx >= 0) return Plan.NudgeExistingPart(tailIdx, trPartIdx, isToolResult = true)
        val txtPartIdx = tail.contentParts.indexOfLast { it is AgentContentPart.Text }
        if (txtPartIdx >= 0) return Plan.NudgeExistingPart(tailIdx, txtPartIdx, isToolResult = false)

        // User tail with no appendable part (image-only, etc.).
        return Plan.AppendStandalone
    }
}
