// Tests for [T-claude-version-parse] — round-2 item M16, iOS half.
//
// Pins 500abe41b (single-segment versions: `claude-fable-5` → (5,0)) and
// 69be65763 (no `thinking.type=disabled` for Claude 5+). One regex on the
// lowercased id drives three wire decisions:
//   * modelRejectsTemperature      — ≥ 4.6 drops `temperature`
//   * modelUsesAdaptiveThinking    — ≥ 4.6 sends `output_config.effort`
//   * modelAcceptsExplicitThinkingDisabled — 4.6 ≤ v < 5 ONLY
// so a parse miss silently sends the wrong body to a whole generation.
//
// Port: AnthropicProvider.parseClaudeVersion + the three predicates
// (Providers/Anthropic/AnthropicProvider.swift ~L73-140), verbatim; the
// PRE-500abe41b regex is kept alongside for contrast.
//
// Standalone (`swift ClaudeVersionParseTests.swift`) like its neighbours.
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

// MARK: - Port

struct V: Equatable { let major: Int; let minor: Int }

func parse(_ modelId: String, pattern: String, anchorAfterClaude: Bool = true) -> V? {
    let lower = modelId.lowercased()
    guard let claudeRange = lower.range(of: "claude") else { return nil }
    guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
    // The shipped parser anchors the search AFTER the family name, so a
    // digit-bearing namespace cannot supply the version. The pre-fix forms
    // searched the whole id.
    let searchStart = anchorAfterClaude ? claudeRange.upperBound : lower.startIndex
    let range = NSRange(searchStart..<lower.endIndex, in: lower)
    guard let match = regex.firstMatch(in: lower, range: range), match.numberOfRanges >= 3,
          let majorRange = Range(match.range(at: 1), in: lower), let major = Int(lower[majorRange]) else { return nil }
    var minor = 0
    if let minorRange = Range(match.range(at: 2), in: lower), let parsed = Int(lower[minorRange]) { minor = parsed }
    else if pattern == preFixPattern { return nil } // the old regex required the pair
    return V(major: major, minor: minor)
}
let shippedPattern = #"[-/]?(\d+)(?:[-.](\d{1,2}))?(?:\b|[^0-9])"#
let preFixPattern  = #"[-/]?(\d+)[-.](\d+)(?:\b|[^0-9])"#
/// The uncapped, unanchored form that shipped before this fix — kept so the two
/// closed gaps can be shown as a before/after rather than asserted blind.
let greedyUnanchoredPattern = #"[-/]?(\d+)(?:[-.](\d+))?(?:\b|[^0-9])"#
func parseClaudeVersion(_ id: String) -> V? { parse(id, pattern: shippedPattern) }
func preFixParse(_ id: String) -> V? { parse(id, pattern: preFixPattern, anchorAfterClaude: false) }
func greedyUnanchoredParse(_ id: String) -> V? { parse(id, pattern: greedyUnanchoredPattern, anchorAfterClaude: false) }

func modelRejectsTemperature(_ id: String) -> Bool { guard let v = parseClaudeVersion(id) else { return false }; return v.major > 4 || (v.major == 4 && v.minor >= 6) }
func modelUsesAdaptiveThinking(_ id: String) -> Bool { guard let v = parseClaudeVersion(id) else { return false }; return v.major > 4 || (v.major == 4 && v.minor >= 6) }
func modelAcceptsExplicitThinkingDisabled(_ id: String) -> Bool { guard let v = parseClaudeVersion(id) else { return false }; return v.major == 4 && v.minor >= 6 }
/// AnthropicProvider.effectiveTemperature
func effectiveTemperature(_ id: String, _ t: Double?) -> Double? { modelRejectsTemperature(id) ? nil : t }

