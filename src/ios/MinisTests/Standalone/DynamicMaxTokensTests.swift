// Tests for [T-ctx-dynamic-max-tokens] — the max_tokens clamp and the
// mid-loop capacity re-check (backlog item T12, iOS half).
//
// Pins 9b0606192 (T-ctx-user-cap / T-ctx-overflow-hard-stop /
// T-ctx-trust-api-usage) and the [T-chat-auto-compact-inloop] guard.
// Issues #119 (half-written file_write when max_tokens shrank), #326, #74.
//
// What must never regress:
//   * an input that already exceeds the window must not be turned back into a
//     1024-token request by the floor and sent anyway (the 108%-over case);
//   * capacity is judged by max(local estimate, API-reported input) on EVERY
//     iteration of a tool loop, not only at the send entry point;
//   * a user cap on a large model triggers proportional compaction (85%).
//
// Ports:
//   * ContextPolicy            — src/ios/Agent/Chat/ContextPolicy.swift (verbatim)
//   * resolvedContextWindow    — AIChatViewModel+Misc.swift ~L280
//   * dynamicMaxTokens         — AIChatViewModel+Misc.swift ~L315 (arithmetic only;
//                                the overflow WARNING becomes a flag)
//   * checkContextBeforeSend   — AIChatViewModel+Compaction.swift ~L20
//   * the in-loop switch       — AIChatViewModel.swift ~L6135
//
// Standalone (`swift DynamicMaxTokensTests.swift`) like its neighbours.
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

// MARK: - Verbatim copy of ContextPolicy

struct ContextPolicy {
    let offloadThreshold: Int
    let offloadTarget: Int
    let compactThreshold: Int
    let exhaustedOnly: Bool
    let manualCompactAllowed: Bool

