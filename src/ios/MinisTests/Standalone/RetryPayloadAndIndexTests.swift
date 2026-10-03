// [T28] A retry after the 120 s stream timeout must pass the context check
// before the same oversized payload is re-sent, and every `messages[msgIdx]`
// on the retry / fallback paths must be re-resolved by stable id after each
// suspension point.
//
// Pins:
//   1b625fe96  capture `runMsgId`, resync at both catch entries, bounds-guard
//              the MainActor.run mutations
//   6f137c42c  re-resync after every MainActor.run suspension (3 sites)
//   bfe9d4bcd  resync before the fallback-notice insert (applyFallbackSwitch)
//   22631d5ae  resync before the empty-reminder request and the per-round
//              main request (compaction runs in between)
//   71993e213  retryFromToolBlock snapshots the trimmed entry by VALUE
//   ad2f354d3  tool_use/tool_result pairing diagnostics around that sub-cut
//   issues #181 (120 s timeout + Retry resends the identical payload → death
//   spiral), #138 / #264 (timeout configuration)
//
// Standalone (`swift RetryPayloadAndIndexTests.swift`): the app cannot link
// for a simulator (deps/libs/libish_emu.a is device-only arm64). Section [1]
// ports ContextPolicy + checkContextBeforeSend and the retry → runAgentLoop
// per-round guard; section [2] ports the resync rule and replays the array
// mutations that crashed in the field; section [3] scans the shipping catch
// region and asserts that no `messages[msgIdx]` follows a suspension without
// a resync in between.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Ported: ContextPolicy (Agent/Chat/ContextPolicy.swift:11-108)

struct ContextPolicy {
    let offloadThreshold: Int
    let compactThreshold: Int
    let exhaustedOnly: Bool
    let manualCompactAllowed: Bool

    init(contextWindow: Int, isUserCap: Bool = false) {
        if isUserCap {
            offloadThreshold = Int(Double(contextWindow) * 0.70)
            compactThreshold = Int(Double(contextWindow) * 0.85)
            exhaustedOnly = false
            manualCompactAllowed = true
            return
        }
        if contextWindow < 32_000 {
            offloadThreshold = 0; compactThreshold = 0; exhaustedOnly = true; manualCompactAllowed = false
        } else if contextWindow < 64_000 {
            offloadThreshold = contextWindow - 10_000; compactThreshold = 0; exhaustedOnly = true; manualCompactAllowed = true
        } else if contextWindow < 128_000 {
            offloadThreshold = contextWindow - 20_000; compactThreshold = contextWindow - 10_000; exhaustedOnly = false; manualCompactAllowed = true
        } else {
            offloadThreshold = contextWindow - 40_000; compactThreshold = contextWindow - 20_000; exhaustedOnly = false; manualCompactAllowed = true
        }
    }

    enum CheckResult: Equatable { case ok, needsCompact, exhausted }

    func check(estimatedTokens: Int, contextWindow: Int) -> CheckResult {
        if compactThreshold > 0, estimatedTokens >= compactThreshold { return .needsCompact }
        if contextWindow > 0, estimatedTokens >= contextWindow {
            return manualCompactAllowed ? .needsCompact : .exhausted
        }
        if exhaustedOnly {
            let exhaustedThreshold = offloadThreshold > 0 ? offloadThreshold : Int(Double(contextWindow) * 0.90)
            if estimatedTokens >= exhaustedThreshold { return .exhausted }
        }
        return .ok
    }
}

/// [T-ctx-measure-outbound] AIChatViewModel+Compaction.measureOutboundContextTokens,
/// reduced to the numbers these scenarios vary: the outbound estimate NOW, and
/// the provider's count for a request we estimated at `estimatedAtReport`
/// (default: the same history — nothing has changed since the report). The
/// calibration ratio is report ÷ that estimate, clamped to 0.8…3.0; with no
/// report the estimate stands alone. So with nothing changed the measurement
/// equals the report, exactly as the old max() did for a report above the
/// estimate — the difference is only in what happens after the history changes.
func measuredTokens(estimated: Int, apiReported: Int, estimatedAtReport: Int? = nil) -> Int {
    let basis = estimatedAtReport ?? estimated
    guard apiReported > 0, basis > 0 else { return estimated }
    let ratio = min(max(Double(apiReported) / Double(basis), 0.8), 3.0)
    return Int((Double(estimated) * ratio).rounded(.up))
}

