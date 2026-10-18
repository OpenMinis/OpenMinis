package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.repository.MultiAgentSettings
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.provider.streamStallWatchdog
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.DiscussionGraph
import com.openminis.app.tools.GroupChat
import com.openminis.app.tools.SubAgentKind
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * [T-groupchat-host-deadline] The host is supposed to only open and close the
 * discussion (e817cc1), but neither call had a deadline of its own: a host
 * model stuck on a stalled network kept runGroupChat suspended forever, the
 * UI parked on the summary state, and "群聊关不掉" (the exact bug aac4d26
 * fixed) came back through the time-out door instead. Members are wrapped by
 * the same deadline inside speakMembers' error handling; the host paths now
 * share one explicit budget.
 */
private const val GROUP_CHAT_HOST_TIMEOUT_MS = 3L * 60_000L

internal suspend fun ChatViewModel.runGroupChat(provider: LLMProvider, closing: Boolean) {
    if (closing) groupChatCloseRequested = true
    if (groupChatAnchorPending) anchorGroupRound()
    val analyzing = context.getString(com.openminis.app.R.string.group_chat_analyzing)
    val userText = _messages.value.lastOrNull { it.role == "user" && !it.isQueued }?.content.orEmpty()
    val closingNow = closing || GroupChat.isCloseRequest(userText)
    val prior = if (closingNow) closeRecord() else groupTranscript()
    if (closingNow && prior.isEmpty()) {
        publishGroupNotice(context.getString(com.openminis.app.R.string.group_chat_nothing_to_close))
        groupChatCloseRequested = false
        return
    }
    if (!closing && userText.isBlank()) return

    val config = providerRepository.config.value
    val mainEntry = _activeEntryId.value?.let { id -> config.modelEntries.find { it.id == id } }
    val hostName = (mainEntry?.model?.displayName ?: currentModel?.displayName ?: "Host") +
        " · " + context.getString(com.openminis.app.R.string.group_chat_host_suffix)
    val hostSnapshot = snapshotFor(mainEntry)
    val hostVendor = GroupChat.vendorKey(
        mainEntry?.model?.id ?: currentModel?.id,
        mainEntry?.model?.displayName ?: currentModel?.displayName,
    )
    val slotMembers = groupMembers(config.modelEntries, mainEntry?.id)
    val hostPlain = mainEntry?.model?.displayName ?: currentModel?.displayName ?: "Host"
    val hostMember = GroupMember(
        name = hostPlain,
        modelId = mainEntry?.model?.id ?: currentModel?.id.orEmpty(),
        vendor = hostVendor,
        provider = provider,
        snapshot = hostSnapshot,
        maxTokens = (mainEntry?.model?.maxOutputTokens ?: 4096).coerceIn(1024, 16384),
        temperature = mainEntry?.overrides?.temperature,
        thinkingLevel = mainEntry?.effectiveMaxThinkingLevel
            ?: com.openminis.app.data.model.ThinkingLevel.OFF,
    )
    // The main session only opens and closes. Selected teammates are the speakers.
    val speakers = slotMembers
    val roster = listOf(hostMember) + speakers
    val wantsClose = closingNow
    val spoken = prior.toMutableList()
    val contextText = recentContext()
    val addressed = if (!wantsClose) {
        GroupChat.resolveTarget(
            userText,
            roster.map { member ->
                GroupChat.Addressable(
                    name = member.name,
                    key = member.modelId.ifBlank { member.name },
                    aliases = listOf(
                        member.name,
                        member.modelId,
                        if (member.modelId == hostMember.modelId && member.name == hostMember.name) {
                            com.openminis.app.agent.SoulStore.cachedMetadata.value.name
                        } else {
                            ""
                        },
                    ).map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                )
            },
        )
    } else {
        null
    }
    if (!wantsClose && addressed == null && speakers.isEmpty()) {
        publishGroupNotice(context.getString(com.openminis.app.R.string.group_chat_need_models))
        return
    }
    // A resolved @ names exactly one speaker. Do not fall through into the
    // all-members round — the others must not start, not even to say PASS.
    if (!wantsClose && addressed != null && !groupChatCloseRequested) {
        val member = roster.find { (it.modelId.ifBlank { it.name }) == addressed.key }
            ?: roster.find { it.name == addressed.name }
        if (member != null && member.modelId == hostMember.modelId && member.name == hostMember.name) {
            val answer = speakVisible(
                speaker = hostName,
                vendor = hostVendor,
                snapshot = hostSnapshot,
                provider = provider,
                system = GroupChat.HOST_DIRECT_SYSTEM,
                user = GroupChat.directPrompt(hostPlain, userText, GroupChat.transcript(spoken), contextText),
                tools = emptyList(),
                placeholder = analyzing,
                maxTokens = hostMember.maxTokens,
            )
            if (answer.isNotBlank()) spoken += GroupChat.Line(hostName, answer)
        } else if (member != null) {
            spoken += speakMembers(listOf(member), analyzing) {
                GroupChat.directPrompt(it.name, userText, GroupChat.transcript(spoken), contextText)
            }
        }
    } else if (!wantsClose && !groupChatCloseRequested && speakers.isNotEmpty()) {
        val savedOpening = savedHostOpening()
        if (savedOpening != null) {
            spoken.add(0, GroupChat.Line(hostName, savedOpening))
        } else {
            val opening = try {
                withTimeout(GROUP_CHAT_HOST_TIMEOUT_MS) {
                    speakVisible(
                        speaker = hostName,
                        vendor = hostVendor,
                        snapshot = hostSnapshot,
                        provider = provider,
                        system = GroupChat.OPENING_SYSTEM,
                        user = GroupChat.openingPrompt(userText, contextText),
                        tools = emptyList(),
                        placeholder = analyzing,
                        maxTokens = hostMember.maxTokens,
                    )
                }
            } catch (e: TimeoutCancellationException) {
                // Deadline miss: the discussion continues without a host
                // opening rather than hanging the whole round.
                ""
            }
            if (opening.isNotBlank()) {
                rememberHostOpening(opening)
                spoken.add(0, GroupChat.Line(hostName, opening))
            }
        }
        // [group-chat-debate] 排队可视化：先给全员预建“排队中”气泡，
        // 轮到谁谁变思考态，队列一眼可见；没被轮到的（关门截断）收尾清掉。
        val queuedText = context.getString(com.openminis.app.R.string.group_chat_queued)
        val queueIds = speakers.associate { it.name to UUID.randomUUID().toString() }
        for (member in speakers) {
            upsertGroupBubble(queueIds.getValue(member.name), member.name, queuedText, analyzing = true, vendor = member.vendor)
        }
        for ((index, member) in speakers.withIndex()) {
            if (groupChatCloseRequested) break
            spoken += speakMembers(listOf(member), analyzing, bubbleIds = queueIds) {
                GroupChat.opinionPrompt(
                    it.name,
                    GroupChat.stance(index),
                    userText,
                    GroupChat.transcript(spoken),
                    contextText,
                )
            }
        }
        clearGroupQueue(queueIds)

        // [group-chat-debate] 答辩轮：按序补刀（可多点、500 字内、可 PASS）。
        // 收敛规则：整圈没人有新观点（全 PASS）即停；上限 4 圈兜底；关门立即停。
        var debateRound = 0
        while (debateRound < 4 && !groupChatCloseRequested) {
            debateRound++
            val debateIds = speakers.associate { it.name to UUID.randomUUID().toString() }
            for (member in speakers) {
                upsertGroupBubble(debateIds.getValue(member.name), member.name, queuedText, analyzing = true, vendor = member.vendor)
            }
            val before = spoken.size
            for (member in speakers) {
                if (groupChatCloseRequested) break
                spoken += speakMembers(listOf(member), analyzing, allowPass = true, bubbleIds = debateIds) { m ->
                    GroupChat.replyPrompt(m.name, userText, GroupChat.transcript(spoken).takeLast(8000))
                }
            }
            clearGroupQueue(debateIds)
            if (spoken.size == before) break
        }
    }

    if (wantsClose || groupChatCloseRequested) {
        val summary = try {
            withTimeout(GROUP_CHAT_HOST_TIMEOUT_MS) {
                speakVisible(
                    speaker = hostName,
                    vendor = hostVendor,
                    snapshot = hostSnapshot,
                    provider = provider,
                    system = GroupChat.HOST_SYSTEM,
                    user = GroupChat.summaryPrompt(userText, GroupChat.transcript(spoken)),
                    tools = emptyList(),
                    placeholder = context.getString(com.openminis.app.R.string.group_chat_summarizing),
                    maxTokens = hostMember.maxTokens,
                )
            }
        } catch (e: TimeoutCancellationException) {
            // [T-groupchat-host-deadline] A stalled summary must not wedge the
            // close flow — fall into the failure branch below, which now also
            // force-disables the group chat so the session is never stuck
            // "closing" with the request flag held true.
            null
        }
        if (!summary.isNullOrBlank()) {
            _messages.value.lastOrNull { it.speakerName == hostName }?.let { closed ->
                groupChatClosedAfterId = stableGroupMessageId(closed)
                groupChatPrefs().edit().putString(closedKey(), groupChatClosedAfterId).apply()
            }
            withContext(Dispatchers.Main) {
                _promptQueue.update { emptyList() }
                _messages.value = _messages.value.filterNot { it.isQueued }
            }
            setGroupChatEnabled(false)
        } else {
            publishGroupNotice(context.getString(com.openminis.app.R.string.group_chat_summary_failed))
            withContext(Dispatchers.Main) {
                _promptQueue.update { emptyList() }
                _messages.value = _messages.value.filterNot { it.isQueued }
            }
            setGroupChatEnabled(false)
        }
    }
    groupChatCloseRequested = false
}

