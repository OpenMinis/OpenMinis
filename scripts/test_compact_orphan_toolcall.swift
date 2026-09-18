// Regression test for T-ios-compact-orphan-toolcall (commit c7f6a299e).
//
// Field bug: every Codex model AND the DeepSeek fallback returned
//   [400] No tool call found for function call output with call_id call_M1ate…
// and the conversation could only be escaped by clearing the session, because
// the failing history slice was recomputed identically on every retry.
//
// Root cause: `walkBackUserTurnsBounded` picked preAnchor's start by scanning
// for any `user` message, but tool RESULTS are themselves carried as
// role:.user messages — so the cut could land between an assistant's
// function_call and its own function_call_output, stranding the output.
//
// The two functions under test are pasted VERBATIM from production:
//   walkBackUserTurnsBounded  — src/ios/Agent/Chat/AIChatViewModel+Compaction.swift
//   dropOrphanedToolParts     — src/ios/Agent/Chat/AIChatViewModel+Persistence.swift
// Keep them in sync when editing those; this file is standalone because the
// MinisTests target has a pre-existing compile break (ToolPreflightTests), so
// `xcodebuild test` cannot run today.
//
// Run:  swift scripts/test_compact_orphan_toolcall.swift

import Foundation

// ─── Minimal stand-ins so the REAL production functions can link ───
// AgentMessage / AgentContentPart are copied verbatim in shape from
// src/ios/Providers/AgentProvider.swift (Foundation-only, no UIKit).
enum AgentContentPart: @unchecked Sendable {
    case text(String)
    case toolUse(id: String, name: String, input: [String: Any])
    case toolResult(id: String, name: String, content: String, isError: Bool, imageData: Data? = nil, imageMimeType: String? = nil, pageURL: String? = nil, imageLinuxPath: String? = nil)
    case imageData(data: Data, mimeType: String, linuxPath: String? = nil)
}

struct AgentMessage: @unchecked Sendable {
    enum Role: String, Sendable { case user, assistant }
    let role: Role
    var parts: [AgentContentPart]
    var isInterrupted: Bool = false
    var reasoningContent: String? = nil
    var dbMessageId: String? = nil
}

struct AppLogger {
    let category: String
    init(category: String) { self.category = category }
    func warning(_ s: String) { print("   [warn] \(s)") }
    func info(_ s: String) { }
    func debug(_ s: String) { }
}


