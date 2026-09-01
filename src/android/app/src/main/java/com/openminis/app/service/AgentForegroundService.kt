package com.openminis.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.openminis.app.MinisApp
import com.openminis.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground service that displays a persistent notification while agent sessions
 * are actively running. Shows session count, current tool name, and elapsed time.
 */
class AgentForegroundService : Service() {

    companion object {
        /**
         * [T-STALL-DIAG] How many times onStartCommand has run in THIS process.
         * A START_STICKY revival lands in a fresh process, so a value of 1 with
         * `revival=true` proves the system re-created the service after the
         * process died — distinguishing that from an ordinary in-process
         * restart (which would show an increasing count).
         */
        private val onStartCommandCalls = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * [T-STALL-DIAG] Process-start reference so every diagnostic line can
         * report how long THIS process has been alive. Pairs with the pid to
         * tell "same dirty process the user failed to kill" apart from "clean
         * cold start" when comparing against `adb shell ps`.
         */
        private val processStartElapsedMs = android.os.SystemClock.elapsedRealtime()

        private const val TAG = "AgentForegroundService"
        private const val CHANNEL_ID = "agent_status"
        private const val CHANNEL_NAME = "Agent Status"
        private const val NOTIFICATION_ID = 9001

        // [T-bg-overlay phase 2 fix] Separate channel + notification id
        // for the SYSTEM_ALERT_WINDOW permission nudge so it can have a
        // higher importance than the ongoing FGS status row (which is
        // intentionally LOW so it doesn't make sound on every tool).
        private const val OVERLAY_NUDGE_CHANNEL_ID = "overlay_permission_nudge"
        private const val OVERLAY_NUDGE_NOTIFICATION_ID = 9002

        private const val EXTRA_SESSION_COUNT = "session_count"
        private const val EXTRA_TOOL_STATUS = "tool_status"

        // [T-android-dynamic-island] Framework extras key read by
        // Notification.isRequestPromotedOngoing() (Android 16). Not exported as
        // a public SDK constant; value verified by decompiling the on-device
        // framework.jar (const-string "android.requestPromotedOngoing").
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        private const val ACTION_STOP = "com.openminis.app.STOP_AGENT_SERVICE"

        /**
         * Starts or updates the foreground service with current status.
         */
        fun startService(context: Context, sessionCount: Int, toolStatus: String) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                putExtra(EXTRA_SESSION_COUNT, sessionCount)
                putExtra(EXTRA_TOOL_STATUS, toolStatus)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stops the foreground service.
         */
        fun stopService(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private var startTimeMs: Long = 0L
    /**
     * Partial wake lock acquired while the foreground service is alive.
     * Required because Android can put the CPU to sleep even with a
     * foreground service running — Doze can suspend non-FGS background
     * threads, and on some OEM ROMs (MIUI, EMUI, ColorOS) the CPU
     * throttles aggressively after screen-off. Without this lock, long
     * shell commands can stall mid-stream when the device sleeps.
     *
     * Held only while the service runs; released in [onDestroy] so we
     * never leak across orientation changes or process restarts.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * T-bg-overlay phase 2: floating tool-status overlay manager + its
     * collector scope. The overlay is *aspirational* — visible only when:
     *   1. User toggled `backgroundOverlayEnabled` ON, AND
     *   2. SYSTEM_ALERT_WINDOW is granted, AND
     *   3. App is currently backgrounded, AND
     *   4. A tool is in flight (isToolRunning), OR was recently in flight
     *      and we're in the 30-second linger window.
     * Falls back silently to Phase 1 notification when any precondition
     * fails. Bound to the service lifetime so onDestroy tears everything
     * down deterministically.
     */
    private var overlayController: ToolOverlayController? = null
    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lingerJob: Job? = null

    // [T-android-overlay-completion-pending] X9: linger a completed-state
    // capsule after the busy edge, but ONLY when the turn ended while the
    // app was backgrounded (and the overlay was eligible to show). Scoping
    // the flag to that exact busy→idle edge is what keeps the old
    // pre-show-if-busy regression out: lastOutcome / lastReplyExcerpt
    // persist across turns in the tracker, so any rule that consults them
    // without an edge guard re-pops the capsule on every later home-screen
    // visit. The flag clears on user dismissal (tap-to-open / X) and on
    // foreground (the user saw the reply in-app).
    private var hasCompletionPending = false
    private var wasBusy = false

    override fun onCreate() {
        super.onCreate()
        // Safe-mode bail-out. When CrashFrequencyDetector tripped in
        // MinisApp.onCreate, the Application skipped its lateinit init
        // for repositories — but a sticky FG service that was running
        // pre-crash will still be re-created by the system on the next
        // process spawn. Reading MinisApp.backgroundSettingsRepository
        // from ToolOverlayController.<init> here would throw
        // UninitializedPropertyAccessException and write a second crash
        // log, which is exactly the "detection logic recursively
        // crashing" pattern. Skip the overlay observer and let
        // onStartCommand satisfy the FG-deadline + stopSelf.
        if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            Log.w(TAG, "safe-mode ON — skipping overlay/wake-lock bring-up")
            createNotificationChannel()
            return
        }
        createNotificationChannel()
        startTimeMs = SystemClock.elapsedRealtime()
        acquireWakeLock()
        startOverlayObserver()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // [T-STALL-DIAG] Revival probe. `intent == null` means the SYSTEM
        // re-created this service under START_STICKY after the process died —
        // the suspected "dirty shell" path behind "killing the app doesn't
        // help". Log the call ordinal, whether this is a revival, and the
        // in-memory tracker state, which is what a revived process CANNOT have
        // restored (SessionActivityTracker is a plain object + StateFlow).
        //
        // Reading `activeSessions` empty on a revival is the smoking gun: the
        // service is being kept alive for streams that no longer exist.
        val call = onStartCommandCalls.incrementAndGet()
        val active = SessionActivityTracker.activeSessions.value
        println(
            "[T-STALL-DIAG] FGS onStartCommand#$call pid=${android.os.Process.myPid()} " +
                "revival=${intent == null} flags=$flags startId=$startId " +
                "activeSessions=${active.size}[${active.joinToString(",")}] " +
                "processAliveMs=${android.os.SystemClock.elapsedRealtime() - processStartElapsedMs} " +
                "slots=${SessionConcurrencyManager.diagSnapshot()}",
        )
        // Safe-mode: system restarted us under START_STICKY (intent==null)
        // after a crash. Satisfy the 5-second startForeground deadline
        // with a stub notification, then unwind. The crash share dialog
        // owns the UX from here; running a background service in this
        // state would re-trip the lateinit access that brought us down.
        if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            try {
                val stub = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Minis")
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setOngoing(false)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        stub,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, stub)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "safe-mode stub startForeground failed: ${t.message}")
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            // T50: the notification's Stop action — also cancel every
            // running agent loop. Without this, stopSelf() alone leaves
            // streamJobs running until the OS reclaims the process; the
            // user taps Stop and sees the notification go away but tools
            // keep firing in the background. SessionActivityTracker holds
            // the per-session cancel callbacks registered by each VM at
            // streamJob start.
            SessionActivityTracker.cancelAllActiveStreams()
            stopSelf()
            return START_NOT_STICKY
        }

