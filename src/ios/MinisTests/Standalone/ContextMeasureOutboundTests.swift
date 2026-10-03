#!/usr/bin/env swift
// [T-ctx-measure-outbound] Capacity decisions judge the request that is about
// to be sent, not the size of the previous one.
//
// The bug: checkContextBeforeSend took max(localEstimate, lastAPIReport). The
// report describes the PREVIOUS request and only the next successful call ever
// replaced it. So after a compaction the stale pre-compaction size won the
// max(); the in-loop guard — which runs BEFORE that next call — re-compacted on
// it (folding the user's just-sent message into a second summary), hit its cap
// and stopped with "Context is full and could not be compacted further" having
// made zero API calls. Reopening the session re-seeded the same number from the
// message it was stamped on, so the session stayed wedged. A revert went the
// other way: the last report came from the compacted context and under-read the
// restored history, while chars/3.5 read CJK at under a third of its size.
//
// The fix measures the outbound history (+ system prompt + tools) and uses the
// provider's count only to CALIBRATE that estimate — report ÷ estimate of the
// same request — so compaction, offload, revert and relaunch all show up in the
// very next decision.
//
// Run: swift ContextMeasureOutboundTests.swift
//
// Convention: a bare `swift` script like its neighbours (deps/libs/libish_emu.a
// is device-arm64 only). The meter is ported verbatim from ContextPolicy.swift,
// the flow is modelled on AIChatViewModel's call order, and section [9] greps
// the real sources so a rewrite fails here instead of passing a stale copy.
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

// MARK: - Port: ContextSizeMeter (Agent/Chat/ContextPolicy.swift)