internal fun ChatViewModel.endGroupChat() {
    if (_isStreaming.value) {
        groupChatCloseRequested = true
        return
    }
    val provider = currentProvider ?: idleGroupProvider()
    if (provider == null) {
        appendSystemInfo(context.getString(com.openminis.app.R.string.group_chat_no_provider), "info")
        return
    }
    currentProvider = provider
    groupChatCloseRequested = true
    _isStreaming.value = true
    streamJob = viewModelScope.launchActiveRun(
        activeSessionId,
        Dispatchers.IO,
        ownerSessionIds = setOf(activeSessionId, sessionId, realSessionId),
        beforeStart = { streamJob = it },
    ) {
        try {
            com.openminis.app.service.SessionConcurrencyManager.acquireSlot(activeSessionId)
            runGroupChat(provider, closing = true)
        } catch (_: CancellationException) {
        } finally {
            com.openminis.app.service.SessionConcurrencyManager.releaseSlot(activeSessionId)
            if (streamJob === coroutineContext[kotlinx.coroutines.Job]) {
                _isStreaming.value = false
            }
        }
    }
}

private data class GroupMember(
    val name: String,
    val modelId: String,
    val vendor: String,
    val provider: LLMProvider,
    val snapshot: ModelAttributionSnapshot?,
    val maxTokens: Int,
    val temperature: Double?,
    val thinkingLevel: com.openminis.app.data.model.ThinkingLevel,
)

