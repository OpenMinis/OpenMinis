// Tests for [T-qwen-thinking-budget-dashscope-scope] — round-2 item M19, iOS half.
//
// Two independent regressions on the same rule, both reported from the field:
//
//   * GH#35 / #641 — DashScope/Bailian rejects a thinking_budget that is not
//     STRICTLY below max_completion_tokens:
//       "max_completion_tokens [64000] must be greater than thinking_budget [65536]"
//       "max_completion_tokens [16384] must be greater than thinking_budget [16384]"
//     …so the bound is `<`, not `<=`, and the margin must be RELATIVE to
//     maxTokens because the limit varies per qwen model (64000 vs 16384).
//     Pins 8db455fff (first clamp) and a5a0de20d (strict + relative margin +
//     the maxTokens < 2 drop).
//
//   * The qwen rule is `scope: .modelPattern("*qwen*")` — it matches on the
//     MODEL NAME and knows nothing about the endpoint, so a self-hosted gateway
//     serving a qwen-named model used to receive DashScope's `extra_body`
//     envelope and answered
//       400 {"code":"UNKNOWN_FIELD","message":"未知请求字段：extra_body"}
//     Pins 672fc4de9: the DUAL send (top-level + extra_body, from 251657006 /
//     8b72080da) is now scoped to isDashScope; everyone else gets
//     `.qwenRootOnly` — `enable_thinking` alone, no extra_body, no
//     thinking_budget.
//
// Ports (structure preserved, so a change to either has to change this file):
//   ThinkingRuleResolver.qwenThinkingBudget           — ThinkingRuleResolver.swift ~L855
//   the .qwenDual / .qwenRootOnly emit branches       — ~L621-639
//   the *qwen* rule + its isDashScope wireFormat pick — ~L294-299
//   OpenAIProvider.isDashScope                        — OpenAIProvider.swift ~L262
//
// Standalone (`swift QwenThinkingBudgetTests.swift`) like its neighbours.
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
func json(_ obj: Any) -> String {
    let d = try! JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys, .fragmentsAllowed])
    return String(data: d, encoding: .utf8)!
}

// MARK: - Ports

enum ThinkingLevel: String, CaseIterable { case off, low, medium, high, xhigh, max, ultra
    var isEnabled: Bool { self != .off }
}
struct Ctx { var modelId: String; var level: ThinkingLevel; var maxTokens: Int; var isDashScope = false }

/// ThinkingRuleResolver.qwenThinkingBudget, verbatim.
func qwenThinkingBudget(_ ctx: Ctx) -> Int {
    var budget: Int = switch ctx.level {
    case .off: 0
    case .low: 4096
    case .medium: 16384
    case .high: 32768
    case .xhigh, .max, .ultra: 65536
    }
    guard budget > 0, ctx.maxTokens > 0 else { return budget }
    if ctx.maxTokens < 2 { return 0 }
    let margin = max(2048, ctx.maxTokens / 8)
    let ceiling = max(1, min(ctx.maxTokens - margin, ctx.maxTokens - 1))
    if budget >= ceiling { budget = ceiling }
    return budget
}

/// The pre-8db455ff shape: the ladder, with no relation to max_completion_tokens.
func preFixBudget(_ level: ThinkingLevel) -> Int {
    switch level { case .off: 0; case .low: 4096; case .medium: 16384; case .high: 32768; case .xhigh, .max, .ultra: 65536 }
}
/// The 8db455ff shape (fixed 2048 margin, `ceiling > 0` guard) — kept so the
/// hole a5a0de20d closed stays visible.
func firstClampBudget(_ ctx: Ctx) -> Int {
    var budget = preFixBudget(ctx.level)
    guard budget > 0, ctx.maxTokens > 0 else { return budget }
    let ceiling = ctx.maxTokens - 2048
    if ceiling > 0, budget >= ceiling { budget = ceiling }
    return budget
}

/// The `.qwenDual` emit branch (DashScope only).
func emitQwenDual(_ ctx: Ctx) -> [String: Any] {
    var body: [String: Any] = [:]
    let enabled = ctx.level.isEnabled
    let budget = qwenThinkingBudget(ctx)
    body["enable_thinking"] = enabled
    if budget > 0 { body["thinking_budget"] = budget }
    body["extra_body"] = [
        "enable_thinking": enabled,
        "thinking_budget": budget > 0 ? budget : NSNull(),
    ] as [String: Any]
    return body
}
/// The `.qwenRootOnly` emit branch (every other endpoint).
func emitQwenRootOnly(_ ctx: Ctx) -> [String: Any] { ["enable_thinking": ctx.level.isEnabled] }

