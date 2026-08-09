import Foundation

// MARK: - VoiceProvider base class
//
// Implements the OpenAI-compatible /v1/audio/transcriptions (ASR) and
// /v1/audio/speech (TTS) endpoints. Vendor subclasses override the hook
// methods below to adapt to non-OpenAI request/response shapes.
//
// The two entry points `transcribe`/`synthesize` are intentionally NOT final:
// vendors whose response envelope differs (MiniMax/Doubao wrap audio in base64
// JSON) override them wholesale. Vendors that only differ in request shape or
// endpoint path override the narrower `build*`/`parse*` hooks instead.

class VoiceProvider: VoiceInputCapable, VoiceOutputCapable {

    let providerId: String
    let baseURL: String
    let apiKey: String?

    private let logger = AppLogger(category: "VoiceProvider")

    /// Dedicated session: TTS responses can be large and slow, so use a
    /// generous request timeout instead of URLSession.shared's default.
    private static let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 120
        config.timeoutIntervalForResource = 120
        return URLSession(configuration: config)
    }()

    init(providerId: String, baseURL: String, apiKey: String? = nil) {
        self.providerId = providerId
        self.baseURL = baseURL
        self.apiKey = apiKey
    }

    // MARK: - Entry points

    func transcribe(_ request: VoiceInputRequest) async throws -> VoiceInputResponse {
        // Chat-based ASR: for multimodal models (audio+text input like GPT Audio
        // Mini) that use chat completions instead of the Whisper transcription API.
        if let model = request.resolvedModel, usesChatBasedASR(model: model) {
            logger.info("Using chat-based ASR for \(model.displayName)")
            return try await transcribeChatBased(request)
        }
        guard supportsVoiceInput else {
            throw VoiceProviderError.unsupported("\(type(of: self)) does not support voice input")
        }
        let urlRequest = try buildVoiceInputRequest(request)
        let data = try await executeRequest(urlRequest)
        return try parseVoiceInputResponse(data, request: request)
    }

    func synthesize(_ request: VoiceOutputRequest) async throws -> Data {
        guard supportsVoiceOutput else {
            throw VoiceProviderError.unsupported("\(type(of: self)) does not support voice output")
        }
        let urlRequest = try buildVoiceOutputRequest(request)
        return try await executeRequest(urlRequest)
    }

    // MARK: - Capability flags (override)

    var supportsVoiceInput: Bool  { true }
    var supportsVoiceOutput: Bool { true }

    // MARK: - Endpoint paths (override)

    func voiceInputEndpointPath() -> String  { "/v1/audio/transcriptions" }
    func voiceOutputEndpointPath() -> String { "/v1/audio/speech" }

    // MARK: - Default model / voice (override)

    func defaultVoiceInputModel() -> String  { "whisper-1" }
    func defaultVoiceOutputModel() -> String { "tts-1" }
    func defaultVoiceOutputVoice() -> String { "nova" }

    // MARK: - Request construction (override to customize format)

    /// Build the ASR request. Default: OpenAI multipart/form-data.
    func buildVoiceInputRequest(_ request: VoiceInputRequest) throws -> URLRequest {
        let urlStr = composedURLString(path: voiceInputEndpointPath())
        guard let url = URL(string: urlStr) else {
            throw VoiceProviderError.parseError("Invalid URL: \(urlStr)")
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        applyVoiceAuth(&urlRequest)

        let boundary = "VoiceBoundary-\(UUID().uuidString)"
        urlRequest.setValue("multipart/form-data; boundary=\(boundary)",
                            forHTTPHeaderField: "Content-Type")
        urlRequest.httpBody = buildMultipartBody(request, boundary: boundary)
        return urlRequest
    }

    /// Build the TTS request. Default: OpenAI JSON body.
    func buildVoiceOutputRequest(_ request: VoiceOutputRequest) throws -> URLRequest {
        let urlStr = composedURLString(path: voiceOutputEndpointPath())
        guard let url = URL(string: urlStr) else {
            throw VoiceProviderError.parseError("Invalid URL: \(urlStr)")
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        applyVoiceAuth(&urlRequest)

        var body: [String: Any] = [
            "model":           request.model ?? defaultVoiceOutputModel(),
            "input":           request.input,
            "voice":           request.voice ?? defaultVoiceOutputVoice(),
            "response_format": request.responseFormat.rawValue
        ]
        if let speed = request.speed { body["speed"] = speed }
        urlRequest.httpBody = try JSONSerialization.data(withJSONObject: body)
        return urlRequest
    }

    /// Parse the ASR response. Default: OpenAI JSON, falling back to plain text.
    func parseVoiceInputResponse(_ data: Data,
                                 request: VoiceInputRequest) throws -> VoiceInputResponse {
        struct OpenAIASRResponse: Decodable {
            let text: String
            let language: String?
            let duration: Double?
        }
        if let resp = try? JSONDecoder().decode(OpenAIASRResponse.self, from: data) {
            return VoiceInputResponse(text: resp.text,
                                      language: resp.language,
                                      duration: resp.duration)
        }
        // Some compatible endpoints return raw text.
        if let text = String(data: data, encoding: .utf8), !text.isEmpty {
            return VoiceInputResponse(text: text, language: nil, duration: nil)
        }
        throw VoiceProviderError.parseError("Empty or undecodable ASR response")
    }

    /// Inject auth header. Default: Bearer token.
    func applyVoiceAuth(_ request: inout URLRequest) {
        guard let key = apiKey, !key.isEmpty else { return }
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
    }

    // MARK: - Helpers

    func effectiveBaseURL() -> String {
        var base = baseURL
        if base.hasSuffix("/") { base.removeLast() }
        return base
    }

    /// Reduce a language tag (e.g. "zh-CN", "zh-Hans-CN", "en") to its ISO-639-1
    /// two-letter primary code ("zh", "en"), which is what Whisper expects.
    static func iso639_1(from tag: String) -> String? {
        let trimmed = tag.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        let primary = Locale(identifier: trimmed).language.languageCode?.identifier
            ?? trimmed.split(separator: "-").first.map(String.init)
        return primary?.lowercased()
    }

    /// Join the base URL with an endpoint path, avoiding a duplicated version
    /// segment. Many OpenAI-compatible bases already include `/v1` (e.g. Groq's
    /// `https://api.groq.com/openai/v1`), and our default paths start with
    /// `/v1/...` — naive concatenation yields `.../v1/v1/...` → HTTP 404.
    func composedURLString(path: String) -> String {
        let base = effectiveBaseURL()
        var p = path
        // If the base already ends in a version segment that the path repeats,
        // drop the leading duplicate from the path.
        for version in ["/v1", "/v2", "/v3"] {
            if base.hasSuffix(version), p.hasPrefix(version + "/") {
                p = String(p.dropFirst(version.count))
                break
            }
        }
        return base + p
    }

    func executeRequest(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await Self.session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw VoiceProviderError.parseError("Non-HTTP response")
        }
        guard (200..<300).contains(http.statusCode) else {
            if http.statusCode == 401 || http.statusCode == 403 {
                logger.error("Voice auth failed: HTTP \(http.statusCode)")
                throw VoiceProviderError.authError
            }
            logger.error("Voice request failed: HTTP \(http.statusCode)")
            throw VoiceProviderError.httpError(http.statusCode, data)
        }
        return data
    }

    // MARK: - Chat-based ASR (for audio+text multimodal models like GPT Audio Mini)

    /// Whether this model transcribes via the chat completions API instead of
    /// the Whisper-style /v1/audio/transcriptions endpoint.
    ///
    /// DEFAULT IS THE TRANSCRIPTIONS ENDPOINT — audio-capable CHAT models are a
    /// small, enumerable family (OpenAI gpt-4o-audio-preview / gpt-audio,
    /// Qwen audio/omni), and they're the only ones that OpenAI(-compatible)
    /// servers accept exclusively via chat completions. Dedicated ASR models
    /// are an OPEN set (whisper variants, SenseVoice, Paraformer, arbitrary
    /// local names on Speaches/LocalAI/Groq), so they must be the fallback,
    /// not the exception.
    ///
    /// The previous modality-based test (input has audio AND text → chat)
    /// misrouted any third-party ASR entry whose modality was hand-edited to
    /// include text input: the request went to /v1/chat/completions, which
    /// pure ASR servers (e.g. Speaches) don't serve → 404. User report:
    /// Speaches + deepdml/faster-whisper-large-v3-turbo-ct2. Model NAME is the
    /// reliable signal here — modality bits can't distinguish a hand-annotated
    /// Whisper from a true audio chat model.
    /// Matching is deliberately BROAD and version-agnostic (family stem +
    /// "audio", not exact ids), so gpt-4o-audio-preview, gpt-audio-mini,
    /// gpt-5-audio, qwen2.5-audio-instruct … all hit without maintaining a
    /// version list. "audio" alone is NOT enough — third-party ASR ids can
    /// carry it incidentally (e.g. FunAudioLLM/SenseVoiceSmall), so it must
    /// co-occur with a chat-family stem.
    func usesChatBasedASR(model: LLMModel) -> Bool {
        let id = model.id.lowercased()
        if id.contains("omni") { return true }                     // qwen*-omni, *-omni-*
        guard id.contains("audio") else { return false }
        return id.contains("gpt") || id.contains("qwen")           // gpt*audio*, qwen*audio*
    }

    /// Transcribe audio via the chat completions API. The system prompt forces
    /// the model to output ONLY the verbatim transcription — no commentary,
    /// no markdown, no thinking — so it behaves like a dedicated ASR engine.
    func transcribeChatBased(_ request: VoiceInputRequest) async throws -> VoiceInputResponse {
        let urlStr = composedURLString(path: "/v1/chat/completions")
        guard let url = URL(string: urlStr) else {
            throw VoiceProviderError.parseError("Invalid URL: \(urlStr)")
        }

        let audioBase64 = request.audioData.base64EncodedString()
        let langHint = request.language.flatMap { Self.iso639_1(from: $0) } ?? "auto"

        let body: [String: Any] = [
            "model": request.model ?? "gpt-4o-mini-audio-preview",
            "messages": [
                [
                    "role": "system",
                    "content": "You are a speech-to-text transcription engine. Output ONLY the exact verbatim transcription of the audio. No commentary, no punctuation corrections, no translations, no markdown, no extra text. If the audio is empty or unintelligible, output an empty string. Language hint: \(langHint)."
                ],
                [
                    "role": "user",
                    "content": [
                        [
                            "type": "input_audio",
                            "input_audio": [
                                "data": audioBase64,
                                "format": "wav"
                            ]
                        ]
                    ]
                ]
            ],
            "max_tokens": 4096,
            "temperature": 0
        ]

        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        applyVoiceAuth(&urlRequest)
        urlRequest.httpBody = try JSONSerialization.data(withJSONObject: body)

        let data = try await executeRequest(urlRequest)
        return try parseChatASRResponse(data)
    }

    private func parseChatASRResponse(_ data: Data) throws -> VoiceInputResponse {
        struct Choice: Decodable {
            struct Message: Decodable { let content: String? }
            let message: Message
        }
        struct ChatResponse: Decodable { let choices: [Choice] }

        let resp = try JSONDecoder().decode(ChatResponse.self, from: data)
        let text = resp.choices.first?.message.content?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return VoiceInputResponse(text: text, language: nil, duration: nil)
    }

    func buildMultipartBody(_ request: VoiceInputRequest, boundary: String) -> Data {
        var body = Data()

        // file field (16 kHz WAV from the VAD)
        body.appendMultipart(boundary: boundary,
                             name: "file", filename: "voice.wav",
                             contentType: "audio/wav",
                             data: request.audioData)
        // model field
        body.appendMultipartField(boundary: boundary, name: "model",
                                  value: request.model ?? defaultVoiceInputModel())
        // language field (optional). Whisper/OpenAI-compatible APIs expect an
        // ISO-639-1 TWO-LETTER code (e.g. "zh", "en") and reject region-qualified
        // ids like "zh-CN" / "zh-HK" with HTTP 400 "unsupported language". Our
        // language picker stores full locale ids (to distinguish Mandarin from
        // Cantonese for the on-device recognizer), so strip to the primary code.
        if let lang = request.language, let primary = Self.iso639_1(from: lang) {
            body.appendMultipartField(boundary: boundary, name: "language", value: primary)
        }
        // response_format field
        body.appendMultipartField(boundary: boundary, name: "response_format",
                                  value: request.responseFormat.rawValue)
        // prompt field (optional)
        if let prompt = request.prompt {
            body.appendMultipartField(boundary: boundary, name: "prompt", value: prompt)
        }

        body.append("--\(boundary)--\r\n")
        return body
    }
}

// MARK: - Data multipart helpers

private extension Data {
    mutating func append(_ string: String) {
        if let data = string.data(using: .utf8) { append(data) }
    }

    mutating func appendMultipart(boundary: String, name: String, filename: String,
                                  contentType: String, data fileData: Data) {
        append("--\(boundary)\r\n")
        append("Content-Disposition: form-data; name=\"\(name)\"; filename=\"\(filename)\"\r\n")
        append("Content-Type: \(contentType)\r\n\r\n")
        append(fileData)
        append("\r\n")
    }

    mutating func appendMultipartField(boundary: String, name: String, value: String) {
        append("--\(boundary)\r\n")
        append("Content-Disposition: form-data; name=\"\(name)\"\r\n\r\n")
        append(value)
        append("\r\n")
    }
}
