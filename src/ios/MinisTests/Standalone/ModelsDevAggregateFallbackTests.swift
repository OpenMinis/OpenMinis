// Tests for [T-modelsdev-aggregate-fallback] — stage 4 of model metadata
// resolution: for providers with no models.dev channel identity, fold every
// catalog record sharing an id prefix into one optimistic answer (max for
// numbers, OR for capabilities).
//
// Standalone (`swift ModelsDevAggregateFallbackTests.swift`) for the same
// reason as the neighbouring files: the MinisTests target has a pre-existing
// compile break and the shipping types are `private` to ModelsDevAPI. The
// aggregation and gating rules are reproduced here; section [6] re-reads the
// shipping source so the copies cannot drift.

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Reproduced model shapes

struct Limit { var context: Int?; var output: Int? }
struct Modalities { var input: [String]?; var output: [String]? }
struct DevModel {
    var id: String
    var modalities: Modalities?
    var limit: Limit?
    var reasoning: Bool?
    var effortValues: [String]?
}

let minPrefixSegments = 2

/// Mirrors ModelsDevAPI.normalizedModelKey's relevant behaviour (fold . and _ to -).
func normalizedKey(_ id: String) -> String {
    id.lowercased().replacingOccurrences(of: ".", with: "-").replacingOccurrences(of: "_", with: "-")
}

/// Mirrors ModelsDevAPI.aggregate.
func aggregate(_ records: [DevModel], id: String) -> DevModel {
    var maxContext: Int?, maxOutput: Int?
    var anyReasoning = false, sawReasoning = false
    var unionIn: Set<String> = [], unionOut: Set<String> = []
    var effortUnion: [String] = [], sawEffort = false
    for r in records {
        if let c = r.limit?.context { maxContext = max(maxContext ?? c, c) }
        if let o = r.limit?.output { maxOutput = max(maxOutput ?? o, o) }
        if let x = r.reasoning { sawReasoning = true; anyReasoning = anyReasoning || x }
        unionIn.formUnion(r.modalities?.input ?? [])
        unionOut.formUnion(r.modalities?.output ?? [])
        if let e = r.effortValues { sawEffort = true; for v in e where !effortUnion.contains(v) { effortUnion.append(v) } }
    }
    return DevModel(
        id: id,
        modalities: (unionIn.isEmpty && unionOut.isEmpty) ? nil
            : Modalities(input: unionIn.sorted(), output: unionOut.sorted()),
        limit: (maxContext == nil && maxOutput == nil) ? nil : Limit(context: maxContext, output: maxOutput),
        reasoning: sawReasoning ? anyReasoning : nil,
        effortValues: sawEffort ? effortUnion : nil)
}

/// Mirrors buildAggregateIndex.
func buildAggregateIndex(_ records: [DevModel]) -> [String: DevModel] {
    var grouped: [String: [DevModel]] = [:]
    for r in records {
        var segs = normalizedKey(r.id).split(separator: "-").map(String.init)
        while segs.count >= minPrefixSegments {
            grouped[segs.joined(separator: "-"), default: []].append(r)
            segs.removeLast()
        }
    }
    var index: [String: DevModel] = [:]
    for (p, rs) in grouped { index[p] = aggregate(rs, id: p) }
    return index
}

/// Mirrors providerKeyMap + isUnrecognizedProvider.
let providerKeyMap: [String: [String]] = [
    "Anthropic": ["anthropic"], "Google": ["google", "google-vertex"],
    "OpenAI": ["openai"], "OpenRouter": ["openrouter"], "Antigravity": [],
]
func isUnrecognizedProvider(_ p: String) -> Bool { (providerKeyMap[p] ?? []).isEmpty }

/// Mirrors the stage-4 lookup in resolveDevModel.
func stage4(_ modelId: String, provider: String, index: [String: DevModel]) -> DevModel? {
    guard isUnrecognizedProvider(provider) else { return nil }
    var segs = normalizedKey(modelId).split(separator: "-").map(String.init)
    while segs.count >= minPrefixSegments {
        if let hit = index[segs.joined(separator: "-")] { return hit }
        segs.removeLast()
    }
    return nil
}

// A small catalog: one family, records disagreeing on every field.
let catalog: [DevModel] = [
    DevModel(id: "glm-5.3-flash", modalities: Modalities(input: ["text"], output: ["text"]),
             limit: Limit(context: 128_000, output: 8_192), reasoning: false, effortValues: nil),
    DevModel(id: "glm-5.3-flash-air", modalities: Modalities(input: ["text", "image"], output: ["text"]),
             limit: Limit(context: 1_000_000, output: 4_096), reasoning: true, effortValues: ["low", "high"]),
    DevModel(id: "glm-5.3-pro", modalities: Modalities(input: ["text"], output: ["text", "image"]),
             limit: Limit(context: 200_000, output: 32_768), reasoning: nil, effortValues: ["medium"]),
    DevModel(id: "gpt-9-turbo", modalities: Modalities(input: ["text"], output: ["text"]),
             limit: Limit(context: 64_000, output: 1_024), reasoning: false, effortValues: nil),
]
let index = buildAggregateIndex(catalog)