/// OpenAIProvider.isDashScope.
func isDashScope(_ base: String?) -> Bool { (base?.lowercased()).map { $0.contains("dashscope") } ?? false }

/// The registry's wireFormat pick for the `*qwen*` rule, plus its glob.
func qwenGlobMatches(_ modelId: String) -> Bool { modelId.lowercased().contains("qwen") }
enum QwenFormat: String { case dual = "qwenDual", rootOnly = "qwenRootOnly", notQwen = "n/a" }
func qwenFormat(modelId: String, base: String?) -> QwenFormat {
    guard qwenGlobMatches(modelId) else { return .notQwen }
    return isDashScope(base) ? .dual : .rootOnly
}
func emitForQwen(modelId: String, base: String?, level: ThinkingLevel, maxTokens: Int) -> [String: Any] {
    let ctx = Ctx(modelId: modelId, level: level, maxTokens: maxTokens, isDashScope: isDashScope(base))
    switch qwenFormat(modelId: modelId, base: base) {
    case .dual: return emitQwenDual(ctx)
    case .rootOnly: return emitQwenRootOnly(ctx)
    case .notQwen: return [:]
    }
}

let dashscope = "https://dashscope.aliyuncs.com/compatible-mode/v1"

print("▶️  1. thinking_budget is STRICTLY below max_completion_tokens (#35, #641)")
do {
    // The two reported numbers, exactly.
    checkEq("#35: xhigh (65536) vs max 64000 → 56000", qwenThinkingBudget(Ctx(modelId: "qwen3.8-max", level: .xhigh, maxTokens: 64000)), 56000)
    checkEq("#641: medium (16384) vs max 16384 → 14336", qwenThinkingBudget(Ctx(modelId: "qwen3.7-max", level: .medium, maxTokens: 16384)), 14336)
    checkEq("#641: high (32768) vs max 16384 → 14336", qwenThinkingBudget(Ctx(modelId: "qwen3.7-max", level: .high, maxTokens: 16384)), 14336)
    check("PRE-FIX: the ladder ignored max entirely and sent 65536 ≥ 64000", preFixBudget(.xhigh) >= 64000)
    check("PRE-FIX: the equal case 16384 vs 16384 was also rejected", preFixBudget(.medium) >= 16384)

    // The invariant, swept: strictly below max for every level × every max.
    var violations: [String] = []
    for maxTokens in [128000, 64000, 32768, 16384, 8192, 4096, 2048, 1024, 64, 8, 3, 2] {
        for level in ThinkingLevel.allCases where level.isEnabled {
            let b = qwenThinkingBudget(Ctx(modelId: "qwen3-max", level: level, maxTokens: maxTokens))
            if b >= maxTokens { violations.append("\(level.rawValue)@\(maxTokens)→\(b)") }
            if b < 0 { violations.append("negative \(level.rawValue)@\(maxTokens)") }
        }
    }
    checkEq("budget < max for every level × max ∈ {128000…2}", violations, [])

    // The hole a5a0de20d closed: the first clamp skipped itself for tiny maxes.
    checkEq("PRE-a5a0de20d: max 2048 skipped the clamp (ceiling ≤ 0) and sent 65536", firstClampBudget(Ctx(modelId: "q", level: .xhigh, maxTokens: 2048)), 65536)
    check("…now it is strictly below", qwenThinkingBudget(Ctx(modelId: "q", level: .xhigh, maxTokens: 2048)) < 2048)
    checkEq("max 2 → 1 (the maxTokens-1 term, not 0)", qwenThinkingBudget(Ctx(modelId: "q", level: .xhigh, maxTokens: 2)), 1)
    checkEq("max 1 → 0, so the field is dropped (no room for a positive budget)", qwenThinkingBudget(Ctx(modelId: "q", level: .xhigh, maxTokens: 1)), 0)

    // Margin is RELATIVE: it scales with the model's own ceiling.
    checkEq("margin is max(2048, max/8): 128000 → 112000", qwenThinkingBudget(Ctx(modelId: "q", level: .max, maxTokens: 128000)), 65536)
    checkEq("…and bites once the ladder exceeds it: 32768 → 28672", qwenThinkingBudget(Ctx(modelId: "q", level: .xhigh, maxTokens: 32768)), 28672)
    checkEq("small max uses the 2048 floor, not max/8: 8192 → 6144", qwenThinkingBudget(Ctx(modelId: "q", level: .high, maxTokens: 8192)), 6144)

    // Under-budget requests are untouched — the clamp only ever cuts.
    checkEq("low (4096) vs max 64000 passes through", qwenThinkingBudget(Ctx(modelId: "q", level: .low, maxTokens: 64000)), 4096)
    checkEq("medium (16384) vs max 64000 passes through", qwenThinkingBudget(Ctx(modelId: "q", level: .medium, maxTokens: 64000)), 16384)
    checkEq("high (32768) vs max 64000 passes through", qwenThinkingBudget(Ctx(modelId: "q", level: .high, maxTokens: 64000)), 32768)
    // maxTokens not provided (title-gen reference) → no clamp at all.
    checkEq("maxTokens 0 (\"not provided\") skips the clamp", qwenThinkingBudget(Ctx(modelId: "q", level: .xhigh, maxTokens: 0)), 65536)
    checkEq("negative maxTokens also skips it", qwenThinkingBudget(Ctx(modelId: "q", level: .xhigh, maxTokens: -1)), 65536)
    // Off never carries a budget, whatever max says.
    checkEq("off → 0 at every max", Set([64000, 16384, 0, 2].map { qwenThinkingBudget(Ctx(modelId: "q", level: .off, maxTokens: $0)) }), Set([0]))
    // The clamp never walks UP: a low tier is not inflated to the ceiling.
    check("low is never raised toward the ceiling", qwenThinkingBudget(Ctx(modelId: "q", level: .low, maxTokens: 128000)) == 4096)
    // Monotonic in the level, so the picker's ladder still means something.
    let ladder = ThinkingLevel.allCases.filter(\.isEnabled).map { qwenThinkingBudget(Ctx(modelId: "q", level: $0, maxTokens: 64000)) }
    check("budget is non-decreasing across the level ladder", zip(ladder, ladder.dropFirst()).allSatisfy { $0 <= $1 })
}

