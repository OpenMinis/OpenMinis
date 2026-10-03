// Tests for [T-ios-compact-orphan-toolcall] — the tool-pairing invariants that
// sit between compaction and the provider request.
//
// Pins the JOINT behaviour of two functions that ship in the view model:
//
//   * `AIChatViewModel.dropOrphanedToolParts` (AIChatViewModel+Persistence.swift
//     ~1892, commits 60b4603f0 / 04db20f01 / 5d346dc2e): a tool_result with no
//     tool_use anywhere in the slice is dropped; a tool_use with no tool_result
//     gets a synthetic "interrupted" error result spliced in right after its
//     assistant turn; the tool_uses of the FINAL assistant message are exempt
//     because the loop is legitimately between "model asked" and "results
//     appended" (5d346dc2e — a cache-warmup snapshot inside that gap used to
//     fabricate failures for tools that were about to run normally).
//   * `AIChatViewModel.walkBackUserTurnsBounded` (AIChatViewModel+Compaction.swift
//     ~409, commit c7f6a299e): the v2 compaction walk-back may only cut at a
//     user message that carries NO toolResult part, or it splits an
//     assistant(tool_use)/user(tool_result) pair down the middle and every
//     retry re-sends the same 400 (`No tool call found for function call
//     output with call_id …`), wedging the conversation.
//
// Both are ported verbatim (minus logging) — they are instance/static members
// of the view model and the app cannot link for a simulator. Section [6]
// re-reads the shipping sources so the copies cannot drift.
//
// Standalone: `swift ToolPairingInvariantTests.swift`.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ actual: T, _ expected: T) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label)\n     expected: \(expected)\n     actual:   \(actual)"); failures += 1 }
}

// MARK: - Minimal mirror of Providers/AgentProvider.swift

enum AgentContentPart: Equatable {
    case text(String)
    case toolUse(id: String, name: String)
    case toolResult(id: String, name: String, content: String, isError: Bool)
    case imageData
}
struct AgentMessage: Equatable {
    enum Role { case user, assistant }
    let role: Role
    var parts: [AgentContentPart]
}

let interruptedText = "Tool execution was interrupted by an unexpected error."

// MARK: - Verbatim port: AIChatViewModel+Persistence.swift dropOrphanedToolParts

/// [T-responses-tool-id-normalize] Verbatim port of AIChatViewModel.pairingKey.
/// The Responses path carries "<call_id>|<fc_id>" and the wire matches on the
/// call_id half, so this layer must too or the two disagree.
func pairingKey(_ id: String) -> String {
    guard let pipe = id.firstIndex(of: "|") else { return id }
    return String(id[id.startIndex..<pipe])
}

func dropOrphanedToolParts(_ history: [AgentMessage]) -> [AgentMessage] {
    var toolUseIds: Set<String> = []
    var toolResultIds: Set<String> = []
    for msg in history {
        for part in msg.parts {
            switch part {
            case .toolUse(let id, _): toolUseIds.insert(pairingKey(id))
            case .toolResult(let id, _, _, _): toolResultIds.insert(pairingKey(id))
            default: break
            }
        }
    }
    let orphanedResults = toolResultIds.subtracting(toolUseIds)
    var orphanedUses = toolUseIds.subtracting(toolResultIds)

    // IN-FLIGHT EXEMPTION (5d346dc2e).
    if let last = history.last, last.role == .assistant {
        for part in last.parts {
            if case .toolUse(let id, _) = part { orphanedUses.remove(pairingKey(id)) }
        }
    }

    guard !orphanedResults.isEmpty || !orphanedUses.isEmpty else { return history }

    var cleaned: [AgentMessage] = []
    cleaned.reserveCapacity(history.count)
    for var msg in history {
        let kept = msg.parts.filter { part in
            if case .toolResult(let id, _, _, _) = part {
                return !orphanedResults.contains(pairingKey(id))
            }
            return true
        }
        if kept.isEmpty { continue }
        msg.parts = kept
        cleaned.append(msg)

        guard msg.role == .assistant else { continue }
        let unanswered = kept.compactMap { part -> (String, String)? in
            if case .toolUse(let id, let name) = part, orphanedUses.contains(pairingKey(id)) {
                return (id, name)
            }
            return nil
        }
        if !unanswered.isEmpty {
            cleaned.append(AgentMessage(
                role: .user,
                parts: unanswered.map { id, name in
                    AgentContentPart.toolResult(id: id, name: name, content: interruptedText, isError: true)
                }
            ))
        }
    }
    return cleaned
}

