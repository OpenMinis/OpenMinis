// Tests for [T-openai-family-gates] — round-2 item M20, iOS half.
//
// Four independent predicates all keyed on the model id or the endpoint host,
// each of which has cost a field report when it drifted:
//
//   * Fast Mode (`service_tier: "priority"`) — the Responses-path toggle is
//     gated on a gpt-family id; xAI reaches the SAME user-facing feature
//     through `sendsPriorityServiceTier` (isXAI + the same UserDefaults key),
//     so a grok model must not be caught by the gpt gate and a custom relay
//     must not receive the xAI extension.
//   * OpenRouter's `anthropic/` namespace — Claude on OpenRouter needs an
//     explicit `cache_control` breakpoint or nothing is cached at all (GH#191:
//     cache_read pinned at 0, 3-6x cost overrun). Scoped to isOpenRouter AND
//     the prefix, so every other model on the gateway keeps a byte-identical
//     body.
//   * The openai-native reasoning prefix set — iOS uses an explicit
//     o1/o3/o4/gpt-5/gpt-4 list, both in the rule registry and in the
//     `.reasoningEffort` emit branch. Android's half of this item pins its own
//     (broader) `startsWith("o")` predicate; the cross-platform note is at the
//     end of section 3.
//   * `max_completion_tokens` vs legacy `max_tokens` — OpenRouter and Mistral
//     reject the newer name (and `stream_options`).
//
// Ports (structure preserved so a change to either side has to change here):
//   the Responses fast-mode block   — OpenAIProvider.swift ~L1262 / OpenAIAgentProvider.swift ~L582
//   sendsPriorityServiceTier        — OpenAIProvider.swift ~L257
//   activeModelSupportsFastMode     — AIChatView.swift ~L2151
//   needsOpenRouterAnthropicCacheControl — OpenAIProvider.swift ~L296
//   isOpenRouter / isMistral / isDashScope / isXAI — OpenAIProvider.swift ~L262-350
//   the openai-native rules + isOpenAINative — ThinkingRuleResolver.swift ~L263-276 / ~L531
//   the max_tokens branch           — OpenAIProvider.swift ~L1122
//
// Standalone (`swift OpenAIFamilyGatesTests.swift`) like its neighbours.
import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}
func note(_ l: String) { print("  ℹ️  \(l)") }
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

enum ProviderType { case openAI, openAIResponses, xAI, custom }
enum CredentialType { case apiKey, oauth }

/// The endpoint identity predicates, all host checks on the user's base URL.
struct Endpoint {
    var customBaseURL: String? = nil
    var useOpenRouterCompat = false
    var isMistralFlag = false
    var providerType: ProviderType = .openAI

    private var base: String { customBaseURL?.lowercased() ?? "" }
    var isOpenRouter: Bool { customBaseURL != nil && base.contains("openrouter.ai") }
    var isDashScope: Bool { customBaseURL != nil && base.contains("dashscope") }
    var isXAI: Bool { customBaseURL != nil && (base.contains("api.x.ai") || base.contains("//x.ai")) }
    var isMistral: Bool { isMistralFlag }
    var usesLegacyMaxTokens: Bool { useOpenRouterCompat }
}

/// OpenAIProvider.needsOpenRouterAnthropicCacheControl.
func needsOpenRouterAnthropicCacheControl(_ e: Endpoint, modelId: String) -> Bool {
    e.isOpenRouter && modelId.lowercased().hasPrefix("anthropic/")
}

/// The Responses-path Fast Mode block plus the xAI one, in source order — both
/// read the same global toggle, and the second wins if both fire.
func serviceTier(_ e: Endpoint, modelId: String, fastModeOn: Bool) -> String? {
    var tier: String?
    if fastModeOn, modelId.lowercased().contains("gpt") { tier = "priority" }
    if e.isXAI && fastModeOn { tier = "priority" }   // sendsPriorityServiceTier
    return tier
}

/// AIChatView.activeModelSupportsFastMode — whether the SWITCH is even offered.
func offersFastMode(providerType: ProviderType, modelId: String, credential: CredentialType, customBaseURL: String?) -> Bool {
    if providerType == .xAI { return true }
    guard modelId.lowercased().contains("gpt") else { return false }
    if providerType == .openAIResponses { return true }
    return providerType == .openAI && credential == .oauth && (customBaseURL?.isEmpty ?? true)
}

