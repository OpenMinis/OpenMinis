package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.data.repository.MultiAgentSettings
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.PlanDiscussionOrchestrator
import com.openminis.app.tools.SubAgentLane
import com.openminis.app.tools.SubAgentRunner
import com.openminis.app.tools.SubAgentTokenBudget
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.SubAgentKind
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Per-retry backoff (seconds). Index 0 = wait before attempt 2, etc. */
// [T-subagent-429-selfheal] The ladder used to be {2, 5} — sized for a
// transient stream blip, not a provider rate-limit window, which is typically
// 60s. With three attempts the whole retry budget was spent in ten seconds, so
// a 429 during a concurrent wave still surfaced as a dead lane.
private val SUBAGENT_BACKOFF_S = intArrayOf(2, 5, 15, 30)

/** Ceiling on one backoff wait, even when Retry-After asks for longer. */
private const val MAX_BACKOFF_S = 60

/**
 * Stagger between lanes of the same wave. Fanning out N requests at the same
 * millisecond against one provider is the cheapest way to manufacture the 429
 * that then kills a lane; a few hundred ms of spread costs nothing.
 */
private const val SPAWN_STAGGER_MS = 400L

/**
 * Backoff for [attempt] (1-based, the attempt that just failed), never below
 * the provider's own Retry-After and never above [MAX_BACKOFF_S].
 */
internal fun backoffSeconds(attempt: Int, retryAfterSeconds: Int): Int {
    val ladder = SUBAGENT_BACKOFF_S[(attempt - 1).coerceIn(0, SUBAGENT_BACKOFF_S.size - 1)]
    return maxOf(ladder, retryAfterSeconds).coerceAtMost(MAX_BACKOFF_S)
}

private fun parseThinkingLevel(raw: String?, max: ThinkingLevel): ThinkingLevel {
    val requested = raw?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { ThinkingLevel.valueOf(it.uppercase()) }.getOrNull() }
        ?: max
    return if (requested.rank <= max.rank) requested else max
}

private suspend fun publishSubAgentUi(trackerId: String, event: com.openminis.app.tools.SubAgentRunner.UiEvent) {
    val tracker = com.openminis.app.service.SubAgentActivityTracker
    when (event) {
        is com.openminis.app.tools.SubAgentRunner.UiEvent.Phase ->
            tracker.setPhase(trackerId, event.label, event.tool)
        is com.openminis.app.tools.SubAgentRunner.UiEvent.Thinking ->
            tracker.appendStream(trackerId, event.stepId, "thinking", event.delta)
        is com.openminis.app.tools.SubAgentRunner.UiEvent.Text ->
            tracker.appendStream(trackerId, event.stepId, "text", event.delta)
        is com.openminis.app.tools.SubAgentRunner.UiEvent.ToolStart ->
            tracker.beginTool(trackerId, event.id, event.name)
        is com.openminis.app.tools.SubAgentRunner.UiEvent.ToolArgs ->
            tracker.updateToolArgs(trackerId, event.id, event.name, event.args)
        is com.openminis.app.tools.SubAgentRunner.UiEvent.ToolRunning ->
            tracker.markToolRunning(trackerId, event.id, event.name, event.args)
        is com.openminis.app.tools.SubAgentRunner.UiEvent.ToolDone ->
            tracker.finishTool(trackerId, event.id, event.name, event.success, event.output)
    }
}

