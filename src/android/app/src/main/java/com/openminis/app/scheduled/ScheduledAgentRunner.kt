package com.openminis.app.scheduled

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.openminis.app.MinisApp
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.AgentForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-android-scheduled-tasks-design] Headless agent launch for scheduled
 * tasks. Mirrors the shape of iOS SendPromptIntent.perform():
 *
 *  - resolve a session id (NEW_SESSION → create row; APPEND_TO → reuse)
 *  - run the prompt through the existing agent loop (via [HeadlessChatRunner])
 *  - post a "completed" notification with a deep-link back to the session
 *  - hand the result preview back to [ScheduledTaskManager] for the row
 *
 * Concurrency: serialised per-session by [HeadlessChatRunner]'s VM cache —
 * two scheduled prompts hitting the same session id are queued via
 * ChatViewModel.enqueuePrompt.
 */
object ScheduledAgentRunner {

    private const val TAG = "ScheduledAgentRunner"
    private const val RUN_TIMEOUT_MS = 10 * 60 * 1000L  // 10 min ceiling

    /**
     * App-scoped scope for fire-and-forget completion work when a caller asks
     * NOT to wait (the "Run now" button). Outlives the editor screen so the
     * agent loop + completion notification finish even after the user leaves.
     */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fire a scheduled task.
     *
     * @param waitForCompletion when true, suspend until the agent loop
     *   finishes, then mark-fired + post the completion notification before
     *   returning. When false, return as soon as the session is resolved and
     *   the prompt is dispatched — the agent keeps running in the background
     *   and the completion (mark-fired + notification) is finished off an
     *   app-scoped coroutine. Mirrors iOS SendPromptIntent's waitForResult
     *   flag.
     *
     *   [GH#197] NEVER pass true from a BroadcastReceiver. Waiting here can
     *   take up to [RUN_TIMEOUT_MS] (10 min) and the wait lands on the main
     *   thread (HeadlessChatRunner.prompt/retry are
     *   `withContext(Dispatchers.Main)`), so a receiver that waits blows its
     *   ~10s broadcast budget and gets the whole process ANR-killed, along
     *   with every PRoot sandbox child. The alarm path passing the default
     *   `true` was exactly that bug. Waiting is only safe off a broadcast —
     *   e.g. the minis-scheduled CLI, which runs in its own offload thread.
     * @return the session id once the action has been DISPATCHED (resolved +
     *   prompt sent), or null when the runner couldn't even start (no provider,
     *   target chat gone, MinisApp not initialized).
     */
    suspend fun run(
        context: Context,
        task: ScheduledTask,
        waitForCompletion: Boolean = true,
    ): String? {
        // [T-android-scheduled-lateinit-crash-156] `as? MinisApp` only rules out
        // a null / wrong-type Application — it does NOT mean the Application is
        // INITIALIZED, which is what the old comment here claimed. Every
        // `app.chatRepository` read below goes through a lateinit getter that
        // throws UninitializedPropertyAccessException when onCreate
        // early-returned (safe-mode) or aborted partway.
        //
        // GH#156: an alarm fires while the process is in that state and the app
        // crashes. A scheduled task is the worst case for this because the alarm
        // can START the process — Android creates the Application, onCreate
        // early-returns under safe-mode, and the receiver runs against a
        // permanently half-built app with no Activity anywhere in the picture
        // (so MainActivity's guard never gets a say).
        //
        // Skipping the run is the right degradation: the task stays scheduled
        // and its next occurrence was already armed by the receiver before this
        // call, so a skipped fire self-heals on the following launch.
        val app = context.applicationContext as? MinisApp ?: run {
            AppLogger.error(TAG, "Application is not MinisApp — skipping task ${task.id}")
            return null
        }
        if (!app.subsystemsReady()) {
            AppLogger.error(
                TAG,
                "MinisApp subsystems not initialized (safe-mode or failed init) — skipping task ${task.id}",
            )
            return null
        }

        // Kick the FGS so the agent loop survives Doze / screen-off. The
        // existing service is idempotent and reused by chat UI; we pass a
        // generic status string so it shows up in the ongoing notification.
        AgentForegroundService.startService(
            context = app,
            sessionCount = 1,
            toolStatus = "Scheduled: ${task.label.ifBlank { "task" }}",
        )

        val sessionId = withContext(Dispatchers.IO) {
            resolveSessionId(app, task)
        } ?: return null

        AppLogger.info(
            TAG,
            "running task=${task.id} label=\"${task.label}\" " +
                "mode=${task.targetMode.encode()} session=$sessionId wait=$waitForCompletion",
        )

        if (waitForCompletion) {
            val result = dispatch(app, task, sessionId, wait = true)
            val preview = (result.responseText ?: "").take(200).ifBlank { "(no response)" }
            val ok = result.status != "Error" && result.status != "Timeout"
            ScheduledTaskManager(app).markFired(task.id, sessionId, preview, ok = ok)
            postCompletionNotification(app, task, sessionId, preview)
            return sessionId
        }

        // Fire-and-forget: the session is resolved and the FGS is up, so the
        // task HAS started. Run the actual dispatch + completion entirely off
        // the app scope (wait=true so we still mark-fired + notify when it
        // finishes), and return the session id immediately so the UI can show
        // "task started" without blocking on the agent loop. Leaving the editor
        // can't cancel it because bgScope outlives the screen.
        bgScope.launch {
            val result = dispatch(app, task, sessionId, wait = true)
            val preview = (result.responseText ?: "").take(200).ifBlank { "(no response)" }
            val ok = result.status != "Error" && result.status != "Timeout"
            ScheduledTaskManager(app).markFired(task.id, sessionId, preview, ok = ok)
            postCompletionNotification(app, task, sessionId, preview)
        }
        return sessionId
    }

