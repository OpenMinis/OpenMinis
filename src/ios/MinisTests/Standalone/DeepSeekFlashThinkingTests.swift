// Tests for [T-deepseek-flash-scope] — GH#356. DeepSeek now recommends the
// bare `deepseek-flash`; the vendor-native thinking rule only matched
// `*deepseek-v4*`, so the new id fell through to the generic reasoning_effort
// default and no ladder ceiling applied.
//
// Standalone (`swift DeepSeekFlashThinkingTests.swift`) like its neighbours:
// deps/libs/libish_emu.a is device-only arm64, so the app cannot link for a
// simulator and an XCTest bundle has nowhere to run.

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Reproduced: the glob, verbatim from ThinkingRule.Scope

func glob(_ pattern: String, matches input: String) -> Bool {
    let parts = pattern.components(separatedBy: "*")
    if parts.count == 1 { return input == pattern }
    var cursor = input.startIndex
    for (i, part) in parts.enumerated() {
        if part.isEmpty { continue }
        if i == 0 {
            guard input.hasPrefix(part) else { return false }
            cursor = input.index(cursor, offsetBy: part.count)
            continue
        }
        if i == parts.count - 1 && !pattern.hasSuffix("*") {
            guard input[cursor...].hasSuffix(part) else { return false }
            return input.distance(from: cursor, to: input.endIndex) >= part.count
        }
        guard let r = input[cursor...].range(of: part) else { return false }
        cursor = r.upperBound
    }
    return true
}

func matches(_ pattern: String, _ modelId: String) -> Bool {
    glob(pattern.lowercased().replacingOccurrences(of: ".", with: "-"),
         matches: modelId.lowercased().replacingOccurrences(of: ".", with: "-"))
}

/// The two officialVendor DeepSeek rules, in registration order. Returns the
/// label of the first that claims the id, or nil → generic default.
func deepSeekRule(for id: String) -> String? {
    if matches("*deepseek-v4*", id) { return "deepseek-v4-official" }
    if matches("deepseek-flash*", id) { return "deepseek-flash-official" }
    return nil
}

/// clampEffort, verbatim from OpenAIAgentProvider: nearest declared tier,
/// preferring to walk DOWN.
func clampEffort(_ effort: String, to values: [String]?) -> String {
    guard let values, !values.isEmpty else { return effort }
    if values.contains(effort) { return effort }
    let ladder = ["none", "minimal", "low", "medium", "high", "xhigh", "max"]
    guard let want = ladder.firstIndex(of: effort) else { return effort }
    let declared = values.compactMap { v in ladder.firstIndex(of: v).map { ($0, v) } }
        .sorted { $0.0 < $1.0 }
    guard !declared.isEmpty else { return effort }
    if let below = declared.last(where: { $0.0 <= want }) { return below.1 }
    return declared.first!.1
}

/// What models.dev declares for the DeepSeek flash line. Both verified present
/// in Resources/models-dev-api.json at the time of writing: `deepseek-v4-flash`
/// under the official `deepseek` provider, `deepseek-flash` under `opencode-go`
/// (reachable because the cross-provider index is keyed on the bare id).
let deepSeekDeclared = ["low", "high", "max"]

print("\n[1] The rule scope — problem one")
do {
    // The reported bug.
    checkEq("deepseek-flash now resolves to the vendor-native rule",
            deepSeekRule(for: "deepseek-flash"), "deepseek-flash-official")
    check("PRE-FIX it matched nothing and fell to the generic default",
          matches("*deepseek-v4*", "deepseek-flash"), false)

    // Regression: the legacy id keeps its original rule, unchanged.
    checkEq("deepseek-v4-flash still uses the ORIGINAL rule",
            deepSeekRule(for: "deepseek-v4-flash"), "deepseek-v4-official")
    checkEq("deepseek-v4-pro is untouched",
            deepSeekRule(for: "deepseek-v4-pro"), "deepseek-v4-official")

    // Anchored, so future sub-variants are covered…
    checkEq("a future deepseek-flash-lite is covered",
            deepSeekRule(for: "deepseek-flash-lite"), "deepseek-flash-official")
    // …without claiming DeepSeek models whose wire shape may differ.
    check("deepseek-chat is NOT claimed", deepSeekRule(for: "deepseek-chat") == nil)
    check("deepseek-reasoner is NOT claimed", deepSeekRule(for: "deepseek-reasoner") == nil)
    // A bare `*deepseek*` would have caught those — that is why it was not used.
    check("a naive *deepseek* WOULD have over-matched", matches("*deepseek*", "deepseek-chat"))

    // Case and dot normalisation come free from Scope.matches.
    checkEq("uppercase is normalised", deepSeekRule(for: "DeepSeek-Flash"), "deepseek-flash-official")
    // Both rules share wireFormat/echo, so which one claims an id is cosmetic —
    // what matters is that SOME officialVendor rule does.
    for id in ["deepseek-flash", "deepseek-v4-flash", "deepseek-flash-lite"] {
        check("\(id) gets a vendor-native (deepSeekSibling) rule", deepSeekRule(for: id) != nil)
    }
}

