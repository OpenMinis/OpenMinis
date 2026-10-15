package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.db.CompactMarkerEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.openminis.app.R

/**
 * [T-android-auto-compact-inloop] Compact the session.
 *
 * [allowDuringProcessing] lets the in-loop guard in [runAgentLoop] compact
 * BETWEEN agent iterations, where `_isStreaming` is legitimately true. All
 * user-initiated paths keep the default (false) so the "can't compact while
 * a turn is running" guard is unchanged for them. Re-entrancy is still
 * covered by [_isCompacting]. Mirrors iOS f70ac173.
 *
 * [onFinished] fires on the IO coroutine once the compaction attempt has
 * settled (success or failure), so the loop can await it before issuing the
 * next API call — the function itself is fire-and-forget.
 */
/**
 * Public entry: guarantees [onFinished] is invoked exactly once even when a
 * precondition rejects the request before any work is launched. The inner
 * implementation has many early returns; wrapping it here is safer than
 * threading a callback through each one, and it means an in-loop caller can
 * never hang waiting for a callback that was skipped.
 */
internal fun ChatViewModel.compactAll(
    anchorIdxOverride: Int? = null,
    allowDuringProcessing: Boolean = false,
    onFinished: ((Boolean) -> Unit)? = null,
) {
    var started = false
    compactAllImpl(anchorIdxOverride, allowDuringProcessing, onFinished) { started = true }
    if (!started) onFinished?.invoke(false)
}

