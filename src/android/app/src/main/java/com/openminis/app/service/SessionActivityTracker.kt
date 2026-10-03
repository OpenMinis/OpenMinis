package com.openminis.app.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
     * [T-android-live-update-content] True while the model is emitting
     * reasoning (ThinkingDelta chunks) and has not yet produced visible
     * text for the current turn. Lets the notification / Live Update chip
     * and the overlay say "Thinking…" instead of a generic "Generating…",
     * mirroring the iOS Dynamic Island's thinking state. Cleared by the
     * first Text chunk, by a tool start, and at run boundaries.
     */
    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

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
     * [T-android-overlay-multitask] One slot per task the capsule can page
     * through: the session's own title, when it started, and whether it has
     * finished but is still lingering on screen.
     *
     * Everything else in this object describes ONE task — `currentToolName`,
     * `currentSessionId`, `lastToolTitle` and friends are single values that
     * whichever session wrote last owns. That was correct while the overlay
     * showed one run; with several sessions live it means a second run
     * silently takes the capsule over, and the halves can disagree: observed
     * on device at 10:58:01, a capsule rendering session A's tool title while
     * bound to session B's id, so tapping it opened the wrong chat.
     *
     * This map is the source of truth for "how many tasks are there and which
     * one am I looking at". The singular fields above are kept as the FOCUSED
     * task's view of the world, so every existing reader keeps working.
     *
     * Insertion-ordered (LinkedHashMap) because the index shown to the user is
     * a position in this map. Ordering by start time and never renumbering a
     * live task matters: if finishing task ① renumbered task ② to ①, the user
     * would look away from "② of 2" and back at "① of 1" describing the same
     * work.
     */
    data class OverlayTask(
        val sessionId: String,
        val title: String,
        val startedAtMs: Long,
        /**
         * The run has ended and this slot is only still here because the
         * capsule lingers. It keeps its index and stays in the denominator
         * until the linger expires — a finished task the user can still see
         * has to be numbered, or "① of 2" would point at nothing.
         */
        val finished: Boolean = false,
    )

    private val _overlayTasks = MutableStateFlow<List<OverlayTask>>(emptyList())

    /** Ordered task slots backing the capsule's "① of N" indicator. */
    val overlayTasks: StateFlow<List<OverlayTask>> = _overlayTasks.asStateFlow()

    /**
     * Which slot the capsule is showing. Held as an id rather than an index so
     * it survives a slot leaving the list; the index is derived at render time.
     * Null means "follow the newest activity", the pre-existing behaviour.
     */
    private val _focusedTaskId = MutableStateFlow<String?>(null)
    val focusedTaskId: StateFlow<String?> = _focusedTaskId.asStateFlow()

    /** Guards read-modify-write on the task list; callers are on several threads. */
    private val taskLock = Any()

    /**
     * Registers (or refreshes) a task slot. Called from [setActive], which is
     * the one place that already knows a run is beginning.
     */
    private fun upsertTask(sessionId: String, title: String?) {
        synchronized(taskLock) {
            val now = SystemClock.elapsedRealtime()
            val existing = _overlayTasks.value
            val hit = existing.firstOrNull { it.sessionId == sessionId }
            _overlayTasks.value = if (hit == null) {
                existing + OverlayTask(sessionId, title.orEmpty(), now)
            } else {
                // A re-run of a session already on screen reuses its slot and
                // its position, but stops being finished and re-anchors its
                // clock. Appending instead would show the same chat twice.
                existing.map {
                    if (it.sessionId == sessionId) {
                        it.copy(
                            title = title?.takeIf { t -> t.isNotBlank() } ?: it.title,
                            startedAtMs = now,
                            finished = false,
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }

    /**
     * Marks a slot finished without removing it — the capsule keeps showing it
     * for the linger window, so it must keep its number.
     */
    private fun markTaskFinished(sessionId: String) {
        synchronized(taskLock) {
            _overlayTasks.value = _overlayTasks.value.map {
                if (it.sessionId == sessionId) it.copy(finished = true) else it
            }
        }
    }

    /**
     * Drops a slot for good. Called when the capsule stops showing this task —
     * its linger expired, or the user dismissed the whole overlay.
     */
    fun removeOverlayTask(sessionId: String) {
        synchronized(taskLock) {
            _overlayTasks.value = _overlayTasks.value.filterNot { it.sessionId == sessionId }
            if (_focusedTaskId.value == sessionId) _focusedTaskId.value = null
        }
    }

    /**
     * [T-android-overlay-hide-keeps-running-slots] Drops the slots the capsule
     * no longer needs once it goes down, keeping every task that is still
     * running.
     *
     * The capsule is hidden for many reasons that say nothing about the tasks:
     * the app came to the foreground, the camera suppressed it, the dynamic
     * island took over. A running task is registered only once per send
     * ([setActive]), so clearing its slot on one of those hides meant the
     * capsule came back with no title and no "n of m" while it was still
     * running, and a newly started task counted itself "1 of 1" beside two
     * live ones. Finished slots still go — keeping those is the "3 of 3" leak
     * that made every hide clear the list in the first place.
     *
     * A slot is kept only while it is both unfinished and still in
     * [activeSessions]: [setInactive] leaves the active set before it marks
     * the slot finished, and either signal alone means the task is over.
     */
    fun dropFinishedOverlayTasks() {
        synchronized(taskLock) {
            val running = _activeSessions.value
            val kept = _overlayTasks.value.filter { !it.finished && it.sessionId in running }
            _overlayTasks.value = kept
            if (kept.none { it.sessionId == _focusedTaskId.value }) _focusedTaskId.value = null
        }
    }

    /** Clears every slot — the overlay is going away entirely. */
    fun clearOverlayTasks() {
        synchronized(taskLock) {
            _overlayTasks.value = emptyList()
            _focusedTaskId.value = null
        }
    }

    /**
     * Advances the capsule to the next slot, wrapping. Backs the tap-to-cycle
     * gesture on the index.
     */
    fun cycleFocusedTask() {
        synchronized(taskLock) {
            val tasks = _overlayTasks.value
            if (tasks.size < 2) return
            // [T-android-overlay-cycle-from-shown] With no explicit focus the
            // capsule shows the NEWEST slot (focusedTaskSlot falls back to the
            // last), so the cycle must start there too — starting from the
            // first made the first tap on "3 of 3" land on "2 of 3". A focus
            // on a slot that was since removed also means "showing the last".
            val cur = _focusedTaskId.value?.takeIf { id -> tasks.any { it.sessionId == id } }
                ?: tasks.last().sessionId
            val i = tasks.indexOfFirst { it.sessionId == cur }
            _focusedTaskId.value = tasks[(i + 1) % tasks.size].sessionId
        }
    }

    /**
     * The slot the capsule should render, as a (1-based index, total) pair,
     * plus the task itself. Null when there is nothing to show.
     *
     * Focus falls back to the last slot rather than the first: with no explicit
     * choice the capsule follows the newest activity, which is what it did
     * before this indicator existed.
     */
    fun focusedTaskSlot(): Triple<OverlayTask, Int, Int>? {
        val tasks = _overlayTasks.value
        if (tasks.isEmpty()) return null
        val id = _focusedTaskId.value
        val idx = tasks.indexOfFirst { it.sessionId == id }.takeIf { it >= 0 } ?: tasks.lastIndex
        return Triple(tasks[idx], idx + 1, tasks.size)
    }

    /**
     * [T-android-overlay-hide-camera] True while the user has launched the
     * system camera (ACTION_IMAGE_CAPTURE) from inside Minis and we're
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
    /**
     * [T-android-fgs-start-after-stop] Public read of the same predicate, for
     * [com.openminis.app.service.AgentForegroundService]'s GH#329 backstop.
     *
     * Deliberately NOT taking `serviceLock`: this is called from
     * `onStartCommand` on the main thread, and a start that raced a stop is
     * exactly the case it must catch. A momentarily stale `false` costs one
     * promote/demote cycle; a stale `true` just runs the normal path, which
     * promotes anyway. Neither can violate the contract.
     */
    fun shouldRunServiceNow(): Boolean = shouldRunService()

    private fun shouldRunService(): Boolean =
        _activeSessions.value.isNotEmpty() || _presentSessions.value.isNotEmpty() ||
            _overlayCompletionPending.value

    /**
     * [T-android-overlay-completion-survives-stop] True while a finished turn
     * still owes the user a completion capsule (green check + reply excerpt).
     *
     * The overlay lives in a WindowManager view owned by
     * [AgentForegroundService], so the service must outlive the stream that
     * produced it. Without this, the ordinary end-of-turn sequence in
     * [setInactive] emptied `activeSessions` and — whenever the Activity was
     * already gone (swiped from recents, or destroyed by the OEM while
     * backgrounded) — `presentSessions` was empty too, so `shouldRunService()`
     * went false and the service was stopped in the same call. `onDestroy`
     * unconditionally hides the capsule and cancels the observer scope, so the
     * completion state the overlay was about to render died with it: the user
     * saw the capsule disappear at exactly the moment it should have said
     * "done, here's the answer".
     *
     * Set by [AgentForegroundService] when it latches a pending completion, and
     * cleared when the user taps/dismisses the capsule ([dismissOverlay]) or the
     * app comes back to the foreground. It is deliberately NOT set when the
     * overlay is ineligible (toggle off, no permission, app foregrounded), so a
     * user who never enabled the overlay keeps the old teardown timing exactly.
     */
    private val _overlayCompletionPending = MutableStateFlow(false)

    fun setOverlayCompletionPending(pending: Boolean) {
        if (_overlayCompletionPending.value == pending) return
        _overlayCompletionPending.value = pending
        Log.d(TAG, "overlayCompletionPending=$pending")
        // Releasing the last reason to live must actually stop the service —
        // the flag is set while the stream is already gone, so nothing else
        // will re-evaluate this for us.
        if (!pending && !shouldRunService()) stopService()
    }

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
        // [T-android-overlay-completion-survives-stop] The capsule is gone, so
        // the service no longer needs to outlive the finished stream.
        setOverlayCompletionPending(false)
        // [T-android-overlay-multitask] Dismissal is about the whole capsule,
        // not one slot, so every task goes — including any still running. They
        // keep running; the user just stopped being notified, which is the
        // same contract this method already had for a single task.
        clearOverlayTasks()
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
    fun setActive(sessionId: String, onStop: (() -> Unit)? = null, sessionTitle: String? = null) {
        // [T-android-overlay-multitask] Register the capsule slot first so the
        // "① of N" indicator counts this run even if it produces no tool call.
        upsertTask(sessionId, sessionTitle)
        val wasIdle = !shouldRunService()
        // [T-android-live-update-completed] Capture BEFORE mutating: a run
        // "begins" only on the empty → non-empty edge. A second concurrent
        // session joining an in-flight run must not restart the clock.
        val wasRunIdle = _activeSessions.value.isEmpty()
        _activeSessions.value = _activeSessions.value + sessionId
        if (onStop != null) {
            synchronized(streamCancellers) { streamCancellers[sessionId] = onStop }
        }
        // [T-android-overlay-reply-status-34599] Track which session is
        // driving the overlay so the tap-to-open intent lands in the
        // right chat. Clear the previous reply excerpt so the user
        // doesn't briefly see a stale reply attached to a fresh run.
        _currentSessionId.value = sessionId
        _lastReplyExcerpt.value = null
        _isThinking.value = false
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
    fun setInactive(sessionId: String) {
        val wasActive = sessionId in _activeSessions.value
        _activeSessions.value = _activeSessions.value - sessionId
        // [T-android-overlay-multitask] The run is over but the capsule may
        // still be showing this task, so the slot stays (and keeps its number)
        // until the linger drops it via removeOverlayTask.
        markTaskFinished(sessionId)
        synchronized(streamCancellers) { streamCancellers.remove(sessionId) }
        val wasError = synchronized(pendingErrorFlag) { pendingErrorFlag.remove(sessionId) }
        Log.d(TAG, "Session deactivated: $sessionId (total: ${_activeSessions.value.size})")

        if (_activeSessions.value.isEmpty()) {
            _currentToolStatus.value = "Idle"
            _currentToolName.value = null
            _currentToolTitle.value = null
            _isToolRunning.value = false
            _isThinking.value = false
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
        if (!shouldRunService()) {
            stopService()
        } else {
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
    fun setPresent(sessionId: String) {
        if (sessionId in _presentSessions.value) return
        val wasIdle = !shouldRunService()
        _presentSessions.value = _presentSessions.value + sessionId
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
    fun setAbsent(sessionId: String) {
        if (sessionId !in _presentSessions.value) return
        _presentSessions.value = _presentSessions.value - sessionId
        Log.d(TAG, "Presence cleared: $sessionId (present total: ${_presentSessions.value.size})")
        if (!shouldRunService()) {
            stopService()
        } else {
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
    fun clearPresence() {
        if (_presentSessions.value.isEmpty()) return
        _presentSessions.value = emptySet()
        Log.d(TAG, "Presence cleared (all)")
        if (!shouldRunService()) {
            stopService()
        } else {
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
        // A tool call ends the thinking stretch for status purposes even if
        // the provider interleaves more reasoning later — the tool row wins.
        if (isRunning) _isThinking.value = false
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
    /**
     * [T-android-concurrent-tools] How many tool calls are in flight right now.
     *
     * The overlay used to be a strict start/stop pair because the dispatch loop
     * ran one tool at a time. Now a turn can start several at once, and the
     * FIRST to finish would blank the overlay while the others were still
     * working — the status line would read idle mid-turn and the running glyph
     * would vanish. Counting instead means the overlay clears when the last one
     * finishes, which is what "is this turn still doing something" means to a
     * user. Kept as an AtomicInteger: the callers are on different IO threads.
     */
    private val inFlightTools = java.util.concurrent.atomic.AtomicInteger(0)

    /** [T-android-concurrent-tools] Called when a tool call starts. */
    fun toolStarted() { inFlightTools.incrementAndGet() }

    /**
     * [T-android-concurrent-tools] Called from the dispatcher's `finally` when
     * a tool call leaves, however it left. Balances [toolStarted] only — the
     * overlay's own clear stays on the result path, which is the one that knows
     * the outcome. Coerced at zero so a stray call can never wedge the counter
     * negative and suppress every future clear.
     */
    fun toolFinished() {
        if (inFlightTools.decrementAndGet() < 0) inFlightTools.set(0)
    }

    /**
     * [T-android-live-update-content] Thinking-phase edge. Providers emit
     * ThinkingDelta at token rate, so this is called very often; it only
     * touches the flow (and the FGS notification) on an actual transition.
     */
    fun setThinking(thinking: Boolean) {
        if (_isThinking.value == thinking) return
        _isThinking.value = thinking
        if (_activeSessions.value.isNotEmpty()) {
            updateService()
        }
    }

    fun clearToolRunning(outcome: ToolOutcome = ToolOutcome.Unknown) {
        // [T-android-concurrent-tools] Only the LAST of a concurrent batch
        // clears the overlay; the first of three finishing used to blank it
        // while the other two were still working. This fires from the result
        // path, BEFORE the dispatcher's finally decrements, so this call's own
        // slot is still counted — "someone else is still running" is >1.
        if (inFlightTools.get() > 1) return
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

    /**
     * [T-android-fgs-start-after-stop] Serialises every start/stop decision and
     * remembers which one won last.
     *
     * GH#329. Callers decide "should the service run?" by reading
     * `_activeSessions` and then acting on the answer — a check-then-act across
     * an unsynchronized object whose StateFlows are written from several
     * threads (stream teardown, tool callbacks, the scheduled runner). Cancel
     * makes the two steps race:
     *
     *   setInactive()                    updateToolStatus()  (in flight)
     *     _activeSessions -> {}            reads a STALE non-empty set
     *     shouldRunService() == false      guard passes
     *     stopService()                    …
     *     [service destroyed]              updateService() -> startForegroundService()
     *
     * The late start re-creates a service the user just cancelled, for a
     * session that no longer exists. Android then requires that instance to
     * call startForeground() within ~5s or it kills the PROCESS with
     * ForegroundServiceDidNotStartInTimeException — which is the reported
     * crash, and why it only reproduces when cancelling (the only moment the
     * two paths overlap).
     *
     * The lock makes decide-and-dispatch atomic, and [serviceWanted] lets a
     * start that lost the race be dropped instead of resurrecting the service.
     */
    private val serviceLock = Any()

    /** Last dispatched intent: true = running, false = stopped. */
    private var serviceWanted = false

    private fun startServiceIfNeeded() {
        val context = appContext ?: run {
            Log.w(TAG, "Context not initialized, cannot start service")
            return
        }
        synchronized(serviceLock) {
            // Re-read INSIDE the lock. A caller that decided to start while the
            // cancel path was emptying the set would otherwise start a service
            // for nothing left to run.
            if (!shouldRunService()) {
                Log.d(TAG, "start requested but nothing needs the service — skipped")
                return
            }
            serviceWanted = true
            AgentForegroundService.startService(
                context,
                sessionCountForNotification(),
                statusForNotification(),
            )
        }
    }

    private fun updateService() {
        val context = appContext ?: return
        synchronized(serviceLock) {
            // An update is only ever a notification refresh for a service that
            // should be running. Once the stop has been dispatched, "update"
            // would mean START — the exact resurrection GH#329 crashes on.
            if (!shouldRunService()) {
                Log.d(TAG, "update requested after the service was stopped — skipped")
                return
            }
            serviceWanted = true
            AgentForegroundService.startService(
                context,
                sessionCountForNotification(),
                statusForNotification(),
            )
        }
    }

    /**
     * Notification session count = streaming sessions if any, otherwise
     * presence count. The user sees "1 session — Streaming…" while a
     * turn runs, and "1 session — In session" while they're composing
     * but idle. Two-bucket display keeps the count honest without
     * double-counting a session that's both present and streaming.
     */
    private fun sessionCountForNotification(): Int =
        if (_activeSessions.value.isNotEmpty()) _activeSessions.value.size
        else _presentSessions.value.size

    /**
     * Tool status fallback: when nothing is streaming, expose "In session"
     * (or "Idle" if the user isn't even in a chat). The FG service is
     * still running because of presence, but the notification shouldn't
     * imply a tool is executing.
     *
     * T180-bg-notif: localize via context resources when the active set
     * is non-empty — the notification reads "1 task running" / "N tasks
     * running" instead of falling through to the per-tool English status.
     * Tool-specific status strings (e.g. "browser_use") still surface as
     * `_currentToolStatus.value` when set; otherwise we synthesize
     * "%d task(s) running".
     */
    private fun statusForNotification(): String {
        val ctx = appContext
        val activeCount = _activeSessions.value.size
        return when {
            activeCount > 0 -> {
                val tool = _currentToolStatus.value
                if (tool.isNotBlank() && tool != "Idle") {
                    tool
                } else if (ctx != null) {
                    if (activeCount == 1) {
                        ctx.getString(com.openminis.app.R.string.notif_one_task_running)
                    } else {
                        ctx.getString(com.openminis.app.R.string.notif_n_tasks_running, activeCount)
                    }
                } else {
                    if (activeCount == 1) "1 task running" else "$activeCount tasks running"
                }
            }
            _presentSessions.value.isNotEmpty() ->
                ctx?.getString(com.openminis.app.R.string.notif_in_session) ?: "In session"
            else -> "Idle"
        }
    }

    private fun stopService() {
        val context = appContext ?: run {
            Log.w(TAG, "Context not initialized, cannot stop service")
            return
        }
        synchronized(serviceLock) {
            serviceWanted = false
            AgentForegroundService.stopService(context)
            Log.d(TAG, "All sessions complete, service stopped")
        }
    }
}
