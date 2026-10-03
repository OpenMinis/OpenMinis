// Tests for [T-provider-default-modality-key] (M08) — the provider-default
// modality fallback is only reachable if its table KEY matches the string
// `LLMModel.provider` actually carries, for EVERY provider type.
//
// Android shipped exactly this bug twice. 1be751fdc added the provider-default
// table but wired it only into the model detail SCREEN, so the property the app
// reads never saw it (728bf68d8 moved it into ModelEntry.model). Then 6fff8231b
// found the Gemini row keyed "Google" while ProviderType.displayName is
// "Google Gemini" — an uncatalogued Gemini model therefore got no default at
// all: hasImageInput false, excluded from Vision Group membership, every
// modality switch OFF. The failure is silent by construction: a dictionary miss
// falls through to `defaultCapabilities` (textOnly), which looks exactly like a
// model that genuinely cannot see images.
//
// On iOS there are TWO tables and they are keyed differently, which is the whole
// point of this file:
//
//   * `ProviderType.defaultModality` (ProviderTypes.swift:141) switches over the
//     ENUM — structurally immune to a key typo, and exhaustive by compiler.
//   * `LLMModel.knownCapabilities` (LLMTypes.swift:653) is a
//     [String: ModelCapabilities] keyed on the free-form `provider` string that
//     each model literal / models API writes. That one CAN mismatch, and section
//     [4] asserts that EVERY provider string a models API writes has a row —
//     "xAI", "Kimi" and "GitHub Copilot" were missing until G8 added them.
//
// Both tables and the effective-modality chain are ported verbatim; section [6]
// re-reads the shipping sources so the copies cannot drift. Standalone
// (`swift ProviderDefaultModalityKeyTests.swift`).

import Foundation

var failures = 0
var gaps: [String] = []
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}
/// A genuine production gap: recorded and reported, never failed.
func gap(_ l: String) { print("  ⚠️ KNOWN GAP: \(l)"); gaps.append(l) }

// MARK: - Ported types

struct Modality: OptionSet, Equatable {
    let rawValue: Int
    static let textInput    = Modality(rawValue: 1 << 0)
    static let imageInput   = Modality(rawValue: 1 << 1)
    static let pdfInput     = Modality(rawValue: 1 << 2)
    static let audioInput   = Modality(rawValue: 1 << 3)
    static let videoInput   = Modality(rawValue: 1 << 4)
    static let textOutput   = Modality(rawValue: 1 << 5)
    static let textOnly: Modality = [.textInput, .textOutput]
    static let vision: Modality = [.textInput, .imageInput, .pdfInput, .textOutput]
    static let fullMultimodal: Modality = [.textInput, .imageInput, .pdfInput,
                                           .audioInput, .videoInput, .textOutput]
}

enum ProviderType: String, CaseIterable {
    case anthropic, gemini, openAI, openAIResponses, antigravity
    case openRouter, xAI, kimiCode, githubCopilot, unsupported

    /// Verbatim from ProviderTypes.swift:42.
    var displayName: String {
        switch self {
        case .anthropic: return "Anthropic"
        case .gemini: return "Google Gemini"
        case .openAI: return "OpenAI"
        case .antigravity: return "Antigravity"
        case .openRouter: return "OpenRouter"
        case .openAIResponses: return "Responses API (v3)"
        case .xAI: return "xAI (Grok)"
        case .kimiCode: return "Kimi Code"
        case .githubCopilot: return "GitHub Copilot"
        case .unsupported: return "Unsupported"
        }
    }

    /// Verbatim from ProviderTypes.swift:141 — keyed on the ENUM, not a string.
    var defaultModality: Modality {
        switch self {
        case .anthropic: return .vision
        case .gemini:    return .fullMultimodal
        case .openAI:    return .vision
        case .antigravity: return .fullMultimodal
        case .openRouter: return .vision
        case .openAIResponses: return .vision
        case .xAI: return .vision
        case .kimiCode: return .vision
        case .githubCopilot: return .vision
        case .unsupported: return .vision
        }
    }