private fun ChatViewModel.idleGroupProvider(): LLMProvider? {
    val config = providerRepository.config.value
    val entry = _activeEntryId.value?.let { id -> config.modelEntries.find { it.id == id } }
        ?: config.modelEntries.firstOrNull()
    return entry?.let { providerForModelEntry(it) }
}

private fun ChatViewModel.groupMembers(
    entries: List<ModelEntry>,
    hostEntryId: String?,
): List<GroupMember> {
    val ids = MultiAgentSettings.retainLive(
        multiAgentSettings.selectedModelEntryIds.value,
        entries.map { it.id }.toSet(),
        multiAgentSettings.maxConcurrent.value,
    )
    return ids.mapNotNull { id ->
        if (id.isBlank() || id == hostEntryId) return@mapNotNull null
        val entry = entries.find { it.id == id } ?: return@mapNotNull null
        val provider = providerForModelEntry(entry) ?: return@mapNotNull null
        GroupMember(
            name = entry.model.displayName,
            modelId = entry.model.id,
            vendor = GroupChat.vendorKey(entry.model.id, entry.model.displayName),
            provider = provider,
            snapshot = snapshotFor(entry),
            maxTokens = (entry.model.maxOutputTokens ?: 4096).coerceIn(1024, 16384),
            temperature = entry.overrides.temperature,
            thinkingLevel = entry.effectiveMaxThinkingLevel,
        )
    }
}

