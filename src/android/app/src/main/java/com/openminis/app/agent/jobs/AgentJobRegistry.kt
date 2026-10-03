package com.openminis.app.agent.jobs

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

// [T-p1-delegate-task] Component B of the sub-agent / scheduled-task design
// (subagent_design_v4 §8.1–8.2), Android port of iOS `AgentJobRegistry`.
//
// The in-process registry for every job that runs a prompt in a session on
// behalf of something that is not the user's own tap: a `delegate_task`
// helper today; `minis-scheduled --target child-of-current` and background
// helpers in P2.
//
// Deliberately SEPARATE from `ScheduledTaskStore`. That store is persisted
// (AlarmManager tasks must survive a process kill); this one is not, on
// purpose — a helper's whole life is inside its parent turn, and the parent
// message's tool block is the durable record of what happened. Folding both
// into one table would either strand ephemeral helper rows on disk or drop
// the persistence the alarms need. The two share only the trigger/target
// vocabulary and the CLI contract (§8.3).
//
// No `kind` field (§8.2): purpose is expressed by `trigger` + `target`, and
// "is this a helper" is `target is ChildOfCurrent`.
//
// Intentionally different from iOS: there is no loop-end NotificationCenter
// observer. iOS needs one because its headless runs finish on a detached vm
// and the only signal is a notification that is (bug, noted in the P0
// review) only posted for non-active sessions. On Android the runner holds
// the child ViewModel and awaits its `isStreaming` flow directly, so job
// closure is explicit and does not depend on which session is on screen.
//
// Threading: all mutation happens on the main thread (callers are the agent
// loop's Main hops, Compose, and the RPC layer's Main dispatch). Reads via the
// StateFlow are safe from anywhere.

enum class AgentJobOrigin { TOOL, CLI, SHORTCUT, SCHEDULED }

sealed class AgentJobTrigger {
    object Immediate : AgentJobTrigger()
    data class Once(val afterMs: Long) : AgentJobTrigger()
    data class Loop(val intervalMs: Long, val count: Int?) : AgentJobTrigger()
    data class OnCompletion(val ofJobId: String) : AgentJobTrigger()

    val logLabel: String
        get() = when (this) {
            Immediate -> "immediate"
            is Once -> "once(+${afterMs / 1000}s)"
            is Loop -> "loop(${intervalMs / 1000}s×${count ?: "∞"})"
            is OnCompletion -> "onCompletion(${ofJobId.take(8)})"
        }
}

sealed class AgentJobTarget {
    object New : AgentJobTarget()
    data class FollowUp(val sessionId: String) : AgentJobTarget()
    data class Rerun(val sessionId: String, val messageId: String) : AgentJobTarget()
    /** A hidden child session of [parentSessionId]. [parentToolUseId] is the
     *  `delegate_task` block that spawned it, or null for a CLI child. */
    data class ChildOfCurrent(val parentSessionId: String, val parentToolUseId: String?) : AgentJobTarget()

    val isChildOfCurrent: Boolean get() = this is ChildOfCurrent
    val parentSessionIdOrNull: String? get() = (this as? ChildOfCurrent)?.parentSessionId

    val logLabel: String
        get() = when (this) {
            New -> "new"
            is FollowUp -> "followUp(${sessionId.take(8)})"
            is Rerun -> "rerun(${sessionId.take(8)}/${messageId.take(8)})"
            is ChildOfCurrent -> "childOfCurrent(${parentSessionId.take(8)} tool=${parentToolUseId?.take(8) ?: "-"})"
        }
}

sealed class AgentJobThen {
    /** The producer awaits the run in-process (wait-mode helper). */
    object None : AgentJobThen()
    /** P2: inject a result message into the parent via the submit funnel. */
    data class FollowUpParent(val template: String?) : AgentJobThen()
}

enum class AgentJobState {
    PENDING, RUNNING, DONE, CANCELLED, FAILED,
    /** [T-p2-agent-series] The wall-clock budget elapsed and the run was cut short. */
    TIMEOUT;

    /** iOS AgentJobState.rawValue — the wire word used in callbacks / agent_status. */
    val wire: String get() = name.lowercase()
}