        val sessionCount = intent?.getIntExtra(EXTRA_SESSION_COUNT, 0) ?: 0
        val toolStatus = intent?.getStringExtra(EXTRA_TOOL_STATUS) ?: "Idle"

        val notification = buildNotification(sessionCount, toolStatus)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Swipe-from-recents handler. Default Service behaviour on some OEM
     * builds is to silently end the service when the task is removed
     * even if it's a foreground service — we lose the streamJob, the
     * notification disappears, and the user thinks "Stop" was tapped.
     *
     * Re-anchor the service to its own intent and call startForeground
     * again. AOSP keeps it alive across task removal as long as at least
     * one active session is registered; if zero sessions remain (the
     * task removal raced a natural completion), [stopSelf] cleans up.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // [T-STALL-DIAG] Record the FULL keep-alive decision. The branch taken
        // here decides whether a swipe-away leaves a service behind that the
        // system will later revive with START_STICKY.
        val activeAtRemoval = SessionActivityTracker.activeSessions.value
        println(
            "[T-STALL-DIAG] FGS onTaskRemoved pid=${android.os.Process.myPid()} " +
                "activeSessions=${activeAtRemoval.size}[${activeAtRemoval.joinToString(",")}] " +
                "decision=${if (activeAtRemoval.isEmpty()) "stopSelf" else "KEEP-ALIVE"} " +
                "slots=${SessionConcurrencyManager.diagSnapshot()}",
        )
        // T166: swiping from recents kills the Activity but the FG
        // service should survive iff a stream is still running. Pure
        // presence (user was reading a chat, then swiped away) is no
        // longer a reason to keep alive — they explicitly dismissed
        // the app, so clear presence here and re-evaluate.
        SessionActivityTracker.clearPresence()
        if (SessionActivityTracker.activeSessions.value.isEmpty()) {
            Log.d(TAG, "onTaskRemoved with no active sessions, stopping self")
            stopSelf()
            return
        }
        Log.d(TAG, "onTaskRemoved with ${SessionActivityTracker.activeSessions.value.size} active session(s) — keeping service alive")
        // Re-issue the foreground notification with current state so the
        // OS sees us as a "live" foreground service after the task tear-
        // down. Without this, OEM ROMs sometimes downgrade us to a plain
        // background service and reclaim within ~60 s.
        val notification = buildNotification(
            SessionActivityTracker.activeSessions.value.size,
            SessionActivityTracker.currentToolStatus.value,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // [T-android-overlay-landscape-width-rotation-drift] A Service receives
    // raw configuration changes regardless of any manifest configChanges
    // filter. Forward orientation flips / multi-window resizes to the
    // overlay controller so the WindowManager capsule (which is not
    // recreated on rotation) re-clamps itself back into the new screen
    // bounds and picks up the new capped width.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayController?.onConfigurationChanged()
    }

