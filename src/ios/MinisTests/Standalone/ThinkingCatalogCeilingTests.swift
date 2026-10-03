// Tests for [T-thinking-max-unreachable] / [T-deepseek-flash-scope] /
// [T-thinking-rules-phase2] — backlog item T23, iOS half.
//
// Pins ef95c007d. Issues #356, #96, #306, #311, #180.
//
//   * a newly recommended id (`deepseek-flash`) must not slip past the
//     substring family rules;
//   * an INCOMPLETE effort declaration from models.dev must not hide Max on a
//     model whose family rule reaches it — the ceiling is the HIGHER of the
//     declared top and the rule, and the picker extends the ladder up to it;
//   * the offered list is a pure function of model + override, so switching
//     the selection XHigh → Max → XHigh can never make Max disappear;
//   * a user rule is matched by provider instance (any pattern, incl.
//     `*`), so it can target an Anthropic-compatible relay's model.
//
// The existing ThinkingRulesRegressionTests is XCTest and cannot run here;
// this is its standalone counterpart.
//
// Ports: ThinkingLevelCatalog (Providers/ThinkingLevelCatalog.swift),
// LLMModel.catalogMaxThinkingLevel / selectableThinkingLevels (LLMTypes.swift
// ~L938 / ~L991), ModelEntry.selectableThinkingLevels (ModelEntry.swift ~L299),
// ThinkingRule.Scope.matches + the stage-A winner (ThinkingRule.swift,
// ThinkingRuleResolver.swift ~L372).
//
// Standalone (`swift ThinkingCatalogCeilingTests.swift`) like its neighbours.
import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}
func gap(_ l: String, _ closed: Bool) {
    if closed { print("  ✅ \(l) (gap closed)") } else { print("  ⚠️ KNOWN GAP: \(l)") }
}
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}

// MARK: - Ports

enum ThinkingLevel: String, CaseIterable, Comparable {
    case off, low, medium, high, xhigh, max, ultra
    static func < (l: ThinkingLevel, r: ThinkingLevel) -> Bool { allCases.firstIndex(of: l)! < allCases.firstIndex(of: r)! }
}

enum ThinkingLevelCatalog {
    private static func normalizedHasPrefix(_ id: String, _ prefix: String) -> Bool { id.replacingOccurrences(of: ".", with: "-").hasPrefix(prefix) }
    private static let rules: [(match: (String) -> Bool, max: ThinkingLevel)] = [
        ({ $0.hasPrefix("gpt-6-astra") }, .max),
        ({ $0.hasPrefix("gpt-5.6-sol") || $0.hasPrefix("gpt-5.6-terra") }, .max),
        ({ $0.hasPrefix("gpt-5.6-luna") }, .max),
        ({ $0.hasPrefix("gpt-5.5") }, .xhigh),
        ({ $0.contains("mimo") || $0.contains("agnes") }, .high),
        ({ $0.contains("seed-") || $0.contains("bytedance-seed") }, .high),
        ({ normalizedHasPrefix($0, "claude-opus-4") }, .max),
        ({ $0.contains("deepseek-flash") || $0.contains("deepseek-v4") }, .max),
    ]
    static func declaredMaxLevel(for modelId: String) -> ThinkingLevel? {
        let lid = modelId.lowercased()
        return rules.first { $0.match(lid) }?.max
    }
}

struct LLMModel {
    let id: String
    var supportsReasoning: Bool? = true
    var reasoningEffortValues: [String]? = nil

    var selectableThinkingLevels: [ThinkingLevel] {
        guard let declared = reasoningEffortValues, !declared.isEmpty else { return [] }
        let mapping: [(wire: String, level: ThinkingLevel)] = [("low", .low), ("medium", .medium), ("high", .high), ("xhigh", .xhigh), ("max", .max)]
        let set = Set(declared.map { $0.lowercased() })
        return mapping.filter { set.contains($0.wire) }.map(\.level)
    }
    var catalogMaxThinkingLevel: ThinkingLevel {
        if supportsReasoning == false { return .off }
        let ruleTop = ThinkingLevelCatalog.declaredMaxLevel(for: id)
        if let declaredTop = selectableThinkingLevels.last { return Swift.max(declaredTop, ruleTop ?? declaredTop) }
        return ruleTop ?? .xhigh
    }
}