/** One registered job. Immutable snapshot; the registry replaces entries. */
data class AgentJob(
    val id: String,
    val label: String?,
    val title: String,
    val origin: AgentJobOrigin,
    val trigger: AgentJobTrigger,
    val target: AgentJobTarget,
    val prompt: String?,
    val then: AgentJobThen,
    val state: AgentJobState = AgentJobState.PENDING,
    val firedCount: Int = 0,
    val remaining: Int? = (trigger as? AgentJobTrigger.Loop)?.count,
    /** The session the run executes in once known. */
    val runSessionId: String? = null,
    /** Which model tier actually ran ("primary"/"sub"). */
    val tierUsed: String? = null,
    /**
     * [T-sub-agents-v1] Where this run's model came from — a
     * [HelperModelOrigin] wire value. This is what the callback's `model`
     * attribute carries, matching iOS; [tierUsed] is the pre-rename legacy
     * field and no longer reaches the model.
     */
    val modelOrigin: String? = null,
    /** [T-sub-agents-v1] The named sub agent. */
    val agentName: String? = null,
    /**
     * [T-android-subagent-prompt-parity] True when this run is a RESUME of a
     * child the app lost, rather than a fresh delegation.
     *
     * Reported to the parent model in the final payload: a resumed run's tool
     * context was destroyed at the interruption, and its elapsed/turn counts
     * cover only the resumed part. Without saying so, the parent reads those
     * numbers as if they described the whole task and trusts state (an open
     * page, a live process) that no longer exists. Mirrors iOS
     * `AgentJob.wasResumed`.
     */
    val wasResumed: Boolean = false,
    /** [T-p2-progress-report] none | frequent | moderate (background mode only). */
    val progressLevel: String = "none",
    /** Last "Summary: tools … · turns … · tokens …" line the runner computed. */
    val summaryLine: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val resultText: String? = null,
) {
    val isActive: Boolean get() = state == AgentJobState.RUNNING || state == AgentJobState.PENDING
    val elapsedMs: Long?
        get() = startedAtMs?.let { (finishedAtMs ?: System.currentTimeMillis()) - it }
    val logLabel: String
        get() = "job ${id.take(8)} '$title' origin=$origin trigger=${trigger.logLabel} target=${target.logLabel} state=$state"
}

/** Posted ONCE per [AgentJobRegistry.finish]. */
data class AgentJobCompletion(
    val jobId: String,
    val state: AgentJobState,
    /** `.OnCompletion` jobs this finish armed (empty when none). */
    val dependentJobIds: List<String>,
)

object AgentJobRegistry {
    private const val TAG = "AgentJobRegistry"

    /** Hard cap on concurrently running child-session jobs (design §4.3).
     *  Helpers bypass SessionConcurrencyManager — a parent awaiting its helper
     *  holds a slot there, so routing helpers through the same pool of 5 could
     *  deadlock every parent behind its own children (§4.4). */
    const val MAX_CONCURRENT_CHILD_JOBS = 3

    private val _jobs = MutableStateFlow<Map<String, AgentJob>>(emptyMap())
    val jobs: StateFlow<Map<String, AgentJob>> = _jobs.asStateFlow()

    private val _completions = MutableSharedFlow<AgentJobCompletion>(extraBufferCapacity = 16)
    val completions: SharedFlow<AgentJobCompletion> = _completions.asSharedFlow()

    /** Registered by the producer at [markRunning]: how to stop this job's run. */
    private val cancelHooks = HashMap<String, () -> Unit>()
    /** [T-p2-background-helper] Runs once when the job closes (any terminal
     *  state), BEFORE `then` is dispatched — the delegate_task runner uses it
     *  to write the final payload into the parent's tool block. */
    private val completionHooks = HashMap<String, (AgentJob) -> Unit>()

    /**
     * [T-p2-agent-series] How `then = FollowUpParent` reaches the parent
     * conversation: (parentSessionId, text, jobId) → submit as a PROGRAMMATIC
     * prompt (queued behind a running loop, never interrupting it). Installed
     * by MinisApp; the registry itself stays free of ViewModel imports.
     */
    @Volatile
    var followUpDispatcher: ((parentSessionId: String, text: String, jobId: String) -> Unit)? = null

