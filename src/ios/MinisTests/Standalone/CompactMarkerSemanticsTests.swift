// Tests for [T-compact-marker-semantics] — backlog item T13.
//
// Compaction writes a MARKER and folds at read time, so several invariants
// are easy to get wrong in review and were each broken once:
//   * 9eb066f4b  "did it get shorter?" must be measured on
//                effectiveAgentHistory().count, not agentHistory.count;
//   * dc7d9f46a  the marker walk-back clamps to agentHistory's CURRENT end
//                (the array can shrink during the summary await);
//   * d2814131b / 8234c8bbf  an orphaned lcmId self-heals by createdAt to the
//                latest predecessor that is still in agentHistory;
//   * e8ac8b825  duplicating a session copies its markers with remapped ids;
//   * 249d41c45  segmented retry fires on ANY non-network error;
//   * 8b76cd747  the walk-back is bounded and never cuts a tool round.
// Issues #235, #275.
//
// Ports (from src/ios/Agent/Chat/AIChatViewModel+Compaction.swift unless noted):
//   walkBackUserTurnsBounded ~L409, anchorByCreatedAt ~L269,
//   rewriteMarkerForHeal ~L294, uiAnchorIndexForKeptTail ~L496,
//   the lcm clamp loop ~L866, isSegmentRetryableError ~L1183,
//   effectiveAgentHistoryUncounted v2 (AIChatViewModel+Persistence.swift ~L1967),
//   SessionForkManager marker copy (Agent/Session/SessionForkManager.swift).
//
// Standalone (`swift CompactMarkerSemanticsTests.swift`) like its neighbours.
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

enum Role: String { case user, assistant }
enum Part: Equatable {
    case text(String)
    case toolUse(id: String, name: String)
    case toolResult(id: String, content: String)
}
struct AgentMessage: Equatable {
    var role: Role
    var parts: [Part]
    var dbMessageId: String? = nil
}
struct RawMessage { let id: String; let sortOrder: Int; let createdAt: Date }
struct CompactMarker: Equatable {
    var id: String
    var sessionId: String
    var summary: String
    var firstKeptSortOrder: Int
    var compactedCount: Int
    var createdAt: Date
    var uiBoundarySortOrder: Int?
    var boundaryMessageId: String?
    var firstKeptMessageId: String?
    var lastCompactedMessageId: String?
    var version: Int
}
struct ChatMessage { let role: Role; let content: String }

func user(_ t: String, db: String? = nil) -> AgentMessage { AgentMessage(role: .user, parts: [.text(t)], dbMessageId: db) }
func assistant(_ t: String, db: String? = nil) -> AgentMessage { AgentMessage(role: .assistant, parts: [.text(t)], dbMessageId: db) }
func toolCall(_ id: String, db: String? = nil) -> AgentMessage { AgentMessage(role: .assistant, parts: [.toolUse(id: id, name: "shell")], dbMessageId: db) }
func toolReply(_ id: String, _ content: String = "ok", db: String? = nil) -> AgentMessage { AgentMessage(role: .user, parts: [.toolResult(id: id, content: content)], dbMessageId: db) }

// MARK: - Ports

struct WalkBackResult: Equatable {
    let priorIdx: Int?
    let userTextTurnsFound: Int
    let messageCount: Int
    let stopReason: String
}

