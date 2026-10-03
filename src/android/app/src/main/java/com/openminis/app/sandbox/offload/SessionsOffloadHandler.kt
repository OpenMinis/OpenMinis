package com.openminis.app.sandbox.offload

import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * T188 — `minis-sessions-cli` offload handler. Lets the in-shell agent
 * query historical chat sessions and messages without round-tripping
 * back through the LLM. Three subcommands:
 *
 *   list       List recent sessions, optionally filtered by id, keyword,
 *              and date range. Default if no subcommand is given.
 *   search     Search message content across sessions; --keywords
 *              required.
 *   messages   Read a paginated transcript of one session; --id required.
 *
 * Mirrors iOS canonical CLI surface: argv shape, JSON envelope, exit
 * codes, error messages all line up. The on-device db (Room) is the
 * same shape as iOS (sqlite tables `sessions`/`messages` with matching
 * column names), so query semantics translate one-to-one.
 *
 * Output flags `--compact` / `-q` / `--quiet` are honored uniformly via
 * [OffloadOutput.formatBody], same as every other android-* / minis-*
 * tool.
 */
class SessionsOffloadHandler(
    private val repo: ChatRepository,
) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        // argv[0] is the program name ("minis-sessions-cli"); subcommand
        // and options follow. Drop argv[0] before parsing so positional[0]
        // is the subcommand name. `full` is declared boolean so
        // `--full <token>` never greedily consumes the next token as a value
        // (e.g. `--full` placed before the subcommand). Same for `--tools`.
        val args = OffloadArgs(request.argv.drop(1), booleanFlags = setOf("full", "tools"))
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, OffloadOutput.formatBody(HELP_TEXT, args) + "\n")
        }

        val sub = args.positional.firstOrNull() ?: "list"
        return try {
            when (sub) {
                "list" -> cmdList(args)
                "search" -> cmdSearch(args)
                "messages" -> cmdMessages(args)
                else -> {
                    val err = errorEnvelope(
                        sub,
                        "INVALID_ARGS",
                        "Unknown command '$sub'. Valid: list, search, messages. " +
                            "Use --help for details.",
                    )
                    NativeOffloadResult(
                        EXIT_INVALID_ARGS,
                        OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
                    )
                }
            }
        } catch (e: Throwable) {
            // Last-ditch: any unexpected exception (sql parse error, OOM,
            // …) becomes an INTERNAL envelope rather than a crash so the
            // shell stays alive and the agent can read the failure.
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            val err = errorEnvelope(sub, "INTERNAL", e.message ?: "unknown error")
            NativeOffloadResult(
                1,
                OffloadOutput.formatBody(err.toString(2), args) + "\n",
            )
        }
    }

    private fun cmdList(args: OffloadArgs): NativeOffloadResult {
        val ids = parseIds(args)
        val kws = parseKeywords(args)
        val startMs = parseDate(args.get("start"))
        val endMs = parseEndDate(args.get("end"))
        val limit = parseLimit(args)

        val metas = runBlocking { repo.querySessionsMeta(ids, kws, limit, startMs, endMs) }

        val sessions = JSONArray()
        for (m in metas) {
            val s = JSONObject()
                .put("session_id", m.id)
                .put("started_at", DATE_FMT.format(Date(m.startedAt)))
                .put("last_active", DATE_FMT.format(Date(m.lastActive)))
                .put("message_count", m.messageCount)
            // Optional fields — only emit when non-null so the JSON
            // matches iOS's `(optional)` shape rather than carrying
            // null literals through to the agent prompt.
            m.title?.let { if (it.isNotBlank()) s.put("title", it) }
            m.preview?.let { if (it.isNotBlank()) s.put("preview", it) }
            m.source?.let { if (it.isNotBlank()) s.put("source", it) }
            sessions.put(s)
        }
        val data = JSONObject()
            .put("count", sessions.length())
            .put("sessions", sessions)
        return emit("list", data, args)
    }

    private fun cmdSearch(args: OffloadArgs): NativeOffloadResult {
        val kws = parseKeywords(args).orEmpty()
        if (kws.isEmpty()) {
            val err = errorEnvelope(
                "search",
                "INVALID_ARGS",
                "--keywords is required for search. " +
                    "Example: minis-sessions-cli search --keywords \"API error\"",
            )
            return NativeOffloadResult(
                EXIT_INVALID_ARGS,
                OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
            )
        }
        val ids = parseIds(args)
        val startMs = parseDate(args.get("start"))
        val endMs = parseEndDate(args.get("end"))
        val limit = parseLimit(args)

        val matches = runBlocking { repo.searchMessages(ids, kws, limit, startMs, endMs) }

        val msgs = JSONArray()
        for (m in matches) {
            msgs.put(
                JSONObject()
                    .put("session_id", m.sessionId)
                    .put("message_id", m.messageId)
                    .put("role", m.role)
                    .put("created_at", DATE_FMT.format(Date(m.createdAt)))
                    .put("snippet", m.snippet),
            )
        }
        val data = JSONObject()
            .put("count", msgs.length())
            .put("messages", msgs)
        return emit("search", data, args)
    }

    private fun cmdMessages(args: OffloadArgs): NativeOffloadResult {
        val sessionId = args.get("id")?.takeIf { it.isNotBlank() }
            ?: run {
                val err = errorEnvelope(
                    "messages",
                    "INVALID_ARGS",
                    "--id <session_id> is required. Use 'list' first to find session IDs.",
                )
                return NativeOffloadResult(
                    EXIT_INVALID_ARGS,
                    OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
                )
            }
        val limit = parseLimit(args)
        val offset = (args.getInt("offset") ?: 0).coerceAtLeast(0)
        // [T-android-sessions-cli-full] --full lifts the per-message text cap
        // from the default 600 to 50000 chars (iOS parity). Previously the flag
        // was silently ignored — every message stayed truncated at 600 no
        // matter what the caller passed, gutting transcript exports.
        val full = args.hasFlag("full")
        val maxChars = if (full) ChatRepository.MESSAGE_TEXT_MAX_FULL else ChatRepository.MESSAGE_TEXT_MAX
        // [T-android-sessions-cli-messages-daterange] GH#200 (iOS 8f3189a73).
        // HELP_TEXT documents --start / --end and `list` / `search` honour
        // them, but `messages` parsed neither — the CLI accepted the flags and
        // silently returned the whole session, which reads as the filter
        // working and matching everything.
        val startMs = parseDate(args.get("start"))
        val endMs = parseEndDate(args.get("end"))
        // [T-android-sessions-cli-tool-calls] Opt-in, so the default output is
        // unchanged (iOS 7af22b59f).
        val tools = args.hasFlag("tools")

        val (page, total) = runBlocking {
            // Single coroutine block so the two reads see a consistent
            // snapshot — ordering matters less than ensuring we don't
            // surface a `total` that disagrees with the slice we
            // returned (e.g. another session interleaving inserts mid-
            // call). Room serializes via the suspending dispatcher so
            // these run sequentially in the same coroutine.
            // Both reads take the SAME date range on purpose: an unfiltered
            // count next to a filtered page would make `total` describe the
            // whole session while the slice covers only the matches, so
            // `hasMore` would lie. iOS 8f3189a73 calls this out explicitly.
            repo.loadMessagePage(sessionId, offset, limit, maxChars, startMs, endMs, includeTools = tools) to
                repo.messageCountInRange(sessionId, startMs, endMs)
        }

        val msgs = JSONArray()
        for (m in page) {
            val obj = JSONObject()
                .put("message_id", m.messageId)
                .put("role", m.role)
                .put("created_at", DATE_FMT.format(Date(m.createdAt)))
                .put("text", m.text)
            // Only present when the stored text exceeded the cap, so normal
            // messages serialize byte-identically to before (iOS parity).
            if (m.truncated) obj.put("truncated", true)
            if (tools) {
                obj.put("tool_calls", toolCallsJson(m.toolUses, maxChars))
                obj.put("tool_results", toolResultsJson(m.toolResults, maxChars))
            }
            msgs.put(obj)
        }
        val data = JSONObject()
            .put("session_id", sessionId)
            .put("offset", offset)
            .put("limit", limit)
            .put("full", full)
            .put("max_chars", maxChars)
            .put("total", total)
            .put("count", msgs.length())
            .put("messages", msgs)
        // Only when on, so the default envelope keeps exactly its old keys.
        if (tools) data.put("tools", true)
        return emit("messages", data, args)
    }

    // ─── arg parsing helpers ──────────────────────────────────────────

    private fun parseIds(args: OffloadArgs): List<String>? {
        val raw = args.get("ids") ?: return null
        val parts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.takeIf { it.isNotEmpty() }
    }

    private fun parseKeywords(args: OffloadArgs): List<String>? {
        val raw = args.get("keywords") ?: return null
        // Split on any whitespace (the user may quote multi-word keywords
        // at the shell, in which case we get one big token containing a
        // space — collapse runs of whitespace into a single delimiter).
        val parts = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return parts.takeIf { it.isNotEmpty() }
    }

    private fun parseDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getDefault()
            sdf.isLenient = false
            sdf.parse(raw)?.time
        }.getOrNull()
    }

    /**
     * Inclusive end-of-day: `--end 2025-03-31` includes everything up to
     * and including 2025-03-31 23:59:59.999. Adding (1 day - 1 ms) is
     * safer than (24h - 1ms) across DST transitions on devices that
     * don't run UTC. Mirrors iOS SessionsOffload.m L141-145.
     */
    private fun parseEndDate(raw: String?): Long? {
        val ms = parseDate(raw) ?: return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = ms
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis - 1
    }

    private fun parseLimit(args: OffloadArgs): Int {
        val raw = args.getInt("limit") ?: return DEFAULT_LIMIT
        return raw.coerceIn(1, MAX_LIMIT)
    }

    // ─── envelope helpers ─────────────────────────────────────────────

    private fun emit(action: String, data: JSONObject, args: OffloadArgs): NativeOffloadResult {
        val envelope = JSONObject()
            .put("ok", true)
            .put("tool", TOOL_NAME)
            .put("action", action)
            .put("data", data)
        return NativeOffloadResult(
            0,
            OffloadOutput.formatBody(envelope.toString(2), args) + "\n",
        )
    }

    private fun errorEnvelope(action: String, code: String, msg: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("tool", TOOL_NAME)
            .put("action", action)
            .put("data", JSONObject().put("error", code).put("message", msg))
    }

    companion object {
        private const val TAG = "SessionsOffload"
        private const val TOOL_NAME = "minis-sessions-cli"
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 100

        // iOS NOFF_EXIT_INVALID_ARGS — the shell convention is exit 2
        // for invalid CLI args, distinct from exit 1 for runtime errors.
        private const val EXIT_INVALID_ARGS = 2

        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

        /**
         * [T-android-sessions-cli-tool-calls] `tool_calls` for one message:
         * `{tool_use_id, name, input}`, input being the stored input JSON as a
         * string (so the field has one type even when cut mid-JSON), capped at
         * the same [maxChars] as `text` with `input_truncated` when cut.
         */
        internal fun toolCallsJson(uses: List<com.openminis.app.data.repository.OffloadToolUse>, maxChars: Int): JSONArray {
            val out = JSONArray()
            for (tu in uses) {
                val call = JSONObject()
                    .put("tool_use_id", tu.toolUseId)
                    .put("name", tu.name)
                    .put("input", tu.input.take(maxChars))
                if (tu.input.length > maxChars) call.put("input_truncated", true)
                out.put(call)
            }
            return out
        }

        /** [T-android-sessions-cli-tool-calls] `tool_results`: `{tool_use_id, success, output}`, same cap. */
        internal fun toolResultsJson(results: List<com.openminis.app.data.repository.OffloadToolResult>, maxChars: Int): JSONArray {
            val out = JSONArray()
            for (tr in results) {
                val res = JSONObject()
                    .put("tool_use_id", tr.toolUseId)
                    .put("success", tr.success)
                    .put("output", tr.output.take(maxChars))
                if (tr.output.length > maxChars) res.put("output_truncated", true)
                out.put(res)
            }
            return out
        }

        private const val HELP_TEXT = """minis-sessions-cli - Query historical chat sessions and messages

USAGE:
  minis-sessions-cli <command> [options]

COMMANDS:
  list      List recent sessions (default: 50, max: 100)
  search    Search message content across sessions (requires --keywords)
  messages  Read messages from a specific session (requires --id)

OPTIONS:
  --keywords <words>    Space-separated keywords (AND logic, required for search)
  --ids <id1,id2,...>   Filter by comma-separated session IDs (list/search)
  --id <session_id>     Session ID to read messages from (messages)
  --full                (messages only) Return full message text up to 50000 chars
  --tools               (messages only) Also return each message's tool calls:
                        tool_calls [{tool_use_id, name, input}] with the full
                        input JSON (e.g. the exact shell command), and
                        tool_results [{tool_use_id, success, output}].
                        Same per-field cap as text (600, or 50000 with --full).
  --offset <n>          Skip first n messages, 0-based (default: 0)
  --start <YYYY-MM-DD>  Filter results after this date (inclusive)
  --end <YYYY-MM-DD>    Filter results before this date (inclusive, end of day)
  --limit <n>           Max results (default: 50, max: 100)
  --help, -h            Show this help message
  --compact             Minimize JSON output
  -q, --quiet           Output only data field

OUTPUT (list):
  Each session includes: session_id, title, preview (first user message,
  60 chars), source, started_at, last_active, message_count.

OUTPUT (search):
  Each message includes: session_id, message_id, role, created_at,
  snippet (up to 600 chars centered on keyword match).

OUTPUT (messages):
  Each message includes: message_id, role, created_at, text (up to 600 chars
  by default; up to 50000 with --full). Messages longer than the cap carry
  "truncated": true. Response also includes: session_id, offset, limit, full,
  max_chars, total (total message count). With --tools each message also
  has tool_calls / tool_results (input_truncated / output_truncated when cut)
  and the response carries "tools": true.

WORKFLOW:
  1. Use 'list' or 'list --keywords <topic>' to find relevant sessions
  2. Use 'search --keywords <terms>' to find specific messages
  3. Use 'messages --id <session_id>' to read a conversation
  4. Use --offset to paginate through long conversations

EXAMPLES:
  minis-sessions-cli list
  minis-sessions-cli list --limit 10
  minis-sessions-cli list --keywords python flask
  minis-sessions-cli list --start 2025-01-01 --end 2025-03-31
  minis-sessions-cli search --keywords "API error" --limit 20
  minis-sessions-cli search --keywords deploy --ids abc123,def456
  minis-sessions-cli messages --id <session_id>
  minis-sessions-cli messages --id <session_id> --full
  minis-sessions-cli messages --id <session_id> --tools --full
  minis-sessions-cli messages --id <session_id> --offset 20 --limit 10
"""
    }
}
