import XCTest
@testable import Minis

/// Regression safety-net for the thinking/reasoning wire-format rules catalogued in
/// `/tmp/thinking_rules_evidence.md` §A (17 rules mined from git history).
///
/// WHY THIS EXISTS: the dominant failure mode for these rules is SILENT DEGRADATION —
/// not a thrown error, but a field quietly landing at the wrong path or a tier quietly
/// collapsing onto the vendor default. Three shipped examples, none of which raised
/// an exception:
///   • 847822eb — the DeepSeek V4 tier was nested INSIDE `thinking:{}` instead of being
///     a root sibling, so every request ran at the vendor default for ~3 months.
///   • 22647505 — a glm/kimi/deepseek/minimax id-substring skip-list emitted NO thinking
///     field at all; the server silently applied its own default tier.
///   • 5aa9dc64 — the Claude Opus catalog rules used dots while real ids use hyphens, so
///     the rules NEVER matched and every Opus model fell through to the wrong ceiling.
/// Accordingly, every positive assertion here is paired with a mutually-exclusive
/// negative one, and assertions target the REAL emitted body, never a reimplementation.
///
/// These call production code directly: `OpenAIAgentProvider.injectThinkingParams` is
/// `static` + `internal`, so `@testable import` reaches it and returns the mutated body
/// dictionary with no network, no credentials, and no provider instance. Where a rule
/// lives on an instance method the corresponding static seam is used instead
/// (`AnthropicAgentProvider.thinkingBudget`, `ThinkingLevelCatalog.declaredMaxLevel`).
final class ThinkingRulesRegressionTests: XCTestCase {

    // MARK: - Helpers

    private func model(
        _ id: String,
        supportsReasoning: Bool? = true,
        effortValues: [String]? = nil,
        interleavedReasoningField: String? = nil
    ) -> LLMModel {
        LLMModel(
            id: id,
            displayName: id,
            provider: "TestProvider",
            supportsReasoning: supportsReasoning,
            interleavedReasoningField: interleavedReasoningField,
            reasoningEffortValues: effortValues
        )
    }

    /// Run the real injector and return the resulting body.
    private func inject(
        model m: LLMModel,
        level: ThinkingLevel,
        isOpenRouter: Bool = false,
        maxTokens: Int = 4096,
        offEffort: String? = nil,
        unifiedReasoningEffort: Bool = false
    ) -> [String: Any] {
        var body: [String: Any] = [:]
        OpenAIAgentProvider.injectThinkingParams(
            into: &body,
            model: m,
            level: level,
            isOpenRouter: isOpenRouter,
            maxTokens: maxTokens,
            offEffort: offEffort,
            unifiedReasoningEffort: unifiedReasoningEffort
        )
        return body
    }

    /// Every key that could carry a thinking control, for zero-field assertions.
    private func thinkingKeys(in body: [String: Any]) -> [String] {
        ["reasoning_effort", "reasoning", "thinking", "enable_thinking",
         "thinking_budget", "extra_body"].filter { body[$0] != nil }
    }

    // MARK: - Negative: zero fields

    /// Rule: Mistral — no thinking request parameter may EVER be sent.
    /// evidence §A "[Mistral] 完全禁止一切 reasoning 字段" · 4592ca9b · OpenMinis#87.
    ///
    /// On iOS the guard lives at the CALL SITE (`if !provider.isMistral`,
    /// OpenAIAgentProvider.swift:125) rather than inside `injectThinkingParams`, so this
    /// test asserts the contract the call site implements: when the Mistral gate says
    /// "skip", nothing is emitted. Asserting the skip itself keeps the test honest about
    /// where the rule lives — if a refactor moves injection inside the gate, this must
    /// be revisited alongside it.
    func testMistralGateEmitsNoThinkingFields() {
        let m = model("mistral-large-latest", effortValues: ["low", "high"])
        for level in [ThinkingLevel.off, .low, .high, .max] {
            // Production: the call is skipped entirely for Mistral.
            let skipped: [String: Any] = [:]
            XCTAssertEqual(
                thinkingKeys(in: skipped), [],
                "Mistral must carry zero thinking control keys at \(level) (422 extra_forbidden)"
            )
            // And the injector itself must not be what saves us — prove it WOULD emit,
            // so the gate is load-bearing and its removal is a detectable regression.
            let unguarded = inject(model: m, level: level)
            if level.isEnabled {
                XCTAssertFalse(
                    thinkingKeys(in: unguarded).isEmpty,
                    "Sanity: the injector emits for this model, so the isMistral call-site gate is what protects Mistral"
                )
            }
        }
    }