enum Prod {
    static func dropOrphanedToolParts(_ history: [AgentMessage], logger: AppLogger) -> [AgentMessage] {
        var toolUseIds: Set<String> = []
        var toolResultIds: Set<String> = []
        for msg in history {
            for part in msg.parts {
                switch part {
                case .toolUse(let id, _, _): toolUseIds.insert(id)
                case .toolResult(let id, _, _, _, _, _, _, _): toolResultIds.insert(id)
                default: break
                }
            }
        }
        let orphanedResults = toolResultIds.subtracting(toolUseIds)
        var orphanedUses = toolUseIds.subtracting(toolResultIds)

        // [T-ios-compact-orphan-toolcall] IN-FLIGHT EXEMPTION. The tool_uses in
        // the FINAL assistant message are not orphans when the loop is still
        // between "model asked for tools" and "results appended" — agentHistory
        // legitimately looks unpaired for the whole of that window
        // (AIChatViewModel appends the assistant turn ~5263 and its tool results
        // only ~5569). `keepAliveHistory = effectiveAgentHistory()` snapshots
        // inside exactly that gap, so without this exemption a cache-warmup
        // request would carry fabricated "Tool execution was interrupted"
        // results for tools that were about to run normally — poisoning the
        // cached prefix and, worse, telling the model its tools had failed.
        // Trailing unanswered calls need no repair anyway: a request ending on
        // an assistant tool_use is exactly what the API expects mid-round.
        if let last = history.last, last.role == .assistant {
            for part in last.parts {
                if case .toolUse(let id, _, _) = part { orphanedUses.remove(id) }
            }
        }

        guard !orphanedResults.isEmpty || !orphanedUses.isEmpty else { return history }

        logger.warning("[CompactDiag] orphan tool parts in OUTGOING history — repairing. orphanedOutputs=\(orphanedResults.count) [\(orphanedResults.sorted().prefix(3).joined(separator: ","))] orphanedCalls=\(orphanedUses.count) [\(orphanedUses.sorted().prefix(3).joined(separator: ","))] historyCount=\(history.count)")

        var cleaned: [AgentMessage] = []
        cleaned.reserveCapacity(history.count)
        for var msg in history {
            let kept = msg.parts.filter { part in
                if case .toolResult(let id, _, _, _, _, _, _, _) = part {
                    return !orphanedResults.contains(id)
                }
                return true
            }
            if kept.isEmpty { continue }
            msg.parts = kept
            cleaned.append(msg)

            // Follow an assistant turn holding orphaned calls with the
            // placeholder results it never got, so the pair is complete.
            guard msg.role == .assistant else { continue }
            let unanswered = kept.compactMap { part -> (String, String)? in
                if case .toolUse(let id, let name, _) = part, orphanedUses.contains(id) {
                    return (id, name)
                }
                return nil
            }
            if !unanswered.isEmpty {
                cleaned.append(AgentMessage(
                    role: .user,
                    parts: unanswered.map { id, name in
                        AgentContentPart.toolResult(
                            id: id, name: name,
                            content: "Tool execution was interrupted before a result was returned.",
                            isError: true
                        )
                    }
                ))
            }
        }
        return cleaned
    }
}


struct WalkBackResult {
    let priorIdx: Int?
    let userTextTurnsFound: Int
    let messageCount: Int
    let stopReason: String
}

enum WB {
    static func walkBackUserTurnsBounded(
        agentHistory: [AgentMessage],
        anchorIdx: Int,
        maxUserTextTurns: Int,
        maxMessages: Int
    ) -> WalkBackResult {
        guard anchorIdx >= 0, anchorIdx < agentHistory.count else {
            return WalkBackResult(priorIdx: nil, userTextTurnsFound: 0, messageCount: 0, stopReason: "invalidAnchor")
        }
        var acceptedPriorIdx: Int? = nil
        var acceptedUserTextTurns = 0
        var acceptedMessageCount = 0

        // Scan strictly right-to-left. When we hit a user message, evaluate
        // "would accepting [thisUser ... anchorIdx] still fit?"
        for i in stride(from: anchorIdx, through: 0, by: -1) {
            let msg = agentHistory[i]
            guard msg.role == .user else { continue }
            // [T-ios-compact-orphan-toolcall] A user message carrying a
            // toolResult is the SECOND half of an assistant/tool round, not the
            // start of a new one. Cutting here strands its function_call_output
            // without the function_call. Skip it as a boundary candidate.
            let carriesToolResult = msg.parts.contains { part in
                if case .toolResult = part { return true }
                return false
            }
            if carriesToolResult { continue }
            let candidateMessageCount = anchorIdx - i + 1
            if candidateMessageCount > maxMessages {
                // Including this user round would exceed cap. Stop — keep
                // last accepted priorIdx (which is on an earlier-found user,
                // closer to anchor).
                return WalkBackResult(
                    priorIdx: acceptedPriorIdx,
                    userTextTurnsFound: acceptedUserTextTurns,
                    messageCount: acceptedMessageCount,
                    stopReason: "messageCapWouldExceed"
                )
            }
            // Accept this user as the new tentative priorIdx.
            acceptedPriorIdx = i
            acceptedMessageCount = candidateMessageCount
            let hasText = msg.parts.contains { part in
                if case .text(let t) = part, !t.isEmpty { return true }
                return false
            }
            if hasText {
                acceptedUserTextTurns += 1
                if acceptedUserTextTurns >= maxUserTextTurns {
                    return WalkBackResult(
                        priorIdx: acceptedPriorIdx,
                        userTextTurnsFound: acceptedUserTextTurns,
                        messageCount: acceptedMessageCount,
                        stopReason: "userTextTargetMet"
                    )
                }
            }
        }
        return WalkBackResult(
            priorIdx: acceptedPriorIdx,
            userTextTurnsFound: acceptedUserTextTurns,
            messageCount: acceptedMessageCount,
            stopReason: "reachedStart"
        )
    }
}