    fun register(
        title: String,
        label: String? = null,
        origin: AgentJobOrigin,
        trigger: AgentJobTrigger,
        target: AgentJobTarget,
        prompt: String?,
        then: AgentJobThen? = null,
        /** [T-sub-agents-v1] The named sub agent; null for the built-in. */
        agentName: String? = null,
        /**
         * [T-sub-agents-resume] Claim a child session immediately.
         *
         * Normally set by markRunning, but a resume must own its child from
         * REGISTER time: anything that asks "is this child still lost?" in the
         * window before the run actually starts would otherwise start a second
         * one on the same session.
         */
        runSessionId: String? = null,
        /** [T-android-subagent-prompt-parity] See [AgentJob.wasResumed]. */
        wasResumed: Boolean = false,
    ): AgentJob {
        val resolvedThen = then ?: if (target.isChildOfCurrent) AgentJobThen.FollowUpParent(null) else AgentJobThen.None
        val job = AgentJob(
            id = UUID.randomUUID().toString(), label = label, title = title, origin = origin,
            trigger = trigger, target = target, prompt = prompt, then = resolvedThen,
            agentName = agentName, runSessionId = runSessionId, wasResumed = wasResumed,
        )
        _jobs.value = _jobs.value + (job.id to job)
        AppLogger.info(TAG, "REGISTER ${job.logLabel} then=$resolvedThen")
        return job
    }

    fun job(id: String): AgentJob? = _jobs.value[id]

    fun jobByLabel(label: String): AgentJob? = _jobs.value.values.firstOrNull { it.label == label && it.isActive }

    fun list(): List<AgentJob> = _jobs.value.values.sortedByDescending { it.createdAtMs }

    /** Active child-session jobs of [parentSessionId], oldest first — the capsule's data. */
    fun activeChildren(parentSessionId: String): List<AgentJob> =
        _jobs.value.values
            .filter { it.target.parentSessionIdOrNull == parentSessionId && it.isActive }
            .sortedBy { it.createdAtMs }

    fun jobForSession(runSessionId: String): AgentJob? =
        _jobs.value.values.firstOrNull { it.runSessionId == runSessionId }

    /** PENDING counts too: a job is reserved from `register` on, so the window
     *  between registering and `markRunning` (session creation, vm readiness)
     *  cannot be used to exceed the cap. */
    val runningChildJobCount: Int
        get() = _jobs.value.values.count { it.target.isChildOfCurrent && it.isActive }

    val canStartChildJob: Boolean get() = runningChildJobCount < MAX_CONCURRENT_CHILD_JOBS

    // ── Delegation queue  [T-sub-agents-queue] ──────────────────────────────

    /**
     * A delegation that could not start yet, holding the RAW tool arguments.
     *
     * Storing the args rather than a half-built job is what lets the re-entry
     * re-resolve the definition, the model group and the limits at the moment
     * it actually starts — a roster edit while the task sat in the queue is
     * then honoured instead of baked in at enqueue time.
     */
    data class QueuedDelegation(
        val parentSessionId: String,
        val argsJson: String,
        val toolUseId: String,
        val queuedAtMs: Long = System.currentTimeMillis(),
    )

    /** Backlog bound. Past this a delegation is refused, and says so plainly. */
    const val MAX_QUEUED_DELEGATIONS = 10

    private val queuedDelegations = mutableListOf<QueuedDelegation>()

    /**
     * [T-android-sidebar-subagent-running] Bumped whenever the queue changes.
     *
     * [queuedDelegations] is a plain synchronized list with no flow behind it,
     * so a delegation that is queued but not yet started would never reach a
     * Compose reader. This gives the derivation below something to observe;
     * the value is meaningless on its own.
     */
    private val queueRevision = MutableStateFlow(0)

