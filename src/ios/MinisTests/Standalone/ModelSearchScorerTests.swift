// [T-picker-search-relevance] Ranking rules for the model picker's search
// (GH#272), and the debounce contract behind GH#271.
//
// Standalone (`swift ModelSearchScorerTests.swift`) because the MinisTests
// target cannot link on this machine — `deps/libs/libish_emu.a` is built for
// iOS, not iOS-simulator. Same rationale as InLoopCompactOrderTests.swift.
//
// The scorer is reproduced verbatim from
// src/ios/Views/Providers/ModelSearchScorer.swift. It has no dependencies, so
// the copy is exact rather than a model of it.

import Foundation

// MARK: - Production code, copied verbatim

enum ModelSearchScorer {
    enum Tier: Int {
        case exact = 1000
        case prefix = 900
        case tokenPrefix = 800
        case substring = 700
        case subsequence = 100
    }

    static func score(_ text: String, query: String) -> Int {
        guard !query.isEmpty else { return Tier.exact.rawValue }
        let target = text.lowercased()
        if target == query { return Tier.exact.rawValue }
        if target.hasPrefix(query) { return Tier.prefix.rawValue + lengthBonus(target) }
        if tokenHasPrefix(target, query) { return Tier.tokenPrefix.rawValue + lengthBonus(target) }
        if target.contains(query) { return Tier.substring.rawValue + lengthBonus(target) }
        if isSubsequence(query, of: target) { return Tier.subsequence.rawValue }
        return 0
    }

    static func bestScore(of texts: [String], query: String) -> Int {
        var best = 0
        for t in texts {
            let s = score(t, query: query)
            if s > best { best = s }
            if best >= Tier.exact.rawValue { break }
        }
        return best
    }

    private static func lengthBonus(_ target: String) -> Int {
        max(0, 99 - min(99, target.count))
    }

    private static func tokenHasPrefix(_ target: String, _ query: String) -> Bool {
        var atBoundary = true
        var i = target.startIndex
        while i < target.endIndex {
            let ch = target[i]
            if atBoundary, target[i...].hasPrefix(query) { return true }
            atBoundary = (ch == "-" || ch == "/" || ch == "." || ch == "_" || ch == ":" || ch == " ")
            i = target.index(after: i)
        }
        return false
    }

    private static func isSubsequence(_ query: String, of target: String) -> Bool {
        var idx = target.startIndex
        for ch in query {
            guard let found = target[idx...].firstIndex(of: ch) else { return false }
            idx = target.index(after: found)
        }
        return true
    }
}

// MARK: - Harness

var failures = 0
func check(_ name: String, _ cond: Bool) {
    if cond { print("  ✅ \(name)") } else { print("  ❌ \(name)"); failures += 1 }
}
func checkEq<T: Equatable>(_ name: String, _ got: T, _ want: T) {
    if got == want { print("  ✅ \(name)") }
    else { print("  ❌ \(name): got \(got), want \(want)"); failures += 1 }
}

// MARK: - 1. Tier ordering

print("Tier ordering")
do {
    let q = "claude"
    let exact = ModelSearchScorer.score("claude", query: q)
    let prefix = ModelSearchScorer.score("claude-3-5-sonnet", query: q)
    let token = ModelSearchScorer.score("anthropic/claude-3", query: q)
    let substr = ModelSearchScorer.score("myclaudemodel", query: q)
    let subseq = ModelSearchScorer.score("cool-lightweight-audio-decoder", query: q)

    check("exact > prefix", exact > prefix)
    check("prefix > token-prefix", prefix > token)
    check("token-prefix > substring", token > substr)
    check("substring > subsequence", substr > subseq)
    check("subsequence still matches (nothing stops being findable)", subseq > 0)

    // The reported symptom: a loose subsequence hit outranking the real model.
    check("GH#272: claude-3-5-sonnet outranks a loose subsequence hit", prefix > subseq)
}

// MARK: - 2. Bonuses never cross a tier

print("\nBonuses stay inside their tier")
do {
    // A very short substring match must still lose to the longest possible
    // token-prefix match.
    let shortSubstring = ModelSearchScorer.score("xclaudex", query: "claude")
    let longTokenPrefix = ModelSearchScorer.score(
        "vendor/some-extremely-long-model-name-claude-with-suffix", query: "claude")
    check("longest token-prefix still beats shortest substring", longTokenPrefix > shortSubstring)
    check("substring stays under the token-prefix floor", shortSubstring < 800)
    check("token-prefix stays under the prefix floor", longTokenPrefix < 900)
}

