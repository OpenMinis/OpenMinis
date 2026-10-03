import Foundation

/// [T-picker-search-relevance] Relevance scoring for the model picker (GH#272).
///
/// The picker used to filter with a Bool `fuzzyMatch` and keep the provider's
/// default order, so a loose subsequence hit could outrank an exact one —
/// searching "claude" could bury `claude-3-5-sonnet` under models that merely
/// contain those letters in order. Scoring makes the ranking explicit.
///
/// A separate, dependency-free type on purpose: it is the part worth testing,
/// and the picker view itself cannot be unit-tested here (the test target does
/// not link — `deps/libs/libish_emu.a` is device-only). See
/// `MinisTests/Standalone/ModelSearchScorerTests.swift`.
enum ModelSearchScorer {

    /// Tiers, in the order the issue specifies. Spread wide so no combination
    /// of bonuses can lift a lower tier above a higher one.
    enum Tier: Int {
        case exact = 1000
        case prefix = 900
        case tokenPrefix = 800
        case substring = 700
        case subsequence = 100
    }

    /// 0 means "no match" — the caller drops the row.
    ///
    /// `query` is expected pre-lowercased by the caller: scoring runs over
    /// every candidate on every keystroke, and lowercasing the query once
    /// outside the loop rather than per candidate is the difference that
    /// matters at 7,000 models.
    static func score(_ text: String, query: String) -> Int {
        guard !query.isEmpty else { return Tier.exact.rawValue }
        let target = text.lowercased()
        if target == query { return Tier.exact.rawValue }
        if target.hasPrefix(query) {
            // Shorter targets rank higher within the tier: for "claude",
            // `claude-3` should beat `claude-3-5-sonnet-20241022`. Capped at
            // 99 so it can never reach the next tier.
            return Tier.prefix.rawValue + lengthBonus(target)
        }
        if tokenHasPrefix(target, query) { return Tier.tokenPrefix.rawValue + lengthBonus(target) }
        if target.contains(query) { return Tier.substring.rawValue + lengthBonus(target) }
        if isSubsequence(query, of: target) { return Tier.subsequence.rawValue }
        return 0
    }

    /// Best score across several fields (display name, id, provider label).
    /// A model whose id matches exactly should rank as an exact hit even when
    /// its display name only matches loosely.
    static func bestScore(of texts: [String], query: String) -> Int {
        var best = 0
        for t in texts {
            let s = score(t, query: query)
            if s > best { best = s }
            // Nothing can beat an exact hit — stop early. Worth it here:
            // this runs per candidate per keystroke.
            if best >= Tier.exact.rawValue { break }
        }
        return best
    }

    /// Up to 99 points, decreasing with length. Keeps ordering stable and
    /// intuitive inside a tier without crossing tier boundaries.
    private static func lengthBonus(_ target: String) -> Int {
        max(0, 99 - min(99, target.count))
    }

    /// True when any word of `target` starts with `query`. Model ids are
    /// hyphen/slash/dot separated far more often than space separated, so all
    /// of those count as boundaries — otherwise "sonnet" would not
    /// token-prefix-match `claude-3-5-sonnet`.
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

    /// The original loose match, kept as the lowest tier so nothing that used
    /// to be findable stops being findable.
    private static func isSubsequence(_ query: String, of target: String) -> Bool {
        var idx = target.startIndex
        for ch in query {
            guard let found = target[idx...].firstIndex(of: ch) else { return false }
            idx = target.index(after: found)
        }
        return true
    }
}