print("▶️  1. the parser, id shape by id shape")
do {
    checkEq("claude-fable-5 → (5,0)", parseClaudeVersion("claude-fable-5"), V(major: 5, minor: 0))
    checkEq("claude-opus-5 → (5,0)", parseClaudeVersion("claude-opus-5"), V(major: 5, minor: 0))
    checkEq("claude-fable-5-1 → (5,1)", parseClaudeVersion("claude-fable-5-1"), V(major: 5, minor: 1))
    checkEq("claude-3-5-sonnet → (3,5), never (3,0)", parseClaudeVersion("claude-3-5-sonnet"), V(major: 3, minor: 5))
    checkEq("claude-3-5-sonnet-20241022 → (3,5)", parseClaudeVersion("claude-3-5-sonnet-20241022"), V(major: 3, minor: 5))
    checkEq("claude-sonnet-4-6 → (4,6)", parseClaudeVersion("claude-sonnet-4-6"), V(major: 4, minor: 6))
    checkEq("claude-sonnet-4-6-thinking → (4,6)", parseClaudeVersion("claude-sonnet-4-6-thinking"), V(major: 4, minor: 6))
    checkEq("anthropic/claude-opus-4.8 (dotted, vendor prefix) → (4,8)", parseClaudeVersion("anthropic/claude-opus-4.8"), V(major: 4, minor: 8))
    checkEq("claude-opus-4-1-20250805 → (4,1)", parseClaudeVersion("claude-opus-4-1-20250805"), V(major: 4, minor: 1))
    checkEq("us.anthropic.claude-opus-4-5-20251101-v1:0 (Bedrock) → (4,5)", parseClaudeVersion("us.anthropic.claude-opus-4-5-20251101-v1:0"), V(major: 4, minor: 5))
    checkEq("claude-3-opus → (3,0)", parseClaudeVersion("claude-3-opus"), V(major: 3, minor: 0))
    checkEq("Claude-Haiku-4-5 (case) → (4,5)", parseClaudeVersion("Claude-Haiku-4-5"), V(major: 4, minor: 5))
    checkEq("non-Claude id (gpt-5) → nil", parseClaudeVersion("gpt-5"), nil)
    checkEq("non-Claude id (MiniMax-M3) → nil", parseClaudeVersion("MiniMax-M3"), nil)
    checkEq("claude with no version segment → nil", parseClaudeVersion("claude-instant"), nil)
    // What 500abe41b changed.
    checkEq("PRE-FIX: claude-fable-5 → nil (the pair was mandatory)", preFixParse("claude-fable-5"), nil)
    checkEq("PRE-FIX: claude-opus-5 → nil", preFixParse("claude-opus-5"), nil)
    checkEq("PRE-FIX: the pair form was already right", preFixParse("claude-sonnet-4-6"), V(major: 4, minor: 6))
    // GAP 1 CLOSED — the minor group is now capped at 1-2 digits. It used to be
    // uncapped and greedy, so a bare-major id followed by a snapshot stamp
    // swallowed the stamp as the minor: `claude-sonnet-4-20250514` (Claude
    // Sonnet 4.0) parsed as (4, 20250514) and was therefore treated as ≥ 4.6 —
    // temperature dropped, adaptive-thinking effort sent,
    // `thinking.type=disabled` sent, none of which a 4.0 model accepts. These
    // are SHIPPING catalog ids, so this was a live misconfiguration, not a
    // latent one.
    checkEq("claude-sonnet-4-20250514 → (4,0), not (4,20250514)", parseClaudeVersion("claude-sonnet-4-20250514"), V(major: 4, minor: 0))
    checkEq("claude-opus-4-20250514 → (4,0)", parseClaudeVersion("claude-opus-4-20250514"), V(major: 4, minor: 0))
    checkEq("anthropic/claude-sonnet-4-20250514 → (4,0)", parseClaudeVersion("anthropic/claude-sonnet-4-20250514"), V(major: 4, minor: 0))
    check("claude-opus-4-20250514 keeps its temperature", !modelRejectsTemperature("claude-opus-4-20250514"))
    check("…and uses legacy budget thinking, not adaptive", !modelUsesAdaptiveThinking("claude-opus-4-20250514"))
    check("…and is not sent the disabled literal", !modelAcceptsExplicitThinkingDisabled("claude-opus-4-20250514"))
    checkEq("a single-segment 5-series id sheds its stamp too", parseClaudeVersion("claude-opus-5-20260401"), V(major: 5, minor: 0))
    // A version PAIR was never affected — the pair consumes the separator first.
    checkEq("a version PAIR plus a stamp is unchanged", parseClaudeVersion("claude-opus-4-8-20260115"), V(major: 4, minor: 8))
    // Before/after, so the fix is visible rather than merely asserted.
    checkEq("PRE-FIX: the uncapped minor swallowed the stamp", greedyUnanchoredParse("claude-sonnet-4-20250514"), V(major: 4, minor: 20250514))

    // GAP 2 CLOSED — the search now starts AFTER the word "claude", so a
    // namespace carrying digits before the family name can no longer supply the
    // version. `v2-gateway/claude-opus-5` parsed as (2,0) and
    // `inst-53/claude-haiku-4-5` as (53,0); both would then take `temperature`
    // plus legacy budget thinking — the exact 400 this commit family fixes.
    checkEq("v2-gateway/claude-opus-5 → (5,0), not (2,0)", parseClaudeVersion("v2-gateway/claude-opus-5"), V(major: 5, minor: 0))
    checkEq("inst-53/claude-haiku-4-5 → (4,5), not (53,0)", parseClaudeVersion("inst-53/claude-haiku-4-5"), V(major: 4, minor: 5))
    checkEq("inst-7/anthropic/claude-sonnet-5 → (5,0)", parseClaudeVersion("inst-7/anthropic/claude-sonnet-5"), V(major: 5, minor: 0))
    check("a namespaced 5-series id now rejects temperature", modelRejectsTemperature("v2-gateway/claude-opus-5"))
    checkEq("PRE-FIX: the namespace digit won", greedyUnanchoredParse("v2-gateway/claude-opus-5"), V(major: 2, minor: 0))
}

