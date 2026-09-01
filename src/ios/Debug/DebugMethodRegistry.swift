#if DEBUG
import Foundation

/// Static catalogue of every JSON-RPC method exposed by the iOS debug server.
///
/// Returned by `rpc.discover` so clients can introspect the API at runtime
/// instead of relying on external documentation. Keep this in sync with the
/// dispatch switch in `DebugJSONRPC.dispatch(method:params:)`.
enum DebugMethodRegistry {

    struct ParamSpec {
        let name: String
        let type: String
        let required: Bool
        let `default`: Any?
        let description: String
    }

    struct MethodSpec {
        let name: String
        let description: String
        let params: [ParamSpec]
        let returns: String
        let example: [String: Any]
    }

    static let methods: [MethodSpec] = [
        MethodSpec(
            name: "rpc.discover",
            description: "Return the full list of supported methods with parameter schemas and examples.",
            params: [],
            returns: "{platform, version, build, methodCount, methods:[{name, description, params, returns, example}]}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.viewTree",
            description: "Return the view hierarchy as a nested tree. Merges UIView subviews AND SwiftUI accessibility elements (rendered Text/Button/Label). Accessibility-only nodes are typed with an 'AX.' prefix and carry their frame in screen-space.",
            params: [
                ParamSpec(name: "maxDepth", type: "int", required: false, default: 50, description: "Maximum tree depth to traverse."),
            ],
            returns: "Single tree object, or array of trees when multiple windows are present. Nodes carry {type, address, frame, identifier?, label?, value?, children?}.",
            example: ["maxDepth": 20]
        ),
        MethodSpec(
            name: "debug.search",
            description: "Search both the UIView tree AND the accessibility element tree. Use scope='text' to find SwiftUI Text content by its accessibilityLabel — a plain view-tree walk misses SwiftUI Text because it isn't materialised as a UIView subview.",
            params: [
                ParamSpec(name: "keyword", type: "string", required: true, default: nil, description: "Case-insensitive substring matched against the chosen scope."),
                ParamSpec(name: "scope", type: "string", required: false, default: "all", description: "'all' (default), 'type', 'identifier', or 'text'. 'text' matches accessibilityLabel, accessibilityValue, and UILabel/UITextField/UITextView text — this is what finds SwiftUI Text."),
            ],
            returns: "Array of matching nodes: {type, address, frame?, identifier?, label?, value?, text?}. Nodes prefixed 'AX.' are accessibility elements (typically SwiftUI content).",
            example: ["keyword": "MiniMax", "scope": "text"]
        ),
        MethodSpec(
            name: "debug.inspect",
            description: "Return full properties of the view at the given address from debug.viewTree.",
            params: [
                ParamSpec(name: "address", type: "string", required: true, default: nil, description: "Opaque address returned by viewTree/search."),
            ],
            returns: "Object with view properties, or {error} if not found.",
            example: ["address": "0x10abc1234"]
        ),
        MethodSpec(
            name: "debug.highlight",
            description: "Flash a colored overlay on a specific view for visual verification.",
            params: [
                ParamSpec(name: "address", type: "string", required: true, default: nil, description: "View address."),
                ParamSpec(name: "color", type: "string", required: false, default: "red", description: "Named color: red, green, blue, yellow, purple, orange."),
                ParamSpec(name: "duration", type: "number", required: false, default: 2.0, description: "Seconds to keep the overlay visible."),
            ],
            returns: "{ok: bool}",
            example: ["address": "0x10abc1234", "color": "green", "duration": 1.5]
        ),
        MethodSpec(
            name: "debug.agentTrace",
            description: "Return recorded agent request traces (prompts, tool calls, responses).",
            params: [
                ParamSpec(name: "last", type: "bool", required: false, default: false, description: "If true, return only the most recent trace."),
            ],
            returns: "{traces: [...]} or a single trace object when last=true.",
            example: ["last": true]
        ),
        MethodSpec(
            name: "debug.agentTrace.clear",
            description: "Drop all recorded agent traces AND captured LLM request/response bodies, freeing their memory. Clears the in-flight trace too.",
            params: [
                ParamSpec(name: "llmRequests", type: "bool", required: false, default: true, description: "Also clear debug.llmRequests captures (where the request/response BODIES live — usually the bulk of the memory). false = clear step traces only."),
            ],
            returns: "{clearedTraces: int, clearedLLMRequests: bool, clearedLLMRequestCount?: int}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.agentTrace.pause",
            description: "Pause/resume in-memory recording of agent traces and LLM requests, so a captured state can be pulled without later requests growing or overwriting it. Omit `paused` to just read the current state.",
            params: [
                ParamSpec(name: "paused", type: "bool", required: false, default: nil, description: "true = stop recording, false = resume. Omit to query only."),
                ParamSpec(name: "llmRequests", type: "bool", required: false, default: true, description: "Also pause debug.llmRequests capture."),
            ],
            returns: "{paused: bool, llmRequestsPaused: bool}",
            example: ["paused": true]
        ),
        MethodSpec(
            name: "debug.ls",
            description: "List directory contents under the app sandbox.",
            params: [
                ParamSpec(name: "path", type: "string", required: false, default: "/", description: "Sandbox-relative path."),
                ParamSpec(name: "recursive", type: "bool", required: false, default: false, description: "Recurse into subdirectories."),
                ParamSpec(name: "maxDepth", type: "int", required: false, default: 3, description: "Max recursion depth when recursive=true."),
            ],
            returns: "Array of {name, type, size, modified} entries.",
            example: ["path": "/Documents", "recursive": true, "maxDepth": 2]
        ),
        MethodSpec(
            name: "debug.readFile",
            description: "Read a file from the app sandbox, with optional offset/limit slicing.",
            params: [
                ParamSpec(name: "path", type: "string", required: true, default: nil, description: "Sandbox-relative file path."),
                ParamSpec(name: "base64", type: "bool", required: false, default: false, description: "Force base64 encoding (otherwise detected from content)."),
                ParamSpec(name: "offset", type: "int", required: false, default: nil, description: "Byte offset to start reading from."),
                ParamSpec(name: "limit", type: "int", required: false, default: 524288, description: "Maximum bytes to read (default 512KB)."),
            ],
            returns: "{size, content, encoding, offset?, bytesRead, truncated?}",
            example: ["path": "/Documents/log.txt", "limit": 4096]
        ),
        MethodSpec(
            name: "debug.writeFile",
            description: "Write a file into the app sandbox (and register it in the iSH fakefs meta.db so the guest can see it). Intended for automated test harnesses to stage test fixtures before running shell commands via debug.shellExecute.",
            params: [
                ParamSpec(name: "path", type: "string", required: true, default: nil, description: "Sandbox-relative file path. Paths under /tmp, /usr/..., etc. map into the iSH rootfs data/ directory so the guest sees the file at that exact path."),
                ParamSpec(name: "content", type: "string", required: true, default: nil, description: "File content. Encoded per the 'encoding' field."),
                ParamSpec(name: "encoding", type: "string", required: false, default: "utf8", description: "'utf8' (default) or 'base64'."),
                ParamSpec(name: "overwrite", type: "bool", required: false, default: true, description: "Whether to overwrite an existing file."),
                ParamSpec(name: "registerInGuest", type: "bool", required: false, default: true, description: "Insert the path into fakefs meta.db so iSH-guest processes can open it. Turn off when writing host-only files (App Group etc.)."),
                ParamSpec(name: "mode", type: "string", required: false, default: "0644", description: "Octal file mode, e.g. '0644' or '0755'. Accepts integer decimal too."),
            ],
            returns: "{ok, path, hostPath, linuxPath, size, registeredInGuest}",
            example: ["path": "/tmp/test.sh", "content": "IyEvYmluL3NoCmVjaG8gaGVsbG8K", "encoding": "base64", "mode": "0755"]
        ),
        MethodSpec(
            name: "debug.mcp.list",
            description: "List the app's currently-active MCP servers (the in-memory MCPStore list, reflecting servers.json). Each entry includes transport (http/stdio), enabled state, and its command/args/env or url/headers.",
            params: [],
            returns: "{count, servers: [{id, enabled, transport, note?, command?, args?, env?, url?, headers?, createdAt?}]}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.mcp.import",
            description: "Import/upsert MCP server(s) from a Claude-style JSON config and apply it LIVE — updates the in-memory list AND persists servers.json (unlike debug.writeFile, which only writes the file and requires a reload). Upserts by server name; existing servers with the same name are overwritten (createdAt preserved), others untouched.",
            params: [
                ParamSpec(name: "json", type: "string", required: true, default: nil, description: "MCP config text: {\"mcpServers\": {\"name\": {...}}} or a bare {\"name\": {...}} map. Each server: {command,args,env} for stdio or {url,headers} for http; optional note/enabled/disabled."),
            ],
            returns: "{ok, importedCount, importedIds, allServerIds}",
            example: ["json": "{\"mcpServers\":{\"blender-mcp\":{\"command\":\"blender-mcp\",\"env\":{\"BLENDER_MCP_HOST\":\"blender.example.com\",\"BLENDER_MCP_PORT\":\"9876\"}}}}"]
        ),
        MethodSpec(
            name: "debug.mcp.delete",
            description: "Delete an MCP server by name from the live list and servers.json.",
            params: [
                ParamSpec(name: "id", type: "string", required: true, default: nil, description: "The server name (servers.json key) to delete."),
            ],
            returns: "{ok, deleted, id, allServerIds}",
            example: ["id": "blender-mcp"]
        ),
        MethodSpec(
            name: "debug.shellExecute",
            description: "Run a shell command inside the iSH guest and return captured stdout/stderr + exit code. Synchronous: blocks until the process exits or the timeout fires. Intended for automated debugging — not interactive use. Set stdinScript=true to mirror the agent's shell_execute tool path (sh with no argv, script fed via stdin pipe) for comparison testing.",
            params: [
                ParamSpec(name: "command", type: "string", required: true, default: nil, description: "Shell command passed to /bin/sh -c (default) or fed via stdin pipe when stdinScript=true."),
                ParamSpec(name: "timeout", type: "number", required: false, default: 60, description: "Max wait time in seconds. Clamped to [1, 600]."),
                ParamSpec(name: "stdinScript", type: "bool", required: false, default: false, description: "When true, run /bin/sh with no argv and feed command via stdin pipe. Mirrors ISHExecutionCoordinator.runCommand path used by the LLM agent. Required for sessionId routing."),
                ParamSpec(name: "env", type: "object", required: false, default: nil, description: "Optional map of environment overrides passed to the child process."),
                ParamSpec(name: "sessionId", type: "string", required: false, default: nil, description: "Stamp the spawned shell's task group with this session's fs_context, so /var/minis/{offloads,attachments,workspace,browser} route to that session's host bucket. Requires stdinScript=true. Omit for the default global view (fs_context=0)."),
            ],
            returns: "{exitCode, pid, error, stdout, stderr, duration, timedOut}",
            example: ["command": "ls /var/minis/workspace", "timeout": 30, "stdinScript": true, "sessionId": "ABC123..."]
        ),
        MethodSpec(
            name: "debug.scrollMetrics",
            description: "In-memory scroll diagnostics ring buffer (DEBUG builds only, OFF by default). Call with enabled=true to arm recording, then scroll, then call without 'enabled' to dump: every cell self-size (measure), layout height correction (inv), settle flush (flush), settle anchor shift (settle), full re-flow (prepare) and per-frame offset sample (scroll). No thresholds — complete data.",
            params: [
                ParamSpec(name: "enabled", type: "bool", required: false, default: nil, description: "Arm (true) or disarm (false) recording; disarming empties the ring. When present, only toggles — no events returned."),
                ParamSpec(name: "clear", type: "bool", required: false, default: false, description: "Empty the ring after reading."),
            ],
            returns: "{count, events:[{t, kind, idx, a, b, key, src, pos, offset, note}]} — a/b are kind-specific (measure: est→fit; inv/flush: old→new; settle: screenDelta; prepare: itemCount/totalHeight; scroll: offset/contentSizeHeight).",
            example: ["clear": true]
        ),
        MethodSpec(
            name: "debug.appInfo",
            description: "Return app metadata: paths, build date, disk usage.",
            params: [],
            returns: "{app, version, build, buildDate, paths, diskUsage}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.backup.export",
            description: "Build a .minisbak package (export only; unencrypted, stage 1).",
            params: [
                ParamSpec(name: "categories", type: "array", required: false, default: nil,
                          description: "Category raw values; default = all. Known: chats, shared_files, skills, memory, providers, mcp_servers, voice_corrections"),
                ParamSpec(name: "maxFileBytes", type: "int", required: false, default: nil,
                          description: "Per-file size cap; omit for unlimited (the default)"),
                ParamSpec(name: "keep", type: "boolean", required: false, default: false,
                          description: "Move the package into Documents so it survives tmp cleanup"),
                ParamSpec(name: "includeCredentials", type: "boolean", required: false, default: true,
                          description: "Include Keychain credentials in secrets.json. false = share copy."),
                ParamSpec(name: "passphrase", type: "string", required: false, default: nil,
                          description: "Encrypt the package (minisbak-enc/1). REQUIRED when credentials are included."),
                ParamSpec(name: "deliver", type: "boolean", required: false, default: false,
                          description: "Move the package into Files-visible shared storage (Minis ▸ Backups), the path the Backup UI uses."),
            ],
            returns: "{backupId, path, bytes, durationMs, categories, skippedFiles, skippedBytes, skippedPaths}",
            example: ["keep": true]
        ),
        MethodSpec(
            name: "debug.backup.restore",
            description: "Import a .minisbak package (stage 2: Merge mode, unencrypted).",
            params: [
                ParamSpec(name: "path", type: "string", required: true, default: nil,
                          description: "Path to the .minisbak file"),
                ParamSpec(name: "categories", type: "array", required: false, default: nil,
                          description: "Category raw values; default = all present in the package"),
                ParamSpec(name: "skipIntegrityCheck", type: "boolean", required: false, default: false,
                          description: "Diagnostics only — skip the SHA-256 verification pass"),
                ParamSpec(name: "passphrase", type: "string", required: false, default: nil,
                          description: "Required to restore an encrypted package"),
            ],
            returns: "{backupId, durationMs, integrityChecked, integrityFailed, rolledBack, totals, categories, warnings}",
            example: ["path": "/…/backup.minisbak"]
        ),
        MethodSpec(
            name: "debug.backup.cleanup",
            description: "Delete .minisbak packages left in Documents by export {keep:true}.",
            params: [],
            returns: "{removed, count, freedBytes}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.backup.rescue",
            description: "Salvage what can be read from a damaged .minisbak: rescue.json, manifest.json, its tail copy, and the surviving member list.",
            params: [
                ParamSpec(name: "path", type: "string", required: true, default: nil,
                          description: "Path to the (possibly damaged) .minisbak file"),
            ],
            returns: "{stages, backupId, blobCount, sessionCount, sessions, blobs, categories, missingMembers, notImplemented}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.backup.destinations",
            description: "Inspect/set backup destination mounted folders (§6.2 path 2), and optionally deliver a package to them.",
            params: [
                ParamSpec(name: "select", type: "array", required: false, default: nil,
                          description: "Mount ids to set as destinations"),
                ParamSpec(name: "deliver", type: "string", required: false, default: nil,
                          description: "Path to a .minisbak to copy to every destination"),
            ],
            returns: "{mounts, selectedIds, eligibleCount, packagesInDestinations, delivered?}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.backup.open",
            description: "Drive the 'opened a .minisbak from Files' entry point (BackupOpenRouter.handle) and report whether the root restore sheet would present.",
            params: [
                ParamSpec(name: "path", type: "string", required: true, default: nil,
                          description: "Path to the .minisbak file to open"),
            ],
            returns: "{recognisedAsPackage, handled, pendingPackage, hasPendingPackage, appIsLocked, sheetWouldPresent}",
            example: ["path": "/…/backup-20260814-0230.minisbak"]
        ),
        MethodSpec(
            name: "debug.backup.inspect",
            description: "List a .minisbak package's entries and read back its manifest.",
            params: [
                ParamSpec(name: "path", type: "string", required: true, default: nil,
                          description: "Path to the .minisbak file"),
            ],
            returns: "{path, entryCount, entries, manifest, lineCounts}",
            example: ["path": "/…/backup-20260814-0230.minisbak"]
        ),
        MethodSpec(
            name: "debug.auth.list",
            description: "List paired debug-auth tokens (id, client, grant/use times — never the key) and the auth-required switch state.",
            params: [],
            returns: "{tokens:[{tok, clientName, grantedAt, lastUsed}], authRequired}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.auth.revoke",
            description: "Revoke one paired debug-auth token by its 8-hex id.",
            params: [
                ParamSpec(name: "tok", type: "string", required: true, default: nil, description: "Token id from debug.auth.list."),
            ],
            returns: "{revoked: bool}",
            example: ["tok": "a1b2c3d4"]
        ),
        MethodSpec(
            name: "debug.auth.revokeAll",
            description: "Revoke every paired debug-auth token and clear the pairing trust window.",
            params: [],
            returns: "{revoked: int}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.auth.setRequired",
            description: "Toggle the debug-auth master switch. When off, plain (legacy) JSON-RPC is accepted again; envelope calls keep working either way.",
            params: [
                ParamSpec(name: "required", type: "bool", required: true, default: nil, description: "true = enforce envelope auth (default)."),
            ],
            returns: "{authRequired: bool}",
            example: ["required": true]
        ),
        MethodSpec(
            name: "debug.db.list",
            description: "List the app's known SQLite databases (minis, skills, provider-config, voice-correction, alarm-labels, rootfs-meta) with existence and size.",
            params: [],
            returns: "{databases: [{name, path, exists, sizeBytes}]}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.db.tables",
            description: "List tables and views in a database.",
            params: [
                ParamSpec(name: "name", type: "string", required: true, default: nil, description: "DB name from debug.db.list."),
            ],
            returns: "{db, tables: [{name, type}]}",
            example: ["name": "minis"]
        ),
        MethodSpec(
            name: "debug.db.schema",
            description: "Show schema. With 'table' → its columns + indexes (PRAGMA); without → full DDL of all objects.",
            params: [
                ParamSpec(name: "name", type: "string", required: true, default: nil, description: "DB name from debug.db.list."),
                ParamSpec(name: "table", type: "string", required: false, default: nil, description: "Optional table name; omit for the whole-db DDL dump."),
            ],
            returns: "{db, table?, columns?, indexes?, objects?}",
            example: ["name": "minis", "table": "messages"]
        ),
        MethodSpec(
            name: "debug.db.query",
            description: "Run a single READ-ONLY SQL query (SELECT/WITH/PRAGMA/EXPLAIN/VALUES) against a database. Connection is opened read-only; writes and multi-statements are rejected.",
            params: [
                ParamSpec(name: "name", type: "string", required: true, default: nil, description: "DB name from debug.db.list."),
                ParamSpec(name: "sql", type: "string", required: true, default: nil, description: "A single read-only statement. No interior ';'."),
                ParamSpec(name: "limit", type: "int", required: false, default: 200, description: "Max rows. Clamped to [1, 2000]."),
            ],
            returns: "{db, columns:[...], rows:[{col:val}], rowCount, truncated}",
            example: ["name": "minis", "sql": "SELECT role, COUNT(*) c FROM messages GROUP BY role", "limit": 20]
        ),
        MethodSpec(
            name: "debug.browser.listTabs",
            description: "List currently open browser tabs.",
            params: [],
            returns: "{tabs: [{id, url, title, selected, inUse}, ...]}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.browser.pageInfo",
            description: "Return URL, title, and viewport info for a browser tab.",
            params: [
                ParamSpec(name: "tabId", type: "int", required: false, default: nil, description: "Tab id; defaults to currently selected tab."),
            ],
            returns: "{id, url, title, scrollX, scrollY, pageWidth, pageHeight, viewportWidth, viewportHeight, readyState}",
            example: ["tabId": 1]
        ),
        MethodSpec(
            name: "debug.browser.executeJS",
            description: "Execute JavaScript in a browser tab and return the result.",
            params: [
                ParamSpec(name: "script", type: "string", required: true, default: nil, description: "JavaScript source to evaluate."),
                ParamSpec(name: "tabId", type: "int", required: false, default: nil, description: "Tab id; defaults to selected."),
            ],
            returns: "{result: any}",
            example: ["script": "document.title", "tabId": 1]
        ),
        MethodSpec(
            name: "debug.browser.getReadable",
            description: "Extract main article text from a tab using Readability.",
            params: [
                ParamSpec(name: "tabId", type: "int", required: false, default: nil, description: "Tab id; defaults to selected."),
            ],
            returns: "{text, length, debug}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.browser.getText",
            description: "Extract text from a CSS selector (or full page) in a tab.",
            params: [
                ParamSpec(name: "selector", type: "string", required: false, default: nil, description: "Optional CSS selector."),
                ParamSpec(name: "tabId", type: "int", required: false, default: nil, description: "Tab id; defaults to selected."),
            ],
            returns: "{text, length, debug}",
            example: ["selector": "article"]
        ),
        MethodSpec(
            name: "debug.browser.screenshot",
            description: "Capture a PNG screenshot of a browser tab.",
            params: [
                ParamSpec(name: "tabId", type: "int", required: false, default: nil, description: "Tab id; defaults to selected."),
            ],
            returns: "{base64, size, encoding: 'png'}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.llmRequests",
            description: "Return captured LLM provider requests/responses for debugging.",
            params: [
                ParamSpec(name: "last", type: "int", required: false, default: nil, description: "Limit to the N most recent entries."),
                ParamSpec(name: "formatted", type: "bool", required: false, default: false, description: "Return a pre-formatted text blob instead of structured items."),
            ],
            returns: "{count, requests:[...]} or {count, text} when formatted=true",
            example: ["last": 5]
        ),
        MethodSpec(
            name: "debug.voiceInputs",
            description: "Return the last ≤5 audio payloads actually sent to the ASR/transcription model (captured just before the request), for diagnosing whether VAD trimmed or dropped speech. Entries recorded during one mic session (VAD start→stop) share a 'sessionId' and carry that session's UN-CUT full recording under 'fullSession' — decode both WAVs and compare to see exactly where VAD cut. In-memory only, DEBUG only.",
            params: [
                ParamSpec(name: "last", type: "int", required: false, default: nil, description: "Limit to the N most recent entries."),
                ParamSpec(name: "includeAudio", type: "bool", required: false, default: true, description: "Include base64-encoded WAV per entry (set false for metadata only)."),
            ],
            returns: "{count, voiceInputs:[{id, capturedAt, byteCount, durationSeconds, audioFormat:{container,sampleRate,channels,bitsPerSample}, vad:{segmentCount,endReason,runningDurationSeconds}, model?, language?, onDeviceRecognition?, audioBase64?, sessionId?, fullSession?:{byteCount,durationSeconds,truncated,audioBase64?}}]}. sessionId groups entries from one mic session; fullSession is that session's untrimmed start→stop recording (same for every entry of the session, absent while the session is still recording, truncated=true if it hit the 10-minute cap).",
            example: ["last": 5, "includeAudio": false]
        ),
        MethodSpec(
            name: "debug.voiceCorrection.traceList",
            description: "Every REAL voice-correction run with its full evidence chain: segmented tokens, retrieved candidates (per source, with fused scores), the hotwords mined from the conversation (rare-term digest WITH per-term rarity scores, plus the message excerpts), which vocab/confusion terms actually survived budget clamping into the prompt, and the model's verdict. This is the tuning view — unlike dryRunCorrection it reports what happened on-device rather than on text you supply. In-memory ring (last 40 runs), DEBUG only.",
            params: [
                ParamSpec(name: "limit", type: "int", required: false, default: 10, description: "Most recent N runs."),
                ParamSpec(name: "sinceID", type: "int", required: false, default: nil, description: "Only runs with id greater than this — poll for new runs without re-reading."),
                ParamSpec(name: "includePrompt", type: "bool", required: false, default: false, description: "Include the full assembled prompt per run (~2.5k chars each; off by default so a listing stays readable)."),
            ],
            returns: "{count, totalRetained, runs:[{id, at, trigger, locale, transcript, segmentedTokens, phoneticKeys, retrieval:{ms,cacheHit,candidateCount,candidates:[{token,phoneticKey,term,source,confidence,fusedScore,evidence}]}, contextMining:{scannedMessages,buildMs,digestChars,digestTerms:[{term,score,count,backgroundRank}],excerptChars,excerpts:[{role,newestIndex,kind,chars,text}]}, promptEvidence:{vocabTerms,confusionLines,blockChars}, verdict:{corrected,changed,appliedPairs,rejectedReason,modelGroup,totalMs}, prompt?}]}",
            example: ["limit": 5, "includePrompt": false]
        ),
        MethodSpec(
            name: "debug.voiceCorrection.traceManualEdits",
            description: "Transcript spans the user fixed BY HAND — i.e. the cases the corrector MISSED. Includes spans that CorrectionAdmission rejected as 'rewrite' (admitted:false), which are invisible everywhere else because nothing is written to the DB and the log carries lengths only. A genuine ASR fix appearing with admitted:false means the admission thresholds need tuning. 'precedingRunID' links a miss back to the traceList run whose prompt failed to catch it. DEBUG only.",
            params: [
                ParamSpec(name: "limit", type: "int", required: false, default: 30, description: "Most recent N edits."),
                ParamSpec(name: "admittedOnly", type: "bool", required: false, default: nil, description: "true = only edits learned into confusion_dictionary; false = only rejected ones (the tuning-relevant set); omit for both."),
            ],
            returns: "{count, edits:[{id, at, source, locale, from, to, fromPhoneticKey, toPhoneticKey, phoneticKeyMatch, admitted, reason, sentenceLen, before, after, precedingRunID}]}",
            example: ["limit": 20, "admittedOnly": false]
        ),
        MethodSpec(
            name: "debug.voiceCorrection.vocabularyStats",
            description: "Aggregate health of typed_vocabulary (the table filling the prompt's largest evidence block): frequency histogram, single-occurrence share, out-of-background-vocabulary share, and noise classes (hexLike UUID fragments / urlEncoded residue / pathFragment terms that entered via attachment markup) with example terms. Answers 'how much of this table is noise?' without paging thousands of rows over RPC. DEBUG only.",
            params: [
                ParamSpec(name: "sample", type: "int", required: false, default: 5000, description: "Rows to sample, highest-frequency first."),
            ],
            returns: "{totalRows, sampled, frequencyHistogram, singleOccurrence, outOfBackgroundVocabulary, noiseClasses, noiseExamples, backgroundListVersion}",
            example: ["sample": 5000]
        ),
        MethodSpec(
            name: "debug.voiceCorrection.traceClear",
            description: "Clear the in-memory correction trace and manual-edit ring. Affects DEBUG capture only — never touches voice-correction.db.",
            params: [],
            returns: "{ok}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.voice.panel",
            description: "Drive the inline voice-input panel for on-device e2e audio tests (synthetic debug.tap cannot press SwiftUI buttons). open/close toggles voice mode; mic acts as the panel's main mic button (start recording / pause+flush / cancel).",
            params: [
                ParamSpec(name: "action", type: "string", required: true, default: nil, description: "open | close | mic"),
            ],
            returns: "{action, voiceActive}",
            example: ["action": "mic"]
        ),
        MethodSpec(
            name: "debug.folders.list",
            description: "List session folders with member counts and last member activity. Test/automation API for the home-list grouping feature.",
            params: [],
            returns: "{count, folders:[{id, name, origin, memberCount, lastActivity}]}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.folders.create",
            description: "Create a session folder (same ChatStore path as the UI picker's New Folder). Posts the session-list refresh notification.",
            params: [
                ParamSpec(name: "name", type: "string", required: true, default: nil, description: "Folder display name (duplicates allowed by design)"),
            ],
            returns: "{id, name}",
            example: ["name": "Work"]
        ),
        MethodSpec(
            name: "debug.folders.move",
            description: "Move sessions into a folder, or out of any folder when folderId is omitted. Mirrors the UI picker's apply path.",
            params: [
                ParamSpec(name: "sessionIds", type: "[string]", required: true, default: nil, description: "Session ids to move"),
                ParamSpec(name: "folderId", type: "string", required: false, default: nil, description: "Target folder id; omit to ungroup"),
            ],
            returns: "{moved, folderId}",
            example: ["sessionIds": ["<sid>"], "folderId": "<fid>"]
        ),
        MethodSpec(
            name: "debug.folders.dissolve",
            description: "Dissolve a folder: members' folder_id cleared, folder deleted, NO session deleted.",
            params: [
                ParamSpec(name: "folderId", type: "string", required: true, default: nil, description: "Folder id"),
            ],
            returns: "{folderId, freedSessions}",
            example: ["folderId": "<fid>"]
        ),
        MethodSpec(
            name: "debug.folders.get",
            description: "One folder in full: metadata (name/origin/pinned/timestamps) plus member sessions (id/title/category/updatedAt), newest first.",
            params: [
                ParamSpec(name: "folderId", type: "string", required: true, default: nil, description: "Folder id (from debug.folders.list)"),
            ],
            returns: "{id, name, icon, color, origin, pinned, createdAt, updatedAt, memberCount, members:[{id,title,category,updatedAt}]}",
            example: ["folderId": "<fid>"]
        ),
        MethodSpec(
            name: "debug.folders.rename",
            description: "Rename a folder (same ChatStore path as the header menu's Rename; rides FolderV2 LWW, members untouched).",
            params: [
                ParamSpec(name: "folderId", type: "string", required: true, default: nil, description: "Folder id"),
                ParamSpec(name: "name", type: "string", required: true, default: nil, description: "New display name"),
            ],
            returns: "{folderId, name}",
            example: ["folderId": "<fid>", "name": "Work"]
        ),
        MethodSpec(
            name: "debug.sessions.badge",
            description: "Inject a session badge (paused/…) with a back-dated entry stamp — exercises the group-card 24h freshness filter.",
            params: [
                ParamSpec(name: "sessionId", type: "string", required: true, default: nil, description: "Session id"),
                ParamSpec(name: "state", type: "string", required: true, default: nil, description: "paused | iCloudSyncing | unread"),
                ParamSpec(name: "ageHours", type: "number", required: false, default: "0", description: "How long ago the state was entered"),
            ],
            returns: "{sessionId, state, ageHours}",
            example: ["sessionId": "<sid>", "state": "paused", "ageHours": 25]
        ),
        MethodSpec(
            name: "debug.appearance.set",
            description: "Set the app Theme override (0 system / 1 light / 2 dark) — drives appearance-dependent rendering for tests.",
            params: [
                ParamSpec(name: "mode", type: "int", required: true, default: nil, description: "0 system, 1 light, 2 dark"),
            ],
            returns: "{mode}",
            example: ["mode": 2]
        ),
        MethodSpec(
            name: "debug.sessions.pin",
            description: "Toggle a session's pin (same ChatStore path as the row menu's Pin).",
            params: [
                ParamSpec(name: "sessionId", type: "string", required: true, default: nil, description: "Session id"),
            ],
            returns: "{sessionId, pinned}",
            example: ["sessionId": "<sid>"]
        ),
        MethodSpec(
            name: "debug.folders.pin",
            description: "Toggle a folder's pin (pinned folders float above unpinned ones).",
            params: [
                ParamSpec(name: "folderId", type: "string", required: true, default: nil, description: "Folder id"),
            ],
            returns: "{folderId, pinned}",
            example: ["folderId": "<fid>"]
        ),
        MethodSpec(
            name: "debug.folders.setAutoGrouping",
            description: "Toggle the auto-grouping setting (title generation files new chats into matching existing folders). Test hook — the real toggle is in Settings → Appearance.",
            params: [
                ParamSpec(name: "enabled", type: "bool", required: true, default: nil, description: "true to enable"),
            ],
            returns: "{enabled}",
            example: ["enabled": true]
        ),
        MethodSpec(
            name: "debug.rootfs.reset",
            description: "Delete the iSH rootfs so the next COLD launch reinstalls a clean copy. Recovers a mount wedged by heavy apk churn (processCreationFailed). Returns relaunchRequired=true; the caller must kill + cold-launch the app afterward. DEBUG only.",
            params: [
                ParamSpec(name: "keepUserData", type: "bool", required: false, default: true, description: "Back up /root before deleting (restored on reinstall)."),
            ],
            returns: "{reset, keepUserData, backupPath?, relaunchRequired, note}",
            example: ["keepUserData": false]
        ),
        MethodSpec(
            name: "debug.logs.list",
            description: "List NSLog-captured log files.",
            params: [],
            returns: "{enabled, logDirectory, totalSize, files:[{name, size, modified}]}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.logs.read",
            description: "Read a log file by filename (no path separators allowed).",
            params: [
                ParamSpec(name: "name", type: "string", required: true, default: nil, description: "Log filename (no '/' or '..')."),
                ParamSpec(name: "offset", type: "int", required: false, default: nil, description: "Byte offset to start reading from."),
                ParamSpec(name: "limit", type: "int", required: false, default: 524288, description: "Maximum bytes to read (default 512KB)."),
            ],
            returns: "{name, size, content, offset?, bytesRead, truncated?}",
            example: ["name": "2026-04-17.log", "limit": 16384]
        ),
        MethodSpec(
            name: "debug.logs.flush",
            description: "Synchronously fsync the in-memory log buffer to disk so a following debug.logs.read sees the very latest lines. Returns the newest log file's post-flush name/size for an immediate tail read (offset = size - N).",
            params: [],
            returns: "{ok, enabled, name, size, modified}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.logs.setEnabled",
            description: "Enable or disable log file capture at runtime.",
            params: [
                ParamSpec(name: "enabled", type: "bool", required: true, default: nil, description: "New enabled state."),
            ],
            returns: "{enabled: bool}",
            example: ["enabled": true]
        ),
        MethodSpec(
            name: "debug.toolExec.setEnabled",
            description: "Enable or disable the durable [ToolExec] breadcrumb that records each shell_execute immediately BEFORE it launches (O_SYNC, so it survives a SIGKILL/Jetsam mid-command). Default ON; the setting persists across launches. DEBUG builds only.",
            params: [
                ParamSpec(name: "enabled", type: "bool", required: true, default: nil, description: "New enabled state."),
            ],
            returns: "{enabled, path, exists, size, maxBytes, modified}",
            example: ["enabled": true]
        ),
        MethodSpec(
            name: "debug.toolExec.getStatus",
            description: "Current [ToolExec] breadcrumb state: whether it is on, plus the file's path/size/mtime. Read the contents with debug.readFile using the returned `path` — a STARTING line with no matching FINISHED is the command the process died inside.",
            params: [],
            returns: "{enabled, path, exists, size, maxBytes, modified}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.screenshot",
            description: "Capture a live screenshot of the app window as PNG.",
            params: [
                ParamSpec(name: "scale", type: "number", required: false, default: 1.0, description: "Render scale (0 < scale <= 1)."),
                ParamSpec(name: "window", type: "int", required: false, default: 0, description: "Window index when multiple scenes exist."),
            ],
            returns: "{base64, size, encoding: 'png'}",
            example: ["scale": 0.5]
        ),
        MethodSpec(
            name: "debug.screenshot.capture",
            description: "Capture a screenshot into the ring buffer with an optional label.",
            params: [
                ParamSpec(name: "label", type: "string", required: false, default: "", description: "Free-form label."),
                ParamSpec(name: "scale", type: "number", required: false, default: 0.5, description: "Render scale."),
            ],
            returns: "{id, label, timestamp, size}",
            example: ["label": "before-tap"]
        ),
        MethodSpec(
            name: "debug.screenshot.list",
            description: "List entries in the screenshot ring buffer.",
            params: [],
            returns: "{count, entries:[{id, label, timestamp, size}]}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.screenshot.get",
            description: "Return a single screenshot from the ring buffer as base64 PNG.",
            params: [
                ParamSpec(name: "id", type: "int", required: false, default: nil, description: "Entry id; defaults to the latest entry."),
            ],
            returns: "{id, label, timestamp, base64, size, encoding: 'png'}",
            example: ["id": 3]
        ),
        MethodSpec(
            name: "debug.screenshot.clear",
            description: "Clear the screenshot ring buffer.",
            params: [],
            returns: "{cleared: int}",
            example: [:]
        ),
        // Sync v2 diagnostics
        MethodSpec(
            name: "debug.sync.transports",
            description: "List currently registered v2 SyncTransports + capabilities.",
            params: [],
            returns: "{isRunning, transports:[{name,capabilities,isLan}], lanImplemented, registeredRecordTypes}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.sync.tombstones",
            description: "Sessions that have been remote-deleted but kept locally (soft-delete).",
            params: [
                ParamSpec(name: "sessionId", type: "string", required: false, default: nil, description: "Check a single session id; otherwise returns all."),
            ],
            returns: "{count, sessionIds} or {sessionId, tombstoned}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.sync.dirtyByType",
            description: "Snapshot of the dirty record queue grouped by record type.",
            params: [],
            returns: "{total, byType}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.sync.providerV3.status",
            description: "Report Provider Sync v3 (per-record SQLite-backed) state: feature flag, migration completion, db open status, per-table row counts. Used to verify the v3 surface is the authoritative inbound path after a build upgrade.",
            params: [],
            returns: "{enabled, migrationCompleted, dbOpen, counts:{instances, entries, groups}}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.sync.providerV3.setEnabled",
            description: "Kill-switch for Provider Sync v3. Off resumes V2-merge inbound + stops the V3-inbound-merge path (V3 outbound dirty rows already in the queue still drain on next send, harmlessly). Use to revert v3 in the field without a code change. Takes effect immediately for inbound.",
            params: [
                ParamSpec(name: "enabled", type: "bool", required: true, default: nil, description: "true = v3 is authoritative for inbound, v2 inbound silently dropped; false = v3 dormant, v2 inbound merged normally."),
            ],
            returns: "{enabled}",
            example: ["enabled": false]
        ),
        // MARK: Env vars
        MethodSpec(
            name: "debug.envvar.list",
            description: "List all env-var entries managed by EnvVarStore. Values are kept in the iOS Keychain and only returned when includeValues=true.",
            params: [
                ParamSpec(name: "includeValues", type: "bool", required: false, default: false, description: "If true, include decoded Keychain value for each entry."),
            ],
            returns: "{count, entries:[{id, key, note, createdAt, updatedAt, hasValue, value?}]}",
            example: ["includeValues": false]
        ),
        MethodSpec(
            name: "debug.envvar.set",
            description: "Create or update an env-var entry via EnvVarStore (so it triggers the same dirty-marking / sync push as user edits). Lookup precedence: id → key → create new.",
            params: [
                ParamSpec(name: "key", type: "string", required: false, default: nil, description: "Variable name. Required when creating; optional when updating by id."),
                ParamSpec(name: "value", type: "string", required: false, default: nil, description: "Plaintext value. nil leaves the existing Keychain value untouched on update."),
                ParamSpec(name: "note", type: "string", required: false, default: nil, description: "Free-form description. nil leaves existing note untouched on update."),
                ParamSpec(name: "id", type: "string", required: false, default: nil, description: "Existing entry UUID. When provided, update that entry."),
            ],
            returns: "{id, key, note, createdAt, updatedAt, hasValue}",
            example: ["key": "OPENAI_API_KEY", "value": "sk-...", "note": "primary"]
        ),
        MethodSpec(
            name: "debug.envvar.delete",
            description: "Delete an env-var entry by id or by key. Routes through EnvVarStore.delete so the per-key sync record is queued for cloud deletion.",
            params: [
                ParamSpec(name: "id", type: "string", required: false, default: nil, description: "Entry UUID."),
                ParamSpec(name: "key", type: "string", required: false, default: nil, description: "Variable name. Used when id is omitted."),
            ],
            returns: "{deleted: bool, id?: string, key?: string}",
            example: ["key": "OPENAI_API_KEY"]
        ),
        MethodSpec(
            name: "debug.tap",
            description: "Dispatch a tap. Prefer 'identifier'/'label'/'text' for SwiftUI content — they resolve through the accessibility tree and call accessibilityActivate(), which SwiftUI wires to onTapGesture/Button (coordinate-based synthetic taps often miss SwiftUI hit-tests inside List/ScrollView).",
            params: [
                ParamSpec(name: "identifier", type: "string", required: false, default: nil, description: "Match by exact accessibilityIdentifier. Preferred for stable tests."),
                ParamSpec(name: "label", type: "string", required: false, default: nil, description: "Match by exact accessibilityLabel (or UIButton title, UILabel text)."),
                ParamSpec(name: "text", type: "string", required: false, default: nil, description: "Match by text content. Defaults to partial (case-insensitive substring) match."),
                ParamSpec(name: "partial", type: "bool", required: false, default: true, description: "When 'text' is set, use substring matching. Set false to require exact match."),
                ParamSpec(name: "x", type: "number", required: false, default: nil, description: "Fallback: screen x in points. Requires y."),
                ParamSpec(name: "y", type: "number", required: false, default: nil, description: "Fallback: screen y in points. Requires x."),
                ParamSpec(name: "address", type: "string", required: false, default: nil, description: "Optional view address (used with x/y); tap resolves to its center when provided."),
            ],
            returns: "{ok, strategy?, via:'accessibilityActivate'|'coordinate', point?, view?}",
            example: ["identifier": "provider.model.row.gpt-5"]
        ),
        MethodSpec(
            name: "debug.inputText",
            description: "Type text into the currently focused field.",
            params: [
                ParamSpec(name: "text", type: "string", required: true, default: nil, description: "Text to input."),
            ],
            returns: "{ok, length}",
            example: ["text": "hello world"]
        ),
        MethodSpec(
            name: "debug.scroll",
            description: "Simulate a scroll gesture from a point by deltaX/deltaY.",
            params: [
                ParamSpec(name: "x", type: "number", required: true, default: nil, description: "Start x in points."),
                ParamSpec(name: "y", type: "number", required: true, default: nil, description: "Start y in points."),
                ParamSpec(name: "deltaX", type: "number", required: false, default: 0, description: "Horizontal delta."),
                ParamSpec(name: "deltaY", type: "number", required: false, default: 0, description: "Vertical delta."),
            ],
            returns: "{ok, start:{x,y}, delta:{x,y}}",
            example: ["x": 200, "y": 400, "deltaY": -200]
        ),

        // MARK: Hang detector
        MethodSpec(
            name: "debug.hangDetector.start",
            description: "Begin monitoring the main thread. When the main runloop is active for more than `thresholdMs` without a heartbeat, the detector suspends the main thread, captures its stack, and stores it in a ring buffer (last 64 events). Pull events with `debug.hangDetector.dump`.",
            params: [
                ParamSpec(name: "thresholdMs", type: "number", required: false, default: 100, description: "Hang threshold in milliseconds. Clamped to [16, 5000]."),
            ],
            returns: "{ok, isRunning, thresholdMs}",
            example: ["thresholdMs": 100]
        ),
        MethodSpec(
            name: "debug.hangDetector.stop",
            description: "Stop the hang detector. Buffered events are preserved.",
            params: [],
            returns: "{ok, isRunning}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.hangDetector.dump",
            description: "Return the most-recent captured hang events. Each event has timestamp, durationMs, runLoopActivity, and a frame list with image+offset (use `scripts/symbolicate_hang.py` to resolve to source lines).",
            params: [
                ParamSpec(name: "last", type: "int", required: false, default: 20, description: "Max events to return; 0 = all. Capped at the ring buffer size (64)."),
                ParamSpec(name: "clear", type: "bool", required: false, default: false, description: "Clear the buffer after returning."),
            ],
            returns: "{count, isRunning, thresholdMs, events:[{timestamp, durationMs, runLoopActivity, frames:[{address, image, offset, symbol?}]}]}",
            example: ["last": 5]
        ),
        MethodSpec(
            name: "debug.hangDetector.clear",
            description: "Clear the captured hang events without stopping the detector.",
            params: [],
            returns: "{ok}",
            example: [:]
        ),

        // MARK: PerfTrace
        MethodSpec(
            name: "debug.perfTrace.start",
            description: "Enable the PerfTrace span timer (off by default). Instrumented `PerfTrace.measure { }` spans that run longer than `thresholdMs` log a `[JANK]` line + business call stack to the daily log file (pull with `debug.logs.read`). Complements the hang detector: HangDetector tells you the main thread stalled in a *system* frame; PerfTrace tells you which of *your* closures made that frame expensive.",
            params: [
                ParamSpec(name: "thresholdMs", type: "number", required: false, default: 16, description: "Span trip point in ms. A span over this dropped at least one 60fps frame. Clamped to [1, 5000]."),
                ParamSpec(name: "throttleWindow", type: "number", required: false, default: 0.5, description: "Per-label coalescing window in seconds; keeps the slowest sample per label and emits once per window."),
            ],
            returns: "{ok, isEnabled, thresholdMs, throttleWindow}",
            example: ["thresholdMs": 16]
        ),
        MethodSpec(
            name: "debug.perfTrace.stop",
            description: "Disable the PerfTrace span timer and flush any pending worst-samples to the log.",
            params: [],
            returns: "{ok, isEnabled}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.perfTrace.status",
            description: "Report whether PerfTrace is currently enabled and its threshold/throttle settings.",
            params: [],
            returns: "{isEnabled, thresholdMs, throttleWindow}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.perfTrace.flush",
            description: "Force-emit pending worst-samples without disabling the timer. Use at the end of a scroll session to flush the tail immediately.",
            params: [],
            returns: "{ok}",
            example: [:]
        ),

        // MARK: Provider methods
        MethodSpec(
            name: "provider.types",
            description: "List all supported provider types and their accepted-field schemas.",
            params: [],
            returns: "{types: [{id, displayName, supportedCredentials, customBaseURLSupported, builtInModelIds, ...}]}",
            example: [:]
        ),
        MethodSpec(
            name: "provider.instances.list",
            description: "List configured provider instances. Credential material is never returned.",
            params: [
                ParamSpec(name: "includeDisabled", type: "bool", required: false, default: true, description: "If false, omit instances with isEnabled=false."),
            ],
            returns: "{count, instances:[{id, label, providerType, customBaseURL, hasCredential, modelEntryCount, ...}]}",
            example: ["includeDisabled": false]
        ),
        MethodSpec(
            name: "provider.instances.create",
            description: "Add a new provider instance. apiKey is stored in the iOS Keychain.",
            params: [
                ParamSpec(name: "providerType", type: "string", required: true, default: nil, description: "One of openAI, anthropic, gemini, antigravity, openRouter, openAIResponses."),
                ParamSpec(name: "label", type: "string", required: true, default: nil, description: "User-visible name."),
                ParamSpec(name: "credentialType", type: "string", required: false, default: "apiKey", description: "apiKey or oauth."),
                ParamSpec(name: "apiKey", type: "string", required: false, default: nil, description: "Required when credentialType=apiKey. Write-only."),
                ParamSpec(name: "oauthToken", type: "string", required: false, default: nil, description: "Manual OAuth token (for types that support it). Write-only."),
                ParamSpec(name: "customBaseURL", type: "string", required: false, default: nil, description: "Optional proxy / OpenAI-compatible base URL."),
                ParamSpec(name: "appendV1Suffix", type: "bool", required: false, default: true, description: "Whether the provider should auto-append /v1 to customBaseURL."),
                ParamSpec(name: "isEnabled", type: "bool", required: false, default: true, description: "Create in enabled state."),
                ParamSpec(name: "seedBuiltInModels", type: "bool", required: false, default: true, description: "Seed the type's built-in models. Best-effort hint — false does not prevent the API model fetch."),
            ],
            returns: "{instance: {...}}",
            example: ["providerType": "openAI", "label": "DeepSeek", "apiKey": "sk-…", "customBaseURL": "https://api.deepseek.com"]
        ),
        MethodSpec(
            name: "provider.instances.update",
            description: "Patch fields on a provider instance. Pass apiKey=\"\" to clear the stored key.",
            params: [
                ParamSpec(name: "instanceId", type: "string", required: true, default: nil, description: "Target instance UUID."),
                ParamSpec(name: "label", type: "string", required: false, default: nil, description: "New display name."),
                ParamSpec(name: "apiKey", type: "string", required: false, default: nil, description: "Replace stored API key. Empty string clears it."),
                ParamSpec(name: "oauthToken", type: "string", required: false, default: nil, description: "Replace manual OAuth token."),
                ParamSpec(name: "customBaseURL", type: "string", required: false, default: nil, description: "New base URL. Pass null to revert to default."),
                ParamSpec(name: "appendV1Suffix", type: "bool", required: false, default: nil, description: "Update suffix behavior."),
                ParamSpec(name: "isEnabled", type: "bool", required: false, default: nil, description: "Enable or disable."),
                ParamSpec(name: "imageEndpointMode", type: "string", required: false, default: nil, description: "auto | images_generations | chat_completions. Forces a specific endpoint for image-output models on this instance."),
                ParamSpec(name: "imageEndpointResolved", type: "string", required: false, default: nil, description: "Manually set/clear the auto-mode cache. Pass null to clear so the next call re-probes."),
            ],
            returns: "{instance: {...}}",
            example: ["instanceId": "pi_xyz", "imageEndpointMode": "chat_completions"]
        ),
        MethodSpec(
            name: "provider.instances.delete",
            description: "Remove a provider instance, its model entries, and its credentials.",
            params: [
                ParamSpec(name: "instanceId", type: "string", required: true, default: nil, description: "Target instance UUID."),
                ParamSpec(name: "confirm", type: "bool", required: true, default: nil, description: "Must be true. Guards against accidental deletion."),
            ],
            returns: "{instanceId, deletedModelEntries, affectedSessionBindings}",
            example: ["instanceId": "pi_xyz", "confirm": true]
        ),
        MethodSpec(
            name: "provider.models.list",
            description: "List model entries for a specific provider instance.",
            params: [
                ParamSpec(name: "instanceId", type: "string", required: true, default: nil, description: "Target instance UUID."),
                ParamSpec(name: "includeHidden", type: "bool", required: false, default: false, description: "Include entries the user has hidden from the picker."),
            ],
            returns: "{instanceId, count, entries:[{id, modelId, displayName, isCustom, isHidden, overrides, ...}]}",
            example: ["instanceId": "pi_xyz"]
        ),
        MethodSpec(
            name: "provider.thinkingRules.list",
            description: "List a provider's thinking rules — user-authored (custom) and built-in.",
            params: [
                ParamSpec(name: "instanceId", type: "string", required: true, default: nil, description: "Target instance UUID."),
            ],
            returns: "{instanceId, custom:[rule], builtIn:[rule]}",
            example: ["instanceId": "pi_xyz"]
        ),
        MethodSpec(
            name: "provider.thinkingRules.add",
            description: "Create or update ONE user thinking rule via the same store API the UI uses.",
            params: [
                ParamSpec(name: "instanceId", type: "string", required: true, default: nil, description: "Target instance UUID."),
                ParamSpec(name: "label", type: "string", required: true, default: nil, description: "Rule name."),
                ParamSpec(name: "wireFormat", type: "object", required: true, default: nil, description: "Encoded ThinkingWireFormat, e.g. {\"kind\":\"omitEverything\"}."),
                ParamSpec(name: "pattern", type: "string", required: false, default: nil, description: "Model glob. Omit for all models."),
                ParamSpec(name: "sortOrder", type: "int", required: false, default: "0", description: "Position; lower evaluates first."),
                ParamSpec(name: "id", type: "string", required: false, default: nil, description: "Pass an existing id to update in place."),
            ],
            returns: "{ok, id, instanceId}",
            example: ["instanceId": "pi_xyz", "label": "test", "pattern": "*deepseek-v4*", "wireFormat": ["kind": "omitEverything"]]
        ),
        MethodSpec(
            name: "provider.thinkingRules.delete",
            description: "Delete one user thinking rule by id.",
            params: [
                ParamSpec(name: "instanceId", type: "string", required: true, default: nil, description: "Target instance UUID."),
                ParamSpec(name: "id", type: "string", required: true, default: nil, description: "Rule id from thinkingRules.list."),
            ],
            returns: "{ok, id}",
            example: ["instanceId": "pi_xyz", "id": "..."]
        ),
        MethodSpec(
            name: "provider.thinkingRules.resolve",
            description: "Resolve (model, level) through the real resolver and return the emitted body + trace.",
            params: [
                ParamSpec(name: "instanceId", type: "string", required: true, default: nil, description: "Target instance UUID."),
                ParamSpec(name: "modelId", type: "string", required: true, default: nil, description: "Model id to resolve for."),
                ParamSpec(name: "level", type: "string", required: false, default: "high", description: "off|low|medium|high|xhigh|max|ultra."),
                ParamSpec(name: "maxTokens", type: "int", required: false, default: "8192", description: "For budget-shaped formats."),
                ParamSpec(name: "offEffort", type: "string", required: false, default: nil, description: "Vendor off tier, when simulating one."),
            ],
            returns: "{modelId, level, body, trace:{rule, kind, source, emittedKeys}}",
            example: ["instanceId": "pi_xyz", "modelId": "deepseek-v4-flash", "level": "high"]
        ),
        MethodSpec(
            name: "provider.models.add",
            description: "Add a custom model entry to an instance.",
            params: [
                ParamSpec(name: "instanceId", type: "string", required: true, default: nil, description: "Target instance UUID."),
                ParamSpec(name: "modelId", type: "string", required: true, default: nil, description: "API model ID (e.g. \"deepseek-v4-pro\")."),
                ParamSpec(name: "displayName", type: "string", required: false, default: nil, description: "Defaults to a prettified modelId."),
                ParamSpec(name: "contextWindow", type: "int", required: false, default: nil, description: "Tokens."),
                ParamSpec(name: "maxOutputTokens", type: "int", required: false, default: nil, description: "Tokens."),
                ParamSpec(name: "supportsReasoning", type: "bool", required: false, default: nil, description: "Tri-state — pass null to leave unknown."),
                ParamSpec(name: "supportsImageInput", type: "bool", required: false, default: nil, description: "Modality flag."),
                ParamSpec(name: "supportsImageOutput", type: "bool", required: false, default: nil, description: "Modality flag."),
            ],
            returns: "Same shape as one entries[] item in provider.models.list.",
            example: ["instanceId": "pi_xyz", "modelId": "deepseek-v4-pro", "supportsReasoning": true]
        ),
        MethodSpec(
            name: "provider.models.update",
            description: "Patch a model entry's overrides (displayName, maxOutputTokens) or visibility.",
            params: [
                ParamSpec(name: "entryId", type: "string", required: true, default: nil, description: "Target entry UUID."),
                ParamSpec(name: "displayName", type: "string", required: false, default: nil, description: "Override visible name. Pass null to clear."),
                ParamSpec(name: "maxOutputTokens", type: "int", required: false, default: nil, description: "Override output limit. Pass null to clear."),
                ParamSpec(name: "isHidden", type: "bool", required: false, default: nil, description: "Toggle picker visibility."),
            ],
            returns: "Updated entry in provider.models.list shape.",
            example: ["entryId": "entry_123", "displayName": "DeepSeek (Pro)"]
        ),
        MethodSpec(
            name: "provider.models.delete",
            description: "Remove a custom model entry. Built-in entries cannot be deleted — hide instead.",
            params: [
                ParamSpec(name: "entryId", type: "string", required: true, default: nil, description: "Target entry UUID."),
                ParamSpec(name: "confirm", type: "bool", required: true, default: nil, description: "Must be true."),
            ],
            returns: "{entryId, deleted, affectedSessionBindings}",
            example: ["entryId": "entry_123", "confirm": true]
        ),
        MethodSpec(
            name: "provider.models.setModality",
            description: "Override modality flags on an entry's baseModel. Use to fix mis-classified entries (e.g. gpt-image-2 lacking imageOutput).",
            params: [
                ParamSpec(name: "entryId", type: "string", required: true, default: nil, description: "Target entry UUID."),
                ParamSpec(name: "imageInput", type: "bool", required: false, default: nil, description: "Toggle imageInput flag."),
                ParamSpec(name: "imageOutput", type: "bool", required: false, default: nil, description: "Toggle imageOutput flag."),
                ParamSpec(name: "audioInput", type: "bool", required: false, default: nil, description: "Toggle audioInput flag."),
                ParamSpec(name: "audioOutput", type: "bool", required: false, default: nil, description: "Toggle audioOutput flag."),
                ParamSpec(name: "videoInput", type: "bool", required: false, default: nil, description: "Toggle videoInput flag."),
                ParamSpec(name: "videoOutput", type: "bool", required: false, default: nil, description: "Toggle videoOutput flag."),
                ParamSpec(name: "pdfInput", type: "bool", required: false, default: nil, description: "Toggle pdfInput flag."),
                ParamSpec(name: "textInput", type: "bool", required: false, default: nil, description: "Toggle textInput flag."),
                ParamSpec(name: "textOutput", type: "bool", required: false, default: nil, description: "Toggle textOutput flag."),
            ],
            returns: "Updated entry in provider.models.list shape.",
            example: ["entryId": "entry_123", "imageOutput": true]
        ),
        MethodSpec(
            name: "provider.models.setAgentLoop",
            description: "Toggle whether a model entry is exposed to the in-shell `minis-model-use` agent.",
            params: [
                ParamSpec(name: "entryId", type: "string", required: true, default: nil, description: "Target entry UUID."),
                ParamSpec(name: "inLoop", type: "bool", required: true, default: nil, description: "true = expose to agent loop; false = remove."),
            ],
            returns: "{entryId, inLoop}",
            example: ["entryId": "entry_123", "inLoop": true]
        ),
        MethodSpec(
            name: "provider.models.refresh",
            description: "Re-query the provider's /v1/models (or equivalent) and merge into the local entry list.",
            params: [
                ParamSpec(name: "instanceId", type: "string", required: true, default: nil, description: "Target instance UUID."),
            ],
            returns: "{instanceId, added, updated, disappeared, total, durationMs}",
            example: ["instanceId": "pi_xyz"]
        ),
        MethodSpec(
            name: "provider.export",
            description: "Export a provider instance (config + models + stored API key) as a portable JSON envelope. Mirrors the in-app Share button. The API key is base64-wrapped to survive JSON string escaping; this is NOT a security boundary — callers of the 5321 RPC are trusted.",
            params: [
                ParamSpec(name: "instanceId", type: "string", required: true, default: nil, description: "Target instance UUID."),
            ],
            returns: "{version, exportedAt, platform, instanceId, instanceLabel, config:{providerType, label, credentialType, apiKey?, customBaseURL?, models:[...]}}",
            example: ["instanceId": "pi_xyz"]
        ),
        MethodSpec(
            name: "provider.import",
            description: "Import a provider instance from a JSON document produced by `provider.export` or the in-app Share button. Accepts either the wrapped envelope or the raw legacy config JSON for cross-platform compatibility. Always assigns a new instance UUID; duplicate labels are auto-suffixed.",
            params: [
                ParamSpec(name: "configJson", type: "string", required: true, default: nil, description: "Either the {version,config:{...}} envelope or the raw legacy config JSON."),
            ],
            returns: "{instanceId, instanceLabel, modelEntryCount, success}",
            example: ["configJson": "{\"version\":1,\"config\":{...}}"]
        ),

        // MARK: minis-config (remote CLI driver)
        MethodSpec(
            name: "config.get",
            description: "Read one minis-config path through the real ConfigOffloadBridge — identical to `minis-config get <path>` in the iSH guest. Works for flat fields, collection children (`<base>.<id>.<leaf>`) and read-only aggregates.",
            params: [
                ParamSpec(name: "path", type: "string", required: true, default: nil, description: "Registered path, e.g. `thinkingrules` or `thinkingrules.<instanceId>:<ruleId>.scope`."),
                ParamSpec(name: "filter", type: "string", required: false, default: nil, description: "Whitespace-split AND terms, matched case-insensitively against each array element's JSON."),
                ParamSpec(name: "page", type: "int", required: false, default: "0", description: "1-based page for array values."),
                ParamSpec(name: "pageSize", type: "int", required: false, default: "20", description: "Max 100."),
            ],
            returns: "The bridge envelope verbatim: {ok, value, …} or {ok:false, error, reason}",
            example: ["path": "thinkingrules"]
        ),
        MethodSpec(
            name: "config.set",
            description: "Write minis-config path(s) through the real write batch — the same code path as `minis-config set`, including collection add/remove (`<base>.add` / `<base>.remove`) and array append. By DEFAULT this triggers the on-device confirmation sheet and blocks until it is answered (120s gate timeout); pass skipConfirmation to bypass it for unattended runs.",
            params: [
                ParamSpec(name: "path", type: "string", required: false, default: nil, description: "Single-write path. Use `items` for a batch."),
                ParamSpec(name: "value_json", type: "string", required: false, default: "null", description: "Value as a JSON STRING, matching the CLI's argv contract (a string value is \"\\\"abc\\\"\")."),
                ParamSpec(name: "items", type: "array", required: false, default: nil, description: "Batch form: [{path, value_json}, …]."),
                ParamSpec(name: "caption", type: "string", required: false, default: "debug rpc config.set", description: "Shown on the confirmation sheet and stored in the audit row."),
                ParamSpec(name: "actor", type: "string", required: false, default: "agent", description: "Audit actor."),
                ParamSpec(name: "skipConfirmation", type: "bool", required: false, default: "false", description: "Bypass the confirmation sheet. DEBUG-only."),
            ],
            returns: "{ok, applied:[{path,display_name,old,new}], audit_ids, user_message} or an error envelope",
            example: ["path": "thinkingrules.add", "value_json": "{\"provider\":\"<uuid>\",\"label\":\"my rule\",\"wire_format\":{\"kind\":\"reasoningEffort\"}}"]
        ),
        MethodSpec(
            name: "config.topics",
            description: "Every registered minis-config topic — the index `minis-config --help` prints. Use it to confirm a collection is actually registered.",
            params: [],
            returns: "{topics:[string]}",
            example: [:]
        ),
        MethodSpec(
            name: "config.topicHelp",
            description: "Field schema for one topic, as `minis-config <topic> --help` prints it. This is how the writable paths of a collection are discovered.",
            params: [
                ParamSpec(name: "topic", type: "string", required: true, default: nil, description: "Topic name, e.g. `thinkingrules`."),
            ],
            returns: "{topic, fields:[{path, display_name, description, type, access, risk}]}",
            example: ["topic": "thinkingrules"]
        ),
        MethodSpec(
            name: "config.audit",
            description: "Recent minis-config audit rows, to prove a write was recorded with the expected actor/status rather than merely returning ok.",
            params: [
                ParamSpec(name: "limit", type: "int", required: false, default: "20", description: "Max rows."),
                ParamSpec(name: "scope", type: "string", required: false, default: nil, description: "Optional scope filter."),
            ],
            returns: "{ok, entries:[…]}",
            example: ["limit": 5]
        ),

        // MARK: Group management
        MethodSpec(
            name: "provider.groups.list",
            description: "List all configured model groups with their members and routing strategies.",
            params: [
                ParamSpec(name: "includeMembers", type: "bool", required: false, default: true, description: "Include resolved member entry summaries."),
            ],
            returns: "{defaultGroupId, count, groups:[{id, name, strategy, memberEntryIds, isDefault, members?, ...}]}",
            example: [:]
        ),
        MethodSpec(
            name: "provider.groups.create",
            description: "Create a new model group.",
            params: [
                ParamSpec(name: "name", type: "string", required: true, default: nil, description: "Display name."),
                ParamSpec(name: "memberEntryIds", type: "[string]", required: false, default: nil, description: "Initial members. Order matters for fallback strategy."),
                ParamSpec(name: "strategy", type: "string", required: false, default: "fallback", description: "fallback or loadBalance."),
                ParamSpec(name: "fallbackStrategy", type: "string", required: false, default: "limited", description: "limited (only on provider errors) or always (any error). Only meaningful when strategy=fallback."),
                ParamSpec(name: "defaultThinkingLevel", type: "string", required: false, default: nil, description: "off | low | medium | high | xhigh."),
                ParamSpec(name: "contextLimitTokens", type: "int", required: false, default: nil, description: "Context override for sessions bound to this group."),
            ],
            returns: "{group: {...}}",
            example: ["name": "Coding", "memberEntryIds": ["entry_a", "entry_b"], "strategy": "fallback"]
        ),
        MethodSpec(
            name: "provider.groups.update",
            description: "Patch fields on an existing group. Omitted fields are untouched.",
            params: [
                ParamSpec(name: "groupId", type: "string", required: true, default: nil, description: "Target group UUID."),
                ParamSpec(name: "name", type: "string", required: false, default: nil, description: "New name."),
                ParamSpec(name: "memberEntryIds", type: "[string]", required: false, default: nil, description: "Replace members. Pass [] to clear all."),
                ParamSpec(name: "strategy", type: "string", required: false, default: nil, description: "fallback or loadBalance."),
                ParamSpec(name: "fallbackStrategy", type: "string", required: false, default: nil, description: "limited or always."),
                ParamSpec(name: "defaultThinkingLevel", type: "string", required: false, default: nil, description: "Set thinking-level default. Pass null to clear."),
                ParamSpec(name: "contextLimitTokens", type: "int", required: false, default: nil, description: "Context override. Pass null to revert."),
            ],
            returns: "{group: {...}}",
            example: ["groupId": "grp_xyz", "name": "Coding (V2)"]
        ),
        MethodSpec(
            name: "provider.groups.delete",
            description: "Delete a model group. Sessions bound to it fall back to the default on next use.",
            params: [
                ParamSpec(name: "groupId", type: "string", required: true, default: nil, description: "Target group UUID."),
                ParamSpec(name: "confirm", type: "bool", required: true, default: nil, description: "Must be true."),
            ],
            returns: "{groupId, deleted, wasDefault, affectedSessionBindings}",
            example: ["groupId": "grp_xyz", "confirm": true]
        ),
        MethodSpec(
            name: "provider.groups.setDefault",
            description: "Set or clear the global default model group used by new sessions.",
            params: [
                ParamSpec(name: "groupId", type: "string", required: true, default: nil, description: "Target group UUID. Pass null to clear the default."),
            ],
            returns: "{defaultGroupId}",
            example: ["groupId": "grp_xyz"]
        ),
        MethodSpec(
            name: "provider.groups.setAgentLoop",
            description: "Toggle whether a group is exposed to the in-shell minis-model-use agent.",
            params: [
                ParamSpec(name: "groupId", type: "string", required: true, default: nil, description: "Target group UUID."),
                ParamSpec(name: "inLoop", type: "bool", required: true, default: nil, description: "true = expose; false = remove."),
            ],
            returns: "{groupId, inLoop}",
            example: ["groupId": "grp_xyz", "inLoop": true]
        ),

        // MARK: Chat methods
        MethodSpec(
            name: "chat.models.list",
            description: "List the candidate models and groups visible in the in-app picker.",
            params: [
                ParamSpec(name: "includeHidden", type: "bool", required: false, default: false, description: "Include entries the user has hidden."),
                ParamSpec(name: "includeDisabled", type: "bool", required: false, default: false, description: "Include entries from disabled provider instances."),
            ],
            returns: "{defaultGroupId, groupCount, entryCount, groups:[…], entries:[…]}",
            example: [:]
        ),
        MethodSpec(
            name: "chat.sessions.list",
            description: "List recent chat sessions, most recently updated first.",
            params: [
                ParamSpec(name: "limit", type: "int", required: false, default: 50, description: "Max sessions. Clamped to [1, 500]."),
                ParamSpec(name: "includeEmpty", type: "bool", required: false, default: false, description: "Include sessions with no messages yet."),
            ],
            returns: "{count, sessions:[{id, title, modelId, modelName, source, lastMessagePreview, createdAt, updatedAt}]}",
            example: ["limit": 20]
        ),
        MethodSpec(
            name: "chat.sessions.get",
            description: "Fetch one session's metadata.",
            params: [
                ParamSpec(name: "sessionId", type: "string", required: true, default: nil, description: "Target session ID."),
            ],
            returns: "Session metadata dict, plus thinkingLevel if set.",
            example: ["sessionId": "6D0F…"]
        ),
        MethodSpec(
            name: "chat.messages.list",
            description: "List messages in a session, in chronological order.",
            params: [
                ParamSpec(name: "sessionId", type: "string", required: true, default: nil, description: "Target session ID."),
                ParamSpec(name: "limit", type: "int", required: false, default: 200, description: "Max messages. Clamped to [1, 1000]."),
                ParamSpec(name: "offset", type: "int", required: false, default: 0, description: "Skip N messages from the start."),
                ParamSpec(name: "roles", type: "[string]", required: false, default: nil, description: "Filter by role array (\"user\", \"assistant\")."),
                ParamSpec(name: "includeTools", type: "bool", required: false, default: true, description: "Include tool-call blocks."),
                ParamSpec(name: "includeReasoning", type: "bool", required: false, default: false, description: "Include captured reasoning_content (currently no-op — kept for forward compat)."),
            ],
            returns: "{sessionId, count, messages:[{id, role, createdAt, content, toolCalls?, attachments?}]}",
            example: ["sessionId": "6D0F…", "limit": 50]
        ),
        MethodSpec(
            name: "chat.prompt",
            description: "Send a prompt to a new or existing session. Equivalent to SendPromptIntent.",
            params: [
                ParamSpec(name: "prompt", type: "string", required: true, default: nil, description: "User message text."),
                ParamSpec(name: "sessionId", type: "string", required: false, default: nil, description: "Existing session to continue. Omit to create a new session."),
                ParamSpec(name: "attachments", type: "[object]", required: false, default: nil, description: "Array of {name, data, mime?} where data is base64."),
                ParamSpec(name: "thinkingLevel", type: "string", required: false, default: nil, description: "off | low | medium | high | xhigh."),
                ParamSpec(name: "modelEntryId", type: "string", required: false, default: nil, description: "Pin to a specific entry from chat.models.list. Mutually exclusive with modelGroupId."),
                ParamSpec(name: "modelGroupId", type: "string", required: false, default: nil, description: "Pin to a specific group. Mutually exclusive with modelEntryId."),
                ParamSpec(name: "wait", type: "bool", required: false, default: false, description: "Block until completion."),
                ParamSpec(name: "waitTimeout", type: "int", required: false, default: 600, description: "Seconds when wait=true. Clamped to [1, 1800]."),
            ],
            returns: "{sessionId, isNewSession, modelName, status, prompt, responseText, userMessageId, tokenUsage?}",
            example: ["prompt": "Say hi", "wait": true]
        ),
        MethodSpec(
            name: "chat.session.status",
            description: "Poll a session's live status — useful after async chat.prompt.",
            params: [
                ParamSpec(name: "sessionId", type: "string", required: true, default: nil, description: "Target session ID."),
            ],
            returns: "{sessionId, title, modelName, isRunning, lastMessage, updatedAt}",
            example: ["sessionId": "6D0F…"]
        ),
        MethodSpec(
            name: "chat.session.blocksDiff",
            description: "Compare DB raw text blocks vs cached VM blocks for the last assistant message. Diagnoses DB/VM content mismatch.",
            params: [
                ParamSpec(name: "sessionId", type: "string", required: true, default: nil, description: "Target session ID."),
            ],
            returns: "{sessionId, dbTextBlocks, vmTextBlocks, vmCached}",
            example: ["sessionId": "6D0F…"]
        ),
        MethodSpec(
            name: "chat.thinking.setExpanded",
            description: "Set the last thinking block's expanded state on the session's cached VM (perf A/B; synthetic taps can't hit the pill).",
            params: [
                ParamSpec(name: "sessionId", type: "string", required: true, default: nil, description: "Target session ID (must be open in the UI)."),
                ParamSpec(name: "expanded", type: "bool", required: false, default: true, description: "true=expand, false=collapse."),
            ],
            returns: "{sessionId, blockId, contentLen, expanded}",
            example: ["sessionId": "6D0F…", "expanded": true]
        ),
        MethodSpec(
            name: "chat.session.cancel",
            description: "Abort an in-progress agent run. No-op if the session isn't running.",
            params: [
                ParamSpec(name: "sessionId", type: "string", required: true, default: nil, description: "Target session ID."),
            ],
            returns: "{sessionId, wasRunning, cancelled}",
            example: ["sessionId": "6D0F…"]
        ),
        MethodSpec(
            name: "chat.session.delete",
            description: "Permanently delete a session and its messages. Irreversible.",
            params: [
                ParamSpec(name: "sessionId", type: "string", required: true, default: nil, description: "Target session ID."),
                ParamSpec(name: "confirm", type: "bool", required: true, default: nil, description: "Must be true."),
            ],
            returns: "{sessionId, deleted}",
            example: ["sessionId": "6D0F…", "confirm": true]
        ),
        MethodSpec(
            name: "chat.compact.markers.list",
            description: "List all compact markers on a session, oldest → newest. Includes v2 fields (anchorMessageId, version, summaryLength).",
            params: [
                ParamSpec(name: "sessionId", type: "string", required: true, default: nil, description: "Target session ID."),
            ],
            returns: "{sessionId, count, markers:[{id, version, anchorMessageId, summaryLength, summaryPreview, compactedCount, createdAt}]}",
            example: ["sessionId": "6D0F…"]
        ),
        MethodSpec(
            name: "chat.compact.before",
            description: "Trigger compactBefore on a UI message — folds everything from session start (or the previous marker's anchor + 1) up to and including this message into a new v2 marker. Set includesBoundary=true for compactAll semantics (auto-anchor on the Nth-from-last user turn).",
            params: [
                ParamSpec(name: "sessionId", type: "string", required: true, default: nil, description: "Target session ID."),
                ParamSpec(name: "messageId", type: "string", required: true, default: nil, description: "ChatMessage UUID (from chat.messages.list). The marker will anchor here."),
                ParamSpec(name: "includesBoundary", type: "bool", required: false, default: false, description: "true → compactAll mode (auto-anchor)."),
                ParamSpec(name: "waitTimeout", type: "int", required: false, default: 120, description: "Seconds to wait for compaction LLM call. Clamped [1,600]."),
            ],
            returns: "{sessionId, messageId, beforeMarkerCount, afterMarkerCount, wrote, latestMarker:{id, version, anchorMessageId, summaryLength, compactedCount}}",
            example: ["sessionId": "6D0F…", "messageId": "ABCD-…"]
        ),
        MethodSpec(
            name: "chat.simulateLongMessage",
            description: "Inject a synthetic long markdown message into a session via the normal ChatStore.appendMessage path. Used to reproduce the TextKit1 main-thread hang in SelectableMarkdownView when CollectionViewMessageListV3 measures intrinsic size. WARNING: opening the resulting session in the UI may trigger a watchdog crash — use only for repro testing.",
            params: [
                ParamSpec(name: "sessionId", type: "string", required: true, default: nil, description: "Target session ID. Must already exist."),
                ParamSpec(name: "charCount", type: "int", required: false, default: 200_000, description: "Target total characters in the generated markdown."),
                ParamSpec(name: "blockChars", type: "int", required: false, default: 800, description: "Approximate per-paragraph length. Number of paragraphs ≈ charCount/blockChars."),
                ParamSpec(name: "role", type: "string", required: false, default: "assistant", description: "'assistant' or 'user'."),
                ParamSpec(name: "text", type: "string", required: false, default: nil, description: "Verbatim markdown to inject. When present, charCount/blockChars are ignored and this exact text is stored — use it to replay a captured real conversation (CJK, LaTeX, tables) that generated lorem ipsum cannot reproduce."),
            ],
            returns: "{messageId, sortOrder, totalChars, blockCount, role}",
            example: ["sessionId": "6D0F…", "charCount": 200000, "blockChars": 800]
        ),
        MethodSpec(
            name: "chat.debugRemoveMessages",
            description: "TEST HOOK [T-msgidx-oob-repro]: remove N UI rows from the FRONT of the live view model's messages array (never the trailing assistant), simulating an external mid-loop mutation (iCloud rebuild / user delete). Mutates only the UI array — agentHistory and the DB are untouched. Time it into a stalled stream to reproduce the stale-msgIdx crash family.",
            params: [
                ParamSpec(name: "sessionId", type: "string", required: true, default: nil, description: "Target session ID (must be the live/open one)."),
                ParamSpec(name: "count", type: "int", required: false, default: 2, description: "Rows to remove from the front. Clamped [1,10]."),
            ],
            returns: "{before, removed, after}",
            example: ["sessionId": "6D0F…", "count": 2]
        ),
        MethodSpec(
            name: "chat.deleteFromMessage",
            description: "TEST HOOK [T-ios-delete-from-message]: delete the n-th user bubble and everything after it, exercising the same suffix-truncation rule as retry. Reports UI / agentHistory / trailing-entry state so a test can assert the surviving history never ends on a dangling tool_use.",
            params: [
                ParamSpec(name: "sessionId", type: "string", required: true, default: nil, description: "Target session ID (must be the live/open one)."),
                ParamSpec(name: "index", type: "int", required: false, default: 0, description: "Which USER bubble to delete from, 0-based."),
            ],
            returns: "{userBubblesBefore, messagesBefore, messagesAfter, historyBefore, historyAfter, trailingHistoryRole, trailingHasToolUse}",
            example: ["sessionId": "6D0F…", "index": 1]
        ),
        MethodSpec(
            name: "chat.compact.revert",
            description: "Revert the most recent compact on a session. Drops the latest marker; cachedLatestMarker falls back to the previous one (or nil), then loadSession() rebuilds the UI.",
            params: [
                ParamSpec(name: "sessionId", type: "string", required: true, default: nil, description: "Target session ID."),
            ],
            returns: "{sessionId, beforeMarkerCount, afterMarkerCount, removedMarkerId, newLatestMarkerId}",
            example: ["sessionId": "6D0F…"]
        ),
        // MARK: Markdown Snapshot diagnostics (T-attachment-zero-origin)
        MethodSpec(
            name: "debug.markdownSnapshot.setEnabled",
            description: "Toggle the in-memory Markdown render snapshot collector. When enabled, every SelectableMarkdownView render pass (post-updateUIView, post-sizeThatFits on the measureTextView, and post-layoutSubviews) records a full snapshot — attributedString attachment metadata, layoutManager geometry, and a 1× PNG of the textView — into a 64-entry ring buffer. Default OFF; turn on only when reproducing a layout bug because each snapshot can be hundreds of KB (PNG + JSON). DEBUG builds only.",
            params: [
                ParamSpec(name: "enabled", type: "bool", required: true, default: nil, description: "New enabled state."),
            ],
            returns: "{enabled: bool}",
            example: ["enabled": true]
        ),
        MethodSpec(
            name: "debug.markdownSnapshot.list",
            description: "List brief metadata for every snapshot in the in-memory ring buffer. Use `debug.markdownSnapshot.get` to fetch full details + PNG.",
            params: [],
            returns: "{count, snapshots:[{id, timestamp, phase, messageId, textViewPointer, isMeasureView, boundsW, boundsH, textStorageLen, attachmentCount, markdownPreview, imagePNGBytes}]}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.markdownSnapshot.get",
            description: "Fetch a single snapshot in full, including base64-PNG screenshot of the textView at that phase, attachment geometry, text runs, container size, etc.",
            params: [
                ParamSpec(name: "id", type: "string", required: true, default: nil, description: "Snapshot id returned by `debug.markdownSnapshot.list`."),
            ],
            returns: "Full snapshot object (see MarkdownSnapshotCollector.Snapshot fields).",
            example: ["id": "md-1"]
        ),
        MethodSpec(
            name: "debug.markdownSnapshot.clear",
            description: "Empty the snapshot ring buffer without disabling collection.",
            params: [],
            returns: "{cleared: true}",
            example: [:]
        ),
        // MARK: Message-list snapshot diagnostics (T-attachment-zero-origin)
        MethodSpec(
            name: "debug.messageListSnapshot.setEnabled",
            description: "Toggle the in-memory message-list snapshot collector. Unlike `debug.markdownSnapshot` (scoped to a single textView), this one captures the entire UICollectionView state: contentOffset, every visible cell's frame + indexPath, and inside each cell every attachment's boundingRect + matched subview frame. Used to diagnose inter-cell layout drift (e.g. attachment frame mutating after layoutSubviews settled). Default OFF.",
            params: [
                ParamSpec(name: "enabled", type: "bool", required: true, default: nil, description: "New enabled state."),
            ],
            returns: "{enabled: bool}",
            example: ["enabled": true]
        ),
        MethodSpec(
            name: "debug.messageListSnapshot.list",
            description: "List brief metadata for every message-list snapshot in the ring buffer.",
            params: [],
            returns: "{count, snapshots:[{id, timestamp, trigger, triggerDetail, cellCount, imagePNGBytes, collectionContentOffsetY}]}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.messageListSnapshot.get",
            description: "Fetch a single message-list snapshot in full: collection-view geometry, every cell + attachments + matched subview frames, plus a base64-PNG of the collection view.",
            params: [
                ParamSpec(name: "id", type: "string", required: true, default: nil, description: "Snapshot id from `debug.messageListSnapshot.list`."),
            ],
            returns: "Full snapshot object (see MessageListSnapshotCollector.Snapshot).",
            example: ["id": "ml-1"]
        ),
        MethodSpec(
            name: "debug.messageListSnapshot.clear",
            description: "Empty the message-list snapshot ring buffer without disabling collection.",
            params: [],
            returns: "{cleared: true}",
            example: [:]
        ),
        MethodSpec(
            name: "debug.messageListSnapshot.captureNow",
            description: "Force one immediate message-list snapshot independently of in-code capture sites. Useful for grabbing the current state after a bug has finished reproducing.",
            params: [],
            returns: "{ok: true}",
            example: [:]
        ),
        // MARK: Debug View Overlay (visual frame inspector)
        MethodSpec(
            name: "debug.debugOverlay.setEnabled",
            description: "Toggle the on-screen debug-overlay layer. When enabled, every matching UIView gets a colored semi-transparent rectangle + a label showing its class name, hex address, and frame (x/y/w/h). Updated via a 30fps CADisplayLink so overlays follow live frame changes. Use this to visually inspect margins, overlapping subviews, and layout bugs. Default OFF. Use `setMode` to choose what to highlight.",
            params: [
                ParamSpec(name: "enabled", type: "bool", required: true, default: nil, description: "Turn the overlay on or off."),
            ],
            returns: "{enabled: bool}",
            example: ["enabled": true]
        ),
        MethodSpec(
            name: "debug.debugOverlay.setMode",
            description: "Choose which views the debug overlay highlights. 'all' = every visible view (busy but full). 'byType' = exact class name (e.g. 'TableScrollView'). 'byTypePrefix' = class-name prefix (e.g. 'Selectable' matches every Selectable* view). 'byAddress' = single view by its hex address (e.g. one returned from `debug.search`). Switching modes immediately clears existing overlays and applies the new filter on the next display-link tick.",
            params: [
                ParamSpec(name: "mode", type: "string", required: true, default: nil, description: "'all' | 'byType' | 'byTypePrefix' | 'byAddress'."),
                ParamSpec(name: "type", type: "string", required: false, default: nil, description: "Required when mode='byType' or 'byTypePrefix'. The class name (or prefix) to match against `String(describing: type(of: view))`."),
                ParamSpec(name: "address", type: "string", required: false, default: nil, description: "Required when mode='byAddress'. Hex address like '0x14eb18700' (must match the format `%p` produces)."),
            ],
            returns: "{mode: string}",
            example: ["mode": "byType", "type": "TableScrollView"]
        ),
        MethodSpec(
            name: "debug.providers.quickTest",
            description: "Run Quick Test on a specific model entry (same as the UI Quick Test sheet). Tests applicable modalities (text, speechOut, transcription, imageGen) concurrently and returns results. Use `minis-config get models` to find entry IDs.",
            params: [
                ParamSpec(name: "entryId", type: "string", required: true, default: nil, description: "The model entry ID to test (from `minis-config get models`)."),
                ParamSpec(name: "kinds", type: "string[]", required: false, default: nil, description: "Optional list of test kinds to run: 'text', 'speechOut', 'transcription', 'imageGen'. Omit to auto-detect from model modalities."),
            ],
            returns: "{model, results: [{kind, status, elapsed, detail}]}",
            example: ["entryId": "58334FC7-…"]
        ),
    ]

    /// Serialize a single method spec to a JSON-compatible dictionary.
    private static func encode(_ method: MethodSpec) -> [String: Any] {
        let params: [[String: Any]] = method.params.map { p in
            var dict: [String: Any] = [
                "name": p.name,
                "type": p.type,
                "required": p.required,
                "description": p.description,
            ]
            if let d = p.default { dict["default"] = d }
            return dict
        }
        return [
            "name": method.name,
            "description": method.description,
            "params": params,
            "returns": method.returns,
            "example": method.example,
        ]
    }

    /// Full payload returned from `rpc.discover`.
    static func discover() -> [String: Any] {
        let info = Bundle.main.infoDictionary ?? [:]
        return [
            "platform": "ios",
            "version": info["CFBundleShortVersionString"] as? String ?? "?",
            "build": info["CFBundleVersion"] as? String ?? "?",
            "methodCount": methods.count,
            "methods": methods.map(encode),
        ]
    }

    /// Names of every registered method — used to suggest alternatives in error messages.
    static var methodNames: [String] {
        methods.map(\.name)
    }
}
#endif