// MARK: - Verbatim port: AIChatViewModel+Compaction.swift walkBackUserTurnsBounded

struct WalkBackResult: Equatable {
    let priorIdx: Int?
    let userTextTurnsFound: Int
    let messageCount: Int
    let stopReason: String
}

func walkBackUserTurnsBounded(_ agentHistory: [AgentMessage], anchorIdx: Int,
                              maxUserTextTurns: Int, maxMessages: Int) -> WalkBackResult {
    guard anchorIdx >= 0, anchorIdx < agentHistory.count else {
        return WalkBackResult(priorIdx: nil, userTextTurnsFound: 0, messageCount: 0, stopReason: "invalidAnchor")
    }
    var acceptedPriorIdx: Int? = nil
    var acceptedUserTextTurns = 0
    var acceptedMessageCount = 0
    for i in stride(from: anchorIdx, through: 0, by: -1) {
        let msg = agentHistory[i]
        guard msg.role == .user else { continue }
        let carriesToolResult = msg.parts.contains { part in
            if case .toolResult = part { return true }
            return false
        }
        if carriesToolResult { continue }
        let candidateMessageCount = anchorIdx - i + 1
        if candidateMessageCount > maxMessages {
            return WalkBackResult(priorIdx: acceptedPriorIdx, userTextTurnsFound: acceptedUserTextTurns,
                                  messageCount: acceptedMessageCount, stopReason: "messageCapWouldExceed")
        }
        acceptedPriorIdx = i
        acceptedMessageCount = candidateMessageCount
        let hasText = msg.parts.contains { part in
            if case .text(let t) = part, !t.isEmpty { return true }
            return false
        }
        if hasText {
            acceptedUserTextTurns += 1
            if acceptedUserTextTurns >= maxUserTextTurns {
                return WalkBackResult(priorIdx: acceptedPriorIdx, userTextTurnsFound: acceptedUserTextTurns,
                                      messageCount: acceptedMessageCount, stopReason: "userTextTargetMet")
            }
        }
    }
    return WalkBackResult(priorIdx: acceptedPriorIdx, userTextTurnsFound: acceptedUserTextTurns,
                          messageCount: acceptedMessageCount, stopReason: "reachedStart")
}

// MARK: - Helpers

func user(_ t: String) -> AgentMessage { AgentMessage(role: .user, parts: [.text(t)]) }
func assistant(_ t: String) -> AgentMessage { AgentMessage(role: .assistant, parts: [.text(t)]) }
func calls(_ ids: [String]) -> AgentMessage {
    AgentMessage(role: .assistant, parts: ids.map { .toolUse(id: $0, name: "shell_execute") })
}
func results(_ ids: [String]) -> AgentMessage {
    AgentMessage(role: .user, parts: ids.map { .toolResult(id: $0, name: "shell_execute", content: "ok", isError: false) })
}
func toolUseIds(_ h: [AgentMessage]) -> [String] {
    h.flatMap { $0.parts.compactMap { if case .toolUse(let id, _) = $0 { return id }; return nil } }
}
func toolResultIds(_ h: [AgentMessage]) -> [String] {
    h.flatMap { $0.parts.compactMap { if case .toolResult(let id, _, _, _) = $0 { return id }; return nil } }
}
func syntheticIds(_ h: [AgentMessage]) -> [String] {
    h.flatMap { $0.parts.compactMap {
        if case .toolResult(let id, _, let c, let e) = $0, e, c == interruptedText { return id }; return nil
    } }
}
/// The pairing invariant every outbound slice must satisfy.
func isPaired(_ h: [AgentMessage]) -> Bool {
    let uses = Set(toolUseIds(h)), res = Set(toolResultIds(h))
    // Every result has a use; every use has a result, except in-flight ones on a trailing assistant.
    guard res.isSubset(of: uses) else { return false }
    var trailing: Set<String> = []
    if let last = h.last, last.role == .assistant {
        trailing = Set(last.parts.compactMap { if case .toolUse(let id, _) = $0 { return id }; return nil })
    }
    return uses.subtracting(res).isSubset(of: trailing)
}

