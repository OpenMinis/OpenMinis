package com.openminis.app.ui.chat.retention

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * The model-context retention rule, and the reason the chat surface is not
 * part of it.
 *
 * The reported OOM was a 4,562-message session (5.4M chars, 4,288 tool
 * messages) whose oversized tool output sits in the model context. 2.0.10
 * also spilled the RENDERED messages, replacing terminal tool blocks with an
 * `[CONTEXT OFFLOADED] N chars at ...` stub on every send. Nothing renders
 * that stub back, so the user lost a chunk of the transcript on the next
 * message sent — a byte saving that is really silent data loss.
 */
internal object ResidentWindow {
    const val RESIDENT_BYTES = HotWindow.RESIDENT_BYTES

    fun bytesOf(history: List<LLMMessage>): Long {
        var total = 0L
        for (msg in history) total += messageBytes(msg)
        return total
    }

    /**
     * Per-message byte estimate, extracted from [bytesOf] so [byteCut] can
     * size each message exactly once per pass instead of re-serializing the
     * whole remaining suffix at every candidate cut.
     *
     * [AgentContentPart.ToolUse.input] is the only part whose size requires
     * *serializing* (a `JSONObject` has no cached length), which is why the
     * old `byteCut` — `bytesOf(subList(cut, size))` inside the candidate
     * loop — re-stringified every tool input O(candidates) times. On a
     * session with a multi-megabyte tool argument that stalled the main
     * thread for seconds during `loadSession`'s history rebuild and ANR'd
     * the app (input dispatching timed out). Sizing each message once and
     * walking candidates over suffix sums is semantically identical —
     * `suffix[cut] == bytesOf(history.subList(cut, history.size))` — and
     * costs one serialization pass total.
     */
    fun messageBytes(msg: LLMMessage): Long =
        msg.content.length.toLong() * 2 +
            (msg.reasoningContent?.length?.toLong() ?: 0L) * 2 +
            msg.contentParts.sumOf { part ->
                when (part) {
                    is AgentContentPart.Text -> part.text.length.toLong() * 2
                    is AgentContentPart.ToolResult ->
                        part.content.length.toLong() * 2 + (part.imageData?.size?.toLong() ?: 0L)
                    is AgentContentPart.ToolUse -> part.input.toString().length.toLong() * 2
                    is AgentContentPart.ImageData -> part.data.size.toLong()
                }
            }

    /**
     * How many leading entries to drop so the history is at most
     * [maxMessages] long, without orphaning a tool result.
     */
    fun countCut(history: List<LLMMessage>, maxMessages: Int): Int {
        val overflow = history.size - maxMessages
        if (overflow <= 0) return 0
        val legal = legalBoundaries(history)
        return firstLegal(legal, overflow) ?: 0
    }

    /**
     * How many leading entries to drop so the history fits [budget].
     *
     * The last entry is never dropped: that is the turn in flight, and
     * emptying it would leave the model with nothing to answer.
     *
     * Each round asks whether the CURRENT cut point is already legal before
     * advancing. Skipping straight to `cut + 1` would walk past a legal
     * boundary sitting at `cut` itself — a plain-text head followed by bare
     * tool results is the common shape, and it must be cuttable at the head.
     *
     * The legality of every cut is precomputed in one right-to-left pass
     * ([legalBoundaries]); asking per candidate used to scan the whole suffix
     * each time, i.e. O(N²) on a 4,000-message session.
     */
    fun byteCut(history: List<LLMMessage>, budget: Long = RESIDENT_BYTES): Int {
        if (history.size <= 1) return 0
        // One sizing pass (see [messageBytes]), then pure arithmetic per
        // candidate: suffix[cut] is the byte cost of keeping everything
        // from `cut` on, which is exactly what the old per-candidate
        // `bytesOf(history.subList(cut, history.size))` computed.
        val suffix = LongArray(history.size + 1)
        for (i in history.size - 1 downTo 0) {
            suffix[i] = suffix[i + 1] + messageBytes(history[i])
        }
        if (suffix[0] <= budget) return 0
        val legal = legalBoundaries(history)
        var cut = firstLegal(legal, 0)
        var lastLegal = cut
        while (cut != null && cut < history.size - 1) {
            if (suffix[cut] <= budget) return cut
            cut = firstLegal(legal, cut + 1)
            if (cut != null) lastLegal = cut
        }
        // The last legal cut is the best that can be done: everything after
        // it is either over budget or uncuttable without orphaning.
        if (lastLegal != null && lastLegal > 0) return lastLegal
        return 0
    }

    /**
     * Precompute every legal cut in one right-to-left pass.
     *
     * Forward rule (the original [isLegalBoundary]): walking the suffix, a
     * ToolResult must match an earlier ToolUse with the same id/name; at the
     * end, any unmatched use is allowed only in the final assistant message.
     *
     * Backward recurrence while extending the suffix to the left:
     *  - a ToolResult can only match a use to its LEFT, so it joins
     *    [pendingResults] and waits;
     *  - a ToolUse first tries to consume a waiting result to its right;
     *    otherwise it is unmatched, and it is "bad" unless it sits in the
     *    final assistant message.
     *
     * A cut is legal iff no result is still waiting and no non-final use is
     * unmatched. Results waiting at the current message boundary stay in the
     * state when extending further left — an earlier use may still match them.
     */
    private fun legalBoundaries(history: List<LLMMessage>): BooleanArray {
        val n = history.size
        val legal = BooleanArray(n + 1)
        val pendingResults = HashMap<String, Int>()
        var badUses = 0
        for (i in n - 1 downTo 0) {
            val message = history[i]
            val inFlightTurn = i == n - 1 && message.role == LLMMessage.Role.ASSISTANT
            val parts = message.contentParts
            for (p in parts.asReversed()) {
                when (p) {
                    is AgentContentPart.ToolResult -> {
                        val key = toolKey(p.id, p.name)
                        pendingResults[key] = (pendingResults[key] ?: 0) + 1
                    }
                    is AgentContentPart.ToolUse -> {
                        val key = toolKey(p.id, p.name)
                        val waiting = pendingResults[key] ?: 0
                        if (waiting > 0) {
                            if (waiting == 1) pendingResults.remove(key) else pendingResults[key] = waiting - 1
                        } else if (!inFlightTurn) {
                            badUses++
                        }
                    }
                    else -> Unit
                }
            }
            legal[i] = pendingResults.isEmpty() && badUses == 0
        }
        legal[n] = false
        return legal
    }

    private fun toolKey(id: String, name: String): String = "$id\u0000$name"

    /** First legal cut index >= [from], or null. Built once, O(1) per query. */
    private fun firstLegal(legal: BooleanArray, from: Int): Int? {
        var i = from.coerceIn(0, legal.size)
        while (i < legal.size) {
            if (legal[i]) return i
            i++
        }
        return null
    }
}