print("\n[1] Numeric fields aggregate to the MAXIMUM")
let flash = index["glm-5-3-flash"]!
checkEq("context = max(128k, 1M)", flash.limit?.context, 1_000_000)
checkEq("output = max(8192, 4096)", flash.limit?.output, 8_192)
// A wider prefix folds in the pro record too.
let fam = index["glm-5-3"]!
checkEq("family context = max over all three", fam.limit?.context, 1_000_000)
checkEq("family output = max over all three", fam.limit?.output, 32_768)

print("\n[2] Capability fields aggregate by logical OR")
checkEq("reasoning = false OR true", flash.reasoning, true)
checkEq("image input unioned in", flash.modalities?.input, ["image", "text"])
checkEq("family output unions image", fam.modalities?.output, ["image", "text"])
checkEq("effort tiers unioned", Set(fam.effortValues ?? []), Set(["low", "high", "medium"]))
// A field no record declares stays nil rather than being invented.
let gpt = index["gpt-9"]!
checkEq("reasoning stays false when every record says false", gpt.reasoning, false)
checkEq("no effort tiers fabricated", gpt.effortValues == nil, true)

print("\n[3] Unknown third-party provider DOES trigger the fallback")
// The reported shape: a relay renames a model with a house suffix.
let relayed = stage4("glm-5.3-flash-cpa", provider: "MyRelay", index: index)
check("relay model resolves via aggregate", relayed != nil)
checkEq("…and gets the aggregated context", relayed?.limit?.context, 1_000_000)
checkEq("…and the OR'd reasoning flag", relayed?.reasoning, true)
check("Antigravity (mapped to an empty key list) also counts as unrecognized",
      stage4("glm-5.3-flash-cpa", provider: "Antigravity", index: index) != nil)

print("\n[4] Known first-party providers do NOT trigger it")
for p in ["OpenAI", "Anthropic", "Google", "OpenRouter"] {
    check("\(p) is not aggregated", stage4("glm-5.3-flash-cpa", provider: p, index: index) == nil)
}
// The guard is about the PROVIDER, not the id: the same id is aggregated for a
// relay and refused for OpenAI.
check("same id: aggregated for relay, refused for OpenAI",
      stage4("glm-5.3-flash-cpa", provider: "Relay", index: index) != nil
      && stage4("glm-5.3-flash-cpa", provider: "OpenAI", index: index) == nil)

print("\n[5] Prefix floor and rebuild")
// A one-segment id can never match: two segments name a family at minimum.
check("single-segment id is refused", stage4("glm", provider: "Relay", index: index) == nil)
check("an id sharing nothing resolves to nil",
      stage4("totally-unrelated-xyz", provider: "Relay", index: index) == nil)
// Rebuild: a refreshed snapshot must produce a different index.
var refreshed = catalog
refreshed.append(DevModel(id: "glm-5.3-flash-max",
                          modalities: Modalities(input: ["text", "audio"], output: ["text"]),
                          limit: Limit(context: 2_000_000, output: 65_536),
                          reasoning: true, effortValues: ["xhigh"]))
let rebuilt = buildAggregateIndex(refreshed)
checkEq("rebuilt index picks up the larger context", rebuilt["glm-5-3-flash"]?.limit?.context, 2_000_000)
checkEq("rebuilt index picks up the larger output", rebuilt["glm-5-3-flash"]?.limit?.output, 65_536)
check("rebuilt index unions the new modality",
      rebuilt["glm-5-3-flash"]?.modalities?.input?.contains("audio") == true)
check("old index is unchanged (rebuild is not in-place mutation)",
      index["glm-5-3-flash"]?.limit?.context == 1_000_000)

print("\n[6] Shipping source matches these assumptions")
func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let src = source("Providers/ModelsDevAPI.swift")
if src.isEmpty { print("  ⏭  source not readable from this sandbox") } else {
    check("gate reuses providerKeyMap, no new enum",
          src.contains("(providerKeyMap[providerName] ?? []).isEmpty"))
    check("stage 4 is gated on the unrecognized check",
          src.contains("guard isUnrecognizedProvider(model.provider),"))
    check("index is precomputed + memoized like stage 2",
          src.contains("private static func aggregateIndex(for registry:"))
    check("invalidated on snapshot refresh via cacheTimestamp",
          src.contains("aggregateIndexBuiltFrom == cacheTimestamp"))
    check("numeric fold is max", src.contains("maxContext = max(maxContext ?? c, c)"))
    check("capability fold is OR", src.contains("anyReasoning = anyReasoning || reasoning"))
    check("modality fold is a union", src.contains("unionInput.formUnion"))
    check("aggregate result is never authoritative",
          src.contains("return DevModelMatch(model: agg, authoritative: false)"))
    check("stage 4 runs only after prefixMatch misses",
          src.contains("if let hit = prefixMatch(wanted, in: index) { return hit }"))
    check("build honours the same segment floor",
          src.contains("while segments.count >= minPrefixSegments {"))
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
