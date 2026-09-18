package com.openminis.app.notification

import android.app.NotificationChannel
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
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * T180-bg-notif: posts task-completion notifications when an agent
 * session finishes while the app is backgrounded. Mirrors iOS
 * `BackgroundKeepAliveManager.postBackgroundTaskNotification` (L274).
 *
 * Trigger contract: this class is hooked into [com.openminis.app.service.SessionActivityTracker]
 * so a session transitioning from active → inactive (i.e. its agent
 * loop completed) deterministically reaches `notifyTaskCompleted`. The
 * tracker itself is the single source of truth for "is this session
 * still streaming" — using its callback avoids invading
 * `ChatViewModel`, whose 4 stream-finally blocks would all need the
 * same hook.
 *
 * Behaviour rules:
 * - Skip silently if the user has disabled Task Notifications
 *   ([BackgroundSettingsRepository.taskNotificationsEnabled] = false).
 * - Skip silently if the app is currently in foreground — the user is
 *   already looking at the chat, no need to interrupt.
 * - Tap on the notification deep-links into the originating chat via
 *   `minis://session/<sessionId>` (existing
 *   `DeepLinkHandler.OpenSession` path).
 *
 * On Android the absence of `responseSummary` from the spec is
 * intentional: extracting plain text from a `parts_json` blob is
 * fragile; the notification's deep-link opens the chat where the
 * full response is rendered, matching the user's likely intent.
 */
class BackgroundTaskNotifier(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val backgroundSettings: BackgroundSettingsRepository,
    private val isAppForeground: () -> Boolean,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ensureChannel()
    }

    /**
     * Called by [com.openminis.app.service.SessionActivityTracker] when a
     * session finishes (active → inactive transition). Looks up the
     * session title via [chatRepository] and posts the notification, off
     * the main thread. No-ops silently if the user has disabled
     * notifications or the app is in foreground.
     */
    fun notifyTaskCompleted(sessionId: String, isError: Boolean = false) {
        if (!backgroundSettings.taskNotificationsEnabled.value) return
        if (isAppForeground()) return

        scope.launch {
            try {
                val session = chatRepository.getSession(sessionId)
                val rawTitle = session?.title?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.notif_task_completed_default_title)
                val title = if (isError) "❌ $rawTitle" else rawTitle
                val excerpt = com.openminis.app.service.SessionActivityTracker
                    .lastReplyExcerpt.value
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val body = when {
                    isError -> context.getString(R.string.notif_task_failed_body)
                    excerpt != null -> excerpt
                    else -> context.getString(R.string.notif_task_completed_body)
                }
                postNotification(sessionId, title, body)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "notifyTaskCompleted failed: ${t.message}")
            }
        }
    }

    private fun postNotification(sessionId: String, title: String, body: String) {
        // Pre-Tiramisu: we don't need the runtime permission, just post.
        // Tiramisu+: NotificationManagerCompat.areNotificationsEnabled
        // is the right gate — POST_NOTIFICATIONS is requested at toggle-on
        // time in Settings (separate flow), and if it was denied we silently
        // skip rather than crash.
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            return
        }

        val deepLink = Uri.parse("minis://session/$sessionId")
        val launchIntent = Intent(Intent.ACTION_VIEW, deepLink).apply {
            // FLAG_ACTIVITY_NEW_TASK because we're posting from a
            // background scope without an Activity context.
            // FLAG_ACTIVITY_CLEAR_TOP so MainActivity (singleTask) reuses
            // the existing instance and routes the deep-link via
            // onNewIntent rather than spawning a duplicate.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setTicker(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        try {
            nm.notify(sessionId.hashCode(), notification)
        } catch (se: SecurityException) {
            // POST_NOTIFICATIONS not granted — silently no-op rather
            // than crashing the agent-completion path.
            AppLogger.info(TAG, "notify denied (POST_NOTIFICATIONS not granted)")
        }
    }

    /**
     * T298: cancel every notification posted on the
     * [CHANNEL_ID] channel. Called from MinisApp's foreground transition
     * (Activity start count 0 → 1) so the user never finds a stale "task
     * completed" entry waiting in the tray when they open the app — they
     * just saw the result, the notification has served its purpose.
     *
     * Implementation: walk [NotificationManager.activeNotifications] (API
     * 23+, lower bound is API 26 for our app) and cancel any whose
     * channelId matches ours. We can't filter by channel directly because
     * each notification id is the session hash — there's no single id to
     * cancel — and `cancelAll()` would also nuke the FG service banner.
     */
    fun cancelAllCompletedNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        try {
            val active = nm.activeNotifications ?: return
            for (sb in active) {
                if (sb.notification?.channelId == CHANNEL_ID) {
                    nm.cancel(sb.tag, sb.id)
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "cancelAllCompletedNotifications failed: ${t.message}")
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_task_completed_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_task_completed_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "TaskNotifier"
        const val CHANNEL_ID = "minis_task_completed"
    }
}
