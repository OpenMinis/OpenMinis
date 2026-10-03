// Tests for [T-outbound-field-gating] — backlog item T19, iOS half.
//
// Fields that are harmless extras on one gateway are hard 400s on another:
//   * `prompt_cache_key` — NVIDIA rejected it three times (#247, #259, #268):
//     only the official OpenAI endpoint (and an explicit Responses relay)
//     gets it;
//   * a root `thinking` object is a hard 400 on Venice (#86); unified
//     gateways (Ark / Azure / Venice) get root `reasoning_effort` only;
//   * `reasoning_effort` lives at the root on Chat Completions, under
//     `reasoning.effort` on the Responses API / OpenRouter, and under
//     `output_config.effort` on Anthropic adaptive models (#177);
//   * the explicit off value is an allowlist: "none" for official OpenAI,
//     "minimal" for Ark, omission everywhere else.
// (#304 / #327 / #347 x-opencode-session is pinned by OpenCodeSessionHeaderTests.)
//
// Ports:
//   shouldSendPromptCacheKey  — OpenAIAgentProvider.swift ~L929
//   explicitOffEffort         — OpenAIAgentProvider.swift ~L1005
//   usesUnifiedReasoningEffort — OpenAIProvider.swift ~L330
//   ThinkingRuleResolver.emit for .reasoningEffort / .reasoningEffortNested /
//     .deepSeekSibling and the unified-gateway rule ordering ~L310–640
//   Responses reasoning.effort — OpenAIAgentProvider.swift ~L520
//   Anthropic output_config.effort — OAuthHTTPClient.swift ~L1449
//
// Standalone (`swift OutboundFieldGatingTests.swift`) like its neighbours.
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

// MARK: - Provider predicates

struct Provider {
    var customBaseURL: String? = nil
    var isAzure = false
    var forceResponsesAPI = false
    var useOpenRouterCompat = false
    var usesUnifiedReasoningEffort: Bool {
        if isAzure { return true }
        guard let base = customBaseURL?.lowercased() else { return false }
        return base.contains("volces") || base.contains("ark.") || base.contains("api.venice.ai")
    }
    var isOpenRouter: Bool { (customBaseURL?.lowercased() ?? "").contains("openrouter.ai") }
}

func shouldSendPromptCacheKey(for provider: Provider) -> Bool {
    if provider.customBaseURL == nil && !provider.isAzure { return true }
    return provider.forceResponsesAPI
}

enum ThinkingLevel { case off, low, medium, high, xhigh, max; var isEnabled: Bool { self != .off } }
func wireEffort(_ l: ThinkingLevel) -> String { switch l { case .off, .low: return "low"; case .medium: return "medium"; case .high: return "high"; case .xhigh: return "xhigh"; case .max: return "max" } }

func explicitOffEffort(provider: Provider, modelId: String, level: ThinkingLevel) -> String? {
    guard !level.isEnabled else { return nil }
    if provider.customBaseURL == nil && !provider.isAzure { return "none" }
    let base = provider.customBaseURL?.lowercased() ?? ""
    let lid = modelId.lowercased()
    if base.contains("volces") || base.contains("ark.") || lid.contains("seed-") || lid.contains("doubao") { return "minimal" }
    return nil
}

// MARK: - Rule registry + emit (the slice that decides root shape)

enum Wire: Equatable { case reasoningEffortNested, reasoningEffort(offValue: String?), deepSeekSibling }
struct Rule { let pattern: String?; let wire: Wire; let label: String }
func glob(_ pattern: String, _ input: String) -> Bool {
    let p = pattern.lowercased(), i = input.lowercased()
    let parts = p.components(separatedBy: "*")
    if parts.count == 1 { return i == p }
    var cursor = i.startIndex
    for (k, part) in parts.enumerated() {
        if part.isEmpty { continue }
        if k == 0 { guard i.hasPrefix(part) else { return false }; cursor = i.index(cursor, offsetBy: part.count); continue }
        if k == parts.count - 1 && !p.hasSuffix("*") { return i.hasSuffix(part) }
        guard let r = i.range(of: part, range: cursor..<i.endIndex) else { return false }
        cursor = r.upperBound
    }
    return true
}
struct Ctx { let modelId: String; let level: ThinkingLevel; let provider: Provider; let offEffort: String?; let declared: [String]? }
func builtInRules(_ ctx: Ctx) -> [Rule] {
    var rules: [Rule] = []
    if ctx.provider.isOpenRouter { rules.append(Rule(pattern: nil, wire: .reasoningEffortNested, label: "openrouter")) }
    if ctx.provider.usesUnifiedReasoningEffort { rules.append(Rule(pattern: nil, wire: .reasoningEffort(offValue: ctx.offEffort), label: "unified-gateway(ark|azure|venice)")) }
    rules.append(Rule(pattern: "*deepseek-v4*", wire: .deepSeekSibling, label: "deepseek-v4-official"))
    rules.append(Rule(pattern: nil, wire: .reasoningEffort(offValue: ctx.offEffort), label: "openai-compatible-default"))
    return rules
}
func apply(_ ctx: Ctx) -> (body: [String: Any], label: String) {
    let winner = builtInRules(ctx).first { $0.pattern == nil || glob($0.pattern!, ctx.modelId) }!
    var body: [String: Any] = [:]
    switch winner.wire {
    case .reasoningEffortNested:
        if ctx.level.isEnabled { body["reasoning"] = ["effort": wireEffort(ctx.level)] }
    case .reasoningEffort(let offValue):
        if !ctx.level.isEnabled {
            if let offValue, ctx.declared?.contains(offValue) ?? true { body["reasoning_effort"] = offValue }
        } else {
            body["reasoning_effort"] = wireEffort(ctx.level)
        }
    case .deepSeekSibling:
        if ctx.level.isEnabled { body["thinking"] = ["type": "enabled"]; body["reasoning_effort"] = wireEffort(ctx.level) }
        else { body["thinking"] = ["type": "disabled"] }
    }
    return (body, winner.label)
}

