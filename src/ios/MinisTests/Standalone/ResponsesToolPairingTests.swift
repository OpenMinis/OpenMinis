#!/usr/bin/env swift
// [T-responses-orphan-tool-output, T-responses-tool-id-normalize]
//
// Field report (2026-09): a multi-turn session started answering
//     {"error":{"message":"No tool call found for function call output with
//      call_id call_M1ate3tSzXCh3c1lr8QCsild.","type":"invalid_request_error"}}
// and, because the request array is rebuilt deterministically from the same
// history, it repeated on every retry and every fallback model — the session
// was unrecoverable without clearing it.
//
// Two defects, pinned separately below.
//
// [1] The Responses builder had NO pairing pass. Chat Completions has run
//     `sanitizeToolCallAdjacency` over its flattened array forever;
//     `convertMessagesResponsesAPI` emitted `function_call_output`
//     UNCONDITIONALLY and returned. The gap was even noted in that file's own
//     comment ("the Responses path has no such pass") without being closed, so
//     every upstream mechanism that can strand a tool result — ChatStore's
//     pruneOldMessages, per-record iCloud merges, legacy-marker index slices —
//     reached the wire unchecked.
//
// [2] The layer above (`dropOrphanedToolParts`) and the wire layer used
//     DIFFERENT notions of identity. Responses carries a combined id
//     "<call_id>|<fc_id>"; the wire splits it and matches on the call_id half
//     alone, while the repair pass compared raw ids. A history mixing the two
//     forms looked paired upstream and came apart on the wire.
//
// Both ports below are verbatim apart from the logger. Section [5] re-reads
// the shipping sources so a rewrite fails here rather than passing stale.
//
// Run: swift ResponsesToolPairingTests.swift
//
// Convention: a bare `swift` script — `deps/libs/libish_emu.a` is device-arm64
// only, so the app cannot link for the simulator.
import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(label)") }
    else { print("  ❌ \(label)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
func codeOnly(_ src: String) -> String {
    src.components(separatedBy: "\n")
        .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
        .joined(separator: "\n")
}

final class LogSpy {
    var warnings: [String] = []
    func warning(_ m: String) { warnings.append(m) }
}

// MARK: - Verbatim port: OpenAIAgentProvider.sanitizeResponsesToolPairing

func sanitizeResponsesToolPairing(_ items: [[String: Any]], logger: LogSpy) -> [[String: Any]] {
    func callId(_ item: [String: Any], _ type: String) -> String? {
        guard (item["type"] as? String) == type else { return nil }
        return item["call_id"] as? String
    }

    var callIds: Set<String> = []
    var answeredIds: Set<String> = []
    for item in items {
        if let id = callId(item, "function_call") { callIds.insert(id) }
        if let id = callId(item, "function_call_output") { answeredIds.insert(id) }
    }

    var trailingCallIds: Set<String> = []
    for item in items.reversed() {
        let type = item["type"] as? String
        if type == "function_call" {
            if let id = item["call_id"] as? String { trailingCallIds.insert(id) }
            continue
        }
        if type == "reasoning" { continue }
        break
    }

    let orphanedOutputs = answeredIds.subtracting(callIds)
    let unansweredCalls = callIds.subtracting(answeredIds).subtracting(trailingCallIds)
    guard !orphanedOutputs.isEmpty || !unansweredCalls.isEmpty else { return items }

    logger.warning("[sanitize-responses] orphan tool items in OUTGOING request — repairing. orphanedOutputs=\(orphanedOutputs.count) unansweredCalls=\(unansweredCalls.count) itemCount=\(items.count)")

    var out: [[String: Any]] = []
    out.reserveCapacity(items.count)
    var pendingPlaceholders: [[String: Any]] = []
    func flushPlaceholders() {
        guard !pendingPlaceholders.isEmpty else { return }
        out.append(contentsOf: pendingPlaceholders)
        pendingPlaceholders.removeAll()
    }

    for item in items {
        if let id = callId(item, "function_call_output"), orphanedOutputs.contains(id) {
            continue
        }
        if (item["type"] as? String) != "function_call" { flushPlaceholders() }
        out.append(item)
        if let id = callId(item, "function_call"), unansweredCalls.contains(id) {
            let name = (item["name"] as? String) ?? "unknown"
            logger.warning("[sanitize-responses] injecting placeholder output for orphan function_call id=\(id) name=\(name)")
            pendingPlaceholders.append([
                "type": "function_call_output",
                "call_id": id,
                "output": "Tool execution result is unavailable (history was truncated or interrupted).",
            ])
        }
    }
    flushPlaceholders()
    return out
}

/// The OLD behaviour — no pass at all — kept only to prove the tests bite.
func noSanitize(_ items: [[String: Any]]) -> [[String: Any]] { items }

// MARK: - Verbatim port: AIChatViewModel.pairingKey

func pairingKey(_ id: String) -> String {
    guard let pipe = id.firstIndex(of: "|") else { return id }
    return String(id[id.startIndex..<pipe])
}

// MARK: - Helpers

func fc(_ id: String, _ name: String = "bash") -> [String: Any] {
    ["type": "function_call", "call_id": id, "name": name, "arguments": "{}"]
}
func out(_ id: String) -> [String: Any] {
    ["type": "function_call_output", "call_id": id, "output": "ok"]
}
func userMsg(_ t: String = "hi") -> [String: Any] { ["role": "user", "content": t] }
func reasoning(_ id: String) -> [String: Any] {
    ["type": "reasoning", "id": id, "summary": [] as [[String: Any]]]
}
func imageCarrier() -> [String: Any] {
    ["role": "user", "content": [["type": "input_image", "image_url": "data:image/png;base64,AA"]]]
}
/// Compact shape for assertions.
func shape(_ items: [[String: Any]]) -> [String] {
    items.map { i in
        let t = (i["type"] as? String) ?? (i["role"] as? String) ?? "?"
        if t == "function_call" || t == "function_call_output" {
            return "\(t)(\((i["call_id"] as? String) ?? "?"))"
        }
        if t == "reasoning" { return "reasoning" }
        if i["role"] as? String == "user", i["content"] is [[String: Any]] { return "user(image)" }
        return t
    }
}
/// Every function_call_output must sit in ONE contiguous block.
func outputRunContiguous(_ items: [[String: Any]]) -> Bool {
    let flags = items.map { ($0["type"] as? String) == "function_call_output" }
    guard let first = flags.firstIndex(of: true) else { return true }
    guard let last = flags.lastIndex(of: true) else { return true }
    return !flags[first...last].contains(false)
}

print("▶️  1. the reported bug: an output whose call is gone")
do {
    // Exactly the shape the field report produced: compaction dropped the
    // assistant turn, the tool result survived.
    let wire = [userMsg(), out("call_M1ate3tSzXCh3c1lr8QCsild"), userMsg("next")]
    let spy = LogSpy()
    let fixed = sanitizeResponsesToolPairing(wire, logger: spy)

    checkEq("the orphan output is dropped", shape(fixed), ["user", "user"])
    check("…and it is logged, not silent",
          spy.warnings.contains { $0.contains("orphanedOutputs=1") })

    // Non-vacuous: without the pass the orphan goes straight to the wire,
    // which is what produced the 400.
    check("PRE-FIX the orphan reached the wire",
          shape(noSanitize(wire)).contains("function_call_output(call_M1ate3tSzXCh3c1lr8QCsild)"))
}

print("\n▶️  2. a call with no output gets a placeholder, not deletion")
do {
    // Deleting the call would silently discard the assistant's own turn.
    let wire = [userMsg(), fc("call_a"), userMsg("later")]
    let spy = LogSpy()
    let fixed = sanitizeResponsesToolPairing(wire, logger: spy)

    checkEq("the call survives and is answered",
            shape(fixed), ["user", "function_call(call_a)", "function_call_output(call_a)", "user"])
    check("the placeholder says the result is unavailable",
          (fixed[2]["output"] as? String)?.contains("unavailable") == true)
    check("…and it is logged", spy.warnings.contains { $0.contains("injecting placeholder") })
}

print("\n▶️  3. a TRAILING call is exempt — the round is still in flight")
do {
    // A request ending on function_call is exactly what the API expects between
    // "model asked" and "results appended". Fabricating a failure here would
    // tell the model a tool it is about to run has already failed.
    let wire = [userMsg(), reasoning("rs_1"), fc("call_a"), fc("call_b")]
    let spy = LogSpy()
    let fixed = sanitizeResponsesToolPairing(wire, logger: spy)

    checkEq("nothing is added", shape(fixed), shape(wire))
    check("…and nothing is logged", spy.warnings.isEmpty)
}

print("\n▶️  4. parallel calls: the output run stays contiguous")
do {
    // The load-bearing invariant. [T-openai-tool-result-image] buffers image
    // carriers precisely so the run of function_call_output items is never
    // split; a placeholder emitted INLINE after its own call would split the
    // function_call run instead, which is the same defect on the other side.
    let wire = [userMsg(), reasoning("rs_1"),
                fc("call_01"), fc("call_02"), fc("call_03"),
                out("call_01"), out("call_03"), imageCarrier(), userMsg("next")]
    let spy = LogSpy()
    let fixed = sanitizeResponsesToolPairing(wire, logger: spy)

    // call_02 is unanswered and NOT trailing (outputs follow), so it is filled.
    checkEq("shape", shape(fixed),
            ["user", "reasoning",
             "function_call(call_01)", "function_call(call_02)", "function_call(call_03)",
             "function_call_output(call_02)",
             "function_call_output(call_01)", "function_call_output(call_03)",
             "user(image)", "user"])
    check("the function_call run was NOT split", outputRunContiguous(fixed))
    check("every call is answered exactly once",
          Set(fixed.compactMap { $0["type"] as? String == "function_call_output"
                                 ? $0["call_id"] as? String : nil })
          == Set(["call_01", "call_02", "call_03"]))

    // Prove the inline form — the shape I wrote first — really would split it.
    var inline: [[String: Any]] = []
    for item in wire {
        inline.append(item)
        if (item["type"] as? String) == "function_call",
           (item["call_id"] as? String) == "call_02" { inline.append(out("call_02")) }
    }
    check("PRE-FIX an inline placeholder WOULD split the call run",
          shape(inline)[3] == "function_call(call_02)" && shape(inline)[4] == "function_call_output(call_02)")
}

print("\n▶️  5. a healthy conversation is untouched")
do {
    // The guard must be a no-op on the overwhelmingly common case, or it is a
    // cache-breaking rewrite of every request.
    let wire = [userMsg(), reasoning("rs_1"), fc("call_01"), fc("call_02"),
                out("call_01"), out("call_02"), userMsg("next")]
    let spy = LogSpy()
    let fixed = sanitizeResponsesToolPairing(wire, logger: spy)
    checkEq("identical shape", shape(fixed), shape(wire))
    checkEq("identical count", fixed.count, wire.count)
    check("no warning logged", spy.warnings.isEmpty)
}

print("\n▶️  6. the two layers agree on identity (combined vs bare id)")
do {
    // Responses carries "<call_id>|<fc_id>"; the wire matches on call_id alone.
    checkEq("a combined id normalizes to its call_id",
            pairingKey("call_abc|fc_123"), "call_abc")
    checkEq("a bare id is unchanged", pairingKey("call_abc"), "call_abc")
    checkEq("toolu_ (Anthropic) is unchanged", pairingKey("toolu_01X"), "toolu_01X")
    checkEq("an empty id is unchanged", pairingKey(""), "")
    // Degenerate forms must not crash or produce surprises.
    checkEq("a leading pipe yields empty", pairingKey("|fc_1"), "")
    checkEq("only the FIRST pipe splits", pairingKey("call_a|fc_1|x"), "call_a")

    // The actual defect: a use/result pair recorded in the two different forms.
    let useId = "call_M1ate3tSzXCh3c1lr8QCsild|fc_0a1b2c3d"
    let resultId = "call_M1ate3tSzXCh3c1lr8QCsild"
    check("PRE-FIX raw comparison called this an orphan", useId != resultId)
    check("POST-FIX they pair", pairingKey(useId) == pairingKey(resultId))
}

print("\n▶️  7. shipping sources still carry both fixes")
do {
    let oai = codeOnly(source("Providers/OpenAI/OpenAIAgentProvider.swift"))
    let persist = codeOnly(source("Agent/Chat/AIChatViewModel+Persistence.swift"))
    let vm = codeOnly(source("Agent/Chat/AIChatViewModel.swift"))
    if oai.isEmpty || persist.isEmpty || vm.isEmpty {
        print("  ⏭  sources not readable")
    } else {
        check("the Responses sanitize pass ships",
              oai.contains("static func sanitizeResponsesToolPairing("))
        // Must assert it is CALLED, not merely defined — an earlier guard in
        // this repo passed while a helper's call site was disabled.
        check("…and convertMessagesResponsesAPI actually calls it",
              oai.contains("return Self.sanitizeResponsesToolPairing(result, logger: logger)"))
        check("…and the bare `return result` is gone from that builder",
              !oai.contains("        flushImageCarriers()\n        return result\n    }"))
        check("placeholders are buffered, not emitted inline",
              oai.contains("var pendingPlaceholders: [[String: Any]] = []")
              && oai.contains("if (item[\"type\"] as? String) != \"function_call\" { flushPlaceholders() }"))
        check("the trailing call run is exempt", oai.contains("var trailingCallIds: Set<String> = []"))

        check("the pairing key helper ships",
              persist.contains("static func pairingKey(_ id: String) -> String {"))
        check("…and dropOrphanedToolParts uses it on BOTH sides",
              persist.contains("case .toolUse(let id, _, _, _): toolUseIds.insert(Self.pairingKey(id))")
              && persist.contains("toolResultIds.insert(Self.pairingKey(id))"))
        check("…including the filter and the in-flight exemption",
              persist.contains("return !orphanedResults.contains(Self.pairingKey(id))")
              && persist.contains("orphanedUses.remove(Self.pairingKey(id))"))
        // The runAgentLoop startup sweep is a SECOND pairing pass over the full
        // history; leaving it on raw ids could delete a correctly paired result.
        check("the runAgentLoop sweep normalizes too",
              vm.contains("allToolUseIds.insert(Self.pairingKey(id))")
              && vm.contains("if !allToolUseIds.contains(Self.pairingKey(id)) {"))
        check("…on its second half as well",
              vm.contains("allToolResultIds.insert(Self.pairingKey(id))")
              && vm.contains("!allToolResultIds.contains(Self.pairingKey(id))"))
    }
}

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)") }
exit(failures == 0 ? 0 : 1)
