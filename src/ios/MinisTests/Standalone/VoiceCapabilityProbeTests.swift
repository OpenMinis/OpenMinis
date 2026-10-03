// [T-voice-capability-probe] `VoiceProviderFactory.supportsVoice` must agree
// with `make(for:)`.
//
// Run: swift VoiceCapabilityProbeTests.swift
//
// The probe exists so a capability question does not cost a Keychain read.
// Its whole value depends on answering exactly what `make` would, so the risk
// is DIVERGENCE: a future branch added to `make` that the probe does not know
// about silently hides a working voice provider from the UI (or offers one that
// cannot be built).
//
// Both are switches over `providerType` + normalized base URL, so the routing
// decision is reproduced here and checked against the shipping source: every
// host `make` special-cases must be accounted for, and the set of types each
// one rejects must match.

import Foundation

// MARK: - Mirror of the routing decision

enum ProviderTypeM: String, CaseIterable {
    case openAI, openAIResponses, anthropic, gemini, xAI
    case openRouter, kimiCode, githubCopilot, antigravity, unsupported
}

/// Mirror of `supportsVoice(for:)`. `compoundParts` stands in for the Xunfei
/// key: nil means "no key stored".
func supportsVoice(type: ProviderTypeM, base: String, compoundParts: Int? = nil) -> Bool {
    let b = base.lowercased()
    switch type {
    case .openAI, .openAIResponses:
        if b.contains("xfyun") { return (compoundParts ?? 0) >= 3 }
        return true
    case .xAI, .openRouter, .gemini:
        return true
    case .anthropic:
        return b.contains("minimax")
    case .antigravity, .kimiCode, .githubCopilot, .unsupported:
        return false
    }
}

// MARK: - Harness

var failures = 0
func check(_ label: String, _ cond: Bool, _ expected: Bool = true) {
    if cond == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(cond)"); failures += 1 }
}

func sourceOf(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}

// MARK: - 1. The types that never serve voice

print("\n[1] Types with no voice path are rejected")

for t in [ProviderTypeM.antigravity, .kimiCode, .githubCopilot, .unsupported] {
    check("\(t.rawValue) → false", supportsVoice(type: t, base: ""), false)
    // …and not rescued by a base URL that looks like a voice vendor.
    check("\(t.rawValue) → false even with a voice-looking base",
          supportsVoice(type: t, base: "https://api.minimax.io"), false)
}

// MARK: - 2. Anthropic is MiniMax-only

print("\n[2] Anthropic serves voice only behind a MiniMax base")

check("plain Anthropic → false", supportsVoice(type: .anthropic, base: ""), false)
check("api.anthropic.com → false",
      supportsVoice(type: .anthropic, base: "https://api.anthropic.com"), false)
check("MiniMax behind an Anthropic instance → true",
      supportsVoice(type: .anthropic, base: "https://api.minimax.io"))
check("case-insensitive", supportsVoice(type: .anthropic, base: "https://API.MiniMax.IO"))

// MARK: - 3. Xunfei is the one key-dependent branch

print("\n[3] Xunfei depends on the compound key, everything else does not")

check("xfyun with 3 parts → true",
      supportsVoice(type: .openAI, base: "https://spark.xfyun.cn", compoundParts: 3))
check("xfyun with 2 parts → false",
      supportsVoice(type: .openAI, base: "https://spark.xfyun.cn", compoundParts: 2), false)
check("xfyun with no key → false",
      supportsVoice(type: .openAI, base: "https://spark.xfyun.cn", compoundParts: nil), false)
// The point of the probe: no other branch is allowed to need a key.
check("OpenAI with no key → still true (auth fails later, not here)",
      supportsVoice(type: .openAI, base: "", compoundParts: nil))
check("Groq with no key → still true",
      supportsVoice(type: .openAI, base: "https://api.groq.com/openai", compoundParts: nil))

// MARK: - 4. The always-true families

print("\n[4] Families that always resolve a provider")

for (t, b) in [(ProviderTypeM.openAI, ""), (.openAIResponses, ""),
               (.xAI, "https://api.x.ai"), (.openRouter, "https://openrouter.ai/api"),
               (.gemini, "https://generativelanguage.googleapis.com/v1beta")] {
    check("\(t.rawValue) → true", supportsVoice(type: t, base: b))
}