    override fun onDestroy() {
        releaseWakeLock()
        try {
            overlayController?.hide()
        } catch (_: Throwable) {}
        overlayController = null
        overlayScope.cancel()
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    /**
     * T-bg-overlay phase 2: collect the (foreground, toolName, toolStatus,
     * isToolRunning, toggle) tuple and reflect it into the overlay. We
     * combine() inside the service so the collector dies cleanly with
     * onDestroy and we never leak views across service restarts.
     *
     * Linger behaviour: when [SessionActivityTracker.isToolRunning] flips
     * false (tool finished), we don't hide immediately — Phase 2 spec
     * wants 30 s of "task ended" feedback while the app stays
     * backgrounded. Foreground transitions always hide instantly so the
     * overlay doesn't draw on top of the chat itself.
     */
    private fun startOverlayObserver() {
        val app = applicationContext as? MinisApp ?: return
        overlayController = ToolOverlayController(applicationContext).apply {
            // [T-android-overlay-reply-status-34599] Tap-to-open or X
            // dismissal clears the lingered completion state so the
            // observer's AND-gate stops re-showing the capsule on
            // subsequent emissions (e.g. a stale toolStatus flip).
            // [T-android-overlay-completion-pending] Also drop the
            // completion-pending linger — the user has either opened the
            // session or explicitly dismissed. No re-emission is needed:
            // the controller hides itself on the dismissal path, and the
            // cleared flag only matters on the NEXT applyOverlayState pass.
            onDismissByUser = {
                hasCompletionPending = false
                SessionActivityTracker.dismissOverlay()
            }
        }
        val backgroundRepo = app.backgroundSettingsRepository

        overlayScope.launch {
            combine(
                app.isAppForegroundFlow,
                SessionActivityTracker.currentToolName,
                SessionActivityTracker.currentToolStatus,
                SessionActivityTracker.isToolRunning,
                backgroundRepo.backgroundOverlayEnabled,
                SessionActivityTracker.lastToolOutcome,
                SessionActivityTracker.lastReplyExcerpt,
                SessionActivityTracker.currentSessionId,
                SessionActivityTracker.currentToolTitle,
                SessionActivityTracker.cameraSuppressActive,
                SessionActivityTracker.lastToolName,
                SessionActivityTracker.lastToolTitle,
                SessionActivityTracker.lastToolStatus,
                SessionActivityTracker.activeSessions,
                // [T-android-dynamic-island] Reactive dynamic-island toggle —
                // flipping it must appear/hide the overlay live (mutual
                // exclusion) without an app restart.
                backgroundRepo.dynamicIslandEnabled,
            ) { values: Array<Any?> ->
                @Suppress("UNCHECKED_CAST")
                val activeSessions = values[13] as Set<String>
                OverlayState(
                    isForeground = values[0] as Boolean,
                    toolName = values[1] as String?,
                    toolStatus = values[2] as String,
                    isRunning = values[3] as Boolean,
                    enabled = values[4] as Boolean,
                    lastOutcome = values[5] as ToolOutcome,
                    lastReplyExcerpt = values[6] as String?,
                    currentSessionId = values[7] as String?,
                    toolTitle = values[8] as String?,
                    cameraSuppress = values[9] as Boolean,
                    lastToolName = values[10] as String?,
                    lastToolTitle = values[11] as String?,
                    lastToolStatus = values[12] as String?,
                    hasActiveStream = activeSessions.isNotEmpty(),
                    dynamicIslandEnabled = values[14] as Boolean,
                )
            }.distinctUntilChanged().collect { state -> applyOverlayState(state) }
        }
    }

    private data class OverlayState(
        val isForeground: Boolean,
        val toolName: String?,
        val toolStatus: String,
        val isRunning: Boolean,
        val enabled: Boolean,
        val lastOutcome: ToolOutcome,
        val lastReplyExcerpt: String?,
        val currentSessionId: String?,
        val toolTitle: String?,
        val cameraSuppress: Boolean,
        val lastToolName: String?,
        val lastToolTitle: String?,
        val lastToolStatus: String?,
        // T-android-overlay-show-if-busy: at least one session has an
        // active streamJob (assistant generating reply). Together with
        // isRunning (tool executing) this is the only signal the overlay
        // uses to decide whether to surface — the previous "linger after
        // completion" semantics are dropped per spec.
        val hasActiveStream: Boolean,
        // [T-android-dynamic-island] User toggle for the Android 16 Live
        // Updates surface. When this is ON *and* the device is capable, the
        // floating overlay is suppressed (see applyOverlayState) so the two
        // status UIs never render simultaneously.
        val dynamicIslandEnabled: Boolean,
    )

    private fun applyOverlayState(state: OverlayState) {
        val controller = overlayController ?: return
        val hasPerm = controller.hasOverlayPermission()

        // [T-android-dynamic-island] MUTUAL EXCLUSION (critical): when the
        // Android 16 Live Updates "dynamic island" surface is the active status
        // UI — i.e. the user enabled the toggle AND the device is capable
        // (canPostPromotedNotifications) — we must NOT also show the floating
        // overlay capsule, or the user sees two duplicate real-time status UIs
        // at once. This is a hard short-circuit that overrides even an
        // explicitly-enabled backgroundOverlayEnabled toggle: the higher-tier
        // dynamic-island wins. Re-checked reactively because
        // state.dynamicIslandEnabled comes through the combined flow and
        // capability is re-probed here, so toggling either the app switch or
        // the system Live-Updates grant hides/reveals the overlay live.
        if (DynamicIslandSupport.isDynamicIslandActive(this, state.dynamicIslandEnabled)) {
            if (controller.isShown) controller.hide()
            lingerJob?.cancel()
            lingerJob = null
            hasCompletionPending = false
            wasBusy = state.hasActiveStream || state.isRunning
            // [T-android-dynamic-island] The overlay hid reactively above; make
            // the notification switch to the promoted ProgressStyle promptly too
            // (rather than waiting for the next tool/status tick that rebuilds
            // it). Only when we're actually foregrounded as a service — a bare
            // notify() here would post a non-FGS notification. Guard on active
            // sessions since that's when the FG service is alive.
            if (SessionActivityTracker.activeSessions.value.isNotEmpty()) {
                refreshOngoingNotification()
            }
            Log.d(
                TAG,
                "applyOverlayState: dynamic-island active — overlay suppressed " +
                    "(dynamicIslandEnabled=${state.dynamicIslandEnabled})",
            )
            return
        }
        // [T-android-overlay-show-if-busy] Overlay surfaces while the
        // agent is actively working — either an assistant streamJob is
        // mid-generation OR a tool is executing.
        // [T-android-overlay-completion-pending] X9 brings back a linger,
        // but edge-scoped: when busy flips false WHILE the overlay is
        // eligible and the app is backgrounded, the capsule stays in a
        // completed/replied rendering until the user taps it (open or X).
        // A turn that ends in the foreground sets nothing, so
        // backgrounding later does NOT re-pop — that edge guard is the
        // fix for the old regression where lastOutcome / lastReplyExcerpt
        // (which persist across turns) re-popped the capsule on every
        // home-screen visit.
        val isBusy = state.hasActiveStream || state.isRunning
        if (wasBusy && !isBusy && !state.isForeground && state.enabled &&
            hasPerm && !state.cameraSuppress
        ) {
            hasCompletionPending = true
        }
        wasBusy = isBusy
        val shouldShow = state.enabled && hasPerm && !state.isForeground &&
            !state.cameraSuppress &&
            (isBusy || hasCompletionPending)
        Log.d(
            TAG,
            "applyOverlayState fg=${state.isForeground} enabled=${state.enabled} " +
                "perm=$hasPerm streaming=${state.hasActiveStream} toolRunning=${state.isRunning} " +
                "cameraSuppress=${state.cameraSuppress} completionPending=$hasCompletionPending " +
                "toolName=${state.toolName} toolTitle=${state.toolTitle} shouldShow=$shouldShow shown=${controller.isShown}",
        )
        // [T-bg-overlay phase 2 fix] Permission nudge — the user opted in
        // via the Settings toggle but Android still rejects our
        // SYSTEM_ALERT_WINDOW. Post a high-importance notification that
        // deep-links to the system overlay-permission screen.
        if (state.enabled && !hasPerm && isBusy && !state.isForeground) {
            maybePostOverlayPermissionNudge()
        }
        // Foreground / toggle-off / no-perm → hide immediately, cancel
        // any (legacy) linger timer. [T-android-overlay-foreground-hide]
        // We deliberately do NOT clear tracker state on a foreground
        // transition: the user only saw the chat, they didn't explicitly
        // dismiss the capsule. If they background the app again while a
        // tool is still running OR the post-completion linger hasn't been
        // explicitly cleared (X / tap-to-open route through
        // `onDismissByUser` → `dismissOverlay()`), the capsule should
        // reappear. Toggle-off / no-perm also leave tracker state alone
        // so a subsequent re-enable picks up where we left off.
        if (state.isForeground || !state.enabled || !hasPerm || state.cameraSuppress) {
            // [T-android-overlay-completion-pending] Foreground means the
            // user saw the reply in-app — drop the pending linger so a
            // later backgrounding doesn't re-pop a stale completion.
            // Toggle-off / no-perm / camera-suppress keep the flag, same
            // as they leave tracker state alone (see comment above): a
            // re-enable picks up where we left off.
            if (state.isForeground) hasCompletionPending = false
            lingerJob?.cancel()
            lingerJob = null
            if (controller.isShown) controller.hide()
            return
        }

        if (shouldShow) {
            lingerJob?.cancel()
            lingerJob = null
            // [T-android-overlay-show-if-busy] When a tool is active the
            // tracker has live toolName/toolTitle/toolStatus; otherwise
            // (stream-only, no tool yet) those may be null/Idle, in which
            // case we fall back to the lastTool* snapshot from the most
            // recent tool of THIS turn so the capsule isn't a bare
            // spinner.
            val effectiveToolName = state.toolName ?: state.lastToolName
            val effectiveToolTitle = state.toolTitle ?: state.lastToolTitle
            val effectiveStatus = if (!state.toolStatus.equals("Idle", ignoreCase = true)) {
                state.toolStatus
            } else {
                state.lastToolStatus ?: state.toolStatus
            }
            if (isBusy) {
                controller.show(
                    toolName = effectiveToolName,
                    statusText = effectiveStatus,
                    isRunning = true,
                    outcome = ToolOutcome.Unknown,
                    replyExcerpt = null,
                    targetSessionId = state.currentSessionId,
                    toolTitle = effectiveToolTitle,
                )
            } else {
                // [T-android-overlay-completion-pending] Completion linger:
                // the turn ended while backgrounded. Render the controller's
                // existing completed state (outcome glyph + localized
                // completion word + reply excerpt row + visible X); it stays
                // until tap-to-open / X clears hasCompletionPending via
                // onDismissByUser, or a foreground transition does.
                controller.show(
                    toolName = effectiveToolName,
                    statusText = effectiveStatus,
                    isRunning = false,
                    outcome = state.lastOutcome,
                    replyExcerpt = state.lastReplyExcerpt,
                    targetSessionId = state.currentSessionId,
                    toolTitle = effectiveToolTitle,
                )
            }
            return
        }

        // Not busy AND no completion pending from this round — drop the
        // overlay if it's up. [T-android-overlay-completion-pending] With
        // the edge-scoped linger above, this branch now only fires when
        // the user already dismissed the completion (or none was pending,
        // e.g. the turn ended in foreground), so the old "task finished →
        // proactively hide" semantics still hold for those cases.
        if (controller.isShown) controller.hide()
    }

    /**
     * [T-android-dynamic-island] Re-post the ongoing status notification with
     * the current session/status so a dynamic-island toggle flip switches the
     * notification style (promoted ProgressStyle ↔ plain) without waiting for
     * the next tool tick. Uses NotificationManager.notify on the same id — the
     * service is already in the foreground for this id, so this updates it in
     * place rather than posting a duplicate.
     */
    private fun refreshOngoingNotification() {
        try {
            val notification = buildNotification(
                SessionActivityTracker.activeSessions.value.size,
                SessionActivityTracker.currentToolStatus.value,
            )
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "refreshOngoingNotification failed: ${t.message}")
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "minis:inference",
            ).apply {
                setReferenceCounted(false)
                // No timeout — release happens deterministically in onDestroy
                // when SessionActivityTracker reports zero active sessions.
                acquire()
            }
            Log.d(TAG, "WakeLock acquired (PARTIAL_WAKE_LOCK)")
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release failed: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bg_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.bg_service_channel_description)
                setShowBadge(false)
            }
            // [T-bg-overlay phase 2 fix] Higher-importance channel for the
            // SYSTEM_ALERT_WINDOW permission nudge. IMPORTANCE_DEFAULT
            // gets a heads-up surface so the user actually sees that the
            // overlay they enabled needs one more grant.
            val nudgeChannel = NotificationChannel(
                OVERLAY_NUDGE_CHANNEL_ID,
                getString(R.string.bg_overlay_nudge_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.bg_overlay_nudge_channel_description)
                setShowBadge(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(nudgeChannel)
        }
    }

    /**
     * [T-bg-overlay phase 2 fix] Throttle for the SAW permission nudge.
     * `true` after the first emission this service lifetime so a single
     * agent loop that calls 10 tools doesn't post 10 identical
     * heads-up notifications. Reset on service destroy so a fresh
     * launch after the user has had a chance to think about it can
     * remind them again.
     */
    private var overlayNudgePosted: Boolean = false

    private fun maybePostOverlayPermissionNudge() {
        if (overlayNudgePosted) return
        overlayNudgePosted = true
        try {
            val mgrIntent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName"),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            val pi = PendingIntent.getActivity(
                this,
                2,
                mgrIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notif = NotificationCompat.Builder(this, OVERLAY_NUDGE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.bg_overlay_nudge_title))
                .setContentText(getString(R.string.bg_overlay_nudge_body))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(getString(R.string.bg_overlay_nudge_body)),
                )
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build()
            val mgr = getSystemService(NotificationManager::class.java)
            mgr?.notify(OVERLAY_NUDGE_NOTIFICATION_ID, notif)
            Log.d(TAG, "overlay permission nudge posted (SAW not granted but toggle is ON)")
        } catch (e: Throwable) {
            Log.w(TAG, "overlay permission nudge failed: ${e.message}", e)
        }
    }

    private fun buildNotification(sessionCount: Int, toolStatus: String): Notification {
        // [T-android-live-update-completed] The service outlives the task: it
        // stays alive while the user is merely *present* in a chat (see
        // SessionActivityTracker.shouldRunService), so after the agent finishes
        // the ongoing notification / Live Update chip remains on screen. Anchor
        // the elapsed time to the task's finish moment when there is one, so the
        // timer freezes at the real run duration instead of counting service
        // uptime forever — the reported bug was a completed task whose dynamic
        // island kept ticking as though it were still running.
        val finishedAtMs = SessionActivityTracker.lastTaskFinishedAtMs.value
        val isCompleted = finishedAtMs != null && SessionActivityTracker.activeSessions.value.isEmpty()
        val endMs = if (isCompleted) finishedAtMs!! else SystemClock.elapsedRealtime()
        // Anchor to the current run's start, not the service's. The service is
        // created once per presence window and outlives individual tasks, so
        // startTimeMs would report "time spent in this chat" — and a second task
        // in the same sitting would show a duration that already included the
        // first. Fall back to startTimeMs for the presence-only case (user in a
        // chat having never run anything), where no run anchor exists.
        val anchorMs = SessionActivityTracker.currentRunStartedAtMs.value ?: startTimeMs
        val elapsedMs = (endMs - anchorMs).coerceAtLeast(0L)
        val elapsedSeconds = (elapsedMs / 1000).toInt()
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        val timeString = String.format("%d:%02d", minutes, seconds)

        val mainIntent = Intent(this, Class.forName("com.openminis.app.MainActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AgentForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sessionLabel = resources.getQuantityString(
            R.plurals.bg_service_sessions, sessionCount, sessionCount,
        )

        // T-bg-overlay phase 1: enrich the ongoing notification.
        // Title:   "Minis is using <Tool>"  (or session-count summary when idle/between turns)
        // Text:    one-line "<sessionLabel> · <elapsed>" so the always-visible row stays compact
        // BigText: full status string from SessionActivityTracker.currentToolStatus when expanded
        // Progress: indeterminate while a tool is in flight (isToolRunning), hidden otherwise
        // The system Doze-friendly setOnlyAlertOnce keeps repeated rebuilds silent.
        val toolName = SessionActivityTracker.currentToolName.value
        val isToolRunning = SessionActivityTracker.isToolRunning.value

        // [T-android-live-update-completed] In the completed resting state the
        // title/status must stop describing work in progress. `toolName` is
        // already null by then (setInactive clears it), so the old code fell
        // through to the generic "Minis is running" title while the icon fell
        // through to the wrench (toolSmallIconRes' else branch) — a finished
        // task rendered exactly like a running one.
        val titleText = when {
            isCompleted -> getString(R.string.bg_service_notification_title_completed)
            toolName != null -> toolDisplayLabel(toolName)
            else -> getString(R.string.bg_service_notification_title)
        }
        val collapsedText = if (isCompleted) {
            getString(R.string.bg_service_notification_text_completed, sessionLabel, timeString)
        } else {
            getString(
                R.string.bg_service_notification_text, sessionLabel, toolStatus, timeString,
            )
        }

        // [T-android-dynamic-island] Short critical text — the ~7-char glyph
        // the system shows on the always-on / compact chip. Available since
        // Android 12 (API 31) on the native Builder, so we compute it for all
        // branches and apply it wherever the API exists. Prefer the elapsed
        // time (most glanceable); fall back to a tool hint.
        val shortCritical = timeString

        // [T-android-dynamic-island] Tier 3: Android 16 (Baklava) Live Updates.
        // When the device is capable AND the user enabled the toggle, build the
        // ongoing notification with Notification.ProgressStyle and request
        // promotion (FLAG_PROMOTED_ONGOING) so it surfaces on the status chip /
        // "dynamic island". androidx.core 1.15 has none of these APIs, so this
        // branch drops to the native Notification.Builder. Everything the
        // promoted-notification contract requires is satisfied here: ongoing,
        // a contentTitle, a supported style (ProgressStyle), NOT a group
        // summary, NOT colorized, and the channel importance is LOW (not MIN).
        // [T-android-safemode-lateinit-crash-147] `?.` protects against a null
        // Application, NOT against an uninitialized lateinit — the safe call
        // succeeds and then the GETTER throws
        // UninitializedPropertyAccessException. This service can be restarted
        // by the system with no Activity, so it can observe a MinisApp whose
        // onCreate early-returned under safe-mode. Gate on subsystemsReady()
        // first; a notification built without the dynamic-island style is a
        // cosmetic downgrade, a crash here kills the FGS mid-task.
        val minisApp = (applicationContext as? MinisApp)?.takeIf { it.subsystemsReady() }
        val dynamicIslandUserEnabled =
            minisApp?.backgroundSettingsRepository?.dynamicIslandEnabled?.value == true
        val dynamicIslandOn = DynamicIslandSupport.isDynamicIslandActive(
            this,
            dynamicIslandUserEnabled,
        )
        if (dynamicIslandOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            return buildPromotedNotification(
                titleText = titleText,
                collapsedText = collapsedText,
                shortCritical = shortCritical,
                smallIcon = smallIconRes(toolName, isCompleted),
                isToolRunning = isToolRunning,
                isCompleted = isCompleted,
                contentIntent = pendingIntent,
                stopIntent = stopPendingIntent,
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIconRes(toolName, isCompleted))
            .setContentTitle(titleText)
            .setContentText(collapsedText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(collapsedText))
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        // [T-android-live-update-completed] Same rule as the promoted branch:
        // no Stop once there is nothing left to stop.
        if (!isCompleted) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.bg_service_stop_action),
                stopPendingIntent,
            )
        }

        if (isToolRunning) {
            // Tools rarely report determinate progress (shell/browser/a11y
            // are open-ended). Always indeterminate while a tool is in
            // flight; explicitly drop progress when not, so the bar
            // disappears at idle/between-turn moments.
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    /**
     * [T-android-dynamic-island] Build the Android 16 promoted / Live Updates
     * variant of the ongoing status notification using the native
     * Notification.Builder (androidx.core 1.15 lacks these APIs). Requires
     * API >= 36 — callers gate on Build.VERSION.SDK_INT before invoking.
     *
     * Uses Notification.ProgressStyle with an indeterminate segment while a
     * tool is running (tools are open-ended: shell/browser/a11y rarely report
     * determinate progress), and requests promotion via FLAG_PROMOTED_ONGOING.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildPromotedNotification(
        titleText: String,
        collapsedText: String,
        shortCritical: String,
        smallIcon: Int,
        isToolRunning: Boolean,
        isCompleted: Boolean,
        contentIntent: PendingIntent,
        stopIntent: PendingIntent,
    ): Notification {
        // [T-android-dynamic-island] A ProgressStyle only counts as a valid
        // *promotable* style when it carries at least one progress segment with
        // positive length — an empty ProgressStyle (even an indeterminate one)
        // fails Notification.hasPromotableCharacteristics() and the notification
        // silently drops to a plain ongoing row (confirmed on-device: every
        // other precondition passed, only the ProgressStyle validity failed).
        // So the segment is NOT optional: it is what keeps the chip promoted,
        // and it is why this style survives even though we show no percentage.
        //
        // [T-android-live-update-progressbar] An agent run has exactly two
        // states the user cares about — running and done — and no meaningful
        // fraction in between (tools are open-ended; there is no Nth-of-M to
        // report). Rendered as a tracker, that produced a bar parked at 0 %
        // with a paper-plane sitting on the left for the entire run: it looked
        // like a stalled download rather than "working".
        //
        // setStyledByProgress(false) keeps the style (so promotion holds) but
        // stops it drawing as a position tracker, and clearing the tracker icon
        // removes the plane. The two states are carried by the title, the icon
        // and the elapsed timer, which is where a user actually reads them.
        val progressStyle = Notification.ProgressStyle()
            .addProgressSegment(Notification.ProgressStyle.Segment(100))
            .setStyledByProgress(false)
            .setProgressTrackerIcon(null)
            .setProgressIndeterminate(isToolRunning && !isCompleted)
            .setProgress(if (isCompleted) 100 else 0)

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(titleText)
            .setContentText(collapsedText)
            .setStyle(progressStyle)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            // Explicitly NOT colorized and NOT a group summary — both would
            // disqualify the notification from promotion.
            .setColorized(false)
            .setShortCriticalText(shortCritical)

        // [T-android-live-update-completed] "Stop" is meaningless once the task
        // has finished — there is nothing left to stop, and offering it invites
        // a tap that does nothing visible. Reported from a device screenshot
        // showing "任务已完成 ✓" above a live Stop button.
        if (!isCompleted) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.bg_service_stop_action),
                    stopIntent,
                ).build(),
            )
        }

        // [T-android-dynamic-island] Request the always-visible "dynamic island"
        // promotion. The public builder method `setRequestPromotedOngoing(true)`
        // is NOT in the android-36 SDK stubs yet (@FlaggedApi / not exported),
        // and — importantly — this is NOT the same as FLAG_PROMOTED_ONGOING:
        // decompiling the on-device framework showed
        // Notification.hasPromotableCharacteristics() gates on
        // isRequestPromotedOngoing(), which reads the extras boolean
        // "android.requestPromotedOngoing" — the FLAG is what the *system* sets
        // AFTER it decides to promote, not the request. So we set the extras
        // key directly (verified against the decompiled getBoolean call).
        builder.addExtras(android.os.Bundle().apply {
            putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        })

        val notification = builder.build()
        if (!notification.hasPromotableCharacteristics()) {
            // Not fatal — the notification still posts as a normal ongoing FGS
            // row; it just won't get the promoted chip. Dump each individual
            // promotion precondition so the failing one is diagnosable.
            val flags = notification.flags
            Log.w(
                TAG,
                "promoted notification lacks promotable characteristics — diag: " +
                    "requestPromotedOngoing=${notification.extras.getBoolean(EXTRA_REQUEST_PROMOTED_ONGOING)} " +
                    "FLAG_ONGOING_EVENT=${(flags and Notification.FLAG_ONGOING_EVENT) != 0} " +
                    "hasTitle=${!notification.extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrEmpty()} " +
                    "smallIcon=${notification.smallIcon != null} " +
                    "isGroupSummary=${(flags and Notification.FLAG_GROUP_SUMMARY) != 0} " +
                    "styleTemplate=${notification.extras.getString(Notification.EXTRA_TEMPLATE)}",
            )
        } else {
            Log.d(TAG, "promoted notification OK — hasPromotableCharacteristics=true")
        }
        return notification
    }

    /**
     * T-bg-overlay phase 1: human-readable label per tool, mirroring
     * `ChatScreen.kt:5974 toolTitleLabel` so the notification's title
     * matches what the in-app FloatingToolStatusBar shows. Falls back
     * to the raw tool name for unknowns rather than a generic string,
     * so the user still gets a hint about what's running.
     */
    private fun toolDisplayLabel(toolName: String): String = when (toolName) {
        "shell_execute" -> "Minis is using Shell"
        "file_read" -> "Minis is reading File"
        "file_write" -> "Minis is using Editor"
        "file_edit" -> "Minis is editing File"
        "browser_use" -> "Minis is using Browser"
        "read_image" -> "Minis is reading Image"
        "memory_write", "memory_get" -> "Minis is using Memory"
        "web_search" -> "Minis is using Search"
        else -> "Minis is using $toolName"
    }

    /**
     * T-bg-overlay phase 1: pick a system small-icon hint per tool kind.
     * Notification small icons must be tintable monochrome — we use
     * built-in framework drawables instead of pulling in app icon
     * resources to avoid the Android < 24 "white square" fallback for
     * vector drawables. The default (`ic_menu_manage`) preserves the
     * pre-T pixel-identical look for idle / between-turn rebuilds.
     */
    /**
     * [T-android-live-update-completed] Small icon for the ongoing notification.
     * Once the task has finished, show a checkmark instead of a tool glyph — the
     * icon is the most glanceable part of the Live Update chip, and leaving the
     * generic wrench there made a completed task read as still-running.
     */
    private fun smallIconRes(toolName: String?, isCompleted: Boolean): Int =
        if (isCompleted) R.drawable.ic_notification_completed else toolSmallIconRes(toolName)

    private fun toolSmallIconRes(toolName: String?): Int = when (toolName) {
        "shell_execute" -> android.R.drawable.ic_menu_edit
        "file_read", "read_image" -> android.R.drawable.ic_menu_view
        "file_write", "file_edit" -> android.R.drawable.ic_menu_edit
        "browser_use" -> android.R.drawable.ic_menu_compass
        "memory_write", "memory_get" -> android.R.drawable.ic_menu_save
        "web_search" -> android.R.drawable.ic_menu_search
        else -> android.R.drawable.ic_menu_manage
    }
}