    /// The `provider` string that this type's model literals / models API write
    /// into `LLMModel.provider`. Read out of the sources, not guessed:
    ///   LLMTypes.swift `provider: "Anthropic" / "Google" / "OpenAI" /
    ///   "Antigravity" / "OpenRouter"`, xAIModelsAPI `"xAI"`,
    ///   KimiModelsAPI `"Kimi"`, CopilotModelsAPI `"GitHub Copilot"`,
    ///   GeminiModelsAPI `"Google"`, OpenAIModelsAPI `"OpenAI"`.
    var modelProviderString: String? {
        switch self {
        case .anthropic: return "Anthropic"
        case .gemini: return "Google"
        case .openAI, .openAIResponses: return "OpenAI"
        case .antigravity: return "Antigravity"
        case .openRouter: return "OpenRouter"
        case .xAI: return "xAI"
        case .kimiCode: return "Kimi"
        case .githubCopilot: return "GitHub Copilot"
        case .unsupported: return nil
        }
    }
}

/// Verbatim from LLMTypes.swift:653 — a STRING-keyed table.
let knownCapabilities: [String: Modality] = [
    "Anthropic": .vision,
    "Google": .fullMultimodal,
    "OpenAI": .vision,
    "Antigravity": .fullMultimodal,
    "OpenRouter": .vision,
    // The three rows added for this item. In the shipping table their modality
    // is read from `ProviderType.<case>.defaultModality` rather than restated,
    // so the string table cannot drift from the enum; section [5] asserts they
    // still agree, and section [6] greps for the derivation.
    "xAI": ProviderType.xAI.defaultModality,
    "Kimi": ProviderType.kimiCode.defaultModality,
    "GitHub Copilot": ProviderType.githubCopilot.defaultModality,
]
/// Verbatim from LLMTypes.swift:677.
let defaultCapabilities: Modality = .textOnly

struct Model {
    var id: String
    var provider: String
    var modalityOverride: Modality?

    /// Verbatim from LLMModel.capabilities (LLMTypes.swift:735).
    var capabilities: Modality {
        if let override = modalityOverride { return override }
        return knownCapabilities[provider] ?? defaultCapabilities
    }
    var hasImageInput: Bool { capabilities.contains(.imageInput) }
}

/// Verbatim from ModelEntry.model — the property the rest of the app reads.
struct Overrides { var modalityOverride: Modality? = nil }
struct Entry {
    var baseModel: Model
    var overrides = Overrides()
    var isEmpty: Bool { overrides.modalityOverride == nil }
    var model: Model {
        guard !isEmpty else { return baseModel }
        var m = baseModel
        m.modalityOverride = overrides.modalityOverride ?? baseModel.modalityOverride
        return m
    }
}

// ---------------------------------------------------------------------------

print("\n[1] ProviderType.defaultModality is keyed on the ENUM — exhaustive by compiler")

// The Android "Google" vs "Google Gemini" class of bug cannot happen here,
// because there is no string to mistype. Asserted as a property: every case
// answers, and no answer is the accidental textOnly.
var answered = 0
for t in ProviderType.allCases {
    answered += 1
    check("\(t.rawValue) has a default modality that is not the textOnly fallthrough",
          t.defaultModality != .textOnly)
}
checkEq("every provider type answers", answered, ProviderType.allCases.count)
checkEq("Gemini's default is fullMultimodal", ProviderType.gemini.defaultModality, .fullMultimodal)
checkEq("Antigravity's default is fullMultimodal", ProviderType.antigravity.defaultModality, .fullMultimodal)
for t in [ProviderType.anthropic, .openAI, .openRouter, .openAIResponses, .xAI, .kimiCode, .githubCopilot] {
    checkEq("\(t.rawValue)'s default is vision", t.defaultModality, .vision)
}
// Every default must at least allow image input, or the Vision-Group /
// image-preflight paths see a provider-default that is worse than useless.
for t in ProviderType.allCases {
    check("\(t.rawValue)'s default includes imageInput", t.defaultModality.contains(.imageInput))
}

