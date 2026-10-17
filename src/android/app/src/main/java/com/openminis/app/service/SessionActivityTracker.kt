package com.openminis.app.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Singleton that tracks two independent kinds of "session is alive":
 *
 *  - `activeSessions`: at least one [com.openminis.app.ui.chat.ChatViewModel.streamJob]
 *    is in flight (LLM call, tool execution).
 *  - `presentSessions` (T166): the user is sitting on a chat screen
 *    with the composer mounted, regardless of whether a stream is
 *    running. Drives the FG service so backgrounding the chat for a
 *    minute doesn't drop the process to adj=700 and get it reclaimed.
 *
 * The foreground service runs whenever EITHER set is non-empty, so
 * the user holds a stable adj=200 across reading, composing, and
 * streaming alike. It stops only when both are empty (user has
 * navigated back to Sessions list AND no stream is in flight).
 */
object SessionActivityTracker {

    private const val TAG = "SessionTracker"

    // [T-fgs-stop-linger] Owns the deferred service stop. See
    // [ServiceStopLinger] for the crossing this closes.
    private val trackerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stopLinger = ServiceStopLinger(
        scope = trackerScope,
        onStop = { stopService() },
    )

    /**
     * Test seam: true while a deferred service stop is scheduled. The stop
     * itself is a no-op without an initialized [appContext], so unit tests
     * observe the linger wiring through this instead.
     */
    val isStopPendingForTest: Boolean get() = stopLinger.isPending

    private val _activeSessions = MutableStateFlow<Set<String>>(emptySet())
    val activeSessions: StateFlow<Set<String>> = _activeSessions.asStateFlow()

    /**
     * T166: sessions the user is currently *present in* (composing /
     * reading), distinct from [activeSessions] which tracks streaming.
     * Drives the foreground service so the process stays at adj=200
     * the entire time the user is inside a chat — not just while the
     * stream is in flight. Without this, hitting Home from a chat
     * drops the process to adj=700 (LAST) and a Pixel 4a will reclaim
     * within minutes under any memory pressure, forcing a full
     * Activity rebuild on return.
     */
    private val _presentSessions = MutableStateFlow<Set<String>>(emptySet())
    val presentSessions: StateFlow<Set<String>> = _presentSessions.asStateFlow()

    private val _currentToolStatus = MutableStateFlow("Idle")
    val currentToolStatus: StateFlow<String> = _currentToolStatus.asStateFlow()

    /**
     * [T-android-live-update-completed] `SystemClock.elapsedRealtime()` at the
     * moment the LAST active session finished, i.e. when [activeSessions] went
     * non-empty → empty. Null while anything is still streaming, and reset to
     * null by [setActive] so a fresh run never inherits a stale finish stamp.
     *
     * Why this exists: the foreground service keeps running after the task ends
     * (the user is still *present* in the chat — see [shouldRunService]), so the
     * ongoing notification / Android 16 Live Update chip stays on screen. Its
     * elapsed timer was computed from the SERVICE start time, which meant it
     * kept ticking up long after the agent stopped working — users reported the
     * dynamic island showing a running task that had already completed.
     *
     * With this stamp the notification can freeze the timer at the real task
     * duration and swap in a completed icon + label. Mirrors iOS
     * `AgentActivityAttributes.ContentState.finishedAt`, which drives the same
     * "static total run time" resting state there.
     */
    private val _lastTaskFinishedAtMs = MutableStateFlow<Long?>(null)
    val lastTaskFinishedAtMs: StateFlow<Long?> = _lastTaskFinishedAtMs.asStateFlow()

    /**
     * [T-android-live-update-completed] `SystemClock.elapsedRealtime()` at the
     * moment the CURRENT run began — i.e. when [activeSessions] went empty →
     * non-empty. Null while nothing is running.
     *
     * The foreground service's own `startTimeMs` is stamped once in onCreate and
     * covers the whole *presence* window (the service outlives individual tasks),
     * so it answers "how long have you been in this chat", not "how long did this
     * task take". Anchoring the notification's elapsed time here instead makes
     * the displayed duration mean the run, and makes consecutive runs in one
     * sitting each start from zero rather than accumulating.
     */
    private val _currentRunStartedAtMs = MutableStateFlow<Long?>(null)
    val currentRunStartedAtMs: StateFlow<Long?> = _currentRunStartedAtMs.asStateFlow()

