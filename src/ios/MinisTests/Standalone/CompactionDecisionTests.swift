#!/usr/bin/env swift
// Guards for the auto-compaction and revert decisions that a later edit is
// most likely to break without any other test noticing:
//
//   [1] the in-loop decision table (ContextPolicy.inLoopStep) and the
//       "never wedge" properties it exists for;
//   [2] an agent-loop model built only from the ported production functions,
//       for the interactions (smoothing × valve × rejection × compaction);
//   [3] the calibration replay a reload runs, and the same-session carry-over
//       a revert depends on;
//   [4] the warm-up trim's turn boundaries;
//   [5] the view model routes its decisions through those functions.
//
// A bare `swift` script cannot import the app, so the functions under test
// are copied into `Port` below — and [0] compares every copied function with
// the production source text (comments and whitespace ignored). A copy that
// drifts from production fails here instead of silently testing old code.
//
// Run: swift CompactionDecisionTests.swift
// Android twin: app/src/test/java/com/openminis/app/data/CompactionDecisionTest.kt
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
func checkClose(_ label: String, _ a: Double, _ b: Double) {
    let ok = abs(a - b) < 1e-9
    print(ok ? "  ✅ \(label)" : "  ❌ \(label) — expected \(b), got \(a)")
    if !ok { failures += 1 }
}
func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}

// MARK: - Port (verbatim; [0] checks it against Agent/Chat/ContextPolicy.swift)

enum Port {
    static let calibrationRange: ClosedRange<Double> = 0.8...3.0
    static let uncalibratedModelMargin = 1.2
    static let calibrationFallRate = 0.3

    enum CheckResult { case ok, needsCompact, exhausted }
    enum InLoopStep: Equatable { case proceed, compact, sendWithinWindow, sendUncalibratedOnce, stop }

    struct CalibrationSample: Equatable {
        let reported: Int, estimated: Int, fixedTokens: Int, modelId: String?
    }

    struct CalibrationState: Equatable {
        var ratios: [String: Double] = [:]
        var lastLearned: Double? = nil
        var fixedTokens = 0
        var samples = 0

        func carryingOver(_ learned: CalibrationState) -> CalibrationState {
            var merged = self
            merged.ratios.merge(learned.ratios) { _, inMemory in inMemory }
            if let last = learned.lastLearned { merged.lastLearned = last }
            if learned.fixedTokens > 0 { merged.fixedTokens = learned.fixedTokens }
            return merged
        }
    }

    static func calibrationRatio(reported: Int, estimated: Int) -> Double? {
        guard reported > 0, estimated > 0 else { return nil }
        let raw = Double(reported) / Double(estimated)
        return min(max(raw, calibrationRange.lowerBound), calibrationRange.upperBound)
    }

    static func calibrated(_ estimated: Int, ratio: Double) -> Int {
        Int((Double(estimated) * ratio).rounded(.up))
    }

    static func smoothed(previous: Double?, sample: Double) -> Double {
        guard let previous else { return sample }
        if sample >= previous { return sample }
        return previous + calibrationFallRate * (sample - previous)
    }

    static func ratio(for modelId: String?, known: [String: Double], lastLearned: Double?) -> Double {
        if let modelId, let own = known[modelId] { return own }
        guard let lastLearned else { return 1.0 }
        return min(max(lastLearned, 1.0) * uncalibratedModelMargin, calibrationRange.upperBound)
    }

    static func ratioAfterOverflow(current: Double, estimated: Int, requested: Int?, window: Int) -> Double {
        guard estimated > 0 else { return current }
        let plausible = requested.flatMap { r -> Int? in
            guard window > 0 else { return r }
            return (Double(r) >= Double(window) * 0.9 && Double(r) <= Double(window) * 4) ? r : nil
        }
        let target = plausible.map(Double.init) ?? Double(max(window, 1)) * 1.02
        let implied = target / Double(estimated)
        return min(max(current, implied), calibrationRange.upperBound)
    }