func estimateTokens(_ text: String) -> Int {
    var letters = 0, digits = 0, asciiOther = 0, nonASCII = 0
    var copy = text
    copy.withUTF8 { bytes in
        for b in bytes {
            if b >= 0x80 {
                if b & 0xC0 != 0x80 { nonASCII += 1 }   // lead byte = one scalar
            } else if (b >= 65 && b <= 90) || (b >= 97 && b <= 122) {
                letters += 1
            } else if b >= 48 && b <= 57 {
                digits += 1
            } else {
                asciiOther += 1
            }
        }
    }
    let tokens = Double(letters) / 4.5 + Double(digits) / 2.0
        + Double(asciiOther) * 0.35 + Double(nonASCII)
    return Int(tokens.rounded(.up))
}
/// The previous scalar-walking form, kept as the reference the byte scan must match.
func estimateTokensByScalars(_ text: String) -> Int {
    var letters = 0, digits = 0, asciiOther = 0, nonASCII = 0
    for scalar in text.unicodeScalars {
        let v = scalar.value
        if v >= 128 {
            nonASCII += 1
        } else if (65...90).contains(v) || (97...122).contains(v) {
            letters += 1
        } else if (48...57).contains(v) {
            digits += 1
        } else {
            asciiOther += 1
        }
    }
    let tokens = Double(letters) / 4.5 + Double(digits) / 2.0
        + Double(asciiOther) * 0.35 + Double(nonASCII)
    return Int(tokens.rounded(.up))
}
let calibrationRange: ClosedRange<Double> = 0.8...3.0
func calibrationRatio(reported: Int, estimated: Int) -> Double? {
    guard reported > 0, estimated > 0 else { return nil }
    let raw = Double(reported) / Double(estimated)
    return min(max(raw, calibrationRange.lowerBound), calibrationRange.upperBound)
}
func calibrated(_ estimated: Int, ratio: Double) -> Int { Int((Double(estimated) * ratio).rounded(.up)) }
let uncalibratedModelMargin = 1.2
func ratio(for modelId: String?, known: [String: Double], lastLearned: Double?) -> Double {
    if let modelId, let own = known[modelId] { return own }
    guard let lastLearned else { return 1.0 }
    return min(max(lastLearned, 1.0) * uncalibratedModelMargin, calibrationRange.upperBound)
}
let overflowMarkers = [
    "maximum context length", "context length exceeded", "context_length_exceeded",
    "reduce the length of the messages", "too many tokens", "prompt is too long",
    "request too large", "exceeds the maximum", "input is too long",
    "exceeds the context window", "input exceeds the context",
    "上下文长度", "超出最大长度", "内容过长",
]
func isContextOverflow(_ text: String) -> Bool {
    let lower = text.lowercased()
    let codes = (try? NSRegularExpression(pattern: #"\[(\d{3})\]"#))?
        .matches(in: lower, range: NSRange(lower.startIndex..., in: lower))
        .compactMap { Range($0.range(at: 1), in: lower).flatMap { Int(lower[$0]) } } ?? []
    if !codes.isEmpty && !codes.contains(where: { $0 == 400 || $0 == 413 }) { return false }
    return overflowMarkers.contains { lower.contains($0) }
}
func requestedTokens(inOverflowMessage text: String) -> Int? {
    let cleaned = text.replacingOccurrences(of: #"(?<=\d),(?=\d{3})"#, with: "", options: .regularExpression)
    let regex = try? NSRegularExpression(pattern: #"\d{4,}"#)
    let range = NSRange(cleaned.startIndex..., in: cleaned)
    let values = regex?.matches(in: cleaned, range: range).compactMap {
        Range($0.range, in: cleaned).flatMap { Int(cleaned[$0]) }
    } ?? []
    return values.filter { $0 >= 1000 }.max()
}
func ratioAfterOverflow(current: Double, estimated: Int, requested: Int?, window: Int) -> Double {
    guard estimated > 0 else { return current }
    let plausible = requested.flatMap { r -> Int? in
        guard window > 0 else { return r }
        return (Double(r) >= Double(window) * 0.9 && Double(r) <= Double(window) * 4) ? r : nil
    }
    let target = plausible.map(Double.init) ?? Double(max(window, 1)) * 1.02
    let implied = target / Double(estimated)
    return min(max(current, implied), calibrationRange.upperBound)
}

// MARK: - Port: ContextPolicy (≥128K tier + user cap) — ContextPolicy.swift

enum CheckResult: String { case ok, needsCompact, exhausted }
func policyCheck(_ tokens: Int, window: Int) -> CheckResult {
    let compactThreshold = window >= 128_000 ? window - 20_000 : Int(Double(window) * 0.85)
    if tokens >= compactThreshold { return .needsCompact }
    if tokens >= window { return .needsCompact }
    return .ok
}

// MARK: - Model of the session
//
// Token sizes are what the MEASUREMENT sees. `providerFactor` is how much more
// the provider's tokenizer counts than our estimate — the thing calibration
// learns. The "provider" reports `estimate × providerFactor` for whatever was
// actually sent.

let window = 128_000
let providerFactor = 1.4
let fixed = 9_000                          // system prompt + tool schemas (estimate)

struct Session {
    var history: [Int]                     // estimated tokens per outbound message
    var fullHistoryBeforeCompaction: [Int] = []
    var ratio = 1.0
    var lastDispatchEstimate = 0
    var apiCalls = 0
    var compactions = 0
    var stampedReport = 0                  // latestContextTokens on the last turn
    var stampedEstimate = 0                // estimatedRequestTokens on the last turn

    var outboundEstimate: Int { history.reduce(0, +) + fixed }
    /// measureOutboundContextTokens()
    var measured: Int { calibrated(outboundEstimate, ratio: ratio) }

    mutating func dispatch() {
        lastDispatchEstimate = outboundEstimate                         // recordContextDispatch
        apiCalls += 1
        let report = Int(Double(outboundEstimate) * providerFactor)     // provider's count
        if let r = calibrationRatio(reported: report, estimated: lastDispatchEstimate) { ratio = r }
        stampedReport = report
        stampedEstimate = lastDispatchEstimate
    }
    mutating func compact() {
        fullHistoryBeforeCompaction = history
        history = [3_000] + history.suffix(2)                           // summary + kept tail
        compactions += 1
    }
    mutating func revert() { history = fullHistoryBeforeCompaction + history.suffix(from: 3) }
    /// seedContextCalibration() on reload: the pair, never a raw size.
    mutating func reload() {
        ratio = calibrationRatio(reported: stampedReport, estimated: stampedEstimate) ?? 1.0
    }
}

/// The in-loop guard as fixed: measure before/after, no retry without
/// progress, and send while the request still fits the window.
func runTurnFixed(_ s: inout Session, maxCompactions: Int = 3) -> String {
    var noProgress = false
    while true {
        switch policyCheck(s.measured, window: window) {
        case .ok:
            s.dispatch(); return "sent"
        case .needsCompact, .exhausted:
            if s.compactions < maxCompactions, !noProgress {
                let before = s.measured
                s.compact()
                noProgress = s.measured >= before
                continue
            }
            if s.measured < window { s.dispatch(); return "sent" }
            return "exhausted"
        }
    }
}

/// The OLD rule: max(chars/3.5-style estimate, last report), cap then abort.
/// `staleReport` is the live mirror / stamped value, replaced only by dispatch.
func runTurnOld(history: inout [Int], staleReport: inout Int, apiCalls: inout Int,
                compactions: inout Int, underRead: Double = 1.0) -> String {
    var inLoop = 0
    while true {
        let estimate = Int(Double(history.reduce(0, +)) * underRead)   // no fixed share
        switch policyCheck(max(estimate, staleReport), window: window) {
        case .ok:
            apiCalls += 1
            staleReport = Int(Double(history.reduce(0, +) + fixed) * providerFactor)
            return "sent"
        case .needsCompact, .exhausted:
            if inLoop < 3 {
                inLoop += 1; compactions += 1
                if history.count > 3 { history = [3_000] + history.suffix(2) }   // nothing left → no-op
                continue
            }
            return "exhausted"
        }
    }
}

// MARK: - [1] The estimator no longer under-reads CJK by 3x

print("\n══ [1] the estimator reads CJK at its real size ══")
do {
    // 64 characters; cl100k_base counts 61 tokens (tiktoken, measured).
    let zh = "我们需要排查自动压缩逻辑，如果会话消息触发自动压缩阈值并自动压缩后继续发消息，此时会更新之前成功对话消息的 usage 数据吗？"
    let real = 61
    let est = estimateTokens(zh)
    let old = Int(Double(zh.count) / 3.5)
    check("new estimate within the fitted band (0.66–1.18x of real)",
          Double(est) / Double(real) >= 0.66 && Double(est) / Double(real) <= 1.18)
    check("OLD chars/3.5 read it at under a third of real (\(old) of \(real))", Double(old) / Double(real) < 0.34)
    checkEq("empty string is zero", estimateTokens(""), 0)
    check("digits cost more than letters (numbers split into short tokens)",
          estimateTokens("1234567890") > estimateTokens("abcdefghij"))
}

// MARK: - [2] Compact, then send: exactly one API call, no second compaction

print("\n══ [2] after a compaction the next request goes out ══")
do {
    // 13 messages; the last request measured ~108.8k by the provider.
    var s = Session(history: Array(repeating: 5_500, count: 13) + [1_000])
    s.dispatch()
    check("the session really is over the compact line", policyCheck(s.measured, window: window) == .needsCompact)
    let before = (calls: s.apiCalls, compactions: s.compactions)
    s.compact()                                                    // compactAndSend
    let outcome = runTurnFixed(&s)                                 // drained prompt → loop
    checkEq("the drained prompt is SENT", outcome, "sent")
    checkEq("…with exactly one API call", s.apiCalls - before.calls, 1)
    checkEq("…and no in-loop re-compaction", s.compactions - before.compactions, 1)
    check("the measurement after compaction is under the line", policyCheck(s.measured, window: window) == .ok)

    // Falsification: the old rule on the same state.
    var h = Array(repeating: 5_500, count: 13) + [1_000]
    var report = Int(Double(h.reduce(0, +) + fixed) * providerFactor)
    var calls = 0, compactions = 0
    h = [3_000] + h.suffix(2)                                      // the send-time compaction
    let oldOutcome = runTurnOld(history: &h, staleReport: &report, apiCalls: &calls, compactions: &compactions)
    checkEq("OLD: the turn ended exhausted", oldOutcome, "exhausted")
    checkEq("OLD: with zero API calls", calls, 0)
    check("OLD: after re-compacting on the stale number", compactions >= 1)
}

// MARK: - [3] Reopening a compacted session does not re-wedge it

print("\n══ [3] reopen after compaction ══")
do {
    var s = Session(history: Array(repeating: 5_500, count: 13) + [1_000])
    s.dispatch()
    s.compact()
    s.reload()                          // stamped pair predates the compaction
    checkEq("the next send after reopening is ok", policyCheck(s.measured, window: window), .ok)
    check("the ratio (not the size) was carried over", abs(s.ratio - providerFactor) < 0.01)
    // Old: liveContextTokensForCapacity re-seeded to the stamped raw report.
    check("OLD: the stamped raw report still judged it over the line",
          policyCheck(max(s.history.reduce(0, +), s.stampedReport), window: window) == .needsCompact)
}

// MARK: - [4] Revert restores the full size, it is not under-read

print("\n══ [4] revert after post-compaction turns ══")
do {
    var s = Session(history: Array(repeating: 5_500, count: 13) + [1_000])
    s.dispatch()
    s.compact()
    s.history += [2_000, 2_000]; s.dispatch()   // turns on the compacted context
    let smallReport = s.stampedReport
    s.revert()
    s.reload()                                  // revertCompact → loadSession
    check("the restored history is judged over the line", policyCheck(s.measured, window: window) == .needsCompact)
    check("…at least as large as before the compaction plus the new turns",
          s.measured >= Int(Double(Array(repeating: 5_500, count: 13).reduce(0, +) + 1_000 + 4_000 + fixed) * providerFactor) - 10)
    // Old: max(chars/3.5 over the restored history — CJK read at ~0.3x — , the
    // report from the COMPACTED context).
    let oldJudged = max(Int(Double(s.history.reduce(0, +)) * 0.3), smallReport)
    checkEq("OLD: under-read as ok, so an over-length request would go out", policyCheck(oldJudged, window: window), .ok)
}

// MARK: - [5] Offload is seen by the very next decision

print("\n══ [5] offload lowers the measurement the guard reads ══")
do {
    var s = Session(history: Array(repeating: 5_500, count: 13) + [1_000])
    s.dispatch()
    let staleReport = s.stampedReport
    // offloadContextIfNeeded replaces six large tool results with short stubs in place.
    for i in 0..<6 { s.history[i] = 120 }
    checkEq("after offload the guard answers ok — no compaction", policyCheck(s.measured, window: window), .ok)
    checkEq("OLD: the pre-offload report still forced a compaction",
            policyCheck(max(s.history.reduce(0, +), staleReport), window: window), .needsCompact)
}

// MARK: - [6] A compaction that cannot shrink the request is not retried

print("\n══ [6] no-progress compaction ══")
do {
    // Everything compactable is already a summary: compact() changes nothing.
    var s = Session(history: [3_000, 60_000, 30_000])
    s.ratio = 1.1
    let r = runTurnFixed(&s)
    checkEq("above the threshold but inside the window → sent", r, "sent")
    checkEq("…after ONE compaction attempt, not three", s.compactions, 1)

    var over = Session(history: [3_000, 90_000, 40_000])
    over.ratio = 1.1
    checkEq("over the window with nothing to compact → exhausted", runTurnFixed(&over), "exhausted")
    checkEq("…again after one attempt", over.compactions, 1)
}

// MARK: - [7] Calibration

print("\n══ [7] calibration pairing and clamps ══")
do {
    checkEq("report ÷ estimate of the same request", calibrationRatio(reported: 140, estimated: 100), 1.4)
    checkEq("absurd reports are clamped high", calibrationRatio(reported: 1_000_000, estimated: 100), 3.0)
    checkEq("…and low — an under-reporting upstream cannot shrink the estimate by more than 20%",
            calibrationRatio(reported: 1, estimated: 100), 0.8)
    checkEq("a genuine over-read (the estimator's worst, 1.18x) is still followed",
            calibrationRatio(reported: 100, estimated: 118)!, 100.0 / 118.0)
    check("no report → no calibration", calibrationRatio(reported: 0, estimated: 100) == nil)
    check("no estimate → no calibration", calibrationRatio(reported: 100, estimated: 0) == nil)
    checkEq("with nothing changed, the measurement reproduces the report",
            calibrated(100_000, ratio: calibrationRatio(reported: 138_800, estimated: 100_000)!), 138_800)
}

// MARK: - [8] Persistence: the pair round-trips; old rows stay readable

print("\n══ [8] StoredTokenUsage ══")
do {
    struct StoredTokenUsage: Codable, Equatable {       // ChatStore.swift, verbatim fields
        var inputTokens: Int
        var outputTokens: Int
        var cacheCreationTokens: Int
        var cacheReadTokens: Int
        var latestContextTokens: Int?
        var estimatedRequestTokens: Int? = nil
        var estimatedFixedTokens: Int? = nil
    }
    let legacy = #"{"inputTokens":10,"outputTokens":5,"cacheCreationTokens":0,"cacheReadTokens":0,"latestContextTokens":120000}"#
    let old = try? JSONDecoder().decode(StoredTokenUsage.self, from: Data(legacy.utf8))
    check("a row written before the pair decodes", old != nil)
    check("…with no pair, so it does not seed calibration", old?.estimatedRequestTokens == nil)
    let fresh = StoredTokenUsage(inputTokens: 1, outputTokens: 2, cacheCreationTokens: 0, cacheReadTokens: 0,
                                 latestContextTokens: 140_000, estimatedRequestTokens: 100_000, estimatedFixedTokens: 9_000)
    let back = try? JSONDecoder().decode(StoredTokenUsage.self, from: try! JSONEncoder().encode(fresh))
    checkEq("the pair round-trips", back, fresh)
}

// MARK: - [9] Source invariants

print("\n══ [9] source invariants ══")
do {
    let policy = source("Agent/Chat/ContextPolicy.swift")
    let compaction = source("Agent/Chat/AIChatViewModel+Compaction.swift")
    let vm = source("Agent/Chat/AIChatViewModel.swift")
    let persist = source("Agent/Chat/AIChatViewModel+Persistence.swift")
    let store = source("Agent/Chat/ChatStore.swift")
    let models = source("Agent/Chat/ChatModels.swift")
    guard ![policy, compaction, vm, persist, store, models].contains(where: \.isEmpty) else {
        check("sources readable", false); exit(1)
    }

    // The port is the shipping code.
    check("estimator weights match the port",
          policy.contains("let tokens = Double(letters) / 4.5 + Double(digits) / 2.0")
          && policy.contains("+ Double(asciiOther) * 0.35 + Double(nonASCII)"))
    check("calibration band matches the port", policy.contains("calibrationRange: ClosedRange<Double> = 0.8...3.0"))

    // The guard judges the measurement; the stale raw report is gone.
    check("the check judges the outbound measurement", compaction.contains("let measured = m.measured\n        let result = policy.check(estimatedTokens: measured, contextWindow: contextWindow)"))
    check("…not max(estimate, report)", !compaction.contains("max(estimated, apiReported)"))
    check("the raw-report mirror no longer exists", !vm.contains("var liveContextTokensForCapacity"))
    check("the measurement reads the compaction-aware history",
          compaction.contains("let history = ContextSizeMeter.estimateTokens(effectiveAgentHistoryUncounted())")
          && compaction.contains("ContextSizeMeter.calibrated(history + contextFixedTokens, ratio: ratio)"))

    // Calibration is paired with the SAME request.
    let lines = vm.components(separatedBy: "\n")
    let dispatchIdx = lines.firstIndex { $0.contains("let dispatchInputTokens = recordContextDispatch(history: contextHistory, model: activeModel)") }
    let budgetIdx = lines.firstIndex { $0.contains("contextHistory = applyRequestImageBudget(contextHistory)") }
    check("the dispatch estimate is recorded", dispatchIdx != nil)
    check("…on the pre-budget history, like every other measurement",
          dispatchIdx != nil && budgetIdx != nil && dispatchIdx! < budgetIdx!)
    check("the report calibrates on arrival, for the model that served it",
          vm.contains("if calibrateContextSize(reportedTokens: turnUsage.latestContextTokens, servedModelId: servedModelId) {"))
    check("…and the served model is stamped with the pair", vm.contains("turnUsage.calibrationModelId = servedModelId"))
    check("…and the pair is stamped for persistence",
          vm.contains("turnUsage.estimatedRequestTokens = lastDispatchEstimate"))
    check("the fixed share is measured every iteration",
          vm.contains("contextFixedTokens = ContextSizeMeter.estimateFixedTokens(systemPrompt: userSystemPrompt, tools: tools)"))

    // Every size consumer in the loop reads the same number.
    check("offload is judged on the measurement",
          vm.contains("offloadContextIfNeeded(model: activeModelForOffload, lastContextTokens: measureOutboundContextTokens())"))
    // 5 sizing sites + the [CtxMeter] dispatch log, which reports the same max_tokens.
    checkEq("max_tokens / fallback sizing read the dispatch measurement (5 sites + dispatch log)",
            vm.components(separatedBy: "lastContextTokens: dispatchInputTokens").count - 1, 6)
    check("no loop site sizes a request from the stale report",
          !vm.contains("lastContextTokens: turnUsage.latestContextTokens"))

    // In-loop guard: progress, then window.
    check("in-loop compaction is measured before and after",
          vm.contains("lastInLoopCompactionMadeNoProgress = sizeAfter >= sizeBefore"))
    check("…and not retried without progress", vm.contains("!lastInLoopCompactionMadeNoProgress,"))
    check("budget spent but within the window → send", vm.contains("if settle.step == .sendWithinWindow {"))

    // Reload, compaction and revert.
    check("load seeds calibration from the stamped pair", persist.contains("if seedContextCalibration() {"))
    check("…and never re-seeds a raw size", !persist.contains("liveContextTokensForCapacity ="))
    let compactTail = compaction.range(of: "offloadContextIfNeeded(model: activeModel, lastContextTokens: 0, force: true)")
        .map { String(compaction[$0.upperBound...].prefix(400)) } ?? ""
    // [T-ctx-usage-after-compact] The refresh now runs inside
    // announceContextUsageAfterCompaction(), which also re-issues the
    // placeholder line; it must still start from the measured size.
    check("the glow is refreshed after a compaction",
          compactTail.contains("announceContextUsageAfterCompaction()")
            && vm.contains("        })?.id\n        // Live measured size, not the message-derived path: it also works when\n        // no turn has reported usage yet.\n        publishMeasuredContextUsage()"))
    let revertTail = compaction.range(of: "func revertCompact() async {")
        .map { String(compaction[$0.upperBound...].prefix(3000)) } ?? ""
    check("…and after a revert", revertTail.contains("await loadSession()\n        // [T-ctx-measure-outbound]")
          && revertTail.contains("publishMeasuredContextUsage()"))

    // Persistence wiring.
    check("StoredTokenUsage carries the pair as optionals",
          store.contains("var estimatedRequestTokens: Int? = nil") && store.contains("var estimatedFixedTokens: Int? = nil"))
    check("it is restored on load", store.contains("estimatedRequestTokens: usage.estimatedRequestTokens ?? 0"))
    check("it is written on persist",
          persist.contains("estimatedRequestTokens: $0.estimatedRequestTokens > 0 ? $0.estimatedRequestTokens : nil"))
    check("TokenUsage holds it", models.contains("var estimatedRequestTokens: Int = 0"))
}

// MARK: - [10] Byte scan == scalar walk

print("\n══ [10] the UTF-8 scan counts exactly what the scalar walk did ══")
do {
    let samples = ["", "abc", "ls -la 1234", "自动压缩后继续", "😀 emoji", "é accented", "mixed 中文 and 123 😀 é\n\t{}",
                   String(repeating: "drwxr-xr-x  13 alice staff 416 Sep 22\n", count: 50)]
    check("identical on ASCII, CJK, emoji, accented, whitespace", samples.allSatisfy { estimateTokens($0) == estimateTokensByScalars($0) })
}

// MARK: - [11] Per-model calibration (scenario 7: switching model mid-session)

print("\n══ [11] a model is judged by its own ratio, or a margin over a borrowed one ══")
do {
    let known = ["gpt-5": 1.05, "claude-5": 1.30]
    checkEq("own ratio when known", ratio(for: "claude-5", known: known, lastLearned: 1.05), 1.30)
    checkEq("switching back uses the other model's own ratio, not the last one", ratio(for: "gpt-5", known: known, lastLearned: 1.30), 1.05)
    checkEq("an unseen model borrows the last ratio WITH the margin", ratio(for: "gemini-4", known: known, lastLearned: 1.05), 1.05 * 1.2)
    checkEq("a borrowed ratio below 1 is not used to shrink", ratio(for: "x", known: [:], lastLearned: 0.85), 1.2)
    checkEq("…and the margin respects the ceiling", ratio(for: "x", known: [:], lastLearned: 2.9), 3.0)
    checkEq("a session with no usage at all stays at 1.0", ratio(for: "x", known: [:], lastLearned: nil), 1.0)

    // The reported case: A (ratio 1.05) near its limit; switch to B whose
    // tokenizer counts 30% more. Same history, same 128K window.
    let estimate = 95_000                                // outbound estimate incl. fixed
    let realOnB = Int(Double(estimate) * 1.36)          // 129,200 — over B's window
    let oldJudged = Int(Double(estimate) * 1.05)        // A's count carried over: 99,750
    checkEq("OLD: B judged by A's count → ok → sent over B's window", check(oldJudged), .ok)
    check("…which B rejects", realOnB > window)
    let newJudged = calibrated(estimate, ratio: ratio(for: "B", known: ["A": 1.05], lastLearned: 1.05))
    checkEq("NEW: the borrowed ratio's margin compacts first instead", check(newJudged), .needsCompact)
}
func check(_ t: Int) -> CheckResult { policyCheck(t, window: window) }

// MARK: - [12] A context-length rejection raises the ratio

print("\n══ [12] provider rejections recalibrate ══")
do {
    let openai = "Provider error: [400] This model's maximum context length is 128000 tokens. However, your messages resulted in 131,244 tokens."
    let anthropic = "Provider error: [400] prompt is too long: 205000 tokens > 200000 maximum"
    let issue133 = "Provider error: [400] [context_length_exceeded] Your input exceeds the context window of this model"
    let rate = "Provider error: [429] Rate limit reached: too many tokens per minute"
    let group = "⚠️ A (X): Provider error: [429] slow down\n⚠️ B (Y): Provider error: [400] prompt is too long"
    check("OpenAI wording", isContextOverflow(openai))
    check("Anthropic wording", isContextOverflow(anthropic))
    check("OpenMinis#133 wording", isContextOverflow(issue133))
    check("a 429 'too many tokens' is a rate limit, not an overflow", !isContextOverflow(rate))
    check("a group trail counts when any member was a 400 overflow", isContextOverflow(group))
    check("no status stated, marker present → overflow", isContextOverflow("prompt is too long"))

    checkEq("stated count parsed (thousands separator)", requestedTokens(inOverflowMessage: openai), 131_244)
    checkEq("the larger number is the request", requestedTokens(inOverflowMessage: anthropic), 205_000)
    check("no count stated → nil", requestedTokens(inOverflowMessage: issue133) == nil)

    // estimate of the rejected request 100k at ratio 1.05; provider said 131,244
    let raised = ratioAfterOverflow(current: 1.05, estimated: 100_000, requested: 131_244, window: 128_000)
    checkEq("ratio follows the stated count", raised, 1.31244)
    check("…so that request now measures over the compact line", check(calibrated(100_000, ratio: raised)) == .needsCompact)
    checkEq("no count → just enough to reach the window",
            ratioAfterOverflow(current: 1.05, estimated: 100_000, requested: nil, window: 128_000), 1.3056)
    checkEq("a rejection never LOWERS the ratio",
            ratioAfterOverflow(current: 1.5, estimated: 100_000, requested: 120_000, window: 128_000), 1.5)
    checkEq("an implausible number (a request id) is ignored, not trusted",
            ratioAfterOverflow(current: 1.0, estimated: 100_000, requested: 123_456_789, window: 128_000), 1.3056)
}

// MARK: - [13] No permanent wedge on an extrapolated verdict

print("\n══ [13] an over-the-window verdict that is only an extrapolation sends once ══")
do {
    // Ratio 2.4 learned on shell output (estimator under-reads it), history
    // now prose after compaction: calibrated 132k > window, raw 55k fits.
    let raw = 55_000
    let ratioNow = 2.4
    var sentOnce = false
    func decide() -> String {
        if calibrated(raw, ratio: ratioNow) < window { return "send" }
        if !sentOnce, ratioNow > 1.0, raw < window { sentOnce = true; return "send-once" }
        return "stop"
    }
    checkEq("first time: sent once for the provider to decide", decide(), "send-once")
    checkEq("never twice in one loop", decide(), "stop")
    // A real overflow then raises the ratio from ground truth, so a later
    // stop is backed by the provider's count, not the extrapolation.
}

// MARK: - [14] Source invariants for the optimisation round

print("\n══ [14] source invariants — optimisation round ══")
do {
    let policy = source("Agent/Chat/ContextPolicy.swift")
    let compaction = source("Agent/Chat/AIChatViewModel+Compaction.swift")
    let vm = source("Agent/Chat/AIChatViewModel.swift")
    let store = source("Agent/Chat/ChatStore.swift")
    check("byte scan in the shipping meter", policy.contains("if b & 0xC0 != 0x80 { nonASCII += 1 }"))
    check("persisted messages are cached by id AND content shape",
          policy.contains("guard let id = msg.dbMessageId else { return uncachedEstimate(msg) }")
          && policy.contains("shape += \"|r\\(content.utf8.count)"))
    check("…and an offloaded argument changes the key", policy.contains("offloaded ? \"o\" : \"\""))
    check("ratios are kept per model", vm.contains("var contextCalibrationRatios: [String: Double] = [:]"))
    check("the check uses the current model's ratio", compaction.contains("let ratio = override ?? contextCalibrationRatio(for: model)"))
    check("seeding replays one smoothed ratio per model",
          compaction.contains("let seeded = ContextSizeMeter.replayCalibration(samples)"))
    check("the model id is persisted with the pair", store.contains("var calibrationModelId: String? = nil"))
    check("every loop error passes the overflow hook",
          vm.contains("try await runAgentLoopCore(resumingAt: existingMsgIdx, committedBlocks: committedBlocks)")
          && vm.contains("noteContextOverflow(errorText: text, modelId: nil)"))
    check("the extrapolation valve is once per loop",
          vm.contains("if settle.step == .sendUncalibratedOnce {")
          && vm.contains("sentPastExtrapolatedLimitThisLoop = false"))
}

// MARK: - [15] Warm-up trimming (a compaction must be able to help)

print("\n══ [15] warm-up turns are trimmed only when they keep the request over the line ══")
do {
    // Raw-estimate units, as trimWarmUpToFit works: budget = line/ratio − fixed.
    // Turns are (userText, reply) pairs; tool results ride as extra user msgs.
    enum Role { case userText, assistant, toolResult }
    func trim(_ warm: [(Role, Int)], rest: Int, budget: Int) -> [(Role, Int)] {
        var kept = warm
        guard kept.map(\.1).reduce(0, +) + rest >= budget else { return kept }
        while !kept.isEmpty, kept.map(\.1).reduce(0, +) + rest >= budget {
            kept.removeFirst()
            while let f = kept.first, f.0 != .userText { kept.removeFirst() }
        }
        return kept
    }
    let warm: [(Role, Int)] = [(.userText, 10), (.assistant, 9_000), (.userText, 10), (.assistant, 9_000),
                               (.userText, 10), (.assistant, 300), (.toolResult, 200), (.assistant, 100)]
    checkEq("under budget → untouched (prompt cache prefix unchanged)", trim(warm, rest: 500, budget: 40_000).count, warm.count)
    let t = trim(warm, rest: 500, budget: 12_000)
    checkEq("over budget → the oldest turn is dropped, and no more than needed", t.count, 6)
    check("…and the kept slice still starts on a user-text turn", t.first?.0 == .userText)
    check("…never on an orphaned tool result", !t.contains { $0.0 == .toolResult } || t.first?.0 == .userText)
    checkEq("nothing fits → the whole warm-up goes, summary + rest remain", trim(warm, rest: 500, budget: 100).count, 0)

    let persist = source("Agent/Chat/AIChatViewModel+Persistence.swift")
    let compaction = source("Agent/Chat/AIChatViewModel+Compaction.swift")
    check("the builder trims the warm-up before appending it",
          persist.contains("let fitted = trimWarmUpToFit(preAnchorPruned, rest: postAnchor, summaryText: summaryText)"))
    check("only when over the line", compaction.contains("guard ContextSizeMeter.estimateTokens(warmUp) + restTokens >= budget else { return (warmUp, true) }"))
    // [T-ctx-warmup-trim-undecided] Nothing to measure against → undecided.
    check("no entry / window / fixed size yet → undecided, not 'drop nothing'",
          compaction.contains("guard let entry = resolveCurrentEntry() else { return (warmUp, false) }")
          && compaction.contains("guard resolved.window > 0, contextFixedTokens > 0 else { return (warmUp, false) }"))
    check("drops whole user-TEXT turns (behaviour: CompactionDecisionTests [4])", compaction.contains("let drop = ContextSizeMeter.warmUpDrop("))

    // Decided once per marker: re-deciding each request slid the warm-up one
    // turn per message on device (constant 49,354-token requests, prefix
    // changing every turn → no prompt-cache hits).
    struct Req { let prefix: [Int]; let size: Int }
    func requests(sticky: Bool, turns: Int) -> [Req] {
        let warm = [9_000, 9_000, 9_000]; let line = 49_500; var post = [700]
        var decided: Int? = nil; var out: [Req] = []
        for _ in 0..<turns {
            post.append(9_000)
            var w = warm
            if sticky, let d = decided { w = Array(w.dropFirst(d)) }
            else {
                var drop = 0
                while !w.isEmpty, w.reduce(0, +) + post.reduce(0, +) >= line { w.removeFirst(); drop += 1 }
                if sticky { decided = drop }
            }
            out.append(Req(prefix: w, size: w.reduce(0, +) + post.reduce(0, +)))
        }
        return out
    }
    let slid = requests(sticky: false, turns: 3), fixed = requests(sticky: true, turns: 3)
    check("OLD (re-decided): the request prefix changed every turn", Set(slid.map { $0.prefix.count }).count > 1)
    check("NEW (per marker): the prefix is identical across turns", Set(fixed.map { $0.prefix.count }).count == 1)
    check("…and the request grows, so compaction fires at the line", fixed.last!.size > fixed.first!.size)
    let persist2 = source("Agent/Chat/AIChatViewModel+Persistence.swift")
    check("the decision is cached per marker id",
          persist2.contains("if let drop = warmUpDropByMarker[marker.id] {")
          && persist2.contains("if fitted.decided {\n                    warmUpDropByMarker[marker.id] = preAnchorPruned.count - fitted.kept.count"))
}

// MARK: - [16] A same-session reload keeps what was learned

print("\n══ [16] reloading the same session keeps an overflow-raised ratio ══")
do {
    // Port of seedContextCalibration's merge rule.
    func seed(persisted: [String: Double], inMemory: [String: Double], sameSession: Bool) -> [String: Double] {
        var out = persisted
        if sameSession { out.merge(inMemory) { _, mem in mem } }
        return out
    }
    // Measured on device: a rejection raised ctx-c to 1.97; a reload re-seeded
    // only the persisted 1.29, and the retry was rejected again.
    let reloaded = seed(persisted: ["ctx-c": 1.29], inMemory: ["ctx-c": 1.97], sameSession: true)
    checkEq("same session: the raised ratio survives the reload", reloaded["ctx-c"], 1.97)
    let switched = seed(persisted: ["ctx-a": 1.04], inMemory: ["ctx-c": 1.97], sameSession: false)
    check("another session starts from its own transcript", switched["ctx-c"] == nil)
    // Retry after the rejection (device run, 64K window): 36,356 raw × 1.97 ≥ window → compacts.
    check("…so the retry measures over the window and compacts instead of re-sending",
          policyCheck(calibrated(36_356, ratio: reloaded["ctx-c"]!), window: 64_000) == .needsCompact)

    let compaction = source("Agent/Chat/AIChatViewModel+Compaction.swift")
    check("seeding merges in-memory values for the same session",
          compaction.contains("sessionId != nil && calibrationSessionId == sessionId")
          && compaction.contains("let state = learned.map { seeded.carryingOver($0) } ?? seeded"))
}

// MARK: - [17] Smoothing: rise at once, fall gradually

print("\n══ [17] the ratio is an asymmetric weighted average ══")
do {
    let fall = 0.3
    func smoothed(_ previous: Double?, _ sample: Double) -> Double {   // port of ContextSizeMeter.smoothed
        guard let previous else { return sample }
        if sample >= previous { return sample }
        return previous + fall * (sample - previous)
    }
    checkEq("first sample for a model is taken as-is", smoothed(nil, 1.04), 1.04)
    checkEq("a HIGHER sample applies at once (under-reading is the unsafe side)", smoothed(1.04, 1.46), 1.46)
    let dipped = smoothed(1.46, 0.90)
    check("a single LOW sample moves it only 30% of the way (1.46 → \(String(format: "%.3f", dipped)))",
          abs(dipped - 1.292) < 0.001)
    // The unsmoothed rule on the same outlier: a relay under-counting once.
    let window = 64_000, estimate = 45_000
    check("…so one under-count no longer drops a near-limit request below the line",
          policyCheck(calibrated(estimate, ratio: dipped), window: window) == .needsCompact
          && policyCheck(calibrated(estimate, ratio: 0.90), window: window) == .ok)
    var r = 1.46
    for _ in 0..<4 { r = smoothed(r, 1.0) }
    check("a real, sustained drop settles within 4 responses (1.46 → \(String(format: "%.3f", r)))", r < 1.12)
    // Reload replays samples oldest → newest through the same rule.
    let samples = [1.04, 1.10, 0.96, 0.97]
    let replayed = samples.dropFirst().reduce(samples[0]) { smoothed($0, $1) }
    check("replay reproduces the live value, not the newest raw sample", abs(replayed - 0.97) > 0.05)

    let policy = source("Agent/Chat/ContextPolicy.swift")
    let compaction = source("Agent/Chat/AIChatViewModel+Compaction.swift")
    let vm = source("Agent/Chat/AIChatViewModel.swift")
    check("shipping rule matches the port", policy.contains("static let calibrationFallRate = 0.3")
          && policy.contains("return previous + calibrationFallRate * (sample - previous)"))
    check("live calibration blends into the model's OWN ratio only",
          compaction.contains("let updated = ContextSizeMeter.smoothed(previous: own, sample: sample)"))

    // [CtxMeter] logs: every decision, dispatch, response, rejection,
    // compaction, revert, seed and warm-up trim.
    for tag in ["[CtxMeter] decide", "[CtxMeter] actual", "[CtxMeter] rejected", "[CtxMeter] compacted",
                "[CtxMeter] reverted", "[CtxMeter] seed", "[CtxMeter] warmup"] {
        check("logs \(tag)", compaction.contains(tag))
    }
    check("logs [CtxMeter] dispatch", vm.contains("[CtxMeter] dispatch"))
    check("the in-loop decision is labelled", vm.contains("checkContextBeforeSend(site: \"in-loop\")"))
    check("the response log carries the prediction error", compaction.contains("err=\\(String(format: \"%+.1f\", err))%"))
}

print("")
if failures == 0 {
    print("✅ ALL PASSED")
    exit(0)
} else {
    print("❌ \(failures) FAILURE(S)")
    exit(1)
}
