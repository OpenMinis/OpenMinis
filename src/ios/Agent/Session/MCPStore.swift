//
//  MCPStore.swift
//  MinisApp
//
//  Manages MCP (Model Context Protocol) server configurations: CRUD, JSON
//  import, per-session overrides, and Top-20 system-prompt injection.
//
//  The server list lives in MinisConfig/mcp-servers/servers.json (mcpServers
//  object form, Claude-Desktop compatible), bind-mounted into the guest at
//  /var/minis/mcp-servers — the SAME file the in-guest `minis-mcp-cli`
//  reads/writes, so the two co-read/write it. Session-override metadata reuses
//  the skills.db SQLite file via an `mcp_session_overrides` table (mirrors
//  SkillStore's session_skill_overrides).
//
//  Mirrors SkillStore.swift in structure intentionally — MCP is the sibling
//  "integrations" module to Skills.
//

import Foundation
import SQLite3

// MARK: - MCP Server Model

/// One MCP server entry. `id` is the server name (the key under `mcpServers`).
/// HTTP transport uses url+headers; STDIO uses command+args+env.
struct MCPServerConfig: Codable, Identifiable, Hashable {
    var id: String          // servers.json key (server name)
    var note: String?
    var enabled: Bool       // default true
    /// Epoch seconds when this server was first added. Drives the Top-20
    /// prompt-selection sort (createdAt desc). Optional in the on-disk schema:
    /// a peer/CLI on an older build won't write it, in which case MCPStore
    /// assigns a stable value on first sight (see readServersFromDisk).
    /// [T-mcp-review-fixes-ios]
    var createdAt: Double?
    /// Epoch seconds of the last semantic edit to this entry — the per-server
    /// LWW clock for iCloud sync (MCPServerItem records). Stamped by every
    /// in-app mutation; CLI-authored edits get stamped when the external-edit
    /// scan detects a content change. Optional in the on-disk schema (older
    /// builds / CLI never write it); falls back to createdAt / file mtime.
    /// [T-mcp-per-server-sync]
    var updatedAt: Double?
    // HTTP transport
    var url: String?
    var headers: [String: String]?
    /// [T-mcp-static-oauth] Non-secret OAuth config (mode/clientId/endpoints/
    /// scopes). Secrets (client secret, tokens) live in the Keychain via
    /// MCPOAuthController; this struct is safe to sync in servers.json.
    var oauth: MCPOAuthConfig?
    // STDIO transport
    var command: String?
    var args: [String]?
    var env: [String: String]?
    /// Per-server startup/handshake timeout in seconds for a STDIO server's
    /// first MCP `initialize` (Minis config, not MCP protocol). Enforcement
    /// lives entirely in the in-guest `minis-mcp-cli` daemon; the native layer
    /// only round-trips the field so an edit/import/export never drops it.
    /// Optional: absent means the daemon default (60s). [T-mcp-startup-timeout]
    var startupTimeoutSeconds: Int?

    var isHTTP: Bool { !(url?.isEmpty ?? true) }
    var isSTDIO: Bool { !(command?.isEmpty ?? true) }

    /// Human-readable transport target for list rows ("https://…" or "npx …").
    var transportSummary: String {
        if isHTTP { return url ?? "" }
        if isSTDIO {
            let argStr = (args ?? []).joined(separator: " ")
            return argStr.isEmpty ? (command ?? "") : "\(command ?? "") \(argStr)"
        }
        return ""
    }
}

// MARK: - MCPStore

@MainActor
final class MCPStore: ObservableObject {
    static let shared = MCPStore()

    @Published private(set) var servers: [MCPServerConfig] = []

    /// Increments on every session-override change so SwiftUI panels refresh.
    @Published private(set) var sessionOverrideVersion: Int = 0

    private let fm = FileManager.default
    private var db: OpaquePointer?

    /// Top-N MCP servers disclosed in the system prompt (mirrors
    /// SkillStore.maxSkillMetadataCount = 20).
    private static let maxMetadataCount = 20

    private static let SQLITE_TRANSIENT_DB = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    private init() {
        Self.migrateLegacyServersFile()
        openDatabase()
        createTables()
        load()
        scheduleLegacyRecordCleanupIfNeeded()
    }

    deinit {
        sqlite3_close(db)
    }

    // MARK: - Paths

    /// Host path of the guest's /var/minis/mcp-servers/servers.json. Lives in
    /// the MinisConfig persistent dir (same mechanism as skills/shared/memory:
    /// the guest path is a bind mount / symlink onto this dir — see the static
    /// mount tables in ISHExecutionCoordinator.performMount and
    /// ensureMinisSymlinks). NOT minisAppGroupRoot: servers.json carries
    /// credentials and must never surface in iOS Files.
    private var serversFileURL: URL { Self.syncFileURL }

    /// Host path of the guest's servers.json, as a static for the sync layer
    /// (P4) which runs off the MainActor. Same file as `serversFileURL`.
    nonisolated static var syncFileURL: URL {
        AIChatViewModel.minisMcpServersPersistentDir.appendingPathComponent("servers.json")
    }

