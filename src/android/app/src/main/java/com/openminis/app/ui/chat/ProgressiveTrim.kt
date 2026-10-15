package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-android-progressive-trim] Pure projection that shrinks an OUTGOING
 * history payload progressively as context pressure rises, BEFORE
 * compaction becomes necessary.
 *
 * Why: compaction (compactAll) is the last resort — it spends an LLM call,
 * drops verbatim detail, and greys out the transcript. Between compactions
 * the payload grows unbounded even though most of its bulk is content the
 * model no longer needs verbatim:
 *
 *   1. OLD `reasoningContent` — replayed verbatim by every provider path
 *      (OpenAI `reasoning_content` echo, Anthropic compat-proxy thinking
 *      blocks). Reasoning older than the last few turns is pure token cost.
 *   2. LARGE old tool outputs — a 50 KB shell log from 60 messages ago is
 *      still sent in full on every subsequent turn.
 *
 * Progressive trimming removes exactly that bulk, in tiers keyed off the
 * untrimmed estimate vs. the context window:
 *
 *   - < 50% of window  → Level.NONE (no trimming)
 *   - ≥ 50%            → STRIP_OLD_THINKING
 *   - ≥ 70%            → + STUB_LARGE_TOOL_OUTPUTS
 *
 * The compact threshold (ContextPolicy, default 90%) sits ABOVE the trim
 * tiers, so trimming gets a chance to pull the payload back under the
 * compact line first. Levels are computed from the UNTRIMMED estimate —
 * monotone in history growth — so the projection cannot oscillate between
 * turns (a usage-based level would: trim → smaller usage → level drops →
 * untrimmed again → usage rises …).
 *
 * Safety invariants (all provider-agnostic):
 *   - Never removes a message or breaks a tool_use/tool_result pair —
 *     stubbing keeps the ToolResult part with its id, so pairing logic
 *     (ToolPairing / dropOrphanedToolParts / ResponsesToolPairing) is
 *     unaffected.
 *   - Never touches anything inside the CURRENT round (after the last
 *     user message) or the last [PROTECTED_RECENT_MESSAGES] messages.
 *   - Never touches user content or assistant text — only reasoning and
 *     oversized tool outputs.
 *   - Stripping `reasoningContent` degrades to `null`, which every echo
 *     path already handles as "reasoning never captured" (OpenAI sends
 *     `""` field-presence placeholder; Anthropic compat proxies accept an
 *     empty thinking block) — the exact shape of pre-thinking-era history.
 *
 * Pure function of (list, level) — unit-testable without Robolectric.
 * The level decision [levelFor] is likewise pure.
 */
internal object ProgressiveTrim {
    /** At/above this fraction of the window, strip old thinking. */
    internal const val LEVEL1_WINDOW_FRACTION: Double = 0.50

    /** At/above this fraction, additionally stub large old tool outputs. */
    internal const val LEVEL2_WINDOW_FRACTION: Double = 0.70

    /** Messages at the tail are never trimmed, regardless of level. */
    internal const val PROTECTED_RECENT_MESSAGES: Int = 40

    /**
     * Assistant turns whose reasoningContent is always kept verbatim.
     * Recent reasoning carries continuity ("what was I just doing");
     * older reasoning is noise. The current round is protected separately
     * (last-user-message rule), so this only governs completed rounds.
     */
    internal const val PROTECTED_RECENT_ASSISTANT_MESSAGES: Int = 8

    /** ToolResult content longer than this (chars) is stubbed at L2. */
    internal const val TOOL_RESULT_STUB_THRESHOLD_CHARS: Int = 2000

    /** Head of a stubbed tool result kept verbatim, for recognition. */
    internal const val TOOL_RESULT_STUB_HEAD_CHARS: Int = 160

    enum class Level {
        NONE,
        STRIP_OLD_THINKING,
        STUB_LARGE_TOOL_OUTPUTS,
    }