    private val registryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * [T-android-sidebar-subagent-running] Parent sessions that have sub agent
     * work in flight, for the session list.
     *
     * A session whose own turn has ended but whose sub agents are still
     * working showed as idle in the sidebar, because that list only consults
     * the streaming tracker. The child's activity is recorded under the
     * CHILD's hidden session id, which no sidebar row carries — the row the
     * user is looking at goes dark while the work continues.
     *
     * Derived from the jobs StateFlow so the list recomposes on its own. Queued
     * delegations are deliberately included: from the user's side a delegation
     * waiting for a slot is still this conversation having work outstanding.
     */
    val parentsWithAgentWork: StateFlow<Set<String>> = MutableStateFlow(emptySet<String>()).also { flow ->
        // Recomputed synchronously on every job or queue change, rather than
        // derived with stateIn: a derived flow only publishes once its
        // collector runs, so `.value` lags its inputs — invisible in the UI,
        // but it makes the state untestable and the lag real on a slow frame.
        registryScope.launch {
            combine(_jobs, queueRevision) { jobs, _ -> jobs }.collect { flow.value = computeParentsWithAgentWork(it) }
        }
    }

    /** Pure, so the rule can be asserted directly. */
    private fun computeParentsWithAgentWork(jobs: Map<String, AgentJob>): Set<String> {
        val running = jobs.values.filter { it.isActive }.mapNotNull { it.target.parentSessionIdOrNull }
        val queued = synchronized(queuedDelegations) { queuedDelegations.map { it.parentSessionId } }
        return (running + queued).toSet()
    }

    /**
     * Synchronous read for a caller that must not wait for the flow to settle
     * (a unit test, or a one-shot check). The flow above is what Compose
     * observes; this answers the same question about the current instant.
     */
    fun hasAgentWork(parentSessionId: String): Boolean =
        parentSessionId in computeParentsWithAgentWork(_jobs.value)

    /**
     * Per-parent starters for queued delegations.
     *
     * Registered by each chat view model for its OWN session and removed when
     * that view model is cleared, so the registry can only ever start work into
     * a conversation that is still live. A single global starter would have had
     * to look a view model up by id and could resurrect one the store had
     * already released.
     */
    private val queuedStarters = java.util.concurrent.ConcurrentHashMap<String, (QueuedDelegation) -> Boolean>()

    fun registerQueuedStarter(parentSessionId: String, starter: (QueuedDelegation) -> Boolean) {
        if (parentSessionId.isEmpty()) return
        queuedStarters[parentSessionId] = starter
    }

    fun unregisterQueuedStarter(parentSessionId: String) {
        queuedStarters.remove(parentSessionId)
    }

    fun enqueueDelegation(item: QueuedDelegation): Boolean = synchronized(queuedDelegations) {
        if (queuedDelegations.size >= MAX_QUEUED_DELEGATIONS) return false
        queuedDelegations.add(item)
        queueRevision.value++
        AppLogger.info(
            TAG,
            "QUEUE + tool=${item.toolUseId.take(12)} depth=${queuedDelegations.size}",
        )
        true
    }

    fun queuedCount(parentSessionId: String): Int = synchronized(queuedDelegations) {
        queuedDelegations.count { it.parentSessionId == parentSessionId }
    }

    /** Whether this tool call is still waiting in the queue. */
    fun isQueued(toolUseId: String): Boolean = synchronized(queuedDelegations) {
        queuedDelegations.any { it.toolUseId == toolUseId }
    }

    /**
     * Drop a parent's whole backlog — the user stopped the turn, or the session
     * was deleted. Without this a queued task would start into a conversation
     * the user had already ended.
     */
    fun dropQueuedDelegations(parentSessionId: String, reason: String) {
        val removed = synchronized(queuedDelegations) {
            val hit = queuedDelegations.filter { it.parentSessionId == parentSessionId }
            queuedDelegations.removeAll(hit)
            queueRevision.value++
            hit.size
        }
        if (removed > 0) AppLogger.info(TAG, "QUEUE drop $removed for ${parentSessionId.take(8)} ($reason)")
    }

