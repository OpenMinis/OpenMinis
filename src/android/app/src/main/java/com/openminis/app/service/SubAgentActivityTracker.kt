package com.openminis.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import java.util.UUID

/**
 * Live roster of concurrent spawn_agent members for the chat status bar.
 * UI shows 子代理 i/N plus turn and the in-flight tool.
 *
 * A member exists only for as long as it is RUNNING: [finish] removes the
 * entry outright. There is deliberately no terminal state in the BAR — a
 * sticky SUCCESS/FAILED chip carried no information and read as "still
 * going". The completed run is NOT invisible, though: the session page hides
 * a sub-agent transcript card only while its `parentToolId` is in this roster,
 * so once [finish] drops the member the card reappears in the timeline and the
 * user can see what finished. See ChatFlatItems.buildFlatItems'
 * `activeSubAgentToolIds`.
 *
 * [T-event-bus] Every lifecycle transition here is mirrored to
 * [SubAgentEventBus], which keeps bounded per-session history — the roster
 * forgets finished runs, the bus remembers them (parent-session awareness,
 * lifecycle footers on sub-agent results, diagnostics).
 */
object SubAgentActivityTracker {

    data class Member(
        val id: String,
        val parentSessionId: String,
        val parentToolId: String,
        val title: String,
        val role: String?,
        val model: String?,
        val lastStep: String = "",
        val error: String? = null,
        val index: Int = 0,
        val total: Int = 0,
        val kind: String? = null,
        val turnIndex: Int = 0,
        val turnCap: Int = 0,
        val currentTool: String = "",
        val transcript: String = "",
        /** 思考中 / 回复中 / 调用工具 / 执行中. Empty until the runner reports one. */
        val phase: String = "",
        val steps: List<Step> = emptyList(),
    ) {
        val detailToolId: String get() = when {
            parentToolId.isBlank() -> ""
            total > 1 -> "$parentToolId#sub-$index"
            else -> parentToolId
        }
    }

    /**
     * One row in the live detail page. Same three kinds the session page
     * renders: thinking, assistant text, and a tool card.
     */
    data class Step(
        val id: String,
        val kind: String,
        val title: String = "",
        val body: String = "",
        val toolName: String = "",
        val toolArgs: String = "",
        val status: String = "",
        val startedAt: Long = 0L,
        val durationMs: Long = 0L,
    )

    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members.asStateFlow()
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val userStopped = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun start(
        parentSessionId: String,
        title: String,
        role: String?,
        model: String?,
        index: Int = 0,
        total: Int = 0,
        kind: String? = role,
        turnCap: Int = 0,
        parentToolId: String = "",
    ): String {
        val id = UUID.randomUUID().toString()
        val member = Member(
            id = id,
            parentSessionId = parentSessionId,
            parentToolId = parentToolId,
            title = title,
            role = role,
            model = model,
            index = index,
            total = total,
            kind = kind,
            turnCap = turnCap,
        )
        _members.update { it + member }
        SubAgentEventBus.publish(
            SubAgentEvent.Spawned(
                runId = id,
                parentSessionId = parentSessionId,
                parentToolId = parentToolId,
                title = title,
                role = role,
                kind = kind ?: role ?: "",
                index = index,
                total = total,
                model = model ?: "",
                turnCap = turnCap,
                atMs = System.currentTimeMillis(),
            ),
        )
        return id
    }

    fun attachJob(id: String, job: Job?) {
        if (job != null) jobs[id] = job
    }

    fun stop(id: String): Boolean {
        val job = jobs.remove(id) ?: return false
        userStopped += id
        job.cancel()
        memberById(id)?.let { m ->
            SubAgentEventBus.publish(
                SubAgentEvent.Stopped(id, m.parentSessionId, System.currentTimeMillis()),
            )
        }
        return true
    }

    fun wasUserStopped(id: String): Boolean = id in userStopped

    fun isParentToolActive(parentToolId: String): Boolean =
        parentToolId.isNotBlank() && _members.value.any { it.parentToolId == parentToolId }

    fun updateStep(id: String, step: String) {
        _members.update { list ->
            list.map { m -> if (m.id == id) m.copy(lastStep = step) else m }
        }
    }

