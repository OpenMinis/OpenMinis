import XCTest
@testable import Minis

/// GOLDEN SNAPSHOT for the Gemini and Anthropic thinking shapes — the Phase 2 §1
/// counterpart to `ThinkingWireGoldenSnapshotTests` (which covers the OpenAI family).
///
/// WHY: Phase 1 deliberately left these two providers out of `ThinkingRuleResolver`
/// (their logic lived in `GeminiProvider`'s private thinkingConfig builders and
/// `AnthropicAgentProvider`'s budget/effort helpers). Phase 2 §1 migrates them in. The
/// acceptance criterion is the same as Phase 1's: byte-for-byte identical behaviour.
/// This file is generated against the PRE-migration implementations and committed
/// before the migration, so it is an oracle rather than a rubber stamp.
///
/// HOW TO USE
///   1. Committed BEFORE the migration, generated from the OLD code.
///   2. After the migration it must still pass unchanged. A diff is a behaviour change —
///      either an unintended regression, or an intended fix that must be called out in
///      the commit message and updated deliberately.
///   3. Do NOT regenerate expectations to turn a red test green without first explaining,
///      in words, why the wire format legitimately changed.
///
/// COVERAGE — every case the Phase 2 spec names:
///   • Gemini 2.5 Pro    — cannot disable, floor 128 (df8a823d)
///   • Gemini 2.5 Flash  — budget 0 disables
///   • Gemini 2.5 Flash Lite — no thinking support at all
///   • Gemini 3.x        — `thinkingLevel` string instead of a numeric budget
///   • Gemini specialized (-tts/-image/-embedding/-vision) — no thinking config at ANY
///     level, including ids that also match a family pattern (OpenMinis#226). The
///     enabled-level rows for these models changed in that fix: they used to receive a
///     `thinkingBudget`, which Gemini rejects with 400 for TTS models.
///   • Gemini unknown id — conservative fallback table, floor 128 when enabled
///   • Anthropic 4.6+    — adaptive `effort`, and an explicit disable at OFF
///   • Anthropic ≤4.5    — `budget_tokens`, strictly below max_tokens
///   • Anthropic dotted ids (claude-opus-4.8) — must resolve like the hyphenated form
final class ThinkingWireGeminiAnthropicSnapshotTests: XCTestCase {