    /**
     * [T-android-scheduled-tasks-full] Branch on target mode, mirroring the iOS
     * App Intent set:
     *   NewSession / AppendToSession → HeadlessChatRunner.prompt (the prompt is
     *     sent as a fresh user turn; for append it lands in the existing
     *     session, for new it's the first turn of the freshly-created one).
     *   RerunMessage → HeadlessChatRunner.retry from the chosen message id (the
     *     message is replayed; task.prompt is ignored, matching iOS
     *     RetryRunIntent which has no prompt param).
     */
    private suspend fun dispatch(
        app: MinisApp,
        task: ScheduledTask,
        sessionId: String,
        wait: Boolean,
    ): HeadlessChatRunner.PromptResult = runCatching {
        val mode = task.targetMode
        if (mode is ScheduledTargetMode.RerunMessage) {
            HeadlessChatRunner.retry(
                context = app,
                sessionId = sessionId,
                messageId = mode.messageId,
                wait = wait,
                timeoutMs = RUN_TIMEOUT_MS,
            )
        } else {
            HeadlessChatRunner.prompt(
                context = app,
                sessionId = sessionId,
                text = task.prompt,
                attachments = emptyList(),
                thinkingLevel = null,
                wait = wait,
                timeoutMs = RUN_TIMEOUT_MS,
            )
        }
    }.getOrElse { t ->
        AppLogger.error(TAG, "task ${task.id} dispatch failed: ${t.message}")
        HeadlessChatRunner.PromptResult(
            status = "Error",
            responseText = "Error: ${t.message}",
            timedOut = false,
        )
    }

