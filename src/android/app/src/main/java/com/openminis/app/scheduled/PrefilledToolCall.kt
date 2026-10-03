package com.openminis.app.scheduled

import com.openminis.app.data.model.LLMStreamChunk
import org.json.JSONObject
import java.util.UUID

/**
 * [T-scheduled-tool-prefill] A tool call a scheduled task makes BEFORE the
 * model is asked anything.
 *
 * Many scheduled tasks are "run this fixed command, then tell me what it
 * says" — a curl for the weather, a disk-usage check. Without this the fired
 * prompt costs two model round-trips: one where the model decides to call the
 * tool (and re-derives arguments that were known when the task was created),
 * one where it summarises the result. A prefilled call is executed up front
 * and handed to the model already answered, so only the summary request is
 * made.
 *
 * The call is not replayed by hand-writing history rows. The agent loop's
 * first turn is fed a scripted chunk stream ([asStreamChunks]) in place of a
 * provider request, so the call travels the exact path a model-emitted call
 * does: the same tool card, the same dispatch, the same persisted
 * tool_use / tool_result rows, the same agentHistory entries. A reloaded
 * session cannot tell the two apart because they were written by the same
 * code.
 *
 * [argsJson] is kept as text rather than a JSONObject so the type has value
 * equality (JSONObject compares by reference).
 */
