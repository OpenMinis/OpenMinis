#!/usr/bin/env swift
// [T-gemini-usage-thoughts-cache] GH#384 — the Gemini `usageMetadata` parser
// dropped thinking tokens and never read the prompt cache.
//
// Two defects, both in the same six lines, on both GeminiProvider.swift and the
// copy-pasted AntigravityProvider.swift:
//
//  1. Only `candidatesTokenCount` was read as output. Gemini 3.x bills thinking
//     separately in `thoughtsTokenCount` (the API keeps
//     totalTokenCount = prompt + candidates + thoughts), so every thinking
//     token was silently dropped.
//  2. `cacheReadInputTokens` was hardcoded nil and `cachedContentTokenCount`
//     never read, so the cache row never appeared — and because
//     `promptTokenCount` (the FULL input) was passed straight through as
//     `inputTokens`, reporting the cache as well would have double-counted it.
//
// The numbers below are real captures against gemini-3.8-flash, not invented
// fixtures.
//
// Run: swift GeminiProviderUsageTests.swift
//
// Convention: a bare `swift` script like its neighbours — deps/libs/libish_emu.a
// is device-arm64 only, so the app cannot link for the simulator. The parser is
// ported verbatim from the shipping source and section [5] greps the real files
// so a rewrite fails here rather than silently passing a stale copy.
import Foundation

var failures = 0
func check(_ label: String, _ cond: Bool) {
    print(cond ? "  ✅ \(label)" : "  ❌ \(label) — expected true, got false")
    if !cond { failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ a: T, _ b: T) {
    print(a == b ? "  ✅ \(label)" : "  ❌ \(label) — expected \(b), got \(a)")
    if a != b { failures += 1 }
}
func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}

// MARK: - Ported types

struct LLMUsage: Equatable {
    let inputTokens: Int
    let outputTokens: Int
    var cacheCreationInputTokens: Int? = nil
    var cacheReadInputTokens: Int? = nil
}

// MARK: - Port: GeminiProvider.parseUsageMetadata (Providers/Gemini/GeminiProvider.swift)

func parseUsageMetadata(_ usage: [String: Any]) -> LLMUsage {
    let promptTokens = usage["promptTokenCount"] as? Int ?? 0
    let candidatesTokens = usage["candidatesTokenCount"] as? Int ?? 0
    let thoughtsTokens = usage["thoughtsTokenCount"] as? Int ?? 0
    let cachedTokens = (usage["cachedContentTokenCount"] as? Int).flatMap { $0 > 0 ? $0 : nil }

    let totalOutput = candidatesTokens + thoughtsTokens
    let freshInput = cachedTokens.map { max(0, promptTokens - $0) } ?? promptTokens

    return LLMUsage(inputTokens: freshInput, outputTokens: totalOutput,
                    cacheCreationInputTokens: nil, cacheReadInputTokens: cachedTokens)
}

/// The OLD parser, kept so every claim below is falsifiable.
func parseUsageMetadataOLD(_ usage: [String: Any]) -> LLMUsage {
    LLMUsage(inputTokens: usage["promptTokenCount"] as? Int ?? 0,
             outputTokens: usage["candidatesTokenCount"] as? Int ?? 0,
             cacheCreationInputTokens: nil, cacheReadInputTokens: nil)
}

/// Port of TokenUsage.add (Agent/Chat/ChatModels.swift:379) — how the UI folds
/// each streamed chunk together. input/output take max(); the cache fields are
/// ASSIGNED, so the last chunk to arrive decides them.
struct TokenUsage: Equatable {
    var inputTokens = 0, outputTokens = 0
    var cacheCreationTokens = 0, cacheReadTokens = 0, latestContextTokens = 0
    mutating func add(_ u: LLMUsage) {
        // [GH#384] A chunk that newly reports a cache supersedes: its input and
        // its cache are one split of one prompt.
        let cacheNewlyReported = (u.cacheReadInputTokens ?? 0) > cacheReadTokens
        inputTokens = cacheNewlyReported ? u.inputTokens : max(inputTokens, u.inputTokens)
        outputTokens = max(outputTokens, u.outputTokens)
        cacheCreationTokens = (u.cacheCreationInputTokens ?? 0)
        cacheReadTokens = (u.cacheReadInputTokens ?? 0)
        latestContextTokens = u.inputTokens + (u.cacheReadInputTokens ?? 0) + (u.cacheCreationInputTokens ?? 0)
    }
}

/// The capsule's hit rate: cacheRead / (input + cacheRead).
func hitRate(_ t: TokenUsage) -> Double {
    let denom = t.inputTokens + t.cacheReadTokens
    return denom == 0 ? 0 : Double(t.cacheReadTokens) / Double(denom)
}

