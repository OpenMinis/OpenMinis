package com.openminis.app.debug

import android.os.Build
import com.openminis.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Static catalogue of every JSON-RPC method exposed by the Android debug server.
 *
 * Returned by `rpc.discover` so clients can introspect the API at runtime
 * instead of relying on external documentation. Keep this in sync with the
 * dispatch `when` in [DebugRPCHandler.dispatch].
 */
object DebugMethodRegistry {

    data class ParamSpec(
        val name: String,
        val type: String,
        val required: Boolean,
        val default: Any? = null,
        val description: String,
    )

    data class MethodSpec(
        val name: String,
        val description: String,
        val params: List<ParamSpec>,
        val returns: String,
        val example: JSONObject,
    )

    private fun ex(vararg pairs: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in pairs) o.put(k, v)
        return o
    }

    val methods: List<MethodSpec> by lazy {
        buildList {
            addAll(BASE_METHODS)
            if (BuildConfig.DEBUG) addAll(DEBUG_ONLY_METHODS)
        }
    }

    private val DEBUG_ONLY_METHODS: List<MethodSpec> = listOf(
        MethodSpec(
            name = "debug.shizuku.exec",
            description = "DEBUG-only: invoke ShizukuOffloadHandler directly with the given argv (T344). Bypasses the agent loop for e2e harness verification of android-shizuku-cli.",
            params = listOf(
                ParamSpec("args", "[string]", required = false, description = "argv past `android-shizuku-cli` (e.g. [\"exec\", \"id\"]). Either this or 'command' is required."),
                ParamSpec("command", "string", required = false, description = "Whitespace-separated alternative to args, e.g. \"exec id\"."),
            ),
            returns = "{exitCode, output, argv}",
            example = ex("args" to JSONArray().apply { put("exec"); put("id") }),
        ),
        MethodSpec(
            name = "debug.modelUse.exec",
            description = "DEBUG-only: invoke ModelUseOffloadHandler directly with the given argv. Parallels debug.shizuku.exec — lets harnesses trigger `minis-model-use run/list/search` without an in-shell prompt.",
            params = listOf(
                ParamSpec("args", "[string]", required = false, description = "argv past `minis-model-use` (e.g. [\"run\", \"--model\", \"gpt-5.3-codex\"])."),
                ParamSpec("command", "string", required = false, description = "Whitespace-separated alternative to args."),
                ParamSpec("input", "string", required = false, description = "Raw JSON/text fed to the handler as the --input file contents (written to a temp file under /tmp)."),
            ),
            returns = "{exitCode, output, argv}",
            example = ex(
                "args" to JSONArray().apply { put("run"); put("--model"); put("gpt-5.3-codex") },
                "input" to "{\"messages\":[{\"role\":\"user\",\"content\":\"Reply with the single word OK.\"}]}",
            ),
        ),
        MethodSpec(
            name = "debug.sessions.exec",
            description = "DEBUG-only: invoke SessionsOffloadHandler directly with the given argv. Parallels debug.modelUse.exec — lets harnesses trigger `minis-sessions-cli list/search/messages` (incl. --full) without an in-shell prompt.",
            params = listOf(
                ParamSpec("args", "[string]", required = false, description = "argv past `minis-sessions-cli` (e.g. [\"messages\", \"--id\", \"<session_id>\", \"--full\"])."),
                ParamSpec("command", "string", required = false, description = "Whitespace-separated alternative to args."),
            ),
            returns = "{exitCode, output, argv}",
            example = ex(
                "args" to JSONArray().apply { put("messages"); put("--id"); put("<session_id>"); put("--full") },
            ),
        ),
        MethodSpec(
            name = "debug.minisConfig.exec",
            description = "DEBUG-only: drive minis-config through the REAL ConfigBridge (same code path as the in-shell CLI), so a harness can exercise every collection, the confirmation gate and the audit log without an in-shell prompt. Subcommands: set, get, topics, topic-help, audit-list.",
            params = listOf(
                ParamSpec("subcommand", "string", required = true, description = "One of: set, get, topics, topic-help, audit-list."),
                ParamSpec("path", "string", required = false, description = "Config path, for get and single-path set (e.g. \"thinkingrules.<inst>:<rule>.label\")."),
                ParamSpec("value_json", "string", required = false, description = "JSON-encoded new value, for single-path set."),
                ParamSpec("items", "[{path,value_json}]", required = false, description = "Multi-path set applied as ONE write batch; alternative to path+value_json."),
                ParamSpec("skipConfirmation", "bool", required = false, description = "set only. Default true (unattended). Pass false to drive the real on-device confirmation sheet."),
                ParamSpec("filter", "string", required = false, description = "get only. Space-separated terms; filters JSON array values."),
                ParamSpec("page", "int", required = false, description = "get only. 1-BASED page number (page=1 and page=0 both mean the first page). Omit together with pageSize for no pagination."),
                ParamSpec("pageSize", "int", required = false, description = "get only. 0 = return everything."),
                ParamSpec("topic", "string", required = false, description = "topic-help only. Topic name from subcommand=topics."),
                ParamSpec("limit", "int", required = false, description = "audit-list only. 1..1000, default 100."),
                ParamSpec("scope", "string", required = false, description = "audit-list only. Restrict the audit trail to one scope."),
            ),
            returns = "get/set -> the bridge's {ok,…} envelope; topics -> {ok, topics:[string]}; topic-help -> {ok, topic, empty, fields:[{path,display_name,description,schema,access,risk,revertable}]} (empty=true means a registered collection with no children yet, e.g. thinkingrules before any rule is authored); audit-list -> {ok, count, capacity, total_used, entries}",
            example = ex(
                "subcommand" to "get",
                "path" to "thinkingrules",
            ),
        ),
    )

    private val BASE_METHODS: List<MethodSpec> = listOf(
        MethodSpec(
            name = "rpc.discover",
            description = "Return the full list of supported methods with parameter schemas and examples.",
            params = emptyList(),
            returns = "{platform, version, build, methodCount, methods:[{name, description, params, returns, example}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.appInfo",
            description = "Return app metadata, device info, and disk usage.",
            params = emptyList(),
            returns = "{platform, sdkVersion, device, androidVersion, prootBooted, filesDir, logFiles, totalLogSize, diskUsage:{filesDir, sessions, global}}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.screenshot",
            description = "Capture a live screenshot of the foreground Activity as PNG.",
            params = listOf(
                ParamSpec("scale", "number", required = false, default = 1.0, description = "Render scale (0 < scale <= 1)."),
            ),
            returns = "{base64, size, encoding: 'png'}",
            example = ex("scale" to 0.5),
        ),
        MethodSpec(
            name = "debug.ls",
            description = "List directory contents inside the proot-mounted Linux filesystem.",
            params = listOf(
                ParamSpec("path", "string", required = false, default = "/", description = "Linux path under the proot rootfs."),
                ParamSpec("recursive", "bool", required = false, default = false, description = "Recurse into subdirectories."),
                ParamSpec("maxDepth", "int", required = false, default = 3, description = "Max recursion depth when recursive=true."),
            ),
            returns = "Array of {name, type, size, modified}; when recursive=true, directories include a 'children' array.",
            example = ex("path" to "/root", "recursive" to true, "maxDepth" to 2),
        ),
        MethodSpec(
            name = "debug.readFile",
            description = "Read a file from the proot rootfs with optional offset/limit slicing.",
            params = listOf(
                ParamSpec("path", "string", required = true, description = "Linux path under the proot rootfs."),
                ParamSpec("offset", "long", required = false, default = 0, description = "Byte offset to start reading from."),
                ParamSpec("limit", "int", required = false, default = 524288, description = "Maximum bytes to read (default 512KB)."),
                ParamSpec("base64", "bool", required = false, default = false, description = "Force base64 encoding (otherwise detected from content)."),
            ),
            returns = "{size, content, encoding, bytesRead, truncated?}",
            example = ex("path" to "/etc/os-release", "limit" to 4096),
        ),
        MethodSpec(
            name = "debug.logs.list",
            description = "List AppLogger log files on disk.",
            params = emptyList(),
            returns = "{totalSize, files:[{name, size, modified}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.logs.read",
            description = "Read a log file by filename (no path separators allowed).",
            params = listOf(
                ParamSpec("name", "string", required = true, description = "Log filename (no '/' or '..')."),
                ParamSpec("offset", "int", required = false, default = 0, description = "Character offset to start reading from."),
                ParamSpec("limit", "int", required = false, default = 524288, description = "Maximum characters to read (default 512KB)."),
            ),
            returns = "{name, size, content, bytesRead, truncated?}",
            example = ex("name" to "2026-04-17.log", "limit" to 16384),
        ),
        MethodSpec(
            name = "provider.quickTest",
            description = "Run Quick Test on a model entry (same code path as the UI Quick Test sheet). " +
                "Tests applicable modalities (text, speechOut, transcription, imageGen) and returns " +
                "per-kind status. Audio/image results report byte counts, not payloads. " +
                "Android counterpart of iOS debug.providers.quickTest.",
            params = listOf(
                ParamSpec("entryId", "string", required = true, description = "Model entry id from provider.models.list."),
                ParamSpec("kinds", "string[]", required = false, description = "Subset of text/speechOut/transcription/imageGen; omit to auto-detect."),
            ),
            returns = "{entryId, modelId, displayName, providerLabel, results:[{kind, status, elapsedMs, detail, bytes?}]}",
            example = ex("entryId" to "pi_xyz/mimo-v2.5-tts"),
        ),
        MethodSpec(
            name = "debug.crash.list",
            description = "List on-device crash reports (ACRA Java/Kotlin + native), newest first, " +
                "each with a one-line summary of the exception and first app frame.",
            params = listOf(
                ParamSpec("limit", "int", required = false, default = 20, description = "Max reports to return (1-200)."),
            ),
            returns = "{count, returned, crashes:[{name, size, modified, native, summary}]}",
            example = ex("limit" to 5),
        ),
        MethodSpec(
            name = "debug.crash.read",
            description = "Read a crash report. Omit 'name' to get the NEWEST crash — the usual " +
                "\"why did it just die?\" call. Returns the stack section only by default; " +
                "pass stackOnly=false for the full file including the embedded logcat tail.",
            params = listOf(
                ParamSpec("name", "string", required = false, description = "Report filename from debug.crash.list; omit for newest."),
                ParamSpec("stackOnly", "bool", required = false, default = true, description = "Trim the trailing logcat/Build dump."),
                ParamSpec("limit", "int", required = false, default = 262144, description = "Max characters to return."),
            ),
            returns = "{name, modified, native, fileSize, stackOnly, content, truncated?}",
            example = ex("stackOnly" to true),
        ),
        MethodSpec(
            name = "debug.tap",
            description = "Dispatch a tap gesture at the given Activity-space coordinates.",
            params = listOf(
                ParamSpec("x", "number", required = true, description = "Screen x in pixels."),
                ParamSpec("y", "number", required = true, description = "Screen y in pixels."),
            ),
            returns = "{ok, point:{x,y}}",
            example = ex("x" to 300, "y" to 600),
        ),
        MethodSpec(
            name = "debug.scroll",
            description = "Simulate a scroll gesture from a point by deltaX/deltaY.",
            params = listOf(
                ParamSpec("x", "number", required = true, description = "Start x in pixels."),
                ParamSpec("y", "number", required = true, description = "Start y in pixels."),
                ParamSpec("deltaX", "number", required = false, default = 0, description = "Horizontal delta."),
                ParamSpec("deltaY", "number", required = false, default = 0, description = "Vertical delta. At least one of deltaX/deltaY must be non-zero."),
            ),
            returns = "{ok, start:{x,y}, delta:{x,y}}",
            example = ex("x" to 300, "y" to 600, "deltaY" to -400),
        ),
        MethodSpec(
            name = "debug.inputText",
            description = "Type text into the currently focused field.",
            params = listOf(
                ParamSpec("text", "string", required = true, description = "Text to input."),
            ),
            returns = "{ok, length}",
            example = ex("text" to "hello world"),
        ),
        MethodSpec(
            name = "debug.setClipboard",
            description = "Put text on the system clipboard from inside the app process, " +
                "so `input keyevent PASTE` can trigger a REAL single-shot paste. " +
                "debug.inputText types per character and therefore cannot exercise " +
                "paste-detection logic.",
            params = listOf(
                ParamSpec("text", "string", required = true, description = "Text to place on the clipboard."),
                ParamSpec("label", "string", required = false, default = "minis-debug", description = "ClipData label."),
            ),
            returns = "{ok, length, clipboardLength}",
            example = ex("text" to "a long block of text"),
        ),
        MethodSpec(
            name = "debug.llmRequests",
            description = "Return captured LLM provider requests/responses for debugging.",
            params = listOf(
                ParamSpec("last", "int", required = false, description = "Limit to the N most recent entries."),
                ParamSpec("formatted", "bool", required = false, default = false, description = "If true, return a single 'text' string (same shape as iOS Copy Requests output) instead of structured JSON."),
            ),
            returns = "{count, requests:[...]} or {count, text} when formatted=true",
            example = ex("last" to 5),
        ),
        MethodSpec(
            name = "debug.llmRequests.clear",
            description = "Clear the captured LLM request log.",
            params = emptyList(),
            returns = "{cleared: true}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.agentTrace",
            description = "Get agent request traces (alias of debug.llmRequests, iOS-compatible shape).",
            params = listOf(
                ParamSpec("last", "bool", required = false, default = false, description = "If true, return only the most recent trace object instead of {traces:[...]}."),
            ),
            returns = "{traces:[...]} or a single trace object when last=true",
            example = ex("last" to true),
        ),
        MethodSpec(
            name = "debug.fetch",
            description = "Diagnostic HTTP probe: runs DNS, HttpURLConnection, OkHttp, and proxy-selector checks against a URL.",
            params = listOf(
                ParamSpec("url", "string", required = true, description = "Absolute URL to probe."),
            ),
            returns = "{dns?, dns_error?, httpurlconn_status?, httpurlconn_error?, okhttp_status?, okhttp_error?, proxies?, proxy_error?}",
            example = ex("url" to "https://example.com"),
        ),
        MethodSpec(
            name = "debug.logs.setEnabled",
            description = "Enable or disable AppLogger file capture at runtime.",
            params = listOf(
                ParamSpec("enabled", "bool", required = true, description = "true to start capturing, false to stop."),
            ),
            returns = "{enabled}",
            example = ex("enabled" to true),
        ),
        MethodSpec(
            name = "debug.viewTree",
            description = "Return the foreground Activity's View hierarchy with embedded Compose semantics nodes (shallow). Addresses are good only until the next viewTree/search call.",
            params = listOf(
                ParamSpec("maxDepth", "int", required = false, default = 50, description = "Maximum tree depth to traverse."),
            ),
            returns = "Array of root nodes; each node has {address, type, bounds, text?, description?, children?, compose?}.",
            example = ex("maxDepth" to 20),
        ),
        MethodSpec(
            name = "debug.search",
            description = "Search the View + Compose semantics tree for nodes matching a keyword (case-insensitive substring).",
            params = listOf(
                ParamSpec("keyword", "string", required = true, description = "Substring matched against text, contentDescription, type, or testTag."),
                ParamSpec("scope", "string", required = false, default = "all", description = "'all', 'text', or 'type'."),
            ),
            returns = "Array of matching nodes (shallow describe shape).",
            example = ex("keyword" to "Login", "scope" to "text"),
        ),
        MethodSpec(
            name = "debug.inspect",
            description = "Return full properties of the node at the given address from a prior viewTree/search call.",
            params = listOf(
                ParamSpec("address", "string", required = true, description = "Address returned by viewTree/search."),
            ),
            returns = "Node object with extra detail (alpha, class, idName, actions…), or {error} if not found.",
            example = ex("address" to "0x12abcd"),
        ),
        MethodSpec(
            name = "debug.highlight",
            description = "Flash a colored overlay on a specific node for visual verification.",
            params = listOf(
                ParamSpec("address", "string", required = true, description = "Address returned by viewTree/search."),
                ParamSpec("color", "string", required = false, default = "red", description = "Named color: red, green, blue, yellow, purple, orange — or any #RRGGBB."),
                ParamSpec("duration", "number", required = false, default = 2.0, description = "Seconds to keep the overlay visible (0.1..10)."),
            ),
            returns = "{ok: bool}",
            example = ex("address" to "0x12abcd", "color" to "green", "duration" to 1.5),
        ),
        MethodSpec(
            name = "debug.cloudSync",
            description = "Return cloud sync status. Android does not implement iCloud sync — this method is a stub returning {enabled: false, status: 'unsupported'} for iOS API compatibility.",
            params = emptyList(),
            returns = "{enabled, status, devices, deviceCount}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.writeFile",
            description = "Write a file into the app's filesDir-rooted Linux-path namespace (resolved through PRoot bind mounts). Intended for staging test fixtures before debug.shellExecute.",
            params = listOf(
                ParamSpec("path", "string", required = true, description = "Linux path under the proot rootfs (e.g. /tmp/test.sh, /var/minis/skills/example.md)."),
                ParamSpec("content", "string", required = true, description = "File content. Encoded per the 'encoding' field."),
                ParamSpec("encoding", "string", required = false, default = "utf8", description = "'utf8' or 'base64'."),
                ParamSpec("overwrite", "bool", required = false, default = true, description = "Whether to overwrite an existing file."),
                ParamSpec("mode", "string", required = false, default = "0644", description = "Octal file mode, e.g. '0644' or '0755'. Setting mode is best-effort on Android."),
            ),
            returns = "{ok, path, hostPath, size}",
            example = ex("path" to "/tmp/hello.sh", "content" to "IyEvYmluL3NoCmVjaG8gaGVsbG8K", "encoding" to "base64", "mode" to "0755"),
        ),
        MethodSpec(
            name = "debug.screenshot.capture",
            description = "Capture a screenshot into the in-memory ring buffer with an optional label.",
            params = listOf(
                ParamSpec("label", "string", required = false, default = "", description = "Free-form label."),
                ParamSpec("scale", "number", required = false, default = 0.5, description = "Render scale (0 < scale <= 1)."),
            ),
            returns = "{id, label, timestamp, size}",
            example = ex("label" to "before-tap"),
        ),
        MethodSpec(
            name = "debug.screenshot.list",
            description = "List entries in the screenshot ring buffer (newest last).",
            params = emptyList(),
            returns = "{count, entries:[{id, label, timestamp, size}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.screenshot.get",
            description = "Return a single screenshot from the ring buffer as base64 PNG.",
            params = listOf(
                ParamSpec("id", "int", required = false, description = "Entry id; defaults to the latest entry."),
            ),
            returns = "{id, label, timestamp, base64, size, encoding: 'png'}",
            example = ex("id" to 3),
        ),
        MethodSpec(
            name = "debug.screenshot.clear",
            description = "Clear the screenshot ring buffer.",
            params = emptyList(),
            returns = "{cleared: int}",
            example = JSONObject(),
        ),

        // --- Browser ---
        MethodSpec(
            name = "debug.browser.listTabs",
            description = "List all open browser tabs in the application-scoped pool.",
            params = emptyList(),
            returns = "{tabs:[{id, url, title, selected, inUse}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.browser.pageInfo",
            description = "Get URL/title/loading-state/viewport for a tab.",
            params = listOf(
                ParamSpec("tabId", "int", required = false, description = "Target tab id; defaults to selected tab."),
            ),
            returns = "{tabId, url, title, isLoading, canGoBack, canGoForward, viewport, text}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.browser.executeJS",
            description = "Run arbitrary JavaScript in a tab's WebView and return the evaluated result.",
            params = listOf(
                ParamSpec("script", "string", required = true, description = "JS source. The expression's value is stringified and returned."),
                ParamSpec("tabId", "int", required = false, description = "Target tab id; defaults to selected tab."),
            ),
            returns = "{result}",
            example = ex("script" to "document.title"),
        ),
        MethodSpec(
            name = "debug.browser.getReadable",
            description = "Run the Readability-style content extraction on a tab.",
            params = listOf(
                ParamSpec("tabId", "int", required = false, description = "Target tab id; defaults to selected tab."),
            ),
            returns = "{tabId, text, length}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.browser.getText",
            description = "Extract text content from the page, optionally scoped to a CSS selector.",
            params = listOf(
                ParamSpec("selector", "string", required = false, description = "CSS selector to scope the extraction."),
                ParamSpec("tabId", "int", required = false, description = "Target tab id; defaults to selected tab."),
            ),
            returns = "{tabId, text, length}",
            example = ex("selector" to "article"),
        ),
        MethodSpec(
            name = "debug.browser.screenshot",
            description = "Capture a JPEG screenshot of a tab's WebView.",
            params = listOf(
                ParamSpec("tabId", "int", required = false, description = "Target tab id; defaults to selected tab."),
            ),
            returns = "{base64, size, encoding: 'jpeg', tabId}",
            example = JSONObject(),
        ),

        // --- Provider (read-only — Phase 1) ---
        MethodSpec(
            name = "provider.types",
            description = "Return the schema of every supported provider type, including built-in model ids and OAuth flows.",
            params = emptyList(),
            returns = "{types:[{id, displayName, supportedCredentials, oauthFlow, defaultBaseURL, customBaseURLSupported, appendV1SuffixConfigurable, builtInModelIds}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "provider.instances.list",
            description = "List all configured provider instances (write-only credential material excluded).",
            params = listOf(
                ParamSpec("includeDisabled", "bool", required = false, default = true, description = "If false, omit instances where isEnabled=false."),
            ),
            returns = "{count, instances:[{id, label, providerType, credentialType, isEnabled, customBaseURL, appendV1Suffix, useResponsesAPI, createdAt, hasCredential, modelEntryCount}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "provider.models.list",
            description = "List model entries for a specific provider instance.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
                ParamSpec("includeHidden", "bool", required = false, default = false, description = "Include user-hidden entries."),
            ),
            returns = "{instanceId, instanceLabel, count, entries:[...]}",
            example = ex("instanceId" to "pi_xyz"),
        ),
        MethodSpec(
            name = "provider.export",
            description = "Export a provider instance (config + models + stored API key) as a portable JSON envelope. Mirrors the in-app Share/Export button. The API key is base64-wrapped to survive JSON string escaping; this is NOT a security boundary — callers of the 5321 RPC are trusted.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
            ),
            returns = "{version, exportedAt, platform, instanceId, instanceLabel, config:{providerType, label, credentialType, apiKey?, customBaseURL?, models:[...]}}",
            example = ex("instanceId" to "pi_xyz"),
        ),
        MethodSpec(
            name = "provider.import",
            description = "Import a provider instance from a JSON document produced by `provider.export` or the in-app Share button. Accepts either the wrapped envelope or the raw legacy config JSON for cross-platform compatibility. Always assigns a new instance UUID; duplicate labels are auto-suffixed.",
            params = listOf(
                ParamSpec("configJson", "string", required = true, description = "Either the {version,config:{...}} envelope or the raw legacy config JSON."),
            ),
            returns = "{instanceId, instanceLabel, modelEntryCount, success}",
            example = ex("configJson" to "{\"version\":1,\"config\":{...}}"),
        ),
        MethodSpec(
            name = "provider.groups.list",
            description = "List all configured model groups.",
            params = listOf(
                ParamSpec("includeMembers", "bool", required = false, default = true, description = "Include resolved member summaries in each group."),
            ),
            returns = "{defaultGroupId, count, groups:[{id, name, strategy, fallbackStrategy, isDefault, inAgentLoop, memberEntryIds, members?}]}",
            example = JSONObject(),
        ),

        // --- Chat (read-only — Phase 1) ---
        MethodSpec(
            name = "chat.sessions.list",
            description = "List recent chat sessions sorted by updated_at desc.",
            params = listOf(
                ParamSpec("limit", "int", required = false, default = 50, description = "Max sessions to return (1..500)."),
                ParamSpec("includeEmpty", "bool", required = false, default = false, description = "If true, include sessions with no messages."),
            ),
            returns = "{count, sessions:[...]}",
            example = ex("limit" to 20),
        ),
        MethodSpec(
            name = "chat.sessions.get",
            description = "Fetch a single session's metadata.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
            ),
            returns = "{id, title, modelId, modelName, source, isRunning, memoryEnabled, category, messageCount, createdAt, updatedAt}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "chat.sessions.usage",
            description = "Aggregate token usage for a session, optionally broken down per assistant turn.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("perTurn", "bool", required = false, default = false, description = "If true, include a turns:[] breakdown."),
            ),
            returns = "{sessionId, totals:{...}, turns?}",
            example = ex("sessionId" to "6D0F…", "perTurn" to true),
        ),
        MethodSpec(
            name = "chat.messages.list",
            description = "List messages in a session in chronological order, with optional filtering.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("limit", "int", required = false, default = 200, description = "Max messages (1..1000)."),
                ParamSpec("offset", "int", required = false, default = 0, description = "Skip the first N matched messages."),
                ParamSpec("roles", "[string]", required = false, description = "Filter by role: user/assistant/system/tool."),
                ParamSpec("includeTools", "bool", required = false, default = true, description = "Include tool-call/tool-result blocks."),
                ParamSpec("includeReasoning", "bool", required = false, default = false, description = "Include captured reasoning_content."),
            ),
            returns = "{sessionId, totalCount, count, messages:[...]}",
            example = ex("sessionId" to "6D0F…", "limit" to 50),
        ),
        MethodSpec(
            name = "chat.models.list",
            description = "Return the candidate models and groups visible to the chat picker.",
            params = listOf(
                ParamSpec("includeHidden", "bool", required = false, default = false, description = "Include user-hidden entries."),
                ParamSpec("includeDisabled", "bool", required = false, default = false, description = "Include entries from disabled provider instances."),
            ),
            returns = "{defaultGroupId, groupCount, entryCount, groups:[...], entries:[...]}",
            example = JSONObject(),
        ),

        // --- Provider mutate ---
        MethodSpec(
            name = "provider.instances.create",
            description = "Create a new provider instance. apiKey/oauthToken are write-only.",
            params = listOf(
                ParamSpec("providerType", "string", required = true, description = "anthropic / gemini / openAI / openRouter"),
                ParamSpec("label", "string", required = true, description = "User-visible name."),
                ParamSpec("credentialType", "string", required = false, default = "apiKey", description = "apiKey or oauth"),
                ParamSpec("apiKey", "string", required = false, description = "Stored encrypted; never returned by any read."),
                ParamSpec("oauthToken", "string", required = false, description = "Manual OAuth token seed."),
                ParamSpec("customBaseURL", "string", required = false, description = "openAI only."),
                ParamSpec("appendV1Suffix", "bool", required = false, default = true, description = "Whether to append /v1 to customBaseURL."),
                ParamSpec("useResponsesAPI", "bool", required = false, default = false, description = "openAI only — route via /v1/responses."),
                ParamSpec("customUserAgent", "string", required = false, description = "Override outbound User-Agent. apiKey providers only."),
                ParamSpec("isEnabled", "bool", required = false, default = true, description = "Create in enabled state."),
                ParamSpec("seedBuiltInModels", "bool", required = false, default = true, description = "If false, create instance with no model entries."),
            ),
            returns = "{instance:{...}}",
            example = ex("providerType" to "openAI", "label" to "DeepSeek", "apiKey" to "sk-…", "customBaseURL" to "https://api.deepseek.com", "seedBuiltInModels" to false),
        ),
        MethodSpec(
            name = "provider.instances.update",
            description = "Patch fields on an existing instance. Omitted fields are untouched. Pass apiKey=\"\" to clear.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
                ParamSpec("label", "string", required = false, description = "New display name."),
                ParamSpec("apiKey", "string", required = false, description = "Replace stored API key. \"\" clears."),
                ParamSpec("oauthToken", "string", required = false, description = "Replace stored OAuth token."),
                ParamSpec("customBaseURL", "string", required = false, description = "null reverts to default."),
                ParamSpec("appendV1Suffix", "bool", required = false, description = "Update suffix behavior."),
                ParamSpec("useResponsesAPI", "bool", required = false, description = "openAI only."),
                ParamSpec("customUserAgent", "string", required = false, description = "Override outbound User-Agent (null/\"\" reverts to default). apiKey providers only."),
                ParamSpec("isEnabled", "bool", required = false, description = "Enable / disable."),
                ParamSpec("imageEndpointMode", "string", required = false, description = "auto | images_generations | chat_completions. Forcing non-auto clears the cached probe result (GH#68)."),
            ),
            returns = "{instance:{...}}",
            example = ex("instanceId" to "pi_xyz", "isEnabled" to false),
        ),
        MethodSpec(
            name = "provider.instances.delete",
            description = "Remove a provider instance, all its model entries, and its stored credential.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
                ParamSpec("confirm", "bool", required = true, default = false, description = "Must be true."),
            ),
            returns = "{instanceId, deletedModelEntries, deleted}",
            example = ex("instanceId" to "pi_xyz", "confirm" to true),
        ),
        MethodSpec(
            name = "provider.instances.test",
            description = "Probe an instance with the stored credential to verify reachability.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
                ParamSpec("timeoutMs", "int", required = false, default = 10000, description = "Clamped to [1000, 30000]."),
            ),
            returns = "{ok, httpStatus, latencyMs, reachableModelCount?, error?}",
            example = ex("instanceId" to "pi_xyz"),
        ),
        MethodSpec(
            name = "provider.models.add",
            description = "Add a custom model entry to an instance.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
                ParamSpec("modelId", "string", required = true, description = "API model id."),
                ParamSpec("displayName", "string", required = false, description = "Defaults to a prettified modelId."),
                ParamSpec("contextWindow", "int", required = false, description = "Tokens."),
                ParamSpec("maxOutputTokens", "int", required = false, description = "Tokens."),
                ParamSpec("supportsReasoning", "bool", required = false, description = "Tri-state — pass null to leave unknown."),
                ParamSpec("supportsImageInput", "bool", required = false, description = "Modality flag."),
                ParamSpec("supportsAudioInput", "bool", required = false, description = "Modality flag."),
                ParamSpec("supportsVideoInput", "bool", required = false, description = "Modality flag."),
                ParamSpec("supportsPDFInput", "bool", required = false, description = "Modality flag."),
                ParamSpec("supportsImageOutput", "bool", required = false, description = "Modality flag."),
            ),
            returns = "Entry shape (same as provider.models.list[].entries[i]).",
            example = ex("instanceId" to "pi_xyz", "modelId" to "deepseek-v4-pro"),
        ),
        MethodSpec(
            name = "provider.models.update",
            description = "Patch a model entry. Omitted fields are untouched; pass null to clear an override; modelId only mutable on custom entries.",
            params = listOf(
                ParamSpec("entryId", "string", required = true, description = "Target entry UUID."),
                ParamSpec("displayName", "string", required = false, description = "null clears the override."),
                ParamSpec("maxOutputTokens", "int", required = false, description = "null clears the override."),
                ParamSpec("contextWindow", "int", required = false, description = "null clears the override."),
                ParamSpec("isHidden", "bool", required = false, description = "Toggle picker visibility."),
                ParamSpec("modelId", "string", required = false, description = "Custom entries only."),
            ),
            returns = "Updated entry.",
            example = ex("entryId" to "entry_123", "isHidden" to true),
        ),
        MethodSpec(
            name = "provider.models.delete",
            description = "Remove a custom model entry. Built-in entries can't be deleted — hide them via update isHidden=true.",
            params = listOf(
                ParamSpec("entryId", "string", required = true, description = "Target entry UUID."),
                ParamSpec("confirm", "bool", required = true, default = false, description = "Must be true."),
            ),
            returns = "{entryId, deleted}",
            example = ex("entryId" to "entry_123", "confirm" to true),
        ),
        MethodSpec(
            name = "provider.models.refresh",
            description = "Re-query the provider's /v1/models for an instance and update the entry list.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
            ),
            returns = "{instanceId, added, disappeared, total, durationMs}",
            example = ex("instanceId" to "pi_xyz"),
        ),
        MethodSpec(
            name = "provider.models.setAgentLoop",
            description = "Toggle whether a model entry is exposed to the in-shell minis-model-use agent.",
            params = listOf(
                ParamSpec("entryId", "string", required = true, description = "Target entry UUID."),
                ParamSpec("inLoop", "bool", required = true, description = "true to add, false to remove."),
            ),
            returns = "{entryId, inLoop}",
            example = ex("entryId" to "entry_123", "inLoop" to true),
        ),
        MethodSpec(
            name = "provider.groups.create",
            description = "Create a new model group.",
            params = listOf(
                ParamSpec("name", "string", required = true, description = "Display name."),
                ParamSpec("memberEntryIds", "[string]", required = false, description = "Initial member entries (order significant for fallback)."),
                ParamSpec("strategy", "string", required = false, default = "fallback", description = "fallback / loadBalance"),
                ParamSpec("fallbackStrategy", "string", required = false, default = "default", description = "default / always"),
            ),
            returns = "{group:{...}}",
            example = ex("name" to "Coding", "memberEntryIds" to JSONArray().apply { put("entry_a") }),
        ),
        MethodSpec(
            name = "provider.groups.update",
            description = "Patch fields on an existing group.",
            params = listOf(
                ParamSpec("groupId", "string", required = true, description = "Target group UUID."),
                ParamSpec("name", "string", required = false, description = "Must be non-empty if supplied."),
                ParamSpec("memberEntryIds", "[string]", required = false, description = "Replace members entirely; order preserved."),
                ParamSpec("strategy", "string", required = false, description = "fallback / loadBalance"),
                ParamSpec("fallbackStrategy", "string", required = false, description = "default / always"),
            ),
            returns = "{group:{...}}",
            example = ex("groupId" to "grp_xyz"),
        ),
        MethodSpec(
            name = "provider.groups.delete",
            description = "Remove a group. Sessions bound to it fall back to the default group.",
            params = listOf(
                ParamSpec("groupId", "string", required = true, description = "Target group UUID."),
                ParamSpec("confirm", "bool", required = true, default = false, description = "Must be true."),
            ),
            returns = "{groupId, deleted, wasDefault}",
            example = ex("groupId" to "grp_xyz", "confirm" to true),
        ),
        MethodSpec(
            name = "provider.groups.setDefault",
            description = "Set or clear the global default model group.",
            params = listOf(
                ParamSpec("groupId", "string", required = true, description = "Target group UUID, or null to clear."),
            ),
            returns = "{defaultGroupId}",
            example = ex("groupId" to "grp_xyz"),
        ),
        MethodSpec(
            name = "provider.groups.setAgentLoop",
            description = "Toggle whether a model group is exposed to the in-shell minis-model-use agent.",
            params = listOf(
                ParamSpec("groupId", "string", required = true, description = "Target group UUID."),
                ParamSpec("inLoop", "bool", required = true, description = "true to add, false to remove."),
            ),
            returns = "{groupId, inLoop}",
            example = ex("groupId" to "grp_xyz", "inLoop" to true),
        ),

        // --- Chat mutate ---
        MethodSpec(
            name = "chat.prompt",
            description = "Send a prompt to a new or existing chat session. wait=true blocks until completion.",
            params = listOf(
                ParamSpec("prompt", "string", required = true, description = "User message text."),
                ParamSpec("sessionId", "string", required = false, description = "Existing session id; omit to create a new one (source=debug)."),
                ParamSpec("attachments", "[object]", required = false, description = "Array of {name, data, mime?}; data is base64."),
                ParamSpec("modelEntryId", "string", required = false, description = "Pin to a specific model entry (mutually exclusive with modelGroupId)."),
                ParamSpec("modelGroupId", "string", required = false, description = "Pin to a model group."),
                ParamSpec("thinkingLevel", "string", required = false, description = "off / low / medium / high / xhigh / max / ultra — applies before send; leaves the VM setting alone when omitted."),
                ParamSpec("wait", "bool", required = false, default = false, description = "Block until completion."),
                ParamSpec("waitTimeout", "int", required = false, default = 600, description = "Seconds; clamped to [1, 1800]."),
            ),
            returns = "{sessionId, isNewSession, modelName, status, prompt, responseText, userMessageId, timedOut?}",
            example = ex("prompt" to "Hello", "wait" to true),
        ),
        MethodSpec(
            name = "chat.retry",
            description = "Retry from a specific user message in a session.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("messageId", "string", required = false, description = "User message id; omit to retry from the most recent user message."),
                ParamSpec("modelEntryId", "string", required = false, description = "Pin retry to a specific entry."),
                ParamSpec("modelGroupId", "string", required = false, description = "Pin retry to a group."),
                ParamSpec("wait", "bool", required = false, default = false, description = "Block until completion."),
                ParamSpec("waitTimeout", "int", required = false, default = 600, description = "Seconds; clamped to [1, 1800]."),
            ),
            returns = "{sessionId, status, retriedMessageId, deletedMessageCount, modelName, responseText, timedOut?}",
            example = ex("sessionId" to "6D0F…", "wait" to true),
        ),
        MethodSpec(
            name = "chat.rerunFromToolBlock",
            description = "Block-boundary re-run: cut at a specific tool_use block (keep earlier blocks in its turn, drop it + everything after) and regenerate. In-app this is the tool-bubble long-press 'Re-run From Here'.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("assistantMessageId", "string", required = true, description = "UI assistant bubble id owning the tool block."),
                ParamSpec("blockId", "string", required = true, description = "Tool block id (equals its tool_use id)."),
                ParamSpec("wait", "bool", required = false, default = false, description = "Block until completion."),
                ParamSpec("waitTimeout", "int", required = false, default = 600, description = "Seconds; clamped to [1, 1800]."),
            ),
            returns = "{sessionId, status, deletedMessageCount, responseText, timedOut?}",
            example = ex("sessionId" to "6D0F…", "assistantMessageId" to "assistant_…", "blockId" to "call_…", "wait" to true),
        ),
        MethodSpec(
            name = "chat.session.status",
            description = "Poll a session's live status (after async chat.prompt / chat.retry).",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
            ),
            returns = "{sessionId, title, modelName, isRunning, messageCount, lastMessageRole, updatedAt}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "chat.session.cancel",
            description = "Cancel an in-progress agent run. No-op if the session isn't running.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
            ),
            returns = "{sessionId, wasRunning, cancelled}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "chat.session.selectModel",
            description = "Switch the live session's bound model mid-session (same as tapping a model in the in-app picker: ChatViewModel.selectEntry). Unlike chat.prompt's modelEntryId (which only rewrites the DB binding and is ignored by the loaded VM), this re-resolves the live model/provider. The thinking level is preserved across the switch; the response echoes it back.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("modelEntryId", "string", required = true, description = "Model entry id to bind (from chat.models.list)."),
            ),
            returns = "{sessionId, modelEntryId, modelName, thinkingLevel}",
            example = ex("sessionId" to "6D0F…", "modelEntryId" to "…/gpt-5.6-sol"),
        ),
        MethodSpec(
            name = "chat.compact.markers.list",
            description = "List all compact markers on a session, oldest → newest. Includes v2 fields (anchorMessageId, version, summaryLength, summaryPreview).",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("includeFullSummary", "bool", required = false, default = false, description = "Include the full summary string in each marker (default: 120-char preview only)."),
            ),
            returns = "{sessionId, count, markers:[{id, version, anchorMessageId, firstKeptMessageId, summaryLength, summaryPreview, compactedCount, createdAt}]}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "chat.compact.before",
            description = "Trigger compaction on a session. includesBoundary=true → compactAll semantics (the in-app /compact slash command, anchor = last active message). includesBoundary=false → 'compact up through messageId' (long-press equivalent, anchor = caller-supplied DB message id). In v2 both modes write the same shape of marker — includesBoundary is preserved for ABI compat with iOS.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("messageId", "string", required = false, description = "DB message id to use as the anchor. Required when includesBoundary=false."),
                ParamSpec("includesBoundary", "bool", required = false, default = false, description = "true → compactAll mode (auto-anchor on last active message)."),
                ParamSpec("waitTimeout", "int", required = false, default = 120, description = "Seconds to wait for compaction LLM call. Clamped [1,1800]."),
            ),
            returns = "{sessionId, messageId, includesBoundary, beforeMarkerCount, afterMarkerCount, wrote, status, timedOut?, error?, latestMarker:{id, version, anchorMessageId, summaryLength, compactedCount, createdAt}}",
            example = ex("sessionId" to "6D0F…", "includesBoundary" to true),
        ),
        MethodSpec(
            name = "chat.compact.revert",
            description = "Revert the most recent compact on a session. Drops the latest marker; cachedLatestMarker falls back to the previous one (or null), then the UI rebuilds.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
            ),
            returns = "{sessionId, beforeMarkerCount, afterMarkerCount, removedMarkerId, newLatestMarkerId}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "chat.session.delete",
            description = "Permanently delete a session and its messages. Cancels any in-flight run first.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("confirm", "bool", required = true, default = false, description = "Must be true."),
            ),
            returns = "{sessionId, deleted}",
            example = ex("sessionId" to "6D0F…", "confirm" to true),
        ),
    )

    private fun encode(method: MethodSpec): JSONObject {
        val params = JSONArray()
        for (p in method.params) {
            val obj = JSONObject().apply {
                put("name", p.name)
                put("type", p.type)
                put("required", p.required)
                put("description", p.description)
            }
            if (p.default != null) obj.put("default", p.default)
            params.put(obj)
        }
        return JSONObject().apply {
            put("name", method.name)
            put("description", method.description)
            put("params", params)
            put("returns", method.returns)
            put("example", method.example)
        }
    }

    fun discover(): JSONObject {
        val arr = JSONArray()
        for (m in methods) arr.put(encode(m))
        return JSONObject().apply {
            put("platform", "android")
            put("version", BuildConfig.VERSION_NAME)
            put("build", BuildConfig.VERSION_CODE)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("methodCount", methods.size)
            put("methods", arr)
        }
    }

    val methodNames: List<String>
        get() = methods.map { it.name }
}