print("\n[2] The display name is NOT the key — the Android mismatch, restated")

// The literal shape of the Android bug: keying the capability table by
// displayName. Demonstrated to be wrong on iOS data too, so nobody "tidies" the
// key over to displayName later.
for t in ProviderType.allCases {
    guard let real = t.modelProviderString else { continue }
    if t.displayName != real {
        check("\(t.rawValue): displayName \"\(t.displayName)\" ≠ model provider string \"\(real)\"",
              true)
        check("…so a displayName-keyed lookup misses", knownCapabilities[t.displayName] == nil)
    }
}
// Gemini specifically — the exact Android case, and the one where iOS is right.
checkEq("gemini displayName is \"Google Gemini\"", ProviderType.gemini.displayName, "Google Gemini")
checkEq("…but its models carry \"Google\"", ProviderType.gemini.modelProviderString, "Google")
check("the table has a \"Google\" row", knownCapabilities["Google"] != nil)
check("the table has NO \"Google Gemini\" row", knownCapabilities["Google Gemini"] == nil)
// So an uncatalogued Gemini model resolves correctly on iOS.
let uncataloguedGemini = Model(id: "gemini-4.0-pro-exp", provider: "Google")
checkEq("an uncatalogued Gemini model still gets fullMultimodal",
        uncataloguedGemini.capabilities, .fullMultimodal)
check("…so hasImageInput is true (the Android symptom was false)",
      uncataloguedGemini.hasImageInput)

print("\n[3] The fallback is applied where the REQUEST reads the model, not only in a screen")

// 728bf68d8's lesson: a fallback wired into the detail screen alone never
// reaches the property the app builds requests from. On iOS that property is
// ModelEntry.model, and `capabilities` is computed on LLMModel itself — so
// every reader gets the same answer.
let entry = Entry(baseModel: uncataloguedGemini)
checkEq("ModelEntry.model resolves the same capabilities as the bare model",
        entry.model.capabilities, uncataloguedGemini.capabilities)
check("…and the same hasImageInput", entry.model.hasImageInput == uncataloguedGemini.hasImageInput)
// A user override still wins over the provider default, in both readers.
var overridden = Entry(baseModel: uncataloguedGemini)
overridden.overrides.modalityOverride = .textOnly
checkEq("a user override wins over the provider default",
        overridden.model.capabilities, .textOnly)
check("…and turns hasImageInput off", overridden.model.hasImageInput, false)
// An explicit per-model modality (models.dev, or a provider API that reports
// modalities) also wins over the provider default.
let devEnriched = Model(id: "gemini-3-flash", provider: "Google", modalityOverride: .vision)
checkEq("an explicit per-model modality wins over the provider default",
        devEnriched.capabilities, .vision)
check("…so a model models.dev says is vision-only is not inflated to full multimodal",
      devEnriched.capabilities != .fullMultimodal)

print("\n[4] Coverage: which provider strings actually have a row")

// This is the M08 requirement — iterate EVERY provider type, not just the one
// that was reported. A type whose models write a provider string with no row
// gets `defaultCapabilities` = textOnly, and that is indistinguishable from a
// genuinely text-only model.
var missing: [String] = []
for t in ProviderType.allCases {
    guard let key = t.modelProviderString else { continue }
    if knownCapabilities[key] == nil { missing.append("\(t.rawValue) → \"\(key)\"") }
}
for t in ProviderType.allCases {
    guard let key = t.modelProviderString, let row = knownCapabilities[key] else { continue }
    checkEq("\(t.rawValue) (\"\(key)\") has a row, and it matches defaultModality",
            row, t.defaultModality)
}