// MARK: - [1] orphan tool_result (no tool_use anywhere) is dropped

print("\n[1] orphaned tool_result → dropped")
do {
    let h = [
        user("hi"),
        results(["call_ghost"]),          // its tool_use was compacted away
        assistant("done"),
    ]
    let out = dropOrphanedToolParts(h)
    check("the orphan result is gone", !toolResultIds(out).contains("call_ghost"))
    check("the user message it emptied is removed (empty content is itself a 400)",
          out.count == 2 && out[0] == user("hi") && out[1] == assistant("done"))
    check("nothing synthetic was invented", syntheticIds(out).isEmpty)

    // Mixed: one orphan and one legitimate result in the same user message.
    let mixed = [calls(["a"]), AgentMessage(role: .user, parts: [
        .toolResult(id: "a", name: "shell_execute", content: "ok", isError: false),
        .toolResult(id: "ghost", name: "shell_execute", content: "?", isError: false),
    ]), assistant("done")]
    let out2 = dropOrphanedToolParts(mixed)
    checkEq("only the orphan part is filtered, the paired one survives", toolResultIds(out2), ["a"])
}

// MARK: - [2] mid-history dangling tool_use → synthetic interrupted result

print("\n[2] mid-history dangling tool_use → synthetic 'interrupted' result")
do {
    let h = [
        user("run it"),
        calls(["call_1"]),                // stream died before the result was appended
        user("are you still there?"),     // the user typed again
        assistant("yes"),
    ]
    let out = dropOrphanedToolParts(h)
    checkEq("a synthetic result for call_1 exists", syntheticIds(out), ["call_1"])
    check("it is spliced in directly after the assistant turn",
          out.count == 5 && out[1] == calls(["call_1"]) && out[2].role == .user
            && out[2].parts == [.toolResult(id: "call_1", name: "shell_execute", content: interruptedText, isError: true)])
    check("the user's own message follows it untouched", out[3] == user("are you still there?"))
    check("result is paired afterwards", isPaired(out))
    check("the synthetic result is flagged isError", {
        if case .toolResult(_, _, _, let e) = out[2].parts[0] { return e }; return false
    }())
}

// MARK: - [3] trailing in-flight tool_use is exempt (5d346dc2e)

print("\n[3] tail assistant with in-flight tool_use → untouched")
do {
    let h = [
        user("run it"),
        calls(["call_live_1", "call_live_2"]),   // results are about to be appended
    ]
    let out = dropOrphanedToolParts(h)
    check("history is returned as-is (same instance shape, no synthetic)", out == h)
    check("no fabricated failure for a tool that is about to run", syntheticIds(out).isEmpty)

    // The exemption is scoped to the LAST message only: an earlier dangle in
    // the same history is still repaired while the tail stays untouched.
    let h2 = [
        user("run it"),
        calls(["call_old"]),
        user("hmm"),
        calls(["call_live"]),
    ]
    let out2 = dropOrphanedToolParts(h2)
    checkEq("earlier dangle repaired", syntheticIds(out2), ["call_old"])
    check("trailing in-flight call still has no result", !toolResultIds(out2).contains("call_live"))
    check("tail message is the original assistant turn", out2.last == calls(["call_live"]))

    // A trailing USER message does not exempt anything.
    let h3 = [calls(["call_x"]), user("go on")]
    checkEq("dangle before a trailing user message is repaired", syntheticIds(dropOrphanedToolParts(h3)), ["call_x"])
}

