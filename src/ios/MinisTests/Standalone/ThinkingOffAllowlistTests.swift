// Tests for [T-thinking-off-allowlist] — round-2 item M17, iOS half.
//
// Pins ff60c818a (explicit off values are an ALLOWLIST: official OpenAI →
// "none", Volcano Ark / seed / doubao → "minimal", everyone else omits),
// c5efeb1ed (MiMo / Agnes strict enum: never an off tier, even on the
// official base), 4a89f5caf (fallback pre-clamps the requested level to the
// family ceiling) and 72968c4f2 (the ceiling matches the MiMo FAMILY —
// `mimo-2.5` AND `mimo-v2.5` — so xhigh is clamped to high for both).
//
// Ports:
//   explicitOffEffort                — OpenAIAgentProvider.swift ~L1005
//   strictEffortEnum + the off path of the generic / openai-native branches
//                                    — ThinkingRuleResolver.emit ~L440-560
//   ThinkingLevelCatalog.declaredMaxLevel — ThinkingLevelCatalog.swift
//   clampEffort / wireEffort          — OpenAIAgentProvider.swift ~L1139-1180
//
// Standalone (`swift ThinkingOffAllowlistTests.swift`) like its neighbours.
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
    let d = try! JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])
    return String(data: d, encoding: .utf8)!
}

// MARK: - Ports

enum ThinkingLevel: Int, Comparable { case off, low, medium, high, xhigh, max, ultra
    var isEnabled: Bool { self != .off }
    static func < (a: ThinkingLevel, b: ThinkingLevel) -> Bool { a.rawValue < b.rawValue }
}
struct Provider { var customBaseURL: String? = nil; var isAzure = false }
struct Model { let id: String; var supportsReasoning: Bool? = true; var declared: [String]? = nil }

func explicitOffEffort(provider: Provider, model: Model, level: ThinkingLevel) -> String? {
    guard !level.isEnabled else { return nil }
    if provider.customBaseURL == nil && !provider.isAzure { return "none" }
    let base = provider.customBaseURL?.lowercased() ?? ""
    let lid = model.id.lowercased()
    if base.contains("volces") || base.contains("ark.") || lid.contains("seed-") || lid.contains("doubao") { return "minimal" }
    return nil
}

func wireEffort(_ l: ThinkingLevel) -> String { switch l { case .off, .low: "low"; case .medium: "medium"; case .high: "high"; case .xhigh: "xhigh"; case .max, .ultra: "max" } }
func reasoningEffort(model: Model, level: ThinkingLevel) -> String? {
    guard model.supportsReasoning ?? false else { return nil }
    switch level { case .off: return nil; case .low: return "low"; case .medium: return "medium"; case .high: return "high"; case .xhigh: return "xhigh"; case .max, .ultra: return "max" }
}
func clampEffort(_ effort: String, to values: [String]?) -> String {
    guard let values, !values.isEmpty else { return effort }
    if values.contains(effort) { return effort }
    let ladder = ["none", "minimal", "low", "medium", "high", "xhigh", "max"]
    guard let want = ladder.firstIndex(of: effort) else { return effort }
    let declared = values.compactMap { v in ladder.firstIndex(of: v).map { ($0, v) } }.sorted { $0.0 < $1.0 }
    guard !declared.isEmpty else { return effort }
    if let below = declared.last(where: { $0.0 <= want }) { return below.1 }
    return declared[0].1
}

/// The `.reasoningEffort` emitter, reduced to what decides the OFF field.
/// (`isOpenAINative` mirrors the resolver's prefix set; the unified / xAI gates
/// are pinned elsewhere and omitted here.)
func emitReasoningEffort(model: Model, level: ThinkingLevel, ctxOffEffort: String?) -> [String: Any] {
    var body: [String: Any] = [:]
    let lid = model.id.lowercased()
    let strictEffortEnum = lid.contains("mimo") || lid.contains("agnes")
    let offEffort = strictEffortEnum ? nil : ctxOffEffort
    let isOpenAINative = lid.hasPrefix("o1") || lid.hasPrefix("o3") || lid.hasPrefix("o4") || lid.hasPrefix("gpt-5") || lid.hasPrefix("gpt-4")
    if isOpenAINative {
        if let e = reasoningEffort(model: model, level: level) { body["reasoning_effort"] = e }
        else if !level.isEnabled, let offEffort, model.supportsReasoning ?? false { body["reasoning_effort"] = offEffort }
        return body
    }
    let declaresEffort = !(model.declared?.isEmpty ?? true)
    if !declaresEffort, ["deepseek", "glm", "kimi", "minimax"].contains(where: { lid.contains($0) }) { return body }
    guard model.supportsReasoning != false else { return body }
    if !level.isEnabled {
        if let offEffort, model.declared?.contains(offEffort) ?? true { body["reasoning_effort"] = offEffort }
        return body
    }
    body["reasoning_effort"] = clampEffort(wireEffort(level), to: model.declared)
    return body
}
func offBody(_ p: Provider, _ m: Model) -> [String: Any] {
    emitReasoningEffort(model: m, level: .off, ctxOffEffort: explicitOffEffort(provider: p, model: m, level: .off))
}

