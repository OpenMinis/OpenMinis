package com.openminis.app.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.service.ApprovalGate

/**
 * [T-approval-action-single-source] The ONE definition of the approval
 * broadcast/delivery actions and the request-id extra key.
 *
 * Both [ApprovalNotifier] (posts the PendingIntents) and
 * [com.openminis.app.service.AgentForegroundService] (handles the fallback
 * delivery to onStartCommand) read these — they were previously hand-copied
 * with a "MUST match verbatim" comment, which is exactly the drift this object
 * exists to prevent. The literal strings are also referenced by the
 * intent-filter in AndroidManifest.xml; any change must update that too.
 */
object ApprovalIntents {
    const val ACTION_APPROVE = "com.openminis.app.APPROVE_TOOL"
    const val ACTION_DENY = "com.openminis.app.DENY_TOOL"
    const val EXTRA_REQUEST_ID = "approval_request_id"
}

/**
 * Posts a one-shot "needs approval" notification for a sensitive tool call.
 * This is distinct from the ongoing agent-status notification — it sits at
 * the top of the shade with two action buttons (approve / deny) and
 * disappears once the user picks one or the timeout fires.
 *
 * Designed as a companion to [ApprovalGate]: call
 * `requestApproval` first to get an id, post this notification, then
 * `waitFor(id)` to block the tool dispatch until the user responds.
 *
 * If POST_NOTIFICATIONS is not granted, this silently no-ops (the tool
 * dispatch path should fall back to running the tool or returning a
 * user-error message).
 *
 * ## Notification clearing contract
 *
 * The approval notification is cleared on every resolution path:
 * - **Approve / Deny tap**: [ApprovalBroadcastReceiver.onReceive] calls the
 *   companion [cancelApproval] after forwarding the result to [ApprovalGate].
 * - **Timeout**: [ApprovalGate.waitFor] returns `false`; callers in
 *   `ChatViewModel` must call [cancelApproval] after a `false` result so the
 *   stale buttons are removed. (The [ApprovalBroadcastReceiver] fallback —
 *   see AgentForegroundService ACTION_APPROVE/DENY branch — also clears it
 *   when an intent is routed to the service instead of the receiver.)
 *
 * The clearing responsibility is intentionally on the caller side for timeout:
 * [ApprovalGate] is process-local and cannot reach the notification manager,
 * so it cannot clear the bar on its own. [ApprovalBroadcastReceiver] clears
 * it on user tap because it runs in the broadcast path with a `Context`.
 */
class ApprovalNotifier(private val context: Context) {