/// Responses API (OpenAIAgentProvider ~L520): nested reasoning.effort.
func responsesThinking(level: ThinkingLevel, offEffort: String?) -> [String: Any] {
    var body: [String: Any] = [:]
    if level.isEnabled { body["reasoning"] = ["effort": wireEffort(level), "summary": "auto"] }
    else if let offEffort { body["reasoning"] = ["effort": offEffort] }
    return body
}
/// Anthropic adaptive (OAuthHTTPClient ~L1449): output_config.effort.
func anthropicAdaptive(effort: String) -> [String: Any] {
    var json: [String: Any] = ["model": "claude-opus-4-6"]
    json["thinking"] = ["type": "adaptive"]
    var oc = (json["output_config"] as? [String: Any]) ?? [:]
    oc["effort"] = effort
    json["output_config"] = oc
    return json
}

func chatBody(_ p: Provider, model: String, level: ThinkingLevel, declared: [String]? = nil) -> [String: Any] {
    var body: [String: Any] = ["model": model, "stream": true]
    if shouldSendPromptCacheKey(for: p) { body["prompt_cache_key"] = "pck_stable" }
    let off = explicitOffEffort(provider: p, modelId: model, level: level)
    let r = apply(Ctx(modelId: model, level: level, provider: p, offEffort: off, declared: declared))
    for (k, v) in r.body { body[k] = v }
    return body
}

print("▶️  1. prompt_cache_key only for the official OpenAI endpoint")
do {
    let nvidia = Provider(customBaseURL: "https://integrate.api.nvidia.com/v1")
    check("NVIDIA → no key", chatBody(nvidia, model: "nvidia/llama", level: .off)["prompt_cache_key"] == nil)
    check("api.openai.com (no custom base) → key", chatBody(Provider(), model: "gpt-5.5", level: .off)["prompt_cache_key"] != nil)
    check("Azure → no key", chatBody(Provider(isAzure: true), model: "gpt-5.5", level: .off)["prompt_cache_key"] == nil)
    check("an explicit Responses relay (sub2api) keeps the key", shouldSendPromptCacheKey(for: Provider(customBaseURL: "https://relay.example/v1", forceResponsesAPI: true)))
    check("a generic custom base without the opt-in → no key", !shouldSendPromptCacheKey(for: Provider(customBaseURL: "https://relay.example/v1")))
    check("even a custom base that IS openai.com is treated as custom", !shouldSendPromptCacheKey(for: Provider(customBaseURL: "https://api.openai.com/v1")))
}

