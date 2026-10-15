package com.openminis.app.provider.openai

import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-responses-orphan-tool-output] Last gate before a Responses
 * request goes out: every `function_call_output` must answer a
 * `function_call` with the same `call_id` in the array, at most once, and every
 * `function_call` except the trailing run must be answered. Port of iOS
 * `sanitizeResponsesToolPairing` (bc142f750, first-output-wins from ffbed35ca).
 *
 * Why it is needed: the Responses builder emitted `function_call_output`
 * unconditionally. An upstream answers a stranded one with
 *     No tool call found for function call output with call_id call_… .
 * and, because the request is rebuilt from the same history every time, the
 * 400 repeated on every retry and every fallback model until the chat was
 * cleared (the iOS field report). ChatViewModel's history repair catches most
 * orphans one layer up, but it works on stored ids while this sees exactly the
 * ids the request carries (split on "|" and capped), and it cannot see every
 * source of a stranded or duplicated result (a retry, resume or restore that
 * left an extra result row).
 *
 * Repair, same rules as iOS:
 *  - an output whose call is not in the request is DROPPED - nothing can
 *    reconstruct the call;
 *  - a second output for the same call_id is DROPPED - the first one wins, as
 *    in the Chat Completions path;
 *  - an unanswered call gets a placeholder output instead of being deleted,
 *    which would silently discard the assistant's own turn. The TRAILING run of
 *    calls is exempt: a request ending on function_call is what the API expects
 *    mid-round, and a placeholder there would tell the model a tool it is about
 *    to run has already failed.
 *
 * Placeholders are buffered until the run of function_call items closes and
 * land before the first following item, so parallel calls stay
 * `function_call ×N, function_call_output ×N` - an output in the middle of the
 * call run, or a tool-image carrier between outputs, is itself a 400
 * ([T-android-responses-toolresult-image-split]). Order is otherwise kept as
 * built; no items are moved.
 */
internal object ResponsesToolPairing {

    const val PLACEHOLDER_OUTPUT =
        "Tool execution result is unavailable (history was truncated or interrupted)."

    class Result(
        val items: JSONArray,
        val droppedOrphanOutputs: List<String>,
        val droppedDuplicateOutputs: List<String>,
        val placeholderCalls: List<String>,
    ) {
        val changed: Boolean
            get() = droppedOrphanOutputs.isNotEmpty() || droppedDuplicateOutputs.isNotEmpty() ||
                placeholderCalls.isNotEmpty()
    }

    fun sanitize(items: JSONArray): Result {
        val callIds = HashSet<String>()
        val outputCounts = HashMap<String, Int>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "function_call" -> callIds.add(item.optString("call_id"))
                "function_call_output" -> {
                    val id = item.optString("call_id")
                    outputCounts[id] = (outputCounts[id] ?: 0) + 1
                }
            }
        }

        // Calls at the very end are waiting for their results, not orphaned.
        val trailing = HashSet<String>()
        for (i in items.length() - 1 downTo 0) {
            val item = items.optJSONObject(i) ?: break
            when (item.optString("type")) {
                "function_call" -> trailing.add(item.optString("call_id"))
                "reasoning" -> {}
                else -> break
            }
        }

        val orphanOutputs = outputCounts.keys - callIds
        val duplicated = outputCounts.filter { (id, n) -> n > 1 && id in callIds }.keys
        val unanswered = callIds - outputCounts.keys - trailing
        if (orphanOutputs.isEmpty() && duplicated.isEmpty() && unanswered.isEmpty()) {
            return Result(items, emptyList(), emptyList(), emptyList())
        }

        val out = JSONArray()
        val seenOutputs = HashSet<String>()
        val droppedOrphans = mutableListOf<String>()
        val droppedDuplicates = mutableListOf<String>()
        val placeholders = mutableListOf<String>()
        val pending = mutableListOf<JSONObject>()
        fun flush() {
            for (p in pending) out.put(p)
            pending.clear()
        }
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i)
            if (item == null) {
                out.put(items.get(i))
                continue
            }
            val type = item.optString("type")
            if (type == "function_call_output") {
                val id = item.optString("call_id")
                if (id in orphanOutputs) { droppedOrphans += id; continue }
                if (!seenOutputs.add(id)) { droppedDuplicates += id; continue }
            }
            // A real output closes the call run just as a placeholder does, so
            // pending placeholders go BEFORE it: one contiguous output block.
            if (type != "function_call") flush()
            out.put(item)
            if (type == "function_call") {
                val id = item.optString("call_id")
                if (id in unanswered) {
                    placeholders += id
                    pending += JSONObject().apply {
                        put("type", "function_call_output")
                        put("call_id", id)
                        put("output", PLACEHOLDER_OUTPUT)
                    }
                }
            }
        }
        flush()
        return Result(out, droppedOrphans, droppedDuplicates, placeholders)
    }
}
