package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Nested agent loop used by [spawn_agent]. Sub-agents do not receive the
 * parent conversation and must not spawn further sub-agents.
 */
object SubAgentRunner {

    /** Absolute safety ceiling so a runaway loop cannot burn tokens forever. */
    const val ABSOLUTE_MAX_TURNS = 200

    /**
     * Live rows for the detail page. The parent card still uses [onStep];
     * this stream is what makes thinking, a tool call, and execution distinct.
     */
    sealed class UiEvent {
        data class Phase(val label: String, val tool: String = "") : UiEvent()
        data class Thinking(val stepId: String, val delta: String) : UiEvent()
        data class Text(val stepId: String, val delta: String) : UiEvent()
        data class ToolStart(val id: String, val name: String) : UiEvent()
        data class ToolArgs(val id: String, val name: String, val args: String) : UiEvent()
        data class ToolRunning(val id: String, val name: String, val args: String) : UiEvent()
        data class ToolDone(
            val id: String,
            val name: String,
            val success: Boolean,
            val output: String,
        ) : UiEvent()
    }

    /** Fraction of budget consumed at which a <budget_warning> is injected. */
    private const val WARN_FRACTION = 0.80

    /** Fraction at which the agent is ordered to stop calling tools and write up. */
    private const val FORCE_FRACTION = 0.95

    /** Cap on a single tool-result / report chunk kept in history (tail kept).
     *  Generous: truncation here is a last-resort runaway guard, not a
     *  content policy — the coordinator asked for full reports. */
    private const val MAX_REPORT_CHARS = 64_000