    init(contextWindow: Int, isUserCap: Bool = false) {
        if isUserCap {
            offloadThreshold = Int(Double(contextWindow) * 0.70)
            offloadTarget = Int(Double(contextWindow) * 0.55)
            compactThreshold = Int(Double(contextWindow) * 0.85)
            exhaustedOnly = false
            manualCompactAllowed = true
            return
        }
        if contextWindow < 32_000 {
            offloadThreshold = 0; offloadTarget = 0; compactThreshold = 0
            exhaustedOnly = true; manualCompactAllowed = false
        } else if contextWindow < 64_000 {
            offloadThreshold = contextWindow - 10_000; offloadTarget = contextWindow - 15_000
            compactThreshold = 0; exhaustedOnly = true; manualCompactAllowed = true
        } else if contextWindow < 128_000 {
            offloadThreshold = contextWindow - 20_000; offloadTarget = contextWindow - 30_000
            compactThreshold = contextWindow - 10_000; exhaustedOnly = false; manualCompactAllowed = true
        } else {
            offloadThreshold = contextWindow - 40_000; offloadTarget = contextWindow - 60_000
            compactThreshold = contextWindow - 20_000; exhaustedOnly = false; manualCompactAllowed = true
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

// MARK: - Ports of the view-model arithmetic

/// AIChatViewModel+Misc.resolvedContextWindow(for:) with the store lookup
/// replaced by an explicit `override` (the group's contextLimitTokens).
func resolvedContextWindow(native: Int, override: Int?) -> (window: Int, isUserCap: Bool) {
    guard let override, override > 0, override < Int.max else { return (native, false) }
    guard native > 0 else { return (override, true) }
    return override < native ? (override, true) : (native, false)
}

let globalMaxTokensCeiling = 128_000

struct MaxTokensResult: Equatable {
    let value: Int
    /// The `remaining <= 0` branch — production logs a WARNING here.
    let overflowed: Bool
    let remaining: Int
}

/// AIChatViewModel+Misc.dynamicMaxTokens, arithmetic only.
func dynamicMaxTokens(modelMaxOutput: Int?, providerDefault: Int, contextWindow: Int,
                      lastContextTokens: Int, estimated: Int) -> MaxTokensResult {
    let modelOrProvider = modelMaxOutput ?? providerDefault
    let upperBound = min(globalMaxTokensCeiling, modelOrProvider)
    guard contextWindow > 0 else { return MaxTokensResult(value: upperBound, overflowed: false, remaining: Int.max) }
    let inputTokens = lastContextTokens > 0 ? lastContextTokens : estimated
    let remaining = contextWindow - inputTokens
    let overflowed = remaining <= 0
    let floor = min(1024, upperBound)
    let clamped = max(remaining, floor)
    let result = min(upperBound, clamped)
    return MaxTokensResult(value: result, overflowed: overflowed, remaining: remaining)
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

/// AIChatViewModel+Compaction.checkContextBeforeSend: the calibrated measurement.
func checkContext(window: Int, isUserCap: Bool, estimated: Int, apiReported: Int) -> ContextPolicy.CheckResult {
    guard window > 0 else { return .ok }
    let policy = ContextPolicy(contextWindow: window, isUserCap: isUserCap)
    return policy.check(estimatedTokens: measuredTokens(estimated: estimated, apiReported: apiReported), contextWindow: window)
}

/// The send path as the loop composes it: the guard runs BEFORE the clamp, so
/// an overflowing input never reaches dynamicMaxTokens on its way to the wire.
enum Prepared: Equatable { case compact, exhausted, send(maxTokens: Int) }
func prepareRequest(window: Int, isUserCap: Bool, estimated: Int, apiReported: Int, upper: Int = 16_384) -> Prepared {
    switch checkContext(window: window, isUserCap: isUserCap, estimated: estimated, apiReported: apiReported) {
    case .needsCompact: return .compact
    case .exhausted: return .exhausted
    case .ok:
        // [T-ctx-measure-outbound] max_tokens is sized from the same measurement
        // the guard judged (`dispatchInputTokens` in the loop).
        let r = dynamicMaxTokens(modelMaxOutput: upper, providerDefault: 8192, contextWindow: window,
                                 lastContextTokens: measuredTokens(estimated: estimated, apiReported: apiReported),
                                 estimated: estimated)
        return .send(maxTokens: r.value)
    }
}

print("▶️  1. window 128k, input 130k → the request is never sent at 1024")
do {
    let raw = dynamicMaxTokens(modelMaxOutput: 16_384, providerDefault: 8192, contextWindow: 128_000,
                               lastContextTokens: 130_000, estimated: 60_000)
    check("the clamp itself flags the overflow (remaining <= 0)", raw.overflowed)
    checkEq("remaining is negative", raw.remaining, -2_000)
    // The floor still yields 1024 — refusing to send is the GUARD's job, and
    // the guard runs first. What matters is the composed outcome.
    checkEq("send path compacts instead of sending", prepareRequest(window: 128_000, isUserCap: false, estimated: 60_000, apiReported: 130_000), .compact)
    check("…and specifically never emits a 1024-token request",
          prepareRequest(window: 128_000, isUserCap: false, estimated: 60_000, apiReported: 130_000) != .send(maxTokens: 1024))
    // Exhausted-only tier (no auto-compact): past the ceiling must be .exhausted, not .ok.
    checkEq("a <32K tier past its ceiling is exhausted, not ok",
            prepareRequest(window: 30_000, isUserCap: false, estimated: 31_000, apiReported: 0), .exhausted)
    checkEq("a 32K–64K tier past its ceiling compacts (manual compaction is viable)",
            checkContext(window: 40_000, isUserCap: false, estimated: 40_000, apiReported: 0), .needsCompact)
}

print("▶️  2. estimate 60k, API-reported 120k → judged at 120k")
do {
    checkEq("calibration from a 120k report crosses the 108k line", checkContext(window: 128_000, isUserCap: false, estimated: 60_000, apiReported: 120_000), .needsCompact)
    checkEq("the estimate alone would have said ok (the pre-fix failure)", checkContext(window: 128_000, isUserCap: false, estimated: 60_000, apiReported: 0), .ok)
    checkEq("a fresh session (api 0) still uses the estimate", checkContext(window: 128_000, isUserCap: false, estimated: 110_000, apiReported: 0), .needsCompact)
    checkEq("API-reported input feeds the clamp too",
            dynamicMaxTokens(modelMaxOutput: 16_384, providerDefault: 8192, contextWindow: 128_000, lastContextTokens: 120_000, estimated: 60_000).value, 8_000)
}

print("▶️  3. the 5th tool round crosses 85% of a user cap → in-loop compaction")
do {
    // Harness of the in-loop switch: re-evaluate the policy each iteration,
    // compact in place at most maxInLoopCompactions times, never reset turns.
    struct Loop {
        let window = 100_000; let isUserCap = true
        var compactionsThisLoop = 0
        var compactedAtRound: [Int] = []
        var apiTokens = 0
        static let maxInLoopCompactions = 3
        mutating func round(_ n: Int, grow: Int) {
            apiTokens += grow
            // [T-ctx-measure-outbound] The guard judges the measured outbound
            // request, so the wire growth is fed in as the measurement (ratio 1).
            switch checkContext(window: window, isUserCap: isUserCap, estimated: apiTokens, apiReported: 0) {
            case .ok: break
            case .needsCompact:
                if compactionsThisLoop < Self.maxInLoopCompactions {
                    compactionsThisLoop += 1
                    compactedAtRound.append(n)
                    apiTokens = 40_000   // the summary shrinks the wire size
                }
            case .exhausted: break
            }
        }
    }
    var loop = Loop()
    for n in 1...6 { loop.round(n, grow: 17_000) }
    checkEq("compaction fired exactly once, at round 5 (85k ≥ 85%)", loop.compactedAtRound, [5])
    check("rounds 1–4 stayed under the line", !loop.compactedAtRound.contains(where: { $0 < 5 }))
    // Keep growing: the cap bounds how many in-loop compactions one turn may do.
    for n in 7...40 { loop.round(n, grow: 17_000) }
    checkEq("in-loop compactions are capped", loop.compactionsThisLoop, Loop.maxInLoopCompactions)
}

print("▶️  4. a 1M model capped at 32k → needsCompact at 27.2k")
do {
    let r = resolvedContextWindow(native: 1_000_000, override: 32_000)
    checkEq("the cap binds", r.window, 32_000)
    check("…and is flagged as a user cap", r.isUserCap)
    let policy = ContextPolicy(contextWindow: r.window, isUserCap: r.isUserCap)
    checkEq("compact threshold is 85%", policy.compactThreshold, 27_200)
    checkEq("offload threshold is 70%", policy.offloadThreshold, 22_400)
    check("auto-compact stays available under a small cap", !policy.exhaustedOnly && policy.manualCompactAllowed)
    checkEq("just under the line is ok", checkContext(window: r.window, isUserCap: r.isUserCap, estimated: 27_199, apiReported: 0), .ok)
    checkEq("on the line compacts", checkContext(window: r.window, isUserCap: r.isUserCap, estimated: 27_200, apiReported: 0), .needsCompact)
    // The same 32k treated as a NATIVE window would refuse to auto-compact —
    // that is the difference isUserCap exists to express.
    checkEq("a native 32k window would be exhausted-only", checkContext(window: 32_000, isUserCap: false, estimated: 27_200, apiReported: 0), .exhausted)
}

print("▶️  5. resolvedContextWindow edge cases")
do {
    checkEq("a cap above the native window does not raise the ceiling", resolvedContextWindow(native: 1_000_000, override: 2_000_000).window, 1_000_000)
    check("…and is not a user cap", !resolvedContextWindow(native: 1_000_000, override: 2_000_000).isUserCap)
    check("Int.max is the 'Unlimited' sentinel", resolvedContextWindow(native: 200_000, override: Int.max) == (200_000, false))
    check("zero override means no override", resolvedContextWindow(native: 200_000, override: 0) == (200_000, false))
    check("unknown native window takes the cap", resolvedContextWindow(native: 0, override: 64_000) == (64_000, true))
    checkEq("no override, no native → 0 → guards skip", resolvedContextWindow(native: 0, override: nil).window, 0)
}

print("▶️  6. the floor never inflates a deliberately tiny upper bound")
do {
    checkEq("upper 32, remaining 5 → 32", dynamicMaxTokens(modelMaxOutput: 32, providerDefault: 8192, contextWindow: 1000, lastContextTokens: 995, estimated: 0).value, 32)
    checkEq("upper 32, remaining negative → 32 (not 1024)", dynamicMaxTokens(modelMaxOutput: 32, providerDefault: 8192, contextWindow: 1000, lastContextTokens: 2000, estimated: 0).value, 32)
    checkEq("a model claiming 200k output is capped by the global ceiling", dynamicMaxTokens(modelMaxOutput: 200_000, providerDefault: 8192, contextWindow: 1_000_000, lastContextTokens: 0, estimated: 1000).value, 128_000)
    checkEq("model override wins over provider default", dynamicMaxTokens(modelMaxOutput: 4096, providerDefault: 64_000, contextWindow: 200_000, lastContextTokens: 1000, estimated: 0).value, 4096)
    checkEq("no model value → provider default", dynamicMaxTokens(modelMaxOutput: nil, providerDefault: 64_000, contextWindow: 200_000, lastContextTokens: 1000, estimated: 0).value, 64_000)
    checkEq("unknown window → upper bound untouched", dynamicMaxTokens(modelMaxOutput: nil, providerDefault: 64_000, contextWindow: 0, lastContextTokens: 999_999, estimated: 0).value, 64_000)
}

print("▶️  7. shipping sources still carry the pinned lines")
do {
    let misc = source("Agent/Chat/AIChatViewModel+Misc.swift")
    let compaction = source("Agent/Chat/AIChatViewModel+Compaction.swift")
    let vm = source("Agent/Chat/AIChatViewModel.swift")
    let policy = source("Agent/Chat/ContextPolicy.swift")
    if misc.isEmpty || compaction.isEmpty || vm.isEmpty || policy.isEmpty { print("  ⏭  sources not readable") } else {
        check("floor is min(1024, upperBound)", misc.contains("let floor = min(1024, upperBound)"))
        check("overflow branch is present and loud", misc.contains("if remaining <= 0 {") && misc.contains("EXCEEDS context window"))
        check("global ceiling is 128k", misc.contains("static let globalMaxTokensCeiling: Int = 128_000"))
        check("cap applies as min(modelWindow, groupLimit)", misc.contains("return override < native ? (override, true) : (native, false)"))
        check("send guard judges the calibrated outbound measurement",
              compaction.contains("let measured = m.measured\n        let result = policy.check(estimatedTokens: measured, contextWindow: contextWindow)")
              && !compaction.contains("max(estimated, apiReported)"))
        check("the stale live-usage mirror is gone", !vm.contains("liveContextTokensForCapacity ="))
        checkEq("the guard runs at send AND inside the loop", vm.components(separatedBy: "switch checkContextBeforeSend(").count - 1, 2)
        check("in-loop compaction is capped at 3", vm.contains("static let maxInLoopCompactions = 3"))
        check("user cap → proportional 85%", policy.contains("compactThreshold = Int(Double(contextWindow) * 0.85)"))
        check("hard stop at the ceiling", policy.contains("if contextWindow > 0, estimatedTokens >= contextWindow {"))
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
