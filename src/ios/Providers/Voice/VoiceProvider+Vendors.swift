import Foundation
import CryptoKit

// MARK: - Vendor subclasses
//
// Each vendor overrides only what differs from the OpenAI-compatible base:
// default model names, endpoint paths, request bodies, response envelopes, or
// auth. Vendors that cannot be exercised in this environment (HMAC signing,
// base64-wrapped audio) mirror their public API docs as closely as possible.

// MARK: - Groq (ASR only, OpenAI-compatible, only the default model differs)

final class GroqVoiceProvider: VoiceProvider {
    override var supportsVoiceOutput: Bool { false }
    override func defaultVoiceInputModel() -> String { "whisper-large-v3-turbo" }

    override func buildVoiceOutputRequest(_ request: VoiceOutputRequest) throws -> URLRequest {
        throw VoiceProviderError.unsupported("Groq does not support speech synthesis")
    }
}

// MARK: - Alibaba Bailian (OpenAI-compatible, only default models differ)

final class AlibabaVoiceProvider: VoiceProvider {
    override func defaultVoiceInputModel() -> String  { "paraformer-realtime-v2" }
    override func defaultVoiceOutputModel() -> String { "cosyvoice-v2" }
    override func defaultVoiceOutputVoice() -> String { "longxiaochun" }
}

// MARK: - xAI (ASR endpoint path differs: /v1/stt)

final class XAIVoiceProvider: VoiceProvider {
    override func voiceInputEndpointPath() -> String  { "/v1/stt" }
    override func defaultVoiceInputModel() -> String  { "grok-stt" }
    override func defaultVoiceOutputModel() -> String { "grok-tts-1" }
    override func defaultVoiceOutputVoice() -> String { "eve" }
}

// MARK: - MiniMax (TTS only, distinct body, base64-nested response)

final class MiniMaxVoiceProvider: VoiceProvider {
    override var supportsVoiceInput: Bool { false }

    override func buildVoiceInputRequest(_ request: VoiceInputRequest) throws -> URLRequest {
        throw VoiceProviderError.unsupported("MiniMax does not support speech recognition")
    }

    /// [T-voice-minimax-tts-404] MiniMax's NATIVE TTS endpoint (`/v1/t2a_v2`)
    /// lives at the API host ROOT — NOT under the `/anthropic` chat-proxy path.
    /// Users configure the instance's base URL for chat as
    /// `https://api.minimax.io/anthropic` (or `.../anthropic/v1`,
    /// `https://api.minimaxi.com/anthropic`), so naively appending `/v1/t2a_v2`
    /// produced `.../anthropic/v1/t2a_v2` → HTTP 404. Strip a trailing
    /// `/anthropic` (and any `/v1`) segment so TTS hits the correct
    /// `https://<host>/v1/t2a_v2`. Chat requests are unaffected — they go through
    /// the OpenAI/Anthropic provider path, not this voice provider.
    override func effectiveBaseURL() -> String {
        var base = super.effectiveBaseURL()
        while base.hasSuffix("/") { base.removeLast() }
        for suffix in ["/v1", "/anthropic"] {
            if base.hasSuffix(suffix) { base = String(base.dropLast(suffix.count)) }
        }
        while base.hasSuffix("/") { base.removeLast() }
        return base
    }

    override func voiceOutputEndpointPath() -> String { "/v1/t2a_v2" }

    override func buildVoiceOutputRequest(_ request: VoiceOutputRequest) throws -> URLRequest {
        let urlStr = composedURLString(path: voiceOutputEndpointPath())
        guard let url = URL(string: urlStr) else {
            throw VoiceProviderError.parseError("Invalid URL: \(urlStr)")
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        applyVoiceAuth(&urlRequest)  // Bearer {apiKey}, from base class

        // MiniMax-specific shape: speed becomes an integer 0~200.
        let speedInt = Int((request.speed ?? 1.0) * 100)
        // [T-voice-minimax-quicktest-2054] Quick Test (and any caller whose
        // model entries double as voices) passes the MODEL id in `voice`.
        // MiniMax voice ids are a separate namespace — sending the model id
        // as voice_id fails with 2054 "voice id not exist". Treat
        // voice == model as "no voice selected" and use the default.
        let requestedVoice = (request.voice == request.model) ? nil : request.voice
        let body: [String: Any] = [
            "model": request.model ?? defaultVoiceOutputModel(),
            "text":  request.input,                                  // input -> text
            "stream": false,
            "voice_setting": [
                "voice_id": requestedVoice ?? defaultVoiceOutputVoice(),  // voice -> voice_id
                "speed":    speedInt,
                "vol":      100,
                "pitch":    0
            ],
            "audio_setting": [
                "sample_rate": 32000,
                "bitrate":     128000,
                "format":      request.responseFormat.rawValue
            ]
        ]
        urlRequest.httpBody = try JSONSerialization.data(withJSONObject: body)
        return urlRequest
    }

    // MiniMax response: { "audio": { "audio": "base64..." }, "base_resp": { status_code, status_msg } }
    override func synthesize(_ request: VoiceOutputRequest) async throws -> Data {
        let urlRequest = try buildVoiceOutputRequest(request)
        let rawData = try await executeRequest(urlRequest)
        let json = try? JSONSerialization.jsonObject(with: rawData) as? [String: Any]
        // [T-voice-minimax-tts-404] Surface MiniMax's own error (base_resp) instead
        // of a generic "unexpected format" — on failure (e.g. status_code 1008
        // "insufficient balance", 1004 auth) t2a_v2 returns NO audio, only
        // base_resp. Reporting the real status_msg makes the cause obvious in the
        // Quick Test / capsule instead of looking like a parser bug.
        if let base = json?["base_resp"] as? [String: Any],
           let code = base["status_code"] as? Int, code != 0 {
            let msg = (base["status_msg"] as? String) ?? "unknown error"
            throw VoiceProviderError.parseError("MiniMax TTS error [\(code)]: \(msg)")
        }
        // [T-voice-minimax-quicktest-2054] Current t2a_v2 (api.minimaxi.com,
        // verified 2026-07-24) nests HEX-encoded audio at `data.audio`
        // (magic ID3 after unhex). Older deployments returned base64 at
        // `audio.audio` — keep that as fallback.
        if let dataSec = json?["data"] as? [String: Any],
           let encoded = dataSec["audio"] as? String {
            if let hexData = Self.decodeHex(encoded) { return hexData }
            if let b64Data = Data(base64Encoded: encoded) { return b64Data }
            throw VoiceProviderError.parseError("MiniMax TTS: data.audio is neither hex nor base64")
        }
        guard
            let audioSec  = json?["audio"] as? [String: Any],
            let base64Str = audioSec["audio"] as? String,
            let audioData = Data(base64Encoded: base64Str)
        else {
            throw VoiceProviderError.parseError("Unexpected MiniMax TTS response format")
        }
        return audioData
    }

    /// Strict hex → Data. Returns nil on odd length or any non-hex byte, so
    /// base64 payloads (which contain '+', '/', '=' etc.) fall through to the
    /// base64 branch instead of mis-decoding.
    private static func decodeHex(_ s: String) -> Data? {
        let bytes = Array(s.utf8)
        guard !bytes.isEmpty, bytes.count % 2 == 0 else { return nil }
        func nibble(_ c: UInt8) -> UInt8? {
            switch c {
            case 0x30...0x39: return c - 0x30
            case 0x61...0x66: return c - 0x61 + 10
            case 0x41...0x46: return c - 0x41 + 10
            default: return nil
            }
        }
        var out = Data(capacity: bytes.count / 2)
        var i = 0
        while i < bytes.count {
            guard let hi = nibble(bytes[i]), let lo = nibble(bytes[i + 1]) else { return nil }
            out.append((hi << 4) | lo)
            i += 2
        }
        return out
    }

    override func defaultVoiceOutputModel() -> String { "speech-2.8-hd" }
    override func defaultVoiceOutputVoice() -> String { "female-shaonv" }
}

// MARK: - Doubao / Volcano (TTS + ASR, two-credential auth, distinct formats)

final class DoubaoVoiceProvider: VoiceProvider {

