// Tests for [T-error-persist-current-turn] — GH#263: a failed turn's error was
// written onto the PREVIOUS turn's successful reply.
//
// Root cause (AIChatViewModel+Persistence.swift persistErrorInfo): the target
// row was `agentHistory.last(where: assistant && dbMessageId != nil)`. When the
// current turn died before any output (network error, 4xx, rate limit), the
// last persisted assistant row was the previous turn's good reply, so
// `error_info` landed there and the banner reappeared under it after a reload.
//
// Fix: a WRITE only targets a row in the current turn. With no current-turn row
// it writes a carrier row for this turn — on turn 1 AND on later turns
// ([T-error-persist-turn2-carrier]: the first version skipped on later turns,
// which left the error in memory only and lost it on reload). CLEARS (`nil`) are unchanged: `clearSupersededErrors` relies on
// clearing a superseded error left on an earlier turn's row.
//
// Standalone: `swift ErrorInfoTargetRowTests.swift`.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

enum Part { case text(String), toolUse, toolResult }
struct Msg { enum Role { case user, assistant }; let role: Role; let parts: [Part]; var db: String? = nil }

func user(_ t: String) -> Msg { Msg(role: .user, parts: [.text(t)]) }
func reply(_ id: String) -> Msg { Msg(role: .assistant, parts: [.text("answer")], db: id) }
func toolCall(_ id: String) -> Msg { Msg(role: .assistant, parts: [.toolUse], db: id) }
let toolResult = Msg(role: .user, parts: [.toolResult])

func currentTurnStartIndex(_ h: [Msg]) -> Int {
    guard let i = h.lastIndex(where: { m in
        m.role == .user && m.parts.contains { if case .toolResult = $0 { return false }; return true }
    }) else { return 0 }
    return i + 1
}

enum Target: Equatable { case row(String), skip, carrier }

/// Port of persistErrorInfo's target choice. `fixed: false` = the old rule.
func target(_ h: [Msg], writing: Bool, fixed: Bool) -> Target {
    if fixed && writing {
        let start = currentTurnStartIndex(h)
        let turnRows = h.indices.filter { $0 >= start && h[$0].role == .assistant && h[$0].db != nil }
        if turnRows.isEmpty { return .carrier }
    }
    if let id = h.last(where: { $0.role == .assistant && $0.db != nil })?.db { return .row(id) }
    return writing ? .carrier : .skip
}

print("▶️  1. early failure on a later turn")
do {
    let h = [user("q1"), reply("A1"), user("q2")]
    check("OLD: error stamped onto the previous reply A1 (the bug)", target(h, writing: true, fixed: false) == .row("A1"))
    check("NEW: a carrier row for turn 2 — A1 is left alone and the error survives a reload",
          target(h, writing: true, fixed: true) == .carrier)
}

print("\n▶️  2. rows in this turn are still the target")
do {
    let h = [user("q1"), reply("A1"), user("q2"), toolCall("T2a"), toolResult]
    check("multi-iteration tool loop: this turn's earlier row", target(h, writing: true, fixed: true) == .row("T2a"))
}
do {
    let h = [user("q1"), reply("A1"), user("q2"), toolCall("T2a"), toolResult, toolCall("T2b"), toolResult]
    check("…the latest of several iterations", target(h, writing: true, fixed: true) == .row("T2b"))
}
check("mid-reply failure after this turn's row was persisted",
      target([user("q1"), reply("A1"), user("q2"), reply("A2")], writing: true, fixed: true) == .row("A2"))

print("\n▶️  3. unchanged paths")
check("first turn, no rows anywhere → existing carrier path",
      target([user("q1")], writing: true, fixed: true) == .carrier)
check("clear still reaches the superseded error on the last row",
      target([user("q1"), reply("A1"), user("q2")], writing: false, fixed: true) == .row("A1"))
check("clear with nothing persisted is a no-op", target([user("q1")], writing: false, fixed: true) == .skip)

// MARK: - Sources

let root = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
let persist = (try? String(contentsOf: root.appendingPathComponent("Agent/Chat/AIChatViewModel+Persistence.swift"), encoding: .utf8)) ?? ""
let fn: String = {
    guard let a = persist.range(of: "func persistErrorInfo(_ error: String?) async {"),
          let b = persist.range(of: "\n    }\n", range: a.upperBound..<persist.endIndex) else { return "" }
    return String(persist[a.lowerBound..<b.upperBound])
}()

print("\n▶️  4. sources")
check("persistErrorInfo located", !fn.isEmpty)
check("writes are limited to this turn",
      fn.contains("if error != nil {\n            let turnStart = Self.currentTurnStartIndex(in: agentHistory)"))
check("…the last-row lookup is gated on this turn having a row",
      fn.contains("if thisTurnHasRow,\n           let dbId = agentHistory.last(where:"))
check("the turn check runs before the row lookup",
      (fn.range(of: "if error != nil {")?.lowerBound ?? fn.endIndex)
        < (fn.range(of: "let dbId = agentHistory.last(where:")?.lowerBound ?? fn.startIndex))
check("no early return drops a later turn's error",
      fn.contains("skip — this turn has no persisted row"), false)

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)"); exit(1) }
