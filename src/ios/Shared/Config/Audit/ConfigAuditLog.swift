import Foundation
import SQLite3

private let auditLogger = AppLogger(category: "ConfigAudit")

/// SQLite-backed rolling log of config changes.
///
/// Storage: a dedicated `minis-config-audit.db` next to the chat db.
/// Keeping it separate from `minis.db` means the audit table never
/// gets caught in sync-dirty queries and can be wiped independently
/// if it ever corrupts. Capacity: most-recent **1000** rows; older
/// rows are pruned in the same transaction as each insert.
@MainActor
final class ConfigAuditLog: ObservableObject {
    static let shared = ConfigAuditLog()

    /// Bumped on every insert / status update / clear so SwiftUI views
    /// observing the log refresh without needing per-entry @Published.
    @Published private(set) var revision: Int = 0

    private static let maxRows = 1000

    private var db: OpaquePointer?
    private let dbURL: URL

    init() {
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let base = library.appendingPathComponent("MinisChat", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        self.dbURL = base.appendingPathComponent("minis-config-audit.db")
        openAndMigrate()
        // [T-config-audit-wal-loss] See checkpoint(): a disappeared log and a
        // never-used one look identical without this line.
        logRowCountForDiagnostics()
    }

    // MARK: Schema

    private func openAndMigrate() {
        if sqlite3_open(dbURL.path, &db) != SQLITE_OK {
            auditLogger.error("open failed: \(dbURL.path)")
            return
        }
        exec("PRAGMA journal_mode=WAL")
        exec("""
            CREATE TABLE IF NOT EXISTS config_audit (
                id            TEXT PRIMARY KEY,
                at            REAL NOT NULL,
                actor         TEXT NOT NULL,
                session_id    TEXT,
                scope         TEXT NOT NULL,
                key           TEXT NOT NULL,
                old_value     TEXT NOT NULL,
                new_value     TEXT NOT NULL,
                confirmed_at  REAL,
                status        TEXT NOT NULL,
                revert_of     TEXT,
                caption       TEXT
            )
        """)
        // Idempotent migration for databases created before `caption`
        // existed. SQLite has no `ADD COLUMN IF NOT EXISTS`; the second
        // run errors with "duplicate column name", which `exec` swallows
        // by logging — that's the desired no-op.
        exec("ALTER TABLE config_audit ADD COLUMN caption TEXT")
        exec("CREATE INDEX IF NOT EXISTS idx_audit_at ON config_audit(at DESC)")
        exec("CREATE INDEX IF NOT EXISTS idx_audit_scope ON config_audit(scope, at DESC)")
        exec("CREATE INDEX IF NOT EXISTS idx_audit_revert_of ON config_audit(revert_of)")
    }

    private func exec(_ sql: String) {
        var err: UnsafeMutablePointer<CChar>?
        if sqlite3_exec(db, sql, nil, nil, &err) != SQLITE_OK {
            let msg = err.map { String(cString: $0) } ?? "?"
            auditLogger.error("exec failed: \(msg) | \(sql)")
            sqlite3_free(err)
        }
    }

    // MARK: Writes

    /// Insert a new audit entry. Caller is responsible for filling
    /// `confirmedAt` (if applicable) and the right `status`. Trim runs
    /// in the same transaction so the table never exceeds `maxRows`.
    func append(_ entry: ConfigAuditEntry) {
        guard db != nil else { return }
        exec("BEGIN")
        let sql = """
            INSERT OR REPLACE INTO config_audit
              (id, at, actor, session_id, scope, key, old_value, new_value,
               confirmed_at, status, revert_of, caption)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (entry.id as NSString).utf8String, -1, nil)
            sqlite3_bind_double(stmt, 2, entry.at.timeIntervalSince1970)
            sqlite3_bind_text(stmt, 3, (entry.actor.rawValue as NSString).utf8String, -1, nil)
            if let sid = entry.sessionId {
                sqlite3_bind_text(stmt, 4, (sid as NSString).utf8String, -1, nil)
            } else {
                sqlite3_bind_null(stmt, 4)
            }
            sqlite3_bind_text(stmt, 5, (entry.scope as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 6, (entry.key as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 7, (entry.oldValueJSON as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 8, (entry.newValueJSON as NSString).utf8String, -1, nil)
            if let confirmed = entry.confirmedAt {
                sqlite3_bind_double(stmt, 9, confirmed.timeIntervalSince1970)
            } else {
                sqlite3_bind_null(stmt, 9)
            }
            sqlite3_bind_text(stmt, 10, (entry.status.rawValue as NSString).utf8String, -1, nil)
            if let r = entry.revertOf {
                sqlite3_bind_text(stmt, 11, (r as NSString).utf8String, -1, nil)
            } else {
                sqlite3_bind_null(stmt, 11)
            }
            if let cap = entry.caption, !cap.isEmpty {
                sqlite3_bind_text(stmt, 12, (cap as NSString).utf8String, -1, nil)
            } else {
                sqlite3_bind_null(stmt, 12)
            }
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)

        // Rolling cap: keep 1000 most-recent rows. The subquery picks
        // the cutoff timestamp at row#1000; everything older gets
        // dropped. Fast even at 1000 with the at-DESC index.
        exec("""
            DELETE FROM config_audit
            WHERE id NOT IN (
                SELECT id FROM config_audit ORDER BY at DESC LIMIT \(Self.maxRows)
            )
        """)
        exec("COMMIT")
        revision &+= 1
    }

    /// Mark an existing entry as `reverted` (set during a successful
    /// revert flow). Does not delete the row — keeps the history visible.
    func markReverted(id: String) {
        guard db != nil else { return }
        let sql = "UPDATE config_audit SET status = ? WHERE id = ?"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (ConfigAuditStatus.reverted.rawValue as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (id as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
        revision &+= 1
    }

    /// Clear ALL entries. Surfaced via Logs UI as a manual action; not
    /// callable through `minis-config` (would let the agent erase its
    /// own trail).
    func clearAll() {
        guard db != nil else { return }
        exec("DELETE FROM config_audit")
        revision &+= 1
    }

    /// [T-config-audit-wal-loss] Fold the WAL back into the main DB file.
    ///
    /// This database is deliberately separate from `minis.db` so the audit table
    /// never lands in a sync-dirty query — but that also left it out of
    /// `ICloudBackupManager.walCheckpoint`, which covers minis.db and skills.db.
    /// It runs in WAL mode with nothing ever checkpointing it, so a jetsam or
    /// crash can drop history that was only in the `-wal` sidecar.
    ///
    /// This is the defensive half of OpenMinis#98 defect 3. The report's "audit
    /// log was erased" could NOT be reproduced from code — no path in this tree
    /// deletes the DB or bulk-deletes rows outside the 1000-row cap — so the two
    /// surviving explanations are an uncheckpointed WAL (fixed here) or a
    /// freshly-created DB after a container change (which this cannot address).
    /// Called on background/terminate.
    func checkpoint() {
        guard let db else { return }
        var errMsg: UnsafeMutablePointer<CChar>?
        if sqlite3_exec(db, "PRAGMA wal_checkpoint(TRUNCATE)", nil, nil, &errMsg) != SQLITE_OK {
            let msg = errMsg.map { String(cString: $0) } ?? "?"
            sqlite3_free(errMsg)
            auditLogger.warning("[Audit] wal_checkpoint failed: \(msg)")
            return
        }
        auditLogger.info("[Audit] wal_checkpoint(TRUNCATE) ok")
    }

    /// [T-config-audit-wal-loss] Row count at open, so a log that vanishes
    /// between launches is visible in the logs instead of being silently
    /// indistinguishable from "never used".
    func logRowCountForDiagnostics() {
        guard let db else { return }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM config_audit", -1, &stmt, nil) == SQLITE_OK,
              sqlite3_step(stmt) == SQLITE_ROW else { return }
        auditLogger.info("[Audit] opened with \(sqlite3_column_int(stmt, 0)) rows at \(dbURL.lastPathComponent)")
    }

    // MARK: Reads

    /// Most-recent first. `limit` capped at `maxRows`.
    func recent(limit: Int = 200, scope: String? = nil) -> [ConfigAuditEntry] {
        guard db != nil else { return [] }
        let cap = max(1, min(limit, Self.maxRows))
        var sql = "SELECT id, at, actor, session_id, scope, key, old_value, new_value, confirmed_at, status, revert_of, caption FROM config_audit"
        if scope != nil { sql += " WHERE scope = ?" }
        sql += " ORDER BY at DESC LIMIT ?"
        var stmt: OpaquePointer?
        var out: [ConfigAuditEntry] = []
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            var bindIdx: Int32 = 1
            if let s = scope {
                sqlite3_bind_text(stmt, bindIdx, (s as NSString).utf8String, -1, nil)
                bindIdx += 1
            }
            sqlite3_bind_int(stmt, bindIdx, Int32(cap))
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let entry = decode(stmt: stmt) { out.append(entry) }
            }
        }
        sqlite3_finalize(stmt)
        return out
    }

    func get(id: String) -> ConfigAuditEntry? {
        guard db != nil else { return nil }
        let sql = "SELECT id, at, actor, session_id, scope, key, old_value, new_value, confirmed_at, status, revert_of, caption FROM config_audit WHERE id = ?"
        var stmt: OpaquePointer?
        var entry: ConfigAuditEntry?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW { entry = decode(stmt: stmt) }
        }
        sqlite3_finalize(stmt)
        return entry
    }

    /// Total row count + capacity. UI shows "X / 1000 used".
    func usage() -> (count: Int, capacity: Int) {
        guard db != nil else { return (0, Self.maxRows) }
        var stmt: OpaquePointer?
        var count = 0
        if sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM config_audit", -1, &stmt, nil) == SQLITE_OK,
           sqlite3_step(stmt) == SQLITE_ROW {
            count = Int(sqlite3_column_int(stmt, 0))
        }
        sqlite3_finalize(stmt)
        return (count, Self.maxRows)
    }

    private func decode(stmt: OpaquePointer?) -> ConfigAuditEntry? {
        guard let stmt else { return nil }
        let id = String(cString: sqlite3_column_text(stmt, 0))
        let at = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 1))
        let actorRaw = String(cString: sqlite3_column_text(stmt, 2))
        guard let actor = ConfigAuditActor(rawValue: actorRaw) else { return nil }
        let sessionId = sqlite3_column_text(stmt, 3).map { String(cString: $0) }
        let scope = String(cString: sqlite3_column_text(stmt, 4))
        let key = String(cString: sqlite3_column_text(stmt, 5))
        let oldVal = String(cString: sqlite3_column_text(stmt, 6))
        let newVal = String(cString: sqlite3_column_text(stmt, 7))
        let confirmedAt: Date? = sqlite3_column_type(stmt, 8) != SQLITE_NULL
            ? Date(timeIntervalSince1970: sqlite3_column_double(stmt, 8))
            : nil
        let statusRaw = String(cString: sqlite3_column_text(stmt, 9))
        guard let status = ConfigAuditStatus(rawValue: statusRaw) else { return nil }
        let revertOf = sqlite3_column_text(stmt, 10).map { String(cString: $0) }
        let caption = sqlite3_column_text(stmt, 11).map { String(cString: $0) }
        return ConfigAuditEntry(
            id: id, at: at, actor: actor, sessionId: sessionId,
            scope: scope, key: key,
            oldValueJSON: oldVal, newValueJSON: newVal,
            confirmedAt: confirmedAt, status: status,
            revertOf: revertOf,
            caption: caption
        )
    }
}