print("\n[2] Effort clamping — problem two, on the wire")
do {
    // The reported symptom: xhigh reaching DeepSeek, which rejects it.
    checkEq("xhigh is clamped to the nearest declared tier at or below",
            clampEffort("xhigh", to: deepSeekDeclared), "high")
    check("…and is NOT sent verbatim", clampEffort("xhigh", to: deepSeekDeclared) != "xhigh")
    // Declared tiers pass through untouched.
    for t in deepSeekDeclared {
        checkEq("declared tier \(t) passes through", clampEffort(t, to: deepSeekDeclared), t)
    }
    // medium is undeclared too, and walks DOWN rather than up — overshooting
    // costs the user money they did not ask for.
    checkEq("medium walks down to low", clampEffort("medium", to: deepSeekDeclared), "low")

    // The failure mode the catalog rule guards: NO declaration at all.
    checkEq("with no declaration, xhigh would go out verbatim",
            clampEffort("xhigh", to: nil), "xhigh")
}

print("\n[3] The UI ceiling — where the catalog rule does and does not apply")
do {
    // selectableThinkingLevels, from LLMTypes.
    func selectable(_ declared: [String]?) -> [String] {
        guard let declared, !declared.isEmpty else { return [] }
        let order = ["low", "medium", "high", "xhigh", "max"]
        let set = Set(declared.map { $0.lowercased() })
        return order.filter { set.contains($0) }
    }
    let levels = selectable(deepSeekDeclared)
    checkEq("the picker offers exactly the declared tiers", levels, ["low", "high", "max"])
    check("xhigh is never offered when models.dev resolves", !levels.contains("xhigh"))
    checkEq("so the ceiling is already max, WITHOUT the catalog rule",
            levels.last, "max")

    // Which is why the catalog rule is a fallback, not the primary fix: it is
    // consulted only when the declared set is empty.
    check("an empty declaration is what falls back to the catalog",
          selectable(nil).isEmpty)
}

// MARK: - [3b] Round-2 item M14: the two DeepSeek rules side by side, and the
// registry ORDER that decides which rule a request actually gets.
//
// The order is load-bearing and was got wrong once: the first version of the
// registry hoisted the unified-gateway rule ABOVE qwen and the OpenAI-native
// patterns, which silently flipped two real cases (a qwen id on Ark stopped
// sending enable_thinking; a gpt-5 id on DashScope started). The old if-return
// chain applied the unified guard ONLY to deepseek-v4, so the gateway rule must
// sit BELOW those two and ABOVE the DeepSeek pair.

/// The built-in registry, in registration order, reduced to (label, scope).
/// Mirrors ThinkingRuleResolver.builtInRules(for:) ~L214-361.
func builtInLabels(isMistral: Bool, isOpenRouter: Bool, isDashScope: Bool, unified: Bool) -> [(label: String, pattern: String?)] {
    var rules: [(String, String?)] = []
    if isMistral { rules.append(("mistral-official", nil)) }
    if isOpenRouter { rules.append(("openrouter", nil)) }
    for p in ["o1*", "o3*", "o4*", "gpt-5*", "gpt-4*"] { rules.append(("openai-native", p)) }
    rules.append((isDashScope ? "qwen-dashscope" : "qwen-root-only", "*qwen*"))
    if unified { rules.append(("unified-gateway(ark|azure|venice)", nil)) }
    rules.append(("deepseek-v4-official", "*deepseek-v4*"))
    rules.append(("deepseek-flash-official", "deepseek-flash*"))
    rules.append(("openai-compatible-default", nil))
    return rules
}
/// Stage A: first match wins, user rules ahead of the built-ins.
func winningLabel(_ modelId: String, isMistral: Bool = false, isOpenRouter: Bool = false,
                  isDashScope: Bool = false, unified: Bool = false, userPatterns: [(String, String)] = []) -> String {
    for (label, pattern) in userPatterns where matches(pattern, modelId) { return label }
    for rule in builtInLabels(isMistral: isMistral, isOpenRouter: isOpenRouter, isDashScope: isDashScope, unified: unified) {
        guard let p = rule.pattern else { return rule.label }
        if matches(p, modelId) { return rule.label }
    }
    return "openai-compatible-default"
}