/// The openai-native prefix set, as both the rule registry and the emit branch
/// spell it. `patterns` is the registry order; `isOpenAINative` is the emitter.
let openAINativePatterns = ["o1*", "o3*", "o4*", "gpt-5*", "gpt-4*"]
func isOpenAINative(_ modelId: String) -> Bool {
    let lid = modelId.lowercased()
    return lid.hasPrefix("o1") || lid.hasPrefix("o3") || lid.hasPrefix("o4")
        || lid.hasPrefix("gpt-5") || lid.hasPrefix("gpt-4")
}
/// Android's half of M20 uses a single broader predicate; kept here only to
/// show what the iOS list deliberately does NOT match.
func androidStartsWithO(_ modelId: String) -> Bool { modelId.lowercased().hasPrefix("o") }

/// The chat-completions token-limit branch (the only two keys it can write).
func tokenLimitKeys(_ e: Endpoint, maxTokens: Int, stream: Bool) -> [String: Any] {
    var body: [String: Any] = [:]
    if e.usesLegacyMaxTokens {
        body["max_tokens"] = maxTokens
    } else {
        body["max_completion_tokens"] = maxTokens
        if stream { body["stream_options"] = ["include_usage": true] }
    }
    return body
}

let official = Endpoint()
let responsesRelay = Endpoint(customBaseURL: "https://sub2api.example/v1", providerType: .openAIResponses)
let openrouter = Endpoint(customBaseURL: "https://openrouter.ai/api/v1", useOpenRouterCompat: true)
let mistral = Endpoint(customBaseURL: "https://api.mistral.ai/v1", useOpenRouterCompat: true, isMistralFlag: true)
let xai = Endpoint(customBaseURL: "https://api.x.ai/v1", providerType: .xAI)
let relay = Endpoint(customBaseURL: "https://relay.example/v1", providerType: .custom)

print("▶️  1. Fast Mode service_tier=priority is per-family, not global")
do {
    checkEq("official OpenAI + gpt-5.6 + toggle on → priority", serviceTier(official, modelId: "gpt-5.6-sol", fastModeOn: true), "priority")
    checkEq("…toggle off → nothing", serviceTier(official, modelId: "gpt-5.6-sol", fastModeOn: false), nil)
    checkEq("a Responses relay + gpt id → priority (broadened past Codex OAuth)", serviceTier(responsesRelay, modelId: "gpt-5.3-codex", fastModeOn: true), "priority")
    checkEq("o3 on the SAME endpoint → nothing (no \"gpt\" in the id)", serviceTier(official, modelId: "o3", fastModeOn: true), nil)
    checkEq("claude via a Responses relay → nothing", serviceTier(responsesRelay, modelId: "claude-opus-4-6", fastModeOn: true), nil)
    checkEq("a random relay's glm model → nothing (no unknown field injected)", serviceTier(relay, modelId: "glm-5.2", fastModeOn: true), nil)
    // xAI reaches the same feature through the other gate, never the gpt one.
    checkEq("xAI + grok-4.20 → priority via sendsPriorityServiceTier", serviceTier(xai, modelId: "grok-4.20", fastModeOn: true), "priority")
    check("…grok is NOT matched by the gpt gate", !"grok-4.20".lowercased().contains("gpt"))
    checkEq("xAI + toggle off → nothing", serviceTier(xai, modelId: "grok-4.20", fastModeOn: false), nil)
    // The two blocks cannot conflict: they write the same value, and a body can
    // only see one of them in practice.
    checkEq("an id containing gpt served BY xAI still yields exactly one value", serviceTier(xai, modelId: "gpt-oss-120b", fastModeOn: true), "priority")
    // The substring gate is intentionally loose — a relay's `openai/gpt-5` id
    // must still get it, which is why `contains` rather than `hasPrefix`.
    checkEq("openai/gpt-5.6 (OpenRouter spelling) → priority", serviceTier(Endpoint(customBaseURL: "https://openrouter.ai/api/v1", providerType: .openAIResponses), modelId: "openai/gpt-5.6", fastModeOn: true), "priority")

    // The UI gate is STRICTER than the request gate, and deliberately so: the
    // switch is only offered where the tier is known to be honored.
    check("switch offered: xAI, any model", offersFastMode(providerType: .xAI, modelId: "grok-4.20", credential: .apiKey, customBaseURL: "https://api.x.ai/v1"))
    check("switch offered: Codex OAuth (no custom base)", offersFastMode(providerType: .openAI, modelId: "gpt-5.3-codex", credential: .oauth, customBaseURL: nil))
    check("switch offered: Responses-type relay + gpt id", offersFastMode(providerType: .openAIResponses, modelId: "gpt-5.6", credential: .apiKey, customBaseURL: "https://sub2api.example/v1"))
    check("switch NOT offered: api-key OpenAI + gpt id", !offersFastMode(providerType: .openAI, modelId: "gpt-5.6", credential: .apiKey, customBaseURL: nil))
    check("switch NOT offered: OAuth OpenAI but a custom base", !offersFastMode(providerType: .openAI, modelId: "gpt-5.6", credential: .oauth, customBaseURL: "https://relay.example/v1"))
    check("switch NOT offered: non-gpt id on a Responses relay", !offersFastMode(providerType: .openAIResponses, modelId: "claude-opus-4-6", credential: .apiKey, customBaseURL: "https://sub2api.example/v1"))
    check("the xAI check runs BEFORE the gpt guard, or grok would never show it", offersFastMode(providerType: .xAI, modelId: "grok-4.20", credential: .apiKey, customBaseURL: nil))
}