    init(providerId: String, apiKey: String?) {
        super.init(providerId: providerId,
                   baseURL: "https://openspeech.bytedance.com",
                   apiKey: apiKey)
    }

    override func applyVoiceAuth(_ request: inout URLRequest) {
        guard let key = apiKey, !key.isEmpty else { return }
        request.setValue(key, forHTTPHeaderField: "X-Api-Key")
    }

    // -- TTS (v3 unidirectional streaming) ------------------------------------

    override func voiceOutputEndpointPath() -> String { "/api/v3/tts/unidirectional" }

    override func buildVoiceOutputRequest(_ request: VoiceOutputRequest) throws -> URLRequest {
        let urlStr = composedURLString(path: voiceOutputEndpointPath())
        guard let url = URL(string: urlStr) else {
            throw VoiceProviderError.parseError("Invalid URL: \(urlStr)")
        }
        let raw = request.model ?? request.voice ?? defaultVoiceOutputVoice()
        let speaker = raw.hasPrefix("seed-tts-") ? defaultVoiceOutputVoice() : raw
        let resourceId: String
        if speaker.contains("_uranus_") || speaker.hasPrefix("saturn_") {
            resourceId = "seed-tts-2.0"
        } else {
            resourceId = "seed-tts-1.0"
        }

        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        urlRequest.setValue(resourceId, forHTTPHeaderField: "X-Api-Resource-Id")
        urlRequest.setValue("keep-alive", forHTTPHeaderField: "Connection")
        applyVoiceAuth(&urlRequest)

        let body: [String: Any] = [
            "req_params": [
                "text": request.input,
                "speaker": speaker,
                "audio_params": [
                    "format": request.responseFormat == .wav ? "wav" : "mp3",
                    "sample_rate": 24000
                ]
            ]
        ]
        urlRequest.httpBody = try JSONSerialization.data(withJSONObject: body)
        return urlRequest
    }

    // v3 TTS returns HTTP chunked: each line is a JSON frame with base64 audio.
    // Collect all frames, concatenate the decoded audio data.
    override func synthesize(_ request: VoiceOutputRequest) async throws -> Data {
        let urlRequest = try buildVoiceOutputRequest(request)
        let rawData = try await executeRequest(urlRequest)
        var audioChunks = Data()
        let lines = rawData.split(separator: UInt8(ascii: "\n"))
        for line in lines {
            guard let frame = try? JSONSerialization.jsonObject(with: Data(line)) as? [String: Any],
                  let code = frame["code"] as? Int, code == 0,
                  let b64 = frame["data"] as? String, !b64.isEmpty,
                  let chunk = Data(base64Encoded: b64) else { continue }
            audioChunks.append(chunk)
        }
        guard !audioChunks.isEmpty else {
            let preview = String(data: rawData.prefix(500), encoding: .utf8) ?? "(binary \(rawData.count) bytes)"
            throw VoiceProviderError.parseError("Doubao v3 TTS: no audio frames in response. Raw: \(preview)")
        }
        return audioChunks
    }

    // -- ASR (v3 bigmodel flash recognize) ------------------------------------

    override func voiceInputEndpointPath() -> String { "/api/v3/auc/bigmodel/recognize/flash" }

    override func buildVoiceInputRequest(_ request: VoiceInputRequest) throws -> URLRequest {
        let urlStr = composedURLString(path: voiceInputEndpointPath())
        guard let url = URL(string: urlStr) else {
            throw VoiceProviderError.parseError("Invalid URL: \(urlStr)")
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        urlRequest.setValue("volc.bigasr.auc_turbo", forHTTPHeaderField: "X-Api-Resource-Id")
        urlRequest.setValue(UUID().uuidString, forHTTPHeaderField: "X-Api-Request-Id")
        urlRequest.setValue("-1", forHTTPHeaderField: "X-Api-Sequence")
        applyVoiceAuth(&urlRequest)

        let body: [String: Any] = [
            "user": ["uid": "minis_user"],
            "audio": ["data": request.audioData.base64EncodedString()],
            "request": ["model_name": "bigmodel"]
        ]
        urlRequest.httpBody = try JSONSerialization.data(withJSONObject: body)
        return urlRequest
    }

    // v3 ASR response: { "result": { "text": "..." }, "audio_info": { ... } }
    override func parseVoiceInputResponse(_ data: Data,
                                          request: VoiceInputRequest) throws -> VoiceInputResponse {
        let rawPreview = String(data: data.prefix(500), encoding: .utf8) ?? "(binary)"
        AppLogger(category: "DoubaoASR").info("raw response (\(data.count) bytes): \(rawPreview)")
        guard
            let json   = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let result = json["result"] as? [String: Any],
            let text   = result["text"] as? String
        else {
            throw VoiceProviderError.parseError("Unexpected Doubao v3 ASR response format")
        }
        let duration = (json["audio_info"] as? [String: Any])?["duration"] as? Double
        return VoiceInputResponse(text: text, language: request.language,
                                  duration: duration.map { $0 / 1000 })
    }

    override func defaultVoiceInputModel() -> String  { "bigmodel" }
    override func defaultVoiceOutputModel() -> String { "zh_female_cancan_uranus_bigtts" }
    override func defaultVoiceOutputVoice() -> String { "zh_female_cancan_uranus_bigtts" }
}

// MARK: - iFlytek / Xunfei (ASR with HMAC-SHA256 signed URL)

final class XunfeiVoiceProvider: VoiceProvider {
    let appId:     String
    let apiSecret: String