    companion object {
        private const val TAG = "ApprovalNotifier"
        const val CHANNEL_ID = "minis_approval"

        // Single source of truth: delegate to ApprovalIntents so the service
        // fallback and the receiver can never drift. (const = another const is
        // a compile-time constant, so existing `ApprovalNotifier.ACTION_APPROVE`
        // call sites keep working unchanged.)
        const val ACTION_APPROVE = ApprovalIntents.ACTION_APPROVE
        const val ACTION_DENY = ApprovalIntents.ACTION_DENY
        const val EXTRA_REQUEST_ID = ApprovalIntents.EXTRA_REQUEST_ID

        // Different requestCode per action so the system doesn't collapse them
        // into a single PendingIntent (which would make approve/deny indistinguishable).
        private const val REQ_APPROVE = 0x1A1
        private const val REQ_DENY    = 0x1B2
        private const val NOTIF_BASE = 0x2C300

        fun notificationId(requestId: String): Int = NOTIF_BASE xor requestId.hashCode()

        /**
         * [T-orphan-notification-sweep] Cancels every approval notification
         * still active in this package. Called at process start: the approval
         * gate is process-local, so any row still up when a fresh process
         * boots is an orphan from a dead process (see
         * [OrphanNotificationSweep]).
         */
        fun sweepOrphans(ctx: Context) {
            OrphanNotificationSweep.sweep(ctx, CHANNEL_ID)
        }

        /**
         * Creates the notification channel [CHANNEL_ID] if it hasn't been created yet.
         * Safe to call from any thread before posting the approval notification.
         */
        fun ensureChannel(ctx: Context) {
            // minSdk 26 (O): the SDK_INT < O bail-out was unreachable.
            val mgr = ContextCompat.getSystemService(ctx, NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.notif_approval_channel_name),
                    android.app.NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = ctx.getString(R.string.notif_approval_channel_desc)
                    setShowBadge(false)
                    // [T-approval-lockscreen] PRIVATE, not PUBLIC: the content
                    // text is a tool-input preview and shell command lines
                    // routinely embed tokens and private paths. The per-
                    // notification publicVersion (see notifyApproval) keeps the
                    // lock-screen row available with benign text only.
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                }
            )
        }

        /**
         * Cancels the approval notification by [NOTIF_ID].
         *
         * **Clearing responsibility**: this is the single method that removes
         * the notification bar entry on every resolution path. It is called:
         *  - from [ApprovalBroadcastReceiver.onReceive] after a user tap
         *    (the receiver reaches the notification manager directly).
         *  - from ChatViewModel when [ApprovalGate.waitFor] returns `false`
         *    (timeout path) — ApprovalGate is process-local and cannot
         *    clear the notification itself, so the caller MUST call this.
         *
         * Passing a `Context` (rather than relying on the instance holder)
         * lets the caller invoke it from the receiver without constructing a
         * full ApprovalNotifier.
         */
        fun cancelApproval(ctx: Context, requestId: String) {
            try {
                NotificationManagerCompat.from(ctx).cancel(notificationId(requestId))
            } catch (_: Exception) { /* nothing to cancel */ }
        }

        /**
         * Cancels every approval notification whose id is in [requestIds].
         *
         * Used on the service teardown paths (ACTION_STOP / ACTION_INTERRUPT):
         * [ApprovalGate.cleanupAll] denies the waiting flows but cannot reach
         * the notification manager, so the caller snapshots
         * `ApprovalGate.pendingIds()` first, cleans up, then clears the bar
         * entries here. An empty/missing collection is a no-op.
         */
        fun cancelAll(ctx: Context, requestIds: Collection<String>) {
            if (requestIds.isEmpty()) return
            val nm = NotificationManagerCompat.from(ctx)
            requestIds.forEach { id ->
                try {
                    nm.cancel(notificationId(id))
                } catch (_: Exception) { /* nothing to cancel */ }
            }
        }
    }

    /**
     * Post a persistent approval notification with approve/deny buttons.
     * @param requestId  id returned by [com.openminis.app.service.ApprovalGate.requestApproval]
     * @param toolName   human-readable tool name for the notification title
     * @param preview    short tool input preview (e.g. first 200 chars)
     */
    fun notifyApproval(requestId: String, toolName: String, preview: String) {
        ensureChannel(context)
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            return
        }
        val launchIntent = buildLaunchIntent()

        fun actionPending(approved: Boolean): PendingIntent {
            val intent = Intent(context, ApprovalBroadcastReceiver::class.java).apply {
                action = if (approved) ACTION_APPROVE else ACTION_DENY
                putExtra(EXTRA_REQUEST_ID, requestId)
                setPackage(context.packageName) // explicit package so the PendingIntent resolves reliably
            }
            val reqCode = if (approved) REQ_APPROVE else REQ_DENY
            return PendingIntent.getBroadcast(
                context, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notify_approval_title, toolName))
            .setContentText(preview.take(200).ifBlank { context.getString(R.string.notify_approval_default_text) })
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(launchIntent)
            .addAction(android.R.drawable.ic_menu_send, context.getString(R.string.notify_approval_action_approve), actionPending(true))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.notify_approval_action_deny), actionPending(false))
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // [T-approval-lockscreen] The content text is the raw tool-input
            // preview (shell command lines carry tokens and private paths).
            // Keep the row on the lock screen — approve/deny from a locked
            // phone is the point — but show only this benign stand-in there.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(context.getString(R.string.notify_approval_public_title))
                    .setContentText(context.getString(R.string.notify_approval_public_text))
                    .setContentIntent(launchIntent)
                    .addAction(android.R.drawable.ic_menu_send, context.getString(R.string.notify_approval_action_approve), actionPending(true))
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.notify_approval_action_deny), actionPending(false))
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .build(),
            )
            .build()
        try {
            nm.notify(notificationId(requestId), notification)
            android.util.Log.d(TAG, "approval notified requestId=$requestId tool=$toolName")
        } catch (se: SecurityException) {
            android.util.Log.w(TAG, "approval notify denied: ${se.message}")
        }
    }

    private fun buildLaunchIntent(): PendingIntent {
        val deepLink = Uri.parse("minis://chat")
        val launch = Intent(Intent.ACTION_VIEW, deepLink).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