print("\n[3b] deepseek-flash and deepseek-v4 side by side, in the real registry")
do {
    // Both ids reach a DeepSeek-native rule on a plain OpenAI-compatible provider,
    // and each keeps its OWN rule — the sibling was added, not widened.
    checkEq("deepseek-flash → deepseek-flash-official", winningLabel("deepseek-flash"), "deepseek-flash-official")
    checkEq("deepseek-v4-flash → deepseek-v4-official", winningLabel("deepseek-v4-flash"), "deepseek-v4-official")
    checkEq("deepseek-v4 → deepseek-v4-official", winningLabel("deepseek-v4"), "deepseek-v4-official")
    checkEq("deepseek-flash-lite → deepseek-flash-official", winningLabel("deepseek-flash-lite"), "deepseek-flash-official")
    checkEq("DeepSeek-Flash (case) → deepseek-flash-official", winningLabel("DeepSeek-Flash"), "deepseek-flash-official")
    // The v4 rule is registered first, so an id matching both goes to v4. That is
    // cosmetic (identical wireFormat / echo) but pinned so a future reorder is a
    // deliberate decision rather than a surprise.
    checkEq("deepseek-v4-flash matches BOTH patterns and v4 wins on order", winningLabel("deepseek-v4-flash"), "deepseek-v4-official")
    check("…it really does match both", matches("*deepseek-v4*", "deepseek-v4-flash") && !matches("deepseek-flash*", "deepseek-v4-flash"))
    // Non-flash DeepSeek ids stay on the generic default — the wire shape for
    // those has never been probed, so claiming them would be a guess.
    checkEq("deepseek-chat → generic default", winningLabel("deepseek-chat"), "openai-compatible-default")
    checkEq("deepseek-reasoner → generic default", winningLabel("deepseek-reasoner"), "openai-compatible-default")

    // The unified gateway must NOT outrank qwen or the openai-native prefixes…
    checkEq("qwen on Ark keeps its native rule, not the gateway", winningLabel("qwen3.8-max", unified: true), "qwen-root-only")
    checkEq("gpt-5.6 on Ark keeps openai-native", winningLabel("gpt-5.6-sol", unified: true), "openai-native")
    checkEq("o3 on Ark keeps openai-native", winningLabel("o3-mini", unified: true), "openai-native")
    checkEq("gpt-5 on DashScope keeps openai-native (the mirrored Android case)", winningLabel("gpt-5.6", isDashScope: true, unified: false), "openai-native")
    checkEq("qwen on DashScope takes the dual shape", winningLabel("qwen3.8-max", isDashScope: true), "qwen-dashscope")
    // …but it MUST outrank the DeepSeek pair, which is exactly what the old
    // chain's `!unifiedReasoningEffort` guard did.
    checkEq("deepseek-v4 on Ark → the gateway rule", winningLabel("deepseek-v4", unified: true), "unified-gateway(ark|azure|venice)")
    checkEq("deepseek-flash on Ark → the gateway rule too", winningLabel("deepseek-flash", unified: true), "unified-gateway(ark|azure|venice)")
    checkEq("deepseek-flash on Venice → the gateway rule", winningLabel("deepseek-flash", unified: true), "unified-gateway(ark|azure|venice)")
    checkEq("glm on Ark → the gateway rule (a self-reasoning family it must claim)", winningLabel("glm-5.2", unified: true), "unified-gateway(ark|azure|venice)")
    checkEq("mimo on Ark → the gateway rule", winningLabel("mimo-v2.5", unified: true), "unified-gateway(ark|azure|venice)")
    // Mistral and OpenRouter outrank everything, DeepSeek ids included.
    checkEq("deepseek-flash on Mistral → omitEverything wins", winningLabel("deepseek-flash", isMistral: true), "mistral-official")
    checkEq("deepseek-flash on OpenRouter → nested reasoning wins", winningLabel("deepseek/deepseek-flash", isOpenRouter: true), "openrouter")
    checkEq("qwen on OpenRouter → openrouter, not the qwen rule", winningLabel("qwen/qwen3-max", isOpenRouter: true), "openrouter")
    checkEq("gpt-5 on OpenRouter → openrouter, not openai-native", winningLabel("openai/gpt-5.6", isOpenRouter: true), "openrouter")
    // Mistral outranks OpenRouter when both flags are set (Mistral sets the
    // OpenRouter-compat body shape as a strict subset).
    checkEq("Mistral outranks OpenRouter", winningLabel("mistral-large", isMistral: true, isOpenRouter: true), "mistral-official")
    // A user rule shadows every built-in, on any endpoint.
    checkEq("a user rule beats deepseek-flash-official", winningLabel("deepseek-flash", userPatterns: [("mine", "*deepseek*")]), "mine")
    checkEq("…and beats the gateway rule", winningLabel("deepseek-flash", unified: true, userPatterns: [("mine", "*")]), "mine")
    // Every id resolves to something: stage A can never fall through.
    for id in ["deepseek-flash", "deepseek-v4", "qwen3-max", "o1", "gpt-4o", "glm-5.2", "totally-unknown", ""] {
        check("\(id.isEmpty ? "<empty>" : id) always resolves to some rule", !winningLabel(id).isEmpty)
    }
}

