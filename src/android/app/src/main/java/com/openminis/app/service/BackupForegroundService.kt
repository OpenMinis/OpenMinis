package com.openminis.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.openminis.app.R
import com.openminis.app.backup.BackupRunController
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * [T-android-backup-fgs] Keeps a running backup alive while the app is in the
 * background: a `dataSync` foreground service with an ongoing progress
 * notification, a PARTIAL_WAKE_LOCK, and (where it still does anything) a
 * Wi-Fi lock.
 *
 * Stage 1 moved the run off the screen's `viewModelScope` onto
 * [BackupRunController]'s process scope, which fixes "leaving the screen
 * kills the backup". It does not fix "backgrounding the app kills the
 * backup" (user reports: dies on screen-off / cell handover). A backgrounded
 * process with no foreground service is fair game for the cached-app freezer
 * and Doze: coroutines stop being scheduled, sockets time out mid-upload, and
 * on a low-memory device the process is simply reclaimed. A foreground service
 * raises the process to a perceptible priority, and the wake lock keeps the CPU
 * running with the screen off.
 *
 * Lifecycle is driven by the controller, never by a screen:
 *  - [BackupRunController.start] calls [start] right after launching the run.
 *  - The service observes [BackupRunController.isRunning]; when the run ends
 *    it posts a separate, dismissible outcome notification, releases both
 *    locks and stops itself. Nothing else has to remember to stop it.
 *
 * `dataSync`, not the `mediaPlayback` type AgentForegroundService uses to
 * dodge the Android 14+ dataSync cap. A backup is exactly what dataSync means,
 * and it is finite. The cap (6 h per 24 h, cumulative across the app) is
 * still handled rather than ignored: [onTimeout] stops the service promptly,
 * because exceeding the grace period after the timeout throws
 * ForegroundServiceDidNotStopInTimeException and kills the WHOLE process, which
 * is a far worse outcome than losing the keep-alive for the tail of a very
 * long run.
 */
class BackupForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var observing = false
    private var lastPostedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLogger.info(TAG, "[BackupFGS] stop requested from notification")
            BackupRunController.stop()
            return START_NOT_STICKY
        }
        // startForegroundService() must be answered with startForeground()
        // within ~5 s or the system kills the app — do it before anything else.
        goForeground(BackupRunController.statusText.value)
        acquireLocks()
        if (!observing) {
            observing = true
            observeRun()
        }
        // NOT_STICKY: if the process dies, there is no run to resume — a
        // restarted service would show a progress notification for nothing.
        return START_NOT_STICKY
    }

    private fun observeRun() {
        scope.launch {
            combine(BackupRunController.isRunning, BackupRunController.statusText) { running, status ->
                running to status
            }.distinctUntilChanged().collect { (running, status) ->
                if (running) {
                    updateProgress(status)
                } else {
                    finish()
                }
            }
        }
    }

    private fun goForeground(status: String?) {
        ensureChannel(this)
        val n = progressNotification(status)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(PROGRESS_ID, n)
            }
        }.onFailure {
            // Can be refused (e.g. ForegroundServiceStartNotAllowedException).
            // The backup still runs on the controller's scope; it just has no
            // background protection. Stop rather than linger unpromoted.
            AppLogger.error(TAG, "[BackupFGS] startForeground refused: ${it.message}")
            stopSelf()
        }
    }

    /**
     * Refresh the progress text. Throttled: the system silently drops
     * notification updates posted faster than a few per second, and upload
     * progress arrives per percent.
     */
    private fun updateProgress(status: String?) {
        val now = System.currentTimeMillis()
        if (now - lastPostedAt < PROGRESS_THROTTLE_MS) return
        lastPostedAt = now
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(PROGRESS_ID, progressNotification(status))
        }
    }

    private fun finish() {
        postOutcome()
        releaseLocks()
        stopForegroundCompat()
        stopSelf()
    }

    /**
     * A separate, dismissible notification for the result — the progress one
     * is removed with the foreground state. A backup finishing while the user
     * is elsewhere is exactly when they need telling where the package went.
     */
    private fun postOutcome() {
        val error = BackupRunController.errorText.value
        val (title, text) = when (outcomeOf(BackupRunController.lastResult.value, error)) {
            Outcome.LOCAL_PENDING ->
                getString(R.string.backup_notif_done_title) to getString(R.string.backup_notif_local_pending)
            Outcome.DELIVERED ->
                getString(R.string.backup_notif_done_title) to getString(R.string.backup_notif_done_text)
            Outcome.ISSUES ->
                getString(R.string.backup_notif_issues_title) to (error ?: "")
            Outcome.FAILED ->
                getString(R.string.backup_notif_failed_title) to (error ?: "")
            Outcome.STOPPED ->
                getString(R.string.backup_notif_stopped_title) to getString(R.string.backup_notif_stopped_text)
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_completed)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(OUTCOME_ID, n) }
    }

    private fun progressNotification(status: String?): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, BackupForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.backup_notif_running_title))
            .setContentText(status ?: getString(R.string.backup_notif_running_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.backup_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = runCatching {
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Minis:Backup")
                    .apply {
                        setReferenceCounted(false)
                        // Safety cap: a lock must never outlive a wedged run.
                        // Longer than any backup should take, shorter than
                        // forever.
                        acquire(WAKE_LOCK_CAP_MS)
                    }
            }.onFailure { AppLogger.error(TAG, "[BackupFGS] wake lock failed: ${it.message}") }
                .getOrNull()
        }
        // Wi-Fi lock: keeps the radio from dropping to power-save mid-upload
        // with the screen off. WIFI_MODE_FULL_HIGH_PERF is deprecated and
        // documented as non-functional from API 34, and the low-latency mode
        // only applies while the app is in the foreground, so below 34 only.
        if (wifiLock == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            wifiLock = runCatching {
                @Suppress("DEPRECATION")
                (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Minis:Backup")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    /**
     * The dataSync time cap was reached (Android 15+ calls this; 14 has the
     * single-argument form). Stop NOW: the grace period after this callback is
     * a few seconds, and missing it kills the process — which would take the
     * backup with it. The run keeps going on the controller's scope, just
     * without background protection from here on.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLogger.warning(TAG, "[BackupFGS] dataSync time limit reached — releasing keep-alive")
        releaseLocks()
        stopForegroundCompat()
        stopSelf()
    }

    @Deprecated("Replaced by onTimeout(Int, Int) on API 35")
    override fun onTimeout(startId: Int) = onTimeout(startId, 0)

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        observing = false
        super.onDestroy()
    }

    /** What the finished run amounts to, for the outcome notification. */
    internal enum class Outcome { LOCAL_PENDING, DELIVERED, ISSUES, FAILED, STOPPED }

    companion object {
        private const val TAG = "BackupForegroundService"

        /**
         * Classify a finished run. Pure, so the wording rule is testable:
         * a local-only package is never announced as "delivered", since it is
         * still inside the app and the notification is the prompt to move it.
         * A run that produced a package but reported an error is ISSUES (a
         * destination failed), and no package with an error is FAILED. No
         * package and no error means the user stopped it.
         */
        internal fun outcomeOf(result: BackupRunController.RunResult?, error: String?): Outcome = when {
            result != null && result.localOnly -> Outcome.LOCAL_PENDING
            result != null && error == null -> Outcome.DELIVERED
            result != null -> Outcome.ISSUES
            error != null -> Outcome.FAILED
            else -> Outcome.STOPPED
        }
        const val CHANNEL_ID = "backup"
        private const val PROGRESS_ID = 4201
        private const val OUTCOME_ID = 4202
        private const val ACTION_STOP = "com.openminis.app.backup.STOP"
        internal const val PROGRESS_THROTTLE_MS = 1_000L
        /** 6 h — the dataSync budget; a lock beyond it would outlive the service. */
        internal const val WAKE_LOCK_CAP_MS = 6 * 60 * 60 * 1000L

        /**
         * Promote the process for the running backup. Called by
         * [BackupRunController.start] from a user action, so the
         * background-start restriction does not apply; still guarded, because
         * a refused start must never stop the backup itself.
         */
        fun start(context: Context) {
            runCatching {
                val i = Intent(context, BackupForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            }.onFailure {
                AppLogger.error(TAG, "[BackupFGS] could not start: ${it.message}")
            }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.backup_notif_channel),
                    // LOW: visible and persistent, but no sound or heads-up
                    // for every progress refresh.
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}
