// Edge-case guards for the iOS turn-lifecycle / wire-protocol fixes of the week
// of 2026-09-16. Complements (does not duplicate) ErrorInfoTargetRowTests,
// CodexEmptyResponseReproTests, ShellTimeoutPreserveOutputTests,
// ResponsesToolPairingTests, ResponsesReasoningPassbackTests and
// ClaudeOAuthTokenRequestTests.
//
// Guards, by commit / task id:
//   [1] fd7ec79a2  T-error-persist-current-turn  vs  T-ios-error-banner-lost
//       An early failure on turn >= 2 must still leave a durable error row.
//   [2] 627f7c403  T-ios-reasoning-only-empty-turn  +  8d17198d6 T-openai-done-overwrite
//       A `.toolUse` stop that produced NO tool entries is a stall, with or
//       without reasoning.
//   [3] 3092a9c4b  T-ish-shell-timeout-preserve-output
//       Past the mirror cap, the TAIL (the error right before the hang) must
//       survive, and the notice must name what was dropped.
//   [4] bc142f750  T-responses-orphan-tool-output        (known gaps, warn only)
//   [5] 0250ba32b / 04c80e68e  T-responses-echo-persist  (known gap, warn only)
//   [6] bc142f750  T-responses-tool-id-normalize          (known gap, warn only)
//   [7] 9b6a9910c  issue #360: iOS and Android send the SAME Claude CLI
//       mimicry header set (the backend version-gates models on User-Agent).
//
// [1]–[3] were real defects when this file was written; they are fixed
// (T-error-persist-turn2-carrier, T-ios-tooluse-stop-no-entries,
// T-ish-shell-timeout-keep-tail) and these sections now pin the fix.
// Lines marked ⚠️ document RISK gaps and do not fail the run.
//
// Standalone: `cd src/ios/MinisTests/Standalone && swift TurnLifecycleWireEdgeTests.swift`

import Foundation

var failures = 0
var warnings = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func warn(_ label: String, _ gapPresent: Bool) {
    if gapPresent { print("  ⚠️  KNOWN GAP (RISK): \(label)"); warnings += 1 }
    else { print("  ✅ gap closed: \(label)") }
}