    /** Publish notification-relevant run boundaries atomically, after all individual
     * tracker flows have settled. Prevents a new-run chip from first rendering
     * the previous run's anchor or a completion from briefly restarting its timer. */
    data class NotificationTimeline(
        val activeCount: Int = 0,
        val startedAtMs: Long? = null,
        val finishedAtMs: Long? = null,
    )
    private val _notificationTimeline = MutableStateFlow(NotificationTimeline())
    val notificationTimeline: StateFlow<NotificationTimeline> = _notificationTimeline.asStateFlow()

    private fun publishNotificationTimeline() {
        _notificationTimeline.value = NotificationTimeline(
            activeCount = _activeSessions.value.size,
            startedAtMs = _currentRunStartedAtMs.value,
            finishedAtMs = _lastTaskFinishedAtMs.value,
        )
    }

    /**
     * T-bg-overlay phase 1: tool name currently dispatched to the agent
     * (e.g. "shell_execute", "browser_use"). null when no tool is in
     * flight (idle, or between tool calls within a turn). The FGS
     * notification reads this to render a tool-specific icon + display
     * label without parsing [currentToolStatus]'s freeform string.
     */
    private val _currentToolName = MutableStateFlow<String?>(null)
    val currentToolName: StateFlow<String?> = _currentToolName.asStateFlow()

    /**
     * [T-android-overlay-tool-title] Model-supplied `tool_title` for the
     * tool currently in flight (e.g. "Open Baidu home page", "Take screenshot of
     * current page"). Null when the model didn't supply one OR no tool is
     * running. The overlay capsule and notification prefer this over the
     * static per-tool label ("Browser", "Shell", …) so users see the
     * actual intent of the call rather than just the tool kind.
     *
     * Populated from the dispatch loop in [com.openminis.app.ui.chat.ChatViewModel]
     * by reading the `tool_title` arg uniformly for ALL tools — so
     * browser_use (which has no per-tool status override) surfaces the
     * title alongside shell_execute and friends.
     */
    private val _currentToolTitle = MutableStateFlow<String?>(null)
    val currentToolTitle: StateFlow<String?> = _currentToolTitle.asStateFlow()

    /**
     * T-bg-overlay phase 1: true while a tool call is actively executing
     * (between dispatch and result). Drives the notification's
     * indeterminate progress bar so the user can tell at a glance whether
     * Minis is "between turns" (false → no progress) vs "doing something"
     * (true → spinning bar).
     */
    private val _isToolRunning = MutableStateFlow(false)
    val isToolRunning: StateFlow<Boolean> = _isToolRunning.asStateFlow()

    /**
     * T-overlay-glyph-typed-outcome: typed outcome of the most recently
     * completed tool call. Replaces the old text-sniffing heuristic in
     * [ToolOverlayController] (which read stale "Running: foo" status
     * text and always inferred success). Set by [clearToolRunning];
     * defaults to [ToolOutcome.Unknown] until any tool finishes.
     */
    private val _lastToolOutcome = MutableStateFlow(ToolOutcome.Unknown)
    val lastToolOutcome: StateFlow<ToolOutcome> = _lastToolOutcome.asStateFlow()

    /**
     * Snapshot of the most recently completed tool's identity + status
     * line. Captured by [clearToolRunning] right before the live
     * [currentToolName] / [currentToolTitle] / [currentToolStatus] are
     * wiped, so the floating overlay can surface what the agent just did
     * after the run ends (e.g. "browser_use — Completed" + "Opened Google.com
     * in system Chrome") instead of a bare "Done". Cleared on
     * [dismissOverlay] and on [setActive] so a fresh run starts blank.
     */
    private val _lastToolName = MutableStateFlow<String?>(null)
    val lastToolName: StateFlow<String?> = _lastToolName.asStateFlow()

    private val _lastToolTitle = MutableStateFlow<String?>(null)
    val lastToolTitle: StateFlow<String?> = _lastToolTitle.asStateFlow()

    private val _lastToolStatus = MutableStateFlow<String?>(null)
    val lastToolStatus: StateFlow<String?> = _lastToolStatus.asStateFlow()