    init(providerId: String, appId: String, apiKey: String, apiSecret: String) {
        self.appId     = appId
        self.apiSecret = apiSecret
        super.init(providerId: providerId,
                   baseURL: "https://iat-api.xfyun.cn",
                   apiKey: apiKey)
    }

    // Xunfei authenticates via a signed URL, not a header — no-op here.
    override func applyVoiceAuth(_ request: inout URLRequest) { }

    override var supportsVoiceOutput: Bool { true }

    /// Default Xunfei TTS voice (Chinese female). Overridable via request.voice.
    private static let defaultTTSVoice = "xiaoyan"

    /// Xunfei TTS is a WebSocket endpoint (tts-api.xfyun.cn/v2/tts) that streams
    /// base64 PCM frames, signed with the same HMAC scheme as ASR. We collect all
    /// frames and wrap the raw 16k PCM in a WAV header for AVAudioPlayer.
    override func synthesize(_ request: VoiceOutputRequest) async throws -> Data {
        let host = "tts-api.xfyun.cn"
        let path = "/v2/tts"
        let signedURL = buildSignedURL(host: host, path: path, date: Date())
        guard let url = URL(string: signedURL) else {
            throw VoiceProviderError.parseError("Failed to build Xunfei TTS URL")
        }
        let session = URLSession(configuration: .default)
        let ws = session.webSocketTask(with: url)
        ws.resume()
        defer { ws.cancel(with: .normalClosure, reason: nil) }

        let voice = (request.voice?.isEmpty == false) ? request.voice! : Self.defaultTTSVoice
        let textB64 = Data(request.input.utf8).base64EncodedString()
        let body: [String: Any] = [
            "common": ["app_id": appId],
            "business": [
                "aue": "raw", "auf": "audio/L16;rate=16000",
                "vcn": voice, "tte": "UTF8"
            ],
            "data": ["status": 2, "text": textB64]
        ]
        let payload = try JSONSerialization.data(withJSONObject: body)
        try await ws.send(.string(String(data: payload, encoding: .utf8) ?? ""))

        var pcm = Data()
        while true {
            switch try await ws.receive() {
            case .string(let s):
                guard let d = s.data(using: .utf8),
                      let json = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { continue }
                if let code = json["code"] as? Int, code != 0 {
                    throw VoiceProviderError.parseError("Xunfei TTS error code \(code): \(json["message"] as? String ?? "")")
                }
                if let data = json["data"] as? [String: Any],
                   let b64 = data["audio"] as? String,
                   let chunk = Data(base64Encoded: b64) {
                    pcm.append(chunk)
                    if (data["status"] as? Int) == 2 {   // last frame
                        guard !pcm.isEmpty else { throw VoiceProviderError.parseError("Xunfei TTS empty audio") }
                        return Self.wrapPCM16WAV(pcm, sampleRate: 16000)
                    }
                }
            case .data:
                continue
            @unknown default:
                break
            }
        }
    }

    /// Wrap signed 16-bit LE mono PCM in a WAV container.
    private static func wrapPCM16WAV(_ pcm: Data, sampleRate: Int) -> Data {
        let byteRate = sampleRate * 2, blockAlign = 2, dataSize = UInt32(pcm.count)
        var wav = Data()
        func le32(_ v: UInt32) { var x = v.littleEndian; wav.append(Data(bytes: &x, count: 4)) }
        func le16(_ v: UInt16) { var x = v.littleEndian; wav.append(Data(bytes: &x, count: 2)) }
        wav.append("RIFF".data(using: .ascii)!); le32(36 + dataSize)
        wav.append("WAVE".data(using: .ascii)!); wav.append("fmt ".data(using: .ascii)!)
        le32(16); le16(1); le16(1); le32(UInt32(sampleRate)); le32(UInt32(byteRate))
        le16(UInt16(blockAlign)); le16(16)
        wav.append("data".data(using: .ascii)!); le32(dataSize); wav.append(pcm)
        return wav
    }

