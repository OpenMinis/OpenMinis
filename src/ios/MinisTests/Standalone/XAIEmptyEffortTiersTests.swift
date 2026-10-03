// Tests for [T-xai-empty-effort-tiers] — round-2 item M18, iOS half (#163).
//
// Pins 3ba3eb5c0: a grok id whose catalog entry AFFIRMATIVELY declares zero
// effort tiers (`"reasoning": true, "reasoning_options": []`) must get no
// `reasoning_effort` at all on api.x.ai — the server answers
// "Model grok-build-0.1 does not support parameter reasoningEffort".
// The skip is deliberately scoped: same shape on a relay, or a catalog that
// is merely SILENT, keeps sending the field.
//
// Ports:
//   ModelsDevModel.effortValues / declaresNoEffortTiers — ModelsDevAPI.swift ~L843-877
//   LLMModel.declaresNoEffortTiers: Bool? (Codable)     — LLMTypes.swift ~L91
//   OpenAIProvider.isXAI                                — OpenAIProvider.swift ~L350
//   the generic `.reasoningEffort` branch incl. the xAI gate
//                                                       — ThinkingRuleResolver.swift ~L520-560
//
// Standalone (`swift XAIEmptyEffortTiersTests.swift`) like its neighbours.
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

struct ReasoningOption: Decodable { let type: String; let values: [String]? }
struct ModelsDevModel: Decodable {
    let id: String
    let reasoning: Bool?
    let reasoningOptions: [ReasoningOption]?
    enum CodingKeys: String, CodingKey { case id, reasoning, reasoningOptions = "reasoning_options" }
    var effortValues: [String]? {
        guard let opts = reasoningOptions else { return nil }
        let values = opts.first { $0.type == "effort" }?.values?.map { $0.lowercased() }
        guard let values, !values.isEmpty else { return nil }
        return values
    }
    var declaresNoEffortTiers: Bool {
        guard reasoningOptions != nil else { return false }
        return effortValues == nil
    }
}
struct LLMModel: Codable {
    let id: String
    var supportsReasoning: Bool?
    var reasoningEffortValues: [String]?
    var declaresNoEffortTiers: Bool?
}
func isXAI(_ base: String?) -> Bool {
    guard let base = base?.lowercased() else { return false }
    return base.contains("api.x.ai") || base.contains("//x.ai")
}

enum ThinkingLevel { case off, low, medium, high, xhigh, max; var isEnabled: Bool { self != .off } }
func wireEffort(_ l: ThinkingLevel) -> String { switch l { case .off, .low: "low"; case .medium: "medium"; case .high: "high"; case .xhigh: "xhigh"; case .max: "max" } }
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
struct Ctx {
    var modelId: String; var supportsReasoning: Bool? = true; var declared: [String]? = nil
    var declaresNoEffortTiers = false; var isXAI = false; var unified = false
    var level: ThinkingLevel; var offEffort: String? = nil
}
/// The generic (non-openai-native) `.reasoningEffort` branch, verbatim in structure.
func emitGeneric(_ ctx: Ctx) -> (body: [String: Any], gate: String?) {
    let lid = ctx.modelId.lowercased()
    var body: [String: Any] = [:]
    let declaresEffort = !(ctx.declared?.isEmpty ?? true)
    if !ctx.unified, !declaresEffort, ["deepseek", "glm", "kimi", "minimax"].contains(where: { lid.contains($0) }) { return (body, "self-reasoning-family") }
    if ctx.isXAI, !ctx.unified, ctx.declaresNoEffortTiers, !declaresEffort { return (body, "xai-no-effort-tiers") }
    guard ctx.supportsReasoning != false else { return (body, "non-reasoning-model") }
    if !ctx.level.isEnabled {
        if let off = ctx.offEffort, ctx.declared?.contains(off) ?? true { body["reasoning_effort"] = off }
        return (body, nil)
    }
    body["reasoning_effort"] = clampEffort(wireEffort(ctx.level), to: ctx.declared)
    return (body, nil)
}
/// LLMModel → context, as OpenAIAgentProvider.injectThinkingParams wires it.
func ctx(_ m: LLMModel, base: String?, level: ThinkingLevel) -> Ctx {
    Ctx(modelId: m.id, supportsReasoning: m.supportsReasoning, declared: m.reasoningEffortValues,
        declaresNoEffortTiers: m.declaresNoEffortTiers ?? false, isXAI: isXAI(base), level: level)
}

