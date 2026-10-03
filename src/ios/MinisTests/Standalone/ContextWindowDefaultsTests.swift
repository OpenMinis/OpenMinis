// Tests for [T-ios-grok-context-underestimate] + [T-anthropic-context-window]
// (M10) — the context-window fallback heuristic, and the precedence chain around
// it: user override > models.dev / provider API > id heuristic.
//
// Why a wrong default is expensive rather than cosmetic: ContextPolicy derives
// compactThreshold = window - 20K, so an underestimate makes the agent loop
// auto-compact constantly. Field report 2026-08-13 — `grok-4.6`, not yet in the
// models.dev catalog, fell through to the generic 128K default, giving a 108K
// threshold on a model with a 256K-2M window, and compacted 6 times in 47
// minutes (33b028477, iOS parity). The Anthropic half is the mirror image: the
// old "everything without `-1m` is 200K" rule capped Sonnet 4.6 / Sonnet 5 /
// Opus 4.x / Fable 5 at a fifth of their real window (507105f09).
//
// The heuristic is a long if-ladder whose ORDER is load-bearing (an id can match
// several branches), so this file walks it branch by branch and includes the
// cross-family ids that could be captured by the wrong one. It is ported
// verbatim from LLMModel.contextWindowTokens (src/ios/Providers/LLMTypes.swift
// ~685-715); section [7] re-reads the shipping source so the copy cannot drift.
//
// Standalone (`swift ContextWindowDefaultsTests.swift`) like its neighbours.

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Ported verbatim from LLMModel.contextWindowTokens

struct Model {
    var id: String
    /// From models.dev / the provider API.
    var contextWindow: Int?

    var contextWindowTokens: Int {
        if let ctx = contextWindow, ctx > 0 { return ctx }
        let lid = id.lowercased()
        if lid.contains("claude") {
            if lid.contains("haiku") { return 200_000 }
            if lid.contains("claude-2") || lid.contains("claude-3") { return 200_000 }
            return 1_000_000
        }
        if lid.contains("gemini") {
            if lid.contains("1.0") { return 32_000 }
            return 1_000_000
        }
        if lid.contains("gpt-3.5") { return 16_000 }
        if lid.contains("gpt-4o") || lid.contains("gpt-4-turbo") { return 128_000 }
        if lid.contains("gpt-5") { return 400_000 }
        if lid.contains("gpt-4") { return 8_000 }
        if lid.contains("o3") || lid.contains("o4") { return 200_000 }
        if lid.contains("codex") { return 200_000 }
        if lid.contains("deepseek") { return 128_000 }
        if lid.contains("grok") {
            if lid.contains("grok-2") || lid.contains("grok-3") { return 131_072 }
            return 256_000
        }
        return 128_000
    }
}

/// The override layer (ModelOverrides.contextWindow applied by ModelEntry.model).
struct Entry {
    var baseModel: Model
    var overrideContextWindow: Int? = nil
    var model: Model {
        var m = baseModel
        if let o = overrideContextWindow { m.contextWindow = o }
        return m
    }
    var window: Int { model.contextWindowTokens }
}

/// What ContextPolicy does with the answer — why an underestimate is expensive.
func compactThreshold(_ window: Int) -> Int { max(0, window - 20_000) }

func w(_ id: String) -> Int { Model(id: id, contextWindow: nil).contextWindowTokens }

// ---------------------------------------------------------------------------

print("\n[1] Unknown Grok must NOT get the generic 128K default")

// The reported id, and every modern Grok line. 256K is the conservative floor
// for Grok 4+; the fast / 4.20 lines advertise 2M, so the floor is deliberately
// below the truth rather than above it.
for id in ["grok-4.6", "grok-4.5", "grok-4.3", "grok-4", "grok-4-fast",
           "grok-4.20-0309-reasoning", "grok-4.20-multi-agent-0309",
           "grok-code-fast-1", "grok-composer-2.5-fast", "grok-build-0.1",
           "grok-5-whatever-ships-next"] {
    checkEq("\(id) → 256K, not 128K", w(id), 256_000)
}
check("…and none of them lands on the generic default",
      ["grok-4.6", "grok-4", "grok-4-fast"].allSatisfy { w($0) != 128_000 })
