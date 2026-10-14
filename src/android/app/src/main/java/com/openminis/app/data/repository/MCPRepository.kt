package com.openminis.app.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages MCP (Model Context Protocol) server configs.
 *
 * Mirrors [SkillRepository] in architecture, but the source of truth for the
 * server list is a single Claude-Desktop-compatible JSON file rather than a
 * SQLite table:
 *   - Server configs live in `/var/minis/mcp-servers/servers.json` (host:
 *     `minis-global/mcp-servers/servers.json`) in the `{ "mcpServers": { … } }`
 *     format. This is the SAME file the `minis-mcp-cli` Python tool reads/writes
 *     inside PRoot, and the file browser can edit — so all three surfaces stay
 *     in sync without an Android-side sync layer (matches Android's local-only
 *     skills/provider model; no CloudKit / whole-file sync here).
 *   - Session enable/disable overrides live in `mcp_session_overrides` (SQLite),
 *     exactly mirroring SkillRepository's `session_skill_overrides`.
 *   - Prompt-fragment generation discloses the Top-N enabled servers, reusing
 *     the same description-truncation cap as skills.
 *
 * No usage-count / recent-use tracking is persisted server-side (the CLI owns
 * invocation); "recent" ordering falls back to recent-add (createdAt desc),
 * which is the best signal Android has locally.
 */
class MCPRepository(private val context: Context) {

    companion object {
        private const val TAG = "MCPRepository"
        private const val DB_NAME = "mcp.db"
        private const val DB_VERSION = 1
        private const val MAX_MCPS_IN_PROMPT = 20
        // Reuse the skill description cap so MCP notes truncate identically.
        private const val MAX_NOTE_LENGTH = 200
    }

    // -- Data Types --

    /**
     * A single MCP server entry. [id] is the server name (the key in the
     * `mcpServers` object). Exactly one transport is populated: HTTP/SSE
     * (url + headers) OR STDIO (command + args + env).
     */
    data class MCPServerConfig(
        val id: String,
        val note: String? = null,
        val enabled: Boolean = true,
        // HTTP / SSE transport
        val url: String? = null,
        val headers: Map<String, String> = emptyMap(),
        // STDIO transport
        val command: String? = null,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        /**
         * Per-server startup/handshake timeout (seconds) for a STDIO server's
         * first MCP `initialize`. Minis config, not MCP protocol; enforcement is
         * entirely in the in-guest `minis-mcp-cli` daemon. Round-tripped verbatim
         * so an edit/import/export never drops it. Null = daemon default (60s).
         * [T-mcp-startup-timeout]
         */
        val startupTimeoutSeconds: Int? = null,
        /**
         * [T-android-mcp-oauth] Non-secret Static-OAuth config for an HTTP/SSE
         * server (client id + endpoints + scopes). Null = no OAuth (static
         * headers only). The client secret and issued tokens are NOT here — they
         * live in [com.openminis.app.mcp.oauth.MCPOAuthStore]. Round-tripped
         * verbatim through servers.json.
         */
        val oauth: com.openminis.app.mcp.oauth.MCPOAuthConfig? = null,
        /** Local-only: when this entry was first written, for recent-add ordering. */
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        val isStdio: Boolean get() = !command.isNullOrBlank()
        /** Short transport descriptor for list rows: the URL or the command line. */
        val transportSummary: String
            get() = if (isStdio) {
                (listOf(command).plus(args)).joinToString(" ").trim()
            } else {
                url ?: ""
            }
    }

    // -- State --

    private val _servers = MutableStateFlow<List<MCPServerConfig>>(emptyList())
    val servers: StateFlow<List<MCPServerConfig>> = _servers.asStateFlow()

    private val db: SQLiteDatabase by lazy {
        McpDbHelper(context).writableDatabase
    }

    /** Host dir backing `/var/minis/mcp-servers` (mirrors skills' minis-global dir). */
    private val mcpDir: File
        get() = File(context.filesDir, "minis-global/mcp-servers")

    private val serversFile: File
        get() = File(mcpDir, "servers.json")

    init {
        load()
    }

    // -- Load / Save (servers.json is the source of truth) --

