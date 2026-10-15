package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.security.Decision
import com.openminis.app.security.SecurityGateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 聊天代码块「▶ 运行」的 VM 侧执行扩展。
 *
 * 职责链：路由（[CodeBlockRunRouter.plan]，纯函数）→ 门禁（SecurityGate：
 * classify / decide / audit，与 [SecurityGateHolder.intercept] 同一决策核心，
 * 但不做弹窗确认——按钮路径里 NeedConfirm 直接降级为一条错误消息）→
 * ExecutionCoordinator.execute（与代理的 shell_execute 共享同一持久 shell、
 * 同一 per-session Mutex 与资源预算）→ 输出经 [CodeBlockRunRouter.formatOutput]
 * 回填为一条 assistant 消息并持久化。
 *
 * 纯文案拼装放在 [CodeBlockRunTexts]，保持可在纯 JVM 单测里覆盖。
 */
internal object CodeBlockRunTexts {

    /** isStreaming 时的拒绝提示。 */
    fun busyHint(): String = "正在回复中，暂不能运行代码块，请等当前回复结束。"

    /** 门禁拒绝（含会话模式不足）的提示。 */
    fun deniedHint(reason: String): String = "无法运行该代码块：$reason"

    /** 需要确认但按钮路径不弹窗的提示（协调者指定的文案）。 */
    fun needConfirmHint(reason: String): String =
        "该命令需确认（$reason）。运行按钮不会弹窗，请让代理执行或切换权限模式。"

    /** 路由失败的兜底提示（理论不可达：按钮只在 plan 非 null 时显示）。 */
    fun unsupportedHint(lang: String): String = "暂不支持运行该语言的代码块：$lang"
}

/**
 * 聊天流里代码块的「▶ 运行」入口。由 ChatScreen 的 MarkdownText /
 * StreamingMarkdownText 回调调用；fire-and-forget，内部自带全部状态回写。
 *
 * @param lang fence 语言标签（原文，路由层负责归一化）。
 * @param code 代码正文（完整块；运行中状态以该串为 key 回填 UI）。
 */
internal fun ChatViewModel.runCodeBlockInline(lang: String, code: String) {
    if (_isStreaming.value) {
        appendLocalAssistantNotice(CodeBlockRunTexts.busyHint())
        return
    }
    // [T-code-run-state-additive] The run-state set used to be single-slot
    // (setOf(code) on start, emptySet() in finally). Two concurrent runs —
    // the entry gate only checks _isStreaming, which code runs never set —
    // meant the FIRST finisher wiped the SECOND block's spinner while it
    // was still running. Add/remove per code instead of set/clear, and use
    // membership as the re-entry guard for double-taps on the same block.
    if (code in _codeBlockRunState.value) return
    val plan = CodeBlockRunRouter.plan(lang, code, System.currentTimeMillis())
    if (plan == null) {
        appendLocalAssistantNotice(CodeBlockRunTexts.unsupportedHint(lang))
        return
    }

    // 门禁。classify("shell_execute", …) 走 shell 路由（命令风险分级、
    // 隔离边界都在里面）；decide 用该会话的当前权限模式；audit 留痕。
    // 与 SecurityGateHolder.intercept 的差别只有一点：这里不弹审批框，
    // NeedConfirm 直接降级为提示（见 needConfirmHint 的文案）。
    val argsJson = JSONObject().put("command", plan.command).toString()
    val gateCommand = SecurityGateHolder.gate.classify("shell_execute", argsJson)
    val mode = SecurityGateHolder.activeSessionMode(activeSessionId)
    val decision = SecurityGateHolder.gate.withCallerSession(activeSessionId) {
        SecurityGateHolder.gate.decide(gateCommand, mode)
    }
    SecurityGateHolder.gate.audit(gateCommand, decision, null)
    when (decision) {
        is Decision.Denied -> {
            appendLocalAssistantNotice(CodeBlockRunTexts.deniedHint(decision.reason))
            return
        }
        is Decision.NeedConfirm -> {
            appendLocalAssistantNotice(CodeBlockRunTexts.needConfirmHint(decision.reason))
            return
        }
        is Decision.Allow -> Unit
    }

    // 运行中状态：以代码正文为 key（MarkdownText / RenderBlock 的按钮按
    // LocalCodeBlockRunState.current.contains(code) 显示 spinner）。
    com.openminis.app.logging.AppLogger.warning(
        "ChatCodeRun",
        "run start lang=$lang file=${plan.fileName} mode=$mode",
    )
    _codeBlockRunState.value = _codeBlockRunState.value + code

    viewModelScope.launch(Dispatchers.IO) {
        try {
            val result = withContext(Dispatchers.IO) {
                com.openminis.app.sandbox.ExecutionCoordinator.execute(
                    sessionId = activeSessionId,
                    command = plan.command,
                )
            }
            val text = CodeBlockRunRouter.formatOutput(
                displayLang = plan.displayLang,
                durationMs = result.durationMs,
                exitCode = result.exitCode,
                output = result.output,
            )
            appendLocalAssistantNotice(text, persist = true)
        } catch (e: Exception) {
            val text = CodeBlockRunRouter.formatOutput(
                displayLang = plan.displayLang,
                durationMs = 0L,
                exitCode = 1,
                output = "运行失败：${e.message ?: e.javaClass.simpleName}",
            )
            appendLocalAssistantNotice(text, persist = true)
        } finally {
            _codeBlockRunState.value = _codeBlockRunState.value - code
        }
    }
}

/**
 * 往聊天流尾部插一条完整 assistant 消息（非流式占位，直接是最终文本）。
 * 本扩展专用的轻量版：不设 isStreaming / isAwaitingModelResponse，
 * 可选持久化为单个 text part。
 */
private fun ChatViewModel.appendLocalAssistantNotice(
    text: String,
    persist: Boolean = false,
) {
    val id = java.util.UUID.randomUUID().toString()
    _messages.value = trimLoadedWindow(_messages.value + ChatMessage(
        id = id,
        role = "assistant",
        content = text,
    ))
    if (!persist) return
    val sid = realSessionId.ifEmpty { sessionId }
    if (sid.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val parts = """[{"type":"text","value":${escapeJson(text)}}]"""
            chatRepository.appendMessage(
                sessionId = sid,
                role = "assistant",
                partsJson = parts,
                modelSnapshot = currentModelSnapshot(),
            )
        } catch (e: Exception) {
            com.openminis.app.logging.AppLogger.warning(
                "ChatCodeRun",
                "persist run result failed: ${e.message}",
            )
        }
    }
}