print("▶️  2. OpenRouter's anthropic/ namespace gets an explicit cache breakpoint (GH#191)")
do {
    check("openrouter + anthropic/claude-sonnet-4 → cache_control", needsOpenRouterAnthropicCacheControl(openrouter, modelId: "anthropic/claude-sonnet-4"))
    check("openrouter + anthropic/claude-opus-4.1 → cache_control", needsOpenRouterAnthropicCacheControl(openrouter, modelId: "anthropic/claude-opus-4.1"))
    check("ANTHROPIC/Claude-Opus-5 (case) → cache_control", needsOpenRouterAnthropicCacheControl(openrouter, modelId: "ANTHROPIC/Claude-Opus-5"))
    check("openrouter + openai/gpt-5.6 → NOT touched (caches with no opt-in)", !needsOpenRouterAnthropicCacheControl(openrouter, modelId: "openai/gpt-5.6"))
    check("openrouter + x-ai/grok-4 → NOT touched", !needsOpenRouterAnthropicCacheControl(openrouter, modelId: "x-ai/grok-4"))
    check("openrouter + moonshotai/kimi-k2 → NOT touched", !needsOpenRouterAnthropicCacheControl(openrouter, modelId: "moonshotai/kimi-k2"))
    // Scope: the SAME model id on any other endpoint is byte-identical.
    check("a bare claude id on OpenRouter is not the anthropic/ namespace", !needsOpenRouterAnthropicCacheControl(openrouter, modelId: "claude-sonnet-4"))
    check("anthropic/… on a non-OpenRouter relay → not touched", !needsOpenRouterAnthropicCacheControl(relay, modelId: "anthropic/claude-sonnet-4"))
    check("anthropic/… on Mistral-compat (also useOpenRouterCompat) → not touched", !needsOpenRouterAnthropicCacheControl(mistral, modelId: "anthropic/claude-sonnet-4"))
    check("…because the gate is the HOST, not the compat flag", mistral.useOpenRouterCompat && !mistral.isOpenRouter)
    check("official OpenAI (nil base) → not OpenRouter", !official.isOpenRouter)
    check("a relay whose path mentions openrouter.ai IS matched (host check is a substring — documented fail-open)", Endpoint(customBaseURL: "https://proxy.example/openrouter.ai/v1").isOpenRouter)
}

