// Tests for [T-ctx-usage-after-compact]: after a manual or automatic
// compaction, the composer's placeholder line kept showing the
// PRE-compaction context figure, so compaction looked like it had done
// nothing. The yellow/red glow could also fall back to that figure.
//
// Root cause:
//   * The placeholder line is a one-shot request (ContextUsageHint, one per
//     generation) raised only at turn end or on a mid-loop tier crossing.
//     Compaction never raised one, so the pre-compaction line stayed until the
//     user typed.
//   * publishContextUsage() without a live figure reads the newest stamped
//     message usage. After a compaction that report is the size of the
//     context that was just replaced, so any such publish put the glow back.
//
// Fix: compactBefore (every compaction path) calls
// announceContextUsageAfterCompaction(). It publishes the measured size and
// issues a new hint generation. It also marks the current report as
// superseded, and publishContextUsage() then uses the measured size while that
// report is still the newest one (or while it sits in compacted history after
// a reload).
//
// Standalone: `swift ContextUsageAfterCompactTests.swift`.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

// MARK: - Port of the publishContextUsage source selection

struct Msg { let id = UUID(); var reported: Int; var compacted = false }

/// `fixed: false` = the pre-fix rule (always the newest report).
func usedTokens(messages: [Msg], live: Int?, hasMarker: Bool, superseded: UUID?, measured: Int, fixed: Bool) -> Int {
    let reported = messages.last(where: { $0.reported > 0 })
    var used = live ?? (reported?.reported ?? 0)
    if fixed, live == nil, hasMarker, let r = reported, r.id == superseded || r.compacted {
        if measured > 0 { used = measured }
    }
    return used
}

func tier(_ used: Int, _ window: Int) -> String {
    let f = Double(used) / Double(window)
    return f >= 0.8 ? "critical" : f >= 0.7 ? "warning" : "normal"
}

let window = 200_000
print("▶️  1. the glow after a compaction")
do {
    let turn = Msg(reported: 170_000)   // 85% → red glow before compacting
    let msgs = [turn]
    let measured = 40_000               // what the compacted context measures
    let old = usedTokens(messages: msgs, live: nil, hasMarker: true, superseded: turn.id, measured: measured, fixed: false)
    let new = usedTokens(messages: msgs, live: nil, hasMarker: true, superseded: turn.id, measured: measured, fixed: true)
    check("OLD: a later publish restores the pre-compaction 85% (the bug)", tier(old, window) == "critical")
    check("NEW: it stays at the measured post-compaction size", new == measured && tier(new, window) == "normal")
}
do {
    // The next turn reports a real size on a NEWER message: that wins.
    let before = Msg(reported: 170_000)
    let after = Msg(reported: 52_000)
    check("a newer turn's report takes over again",
          usedTokens(messages: [before, after], live: nil, hasMarker: true, superseded: before.id, measured: 40_000, fixed: true) == 52_000)
}
check("a live mid-loop figure always wins",
      usedTokens(messages: [Msg(reported: 170_000)], live: 61_000, hasMarker: true, superseded: nil, measured: 40_000, fixed: true) == 61_000)
check("no compaction marker → the report is used as before",
      usedTokens(messages: [Msg(reported: 170_000)], live: nil, hasMarker: false, superseded: nil, measured: 40_000, fixed: true) == 170_000)
do {
    // After a reload the in-memory marker id is gone, but the report sits in
    // compacted (greyed) history.
    let greyed = Msg(reported: 170_000, compacted: true)
    check("reload: a report in compacted history is replaced by the measurement",
          usedTokens(messages: [greyed], live: nil, hasMarker: true, superseded: nil, measured: 40_000, fixed: true) == 40_000)
}
check("an unmeasurable context (0) keeps the report rather than hiding the glow",
      usedTokens(messages: [Msg(reported: 170_000)], live: nil, hasMarker: true, superseded: nil, measured: 0, fixed: true) == 170_000)

// MARK: - Port of the hint announcement

struct HintState { var generation = 0; var text: String? = nil }
func announce(_ s: inout HintState, used: Int?, inputEmpty: Bool) {
    guard let used, inputEmpty else { return }
    s.generation += 1
    s.text = "Context \(used * 100 / window)% used"
}

print("\n▶️  2. the placeholder line after a compaction")
do {
    var s = HintState(generation: 7, text: "Context 85% used")   // shown before compacting
    announce(&s, used: 40_000, inputEmpty: true)
    check("a NEW generation is issued (the composer acts once per generation)", s.generation == 8)
    check("…carrying the post-compaction figure", s.text == "Context 20% used")
}
do {
    var s = HintState(generation: 3, text: nil)
    announce(&s, used: 40_000, inputEmpty: false)
    check("not raised over typed text (it would be hidden anyway)", s.generation == 3)
    announce(&s, used: nil, inputEmpty: true)
    check("not raised when the size is unknown", s.generation == 3)
}

// MARK: - Sources

let root = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
func source(_ rel: String) -> String {
    (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let vm = source("Agent/Chat/AIChatViewModel.swift")
let compaction = source("Agent/Chat/AIChatViewModel+Compaction.swift")
let announceBody: String = {
    guard let a = vm.range(of: "func announceContextUsageAfterCompaction() {"),
          let b = vm.range(of: "\n    }\n", range: a.upperBound..<vm.endIndex) else { return "" }
    return String(vm[a.lowerBound..<b.upperBound])
}()

print("\n▶️  3. sources")
check("compactBefore announces after installing the marker",
      (compaction.range(of: "self.cachedLatestMarker = marker")?.lowerBound ?? compaction.endIndex)
        < (compaction.range(of: "announceContextUsageAfterCompaction()")?.lowerBound ?? compaction.startIndex))
check("announce marks the current report as superseded",
      announceBody.contains("usageReportSupersededByCompaction = messages.last(where: {"))
check("announce publishes the measured size", announceBody.contains("publishMeasuredContextUsage()"))
check("announce issues a new hint generation",
      announceBody.contains("contextUsageHintGeneration += 1")
        && announceBody.contains("contextUsageHint = ContextUsageHint.make(usage: usage, generation: contextUsageHintGeneration)"))
check("announce resets the tier baseline", announceBody.contains("_ = contextTierTracker.observe(usage.tier, now: now)"))
check("announce does not markFired (the next loop-end line must not be deduplicated)",
      !announceBody.contains("markFired("))
check("publishContextUsage prefers the measurement for a superseded report",
      vm.contains("if liveContextTokens == nil, cachedLatestMarker != nil, let reported,\n           reported.id == usageReportSupersededByCompaction || reported.isCompactedHistory {"))
check("…only when the measurement is positive", vm.contains("if measured > 0 { used = measured }"))
check("the glow reads contextUsage's tier",
      source("Views/Chat/AIChatView.swift").contains("ComposerContextGlow(tier: vm.contextUsage?.tier ?? .normal)"))

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)"); exit(1) }