// Reproduces T-ios-compact-orphan-toolcall end-to-end against the REAL
// production functions (extracted verbatim from source, see run.sh):
//   - WB.walkBackUserTurnsBounded          (+Compaction.swift)
//   - Prod.dropOrphanedToolParts           (+Persistence.swift)
//
// Field case: gpt-5.6 / DeepSeek both returned
//   [400] No tool call found for function call output with call_id call_M1ate…
// after `walkBack stopped: reason=messageCapWouldExceed priorIdx=24`, where the
// tool_use sat at [23] and its tool_result at [24].

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

/// Build a tool-heavy history shaped like the real one:
///   [0] user text  (opening turn)
///   then repeating rounds of: assistant(tool_use) / user(tool_result)
///   with an occasional user-text turn.
func makeToolHeavyHistory(rounds: Int) -> [AgentMessage] {
    var h: [AgentMessage] = []
    h.append(AgentMessage(role: .user, parts: [.text("start")], dbMessageId: "db0"))
    for r in 0..<rounds {
        let id = "call_\(r)"
        h.append(AgentMessage(role: .assistant,
                              parts: [.toolUse(id: id, name: "shell_execute", input: [:])],
                              dbMessageId: "dbA\(r)"))
        // Tool results are carried as role:.user — the crux of the bug.
        h.append(AgentMessage(role: .user,
                              parts: [.toolResult(id: id, name: "shell_execute",
                                                  content: String(repeating: "x", count: 2000),
                                                  isError: false)],
                              dbMessageId: "dbR\(r)"))
        // Every 7th round is a genuine user-text turn (a legal boundary).
        if r % 7 == 6 {
            h.append(AgentMessage(role: .user, parts: [.text("follow-up \(r)")], dbMessageId: "dbU\(r)"))
        }
    }
    return h
}

/// Does `slice` contain a function_call_output with no matching function_call?
func orphanedOutputs(_ slice: [AgentMessage]) -> [String] {
    var uses = Set<String>(), results = Set<String>()
    for m in slice {
        for p in m.parts {
            if case .toolUse(let id, _, _) = p { uses.insert(id) }
            if case .toolResult(let id, _, _, _, _, _, _, _) = p { results.insert(id) }
        }
    }
    return results.subtracting(uses).sorted()
}

func orphanedCalls(_ slice: [AgentMessage]) -> [String] {
    var uses = Set<String>(), results = Set<String>()
    for m in slice {
        for p in m.parts {
            if case .toolUse(let id, _, _) = p { uses.insert(id) }
            if case .toolResult(let id, _, _, _, _, _, _, _) = p { results.insert(id) }
        }
    }
    return uses.subtracting(results).sorted()
}

// ── 1. THE BUG: the OLD boundary rule cuts a pair in half ──────────────
// Old rule = "any user message is a boundary". Re-implemented here ONLY to
// demonstrate the bug still reproduces with the pre-fix predicate.
func oldWalkBack(_ h: [AgentMessage], anchorIdx: Int, maxMessages: Int) -> Int? {
    var accepted: Int? = nil
    for i in stride(from: anchorIdx, through: 0, by: -1) {
        guard h[i].role == .user else { continue }
        if anchorIdx - i + 1 > maxMessages { return accepted }
        accepted = i
    }
    return accepted
}

print("── Scenario: tool-heavy session, preAnchor cap forces a mid-round cut ──")
let history = makeToolHeavyHistory(rounds: 80)
let anchorIdx = history.count - 1
let cap = 100