private fun ChatViewModel.groupTools(provider: LLMProvider) = AgentTools.makeAgentTools(
    supportsImageInput = provider.model.hasImageInput,
    visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
        providerRepository, context,
    ),
    memoryEnabled = false,
    subAgentEnabled = false,
).filter { !SubAgentKind.blocks(SubAgentKind.PLAN, it.name) }

private fun ChatViewModel.openingKey() = "opening:$groupChatPrefsId"

private fun ChatViewModel.savedHostOpening(): String? =
    groupChatPrefs().getString(openingKey(), null)?.trim()?.takeIf { it.isNotEmpty() }

private fun ChatViewModel.rememberHostOpening(text: String) {
    groupChatPrefs().edit().putString(openingKey(), text).apply()
}

internal fun ChatViewModel.clearHostOpening() {
    groupChatPrefs().edit().remove(openingKey()).apply()
}

private suspend fun ChatViewModel.speakMembers(
    members: List<GroupMember>,
    analyzing: String,
    deferBubble: Boolean = false,
    allowPass: Boolean = false,
    bubbleIds: Map<String, String>? = null,
    promptFor: (GroupMember) -> String,
): List<GroupChat.Line> {
    val gate = Mutex()
    val lines = mutableListOf<GroupChat.Line>()
    for (member in members) {
        if (groupChatCloseRequested) break
        val text = try {
            speakVisible(
                speaker = member.name,
                snapshot = member.snapshot,
                provider = member.provider,
                system = GroupChat.memberSystem(member.name),
                user = promptFor(member),
                tools = groupTools(member.provider),
                placeholder = analyzing,
                toolGate = gate,
                allowPass = allowPass,
                thinkingLevel = member.thinkingLevel,
                vendor = member.vendor,
                deferBubble = deferBubble,
                maxTokens = member.maxTokens,
                bubbleId = bubbleIds?.get(member.name),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            upsertGroupBubble(
                UUID.randomUUID().toString(),
                member.name,
                context.getString(
                    com.openminis.app.R.string.group_chat_member_failed,
                    member.name,
                    e.message ?: e.javaClass.simpleName,
                ),
                analyzing = false,
                vendor = member.vendor,
            )
            continue
        }
        text.takeIf { it.isNotBlank() }?.let { lines += GroupChat.Line(member.name, it) }
    }
    return lines
}

private suspend fun ChatViewModel.speakVisible(
    speaker: String,
    snapshot: ModelAttributionSnapshot?,
    provider: LLMProvider,
    system: String,
    user: String,
    tools: List<com.openminis.app.data.model.AgentToolDefinition>,
    placeholder: String,
    toolGate: Mutex? = null,
    allowPass: Boolean = false,
    thinkingLevel: com.openminis.app.data.model.ThinkingLevel =
        com.openminis.app.data.model.ThinkingLevel.OFF,
    vendor: String = GroupChat.VENDOR_UNKNOWN,
    deferBubble: Boolean = false,
    maxTokens: Int = 4096,
    bubbleId: String? = null,
): String {
    if (groupChatCloseRequested && allowPass) return ""
    // [group-chat-debate] 排队可视化：外部预建的“排队中”气泡可传入复用，
    // 轮到该成员时同一气泡从排队态平滑切换到思考态。
    val id = bubbleId ?: UUID.randomUUID().toString()
    if (!deferBubble) upsertGroupBubble(id, speaker, placeholder, analyzing = true, vendor = vendor)
    val text = try {
        var spoken = speakModel(
            provider, system, user, tools, toolGate, thinkingLevel,
            stopWhenClosing = allowPass, maxTokens = maxTokens,
        ) { block ->
            showGroupStatus(id, speaker, vendor, block)
        }
        if (!allowPass && !GroupChat.isSubstantive(spoken) && !groupChatCloseRequested) {
            spoken = speakModel(
                provider,
                system,
                user + "\n\n上一句不是发言。请按上面的字数写成完整的一段，不要只回一个字或「对」。",
                emptyList(),
                toolGate = null,
                thinkingLevel = thinkingLevel,
                stopWhenClosing = false,
                maxTokens = maxTokens,
            ) { block ->
                showGroupStatus(id, speaker, vendor, block)
            }
        }
        spoken
    } catch (e: CancellationException) {
        removeGroupBubble(id)
        throw e
    }
    if (!GroupChat.isSubstantive(text)) {
        if (allowPass) {
            upsertGroupBubble(
                id,
                speaker,
                context.getString(com.openminis.app.R.string.group_chat_passed),
                analyzing = false,
                vendor = vendor,
            )
        } else if (!deferBubble) {
            // [group-chat-empty-fix] 空白发言不再静默删气泡：留痕，让“过程可见结果为空”
            // 可以被看见和排查。
            upsertGroupBubble(
                id,
                speaker,
                context.getString(com.openminis.app.R.string.group_chat_no_output),
                analyzing = false,
                vendor = vendor,
            )
        }
        return ""
    }
    if (deferBubble) upsertGroupBubble(id, speaker, text, analyzing = false, vendor = vendor)
    val dbId = persistGroupUtterance(speaker, text, snapshot, vendor)
    withContext(Dispatchers.Main) {
        _messages.value = _messages.value.map { message ->
            if (message.id == id) message.copy(
                content = text,
                toolBlocks = emptyList(),
                speakerName = speaker,
                speakerVendor = vendor,
                isStreaming = false,
                isAwaitingModelResponse = false,
                sourceDbIds = listOfNotNull(dbId),
            ) else message
        }
    }
    return text
}

private suspend fun ChatViewModel.speakModel(
    provider: LLMProvider,
    system: String,
    user: String,
    tools: List<com.openminis.app.data.model.AgentToolDefinition>,
    toolGate: Mutex?,
    thinkingLevel: com.openminis.app.data.model.ThinkingLevel,
    stopWhenClosing: Boolean,
    maxTokens: Int = 4096,
    onStatus: suspend (AssistantBlock) -> Unit,
): String {
    val history = mutableListOf(LLMMessage(role = LLMMessage.Role.USER, content = user))
    val report = StringBuilder()
    var lastThinking = ""
    // [group-chat-empty-fix] 轮次上限 3→6，另加 3 分钟总时长兜底：工具密集的发言
    // 以前跑满 3 轮就只剩思考兑底。思考档不降级，靠预算和记账给正文留位置。
    val deadline = android.os.SystemClock.elapsedRealtime() + 180_000L
    var round = 0
    while (round < 6 && android.os.SystemClock.elapsedRealtime() < deadline) {
        round++
        if (stopWhenClosing && groupChatCloseRequested) {
            return GroupChat.recoverUtterance(report.toString(), lastThinking)
        }
        val textSb = StringBuilder()
        val thinking = StringBuilder()
        val calls = mutableListOf<Triple<String, String, JSONObject>>()
        var lastStatusAt = 0L
        // [T-stream-stall-watchdog] Same two-phase bound as the main agent
        // loop: a group speaker whose stream goes quiet mid-sentence must not
        // wedge the round for the length of a transport read timeout.
        val firstEventTimeoutMs = if (thinkingLevel.isEnabled) 90_000L else 45_000L
        provider.streamMessage(
            messages = history,
            systemPrompt = system,
            maxTokens = maxTokens,
            temperature = null,
            tools = tools,
            thinkingLevel = thinkingLevel,
        ).streamStallWatchdog(
            firstEventTimeoutMs,
            idleTimeoutMs = firstEventTimeoutMs * 2,
        ).collect { chunk ->
            when (chunk) {
                is LLMStreamChunk.Started -> onStatus(
                    AssistantBlock(
                        id = "group-status",
                        kind = "info",
                        content = context.getString(com.openminis.app.R.string.group_chat_thinking),
                    ),
                )
                is LLMStreamChunk.ThinkingDelta -> {
                    thinking.append(chunk.text)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastStatusAt >= 120L) {
                        lastStatusAt = now
                        onStatus(thinkingStatus(thinking))
                    }
                }
                is LLMStreamChunk.Text -> textSb.append(chunk.text)
                is LLMStreamChunk.ToolUseStart -> onStatus(toolStatus(chunk.id, chunk.name, ToolBlockStatus.RUNNING, ""))
                is LLMStreamChunk.ToolCallComplete -> {
                    calls += Triple(chunk.id, chunk.name, chunk.args)
                    onStatus(toolStatus(chunk.id, chunk.name, ToolBlockStatus.RUNNING, chunk.args.toString()))
                }
                else -> Unit
            }
        }
        if (thinking.isNotEmpty()) onStatus(thinkingStatus(thinking))
        lastThinking = thinking.toString()
        val text = textSb.toString().trim()
        // [group-chat-empty-fix] 每轮正文都记账：带工具调用的轮次同样有话要说，
        // 以前只收“干净轮”的正文，工具密集的发言整段丢失。
        if (text.isNotEmpty()) {
            if (report.isNotEmpty()) report.append("\n\n")
            report.append(text)
        }
        if (calls.isEmpty() || tools.isEmpty()) {
            return GroupChat.recoverUtterance(report.toString(), lastThinking)
        }
        val assistantParts = mutableListOf<AgentContentPart>()
        if (text.isNotEmpty()) assistantParts += AgentContentPart.Text(text)
        calls.forEach { (callId, name, args) ->
            assistantParts += AgentContentPart.ToolUse(callId, name, input = args)
        }
        history += LLMMessage(role = LLMMessage.Role.ASSISTANT, content = text, contentParts = assistantParts)
        val results = mutableListOf<AgentContentPart>()
        for ((callId, name, args) in calls) {
            onStatus(toolStatus(callId, name, ToolBlockStatus.RUNNING, args.toString()))
            val denied = DiscussionGraph.denyExecution(name, args.toString())
            val result = if (denied != null) {
                ToolExecutionResult(denied, false)
            } else if (toolGate != null) {
                toolGate.withLock { executeTool(name, args.toString(), "", mutableListOf(), "", "") }
            } else {
                executeTool(name, args.toString(), "", mutableListOf(), "", "")
            }
            results += AgentContentPart.ToolResult(callId, name, result.output, isError = !result.success)
        }
        history += LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = results)
    }
    return GroupChat.recoverUtterance(report.toString(), lastThinking)
}