    fun updateProgress(id: String, turn: Int, cap: Int, tool: String) {
        val step = buildString {
            append("turn $turn/$cap")
            if (tool.isNotBlank()) append(" · $tool")
        }
        _members.update { list ->
            list.map { m ->
                if (m.id == id) {
                    m.copy(
                        lastStep = step,
                        turnIndex = turn,
                        turnCap = cap,
                        currentTool = tool,
                    )
                } else {
                    m
                }
            }
        }
        if (turn > 0) {
            memberById(id)?.let { m ->
                SubAgentEventBus.publish(
                    SubAgentEvent.TurnStarted(id, m.parentSessionId, turn, cap, System.currentTimeMillis()),
                )
            }
        }
    }

    /**
     * Mark [id] finished and REMOVE it from the roster.
     *
     * The member used to be kept around with a SUCCESS/FAILED status, so
     * `members` never emptied and the live bar stayed on screen after the whole
     * run was over — including after every sub-agent had failed. The bar is a
     * LIVE indicator: a stuck-on chip reads as "still running" and there is
     * nothing left to tap into.
     *
     * The transcript is what survives, and it is already carried on the tool
     * result and folded into the process summary via [combinedTranscript]
     * before this point, so dropping the roster entry loses nothing.
     */
    /**
     * Drop [id] from the roster.
     *
     * [T-event-bus] [success] is no longer state-free: it feeds the Completed
     * event published to [SubAgentEventBus] BEFORE the roster entry
     * disappears, so parent sessions and diagnostics still see the outcome
     * afterwards. [error] stays call-site documentation only.
     */
    fun finish(id: String, success: Boolean, error: String? = null) {
        jobs.remove(id)
        userStopped.remove(id)
        var member: Member? = null
        _members.update { list ->
            member = list.find { it.id == id }
            list.filterNot { it.id == id }
        }
        val finished = member
        if (finished != null) {
            SubAgentEventBus.publish(
                SubAgentEvent.Completed(
                    runId = id,
                    parentSessionId = finished.parentSessionId,
                    success = success,
                    turnsUsed = finished.turnIndex,
                    toolCalls = finished.steps.count { it.kind == "tool" },
                    atMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun setPhase(id: String, phase: String, tool: String = "") {
        if (phase.isBlank() && tool.isBlank()) return
        mutate(id) { m ->
            val nextTool = when {
                tool.isNotBlank() -> tool
                phase == "思考中" || phase == "回复中" -> ""
                else -> m.currentTool
            }
            m.copy(
                phase = phase.ifBlank { m.phase },
                currentTool = nextTool,
                lastStep = if (nextTool.isBlank()) phase else "$phase · $nextTool",
            )
        }
    }

    fun appendStream(id: String, stepId: String, kind: String, delta: String) {
        if (delta.isEmpty() || stepId.isBlank()) return
        mutate(id) { m ->
            val steps = m.steps.toMutableList()
            val idx = steps.indexOfLast { it.id == stepId }
            if (idx >= 0) {
                val cur = steps[idx]
                steps[idx] = cur.copy(body = clip(cur.body + delta, 8_000), status = "streaming")
            } else {
                trim(steps)
                steps += Step(
                    id = stepId,
                    kind = kind,
                    title = if (kind == "thinking") "思考" else "",
                    body = clip(delta, 8_000),
                    status = "streaming",
                    startedAt = System.currentTimeMillis(),
                )
            }
            m.copy(steps = steps)
        }
    }

    fun beginTool(id: String, toolId: String, name: String) {
        if (toolId.isBlank()) return
        mutate(id) { m ->
            val steps = m.steps.toMutableList()
            if (steps.none { it.id == toolId }) {
                trim(steps)
                steps += Step(
                    id = toolId,
                    kind = "tool",
                    title = name,
                    toolName = name,
                    status = "streaming",
                    startedAt = System.currentTimeMillis(),
                )
            }
            m.copy(steps = steps, phase = "调用工具", currentTool = name, lastStep = "调用工具 · $name")
        }
    }

    fun updateToolArgs(id: String, toolId: String, name: String, args: String) {
        mutate(id) { m ->
            val steps = m.steps.toMutableList()
            val idx = steps.indexOfLast { it.id == toolId }
            val clipped = clip(args, 4_000)
            if (idx >= 0) {
                val cur = steps[idx]
                steps[idx] = cur.copy(
                    toolName = name.ifBlank { cur.toolName },
                    title = name.ifBlank { cur.title },
                    toolArgs = clipped,
                    status = if (cur.status == "running" || cur.status == "success" || cur.status == "failed") cur.status else "pending",
                )
            } else {
                trim(steps)
                steps += Step(
                    id = toolId,
                    kind = "tool",
                    title = name,
                    toolName = name,
                    toolArgs = clipped,
                    status = "pending",
                    startedAt = System.currentTimeMillis(),
                )
            }
            m.copy(steps = steps, phase = "调用工具", currentTool = name.ifBlank { m.currentTool })
        }
    }

    fun markToolRunning(id: String, toolId: String, name: String, args: String) {
        mutate(id) { m ->
            val steps = m.steps.toMutableList()
            val idx = steps.indexOfLast { it.id == toolId }
            if (idx >= 0) {
                val cur = steps[idx]
                steps[idx] = cur.copy(
                    toolName = name.ifBlank { cur.toolName },
                    title = name.ifBlank { cur.title },
                    toolArgs = args.ifBlank { cur.toolArgs },
                    status = "running",
                    startedAt = cur.startedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                )
            }
            m.copy(steps = steps, phase = "执行中", currentTool = name.ifBlank { m.currentTool }, lastStep = "执行中 · $name")
        }
        memberById(id)?.let { m ->
            SubAgentEventBus.publish(
                SubAgentEvent.ToolInvoked(id, m.parentSessionId, name, args.take(200), System.currentTimeMillis()),
            )
        }
    }

    fun finishTool(id: String, toolId: String, name: String, success: Boolean, output: String) {
        val startedAt = memberById(id)?.steps?.lastOrNull { it.id == toolId }?.startedAt ?: 0L
        mutate(id) { m ->
            val steps = m.steps.toMutableList()
            val idx = steps.indexOfLast { it.id == toolId }
            val now = System.currentTimeMillis()
            if (idx >= 0) {
                val cur = steps[idx]
                steps[idx] = cur.copy(
                    toolName = name.ifBlank { cur.toolName },
                    title = name.ifBlank { cur.title },
                    body = clip(output, 4_000),
                    status = if (success) "success" else "failed",
                    durationMs = if (cur.startedAt > 0) now - cur.startedAt else 0L,
                )
            }
            m.copy(steps = steps, phase = "思考中", currentTool = "", lastStep = "思考中")
        }
        memberById(id)?.let { m ->
            val durationMs = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0L
            SubAgentEventBus.publish(
                SubAgentEvent.ToolFinished(id, m.parentSessionId, name, success, durationMs, System.currentTimeMillis()),
            )
        }
    }

    private fun memberById(id: String): Member? = _members.value.find { it.id == id }

    private fun mutate(id: String, block: (Member) -> Member) {
        _members.update { list ->
            list.map { m -> if (m.id == id) block(m) else m }
        }
    }

    private fun trim(steps: MutableList<Step>) {
        while (steps.size >= 80) steps.removeAt(0)
    }

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.takeLast(max)

    fun appendLog(id: String, line: String) {
        if (line.isBlank()) return
        _members.update { list ->
            list.map { m ->
                if (m.id != id) m else {
                    val next = if (m.transcript.isEmpty()) line else m.transcript + "\n" + line
                    val clipped = if (next.length > 40_000) next.takeLast(32_000) else next
                    m.copy(transcript = clipped)
                }
            }
        }
    }

    fun combinedTranscript(parentSessionId: String): String {
        val list = membersFor(parentSessionId)
        if (list.isEmpty()) return ""
        if (list.size == 1) {
            val m = list.first()
            val body = m.transcript.ifBlank { m.lastStep }
            return if (m.error.isNullOrBlank()) body else body.trimEnd() + "\nerror: ${m.error}"
        }
        return list.joinToString("\n\n") { m ->
            buildString {
                append("## ")
                if (m.index > 0 && m.total > 0) append("子代理 ${m.index}/${m.total}")
                else append(m.title)
                m.kind?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                append(" · running")
                append('\n')
                val body = m.transcript.ifBlank { m.lastStep }
                if (body.isNotBlank()) append(body.trimEnd())
                m.error?.takeIf { it.isNotBlank() }?.let {
                    append("\nerror: ").append(it)
                }
            }
        }
    }

    fun clearSession(parentSessionId: String) {
        _members.value.filter { it.parentSessionId == parentSessionId }.forEach { id ->
            jobs.remove(id.id)?.cancel()
            userStopped.remove(id.id)
        }
        _members.update { list -> list.filter { it.parentSessionId != parentSessionId } }
    }

    fun membersFor(parentSessionId: String): List<Member> =
        _members.value.filter { it.parentSessionId == parentSessionId }
}