/// AIChatViewModel+Compaction.checkContextBeforeSend — judges the calibrated
/// measurement of the outbound request.
func checkContextBeforeSend(estimated: Int, apiReported: Int, contextWindow: Int, isUserCap: Bool = false) -> ContextPolicy.CheckResult {
    guard contextWindow > 0 else { return .ok }
    let policy = ContextPolicy(contextWindow: contextWindow, isUserCap: isUserCap)
    return policy.check(estimatedTokens: measuredTokens(estimated: estimated, apiReported: apiReported), contextWindow: contextWindow)
}

/// What the retry does with the payload, per round.
enum RetryRoundAction: Equatable { case compactThenContinue, stopExhausted, sendAsIs }

/// retry() → runAgentLoop(resumingAt:) → the in-loop guard
/// (AIChatViewModel.swift:6135-6150) runs BEFORE the round's request is built.
func retryRound(estimated: Int, apiReported: Int, contextWindow: Int,
                compactionsThisLoop: Int = 0, maxInLoopCompactions: Int = 2) -> RetryRoundAction {
    switch checkContextBeforeSend(estimated: estimated, apiReported: apiReported, contextWindow: contextWindow) {
    case .ok: return .sendAsIs
    case .needsCompact:
        if compactionsThisLoop < maxInLoopCompactions { return .compactThenContinue }
        // [T-ctx-measure-outbound] Budget spent: above the compact THRESHOLD is
        // still sendable while the request fits the window.
        return measuredTokens(estimated: estimated, apiReported: apiReported) < contextWindow
            ? .sendAsIs : .stopExhausted
    case .exhausted: return .stopExhausted
    }
}

/// The #181 behaviour: Retry rebroadcast the identical request.
func retryRound_preFix() -> RetryRoundAction { .sendAsIs }

// MARK: - Ported: the msgIdx resync rule

struct Row: Equatable { let id: UUID; var writes: Int = 0 }

enum Resync: Equatable { case kept, resynced(Int), aborted }

/// AIChatViewModel.swift:6325-6331 / :6527-6533 — "verify index + identity,
/// else re-find by runMsgId, else abort the round". Deliberately NOT a bare
/// bounds check (bfe9d4bcd: an in-range index pointing at the WRONG row is
/// worse than a crash).
func resync(_ msgIdx: inout Int, runMsgId: UUID, messages: [Row]) -> Resync {
    if msgIdx < 0 || msgIdx >= messages.count || messages[msgIdx].id != runMsgId {
        guard let resynced = messages.firstIndex(where: { $0.id == runMsgId }) else { return .aborted }
        msgIdx = resynced
        return .resynced(resynced)
    }
    return .kept
}

/// The catch-entry shape (1b625fe96): firstIndex by id, else throw.
func resyncOrThrow(_ msgIdx: inout Int, runMsgId: UUID, messages: [Row]) -> Bool {
    guard let resynced = messages.firstIndex(where: { $0.id == runMsgId }) else { return false }
    msgIdx = resynced
    return true
}

/// The pre-fix "fix": clamp the index into range. Compiles, never traps,
/// silently writes to the wrong row.
func clampOnly(_ msgIdx: inout Int, messages: [Row]) -> Bool {
    guard !messages.isEmpty else { return false }
    msgIdx = min(msgIdx, messages.count - 1)
    return true
}

/// A retry-path write, as the loop does it after a resync.
func writeAt(_ msgIdx: Int, _ messages: inout [Row]) { messages[msgIdx].writes += 1 }

// MARK: - [1] The retry must pass the context check