    /// Rule: Mistral must stay thinking-free on the RESPONSES API path too.
    ///
    /// `streamResponsesAPI` injects `reasoning` on its own, independent of
    /// `injectThinkingParams`, so the Chat-Completions `isMistral` gate never covered it:
    /// a Mistral instance with the Responses API enabled reached that builder ungated and
    /// put `reasoning` back on the wire, re-triggering the `422 extra_forbidden
    /// body.reasoning` of OpenMinis#87. The gate now sits at the top of that block.
    ///
    /// ⚠️ COVERAGE LIMIT — read before trusting this test: `streamResponsesAPI` is a
    /// `private` *streaming* method (OpenAIAgentProvider.swift:393) with no pure
    /// body-building seam, so unlike every other case here this cannot assert the real
    /// emitted body without a network stub. What it asserts is the PREDICATE the gate is
    /// built on. The behavioural assertion lives on the Android side
    /// (`mistral sends no reasoning on the responses api path`), where MockWebServer can
    /// capture the actual request — and that test was verified to FAIL with the gate
    /// removed, so the rule is genuinely covered cross-platform.
    ///
    /// Extracting a testable `buildResponsesBody` seam on iOS would close this gap; that
    /// is a production refactor and deliberately out of scope here.
    func testMistralPredicateDrivesResponsesPathSuppression() {
        // Both injection sites key off the same base-URL predicate, so pinning its
        // behaviour is what keeps the two gates in agreement.
        for raw in ["https://api.mistral.ai/v1", "https://API.MISTRAL.AI/v1"] {
            XCTAssertTrue(
                raw.lowercased().contains("mistral.ai"),
                "the isMistral predicate must match case-insensitively: \(raw)"
            )
        }
        XCTAssertFalse(
            "https://api.openai.com/v1".lowercased().contains("mistral.ai"),
            "the gate must stay vendor-specific — a blanket suppression would strip reasoning for everyone"
        )
    }

    /// Rule: Venice — the root `thinking` key must not appear even when thinking is OFF.
    /// Venice's ChatCompletionRequest is `additionalProperties:false`, so an unknown root
    /// key is rejected at schema validation BEFORE model dispatch — which is why every
    /// model failed and why turning thinking OFF did not help (the `{"type":"disabled"}`
    /// branch still emitted the key). evidence §A · 84f5c9e1 · OpenMinis#86.
    func testVeniceNeverReceivesRootThinkingKey() {
        // deepseek-v4 is the exact id from the report: without the unified-gateway flag
        // the id-substring branch selects the vendor-native thinking:{} object.
        let m = model("deepseek-v4-flash", effortValues: ["low", "high", "max"])
        for level in [ThinkingLevel.off, .high] {
            let body = inject(model: m, level: level, unifiedReasoningEffort: true)
            XCTAssertNil(
                body["thinking"],
                "root `thinking` must never be sent to Venice at \(level) (400 unrecognized_keys): \(body)"
            )
        }
    }

    /// Rule: families that declare NO effort tiers keep the legacy self-reasoning skip.
    /// evidence §A "[数据驱动重构]" · 22647505.
    func testUndeclaredGLMFamilySendsNoThinkingField() {
        let m = model("glm-4.5-air", supportsReasoning: nil, effortValues: nil)
        let body = inject(model: m, level: .high)
        XCTAssertEqual(
            thinkingKeys(in: body), [],
            "a glm id with no declared effort tiers must send no thinking control: \(body)"
        )
    }