// MARK: - 1. Thinking tokens (captured: gemini-3.8-flash)

print("\n══ [1] Gemini 3.x thinking tokens are counted ══")
do {
    // Real capture: prompt 11, candidates 20, thoughts 310, total 341.
    let chunk: [String: Any] = [
        "promptTokenCount": 11, "candidatesTokenCount": 20,
        "totalTokenCount": 341, "thoughtsTokenCount": 310,
    ]
    let u = parseUsageMetadata(chunk)
    checkEq("output = candidates + thoughts", u.outputTokens, 330)
    checkEq("input is the prompt (no cache in this capture)", u.inputTokens, 11)
    check("no cache reported", u.cacheReadInputTokens == nil)
    // The API's own invariant must hold against what we report.
    checkEq("prompt + output reconstructs totalTokenCount",
            u.inputTokens + u.outputTokens, chunk["totalTokenCount"] as! Int)

    let old = parseUsageMetadataOLD(chunk)
    checkEq("OLD reported only the visible answer", old.outputTokens, 20)
    check("OLD dropped 310 thinking tokens (94% of the output)",
          old.outputTokens < u.outputTokens && u.outputTokens - old.outputTokens == 310)
}

// MARK: - 2. Context cache (captured: 49k-token prompt)

print("\n══ [2] prompt-cache hits are reported, and not double-counted ══")
do {
    // Real capture, final chunk of the stream.
    let final: [String: Any] = [
        "promptTokenCount": 49016, "candidatesTokenCount": 7,
        "totalTokenCount": 49527, "cachedContentTokenCount": 45026,
        "thoughtsTokenCount": 504,
    ]
    let u = parseUsageMetadata(final)
    checkEq("cache read is surfaced", u.cacheReadInputTokens, 45026)
    checkEq("input is the FRESH remainder, 49016 - 45026", u.inputTokens, 3990)
    checkEq("output = 7 + 504", u.outputTokens, 511)

    var t = TokenUsage(); t.add(u)
    checkEq("the full prompt is still recoverable as context size", t.latestContextTokens, 49016)
    // The point of subtracting: the denominator must be the full prompt, not
    // prompt + cache.
    check("hit rate is ~91.8%", abs(hitRate(t) - 0.918) < 0.001)

    var tOld = TokenUsage(); tOld.add(parseUsageMetadataOLD(final))
    checkEq("OLD showed no cache at all", tOld.cacheReadTokens, 0)
    checkEq("OLD hit rate was 0%", hitRate(tOld), 0)
    // And had the old code merely added the cache without subtracting it:
    var naive = TokenUsage()
    naive.add(LLMUsage(inputTokens: 49016, outputTokens: 511,
                       cacheCreationInputTokens: nil, cacheReadInputTokens: 45026))
    check("…and passing the full prompt through would halve it to ~47.8%",
          abs(hitRate(naive) - 0.478) < 0.001)
}

// MARK: - 3. Multi-chunk stream — the cache must survive to the end

print("\n══ [3] multi-chunk stream: the late cache field wins ══")
do {
    // Real capture: three usage-bearing chunks, only the LAST carrying the cache.
    let chunks: [[String: Any]] = [
        ["promptTokenCount": 49016, "candidatesTokenCount": 3, "totalTokenCount": 49523, "thoughtsTokenCount": 504],
        ["promptTokenCount": 49016, "candidatesTokenCount": 7, "totalTokenCount": 49527, "thoughtsTokenCount": 504],
        ["promptTokenCount": 49016, "candidatesTokenCount": 7, "totalTokenCount": 49527,
         "cachedContentTokenCount": 45026, "thoughtsTokenCount": 504],
    ]
    var t = TokenUsage()
    for c in chunks { t.add(parseUsageMetadata(c)) }
    checkEq("cache read survives the fold", t.cacheReadTokens, 45026)
    checkEq("input settles on the fresh remainder", t.inputTokens, 3990)
    checkEq("output settles on the max, not a sum", t.outputTokens, 511)
    checkEq("context size is the full prompt", t.latestContextTokens, 49016)

    // This is the half the plan missed: parsing alone is not enough, because
    // `add` folds with max() and the cache-less chunks report the FULL prompt.
    // Without the supersede rule the fold keeps input=49016 AND cacheRead=45026,
    // i.e. exactly the double-count the parser change set out to remove.
    var naiveFold = TokenUsage()
    for c in chunks {
        let u = parseUsageMetadata(c)
        naiveFold.inputTokens = max(naiveFold.inputTokens, u.inputTokens)   // old rule
        naiveFold.cacheReadTokens = (u.cacheReadInputTokens ?? 0)
    }
    checkEq("OLD fold rule would have kept the full prompt as input", naiveFold.inputTokens, 49016)
    check("…yielding the halved ~47.8% hit rate again", abs(hitRate(naiveFold) - 0.478) < 0.001)

    checkEq("a cache-less chunk reports the full prompt as input",
            parseUsageMetadata(chunks[0]).inputTokens, 49016)
    check("…and reports no cache", parseUsageMetadata(chunks[0]).cacheReadInputTokens == nil)
}

