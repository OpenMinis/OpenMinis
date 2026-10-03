package com.openminis.app.sandbox.offload

import com.openminis.app.config.ConfigBridge
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * `minis-config` offload handler. Mirrors iOS `ConfigOffload.m`.
 *
 * Subcommands: list-topics, topic-help, get, set, set-batch,
 * audit-list, audit-get, audit-revert. The shell-friendly flags
 * `--filter`/`-f`, `--page`/`-p`, `--page-size`/`-s`, `--caption`,
 * `--actor`, `--session` follow exact iOS semantics.
 *
 * Collection-level writes:
 *   set models.append '{"provider_id":"…","model_id":"…", …}'
 *     → ModelsCollection.add(); creates a custom (is_custom=true) entry.
 *   set models.remove '"<entry-id>"'
 *     → ModelsCollection.remove(); rejected for non-custom entries.
 *
 * Future: per-entry hide via the existing
 *   set models.<entry_id>.isHidden true   (entry_id verbatim, dots included)
 * path. Already supported by ModelsCollection.isHiddenField; nothing
 * else needs to change in this handler when the agent picks it up.
 *
 * Exit codes:
 *   0    success
 *   1    invalid args / validation failed
 *   124  timeout
 *   125  user_rejected / all_rejected
 *   126  permission_denied
 */
