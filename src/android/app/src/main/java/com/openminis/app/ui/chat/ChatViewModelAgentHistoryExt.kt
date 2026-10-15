package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.flow.first

internal fun ChatViewModel.effectiveAgentHistoryUncounted(): List<LLMMessage> {
    val summary = _compactSummary.value
    val marker = _cachedLatestMarker
    // No compact in play → return full history untouched.
    if (summary.isNullOrBlank() || marker == null) return progressivelyTrimmed(agentHistory.toList())

    val summaryWrappedText = "<context-summary>\n" +
        "The following is a summary of the earlier conversation that was compacted to save context space.\n" +
        "Treat it as background context only. The user's most recent message (below or in the next turn) takes precedence — if it changes the task, the goal, or any numbers/scope, follow the new instruction and do not resume the old plan from this summary. The current persona in the system prompt overrides any voice implied by this summary. Do not re-run discovery (reading memory, scanning skills, re-reading files) unless the new instruction requires it.\n\n" +
        summary +
        "\n</context-summary>"

    // [T-compact-chunk-pool] When the marker carries a rolling chunk
    // pool, retrieve the chunks that match the current instruction
    // instead of injecting the whole monolithic summary. A single-chunk
    // pool is byte-identical to the v2 blob, so retrieval only kicks in
    // once several compactions have accumulated.
    val chunkRetrieved: String? = marker.summaryChunks?.let { json ->
        val query = agentHistory.lastOrNull { it.role == LLMMessage.Role.USER }?.content.orEmpty()
        ChatViewModel.selectSummaryChunks(json, query)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n---\n\n")
    }
    val effectiveSummaryWrappedText = if (chunkRetrieved != null && chunkRetrieved != summary) {
        // The chunk pool is an accelerator for relevance, not the source of
        // truth. It intentionally evicts old per-compaction chunks, while
        // `summary` is regenerated from previousSummary and therefore carries
        // the cumulative history. Always retain that cumulative summary so a
        // long-running chat cannot lose early decisions merely because the
        // rolling pool crossed SUMMARY_CHUNK_POOL_MAX.
        "<context-summary>\n" +
            "The following cumulative summary preserves the earlier conversation. A few relevant compaction excerpts follow as additional detail.\n" +
            "Treat it as background context only. The user's most recent message (below or in the next turn) takes precedence — if it changes the task, the goal, or any numbers/scope, follow the new instruction and do not resume the old plan from this summary.\n\n" +
            summary +
            "\n\n--- relevant compaction excerpts ---\n" +
            chunkRetrieved +
            "\n</context-summary>"
    } else {
        summaryWrappedText
    }

    // ─── v2 markers (id-only anchor model) ─────────────────────────
    //
    // anchor = lastCompactedMessageId. What we send to the model:
    //   1. last [COMPACT_KEEP_RECENT_USER_TURNS] user-text turns BEFORE
    //      anchor (inclusive of anchor) — recent verbatim warm-up
    //   2. the summary, INLINED as a `<context-summary>` text part
    //      prepended to the first user message AFTER anchor (preserves
    //      strict role alternation — no synthetic standalone user turn)
    //   3. all messages strictly after anchor (the kept-tail "active"
    //      region — typically empty right after compact, populated as
    //      the user sends new prompts)
    //
    // If anchor unresolvable, degrade to full history (over-inform
    // beats summary-only; the M-Team session bug taught us that a lone
    // summary message paired with hot tools makes the model loop).
    if (marker.version >= 2) {
        val anchorId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
        val anchorIdx = anchorId?.let { id ->
            agentHistory.indexOfLast { it.dbMessageId == id }
        } ?: -1
        if (anchorIdx < 0) {
            // [T-compact-detached-anchor] The anchor was evicted from the
            // bounded window. Degrading to FULL history throws the summary
            // away on every turn - the session then stays over budget
            // forever and never recovers on its own. The summary is
            // independent of the anchor, so inject it with a verbatim
            // tail instead. Logged once per detection, not per call.
            if (!_compactMarkerDetached) {
                _compactMarkerDetached = true
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[Compact] marker ${marker.id.take(8)} anchor ${anchorId?.take(8) ?: "nil"} " +
                        "evicted from agentHistory(size=${agentHistory.size}) - " +
                        "summary+tail injection (tail=${DETACHED_TAIL_MESSAGES})",
                )
            }
            return progressivelyTrimmed(detachedCompactHistory(effectiveSummaryWrappedText))
        }

        // Step 1: walk back from anchor collecting user-text turns. Stop
        // when EITHER we've collected N user-text turns OR including the
        // next turn would push preAnchor over 100 messages. Decisions
        // happen only at user-message boundaries so we never split a
        // user/assistant/tool round in half (which would orphan a
        // tool_use with no matching tool_result).
        //
        // [T-compact-preanchor-prune, port iOS 8b76cd74]
        val keepN = ChatViewModel.COMPACT_KEEP_RECENT_USER_TURNS
        val preAnchorCap = 100
        val walkBack = walkBackUserTurnsBounded(
            anchorIdx = anchorIdx,
            maxUserTextTurns = keepN,
            maxMessages = preAnchorCap,
        )
        val priorIdxResolved: Int? = walkBack.priorIdx
        val priorIdx = walkBack.priorIdx ?: (anchorIdx + 1) // empty preAnchor sentinel
        if (walkBack.stopReason != "userTextTargetMet") {
            AppLogger.info(ChatViewModel.TAG, "[CompactDiag] eAH v2 walkBack stopped: reason=${walkBack.stopReason} priorIdx=$priorIdx userTextTurnsFound=${walkBack.userTextTurnsFound} preAnchorMsgs=${walkBack.messageCount}")
        }

        // PRE-ANCHOR PRUNE (tool-heavy session fix):
        // The walk-back-N-user-text strategy pulls in everything between
        // the Nth-last and last user-text turn — in a heavy tool-call
        // session that can be many messages of tool_result / tool_use,
        // tens of thousands of tokens that the summary already covers.
        // Drop any tool_result > 1000 chars in the preAnchor slice and
        // strip the matching tool_use part (same id) from the assistant
        // message so the model never sees a dangling tool_use/result.
        val preAnchorRaw: List<LLMMessage> =
            if (priorIdx <= anchorIdx) agentHistory.subList(priorIdx, anchorIdx + 1).toList()
            else emptyList()

        val droppedToolIds = mutableSetOf<String>()
        var droppedToolResultCount = 0
        for (msg in preAnchorRaw) {
            for (part in msg.contentParts) {
                if (part is AgentContentPart.ToolResult && part.content.length > 1000) {
                    droppedToolIds.add(part.id)
                    droppedToolResultCount += 1
                }
            }
        }

        val preAnchorPruned: MutableList<LLMMessage> = ArrayList(preAnchorRaw.size)
        for (msg in preAnchorRaw) {
            if (msg.contentParts.isEmpty()) {
                // Plain text-only message — nothing to prune.
                preAnchorPruned.add(msg)
                continue
            }
            val kept = msg.contentParts.filter { part ->
                when (part) {
                    is AgentContentPart.ToolUse -> !droppedToolIds.contains(part.id)
                    is AgentContentPart.ToolResult -> !droppedToolIds.contains(part.id)
                    else -> true
                }
            }
            if (kept.isEmpty()) continue // skip empty shells
            preAnchorPruned.add(msg.copy(contentParts = kept))
        }

        if (droppedToolResultCount > 0) {
            AppLogger.info(ChatViewModel.TAG, "[CompactDiag] eAH v2 preAnchor prune: dropped $droppedToolResultCount toolResult(>1kc) + paired toolUse, ${preAnchorRaw.size - preAnchorPruned.size} messages emptied; pruned slice=${preAnchorPruned.size}")
        }

        // ROLE ALIGNMENT: the API requires the first message to be `user`.
        // After clamp (cap may land on assistant) and after prune (the
        // head user may have been emptied), peel any leading non-user
        // messages so preAnchor starts on a user turn.
        while (preAnchorPruned.isNotEmpty() && preAnchorPruned.first().role != LLMMessage.Role.USER) {
            preAnchorPruned.removeAt(0)
        }

        // Step 2 & 3: copy the lookback window (post-prune), then splice
        // in the summary as parts[0] of the first post-anchor user msg.
        val result = mutableListOf<LLMMessage>()
        result.addAll(preAnchorPruned)

        val postAnchor = if (anchorIdx + 1 < agentHistory.size) {
            agentHistory.subList(anchorIdx + 1, agentHistory.size)
        } else {
            emptyList()
        }

        // DIAG: explain how the slice was sized using post-prune /
        // post-alignment counts so the log reflects what actually
        // reaches the model.
        val preAnchorRawCount = maxOf(0, anchorIdx - priorIdx + 1)
        val priorIdxSource =
            if (priorIdxResolved == null) "fallback=empty(<$keepN user-text turns before anchor or cap hit)"
            else "userTextWalkBack(N=$keepN)"
        AppLogger.info(ChatViewModel.TAG, "[CompactDiag] eAH v2 slice: priorIdx=$priorIdx anchorIdx=$anchorIdx agentHistory.size=${agentHistory.size} → preAnchorRaw=$preAnchorRawCount preAnchorSent=${preAnchorPruned.size} postAnchor=${postAnchor.size} summaryChars=${summary.length} priorIdxSource=$priorIdxSource markerId=${marker.id.take(8)}")

        val firstUserOffset = postAnchor.indexOfFirst { it.role == LLMMessage.Role.USER }
        if (firstUserOffset >= 0) {
            if (firstUserOffset > 0) {
                result.addAll(postAnchor.subList(0, firstUserOffset))
            }
            val target = postAnchor[firstUserOffset]
            // Prepend `<context-summary>...` to the user content. We
            // edit `content` directly because Android LLMMessage uses
            // `content: String` as the canonical text payload; any
            // contentParts the message also carries get preserved.
            val injected = target.copy(
                content = effectiveSummaryWrappedText + "\n\n" + target.content,
            )
            result.add(injected)
            if (firstUserOffset + 1 < postAnchor.size) {
                result.addAll(postAnchor.subList(firstUserOffset + 1, postAnchor.size))
            }
        } else {
            // Rare: no user message after anchor. Append everything
            // post-anchor (typically empty) then a standalone summary
            // user turn. Safe — no later user follows it to break
            // alternation.
            result.addAll(postAnchor)
            result.add(LLMMessage(role = LLMMessage.Role.USER, content = effectiveSummaryWrappedText))
        }
        return progressivelyTrimmed(result)
    }

    // ─── v1 (legacy) markers ──────────────────────────────────────
    //
    // Original behavior preserved unchanged so old markers keep
    // rendering / sending data the same way they always did.
    val summaryHead = LLMMessage(role = LLMMessage.Role.USER, content = effectiveSummaryWrappedText)
    val firstKeptId = (marker.firstKeptMessageId?.takeIf { it.isNotEmpty() })
        ?: (marker.boundaryMessageId?.takeIf { it.isNotEmpty() })

    if (firstKeptId != null) {
        val keepStart = agentHistory.indexOfFirst { it.dbMessageId == firstKeptId }
        if (keepStart >= 0) {
            return buildList(agentHistory.size - keepStart + 1) {
                add(summaryHead)
                addAll(agentHistory.subList(keepStart, agentHistory.size))
            }
        }
        // Fall through to safety net.
    } else {
        val lcmId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
        val lcmIdx = lcmId?.let { id ->
            agentHistory.indexOfLast { it.dbMessageId == id }
        } ?: -1
        val postCompactStart = lcmIdx + 1
        return buildList(agentHistory.size - postCompactStart + 1) {
            add(summaryHead)
            if (postCompactStart < agentHistory.size) {
                addAll(agentHistory.subList(postCompactStart, agentHistory.size))
            }
        }
    }

    Log.w(ChatViewModel.TAG, "[Compact] effectiveAgentHistory: marker ${marker.id.take(8)} unresolvable in agentHistory (size=${agentHistory.size}); returning full history")
    return agentHistory.toList()
}

