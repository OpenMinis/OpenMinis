// [T-token-attribution-snapshot] The sync clobber regression.
//
// Standalone (`swift UsageAttributionClobberTests.swift`) because the
// MinisTests target has a pre-existing compile break — same rationale and
// directory as BackupZipContainmentTests / RestoreSymlinkContainmentTests.
//
// ## The bug this locks down
//
// Two devices, one newer than the other. The newer one records which model
// actually produced each message in four new columns. The older one knows
// nothing about them, so when it re-sends that same message — an ordinary echo,
// or an edit — its payload has no such fields and the hydrator passes nil.
//
// If the receiving UPDATE binds those nils directly, `model_id = ?` writes NULL
// and the newer device's correct attribution is destroyed. Conflict resolution
// is last-write-wins on updated_at, so the older device can legitimately win.
// The symptom would be attribution vanishing at random on multi-device setups —
// essentially undiagnosable from a bug report.
//
// The fix is `COALESCE(?, model_id)`: absent means "no opinion", so the local
// value stands. That is only correct for SNAPSHOT columns — parts_json must
// still be overwritten, because for content an incoming value really is newer
// truth. Both halves are asserted below.
//
// Both the broken and the fixed UPDATE are reproduced verbatim so this proves
// the fix CHANGES behaviour; a test that only exercises the new statement
// cannot show the bug was real.

import Foundation
import SQLite3

let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

// MARK: - The two statements, before and after

/// Pre-fix: a plain assignment. Binding NULL erases the local snapshot.
let brokenSQL = "UPDATE messages SET parts_json = ?, model_id = ? WHERE id = ?"
/// Post-fix: absent means "no opinion", so the local value survives.
let fixedSQL = "UPDATE messages SET parts_json = ?, model_id = COALESCE(?, model_id) WHERE id = ?"

// MARK: - Harness

var failures = 0
var checks = 0

func check(_ condition: Bool, _ label: String) {
    checks += 1
    if condition {
        print("  ✓ \(label)")
    } else {
        failures += 1
        print("  ✗ \(label)")
    }
}

func equal(_ actual: String?, _ expected: String?, _ label: String) {
    checks += 1
    if actual == expected {
        print("  ✓ \(label)")
    } else {
        failures += 1
        print("  ✗ \(label) — expected \(expected.map { "\"\($0)\"" } ?? "nil"), got \(actual.map { "\"\($0)\"" } ?? "nil")")
    }
}

func makeDB() -> OpaquePointer {
    var db: OpaquePointer?
    guard sqlite3_open(":memory:", &db) == SQLITE_OK, let db else {
        fatalError("could not open in-memory sqlite")
    }
    sqlite3_exec(db, """
        CREATE TABLE messages (
            id TEXT PRIMARY KEY,
            parts_json TEXT,
            model_id TEXT,
            model_display_name TEXT,
            provider_type TEXT,
            provider_instance_id TEXT
        )
    """, nil, nil, nil)
    // A message this device recorded WITH full attribution.
    sqlite3_exec(db, """
        INSERT INTO messages VALUES
        ('m1', '[{"text":"local"}]', 'deepseek-v4-pro', 'DeepSeek V4 Pro', 'openAI', 'inst-1')
    """, nil, nil, nil)
    return db
}

func column(_ db: OpaquePointer, _ col: String) -> String? {
    var stmt: OpaquePointer?
    defer { sqlite3_finalize(stmt) }
    guard sqlite3_prepare_v2(db, "SELECT \(col) FROM messages WHERE id = 'm1'", -1, &stmt, nil) == SQLITE_OK,
          sqlite3_step(stmt) == SQLITE_ROW else { return nil }
    return sqlite3_column_text(stmt, 0).map { String(cString: $0) }
}

/// Simulate an inbound merge. `modelId == nil` models a sender on an older
/// build, which knows nothing of these fields.
func merge(_ db: OpaquePointer, sql: String, partsJson: String, modelId: String?) {
    var stmt: OpaquePointer?
    defer { sqlite3_finalize(stmt) }
    guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
        print("  ✗ prepare failed: \(String(cString: sqlite3_errmsg(db)))")
        failures += 1
        return
    }
    sqlite3_bind_text(stmt, 1, partsJson, -1, SQLITE_TRANSIENT)
    if let modelId {
        sqlite3_bind_text(stmt, 2, modelId, -1, SQLITE_TRANSIENT)
    } else {
        sqlite3_bind_null(stmt, 2)
    }
    sqlite3_bind_text(stmt, 3, "m1", -1, SQLITE_TRANSIENT)
    if sqlite3_step(stmt) != SQLITE_DONE {
        print("  ✗ step failed: \(String(cString: sqlite3_errmsg(db)))")
        failures += 1
    }
}

// MARK: - Cases

print("UsageAttributionClobberTests")

print("\n1. the bug is real — a plain assignment wipes the snapshot")
do {
    let db = makeDB(); defer { sqlite3_close(db) }
    merge(db, sql: brokenSQL, partsJson: "[{\"text\":\"from old device\"}]", modelId: nil)
    check(column(db, "model_id") == nil,
          "unfixed UPDATE destroys attribution when the sender omits it")
}

print("\n2. the fix — an older device's echo leaves attribution intact")
do {
    let db = makeDB(); defer { sqlite3_close(db) }
    merge(db, sql: fixedSQL, partsJson: "[{\"text\":\"from old device\"}]", modelId: nil)
    equal(column(db, "model_id"), "deepseek-v4-pro",
          "an older sender says nothing about the model; it must not erase ours")
}

print("\n3. COALESCE must not freeze the value — a newer sender still wins")
do {
    let db = makeDB(); defer { sqlite3_close(db) }
    merge(db, sql: fixedSQL, partsJson: "[{\"text\":\"from new device\"}]", modelId: "grok-4.5")
    equal(column(db, "model_id"), "grok-4.5",
          "a sender that DOES know the model must be able to update it")
}

print("\n4. content is not a snapshot — it is still overwritten")
do {
    let db = makeDB(); defer { sqlite3_close(db) }
    merge(db, sql: fixedSQL, partsJson: "[{\"text\":\"edited elsewhere\"}]", modelId: nil)
    equal(column(db, "parts_json"), "[{\"text\":\"edited elsewhere\"}]",
          "COALESCE protection applies to snapshot columns only")
}

print("\n5. repeated echoes from an older device do not degrade")
do {
    let db = makeDB(); defer { sqlite3_close(db) }
    for i in 0..<5 {
        merge(db, sql: fixedSQL, partsJson: "[{\"text\":\"echo \(i)\"}]", modelId: nil)
    }
    equal(column(db, "model_id"), "deepseek-v4-pro",
          "attribution stays stable across repeated inbound merges")
}

print("\n\(checks - failures)/\(checks) checks passed")
if failures > 0 {
    print("FAILED")
    exit(1)
}
print("OK")