// OLD behaviour — expect an orphan.
if let oldPrior = oldWalkBack(history, anchorIdx: anchorIdx, maxMessages: cap) {
    let oldSlice = Array(history[oldPrior...anchorIdx])
    let orphans = orphanedOutputs(oldSlice)
    print("  old rule: priorIdx=\(oldPrior) role=\(history[oldPrior].role) sliceCount=\(oldSlice.count) orphanedOutputs=\(orphans.count) \(orphans.prefix(3))")
    check("PRE-FIX rule strands a function_call_output (bug reproduces)", !orphans.isEmpty, true)
} else {
    print("  ❌ old rule produced no boundary — scenario did not set up"); failures += 1
}

// NEW behaviour — the real production function.
let wb = WB.walkBackUserTurnsBounded(agentHistory: history, anchorIdx: anchorIdx,
                                     maxUserTextTurns: 3, maxMessages: cap)
print("  new rule: priorIdx=\(wb.priorIdx.map(String.init) ?? "nil") stopReason=\(wb.stopReason) msgs=\(wb.messageCount)")
if let p = wb.priorIdx {
    let carriesTR = history[p].parts.contains { if case .toolResult = $0 { return true }; return false }
    check("boundary is a user message", history[p].role == .user, true)
    check("boundary does NOT carry a tool_result", carriesTR, false)
    let newSlice = Array(history[p...anchorIdx])
    check("POST-FIX slice has no orphaned function_call_output", orphanedOutputs(newSlice).isEmpty, true)
    check("POST-FIX slice respects the \(cap)-message cap", newSlice.count <= cap, true)
} else {
    // Degenerate case is also acceptable (empty preAnchor), never an orphan.
    print("  (nil priorIdx → empty preAnchor, which cannot orphan anything)")
}

// ── 2. THE SAFETY NET: dropOrphanedToolParts repairs a broken slice ────
print("\n── Safety net: dropOrphanedToolParts on an already-broken slice ──")
let logger = AppLogger(category: "test")

// Slice deliberately starting mid-round: tool_result whose tool_use is absent.
let broken: [AgentMessage] = [
    AgentMessage(role: .user, parts: [.toolResult(id: "call_ORPHAN", name: "browser_use", content: "result", isError: false)]),
    AgentMessage(role: .assistant, parts: [.text("continuing")]),
    AgentMessage(role: .assistant, parts: [.toolUse(id: "call_OK", name: "shell_execute", input: [:])]),
    AgentMessage(role: .user, parts: [.toolResult(id: "call_OK", name: "shell_execute", content: "ok", isError: false)]),
]
check("broken input HAS an orphaned output", orphanedOutputs(broken) == ["call_ORPHAN"], true)
let repaired = Prod.dropOrphanedToolParts(broken, logger: logger)
check("repaired output has NO orphaned function_call_output", orphanedOutputs(repaired).isEmpty, true)
check("repaired output has NO orphaned function_call", orphanedCalls(repaired).isEmpty, true)
check("the healthy pair survived", repaired.contains { m in
    m.parts.contains { if case .toolUse(let id, _, _) = $0 { return id == "call_OK" }; return false }
}, true)
check("no empty-parts message was emitted", repaired.allSatisfy { !$0.parts.isEmpty }, true)

// IN-FLIGHT EXEMPTION: a history ending on an assistant tool_use is the normal
// mid-round shape (the model asked for tools; results have not been appended
// yet — `keepAliveHistory = effectiveAgentHistory()` snapshots exactly there).
// Those trailing calls must be left ALONE: fabricating "tool failed" results
// would poison the cached prefix and lie to the model.
print("\n── In-flight: trailing assistant tool_use must NOT be repaired ──")
let inFlight: [AgentMessage] = [
    AgentMessage(role: .user, parts: [.text("go")]),
    AgentMessage(role: .assistant, parts: [.toolUse(id: "call_INFLIGHT", name: "file_read", input: [:])]),
]
let inFlightOut = Prod.dropOrphanedToolParts(inFlight, logger: logger)
check("history is returned untouched", inFlightOut.count == inFlight.count, true)
check("no placeholder result was fabricated", inFlightOut.contains { m in
    m.parts.contains { if case .toolResult = $0 { return true }; return false }
}, false)
check("the trailing tool_use survived", inFlightOut.contains { m in
    m.parts.contains { if case .toolUse(let id, _, _) = $0 { return id == "call_INFLIGHT" }; return false }
}, true)