print("\n[1] Retry after a 120 s timeout re-checks context before re-sending")
do {
    // #181: Grok 4.5, ~128K window, a tool-heavy session estimated well past
    // the compact line. The old Retry re-sent the identical payload.
    let window = 131_072
    let oversized = 125_000
    checkEq("estimate past the compact line → compact before the request",
            retryRound(estimated: oversized, apiReported: 0, contextWindow: window), .compactThenContinue)
    checkEq("PRE-FIX: the identical payload went straight back out", retryRound_preFix(), .sendAsIs)

    // The provider's own number counts, even when the local estimate is calm.
    checkEq("local estimate calm but API reported 108% → NOT sent as-is",
            retryRound(estimated: 60_000, apiReported: 138_800, contextWindow: 128_000), .compactThenContinue)
    checkEq("under both thresholds → send", retryRound(estimated: 60_000, apiReported: 70_000, contextWindow: 128_000), .sendAsIs)

    // Exactly at the compact threshold (window − 20K for ≥128K).
    checkEq("at the ≥128K compact line (window−20K) → compact",
            retryRound(estimated: 128_000 - 20_000, apiReported: 0, contextWindow: 128_000), .compactThenContinue)
    checkEq("one token under the line → send",
            retryRound(estimated: 128_000 - 20_001, apiReported: 0, contextWindow: 128_000), .sendAsIs)

    // Small windows cannot compact: stop with a resumable notice instead of a spiral.
    checkEq("<32K window past 90% → exhausted (no compaction possible)",
            retryRound(estimated: 15_000, apiReported: 0, contextWindow: 16_000), .stopExhausted)
    checkEq("32K–64K window past the offload line → exhausted (no auto-compact tier)",
            retryRound(estimated: 40_000, apiReported: 0, contextWindow: 48_000), .stopExhausted)
    checkEq("64K–128K window: compact at window−10K",
            retryRound(estimated: 90_000 - 10_000, apiReported: 0, contextWindow: 90_000), .compactThenContinue)

    // Past the ceiling outright.
    checkEq("over the ceiling on a compactable tier → compact",
            retryRound(estimated: 140_000, apiReported: 0, contextWindow: 128_000), .compactThenContinue)
    checkEq("over the ceiling on a non-compactable tier → exhausted",
            retryRound(estimated: 20_000, apiReported: 0, contextWindow: 16_000), .stopExhausted)

    // The compaction budget: a loop that keeps compacting must not run forever.
    // [T-ctx-measure-outbound] Budget spent never loops. It used to stop the
    // turn outright; it now sends when the request still fits the window
    // (the compact line sits 20K under it by design) and stops only when not.
    checkEq("compaction budget spent, still within the window → send, not abort",
            retryRound(estimated: 125_000, apiReported: 0, contextWindow: 131_072, compactionsThisLoop: 2), .sendAsIs)
    checkEq("compaction budget spent and over the window → stop rather than a compact loop",
            retryRound(estimated: 132_000, apiReported: 0, contextWindow: 131_072, compactionsThisLoop: 2), .stopExhausted)

    // User-capped window is proportional.
    checkEq("user cap 32K: compact at 85%", checkContextBeforeSend(estimated: 27_200, apiReported: 0, contextWindow: 32_000, isUserCap: true), .needsCompact)
    checkEq("no window known → ok (nothing to judge against)", checkContextBeforeSend(estimated: 1_000_000, apiReported: 0, contextWindow: 0), .ok)
}

// MARK: - [2] Index re-resolution after an await