// Grok 3 (and 2) really are the 131K generation, so they must NOT be inflated.
for id in ["grok-3", "grok-3-mini", "grok-3-mini-fast", "grok-2", "grok-2-vision-1212"] {
    checkEq("\(id) → 131072 (the only 131K generation)", w(id), 131_072)
}
// The consequence the fix exists for.
checkEq("the old 128K default gave a 108K compact threshold",
        compactThreshold(128_000), 108_000)
checkEq("…where 256K gives 236K", compactThreshold(256_000), 236_000)
check("a grok-4.6 conversation is no longer compacted at 108K",
      compactThreshold(w("grok-4.6")) > 108_000)

print("\n[2] Modern Claude is 1M, not 200K")

for id in ["claude-opus-5", "claude-sonnet-5", "claude-sonnet-4-6", "claude-opus-4-5",
           "claude-fable-5", "claude-mythos-5", "claude-sonnet-4", "claude-opus-4"] {
    checkEq("\(id) → 1M", w(id), 1_000_000)
}
// Haiku and the legacy generations stay at 200K — the fix must not inflate them.
for id in ["claude-haiku-4-5", "claude-3-5-haiku-20241022", "claude-haiku-5"] {
    checkEq("\(id) → 200K (Haiku)", w(id), 200_000)
}
for id in ["claude-3-5-sonnet-20241022", "claude-3-opus-20240229", "claude-3-haiku", "claude-2.1"] {
    checkEq("\(id) → 200K (legacy generation)", w(id), 200_000)
}
// Ordering inside the Claude branch: `haiku` is tested BEFORE the generation
// check, so claude-3-5-haiku matches once, on the Haiku rule.
checkEq("a legacy Haiku resolves to 200K either way", w("claude-3-5-haiku"), 200_000)
// A dated / namespaced / Bedrock-shaped id must resolve the same, since the
// heuristic is a plain lowercased substring scan.
for id in ["anthropic/claude-opus-5", "us.anthropic.claude-opus-4-5-20251101-v1:0",
           "CLAUDE-SONNET-5", "claude-sonnet-5@default", "claude-opus-5:free"] {
    checkEq("\(id) still resolves to 1M", w(id), 1_000_000)
}

print("\n[3] The rest of the ladder, in its declared order")

checkEq("gemini-3-pro → 1M", w("gemini-3-pro"), 1_000_000)
checkEq("gemini-2.5-flash → 1M", w("gemini-2.5-flash"), 1_000_000)
checkEq("gemini-1.0-pro → 32K", w("gemini-1.0-pro"), 32_000)
checkEq("gpt-3.5-turbo → 16K", w("gpt-3.5-turbo"), 16_000)
checkEq("gpt-4o → 128K", w("gpt-4o"), 128_000)
checkEq("gpt-4-turbo → 128K", w("gpt-4-turbo"), 128_000)
checkEq("gpt-5 → 400K", w("gpt-5"), 400_000)
checkEq("gpt-6-astra → 128K (no gpt-6 rule; the generic default)", w("gpt-6-astra"), 128_000)
checkEq("gpt-4 → 8K", w("gpt-4"), 8_000)
checkEq("o3 → 200K", w("o3"), 200_000)
checkEq("o4-mini → 200K", w("o4-mini"), 200_000)
checkEq("codex-auto-review → 200K", w("codex-auto-review"), 200_000)
checkEq("deepseek-v4 → 128K", w("deepseek-v4"), 128_000)
checkEq("an id matching nothing → 128K, the modern-long-context default",
        w("some-relay/unknown-model-v1"), 128_000)