    /**
     * Start ONE queued delegation if a slot is free.
     *
     * One per call on purpose: the started task only occupies its slot once it
     * registers, so draining the whole backlog here would blow past the
     * concurrency cap. The next `finish` drains the next one.
     */
    private fun drainQueuedDelegations() {
        while (true) {
            if (!canStartChildJob) return
            val next = synchronized(queuedDelegations) {
                queuedDelegations.removeFirstOrNull()?.also { queueRevision.value++ }
            } ?: return
            val starter = queuedStarters[next.parentSessionId]
            if (starter == null) {
                // The parent conversation is not live (closed, or the process
                // was restarted). Nothing can start it, and the block is
                // surfaced as never-started on the next load rather than
                // sitting at "Queued" forever.
                AppLogger.info(
                    TAG,
                    "QUEUE parent ${next.parentSessionId.take(8)} not live — dropping ${next.toolUseId.take(12)}",
                )
                continue
            }
            val started = runCatching { starter(next) }.getOrElse { e ->
                AppLogger.warning(TAG, "QUEUE start failed for ${next.toolUseId.take(12)}: ${e.message}")
                false
            }
            AppLogger.info(TAG, "QUEUE - tool=${next.toolUseId.take(12)} started=$started")
            // A parent that no longer exists takes its queued work with it;
            // move on to the next rather than stalling the whole backlog.
            if (started) return
        }
    }

    /**
     * [T-sub-agents-sibling-status] One line about the OTHER sub agents of a
     * conversation, for the callback envelope. Null when this job is alone.
     *
     * [interruptedCount] is supplied by the caller because it cannot be counted
     * here: the registry is in memory, so a process kill leaves no job behind —
     * and that is exactly the case that most needs reporting, since nothing
     * else will ever mention those runs again. It comes from the parent's
     * TRANSCRIPT instead (a block still holding a "running" payload with no
     * live job behind it).
     */
    fun siblingSummary(
        parentSessionId: String,
        excludingJobId: String,
        interruptedCount: Int,
    ): String? {
        val siblings = _jobs.value.values.filter {
            it.id != excludingJobId &&
                it.target.parentSessionIdOrNull == parentSessionId
        }
        val running = siblings.count { it.state == AgentJobState.RUNNING }
        val pending = siblings.count { it.state == AgentJobState.PENDING }
        val queued = queuedCount(parentSessionId)
        val parts = mutableListOf<String>()
        if (running + pending > 0) parts.add("${running + pending} still running")
        if (queued > 0) parts.add("$queued queued")
        if (interruptedCount > 0) {
            parts.add(
                "$interruptedCount interrupted (the app was restarted; " +
                    "resume with action=resume, or leave them)",
            )
        }
        if (parts.isEmpty()) return null
        return "Other sub agents in this conversation: " + parts.joinToString(", ") + "."
    }

    /**
     * Drain when a slot may have freed WITHOUT passing through [finish].
     *
     * A job killed off-path, or a watcher that threw, frees a slot with nothing
     * left to notice it — and the queue then starves silently and permanently,
     * leaving cards that read "Queued" forever. Cheap enough to call often: it
     * returns immediately unless work is waiting AND a slot is free.
     */
    fun drainIfStalled() {
        val waiting = synchronized(queuedDelegations) { queuedDelegations.isNotEmpty() }
        if (!waiting || !canStartChildJob) return
        AppLogger.info(TAG, "QUEUE stalled with a free slot — draining")
        drainQueuedDelegations()
    }

    private fun update(jobId: String, f: (AgentJob) -> AgentJob): AgentJob? {
        val cur = _jobs.value[jobId] ?: return null
        val next = f(cur)
        _jobs.value = _jobs.value + (jobId to next)
        return next
    }

    fun setTierUsed(jobId: String, tier: String) { update(jobId) { it.copy(tierUsed = tier) } }

    /** [T-sub-agents-v1] Record where this run's model came from. */
    fun setModelOrigin(jobId: String, origin: String) { update(jobId) { it.copy(modelOrigin = origin) } }
    fun setProgressLevel(jobId: String, level: String) { update(jobId) { it.copy(progressLevel = level) } }
    fun setSummaryLine(jobId: String, line: String) { update(jobId) { it.copy(summaryLine = line) } }
    fun setCompletionHook(jobId: String, hook: (AgentJob) -> Unit) { completionHooks[jobId] = hook }
    /** A wait-mode helper converts to background when the user follows up. */
    fun setThen(jobId: String, then: AgentJobThen) { update(jobId) { it.copy(then = then) } }