internal suspend fun ChatViewModel.runPlanDiscussion(provider: LLMProvider): String {
        val assistantId = java.util.UUID.randomUUID().toString()
        withContext(Dispatchers.Main) {
            _messages.value = _messages.value + ChatMessage(
                id = assistantId,
                role = "assistant",
                content = "",
                isStreaming = true,
                isAwaitingModelResponse = true,
            )
        }
        val userText = _messages.value.lastOrNull { it.role == "user" && !it.isQueued }?.content.orEmpty()
        val excerpt = _messages.value.takeLast(16).joinToString("\n") {
            "${it.role}: ${it.content.take(500)}"
        }
        val config = providerRepository.config.value
        val mainEntry = _activeEntryId.value?.let { id -> config.modelEntries.find { it.id == id } }
        val mainName = mainEntry?.model?.displayName ?: currentModel?.displayName ?: "main"
        val mainMax = (mainEntry?.model?.maxOutputTokens ?: currentModel?.maxOutputTokens ?: 4096).coerceIn(256, 8192)
        val mainMember = PlanDiscussionOrchestrator.Member(
            displayName = mainName,
            stance = "facilitator",
            provider = provider,
            maxTokens = mainMax,
            temperature = mainEntry?.overrides?.temperature,
            thinkingLevel = mainEntry?.effectiveMaxThinkingLevel ?: ThinkingLevel.ULTRA,
        )
        val stances = listOf("architect", "skeptic", "implementer", "operator")
        val pool = MultiAgentSettings.retainLive(
            multiAgentSettings.selectedModelEntryIds.value,
            config.modelEntries.map { it.id }.toSet(),
            multiAgentSettings.maxConcurrent.value,
        )
        val members = mutableListOf<PlanDiscussionOrchestrator.Member>()
        if (pool.isNotEmpty()) {
            pool.forEachIndexed { i, id ->
                val entry = config.modelEntries.find { it.id == id } ?: return@forEachIndexed
                val p = providerForModelEntry(entry) ?: return@forEachIndexed
                members += PlanDiscussionOrchestrator.Member(
                    displayName = entry.model.displayName,
                    stance = stances[i % stances.size],
                    provider = p,
                    maxTokens = (entry.model.maxOutputTokens ?: 4096).coerceIn(256, 8192),
                    temperature = entry.overrides.temperature,
                    thinkingLevel = parseThinkingLevel(
                        multiAgentSettings.slotThinkingLevel(i),
                        entry.effectiveMaxThinkingLevel,
                    ),
                )
            }
        }
        if (members.isEmpty()) {
            members += mainMember.copy(stance = "architect")
            members += mainMember.copy(displayName = "$mainName · critic", stance = "skeptic")
            members += mainMember.copy(displayName = "$mainName · implementer", stance = "implementer")
        }
        val tools = AgentTools.makeAgentTools(
            supportsImageInput = currentModel?.hasImageInput == true,
            visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
                providerRepository, context,
            ),
            memoryEnabled = false,
            subAgentEnabled = false,
        )
        val result = try {
            PlanDiscussionOrchestrator.run(
                userText = userText,
                conversationExcerpt = excerpt,
                main = mainMember,
                members = members,
                tools = tools,
                executeTool = { name, json ->
                    // [T-subagent-browser-readonly-actions] Discussion seats
                    // are PLAN-filtered, so browser_use reaches them — but the
                    // Runner loop (with the read-only browser gate) is not in
                    // this path. Deny mutating actions at the seat boundary.
                    val browserDenial = if (name == "browser_use") {
                        com.openminis.app.tools.SubAgentKind.readOnlyBrowserDenial(
                            runCatching { org.json.JSONObject(json).optString("action") }.getOrDefault(""),
                        )
                    } else {
                        null
                    }
                    if (browserDenial != null) {
                        com.openminis.app.tools.ToolExecutionResult(browserDenial, false)
                    } else {
                        executeTool(name, json, "", mutableListOf(), assistantId, "")
                    }
                },
                onProgress = { msg ->
                    withContext(Dispatchers.Main) {
                        val overlay = msg.lineSequence()
                            .firstOrNull { it.startsWith("**状态：**") }
                            ?.removePrefix("**状态：**")
                            ?.trim()
                            ?: "计划讨论"
                        SessionActivityTracker.updateToolStatus(overlay, "plan_discussion", true, overlay)
                        _messages.value = _messages.value.map {
                            if (it.id == assistantId) {
                                it.copy(content = msg, isAwaitingModelResponse = true, isStreaming = true)
                            } else it
                        }
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = "计划讨论失败: ${e.message ?: e.javaClass.simpleName}"
            withContext(Dispatchers.Main) {
                _messages.value = _messages.value.map {
                    if (it.id == assistantId) {
                        it.copy(content = err, isStreaming = false, isAwaitingModelResponse = false)
                    } else it
                }
            }
            return ""
        }
        val persistedId = persistAssistantTurnForRun(
            checkNotNull(com.openminis.app.service.ActiveRunContext.current()) {
                "Plan discussion has no persistence owner"
            },
            listOf(AgentContentPart.Text(result.markdown)),
            usage = null,
            assistantText = result.markdown,
            uiMessageId = assistantId,
        ) ?: throw CancellationException("Plan discussion was stopped before persistence")
        withContext(Dispatchers.Main) {
            SessionActivityTracker.updateToolStatus("", null, false)
            _messages.value = _messages.value.map {
                if (it.id == assistantId) it.copy(
                    id = persistedId,
                    content = result.markdown,
                    isStreaming = false,
                    isAwaitingModelResponse = false,
                ) else it
            }
        }
        return result.contract.ifBlank { result.markdown }
    }

internal suspend fun ChatViewModel.publishRunSubagentLog(
        toolId: String,
        assistantId: String,
        currentText: String,
        toolBlocks: MutableList<AssistantBlock>?,
        log: String,
    ) {
        if (toolId.isEmpty() || assistantId.isEmpty() || toolBlocks == null) return
        synchronized(toolBlocks) {
            val i = toolBlocks.indexOfFirst { it.id == toolId }
            if (i >= 0) {
                toolBlocks[i] = toolBlocks[i].copy(content = log)
            }
        }
        withContext(Dispatchers.Main) {
            updateAssistantMessage(assistantId, currentText, true, toolBlocks.toList())
        }
    }

internal fun subAgentCardId(parentToolId: String, index: Int): String = "$parentToolId#sub-$index"

internal fun currentSubAgentLine(spawn: ChatSubAgentSpawn, index: Int, total: Int, step: String): String =
    buildString {
        val role = spawn.role?.takeIf { it.isNotBlank() }
        if (role != null) append("角色 ").append(role).append(" · ")
        append("类型 ").append(spawn.kind)
        append(" · ").append(index).append('/').append(total)
        if (step.isNotBlank()) append('\n').append(step)
    }

/**
 * One card per sub-agent. A batch shares the parent tool id, so each member
 * gets `$toolId#sub-$index`. A single spawn keeps the parent card and must
 * not publish every live sibling's transcript into it.
 */
internal fun ChatViewModel.subAgentChipsEnabled(): Boolean =
    context.getSharedPreferences(
        com.openminis.app.ui.settings.PREF_APPEARANCE,
        android.content.Context.MODE_PRIVATE,
    ).getBoolean(com.openminis.app.ui.settings.KEY_SHOW_SUBAGENT_BAR, true)

internal suspend fun ChatViewModel.publishSubAgentCard(
    parentToolId: String,
    cardIndex: Int?,
    title: String,
    assistantId: String,
    currentText: String,
    toolBlocks: MutableList<AssistantBlock>?,
    log: String,
    status: ToolBlockStatus = ToolBlockStatus.RUNNING,
) {
    // Chip mode owns the sub-agent UI. Do not also inject transcript cards.
    if (subAgentChipsEnabled()) return
    if (parentToolId.isEmpty() || assistantId.isEmpty() || toolBlocks == null) return
    val cardId = if (cardIndex != null) subAgentCardId(parentToolId, cardIndex) else parentToolId
    synchronized(toolBlocks) {
        val i = toolBlocks.indexOfFirst { it.id == cardId }
        if (i >= 0) {
            val existing = toolBlocks[i]
            toolBlocks[i] = existing.copy(
                content = log,
                toolTitle = title.ifBlank { existing.toolTitle },
                toolStatus = if (cardIndex == null) existing.toolStatus else status,
            )
        } else if (cardIndex != null) {
            val parent = toolBlocks.indexOfFirst { it.id == parentToolId }
            var at = if (parent >= 0) parent + 1 else toolBlocks.size
            val prefix = "$parentToolId#sub-"
            while (at < toolBlocks.size && toolBlocks[at].id.startsWith(prefix)) at++
            toolBlocks.add(
                at,
                AssistantBlock(
                    id = cardId,
                    kind = "tool_use",
                    content = log,
                    toolStatus = status,
                    toolTitle = title,
                    toolName = "spawn_agent",
                    startTimeMs = System.currentTimeMillis(),
                ),
            )
        }
    }
    withContext(Dispatchers.Main) {
        updateAssistantMessage(assistantId, currentText, true, toolBlocks.toList())
    }
}

internal suspend fun ChatViewModel.executeRunSubAgent(
        argsJson: String,
        toolId: String = "",
        toolBlocks: MutableList<AssistantBlock>? = null,
        assistantId: String = "",
        currentText: String = "",
        limiter: Semaphore? = null,
        parallelWriters: Int = 1,
        waveIndex: Int = 0,
        waveSize: Int = 1,
        /**
         * [T-subagent-background] Detach the wave: hand the model a dispatch id
         * immediately and let the lanes run on [viewModelScope] while the parent
         * loop keeps thinking. Results are collected later with `check_agent`.
         */
        background: Boolean = false,
    ): ToolExecutionResult {
        if (!multiAgentSettings.enabled.value) {
            return ToolExecutionResult("Multi-agent dispatch is disabled in Settings → Multi-agent.", false)
        }
        // [T-android-subagent-depth-leak] The authoritative "am I a sub-agent?"
        // test is the lane marker carried in the coroutine context: it is scoped
        // to the coroutine, so it cannot leak, and it repairs itself by
        // construction. The depth counter is kept only as a stale-state
        // detector - an interrupted dispatch used to leave it above zero for the
        // rest of the session, and gating on it turned one stopped dispatch into
        // a session that could never dispatch again until the app was killed.
        if (kotlin.coroutines.coroutineContext[SubAgentLane] != null) {
            return ToolExecutionResult("Error: sub-agents cannot spawn further sub-agents.", false)
        }
        if (subAgentDepth.get() > 0) {
            Log.w(
                ChatViewModel.TAG,
                "sub-agent depth=${subAgentDepth.get()} with no lane marker - resetting stale counter",
            )
            subAgentDepth.set(0)
        }
        val spawns = parseSubAgentBatch(argsJson, com.openminis.app.data.ToolLimitPrefs.subagentMaxTurns())
        if (spawns.isEmpty()) {
            return ToolExecutionResult(
                if (argsJson.isBlank() || !argsJson.trim().startsWith("{"))
                    "Error: invalid spawn_agent arguments"
                else
                    "Error: spawn_agent requires a non-empty tasks array or prompt",
                false,
            )
        }
        // [T-subagent-background] Detach when the caller says so, or when the
        // model asked for it in the arguments. Providers disagree on how a
        // boolean arrives — a real JSON bool from OpenAI-shaped ones, the string
        // "true" from others — so accept both spellings rather than silently
        // ignoring half of them.
        val detached = background || runCatching {
            org.json.JSONObject(argsJson).let { o ->
                o.optBoolean("background", false) ||
                    o.optString("background", "").equals("true", ignoreCase = true)
            }
        }.getOrDefault(false)
        val writersHere = spawns.count { SubAgentKind.canWrite(it.kind) }
        val writerTotal = maxOf(parallelWriters, writersHere)
        val sem = limiter ?: Semaphore(
            MultiAgentSettings.clampConcurrent(multiAgentSettings.maxConcurrent.value),
        )
        // Shared token pool per dispatch (Codex rollout_budget), OPT-IN only:
        // when the model passes token_budget the strictest requested cap
        // governs the whole wave; when it omits the field there is NO budget —
        // lanes run to their turn budgets. A silent default cap would cut
        // long-running workers off mid-task with a "partial report", which the
        // user experiences as lost work.
        val requestedCap = spawns.mapNotNull { it.tokenBudget }.minOrNull()
        val sharedBudget = requestedCap?.let { SubAgentTokenBudget(SubAgentTokenBudget.clamp(it)) }
        // [T-subagent-plan-autolog] A wave used to leave no durable trace unless
        // the model happened to call agent_plan first — so an interrupted
        // dispatch lost both the task list and how far each lane got. File the
        // board here instead of relying on the model to remember. A failed
        // add() must not abort the dispatch: runCatching, board stays
        // best-effort, the wave is the deliverable.
        val ownerSession = realSessionId.ifBlank { sessionId }.ifBlank { activeSessionId }
        val autoPlan = spawns.size > 1 || detached
        val planIds: List<String?> = if (autoPlan) {
            spawns.mapIndexed { i, s ->
                runCatching {
                    com.openminis.app.tools.AgentPlanStore.add(
                        ownerSession,
                        context,
                        title = "子代理 ${i + 1}/${spawns.size} · ${s.title.ifBlank { s.kind }}",
                        description = s.prompt.trim().take(200),
                        status = "active",
                    ).id
                }.getOrNull()
            }
        } else {
            spawns.map { null }
        }
        val batch = com.openminis.app.tools.SubAgentBatchRegistry.begin(
            sessionId = ownerSession,
            context = context,
            toolId = toolId,
            assistantId = assistantId,
            background = detached,
            seeds = spawns.mapIndexed { i, s ->
                com.openminis.app.tools.SubAgentBatchRegistry.LaneSeed(
                    index = i + 1,
                    kind = s.kind,
                    title = s.title.ifBlank { s.kind },
                    prompt = s.prompt,
                    planId = planIds[i],
                )
            },
        )
        if (detached) {
            return launchDetachedSubAgentBatch(
                batch = batch,
                ownerSession = ownerSession,
                spawns = spawns,
                toolId = toolId,
                writerTotal = writerTotal,
                sem = sem,
                sharedBudget = sharedBudget,
            )
        }
        if (spawns.size == 1) {
            val total = waveSize.coerceAtLeast(1)
            val index = if (total > 1) waveIndex + 1 else 1
            return try {
                supervisorScope {
                    try {
                        sem.withPermit {
                            runOneSubAgent(
                                spawn = spawns[0],
                                toolId = toolId,
                                toolBlocks = toolBlocks,
                                assistantId = assistantId,
                                currentText = currentText,
                                parallelWriters = writerTotal,
                                index = index,
                                total = total,
                                cardIndex = null,
                                tokenBudget = sharedBudget,
                                batchId = batch.id,
                                batchSession = ownerSession,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        ToolExecutionResult(
                            "Sub-agent failed: ${t.javaClass.simpleName}: ${t.message}",
                            false,
                        )
                    }
                }
            } finally {
                multiAgentSettings.clearSlotThinkingLevels()
            }
        }
        val results = try {
            supervisorScope {
                spawns.mapIndexed { i, spawn ->
                    async {
                        try {
                            sem.withPermit {
                                runOneSubAgent(
                                    spawn = spawn,
                                    toolId = toolId,
                                    toolBlocks = toolBlocks,
                                    assistantId = assistantId,
                                    currentText = currentText,
                                    parallelWriters = writerTotal,
                                    index = i + 1,
                                    total = spawns.size,
                                    cardIndex = i + 1,
                                    tokenBudget = sharedBudget,
                                    batchId = batch.id,
                                    batchSession = ownerSession,
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            ToolExecutionResult(
                                "Sub-agent ${i + 1} failed: ${t.javaClass.simpleName}: ${t.message}",
                                false,
                            )
                        }
                    }
                }.awaitAll()
            }
        } finally {
            multiAgentSettings.clearSlotThinkingLevels()
        }
        val ok = results.count { it.success }
        val body = buildString {
            append("Dispatched ${results.size} sub-agents: $ok ok, ${results.size - ok} failed.\n")
            results.forEachIndexed { i, r ->
                append("\n## 子代理 ${i + 1}/${results.size} (${spawns[i].kind}, ")
                append(if (r.success) "ok" else "fail")
                append(")\n")
                append(r.output.trim())
                append('\n')
            }
        }
        publishRunSubagentLog(
            toolId,
            assistantId,
            currentText,
            toolBlocks,
            "Dispatched ${results.size} sub-agents: $ok ok, ${results.size - ok} failed.",
        )
        return ToolExecutionResult(
            output = body,
            success = results.any { it.success },
            toolTitle = "子代理 ${spawns.size}",
        )
    }

/**
 * [T-subagent-background] Run a wave detached from the calling tool.
 *
 * The parent loop gets a dispatch id back immediately and keeps thinking; the
 * lanes run on viewModelScope, which outlives this tool call and the current
 * agent turn. Progress stays visible through the sub-agent chip bar
 * (SubAgentActivityTracker) and queryable with `check_agent`; the registry also
 * persists lane state, so a wave cut short by process death comes back as
 * `stopped` instead of disappearing without a trace.
 *
 * Deliberately no parent tool block: by the time a detached lane reports, the
 * main transcript has moved on, and writing into a stale MutableList would
 * corrupt a block that is already persisted.
 */
private fun ChatViewModel.launchDetachedSubAgentBatch(
        batch: com.openminis.app.tools.SubAgentBatchRegistry.Batch,
        ownerSession: String,
        spawns: List<ChatSubAgentSpawn>,
        toolId: String,
        writerTotal: Int,
        sem: Semaphore,
        sharedBudget: SubAgentTokenBudget?,
    ): ToolExecutionResult {
        viewModelScope.launch(SupervisorJob() + Dispatchers.Default) {
            try {
                supervisorScope {
                    spawns.mapIndexed { i, spawn ->
                        async {
                            try {
                                sem.withPermit {
                                    runOneSubAgent(
                                        spawn = spawn,
                                        toolId = toolId,
                                        toolBlocks = null,
                                        assistantId = "",
                                        currentText = "",
                                        parallelWriters = writerTotal,
                                        index = i + 1,
                                        total = spawns.size,
                                        cardIndex = null,
                                        tokenBudget = sharedBudget,
                                        batchId = batch.id,
                                        batchSession = ownerSession,
                                    )
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                // One dead lane must not cancel its siblings.
                                Log.w(
                                    ChatViewModel.TAG,
                                    "background lane ${i + 1}/${spawns.size} failed: ${t.message}",
                                )
                                ToolExecutionResult(
                                    "Sub-agent ${i + 1} failed: ${t.javaClass.simpleName}: ${t.message}",
                                    false,
                                )
                            }
                        }
                    }.awaitAll()
                }
            } catch (e: CancellationException) {
                Log.i(ChatViewModel.TAG, "background dispatch ${batch.id.take(8)} cancelled")
            } finally {
                multiAgentSettings.clearSlotThinkingLevels()
            }
        }
        val summary = spawns.mapIndexed { i, s ->
            "  ${i + 1}. [${s.kind}] ${s.title.ifBlank { s.prompt.trim().take(60) }}"
        }.joinToString("\n")
        return ToolExecutionResult(
            output = "Dispatched ${spawns.size} sub-agent(s) in the BACKGROUND — dispatch_id=${batch.id.take(8)}.\n" +
                "$summary\n\n" +
                "They are running now; this call did NOT wait for them. Continue with other work, then " +
                "call check_agent with dispatch_id=${batch.id.take(8)} — op=status for a snapshot, " +
                "op=await to block until they finish, op=collect for their reports. Each lane is also " +
                "filed on the agent_plan board, so progress survives an interruption.",
            success = true,
            toolTitle = "后台子代理 ${spawns.size}",
        )
    }

/**
 * [T-subagent-plan-autolog] Bookkeeping shell around the lane body. Whatever the
 * inner loop hands back — a report, a budget-exhaustion partial, a final
 * failure — the registry and the plan board learn about it here, so a lane
 * cannot finish without its progress being recorded. Cancellation is recorded
 * as `stopped` and then rethrown.
 */
private suspend fun ChatViewModel.runOneSubAgent(
        spawn: ChatSubAgentSpawn,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>?,
        assistantId: String,
        currentText: String,
        parallelWriters: Int,
        cardIndex: Int? = null,
        index: Int,
        total: Int,
        tokenBudget: SubAgentTokenBudget? = null,
        batchId: String? = null,
        batchSession: String = "",
    ): ToolExecutionResult {
        val ownerSession = batchSession.ifBlank {
            realSessionId.ifBlank { sessionId }.ifBlank { activeSessionId }
        }
        val outcome = try {
            runOneSubAgentInner(
                spawn = spawn,
                toolId = toolId,
                toolBlocks = toolBlocks,
                assistantId = assistantId,
                currentText = currentText,
                parallelWriters = parallelWriters,
                cardIndex = cardIndex,
                index = index,
                total = total,
                tokenBudget = tokenBudget,
                batchId = batchId,
                batchSession = ownerSession,
            )
        } catch (e: CancellationException) {
            recordLaneOutcome(ownerSession, batchId, index, null, stopped = true, error = e.message)
            throw e
        }
        recordLaneOutcome(ownerSession, batchId, index, outcome, stopped = false, error = null)
        return outcome
    }

/**
 * Mirror a finished lane onto the registry and its plan item. Best-effort by
 * design: bookkeeping must never turn a completed lane into a failed one.
 */
private fun ChatViewModel.recordLaneOutcome(
        sessionId: String,
        batchId: String?,
        index: Int,
        outcome: ToolExecutionResult?,
        stopped: Boolean,
        error: String?,
    ) {
        if (batchId == null) return
        val registry = com.openminis.app.tools.SubAgentBatchRegistry
        val lane = runCatching { registry.get(sessionId, context, batchId) }
            .getOrNull()?.lanes?.find { it.index == index } ?: return
        val success = outcome?.success == true
        runCatching {
            registry.markFinished(
                sessionId = sessionId,
                context = context,
                batchId = batchId,
                index = index,
                success = success,
                output = outcome?.output.orEmpty(),
                error = error ?: if (!success && !stopped) outcome?.output?.trim()?.take(300) else null,
                attempts = lane.attempts,
                stopped = stopped,
            )
        }
        val planId = lane.planId ?: return
        val status = if (success) "done" else "failed"
        runCatching {
            com.openminis.app.tools.AgentPlanStore.update(
                sessionId,
                context,
                planId,
                null,
                outcome?.output?.trim()?.take(200),
                status,
            )
        }
    }

private suspend fun ChatViewModel.runOneSubAgentInner(
        spawn: ChatSubAgentSpawn,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>?,
        assistantId: String,
        currentText: String,
        parallelWriters: Int,
        cardIndex: Int? = null,
        index: Int,
        total: Int,
        tokenBudget: SubAgentTokenBudget? = null,
        /** [T-subagent-plan-autolog] Wave this lane belongs to, for the registry. */
        batchId: String? = null,
        batchSession: String = "",
    ): ToolExecutionResult {
        val prompt = spawn.prompt
        val role = spawn.role
        val skills = spawn.skills
        val requested = spawn.requestedModel
        val kind = spawn.kind
        val writePaths = spawn.writePaths
        val maxTurns = spawn.maxTurns
        val title = spawn.title.ifEmpty { "子代理 $index/$total" }
        // A present-but-all-invalid `write_paths` parses to the poison marker,
        // which WritePathGuard resolves to "deny every write" — correct, but
        // useless to the model, which would see a worker that cannot write
        // anything and no explanation. Treat it as "not supplied" so the
        // actionable error below fires instead of a silent denial at runtime.
        if (SubAgentKind.requiresWritePaths(kind, parallelWriters) &&
            (writePaths.isEmpty() || com.openminis.app.tools.WritePathGuard.isPoisoned(writePaths))
        ) {
            val msg = "Error: parallel workers must set write_paths to non-overlapping directories, or write_paths=none if this task must not write."
            publishSubAgentCard(
                parentToolId = toolId,
                cardIndex = cardIndex,
                title = title,
                assistantId = assistantId,
                currentText = currentText,
                toolBlocks = toolBlocks,
                log = currentSubAgentLine(spawn, index, total, msg),
                status = ToolBlockStatus.FAILED,
            )
            return ToolExecutionResult(msg, false)
        }
        // [T-subagent-config-race] `config.value` is whatever the StateFlow
        // holds RIGHT NOW. A spawn that lands before the one-shot async config
        // load publishes (cold app start, process recreate after memory
        // pressure) sees empty modelEntries, resolves baseEntry to null and
        // dies with "No model available for sub-agent" — on a device that has
        // models configured. Intermittent by construction: it is a start-up
        // window, not a state, and the retry loop further down never runs
        // because the failure happens before it. Wait for the load, bounded,
        // and only then fall through to the actionable error.
        if (providerRepository.config.value.modelEntries.isEmpty()) {
            kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                providerRepository.configLoaded.first { it }
            }
        }
        val config = providerRepository.config.value
        val pool = MultiAgentSettings.retainLive(
            multiAgentSettings.selectedModelEntryIds.value,
            config.modelEntries.map { it.id }.toSet(),
            multiAgentSettings.maxConcurrent.value,
        )
        // Resolve exact IDs first, then fuzzy-match dirty provider/model labels
        // only within the user's configured pool. Retries may rotate afterwards.
        val selectedEntries = if (pool.isEmpty()) config.modelEntries else
            pool.mapNotNull { id -> config.modelEntries.find { it.id == id } }
        val requestedEntry = requested?.let { modelId ->
            selectedEntries.firstOrNull { it.id.equals(modelId, ignoreCase = true) }
                ?: com.openminis.app.provider.ModelAliasMatcher.resolveBest(
                    modelId,
                    selectedEntries,
                    idOf = { it.model.id },
                    nameOf = { it.model.displayName },
                )
        }
        val pickedId = requestedEntry?.id
            ?: MultiAgentSettings.pickModelId(pool, requested, subAgentRoundRobin.getAndIncrement())
        val baseEntry = requestedEntry ?: pickedId?.let { id ->
            config.modelEntries.find { it.id == id }
        } ?: _activeEntryId.value?.let { id -> config.modelEntries.find { it.id == id } }
            ?: return ToolExecutionResult(
            "No model available for sub-agent. Select models under Settings → Multi-agent, or keep the main session model selected.",
            false,
        )
        val requestedThinking = spawn.thinkingLevel ?: parseThinkingLevel(
            multiAgentSettings.slotThinkingLevel((index - 1).coerceAtLeast(0)),
            baseEntry.effectiveMaxThinkingLevel,
        )
        // [T-android-subagent-depth-leak] Claim the depth slot inside a
        // try/finally that covers EVERYTHING after the claim. The lane body has
        // its own try/finally, but between this claim and entering that block
        // sit suspension points (activity-tracker start, lane-id resolution).
        // Stopping a dispatch while it was parked in one of them threw
        // CancellationException before the lane's finally existed, so the
        // decrement never ran: depth stayed above zero for the rest of the
        // ViewModel's life and every later dispatch was refused with
        // "sub-agents cannot spawn further sub-agents" until the app was killed.
        subAgentDepth.incrementAndGet()
        return try {
        val parentSession = batchSession.ifBlank {
            realSessionId.ifBlank { sessionId }.ifBlank { activeSessionId }
        }
        val laneId = kotlin.coroutines.coroutineContext[SubAgentLane]?.id
            ?: SubAgentLane.idFor(parentSession, System.nanoTime())
        val trackerId = com.openminis.app.service.SubAgentActivityTracker.start(
            parentSessionId = parentSession,
            parentToolId = toolId,
            title = title,
            role = role,
            model = baseEntry.model.displayName,
            index = index,
            total = total,
            kind = kind,
            turnCap = maxTurns,
        )
        val subAgentJob = kotlinx.coroutines.SupervisorJob(kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job])
        com.openminis.app.service.SubAgentActivityTracker.attachJob(trackerId, subAgentJob)
        return try {
            withContext(subAgentJob) {
            // [T-subagent-429-selfheal] Fan-out used to fire every lane in the
            // same millisecond, which is the cheapest way to provoke the very
            // rate limit that then killed one of them. Spread the wave.
            if (total > 1 && index > 1) {
                delay(SPAWN_STAGGER_MS * (index - 1) + Random.nextLong(0, 150))
            }
            // Bounded retry with pool-endpoint rotation. Sub-agent failures are
            // mostly transient upstream errors (429 / truncated stream); the main
            // session already retries those, sub-agents used to die on first blip.
            // Attempts budget is user-tunable (Settings → Multi-agent, 1 = no retry).
            val maxAttempts = multiAgentSettings.subagentMaxAttempts.value
            var attempt = 0
            var lastError: Exception? = null
            var lastFailedGateKey: String? = null
            var lastFailedEntryId: String? = null
            while (attempt < maxAttempts) {
                attempt++
                val entry = if (attempt == 1) {
                    baseEntry
                } else {
                    val rotated = pickRetryEntry(
                        entries = config.modelEntries,
                        pool = pool,
                        excludeEntryId = lastFailedEntryId,
                        excludeGateKey = lastFailedGateKey,
                        gateKeyOf = { e -> providerForModelEntry(e)?.callGateKey },
                        seed = subAgentRoundRobin.getAndIncrement(),
                    )
                    if (rotated != null) {
                        rotated
                    } else {
                        // [T-subagent-429-selfheal] "Nothing to rotate to" used
                        // to be treated as "nothing left to try": a 429 on a
                        // single-provider pool returned a final failure at once,
                        // discarding the remaining attempt budget. That is why a
                        // concurrent wave lost exactly one member at random —
                        // whichever lane hit the rate limit first — while its
                        // siblings finished normally. Rotation and retry are
                        // separate questions: with no alternative bucket, wait
                        // out the cooldown on the same entry and keep going.
                        val last = lastError
                        val rateLimited = last is LLMError.RateLimited ||
                            (last as? LLMError)?.isFallbackable == true
                        if (rateLimited && attempt < maxAttempts) {
                            val waitS = backoffSeconds(
                                attempt,
                                (last as? LLMError.RateLimited)?.retryAfterSeconds ?: 0,
                            )
                            Log.i(
                                ChatViewModel.TAG,
                                "Sub-agent 429 lane=$laneId no rotation target; cooling down ${waitS}s on ${baseEntry.model.displayName} (attempt $attempt/$maxAttempts)",
                            )
                            runCatching {
                                com.openminis.app.service.SubAgentActivityTracker.updateProgress(
                                    trackerId, 0, maxTurns, "rate-limited; retry in ${waitS}s",
                                )
                            }
                            com.openminis.app.service.SubAgentEventBus.publish(
                                com.openminis.app.service.SubAgentEvent.RetryScheduled(
                                    runId = trackerId,
                                    parentSessionId = parentSession,
                                    attempt = attempt,
                                    maxAttempts = maxAttempts,
                                    waitMs = waitS * 1000L,
                                    reason = "rate-limit-cooldown",
                                    atMs = System.currentTimeMillis(),
                                ),
                            )
                            delay(waitS * 1000L + Random.nextLong(0, 800))
                        }
                        baseEntry
                    }
                }
                // [T-subagent-plan-autolog] Publish the attempt before it runs:
                // a lane that dies mid-call still shows which model and which
                // attempt it was on, instead of a stale `pending`.
                if (batchId != null) {
                    runCatching {
                        com.openminis.app.tools.SubAgentBatchRegistry.markRunning(
                            parentSession, context, batchId, index, trackerId,
                            entry.model.displayName, attempt,
                        )
                    }
                }
                val provider = providerForModelEntry(entry)
                if (provider == null) {
                    if (attempt >= maxAttempts) {
                        return@withContext ToolExecutionResult(
                            "Failed to create provider for ${entry.model.displayName} (attempt $attempt/$maxAttempts)",
                            false,
                        )
                    }
                    lastFailedEntryId = entry.id
                    continue
                }
                if (attempt > 1) {
                    Log.i(ChatViewModel.TAG, "Sub-agent retry lane=$laneId attempt=$attempt/$maxAttempts model=${entry.model.displayName} prev=${lastError?.message}")
                    runCatching {
                        com.openminis.app.service.SubAgentActivityTracker.updateProgress(
                            trackerId, 0, maxTurns, "retry ${attempt - 1} → ${entry.model.displayName}",
                        )
                    }
                    com.openminis.app.service.SubAgentEventBus.publish(
                        com.openminis.app.service.SubAgentEvent.RetryScheduled(
                            runId = trackerId,
                            parentSessionId = parentSession,
                            attempt = attempt,
                            maxAttempts = maxAttempts,
                            waitMs = 0L,
                            reason = "model-rotation:${entry.model.displayName}",
                            atMs = System.currentTimeMillis(),
                        ),
                    )
                }
                val attemptResult: ToolExecutionResult? = try {
            withContext(SubAgentLane(laneId)) {
            var lastUiMs = 0L
            suspend fun onStep(turn: Int, toolName: String) {
                com.openminis.app.service.SubAgentActivityTracker.updateProgress(
                    trackerId, turn, maxTurns, toolName,
                )
                if (toolName.isNotBlank()) {
                    com.openminis.app.service.SubAgentActivityTracker.appendLog(
                        trackerId,
                        "turn $turn/$maxTurns · $toolName",
                    )
                }
                val now = System.currentTimeMillis()
                val important = toolName.isNotBlank()
                if (!important && now - lastUiMs < 250L) return
                lastUiMs = now
                val step = if (toolName.isNotBlank()) "turn $turn/$maxTurns · $toolName" else "turn $turn/$maxTurns"
                publishSubAgentCard(
                    parentToolId = toolId,
                    cardIndex = cardIndex,
                    title = title,
                    assistantId = assistantId,
                    currentText = currentText,
                    toolBlocks = toolBlocks,
                    log = currentSubAgentLine(spawn, index, total, step),
                )
            }
            val result = com.openminis.app.tools.WritePathGuard.withPaths(writePaths) {
                SubAgentRunner.run(
                    provider = provider,
                    temperature = entry.overrides.temperature,
                    modelDisplayName = entry.model.displayName,
                    userPrompt = prompt,
                    role = role,
                    roleContext = context,
                    skillsHint = skills,
                    tools = com.openminis.app.tools.DispatchAgentsTool.filterToolsForType(
                        com.openminis.app.tools.SubAgentTypeStore.find(context, role.orEmpty()),
                        SubAgentKind.filterTools(
                            kind,
                            AgentTools.makeAgentTools(
                                supportsImageInput = entry.model.hasImageInput,
                                visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
                                    providerRepository, context,
                                ),
                                memoryEnabled = false,
                                subAgentEnabled = false,
                            ) + AgentTools.makeSubAgentExtraTools(),
                            role,
                            context,
                        ),
                    ),
                    maxTokens = (entry.model.maxOutputTokens ?: 4096).coerceIn(256, 8192),
                    executeTool = { name, json ->
                        if (SubAgentKind.blocks(kind, name)) {
                            ToolExecutionResult("Error: $kind sub-agent cannot use $name.", false)
                        } else if (com.openminis.app.tools.CollabRoles.toolsFor(context, role)?.let { name !in it } == true) {
                            ToolExecutionResult("Error: role $role cannot use $name.", false)
                        } else if (run {
                            val type = com.openminis.app.tools.SubAgentTypeStore.find(context, role.orEmpty())
                            type != null && type.toolNames.isNotEmpty() && name !in type.toolNames
                        }) {
                            ToolExecutionResult("Error: type $role cannot use $name.", false)
                        } else if (name == com.openminis.app.tools.GrepSourceTool.NAME) {
                            com.openminis.app.tools.GrepSourceTool.execute(json, sessionId, context)
                        } else {
                            executeTool(name, json, "", mutableListOf(), "", "")
                        }
                    },
                    onStep = { turn, toolName -> onStep(turn, toolName) },
                    onUi = { event -> publishSubAgentUi(trackerId, event) },
                    kind = kind,
                    writePaths = writePaths,
                    maxTurns = maxTurns,
                    tokenBudget = tokenBudget,
                    thinkingLevel = requestedThinking,
                )
            }
            com.openminis.app.service.SubAgentActivityTracker.appendLog(
                trackerId,
                "---\n" + result.output,
            )
            publishSubAgentCard(
                parentToolId = toolId,
                cardIndex = cardIndex,
                title = title,
                assistantId = assistantId,
                currentText = currentText,
                toolBlocks = toolBlocks,
                log = currentSubAgentLine(spawn, index, total, SubAgentRunner.cardStep(result.output)),
                status = if (result.success) ToolBlockStatus.SUCCESS else ToolBlockStatus.FAILED,
            )
            com.openminis.app.service.SubAgentActivityTracker.finish(trackerId, result.success)
            // [T-event-bus] Parent-session awareness: the coordinator used to
            // receive only the lane's final text — append what the bus
            // observed for this run so turn counts, tool volume and retry
            // storms are visible in the dispatch result itself.
            val lifecycle = com.openminis.app.service.SubAgentEventBus.summaryFor(trackerId)
                ?.let { s -> "\n\n[lifecycle] turns=${s.turns} toolCalls=${s.toolCalls} retries=${s.retries}" }
                .orEmpty()
            result.copy(
                output = result.output + lifecycle,
                toolTitle = title.ifEmpty { "Sub-agent · ${entry.model.displayName}" },
            )
            }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                lastFailedEntryId = entry.id
                lastFailedGateKey = provider.callGateKey
                val retryable = (e as? LLMError)?.isRetryable ?: isUpstreamTruncation(e)
                Log.w(ChatViewModel.TAG, "Sub-agent lane=$laneId attempt=$attempt/$maxAttempts retryable=$retryable err=${e.message}")
                if (retryable && attempt < maxAttempts) {
                    // Same ladder as the rotation path, and capped: an uncapped
                    // Retry-After could park a lane (holding its concurrency
                    // permit) for minutes while siblings waited on it.
                    val waitS = backoffSeconds(
                        attempt,
                        (e as? LLMError.RateLimited)?.retryAfterSeconds ?: 0,
                    )
                    runCatching {
                        com.openminis.app.service.SubAgentActivityTracker.updateProgress(
                            trackerId, 0, maxTurns, "retry in ${waitS}s (${e.javaClass.simpleName})",
                        )
                    }
                    com.openminis.app.service.SubAgentEventBus.publish(
                        com.openminis.app.service.SubAgentEvent.RetryScheduled(
                            runId = trackerId,
                            parentSessionId = parentSession,
                            attempt = attempt,
                            maxAttempts = maxAttempts,
                            waitMs = waitS * 1000L,
                            reason = e.javaClass.simpleName,
                            atMs = System.currentTimeMillis(),
                        ),
                    )
                    delay(waitS * 1000L + Random.nextLong(0, 800))
                    null
                } else {
                    // Final failure: RETURN, do not throw — an exception escaping
                    // this lane would cancel its fan-out siblings via awaitAll.
                    com.openminis.app.service.SubAgentActivityTracker.finish(trackerId, false, e.message)
                    return@withContext ToolExecutionResult(
                        "Sub-agent failed after $attempt attempt(s): ${e.message ?: e.javaClass.simpleName}",
                        false,
                    )
                }
            }
            if (attemptResult != null) {
                return@withContext if (attempt > 1) attemptResult.copy(
                    output = "(recovered on attempt $attempt/$maxAttempts via ${entry.model.displayName})\n" + attemptResult.output,
                ) else attemptResult
            }
            }
            val failed = ToolExecutionResult(
                "Sub-agent failed after $maxAttempts attempt(s): ${lastError?.message ?: "unknown error"}",
                false,
            )
            publishSubAgentCard(
                parentToolId = toolId,
                cardIndex = cardIndex,
                title = title,
                assistantId = assistantId,
                currentText = currentText,
                toolBlocks = toolBlocks,
                log = currentSubAgentLine(spawn, index, total, failed.output),
                status = ToolBlockStatus.FAILED,
            )
            failed
            }
        } catch (e: CancellationException) {
            val stoppedByUser = com.openminis.app.service.SubAgentActivityTracker.wasUserStopped(trackerId)
            com.openminis.app.service.SubAgentActivityTracker.finish(trackerId, false, e.message)
            if (stoppedByUser) return ToolExecutionResult("Sub-agent stopped by user", false)
            throw e
        } catch (t: Throwable) {
            com.openminis.app.service.SubAgentActivityTracker.finish(trackerId, false, t.message)
            val failed = ToolExecutionResult(
                "Sub-agent failed: ${t.javaClass.simpleName}: ${t.message}",
                false,
            )
            publishSubAgentCard(
                parentToolId = toolId,
                cardIndex = cardIndex,
                title = title,
                assistantId = assistantId,
                currentText = currentText,
                toolBlocks = toolBlocks,
                log = currentSubAgentLine(spawn, index, total, failed.output),
                status = ToolBlockStatus.FAILED,
            )
            return failed
        } finally {
            withContext(NonCancellable) {
                runCatching { ExecutionCoordinator.sessionDidTerminate(laneId) }
                subAgentJob.cancel()
            }
        }
        } finally {
            subAgentDepth.decrementAndGet()
        }
    }

private fun isUpstreamTruncation(e: Exception): Boolean {
        val m = (e.message ?: "").lowercase()
        return "empty response" in m || "connection dropped" in m || "upstream" in m ||
            "eof" in m || "truncat" in m
    }

/**
 * Re-pick a pool entry on a different rate-limit bucket (host + key + model).
 * Same model name on another key is a different bucket and is eligible.
 * Returns null when the pool has no other bucket (caller may stop on 429).
 */
private fun pickRetryEntry(
        entries: List<ModelEntry>,
        pool: List<String>,
        excludeEntryId: String?,
        excludeGateKey: String?,
        gateKeyOf: (ModelEntry) -> String?,
        seed: Int,
    ): ModelEntry? {
        val pooled = pool.filter { it.isNotBlank() }.mapNotNull { id -> entries.find { it.id == id } }
        val rotated = pooled.filter { candidate ->
            if (excludeEntryId != null && candidate.id == excludeEntryId) return@filter false
            val gk = gateKeyOf(candidate)
            gk.isNullOrBlank() || excludeGateKey.isNullOrBlank() ||
                !com.openminis.app.provider.ProviderKeyGate.sameBucket(gk, excludeGateKey)
        }
        return if (rotated.isNotEmpty()) rotated[Math.floorMod(seed, rotated.size)] else null
    }

internal fun ChatViewModel.providerForModelEntry(entry: ModelEntry): LLMProvider? {
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return null
        var apiKey = providerRepository.usableApiKey(instance) ?: return null
        if (instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth) {
            try {
                val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                val freshToken = kotlinx.coroutines.runBlocking { manager?.validAccessToken() }
                if (freshToken != null && freshToken != apiKey) {
                    providerRepository.saveApiKey(instance.id, freshToken)
                    apiKey = freshToken
                }
            } catch (e: Exception) {
                Log.w(ChatViewModel.TAG, "Sub-agent OAuth refresh failed: ${e.message}")
            }
        }
        return ProviderFactory.create(instance, apiKey, entry.model, context)
    }
