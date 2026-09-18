import Foundation

enum ThinkingLevelCatalog {
    private static let rules: [(match: (String) -> Bool, max: ThinkingLevel)] = [
        // GPT-5.6 sol/terra/luna all reach .max. (.ultra is a client-side
        // "Max + orchestration" concept — the wire effort tops out at "max",
        // reasoningEffort(for:level:) maps both .max and .ultra to "max".)
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
        // DeepSeek V4 (recommended `deepseek-flash`, legacy `deepseek-v4*`, and the
        // `*-pro` members) declares low/high/max and does NOT include xhigh. When the
        // models.dev entry is present the data-driven ceiling already tops out at `max`;
        // this family rule is the safety net for a custom OpenAI-compatible DeepSeek
        // endpoint whose bare id never gets enriched — there `reasoningEffort(for:level:)`
        // would otherwise emit a literal "xhigh" the vendor does not declare. Same class
        // of mismatch as MiMo/Agnes, so the same `.high` fallback cap.
        // (GH#356)
        ({ $0.contains("deepseek-flash") || $0.contains("deepseek-v4") }, .high),
        // Claude Opus 4.x — model IDs use hyphens (claude-opus-4-8) in the
        // built-in catalog but third-party proxies may return dots
        // (claude-opus-4.8). Normalize to match both.
        ({ Self.normalizedHasPrefix($0, "claude-opus-4") }, .max),
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
