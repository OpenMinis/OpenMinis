package com.openminis.app.mcp.server.tools

import com.openminis.app.mcp.server.McpServerCore
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.security.Decision
import com.openminis.app.security.PermissionMode
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-mcp-refactor] Pure helpers shared by every domain tool file. Everything
 * here is side-effect free except [executeInGuest] / [gateAndRun], which are
 * the two guest-touching primitives the old 1280-line dispatcher duplicated
 * inline in every handler.
 */
internal object McpToolShared {

    const val SESSION_ID = "mcp-server"
    const val MAX_FILE_READ_CHARS = 80_000
    const val MAX_FILE_WRITE_CHARS = 262_144
    val FILE_READ_PREFIXES = listOf("/var/minis/", "/sdcard/")

    const val SHELL_STRICT_HINT =
        "当前 shell 策略为 strict，命令需要用户确认（MCP 通道无法弹确认）。" +
            "可在 设置 → MCP → 内置 Server 把 shell 策略切到 auto。"

    const val MAX_COORD = 20_000
    val UI_ACTIONS = setOf("tap", "long_press", "type", "swipe", "scroll", "back", "open_app", "open_url")
    val SCROLL_DIRECTIONS = setOf("up", "down", "left", "right")

    fun text(content: String): JSONObject = JSONObject().apply {
        put("type", "text")
        put("text", content)
    }

    fun error(msg: String): McpServerCore.CallResult = McpServerCore.CallResult(
        JSONArray().put(text(msg)),
        isError = true,
    )

    /**
     * Shell-escape a value into a single-quoted POSIX word: wrap in single
     * quotes and turn each embedded quote into '\''. Every string
     * interpolated into a shell command must go through this.
     */
    fun shellEscape(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /**
     * Validate a screen coordinate coming from JSON. Accepts only true
     * integers (JSON numbers without a fraction, or numeric strings) in
     * [0, MAX_COORD]; rejects everything else with a reason instead of
     * silently substituting an empty string.
     */
    fun validateCoord(raw: Any?, coordName: String): Pair<Int?, String?> {
        if (raw == null || raw == JSONObject.NULL) return null to "missing '$coordName' coordinate"
        val d: Double = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
                ?: return null to "'$coordName' is not a number: '$raw'"
            else -> return null to "'$coordName' has unexpected type: ${raw::class.simpleName}"
        }
        if (d != d.toLong().toDouble()) return null to "'$coordName' is not an integer: $d"
        val i = d.toInt()
        if (i < 0) return null to "'$coordName' is negative: $i"
        if (i > MAX_COORD) return null to "'$coordName' exceeds maximum $MAX_COORD: $i"
        return i to null
    }

    /** Pure path validation. Returns null when allowed, else a deny reason. */
    fun validateFilePath(rawPath: String): String? {
        val normalized = normalizePath(rawPath)
        if (normalized.isEmpty()) return "path is empty"
        val allowed = FILE_READ_PREFIXES.any { prefix ->
            val root = prefix.trimEnd('/')
            normalized == prefix || normalized == root || normalized.startsWith(prefix)
        }
        if (!allowed) {
            return "path '$normalized' is outside allowed prefixes (${FILE_READ_PREFIXES.joinToString()})"
        }
        val segments = normalized.split('/')
        if (segments.any { it == ".." }) return "path contains '..'"
        return null
    }

    /** Normalize slashes and collapse '.'/'..' segments (no filesystem access). */
    fun normalizePath(path: String): String {
        var p = path.trim().replace('\\', '/')
        while (p.contains("//")) p = p.replace("//", "/")
        val segments = mutableListOf<String>()
        for (seg in p.split('/')) {
            when {
                seg.isEmpty() || seg == "." -> Unit
                seg == ".." -> {
                    // [T-removelast-jdk21] NOT removeLast(): binds to the JDK 21+
                    // SequencedCollection member on CI's test JVM and is absent
                    // from Android's java.util. removeAt(index) is everywhere.
                    if (segments.isNotEmpty() && segments.last() != "..") segments.removeAt(segments.size - 1)
                    else if (segments.isEmpty()) return "" // escaping above root
                }
                else -> segments.add(seg)
            }
        }
        val body = segments.joinToString("/")
        return when {
            !p.startsWith("/") -> body
            body.isEmpty() -> "/"
            p.endsWith("/") -> "/$body/"
            else -> "/$body"
        }
    }

    /**
     * classify → decide → audit, then run [onAllowed] when the gate allows.
     * Denied / NeedConfirm both map to -32001 with the reason as errorData —
     * the MCP channel has no interactive approval path. This is the ONE copy
     * of the gate dance (the old dispatcher inlined it four times).
     */
    fun gateAndRun(
        deps: McpToolDeps,
        toolName: String,
        command: String,
        onAllowed: () -> McpServerCore.CallResult,
    ): McpServerCore.CallResult {
        val argsJson = JSONObject().put("command", command).toString()
        val gateCommand = deps.gate.classify(toolName, argsJson)
        val mode = deps.shellMode()
        val decision = deps.gate.decide(gateCommand, mode)
        deps.gate.audit(gateCommand, decision, null)
        when (decision) {
            is Decision.Denied -> {
                return McpServerCore.CallResult(
                    JSONArray().put(text("SecurityGate denied: ${decision.reason}")),
                    isError = true,
                    errorCode = McpServerCore.ErrorCode.GATE_DENIED,
                    errorData = decision.reason,
                )
            }
            is Decision.NeedConfirm -> {
                val preview = decision.preview.take(240).ifBlank { decision.reason }
                // Under strict the ask-for-approval verdict is expected policy
                // behavior — append a hint pointing at the settings toggle.
                val policyHint = if (mode == PermissionMode.ASK) " $SHELL_STRICT_HINT" else ""
                return McpServerCore.CallResult(
                    JSONArray().put(
                        text(
                            "This command requires user approval which is not available on the " +
                                "MCP server channel. Reason: ${decision.reason}. Preview: $preview.$policyHint",
                        ),
                    ),
                    isError = true,
                    errorCode = McpServerCore.ErrorCode.GATE_DENIED,
                    errorData = decision.reason,
                )
            }
            is Decision.Allow -> Unit
        }
        return onAllowed()
    }

    /**
     * Execute a shell command inside the PRoot guest via [ExecutionCoordinator].
     * Runs for SESSION_ID ("mcp-server") — its workspace dirs are materialized
     * at server start. runBlocking suffices: the dispatcher has no coroutine
     * context of its own.
     */
    /**
     * Raw guest execution — returns the coordinator result (output/exitCode/
     * durationMs). Callers that need the raw fields (shell_exec) use this;
     * [executeInGuest] wraps it into a CallResult.
     */
    fun runInGuest(script: String, timeoutSec: Int = 30): ExecutionCoordinator.CommandResult =
        runCatching {
            runBlocking {
                ExecutionCoordinator.execute(
                    sessionId = SESSION_ID,
                    command = script,
                    timeout = timeoutSec * 1000L,
                )
            }
        }.getOrElse {
            ExecutionCoordinator.CommandResult(
                output = "Execution failed: ${it.message}",
                exitCode = 124,
                durationMs = 0L,
            )
        }

    fun executeInGuest(script: String, timeoutSec: Int = 30): McpServerCore.CallResult {
        val result = runInGuest(script, timeoutSec)
        val output = if (result.output.isNotBlank()) result.output.trim() else "(no output)"
        return McpServerCore.CallResult(
            JSONArray().put(text(output)),
            isError = result.exitCode != 0,
        )
    }
}
