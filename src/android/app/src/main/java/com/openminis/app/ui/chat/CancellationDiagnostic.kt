package com.openminis.app.ui.chat

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * [T-cancel-diag] Structured diagnostics for real job cancellations inside
 * the agent loop (ChatViewModelAgentLoopExt's catch classifier).
 *
 * The problem this solves: a real job cancellation (`e.cause == null` in the
 * catch) is rethrown verbatim and leaves NO trace — when a user reports
 * "my run just stopped" or the resume-banner race (T-android-resume-banner-race)
 * class of bugs, logcat has no structured record of WHO stopped the loop,
 * WHEN, or on which session/model. The cancel sources live upstream
 * (ChatViewModel.kt:5572 "Stopped by user", :1931 "compact cancelled by
 * user", epoch expiry, VM teardown), but the cancellation exception's
 * message carries the stop reason string verbatim — that IS the "who".
 *
 * Recorded fields: source (why the scope was cancelled), model, session,
 * streaming state, timestamp (from Logcat itself). Filter `CancelDiag` in
 * logcat to get the cancellation timeline for any session.
 *
 * Pure decision core [recordLine] is unit tested; the Log call is not.
 */
internal object CancellationDiagnostic {

    const val TAG = "CancelDiag"

    /** Cancel reason strings worth a dedicated source label. */
    private const val REASON_STOPPED = "Stopped by user"
    private const val REASON_COMPACT = "compact cancelled by user"

    /**
     * Pure decision core: classify a real job cancellation and build the
     * one-line structured record.
     *
     * [reason] is the cancellation exception's message — null means a bare
     * scope cancellation (VM teardown / scope close), a non-null string is
     * the stop reason carried by `job.cancel(CancellationException(...))`.
     */
    internal fun recordLine(
        model: String,
        sessionId: String,
        reason: String?,
        streaming: Boolean,
    ): String {
        val source = when {
            reason == null -> "scope-cancel"
            reason.startsWith(REASON_STOPPED) -> "user-stop"
            reason.startsWith(REASON_COMPACT) -> "compact-cancel"
            else -> "other"
        }
        return buildString {
            append("source=").append(source)
            append(" model=").append(model)
            append(" session=").append(sessionId)
            append(" streaming=").append(streaming)
            if (reason != null) {
                append(" reason=\"")
                // Log lines are single-line; strip control chars/newlines so
                // a multi-line reason can't split the record.
                append(reason.take(120).replace(Regex("[\\r\\n\\t]+"), " "))
                append('"')
            }
        }
    }

    /**
     * Record + log. Called from the agent loop's real-cancellation branch
     * right before the rethrow — logging must not throw, so the whole call
     * is defensive.
     */
    fun log(model: String, sessionId: String, e: CancellationException, streaming: Boolean) {
        try {
            Log.w(TAG, "agent-loop cancelled: ${recordLine(model, sessionId, e.message, streaming)}")
        } catch (_: Exception) {
            // Diagnostics must never mask the rethrow path.
        }
    }
}