/// ThinkingLevelCatalog.declaredMaxLevel, the family-ceiling rows only.
func declaredMaxLevel(_ id: String) -> ThinkingLevel? {
    let lid = id.lowercased()
    if lid.hasPrefix("gpt-5.5") { return .xhigh }
    if lid.contains("mimo") || lid.contains("agnes") { return .high }
    if lid.contains("seed-") || lid.contains("bytedance-seed") { return .high }
    return nil
}
/// AIChatViewModel+Fallback pre-clamp: the requested level never exceeds the ceiling.
func preclamp(_ level: ThinkingLevel, model: Model) -> ThinkingLevel {
    if model.supportsReasoning == false { return .off }
    guard let ceiling = declaredMaxLevel(model.id) else { return level }
    return min(level, ceiling)
}

let official = Provider()
let azure = Provider(customBaseURL: "https://my.openai.azure.com/openai", isAzure: true)
let ark = Provider(customBaseURL: "https://ark.cn-beijing.volces.com/api/v3")
let relay = Provider(customBaseURL: "https://relay.example/v1")
let openrouter = Provider(customBaseURL: "https://openrouter.ai/api/v1")
let xai = Provider(customBaseURL: "https://api.x.ai/v1")
let mimoHost = Provider(customBaseURL: "https://api.xiaomimimo.com/v1")

print("▶️  1. explicitOffEffort is an allowlist (ff60c818a)")
do {
    checkEq("official OpenAI → none", explicitOffEffort(provider: official, model: Model(id: "gpt-5.5"), level: .off), "none")
    checkEq("Azure → nil (not the official base, not Ark)", explicitOffEffort(provider: azure, model: Model(id: "gpt-5.5"), level: .off), nil)
    checkEq("Volcano Ark (ark.) → minimal", explicitOffEffort(provider: ark, model: Model(id: "doubao-seed-2.0"), level: .off), "minimal")
    checkEq("volces host → minimal", explicitOffEffort(provider: Provider(customBaseURL: "https://open.volces.com/v1"), model: Model(id: "deepseek-v4"), level: .off), "minimal")
    checkEq("seed- id on any custom base → minimal", explicitOffEffort(provider: relay, model: Model(id: "seed-2.1-turbo"), level: .off), "minimal")
    checkEq("doubao id on any custom base → minimal", explicitOffEffort(provider: openrouter, model: Model(id: "bytedance/doubao-pro"), level: .off), "minimal")
    checkEq("generic relay → nil (omit)", explicitOffEffort(provider: relay, model: Model(id: "glm-5.2"), level: .off), nil)
    checkEq("OpenRouter → nil", explicitOffEffort(provider: openrouter, model: Model(id: "openai/gpt-5.5"), level: .off), nil)
    checkEq("xAI → nil", explicitOffEffort(provider: xai, model: Model(id: "grok-4.20"), level: .off), nil)
    checkEq("a custom base that IS api.openai.com is still custom → nil", explicitOffEffort(provider: Provider(customBaseURL: "https://api.openai.com/v1"), model: Model(id: "gpt-5.5"), level: .off), nil)
    check("thinking ON → nil everywhere", [official, ark, relay].allSatisfy { explicitOffEffort(provider: $0, model: Model(id: "seed-2.0"), level: .low) == nil })
    // The pre-ff60c818a shape sent "minimal" to EVERY custom base.
    func preFix(_ p: Provider) -> String { p.customBaseURL == nil && !p.isAzure ? "none" : "minimal" }
    checkEq("PRE-FIX: a generic relay got minimal (MiMo 400'd on it)", preFix(mimoHost), "minimal")
}

