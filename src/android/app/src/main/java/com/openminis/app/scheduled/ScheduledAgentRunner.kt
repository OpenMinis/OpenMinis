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
     * @param scheduledFire true for a fire of the task's own schedule (its
     *   alarm, or its on-completion trigger); false for "Run now". Only a
     *   scheduled fire can finish the schedule and release the tasks waiting
     *   on it ([ScheduledCompletionTriggers.onScheduledRunFinished]).
     */
    suspend fun run(
        context: Context,
        task: ScheduledTask,
        waitForCompletion: Boolean = true,
        scheduledFire: Boolean = false,
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

        // [T-scheduled-task-detail] One timestamp for this fire, written into
        // its envelope and its run record so the two can be matched.
        val firedAt = System.currentTimeMillis()
        val sessionId = withContext(Dispatchers.IO) {
            resolveSessionId(app, task)
        } ?: return null

        AppLogger.info(
            TAG,
            "running task=${task.id} label=\"${task.label}\" " +
                "mode=${task.targetMode.encode()} session=$sessionId wait=$waitForCompletion",
        )

        if (waitForCompletion) {
            val result = dispatch(app, task, sessionId, wait = true, firedAt = firedAt)
            val preview = (result.responseText ?: "").take(200).ifBlank { "(no response)" }
            val ok = result.status != "Error" && result.status != "Timeout" && result.status != "Rejected"
            ScheduledTaskManager(app).markFired(task.id, sessionId, preview, ok = ok, firedAt = firedAt)
            postCompletionNotification(app, task, sessionId, preview)
            // [T-android-scheduled-triggers] Tasks waiting on this one run
            // once its whole schedule is over.
            if (scheduledFire) ScheduledCompletionTriggers.onScheduledRunFinished(app, task.id, result.responseText)
            return sessionId
        }

        // Fire-and-forget: the session is resolved and the FGS is up, so the
        // task HAS started. Run the actual dispatch + completion entirely off
        // the app scope (wait=true so we still mark-fired + notify when it
        // finishes), and return the session id immediately so the UI can show
        // "task started" without blocking on the agent loop. Leaving the editor
        // can't cancel it because bgScope outlives the screen.
        bgScope.launch {
            val result = dispatch(app, task, sessionId, wait = true, firedAt = firedAt)
            val preview = (result.responseText ?: "").take(200).ifBlank { "(no response)" }
            val ok = result.status != "Error" && result.status != "Timeout" && result.status != "Rejected"
            ScheduledTaskManager(app).markFired(task.id, sessionId, preview, ok = ok, firedAt = firedAt)
            postCompletionNotification(app, task, sessionId, preview)
            // [T-android-scheduled-triggers] Tasks waiting on this one run
            // once its whole schedule is over.
            if (scheduledFire) ScheduledCompletionTriggers.onScheduledRunFinished(app, task.id, result.responseText)
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
        firedAt: Long,
    ): HeadlessChatRunner.PromptResult = runCatching {
        // [T-android-scheduled-task-card] Wrap the prompt so the transcript can
        // tell it apart from something the user typed. Without this the fired
        // message is an ordinary user bubble: the user sees text they never
        // wrote, with nothing saying which task produced it. The prompt follows
        // the tag verbatim, so a reader that does not know the tag still shows
        // the text rather than an empty bubble.
        //
        // RerunMessage is deliberately NOT wrapped — it replays an existing
        // message rather than sending a new one, so there is nothing to mark.
        //
        // [T-scheduled-tool-prefill] The prefilled call, if any, runs as the
        // loop's first turn instead of a model request; the envelope names it
        // so the model knows the scheduler made that call, not itself. A rerun
        // ignores it along with the prompt — it sends nothing new.
        val prefill = listOfNotNull(task.prefillToolCall?.takeIf { it.validationError() == null })
        val marked = ScheduledTaskMarker(
            taskId = task.id,
            label = task.label,
            nextFireAtMs = task.nextTriggerMs(),
            prompt = task.prompt,
            prefilledTool = prefill.firstOrNull()?.toolName,
            firedAtMs = firedAt,
        ).xml

        val mode = task.targetMode
        if (mode is ScheduledTargetMode.ChildOfCurrent) {
            val r = HeadlessChatRunner.prompt(
                context = app,
                sessionId = sessionId,
                text = marked,
                attachments = emptyList(),
                // [T-android-scheduled-task-thinking] Was hardcoded null, so a
                // task could not run with any reasoning effort. null still means
                // "inherit" inside HeadlessChatRunner.
                thinkingLevel = task.thinkingLevel,
                wait = wait,
                timeoutMs = com.openminis.app.agent.jobs.HelperRunner.MAX_MINUTES * 60_000L,
                prefill = prefill,
            )
            closeChildJob(app, sessionId, r)
            r
        } else if (mode is ScheduledTargetMode.RerunMessage) {
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
                text = marked,
                attachments = emptyList(),
                // [T-android-scheduled-task-thinking] Was hardcoded null, so a
                // task could not run with any reasoning effort. null still means
                // "inherit" inside HeadlessChatRunner.
                thinkingLevel = task.thinkingLevel,
                wait = wait,
                timeoutMs = RUN_TIMEOUT_MS,
                prefill = prefill,
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
            is ScheduledTargetMode.ChildOfCurrent -> resolveChildOfCurrent(app, task, mode.sessionId)
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
    /**
     * [T-p2-agent-series] Mirror of iOS ScheduledJobRunner's `.childOfCurrent`:
     * a hidden agent child of [parentSid] on the parent's primary tier, with
     * the helper config (turn cap, no memory, parent's browser tabs, shared
     * workspace) and a registry job whose `then = FollowUpParent` posts the
     * result back into the parent as an <agent_callback>. The prompt itself
     * is dispatched by [dispatch] on the child session id; [closeChildJob]
     * closes the job when that returns.
     */
    private suspend fun resolveChildOfCurrent(app: MinisApp, task: ScheduledTask, parentSid: String): String? {
        // [T-tools-granular-switches] A child-of-current task IS a helper: a
        // hidden session with helper config that reports back to the parent.
        // The user who turned Agents off must not keep getting them from a
        // scheduled task set up earlier. Checked at fire time, not at
        // creation, so flipping the switch takes effect on the next run.
        // Mirrors iOS ScheduledJobRunner's AgentToolSwitch.agents guard.
        if (!com.openminis.app.tools.AgentToolSwitch.AGENTS.isEnabled(app)) {
            AppLogger.warning(TAG, "task ${task.id}: agents are turned off in settings — abort child-of-current")
            return null
        }
        val parentRow = app.chatRepository.getSession(parentSid)
        if (parentRow == null) {
            AppLogger.warning(TAG, "task ${task.id}: parent session ${parentSid} gone — abort")
            return null
        }
        val resolution = com.openminis.app.agent.jobs.ModelTierResolver.resolve(
            tier = com.openminis.app.agent.jobs.HelperModelTier.PRIMARY, repo = app.providerRepository,
            parentBindingJson = parentRow.modelBinding, parentModelId = parentRow.modelId, parentActiveEntryId = null,
        ) ?: run {
            AppLogger.warning(TAG, "task ${task.id}: parent has no model — abort")
            return null
        }
        val title = task.label.ifBlank { task.prompt.take(40) }
        val child = app.chatRepository.createSession(
            modelId = resolution.seedModelId,
            title = com.openminis.app.agent.jobs.HelperRunner.childSessionTitle(app, title),
            memoryEnabled = false,
            parentSessionId = parentSid,
            parentToolUseId = null,
        )
        app.chatRepository.dao.updateSource(child.id, "scheduled")
        resolution.bindingJson?.let { app.chatRepository.updateSessionBinding(child.id, it, resolution.seedModelId) }
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        val job = registry.register(
            title = title, label = task.label.ifBlank { null },
            origin = com.openminis.app.agent.jobs.AgentJobOrigin.SCHEDULED,
            trigger = com.openminis.app.agent.jobs.AgentJobTrigger.Immediate,
            target = com.openminis.app.agent.jobs.AgentJobTarget.ChildOfCurrent(parentSid, null),
            prompt = task.prompt, then = com.openminis.app.agent.jobs.AgentJobThen.FollowUpParent(null),
        )
        registry.setTierUsed(job.id, resolution.tierUsed.wire)
        withContext(Dispatchers.Main) {
            val parentVm = HeadlessChatRunner.viewModelFor(app, parentSid)
            // [T-android-vm-store-dual-pool] The CHILD is tagged; `parentVm`
            // above is a normal session and deliberately keeps the default.
            HeadlessChatRunner.viewModelFor(
                app, child.id, com.openminis.app.ui.chat.ChatViewModelStore.PoolKind.CHILD,
            ).also {
                it.helperConfig = com.openminis.app.agent.jobs.HelperConfig(
                    parentSessionId = parentSid, parentToolUseId = "", jobId = job.id,
                    maxTurns = com.openminis.app.agent.jobs.HelperRunner.MAX_TURNS, title = title, tierUsed = resolution.tierUsed,
                )
                it.adoptBrowserTabPool(parentVm.browserTabPool)
                registry.markRunning(job.id, child.id) { if (it.isStreaming.value) it.cancelStream() }
            }
        }
        AppLogger.info(TAG, "task ${task.id}: child ${child.id.take(8)} of ${parentSid.take(8)} job=${job.id.take(8)} tier=${resolution.tierUsed.wire}")
        return child.id
    }

    /** Close a child-of-current job after its prompt returned; `then` posts the callback. */
    private suspend fun closeChildJob(app: MinisApp, childId: String, result: HeadlessChatRunner.PromptResult) {
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        val job = registry.jobForSession(childId)?.takeIf { it.isActive } ?: return
        val hr = com.openminis.app.agent.jobs.HelperRunner
        // [T-agent-port-round2] The headless wait gave up but the child may still
        // be running: stop it, or it would keep working as an orphan after the
        // job is closed (and the callback would report a stale partial).
        if (result.timedOut) runCatching { HeadlessChatRunner.cancel(app, childId) }
        val facts = hr.childRunFacts(app.chatRepository.dao.loadMessages(childId))
        registry.setSummaryLine(job.id, hr.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead))
        val state = when {
            result.timedOut || result.status == "Timeout" -> com.openminis.app.agent.jobs.AgentJobState.TIMEOUT
            result.status == "Error" || result.status == "Rejected" -> com.openminis.app.agent.jobs.AgentJobState.FAILED
            else -> com.openminis.app.agent.jobs.AgentJobState.DONE
        }
        registry.finish(job.id, state, facts.lastText)
    }

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