let root = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
let repo = root.deletingLastPathComponent().deletingLastPathComponent()
func src(_ rel: String) -> String {
    (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
func repoSrc(_ rel: String) -> String {
    (try? String(contentsOf: repo.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
func slice(_ text: String, from start: String, to end: String) -> String {
    guard let a = text.range(of: start),
          let b = text.range(of: end, range: a.upperBound..<text.endIndex) else { return "" }
    return String(text[a.lowerBound..<b.upperBound])
}

let persistSrc = src("Agent/Chat/AIChatViewModel+Persistence.swift")
let vmSrc = src("Agent/Chat/AIChatViewModel.swift")
let sseSrc = src("Agent/Chat/AIChatViewModel+SSEStream.swift")
let openaiSrc = src("Providers/OpenAI/OpenAIAgentProvider.swift")
let coordSrc = src("Agent/ISH/ISHExecutionCoordinator.swift")
let oauthSrc = src("Providers/Anthropic/OAuthHTTPClient.swift")
let androidMimicrySrc = repoSrc("src/android/app/src/main/java/com/openminis/app/auth/ClaudeCliMimicryHeaders.kt")

// MARK: - [1] Error durability on turn >= 2

print("▶️  1. early failure on a later turn must still be durable (fd7ec79a2 vs T-ios-error-banner-lost)")
enum Part { case text(String), toolResult }
struct Msg { enum Role { case user, assistant }; var role: Role; var parts: [Part]; var db: String? = nil }
func currentTurnStartIndex(_ h: [Msg]) -> Int {
    guard let i = h.lastIndex(where: { m in
        m.role == .user && m.parts.contains { if case .toolResult = $0 { return false }; return true }
    }) else { return 0 }
    return i + 1
}
enum Outcome: Equatable { case row(String), carrier, lost }

let persistFn = slice(persistSrc, from: "func persistErrorInfo(_ error: String?) async {", to: "\n    }\n")
check("persistErrorInfo located", !persistFn.isEmpty)
check("source: a turn with no row of its own is detected",
      persistFn.contains("thisTurnHasRow = agentHistory.indices.contains {"))
check("source: the last-row UPDATE is gated on this turn having a row",
      persistFn.contains("if thisTurnHasRow,\n           let dbId = agentHistory.last("))
check("source: no early return left in the turn-scoping branch",
      persistFn.contains("[ErrorPersist] skip — this turn has no persisted row"), false)

/// Port of persistErrorInfo's write path with `sessionId != nil`.
func persistWrite(_ h: [Msg]) -> Outcome {
    let start = currentTurnStartIndex(h)
    let thisTurnHasRow = h.indices.contains { $0 >= start && h[$0].role == .assistant && h[$0].db != nil }
    if thisTurnHasRow, let id = h.last(where: { $0.role == .assistant && $0.db != nil })?.db { return .row(id) }
    return .carrier
}
do {
    let turn1 = [Msg(role: .user, parts: [.text("q1")])]
    check("turn 1 early failure → carrier row (durable)", persistWrite(turn1) == .carrier)
    let turn2 = [Msg(role: .user, parts: [.text("q1")]),
                 Msg(role: .assistant, parts: [.text("a1")], db: "A1"),
                 Msg(role: .user, parts: [.text("q2")])]
    let o = persistWrite(turn2)
    check("turn 2 early failure is never stamped on A1", o != .row("A1"))
    // Was a bug: nothing was written, so a session switch / relaunch showed
    // turn 2 as if nothing happened (T-ios-error-banner-lost, turn 2+ edition).
    check("turn 2 early failure is durable (carrier), not silently lost", o == .carrier)
    let turn2Partial = turn2 + [Msg(role: .assistant, parts: [.text("partial")], db: "A2")]
    check("turn 2 failure after output stamps turn 2's own row", persistWrite(turn2Partial) == .row("A2"))
    let afterTool = turn2Partial + [Msg(role: .user, parts: [.toolResult])]
    check("a tool-result user row does not start a new turn", persistWrite(afterTool) == .row("A2"))
}

// MARK: - [2] toolUse stop with zero tool entries

print("\n▶️  2. a .toolUse stop with no tool entries is a stall (627f7c403 + 8d17198d6)")
enum Stop { case endTurn, toolUse, maxTokens, refusal }
struct R { var text = ""; var entries = 0; var reasoning: String? = nil; var interrupted = false; var stop: Stop? = nil }
/// Verbatim port of the current isEmptyResponse.
func isEmptyResponse(_ r: R) -> Bool {
    return r.text.isEmpty && r.entries == 0
        && !r.interrupted
        && r.stop != .maxTokens
        && r.stop != .refusal
}
check("source: isEmptyResponse matches the port (no reasoning exemption)",
      vmSrc.contains("return r.assistantText.isEmpty && r.toolEntries.isEmpty\n                    && !r.isStreamInterrupted\n                    && r.stopReason != .maxTokens"))
// Entries are attached DURING the stream (on .toolCallComplete), and every
// provider emits toolCallComplete before its terminal .done, so at the point
// isEmptyResponse runs a real tool call is always already in `toolEntries`.
// The "entries not yet attached" rationale therefore does not apply.
check("source: toolEntries are appended inside processStreamEvents",
      sseSrc.contains("result.toolEntries.append(StreamResult.ToolEntry(id: id, name: name, args: args"))
check("source: the loop ends the run on empty toolEntries (no tool follows)",
      vmSrc.contains("guard !toolEntries.isEmpty else {\n                logger.info(\"Agent loop ending — no tool calls."))
check("toolUse stop, no entries, no reasoning → empty (retry path)",
      isEmptyResponse(R(stop: .toolUse)))
// Was a bug: finish_reason "tool_calls" whose deltas were all dropped (e.g. a
// proxy omitting `index`) plus some reasoning was classified as a SUCCESS; the
// loop saw no tools and the run ended with nothing visible.
check("toolUse stop, no entries, WITH reasoning → empty too (same stall)",
      isEmptyResponse(R(reasoning: "picking a tool", stop: .toolUse)))
check("regression guard: interleaved thinking with a real call is NOT empty",
      isEmptyResponse(R(entries: 1, reasoning: "read it", stop: .toolUse)), false)

// MARK: - [3] Shell timeout partial output keeps the tail

print("\n▶️  3. timed-out shell output keeps the TAIL (3092a9c4b)")
let kMax = 256_000
check("source: mirror evicts the OLDEST lines once over the cap",
      coordSrc.contains("while partialChars > Self.kMaxPartialOutputChars,\n                      partialLines.count - partialHead > 1 {"))
check("source: snapshot reads from the head index",
      coordSrc.contains("partialLines[partialHead...].joined(separator:"))
/// Port of recordPartial / partialSnapshot.
final class Mirror {
    var store: [String] = []; var head = 0; var chars = 0; var truncated = false
    var lines: [String] { Array(store[head...]) }
    func record(_ line: String) {
        store.append(line); chars += line.count + 1
        while chars > kMax, store.count - head > 1 {
            chars -= store[head].count + 1; head += 1; truncated = true
        }
        if head > 4096, head * 2 > store.count { store.removeFirst(head); head = 0 }
    }
}
do {
    let m = Mirror()
    for i in 0..<5_000 { m.record("progress line \(i) " + String(repeating: "x", count: 80)) }
    m.record("FATAL: linker error in libfoo.a — hanging on retry")
    let text = m.lines.joined(separator: "\n")
    check("overflow was recorded", m.truncated)
    check("the last line before the hang survives the cap", text.contains("FATAL: linker error"))
    check("the oldest line was the one evicted", !text.contains("progress line 0 "))
    check("kept text stays within the cap", text.count <= kMax)
    check("kept lines are contiguous and end at the newest",
          m.lines.last == "FATAL: linker error in libfoo.a — hanging on retry")
}
do {
    // Edge: one line larger than the whole cap must still be kept (never an
    // empty snapshot for a command that did print).
    let m = Mirror()
    m.record("a"); m.record(String(repeating: "y", count: kMax + 10))
    check("a single oversized line is kept on its own", m.lines.count == 1 && m.lines[0].count == kMax + 10)
    check("…and the drop is reported", m.truncated)
}
do {
    // Edge: output under the cap is untouched and not flagged.
    let m = Mirror()
    for i in 0..<10 { m.record("l\(i)") }
    check("small output is kept whole and not flagged", m.lines.count == 10 && !m.truncated)
}
check("notice says the EARLIER output was dropped and sits above the kept text",
      coordSrc.contains("? \"[Earlier output beyond the last \\(Self.kMaxPartialOutputChars) chars was dropped]\\n\\n\" + snapshot.text"))

// MARK: - [4] Responses pairing sanitize — known gaps

print("\n▶️  4. sanitizeResponsesToolPairing gaps (bc142f750)")
let sanitizeFn = slice(openaiSrc, from: "static func sanitizeResponsesToolPairing(", to: "\n        flushPlaceholders()\n        return out\n    }")
check("sanitize located", !sanitizeFn.isEmpty)
check("source: pairing is SET based (no position check)",
      sanitizeFn.contains("let orphanedOutputs = answeredIds.subtracting(callIds)"))
check("source: duplicate outputs for one call_id are dropped after the first",
      sanitizeFn.contains("if let id = callId(item, \"function_call_output\"), !emittedOutputIds.insert(id).inserted {"))
/// Port of the set logic + first-output-wins dedupe (placeholders omitted).
func sanitize(_ items: [[String: String]]) -> [[String: String]] {
    var calls = Set<String>(), answered = Set<String>()
    for i in items {
        if i["type"] == "function_call", let c = i["call_id"] { calls.insert(c) }
        if i["type"] == "function_call_output", let c = i["call_id"] { answered.insert(c) }
    }
    let orphanOut = answered.subtracting(calls)
    var emitted = Set<String>()
    return items.filter {
        guard $0["type"] == "function_call_output", let c = $0["call_id"] else { return true }
        if orphanOut.contains(c) { return false }
        return emitted.insert(c).inserted
    }
}
do {
    // The doc comment promises "a function_call with the same call_id EARLIER
    // in the array"; an output that precedes its call passes untouched.
    let outOfOrder: [[String: String]] = [
        ["type": "function_call_output", "call_id": "call_A"],
        ["type": "function_call", "call_id": "call_A"],
    ]
    let s = sanitize(outOfOrder)
    warn("an output BEFORE its call reaches the wire (doc says 'EARLIER')",
         s.first?["type"] == "function_call_output")
    // [T-responses-dedupe-tool-output] Like Chat Completions, keep the first.
    let dup: [[String: String]] = [
        ["type": "function_call", "call_id": "call_A"],
        ["type": "function_call_output", "call_id": "call_A", "output": "first"],
        ["type": "function_call_output", "call_id": "call_A", "output": "second"],
    ]
    let deduped = sanitize(dup).filter { $0["type"] == "function_call_output" }
    check("duplicate outputs collapse to one", deduped.count == 1)
    check("…and it is the FIRST one", deduped.first?["output"] == "first")
    let parallel: [[String: String]] = [
        ["type": "function_call", "call_id": "call_A"],
        ["type": "function_call", "call_id": "call_B"],
        ["type": "function_call_output", "call_id": "call_A"],
        ["type": "function_call_output", "call_id": "call_B"],
    ]
    check("distinct parallel outputs are untouched", sanitize(parallel).count == 4)
}

// MARK: - [5] Reasoning echo replayed across upstreams keeps a foreign id

print("\n▶️  5. cross-upstream reasoning echo still carries the minted id (0250ba32b)")
let echoBlock = slice(openaiSrc, from: "for item in echo.items {", to: "result.append(entry)")
check("echo replay block located", !echoBlock.isEmpty)
let idAlwaysSent = echoBlock.contains("\"id\": id,") && !echoBlock.contains("if sameUpstream {\n                            entry[\"id\"]")
// 0250ba32b measured "well-formed but foreign id → 404" on store:false. Since
// echoes are now PERSISTED, any session that moves from Responses upstream A to
// Responses upstream B (group fallback, provider switch) replays A's rs_ id to
// B on every request. Suggested fix: emit the id only when sameUpstream,
// otherwise the id-less head (measured 200).
warn("a cross-upstream hop replays upstream A's rs_ id to upstream B", idAlwaysSent)

// MARK: - [6] pairingKey vs Chat Completions wire ids

print("\n▶️  6. history pairing (pairingKey) vs Chat Completions wire (raw id) (bc142f750)")
check("source: dropOrphanedToolParts pairs on pairingKey",
      persistSrc.contains("case .toolUse(let id, _, _, _): toolUseIds.insert(Self.pairingKey(id))"))
let chatUsesRaw = openaiSrc.contains("\"tool_call_id\": Self.capChatId(id),")
    && !openaiSrc.contains("capChatId(Self.splitResponsesAPIIds")
// A mixed history ("call_x|fc_y" use, "call_x" result) is now "paired" in the
// history layer, but the Chat Completions wire compares the raw capped ids, so
// sanitizeToolCallAdjacency drops the real result and injects "unavailable".
warn("mixed-form ids pass the history layer but split apart on Chat Completions", chatUsesRaw)

// MARK: - [7] Claude CLI mimicry header parity

print("\n▶️  7. Claude CLI mimicry headers identical on iOS and Android (9b6a9910c)")
func pairs(_ text: String, pattern: String) -> [String: String] {
    guard let re = try? NSRegularExpression(pattern: pattern) else { return [:] }
    var out: [String: String] = [:]
    let ns = text as NSString
    for m in re.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
        out[ns.substring(with: m.range(at: 1))] = ns.substring(with: m.range(at: 2))
    }
    return out
}
let iosBlock = slice(oauthSrc, from: "enum ClaudeCLIMimicry {", to: "]\n")
let androidBlock = slice(androidMimicrySrc, from: "val ALL: List<Pair<String, String>> = listOf(", to: ")\n")
let iosHeaders = pairs(iosBlock, pattern: #""([A-Za-z-]+)":\s*"([^"]*)""#)
let androidHeaders = pairs(androidBlock, pattern: #""([A-Za-z-]+)"\s+to\s+"([^"]*)""#)
check("iOS header set parsed (11)", iosHeaders.count == 11)
check("Android header set parsed (11)", androidHeaders.count == 11)
check("same header names", Set(iosHeaders.keys) == Set(androidHeaders.keys))
for k in iosHeaders.keys.sorted() where iosHeaders[k] != androidHeaders[k] {
    check("\(k): iOS=\(iosHeaders[k] ?? "nil") Android=\(androidHeaders[k] ?? "nil")", false)
}
check("User-Agent identical (model floors are version-gated on it)",
      iosHeaders["User-Agent"] != nil && iosHeaders["User-Agent"] == androidHeaders["User-Agent"])

print("")
print("⚠️  \(warnings) known gap(s) documented")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)"); exit(1) }