    /** Re-read `servers.json` from disk and re-publish [servers]. */
    fun load() {
        _servers.value = readServersFile()
    }

    /** Alias matching SkillRepository.reloadFromDisk() so callers read symmetrically. */
    fun reloadFromDisk() = load()

    /**
     * [T-mcp-empty-load-wipe] servers.json EXISTS but could not be read or
     * parsed. Same failure class [T-android-provider-empty-load-wipe] already
     * guards on the provider side: swallowing the error as "zero servers" makes
     * a torn/hand-mangled file indistinguishable from a fresh install, and the
     * next add/toggle then `save()`s that emptiness over the populated file —
     * permanently deleting every MCP server definition, OAuth config included.
     * While this flag is set, persistence is refused until a clean read.
     */
    @Volatile
    private var diskUnreadable = false

    /** True when the last read of servers.json could not be trusted. */
    val isDiskUnreadable: Boolean get() = diskUnreadable

    private fun readServersFile(): List<MCPServerConfig> {
        val file = serversFile
        if (!file.exists()) {
            diskUnreadable = false
            return emptyList()
        }
        val text = try { file.readText() } catch (e: Exception) {
            Log.e(TAG, "Failed to read servers.json, refusing to rewrite it: ${e.message}")
            diskUnreadable = true
            return emptyList()
        }
        if (text.isBlank()) {
            // An empty file is a torn write, not an empty config: the app always
            // writes the mcpServers object.
            Log.e(TAG, "servers.json is empty — treating it as unreadable")
            diskUnreadable = true
            return emptyList()
        }
        val root = try { JSONObject(text) } catch (e: Exception) {
            Log.e(TAG, "servers.json is not valid JSON, refusing to rewrite it: ${e.message}")
            diskUnreadable = true
            return emptyList()
        }
        val obj = root.optJSONObject("mcpServers") ?: run {
            Log.e(TAG, "servers.json has no mcpServers object — treating it as unreadable")
            diskUnreadable = true
            return emptyList()
        }
        diskUnreadable = false
        val out = mutableListOf<MCPServerConfig>()
        // [T-mcp-review-fixes-android] FIX 2: createdAt must be a STABLE recency
        // signal. Entries written by the CLI / JSON-import have no createdAt; we
        // must NOT mint now() on every read (that churns the createdAt-desc
        // Top-20 sort and floats those servers to the top forever). Assign a
        // stable value once on first sight and persist it back to servers.json.
        // Stagger the assigned timestamps by file order so a batch of
        // createdAt-less entries keeps a deterministic (not all-equal) order.
        var needsPersist = false
        val now = System.currentTimeMillis()
        var idx = 0
        for (name in obj.keys()) {
            val entry = obj.optJSONObject(name) ?: continue
            val parsed = parseEntry(name, entry)
            if (!entry.has("createdAt")) {
                needsPersist = true
                // Older-looking, stable, and order-preserving: earlier keys get
                // slightly smaller stamps so iteration order is the tiebreak.
                out.add(parsed.copy(createdAt = now - (idx + 1)))
            } else {
                out.add(parsed)
            }
            idx++
        }
        val sorted = out.sortedByDescending { it.createdAt }
        // Persist the freshly-assigned createdAt values once so subsequent reads
        // are stable. Pass the list explicitly — _servers.value isn't updated
        // until load() returns this.
        if (needsPersist) save(sorted)
        return sorted
    }

    /**
     * Write [list] (defaults to current [servers]) to servers.json in mcpServers
     * format. Returns false when nothing was persisted — either because the disk
     * state could not be read ([diskUnreadable], see [T-mcp-empty-load-wipe]:
     * overwriting an unreadable file with in-memory state is the wipe) or because
     * the write itself failed.
     */
    private fun save(list: List<MCPServerConfig> = _servers.value): Boolean {
        if (diskUnreadable) {
            Log.e(
                TAG,
                "servers.json unreadable — refusing to overwrite it with ${list.size} " +
                    "in-memory entries (would delete the on-disk config)",
            )
            return false
        }
        mcpDir.mkdirs()
        val mcpServers = JSONObject()
        for (s in list) {
            mcpServers.put(s.id, entryToJson(s))
        }
        val root = JSONObject().put("mcpServers", mcpServers)
        return try {
            // [T-mcp-servers-json-atomic-write] servers.json has CONCURRENT
            // readers: the in-guest minis-mcp-cli daemon polls it for config
            // changes and the file browser can open it. A torn write here
            // reads back as invalid JSON and silently empties every surface
            // (the CLI then fails with "server not found"). Temp+rename, same
            // discipline MemoryRepository.applyUser uses for GLOBAL.md.
            writeTextAtomic(serversFile, root.toString(2))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write servers.json: ${e.message}")
            false
        }
    }

