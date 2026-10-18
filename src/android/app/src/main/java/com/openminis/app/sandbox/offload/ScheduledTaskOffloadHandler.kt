package com.openminis.app.sandbox.offload

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.scheduled.ScheduledModelResolver
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * [T-android-scheduled-tasks-full] minis-scheduled — CLI surface for the
 * Scheduled Tasks feature, exposing the same option set as the editor UI and
 * the iOS Shortcuts intents so the agent can create / inspect / fire timed
 * AI actions from a prompt.
 *
 *   minis-scheduled list
 *   minis-scheduled create --label L --time HH:MM --prompt "..."
 *                          [--repeat once|daily|weekdays|custom --days mon,tue,...]
 *                          [--target new|follow-up|rerun]
 *                          [--session <id>] [--message <id>]
 *                          [--model <modelId>]
 *                          [--start YYYY-MM-DD] [--end YYYY-MM-DD]
 *                          [--disabled]
 *   minis-scheduled delete --id <taskId>
 *   minis-scheduled enable  --id <taskId>
 *   minis-scheduled disable --id <taskId>
 *   minis-scheduled run     --id <taskId>     (fire immediately, off-schedule)
 *
 * Target modes mirror iOS App Intents:
 *   new        ≈ SendPrompt(no session)   — run prompt in a fresh chat
 *   follow-up  ≈ FollowUpSession           — append prompt to --session
 *   rerun      ≈ RetryRun                  — re-run --session from --message
 */
class ScheduledTaskOffloadHandler(private val context: Context) : NativeOffloadHandler {