    /**
     * Decide the trim level from the UNTRIMMED token estimate and the
     * model's context window. Null/zero window or non-positive estimate →
     * NONE (no basis for a ratio; the compact guard still protects the
     * ceiling).
     */
    fun levelFor(untrimmedTokens: Int, windowTokens: Int?): Level {
        if (windowTokens == null || windowTokens <= 0 || untrimmedTokens <= 0) {
            return Level.NONE
        }
        val ratio = untrimmedTokens.toDouble() / windowTokens.toDouble()
        return when {
            ratio >= LEVEL2_WINDOW_FRACTION -> Level.STUB_LARGE_TOOL_OUTPUTS
            ratio >= LEVEL1_WINDOW_FRACTION -> Level.STRIP_OLD_THINKING
            else -> Level.NONE
        }
    }

    /**
     * Apply [level] to [messages]. Returns the SAME list instance when
     * nothing changes (callers can identity-compare to skip downstream
     * work); otherwise a new list with copies of only the affected
     * messages.
     */
    fun trim(messages: List<LLMMessage>, level: Level): List<LLMMessage> {
        if (level == Level.NONE) return messages
        if (messages.size <= PROTECTED_RECENT_MESSAGES) return messages

        // The current round (everything after the last user message) is
        // never trimmed: its tool results and reasoning are actively in
        // play, and Anthropic requires the thinking preceding a tool_use
        // in the CURRENT turn sequence to be preserved.
        val lastUserIdx = messages.indexOfLast { it.role == LLMMessage.Role.USER }

        // Indices of the last N assistant messages — protected reasoning
        // zone (recent reasoning aids continuity).
        val protectedAssistant = HashSet<Int>(PROTECTED_RECENT_ASSISTANT_MESSAGES)
        var seen = 0
        for (i in messages.indices.reversed()) {
            if (messages[i].role == LLMMessage.Role.ASSISTANT) {
                protectedAssistant.add(i)
                seen += 1
                if (seen >= PROTECTED_RECENT_ASSISTANT_MESSAGES) break
            }
        }

        val firstTrimmableIdx = messages.size - PROTECTED_RECENT_MESSAGES
        var changed = false
        val out = ArrayList<LLMMessage>(messages.size)
        for ((i, msg) in messages.withIndex()) {
            val inProtectedTail = i >= firstTrimmableIdx
            val inCurrentRound = lastUserIdx >= 0 && i >= lastUserIdx
            if (inProtectedTail || inCurrentRound) {
                out.add(msg)
                continue
            }
            var working = msg
            // L1+: strip old reasoning.
            if (working.role == LLMMessage.Role.ASSISTANT &&
                working.reasoningContent != null &&
                i !in protectedAssistant
            ) {
                working = working.copy(reasoningContent = null)
                changed = true
            }
            // L2: stub oversized tool outputs. Bytes (imageData) are NOT
            // trimmed here: ImageBudget already elides old images at the
            // provider boundary with a re-fetchable path placeholder;
            // double-handling would race its budget math.
            if (level == Level.STUB_LARGE_TOOL_OUTPUTS && working.contentParts.isNotEmpty()) {
                var partsChanged = false
                val stubbedParts = working.contentParts.map { part ->
                    if (part is AgentContentPart.ToolResult &&
                        part.content.length > TOOL_RESULT_STUB_THRESHOLD_CHARS
                    ) {
                        partsChanged = true
                        part.copy(content = stubTextFor(part.content))
                    } else {
                        part
                    }
                }
                if (partsChanged) {
                    working = working.copy(contentParts = stubbedParts)
                    changed = true
                }
            }
            out.add(working)
        }
        return if (changed) out else messages
    }

    private fun stubTextFor(content: String): String =
        "[tool output trimmed for context: ${content.length} chars total, first " +
            "$TOOL_RESULT_STUB_HEAD_CHARS kept]\n" +
            content.take(TOOL_RESULT_STUB_HEAD_CHARS)
}
