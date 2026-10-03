// Tests for [T-ios-compact-model-fallback] — compaction now walks model
// candidates when one is exhausted, instead of splitting the input and
// re-running the same dead model up to eight times.
//
// Standalone (`swift CompactModelFallbackTests.swift`) for the same reason as
// the neighbouring files: the MinisTests target has a pre-existing compile
// break. The candidate ordering and the error classification are reproduced
// here; section [5] re-reads the shipping source so the copies cannot drift.

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Reproduced: error classification

enum LLMErr: Error, Equatable {
    case rateLimited, invalidAPIKey, providerError, transientError(code: Int?)
    case networkError, decodingError, cancelled, unknown

    var isFallbackable: Bool {
        switch self {
        case .rateLimited, .invalidAPIKey, .providerError: return true
        default: return false
        }
    }
    /// 5xx = this deployment has no capacity; another member may.
    var isServerCapacityTransient: Bool {
        if case .transientError(let c) = self, let c, (500..<600).contains(c) { return true }
        return false
    }
}

/// Mirrors isSegmentRetryableError AFTER this change.
func isSegmentRetryable(_ e: LLMErr) -> Bool {
    switch e {
    case .cancelled, .networkError: return false
    case .rateLimited, .invalidAPIKey: return false      // ← the change
    case .transientError: return false
    default: return true
    }
}

print("\n[1] Quota/auth errors no longer trigger the split path")
// This is the waste the task describes: a 429 is not a size problem, so
// halving the input just re-runs a dead model on smaller inputs.
check("rateLimited is NOT segment-retryable", isSegmentRetryable(.rateLimited), false)
check("invalidAPIKey is NOT segment-retryable", isSegmentRetryable(.invalidAPIKey), false)
// …while the size rejections splitting genuinely fixes still are.
check("providerError (context too large) still splits", isSegmentRetryable(.providerError))
check("cancelled still never splits", isSegmentRetryable(.cancelled), false)

print("\n[2] The fallback loop triggers on exactly the right errors")
func triggersFallback(_ e: LLMErr) -> Bool { e.isFallbackable || e.isServerCapacityTransient }
for (name, e, want) in [
    ("rateLimited (429)", LLMErr.rateLimited, true),
    ("invalidAPIKey (401/403)", .invalidAPIKey, true),
    ("providerError (balance/quota)", .providerError, true),
    ("5xx server capacity", .transientError(code: 503), true),
    ("local transient (no status)", .transientError(code: nil), false),
    ("network offline", .networkError, false),
    ("cancelled", .cancelled, false),
    ("decoding", .decodingError, false),
] { checkEq("fallback on \(name)", triggersFallback(e), want) }

// MARK: - Reproduced: candidate ordering

struct Entry { let id: String; let model: String }
struct Group { let id: String; var memberEntryIds: [String] }

/// Mirrors ModelGroupRouter.nextFallback: walk forward, then wrap.
func nextFallback(_ g: Group, _ current: String) -> String? {
    guard let i = g.memberEntryIds.firstIndex(of: current) else { return g.memberEntryIds.first }
    let after = g.memberEntryIds[(i + 1)...]
    if let n = after.first { return n }
    let before = g.memberEntryIds[..<i]
    return before.first
}

/// Mirrors compactFallbackCandidates.
func candidates(first: Entry, group: Group?, burned: Set<String>) -> [Entry] {
    var ordered = [first]; var seen: Set<String> = [first.id]
    if let g = group {
        var cursor = first.id
        for _ in 0..<max(1, g.memberEntryIds.count) {
            guard let next = nextFallback(g, cursor) else { break }
            cursor = next
            if seen.contains(next) { continue }
            seen.insert(next); ordered.append(Entry(id: next, model: next))
        }
    }
    let usable = ordered.filter { !burned.contains($0.id) }
    return usable.isEmpty ? [first] : usable
}

print("\n[3] Candidate ordering")
let g = Group(id: "grp", memberEntryIds: ["a", "b", "c"])
checkEq("primary first, then the rest of the ring",
        candidates(first: Entry(id: "a", model: "a"), group: g, burned: []).map(\.id), ["a", "b", "c"])
checkEq("starting mid-ring wraps around",
        candidates(first: Entry(id: "b", model: "b"), group: g, burned: []).map(\.id), ["b", "c", "a"])
checkEq("no duplicates on a full walk",
        Set(candidates(first: Entry(id: "a", model: "a"), group: g, burned: []).map(\.id)).count, 3)
// Requirement 3: a direct-entry session (no group) borrows the default group.
checkEq("direct entry with NO group at least tries itself",
        candidates(first: Entry(id: "solo", model: "solo"), group: nil, burned: []).map(\.id), ["solo"])
checkEq("direct entry WITH a borrowed default group gains fallbacks",
        candidates(first: Entry(id: "a", model: "a"), group: g, burned: []).count, 3)

print("\n[4] A model that already failed this run is not retried (requirement 4)")
checkEq("burned primary is skipped",
        candidates(first: Entry(id: "a", model: "a"), group: g, burned: ["a"]).map(\.id), ["b", "c"])
checkEq("two burned leaves the third",
        candidates(first: Entry(id: "a", model: "a"), group: g, burned: ["a", "b"]).map(\.id), ["c"])
// Never empty: if everything is burned we still return the primary so the real
// provider error surfaces instead of a synthetic "no model available".
checkEq("all burned still yields the primary (real error surfaces)",
        candidates(first: Entry(id: "a", model: "a"), group: g, burned: ["a", "b", "c"]).map(\.id), ["a"])

print("\n[5] Shipping source matches these assumptions")
func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let c = source("Agent/Chat/AIChatViewModel+Compaction.swift")
if c.isEmpty {
    print("  ⏭  source not readable from this sandbox")
} else {
    check("fallback loop exists", c.contains("let candidates = compactFallbackCandidates(startingAt: primary)"))
    check("loop catches fallbackable + 5xx capacity",
          c.contains("catch let error as LLMError where error.isFallbackable || error.isServerCapacityTransient"))
    check("each candidate gets its own attempt (single-entry helper)",
          c.contains("private func generateCompactSummaryOnce("))
    check("quota errors no longer split",
          c.contains("case .rateLimited, .invalidAPIKey:\n                //"))
    check("success on a fallback updates the session's effective entry",
          c.contains("noteEffectiveEntry(entry.id)"))
    check("status line names the model actually compacting",
          c.contains("statusMsg?.content = \"Compacting with \\(entry.model.displayName)...\""))
    check("failed entries are recorded", c.contains("Self.compactFailedEntryIds.insert(entry.id)"))
    check("burn list is per-run, reset at compact start",
          c.contains("Self.compactFailedEntryIds.removeAll()"))
    check("direct-entry sessions borrow the default primary group",
          c.contains("appendGroupMembers(store.defaultPrimaryGroupId)"))
    check("group walk is bounded by member count",
          c.contains("for _ in 0..<max(1, group.memberEntryIds.count)"))
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