    /**
     * [T-android-overlay-reply-status-34599] Truncated excerpt of the
     * most recent assistant reply for the currently-tracked session.
     * Published by ChatViewModel via [publishLastReply] right before
     * [setInactive] so the overlay can show "what did Minis just say".
     * Null when no reply has been observed yet this session-cycle;
     * cleared when a fresh session goes active (so the previous
     * session's reply doesn't bleed into a newly-started turn).
     */
    private val _lastReplyExcerpt = MutableStateFlow<String?>(null)
    val lastReplyExcerpt: StateFlow<String?> = _lastReplyExcerpt.asStateFlow()

    /**
     * [T-android-overlay-reply-status-34599] Session ID associated with
     * [lastReplyExcerpt] and the current activity. Drives the
     * "tap-overlay → open chat" intent in [ToolOverlayController] by
     * synthesising a `minis://session/<id>` deep-link the existing
     * DeepLinkHandler already understands.
     */
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    /** Max chars of the assistant reply we surface in the overlay. */
    private const val REPLY_EXCERPT_MAX = 72

    /**
     * [T-android-overlay-hide-camera] True while the user has launched the
     * system camera (ACTION_IMAGE_CAPTURE) from inside Minis Ultra and we're
     * waiting on the ActivityResult callback. The overlay observer in
     * [AgentForegroundService] gates `shouldShow` on this flag so the
     * floating capsule doesn't obstruct the camera viewfinder — Minis is
     * technically backgrounded during the capture (the camera Activity is
     * on top), which would otherwise satisfy the bg-only show rule from
     * #451. Cleared in the camera launcher's result callback (success,
     * cancel, or launch-failure) so a fresh bg event after the user
     * returns reactivates the overlay normally.
     */
    private val _cameraSuppressActive = MutableStateFlow(false)
    val cameraSuppressActive: StateFlow<Boolean> = _cameraSuppressActive.asStateFlow()

    fun setCameraSuppressActive(active: Boolean) {
        _cameraSuppressActive.value = active
    }

    private var appContext: Context? = null

    /**
     * The service should run iff at least one session is streaming OR
     * the user is currently present in at least one chat. Both inputs
     * are independently mutated, so we always recompute from the live
     * state flows rather than tracking a derived flag.
     */
    private fun shouldRunService(): Boolean =
        _activeSessions.value.isNotEmpty() || _presentSessions.value.isNotEmpty()

    /**
     * T50: per-session stream-cancel callbacks. Each ChatViewModel
     * registers its own [com.openminis.app.ui.chat.ChatViewModel.cancelStream]
     * here when [setActive] is called and unregisters in [setInactive].
     * The foreground service's notification "Stop" action calls
     * [cancelAllActiveStreams] which iterates this map — without it the
     * notification can only kill itself, leaving streamJobs running until
     * the OS reclaims the process. Held under the same Map lock as the
     * activeSessions flow so callers don't observe a transient state where
     * the session is "active" but has no canceller.
     */
    private val streamCancellers = mutableMapOf<String, () -> Unit>()

    /**
     * T180-bg-notif: per-session "task is finishing — was it cancelled?"
     * flag, set by [setInactive]'s callers via [setInactiveError] when
     * the streamJob unwinds because of an error (vs a clean completion).
     * Drives the success/error variant of the completion notification.
     * Cleared as soon as the listener has fired.
     */
    private val pendingErrorFlag = mutableSetOf<String>()

    /**
     * T180-bg-notif: completion listener. Wired in MinisApp.onCreate to
     * a [com.openminis.app.notification.BackgroundTaskNotifier] so when
     * an agent loop ends while the app is backgrounded, the user gets a
     * tap-to-open-session notification — mirrors iOS
     * `BackgroundKeepAliveManager.postBackgroundTaskNotification` (L274).
     *
     * Held as a single-slot setter rather than a list because there's
     * exactly one notifier per process. Keeping the tracker free of
     * direct ChatRepository / Notifier dependencies preserves its
     * "pure session-tracking" responsibility.
     */
    private var completionListener: ((sessionId: String, isError: Boolean) -> Unit)? = null

    fun setCompletionListener(listener: ((sessionId: String, isError: Boolean) -> Unit)?) {
        completionListener = listener
    }