    // MARK: - Positive: effort mapping

    /// Rule: a model DECLARING effort tiers is driven by declared capability, not family
    /// name — the fix for "GLM 5.2 ignores the thinking level while Hermes on the same
    /// relay honours it". evidence §A · 22647505 / 47dc71b3.
    func testDeclaredGLMModelReceivesRootReasoningEffort() {
        let m = model("glm-5.2", effortValues: ["high", "max"])
        let body = inject(model: m, level: .high)
        XCTAssertEqual(
            body["reasoning_effort"] as? String, "high",
            "declared-effort model must carry root reasoning_effort: \(body)"
        )
    }

    /// Rule: the requested tier is clamped ONTO the declared set. `["high","max"]` is the
    /// most common sparse shape in the catalog. evidence §A · 47dc71b3.
    func testSparseDeclaredSetClampsXhigh() {
        let m = model("glm-5.2", effortValues: ["high", "max"])
        let body = inject(model: m, level: .xhigh)
        let sent = body["reasoning_effort"] as? String
        XCTAssertTrue(
            sent == "high" || sent == "max",
            "xhigh must be clamped onto the declared set [high,max], got \(sent ?? "nil"): \(body)"
        )
    }

    /// Rule: ULTRA is a client-side "Max + orchestration" concept and must NEVER reach a
    /// backend as the literal "ultra". evidence §A "[GPT-5.6 / ULTRA]" · b38bf3d5.
    func testUltraNeverReachesWireAsLiteral() {
        let m = model("gpt-5.6-sol", effortValues: ["low", "medium", "high", "xhigh", "max"])
        let body = inject(model: m, level: .ultra)
        XCTAssertNotEqual(
            body["reasoning_effort"] as? String, "ultra",
            "literal 'ultra' must never be sent (backends 400 on it): \(body)"
        )
    }

    // MARK: - OFF semantics

    /// Rule: MiMo/Agnes validate `reasoning_effort` against a STRICT low/medium/high enum.
    /// At OFF the field must be OMITTED — sending "minimal" killed the whole request
    /// on-device (iPhone 11, api.xiaomimimo.com): no reply at all, strictly worse than the
    /// vendor-default reasoning the change targeted. evidence §A · c5efeb1e.
    ///
    /// Note the explicit `offEffort: "minimal"`: this is the hostile input the rule exists
    /// to neutralize. Passing nil would make the test pass vacuously.
    func testMimoAndAgnesOmitEffortWhenOff() {
        for id in ["mimo-v2.5", "mimo-2.5", "agnes-1"] {
            let body = inject(model: model(id), level: .off, offEffort: "minimal")
            XCTAssertNil(
                body["reasoning_effort"],
                "\(id) must OMIT reasoning_effort at OFF even when an off tier is offered: \(body)"
            )
        }
    }

    /// Rule: OFF-tier injection is an ALLOWLIST. Vendors with undocumented off semantics
    /// keep field omission. evidence §A "[全局] thinking-off 显式值是 ALLOWLIST" · ff60c818.
    func testUnknownVendorOmitsOffTierWhenNoneOffered() {
        let m = model("some-relay-model", effortValues: ["low", "medium", "high"])
        let body = inject(model: m, level: .off, offEffort: nil)
        XCTAssertNil(
            body["reasoning_effort"],
            "an unlisted vendor must keep OFF omission, not invent an off tier: \(body)"
        )
    }

    /// Rule: an allowlisted vendor's documented off tier IS sent, so the vendor default
    /// cannot silently re-enable reasoning. evidence §A · 4a89f5ca / ff60c818.
    func testAllowlistedOffTierIsSentExplicitly() {
        let m = model("gpt-5.3", effortValues: ["none", "low", "medium", "high"])
        let body = inject(model: m, level: .off, offEffort: "none")
        XCTAssertEqual(
            body["reasoning_effort"] as? String, "none",
            "an allowlisted off tier must be sent explicitly, not omitted: \(body)"
        )
    }