func walkBackUserTurnsBounded(_ agentHistory: [AgentMessage], anchorIdx: Int, maxUserTextTurns: Int, maxMessages: Int) -> WalkBackResult {
    guard anchorIdx >= 0, anchorIdx < agentHistory.count else {
        return WalkBackResult(priorIdx: nil, userTextTurnsFound: 0, messageCount: 0, stopReason: "invalidAnchor")
    }
    var acceptedPriorIdx: Int? = nil
    var acceptedUserTextTurns = 0
    var acceptedMessageCount = 0
    for i in stride(from: anchorIdx, through: 0, by: -1) {
        let msg = agentHistory[i]
        guard msg.role == .user else { continue }
        let carriesToolResult = msg.parts.contains { if case .toolResult = $0 { return true }; return false }
        if carriesToolResult { continue }
        let candidateMessageCount = anchorIdx - i + 1
        if candidateMessageCount > maxMessages {
            return WalkBackResult(priorIdx: acceptedPriorIdx, userTextTurnsFound: acceptedUserTextTurns, messageCount: acceptedMessageCount, stopReason: "messageCapWouldExceed")
        }
        acceptedPriorIdx = i
        acceptedMessageCount = candidateMessageCount
        let hasText = msg.parts.contains { if case .text(let t) = $0, !t.isEmpty { return true }; return false }
        if hasText {
            acceptedUserTextTurns += 1
            if acceptedUserTextTurns >= maxUserTextTurns {
                return WalkBackResult(priorIdx: acceptedPriorIdx, userTextTurnsFound: acceptedUserTextTurns, messageCount: acceptedMessageCount, stopReason: "userTextTargetMet")
            }
        }
    }
    return WalkBackResult(priorIdx: acceptedPriorIdx, userTextTurnsFound: acceptedUserTextTurns, messageCount: acceptedMessageCount, stopReason: "reachedStart")
}

let compactKeepRecentUserTurns = 3
let summaryPrefix = "<context-summary>"

/// effectiveAgentHistoryUncounted, v2 branch only (the one every marker
/// written today takes). Degrades to the full history when the anchor cannot
/// be resolved — exactly as production does.
func effectiveAgentHistory(_ agentHistory: [AgentMessage], marker: CompactMarker?) -> [AgentMessage] {
    guard let marker, marker.version >= 2 else { return agentHistory }
    guard let anchorId = marker.lastCompactedMessageId,
          let anchorIdx = agentHistory.lastIndex(where: { $0.dbMessageId == anchorId }) else {
        return agentHistory
    }
    let walkBack = walkBackUserTurnsBounded(agentHistory, anchorIdx: anchorIdx, maxUserTextTurns: compactKeepRecentUserTurns, maxMessages: 100)
    let priorIdx = walkBack.priorIdx ?? (anchorIdx + 1)
    let summaryText = summaryPrefix + "\n" + marker.summary + "\n</context-summary>"
    let preAnchorRaw: [AgentMessage] = (priorIdx <= anchorIdx) ? Array(agentHistory[priorIdx...anchorIdx]) : []
    var droppedToolIds: Set<String> = []
    for msg in preAnchorRaw {
        for part in msg.parts {
            if case .toolResult(let id, let content) = part, content.count > 1000 { droppedToolIds.insert(id) }
        }
    }
    var preAnchorPruned: [AgentMessage] = []
    for var msg in preAnchorRaw {
        let kept = msg.parts.filter { part in
            switch part {
            case .toolUse(let id, _): return !droppedToolIds.contains(id)
            case .toolResult(let id, _): return !droppedToolIds.contains(id)
            default: return true
            }
        }
        if kept.isEmpty { continue }
        msg.parts = kept
        preAnchorPruned.append(msg)
    }
    while let first = preAnchorPruned.first, first.role != .user { preAnchorPruned.removeFirst() }
    var result: [AgentMessage] = preAnchorPruned
    let postAnchor = (anchorIdx + 1) < agentHistory.count ? Array(agentHistory[(anchorIdx + 1)...]) : []
    if let firstUserOffset = postAnchor.firstIndex(where: { $0.role == .user }) {
        if firstUserOffset > 0 { result.append(contentsOf: postAnchor[0..<firstUserOffset]) }
        var injected = postAnchor[firstUserOffset]
        injected.parts.insert(.text(summaryText), at: 0)
        result.append(injected)
        if firstUserOffset + 1 < postAnchor.count { result.append(contentsOf: postAnchor[(firstUserOffset + 1)...]) }
    } else {
        result.append(contentsOf: postAnchor)
        result.append(AgentMessage(role: .user, parts: [.text(summaryText)]))
    }
    return result
}

