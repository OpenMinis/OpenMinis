package com.openminis.app.agent.jobs

import android.content.Context
import com.openminis.app.R
import org.json.JSONArray
import org.json.JSONObject

// [T-p1-delegate-task / T-p2-agent-series] The `delegate_task` tool, Android
// port of iOS `HelperRunner.swift` at its FINAL state: background by default
// (the call returns at once, the agent keeps running and reports back into
// the parent conversation as an <agent_callback> message), wait=true as the
// opt-in blocking mode, progress reports, a wrap-up turn before the budget
// kills a run, and a final payload that is written back into the original
// tool block and its stored tool_result.
//
// The orchestration (child session, child ViewModel, mirrors, watchers)
// lives in `ChatViewModel.executeDelegateTask` because it needs the vm's
// private message/block state. Everything pure — argument parsing, limits,
// the JSON envelopes, the prompts — lives here so it is unit-testable.

/** Set on a child (helper) ChatViewModel before its first turn. */
data class HelperConfig(
    val parentSessionId: String,
    val parentToolUseId: String,
    val jobId: String,
    /** Hard cap on the child's agent-loop turns (design §4.3: 25). */
    val maxTurns: Int,
    val title: String,
    val tierUsed: HelperModelTier,
    /**
     * [T-sub-agents-v1] The sub agent this child runs as. Null for a run
     * started before the roster existed (a resumed old job), which behaves as
     * the built-in.
     */
    val agentName: String? = null,
    /**
     * [T-sub-agents-v1] The definition's user-written instructions, appended
     * to the child's system prompt. Empty = nothing appended.
     */
    val agentInstructions: String = "",
)

data class DelegateTaskArgs(
    val title: String,
    val task: String,
    val context: String,
    val tier: HelperModelTier,
    val tierRequested: String,
    /**
     * [T-sub-agents-v1] The sub agent the model asked for, verbatim. Null or
     * blank = the built-in. Resolution happens against the live roster at
     * dispatch, not here, so a rename between schema build and call is caught.
     */
    val agentName: String?,
    /**
     * [T-sub-agents-v1] Only consulted when the resolved definition pins no
     * Model Group. Unknown/absent parses to `same_as_me` — the safe direction,
     * since it is the model the user already chose for this conversation.
     */
    val modelChoice: SubAgentModelChoice,
    val minutes: Int,
    /** false (default): background; true: block this call for the result. */
    val wait: Boolean,
    /** none | frequent | moderate (background only). */
    val progressLevel: String,
)

enum class HelperWrapUpReason { TURNS, BUDGET }

object HelperRunner {
    /**
     * [T-sub-agents-v1] The tool name the model sees and the name new blocks
     * are written under: `subagent_task`.
     */
    const val TOOL_NAME = com.openminis.app.data.model.SubAgentDefinition.TOOL_NAME

    /**
     * [T-sub-agents-v1] The pre-rename names, still RECOGNISED but never
     * emitted.
     *
     * iOS could rename without a shim because sub agents had not shipped there.
     * On Android delegation DID ship, so transcripts on real devices already
     * carry `delegate_task` / `agent_status` blocks. The renderer and the
     * dispatcher both key off the tool name, so dropping the old names would
     * turn every past delegation in a user's history from an agent card into an
     * anonymous tool row, and would make a replayed call fall through to
     * "Unknown tool".
     *
     * Recognition is one-way: nothing writes these, so the transcripts stop
     * accumulating them.
     */
    const val LEGACY_TOOL_NAME = "delegate_task"
    const val LEGACY_STATUS_TOOL_NAME = "agent_status"

    /** True for the current name or either name a shipped build wrote. */
    fun isSubAgentToolName(name: String?): Boolean =
        name == TOOL_NAME || name == LEGACY_TOOL_NAME || name == LEGACY_STATUS_TOOL_NAME

    // Limits (design §4.3) — enforced in code, not prompt.
    /**
     * [T-subagent-turn-parity] Tool-round ceiling for a child, matched to the
     * main chat's own cap (ChatViewModel.MAX_AGENT_TURNS).
     *
     * Was 25, ported from iOS to match it exactly (cfca59ee8). In the field
     * that proved too tight for the work children are actually given: testers
     * hit it repeatedly, and 13 of 14 recorded child sessions ended at exactly
     * the cap rather than because the task was done. A child doing real work —
     * survey a repo, then write the deliverable — spent its rounds on the
     * survey and had none left to produce the artifact.
     *
     * There is no principled reason for a delegated task to get 8x fewer
     * rounds than the same work done inline in the parent conversation, so the
     * two ceilings are now the same number. The time budget
     * ([DEFAULT_MINUTES]/[MAX_MINUTES]) is what actually bounds a runaway
     * child, and it is unchanged; this cap is the backstop behind it.
     */
    const val MAX_TURNS = 200
    const val MAX_PER_ASSISTANT_TURN = 3
    const val DEFAULT_MINUTES = 10
    const val MAX_MINUTES = 60