    static func replayCalibration(_ samples: [CalibrationSample]) -> CalibrationState {
        var state = CalibrationState()
        for s in samples {
            guard let sample = calibrationRatio(reported: s.reported, estimated: s.estimated) else { continue }
            state.samples += 1
            if let model = s.modelId {
                let updated = smoothed(previous: state.ratios[model], sample: sample)
                state.ratios[model] = updated
                state.lastLearned = updated
            } else {
                state.lastLearned = sample
            }
            state.fixedTokens = s.fixedTokens
        }
        return state
    }

    static func warmUpDrop(sizes: [Int], startsTurn: [Bool], restTokens: Int, budget: Int) -> Int {
        precondition(sizes.count == startsTurn.count)
        var total = sizes.reduce(0, +)
        var drop = 0
        while drop < sizes.count, total + restTokens >= budget {
            total -= sizes[drop]; drop += 1
            while drop < sizes.count, !startsTurn[drop] { total -= sizes[drop]; drop += 1 }
        }
        return drop
    }

    static func inLoopStep(verdict: CheckResult, measured: Int, rawTokens: Int, window: Int,
                           canCompact: Bool, ratio: Double, uncalibratedSendUsed: Bool) -> InLoopStep {
        switch verdict {
        case .ok: return .proceed
        case .exhausted: return .stop
        case .needsCompact:
            if canCompact { return .compact }
            if window <= 0 || measured < window { return .sendWithinWindow }
            if !uncalibratedSendUsed, ratio > 1.0, rawTokens < window { return .sendUncalibratedOnce }
            return .stop
        }
    }
}

/// `func NAME(` … its matching `}`, with `//` comments and whitespace removed.
func functionText(_ name: String, in text: String) -> String? {
    guard let start = text.range(of: "func \(name)(") else { return nil }
    var depth = 0, seenBrace = false
    var out = ""
    var i = start.lowerBound
    while i < text.endIndex {
        let c = text[i]
        out.append(c)
        if c == "{" { depth += 1; seenBrace = true }
        if c == "}" { depth -= 1; if seenBrace && depth == 0 { break } }
        i = text.index(after: i)
    }
    let noComments = out.split(separator: "\n").map { line -> Substring in
        if let r = line.range(of: "//") { return line[..<r.lowerBound] }
        return line
    }.joined()
    return noComments.filter { !$0.isWhitespace }
}

// MARK: - [0] The port is the production code