// MARK: - [4] walk-back never cuts at a tool_result user message (c7f6a299e)

print("\n[4] walk-back boundary skips tool_result carriers")
let history: [AgentMessage] = [
    user("first question"),            // 0
    calls(["c1"]),                     // 1
    results(["c1"]),                   // 2  ← illegal boundary
    assistant("answer 1"),             // 3
    user("second question"),           // 4
    calls(["c2", "c3"]),               // 5
    results(["c2", "c3"]),             // 6  ← illegal boundary
    assistant("answer 2"),             // 7
    user("third question"),            // 8
    assistant("answer 3"),             // 9
]
do {
    // Field report shape: the tool_result at [2] was the natural candidate
    // (it is the user message nearest the target), and cutting there stranded
    // its function_call_output without the function_call at [1].
    let r = walkBackUserTurnsBounded(history, anchorIdx: 3, maxUserTextTurns: 1, maxMessages: 50)
    checkEq("anchor 3, 1 turn: skips the tool_result at [2] and lands on [0]", r.priorIdx, 0)
    checkEq("…stop reason", r.stopReason, "userTextTargetMet")

    let r2 = walkBackUserTurnsBounded(history, anchorIdx: 7, maxUserTextTurns: 1, maxMessages: 50)
    checkEq("anchor 7, 1 turn: skips [6], lands on [4]", r2.priorIdx, 4)
    let r3 = walkBackUserTurnsBounded(history, anchorIdx: 7, maxUserTextTurns: 2, maxMessages: 50)
    checkEq("anchor 7, 2 turns: [4] then [0], never [6] or [2]", r3.priorIdx, 0)

    // Exhaustive: for every anchor and every turn budget, the chosen boundary
    // is never a tool_result carrier and the resulting slice is already paired
    // (dropOrphanedToolParts has nothing to repair).
    var violations = 0
    var sliceRepairs = 0
    for anchor in 0..<history.count {
        for turns in 1...3 {
            for cap in [2, 4, 50] {
                let res = walkBackUserTurnsBounded(history, anchorIdx: anchor, maxUserTextTurns: turns, maxMessages: cap)
                guard let p = res.priorIdx else { continue }
                if history[p].parts.contains(where: { if case .toolResult = $0 { return true }; return false }) { violations += 1 }
                let slice = Array(history[p...anchor])
                if dropOrphanedToolParts(slice) != slice { sliceRepairs += 1 }
            }
        }
    }
    checkEq("no boundary ever lands on a tool_result user message", violations, 0)
    checkEq("every [priorIdx…anchor] slice is already paired (no repair needed)", sliceRepairs, 0)

    // The message cap still applies to the accepted candidate, not the skipped one.
    let r4 = walkBackUserTurnsBounded(history, anchorIdx: 7, maxUserTextTurns: 2, maxMessages: 4)
    checkEq("cap 4 from anchor 7 accepts [4] (4 msgs) and stops before [0]", r4.priorIdx, 4)
    checkEq("…stop reason", r4.stopReason, "messageCapWouldExceed")
    let r5 = walkBackUserTurnsBounded(history, anchorIdx: 7, maxUserTextTurns: 2, maxMessages: 2)
    checkEq("cap 2 from anchor 7: [6] is skipped (not a candidate), [4] exceeds → nil", r5.priorIdx, nil)
}

// MARK: - [5] parallel batch: 3 calls, 2 results → third is completed, order kept

