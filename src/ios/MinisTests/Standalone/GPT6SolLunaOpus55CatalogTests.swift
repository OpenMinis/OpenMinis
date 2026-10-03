#!/usr/bin/env swift
// [T-gpt6-sol-luna, T-anthropic-opus55] Catalog entries for three models added
// from CLIProxyAPI registry commit 2430354330af ("update model definitions and
// bump codex client version to 0.155.0"):
//
//   claude-opus-5-5   Claude Opus 5.5   Anthropic          1M ctx / 128k out
//   gpt-6-sol         GPT-6 Sol         OpenAI Codex OAuth
//   gpt-6-luna        GPT-6 Luna        OpenAI Codex OAuth
//
// The behavioural assertions (level → wire effort, thinking-disabled gating)
// live in MinisTests/GPT6SolLunaOpus55Tests.swift, which links the app and can
// call the real functions. THIS file is the Standalone half: it re-reads the
// shipping sources, so the catalog wiring is guarded by the suite that actually
// runs on every change — `deps/libs/libish_emu.a` is device-arm64 only, so the
// XCTest half cannot execute here.
//
// Three hazards are pinned, each of which has bitten before:
//
//  1. `supportsReasoning: true` must be EXPLICIT on the two GPT-6 ids. A
//     brand-new id is absent from models-dev-api.json, so
//     `ModelsDevAPI.enrichModels()` leaves the flag nil and
//     `reasoningEffort(for:)` — which guards on `supportsReasoning ?? false` —
//     collapses every level to the Codex-OAuth fallback "low". Exactly the
//     gpt-6-astra regression ([T-gpt6-astra-effort]).
//  2. Two client-version floors move together with the ids: Anthropic gates
//     claude-opus-5-5 on `claude-cli >= 2.1.280`, OpenAI gates gpt-6-sol/luna
//     on Codex client >= 0.155.0. A stale fingerprint reads as a 400 that looks
//     like a bad catalog entry rather than a version gate.
//  3. Opus 5.5 rejects `thinking.type: "disabled"`. It is already correct
//     because the gate is `major == 4 && minor >= 6` and this is major 5 — an
//     accident worth pinning, since widening that predicate would silently
//     start sending a payload the model 400s on.
//
// Run: swift GPT6SolLunaOpus55CatalogTests.swift
import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(label)") }
    else { print("  ❌ \(label)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
func codeOnly(_ src: String) -> String {
    src.components(separatedBy: "\n")
        .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
        .joined(separator: "\n")
}

let types = codeOnly(source("Providers/LLMTypes.swift"))
let catalog = codeOnly(source("Providers/ThinkingLevelCatalog.swift"))
let openai = codeOnly(source("Providers/OpenAI/OpenAIProvider.swift"))
let anthropicUA = codeOnly(source("Providers/Anthropic/OAuthHTTPClient.swift"))
let anthropic = codeOnly(source("Providers/Anthropic/AnthropicProvider.swift"))
guard !types.isEmpty, !catalog.isEmpty, !openai.isEmpty, !anthropicUA.isEmpty, !anthropic.isEmpty else {
    print("  ⏭  sources not readable from \(#filePath)")
    exit(0)
}

/// Compare dotted version strings numerically ("0.9.0" < "0.155.0").
func atLeast(_ v: String, _ floor: String) -> Bool {
    let a = v.split(separator: ".").map { Int($0) ?? 0 }
    let b = floor.split(separator: ".").map { Int($0) ?? 0 }
    for i in 0..<max(a.count, b.count) {
        let x = i < a.count ? a[i] : 0, y = i < b.count ? b[i] : 0
        if x != y { return x > y }
    }
    return true
}

print("▶️  1. the three catalog entries ship")
do {
    check("claude-opus-5-5 is defined",
          types.contains("static let claudeOpus55 = LLMModel(")
          && types.contains("id: \"claude-opus-5-5\""))
    check("…with 1M context and 128k output",
          types.range(of: "static let claudeOpus55 = LLMModel(").map {
              let tail = types[$0.upperBound...].prefix(300)
              return tail.contains("contextWindow: 1_000_000") && tail.contains("maxOutputTokens: 128_000")
          } ?? false)
    check("gpt-6-sol is defined",
          types.contains("static let gpt6Sol = LLMModel(") && types.contains("id: \"gpt-6-sol\""))
    check("gpt-6-luna is defined",
          types.contains("static let gpt6Luna = LLMModel(") && types.contains("id: \"gpt-6-luna\""))
}

print("\n▶️  2. they are reachable from the pickers")
do {
    let anthropicList: String = {
        guard let s = types.range(of: "static let allAnthropic: [LLMModel] = ["),
              let e = types.range(of: "]", range: s.upperBound..<types.endIndex) else { return "" }
        return String(types[s.upperBound..<e.lowerBound])
    }()
    let codexList: String = {
        guard let s = types.range(of: "static let allOpenAICodexOAuth: [LLMModel] = ["),
              let e = types.range(of: "]", range: s.upperBound..<types.endIndex) else { return "" }
        return String(types[s.upperBound..<e.lowerBound])
    }()
    check("the Anthropic list was located", !anthropicList.isEmpty)
    check("allAnthropic carries claudeOpus55", anthropicList.contains(".claudeOpus55"))
    check("the Codex list was located", !codexList.isEmpty)
    check("allOpenAICodexOAuth carries gpt6Sol", codexList.contains(".gpt6Sol"))
    check("allOpenAICodexOAuth carries gpt6Luna", codexList.contains(".gpt6Luna"))
    // GPT6AstraReasoningTests asserts astra is FIRST; keep that true.
    check("gpt6Astra is still the first entry",
          codexList.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix(".gpt6Astra"))
}

print("\n▶️  3. supportsReasoning is explicit on both GPT-6 ids")
do {
    // Scoped to each definition — a bare grep for the flag would match astra's.
    func body(_ decl: String) -> String {
        guard let s = types.range(of: decl),
              let e = types.range(of: ")", range: s.upperBound..<types.endIndex) else { return "" }
        return String(types[s.upperBound..<e.lowerBound])
    }
    check("gpt6Sol declares supportsReasoning: true",
          body("static let gpt6Sol = LLMModel(").contains("supportsReasoning: true"))
    check("gpt6Luna declares supportsReasoning: true",
          body("static let gpt6Luna = LLMModel(").contains("supportsReasoning: true"))
}

print("\n▶️  4. thinking ceilings")
do {
    check("a rule covers gpt-6-sol and gpt-6-luna",
          catalog.contains("{ $0.hasPrefix(\"gpt-6-sol\") || $0.hasPrefix(\"gpt-6-luna\") }, .max)"))
    // Sol's "no ultra" IS the .max ceiling — the picker stops there, and the
    // shared wire clamp folds .max/.ultra to "max" anyway.
    check("the ceiling is .max, not .ultra",
          !catalog.contains("hasPrefix(\"gpt-6-sol\") || $0.hasPrefix(\"gpt-6-luna\") }, .ultra)"))
    check("the shared ultra→max wire clamp is intact",
          codeOnly(source("Providers/OpenAI/OpenAIAgentProvider.swift"))
              .contains("case .max, .ultra: \"max\""))
}

print("\n▶️  4b. Claude Opus 5.5 reaches Max in the picker")
do {
    // [T-anthropic-opus55-ceiling] Hotfix on ff6692c76. The Opus family rule
    // only matched `claude-opus-4`, and claude-opus-5-5 is NOT in
    // models-dev-api.json (the catalog has claude-opus-5, -5-fast and
    // -5-thinking, no -5-5). So `selectableThinkingLevels` was empty AND the
    // rule missed, and `catalogMaxThinkingLevel` fell through to
    // `ruleTop ?? .xhigh` — Max silently absent from the picker, no error.
    check("a rule covers the claude-opus-5 family",
          catalog.contains("{ Self.normalizedHasPrefix($0, \"claude-opus-5\") }, .max)"))
    check("…and the 4.x rule is still there",
          catalog.contains("{ Self.normalizedHasPrefix($0, \"claude-opus-4\") }, .max)"))

    // Behavioural, not textual: port the matcher + the ceiling fallback and run
    // the real ids through them. A source-presence check alone would not catch
    // a normalizedHasPrefix that stopped normalising.
    func normalizedHasPrefix(_ id: String, _ prefix: String) -> Bool {
        id.replacingOccurrences(of: ".", with: "-").hasPrefix(prefix)
    }
    /// The shipping rule list, in order, for the ids this section cares about.
    func declaredMaxLevel(for id: String) -> String? {
        if normalizedHasPrefix(id, "claude-opus-4") { return "max" }
        if normalizedHasPrefix(id, "claude-opus-5") { return "max" }
        return nil
    }
    /// Rank the levels so the port's `max` means "higher tier", not
    /// lexicographic order — `max("xhigh", "max")` as Strings is "xhigh", which
    /// would model the real `Swift.max` on the `ThinkingLevel` enum backwards.
    let order = ["off": 0, "low": 1, "medium": 2, "high": 3, "xhigh": 4, "max": 5, "ultra": 6]
    func higher(_ a: String, _ b: String) -> String {
        (order[a] ?? -1) >= (order[b] ?? -1) ? a : b
    }
    /// `LLMModel.catalogMaxThinkingLevel`'s tail: declared tiers win, else the
    /// rule, else the historical .xhigh default.
    func ceiling(id: String, declaredTiers: [String]) -> String {
        if let top = declaredTiers.last { return higher(top, declaredMaxLevel(for: id) ?? top) }
        return declaredMaxLevel(for: id) ?? "xhigh"
    }

    checkEq("declaredMaxLevel(claude-opus-5-5) == max", declaredMaxLevel(for: "claude-opus-5-5"), "max")
    checkEq("claude-opus-5 too", declaredMaxLevel(for: "claude-opus-5"), "max")
    // A proxy may return dots. `claude-opus-5.5` would match even WITHOUT the
    // dot normalisation (the dot falls after the prefix), so it proves only
    // that such ids are covered — not that normalisation works. This one needs
    // it: the dot lands inside the prefix span.
    checkEq("…and a dotted id that genuinely needs normalising",
            declaredMaxLevel(for: "claude-opus.5-5"), "max")

    // The reported symptom, reproduced through the real fallback: unenriched
    // (no declared tiers) is exactly the claude-opus-5-5 situation.
    checkEq("unenriched claude-opus-5-5 now ceilings at max",
            ceiling(id: "claude-opus-5-5", declaredTiers: []), "max")
    // Non-vacuous: without the new rule this is the bug.
    func ceilingPreFix(id: String, declaredTiers: [String]) -> String {
        let rule = normalizedHasPrefix(id, "claude-opus-4") ? "max" : nil
        if let top = declaredTiers.last { return higher(top, rule ?? top) }
        return rule ?? "xhigh"
    }
    checkEq("PRE-FIX it silently fell back to xhigh",
            ceilingPreFix(id: "claude-opus-5-5", declaredTiers: []), "xhigh")

    // claude-opus-5 IS enriched, so its ceiling never depended on the rule —
    // the fix must not change models that were already correct.
    checkEq("enriched claude-opus-5 is unaffected",
            ceiling(id: "claude-opus-5", declaredTiers: ["low", "medium", "high", "max"]), "max")

    // And the rule must not leak onto unrelated Anthropic ids.
    check("claude-sonnet-5 is not caught by the Opus rule",
          declaredMaxLevel(for: "claude-sonnet-5") == nil)
    check("claude-haiku-4-5 is not caught either",
          declaredMaxLevel(for: "claude-haiku-4-5") == nil)

    // The catalog really is missing claude-opus-5-5 — if a future snapshot adds
    // it, the rule becomes belt-and-braces rather than load-bearing, and this
    // line is where that shows up.
    let catalogJSON = (try? String(contentsOf: URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("Resources/models-dev-api.json"), encoding: .utf8)) ?? ""
    if catalogJSON.isEmpty {
        print("  ⏭  models-dev-api.json not readable")
    } else {
        check("models-dev-api.json still lacks claude-opus-5-5",
              !catalogJSON.contains("\"claude-opus-5-5\""))
        check("…while carrying claude-opus-5 (so the comparison is meaningful)",
              catalogJSON.contains("\"claude-opus-5\""))
    }
}

print("\n▶️  5. both client-version floors are cleared")
do {
    // Codex: gpt-6-sol/luna need >= 0.155.0; astra's 0.153.0 must still clear.
    let codexVer: String? = {
        guard let r = openai.range(of: "static let codexClientVersion = \"") else { return nil }
        return String(openai[r.upperBound...].prefix { $0 != "\"" })
    }()
    check("codexClientVersion was found", codexVer != nil)
    if let v = codexVer {
        check("…>= 0.155.0 (gpt-6-sol / gpt-6-luna)", atLeast(v, "0.155.0"))
        check("…>= 0.153.0 (gpt-6-astra still clears)", atLeast(v, "0.153.0"))
    }

    // Anthropic: claude-opus-5-5 needs claude-cli >= 2.1.280; Fable 5.1's
    // 2.1.251 floor must still clear (a gate is a floor, not an exact match).
    let ua: String? = {
        guard let r = anthropicUA.range(of: "\"User-Agent\": \"claude-cli/") else { return nil }
        return String(anthropicUA[r.upperBound...].prefix { $0 != " " })
    }()
    check("the claude-cli User-Agent was found", ua != nil)
    if let v = ua {
        check("…>= 2.1.280 (claude-opus-5-5)", atLeast(v, "2.1.280"))
        check("…>= 2.1.251 (claude-fable-5-1 still clears)", atLeast(v, "2.1.251"))
    }
}

print("\n▶️  6. Opus 5.5 must never be sent thinking.type=disabled")
do {
    // Port the predicate and its parser, then assert on the real id. Pinning
    // the source text alone would not catch a widened predicate.
    func parseClaudeVersion(_ modelId: String) -> (major: Int, minor: Int)? {
        let lower = modelId.lowercased()
        guard let claudeRange = lower.range(of: "claude") else { return nil }
        let pattern = #"[-/]?(\d+)(?:[-.](\d{1,2}))?(?:\b|[^0-9])"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(claudeRange.upperBound..<lower.endIndex, in: lower)
        guard let m = regex.firstMatch(in: lower, range: range), m.numberOfRanges >= 3,
              let majorRange = Range(m.range(at: 1), in: lower),
              let major = Int(lower[majorRange]) else { return nil }
        let minor = Range(m.range(at: 2), in: lower).flatMap { Int(lower[$0]) } ?? 0
        return (major, minor)
    }
    func acceptsDisabled(_ id: String) -> Bool {
        guard let v = parseClaudeVersion(id) else { return false }
        return v.major == 4 && v.minor >= 6
    }

    check("claude-opus-5-5 does NOT accept thinking.type=disabled",
          acceptsDisabled("claude-opus-5-5"), false)
    // …and for the right reason: a nil parse would also answer false.
    let v = parseClaudeVersion("claude-opus-5-5")
    checkEq("…because it parses as major 5", v?.major, 5)
    checkEq("…minor 5", v?.minor, 5)

    // The 4.6+ models that DO accept it must keep accepting it, or the
    // assertion above could be satisfied by disabling the feature outright.
    check("claude-opus-4-8 still accepts it", acceptsDisabled("claude-opus-4-8"))
    check("claude-opus-4-6 still accepts it", acceptsDisabled("claude-opus-4-6"))
    check("claude-opus-5 still does not", acceptsDisabled("claude-opus-5"), false)
    check("claude-fable-5-1 still does not", acceptsDisabled("claude-fable-5-1"), false)
    check("claude-sonnet-4-5 still does not", acceptsDisabled("claude-sonnet-4-5"), false)

    // The shipping predicate must still be the one ported above.
    check("the shipping gate is unchanged",
          anthropic.contains("return v.major == 4 && v.minor >= 6"))
}

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)") }
exit(failures == 0 ? 0 : 1)