    /**
     * [T-android-overlay-reply-status-34599] Push the most recent
     * assistant reply for [sessionId] into the overlay surface. The
     * text is collapsed to a single line and truncated to
     * [REPLY_EXCERPT_MAX] chars with an ellipsis when over budget so
     * the overlay capsule doesn't blow out horizontally. No-op when the
     * text is blank — we don't want to render an empty bubble that
     * looks like a UI bug.
     */
    fun publishLastReply(sessionId: String, fullText: String?) {
        val collapsed = fullText
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(" ")
            ?.takeIf { it.isNotBlank() } ?: return
        val excerpt = if (collapsed.length > REPLY_EXCERPT_MAX) {
            collapsed.substring(0, REPLY_EXCERPT_MAX).trimEnd() + "…"
        } else {
            collapsed
        }
        _currentSessionId.value = sessionId
        _lastReplyExcerpt.value = excerpt
    }

    /**
     * [T-android-overlay-reply-status-34599] User explicitly dismissed
     * the floating overlay (X button or tap-to-open-chat). Clears the
     * lingered reply state so the overlay observer in
     * [AgentForegroundService] flips its `shouldShow` predicate to
     * false and pulls the view down. The session activity itself stays
     * untouched — the agent loop continues; the user just chose to
     * stop being notified about it.
     */
    fun dismissOverlay() {
        _lastReplyExcerpt.value = null
        _lastToolOutcome.value = ToolOutcome.Unknown
        _lastToolName.value = null
        _lastToolTitle.value = null
        _lastToolStatus.value = null
    }

    /**
     * Initialize with application context. Must be called once at app startup.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Marks a session as active. Starts the foreground service if this is
     * the first active session. [onStop], when supplied, is the agent
     * loop's cancel callback — captured here so the notification's Stop
     * action can fan out to every running session.
     */
    @Synchronized
    fun setActive(sessionId: String, onStop: (() -> Unit)? = null) {
        // [T-fgs-stop-linger] A new run cancels any deferred stop — the
        // service is needed again, and the next startForegroundService()
        // must not race the teardown it would have triggered.
        stopLinger.cancel()
        val wasIdle = !shouldRunService()
        // [T-android-live-update-completed] Capture BEFORE mutating: a run
        // "begins" only on the empty → non-empty edge. A second concurrent
        // session joining an in-flight run must not restart the clock.
        val wasRunIdle = _activeSessions.value.isEmpty()
        _activeSessions.update { it + sessionId }
        if (onStop != null) {
            synchronized(streamCancellers) { streamCancellers[sessionId] = onStop }
        }
        // [T-android-overlay-reply-status-34599] Track which session is
        // driving the overlay so the tap-to-open intent lands in the
        // right chat. Clear the previous reply excerpt so the user
        // doesn't briefly see a stale reply attached to a fresh run.
        _currentSessionId.value = sessionId
        _lastReplyExcerpt.value = null
        _lastToolName.value = null
        _lastToolTitle.value = null
        _lastToolStatus.value = null
        // [T-android-live-update-completed] A new run supersedes any completed
        // resting state — drop the finish stamp so the notification goes back to
        // a live ticking timer instead of staying frozen at the previous total,
        // and re-anchor the clock so this run's duration starts from zero.
        if (wasRunIdle) {
            _lastTaskFinishedAtMs.value = null
            _currentRunStartedAtMs.value = SystemClock.elapsedRealtime()
        }
        publishNotificationTimeline()
        Log.d(TAG, "Session activated: $sessionId (total: ${_activeSessions.value.size})")

        if (wasIdle) {
            startServiceIfNeeded()
        } else {
            updateService()
        }
    }