/// The lcm walk-back in compactBefore, post dc7d9f46a: clamp `endExclusive`
/// to the CURRENT count before indexing. Returns (lcmId, idx, clamped).
func resolveLcm(_ agentHistory: [AgentMessage], endExclusive: Int, allRawIds: Set<String>) -> (id: String?, idx: Int?, clamped: Bool) {
    var i = min(endExclusive, agentHistory.count) - 1
    let clamped = i != endExclusive - 1
    while i >= 0 {
        if let id = agentHistory[i].dbMessageId, allRawIds.contains(id) { return (id, i, clamped) }
        i -= 1
    }
    return (nil, nil, clamped)
}

func anchorByCreatedAt(in rawMessages: [RawMessage], markerCreatedAt: Date, historyDbIds: Set<String>) -> RawMessage? {
    rawMessages.last { raw in
        guard raw.createdAt < markerCreatedAt else { return false }
        return historyDbIds.isEmpty || historyDbIds.contains(raw.id)
    }
}

func rewriteMarkerForHeal(_ marker: CompactMarker, newAnchor: RawMessage, lastRaw: RawMessage?) -> CompactMarker {
    let pastEnd = (lastRaw?.sortOrder ?? 0) + 1
    return CompactMarker(id: marker.id, sessionId: marker.sessionId, summary: marker.summary,
                         firstKeptSortOrder: pastEnd, compactedCount: marker.compactedCount, createdAt: marker.createdAt,
                         uiBoundarySortOrder: pastEnd, boundaryMessageId: nil, firstKeptMessageId: nil,
                         lastCompactedMessageId: newAnchor.id, version: 2)
}

func uiAnchorIndexForKeptTail(in messages: [ChatMessage], keepUserTurns n: Int) -> Int {
    guard n > 0, !messages.isEmpty else { return 0 }
    var seen = 0
    for i in stride(from: messages.count - 1, through: 0, by: -1) {
        let m = messages[i]
        guard m.role == .user, !m.content.isEmpty else { continue }
        seen += 1
        if seen == n { return i }
    }
    return 0
}

/// SessionForkManager.duplicate — the marker copy loop.
func copyMarkers(_ markers: [CompactMarker], oldToNew: [String: String], newSortOrderById: [String: Int], newSessionId: String) -> [CompactMarker] {
    func remapId(_ id: String?) -> String? { guard let id else { return nil }; return oldToNew[id] }
    func remapSortOrder(messageId: String?, original: Int) -> Int {
        guard let newId = remapId(messageId), let so = newSortOrderById[newId] else { return original }
        return so
    }
    var out: [CompactMarker] = []
    for marker in markers {
        let firstKeptNew = remapId(marker.firstKeptMessageId)
        let lastCompactedNew = remapId(marker.lastCompactedMessageId)
        let boundaryNew = remapId(marker.boundaryMessageId)
        if (marker.firstKeptMessageId != nil && firstKeptNew == nil)
            || (marker.lastCompactedMessageId != nil && lastCompactedNew == nil)
            || (marker.boundaryMessageId != nil && boundaryNew == nil) { continue }
        out.append(CompactMarker(
            id: UUID().uuidString, sessionId: newSessionId, summary: marker.summary,
            firstKeptSortOrder: remapSortOrder(messageId: marker.firstKeptMessageId, original: marker.firstKeptSortOrder),
            compactedCount: marker.compactedCount, createdAt: marker.createdAt,
            uiBoundarySortOrder: marker.uiBoundarySortOrder.map { _ in remapSortOrder(messageId: marker.boundaryMessageId, original: marker.uiBoundarySortOrder ?? 0) },
            boundaryMessageId: boundaryNew, firstKeptMessageId: firstKeptNew, lastCompactedMessageId: lastCompactedNew,
            version: marker.version))
    }
    return out
}

