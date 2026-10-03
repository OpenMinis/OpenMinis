// Tests for [T-supports-reasoning-defaults] (M11) — how a nil / true / false
// `supportsReasoning` is interpreted, and by whom.
//
// Android fixed this twice by editing model literals: GPT-5.x / o-series had to
// default to true or the Thinking pill never enabled (9938d0686), and a direct
// (non-proxy) Anthropic instance running a reasoning Claude showed no Deep
// Thinking toggle (f1b8e397f). iOS solves the same problem structurally instead:
// its built-in literals leave `supportsReasoning` NIL (= unknown) and every gate
// decides what unknown means for its own direction —
//
//   * `AIChatViewModel.entryAllowsReasoning` (AIChatViewModel.swift:1368) is the
//     TOGGLE-VISIBILITY gate: `supportsReasoning != false` → visible, so nil and
//     true both show the toggle. An explicit false is then rescued per provider
//     type, because a third-party OpenAI-compatible relay's catalog routinely
//     under-reports.
//   * `modelMayReason = supportsReasoning ?? true` is a VETO: only clamp off when
//     the catalog actively says false (OpenAIAgentProvider.swift:1347,
//     AnthropicAgentProvider.swift:166).
//   * `modelAlwaysReasons = supportsReasoning == true` is the forced-reasoning
//     signal, where nil must NOT count (OpenAIAgentProvider.swift:1346).
//   * The detail screen's switch reads `effective.supportsReasoning ?? false`,
//     because a switch has to render some position and OFF is the honest one for
//     "unknown".
//
// Getting any of those four defaults backwards is a silent capability change, so
// this file pins each one and the sign of its `??`. It also covers 6960e5b9c (an
// edit persists into overrides and survives a catalog refresh) and c19fa7c58 (an
// imported model must carry an explicit modality rather than inheriting the
// provider table's vision default).
//
// The gates are ported verbatim; section [6] re-reads the shipping sources so the
// copies cannot drift. Standalone (`swift SupportsReasoningDefaultsTests.swift`).

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Ported types

struct Modality: OptionSet, Equatable {
    let rawValue: Int
    static let textInput  = Modality(rawValue: 1 << 0)
    static let imageInput = Modality(rawValue: 1 << 1)
    static let pdfInput   = Modality(rawValue: 1 << 2)
    static let textOutput = Modality(rawValue: 1 << 3)
    static let textOnly: Modality = [.textInput, .textOutput]
    static let vision: Modality = [.textInput, .imageInput, .pdfInput, .textOutput]
}

enum ProviderType { case anthropic, gemini, openAI, openAIResponses, antigravity
                    case openRouter, xAI, kimiCode, githubCopilot, unsupported }

struct Instance { var id: String; var providerType: ProviderType; var customBaseURL: String? }

struct LLMModel {
    var id: String
    var provider: String = "OpenAI"
    var supportsReasoning: Bool?
    var modalityOverride: Modality?
    var interleavedReasoningField: String?
    /// Mirrors LLMModel.capabilities' nil→provider-table fallback.
    var capabilities: Modality {
        if let o = modalityOverride { return o }
        return ["OpenAI": .vision, "Anthropic": .vision, "Google": .vision][provider] ?? .textOnly
    }
}

struct Overrides: Equatable {
    var supportsReasoning: Bool? = nil
    var contextWindow: Int? = nil
    var modalityOverride: Modality? = nil
    var isEmpty: Bool { supportsReasoning == nil && contextWindow == nil && modalityOverride == nil }
}

struct ModelEntry {
    var uuid: String
    var providerInstanceId: String
    var baseModel: LLMModel
    var overrides = Overrides()
    /// Verbatim from ModelEntry.model.
    var model: LLMModel {
        guard !overrides.isEmpty else { return baseModel }
        var m = baseModel
        m.supportsReasoning = overrides.supportsReasoning ?? baseModel.supportsReasoning
        m.modalityOverride = overrides.modalityOverride ?? baseModel.modalityOverride
        return m
    }
}