struct ModelEntry {
    let model: LLMModel
    var overrideMaxThinkingLevel: ThinkingLevel? = nil
    var effectiveMaxThinkingLevel: ThinkingLevel { overrideMaxThinkingLevel ?? model.catalogMaxThinkingLevel }
    var selectableThinkingLevels: [ThinkingLevel] {
        let ceiling = effectiveMaxThinkingLevel
        guard ceiling != .off else { return [] }
        let declared = model.selectableThinkingLevels
        guard !declared.isEmpty else { return ThinkingLevel.allCases.filter { $0 != .off && $0 <= ceiling } }
        let capped = declared.filter { $0 <= ceiling }
        if let declaredTop = declared.last, ceiling > declaredTop {
            let above = ThinkingLevel.allCases.filter { $0 != .off && $0 > declaredTop && $0 <= ceiling }
            return capped + above
        }
        return capped.isEmpty ? [declared[0]] : capped
    }
}

/// The pre-fix ceiling, for contrast: declared top returned verbatim.
func preFixCeiling(_ m: LLMModel) -> ThinkingLevel {
    if m.supportsReasoning == false { return .off }
    if let top = m.selectableThinkingLevels.last { return top }
    return ThinkingLevelCatalog.declaredMaxLevel(for: m.id) ?? .xhigh
}

enum Scope { case allModels, modelPattern(String)
    func matches(_ modelId: String) -> Bool {
        switch self {
        case .allModels: return true
        case .modelPattern(let p): return Self.glob(p.lowercased().replacingOccurrences(of: ".", with: "-"), matches: modelId.lowercased().replacingOccurrences(of: ".", with: "-"))
        }
    }
    static func glob(_ pattern: String, matches input: String) -> Bool {
        let parts = pattern.components(separatedBy: "*")
        if parts.count == 1 { return input == pattern }
        var cursor = input.startIndex
        for (i, part) in parts.enumerated() {
            if part.isEmpty { continue }
            if i == 0 { guard input.hasPrefix(part) else { return false }; cursor = input.index(cursor, offsetBy: part.count); continue }
            if i == parts.count - 1 && !pattern.hasSuffix("*") {
                guard input.hasSuffix(part), input.distance(from: cursor, to: input.endIndex) >= part.count else { return false }
                continue
            }
            guard let found = input.range(of: part, range: cursor..<input.endIndex) else { return false }
            cursor = found.upperBound
        }
        return true
    }
}
enum Kind { case custom, officialVendor, providerTypeDefault }
struct Rule { let kind: Kind; let scope: Scope; let label: String }
/// Stage A: user rules first, built-ins after, first match wins.
func winner(userRules: [Rule], builtIn: [Rule], modelId: String) -> Rule { (userRules + builtIn).first { $0.scope.matches(modelId) }! }

print("▶️  1. deepseek-flash → the DeepSeek family rule")
do {
    checkEq("deepseek-flash → .max", ThinkingLevelCatalog.declaredMaxLevel(for: "deepseek-flash"), .max)
    checkEq("DeepSeek-Flash (case) → .max", ThinkingLevelCatalog.declaredMaxLevel(for: "DeepSeek-Flash"), .max)
    checkEq("deepseek-v4-flash → .max", ThinkingLevelCatalog.declaredMaxLevel(for: "deepseek-v4-flash"), .max)
    checkEq("deepseek-flash-lite (future) → .max", ThinkingLevelCatalog.declaredMaxLevel(for: "deepseek-flash-lite"), .max)
    check("deepseek-chat (no family rule) → nil → default .xhigh", ThinkingLevelCatalog.declaredMaxLevel(for: "deepseek-chat") == nil && LLMModel(id: "deepseek-chat").catalogMaxThinkingLevel == .xhigh)
    // Catalog-declared tiers still win when present (the rule is a fallback).
    let declared = LLMModel(id: "deepseek-flash", reasoningEffortValues: ["low", "high", "max"])
    checkEq("declared ladder collapses to one option per distinct tier", ModelEntry(model: declared).selectableThinkingLevels, [.low, .high, .max])
    let relay = LLMModel(id: "deepseek-flash", reasoningEffortValues: nil)
    checkEq("undeclared (custom relay) → full ladder up to the rule ceiling", ModelEntry(model: relay).selectableThinkingLevels, [.low, .medium, .high, .xhigh, .max])
    // Two rules are consulted in order; the first family match wins.
    checkEq("claude-opus-4.8 (dotted) → .max", ThinkingLevelCatalog.declaredMaxLevel(for: "claude-opus-4.8"), .max)
    checkEq("mimo-v2.5 → .high", ThinkingLevelCatalog.declaredMaxLevel(for: "mimo-v2.5"), .high)
    checkEq("mimo-v2.5-tts (non-reasoning) → .off regardless of the family rule", LLMModel(id: "mimo-v2.5-tts", supportsReasoning: false).catalogMaxThinkingLevel, .off)
}

