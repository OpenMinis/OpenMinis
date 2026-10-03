#!/usr/bin/env swift
// [T-openrouter-voice-catalog] OpenMinis#280 — OpenRouter voice models were
// detected wrongly and routed to the wrong endpoints.
//
// Root cause:
//   * OpenRouterModelsAPI fetched only GET /models, which lists chat models
//     only. OpenRouter's 18 speech and 22 transcription models are listed
//     solely by ?output_modalities=speech / ?output_modalities=transcription
//     (live API, 2026-09-23: neither list shares an id with the default one).
//   * The voice pickers keyed off raw audio bits, so they held only false
//     positives: Muse Spark (a chat model that hears audio) as STT, Lyria (a
//     music generator) as TTS.
//   * OpenRouterVoiceProvider assumed /audio/speech does not exist, guessed
//     transcription models from their names (10 of the 22 live ones match
//     none of the words), and every 401 became "Invalid API key".
//
// Fix: fetch all three lists and tag each model with LLMModel.voiceRole
// ("tts" / "stt" / "none"). Voice candidacy and routing then follow the tag;
// untagged models (custom entries, other providers, older saves) keep the old
// rules. OpenRouter 401s count as auth errors only when the body says so.
//
// Part 1 runs a port of the mechanism against the live fixture ids. Part 2
// pins the port to the shipping Swift sources. Part 3 checks that iOS and
// Android agree: role values, allowlist, auth phrases, cache bump and the
// fixture lists (Android OpenRouterVoiceRoutingTest.kt).
//
// Run: swift OpenRouterVoiceCatalogTests.swift
import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

// ── Fixtures: live API, 2026-09-23 (byte-identical to the Android test) ─────

let speechIds = [
    "deepgram/flux-tts:free", "fish-audio/s1", "fish-audio/s2-pro",
    "fish-audio/s2.1-pro-free:free", "fish-audio/s2.1-pro", "microsoft/mai-voice-2-flash",
    "qwen/qwen-audio-3.0-tts-flash", "qwen/qwen-audio-3.0-tts-plus", "deepgram/aura-2",
    "minimax/speech-2.8-hd", "minimax/speech-2.8-turbo", "microsoft/mai-voice-2",
    "x-ai/grok-voice-tts-1.0", "google/gemini-3.1-flash-tts-preview",
    "canopylabs/orpheus-3b-0.1-ft", "sesame/csm-1b", "hexgrad/kokoro-82m",
    "mistralai/voxtral-mini-tts-2603",
]

let transcriptionIds = [
    "assemblyai/universal-3-5-pro", "meta/muse-voice-transcribe-1.0",
    "microsoft/mai-transcribe-2", "nvidia/nemotron-3.5-asr-streaming-multilingual-0.6b",
    "mistralai/voxtral-small-24b-2507-stt", "mistralai/voxtral-mini-3b-2507",
    "qwen/qwen3-asr-1.7b", "qwen/qwen3-asr-0.6b", "openai/gpt-transcribe",
    "fish-audio/transcribe-1", "x-ai/grok-stt-1.0", "deepgram/nova-3",
    "microsoft/mai-transcribe-1.5", "nvidia/parakeet-tdt-0.6b-v3",
    "mistralai/voxtral-mini-transcribe", "qwen/qwen3-asr-flash-2026-02-10",
    "google/chirp-3", "openai/gpt-4o-mini-transcribe", "openai/whisper-large-v3",
    "openai/whisper-large-v3-turbo", "openai/whisper-1", "openai/gpt-4o-transcribe",
]

// ── Part 1: port ─────────────────────────────────────────────────────────────

struct Modality: OptionSet, Hashable {
    let rawValue: Int
    static let textInput = Modality(rawValue: 1 << 0)
    static let textOutput = Modality(rawValue: 1 << 1)
    static let audioInput = Modality(rawValue: 1 << 2)
    static let audioOutput = Modality(rawValue: 1 << 3)
    static let imageInput = Modality(rawValue: 1 << 4)
}

enum VoiceRole {
    static let tts = "tts"
    static let stt = "stt"
    static let none = "none"
    static let openRouterChatAudioModels: Set<String> = ["openai/gpt-audio", "openai/gpt-audio-mini"]
}

struct Model {
    let id: String
    var modalityOverride: Modality?
    var voiceRole: String? = nil
    var modalities: Modality { modalityOverride ?? [.textInput, .textOutput] }