// MARK: - 4. Backward compatibility and edge cases

print("\n══ [4] older responses and malformed input ══")
do {
    // Gemini 1.5 / non-thinking: neither new field present.
    let legacy: [String: Any] = ["promptTokenCount": 100, "candidatesTokenCount": 40, "totalTokenCount": 140]
    let u = parseUsageMetadata(legacy)
    checkEq("input unchanged", u.inputTokens, 100)
    checkEq("output unchanged", u.outputTokens, 40)
    check("no cache invented", u.cacheReadInputTokens == nil)
    checkEq("identical to the OLD parser for this shape", u, parseUsageMetadataOLD(legacy))

    // A zero cache field means "no cache", not "a cache of zero" — otherwise the
    // UI would show a 0% cache row on every uncached call.
    let zeroCache: [String: Any] = ["promptTokenCount": 100, "candidatesTokenCount": 40,
                                    "cachedContentTokenCount": 0]
    check("cachedContentTokenCount: 0 is treated as absent",
          parseUsageMetadata(zeroCache).cacheReadInputTokens == nil)
    checkEq("…so input is not altered", parseUsageMetadata(zeroCache).inputTokens, 100)

    // A relay reporting a cache larger than the prompt must not go negative.
    let bogus: [String: Any] = ["promptTokenCount": 100, "candidatesTokenCount": 5,
                                "cachedContentTokenCount": 250]
    checkEq("an over-large cache clamps input at 0", parseUsageMetadata(bogus).inputTokens, 0)
    checkEq("…and still reports the cache", parseUsageMetadata(bogus).cacheReadInputTokens, 250)

    // Empty metadata must not crash or fabricate.
    let empty = parseUsageMetadata([:])
    checkEq("empty metadata yields zeros", empty, LLMUsage(inputTokens: 0, outputTokens: 0,
                                                          cacheCreationInputTokens: nil,
                                                          cacheReadInputTokens: nil))

    // Thoughts with no candidates (all-thinking chunk).
    let onlyThoughts: [String: Any] = ["promptTokenCount": 11, "thoughtsTokenCount": 310]
    checkEq("a thoughts-only chunk still reports output", parseUsageMetadata(onlyThoughts).outputTokens, 310)
}

// MARK: - 5. Source invariants

print("\n══ [5] source invariants ══")
do {
    let gem = source("Providers/Gemini/GeminiProvider.swift")
    let anti = source("Providers/Antigravity/AntigravityProvider.swift")
    check("GeminiProvider source was read", !gem.isEmpty)
    check("AntigravityProvider source was read", !anti.isEmpty)

    check("the parser reads thoughtsTokenCount", gem.contains("usage[\"thoughtsTokenCount\"]"))
    check("the parser reads cachedContentTokenCount", gem.contains("usage[\"cachedContentTokenCount\"]"))
    check("output sums candidates and thoughts",
          gem.contains("let totalOutput = candidatesTokens + thoughtsTokens"))
    check("input subtracts the cache, clamped",
          gem.contains("cachedTokens.map { max(0, promptTokens - $0) } ?? promptTokens"))
    check("cacheReadInputTokens is no longer hardcoded nil",
          gem.contains("cacheReadInputTokens: cachedTokens"))
    check("a zero cache is normalized to nil", gem.contains("$0 > 0 ? $0 : nil"))

    // The duplicate is the reason this bug shipped twice; it must now share one
    // implementation rather than be fixed twice and drift again.
    // The aggregation half — without it the parser fix is undone downstream.
    let models = source("Agent/Chat/ChatModels.swift")
    check("TokenUsage.add lets a newly reported cache supersede the input",
          models.contains("let cacheNewlyReported = (u.cacheReadInputTokens ?? 0) > cacheReadTokens")
          && models.contains("inputTokens = cacheNewlyReported ? u.inputTokens : max(inputTokens, u.inputTokens)"))

    check("Antigravity delegates to the shared parser",
          anti.contains("GeminiProvider.parseUsageMetadata(usage)"))
    check("…and keeps no copy of the old body",
          !anti.contains("let output = usage[\"candidatesTokenCount\"] as? Int ?? 0"))
}

print("")
if failures == 0 {
    print("✅ ALL PASSED")
    exit(0)
} else {
    print("❌ \(failures) FAILURE(S)")
    exit(1)
}