/// Mirror of LLMError's cases that isSegmentRetryableError distinguishes.
enum LLMError: Error { case cancelled, networkError, rateLimited, invalidAPIKey, transientError, providerError(String), contextTooLong }
func isSegmentRetryableError(_ error: Error) -> Bool {
    if error is CancellationError { return false }
    if let llm = error as? LLMError {
        switch llm {
        case .cancelled, .networkError: return false
        case .rateLimited, .invalidAPIKey: return false
        case .transientError: return false
        default: return true
        }
    }
    let ns = error as NSError
    if ns.domain == NSURLErrorDomain { return false }
    return true
}

// MARK: - Fixtures

let t0 = Date(timeIntervalSince1970: 1_700_000_000)
func at(_ s: TimeInterval) -> Date { t0.addingTimeInterval(s) }

/// 10 rows: 3 user-text turns with a tool round in the middle.
let history: [AgentMessage] = [
    user("q1", db: "m0"), assistant("a1", db: "m1"),
    user("q2", db: "m2"), toolCall("c1", db: "m3"), toolReply("c1", db: "m4"), assistant("a2", db: "m5"),
    user("q3", db: "m6"), assistant("a3", db: "m7"),
    user("q4", db: "m8"), assistant("a4", db: "m9"),
]
let raws: [RawMessage] = (0..<10).map { RawMessage(id: "m\($0)", sortOrder: $0, createdAt: at(Double($0) * 10)) }

print("▶️  1. success is measured on the effective history, not the row count (9eb066f4b)")
do {
    let before = effectiveAgentHistory(history, marker: nil).count
    let marker = CompactMarker(id: "M1", sessionId: "s", summary: "sum", firstKeptSortOrder: 10, compactedCount: 10,
                               createdAt: at(200), uiBoundarySortOrder: 10, boundaryMessageId: nil, firstKeptMessageId: nil,
                               lastCompactedMessageId: "m9", version: 2)
    let after = effectiveAgentHistory(history, marker: marker)
    checkEq("agentHistory.count is UNCHANGED by a successful compaction", history.count, 10)
    check("…while the effective count shrinks", after.count < before)
    checkEq("kept tail = last 3 user-text turns leading into the anchor (+ standalone summary)", after.count, 9)
    check("the summary rides at the end when nothing follows the anchor",
          { if case .text(let t)? = after.last?.parts.first { return t.hasPrefix(summaryPrefix) }; return false }())
    // A guard written against the raw count would have refused the retry.
    check("raw-count guard would wrongly report 'did not shorten'", history.count == 10 && after.count != history.count)
    // With a message after the anchor, the summary is spliced INTO that user turn.
    let withNew = history + [user("q5", db: "m10")]
    let eff2 = effectiveAgentHistory(withNew, marker: marker)
    check("summary is prepended to the first post-anchor user turn",
          { if case .text(let t)? = eff2.last?.parts.first { return t.hasPrefix(summaryPrefix) }; return false }() && eff2.last?.parts.count == 2)
}

print("▶️  2. the marker walk-back clamps to the current end (dc7d9f46a)")
do {
    let ids = Set(raws.map(\.id))
    let normal = resolveLcm(history, endExclusive: 10, allRawIds: ids)
    checkEq("normal: anchor is the last row", normal.id, "m9")
    check("normal: no clamp", !normal.clamped)
    // The user deleted messages while the summary was being generated.
    let shrunk = Array(history.prefix(6))
    let clamped = resolveLcm(shrunk, endExclusive: 10, allRawIds: ids)
    check("shrunk history → clamped instead of trapping", clamped.clamped)
    checkEq("…and anchors on the last SURVIVING persisted row", clamped.id, "m5")
    let emptied = resolveLcm([], endExclusive: 10, allRawIds: ids)
    check("emptied history → nil (caller aborts cleanly)", emptied.id == nil && emptied.clamped)
    // An unpersisted tail walks back to the nearest persisted entry.
    var tail = history; tail[9].dbMessageId = nil
    checkEq("unpersisted last row → previous persisted one", resolveLcm(tail, endExclusive: 10, allRawIds: ids).id, "m8")
    checkEq("a row deleted from the DB is skipped", resolveLcm(history, endExclusive: 10, allRawIds: ids.subtracting(["m9"])).id, "m8")
}