data class PrefilledToolCall(
    val toolName: String,
    val argsJson: String,
) {
    /** The arguments as a fresh JSONObject; a malformed payload yields `{}`. */
    fun args(): JSONObject = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }

    /** The shell command, for display; null for non-shell tools. */
    val shellCommand: String?
        get() = if (toolName == SHELL_TOOL) args().optString("command", "").ifBlank { null } else null

    /**
     * Why this call cannot be stored, or null when it can. Only the tools in
     * [SUPPORTED_TOOLS] are accepted; the rest are refused up front rather
     * than failing silently every time the task fires.
     */
    fun validationError(): String? {
        if (toolName !in SUPPORTED_TOOLS) {
            return "prefilled tool '$toolName' is not supported yet (supported: ${SUPPORTED_TOOLS.joinToString()})"
        }
        val a = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return "prefilled tool arguments must be a JSON object"
        return when (toolName) {
            SHELL_TOOL -> when {
                a.optString("command", "").isBlank() -> "shell prefill needs a non-empty command"
                a.optString("command", "").length > MAX_COMMAND_LENGTH ->
                    "shell command is longer than $MAX_COMMAND_LENGTH characters; put the script in a file and run that file"
                a.has("timeout") && a.optInt("timeout", -1) !in TIMEOUT_RANGE_SEC ->
                    "shell timeout must be between ${TIMEOUT_RANGE_SEC.first}s and ${TIMEOUT_RANGE_SEC.last}s"
                else -> null
            }
            else -> null
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("tool", toolName)
        .put("args", args())

    companion object {
        const val SHELL_TOOL = "shell_execute"

        /** Same limits as iOS ScheduledPresetToolCall. */
        const val MAX_COMMAND_LENGTH = 16_000
        val TIMEOUT_RANGE_SEC: IntRange = 1..3600

        /**
         * Tools a task may prefill. Extending this is a matter of adding the
         * name here and its argument check in [validationError]; the firing
         * path is tool-agnostic because it reuses the agent loop's dispatch.
         */
        val SUPPORTED_TOOLS: Set<String> = setOf(SHELL_TOOL)

        /** Short names accepted on the CLI, mapped to the tool's real name. */
        private val ALIASES = mapOf("shell" to SHELL_TOOL, "sh" to SHELL_TOOL, "bash" to SHELL_TOOL)

        fun canonicalToolName(raw: String): String {
            val t = raw.trim()
            return ALIASES[t.lowercase()] ?: t
        }

        /**
         * A shell prefill. [title] fills `tool_title` (the card header) when
         * the caller gave none, since the tool schema requires one and the
         * model never got to write it.
         */
        fun shell(command: String, title: String? = null, timeoutSec: Int? = null): PrefilledToolCall =
            of(SHELL_TOOL, JSONObject().put("command", command).apply { timeoutSec?.let { put("timeout", it) } }, title)

        /**
         * Build a call from a tool name and its arguments, adding a
         * `tool_title` when absent. The title falls back to the first line of
         * the shell command so the card never shows a bare tool name.
         */
        fun of(toolName: String, args: JSONObject, title: String? = null): PrefilledToolCall {
            val name = canonicalToolName(toolName)
            val copy = JSONObject(args.toString())
            if (copy.optString("tool_title", "").isBlank()) {
                val fallback = title?.trim()?.takeIf { it.isNotEmpty() }
                    ?: copy.optString("command", "").lineSequence().firstOrNull()?.trim()?.take(40)
                        ?.takeIf { it.isNotEmpty() }
                if (fallback != null) copy.put("tool_title", fallback)
            }
            return PrefilledToolCall(name, copy.toString())
        }

        /** Read the stored shape; null for a missing or unusable value. */
        fun fromJson(o: JSONObject?): PrefilledToolCall? {
            if (o == null) return null
            val tool = o.optString("tool", "").takeIf { it.isNotBlank() } ?: return null
            val args = o.optJSONObject("args") ?: JSONObject()
            return PrefilledToolCall(tool, args.toString())
        }

        /**
         * A tool_use id for a prefilled call. Letters, digits and `_` only, so
         * it satisfies Anthropic's `^[a-zA-Z0-9_-]+$`, and 34 chars stays under
         * the 64-char cap OpenAI-compatible endpoints enforce. The prefix makes
         * a prefilled call identifiable in logs and request dumps.
         */
        fun newToolUseId(): String = ID_PREFIX + UUID.randomUUID().toString().replace("-", "").take(24)

        const val ID_PREFIX = "call_sched_"
    }
}

/**
 * [T-scheduled-tool-prefill] The first agent-loop turn, pre-decided. Each
 * call carries the tool_use id it will be recorded under.
 */
data class ScriptedToolTurn(val calls: List<Call>) {
    data class Call(val id: String, val toolName: String, val argsJson: String)

    /**
     * The chunk sequence a provider would have streamed for a turn that is
     * nothing but these tool calls: start, one start+complete pair per call,
     * then a `tool_use` finish. No text, no thinking, no usage — the turn
     * made no request, so it has no tokens to report.
     */
    fun asStreamChunks(): List<LLMStreamChunk> = buildList {
        add(LLMStreamChunk.Started)
        for (c in calls) {
            add(LLMStreamChunk.ToolUseStart(c.id, c.toolName))
            add(LLMStreamChunk.ToolCallComplete(c.id, c.toolName, runCatching { JSONObject(c.argsJson) }.getOrElse { JSONObject() }))
        }
        add(LLMStreamChunk.Finished(STOP_REASON))
    }

    companion object {
        const val STOP_REASON = "tool_use"

        /** True for a tool_use id minted by [PrefilledToolCall.newToolUseId]. */
        fun isScriptedId(id: String): Boolean = id.startsWith(PrefilledToolCall.ID_PREFIX)

        /**
         * Turn [prefills] into a scripted turn, keeping only calls whose tool
         * this session actually offers ([availableTools]). A call to a tool the
         * model was not given would be a history the model could never have
         * produced; dropping it falls back to the old behaviour, where the
         * model reads the prompt and decides for itself. Null when nothing is
         * left to run.
         */
        fun from(prefills: List<PrefilledToolCall>, availableTools: Set<String>): ScriptedToolTurn? {
            val calls = prefills
                .filter { it.validationError() == null && it.toolName in availableTools }
                .map { Call(PrefilledToolCall.newToolUseId(), it.toolName, it.argsJson) }
            return if (calls.isEmpty()) null else ScriptedToolTurn(calls)
        }
    }
}