// THE INVARIANT. "xAI", "Kimi" and "GitHub Copilot" used to have no row, while
// ProviderType already said .vision for all three — and a lookup miss is silent
// by construction, falling through to defaultCapabilities (textOnly), which is
// indistinguishable from a model that genuinely cannot see images.
//
// The consequence for xAI was the GH#265 shape exactly: an uncatalogued Grok
// (grok-4.6 the day it shipped) has no models.dev modality and no literal
// override, so with no table row `capabilities` was textOnly and hasImageInput
// false for a model that can see images. Asserted as a PROPERTY over all cases,
// not as three named rows, so the next provider type cannot ship without one.
checkEq("every provider string a models API writes has a row (none falls through "
        + "to textOnly)", missing, [])
let uncataloguedGrok = Model(id: "grok-4.6", provider: "xAI")
checkEq("an uncatalogued Grok (grok-4.6) resolves to xAI's default, not textOnly",
        uncataloguedGrok.capabilities, ProviderType.xAI.defaultModality)
check("…so hasImageInput is true (the pre-fix symptom was false)", uncataloguedGrok.hasImageInput)
let uncataloguedKimi = Model(id: "kimi-k3-preview", provider: "Kimi")
checkEq("an uncatalogued Kimi model resolves to kimiCode's default",
        uncataloguedKimi.capabilities, ProviderType.kimiCode.defaultModality)
check("…and can see images", uncataloguedKimi.hasImageInput)
let uncataloguedCopilot = Model(id: "gpt-6-astra", provider: "GitHub Copilot")
checkEq("a Copilot model that somehow reaches the table gets githubCopilot's default",
        uncataloguedCopilot.capabilities, ProviderType.githubCopilot.defaultModality)
// What DOES hold today, and is what keeps the gap from being user-visible:
check("a Copilot model always carries an explicit override (never reaches the table)",
      Model(id: "gpt-6-astra", provider: "GitHub Copilot", modalityOverride: .vision)
        .capabilities == .vision)
check("a catalogued Grok is rescued by its models.dev modality",
      Model(id: "grok-4", provider: "xAI", modalityOverride: .vision).hasImageInput)
// And the direction of the failure: a miss is always textOnly, never a crash
// and never an over-claim.
checkEq("an entirely unknown provider string degrades to textOnly, not a crash",
        Model(id: "x", provider: "Brand New Relay").capabilities, .textOnly)
check("…which never over-claims a capability the model lacks",
      Model(id: "x", provider: "Brand New Relay").hasImageInput, false)

print("\n[5] Table rows and enum defaults must not disagree")

// The two tables answer the same question by different keys, so they must give
// the same answer wherever both apply. A silent divergence would mean the
// "Add custom model" sheet and the request builder disagree about the model.
for t in ProviderType.allCases {
    guard let key = t.modelProviderString, let row = knownCapabilities[key] else { continue }
    checkEq("\(t.rawValue): string table and enum default agree", row, t.defaultModality)
}
checkEq("Anthropic: both say vision", knownCapabilities["Anthropic"], ProviderType.anthropic.defaultModality)
checkEq("Google: both say fullMultimodal", knownCapabilities["Google"], ProviderType.gemini.defaultModality)
checkEq("OpenAI: both say vision", knownCapabilities["OpenAI"], ProviderType.openAI.defaultModality)
checkEq("Antigravity: both say fullMultimodal",
        knownCapabilities["Antigravity"], ProviderType.antigravity.defaultModality)
checkEq("OpenRouter: both say vision", knownCapabilities["OpenRouter"], ProviderType.openRouter.defaultModality)

print("\n[6] Source-grep drift guard")