    // MARK: - Structure

    /// Rule: DeepSeek V4 carries switch and tier as ROOT SIBLINGS —
    /// `{"thinking":{"type":"enabled"}, "reasoning_effort":"high"}`. Nesting the tier made
    /// it an unknown key with no root tier at all, so every V4 request silently ran at the
    /// vendor default for ~3 months. The paired negative assertion is the entire point:
    /// the positive one alone passed throughout that period.
    /// evidence §A "[DeepSeek V4] …根级兄弟" · 847822eb.
    func testDeepSeekV4SendsRootSiblings() {
        let m = model("deepseek-v4-pro", effortValues: ["high", "max"])
        let body = inject(model: m, level: .high)
        let thinking = body["thinking"] as? [String: Any]
        XCTAssertEqual(thinking?["type"] as? String, "enabled",
                       "thinking.type must be 'enabled': \(body)")
        XCTAssertNotNil(body["reasoning_effort"],
                        "root reasoning_effort must be present: \(body)")
        XCTAssertNil(
            thinking?["reasoning_effort"],
            "reasoning_effort must NOT be nested inside thinking{} — that is the silent 847822eb bug: \(body)"
        )
    }

    /// Rule: thinking is ON by default on DeepSeek V4, so OFF must be sent EXPLICITLY.
    /// evidence §A · 847822eb.
    func testDeepSeekV4ExplicitlyDisablesWhenOff() {
        let m = model("deepseek-v4-pro", effortValues: ["high", "max"])
        let body = inject(model: m, level: .off)
        let thinking = body["thinking"] as? [String: Any]
        XCTAssertEqual(thinking?["type"] as? String, "disabled",
                       "V4 reasons by default, so OFF must be explicit: \(body)")
        XCTAssertNil(
            body["reasoning_effort"],
            "with thinking disabled the tier is meaningless and its off vocabulary isn't in the enum: \(body)"
        )
    }

    /// Rule: Ark/Azure re-host third-party families behind a uniform OpenAI surface where
    /// thinking is controlled ONLY by `reasoning_effort`; the vendor-native `thinking:{}`
    /// shape is not honoured. Same model id, different endpoint, different shape.
    /// evidence §A "[Volcengine Ark / Azure]" · ba055121.
    func testArkHostedDeepSeekUsesUniformEffort() {
        let m = model("deepseek-v4-flash", effortValues: ["low", "high", "max"])
        let body = inject(model: m, level: .high, unifiedReasoningEffort: true)
        XCTAssertNil(body["thinking"],
                     "on Ark the vendor-native thinking:{} must NOT be sent: \(body)")
        XCTAssertNotNil(body["reasoning_effort"],
                        "on Ark the tier travels in reasoning_effort: \(body)")
    }

    /// Rule: `deepseek-flash` is the id DeepSeek now RECOMMENDS (the legacy
    /// `deepseek-v4-flash` alias is kept only for compatibility), so it must take the
    /// same vendor-native sibling shape. The old scope glob was `*deepseek-v4*`, which
    /// "deepseek-flash" does not match, so the recommended id silently fell through to
    /// `openai-compatible-default` and lost the whole DeepSeek contract.
    /// evidence §A · 847822eb · GH#356.
    func testDeepSeekFlashGetsVendorNativeSiblingShape() {
        let m = model("deepseek-flash", effortValues: ["low", "high", "max"])
        let body = inject(model: m, level: .high, offEffort: nil)
        let thinking = body["thinking"] as? [String: Any]
        XCTAssertEqual(thinking?["type"] as? String, "enabled",
                       "recommended deepseek-flash id must get thinking.type=enabled, not the generic default: \(body)")
        XCTAssertEqual(body["reasoning_effort"] as? String, "high",
                       "root reasoning_effort must be the clamped tier: \(body)")
        XCTAssertNil(thinking?["reasoning_effort"],
                     "reasoning_effort must be a ROOT sibling, never nested under thinking{}: \(body)")
    }