print("▶️  2. Venice: root reasoning_effort, never a root thinking object")
do {
    let venice = Provider(customBaseURL: "https://api.venice.ai/api/v1")
    let on = chatBody(venice, model: "deepseek-v4-pro", level: .high)
    check("no root `thinking`", on["thinking"] == nil)
    checkEq("root reasoning_effort", on["reasoning_effort"] as? String, "high")
    checkEq("the unified-gateway rule won over the deepseek-v4 vendor rule", apply(Ctx(modelId: "deepseek-v4-pro", level: .high, provider: venice, offEffort: nil, declared: nil)).label, "unified-gateway(ark|azure|venice)")
    let off = chatBody(venice, model: "deepseek-v4-pro", level: .off)
    check("off → field omitted (Venice has no allowlisted off value)", off["reasoning_effort"] == nil && off["thinking"] == nil)
    // The same model on a plain relay takes the vendor-native sibling shape.
    let relay = Provider(customBaseURL: "https://relay.example/v1")
    checkEq("plain relay + deepseek-v4 → sibling shape", json(apply(Ctx(modelId: "deepseek-v4-pro", level: .high, provider: relay, offEffort: nil, declared: nil)).body), #"{"reasoning_effort":"high","thinking":{"type":"enabled"}}"#)
    let ark = Provider(customBaseURL: "https://ark.cn-beijing.volces.com/api/v3")
    checkEq("Ark off → minimal", chatBody(ark, model: "doubao-seed-2.0", level: .off)["reasoning_effort"] as? String, "minimal")
    check("Azure counts as unified", Provider(isAzure: true).usesUnifiedReasoningEffort)
}

print("▶️  3. reasoning_effort placement per protocol")
do {
    checkEq("Chat Completions → top-level", json(apply(Ctx(modelId: "gpt-5.5", level: .high, provider: Provider(), offEffort: "none", declared: nil)).body), #"{"reasoning_effort":"high"}"#)
    checkEq("Chat Completions off (official OpenAI) → explicit none", json(apply(Ctx(modelId: "gpt-5.5", level: .off, provider: Provider(), offEffort: "none", declared: nil)).body), #"{"reasoning_effort":"none"}"#)
    check("off value not in the declared set → omitted, never clamped UP", apply(Ctx(modelId: "glm-5.2", level: .off, provider: Provider(customBaseURL: "https://relay/v1"), offEffort: "none", declared: ["high", "max"])).body.isEmpty)
    checkEq("Responses → reasoning.effort", json(responsesThinking(level: .high, offEffort: nil)), #"{"reasoning":{"effort":"high","summary":"auto"}}"#)
    checkEq("Responses off (official) → reasoning.effort=none", json(responsesThinking(level: .off, offEffort: "none")), #"{"reasoning":{"effort":"none"}}"#)
    check("Responses off (relay) → omitted", responsesThinking(level: .off, offEffort: nil).isEmpty)
    let or = Provider(customBaseURL: "https://openrouter.ai/api/v1")
    checkEq("OpenRouter → nested reasoning.effort", json(apply(Ctx(modelId: "anthropic/claude-opus-4-6", level: .medium, provider: or, offEffort: nil, declared: nil)).body), #"{"reasoning":{"effort":"medium"}}"#)
    check("OpenRouter off → omitted entirely", apply(Ctx(modelId: "x", level: .off, provider: or, offEffort: nil, declared: nil)).body.isEmpty)
    let a = anthropicAdaptive(effort: "xhigh")
    checkEq("Anthropic adaptive → output_config.effort", (a["output_config"] as? [String: String])?["effort"], "xhigh")
    check("…never a root reasoning_effort", a["reasoning_effort"] == nil)
    // The #177 anti-pattern: effort nested INSIDE thinking must never appear on any path.
    for body in [chatBody(Provider(), model: "gpt-5.5", level: .max), chatBody(Provider(customBaseURL: "https://relay/v1"), model: "deepseek-v4", level: .max), responsesThinking(level: .max, offEffort: nil)] {
        let nested = (body["thinking"] as? [String: Any])?["reasoning_effort"]
        check("no `thinking.reasoning_effort` hybrid", nested == nil)
    }
}

print("▶️  4. shipping sources still carry the pinned lines")
do {
    let oai = source("Providers/OpenAI/OpenAIAgentProvider.swift")
    let op = source("Providers/OpenAI/OpenAIProvider.swift")
    let res = source("Providers/Thinking/ThinkingRuleResolver.swift")
    let http = source("Providers/Anthropic/OAuthHTTPClient.swift")
    if oai.isEmpty || op.isEmpty || res.isEmpty || http.isEmpty { print("  ⏭  sources not readable") } else {
        check("prompt_cache_key gate exists", oai.contains("static func shouldSendPromptCacheKey(for provider: OpenAIProvider) -> Bool {\n        if provider.customBaseURL == nil && !provider.isAzure { return true }\n        return provider.forceResponsesAPI"))
        checkEq("both chat and Responses paths use the gate", oai.components(separatedBy: "if Self.shouldSendPromptCacheKey(for: provider) {").count - 1, 2)
        check("unified gateways are one concept incl. Venice", op.contains("return base.contains(\"volces\") || base.contains(\"ark.\")\n            || base.contains(\"api.venice.ai\")"))
        check("the unified rule uses root reasoning_effort only", res.contains("wireFormat: .reasoningEffort(offValue: ctx.offEffort),\n                label: \"unified-gateway(ark|azure|venice)\""))
        check("…and is registered BEFORE the deepseek-v4 sibling rule", res.range(of: "label: \"unified-gateway(ark|azure|venice)\"")!.lowerBound < res.range(of: "label: \"deepseek-v4-official\"")!.lowerBound)
        check("off value is never clamped up", res.contains("if let offEffort, ctx.declaredEffortValues?.contains(offEffort) ?? true {"))
        check("off allowlist: official → none, Ark → minimal", oai.contains("if provider.customBaseURL == nil && !provider.isAzure { return \"none\" }") && oai.contains("return \"minimal\""))
        check("Responses uses reasoning.effort", oai.contains("body[\"reasoning\"] = [\"effort\": effort, \"summary\": \"auto\"]"))
        check("Anthropic adaptive uses output_config.effort", http.contains("var oc = (json[\"output_config\"] as? [String: Any]) ?? [:]"))
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