    suspend fun run(
        provider: LLMProvider,
        modelDisplayName: String,
        userPrompt: String,
        role: String?,
        skillsHint: String?,
        tools: List<AgentToolDefinition>,
        maxTokens: Int,
        executeTool: suspend (name: String, argsJson: String) -> ToolExecutionResult,
        onStep: suspend (turn: Int, toolName: String) -> Unit = { _, _ -> },
        onUi: suspend (UiEvent) -> Unit = {},
        kind: String = SubAgentKind.WORKER,
        writePaths: List<String> = emptyList(),
        maxTurns: Int = ABSOLUTE_MAX_TURNS,
        roleContext: Context? = null,
        temperature: Double? = null,
        tokenBudget: SubAgentTokenBudget? = null,
        thinkingLevel: ThinkingLevel = ThinkingLevel.ULTRA,
    ): ToolExecutionResult {
        val briefed = SubAgentBrief.wrap(userPrompt, kind = kind, role = role, writePaths = writePaths)
        // Defensive: duplicate tool names reach providers as duplicate schemas
        // and some of them reject the payload outright. Keep first-seen order.
        val dedupedTools = SubAgentKind.dedupeByName(tools)
        val history = mutableListOf(
            LLMMessage(role = LLMMessage.Role.USER, content = briefed),
        )
        // Turn budget is whatever the coordinator assigned (auto-sized or
        // explicit), bounded only by ABSOLUTE_MAX_TURNS as a runaway guard —
        // there is no longer a low global settings clamp here.
        val turns = maxTurns.coerceIn(1, ABSOLUTE_MAX_TURNS)
        val system = workerSystemPrompt(modelDisplayName, role, skillsHint, kind, writePaths, turns, roleContext)
        val report = StringBuilder()
        val timeline = StringBuilder()
        var warned = false
        var forced = false

        try {
            var turn = 0
            while (turn < turns) {
                // Shared token budget hard stop (Codex SessionBudgetExceeded
                // semantics): once the pool is exhausted this lane stops
                // immediately and hands in what it has. A sibling that busts
                // the budget stops itself; the wave continues with the rest.
                if (tokenBudget != null && tokenBudget.exhausted) {
                    runCatching { onStep(turn, "token budget exhausted") }
                    val partial = report.toString().trim()
                    val footer = "(stopped: shared token budget exhausted at $turn/$turns turns)"
                    return ToolExecutionResult(composeOutput(partial, timeline.toString(), footer), true)
                }
                turn++
                runCatching { onStep(turn, "") }
                runCatching { onUi(UiEvent.Phase("思考中")) }
                var announcedSpeak = false
                var textSeg = 0
                var thinkSeg = 0
                // Compact accumulated history before it can blow the context
                // window; the freshest tool results stay verbatim.
                val sendHistory = SubAgentHistoryCompactor.compact(history)
                val textSb = StringBuilder()
                val toolCalls = mutableListOf<Triple<String, String, JSONObject>>()
                provider.streamMessage(
                    messages = sendHistory,
                    systemPrompt = system,
                    // Output cap follows the model's own declared ceiling (the
                    // coordinator already sized maxTokens per entry); 8192 was a
                    // blanket cap that clipped long reports on capable models.
                    maxTokens = maxTokens.coerceAtLeast(256),
                    temperature = temperature,
                    tools = dedupedTools,
                    thinkingLevel = thinkingLevel,
                ).collect { chunk ->
                    when (chunk) {
                        is LLMStreamChunk.Text -> {
                            textSb.append(chunk.text)
                            if (!announcedSpeak) {
                                announcedSpeak = true
                                runCatching { onUi(UiEvent.Phase("回复中")) }
                            }
                            runCatching { onUi(UiEvent.Text("t$turn-text-$textSeg", chunk.text)) }
                        }
                        is LLMStreamChunk.ThinkingDelta -> {
                            runCatching { onUi(UiEvent.Phase("思考中")) }
                            runCatching { onUi(UiEvent.Thinking("t$turn-think-$thinkSeg", chunk.text)) }
                        }
                        is LLMStreamChunk.ToolUseStart -> {
                            textSeg++
                            thinkSeg++
                            announcedSpeak = false
                            runCatching { onUi(UiEvent.Phase("调用工具", chunk.name)) }
                            runCatching { onUi(UiEvent.ToolStart(chunk.id, chunk.name)) }
                        }
                        is LLMStreamChunk.ToolInputDelta ->
                            runCatching { onUi(UiEvent.ToolArgs(chunk.id, "", chunk.accumulated)) }
                        is LLMStreamChunk.ToolCallComplete -> {
                            toolCalls.add(Triple(chunk.id, chunk.name, chunk.args))
                            runCatching { onUi(UiEvent.ToolArgs(chunk.id, chunk.name, chunk.args.toString())) }
                        }
                        is LLMStreamChunk.Usage -> {
                            // Shared token budget (Codex rollout_budget semantics,
                            // rewritten): usage is recorded even when it busts the
                            // budget, and the verdict is a HARD stop for this lane,
                            // not an advisory. Prefill weight <1 because cached
                            // context reads are cheap; output weight 1.
                            //
                            // `inputTokens` is already fresh-only by the parser
                            // convention (OpenAIProviderUsage.kt subtracts the
                            // cached portion; Anthropic reports it separately), so
                            // prefill is taken as-is — subtracting cacheRead here
                            // would double-subtract. Gate on any usage field, not
                            // latestContextTokens: Gemini never sets that field.
                            if (tokenBudget != null &&
                                (chunk.usage.outputTokens > 0 || chunk.usage.inputTokens > 0)
                            ) {
                                tokenBudget.recordUsage(
                                    outputTokens = chunk.usage.outputTokens,
                                    prefillTokens = chunk.usage.inputTokens,
                                )
                            }
                        }
                        else -> Unit
                    }
                }

                val text = textSb.toString().trim()
                if (text.isNotEmpty()) {
                    if (report.isNotEmpty()) report.append("\n\n")
                    report.append(text)
                }

                if (toolCalls.isEmpty()) {
                    runCatching { onStep(turn, "done") }
                    return ToolExecutionResult(composeOutput(report.toString(), timeline.toString()), true)
                }

                val assistantParts = mutableListOf<AgentContentPart>()
                if (text.isNotEmpty()) assistantParts.add(AgentContentPart.Text(text))
                for ((id, name, args) in toolCalls) {
                    assistantParts.add(AgentContentPart.ToolUse(id, name, input = args))
                }
                history.add(
                    LLMMessage(
                        role = LLMMessage.Role.ASSISTANT,
                        content = text,
                        contentParts = assistantParts,
                    ),
                )

                val resultParts = mutableListOf<AgentContentPart>()
                for ((id, name, args) in toolCalls) {
                    if (SubAgentKind.isSpawnTool(name) || SubAgentKind.blocks(kind, name)) {
                        runCatching { onStep(turn, "$name · blocked") }
                        runCatching {
                            onUi(UiEvent.ToolDone(id, name, false, "子代理不能再派生子代理，也不能使用被禁止的工具。"))
                        }
                        timeline.append("- turn $turn: $name (blocked)\n")
                        resultParts.add(
                            AgentContentPart.ToolResult(
                                id = id,
                                name = name,
                                content = "Error: sub-agents cannot spawn further sub-agents or use blocked tools. Complete the assigned work yourself.",
                                isError = true,
                            ),
                        )
                        continue
                    }
                    // [T-subagent-browser-readonly-actions] browser_use is
                    // allow-listed for read-only kinds, but its mutating
                    // actions are not read-only. Same shape as the shell gate.
                    if (SubAgentKind.isReadOnly(kind) && name == "browser_use") {
                        val denied = SubAgentKind.readOnlyBrowserDenial(
                            runCatching { args.optString("action") }.getOrDefault(""),
                        )
                        if (denied != null) {
                            runCatching { onUi(UiEvent.ToolDone(id, name, false, denied)) }
                            timeline.append("- turn $turn: browser_use (read-only denied)\n")
                            resultParts.add(
                                AgentContentPart.ToolResult(
                                    id = id,
                                    name = name,
                                    content = denied,
                                    isError = true,
                                ),
                            )
                            continue
                        }
                    }
                    val argsJson = args.toString()
                    if (SubAgentKind.isReadOnly(kind) && name in setOf("shell_execute", "shell_exec", "env_exec")) {
                        val denied = SubAgentKind.readOnlyShellDenial(
                            runCatching { org.json.JSONObject(argsJson).optString("command") }.getOrDefault(""),
                        )
                        if (denied != null) {
                            runCatching { onUi(UiEvent.ToolDone(id, name, false, denied)) }
                            timeline.append("- turn $turn: $name (read-only denied)\n")
                            resultParts.add(
                                AgentContentPart.ToolResult(id = id, name = name, content = denied, isError = true),
                            )
                            continue
                        }
                    }
                    val preview = previewToolArgs(argsJson)
                    runCatching {
                        onStep(turn, buildString {
                            append(name)
                            if (preview.isNotBlank()) append(" · ").append(preview)
                        })
                    }
                    runCatching { onUi(UiEvent.ToolRunning(id, name, argsJson)) }
                    val result = try {
                        executeTool(name, argsJson)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ToolExecutionResult("Error: ${e.message ?: e.javaClass.simpleName}", false)
                    }
                    runCatching { onUi(UiEvent.ToolDone(id, name, result.success, result.output)) }
                    runCatching {
                        onStep(turn, buildString {
                            append(if (result.success) "ok" else "fail")
                            append(' ').append(name)
                            val snippet = result.output.replace('\n', ' ').trim().take(80)
                            if (snippet.isNotEmpty()) append(" · ").append(snippet)
                        })
                    }
                    appendTimeline(timeline, turn, name, preview, result)
                    resultParts.add(
                        AgentContentPart.ToolResult(
                            id = id,
                            name = name,
                            content = result.output,
                            isError = !result.success,
                            imageData = result.imageData,
                            imageMimeType = result.imageMimeType,
                            imageLinuxPath = result.imageLinuxPath,
                        ),
                    )
                }
                history.add(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = resultParts,
                    ),
                )

                // Turn-budget advisory so the agent wraps up instead of silently
                // hitting the cap. 80%: warning; 95%: order to stop tool calls
                // and hand in the partial result.
                val frac = turn.toDouble() / turns
                when {
                    frac >= FORCE_FRACTION && !forced -> {
                        forced = true
                        history.add(
                            LLMMessage(
                                role = LLMMessage.Role.USER,
                                content = "<budget_warning used=\"$turn/$turns\" force=\"true\">\n" +
                                    "You are at the final stretch of your turn budget. STOP calling tools NOW and return your findings so far as your final report in THIS turn — partial results are far more valuable than a perfect result you never submit. Lead with what you already confirmed, then list what is still unverified.\n" +
                                    "</budget_warning>",
                            ),
                        )
                    }
                    frac >= WARN_FRACTION && !warned -> {
                        warned = true
                        history.add(
                            LLMMessage(
                                role = LLMMessage.Role.USER,
                                content = "<budget_warning used=\"$turn/$turns\">\n" +
                                    "You have used $turn of your $turns turns (~${(frac * 100).toInt()}%). Prioritize finishing: avoid further broad searches, consolidate what you have, and prepare to submit your report.\n" +
                                    "</budget_warning>",
                            ),
                        )
                    }
                }
            }
            // Loop ended at the budget (95% force message, or a stubborn agent
            // kept calling tools to the last turn). Hand back the accumulated
            // partial report instead of a bare "hit the cap" string.
            val partial = report.toString().trim()
            val footer = if (partial.isNotEmpty()) {
                "(reached the $turns-turn budget — partial report above)"
            } else {
                "(sub-agent reached the $turns-turn budget with no findings to report)"
            }
            return ToolExecutionResult(composeOutput(partial, timeline.toString(), footer), true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolExecutionResult(
                composeOutput(
                    report.toString(),
                    timeline.toString(),
                    "Sub-agent failed: ${e.message ?: e.javaClass.simpleName}",
                ),
                false,
            )
        }
    }