func source(_ rel: String) -> String {
    var root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
    while !FileManager.default.fileExists(atPath: root.appendingPathComponent(rel).path),
          root.pathComponents.count > 1 { root.deleteLastPathComponent() }
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let types = source("src/ios/Providers/ProviderTypes.swift")
let llm = source("src/ios/Providers/LLMTypes.swift")
let entrySrc = source("src/ios/Providers/ModelEntry.swift")
check("ProviderTypes read", !types.isEmpty)
check("LLMTypes read", !llm.isEmpty)
check("ModelEntry read", !entrySrc.isEmpty)

// The enum-keyed table stays enum-keyed.
check("defaultModality switches over the enum, not a string",
      types.contains("var defaultModality: ModelModality {") && types.contains("switch self {"))
checkEq("it still answers all ten cases",
        ["anthropic", "gemini", "openAI", "antigravity", "openRouter",
         "openAIResponses", "xAI", "kimiCode", "githubCopilot", "unsupported"]
            .filter { types.contains("case .\($0): return .") || types.contains("case .\($0):    return .") }
            .count,
        10)
check("the display names this test mirrors are unchanged",
      types.contains("case .gemini: return \"Google Gemini\"")
        && types.contains("case .xAI: return \"xAI (Grok)\""))

// The string-keyed table's keys are the ones this test asserts against.
check("knownCapabilities is still [String: ModelCapabilities]",
      llm.contains("private static let knownCapabilities: [String: ModelCapabilities] = ["))
for key in ["Anthropic", "Google", "OpenAI", "Antigravity", "OpenRouter",
            "xAI", "Kimi", "GitHub Copilot"] {
    check("row \"\(key)\" still present", llm.contains("\"\(key)\": ModelCapabilities("))
}
// The three rows added for this item take their modality FROM the enum rather
// than restating it, which is what makes the two tables unable to disagree.
for (key, enumCase) in [("xAI", "xAI"), ("Kimi", "kimiCode"), ("GitHub Copilot", "githubCopilot")] {
    check("row \"\(key)\" derives its modality from ProviderType.\(enumCase).defaultModality",
          llm.contains("supportedModalities: ProviderType.\(enumCase).defaultModality"))
}
check("the Gemini row is keyed \"Google\", not \"Google Gemini\"",
      llm.contains("\"Google Gemini\": ModelCapabilities("), false)
check("a miss still falls through to defaultCapabilities",
      llm.contains("return Self.knownCapabilities[provider] ?? Self.defaultCapabilities"))
check("defaultCapabilities is textOnly (so a miss is silent, hence this test)",
      llm.contains("private static let defaultCapabilities = ModelCapabilities(\n        supportedModalities: .textOnly,"))
check("an explicit modalityOverride still short-circuits the table",
      llm.contains("if let override = modalityOverride {"))
// The provider strings the models APIs write, which the coverage list mirrors.
check("Gemini models carry \"Google\"",
      source("src/ios/Providers/Gemini/GeminiModelsAPI.swift").contains("provider: \"Google\""))
check("xAI models carry \"xAI\"",
      source("src/ios/Providers/xAI/xAIModelsAPI.swift").contains("provider: \"xAI\""))
check("Kimi models carry \"Kimi\"",
      source("src/ios/Providers/Kimi/KimiModelsAPI.swift").contains("provider: \"Kimi\""))
check("Copilot models carry \"GitHub Copilot\" AND an explicit modality",
      source("src/ios/Providers/Copilot/CopilotModelsAPI.swift").contains("provider: \"GitHub Copilot\"")
        && source("src/ios/Providers/Copilot/CopilotModelsAPI.swift")
            .contains("modalityOverride: (supports?.vision ?? false) ? .vision : .textOnly"))

// 728bf68d8's lesson: the fallback must live on the property the app reads.
check("ModelEntry.model applies the modality override (not a view-only path)",
      entrySrc.contains("modalityOverride: overrides.modalityOverride ?? baseModel.modalityOverride"))
check("capabilities is computed on LLMModel itself, so every reader agrees",
      llm.contains("var capabilities: ModelCapabilities {"))
// The detail screen consumes the enum default for NEW custom models only.
check("the provider-default is used to seed a custom model's switches",
      source("src/ios/Views/Providers/ProviderInstanceDetailView.swift")
        .contains("let defaultModality = instance.providerType.defaultModality"))

print("")
if !gaps.isEmpty {
    print("⚠️ \(gaps.count) KNOWN GAP(S) recorded (not failures):")
    for g in gaps { print("   • \(g)") }
    print("")
}
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILED") }
exit(failures == 0 ? 0 : 1)
