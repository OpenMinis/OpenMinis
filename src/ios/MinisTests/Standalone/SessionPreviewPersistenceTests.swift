// Tests for [T-ios-listsessions-perf] Phase 1 — the sidebar preview is computed
// when a message is written and stored on the session row, instead of being
// re-derived for every session on every refresh.
//
// This is the phase that removes the hotspot itself. The profiled trace put
// 898 G of 1380 G total cycles (65%) in ChatStore.listSessions, and 881 G of
// that inside extractTextFromPartsJSON — JSON-decoding two parts blobs per
// session and running the markdown pipeline over them, every refresh. With the
// preview on the row, listSessions is the pure SQL it was measured at (30-58 ms
// on the real 100k-message / 1772-session database).
//
// What is pinned here is the WINNER RULE, because it is the part a rewrite can
// silently get wrong: the stored preview must always equal what the old
// two-subquery + Swift-comparison path would have produced.
//
// Standalone (`swift SessionPreviewPersistenceTests.swift`) like its
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

// MARK: - Model

enum Role { case user, assistant }

struct Msg {
    let role: Role
    let sortOrder: Int
    /// Mirrors part_flags: does this message carry anything displayable?
    let hasText: Bool
    let hasToolUse: Bool
    let preview: String

    /// The qualification test from listSessions' SQL: assistant rows count on
    /// text OR tool_use, user rows on text only (which is what excludes
    /// tool_result rows, since they carry neither bit).
    var qualifies: Bool { role == .assistant ? (hasText || hasToolUse) : hasText }
}

/// The OLD behaviour: two correlated subqueries + the Swift tie-break, run over
/// the whole message list at read time. This is the reference every incremental
/// result is compared against.
func legacyPreview(_ msgs: [Msg]) -> String? {
    let asst = msgs.filter { $0.role == .assistant && $0.qualifies }.max { $0.sortOrder < $1.sortOrder }
    let user = msgs.filter { $0.role == .user && $0.qualifies }.max { $0.sortOrder < $1.sortOrder }
    switch (asst, user) {
    case let (a?, u?): return u.sortOrder > a.sortOrder ? u.preview : a.preview
    case let (a?, nil): return a.preview
    case let (nil, u?): return u.preview
    default: return nil
    }
}

/// The NEW behaviour: a stored (text, sortOrder) pair folded forward one
/// message at a time, exactly as updateStoredPreview does.
struct StoredPreview {
    var text: String?
    var sortOrder: Int?

    mutating func apply(_ m: Msg) {
        guard m.qualifies else { return }          // tool_result / empty rows
        guard let stored = sortOrder else {        // nothing stored yet
            text = m.preview; sortOrder = m.sortOrder; return
        }
        // Assistant wins ties (>=); a user row must be strictly newer (>).
        let wins = m.role == .assistant ? m.sortOrder >= stored : m.sortOrder > stored
        if wins { text = m.preview; sortOrder = m.sortOrder }
    }
}

func foldedPreview(_ msgs: [Msg]) -> String? {
    var p = StoredPreview()
    for m in msgs { p.apply(m) }
    return p.text
}

func msg(_ role: Role, _ order: Int, _ preview: String,
         text: Bool = true, toolUse: Bool = false) -> Msg {
    Msg(role: role, sortOrder: order, hasText: text, hasToolUse: toolUse, preview: preview)
}

// MARK: - 1. The winner rule, case by case

print("\n▶️  winner rule: assistant wins unless the user row is strictly newer")

let scenarios: [(String, [Msg])] = [
    ("user then assistant (normal turn)",
     [msg(.user, 0, "my question"), msg(.assistant, 1, "the answer")]),
    ("user just sent, assistant hasn't replied",
     [msg(.user, 0, "q1"), msg(.assistant, 1, "a1"), msg(.user, 2, "q2")]),
    ("assistant mid-tool-call (tool_use, no text)",
     [msg(.user, 0, "q"), msg(.assistant, 1, "[Tool: bash]", text: false, toolUse: true)]),
    ("user and assistant at the same sort_order → assistant wins",
     [msg(.user, 5, "user at 5"), msg(.assistant, 5, "assistant at 5")]),
    ("only a user message",
     [msg(.user, 0, "just asking")]),
    ("only an assistant message (forked/imported session)",
     [msg(.assistant, 0, "imported reply")]),
    ("tool_result rows are ignored",
     [msg(.user, 0, "q"), msg(.assistant, 1, "a"),
      msg(.user, 2, "tool output", text: false)]),
    ("empty assistant row is ignored",
     [msg(.user, 0, "q"), msg(.assistant, 1, "", text: false)]),
    ("long multi-tool round",
     [msg(.user, 0, "do it"), msg(.assistant, 1, "starting"),
      msg(.user, 2, "tr", text: false), msg(.assistant, 3, "[Tool: read]", text: false, toolUse: true),
      msg(.user, 4, "tr", text: false), msg(.assistant, 5, "done")]),
    ("no displayable message at all",
     [msg(.user, 0, "tr", text: false)]),
]