print("▶️  3. the openai-native reasoning prefix set (iOS: an explicit list)")
do {
    for id in ["o1", "o1-mini", "o1-pro", "o3", "o3-mini", "o4-mini", "gpt-5", "gpt-5.6-sol", "gpt-4o", "gpt-4.1"] {
        check("\(id) → openai-native", isOpenAINative(id))
    }
    for id in ["gpt-6-astra", "gpt-3.5-turbo", "chatgpt-4o-latest", "openai/gpt-5.6", "deepseek-v4", "glm-5.2", "grok-4.20", "qwen3-max", "omni-moderation-latest", "olmo-3-32b"] {
        check("\(id) → NOT openai-native", !isOpenAINative(id))
    }
    // The prefix set and the rule registry must agree, or the rule that matched
    // would emit through the wrong sub-shape.
    func registryMatches(_ id: String) -> Bool {
        let lid = id.lowercased()
        return openAINativePatterns.contains { lid.hasPrefix(String($0.dropLast())) }
    }
    for id in ["o1-mini", "o3", "o4-mini", "gpt-5.6-sol", "gpt-4o", "gpt-6-astra", "grok-4", "o200k-test", "olmo-3"] {
        checkEq("registry and emitter agree on \(id)", registryMatches(id), isOpenAINative(id))
    }
    // gpt-6-astra is NOT openai-native: it falls to the providerTypeDefault,
    // which is the CLAMPED generic path. That is the live behaviour, so pin it
    // rather than assume the list tracks every new OpenAI family.
    note("gpt-6-astra is not in the prefix list, so it resolves via the generic clamped path — intended, since the catalog declares its tiers")
    // Cross-platform note: Android's half of M20 pins `startsWith("o")`, which
    // is broader than this list. Non-OpenAI ids beginning with "o" exist and
    // WOULD be misrouted there; iOS's explicit list cannot hit them, which is
    // what these two assertions record.
    check("iOS: omni-moderation-latest is not openai-native", !isOpenAINative("omni-moderation-latest"))
    check("Android's broader predicate WOULD claim it (documented divergence)", androidStartsWithO("omni-moderation-latest"))
    check("same for olmo-3-32b", !isOpenAINative("olmo-3-32b") && androidStartsWithO("olmo-3-32b"))
}