    /// Pre-mount-mechanism location: a real file inside the rootfs data tree
    /// (invisible to the guest unless registered in fakefs meta.db — the bug
    /// that motivated the move). Kept only for one-time migration.
    nonisolated private static var legacyServersFileURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("alpine-rootfs/data/var/minis/mcp-servers", isDirectory: true)
            .appendingPathComponent("servers.json")
    }

    /// One-time migration of servers.json from the rootfs data tree into the
    /// MinisConfig persistent dir. Runs before load() on every launch; no-op
    /// once migrated. Guards with lstat: after the mount layer has run, the
    /// old parent is a symlink onto the NEW dir, and copying through it would
    /// be self-copy. copyItem preserves the modification date, which is the
    /// sync layer's LWW clock — a migrated file must not look like a fresh
    /// local edit.
    nonisolated private static func migrateLegacyServersFile() {
        let logger = AppLogger(category: "MCPStore")
        let fm = FileManager.default
        let newURL = syncFileURL
        guard !fm.fileExists(atPath: newURL.path) else {
            logger.info("[Migrate] skip: servers.json already at \(newURL.path)")
            return
        }
        let oldURL = legacyServersFileURL
        let oldParent = oldURL.deletingLastPathComponent()
        var st = stat()
        guard lstat(oldParent.path, &st) == 0 else {
            logger.info("[Migrate] skip: no legacy dir at \(oldParent.path) (fresh install)")
            return
        }
        guard (st.st_mode & S_IFMT) == S_IFDIR else {
            logger.info("[Migrate] skip: legacy dir is already a symlink (mode=0o\(String(st.st_mode & S_IFMT, radix: 8))) — mount layer converted it")
            return
        }
        guard fm.fileExists(atPath: oldURL.path) else {
            logger.info("[Migrate] skip: legacy dir exists but has no servers.json")
            return
        }
        do {
            try fm.createDirectory(at: newURL.deletingLastPathComponent(),
                                   withIntermediateDirectories: true)
            try fm.copyItem(at: oldURL, to: newURL)
            // Remove the old file so the mount layer's real-dir migration
            // doesn't find a stale duplicate; the dir itself is replaced with
            // a symlink by ensureMinisSymlinks/performMount.
            try? fm.removeItem(at: oldURL)
            let attrs = (try? fm.attributesOfItem(atPath: newURL.path)) ?? [:]
            let size = attrs[.size] as? Int ?? 0
            let mtime = attrs[.modificationDate] as? Date
            logger.info("[Migrate] OK: \(oldURL.path) -> \(newURL.path) (\(size) bytes, mtime=\(mtime?.description ?? "nil"))")
        } catch {
            logger.error("[Migrate] FAILED \(oldURL.path) -> \(newURL.path): \(error.localizedDescription)")
        }
    }

    /// Local file mtime of servers.json (epoch), or nil if absent — fallback
    /// clock for entries that predate the per-entry updatedAt field.
    nonisolated static func localMTime() -> Date? {
        (try? FileManager.default.attributesOfItem(atPath: syncFileURL.path)[.modificationDate]) as? Date
    }

    /// Reuse the skills.db file for the mcp_session_overrides table.
    private var dbPath: String {
        let library = fm.urls(for: .libraryDirectory, in: .userDomainMask)[0]
        return library.appendingPathComponent("MinisChat/skills.db").path
    }

    // MARK: - Database (session overrides)

    private func openDatabase() {
        let dir = (dbPath as NSString).deletingLastPathComponent
        try? fm.createDirectory(atPath: dir, withIntermediateDirectories: true)
        if sqlite3_open(dbPath, &db) != SQLITE_OK {
            AppLogger(category: "MCPStore").error("Failed to open skills.db for MCP overrides")
        }
    }

    private func createTables() {
        let sql = """
        CREATE TABLE IF NOT EXISTS mcp_session_overrides (
            session_id TEXT NOT NULL,
            server_id TEXT NOT NULL,
            is_enabled INTEGER NOT NULL,
            PRIMARY KEY (session_id, server_id)
        );
        """
        sqlite3_exec(db, sql, nil, nil, nil)
    }

    private func dbSetSessionOverride(sessionId: String, serverId: String, enabled: Bool) {
        let sql = "INSERT OR REPLACE INTO mcp_session_overrides (session_id, server_id, is_enabled) VALUES (?, ?, ?)"
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, Self.SQLITE_TRANSIENT_DB)
        sqlite3_bind_text(stmt, 2, (serverId as NSString).utf8String, -1, Self.SQLITE_TRANSIENT_DB)
        sqlite3_bind_int(stmt, 3, enabled ? 1 : 0)
        sqlite3_step(stmt)
    }

    private func dbDeleteSessionOverride(sessionId: String, serverId: String) {
        let sql = "DELETE FROM mcp_session_overrides WHERE session_id = ? AND server_id = ?"
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, Self.SQLITE_TRANSIENT_DB)
        sqlite3_bind_text(stmt, 2, (serverId as NSString).utf8String, -1, Self.SQLITE_TRANSIENT_DB)
        sqlite3_step(stmt)
    }

    private func dbSessionOverride(sessionId: String, serverId: String) -> Bool? {
        let sql = "SELECT is_enabled FROM mcp_session_overrides WHERE session_id = ? AND server_id = ?"
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, Self.SQLITE_TRANSIENT_DB)
        sqlite3_bind_text(stmt, 2, (serverId as NSString).utf8String, -1, Self.SQLITE_TRANSIENT_DB)
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        return sqlite3_column_int(stmt, 0) != 0
    }

    // MARK: - servers.json (co-read/write with minis-mcp-cli)

    /// The on-disk JSON shape: { "mcpServers": { "<name>": {...} } }
    private struct ServersFile: Codable {
        var mcpServers: [String: ServerEntry]
    }

    /// A single server entry as stored on disk. `enabled` is optional in the
    /// file (defaults to true) so externally-authored configs parse.
    private struct ServerEntry: Codable {
        var note: String?
        var enabled: Bool?
        var createdAt: Double?   // epoch seconds; optional for old/CLI-authored entries
        var updatedAt: Double?   // epoch seconds; per-server sync LWW clock [T-mcp-per-server-sync]
        var url: String?
        var headers: [String: String]?
        var oauth: MCPOAuthConfig?        // [T-mcp-static-oauth] non-secret oauth config
        var command: String?
        var args: [String]?
        var env: [String: String]?
        var startupTimeoutSeconds: Int?   // Minis STDIO startup timeout; round-tripped verbatim
    }

    func load() {
        servers = readServersFromDisk()
    }

    private func readServersFromDisk() -> [MCPServerConfig] {
        guard let data = try? Data(contentsOf: serversFileURL) else {
            AppLogger(category: "MCPStore").info("[Load] no servers.json at \(serversFileURL.path)")
            return []
        }
        // [T-mcp-review-fixes-ios] FIX 1: per-entry decode tolerance. Read the
        // mcpServers object as a raw [name: Any] map via JSONSerialization, then
        // decode each entry INDIVIDUALLY in its own do/catch — one malformed
        // entry (e.g. args as a string, or a non-object value) is skipped +
        // logged instead of throwing out the whole list and blanking the UI.
        // Matches Android's leniency.
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rawServers = root["mcpServers"] as? [String: Any] else {
            return []
        }

        let logger = AppLogger(category: "MCPStore")
        let decoder = JSONDecoder()
        var configs: [MCPServerConfig] = []
        var assignedCreatedAt = false
        // [T-mcp-review-fixes-ios] FIX 2: stable createdAt. An entry without
        // createdAt (older build / CLI-added) gets a stable value assigned ONCE
        // on first sight and persisted back, so it doesn't churn on every read.
        let now = Date().timeIntervalSince1970
        for (name, rawEntry) in rawServers.sorted(by: { $0.key.localizedCaseInsensitiveCompare($1.key) == .orderedAscending }) {
            do {
                let entryData = try JSONSerialization.data(withJSONObject: rawEntry)
                let entry = try decoder.decode(ServerEntry.self, from: entryData)
                let createdAt: Double
                if let existing = entry.createdAt {
                    createdAt = existing
                } else {
                    createdAt = now
                    assignedCreatedAt = true
                }
                configs.append(MCPServerConfig(
                    id: name,
                    note: entry.note,
                    enabled: entry.enabled ?? true,
                    createdAt: createdAt,
                    updatedAt: entry.updatedAt,
                    url: entry.url,
                    headers: entry.headers,
                    oauth: entry.oauth,
                    command: entry.command,
                    args: entry.args,
                    env: entry.env,
                    startupTimeoutSeconds: entry.startupTimeoutSeconds
                ))
            } catch {
                logger.error("Skipping malformed MCP server entry '\(name)': \(error.localizedDescription)")
            }
        }

        // Persist the stable-assigned createdAt back to disk ONCE so subsequent
        // reads don't re-stamp (and so peers converge on the same value via the
        // whole-file LWW sync). Done after building `configs` to avoid mutating
        // `servers` mid-read; save() reads from the assigned configs.
        if assignedCreatedAt {
            servers = configs
            save()
        }
        AppLogger(category: "MCPStore").info("[Load] \(configs.count) server(s) from \(serversFileURL.path)")
        return configs
    }

    func save() {
        var map: [String: ServerEntry] = [:]
        for s in servers {
            map[s.id] = ServerEntry(
                note: s.note,
                enabled: s.enabled,
                createdAt: s.createdAt,
                updatedAt: s.updatedAt,
                url: s.url,
                headers: s.headers,
                oauth: s.oauth,
                command: s.command,
                args: s.args,
                env: s.env,
                startupTimeoutSeconds: s.startupTimeoutSeconds
            )
        }
        let file = ServersFile(mcpServers: map)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .withoutEscapingSlashes, .sortedKeys]
        guard let data = try? encoder.encode(file) else {
            AppLogger(category: "MCPStore").error("Failed to encode servers.json")
            return
        }
        do {
            try fm.createDirectory(at: serversFileURL.deletingLastPathComponent(),
                                   withIntermediateDirectories: true)
            // Atomic write so a concurrent reader (CLI / agent) never sees a
            // half-written file.
            try data.write(to: serversFileURL, options: .atomic)
            AppLogger(category: "MCPStore").info("[Save] wrote \(servers.count) server(s), \(data.count) bytes to \(serversFileURL.path)")
        } catch {
            AppLogger(category: "MCPStore").error("[Save] FAILED writing \(serversFileURL.path): \(error.localizedDescription)")
        }
    }

    /// Export a single server as standard, importable `mcpServers` JSON
    /// (`{"mcpServers": { <id>: { … } }}`). Values (headers / env / url) are kept
    /// verbatim, including literal tokens or $$VAR references. `createdAt` is
    /// internal bookkeeping and is omitted; the object is a valid subset that
    /// parseImport round-trips. Returns nil only if encoding fails.
    func exportServerJSON(_ server: MCPServerConfig) -> String? {
        let entry = ServerEntry(
            note: server.note,
            enabled: server.enabled,
            createdAt: nil,
            updatedAt: nil,
            url: server.url,
            headers: server.headers,
            oauth: server.oauth,
            command: server.command,
            args: server.args,
            env: server.env,
            startupTimeoutSeconds: server.startupTimeoutSeconds
        )
        let file = ServersFile(mcpServers: [server.id: entry])
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .withoutEscapingSlashes, .sortedKeys]
        guard let data = try? encoder.encode(file) else {
            AppLogger(category: "MCPStore").error("Failed to encode export for server '\(server.id)'")
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    // MARK: - CRUD

    func add(_ server: MCPServerConfig) {
        var server = server
        server.updatedAt = Date().timeIntervalSince1970   // per-server LWW clock
        if let idx = servers.firstIndex(where: { $0.id == server.id }) {
            // [T-mcp-review-fixes-ios] Never overwrite an existing createdAt on
            // update — preserve the original add-time so Top-20 ordering is stable.
            server.createdAt = servers[idx].createdAt ?? server.createdAt ?? Date().timeIntervalSince1970
            servers[idx] = server   // overwrite (LWW on name collision)
        } else {
            // New server: stamp createdAt = now if the caller didn't set one.
            if server.createdAt == nil { server.createdAt = Date().timeIntervalSince1970 }
            servers.append(server)
        }
        servers.sort { $0.id.localizedCaseInsensitiveCompare($1.id) == .orderedAscending }
        save()
        noteSyncedLocalChange(names: [server.id])
    }

    func update(_ server: MCPServerConfig) {
        add(server)
    }

    func delete(id: String) {
        servers.removeAll { $0.id == id }
        save()
        noteSyncedLocalDeletion(names: [id])
        // [T-mcp-static-oauth] Drop the server's OAuth credentials + guest
        // bridge file with it.
        MCPOAuthController.purge(server: id)
    }

    func toggle(id: String) {
        guard let idx = servers.firstIndex(where: { $0.id == id }) else { return }
        servers[idx].enabled.toggle()
        servers[idx].updatedAt = Date().timeIntervalSince1970
        save()
        noteSyncedLocalChange(names: [id])
    }

    // MARK: - JSON import (format compat)

    enum ImportError: LocalizedError {
        case empty
        case unparseable
        case noServers

        var errorDescription: String? {
            switch self {
            case .empty: return AppLocalized("Paste some JSON to import.")
            case .unparseable: return AppLocalized("Couldn't parse that as JSON.")
            case .noServers: return AppLocalized("No MCP servers found in that JSON.")
            }
        }
    }

    /// Parse pasted JSON into server configs WITHOUT persisting. Caller previews
    /// then commits via `commitImport`. Accepts (in priority order):
    ///   1. { "mcpServers": { "name": {...} } }      — standard Claude-Desktop
    ///   2. { "name": { "command"|"url": ... } }     — object key = server name
    ///   3. { "command": "npx", ... }                — single entry (name TBD)
    /// A `disabled: true` field maps to `enabled: false`.
    func parseImport(_ text: String) throws -> [MCPServerConfig] {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw ImportError.empty }
        guard let data = trimmed.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ImportError.unparseable
        }

        var result: [MCPServerConfig] = []

        if let wrapped = root["mcpServers"] as? [String: Any] {
            // Form 1
            for (name, value) in wrapped {
                if let obj = value as? [String: Any] {
                    result.append(serverConfig(name: name, from: obj))
                }
            }
        } else if root["command"] != nil || root["url"] != nil {
            // Form 3 — single entry, no name in the JSON; use a placeholder the
            // user renames in the preview/form.
            result.append(serverConfig(name: root["name"] as? String ?? "new-server", from: root))
        } else {
            // Form 2 — top-level keys are server names.
            for (name, value) in root {
                if let obj = value as? [String: Any], obj["command"] != nil || obj["url"] != nil {
                    result.append(serverConfig(name: name, from: obj))
                }
            }
        }

        guard !result.isEmpty else { throw ImportError.noServers }
        return result.sorted { $0.id.localizedCaseInsensitiveCompare($1.id) == .orderedAscending }
    }

    /// Persist the parsed servers, overwriting any name collisions (LWW).
    func commitImport(_ parsed: [MCPServerConfig]) {
        let now = Date().timeIntervalSince1970
        for server in parsed {
            var server = server
            server.updatedAt = now
            if let idx = servers.firstIndex(where: { $0.id == server.id }) {
                // [T-mcp-review-fixes-ios] Preserve the original createdAt on
                // overwrite; never re-stamp an existing server.
                server.createdAt = servers[idx].createdAt ?? server.createdAt ?? now
                servers[idx] = server
            } else {
                if server.createdAt == nil { server.createdAt = now }
                servers.append(server)
            }
        }
        servers.sort { $0.id.localizedCaseInsensitiveCompare($1.id) == .orderedAscending }
        save()
        noteSyncedLocalChange(names: parsed.map(\.id))
    }

    /// Convenience: parse + commit in one call.
    @discardableResult
    func importJSON(_ text: String) throws -> [MCPServerConfig] {
        let parsed = try parseImport(text)
        commitImport(parsed)
        return parsed
    }

    private func serverConfig(name: String, from obj: [String: Any]) -> MCPServerConfig {
        // `disabled: true` → enabled: false; otherwise honour `enabled` (default true).
        let enabled: Bool
        if let disabled = obj["disabled"] as? Bool {
            enabled = !disabled
        } else {
            enabled = (obj["enabled"] as? Bool) ?? true
        }
        return MCPServerConfig(
            id: name,
            note: obj["note"] as? String,
            enabled: enabled,
            url: obj["url"] as? String,
            headers: obj["headers"] as? [String: String],
            oauth: Self.oauthConfig(from: obj["oauth"] as? [String: Any]),
            command: obj["command"] as? String,
            args: obj["args"] as? [String],
            env: obj["env"] as? [String: String],
            startupTimeoutSeconds: Self.startupTimeout(from: obj)
        )
    }

    /// Read a STDIO startup timeout from imported JSON, honouring the same
    /// primary-field-then-alias priority as the in-guest CLI so a config
    /// migrated from another MCP client keeps its value. Enforcement/validation
    /// is the daemon's job; here we only preserve an integer if one is present.
    /// [T-mcp-startup-timeout]
    private static let startupTimeoutKeys = [
        "startupTimeoutSeconds", "startup_timeout_sec", "startupTimeout",
        "handshakeTimeout", "initializeTimeout",
    ]
    /// [T-mcp-static-oauth] Parse an `oauth` object from imported JSON.
    private static func oauthConfig(from obj: [String: Any]?) -> MCPOAuthConfig? {
        guard let obj else { return nil }
        var cfg = MCPOAuthConfig()
        cfg.mode = (obj["mode"] as? String) ?? "static"
        cfg.clientId = (obj["clientId"] as? String) ?? ""
        cfg.authorizationEndpoint = (obj["authorizationEndpoint"] as? String) ?? ""
        cfg.tokenEndpoint = (obj["tokenEndpoint"] as? String) ?? ""
        cfg.scopes = obj["scopes"] as? String
        cfg.redirectURI = obj["redirectURI"] as? String
        return cfg
    }

    private static func startupTimeout(from obj: [String: Any]) -> Int? {
        for key in startupTimeoutKeys {
            guard let raw = obj[key] else { continue }
            if let n = raw as? NSNumber { return n.intValue }
            if let s = raw as? String, let n = Int(s) { return n }
        }
        return nil
    }

    // MARK: - Session overrides (mirror SkillStore)

    func isEnabledForSession(_ serverId: String, sessionId: String) -> Bool {
        if let override = dbSessionOverride(sessionId: sessionId, serverId: serverId) {
            return override
        }
        return servers.first(where: { $0.id == serverId })?.enabled ?? false
    }

    func setSessionOverride(serverId: String, sessionId: String, enabled: Bool) {
        let defaultEnabled = servers.first(where: { $0.id == serverId })?.enabled ?? true
        if enabled == defaultEnabled {
            dbDeleteSessionOverride(sessionId: sessionId, serverId: serverId)
        } else {
            dbSetSessionOverride(sessionId: sessionId, serverId: serverId, enabled: enabled)
        }
        sessionOverrideVersion += 1
    }

    // MARK: - System-prompt injection (Top 20)

    /// Build the MCP system-prompt snippet for a session, or nil when no server
    /// is enabled (no injection). Discloses the Top-20 enabled-and-not-session-
    /// disabled servers, sorted by createdAt DESC (most-recently-added first),
    /// name as tiebreak. Note is truncated to the same cap as Skill descriptions
    /// (200 chars). [T-mcp-review-fixes-ios] Unified with Android on createdAt-desc.
    func systemPromptSnippet(for sessionId: String) -> String? {
        let enabled = servers.filter { isEnabledForSession($0.id, sessionId: sessionId) }
        guard !enabled.isEmpty else { return nil }

        // Sort by createdAt desc, name asc as tiebreak, then take Top-N. Missing
        // createdAt sorts oldest (0) — but readServersFromDisk stable-assigns one
        // on first sight, so this is only a transient fallback.
        let selected = enabled.sorted { a, b in
            let ca = a.createdAt ?? 0, cb = b.createdAt ?? 0
            if ca != cb { return ca > cb }
            return a.id.localizedCaseInsensitiveCompare(b.id) == .orderedAscending
        }.prefix(Self.maxMetadataCount)
        let maxNoteLength = 200   // same cap as SkillStore.skillPromptFragment

        var lines = "Available MCP Servers (use minis-mcp-cli to discover and call):\n"
        for s in selected {
            var note = s.note ?? ""
            if note.count > maxNoteLength {
                note = String(note.prefix(maxNoteLength)) + "…"
            }
            if note.isEmpty {
                lines += "- \(s.id)\n"
            } else {
                lines += "- \(s.id): \(note)\n"
            }
        }
        lines += "\nTo use: run `minis-mcp-cli tools <server>` to see available tools,\n"
        lines += "then `minis-mcp-cli call <server> <tool> [args]` to invoke."
        // Agent-facing guidance on the runtime env placeholder; English-only,
        // not localized (this is prompt text, never shown in the UI).
        lines += "\nWhen adding or modifying an MCP server config (via minis-mcp-cli add / the UI), use $$VARNAME in env/headers/url values as a placeholder resolved at runtime from the system/App environment variables — do not hardcode secrets; reference an existing App environment variable as $$NAME."
        return lines
    }

    // MARK: - Tools refresh (native → in-guest CLI) [T-mcp-tools-refresh]

    /// One tool row from `minis-mcp-cli tools --refresh`.
    struct MCPToolInfo: Identifiable, Hashable {
        var id: String { name }
        let name: String
        let description: String
    }

    enum ToolsRefreshError: LocalizedError {
        case kernelNotBooted
        case cliFailed(String)

        var errorDescription: String? {
            switch self {
            case .kernelNotBooted:
                return AppLocalized("The terminal engine isn't running yet. Open a chat or the terminal once, then retry.")
            case .cliFailed(let msg):
                return msg
            }
        }
    }

    /// Force-reconnect + re-list a server's tools via the in-guest CLI
    /// (`minis-mcp-cli refresh <name>`), so tools the remote added after the
    /// last connect show up without restarting anything. The synthetic
    /// sessionId gives the coordinator a mount context; the static mount layer
    /// it initializes includes /var/minis/mcp-servers.
    func refreshTools(server: String) async throws -> [MCPToolInfo] {
        guard ISHKernel.shared.isBooted else { throw ToolsRefreshError.kernelNotBooted }
        // Shell-quote the name defensively (names are also file keys, but a
        // space or quote must not break the command line).
        let quoted = "'" + server.replacingOccurrences(of: "'", with: "'\\''") + "'"
        let result = try await ISHExecutionCoordinator.shared.execute(
            sessionId: "mcp-settings",
            command: "minis-mcp-cli refresh \(quoted)",
            timeout: 120,
            lineCallback: { _ in },
            pidCallback: { _ in }
        )
        // stdout is one JSON envelope line; tolerate stray non-JSON lines
        // (wrapper noise) by scanning for the first parseable object.
        for line in result.output.split(separator: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard trimmed.hasPrefix("{"), let data = trimmed.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { continue }
            if let err = obj["error"] as? String {
                throw ToolsRefreshError.cliFailed(err)
            }
            let root = (obj["result"] as? [String: Any]) ?? obj
            guard let tools = root["tools"] as? [[String: Any]] else { continue }
            return tools.compactMap { t in
                guard let name = t["name"] as? String else { return nil }
                return MCPToolInfo(name: name, description: (t["description"] as? String) ?? "")
            }
        }
        throw ToolsRefreshError.cliFailed(AppLocalized("Unexpected CLI output (no JSON result). Check the server's connectivity."))
    }

    // MARK: - iCloud sync (per-server MCPServerItem records) [T-mcp-per-server-sync]
    //
    // One CK record per server (recordName = server name). Local mutations
    // mark per-server dirty rows event-driven; CLI/in-guest edits are caught
    // by the fingerprint scan below (SyncDirtyScanner triggers it on mtime
    // change). Inbound applies update the fingerprint WITHOUT marking dirty,
    // so a received record never echoes back with a fresh clock.

    /// Persisted semantic fingerprints of the last state the sync layer has
    /// acknowledged, keyed by server name. UserDefaults (tiny payload). Used
    /// to (a) detect CLI-authored edits that bypassed the CRUD paths and
    /// (b) suppress echo re-pushes after an inbound apply.
    private static let fingerprintsKey = "cloudSync.mcp.syncFingerprints"

    nonisolated private static func loadFingerprints() -> [String: String] {
        (UserDefaults.standard.dictionary(forKey: fingerprintsKey) as? [String: String]) ?? [:]
    }

    nonisolated private static func saveFingerprints(_ fp: [String: String]) {
        UserDefaults.standard.set(fp, forKey: fingerprintsKey)
    }

    /// Semantic content hash of an entry — everything the user cares about,
    /// EXCLUDING the bookkeeping clocks (createdAt/updatedAt). The clocks
    /// must not participate: a re-stamp would otherwise change the
    /// fingerprint and re-trigger the external-edit scan forever.
    private static func semanticFingerprint(_ s: MCPServerConfig) -> String {
        var obj: [String: Any] = ["enabled": s.enabled]
        if let v = s.note { obj["note"] = v }
        if let v = s.url { obj["url"] = v }
        if let v = s.headers { obj["headers"] = v }
        if let v = s.command { obj["command"] = v }
        if let v = s.args { obj["args"] = v }
        if let v = s.env { obj["env"] = v }
        if let v = s.startupTimeoutSeconds { obj["startupTimeoutSeconds"] = v }
        // [T-mcp-static-oauth] OAuth config is semantic content: an endpoint /
        // clientId edit must re-sync (and re-push) the entry.
        if let o = s.oauth {
            obj["oauth"] = ["mode": o.mode, "clientId": o.clientId,
                            "authorizationEndpoint": o.authorizationEndpoint,
                            "tokenEndpoint": o.tokenEndpoint,
                            "scopes": o.scopes ?? "", "redirectURI": o.redirectURI ?? ""]
        }
        let data = (try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])) ?? Data()
        return String(data: data, encoding: .utf8) ?? ""
    }

    /// Record a local mutation: refresh fingerprints and queue per-server
    /// upsert dirty rows.
    private func noteSyncedLocalChange(names: [String]) {
        var fp = Self.loadFingerprints()
        for name in names {
            if let s = servers.first(where: { $0.id == name }) {
                fp[name] = Self.semanticFingerprint(s)
            }
            markServerDirty(name: name, operation: "upsert")
        }
        Self.saveFingerprints(fp)
    }

    /// Record a local deletion: drop the fingerprint and queue an op=delete
    /// dirty row (peers hard-delete via the deletionApplier; the tombstone
    /// guard in markDirty prevents resurrection by a racing re-fetch).
    private func noteSyncedLocalDeletion(names: [String]) {
        var fp = Self.loadFingerprints()
        for name in names {
            fp.removeValue(forKey: name)
            markServerDirty(name: name, operation: "delete")
        }
        Self.saveFingerprints(fp)
    }

    private func markServerDirty(name: String, operation: String) {
        // CK recordNames cannot start with an underscore. Server names are
        // user/CLI-authored; skip cloud sync for the (rare) invalid ones
        // rather than failing the push loop.
        guard !name.hasPrefix("_"), name.utf8.count <= 200 else {
            AppLogger(category: "MCPStore").warning("[Sync] skip '\(name)': not a valid CK recordName")
            return
        }
        Task { await ChatStore.shared.markDirty(
            recordType: "MCPServerItem",
            recordId: name,
            operation: operation
        ) }
    }

    /// Full ServerEntry JSON for the sync builder (verbatim round-trip shape,
    /// same encoder settings as save()). nil when the server doesn't exist.
    nonisolated static func entryJsonForSync(name: String) -> (json: String, createdAt: Date, updatedAt: Date)? {
        // Read straight from disk so the builder (off-MainActor) never races
        // the in-memory list.
        guard let data = try? Data(contentsOf: syncFileURL),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rawServers = root["mcpServers"] as? [String: Any],
              let rawEntry = rawServers[name] as? [String: Any],
              let entryData = try? JSONSerialization.data(withJSONObject: rawEntry, options: [.sortedKeys]),
              let json = String(data: entryData, encoding: .utf8) else { return nil }
        let mtime = localMTime() ?? Date()
        let createdAt = (rawEntry["createdAt"] as? Double).map { Date(timeIntervalSince1970: $0) } ?? mtime
        let updatedAt = (rawEntry["updatedAt"] as? Double).map { Date(timeIntervalSince1970: $0) } ?? createdAt
        return (json, createdAt, updatedAt)
    }

    /// Apply an inbound per-server record. LWW by per-entry updatedAt: skip
    /// when the local entry is strictly newer (small slack for clock skew).
    /// Updates the fingerprint so the external-edit scan doesn't echo the
    /// applied content back with a fresh clock.
    func applyRemoteServerItem(name: String, entryJson: String, remoteCreatedAt: Date, remoteUpdatedAt: Date) {
        guard let data = entryJson.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            AppLogger(category: "MCPStore").error("[Sync] applyRemoteServerItem '\(name)': unparseable entryJson")
            return
        }
        var incoming = serverConfig(name: name, from: obj)
        incoming.createdAt = (obj["createdAt"] as? Double) ?? remoteCreatedAt.timeIntervalSince1970
        incoming.updatedAt = remoteUpdatedAt.timeIntervalSince1970

        if let idx = servers.firstIndex(where: { $0.id == name }) {
            let local = servers[idx]
            // Content-equality short circuit — echo of our own push.
            if Self.semanticFingerprint(local) == Self.semanticFingerprint(incoming) {
                var fp = Self.loadFingerprints()
                fp[name] = Self.semanticFingerprint(local)
                Self.saveFingerprints(fp)
                return
            }
            let localClock = local.updatedAt ?? local.createdAt ?? 0
            if localClock - remoteUpdatedAt.timeIntervalSince1970 > 2 {
                AppLogger(category: "MCPStore").info("[Sync] applyRemoteServerItem SKIP (local newer) '\(name)'")
                return
            }
            // Keep the earliest createdAt ever seen for stable Top-20 order.
            if let lc = local.createdAt, lc < (incoming.createdAt ?? lc) { incoming.createdAt = lc }
            servers[idx] = incoming
        } else {
            servers.append(incoming)
            servers.sort { $0.id.localizedCaseInsensitiveCompare($1.id) == .orderedAscending }
        }
        save()
        var fp = Self.loadFingerprints()
        fp[name] = Self.semanticFingerprint(incoming)
        Self.saveFingerprints(fp)
        AppLogger(category: "MCPStore").info("[Sync] applied MCPServerItem '\(name)' remoteUpdatedAt=\(remoteUpdatedAt)")
    }

    /// Hard-delete a server locally from an inbound op=delete tombstone,
    /// without re-queueing the delete back into the sync layer.
    func applyRemoteServerDeletion(name: String) {
        guard servers.contains(where: { $0.id == name }) else { return }
        servers.removeAll { $0.id == name }
        save()
        var fp = Self.loadFingerprints()
        fp.removeValue(forKey: name)
        Self.saveFingerprints(fp)
        AppLogger(category: "MCPStore").info("[Sync] applied MCPServerItem deletion '\(name)'")
    }

    /// Detect edits that bypassed the CRUD paths (in-guest minis-mcp-cli
    /// writes servers.json directly). Called by SyncDirtyScanner when the
    /// file mtime moves. Compares current semantic fingerprints against the
    /// persisted last-synced set:
    ///   changed/new → stamp updatedAt=now (the CLI never writes the clock),
    ///                 persist, mark dirty;
    ///   missing     → mark op=delete.
    /// Fingerprints persist across launches, so CLI edits made while the app
    /// was closed are caught by the first scan after launch.
    func scanExternalChanges() {
        load()   // pick up CLI writes
        var fp = Self.loadFingerprints()
        var changed: [String] = []
        var stamped = false
        let now = Date().timeIntervalSince1970
        for (idx, s) in servers.enumerated() {
            let cur = Self.semanticFingerprint(s)
            guard fp[s.id] != cur else { continue }
            // First-ever sight (no fingerprint at all) with no sync history:
            // still push — per-item sync wants the full set on cloud, and
            // upserts are idempotent (peer merger short-circuits on equal
            // content).
            if fp[s.id] != nil || s.updatedAt == nil {
                servers[idx].updatedAt = now
                stamped = true
            }
            fp[s.id] = Self.semanticFingerprint(servers[idx])
            changed.append(s.id)
        }
        let liveNames = Set(servers.map(\.id))
        let removed = fp.keys.filter { !liveNames.contains($0) }
        for name in removed { fp.removeValue(forKey: name) }
        guard !changed.isEmpty || !removed.isEmpty else { return }
        if stamped { save() }
        Self.saveFingerprints(fp)
        for name in changed { markServerDirty(name: name, operation: "upsert") }
        for name in removed { markServerDirty(name: name, operation: "delete") }
        AppLogger(category: "MCPStore").info("[Sync] external scan: \(changed.count) changed, \(removed.count) removed")
    }

    // MARK: - Legacy whole-file record cleanup

    private static let legacyCleanupKey = "cloudSync.mcp.legacyV2RecordDeleted"

    /// One-time job (mirrors EnvVarStore.scheduleLegacyRecordCleanupIfNeeded):
    /// re-emit every server as a per-item record, then queue an op=delete for
    /// the legacy whole-file MCPServersV2 record so it stops occupying cloud
    /// storage and confusing peers' inbound mergers. CloudKit orders the item
    /// upserts before the whole-file delete within the batch.
    private func scheduleLegacyRecordCleanupIfNeeded() {
        guard !UserDefaults.standard.bool(forKey: Self.legacyCleanupKey) else { return }
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 5_000_000_000) // 5s — let ChatStore/SyncCore finish wiring
            guard let self else { return }
            await MainActor.run {
                self.noteSyncedLocalChange(names: self.servers.map(\.id))
                Task { await ChatStore.shared.markDirty(
                    recordType: "MCPServers",
                    recordId: "mcp-servers",
                    operation: "delete"
                ) }
                UserDefaults.standard.set(true, forKey: Self.legacyCleanupKey)
                AppLogger(category: "MCPStore").info("[Sync] legacy MCPServersV2 cleanup scheduled (\(self.servers.count) servers re-emitted as MCPServerItem)")
            }
        }
    }
}