checkEq("an empty id → the default", w(""), 128_000)

print("\n[4] Order hazards — an id that could match two branches")

// `gpt-4o` is checked before the bare `gpt-4` rule, or every 4o model would be
// capped at 8K.
check("gpt-4o is not captured by the bare gpt-4 rule", w("gpt-4o") != 8_000)
checkEq("gpt-4o-mini → 128K", w("gpt-4o-mini"), 128_000)
// `gpt-5` is checked before `gpt-4`, but they cannot collide; `gpt-4.1` can only
// hit the gpt-4 rule.
checkEq("gpt-4.1 → 8K (only the gpt-4 rule matches)", w("gpt-4.1"), 8_000)
// `gpt-5-codex` hits the gpt-5 rule first, NOT the later codex rule.
checkEq("gpt-5-codex → 400K (gpt-5 wins over codex)", w("gpt-5-codex"), 400_000)
checkEq("gpt-5.3-codex-spark → 400K", w("gpt-5.3-codex-spark"), 400_000)
// A Claude id that also contains "gemini"/"gpt" (a relay's compound id) resolves
// on the FIRST branch, Claude.
checkEq("a relay id naming both claude and gpt resolves as Claude",
        w("relay/claude-opus-5-via-gpt-proxy"), 1_000_000)
// A deepseek id that also says grok resolves as deepseek (it is earlier).
checkEq("deepseek is checked before grok", w("deepseek-grok-mix"), 128_000)
// And `grok-4-fast` must not be caught by the grok-3 sub-rule.
check("grok-4-fast is not captured by the grok-3 rule", w("grok-4-fast") != 131_072)

print("\n[5] Precedence: models.dev / the provider API beats the heuristic")

// The catalog value wins whenever there is one — that is what makes the
// heuristic a FALLBACK and not a cap.
checkEq("a catalogued grok wins over the 256K floor",
        Model(id: "grok-4-fast", contextWindow: 2_000_000).contextWindowTokens, 2_000_000)
checkEq("a catalogued value LOWER than the heuristic also wins",
        Model(id: "grok-4.6", contextWindow: 131_072).contextWindowTokens, 131_072)
checkEq("a catalogued Claude value wins over 1M",
        Model(id: "claude-opus-5", contextWindow: 200_000).contextWindowTokens, 200_000)
// Zero and negative are treated as "no value", not as a real window: a provider
// reporting 0 must not collapse the slider.
checkEq("contextWindow = 0 falls back to the heuristic",
        Model(id: "grok-4.6", contextWindow: 0).contextWindowTokens, 256_000)
checkEq("a negative contextWindow falls back too",
        Model(id: "claude-opus-5", contextWindow: -1).contextWindowTokens, 1_000_000)

print("\n[6] Precedence: a user override beats everything")

let catalogued = Model(id: "grok-4.6", contextWindow: 256_000)
checkEq("no override → the catalog value", Entry(baseModel: catalogued).window, 256_000)
checkEq("an override wins over the catalog",
        Entry(baseModel: catalogued, overrideContextWindow: 1_000_000).window, 1_000_000)
checkEq("…including a SMALLER one (the user may be working around a relay cap)",
        Entry(baseModel: catalogued, overrideContextWindow: 64_000).window, 64_000)
checkEq("an override wins over the heuristic too",
        Entry(baseModel: Model(id: "claude-opus-5", contextWindow: nil),
              overrideContextWindow: 300_000).window, 300_000)
// An override of 0 is not a window — it falls back through the same guard, so a
// stray 0 cannot collapse the agent loop's threshold to nothing.
checkEq("an override of 0 falls back to the heuristic",
        Entry(baseModel: Model(id: "claude-opus-5", contextWindow: nil),
              overrideContextWindow: 0).window, 1_000_000)