print("▶️  2. the three predicates it drives")
do {
    for id in ["claude-fable-5", "claude-opus-5", "claude-fable-5-1", "claude-sonnet-4-6", "claude-opus-4.8", "anthropic/claude-opus-4-7"] {
        check("\(id): rejects temperature + adaptive thinking", modelRejectsTemperature(id) && modelUsesAdaptiveThinking(id))
    }
    for id in ["claude-sonnet-4-5", "claude-3-5-sonnet", "claude-opus-4-1", "claude-3-opus"] {
        check("\(id): keeps temperature, budget thinking", !modelRejectsTemperature(id) && !modelUsesAdaptiveThinking(id))
    }
    check("non-Claude ids never trip any predicate", ["gpt-5", "MiniMax-M3", "deepseek-v4"].allSatisfy { !modelRejectsTemperature($0) && !modelUsesAdaptiveThinking($0) && !modelAcceptsExplicitThinkingDisabled($0) })
    // The disabled literal is a 4.6 ≤ v < 5 window, NOT "adaptive".
    check("4.6–4.x accept thinking.type=disabled", ["claude-sonnet-4-6", "claude-opus-4-7", "claude-opus-4.8", "claude-opus-4-99"].allSatisfy(modelAcceptsExplicitThinkingDisabled))
    check("Claude 5+ does NOT (69be65763)", ["claude-fable-5", "claude-opus-5", "claude-fable-5-1", "claude-sonnet-6"].allSatisfy { !modelAcceptsExplicitThinkingDisabled($0) })
    check("≤ 4.5 does not either (off is already its default)", ["claude-sonnet-4-5", "claude-3-5-sonnet"].allSatisfy { !modelAcceptsExplicitThinkingDisabled($0) })
    check("adaptive ⊋ acceptsDisabled: 5.x is adaptive but not disable-able", modelUsesAdaptiveThinking("claude-fable-5") && !modelAcceptsExplicitThinkingDisabled("claude-fable-5"))
    checkEq("effectiveTemperature: 4.6+ → nil", effectiveTemperature("claude-sonnet-4-6", 0.7), nil)
    checkEq("effectiveTemperature: 4.5 → passthrough", effectiveTemperature("claude-sonnet-4-5", 0.7), 0.7)
    checkEq("effectiveTemperature: Claude 5 → nil (the 500abe41b symptom)", effectiveTemperature("claude-opus-5", 0.7), nil)
}

print("▶️  3. shipping sources still carry the pinned lines")
do {
    let ap = source("Providers/Anthropic/AnthropicProvider.swift")
    let http = source("Providers/Anthropic/OAuthHTTPClient.swift")
    let res = source("Providers/Thinking/ThinkingRuleResolver.swift")
    if ap.isEmpty || http.isEmpty || res.isEmpty { print("  ⏭  sources not readable") } else {
        check("shipped regex has the optional minor group, capped at 1-2 digits", ap.contains("let pattern = #\"[-/]?(\\d+)(?:[-.](\\d{1,2}))?(?:\\b|[^0-9])\"#"))
        check("the search is anchored AFTER the family name", ap.contains("guard let claudeRange = lower.range(of: \"claude\") else { return nil }") && ap.contains("NSRange(claudeRange.upperBound..<lower.endIndex, in: lower)"))
        check("minor defaults to 0 when the group is absent", ap.contains("minor = 0"))
        check("rejectsTemperature = ≥ 4.6", ap.contains("static func modelRejectsTemperature(_ modelId: String) -> Bool {\n        guard let v = parseClaudeVersion(modelId) else { return false }\n        return v.major > 4 || (v.major == 4 && v.minor >= 6)"))
        check("acceptsExplicitThinkingDisabled = 4.6 ≤ v < 5", ap.contains("return v.major == 4 && v.minor >= 6"))
        check("effectiveTemperature consults the predicate", ap.contains("if Self.modelRejectsTemperature(model.id) { return nil }"))
        check("the wire patcher strips temperature for ≥ 4.6", http.contains("if AnthropicProvider.modelRejectsTemperature(modelId) {\n            json.removeValue(forKey: \"temperature\")"))
        check("resolver's off shape asks acceptsExplicitThinkingDisabled, not adaptive", res.contains("return AnthropicProvider.modelAcceptsExplicitThinkingDisabled(modelId)\n            ? [\"disabled\": true]"))
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