    /** Extra seconds a helper gets after its budget expires to answer the wrap-up prompt. */
    const val WRAP_UP_GRACE_MS = 90_000L
    /** Seconds a running tool gets to return on its own after the budget expires
     *  before it is interrupted so the wrap-up turn can start. */
    const val WRAP_UP_TOOL_PATIENCE_MS = 20_000L

    const val PROGRESS_INTERVAL_FREQUENT_MS = 15_000L
    const val PROGRESS_INTERVAL_MODERATE_MS = 60_000L
    const val PROGRESS_LAST_MESSAGE_MAX_CHARS = 800

    /**
     * [T-sub-agents-queue] Marker put into a queued delegation's args so the
     * re-entry knows the per-turn allowance has already been served. See the
     * deadlock note at the dispatch site.
     */
    const val QUEUED_REENTRY_KEY = "__from_queue"

    /**
     * [T-android-subagent-prompt-parity] Marks a queued item as a RESUME of an
     * interrupted child rather than a fresh delegation, carrying the child's
     * session id. Ported from iOS `__resume_child` (HelperRunner.swift:1438).
     *
     * The queue drains through one starter callback, so without a marker a
     * deferred resume would come back as a brand-new delegation and start the
     * task over from nothing — losing exactly the work the resume exists to
     * preserve.
     */
    const val QUEUED_RESUME_CHILD_KEY = "__resume_child"

    /**
     * [T-subagent-steer-continues-loop] Restarts an idle child's loop so a
     * pending course correction is actually delivered. Byte-identical to iOS
     * `steerNudgePrompt` (HelperRunner.swift:257).
     *
     * Deliberately says nothing itself: the loop prepends the real correction
     * at the top of the turn this starts, and a second instruction here would
     * compete with it for the child's attention.
     */
    const val STEER_NUDGE_PROMPT =
        "The delegating agent has sent you a course correction. Read it and continue."

    /**
     * [T-subagent-steer-continues-loop] Whether a child whose turn just ended
     * with no tool calls should take one more turn to read a pending course
     * correction, instead of finishing and reporting it as missed.
     *
     * - Only for a sub agent ([isHelper]); a main conversation has no steers.
     * - Not after an empty turn: that turn already gave up with an error.
     * - Not on the last permitted round: another iteration does not exist, and
     *   forcing one would take the "exceeded the turn limit" error path. The
     *   steer is then reported as missed, which is the truthful outcome.
     *
     * [turn] is the 0-based index of the turn that just ended.
     */
    fun shouldContinueForSteer(
        isHelper: Boolean,
        isEmptyTurn: Boolean,
        turn: Int,
        turnCap: Int,
        pendingSteers: Int,
    ): Boolean = isHelper && !isEmptyTurn && pendingSteers > 0 && turn + 1 < turnCap

    fun parseArgs(argsJson: String): DelegateTaskArgs {
        val o = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val title = o.optString("tool_title", "").trim()
        val task = o.optString("task", "").trim()
        val tierRaw = o.optString("model_tier", "primary").trim().lowercase()
        // [T-sub-agents-v1] `agent` replaces the old model_tier as the way the
        // model picks WHO runs the task; model_tier is still parsed above so a
        // replayed call from a pre-rename transcript keeps working.
        val agentName = o.optString("agent", "").trim().ifEmpty { null }
        val modelChoice = SubAgentModelChoice.parse(o.optString("model_choice", "").trim())
        val minutesRaw = if (o.has("max_minutes")) o.optDouble("max_minutes", DEFAULT_MINUTES.toDouble()).toInt() else DEFAULT_MINUTES
        val level = o.optString("progress_report", "none").trim().lowercase()
        return DelegateTaskArgs(
            title = title.ifEmpty { task.take(40) },
            task = task,
            context = o.optString("context", "").trim(),
            tier = HelperModelTier.parse(tierRaw),
            tierRequested = tierRaw.ifEmpty { "primary" },
            agentName = agentName,
            modelChoice = modelChoice,
            minutes = minutesRaw.coerceIn(1, MAX_MINUTES),
            // [T-p2-background-default] Background is the default; wait=true is
            // the opt-in for "the next step needs this result".
            wait = if (o.has("wait")) o.optBoolean("wait", false) else false,
            progressLevel = if (level in setOf("none", "frequent", "moderate")) level else "none",
        )
    }

    /** The prompt the child receives: task + optional verbatim context. */
    fun childPrompt(args: DelegateTaskArgs): String =
        if (args.context.isEmpty()) args.task
        else args.task + "\n\n--- Context from the delegating agent ---\n" + args.context

    /** The child session's title — the one place that spells the prefix. */
    /**
     * [T-sub-agents-v1] `subAgentName` is omitted for the built-in definition,
     * so an ordinary delegation keeps exactly the title format it had.
     */
    fun childSessionTitle(context: Context, title: String, subAgentName: String? = null): String {
        val label = context.getString(R.string.helper_label)
        val name = subAgentName?.trim().orEmpty()
        return if (name.isEmpty()) "$label · $title" else "$label · $name · $title"
    }