    private fun parseEntry(name: String, entry: JSONObject): MCPServerConfig {
        // `disabled: true` (Cursor variant) maps to enabled=false; `enabled`
        // takes precedence when present. Default enabled=true.
        val enabled = when {
            entry.has("enabled") -> entry.optBoolean("enabled", true)
            entry.has("disabled") -> !entry.optBoolean("disabled", false)
            else -> true
        }
        val headers = entry.optJSONObject("headers")?.let { stringMap(it) } ?: emptyMap()
        val env = entry.optJSONObject("env")?.let { stringMap(it) } ?: emptyMap()
        val args = entry.optJSONArray("args")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        } ?: emptyList()
        val note = entry.optString("note", "").ifBlank { null }
        val createdAt = entry.optLong("createdAt", System.currentTimeMillis())
        return MCPServerConfig(
            id = name,
            note = note,
            enabled = enabled,
            url = entry.optString("url", "").ifBlank { null },
            headers = headers,
            command = entry.optString("command", "").ifBlank { null },
            args = args,
            env = env,
            startupTimeoutSeconds = parseStartupTimeout(entry),
            oauth = com.openminis.app.mcp.oauth.MCPOAuthConfig.fromJson(entry.optJSONObject("oauth")),
            createdAt = createdAt,
        )
    }

    /**
     * [T-mcp-startup-timeout] Read a STDIO startup timeout, honouring the same
     * primary-field-then-alias priority as the in-guest CLI so a config migrated
     * from another MCP client keeps its value. Validation/enforcement is the
     * daemon's job; here we only preserve an integer if one is present.
     */
    private fun parseStartupTimeout(entry: JSONObject): Int? {
        val keys = listOf(
            "startupTimeoutSeconds", "startup_timeout_sec", "startupTimeout",
            "handshakeTimeout", "initializeTimeout",
        )
        for (key in keys) {
            if (!entry.has(key) || entry.isNull(key)) continue
            // optInt returns 0 for a non-numeric string; distinguish that from a
            // real 0 by re-parsing the raw value.
            val raw = entry.get(key)
            when (raw) {
                is Number -> return raw.toInt()
                is String -> raw.trim().toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun entryToJson(s: MCPServerConfig): JSONObject {
        val o = JSONObject()
        if (s.isStdio) {
            o.put("command", s.command)
            if (s.args.isNotEmpty()) o.put("args", JSONArray(s.args))
            if (s.env.isNotEmpty()) o.put("env", JSONObject(s.env))
            s.startupTimeoutSeconds?.let { o.put("startupTimeoutSeconds", it) }
        } else if (!s.url.isNullOrBlank()) {
            o.put("url", s.url)
            if (s.headers.isNotEmpty()) o.put("headers", JSONObject(s.headers))
            // [T-android-mcp-oauth] Persist the non-secret OAuth config alongside
            // the URL. Secret + tokens live in MCPOAuthStore, never in JSON.
            s.oauth?.takeIf { it.isConfigured }?.let { o.put("oauth", it.toJson()) }
        }
        s.note?.takeIf { it.isNotBlank() }?.let { o.put("note", it) }
        o.put("enabled", s.enabled)
        o.put("createdAt", s.createdAt)
        return o
    }

    private fun stringMap(obj: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (k in obj.keys()) out[k] = obj.optString(k, "")
        return out
    }

    /**
     * [T-mcp-server-export-android] Serialize a SINGLE server as standard,
     * importable `{ "mcpServers": { "<id>": { … } } }` JSON. Values are exported
     * VERBATIM ($$VAR references and literals as-is). The output is the same
     * shape importJSON reads, so it round-trips. [server] may be built from the
     * live edit-form fields, so the export reflects unsaved edits.
     */
    fun exportServerJSON(server: MCPServerConfig): String {
        val mcpServers = JSONObject().put(server.id, entryToJson(server))
        return JSONObject().put("mcpServers", mcpServers).toString(2)
    }

    // -- CRUD --

    /** Add or overwrite a server (rename/overwrite is last-write-wins by id). */
    fun add(server: MCPServerConfig): Boolean {
        if (server.id.isBlank()) return false
        _servers.value = _servers.value.filter { it.id != server.id } + server
        if (!save()) return false
        Log.i(TAG, "Added MCP server: ${server.id}")
        return true
    }

    fun update(server: MCPServerConfig): Boolean {
        if (_servers.value.none { it.id == server.id }) return false
        _servers.value = _servers.value.map { if (it.id == server.id) server else it }
        return save()
    }

    fun delete(id: String) {
        db.execSQL("DELETE FROM mcp_session_overrides WHERE mcp_id=?", arrayOf(id))
        overrideRevision++
        // Secrets + guest bridge must not outlive the server entry.
        com.openminis.app.mcp.oauth.MCPOAuthStore.purge(context, id)
        _servers.value = _servers.value.filter { it.id != id }
        save()
        Log.i(TAG, "Deleted MCP server: $id")
    }

    fun setEnabled(id: String, enabled: Boolean) {
        _servers.value = _servers.value.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        save()
    }

    fun toggle(id: String) {
        val current = _servers.value.find { it.id == id } ?: return
        setEnabled(id, !current.enabled)
    }

    // -- Session Overrides (mirror SkillRepository) --

    /** [T-prompt-cache] Bumped by every `mcp_session_overrides` mutation. */
    @Volatile
    private var overrideRevision: Long = 0L

    /**
     * Bulk-load all overrides for one session in a single query —
     * same pattern as [SkillRepository.sessionOverrideMap].
     */
    fun sessionOverrideMap(sessionId: String): Map<String, Boolean> {
        val map = HashMap<String, Boolean>()
        db.rawQuery(
            "SELECT mcp_id, is_enabled FROM mcp_session_overrides WHERE session_id=?",
            arrayOf(sessionId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                map[cursor.getString(0)] = cursor.getInt(1) == 1
            }
        }
        return map
    }

    /**
     * [T-mcp-native] Effective per-server enabled state for a session:
     * the session override when present, else the server config default.
     * Used by [com.openminis.app.mcp.client.McpNativeBridge] so the native
     * `mcp` tool honours exactly the same gating as the prompt fragment.
     */
    fun effectiveEnabledMap(sessionId: String): Map<String, Boolean> {
        val overrides = sessionOverrideMap(sessionId)
        return _servers.value.associate { it.id to (overrides[it.id] ?: it.enabled) }
    }

    fun isEnabledForSession(mcpId: String, sessionId: String): Boolean {
        val cursor = db.rawQuery(
            "SELECT is_enabled FROM mcp_session_overrides WHERE session_id=? AND mcp_id=?",
            arrayOf(sessionId, mcpId)
        )
        val override = if (cursor.moveToFirst()) cursor.getInt(0) == 1 else null
        cursor.close()
        if (override != null) return override
        return _servers.value.find { it.id == mcpId }?.enabled ?: false
    }

    /**
     * [T-mcp-review-fixes-android] FIX 3: sparse-clear to match iOS. When the
     * chosen [enabled] equals the server's CURRENT global enabled, DELETE the
     * override row instead of pinning it — so a later flip of the global
     * default propagates to this session (a permanently-pinned row would leave
     * the session stuck at the old value). Only store a row when the session
     * value genuinely diverges from the global default. Read precedence
     * (override → global → false) in [isEnabledForSession] is unchanged.
     */
    fun setSessionOverride(sessionId: String, mcpId: String, enabled: Boolean) {
        val globalEnabled = _servers.value.find { it.id == mcpId }?.enabled
        if (globalEnabled != null && enabled == globalEnabled) {
            db.execSQL(
                "DELETE FROM mcp_session_overrides WHERE session_id=? AND mcp_id=?",
                arrayOf(sessionId, mcpId)
            )
        } else {
            db.execSQL(
                "INSERT OR REPLACE INTO mcp_session_overrides (session_id, mcp_id, is_enabled) VALUES (?, ?, ?)",
                arrayOf<Any>(sessionId, mcpId, if (enabled) 1 else 0)
            )
        }
        overrideRevision++
    }

    fun clearSessionOverrides(sessionId: String) {
        db.execSQL("DELETE FROM mcp_session_overrides WHERE session_id=?", arrayOf(sessionId))
        overrideRevision++
    }

    /**
     * [T-android-session-skill-override-init-timing] Re-point every
     * `mcp_session_overrides` row that was written against the draft session
     * id ([fromDraft], e.g. `__new__<uuid>`) onto the persisted session id
     * ([toReal]) once `ensureSession()` creates the real DB row. Without
     * this hop, a pre-first-message MCP toggle stays bound to the draft key
     * and becomes invisible the next time the chat is opened under its real
     * id. Paired with [SkillRepository.renameSessionOverrides].
     */
    fun renameSessionOverrides(fromDraft: String, toReal: String) {
        if (fromDraft == toReal) return
        db.execSQL(
            "UPDATE OR REPLACE mcp_session_overrides SET session_id=? WHERE session_id=?",
            arrayOf<Any>(toReal, fromDraft),
        )
        overrideRevision++
    }

    // -- JSON Import (4 format variants, last-write-wins on name) --

    /**
     * Parse pasted JSON and import any server entries. Returns the imported
     * configs (already merged into state + written to disk), empty on no-op.
     *
     * Accepted formats (probed in priority order):
     *   1. `{ "mcpServers": { "name": { … } } }` — standard Claude Desktop.
     *   2. `{ "name": { "command"|"url": … } }` — name-keyed object.
     *   3. `{ "command": "npx", … }` / `{ "url": … }` — single bare entry
     *      (name derived from a `name` field or "imported-mcp").
     *   4. Any of the above with `disabled: true` → enabled=false.
     * Rename/overwrite is last-write-wins by name.
     */
    fun importJSON(text: String): List<MCPServerConfig> {
        val root = try { JSONObject(text.trim()) } catch (e: Exception) {
            Log.w(TAG, "importJSON: not valid JSON: ${e.message}")
            return emptyList()
        }
        val parsed = parseImportRoot(root)
        if (parsed.isEmpty()) return emptyList()
        // Merge: overwrite same-name entries, keep the rest.
        val incomingIds = parsed.mapTo(HashSet()) { it.id }
        _servers.value = _servers.value.filter { it.id !in incomingIds } + parsed
        _servers.value = _servers.value.sortedByDescending { it.createdAt }
        if (!save()) {
            // Nothing was lost on disk (the write was refused), but the import
            // lives only in memory until servers.json is readable again.
            Log.e(TAG, "Import of ${parsed.size} server(s) kept in memory only — servers.json is unreadable")
        } else {
            Log.i(TAG, "Imported ${parsed.size} MCP server(s)")
        }
        return parsed
    }

    /**
     * Preview-only parse: how many servers a paste would import, without
     * mutating state. Powers the import sheet's "found N servers" hint.
     */
    fun previewImport(text: String): Int {
        val root = try { JSONObject(text.trim()) } catch (_: Exception) { return 0 }
        return parseImportRoot(root).size
    }

    private fun parseImportRoot(root: JSONObject): List<MCPServerConfig> {
        // Variant 1: { "mcpServers": { … } }
        root.optJSONObject("mcpServers")?.let { obj ->
            return obj.keys().asSequence().mapNotNull { name ->
                obj.optJSONObject(name)?.let { parseEntry(name, it) }
            }.toList()
        }
        // Variant 3: single bare entry { "command"|"url": … }
        if (root.has("command") || root.has("url")) {
            val name = root.optString("name", "").ifBlank { "imported-mcp" }
            return listOf(parseEntry(name, root))
        }
        // Variant 2: name-keyed object { "name": { "command"|"url": … } }
        val out = mutableListOf<MCPServerConfig>()
        for (name in root.keys()) {
            val entry = root.optJSONObject(name) ?: continue
            if (entry.has("command") || entry.has("url")) {
                out.add(parseEntry(name, entry))
            }
        }
        return out
    }

    // -- Prompt Fragment (Top 20) --

    /** [T-prompt-cache] Fragment cache keyed by (session, servers snapshot, override rev). */
    @Volatile
    private var fragmentCacheServers: List<MCPServerConfig>? = null
    @Volatile
    private var fragmentCacheSession: String? = null
    @Volatile
    private var fragmentCacheRevision: Long = -1L
    @Volatile
    private var fragmentCacheValue: String? = null

    /**
     * Build the system-prompt fragment disclosing enabled MCP servers, Top
     * [MAX_MCPS_IN_PROMPT] sorted by recent-add (createdAt desc — Android has
     * no server-side use tracking; the CLI owns invocation). Notes are
     * truncated at [MAX_NOTE_LENGTH] to match the skill description cap.
     * Returns null when the session has no enabled servers. Format per the
     * feature design doc §6a.
     *
     * [T-prompt-cache] Returns the cached fragment unless the servers list
     * or session overrides have changed — re-queries are rare relative to
     * turn frequency.
     */
    fun mcpPromptFragment(sessionId: String): String? {
        val servers = _servers.value
        if (sessionId == fragmentCacheSession &&
            servers === fragmentCacheServers &&
            overrideRevision == fragmentCacheRevision
        ) return fragmentCacheValue

        val overrides = sessionOverrideMap(sessionId)
        val enabled = servers
            .filter { overrides[it.id] ?: it.enabled }
            .sortedByDescending { it.createdAt }
        if (enabled.isEmpty()) {
            fragmentCacheSession = sessionId
            fragmentCacheServers = servers
            fragmentCacheRevision = overrideRevision
            fragmentCacheValue = null
            return null
        }

        val selected = enabled.take(MAX_MCPS_IN_PROMPT)

        val result = buildString {
            append("Available MCP Servers (prefer the native `mcp` tool; minis-mcp-cli via shell also works):\n")
            for (s in selected) {
                var note = s.note ?: ""
                if (note.length > MAX_NOTE_LENGTH) note = note.substring(0, MAX_NOTE_LENGTH) + "…"
                append("- ").append(s.id)
                if (note.isNotBlank()) append(": ").append(note)
                append("\n")
            }
            append("\n")
            append("To use: call the native `mcp` tool — {\"action\":\"tools\",\"server\":\"<server>\"} to list,\n")
            append("{\"action\":\"call\",\"server\":\"<server>\",\"tool\":\"<tool>\",\"arguments\":{…}} to invoke.\n")
            append("Fallback (interactive terminal / unusual cases): `minis-mcp-cli tools <server>`, `minis-mcp-cli call <server> <tool> [args]`.\n")
            // [T-mcp-dollar-var-systemprompt-android] Document the $$VAR runtime
            // env placeholder (mirrors iOS 5fa9e6a9). Agent-facing English — not
            // localized; wording must match iOS verbatim.
            append("When adding or modifying an MCP server config (via minis-mcp-cli add / the UI), use \$\$VARNAME in env/headers/url values as a placeholder resolved at runtime from the system/App environment variables — do not hardcode secrets; reference an existing App environment variable as \$\$NAME.")
        }
        fragmentCacheSession = sessionId
        fragmentCacheServers = servers
        fragmentCacheRevision = overrideRevision
        fragmentCacheValue = result
        return result
    }

    // -- Database Helper (session overrides only) --

    private class McpDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE mcp_session_overrides (
                    session_id TEXT NOT NULL,
                    mcp_id TEXT NOT NULL,
                    is_enabled INTEGER NOT NULL,
                    PRIMARY KEY (session_id, mcp_id)
                )
            """)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 only — no migrations yet.
        }
    }
}
