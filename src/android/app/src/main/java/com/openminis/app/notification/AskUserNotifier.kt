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
import com.openminis.app.service.AskUserGate
import com.openminis.app.tools.AskUserQuestion

/**
 * [T-question-bg-notify] Single source of truth for the question-notification
 * broadcast action and its intent extras.
 *
 * [AskUserNotifier] builds the PendingIntents and
 * [com.openminis.app.service.AgentForegroundService] handles the fallback
 * delivery — the literal strings are also referenced by the intent-filter in
 * AndroidManifest.xml. Same drift-prevention shape as [ApprovalIntents].
 */
object AskUserIntents {
    const val ACTION_ANSWER = "com.openminis.app.ANSWER_QUESTION"
    const val EXTRA_REQUEST_ID = "ask_user_request_id"
    const val EXTRA_ANSWER_LABEL = "ask_user_answer_label"
}

/**
 * [T-question-bg-notify] Posts the "agent is waiting for your answer"
 * notification when an `ask_user_question` card is pending and the user is
 * not looking at that chat (SessionActivityTracker presence).
 *
 * Companion to [AskUserGate]: the VM registers the pending question, then
 * posts this while the session is absent. Tapping the row deep-links to the
 * session (`minis://session/<id>`); when the pending set is exactly one
 * single-select question with 2–3 options, each option also becomes a
 * notification action that answers directly via [AskUserBroadcastReceiver].
 *
 * ## Notification clearing contract
 *
 * Cleared on every resolution path:
 *  - **Answer tap**: [AskUserBroadcastReceiver.onReceive] cancels after
 *    forwarding to [AskUserGate].
 *  - **In-app answer / skip / tool-dispatch end**: the VM's `finally` calls
 *    [cancelQuestion].
 *  - **User returns to the chat**: the VM's presence watcher cancels — the
 *    card is on screen, the shade copy is redundant.
 *  - **Service stop/interrupt**: AgentForegroundService drains the gate and
 *    cancels via [cancelAll].
 *
 * If POST_NOTIFICATIONS is not granted this silently no-ops — the in-app
 * card still works, only the background signal is lost.
 *
 * Body text is hardcoded zh-CN, matching the [ApprovalNotifier] precedent
 * (its "需要审批/同意/拒绝" literals are also untranslated); only the
 * channel name/desc are localized resources.
 */
class AskUserNotifier(private val context: Context) {

    companion object {
        private const val TAG = "AskUserNotifier"
        const val CHANNEL_ID = "minis_question"

        // Distinct from ApprovalNotifier.NOTIF_BASE so a session can never
        // collide with an approval notification id.
        private const val NOTIF_BASE = 0x2D400

        // One pending question per session at a time (the VM's deferred is a
        // single slot), so the notification id is session-keyed.
        fun notificationId(sessionId: String): Int = NOTIF_BASE xor sessionId.hashCode()

        /** Creates the channel if missing; safe from any thread. */
        fun ensureChannel(ctx: Context) {
            val mgr = ContextCompat.getSystemService(ctx, NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.notif_question_channel_name),
                    android.app.NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = ctx.getString(R.string.notif_question_channel_desc)
                    setShowBadge(false)
                    // The question text is conversation content; keep the row
                    // on the lock screen but only with the benign stand-in.
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                }
            )
        }

        /** Cancels the question notification for [sessionId]. */
        fun cancelQuestion(ctx: Context, sessionId: String) {
            try {
                NotificationManagerCompat.from(ctx).cancel(notificationId(sessionId))
            } catch (_: Exception) { /* nothing to cancel */ }
        }

        /** Cancels every question notification for the given sessions. */
        fun cancelAll(ctx: Context, sessionIds: Collection<String>) {
            if (sessionIds.isEmpty()) return
            val nm = NotificationManagerCompat.from(ctx)
            sessionIds.forEach { sid ->
                try {
                    nm.cancel(notificationId(sid))
                } catch (_: Exception) { /* nothing to cancel */ }
            }
        }

        // Single source of truth: delegate to AskUserIntents so the service
        // fallback and the receiver can never drift (ApprovalNotifier shape).
        const val ACTION_ANSWER = AskUserIntents.ACTION_ANSWER
        const val EXTRA_REQUEST_ID = AskUserIntents.EXTRA_REQUEST_ID
        const val EXTRA_ANSWER_LABEL = AskUserIntents.EXTRA_ANSWER_LABEL
    }

    /**
     * Posts (or refreshes — the id is session-keyed) the question notification.
     *
     * @param sessionId session the question belongs to; keys the notification
     *   id and the deep link.
     * @param requestId [AskUserGate] registration id for the action buttons.
     * @param questions the pending question set.
     */
    fun notifyQuestion(sessionId: String, requestId: String, questions: List<AskUserQuestion.Question>) {
        ensureChannel(context)
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            return
        }
        val first = questions.firstOrNull() ?: return
        val launchIntent = buildLaunchIntent(sessionId)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Agent 在等你回答")
            .setContentText(questionPreview(first, questions.size))
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Minis 在等你回答")
                    .setContentText("解锁后查看问题并回答")
                    .setContentIntent(launchIntent)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .build(),
            )

        // Action buttons only for the one shape a notification can answer
        // faithfully: a single single-select question with 2–3 options. More
        // questions, multi-select, or 4+ options → tap-to-open only.
        if (questions.size == 1 && !first.multiSelect && first.options.size in 2..3) {
            first.options.forEach { option ->
                builder.addAction(
                    android.R.drawable.ic_menu_send,
                    option.label.take(20),
                    answerPending(requestId, option.label),
                )
            }
        }

        try {
            nm.notify(notificationId(sessionId), builder.build())
            android.util.Log.d(TAG, "question notified session=${sessionId.take(8)}… questions=${questions.size}")
        } catch (se: SecurityException) {
            android.util.Log.w(TAG, "question notify denied: ${se.message}")
        }
    }

    private fun questionPreview(first: AskUserQuestion.Question, total: Int): String {
        val base = first.header.ifBlank { first.question }.take(200)
        return if (total > 1) "$base（共 $total 个问题）" else base
    }

    private fun answerPending(requestId: String, label: String): PendingIntent {
        val intent = Intent(context, AskUserBroadcastReceiver::class.java).apply {
            action = AskUserIntents.ACTION_ANSWER
            putExtra(AskUserIntents.EXTRA_REQUEST_ID, requestId)
            putExtra(AskUserIntents.EXTRA_ANSWER_LABEL, label)
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context, label.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildLaunchIntent(sessionId: String): PendingIntent {
        val deepLink = Uri.parse("minis://session/$sessionId")
        val launch = Intent(Intent.ACTION_VIEW, deepLink).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context, sessionId.hashCode(), launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