    var isVoiceInputCandidate: Bool {
        switch voiceRole {
        case nil: return modalities.contains(.audioInput)
        case .some(VoiceRole.stt): return true
        default: return VoiceRole.openRouterChatAudioModels.contains(id)
        }
    }
    var isVoiceOutputCandidate: Bool {
        switch voiceRole {
        case nil: return modalities.contains(.audioOutput)
        case .some(VoiceRole.tts): return true
        default: return VoiceRole.openRouterChatAudioModels.contains(id)
        }
    }
}

func mergeVoiceCatalogs(defaultModels: [Model], speech: [Model], transcription: [Model]) -> [Model] {
    var order: [String] = []
    var byId: [String: Model] = [:]
    func put(_ m: Model) {
        if byId[m.id] == nil { order.append(m.id) }
        byId[m.id] = m
    }
    for var m in defaultModels { m.voiceRole = VoiceRole.none; put(m) }
    for var m in speech {
        m.modalityOverride = [.textInput, .audioOutput]
        m.voiceRole = VoiceRole.tts
        put(m)
    }
    for var m in transcription {
        m.modalityOverride = [.audioInput, .textOutput]
        m.voiceRole = VoiceRole.stt
        put(m)
    }
    return order.compactMap { byId[$0] }
}

enum TTSRoute: Equatable { case speechEndpoint, chatAudio, unsupported }

func looksLikeChatAudio(_ modelId: String) -> Bool {
    let id = modelId.lowercased()
    guard id.contains("audio") else { return false }
    return id.contains("gpt") || id.contains("openai")
}

/// The untagged fallback only needs "is this name a TTS name"; "tts" is the
/// first TTS inference pattern, which is enough for the ids used below.
func nameSaysTTS(_ id: String) -> Bool { id.lowercased().contains("tts") }

func ttsRoute(modelId: String, model: Model?) -> TTSRoute {
    if VoiceRole.openRouterChatAudioModels.contains(modelId) { return .chatAudio }
    switch model?.voiceRole {
    case .some(VoiceRole.tts): return .speechEndpoint
    case nil: break
    default: return .unsupported
    }
    if looksLikeChatAudio(modelId) { return .chatAudio }
    let declaresAudioOut = model?.modalities.contains(.audioOutput) ?? false
    return (declaresAudioOut || nameSaysTTS(modelId)) ? .speechEndpoint : .unsupported
}

func isDedicatedTranscriptionModel(_ modelId: String) -> Bool {
    let id = modelId.lowercased()
    return id.contains("whisper") || id.contains("transcribe") || id.contains("deepgram/")
}

func routesASRThroughChat(_ model: Model) -> Bool {
    if VoiceRole.openRouterChatAudioModels.contains(model.id) { return true }
    if let role = model.voiceRole { return role != VoiceRole.stt }
    return !isDedicatedTranscriptionModel(model.id)
}

let authFailurePhrases = ["user not found", "no auth credentials", "invalid api key", "invalid_api_key"]
func isAuthFailureBody(status: Int, body: String?) -> Bool {
    guard status == 401 || status == 403, let text = body?.lowercased() else { return false }
    return authFailurePhrases.contains { text.contains($0) }
}

func speechVoice(_ requested: String?, modelId: String) -> String? {
    guard let v = requested?.trimmingCharacters(in: .whitespaces), !v.isEmpty, v != modelId else { return nil }
    return v
}

// Default-catalog entries as parsed (declared modalities).
let museSpark = Model(id: "meta/muse-spark-1.3", modalityOverride: [.textInput, .imageInput, .audioInput, .textOutput])
let lyria = Model(id: "google/lyria-3-pro-preview", modalityOverride: [.textInput, .imageInput, .textOutput, .audioOutput])
let gptAudio = Model(id: "openai/gpt-audio", modalityOverride: [.textInput, .audioInput, .textOutput, .audioOutput])
let gptAudioMini = Model(id: "openai/gpt-audio-mini", modalityOverride: [.textInput, .audioInput, .textOutput, .audioOutput])
let gemini = Model(id: "google/gemini-3.6-flash", modalityOverride: [.textInput, .imageInput, .audioInput, .textOutput])
let chatModels = [museSpark, lyria, gptAudio, gptAudioMini, gemini]