    /**
     * Marks a session as inactive. Stops the foreground service if no
     * sessions remain active *and* the user is no longer present in any
     * chat.
     */
    @Synchronized
    fun setInactive(sessionId: String) {
        val wasActive = sessionId in _activeSessions.value
        _activeSessions.update { it - sessionId }
        synchronized(streamCancellers) { streamCancellers.remove(sessionId) }
        val wasError = synchronized(pendingErrorFlag) { pendingErrorFlag.remove(sessionId) }
        Log.d(TAG, "Session deactivated: $sessionId (total: ${_activeSessions.value.size})")

        if (_activeSessions.value.isEmpty()) {
            _currentToolStatus.value = "Idle"
            _currentToolName.value = null
            _currentToolTitle.value = null
            _isToolRunning.value = false
            // [T-android-live-update-completed] Stamp the finish moment so the
            // ongoing notification can freeze its elapsed timer at the real task
            // duration instead of tracking service uptime. Only set when the
            // session was genuinely active — a defensive setInactive for a
            // never-started session must not fake a completion.
            if (wasActive) {
                _lastTaskFinishedAtMs.value = SystemClock.elapsedRealtime()
            }
            // [T-android-overlay-reply-status-34599] Preserve the last
            // tool outcome AND the last reply excerpt across stream
            // teardown so the overlay observer in
            // [AgentForegroundService] can flip into "completed" mode
            // and render ✓ / ✗ + the assistant's reply text. Cleared
            // when the user dismisses the overlay via [dismissOverlay]
            // or starts a fresh run via [setActive].
            // Tag the error outcome here if the stream finalized with
            // an error (markStreamError was called); otherwise keep
            // whatever the last tool reported (Success / Error / etc.)
            // so the glyph reflects the actual end state.
            if (wasError) {
                _lastToolOutcome.value = ToolOutcome.Error
            }
        }
        publishNotificationTimeline()
        if (!shouldRunService()) {
            // [T-fgs-stop-linger] Deferred stop: a rapid follow-up command
            // (next probe / apt step in the same assembly) cancels this and
            // keeps one service instance, instead of crossing stopService()
            // with its own startForegroundService() at ActivityManager.
            stopLinger.schedule()
        } else {
            stopLinger.cancel()
            updateService()
        }
        // T180-bg-notif: fire the completion listener AFTER service state
        // is settled so the notifier sees a stable activeSessions count.
        // Skip if the session was never marked active (defensive — keeps
        // the "completed" semantic honest).
        if (wasActive) {
            completionListener?.invoke(sessionId, wasError)
        }
    }

    /**
     * T180-bg-notif: caller marks the session's stream as having ended in
     * an error. Must be invoked BEFORE [setInactive] (the flag is consumed
     * inside setInactive). If never called, the completion listener fires
     * with `isError=false`.
     */
    fun markStreamError(sessionId: String) {
        synchronized(pendingErrorFlag) { pendingErrorFlag.add(sessionId) }
    }

    /**
     * T166: marks the user as present in a chat (composer is mounted,
     * messages list is rendering). Idempotent. Starts the foreground
     * service the first time presence is recorded so the process holds
     * at adj=200 across Home / app-switcher / lock-screen, even when no
     * stream is in flight.
     */
    @Synchronized
    fun setPresent(sessionId: String) {
        if (sessionId in _presentSessions.value) return
        // [T-fgs-stop-linger] Presence re-holds the service the same way a
        // new active session does — cancel any deferred stop.
        stopLinger.cancel()
        val wasIdle = !shouldRunService()
        _presentSessions.update { it + sessionId }
        Log.d(TAG, "Presence set: $sessionId (present total: ${_presentSessions.value.size})")
        if (wasIdle) {
            startServiceIfNeeded()
        } else {
            updateService()
        }
    }

    /**
     * T166: counterpart of [setPresent]. Stops the foreground service
     * if both presence and streaming are now empty. Called from
     * MainActivity when the user leaves the chat route or the Activity
     * is paused.
     */
    @Synchronized
    fun setAbsent(sessionId: String) {
        if (sessionId !in _presentSessions.value) return
        _presentSessions.update { it - sessionId }
        Log.d(TAG, "Presence cleared: $sessionId (present total: ${_presentSessions.value.size})")
        if (!shouldRunService()) {
            // [T-fgs-stop-linger] see setInactive — deferred, cancellable.
            stopLinger.schedule()
        } else {
            stopLinger.cancel()
            updateService()
        }
    }

    /**
     * T166: clear all presence markers. Called only when MainActivity
     * is destroyed for real (not just paused) — Home press / lock
     * screen MUST keep presence so the FG service keeps the process
     * pinned at adj=200 across the user's brief absence. Backgrounding
     * is the entire scenario this exists to protect against.
     */
    @Synchronized
    fun clearPresence() {
        if (_presentSessions.value.isEmpty()) return
        _presentSessions.update { emptySet() }
        Log.d(TAG, "Presence cleared (all)")
        if (!shouldRunService()) {
            // [T-fgs-stop-linger] see setInactive — deferred, cancellable.
            stopLinger.schedule()
        } else {
            stopLinger.cancel()
            updateService()
        }
    }