print("▶️  3. an orphaned lcmId self-heals to the nearest legal boundary (d2814131b / 8234c8bbf)")
do {
    let orphan = CompactMarker(id: "M2", sessionId: "s", summary: "sum", firstKeptSortOrder: 0, compactedCount: 6,
                               createdAt: at(65), uiBoundarySortOrder: nil, boundaryMessageId: nil, firstKeptMessageId: nil,
                               lastCompactedMessageId: "GONE", version: 2)
    checkEq("unresolvable anchor degrades to full history (no summary-only request)", effectiveAgentHistory(history, marker: orphan).count, history.count)
    let historyIds = Set(history.compactMap(\.dbMessageId))
    let healedAnchor = anchorByCreatedAt(in: raws, markerCreatedAt: orphan.createdAt, historyDbIds: historyIds)
    checkEq("latest raw created before the marker", healedAnchor?.id, "m6")
    // The heal must land on a row agentHistory can resolve, or it is cosmetic.
    let partial = historyIds.subtracting(["m6"])
    checkEq("…skipping rows agentHistory no longer carries", anchorByCreatedAt(in: raws, markerCreatedAt: orphan.createdAt, historyDbIds: partial)?.id, "m5")
    check("no predecessor → nil", anchorByCreatedAt(in: raws, markerCreatedAt: at(-1), historyDbIds: historyIds) == nil)
    let healed = rewriteMarkerForHeal(orphan, newAnchor: healedAnchor!, lastRaw: raws.last)
    check("heal keeps identity (id/session/summary/createdAt/count)",
          healed.id == orphan.id && healed.sessionId == orphan.sessionId && healed.summary == orphan.summary
          && healed.createdAt == orphan.createdAt && healed.compactedCount == orphan.compactedCount)
    checkEq("heal points lcmId at the recomputed anchor", healed.lastCompactedMessageId, "m6")
    check("legacy fields are zeroed / past-the-end", healed.boundaryMessageId == nil && healed.firstKeptMessageId == nil && healed.firstKeptSortOrder == 10 && healed.version == 2)
    // The anchor sits early (m6, with three user-text turns before it), so
    // the fold is not SHORTER here — what proves the heal is that the summary
    // is now injected at all, where the orphaned marker produced none.
    let healedFold = effectiveAgentHistory(history, marker: healed)
    let hasSummary = healedFold.contains { $0.parts.contains { if case .text(let t) = $0 { return t.hasPrefix(summaryPrefix) }; return false } }
    check("the healed marker now resolves (summary injected)", hasSummary)
    check("…where the orphaned one injected nothing", !effectiveAgentHistory(history, marker: orphan).contains { $0.parts.contains { if case .text(let t) = $0 { return t.hasPrefix(summaryPrefix) }; return false } })
    // UI counterpart used when even the heal fails.
    let ui = [ChatMessage(role: .user, content: "q1"), ChatMessage(role: .assistant, content: "a"), ChatMessage(role: .user, content: ""),
              ChatMessage(role: .user, content: "q2"), ChatMessage(role: .assistant, content: "a"), ChatMessage(role: .user, content: "q3")]
    checkEq("UI kept-tail anchor skips empty (tool-only) user rows", uiAnchorIndexForKeptTail(in: ui, keepUserTurns: 2), 3)
    checkEq("fewer user rows than requested → 0 (no graying)", uiAnchorIndexForKeptTail(in: ui, keepUserTurns: 5), 0)
}