print("▶️  2. on the wire: none / minimal / omitted")
do {
    checkEq("official + gpt-5.5 off → reasoning_effort none", json(offBody(official, Model(id: "gpt-5.5"))), #"{"reasoning_effort":"none"}"#)
    checkEq("official + o3 off → none", json(offBody(official, Model(id: "o3"))), #"{"reasoning_effort":"none"}"#)
    check("official + gpt-5.5 with supportsReasoning=false → nothing", offBody(official, Model(id: "gpt-5.5", supportsReasoning: false)).isEmpty)
    checkEq("Ark + doubao-seed off → minimal", json(offBody(ark, Model(id: "doubao-seed-2.0"))), #"{"reasoning_effort":"minimal"}"#)
    checkEq("Ark + glm off → minimal (the family skip is bypassed by declared tiers or by unified — here: declared)", json(offBody(ark, Model(id: "glm-5.2", declared: ["minimal", "low", "high"]))), #"{"reasoning_effort":"minimal"}"#)
    check("relay + glm off → nothing (omit = vendor default)", offBody(relay, Model(id: "glm-5.2", declared: ["low", "high"])).isEmpty)
    check("relay + grok off → nothing", offBody(relay, Model(id: "grok-4.20")).isEmpty)
    check("relay + nvidia-hosted llama off → nothing", offBody(Provider(customBaseURL: "https://integrate.api.nvidia.com/v1"), Model(id: "meta/llama-4")).isEmpty)
    check("Ark + declared tiers WITHOUT minimal → omitted, never clamped up", offBody(ark, Model(id: "kimi-k2.6", declared: ["high", "max"])).isEmpty)
}

print("▶️  3. MiMo / Agnes strict enum: no off tier, ever (c5efeb1ed)")
do {
    for id in ["mimo-v2.5", "mimo-v2.5-pro", "MiMo-2.5", "mimo-2.5-pro", "agnes-1", "xiaomi/agnes-preview"] {
        check("\(id) on the official base: off tier suppressed", offBody(official, Model(id: id)).isEmpty)
        check("\(id) on Ark: off tier suppressed even though Ark allows minimal", offBody(ark, Model(id: id)).isEmpty)
    }
    check("enabled tiers still flow for MiMo", json(emitReasoningEffort(model: Model(id: "mimo-v2.5"), level: .high, ctxOffEffort: "none")) == #"{"reasoning_effort":"high"}"#)
    check("the exemption is keyed on the model family, not the host: gpt-5 on MiMo's host is not strict", explicitOffEffort(provider: mimoHost, model: Model(id: "gpt-5"), level: .off) == nil)
}

print("▶️  4. xhigh → high for the MiMo family, both spellings (72968c4f2, 4a89f5caf)")
do {
    for id in ["mimo-2.5", "MiMo-2.5-Pro", "mimo-v2.5", "mimo-v2.5-pro", "agnes-1"] {
        checkEq("\(id) ceiling → high", declaredMaxLevel(id), .high)
        checkEq("\(id) xhigh request pre-clamps to high", preclamp(.xhigh, model: Model(id: id)), .high)
        checkEq("…and the wire string is high, not xhigh", wireEffort(preclamp(.xhigh, model: Model(id: id))), "high")
    }
    // The bug 72968c4f2 fixed: the old substring was "mimo-2.5", which the live
    // API's "mimo-v2.5" ids never contained.
    check("PRE-FIX substring \"mimo-2.5\" missed the live id", !"mimo-v2.5-pro".contains("mimo-2.5"))
    checkEq("seed family also caps at high", preclamp(.max, model: Model(id: "seed-2.1-turbo")), .high)
    checkEq("bytedance-seed/… (OpenRouter spelling) caps at high", preclamp(.xhigh, model: Model(id: "bytedance-seed/seed-2.0")), .high)
    checkEq("non-reasoning family member caps at off (mimo-v2.5-tts)", preclamp(.high, model: Model(id: "mimo-v2.5-tts", supportsReasoning: false)), .off)
    checkEq("levels below the ceiling pass through", preclamp(.low, model: Model(id: "mimo-v2.5")), .low)
    checkEq("unruled model is not clamped", preclamp(.max, model: Model(id: "glm-5.2")), .max)
    // Belt and braces: the wire-side clamp against a declared set walks DOWN.
    checkEq("clampEffort xhigh → high on [low,medium,high]", clampEffort("xhigh", to: ["low", "medium", "high"]), "high")
}

print("▶️  5. shipping sources still carry the pinned lines")
do {
    let oai = source("Providers/OpenAI/OpenAIAgentProvider.swift")
    let res = source("Providers/Thinking/ThinkingRuleResolver.swift")
    let cat = source("Providers/ThinkingLevelCatalog.swift")
    if oai.isEmpty || res.isEmpty || cat.isEmpty { print("  ⏭  sources not readable") } else {
        check("allowlist: official → none", oai.contains("if provider.customBaseURL == nil && !provider.isAzure { return \"none\" }"))
        check("allowlist: volces/ark/seed-/doubao → minimal", oai.contains("if base.contains(\"volces\") || base.contains(\"ark.\")\n            || lid.contains(\"seed-\") || lid.contains(\"doubao\") {\n            return \"minimal\""))
        check("allowlist: everyone else nil", oai.range(of: "return \"minimal\"\n        }\n        return nil") != nil)
        check("Responses path consumes the SAME allowlist value", oai.contains("body[\"reasoning\"] = [\"effort\": offEffort]"))
        check("strict enum keyed on mimo/agnes", res.contains("let strictEffortEnum = lid.contains(\"mimo\") || lid.contains(\"agnes\")"))
        check("strict enum nils the off tier before every branch", res.contains("let offEffort = (strictEffortEnum || offTierNotDeclared) ? nil : ctx.offEffort"))
        check("off value never clamped up", res.contains("if let offEffort, ctx.declaredEffortValues?.contains(offEffort) ?? true {"))
        check("catalog matches the MiMo family, not one spelling", cat.contains("({ $0.contains(\"mimo\") || $0.contains(\"agnes\") }, .high),"))
        check("catalog caps seed at high", cat.contains("({ $0.contains(\"seed-\") || $0.contains(\"bytedance-seed\") }, .high),"))
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
