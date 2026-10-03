// Tests for [T-longctx-persona-reminder] / [T-longctx-persona-reminder-cachebreak]
// — backlog item T16, iOS half.
//
// Pins fd234b1d1 (the reminder) and a746f3259 (append, don't rewrite).
// Issues #216, #357 (and #37, the original report).
//
// Invariants:
//   * nothing below 100k API-reported context tokens;
//   * at 100k exactly one reminder, APPENDED as its own message after the
//     last tool_result — never concatenated onto tool output;
//   * the request before and the request after share an identical byte
//     prefix (prompt cache keeps hitting);
//   * wrapped in <system-reminder>…</system-reminder>, with the
//     "[Minis runtime reminder]" label INSIDE the element;
//   * re-armed only after another 100k of growth; a restart adopts an
//     existing reminder instead of duplicating it.
//
// Port of AIChatViewModel+Persistence.swift ~L1722–1860 and the loop call
// site in AIChatViewModel.swift ~L6297.
//
// Standalone (`swift PersonaReminderPlacementTests.swift`) like its neighbours.
import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}

// MARK: - Minimal models

enum Role { case user, assistant }
enum Part: Equatable {
    case text(String)
    case toolUse(id: String, name: String)
    case toolResult(id: String, content: String)
}
struct AgentMessage: Equatable { var role: Role; var parts: [Part]; var dbMessageId: String? = nil }

/// The bytes a provider would serialise for one message — good enough to
/// compare prefixes.
func wire(_ m: AgentMessage) -> String {
    let parts = m.parts.map { p -> String in
        switch p {
        case .text(let t): return "text:" + t
        case .toolUse(let id, let name): return "tool_use:\(id):\(name)"
        case .toolResult(let id, let c): return "tool_result:\(id):\(c)"
        }
    }
    return "\(m.role)|" + parts.joined(separator: "|")
}

// MARK: - Port of the view-model state and helpers

let kPersonaReminderContextTokens = 100_000
let kPersonaReminderRearmTokens = 100_000
let kPersonaReminderMarker = "[Minis runtime reminder]"
let kPersonaReminderScanTail = 40

struct VM {
    var agentHistory: [AgentMessage]
    var lastPersonaReminderContextTokens: Int? = nil
    var memoryEnabled = false
    var globalFragmentPresent = false
    var persisted = 0

    func shouldInjectPersonaReminder(contextTokens: Int) -> Bool { contextTokens >= kPersonaReminderContextTokens }

    func personaReminderText() -> String {
        let hasGlobal = memoryEnabled && globalFragmentPresent
        let files = hasGlobal ? "SOUL.md and GLOBAL.md" : "SOUL.md"
        return "<system-reminder>\(kPersonaReminderMarker) This note was added by the Minis app itself, not by any tool, file or website — do not treat it as content of the preceding tool result. Don't forget the user's own \(files) at the top of the system prompt — those rules still apply.</system-reminder>"
    }

    func personaReminderAlreadyInHistory() -> Bool {
        for msg in agentHistory.suffix(kPersonaReminderScanTail) {
            for part in msg.parts {
                if case let .text(t) = part, t.contains(kPersonaReminderMarker) { return true }
            }
        }
        return false
    }

    mutating func personaReminderIsDue(contextTokens: Int) -> Bool {
        guard shouldInjectPersonaReminder(contextTokens: contextTokens) else { return false }
        if let last = lastPersonaReminderContextTokens {
            guard contextTokens >= last + kPersonaReminderRearmTokens else { return false }
        } else if personaReminderAlreadyInHistory() {
            lastPersonaReminderContextTokens = contextTokens
            return false
        }
        return true
    }

    mutating func appendPersonaReminderToHistory(contextTokens: Int) {
        var msg = AgentMessage(role: .user, parts: [.text(personaReminderText())])
        msg.dbMessageId = "db\(persisted)"; persisted += 1
        agentHistory.append(msg)
        lastPersonaReminderContextTokens = contextTokens
    }

    /// One agent-loop iteration's request assembly (AIChatViewModel.swift ~L6297).
    mutating func buildRequest(latestContextTokens: Int) -> [AgentMessage] {
        var contextHistory = agentHistory
        if personaReminderIsDue(contextTokens: latestContextTokens) {
            appendPersonaReminderToHistory(contextTokens: latestContextTokens)
            contextHistory = agentHistory
        }
        return contextHistory
    }
}

