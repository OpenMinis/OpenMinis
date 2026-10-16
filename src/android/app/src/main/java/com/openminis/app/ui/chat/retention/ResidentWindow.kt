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
        return firstLegalCut(history, overflow) ?: 0
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
        var cut = 0
        while (cut < history.size - 1) {
            if (isLegalBoundary(history, cut)) {
                if (suffix[cut] <= budget) return cut
            }
            val next = firstLegalCut(history, cut + 1) ?: break
            cut = next
        }
        // The last legal cut is the best that can be done: everything after
        // it is either over budget or uncuttable without orphaning.
        if (cut > 0 && isLegalBoundary(history, cut)) return cut
        return 0
    }

    /**
     * Validate the complete retained suffix, not just its first message. A
     * result must follow an unmatched use with the same id and name. This is
     * important when a provider stores the use in an assistant message and
     * the result in a following user message: cutting between those rows would
     * leave a valid-looking first row but orphan the result later in the suffix.
     * An unmatched use is allowed only in the final assistant message because
     * that is the in-flight turn and may legitimately await execution.
     */
    private fun isLegalBoundary(history: List<LLMMessage>, index: Int): Boolean {
        if (index >= history.size) return false
        data class Use(val id: String, val name: String, val messageIndex: Int)
        val pending = ArrayList<Use>()
        history.subList(index, history.size).forEachIndexed { offset, message ->
            val messageIndex = index + offset
            message.contentParts.forEach { part ->
                when (part) {
                    is AgentContentPart.ToolUse -> pending += Use(part.id, part.name, messageIndex)
                    is AgentContentPart.ToolResult -> {
                        val match = pending.indexOfFirst { it.id == part.id && it.name == part.name }
                        if (match < 0) return false
                        pending.removeAt(match)
                    }
                    else -> Unit
                }
            }
        }
        val finalIndex = history.lastIndex
        return pending.all { it.messageIndex == finalIndex && history[finalIndex].role == LLMMessage.Role.ASSISTANT }
    }

    /** Smallest index >= [from] that is a legal cut point, or `null`. */
    private fun firstLegalCut(history: List<LLMMessage>, from: Int): Int? {
        var i = from.coerceIn(0, history.size)
        while (i < history.size) {
            if (isLegalBoundary(history, i)) return i
            i++
        }
        return null
    }
}