/**
 * [group-chat-debate] 清掉仍是排队/思考态的占位气泡（被关门截断、没轮到发言的成员）。
 * 已定型（正文/PASS/留痕）的气泡不受影响。
 */
private suspend fun ChatViewModel.clearGroupQueue(ids: Map<String, String>) {
    withContext(Dispatchers.Main) {
        val alive = _messages.value
            .filter { message -> message.id in ids.values && message.isStreaming }
            .map { it.id }
            .toSet()
        if (alive.isNotEmpty()) {
            _messages.value = _messages.value.filterNot { it.id in alive }
        }
    }
}

private fun thinkingStatus(thinking: StringBuilder) = AssistantBlock(
    id = "group-thinking",
    kind = "thinking",
    content = thinking.toString().takeLast(600),
    toolTitle = "Thinking",
)

private fun toolStatus(
    id: String,
    name: String,
    status: ToolBlockStatus,
    args: String,
) = AssistantBlock(
    id = id.ifBlank { "group-tool" },
    kind = "tool_use",
    toolName = name,
    toolTitle = name,
    toolArgs = args.take(240),
    toolStatus = status,
    content = name,
)

private suspend fun ChatViewModel.showGroupStatus(
    id: String,
    speaker: String,
    vendor: String,
    block: AssistantBlock,
) {
    withContext(Dispatchers.Main) {
        val current = _messages.value
        val status = ChatMessage(
            id = id,
            role = "assistant",
            content = "",
            speakerName = speaker,
            speakerVendor = vendor,
            isStreaming = true,
            isAwaitingModelResponse = false,
            toolBlocks = listOf(block),
        )
        val next = if (current.any { it.id == id }) {
            current.map { if (it.id == id) status else it }
        } else {
            current + status
        }
        _messages.value = trimLoadedWindow(next)
    }
}