    private func canonical(_ value: Any) -> String {
        switch value {
        case let d as [String: Any]:
            return "{" + d.keys.sorted().map { "\($0):\(canonical(d[$0]!))" }.joined(separator: ",") + "}"
        case let n as NSNumber:
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue ? "true" : "false" }
            return "\(n)"
        case let s as String:
            return "\"\(s)\""
        default:
            return "\(value)"
        }
    }

    private let geminiModels = [
        "gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite",
        "gemini-3-pro-preview", "gemini-3-flash-preview", "gemini-flash-latest",
        "gemini-2.5-pro-exp-0827", "gemini-2.0-tts", "gemini-2.5-embedding",
        // [OpenMinis#226] Specialized ids that ALSO match a family pattern — the exact
        // shape the old ordering got wrong. `gemini-3.1-flash-tts-preview` contains
        // "gemini-3", `gemini-2.5-pro-preview-tts` contains "2.5-pro", so both were
        // answered by the family branch and shipped a thinking parameter the API rejects.
        // The plain `gemini-2.0-tts` above never caught this: it matches no family.
        "gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts",
        "gemini-2.5-pro-preview-tts", "gemini-3-pro-image-preview",
        "unknown-gemini-model",
    ]

    private let anthropicModels = [
        "claude-opus-4-8", "claude-opus-4.8", "claude-sonnet-4-6", "claude-sonnet-4-5",
        "claude-haiku-4-5", "claude-opus-4-1", "claude-3-7-sonnet", "not-a-claude",
    ]

    private let levels: [ThinkingLevel] = [.off, .low, .medium, .high, .xhigh, .max, .ultra]

    /// Renders the same matrix the baseline was generated from, through whatever the
    /// current production path is. After the migration this goes through
    /// `ThinkingRuleResolver`; before it, through the provider-local helpers.
    private func render() -> String {
        var out: [String] = []
        for m in geminiModels {
            for lv in levels {
                let cfg = ThinkingRuleResolver.geminiThinkingConfig(modelId: m, level: lv)
                out.append("gemini/\(m)/\(lv.rawValue) -> \(canonical(cfg))")
            }
        }
        for m in anthropicModels {
            for lv in levels {
                for mt in [8192, 65536] {
                    let shape = ThinkingRuleResolver.anthropicThinkingShape(
                        modelId: m, supportsReasoning: true, level: lv, maxTokens: mt
                    )
                    out.append("anthropic/\(m)/\(lv.rawValue)/mt\(mt) -> \(canonical(shape))")
                }
            }
        }
        return out.joined(separator: "\n")
    }

    func testGeminiAndAnthropicGoldenSnapshot() {
        XCTAssertEqual(
            render(), Self.expected,
            """
            GEMINI/ANTHROPIC THINKING SHAPE CHANGED.

            This is the byte-for-byte oracle for the Phase 2 §1 migration. A failure means
            the resolver emits something different from the pre-migration provider logic
            for at least one (model, level) pair.

            Do not paste the new output in. Diff the blocks, find which branch moved, and
            either restore parity or justify the change explicitly.
            """
        )
    }

    // MARK: - [T-gemini37-flash-minimal-400] 3.7+ Flash rejects "minimal"

    /// gemini-3.7-flash answers 400 INVALID_ARGUMENT ("Thinking level MINIMAL
    /// is not supported for this model.") — thinking-off must floor at "low",
    /// like Pro. Version-threshold rule: every dotted 3.<minor≥7> Flash id
    /// floors; unversioned and 3.0–3.6 Flash keep "minimal" exactly as the
    /// golden snapshot above pins.
    func testGemini37FlashOffFloorsToLowInsteadOfMinimal() {
        for id in ["gemini-3.7-flash", "gemini-3.7-flash-preview", "gemini-3.9-flash"] {
            let cfg = ThinkingRuleResolver.geminiThinkingConfig(modelId: id, level: .off)
            XCTAssertEqual(cfg["thinkingLevel"] as? String, "low", "id=\(id)")
            XCTAssertNil(cfg["includeThoughts"], "id=\(id) — off must not request thoughts")
        }
    }

    func testPre37FlashOffKeepsMinimal() {
        for id in ["gemini-3-flash-preview", "gemini-3.1-flash", "gemini-3.6-flash-preview"] {
            let cfg = ThinkingRuleResolver.geminiThinkingConfig(modelId: id, level: .off)
            XCTAssertEqual(cfg["thinkingLevel"] as? String, "minimal", "id=\(id)")
        }
    }

    func testGemini37ProOffUnchangedAtLow() {
        let cfg = ThinkingRuleResolver.geminiThinkingConfig(modelId: "gemini-3.7-pro", level: .off)
        XCTAssertEqual(cfg["thinkingLevel"] as? String, "low")
    }

    func testGemini37FlashEnabledLevelsUnaffected() {
        let cfg = ThinkingRuleResolver.geminiThinkingConfig(modelId: "gemini-3.7-flash", level: .low)
        XCTAssertEqual(cfg["thinkingLevel"] as? String, "low")
        XCTAssertEqual(cfg["includeThoughts"] as? Bool, true)
    }

    func testGemini37FlashSpecializedSuffixStillSendsNothing() {
        // The -tts/-image/-embedding/-vision precedence (OpenMinis#226) must
        // keep outranking the new version rule.
        let cfg = ThinkingRuleResolver.geminiThinkingConfig(modelId: "gemini-3.7-flash-tts", level: .off)
        XCTAssertTrue(cfg.isEmpty)
    }

    /// Generated from the PRE-migration implementations
    /// (GeminiProvider.minimalThinkingConfig/elevatedThinkingConfig,
    /// AnthropicAgentProvider.thinkingBudget/thinkingEffort +
    /// AnthropicProvider.modelUsesAdaptiveThinking).
    static let expected = #"""