    override func buildVoiceInputRequest(_ request: VoiceInputRequest) throws -> URLRequest {
        let date = Date()
        let signedURLStr = buildSignedURL(date: date)
        guard let url = URL(string: signedURLStr) else {
            throw VoiceProviderError.parseError("Failed to build Xunfei signed URL")
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let audioBase64 = request.audioData.base64EncodedString()
        let body: [String: Any] = [
            "header": ["app_id": appId, "status": 3],
            "parameter": [
                "iat": [
                    "domain":   "iat",
                    "language": request.language ?? "zh_cn",
                    "accent":   "mandarin",
                    "result":   ["encoding": "utf8", "compress": "raw", "format": "json"]
                ]
            ],
            "payload": [
                "audio": [
                    "encoding":    "raw",
                    "sample_rate": 16000,
                    "channels":    1,
                    "bit_depth":   16,
                    "status":      3,
                    "audio":       audioBase64
                ]
            ]
        ]
        urlRequest.httpBody = try JSONSerialization.data(withJSONObject: body)
        return urlRequest
    }

    override func parseVoiceInputResponse(_ data: Data,
                                          request: VoiceInputRequest) throws -> VoiceInputResponse {
        guard
            let json     = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let header   = json["header"] as? [String: Any],
            (header["code"] as? Int) == 0,
            let payload  = json["payload"] as? [String: Any],
            let result   = payload["result"] as? [String: Any],
            let b64Text  = result["text"] as? String,
            let textData = Data(base64Encoded: b64Text),
            let textJson = try? JSONSerialization.jsonObject(with: textData) as? [String: Any]
        else {
            throw VoiceProviderError.parseError("Unexpected Xunfei ASR response format")
        }

        let text = (textJson["ws"] as? [[String: Any]] ?? [])
            .flatMap { $0["cw"] as? [[String: Any]] ?? [] }
            .compactMap { $0["w"] as? String }
            .joined()

        return VoiceInputResponse(text: text, language: request.language, duration: nil)
    }

    // MARK: - HMAC-SHA256 URL signature

    private func buildSignedURL(host: String = "iat-api.xfyun.cn", path: String = "/v2/iat", date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale     = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss z"
        formatter.timeZone   = TimeZone(identifier: "GMT")
        let dateStr = formatter.string(from: date)

        let signatureOrigin = "host: \(host)\ndate: \(dateStr)\nGET \(path) HTTP/1.1"
        let signatureB64    = signatureOrigin.voiceHMACSHA256Base64(key: apiSecret)

        let authOrigin = "api_key=\"\(apiKey ?? "")\", " +
                         "algorithm=\"hmac-sha256\", " +
                         "headers=\"host date request-line\", " +
                         "signature=\"\(signatureB64)\""
        let authB64 = Data(authOrigin.utf8).base64EncodedString()

        return "https://\(host)\(path)?authorization=\(authB64)&date=\(dateStr.voiceURLEncoded)&host=\(host)"
    }
}

// MARK: - Google Gemini (native TTS via generateContent + AUDIO modality)

/// Gemini's TTS is NOT OpenAI-compatible: it uses
/// `POST {base}/v1beta/models/{model}:generateContent` with the API key in a
/// `?key=` query param and returns base64 raw PCM (24 kHz, 16-bit, mono) in
/// `candidates[0].content.parts[].inlineData.data`. We wrap that PCM in a WAV
/// header so AVAudioPlayer can play it.
final class GeminiVoiceProvider: VoiceProvider {

    /// Gemini prebuilt voices; default "Kore". Map an OpenAI-style voice name if
    /// the request carries one we recognize, else fall back to Kore.
    private static func geminiVoice(_ requested: String?) -> String {
        guard let v = requested, !v.isEmpty else { return "Kore" }
        // If the caller already passed a Gemini voice name, use it verbatim.
        let known: Set<String> = ["Kore", "Puck", "Charon", "Fenrir", "Aoede", "Zephyr", "Leda", "Orus"]
        if known.contains(v) { return v }
        return "Kore"
    }

    override var supportsVoiceInput: Bool { false }
    override var supportsVoiceOutput: Bool { true }
    override func defaultVoiceOutputModel() -> String { "gemini-2.5-flash-preview-tts" }

    override func buildVoiceOutputRequest(_ request: VoiceOutputRequest) throws -> URLRequest {
        let model = request.model ?? defaultVoiceOutputModel()
        var base = effectiveBaseURL()
        // Normalize to a v1beta base (the configured base may be just the host).
        if !base.contains("/v1beta") {
            if base.hasSuffix("/") { base.removeLast() }
            base += "/v1beta"
        }
        let key = apiKey ?? ""
        let urlStr = "\(base)/models/\(model):generateContent?key=\(key)"
        guard let url = URL(string: urlStr) else {
            throw VoiceProviderError.parseError("Invalid Gemini TTS URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let body: [String: Any] = [
            "contents": [["parts": [["text": request.input]]]],
            "generationConfig": [
                "responseModalities": ["AUDIO"],
                "speechConfig": [
                    "voiceConfig": [
                        "prebuiltVoiceConfig": [
                            "voiceName": Self.geminiVoice(request.voice)
                        ]
                    ]
                ]
            ]
        ]
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        return req
    }

    override func synthesize(_ request: VoiceOutputRequest) async throws -> Data {
        let urlRequest = try buildVoiceOutputRequest(request)
        let raw = try await executeRequest(urlRequest)
        guard let json = try? JSONSerialization.jsonObject(with: raw) as? [String: Any],
              let candidates = json["candidates"] as? [[String: Any]],
              let content = candidates.first?["content"] as? [String: Any],
              let parts = content["parts"] as? [[String: Any]] else {
            throw VoiceProviderError.parseError("Unexpected Gemini TTS response")
        }
        // Find the inlineData audio part.
        for part in parts {
            guard let inline = part["inlineData"] as? [String: Any],
                  let b64 = inline["data"] as? String,
                  let pcm = Data(base64Encoded: b64) else { continue }
            let mime = (inline["mimeType"] as? String) ?? "audio/L16;rate=24000"
            // If it's already a container (wav), return as-is; else wrap raw PCM.
            if mime.lowercased().contains("wav") { return pcm }
            let rate = Self.sampleRate(fromMime: mime)
            return Self.wrapPCMInWAV(pcm, sampleRate: rate)
        }
        throw VoiceProviderError.parseError("No audio in Gemini TTS response")
    }

    /// Parse the sample rate from a mime like "audio/L16;rate=24000" (default 24k).
    private static func sampleRate(fromMime mime: String) -> Int {
        if let r = mime.range(of: "rate="),
           let val = Int(mime[r.upperBound...].prefix(while: { $0.isNumber })) {
            return val
        }
        return 24000
    }

    /// Wrap signed 16-bit little-endian mono PCM in a minimal WAV container.
    private static func wrapPCMInWAV(_ pcm: Data, sampleRate: Int) -> Data {
        let channels = 1, bitsPerSample = 16
        let byteRate = sampleRate * channels * bitsPerSample / 8
        let blockAlign = channels * bitsPerSample / 8
        let dataSize = UInt32(pcm.count)
        var wav = Data()
        func le32(_ v: UInt32) { var x = v.littleEndian; wav.append(Data(bytes: &x, count: 4)) }
        func le16(_ v: UInt16) { var x = v.littleEndian; wav.append(Data(bytes: &x, count: 2)) }
        wav.append("RIFF".data(using: .ascii)!)
        le32(36 + dataSize)
        wav.append("WAVE".data(using: .ascii)!)
        wav.append("fmt ".data(using: .ascii)!)
        le32(16)
        le16(1)                          // PCM
        le16(UInt16(channels))
        le32(UInt32(sampleRate))
        le32(UInt32(byteRate))
        le16(UInt16(blockAlign))
        le16(UInt16(bitsPerSample))
        wav.append("data".data(using: .ascii)!)
        le32(dataSize)
        wav.append(pcm)
        return wav
    }
}

// MARK: - ElevenLabs (TTS) — REST, xi-api-key header, returns MP3

/// ElevenLabs TTS. The model entry `id` carries the ElevenLabs voice_id; a fixed
/// model_id (`eleven_multilingual_v2`) drives synthesis. Endpoint:
/// POST {base}/v1/text-to-speech/{voice_id} → audio/mpeg (MP3).
final class ElevenLabsVoiceProvider: VoiceProvider {