func dev(_ s: String) -> ModelsDevModel { try! JSONDecoder().decode(ModelsDevModel.self, from: s.data(using: .utf8)!) }

print("▶️  1. the catalog distinguishes \"says nothing\" from \"says none\"")
do {
    let build = dev(#"{"id":"grok-build-0.1","reasoning":true,"reasoning_options":[]}"#)
    check("grok-build-0.1: reasoning_options [] → declaresNoEffortTiers", build.declaresNoEffortTiers && build.effortValues == nil)
    let silent = dev(#"{"id":"grok-4.20","reasoning":true}"#)
    check("absent reasoning_options → NOT affirmative", !silent.declaresNoEffortTiers && silent.effortValues == nil)
    let nullOpts = dev(#"{"id":"x","reasoning":true,"reasoning_options":null}"#)
    check("null reasoning_options → NOT affirmative", !nullOpts.declaresNoEffortTiers)
    let toggleOnly = dev(#"{"id":"y","reasoning":true,"reasoning_options":[{"type":"toggle"}]}"#)
    check("toggle-only declaration counts as \"no effort tiers\"", toggleOnly.declaresNoEffortTiers)
    let budgetOnly = dev(#"{"id":"y","reasoning":true,"reasoning_options":[{"type":"budget_tokens","values":[]}]}"#)
    check("budget_tokens-only counts too", budgetOnly.declaresNoEffortTiers)
    let emptyEffort = dev(#"{"id":"z","reasoning":true,"reasoning_options":[{"type":"effort","values":[]}]}"#)
    check("an effort entry with empty values counts", emptyEffort.declaresNoEffortTiers)
    let declared = dev(#"{"id":"grok-4","reasoning":true,"reasoning_options":[{"type":"effort","values":["Low","High"]}]}"#)
    checkEq("declared tiers are lowercased and NOT affirmative-none", declared.effortValues, ["low", "high"])
    check("…", !declared.declaresNoEffortTiers)
    // LLMModel persistence: absent field decodes as nil, never false.
    let legacy = try! JSONDecoder().decode(LLMModel.self, from: #"{"id":"grok-4"}"#.data(using: .utf8)!)
    checkEq("a model persisted before the field existed decodes as nil (unknown)", legacy.declaresNoEffortTiers, nil)
    let fresh = try! JSONDecoder().decode(LLMModel.self, from: #"{"id":"grok-build-0.1","declaresNoEffortTiers":true}"#.data(using: .utf8)!)
    checkEq("a fresh one round-trips true", fresh.declaresNoEffortTiers, true)
}

print("▶️  2. the endpoint predicate")
do {
    check("https://api.x.ai/v1 → xAI", isXAI("https://api.x.ai/v1"))
    check("https://x.ai/v1 → xAI", isXAI("https://x.ai/v1"))
    check("HTTPS://API.X.AI/v1 (case) → xAI", isXAI("HTTPS://API.X.AI/v1"))
    check("nil base (official OpenAI) → not xAI", !isXAI(nil))
    check("OpenRouter → not xAI even for grok ids", !isXAI("https://openrouter.ai/api/v1"))
    check("a relay path containing x.ai is not the host", !isXAI("https://relay.example/x.ai/v1"))
    check("api.xai.example → not xAI", !isXAI("https://api.xai.example/v1"))
}

print("▶️  3. on the wire: the skip fires only for xAI + affirmative-none")
do {
    let build = LLMModel(id: "grok-build-0.1", supportsReasoning: true, reasoningEffortValues: nil, declaresNoEffortTiers: true)
    let onXAI = emitGeneric(ctx(build, base: "https://api.x.ai/v1", level: .high))
    check("grok-build-0.1 on api.x.ai, thinking high → no reasoning_effort", onXAI.body.isEmpty)
    checkEq("…gate recorded", onXAI.gate, "xai-no-effort-tiers")
    check("grok-4.20-0309-reasoning (same shape) → skipped too", emitGeneric(ctx(LLMModel(id: "grok-4.20-0309-reasoning", supportsReasoning: true, declaresNoEffortTiers: true), base: "https://api.x.ai/v1", level: .max)).body.isEmpty)
    checkEq("SAME model on a relay → field sent (skip is endpoint-scoped)", json(emitGeneric(ctx(build, base: "https://relay.example/v1", level: .high)).body), #"{"reasoning_effort":"high"}"#)
    checkEq("SAME model on OpenRouter-compat relay → sent", json(emitGeneric(ctx(build, base: "https://integrate.api.nvidia.com/v1", level: .high)).body), #"{"reasoning_effort":"high"}"#)
    let silent = LLMModel(id: "grok-4.20", supportsReasoning: true)
    checkEq("xAI + catalog silent → sent (permissive)", json(emitGeneric(ctx(silent, base: "https://api.x.ai/v1", level: .high)).body), #"{"reasoning_effort":"high"}"#)
    let declared = LLMModel(id: "grok-4", supportsReasoning: true, reasoningEffortValues: ["low", "high"])
    checkEq("xAI + declared tiers → sent, clamped", json(emitGeneric(ctx(declared, base: "https://api.x.ai/v1", level: .xhigh)).body), #"{"reasoning_effort":"high"}"#)
    // A model that persisted `nil` (pre-field build) must not be skipped.
    let legacy = LLMModel(id: "grok-build-0.1", supportsReasoning: true, declaresNoEffortTiers: nil)
    check("nil (unknown) is not affirmative → sent", !emitGeneric(ctx(legacy, base: "https://api.x.ai/v1", level: .high)).body.isEmpty)
    // Ordering: the legacy family skip runs first and still owns relay-hosted deepseek.
    checkEq("family skip still fires before the xAI check", emitGeneric(Ctx(modelId: "deepseek-r1", declaresNoEffortTiers: true, isXAI: true, level: .high)).gate, "self-reasoning-family")
    // Unified gateways are exempt (they own their model list).
    check("unified gateway exempt", !emitGeneric(Ctx(modelId: "grok-build-0.1", declaresNoEffortTiers: true, isXAI: true, unified: true, level: .high)).body.isEmpty)
    // Off + no allowlisted off value → nothing either way, so the skip is only
    // observable with thinking ON.
    check("off on xAI → nothing (no off value allowlisted for xAI)", emitGeneric(ctx(build, base: "https://api.x.ai/v1", level: .off)).body.isEmpty)
    // The non-xAI same-shape universe is untouched (the "1292 entries" argument).
    let relayClaude = LLMModel(id: "anthropic/claude-opus-4-6", supportsReasoning: true, declaresNoEffortTiers: true)
    check("relay-hosted Claude with the same empty-tier shape is unchanged", !emitGeneric(ctx(relayClaude, base: "https://relay.example/v1", level: .high)).body.isEmpty)
}

print("▶️  4. shipping sources still carry the pinned lines")
do {
    let res = source("Providers/Thinking/ThinkingRuleResolver.swift")
    let api = source("Providers/ModelsDevAPI.swift")
    let op = source("Providers/OpenAI/OpenAIProvider.swift")
    let types = source("Providers/LLMTypes.swift")
    let oai = source("Providers/OpenAI/OpenAIAgentProvider.swift")
    if res.isEmpty || api.isEmpty || op.isEmpty || types.isEmpty || oai.isEmpty { print("  ⏭  sources not readable") } else {
        check("skip condition is xAI && !unified && affirmative-none && !declaresEffort", res.contains("if ctx.isXAI, !ctx.usesUnifiedReasoningEffort,\n               ctx.declaresNoEffortTiers, !declaresEffort {"))
        check("…ordered AFTER the family list", res.range(of: "[\"deepseek\", \"glm\", \"kimi\", \"minimax\"].contains(where: { lid.contains($0) })")!.lowerBound < res.range(of: "id: \"xai-no-effort-tiers\"")!.lowerBound)
        check("catalog: affirmative-none = options present but no usable effort entry", api.contains("guard reasoningOptions != nil else { return false }\n        return effortValues == nil"))
        check("catalog: enrich only sets true, never false", api.contains("if devModel.declaresNoEffortTiers {\n            result.declaresNoEffortTiers = true"))
        check("isXAI predicate", op.contains("return base.contains(\"api.x.ai\") || base.contains(\"//x.ai\")"))
        check("LLMModel field is Optional", types.contains("var declaresNoEffortTiers: Bool?"))
        check("agent provider threads isXAI into the resolver", oai.contains("isXAI: provider.isXAI"))
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