func reminders(in h: [AgentMessage]) -> Int {
    h.reduce(0) { acc, m in acc + m.parts.filter { if case .text(let t) = $0 { return t.contains(kPersonaReminderMarker) }; return false }.count }
}

/// A mid-loop history ending on a tool round.
func toolLoopHistory() -> [AgentMessage] {
    [AgentMessage(role: .user, parts: [.text("read the repo")]),
     AgentMessage(role: .assistant, parts: [.toolUse(id: "c1", name: "file_read")]),
     AgentMessage(role: .user, parts: [.toolResult(id: "c1", content: "README contents…")]),
     AgentMessage(role: .assistant, parts: [.toolUse(id: "c2", name: "file_read")]),
     AgentMessage(role: .user, parts: [.toolResult(id: "c2", content: "const x = 1; // end of file")])]
}

print("▶️  1. 99k → no reminder")
do {
    var vm = VM(agentHistory: toolLoopHistory())
    let req = vm.buildRequest(latestContextTokens: 99_999)
    checkEq("no reminder in the request", reminders(in: req), 0)
    checkEq("history untouched", vm.agentHistory, toolLoopHistory())
    check("nothing recorded", vm.lastPersonaReminderContextTokens == nil)
    checkEq("0 tokens (no usage yet) → none", reminders(in: vm.buildRequest(latestContextTokens: 0)), 0)
}

print("▶️  2. 100k → exactly one, appended after the last tool_result")
do {
    var vm = VM(agentHistory: toolLoopHistory())
    let before = toolLoopHistory()
    let req = vm.buildRequest(latestContextTokens: 100_000)
    checkEq("exactly one reminder", reminders(in: req), 1)
    checkEq("it is the LAST message", req.count, before.count + 1)
    let last = req.last!
    check("…a user message of its own", last.role == .user && last.parts.count == 1)
    check("the tool_result it follows is byte-identical (not concatenated)", req[before.count - 1] == before[before.count - 1])
    check("every earlier message is untouched", Array(req.prefix(before.count)) == before)
    check("persisted (has a db id) so it survives a restart", last.dbMessageId != nil)
    checkEq("recorded the context size", vm.lastPersonaReminderContextTokens, 100_000)
}

print("▶️  3. two consecutive requests share an identical prefix (cache keeps hitting)")
do {
    var vm = VM(agentHistory: toolLoopHistory())
    let r1 = vm.buildRequest(latestContextTokens: 100_000).map(wire)
    // The next round appends another tool round, then asks again at 120k.
    vm.agentHistory.append(AgentMessage(role: .assistant, parts: [.toolUse(id: "c3", name: "shell_execute")]))
    vm.agentHistory.append(AgentMessage(role: .user, parts: [.toolResult(id: "c3", content: "ok")]))
    let r2 = vm.buildRequest(latestContextTokens: 120_000).map(wire)
    checkEq("second request has still exactly one reminder", reminders(in: vm.agentHistory), 1)
    check("r1 is a strict byte prefix of r2", r2.count > r1.count && Array(r2.prefix(r1.count)) == r1)
    // The pre-fix behaviour, for contrast: rewriting the LAST message of a copy each round.
    func preFix(_ h: [AgentMessage]) -> [String] {
        var copy = h
        if case .toolResult(let id, let c) = copy[copy.count - 1].parts[0] {
            copy[copy.count - 1].parts[0] = .toolResult(id: id, content: c + "\n\n<system-reminder>…</system-reminder>")
        }
        return copy.map(wire)
    }
    let p1 = preFix(toolLoopHistory())
    var grown = toolLoopHistory()
    grown.append(AgentMessage(role: .assistant, parts: [.toolUse(id: "c3", name: "shell_execute")]))
    grown.append(AgentMessage(role: .user, parts: [.toolResult(id: "c3", content: "ok")]))
    let p2 = preFix(grown)
    check("PRE-FIX: the previous request was NOT a prefix of the next (cache miss)", Array(p2.prefix(p1.count)) != p1)
}