    private suspend fun resolveSessionId(app: MinisApp, task: ScheduledTask): String? {
        return when (val mode = task.targetMode) {
            is ScheduledTargetMode.AppendToSession -> {
                // Follow-up: the target session must still exist. If the user
                // deleted it, abort rather than silently spawning a new chat.
                if (app.chatRepository.getSession(mode.sessionId) == null) {
                    AppLogger.warning(TAG, "task ${task.id}: follow-up session ${mode.sessionId} gone — abort")
                    null
                } else {
                    mode.sessionId
                }
            }
            is ScheduledTargetMode.RerunMessage -> {
                if (app.chatRepository.getSession(mode.sessionId) == null) {
                    AppLogger.warning(TAG, "task ${task.id}: re-run session ${mode.sessionId} gone — abort")
                    null
                } else {
                    mode.sessionId
                }
            }
            ScheduledTargetMode.NewSession -> {
                // Resolution priority (mirrors the UI's normal-chat boot):
                //   1. task.modelBinding (user picked a group or specific entry
                //      on the task editor) → write it through verbatim so the
                //      new session lights up the same group/entry chip.
                //   2. legacy task.modelId (pre-binding, kept for backcompat) →
                //      pin to that entry, no binding.
                //   3. default primary group → write a group binding so the
                //      session boots through the user's default group.
                //   4. first visible entry → last-resort fallback.
                val explicitBinding = task.modelBinding
                val pinnedModelId = task.modelId
                val defaultGroupId =
                    if (explicitBinding == null && pinnedModelId == null) {
                        app.providerRepository.defaultPrimaryGroupId
                    } else null

                // Derive the seed modelId for the session row (must be valid
                // even before restoreFromBinding runs).
                val seedModelId: String = pinnedModelId
                    ?: run {
                        // Try to peek a member from whichever binding we'll
                        // write — explicit takes priority, then default group.
                        val groupIdForSeed: String? = explicitBinding
                            ?.let { parseGroupIdFromBinding(it) }
                            ?: defaultGroupId
                        val entryIdForSeed: String? = explicitBinding
                            ?.let { parseEntryIdFromBinding(it) }

                        // Entry binding → use that entry's model id.
                        entryIdForSeed
                            ?.let { eid -> app.providerRepository.config.value.modelEntries.firstOrNull { it.id == eid }?.model?.id }
                            ?: groupIdForSeed
                                ?.let { gid -> app.providerRepository.group(gid) }
                                // [T-android-group-resolve-skip-uncredentialed]
                                // Credential-aware filter — a scheduled run
                                // seeded with an uncredentialed member would
                                // fail unattended, with no user around to see
                                // the auth error.
                                ?.let { g -> app.providerRepository.availableMemberEntries(g).firstOrNull()?.model?.id }
                    }
                    ?: app.providerRepository.allVisibleEntries().firstOrNull()?.baseModel?.id
                    ?: run {
                        AppLogger.warning(TAG, "task ${task.id}: no provider — abort")
                        return null
                    }
                val title = task.label.ifBlank { "Scheduled task" }
                val memoryOn = com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(app)
                val session = app.chatRepository.createSession(
                    modelId = seedModelId,
                    title = title,
                    memoryEnabled = memoryOn,
                )
                app.chatRepository.dao.updateSource(session.id, "scheduled")

                // Write a model_binding when the task carried one OR when we
                // fell back to defaultPrimaryGroupId. Pinned modelId (no
                // binding) leaves modelBinding null so the chat layer treats
                // it as a hard-pinned entry, matching what the user picked.
                val bindingToWrite: String? = explicitBinding
                    ?: defaultGroupId?.let { """{"type":"group","groupId":"$it"}""" }
                if (bindingToWrite != null) {
                    app.chatRepository.updateSessionBinding(session.id, bindingToWrite, seedModelId)
                }
                session.id
            }
        }
    }

    // Mirrors ChatViewModel.restoreFromBinding's JSON shape. Robust against
    // malformed JSON — both parsers return null and the caller fall-backs.
    private fun parseGroupIdFromBinding(json: String): String? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optString("type") == "group") {
            o.optString("groupId").takeIf { it.isNotEmpty() }
        } else null
    }.getOrNull()

    private fun parseEntryIdFromBinding(json: String): String? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optString("type") == "entry") {
            o.optString("entryId").takeIf { it.isNotEmpty() }
        } else null
    }.getOrNull()

    private fun postCompletionNotification(
        context: Context,
        task: ScheduledTask,
        sessionId: String,
        preview: String,
    ) {
        val deepLink = Uri.parse("minis://session/$sessionId")
        val openIntent = Intent(Intent.ACTION_VIEW, deepLink).apply {
            setPackage(context.packageName)
        }
        val notificationId = task.id.hashCode() and 0x7FFFFFFF
        val contentPi = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "Minis: ${task.label.ifBlank { "Scheduled task" }}"
        val notification = NotificationCompat.Builder(context, ScheduledTaskManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.notify(notificationId, notification)
        } catch (e: SecurityException) {
            AppLogger.warning(TAG, "POST_NOTIFICATIONS denied — completion notice suppressed")
        }
    }
}