print("▶️  4. duplicating a session copies the marker with remapped ids (e8ac8b825)")
do {
    let oldToNew = Dictionary(uniqueKeysWithValues: raws.map { ($0.id, "n" + $0.id) })
    let newSort = Dictionary(uniqueKeysWithValues: raws.map { ("n" + $0.id, $0.sortOrder + 100) })
    let m = CompactMarker(id: "M3", sessionId: "old", summary: "S", firstKeptSortOrder: 10, compactedCount: 10, createdAt: at(200),
                          uiBoundarySortOrder: 7, boundaryMessageId: "m7", firstKeptMessageId: "m8", lastCompactedMessageId: "m7", version: 2)
    let copies = copyMarkers([m], oldToNew: oldToNew, newSortOrderById: newSort, newSessionId: "new")
    checkEq("one marker copied", copies.count, 1)
    let c = copies[0]
    check("fresh id, new session", c.id != m.id && c.sessionId == "new")
    checkEq("lcmId remapped", c.lastCompactedMessageId, "nm7")
    checkEq("firstKept remapped", c.firstKeptMessageId, "nm8")
    checkEq("boundary remapped", c.boundaryMessageId, "nm7")
    checkEq("legacy sort orders follow the new rows", c.firstKeptSortOrder, 108)
    checkEq("ui boundary follows the new rows", c.uiBoundarySortOrder, 107)
    check("summary / createdAt / count / version preserved", c.summary == "S" && c.createdAt == m.createdAt && c.compactedCount == 10 && c.version == 2)
    // Equivalence: the copy folds the duplicated history identically.
    let dup = history.map { var x = $0; x.dbMessageId = x.dbMessageId.map { "n" + $0 }; return x }
    checkEq("copy folds the duplicate exactly like the original", effectiveAgentHistory(dup, marker: c).count, effectiveAgentHistory(history, marker: m).count)
    var dangling = m; dangling.lastCompactedMessageId = "GONE"
    checkEq("a dangling source marker is skipped, not copied dangling", copyMarkers([dangling], oldToNew: oldToNew, newSortOrderById: newSort, newSessionId: "new").count, 0)
    var legacyNil = m; legacyNil.firstKeptMessageId = nil; legacyNil.boundaryMessageId = nil; legacyNil.uiBoundarySortOrder = nil
    let lc = copyMarkers([legacyNil], oldToNew: oldToNew, newSortOrderById: newSort, newSessionId: "new")
    check("nil id fields stay nil and keep their original sort order", lc.count == 1 && lc[0].firstKeptMessageId == nil && lc[0].firstKeptSortOrder == 10 && lc[0].uiBoundarySortOrder == nil)
}

print("▶️  5. segmented retry fires on any non-network error (249d41c45)")
do {
    check("provider rejection (context too long, any wording) → retry in segments", isSegmentRetryableError(LLMError.providerError("Error")))
    check("unclassified LLMError → retry", isSegmentRetryableError(LLMError.contextTooLong))
    check("unknown NSError → retry", isSegmentRetryableError(NSError(domain: "x", code: 1)))
    check("network → no", !isSegmentRetryableError(LLMError.networkError))
    check("cancelled → no", !isSegmentRetryableError(LLMError.cancelled))
    check("Swift CancellationError → no", !isSegmentRetryableError(CancellationError()))
    check("rate limited → no (size is not the problem)", !isSegmentRetryableError(LLMError.rateLimited))
    check("invalid key → no", !isSegmentRetryableError(LLMError.invalidAPIKey))
    check("transient (our own timeout) → no", !isSegmentRetryableError(LLMError.transientError))
    check("URLError → no", !isSegmentRetryableError(URLError(.timedOut)))
}