    fun composeOutput(report: String, timeline: String, footer: String = ""): String {
        val body = report.trim()
        val trace = timeline.trim()
        val note = footer.trim()
        val composed = buildString {
            if (trace.isNotEmpty()) {
                append("## Trace\n")
                append(trace)
            }
            if (body.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("## Report\n")
                append(body)
            }
            if (note.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(note)
            }
            if (isEmpty()) append("(sub-agent finished with empty output)")
        }
        return truncate(composed)
    }

    /**
     * Card and detail show the current result, not the step history.
     * The tool result returned to the parent still uses [composeOutput].
     */
    fun cardStep(output: String): String {
        val text = output.trim()
        val marker = "## Report"
        val at = text.indexOf(marker)
        if (at >= 0) {
            return text.substring(at + marker.length).trim().ifEmpty { text }
        }
        if (text.startsWith("## Trace")) return "已结束"
        return text
    }

    private fun appendTimeline(
        timeline: StringBuilder,
        turn: Int,
        name: String,
        preview: String,
        result: ToolExecutionResult,
    ) {
        timeline.append("- turn ").append(turn).append(": ").append(name)
        if (preview.isNotBlank()) {
            timeline.append(" `").append(preview.replace('`', '\'')).append("`")
        }
        timeline.append('\n')
        val snippet = result.output.replace('\n', ' ').trim().take(160)
        if (snippet.isNotEmpty()) {
            timeline.append("  ")
            if (!result.success) timeline.append("fail: ") else timeline.append("→ ")
            timeline.append(snippet)
            timeline.append('\n')
        }
    }