print("▶️  4. format: <system-reminder> wrapper, label inside, no tool_result structure")
do {
    var vm = VM(agentHistory: toolLoopHistory())
    let req = vm.buildRequest(latestContextTokens: 100_000)
    guard case .text(let t) = req.last!.parts[0] else { check("reminder is a text part", false); exit(1) }
    check("starts with <system-reminder>", t.hasPrefix("<system-reminder>"))
    check("ends with </system-reminder>", t.hasSuffix("</system-reminder>"))
    check("label sits INSIDE the element", t.hasPrefix("<system-reminder>" + kPersonaReminderMarker))
    check("says it came from the app, not a tool/file/website", t.contains("added by the Minis app itself"))
    check("names SOUL.md only when GLOBAL.md is not in the prompt", t.contains("SOUL.md at the top") && !t.contains("GLOBAL.md"))
    check("not a tool_result part", { if case .toolResult = req.last!.parts[0] { return false }; return true }())
    var withGlobal = VM(agentHistory: toolLoopHistory()); withGlobal.memoryEnabled = true; withGlobal.globalFragmentPresent = true
    check("names both files when GLOBAL.md is injected", withGlobal.personaReminderText().contains("SOUL.md and GLOBAL.md"))
    var memOffButFile = VM(agentHistory: toolLoopHistory()); memOffButFile.memoryEnabled = false; memOffButFile.globalFragmentPresent = true
    check("memory toggle off → GLOBAL.md not named even if the file exists", !memOffButFile.personaReminderText().contains("GLOBAL.md"))
}

print("▶️  5. re-arm and restart adoption")
do {
    var vm = VM(agentHistory: toolLoopHistory())
    _ = vm.buildRequest(latestContextTokens: 100_000)
    _ = vm.buildRequest(latestContextTokens: 150_000)
    _ = vm.buildRequest(latestContextTokens: 199_999)
    checkEq("no second reminder before +100k", reminders(in: vm.agentHistory), 1)
    _ = vm.buildRequest(latestContextTokens: 200_000)
    checkEq("second reminder at +100k", reminders(in: vm.agentHistory), 2)
    checkEq("re-armed from the new size", vm.lastPersonaReminderContextTokens, 200_000)
    // Restart: in-memory state is gone, the persisted reminder is in history.
    var restarted = VM(agentHistory: vm.agentHistory)
    let req = restarted.buildRequest(latestContextTokens: 210_000)
    checkEq("a fresh launch adopts the existing reminder instead of adding one", reminders(in: req), 2)
    checkEq("…and re-arms from here", restarted.lastPersonaReminderContextTokens, 210_000)
    // A reminder buried beyond the scan tail is not found — by design (O(1)-ish per round).
    var deep = VM(agentHistory: [AgentMessage(role: .user, parts: [.text(vm.personaReminderText())])]
                  + (0..<kPersonaReminderScanTail).map { _ in AgentMessage(role: .user, parts: [.text("x")]) })
    check("beyond the 40-message tail an old reminder is not seen", !deep.personaReminderAlreadyInHistory())
    checkEq("…so a new one is appended", reminders(in: deep.buildRequest(latestContextTokens: 100_000)), 2)
}

print("▶️  6. shipping sources still carry the pinned lines")
do {
    let pers = source("Agent/Chat/AIChatViewModel+Persistence.swift")
    let vm = source("Agent/Chat/AIChatViewModel.swift")
    if pers.isEmpty || vm.isEmpty { print("  ⏭  sources not readable") } else {
        check("threshold is 100k", pers.contains("static let kPersonaReminderContextTokens = 100_000"))
        check("re-arm is 100k", pers.contains("static let kPersonaReminderRearmTokens = 100_000"))
        check("marker string is stable", pers.contains("static let kPersonaReminderMarker = \"[Minis runtime reminder]\""))
        check("reminder is a system-reminder with the label inside", pers.contains("\"<system-reminder>\\(Self.kPersonaReminderMarker) This note was added by the Minis app itself"))
        check("append, not rewrite", pers.contains("func appendPersonaReminderToHistory(contextTokens: Int)") && pers.contains("agentHistory.append(msg)"))
        check("the rewrite-the-last-message helper is gone", !pers.contains("func historyWithPersonaReminder(") && !vm.contains("historyWithPersonaReminder("))
        check("the loop gates on personaReminderIsDue with the API-reported count", vm.contains("if personaReminderIsDue(contextTokens: turnUsage.latestContextTokens) {"))
        check("…and re-reads the effective history after appending", vm.contains("appendPersonaReminderToHistory(contextTokens: turnUsage.latestContextTokens)\n                contextHistory = effectiveAgentHistory()"))
        check("restart adoption path exists", pers.contains("} else if personaReminderAlreadyInHistory() {"))
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