    /** The producer has started the run in [sessionId]. [onCancel] stops it. */
    fun markRunning(jobId: String, sessionId: String?, onCancel: (() -> Unit)? = null) {
        val job = update(jobId) {
            it.copy(
                state = AgentJobState.RUNNING,
                startedAtMs = it.startedAtMs ?: System.currentTimeMillis(),
                firedCount = it.firedCount + 1,
                runSessionId = sessionId ?: it.runSessionId,
                remaining = it.remaining?.let { r -> maxOf(0, r - 1) },
            )
        } ?: return
        if (onCancel != null) cancelHooks[jobId] = onCancel
        AppLogger.info(TAG, "RUNNING ${job.logLabel} session=${sessionId?.take(8) ?: "nil"} fired=${job.firedCount}")
    }

    // ── Steering  [T-sub-agents-steer] ──────────────────────────────────────

    /**
     * Deliver a course correction to a running child. Returns false when the
     * child cannot take one (finished, or never started).
     */
    private val steerHooks = java.util.concurrent.ConcurrentHashMap<String, (String) -> Boolean>()

    /** Steers a job never got to read, reported on its result. */
    private val missedSteers = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()

    fun registerSteerHook(jobId: String, hook: (String) -> Boolean) { steerHooks[jobId] = hook }

    /**
     * Supplies whatever steers the child never got to read, asked once when the
     * job closes. Registered alongside the steer hook.
     */
    private val missedSteerDrains = java.util.concurrent.ConcurrentHashMap<String, () -> List<String>>()

    fun registerMissedSteerDrain(jobId: String, drain: () -> List<String>) { missedSteerDrains[jobId] = drain }

    /**
     * [T-android-browser-tab-ownership] Hand back the browser tabs an agent
     * owned when its job ends.
     *
     * Registered per job and fired from [finish], so it covers EVERY terminal
     * state — done, cancelled, timeout, failed — rather than only the happy
     * path. A leaked claim would keep a tab out of the chat's and its siblings'
     * reach for the life of the process, which is the same starvation the
     * ownership rules exist to prevent.
     */
    private val tabReleases = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    fun registerTabRelease(jobId: String, release: () -> Unit) { tabReleases[jobId] = release }

    fun steer(jobId: String, message: String): Boolean {
        val hook = steerHooks[jobId] ?: return false
        return runCatching { hook(message) }.getOrDefault(false)
    }

    /** Record steers the child never read, so the result can say so. */
    fun recordMissedSteers(jobId: String, messages: List<String>) {
        if (messages.isEmpty()) return
        missedSteers.getOrPut(jobId) { mutableListOf() }.addAll(messages)
    }

    fun missedSteersFor(jobId: String): List<String> = missedSteers[jobId].orEmpty().toList()

    /** Close a job. Idempotent; emits exactly one [AgentJobCompletion]. */
    fun finish(jobId: String, state: AgentJobState, result: String?) {
        val cur = _jobs.value[jobId] ?: return
        if (!cur.isActive) return
        val job = update(jobId) {
            it.copy(state = state, finishedAtMs = System.currentTimeMillis(), resultText = result)
        } ?: return
        cancelHooks.remove(jobId)
        steerHooks.remove(jobId)
        // [T-android-browser-tab-ownership] Release before the completion hook
        // so the slot is already back when the parent's next turn runs.
        tabReleases.remove(jobId)?.let { runCatching { it() } }
        // [T-sub-agents-steer] A correction that arrived after the child's last
        // turn is reported rather than silently dropped — the parent believes
        // it landed and would otherwise assume it was acted on.
        missedSteerDrains.remove(jobId)?.let { drain ->
            recordMissedSteers(jobId, runCatching { drain() }.getOrDefault(emptyList()))
        }
        AppLogger.info(TAG, "FINISH ${job.logLabel} elapsed=${(job.elapsedMs ?: 0) / 1000}s result=${result?.length ?: 0}ch")
        completionHooks.remove(jobId)?.let { hook -> runCatching { hook(_jobs.value[jobId] ?: job) } }
        // Every terminal state reports back — a parent that delegated in the
        // background must learn about a cancel / timeout / failure just as it
        // learns about success (the envelope carries `status`).
        runThen(_jobs.value[jobId] ?: job)
        val dependents = _jobs.value.values.filter { dep ->
            dep.state == AgentJobState.PENDING &&
                (dep.trigger as? AgentJobTrigger.OnCompletion)?.ofJobId == jobId
        }.map { it.id }
        if (dependents.isNotEmpty()) {
            AppLogger.info(TAG, "onCompletion dependents ${dependents.map { it.take(8) }} armed by ${jobId.take(8)} — producer runs them")
        }
        _completions.tryEmit(AgentJobCompletion(jobId, state, dependents))
        // [T-sub-agents-queue] A slot just freed.
        drainQueuedDelegations()
    }