print("\n[2] msgIdx is re-resolved by stable id after every await")
do {
    let a = Row(id: UUID()), b = Row(id: UUID()), run = Row(id: UUID())
    var messages = [a, b, run]
    var msgIdx = messages.count - 1            // captured at turn start
    let runMsgId = messages[msgIdx].id

    // Case: a row is INSERTED before the run row during the await (iCloud
    // inbound / compaction divider). The stale index now points at `run`'s
    // old slot, which is a different row.
    messages.insert(Row(id: UUID()), at: 0)
    let r1 = resync(&msgIdx, runMsgId: runMsgId, messages: messages)
    checkEq("insert during await → index resynced to the run row", r1, .resynced(3))
    writeAt(msgIdx, &messages)
    check("the write lands on the original message", messages.first { $0.id == runMsgId }!.writes == 1)
    check("…and not on the row that now sits at the stale slot", messages[2].writes == 0)

    // Case: nothing changed → cheap no-op.
    checkEq("unchanged array → kept", resync(&msgIdx, runMsgId: runMsgId, messages: messages), .kept)

    // Case: a leading row is DELETED (the 22631d5ae repro: chat.debugRemoveMessages).
    messages.removeFirst(2)
    let r2 = resync(&msgIdx, runMsgId: runMsgId, messages: messages)
    checkEq("delete during await → resynced, not out of range", r2, .resynced(1))
    writeAt(msgIdx, &messages)
    check("second write also hits the original message", messages.first { $0.id == runMsgId }!.writes == 2)

    // Case: the run row itself is gone → abort, never subscript.
    messages.removeAll { $0.id == runMsgId }
    checkEq("run row gone → abort the round", resync(&msgIdx, runMsgId: runMsgId, messages: messages), .aborted)
    check("catch-entry variant reports the miss instead of trapping",
          resyncOrThrow(&msgIdx, runMsgId: runMsgId, messages: messages), false)

    // Case: REORDER (sync HEAL) — in range, wrong row. The lesson of bfe9d4bcd.
    var reordered = [run, a, b]
    var idx = 2
    let clamped = clampOnly(&idx, messages: reordered)
    check("PRE-FIX clamp accepts the in-range index", clamped && idx == 2)
    check("PRE-FIX clamp would write to the WRONG row", reordered[idx].id != runMsgId)
    checkEq("resync by id finds the run row after a reorder", resync(&idx, runMsgId: runMsgId, messages: reordered), .resynced(0))
    writeAt(idx, &reordered)
    check("the write lands on the run row", reordered[0].writes == 1 && reordered[0].id == runMsgId)

    // Case: the array shrank below the stale index (the 1b625fe96 crash shape).
    var shrunk = [run]
    var stale = 5
    check("PRE-FIX: a bare subscript at the stale index would trap", stale >= shrunk.count)
    checkEq("resync recovers the row", resync(&stale, runMsgId: runMsgId, messages: shrunk), .resynced(0))
    writeAt(stale, &shrunk)
    check("write succeeds after recovery", shrunk[0].writes == 1)

    // Case: 71993e213 — snapshot by value survives a shrink; indexing does not.
    struct Entry: Equatable { let id: String; var parts: [String] }
    var agentHistory = (0..<19).map { Entry(id: "e\($0)", parts: ["p\($0)"]) }
    let entryIdx = 18
    let trimmedEntry = agentHistory[entryIdx]                 // by value, before the await
    agentHistory.removeAll { $0.id == "e3" }                  // orphan-tool_result sweep during the await: 19 → 18
    check("PRE-FIX: agentHistory[entryIdx] after the await is out of range", entryIdx >= agentHistory.count)
    checkEq("the value snapshot still carries the trimmed parts", trimmedEntry.parts, ["p18"])
}

// MARK: - [3] Source scan: no bare subscript after an await in the retry region