private fun ChatViewModel.closeRecord(): List<GroupChat.Line> {
    val windowed = groupTranscript()
    if (windowed.isNotEmpty()) return windowed
    // Do not walk back to the previous user question. That slice is the last
    // group chat, and ending an empty new round would make the host report it.
    val start = GroupChat.roundStartIndex(groupSlices(), groupRoundMarkers(), hostSuffix())
    val placeholder = context.getString(com.openminis.app.R.string.group_chat_analyzing)
    val passed = context.getString(com.openminis.app.R.string.group_chat_passed).trim()
    return _messages.value.drop(start).mapNotNull { message ->
        if (message.role != "assistant" || message.isQueued) return@mapNotNull null
        val speaker = message.speakerName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (GroupChat.isHostSpeaker(speaker, hostSuffix())) return@mapNotNull null
        val text = message.content.trim().ifBlank {
            message.toolBlocks
                .filter { it.kind == "text" || it.kind == "thinking" }
                .joinToString("\n") { it.content }
                .trim()
        }
        if (
            text.isBlank() ||
            text == placeholder ||
            text == passed ||
            GroupChat.isPass(text) ||
            message.isAwaitingModelResponse
        ) {
            null
        } else {
            GroupChat.Line(speaker, text)
        }
    }
}

private suspend fun ChatViewModel.upsertGroupBubble(
    id: String,
    speaker: String,
    text: String,
    analyzing: Boolean,
    vendor: String = GroupChat.VENDOR_UNKNOWN,
) {
    withContext(Dispatchers.Main) {
        val current = _messages.value
        val next = if (current.any { it.id == id }) {
            current.map {
                if (it.id == id) it.copy(
                    content = text,
                    toolBlocks = if (analyzing) it.toolBlocks else emptyList(),
                    speakerName = speaker,
                    speakerVendor = vendor,
                    isStreaming = analyzing,
                    isAwaitingModelResponse = analyzing,
                ) else it
            }
        } else {
            current + ChatMessage(
                id = id,
                role = "assistant",
                content = text,
                speakerName = speaker,
                speakerVendor = vendor,
                isStreaming = analyzing,
                isAwaitingModelResponse = analyzing,
            )
        }
        _messages.value = trimLoadedWindow(next)
    }
}