gemini/gemini-2.5-pro/off -> {thinkingBudget:128}
gemini/gemini-2.5-pro/low -> {includeThoughts:true,thinkingBudget:2048}
gemini/gemini-2.5-pro/medium -> {includeThoughts:true,thinkingBudget:8192}
gemini/gemini-2.5-pro/high -> {includeThoughts:true,thinkingBudget:16384}
gemini/gemini-2.5-pro/xhigh -> {includeThoughts:true,thinkingBudget:32768}
gemini/gemini-2.5-pro/max -> {includeThoughts:true,thinkingBudget:32768}
gemini/gemini-2.5-pro/ultra -> {includeThoughts:true,thinkingBudget:32768}
gemini/gemini-2.5-flash/off -> {thinkingBudget:0}
gemini/gemini-2.5-flash/low -> {includeThoughts:true,thinkingBudget:1024}
gemini/gemini-2.5-flash/medium -> {includeThoughts:true,thinkingBudget:4096}
gemini/gemini-2.5-flash/high -> {includeThoughts:true,thinkingBudget:8192}
gemini/gemini-2.5-flash/xhigh -> {includeThoughts:true,thinkingBudget:16384}
gemini/gemini-2.5-flash/max -> {includeThoughts:true,thinkingBudget:16384}
gemini/gemini-2.5-flash/ultra -> {includeThoughts:true,thinkingBudget:16384}
gemini/gemini-2.5-flash-lite/off -> {}
gemini/gemini-2.5-flash-lite/low -> {includeThoughts:true,thinkingBudget:1024}
gemini/gemini-2.5-flash-lite/medium -> {includeThoughts:true,thinkingBudget:4096}
gemini/gemini-2.5-flash-lite/high -> {includeThoughts:true,thinkingBudget:8192}
gemini/gemini-2.5-flash-lite/xhigh -> {includeThoughts:true,thinkingBudget:16384}
gemini/gemini-2.5-flash-lite/max -> {includeThoughts:true,thinkingBudget:16384}
gemini/gemini-2.5-flash-lite/ultra -> {includeThoughts:true,thinkingBudget:16384}
gemini/gemini-3-pro-preview/off -> {thinkingLevel:"low"}
gemini/gemini-3-pro-preview/low -> {includeThoughts:true,thinkingLevel:"low"}
gemini/gemini-3-pro-preview/medium -> {includeThoughts:true,thinkingLevel:"medium"}
gemini/gemini-3-pro-preview/high -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3-pro-preview/xhigh -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3-pro-preview/max -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3-pro-preview/ultra -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3-flash-preview/off -> {thinkingLevel:"minimal"}
gemini/gemini-3-flash-preview/low -> {includeThoughts:true,thinkingLevel:"low"}
gemini/gemini-3-flash-preview/medium -> {includeThoughts:true,thinkingLevel:"medium"}
gemini/gemini-3-flash-preview/high -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3-flash-preview/xhigh -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3-flash-preview/max -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3-flash-preview/ultra -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-flash-latest/off -> {thinkingBudget:0}
gemini/gemini-flash-latest/low -> {includeThoughts:true,thinkingBudget:1024}
gemini/gemini-flash-latest/medium -> {includeThoughts:true,thinkingBudget:4096}
gemini/gemini-flash-latest/high -> {includeThoughts:true,thinkingBudget:8192}
gemini/gemini-flash-latest/xhigh -> {includeThoughts:true,thinkingBudget:16384}
gemini/gemini-flash-latest/max -> {includeThoughts:true,thinkingBudget:16384}
gemini/gemini-flash-latest/ultra -> {includeThoughts:true,thinkingBudget:16384}
gemini/gemini-2.5-pro-exp-0827/off -> {thinkingBudget:128}
gemini/gemini-2.5-pro-exp-0827/low -> {includeThoughts:true,thinkingBudget:2048}
gemini/gemini-2.5-pro-exp-0827/medium -> {includeThoughts:true,thinkingBudget:8192}
gemini/gemini-2.5-pro-exp-0827/high -> {includeThoughts:true,thinkingBudget:16384}
gemini/gemini-2.5-pro-exp-0827/xhigh -> {includeThoughts:true,thinkingBudget:32768}
gemini/gemini-2.5-pro-exp-0827/max -> {includeThoughts:true,thinkingBudget:32768}
gemini/gemini-2.5-pro-exp-0827/ultra -> {includeThoughts:true,thinkingBudget:32768}
gemini/gemini-2.0-tts/off -> {}
gemini/gemini-2.0-tts/low -> {}
gemini/gemini-2.0-tts/medium -> {}
gemini/gemini-2.0-tts/high -> {}
gemini/gemini-2.0-tts/xhigh -> {}
gemini/gemini-2.0-tts/max -> {}
gemini/gemini-2.0-tts/ultra -> {}
gemini/gemini-2.5-embedding/off -> {}
gemini/gemini-2.5-embedding/low -> {}
gemini/gemini-2.5-embedding/medium -> {}
gemini/gemini-2.5-embedding/high -> {}
gemini/gemini-2.5-embedding/xhigh -> {}
gemini/gemini-2.5-embedding/max -> {}
gemini/gemini-2.5-embedding/ultra -> {}
gemini/gemini-3.1-flash-tts-preview/off -> {}
gemini/gemini-3.1-flash-tts-preview/low -> {}
gemini/gemini-3.1-flash-tts-preview/medium -> {}
gemini/gemini-3.1-flash-tts-preview/high -> {}
gemini/gemini-3.1-flash-tts-preview/xhigh -> {}
gemini/gemini-3.1-flash-tts-preview/max -> {}
gemini/gemini-3.1-flash-tts-preview/ultra -> {}
gemini/gemini-2.5-flash-preview-tts/off -> {}
gemini/gemini-2.5-flash-preview-tts/low -> {}
gemini/gemini-2.5-flash-preview-tts/medium -> {}
gemini/gemini-2.5-flash-preview-tts/high -> {}
gemini/gemini-2.5-flash-preview-tts/xhigh -> {}
gemini/gemini-2.5-flash-preview-tts/max -> {}
gemini/gemini-2.5-flash-preview-tts/ultra -> {}
gemini/gemini-2.5-pro-preview-tts/off -> {}
gemini/gemini-2.5-pro-preview-tts/low -> {}
gemini/gemini-2.5-pro-preview-tts/medium -> {}
gemini/gemini-2.5-pro-preview-tts/high -> {}
gemini/gemini-2.5-pro-preview-tts/xhigh -> {}
gemini/gemini-2.5-pro-preview-tts/max -> {}
gemini/gemini-2.5-pro-preview-tts/ultra -> {}
gemini/gemini-3-pro-image-preview/off -> {}
gemini/gemini-3-pro-image-preview/low -> {}
gemini/gemini-3-pro-image-preview/medium -> {}
gemini/gemini-3-pro-image-preview/high -> {}
gemini/gemini-3-pro-image-preview/xhigh -> {}
gemini/gemini-3-pro-image-preview/max -> {}
gemini/gemini-3-pro-image-preview/ultra -> {}
gemini/unknown-gemini-model/off -> {thinkingBudget:0}
gemini/unknown-gemini-model/low -> {includeThoughts:true,thinkingBudget:1024}
gemini/unknown-gemini-model/medium -> {includeThoughts:true,thinkingBudget:4096}
gemini/unknown-gemini-model/high -> {includeThoughts:true,thinkingBudget:8192}
gemini/unknown-gemini-model/xhigh -> {includeThoughts:true,thinkingBudget:16384}
gemini/unknown-gemini-model/max -> {includeThoughts:true,thinkingBudget:16384}
gemini/unknown-gemini-model/ultra -> {includeThoughts:true,thinkingBudget:16384}
anthropic/claude-opus-4-8/off/mt8192 -> {disabled:true}
anthropic/claude-opus-4-8/off/mt65536 -> {disabled:true}
anthropic/claude-opus-4-8/low/mt8192 -> {effort:"low"}
anthropic/claude-opus-4-8/low/mt65536 -> {effort:"low"}
anthropic/claude-opus-4-8/medium/mt8192 -> {effort:"medium"}
anthropic/claude-opus-4-8/medium/mt65536 -> {effort:"medium"}
anthropic/claude-opus-4-8/high/mt8192 -> {effort:"high"}
anthropic/claude-opus-4-8/high/mt65536 -> {effort:"high"}
anthropic/claude-opus-4-8/xhigh/mt8192 -> {effort:"max"}
anthropic/claude-opus-4-8/xhigh/mt65536 -> {effort:"max"}
anthropic/claude-opus-4-8/max/mt8192 -> {effort:"max"}
anthropic/claude-opus-4-8/max/mt65536 -> {effort:"max"}
anthropic/claude-opus-4-8/ultra/mt8192 -> {effort:"max"}
anthropic/claude-opus-4-8/ultra/mt65536 -> {effort:"max"}
anthropic/claude-opus-4.8/off/mt8192 -> {disabled:true}
anthropic/claude-opus-4.8/off/mt65536 -> {disabled:true}
anthropic/claude-opus-4.8/low/mt8192 -> {effort:"low"}
anthropic/claude-opus-4.8/low/mt65536 -> {effort:"low"}
anthropic/claude-opus-4.8/medium/mt8192 -> {effort:"medium"}
anthropic/claude-opus-4.8/medium/mt65536 -> {effort:"medium"}
anthropic/claude-opus-4.8/high/mt8192 -> {effort:"high"}
anthropic/claude-opus-4.8/high/mt65536 -> {effort:"high"}
anthropic/claude-opus-4.8/xhigh/mt8192 -> {effort:"max"}
anthropic/claude-opus-4.8/xhigh/mt65536 -> {effort:"max"}
anthropic/claude-opus-4.8/max/mt8192 -> {effort:"max"}
anthropic/claude-opus-4.8/max/mt65536 -> {effort:"max"}
anthropic/claude-opus-4.8/ultra/mt8192 -> {effort:"max"}
anthropic/claude-opus-4.8/ultra/mt65536 -> {effort:"max"}
anthropic/claude-sonnet-4-6/off/mt8192 -> {disabled:true}
anthropic/claude-sonnet-4-6/off/mt65536 -> {disabled:true}
anthropic/claude-sonnet-4-6/low/mt8192 -> {effort:"low"}
anthropic/claude-sonnet-4-6/low/mt65536 -> {effort:"low"}
anthropic/claude-sonnet-4-6/medium/mt8192 -> {effort:"medium"}
anthropic/claude-sonnet-4-6/medium/mt65536 -> {effort:"medium"}
anthropic/claude-sonnet-4-6/high/mt8192 -> {effort:"high"}
anthropic/claude-sonnet-4-6/high/mt65536 -> {effort:"high"}
anthropic/claude-sonnet-4-6/xhigh/mt8192 -> {effort:"max"}
anthropic/claude-sonnet-4-6/xhigh/mt65536 -> {effort:"max"}
anthropic/claude-sonnet-4-6/max/mt8192 -> {effort:"max"}
anthropic/claude-sonnet-4-6/max/mt65536 -> {effort:"max"}
anthropic/claude-sonnet-4-6/ultra/mt8192 -> {effort:"max"}
anthropic/claude-sonnet-4-6/ultra/mt65536 -> {effort:"max"}
anthropic/claude-sonnet-4-5/off/mt8192 -> {}
anthropic/claude-sonnet-4-5/off/mt65536 -> {}
anthropic/claude-sonnet-4-5/low/mt8192 -> {budget_tokens:8191}
anthropic/claude-sonnet-4-5/low/mt65536 -> {budget_tokens:8192}
anthropic/claude-sonnet-4-5/medium/mt8192 -> {budget_tokens:8191}
anthropic/claude-sonnet-4-5/medium/mt65536 -> {budget_tokens:32768}
anthropic/claude-sonnet-4-5/high/mt8192 -> {budget_tokens:8191}
anthropic/claude-sonnet-4-5/high/mt65536 -> {budget_tokens:65535}
anthropic/claude-sonnet-4-5/xhigh/mt8192 -> {budget_tokens:8191}
anthropic/claude-sonnet-4-5/xhigh/mt65536 -> {budget_tokens:65535}
anthropic/claude-sonnet-4-5/max/mt8192 -> {budget_tokens:8191}
anthropic/claude-sonnet-4-5/max/mt65536 -> {budget_tokens:65535}
anthropic/claude-sonnet-4-5/ultra/mt8192 -> {budget_tokens:8191}
anthropic/claude-sonnet-4-5/ultra/mt65536 -> {budget_tokens:65535}
anthropic/claude-haiku-4-5/off/mt8192 -> {}
anthropic/claude-haiku-4-5/off/mt65536 -> {}
anthropic/claude-haiku-4-5/low/mt8192 -> {budget_tokens:8191}
anthropic/claude-haiku-4-5/low/mt65536 -> {budget_tokens:8192}
anthropic/claude-haiku-4-5/medium/mt8192 -> {budget_tokens:8191}
anthropic/claude-haiku-4-5/medium/mt65536 -> {budget_tokens:32768}
anthropic/claude-haiku-4-5/high/mt8192 -> {budget_tokens:8191}
anthropic/claude-haiku-4-5/high/mt65536 -> {budget_tokens:65535}
anthropic/claude-haiku-4-5/xhigh/mt8192 -> {budget_tokens:8191}
anthropic/claude-haiku-4-5/xhigh/mt65536 -> {budget_tokens:65535}
anthropic/claude-haiku-4-5/max/mt8192 -> {budget_tokens:8191}
anthropic/claude-haiku-4-5/max/mt65536 -> {budget_tokens:65535}
anthropic/claude-haiku-4-5/ultra/mt8192 -> {budget_tokens:8191}
anthropic/claude-haiku-4-5/ultra/mt65536 -> {budget_tokens:65535}
anthropic/claude-opus-4-1/off/mt8192 -> {}
anthropic/claude-opus-4-1/off/mt65536 -> {}
anthropic/claude-opus-4-1/low/mt8192 -> {budget_tokens:8191}
anthropic/claude-opus-4-1/low/mt65536 -> {budget_tokens:8192}
anthropic/claude-opus-4-1/medium/mt8192 -> {budget_tokens:8191}
anthropic/claude-opus-4-1/medium/mt65536 -> {budget_tokens:32768}
anthropic/claude-opus-4-1/high/mt8192 -> {budget_tokens:8191}
anthropic/claude-opus-4-1/high/mt65536 -> {budget_tokens:65535}
anthropic/claude-opus-4-1/xhigh/mt8192 -> {budget_tokens:8191}
anthropic/claude-opus-4-1/xhigh/mt65536 -> {budget_tokens:65535}
anthropic/claude-opus-4-1/max/mt8192 -> {budget_tokens:8191}
anthropic/claude-opus-4-1/max/mt65536 -> {budget_tokens:65535}
anthropic/claude-opus-4-1/ultra/mt8192 -> {budget_tokens:8191}
anthropic/claude-opus-4-1/ultra/mt65536 -> {budget_tokens:65535}
anthropic/claude-3-7-sonnet/off/mt8192 -> {}
anthropic/claude-3-7-sonnet/off/mt65536 -> {}
anthropic/claude-3-7-sonnet/low/mt8192 -> {budget_tokens:8191}
anthropic/claude-3-7-sonnet/low/mt65536 -> {budget_tokens:8192}
anthropic/claude-3-7-sonnet/medium/mt8192 -> {budget_tokens:8191}
anthropic/claude-3-7-sonnet/medium/mt65536 -> {budget_tokens:32768}
anthropic/claude-3-7-sonnet/high/mt8192 -> {budget_tokens:8191}
anthropic/claude-3-7-sonnet/high/mt65536 -> {budget_tokens:65535}
anthropic/claude-3-7-sonnet/xhigh/mt8192 -> {budget_tokens:8191}
anthropic/claude-3-7-sonnet/xhigh/mt65536 -> {budget_tokens:65535}
anthropic/claude-3-7-sonnet/max/mt8192 -> {budget_tokens:8191}
anthropic/claude-3-7-sonnet/max/mt65536 -> {budget_tokens:65535}
anthropic/claude-3-7-sonnet/ultra/mt8192 -> {budget_tokens:8191}
anthropic/claude-3-7-sonnet/ultra/mt65536 -> {budget_tokens:65535}
anthropic/not-a-claude/off/mt8192 -> {}
anthropic/not-a-claude/off/mt65536 -> {}
anthropic/not-a-claude/low/mt8192 -> {budget_tokens:8191}
anthropic/not-a-claude/low/mt65536 -> {budget_tokens:8192}
anthropic/not-a-claude/medium/mt8192 -> {budget_tokens:8191}
anthropic/not-a-claude/medium/mt65536 -> {budget_tokens:32768}
anthropic/not-a-claude/high/mt8192 -> {budget_tokens:8191}
anthropic/not-a-claude/high/mt65536 -> {budget_tokens:65535}
anthropic/not-a-claude/xhigh/mt8192 -> {budget_tokens:8191}
anthropic/not-a-claude/xhigh/mt65536 -> {budget_tokens:65535}
anthropic/not-a-claude/max/mt8192 -> {budget_tokens:8191}
anthropic/not-a-claude/max/mt65536 -> {budget_tokens:65535}
anthropic/not-a-claude/ultra/mt8192 -> {budget_tokens:8191}
anthropic/not-a-claude/ultra/mt65536 -> {budget_tokens:65535}
"""#
}