private inline fun ChatViewModel.compactAllImpl(
    anchorIdxOverride: Int?,
    allowDuringProcessing: Boolean,
    noinline onFinished: ((Boolean) -> Unit)?,
    markStarted: () -> Unit,
) {
    AppLogger.info(ChatViewModel.TAG, "[Compact] compactAll() invoked streaming=${_isStreaming.value} compacting=${_isCompacting.value} historySize=${agentHistory.size} anchorOverride=$anchorIdxOverride inLoop=$allowDuringProcessing")
    if (_isStreaming.value && !allowDuringProcessing) {
        AppLogger.info(ChatViewModel.TAG, "[Compact] aborted: stream in progress")
        appendSystemInfo(
            text = context.getString(R.string.vm_compact_in_progress_blocked),
            iconKind = "compact",
        )
        return
    }
    if (_isCompacting.value) {
        AppLogger.info(ChatViewModel.TAG, "[Compact] aborted: another compact already in flight")
        appendSystemInfo(
            text = context.getString(R.string.vm_compact_already_running),
            iconKind = "compact",
        )
        return
    }
    val provider = currentProvider ?: run {
        appendSystemInfo(context.getString(R.string.vm_compact_no_provider), "compact")
        return
    }
    val history = agentHistory.toList()
    if (history.isEmpty()) {
        appendSystemInfo(context.getString(R.string.vm_compact_empty_session), "compact")
        return
    }
    // ─── v2 unified anchor model ───────────────────────────────────
    //
    // anchor = last active agentHistory entry. The compacted range is
    // `[prev marker anchor + 1, anchor]` (or `[0, anchor]` if no prev),
    // so each compact "extends" the latest summary forward to cover all
    // new turns. effectiveAgentHistory then re-injects the LAST N
    // user-text turns LEADING UP TO the anchor as fresh context, so the
    // model still sees recent verbatim content alongside the summary.
    //
    // Mirrors iOS post-Phase-v2: anchor = last active message, no
    // "auto-keep tail" baked into the compacted range — that's a
    // read-side decoration done by effectiveAgentHistory.
    //
    // anchor must be a persisted entry (have a non-null dbMessageId).
    // The strict iOS check also requires id ∈ rawMessages DB, but DAO
    // is suspend and we'd have to relocate range calculation into the
    // launch below. As a compromise we do the dbMessageId-non-empty
    // pre-check here (catches most stale-id cases at this stage), and
    // do the rawDbIds-membership check inside the launch before the
    // marker is written. Mirrors iOS AIChatViewModel+Compaction.swift:
    // 644-657 "walk back through agentHistory looking for dbMessageId
    // AND allRaw.contains" — split across two phases to honor suspend
    // boundaries.
    val anchorIdx: Int = if (anchorIdxOverride != null) {
        // compactBefore() supplied a specific anchor — walk back from
        // there to the closest entry with a dbMessageId (mirrors the
        // tail-walk-back logic but bounded to [0..override]).
        var i = anchorIdxOverride.coerceIn(0, history.lastIndex)
        while (i >= 0 && history[i].dbMessageId.isNullOrEmpty()) i -= 1
        i
    } else {
        // compactAll() — walk back from the tail to the closest
        // persisted entry. iOS compactAll calls compactBefore with the
        // last active UI message; we go through agentHistory directly
        // since Android's agentHistory and UI list are tighter-coupled.
        var i = history.lastIndex
        while (i >= 0 && history[i].dbMessageId.isNullOrEmpty()) i -= 1
        i
    }
    if (anchorIdx < 0) {
        appendSystemInfo("Cannot compact: no persisted messages yet.", "compact")
        return
    }

    // Slice to compact = (prev marker's anchor + 1) … anchorIdx inclusive.
    // For v2 prev markers, lastCompactedMessageId IS the prev anchor —
    // start at prevIdx + 1. For v1 prev markers, firstKeptMessageId points
    // at "first kept" — start AT prevIdx (it was exclusive on right edge).
    val prev = _cachedLatestMarker
    val effectiveStartIdx: Int = if (prev == null) {
        0
    } else {
        val prevAnchorOrFirstKept: String? = if (prev.version >= 2) {
            prev.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
        } else {
            prev.firstKeptMessageId?.takeIf { it.isNotEmpty() }
                ?: prev.boundaryMessageId?.takeIf { it.isNotEmpty() }
        }
        val prevIdx = prevAnchorOrFirstKept?.let { id ->
            history.indexOfFirst { it.dbMessageId == id }
        } ?: -1
        if (prevIdx < 0) 0   // prev anchor not in current history — restart from top
        else if (prev.version >= 2) prevIdx + 1
        else prevIdx
    }
    if (effectiveStartIdx > anchorIdx) {
        appendSystemInfo(context.getString(R.string.vm_compact_already_compacted), "compact")
        return
    }
    val toCompact = history.subList(effectiveStartIdx, anchorIdx + 1)
    if (toCompact.isEmpty()) {
        appendSystemInfo(context.getString(R.string.vm_compact_nothing_to_compact), "compact")
        return
    }
    // Past every precondition — from here the launch below owns the
    // onFinished callback.
    markStarted()
    _isCompacting.value = true
    // [T-android-compact-runaway] Size the wall-clock budget off the actual
    // transcript, so a long first compaction is not cut off by a limit
    // tuned for a short one. Measured on the same truncated transcript the
    // request will carry, not the raw history.
    val transcriptChars = buildConversationTextForSummary(toCompact).length
    val timeoutMs = ChatViewModel.compactTimeoutMsFor(transcriptChars)
    compactCallsIssued.set(0)
    val stages = compactStagePlan()
    _compactProgress.value = ChatViewModel.CompactProgress(
        startedAtMs = System.currentTimeMillis(),
        depth = 0,
        callsIssued = 0,
        callBudget = ChatViewModel.COMPACT_STAGE_BUDGET,
        timeoutSeconds = (timeoutMs / 1000L).toInt(),
        stage = 1,
        stageBudget = stages.size.coerceAtLeast(1),
        stageKind = stages.firstOrNull()?.kind ?: "session",
        modelLabel = stages.firstOrNull()?.label ?: "session",
        nextHint = stages.firstOrNull()?.nextHint ?: "",
    )
    AppLogger.info(
        ChatViewModel.TAG,
        "[Compact] starting two-shot: ${toCompact.size} entries, ${transcriptChars} transcript chars, " +
            "timeout=${timeoutMs / 1000}s, stages=${stages.joinToString { it.kind }}",
    )
    compactJob = viewModelScope.launch(Dispatchers.IO) {
        // [T-android-compact-queued-drain] Only a SUCCESSFUL compact kicks
        // the queued-prompt drain below; failure/cancel/empty-summary paths
        // keep today's behavior (queued bubbles stay pending + cancellable).
        var compactSucceeded = false
        // Distinguishes "we gave up on time" from other failures so the
        // user-facing message can say so and invite a retry.
        var timedOut = false
        try {
            val existing = _compactSummary.value
            val summary = runTwoShotCompact(
                messages = toCompact,
                previousSummary = existing,
                stages = stages,
                timeoutMs = timeoutMs,
            )
            if (summary.isEmpty()) {
                withContext(Dispatchers.Main) {
                    appendSystemInfo("Compaction produced no output — try again later.", "compact")
                }
                return@launch
            }

            val sid = realSessionId.ifEmpty { sessionId }
            // v2 marker: lastCompactedMessageId IS the anchor — single
            // source of truth. The anchor we resolved above is guaranteed
            // to have a persisted dbMessageId. Legacy fields (firstKept /
            // boundary / sortOrder) stay null/MAX so a downgraded reader
            // sees "everything compacted, nothing kept" as a graceful
            // fallback rather than a stale boundary.
            // Re-resolve anchor: now that we're inside an IO coroutine
            // we can read the messages DB to verify the dbMessageId is
            // actually persisted, not just set on the in-memory
            // LLMMessage. iOS does this belt-and-suspenders check
            // (AIChatViewModel+Compaction.swift:644-657). Walk back from
            // the original anchorIdx until we find an entry whose id is
            // both non-empty AND present in rawDbIds.
            val rawDbIds: Set<String> = try {
                chatRepository.loadMessageIds(sid)
            } catch (e: Exception) {
                Log.w(ChatViewModel.TAG, "[Compact] loadMessages for raw-id verify failed: ${e.message}")
                emptySet()
            }
            val verifiedAnchorIdx: Int = if (rawDbIds.isEmpty()) {
                // DB read failed; trust the in-memory walk-back result.
                anchorIdx
            } else {
                var i = anchorIdx
                while (i >= 0) {
                    val id = history[i].dbMessageId
                    if (!id.isNullOrEmpty() && id in rawDbIds) break
                    i -= 1
                }
                i
            }
            if (verifiedAnchorIdx < 0) {
                Log.w(ChatViewModel.TAG, "[Compact] No agentHistory entry has a DB-persisted dbMessageId; aborting")
                withContext(Dispatchers.Main) {
                    appendSystemInfo("Compact failed: could not anchor to a persisted message.", "compact")
                }
                return@launch
            }
            if (verifiedAnchorIdx != anchorIdx) {
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[Compact] anchor walked back from idx=$anchorIdx to idx=$verifiedAnchorIdx " +
                        "(closest with id in rawDbIds). Unsynced tail entries will fall on the active side of the divider.",
                )
            }
            val lastCompactedDbId = history[verifiedAnchorIdx].dbMessageId
                ?: run {
                    Log.w(ChatViewModel.TAG, "[Compact] verified anchor at idx=$verifiedAnchorIdx lost dbMessageId; aborting")
                    withContext(Dispatchers.Main) {
                        appendSystemInfo("Compact failed: anchor message id unavailable.", "compact")
                    }
                    return@launch
                }
            val marker = CompactMarkerEntity(
                id = java.util.UUID.randomUUID().toString(),
                sessionId = sid,
                summary = summary,
                firstKeptSortOrder = Int.MAX_VALUE,   // legacy field; v2 ignores
                compactedCount = toCompact.size,
                createdAt = System.currentTimeMillis(),
                uiBoundarySortOrder = null,
                boundaryMessageId = null,
                firstKeptMessageId = null,
                lastCompactedMessageId = lastCompactedDbId,
                version = 2,
                // [T-compact-chunk-pool] Roll this pass's summary into
                // the retrieval pool carried by the previous marker, so
                // earlier material stays retrievable instead of being
                // folded into one ever-changing blob.
                summaryChunks = ChatViewModel.appendSummaryChunk(prev?.summaryChunks, summary),
            )
            val markerPersisted = runCatching {
                chatRepository.dao.insertCompactMarker(marker)
                true
            }.getOrElse {
                Log.w(ChatViewModel.TAG, "Failed to persist compact marker: ${it.message}")
                false
            }
            if (!markerPersisted) {
                withContext(Dispatchers.Main) {
                    appendSystemInfo("Compaction failed: could not save the compact marker.", "compact")
                }
                return@launch
            }
            _compactSummary.value = summary
            // Keep the marker in memory so effectiveAgentHistory() can
            // resolve the boundary on the very next outgoing turn.
            // Mirrors iOS `cachedLatestMarker = marker`.
            _cachedLatestMarker = marker
            // [T-android-compact-delivery] Verify the marker actually
            // delivers: the next outgoing projection must resolve the
            // boundary (anchor found in agentHistory). A marker that
            // cannot be resolved makes effectiveAgentHistory() degrade
            // to the FULL history — the compaction silently does nothing
            // and the next request re-sends everything (the exact
            // "compact didn't help" symptom). Fail loudly here instead.
            val anchorId = marker.lastCompactedMessageId
            val anchorResolved = marker.version < 2 || anchorId.isNullOrEmpty() ||
                agentHistory.any { it.dbMessageId == anchorId }
            if (!anchorResolved) {
                Log.w(
                    ChatViewModel.TAG,
                    "[Compact] marker ${marker.id.take(8)} persisted but anchor " +
                        "${anchorId?.take(8)} does not resolve (agentHistory=${agentHistory.size}); " +
                        "next request would carry the full history",
                )
                withContext(Dispatchers.Main) {
                    appendSystemInfo(
                        "Compaction saved, but the boundary could not be resolved; the next request still carries the full history.",
                        "compact",
                    )
                }
            }
            // Fresh anchor sits inside the window by construction.
            _compactMarkerDetached = false
            // [T-android-compact-stale-usage] Same stale-reading hazard the
            // in-loop path fixed ([T-android-auto-compact-inloop]): the last
            // usage chunk describes the PRE-compaction payload, and
            // refreshContextUsage() prefers it over the estimate. Zero it so
            // the next reading comes from the effective-history estimate, and
            // refresh now — the usage sheet / context ring otherwise keep
            // reporting the pre-compact size until some future turn completes
            // (observed on device: sheet showed 659.5K after a compact that
            // took the next-turn payload to ~115K).
            _lastTurnContextTokens.value = 0
            withContext(Dispatchers.Main) {
                refreshContextUsage()
                // Gray out everything in the compacted range; the kept
                // tail (last N user turns + tool/assistant follow-ups)
                // stays full opacity. Determined by walking _messages
                // until we pass the row whose id == lastCompactedDbId.
                //
                // Also drop any prior compact-divider system rows — a
                // session shows at most one divider (the latest marker).
                // Those old dividers are stored as system messages with
                // a "compact" iconKind in toolBlocks[0].toolName.
                val cutoffId: String = lastCompactedDbId
                var passedCutoff = false   // anchor is guaranteed non-null in v2
                val cleaned = _messages.value
                    .filterNot { msg ->
                        // Drop prior compact-divider rows; appendSystemInfo
                        // below will re-add the new one.
                        msg.role == "system" &&
                            msg.toolBlocks.firstOrNull()?.toolName == "compact"
                    }
                    .map { msg ->
                        if (msg.role == "system") msg
                        else if (passedCutoff) msg
                        else {
                            val grayed = if (msg.isCompactedHistory) msg
                                else msg.copy(isCompactedHistory = true)
                            if (msg.id == cutoffId) passedCutoff = true
                            grayed
                        }
                    }
                // T84: count UI bubbles in this pass's compacted range.
                // Filters: role != system (dividers/notices don't count).
                // Range: everything up to and including the cutoff row,
                // since the kept-tail starts immediately after.
                // Falls back to "all non-system" when cutoffId is null
                // (compact-everything path), matching iOS dividerInsertIdx
                // == messages.count behavior.
                //
                // We deliberately do NOT exclude `isCompactedHistory` rows.
                // Back-to-back compacts (or compact after restoring a prior
                // marker on session reload) leave the in-range rows already
                // grayed; excluding them produced "0 messages compacted"
                // even though `toCompact.size` was nonzero. The divider's
                // count should reflect the size of THIS pass's range, not
                // the delta of newly-grayed rows.
                val cutoffIdx = cleaned.indexOfLast { it.id == cutoffId }
                val compactedUICount = if (cutoffIdx < 0) {
                    cleaned.count { it.role != "system" }
                } else {
                    cleaned.take(cutoffIdx + 1).count { it.role != "system" }
                }
                _messages.value = cleaned
                AppLogger.info(ChatViewModel.TAG, "[Compact] divider: $compactedUICount UI bubbles compacted (history entries: ${toCompact.size})")
                appendSystemInfo(
                    text = "$compactedUICount messages compacted",
                    iconKind = "compact",
                    payload = summary,
                )
            }
            compactSucceeded = true
        } catch (e: TimeoutCancellationException) {
            // [T-android-compact-runaway] MUST precede the CancellationException
            // arm — TimeoutCancellationException extends it, so the generic
            // re-throw would otherwise swallow our own timeout and surface it
            // as a silent cancel with no message.
            timedOut = true
            val elapsed = (timeoutMs / 1000L).toInt()
            val calls = compactCallsIssued.get()
            Log.w(ChatViewModel.TAG, "[Compact] timed out after ${elapsed}s ($calls model call(s) issued)")
            withContext(Dispatchers.Main) {
                appendSystemInfo(
                    text = context.getString(R.string.vm_compaction_timed_out, elapsed, calls),
                    iconKind = "compact",
                )
            }
        } catch (e: CancellationException) {
            // User-initiated (cancelCompact) or scope teardown. Tell the
            // user only if they are still around to read it; the `finally`
            // below releases the lock either way.
            if (compactJob?.isCancelled == true) {
                runCatching {
                    withContext(NonCancellable + Dispatchers.Main) {
                        appendSystemInfo("Compaction cancelled.", "compact")
                    }
                }
            }
            throw e
        } catch (e: Exception) {
            Log.w(ChatViewModel.TAG, "Compact failed", e)
            withContext(Dispatchers.Main) {
                appendSystemInfo(
                    text = context.getString(R.string.vm_compaction_failed, e.message ?: e.javaClass.simpleName),
                    iconKind = "compact",
                )
            }
        } finally {
            _isCompacting.value = false
            _compactProgress.value = null
            AppLogger.info(
                ChatViewModel.TAG,
                "[Compact] finished: success=$compactSucceeded timedOut=$timedOut " +
                    "calls=${compactCallsIssued.get()}",
            )
            // [T-android-auto-compact-inloop] Signal the awaiting in-loop
            // caller. In `finally` so a thrown/cancelled compaction can
            // never strand the agent loop waiting on a callback.
            onFinished?.invoke(compactSucceeded)
        }
        // [T-android-compact-queued-drain] A successful compact must let
        // any queued prompts proceed — previously nothing re-triggered the
        // drain after compact (loop-end / cancel / tool-boundary are the
        // only drain triggers), so a prompt sitting in the queue when a
        // compact ran stayed in the dashed "queued" state forever. Reuse
        // resumeQueueAfterCancel: it re-checks queue-non-empty + not-
        // streaming + not-compacting after its grace delay (so an ✕ tap at
        // the compact-finish instant is a clean no-op), refreshes OAuth,
        // and drains through the normal stream-slot machinery — no new
        // reentrancy path. Runs after `finally` so isCompacting is already
        // false. Mirrors the iOS fix for the same report.
        if (compactSucceeded && _promptQueue.value.isNotEmpty()) {
            AppLogger.info(ChatViewModel.TAG, "[Compact] success with ${_promptQueue.value.size} queued prompt(s) — kicking drain")
            resumeQueueAfterCancel()
        }
    }
}