// The filtered lists declare "speech"/"transcription", which parse to no audio bit.
let catalog = mergeVoiceCatalogs(
    defaultModels: chatModels,
    speech: speechIds.map { Model(id: $0, modalityOverride: nil) },
    transcription: transcriptionIds.map { Model(id: $0, modalityOverride: nil) })
func tagged(_ id: String) -> Model { catalog.first { $0.id == id }! }

print("▶️  1. A: the merged catalog tags every model by its source list")
check("all 45 models present (5 chat + 18 speech + 22 transcription)", catalog.count == 45)
check("18 speech models → tts, text in / audio out",
      speechIds.allSatisfy { tagged($0).voiceRole == "tts" && tagged($0).modalities == [.textInput, .audioOutput] })
check("22 transcription models → stt, audio in / text out",
      transcriptionIds.allSatisfy { tagged($0).voiceRole == "stt" && tagged($0).modalities == [.audioInput, .textOutput] })
check("chat models → none, declared modalities kept",
      chatModels.allSatisfy { tagged($0.id).voiceRole == "none" && tagged($0.id).modalityOverride == $0.modalityOverride })
do {
    let dual = mergeVoiceCatalogs(defaultModels: [Model(id: "x/dual", modalityOverride: nil)],
                                  speech: [Model(id: "x/dual", modalityOverride: nil)], transcription: [])
    check("a filter list wins an id collision", dual.count == 1 && dual[0].voiceRole == "tts")
}

print("\n▶️  2. B: voice candidates")
check("every speech model is a TTS candidate, never ASR",
      speechIds.allSatisfy { tagged($0).isVoiceOutputCandidate && !tagged($0).isVoiceInputCandidate })
check("every transcription model is an ASR candidate, never TTS",
      transcriptionIds.allSatisfy { tagged($0).isVoiceInputCandidate && !tagged($0).isVoiceOutputCandidate })
check("Muse Spark is not an ASR candidate", tagged(museSpark.id).isVoiceInputCandidate, false)
check("Lyria is not a TTS candidate", tagged(lyria.id).isVoiceOutputCandidate, false)
check("Gemini (hears audio) is not an ASR candidate", tagged(gemini.id).isVoiceInputCandidate, false)
check("…while their audio bits are unchanged (multimodal chat still sees them)",
      tagged(museSpark.id).modalities.contains(.audioInput) && tagged(lyria.id).modalities.contains(.audioOutput))
check("gpt-audio / gpt-audio-mini serve both directions",
      [gptAudio, gptAudioMini].allSatisfy { tagged($0.id).isVoiceInputCandidate && tagged($0.id).isVoiceOutputCandidate })
do {
    let custom = Model(id: "fish-audio/s9-custom", modalityOverride: [.textInput, .audioOutput])
    check("an untagged custom entry keeps the modality rule (escape hatch)",
          custom.voiceRole == nil && custom.isVoiceOutputCandidate)
    check("…and routes to the speech endpoint", ttsRoute(modelId: custom.id, model: custom) == .speechEndpoint)
}

print("\n▶️  3. C: routing")
check("every speech model → /audio/speech",
      speechIds.allSatisfy { ttsRoute(modelId: $0, model: tagged($0)) == .speechEndpoint })
check("all 22 transcription models → /audio/transcriptions",
      transcriptionIds.allSatisfy { !routesASRThroughChat(tagged($0)) })
do {
    let nameless = transcriptionIds.filter { !isDedicatedTranscriptionModel($0) }
    check("10 of them carry no name hint (\(nameless.count))", nameless.count == 10)
    check("…which the name rule alone sent to chat (the old bug)",
          nameless.allSatisfy { routesASRThroughChat(Model(id: $0, modalityOverride: nil)) })
    check("…and the tag now sends to transcriptions", nameless.allSatisfy { !routesASRThroughChat(tagged($0)) })
}
check("chat-audio keeps chat for TTS and ASR",
      [gptAudio, gptAudioMini].allSatisfy { ttsRoute(modelId: $0.id, model: tagged($0.id)) == .chatAudio && routesASRThroughChat(tagged($0.id)) })
check("Lyria / Muse Spark are refused for TTS, not guessed",
      ttsRoute(modelId: lyria.id, model: tagged(lyria.id)) == .unsupported
        && ttsRoute(modelId: museSpark.id, model: tagged(museSpark.id)) == .unsupported)