// Every vendor `make` special-cases must still resolve.
for host in ["groq.com", "dashscope", "minimax", "openspeech.bytedance", "volcano",
             "xiaomimimo", "elevenlabs", "tts.speech.microsoft.com", "deepgram"] {
    check("OpenAI-family host \(host) → true",
          supportsVoice(type: .openAI, base: "https://\(host)/v1"))
}

// MARK: - 5. Every case is decided (no silent default)

print("\n[5] Every provider type is decided explicitly")

for t in ProviderTypeM.allCases {
    // Just has to not trap; the value itself is asserted above.
    _ = supportsVoice(type: t, base: "")
}
print("  ✅ all \(ProviderTypeM.allCases.count) types evaluated")

// MARK: - 6. The shipping source still agrees

print("\n[6] The shipping source matches this mirror")

let factory = sourceOf("Providers/Voice/VoiceProviderFactory.swift")
check("supportsVoice exists", factory.contains("static func supportsVoice(for instance: ProviderInstance) -> Bool"))
check("it rejects the four no-voice types together",
      factory.contains("case .antigravity, .kimiCode, .githubCopilot, .unsupported:"))
check("anthropic is gated on minimax",
      factory.contains("return normalizedBase.contains(\"minimax\")"))
check("xfyun is the only key-dependent probe branch",
      factory.contains("Self.splitCompound(key).count >= 3"))

// The probe must stay next to `make`, and `make` must still be the thing it
// mirrors — if `make` grows a new `return nil` in the OpenAI family, the probe's
// blanket `return true` for that family becomes wrong.
let openAIBranch: String = {
    guard let start = factory.range(of: "case .openAI, .openAIResponses:"),
          let end = factory.range(of: "case .xAI:", range: start.upperBound..<factory.endIndex)
    else { return "" }
    return String(factory[start.upperBound..<end.lowerBound])
}()
check("make's OpenAI branch was located", !openAIBranch.isEmpty)
// Two occurrences expected: the probe's xfyun guard and make's own.
let nilReturns = openAIBranch.components(separatedBy: "return nil").count - 1
check("make's OpenAI family has exactly ONE `return nil` (xfyun); found \(nilReturns)",
      nilReturns == 1)

let store = sourceOf("Providers/ProviderConfigStore.swift")
check("shadowVoiceProviders uses the probe, not make",
      store.contains("VoiceProviderFactory.supportsVoice(for: inst)"))
check("shadowVoiceProviders no longer builds a provider to test nil",
      store.contains("VoiceProviderFactory.make(for: inst) != nil"), false)

let resolver = sourceOf("Providers/Voice/VoiceProviderResolver.swift")
check("resolver's two pure probes converted",
      resolver.components(separatedBy: "supportsVoice(for: instance)").count - 1 == 2)
// These three genuinely read the built provider's capability flags and MUST
// keep calling make.
check("resolver keeps make where the provider itself is inspected",
      resolver.components(separatedBy: "VoiceProviderFactory.make(for: instance)").count - 1 == 3)

// MARK: - [T-openrouter-voice-catalog] voice lists use the candidate predicate

print("\n[N] Voice lists ask isVoice*Candidate, not raw audio bits (OpenMinis#280)")
// A raw audio bit put chat models that merely hear audio (Muse Spark) and
// music generators (Lyria) into the voice lists. Every voice-LIST site must go
// through the candidate predicate; the audio bits themselves stay untouched
// for multimodal chat.
check("hasVoiceModels uses isVoiceCandidate",
      store.contains("e.providerInstanceId == instanceId && e.baseModel.isVoiceCandidate"))
check("shadow input/output lists use the candidate predicates",
      store.contains("entries.filter { $0.baseModel.isVoiceInputCandidate }")
        && store.contains("entries.filter { $0.baseModel.isVoiceOutputCandidate }"))
check("resolver canServe uses the candidate predicates",
      resolver.contains("case .input:  return model.isVoiceInputCandidate")
        && resolver.contains("case .output: return model.isVoiceOutputCandidate"))
let picker = sourceOf("Views/Providers/UnifiedModelPicker.swift")
check("model picker canServe uses the candidate predicates",
      picker.contains("direction == .input ? model.isVoiceInputCandidate : model.isVoiceOutputCandidate"))

// MARK: - Result

print("")
if failures == 0 { print("✅ ALL CHECKS PASSED") }
else { print("❌ \(failures) FAILURE(S)"); exit(1) }