// MARK: - 3. Shorter wins inside a tier

print("\nShorter targets rank first within a tier")
do {
    let short = ModelSearchScorer.score("claude-3", query: "claude")
    let long = ModelSearchScorer.score("claude-3-5-sonnet-20241022-v2", query: "claude")
    check("claude-3 outranks claude-3-5-sonnet-20241022-v2", short > long)
}

// MARK: - 4. Token boundaries

print("\nToken boundaries in model ids")
do {
    // Ids are hyphen/slash separated far more than space separated.
    check("hyphen boundary", ModelSearchScorer.score("claude-3-5-sonnet", query: "sonnet") >= 800)
    check("slash boundary", ModelSearchScorer.score("anthropic/claude-3", query: "claude") >= 800)
    check("dot boundary", ModelSearchScorer.score("gpt-4.turbo", query: "turbo") >= 800)
    check("underscore boundary", ModelSearchScorer.score("qwen_2_5_coder", query: "coder") >= 800)
    // Mid-token is a substring, not a token prefix.
    let mid = ModelSearchScorer.score("xxsonnetxx", query: "sonnet")
    check("mid-token is substring tier", mid >= 700 && mid < 800)
}

// MARK: - 5. Multi-field

print("\nbestScore across fields")
do {
    // Display name matches loosely, id matches exactly -> exact wins.
    let s = ModelSearchScorer.bestScore(
        of: ["Claude Sonnet (latest)", "claude"], query: "claude")
    checkEq("exact id beats loose display name", s, 1000)
    checkEq("no field matches -> 0",
            ModelSearchScorer.bestScore(of: ["gpt-4", "openai"], query: "zzzz"), 0)
}

// MARK: - 6. Edge cases

print("\nEdge cases")
do {
    checkEq("empty query treated as match-all", ModelSearchScorer.score("anything", query: ""), 1000)
    checkEq("no match -> 0", ModelSearchScorer.score("gpt-4", query: "zzzz"), 0)
    check("case insensitive", ModelSearchScorer.score("CLAUDE-3-5", query: "claude") >= 900)
    checkEq("empty target, non-empty query -> 0", ModelSearchScorer.score("", query: "a"), 0)
}

// MARK: - 7. A realistic ranking

print("\nRealistic ranking for \"claude\"")
do {
    let candidates = [
        "cool-lightweight-audio-decoder",   // subsequence only
        "anthropic/claude-3-opus",          // token prefix
        "claude-3-5-sonnet-20241022",       // prefix
        "claude",                           // exact
        "myclaudemodel",                    // substring
    ]
    let ranked = candidates
        .map { ($0, ModelSearchScorer.score($0, query: "claude")) }
        .sorted { $0.1 > $1.1 }
        .map(\.0)
    checkEq("ordering", ranked, [
        "claude",
        "claude-3-5-sonnet-20241022",
        "anthropic/claude-3-opus",
        "myclaudemodel",
        "cool-lightweight-audio-decoder",
    ])
}

// MARK: - 8. Debounce contract (GH#271)

print("\nDebounce contract")
do {
    // The rule the picker implements: only the LAST keystroke inside the
    // window survives, because each new one cancels the pending task.
    // Modelled here as the coalescing it is.
    struct Debouncer {
        var pending: String?
        var committed: [String] = []
        mutating func type(_ s: String) { pending = s }          // cancels previous
        mutating func fire() { if let p = pending { committed.append(p); pending = nil } }
    }
    var d = Debouncer()
    for ch in ["c", "cl", "cla", "clau", "claud", "claude"] { d.type(ch) }
    d.fire()
    checkEq("6 keystrokes produce 1 search", d.committed, ["claude"])

    var d2 = Debouncer()
    d2.type("cla"); d2.fire()
    d2.type("claude"); d2.fire()
    checkEq("a pause commits both", d2.committed, ["cla", "claude"])
}

print(failures == 0 ? "\n✅ all checks passed" : "\n❌ \(failures) check(s) failed")
exit(failures == 0 ? 0 : 1)