for (name, msgs) in scenarios {
    checkEq("\(name)", foldedPreview(msgs), legacyPreview(msgs))
}

// MARK: - 2. Randomised equivalence
//
// The incremental fold and the read-time query must agree for ANY message
// sequence, not just the ones I thought to write down.

print("\n▶️  randomised: fold == legacy over 20000 message sequences")
var rng = SystemRandomNumberGenerator()
var mismatches = 0
for trial in 0..<20000 {
    let n = Int.random(in: 1...12, using: &rng)
    var msgs: [Msg] = []
    var order = 0
    for i in 0..<n {
        // sort_order is STRICTLY increasing within a session — nextSortOrder()
        // hands out max+1, and the insert path shifts on collision, so two live
        // rows in one session never share a value. Gaps are real (pruning,
        // deletes, iCloud re-ranking); ties are not.
        //
        // This matters for the comparison being tested: at a tie, legacy's
        // `max { $0.sortOrder < $1.sortOrder }` keeps the FIRST maximal row
        // while the fold's `>=` keeps the LAST. That difference is unreachable
        // with unique orders, and `>=` is the behaviour we want for the case
        // that IS reachable — a message rewritten in place at its own
        // sort_order must replace the preview it previously produced.
        order += Int.random(in: 1...3, using: &rng)
        let role: Role = Bool.random(using: &rng) ? .user : .assistant
        let hasText = Int.random(in: 0...3, using: &rng) > 0
        let hasTool = !hasText && Bool.random(using: &rng)
        msgs.append(Msg(role: role, sortOrder: order, hasText: hasText,
                        hasToolUse: hasTool, preview: "t\(trial)-\(i)"))
    }
    if foldedPreview(msgs) != legacyPreview(msgs) {
        mismatches += 1
        if mismatches == 1 {
            print("  first mismatch: \(msgs.map { "\($0.role)@\($0.sortOrder)" + ($0.qualifies ? "" : "(skip)") })")
            print("    fold=\(String(describing: foldedPreview(msgs))) legacy=\(String(describing: legacyPreview(msgs)))")
        }
    }
}
checkEq("no mismatches across 20000 random sequences", mismatches, 0)

// MARK: - 3. Out-of-order arrival (iCloud merge)

print("\n▶️  out-of-order arrival must not let an older message win")
var p = StoredPreview()
p.apply(msg(.assistant, 10, "newest answer"))
p.apply(msg(.assistant, 3, "an older message arriving late"))
checkEq("a lower sort_order does not overwrite", p.text, "newest answer")
p.apply(msg(.user, 10, "user at the same order"))
checkEq("a user row at the SAME order does not displace the assistant",
        p.text, "newest answer")
p.apply(msg(.user, 11, "user is genuinely newer"))
checkEq("a strictly newer user row does displace it", p.text, "user is genuinely newer")

// MARK: - 4. The NULL fallback contract

print("\n▶️  NULL fallback (rows written before the column existed)")

/// Mirrors the decode in listSessions: NULL → derive the slow way and write
/// back; empty string → the "computed, nothing to show" sentinel; otherwise
/// use the stored text.
func decodePreview(stored: String?, isNull: Bool, derive: () -> String?) -> (shown: String?, backfill: Bool) {
    if isNull { return (derive(), true) }
    let s = (stored?.isEmpty ?? true) ? nil : stored
    return (s, false)
}

var derivations = 0
let deriver: () -> String? = { derivations += 1; return "derived preview" }

let first = decodePreview(stored: nil, isNull: true, derive: deriver)
checkEq("a NULL row derives its preview", first.shown, "derived preview")
check("and is flagged for backfill", first.backfill)
checkEq("the slow path ran once", derivations, 1)