    /** Strip the (current-locale or legacy) prefix back off a stored title. */
    fun stripChildSessionTitlePrefix(context: Context, title: String): String {
        var t = title
        for (prefix in listOf("${context.getString(R.string.helper_label)} · ", "Agent · ", "Helper · ", "代理任务 · ", "帮手 · ")) {
            if (t.startsWith(prefix)) t = t.removePrefix(prefix)
        }
        return t
    }

    // ── Prompts ──────────────────────────────────────────────────────────

    /** [T-agent-wrapup-turn] The "time's up, write it up" message. */
    fun wrapUpPrompt(reason: HelperWrapUpReason): String {
        val why = if (reason == HelperWrapUpReason.TURNS) "You have used all of your tool rounds." else "Your time budget is up."
        return "[$why Tools are no longer available for this turn.]\n" +
            "Write your final answer NOW from what you already have. It must contain the complete deliverable itself — the full report, document, list or answer the task asked for — not a description of what you did, not a pointer to a file, not a request for more time. Mark anything you could not verify as unverified. This message is returned to the parent agent verbatim."
    }

    /**
     * [T-subagent-turn-countdown] How many rounds before the cliff the child is
     * warned that its tool budget is nearly gone.
     *
     * The cap itself is deliberate (design §4.3) and unchanged. What was
     * missing is any FEEDBACK on the way to it: the child is told "you have at
     * most N tool rounds" once, in the system prompt, and then has to count its
     * own turns across a long run — which models reliably fail to do. The
     * reported symptom is the consequence: a child spends its whole budget
     * investigating, and the first time it learns the budget is over is the
     * wrap-up turn, where tools are already gone and it can no longer write the
     * file it was asked to produce.
     *
     * 3 leaves room to actually act: one round to finish the current thread of
     * work, one to write the deliverable to disk, one spare — and still lands
     * before the tools-withdrawn turn.
     */
    const val TURN_WARNING_LEAD = 3

    /**
     * [T-subagent-turn-countdown] The nudge injected at [TURN_WARNING_LEAD]
     * rounds remaining.
     *
     * Deliberately NOT the wrap-up text: tools still work here, and the whole
     * point is to spend the remaining ones on finishing rather than on more
     * investigation. Says the number out loud because that is precisely the
     * fact the child cannot derive for itself.
     */
    fun turnBudgetWarning(remaining: Int): String =
        "[Budget check: $remaining tool ${if (remaining == 1) "round" else "rounds"} left before tools are withdrawn.] " +
            "Stop investigating now and spend what is left on finishing: if the task asked you to write a file or produce an artifact, do it in the next round, then write your final answer. " +
            "Remember the final message must contain the complete deliverable itself, not a pointer to it."

    /** What the parent reads when the child really ended without any text. */
    fun emptyResultNote(status: String): String =
        "(the agent ended with status $status before writing a final answer; there is no deliverable — re-delegate with a narrower task or a larger budget rather than reading its transcript)"

    /** System-prompt preamble for a helper vm — replaces the SOUL identity
     *  section. Describe the environment, do not assign identity (the
     *  `minis-model-use` identity-pollution lesson, OpenMinis#103). */
    fun identitySection(cfg: HelperConfig, browserEnabled: Boolean = true): String =
        "You are running as a helper (sub-agent) inside an app called Minis, on an Android device with a fully functional Linux shell (Alpine Linux, aarch64). This is the calling environment, not your identity — keep your own model identity unchanged.\n" +
            "A parent agent delegated ONE focused task to you: \"${cfg.title}\". You cannot see the parent conversation and it cannot see yours; everything you need is in the task text. Work the task to completion using your tools, then finish with a single clear final answer — that final message is returned verbatim to the parent agent as the result, and it is the ONLY thing the parent receives. So the final message must contain the complete deliverable itself: the full report, document, list, code or answer the task asked for, in full — never a summary of what you did, a pointer to a file you wrote, or \"see above\". If you produced a file, paste its full content into the final message as well. Do not ask the parent or the user questions; make reasonable assumptions and state them. Do not restate the task or add meta-commentary. Do not create scheduled tasks or delegate further.\n" +
            browserSharingSection(browserEnabled) +
            "You have at most ${cfg.maxTurns} tool rounds; on the last one tools are withdrawn and you are asked to write the deliverable from what you have, so start writing before you run out. If the task genuinely exceeds what you can do at your capability level, end your final message with a line `[ESCALATE] <one-line reason>` so the parent can rerun it on a stronger model.\n\n" +
            subAgentInstructionsSection(cfg.agentInstructions)