print("\n[5] parallel batch: 3 calls / 2 results")
do {
    let h = [
        user("do three things"),
        calls(["p1", "p2", "p3"]),
        results(["p1", "p2"]),            // p3 never came back
        assistant("two done"),
        user("and the third?"),
    ]
    let out = dropOrphanedToolParts(h)
    checkEq("p3 gets a synthetic result", syntheticIds(out), ["p3"])
    checkEq("the assistant's tool_use order is unchanged", toolUseIds(out), ["p1", "p2", "p3"])
    check("all three calls are answered", Set(toolResultIds(out)) == ["p1", "p2", "p3"])
    check("the real results are untouched",
          out.contains(results(["p1", "p2"])))
    // No result precedes its call.
    var seenUses: Set<String> = []
    var ordered = true
    for m in out {
        for p in m.parts {
            if case .toolUse(let id, _) = p { seenUses.insert(id) }
            if case .toolResult(let id, _, _, _) = p, !seenUses.contains(id) { ordered = false }
        }
    }
    check("every result follows its tool_use", ordered)
    check("the slice is paired", isPaired(out))

    // All-or-nothing on the RESULT side: when the whole batch's tool_use turn
    // was compacted away, every one of its results is dropped, not just some.
    let noCalls = [user("x"), results(["p1", "p2", "p3"]), assistant("…")]
    let out2 = dropOrphanedToolParts(noCalls)
    check("a batch of results with no calls is cleared entirely", toolResultIds(out2).isEmpty)
    checkEq("…and the emptied message vanishes", out2.count, 2)

    // Idempotent: a repaired slice is a fixed point.
    check("repair is idempotent", dropOrphanedToolParts(out) == out)
}

// MARK: - [6] shipping source cross-check

print("\n[6] shipping source cross-check")
func sourceOf(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let persistence = sourceOf("Agent/Chat/AIChatViewModel+Persistence.swift")
let compaction = sourceOf("Agent/Chat/AIChatViewModel+Compaction.swift")
check("Persistence source read", !persistence.isEmpty)
check("Compaction source read", !compaction.isEmpty)
check("dropOrphanedToolParts ships as a static pure function",
      persistence.contains("static func dropOrphanedToolParts(_ history: [AgentMessage], logger: AppLogger) -> [AgentMessage]"))
check("effectiveAgentHistory routes through it",
      persistence.contains("Self.dropOrphanedToolParts(effectiveAgentHistoryUncounted(), logger: logger)"))
check("in-flight exemption is scoped to the trailing assistant message",
      persistence.contains("if let last = history.last, last.role == .assistant {")
        && persistence.contains("if case .toolUse(let id, _, _, _) = part { orphanedUses.remove(Self.pairingKey(id)) }"))
// [T-responses-tool-id-normalize] Comparisons go through `pairingKey`, not the
// raw id: the Responses path carries "<call_id>|<fc_id>" while the wire matches
// on the call_id half, and the two layers disagreeing is what let an orphan
// through to a 400. These assertions used to pin the raw-id form.
check("orphan results are filtered by the normalized id",
      persistence.contains("return !orphanedResults.contains(Self.pairingKey(id))"))
check("…and the id sets are built with it too",
      persistence.contains("toolUseIds.insert(Self.pairingKey(id))")
        && persistence.contains("toolResultIds.insert(Self.pairingKey(id))"))
check("emptied messages are removed",
      persistence.contains("if kept.isEmpty { continue }"))
check("synthetic result wording is unchanged",
      persistence.contains("content: \"\(interruptedText)\","))
check("synthetic results are flagged isError", persistence.contains("isError: true"))
check("walkBackUserTurnsBounded ships", compaction.contains("func walkBackUserTurnsBounded("))
check("walk-back skips tool_result carriers",
      compaction.contains("if carriesToolResult { continue }"))
check("…evaluated before the message cap",
      (compaction.range(of: "if carriesToolResult { continue }")?.lowerBound ?? compaction.endIndex)
        < (compaction.range(of: "if candidateMessageCount > maxMessages {")?.lowerBound ?? compaction.startIndex))

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