    private val manager get() = ScheduledTaskManager(context)

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(
            request.argv.drop(1),
            booleanFlags = setOf("disabled", "h", "help", "allow-unknown-model"),
        )
        // [T-offload-defaults-batch-android] Help only on explicit
        // --help/-h; no subcommand defaults to `list`.
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }
        return try {
            when (val sub = args.positional.firstOrNull() ?: "list") {
                "list" -> handleList()
                "create", "add" -> handleCreate(args)
                "delete", "remove", "rm" -> handleDelete(args)
                "enable" -> handleSetEnabled(args, true)
                "disable" -> handleSetEnabled(args, false)
                "run" -> handleRun(args)
                else -> NativeOffloadResult(2, "minis-scheduled: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: IllegalArgumentException) {
            NativeOffloadResult(2, "minis-scheduled: ${e.message}")
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "handle failed: ${e.message}")
            NativeOffloadResult(1, "minis-scheduled: ${e.message}")
        }
    }

    private fun handleList(): NativeOffloadResult {
        val arr = JSONArray()
        for (t in manager.list()) arr.put(taskJson(t))
        val out = JSONObject().put("tasks", arr).put("count", arr.length())
        return NativeOffloadResult(0, out.toString(2))
    }

    private fun handleCreate(args: OffloadArgs): NativeOffloadResult {
        val label = args.get("label", "l") ?: ""
        val (hour, minute) = parseTime(args.get("time", "t")
            ?: throw IllegalArgumentException("--time HH:MM required"))
        val repeat = parseRepeat(args.get("repeat", "r"))
        val customDays = if (repeat == ScheduledRepeatMode.CUSTOM) {
            parseDays(args.get("days") ?: throw IllegalArgumentException("--days required for custom repeat"))
        } else emptySet()
        val target = parseTarget(args)
        val prompt = args.get("prompt", "p") ?: ""
        if (target !is ScheduledTargetMode.RerunMessage && prompt.isBlank()) {
            throw IllegalArgumentException("--prompt required (except for rerun target)")
        }
        val task = ScheduledTask(
            label = label,
            timeOfDayHour = hour,
            timeOfDayMinute = minute,
            repeatMode = repeat,
            customDays = customDays,
            prompt = prompt,
            targetMode = target,
            modelId = resolveModelArg(args),
            enabled = !args.hasFlag("disabled"),
            startDateMs = args.get("start")?.let { parseDate(it) },
            endDateMs = args.get("end")?.let { parseDate(it) },
        )
        val saved = manager.create(task)
        val out = JSONObject().put("created", taskJson(saved))
        if (saved.id != task.id) out.put("already_existed", true)
        return NativeOffloadResult(0, out.toString(2))
    }

    private fun requireId(args: OffloadArgs): String {
        val raw = args.get("id") ?: throw IllegalArgumentException("--id required")
        return manager.resolveId(raw)
            ?: throw IllegalArgumentException("minis-scheduled: no task with id=$raw")
    }

    private fun handleDelete(args: OffloadArgs): NativeOffloadResult {
        val id = requireId(args)
        if (!manager.delete(id) || manager.get(id) != null) {
            return NativeOffloadResult(1, "minis-scheduled: delete did not persist for id=$id")
        }
        return NativeOffloadResult(0, JSONObject().put("deleted", id).toString())
    }

    private fun handleSetEnabled(args: OffloadArgs, enabled: Boolean): NativeOffloadResult {
        val id = requireId(args)
        if (manager.get(id) == null) return NativeOffloadResult(1, "minis-scheduled: no task with id=$id")
        manager.setEnabled(id, enabled)
        return NativeOffloadResult(0, JSONObject().put("id", id).put("enabled", enabled).toString())
    }

    private fun handleRun(args: OffloadArgs): NativeOffloadResult {
        val raw = args.get("id") ?: throw IllegalArgumentException("--id required")
        val id = manager.resolveId(raw) ?: return NativeOffloadResult(1, "minis-scheduled: no task with id=$raw")
        val task = manager.get(id) ?: return NativeOffloadResult(1, "minis-scheduled: no task with id=$id")
        // Fire immediately, off-schedule. Blocks until the agent loop finishes
        // (ScheduledAgentRunner waits internally). Mirrors the editor "Run now".
        val sessionId = runBlocking {
            com.openminis.app.scheduled.ScheduledAgentRunner.run(context, task)
        }
        val out = JSONObject()
            .put("id", id)
            .put("ran", sessionId != null)
            .apply { if (sessionId != null) put("sessionId", sessionId) }
        return NativeOffloadResult(if (sessionId != null) 0 else 1, out.toString(2))
    }

    // ── parsing helpers ──

    /**
     * [T-android-scheduled-model-resolve] Resolve `--model` against the
     * configured models NOW.
     *
     * Deferring this to fire time meant a typo saved cleanly and then failed
     * every unattended run — the exact opposite of what a scheduler is for.
     * Accepts a wire model id, an entry uuid or an unambiguous display name and
     * stores the canonical wire id.
     *
     * Returns null when `--model` was not given (the task keeps the runtime
     * default), and throws [IllegalArgumentException] — surfaced as exit code 2
     * by [handle] — for an unknown value, unless `--allow-unknown-model` was
     * passed for a model that will exist after a later provider refresh.
     */
    private fun resolveModelArg(args: OffloadArgs): String? {
        val raw = args.get("model", "m")
        val entries = (context.applicationContext as? com.openminis.app.MinisApp)
            ?.providerRepositoryOrNull?.config?.value?.modelEntries.orEmpty()
        return when (val resolved = ScheduledModelResolver.resolve(raw, entries)) {
            ScheduledModelResolver.Result.Unspecified -> null
            is ScheduledModelResolver.Result.Resolved -> resolved.modelId
            is ScheduledModelResolver.Result.Unknown ->
                if (args.hasFlag("allow-unknown-model")) {
                    raw
                } else {
                    val known = resolved.known.ifEmpty { listOf("(none configured)") }.joinToString(", ")
                    throw IllegalArgumentException(
                        "--model '${resolved.raw}' is not a configured model. Known ids: $known. " +
                            "Pass --allow-unknown-model to store it verbatim.",
                    )
                }
        }
    }

    private fun parseTime(s: String): Pair<Int, Int> {
        val parts = s.split(":")
        require(parts.size == 2) { "--time must be HH:MM" }
        val h = parts[0].toIntOrNull()?.takeIf { it in 0..23 }
            ?: throw IllegalArgumentException("bad hour in --time")
        val m = parts[1].toIntOrNull()?.takeIf { it in 0..59 }
            ?: throw IllegalArgumentException("bad minute in --time")
        return h to m
    }

    private fun parseRepeat(s: String?): ScheduledRepeatMode = when (s?.lowercase()) {
        null, "once" -> ScheduledRepeatMode.ONCE
        "daily" -> ScheduledRepeatMode.DAILY
        "weekdays" -> ScheduledRepeatMode.WEEKDAYS
        "custom" -> ScheduledRepeatMode.CUSTOM
        "interval" -> ScheduledRepeatMode.INTERVAL
        else -> throw IllegalArgumentException("--repeat must be once|daily|weekdays|custom|interval")
    }

    private fun parseDays(s: String): Set<Int> {
        val map = mapOf(
            "sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY,
            "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY,
            "sat" to Calendar.SATURDAY,
        )
        return s.split(",").mapNotNull { map[it.trim().lowercase().take(3)] }.toSet()
    }

    private fun parseTarget(args: OffloadArgs): ScheduledTargetMode {
        return when (args.get("target")?.lowercase() ?: "new") {
            "new" -> ScheduledTargetMode.NewSession
            "follow-up", "followup", "follow" -> {
                val sid = args.get("session", "s")
                    ?: throw IllegalArgumentException("--session required for follow-up target")
                ScheduledTargetMode.AppendToSession(sid)
            }
            "rerun", "re-run", "retry" -> {
                val sid = args.get("session", "s")
                    ?: throw IllegalArgumentException("--session required for rerun target")
                val mid = args.get("message")
                    ?: throw IllegalArgumentException("--message required for rerun target")
                ScheduledTargetMode.RerunMessage(sid, mid)
            }
            else -> throw IllegalArgumentException("--target must be new|follow-up|rerun")
        }
    }

    /** YYYY-MM-DD → local start-of-day epoch ms. */
    private fun parseDate(s: String): Long {
        val parts = s.split("-")
        require(parts.size == 3) { "--start/--end must be YYYY-MM-DD" }
        val y = parts[0].toInt(); val mo = parts[1].toInt(); val d = parts[2].toInt()
        return Calendar.getInstance().apply {
            clear(); set(y, mo - 1, d, 0, 0, 0)
        }.timeInMillis
    }

    private fun taskJson(t: ScheduledTask): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("label", t.label)
        put("time", "%02d:%02d".format(t.timeOfDayHour, t.timeOfDayMinute))
        put("repeat", t.repeatMode.name.lowercase())
        if (t.customDays.isNotEmpty()) put("days", JSONArray(t.customDays.toList()))
        put("prompt", t.prompt)
        put("target", t.targetMode.encode())
        if (t.modelId != null) put("model", t.modelId)
        put("enabled", t.enabled)
        t.nextTriggerMs()?.let { put("nextTriggerMs", it) }
        if (t.startDateMs != null) put("startDateMs", t.startDateMs)
        if (t.endDateMs != null) put("endDateMs", t.endDateMs)
        if (t.lastFiredAt != null) put("lastFiredAt", t.lastFiredAt)
        if (t.lastResultPreview != null) put("lastResultPreview", t.lastResultPreview)
        if (t.lastResultSessionId != null) put("lastResultSessionId", t.lastResultSessionId)
    }

    companion object {
        private const val TAG = "ScheduledTaskOffload"
        private val HELP = """
            minis-scheduled — manage timed AI tasks (mirrors the in-app Scheduled Tasks).

            (no subcommand)  Same as `list` (default subcommand)
            list
            create --time HH:MM --prompt "..." [--label L]
                   [--repeat once|daily|weekdays|custom --days mon,tue,...]
                   [--target new|follow-up|rerun --session <id> --message <id>]
                   [--model <modelId>] [--start YYYY-MM-DD] [--end YYYY-MM-DD] [--disabled]
            delete  --id <taskId>
            enable  --id <taskId>
            disable --id <taskId>
            run     --id <taskId>          fire immediately, off-schedule

            Target modes: new = run prompt in a fresh chat; follow-up = append prompt
            to an existing chat (--session); rerun = re-run a chat (--session) from a
            chosen user message (--message, prompt ignored).

            --model is resolved when the task is CREATED: a wire model id, an entry
            uuid or an unambiguous display name all work, and the stored value is the
            canonical id. An unknown value fails now instead of at every fire; pass
            --allow-unknown-model to store it verbatim anyway (e.g. a model that
            arrives with a later provider refresh).
        """.trimIndent()
    }
}