print("▶️  4. max_completion_tokens vs legacy max_tokens")
do {
    checkEq("official OpenAI, streaming → max_completion_tokens + stream_options",
            json(tokenLimitKeys(official, maxTokens: 32000, stream: true)),
            #"{"max_completion_tokens":32000,"stream_options":{"include_usage":true}}"#)
    checkEq("official OpenAI, non-streaming → no stream_options",
            json(tokenLimitKeys(official, maxTokens: 32000, stream: false)),
            #"{"max_completion_tokens":32000}"#)
    checkEq("OpenRouter → legacy max_tokens, never stream_options",
            json(tokenLimitKeys(openrouter, maxTokens: 32000, stream: true)),
            #"{"max_tokens":32000}"#)
    checkEq("Mistral (a strict subset of OpenRouter compat) → legacy too",
            json(tokenLimitKeys(mistral, maxTokens: 8192, stream: true)),
            #"{"max_tokens":8192}"#)
    check("the two names are mutually exclusive on every endpoint", [official, openrouter, mistral, relay, xai].allSatisfy {
        let keys = Set(tokenLimitKeys($0, maxTokens: 1000, stream: true).keys)
        return keys.contains("max_tokens") != keys.contains("max_completion_tokens")
    })
    check("a plain relay keeps the modern name (fail-open, unchanged body)", tokenLimitKeys(relay, maxTokens: 1000, stream: true)["max_completion_tokens"] != nil)
}

print("▶️  5. temperature on the OpenAI path is opt-in, so reasoning models need no gate")
do {
    // Android's half of M20 asks for "no temperature for reasoning models".
    // iOS reaches the same outcome structurally rather than with a predicate:
    // neither chat-completions nor Responses writes the caller's `temperature`
    // into the body at all. The ONLY writer is applyModelOverrides, i.e. a value
    // the user typed for that specific model — so an o-series / gpt-5 request
    // carries no temperature unless its owner deliberately set one, and there is
    // no reasoning-family branch to drift.
    func applyModelOverrides(into body: inout [String: Any], temperature: Double?, topP: Double?, extra: [String: String] = [:]) {
        if let t = temperature, body["temperature"] == nil { body["temperature"] = t }
        if let p = topP, body["top_p"] == nil { body["top_p"] = p }
        for (k, v) in extra where body[k] == nil { body[k] = v }
    }
    var base: [String: Any] = ["model": "o3", "messages": []]
    applyModelOverrides(into: &base, temperature: nil, topP: nil)
    check("no stored override → no temperature key for a reasoning model", base["temperature"] == nil)
    var withOverride: [String: Any] = ["model": "gpt-5.6-sol"]
    applyModelOverrides(into: &withOverride, temperature: 0.2, topP: 0.9)
    checkEq("a user-set override IS sent, reasoning family included (their configuration, their 400)", withOverride["temperature"] as? Double, 0.2)
    var preset: [String: Any] = ["model": "gpt-5.6-sol", "temperature": 1.0]
    applyModelOverrides(into: &preset, temperature: 0.2, topP: nil)
    checkEq("an explicit per-request value beats the stored override", preset["temperature"] as? Double, 1.0)
    let builders = source("Providers/OpenAI/OpenAIProvider.swift")
    if !builders.isEmpty {
        // The load-bearing assertion: `temperature` must not be written anywhere
        // except applyModelOverrides. If a builder starts writing it, this fails
        // and the reasoning-family question becomes live on iOS too.
        let writes = builders.split(separator: "\n")
            .filter { $0.contains("body[\"temperature\"]") && !$0.contains("body[\"temperature\"] == nil") }
        checkEq("exactly one writer of body[\"temperature\"] in the whole provider", writes.count, 1)
        check("…and it is the override merge", writes.first?.contains("= t") ?? false)
        check("…guarded on \"not already present\"", builders.contains("if let t = overrideTemperature, body[\"temperature\"] == nil {"))
    }
}

print("▶️  6. shipping sources still carry the pinned lines")
do {
    let op = source("Providers/OpenAI/OpenAIProvider.swift")
    let agent = source("Providers/OpenAI/OpenAIAgentProvider.swift")
    let res = source("Providers/Thinking/ThinkingRuleResolver.swift")
    let view = source("Views/Chat/AIChatView.swift")
    if op.isEmpty || agent.isEmpty || res.isEmpty || view.isEmpty { print("  ⏭  sources not readable") } else {
        check("Responses builder gates fast mode on a gpt id", op.contains("if UserDefaults.standard.bool(forKey: Self.fastModeDefaultsKey),\n           model.id.lowercased().contains(\"gpt\") {"))
        check("agent Responses path gates it the same way", agent.contains("if UserDefaults.standard.bool(forKey: OpenAIProvider.fastModeDefaultsKey),\n           model.id.lowercased().contains(\"gpt\") {"))
        check("xAI priority reads the SAME defaults key and adds isXAI", op.contains("isXAI && UserDefaults.standard.bool(forKey: Self.fastModeDefaultsKey)"))
        check("the UI gate checks xAI before the gpt guard", (view.range(of: "if instance.providerType == .xAI { return true }")?.lowerBound ?? view.endIndex)
              < (view.range(of: "guard modelId.lowercased().contains(\"gpt\") else { return false }")?.lowerBound ?? view.startIndex))
        check("cache_control gate is host AND anthropic/ prefix", op.contains("isOpenRouter && model.id.lowercased().hasPrefix(\"anthropic/\")"))
        check("…and writes an ephemeral breakpoint", op.contains("body[\"cache_control\"] = [\"type\": \"ephemeral\"]"))
        check("isOpenRouter is a host check, not useOpenRouterCompat", op.contains("return base.contains(\"openrouter.ai\")"))
        check("the emit branch's native prefix list", res.contains("let isOpenAINative = lid.hasPrefix(\"o1\") || lid.hasPrefix(\"o3\") || lid.hasPrefix(\"o4\")\n                || lid.hasPrefix(\"gpt-5\") || lid.hasPrefix(\"gpt-4\")"))
        for p in openAINativePatterns {
            check("registry still registers \(p)", res.contains(".modelPattern(\"\(p)\")"))
        }
        check("legacy token name gated on useOpenRouterCompat", op.contains("if useOpenRouterCompat {\n            body[\"max_tokens\"] = maxTokens"))
        check("…otherwise max_completion_tokens + stream_options", op.contains("body[\"max_completion_tokens\"] = maxTokens"))
        check("Mistral documents itself as an OpenRouter-compat subset", op.contains("var isMistral: Bool = false"))
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