/// Verbatim from AIChatViewModel.entryAllowsReasoning (AIChatViewModel.swift:1368).
func entryAllowsReasoning(_ entry: ModelEntry, instance: Instance?) -> Bool {
    if entry.model.supportsReasoning != false { return true }   // true or nil → allowed
    guard let instance else { return false }
    switch instance.providerType {
    case .openAI, .openAIResponses:
        return instance.customBaseURL?.isEmpty == false          // custom/local base only
    case .openRouter, .xAI, .kimiCode:
        return true
    case .anthropic, .gemini, .antigravity, .githubCopilot, .unsupported:
        return false
    }
}

/// The three request-side signals.
func modelAlwaysReasons(_ m: LLMModel) -> Bool { m.supportsReasoning == true }
func modelMayReason(_ m: LLMModel) -> Bool { m.supportsReasoning ?? true }
func includeReasoning(_ m: LLMModel, thinkingEnabled: Bool) -> Bool {
    (thinkingEnabled || modelAlwaysReasons(m)) && modelMayReason(m)
}
/// The detail screen's switch position.
func detailSwitchIsOn(_ m: LLMModel) -> Bool { m.supportsReasoning ?? false }

func entry(_ id: String, _ reasoning: Bool?, provider: String = "OpenAI") -> ModelEntry {
    ModelEntry(uuid: "u-\(id)", providerInstanceId: "i",
               baseModel: LLMModel(id: id, provider: provider, supportsReasoning: reasoning))
}
let officialOpenAI = Instance(id: "i", providerType: .openAI, customBaseURL: nil)
let relay = Instance(id: "i", providerType: .openAI, customBaseURL: "https://relay.example/v1")
let directAnthropic = Instance(id: "i", providerType: .anthropic, customBaseURL: nil)

// ---------------------------------------------------------------------------

print("\n[1] GPT-5.x / o-series: the toggle must be available")

// Android had to write supportsReasoning=true into each literal; iOS reaches the
// same outcome from nil, because the visibility gate is `!= false`. Both the
// catalogued-true and the uncatalogued-nil shapes are asserted, so a future
// change that flips the gate to `== true` fails here.
for id in ["gpt-5", "gpt-5.2", "gpt-5.4-mini", "gpt-5.6-sol", "gpt-5.3-codex",
           "o3", "o3-mini", "o4-mini", "codex-mini-latest"] {
    check("\(id) with nil capability: toggle visible",
          entryAllowsReasoning(entry(id, nil), instance: officialOpenAI))
    check("\(id) with catalogued true: toggle visible",
          entryAllowsReasoning(entry(id, true), instance: officialOpenAI))
}
// A model the catalog says is NOT a reasoning model, on the OFFICIAL endpoint,
// is authoritative — the toggle hides.
check("official OpenAI + explicit false → toggle hidden",
      entryAllowsReasoning(entry("gpt-4o", false), instance: officialOpenAI), false)
// …but on a custom/local base the same false is not trusted, because a relay's
// /v1/models routinely reports no capability metadata at all.
check("a custom-base OpenAI instance rescues an explicit false",
      entryAllowsReasoning(entry("some-local-model", false), instance: relay))
check("…and an empty custom base string does NOT count as custom",
      entryAllowsReasoning(entry("m", false),
                           instance: Instance(id: "i", providerType: .openAI, customBaseURL: "")), false)

print("\n[2] Direct Claude: a reasoning model shows the Deep Thinking toggle")

// f1b8e397f's report, from the iOS side: with nil (uncatalogued) or true the
// toggle shows on a direct Anthropic instance.
for id in ["claude-opus-5", "claude-sonnet-5", "claude-fable-5-1", "claude-opus-4-8"] {
    check("\(id) nil → toggle visible on direct Anthropic",
          entryAllowsReasoning(entry(id, nil, provider: "Anthropic"), instance: directAnthropic))
    check("\(id) true → toggle visible on direct Anthropic",
          entryAllowsReasoning(entry(id, true, provider: "Anthropic"), instance: directAnthropic))
}
// Anthropic is in the STRICT group: an explicit false there really is
// authoritative, unlike the OpenAI-compatible relays.
check("direct Anthropic + explicit false → hidden (authoritative)",
      entryAllowsReasoning(entry("claude-3-haiku", false, provider: "Anthropic"),
                           instance: directAnthropic), false)
