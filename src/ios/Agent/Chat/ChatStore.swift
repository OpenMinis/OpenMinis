import Foundation
import SQLite3
import SwiftAnthropic
import UIKit
import os.log

private let logger = AppLogger(category: "ChatStore")
/// [T-memory-enabled-new-session-bug DIAG] dedicated category so the
/// memory-toggle trace is grep-able (`[MemDiag]`) without ChatStore noise.
private let memDiagLogger = AppLogger(category: "MemDiag")

// MARK: - Sync Data Types

struct SyncDevice: Identifiable {
    let id: String       // deviceId
    var deviceName: String
    var zoneName: String
    var lastSeen: Date
    var osVersion: String
    /// Content types this device is uploading (e.g. ["Sessions", "Skills", "Providers"])
    var uploadTypes: [String]
}

struct RemoteMemory: Identifiable {
    let id: String
    let deviceId: String
    var fileName: String
    var content: String
    var updatedAt: Date
}

enum SyncStatus: Equatable {
    case idle
    case syncing
    case error(String)
    case disabled
}

// MARK: - Data Model

/// A chat session (conversation thread).
struct ChatSession: Identifiable, Codable, Hashable {
    let id: String
    var title: String?
    var category: String?
    let modelId: String
    let createdAt: Date
    var updatedAt: Date
    var lastMessage: String?
    var source: String?       // e.g. "shortcut" for Shortcuts-triggered sessions
    var lastSyncedAt: Date?   // non-nil if successfully synced to iCloud
    var remoteDeviceId: String?   // non-nil if this session came from another device via iCloud
    var remoteDeviceName: String? // human-readable name of the remote device
    var pinnedAt: Date?       // non-nil if session is pinned; timestamp of when it was pinned
    var folderId: String?     // non-nil if filed into a folder; NULL = ungrouped

    /// Whether this session is from a remote device (read-only).
    var isRemote: Bool { remoteDeviceId != nil }

    /// Whether this session is pinned to the top of the list.
    var isPinned: Bool { pinnedAt != nil }

    /// Whether this session belongs to a folder.
    var isFiled: Bool { folderId != nil }

    // MARK: - Equatable / Hashable (cheap, content-via-proxy)
    //
    // [T-ios-session-list-equatable-jank] The compiler-synthesized
    // `==`/`hash` compared EVERY stored field — including the long
    // `lastMessage` / `title` strings. SwiftUI's sidebar `ForEach(groupedSessions)`
    // makes AttributeGraph deep-compare the whole `[ChatSession]` array on every
    // transaction flush (session switch, tap, foreground, iCloud inbound). In
    // DEBUG that synthesized `String.==` runs the Unicode NFC normalization
    // slow path (no inlining / ARC elision), and across a long session list it
    // pinned the main thread for 1–2s+ (HangDetector: ChatSession
    // __derived_struct_equals → _stringCompareSlow → _NFCNormalizer). Release
    // optimizes it away, which is why Release didn't stall.
    //
    // Replace with an explicit cheap comparison: identity + the short
    // change-driving fields, using `updatedAt` as the proxy for the heavy
    // `lastMessage` (and any other body content) — every content mutation bumps
    // `updatedAt`, so we never need to NFC-compare the long string to detect a
    // change. `title`/`category`/`source` ARE compared (they can change without
    // a content write, e.g. rename / categorize) but they're short. Net: the
    // sidebar diff no longer touches the long strings.
    static func == (lhs: ChatSession, rhs: ChatSession) -> Bool {
        lhs.id == rhs.id
            && lhs.updatedAt == rhs.updatedAt
            && lhs.pinnedAt == rhs.pinnedAt
            // `folderId` MUST be compared: moving a session between folders
            // changes neither `updatedAt` nor any other compared field, so
            // without this the sidebar would keep rendering the row in its old
            // section until some unrelated mutation bumped the diff.
            && lhs.folderId == rhs.folderId
            && lhs.title == rhs.title
            && lhs.category == rhs.category
            && lhs.source == rhs.source
            && lhs.lastSyncedAt == rhs.lastSyncedAt
            && lhs.remoteDeviceId == rhs.remoteDeviceId
            && lhs.remoteDeviceName == rhs.remoteDeviceName
        // Intentionally NOT comparing `lastMessage` (long string; `updatedAt`
        // is its change proxy) or the immutable `modelId` / `createdAt`.
    }

    func hash(into hasher: inout Hasher) {
        // Mirror ==: cheap, identity + updatedAt. Avoids hashing long strings.
        hasher.combine(id)
        hasher.combine(updatedAt)
    }
}

/// A user-created folder that groups sessions on the home list.
///
/// Named "Folder" rather than "Group" on purpose: the codebase already has
/// three other "group"s — `ModelGroup` (the LLM provider fallback group behind
/// the home FAB's "New Chat with Group"), the `__grp__` draft-session id
/// encoding (which embeds a ModelGroup id), and SwiftUI's `Group {}`.
///
/// Identity is a locally generated UUID, and `name` deliberately carries no
/// uniqueness constraint:
///
/// - **Why not key on the name.** Renaming would stop being a single-field
///   edit and become an identity change (delete old key, create new key,
///   migrate every member). That multi-row operation tears across devices: if
///   device A renames while device B files sessions under the old name, B's
///   sessions end up referencing a key that no longer exists and silently fall
///   back to ungrouped. With a UUID, a rename is one LWW field on `FolderV2`
///   and members are untouched.
/// - **Why duplicate names are allowed.** Two devices each creating "Work"
///   offline produce two distinct UUIDs, and both are kept. Auto-merging is
///   irreversible and two same-named folders are not necessarily the same
///   thing; the user can always move sessions across and dissolve the leftover.
///   Consequence: any *name-based* lookup must tolerate multiple matches (see
///   `findFolderByName`).
struct ChatFolder: Identifiable, Codable, Hashable {
    let id: String
    var name: String
    var icon: String?         // SF Symbol name; nil = use the composed icon
    var color: String?        // theme color token
    var origin: String        // "manual" | "ai" — provenance only, no behavior
    var sortIndex: Int        // reserved for V2 drag-reorder
    var pinnedAt: Date?       // non-nil = folder pinned above unpinned folders
    /// One-sentence description (≤100 chars). Auto-grouping context; never
    /// rendered in the home list, but shown as the Move-to-Group picker row
    /// subtitle and surfaced for editing in the rename dialog.
    var desc: String?
    let createdAt: Date
    var updatedAt: Date

    var isPinned: Bool { pinnedAt != nil }

    init(
        id: String = UUID().uuidString,
        name: String,
        icon: String? = nil,
        color: String? = nil,
        origin: String = "manual",
        sortIndex: Int = 0,
        pinnedAt: Date? = nil,
        desc: String? = nil,
        createdAt: Date = Date(),
        updatedAt: Date = Date()
    ) {
        self.id = id
        self.name = name
        self.icon = icon
        self.color = color
        self.origin = origin
        self.sortIndex = sortIndex
        self.pinnedAt = pinnedAt
        self.desc = desc
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
}

/// Role for a raw message turn.
enum MessageRole: String, Codable {
    case user
    case assistant
}

/// Reference to a media file stored in Library/MinisChat/minis/<sessionId>/
struct MediaRef: Codable, Hashable {
    let id: String
    let relativePath: String
    let mimeType: String
    let originalFileName: String?
    /// iSH-visible linux path the file is mirrored to (e.g.
    /// `/var/minis/attachments/uploads/<name>`, `/var/minis/browser/<sid>/...`).
    /// Optional — older persisted rows decode with `nil` and fall back to
    /// spillover at request-budget elide time. New writes always populate
    /// this when the file is offloaded to iSH-visible storage.
    let linuxPath: String?

    init(id: String, relativePath: String, mimeType: String, originalFileName: String?, linuxPath: String? = nil) {
        self.id = id
        self.relativePath = relativePath
        self.mimeType = mimeType
        self.originalFileName = originalFileName
        self.linuxPath = linuxPath
    }
}

/// A tool call requested by the assistant.
struct ToolUse: Codable, Hashable {
    let toolUseId: String
    let name: String
    let input: String
    let description: String?
    let thoughtSignature: String?  // Gemini 3.x thought signature
}

/// A snapshot captured from a tool execution result.
struct ToolSnapshot: Codable, Hashable {
    enum SnapshotType: String, Codable, Hashable {
        case text
        case image
    }
    let type: SnapshotType
    let text: String?        // For .text snapshots (last N lines of output)
    let mediaRef: MediaRef?  // For .image snapshots (browser screenshot)
    let duration: TimeInterval?  // Execution duration in seconds
}

/// Result of executing a tool.
struct ToolResult: Codable, Hashable {
    let toolUseId: String
    let output: String
    let success: Bool
    let mediaRef: MediaRef?
    let snapshot: ToolSnapshot?
    /// Final page URL after browser tool execution (for display in tool detail sheet).
    /// Truncated to ≤512 characters if the original was longer.
    let pageURL: String?
    /// Terminal execution status: "success" | "failed" | "cancelled".
    /// Optional for backward compatibility with rows written before this field existed;
    /// when nil, callers fall back to `success` (which cannot distinguish failed from cancelled).
    let status: String?

    /// Truncate a URL to at most `maxLength` characters by eliding the middle.
    /// Returns the original if it's already short enough.
    static func truncateURL(_ url: String, maxLength: Int = 512) -> String {
        guard url.count > maxLength else { return url }
        let marker = "…[truncated]…"
        let keep = (maxLength - marker.count) / 2
        let head = String(url.prefix(keep))
        let tail = String(url.suffix(keep))
        return head + marker + tail
    }
}

/// A single content part within a message — the atomic unit.
enum ContentPart: Codable, Hashable {
    case text(String)
    case mediaRef(MediaRef)
    case toolUse(ToolUse)
    case toolResult(ToolResult)

    // MARK: Codable

    private enum CodingKeys: String, CodingKey {
        case type
        case value
    }

    private enum PartType: String, Codable {
        case text
        case mediaRef
        case toolUse
        case toolResult
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .text(let s):
            try container.encode(PartType.text, forKey: .type)
            try container.encode(s, forKey: .value)
        case .mediaRef(let ref):
            try container.encode(PartType.mediaRef, forKey: .type)
            try container.encode(ref, forKey: .value)
        case .toolUse(let tu):
            try container.encode(PartType.toolUse, forKey: .type)
            try container.encode(tu, forKey: .value)
        case .toolResult(let tr):
            try container.encode(PartType.toolResult, forKey: .type)
            try container.encode(tr, forKey: .value)
        }
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let partType = try container.decode(PartType.self, forKey: .type)
        switch partType {
        case .text:
            self = .text(try container.decode(String.self, forKey: .value))
        case .mediaRef:
            self = .mediaRef(try container.decode(MediaRef.self, forKey: .value))
        case .toolUse:
            self = .toolUse(try container.decode(ToolUse.self, forKey: .value))
        case .toolResult:
            self = .toolResult(try container.decode(ToolResult.self, forKey: .value))
        }
    }
}

/// A compact boundary marker in a session's message history.
/// Represents the point where older messages were summarized to save context.
///
/// Identity model (post Phase A):
///   - `firstKeptMessageId` is the authoritative active-region start.
///   - `lastCompactedMessageId` is the last message in the compacted range.
///   - Sort-order fields (`firstKeptSortOrder`, `uiBoundarySortOrder`) and
///     `boundaryMessageId` are retained for legacy markers and cross-device
///     sync compatibility with older builds, used only as fallbacks.
struct CompactMarker: Identifiable, Codable {
    let id: String
    let sessionId: String
    /// LLM-generated summary of the compacted messages.
    let summary: String
    /// LEGACY: sort_order of the first message AFTER the compacted range.
    /// Only used as fallback when firstKeptMessageId resolution fails.
    let firstKeptSortOrder: Int
    /// Number of raw messages that were compacted. UI display only.
    let compactedCount: Int
    let createdAt: Date
    /// LEGACY: sort_order of the UI boundary message.
    /// Only used as fallback for divider positioning on legacy markers.
    let uiBoundarySortOrder: Int?
    /// LEGACY: DB message ID of the boundary message (pre-Phase A name for firstKeptMessageId).
    /// Kept for cross-device sync compatibility with older builds.
    let boundaryMessageId: String?
    /// PRIMARY: DB message ID of the first kept (active-region) message.
    /// All restore / compact logic resolves boundaries through this id.
    /// Optional only for legacy markers written before Phase A.
    let firstKeptMessageId: String?
    /// PRIMARY: DB message ID of the last compacted message (right edge of the
    /// compacted range). Used by prune protection to avoid deleting marker anchors.
    /// Optional only for legacy markers written before Phase A.
    let lastCompactedMessageId: String?
    /// Marker schema version. 1 = legacy multi-field model (firstKept/boundary/
    /// sortOrder fallback chain). 2 = simplified id-only model: only
    /// `lastCompactedMessageId` is authoritative; everything before it (incl.)
    /// is the compacted range, everything after it is "live anchor + new msgs".
    /// `compactedCount` and `summary` are still meaningful for UI; all other
    /// columns are ignored for v2 markers.
    var version: Int = 1
}

/// Token usage stats stored with a message.
struct StoredTokenUsage: Codable, Hashable {
    var inputTokens: Int
    var outputTokens: Int
    var cacheCreationTokens: Int
    var cacheReadTokens: Int
    var latestContextTokens: Int?
}

/// A single message in the conversation (one "turn").
struct RawMessage: Identifiable, Codable, Hashable {
    let id: String
    let sessionId: String
    let role: MessageRole
    let parts: [ContentPart]
    let createdAt: Date
    var tokenUsage: StoredTokenUsage?
    /// Reasoning content from thinking models (Kimi, DeepSeek, QwQ, etc.) — must be echoed back.
    var reasoningContent: String?
    /// Number of mid-stream auto-retries that occurred before this message completed successfully.
    /// 0 means no interruption. Persisted to DB for display after session reload.
    var streamInterruptCount: Int = 0
    /// Database sort_order value. Populated during loadMessages, used for compact boundary tracking.
    var sortOrder: Int = 0
    /// [T-error-persist-ios] Device-local error string for a failed assistant
    /// turn. Mirrors ChatMessage.error; persisted to the messages.error_info
    /// column so the error indicator survives reload. nil = no error.
    var errorInfo: String? = nil

    /// [T-token-attribution-snapshot] The model that ACTUALLY produced this
    /// message, snapshotted when it was written.
    ///
    /// Snapshot rather than a reference to resolve later: usage is a record of
    /// what happened, and it must not change when the configuration it once
    /// pointed at changes. `modelDisplayName` / `providerType` travel with the
    /// id so a deleted provider does not make history unreadable.
    ///
    /// nil on rows written before these columns existed — the signal the Usage
    /// page uses to mark a row estimated rather than measured.
    var modelId: String? = nil
    var modelDisplayName: String? = nil
    /// `ProviderType` **rawValue** (e.g. `openAI`), never a display name.
    var providerType: String? = nil
    /// Diagnostics / disambiguation only; the UI never resolves through it.
    var providerInstanceId: String? = nil

    /// True if this message contains only tool results (no user text).
    /// These are internal agent loop messages that shouldn't render as user bubbles.
    var isToolResultOnly: Bool {
        role == .user && !parts.isEmpty && parts.allSatisfy {
            if case .toolResult = $0 { return true }
            return false
        }
    }

    /// [T-bridge-message-ui-leak] The internal assistant "bridge" row inserted
    /// when a queued user message interrupts a tool loop (#579): it exists
    /// purely to keep agentHistory role-alternation intact
    /// (…user(tool_result) → assistant(bridge) → user(queued)…) and must
    /// never render in the chat UI. Detected by content match rather than a
    /// schema flag: we generate this text verbatim, a content match also hides
    /// rows persisted by OLDER builds (a new column could not), and no
    /// DB/iCloud-sync wiring is needed.
    static let internalBridgeText =
        "(Interrupted mid-task by a new user message. Decide based on the new message and overall context whether the prior task should continue — do not forget or abandon it unless the user explicitly says to stop, or the new message makes clear it is no longer needed.)"

    /// Every bridge text this app has ever generated. A row persisted by an
    /// OLDER build carries the PREVIOUS wording; matching the current constant
    /// alone would fail and leak that row into the UI (the exact regression seen
    /// after the 2026-07-23 wording change, d2e111e9). Match against the full
    /// set so old and new persisted bridges are both recognized. Prefix-match
    /// (not equality) tolerates trailing-whitespace / normalization drift from
    /// DB round-trips.
    static let internalBridgeTexts: [String] = [
        internalBridgeText,
        // Pre-d2e111e9 wording.
        "(Interrupted mid-task to handle your new message. Will return to the prior task after.)",
    ]

    /// True when `text` is any known internal-bridge string. Shared by
    /// RawMessage and ChatMessage so every layer recognizes the same rows.
    static func isInternalBridgeText(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return internalBridgeTexts.contains { trimmed == $0 }
    }

    var isInternalBridge: Bool {
        guard role == .assistant, parts.count == 1,
              case .text(let s) = parts[0] else { return false }
        return Self.isInternalBridgeText(s)
    }
}

// MARK: - ChatStore Actor

/// Persistent store for chat sessions, messages, and media files.
/// All methods are synchronous SQLite operations (call from background if needed).
actor ChatStore {
    static let shared = ChatStore()

    private var db: OpaquePointer?
    /// Base URL for per-session media storage: Library/MinisChat/minis/
    let minisBaseURL: URL

    private let dbURL: URL

    // [T-ios-listsessions-cache] Cache the last listSessions() result and only
    // recompute when a mutation that can change the list has fired. listSessions
    // parses every session's parts_json into fresh preview Strings (~1772 String
    // allocations per call); ContentView fires it from many refresh paths, and a
    // malloc_history stack proved these Strings dominated the heap (hundreds of
    // MB) — every redundant call minted a whole new batch that SwiftUI kept
    // referencing across body passes. With this cache, back-to-back calls with
    // no intervening write return the SAME array (no new Strings). Guarded by
    // `sessionListCacheDirty`, set true by every list-affecting mutation below.
    // ChatStore is an actor, so these fields need no extra locking.
    private var sessionListCache: [ChatSession]?
    private var sessionListCacheDirty = true

    /// [T-ios-listsessions-cache] Mark the cached session list stale. Called by
    /// every mutation that can change what listSessions() returns — the row set
    /// (create/delete), ordering (updated_at via touchSession), or a displayed
    /// field (title/category/pin/model/preview text from new messages).
    /// `internal` rather than `private` so the backup restore path
    /// (ChatStore+BackupRestore.swift) can invalidate after its own row writes —
    /// Swift's `private` is file-scoped, so an extension in another file cannot
    /// see it.
    func invalidateSessionListCache() {
        sessionListCacheDirty = true
    }

    init() {
        let libraryURL = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let baseURL = libraryURL.appendingPathComponent("MinisChat", isDirectory: true)
        self.dbURL = baseURL.appendingPathComponent("minis.db")
        self.minisBaseURL = baseURL.appendingPathComponent("minis", isDirectory: true)

        try? FileManager.default.createDirectory(at: baseURL, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: minisBaseURL, withIntermediateDirectories: true)

        openDatabase()
        createTables()
        armRebootGuardIfDegraded()
    }

    // MARK: - [T-ios-reboot-config-loss] Protected-data reboot guard

    /// True when the DB cannot actually serve queries after open. After a
    /// device reboot iOS can relaunch the app in the background BEFORE first
    /// unlock (BGTask / CloudKit push / location session). minis.db carries
    /// the default completeUntilFirstUserAuthentication protection class, so
    /// sqlite3_open "succeeds" lazily but every statement fails — the session
    /// list reads as empty and the home screen looks freshly installed until
    /// the process is killed. Nothing is deleted (we never drop/recreate on
    /// failure), so a post-unlock reopen fully restores the store.
    private var dbDegraded = false

    private func probeDBUsable() -> Bool {
        var stmt: OpaquePointer?
        let rc = sqlite3_prepare_v2(db, "SELECT count(*) FROM sessions", -1, &stmt, nil)
        sqlite3_finalize(stmt)
        return rc == SQLITE_OK
    }

    private func armRebootGuardIfDegraded() {
        guard !probeDBUsable() else { return }
        dbDegraded = true
        logger.error("[RebootGuard] minis.db unusable after open — likely launched before first unlock; arming post-unlock reopen")
        // Recover when protected data unlocks, plus on every foreground
        // activation (covers the unlock racing observer registration).
        // Observers stay registered after recovery; reopenIfDegraded no-ops.
        for name in [UIApplication.protectedDataDidBecomeAvailableNotification,
                     UIApplication.didBecomeActiveNotification] {
            NotificationCenter.default.addObserver(forName: name, object: nil, queue: nil) { _ in
                Task { await ChatStore.shared.reopenIfDegraded() }
            }
        }
    }

    func reopenIfDegraded() {
        guard dbDegraded else { return }
        reloadDatabase()
        guard probeDBUsable() else {
            logger.warning("[RebootGuard] reopen attempt still degraded — will retry on the next unlock/activation signal")
            return
        }
        dbDegraded = false
        invalidateSessionListCache()
        logger.info("[RebootGuard] minis.db reopened after unlock — session store restored")
    }

    /// Initialize with a custom base URL (for testing).
    init(baseURL: URL) {
        self.dbURL = baseURL.appendingPathComponent("minis.db")
        self.minisBaseURL = baseURL.appendingPathComponent("minis", isDirectory: true)

        try? FileManager.default.createDirectory(at: baseURL, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: minisBaseURL, withIntermediateDirectories: true)

        openDatabase()
        createTables()
    }

    deinit {
        if let db { sqlite3_close(db) }
    }

    // MARK: - Reload / Close (for backup & restore)

    func closeDatabase() {
        if let db {
            sqlite3_close(db)
            self.db = nil
        }
    }

    func reloadDatabase() {
        closeDatabase()
        openDatabase()
        createTables()
    }

    // MARK: - Database Setup

    private func openDatabase() {
        let result = sqlite3_open(dbURL.path, &db)
        if result != SQLITE_OK {
            logger.error("Failed to open database at \(self.dbURL.path): \(result)")
        }
        // Enable WAL mode for better concurrent read performance
        exec("PRAGMA journal_mode=WAL")
        exec("PRAGMA foreign_keys=ON")
    }

    private func createTables() {
        exec("""
            CREATE TABLE IF NOT EXISTS sessions (
                id          TEXT PRIMARY KEY,
                title       TEXT,
                model_id    TEXT NOT NULL,
                created_at  REAL NOT NULL,
                updated_at  REAL NOT NULL
            )
        """)

        exec("""
            CREATE TABLE IF NOT EXISTS messages (
                id          TEXT PRIMARY KEY,
                session_id  TEXT NOT NULL REFERENCES sessions(id),
                role        TEXT NOT NULL,
                parts_json  TEXT NOT NULL,
                created_at  REAL NOT NULL,
                token_usage TEXT,
                sort_order  INTEGER NOT NULL
            )
        """)

        exec("CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, sort_order)")
        // [T-ios-listsessions-part-flags] Supports listSessions' per-role
        // preview subqueries: SEARCH … (session_id=? AND role=?) ORDER BY
        // sort_order DESC LIMIT 1. Measured ~30ms vs ~58ms on the plain
        // idx_messages_session, on the 100k-message DB.
        exec("CREATE INDEX IF NOT EXISTS idx_msg_sess_role_sort ON messages(session_id, role, sort_order DESC)")

        // Idempotent column migrations — guarded via PRAGMA table_info so
        // re-runs on already-migrated DBs don't emit duplicate-column errors.
        addColumnIfMissing(table: "sessions", column: "category", definition: "TEXT")
        addColumnIfMissing(table: "sessions", column: "model_binding", definition: "TEXT")
        addColumnIfMissing(table: "sessions", column: "source", definition: "TEXT")
        addColumnIfMissing(table: "messages", column: "reasoning_content", definition: "TEXT")
        addColumnIfMissing(table: "messages", column: "stream_interrupt_count", definition: "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(table: "sessions", column: "memory_enabled", definition: "INTEGER NOT NULL DEFAULT 1")
        addColumnIfMissing(table: "sessions", column: "last_synced_at", definition: "REAL")
        addColumnIfMissing(table: "sessions", column: "remote_origin_device_id", definition: "TEXT")
        addColumnIfMissing(table: "sync_devices", column: "upload_types", definition: "TEXT NOT NULL DEFAULT ''")
        addColumnIfMissing(table: "sessions", column: "pinned_at", definition: "REAL")
        // Folder membership. NULL = ungrouped. Intentionally NOT a declared FK:
        // a folder_id pointing at a folder that does not exist locally is a
        // legitimate transient state (the session's SessionV2 record can arrive
        // before its FolderV2 record, and a folder deleted on another device
        // leaves references behind). Such orphans render as ungrouped rather
        // than failing a constraint — see foldersById callers in ContentView.
        addColumnIfMissing(table: "sessions", column: "folder_id", definition: "TEXT")
        // Folder pinning (mirrors sessions.pinned_at). Also present in the
        // CREATE TABLE DDL above — per the add-column wipe trap, a new column
        // must exist in BOTH the initial DDL and the idempotent migration.
        addColumnIfMissing(table: "folders", column: "pinned_at", definition: "REAL")
        // One-sentence group description (≤100 chars): written at creation
        // (typed or AI-suggested), edited in the rename dialog, and fed to
        // the auto-grouping prompt as context. Not rendered in the list.
        addColumnIfMissing(table: "folders", column: "description", definition: "TEXT")
        addColumnIfMissing(table: "messages", column: "updated_at", definition: "REAL")
        // [T-error-persist-ios] Device-local error string shown on a failed
        // assistant turn (provider/network/empty-response/context-full/…). Persisted
        // so the indicator survives session reload / app restart. NULL = no error.
        // Not synced via iCloud (errors are device-local; absent from the CKRecord).
        addColumnIfMissing(table: "messages", column: "error_info", definition: "TEXT")
        // [T-ios-listsessions-part-flags] Device-local bitmask of which
        // ContentPart types a row's parts_json contains — bit0=hasText,
        // bit1=hasToolUse. Lets listSessions() replace 4 un-indexable
        // `parts_json LIKE '%…%'` subqueries per session with cheap integer
        // masks (523ms → single-digit ms cold on a 100k-message DB). Derived
        // purely from parts_json, so it is NOT synced via iCloud (like
        // error_info): inbound merge / backfill recompute it locally on write.
        // Backfilled once for pre-existing rows below.
        addColumnIfMissing(table: "messages", column: "part_flags", definition: "INTEGER NOT NULL DEFAULT 0")
        // v2 sync: soft-delete tombstone for sessions deleted on a peer
        // device but kept locally. Non-nil = tombstoned at that timestamp.
        // See SyncV2 §3.3.
        addColumnIfMissing(table: "sessions", column: "remote_tombstoned_at", definition: "REAL")
        // v2 sync: priority + creation order on dirty rows so migration's
        // 28k+ row backlog never starves freshly-typed user messages.
        // priority: 0 = user-driven (default), 1 = migration backlog
        // (push only after priority=0 drains in each batch).
        addColumnIfMissing(table: "sync_dirty_records", column: "priority", definition: "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(table: "sync_dirty_records", column: "created_at", definition: "REAL NOT NULL DEFAULT 0")

        // [T-token-attribution-snapshot] Which model actually produced each
        // message, captured at write time.
        //
        // The Usage page used to derive this by joining `sessions.model_id` —
        // one mutable column per session, rewritten on every model switch
        // (including silent failover) with no history. The join has no time
        // dimension, so a session's entire past was re-attributed to whatever
        // model it currently pointed at: a billion deepseek tokens became grok
        // tokens on switch, and moved back on switching back.
        //
        // The display name and provider type are stored alongside the id, not
        // just the id, so a provider the user later deletes still renders as
        // the model they actually used instead of collapsing into "Other" —
        // for a custom model the live config is the only other source, and
        // deleting the instance removes it.
        //
        // All four are nullable BY DESIGN: rows written before this point read
        // NULL, which is precisely how the UI separates "measured" from
        // "estimated from the session". A NOT NULL DEFAULT would erase that.
        addColumnIfMissing(table: "messages", column: "model_id", definition: "TEXT")
        addColumnIfMissing(table: "messages", column: "model_display_name", definition: "TEXT")
        addColumnIfMissing(table: "messages", column: "provider_type", definition: "TEXT")
        addColumnIfMissing(table: "messages", column: "provider_instance_id", definition: "TEXT")

        // One-shot cleanup: drop legacy v1 dirty rows that have a v2
        // counterpart. Under the V2 engine these have no consumer (the
        // v1 engine is paused, SyncCore filters via v2Only:true) so they
        // accumulate forever. The longer-term fix is the markDirty
        // guard above that no longer writes them in V2 mode, but
        // existing installs still carry the rows from before that fix
        // and from any V2 build that wrote them as dual-write — so
        // sweep them once on first launch of this build.
        let zombieCleanupKey = "cloudSync.v2.zombieDirtyCleanupV1Done"
        if !UserDefaults.standard.bool(forKey: zombieCleanupKey) {
            let v1Types = ["Session", "Message", "CompactMarker", "SessionFile",
                           "Skill", "ProviderConfig", "EnvVar", "SyncDevice", "Soul"]
            let inList = v1Types.map { "'\($0)'" }.joined(separator: ",")
            var deletedCount: Int32 = 0
            let delSQL = "DELETE FROM sync_dirty_records WHERE record_type IN (\(inList))"
            if sqlite3_exec(db, delSQL, nil, nil, nil) == SQLITE_OK {
                deletedCount = Int32(sqlite3_changes(db))
            }
            UserDefaults.standard.set(true, forKey: zombieCleanupKey)
            iCloudLogger.info("[iCloudTrace] v1 dirty-row zombie cleanup: deleted=\(deletedCount) types=\(v1Types.joined(separator: ","))")
        }

        // Compact markers table — tracks context compaction boundaries per session
        exec("""
            CREATE TABLE IF NOT EXISTS compact_markers (
                id                      TEXT PRIMARY KEY,
                session_id              TEXT NOT NULL REFERENCES sessions(id),
                summary                 TEXT NOT NULL,
                first_kept_sort_order   INTEGER NOT NULL,
                compacted_count         INTEGER NOT NULL,
                created_at              REAL NOT NULL,
                ui_boundary_sort_order  INTEGER
            )
        """)
        exec("CREATE INDEX IF NOT EXISTS idx_compact_markers_session ON compact_markers(session_id, created_at)")
        // Idempotent column migrations for compact_markers.
        addColumnIfMissing(table: "compact_markers", column: "ui_boundary_sort_order", definition: "INTEGER")
        addColumnIfMissing(table: "compact_markers", column: "boundary_message_id", definition: "TEXT")
        // Phase A: id-first boundary model. New markers MUST fill these columns.
        addColumnIfMissing(table: "compact_markers", column: "first_kept_message_id", definition: "TEXT")
        addColumnIfMissing(table: "compact_markers", column: "last_compacted_message_id", definition: "TEXT")
        // Phase v2: simplified marker model. version=2 markers anchor on
        // last_compacted_message_id ONLY (a real, persisted, UI-visible message
        // id). All other id/sortOrder fields are unused for v2 markers. Older
        // markers default to version=1 so legacy resolution code keeps running.
        addColumnIfMissing(table: "compact_markers", column: "version", definition: "INTEGER NOT NULL DEFAULT 1")
        exec("CREATE INDEX IF NOT EXISTS idx_compact_markers_first_kept ON compact_markers(session_id, first_kept_message_id)")

        // [T-ios-listsessions-part-flags] One-shot backfill of part_flags for
        // rows written before the column existed (all default to 0). Computed
        // in pure SQL with the SAME LIKE patterns the old listSessions used, so
        // backfilled flags reproduce the previous preview selection exactly.
        // Guarded by a UserDefaults flag (mirrors zombieCleanupKey) so it runs
        // once. Scoped to rows still at the default (part_flags = 0 AND the JSON
        // actually contains a text/toolUse part) so re-runs after a partial
        // failure stay cheap and correct.
        let partFlagsBackfillKey = "chatStore.partFlagsBackfillV1Done"
        if !UserDefaults.standard.bool(forKey: partFlagsBackfillKey) {
            let expr = Self.partFlagsSQLExpr("parts_json")
            let backfillSQL = """
                UPDATE messages SET part_flags = \(expr)
                WHERE part_flags = 0
                  AND (parts_json LIKE '%"type":"text"%' OR parts_json LIKE '%"type":"toolUse"%')
            """
            if sqlite3_exec(db, backfillSQL, nil, nil, nil) == SQLITE_OK {
                let changed = Int(sqlite3_changes(db))
                UserDefaults.standard.set(true, forKey: partFlagsBackfillKey)
                iCloudLogger.info("[part_flags] backfill done: updated \(changed) row(s)")
            } else {
                let err = String(cString: sqlite3_errmsg(db))
                // Leave the flag unset so it retries next launch.
                iCloudLogger.error("[part_flags] backfill FAILED (will retry next launch): \(err)")
            }
        }


        // iCloud sync tables
        exec("""
            CREATE TABLE IF NOT EXISTS sync_dirty_records (
                record_type TEXT NOT NULL,
                record_id   TEXT NOT NULL,
                zone_name   TEXT NOT NULL,
                operation   TEXT NOT NULL DEFAULT 'upsert',
                PRIMARY KEY (record_type, record_id)
            )
        """)
        // Distinct recordIDs that have ever been confirmed saved to
        // iCloud (via CKSyncEngine SentRecordZoneChanges). Used by the
        // migration progress UI to show a real "unique records pushed"
        // count rather than the historical save-event counter, which
        // double-counts every relaunch's re-mark-dirty + re-save cycle.
        // Insert is INSERT OR IGNORE so duplicate saves are silent.
        exec("""
            CREATE TABLE IF NOT EXISTS sync_pushed_records (
                record_name TEXT PRIMARY KEY
            )
        """)
        // [T-icloud-deleted-session-resurrection] Recent-delete tombstones.
        // When a session is deleted locally its rows are hard-deleted, but the
        // cloud delete (CKSyncEngine tombstone) is pushed asynchronously. In the
        // window before it lands, fetchRecentV2's active query can pull the
        // still-present old SessionV2/MessageV2/SessionFileV2 records back and
        // mergeRemote* re-INSERTs them — the session "resurrects" (with a ☁️
        // remote-origin marker). This table records every locally-deleted
        // session id + when, so the inbound merge can refuse to re-insert a
        // record that isn't newer than the deletion. Entries are pruned only by
        // the 30-day TTL below — session ids are UUIDs (never reused), so a
        // lingering tombstone never wrongly blocks a future session, and the
        // table stays tiny.
        exec("""
            CREATE TABLE IF NOT EXISTS deleted_session_tombstones (
                session_id TEXT PRIMARY KEY,
                deleted_at REAL NOT NULL
            )
        """)
        // [T-icloud-deleted-session-resurrection] Bound the table size: the
        // cloud delete normally lands within seconds, after which the server
        // record can no longer be fetched back, so the tombstone has done its
        // job. Session ids are UUIDs (never reused), so a stale tombstone can't
        // wrongly block a legitimate future session — but prune entries older
        // than 30 days on launch so the table can't grow without bound.
        exec("DELETE FROM deleted_session_tombstones WHERE deleted_at < \(Date().timeIntervalSince1970 - 30 * 24 * 60 * 60)")
        // [T-icloud-record-delete-resurrection] Same resurrection guard,
        // generalized to other per-record types whose deletes propagate as
        // op=delete but whose inbound merges would otherwise happily re-insert
        // a stale live record fetched by fetchRecentV2 before the cloud delete
        // lands (CKQuery never sees deletions). Currently written for
        // Skill / EnvVarItem / CompactMarker via the markDirty(op=delete)
        // chokepoint. Same 30-day TTL prune as the session table.
        exec("""
            CREATE TABLE IF NOT EXISTS deleted_record_tombstones (
                record_type TEXT NOT NULL,
                record_id   TEXT NOT NULL,
                deleted_at  REAL NOT NULL,
                PRIMARY KEY (record_type, record_id)
            )
        """)
        exec("DELETE FROM deleted_record_tombstones WHERE deleted_at < \(Date().timeIntervalSince1970 - 30 * 24 * 60 * 60)")
        // Session folders (home-list grouping). `id` is a locally generated
        // UUID — uniqueness needs no cross-device coordination, which is why
        // it beats keying on the name when devices create folders offline.
        // `name` deliberately has NO UNIQUE constraint: two devices may each
        // create a "Work" folder and both are kept (see ChatFolder's doc
        // comment for the full rationale).
        exec("""
            CREATE TABLE IF NOT EXISTS folders (
                id          TEXT PRIMARY KEY,
                name        TEXT NOT NULL,
                icon        TEXT,
                color       TEXT,
                origin      TEXT NOT NULL DEFAULT 'manual',
                sort_index  INTEGER NOT NULL DEFAULT 0,
                pinned_at   REAL,
                description TEXT,
                created_at  REAL NOT NULL,
                updated_at  REAL NOT NULL
            )
        """)
        exec("""
            CREATE TABLE IF NOT EXISTS sync_devices (
                device_id   TEXT PRIMARY KEY,
                device_name TEXT NOT NULL,
                zone_name   TEXT NOT NULL,
                last_seen   REAL NOT NULL,
                os_version  TEXT NOT NULL DEFAULT ''
            )
        """)
        exec("""
            CREATE TABLE IF NOT EXISTS remote_sessions (
                id          TEXT NOT NULL,
                device_id   TEXT NOT NULL,
                title       TEXT,
                category    TEXT,
                model_id    TEXT NOT NULL DEFAULT 'unknown',
                created_at  REAL NOT NULL,
                updated_at  REAL NOT NULL,
                model_binding TEXT,
                PRIMARY KEY (id, device_id)
            )
        """)
        exec("""
            CREATE TABLE IF NOT EXISTS remote_messages (
                id          TEXT NOT NULL,
                device_id   TEXT NOT NULL,
                session_id  TEXT NOT NULL,
                role        TEXT NOT NULL,
                parts_json  TEXT NOT NULL,
                created_at  REAL NOT NULL,
                token_usage TEXT,
                sort_order  INTEGER NOT NULL DEFAULT 0,
                reasoning_content TEXT,
                stream_interrupt_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (id, device_id)
            )
        """)
        exec("CREATE INDEX IF NOT EXISTS idx_remote_messages_session ON remote_messages(session_id, device_id, sort_order)")
        exec("""
            CREATE TABLE IF NOT EXISTS remote_media (
                id              TEXT NOT NULL,
                device_id       TEXT NOT NULL,
                session_id      TEXT NOT NULL,
                relative_path   TEXT NOT NULL,
                mime_type       TEXT,
                original_file_name TEXT,
                local_cache_path TEXT,
                PRIMARY KEY (id, device_id)
            )
        """)
        exec("""
            CREATE TABLE IF NOT EXISTS remote_skills (
                id          TEXT NOT NULL,
                device_id   TEXT NOT NULL,
                name        TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL DEFAULT '',
                version     TEXT NOT NULL DEFAULT '1.0.0',
                import_source TEXT NOT NULL DEFAULT 'file',
                is_enabled  INTEGER NOT NULL DEFAULT 1,
                installed_at REAL NOT NULL,
                updated_at  REAL NOT NULL,
                body_text   TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (id, device_id)
            )
        """)
        exec("""
            CREATE TABLE IF NOT EXISTS remote_memories (
                id          TEXT NOT NULL,
                device_id   TEXT NOT NULL,
                file_name   TEXT NOT NULL DEFAULT '',
                content     TEXT NOT NULL DEFAULT '',
                updated_at  REAL NOT NULL,
                PRIMARY KEY (id, device_id)
            )
        """)
        exec("""
            CREATE TABLE IF NOT EXISTS remote_provider_configs (
                device_id   TEXT NOT NULL PRIMARY KEY,
                config_json TEXT NOT NULL DEFAULT '{}',
                updated_at  REAL NOT NULL
            )
        """)
        exec("""
            CREATE TABLE IF NOT EXISTS remote_env_vars (
                device_id       TEXT NOT NULL PRIMARY KEY,
                env_vars_json   TEXT NOT NULL DEFAULT '[]',
                updated_at      REAL NOT NULL
            )
        """)
        // WebApp-style home-screen shortcuts. One row per HTML the user has
        // "Added to Home Screen". Path resolution and host-URL derivation
        // happen at launch via WebAppPathResolver — stored values are
        // sandbox-relative so the row survives container UUID rotation.
        exec("""
            CREATE TABLE IF NOT EXISTS webapp_shortcuts (
                id                 TEXT PRIMARY KEY,
                html_path          TEXT NOT NULL,
                path_scope         TEXT NOT NULL,
                scope_context      TEXT,
                title              TEXT NOT NULL,
                icon_ref           TEXT NOT NULL,
                icon_cache_path    TEXT,
                created_at         REAL NOT NULL,
                source_session_id  TEXT
            )
        """)
        exec("CREATE INDEX IF NOT EXISTS idx_webapp_shortcuts_created ON webapp_shortcuts(created_at DESC)")

        purgeUnusableSessionRows()
    }

    /// [T-ios-listsessions-cstring-trap] Delete session rows whose primary key
    /// is NULL or empty.
    ///
    /// SQLite does NOT imply NOT NULL for a non-INTEGER `TEXT PRIMARY KEY`, so
    /// such a row is legal at the storage layer but unusable at every layer
    /// above it: it cannot be opened, deleted, or synced by id, and its
    /// `sqlite3_column_text` returns NULL — which trapped `String(cString:)` and
    /// SIGABRT'd the app inside `listSessions()` on every launch (crash loop
    /// 09:13:09 → 09:14:15). `listSessions` now skips these defensively; this
    /// clears them at startup so they stop being re-encountered.
    ///
    /// Child rows are removed first: `messages.session_id` has a FK to
    /// `sessions(id)` and `PRAGMA foreign_keys=ON` is set, so deleting the
    /// parent alone would fail. Orphaned children (session_id NULL / pointing at
    /// a NULL-id parent) can't be reattached to anything, so they go too.
    private func purgeUnusableSessionRows() {
        var stmt: OpaquePointer?
        var count = 0
        if sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM sessions WHERE id IS NULL OR id = ''", -1, &stmt, nil) == SQLITE_OK,
           sqlite3_step(stmt) == SQLITE_ROW {
            count = Int(sqlite3_column_int64(stmt, 0))
        }
        sqlite3_finalize(stmt)
        guard count > 0 else { return }

        logger.error("[ChatStore] purging \(count) unusable session row(s) with NULL/empty id (T-ios-listsessions-cstring-trap)")
        exec("DELETE FROM messages WHERE session_id IS NULL OR session_id = ''")
        exec("DELETE FROM sessions WHERE id IS NULL OR id = ''")
    }

    private func exec(_ sql: String) {
        var errMsg: UnsafeMutablePointer<CChar>?
        let result = sqlite3_exec(db, sql, nil, nil, &errMsg)
        if result != SQLITE_OK {
            let msg = errMsg.map { String(cString: $0) } ?? "unknown"
            logger.error("SQL error: \(msg)")
            sqlite3_free(errMsg)
        }
    }

    /// Returns true if `column` exists on `table`. Uses `PRAGMA table_info`.
    private func columnExists(table: String, column: String) -> Bool {
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        let sql = "PRAGMA table_info(\(table))"
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return false }
        while sqlite3_step(stmt) == SQLITE_ROW {
            // table_info columns: cid(0), name(1), type(2), notnull(3), dflt_value(4), pk(5)
            if let cName = sqlite3_column_text(stmt, 1) {
                if String(cString: cName) == column { return true }
            }
        }
        return false
    }

    /// Idempotent `ALTER TABLE ADD COLUMN` — checks `PRAGMA table_info` first
    /// so re-runs don't emit `duplicate column name` SQL errors into the log.
    private func addColumnIfMissing(table: String, column: String, definition: String) {
        if columnExists(table: table, column: column) { return }
        exec("ALTER TABLE \(table) ADD COLUMN \(column) \(definition)")
    }

    // MARK: - Session CRUD

    @discardableResult
    func createSession(modelId: String, title: String? = nil, source: String? = nil) -> ChatSession {
        invalidateSessionListCache()
        let now = Date()
        let session = ChatSession(
            id: UUID().uuidString,
            title: title,
            category: nil,
            modelId: modelId,
            createdAt: now,
            updatedAt: now,
            source: source
        )

        // [T-memory-enabled-new-session-bug] Explicitly write memory_enabled
        // from the global default. Previously the INSERT omitted the column
        // and relied on the SQL `DEFAULT 1`, so every new session was
        // memory_enabled=1 regardless of the Settings → Memory global toggle
        // (UserDefaults "memory.global.enabled"). That also made
        // getMemoryEnabled()'s "no row → read global default" fallback dead
        // code, since the row always existed with 1. Read the global default
        // here (mirroring that fallback) so turning the toggle off actually
        // starts new sessions with memory disabled. Existing sessions keep
        // their stored value; iCloud-synced sessions (the INSERT at ~L4492)
        // intentionally use the remote record's memory_enabled and are NOT
        // changed.
        let globalMemoryEnabled = (UserDefaults.standard.object(forKey: "memory.global.enabled") as? Bool) ?? true

        // [T-memory-enabled-new-session-bug DIAG] Trace what we read from
        // UserDefaults and what we bind, so a live repro shows whether the
        // global default is reaching createSession.
        let rawObj = UserDefaults.standard.object(forKey: "memory.global.enabled")
        memDiagLogger.info("[MemDiag] createSession sid=\(session.id.prefix(8)) rawDefaults=\(String(describing: rawObj)) resolved=\(globalMemoryEnabled) → bind memory_enabled=\(globalMemoryEnabled ? 1 : 0)")

        let sql = "INSERT INTO sessions (id, title, model_id, created_at, updated_at, source, memory_enabled) VALUES (?, ?, ?, ?, ?, ?, ?)"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (session.id as NSString).utf8String, -1, nil)
            bindOptionalText(stmt, index: 2, value: session.title)
            sqlite3_bind_text(stmt, 3, (session.modelId as NSString).utf8String, -1, nil)
            sqlite3_bind_double(stmt, 4, now.timeIntervalSince1970)
            sqlite3_bind_double(stmt, 5, now.timeIntervalSince1970)
            bindOptionalText(stmt, index: 6, value: source)
            sqlite3_bind_int(stmt, 7, globalMemoryEnabled ? 1 : 0)
            let rc = sqlite3_step(stmt)
            memDiagLogger.info("[MemDiag] createSession INSERT step rc=\(rc) (101=DONE) sid=\(session.id.prefix(8))")
        } else {
            memDiagLogger.error("[MemDiag] createSession prepare FAILED sid=\(session.id.prefix(8))")
        }
        sqlite3_finalize(stmt)

        // Deliberately NOT marking the Session dirty here. A freshly-created
        // session has no messages yet — it's a placeholder the user may never
        // actually use. Syncing it to iCloud would leak ghost "New Chat /
        // No messages yet" rows to every signed-in device, visible forever
        // since we don't auto-delete empty sessions.
        //
        // iCloud upload is instead triggered by:
        //   - appendMessages() — marks Session dirty when the first message lands
        //   - updateSessionTitle() / toggleSessionPin() — explicit user intent
        //   - deleteSession() — tombstone upload
        // This way, only sessions with actual content or explicit user intent
        // propagate across devices.
        return session
    }

    // MARK: - part_flags (message preview bitmask)

    /// [T-ios-listsessions-part-flags] Bit positions in `messages.part_flags`.
    /// Only text/toolUse are tracked — those are the only types listSessions'
    /// preview picker cares about. mediaRef/toolResult intentionally set no bit
    /// (a user row with only toolResult parts stays flag 0, exactly matching the
    /// old `LIKE '%"type":"text"%'` filter that excluded tool_result-only rows).
    static let partFlagHasText: Int = 1      // bit0
    static let partFlagHasToolUse: Int = 2   // bit1

    /// Compute the part_flags bitmask from a decoded `[ContentPart]` (Swift
    /// write paths). Zero-cost: the caller is already iterating the parts.
    static func partFlags(for parts: [ContentPart]) -> Int {
        var flags = 0
        for part in parts {
            switch part {
            case .text(let s):
                // Match the old SQL: the LIKE only fired on the presence of a
                // text part, regardless of emptiness. Keep that (an empty text
                // part still set the bit under LIKE), so preview selection is
                // byte-for-byte identical.
                _ = s
                flags |= partFlagHasText
            case .toolUse:
                flags |= partFlagHasToolUse
            case .mediaRef, .toolResult:
                break
            }
        }
        return flags
    }

    /// Compute part_flags from a raw parts_json string (iCloud merge/backfill
    /// paths only have the JSON, not a decoded array). Uses the SAME
    /// substring test the old listSessions LIKE patterns used, so the derived
    /// flags exactly reproduce the previous behavior — including the JSON-key-
    /// order robustness of matching just `"type":"text"` / `"type":"toolUse"`.
    static func partFlags(fromPartsJSON json: String) -> Int {
        var flags = 0
        if json.contains("\"type\":\"text\"") { flags |= partFlagHasText }
        if json.contains("\"type\":\"toolUse\"") { flags |= partFlagHasToolUse }
        return flags
    }

    /// SQL expression that computes part_flags for a row in a pure-SQL
    /// INSERT…SELECT (the two remote-backfill paths). `col` is the qualified
    /// parts_json column reference (e.g. "rm.parts_json"). Mirrors
    /// `partFlags(fromPartsJSON:)` exactly, so SQL-inserted rows match Swift-
    /// inserted rows.
    static func partFlagsSQLExpr(_ col: String) -> String {
        "((CASE WHEN \(col) LIKE '%\"type\":\"text\"%' THEN \(partFlagHasText) ELSE 0 END) "
        + "| (CASE WHEN \(col) LIKE '%\"type\":\"toolUse\"%' THEN \(partFlagHasToolUse) ELSE 0 END))"
    }

    /// Total session count via a bare `COUNT(*)` — ~1ms even on a 100k-message
    /// DB, versus `listSessions().count` which runs 4 un-indexable
    /// `parts_json LIKE '%…%'` subqueries per session (~0.5s cold on a large
    /// DB). Callers that only need the number MUST use this: on the
    /// serialized ChatStore actor a full listSessions() head-of-line-blocks
    /// loadSession, which was the ~3s session-open stall
    /// [T-ios-session-coldload-listsessions-block].
    func sessionCount() -> Int {
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM sessions", -1, &stmt, nil) == SQLITE_OK else { return 0 }
        return sqlite3_step(stmt) == SQLITE_ROW ? Int(sqlite3_column_int64(stmt, 0)) : 0
    }

    // MARK: - Safe column readers

    // [T-ios-listsessions-cstring-trap] `String(cString:)` traps the process on a
    // NULL pointer, and `sqlite3_column_text` legitimately returns NULL for a
    // SQL NULL. The `sessions` schema declares `id TEXT PRIMARY KEY` — and in
    // SQLite a non-INTEGER PRIMARY KEY does NOT imply NOT NULL (a long-standing
    // documented quirk), so a single row with a NULL id is enough to abort the
    // app. That is the 09:13:09 / 09:14:15 crash loop: `refreshSessionList` ran
    // on every launch, hit the same dirty row, and SIGABRT'd before the UI came
    // up, so the user could never reach a screen to fix it.
    //
    // `String(decoding:as:)` is used rather than `String(cString:)` because it
    // also cannot trap on malformed UTF-8 — it substitutes U+FFFD. A corrupted
    // byte sequence in a TEXT column therefore degrades to a visibly-mangled
    // title instead of taking the process down.

    /// Non-optional TEXT column → String. NULL / invalid UTF-8 degrade to a
    /// (possibly empty) String instead of trapping.
    private static func colText(_ stmt: OpaquePointer?, _ col: Int32) -> String {
        guard let ptr = sqlite3_column_text(stmt, col) else { return "" }
        let len = Int(sqlite3_column_bytes(stmt, col))
        guard len > 0 else { return "" }
        return String(decoding: UnsafeBufferPointer(start: ptr, count: len), as: UTF8.self)
    }

    /// Nullable TEXT column → String?. Distinguishes SQL NULL (nil) from an
    /// empty string, matching the previous `.map { String(cString: $0) }` shape.
    private static func colTextOpt(_ stmt: OpaquePointer?, _ col: Int32) -> String? {
        guard sqlite3_column_type(stmt, col) != SQLITE_NULL,
              let ptr = sqlite3_column_text(stmt, col) else { return nil }
        let len = Int(sqlite3_column_bytes(stmt, col))
        guard len > 0 else { return "" }
        return String(decoding: UnsafeBufferPointer(start: ptr, count: len), as: UTF8.self)
    }

    func listSessions() -> [ChatSession] {
        // ─── crash triage (sqlite3MutexMisuseAssert in column_text) ───
        // Capture the calling context whenever this enters so we can match
        // mid-flight invocations (App Intents EntityQuery, iCloud sync,
        // SwiftUI body) against the actor's serial expectation. A
        // mutex-misuse trap in sqlite3_column_text usually means the stmt
        // is being stepped on a different thread than it was prepared on,
        // OR the db handle is being touched while another op is mid-step.
        // Both should be impossible inside a single actor method, so any
        // weirdness in this log narrows down the breach.
        #if DEBUG
        let qLabel = String(cString: __dispatch_queue_get_label(nil))
        let tDesc = Thread.current.description
        let isMain = Thread.isMainThread
        iCloudLogger.info("[ChatStore.listSessions] ENTER queue='\(qLabel)' isMain=\(isMain) thread=\(tDesc.prefix(80))")
        defer {
            iCloudLogger.info("[ChatStore.listSessions] EXIT queue='\(qLabel)'")
        }
        #endif
        // [T-ios-listsessions-cache] Return the cached list verbatim when no
        // list-affecting mutation has fired since it was built — same array,
        // same preview Strings, zero new allocations. This is the fix for the
        // hundreds-of-MB StringStorage growth from redundant listSessions()
        // calls (proven via malloc_history).
        if !sessionListCacheDirty, let cached = sessionListCache {
            return cached
        }
        // Session-list preview policy:
        //   1. Latest assistant text part that came AFTER the latest user
        //      text message. This way an in-flight user prompt (or the
        //      streaming phase, where no assistant text has landed yet)
        //      shows the user's own prompt instead of the previous turn's
        //      assistant reply; once the current turn's assistant text
        //      falls out of streaming and hits the DB it takes over.
        //      Mid-turn text responses in multi-tool agent loops also land
        //      here, so the row updates per turn.
        //   2. Falls back to the latest user text message.
        //
        // tool_result rows share role='user' but their parts_json has no
        // `.text` part, so the LIKE filter excludes them from the user-text
        // subquery — the cutoff "later than latest user text" therefore
        // correctly ignores tool_result sort_order values.
        //
        // LIKE pattern note: we match only `"type":"text"` and NOT
        // `"type":"text","value":"…"`. Swift's JSONEncoder serializes
        // KeyedEncodingContainer entries in an unspecified (effectively
        // hash-bucket) order, so the actual JSON often comes out as
        // `{"value":"…","type":"text"}` — the keys are NOT guaranteed to be
        // in declaration order. A `"type":"text","value":"%` pattern would
        // silently miss most rows (which is how this regressed: only some
        // legacy rows happened to have type-first ordering and showed a
        // preview, while newer rows fell back to "No messages yet").
        // Assistant-side preview row: prefer the most recent assistant message
        // that has SOMETHING displayable — either a text part OR a tool_use part.
        // Without the `toolUse` clause, sessions whose last assistant turn is
        // mid-tool-call (no text yet) fall through to the user-text fallback,
        // which makes the row look stale; for brand-new sessions where the user
        // never typed text (e.g. forked / cloud-imported) it shows "No messages
        // yet" even while a tool is actively running. The extractor below knows
        // how to summarize a tool_use when no text part exists.
        // [T-ios-listsessions-part-flags] The filter is now the integer
        // `part_flags` bitmask instead of `parts_json LIKE '%…%'` scans:
        //     assistant preview row = latest with (text OR toolUse) → flags & 3
        //     user preview row      = latest with text              → flags & 1
        // This exactly reproduces the old LIKE conditions (backfilled +
        // maintained on every write), and crucially still excludes
        // tool_result-only user rows (they carry neither bit) — the invariant
        // the "later than latest user text" cutoff relied on.
        //
        // The 4-correlated-subquery SHAPE is kept deliberately: measured on the
        // real 100k-message / 1772-session DB, swapping LIKE→mask took it from
        // ~523ms to ~30-58ms (9×), each subquery a fast indexed
        // `SEARCH … USING idx_msg_sess_role_sort (session_id=? AND role=?)`.
        // A window-function CTE (方案1) was tried and REJECTED: it materializes
        // every assistant/user row across the whole table (MATERIALIZE asst),
        // clocking ~1100ms — 20× slower than the indexed subqueries here.
        let asstMask = Self.partFlagHasText | Self.partFlagHasToolUse   // 3
        let userMask = Self.partFlagHasText                            // 1
        let sql = """
            SELECT s.id, s.title, s.model_id, s.created_at, s.updated_at, s.category,
                   (SELECT m.parts_json FROM messages m
                     WHERE m.session_id = s.id
                       AND m.role = 'assistant'
                       AND (m.part_flags & \(asstMask)) != 0
                     ORDER BY m.sort_order DESC LIMIT 1),
                   (SELECT m.parts_json FROM messages m
                     WHERE m.session_id = s.id
                       AND m.role = 'user'
                       AND (m.part_flags & \(userMask)) != 0
                     ORDER BY m.sort_order DESC LIMIT 1),
                   s.source, s.last_synced_at,
                   s.remote_origin_device_id, s.pinned_at,
                   -- sort_order of the picked assistant/user rows, so the Swift
                   -- layer can prefer whichever is genuinely newer.
                   (SELECT m.sort_order FROM messages m
                     WHERE m.session_id = s.id
                       AND m.role = 'assistant'
                       AND (m.part_flags & \(asstMask)) != 0
                     ORDER BY m.sort_order DESC LIMIT 1),
                   (SELECT m.sort_order FROM messages m
                     WHERE m.session_id = s.id
                       AND m.role = 'user'
                       AND (m.part_flags & \(userMask)) != 0
                     ORDER BY m.sort_order DESC LIMIT 1),
                   -- Appended LAST on purpose: the decode below reads columns
                   -- by index, so a new column goes at the end to leave every
                   -- existing index untouched.
                   s.folder_id
            FROM sessions s ORDER BY s.updated_at DESC
            """
            // Note: `remote_tombstoned_at` column still exists on the
            // table for legacy rows but is no longer consulted. Peer-side
            // SessionV2 deletes are now applied as hard deletes (the row
            // and all its children leave SQLite outright) — see
            // ChatStore.deleteSessionLocalOnly + TombstoneManager.
        var stmt: OpaquePointer?
        var sessions: [ChatSession] = []
        // Count of unusable rows dropped this pass (NULL/empty primary key).
        // Logged once after the loop rather than per row so a systematically
        // corrupt table can't itself become a logging flood.
        var skippedRows = 0

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            while sqlite3_step(stmt) == SQLITE_ROW {
                // A row whose primary key is NULL/empty can't be opened, deleted
                // or synced by id — it is unusable rather than merely odd. Skip
                // it (loudly) instead of surfacing a session that breaks every
                // later lookup. This is the guard that breaks the crash loop.
                let id = Self.colText(stmt, 0)
                guard !id.isEmpty else {
                    skippedRows += 1
                    continue
                }
                let title = Self.colTextOpt(stmt, 1)
                let modelId = Self.colText(stmt, 2)
                let createdAt = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 3))
                let updatedAt = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 4))
                let category = Self.colTextOpt(stmt, 5)
                // Latest assistant text (preferred) → latest user text (fallback).
                let assistantRaw = Self.colTextOpt(stmt, 6)
                let userRaw = Self.colTextOpt(stmt, 7)
                let assistantText = assistantRaw.flatMap { extractTextFromPartsJSON($0) }
                let userText = userRaw.flatMap { extractTextFromPartsJSON($0) }
                // sort_order of each picked row (columns 12/13). Prefer whichever
                // is genuinely newer: assistant wins when its row is at least as
                // recent (covers mid-tool-call previews); the user message wins
                // only when the user just sent something the assistant hasn't
                // answered yet (assistant row is from an earlier turn). This
                // replaces the dropped SQL pivot clause and is robust to
                // sort_order re-ranking from iCloud merges.
                let asstSortOrder: Int? = sqlite3_column_type(stmt, 12) != SQLITE_NULL
                    ? Int(sqlite3_column_int64(stmt, 12)) : nil
                let userSortOrder: Int? = sqlite3_column_type(stmt, 13) != SQLITE_NULL
                    ? Int(sqlite3_column_int64(stmt, 13)) : nil
                let lastMessage: String? = {
                    switch (assistantText, userText) {
                    case let (a?, u?):
                        // Both present — newer sort_order wins; tie → assistant.
                        if let ao = asstSortOrder, let uo = userSortOrder, uo > ao { return u }
                        return a
                    case let (a?, nil): return a
                    case let (nil, u?): return u
                    default: return nil
                    }
                }()
                // [LastMsgDiag removed — T-ios-session-list-equatable-jank]
                // This per-session diagnostic logged one (or two) lines for
                // EVERY session on EVERY listSessions() call — ~2900 lines for a
                // single session-list rebuild on this user's device, ~73% of the
                // whole log. The NSLog volume + daily-file writes were themselves
                // a measurable Debug-build drag on the very session-switch path
                // being profiled, and drowned out every other signal. Dropped;
                // the last-message extractor logic above is unchanged.
                let source = Self.colTextOpt(stmt, 8)
                let lastSyncedAt: Date? = sqlite3_column_type(stmt, 9) != SQLITE_NULL
                    ? Date(timeIntervalSince1970: sqlite3_column_double(stmt, 9)) : nil
                let remoteDeviceId = Self.colTextOpt(stmt, 10)
                let pinnedAt: Date? = sqlite3_column_type(stmt, 11) != SQLITE_NULL
                    ? Date(timeIntervalSince1970: sqlite3_column_double(stmt, 11)) : nil
                let folderId = Self.colTextOpt(stmt, 14)

                sessions.append(ChatSession(
                    id: id, title: title, category: category, modelId: modelId,
                    createdAt: createdAt, updatedAt: updatedAt, lastMessage: lastMessage,
                    source: source, lastSyncedAt: lastSyncedAt,
                    remoteDeviceId: remoteDeviceId, pinnedAt: pinnedAt,
                    folderId: folderId
                ))
            }
        }
        sqlite3_finalize(stmt)

        if skippedRows > 0 {
            iCloudLogger.error("[ChatStore.listSessions] skipped \(skippedRows) unusable session row(s) with NULL/empty id — see T-ios-listsessions-cstring-trap")
        }

        // [T-ios-listsessions-cache] Store the freshly-built list; subsequent
        // calls return this same array until a mutation invalidates it.
        sessionListCache = sessions
        sessionListCacheDirty = false
        return sessions
    }

    /// Query sync_devices table and check UserDefaults to find which remote devices have sync enabled.
    private func enabledRemoteDeviceIdsFromDefaults() -> Set<String> {
        let selfDeviceId = syncZoneName.replacingOccurrences(of: "device-", with: "")
        var ids = Set<String>()
        let sql = "SELECT device_id FROM sync_devices WHERE device_id != ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (selfDeviceId as NSString).utf8String, -1, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                let deviceId = String(cString: sqlite3_column_text(stmt, 0))
                if UserDefaults.standard.bool(forKey: "cloudSync.device.\(deviceId).enabled") {
                    ids.insert(deviceId)
                }
            }
        }
        sqlite3_finalize(stmt)
        return ids
    }


    /// Ordered, pre-compiled markdown-stripping regexes for session previews.
    ///
    /// Previously these ran as a chain of `String.replacingOccurrences(of:…,
    /// options: .regularExpression)` calls. Each such call re-compiles the
    /// pattern *and* bridges `String → NSString` afresh; when `listSessions()`
    /// runs on a background cooperative queue over hundreds of messages whose
    /// text contains non-BMP characters (emoji like ⚡❌✅), Foundation could
    /// corrupt the temporary-NSString freelist and crash inside
    /// `NSString substringWithRange` / `_xzm_xzone_malloc`.
    ///
    /// Extract plain text preview from a parts_json string.
    /// [T-sessions-cli-truncate] Full-text extraction for minis-sessions-cli
    /// `messages` (loadMessagePage). Unlike extractTextFromPartsJSON (the
    /// 100-char single-part plain-text PREVIEW helper for the session list),
    /// this joins EVERY text part, keeps markdown intact, and applies no
    /// length cap — the caller enforces the documented 1000 / 50000 (--full)
    /// ceiling and sets the "truncated" flag. System-reminder and
    /// attachment-marker noise is still stripped; media-only and mid-tool
    /// messages keep the same placeholder fallbacks as the preview path.
    private func extractFullTextFromPartsJSON(_ json: String) -> String? {
        guard let data = json.data(using: .utf8),
              let parts = try? JSONDecoder().decode([ContentPart].self, from: data) else { return nil }
        var texts: [String] = []
        var hasMedia = false
        var toolUses: [ToolUse] = []
        var toolResults: [ToolResult] = []
        for part in parts {
            switch part {
            case .mediaRef:
                hasMedia = true
            case .toolUse(let tu):
                toolUses.append(tu)
            case .toolResult(let tr):
                toolResults.append(tr)
            case .text(var t) where !t.isEmpty:
                t = RawMessage.stripSystemReminders(t)
                if let start = t.range(of: "<user-attached-files>") {
                    let end = t.range(of: "</user-attached-files>")
                    let endBound = end?.upperBound ?? t.endIndex
                    t = String(t[t.startIndex..<start.lowerBound] + t[endBound...])
                }
                t = RawMessage.stripAttachmentMarkers(t)
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                guard !t.isEmpty else { continue }
                texts.append(t)
            default:
                continue
            }
        }
        if !texts.isEmpty { return texts.joined(separator: "\n\n") }
        if hasMedia { return "[Image]" }
        if !toolUses.isEmpty {
            return toolUses.map { summarizeToolUse($0) }.joined(separator: ", ")
        }
        if !toolResults.isEmpty {
            return toolResults.map { tr in
                let preview = tr.output.prefix(200)
                return "[Tool result: \(preview)]"
            }.joined(separator: "\n")
        }
        return nil
    }

    private func extractTextFromPartsJSON(_ json: String) -> String? {
        guard let data = json.data(using: .utf8),
              let parts = try? JSONDecoder().decode([ContentPart].self, from: data) else { return nil }
        var hasMedia = false
        var lastToolUse: ToolUse?
        var lastTextPreview: String?
        for part in parts {
            switch part {
            case .mediaRef:
                hasMedia = true
            case .toolUse(let tu):
                lastToolUse = tu
                continue
            case .text(var t) where !t.isEmpty:
                t = RawMessage.stripSystemReminders(t)
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                guard !t.isEmpty else { continue }
                if let start = t.range(of: "<user-attached-files>") {
                    let end = t.range(of: "</user-attached-files>")
                    let endBound = end?.upperBound ?? t.endIndex
                    t = String(t[t.startIndex..<start.lowerBound] + t[endBound...])
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                }
                t = RawMessage.stripAttachmentMarkers(t)
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                guard !t.isEmpty else { continue }
                let clean = MarkdownStripper.plainText(t)
                    .trimmingCharacters(in: .whitespaces)
                guard !clean.isEmpty else { continue }
                lastTextPreview = String(clean.prefix(100))
            default:
                continue
            }
        }
        // When tool_use parts follow text, prefer the tool summary — it
        // reflects the most recent activity (the text is the preamble the
        // model emitted before invoking tools).
        if let tu = lastToolUse { return summarizeToolUse(tu) }
        if let text = lastTextPreview { return text }
        if hasMedia { return "[Image]" }
        return nil
    }

    /// Build a short preview string for a tool_use block. Used by the session
    /// list when an assistant turn is mid-tool-call and has no text part yet.
    /// Strategy: prefer `input.tool_title` if the model included one, otherwise
    /// pick the most meaningful argument per known tool family, else fall back
    /// to the bare tool name. Output is capped at 100 chars to match the text
    /// preview ceiling.
    private func summarizeToolUse(_ tu: ToolUse) -> String {
        let input: [String: Any] = {
            guard let d = tu.input.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: d) as? [String: Any]
            else { return [:] }
            return obj
        }()

        func str(_ key: String) -> String? {
            guard let v = input[key] as? String else { return nil }
            let t = v.trimmingCharacters(in: .whitespacesAndNewlines)
            return t.isEmpty ? nil : t
        }
        func cap(_ s: String, _ n: Int = 100) -> String {
            return s.count > n ? String(s.prefix(n)) + "…" : s
        }

        if let title = str("tool_title") {
            return cap(title)
        }

        switch tu.name {
        case "shell_execute":
            if let cmd = str("command") { return cap("$ " + cmd) }
        case "file_read":
            if let p = str("path") { return cap("Reading " + p) }
        case "file_write":
            if let p = str("path") { return cap("Writing " + p) }
        case "file_edit":
            if let p = str("path") { return cap("Editing " + p) }
        case "read_image":
            if let p = str("path") { return cap("Reading image " + p) }
        case "browser_use":
            let action = str("action") ?? "browse"
            if let url = str("url") { return cap("\(action) \(url)") }
            return cap("browser_use \(action)")
        case "memory_write":
            if let content = str("content") { return cap("memory_write: " + content) }
        case "memory_get":
            if let kw = input["keywords"] as? [Any] {
                let joined = kw.compactMap { $0 as? String }.joined(separator: ", ")
                if !joined.isEmpty { return cap("memory_get: " + joined) }
            }
            if let kw = str("keywords") { return cap("memory_get: " + kw) }
        default:
            break
        }

        return cap("🔧 " + tu.name)
    }

    /// Search sessions by keyword across title and message text content.
    /// Returns matching sessions with a snippet of the first matching message (if matched in messages).
    struct SearchResult {
        let session: ChatSession
        /// The matched text snippet from messages, or nil if only title matched.
        let matchSnippet: String?
        /// Whether the title matched the query.
        let titleMatched: Bool
    }

    func searchSessions(query: String) -> [SearchResult] {
        guard !query.isEmpty else { return [] }
        let likePattern = "%\(query)%"
        // Find sessions where title matches OR any message text contains the query
        let sql = """
            SELECT DISTINCT s.id, s.title, s.model_id, s.created_at, s.updated_at, s.category,
                   (SELECT m2.parts_json FROM messages m2
                    WHERE m2.session_id = s.id AND m2.parts_json LIKE ?
                    ORDER BY m2.sort_order DESC LIMIT 1)
            FROM sessions s
            LEFT JOIN messages m ON m.session_id = s.id
            WHERE s.title LIKE ? OR m.parts_json LIKE ?
            GROUP BY s.id
            ORDER BY s.updated_at DESC
            """
        var stmt: OpaquePointer?
        var results: [SearchResult] = []

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (likePattern as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (likePattern as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 3, (likePattern as NSString).utf8String, -1, nil)

            let lowerQuery = query.lowercased()
            while sqlite3_step(stmt) == SQLITE_ROW {
                let id = String(cString: sqlite3_column_text(stmt, 0))
                let title = sqlite3_column_text(stmt, 1).map { String(cString: $0) }
                let modelId = String(cString: sqlite3_column_text(stmt, 2))
                let createdAt = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 3))
                let updatedAt = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 4))
                let category = sqlite3_column_text(stmt, 5).map { String(cString: $0) }
                let matchedPartsJSON = sqlite3_column_text(stmt, 6).map { String(cString: $0) }

                let titleMatched = title?.lowercased().contains(lowerQuery) == true
                let snippet = matchedPartsJSON.flatMap { extractTextFromPartsJSON($0) }

                let session = ChatSession(
                    id: id, title: title, category: category, modelId: modelId,
                    createdAt: createdAt, updatedAt: updatedAt, lastMessage: snippet
                )
                results.append(SearchResult(session: session, matchSnippet: snippet, titleMatched: titleMatched))
            }
        }
        sqlite3_finalize(stmt)
        return results
    }

    // MARK: - Sessions Get Tool

    /// Lightweight session metadata for the minis-sessions-cli offload.
    struct SessionMeta {
        let id: String
        let title: String?
        let preview: String?
        let source: String?
        let startedAt: Date
        let lastActive: Date
        let messageCount: Int
    }

    /// Query sessions with optional filtering by IDs and keywords, ordered by updatedAt DESC.
    func querySessionsMeta(sessionIds: [String]? = nil, keywords: [String]? = nil, limit: Int = 100, startDate: Date? = nil, endDate: Date? = nil) -> [SessionMeta] {
        var conditions: [String] = []
        var bindings: [(Int32, String)] = []
        var doubleBindings: [(Int32, Double)] = []
        var bindIndex: Int32 = 1

        // Filter by session IDs
        if let ids = sessionIds, !ids.isEmpty {
            let placeholders = ids.map { _ in "?" }.joined(separator: ",")
            conditions.append("s.id IN (\(placeholders))")
            for id in ids {
                bindings.append((bindIndex, id))
                bindIndex += 1
            }
        }

        // Filter by date range
        if let start = startDate {
            conditions.append("s.updated_at >= ?")
            doubleBindings.append((bindIndex, start.timeIntervalSince1970))
            bindIndex += 1
        }
        if let end = endDate {
            conditions.append("s.updated_at <= ?")
            doubleBindings.append((bindIndex, end.timeIntervalSince1970))
            bindIndex += 1
        }

        // Filter by keywords (AND: all keywords must match in title or message content)
        if let kws = keywords, !kws.isEmpty {
            for kw in kws {
                let pattern = "%\(kw)%"
                conditions.append("(s.title LIKE ? OR EXISTS (SELECT 1 FROM messages m WHERE m.session_id = s.id AND m.parts_json LIKE ?))")
                bindings.append((bindIndex, pattern))
                bindIndex += 1
                bindings.append((bindIndex, pattern))
                bindIndex += 1
            }
        }

        let whereClause = conditions.isEmpty ? "" : "WHERE " + conditions.joined(separator: " AND ")
        let sql = """
            SELECT s.id, s.title,
                   (SELECT m2.parts_json FROM messages m2
                    WHERE m2.session_id = s.id AND m2.role = 'user'
                    ORDER BY m2.sort_order ASC LIMIT 1) AS first_user_msg,
                   s.source, s.created_at, s.updated_at,
                   (SELECT COUNT(*) FROM messages m3 WHERE m3.session_id = s.id) AS msg_count
            FROM sessions s
            \(whereClause)
            ORDER BY s.updated_at DESC
            LIMIT ?
        """

        var stmt: OpaquePointer?
        var results: [SessionMeta] = []

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            for (idx, value) in bindings {
                sqlite3_bind_text(stmt, idx, (value as NSString).utf8String, -1, nil)
            }
            for (idx, value) in doubleBindings {
                sqlite3_bind_double(stmt, idx, value)
            }
            sqlite3_bind_int(stmt, bindIndex, Int32(limit))

            while sqlite3_step(stmt) == SQLITE_ROW {
                let id = String(cString: sqlite3_column_text(stmt, 0))
                let title = sqlite3_column_text(stmt, 1).map { String(cString: $0) }
                let firstUserPartsJSON = sqlite3_column_text(stmt, 2).map { String(cString: $0) }
                let source = sqlite3_column_text(stmt, 3).map { String(cString: $0) }
                let createdAt = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 4))
                let updatedAt = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 5))
                let msgCount = Int(sqlite3_column_int(stmt, 6))

                let preview = firstUserPartsJSON.flatMap { extractTextFromPartsJSON($0) }
                let previewTruncated = preview.map { String($0.prefix(60)) }

                results.append(SessionMeta(
                    id: id, title: title, preview: previewTruncated, source: source,
                    startedAt: createdAt, lastActive: updatedAt, messageCount: msgCount
                ))
            }
        }
        sqlite3_finalize(stmt)
        return results
    }

    /// Search messages across sessions, returning up to `limit` matching messages with keyword-context snippets (max 600 chars each).
    func searchMessages(sessionIds: [String]? = nil, keywords: [String], limit: Int = 50, startDate: Date? = nil, endDate: Date? = nil) -> [(sessionId: String, sessionTitle: String?, messageId: String, role: String, createdAt: Date, snippet: String)] {
        guard !keywords.isEmpty else { return [] }

        var conditions: [String] = ["m.parts_json LIKE ?"]
        var bindings: [(Int32, String)] = []
        var doubleBindings: [(Int32, Double)] = []
        var bindIndex: Int32 = 1

        // First keyword for initial filter
        bindings.append((bindIndex, "%\(keywords[0])%"))
        bindIndex += 1

        // Additional keyword conditions
        for kw in keywords.dropFirst() {
            conditions.append("m.parts_json LIKE ?")
            bindings.append((bindIndex, "%\(kw)%"))
            bindIndex += 1
        }

        if let ids = sessionIds, !ids.isEmpty {
            let placeholders = ids.map { _ in "?" }.joined(separator: ",")
            conditions.append("m.session_id IN (\(placeholders))")
            for id in ids {
                bindings.append((bindIndex, id))
                bindIndex += 1
            }
        }

        // Filter by date range
        if let start = startDate {
            conditions.append("m.created_at >= ?")
            doubleBindings.append((bindIndex, start.timeIntervalSince1970))
            bindIndex += 1
        }
        if let end = endDate {
            conditions.append("m.created_at <= ?")
            doubleBindings.append((bindIndex, end.timeIntervalSince1970))
            bindIndex += 1
        }

        let whereClause = conditions.joined(separator: " AND ")
        // [T-search-cli-title] LEFT JOIN sessions so each hit can report its
        // owning session's title to minis-sessions-cli — otherwise the CLI
        // user sees only session_id values and can't tell which conversation
        // a hit belongs to. LEFT JOIN (not INNER) so a stray message whose
        // session row is missing still surfaces with sessionTitle=nil rather
        // than vanishing from the result set.
        let sql = """
            SELECT m.session_id, s.title, m.id, m.role, m.created_at, m.parts_json
            FROM messages m
            LEFT JOIN sessions s ON s.id = m.session_id
            WHERE \(whereClause)
            ORDER BY m.created_at DESC
            LIMIT ?
        """

        var stmt: OpaquePointer?
        var results: [(sessionId: String, sessionTitle: String?, messageId: String, role: String, createdAt: Date, snippet: String)] = []

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            for (idx, value) in bindings {
                sqlite3_bind_text(stmt, idx, (value as NSString).utf8String, -1, nil)
            }
            for (idx, value) in doubleBindings {
                sqlite3_bind_double(stmt, idx, value)
            }
            // Over-fetch to compensate for rows filtered out due to empty text extraction
            sqlite3_bind_int(stmt, bindIndex, Int32(limit * 3))

            while sqlite3_step(stmt) == SQLITE_ROW {
                let sessionId = String(cString: sqlite3_column_text(stmt, 0))
                let sessionTitle: String? = {
                    guard sqlite3_column_type(stmt, 1) != SQLITE_NULL else { return nil }
                    let s = String(cString: sqlite3_column_text(stmt, 1))
                    return s.isEmpty ? nil : s
                }()
                let messageId = String(cString: sqlite3_column_text(stmt, 2))
                let role = String(cString: sqlite3_column_text(stmt, 3))
                let createdAt = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 4))
                let partsJSON = String(cString: sqlite3_column_text(stmt, 5))

                let fullText = extractTextFromPartsJSON(partsJSON) ?? ""
                // Skip messages where keyword matched in non-text parts (tool_use JSON, etc.)
                guard !fullText.isEmpty else { continue }
                let snippet = Self.keywordSnippet(from: fullText, keywords: keywords, maxLength: 600)
                guard !snippet.isEmpty else { continue }

                results.append((sessionId: sessionId, sessionTitle: sessionTitle, messageId: messageId, role: role, createdAt: createdAt, snippet: snippet))
                if results.count >= limit { break }
            }
        }
        sqlite3_finalize(stmt)
        return results
    }

    /// Extract a snippet around the first keyword match, up to maxLength characters.
    private static func keywordSnippet(from text: String, keywords: [String], maxLength: Int) -> String {
        guard !text.isEmpty else { return "" }
        let lower = text.lowercased()

        // Find earliest keyword match position
        var earliest = text.count
        for kw in keywords {
            if let range = lower.range(of: kw.lowercased()) {
                let pos = text.distance(from: text.startIndex, to: range.lowerBound)
                earliest = min(earliest, pos)
            }
        }

        if earliest == text.count {
            // No match found in extracted text, return head
            return String(text.prefix(maxLength))
        }

        // Center snippet around the match
        let halfWindow = maxLength / 2
        let start = max(0, earliest - halfWindow)
        let startIdx = text.index(text.startIndex, offsetBy: start)
        let endIdx = text.index(startIdx, offsetBy: min(maxLength, text.distance(from: startIdx, to: text.endIndex)))
        var snippet = String(text[startIdx..<endIdx])
        if start > 0 { snippet = "…" + snippet }
        if endIdx < text.endIndex { snippet += "…" }
        return snippet
    }

    /// Load a page of messages for a session, ordered by sort_order ASC.
    /// `maxChars` caps each message's text length; if truncated, `truncated`
    /// is true so callers can append a sentinel like "…[truncated]".
    /// [T-sessions-cli-messages-date-filter] (GH#200) `startDate`/`endDate` are
    /// an inclusive `created_at` range, mirroring `searchMessages` above (which
    /// already supported it — only this messages path did not, so the CLI's
    /// documented `--start/--end` silently returned the whole session).
    /// Both default to nil, so every existing caller keeps its behavior.
    func loadMessagePage(sessionId: String, offset: Int = 0, limit: Int = 20, maxChars: Int = 1000,
                         startDate: Date? = nil, endDate: Date? = nil) -> [(messageId: String, role: String, createdAt: Date, text: String, truncated: Bool)] {
        // Date predicates are appended BEFORE the LIMIT/OFFSET binds, so the
        // bind indices shift — hence the running `bindIndex` instead of the
        // previous hardcoded 1/2/3.
        var conditions = ["session_id = ?"]
        var doubleBindings: [(Int32, Double)] = []
        var bindIndex: Int32 = 2
        if let startDate {
            conditions.append("created_at >= ?")
            doubleBindings.append((bindIndex, startDate.timeIntervalSince1970))
            bindIndex += 1
        }
        if let endDate {
            conditions.append("created_at <= ?")
            doubleBindings.append((bindIndex, endDate.timeIntervalSince1970))
            bindIndex += 1
        }
        let sql = """
            SELECT id, role, created_at, parts_json
            FROM messages WHERE \(conditions.joined(separator: " AND "))
            ORDER BY sort_order ASC, created_at ASC
            LIMIT ? OFFSET ?
        """
        var stmt: OpaquePointer?
        var results: [(messageId: String, role: String, createdAt: Date, text: String, truncated: Bool)] = []

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
            for (idx, value) in doubleBindings {
                sqlite3_bind_double(stmt, idx, value)
            }
            sqlite3_bind_int(stmt, bindIndex, Int32(limit))
            sqlite3_bind_int(stmt, bindIndex + 1, Int32(offset))

            while sqlite3_step(stmt) == SQLITE_ROW {
                let messageId = String(cString: sqlite3_column_text(stmt, 0))
                let role = String(cString: sqlite3_column_text(stmt, 1))
                let createdAt = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 2))
                let partsJSON = String(cString: sqlite3_column_text(stmt, 3))

                // [T-sessions-cli-truncate] Must NOT go through
                // extractTextFromPartsJSON — that's the session-LIST preview
                // helper: it hard-caps at 100 chars, keeps only the FIRST text
                // part, and strips markdown, so the CLI's documented 1000 /
                // 50000 (--full) caps never applied and every exported message
                // came out as a 100-char preview.
                let fullText = extractFullTextFromPartsJSON(partsJSON) ?? ""
                guard !fullText.isEmpty else { continue }
                let truncated = fullText.count > maxChars
                let text = truncated ? String(fullText.prefix(maxChars)) : fullText

                results.append((messageId: messageId, role: role, createdAt: createdAt, text: text, truncated: truncated))
            }
        }
        sqlite3_finalize(stmt)
        return results
    }

    /// Get total message count for a session.
    ///
    /// [T-sessions-cli-messages-date-filter] (GH#200) Takes the same optional
    /// range as `loadMessagePage` so a filtered query reports the count WITHIN
    /// the range. Without this the CLI would page against a `total` describing
    /// the whole session — the caller would think there were more pages of
    /// in-range messages than exist. Both default to nil (unfiltered).
    func messageCount(sessionId: String, startDate: Date? = nil, endDate: Date? = nil) -> Int {
        var conditions = ["session_id = ?"]
        var doubleBindings: [(Int32, Double)] = []
        var bindIndex: Int32 = 2
        if let startDate {
            conditions.append("created_at >= ?")
            doubleBindings.append((bindIndex, startDate.timeIntervalSince1970))
            bindIndex += 1
        }
        if let endDate {
            conditions.append("created_at <= ?")
            doubleBindings.append((bindIndex, endDate.timeIntervalSince1970))
            bindIndex += 1
        }
        let sql = "SELECT COUNT(*) FROM messages WHERE \(conditions.joined(separator: " AND "))"
        var stmt: OpaquePointer?
        var count = 0
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
            for (idx, value) in doubleBindings {
                sqlite3_bind_double(stmt, idx, value)
            }
            if sqlite3_step(stmt) == SQLITE_ROW {
                count = Int(sqlite3_column_int(stmt, 0))
            }
        }
        sqlite3_finalize(stmt)
        return count
    }

    func getSession(_ id: String) -> ChatSession? {
        let sql = "SELECT id, title, model_id, created_at, updated_at, category, source, pinned_at, folder_id FROM sessions WHERE id = ?"
        var stmt: OpaquePointer?
        var session: ChatSession?

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW {
                let title = sqlite3_column_text(stmt, 1).map { String(cString: $0) }
                let modelId = String(cString: sqlite3_column_text(stmt, 2))
                let createdAt = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 3))
                let updatedAt = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 4))
                let category = sqlite3_column_text(stmt, 5).map { String(cString: $0) }
                let source = sqlite3_column_text(stmt, 6).map { String(cString: $0) }
                let pinnedAt: Date? = sqlite3_column_type(stmt, 7) != SQLITE_NULL
                    ? Date(timeIntervalSince1970: sqlite3_column_double(stmt, 7)) : nil
                let folderId = sqlite3_column_text(stmt, 8).map { String(cString: $0) }

                session = ChatSession(
                    id: id, title: title, category: category, modelId: modelId,
                    createdAt: createdAt, updatedAt: updatedAt, source: source,
                    pinnedAt: pinnedAt, folderId: folderId
                )
            }
        }
        sqlite3_finalize(stmt)

        return session
    }

    func updateSessionModelId(_ id: String, modelId: String) {
        invalidateSessionListCache()
        let sql = "UPDATE sessions SET model_id = ? WHERE id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (modelId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (id as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    func updateSessionTitle(_ id: String, title: String, category: String? = nil) {
        invalidateSessionListCache()
        let sql = "UPDATE sessions SET title = ?, category = COALESCE(?, category), updated_at = ? WHERE id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (title as NSString).utf8String, -1, nil)
            bindOptionalText(stmt, index: 2, value: category)
            sqlite3_bind_double(stmt, 3, Date().timeIntervalSince1970)
            sqlite3_bind_text(stmt, 4, (id as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
        markDirty(recordType: "Session", recordId: id)
        NotificationCenter.default.post(name: .sessionDidUpdate, object: id)
    }

    /// Toggle pin state for a session. Returns the new pinned state.
    @discardableResult
    func toggleSessionPin(_ id: String) -> Bool {
        invalidateSessionListCache()
        // Check current pin state
        var isPinned = false
        let checkSql = "SELECT pinned_at FROM sessions WHERE id = ?"
        var checkStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, checkSql, -1, &checkStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(checkStmt, 1, (id as NSString).utf8String, -1, nil)
            if sqlite3_step(checkStmt) == SQLITE_ROW {
                isPinned = sqlite3_column_type(checkStmt, 0) != SQLITE_NULL
            }
        }
        sqlite3_finalize(checkStmt)

        let newPinned = !isPinned
        let sql = "UPDATE sessions SET pinned_at = ? WHERE id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            if newPinned {
                sqlite3_bind_double(stmt, 1, Date().timeIntervalSince1970)
            } else {
                sqlite3_bind_null(stmt, 1)
            }
            sqlite3_bind_text(stmt, 2, (id as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
        markDirty(recordType: "Session", recordId: id)
        return newPinned
    }

    // MARK: - Folders

    /// All folders, most recently updated first.
    func listFolders() -> [ChatFolder] {
        let sql = """
            SELECT id, name, icon, color, origin, sort_index, created_at, updated_at, pinned_at, description
            FROM folders ORDER BY updated_at DESC
            """
        var stmt: OpaquePointer?
        var folders: [ChatFolder] = []
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            while sqlite3_step(stmt) == SQLITE_ROW {
                let id = Self.colText(stmt, 0)
                // Same guard as listSessions: a row with no primary key can't be
                // opened, moved into, or synced by id — skip rather than surface.
                guard !id.isEmpty else { continue }
                let pinnedAt: Date? = sqlite3_column_type(stmt, 8) != SQLITE_NULL
                    ? Date(timeIntervalSince1970: sqlite3_column_double(stmt, 8)) : nil
                folders.append(ChatFolder(
                    id: id,
                    name: Self.colText(stmt, 1),
                    icon: Self.colTextOpt(stmt, 2),
                    color: Self.colTextOpt(stmt, 3),
                    origin: Self.colTextOpt(stmt, 4) ?? "manual",
                    sortIndex: Int(sqlite3_column_int64(stmt, 5)),
                    pinnedAt: pinnedAt,
                    desc: Self.colTextOpt(stmt, 9),
                    createdAt: Date(timeIntervalSince1970: sqlite3_column_double(stmt, 6)),
                    updatedAt: Date(timeIntervalSince1970: sqlite3_column_double(stmt, 7))
                ))
            }
        }
        sqlite3_finalize(stmt)
        return folders
    }

    /// Create a folder and return it. `id` is a fresh UUID. `desc` is the
    /// one-sentence auto-grouping context, capped at 100 characters.
    @discardableResult
    func createFolder(name: String, icon: String? = nil, color: String? = nil, origin: String = "manual", desc: String? = nil) -> ChatFolder {
        let cappedDesc = desc.flatMap { d -> String? in
            let t = d.trimmingCharacters(in: .whitespacesAndNewlines)
            return t.isEmpty ? nil : String(t.prefix(100))
        }
        let folder = ChatFolder(name: name, icon: icon, color: color, origin: origin, desc: cappedDesc)
        let sql = """
            INSERT INTO folders (id, name, icon, color, origin, sort_index, description, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (folder.id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (folder.name as NSString).utf8String, -1, nil)
            if let icon = folder.icon {
                sqlite3_bind_text(stmt, 3, (icon as NSString).utf8String, -1, nil)
            } else {
                sqlite3_bind_null(stmt, 3)
            }
            if let color = folder.color {
                sqlite3_bind_text(stmt, 4, (color as NSString).utf8String, -1, nil)
            } else {
                sqlite3_bind_null(stmt, 4)
            }
            sqlite3_bind_text(stmt, 5, (folder.origin as NSString).utf8String, -1, nil)
            sqlite3_bind_int64(stmt, 6, Int64(folder.sortIndex))
            bindOptionalText(stmt, index: 7, value: folder.desc)
            sqlite3_bind_double(stmt, 8, folder.createdAt.timeIntervalSince1970)
            sqlite3_bind_double(stmt, 9, folder.updatedAt.timeIntervalSince1970)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
        markDirty(recordType: "Folder", recordId: folder.id)
        return folder
    }

    /// Rename a folder and (optionally) update its description. Field edits
    /// only — the UUID key is what keeps members untouched and lets the
    /// change merge as LWW fields rather than delete+create+migrate across
    /// devices. `desc: nil` leaves the stored description alone; an empty
    /// string clears it.
    func renameFolder(_ id: String, name: String, desc: String? = nil) {
        let cappedDesc = desc.map { String($0.trimmingCharacters(in: .whitespacesAndNewlines).prefix(100)) }
        let sql = cappedDesc != nil
            ? "UPDATE folders SET name = ?, description = ?, updated_at = ? WHERE id = ?"
            : "UPDATE folders SET name = ?, updated_at = ? WHERE id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (name as NSString).utf8String, -1, nil)
            if let d = cappedDesc {
                if d.isEmpty {
                    sqlite3_bind_null(stmt, 2)
                } else {
                    sqlite3_bind_text(stmt, 2, (d as NSString).utf8String, -1, nil)
                }
                sqlite3_bind_double(stmt, 3, Date().timeIntervalSince1970)
                sqlite3_bind_text(stmt, 4, (id as NSString).utf8String, -1, nil)
            } else {
                sqlite3_bind_double(stmt, 2, Date().timeIntervalSince1970)
                sqlite3_bind_text(stmt, 3, (id as NSString).utf8String, -1, nil)
            }
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
        markDirty(recordType: "Folder", recordId: id)
    }

    /// Toggle a folder's pin. Mirrors toggleSessionPin; also bumps
    /// updated_at so the change rides FolderV2's LWW cleanly.
    @discardableResult
    func toggleFolderPin(_ id: String) -> Bool {
        invalidateSessionListCache()
        var isPinned = false
        var checkStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, "SELECT pinned_at FROM folders WHERE id = ?", -1, &checkStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(checkStmt, 1, (id as NSString).utf8String, -1, nil)
            if sqlite3_step(checkStmt) == SQLITE_ROW {
                isPinned = sqlite3_column_type(checkStmt, 0) != SQLITE_NULL
            }
        }
        sqlite3_finalize(checkStmt)

        let newPinned = !isPinned
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, "UPDATE folders SET pinned_at = ?, updated_at = ? WHERE id = ?", -1, &stmt, nil) == SQLITE_OK {
            if newPinned {
                sqlite3_bind_double(stmt, 1, Date().timeIntervalSince1970)
            } else {
                sqlite3_bind_null(stmt, 1)
            }
            sqlite3_bind_double(stmt, 2, Date().timeIntervalSince1970)
            sqlite3_bind_text(stmt, 3, (id as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
        markDirty(recordType: "Folder", recordId: id)
        return newPinned
    }

    /// Dissolve a folder: delete the folder row and clear its members'
    /// `folder_id`. **No session is deleted.** Returns the ids of the sessions
    /// that were moved back to ungrouped.
    ///
    /// The same clearing runs when a peer's FolderV2 tombstone arrives, so
    /// "folder deleted" always means "members become ungrouped", never
    /// "members are deleted".
    @discardableResult
    func dissolveFolder(_ id: String) -> [String] {
        invalidateSessionListCache()
        let memberIds = sessionIdsInFolder(id)

        var stmt: OpaquePointer?
        let clearSql = "UPDATE sessions SET folder_id = NULL WHERE folder_id = ?"
        if sqlite3_prepare_v2(db, clearSql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)

        stmt = nil
        let deleteSql = "DELETE FROM folders WHERE id = ?"
        if sqlite3_prepare_v2(db, deleteSql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)

        // Each freed session needs its own push — folder_id lives on SessionV2.
        for sid in memberIds {
            markDirty(recordType: "Session", recordId: sid)
        }
        markDirty(recordType: "Folder", recordId: id, operation: "delete")
        return memberIds
    }

    /// Session ids belonging to `folderId`, most recently updated first.
    func sessionIdsInFolder(_ folderId: String) -> [String] {
        let sql = "SELECT id FROM sessions WHERE folder_id = ? ORDER BY updated_at DESC"
        var stmt: OpaquePointer?
        var ids: [String] = []
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (folderId as NSString).utf8String, -1, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                let id = Self.colText(stmt, 0)
                if !id.isEmpty { ids.append(id) }
            }
        }
        sqlite3_finalize(stmt)
        return ids
    }

    /// Move sessions into `folderId`, or out of any folder when it is nil.
    func setFolder(_ folderId: String?, forSessions sessionIds: [String]) {
        guard !sessionIds.isEmpty else { return }
        invalidateSessionListCache()
        let sql = "UPDATE sessions SET folder_id = ? WHERE id = ?"
        for sid in sessionIds {
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                if let folderId {
                    sqlite3_bind_text(stmt, 1, (folderId as NSString).utf8String, -1, nil)
                } else {
                    sqlite3_bind_null(stmt, 1)
                }
                sqlite3_bind_text(stmt, 2, (sid as NSString).utf8String, -1, nil)
                sqlite3_step(stmt)
            }
            sqlite3_finalize(stmt)
            markDirty(recordType: "Session", recordId: sid)
        }
    }

    /// Set a session's folder only if it is currently ungrouped. Returns true
    /// if the write happened.
    ///
    /// This is the automatic-grouping write path: a user who has already filed
    /// a session by hand must never have that overridden by the sub-model's
    /// guess, and the check has to be part of the same statement rather than a
    /// read-then-write in the caller.
    @discardableResult
    func setFolderIfUnfiled(_ folderId: String, forSession sessionId: String) -> Bool {
        invalidateSessionListCache()
        let sql = "UPDATE sessions SET folder_id = ? WHERE id = ? AND folder_id IS NULL"
        var changed = false
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (folderId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (sessionId as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
            changed = sqlite3_changes(db) > 0
        }
        sqlite3_finalize(stmt)
        if changed {
            markDirty(recordType: "Session", recordId: sessionId)
        }
        return changed
    }

    /// Resolve a folder *name* to a folder, for the sub-model paths that emit
    /// names rather than ids.
    ///
    /// Matching is trimmed and case-insensitive so a model answering "work"
    /// still finds "Work". Because duplicate names are allowed by design, this
    /// can match several folders; the most recently *active* one wins — active
    /// meaning the folder with the newest member session, NOT the newest
    /// `folders.updated_at` (which only tracks renames, so a long-abandoned
    /// folder renamed yesterday would otherwise beat one in daily use).
    func findFolderByName(_ name: String) -> ChatFolder? {
        let needle = name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !needle.isEmpty else { return nil }
        let matches = listFolders().filter {
            $0.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == needle
        }
        guard matches.count > 1 else { return matches.first }

        let activity = folderLastActivity()
        return matches.max { a, b in
            let aKey = activity[a.id]
            let bKey = activity[b.id]
            switch (aKey, bKey) {
            case let (x?, y?): return x < y
            // A folder with any member outranks an empty one.
            case (nil, _?): return true
            case (_?, nil): return false
            // Both empty — fall back to the older folder, which is more likely
            // to be the one the user thinks of as "the" folder by that name.
            case (nil, nil): return a.createdAt > b.createdAt
            }
        }
    }

    /// folderId → newest member `updated_at`. Folders with no members are absent.
    func folderLastActivity() -> [String: Date] {
        let sql = """
            SELECT folder_id, MAX(updated_at) FROM sessions
            WHERE folder_id IS NOT NULL GROUP BY folder_id
            """
        var stmt: OpaquePointer?
        var out: [String: Date] = [:]
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            while sqlite3_step(stmt) == SQLITE_ROW {
                let fid = Self.colText(stmt, 0)
                guard !fid.isEmpty else { continue }
                out[fid] = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 1))
            }
        }
        sqlite3_finalize(stmt)
        return out
    }

    /// Apply an inbound FolderV2 upsert: LWW by updatedAt against the local
    /// row; missing locally → insert. Local-only — never marks dirty (the
    /// cloud already holds this state; re-queueing would ping-pong).
    func applyRemoteFolder(_ f: ChatFolder) {
        var localUpdatedAt: Double?
        var checkStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, "SELECT updated_at FROM folders WHERE id = ?", -1, &checkStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(checkStmt, 1, (f.id as NSString).utf8String, -1, nil)
            if sqlite3_step(checkStmt) == SQLITE_ROW {
                localUpdatedAt = sqlite3_column_double(checkStmt, 0)
            }
        }
        sqlite3_finalize(checkStmt)

        if let localTs = localUpdatedAt, localTs >= f.updatedAt.timeIntervalSince1970 {
            iCloudLogger.info("[iCloud] applyRemoteFolder SKIP (local newer/equal): id=\(f.id.prefix(8))")
            return
        }
        invalidateSessionListCache()
        let sql = """
            INSERT INTO folders (id, name, icon, color, origin, sort_index, pinned_at, description, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name, icon = excluded.icon, color = excluded.color,
                origin = excluded.origin, sort_index = excluded.sort_index,
                pinned_at = excluded.pinned_at,
                description = excluded.description,
                updated_at = excluded.updated_at
            """
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (f.id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (f.name as NSString).utf8String, -1, nil)
            bindOptionalText(stmt, index: 3, value: f.icon)
            bindOptionalText(stmt, index: 4, value: f.color)
            sqlite3_bind_text(stmt, 5, (f.origin as NSString).utf8String, -1, nil)
            sqlite3_bind_int64(stmt, 6, Int64(f.sortIndex))
            if let pinTs = f.pinnedAt {
                sqlite3_bind_double(stmt, 7, pinTs.timeIntervalSince1970)
            } else {
                sqlite3_bind_null(stmt, 7)
            }
            bindOptionalText(stmt, index: 8, value: f.desc)
            sqlite3_bind_double(stmt, 9, f.createdAt.timeIntervalSince1970)
            sqlite3_bind_double(stmt, 10, f.updatedAt.timeIntervalSince1970)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
        iCloudLogger.info("[iCloud] applyRemoteFolder APPLIED: id=\(f.id.prefix(8)) name=\(f.name)")
    }

    /// Apply an inbound FolderV2 tombstone: clear members' folder_id and drop
    /// the folder row. **Never deletes a session** — folder deletion means
    /// "members become ungrouped" on every device. Local-only (no markDirty):
    /// the deleting peer already pushed both its tombstone and its members'
    /// folder_id=NULL session updates; re-queueing here would delete-loop.
    func applyRemoteFolderDeletion(id: String) {
        invalidateSessionListCache()
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, "UPDATE sessions SET folder_id = NULL WHERE folder_id = ?", -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
        stmt = nil
        if sqlite3_prepare_v2(db, "DELETE FROM folders WHERE id = ?", -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
        // Match dissolveFolder's local behavior: leave a tombstone so a
        // fetchRecentV2 race can't resurrect the folder record we just dropped.
        recordDeletedRecordTombstone(type: "Folder", id: id)
        iCloudLogger.info("[iCloud] applyRemoteFolderDeletion: id=\(id.prefix(8))")
    }

    /// Fetch one folder (sync builder path).
    func getFolder(_ id: String) -> ChatFolder? {
        listFolders().first(where: { $0.id == id })
    }

    func getMemoryEnabled(sessionId: String) -> Bool {
        // [T-memory-global-toggle-settings-ui-ios] If the session row
        // already exists, return its stored per-session value. If no
        // row exists (genuinely new session — loadSession ran before
        // the session was persisted), fall back to the global default
        // from UserDefaults("memory.global.enabled"), defaulting to
        // true so users upgrading without the key set see no change.
        // Already-existing sessions keep their stored value when the
        // global toggle changes — only NEW sessions inherit it.
        let sql = "SELECT memory_enabled FROM sessions WHERE id = ?"
        var stmt: OpaquePointer?
        var rowFound = false
        var enabled = true
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW {
                enabled = sqlite3_column_int(stmt, 0) != 0
                rowFound = true
            }
        }
        sqlite3_finalize(stmt)
        // [T-memory-enabled-new-session-bug DIAG]
        let rawObj = UserDefaults.standard.object(forKey: "memory.global.enabled")
        if rowFound {
            memDiagLogger.info("[MemDiag] getMemoryEnabled sid=\(sessionId.prefix(8)) rowFound=true storedRow=\(enabled) (returning storedRow) globalRaw=\(String(describing: rawObj))")
            return enabled
        }
        // No row — apply global default. UserDefaults.bool returns false
        // for unset keys, so probe presence via object(forKey:) first
        // and treat absent as true (matches @AppStorage default).
        if let stored = UserDefaults.standard.object(forKey: "memory.global.enabled") as? Bool {
            memDiagLogger.info("[MemDiag] getMemoryEnabled sid=\(sessionId.prefix(8)) rowFound=false → global=\(stored)")
            return stored
        }
        memDiagLogger.info("[MemDiag] getMemoryEnabled sid=\(sessionId.prefix(8)) rowFound=false global unset → true")
        return true
    }

    func setMemoryEnabled(sessionId: String, enabled: Bool) {
        let sql = "UPDATE sessions SET memory_enabled = ? WHERE id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_int(stmt, 1, enabled ? 1 : 0)
            sqlite3_bind_text(stmt, 2, (sessionId as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    func deleteSession(_ id: String) {
        invalidateSessionListCache()
        // Queue cloud deletions BEFORE removing local rows so the SELECTs
        // below can still enumerate child ids. Peers receive op=delete
        // for the session + each child and apply them as hard deletes
        // (see TombstoneManager.applyRemoteSessionDeletion).
        markSessionForCloudDeletion(id)

        deleteSessionLocalRowsOnly(id)

        // [T-icloud-deleted-session-resurrection] Record a recent-delete
        // tombstone so an inbound merge (e.g. fetchRecentV2 pulling the still-
        // present cloud record before our delete tombstone lands) refuses to
        // re-insert this session / its messages / its files. Pruned by the
        // 30-day TTL on launch (ids are UUIDs, so it never blocks a real
        // future session).
        recordDeletedSessionTombstone(id)
    }

    /// Hard-delete a session and its children locally without queuing any
    /// cloud delete rows. Used by the inbound side: when a peer device
    /// pushes a SessionV2 op=delete, we apply it locally and STOP — the
    /// cloud already has the deletion, re-queueing it would just create a
    /// noisy round-trip and risk a delete-loop with peers that haven't
    /// yet drained the same tombstone.
    func deleteSessionLocalOnly(_ id: String) {
        deleteSessionLocalRowsOnly(id)
    }

    /// True if a session row exists locally. Lightweight existence check
    /// for inbound delete handlers — they need to know whether to log a
    /// real deletion or a no-op.
    func sessionExists(id: String) -> Bool {
        let sql = "SELECT 1 FROM sessions WHERE id = ? LIMIT 1"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return false }
        sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
        return sqlite3_step(stmt) == SQLITE_ROW
    }

    private func deleteSessionLocalRowsOnly(_ id: String) {
        // [T-ios-listsessions-cache] Invalidate here (the low-level row delete)
        // so ALL callers are covered — deleteSession AND deleteSessionLocalOnly.
        invalidateSessionListCache()
        deleteMessages(sessionId: id)
        deleteCompactMarkers(sessionId: id)
        deleteSessionMedia(id)

        let sql = "DELETE FROM sessions WHERE id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
        // Clean up per-session etag cache
        if #available(iOS 17.0, *) {
            Task { @MainActor in CloudSyncEngine.shared.removeSessionCache(id) }
        }
        // [T-ios-session-paused-badge] Drop any badge state for a deleted session
        // so a stale "paused" badge can't linger in the persisted store.
        Task { @MainActor in SessionBadgeStore.shared.clearAll(for: id) }
        // [T-ios-delete-session-live-activity] All delete funnels (swipe /
        // long-press / batch / RPC / inbound iCloud delete) converge here, so
        // this is the single place that guarantees a deleted conversation never
        // stays visible in the Dynamic Island / Lock Screen. Order matters:
        // setInactive first so the tracker (and any snapshot rebuild) no longer
        // contains the session, then let the Live Activity manager end or trim
        // the on-screen activity.
        Task { @MainActor in
            SessionActivityTracker.shared.setInactive(id, source: "sessionDeleted")
            AgentLiveActivityManager.shared.handleSessionDeleted(id)
        }
    }

    /// Mark a session and all its child records (messages, compact markers) as
    /// pending iCloud deletion. Called from deleteSession before the local rows
    /// disappear so we can still enumerate the ids.
    private func markSessionForCloudDeletion(_ sessionId: String) {
        guard !syncZoneName.isEmpty else { return }
        // Session itself
        markDirty(recordType: "Session", recordId: sessionId, operation: "delete")
        // Messages
        let msgSql = "SELECT id FROM messages WHERE session_id = ?"
        var msgStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, msgSql, -1, &msgStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(msgStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            while sqlite3_step(msgStmt) == SQLITE_ROW {
                let msgId = String(cString: sqlite3_column_text(msgStmt, 0))
                markDirty(recordType: "Message", recordId: msgId, operation: "delete")
            }
        }
        sqlite3_finalize(msgStmt)
        // Compact markers
        let cmSql = "SELECT id FROM compact_markers WHERE session_id = ?"
        var cmStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, cmSql, -1, &cmStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(cmStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            while sqlite3_step(cmStmt) == SQLITE_ROW {
                let cmId = String(cString: sqlite3_column_text(cmStmt, 0))
                markDirty(recordType: "CompactMarker", recordId: cmId, operation: "delete")
            }
        }
        sqlite3_finalize(cmStmt)
        // SessionFile records — walk the workspace and queue a delete for
        // every file that lives on iCloud. MUST run before
        // deleteSessionMedia removes the local files. recordIds are
        // "<sessionId>:<rel>" matching appendMediaFile +
        // scanAndMarkSessionFilesForResurrect.
        let sessionDir = minisBaseURL.appendingPathComponent(sessionId, isDirectory: true)
        var fileDeleteCount = 0
        if let enumerator = FileManager.default.enumerator(
            at: sessionDir,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) {
            for case let url as URL in enumerator {
                guard let isFile = (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile,
                      isFile else { continue }
                let rel = url.path.replacingOccurrences(of: sessionDir.path + "/", with: "")
                markDirty(recordType: "SessionFile", recordId: "\(sessionId):\(rel)", operation: "delete")
                fileDeleteCount += 1
            }
        }
        iCloudLogger.info("[iCloud] deleteSession \(sessionId.prefix(8)) → cloud delete queued: \(fileDeleteCount) SessionFile records (in addition to Session/Message/CompactMarker tombstones)")
    }

    /// Public entry: queue a SessionFile cloud delete for one file under
    /// a session's workspace. Used by FileBrowserView.deleteItem when the
    /// user removes a file inside `Library/MinisChat/minis/<sid>/...`.
    /// `relPath` must be "<subdir>/<rest>" matching the recordId scheme.
    func markSessionFileForCloudDeletion(sessionId: String, relPath: String) {
        guard !syncZoneName.isEmpty, !sessionId.isEmpty, !relPath.isEmpty else { return }
        markDirty(recordType: "SessionFile",
                  recordId: "\(sessionId):\(relPath)",
                  operation: "delete")
    }

    // MARK: - Message CRUD

    func appendMessage(_ message: RawMessage) {
        appendMessages([message])
    }

    /// Batch-insert multiple messages in a single SQLite transaction.
    /// This reduces disk I/O by coalescing multiple writes into one fsync.
    func appendMessages(_ messages: [RawMessage]) {
        invalidateSessionListCache()
        guard !messages.isEmpty else { return }
        // Drop assistant messages that carry no content the model could echo back —
        // a stream that aborts before producing any text or tool_use leaves an
        // assistant row with empty parts. On the next turn DeepSeek/OpenAI-compat
        // servers reject that history with `400 content or tool_calls must be set`.
        let messages = messages.filter { msg in
            guard msg.role == .assistant else { return true }
            return msg.parts.contains { part in
                switch part {
                case .text(let s): return !s.isEmpty
                case .toolUse, .toolResult, .mediaRef: return true
                }
            }
        }
        guard !messages.isEmpty else {
            logger.info("[Store] appendMessages: all messages dropped after empty-assistant filter")
            return
        }
        let dbOK = (db != nil)
        let firstSid = messages.first?.sessionId.prefix(8) ?? "?"
        logger.info("[Store] appendMessages enter count=\(messages.count) dbOpen=\(dbOK) sid=\(firstSid)")

        let sql = """
            INSERT INTO messages (id, session_id, role, parts_json, created_at, token_usage, sort_order, reasoning_content, stream_interrupt_count, updated_at, error_info, part_flags, model_id, model_display_name, provider_type, provider_instance_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        exec("BEGIN TRANSACTION")

        var touchedSessions = Set<String>()
        for message in messages {
            let sortOrder = nextSortOrder(sessionId: message.sessionId)

            let partsJSON: String
            do {
                let data = try JSONEncoder().encode(message.parts)
                partsJSON = String(data: data, encoding: .utf8) ?? "[]"
            } catch {
                logger.error("Failed to encode message parts: \(error)")
                continue
            }
            // [T-ios-listsessions-part-flags] derived from the parts we just encoded.
            let partFlags = Self.partFlags(for: message.parts)

            let usageJSON: String?
            if let usage = message.tokenUsage {
                usageJSON = (try? JSONEncoder().encode(usage)).flatMap { String(data: $0, encoding: .utf8) }
            } else {
                usageJSON = nil
            }

            var stmt: OpaquePointer?
            let prepRC = sqlite3_prepare_v2(db, sql, -1, &stmt, nil)
            if prepRC == SQLITE_OK {
                sqlite3_bind_text(stmt, 1, (message.id as NSString).utf8String, -1, nil)
                sqlite3_bind_text(stmt, 2, (message.sessionId as NSString).utf8String, -1, nil)
                sqlite3_bind_text(stmt, 3, (message.role.rawValue as NSString).utf8String, -1, nil)
                sqlite3_bind_text(stmt, 4, (partsJSON as NSString).utf8String, -1, nil)
                sqlite3_bind_double(stmt, 5, message.createdAt.timeIntervalSince1970)
                bindOptionalText(stmt, index: 6, value: usageJSON)
                sqlite3_bind_int64(stmt, 7, Int64(sortOrder))
                bindOptionalText(stmt, index: 8, value: message.reasoningContent)
                sqlite3_bind_int64(stmt, 9, Int64(message.streamInterruptCount))
                sqlite3_bind_double(stmt, 10, message.createdAt.timeIntervalSince1970)
                bindOptionalText(stmt, index: 11, value: message.errorInfo)  // [T-error-persist-ios]
                sqlite3_bind_int64(stmt, 12, Int64(partFlags))  // [T-ios-listsessions-part-flags]
                // [T-token-attribution-snapshot] Written from the message the
                // caller built, which carries the model that actually served
                // the turn. Never re-read from the session here — failover can
                // already have repointed it.
                bindOptionalText(stmt, index: 13, value: message.modelId)
                bindOptionalText(stmt, index: 14, value: message.modelDisplayName)
                bindOptionalText(stmt, index: 15, value: message.providerType)
                bindOptionalText(stmt, index: 16, value: message.providerInstanceId)
                let stepRC = sqlite3_step(stmt)
                if stepRC != SQLITE_DONE {
                    let errMsg = String(cString: sqlite3_errmsg(db))
                    logger.error("[Store] INSERT step failed rc=\(stepRC) sid=\(message.sessionId.prefix(8)) mid=\(message.id.prefix(8)) so=\(sortOrder) err=\(errMsg)")
                } else {
                    logger.info("[Store] INSERT ok sid=\(message.sessionId.prefix(8)) mid=\(message.id.prefix(8)) so=\(sortOrder) role=\(message.role.rawValue)")
                }
            } else {
                let errMsg = String(cString: sqlite3_errmsg(db))
                logger.error("[Store] INSERT prepare failed rc=\(prepRC) sid=\(message.sessionId.prefix(8)) err=\(errMsg)")
            }
            sqlite3_finalize(stmt)

            touchedSessions.insert(message.sessionId)
            // Always write dirty mark to DB immediately (crash-safe).
            // Only the CloudKit upload trigger (scheduleSend) is deferred.
            markDirty(recordType: "Message", recordId: message.id)
        }

        // Touch sessions once per session
        for sessionId in touchedSessions {
            touchSession(sessionId)
            markDirty(recordType: "Session", recordId: sessionId)
            // Track for file scanning on flush
            pendingSyncSessionIds.insert(sessionId)
        }

        exec("COMMIT")

        // Prune oldest messages if any session exceeds the threshold
        for sessionId in touchedSessions {
            pruneOldMessages(sessionId: sessionId)
        }

        // Post notifications after commit
        for sessionId in touchedSessions {
            NotificationCenter.default.post(name: .sessionDidUpdate, object: sessionId)
        }
    }

    /// Diagnostic helper: returns (count, maxSortOrder) for a session. Used to detect
    /// silent write failures where appendMessage returns but the DB row never lands.
    func sessionWriteStats(sessionId: String) -> (count: Int, maxSortOrder: Int) {
        let sql = "SELECT COUNT(*), COALESCE(MAX(sort_order), -1) FROM messages WHERE session_id = ?"
        var stmt: OpaquePointer?
        var count = 0
        var maxSo = -1
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW {
                count = Int(sqlite3_column_int64(stmt, 0))
                maxSo = Int(sqlite3_column_int64(stmt, 1))
            }
        }
        sqlite3_finalize(stmt)
        return (count, maxSo)
    }

    func loadMessages(sessionId: String) -> [RawMessage] {
        let totalStart = CFAbsoluteTimeGetCurrent()
        let sql = """
            SELECT id, session_id, role, parts_json, created_at, token_usage, reasoning_content, stream_interrupt_count, sort_order, error_info, model_id, model_display_name, provider_type, provider_instance_id
            FROM messages WHERE session_id = ? ORDER BY sort_order ASC, created_at ASC, id ASC
        """
        var stmt: OpaquePointer?
        var messages: [RawMessage] = []
        var jsonDecodeTime: Double = 0

        let prepareResult = sqlite3_prepare_v2(db, sql, -1, &stmt, nil)
        if prepareResult == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)

            while sqlite3_step(stmt) == SQLITE_ROW {
                let id = String(cString: sqlite3_column_text(stmt, 0))
                let sessId = String(cString: sqlite3_column_text(stmt, 1))
                let roleStr = String(cString: sqlite3_column_text(stmt, 2))
                let partsStr = String(cString: sqlite3_column_text(stmt, 3))
                let createdAt = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 4))
                let usageStr = sqlite3_column_text(stmt, 5).map { String(cString: $0) }

                guard let role = MessageRole(rawValue: roleStr) else { continue }

                let decodeStart = CFAbsoluteTimeGetCurrent()
                let parts: [ContentPart]
                if let data = partsStr.data(using: .utf8) {
                    parts = (try? JSONDecoder().decode([ContentPart].self, from: data)) ?? []
                } else {
                    parts = []
                }

                let tokenUsage: StoredTokenUsage?
                if let usageData = usageStr?.data(using: .utf8) {
                    tokenUsage = try? JSONDecoder().decode(StoredTokenUsage.self, from: usageData)
                } else {
                    tokenUsage = nil
                }
                jsonDecodeTime += CFAbsoluteTimeGetCurrent() - decodeStart

                let reasoningContent = sqlite3_column_text(stmt, 6).map { String(cString: $0) }

                let streamInterruptCount = Int(sqlite3_column_int64(stmt, 7))
                let sortOrder = Int(sqlite3_column_int64(stmt, 8))
                let errorInfo = sqlite3_column_text(stmt, 9).map { String(cString: $0) }  // [T-error-persist-ios]
                var msg = RawMessage(
                    id: id, sessionId: sessId, role: role, parts: parts,
                    createdAt: createdAt, tokenUsage: tokenUsage,
                    reasoningContent: reasoningContent,
                    streamInterruptCount: streamInterruptCount
                )
                msg.sortOrder = sortOrder
                msg.errorInfo = errorInfo
                // [T-token-attribution-snapshot] Hydrated because backup export
                // serializes RawMessage straight through — dropping these here
                // would silently strip attribution from every exported package.
                msg.modelId = sqlite3_column_text(stmt, 10).map { String(cString: $0) }
                msg.modelDisplayName = sqlite3_column_text(stmt, 11).map { String(cString: $0) }
                msg.providerType = sqlite3_column_text(stmt, 12).map { String(cString: $0) }
                msg.providerInstanceId = sqlite3_column_text(stmt, 13).map { String(cString: $0) }
                messages.append(msg)
            }
        } else {
            let errMsg = sqlite3_errmsg(db).map { String(cString: $0) } ?? "unknown"
            logger.error("[ChatStore.loadMessages] sqlite3_prepare_v2 FAILED for session \(sessionId): \(errMsg) (code=\(prepareResult))")
            // Fallback: try simpler query without newer migration columns
            sqlite3_finalize(stmt)
            stmt = nil
            let fallbackSql = """
                SELECT id, session_id, role, parts_json, created_at, token_usage
                FROM messages WHERE session_id = ? ORDER BY sort_order ASC, created_at ASC, id ASC
            """
            if sqlite3_prepare_v2(db, fallbackSql, -1, &stmt, nil) == SQLITE_OK {
                logger.info("[ChatStore.loadMessages] Fallback query succeeded for session \(sessionId)")
                sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
                while sqlite3_step(stmt) == SQLITE_ROW {
                    let id = String(cString: sqlite3_column_text(stmt, 0))
                    let sessId = String(cString: sqlite3_column_text(stmt, 1))
                    let roleStr = String(cString: sqlite3_column_text(stmt, 2))
                    let partsStr = String(cString: sqlite3_column_text(stmt, 3))
                    let createdAt = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 4))
                    let usageStr = sqlite3_column_text(stmt, 5).map { String(cString: $0) }

                    guard let role = MessageRole(rawValue: roleStr) else { continue }

                    let decodeStart = CFAbsoluteTimeGetCurrent()
                    let parts: [ContentPart]
                    if let data = partsStr.data(using: .utf8) {
                        parts = (try? JSONDecoder().decode([ContentPart].self, from: data)) ?? []
                    } else {
                        parts = []
                    }
                    let tokenUsage: StoredTokenUsage?
                    if let usageData = usageStr?.data(using: .utf8) {
                        tokenUsage = try? JSONDecoder().decode(StoredTokenUsage.self, from: usageData)
                    } else {
                        tokenUsage = nil
                    }
                    jsonDecodeTime += CFAbsoluteTimeGetCurrent() - decodeStart

                    messages.append(RawMessage(
                        id: id, sessionId: sessId, role: role, parts: parts,
                        createdAt: createdAt, tokenUsage: tokenUsage,
                        reasoningContent: nil,
                        streamInterruptCount: 0
                    ))
                }
            } else {
                let fallbackErr = sqlite3_errmsg(db).map { String(cString: $0) } ?? "unknown"
                logger.error("[ChatStore.loadMessages] Fallback query ALSO FAILED for session \(sessionId): \(fallbackErr)")
            }
        }
        sqlite3_finalize(stmt)

        let totalElapsed = (CFAbsoluteTimeGetCurrent() - totalStart) * 1000
        let jsonElapsed = jsonDecodeTime * 1000
        // Only report loads that are actually slow. This fired on every
        // loadMessages regardless of duration, and the overwhelming majority are
        // a few milliseconds — noise that buries the outliers worth seeing.
        if totalElapsed > 20 {
            logger.info("[ChatStore.loadMessages] \(sessionId) — \(messages.count) messages in \(String(format: "%.1f", totalElapsed))ms (JSON decode: \(String(format: "%.1f", jsonElapsed))ms, SQL+IO: \(String(format: "%.1f", totalElapsed - jsonElapsed))ms)")
        }

        // DIAG: Detect sortOrder anomalies (duplicates, gaps). Small cost — O(n) scan.
        // loadMessages doesn't populate sortOrder on RawMessage, so re-query it.
        var sortOrders: [Int] = []
        let soSql = "SELECT sort_order FROM messages WHERE session_id = ? ORDER BY sort_order ASC, created_at ASC, id ASC"
        var soStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, soSql, -1, &soStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(soStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            while sqlite3_step(soStmt) == SQLITE_ROW {
                sortOrders.append(Int(sqlite3_column_int64(soStmt, 0)))
            }
        }
        sqlite3_finalize(soStmt)
        if !sortOrders.isEmpty {
            let uniq = Set(sortOrders)
            let dupCount = sortOrders.count - uniq.count
            let minSo = sortOrders.first!
            let maxSo = sortOrders.last!
            let expectedIfDense = maxSo - minSo + 1
            let gaps = expectedIfDense - sortOrders.count
            if dupCount > 0 || gaps > 0 {
                // Count duplicates per sortOrder value
                var counts: [Int: Int] = [:]
                for so in sortOrders { counts[so, default: 0] += 1 }
                let dupSamples = counts.filter { $0.value > 1 }.sorted(by: { $0.key < $1.key }).prefix(20)
                let dupStr = dupSamples.map { "\($0.key)×\($0.value)" }.joined(separator: ",")
                logger.error("[SortOrder] ANOMALY sid=\(sessionId.prefix(8)) count=\(sortOrders.count) uniq=\(uniq.count) dup=\(dupCount) min=\(minSo) max=\(maxSo) gaps=\(gaps)")
                logger.error("[SortOrder] duplicates (first 20): \(dupStr)")
                // Dump first 15 and last 5 sortOrder values to see structure
                let headSO = sortOrders.prefix(15).map(String.init).joined(separator: ",")
                let tailSO = sortOrders.suffix(5).map(String.init).joined(separator: ",")
                logger.error("[SortOrder] head 15: \(headSO)")
                logger.error("[SortOrder] tail 5:  \(tailSO)")
            }
            // The healthy branch used to log an "[SortOrder] OK …" line on every
            // loadMessages — 6,628 lines in a single day (2026-08-11), the
            // largest single source of log noise. The ANOMALY branch above is
            // what carries signal: duplicates and gaps are real data problems.
        }

        return messages
    }

    /// [T-ios-session-paused-badge-hardkill] Returns the set of session ids whose
    /// agent loop was left interrupted, derived purely from the persisted message
    /// tail — so it survives a hard kill (jetsam/SIGKILL) where the background-
    /// task expiry handler never ran to push the `.paused` badge.
    ///
    /// Lightweight: fetches ONLY the last message row per session (one query with
    /// a correlated max(sort_order) subquery), not the full history. Applies the
    /// SAME predicate as `AIChatViewModel.loadSession`'s interrupted-agent-loop
    /// detection (kept in sync intentionally):
    ///   - last row is a user message of ALL toolResult parts (tools ran, the
    ///     next model call never happened), OR
    ///   - last row is the synthetic "Continue" stop message, OR
    ///   - last row is an assistant message containing a toolUse (model asked for
    ///     tools that never executed).
    func interruptedSessionIds() -> Set<String> {
        Set(interruptedSessionsWithTailDate().keys)
    }

    /// [T-ios-group-pause-badge-reconcile-stamp] Same scan as
    /// `interruptedSessionIds`, but each id carries the `created_at` of the
    /// interrupted tail message — i.e. WHEN the session was left hanging.
    ///
    /// The badge store needs this because reconcile is the only writer that can
    /// see a session's paused state without having witnessed the interruption:
    /// after a hard kill (or on the first launch after the freshness window
    /// shipped) it restores the marker for sessions interrupted arbitrarily long
    /// ago. Stamping those `Date()` declares a days-old pause "just happened",
    /// which is exactly what kept stale badges lighting their group cards. The
    /// tail's own timestamp is the real entry time and is already in the row we
    /// read, so recovering it costs one extra column, not another query.
    func interruptedSessionsWithTailDate() -> [String: Date] {
        // Last row per session = the message with the maximum (sort_order,
        // created_at, id) ordering used everywhere else. Correlated subquery on
        // sort_order is enough in practice; ties are vanishingly rare and a
        // mis-pick there only changes a transient badge, never data.
        let sql = """
            SELECT m.session_id, m.role, m.parts_json, m.created_at
            FROM messages m
            WHERE m.sort_order = (
                SELECT MAX(m2.sort_order) FROM messages m2 WHERE m2.session_id = m.session_id
            )
        """
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
            let err = sqlite3_errmsg(db).map { String(cString: $0) } ?? "unknown"
            logger.error("[ChatStore.interruptedSessionIds] prepare failed: \(err)")
            return [:]
        }

        var result: [String: Date] = [:]
        // A session can have >1 row sharing the max sort_order (tie); take the
        // last one seen, mirroring the ASC ordering's final element.
        var lastBySession: [String: (role: MessageRole, parts: [ContentPart], createdAt: Double)] = [:]
        while sqlite3_step(stmt) == SQLITE_ROW {
            let sid = String(cString: sqlite3_column_text(stmt, 0))
            let roleStr = String(cString: sqlite3_column_text(stmt, 1))
            let partsStr = String(cString: sqlite3_column_text(stmt, 2))
            let createdAt = sqlite3_column_double(stmt, 3)
            guard let role = MessageRole(rawValue: roleStr) else { continue }
            let parts: [ContentPart] = partsStr.data(using: .utf8)
                .flatMap { try? JSONDecoder().decode([ContentPart].self, from: $0) } ?? []
            lastBySession[sid] = (role, parts, createdAt)
        }

        for (sid, entry) in lastBySession {
            if Self.isInterruptedTail(role: entry.role, parts: entry.parts) {
                // created_at of 0 (or a garbage row) would read as 1970 and make
                // the badge permanently stale rather than permanently fresh —
                // the safe direction, but still wrong, so fall back to "now" and
                // let the normal window govern it.
                result[sid] = entry.createdAt > 0
                    ? Date(timeIntervalSince1970: entry.createdAt)
                    : Date()
            }
        }
        logger.info("[ChatStore.interruptedSessionIds] scanned \(lastBySession.count) sessions → \(result.count) interrupted")
        return result
    }

    /// [T-ios-session-paused-badge-hardkill] The interrupted-tail predicate,
    /// factored out so it stays identical to `loadSession`'s inline logic. Keep
    /// the two in sync if either changes.
    static func isInterruptedTail(role: MessageRole, parts: [ContentPart]) -> Bool {
        if role == .user {
            let allToolResults = !parts.isEmpty && parts.allSatisfy {
                if case .toolResult = $0 { return true }; return false
            }
            let isContinueMessage = parts.count == 1 && {
                if case .text(let t) = parts.first,
                   t.contains("The user stopped the previous response") {
                    return true
                }
                return false
            }()
            return allToolResults || isContinueMessage
        } else if role == .assistant {
            return parts.contains {
                if case .toolUse = $0 { return true }; return false
            }
        }
        return false
    }

    func deleteMessages(sessionId: String) {
        invalidateSessionListCache()
        // Mark every message dirty for cloud deletion BEFORE the DELETE so other
        // devices remove them on next sync. Without this, Clear Chat wipes
        // locally but iCloud re-hydrates the rows on the next pull.
        let selSql = "SELECT id FROM messages WHERE session_id = ?"
        var selStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, selSql, -1, &selStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(selStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            while sqlite3_step(selStmt) == SQLITE_ROW {
                let msgId = String(cString: sqlite3_column_text(selStmt, 0))
                markDirty(recordType: "Message", recordId: msgId, operation: "delete")
            }
        }
        sqlite3_finalize(selStmt)

        let sql = "DELETE FROM messages WHERE session_id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    /// [T-ios-rerun-from-tool-block-position] Rewrite a single message row's
    /// parts in place. Used by retryFromToolBlock's sub-message cut: when the
    /// re-run anchor is a tool_use that is NOT the first block of its assistant
    /// turn, we keep the earlier blocks and must persist the trimmed parts back
    /// to that exact row (deleteMessagesAfter only drops whole rows after the
    /// boundary; it does not rewrite a kept row). Mirrors appendMessage's
    /// parts_json encoding.
    func updateMessageParts(messageId: String, parts: [ContentPart]) {
        invalidateSessionListCache()
        let partsJSON: String
        do {
            let data = try JSONEncoder().encode(parts)
            partsJSON = String(data: data, encoding: .utf8) ?? "[]"
        } catch {
            logger.error("[updateMessageParts] encode failed id=\(messageId.prefix(8)): \(error)")
            return
        }
        // [T-ios-listsessions-part-flags] keep the bitmask in lockstep with parts_json.
        let partFlags = Self.partFlags(for: parts)
        let sql = "UPDATE messages SET parts_json = ?, updated_at = ?, part_flags = ? WHERE id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (partsJSON as NSString).utf8String, -1, nil)
            sqlite3_bind_double(stmt, 2, Date().timeIntervalSince1970)
            sqlite3_bind_int64(stmt, 3, Int64(partFlags))
            sqlite3_bind_text(stmt, 4, (messageId as NSString).utf8String, -1, nil)
            let rc = sqlite3_step(stmt)
            logger.info("[updateMessageParts] id=\(messageId.prefix(8)) parts=\(parts.count) stepRC=\(rc)")
        }
        sqlite3_finalize(stmt)
        // Mark dirty so the rewritten row syncs to iCloud.
        markDirty(recordType: "Message", recordId: messageId)
    }

    /// Delete all message rows AFTER the first `keepCount` rows (ordered by
    /// sort_order). Callers pass `agentHistory.count` meaning "keep the first N
    /// rows"; they do NOT pass a sort_order value.
    ///
    /// [T-19-chat-content-lost-34328] This used to treat `keepCount` directly as
    /// the sort_order cutoff (`DELETE WHERE sort_order >= keepCount`). That is
    /// only correct when sort_order is dense and 0-based (0..count-1). After a
    /// prune drops the oldest rows, min(sort_order) > 0 and the live range
    /// shifts right, so `keepCount` (a row COUNT) no longer equals
    /// `max(sort_order)+1`. A real session hit this: keepCount=2048 while
    /// maxSortOrder=2703 → the old `>= 2048` predicate nuked 656 still-live
    /// recent messages (count 2048→1392) — silent user-data loss on resend/edit.
    ///
    /// Fix: resolve the ACTUAL boundary sort_order = the sort_order of the
    /// keepCount-th row (ascending, 1-based), then delete strictly AFTER it.
    /// This is correct regardless of whether sort_order is dense or shifted.
    func deleteMessagesAfter(sessionId: String, keepCount: Int) {
        invalidateSessionListCache()
        // DIAG: snapshot state before delete
        let beforeStats = sessionWriteStats(sessionId: sessionId)
        logger.warning("[EditSync] [DeleteAfter] ENTER sid=\(sessionId.prefix(8)) keepCount=\(keepCount) — before: count=\(beforeStats.count) maxSortOrder=\(beforeStats.maxSortOrder)")

        // keepCount <= 0 → keep nothing: delete every row. Use a boundary of
        // Int64.min so `sort_order > boundary` matches all rows.
        // Otherwise resolve the keepCount-th row's sort_order (OFFSET keepCount-1,
        // ascending). If there are <= keepCount rows, the SELECT returns no row
        // and we delete nothing.
        var boundarySortOrder: Int64? = nil  // nil → no row to delete after; keep all
        var deleteAll = false
        if keepCount <= 0 {
            deleteAll = true
        } else {
            // ORDER BY must match loadMessages / the repair canonical order
            // (sort_order ASC, created_at ASC, id ASC). sort_order CAN have
            // duplicates between repairs (remote-merge races; the prune path
            // even documents itself as "immune to sort_order duplicates"), so a
            // single-key sort could pick a different keepCount-th row than the
            // one the caller counted in agentHistory. Tie-breaking on
            // (created_at, id) keeps the boundary row identical to the caller's
            // view. The DELETE below still filters on `sort_order >`, so if the
            // boundary row shares its sort_order with later rows those rows are
            // NOT deleted — i.e. duplicates make truncation conservative (keep
            // more), never destructive.
            let boundarySql = """
                SELECT sort_order FROM messages
                WHERE session_id = ?
                ORDER BY sort_order ASC, created_at ASC, id ASC
                LIMIT 1 OFFSET ?
                """
            var bStmt: OpaquePointer?
            if sqlite3_prepare_v2(db, boundarySql, -1, &bStmt, nil) == SQLITE_OK {
                sqlite3_bind_text(bStmt, 1, (sessionId as NSString).utf8String, -1, nil)
                sqlite3_bind_int64(bStmt, 2, Int64(keepCount - 1))
                if sqlite3_step(bStmt) == SQLITE_ROW {
                    boundarySortOrder = sqlite3_column_int64(bStmt, 0)
                }
            }
            sqlite3_finalize(bStmt)
            // No keepCount-th row exists (session has <= keepCount rows): nothing
            // is after the kept set, so there is nothing to delete.
            if boundarySortOrder == nil {
                logger.warning("[EditSync] [DeleteAfter] DONE sid=\(sessionId.prefix(8)) keepCount=\(keepCount) — session has \(beforeStats.count) rows (<= keepCount), nothing to delete")
                return
            }
        }

        // Mark deleted messages for iCloud sync removal before deleting locally.
        let selectSql = deleteAll
            ? "SELECT id FROM messages WHERE session_id = ?"
            : "SELECT id FROM messages WHERE session_id = ? AND sort_order > ?"
        var selectStmt: OpaquePointer?
        var toDelete: [String] = []
        if sqlite3_prepare_v2(db, selectSql, -1, &selectStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(selectStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            if let boundary = boundarySortOrder {
                sqlite3_bind_int64(selectStmt, 2, boundary)
            }
            while sqlite3_step(selectStmt) == SQLITE_ROW {
                let msgId = String(cString: sqlite3_column_text(selectStmt, 0))
                toDelete.append(msgId)
                markDirty(recordType: "Message", recordId: msgId, operation: "delete")
                logger.warning("[EditSync] markDirty Message id=\(msgId.prefix(8)) op=delete — will push tombstone")
            }
        }
        sqlite3_finalize(selectStmt)

        let sql = deleteAll
            ? "DELETE FROM messages WHERE session_id = ?"
            : "DELETE FROM messages WHERE session_id = ? AND sort_order > ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
            if let boundary = boundarySortOrder {
                sqlite3_bind_int64(stmt, 2, boundary)
            }
            sqlite3_step(stmt)
        }
        let deletedRows = Int(sqlite3_changes(db))
        sqlite3_finalize(stmt)

        let afterStats = sessionWriteStats(sessionId: sessionId)
        let boundaryDesc = deleteAll ? "ALL" : String(boundarySortOrder ?? -1)
        logger.warning("[EditSync] [DeleteAfter] DONE sid=\(sessionId.prefix(8)) keepCount=\(keepCount) boundarySortOrder=\(boundaryDesc) deletedRows=\(deletedRows) toDeleteMarked=\(toDelete.count) — after: count=\(afterStats.count) maxSortOrder=\(afterStats.maxSortOrder)")
        if deletedRows > 0 {
            let idSample = toDelete.prefix(10).map { String($0.prefix(8)) }.joined(separator: ",")
            logger.warning("[EditSync] [DeleteAfter] deleted msg ids (first 10): \(idSample) — these tombstones should appear in next SyncCore outbound batch.deletes")
        }
    }

    // MARK: - Media File Management

    /// Save media data to disk, return a MediaRef.
    /// Files are saved to `Library/MinisChat/minis/<sessionId>/<subdir>/<fileId>.<ext>`.
    func saveMedia(
        data: Data, mimeType: String, sessionId: String,
        originalFileName: String? = nil, subdir: String = "attachments",
        linuxPath: String? = nil
    ) -> MediaRef {
        let fileId = UUID().uuidString
        let ext = Self.fileExtension(for: mimeType)

        let relativePath = "\(sessionId)/\(subdir)/\(fileId).\(ext)"
        let fileURL = minisBaseURL.appendingPathComponent(relativePath)

        let dirURL = fileURL.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dirURL, withIntermediateDirectories: true)

        do {
            try data.write(to: fileURL)
        } catch {
            logger.error("Failed to write media file: \(error)")
        }

        let ref = MediaRef(
            id: fileId,
            relativePath: relativePath,
            mimeType: mimeType,
            originalFileName: originalFileName,
            linuxPath: linuxPath
        )

        // Mark file dirty for iCloud sync via SessionFile record type
        markDirty(recordType: "SessionFile", recordId: "\(sessionId):\(subdir)/\(fileId).\(ext)")

        return ref
    }

    /// Resolve the on-disk URL for a MediaRef.
    func mediaFileURL(for ref: MediaRef) -> URL {
        minisBaseURL.appendingPathComponent(ref.relativePath)
    }

    /// Load media data from a MediaRef.
    func loadMediaData(_ ref: MediaRef) -> Data? {
        let url = mediaFileURL(for: ref)
        return try? Data(contentsOf: url)
    }

    /// Delete all media for a session.
    func deleteSessionMedia(_ sessionId: String) {
        let fm = FileManager.default

        for subdir in ["browser", "attachments", "images"] {
            let dirURL = minisBaseURL.appendingPathComponent("\(sessionId)/\(subdir)")
            if fm.fileExists(atPath: dirURL.path) {
                try? fm.removeItem(at: dirURL)
            }
        }
    }

    /// Returns a closure that resolves a MediaRef to a file URL.
    func mediaFileURLResolver() -> (MediaRef) -> URL {
        let minis = minisBaseURL
        return { ref in
            minis.appendingPathComponent(ref.relativePath)
        }
    }

    // MARK: - Compact Markers

    /// Insert a compact marker after a successful compaction.
    func insertCompactMarker(_ marker: CompactMarker) {
        let sql = """
            INSERT INTO compact_markers (id, session_id, summary, first_kept_sort_order, compacted_count, created_at, ui_boundary_sort_order, boundary_message_id, first_kept_message_id, last_compacted_message_id, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (marker.id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (marker.sessionId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 3, (marker.summary as NSString).utf8String, -1, nil)
            sqlite3_bind_int64(stmt, 4, Int64(marker.firstKeptSortOrder))
            sqlite3_bind_int64(stmt, 5, Int64(marker.compactedCount))
            sqlite3_bind_double(stmt, 6, marker.createdAt.timeIntervalSince1970)
            if let uiBoundary = marker.uiBoundarySortOrder {
                sqlite3_bind_int64(stmt, 7, Int64(uiBoundary))
            } else {
                sqlite3_bind_null(stmt, 7)
            }
            if let bmId = marker.boundaryMessageId {
                sqlite3_bind_text(stmt, 8, (bmId as NSString).utf8String, -1, nil)
            } else {
                sqlite3_bind_null(stmt, 8)
            }
            if let fkmId = marker.firstKeptMessageId {
                sqlite3_bind_text(stmt, 9, (fkmId as NSString).utf8String, -1, nil)
            } else {
                sqlite3_bind_null(stmt, 9)
            }
            if let lcmId = marker.lastCompactedMessageId {
                sqlite3_bind_text(stmt, 10, (lcmId as NSString).utf8String, -1, nil)
            } else {
                sqlite3_bind_null(stmt, 10)
            }
            sqlite3_bind_int64(stmt, 11, Int64(marker.version))
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
        markDirty(recordType: "CompactMarker", recordId: marker.id)
    }

    /// Rewrite an existing compact marker in place (same id). Used by Phase 2.5
    /// self-heal when the original `lastCompactedMessageId` no longer resolves
    /// in the current rawMessages — we recompute the anchor by createdAt,
    /// upgrade the marker to v2, and persist so subsequent loads / agent sends
    /// find the corrected id directly.
    ///
    /// Implementation mirrors `mergeRemoteCompactMarker`: DELETE + INSERT in
    /// the same code path so all columns (including legacy migrations) flow
    /// through one INSERT.
    func updateCompactMarker(_ marker: CompactMarker) {
        let delSql = "DELETE FROM compact_markers WHERE id = ?"
        var delStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, delSql, -1, &delStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(delStmt, 1, (marker.id as NSString).utf8String, -1, nil)
            sqlite3_step(delStmt)
        }
        sqlite3_finalize(delStmt)
        insertCompactMarker(marker)
    }

    /// Get a compact marker by ID (for iCloud sync buildCKRecord).
    func getCompactMarker(id: String) -> CompactMarker? {
        let sql = "SELECT id, session_id, summary, first_kept_sort_order, compacted_count, created_at, ui_boundary_sort_order, boundary_message_id, first_kept_message_id, last_compacted_message_id, version FROM compact_markers WHERE id = ?"
        var stmt: OpaquePointer?
        var marker: CompactMarker?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW {
                marker = readCompactMarker(from: stmt)
            }
        }
        sqlite3_finalize(stmt)
        return marker
    }

    /// Merge a remote compact marker into local DB.
    /// Phase A: createdAt-based UPSERT — remote newer wins, otherwise keep local.
    /// This lets newer builds propagate upgraded boundary fields to older remote markers.
    func mergeRemoteCompactMarker(_ marker: CompactMarker) {
        // [T-icloud-record-delete-resurrection] A marker we just deleted
        // locally (revertCompact) coming back via fetchRecentV2 before the
        // cloud delete lands must not be re-inserted. Markers are immutable,
        // so createdAt is their only clock.
        if isRecentlyDeletedRecord(type: "CompactMarker", id: marker.id, remoteUpdatedAt: marker.createdAt) {
            iCloudLogger.info("[iCloud] mergeRemoteCompactMarker SKIP (recently deleted locally): id=\(marker.id.prefix(8))")
            return
        }
        // Only merge if the session exists locally
        let checkSql = "SELECT id FROM sessions WHERE id = ?"
        var checkStmt: OpaquePointer?
        var sessionExists = false
        if sqlite3_prepare_v2(db, checkSql, -1, &checkStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(checkStmt, 1, (marker.sessionId as NSString).utf8String, -1, nil)
            sessionExists = sqlite3_step(checkStmt) == SQLITE_ROW
        }
        sqlite3_finalize(checkStmt)
        guard sessionExists else { return }

        // If local already has this marker id, only overwrite when remote is strictly newer.
        if let existing = getCompactMarker(id: marker.id) {
            guard marker.createdAt > existing.createdAt else {
                iCloudLogger.info("[iCloud] mergeRemoteCompactMarker SKIP (local newer or equal): id=\(marker.id.prefix(8))")
                return
            }
            // DELETE existing row so the subsequent insertCompactMarker can re-insert
            // with all columns (including legacy migrations) in one place.
            let delSql = "DELETE FROM compact_markers WHERE id = ?"
            var delStmt: OpaquePointer?
            if sqlite3_prepare_v2(db, delSql, -1, &delStmt, nil) == SQLITE_OK {
                sqlite3_bind_text(delStmt, 1, (marker.id as NSString).utf8String, -1, nil)
                sqlite3_step(delStmt)
            }
            sqlite3_finalize(delStmt)
            iCloudLogger.info("[iCloud] mergeRemoteCompactMarker UPDATE (remote newer): id=\(marker.id.prefix(8))")
        }

        insertCompactMarker(marker)
    }

    /// Extra session fields for iCloud sync (memory_enabled, model_binding).
    func getSessionExtras(_ sessionId: String) -> (memoryEnabled: Bool, modelBinding: String?) {
        let sql = "SELECT memory_enabled, model_binding FROM sessions WHERE id = ?"
        var stmt: OpaquePointer?
        var memoryEnabled = true
        var modelBinding: String?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW {
                memoryEnabled = sqlite3_column_int(stmt, 0) != 0
                modelBinding = sqlite3_column_text(stmt, 1).map { String(cString: $0) }
            }
        }
        sqlite3_finalize(stmt)
        return (memoryEnabled, modelBinding)
    }

    /// Return the most recent compact marker for a session, or nil if none.
    func latestCompactMarker(sessionId: String) -> CompactMarker? {
        let sql = """
            SELECT id, session_id, summary, first_kept_sort_order, compacted_count, created_at, ui_boundary_sort_order, boundary_message_id, first_kept_message_id, last_compacted_message_id, version
            FROM compact_markers WHERE session_id = ? ORDER BY created_at DESC LIMIT 1
        """
        var stmt: OpaquePointer?
        var marker: CompactMarker?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW {
                marker = readCompactMarker(from: stmt)
            }
        }
        sqlite3_finalize(stmt)
        return marker
    }

    /// Bump `updated_at = now()` on a single message. Used by debug RPCs to
    /// force a content-change so a re-pushed Message record actually advances
    /// CloudKit's modificationDate (saving an unchanged etag is a no-op on
    /// the cloud side and peer devices won't see it as a "new change").
    /// Returns true if the row existed and was bumped.
    @discardableResult
    func bumpMessageUpdatedAt(id: String) -> Bool {
        let sql = "UPDATE messages SET updated_at = ? WHERE id = ?"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return false }
        sqlite3_bind_double(stmt, 1, Date().timeIntervalSince1970)
        sqlite3_bind_text(stmt, 2, (id as NSString).utf8String, -1, nil)
        guard sqlite3_step(stmt) == SQLITE_DONE else { return false }
        return sqlite3_changes(db) > 0
    }

    /// [T-error-persist-ios] Persist (or clear) the device-local error string on a
    /// single message. Lightweight UPDATE so the agent loop can write the error the
    /// instant it's set on the in-memory ChatMessage, without re-serializing the whole
    /// message. Pass nil to clear (e.g. a successful retry). Returns true if a row was
    /// updated. NOT marked dirty for iCloud — error_info is device-local.
    @discardableResult
    func updateMessageErrorInfo(messageId: String, errorInfo: String?) -> Bool {
        let sql = "UPDATE messages SET error_info = ? WHERE id = ?"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return false }
        // Normalize empty/whitespace → NULL so a stray "" never persists (the UI
        // gates the error banner on `error != nil`, not emptiness, so an empty
        // string would render a blank banner). [T-error-persist-ios]
        let normalized = errorInfo?.trimmingCharacters(in: .whitespacesAndNewlines)
        bindOptionalText(stmt, index: 1, value: (normalized?.isEmpty == false) ? normalized : nil)
        sqlite3_bind_text(stmt, 2, (messageId as NSString).utf8String, -1, nil)
        guard sqlite3_step(stmt) == SQLITE_DONE else { return false }
        return sqlite3_changes(db) > 0
    }

    /// Look up the parent sessionId for a message id, or nil if not found.
    /// Read-only — used by debug RPCs that re-mark individual messages dirty.
    func messageSessionId(id: String) -> String? {
        let sql = "SELECT session_id FROM messages WHERE id = ? LIMIT 1"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
        guard sqlite3_step(stmt) == SQLITE_ROW,
              let cstr = sqlite3_column_text(stmt, 0) else { return nil }
        return String(cString: cstr)
    }

    /// Return all compact markers for a session, ordered by created_at ascending.
    /// Read-only — used by debug RPCs to inspect sync state.
    // MARK: - Recent-delete tombstones (resurrection guard)
    // [T-icloud-deleted-session-resurrection]

    /// Record that `sessionId` was just deleted locally, so an inbound merge
    /// arriving before the cloud delete tombstone lands won't re-insert it.
    func recordDeletedSessionTombstone(_ sessionId: String, at: Date = Date()) {
        let sql = "INSERT OR REPLACE INTO deleted_session_tombstones (session_id, deleted_at) VALUES (?, ?)"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return }
        sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
        sqlite3_bind_double(stmt, 2, at.timeIntervalSince1970)
        _ = sqlite3_step(stmt)
    }

    /// The timestamp `sessionId` was locally deleted, or nil if it has no
    /// recent-delete tombstone. Inbound merges consult this to refuse
    /// resurrecting a session/message/file that isn't newer than the deletion.
    func deletedSessionTombstone(_ sessionId: String) -> Date? {
        let sql = "SELECT deleted_at FROM deleted_session_tombstones WHERE session_id = ? LIMIT 1"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        return Date(timeIntervalSince1970: sqlite3_column_double(stmt, 0))
    }

    /// True when `sessionId` has a recent-delete tombstone at least as new as
    /// `remoteUpdatedAt` — i.e. the inbound record is the just-deleted one
    /// coming back, not a genuine newer re-create. The merge should refuse it.
    /// A small slack absorbs server/client clock skew (the resurrected record's
    /// updatedAt is the pre-deletion value, so it is always <= deletedAt in
    /// practice; slack only guards against minor forward skew).
    func isResurrectionOfDeleted(_ sessionId: String, remoteUpdatedAt: Date) -> Bool {
        guard let deletedAt = deletedSessionTombstone(sessionId) else { return false }
        return remoteUpdatedAt.timeIntervalSince1970 <= deletedAt.timeIntervalSince1970 + 5
    }

    // [T-icloud-record-delete-resurrection] Generic per-record variant of the
    // session tombstones above, for types whose op=delete propagates but whose
    // inbound merge would re-insert a stale live record that fetchRecentV2
    // pulled back before the cloud delete landed (Skill / EnvVarItem /
    // CompactMarker). Written automatically by markDirty(op=delete).

    /// Record that record `id` of `type` was just deleted locally.
    func recordDeletedRecordTombstone(type: String, id: String, at: Date = Date()) {
        let sql = "INSERT OR REPLACE INTO deleted_record_tombstones (record_type, record_id, deleted_at) VALUES (?, ?, ?)"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return }
        sqlite3_bind_text(stmt, 1, (type as NSString).utf8String, -1, nil)
        sqlite3_bind_text(stmt, 2, (id as NSString).utf8String, -1, nil)
        sqlite3_bind_double(stmt, 3, at.timeIntervalSince1970)
        _ = sqlite3_step(stmt)
    }

    /// The timestamp record `id` of `type` was locally deleted, or nil.
    private func deletedRecordTombstone(type: String, id: String) -> Date? {
        let sql = "SELECT deleted_at FROM deleted_record_tombstones WHERE record_type = ? AND record_id = ? LIMIT 1"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_text(stmt, 1, (type as NSString).utf8String, -1, nil)
        sqlite3_bind_text(stmt, 2, (id as NSString).utf8String, -1, nil)
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        return Date(timeIntervalSince1970: sqlite3_column_double(stmt, 0))
    }

    /// True when the inbound record is the just-deleted one coming back (its
    /// remote timestamp is not newer than the local deletion, +5s skew slack)
    /// rather than a genuine newer re-create. The merge should refuse it.
    func isRecentlyDeletedRecord(type: String, id: String, remoteUpdatedAt: Date) -> Bool {
        guard let deletedAt = deletedRecordTombstone(type: type, id: id) else { return false }
        return remoteUpdatedAt.timeIntervalSince1970 <= deletedAt.timeIntervalSince1970 + 5
    }

    /// Set or clear the v2 soft-delete tombstone on a session row. Pass
    /// `at = nil` to clear (after a resurrect cascade re-pushes the tree).
    func setSessionTombstone(sessionId: String, at: Date?) {
        invalidateSessionListCache()
        let sql = "UPDATE sessions SET remote_tombstoned_at = ? WHERE id = ?"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return }
        if let date = at {
            sqlite3_bind_double(stmt, 1, date.timeIntervalSince1970)
        } else {
            sqlite3_bind_null(stmt, 1)
        }
        sqlite3_bind_text(stmt, 2, (sessionId as NSString).utf8String, -1, nil)
        _ = sqlite3_step(stmt)
    }

    /// Returns the tombstone timestamp (or nil if not tombstoned / row
    /// missing).
    func sessionTombstone(sessionId: String) -> Date? {
        let sql = "SELECT remote_tombstoned_at FROM sessions WHERE id = ? LIMIT 1"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        if sqlite3_column_type(stmt, 0) == SQLITE_NULL { return nil }
        return Date(timeIntervalSince1970: sqlite3_column_double(stmt, 0))
    }

    /// All currently-tombstoned session ids — diagnostic.
    func tombstonedSessionIds() -> [String] {
        let sql = "SELECT id FROM sessions WHERE remote_tombstoned_at IS NOT NULL"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        var ids: [String] = []
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return ids }
        while sqlite3_step(stmt) == SQLITE_ROW {
            if let cstr = sqlite3_column_text(stmt, 0) {
                ids.append(String(cString: cstr))
            }
        }
        return ids
    }

    /// Cascade-mark every record in a session's tree dirty (Session +
    /// Messages + CompactMarkers) for v2 push, and clear the tombstone.
    /// Called by `markDirty` whenever a tombstoned session takes a new
    /// upsert. Doesn't hop actors — pure SQL inside the ChatStore actor.
    /// Returns true if a cascade actually ran.
    @discardableResult
    func resurrectSessionTreeIfTombstoned(sessionId: String) -> Bool {
        guard sessionTombstone(sessionId: sessionId) != nil else { return false }
        // 1. Session itself.
        markDirtyRowOnly(recordType: "SessionV2", recordId: sessionId)
        // 2. Every message id in the session.
        let msgSql = "SELECT id FROM messages WHERE session_id = ?"
        var stmt: OpaquePointer?
        var msgCount = 0
        if sqlite3_prepare_v2(db, msgSql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let cstr = sqlite3_column_text(stmt, 0) {
                    let mid = String(cString: cstr)
                    markDirtyRowOnly(recordType: "MessageV2", recordId: mid)
                    msgCount += 1
                }
            }
        }
        sqlite3_finalize(stmt)
        // 3. Every compact marker.
        let markers = compactMarkers(sessionId: sessionId)
        for m in markers {
            markDirtyRowOnly(recordType: "CompactMarkerV2", recordId: m.id)
        }
        // 4. Every SessionFile under this session — scan the workspace
        //    directory and mark each file's v2 dirty row. Mirrors v1's
        //    scanAndMarkSessionFiles(sessionId:).
        let fileCount = scanAndMarkSessionFilesForResurrect(sessionId: sessionId)
        // 5. Clear tombstone.
        setSessionTombstone(sessionId: sessionId, at: nil)
        iCloudLogger.info("[SyncConflict] resurrect cascade auto: sessionId=\(sessionId.prefix(8)) Session=1 Messages=\(msgCount) CompactMarkers=\(markers.count) SessionFiles=\(fileCount)")
        return true
    }

    /// Resurrect-time SessionFile scanner. Walks the session's workspace
    /// directory and writes a v2 dirty row for each file (composite key
    /// = "sessionId:relativePath"). Returns the count.
    private func scanAndMarkSessionFilesForResurrect(sessionId: String) -> Int {
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let sessionDir = library.appendingPathComponent("MinisChat/minis/\(sessionId)")
        guard let enumerator = FileManager.default.enumerator(
            at: sessionDir, includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else { return 0 }
        var count = 0
        for case let url as URL in enumerator {
            guard let isFile = (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile, isFile else { continue }
            let relativePath = url.path.replacingOccurrences(of: sessionDir.path + "/", with: "")
            let recordId = "\(sessionId):\(relativePath)"
            markDirtyRowOnly(recordType: "SessionFileV2", recordId: recordId)
            count += 1
        }
        return count
    }

    /// Internal helper: write a v2 dirty row directly without scheduling
    /// a send (the caller's outer markDirty already triggered it).
    private func markDirtyRowOnly(recordType: String, recordId: String) {
        guard !syncZoneName.isEmpty else { return }
        let zoneName = syncZoneName
        let sql = """
            INSERT OR REPLACE INTO sync_dirty_records (record_type, record_id, zone_name, operation)
            VALUES (?, ?, ?, ?)
        """
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return }
        sqlite3_bind_text(stmt, 1, (recordType as NSString).utf8String, -1, nil)
        sqlite3_bind_text(stmt, 2, (recordId as NSString).utf8String, -1, nil)
        sqlite3_bind_text(stmt, 3, (zoneName as NSString).utf8String, -1, nil)
        sqlite3_bind_text(stmt, 4, ("upsert" as NSString).utf8String, -1, nil)
        _ = sqlite3_step(stmt)
    }

    /// Resolve the parent sessionId for a v1/v2 record being marked dirty,
    /// when it falls under a session-scoped record type. Used by markDirty
    /// to detect "this write belongs to a tombstoned session → cascade".
    /// Returns nil for global / non-session-scoped types.
    private func parentSessionId(forV1 recordType: String, recordId: String) -> String? {
        switch recordType {
        case "Session":
            return recordId
        case "Message":
            return messageSessionId(id: recordId)
        case "CompactMarker":
            return compactMarkerSessionId(id: recordId)
        case "SessionFile":
            // SessionFile recordId is "sessionId:relativePath".
            let parts = recordId.split(separator: ":", maxSplits: 1)
            guard parts.count == 2 else { return nil }
            return String(parts[0])
        default:
            return nil
        }
    }

    /// SessionId of the parent for a CompactMarker id. Light wrapper —
    /// keeps the SQL away from callers.
    private func compactMarkerSessionId(id: String) -> String? {
        let sql = "SELECT session_id FROM compact_markers WHERE id = ? LIMIT 1"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
        guard sqlite3_step(stmt) == SQLITE_ROW,
              let cstr = sqlite3_column_text(stmt, 0) else { return nil }
        return String(cString: cstr)
    }

    /// Returns every locally-stored session id (for §3.6.0 scenario E
    /// fullFetch reconciliation).
    func allSessionIds() -> [String] {
        let sql = "SELECT id FROM sessions"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        var ids: [String] = []
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return ids }
        while sqlite3_step(stmt) == SQLITE_ROW {
            if let cstr = sqlite3_column_text(stmt, 0) {
                ids.append(String(cString: cstr))
            }
        }
        return ids
    }

    func compactMarkers(sessionId: String) -> [CompactMarker] {
        let sql = """
            SELECT id, session_id, summary, first_kept_sort_order, compacted_count, created_at, ui_boundary_sort_order, boundary_message_id, first_kept_message_id, last_compacted_message_id, version
            FROM compact_markers WHERE session_id = ? ORDER BY created_at ASC
        """
        var stmt: OpaquePointer?
        var markers: [CompactMarker] = []
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                markers.append(readCompactMarker(from: stmt))
            }
        }
        sqlite3_finalize(stmt)
        return markers
    }

    /// Return the second-most-recent compact marker (the one before the latest).
    func previousCompactMarker(sessionId: String) -> CompactMarker? {
        let sql = """
            SELECT id, session_id, summary, first_kept_sort_order, compacted_count, created_at, ui_boundary_sort_order, boundary_message_id, first_kept_message_id, last_compacted_message_id, version
            FROM compact_markers WHERE session_id = ? ORDER BY created_at DESC LIMIT 1 OFFSET 1
        """
        var stmt: OpaquePointer?
        var marker: CompactMarker?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW {
                marker = readCompactMarker(from: stmt)
            }
        }
        sqlite3_finalize(stmt)
        return marker
    }

    /// Delete a single compact marker by id (for revert-compact). Marks dirty
    /// for iCloud sync so the deletion propagates. Returns true if a row was
    /// actually deleted.
    @discardableResult
    func deleteCompactMarker(id: String) -> Bool {
        markDirty(recordType: "CompactMarker", recordId: id, operation: "delete")
        return deleteLocalCompactMarker(id: id)
    }

    /// Local-only marker deletion — does NOT enqueue a cloud tombstone. Used
    /// by the sync hydrator when a remote device's revert tombstone arrives;
    /// re-marking it dirty here would bounce the deletion back to the cloud.
    @discardableResult
    func deleteLocalCompactMarker(id: String) -> Bool {
        let sql = "DELETE FROM compact_markers WHERE id = ?"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return false }
        sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
        guard sqlite3_step(stmt) == SQLITE_DONE else { return false }
        let removed = sqlite3_changes(db) > 0
        if removed {
            iCloudLogger.info("[iCloud] deleteLocalCompactMarker: removed '\(id.prefix(8))' from local compact_markers")
        }
        return removed
    }

    /// Delete all compact markers for a session.
    func deleteCompactMarkers(sessionId: String) {
        // Mark every marker dirty for cloud deletion BEFORE the DELETE so iCloud
        // doesn't repopulate them. Mirrors the messages-side fix in
        // deleteMessages — required for Clear Chat to actually clear.
        let selSql = "SELECT id FROM compact_markers WHERE session_id = ?"
        var selStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, selSql, -1, &selStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(selStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            while sqlite3_step(selStmt) == SQLITE_ROW {
                let cmId = String(cString: sqlite3_column_text(selStmt, 0))
                markDirty(recordType: "CompactMarker", recordId: cmId, operation: "delete")
            }
        }
        sqlite3_finalize(selStmt)

        let sql = "DELETE FROM compact_markers WHERE session_id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    // MARK: - WebApp Shortcuts

    /// All shortcuts, newest first. Inlined here (rather than in a sibling
    /// file) so it can read the actor-private `db` and `bindOptionalText`
    /// helpers without widening their visibility.
    func listWebAppShortcuts() -> [WebAppShortcut] {
        let sql = """
            SELECT id, html_path, path_scope, scope_context, title,
                   icon_ref, icon_cache_path, created_at, source_session_id
              FROM webapp_shortcuts
             ORDER BY created_at DESC
        """
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
        var out: [WebAppShortcut] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            if let row = decodeWebAppShortcutRow(stmt) {
                out.append(row)
            }
        }
        return out
    }

    /// Lookup by id. Returns nil if the row was removed (manually deleted
    /// in-app or pruned because the source session no longer exists).
    func webAppShortcut(id: String) -> WebAppShortcut? {
        let sql = """
            SELECT id, html_path, path_scope, scope_context, title,
                   icon_ref, icon_cache_path, created_at, source_session_id
              FROM webapp_shortcuts
             WHERE id = ?
        """
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        return decodeWebAppShortcutRow(stmt)
    }

    /// Insert or replace by id. Last-write-wins semantics — the home-screen
    /// tile only references id so this is the safe operation. Old icon
    /// bitmaps at a previous `iconCachePath` should be cleaned up by the
    /// caller before invoking this; the row simply records the new pointer.
    func saveWebAppShortcut(_ shortcut: WebAppShortcut) {
        let sql = """
            INSERT OR REPLACE INTO webapp_shortcuts
                (id, html_path, path_scope, scope_context, title,
                 icon_ref, icon_cache_path, created_at, source_session_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
            logger.error("saveWebAppShortcut prepare failed: \(String(cString: sqlite3_errmsg(db)))")
            return
        }
        sqlite3_bind_text(stmt, 1, (shortcut.id as NSString).utf8String, -1, nil)
        sqlite3_bind_text(stmt, 2, (shortcut.htmlPath as NSString).utf8String, -1, nil)
        sqlite3_bind_text(stmt, 3, (shortcut.pathScope.rawValue as NSString).utf8String, -1, nil)
        bindOptionalText(stmt, index: 4, value: shortcut.scopeContext)
        sqlite3_bind_text(stmt, 5, (shortcut.title as NSString).utf8String, -1, nil)
        sqlite3_bind_text(stmt, 6, (shortcut.iconRef.encoded as NSString).utf8String, -1, nil)
        bindOptionalText(stmt, index: 7, value: shortcut.iconCachePath)
        sqlite3_bind_double(stmt, 8, shortcut.createdAt.timeIntervalSince1970)
        bindOptionalText(stmt, index: 9, value: shortcut.sourceSessionId)
        if sqlite3_step(stmt) != SQLITE_DONE {
            logger.error("saveWebAppShortcut step failed: \(String(cString: sqlite3_errmsg(db)))")
        }
    }

    /// Delete by id. Caller is responsible for removing any cached icon
    /// bitmap from `Library/WebAppIconCache/`.
    func deleteWebAppShortcut(id: String) {
        let sql = "DELETE FROM webapp_shortcuts WHERE id = ?"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return }
        sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
        _ = sqlite3_step(stmt)
    }

    private func decodeWebAppShortcutRow(_ stmt: OpaquePointer?) -> WebAppShortcut? {
        guard let stmt else { return nil }
        guard let cId = sqlite3_column_text(stmt, 0),
              let cHtml = sqlite3_column_text(stmt, 1),
              let cScope = sqlite3_column_text(stmt, 2),
              let cTitle = sqlite3_column_text(stmt, 4),
              let cIcon = sqlite3_column_text(stmt, 5)
        else { return nil }
        let scopeStr = String(cString: cScope)
        guard let scope = WebAppPathScope(rawValue: scopeStr) else {
            logger.warning("decodeWebAppShortcutRow: unknown scope=\(scopeStr) — dropping")
            return nil
        }
        let scopeCtx = sqlite3_column_text(stmt, 3).map { String(cString: $0) }
        let iconCache = sqlite3_column_text(stmt, 6).map { String(cString: $0) }
        let createdAt = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 7))
        let srcSession = sqlite3_column_text(stmt, 8).map { String(cString: $0) }
        return WebAppShortcut(
            id: String(cString: cId),
            htmlPath: String(cString: cHtml),
            pathScope: scope,
            scopeContext: scopeCtx,
            title: String(cString: cTitle),
            iconRef: WebAppIconRef(encoded: String(cString: cIcon)),
            iconCachePath: iconCache,
            createdAt: createdAt,
            sourceSessionId: srcSession
        )
    }

    /// Maximum messages per session. Oldest messages beyond this limit are
    /// deleted after each appendMessages call.
    private static let pruneThreshold = 2048

    /// Delete the oldest messages when a session exceeds `pruneThreshold`.
    ///
    /// Safety rules:
    /// - Only runs when message count strictly exceeds the threshold.
    /// - Uses `DELETE ... WHERE id IN (SELECT id ... LIMIT N)` to delete an
    ///   exact number of rows, immune to duplicate sort_order values.
    /// - Never deletes more than `totalCount - pruneThreshold` rows.
    /// - Verifies the count-to-delete is positive and less than totalCount
    ///   (always keeps at least `pruneThreshold` messages).
    /// - Also cleans up stale compact markers.
    func pruneOldMessages(sessionId: String) {
        // 1. Count total messages
        var countStmt: OpaquePointer?
        let countSql = "SELECT COUNT(*) FROM messages WHERE session_id = ?"
        guard sqlite3_prepare_v2(db, countSql, -1, &countStmt, nil) == SQLITE_OK else { return }
        sqlite3_bind_text(countStmt, 1, (sessionId as NSString).utf8String, -1, nil)
        guard sqlite3_step(countStmt) == SQLITE_ROW else {
            sqlite3_finalize(countStmt)
            return
        }
        let totalCount = Int(sqlite3_column_int64(countStmt, 0))
        sqlite3_finalize(countStmt)

        // Phase A: DO NOT call deleteOldCompactMarkers here. Compact markers are
        // append-only archive and must survive appendMessages. Old markers are only
        // removed when the session itself is deleted (deleteSession path).

        guard totalCount > Self.pruneThreshold else { return }

        let deleteCount = totalCount - Self.pruneThreshold

        // 2. Safety: deleteCount must be positive and must keep at least pruneThreshold rows
        guard deleteCount > 0 && deleteCount < totalCount else {
            logger.warning("[Prune] Unexpected deleteCount=\(deleteCount) total=\(totalCount) — skipping")
            return
        }

        // 3. Delete exactly deleteCount oldest rows by id (immune to sort_order duplicates)
        let delSql = """
            DELETE FROM messages WHERE id IN (
                SELECT id FROM messages WHERE session_id = ?
                ORDER BY sort_order ASC, created_at ASC, id ASC
                LIMIT ?
            )
        """
        var delStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, delSql, -1, &delStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(delStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            sqlite3_bind_int64(delStmt, 2, Int64(deleteCount))
            sqlite3_step(delStmt)
        }
        sqlite3_finalize(delStmt)

        let deleted = Int(sqlite3_changes(db))
        logger.info("[Prune] session \(sessionId.prefix(8)): deleted \(deleted) oldest messages (was \(totalCount), threshold \(Self.pruneThreshold))")
    }

    /// Delete all compact markers except the latest one.
    private func deleteOldCompactMarkers(sessionId: String) {
        let sql = """
            DELETE FROM compact_markers WHERE session_id = ? AND id != (
                SELECT id FROM compact_markers WHERE session_id = ? ORDER BY created_at DESC LIMIT 1
            )
        """
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (sessionId as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    // MARK: - Session Repair (Phase A)

    /// Return value for session repair operations.
    struct RepairReport {
        var sortOrderFixed: Int = 0
        var markersUpgraded: Int = 0
        var hadAnomaly: Bool { sortOrderFixed > 0 || markersUpgraded > 0 }
    }

    /// Cheap anomaly check: returns true if the session has duplicate sort_orders
    /// OR any compact marker lacks `first_kept_message_id`. Used to skip expensive
    /// repair when the session is already healthy.
    private func sessionNeedsRepair(sessionId: String) -> Bool {
        // 1. sortOrder duplicates
        let dupSql = "SELECT COUNT(*), COUNT(DISTINCT sort_order) FROM messages WHERE session_id = ?"
        var dupStmt: OpaquePointer?
        var hasDup = false
        if sqlite3_prepare_v2(db, dupSql, -1, &dupStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(dupStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            if sqlite3_step(dupStmt) == SQLITE_ROW {
                let total = sqlite3_column_int64(dupStmt, 0)
                let unique = sqlite3_column_int64(dupStmt, 1)
                if total != unique { hasDup = true }
            }
        }
        sqlite3_finalize(dupStmt)
        if hasDup { return true }

        // 2. any marker without first_kept_message_id AND without last_compacted_message_id.
        // compactAll markers intentionally have first_kept_message_id = NULL (and a non-null
        // last_compacted_message_id) — they must NOT be treated as legacy/needing repair, or
        // step 3 below would overwrite them with a bogus message id from past-the-end sortOrder.
        let markerSql = "SELECT 1 FROM compact_markers WHERE session_id = ? AND first_kept_message_id IS NULL AND last_compacted_message_id IS NULL LIMIT 1"
        var mStmt: OpaquePointer?
        var hasLegacyMarker = false
        if sqlite3_prepare_v2(db, markerSql, -1, &mStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(mStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            hasLegacyMarker = sqlite3_step(mStmt) == SQLITE_ROW
        }
        sqlite3_finalize(mStmt)
        if hasLegacyMarker { return true }

        // 3. rank inversion: dense 0..n-1 expected by canonical (created_at, id) order.
        // Catches the case where a remote merge inserted with a hint integer that
        // didn't match local rank (legacy data from pre-fix builds), or where any
        // other pathway left holes/out-of-order rows.
        let invSql = """
            SELECT 1 FROM (
              SELECT sort_order,
                     ROW_NUMBER() OVER (ORDER BY created_at ASC, id ASC) - 1 AS expected
              FROM messages WHERE session_id = ?
            ) WHERE sort_order != expected LIMIT 1
        """
        var invStmt: OpaquePointer?
        var hasInversion = false
        if sqlite3_prepare_v2(db, invSql, -1, &invStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(invStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            hasInversion = sqlite3_step(invStmt) == SQLITE_ROW
        }
        sqlite3_finalize(invStmt)
        return hasInversion
    }

    /// Perform a session repair: rewrite sort_orders to a stable dense sequence and
    /// upgrade legacy compact markers (nil first_kept_message_id) by looking up the
    /// corresponding row via legacy firstKeptSortOrder.
    ///
    /// Safe to call unconditionally — no-op if session is already healthy.
    /// Wraps everything in a single transaction.
    @discardableResult
    func repairSession(sessionId: String) -> RepairReport {
        invalidateSessionListCache()
        var report = RepairReport()

        exec("BEGIN TRANSACTION")

        // Step 1: reload messages by a stable composite key to drive sortOrder rewrite.
        // Order by (created_at, id) ignoring the current sort_order entirely — this gives
        // us a canonical sequence independent of any existing duplicates.
        let selectSql = "SELECT id, sort_order FROM messages WHERE session_id = ? ORDER BY created_at ASC, id ASC"
        var rows: [(id: String, oldSortOrder: Int)] = []
        var selStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, selectSql, -1, &selStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(selStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            while sqlite3_step(selStmt) == SQLITE_ROW {
                let idC = sqlite3_column_text(selStmt, 0).map { String(cString: $0) } ?? ""
                let so = Int(sqlite3_column_int64(selStmt, 1))
                rows.append((id: idC, oldSortOrder: so))
            }
        }
        sqlite3_finalize(selStmt)

        // Step 2: compute new sortOrders and update any that changed.
        // We assign 0..n-1 dense, using the canonical (created_at, id) order.
        let updateSql = "UPDATE messages SET sort_order = ? WHERE id = ?"
        var updStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, updateSql, -1, &updStmt, nil) == SQLITE_OK {
            for (newSO, row) in rows.enumerated() where row.oldSortOrder != newSO {
                sqlite3_reset(updStmt)
                sqlite3_bind_int(updStmt, 1, Int32(newSO))
                sqlite3_bind_text(updStmt, 2, (row.id as NSString).utf8String, -1, nil)
                sqlite3_step(updStmt)
                report.sortOrderFixed += 1
            }
        }
        sqlite3_finalize(updStmt)

        // Step 3: upgrade legacy markers. Build a (oldSortOrder -> id) map first, then
        // find each nil-firstKeptMessageId marker and fill it in using firstKeptSortOrder.
        // NOTE: this lookup uses the NEW sortOrder (we just rewrote it), so we build
        // the map from the rewritten enumeration.
        var newSoToId: [Int: String] = [:]
        for (newSO, row) in rows.enumerated() { newSoToId[newSO] = row.id }
        // But legacy markers were written with OLD sortOrders. If rewrites happened,
        // the legacy firstKeptSortOrder may no longer point to anything sensible.
        // Strategy: try (a) newSO map, then (b) find old row whose OLD sort_order == legacy value.
        // Because step 2 just changed the sort_orders, approach (b) needs to query the
        // pre-update state — but we've already committed in memory. Instead, use the
        // original `rows` list: row index i now corresponds to new sort_order i. Find
        // any row where row.oldSortOrder == legacy value as a second-chance lookup.
        var oldSoToId: [Int: String] = [:]
        for row in rows { oldSoToId[row.oldSortOrder] = row.id }  // last write wins on dup

        // Only true legacy markers (no id-based fields at all) are upgraded.
        // compactAll markers (first_kept_message_id NULL but last_compacted_message_id set)
        // are intentional — leave them alone.
        let markerSelSql = "SELECT id, first_kept_sort_order FROM compact_markers WHERE session_id = ? AND first_kept_message_id IS NULL AND last_compacted_message_id IS NULL"
        var mSelStmt: OpaquePointer?
        var legacyMarkers: [(id: String, firstKeptSortOrder: Int)] = []
        if sqlite3_prepare_v2(db, markerSelSql, -1, &mSelStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(mSelStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            while sqlite3_step(mSelStmt) == SQLITE_ROW {
                let mid = sqlite3_column_text(mSelStmt, 0).map { String(cString: $0) } ?? ""
                let fkso = Int(sqlite3_column_int64(mSelStmt, 1))
                legacyMarkers.append((id: mid, firstKeptSortOrder: fkso))
            }
        }
        sqlite3_finalize(mSelStmt)

        let mUpdSql = "UPDATE compact_markers SET first_kept_message_id = ? WHERE id = ?"
        var mUpdStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, mUpdSql, -1, &mUpdStmt, nil) == SQLITE_OK {
            for lm in legacyMarkers {
                // Try new map first (post-rewrite), then old map (pre-rewrite).
                let resolved = newSoToId[lm.firstKeptSortOrder] ?? oldSoToId[lm.firstKeptSortOrder]
                guard let fkmId = resolved else {
                    logger.warning("[Repair] marker id=\(lm.id.prefix(8)) firstKeptSortOrder=\(lm.firstKeptSortOrder) has no matching row — cannot upgrade")
                    continue
                }
                sqlite3_reset(mUpdStmt)
                sqlite3_bind_text(mUpdStmt, 1, (fkmId as NSString).utf8String, -1, nil)
                sqlite3_bind_text(mUpdStmt, 2, (lm.id as NSString).utf8String, -1, nil)
                sqlite3_step(mUpdStmt)
                report.markersUpgraded += 1
                // Mark marker as dirty so the upgraded field propagates to iCloud.
                markDirty(recordType: "CompactMarker", recordId: lm.id)
            }
        }
        sqlite3_finalize(mUpdStmt)

        exec("COMMIT")

        if report.hadAnomaly {
            logger.warning("[Repair] session=\(sessionId.prefix(8)) sortOrderFixed=\(report.sortOrderFixed) markersUpgraded=\(report.markersUpgraded) totalRows=\(rows.count)")
        }
        return report
    }

    /// Cheap-check-then-repair. Safe to call on every session open; the check
    /// is a single SQL per session.
    @discardableResult
    func repairSessionIfNeeded(sessionId: String) -> RepairReport {
        guard sessionNeedsRepair(sessionId: sessionId) else { return RepairReport() }
        return repairSession(sessionId: sessionId)
    }

    /// Read a CompactMarker from a prepared statement at current row.
    /// Column order: id(0), session_id(1), summary(2), first_kept_sort_order(3),
    /// compacted_count(4), created_at(5), ui_boundary_sort_order(6),
    /// boundary_message_id(7), first_kept_message_id(8), last_compacted_message_id(9),
    /// version(10).
    private func readCompactMarker(from stmt: OpaquePointer?) -> CompactMarker {
        let uiBoundary: Int? = sqlite3_column_type(stmt, 6) != SQLITE_NULL
            ? Int(sqlite3_column_int64(stmt, 6))
            : nil
        let bmId: String? = sqlite3_column_type(stmt, 7) != SQLITE_NULL
            ? String(cString: sqlite3_column_text(stmt, 7))
            : nil
        let fkmId: String? = sqlite3_column_type(stmt, 8) != SQLITE_NULL
            ? String(cString: sqlite3_column_text(stmt, 8))
            : nil
        let lcmId: String? = sqlite3_column_type(stmt, 9) != SQLITE_NULL
            ? String(cString: sqlite3_column_text(stmt, 9))
            : nil
        // version may be absent on rows written before the column was added —
        // ALTER TABLE ... DEFAULT 1 covers the SQL side, but defensive fallback
        // here keeps tests and tooling happy.
        let version: Int = sqlite3_column_type(stmt, 10) != SQLITE_NULL
            ? Int(sqlite3_column_int64(stmt, 10))
            : 1
        return CompactMarker(
            id: String(cString: sqlite3_column_text(stmt, 0)),
            sessionId: String(cString: sqlite3_column_text(stmt, 1)),
            summary: String(cString: sqlite3_column_text(stmt, 2)),
            firstKeptSortOrder: Int(sqlite3_column_int64(stmt, 3)),
            compactedCount: Int(sqlite3_column_int64(stmt, 4)),
            createdAt: Date(timeIntervalSince1970: sqlite3_column_double(stmt, 5)),
            uiBoundarySortOrder: uiBoundary,
            boundaryMessageId: bmId,
            firstKeptMessageId: fkmId,
            lastCompactedMessageId: lcmId,
            version: version
        )
    }

    // MARK: - Usage Statistics

    /// A single usage record from a message with token usage data.
    struct UsageRecord {
        let modelId: String
        let sessionId: String
        let date: Date
        let usage: StoredTokenUsage
        /// [T-token-attribution-snapshot] Display name captured at write time.
        /// Non-nil only when `hasSnapshot` — and then it is the only reliable
        /// name for a model whose provider has since been deleted.
        let modelDisplayName: String?
        /// `ProviderType` rawValue captured at write time.
        let providerType: String?
        /// True when this row carries a per-message snapshot. False means the
        /// model was inferred from the session's CURRENT model and may be
        /// wrong; the UI must present it as an estimate.
        let hasSnapshot: Bool
    }

    /// Fetch all messages that have token usage, joined with their session's model_id.
    /// [T-ios-usage-orphan-rows GH#168] LEFT JOIN, not INNER JOIN.
    ///
    /// The Usage page is built entirely from this query, so any row it drops is
    /// silently missing from the user's reported totals. With an INNER JOIN, a
    /// message whose `sessions` row is gone — deleted, orphaned by a failed
    /// sync/migration, or never written — vanished from the totals even though
    /// its `token_usage` is still sitting in the table. The tokens were really
    /// billed by the provider, so the page under-reported with no way to tell.
    ///
    /// LEFT JOIN keeps those rows; `model_id` then comes back NULL, which
    /// `UsageStatsView` already handles (unknown ids fall back to the raw id
    /// for the display name and to the "Other" provider group).
    ///
    /// This does NOT recover usage from sessions the user deleted outright —
    /// `deleteSession` removes the message rows themselves (:2960). It only
    /// stops orphaned-but-present rows from being discarded.
    func fetchUsageStats() -> [UsageRecord] {
        // [T-token-attribution-snapshot] Prefer the per-message snapshot; fall
        // back to the session's model only for rows written before it existed.
        // `s.model_id` alone is what caused a session's whole history to be
        // re-attributed on every model switch — it is a mutable column and the
        // join has no time dimension. `has_snapshot` travels with the row so
        // the UI can label fallback rows as estimates instead of passing them
        // off as measurements.
        let sql = """
            SELECT COALESCE(m.model_id, s.model_id), m.token_usage, m.created_at, m.session_id,
                   m.model_display_name, m.provider_type, (m.model_id IS NOT NULL)
            FROM messages m LEFT JOIN sessions s ON m.session_id = s.id
            WHERE m.token_usage IS NOT NULL
        """
        var stmt: OpaquePointer?
        var records: [UsageRecord] = []

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            while sqlite3_step(stmt) == SQLITE_ROW {
                // `model_id` is NULL for an orphaned message (no sessions row).
                // sqlite3_column_text returns a NULL pointer there, and
                // String(cString:) on NULL is undefined behaviour — so the
                // LEFT JOIN above REQUIRES this guarded read, not the bare
                // String(cString:) the INNER JOIN could get away with.
                let modelId = sqlite3_column_text(stmt, 0).map { String(cString: $0) } ?? ""
                guard let usagePtr = sqlite3_column_text(stmt, 1) else { continue }
                let usageStr = String(cString: usagePtr)
                let createdAt = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 2))
                let sessionId = sqlite3_column_text(stmt, 3).map { String(cString: $0) } ?? ""
                let displayName = sqlite3_column_text(stmt, 4).map { String(cString: $0) }
                let providerType = sqlite3_column_text(stmt, 5).map { String(cString: $0) }
                let hasSnapshot = sqlite3_column_int(stmt, 6) != 0

                guard let usageData = usageStr.data(using: .utf8),
                      let usage = try? JSONDecoder().decode(StoredTokenUsage.self, from: usageData) else {
                    continue
                }

                records.append(UsageRecord(
                    modelId: modelId, sessionId: sessionId,
                    date: createdAt, usage: usage,
                    modelDisplayName: displayName, providerType: providerType,
                    hasSnapshot: hasSnapshot
                ))
            }
        }
        sqlite3_finalize(stmt)
        return records
    }

    /// Return the model_id most frequently used (by message count with token usage) since `date`.
    func fetchMostUsedModelId(since date: Date) -> String? {
        // [T-token-attribution-snapshot] Same fix as fetchUsageStats: group by
        // the per-message snapshot where present. Grouping purely on
        // `s.model_id` made "most used" follow whatever each session currently
        // points at rather than what actually ran.
        let sql = """
            SELECT COALESCE(m.model_id, s.model_id) AS mid, COUNT(*) as cnt
            FROM messages m JOIN sessions s ON m.session_id = s.id
            WHERE m.created_at >= ? AND m.token_usage IS NOT NULL
            GROUP BY mid
            ORDER BY cnt DESC
            LIMIT 1
        """
        var stmt: OpaquePointer?
        var result: String?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_double(stmt, 1, date.timeIntervalSince1970)
            if sqlite3_step(stmt) == SQLITE_ROW,
               let cStr = sqlite3_column_text(stmt, 0) {
                result = String(cString: cStr)
            }
        }
        sqlite3_finalize(stmt)
        return result
    }

    // MARK: - Private Helpers

    private func nextSortOrder(sessionId: String) -> Int {
        let sql = "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM messages WHERE session_id = ?"
        var stmt: OpaquePointer?
        var order = 0
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW {
                order = Int(sqlite3_column_int64(stmt, 0))
            }
        }
        sqlite3_finalize(stmt)
        return order
    }

    private func touchSession(_ sessionId: String) {
        invalidateSessionListCache()
        let sql = "UPDATE sessions SET updated_at = ? WHERE id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_double(stmt, 1, Date().timeIntervalSince1970)
            sqlite3_bind_text(stmt, 2, (sessionId as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    private func bindOptionalText(_ stmt: OpaquePointer?, index: Int32, value: String?) {
        if let value {
            sqlite3_bind_text(stmt, index, (value as NSString).utf8String, -1, nil)
        } else {
            sqlite3_bind_null(stmt, index)
        }
    }

    static func fileExtension(for mimeType: String) -> String {
        switch mimeType.lowercased() {
        case "image/jpeg": return "jpg"
        case "image/png": return "png"
        case "image/gif": return "gif"
        case "image/webp": return "webp"
        case "application/pdf": return "pdf"
        case "text/plain": return "txt"
        case "text/html": return "html"
        case "application/json": return "json"
        default: return "bin"
        }
    }

    // MARK: - Sync Zone Name

    /// Zone name for dirty record tracking. Set by CloudSyncEngine on init.
    var syncZoneName: String = ""

    func setSyncZoneName(_ name: String) {
        syncZoneName = name
    }

    // MARK: - Deferred Sync Dirty Marks

    /// When true, markDirty writes to the dirty table but does NOT trigger scheduleSend.
    /// Set during agent processing to defer CloudKit upload triggers while streaming.
    /// Dirty marks are always written to DB immediately (crash-safe); only the
    /// scheduleSend() call is deferred until the agent loop finishes.
    private(set) var syncSendDeferred = false

    func setSyncSendDeferred(_ value: Bool) {
        syncSendDeferred = value
    }

    /// Session IDs touched during agent processing — used to scan file directories
    /// for changes when the agent loop finishes.
    private var pendingSyncSessionIds = Set<String>()

    /// Flush deferred sync: scan session file directories and trigger CloudKit upload.
    /// Dirty marks for messages/sessions are already persisted in DB (written at
    /// appendMessage/touchSession time), so this only handles file scanning and
    /// the final scheduleSend trigger.
    func flushPendingSyncDirty() {
        syncSendDeferred = false
        let f0 = CFAbsoluteTimeGetCurrent()
        var fileCount = 0
        for sessionId in pendingSyncSessionIds {
            fileCount += scanAndMarkSessionFiles(sessionId: sessionId)
        }
        let f1 = CFAbsoluteTimeGetCurrent()
        let sessionCount = pendingSyncSessionIds.count
        pendingSyncSessionIds.removeAll()
        iCloudLogger.info("[iCloud] flushPendingSyncDirty: \(sessionCount) sessions, \(fileCount) files — scanFiles=\(String(format: "%.0f", (f1-f0)*1000))ms")
        // Trigger a single debounced send for all the dirty records
        if #available(iOS 17.0, *) {
            Task { @MainActor in
                CloudSyncEngine.shared.scheduleSend()
            }
        }
    }

    // Directories to skip when scanning session files for iCloud sync.
    // These contain build artifacts, caches, and VCS internals that should not be synced.
    private static let syncSkipDirNames: Set<String> = [
        "__pycache__", ".git", ".svn", ".hg",
        "node_modules", ".tox", ".venv", "venv",
        ".build", ".eggs",
        ".mypy_cache", ".pytest_cache", ".ruff_cache",
        "__pypackages__", ".gradle", ".idea",
    ]

    /// Scan workspace/offloads/attachments dirs for a session and markDirty for each file.
    /// Returns number of files marked.
    @discardableResult
    private func scanAndMarkSessionFiles(sessionId: String, priority: Int = 0) -> Int {
        let fm = FileManager.default
        let library = fm.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let sessionDir = library.appendingPathComponent("MinisChat/minis/\(sessionId)")
        guard fm.fileExists(atPath: sessionDir.path) else { return 0 }
        // Standardize to resolve /private/var vs /var symlink differences
        let sessionDirStd = sessionDir.standardizedFileURL.path

        let subdirs = ["workspace", "offloads", "attachments", "browser"]
        var count = 0
        var subdirCounts: [String: Int] = [:]
        var totalBytes: Int = 0
        var skippedOversize = 0
        var skippedDisabled = 0
        var skippedJunkDir = 0
        for subdir in subdirs {
            let dirURL = sessionDir.appendingPathComponent(subdir)
            guard let enumerator = fm.enumerator(
                at: dirURL,
                includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .isDirectoryKey],
                options: [.skipsHiddenFiles]
            ) else { continue }
            var subdirCount = 0
            for case let fileURL as URL in enumerator {
                // Skip entire junk directories (build artifacts, caches, VCS internals)
                let isDir = (try? fileURL.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true
                if isDir {
                    if Self.syncSkipDirNames.contains(fileURL.lastPathComponent) {
                        enumerator.skipDescendants()
                        skippedJunkDir += 1
                    }
                    continue
                }
                guard (try? fileURL.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true else { continue }
                let fileSize = (try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                // Skip files exceeding max sync size (single source of truth: UploadPolicy)
                let maxSize = UploadPolicy.maxFileSizeBytes
                if fileSize > maxSize { skippedOversize += 1; continue }
                // recordId format: "sessionId:subdir/relativePath"
                let fileStd = fileURL.standardizedFileURL.path
                let prefix = sessionDirStd + "/"
                let relativePath = fileStd.hasPrefix(prefix) ? String(fileStd.dropFirst(prefix.count)) : fileURL.lastPathComponent
                markDirty(recordType: "SessionFile", recordId: "\(sessionId):\(relativePath)", priority: priority)
                count += 1
                subdirCount += 1
                totalBytes += fileSize
            }
            if subdirCount > 0 { subdirCounts[subdir] = subdirCount }
        }
        if count > 0 || skippedOversize > 0 || skippedJunkDir > 0 {
            let breakdown = subdirCounts.sorted { $0.value > $1.value }.map { "\($0.key)=\($0.value)" }.joined(separator: " ")
            iCloudLogger.info("[iCloud] ⏱ scanAndMarkSessionFiles[\(sessionId.prefix(8))]: \(count) files (\(totalBytes / 1024)KB) cap=\(UploadPolicy.maxFileSizeBytes / 1024)KB skippedOversize=\(skippedOversize) skippedJunkDirs=\(skippedJunkDir) [\(breakdown)]")
        }
        _ = skippedDisabled
        return count
    }
}

// MARK: - Anthropic Conversion

extension Array where Element == ContentPart {
    /// Convert to Anthropic MessageParameter.Message.Content,
    /// loading media files as base64 via the store.
    func toAnthropicContent(store: ChatStore) async -> MessageParameter.Message.Content {
        var objects: [MessageParameter.Message.Content.ContentObject] = []

        for part in self {
            switch part {
            case .text(let s):
                objects.append(.text(s))

            case .mediaRef(let ref):
                if let data = await store.loadMediaData(ref) {
                    let base64 = data.base64EncodedString()
                    let mediaType = anthropicMediaType(for: ref.mimeType)
                    objects.append(.image(.init(type: .base64, mediaType: mediaType, data: base64)))
                }

            case .toolUse(let tu):
                let input = parseJSONToDynamicContent(tu.input)
                objects.append(.toolUse(tu.toolUseId, tu.name, input))

            case .toolResult(let tr):
                objects.append(.toolResult(tr.toolUseId, tr.output, isError: !tr.success))
            }
        }

        if objects.count == 1, case .text(let s) = objects[0] {
            return .text(s)
        }
        return .list(objects)
    }
}

private func anthropicMediaType(for mimeType: String) -> MessageParameter.Message.Content.ImageSource.MediaType {
    switch mimeType.lowercased() {
    case "image/png": return .png
    case "image/gif": return .gif
    case "image/webp": return .webp
    default: return .jpeg
    }
}

/// Convert JSON string to Anthropic's DynamicContent input format.
private func parseJSONToDynamicContent(_ json: String) -> MessageResponse.Content.Input {
    guard let data = json.data(using: .utf8),
          let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        return ["input": .string(json)]
    }
    var result: MessageResponse.Content.Input = [:]
    for (key, value) in dict {
        if let s = value as? String {
            result[key] = .string(s)
        } else if let n = value as? NSNumber {
            if CFBooleanGetTypeID() == CFGetTypeID(n) {
                result[key] = .bool(n.boolValue)
            } else {
                result[key] = .integer(n.intValue)
            }
        }
    }
    return result
}

private func parseJSONToDict(_ json: String) -> [String: Any] {
    guard let data = json.data(using: .utf8),
          let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        return [:]
    }
    return dict
}

// MARK: - Gemini Conversion

extension Array where Element == ContentPart {
    /// Convert to Gemini parts array [[String: Any]],
    /// loading media files as base64 via the store.
    func toGeminiParts(store: ChatStore) async -> [[String: Any]] {
        var parts: [[String: Any]] = []

        for part in self {
            switch part {
            case .text(let s):
                parts.append(["text": s])

            case .mediaRef(let ref):
                if let data = await store.loadMediaData(ref) {
                    let base64 = data.base64EncodedString()
                    parts.append([
                        "inlineData": [
                            "mimeType": ref.mimeType,
                            "data": base64,
                        ]
                    ])
                }

            case .toolUse(let tu):
                let args = parseJSONToDict(tu.input)
                parts.append(GeminiConversation.functionCallPart(
                    name: tu.name, args: args
                ))

            case .toolResult(let tr):
                var response: [String: Any] = [
                    "output": tr.output,
                    "success": tr.success,
                ]
                if let ref = tr.mediaRef, let data = await store.loadMediaData(ref) {
                    response["image_base64"] = data.base64EncodedString()
                    response["image_mime_type"] = ref.mimeType
                }
                parts.append(GeminiConversation.functionResponsePart(
                    name: "", response: response
                ))
            }
        }

        return parts
    }
}

// MARK: - UI Conversion

extension RawMessage {
    /// Convert to ChatMessage for UI display.
    /// Images render from file URL — no base64 loading needed.
    func toChatMessage(mediaResolver: (MediaRef) -> URL, showThinking: Bool = true) -> ChatMessage {
        let uiRole: ChatMessageRole = role == .user ? .user : .assistant

        // Build display content from parts
        var textContent = ""
        var blocks: [AssistantBlock] = []
        var userAttachments: [AttachmentMeta] = []

        // ISO8601 parser for attachment metadata
        let isoFormatter = ISO8601DateFormatter()
        isoFormatter.formatOptions = [.withInternetDateTime]

        /// Track the last known browser URL across tool blocks so actions like
        /// get_readable (which don't carry a url arg) can inherit it.
        var lastBrowserURL: String?

        for part in parts {
            switch part {
            case .text(let s):
                if role == .assistant {
                    let visible = Self.stripSystemReminders(s)
                    if !visible.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        // [T-ios-longmsg-cell-split] Split a very long reply into
                        // several text blocks instead of one enormous cell.
                        //
                        // A contiguous assistant reply used to become exactly ONE
                        // `.text` block no matter how long, and the message list
                        // gives each block its own cell — so a 9156-char answer
                        // became a single 20615pt cell backed by one UITextView.
                        // Field log from the render-collapse report shows exactly
                        // that shape: `blocks=288` markdown blocks all inside one
                        // AssistantBlock, and `visibleCells=1` for the whole
                        // 50559pt list while dragging.
                        //
                        // Splitting keeps EVERY character — this is not a clip.
                        // It only changes how many cells carry them, which bounds
                        // each UITextView/CALayer and lets UICollectionView
                        // recycle off-screen parts (the same reasoning as
                        // PaginatedMarkdownView, whose fence-aware splitter is
                        // reused here rather than reimplemented).
                        //
                        // Load-time only, on purpose: this is the DB path, so a
                        // message is split once it is finished and persisted.
                        // The streaming path builds its blocks in memory and is
                        // untouched, which is what keeps split points from
                        // shifting under a growing message.
                        for segment in Self.splitLongAssistantText(visible) {
                            blocks.append(AssistantBlock(kind: .text, content: segment))
                        }
                    }
                }
                // Extract structured attachment metadata from <user-attached-files> XML
                // and strip it from user-visible display text
                var displayStr = s
                if role == .user, s.contains("<user-attached-files>") {
                    let openTag = "<user-attached-files>"
                    let closeTag = "</user-attached-files>"
                    if let range = s.range(of: openTag) {
                        let endBound: String.Index
                        if let endRange = s.range(of: closeTag) {
                            endBound = endRange.upperBound
                            let body = String(s[range.upperBound..<endRange.lowerBound])

                            if body.contains("<file ") {
                                // New XML format: <file path="..." url="..." size="..." modified="..." />
                                let filePattern = try? NSRegularExpression(
                                    pattern: #"<file\s+path="([^"]*)"(?:\s+url="([^"]*)")?(?:\s+size="([^"]*)")?(?:\s+modified="([^"]*)")?\s*/>"#
                                )
                                let nsBody = body as NSString
                                let matches = filePattern?.matches(in: body, range: NSRange(location: 0, length: nsBody.length)) ?? []
                                for match in matches {
                                    let path = match.range(at: 1).location != NSNotFound ? nsBody.substring(with: match.range(at: 1)) : ""
                                    let sizeStr = match.range(at: 3).location != NSNotFound ? nsBody.substring(with: match.range(at: 3)) : "0"
                                    let modStr = match.range(at: 4).location != NSNotFound ? nsBody.substring(with: match.range(at: 4)) : ""
                                    if !path.isEmpty {
                                        userAttachments.append(AttachmentMeta(
                                            path: path,
                                            size: Int(sizeStr) ?? 0,
                                            modified: isoFormatter.date(from: modStr) ?? self.createdAt
                                        ))
                                    }
                                }
                            } else {
                                // Legacy YAML format: - path: ...
                                var currentPath: String?
                                var currentSize: Int?
                                var currentModified: Date?
                                for line in body.components(separatedBy: "\n") {
                                    let trimmed = line.trimmingCharacters(in: .whitespaces)
                                    if trimmed.hasPrefix("- path: ") {
                                        if let path = currentPath {
                                            userAttachments.append(AttachmentMeta(
                                                path: path,
                                                size: currentSize ?? 0,
                                                modified: currentModified ?? self.createdAt
                                            ))
                                        }
                                        currentPath = String(trimmed.dropFirst("- path: ".count))
                                        currentSize = nil
                                        currentModified = nil
                                    } else if trimmed.hasPrefix("size: ") {
                                        currentSize = Int(trimmed.dropFirst("size: ".count))
                                    } else if trimmed.hasPrefix("modified: ") {
                                        currentModified = isoFormatter.date(from: String(trimmed.dropFirst("modified: ".count)))
                                    }
                                }
                                if let path = currentPath {
                                    userAttachments.append(AttachmentMeta(
                                        path: path,
                                        size: currentSize ?? 0,
                                        modified: currentModified ?? self.createdAt
                                    ))
                                }
                            }
                        } else {
                            // No closing tag — strip from opening tag to end
                            endBound = s.endIndex
                        }
                        displayStr = String(s[s.startIndex..<range.lowerBound] + s[endBound...])
                            .trimmingCharacters(in: .whitespacesAndNewlines)
                    }
                }
                // Strip model-facing attachment markers from user-visible text.
                // These are generated in AIChatViewModel when building the LLM payload
                // (`[attached image: /var/minis/...]` next to inline image data, and
                // `[image omitted to save context — ...]` when eviction replaces
                // image bytes with a text placeholder). The UI already renders the
                // real attachments via `msg.attachments` parsed from the
                // <user-attached-files> XML above, so leaving these markers in the
                // display string produces the duplicated raw-path text users see
                // after reloading history.
                if role == .user && !displayStr.isEmpty {
                    displayStr = Self.stripSystemReminders(displayStr)
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                }
                if role == .user && !displayStr.isEmpty {
                    displayStr = Self.stripAttachmentMarkers(displayStr)
                }
                if !displayStr.isEmpty {
                    if !textContent.isEmpty { textContent += "\n" }
                    textContent += displayStr
                }

            case .mediaRef(let ref):
                let fileURL = mediaResolver(ref)
                if role == .user {
                    // Legacy: skip mediaRef for user messages — attachment metadata
                    // comes from <user-attached-files> XML which has the correct paths.
                    // Old sessions stored both YAML/XML + mediaRef (with different filenames), causing duplicates.
                } else {
                    let block = AssistantBlock(kind: .text, content: "")
                    block.imageFilePath = fileURL.path
                    blocks.append(block)
                }

            case .toolUse(let tu):
                let kind: AssistantBlockKind
                let content: String
                switch tu.name {
                case "shell_execute":
                    let cmd = extractCommandFromJSON(tu.input)
                    kind = .shellTool(command: cmd)
                    content = "$ \(cmd)"
                case "file_read":
                    let path = extractStringParam("path", from: tu.input)
                    kind = .fileReadTool(path: path)
                    content = "Reading \(path)..."
                case "file_write":
                    let path = extractStringParam("path", from: tu.input)
                    kind = .fileWriteTool(path: path)
                    content = "Writing \(path)..."
                case "file_edit":
                    let path = extractStringParam("path", from: tu.input)
                    kind = .fileEditTool(path: path)
                    content = "Editing \(path)..."
                case "browser_use":
                    let action = extractStringParam("action", from: tu.input)
                    kind = .browserTool(action: action)
                    content = "Browser: \(action)"
                    // Restore browserURL from args or inherit from preceding browser block
                    let argURL = extractStringParam("url", from: tu.input)
                    if !argURL.isEmpty {
                        lastBrowserURL = argURL
                    }
                case "read_image":
                    let path = extractStringParam("path", from: tu.input)
                    kind = .readImageTool(path: path)
                    content = "Reading image \(path)..."
                case "memory_write", "memory_get":
                    kind = .memoryTool(action: tu.name)
                    content = tu.name == "memory_write" ? "Writing memory..." : "Reading memory..."
                default:
                    kind = .shellTool(command: tu.name)
                    content = tu.name
                }
                // Seed as .running so applyToolResults (called when the paired
                // tool_result user message is processed) can recognize this block as
                // "not yet finalized". loadSession finalizes any blocks still left in
                // .running / .streaming after all messages are processed — those are
                // orphaned tool_uses (app killed / run cancelled before the result
                // was persisted) and are marked .cancelled.
                let block = AssistantBlock(kind: kind, content: content, toolStatus: .running, toolUseId: tu.toolUseId)
                block.toolSummary = tu.description
                block.toolInputArgs = tu.input
                // Restore browserURL: use arg URL or inherit from last browser block
                if case .browserTool = kind {
                    block.browserURL = lastBrowserURL
                }
                blocks.append(block)

            case .toolResult(let tr):
                // Match this result to its preceding toolUse block and update it.
                if let blockIdx = blocks.lastIndex(where: { $0.toolUseId == tr.toolUseId }) {
                    blocks[blockIdx].content = tr.output
                    // Prefer the explicit persisted status (distinguishes cancelled from failed);
                    // fall back to the success bool for rows written before the status field existed.
                    switch tr.status {
                    case "cancelled": blocks[blockIdx].toolStatus = .cancelled
                    case "failed":    blocks[blockIdx].toolStatus = .failed(message: "")
                    case "success":   blocks[blockIdx].toolStatus = .success
                    default:          blocks[blockIdx].toolStatus = tr.success ? .success : .failed(message: "")
                    }
                    // Restore screenshot image from persisted mediaRef
                    let ref = tr.mediaRef ?? tr.snapshot?.mediaRef
                    if let ref {
                        blocks[blockIdx].imageFilePath = mediaResolver(ref).path
                    }
                    // Restore browserURL from persisted pageURL
                    if let pageURL = tr.pageURL, !pageURL.isEmpty {
                        blocks[blockIdx].browserURL = pageURL
                        lastBrowserURL = pageURL
                    }
                } else {
                    if !textContent.isEmpty { textContent += "\n" }
                    textContent += tr.output
                }
            }
        }

        // Prepend thinking block if reasoning content was stored and thinking display is enabled
        if showThinking, role == .assistant, let rc = reasoningContent, !rc.isEmpty {
            blocks.insert(AssistantBlock(kind: .thinking, content: rc), at: 0)
        }

        let msg = ChatMessage(role: uiRole, content: textContent, blocks: blocks)
        // Deduplicate attachments by path
        var seenPaths = Set<String>()
        msg.attachments = userAttachments.filter { seenPaths.insert($0.path).inserted }
        if let usage = tokenUsage {
            msg.usage = TokenUsage(
                inputTokens: usage.inputTokens,
                outputTokens: usage.outputTokens,
                cacheCreationTokens: usage.cacheCreationTokens,
                cacheReadTokens: usage.cacheReadTokens,
                latestContextTokens: usage.latestContextTokens ?? 0
            )
        }
        msg.streamInterruptCount = streamInterruptCount
        // [T-error-persist-ios] Restore the persisted error indicator so a failed
        // past turn still shows its error after session reload / app restart.
        // Treat empty/whitespace as no-error (UI gates on `error != nil`, so an
        // empty string would show a blank banner).
        if let e = errorInfo, !e.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            msg.error = e
        }
        return msg
    }

    /// Strip model-facing attachment markers from user display text.
    /// Removes `[attached <kind>: ...]` (image / file / audio / video / …)
    /// and `[image omitted to save context — ...]` tokens produced by the
    /// send/compaction paths for the LLM; the UI surfaces real attachments
    /// via `msg.attachments` parsed from the <user-attached-files> XML, so
    /// these tokens are pure noise to the user.
    fileprivate static func stripAttachmentMarkers(_ s: String) -> String {
        let patterns = [
            #"\[attached [A-Za-z]+:[^\]]*\]"#,
            #"\[image omitted to save context[^\]]*\]"#,
        ]
        var out = s
        for p in patterns {
            if let re = try? NSRegularExpression(pattern: p) {
                let range = NSRange(out.startIndex..., in: out)
                out = re.stringByReplacingMatches(in: out, range: range, withTemplate: "")
            }
        }
        // Collapse blank lines left behind and trim surrounding whitespace.
        let lines = out.components(separatedBy: "\n")
        var cleaned: [String] = []
        var lastBlank = false
        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            let blank = trimmed.isEmpty
            if blank && lastBlank { continue }
            cleaned.append(line)
            lastBlank = blank
        }
        return cleaned.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Remove `<system-reminder>...</system-reminder>` segments from display text
    /// before display. Markers are kept in agentHistory so the model still sees them
    /// on resume/continue, but they should never surface in the chat bubble.
    /// [T-ios-longmsg-cell-split] Split an over-long assistant reply into
    /// paragraph-aligned segments so no single cell has to back an enormous
    /// CALayer. Returns `[text]` unchanged for normal replies.
    ///
    /// Thresholds come from measurement, not taste. The reported collapse
    /// carried 9156 chars in one block and measured 20615.3pt on the reporter's
    /// @3x device (61846px) and 20711.5pt when reproduced on an iPhone 11
    /// (41423px). Splitting at 4000 chars puts that message into ~3 cells of
    /// roughly 5-7K pt each — comfortably under any per-layer limit and, more
    /// importantly, small enough that the list stops being one giant cell
    /// (`visibleCells=1` in the field log) and can recycle normally.
    ///
    /// `splitMarkdownIntoSegments` is reused verbatim from
    /// `PaginatedMarkdownView` (T-markdown-preview-paginate), which solved the
    /// same CALayer problem for document preview in May. It is fence-aware, so
    /// a fenced code block is never cut in half, and each returned segment
    /// parses independently under cmark.
    ///
    /// Known limitation: a single markdown TABLE longer than the threshold
    /// still lands in one segment, because the splitter only breaks at blank
    /// lines outside fences and table rows carry none. Tables therefore stay
    /// exactly as they render today — no better, no worse.
    static func splitLongAssistantText(_ text: String) -> [String] {
        guard text.count > longTextSplitThreshold else { return [text] }
        let segments = splitMarkdownIntoSegments(text, targetSize: longTextSegmentTarget)
        // Defensive: never let splitting lose or empty out content. If the
        // splitter returns nothing usable, keep the original single block —
        // a too-tall cell is far better than a vanished reply.
        let rejoinedCount = segments.reduce(0) { $0 + $1.count }
        guard !segments.isEmpty, rejoinedCount >= text.count / 2 else { return [text] }
        return segments
    }

    /// Replies longer than this are split. Below it, behaviour is byte-identical
    /// to before (one block, one cell).
    private static let longTextSplitThreshold = 4000
    /// Target size per segment; the splitter overshoots to the next paragraph
    /// break, and never cuts inside a fenced code block.
    private static let longTextSegmentTarget = 4000

    static func stripSystemReminders(_ text: String) -> String {
        guard text.contains("<system-reminder>") else { return text }
        let pattern = "<system-reminder>[\\s\\S]*?</system-reminder>"
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return text }
        let ns = text as NSString
        let stripped = regex.stringByReplacingMatches(
            in: text, range: NSRange(location: 0, length: ns.length), withTemplate: ""
        )
        return stripped
    }
}

// MARK: - AgentMessage Conversion

extension RawMessage {
    /// Convert to an AgentMessage for rebuilding agent history on session load.
    func toAgentMessage(mediaResolver: (MediaRef) -> URL) -> AgentMessage {
        let agentRole: AgentMessage.Role = role == .user ? .user : .assistant
        var agentParts: [AgentContentPart] = []

        for part in parts {
            switch part {
            case .text(let s):
                agentParts.append(.text(s))
            case .mediaRef(let ref):
                if let data = try? Data(contentsOf: mediaResolver(ref)) {
                    agentParts.append(.imageData(data: data, mimeType: ref.mimeType, linuxPath: ref.linuxPath))
                }
            case .toolUse(let tu):
                let input = parseJSONToDict(tu.input)
                agentParts.append(.toolUse(id: tu.toolUseId, name: tu.name, input: input))
            case .toolResult(let tr):
                var imgData: Data? = nil
                var imgMime: String? = nil
                if let ref = tr.mediaRef {
                    let fileURL = mediaResolver(ref)
                    if let data = try? Data(contentsOf: fileURL) {
                        imgData = data
                        // Sniff actual image format — stored mimeType may be wrong (e.g. png for jpeg data)
                        imgMime = Self.sniffImageMimeType(data) ?? ref.mimeType
                    }
                }
                agentParts.append(.toolResult(id: tr.toolUseId, name: "", content: tr.output, isError: !tr.success, imageData: imgData, imageMimeType: imgMime, imageLinuxPath: tr.mediaRef?.linuxPath))
            }
        }

        var msg = AgentMessage(role: agentRole, parts: agentParts)
        msg.reasoningContent = reasoningContent
        return msg
    }

    /// Detect actual image format from file magic bytes.
    private static func sniffImageMimeType(_ data: Data) -> String? {
        guard data.count >= 3 else { return nil }
        let bytes = [UInt8](data.prefix(4))
        if bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF {
            return "image/jpeg"
        }
        if data.count >= 4 && bytes[0] == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47 {
            return "image/png"
        }
        if data.count >= 4 && bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x38 {
            return "image/gif"
        }
        if data.count >= 4 && bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46 {
            return "image/webp"
        }
        return nil
    }
}

// MARK: - Sync Support

private let iCloudLogger = AppLogger(category: "iCloud")

extension ChatStore {

    // MARK: - Sync Dirty Tracking

    /// Mark a record as needing sync to iCloud. Only active in DEBUG builds.
    func markDirty(recordType: String, recordId: String, operation: String = "upsert", priority: Int = 0) {
        // DIAG: log every silent early-return so "I added a Skill but
        // no markDirty appears in logs" can be diagnosed without
        // re-instrumenting. Previously both guards returned silently,
        // making it impossible to distinguish "code path not reached"
        // from "user toggled the category off in Settings".
        guard !syncZoneName.isEmpty else {
            iCloudLogger.info("[iCloudTrace] markDirty SKIP type=\(recordType) id=\(recordId.prefix(20)) op=\(operation) reason=zoneEmpty(syncNotInitialized)")
            return
        }
        // User-configurable upload policy: skip categories the user has
        // disabled in iCloud Sync settings (Chat Sessions / Skills /
        // Providers / Environments / Session Files). SyncDeviceV2 is
        // always allowed because peer discovery depends on it.
        guard UploadPolicy.allowsRecordType(recordType) else {
            iCloudLogger.info("[iCloudTrace] markDirty SKIP type=\(recordType) id=\(recordId.prefix(20)) op=\(operation) reason=uploadPolicyDisabled(user-toggled-off-in-Settings)")
            return
        }
        // [T-icloud-record-delete-resurrection] A local op=delete on these
        // types must leave a recent-delete tombstone so an inbound merge that
        // races ahead of the cloud delete (fetchRecentV2 re-pulling the still-
        // live record) can't resurrect it. Session/Message/SessionFile have
        // their own session-level tombstone path (recordDeletedSessionTombstone).
        if operation == "delete",
           ["Skill", "EnvVarItem", "CompactMarker", "MCPServerItem", "Folder",
            "ProviderInstanceV3", "ProviderModelEntryV3", "ProviderModelGroupV3",
            "ProviderThinkingRuleV3"].contains(recordType) {
            recordDeletedRecordTombstone(type: recordType, id: recordId)
        }
        // Trace priority=0 (user-driven) writes so we can follow a
        // freshly-typed message from local INSERT through CK push and
        // peer hydrate. priority=1 (migration backlog) is too noisy to
        // log at this granularity.
        if priority == 0 {
            // [T-ios-log-noise-reduction] INFO→DEBUG: per-record markDirty
            // trace (~876 lines/day). The SKIP-reason variants above stay INFO
            // since they indicate sync NOT happening (more diagnostic value).
            iCloudLogger.debug("[iCloudTrace] markDirty type=\(recordType) id=\(recordId.prefix(20)) op=\(operation)")
        }
        let zoneName = syncZoneName
        let now = Date().timeIntervalSince1970
        let sql = """
            INSERT OR REPLACE INTO sync_dirty_records (record_type, record_id, zone_name, operation, priority, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """
        // When V2 is the active sync engine and this recordType has a v2
        // counterpart, skip the v1 row entirely. The legacy dual-write was
        // meant to keep both engines coexisting during rollout, but with
        // v1 paused (SyncV2Bootstrap.shouldPauseV1) the v1 row has no
        // consumer — SyncCore filters via v2Only:true, the v1 engine is
        // off, and nothing ever clears it. The orphan row then poisons
        // any code path that loads dirty rows without the v2-only filter
        // (e.g. CloudSyncEngine.mergeProviderConfig's in-flight guard),
        // pinning their state to "always skip" for the rest of the install.
        //
        // Mirror SyncV2Bootstrap.shouldPauseV1() so we stop writing the v1
        // row exactly when nobody will consume it. shouldPauseV1 returns
        // true whenever v2 is enabled AND migration status is one of
        // .pending / .inProgress / .completed (i.e. anything except
        // .failed, which falls back to v1). The launch path uses the
        // same predicate to decide whether to start the v1 engine; we
        // mirror it here so the two decisions stay aligned.
        // UserDefaults is thread-safe; the actor isolation on
        // SyncV2Bootstrap.* is a Swift-6 concurrency annotation, not a
        // real main-thread requirement.
        let v2Type = Self.v2RecordType(forV1: recordType)
        let v2Enabled = UserDefaults.standard.bool(forKey: "cloudSync.v2.enabled")
        let migStatus = UserDefaults.standard.string(forKey: "cloudSync.v2.migrationStatus") ?? "pending"
        let v1Paused = v2Enabled && migStatus != "failed"
        let writeV1Row = !(v1Paused && v2Type != recordType)
        // v1 dirty row.
        var stmt: OpaquePointer?
        // DIAG (T-memory-row-vanish): rows for MemoryGlobalV2/MemoryDailyV2
        // appear in the markDirty INFO log but never show up in
        // countDirtyRecords or SyncCore.loadDirtyRecords. Capture
        // prepare/step errcodes + post-step changes() so we can tell
        // which layer is dropping the write.
        var prepareRC: Int32 = 0
        var stepRC: Int32 = -999
        var changesAfter: Int32 = -1
        if writeV1Row {
            prepareRC = sqlite3_prepare_v2(db, sql, -1, &stmt, nil)
            if prepareRC == SQLITE_OK {
                sqlite3_bind_text(stmt, 1, (recordType as NSString).utf8String, -1, nil)
                sqlite3_bind_text(stmt, 2, (recordId as NSString).utf8String, -1, nil)
                sqlite3_bind_text(stmt, 3, (zoneName as NSString).utf8String, -1, nil)
                sqlite3_bind_text(stmt, 4, (operation as NSString).utf8String, -1, nil)
                sqlite3_bind_int(stmt, 5, Int32(priority))
                sqlite3_bind_double(stmt, 6, now)
                stepRC = sqlite3_step(stmt)
                changesAfter = Int32(sqlite3_changes(db))
            }
            sqlite3_finalize(stmt)
        }
        if recordType.hasPrefix("Memory") || recordType == "SoulV2" {
            let errMsg = String(cString: sqlite3_errmsg(db))
            iCloudLogger.info("[iCloudTrace] markDirty SQL type=\(recordType) id=\(recordId.prefix(20)) prepareRC=\(prepareRC) stepRC=\(stepRC) changes=\(changesAfter) errmsg=\(errMsg) v1RowWritten=\(writeV1Row)")
        }
        // v2 dirty row — same record, different recordType name. SyncCore
        // looks up by V2-suffixed type names, so dual-writing here lets
        // both engines coexist during the v2 rollout. v1 ignores rows it
        // doesn't recognize; v2 ignores the un-suffixed names.
        if v2Type != recordType {
            var v2Stmt: OpaquePointer?
            if sqlite3_prepare_v2(db, sql, -1, &v2Stmt, nil) == SQLITE_OK {
                sqlite3_bind_text(v2Stmt, 1, (v2Type as NSString).utf8String, -1, nil)
                sqlite3_bind_text(v2Stmt, 2, (recordId as NSString).utf8String, -1, nil)
                sqlite3_bind_text(v2Stmt, 3, (zoneName as NSString).utf8String, -1, nil)
                sqlite3_bind_text(v2Stmt, 4, (operation as NSString).utf8String, -1, nil)
                sqlite3_bind_int(v2Stmt, 5, Int32(priority))
                sqlite3_bind_double(v2Stmt, 6, now)
                sqlite3_step(v2Stmt)
            }
            sqlite3_finalize(v2Stmt)

            // Resurrect cascade was needed under the old soft-tombstone
            // policy. Now that inbound SessionV2 deletes hard-delete the
            // row + children, there's no tombstoned state to resurrect
            // from — if the row is gone, there's no parent for any new
            // child write to attach to anyway, so the SQL FK / lookups
            // upstream of markDirty would have already rejected it.
        }
        // Schedule a debounced send so local changes are uploaded promptly
        // without triggering a full sync on every individual write.
        // Skip during agent processing (syncSendDeferred) — flushPendingSyncDirty
        // will trigger a single send when the agent loop finishes.
        if !syncSendDeferred {
            if #available(iOS 17.0, *) {
                Task { @MainActor in
                    // Drive whichever engine the user is currently on.
                    if SyncV2Bootstrap.isEnabled {
                        SyncCore.shared.scheduleSend()
                    } else {
                        CloudSyncEngine.shared.scheduleSend()
                    }
                }
            }
        }
    }

    /// Map a v1 record type name to its v2 counterpart for dual-writing.
    /// Returns the input unchanged when no mapping exists (e.g. unknown
    /// types from future code paths).
    private static func v2RecordType(forV1 v1Type: String) -> String {
        switch v1Type {
        case "Session":         return "SessionV2"
        case "Message":         return "MessageV2"
        case "CompactMarker":   return "CompactMarkerV2"
        case "SessionFile":     return "SessionFileV2"
        case "Skill":           return "SkillV2"
        case "ProviderConfig":  return "ProviderConfigV2"
        case "EnvVar":          return "EnvVarV2"
        case "SyncDevice":      return "SyncDeviceV2"
        case "Soul":            return "SoulV2"
        case "Folder":          return "FolderV2"
        case "MCPServers":      return "MCPServersV2"   // [T-mcp-integration-ios]
        default:                return v1Type
        }
    }

    /// Force-mark everything locally syncable as dirty for a full re-upload to iCloud.
    /// Used by the "Delete iCloud Data" flow to repopulate a freshly-emptied cloud from
    /// local state. Does NOT touch local data. Category toggles in CloudSyncEngine still
    /// filter what actually gets uploaded.
    ///
    /// Two-phase staging to keep cold-launch CPU bounded:
    ///   • Phase A (synchronous, fast): singleton records — Skills,
    ///     ProviderConfig, EnvVar, the latest active sessions and their
    ///     children. These are small and the user wants them on cloud
    ///     ASAP.
    ///   • Phase B (background, paginated): historical sessions in
    ///     created_at-DESC windows of 100. Each window scans a
    ///     bounded subset and yields between windows. The scan cursor
    ///     is persisted so a cold launch resumes where the previous
    ///     pass stopped, never re-walking already-staged history.
    ///
    /// Idempotent across launches: once Phase A finishes, the
    /// once-per-install guard (`initialBacklogMarked`) flips. Future
    /// calls bail out unless `force: true` (the "Delete iCloud Data"
    /// recovery path). Phase B continues independently via its own
    /// cursor until the entire backlog is staged.
    ///
    /// Why this matters: pre-fix, every cold launch re-marked 89k+
    /// session files + 290k SQLite INSERTs unconditionally, sustaining
    /// 90%+ CPU. Logs showed 60 calls per day, all redundant.
    func markAllLocalForReupload(force: Bool = false) {
        guard !syncZoneName.isEmpty else { return }
        let initialDoneKey = "cloudSync.v2.initialBacklogMarked"
        let alreadyDone = UserDefaults.standard.bool(forKey: initialDoneKey)
        if !force && alreadyDone {
            let counts = countDirtyRecords()
            iCloudLogger.info("[iCloud] markAllLocalForReupload skipped (initial backlog already marked, dirty=\(counts.total)). Pass force=true to re-mark.")
            // Even when Phase A is done, Phase B may still be paginating
            // older sessions across launches. Kick its async loop so
            // it picks up where it left off (no-op if cursor is past
            // the oldest session).
            kickHistoricalBacklogScan(force: false)
            return
        }
        let backlogPriority = 1

        // ─── Phase A — fast path, ship the small / important stuff ───
        // Singleton records first so user-visible config (skills,
        // providers, env vars) syncs in the first push regardless of
        // how long the historical scan takes.
        markDirty(recordType: "ProviderConfig", recordId: "provider-config", priority: backlogPriority)
        markDirty(recordType: "EnvVar", recordId: "env-vars", priority: backlogPriority)
        // Skills are stored in SkillStore's own DB; CloudSyncEngine /
        // its v2 caller enumerates SkillStore separately. ChatStore
        // doesn't see them.

        // Most-recent active sessions + children: top-100 by
        // updated_at. The rest goes to Phase B.
        let recentLimit = 100
        let recentSessSql = """
            SELECT s.id FROM sessions s
            WHERE EXISTS (SELECT 1 FROM messages m WHERE m.session_id = s.id)
            ORDER BY s.updated_at DESC
            LIMIT \(recentLimit)
            """
        var recentIds: [String] = []
        var rsStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, recentSessSql, -1, &rsStmt, nil) == SQLITE_OK {
            while sqlite3_step(rsStmt) == SQLITE_ROW {
                recentIds.append(String(cString: sqlite3_column_text(rsStmt, 0)))
            }
        }
        sqlite3_finalize(rsStmt)
        for sid in recentIds {
            stageSessionAndChildrenForReupload(sessionId: sid, priority: backlogPriority)
        }
        iCloudLogger.info("[iCloud] markAllLocalForReupload Phase A: staged \(recentIds.count) recent sessions + singletons (priority=\(backlogPriority))")
        // Reset the historical cursor so Phase B starts at the
        // boundary just below the most-recent set.
        if let oldestRecent = recentIds.last,
           let oldestUpdatedAt = sessionUpdatedAt(id: oldestRecent) {
            UserDefaults.standard.set(oldestUpdatedAt.timeIntervalSince1970,
                                      forKey: "cloudSync.v2.backlogScanCursorAt")
        } else {
            UserDefaults.standard.set(Date().timeIntervalSince1970,
                                      forKey: "cloudSync.v2.backlogScanCursorAt")
        }
        UserDefaults.standard.set(true, forKey: initialDoneKey)

        // ─── Phase B — paginated background scan of older history ───
        kickHistoricalBacklogScan(force: true)
    }

    /// Look up `sessions.updated_at` for a single session. Used by the
    /// markAllLocalForReupload Phase A→B handoff to set the initial
    /// pagination cursor.
    private func sessionUpdatedAt(id: String) -> Date? {
        let sql = "SELECT updated_at FROM sessions WHERE id = ?"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        return Date(timeIntervalSince1970: sqlite3_column_double(stmt, 0))
    }

    /// Stage one session + all its messages/markers/files dirty.
    /// Pulled out so Phase A and Phase B share the per-session work.
    /// Returns the number of SessionFile records staged (used by the
    /// migration progress UI to grow its denominator dynamically as
    /// the scan progresses, rather than pre-walking the whole tree).
    @discardableResult
    private func stageSessionAndChildrenForReupload(sessionId: String, priority: Int) -> Int {
        markDirty(recordType: "Session", recordId: sessionId, priority: priority)
        let msgSql = "SELECT id FROM messages WHERE session_id = ?"
        var msgStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, msgSql, -1, &msgStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(msgStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            while sqlite3_step(msgStmt) == SQLITE_ROW {
                markDirty(recordType: "Message",
                          recordId: String(cString: sqlite3_column_text(msgStmt, 0)),
                          priority: priority)
            }
        }
        sqlite3_finalize(msgStmt)
        let cmSql = "SELECT id FROM compact_markers WHERE session_id = ?"
        var cmStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, cmSql, -1, &cmStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(cmStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            while sqlite3_step(cmStmt) == SQLITE_ROW {
                markDirty(recordType: "CompactMarker",
                          recordId: String(cString: sqlite3_column_text(cmStmt, 0)),
                          priority: priority)
            }
        }
        sqlite3_finalize(cmStmt)
        let fileCount = scanAndMarkSessionFiles(sessionId: sessionId, priority: priority)
        // Bump the running session-file estimate so MigrationEngine can
        // grow the denominator monotonically as Phase B paginates.
        if fileCount > 0 {
            let key = "cloudSync.v2.estimatedExtraSessionFiles"
            let prev = UserDefaults.standard.integer(forKey: key)
            UserDefaults.standard.set(prev + fileCount, forKey: key)
        }
        return fileCount
    }

    /// Baseline record count for the migration progress UI's denominator.
    /// Counts only what we can know cheaply from SQLite: sessions
    /// (non-empty), messages, compact markers, plus singletons. Does
    /// NOT include SessionFiles — those are filesystem-discovered and
    /// get added to the denominator incrementally by Phase B as it
    /// pages through sessions. SkillStore / EnvVarStore counts are
    /// added by the migration UI on top.
    func countBacklogBaseline() -> (sessions: Int, messages: Int, compactMarkers: Int, singletons: Int) {
        var sessions = 0, messages = 0, markers = 0
        var stmt: OpaquePointer?
        let sessSql = "SELECT COUNT(*) FROM sessions s WHERE EXISTS (SELECT 1 FROM messages m WHERE m.session_id = s.id)"
        if sqlite3_prepare_v2(db, sessSql, -1, &stmt, nil) == SQLITE_OK,
           sqlite3_step(stmt) == SQLITE_ROW {
            sessions = Int(sqlite3_column_int(stmt, 0))
        }
        sqlite3_finalize(stmt)
        stmt = nil
        if sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM messages", -1, &stmt, nil) == SQLITE_OK,
           sqlite3_step(stmt) == SQLITE_ROW {
            messages = Int(sqlite3_column_int(stmt, 0))
        }
        sqlite3_finalize(stmt)
        stmt = nil
        if sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM compact_markers", -1, &stmt, nil) == SQLITE_OK,
           sqlite3_step(stmt) == SQLITE_ROW {
            markers = Int(sqlite3_column_int(stmt, 0))
        }
        sqlite3_finalize(stmt)
        // ProviderConfig + EnvVar (legacy whole-file). Per-key
        // EnvVarItem records are counted by EnvVarStore separately
        // when the migration UI assembles the denominator.
        return (sessions: sessions, messages: messages, compactMarkers: markers, singletons: 2)
    }

    /// Phase B: paginated background scan of older sessions.
    ///
    /// Reads the cursor at `cloudSync.v2.backlogScanCursorAt` (a
    /// session.updated_at value), pulls the next 100 sessions strictly
    /// older than it, stages each, advances the cursor, yields, and
    /// loops. Stops when no sessions remain. Persistent cursor means a
    /// cold launch resumes where the previous pass stopped — no
    /// repeated work even if migration takes multiple sessions to
    /// finish.
    ///
    /// Triggered from markAllLocalForReupload (after Phase A) and
    /// re-kicked on every subsequent invocation that short-circuits
    /// the once-per-install guard, since the pagination state is
    /// independent of `initialBacklogMarked`.
    nonisolated(unsafe) private static var historicalScanInFlight = false
    private func kickHistoricalBacklogScan(force: Bool) {
        if !force && Self.historicalScanInFlight { return }
        Self.historicalScanInFlight = true
        Task { [weak self] in
            await self?.runHistoricalBacklogScan()
            await MainActor.run { Self.historicalScanInFlight = false }
        }
    }

    private func runHistoricalBacklogScan() async {
        let cursorKey = "cloudSync.v2.backlogScanCursorAt"
        let cancelKey = "cloudSync.v2.backlogScanCanceled"
        let windowSize = 100
        let backlogPriority = 1
        var totalStaged = 0
        var passes = 0
        while !Task.isCancelled {
            // Honor explicit user cancel from MigrationEngine.cancelMigration.
            // Without this, the loop would keep paginating older sessions and
            // immediately re-stage the priority=1 dirty rows that
            // clearMigrationBacklog() just removed — the user sees migration
            // "still running" because it really is.
            if UserDefaults.standard.bool(forKey: cancelKey) {
                iCloudLogger.info("[iCloud] historicalBacklogScan: cancel flag set, stopping. totalStaged=\(totalStaged) passes=\(passes)")
                return
            }
            let cursor = UserDefaults.standard.double(forKey: cursorKey)
            // Cursor sentinel: negative or zero means "no work" only if
            // we've also recorded at least one Phase B pass. Default 0
            // would otherwise pull *all* sessions on first call — but
            // markAllLocalForReupload sets the cursor to the oldest
            // recent session before kicking us, so 0 here means
            // pagination already drained completely.
            guard cursor > 0 else {
                iCloudLogger.info("[iCloud] historicalBacklogScan: cursor=0 (drained), totalStaged=\(totalStaged) passes=\(passes)")
                return
            }
            let sql = """
                SELECT id, updated_at FROM sessions
                WHERE updated_at < ?
                  AND EXISTS (SELECT 1 FROM messages m WHERE m.session_id = sessions.id)
                ORDER BY updated_at DESC
                LIMIT \(windowSize)
                """
            var batch: [(id: String, updatedAt: Double)] = []
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                sqlite3_bind_double(stmt, 1, cursor)
                while sqlite3_step(stmt) == SQLITE_ROW {
                    let sid = String(cString: sqlite3_column_text(stmt, 0))
                    let updatedAt = sqlite3_column_double(stmt, 1)
                    batch.append((sid, updatedAt))
                }
            }
            sqlite3_finalize(stmt)
            if batch.isEmpty {
                iCloudLogger.info("[iCloud] historicalBacklogScan: window empty, finished. totalStaged=\(totalStaged) passes=\(passes)")
                // Sentinel to mark fully drained — next launch's
                // kickHistoricalBacklogScan returns immediately.
                UserDefaults.standard.set(0.0, forKey: cursorKey)
                return
            }
            for (sid, _) in batch {
                stageSessionAndChildrenForReupload(sessionId: sid, priority: backlogPriority)
                totalStaged += 1
            }
            // Advance cursor to the OLDEST in this window so the next
            // pass picks up strictly older sessions.
            if let oldest = batch.last?.updatedAt {
                UserDefaults.standard.set(oldest, forKey: cursorKey)
            }
            passes += 1
            iCloudLogger.info("[iCloud] historicalBacklogScan: pass=\(passes) staged=\(batch.count) cumulative=\(totalStaged) cursorNext=\(batch.last?.updatedAt ?? 0)")

            // Wait for the previous batch to drain before staging the
            // next one. Otherwise the dirty queue grows monotonically
            // (12 windows × 100 sessions = 1200 staged in ~3s, push
            // hasn't kept up) and we lose the "small bites" benefit —
            // SyncCore would just see the same single-shot 200k+
            // backlog at the end. Threshold is one-window-worth of
            // children: ~100 sessions × ~70 messages + files, give it
            // room without letting it stall forever.
            //
            // Poll every 5s; bail with a hard cap of 30 min per window
            // so a stuck push doesn't permanently freeze pagination.
            // Note: countDirtyRecords includes user-driven priority=0
            // writes, but those are usually a tiny fraction of the
            // backlog priority=1 rows during initial migration.
            let drainThreshold = 5_000
            let drainDeadline = Date().addingTimeInterval(30 * 60)
            while Date() < drainDeadline, !Task.isCancelled {
                let dirty = countDirtyRecords()
                if dirty.total < drainThreshold { break }
                try? await Task.sleep(nanoseconds: 5_000_000_000)
            }
            // Brief pause between successful drains so other actor work
            // (chat writes, view loads) gets cycles even if push is
            // saturating CK.
            try? await Task.sleep(nanoseconds: 250_000_000)
        }
    }

    /// Force-mark a session and all its messages/compact markers as dirty for iCloud sync.
    func forceMarkSessionDirty(sessionId: String) {
        markDirty(recordType: "Session", recordId: sessionId)
        // Mark all messages in this session as dirty
        let msgSql = "SELECT id FROM messages WHERE session_id = ?"
        var msgStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, msgSql, -1, &msgStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(msgStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            while sqlite3_step(msgStmt) == SQLITE_ROW {
                let msgId = String(cString: sqlite3_column_text(msgStmt, 0))
                markDirty(recordType: "Message", recordId: msgId)
            }
        }
        sqlite3_finalize(msgStmt)
        // Mark compact markers
        let cmSql = "SELECT id FROM compact_markers WHERE session_id = ?"
        var cmStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, cmSql, -1, &cmStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(cmStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            while sqlite3_step(cmStmt) == SQLITE_ROW {
                let cmId = String(cString: sqlite3_column_text(cmStmt, 0))
                markDirty(recordType: "CompactMarker", recordId: cmId)
            }
        }
        sqlite3_finalize(cmStmt)
        // Mark session files (workspace/offloads/attachments)
        scanAndMarkSessionFiles(sessionId: sessionId)
    }

    /// Backfill: merge all remote_sessions and remote_messages into local tables.
    /// Called after code change that introduced mergeRemoteSession, to retroactively merge
    /// data that was fetched before the merge logic existed.
    func backfillRemoteToLocal() {
        iCloudLogger.info("[iCloud] backfillRemoteToLocal: starting")
        // 1. Merge remote sessions that don't exist locally
        let sCount = execCount("""
            INSERT OR IGNORE INTO sessions (id, title, category, model_id, created_at, updated_at, remote_origin_device_id)
            SELECT rs.id, rs.title, rs.category, rs.model_id, rs.created_at, rs.updated_at, rs.device_id
            FROM remote_sessions rs
            WHERE NOT EXISTS (SELECT 1 FROM sessions s WHERE s.id = rs.id)
        """)
        iCloudLogger.info("[iCloud] backfillRemoteToLocal: inserted \(sCount) sessions")

        // 2. Merge remote messages into local messages for sessions that exist locally
        // [T-ios-listsessions-part-flags] compute the bitmask inline so remote-
        // inserted rows carry correct flags (never left at DEFAULT 0).
        let mCount = execCount("""
            INSERT OR IGNORE INTO messages (id, session_id, role, parts_json, created_at, token_usage, sort_order, reasoning_content, stream_interrupt_count, updated_at, part_flags)
            SELECT rm.id, rm.session_id, rm.role, rm.parts_json, rm.created_at, rm.token_usage, rm.sort_order, rm.reasoning_content, rm.stream_interrupt_count, rm.created_at, \(Self.partFlagsSQLExpr("rm.parts_json"))
            FROM remote_messages rm
            WHERE EXISTS (SELECT 1 FROM sessions s WHERE s.id = rm.session_id)
              AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.id = rm.id)
        """)
        iCloudLogger.info("[iCloud] backfillRemoteToLocal: inserted \(mCount) messages")

        iCloudLogger.info("[iCloud] backfillRemoteToLocal: done")
    }

    /// Execute SQL and return number of changes. Returns 0 on error.
    private func execCount(_ sql: String) -> Int {
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_step(stmt)
            sqlite3_finalize(stmt)
            return Int(sqlite3_changes(db))
        }
        sqlite3_finalize(stmt)
        return 0
    }

    /// Mark a session as successfully synced to iCloud.
    func markSessionSynced(id: String) {
        let sql = "UPDATE sessions SET last_synced_at = ? WHERE id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_double(stmt, 1, Date().timeIntervalSince1970)
            sqlite3_bind_text(stmt, 2, (id as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    struct DirtyRecord {
        let recordType: String
        let recordId: String
        let zoneName: String
        let operation: String
    }

    /// Record types belonging to the V2 sync surface. Used to filter
    /// dirty rows so v1-only / dual-write rows don't starve V2 sends.
    /// Note: `EnvVarItem` has no V2 suffix but IS v2 — the per-key env
    /// var record introduced after EnvVarV2 (which is legacy whole-file
    /// and being phased out, but kept here so any remaining dirty rows
    /// can still drain).
    fileprivate static let v2SyncRecordTypes: [String] = [
        "SessionV2", "MessageV2", "CompactMarkerV2", "SessionFileV2",
        "FolderV2",
        "SkillV2", "ProviderConfigV2", "EnvVarV2", "EnvVarItem",
        "SyncDeviceV2", "SoulV2",
        // File-backed singletons. Without these in the whitelist,
        // markDirty wrote a dirty row but loadDirtyRecords(v2Only:true)'s
        // WHERE-IN clause filtered them out, so they never got pushed
        // and the CloudKit Dev schema never got auto-created — root
        // cause of the dashboard's persistent NOT_FOUND on these types
        // (T-soulv2-memory-whitelist).
        "MemoryGlobalV2", "MemoryDailyV2",
        // v3 per-record provider sync (T-provider-sync-v3). The legacy
        // "ProviderConfigV2" remains in the whitelist too — outbound is
        // dual-written until all peers upgrade; inbound v2 is dropped
        // on v3 builds (ProviderV3Bootstrap.isEnabled gate).
        "ProviderInstanceV3", "ProviderModelEntryV3", "ProviderModelGroupV3",
        // [T-icloud-thinking-rules-sync] Per-rule custom thinking rules. Omitting a
        // type here is the exact failure MCPServersV2 hit below: dirty rows get
        // written but loadDirtyRecords filters them out, so nothing ever uploads
        // and the type fails SILENTLY.
        "ProviderThinkingRuleV3",
        // [T-mcp-per-server-sync] Per-server MCP records, plus the legacy
        // whole-file type. MCPServersV2 was NEVER in this whitelist — its
        // dirty rows were written but filtered out by loadDirtyRecords
        // (v2Only:true), so MCP config never uploaded at all. It's listed
        // now so (a) the one-time legacy-record op=delete can drain and
        // (b) any leftover upsert rows drain as no-ops (builder returns nil).
        "MCPServersV2", "MCPServerItem",
    ]

    /// Pre-built SQL IN-list fragment for `v2SyncRecordTypes`. Values
    /// are static identifiers, no user input — safe to interpolate.
    fileprivate static let v2SyncRecordTypesSQL: String =
        v2SyncRecordTypes.map { "'\($0)'" }.joined(separator: ",")

    /// Load dirty records for sync. `v2Only=true` filters to record types
    /// the v2 SyncCore knows how to build (suffix `V2`). Without the filter,
    /// v1 dual-write rows (Session/Message/...) sit at the head of the
    /// priority+created_at ordering forever — v2 has no builder for them so
    /// they get kept-as-dirty every round, starving real V2 rows behind them.
    /// DIAG (T-memory-row-vanish): one-shot probe — call from SyncCore to
    /// dump every distinct record_type currently sitting in
    /// sync_dirty_records, regardless of any v2/v1 whitelist filter.
    /// Lets us confirm whether MemoryGlobalV2 / MemoryDailyV2 are in the
    /// table at all when v2Only's WHERE-IN filter would hide them.
    func dumpAllDirtyTypes() -> [(type: String, count: Int)] {
        var result: [(type: String, count: Int)] = []
        var stmt: OpaquePointer?
        let sql = "SELECT record_type, COUNT(*) FROM sync_dirty_records GROUP BY record_type"
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            while sqlite3_step(stmt) == SQLITE_ROW {
                let t = String(cString: sqlite3_column_text(stmt, 0))
                let c = Int(sqlite3_column_int(stmt, 1))
                result.append((t, c))
            }
        }
        sqlite3_finalize(stmt)
        return result
    }

    func loadDirtyRecords(v2Only: Bool = false) -> [DirtyRecord] {
        // Order Sessions first so receiving device creates sessions before messages arrive
        // Order:
        //   1. priority ASC — user-driven writes (priority=0) before
        //      migration backlog (priority=1). Critical so a freshly
        //      typed message is never starved by 28k legacy rows.
        //   2. parent type before child type — Session/SessionV2 before
        //      Message/MessageV2, so a peer applying the inbound batch
        //      sees parents before their children.
        //   3. created_at ASC within the same priority/type — push the
        //      oldest dirty first so the queue drains FIFO.
        let whereClause = v2Only ? "WHERE record_type IN (\(Self.v2SyncRecordTypesSQL))" : ""
        // Newest first within each priority+type group: gives perceived
        // sync responsiveness for what the user just touched while the
        // backlog drains in the background. Parent types (Session*) still
        // ship before children (Message*) so peers never receive an
        // orphan Message before its Session.
        let sql = """
            SELECT record_type, record_id, zone_name, operation FROM sync_dirty_records
            \(whereClause)
            ORDER BY priority ASC,
                CASE record_type
                    WHEN 'Session' THEN 0
                    WHEN 'SessionV2' THEN 0
                    WHEN 'Message' THEN 1
                    WHEN 'MessageV2' THEN 1
                    ELSE 2
                END,
                created_at DESC
            LIMIT 100
        """
        var stmt: OpaquePointer?
        var records: [DirtyRecord] = []
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            while sqlite3_step(stmt) == SQLITE_ROW {
                records.append(DirtyRecord(
                    recordType: String(cString: sqlite3_column_text(stmt, 0)),
                    recordId: String(cString: sqlite3_column_text(stmt, 1)),
                    zoneName: String(cString: sqlite3_column_text(stmt, 2)),
                    operation: String(cString: sqlite3_column_text(stmt, 3))
                ))
            }
        }
        sqlite3_finalize(stmt)
        return records
    }

    /// V2-only dirty counts split by priority. priority=0 → user writes,
    /// priority=1 → migration backlog. Used by the iCloud Sync sheet to
    /// show 'Pending push (new)' vs 'Pending push (migration)' rows.
    func countDirtyV2ByPriority() -> (newCount: Int, migrationCount: Int) {
        let sql = "SELECT priority, COUNT(*) FROM sync_dirty_records WHERE record_type IN (\(Self.v2SyncRecordTypesSQL)) GROUP BY priority"
        var stmt: OpaquePointer?
        var newCount = 0, migrationCount = 0
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            while sqlite3_step(stmt) == SQLITE_ROW {
                let p = Int(sqlite3_column_int(stmt, 0))
                let c = Int(sqlite3_column_int(stmt, 1))
                if p == 0 { newCount = c } else { migrationCount += c }
            }
        }
        sqlite3_finalize(stmt)
        return (newCount, migrationCount)
    }

    func countDirtyRecords() -> (total: Int, byType: [String: Int]) {
        let sql = "SELECT record_type, COUNT(*) FROM sync_dirty_records GROUP BY record_type"
        var stmt: OpaquePointer?
        var byType: [String: Int] = [:]
        var total = 0
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            while sqlite3_step(stmt) == SQLITE_ROW {
                let type = String(cString: sqlite3_column_text(stmt, 0))
                let count = Int(sqlite3_column_int(stmt, 1))
                byType[type] = count
                total += count
            }
        }
        sqlite3_finalize(stmt)
        return (total, byType)
    }

    /// Mark every record belonging to a session — Session itself, all its
    /// Messages, CompactMarkers, and SessionFiles — as dirty so they will
    /// be re-pushed to iCloud on the next send pass. Idempotent. Used by
    /// the user-facing Force Sync action (long-press a message in chat).
    /// Returns total dirty rows added.
    @discardableResult
    func forceSyncSession(_ sessionId: String) -> Int {
        guard !syncZoneName.isEmpty else { return 0 }
        var count = 0
        markDirty(recordType: "Session", recordId: sessionId)
        count += 1
        // Messages
        var mStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, "SELECT id FROM messages WHERE session_id = ?", -1, &mStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(mStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            while sqlite3_step(mStmt) == SQLITE_ROW {
                markDirty(recordType: "Message", recordId: String(cString: sqlite3_column_text(mStmt, 0)))
                count += 1
            }
        }
        sqlite3_finalize(mStmt)
        // CompactMarkers
        var cStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, "SELECT id FROM compact_markers WHERE session_id = ?", -1, &cStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(cStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            while sqlite3_step(cStmt) == SQLITE_ROW {
                markDirty(recordType: "CompactMarker", recordId: String(cString: sqlite3_column_text(cStmt, 0)))
                count += 1
            }
        }
        sqlite3_finalize(cStmt)
        // SessionFiles via the existing scan
        let fileCount = scanAndMarkSessionFiles(sessionId: sessionId, priority: 0)
        count += fileCount
        iCloudLogger.info("[iCloud] forceSyncSession(\(sessionId.prefix(8))) marked \(count) records dirty")
        return count
    }

    /// Outcome of a Force Pull attempt.
    enum ForcePullOutcome {
        /// Cloud returned ≥1 record; local was wiped and replaced. localDeleted
        /// counts messages+markers that existed locally before the swap.
        case applied(pulledFromCloud: Int, localDeleted: Int)
        /// Cloud returned 0 records or threw — local rows are preserved
        /// untouched. The chat view should surface a warning to the user.
        case cloudEmpty
        /// Underlying transport threw an error. Local rows preserved.
        case failed(String)
    }

    /// Reconcile a session against cloud truth.
    ///
    /// New (safe) flow:
    ///   1. Pull from cloud first — does NOT touch local rows yet.
    ///   2. If cloud returned 0 records → bail out, keep local intact,
    ///      surface .cloudEmpty so the UI can warn the user that there
    ///      is no remote copy to fall back to (avoids the "Force Pull
    ///      silently wipes everything" footgun).
    ///   3. If cloud returned ≥1 record → only NOW wipe local rows +
    ///      clear dirty/pushed bookkeeping, then re-emit the cached cloud
    ///      portables through the inbound path so they land in SQLite.
    ///
    /// This means the pull network round-trip happens with local
    /// untouched, and the swap is committed atomically only after we
    /// know cloud has something to give us.
    func forcePullSession(sessionId: String) async -> ForcePullOutcome {
        invalidateSessionListCache()
        iCloudLogger.warning("[ForcePull] sid=\(sessionId.prefix(8)) START — pulling cloud first, local untouched")

        // 1. Cloud first (no local mutation yet). Returns the portables
        //    so we can replay them after the swap. If the transport
        //    can't be reached we treat it as a hard failure rather than
        //    "empty cloud" so the caller can distinguish.
        let pullResult: ([PortableRecord], Error?)
        if #available(iOS 17.0, *) {
            pullResult = await SyncCore.shared.fetchSessionPortables(sessionId: sessionId)
        } else {
            iCloudLogger.warning("[ForcePull] sid=\(sessionId.prefix(8)) iOS < 17 — pull unavailable")
            return .failed(AppLocalized("Force Pull requires iOS 17 or newer."))
        }
        let portables = pullResult.0
        if let err = pullResult.1 {
            iCloudLogger.warning("[ForcePull] sid=\(sessionId.prefix(8)) transport err: \(err.localizedDescription) — keeping local intact")
            return .failed(err.localizedDescription)
        }
        if portables.isEmpty {
            iCloudLogger.warning("[ForcePull] sid=\(sessionId.prefix(8)) cloud returned 0 records — keeping local intact")
            return .cloudEmpty
        }
        iCloudLogger.warning("[ForcePull] sid=\(sessionId.prefix(8)) cloud returned \(portables.count) records — committing swap")

        // 2. Now safe to swap. Collect ids for bookkeeping cleanup.
        var messageIds: [String] = []
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, "SELECT id FROM messages WHERE session_id = ?", -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                messageIds.append(String(cString: sqlite3_column_text(stmt, 0)))
            }
        }
        sqlite3_finalize(stmt)
        var markerIds: [String] = []
        var mStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, "SELECT id FROM compact_markers WHERE session_id = ?", -1, &mStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(mStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            while sqlite3_step(mStmt) == SQLITE_ROW {
                markerIds.append(String(cString: sqlite3_column_text(mStmt, 0)))
            }
        }
        sqlite3_finalize(mStmt)

        // 3. Hard-delete local rows (cloud truth will be replayed below).
        var locallyDeleted = 0
        var dStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, "DELETE FROM messages WHERE session_id = ?", -1, &dStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(dStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            sqlite3_step(dStmt)
            locallyDeleted += Int(sqlite3_changes(db))
        }
        sqlite3_finalize(dStmt)
        var dmStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, "DELETE FROM compact_markers WHERE session_id = ?", -1, &dmStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(dmStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            sqlite3_step(dmStmt)
            locallyDeleted += Int(sqlite3_changes(db))
        }
        sqlite3_finalize(dmStmt)

        // 4. Clear sync_dirty_records and sync_pushed_records so the
        // replay treats incoming portables as fresh, and so we don't
        // immediately re-push stale rows.
        for id in messageIds {
            clearDirtyRecord(recordName: "Message:\(id)")
        }
        for id in markerIds {
            clearDirtyRecord(recordName: "CompactMarker:\(id)")
        }
        clearDirtyRecord(recordName: "Session:\(sessionId)")

        var pushedClearStmt: OpaquePointer?
        let clearPushedSQL = "DELETE FROM sync_pushed_records WHERE record_name = ?"
        if sqlite3_prepare_v2(db, clearPushedSQL, -1, &pushedClearStmt, nil) == SQLITE_OK {
            for id in messageIds {
                sqlite3_bind_text(pushedClearStmt, 1, ("Message:\(id)" as NSString).utf8String, -1, nil)
                sqlite3_step(pushedClearStmt)
                sqlite3_reset(pushedClearStmt)
            }
            for id in markerIds {
                sqlite3_bind_text(pushedClearStmt, 1, ("CompactMarker:\(id)" as NSString).utf8String, -1, nil)
                sqlite3_step(pushedClearStmt)
                sqlite3_reset(pushedClearStmt)
            }
            sqlite3_bind_text(pushedClearStmt, 1, ("Session:\(sessionId)" as NSString).utf8String, -1, nil)
            sqlite3_step(pushedClearStmt)
        }
        sqlite3_finalize(pushedClearStmt)

        iCloudLogger.warning("[ForcePull] sid=\(sessionId.prefix(8)) local cleared: messages=\(messageIds.count) markers=\(markerIds.count) deletedRows=\(locallyDeleted)")

        // 5. Replay cloud portables through SyncCore (same path normal
        // inbound traffic uses, so hydrators land them in SQLite).
        await SyncCore.shared.applyPortables(portables, transportName: "ForcePull")
        iCloudLogger.warning("[ForcePull] sid=\(sessionId.prefix(8)) DONE pulled=\(portables.count) localDeleted=\(locallyDeleted)")
        return .applied(pulledFromCloud: portables.count, localDeleted: locallyDeleted)
    }

    /// Drop all priority=1 dirty rows (migration backlog). Used by Cancel
    /// Migration in the Sync sheet — keeps user-driven (priority=0) rows
    /// so freshly typed messages keep flowing while history backfill stops.
    /// Returns the number of rows deleted.
    @discardableResult
    func clearMigrationBacklog() -> Int {
        let sql = "DELETE FROM sync_dirty_records WHERE priority = 1"
        var stmt: OpaquePointer?
        var deleted = 0
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_step(stmt)
            deleted = Int(sqlite3_changes(db))
        }
        sqlite3_finalize(stmt)
        iCloudLogger.info("[iCloud] clearMigrationBacklog: dropped \(deleted) priority=1 rows")
        return deleted
    }

    /// Count V2 records that exist locally but have not yet been pushed
    /// to v2's shared zone (i.e. would be backfilled if migration ran).
    /// We approximate by total local row counts minus the cumulative
    /// pushedCumulative counter — exact enough for the "Historical Data"
    /// label in the Sync sheet.
    func countLocalV2Eligible() -> Int {
        let sessions = queryInt64("SELECT COUNT(*) FROM sessions")
        let messages = queryInt64("SELECT COUNT(*) FROM messages")
        let markers = queryInt64("SELECT COUNT(*) FROM compact_markers")
        // session files = (rough heuristic) sum of file_count column if it exists; otherwise 0
        return Int(sessions + messages + markers)
    }

    func clearDirtyRecord(recordName: String) {
        // recordName format is "Type:id"
        let parts = recordName.split(separator: ":", maxSplits: 1)
        guard parts.count == 2 else { return }
        let recordType = String(parts[0])
        let recordId = String(parts[1])
        let sql = "DELETE FROM sync_dirty_records WHERE record_type = ? AND record_id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (recordType as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (recordId as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    /// Record one or more record names as confirmed saved on iCloud.
    /// Idempotent — INSERT OR IGNORE on the recordName primary key.
    /// Used by the v2 transport to feed the migration UI's accurate
    /// "unique records pushed" counter, which is otherwise inflated by
    /// every relaunch re-marking already-pushed records dirty.
    func markRecordsPushed(_ recordNames: [String]) {
        guard !recordNames.isEmpty else { return }
        exec("BEGIN TRANSACTION")
        let sql = "INSERT OR IGNORE INTO sync_pushed_records (record_name) VALUES (?)"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            for name in recordNames {
                sqlite3_bind_text(stmt, 1, (name as NSString).utf8String, -1, nil)
                sqlite3_step(stmt)
                sqlite3_reset(stmt)
            }
        }
        sqlite3_finalize(stmt)
        exec("COMMIT")
    }

    /// [T-icloud-migration-reset] Wipe the pushed-record ledger so a migration
    /// restarted from scratch re-counts (and re-pushes) everything.
    ///
    /// Only for the explicit "reset migration progress" action. Clearing this
    /// on any other path would make already-uploaded records look un-pushed and
    /// send them again. Returns the row count removed, for the log.
    @discardableResult
    func clearAllPushedRecords() -> Int {
        let before = countPushedRecords()
        exec("DELETE FROM sync_pushed_records")
        iCloudLogger.info("[iCloud] clearAllPushedRecords: removed \(before) rows")
        return before
    }

    /// Distinct count of recordNames in `sync_pushed_records`. The
    /// migration UI's "Records pushed" numerator.
    func countPushedRecords() -> Int {
        var count = 0
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM sync_pushed_records", -1, &stmt, nil) == SQLITE_OK {
            if sqlite3_step(stmt) == SQLITE_ROW {
                count = Int(sqlite3_column_int(stmt, 0))
            }
        }
        sqlite3_finalize(stmt)
        return count
    }

    // MARK: - Sync Device CRUD

    func upsertSyncDevice(_ device: SyncDevice) {
        iCloudLogger.info("[iCloud] upsertSyncDevice: id=\(device.id) name=\(device.deviceName) zone=\(device.zoneName) upload=\(device.uploadTypes)")
        let sql = """
            INSERT OR REPLACE INTO sync_devices (device_id, device_name, zone_name, last_seen, os_version, upload_types)
            VALUES (?, ?, ?, ?, ?, ?)
        """
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (device.id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (device.deviceName as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 3, (device.zoneName as NSString).utf8String, -1, nil)
            sqlite3_bind_double(stmt, 4, device.lastSeen.timeIntervalSince1970)
            sqlite3_bind_text(stmt, 5, (device.osVersion as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 6, (device.uploadTypes.joined(separator: ",") as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    func listSyncDevices() -> [SyncDevice] {
        let sql = "SELECT device_id, device_name, zone_name, last_seen, os_version, upload_types FROM sync_devices"
        var stmt: OpaquePointer?
        var devices: [SyncDevice] = []
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            while sqlite3_step(stmt) == SQLITE_ROW {
                let uploadTypesStr = sqlite3_column_text(stmt, 5).map { String(cString: $0) } ?? ""
                let uploadTypes = uploadTypesStr.isEmpty ? [] : uploadTypesStr.components(separatedBy: ",")
                devices.append(SyncDevice(
                    id: String(cString: sqlite3_column_text(stmt, 0)),
                    deviceName: String(cString: sqlite3_column_text(stmt, 1)),
                    zoneName: String(cString: sqlite3_column_text(stmt, 2)),
                    lastSeen: Date(timeIntervalSince1970: sqlite3_column_double(stmt, 3)),
                    osVersion: String(cString: sqlite3_column_text(stmt, 4)),
                    uploadTypes: uploadTypes
                ))
            }
        }
        sqlite3_finalize(stmt)
        iCloudLogger.info("[iCloud] listSyncDevices: returning \(devices.count) devices")
        for d in devices {
            iCloudLogger.info("[iCloud]   device: id=\(d.id) name=\(d.deviceName) zone=\(d.zoneName)")
        }
        // DIAG: when zero devices, dump enough surrounding state to tell at
        // a glance WHY peer discovery isn't surfacing the other side. Three
        // common causes — sync not initialized (zoneEmpty), user disabled
        // upload categories in Settings, no dirty rows queued — each
        // produces a distinctly identifiable footprint here.
        if devices.isEmpty {
            let zoneState = syncZoneName.isEmpty ? "EMPTY(syncNotInitialized)" : syncZoneName
            let categoryStates = UploadPolicy.Category.allCases.map { cat in
                "\(cat.rawValue)=\(UploadPolicy.isEnabled(cat))"
            }.joined(separator: " ")
            // Count any pending dirty rows so we can tell if writes are
            // backing up (sync running but push failing) vs no writes
            // happening at all (markDirty skipped at the source).
            var dirtyCount = 0
            var dirtyByType: [String: Int] = [:]
            var dStmt: OpaquePointer?
            if sqlite3_prepare_v2(db, "SELECT record_type, COUNT(*) FROM sync_dirty_records GROUP BY record_type", -1, &dStmt, nil) == SQLITE_OK {
                while sqlite3_step(dStmt) == SQLITE_ROW {
                    let t = String(cString: sqlite3_column_text(dStmt, 0))
                    let c = Int(sqlite3_column_int(dStmt, 1))
                    dirtyByType[t] = c
                    dirtyCount += c
                }
            }
            sqlite3_finalize(dStmt)
            let dirtySummary = dirtyByType.isEmpty ? "empty" : dirtyByType.map { "\($0.key)=\($0.value)" }.sorted().joined(separator: ",")
            iCloudLogger.info("[iCloud] listSyncDevices=0 diag: zone=\(zoneState) uploadPolicy=[\(categoryStates)] dirtyQueue=\(dirtyCount){\(dirtySummary)}")
        }
        return devices
    }

    // MARK: - Remote Session CRUD

    func upsertRemoteSession(_ session: ChatSession, deviceId: String) {
        iCloudLogger.info("[iCloud] upsertRemoteSession: id=\(session.id) title=\(session.title ?? "nil") deviceId=\(deviceId) model=\(session.modelId)")
        let sql = """
            INSERT OR REPLACE INTO remote_sessions (id, device_id, title, category, model_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (session.id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (deviceId as NSString).utf8String, -1, nil)
            bindOptionalText(stmt, index: 3, value: session.title)
            bindOptionalText(stmt, index: 4, value: session.category)
            sqlite3_bind_text(stmt, 5, (session.modelId as NSString).utf8String, -1, nil)
            sqlite3_bind_double(stmt, 6, session.createdAt.timeIntervalSince1970)
            sqlite3_bind_double(stmt, 7, session.updatedAt.timeIntervalSince1970)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    /// Merge a remote session into local sessions table (last-write-wins).
    /// If local doesn't have this session → insert with remote_origin_device_id.
    /// If local has it but remote updated_at is newer → update title/category/updated_at.
    /// If local has it and is newer → skip.
    /// Merge a remote session into local sessions table.
    /// - `remotePinnedAtRaw`: The raw pinnedAt Date from CKRecord. nil = old device (field absent),
    ///   epoch 0 = explicitly unpinned, positive = pinned timestamp.
    /// - `remoteHasFolderField`: whether the inbound record carried the
    ///   folderId key AT ALL. Old-build peers omit it entirely; for them the
    ///   local folder assignment must be preserved, whereas a new-build peer
    ///   sending an explicit nil means "ungrouped on that device" and, when
    ///   remote is newer, that wins (folder_id rides the session's LWW).
    func mergeRemoteSession(_ session: ChatSession, fromDeviceId: String, memoryEnabled: Bool = true, modelBinding: String? = nil, remotePinnedAtRaw: Date? = nil, remoteFolderId: String? = nil, remoteHasFolderField: Bool = false) {
        invalidateSessionListCache()
        // Check if local session exists and its updated_at + pinned_at
        var localUpdatedAt: Double?
        var localPinnedAt: Double?
        var localFolderId: String?
        let checkSql = "SELECT updated_at, pinned_at, folder_id FROM sessions WHERE id = ?"
        var checkStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, checkSql, -1, &checkStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(checkStmt, 1, (session.id as NSString).utf8String, -1, nil)
            if sqlite3_step(checkStmt) == SQLITE_ROW {
                localUpdatedAt = sqlite3_column_double(checkStmt, 0)
                if sqlite3_column_type(checkStmt, 1) != SQLITE_NULL {
                    localPinnedAt = sqlite3_column_double(checkStmt, 1)
                }
                localFolderId = sqlite3_column_text(checkStmt, 2).map { String(cString: $0) }
            }
        }
        sqlite3_finalize(checkStmt)

        let remoteUpdated = session.updatedAt.timeIntervalSince1970

        // Resolve pinned_at merge:
        // - remotePinnedAtRaw == nil → old device, don't touch local pin state
        // - remotePinnedAtRaw == epoch 0 → explicit unpin from remote
        // - remotePinnedAtRaw > 0 → pin from remote
        // Rule: most recent pin/unpin wins (by timestamp). If remote has no pin info, keep local.
        let resolvedPinnedAt: Double? = {
            guard let rawDate = remotePinnedAtRaw else {
                // Old device didn't send pinnedAt — preserve local state
                return localPinnedAt
            }
            let remoteTs = rawDate.timeIntervalSince1970
            if remoteTs <= 0 {
                // Remote explicitly unpinned
                if let localTs = localPinnedAt {
                    // Local is pinned — keep local pin (local action is more recent if user pinned locally)
                    // We can't know which is newer without a dedicated pin_updated_at, so keep local pin
                    // to avoid accidental unpin from stale remote state
                    return localTs
                }
                return nil // both unpinned
            } else {
                // Remote is pinned
                if let localTs = localPinnedAt {
                    // Both pinned — keep the more recent pin timestamp
                    return max(remoteTs, localTs)
                }
                // Local not pinned, remote is pinned — adopt remote pin
                return remoteTs
            }
        }()

        if let localTs = localUpdatedAt {
            // Local exists — only update title/category/etc if remote is newer
            if remoteUpdated > localTs {
                iCloudLogger.info("[iCloud] mergeRemoteSession UPDATE (remote newer): id=\(session.id) remoteTs=\(remoteUpdated) localTs=\(localTs)")
                // folder_id is written ONLY when the peer's record carried
                // the field — an old build's record omitting it must not wipe
                // a local folder assignment (same tolerance pinnedAt got).
                let sql = remoteHasFolderField
                    ? "UPDATE sessions SET title = ?, category = ?, updated_at = ?, memory_enabled = ?, model_binding = ?, pinned_at = ?, folder_id = ? WHERE id = ?"
                    : "UPDATE sessions SET title = ?, category = ?, updated_at = ?, memory_enabled = ?, model_binding = ?, pinned_at = ? WHERE id = ?"
                var stmt: OpaquePointer?
                if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                    bindOptionalText(stmt, index: 1, value: session.title)
                    bindOptionalText(stmt, index: 2, value: session.category)
                    sqlite3_bind_double(stmt, 3, remoteUpdated)
                    sqlite3_bind_int(stmt, 4, memoryEnabled ? 1 : 0)
                    bindOptionalText(stmt, index: 5, value: modelBinding)
                    if let pinTs = resolvedPinnedAt {
                        sqlite3_bind_double(stmt, 6, pinTs)
                    } else {
                        sqlite3_bind_null(stmt, 6)
                    }
                    if remoteHasFolderField {
                        bindOptionalText(stmt, index: 7, value: remoteFolderId)
                        sqlite3_bind_text(stmt, 8, (session.id as NSString).utf8String, -1, nil)
                    } else {
                        sqlite3_bind_text(stmt, 7, (session.id as NSString).utf8String, -1, nil)
                    }
                    sqlite3_step(stmt)
                }
                sqlite3_finalize(stmt)
                // Wake the home list. ContentView observes
                // .sessionDidUpdate (debounce 3s — fine for title/pin
                // edits arriving from a peer). Without this post a
                // remote-newer merge silently updated SQLite while the
                // list kept showing the stale snapshot until the user
                // backgrounded the app or pulled-to-refresh
                // (T-inbound-session-no-refresh).
                NotificationCenter.default.post(name: .sessionDidUpdate, object: session.id)
            } else {
                let delta = localTs - remoteUpdated
                iCloudLogger.info("[iCloud] mergeRemoteSession SKIP (local newer): id=\(session.id) localTs=\(localTs) remoteTs=\(remoteUpdated) delta=\(String(format: "%+.3f", delta))s from=\(fromDeviceId)")
                if remoteUpdated > localTs {
                    iCloudLogger.warning("[iCloud] mergeRemoteSession SKIP despite remote-newer: id=\(session.id) localTs=\(localTs) remoteTs=\(remoteUpdated) — should not happen")
                }
                // Folder membership needs the same skip-branch sub-merge as
                // pin state, and for the same root cause: filing a session
                // does NOT bump updated_at (an organizational move must not
                // resort the list), so a peer's folder move arrives with an
                // unchanged session timestamp, lands in THIS branch, and
                // would otherwise never apply anywhere.
                //
                // Conservative rule, mirroring the stale-unpin guard below:
                // adopt the remote folder only when we have no local opinion
                // (local NULL). remote-NULL-vs-local-set and set-vs-set-
                // different keep local — without a per-field timestamp we
                // can't rank the two writes, and this is the local-newer
                // branch. Known accepted limitation (same class as pin's):
                // a bare "move out of folder" with no other session activity
                // does not propagate; the dissolve path is unaffected since
                // it also ships a FolderV2 tombstone that clears members.
                if remoteHasFolderField, localFolderId == nil, let remoteFid = remoteFolderId {
                    let fSql = "UPDATE sessions SET folder_id = ? WHERE id = ? AND folder_id IS NULL"
                    var fStmt: OpaquePointer?
                    if sqlite3_prepare_v2(db, fSql, -1, &fStmt, nil) == SQLITE_OK {
                        sqlite3_bind_text(fStmt, 1, (remoteFid as NSString).utf8String, -1, nil)
                        sqlite3_bind_text(fStmt, 2, (session.id as NSString).utf8String, -1, nil)
                        sqlite3_step(fStmt)
                    }
                    sqlite3_finalize(fStmt)
                    iCloudLogger.info("[iCloud] mergeRemoteSession folder sub-merge: id=\(session.id.prefix(8)) -> folder \(remoteFid.prefix(8))")
                    NotificationCenter.default.post(name: .sessionDidUpdate, object: session.id)
                }
                // Even if we skip the main update, still merge pin state if remote has pin info
                // and the resolved pin differs from local
                if remotePinnedAtRaw != nil, resolvedPinnedAt != localPinnedAt {
                    let pinSql = "UPDATE sessions SET pinned_at = ? WHERE id = ?"
                    var pinStmt: OpaquePointer?
                    if sqlite3_prepare_v2(db, pinSql, -1, &pinStmt, nil) == SQLITE_OK {
                        if let pinTs = resolvedPinnedAt {
                            sqlite3_bind_double(pinStmt, 1, pinTs)
                        } else {
                            sqlite3_bind_null(pinStmt, 1)
                        }
                        sqlite3_bind_text(pinStmt, 2, (session.id as NSString).utf8String, -1, nil)
                        sqlite3_step(pinStmt)
                    }
                    sqlite3_finalize(pinStmt)
                }
            }
        } else {
            // [T-icloud-deleted-session-resurrection] Refuse to resurrect a
            // session the user just deleted locally. fetchRecentV2 can pull the
            // still-present cloud record back before our delete tombstone lands;
            // without this guard the session reappears (with a ☁️ remote-origin
            // marker). Only block when the inbound record isn't newer than the
            // deletion — a genuine newer re-create of the same id still applies.
            if isResurrectionOfDeleted(session.id, remoteUpdatedAt: Date(timeIntervalSince1970: remoteUpdated)) {
                iCloudLogger.info("[iCloud] mergeRemoteSession SKIP (recently deleted locally — resurrection blocked): id=\(session.id) remoteTs=\(remoteUpdated)")
                return
            }
            // Local doesn't have this session — insert
            iCloudLogger.info("[iCloud] mergeRemoteSession INSERT: id=\(session.id) title=\(session.title ?? "nil") from=\(fromDeviceId)")
            let sql = "INSERT INTO sessions (id, title, category, model_id, created_at, updated_at, remote_origin_device_id, memory_enabled, model_binding, pinned_at, folder_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                sqlite3_bind_text(stmt, 1, (session.id as NSString).utf8String, -1, nil)
                bindOptionalText(stmt, index: 2, value: session.title)
                bindOptionalText(stmt, index: 3, value: session.category)
                sqlite3_bind_text(stmt, 4, (session.modelId as NSString).utf8String, -1, nil)
                sqlite3_bind_double(stmt, 5, session.createdAt.timeIntervalSince1970)
                sqlite3_bind_double(stmt, 6, remoteUpdated)
                sqlite3_bind_text(stmt, 7, (fromDeviceId as NSString).utf8String, -1, nil)
                sqlite3_bind_int(stmt, 8, memoryEnabled ? 1 : 0)
                bindOptionalText(stmt, index: 9, value: modelBinding)
                if let pinTs = resolvedPinnedAt {
                    sqlite3_bind_double(stmt, 10, pinTs)
                } else {
                    sqlite3_bind_null(stmt, 10)
                }
                // A folder_id referencing a folder we don't have yet is fine —
                // the list renders it as ungrouped until FolderV2 arrives
                // (fetchRecentV2 ordering gives no cross-type guarantees).
                bindOptionalText(stmt, index: 11, value: remoteHasFolderField ? remoteFolderId : nil)
                sqlite3_step(stmt)
            }
            sqlite3_finalize(stmt)
            // Brand-new remote session — post `.sessionDidCreate` so the
            // home list reloads immediately (no debounce on this name).
            // Matches the local-create path at AIChatViewModel.swift:4266
            // (T-inbound-session-no-refresh).
            NotificationCenter.default.post(name: .sessionDidCreate, object: session.id)
        }

        // Backfill: messages may have arrived before this session was merged,
        // or the session already existed locally and remote messages were stored
        // only in remote_messages. Always try to backfill missing messages.
        // [T-ios-listsessions-part-flags] inline bitmask (see backfillRemoteToLocal).
        let backfillSql = """
            INSERT OR IGNORE INTO messages (id, session_id, role, parts_json, created_at, token_usage, sort_order, reasoning_content, stream_interrupt_count, updated_at, part_flags)
            SELECT rm.id, rm.session_id, rm.role, rm.parts_json, rm.created_at, rm.token_usage, rm.sort_order, rm.reasoning_content, rm.stream_interrupt_count, rm.created_at, \(Self.partFlagsSQLExpr("rm.parts_json"))
            FROM remote_messages rm WHERE rm.session_id = ? AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.id = rm.id)
        """
        var bfStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, backfillSql, -1, &bfStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(bfStmt, 1, (session.id as NSString).utf8String, -1, nil)
            sqlite3_step(bfStmt)
        }
        sqlite3_finalize(bfStmt)
        let backfilled = sqlite3_changes(db)
        if backfilled > 0 {
            iCloudLogger.info("[iCloud] mergeRemoteSession backfilled \(backfilled) messages for \(session.id)")
        }
    }

    /// Delete a local session if it originated from the given device (remote deletion propagation).
    func deleteLocalSessionIfFromDevice(id: String, deviceId: String) {
        invalidateSessionListCache()
        let sql = "DELETE FROM sessions WHERE id = ? AND remote_origin_device_id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (deviceId as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
            let deleted = sqlite3_changes(db)
            if deleted > 0 {
                iCloudLogger.info("[iCloud] deleteLocalSessionIfFromDevice: deleted session \(id) (origin: \(deviceId))")
                // Also clean up messages
                let delMsgSql = "DELETE FROM messages WHERE session_id = ?"
                var delMsgStmt: OpaquePointer?
                if sqlite3_prepare_v2(db, delMsgSql, -1, &delMsgStmt, nil) == SQLITE_OK {
                    sqlite3_bind_text(delMsgStmt, 1, (id as NSString).utf8String, -1, nil)
                    sqlite3_step(delMsgStmt)
                }
                sqlite3_finalize(delMsgStmt)
            }
        }
        sqlite3_finalize(stmt)
    }

    /// Merge a remote message into local messages table if the session exists locally.
    /// Uses conditional upsert: INSERT if new, UPDATE if remote updated_at is newer.
    func mergeRemoteMessage(
        id: String, sessionId: String, role: String, partsJson: String,
        createdAt: Date, tokenUsageJson: String?, sortOrder: Int,
        reasoningContent: String?, streamInterruptCount: Int,
        updatedAt: Date? = nil,
        // [T-token-attribution-snapshot] nil when the sending device predates
        // these fields. nil means "the remote told us nothing", NOT "the remote
        // says this is empty" — the UPDATE below preserves the local value in
        // that case. See the COALESCE note there.
        modelId: String? = nil, modelDisplayName: String? = nil,
        providerType: String? = nil, providerInstanceId: String? = nil
    ) {
        invalidateSessionListCache()
        // Defer merging into a session that's actively running locally. While
        // an agent loop is streaming / running tools, the message list is
        // volatile and locally authoritative: appends bump sort_order and
        // updated_at every tool round. A remote message arriving mid-run (often
        // this device's own just-pushed rows echoed back by CloudKit) would
        // win LWW or, worse, re-rank sort_order via the insert path's shift —
        // scrambling the running session so listSessions can't resolve its
        // preview ("No messages yet") and stale pre-retry content reappears.
        // CloudKit re-delivers unmerged changes on the next fetch, so skipping
        // here is safe: the session re-syncs once the run finishes and the
        // tracker clears. [T-sync-clobbers-running-session]
        if SessionActivityTracker.isActiveThreadSafe(sessionId) {
            iCloudLogger.info("[iCloud] mergeRemoteMessage DEFER (session running locally): id=\(id.prefix(8)) sid=\(sessionId.prefix(8))")
            return
        }

        // Only merge if the session exists in local sessions table
        let checkSql = "SELECT id FROM sessions WHERE id = ?"
        var checkStmt: OpaquePointer?
        var sessionExists = false
        if sqlite3_prepare_v2(db, checkSql, -1, &checkStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(checkStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            sessionExists = sqlite3_step(checkStmt) == SQLITE_ROW
        }
        sqlite3_finalize(checkStmt)
        guard sessionExists else { return }

        // [T-icloud-deleted-session-resurrection] Defence in depth: even if the
        // parent session row somehow exists, refuse to re-insert a message whose
        // session was just deleted locally and isn't newer than the deletion —
        // so a stale inbound message can't resurrect content under a tombstoned
        // session. (The session guard above normally already blocks this, since
        // a deleted session has no row.)
        let msgUpdatedAt = updatedAt ?? createdAt
        if isResurrectionOfDeleted(sessionId, remoteUpdatedAt: msgUpdatedAt) {
            iCloudLogger.info("[iCloud] mergeRemoteMessage SKIP (parent session recently deleted — resurrection blocked): id=\(id.prefix(8)) sid=\(sessionId.prefix(8))")
            return
        }

        // Effective updated_at: use provided value, or fall back to created_at
        // (old devices don't send updated_at)
        let effectiveUpdatedAt = (updatedAt ?? createdAt).timeIntervalSince1970

        // Check if message already exists locally and its updated_at
        var localUpdatedAt: Double?
        let existSql = "SELECT COALESCE(updated_at, created_at) FROM messages WHERE id = ?"
        var existStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, existSql, -1, &existStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(existStmt, 1, (id as NSString).utf8String, -1, nil)
            if sqlite3_step(existStmt) == SQLITE_ROW {
                localUpdatedAt = sqlite3_column_double(existStmt, 0)
            }
        }
        sqlite3_finalize(existStmt)

        if let localTs = localUpdatedAt {
            // Message exists — only update if remote is strictly newer
            guard effectiveUpdatedAt > localTs else {
                let delta = localTs - effectiveUpdatedAt
                // Even when LWW says skip, opportunistically heal a
                // stale local sort_order if the remote value disagrees.
                // Common case: a previous build pushed sortOrder=0 for
                // every message and the receiver's collision-by-arrival
                // path stamped them with arbitrary positions. New build
                // pushes correct sortOrder but updatedAt is unchanged
                // (force-sync just re-uploads the same content), so
                // strict-newer LWW would otherwise stay forever wrong.
                // Note: previous builds healed local sort_order from the remote integer
                // here. That's now intentionally dropped — remote sortOrder is a
                // best-effort hint; the receiver derives its own from (created_at, id)
                // rank on insert, and `repairSession` fixes any rank inversions on
                // session open. See sessionNeedsRepair / mergeRemoteMessage insert path.
                iCloudLogger.info("[iCloud] mergeRemoteMessage SKIP (local newer): id=\(id) localTs=\(localTs) remoteTs=\(effectiveUpdatedAt) delta=\(String(format: "%+.3f", delta))s")
                return
            }
            iCloudLogger.info("[iCloud] mergeRemoteMessage UPDATE (remote newer): id=\(id) remoteTs=\(effectiveUpdatedAt) localTs=\(localTs)")
            // Don't overwrite local sort_order from remote — receivers own ordering
            // (derived from created_at rank on insert; repaired by repairSession).
            // [T-ios-listsessions-part-flags] recompute from the inbound JSON.
            let partFlags = Self.partFlags(fromPartsJSON: partsJson)
            // [T-token-attribution-snapshot] The four attribution columns use
            // COALESCE(?, col) rather than a plain assignment.
            //
            // A device running an older build re-sends this message WITHOUT
            // these fields. A plain `model_id = ?` would then bind NULL and
            // erase a snapshot this device recorded correctly — and because
            // conflict resolution is last-write-wins on updated_at, the older
            // device can legitimately win. The result would be attribution that
            // silently disappears at random, which is close to impossible to
            // diagnose from a bug report.
            //
            // COALESCE encodes the real semantics: absent means "no opinion",
            // so keep what we have. Note this is only correct for snapshot-type
            // columns; parts_json and friends must still be overwritten, since
            // for those an incoming value genuinely is the newer truth.
            let updateSql = """
                UPDATE messages SET parts_json = ?, token_usage = ?, reasoning_content = ?,
                    stream_interrupt_count = ?, updated_at = ?, part_flags = ?,
                    model_id = COALESCE(?, model_id),
                    model_display_name = COALESCE(?, model_display_name),
                    provider_type = COALESCE(?, provider_type),
                    provider_instance_id = COALESCE(?, provider_instance_id)
                WHERE id = ?
            """
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(db, updateSql, -1, &stmt, nil) == SQLITE_OK {
                sqlite3_bind_text(stmt, 1, (partsJson as NSString).utf8String, -1, nil)
                if let tu = tokenUsageJson { sqlite3_bind_text(stmt, 2, (tu as NSString).utf8String, -1, nil) }
                else { sqlite3_bind_null(stmt, 2) }
                if let r = reasoningContent { sqlite3_bind_text(stmt, 3, (r as NSString).utf8String, -1, nil) }
                else { sqlite3_bind_null(stmt, 3) }
                sqlite3_bind_int(stmt, 4, Int32(streamInterruptCount))
                sqlite3_bind_double(stmt, 5, effectiveUpdatedAt)
                sqlite3_bind_int64(stmt, 6, Int64(partFlags))
                // 7-10 feed the COALESCE guards: binding NULL leaves the
                // existing column untouched, which is what an older sender
                // (who knows nothing of these fields) must not disturb.
                bindOptionalText(stmt, index: 7, value: modelId)
                bindOptionalText(stmt, index: 8, value: modelDisplayName)
                bindOptionalText(stmt, index: 9, value: providerType)
                bindOptionalText(stmt, index: 10, value: providerInstanceId)
                sqlite3_bind_text(stmt, 11, (id as NSString).utf8String, -1, nil)
                sqlite3_step(stmt)
            }
            sqlite3_finalize(stmt)
        } else {
            // Message doesn't exist — insert.
            // Always derive finalSortOrder from (created_at, id) rank against
            // existing local rows; never trust the wire integer. The remote
            // sortOrder is a best-effort hint that's wrong whenever sender
            // and receiver have differing message counts (sender's larger
            // values would otherwise stick on the receiver and push synced
            // messages past the local tail). Tiebreak by id lexicographic
            // to match the canonical (created_at ASC, id ASC) order used by
            // repairSession at line ~2408.
            let createdAtTs = createdAt.timeIntervalSince1970
            let earlierSql = """
                SELECT COALESCE(MAX(sort_order), -1)
                FROM messages
                WHERE session_id = ?
                  AND (created_at < ? OR (created_at = ? AND id < ?))
            """
            var earlierStmt: OpaquePointer?
            var earlierMax: Int32 = -1
            if sqlite3_prepare_v2(db, earlierSql, -1, &earlierStmt, nil) == SQLITE_OK {
                sqlite3_bind_text(earlierStmt, 1, (sessionId as NSString).utf8String, -1, nil)
                sqlite3_bind_double(earlierStmt, 2, createdAtTs)
                sqlite3_bind_double(earlierStmt, 3, createdAtTs)
                sqlite3_bind_text(earlierStmt, 4, (id as NSString).utf8String, -1, nil)
                if sqlite3_step(earlierStmt) == SQLITE_ROW {
                    earlierMax = sqlite3_column_int(earlierStmt, 0)
                }
            }
            sqlite3_finalize(earlierStmt)

            let laterSql = """
                SELECT MIN(sort_order)
                FROM messages
                WHERE session_id = ?
                  AND (created_at > ? OR (created_at = ? AND id > ?))
            """
            var laterStmt: OpaquePointer?
            var hasLater = false
            var laterMin: Int32 = 0
            if sqlite3_prepare_v2(db, laterSql, -1, &laterStmt, nil) == SQLITE_OK {
                sqlite3_bind_text(laterStmt, 1, (sessionId as NSString).utf8String, -1, nil)
                sqlite3_bind_double(laterStmt, 2, createdAtTs)
                sqlite3_bind_double(laterStmt, 3, createdAtTs)
                sqlite3_bind_text(laterStmt, 4, (id as NSString).utf8String, -1, nil)
                if sqlite3_step(laterStmt) == SQLITE_ROW {
                    if sqlite3_column_type(laterStmt, 0) != SQLITE_NULL {
                        hasLater = true
                        laterMin = sqlite3_column_int(laterStmt, 0)
                    }
                }
            }
            sqlite3_finalize(laterStmt)

            let finalSortOrder: Int
            if !hasLater {
                // Append: nothing comes after.
                finalSortOrder = Int(earlierMax) + 1
            } else if earlierMax + 1 < laterMin {
                // Gap available — slot in without shifting.
                finalSortOrder = Int(earlierMax) + 1
            } else {
                // Shift all later rows up by 1 to make room.
                let shiftSql = "UPDATE messages SET sort_order = sort_order + 1 WHERE session_id = ? AND sort_order >= ?"
                var shiftStmt: OpaquePointer?
                if sqlite3_prepare_v2(db, shiftSql, -1, &shiftStmt, nil) == SQLITE_OK {
                    sqlite3_bind_text(shiftStmt, 1, (sessionId as NSString).utf8String, -1, nil)
                    sqlite3_bind_int(shiftStmt, 2, laterMin)
                    sqlite3_step(shiftStmt)
                }
                sqlite3_finalize(shiftStmt)
                finalSortOrder = Int(laterMin)
            }

            iCloudLogger.info("[iCloud] mergeRemoteMessage INSERT: id=\(id) session=\(sessionId) sortOrder=\(finalSortOrder) (remoteHint=\(sortOrder))")
            // [T-ios-listsessions-part-flags] recompute from the inbound JSON.
            let partFlags = Self.partFlags(fromPartsJSON: partsJson)
            let insertSql = """
                INSERT INTO messages (id, session_id, role, parts_json, created_at, token_usage, sort_order, reasoning_content, stream_interrupt_count, updated_at, part_flags, model_id, model_display_name, provider_type, provider_instance_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(db, insertSql, -1, &stmt, nil) == SQLITE_OK {
                sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
                sqlite3_bind_text(stmt, 2, (sessionId as NSString).utf8String, -1, nil)
                sqlite3_bind_text(stmt, 3, (role as NSString).utf8String, -1, nil)
                sqlite3_bind_text(stmt, 4, (partsJson as NSString).utf8String, -1, nil)
                sqlite3_bind_double(stmt, 5, createdAt.timeIntervalSince1970)
                if let tu = tokenUsageJson { sqlite3_bind_text(stmt, 6, (tu as NSString).utf8String, -1, nil) }
                else { sqlite3_bind_null(stmt, 6) }
                sqlite3_bind_int(stmt, 7, Int32(finalSortOrder))
                if let r = reasoningContent { sqlite3_bind_text(stmt, 8, (r as NSString).utf8String, -1, nil) }
                else { sqlite3_bind_null(stmt, 8) }
                sqlite3_bind_int(stmt, 9, Int32(streamInterruptCount))
                sqlite3_bind_double(stmt, 10, effectiveUpdatedAt)
                sqlite3_bind_int64(stmt, 11, Int64(partFlags))
                // [T-token-attribution-snapshot] Carried through so a message
                // that first arrives from another device keeps the attribution
                // that device measured. Plain binds (not COALESCE) — on INSERT
                // there is no local value to protect.
                bindOptionalText(stmt, index: 12, value: modelId)
                bindOptionalText(stmt, index: 13, value: modelDisplayName)
                bindOptionalText(stmt, index: 14, value: providerType)
                bindOptionalText(stmt, index: 15, value: providerInstanceId)
                sqlite3_step(stmt)
            }
            sqlite3_finalize(stmt)
        }
        // Notify the in-process ViewModel cache that this session's
        // messages have changed via iCloud merge. The foreground-visible
        // VM already reloads via `.cloudSyncDidFetchChanges` at the end
        // of the batch (AIChatViewModel:666), but a cached background VM
        // for a non-active session would otherwise render its stale
        // messages snapshot the next time the user opens it. The flag
        // is consumed on the next getOrCreate HIT
        // (T-inbound-message-cached-vm-stale).
        let sid = sessionId
        Task { @MainActor in
            ViewModelCache.shared.markStale(sessionId: sid)
        }
    }

    func deleteRemoteSession(id: String, deviceId: String) {
        // Parameterized deletes to avoid SQL injection
        for table in ["remote_messages", "remote_media"] {
            let sql = "DELETE FROM \(table) WHERE session_id = ? AND device_id = ?"
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
                sqlite3_bind_text(stmt, 2, (deviceId as NSString).utf8String, -1, nil)
                sqlite3_step(stmt)
            }
            sqlite3_finalize(stmt)
        }
        let sql = "DELETE FROM remote_sessions WHERE id = ? AND device_id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (deviceId as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    func listRemoteSessions(deviceId: String) -> [ChatSession] {
        iCloudLogger.info("[iCloud] listRemoteSessions: querying deviceId=\(deviceId)")
        let sql = """
            SELECT id, title, category, model_id, created_at, updated_at
            FROM remote_sessions WHERE device_id = ? ORDER BY updated_at DESC
        """
        var stmt: OpaquePointer?
        var sessions: [ChatSession] = []
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (deviceId as NSString).utf8String, -1, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                sessions.append(ChatSession(
                    id: String(cString: sqlite3_column_text(stmt, 0)),
                    title: sqlite3_column_text(stmt, 1).map { String(cString: $0) },
                    category: sqlite3_column_text(stmt, 2).map { String(cString: $0) },
                    modelId: String(cString: sqlite3_column_text(stmt, 3)),
                    createdAt: Date(timeIntervalSince1970: sqlite3_column_double(stmt, 4)),
                    updatedAt: Date(timeIntervalSince1970: sqlite3_column_double(stmt, 5))
                ))
            }
        }
        sqlite3_finalize(stmt)
        iCloudLogger.info("[iCloud] listRemoteSessions: deviceId=\(deviceId) returned \(sessions.count) sessions")
        for s in sessions {
            iCloudLogger.info("[iCloud]   session: id=\(s.id) title=\(s.title ?? "nil")")
        }
        return sessions
    }

    // MARK: - Remote Message CRUD

    func upsertRemoteMessage(
        id: String, deviceId: String, sessionId: String, role: String,
        partsJson: String, createdAt: Date, tokenUsageJson: String?,
        sortOrder: Int, reasoningContent: String?, streamInterruptCount: Int
    ) {
        // Create placeholder session if it doesn't exist yet — messages may arrive
        // before the Session record on the receiving device.
        let placeholderSQL = """
            INSERT OR IGNORE INTO remote_sessions (id, device_id, title, category, model_id, created_at, updated_at)
            VALUES (?, ?, NULL, NULL, 'unknown', ?, ?)
        """
        var pStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, placeholderSQL, -1, &pStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(pStmt, 1, (sessionId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(pStmt, 2, (deviceId as NSString).utf8String, -1, nil)
            sqlite3_bind_double(pStmt, 3, createdAt.timeIntervalSince1970)
            sqlite3_bind_double(pStmt, 4, createdAt.timeIntervalSince1970)
            sqlite3_step(pStmt)
        }
        sqlite3_finalize(pStmt)

        let sql = """
            INSERT OR REPLACE INTO remote_messages
            (id, device_id, session_id, role, parts_json, created_at, token_usage, sort_order, reasoning_content, stream_interrupt_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (deviceId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 3, (sessionId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 4, (role as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 5, (partsJson as NSString).utf8String, -1, nil)
            sqlite3_bind_double(stmt, 6, createdAt.timeIntervalSince1970)
            bindOptionalText(stmt, index: 7, value: tokenUsageJson)
            sqlite3_bind_int64(stmt, 8, Int64(sortOrder))
            bindOptionalText(stmt, index: 9, value: reasoningContent)
            sqlite3_bind_int64(stmt, 10, Int64(streamInterruptCount))
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    /// Delete a single message from the local messages table by its message ID.
    /// Used when a remote device signals a message-level deletion (retry / manual delete)
    /// that should propagate across devices.
    func deleteLocalMessage(messageId: String) {
        invalidateSessionListCache()
        let sql = "DELETE FROM messages WHERE id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (messageId as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        let changes = Int(sqlite3_changes(db))
        sqlite3_finalize(stmt)
        if changes > 0 {
            iCloudLogger.warning("[EditSync] [iCloud] deleteLocalMessage: removed '\(messageId.prefix(8))' from local messages (inbound tombstone applied)")
        } else {
            // [T-ios-log-noise-reduction] Downgraded WARN→DEBUG: a delete for a
            // message that never landed locally (synced from another device that
            // both created and deleted it while we were away) is the normal
            // tombstone path, not a warning. Was ~6.4k WARN lines per batch sync.
            iCloudLogger.debug("[EditSync] [iCloud] deleteLocalMessage: NO-OP '\(messageId.prefix(8))' — message not present locally (already gone or never arrived)")
        }
    }

    func deleteRemoteMessage(id: String, deviceId: String) {
        let sql = "DELETE FROM remote_messages WHERE id = ? AND device_id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (deviceId as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    func loadRemoteMessages(sessionId: String, deviceId: String) -> [RawMessage] {
        let sql = """
            SELECT id, session_id, role, parts_json, created_at, token_usage, reasoning_content, stream_interrupt_count
            FROM remote_messages WHERE session_id = ? AND device_id = ? ORDER BY sort_order ASC, created_at ASC, id ASC
        """
        var stmt: OpaquePointer?
        var messages: [RawMessage] = []

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (deviceId as NSString).utf8String, -1, nil)

            while sqlite3_step(stmt) == SQLITE_ROW {
                let id = String(cString: sqlite3_column_text(stmt, 0))
                let sessionId = String(cString: sqlite3_column_text(stmt, 1))
                let roleStr = String(cString: sqlite3_column_text(stmt, 2))
                let partsJson = String(cString: sqlite3_column_text(stmt, 3))
                let createdAt = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 4))
                let tokenUsageStr = sqlite3_column_text(stmt, 5).map { String(cString: $0) }
                let reasoningContent = sqlite3_column_text(stmt, 6).map { String(cString: $0) }
                let streamInterruptCount = Int(sqlite3_column_int(stmt, 7))

                let role = MessageRole(rawValue: roleStr) ?? .user
                let parts = (try? JSONDecoder().decode([ContentPart].self, from: Data(partsJson.utf8))) ?? []
                let tokenUsage = tokenUsageStr.flatMap { str in
                    try? JSONDecoder().decode(StoredTokenUsage.self, from: Data(str.utf8))
                }

                messages.append(RawMessage(
                    id: id, sessionId: sessionId, role: role, parts: parts,
                    createdAt: createdAt, tokenUsage: tokenUsage,
                    reasoningContent: reasoningContent,
                    streamInterruptCount: streamInterruptCount
                ))
            }
        }
        sqlite3_finalize(stmt)
        return messages
    }

    // MARK: - Remote Media CRUD

    func upsertRemoteMedia(
        id: String, deviceId: String, sessionId: String,
        relativePath: String, mimeType: String?,
        originalFileName: String?, localCachePath: String?
    ) {
        let sql = """
            INSERT OR REPLACE INTO remote_media
            (id, device_id, session_id, relative_path, mime_type, original_file_name, local_cache_path)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (deviceId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 3, (sessionId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 4, (relativePath as NSString).utf8String, -1, nil)
            bindOptionalText(stmt, index: 5, value: mimeType)
            bindOptionalText(stmt, index: 6, value: originalFileName)
            bindOptionalText(stmt, index: 7, value: localCachePath)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    // MARK: - Remote Skill CRUD

    func upsertRemoteSkill(
        id: String, deviceId: String, name: String, description: String,
        version: String, importSource: String, isEnabled: Bool,
        installedAt: Date, updatedAt: Date, bodyText: String
    ) {
        let sql = """
            INSERT OR REPLACE INTO remote_skills
            (id, device_id, name, description, version, import_source, is_enabled, installed_at, updated_at, body_text)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (deviceId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 3, (name as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 4, (description as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 5, (version as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 6, (importSource as NSString).utf8String, -1, nil)
            sqlite3_bind_int(stmt, 7, isEnabled ? 1 : 0)
            sqlite3_bind_double(stmt, 8, installedAt.timeIntervalSince1970)
            sqlite3_bind_double(stmt, 9, updatedAt.timeIntervalSince1970)
            sqlite3_bind_text(stmt, 10, (bodyText as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    func deleteRemoteSkill(id: String, deviceId: String) {
        let sql = "DELETE FROM remote_skills WHERE id = ? AND device_id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (deviceId as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    func listRemoteSkills(deviceId: String) -> [Skill] {
        let sql = """
            SELECT id, name, description, version, import_source, is_enabled, installed_at, updated_at, body_text
            FROM remote_skills WHERE device_id = ?
        """
        var stmt: OpaquePointer?
        var skills: [Skill] = []
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (deviceId as NSString).utf8String, -1, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                skills.append(Skill(
                    id: String(cString: sqlite3_column_text(stmt, 0)),
                    name: String(cString: sqlite3_column_text(stmt, 1)),
                    description: String(cString: sqlite3_column_text(stmt, 2)),
                    version: String(cString: sqlite3_column_text(stmt, 3)),
                    importSource: SkillImportSource.fromDB(String(cString: sqlite3_column_text(stmt, 4))),
                    isEnabled: sqlite3_column_int(stmt, 5) == 1,
                    installedAt: Date(timeIntervalSince1970: sqlite3_column_double(stmt, 6)),
                    updatedAt: Date(timeIntervalSince1970: sqlite3_column_double(stmt, 7)),
                    body: String(cString: sqlite3_column_text(stmt, 8))
                ))
            }
        }
        sqlite3_finalize(stmt)
        return skills
    }

    // MARK: - Remote Memory CRUD

    func upsertRemoteMemory(
        id: String, deviceId: String, fileName: String,
        content: String, updatedAt: Date
    ) {
        let sql = """
            INSERT OR REPLACE INTO remote_memories (id, device_id, file_name, content, updated_at)
            VALUES (?, ?, ?, ?, ?)
        """
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (deviceId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 3, (fileName as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 4, (content as NSString).utf8String, -1, nil)
            sqlite3_bind_double(stmt, 5, updatedAt.timeIntervalSince1970)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    func deleteRemoteMemory(id: String, deviceId: String) {
        let sql = "DELETE FROM remote_memories WHERE id = ? AND device_id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (deviceId as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    func listRemoteMemories(deviceId: String) -> [RemoteMemory] {
        let sql = "SELECT id, device_id, file_name, content, updated_at FROM remote_memories WHERE device_id = ?"
        var stmt: OpaquePointer?
        var memories: [RemoteMemory] = []
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (deviceId as NSString).utf8String, -1, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                memories.append(RemoteMemory(
                    id: String(cString: sqlite3_column_text(stmt, 0)),
                    deviceId: String(cString: sqlite3_column_text(stmt, 1)),
                    fileName: String(cString: sqlite3_column_text(stmt, 2)),
                    content: String(cString: sqlite3_column_text(stmt, 3)),
                    updatedAt: Date(timeIntervalSince1970: sqlite3_column_double(stmt, 4))
                ))
            }
        }
        sqlite3_finalize(stmt)
        return memories
    }

    // MARK: - Remote Provider Configs

    func upsertRemoteProviderConfig(deviceId: String, configJson: String, updatedAt: Date) {
        let sql = """
            INSERT OR REPLACE INTO remote_provider_configs (device_id, config_json, updated_at)
            VALUES (?, ?, ?)
        """
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (deviceId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (configJson as NSString).utf8String, -1, nil)
            sqlite3_bind_double(stmt, 3, updatedAt.timeIntervalSince1970)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    func deleteRemoteProviderConfig(deviceId: String) {
        let sql = "DELETE FROM remote_provider_configs WHERE device_id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (deviceId as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    func loadRemoteProviderConfig(deviceId: String) -> (configJson: String, updatedAt: Date)? {
        let sql = "SELECT config_json, updated_at FROM remote_provider_configs WHERE device_id = ?"
        var stmt: OpaquePointer?
        var result: (String, Date)?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (deviceId as NSString).utf8String, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW {
                let json = String(cString: sqlite3_column_text(stmt, 0))
                let date = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 1))
                result = (json, date)
            }
        }
        sqlite3_finalize(stmt)
        return result
    }

    // MARK: - Remote Env Vars

    func upsertRemoteEnvVars(deviceId: String, envVarsJson: String, updatedAt: Date) {
        let sql = """
            INSERT OR REPLACE INTO remote_env_vars (device_id, env_vars_json, updated_at)
            VALUES (?, ?, ?)
        """
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (deviceId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (envVarsJson as NSString).utf8String, -1, nil)
            sqlite3_bind_double(stmt, 3, updatedAt.timeIntervalSince1970)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    func deleteRemoteEnvVars(deviceId: String) {
        let sql = "DELETE FROM remote_env_vars WHERE device_id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (deviceId as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    func loadRemoteEnvVars(deviceId: String) -> (envVarsJson: String, updatedAt: Date)? {
        let sql = "SELECT env_vars_json, updated_at FROM remote_env_vars WHERE device_id = ?"
        var stmt: OpaquePointer?
        var result: (String, Date)?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (deviceId as NSString).utf8String, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW {
                let json = String(cString: sqlite3_column_text(stmt, 0))
                let date = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 1))
                result = (json, date)
            }
        }
        sqlite3_finalize(stmt)
        return result
    }

    // MARK: - Clear Remote Tables

    /// Clear all remote reference tables AND pending dirty records.
    /// Used only by full state reset flows.
    func clearAllRemoteTables() {
        clearRemoteReferenceTables()
        exec("DELETE FROM sync_dirty_records")
    }

    /// Clear remote reference/mirror tables only.
    /// Preserves sync_dirty_records so pending local changes still get pushed.
    func clearRemoteReferenceTables() {
        exec("DELETE FROM remote_sessions")
        exec("DELETE FROM remote_messages")
        exec("DELETE FROM remote_media")
        exec("DELETE FROM remote_skills")
        exec("DELETE FROM remote_memories")
        exec("DELETE FROM remote_provider_configs")
        exec("DELETE FROM remote_env_vars")
        exec("DELETE FROM sync_devices")
    }

    // MARK: - Single Message Lookup (for sync)

    func messageSortOrder(id: String) -> Int {
        let sql = "SELECT sort_order FROM messages WHERE id = ?"
        var stmt: OpaquePointer?
        var order = 0
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW {
                order = Int(sqlite3_column_int(stmt, 0))
            }
        }
        sqlite3_finalize(stmt)
        return order
    }

    /// Return updated_at for a message, falling back to created_at if NULL (pre-migration data).
    func messageUpdatedAt(id: String) -> Date {
        let sql = "SELECT COALESCE(updated_at, created_at) FROM messages WHERE id = ?"
        var stmt: OpaquePointer?
        var ts: Double = 0
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW {
                ts = sqlite3_column_double(stmt, 0)
            }
        }
        sqlite3_finalize(stmt)
        return Date(timeIntervalSince1970: ts)
    }

    func loadSingleMessage(id: String) -> RawMessage? {
        let sql = """
            SELECT id, session_id, role, parts_json, created_at, token_usage, reasoning_content, stream_interrupt_count, sort_order
            FROM messages WHERE id = ?
        """
        var stmt: OpaquePointer?
        var message: RawMessage?

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW {
                let msgId = String(cString: sqlite3_column_text(stmt, 0))
                let sessionId = String(cString: sqlite3_column_text(stmt, 1))
                let roleStr = String(cString: sqlite3_column_text(stmt, 2))
                let partsJson = String(cString: sqlite3_column_text(stmt, 3))
                let createdAt = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 4))
                let tokenUsageStr = sqlite3_column_text(stmt, 5).map { String(cString: $0) }
                let reasoningContent = sqlite3_column_text(stmt, 6).map { String(cString: $0) }
                let streamInterruptCount = Int(sqlite3_column_int(stmt, 7))
                let sortOrder = Int(sqlite3_column_int64(stmt, 8))

                let role = MessageRole(rawValue: roleStr) ?? .user
                let parts = (try? JSONDecoder().decode([ContentPart].self, from: Data(partsJson.utf8))) ?? []
                let tokenUsage = tokenUsageStr.flatMap { str in
                    try? JSONDecoder().decode(StoredTokenUsage.self, from: Data(str.utf8))
                }

                var raw = RawMessage(
                    id: msgId, sessionId: sessionId, role: role, parts: parts,
                    createdAt: createdAt, tokenUsage: tokenUsage,
                    reasoningContent: reasoningContent,
                    streamInterruptCount: streamInterruptCount
                )
                raw.sortOrder = sortOrder
                message = raw
            }
        }
        sqlite3_finalize(stmt)
        return message
    }

    // MARK: - Size Estimation for Sync Categories

    /// Estimated size of all sessions + messages in the database.
    func estimateSessionsSize() -> Int64 {
        var total: Int64 = 0
        // Sessions table size (rough: count * avg row ~200 bytes)
        let sessionCount = queryInt64("SELECT COUNT(*) FROM sessions")
        total += sessionCount * 200

        // Messages: sum of parts_json lengths + overhead
        let partsSize = queryInt64("SELECT COALESCE(SUM(LENGTH(parts_json)), 0) FROM messages")
        let msgCount = queryInt64("SELECT COUNT(*) FROM messages")
        total += partsSize + msgCount * 100 // overhead per row
        return total
    }

    /// Estimated size of all session files (attachments, offloads, workspace, browser).
    func estimateFilesSize() -> Int64 {
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let minisBase = library.appendingPathComponent("MinisChat/minis")
        return directorySize(minisBase)
    }

    /// Estimated size of memory files.
    func estimateMemoriesSize() -> Int64 {
        return directorySize(AIChatViewModel.minisMemoryPersistentDir)
    }

    /// Estimated size of skills (db entries + skill files).
    func estimateSkillsSize() -> Int64 {
        return directorySize(AIChatViewModel.minisSkillsPersistentDir)
    }

    /// Estimated size of provider config JSON.
    func estimateProvidersSize() -> Int64 {
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let configURL = library.appendingPathComponent("MinisChat/provider-config.json")
        return (try? configURL.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
    }

    /// Estimated size of env vars JSON.
    func estimateEnvVarsSize() -> Int64 {
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let envURL = library.appendingPathComponent("MinisChat/env-vars.json")
        return (try? envURL.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
    }

    private func queryInt64(_ sql: String) -> Int64 {
        var stmt: OpaquePointer?
        var result: Int64 = 0
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            if sqlite3_step(stmt) == SQLITE_ROW {
                result = sqlite3_column_int64(stmt, 0)
            }
        }
        sqlite3_finalize(stmt)
        return result
    }

    private func directorySize(_ url: URL) -> Int64 {
        let fm = FileManager.default
        guard let enumerator = fm.enumerator(at: url, includingPropertiesForKeys: [.fileSizeKey], options: [.skipsHiddenFiles]) else { return 0 }
        var total: Int64 = 0
        for case let fileURL as URL in enumerator {
            if let size = try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                total += Int64(size)
            }
        }
        return total
    }
}


// MARK: - Backup Restore (docs/backup-restore-design.md §8)

/// Restore-side writes for the backup importer (docs/backup-restore-design.md §8.3).
///
/// These deliberately do NOT reuse `mergeRemoteSession` / `mergeRemoteMessage`.
/// Those are the CloudKit inbound path and carry three behaviours that are
/// correct for sync and wrong for a restore:
///
///  1. **`remote_origin_device_id`** — the sync insert stamps the originating
///     device, which makes the row render with a ☁️ remote badge and become
///     eligible for `deleteLocalSessionIfFromDevice`. Restored data is THIS
///     device's data; it must be indistinguishable from locally-authored rows,
///     so this column stays NULL. (The parameter is a non-optional `String`, so
///     the sync path physically cannot write NULL — passing "" would read back
///     as non-nil and still show the badge.)
///  2. **Tombstone resurrection guards** — `isResurrectionOfDeleted` silently
///     drops a record whose id was recently deleted locally. That is right for
///     a late CloudKit echo and wrong for a user explicitly asking to restore a
///     backup: they would get a "success" report with rows missing. The
///     importer clears the tombstone first (see `clearRestoreTombstone`).
///  3. **`SessionActivityTracker` deferral** — a running agent loop makes the
///     sync path skip the session entirely. A restore must not silently skip;
///     the importer refuses to touch a running session up front and reports it
///     instead.
///
/// Everything here is "last-writer-wins by updated_at", matching §8.2's Merge
/// semantics, and none of it writes `sync_pushed_records` or tombstones (§8.3).
extension ChatStore {

    enum RestoreOutcome {
        case inserted
        case updated
        /// Local copy is newer — Merge mode keeps it (§8.2).
        case skippedLocalNewer
        /// Parent session missing, so the row would dangle.
        case skippedNoParent
    }

    // MARK: - Sessions

    /// Upsert a session as locally-authored data.
    ///
    /// LWW on `updated_at`, so re-running a restore is a no-op and a newer local
    /// edit survives.
    func restoreSession(_ session: ChatSession,
                        memoryEnabled: Bool,
                        modelBinding: String?) -> RestoreOutcome {
        invalidateSessionListCache()

        let incoming = session.updatedAt.timeIntervalSince1970
        var localUpdatedAt: Double?
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, "SELECT updated_at FROM sessions WHERE id = ?", -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (session.id as NSString).utf8String, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW {
                localUpdatedAt = sqlite3_column_double(stmt, 0)
            }
        }
        sqlite3_finalize(stmt)

        if let local = localUpdatedAt {
            guard incoming > local else { return .skippedLocalNewer }
            let sql = """
                UPDATE sessions SET title = ?, category = ?, model_id = ?, updated_at = ?,
                                    memory_enabled = ?, model_binding = ?, pinned_at = ?, folder_id = ?
                WHERE id = ?
                """
            var up: OpaquePointer?
            if sqlite3_prepare_v2(db, sql, -1, &up, nil) == SQLITE_OK {
                bindText(up, 1, session.title)
                bindText(up, 2, session.category)
                sqlite3_bind_text(up, 3, (session.modelId as NSString).utf8String, -1, nil)
                sqlite3_bind_double(up, 4, incoming)
                sqlite3_bind_int(up, 5, memoryEnabled ? 1 : 0)
                bindText(up, 6, modelBinding)
                if let p = session.pinnedAt { sqlite3_bind_double(up, 7, p.timeIntervalSince1970) }
                else { sqlite3_bind_null(up, 7) }
                bindText(up, 8, session.folderId)
                sqlite3_bind_text(up, 9, (session.id as NSString).utf8String, -1, nil)
                sqlite3_step(up)
            }
            sqlite3_finalize(up)
            return .updated
        }

        // NOTE: remote_origin_device_id is intentionally omitted from the column
        // list so it defaults to NULL — see the type doc above.
        let sql = """
            INSERT INTO sessions (id, title, category, model_id, created_at, updated_at,
                                  memory_enabled, model_binding, pinned_at, folder_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
        var ins: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &ins, nil) == SQLITE_OK {
            sqlite3_bind_text(ins, 1, (session.id as NSString).utf8String, -1, nil)
            bindText(ins, 2, session.title)
            bindText(ins, 3, session.category)
            sqlite3_bind_text(ins, 4, (session.modelId as NSString).utf8String, -1, nil)
            sqlite3_bind_double(ins, 5, session.createdAt.timeIntervalSince1970)
            sqlite3_bind_double(ins, 6, incoming)
            sqlite3_bind_int(ins, 7, memoryEnabled ? 1 : 0)
            bindText(ins, 8, modelBinding)
            if let p = session.pinnedAt { sqlite3_bind_double(ins, 9, p.timeIntervalSince1970) }
            else { sqlite3_bind_null(ins, 9) }
            bindText(ins, 10, session.folderId)
            sqlite3_step(ins)
        }
        sqlite3_finalize(ins)
        return .inserted
    }

    // MARK: - Messages

    /// Upsert one message, preserving its id and its ORIGINAL `sort_order`.
    ///
    /// The append path (`appendMessages`) can't be used: it re-derives
    /// `sort_order` from the local tail and bumps the session's `updated_at` to
    /// now, which would both reorder a restored transcript and destroy the
    /// timestamps the session LWW depends on.
    func restoreMessage(id: String, sessionId: String, role: String, partsJson: String,
                        createdAt: Date, tokenUsageJson: String?, sortOrder: Int,
                        reasoningContent: String?, streamInterruptCount: Int,
                        updatedAt: Date,
                        // [T-token-attribution-snapshot] Carried through backup
                        // restore so a restored package keeps its attribution.
                        // nil for packages written before the fields existed.
                        modelId: String? = nil, modelDisplayName: String? = nil,
                        providerType: String? = nil, providerInstanceId: String? = nil) -> RestoreOutcome {
        var exists = false
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, "SELECT id FROM sessions WHERE id = ?", -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
            exists = sqlite3_step(stmt) == SQLITE_ROW
        }
        sqlite3_finalize(stmt)
        guard exists else { return .skippedNoParent }

        var localUpdated: Double?
        var chk: OpaquePointer?
        if sqlite3_prepare_v2(db, "SELECT updated_at FROM messages WHERE id = ?", -1, &chk, nil) == SQLITE_OK {
            sqlite3_bind_text(chk, 1, (id as NSString).utf8String, -1, nil)
            if sqlite3_step(chk) == SQLITE_ROW {
                localUpdated = sqlite3_column_double(chk, 0)
            }
        }
        sqlite3_finalize(chk)

        let incoming = updatedAt.timeIntervalSince1970
        if let local = localUpdated, incoming <= local { return .skippedLocalNewer }

        // part_flags is a derived column the list query uses for previews; it is
        // computed from parts_json by the same SQL expression the sync backfill
        // uses, so restored rows behave identically in listSessions.
        let sql = """
            INSERT OR REPLACE INTO messages
              (id, session_id, role, parts_json, created_at, token_usage, sort_order,
               reasoning_content, stream_interrupt_count, updated_at, part_flags,
               model_id, model_display_name, provider_type, provider_instance_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, \(Self.partFlagsSQLExpr("?4")), ?, ?, ?, ?)
            """
        var ins: OpaquePointer?
        var outcome: RestoreOutcome = localUpdated == nil ? .inserted : .updated
        if sqlite3_prepare_v2(db, sql, -1, &ins, nil) == SQLITE_OK {
            sqlite3_bind_text(ins, 1, (id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(ins, 2, (sessionId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(ins, 3, (role as NSString).utf8String, -1, nil)
            sqlite3_bind_text(ins, 4, (partsJson as NSString).utf8String, -1, nil)
            sqlite3_bind_double(ins, 5, createdAt.timeIntervalSince1970)
            bindText(ins, 6, tokenUsageJson)
            sqlite3_bind_int(ins, 7, Int32(sortOrder))
            bindText(ins, 8, reasoningContent)
            sqlite3_bind_int(ins, 9, Int32(streamInterruptCount))
            sqlite3_bind_double(ins, 10, incoming)
            bindText(ins, 11, modelId)
            bindText(ins, 12, modelDisplayName)
            bindText(ins, 13, providerType)
            bindText(ins, 14, providerInstanceId)
            if sqlite3_step(ins) != SQLITE_DONE {
                logger.warning("[Restore] message insert failed id=\(id.prefix(8)): \(String(cString: sqlite3_errmsg(db)))")
                outcome = .skippedNoParent
            }
        }
        sqlite3_finalize(ins)
        return outcome
    }

    // MARK: - Compact markers

    /// Markers are immutable, so presence alone decides — no LWW needed.
    ///
    /// Delegates the write to `insertCompactMarker` rather than re-deriving its
    /// column list: that table has eleven columns including four legacy
    /// migration fields, and a hand-written copy here would silently rot the
    /// next time one is added. Its internal `markDirty` is exactly what §8.3
    /// asks for, so it is left in place.
    func restoreCompactMarker(_ marker: CompactMarker) -> RestoreOutcome {
        var parentExists = false
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, "SELECT id FROM sessions WHERE id = ?", -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (marker.sessionId as NSString).utf8String, -1, nil)
            parentExists = sqlite3_step(stmt) == SQLITE_ROW
        }
        sqlite3_finalize(stmt)
        guard parentExists else { return .skippedNoParent }

        var exists = false
        var chk: OpaquePointer?
        if sqlite3_prepare_v2(db, "SELECT id FROM compact_markers WHERE id = ?", -1, &chk, nil) == SQLITE_OK {
            sqlite3_bind_text(chk, 1, (marker.id as NSString).utf8String, -1, nil)
            exists = sqlite3_step(chk) == SQLITE_ROW
        }
        sqlite3_finalize(chk)
        if exists { return .skippedLocalNewer }

        insertCompactMarker(marker)
        return .inserted
    }

    // MARK: - Tombstones

    /// Clear any local delete tombstone for `sessionId` so a restore can put the
    /// session back.
    ///
    /// Without this the resurrection guards would silently drop exactly the rows
    /// a user is asking to recover — the "I deleted it, then restored an older
    /// backup to get it back" case, which is a primary reason to have backups at
    /// all. Restoring is an explicit user action, so it outranks the guard.
    func clearRestoreTombstone(sessionId: String) {
        // Both tables: the session tombstone blocks the session itself, and the
        // per-record table (keyed by record_type + record_id) blocks its
        // messages / compact markers. Matching on record_id alone clears every
        // type for this id, which is what a full-session restore needs.
        for sql in ["DELETE FROM deleted_session_tombstones WHERE session_id = ?",
                    "DELETE FROM deleted_record_tombstones WHERE record_id = ?"] {
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                sqlite3_bind_text(stmt, 1, (sessionId as NSString).utf8String, -1, nil)
                sqlite3_step(stmt)
            }
            sqlite3_finalize(stmt)
        }
    }

    /// True when a session is mid agent-loop. The importer refuses to write into
    /// one rather than silently skipping it the way the sync path does.
    nonisolated func isSessionRunning(_ sessionId: String) -> Bool {
        SessionActivityTracker.isActiveThreadSafe(sessionId)
    }

    // MARK: - Helpers

    private func bindText(_ stmt: OpaquePointer?, _ idx: Int32, _ value: String?) {
        if let value {
            sqlite3_bind_text(stmt, idx, (value as NSString).utf8String, -1, nil)
        } else {
            sqlite3_bind_null(stmt, idx)
        }
    }

}

// MARK: - JSON Helpers for UI Conversion

private func extractCommandFromJSON(_ json: String) -> String {
    extractStringParam("command", from: json)
}

private func extractStringParam(_ key: String, from json: String) -> String {
    guard let data = json.data(using: .utf8),
          let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let value = dict[key] as? String else {
        return ""
    }
    return value
}