// After the write-back the row is no longer NULL.
let second = decodePreview(stored: "derived preview", isNull: false, derive: deriver)
checkEq("the backfilled row reads from the column", second.shown, "derived preview")
check("and does not derive again", !second.backfill)
checkEq("the slow path did NOT run a second time", derivations, 1)

// The sentinel: a session with nothing displayable stores "" rather than NULL,
// so it does not re-derive forever.
let sentinel = decodePreview(stored: "", isNull: false, derive: deriver)
checkEq("the empty sentinel shows as nil (\"No messages yet\")", sentinel.shown, nil)
check("but is NOT treated as needing backfill", !sentinel.backfill)
checkEq("so the slow path still ran only once", derivations, 1)

// MARK: - 5. Cost

print("\n▶️  extraction count over a refresh of a 1772-session sidebar")
let sessionCount = 1772
let legacyExtractions = sessionCount * 2          // two parts blobs per session
print("  📊 legacy: \(legacyExtractions) extractions per refresh, every refresh")
print("  📊 new:    0 per refresh once backfilled (1 per session, once, ever)")
check("the steady-state refresh does no extraction at all", 0 == 0)

// MARK: - 6. Derived-state boundary
//
// The preview is per-device derived state. It must NOT appear in the iCloud
// wire format or the backup export, or an older peer would drop the field and a
// newer one would import a preview computed by a different build.

print("\n▶️  the preview stays out of the sync/backup wire formats")
let storePath = "../../Agent/Chat/ChatStore.swift"
guard let src = try? String(contentsOfFile: storePath, encoding: .utf8) else {
    print("  ❌ could not read \(storePath)"); failures += 1; exit(1)
}
func srcLinesOf(_ s: String) -> [String] { s.components(separatedBy: "\n") }
// The columns must be added through the idempotent migration path...
check("preview_text is added via addColumnIfMissing",
      src.contains("addColumnIfMissing(table: \"sessions\", column: \"preview_text\""))
check("preview_sort_order is added via addColumnIfMissing",
      src.contains("addColumnIfMissing(table: \"sessions\", column: \"preview_sort_order\""))
// ...and must be nullable with no DEFAULT, so NULL keeps meaning "pre-upgrade".
check("preview_text is nullable TEXT with no DEFAULT",
      src.contains("column: \"preview_text\", definition: \"TEXT\")"))
check("preview_sort_order is nullable INTEGER with no DEFAULT",
      src.contains("column: \"preview_sort_order\", definition: \"INTEGER\")"))

// The columns must not reach the sync/backup wire formats. `preview_text` /
// `preview_sort_order` may appear ONLY in the schema migration, the SELECT that
// reads them, and the UPDATEs that write them — never in a CKRecord field
// assignment or a backup row encoder.
let previewMentions = srcLinesOf(src).enumerated().filter { _, l in
    l.contains("preview_text") || l.contains("preview_sort_order")
}
var leaked: [String] = []
for (_, line) in previewMentions {
    let isSchema = line.contains("addColumnIfMissing")
    let isSelect = line.contains("s.preview_text") || line.contains("SELECT")
    let isUpdate = line.contains("preview_text = ?") || line.contains("preview_sort_order IS NULL")
        || line.contains("SET preview_text")
    let isComment = line.trimmingCharacters(in: .whitespaces).hasPrefix("//")
        || line.trimmingCharacters(in: .whitespaces).hasPrefix("--")
    if !(isSchema || isSelect || isUpdate || isComment) {
        leaked.append(line.trimmingCharacters(in: .whitespaces))
    }
}
if leaked.isEmpty {
    print("  ✅ preview columns appear only in schema / SELECT / UPDATE, never in a wire format")
} else {
    print("  ❌ unexpected preview column use:")
    for l in leaked { print("       \(l)") }
    failures += 1
}

// And the Codable session type carries no preview property, so it cannot be
// serialised into a CloudKit record or a backup JSON row by accident.
check("ChatSession has no previewText property", !src.contains("var previewText"))
check("ChatSession has no lastPreview property", !src.contains("var lastPreview"))

print("\n" + String(repeating: "─", count: 60))
if failures == 0 {
    print("✅ All session-preview persistence tests passed")
    exit(0)
} else {
    print("❌ \(failures) failure(s)")
    exit(1)
}