    /** Cancel one job: runs its cancel hook (stops the child loop) then closes it. */
    fun cancel(jobId: String, reason: String) {
        val cur = _jobs.value[jobId] ?: return
        if (!cur.isActive) return
        AppLogger.info(TAG, "CANCEL ${cur.logLabel} reason=$reason")
        // [T-agent-port-round2] Close the job as CANCELLED BEFORE stopping the
        // child: the stop hook flips the child's isStreaming, which wakes the
        // runner's budget watcher on another dispatcher, and its finish(DONE)
        // raced this finish(CANCELLED) — first writer wins, so a user stop was
        // reported as "done" (device run 15:13). finish() is idempotent; with
        // the terminal state written first the watcher's write is a no-op.
        val hook = cancelHooks.remove(jobId)
        finish(jobId, AgentJobState.CANCELLED, null)
        hook?.let { runCatching { it() } }
    }

    fun cancelByLabel(label: String, reason: String) {
        _jobs.value.values.filter { it.label == label }.forEach { cancel(it.id, reason) }
    }

    /** Cascade: the parent session stopped or was deleted. */
    fun cancelAll(parentSessionId: String, reason: String) {
        activeChildren(parentSessionId).forEach { cancel(it.id, reason) }
    }

    // ── [T-android-stop-sibling-subagent] Stopping the batch ────────────

    /**
     * Stop the whole fan-out a child belongs to, not just that one child.
     *
     * A user who presses Stop on one sub agent card means "stop this work".
     * Cancelling the single job left its same-batch siblings running, so the
     * run kept spending tokens after the user believed it had stopped — the
     * card said stopped and the others simply were not shown.
     *
     * Order matters. [dropQueuedDelegations] runs FIRST because [finish]
     * calls [drainQueuedDelegations] on every terminal state: cancelling the
     * running jobs while a queue still holds entries frees a slot and starts
     * one of the very delegations being stopped. Draining the queue before
     * any cancel removes that window.
     */
    fun cancelSiblings(ofChildSessionId: String, reason: String) {
        val parent = jobForSession(ofChildSessionId)?.target?.parentSessionIdOrNull
        if (parent == null) {
            // No batch to reason about (a scheduled child-of-current run has
            // no delegating parent). Fall back to the single job so Stop still
            // stops what the user pressed it on.
            jobForSession(ofChildSessionId)?.let { if (it.isActive) cancel(it.id, reason) }
            return
        }
        muteDelegationResults(parent)
        dropQueuedDelegations(parent, reason)
        activeChildren(parent).forEach { cancel(it.id, reason) }
    }

    /**
     * Parent sessions whose delegation results must not drive the parent's
     * turn any further.
     *
     * A sibling that had ALREADY FINISHED before the user stopped the batch
     * still owns a pending callback, and delivering it restarts the parent's
     * turn — the conversation begins moving again seconds after the user
     * stopped it, which reads as the Stop button not working. The mute
     * suppresses only that parent-waking hop; the job's own result is still
     * recorded, so the transcript stays complete and auditable.
     */
    private val mutedParents = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun muteDelegationResults(parentSessionId: String) {
        mutedParents.add(parentSessionId)
        AppLogger.info(TAG, "delegation results muted for parent ${parentSessionId.take(8)}")
    }

    /** Cleared when the user starts new work in that session. */
    fun clearDelegationMute(parentSessionId: String) {
        if (mutedParents.remove(parentSessionId)) {
            AppLogger.info(TAG, "delegation mute cleared for parent ${parentSessionId.take(8)}")
        }
    }

    fun isDelegationMuted(parentSessionId: String): Boolean = mutedParents.contains(parentSessionId)