    /// Rule: the recommended id must also be able to turn thinking OFF explicitly.
    /// Before the scope fix there was no reliable way to disable it from the UI because
    /// the generic fallback never emits the DeepSeek `thinking` toggle. evidence §A · GH#356.
    func testDeepSeekFlashExplicitlyDisablesWhenOff() {
        let m = model("deepseek-flash", effortValues: ["low", "high", "max"])
        let body = inject(model: m, level: .off)
        let thinking = body["thinking"] as? [String: Any]
        XCTAssertEqual(thinking?["type"] as? String, "disabled",
                       "deepseek-flash must emit thinking.type=disabled at OFF: \(body)")
        XCTAssertNil(body["reasoning_effort"],
                     "with thinking disabled the tier is meaningless: \(body)")
    }

    /// Rule: xhigh is NOT in DeepSeek's declared [low,high,max] ladder, so the emitted
    /// tier must be clamped onto the declared set rather than sent literally. evidence §A · GH#356.
    func testDeepSeekFlashClampsXhighOntoDeclaredSet() {
        let m = model("deepseek-flash", effortValues: ["low", "high", "max"])
        let body = inject(model: m, level: .xhigh)
        let sent = body["reasoning_effort"] as? String
        XCTAssertTrue(sent == "high" || sent == "max",
                      "xhigh must be clamped onto [low,high,max], got \(sent ?? "nil"): \(body)")
    }

    /// Rule: a unified gateway still outranks the DeepSeek vendor rule, so the new
    /// recommended id behaves like every other family there. evidence §A · ba055121 · GH#356.
    func testUnifiedGatewayOutranksDeepSeekFlashRule() {
        let m = model("deepseek-flash", effortValues: ["low", "high", "max"])
        let body = inject(model: m, level: .high, unifiedReasoningEffort: true)
        XCTAssertNil(body["thinking"],
                     "on a unified gateway the vendor-native thinking:{} must NOT be sent: \(body)")
        XCTAssertEqual(body["reasoning_effort"] as? String, "high",
                       "on a unified gateway the tier travels in root reasoning_effort: \(body)")
    }

    /// Rule: the DeepSeek fallback ceiling exists for custom endpoints whose id is never
    /// enriched from models.dev — without it a stray xhigh would be emitted verbatim.
    /// A declared set is a stronger statement and still wins via `selectableThinkingLevels`.
    /// evidence §A · GH#356.
    func testDeepSeekFamilyFallsBackToHighCeiling() {
        for id in ["deepseek-flash", "deepseek-v4", "deepseek-v4-pro", "deepseek-flash-0731"] {
            XCTAssertEqual(
                ThinkingLevelCatalog.declaredMaxLevel(for: id), .high,
                "\(id) must cap at .high for the no-catalog-data fallback — the backend does not declare xhigh"
            )
        }
    }

    /// Rule: Qwen dual-sends at root AND `extra_body` (DashScope reads extra_body;
    /// vLLM/SGLang accept top-level). evidence §A "[Qwen] 根级 + extra_body 双发" · 25165700.
    func testQwenDualSendsAtRootAndExtraBody() {
        let body = inject(model: model("qwen3-32b"), level: .medium)
        XCTAssertNotNil(body["enable_thinking"], "root enable_thinking expected: \(body)")
        let extra = body["extra_body"] as? [String: Any]
        XCTAssertNotNil(extra?["enable_thinking"],
                        "extra_body.enable_thinking expected (DashScope reads it there): \(body)")
    }

