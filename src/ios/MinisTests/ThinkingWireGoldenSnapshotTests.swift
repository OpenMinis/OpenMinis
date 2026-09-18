import XCTest
@testable import Minis

/// GOLDEN SNAPSHOT of `OpenAIAgentProvider.injectThinkingParams`.
///
/// WHY THIS EXISTS, and why it is different from ThinkingRulesRegressionTests:
/// the regression suite asserts the *rules we knew to look for* — it encodes intent,
/// one hand-written assertion per catalogued rule. This file asserts something weaker
/// but much broader: that for a matrix of (model × level × endpoint-flag) combinations,
/// the emitted body is **byte-for-byte what it is today**, whatever that happens to be.
///
/// That distinction matters for the Thinking Rules refactor. The acceptance criterion is
/// "behaviour is byte-for-byte unchanged", but the regression suite only covers the
/// vendors it was written for (Mistral / Venice / DeepSeek / Qwen / MiMo / Ark / GLM) —
/// it says nothing about the OpenAI-native o/gpt-5 branch, the OpenRouter nested shape,
/// seed, xAI, or nous. Passing it proves "the parts we tested did not change", not
/// "nothing changed". These snapshots close that gap by pinning the ACTUAL current
/// output of every branch of the if-return chain, including branches nobody wrote a
/// named rule for.
///
/// HOW TO USE DURING THE REFACTOR
///   1. This file is committed BEFORE the refactor, generated against the OLD code.
///   2. After the refactor, it must still pass unchanged. A diff here is a behaviour
///      change — either an unintended regression, or an intended fix that must be
///      called out explicitly in the commit message and updated deliberately.
///   3. Do NOT regenerate expectations to make a red test green without first
///      explaining, in words, why the wire format legitimately changed.
///
/// The snapshot is a canonical JSON rendering (sorted keys, stable number formatting)
/// so a dictionary-ordering change can never masquerade as a behaviour change.
///
/// READING THE BASELINE — one row looks alarming and is not: `mimo/xhigh` and
/// `seed/xhigh` record `reasoning_effort:"xhigh"`, even though both families 400 on
/// exactly that value (72968c4f). That is correct: the family ceiling is applied
/// UPSTREAM of this function, at AgentProvider.swift:203
/// (`min(thinkingLevel, model.catalogMaxThinkingLevel)`), so in production
/// injectThinkingParams is never called with xhigh for those ids. These rows pin the
/// behaviour of this function in isolation; the ceiling itself is covered by
/// ThinkingRulesRegressionTests' catalog assertions. Do not "fix" the snapshot by
/// clamping here — that would move the ceiling into a second place and let the two
/// drift apart.
final class ThinkingWireGoldenSnapshotTests: XCTestCase {

    // MARK: - Canonical rendering

