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
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SoulStore
import com.openminis.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

        /**
         * [T-android-notification-chronometer] Convert a
         * `SystemClock.elapsedRealtime()` instant into the wall-clock instant
         * `Notification.setWhen()` expects.
         *
         * Extracted as a pure function for one reason: it is the only piece of
         * real arithmetic in this change, and it fails SILENTLY and hugely if
         * dropped. elapsedRealtime counts from BOOT; setWhen is interpreted as
         * System.currentTimeMillis(). Pass the former straight through and the
         * chronometer's origin lands at the epoch plus the device's uptime — a
         * phone up five days renders "120:00:00" and climbing, on a task that
         * started two seconds ago. Nothing crashes, nothing logs; it just reads
         * as a nonsense clock.
         *
         * `buildNotification` needs a Context and a live tracker, so the
         * conversion cannot be exercised there from a JVM test. Here it can.
         *
         * @param instantElapsedMs the instant to convert, in elapsedRealtime.
         * @param nowElapsedMs elapsedRealtime "now".
         * @param nowWallMs currentTimeMillis "now". Both nows are passed in
         *   (rather than read inside) so a test can pin them and so the two
         *   conversions in one rebuild share a single consistent instant.
         */
        internal fun elapsedRealtimeToWallClock(
            instantElapsedMs: Long,
            nowElapsedMs: Long,
            nowWallMs: Long,
        ): Long = nowWallMs - (nowElapsedMs - instantElapsedMs)

        // [T-bg-overlay phase 2 fix] Separate channel + notification id
        // for the SYSTEM_ALERT_WINDOW permission nudge so it can have a
        // higher importance than the ongoing FGS status row (which is
        // intentionally LOW so it doesn't make sound on every tool).
        private const val OVERLAY_NUDGE_CHANNEL_ID = "overlay_permission_nudge"
        private const val OVERLAY_NUDGE_NOTIFICATION_ID = 9002

        /**
         * [T-android-overlay-completion-survives-stop] How long a completed
         * capsule may hold the foreground service alive waiting to be tapped.
         * Long enough that a user who glances at their phone within a few
         * minutes still sees the result; short enough that a forgotten task
         * cannot pin an FGS + wake lock indefinitely.
         */
        private const val COMPLETION_LINGER_TIMEOUT_MS = 5 * 60 * 1000L

        private const val EXTRA_SESSION_COUNT = "session_count"
        private const val EXTRA_TOOL_STATUS = "tool_status"
        // [T-android-live-update-chip] Chip timer refresh period.
        private const val CHIP_TICK_MS = 1_000L

        // [T-android-dynamic-island] Framework extras key read by
        // Notification.isRequestPromotedOngoing() (Android 16). Not exported as
        // a public SDK constant; this is the extras key the Android 16
        // framework reads ("android.requestPromotedOngoing").
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

    // [T-android-live-update-chip] See ensureChipTicker().
    private var chipTickerJob: Job? = null
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
        // [T-android-fgs-start-after-stop] GH#329 backstop.
        //
        // The tracker now refuses to dispatch a start once the stop has been
        // decided (SessionActivityTracker.serviceLock), which removes the race
        // at its source. This is the second line of defence, for a start that
        // reaches us anyway — a queued Intent the system had already accepted
        // before the stop landed, or a future caller that bypasses the tracker.
        //
        // Android's rule is absolute: an instance created by
        // startForegroundService() MUST call startForeground() within ~5s or the
        // system kills the PROCESS with
        // ForegroundServiceDidNotStartInTimeException. "There is nothing to do"
        // is not an exemption — returning or calling stopSelf() without ever
        // promoting is precisely what trips it. So satisfy the contract first,
        // then unwind.
        //
        // The notification is removed in the same breath via
        // STOP_FOREGROUND_REMOVE, so the user sees nothing: the promote/demote
        // pair completes within one main-thread message, well under the frame
        // the shade would need to draw it. This is the pattern Android's own
        // docs prescribe for "started, but no longer needed".
        if (intent != null && intent.action != ACTION_STOP && !SessionActivityTracker.shouldRunServiceNow()) {
            Log.d(TAG, "start with nothing to run — satisfying the FG contract, then stopping")
            try {
                val stub = buildNotification(0, "Idle")
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
                // Never let the backstop itself be the thing that crashes: a
                // failure here is strictly better than the process death it
                // exists to prevent.
                Log.w(TAG, "FG-contract stub failed: ${t.message}")
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
        ensureChipTicker()

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
                // [T-android-overlay-soul-identity] Editing the Soul name or
                // icon must repaint the capsule without an app restart. The
                // controller reads SoulStore.cachedMetadata.value directly when
                // it renders, so this flow is here purely to make an edit COUNT
                // as a state change and push a refresh — its value is not read
                // out of the array below.
                SoulStore.cachedMetadata,
                // [T-android-live-update-content] Thinking vs generating for
                // the capsule's streaming row.
                SessionActivityTracker.isThinking,
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
                    soulIdentity = (values[15] as SoulMetadata).let { it.name to it.icon },
                    isThinking = values[16] as Boolean,
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
        /**
         * [T-android-overlay-soul-identity] The Soul (name, icon), carried as a
         * VALUE rather than merely combined in.
         *
         * The controller reads SoulStore.cachedMetadata itself when it renders,
         * so this field is never read back out. It exists because the collector
         * is distinctUntilChanged over this data class: a Soul edit that is
         * absent from it produces an equal state and gets dropped, leaving the
         * capsule showing the old name and icon until something unrelated
         * happened to change. Holding the identity here is what makes that edge
         * survive the filter.
         */
        val soulIdentity: Pair<String, String>,
        // [T-android-live-update-content] Model is emitting reasoning, no
        // visible text yet for this turn.
        val isThinking: Boolean,
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
            hideOverlay()
            lingerJob?.cancel()
            lingerJob = null
            hasCompletionPending = false
            // [T-android-overlay-completion-survives-stop] The latch and the
            // tracker keep-alive must move together. Clearing only the local
            // field (as this branch did between b628f31aa and this change)
            // strands `overlayCompletionPending = true` with no capsule left to
            // dismiss it — nothing clears it until the app is foregrounded
            // again, so the service and its wake lock stay pinned.
            //
            // But the release cannot be unconditional either, which is what
            // b628f31aa was right to remove: `setOverlayCompletionPending(false)`
            // calls `stopService()` when nothing else justifies the service, and
            // on a background transition this branch runs WHILE a stream is
            // still going — killing the very work the user backgrounded the app
            // to let finish.
            //
            // Both hold if the release is conditioned on there being no work: a
            // dynamic-island session that is still busy keeps the service alive
            // through its own `activeSessions` entry (so the keep-alive is
            // redundant), and an idle one has nothing to protect. The capsule is
            // suppressed on this path regardless, so no UI depends on the flag.
            val stillWorking = state.hasActiveStream || state.isRunning
            if (!stillWorking) {
                SessionActivityTracker.setOverlayCompletionPending(false)
            }
            wasBusy = stillWorking
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
            // [T-android-overlay-completion-survives-stop] Tell the tracker the
            // service still has a job to do. Otherwise the very next
            // setInactive() — which is what produced this edge — finds no
            // active session and no presence (Activity already destroyed) and
            // stops the service, taking the capsule with it.
            SessionActivityTracker.setOverlayCompletionPending(true)
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
        } else if (hasPerm) {
            // [T-android-overlay-nudge-stale] Clear the nudge once the grant
            // actually lands. The notification is setAutoCancel(true), so it
            // disappears if the user taps IT — but the grant is just as often
            // given from Settings > Display over other apps, or from the
            // in-app toggle's own deep-link (BackgroundSettingsScreen sends
            // the user to the same system screen and returns them here). In
            // those paths nothing dismissed it, so a stale "permission needed"
            // notification sat in the shade contradicting a now-working
            // overlay. Also re-arm the once-per-service latch so a LATER
            // revocation can nudge again — without this, one grant/revoke
            // cycle silenced the nudge for the rest of the service's life.
            clearOverlayPermissionNudge()
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
            if (state.isForeground) {
                hasCompletionPending = false
                SessionActivityTracker.setOverlayCompletionPending(false)
            }
            lingerJob?.cancel()
            lingerJob = null
            hideOverlay()
            return
        }

        if (shouldShow) {
            // [T-android-overlay-completion-survives-stop] Only a BUSY state
            // cancels the linger timer. This cancel used to sit here
            // unconditionally, which was harmless when the linger was
            // unbounded — but the completion branch below now arms a timeout
            // job, and every subsequent emission re-enters this block, so an
            // unconditional cancel here would kill the timer on the very next
            // tick and restore the unbounded (now FGS-pinning) behaviour.
            if (isBusy) {
                lingerJob?.cancel()
                lingerJob = null
            }
            // [T-android-overlay-show-if-busy] When a tool is active the
            // tracker has live toolName/toolTitle/toolStatus; otherwise
            // (stream-only, no tool yet) those may be null/Idle, in which
            // case we fall back to the lastTool* snapshot from the most
            // recent tool of THIS turn so the capsule isn't a bare
            // spinner.
            // [T-android-overlay-stale-tool] Only fall back to the finished
            // tool's identity while the model is still WORKING on the same
            // turn — a streaming stretch between two tool calls, which is what
            // the fallback was added for.
            //
            // It used to apply unconditionally, and the capsule passes
            // isRunning=true on this branch, so a tool that had already
            // finished kept rendering as running: observed on device at
            // 10:57:55 with `toolRunning=false toolName=null` in the state and
            // the previous tool's glyph and title still on screen, then still
            // there minutes later on the launcher. Worse, at 10:58:01 the
            // bound session had moved on while the title had not, so the
            // capsule showed one task's tool above another task's id.
            //
            // Gating on isThinking/hasActiveStream keeps the no-bare-spinner
            // behaviour for the case it was written for and drops the snapshot
            // the moment the turn stops producing anything.
            val stillWorkingThisTurn = state.isRunning || state.isThinking || state.hasActiveStream
            val effectiveToolName =
                state.toolName ?: state.lastToolName?.takeIf { stillWorkingThisTurn }
            val effectiveToolTitle =
                state.toolTitle ?: state.lastToolTitle?.takeIf { stillWorkingThisTurn }
            val effectiveStatus = if (!state.toolStatus.equals("Idle", ignoreCase = true)) {
                state.toolStatus
            } else {
                state.lastToolStatus ?: state.toolStatus
            }
            // [T-android-overlay-multitask] Resolve the focused slot ONCE per
            // render and pass both halves down together, so the title and the
            // index can never describe different tasks.
            //
            // Read here rather than added to the combine() above: that call is
            // already at 17 flows (its positional `values[n]` indexing is the
            // reason this file renumbers so badly), and the slot list changes
            // only on session start/stop — both of which already push a state
            // emission through activeSessions.
            val slot = SessionActivityTracker.focusedTaskSlot()
            if (isBusy) {
                controller.show(
                    toolName = effectiveToolName,
                    statusText = effectiveStatus,
                    isRunning = true,
                    outcome = ToolOutcome.Unknown,
                    replyExcerpt = null,
                    targetSessionId = state.currentSessionId,
                    toolTitle = effectiveToolTitle,
                    // [T-android-overlay-streaming-state] isBusy is
                    // hasActiveStream || isRunning, so passing it alone told
                    // the capsule only "something is happening". Forward the
                    // tool half separately so it can distinguish an executing
                    // tool from the model merely streaming text — the latter
                    // must not show the previous tool's glyph and title, which
                    // effectiveToolName/-Title still carry by design.
                    isToolRunning = state.isRunning,
                    isThinking = state.isThinking,
                    sessionTitle = slot?.first?.title,
                    taskIndex = slot?.second ?: 0,
                    taskTotal = slot?.third ?: 0,
                )
            } else {
                // [T-android-overlay-completion-pending] Completion linger:
                // the turn ended while backgrounded. Render the controller's
                // existing completed state (outcome glyph + localized
                // completion word + reply excerpt row + visible X); it stays
                // until tap-to-open / X clears hasCompletionPending via
                // onDismissByUser, or a foreground transition does.
                // [T-android-overlay-completion-survives-stop] Cap the
                // linger. Before, an un-tapped capsule cost nothing once the
                // service had stopped for other reasons; now the pending flag
                // is itself a keep-alive reason, so an unbounded linger would
                // pin a foreground service (and its wake lock) forever on a
                // user who simply never came back. After the cap we clear the
                // latch, which drops the last keep-alive reason and lets the
                // tracker stop the service normally.
                if (lingerJob == null) {
                    lingerJob = overlayScope.launch {
                        delay(COMPLETION_LINGER_TIMEOUT_MS)
                        Log.d(TAG, "completion linger expired — releasing overlay keep-alive")
                        hasCompletionPending = false
                        lingerJob = null
                        SessionActivityTracker.setOverlayCompletionPending(false)
                        // Last: hideOverlay() also drops the finished slots, which
                        // were kept (and kept their numbers) only while the
                        // capsule was visible to show them.
                        hideOverlay()
                    }
                }
                controller.show(
                    toolName = effectiveToolName,
                    statusText = effectiveStatus,
                    isRunning = false,
                    outcome = state.lastOutcome,
                    replyExcerpt = state.lastReplyExcerpt,
                    targetSessionId = state.currentSessionId,
                    toolTitle = effectiveToolTitle,
                    // [T-android-overlay-multitask] A finished task still on
                    // screen keeps its index and its place in the total — "① of
                    // 2" must not start pointing at nothing the moment one of
                    // the two ends.
                    sessionTitle = slot?.first?.title,
                    taskIndex = slot?.second ?: 0,
                    taskTotal = slot?.third ?: 0,
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
        hideOverlay()
    }

    /**
     * [T-android-overlay-multitask] Takes the capsule down AND drops the
     * finished task slots it was holding open. Running tasks keep theirs
     * ([T-android-overlay-hide-keeps-running-slots]).
     *
     * These two must not be separable. Finished slots are kept deliberately —
     * a task the user can still see has to stay numbered — but that only holds
     * while the capsule is up. Originally only the linger-expiry path cleared
     * them, so every other route down (this branch, the foreground
     * transition, the dynamic-island swap) left them behind, and the NEXT run
     * counted them: one task on screen reporting "3 of 3" after two earlier
     * runs. Reported as "实际执行的只有一个任务，怎么展示 2 of 2".
     */
    private fun hideOverlay() {
        overlayController?.let { if (it.isShown) it.hide() }
        // [T-android-overlay-hide-keeps-running-slots] Finished slots only: a
        // hide is often a foreground/camera/island transition while tasks
        // still run, and their slots are never re-registered mid-run.
        SessionActivityTracker.dropFinishedOverlayTasks()
    }

    /**
     * [T-android-dynamic-island] Re-post the ongoing status notification with
     * the current session/status so a dynamic-island toggle flip switches the
     * notification style (promoted ProgressStyle ↔ plain) without waiting for
     * the next tool tick. Uses NotificationManager.notify on the same id — the
     * service is already in the foreground for this id, so this updates it in
     * place rather than posting a duplicate.
     */
    /**
     * [T-android-live-update-chip] Once-a-second re-post of the promoted
     * notification while a run is in flight, so the status-bar chip's
     * `shortCriticalText` carries a live elapsed timer.
     *
     * Why not the chronometer: AOSP's chip shows the chronometer when there
     * is no short text, but at least one OEM skin (ColorOS 16 "fluid cloud")
     * ignores it and falls back to the content title — producing the
     * stretched "Minis is usin…" pill in the user's recording. A short text
     * is honoured everywhere, and the only way to keep it live is to
     * re-post. One LOW-importance, only-alert-once notify per second is
     * well under the platform's per-package enqueue rate limit and matches
     * what system recorder / timer chips do. Not used on the compat branch
     * (API < 36 or island off): there the system Chronometer view already
     * ticks locally with no notify() traffic.
     */
    private fun ensureChipTicker() {
        if (chipTickerJob?.isActive == true) return
        if (!isPromotedChipActive()) return
        chipTickerJob = overlayScope.launch {
            while (true) {
                delay(CHIP_TICK_MS)
                if (!isPromotedChipActive()) break
                val finished = SessionActivityTracker.lastTaskFinishedAtMs.value != null &&
                    SessionActivityTracker.activeSessions.value.isEmpty()
                if (finished) break
                if (!SessionActivityTracker.shouldRunServiceNow()) break
                refreshOngoingNotification()
            }
        }
    }

    private fun isPromotedChipActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        val minisApp = (applicationContext as? MinisApp)?.takeIf { it.subsystemsReady() }
        val userEnabled = minisApp?.backgroundSettingsRepository?.dynamicIslandEnabled?.value == true
        return DynamicIslandSupport.isDynamicIslandActive(this, userEnabled)
    }

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

    /**
     * [T-android-overlay-nudge-stale] Drop a previously posted nudge and re-arm
     * the latch. Called from the overlay observer as soon as the grant is
     * observed, so the shade never keeps a "grant this permission" prompt for a
     * permission the user already granted.
     */
    private fun clearOverlayPermissionNudge() {
        if (!overlayNudgePosted) return
        overlayNudgePosted = false
        try {
            getSystemService(NotificationManager::class.java)
                ?.cancel(OVERLAY_NUDGE_NOTIFICATION_ID)
            Log.d(TAG, "overlay permission nudge cleared (SAW now granted)")
        } catch (e: Throwable) {
            Log.w(TAG, "overlay permission nudge cancel failed: ${e.message}")
        }
    }

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
        val timeString = chipTimerText(elapsedMs)

        // [T-android-notification-chronometer] Wall-clock instant this run
        // began, for setWhen()/setUsesChronometer().
        //
        // The conversion is NOT optional. Every anchor in this file is
        // SystemClock.elapsedRealtime() (SessionActivityTracker stamps both
        // currentRunStartedAtMs and lastTaskFinishedAtMs from it), which counts
        // from BOOT. setWhen() is interpreted as System.currentTimeMillis().
        // Feeding an elapsedRealtime value straight in would place the timer's
        // origin at the epoch plus the device's uptime — on a phone up for five
        // days the chip would read "120:00:00" and climb. So rebase: take how
        // long ago the anchor was, and subtract that from the wall clock.
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val nowWallMs = System.currentTimeMillis()
        val runStartWallMs = elapsedRealtimeToWallClock(anchorMs, nowElapsedMs, nowWallMs)
        // Completed: freeze at the finish instant so `when` reads as the moment
        // the task ended rather than continuing to tick.
        val finishedWallMs = elapsedRealtimeToWallClock(endMs, nowElapsedMs, nowWallMs)

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
        val toolTitle = SessionActivityTracker.currentToolTitle.value
        val isToolRunning = SessionActivityTracker.isToolRunning.value
        val hasActiveSessions = SessionActivityTracker.activeSessions.value.isNotEmpty()

        // [T-android-live-update-content] One phase drives icon, title and
        // text, resolved by the same pure function the unit tests cover.
        // Phases: COMPLETED (resting, checkmark, frozen time) / TOOL (tool
        // glyph + tool title) / THINKING (reasoning, no text yet) /
        // GENERATING (visible reply streaming) / IDLE (presence only).
        // Previously a finished task, a running tool and plain streaming
        // shared the generic "Minis Agent Active" title and a grey wrench,
        // and the tool title was the sentence "Minis is using Shell", which
        // OEM chips truncated to "Minis is usin…".
        val phase = resolveAgentPhase(
            isCompleted = isCompleted,
            toolName = toolName,
            isThinking = SessionActivityTracker.isThinking.value,
            hasActiveSessions = hasActiveSessions,
        )
        val smallIcon = notificationSmallIconFor(phase, toolName)
        val soulName = SoulStore.cachedMetadata.value.name.ifBlank { SoulMetadata.DEFAULT.name }
        val titleText = when (phase) {
            AgentPhase.COMPLETED -> getString(R.string.bg_service_notification_title_completed)
            AgentPhase.TOOL -> toolRowTitle(toolName!!, toolTitle)
            AgentPhase.THINKING, AgentPhase.GENERATING -> soulName
            AgentPhase.IDLE -> getString(R.string.bg_service_notification_title)
        }
        val collapsedText = when (phase) {
            // Completed is a RESTING state: the chronometer is off, so the
            // total run time has to stay in the text or it would vanish.
            AgentPhase.COMPLETED ->
                getString(R.string.bg_service_notification_text_completed, sessionLabel, timeString)
            // [T-android-notification-chronometer] No baked-in time while
            // running — the system Chronometer (compat branch) or the ticked
            // shortCriticalText (promoted branch) renders the clock.
            AgentPhase.TOOL -> getString(
                R.string.bg_service_notification_text_running,
                sessionLabel,
                humanizeToolStatus(toolStatus, toolName),
            )
            AgentPhase.THINKING -> getString(
                R.string.bg_service_notification_text_running,
                sessionLabel,
                getString(R.string.overlay_thinking),
            )
            AgentPhase.GENERATING -> getString(
                R.string.bg_service_notification_text_running,
                sessionLabel,
                getString(R.string.overlay_streaming),
            )
            AgentPhase.IDLE -> getString(R.string.bg_service_notification_text_running, sessionLabel, toolStatus)
        }

        // [T-android-live-update-chip] Short critical text — the ≤7-char
        // string the Android 16 status-bar chip shows next to the small icon.
        // Always the elapsed timer: while running it is refreshed once a
        // second by ensureChipTicker(), when completed it freezes at the run
        // duration. It used to be null while running so the chronometer
        // could own the clock; ColorOS 16 then fell back to rendering the
        // full content title in the chip, which is the stretched pill the
        // user recorded. With the timer always present the chip is
        // [tool glyph][m:ss] on every skin — the same compact shape as the
        // iOS Dynamic Island and the system screen-recorder chip.
        val shortCritical = chipTimerText(elapsedMs)

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
                smallIcon = smallIcon,
                isToolRunning = isToolRunning,
                isCompleted = isCompleted,
                runStartWallMs = runStartWallMs,
                finishedWallMs = finishedWallMs,
                contentIntent = pendingIntent,
                stopIntent = stopPendingIntent,
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(titleText)
            .setContentText(collapsedText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(collapsedText))
            .setOngoing(true)
            // [T-android-notification-chronometer] Let the system render the
            // clock. SystemUI binds a real Chronometer view for this, so it
            // ticks once a second locally — no notify() per second, which would
            // be rate-limited as a notification flood and cost wakeups.
            // Completed freezes at the finish instant (chronometer off, `when`
            // pinned there); running counts up from the run's start.
            .setShowWhen(true)
            .setWhen(if (isCompleted) finishedWallMs else runStartWallMs)
            .setUsesChronometer(!isCompleted)
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
        runStartWallMs: Long,
        finishedWallMs: Long,
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
            // [T-android-notification-chronometer] Same rule as the compat
            // branch — the promoted chip is precisely where a frozen clock was
            // most visible, since it stays on screen for the whole run.
            .setShowWhen(true)
            .setWhen(if (isCompleted) finishedWallMs else runStartWallMs)
            .setUsesChronometer(!isCompleted)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            // Explicitly NOT colorized and NOT a group summary — both would
            // disqualify the notification from promotion.
            .setColorized(false)

        // [T-android-notification-chronometer] Only when non-null: while
        // running the chronometer owns the clock, and a second stale copy of
        // the same number would contradict it.
        builder.setShortCriticalText(shortCritical)

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
        // the framework's
        // Notification.hasPromotableCharacteristics() gates on
        // isRequestPromotedOngoing(), which reads the extras boolean
        // "android.requestPromotedOngoing" — the FLAG is what the *system* sets
        // AFTER it decides to promote, not the request. So we set the extras
        // key directly (verified on device).
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
}