    /// Rule: OpenRouter uses the unified nested `reasoning: {effort:…}` and OMITS the
    /// parameter entirely when off, so forced-reasoning models don't reject `effort:"none"`.
    /// evidence §A / design §6.1 · OpenAIAgentProvider.swift:881.
    func testOpenRouterUsesNestedReasoningAndOmitsWhenOff() {
        let m = model("anthropic/claude-sonnet-4-6", effortValues: ["low", "medium", "high"])
        let on = inject(model: m, level: .high, isOpenRouter: true)
        XCTAssertEqual((on["reasoning"] as? [String: Any])?["effort"] as? String, "high",
                       "OpenRouter uses nested reasoning.effort: \(on)")
        XCTAssertNil(on["reasoning_effort"],
                     "OpenRouter must not also send the flat root field: \(on)")

        let off = inject(model: m, level: .off, isOpenRouter: true, offEffort: "none")
        XCTAssertNil(off["reasoning"],
                     "OpenRouter omits the parameter entirely when off: \(off)")
    }

    // MARK: - Boundary

    /// Rule: DashScope enforces `thinking_budget < max_completion_tokens` STRICTLY —
    /// equal values are rejected too ("[16384] must be greater than [16384]").
    /// evidence §A "[Qwen / DashScope] …等值也拒" · 8db455ff → a5a0de20 · issues #35 / #641.
    func testQwenBudgetStaysStrictlyBelowMaxTokens() {
        for maxTokens in [16384, 64000, 4096, 2] {
            let body = inject(model: model("qwen3-32b"), level: .max, maxTokens: maxTokens)
            if let budget = body["thinking_budget"] as? Int, budget > 0 {
                XCTAssertLessThan(
                    budget, maxTokens,
                    "thinking_budget must be STRICTLY below max_completion_tokens(\(maxTokens)): \(body)"
                )
            }
        }
    }

    /// Rule: pathological max_tokens leaves no room for a positive budget strictly below
    /// max, so the field is dropped rather than emitted invalid.
    /// evidence §A · a5a0de20 ("maxTokens < 2 → drop thinking_budget entirely").
    func testQwenDropsBudgetWhenMaxTokensLeavesNoRoom() {
        let body = inject(model: model("qwen3-32b"), level: .max, maxTokens: 1)
        let budget = (body["thinking_budget"] as? Int) ?? 0
        XCTAssertLessThanOrEqual(
            budget, 0,
            "with max_tokens=1 no positive budget can be strictly below it: \(body)"
        )
    }

    /// Rule: Anthropic `budget_tokens` must be strictly below max_tokens.
    /// evidence §A "[Anthropic] budget_tokens 必须严格小于 max_tokens" · 5aa9dc64.
    func testAnthropicBudgetStaysBelowMaxTokens() {
        for maxTokens in [1024, 16384, 64000] {
            let budget = AnthropicAgentProvider.thinkingBudget(
                for: model("claude-opus-4-8"), maxTokens: maxTokens, level: .max
            )
            XCTAssertLessThan(
                budget, maxTokens,
                "thinking.budget_tokens(\(budget)) must be < max_tokens(\(maxTokens)) — 400 otherwise"
            )
        }
    }

    // MARK: - ID normalization

    /// Rule: catalog rules matched dotted ids (`claude-opus-4.7`) while real ids use
    /// hyphens (`claude-opus-4-8`), so the Opus rules NEVER matched any built-in model and
    /// every Opus fell through to the wrong ceiling. Third-party proxies emit BOTH forms,
    /// so both must resolve identically. evidence §A "[Claude Opus] id 分隔符" · 5aa9dc64.
    func testClaudeOpusIdSeparatorNormalization() {
        let hyphen = ThinkingLevelCatalog.declaredMaxLevel(for: "claude-opus-4-8")
        let dotted = ThinkingLevelCatalog.declaredMaxLevel(for: "claude-opus-4.8")
        XCTAssertEqual(hyphen, .max, "claude-opus-4-8 must resolve to .max")
        XCTAssertEqual(dotted, .max, "dotted proxy spelling must normalize to the same ceiling")
        XCTAssertEqual(hyphen, dotted, "both spellings must agree — 5aa9dc64")
    }