print("\n[4] Shipping sources match these assumptions")
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let resolver = source("Providers/Thinking/ThinkingRuleResolver.swift")
let catalog  = source("Providers/ThinkingLevelCatalog.swift")

if resolver.isEmpty || catalog.isEmpty { print("  ⏭  sources not readable") } else {
    check("the new rule exists, anchored, with the sibling wire format",
          resolver.contains("scope: .modelPattern(\"deepseek-flash*\")")
          && resolver.contains("label: \"deepseek-flash-official\""))
    // It must sit in the officialVendor block and keep the same echo policy as
    // its neighbour, or the two ids would behave differently on tool turns.
    if let r = resolver.range(of: "label: \"deepseek-flash-official\"") {
        let window = String(resolver[..<r.lowerBound].suffix(420))
        check("…same wireFormat as the v4 rule", window.contains("wireFormat: .deepSeekSibling"))
        check("…same reasoning echo policy",
              window.contains("fieldName: \"reasoning_content\", timing: .afterToolUseOnly"))
        check("…and is an officialVendor rule", window.contains("kind: .officialVendor"))
    }
    check("the ORIGINAL v4 rule is untouched",
          resolver.contains("scope: .modelPattern(\"*deepseek-v4*\")")
          && resolver.contains("label: \"deepseek-v4-official\""))

    // [M14] The registration ORDER, asserted by source position: qwen and the
    // openai-native prefixes must precede the unified-gateway rule, which must
    // precede both DeepSeek rules, which must precede the generic default.
    func at(_ needle: String) -> Int? { resolver.range(of: needle).map { resolver.distance(from: resolver.startIndex, to: $0.lowerBound) } }
    if let native = at("label: \"openai-native\""), let qwen = at(".modelPattern(\"*qwen*\")"),
       let unified = at("label: \"unified-gateway(ark|azure|venice)\""),
       let v4 = at("label: \"deepseek-v4-official\""), let flash = at("label: \"deepseek-flash-official\""),
       let dflt = at("label: \"openai-compatible-default\"") {
        check("openai-native is registered before the unified gateway", native < unified)
        check("qwen is registered before the unified gateway", qwen < unified)
        check("the unified gateway is registered before deepseek-v4", unified < v4)
        check("deepseek-v4 before deepseek-flash", v4 < flash)
        check("both DeepSeek rules before the generic default", flash < dflt)
    } else {
        print("  ❌ registry labels not found — the rule set was renamed"); failures += 1
    }
    check("the order is documented as load-bearing", resolver.contains("ORDER IS LOAD-BEARING FROM HERE DOWN"))
    check("the hoisted-gateway regression is recorded", resolver.contains("The first version of this registry hoisted the")
          && resolver.contains("unified-gateway rule ABOVE qwen and the OpenAI-native patterns"))
    check("Mistral is registered first, outranking everything", (at("label: \"mistral-official\"") ?? Int.max) < (at("label: \"openrouter\"") ?? 0))

    check("the catalog gained a DeepSeek ceiling",
          catalog.contains("$0.contains(\"deepseek-flash\") || $0.contains(\"deepseek-v4\")"))
    check("…at .max, the tier DeepSeek actually accepts",
          catalog.contains("|| $0.contains(\"deepseek-v4\") }, .max)"))
    // Other vendors' ceilings must be untouched.
    for existing in ["gpt-6-astra", "gpt-5.5", "mimo", "seed-", "claude-opus-4"] {
        check("existing rule for \(existing) still present", catalog.contains(existing))
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