// The permissive / strict split, asserted per provider type so a type moving
// group is visible here.
let permissive: [ProviderType] = [.openRouter, .xAI, .kimiCode]
let strict: [ProviderType] = [.anthropic, .gemini, .antigravity, .githubCopilot, .unsupported]
for t in permissive {
    check("explicit false is rescued for a permissive provider",
          entryAllowsReasoning(entry("m", false), instance: Instance(id: "i", providerType: t, customBaseURL: nil)))
}
for t in strict {
    check("explicit false is honoured for a strict provider",
          entryAllowsReasoning(entry("m", false), instance: Instance(id: "i", providerType: t, customBaseURL: nil)), false)
}
// Copilot is deliberately strict: its /models reports supports.thinking per
// model, so a false there is a real answer (T-copilot-provider).
check("GitHub Copilot's explicit false is authoritative",
      entryAllowsReasoning(entry("gpt-4o-copilot", false),
                           instance: Instance(id: "i", providerType: .githubCopilot, customBaseURL: nil)), false)
// A missing instance row cannot rescue anything.
check("an explicit false with no resolvable instance stays hidden",
      entryAllowsReasoning(entry("m", false), instance: nil), false)

print("\n[3] The four ?? defaults point in different directions — on purpose")

let unknown = LLMModel(id: "deepseek-v4", supportsReasoning: nil)
let declaredTrue = LLMModel(id: "deepseek-r1", supportsReasoning: true)
let declaredFalse = LLMModel(id: "gpt-4o", supportsReasoning: false)

// modelMayReason is a VETO: nil stays permissive.
check("modelMayReason(nil) is TRUE — unknown must not clamp", modelMayReason(unknown))
check("modelMayReason(true) is true", modelMayReason(declaredTrue))
check("modelMayReason(false) is false", modelMayReason(declaredFalse), false)
// modelAlwaysReasons is a forced-reasoning signal: nil must NOT count, or every
// uncatalogued model would get reasoning fields echoed into its history.
check("modelAlwaysReasons(nil) is FALSE", modelAlwaysReasons(unknown), false)
check("modelAlwaysReasons(true) is true", modelAlwaysReasons(declaredTrue))
check("modelAlwaysReasons(false) is false", modelAlwaysReasons(declaredFalse), false)
// The two composed: what actually goes on the wire.
check("unknown + thinking on → reasoning included", includeReasoning(unknown, thinkingEnabled: true))
check("unknown + thinking off → NOT included", includeReasoning(unknown, thinkingEnabled: false), false)
check("declared-true + thinking off → still included (forced reasoner)",
      includeReasoning(declaredTrue, thinkingEnabled: false))
check("declared-false + thinking on → still excluded (the veto wins)",
      includeReasoning(declaredFalse, thinkingEnabled: true), false)
// The detail switch is the one place nil renders as OFF.
check("the detail switch is OFF for nil", detailSwitchIsOn(unknown), false)
check("…ON for true", detailSwitchIsOn(declaredTrue))
check("…OFF for false", detailSwitchIsOn(declaredFalse), false)
// …and that is deliberately the OPPOSITE default from the visibility gate, which
// is the asymmetry worth pinning: the toggle is offered, its stored switch reads
// off until the user or the catalog says otherwise.
check("visibility gate and detail switch disagree for nil, by design",
      entryAllowsReasoning(entry("m", nil), instance: officialOpenAI) != detailSwitchIsOn(unknown))

print("\n[4] 6960e5b9c — an edit persists into overrides and survives a refresh")

