package com.openminis.app.ui.chat

import com.openminis.app.data.db.AppDatabase
import com.openminis.app.tools.CodeGraphTool
import com.openminis.app.tools.SubAgentKind
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileCheckpointTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.CronJobTool
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.ToolOutputPolicy
import com.openminis.app.security.SecurityGateHolder
import org.json.JSONObject

internal suspend fun ChatViewModel.executeTool(
    name: String,
    argsJson: String,
    toolId: String,
    toolBlocks: MutableList<AssistantBlock>,
    assistantId: String,
    currentText: String,
): ToolExecutionResult {
    // T330: tri-state permission gating moved into the offload IPC
    // handler (OffloadGate). The CLIs land there whether the LLM
    // emitted a named tool call or a raw shell command, so the gate
    // is consistent across both paths. The pre-check that lived here
    // (`permissionTools = {calendar, location, …}`) was effectively
    // dead since these tools have no native ChatViewModel executor
    // — they always fall through to shell_execute or the offload
    // bridge, which is now where checkPermission runs.
    val canonical = com.openminis.app.security.ToolAliases.canonical(name)
    val gated = com.openminis.app.security.SecurityGateHolder.intercept(
        context, canonical, argsJson, activeSessionId,
    )
    if (gated != null) return gated
    val toolTitle = try { JSONObject(argsJson).optString("tool_title", canonical) } catch (_: Exception) { canonical }

    // [T-find-tools] On-demand tool discovery: resolve the query against the
    // full registry and enable matches for subsequent turns in this session.
    if (canonical == com.openminis.app.tools.FindTools.NAME) {
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: JSONObject()
        val query = args.optString("query", "")
        if (query.isBlank()) {
            return ToolExecutionResult(
                "find_tools requires a non-empty query.",
                false,
                errorCode = com.openminis.app.tools.ToolErrorCode.INVALID_ARGUMENTS,
                recoveryHint = "Provide a capability keyword such as calendar, image, or cron.",
                toolTitle = com.openminis.app.tools.FindTools.NAME,
            )
        }
        val limit = args.optInt("limit", 8)
        return findAndEnableTools(query, limit)
    }

    val result = when (canonical) {
        FileReadTool.NAME -> {
            val result = FileReadTool.execute(argsJson, activeSessionId, context)
            // Record skill usage when SKILL.md under /var/minis/skills/<id>/ is read.
            if (result.success) {
                runCatching {
                    val readPath = JSONObject(argsJson).optString("path", "")
                    if (readPath.isNotEmpty()) {
                        skillRepository?.skillIdFromPath(readPath)?.let { sid ->
                            skillRepository.recordSkillUse(sid)
                        }
                    }
                }
            }
            result
        }
        FileWriteTool.NAME -> FileWriteTool.execute(argsJson, activeSessionId, context).also { if (it.success) maybeReloadSkillsForPath(argsJson) }
        FileEditTool.NAME -> FileEditTool.execute(argsJson, activeSessionId, context).also { if (it.success) maybeReloadSkillsForPath(argsJson) }
        FileCheckpointTool.NAME -> FileCheckpointTool.execute(argsJson, activeSessionId, context)
        com.openminis.app.tools.MultiEditTool.NAME -> com.openminis.app.tools.MultiEditTool.execute(argsJson, activeSessionId, context).also { if (it.success) maybeReloadSkillsForPath(argsJson) }
        com.openminis.app.tools.ListDirTool.NAME -> com.openminis.app.tools.ListDirTool.execute(argsJson, activeSessionId, context)
        com.openminis.app.tools.GrepTool.NAME, com.openminis.app.tools.GrepSourceTool.NAME ->
            com.openminis.app.tools.GrepTool.execute(argsJson, activeSessionId, context)
        com.openminis.app.tools.GlobTool.NAME -> com.openminis.app.tools.GlobTool.execute(argsJson, activeSessionId, context)
        com.openminis.app.tools.WebFetchTool.NAME -> com.openminis.app.tools.WebFetchTool.execute(argsJson)
        // [T-ui-read] GUI Agent 的眼睛：a11y 读屏快照，配合 shell_execute 里
        // 的 android-a11y-cli 点击/输入形成"看屏→决策→执行→再看"闭环。
        com.openminis.app.tools.UiReadTool.NAME ->
            com.openminis.app.tools.UiReadTool.execute(argsJson)
        // [T-ui-action] GUI Agent 的手：tap/type/swipe/scroll/back/open_app/open_url。
        com.openminis.app.tools.UiActionTool.NAME ->
            com.openminis.app.tools.UiActionTool.execute(argsJson)
        // [T-ui-skill-wired] ui_skill was REGISTERED in AgentTools (and its
        // description even tells the model to prefer it over manual
        // ui_read+ui_action) but had NO dispatch branch here — every call
        // fell through to "Unknown tool: ui_skill". The whole
        // record→match→replay pipeline (A11ySkillStore + SkillReplayEngine)
        // was dead code in production. forContext() is a process-wide
        // singleton (see A11ySkillStore), so concurrent ui_skill calls and
        // any future settings-page wiring share one lock-guarded instance.
        com.openminis.app.tools.UiSkillTool.NAME ->
            com.openminis.app.tools.UiSkillTool.execute(
                argsJson,
                com.openminis.app.accessibility.A11ySkillStore.forContext(context),
            )
        // T178: pass sessionId + context so read_image routes through
        // resolveSessionHostPath like file_read/write/edit do — without
        // these, the tool consults the global last-writer-wins
        // bindMounts map and would surface another session's
        // /var/minis/{workspace,attachments,offloads,browser} files.
        ReadImageTool.NAME -> executeReadImageTool(argsJson)
        "shell_execute", "shell_exec", "env_exec" -> {
            executeShellCommand(argsJson, toolId, toolBlocks, assistantId, currentText)
        }
        "su_exec" -> {
            val o = JSONObject(argsJson)
            val cmd = o.optString("command")
            val quoted = "'" + cmd.replace("'", "'\\''") + "'"
            o.put("command", "android-su -c " + quoted)
            executeShellCommand(o.toString(), toolId, toolBlocks, assistantId, currentText)
        }
        "browser_use" -> executeBrowserUseTool(argsJson)
        "memory_write", "save_memory" -> executeMemoryWriteTool(argsJson)
        "memory_get", "recall_memory" -> executeMemoryGetTool(argsJson)
        // Evolution was previously reachable only through Settings; the
        // agent that gathers the evidence had no way to inspect or decide
        // a proposal. See EvolutionTool for why this is read-mostly.
        com.openminis.app.tools.EvolutionTool.NAME ->
            com.openminis.app.tools.EvolutionTool.execute(
                argsJson,
                com.openminis.app.evolution.EvolutionHooks.engine,
            )
        com.openminis.app.tools.DispatchAgentsTool.NAME -> executeRunSubAgent(
            com.openminis.app.tools.DispatchAgentsTool.toSpawnArgs(argsJson, context),
            toolId, toolBlocks, assistantId, currentText,
        )
        com.openminis.app.tools.WolfpackTool.NAME -> executeRunSubAgent(
            com.openminis.app.tools.WolfpackTool.toSpawnArgs(argsJson),
            toolId, toolBlocks, assistantId, currentText,
        )
        com.openminis.app.tools.AgentPlanTool.NAME -> com.openminis.app.tools.AgentPlanTool.execute(
            argsJson,
            activeSessionId,
            context,
        )
        // [T-subagent-background] Inspect / wait for / collect detached waves.
        // Suspends only for op=await, which polls the registry under a bounded
        // timeout; every other op returns immediately.
        com.openminis.app.tools.CheckAgentTool.NAME ->
            com.openminis.app.tools.CheckAgentTool.execute(argsJson, activeSessionId, context)
        com.openminis.app.tools.GoalTool.NAME -> com.openminis.app.tools.GoalTool.execute(
            argsJson,
            activeSessionId,
            com.openminis.app.goal.GoalManager(chatRepository),
        )
        // [T-mcp-native] In-process MCP servers/tools/call — replaces the
        // shell_execute → minis-mcp-cli → daemon round trip for main chats.
        com.openminis.app.tools.McpNativeTool.NAME ->
            com.openminis.app.tools.McpNativeTool.execute(
                argsJson,
                context,
                activeSessionId,
                mcpRepository,
            )

        // [T-ssh-backend] Remote SSH exec/SFTP. Blocking JSch I/O is confined
        // to Dispatchers.IO inside the tool.
        com.openminis.app.tools.SshTool.NAME ->
            com.openminis.app.tools.SshTool.execute(argsJson, activeSessionId, context)
        com.openminis.app.tools.InvokeSkillTool.NAME ->
            com.openminis.app.tools.InvokeSkillTool.execute(argsJson, skillRepository, activeSessionId)
        com.openminis.app.tools.SkillManageTool.NAME ->
            com.openminis.app.tools.SkillManageTool.execute(argsJson, skillRepository)
        com.openminis.app.tools.AskReasoningTool.NAME ->
            com.openminis.app.tools.AskReasoningTool.execute(argsJson) { sys, user -> oneShotAsk(sys, user) }
        com.openminis.app.tools.ExecuteCodeTool.NAME ->
            com.openminis.app.tools.ExecuteCodeTool.execute(argsJson) { n, a ->
                executeTool(n, a, toolId, toolBlocks, assistantId, currentText)
            }
        com.openminis.app.tools.ProductMediaTools.DESCRIBE_IMAGE -> executeReadImageTool(argsJson)
        com.openminis.app.tools.ProductMediaTools.GENERATE_IMAGE ->
            executeGenerateImageTool(argsJson, currentProvider, activeSessionId)
        com.openminis.app.tools.ProductMediaTools.GENERATE_VIDEO ->
            executeGenerateVideoTool(argsJson, currentProvider, activeSessionId)
        com.openminis.app.tools.ProductMediaTools.TRANSCRIBE_AUDIO ->
            com.openminis.app.tools.ProductMediaTools.notConfigured(
                com.openminis.app.tools.ProductMediaTools.TRANSCRIBE_AUDIO,
                "Attach audio in chat or configure a speech model.",
            )
        com.openminis.app.tools.ProductMediaTools.TRANSLATE_TEXT -> {
            val o = JSONObject(argsJson)
            val text = o.optString("text")
            val lang = o.optString("target_lang")
            val out = oneShotAsk(
                "You are a translator. Return only the translation into $lang, no preface.",
                text,
            )
            ToolExecutionResult(out, true, toolTitle = "translate_text")
        }
        CronJobTool.NAME -> CronJobTool.execute(argsJson, context)
        SubAgentKind.SPAWN_AGENT, SubAgentKind.RUN_SUBAGENT ->
            executeRunSubAgent(argsJson, toolId, toolBlocks, assistantId, currentText)
        com.openminis.app.tools.WebSearchTool.NAME,
        com.openminis.app.tools.OcrTool.NAME,
        com.openminis.app.tools.ScreenTimeTool.NAME,
        -> dispatchHostTool(canonical, argsJson, activeSessionId, context)
            ?: ToolExecutionResult("unhandled host tool", false)
        com.openminis.app.tools.SessionLookupTool.SEARCH -> com.openminis.app.tools.SessionLookupTool.executeSearch(
            argsJson, activeSessionId, context,
        )
        com.openminis.app.tools.SessionLookupTool.READ -> com.openminis.app.tools.SessionLookupTool.executeRead(
            argsJson, activeSessionId, context,
        )
        com.openminis.app.tools.AskUserQuestion.NAME, com.openminis.app.tools.AskUserQuestion.ALIAS -> executeAskUserQuestion(argsJson)
        CodeGraphTool.NAME -> {
            val args = JSONObject(argsJson).let { json ->
                buildMap<String, String> {
                    json.optString("action").takeIf { it.isNotBlank() }?.let { put("action", it) }
                    json.optString("name").takeIf { it.isNotBlank() }?.let { put("name", it) }
                    json.optString("path").takeIf { it.isNotBlank() }?.let { put("path", it) }
                }
            }
            // [T-code-graph-honest-result] This used to wrap the call in
            // runCatching and report `success = true` either way, so a failed
            // index query (locked DB, unresolvable path) reached the model as a
            // normal result carrying an error sentence, and the
            // `!result.success` evolution hook never fired.
            runCatching {
                CodeGraphTool.execute(
                    args = args,
                    sessionId = activeSessionId,
                    context = context,
                    dao = AppDatabase.getInstance(context).codeIndexDao(),
                    resolvePath = { path ->
                        com.openminis.app.sandbox.PRootKernel.resolveSessionHostPath(
                            activeSessionId, path, context
                        )?.path
                    },
                )
            }.fold(
                onSuccess = { ToolExecutionResult(it, true, toolTitle = "code_graph") },
                onFailure = { e ->
                    ToolExecutionResult(
                        "code_graph 执行失败: ${e.message}",
                        false,
                        errorCode = com.openminis.app.tools.ToolErrorCode.EXECUTION_FAILED,
                        toolTitle = "code_graph",
                    )
                },
            )
        }
        else -> if (com.openminis.app.plugins.OnlineApiTool.isOnline(name)) {
            com.openminis.app.plugins.OnlineApiTool.execute(name, argsJson, context)
        } else {
            // [T-local-tool-plugins] User-defined command-template tools:
            // dispatch by name, materialize {{param}} placeholders, then run
            // the assembled command through the normal shell pipeline (the
            // SecurityGate still audits the materialized command there).
            val localTool = com.openminis.app.plugins.LocalToolPluginStore.find(context, canonical)
            if (localTool != null) {
                executeLocalTool(localTool, argsJson, toolId, toolBlocks, assistantId, currentText)
            } else {
                ToolExecutionResult(
                    "Unknown tool: $name",
                    false,
                    errorCode = com.openminis.app.tools.ToolErrorCode.UNKNOWN_TOOL,
                    recoveryHint = "Use find_tools to discover which tools are available for this task.",
                )
            }
        }
    }
    if (!result.success) {
        com.openminis.app.evolution.EvolutionHooks.onToolFailure(
            activeSessionId, canonical, argsJson, result.output,
        )
    }
    // [T-tool-output-policy] 统一出口：环境变量脱敏 + 超长输出降级为可检索文件。
    if (result.imageData == null) {
        return result.copy(output = ToolOutputPolicy.apply(result.output, toolId))
    }
    return result
}