    /// Deterministic textual form of a request body: keys sorted at every level, so the
    /// output depends only on content, never on Dictionary iteration order.
    private func canonical(_ value: Any) -> String {
        switch value {
        case let dict as [String: Any]:
            let inner = dict.keys.sorted().map { "\($0):\(canonical(dict[$0]!))" }.joined(separator: ",")
            return "{\(inner)}"
        case let array as [Any]:
            return "[\(array.map { canonical($0) }.joined(separator: ","))]"
        case let n as NSNumber:
            // Bools and ints must not collapse into each other ("true" vs "1").
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue ? "true" : "false" }
            return "\(n)"
        case let s as String:
            return "\"\(s)\""
        case is NSNull:
            return "null"
        default:
            return "\(value)"
        }
    }

    /// `authoritative` mirrors `LLMModel.effortDeclarationIsAuthoritative`, which is a
    /// post-init property rather than an init parameter — hence the two-step build.
    ///
    /// It defaults to nil so every pre-existing row keeps the exact model it had before
    /// this dimension was added: the whole point of a golden file is that adding coverage
    /// must not perturb the baseline.
    private func model(
        _ id: String,
        supportsReasoning: Bool? = true,
        effortValues: [String]? = nil,
        declaresNoEffortTiers: Bool? = nil,
        authoritative: Bool? = nil
    ) -> LLMModel {
        var m = LLMModel(
            id: id,
            displayName: id,
            provider: "Golden",
            supportsReasoning: supportsReasoning,
            reasoningEffortValues: effortValues,
            declaresNoEffortTiers: declaresNoEffortTiers
        )
        m.effortDeclarationIsAuthoritative = authoritative
        return m
    }

    private func emit(
        model m: LLMModel,
        level: ThinkingLevel,
        isOpenRouter: Bool,
        maxTokens: Int,
        offEffort: String?,
        unified: Bool,
        isXAI: Bool = false
    ) -> String {
        var body: [String: Any] = [:]
        OpenAIAgentProvider.injectThinkingParams(
            into: &body,
            model: m,
            level: level,
            isOpenRouter: isOpenRouter,
            maxTokens: maxTokens,
            offEffort: offEffort,
            unifiedReasoningEffort: unified,
            isXAI: isXAI
        )
        return canonical(body)
    }

    // MARK: - Matrix definition

    /// One representative model per branch of the injectThinkingParams if-return chain,
    /// including the branches the named regression suite does not cover.
    private struct Case {
        let label: String
        let model: LLMModel
        let isOpenRouter: Bool
        let unified: Bool
        let offEffort: String?
        let maxTokens: Int
        var isXAI: Bool = false
    }

    private func matrix() -> [Case] {
        [
            // OpenAI native o/gpt-5 branch — NOT covered by the named regression suite.
            Case(label: "openai-gpt5", model: model("gpt-5.3", effortValues: ["none", "low", "medium", "high", "xhigh"]),
                 isOpenRouter: false, unified: false, offEffort: "none", maxTokens: 8192),
            Case(label: "openai-o3", model: model("o3-mini"),
                 isOpenRouter: false, unified: false, offEffort: "none", maxTokens: 8192),
            Case(label: "openai-gpt4o-nonreasoning", model: model("gpt-4o", supportsReasoning: false),
                 isOpenRouter: false, unified: false, offEffort: "none", maxTokens: 8192),
            // OpenRouter nested shape — NOT covered by the named suite.
            Case(label: "openrouter", model: model("anthropic/claude-sonnet-4-6", effortValues: ["low", "medium", "high"]),
                 isOpenRouter: true, unified: false, offEffort: "none", maxTokens: 8192),
            // Qwen dual-send + strict budget inequality.
            Case(label: "qwen", model: model("qwen3-32b"),
                 isOpenRouter: false, unified: false, offEffort: nil, maxTokens: 16384),
            Case(label: "qwen-tiny-max", model: model("qwen3-32b"),
                 isOpenRouter: false, unified: false, offEffort: nil, maxTokens: 1),
            // DeepSeek V4 vendor-native sibling shape, and the same id on a unified gateway.
            Case(label: "deepseek-v4", model: model("deepseek-v4-pro", effortValues: ["high", "max"]),
                 isOpenRouter: false, unified: false, offEffort: nil, maxTokens: 8192),
            Case(label: "deepseek-v4-unified", model: model("deepseek-v4-pro", effortValues: ["high", "max"]),
                 isOpenRouter: false, unified: true, offEffort: "minimal", maxTokens: 8192),
            // [GH#356] The id DeepSeek now recommends (`deepseek-flash`) is a DIFFERENT
            // string from the legacy `deepseek-v4-flash`, so it must resolve to the same
            // vendor-native sibling shape rather than fall through to the generic default.
            Case(label: "deepseek-flash", model: model("deepseek-flash", effortValues: ["low", "high", "max"]),
                 isOpenRouter: false, unified: false, offEffort: nil, maxTokens: 8192),
            // Cross-product control: the unified-gateway rule still outranks the new
            // vendor-native pattern, so a DeepSeek id on Ark/Azure keeps the uniform shape.
            Case(label: "deepseek-flash-unified", model: model("deepseek-flash", effortValues: ["low", "high", "max"]),
                 isOpenRouter: false, unified: true, offEffort: "minimal", maxTokens: 8192),
            // Declared-capability path (GLM) and the legacy undeclared skip.
            Case(label: "glm-declared", model: model("glm-5.2", effortValues: ["high", "max"]),
                 isOpenRouter: false, unified: false, offEffort: nil, maxTokens: 8192),
            Case(label: "glm-undeclared", model: model("glm-4.5-air", supportsReasoning: nil),
                 isOpenRouter: false, unified: false, offEffort: nil, maxTokens: 8192),
            // Strict-enum families: OFF must omit.
            Case(label: "mimo", model: model("mimo-v2.5"),
                 isOpenRouter: false, unified: false, offEffort: "minimal", maxTokens: 8192),
            Case(label: "agnes", model: model("agnes-1"),
                 isOpenRouter: false, unified: false, offEffort: "minimal", maxTokens: 8192),
            // seed family — NOT covered by the named suite at the wire level.
            Case(label: "seed", model: model("seed-1.6"),
                 isOpenRouter: false, unified: false, offEffort: "minimal", maxTokens: 8192),
            // Generic fallback with no declaration at all.
            Case(label: "generic-unknown", model: model("some-relay-model"),
                 isOpenRouter: false, unified: false, offEffort: nil, maxTokens: 8192),
            // Sparse declared set (the most common catalog shape).
            Case(label: "sparse-high-max", model: model("vendor-x", effortValues: ["high", "max"]),
                 isOpenRouter: false, unified: false, offEffort: nil, maxTokens: 8192),
            // [OpenMinis#163] xAI endpoint + catalog declares no effort tiers
            // ("reasoning": true, "reasoning_options": []). grok-build-0.1 400s
            // on `reasoning_effort`, so no row here may carry that key — at any
            // level, including OFF.
            Case(label: "xai-grok-no-effort-tiers",
                 model: model("grok-build-0.1", declaresNoEffortTiers: true),
                 isOpenRouter: false, unified: false, offEffort: "none", maxTokens: 8192,
                 isXAI: true),
            // Control 1 — SAME model/flag, but NOT an xAI endpoint. The skip is
            // deliberately vendor-scoped, so a relay serving the same catalog
            // shape must keep its current wire format. This row is what fails if
            // the fix is ever widened to all vendors without saying so.
            Case(label: "relay-no-effort-tiers-not-xai",
                 model: model("grok-build-0.1", declaresNoEffortTiers: true),
                 isOpenRouter: false, unified: false, offEffort: "none", maxTokens: 8192),
            // Control 2 — xAI endpoint, but catalog silent (flag nil). Unknown
            // stays permissive; still emits reasoning_effort.
            Case(label: "xai-catalog-silent",
                 model: model("grok-build-0.1"),
                 isOpenRouter: false, unified: false, offEffort: "none", maxTokens: 8192,
                 isXAI: true),
            // Control 3 — xAI + no tiers, but on a unified gateway, which
            // normalizes the field and owns its own model list.
            Case(label: "xai-no-effort-tiers-on-unified",
                 model: model("grok-build-0.1", declaresNoEffortTiers: true),
                 isOpenRouter: false, unified: true, offEffort: "minimal", maxTokens: 8192,
                 isXAI: true),

            // ---- CROSS-PRODUCT ROWS ----
            // Every case above varies ONE dimension, and that blind spot let a real
            // ordering regression through: hoisting the unified-gateway rule above the
            // qwen and OpenAI-native patterns changed the wire shape for models that
            // match a vendor pattern AND sit on a unified gateway. Single-dimension rows
            // cannot see it, because neither dimension alone is wrong. These rows pin the
            // interaction, which is where rule-ordering bugs actually live.
            Case(label: "qwen-on-unified", model: model("qwen3-32b"),
                 isOpenRouter: false, unified: true, offEffort: "minimal", maxTokens: 8192),
            Case(label: "gpt5-on-unified", model: model("gpt-5.3", effortValues: ["low", "high"]),
                 isOpenRouter: false, unified: true, offEffort: "minimal", maxTokens: 8192),
            Case(label: "mimo-on-unified", model: model("mimo-v2.5"),
                 isOpenRouter: false, unified: true, offEffort: "minimal", maxTokens: 8192),
            Case(label: "qwen-on-openrouter", model: model("qwen3-32b"),
                 isOpenRouter: true, unified: false, offEffort: nil, maxTokens: 8192),
            Case(label: "deepseek-v4-on-openrouter", model: model("deepseek-v4-pro", effortValues: ["high", "max"]),
                 isOpenRouter: true, unified: false, offEffort: nil, maxTokens: 8192),

            // ---- OFF-TIER DECLARATION ROWS [T-vision-thinking-off-400] ----
            // The guard that withholds an off tier keys on whether the model DECLARES
            // one, not on its vendor family. Both directions need pinning, because the
            // first version of that fix keyed on the family name and silently removed
            // the user's ability to turn thinking off on every glm/kimi/minimax/deepseek
            // id — including the 161 catalog entries that publish an off tier explicitly.
            // A family-keyed rule passes the first row here and fails the rest.
            //
            // Suppressed: declares ON tiers only, so an off value was never legal. This
            // is the reported MiniMax M3 relay shape (400 invalid thinking.type "none").
            Case(label: "minimax-on-tiers-only",
                 model: model("minimax-m3", effortValues: ["low", "medium", "high"]),
                 isOpenRouter: false, unified: false, offEffort: "none", maxTokens: 8192),
            // Honoured: same family, but the vendor documents an off tier (greenpt's
            // glm-5.2 / minimax-m2.5 publish ["none","minimal","low","medium","high"]).
            // Turning thinking off must still reach the wire.
            Case(label: "glm-declares-off-tier",
                 model: model("glm-5.2", effortValues: ["none", "minimal", "low", "medium", "high"]),
                 isOpenRouter: false, unified: false, offEffort: "none", maxTokens: 8192),
            Case(label: "kimi-declares-off-tier",
                 model: model("moonshotai/Kimi-K3", effortValues: ["none", "low", "high", "max"]),
                 isOpenRouter: false, unified: false, offEffort: "none", maxTokens: 8192),
            Case(label: "minimax-declares-off-tier",
                 model: model("minimax-m2.5", effortValues: ["none", "minimal", "low", "medium", "high"]),
                 isOpenRouter: false, unified: false, offEffort: "none", maxTokens: 8192),
            // Non-family control: the rule is vendor-neutral, so an unrelated id that
            // declares ON tiers only is suppressed on exactly the same grounds.
            Case(label: "vendorx-on-tiers-only",
                 model: model("vendor-x-reasoner", effortValues: ["low", "high"]),
                 isOpenRouter: false, unified: false, offEffort: "none", maxTokens: 8192),

            // ---- AUTHORITATIVE-DECLARATION ROWS [T-thinking-off-custom-provider] ----
            // Closes a real hole: the guard added by 621b0f27 is gated on
            // `effortDeclarationIsAuthoritative`, and NO row in this file had ever set
            // that field — it is a post-init property the `model()` factory did not
            // touch, so the whole matrix ran with it nil. The guard therefore never
            // executed once, and deleting it outright left this file, the regression
            // suite and the persistence suite all green. 621b0f27 shipped with no test
            // of its own (4 files changed, none a test), so until now the only thing
            // pinning the fix was the absence of anyone editing it.
            //
            // The rows below had to be chosen carefully, because the pre-existing
            // membership check ("emit the off value only if the model declares it",
            // ThinkingRuleResolver.swift:468) ALREADY suppresses the off tier for the
            // obvious shapes. That is exactly what the `minimax-on-tiers-only` rows
            // above pin — they look like they cover the authoritative guard and do not.
            // To separate the two, the off value must be one the model DOES declare:
            //   • membership check alone → "low" is in the set → emits it
            //   • authoritative guard    → the set holds no off-vocabulary member
            //                              (none/off/minimal/disabled) → suppresses it
            // So the pair below differs ONLY in `authoritative`, and the OFF rows must
            // differ in output. If a future edit makes them agree, the guard has stopped
            // doing anything and one of the two mechanisms is dead code.
            Case(label: "authoritative-on-tiers-only-offvalue-declared",
                 model: model("minimax-m3", effortValues: ["low", "medium", "high"],
                              authoritative: true),
                 isOpenRouter: false, unified: false, offEffort: "low", maxTokens: 8192),
            // Control: byte-identical model and off value, catalog NOT authoritative —
            // the cross-provider fallback scan every user-defined relay resolves
            // through. A guess about somebody else's endpoint must never override the
            // user's explicit "thinking off", so the value still reaches the wire.
            Case(label: "nonauthoritative-on-tiers-only-offvalue-declared",
                 model: model("minimax-m3", effortValues: ["low", "medium", "high"],
                              authoritative: false),
                 isOpenRouter: false, unified: false, offEffort: "low", maxTokens: 8192),
            // Authoritative AND the vendor documents an off tier → honoured. Guards the
            // opposite direction: the first version of this fix keyed on the family name
            // and silently removed thinking-off from every glm/kimi/minimax id, including
            // the 161 catalog entries that publish an off value explicitly.
            Case(label: "authoritative-declares-off-tier",
                 model: model("glm-5.2", effortValues: ["none", "minimal", "low", "high"],
                              authoritative: true),
                 isOpenRouter: false, unified: false, offEffort: "none", maxTokens: 8192),
            // Authoritative but the catalog is silent about tiers. The guard requires a
            // non-empty declared set, so it must NOT fire — "unknown" stays permissive,
            // the same permissiveness `declaresNoEffortTiers` is careful to preserve.
            Case(label: "authoritative-no-declared-tiers",
                 model: model("relay-unknown-model", authoritative: true),
                 isOpenRouter: false, unified: false, offEffort: "none", maxTokens: 8192),
        ]
    }

    private let levels: [ThinkingLevel] = [.off, .low, .medium, .high, .xhigh, .max, .ultra]

    // MARK: - The snapshot

    /// Full matrix rendered as one stable, human-readable block. Kept as a single
    /// assertion so a diff shows every changed line at once rather than failing on the
    /// first mismatch and hiding the rest.
    func testGoldenSnapshotOfEveryBranch() {
        var lines: [String] = []
        for c in matrix() {
            for level in levels {
                let out = emit(
                    model: c.model, level: level, isOpenRouter: c.isOpenRouter,
                    maxTokens: c.maxTokens, offEffort: c.offEffort, unified: c.unified,
                    isXAI: c.isXAI
                )
                lines.append("\(c.label)/\(level.rawValue) -> \(out)")
            }
        }
        let actual = lines.joined(separator: "\n")

        XCTAssertEqual(
            actual, Self.expectedSnapshot,
            """
            THINKING WIRE FORMAT CHANGED.

            This is the byte-for-byte oracle for the Thinking Rules refactor. If you are
            mid-refactor and see this fail, the new rule engine emits a different request
            than the old if-return chain for at least one (model, level) pair.

            Do not "fix" this by pasting the new output in. Diff the two blocks, identify
            which branch moved, and either restore parity or justify the change explicitly.
            """
        )
    }

    /// Generated from the PRE-refactor implementation. See the type doc for the rules
    /// governing when this may be updated.
    static let expectedSnapshot = #"""
