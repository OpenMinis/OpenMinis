// Tests for [T-compact-last-resort-removed] — backlog item T14, iOS half.
//
// Rollback guard. The "every fallback candidate failed → force a compaction
// and lap the group again" layer (df0b71ed7 / c1e5664a7 / e52014156 /
// 9ad0c84b4, issue #133) was narrowed three times and then removed by product
// decision: a dead proxy is not a size problem, and rewriting the user's
// history behind a network blip destroyed context to fix nothing.
//
// This script asserts, from the shipping sources, that the removed symbols
// have not come back and that `groupExhaustedError` only assembles the
// error trail — it never compacts, never touches history.
//
// Port: groupExhaustedError — src/ios/Agent/Chat/AIChatViewModel+Fallback.swift ~L125.
//
// Standalone (`swift ExhaustedCompactionAbsenceTests.swift`) like its neighbours.
import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}
let iosRoot = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
func source(_ rel: String) -> String {
    (try? String(contentsOf: iosRoot.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
/// Every production Swift file under src/ios (tests excluded), as (relPath, text).
func productionSources() -> [(String, String)] {
    var out: [(String, String)] = []
    guard let e = FileManager.default.enumerator(at: iosRoot, includingPropertiesForKeys: nil) else { return out }
    for case let url as URL in e {
        guard url.pathExtension == "swift" else { continue }
        let rel = url.path.replacingOccurrences(of: iosRoot.path + "/", with: "")
        if rel.hasPrefix("MinisTests/") || rel.contains("/.build/") { continue }
        if let s = try? String(contentsOf: url, encoding: .utf8) { out.append((rel, s)) }
    }
    return out
}

// MARK: - Port of groupExhaustedError

enum LLMError: Error, Equatable { case providerError(message: String), rateLimited }
struct FinalError: LocalizedError { var errorDescription: String? { "Error" } }

func groupExhaustedError(fallbackTrail: [(model: String, instance: String, reason: String)], finalError: Error) -> Error {
    guard !fallbackTrail.isEmpty else { return finalError }
    let trailLines = fallbackTrail.map { "⚠️ \($0.model) (\($0.instance)): \($0.reason)" }
    let finalDesc = (finalError as? LocalizedError)?.errorDescription ?? finalError.localizedDescription
    return LLMError.providerError(message: trailLines.joined(separator: "\n") + "\n" + finalDesc)
}

print("▶️  1. the removed symbols are gone from src/ios")
do {
    let removed = ["runExhaustedCompactionFallback", "compactionVetoReason", "CompactNegativeGuard", "CompactLastResort",
                   "ExhaustedCompact]", "forcing compaction (history="]
    let files = productionSources()
    check("scanned a realistic number of production files", files.count > 200)
    for sym in removed {
        let hits = files.filter { $0.1.contains(sym) }.map(\.0)
        checkEq("`\(sym)` absent", hits, [])
    }
    check("the deleted standalone test did not come back",
          !FileManager.default.fileExists(atPath: iosRoot.appendingPathComponent("MinisTests/Standalone/ExhaustedCompactionFallbackTests.swift").path))
}

print("▶️  2. groupExhaustedError only assembles the trail")
do {
    let fb = source("Agent/Chat/AIChatViewModel+Fallback.swift")
    if fb.isEmpty { print("  ⏭  source not readable") } else {
        // Extract the function body: from its declaration to the next "// MARK:".
        let start = fb.range(of: "func groupExhaustedError(")
        check("groupExhaustedError exists", start != nil)
        if let start {
            let rest = fb[start.upperBound...]
            let body = String(rest[..<(rest.range(of: "// MARK:")?.lowerBound ?? rest.endIndex)])
            check("returns a plain LLMError.providerError", body.contains("return LLMError.providerError(message:"))
            check("returns the final error untouched for an empty trail", body.contains("guard !fallbackTrail.isEmpty else { return finalError }"))
            for forbidden in ["compactBefore(", "compactAll(", "needsCompact", "effectiveAgentHistory", "agentHistory", "Task {", "await "] {
                check("body never touches `\(forbidden)`", !body.contains(forbidden))
            }
            check("the rollback rationale is recorded at the site", fb.contains("[T-compact-last-resort-removed]"))
        }
        checkEq("all three exhaustion branches throw it directly", fb.components(separatedBy: "throw groupExhaustedError(fallbackTrail: fallbackReasons, finalError: error)").count - 1, 3)
        check("no sentinel error asks the loop for another lap", !fb.contains("forcedCompaction") && !fb.contains("retryAfterCompaction"))
    }
}

print("▶️  3. all candidates failing → an error with the trail, history untouched")
do {
    struct Candidate { let model: String; let instance: String; let fails: String? }
    /// A model of streamWithGroupFallback's exhaustion branch: walk the
    /// candidates, collect reasons, throw the trail. It has no history to
    /// mutate because the real one has no such parameter either.
    func run(_ candidates: [Candidate], history: inout [String]) -> Result<String, Error> {
        var trail: [(model: String, instance: String, reason: String)] = []
        var lastError: Error = FinalError()
        for c in candidates {
            guard let reason = c.fails else { return .success(c.model) }
            trail.append((c.model, c.instance, reason))
            lastError = FinalError()
        }
        return .failure(groupExhaustedError(fallbackTrail: trail, finalError: lastError))
    }
    var history = ["u1", "a1", "u2", "a2", "u3"]
    let snapshot = history
    let r = run([Candidate(model: "mock-a", instance: "Mock", fails: "HTTP 500"),
                 Candidate(model: "mock-b", instance: "Mock", fails: "Error"),
                 Candidate(model: "mock-c-small", instance: "Mock", fails: "context length exceeded")], history: &history)
    guard case .failure(let err) = r, case LLMError.providerError(let msg) = err else {
        check("result is a providerError", false); exit(1)
    }
    check("trail names every candidate in order", msg.hasPrefix("⚠️ mock-a (Mock): HTTP 500\n⚠️ mock-b (Mock): Error\n⚠️ mock-c-small (Mock): context length exceeded"))
    check("the last model's own words close the message", msg.hasSuffix("\nError"))
    checkEq("history length unchanged", history.count, snapshot.count)
    checkEq("history content unchanged", history, snapshot)
    // Wording must not matter: "context length exceeded" gets the same treatment as "Error".
    let r2 = run([Candidate(model: "m", instance: "i", fails: "context length exceeded")], history: &history)
    check("a context-length wording does not change the outcome", { if case .failure = r2 { return true }; return false }())
    checkEq("still untouched", history, snapshot)
    check("an empty trail passes the final error through", groupExhaustedError(fallbackTrail: [], finalError: LLMError.rateLimited) as? LLMError == .rateLimited)
    // A healthy candidate later in the list still wins — exhaustion is only "all failed".
    let ok = run([Candidate(model: "dead", instance: "i", fails: "x"), Candidate(model: "alive", instance: "i", fails: nil)], history: &history)
    check("a surviving candidate is used", { if case .success("alive") = ok { return true }; return false }())
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
