package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sequential unique replacements on one file.
 *
 * Adapted from XINCODE-Public MultiEditTool (GPL-3.0-or-later).
 */
object MultiEditTool {
    const val NAME = "multi_edit"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Apply several unique old_string→new_string replacements to one file in order. Each old_string must match exactly once unless replace_all is set on that edit.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "path" to AgentToolParam("string", "Absolute Linux path."),
            "edits" to AgentToolParam(
                type = "array",
                description = "List of {old_string, new_string, replace_all?}.",
                items = AgentToolParam(
                    type = "object",
                    description = "One replacement",
                    properties = mapOf(
                        "old_string" to AgentToolParam("string", "Exact text to find"),
                        "new_string" to AgentToolParam("string", "Replacement"),
                        "replace_all" to AgentToolParam("boolean", "Replace every occurrence"),
                    ),
                    required = listOf("old_string", "new_string"),
                ),
            ),
        ),
        required = listOf("tool_title", "path", "edits"),
        propertyOrdering = listOf("tool_title", "path", "edits"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            if (path.isBlank()) return ToolExecutionResult("Error: path required", false, toolTitle = toolTitle)
            if (PRootKernel.isLinuxPathUnderReadOnlyMount(path)) {
                return ToolExecutionResult("Error: $path is read-only mounted", false, toolTitle = toolTitle)
            }
            WritePathGuard.denyReason(path)?.let {
                return ToolExecutionResult(it, false, toolTitle = toolTitle)
            }
            val edits = coerceEdits(args)
            if (edits.length() == 0) {
                val keys = args.keys().asSequence().joinToString(",")
                return ToolExecutionResult(
                    "Error: edits required as an array of {old_string, new_string}. " +
                        "A JSON string, one edit object, or a top-level old_string/new_string pair is also accepted. " +
                        "Received keys: $keys",
                    false,
                    toolTitle = toolTitle,
                )
            }
            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)
            if (!file.exists()) {
                return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            }
            com.openminis.app.data.repository.MemoryRepository
                .fileToolQuotaRefusal(context.filesDir, sessionId, path)
                ?.let { msg -> return ToolExecutionResult(msg, false, toolTitle = toolTitle) }

            // [T-multi-edit-one-rmw] This used to loop `FileEditTool.execute` once
            // per edit, and each iteration took the per-path lock AGAIN, re-read
            // the file, re-ran the guards and — worst of all — captured its OWN
            // checkpoint. Two consequences:
            //  - N checkpoints per call against a 50-slot budget, so one 30-edit
            //    batch ate 60% of the whole session's restore history and evicted
            //    real user snapshots;
            //  - the lock was released between edits, so a concurrent writer could
            //    land between #1 and #2 and edit #2 was computed against bytes that
            //    were no longer the ones edit #1 had just written — the classic
            //    lost-update this repo closed for file_edit in
            //    [T-file-edit-silent-drop], reintroduced one level up.
            // The whole array is now one read-modify-write: ONE lock acquisition,
            // ONE read, all replacements computed in memory, ONE pre-batch capture,
            // ONE verified write. A failing edit still reports the same
            // "stopped at #k" message and leaves the file untouched, because the
            // write only happens once the entire batch matched.
            val outcome = AtomicFileWrite.readModifyWrite(file) { current ->
                when (val r = applyAll(edits, current)) {
                    is BatchOutcome.Failed -> FileEditTool.EditFailure(r.message)
                    is BatchOutcome.Ok -> {
                        FileCheckpointStore.capture(
                            context, sessionId, listOf(path),
                            label = "before multi_edit", source = "multi_edit",
                        )
                        FileEditTool.ReplaceOutcome(r.text, r.total)
                    }
                }
            }

            when (outcome) {
                null -> ToolExecutionResult(
                    "Error: failed to write $path (atomic write did not verify on disk)",
                    false, toolTitle = toolTitle,
                )
                is FileEditTool.EditFailure ->
                    ToolExecutionResult("Error: ${outcome.message}", false, toolTitle = toolTitle)
                is FileEditTool.ReplaceOutcome -> ToolExecutionResult(
                    "Applied ${edits.length()} edits to $path " +
                        "(${outcome.replacements} replacement(s), ${outcome.newContent.length} bytes)",
                    true, toolTitle = toolTitle,
                )
                else -> ToolExecutionResult("Error: unexpected outcome", false, toolTitle = toolTitle)
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error multi_edit: ${e.message}", false, toolTitle = toolTitle)
        }
    }

    internal sealed class BatchOutcome {
        class Ok(val text: String, val total: Int) : BatchOutcome()
        class Failed(val index: Int, val message: String) : BatchOutcome()
    }

    /**
     * Apply every edit to [current] in order, in memory. Pure so it is
     * JVM-testable without a filesystem or a Context.
     */
    internal fun applyAll(edits: JSONArray, current: String): BatchOutcome {
        var text = current
        var total = 0
        for (i in 0 until edits.length()) {
            val e = edits.optJSONObject(i)
                ?: return BatchOutcome.Failed(i + 1, "edit #${i + 1} is not an object")
            val oldString = e.optString("old_string", e.optString("old", ""))
            val newString = e.optString("new_string", e.optString("new", ""))
            if (oldString.isEmpty()) {
                return BatchOutcome.Failed(i + 1, "'old_string' is required and cannot be empty (edit #${i + 1})")
            }
            when (val r = TextReplacers.replace(text, oldString, newString, e.optBoolean("replace_all", false))) {
                is TextReplacers.Result.Success -> {
                    text = r.newContent
                    total += r.count
                }
                is TextReplacers.Result.Failure ->
                    return BatchOutcome.Failed(i + 1, "multi_edit stopped at #${i + 1}: ${r.message}")
            }
        }
        return BatchOutcome.Ok(text, total)
    }

    /**
     * Models sometimes stringify the array, nest it under replacements/changes,
     * or send one old_string/new_string pair. Those used to become "edits required"
     * even though the edit was in the arguments.
     */
    private fun coerceEdits(args: JSONObject): JSONArray {
        val raw = args.opt("edits")
            ?: args.opt("replacements")
            ?: args.opt("changes")
            ?: args.opt("operations")
            ?: args.optJSONObject("input")?.opt("edits")
            ?: args.optJSONObject("arguments")?.opt("edits")
        val arr = when (raw) {
            is JSONArray -> raw
            is JSONObject -> JSONArray().put(raw)
            is String -> parseEditString(raw)
            else -> JSONArray()
        }
        if (arr.length() == 0 && (args.has("old_string") || args.has("old"))) {
            arr.put(
                JSONObject()
                    .put("old_string", args.optString("old_string", args.optString("old", "")))
                    .put("new_string", args.optString("new_string", args.optString("new", ""))),
            )
        }
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (!item.has("old_string") && item.has("old")) item.put("old_string", item.opt("old"))
            if (!item.has("new_string") && item.has("new")) item.put("new_string", item.opt("new"))
        }
        return arr
    }

    private fun parseEditString(raw: String): JSONArray {
        val text = raw.trim()
        if (text.isEmpty()) return JSONArray()
        return try {
            JSONArray(text)
        } catch (_: Exception) {
            try {
                JSONArray().put(JSONObject(text))
            } catch (_: Exception) {
                JSONArray()
            }
        }
    }
}