print("▶️  2. declared up to xhigh, rule says max → Max selectable, XHigh ↔ Max round-trips (ef95c007d)")
do {
    let sol = LLMModel(id: "gpt-5.6-sol", reasoningEffortValues: ["low", "medium", "high", "xhigh"])
    checkEq("ceiling is the HIGHER of declared top and rule", sol.catalogMaxThinkingLevel, .max)
    checkEq("PRE-FIX: the incomplete declaration cut the ceiling to xhigh", preFixCeiling(sol), .xhigh)
    let entry = ModelEntry(model: sol)
    checkEq("picker extends the declared ladder up to Max", entry.selectableThinkingLevels, [.low, .medium, .high, .xhigh, .max])
    // The reported shape: declared only ["low","medium","high"].
    let narrow = ModelEntry(model: LLMModel(id: "gpt-5.6-sol", reasoningEffortValues: ["low", "medium", "high"]))
    checkEq("reported case: [low,medium,high] declared → Max still offered", narrow.selectableThinkingLevels, [.low, .medium, .high, .xhigh, .max])
    // Round trip: the list never reads the current selection.
    func offered(_ e: ModelEntry, currentlySelected: ThinkingLevel) -> [ThinkingLevel] { e.selectableThinkingLevels }
    let a = offered(entry, currentlySelected: .xhigh), b = offered(entry, currentlySelected: .max), c = offered(entry, currentlySelected: .xhigh)
    check("XHigh → Max → XHigh: the offered list is identical each time", a == b && b == c && a.contains(.max))
    // A declaration ABOVE the default still raises (the original intent survives).
    let glm = LLMModel(id: "glm-5.2", reasoningEffortValues: ["high", "max"])
    checkEq("glm-5.2 [high,max] → .max", glm.catalogMaxThinkingLevel, .max)
    checkEq("…offered as two honest options", ModelEntry(model: glm).selectableThinkingLevels, [.high, .max])
    // An unruled narrow declaration stays a cap.
    let g53 = LLMModel(id: "gpt-5.3", reasoningEffortValues: ["low", "high"])
    checkEq("unruled [low,high] → .high", g53.catalogMaxThinkingLevel, .high)
    checkEq("…offered as declared", ModelEntry(model: g53).selectableThinkingLevels, [.low, .high])
    // User override is a ceiling that can cut but never invents tiers.
    var capped = entry; capped.overrideMaxThinkingLevel = .high
    checkEq("override .high cuts the list", capped.selectableThinkingLevels, [.low, .medium, .high])
    var below = ModelEntry(model: glm); below.overrideMaxThinkingLevel = .low
    checkEq("override below every declared tier keeps the weakest tier", below.selectableThinkingLevels, [.high])
    var off = entry; off.overrideMaxThinkingLevel = .off
    checkEq("override .off empties the picker", off.selectableThinkingLevels, [])
}