private suspend fun ChatViewModel.removeGroupBubble(id: String) {
    withContext(Dispatchers.Main) {
        _messages.value = _messages.value.filterNot { it.id == id }
    }
}

private suspend fun ChatViewModel.publishGroupNotice(text: String) {
    val id = UUID.randomUUID().toString()
    val speaker = context.getString(com.openminis.app.R.string.group_chat_host_suffix)
    upsertGroupBubble(id, speaker, text, analyzing = false)
    val dbId = persistGroupUtterance(speaker, text, snapshotFor(null))
    withContext(Dispatchers.Main) {
        _messages.value = _messages.value.map {
            if (it.id == id) it.copy(isStreaming = false, sourceDbIds = listOfNotNull(dbId)) else it
        }
    }
}

private suspend fun ChatViewModel.persistGroupUtterance(
    speaker: String,
    text: String,
    snapshot: ModelAttributionSnapshot?,
    vendor: String = GroupChat.vendorKey(snapshot?.modelId, snapshot?.displayName),
): String? = withContext(Dispatchers.IO) {
    val parts = JSONArray()
        .put(
            JSONObject()
                .put("type", GroupChat.SPEAKER_PART)
                .put("value", speaker)
                .put("vendor", vendor),
        )
        .put(JSONObject().put("type", "text").put("value", text))
        .toString()
    chatRepository.appendMessage(
        realSessionId.ifEmpty { sessionId },
        "assistant",
        parts,
        modelSnapshot = snapshot,
    ).id
}

