package com.openminis.app.inbound

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.logging.AppLogger
import com.openminis.app.scheduled.ScheduledAgentRunner
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledSessionHold
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledTriggerKind
import kotlinx.coroutines.runBlocking

/**
 * [inbound-task-api] Starts a prompt through the same machinery as
 * `minis-scheduled create --prompt …` followed by `minis-scheduled run --id`.
 *
 * The row is a one-shot that is already consumed ([triggeredCount] = 1) and
 * disabled, then fired immediately with [ScheduledAgentRunner]. Disabled so
 * [ScheduledTaskManager.create] does not also register an AlarmManager
 * wakeup — that would run the prompt twice. `waitForCompletion` is false so
 * the HTTP call returns once the chat exists and the prompt is dispatched;
 * the agent keeps running on the runner's own scope, and the runner starts
 * the existing agent foreground service for the duration of the turn.
 *
 * Target: no session → a new chat (`--target new`). A session id → follow-up
 * into that chat (`--target follow-up --session …`).
 */
class ScheduledInboundTaskStarter(private val context: Context) : InboundTaskStarter {

    override fun start(prompt: String, session: String?, label: String?): StartOutcome {
        val app = context.applicationContext as? MinisApp
            ?: return StartOutcome.Rejected(503, "unavailable")
        if (!app.subsystemsReady()) return StartOutcome.Rejected(503, "unavailable")

        if (session != null) {
            val exists = try {
                runBlocking { app.chatRepository.getSession(session) != null }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "session lookup failed (${t.javaClass.simpleName})")
                return StartOutcome.Rejected(503, "unavailable")
            }
            if (!exists) return StartOutcome.Rejected(404, "session_not_found")
        }

        val task = buildTask(
            prompt = prompt,
            session = session,
            label = label,
            defaultLabel = app.getString(com.openminis.app.R.string.inbound_api_default_label),
            now = System.currentTimeMillis(),
        )
        val manager = ScheduledTaskManager(app)
        val created = manager.create(task)
        val target = targetOf(created.targetMode)
        val sessionId = try {
            runBlocking {
                ScheduledAgentRunner.run(
                    app,
                    created,
                    waitForCompletion = false,
                    scheduledFire = false,
                )
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "run failed id=${created.id} (${t.javaClass.simpleName})")
            null
        }
        if (sessionId == null) {
            runCatching { manager.delete(created.id) }
            AppLogger.warning(TAG, "not started id=${created.id}")
            return StartOutcome.Rejected(503, "not_started")
        }
        // Remember the chat now, so a status poll during the run can name it.
        // lastFiredAt stays empty until the runner records the finished run.
        manager.store().update(created.id) { current ->
            current.copy(lastResultSessionId = sessionId)
        }
        AppLogger.info(TAG, "started id=${created.id} target=$target")
        return StartOutcome.Started(created.id, sessionId, target)
    }

    override fun status(id: String): TaskView? {
        if (!id.matches(InboundTaskProtocol.ID)) return null
        val task = ScheduledTaskManager(context).get(id) ?: return null
        val running = (ScheduledSessionHold.runningTaskIds.value[id] ?: 0) > 0
        return describe(task, running)
    }

    companion object {
        private const val TAG = "InboundTask"

        internal fun buildTask(
            prompt: String,
            session: String?,
            label: String?,
            defaultLabel: String,
            now: Long,
        ): ScheduledTask {
            val cleanLabel = label?.trim()?.takeIf { it.isNotEmpty() } ?: defaultLabel
            val mode = if (session == null) {
                ScheduledTargetMode.NewSession
            } else {
                ScheduledTargetMode.AppendToSession(session)
            }
            return ScheduledTask(
                label = cleanLabel,
                timeOfDayHour = 0,
                timeOfDayMinute = 0,
                repeatMode = ScheduledRepeatMode.ONCE,
                prompt = prompt,
                targetMode = mode,
                enabled = false,
                triggerKind = ScheduledTriggerKind.AFTER,
                delaySec = 0,
                anchorMs = now,
                // Already consumed, so a later registerAlarm (while still
                // disabled this is moot; if something arms it without going
                // through setEnabled) finds no pending slot. setEnabled(true)
                // in the task editor resets this count — that is the existing
                // "turn a relative trigger back on" behavior.
                triggeredCount = 1,
                creatorSessionId = session,
            )
        }

        internal fun targetOf(mode: ScheduledTargetMode): String = when (mode) {
            ScheduledTargetMode.NewSession -> "new"
            is ScheduledTargetMode.AppendToSession -> "follow-up"
            else -> "other"
        }

        internal fun describe(task: ScheduledTask, running: Boolean): TaskView {
            val recorded = task.lastFiredAt != null
            val historyOk = task.runHistory.firstOrNull()?.ok
            val status = InboundTaskProtocol.runStatus(running, recorded, historyOk)
            val ok = when {
                !recorded -> null
                historyOk != null -> historyOk
                else -> true
            }
            return TaskView(
                id = task.id,
                label = task.label,
                status = status,
                target = targetOf(task.targetMode),
                sessionId = task.lastResultSessionId ?: task.targetMode.sessionIdOrNull,
                ok = ok,
            )
        }
    }
}
