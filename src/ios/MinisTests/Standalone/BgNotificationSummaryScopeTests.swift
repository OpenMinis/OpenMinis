// Tests for [T-bgnotif-current-turn] — GH#263: the background-completion
// notification showed a complete answer while the bubble said "Model returned
// an empty response".
//
// Root cause (AIChatViewModel+BackgroundTask.swift, endBackgroundProcessing):
// `responseSummary` searched the WHOLE agentHistory for the last assistant
// turn with text. A turn that failed with no text therefore fell back to the
// PREVIOUS turn's reply, shown under a ❌ title — which on a lock screen reads
// as "the model answered".
//
// Fix: candidates come only from the current turn (after the last message the
// user actually sent; tool results do not count). With no text this turn, a
// failed turn shows its error text; otherwise the existing fallbacks apply.
//
// Standalone: `swift BgNotificationSummaryScopeTests.swift`.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

enum Part { case text(String), image, toolUse, toolResult }
struct Msg { enum Role { case user, assistant }; let role: Role; let parts: [Part] }

func user(_ t: String) -> Msg { Msg(role: .user, parts: [.text(t)]) }
func reply(_ t: String) -> Msg { Msg(role: .assistant, parts: [.text(t)]) }
let emptyReply = Msg(role: .assistant, parts: [])
let toolCall = Msg(role: .assistant, parts: [.toolUse])
let toolResult = Msg(role: .user, parts: [.toolResult])

/// Port of AIChatViewModel.currentTurnStartIndex(in:).
func currentTurnStartIndex(_ h: [Msg]) -> Int {
    guard let i = h.lastIndex(where: { m in
        m.role == .user && m.parts.contains { if case .toolResult = $0 { return false }; return true }
    }) else { return 0 }
    return i + 1
}

let bridge = "(Interrupted mid-task by a new user message. Decide based on the new message.)"
func text(_ m: Msg) -> String {
    m.parts.compactMap { if case .text(let t) = $0 { return t }; return nil }.joined(separator: "\n")
}
func hasToolUse(_ m: Msg) -> Bool { m.parts.contains { if case .toolUse = $0 { return true }; return false } }

/// Port of the summary selection. `scoped: false` = the old whole-history search.
func summary(_ h: [Msg], error: String?, scoped: Bool) -> String {
    let start = scoped ? currentTurnStartIndex(h) : 0
    let all = h[min(start, h.count)...].filter { $0.role == .assistant }
    let turns = all.filter { text($0) != bridge }
    if let pure = turns.last(where: { !hasToolUse($0) }), !text(pure).isEmpty { return String(text(pure).prefix(200)) }
    if let any = turns.last(where: { !text($0).isEmpty }) { return String(text(any).prefix(200)) }
    if scoped, let err = error?.trimmingCharacters(in: .whitespacesAndNewlines), !err.isEmpty {
        return String(err.prefix(200))
    }
    if all.contains(where: { text($0) == bridge }) { return "Task interrupted by a new message. Open the session to continue." }
    return "Task completed."
}

let emptyErr = "Model returned an empty response. Please try again or switch models."

print("▶️  1. the reported case: previous turn answered, this turn empty")
do {
    let h = [user("q1"), reply("Here is the full earlier answer."), user("q2"), emptyReply]
    check("OLD: body was the PREVIOUS reply (the bug)",
          summary(h, error: emptyErr, scoped: false) == "Here is the full earlier answer.")
    check("NEW: body is this turn's error", summary(h, error: emptyErr, scoped: true) == emptyErr)
    check("NEW: without an error it never shows the old reply",
          summary(h, error: nil, scoped: true) == "Task completed.")
}
do {
    // Early failure: no assistant row at all this turn.
    let h = [user("q1"), reply("Earlier answer"), user("q2")]
    check("early failure: error text, not the earlier answer",
          summary(h, error: "Provider error: Rate limited", scoped: true) == "Provider error: Rate limited")
}

print("\n▶️  2. this turn's text is still used")
do {
    let h = [user("q1"), reply("old"), user("q2"), toolCall, toolResult, reply("New final answer")]
    check("final reply of a tool loop", summary(h, error: nil, scoped: true) == "New final answer")
    check("…even if an error is also set, text wins", summary(h, error: "x", scoped: true) == "New final answer")
}
do {
    let h = [user("q1"), reply("old"), user("q2"), Msg(role: .assistant, parts: [.text("Working on it"), .toolUse]), toolResult, emptyReply]
    check("earlier iteration's text in this turn is used", summary(h, error: emptyErr, scoped: true) == "Working on it")
}
do {
    // A tool result that carries a screenshot must not move the boundary back.
    let h = [user("q1"), reply("old"), user("q2"), toolCall,
             Msg(role: .user, parts: [.toolResult, .image]), emptyReply]
    check("tool result with an image never reaches an earlier turn",
          summary(h, error: emptyErr, scoped: true) == emptyErr)
}
do {
    let h = [Msg(role: .user, parts: [.image]), reply("described the photo")]
    check("an image-only user message is a turn boundary", currentTurnStartIndex(h) == 1)
}
do {
    let h = [user("q1"), reply("old"), user("q2"), reply(bridge)]
    check("internal bridge text is never the body; interrupted fallback kept",
          summary(h, error: nil, scoped: true) == "Task interrupted by a new message. Open the session to continue.")
}
check("empty history → Task completed.", summary([], error: nil, scoped: true) == "Task completed.")
check("retry: failed reply removed, new one appended after the same user turn",
      summary([user("q1"), reply("old"), user("q2"), reply("retried answer")], error: nil, scoped: true) == "retried answer")

// MARK: - Sources

let root = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
let bg = (try? String(contentsOf: root.appendingPathComponent("Agent/Chat/AIChatViewModel+BackgroundTask.swift"), encoding: .utf8)) ?? ""

print("\n▶️  3. sources")
check("boundary helper exists and skips tool results",
      bg.contains("static func currentTurnStartIndex(in history: [AgentMessage]) -> Int {")
        && bg.contains("if case .toolResult = part { return false }"))
check("summary candidates are sliced from the turn start",
      bg.contains("let turnStart = Self.currentTurnStartIndex(in: agentHistory)")
        && bg.contains("agentHistory[min(turnStart, agentHistory.count)...].filter { $0.role == .assistant }"))
check("no whole-history candidate list remains",
      !bg.contains("let allAssistantTurns = agentHistory.filter { $0.role == .assistant }"))
check("a failed turn's error becomes the body before the generic fallbacks",
      bg.contains("if hasError, let err = (messages.last?.error ?? errorMessage)?")
        && (bg.range(of: "source=turn-error")?.lowerBound ?? bg.endIndex)
            < (bg.range(of: "source=fallback-interrupted")?.lowerBound ?? bg.startIndex))

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)"); exit(1) }