class ConfigOffloadHandler : NativeOffloadHandler {
    private companion object {
        const val TAG = "ConfigOffload"
        const val EXIT_OK = 0
        const val EXIT_INVALID_ARGS = 1
        const val EXIT_TIMEOUT = 124
        const val EXIT_USER_REJECTED = 125
        const val EXIT_PERMISSION_DENIED = 126

        const val HELP_TEXT =
            "minis-config - read or change Minis app settings (logged + revertable)\n" +
                "\n" +
                "USAGE:\n" +
                "  minis-config <subcommand> [args]\n" +
                "\n" +
                "DISCOVERY:\n" +
                "  list-topics                  Show all configurable topics.\n" +
                "  topic-help <topic>           Show fields under one topic.\n" +
                "\n" +
                "PATHS:\n" +
                "  <topic>.<field> or <topic>.<entry_id>.<field>. Use entry_id exactly as\n" +
                "  `get <topic>` prints it — dots and slashes are fine, no escaping, e.g.\n" +
                "    get models.<uuid>/glm-5.1.contextWindow\n" +
                "\n" +
                "FIELD I/O:\n" +
                "  get <path> [--filter <kw>] [--page N] [--page-size N]\n" +
                "                               Read one field. --filter (-f) does\n" +
                "                               case-insensitive AND matching on\n" +
                "                               whitespace-separated keywords against the\n" +
                "                               JSON form of each array element. --page\n" +
                "                               (-p, default 1) and --page-size (-s,\n" +
                "                               default 20, max 100) paginate array\n" +
                "                               results. Filter runs first; pagination\n" +
                "                               total reflects the post-filter count.\n" +
                "  set <path> <value-json>      Write one field. Triggers user confirm.\n" +
                "  set <path> --file <f>        Read value-json from file <f> instead of an\n" +
                "                               argv positional. Use this when the value\n" +
                "                               contains quotes / backslashes / newlines /\n" +
                "                               $ / backticks (e.g. soul.body) — it bypasses\n" +
                "                               shell escaping. Write the JSON to a temp file\n" +
                "                               first, then: set <path> --file /tmp/v.json\n" +
                "  set <path>.append <elem>     Append one element to an array-typed field,\n" +
                "                               or — when <path> is a collection (e.g.\n" +
                "                               `models`) — add a new custom child. Payload\n" +
                "                               for collections is a JSON object, e.g.\n" +
                "                                 set models.append '{\"provider_id\":\"<uuid>\",\n" +
                "                                   \"model_id\":\"gpt-foo\",\"display_name\":\"GPT Foo\"}'\n" +
                "  set <path>.remove <elem>     Drop every occurrence of <elem> from an\n" +
                "                               array-typed field, or — for collections —\n" +
                "                               remove a custom child by its entry id, e.g.\n" +
                "                                 set models.remove '\"<entry-uuid>\"'\n" +
                "                               Built-in / API-reported entries cannot be\n" +
                "                               removed (use `set models.<entryId>.isHidden\n" +
                "                               true` to hide instead).\n" +
                "  add <topic> <value-json>     Create a new child in an addable collection\n" +
                "                               (e.g. providers, groups). The app generates\n" +
                "                               its UUID (returned in applied[0].new). Same\n" +
                "                               as `set <topic>.append <value-json>`. Supports\n" +
                "                               --file like `set`. e.g.\n" +
                "                               add groups '{\"name\":\"Light\",\"entries\":[...]}'\n" +
                "  set-batch                    Read JSON array of {path,value_json} from\n" +
                "                               stdin. One confirm dialog for the batch.\n" +
                "                               Each item may use the .append/.remove suffix.\n" +
                "\n" +
                "AUDIT:\n" +
                "  audit-list [--limit N] [--scope <topic>]\n" +
                "  audit-get  <audit-id>\n" +
                "  audit-revert <audit-id>      Roll back a previous applied entry.\n" +
                "\n" +
                "FLAGS:\n" +
                "  --session <id>               Tag the audit row with the active session.\n" +
                "  --actor agent|user|...       Override audit actor (default: agent).\n" +
                "  --caption <text>             Caption shown above the confirm dialog.\n" +
                "  --help, -h                   Show this help.\n" +
                "\n" +
                "EXIT CODES:\n" +
                "  0    success\n" +
                "  1    invalid args / validation failed\n" +
                "  124  confirmation timed out (30 s)\n" +
                "  125  user rejected\n" +
                "  126  permission denied (master switch off, or hidden field)\n" +
                "\n" +
                "Every write requires user confirmation in-app. The response includes\n" +
                "a `user_message` field — relay it to the user so they know how to\n" +
                "review or revert via Logs → Config Changes.\n"
    }

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        // argv[0] is the program name; subcommand and options follow.
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(EXIT_OK, OffloadOutput.formatBody(HELP_TEXT, args) + "\n")
        }

        val sub = args.positional.firstOrNull() ?: return cmdListTopics(args)
        return try {
            when (sub) {
                "list-topics" -> cmdListTopics(args)
                "topic-help" -> cmdTopicHelp(args)
                "get" -> cmdGet(args)
                "set" -> cmdSet(args)
                "add" -> cmdAdd(args)
                "set-batch" -> cmdSetBatch(args, request)
                "audit-list" -> cmdAuditList(args)
                "audit-get" -> cmdAuditGet(args)
                "audit-revert" -> cmdAuditRevert(args)
                else -> errorResult(
                    args, EXIT_INVALID_ARGS,
                    "INVALID_ARGS",
                    "Unknown subcommand '$sub'. Use --help.",
                    appendHelp = true,
                )
            }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            errorResult(args, EXIT_INVALID_ARGS, "INTERNAL", e.message ?: "unknown error")
        }
    }

    // -- Subcommands --

    private fun cmdListTopics(args: OffloadArgs): NativeOffloadResult {
        if (!ConfigBridge.isEnabled()) {
            return envelopeResult(args, ConfigBridge.disabledErrorEnvelope())
        }
        val out = JSONObject().apply {
            put("ok", true)
            put("tool", "minis-config")
            put("subcommand", "list-topics")
            put("data", JSONObject().apply { put("topics", ConfigBridge.allTopics()) })
        }
        return envelopeResult(args, out)
    }

    private fun cmdTopicHelp(args: OffloadArgs): NativeOffloadResult {
        val topic = args.positional.getOrNull(1)
        if (topic.isNullOrEmpty()) {
            return errorResult(
                args, EXIT_INVALID_ARGS,
                "INVALID_ARGS",
                "topic-help <topic> requires a topic name. Use list-topics to see available topics."
            )
        }
        if (!ConfigBridge.isEnabled()) {
            return envelopeResult(args, ConfigBridge.disabledErrorEnvelope())
        }
        val out = JSONObject().apply {
            put("ok", true)
            put("tool", "minis-config")
            put("subcommand", "topic-help")
            put("data", JSONObject().apply {
                put("topic", topic)
                put("fields", ConfigBridge.fieldsForTopic(topic))
                // [T-config-path-dotted-id] Collections say how to spell a child path.
                ConfigBridge.pathNoteForTopic(topic)?.let { put("path_note", it) }
            })
        }
        return envelopeResult(args, out)
    }

    private fun cmdGet(args: OffloadArgs): NativeOffloadResult {
        val path = args.positional.getOrNull(1) ?: return errorResult(
            args, EXIT_INVALID_ARGS, "INVALID_ARGS",
            "get <path> requires a field path."
        )
        val filter = args.get("filter", "f")
        val page = args.getInt("page", "p")?.coerceAtLeast(0) ?: 0
        val pageSize = args.getInt("page-size", "s")?.coerceAtLeast(0) ?: 0
        val envelope = ConfigBridge.readField(path = path, filter = filter, page = page, pageSize = pageSize)
        return envelopeResult(args, envelope)
    }

    private fun cmdSet(args: OffloadArgs): NativeOffloadResult {
        val path = args.positional.getOrNull(1)
        // [T-android-minis-config-set-shell-escape] (issue #36) `--file <path>`
        // reads the value-json from a file instead of an argv positional. The
        // value normally rides through the guest shell as a positional arg, so
        // busybox ash mangles embedded double-quotes / backslashes / newlines /
        // $ / backticks before this handler sees them — breaking large free-text
        // writes like soul.body. Reading from a file bypasses shell escaping
        // entirely. Mirrors iOS 5dcff277.
        val fileArg = args.get("file")
        val valueJSON = if (fileArg != null) {
            readLinuxPath(fileArg) ?: return errorResult(
                args, EXIT_INVALID_ARGS, "INVALID_ARGS",
                "--file: could not read value from '$fileArg'."
            )
        } else {
            args.positional.getOrNull(2)
        }
        if (path == null || valueJSON == null) {
            return errorResult(
                args, EXIT_INVALID_ARGS, "INVALID_ARGS",
                "set <path> <value-json> requires both arguments " +
                    "(or use: set <path> --file <path-to-value-json>)."
            )
        }
        val caption = args.get("caption")
        val actor = args.get("actor") ?: "agent"
        val sessionId = args.get("session")
        val items = JSONArray().put(JSONObject().apply {
            put("path", path)
            put("value_json", valueJSON)
        })
        val envelope = ConfigBridge.writeFields(items, caption, actor, sessionId)
        return envelopeResult(args, envelope)
    }

    /**
     * `add <topic> <value-json>` — create a new child in an addable collection
     * (e.g. `add providers …`, `add groups …`). Sugar over `set
     * <topic>.append <value-json>`: it reuses the exact same collection-add
     * path in the bridge, so the confirm dialog, audit row, redaction, exit
     * codes, and the new child's auto-generated UUID (returned in
     * applied[0].new) are all identical. Mirrors iOS `cmd_add`. (The bridge's
     * collection-add suffix is `.append` on Android.)
     */
    private fun cmdAdd(args: OffloadArgs): NativeOffloadResult {
        val topic = args.positional.getOrNull(1)
        val fileArg = args.get("file")
        val valueJSON = if (fileArg != null) {
            readLinuxPath(fileArg) ?: return errorResult(
                args, EXIT_INVALID_ARGS, "INVALID_ARGS",
                "--file: could not read value from '$fileArg'."
            )
        } else {
            args.positional.getOrNull(2)
        }
        if (topic == null || valueJSON == null) {
            return errorResult(
                args, EXIT_INVALID_ARGS, "INVALID_ARGS",
                "add <topic> <value-json> requires both arguments " +
                    "(or use: add <topic> --file <path-to-value-json>). " +
                    "Example: add groups '{\"name\":\"Light\",\"entries\":[...]}'."
            )
        }
        val caption = args.get("caption")
        val actor = args.get("actor") ?: "agent"
        val sessionId = args.get("session")
        val items = JSONArray().put(JSONObject().apply {
            put("path", "$topic.append")
            put("value_json", valueJSON)
        })
        val envelope = ConfigBridge.writeFields(items, caption, actor, sessionId)
        return envelopeResult(args, envelope)
    }

    private fun cmdSetBatch(args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        // Batch JSON is passed positionally on Android — there is no
        // stdin pipe on the native_offload protocol. Support either:
        //   set-batch <json-array>      (shell can pass literal)
        //   set-batch --batch <json>    (env-friendly form)
        // The shape is the same as iOS stdin: array of
        // {path, value_json}.
        val raw = args.positional.getOrNull(1) ?: args.get("batch") ?: return errorResult(
            args, EXIT_INVALID_ARGS, "INVALID_ARGS",
            "set-batch expects a JSON array argument with elements {\"path\":..., \"value_json\":...}."
        )
        val parsed = try {
            JSONArray(raw)
        } catch (_: Throwable) {
            return errorResult(
                args, EXIT_INVALID_ARGS, "INVALID_ARGS",
                "set-batch payload is not a JSON array."
            )
        }
        if (parsed.length() == 0) {
            return errorResult(args, EXIT_INVALID_ARGS, "INVALID_ARGS", "Empty batch.")
        }
        if (parsed.length() > 50) {
            return errorResult(args, EXIT_INVALID_ARGS, "INVALID_ARGS", "Batch capped at 50 items.")
        }
        val caption = args.get("caption")
        val actor = args.get("actor") ?: "agent"
        val sessionId = args.get("session")
        val envelope = ConfigBridge.writeFields(parsed, caption, actor, sessionId)
        return envelopeResult(args, envelope)
    }

    private fun cmdAuditList(args: OffloadArgs): NativeOffloadResult {
        val limit = args.getInt("limit")?.coerceIn(1, 1000) ?: 100
        val scope = args.get("scope")
        val envelope = ConfigBridge.auditList(limit, scope)
        return envelopeResult(args, envelope)
    }

    private fun cmdAuditGet(args: OffloadArgs): NativeOffloadResult {
        val id = args.positional.getOrNull(1) ?: return errorResult(
            args, EXIT_INVALID_ARGS, "INVALID_ARGS",
            "audit-get <audit-id> requires an id."
        )
        val envelope = ConfigBridge.auditGet(id)
        return envelopeResult(args, envelope)
    }

    private fun cmdAuditRevert(args: OffloadArgs): NativeOffloadResult {
        val id = args.positional.getOrNull(1) ?: return errorResult(
            args, EXIT_INVALID_ARGS, "INVALID_ARGS",
            "audit-revert <audit-id> requires an id."
        )
        val actor = args.get("actor") ?: "agent-revert"
        val sessionId = args.get("session")
        val envelope = ConfigBridge.auditRevert(id, actor, sessionId, skipConfirmation = false)
        return envelopeResult(args, envelope)
    }

    // -- helpers --

    private fun envelopeResult(args: OffloadArgs, envelope: JSONObject): NativeOffloadResult {
        val exit = exitCodeFromEnvelope(envelope)
        val body = OffloadOutput.formatBody(envelope.toString(2), args) + "\n"
        return NativeOffloadResult(exit, body)
    }

    private fun exitCodeFromEnvelope(envelope: JSONObject): Int {
        if (envelope.optBoolean("ok", false)) return EXIT_OK
        return when (envelope.optString("error")) {
            "permission_denied" -> EXIT_PERMISSION_DENIED
            "timeout" -> EXIT_TIMEOUT
            "user_rejected", "all_rejected" -> EXIT_USER_REJECTED
            // already_exists is a structural validation failure: map to
            // the generic invalid-args exit so the agent can still
            // distinguish via the JSON `error` field. Mirrors iOS.
            "already_exists" -> EXIT_INVALID_ARGS
            else -> EXIT_INVALID_ARGS
        }
    }

    private fun errorResult(
        args: OffloadArgs,
        exitCode: Int,
        code: String,
        reason: String,
        appendHelp: Boolean = false,
    ): NativeOffloadResult {
        val obj = JSONObject().apply {
            put("ok", false)
            put("error", code.lowercase())
            put("reason", reason)
        }
        val body = OffloadOutput.formatBody(obj.toString(2), args) + "\n" +
            (if (appendHelp) HELP_TEXT else "")
        return NativeOffloadResult(exitCode, body)
    }

    /**
     * [T-android-minis-config-set-shell-escape] Read a file's text by its
     * guest (Linux) path, resolving it to the host path through the rootfs /
     * bind mounts. Same helper shape as ModelUseOffloadHandler.readLinuxPath —
     * used by `set --file` so the value-json never transits the shell. Returns
     * null when the path can't be resolved, doesn't exist, or read fails.
     */
    private fun readLinuxPath(linuxPath: String): String? {
        val hostFile: File = PRootKernel.resolveHostPath(linuxPath) ?: return null
        if (!hostFile.exists() || !hostFile.isFile) return null
        return try { hostFile.readText() } catch (_: Throwable) { null }
    }
}