// The full chain, in one assertion per layer.
let chain = Model(id: "grok-4.6", contextWindow: nil)
checkEq("layer 3 (heuristic)", Entry(baseModel: chain).window, 256_000)
var withCatalog = chain; withCatalog.contextWindow = 2_000_000
checkEq("layer 2 (catalog) beats layer 3", Entry(baseModel: withCatalog).window, 2_000_000)
checkEq("layer 1 (user) beats layer 2",
        Entry(baseModel: withCatalog, overrideContextWindow: 500_000).window, 500_000)

print("\n[7] Source-grep drift guard (LLMTypes.contextWindowTokens)")

let rel = "src/ios/Providers/LLMTypes.swift"
var root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
while !FileManager.default.fileExists(atPath: root.appendingPathComponent(rel).path),
      root.pathComponents.count > 1 { root.deleteLastPathComponent() }
let src = (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
check("source read", !src.isEmpty)

check("the catalog value is consulted first, and only when positive",
      src.contains("if let ctx = contextWindow, ctx > 0 { return ctx }"))
check("the scan is on the LOWERCASED id", src.contains("let lid = id.lowercased()"))
check("modern Claude returns 1M", src.contains("if lid.contains(\"claude\") {")
        && src.contains("return 1_000_000"))
check("Haiku is checked before the generation rule", {
    guard let r = src.range(of: "if lid.contains(\"claude\") {") else { return false }
    let body = String(src[r.upperBound...]).prefix(500)
    guard let h = body.range(of: "lid.contains(\"haiku\")"),
          let g = body.range(of: "lid.contains(\"claude-2\")") else { return false }
    return h.lowerBound < g.lowerBound
}())
check("the Grok branch exists and floors unknown Grok at 256K",
      src.contains("if lid.contains(\"grok\") {")
        && src.contains("if lid.contains(\"grok-2\") || lid.contains(\"grok-3\") { return 131_072 }")
        && src.contains("return 256_000"))
check("gpt-4o is checked before the bare gpt-4 rule", {
    guard let a = src.range(of: "if lid.contains(\"gpt-4o\") || lid.contains(\"gpt-4-turbo\")"),
          let b = src.range(of: "if lid.contains(\"gpt-4\") { return 8_000 }") else { return false }
    return a.lowerBound < b.lowerBound
}())
check("gpt-5 is checked before codex", {
    guard let a = src.range(of: "if lid.contains(\"gpt-5\") { return 400_000 }"),
          let b = src.range(of: "if lid.contains(\"codex\") { return 200_000 }") else { return false }
    return a.lowerBound < b.lowerBound
}())
check("deepseek is checked before grok", {
    guard let a = src.range(of: "if lid.contains(\"deepseek\") { return 128_000 }"),
          let b = src.range(of: "if lid.contains(\"grok\") {") else { return false }
    return a.lowerBound < b.lowerBound
}())
check("the terminal default is 128_000, not 64K", {
    guard let r = src.range(of: "var contextWindowTokens: Int {") else { return false }
    let body = String(src[r.upperBound...]).prefix(3000)
    guard let marker = body.range(of: "// Default: assume a modern long-context model rather than 64K")
    else { return false }
    // The last statement of the function is the generic default.
    return body[marker.upperBound...].contains("return 128_000")
}())
check("the Grok rationale (models.dev still overrides) is still documented",
      src.contains("[T-ios-grok-context-underestimate]"))
check("the Anthropic rationale is still documented",
      src.contains("The old \"everything non-`-1m` is 200K\" default wrongly capped"))
// The override layer.
let entrySrc = (try? String(contentsOf: root.appendingPathComponent("src/ios/Providers/ModelEntry.swift"),
                            encoding: .utf8)) ?? ""
check("ModelEntry read", !entrySrc.isEmpty)
check("ModelEntry.model applies the contextWindow override over the base",
      entrySrc.contains("contextWindow: overrides.contextWindow ?? baseModel.contextWindow"))

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILED") }
exit(failures == 0 ? 0 : 1)