print("\n[3] Shipping retry / fallback region: every messages[msgIdx] after a suspension is preceded by a resync")
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let vm = source("Agent/Chat/AIChatViewModel.swift")
let compaction = source("Agent/Chat/AIChatViewModel+Compaction.swift")
let policySrc = source("Agent/Chat/ContextPolicy.swift")
if vm.isEmpty || compaction.isEmpty || policySrc.isEmpty {
    print("  ⏭  a source is not readable"); failures += 1
} else {
    let lines = vm.components(separatedBy: "\n")
    func lineIndex(containing needle: String, from: Int = 0) -> Int? {
        for i in from..<lines.count where lines[i].contains(needle) { return i }
        return nil
    }
    // The retry / fallback region: from the mid-stream catch to the end of
    // the nested retry-exhausted fallback (its applyFallbackSwitch).
    guard let catchStart = lineIndex(containing: "catch let streamError as LLMError where streamError.isRetryable"),
          let nestedCatch = lineIndex(containing: "catch let retryError as LLMError where", from: catchStart),
          let regionEnd = lineIndex(containing: "await applyFallbackSwitch()", from: nestedCatch) else {
        check("retry / fallback catch region located", false); exit(1)
    }
    check("retry / fallback catch region located", true)

    // Statements that genuinely suspend and CLOSE before the next line — a
    // subscript after one of these needs a fresh resync. `await` calls whose
    // argument list itself contains the subscript (streamWithAutoRetry /
    // streamWithGroupFallbackUntilContent) evaluate it before suspending and
    // are covered by the resync that precedes the call.
    func isClosedSuspension(_ l: String) -> Bool {
        let t = l.trimmingCharacters(in: .whitespaces)
        if t.hasPrefix("//") { return false }
        return t.hasPrefix("await MainActor.run")
            || t.contains("await applyFallbackSwitch()")
            || t.contains("try await processStreamEvents(")
            || t.hasPrefix("await self.") || t.hasPrefix("await persist")
    }
    func isResync(_ l: String) -> Bool {
        l.contains("firstIndex(where: { $0.id == runMsgId })")
    }
    var subscripts = 0, unguarded: [Int] = []
    for i in catchStart...regionEnd {
        let l = lines[i]
        guard l.contains("messages[msgIdx]"), !l.trimmingCharacters(in: .whitespaces).hasPrefix("//") else { continue }
        subscripts += 1
        // Inside a `await MainActor.run { guard msgIdx < messages.count else { return }` block
        // the guard is the protection; otherwise walk back for the nearest resync
        // vs the nearest closed suspension.
        var j = i - 1
        var guarded = false
        while j >= catchStart {
            let p = lines[j]
            if p.contains("guard msgIdx < messages.count else { return }") { guarded = true; break }
            if isResync(p) { guarded = true; break }
            if isClosedSuspension(p) { break }
            j -= 1
        }
        if !guarded { unguarded.append(i + 1) }
    }
    check("region contains the subscripts this test is about (\(subscripts) found)", subscripts >= 6)
    check("no messages[msgIdx] in the region follows a suspension without a resync/guard (unguarded lines: \(unguarded))",
          unguarded.isEmpty)

    // The specific sites each commit added.
    check("runMsgId is captured at turn start",
          vm.contains("var runMsgId: UUID = msgIdx < messages.count ? messages[msgIdx].id : UUID()"))
    check("outer catch entry resyncs", vm.contains("🔁STREAM mid-stream catch: assistant message id="))
    check("always-strategy branch re-resyncs post-await", vm.contains("🔀STREAM always-strategy: assistant message id="))
    check("autoRetry branch re-resyncs post-await", vm.contains("🔁STREAM autoRetry path: assistant message id="))
    check("retry-exhausted catch resyncs on entry", vm.contains("🔀STREAM retry-exhausted catch: assistant message id="))
    check("retry-exhausted fallback re-resyncs post-await", vm.contains("🔀STREAM retry-exhausted fallback: assistant message id="))
    check("applyFallbackSwitch resyncs before the notice insert (bfe9d4bcd)",
          vm.contains("🔀AGENT_LOOP applyFallbackSwitch: assistant message id="))
    check("empty-reminder request resyncs (22631d5ae)", vm.contains("🔁STREAM empty-reminder: assistant message id="))
    check("per-round main request resyncs after the compaction guard (22631d5ae)",
          vm.contains("🔀STREAM round-start: assistant message id="))
    check("the identity test is index+id, not a bare bounds check",
          vm.contains("if msgIdx < 0 || msgIdx >= messages.count || messages[msgIdx].id != runMsgId {"))
    check("runMsgId follows the fresh bubble after an in-loop compaction", vm.contains("runMsgId = fresh.id"))
    check("retryFromToolBlock snapshots the entry by value (71993e213)",
          vm.contains("let trimmedEntry = agentHistory[entryIdx]") && vm.contains("buildRawMessage(trimmedEntry)")
          && !vm.contains("buildRawMessage(self.agentHistory[entryIdx])"))

    // The retry payload check: retry() resumes through runAgentLoop, whose
    // per-round guard runs checkContextBeforeSend BEFORE the request is built.
    let retryFn = lineIndex(containing: "    func retry() {")!
    check("retry() resumes the loop rather than re-sending directly",
          lines[retryFn..<min(retryFn + 260, lines.count)].contains { $0.contains("try await self.runAgentLoop(resumingAt: existingMsgIdx, committedBlocks: existingBlockCount)") })
    let loopFn = lineIndex(containing: "private func runAgentLoop(resumingAt existingMsgIdx: Int? = nil")!
    let guardIdx = lineIndex(containing: "switch checkContextBeforeSend(site: \"in-loop\") {", from: loopFn)!
    let mainReq = lineIndex(containing: "let stream = try await streamWithGroupFallback(", from: loopFn)!
    check("the in-loop context guard precedes the round's request", loopFn < guardIdx && guardIdx < mainReq)
    check("the guard compacts in place and continues",
          lines[guardIdx..<guardIdx + 40].contains { $0.contains("await compactBefore(anchorId, allowDuringProcessing: true)") })
    check("checkContextBeforeSend judges the calibrated outbound measurement",
          compaction.contains("let measured = m.measured\n        let result = policy.check(estimatedTokens: measured, contextWindow: contextWindow)")
          && !compaction.contains("max(estimated, apiReported)"))
    check("ContextPolicy.check: compact line first, then ceiling, then exhausted tier",
          policySrc.contains("if compactThreshold > 0, estimatedTokens >= compactThreshold {")
          && policySrc.contains("return manualCompactAllowed ? .needsCompact : .exhausted"))
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