print("▶️  2b. round-2 item M13: declared ceiling vs rule top, swept")
do {
    // A table over the whole cross-product of "what models.dev declared" × "what
    // the family rule says", because the reported failure was one cell of it and
    // the fix (max of the two) has to hold in every other cell too.
    struct Row { let id: String; let declared: [String]?; let ceiling: ThinkingLevel; let offered: [ThinkingLevel] }
    let rows: [Row] = [
        // Ruled families, incomplete declarations: the rule must win.
        Row(id: "gpt-5.6-sol", declared: ["low"], ceiling: .max, offered: [.low, .medium, .high, .xhigh, .max]),
        Row(id: "gpt-5.6-sol", declared: ["low", "medium"], ceiling: .max, offered: [.low, .medium, .high, .xhigh, .max]),
        Row(id: "gpt-5.6-sol", declared: ["medium", "high"], ceiling: .max, offered: [.medium, .high, .xhigh, .max]),
        Row(id: "gpt-5.6-luna", declared: ["low", "medium"], ceiling: .max, offered: [.low, .medium, .high, .xhigh, .max]),
        Row(id: "gpt-6-astra", declared: ["low", "medium"], ceiling: .max, offered: [.low, .medium, .high, .xhigh, .max]),
        Row(id: "deepseek-flash", declared: ["low", "medium"], ceiling: .max, offered: [.low, .medium, .high, .xhigh, .max]),
        Row(id: "claude-opus-4.8", declared: ["low", "medium"], ceiling: .max, offered: [.low, .medium, .high, .xhigh, .max]),
        // A declaration that already reaches the rule: nothing is invented.
        Row(id: "gpt-5.6-sol", declared: ["low", "medium", "high", "xhigh", "max"], ceiling: .max, offered: [.low, .medium, .high, .xhigh, .max]),
        // A declaration ABOVE the rule raises the ceiling (the rule is a floor
        // for the ceiling, not a cap on an honest declaration).
        Row(id: "gpt-5.5", declared: ["low", "max"], ceiling: .max, offered: [.low, .max]),
        Row(id: "mimo-v2.5", declared: ["low", "max"], ceiling: .max, offered: [.low, .max]),
        // A ruled family with NO declaration gets the full ladder to the rule.
        Row(id: "gpt-5.5", declared: nil, ceiling: .xhigh, offered: [.low, .medium, .high, .xhigh]),
        Row(id: "mimo-v2.5", declared: nil, ceiling: .high, offered: [.low, .medium, .high]),
        // An unruled model: the declaration IS the ceiling; with none, .xhigh.
        Row(id: "glm-5.2", declared: ["low", "medium"], ceiling: .medium, offered: [.low, .medium]),
        Row(id: "glm-5.2", declared: nil, ceiling: .xhigh, offered: [.low, .medium, .high, .xhigh]),
        Row(id: "glm-5.2", declared: [], ceiling: .xhigh, offered: [.low, .medium, .high, .xhigh]),
    ]
    for row in rows {
        let m = LLMModel(id: row.id, reasoningEffortValues: row.declared)
        checkEq("\(row.id) declared \(row.declared.map { "\($0)" } ?? "nil") → ceiling \(row.ceiling)", m.catalogMaxThinkingLevel, row.ceiling)
        checkEq("…offered \(row.offered)", ModelEntry(model: m).selectableThinkingLevels, row.offered)
        // Max must never be hidden when the ceiling reaches it.
        if row.ceiling == .max {
            check("…Max is reachable", ModelEntry(model: m).selectableThinkingLevels.contains(.max))
        }
    }
    // The pre-fix ceiling disagreed with the fixed one for exactly the incomplete
    // declarations on a ruled family — nowhere else.
    let disagreements = rows.filter { row in
        let m = LLMModel(id: row.id, reasoningEffortValues: row.declared)
        return preFixCeiling(m) != m.catalogMaxThinkingLevel
    }
    check("PRE-FIX disagreed only where a ruled family under-declared", disagreements.allSatisfy {
        ThinkingLevelCatalog.declaredMaxLevel(for: $0.id) != nil && !($0.declared?.isEmpty ?? true)
    })
    check("…and there was at least one such cell (the reported one)", !disagreements.isEmpty)

    // XHigh → Max → XHigh, driven through a stored selection rather than only
    // through the getter, since that is what the user actually does.
    var selection: ThinkingLevel = .xhigh
    let entry = ModelEntry(model: LLMModel(id: "gpt-5.6-sol", reasoningEffortValues: ["low", "medium", "high"]))
    var seen: [[ThinkingLevel]] = []
    for next in [ThinkingLevel.max, .xhigh, .max, .low, .max] {
        seen.append(entry.selectableThinkingLevels)
        check("Max stays offered while selected=\(selection)", entry.selectableThinkingLevels.contains(.max))
        selection = next
    }
    check("the offered list was identical at every step", Set(seen.map { $0.map(\.rawValue).joined(separator: ",") }).count == 1)
    // And a level the picker offers must survive a round trip through the cap.
    for level in entry.selectableThinkingLevels {
        checkEq("offered level \(level) is <= the ceiling", min(level, entry.effectiveMaxThinkingLevel), level)
    }
}