/**
 * [T-local-tool-plugins] Execute a user-defined command-template tool:
 * substitute `{{param}}` placeholders with the call arguments, then hand the
 * materialized command to [executeShellCommand] so the local tool inherits
 * the full shell pipeline — SecurityGate audit, offload execution, output
 * policy, tool-block lifecycle.
 */
private suspend fun ChatViewModel.executeLocalTool(
    tool: com.openminis.app.plugins.LocalToolPluginStore.LocalToolDef,
    argsJson: String,
    toolId: String,
    toolBlocks: MutableList<AssistantBlock>,
    assistantId: String,
    currentText: String,
): ToolExecutionResult {
    val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: JSONObject()
    // Only params the model actually supplied — materialize applies defaults
    // and flags missing required ones.
    val argMap = tool.params.mapNotNull { p ->
        if (args.has(p.name)) p.name to args.optString(p.name) else null
    }.toMap()
    return when (val m = com.openminis.app.plugins.LocalToolPluginStore.materialize(tool, argMap)) {
        is com.openminis.app.plugins.LocalToolPluginStore.MaterializeResult.Ok -> {
            val shellArgs = JSONObject()
                .put("command", m.command)
                .put("tool_title", tool.name)
            executeShellCommand(shellArgs.toString(), toolId, toolBlocks, assistantId, currentText)
        }
        is com.openminis.app.plugins.LocalToolPluginStore.MaterializeResult.MissingParam ->
            ToolExecutionResult(
                "local tool '${tool.name}' is missing required param '${m.param}'.",
                false,
                errorCode = com.openminis.app.tools.ToolErrorCode.INVALID_ARGUMENTS,
                recoveryHint = "Provide '${m.param}' in the tool call arguments.",
                toolTitle = tool.name,
            )
        is com.openminis.app.plugins.LocalToolPluginStore.MaterializeResult.UnsafeParam ->
            ToolExecutionResult(
                "param '${m.param}' of local tool '${tool.name}' carries shell metacharacters " +
                    "(semicolon, pipe, ampersand, dollar, backtick, redirect or newline) and was refused.",
                false,
                errorCode = com.openminis.app.tools.ToolErrorCode.INVALID_ARGUMENTS,
                recoveryHint = "Pass a plain value without shell metacharacters, or have the tool " +
                    "author quote the placeholder in the template.",
                toolTitle = tool.name,
            )
    }
}