check("untagged whisper still goes to transcriptions (name fallback)",
      !routesASRThroughChat(Model(id: "openai/whisper-1", modalityOverride: nil)))
check("untagged amazon/nova stays on chat (deepgram is vendor-qualified)",
      routesASRThroughChat(Model(id: "amazon/nova-2-lite-v1", modalityOverride: nil)))
check("speech voice: vendor voice id forwarded verbatim",
      speechVoice("fish-voice-7f3a", modelId: "fish-audio/s1") == "fish-voice-7f3a")
check("speech voice: an OpenAI-unknown name is NOT replaced by alloy",
      speechVoice("Wise_Woman", modelId: "minimax/speech-2.8-hd") == "Wise_Woman")
check("speech voice: empty / nil / the model id itself → omitted",
      speechVoice("", modelId: "m") == nil && speechVoice(nil, modelId: "m") == nil
        && speechVoice("fish-audio/s1", modelId: "fish-audio/s1") == nil)

print("\n▶️  4. D: 401 is only an auth error when the body says so")
check("401 'User not found.' → auth",
      isAuthFailureBody(status: 401, body: #"{"error":{"message":"User not found.","code":401}}"#))
check("401 'No auth credentials found' → auth",
      isAuthFailureBody(status: 401, body: #"{"error":{"message":"No auth credentials found","code":401}}"#))
check("403 'Invalid API key' → auth", isAuthFailureBody(status: 403, body: "Invalid API key"))
check("401 with another message → NOT auth (message surfaces)",
      isAuthFailureBody(status: 401, body: #"{"error":{"message":"meta/muse-spark-1.3 is not a transcription model"}}"#), false)
check("401 with no body → NOT auth", isAuthFailureBody(status: 401, body: nil), false)
check("500 mentioning the phrase → NOT auth", isAuthFailureBody(status: 500, body: "User not found"), false)

// ── Part 2: the shipping sources carry the ported code ───────────────────────

let repo = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent()
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
func read(_ rel: String) -> String {
    (try? String(contentsOf: repo.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let types = read("src/ios/Providers/LLMTypes.swift")
let entry = read("src/ios/Providers/ModelEntry.swift")
let api = read("src/ios/Providers/OpenRouter/OpenRouterModelsAPI.swift")
let vendors = read("src/ios/Providers/Voice/VoiceProvider+Vendors.swift")
let base = read("src/ios/Providers/Voice/VoiceProvider.swift")
let player = read("src/ios/Providers/Voice/VoiceOutputPlayer.swift")
let quick = read("src/ios/Views/Providers/ModelQuickTestSheet.swift")

print("\n▶️  5. iOS sources match the port")
check("LLMModel.voiceRole exists and is optional", types.contains("    var voiceRole: String?\n"))
check("candidate predicates as ported",
      types.contains("case .some(VoiceRole.stt): return true")
        && types.contains("case .some(VoiceRole.tts): return true")
        && types.contains("case nil: return capabilities.supportedModalities.contains(.audioInput)")
        && types.contains("case nil: return capabilities.supportedModalities.contains(.audioOutput)"))
check("ModelEntry.model carries voiceRole across its rebuild",
      entry.contains("rebuilt.voiceRole = baseModel.voiceRole"))
check("all three catalogs are fetched",
      api.contains(#"fetchList(path: "/models", apiKey: apiKey)"#)
        && api.contains(#"fetchList(path: "/models?output_modalities=speech", apiKey: apiKey)"#)
        && api.contains(#"fetchList(path: "/models?output_modalities=transcription", apiKey: apiKey)"#))
check("a failed filter list degrades to empty, not a failed fetch",
      api.contains("speech = []") && api.contains("transcription = []"))
check("merge stamps role + exact shape",
      api.contains("m.modalityOverride = [.textInput, .audioOutput]\n            m.voiceRole = VoiceRole.tts")
        && api.contains("m.modalityOverride = [.audioInput, .textOutput]\n            m.voiceRole = VoiceRole.stt")
        && api.contains("for var m in defaultModels { m.voiceRole = VoiceRole.none; put(m) }"))
check("the models cache moved to a new location", api.contains("openrouter-models-cache-v2"))
check("TTS routing as ported",
      vendors.contains("case .some(VoiceRole.tts): return .speechEndpoint")
        && vendors.contains("case .speechEndpoint:\n            return try await executeRequest(buildVoiceOutputRequest(request))")
        && vendors.contains("does not support speech synthesis on OpenRouter"))
check("ASR routing as ported",
      vendors.contains("if let role = model.voiceRole { return role != VoiceRole.stt }"))
check("speech voice passthrough as ported",
      vendors.contains("if let voice = Self.speechVoice(request.voice, modelId: modelId) { body[\"voice\"] = voice }"))
check("voice requests carry HTTP-Referer + X-Title",
      vendors.contains("override func applyVoiceAuth(_ request: inout URLRequest) {")
        && vendors.contains(#"request.setValue("Minis App", forHTTPHeaderField: "X-Title")"#))
check("401 classification is overridable and OpenRouter overrides it",
      base.contains("if isAuthFailure(status: http.statusCode, body: data) {")
        && vendors.contains("override func isAuthFailure(status: Int, body: Data?) -> Bool {"))
check("the chat-audio error path uses the same classification",
      vendors.contains("if isAuthFailure(status: http.statusCode, body: errData) {"))
check("the stale 'endpoint does not exist' claim is gone",
      !vendors.contains("OpenRouter does not implement OpenAI's dedicated TTS endpoint at all"))
check("read-aloud and Quick Test hand the model to TTS",
      player.contains("resolvedModel: entry.model)") && quick.contains("resolvedModel: entry.model)"))

// ── Part 3: iOS ↔ Android agree ──────────────────────────────────────────────

let droidModality = read("src/android/app/src/main/java/com/openminis/app/data/model/VoiceModality.kt")
let droidApi = read("src/android/app/src/main/java/com/openminis/app/provider/openrouter/OpenRouterModelsApi.kt")
let droidVendors = read("src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderVendors.kt")
let droidTest = read("src/android/app/src/test/java/com/openminis/app/provider/voice/OpenRouterVoiceRoutingTest.kt")

/// Every double-quoted literal between `start` and the next `end`.
func literals(_ src: String, from start: String, to end: String) -> [String] {
    guard let a = src.range(of: start),
          let b = src.range(of: end, range: a.upperBound..<src.endIndex) else { return [] }
    let chunk = String(src[a.upperBound..<b.lowerBound])
    let rx = try! NSRegularExpression(pattern: #""([^"]*)""#)
    return rx.matches(in: chunk, range: NSRange(chunk.startIndex..., in: chunk))
        .map { String(chunk[Range($0.range(at: 1), in: chunk)!]) }
}

print("\n▶️  6. the two platforms agree")
if droidModality.isEmpty || droidTest.isEmpty {
    check("Android sources readable", false)
} else {
    check("role values identical",
          droidModality.contains(#"const val TTS = "tts""#) && droidModality.contains(#"const val STT = "stt""#)
            && droidModality.contains(#"const val NONE = "none""#)
            && types.contains(#"static let tts = "tts""#) && types.contains(#"static let stt = "stt""#)
            && types.contains(#"static let none = "none""#))
    check("chat-audio allowlist identical",
          literals(droidModality, from: "OPENROUTER_CHAT_AUDIO_MODELS = setOf(", to: ")")
            == literals(types, from: "openRouterChatAudioModels: Set<String> = [", to: "]"))
    let droidPhrases = literals(droidVendors, from: "AUTH_FAILURE_PHRASES = listOf(", to: ")")
    let iosPhrases = literals(vendors, from: "authFailurePhrases = [", to: "]")
    check("auth phrases identical (\(iosPhrases.count))", !iosPhrases.isEmpty && droidPhrases == iosPhrases)
    check("…and identical to this port", iosPhrases == authFailurePhrases)
    check("Android fetches the same two filters",
          droidApi.contains(#""$MODELS_URL?output_modalities=speech""#)
            && droidApi.contains(#""$MODELS_URL?output_modalities=transcription""#))
    check("Android cache moved too", droidApi.contains(#"ProviderModelsCache("openrouter-v2")"#))
    check("speech fixtures identical to the Android test",
          literals(droidTest, from: "private val speechIds = listOf(", to: ")\n") == speechIds)
    check("transcription fixtures identical to the Android test",
          literals(droidTest, from: "private val transcriptionIds = listOf(", to: ")\n") == transcriptionIds)
}

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)") }
exit(failures == 0 ? 0 : 1)