print("══ [0] every ported function matches ContextPolicy.swift ══")
do {
    let prod = source("Agent/Chat/ContextPolicy.swift")
    let port = (try? String(contentsOf: URL(fileURLWithPath: #filePath), encoding: .utf8)) ?? ""
    let portBody = String(port[port.range(of: "enum Port {")!.lowerBound...])
    for name in ["calibrationRatio", "calibrated", "smoothed", "ratio", "ratioAfterOverflow",
                 "carryingOver", "replayCalibration", "warmUpDrop", "inLoopStep"] {
        let p = functionText(name, in: prod), q = functionText(name, in: portBody)
        check("\(name) is verbatim", p != nil && p == q)
    }
    check("constants unchanged",
          prod.contains("static let calibrationRange: ClosedRange<Double> = 0.8...3.0")
          && prod.contains("static let uncalibratedModelMargin = 1.2")
          && prod.contains("static let calibrationFallRate = 0.3"))
}

typealias Step = Port.InLoopStep
let window = 128_000
/// ContextPolicy(contextWindow: 128_000): compact at window − 20K.
func policyCheck(_ measured: Int) -> Port.CheckResult { measured >= window - 20_000 ? .needsCompact : .ok }

func step(_ verdict: Port.CheckResult = .needsCompact, measured: Int, raw: Int? = nil, window w: Int = window,
          canCompact: Bool = false, ratio: Double = 1.0, used: Bool = false) -> Step {
    Port.inLoopStep(verdict: verdict, measured: measured, rawTokens: raw ?? measured, window: w,
                    canCompact: canCompact, ratio: ratio, uncalibratedSendUsed: used)
}

// MARK: - [1] In-loop decision table

print("\n══ [1] in-loop decision table ══")
checkEq("OK always sends", step(.ok, measured: 500_000, canCompact: true), .proceed)
checkEq("EXHAUSTED always stops", step(.exhausted, measured: 10, canCompact: true, ratio: 2.0), .stop)
checkEq("compaction preferred while it has budget and progress", step(measured: 200_000, canCompact: true), .compact)
checkEq("…even when the request would still fit", step(measured: 110_000, canCompact: true), .compact)
checkEq("above the compact line but inside the window → send (the original wedge)", step(measured: window - 1), .sendWithinWindow)
checkEq("the window itself does not fit", step(measured: window), .stop)
checkEq("over the window only by the ratio → one real request", step(measured: 140_000, raw: 100_000, ratio: 1.4), .sendUncalibratedOnce)
checkEq("…already used → stop", step(measured: 140_000, raw: 100_000, ratio: 1.4, used: true), .stop)
checkEq("…ratio ≤ 1: the raw estimate IS the verdict", step(measured: 140_000, raw: 140_000, ratio: 1.0), .stop)
checkEq("…raw estimate over the window too → stop", step(measured: 190_000, raw: window, ratio: 1.4), .stop)
checkEq("an unknown window never stops a request", step(measured: 900_000, window: 0), .sendWithinWindow)
do {
    var stopped: [String] = []
    for raw in [1, 60_000, 100_000, window - 1] { for r in [0.8, 1.0, 1.01, 1.3, 2.0, 3.0] {
        if step(measured: Port.calibrated(raw, ratio: r), raw: raw, ratio: r) == .stop { stopped.append("\(raw)@\(r)") }
    } }
    check("property: valve unused + raw estimate fits → never stopped \(stopped)", stopped.isEmpty)
}

// MARK: - [2] Agent-loop model

print("\n══ [2] agent loop: smoothing × valve × rejection × compaction ══")

/// One session on one model. `realFactor` is how the provider actually counts
/// our raw estimate; `floor` is what compaction can shrink the history to.
/// Order mirrors the in-loop guard → dispatch → calibrateContextSize /
/// noteContextOverflow.
final class LoopModel {
    var raw: Int; let realFactor: Double; var own: Double?; let borrowed: Double?; let floor: Int
    let rearmValveOnSuccess: Bool, rejectionSpendsValve: Bool
    var sent = 0, rejected = 0, compactions = 0
    init(raw: Int, realFactor: Double, own: Double?, borrowed: Double? = nil, floor: Int? = nil,
         rearmValveOnSuccess: Bool = true, rejectionSpendsValve: Bool = true) {
        self.raw = raw; self.realFactor = realFactor; self.own = own; self.borrowed = borrowed
        self.floor = floor ?? raw
        self.rearmValveOnSuccess = rearmValveOnSuccess; self.rejectionSpendsValve = rejectionSpendsValve
    }
    var ratio: Double { Port.ratio(for: "m", known: own.map { ["m": $0] } ?? [:], lastLearned: borrowed) }

    func loop(iterations: Int) -> String {
        var valveUsed = false, noProgress = false, compactionsThisLoop = 0, done = 0, guardCount = 0
        while done < iterations {
            guardCount += 1; if guardCount > 50 { return "spinning" }
            let measured = Port.calibrated(raw, ratio: ratio)
            let s = Port.inLoopStep(verdict: policyCheck(measured), measured: measured, rawTokens: raw, window: window,
                                    canCompact: compactionsThisLoop < 3 && !noProgress, ratio: ratio,
                                    uncalibratedSendUsed: valveUsed)
            switch s {
            case .stop: return "stopped after \(done)"
            case .compact:
                compactionsThisLoop += 1; compactions += 1
                raw = max(floor, raw / 3)
                noProgress = Port.calibrated(raw, ratio: ratio) >= measured
            default:
                if s == .sendUncalibratedOnce { valveUsed = true }
                let real = Int(Double(raw) * realFactor)
                if real >= window {
                    rejected += 1
                    own = Port.ratioAfterOverflow(current: ratio, estimated: raw, requested: real, window: window)
                    if rejectionSpendsValve { valveUsed = true }
                    continue
                }
                sent += 1; done += 1
                own = Port.smoothed(previous: own, sample: Port.calibrationRatio(reported: real, estimated: raw)!)
                noProgress = false
                if rearmValveOnSuccess { valveUsed = false }
                raw += 500
            }
        }
        return "completed"
    }
}

do {
    // Own ratio learned on image-heavy turns; content is now plain text that
    // counts 1:1, and the history cannot be compacted any further.
    let m = LoopModel(raw: 100_000, realFactor: 1.0, own: 1.8)
    checkEq("a slowly falling ratio does not stop a loop the provider keeps accepting", m.loop(iterations: 6), "completed")
    checkEq("…with no rejections", m.rejected, 0)
    let old = LoopModel(raw: 100_000, realFactor: 1.0, own: 1.8, rearmValveOnSuccess: false)
    checkEq("OLD (valve once per loop): stopped right after an accepted same-size request", old.loop(iterations: 6), "stopped after 1")
}
do {
    let m = LoopModel(raw: 95_000, realFactor: 1.4, own: nil, borrowed: 1.0, floor: 20_000)
    checkEq("an under-read on a borrowed ratio still completes", m.loop(iterations: 4), "completed")
    check("…costing at most one rejection (\(m.rejected))", m.rejected <= 1)
    check("…and compacting once the real size is known", m.compactions >= 1)
}
do {
    let m = LoopModel(raw: 120_000, realFactor: 1.3, own: 1.0)
    check("a genuine overflow compaction cannot fix stops instead of retrying", m.loop(iterations: 3).hasPrefix("stopped"))
    checkEq("…after exactly one rejection", m.rejected, 1)
    let old = LoopModel(raw: 120_000, realFactor: 1.3, own: 1.0, rejectionSpendsValve: false)
    _ = old.loop(iterations: 3)
    checkEq("OLD: the valve re-sent the request the provider had just rejected", old.rejected, 2)
}
do {
    let raised = Port.ratioAfterOverflow(current: 1.1, estimated: 100_000, requested: 140_000, window: window)
    checkClose("a rejection raises the ratio to the provider's count", raised, 1.4)
    let afterOutlier = Port.smoothed(previous: raised, sample: 1.0)
    check("one low outlier moves it only part of the way (\(afterOutlier))", afterOutlier > 1.25)
    checkEq("…so the rejected request is still judged over the compact line",
            policyCheck(Port.calibrated(100_000, ratio: afterOutlier)), .needsCompact)
}
do {
    checkClose("overflow with no dispatch recorded (fresh reload) → unchanged",
               Port.ratioAfterOverflow(current: 1.2, estimated: 0, requested: 140_000, window: window), 1.2)
    checkClose("plausibility bound is inclusive at 0.9x window",
               Port.ratioAfterOverflow(current: 1.0, estimated: 100_000, requested: 115_200, window: window), 1.152)
    checkClose("below 0.9x window: ignored, just past the window",
               Port.ratioAfterOverflow(current: 1.0, estimated: 100_000, requested: 115_199, window: window), Double(window) * 1.02 / 100_000)
    checkClose("unknown window: the stated count is trusted",
               Port.ratioAfterOverflow(current: 1.0, estimated: 100_000, requested: 200_000, window: 0), 2.0)
    checkClose("capped at the calibration ceiling",
               Port.ratioAfterOverflow(current: 1.0, estimated: 10_000, requested: 500_000, window: window), 3.0)
}

// MARK: - [3] Reload / revert: replay and carry-over

print("\n══ [3] reload replays the transcript; revert keeps what memory learned ══")
func sample(_ reported: Int, _ estimated: Int = 100_000, model: String? = "a", fixed: Int = 9_000) -> Port.CalibrationSample {
    .init(reported: reported, estimated: estimated, fixedTokens: fixed, modelId: model)
}
do {
    let samples = [sample(140_000), sample(100_000), sample(120_000), sample(90_000), sample(150_000)]
    var live: Double? = nil
    for s in samples { live = Port.smoothed(previous: live, sample: Port.calibrationRatio(reported: s.reported, estimated: s.estimated)!) }
    let replayed = Port.replayCalibration(samples)
    checkClose("a reload reproduces the live ratio sample for sample", replayed.ratios["a"]!, live!)
    checkEq("…counting every paired row", replayed.samples, 5)

    let fwd = Port.replayCalibration([sample(150_000), sample(100_000)]).ratios["a"]!
    let rev = Port.replayCalibration([sample(100_000), sample(150_000)]).ratios["a"]!
    check("replay is order-sensitive — rows must arrive in conversation order", fwd != rev)

    let multi = Port.replayCalibration([sample(100_000, model: "a"), sample(180_000, model: "b"), sample(90_000, model: "a")])
    checkClose("each model replays its own samples only", multi.ratios["a"]!, 1.0 + 0.3 * (0.9 - 1.0))
    checkClose("…b's first sample is taken as-is", multi.ratios["b"]!, 1.8)
    checkClose("…lastLearned follows the newest row", multi.lastLearned!, multi.ratios["a"]!)

    let legacy = Port.replayCalibration([sample(120_000, 0), sample(0, 100_000), sample(130_000, model: nil, fixed: 7_000)])
    checkEq("unpaired rows are skipped, not zeroed", legacy.samples, 1)
    checkClose("…a model-less row informs only lastLearned", legacy.lastLearned!, 1.3)
    check("…and no per-model ratio", legacy.ratios.isEmpty)
    checkEq("…fixed share from the newest paired row", legacy.fixedTokens, 7_000)
    checkEq("nothing to replay → empty state", Port.replayCalibration([]), Port.CalibrationState())
}
do {
    // Measured on device before the fix: a rejection raised the ratio 1.29 →
    // 1.97, revert reloaded the session, the reload reset it to 1.29 from the
    // transcript, and the retry went out uncompacted and was rejected again.
    let inMemory = Port.CalibrationState(ratios: ["a": 1.97], lastLearned: 1.97, fixedTokens: 9_500)
    let reloaded = Port.replayCalibration([sample(129_000)]).carryingOver(inMemory)
    checkClose("revert keeps a ratio that exists only in memory", reloaded.ratios["a"]!, 1.97)
    checkClose("…and its lastLearned", reloaded.lastLearned!, 1.97)
    checkEq("…and its fixed share", reloaded.fixedTokens, 9_500)
    checkEq("…so the retry compacts instead of being rejected again",
            policyCheck(Port.calibrated(100_000, ratio: reloaded.ratios["a"]!)), .needsCompact)

    let rows = Port.replayCalibration([sample(120_000, model: "a"), sample(150_000, model: "b")])
    let merged = rows.carryingOver(Port.CalibrationState(ratios: ["a": 1.6], lastLearned: nil, fixedTokens: 0))
    checkClose("carry-over: memory wins for a", merged.ratios["a"]!, 1.6)
    checkClose("…rows fill in b", merged.ratios["b"]!, 1.5)
    checkClose("…a nil lastLearned keeps the rows'", merged.lastLearned!, rows.lastLearned!)
    checkEq("…a zero fixed share keeps the rows'", merged.fixedTokens, 9_000)
}

// MARK: - [4] Warm-up trim

print("\n══ [4] warm-up trim keeps whole turns ══")
do {
    // user text, assistant tool_use, user tool_result, assistant, user text, assistant
    let starts = [true, false, false, false, true, false]
    let sizes = [100, 50, 20_000, 300, 100, 400]
    checkEq("the whole first round goes, tool result included",
            Port.warmUpDrop(sizes: sizes, startsTurn: starts, restTokens: 5_000, budget: 10_000), 4)
    checkEq("under budget → untouched",
            Port.warmUpDrop(sizes: sizes, startsTurn: starts, restTokens: 0, budget: 100_000), 0)
    checkEq("nothing fits → all dropped",
            Port.warmUpDrop(sizes: sizes, startsTurn: starts, restTokens: 5_000, budget: 10), sizes.count)
    checkEq("exactly at the budget still trims",
            Port.warmUpDrop(sizes: [1_000, 1_000], startsTurn: [true, true], restTokens: 0, budget: 2_000), 1)
    checkEq("one under keeps everything",
            Port.warmUpDrop(sizes: [1_000, 1_000], startsTurn: [true, true], restTokens: 0, budget: 2_001), 0)
    var rng = SystemRandomNumberGenerator()
    var bad: [String] = []
    for _ in 0..<500 {
        let n = Int.random(in: 1...12, using: &rng)
        let st = (0..<n).map { $0 == 0 || Int.random(in: 0..<3, using: &rng) == 0 }
        let sz = (0..<n).map { _ in Int.random(in: 0..<5_000, using: &rng) }
        let d = Port.warmUpDrop(sizes: sz, startsTurn: st, restTokens: Int.random(in: 0..<5_000, using: &rng),
                                budget: Int.random(in: 0..<30_000, using: &rng))
        if !(d == 0 || d == n || st[d]) { bad.append("\(st)→\(d)") }
    }
    check("property: the kept slice starts on a turn, or is empty \(bad.prefix(3))", bad.isEmpty)
}

// MARK: - [5] Wiring

print("\n══ [5] the view model routes its decisions through these functions ══")
do {
    let vm = source("Agent/Chat/AIChatViewModel.swift")
    let compaction = source("Agent/Chat/AIChatViewModel+Compaction.swift")
    let persist = source("Agent/Chat/AIChatViewModel+Persistence.swift")
    check("in-loop exits use the shared table",
          compaction.contains("let step = ContextPolicy.inLoopStep(verdict: .needsCompact,")
          && vm.contains("let settle = settleWithoutCompacting()")
          && vm.contains("if settle.step == .sendWithinWindow {")
          && vm.contains("if settle.step == .sendUncalibratedOnce {"))
    check("compaction only with budget, progress and an anchor",
          vm.contains("if compactionsThisLoop < Self.maxInLoopCompactions,\n                   !lastInLoopCompactionMadeNoProgress,\n                   let anchorId = compactAnchorId {"))
    check("reload replays the transcript", compaction.contains("let seeded = ContextSizeMeter.replayCalibration(samples)"))
    check("carry-over only within the same session",
          compaction.contains("sessionId != nil && calibrationSessionId == sessionId")
          && compaction.contains("let state = learned.map { seeded.carryingOver($0) } ?? seeded"))
    check("warm-up trim uses warmUpDrop", compaction.contains("let drop = ContextSizeMeter.warmUpDrop("))
    check("warm-up decided once per marker (only when actually decided)",
          persist.contains("if fitted.decided {\n                    warmUpDropByMarker[marker.id] = preAnchorPruned.count - fitted.kept.count"))
    check("an accepted request re-arms the valve",
          vm.contains("lastInLoopCompactionMadeNoProgress = false\n                // [T-ctx-valve-rearm]"))
    check("a rejection spends it",
          compaction.contains("        sentPastExtrapolatedLimitThisLoop = true\n        logger.warning(\"[CtxMeter] rejected"))
    let revert = compaction.components(separatedBy: "func revertCompact() async {").dropFirst().first.map { String($0.prefix(4_000)) } ?? ""
    check("no revert mid-response", revert.contains("guard !isProcessing else {"))
    check("no revert mid-compaction (Android parity)", revert.contains("guard !isCompacting else {"))
    check("falls back to the previous marker, not to none",
          revert.contains("let next = await ChatStore.shared.latestCompactMarker(sessionId: sessionId)")
          && revert.contains("self.cachedLatestMarker = next"))
    check("reloads through loadSession, which re-seeds the calibration", revert.contains("await loadSession()"))
}

print(failures == 0 ? "\n✅ ALL PASSED" : "\n❌ \(failures) FAILED")
exit(failures == 0 ? 0 : 1)