private fun ChatViewModel.snapshotFor(entry: ModelEntry?): ModelAttributionSnapshot? {
    val model = entry?.model ?: currentModel ?: return null
    val instance = entry?.let { providerRepository.instance(it.providerInstanceId) }
        ?: _activeEntryId.value?.let { id ->
            providerRepository.config.value.modelEntries.find { it.id == id }
        }?.let { providerRepository.instance(it.providerInstanceId) }
    return ModelAttributionSnapshot(
        modelId = model.id,
        displayName = model.displayName,
        providerTypeRaw = instance?.providerType?.name ?: "",
        providerInstanceId = entry?.providerInstanceId,
    )
}

internal fun ChatViewModel.anchorGroupRound(): Boolean {
    val anchor = _messages.value.lastOrNull { !it.isQueued }
        ?.let { stableGroupMessageId(it) }
        ?.takeIf { it.isNotBlank() }
        ?: return false
    groupChatRoundStartId = anchor
    groupChatPrefs().edit().putString(roundStartKey(), anchor).apply()
    clearHostOpening()
    groupChatAnchorPending = false
    return true
}

internal fun ChatViewModel.stableGroupMessageId(message: ChatMessage): String =
    message.sourceDbIds.lastOrNull { it.isNotBlank() } ?: message.id

private fun ChatViewModel.hostSuffix(): String =
    context.getString(com.openminis.app.R.string.group_chat_host_suffix)

private fun ChatViewModel.groupRoundMarkers(): List<String?> =
    listOf(groupChatClosedAfterId, groupChatRoundStartId)

private fun ChatViewModel.groupSlices(): List<GroupChat.ContextMessage> =
    _messages.value.map { message ->
        GroupChat.ContextMessage(
            id = message.id,
            role = message.role,
            content = message.content,
            speakerName = message.speakerName,
            sourceIds = message.sourceDbIds,
            queued = message.isQueued,
            awaiting = message.isAwaitingModelResponse,
        )
    }

private fun ChatViewModel.groupTranscript(): List<GroupChat.Line> =
    GroupChat.currentSpeeches(
        groupSlices(),
        groupRoundMarkers(),
        hostSuffix(),
        hiddenTexts = setOf(
            context.getString(com.openminis.app.R.string.group_chat_passed),
            context.getString(com.openminis.app.R.string.group_chat_analyzing),
        ),
    )

private fun ChatViewModel.recentContext(): String =
    GroupChat.currentConversation(groupSlices())
