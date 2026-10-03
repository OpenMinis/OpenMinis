// Follow-up fixes from the 2026-09-23 weekly review (the items the first pass
// only recorded as RISK). Each section models the fixed rule and re-reads the
// shipping source so the model cannot drift from it.
//
//   [1] T-ctx-warmup-trim-undecided      (07ca2e8d2 follow-up)
//       An undecided warm-up trim must not be cached as the marker's answer.
//   [2] T-ctx-overflow-attribute-dispatch (df35445ee / 9aad0ffb5 follow-up)
//       A context rejection is scored against the model the request went to.
//   [3] T-codeblock-offset-reset          (3217cfea7 follow-up)
//       A code block that no longer scrolls on an axis sits at offset 0 there.
//   [4] T-toolbar-collapsed-bounds        (271633fb9 follow-up)
//       The collapsed tool bar never indexes toolBlocks out of range.
//
// Standalone: `cd src/ios/MinisTests/Standalone && swift ReviewFollowupEdgeTests.swift`

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

let root = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
func src(_ rel: String) -> String {
    (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let compaction = src("Agent/Chat/AIChatViewModel+Compaction.swift")
let persist = src("Agent/Chat/AIChatViewModel+Persistence.swift")
let vm = src("Agent/Chat/AIChatViewModel.swift")
let markdown = src("Views/Chat/SelectableMarkdownView.swift")
let toolSheet = src("Views/Chat/ToolLiveSheet.swift")
check("sources found", !compaction.isEmpty && !persist.isEmpty && !vm.isEmpty && !markdown.isEmpty && !toolSheet.isEmpty)

// MARK: - [1] warm-up trim cache

print("\n▶️  1. an undecided warm-up trim is not cached (T-ctx-warmup-trim-undecided)")
struct Env { var hasEntry: Bool; var window: Int; var fixed: Int }
/// Port of trimWarmUpToFit's decision guards (the trim math itself is pinned
/// by CompactionDecisionTests / ContextMeasureOutboundTests).
func trim(_ warmUp: Int, overBy: Int, env: Env) -> (kept: Int, decided: Bool) {
    guard warmUp > 0 else { return (warmUp, true) }
    guard env.hasEntry else { return (warmUp, false) }
    guard env.window > 0, env.fixed > 0 else { return (warmUp, false) }
    guard overBy > 0 else { return (warmUp, true) }
    return (max(0, warmUp - overBy), true)
}
/// Port of the builder's per-marker cache.
final class Builder {
    var cache: [String: Int] = [:]
    func build(marker: String, warmUp: Int, overBy: Int, env: Env) -> Int {
        if let drop = cache[marker] { return warmUp - min(drop, warmUp) }
        let fitted = trim(warmUp, overBy: overBy, env: env)
        if fitted.decided { cache[marker] = warmUp - fitted.kept }
        return fitted.kept
    }
}
do {
    let b = Builder()
    // Session load: the entry is resolved but the fixed size is not seeded yet.
    let first = b.build(marker: "M", warmUp: 6, overBy: 2, env: Env(hasEntry: true, window: 200_000, fixed: 0))
    check("undecided pass keeps the warm-up as is", first == 6)
    check("…and caches nothing", b.cache["M"] == nil)
    let second = b.build(marker: "M", warmUp: 6, overBy: 2, env: Env(hasEntry: true, window: 200_000, fixed: 4_000))
    check("the next request decides for real and trims", second == 4)
    check("…and that decision is cached", b.cache["M"] == 2)
    let third = b.build(marker: "M", warmUp: 6, overBy: 5, env: Env(hasEntry: true, window: 200_000, fixed: 4_000))
    check("later requests reuse it (stable prefix for prompt caching)", third == 4)
    let noEntry = Builder()
    _ = noEntry.build(marker: "N", warmUp: 3, overBy: 1, env: Env(hasEntry: false, window: 0, fixed: 0))
    check("no entry → nothing cached", noEntry.cache.isEmpty)
    let fits = Builder()
    _ = fits.build(marker: "F", warmUp: 3, overBy: 0, env: Env(hasEntry: true, window: 200_000, fixed: 4_000))
    check("a warm-up that fits is a real decision (drop 0 cached)", fits.cache["F"] == 0)
}
check("source: trimWarmUpToFit reports decided",
      compaction.contains("func trimWarmUpToFit(_ warmUp: [AgentMessage], rest: [AgentMessage], summaryText: String) -> (kept: [AgentMessage], decided: Bool)"))
check("source: unseeded fixed size is undecided",
      compaction.contains("guard resolved.window > 0, contextFixedTokens > 0 else { return (warmUp, false) }"))
check("source: builder caches only a decided trim",
      persist.contains("if fitted.decided {\n                    warmUpDropByMarker[marker.id] = preAnchorPruned.count - fitted.kept.count"))

// MARK: - [2] rejection attribution

print("\n▶️  2. a rejection is scored against the dispatched model (T-ctx-overflow-attribute-dispatch)")
/// Port of the model/window choice in noteContextOverflow.
func attribution(explicit: String?, dispatched: String?, dispatchedWindow: Int,
                 bound: String?, boundWindow: Int) -> (model: String?, window: Int) {
    let model = explicit ?? dispatched ?? bound
    let window = dispatchedWindow > 0 ? dispatchedWindow : boundWindow
    return (model, window)
}
do {
    // Group member A (1M window) rejected; the binding already names B (200K)
    // because the user switched mid-turn / the group moved on.
    let a = attribution(explicit: nil, dispatched: "A", dispatchedWindow: 1_000_000, bound: "B", boundWindow: 200_000)
    check("the rejected model is the one that received the request", a.model == "A")
    check("…with ITS window, not the binding's", a.window == 1_000_000)
    let none = attribution(explicit: nil, dispatched: nil, dispatchedWindow: 0, bound: "B", boundWindow: 200_000)
    check("nothing dispatched yet → falls back to the binding", none.model == "B" && none.window == 200_000)
    let forced = attribution(explicit: "X", dispatched: "A", dispatchedWindow: 1_000_000, bound: "B", boundWindow: 200_000)
    check("an explicit model id still wins", forced.model == "X")
}
check("source: dispatch records the model and its window",
      compaction.contains("lastDispatchModelId = modelId\n        lastDispatchWindow = resolvedContextWindow(for: model).window"))
check("source: noteContextOverflow prefers the dispatched model",
      compaction.contains("let model = modelId ?? lastDispatchModelId ?? currentContextModelId()"))
check("source: …and the dispatched window",
      compaction.contains("let window = lastDispatchWindow > 0\n            ? lastDispatchWindow"))
check("source: the loop records the model that serves the request",
      vm.contains("recordContextDispatch(history: contextHistory, model: activeModel)"))
check("source: a new session's calibration seed clears the dispatch record",
      compaction.contains("lastDispatchEstimate = 0\n        lastDispatchModelId = nil\n        lastDispatchWindow = 0"))

// MARK: - [3] code block offset

print("\n▶️  3. a code block that stops scrolling rests at offset 0 (T-codeblock-offset-reset)")
struct Pt: Equatable { var x: Double; var y: Double }
/// Port of the offset tail of syncScrollability.
func settle(_ offset: Pt, content: (w: Double, h: Double), visibleWidth: Double, scrollHeight: Double) -> Pt {
    let canScrollV = content.h > scrollHeight + 0.5
    let canScrollH = content.w > visibleWidth + 0.5
    var o = offset
    if !canScrollH { o.x = 0 }
    if !canScrollV { o.y = 0 }
    return o
}
check("reused for shorter, narrower content → back to origin",
      settle(Pt(x: 180, y: 90), content: (300, 120), visibleWidth: 320, scrollHeight: 200) == Pt(x: 0, y: 0))
check("still wide → horizontal position kept, vertical reset",
      settle(Pt(x: 180, y: 90), content: (900, 120), visibleWidth: 320, scrollHeight: 200) == Pt(x: 180, y: 0))
check("still scrollable both ways → untouched",
      settle(Pt(x: 40, y: 30), content: (900, 900), visibleWidth: 320, scrollHeight: 200) == Pt(x: 40, y: 30))
check("source: syncScrollability zeroes a non-scrolling axis",
      markdown.contains("if !canScrollH { offset.x = 0 }\n        if !canScrollV { offset.y = 0 }"))
check("source: …only writing when it changed (no redundant scroll callbacks)",
      markdown.contains("if offset != scrollView.contentOffset { scrollView.contentOffset = offset }"))

// MARK: - [4] collapsed tool bar bounds

print("\n▶️  4. the collapsed tool bar never indexes out of range (T-toolbar-collapsed-bounds)")
func collapsedBlock(_ blocks: [String], idx: Int) -> String? {
    blocks.indices.contains(idx) ? blocks[idx] : nil
}
check("normal index renders its block", collapsedBlock(["a", "b"], idx: 1) == "b")
check("blocks cleared between parent check and body → renders nothing", collapsedBlock([], idx: 0) == nil)
check("stale index past a truncation → renders nothing", collapsedBlock(["a"], idx: 3) == nil)
check("-1 (no selection) → renders nothing", collapsedBlock(["a"], idx: -1) == nil)
let barFn: String = {
    guard let a = toolSheet.range(of: "fileprivate func collapsedBar(now: Date) -> some View {"),
          let b = toolSheet.range(of: "private func collapsedBarContent(", range: a.upperBound..<toolSheet.endIndex)
    else { return "" }
    return String(toolSheet[a.lowerBound..<b.lowerBound])
}()
check("source: collapsedBar located", !barFn.isEmpty)
check("source: collapsedBar bounds-checks before indexing",
      barFn.contains("if copy.toolBlocks.indices.contains(idx) {\n                collapsedBarContent(block: copy.toolBlocks[idx], idx: idx)"))
check("source: no unchecked subscript left in collapsedBar",
      barFn.contains("let block = copy.toolBlocks[idx]"), false)

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)"); exit(1) }