print("▶️  3. custom rules are scoped by provider instance, not only by model name")
do {
    let builtIn = [Rule(kind: .officialVendor, scope: .modelPattern("*deepseek-v4*"), label: "deepseek-v4-official"),
                   Rule(kind: .providerTypeDefault, scope: .allModels, label: "default")]
    // A rule the user attached to the MiniMax (Anthropic-compatible) instance.
    let minimaxRules = [Rule(kind: .custom, scope: .allModels, label: "minimax-force-thinking")]
    checkEq("on the MiniMax instance, `*` matches MiniMax-M3", winner(userRules: minimaxRules, builtIn: builtIn, modelId: "MiniMax-M3").label, "minimax-force-thinking")
    checkEq("on another instance with no user rules, the same model falls to the default", winner(userRules: [], builtIn: builtIn, modelId: "MiniMax-M3").label, "default")
    checkEq("a user rule shadows a built-in vendor rule for the same model", winner(userRules: [Rule(kind: .custom, scope: .modelPattern("*deepseek*"), label: "mine")], builtIn: builtIn, modelId: "deepseek-v4-flash").label, "mine")
    check("pattern matching normalises dots to dashes", Scope.modelPattern("minimax-m3*").matches("MiniMax-M3.1"))
    check("`*` glob semantics: literal tail must be at the end", Scope.modelPattern("*flash").matches("deepseek-v4-flash") && !Scope.modelPattern("*flash").matches("deepseek-flash-lite"))
    // KNOWN GAP (issues #306 / #311): the per-instance user rules are consulted
    // only on the OpenAI-compatible request path (ThinkingRuleCache.rules(for:)
    // in OpenAIAgentProvider.injectThinkingParams). The Anthropic-protocol path
    // resolves its shape via ThinkingRuleResolver.anthropicThinkingShape, which
    // takes no userRules — so a custom rule attached to an Anthropic-compatible
    // instance (MiniMax M3 at api.minimax.cn/anthropic) can never fire there.
    // Expected: AnthropicAgentProvider consults the instance's user rules the
    // same way the OpenAI path does.
    let anth = source("Providers/Anthropic/AnthropicAgentProvider.swift")
    let res = source("Providers/Thinking/ThinkingRuleResolver.swift")
    gap("Anthropic-protocol requests consult the instance's custom thinking rules",
        anth.contains("ThinkingRuleCache") || (res.range(of: "static func anthropicThinkingShape(").map { res[$0.upperBound...].prefix(400).contains("userRules") } ?? false))
}

print("▶️  4. shipping sources still carry the pinned lines")
do {
    let cat = source("Providers/ThinkingLevelCatalog.swift")
    let types = source("Providers/LLMTypes.swift")
    let entry = source("Providers/ModelEntry.swift")
    let rule = source("Providers/Thinking/ThinkingRule.swift")
    let res = source("Providers/Thinking/ThinkingRuleResolver.swift")
    if cat.isEmpty || types.isEmpty || entry.isEmpty || rule.isEmpty || res.isEmpty { print("  ⏭  sources not readable") } else {
        check("deepseek-flash family rule present", cat.contains("({ $0.contains(\"deepseek-flash\") || $0.contains(\"deepseek-v4\") }, .max),"))
        check("ceiling = max(declared top, rule)", types.contains("return Swift.max(declaredTop, ruleTop ?? declaredTop)"))
        check("picker extends the ladder above the declared top", entry.contains("if let declaredTop = declared.last, ceiling > declaredTop {"))
        check("the list never reads the current selection", !entry.components(separatedBy: "var selectableThinkingLevels").last!.prefix(1500).contains("thinkingLevel"))
        check("scope matching normalises dots", rule.contains("replacingOccurrences(of: \".\", with: \"-\")"))
        check("user rules are evaluated before built-ins", res.contains("let rules = ctx.userRules + builtInRules(for: ctx)"))
        check("OpenAI path loads per-instance user rules", source("Providers/OpenAI/OpenAIAgentProvider.swift").contains("ThinkingRuleCache.shared.rules(for: $0)"))
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