var edited = entry("some-local-model", false)
check("before the edit: the catalog says no", edited.model.supportsReasoning == false)
edited.overrides.supportsReasoning = true
checkEq("the edit lands in overrides, not baseModel",
        edited.overrides.supportsReasoning, true)
checkEq("…and baseModel still records what the catalog said",
        edited.baseModel.supportsReasoning, false)
check("the effective model reports the user's value", edited.model.supportsReasoning == true)
check("…so the request-side signals follow it", modelAlwaysReasons(edited.model))
check("…and the detail switch shows ON", detailSwitchIsOn(edited.model))
// A catalog refresh rebuilds baseModel but carries overrides forward
// (replaceEntries: `overrides: prior?.overrides ?? ModelOverrides()`).
var refreshed = edited
refreshed.baseModel = LLMModel(id: "some-local-model", supportsReasoning: false)
checkEq("the override survives a refresh", refreshed.overrides.supportsReasoning, true)
check("…and still wins", refreshed.model.supportsReasoning == true)
// The reverse edit: a user turning it OFF on a model the catalog calls a
// reasoner must be honoured, and must reach the veto.
var turnedOff = entry("deepseek-r1", true)
turnedOff.overrides.supportsReasoning = false
check("a user-forced false reaches the veto", modelMayReason(turnedOff.model), false)
check("…and hides the toggle even on a permissive provider",
      entryAllowsReasoning(turnedOff, instance: Instance(id: "i", providerType: .openAI, customBaseURL: nil)), false)
// Clearing the override returns to the catalog's answer.
turnedOff.overrides.supportsReasoning = nil
check("clearing the override restores the catalog value",
      turnedOff.model.supportsReasoning == true)
// contextWindow rides the same override mechanism (the other half of 6960e5b9c).
var ctxEdited = entry("m", nil)
ctxEdited.overrides.contextWindow = 300_000
checkEq("a contextWindow edit is stored as an override too",
        ctxEdited.overrides.contextWindow, 300_000)
check("…and the entry is no longer 'unmodified'", ctxEdited.overrides.isEmpty, false)

print("\n[5] c19fa7c58 — an imported model must carry an EXPLICIT modality")

/// Verbatim from ProviderConfigStore's provider.import path.
func importModel(_ m: LLMModel) -> LLMModel {
    var model = m
    if model.modalityOverride == nil {
        model.modalityOverride = [.textInput, .textOutput]
    }
    return model
}
// The reported symptom: a text-only model whose "Image input" switch read OFF
// still received images, because a nil modalityOverride falls through to
// knownCapabilities["OpenAI"] = .vision. Toggling the switch on and off "fixed"
// it — the classic stale-default tell.
let importedNil = LLMModel(id: "local-text-model", provider: "OpenAI", modalityOverride: nil)
check("WITHOUT the fix, a nil modality inherits the provider's vision default",
      importedNil.capabilities.contains(.imageInput))
let imported = importModel(importedNil)
checkEq("after import, the modality is explicit text-only",
        imported.modalityOverride, Modality.textOnly)
check("…so it no longer claims image input", imported.capabilities.contains(.imageInput), false)
// An export that DOES carry a modality is not overwritten.
let importedVision = importModel(LLMModel(id: "gpt-4o", provider: "OpenAI", modalityOverride: .vision))
checkEq("an explicit modality in the export is preserved", importedVision.modalityOverride, Modality.vision)
check("…including its image input", importedVision.capabilities.contains(.imageInput))
// An explicitly text-only export stays text-only (idempotent).
checkEq("importing twice is idempotent",
        importModel(importModel(importedNil)).modalityOverride, Modality.textOnly)
// supportsReasoning is NOT forced by the import — unknown must stay unknown, or
// the import would fabricate a capability.
check("import does not invent a supportsReasoning value",
      importModel(LLMModel(id: "m", supportsReasoning: nil)).supportsReasoning == nil)

print("\n[6] Source-grep drift guard")