    /**
     * [T-android-subagent-prompt-parity] The shared-browser paragraph iOS gives
     * every child (HelperRunner.swift:1174), which Android was missing.
     *
     * [T-android-browser-tab-ownership] already ENFORCES all of this — a child
     * may only touch tabs it owns, list_tabs shows only its own, and the quota
     * is [BrowserTabPool.AGENT_TAB_QUOTA]. But enforcement without explanation
     * is what produced the incident that built the feature: agents guessed tab
     * ids they had never been handed, got refused, and reacted by opening more
     * tabs. Telling the child the rule up front is what turns a refusal it
     * cannot interpret into a constraint it can plan around.
     *
     * Gated on the browser switch: a child whose browser_use is off has no tabs
     * and must not be told it has any — the same prompt/schema drift rule the
     * parent's browser bullet follows.
     */
    fun browserSharingSection(browserEnabled: Boolean): String {
        if (!browserEnabled) return ""
        return "Browser: the browser is shared with the parent and with other agents. " +
            "You may only use your own tabs (up to ${com.openminis.app.browser.BrowserTabPool.AGENT_TAB_QUOTA}); " +
            "list_tabs shows just yours. To open a page, navigate (a tab of yours is picked or created) " +
            "or use new_tab; never guess tab ids you did not receive, and reuse your tabs by navigating " +
            "them instead of opening more.\n"
    }

    /**
     * [T-sub-agents-v1] The definition's own instructions, appended to the
     * child's preamble under a delimiter that marks them as the USER's text
     * rather than the system's.
     *
     * Delimiter is byte-identical to iOS so a transcript reads the same on both
     * platforms. Built-in and custom agents take the same path — the built-in
     * simply has none, so this is empty for it.
     */
    fun subAgentInstructionsSection(instructions: String): String {
        val trimmed = instructions.trim()
        if (trimmed.isEmpty()) return ""
        return "\n--- Sub agent instructions (set by the user) ---\n" + trimmed + "\n\n"
    }

    /**
     * [T-sub-agents-v1] The "which sub agent for which job" section of the
     * system prompt.
     *
     * Built from the SAME roster the `agent` parameter's enum comes from, so a
     * name can never be advertised in one and rejected by the other. The
     * per-line Model note tells the model where each agent runs — information
     * only (it does not choose that) but it explains why two agents can behave
     * differently on the same task.
     *
     * Cost is bounded by SubAgentLimits, with a defensive take() here in case a
     * definition reached storage through a path that skipped the clamp.
     */
    fun subAgentRosterSection(
        roster: List<com.openminis.app.data.model.SubAgentDefinition>,
        groupName: (String) -> String?,
    ): String {
        if (roster.isEmpty()) return ""
        val lines = mutableListOf(
            "Available sub agents (pass the name as ${TOOL_NAME}.agent):",
        )
        for (def in roster) {
            val desc = def.description
                .take(com.openminis.app.data.model.SubAgentLimits.DESCRIPTION_MAX_LENGTH)
                .trim()
            val pinned = def.modelGroupId?.let { groupName(it) }
            val model = if (pinned != null) {
                // Pinned by the user: model_choice does not apply to this one.
                "fixed — $pinned"
            } else {
                "Auto — you choose with model_choice"
            }
            lines.add("- ${def.name} — $desc Model: $model.")
        }
        lines.add("Prefer a specific sub agent when its description matches; otherwise use the general one.")
        return lines.joinToString("\n") + "\n"
    }

    /** The parent-tier system-prompt bullet (byte-identical to iOS), or "" when
     *  the tool is not offered (helper vm, or Settings › Agents off). */
    fun systemPromptBullet(enabled: Boolean): String = if (!enabled) "" else
        "- subagent_task: Delegate a self-contained task to a sub agent that runs its own tool loop in an isolated hidden session, and inspect or stop the ones you started. Full contract in the tool schema. Two things it does not say: the `<agent_callback>` result and progress messages are written by the system, not typed by the user; and while a sub agent runs the user sees it in the tool bar and can watch or stop it.\n"

    // ── Payloads (block content + tool_result JSON) ─────────────────────

    /**
     * [T-sub-agents-queue] Whether this block's delegation is still waiting in
     * the live queue, as opposed to having been queued in a previous run of the
     * app. Drives "Queued" vs "Never started".
     */
    fun isQueuedLive(toolUseId: String): Boolean = AgentJobRegistry.isQueued(toolUseId)

    /**
     * [T-sub-agents-resume] What a resumed child is told on its first turn.
     *
     * States plainly what did and did not survive the restart, because the
     * child's transcript still shows tool results whose side effects are gone:
     * files it wrote are still there, but browser tabs were closed and shell
     * processes ended. Without this it would happily keep referring to a tab
     * that no longer exists.
     */
    fun resumeNotice(): String =
        "[This run was interrupted and has been resumed. The tool results above are still valid, but all live state is gone: browser tabs are closed, shell processes have ended, and anything unsaved is lost. Files in the workspace are still there. Continue from what the transcript already establishes — reopen pages or re-run commands when you need them, and do not assume anything is still open.]"

    /**
     * [T-android-no-deliverable] The status word for a run that ended, taking
     * the RESULT into account and not just the job state.
     *
     * A sub agent that finishes its loop without writing a deliverable used to
     * report `completed`, so the card showed a green tick and "Done" while the
     * parent received nothing. That is more deceptive than an error: the user
     * reads success and only discovers the gap later.
     *
     * Deliberately NOT called "failed". Nothing broke — the transcript is
     * intact and the tools all ran; there is simply nothing to hand over. It is
     * its own outcome and reads as one.
     *
     * Only a cleanly finished run is reclassified. `cancelled` / `timeout` /
     * `failed` already report a non-success status, and relabelling those would
     * throw away the reason the run ended.
     */
    const val NO_DELIVERABLE = "no_deliverable"