    /**
     * Invoke every registered stream-cancel callback. Called by
     * [AgentForegroundService] when the user taps the notification's
     * Stop action. Each VM's cancelStream() is responsible for ending
     * its streamJob + flipping canResume true (T13) so the user can
     * tap Resume later.
     *
     * Snapshot the map before iterating — the cancellers themselves
     * call back into [setInactive] which mutates [streamCancellers],
     * so iterating the live map would ConcurrentModificationException.
     */
    fun cancelAllActiveStreams() {
        val snapshot = synchronized(streamCancellers) { streamCancellers.values.toList() }
        Log.d(TAG, "cancelAllActiveStreams: dispatching to ${snapshot.size} session(s)")
        for (cancel in snapshot) {
            try {
                cancel()
            } catch (e: Exception) {
                Log.w(TAG, "stream canceller threw: ${e.message}")
            }
        }
    }

    /**
     * Returns whether a specific session is currently active.
     */
    fun isActive(sessionId: String): Boolean = sessionId in _activeSessions.value

    /**
     * Updates the current tool status displayed in the notification.
     * Legacy single-argument variant — leaves [currentToolName] and
     * [isToolRunning] untouched. New callers should prefer the overload
     * below so the notification can render a tool-specific icon and
     * progress indicator.
     */
    fun updateToolStatus(status: String) {
        _currentToolStatus.value = status
        if (_activeSessions.value.isNotEmpty()) {
            updateService()
        }
    }

    /**
     * T-bg-overlay phase 1: rich tool-status update. Pass [toolName] =
     * null + [isRunning] = false to clear (e.g. tool finished, between
     * turns). The FGS notification rebuilds when any of name / status /
     * running flag changes.
     */
    fun updateToolStatus(status: String, toolName: String?, isRunning: Boolean) {
        updateToolStatus(status, toolName, isRunning, toolTitle = null)
    }

    /**
     * [T-android-overlay-tool-title] Rich update that also carries the
     * model-supplied `tool_title`. When [toolTitle] is non-blank the
     * overlay label uses it directly (e.g. "Open Baidu home page") instead of the
     * static per-tool label ("Browser"). Pass null/blank to fall back to
     * the per-tool label (existing behavior).
     */
    fun updateToolStatus(status: String, toolName: String?, isRunning: Boolean, toolTitle: String?) {
        _currentToolStatus.value = status
        _currentToolName.value = toolName
        _currentToolTitle.value = toolTitle?.takeIf { it.isNotBlank() }
        _isToolRunning.value = isRunning
        // [T-overlay-glyph-typed-outcome] Starting a new tool clears the
        // previous outcome so the overlay glyph (which reads
        // lastToolOutcome) does not leak the prior result into the new
        // tool's "running" state.
        if (isRunning) _lastToolOutcome.value = ToolOutcome.Unknown
        if (_activeSessions.value.isNotEmpty()) {
            updateService()
        }
    }

    /**
     * T-bg-overlay phase 1: clear tool-running state without touching
     * [currentToolStatus]. Called when a tool block flips to
     * SUCCESS/FAILED/TIMEOUT/CANCELLED so the notification stops
     * showing an active progress bar.
     */
    fun clearToolRunning(outcome: ToolOutcome = ToolOutcome.Unknown) {
        if (!_isToolRunning.value && _currentToolName.value == null) return
        // Snapshot identity + status before wiping the live values so the
        // overlay's post-completion render has something concrete to show.
        // Only retain a status string when it's a real tool-status line
        // (not the "Idle" placeholder).
        _lastToolName.value = _currentToolName.value
        _lastToolTitle.value = _currentToolTitle.value
        _lastToolStatus.value = _currentToolStatus.value
            ?.takeIf { it.isNotBlank() && !it.equals("Idle", ignoreCase = true) }
        _currentToolName.value = null
        _currentToolTitle.value = null
        _isToolRunning.value = false
        _lastToolOutcome.value = outcome
        if (_activeSessions.value.isNotEmpty()) {
            updateService()
        }
    }

    private fun startServiceIfNeeded() {
        val context = appContext ?: run {
            Log.w(TAG, "Context not initialized, cannot start service")
            return
        }
        AgentForegroundService.startService(context)
    }

    private fun updateService() {
        // Already foreground: another startForegroundService forces a
        // startForeground within 5s and makes MIUI re-inflate the island.
        // The service collector applies status changes itself.
        if (AgentForegroundService.isRunning) return
        startServiceIfNeeded()
    }

    private fun stopService() {
        val context = appContext ?: run {
            Log.w(TAG, "Context not initialized, cannot stop service")
            return
        }
        AgentForegroundService.stopService(context)
        Log.d(TAG, "All sessions complete, service stopped")
    }
}
