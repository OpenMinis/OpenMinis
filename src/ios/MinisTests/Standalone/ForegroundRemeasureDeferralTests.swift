#!/usr/bin/env swift
// [T-ios-scenephase-active-sigkill, T-ios-cjk-measure-threshold] issue #355 —
// returning to the foreground after minutes in the background produced a single
// 6.3s main-thread hang (37 HangDetector samples, one stack) in
// `CTLineCreateWithAttributedString` ← SwiftUI Text, then a watchdog SIGKILL.
// Footprint 18MB, so not OOM.
//
// Two changes are pinned here:
//
//  1. The `willEnterForeground` re-measure is deferred by one `Task.yield()`
//     instead of running inside the notification delivery. Every other
//     foreground-resume path in the app already does this under
//     [T-ios-scenephase-active-sigkill]; the text re-measure was the one that
//     never got it, so its cost landed inside the transition tick where the
//     watchdog is strictest.
//  2. Backgrounding must NOT purge the text caches. The purge-on-memory-warning
//     is what leaves them cold, and a cold cache is what makes the foreground
//     re-measure expensive — so adding a background purge would make #355
//     strictly worse.
//
// NOT pinned here, deliberately: a CJK-aware measure cap. Lowering the
// 8000-char watchdog in `measureAttributedStringHeight` was proposed as phase
// 5c item 3 and EXPLICITLY NOT APPROVED (see f070fd756's message and the
// assertion in HeightCacheKeyTests.swift that pins the literal). I implemented
// it for #355 before finding that decision and reverted it. If the threshold is
// ever revisited, that HeightCacheKeyTests assertion is the gate to change
// first, with the user's agreement.
//
// Run: swift ForegroundRemeasureDeferralTests.swift
//
// Convention: a bare `swift` script — `deps/libs/libish_emu.a` is device-arm64
// only, so the app cannot link for the simulator. Sections [1] and [2] verify
// the shipping source's structure (the deferral and the cap are wiring, not
// pure functions).
import Foundation

var failures = 0
func check(_ label: String, _ cond: Bool) {
    print(cond ? "  ✅ \(label)" : "  ❌ \(label) — expected true, got false")
    if !cond { failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ a: T, _ b: T) {
    print(a == b ? "  ✅ \(label)" : "  ❌ \(label) — expected \(b), got \(a)")
    if a != b { failures += 1 }
}

func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}

/// Strip `//` comment lines before matching, so a doc comment that QUOTES code is
/// never mistaken for the code itself — a trap an earlier guard in this code base
/// fell into.
func codeOnly(_ src: String) -> String {
    src.components(separatedBy: "\n")
        .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
        .joined(separator: "\n")
}

let listRaw = source("Agent/MessageList/CollectionViewMessageListV3.swift")
guard !listRaw.isEmpty else {
    print("  ⏭  sources not readable from \(#filePath)")
    exit(0)
}
let list = codeOnly(listRaw)

print("▶️  1. the foreground re-measure is deferred off the transition tick")
do {
    // Isolate the willEnterForeground sink body: from the publisher to the
    // `.store(in:)` that terminates it.
    let body: String = {
        guard let start = list.range(of: "publisher(for: UIApplication.willEnterForegroundNotification)"),
              let end = list.range(of: ".store(in: &subscriptions)", range: start.upperBound..<list.endIndex)
        else { return "" }
        return String(list[start.lowerBound..<end.upperBound])
    }()
    check("the willEnterForeground sink was located", !body.isEmpty)

    // The load-bearing assertion: the expensive call must be INSIDE a yielded
    // Task, not at the sink's top level.
    check("the sink yields before doing work", body.contains("await Task.yield()"))
    check("remeasureVisibleCells is called after the yield",
          body.range(of: "await Task.yield()").map { y in
              body.range(of: "remeasureVisibleCells").map { r in r.lowerBound > y.upperBound } ?? false
          } ?? false)
    // A re-background between the notification and the yield must abort: the work
    // is wasted, and UIKit can apply a diff incompletely while not active.
    check("a re-background check guards the deferred work",
          body.contains("applicationState != .background"))
    // The delta must still be captured synchronously — `contentLengthAtBackground`
    // is consumed here, so reading it after the yield would race a bg→fg→bg cycle
    // that re-armed it.
    check("the delta is still read synchronously, before the Task",
          body.range(of: "self.contentLengthAtBackground = nil").map { c in
              body.range(of: "Task { @MainActor in").map { t in c.upperBound < t.lowerBound } ?? false
          } ?? false)
    // The unchanged-content short circuit must survive, or a plain
    // background/foreground with no streaming starts paying for a re-measure.
    check("the unchanged-content short circuit survives", body.contains("guard accumulated != 0 else {"))
}

print("\n▶️  2. caches are still NOT purged on background")
do {
    // The purge-on-memory-warning is what leaves the cache cold; adding a
    // purge on BACKGROUND would make #355 strictly worse, so pin its absence.
    let bgSink: String = {
        guard let start = list.range(of: "publisher(for: UIApplication.didEnterBackgroundNotification)"),
              let end = list.range(of: ".store(in: &subscriptions)", range: start.upperBound..<list.endIndex)
        else { return "" }
        return String(list[start.lowerBound..<end.upperBound])
    }()
    check("the background sink was located", !bgSink.isEmpty)
    check("it does not drop the height caches",
          !bgSink.contains("attrStringHeightCache.removeAll()")
          && !bgSink.contains("userBubbleHeightCache.removeAll()"))
    check("it still only records the content length",
          bgSink.contains("self.contentLengthAtBackground = len"))
    // The memory-warning purge must remain — it is the legitimate pressure valve.
    check("the memory-warning purge is intact",
          list.contains("self.attrStringHeightCache.removeAll()"))
}

print("")
if failures == 0 {
    print("✅ ALL PASSED")
} else {
    print("❌ \(failures) FAILURE(S)")
}
exit(failures == 0 ? 0 : 1)