    fun resolvedStatus(status: String, result: String): String {
        if (status != "completed") return status
        return if (result.isBlank()) NO_DELIVERABLE else status
    }

    /**
     * [T-android-subagent-error-surface] A few words naming WHY a run failed,
     * from the child's error text. Null when nothing is recognisable.
     *
     * The card has room for a label, not a stack trace, and "Failed" alone
     * gives the user nothing to act on — a rate limit, a dead network and a
     * context overflow need three different responses.
     *
     * Ordered most-specific first, deliberately: an overload response also
     * carries 429-adjacent wording on some providers, and a context error
     * usually mentions "token", which the quota branch would otherwise claim.
     *
     * Attribution is best-effort. Anything unrecognised returns null and keeps
     * the plain "Failed" label rather than inventing a category — a wrong
     * attribution is worse than none, because the user acts on it. The full
     * text is carried separately for the detail sheet.
     *
     * Not localized here: the caller resolves the string resource. Keeping the
     * classifier a pure function of the text is what makes it testable.
     */
    enum class ErrorKind(val res: Int) {
        CONTEXT_LIMIT(R.string.agent_error_context_limit),
        RATE_LIMITED(R.string.agent_error_rate_limited),
        QUOTA_EXHAUSTED(R.string.agent_error_quota_exhausted),
        PROVIDER_OVERLOADED(R.string.agent_error_provider_overloaded),
        TIMED_OUT(R.string.agent_error_timed_out),
        AUTH_FAILED(R.string.agent_error_auth_failed),
        NETWORK_ERROR(R.string.agent_error_network),
        CANCELLED(R.string.agent_error_cancelled),
        ;

        /** The stable wire value carried in the result payload. */
        val wire: String get() = name.lowercase()

        companion object {
            fun fromWire(v: String?): ErrorKind? =
                v?.let { w -> entries.firstOrNull { it.wire == w } }
        }
    }

    fun errorKind(raw: String?): ErrorKind? {
        val s = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (s.contains("context") && (s.contains("length") || s.contains("window") || s.contains("too long"))) {
            return ErrorKind.CONTEXT_LIMIT
        }
        if (s.contains("rate limit") || s.contains("429") || s.contains("too many requests")) {
            return ErrorKind.RATE_LIMITED
        }
        if (s.contains("quota") || s.contains("insufficient") || s.contains("credit") || s.contains("billing")) {
            return ErrorKind.QUOTA_EXHAUSTED
        }
        if (s.contains("overload") || s.contains("529") || s.contains("503") || s.contains("unavailable")) {
            return ErrorKind.PROVIDER_OVERLOADED
        }
        if (s.contains("timed out") || s.contains("timeout")) return ErrorKind.TIMED_OUT
        if (s.contains("unauthor") || s.contains("401") || s.contains("403") ||
            s.contains("api key") || s.contains("credential")
        ) {
            return ErrorKind.AUTH_FAILED
        }
        if (s.contains("offline") || s.contains("network") || s.contains("connection") ||
            s.contains("host") || s.contains("internet")
        ) {
            return ErrorKind.NETWORK_ERROR
        }
        if (s.contains("cancel")) return ErrorKind.CANCELLED
        return null
    }

    // ── Control calls  [T-android-subagent-control-hidden] ─────────────────

    /** What a `subagent_task` block actually was. */
    enum class SubAgentCall { DELEGATE, STATUS, RESUME, STEER, CANCEL }

    /**
     * Classify a `subagent_task` block from its arguments, falling back to the
     * shape of its result.
     *
     * A control call (status / steer / cancel / resume) is the model managing
     * the sub agents it already started. It has no result of its own to show:
     * its effect lands on the card of the run it acted on, so rendering it as
     * its own full card is a duplicate row for work displayed elsewhere.
     *
     * [argsJson] is the preferred signal — it answers the question directly.
     * Android persists tool input and restores it on reload (ChatViewModel's
     * block rebuild sets `toolArgs = toolInput`), so unlike iOS the args are
     * usually still there. The result-shape fallback is kept anyway for any
     * path that did not record them.
     */
    fun classifyCall(argsJson: String?, resultJson: String?): SubAgentCall {
        // 1. The call's own action.
        val action = argsJson?.let {
            runCatching { JSONObject(it).optString("action", "").trim().lowercase() }.getOrNull()
        }?.takeIf { it.isNotEmpty() }
        if (action != null) {
            return when (action) {
                "status" -> SubAgentCall.STATUS
                "resume" -> SubAgentCall.RESUME
                "steer" -> SubAgentCall.STEER
                "cancel" -> SubAgentCall.CANCEL
                else -> SubAgentCall.DELEGATE
            }
        }

        // 2. Infer from the result for a block whose arguments were not kept.
        val o = resultJson?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return SubAgentCall.DELEGATE

        // ORDER MATTERS. A delegation ALWAYS carries child_session_id — it is
        // the run it started — and a control call never does, so this must be
        // tested FIRST. A RESUMED delegation's result also carries a `resumed`
        // key (the marker saying the run had been interrupted), so keying on
        // that instead would hide the very card the user had just resumed.
        // iOS recorded hitting exactly this.
        if (o.has("child_session_id") && o.optString("child_session_id").isNotEmpty()) {
            return SubAgentCall.DELEGATE
        }
        if (o.has("agents")) return SubAgentCall.STATUS
        if (o.has("child_session_ids")) return SubAgentCall.RESUME
        if (o.optString("status") == "queued" && o.has("job_id")) return SubAgentCall.STEER
        val reason = o.optString("reason")
        if (reason == "already_finished" || reason == "child_not_running") return SubAgentCall.STEER
        return SubAgentCall.DELEGATE
    }