print("▶️  2. the dual send is DashScope-only (672fc4de9)")
do {
    checkEq("official DashScope base → qwenDual", qwenFormat(modelId: "qwen3.8-max", base: dashscope), .dual)
    checkEq("the reporter's self-hosted gateway → qwenRootOnly", qwenFormat(modelId: "qwen3.8-max", base: "https://tokenrhythm.studio/v1"), .rootOnly)
    checkEq("OpenRouter-hosted qwen → qwenRootOnly", qwenFormat(modelId: "qwen/qwen3-max", base: "https://openrouter.ai/api/v1"), .rootOnly)
    checkEq("nil base (official OpenAI) → qwenRootOnly", qwenFormat(modelId: "qwen3-max", base: nil), .rootOnly)
    checkEq("a vLLM/SGLang self-host → qwenRootOnly", qwenFormat(modelId: "Qwen3-235B-A22B", base: "http://192.168.1.9:8000/v1"), .rootOnly)
    checkEq("DASHSCOPE uppercase in the URL still matches", qwenFormat(modelId: "qwen3-max", base: "https://DASHSCOPE.aliyuncs.com/v1"), .dual)
    checkEq("a non-qwen model on DashScope is not claimed by this rule", qwenFormat(modelId: "deepseek-v4-flash", base: dashscope), .notQwen)

    // On the wire.
    checkEq("DashScope + xhigh + max 64000: dual, clamped",
            json(emitForQwen(modelId: "qwen3.8-max", base: dashscope, level: .xhigh, maxTokens: 64000)),
            #"{"enable_thinking":true,"extra_body":{"enable_thinking":true,"thinking_budget":56000},"thinking_budget":56000}"#)
    checkEq("third-party gateway + xhigh: enable_thinking ONLY — the 400'd fields are gone",
            json(emitForQwen(modelId: "qwen3.8-max", base: "https://tokenrhythm.studio/v1", level: .xhigh, maxTokens: 64000)),
            #"{"enable_thinking":true}"#)
    let relay = emitForQwen(modelId: "qwen3.8-max", base: "https://tokenrhythm.studio/v1", level: .xhigh, maxTokens: 64000)
    check("…no extra_body (UNKNOWN_FIELD)", relay["extra_body"] == nil)
    check("…no thinking_budget (probed as a second 400 on the same relay)", relay["thinking_budget"] == nil)
    checkEq("third-party gateway + off: enable_thinking false, still nothing else",
            json(emitForQwen(modelId: "qwen3.8-max", base: "https://tokenrhythm.studio/v1", level: .off, maxTokens: 64000)),
            #"{"enable_thinking":false}"#)
    // OFF on DashScope: the toggle is still sent as false (silence would mean
    // "on" for qwen3), and the budget key disappears rather than going out 0.
    checkEq("DashScope + off: false both places, budget null in extra_body, key absent at root",
            json(emitForQwen(modelId: "qwen3-max", base: dashscope, level: .off, maxTokens: 64000)),
            #"{"enable_thinking":false,"extra_body":{"enable_thinking":false,"thinking_budget":null}}"#)
    check("…root thinking_budget is omitted, not 0", emitForQwen(modelId: "qwen3-max", base: dashscope, level: .off, maxTokens: 64000)["thinking_budget"] == nil)
    // The same clamp governs both copies — they can never disagree.
    let dual = emitQwenDual(Ctx(modelId: "qwen3-max", level: .max, maxTokens: 16384, isDashScope: true))
    let nested = dual["extra_body"] as! [String: Any]
    checkEq("root and extra_body carry the SAME clamped budget", "\(dual["thinking_budget"]!)", "\(nested["thinking_budget"]!)")
    checkEq("…which is 14336 for max 16384", dual["thinking_budget"] as? Int, 14336)
    // The whole point of #35: the dual send is only a 400 risk where extra_body
    // is not understood, so a DashScope request must keep it.
    check("DashScope still receives extra_body (251657006 is not reverted)", dual["extra_body"] != nil)
}

print("▶️  3. shipping sources still carry the pinned lines")
do {
    let res = source("Providers/Thinking/ThinkingRuleResolver.swift")
    let op = source("Providers/OpenAI/OpenAIProvider.swift")
    if res.isEmpty || op.isEmpty { print("  ⏭  sources not readable") } else {
        check("relative margin", res.contains("let margin = max(2048, ctx.maxTokens / 8)"))
        check("ceiling forces budget strictly below max", res.contains("let ceiling = max(1, min(ctx.maxTokens - margin, ctx.maxTokens - 1))"))
        check("maxTokens < 2 drops the budget", res.contains("if ctx.maxTokens < 2 { return 0 }"))
        check("the clamp only ever cuts", res.contains("if budget >= ceiling { budget = ceiling }"))
        check("root thinking_budget written only when positive", res.contains("if budget > 0 { body[\"thinking_budget\"] = budget }"))
        check("extra_body sends NSNull rather than 0 when off", res.contains("\"thinking_budget\": budget > 0 ? budget : NSNull(),"))
        check("the *qwen* rule picks its wire format from isDashScope", res.contains("wireFormat: ctx.isDashScope ? .qwenDual : .qwenRootOnly"))
        check("…and labels itself accordingly", res.contains("label: ctx.isDashScope ? \"qwen-dashscope\" : \"qwen-root-only\""))
        check("qwenRootOnly writes enable_thinking and nothing else", res.contains("case .qwenRootOnly:"))
        if let r = res.range(of: "case .qwenRootOnly:") {
            let branch = String(res[r.upperBound...].prefix(900))
            // Code only — the branch's doc comment names both fields while
            // explaining why neither is written.
            let code = (branch.components(separatedBy: "case .booleanToggle").first ?? branch)
                .split(separator: "\n")
                .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
                .joined(separator: "\n")
            check("…no extra_body written in that branch", !code.contains("extra_body"))
            check("…no thinking_budget written in that branch", !code.contains("thinking_budget"))
            check("…it does write enable_thinking", code.contains("body[\"enable_thinking\"] = ctx.level.isEnabled"))
        }
        check("isDashScope is a host check on the base URL", op.contains("return base.contains(\"dashscope\")"))
        check("resolver context carries isDashScope", res.contains("var isDashScope: Bool"))
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