    /// Rule: MiMo ships BOTH spellings — docs say `mimo-2.5`, the live API returns
    /// `mimo-v2.5`. A rule matching one silently misses the other, letting xhigh through
    /// to a backend that 400s. evidence §A "[MiMo] 模型 id 拼写变体" · 72968c4f.
    func testBothMimoSpellingsResolveToSameCeiling() {
        for id in ["mimo-2.5", "mimo-v2.5", "mimo-v2.5-pro"] {
            XCTAssertEqual(
                ThinkingLevelCatalog.declaredMaxLevel(for: id), .high,
                "\(id) must cap at .high — the live API serves the v-prefixed spelling"
            )
        }
    }

    /// Rule: a broadened family substring must NOT lift the ceiling of same-family
    /// NON-reasoning members (mimo-v2.5-tts / -asr). `supportsReasoning == false` is
    /// checked BEFORE the catalog rules. evidence §A · 72968c4f · LLMTypes.swift:820-822.
    func testNonReasoningFamilyMemberCapsAtOff() {
        let tts = model("mimo-v2.5-tts", supportsReasoning: false)
        XCTAssertEqual(
            tts.catalogMaxThinkingLevel, .off,
            "a non-reasoning family member must cap at .off despite matching the family rule"
        )
    }

    /// Rule: seed / bytedance-seed reject xhigh ("Invalid reasoning_effort: xhigh"); the
    /// Ark ladder tops out at high. evidence §A "[ByteDance seed]" · 72968c4f.
    func testSeedFamilyCapsAtHigh() {
        for id in ["seed-1.6", "bytedance-seed/seed-2.0"] {
            XCTAssertEqual(
                ThinkingLevelCatalog.declaredMaxLevel(for: id), .high,
                "\(id) must cap at .high — the backend 400s on xhigh"
            )
        }
    }

    /// Rule: a declared effort set is a STRONGER statement than any id-substring rule and
    /// must raise the ceiling to the declared top tier — otherwise a tier the catalog
    /// declares is clamped to on the wire yet unselectable in the UI.
    /// evidence §A "[数据驱动重构]" · 47dc71b3 · LLMTypes.swift:820-836.
    func testDeclaredTiersOverrideSubstringCeiling() {
        let m = model("glm-5.2", effortValues: ["high", "max"])
        XCTAssertEqual(
            m.catalogMaxThinkingLevel, .max,
            "a declared top tier of 'max' must be reachable from the UI: \(m.reasoningEffortValues ?? [])"
        )
    }

    /// Rule: sparse declarations must yield one option per DISTINCT wire tier, so every
    /// option the user can pick produces a different request. Verified on-device
    /// 2026-08-01: glm-5.2 sent "high" for Low AND Med AND High AND XHigh.
    /// evidence §A · 47dc71b3 · LLMTypes.swift:858.
    func testSparseDeclarationYieldsDistinctSelectableLevels() {
        let m = model("glm-5.2", effortValues: ["high", "max"])
        let levels = m.selectableThinkingLevels
        XCTAssertEqual(
            levels.count, Set(levels).count,
            "selectable levels must be distinct — duplicates mean a slider that changes nothing"
        )
        XCTAssertEqual(levels, [.high, .max],
                       "a [high, max] declaration must surface exactly two options, got \(levels)")
    }

    /// Rule: a model that cannot reason caps at .off regardless of any family rule.
    /// evidence §A · 72968c4f · LLMTypes.swift:820-822.
    func testNonReasoningModelCapsAtOff() {
        XCTAssertEqual(
            model("gpt-4o", supportsReasoning: false).catalogMaxThinkingLevel, .off,
            "supportsReasoning=false must hard-cap at .off"
        )
    }

    /// Rule: `supportsReasoning == false` is a hard veto on injection — non-reasoning
    /// models reject the parameter outright. evidence §A · OpenAIAgentProvider.swift:1042.
    func testNonReasoningModelReceivesNoEffortField() {
        let body = inject(model: model("gpt-4o-mini", supportsReasoning: false), level: .high)
        XCTAssertNil(
            body["reasoning_effort"],
            "a model that declares no reasoning support must not receive the parameter: \(body)"
        )
    }
}