    /** True when this block is a control call and should not get its own card. */
    fun isControlOnly(argsJson: String?, resultJson: String?): Boolean =
        classifyCall(argsJson, resultJson) != SubAgentCall.DELEGATE

    /**
     * How many sub agents a control result touched, for the summary line.
     * Null when the result does not say — the caller then omits the count
     * rather than guessing, because "checked 3" says something "checked sub
     * agents" does not, and a wrong number says something worse.
     */
    fun controlCount(call: SubAgentCall, resultJson: String?): Int? {
        val o = resultJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return when (call) {
            SubAgentCall.STATUS -> o.optJSONArray("agents")?.length()
            SubAgentCall.RESUME -> o.optJSONArray("child_session_ids")?.length()
            else -> null
        }
    }

    /** [T-sub-agents-queue] The tool_result a queued delegation returns at once. */
    fun queuedJson(queuedBehind: Int, detail: String): String =
        JSONObject()
            .put("ok", true)
            .put("status", "queued")
            .put("queued_behind", maxOf(0, queuedBehind))
            .put("detail", detail)
            .toString()

    fun rejectionJson(reason: String, detail: String): String =
        JSONObject().put("ok", false).put("status", "rejected").put("reason", reason).put("detail", detail).toString()

    /** Live progress written into the parent block while the helper runs. */
    fun progressJson(childSessionId: String, title: String, tool: String, activity: String, elapsedMs: Long, background: Boolean = false): String =
        JSONObject()
            .put("status", "running")
            .put("child_session_id", childSessionId)
            .put("title", title)
            .put("tool", tool)
            .put("activity", activity.take(80))
            .put("elapsed_s", elapsedMs / 1000)
            .put("background", background)
            .toString()

    /** Wait-mode final payload (also used for the completion hook, with `deliveredAs` set). */
    fun resultJson(
        status: String, result: String, modelLabel: String, tierUsed: HelperModelTier, tierRequested: String,
        turns: Int, elapsedMs: Long, childSessionId: String, jobId: String,
        summary: String = "", deliveredAs: String? = null,
        // [T-sub-agents-v1] WHO ran it and WHERE it ran. `agent` is omitted for
        // the built-in (there is nothing to disambiguate), and
        // modelGroupUnavailable is reported only when true — a pinned group
        // that silently stopped applying is worth saying out loud.
        agentName: String? = null,
        modelOrigin: HelperModelOrigin? = null,
        modelGroupName: String? = null,
        modelGroupUnavailable: Boolean = false,
        /**
         * [T-android-subagent-prompt-parity] See [RESUMED_NOTE]. Only emitted
         * when true, so an uninterrupted run's payload is byte-unchanged.
         */
        wasResumed: Boolean = false,
        /**
         * [T-android-subagent-error-surface] The child's raw error text. The
         * run's status already says "failed"; only this says WHY, and without
         * it nothing downstream can show a reason. Classified into
         * `error_kind` for the card, carried verbatim as `error_detail` for
         * the detail sheet — a label is what fits on a card, but "why did it
         * fail" needs the actual text.
         */
        errorText: String? = null,
        /** [T-android-agent-model-identity] Resolved-side identity; see below. */
        resolvedEntryId: String? = null,
        resolvedProviderLabel: String? = null,
        resolvedProviderType: String? = null,
        resolvedModelId: String? = null,
        resolvedModelName: String? = null,
        /**
         * [T-subagent-thinking-badge] ThinkingLevel name the run actually used
         * ("HIGH"), after the definition override, the group default and the
         * per-model clamp. null when reasoning stayed off.
         */
        thinkingLevel: String? = null,
    ): String = JSONObject()
        .put("ok", status == "completed")
        .put("status", status)
        .put("result", result.ifEmpty { emptyResultNote(status) })
        .put("model_used", modelLabel)
        .put("tier_used", tierUsed.wire)
        .put("tier_requested", tierRequested)
        // [T-android-agent-model-identity] Resolved-side identity: WHICH entry,
        // on WHICH provider instance. `model_used` is a display label — it
        // cannot distinguish two instances of the same provider, and it does
        // not survive a rename. The detail sheet's Model group / Model tier
        // rows read these, and a payload reloaded after a restart needs them to
        // still say where the run actually went.
        .also { j ->
            fun put(k: String, v: String?) { if (!v.isNullOrEmpty()) j.put(k, v) }
            put("model_resolved_entry_id", resolvedEntryId)
            put("model_resolved_provider", resolvedProviderLabel)
            put("model_resolved_provider_type", resolvedProviderType)
            put("model_resolved_id", resolvedModelId)
            put("model_resolved_name", resolvedModelName)
            put("thinking_level", thinkingLevel)
        }
        .put("escalation_requested", result.contains("[ESCALATE]"))
        .put("turns", turns)
        .put("elapsed_s", elapsedMs / 1000)
        .put("child_session_id", childSessionId)
        .put("job_id", jobId)
        .put("summary", summary)
        .also { if (deliveredAs != null) it.put("delivered_as", deliveredAs) }
        .also { if (!agentName.isNullOrEmpty()) it.put("agent", agentName) }
        .also { j -> modelOrigin?.let { j.put("model_origin", it.wire) } }
        // [T-android-subagent-model-strategy] The group's own name, so the card
        // can say which group the user configured instead of a bare id.
        .also { j -> modelGroupName?.let { if (it.isNotBlank()) j.put("model_group_name", it) } }
        .also { if (modelGroupUnavailable) it.put("model_group_unavailable", true) }
        .also { j ->
            val detail = errorText?.trim()?.takeIf { it.isNotEmpty() }
            if (detail != null) {
                j.put("error_detail", detail)
                errorKind(detail)?.let { j.put("error_kind", it.wire) }
            }
        }
        .also {
            if (wasResumed) {
                it.put("resumed", true)
                it.put("resumed_note", RESUMED_NOTE)
            }
        }
        .toString()