openai-gpt5/off -> {reasoning_effort:"none"}
openai-gpt5/low -> {reasoning_effort:"low"}
openai-gpt5/medium -> {reasoning_effort:"medium"}
openai-gpt5/high -> {reasoning_effort:"high"}
openai-gpt5/xhigh -> {reasoning_effort:"xhigh"}
openai-gpt5/max -> {reasoning_effort:"max"}
openai-gpt5/ultra -> {reasoning_effort:"max"}
openai-o3/off -> {reasoning_effort:"none"}
openai-o3/low -> {reasoning_effort:"low"}
openai-o3/medium -> {reasoning_effort:"medium"}
openai-o3/high -> {reasoning_effort:"high"}
openai-o3/xhigh -> {reasoning_effort:"xhigh"}
openai-o3/max -> {reasoning_effort:"max"}
openai-o3/ultra -> {reasoning_effort:"max"}
openai-gpt4o-nonreasoning/off -> {}
openai-gpt4o-nonreasoning/low -> {}
openai-gpt4o-nonreasoning/medium -> {}
openai-gpt4o-nonreasoning/high -> {}
openai-gpt4o-nonreasoning/xhigh -> {}
openai-gpt4o-nonreasoning/max -> {}
openai-gpt4o-nonreasoning/ultra -> {}
openrouter/off -> {}
openrouter/low -> {reasoning:{effort:"low"}}
openrouter/medium -> {reasoning:{effort:"medium"}}
openrouter/high -> {reasoning:{effort:"high"}}
openrouter/xhigh -> {reasoning:{effort:"xhigh"}}
openrouter/max -> {reasoning:{effort:"max"}}
openrouter/ultra -> {reasoning:{effort:"max"}}
qwen/off -> {enable_thinking:false,extra_body:{enable_thinking:false,thinking_budget:null}}
qwen/low -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:4096},thinking_budget:4096}
qwen/medium -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:14336},thinking_budget:14336}
qwen/high -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:14336},thinking_budget:14336}
qwen/xhigh -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:14336},thinking_budget:14336}
qwen/max -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:14336},thinking_budget:14336}
qwen/ultra -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:14336},thinking_budget:14336}
qwen-tiny-max/off -> {enable_thinking:false,extra_body:{enable_thinking:false,thinking_budget:null}}
qwen-tiny-max/low -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:null}}
qwen-tiny-max/medium -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:null}}
qwen-tiny-max/high -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:null}}
qwen-tiny-max/xhigh -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:null}}
qwen-tiny-max/max -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:null}}
qwen-tiny-max/ultra -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:null}}
deepseek-v4/off -> {thinking:{type:"disabled"}}
deepseek-v4/low -> {reasoning_effort:"high",thinking:{type:"enabled"}}
deepseek-v4/medium -> {reasoning_effort:"high",thinking:{type:"enabled"}}
deepseek-v4/high -> {reasoning_effort:"high",thinking:{type:"enabled"}}
deepseek-v4/xhigh -> {reasoning_effort:"high",thinking:{type:"enabled"}}
deepseek-v4/max -> {reasoning_effort:"max",thinking:{type:"enabled"}}
deepseek-v4/ultra -> {reasoning_effort:"max",thinking:{type:"enabled"}}
deepseek-v4-unified/off -> {}
deepseek-v4-unified/low -> {reasoning_effort:"high"}
deepseek-v4-unified/medium -> {reasoning_effort:"high"}
deepseek-v4-unified/high -> {reasoning_effort:"high"}
deepseek-v4-unified/xhigh -> {reasoning_effort:"high"}
deepseek-v4-unified/max -> {reasoning_effort:"max"}
deepseek-v4-unified/ultra -> {reasoning_effort:"max"}
deepseek-flash/off -> {thinking:{type:"disabled"}}
deepseek-flash/low -> {reasoning_effort:"low",thinking:{type:"enabled"}}
deepseek-flash/medium -> {reasoning_effort:"low",thinking:{type:"enabled"}}
deepseek-flash/high -> {reasoning_effort:"high",thinking:{type:"enabled"}}
deepseek-flash/xhigh -> {reasoning_effort:"high",thinking:{type:"enabled"}}
deepseek-flash/max -> {reasoning_effort:"max",thinking:{type:"enabled"}}
deepseek-flash/ultra -> {reasoning_effort:"max",thinking:{type:"enabled"}}
deepseek-flash-unified/off -> {}
deepseek-flash-unified/low -> {reasoning_effort:"low"}
deepseek-flash-unified/medium -> {reasoning_effort:"low"}
deepseek-flash-unified/high -> {reasoning_effort:"high"}
deepseek-flash-unified/xhigh -> {reasoning_effort:"high"}
deepseek-flash-unified/max -> {reasoning_effort:"max"}
deepseek-flash-unified/ultra -> {reasoning_effort:"max"}
glm-declared/off -> {}
glm-declared/low -> {reasoning_effort:"high"}
glm-declared/medium -> {reasoning_effort:"high"}
glm-declared/high -> {reasoning_effort:"high"}
glm-declared/xhigh -> {reasoning_effort:"high"}
glm-declared/max -> {reasoning_effort:"max"}
glm-declared/ultra -> {reasoning_effort:"max"}
glm-undeclared/off -> {}
glm-undeclared/low -> {}
glm-undeclared/medium -> {}
glm-undeclared/high -> {}
glm-undeclared/xhigh -> {}
glm-undeclared/max -> {}
glm-undeclared/ultra -> {}
mimo/off -> {}
mimo/low -> {reasoning_effort:"low"}
mimo/medium -> {reasoning_effort:"medium"}
mimo/high -> {reasoning_effort:"high"}
mimo/xhigh -> {reasoning_effort:"xhigh"}
mimo/max -> {reasoning_effort:"max"}
mimo/ultra -> {reasoning_effort:"max"}
agnes/off -> {}
agnes/low -> {reasoning_effort:"low"}
agnes/medium -> {reasoning_effort:"medium"}
agnes/high -> {reasoning_effort:"high"}
agnes/xhigh -> {reasoning_effort:"xhigh"}
agnes/max -> {reasoning_effort:"max"}
agnes/ultra -> {reasoning_effort:"max"}
seed/off -> {reasoning_effort:"minimal"}
seed/low -> {reasoning_effort:"low"}
seed/medium -> {reasoning_effort:"medium"}
seed/high -> {reasoning_effort:"high"}
seed/xhigh -> {reasoning_effort:"xhigh"}
seed/max -> {reasoning_effort:"max"}
seed/ultra -> {reasoning_effort:"max"}
generic-unknown/off -> {}
generic-unknown/low -> {reasoning_effort:"low"}
generic-unknown/medium -> {reasoning_effort:"medium"}
generic-unknown/high -> {reasoning_effort:"high"}
generic-unknown/xhigh -> {reasoning_effort:"xhigh"}
generic-unknown/max -> {reasoning_effort:"max"}
generic-unknown/ultra -> {reasoning_effort:"max"}
sparse-high-max/off -> {}
sparse-high-max/low -> {reasoning_effort:"high"}
sparse-high-max/medium -> {reasoning_effort:"high"}
sparse-high-max/high -> {reasoning_effort:"high"}
sparse-high-max/xhigh -> {reasoning_effort:"high"}
sparse-high-max/max -> {reasoning_effort:"max"}
sparse-high-max/ultra -> {reasoning_effort:"max"}
xai-grok-no-effort-tiers/off -> {}
xai-grok-no-effort-tiers/low -> {}
xai-grok-no-effort-tiers/medium -> {}
xai-grok-no-effort-tiers/high -> {}
xai-grok-no-effort-tiers/xhigh -> {}
xai-grok-no-effort-tiers/max -> {}
xai-grok-no-effort-tiers/ultra -> {}
relay-no-effort-tiers-not-xai/off -> {reasoning_effort:"none"}
relay-no-effort-tiers-not-xai/low -> {reasoning_effort:"low"}
relay-no-effort-tiers-not-xai/medium -> {reasoning_effort:"medium"}
relay-no-effort-tiers-not-xai/high -> {reasoning_effort:"high"}
relay-no-effort-tiers-not-xai/xhigh -> {reasoning_effort:"xhigh"}
relay-no-effort-tiers-not-xai/max -> {reasoning_effort:"max"}
relay-no-effort-tiers-not-xai/ultra -> {reasoning_effort:"max"}
xai-catalog-silent/off -> {reasoning_effort:"none"}
xai-catalog-silent/low -> {reasoning_effort:"low"}
xai-catalog-silent/medium -> {reasoning_effort:"medium"}
xai-catalog-silent/high -> {reasoning_effort:"high"}
xai-catalog-silent/xhigh -> {reasoning_effort:"xhigh"}
xai-catalog-silent/max -> {reasoning_effort:"max"}
xai-catalog-silent/ultra -> {reasoning_effort:"max"}
xai-no-effort-tiers-on-unified/off -> {reasoning_effort:"minimal"}
xai-no-effort-tiers-on-unified/low -> {reasoning_effort:"low"}
xai-no-effort-tiers-on-unified/medium -> {reasoning_effort:"medium"}
xai-no-effort-tiers-on-unified/high -> {reasoning_effort:"high"}
xai-no-effort-tiers-on-unified/xhigh -> {reasoning_effort:"xhigh"}
xai-no-effort-tiers-on-unified/max -> {reasoning_effort:"max"}
xai-no-effort-tiers-on-unified/ultra -> {reasoning_effort:"max"}
qwen-on-unified/off -> {enable_thinking:false,extra_body:{enable_thinking:false,thinking_budget:null}}
qwen-on-unified/low -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:4096},thinking_budget:4096}
qwen-on-unified/medium -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:6144},thinking_budget:6144}
qwen-on-unified/high -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:6144},thinking_budget:6144}
qwen-on-unified/xhigh -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:6144},thinking_budget:6144}
qwen-on-unified/max -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:6144},thinking_budget:6144}
qwen-on-unified/ultra -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:6144},thinking_budget:6144}
gpt5-on-unified/off -> {reasoning_effort:"minimal"}
gpt5-on-unified/low -> {reasoning_effort:"low"}
gpt5-on-unified/medium -> {reasoning_effort:"medium"}
gpt5-on-unified/high -> {reasoning_effort:"high"}
gpt5-on-unified/xhigh -> {reasoning_effort:"xhigh"}
gpt5-on-unified/max -> {reasoning_effort:"max"}
gpt5-on-unified/ultra -> {reasoning_effort:"max"}
mimo-on-unified/off -> {}
mimo-on-unified/low -> {reasoning_effort:"low"}
mimo-on-unified/medium -> {reasoning_effort:"medium"}
mimo-on-unified/high -> {reasoning_effort:"high"}
mimo-on-unified/xhigh -> {reasoning_effort:"xhigh"}
mimo-on-unified/max -> {reasoning_effort:"max"}
mimo-on-unified/ultra -> {reasoning_effort:"max"}
qwen-on-openrouter/off -> {}
qwen-on-openrouter/low -> {reasoning:{effort:"low"}}
qwen-on-openrouter/medium -> {reasoning:{effort:"medium"}}
qwen-on-openrouter/high -> {reasoning:{effort:"high"}}
qwen-on-openrouter/xhigh -> {reasoning:{effort:"xhigh"}}
qwen-on-openrouter/max -> {reasoning:{effort:"max"}}
qwen-on-openrouter/ultra -> {reasoning:{effort:"max"}}
deepseek-v4-on-openrouter/off -> {}
deepseek-v4-on-openrouter/low -> {reasoning:{effort:"low"}}
deepseek-v4-on-openrouter/medium -> {reasoning:{effort:"medium"}}
deepseek-v4-on-openrouter/high -> {reasoning:{effort:"high"}}
deepseek-v4-on-openrouter/xhigh -> {reasoning:{effort:"xhigh"}}
deepseek-v4-on-openrouter/max -> {reasoning:{effort:"max"}}
deepseek-v4-on-openrouter/ultra -> {reasoning:{effort:"max"}}
minimax-on-tiers-only/off -> {}
minimax-on-tiers-only/low -> {reasoning_effort:"low"}
minimax-on-tiers-only/medium -> {reasoning_effort:"medium"}
minimax-on-tiers-only/high -> {reasoning_effort:"high"}
minimax-on-tiers-only/xhigh -> {reasoning_effort:"high"}
minimax-on-tiers-only/max -> {reasoning_effort:"high"}
minimax-on-tiers-only/ultra -> {reasoning_effort:"high"}
glm-declares-off-tier/off -> {reasoning_effort:"none"}
glm-declares-off-tier/low -> {reasoning_effort:"low"}
glm-declares-off-tier/medium -> {reasoning_effort:"medium"}
glm-declares-off-tier/high -> {reasoning_effort:"high"}
glm-declares-off-tier/xhigh -> {reasoning_effort:"high"}
glm-declares-off-tier/max -> {reasoning_effort:"high"}
glm-declares-off-tier/ultra -> {reasoning_effort:"high"}
kimi-declares-off-tier/off -> {reasoning_effort:"none"}
kimi-declares-off-tier/low -> {reasoning_effort:"low"}
kimi-declares-off-tier/medium -> {reasoning_effort:"low"}
kimi-declares-off-tier/high -> {reasoning_effort:"high"}
kimi-declares-off-tier/xhigh -> {reasoning_effort:"high"}
kimi-declares-off-tier/max -> {reasoning_effort:"max"}
kimi-declares-off-tier/ultra -> {reasoning_effort:"max"}
minimax-declares-off-tier/off -> {reasoning_effort:"none"}
minimax-declares-off-tier/low -> {reasoning_effort:"low"}
minimax-declares-off-tier/medium -> {reasoning_effort:"medium"}
minimax-declares-off-tier/high -> {reasoning_effort:"high"}
minimax-declares-off-tier/xhigh -> {reasoning_effort:"high"}
minimax-declares-off-tier/max -> {reasoning_effort:"high"}
minimax-declares-off-tier/ultra -> {reasoning_effort:"high"}
vendorx-on-tiers-only/off -> {}
vendorx-on-tiers-only/low -> {reasoning_effort:"low"}
vendorx-on-tiers-only/medium -> {reasoning_effort:"low"}
vendorx-on-tiers-only/high -> {reasoning_effort:"high"}
vendorx-on-tiers-only/xhigh -> {reasoning_effort:"high"}
vendorx-on-tiers-only/max -> {reasoning_effort:"high"}
vendorx-on-tiers-only/ultra -> {reasoning_effort:"high"}
authoritative-on-tiers-only-offvalue-declared/off -> {}
authoritative-on-tiers-only-offvalue-declared/low -> {reasoning_effort:"low"}
authoritative-on-tiers-only-offvalue-declared/medium -> {reasoning_effort:"medium"}
authoritative-on-tiers-only-offvalue-declared/high -> {reasoning_effort:"high"}
authoritative-on-tiers-only-offvalue-declared/xhigh -> {reasoning_effort:"high"}
authoritative-on-tiers-only-offvalue-declared/max -> {reasoning_effort:"high"}
authoritative-on-tiers-only-offvalue-declared/ultra -> {reasoning_effort:"high"}
nonauthoritative-on-tiers-only-offvalue-declared/off -> {reasoning_effort:"low"}
nonauthoritative-on-tiers-only-offvalue-declared/low -> {reasoning_effort:"low"}
nonauthoritative-on-tiers-only-offvalue-declared/medium -> {reasoning_effort:"medium"}
nonauthoritative-on-tiers-only-offvalue-declared/high -> {reasoning_effort:"high"}
nonauthoritative-on-tiers-only-offvalue-declared/xhigh -> {reasoning_effort:"high"}
nonauthoritative-on-tiers-only-offvalue-declared/max -> {reasoning_effort:"high"}
nonauthoritative-on-tiers-only-offvalue-declared/ultra -> {reasoning_effort:"high"}
authoritative-declares-off-tier/off -> {reasoning_effort:"none"}
authoritative-declares-off-tier/low -> {reasoning_effort:"low"}
authoritative-declares-off-tier/medium -> {reasoning_effort:"low"}
authoritative-declares-off-tier/high -> {reasoning_effort:"high"}
authoritative-declares-off-tier/xhigh -> {reasoning_effort:"high"}
authoritative-declares-off-tier/max -> {reasoning_effort:"high"}
authoritative-declares-off-tier/ultra -> {reasoning_effort:"high"}
authoritative-no-declared-tiers/off -> {reasoning_effort:"none"}
authoritative-no-declared-tiers/low -> {reasoning_effort:"low"}
authoritative-no-declared-tiers/medium -> {reasoning_effort:"medium"}
authoritative-no-declared-tiers/high -> {reasoning_effort:"high"}
authoritative-no-declared-tiers/xhigh -> {reasoning_effort:"xhigh"}
authoritative-no-declared-tiers/max -> {reasoning_effort:"max"}
authoritative-no-declared-tiers/ultra -> {reasoning_effort:"max"}
"""#

    // MARK: - Liveness of the authoritative guard

    /// [T-thinking-off-custom-provider] Asserts the authoritative guard is REACHABLE, as
    /// a claim in its own right rather than as a line buried in the golden block.
    ///
    /// Why this exists separately: the golden block is a single string comparison, so the
    /// standard repair for a red snapshot — paste in the new output — would happily
    /// absorb "the guard stopped firing" as though it were a formatting change. This test
    /// cannot be repaired that way. It states the property directly: with everything else
    /// held byte-identical, flipping `effortDeclarationIsAuthoritative` must change the
    /// emitted body. If it ever does not, the guard is dead code and 621b0f27's Vision
    /// 400 fix has silently regressed.
    ///
    /// The off value is deliberately one the model DECLARES ("low" ∈ [low,medium,high]),
    /// which is what isolates this guard from the older membership check at
    /// ThinkingRuleResolver.swift:468. Pick an undeclared off value instead and both
    /// mechanisms suppress, the two sides agree, and the test proves nothing.
    func testAuthoritativeFlagIsLoadBearingForOffTier() {
        let tiers = ["low", "medium", "high"]   // no none/off/minimal/disabled member

        let authoritative = emit(
            model: model("minimax-m3", effortValues: tiers, authoritative: true),
            level: .off, isOpenRouter: false, maxTokens: 8192,
            offEffort: "low", unified: false
        )
        let heuristic = emit(
            model: model("minimax-m3", effortValues: tiers, authoritative: false),
            level: .off, isOpenRouter: false, maxTokens: 8192,
            offEffort: "low", unified: false
        )

        XCTAssertNotEqual(
            authoritative, heuristic,
            """
            The authoritative off-tier guard is no longer reachable.

            Same model, same declared tiers, same off value — only
            `effortDeclarationIsAuthoritative` differs, so these two MUST differ. That they
            agree means the guard added by 621b0f27 no longer fires, and a catalog entry
            describing THIS endpoint can no longer withhold an off tier the vendor never
            documented (the MiniMax M3 vision 400).

            Do not delete this test to make the suite green. Either restore the guard, or
            establish that the older membership check at ThinkingRuleResolver.swift:468 now
            subsumes it — and if so, remove the guard deliberately and say so.
            """
        )

        // Pin both directions, so a future change that inverts them is caught too.
        XCTAssertEqual(authoritative, "{}",
                       "An authoritative catalog that declares no off tier must suppress it.")
        XCTAssertEqual(heuristic, #"{reasoning_effort:"low"}"#,
                       "A non-authoritative guess must never override the user's explicit off.")
    }

    /// Utility: prints the current matrix so the expectation above can be seeded.
    /// Not an assertion — it always passes; it exists to generate the baseline text.
    func testPrintCurrentSnapshot() {
        var lines: [String] = []
        for c in matrix() {
            for level in levels {
                let out = emit(
                    model: c.model, level: level, isOpenRouter: c.isOpenRouter,
                    maxTokens: c.maxTokens, offEffort: c.offEffort, unified: c.unified,
                    isXAI: c.isXAI
                )
                lines.append("\(c.label)/\(level.rawValue) -> \(out)")
            }
        }
        print("===GOLDEN-SNAPSHOT-BEGIN===")
        print(lines.joined(separator: "\n"))
        print("===GOLDEN-SNAPSHOT-END===")
    }
}
