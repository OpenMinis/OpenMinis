#!/usr/bin/env swift
// [T-asr-vendor-family-names] issue #305 — several widely-used Chinese ASR
// models never registered as voice models, so they were missing from the
// voice-input picker even though the provider served them.
//
// `inferDedicatedVoiceModality` matches an id/name against `asrInferencePatterns`
// and returns the EXACT ASR shape `[.audioInput, .textOutput]`. That list only
// held delimiter-bracketed forms of the literal string "asr" (`-asr`, `asr-`,
// `_asr`, `asr_`) plus whisper/stt/transcribe. Families named after the model
// architecture rather than the task matched nothing:
//
//   paraformer-realtime-v2   (Alibaba DashScope)  -> no match
//   SenseVoiceSmall          (FunAudioLLM)        -> no match
//
// A non-match is not a loud failure: it falls through to the broad
// output-union inference, the model gets a general text modality, and the voice
// pickers — which gate on `inputs == .audioInput` EXACTLY — simply never show
// it. Silent absence, which is why it went unnoticed.
//
// This pins the five real model names from the issue. Both halves matter:
// an ASR model must resolve to audioInput, and it must NOT be mistaken for TTS
// (a TTS verdict would put it in the wrong picker and make it text-in/audio-out,
// the exact inverse of what it does).
//
// Run: swift VoiceModalityInferenceGuardTests.swift
//
// Convention: a bare `swift` script — `deps/libs/libish_emu.a` is device-arm64
// only, so the app cannot link for the simulator. The matcher is ported verbatim
// below and section [5] re-reads the shipping source, so a pattern deleted from
// LLMTypes.swift fails here rather than silently passing a stale copy.
import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(label)") }
    else { print("  ❌ \(label)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
func codeOnly(_ src: String) -> String {
    src.components(separatedBy: "\n")
        .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
        .joined(separator: "\n")
}

// MARK: - Ported model (LLMTypes.swift)

struct Modality: OptionSet, Equatable, CustomStringConvertible {
    let rawValue: Int
    static let textInput    = Modality(rawValue: 1 << 0)
    static let textOutput   = Modality(rawValue: 1 << 1)
    static let audioInput   = Modality(rawValue: 1 << 4)
    static let audioOutput  = Modality(rawValue: 1 << 7)
    var description: String {
        var p: [String] = []
        if contains(.textInput)   { p.append("textInput") }
        if contains(.textOutput)  { p.append("textOutput") }
        if contains(.audioInput)  { p.append("audioInput") }
        if contains(.audioOutput) { p.append("audioOutput") }
        return p.isEmpty ? "[]" : "[" + p.joined(separator: ", ") + "]"
    }
}

/// Verbatim port of the shipping pattern list, WITH the #305 additions.
var asrPatterns: [String] = [
    "-asr", "asr-", "_asr", "asr_", "whisper", "transcrib", "speech-to-text",
    "speech2text", "stt-", "-stt", "_stt", "stt_", "voice-input", "voice_input",
    "paraformer", "sensevoice", "fun-asr", "qwen-asr",
]
let ttsPatterns: [String] = [
    "tts", "-tts", "_tts", "text-to-speech", "text2speech", "audio-gen",
    "audio-generation", "seed-tts", "voice-output", "voice_output",
]

/// Verbatim port of `inferDedicatedVoiceModality`.
func inferVoice(id: String, displayName: String) -> Modality? {
    let hay = (id + " " + displayName).lowercased()
    if asrPatterns.contains(where: { hay.contains($0) }) { return [.audioInput, .textOutput] }
    if ttsPatterns.contains(where: { hay.contains($0) }) { return [.textInput, .audioOutput] }
    return nil
}

let ASR: Modality = [.audioInput, .textOutput]
let TTS: Modality = [.textInput, .audioOutput]

/// The five real model names from the issue.
let asrModels = [
    "qwen3-asr-flash",
    "fun-asr-flash",
    "paraformer-realtime-v2",
    "SenseVoiceSmall",
    "Qwen3-ASR-1.7B",
]

print("▶️  1. every reported ASR model resolves to audioInput")
do {
    for m in asrModels {
        // Called with id == displayName, the common case for a raw provider id.
        checkEq("\(m)", inferVoice(id: m, displayName: m), ASR)
    }
}

print("\n▶️  2. …and none of them is mistaken for TTS")
do {
    // A TTS verdict is worse than no verdict: it lands the model in the voice
    // OUTPUT picker as text-in/audio-out — the exact inverse of an ASR model.
    for m in asrModels {
        let got = inferVoice(id: m, displayName: m)
        check("\(m) is not TTS", got != TTS)
        check("\(m) advertises audio INPUT", got?.contains(.audioInput) ?? false)
        check("\(m) does not advertise audio OUTPUT", !(got?.contains(.audioOutput) ?? true))
    }
}

print("\n▶️  3. the exact shape the voice picker gates on")
do {
    // The pickers test `inputs == .audioInput` EXACTLY — a model that also
    // advertised textInput would be filtered out. So the shape must be the
    // two-flag ASR set, not a union with a text base.
    for m in asrModels {
        checkEq("\(m) is exactly [audioInput, textOutput]", inferVoice(id: m, displayName: m), ASR)
        check("\(m) does NOT carry textInput",
              !(inferVoice(id: m, displayName: m)?.contains(.textInput) ?? true))
    }
}

print("\n▶️  4. real TTS models still resolve to TTS, and plain chat models to nil")
do {
    // The fix must not have widened ASR onto everything.
    checkEq("cosyvoice-v2 (TTS)", inferVoice(id: "cosyvoice-v2-tts", displayName: "CosyVoice"), TTS)
    checkEq("seed-tts", inferVoice(id: "seed-tts", displayName: "Seed TTS"), TTS)
    check("gpt-5.5 is not a voice model", inferVoice(id: "gpt-5.5", displayName: "GPT-5.5") == nil)
    check("deepseek-v4 is not a voice model",
          inferVoice(id: "deepseek-v4", displayName: "DeepSeek V4") == nil)
    check("claude-opus-5 is not a voice model",
          inferVoice(id: "claude-opus-5", displayName: "Claude Opus 5") == nil)

    // Case and separator variants of the new roots, as they appear in the wild.
    checkEq("SenseVoice (spaced display name)",
            inferVoice(id: "iic/SenseVoiceSmall", displayName: "Sense Voice Small"), ASR)
    checkEq("sensevoice_small (underscore)",
            inferVoice(id: "sensevoice_small", displayName: "sensevoice_small"), ASR)
    checkEq("Paraformer (capitalised)",
            inferVoice(id: "Paraformer-Large", displayName: "Paraformer Large"), ASR)
}

print("\n▶️  5. shipping source carries the four new roots")
do {
    let src = codeOnly(source("Providers/LLMTypes.swift"))
    if src.isEmpty { print("  ⏭  source not readable") } else {
        for root in ["paraformer", "sensevoice", "fun-asr", "qwen-asr"] {
            check("asrInferencePatterns contains \"\(root)\"",
                  src.contains("\"\(root)\""))
        }
        // The roots must be in the ASR list, not accidentally in the TTS one —
        // grepping the file alone cannot tell those apart.
        let asrBlock: String = {
            guard let s = src.range(of: "asrInferencePatterns: [String] = ["),
                  let e = src.range(of: "]", range: s.upperBound..<src.endIndex)
            else { return "" }
            return String(src[s.upperBound..<e.lowerBound])
        }()
        check("the ASR list was located", !asrBlock.isEmpty)
        for root in ["paraformer", "sensevoice", "fun-asr", "qwen-asr"] {
            check("…\"\(root)\" is in the ASR list specifically", asrBlock.contains("\"\(root)\""))
        }
        // ASR must still be tested BEFORE TTS, or a future root containing
        // "tts" would flip the verdict.
        let asrIdx = src.range(of: "if asrInferencePatterns.contains")
        let ttsIdx = src.range(of: "if ttsInferencePatterns.contains")
        check("ASR is still matched before TTS",
              (asrIdx?.lowerBound).flatMap { a in (ttsIdx?.lowerBound).map { a < $0 } } ?? false)
    }
}

print("\n▶️  6. audio_input string ↔ .audioInput mapping is intact")
do {
    // Step 1's second half: a user-configured modality override containing the
    // literal "audio_input" must decode to the audioInput flag. Two independent
    // sites parse it; both are pinned because they are edited separately.
    let coll = codeOnly(source("Shared/Config/Collections/ModelsCollection.swift"))
    let bridge = codeOnly(source("NativeOffloads/ModelUseOffloadBridge.swift"))
    if coll.isEmpty || bridge.isEmpty { print("  ⏭  sources not readable") } else {
        check("ModelsCollection maps audio_input → .audioInput",
              coll.contains("(\"audio_input\",  .audioInput)"))
        check("…and encodes it back under the same name",
              coll.contains("modalityNamesInOrder.compactMap { m.contains($0.flag) ? $0.name : nil }"))
        check("model_use accepts both \"audio\" and \"audio_input\"",
              bridge.contains("case \"audio\", \"audio_input\":"))
        check("…and reports audio_input on the way out",
              bridge.contains("if modality.contains(.audioInput)  { supported.append(\"audio_input\") }"))
    }
}

print("\n▶️  7. [T-openrouter-voice-catalog] voice candidacy does not narrow the audio bits")
do {
    // OpenMinis#280 moved the voice LISTS onto isVoice*Candidate. The modality
    // bits must keep meaning "this chat model hears / emits audio" — multimodal
    // chat, model_use and the capability badges read them.
    let api = codeOnly(source("Providers/OpenRouter/OpenRouterModelsAPI.swift"))
    let types = codeOnly(source("Providers/LLMTypes.swift"))
    if api.isEmpty || types.isEmpty { print("  ⏭  sources not readable") } else {
        check("OpenRouter audio input still sets .audioInput",
              api.contains("if bare.contains(\"audio\") { modality.insert(.audioInput) }"))
        check("OpenRouter audio output still sets .audioOutput",
              api.contains("if bare.contains(\"audio\") { modality.insert(.audioOutput) }"))
        check("an untagged model falls back to the audio bits (custom-entry escape hatch)",
              types.contains("case nil: return capabilities.supportedModalities.contains(.audioInput)"))
        check("dedicated-voice inference is unchanged by the catalog tag",
              types.contains("static func inferDedicatedVoiceModality(id: String, displayName: String) -> ModelModality? {"))
    }
}

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)") }
exit(failures == 0 ? 0 : 1)