print("▶️  6. the walk-back is bounded and never splits a tool round (8b76cd747)")
do {
    let wb = walkBackUserTurnsBounded(history, anchorIdx: 9, maxUserTextTurns: 3, maxMessages: 100)
    checkEq("three user-text turns back from the anchor", wb.priorIdx, 2)
    checkEq("stop reason", wb.stopReason, "userTextTargetMet")
    // Anchor on the tool_result: the boundary must not be the tool_result row.
    let onResult = walkBackUserTurnsBounded(history, anchorIdx: 4, maxUserTextTurns: 1, maxMessages: 100)
    checkEq("a tool_result user row is skipped as a boundary", onResult.priorIdx, 2)
    let capped = walkBackUserTurnsBounded(history, anchorIdx: 9, maxUserTextTurns: 3, maxMessages: 5)
    checkEq("message cap stops at the last accepted boundary", capped.priorIdx, 6)
    checkEq("cap stop reason", capped.stopReason, "messageCapWouldExceed")
    let tiny = walkBackUserTurnsBounded(history, anchorIdx: 9, maxUserTextTurns: 3, maxMessages: 1)
    check("even the first round over the cap → nil priorIdx (empty preAnchor)", tiny.priorIdx == nil)
    checkEq("invalid anchor", walkBackUserTurnsBounded(history, anchorIdx: 99, maxUserTextTurns: 3, maxMessages: 100).stopReason, "invalidAnchor")
    // Pre-anchor prune keeps pairs together.
    let big = String(repeating: "x", count: 2000)
    let heavy: [AgentMessage] = [user("q", db: "h0"), toolCall("c9", db: "h1"), toolReply("c9", big, db: "h2"), assistant("a", db: "h3"), user("q2", db: "h4")]
    let marker = CompactMarker(id: "M4", sessionId: "s", summary: "S", firstKeptSortOrder: 5, compactedCount: 5, createdAt: at(999),
                               uiBoundarySortOrder: 5, boundaryMessageId: nil, firstKeptMessageId: nil, lastCompactedMessageId: "h4", version: 2)
    let eff = effectiveAgentHistory(heavy, marker: marker)
    let ids = eff.flatMap(\.parts).compactMap { p -> String? in
        switch p { case .toolUse(let id, _): return "use:" + id; case .toolResult(let id, _): return "res:" + id; default: return nil }
    }
    check("a >1000-char tool_result AND its tool_use are both pruned", !ids.contains("use:c9") && !ids.contains("res:c9"))
    checkEq("first sent message is a user turn", eff.first?.role, .user)
}

print("▶️  7. shipping sources still carry the pinned lines")
do {
    let comp = source("Agent/Chat/AIChatViewModel+Compaction.swift")
    let pers = source("Agent/Chat/AIChatViewModel+Persistence.swift")
    let fork = source("Agent/Session/SessionForkManager.swift")
    let fb = source("Agent/Chat/AIChatViewModel+Fallback.swift")
    if comp.isEmpty || pers.isEmpty || fork.isEmpty || fb.isEmpty { print("  ⏭  sources not readable") } else {
        check("lcm walk-back clamps to the current count", comp.contains("var i = min(endExclusive, agentHistory.count) - 1"))
        check("tool_result rows are not walk-back boundaries", comp.contains("if carriesToolResult { continue }"))
        check("createdAt heal filters by agentHistory presence", comp.contains("return historyDbIds.isEmpty || historyDbIds.contains(raw.id)"))
        check("heal rewrites lcmId and keeps identity", comp.contains("lastCompactedMessageId: newAnchor.id,"))
        check("segment retry: providerError still retryable", comp.contains("case .transientError:") && comp.contains("// Deliberately still true: `providerError` covers"))
        check("v2 read side degrades to full history on an unresolvable anchor", pers.contains("degrading to full history (no summary)"))
        check("summary is injected into the first post-anchor user", pers.contains("injected.parts.insert(.text(summaryText), at: 0)"))
        check("pre-anchor prune threshold is 1000 chars", pers.contains("content.count > 1000 {"))
        check("duplicate copies markers with a remap", fork.contains("oldToNewMessageId[msg.id] = newId") && fork.contains("await store.insertCompactMarker(copy)"))
        check("no raw-count success guard survives", !fb.contains("agentHistory.count") || !fb.contains("did not shorten history"))
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