    /**
     * [T-android-subagent-prompt-parity] What the parent must be told about a
     * run that was interrupted and restarted. Byte-identical to iOS
     * `resumed_note` (HelperRunner.swift:765).
     *
     * Two things are wrong with reading a resumed run's payload naively: its
     * elapsed and turn counts describe only the resumed part, so the work looks
     * cheaper than it was; and everything live at the interruption — open pages,
     * running processes — is gone, so a result that depended on such state may
     * be stale in a way the numbers do not reveal.
     */
    const val RESUMED_NOTE =
        "This run was interrupted and resumed; its tool context was lost at that point, and the " +
            "elapsed/turn counts cover only the resumed part. Verify anything that depended on a " +
            "page or process staying open."

    /** The tool_result a background delegation returns at once. */
    /**
     * [T-android-agent-model-identity] Write the model-identity keys onto a
     * payload. Byte-compatible with iOS `HelperModelIdentity.payload()`.
     *
     * One emitter shared by the background-start and final payloads, because
     * they used to disagree: the final one carried `model_origin` while the
     * background one carried only `tier_used` — the internal resolver stage the
     * card was told to stop showing. A background delegation's block therefore
     * had no way to say which model strategy produced it, and since a
     * background run is the DEFAULT (wait=false) that was the common case.
     *
     * Every key is omitted when empty, so a resolution that knows nothing extra
     * (an inherited binding names no entry of its own) produces exactly the
     * payload it did before this existed.
     */
    fun JSONObject.putModelIdentity(
        modelLabel: String?,
        tierUsed: HelperModelTier?,
        origin: HelperModelOrigin?,
        modelGroupName: String? = null,
        modelGroupUnavailable: Boolean = false,
        resolvedEntryId: String? = null,
        resolvedProviderLabel: String? = null,
        resolvedProviderType: String? = null,
        resolvedModelId: String? = null,
        resolvedModelName: String? = null,
    ): JSONObject {
        // Named `putIfPresent`, NOT `put`: a local `fun put` inside an
        // extension on JSONObject shadows JSONObject.put, so the body's
        // `put(k, v)` resolved to itself — infinite recursion, caught by the
        // first test run as a StackOverflowError.
        fun putIfPresent(k: String, v: String?) { if (!v.isNullOrEmpty()) this.put(k, v) }
        putIfPresent("model_used", modelLabel)
        putIfPresent("tier_used", tierUsed?.wire)
        putIfPresent("model_origin", origin?.wire)
        putIfPresent("model_resolved_entry_id", resolvedEntryId)
        putIfPresent("model_resolved_provider", resolvedProviderLabel)
        putIfPresent("model_resolved_provider_type", resolvedProviderType)
        putIfPresent("model_resolved_id", resolvedModelId)
        putIfPresent("model_resolved_name", resolvedModelName)
        putIfPresent("model_group_name", modelGroupName)
        // Only when true, matching the rest of the envelope.
        if (modelGroupUnavailable) put("model_group_unavailable", true)
        return this
    }