    private static let defaultVoiceId = "21m00Tcm4TlvDq8ikWAM" // Rachel
    private static let modelId = "eleven_multilingual_v2"

    override var supportsVoiceInput: Bool { false }
    override var supportsVoiceOutput: Bool { true }

    override func synthesize(_ request: VoiceOutputRequest) async throws -> Data {
        // The selected model entry id is the ElevenLabs voice_id.
        let voiceId = (request.model?.isEmpty == false) ? request.model!
                    : ((request.voice?.isEmpty == false) ? request.voice! : Self.defaultVoiceId)
        var base = effectiveBaseURL()
        if base.hasSuffix("/") { base.removeLast() }
        if !base.contains("/v1") { base += "/v1" }
        guard let url = URL(string: "\(base)/text-to-speech/\(voiceId)") else {
            throw VoiceProviderError.parseError("ElevenLabs: bad URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue(apiKey ?? "", forHTTPHeaderField: "xi-api-key")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("audio/mpeg", forHTTPHeaderField: "Accept")
        let body: [String: Any] = [
            "text": request.input,
            "model_id": Self.modelId,
            "voice_settings": ["stability": 0.5, "similarity_boost": 0.75]
        ]
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await executeRequest(req)   // MP3 bytes
    }
}

// MARK: - Deepgram — ASR (/v1/listen) + TTS Aura (/v1/speak), Token header

/// Deepgram. ASR posts raw audio to /v1/listen?model=…; TTS (Aura) posts text to
/// /v1/speak?model=… and returns audio. Auth: `Authorization: Token <key>`.
final class DeepgramVoiceProvider: VoiceProvider {

    private static let defaultASRModel = "nova-2"
    private static let defaultTTSModel = "aura-asteria-en"

    override var supportsVoiceInput: Bool { true }
    override var supportsVoiceOutput: Bool { true }

    private func dgBase() -> String {
        var base = effectiveBaseURL()
        if base.hasSuffix("/") { base.removeLast() }
        if !base.contains("/v1") { base += "/v1" }
        return base
    }

    // -- TTS (Aura) --------------------------------------------------
    override func synthesize(_ request: VoiceOutputRequest) async throws -> Data {
        let model = (request.model?.isEmpty == false) ? request.model! : Self.defaultTTSModel
        guard let url = URL(string: "\(dgBase())/speak?model=\(model)") else {
            throw VoiceProviderError.parseError("Deepgram: bad TTS URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("Token \(apiKey ?? "")", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["text": request.input])
        return try await executeRequest(req)   // audio (mp3/wav per model)
    }

    // -- ASR ---------------------------------------------------------
    override func buildVoiceInputRequest(_ request: VoiceInputRequest) throws -> URLRequest {
        let model = (request.model?.isEmpty == false) ? request.model! : Self.defaultASRModel
        var qs = "model=\(model)&smart_format=true"
        if let lang = request.language, !lang.isEmpty { qs += "&language=\(lang)" }
        guard let url = URL(string: "\(dgBase())/listen?\(qs)") else {
            throw VoiceProviderError.parseError("Deepgram: bad ASR URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("Token \(apiKey ?? "")", forHTTPHeaderField: "Authorization")
        req.setValue("audio/wav", forHTTPHeaderField: "Content-Type")
        req.httpBody = request.audioData
        return req
    }

    override func parseVoiceInputResponse(_ data: Data, request: VoiceInputRequest) throws -> VoiceInputResponse {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let results = json["results"] as? [String: Any],
              let channels = results["channels"] as? [[String: Any]],
              let alts = channels.first?["alternatives"] as? [[String: Any]],
              let transcript = alts.first?["transcript"] as? String else {
            throw VoiceProviderError.parseError("Unexpected Deepgram ASR response")
        }
        return VoiceInputResponse(text: transcript, language: request.language, duration: nil)
    }
}

// MARK: - Azure TTS (REST API, Ocp-Apim-Subscription-Key auth)

final class AzureTTSVoiceProvider: VoiceProvider {

    override var supportsVoiceInput: Bool { false }
    override var supportsVoiceOutput: Bool { true }
    override func defaultVoiceOutputModel() -> String { "azure-tts" }
    override func defaultVoiceOutputVoice() -> String { "zh-CN-XiaoxiaoNeural" }

    override func buildVoiceInputRequest(_ request: VoiceInputRequest) throws -> URLRequest {
        throw VoiceProviderError.unsupported("Azure TTS does not support speech recognition")
    }

    override func buildVoiceOutputRequest(_ request: VoiceOutputRequest) throws -> URLRequest {
        let urlStr = composedURLString(path: "/cognitiveservices/v1")
        guard let url = URL(string: urlStr) else {
            throw VoiceProviderError.parseError("Invalid URL: \(urlStr)")
        }
        let voice = request.voice ?? defaultVoiceOutputVoice()
        let parts = voice.split(separator: "-", maxSplits: 2)
        let lang = parts.count >= 2 ? "\(parts[0])-\(parts[1])" : "en-US"
        let escaped = request.input
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
        let ssml = """
            <speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="\(lang)">\
            <voice name="\(voice)">\(escaped)</voice>\
            </speak>
            """

        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/ssml+xml", forHTTPHeaderField: "Content-Type")
        urlRequest.setValue("audio-24khz-48kbitrate-mono-mp3", forHTTPHeaderField: "X-Microsoft-OutputFormat")
        if let key = apiKey, !key.isEmpty {
            urlRequest.setValue(key, forHTTPHeaderField: "Ocp-Apim-Subscription-Key")
        }
        urlRequest.httpBody = ssml.data(using: .utf8)
        return urlRequest
    }
}

// MARK: - Xiaomi MiMo (ASR + TTS, both ride on /v1/chat/completions,
// NOT the OpenAI /v1/audio/{transcriptions,speech} endpoints)

final class MimoVoiceProvider: VoiceProvider {

    override init(providerId: String, baseURL: String, apiKey: String?) {
        super.init(providerId: providerId, baseURL: baseURL, apiKey: apiKey)
    }

    override func defaultVoiceInputModel() -> String  { "mimo-v2.5-asr" }
    override func defaultVoiceOutputModel() -> String { "mimo-v2.5-tts" }
    override func defaultVoiceOutputVoice() -> String { "mimo_default" }

    // -- ASR -----------------------------------------------------------------

    override func transcribe(_ request: VoiceInputRequest) async throws -> VoiceInputResponse {
        let urlStr = composedURLString(path: "/v1/chat/completions")
        guard let url = URL(string: urlStr) else {
            throw VoiceProviderError.parseError("Invalid URL: \(urlStr)")
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        applyVoiceAuth(&urlRequest)

        let audioBase64 = request.audioData.base64EncodedString()
        let lang = request.language.flatMap { Self.iso639_1(from: $0) } ?? "auto"

        let body: [String: Any] = [
            "model": request.model ?? defaultVoiceInputModel(),
            "messages": [
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
            "asr_options": ["language": lang]
        ]
        urlRequest.httpBody = try JSONSerialization.data(withJSONObject: body)

        let data = try await executeRequest(urlRequest)
        guard
            let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let choices = json["choices"] as? [[String: Any]],
            let message = choices.first?["message"] as? [String: Any],
            let text = message["content"] as? String
        else {
            throw VoiceProviderError.parseError("Unexpected MiMo ASR response format")
        }
        let seconds = (json["usage"] as? [String: Any])?["seconds"] as? Double
        return VoiceInputResponse(text: text, language: nil, duration: seconds)
    }

    // -- TTS -----------------------------------------------------------------

    override func synthesize(_ request: VoiceOutputRequest) async throws -> Data {
        let urlStr = composedURLString(path: "/v1/chat/completions")
        guard let url = URL(string: urlStr) else {
            throw VoiceProviderError.parseError("Invalid URL: \(urlStr)")
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        applyVoiceAuth(&urlRequest)

        let apiModels: Set<String> = ["mimo-v2.5-tts", "mimo-v2.5-tts-voicedesign", "mimo-v2.5-tts-voiceclone"]
        let rawModel = request.model ?? defaultVoiceOutputModel()

        let apiModel: String
        let voiceId: String?
        if apiModels.contains(rawModel) {
            apiModel = rawModel
            voiceId = request.voice
        } else {
            apiModel = "mimo-v2.5-tts"
            voiceId = rawModel
        }

        let messages: [[String: Any]] = [
            ["role": "user", "content": ""],
            ["role": "assistant", "content": request.input]
        ]

        var audioParams: [String: Any] = ["format": "wav"]
        if apiModel != "mimo-v2.5-tts-voicedesign", let v = voiceId {
            audioParams["voice"] = v
        }

        let body: [String: Any] = [
            "model": apiModel,
            "messages": messages,
            "audio": audioParams
        ]
        urlRequest.httpBody = try JSONSerialization.data(withJSONObject: body)

        let data = try await executeRequest(urlRequest)
        guard
            let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let choices = json["choices"] as? [[String: Any]],
            let message = choices.first?["message"] as? [String: Any],
            let audio = message["audio"] as? [String: Any],
            let b64 = audio["data"] as? String,
            let audioData = Data(base64Encoded: b64)
        else {
            throw VoiceProviderError.parseError("Unexpected MiMo TTS response format")
        }
        return audioData
    }
}

// MARK: - String crypto helpers (CryptoKit)

private extension String {
    /// HMAC-SHA256 of self keyed by `key`, base64-encoded.
    func voiceHMACSHA256Base64(key: String) -> String {
        let symmetricKey = SymmetricKey(data: Data(key.utf8))
        let mac = HMAC<SHA256>.authenticationCode(for: Data(self.utf8), using: symmetricKey)
        return Data(mac).base64EncodedString()
    }

    var voiceURLEncoded: String {
        addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? self
    }
}

// MARK: - OpenRouter — dedicated voice endpoints + chat-audio

/// OpenRouter voice (OpenMinis#280 for the catalog roles; Android
/// `OpenRouterVoiceProvider` must be kept in step).
///
/// OpenRouter serves voice on three routes, each taking a DISJOINT model set:
///   • `POST /api/v1/audio/speech` — the models listed by
///     `GET /models?output_modalities=speech` (fish-audio, minimax speech,
///     kokoro, …; voiceRole "tts");
///   • `POST /api/v1/audio/transcriptions` — the models listed by
///     `?output_modalities=transcription` (whisper, gpt-4o-transcribe, nova-3,
///     qwen3-asr, voxtral, …; voiceRole "stt");
///   • `POST /api/v1/chat/completions` — chat models: audio-preview output for
///     TTS (gpt-audio family only), an `input_audio` part for ASR.
///
/// Sending a model to another set's route fails, and on OpenRouter that
/// failure can be a 401 whose body is NOT about the key — see
/// `isAuthFailure`. So routing follows the catalog tag
/// (`LLMModel.voiceRole`), and model-name guessing is only the fallback for
/// untagged models. (An earlier version of this comment claimed
/// `/audio/speech` does not exist. It does; the ids probed then — e.g.
/// `openai/tts-1` — simply were not OpenRouter speech models.)
///
/// The chat-audio route carries three hard constraints (each verified against
/// the live API with a real key):
///   • `modalities: ["text","audio"]` + `audio: {voice, format}`;
///   • `stream: true` is MANDATORY — otherwise
///     `{"error":{"message":"Audio output requires stream: true"}}`;
///   • while streaming, `audio.format` accepts ONLY `pcm16` — `wav` returns
///     "does not support 'wav' when stream=true. Supported values are:
///     'pcm16'".
///
/// Its SSE body is an ordinary chat-completions stream with two extra fields
/// per delta: `audio.data` (base64 PCM16 chunk) and `audio.transcript`. We
/// accumulate the PCM and wrap it in a WAV container, because `synthesize`
/// must return something `AVAudioPlayer(data:)` can open and headerless PCM
/// is not that.
final class OpenRouterVoiceProvider: VoiceProvider {

    private let orLogger = AppLogger(category: "VoiceProvider")

    /// OpenAI's audio-preview models emit 24 kHz mono PCM16; OpenRouter proxies
    /// that stream unchanged. There is no field in the response announcing the
    /// rate, so it is fixed here — a wrong value would play at the wrong pitch,
    /// which is immediately audible if it ever changes.
    private static let pcmSampleRate = 24000

    /// Where a TTS request goes.
    enum TTSRoute: Equatable { case speechEndpoint, chatAudio, unsupported }

    /// Pre-#280 name rule for chat-audio TTS, kept for untagged models.
    private static func looksLikeChatAudio(_ modelId: String) -> Bool {
        let id = modelId.lowercased()
        guard id.contains("audio") else { return false }
        return id.contains("gpt") || id.contains("openai")
    }

    /// [T-openrouter-voice-catalog] Pick the TTS route. Tagged models go by
    /// tag: "tts" → speech endpoint, the chat-audio allowlist → chat, any
    /// other tag (a chat model such as Lyria, an ASR model) → unsupported,
    /// raised as a clear error instead of a request that can only fail.
    /// Untagged models keep the old name rule for chat-audio. Otherwise they go
    /// to the speech endpoint when they declare audio output (they passed the
    /// picker's gate) or their name says TTS.
    static func ttsRoute(modelId: String, model: LLMModel?) -> TTSRoute {
        if VoiceRole.openRouterChatAudioModels.contains(modelId) { return .chatAudio }
        switch model?.voiceRole {
        case .some(VoiceRole.tts): return .speechEndpoint
        case nil: break
        default: return .unsupported
        }
        if looksLikeChatAudio(modelId) { return .chatAudio }
        let declaresAudioOut = model?.capabilities.supportedModalities.contains(.audioOutput) ?? false
        let nameSaysTTS = LLMModel.inferDedicatedVoiceModality(id: modelId, displayName: model?.displayName ?? modelId)
            .map { $0.contains(.audioOutput) } ?? false
        return (declaresAudioOut || nameSaysTTS) ? .speechEndpoint : .unsupported
    }

    // MARK: - ASR

    /// OpenRouter splits transcription across TWO endpoints that serve
    /// DISJOINT model sets: `/audio/transcriptions` takes only the models of
    /// the transcription catalog, and `chat/completions` with `input_audio`
    /// takes only chat models (it explicitly rejects whisper: "is a
    /// transcription model and cannot be used with the chat/completions
    /// endpoint"). So this is routed per model.
    ///
    /// [T-openrouter-voice-catalog] A tagged model goes by its tag ("stt" →
    /// transcriptions, a chat model → chat). The name rule below is only the
    /// fallback for untagged ids — 10 of the 22 live transcription models
    /// (qwen3-asr, voxtral, parakeet, chirp-3, …) match none of its words.
    override func usesChatBasedASR(model: LLMModel) -> Bool {
        Self.routesASRThroughChat(model)
    }

    static func routesASRThroughChat(_ model: LLMModel) -> Bool {
        if VoiceRole.openRouterChatAudioModels.contains(model.id) { return true }
        if let role = model.voiceRole { return role != VoiceRole.stt }
        return !isDedicatedTranscriptionModel(model.id)
    }

    /// FALLBACK ONLY, for models with no catalog tag (custom entries, models
    /// saved before OpenMinis#280): ids that look like transcription models.
    ///
    /// Deepgram is matched VENDOR-qualified, never by the bare engine name:
    /// `nova-2`/`nova-3` alone also matches `amazon/nova-2-lite-v1`, a chat
    /// model that would then be misrouted.
    static func isDedicatedTranscriptionModel(_ modelId: String) -> Bool {
        let id = modelId.lowercased()
        return id.contains("whisper")
            || id.contains("transcribe")     // gpt-4o[-mini]-transcribe
            || id.contains("deepgram/")
    }

    // MARK: - Auth + attribution

    /// Response-body phrases OpenRouter uses when the credential itself is
    /// rejected. Only these make a 401/403 an auth error.
    private static let authFailurePhrases = [
        "user not found", "no auth credentials", "invalid api key", "invalid_api_key",
    ]

    static func isAuthFailureBody(status: Int, body: Data?) -> Bool {
        guard status == 401 || status == 403,
              let body, let text = String(data: body, encoding: .utf8)?.lowercased() else { return false }
        return authFailurePhrases.contains { text.contains($0) }
    }

    /// [T-openrouter-voice-catalog] OpenMinis#280. A 401 on OpenRouter is not
    /// always about the key: sending a model to a route it is not served on
    /// also came back 401, and reporting that as "check the API key" sent users
    /// hunting for a credential problem that did not exist. Only a body that
    /// actually says so is an auth error; anything else keeps its status and
    /// OpenRouter's own message (`VoiceProviderError.httpError`).
    override func isAuthFailure(status: Int, body: Data?) -> Bool {
        Self.isAuthFailureBody(status: status, body: body)
    }

    /// Same attribution headers the models request already sends.
    override func applyVoiceAuth(_ request: inout URLRequest) {
        super.applyVoiceAuth(&request)
        request.setValue("https://github.com/OpenMinis/OpenMinis", forHTTPHeaderField: "HTTP-Referer")
        request.setValue("Minis App", forHTTPHeaderField: "X-Title")
    }

    // MARK: - TTS

    /// The voices OpenAI's audio-preview models accept, as returned verbatim in
    /// their own 400 ("Supported values are: …"). Used only to recognise a
    /// valid name on the CHAT-AUDIO route, never to pick one — and never
    /// applied to dedicated speech models, whose voices are their own.
    private static let knownVoices: Set<String> = [
        "alloy", "echo", "fable", "onyx", "nova", "shimmer", "coral",
        "verse", "ballad", "ash", "sage", "marin", "cedar",
    ]

    /// Pick the `audio.voice` value for the CHAT-AUDIO route.
    ///
    /// Callers do not agree on what `VoiceOutputRequest.voice` means: for
    /// vendors where a catalog entry IS a voice (ElevenLabs voice_id, Doubao
    /// speaker) the entry id is passed straight through, and Quick Test follows
    /// that convention by sending `entry.model.id`. For chat-audio the model
    /// and the voice are separate axes, so that convention arrives as
    /// `voice: "openai/gpt-audio"` and the upstream answers
    /// "Invalid value: 'openai/gpt-audio'. Supported values are: 'alloy', …".
    ///
    /// So: honour a voice the vendor actually knows, and otherwise fall back to
    /// the default rather than forwarding something that is certain to fail.
    private func resolvedVoice(_ requested: String?, modelId: String) -> String {
        guard let requested, !requested.isEmpty else { return defaultVoiceOutputVoice() }
        let lowered = requested.lowercased()
        if Self.knownVoices.contains(lowered) { return lowered }
        orLogger.info("OpenRouter chat-audio: ignoring non-voice '\(requested)' for \(modelId), using \(defaultVoiceOutputVoice())")
        return defaultVoiceOutputVoice()
    }

    /// `voice` for the speech endpoint: forwarded as-is, because each vendor's
    /// voices are its own (fish-audio reference ids, minimax voice_ids, kokoro
    /// names) and OpenAI's list says nothing about them. Omitted when empty, or
    /// when it is just the model id — Quick Test and read-aloud pass the entry
    /// id as the voice, and a model id is never a voice.
    static func speechVoice(_ requested: String?, modelId: String) -> String? {
        guard let v = requested?.trimmingCharacters(in: .whitespaces), !v.isEmpty, v != modelId else { return nil }
        return v
    }

    /// `POST /v1/audio/speech` for a dedicated speech model.
    override func buildVoiceOutputRequest(_ request: VoiceOutputRequest) throws -> URLRequest {
        let modelId = request.model ?? defaultVoiceOutputModel()
        let urlStr = composedURLString(path: voiceOutputEndpointPath())
        guard let url = URL(string: urlStr) else {
            throw VoiceProviderError.parseError("Invalid URL: \(urlStr)")
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        applyVoiceAuth(&urlRequest)

        var body: [String: Any] = [
            "model": modelId,
            "input": request.input,
            "response_format": request.responseFormat.rawValue,
        ]
        if let voice = Self.speechVoice(request.voice, modelId: modelId) { body["voice"] = voice }
        if let speed = request.speed { body["speed"] = speed }
        urlRequest.httpBody = try JSONSerialization.data(withJSONObject: body)
        return urlRequest
    }

    override func synthesize(_ request: VoiceOutputRequest) async throws -> Data {
        let modelId = request.model ?? defaultVoiceOutputModel()
        switch Self.ttsRoute(modelId: modelId, model: request.resolvedModel) {
        case .speechEndpoint:
            return try await executeRequest(buildVoiceOutputRequest(request))
        case .unsupported:
            orLogger.warning("OpenRouter TTS: \(modelId) is not a speech model (voiceRole=\(request.resolvedModel?.voiceRole ?? "nil"))")
            throw VoiceProviderError.unsupported(
                "\(modelId) does not support speech synthesis on OpenRouter. Pick a text-to-speech model.")
        case .chatAudio:
            break
        }

        let urlStr = composedURLString(path: "/v1/chat/completions")
        guard let url = URL(string: urlStr) else {
            throw VoiceProviderError.parseError("Invalid URL: \(urlStr)")
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        applyVoiceAuth(&urlRequest)

        let body: [String: Any] = [
            "model": modelId,
            "modalities": ["text", "audio"],
            "audio": [
                "voice": resolvedVoice(request.voice, modelId: modelId),
                // pcm16 is the ONLY value accepted while streaming, and
                // streaming is mandatory — so this is fixed, not a preference.
                "format": "pcm16",
            ],
            "stream": true,
            "messages": [
                ["role": "user", "content": request.input],
            ],
        ]
        urlRequest.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (bytes, response) = try await URLSession.shared.bytes(for: urlRequest)
        guard let http = response as? HTTPURLResponse else {
            throw VoiceProviderError.parseError("Non-HTTP response")
        }
        guard (200..<300).contains(http.statusCode) else {
            // Drain the body so the server's message survives into the error —
            // an opaque "HTTP 400" is what made this bug hard to place.
            var errData = Data()
            for try await b in bytes { errData.append(b) }
            if isAuthFailure(status: http.statusCode, body: errData) {
                orLogger.error("OpenRouter voice auth failed: HTTP \(http.statusCode)")
                throw VoiceProviderError.authError
            }
            orLogger.error("OpenRouter chat-audio failed: HTTP \(http.statusCode) body=\(String(data: errData, encoding: .utf8) ?? "")")
            throw VoiceProviderError.httpError(http.statusCode, errData)
        }

        var pcm = Data()
        var transcript = ""
        for try await line in bytes.lines {
            guard line.hasPrefix("data:") else { continue }
            let payload = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
            if payload == "[DONE]" { break }
            guard let chunk = payload.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: chunk) as? [String: Any],
                  let choices = obj["choices"] as? [[String: Any]] else { continue }
            for choice in choices {
                guard let delta = choice["delta"] as? [String: Any],
                      let audio = delta["audio"] as? [String: Any] else { continue }
                if let b64 = audio["data"] as? String,
                   let part = Data(base64Encoded: b64) {
                    pcm.append(part)
                }
                if let t = audio["transcript"] as? String { transcript += t }
            }
        }

        guard !pcm.isEmpty else {
            throw VoiceProviderError.parseError(
                "OpenRouter returned no audio data for \(modelId)"
                + (transcript.isEmpty ? "" : " (transcript: \(transcript.prefix(80)))"))
        }
        orLogger.info("OpenRouter chat-audio ok model=\(modelId) pcmBytes=\(pcm.count) transcriptChars=\(transcript.count)")
        return Self.wrapPCM16InWAV(pcm, sampleRate: Self.pcmSampleRate)
    }

    /// Wrap signed 16-bit little-endian mono PCM in a minimal WAV container so
    /// `AVAudioPlayer(data:)` can open it.
    static func wrapPCM16InWAV(_ pcm: Data, sampleRate: Int) -> Data {
        let channels = 1, bitsPerSample = 16
        let byteRate = sampleRate * channels * bitsPerSample / 8
        let blockAlign = channels * bitsPerSample / 8
        let dataSize = UInt32(pcm.count)
        var wav = Data()
        func le32(_ v: UInt32) { var x = v.littleEndian; wav.append(Data(bytes: &x, count: 4)) }
        func le16(_ v: UInt16) { var x = v.littleEndian; wav.append(Data(bytes: &x, count: 2)) }
        wav.append("RIFF".data(using: .ascii)!)
        le32(36 + dataSize)
        wav.append("WAVE".data(using: .ascii)!)
        wav.append("fmt ".data(using: .ascii)!)
        le32(16)
        le16(1)                          // PCM
        le16(UInt16(channels))
        le32(UInt32(sampleRate))
        le32(UInt32(byteRate))
        le16(UInt16(blockAlign))
        le16(UInt16(bitsPerSample))
        wav.append("data".data(using: .ascii)!)
        le32(dataSize)
        wav.append(pcm)
        return wav
    }
}