func source(_ rel: String) -> String {
    var root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
    while !FileManager.default.fileExists(atPath: root.appendingPathComponent(rel).path),
          root.pathComponents.count > 1 { root.deleteLastPathComponent() }
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let vm = source("src/ios/Agent/Chat/AIChatViewModel.swift")
let openai = source("src/ios/Providers/OpenAI/OpenAIAgentProvider.swift")
let anthropic = source("src/ios/Providers/Anthropic/AnthropicAgentProvider.swift")
let storeSrc = source("src/ios/Providers/ProviderConfigStore.swift")
let entrySrc = source("src/ios/Providers/ModelEntry.swift")
let detail = source("src/ios/Views/Providers/ProviderInstanceDetailView.swift")
for (n, s) in [("AIChatViewModel", vm), ("OpenAIAgentProvider", openai),
               ("AnthropicAgentProvider", anthropic), ("ProviderConfigStore", storeSrc),
               ("ModelEntry", entrySrc), ("ProviderInstanceDetailView", detail)] {
    check("\(n) read", !s.isEmpty)
}

// The visibility gate: `!= false`, not `== true`.
check("entryAllowsReasoning admits nil and true via `!= false`",
      vm.contains("if entry.model.supportsReasoning != false {"))
check("…and reads the EFFECTIVE model, not baseModel",
      vm.contains("entry.model.supportsReasoning != false"))
check("the permissive group is openRouter / xAI / kimiCode",
      vm.contains("case .openRouter, .xAI, .kimiCode:\n            return true"))
check("the strict group includes Anthropic, Gemini, Antigravity, Copilot",
      vm.contains("case .anthropic, .gemini, .antigravity, .githubCopilot, .unsupported:\n            return false"))
check("a custom OpenAI base is what rescues an explicit false",
      vm.contains("return instance.customBaseURL?.isEmpty == false"))
check("a group binding shows the toggle when ANY member allows reasoning",
      vm.contains("return group.memberEntryIds.contains { memberId in"))

// The three request-side signals, with their signs.
check("modelAlwaysReasons is `== true` (nil does not force)",
      openai.contains("let modelAlwaysReasons = model.supportsReasoning == true"))
check("modelMayReason is `?? true` (a veto, not a requirement)",
      openai.contains("let modelMayReason     = model.supportsReasoning ?? true"))
check("Anthropic's echo gate uses the same `?? true` veto",
      anthropic.contains("let modelMayReason          = model.supportsReasoning ?? true"))
check("includeReasoning composes them as (enabled OR always) AND may",
      openai.contains("let includeReasoning   = (thinkingLevel.isEnabled || modelAlwaysReasons) && modelMayReason"))
check("the thinking resolver's own gate is also `!= false`",
      source("src/ios/Providers/Thinking/ThinkingRuleResolver.swift")
        .contains("guard ctx.supportsReasoning != false else {"))

// The detail switch, and persistence into overrides.
check("the detail switch renders nil as OFF",
      detail.contains("supportsThinking = effective.supportsReasoning ?? false"))
check("…and Reset reads the base model with the same default",
      detail.contains("supportsThinking = base.supportsReasoning ?? false"))
check("ModelOverrides carries supportsReasoning",
      entrySrc.contains("var supportsReasoning: Bool?"))
check("…and isEmpty accounts for it, so an edit marks the entry user-modified",
      entrySrc.contains("&& supportsReasoning == nil"))
check("ModelEntry.model layers the override over baseModel",
      entrySrc.contains("supportsReasoning: overrides.supportsReasoning ?? baseModel.supportsReasoning"))
check("a refresh carries overrides forward",
      storeSrc.contains("overrides: prior?.overrides ?? ModelOverrides(),"))

// The import safety net.
check("import never leaves modalityOverride nil",
      storeSrc.contains("if model.modalityOverride == nil {")
        && storeSrc.contains("modalityOverride: [.textInput, .textOutput],"))
check("…and the rationale (the nil → provider-table vision fallback) is documented",
      storeSrc.contains("falls through to knownCapabilities[\"OpenAI\"] = .vision"))

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILED") }
exit(failures == 0 ? 0 : 1)
