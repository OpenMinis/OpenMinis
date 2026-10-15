package com.openminis.app.notification

import android.app.NotificationManager
import android.content.Context
import com.openminis.app.logging.AppLogger

/**
 * [T-orphan-notification-sweep] Cancels process-bound notifications that
 * outlived the process that posted them.
 *
 * The question/approval gates ([com.openminis.app.service.AskUserGate],
 * [com.openminis.app.service.ApprovalGate]) are in-memory singletons: a fresh
 * process has no pending questions or approvals by definition. Their
 * notification rows are `setOngoing(true)` — the user cannot swipe them away —
 * so if the process dies while one is pending (OOM kill, crash, force stop),
 * the row is stuck forever: no gate left to answer it, no watcher left to
 * cancel it, and no way to dismiss it short of the system notification
 * settings. Tapping it deep-links into a session whose question no longer
 * exists.
 *
 * Sweeping at process start closes the loop: any notification on a
 * process-bound channel still visible when this process boots IS an orphan,
 * because the state it advertises cannot survive the process boundary. Runs
 * before the safe-mode early-return in `MinisApp.onCreate` — a crash-loop
 * launch is precisely the "process died" case, and the sweep needs nothing
 * but NotificationManager (no DB, no repositories), so it is safe there.
 *
 * Scope is channel-based, not id-based: notification ids are keyed by
 * session/request hashes that a fresh process cannot enumerate.
 * [NotificationManager.getActiveNotifications] is per-package, so this can
 * never touch another app's rows; channels not listed here (foreground
 * service banner, scheduled-task completion, …) are left alone.
 */
object OrphanNotificationSweep {

    /** Minimal projection of a StatusBarNotification for the pure core. */
    data class ActiveRow(val id: Int, val channelId: String?)

    /**
     * Pure decision core: which active notification ids belong to the given
     * process-bound channels. Null-channel rows (defensive: a malformed
     * notification) are never swept.
     */
    fun idsToSweep(active: List<ActiveRow>, channels: Set<String>): List<Int> =
        active.filter { it.channelId != null && it.channelId in channels }.map { it.id }

    /**
     * Cancels every active notification posted by this package whose channel
     * is in [channels]. Idempotent; safe to call on every process start.
     */
    fun sweep(context: Context, vararg channels: String) {
        if (channels.isEmpty()) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val active = try {
            mgr.activeNotifications ?: return
        } catch (_: Exception) {
            return
        }
        val rows = active.map { ActiveRow(it.id, it.notification?.channelId) }
        val toSweep = idsToSweep(rows, channels.toHashSet())
        for (id in toSweep) {
            try {
                mgr.cancel(id)
            } catch (_: Exception) { /* already gone */ }
        }
        if (toSweep.isNotEmpty()) {
            AppLogger.info(TAG, "swept ${toSweep.size} orphaned gate notification(s) on process start")
        }
    }

    private const val TAG = "OrphanNotificationSweep"
}