    fun previewToolArgs(argsJson: String): String {
        return try {
            val o = JSONObject(argsJson)
            val raw = when {
                o.has("command") -> o.optString("command")
                o.has("path") -> o.optString("path")
                o.has("query") -> o.optString("query")
                o.has("url") -> o.optString("url")
                o.has("pattern") -> o.optString("pattern")
                else -> argsJson
            }
            raw.replace('\n', ' ').trim().take(80)
        } catch (_: Exception) {
            argsJson.replace('\n', ' ').trim().take(80)
        }
    }

    private fun truncate(text: String): String {
        if (text.length <= MAX_REPORT_CHARS) return text
        return "…(truncated)\n" + text.takeLast(MAX_REPORT_CHARS)
    }

    private fun workerSystemPrompt(
        modelDisplayName: String,
        role: String?,
        skillsHint: String?,
        kind: String,
        writePaths: List<String>,
        turns: Int,
        roleContext: Context? = null,
    ): String {
        val catalog = roleContext?.let { CollabRoles.byName(it, role) } ?: CollabRoles.byName(role)
        val roleLine = when {
            catalog != null -> "Assigned role: ${catalog.name}.\n\n${catalog.prompt}\n\n"
            !role.isNullOrBlank() -> "Assigned role: ${role.trim()}.\n"
            else -> ""
        }
        val skillsLine = skillsHint?.trim()?.takeIf { it.isNotEmpty() }?.let {
            "Read these skills first (file_read `/var/minis/skills/<id>/SKILL.md`): $it\n"
        } ?: ""
        val kindLine = "Kind: $kind.\n"
        // [T-write-guard-fail-closed] Same three states as the brief: usable
        // prefixes only get listed, the poison state is spelled out as a write
        // lock-out instead of pasting WritePathGuard.INVALID into the system prompt.
        val writePrefixes = WritePathGuard.usablePrefixes(writePaths)
        val writeLine = when {
            writePrefixes.isNotEmpty() ->
                "You may only file_write/file_edit under: ${writePrefixes.joinToString()}. " +
                    "Keep shell writes in those prefixes; MINIS_WRITE_PATHS is exported.\n"
            WritePathGuard.isPoisoned(writePaths) ->
                "Your write_paths contained no usable absolute prefix, so file_write/file_edit are " +
                    "DENIED for this worker and MINIS_WRITE_PATHS is not exported. Do not retry " +
                    "writes — report what you could do without them.\n"
            else -> ""
        }
        val toolLine = if (SubAgentKind.isReadOnly(kind)) {
            "- Read-only: file_read, grep_source, and shell_execute for inspection only (date, uname, cat, ls). Do not write files or redirect output into a file."
        } else {
            "- Use tools immediately. Prefer file_read / grep_source / file_edit / file_write / shell_execute."
        }
        return """You are a sub-agent ($kind), not the session coordinator. Model: $modelDisplayName.
${roleLine}${skillsLine}${kindLine}${writeLine}You cannot see the parent conversation. The user prompt is a self-contained brief with ## Task / ## Expected result / ## Constraints / ## Workflow / ## Collaboration.

Turn budget: you have $turns turns. A <budget_warning> will be injected as you approach the limit — treat it as a hard signal to wrap up. Handing in a partial report is far better than running dry mid-task; if forced to stop, lead with confirmed findings, then list unverified leftovers.

Rules:
- Complete ONLY the assigned slice. Do not rewrite unrelated files.
- Do not spawn further sub-agents. spawn_agent / run_subagent are not available and will error if you try.
- Do not delegate reading or summarizing a skill's SKILL.md to another agent — that discipline does not exist here; read it yourself if the brief points you at one.
- Searching/reading source: prefer grep_source (one call = matching lines ± context) over paging file_read through a big file. Old tool outputs are auto-trimmed from your context, so re-read a file if you need it back.
$toolLine
- Follow the brief's Workflow, then return a concise report: what changed, files touched, leftover risks, and whether Expected result passed.
- If you cannot meet the acceptance criteria, say so explicitly and list what failed.
"""
    }
}