/**
 * [T-android-progressive-trim] Apply progressive context trimming to an
 * outgoing history projection. No-op (same list instance) unless the
 * untrimmed estimate crosses a trim tier against the model's context
 * window. See [ProgressiveTrim] for the tier ladder and safety
 * invariants; the level is deliberately computed from the UNTRIMMED
 * estimate so it cannot oscillate between turns.
 *
 * v1 (legacy) compact paths intentionally bypass this — their behavior is
 * frozen by contract ("original behavior preserved unchanged").
 */
internal fun ChatViewModel.progressivelyTrimmed(list: List<LLMMessage>): List<LLMMessage> {
    if (list.size <= ProgressiveTrim.PROTECTED_RECENT_MESSAGES) return list
    val window = effectiveContextWindowTokens() ?: return list
    val untrimmed = estimateMessagesTokens(list)
    val level = ProgressiveTrim.levelFor(untrimmed, window)
    if (level == ProgressiveTrim.Level.NONE) return list
    val trimmed = ProgressiveTrim.trim(list, level)
    if (trimmed !== list) {
        AppLogger.info(
            ChatViewModel.TAG,
            "[ProgressiveTrim] level=$level window=$window untrimmedEst=$untrimmed " +
                "messages=${list.size} — stripped old reasoning / stubbed large tool outputs",
        )
    }
    return trimmed
}