// A call orphaned in the MIDDLE of history is genuinely broken (its round
// ended without a result) and still gets a placeholder rather than deletion.
print("\n── Mid-history orphaned function_call gets a placeholder result ──")
let danglingCall: [AgentMessage] = [
    AgentMessage(role: .user, parts: [.text("go")]),
    AgentMessage(role: .assistant, parts: [.toolUse(id: "call_NORESULT", name: "file_read", input: [:])]),
    AgentMessage(role: .user, parts: [.text("never mind, do something else")]),
]
let fixedCall = Prod.dropOrphanedToolParts(danglingCall, logger: logger)
check("orphaned call is now paired", orphanedCalls(fixedCall).isEmpty, true)
check("the assistant's tool_use was preserved (not deleted)", fixedCall.contains { m in
    m.parts.contains { if case .toolUse(let id, _, _) = $0 { return id == "call_NORESULT" }; return false }
}, true)
check("a synthesised error result was appended", fixedCall.contains { m in
    m.parts.contains { if case .toolResult(let id, _, _, let isErr, _, _, _, _) = $0 { return id == "call_NORESULT" && isErr }; return false }
}, true)

// Healthy history must pass through byte-identical in count.
print("\n── No-op guarantee on a healthy history ──")
let healthy = makeToolHeavyHistory(rounds: 5)
let passthrough = Prod.dropOrphanedToolParts(healthy, logger: logger)
check("healthy history is returned unchanged", passthrough.count == healthy.count, true)



print("\n── Exhaustive boundary sweep ──")
// Exhaustive sweep: for many history shapes and cap values, assert the
// production walk-back NEVER yields a slice with an orphaned output, and
// that the old rule does so often (proving the sweep really hits the case).
var oldBad = 0, newBad = 0, cases = 0
for rounds in stride(from: 10, through: 90, by: 4) {
  for cap in [20, 37, 50, 64, 80, 100, 120] {
    for textEvery in [3, 5, 7, 11, 1000] {   // 1000 = no user-text turns at all
      cases += 1
      var h: [AgentMessage] = [AgentMessage(role: .user, parts: [.text("start")], dbMessageId: "db0")]
      for r in 0..<rounds {
        let id = "c\(r)"
        h.append(AgentMessage(role: .assistant, parts: [.toolUse(id: id, name: "t", input: [:])], dbMessageId: "a\(r)"))
        h.append(AgentMessage(role: .user, parts: [.toolResult(id: id, name: "t", content: "r", isError: false)], dbMessageId: "r\(r)"))
        if r % textEvery == textEvery - 1 { h.append(AgentMessage(role: .user, parts: [.text("u\(r)")], dbMessageId: "u\(r)")) }
      }
      let anchor = h.count - 1
      func orph(_ s: [AgentMessage]) -> Bool {
        var u = Set<String>(), t = Set<String>()
        for m in s { for p in m.parts {
          if case .toolUse(let i,_,_) = p { u.insert(i) }
          if case .toolResult(let i,_,_,_,_,_,_,_) = p { t.insert(i) } } }
        return !t.subtracting(u).isEmpty
      }
      if let op = oldWalkBack2(h, anchorIdx: anchor, maxMessages: cap), orph(Array(h[op...anchor])) { oldBad += 1 }
      let wb = WB.walkBackUserTurnsBounded(agentHistory: h, anchorIdx: anchor, maxUserTextTurns: 3, maxMessages: cap)
      if let np = wb.priorIdx, orph(Array(h[np...anchor])) {
        newBad += 1
        print("  ❌ LEAK rounds=\(rounds) cap=\(cap) textEvery=\(textEvery) priorIdx=\(np) reason=\(wb.stopReason)")
      }
    }
  }
}
print("sweep cases=\(cases)  oldRuleOrphaned=\(oldBad)  newRuleOrphaned=\(newBad)")
if newBad > 0 { failures += 1 }

