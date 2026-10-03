// Tests for [T-ios-listsessions-perf] Phase 3 — the session-list cache is
// invalidated per session instead of wholesale.
//
// Before: one `sessionListCacheDirty: Bool`. ANY write — most often
// touchSession bumping updated_at as an agent streams — threw away the whole
// cached list, so the next refresh re-queried every session and re-derived
// every preview. The profiled trace measured that at 4.4 s median with the
// ChatStore actor held for the duration, and because every ChatSession value
// was freshly minted SwiftUI also re-diffed all ~1772 sidebar rows.
//
// After: `dirtySessionIds: Set<String>` for content changes plus
// `sessionListNeedsFullRebuild: Bool` for row-set/ordering changes. This file
// covers the patch logic and enforces the call-site classification.
//
// Standalone (`swift SessionListIncrementalCacheTests.swift`) like its
// neighbours: deps/libs/libish_emu.a is device-only arm64, so the app cannot
// link for a simulator and an XCTest bundle has nowhere to run.

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Model of the cache (mirrors ChatStore's fields and listSessions gate)

/// Stand-in for ChatSession carrying only what the patch logic touches.
/// `token` identifies the VALUE, so a test can prove an untouched row was
/// reused rather than re-decoded into an equal-but-new instance.
struct Sess: Equatable {
    let id: String
    var updatedAt: Double
    var preview: String
    var token: Int
}

final class StoreModel {
    /// The table.
    var rows: [String: Sess] = [:]
    var cache: [Sess]?
    var dirtyIds: Set<String> = []
    var needsFullRebuild = true

    /// Counts what the real code pays for: rows read out of SQLite and
    /// previews re-derived. A full rebuild costs one per session; the
    /// incremental path costs one per dirty session.
    var rowsQueried = 0
    var fullRebuilds = 0
    var incrementalPatches = 0

    /// Bumped on every re-decode so a fresh value is distinguishable.
    private var nextToken = 1000
    private func decode(_ id: String) -> Sess? {
        guard var r = rows[id] else { return nil }
        rowsQueried += 1
        nextToken += 1
        r.token = nextToken
        return r
    }

    func invalidate(sessionId: String) { dirtyIds.insert(sessionId) }
    func invalidateFull() { needsFullRebuild = true }

    func listSessions() -> [Sess] {
        if !needsFullRebuild, let cached = cache {
            if dirtyIds.isEmpty { return cached }
            incrementalPatches += 1
            var out: [Sess] = []
            out.reserveCapacity(cached.count)
            for row in cached {
                guard dirtyIds.contains(row.id) else { out.append(row); continue }
                if let fresh = decode(row.id) { out.append(fresh) }   // deleted → dropped
            }
            out.sort { $0.updatedAt == $1.updatedAt ? $0.id > $1.id : $0.updatedAt > $1.updatedAt }
            cache = out
            dirtyIds.removeAll()
            return out
        }
        fullRebuilds += 1
        var out = rows.keys.compactMap { decode($0) }
        out.sort { $0.updatedAt == $1.updatedAt ? $0.id > $1.id : $0.updatedAt > $1.updatedAt }
        cache = out
        needsFullRebuild = false
        dirtyIds.removeAll()
        return out
    }
}

func makeStore(_ n: Int) -> StoreModel {
    let s = StoreModel()
    for i in 0..<n {
        let id = String(format: "s%04d", i)
        s.rows[id] = Sess(id: id, updatedAt: Double(i), preview: "preview \(i)", token: 0)
    }
    return s
}

// MARK: - 1. Cold start

print("\n▶️  cold start")
let s1 = makeStore(50)
_ = s1.listSessions()
checkEq("first call is a full rebuild", s1.fullRebuilds, 1)
checkEq("it reads every row", s1.rowsQueried, 50)
_ = s1.listSessions()
checkEq("a second call with no writes queries nothing", s1.rowsQueried, 50)
checkEq("and does not rebuild", s1.fullRebuilds, 1)

// MARK: - 2. One dirty session

print("\n▶️  one session changes (the agent-streaming case)")
let s2 = makeStore(50)
let base = s2.listSessions()
let before = s2.rowsQueried

s2.rows["s0010"]!.preview = "a new answer"
s2.rows["s0010"]!.updatedAt = 999
s2.invalidate(sessionId: "s0010")
let after = s2.listSessions()

checkEq("only the dirty row is re-queried", s2.rowsQueried - before, 1)
checkEq("no full rebuild", s2.fullRebuilds, 1)
checkEq("one incremental patch", s2.incrementalPatches, 1)
checkEq("list keeps its length", after.count, 50)
checkEq("the changed row shows the new preview",
        after.first(where: { $0.id == "s0010" })?.preview, "a new answer")
checkEq("and moved to the top (updated_at DESC)", after.first?.id, "s0010")

// The value-identity guarantee: every untouched row must be the SAME value
// (same token) the cache already held, so SwiftUI's diff sees no change.
var reusedAll = true
for row in after where row.id != "s0010" {
    if let old = base.first(where: { $0.id == row.id }), old.token != row.token { reusedAll = false }
}
check("every untouched row is reused verbatim (identical values)", reusedAll)

// MARK: - 3. Several dirty sessions at once

print("\n▶️  a batch spanning sessions")
let s3 = makeStore(200)
_ = s3.listSessions()
let b3 = s3.rowsQueried
for id in ["s0001", "s0050", "s0199"] {
    s3.rows[id]!.updatedAt = 500 + Double(id.hashValue % 3)
    s3.invalidate(sessionId: id)
}
_ = s3.listSessions()
checkEq("exactly three rows re-queried", s3.rowsQueried - b3, 3)
checkEq("200-session list, 3 rows of work", s3.fullRebuilds, 1)

// MARK: - 4. Deletion under the patch

print("\n▶️  a dirty session that no longer exists")
let s4 = makeStore(10)
_ = s4.listSessions()
s4.rows.removeValue(forKey: "s0005")
s4.invalidate(sessionId: "s0005")
let after4 = s4.listSessions()
checkEq("the vanished row is dropped, not left phantom", after4.count, 9)
check("and it is really gone", !after4.contains { $0.id == "s0005" })

// MARK: - 5. Full rebuild still wins when asked

print("\n▶️  full invalidation")
let s5 = makeStore(30)
_ = s5.listSessions()
let b5 = s5.rowsQueried
s5.invalidate(sessionId: "s0003")
s5.invalidateFull()
_ = s5.listSessions()
checkEq("full flag beats pending dirty ids", s5.rowsQueried - b5, 30)
checkEq("two full rebuilds total", s5.fullRebuilds, 2)
check("and the dirty set is cleared by it", s5.dirtyIds.isEmpty)

// MARK: - 6. Ordering parity with the SQL

print("\n▶️  ordering")
let s6 = makeStore(5)
_ = s6.listSessions()
// Give two rows the same updated_at to exercise the tie-break.
s6.rows["s0001"]!.updatedAt = 42
s6.rows["s0002"]!.updatedAt = 42
s6.invalidate(sessionId: "s0001")
s6.invalidate(sessionId: "s0002")
let patched = s6.listSessions()
s6.invalidateFull()
let rebuilt = s6.listSessions()
checkEq("patched order == full-rebuild order", patched.map(\.id), rebuilt.map(\.id))
check("ordering is updated_at DESC",
      zip(patched, patched.dropFirst()).allSatisfy { $0.updatedAt >= $1.updatedAt })

// MARK: - 7. Work saved over a streaming run

print("\n▶️  cost over a 60-turn agent run in a 1772-session sidebar")
let big = makeStore(1772)
_ = big.listSessions()
let coldCost = big.rowsQueried
for turn in 0..<60 {
    big.rows["s0000"]!.updatedAt = 10_000 + Double(turn)
    big.invalidate(sessionId: "s0000")
    _ = big.listSessions()
}
let incrementalCost = big.rowsQueried - coldCost
let legacyCost = 60 * 1772
print("  📊 rows re-read: legacy \(legacyCost) vs incremental \(incrementalCost)")
checkEq("one row per turn", incrementalCost, 60)
check("that is >1000× less work", Double(legacyCost) / Double(incrementalCost) > 1000)

// MARK: - 8. Call-site classification is enforced in the real source

print("\n▶️  every ChatStore call site is classified")
let storePath = "../../Agent/Chat/ChatStore.swift"
guard let src = try? String(contentsOfFile: storePath, encoding: .utf8) else {
    print("  ❌ could not read \(storePath)")
    failures += 1
    exit(1)
}
let srcLines = src.components(separatedBy: "\n")

var perSession = 0, full = 0, undocumented: [Int] = []
for (i, line) in srcLines.enumerated() {
    guard line.contains("invalidateSessionListCache("),
          !line.contains("func invalidateSessionListCache") else { continue }
    // The four lines above a call must say which rule applies.
    let ctx = srcLines[max(0, i - 4)..<i].joined(separator: "\n")
    let documented = ctx.contains("FULL:") || ctx.contains("PER-SESSION:")
    if !documented { undocumented.append(i + 1) }
    if line.contains("sessionId:") { perSession += 1 } else { full += 1 }
}

if undocumented.isEmpty {
    print("  ✅ all \(perSession + full) call sites carry a FULL:/PER-SESSION: rationale")
} else {
    print("  ❌ undocumented call sites at lines: \(undocumented)")
    failures += 1
}
check("some sites use the targeted form", perSession > 0)
check("some sites still need a full rebuild", full > 0)
print("  📊 \(perSession) per-session, \(full) full-rebuild")

// The hot path specifically MUST be targeted — it is the one the whole phase
// exists for. A future edit that reverts it to a full invalidation would
// silently restore the storm, so pin it by name.
for fn in ["touchSession", "appendMessages"] {
    guard let at = srcLines.firstIndex(where: { $0.contains("func \(fn)(") }) else {
        check("found \(fn)", false); continue
    }
    let body = srcLines[at..<min(at + 14, srcLines.count)].joined(separator: "\n")
    check("\(fn) uses the targeted invalidation",
          body.contains("invalidateSessionListCache(sessionId:"))
}

// Row-set changes must NOT be targeted — a per-session patch cannot add or
// remove a row, so using it there would drop a new session from the sidebar.
for fn in ["createSession", "deleteSession", "mergeRemoteSession", "restoreSession"] {
    guard let at = srcLines.firstIndex(where: { $0.contains("func \(fn)(") }) else {
        check("found \(fn)", false); continue
    }
    let body = srcLines[at..<min(at + 16, srcLines.count)].joined(separator: "\n")
    guard body.contains("invalidateSessionListCache") else {
        check("\(fn) invalidates at all", false); continue
    }
    check("\(fn) keeps the FULL rebuild",
          !body.contains("invalidateSessionListCache(sessionId:"))
}

// MARK: - Summary

print("\n" + String(repeating: "─", count: 60))
if failures == 0 {
    print("✅ All incremental session-list cache tests passed")
    exit(0)
} else {
    print("❌ \(failures) failure(s)")
    exit(1)
}
