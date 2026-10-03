import Foundation

enum ThinkingLevelCatalog {
    private static let rules: [(match: (String) -> Bool, max: ThinkingLevel)] = [
        // GPT-5.6 sol/terra/luna all reach .max. (.ultra is a client-side
        // "Max + orchestration" concept — the wire effort tops out at "max",
        // reasoningEffort(for:level:) maps both .max and .ultra to "max".)
        // gpt-6-astra advertises an "ultra" wire level too; deliberately NOT
        // special-cased — same .max ceiling as gpt-5.6-sol/terra, the wire
        // mapping (.max/.ultra → "max") is shared and unchanged.
        ({ $0.hasPrefix("gpt-6-astra") }, .max),
        // [T-gpt6-sol-luna] gpt-6-sol / gpt-6-luna: same .max ceiling, same
        // reasoning as astra above. Sol's registry entry drops "ultra" from its
        // declared levels (the backend rejects that tier, exactly as for
        // gpt-5.6-sol) — and a `.max` ceiling is precisely how that is
        // expressed here: `selectableThinkingLevels` stops at .max, so .ultra is
        // never offered, and the wire mapping folds .max/.ultra to "max" anyway.
        // No separate ultra-exclusion is needed or wanted.
        ({ $0.hasPrefix("gpt-6-sol") || $0.hasPrefix("gpt-6-luna") }, .max),
        ({ $0.hasPrefix("gpt-5.6-sol") || $0.hasPrefix("gpt-5.6-terra") }, .max),
        ({ $0.hasPrefix("gpt-5.6-luna") }, .max),
        ({ $0.hasPrefix("gpt-5.5") }, .xhigh),
        // MiMo ships BOTH id spellings in the wild: catalog docs say
        // "MiMo-2.5" but the live API (api.xiaomimimo.com /v1/models) returns
        // "mimo-v2.5" / "mimo-v2.5-pro" — the old "mimo-2.5" substring missed
        // those, so the wrapper clamp passed xhigh straight through to a
        // backend that 400s on it (verified on-device 2026-07-21). Match the
        // family, not one spelling.
        ({ $0.contains("mimo") || $0.contains("agnes") }, .high),
        // ByteDance seed (Volcano Ark "seed-1.6…"/"seed-2.0…", OpenRouter
        // "bytedance-seed/…"): rejects xhigh with "Invalid reasoning_effort:
        // xhigh" — the field report behind T-fallback-thinking-preclamp. Ark's
        // ladder tops out at high.
        ({ $0.contains("seed-") || $0.contains("bytedance-seed") }, .high),
        // Claude Opus 4.x — model IDs use hyphens (claude-opus-4-8) in the
        // built-in catalog but third-party proxies may return dots
        // (claude-opus-4.8). Normalize to match both.
        ({ Self.normalizedHasPrefix($0, "claude-opus-4") }, .max),
        // [T-anthropic-opus55-ceiling] Claude Opus 5.x — same ceiling, and the
        // rule is load-bearing for `claude-opus-5-5` specifically.
        //
        // `claude-opus-5` IS in models-dev-api.json, so its ceiling already
        // comes from the declared effort tiers and this rule never fires for it.
        // `claude-opus-5-5` is NOT (verified: the catalog carries
        // claude-opus-5, -5-fast and -5-thinking, no -5-5), so
        // `selectableThinkingLevels` is empty for it and
        // `LLMModel.catalogMaxThinkingLevel` falls through to
        // `ruleTop ?? .xhigh`. Without a rule here `ruleTop` was nil and the
        // ceiling silently became .xhigh — Max simply never appeared in the
        // picker, with no error anywhere. Same class as the gpt-5.6-sol case the
        // comment above describes, reached by the other branch.
        ({ Self.normalizedHasPrefix($0, "claude-opus-5") }, .max),
        // [T-deepseek-flash-scope] GH#356. DeepSeek's ladder is
        // ["low","high","max"] — no xhigh, which it rejects the same way Ark's
        // seed models do.
        //
        // This is a FALLBACK, and deliberately so: when models.dev resolves
        // (it does today for both ids — `deepseek-v4-flash` under the official
        // `deepseek` provider, `deepseek-flash` via the cross-provider index),
        // `selectableThinkingLevels` already derives the ceiling from the
        // declared tiers and this rule is never consulted. It earns its keep
        // only when that lookup misses — a custom relay publishing the id under
        // its own name, a snapshot that has drifted, or a future
        // `deepseek-flash-lite` the catalog has not seen yet — where without it
        // the ceiling defaults to `.xhigh` and the UI offers a tier the backend
        // refuses.
        //
        // `.max` is the ceiling because DeepSeek does accept "max"; the wire
        // path separately snaps an xhigh REQUEST down to "high" via
        // clampEffort, which is the nearest declared tier at or below it.
        ({ $0.contains("deepseek-flash") || $0.contains("deepseek-v4") }, .max),
    ]

    static func declaredMaxLevel(for modelId: String) -> ThinkingLevel? {
        let lid = modelId.lowercased()
        return rules.first { $0.match(lid) }?.max
    }

    private static func normalizedHasPrefix(_ id: String, _ prefix: String) -> Bool {
        let normalized = id.replacingOccurrences(of: ".", with: "-")
        return normalized.hasPrefix(prefix)
    }
}
