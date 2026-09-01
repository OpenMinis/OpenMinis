import Foundation

// MARK: - Request / Response models
//
// Shared value types for the voice subsystem. `VoiceInput*` covers speech
// recognition (ASR); `VoiceOutput*` covers speech synthesis (TTS).

/// A speech-recognition (ASR) request: audio in, text out.
struct VoiceInputRequest {
    /// 16 kHz mono WAV produced by the VAD (or any provider-acceptable audio).
    let audioData: Data
    let model: String?
    /// nil = let the provider auto-detect the spoken language.
    let language: String?
    let responseFormat: VoiceInputFormat
    /// Optional biasing prompt to improve recognition of domain terms.
    let prompt: String?
    /// The resolved LLMModel — allows the provider to detect whether this model
    /// uses dedicated ASR (Whisper API) or chat-based ASR (chat completions with
    /// audio input + constrained system prompt).
    let resolvedModel: LLMModel?
    /// System (Apple) ASR only: force on-device (`true`) vs server/cloud (`false`).
    /// nil = provider default (prefer on-device when supported). Ignored by cloud
    /// ASR providers.
    let onDeviceRecognition: Bool?

    init(audioData: Data,
         model: String? = nil,
         language: String? = nil,
         responseFormat: VoiceInputFormat = .json,
         prompt: String? = nil,
         resolvedModel: LLMModel? = nil,
         onDeviceRecognition: Bool? = nil) {
        self.audioData = audioData
        self.model = model
        self.language = language
        self.responseFormat = responseFormat
        self.prompt = prompt
        self.resolvedModel = resolvedModel
        self.onDeviceRecognition = onDeviceRecognition
    }
}

enum VoiceInputFormat: String {
    case json, text, srt, vtt
}

struct VoiceInputResponse {
    let text: String
    let language: String?
    let duration: Double?
}

/// A speech-synthesis (TTS) request: text in, audio out.
struct VoiceOutputRequest {
    let input: String
    let model: String?
    let voice: String?
    /// 0.25 ~ 4.0, nil = 1.0 (provider default).
    let speed: Float?
    let responseFormat: VoiceOutputFormat

    init(input: String,
         model: String? = nil,
         voice: String? = nil,
         speed: Float? = nil,
         responseFormat: VoiceOutputFormat = .mp3) {
        self.input = input
        self.model = model
        self.voice = voice
        self.speed = speed
        self.responseFormat = responseFormat
    }
}

enum VoiceOutputFormat: String {
    case mp3, opus, wav, aac
}

// MARK: - Capability protocols

protocol VoiceInputCapable {
    func transcribe(_ request: VoiceInputRequest) async throws -> VoiceInputResponse
    var supportsVoiceInput: Bool { get }
}

protocol VoiceOutputCapable {
    func synthesize(_ request: VoiceOutputRequest) async throws -> Data
    var supportsVoiceOutput: Bool { get }
}

/// A voice provider that can do both directions (ASR + TTS). The factory returns
/// this so the built-in `SystemVoiceProvider` (not a `VoiceProvider` subclass) and
/// the cloud `VoiceProvider` subclasses share one factory entry point.
typealias VoiceProviderCapable = VoiceInputCapable & VoiceOutputCapable

// MARK: - Errors

enum VoiceProviderError: LocalizedError {
    case unsupported(String)
    case httpError(Int, Data?)
    case parseError(String)
    case authError
    case noAudioData

    var errorDescription: String? {
        switch self {
        case .unsupported(let msg):
            return AppLocalized("Unsupported: \(msg)", comment: "Voice provider unsupported capability")
        case .httpError(let code, let body):
            // Surface the server's own error message when present (most APIs put
            // the reason in the body, e.g. {"error":{"message":"..."}}).
            if let detail = Self.serverMessage(from: body) {
                return AppLocalized("Request failed (HTTP \(code)): \(detail)", comment: "Voice provider HTTP error with detail")
            }
            if code == 404 {
                return AppLocalized("Request failed (HTTP 404) — check the provider's Base URL and model name", comment: "Voice provider 404 hint")
            }
            return AppLocalized("Request failed (HTTP \(code))", comment: "Voice provider HTTP error")
        case .parseError(let msg):
            return AppLocalized("Parse failed: \(msg)", comment: "Voice provider response parse error")
        case .authError:
            return AppLocalized("Authentication failed, please check the API key", comment: "Voice provider auth error")
        case .noAudioData:
            return AppLocalized("No audio data", comment: "Voice provider missing audio")
        }
    }

    /// Best-effort extraction of an API error message from a JSON/text body.
    private static func serverMessage(from body: Data?) -> String? {
        guard let body, !body.isEmpty else { return nil }
        if let obj = try? JSONSerialization.jsonObject(with: body) as? [String: Any] {
            if let err = obj["error"] as? [String: Any], let msg = err["message"] as? String { return msg }
            if let msg = obj["error"] as? String { return msg }
            if let msg = obj["message"] as? String { return msg }
        }
        if let text = String(data: body, encoding: .utf8) {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty, trimmed.count < 200 { return trimmed }
        }
        return nil
    }
}