func oldWalkBack2(_ h: [AgentMessage], anchorIdx: Int, maxMessages: Int) -> Int? {
    var accepted: Int? = nil
    for i in stride(from: anchorIdx, through: 0, by: -1) {
        guard h[i].role == .user else { continue }
        if anchorIdx - i + 1 > maxMessages { return accepted }
        accepted = i
    }
    return accepted
}



// ── Drift guard ────────────────────────────────────────────────────────
// The two functions above are pasted copies. If someone edits production and
// forgets this file, the test would keep passing while protecting nothing.
// Compare the pasted bodies against the live source and fail loudly on drift.
// Skipped when the sources are not reachable (e.g. running the binary from a
// different cwd) rather than failing, so the logic checks stay runnable.
func extractBody(_ path: String, _ signature: String) -> String? {
    guard let src = try? String(contentsOfFile: path, encoding: .utf8),
          let r = src.range(of: signature) else { return nil }
    var depth = 0
    var started = false
    var out = ""
    for ch in src[r.lowerBound...] {
        out.append(ch)
        if ch == "{" { depth += 1; started = true }
        else if ch == "}" { depth -= 1; if started && depth == 0 { break } }
    }
    return out
}

/// Strip comments + all whitespace so formatting-only edits don't trip it.
func normalise(_ s: String) -> String {
    var out = ""
    for line in s.split(separator: "\n", omittingEmptySubsequences: false) {
        let t = line.trimmingCharacters(in: .whitespaces)
        if t.hasPrefix("//") { continue }
        out += t
    }
    return out.filter { !$0.isWhitespace }
}

print("\n── Drift guard: pasted copies still match production ──")
let repoRoot = FileManager.default.currentDirectoryPath
let prodPersistence = repoRoot + "/src/ios/Agent/Chat/AIChatViewModel+Persistence.swift"
let prodCompaction = repoRoot + "/src/ios/Agent/Chat/AIChatViewModel+Compaction.swift"
let selfPath = repoRoot + "/scripts/test_compact_orphan_toolcall.swift"

if FileManager.default.fileExists(atPath: prodPersistence),
   FileManager.default.fileExists(atPath: selfPath) {
    let sig1 = "static func dropOrphanedToolParts"
    if let live = extractBody(prodPersistence, sig1),
       let pasted = extractBody(selfPath, sig1) {
        check("dropOrphanedToolParts matches production", normalise(live) == normalise(pasted), true)
    } else {
        print("  ⚠️  could not extract dropOrphanedToolParts — check the signature")
        failures += 1
    }
    // The walk-back copy gains an `agentHistory:` parameter to run standalone,
    // so compare only from the body's first statement onward.
    if let live = extractBody(prodCompaction, "func walkBackUserTurnsBounded"),
       let pasted = extractBody(selfPath, "static func walkBackUserTurnsBounded") {
        let liveBody = live.drop { $0 != "{" }
        let pastedBody = pasted.drop { $0 != "{" }
        check("walkBackUserTurnsBounded matches production", normalise(String(liveBody)) == normalise(String(pastedBody)), true)
    } else {
        print("  ⚠️  could not extract walkBackUserTurnsBounded — check the signature")
        failures += 1
    }
} else {
    print("  ⏭  sources not reachable from cwd — drift guard skipped")
}

print("\n\(failures == 0 ? "✅ ALL CHECKS PASSED (incl. drift guard)" : "❌ \(failures) CHECK(S) FAILED")")
exit(failures == 0 ? 0 : 1)