    fun backgroundStartJson(
        jobId: String, childSessionId: String, modelLabel: String, tierUsed: HelperModelTier,
        minutes: Int, converted: Boolean,
        // [T-android-agent-model-identity] Nullable so the existing callers that
        // have no resolution to hand keep working unchanged.
        resolution: HelperModelResolution? = null,
    ): String = JSONObject()
        .put("ok", true)
        .put("status", "running")
        .put("job_id", jobId)
        .put("child_session_id", childSessionId)
        .putModelIdentity(
            modelLabel = modelLabel,
            tierUsed = tierUsed,
            origin = resolution?.origin,
            modelGroupName = resolution?.modelGroupName,
            modelGroupUnavailable = resolution?.modelGroupUnavailable ?: false,
            resolvedEntryId = resolution?.resolvedEntryId,
            resolvedProviderLabel = resolution?.resolvedProviderLabel,
            resolvedProviderType = resolution?.resolvedProviderType,
            resolvedModelId = resolution?.resolvedModelId,
            resolvedModelName = resolution?.resolvedModelName,
        )
        .put("budget_minutes", minutes)
        .put("converted_from_wait", converted)
        .put(
            "note",
            if (converted) "The user sent a new message while you were waiting, so this delegation was moved to the background. Answer the user now; the agent's result will arrive as a NEW MESSAGE in this conversation (prefixed [Background task finished …]) when it is done. Use agent_status if you need its current state."
            else "The agent is running in the background. Its result will arrive as a NEW MESSAGE in this conversation (prefixed [Background task finished …]) when it is done — end this turn when you have nothing else to do. Use agent_status to check on it or cancel it; do not poll in a loop.",
        )
        .toString()

    /** True for a `status: running` payload (background start / progress). */
    fun isRunningPayload(content: String): Boolean =
        content.trimStart().startsWith("{") && runCatching { JSONObject(content).optString("status") == "running" }.getOrDefault(false)

    /** `child_session_id` from a progress or result block, or null. */
    fun childSessionIdFrom(blockContent: String): String? =
        runCatching { JSONObject(blockContent).optString("child_session_id", "").ifEmpty { null } }.getOrNull()

    /** Map a terminal job state to the payload / callback status word. */
    fun statusWord(state: AgentJobState): String = when (state) {
        AgentJobState.DONE -> "completed"
        else -> state.wire
    }

    // ── Run summary (tools · turns · tokens) ─────────────────────────────

    /**
     * One line the parent model can read at a glance. [toolNames] are the
     * child's tool calls in order (from its assistant rows), [turns] its
     * assistant-message count, tokens from its persisted usage rows.
     */
    fun runSummaryLine(toolNames: List<String>, turns: Int, inputTokens: Long, outputTokens: Long, cacheRead: Long): String {
        val counts = LinkedHashMap<String, Int>()
        for (n in toolNames) counts[n] = (counts[n] ?: 0) + 1
        val tools = if (counts.isEmpty()) "none yet" else counts.entries.joinToString(", ") { "${it.key}×${it.value}" }
        fun k(n: Long) = if (n >= 1000) "%.1fk".format(n / 1000.0) else n.toString()
        var tokens = "in ${k(inputTokens)} / out ${k(outputTokens)}"
        if (cacheRead > 0) tokens += " (cache read ${k(cacheRead)})"
        return "Summary: tools $tools · turns $turns · tokens $tokens"
    }

    /** Tool names (in order), turn count and token totals from a child session's persisted rows. */
    data class ChildRunFacts(val toolNames: List<String>, val turns: Int, val input: Long, val output: Long, val cacheRead: Long, val lastText: String)

    fun childRunFacts(rows: List<com.openminis.app.data.db.MessageEntity>): ChildRunFacts {
        val tools = mutableListOf<String>()
        var turns = 0; var input = 0L; var output = 0L; var cache = 0L
        val assistantTexts = mutableListOf<String>()
        for (r in rows) {
            if (r.role != "assistant") continue
            turns++
            runCatching {
                val arr = JSONArray(r.partsJson)
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    // Per ELEMENT, not per row: this text is the child's
                    // deliverable, returned verbatim to the parent. One odd
                    // part used to abort the whole row, so the parent received
                    // the "no result" note instead of the answer the child had
                    // actually written.
                    val o = arr.optJSONObject(i) ?: continue
                    when (o.optString("type")) {
                        "toolUse" -> o.optJSONObject("value")?.optString("name")?.takeIf { it.isNotEmpty() }?.let { tools += it }
                        "text" -> { val t = o.optString("value", ""); if (t.isNotBlank()) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(t) } }
                    }
                }
                assistantTexts += sb.toString().trim()
            }
            r.tokenUsage?.let { tu ->
                runCatching {
                    val u = JSONObject(tu)
                    input += u.optLong("inputTokens", 0); output += u.optLong("outputTokens", 0); cache += u.optLong("cacheReadTokens", 0)
                }
            }
        }
        // [T-agent-wrapup-turn] The final text, or — when the run ended on a
        // tool call — the last thing the agent actually said, labelled.
        val last = assistantTexts.lastOrNull().orEmpty()
        val lastText = if (last.isNotEmpty()) last else assistantTexts.dropLast(1).lastOrNull { it.isNotEmpty() }
            ?.let { "(The agent did not write a final answer; this is its last message before the run ended.)\n$it" } ?: ""
        return ChildRunFacts(tools, turns, input, output, cache, lastText)
    }

    fun formatClock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
