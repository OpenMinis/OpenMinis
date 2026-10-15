package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal suspend fun ChatViewModel.runTwoShotCompact(
    messages: List<LLMMessage>,
    previousSummary: String?,
    stages: List<ChatViewModel.CompactAttempt>,
    timeoutMs: Long,
): String {
    val existing = previousSummary
    if (stages.isEmpty()) {
        throw IllegalStateException("No LLM provider available for compaction")
    }
    var lastError: Exception? = null
    // [T-compact-reduced-retry] Both full-size stages failed. Truncation
    // is the LAST resort because it discards history outright, so try one
    // more pass on a reduced input first: the newest slice of the range
    // plus the previous summary. A provider that refused for size or
    // complexity reasons can often still answer a smaller request, and
    // keeping the tail preserves the most recent turns either way.
    //
    // [T-compact-fallback-walk] The reduced pass re-walks the SAME stage
    // list. Resetting compactLeafAttempt between passes used to make the
    // leaf re-resolve to the session model only — so a session provider
    // that was down outright (auth, 5xx) burned the reduced shot on the
    // same broken endpoint and the fallback models never saw it. A
    // smaller request rescues a size failure; a different provider
    // rescues an outage; the reduced pass needs both.
    val reduced = ChatViewModel.reducedCompactRetryInput(messages)
    val passes = buildList {
        add(messages to false)
        if (reduced != null) add(reduced to true)
    }
    for ((input, isReduced) in passes) {
        if (isReduced) {
            AppLogger.warning(
                ChatViewModel.TAG,
                "[Compact] all stages failed (${lastError?.message}); retrying on reduced " +
                    "input ${input.size}/${messages.size} messages before truncating",
            )
            withContext(Dispatchers.Main) {
                appendSystemInfo(
                    "All compact attempts failed (${lastError?.message ?: "no output"}). " +
                        "Retrying on a smaller slice before dropping older context.",
                    "compact",
                )
            }
            compactCallsIssued.set(0)
        }
        for ((index, stage) in stages.withIndex()) {
            val stageNo = index + 1
            compactLeafAttempt = stage
            _compactProgress.value = _compactProgress.value?.copy(
                stage = stageNo,
                stageBudget = stages.size,
                stageKind = stage.kind,
                modelLabel = stage.label,
                nextHint = stage.nextHint,
                callsIssued = stageNo,
                callBudget = stages.size,
            )
            val headline = when (stage.kind) {
                "fallback" -> "Compressing ($stageNo/${stages.size}) with fallback model ${stage.label}."
                "session-retry" -> "Compressing ($stageNo/${stages.size}) retrying current model ${stage.label}. No fallback set."
                else -> "Compressing ($stageNo/${stages.size}) with current model ${stage.label}."
            } + when (stage.nextHint) {
                "fallback" -> " If this fails, the next model is tried."
                "session-retry" -> " If this fails, the current model is tried once more."
                else -> " If this fails, older context is truncated."
            }
            withContext(Dispatchers.Main) {
                appendSystemInfo(headline, "compact")
            }
            try {
                val stageTimeout = minOf(timeoutMs, 120_000L)
                val text = withTimeout(stageTimeout) {
                    generateCompactSummaryWithSplitting(input, existing, 0)
                }.trim()
                // [T-compact-summary-quality] A short or off-target summary
                // silently poisons every later turn: the model then reasons
                // from a summary that dropped the user's actual goal. Treat a
                // failing check as a stage failure so the next stage (or the
                // reduced pass) still gets a chance.
                if (text.isNotEmpty() && ChatViewModel.isCompactSummaryAcceptable(text, input)) {
                    if (isReduced) {
                        AppLogger.info(
                            ChatViewModel.TAG,
                            "[Compact] reduced-input retry succeeded via ${stage.label} (${text.length} chars)",
                        )
                    }
                    return text
                }
                lastError = IllegalStateException(
                    if (text.isEmpty()) "empty compact summary from ${stage.label}"
                    else "compact summary from ${stage.label} failed the quality check " +
                        "(${text.length} chars)",
                )
            } catch (e: CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException) {
                    lastError = e
                    AppLogger.warning(ChatViewModel.TAG, "[Compact] stage ${stage.kind} timed out")
                } else {
                    throw e
                }
            } catch (e: Exception) {
                lastError = e
                AppLogger.warning(ChatViewModel.TAG, "[Compact] stage ${stage.kind} failed: ${e.message}")
            } finally {
                compactLeafAttempt = null
            }
        }
    }
    AppLogger.warning(ChatViewModel.TAG, "[Compact] compaction exhausted, truncating older context: ${lastError?.message}")
    withContext(Dispatchers.Main) {
        appendSystemInfo(
            "Compaction could not summarize (${lastError?.message ?: "no output"}). " +
                "Truncating older context to continue.",
            "compact",
        )
    }
    return existing?.takeIf { it.isNotBlank() }
        ?: "Earlier conversation was truncated after compaction failed. Recent turns remain below."
}