    /** Drop terminal jobs so the map does not grow without bound. */
    fun pruneFinished(olderThanMs: Long = 3_600_000L) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        _jobs.value = _jobs.value.filterValues { it.isActive || (it.finishedAtMs ?: it.createdAtMs) >= cutoff }
    }

    /** Test hook. */
    internal fun resetForTest() {
        _jobs.value = emptyMap(); cancelHooks.clear(); completionHooks.clear()
        followUpDispatcher = null; mutedParents.clear()
    }

    // ── then ────────────────────────────────────────────────────────────

    private fun runThen(job: AgentJob) {
        val then = job.then as? AgentJobThen.FollowUpParent ?: return
        val parent = job.target.parentSessionIdOrNull ?: return
        // [T-android-stop-sibling-subagent] The user stopped this batch. The
        // job still finishes and still records its result; what it must not do
        // is hand that result to the parent, which would start a new turn in a
        // conversation the user just stopped.
        if (isDelegationMuted(parent)) {
            AppLogger.info(TAG, "then.FollowUpParent ${job.id.take(8)} suppressed — parent ${parent.take(8)} stopped")
            return
        }
        // [T-agent-wrapup-turn] Never hand the parent an empty result: say
        // what happened so it re-delegates instead of digging.
        val result = job.resultText?.takeIf { it.isNotBlank() } ?: HelperRunner.emptyResultNote(job.state.wire)
        val text = then.template?.takeIf { it.isNotEmpty() }?.replace("{{result}}", result)
            ?: completionCallback(job, result).xml
        val dispatcher = followUpDispatcher
        if (dispatcher == null) {
            AppLogger.warning(TAG, "then.FollowUpParent ${job.id.take(8)} dropped — no dispatcher installed")
            return
        }
        AppLogger.info(TAG, "then.FollowUpParent ${job.id.take(8)} → parent ${parent.take(8)} state=${job.state.wire}")
        runCatching { dispatcher(parent, text, job.id) }
            .onFailure { AppLogger.warning(TAG, "then.FollowUpParent ${job.id.take(8)} failed: ${it.message}") }
    }

    /** The finished-envelope for [job] — iOS AgentJobRegistry.completionCallback. */
    /**
     * [T-sub-agents-sibling-status] Counts a parent's interrupted sub agents
     * from its TRANSCRIPT.
     *
     * Installed by the app because the registry has no database access — and it
     * must not be answered from the registry anyway: a process kill leaves no
     * job behind, which is precisely the state that needs reporting.
     */
    private val interruptedCounters = java.util.concurrent.ConcurrentHashMap<String, () -> Int>()

    fun registerInterruptedCounter(parentSessionId: String, counter: () -> Int) {
        if (parentSessionId.isEmpty()) return
        interruptedCounters[parentSessionId] = counter
    }

    fun unregisterInterruptedCounter(parentSessionId: String) {
        interruptedCounters.remove(parentSessionId)
    }

    private fun siblingsFor(job: AgentJob): String? {
        val parent = job.target.parentSessionIdOrNull ?: return null
        // With no live view model there is no transcript to count and no
        // conversation to report into, so zero is the honest answer.
        val interrupted = runCatching { interruptedCounters[parent]?.invoke() ?: 0 }.getOrDefault(0)
        return siblingSummary(parent, job.id, interrupted)
    }

    fun completionCallback(job: AgentJob, result: String): AgentCallback = AgentCallback(
        kind = AgentCallback.Kind.FINISHED,
        jobId = job.id,
        childSessionId = job.runSessionId,
        title = job.title,
        status = job.state.wire,
        // [T-sub-agents-v1] The origin, not the legacy tier: on iOS this
        // attribute answers "where did this model come from", and the two
        // platforms must not give the same attribute different meanings.
        tier = job.modelOrigin ?: job.tierUsed,
        elapsed = elapsedClock(job.elapsedMs),
        agentName = job.agentName,
        summary = job.summaryLine?.removePrefix("Summary: "),
        siblings = siblingsFor(job),
        body = result,
    )

    /** "1m02s" / "45s". */
    fun elapsedClock(ms: Long?): String? {
        val s = (ms ?: return null) / 1000
        return if (s >= 60) "${s / 60}m${"%02d".format(s % 60)}s" else "${s}s"
    }
}
