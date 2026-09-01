import Foundation
import os.log

private let logger = AppLogger(category: "GeminiProvider")

/// [T-thinking-rules-phase2] Shared category with the OpenAI/Anthropic paths so one grep
/// shows every provider's thinking resolution (design §8).
private let thinkingLogger = AppLogger(category: "Thinking")

/// URLSession with a long read timeout for SSE streaming.
/// URLSession.shared uses a 60-second timeoutIntervalForRequest which can kill
/// long-running streams (e.g. large file_write generation). This session gives
/// the server 10 minutes of idle time before timing out.
private let geminiStreamingSession: URLSession = {
    let config = URLSessionConfiguration.default
    config.timeoutIntervalForRequest = 600  // 10 minutes
    let session = URLSession(configuration: config)
    // Evict this session's pooled (possibly stale) connections on network
    // transitions — see LLMSessionRegistry / Android #740.
    LLMSessionRegistry.shared.register(session)
    return session
}()

/// LLMProvider implementation for Google Gemini models.
/// Calls the Gemini REST API directly via URLSession (no SDK dependency).
/// Supports both API key and OAuth Bearer token authentication.
final class GeminiProvider: LLMProvider {
    let name = "Google"
    var model: LLMModel

    private static let defaultBasePath = "https://generativelanguage.googleapis.com/v1beta"
    private let basePath: String
    private let cloudCodeBase = "https://cloudcode-pa.googleapis.com/v1internal"
    private let authMode: GeminiAuthMode

    /// GCP project ID for Cloud Code Assist routing. Set externally after OAuth + project discovery.
    var gcpProjectID: String?

    /// Extra generation config fields to merge into `generationConfig` (e.g. aspect ratio, resolution).
    /// Set by callers like ModelUseOffloadBridge before calling sendMessage.
    var extraGenerationConfig: [String: Any]?


    /// Stable session ID for Cloud Code Assist (persists for the lifetime of this provider instance).
    private let sessionId = UUID().uuidString


    /// When true, requests route through Cloud Code Assist (cloudcode-pa.googleapis.com).
    private var useCloudCode: Bool {
        if case .oauth = authMode, gcpProjectID != nil { return true }
        return false
    }

    enum GeminiAuthMode {
        case apiKey(String)
        case oauth(() async throws -> String)
    }

    init(apiKey: String, model: LLMModel = .gemini25Flash, customBasePath: String? = nil) {
        self.model = model
        self.authMode = .apiKey(apiKey)
        self.basePath = customBasePath ?? Self.defaultBasePath
    }

    init(oauthTokenProvider: @escaping () async throws -> String, model: LLMModel = .gemini25Flash, customBasePath: String? = nil) {
        self.model = model
        self.authMode = .oauth(oauthTokenProvider)
        self.basePath = customBasePath ?? Self.defaultBasePath
    }

    // MARK: - LLMProvider

    func sendMessage(
        messages: [LLMMessage],
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?
    ) async throws -> LLMResponse {
        let body = buildRequestBody(messages: messages, systemPrompt: systemPrompt, maxTokens: maxTokens, temperature: temperature)
        let request = try await buildRequest(path: "generateContent", body: body)

        #if DEBUG
        logFullRequest(request)
        #endif

        // Use the 600s session — image-generation models (e.g.
        // gemini-3.1-flash-image-preview) routinely take 1–5 min, well past
        // URLSession.shared's 60s timeoutIntervalForRequest.
        let (data, response) = try await geminiStreamingSession.data(for: request)
        try checkHTTPResponse(response, data: data)

        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]

        #if DEBUG
        logGenerateResponse(json)
        #endif

        let (text, _, media) = extractResponseContent(json)
        let usage = extractUsage(json)

        var result = LLMResponse(text: text, stopReason: "end_turn", usage: usage)
        result.mediaAttachments = media
        return result
    }

    func streamMessage(
        messages: [LLMMessage],
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?
    ) async throws -> AsyncThrowingStream<LLMStreamChunk, Error> {
        let body = buildRequestBody(messages: messages, systemPrompt: systemPrompt, maxTokens: maxTokens, temperature: temperature)
        let request = try await buildRequest(path: "streamGenerateContent?alt=sse", body: body)

        #if DEBUG
        logFullRequest(request)
        #endif

        let (bytes, response) = try await geminiStreamingSession.bytes(for: request)
        try await checkStreamHTTPResponse(response, bytes: bytes)

        return AsyncThrowingStream { continuation in
            let task = Task { [self] in
                var started = false
                do {
                    for try await line in bytes.lines {
                        guard !Task.isCancelled else { break }

                        guard line.hasPrefix("data: ") else { continue }
                        let jsonStr = String(line.dropFirst(6))
                        guard let jsonData = jsonStr.data(using: .utf8),
                              let json = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] else { continue }

                        if !started {
                            continuation.yield(.started)
                            started = true
                        }

                        let (text, _, _) = extractResponseContent(json)
                        if !text.isEmpty {
                            continuation.yield(.text(text))
                        }

                        if let usage = extractUsage(json) {
                            continuation.yield(.usage(usage))
                        }

                        #if DEBUG
                        var events: [GeminiStreamEvent] = []
                        if !text.isEmpty { events.append(.textDelta(text)) }
                        if let u = extractUsage(json) { events.append(.usage(u)) }
                        if !events.isEmpty { logStreamChunk(json, events: events) }
                        #endif
                    }
                    continuation.yield(.finished(stopReason: "end_turn"))
                    continuation.finish()
                } catch {
                    if !Task.isCancelled {
                        continuation.finish(throwing: mapError(error))
                    }
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - Tool-Use Streaming (for agent loop)

    /// Stream a Gemini API call with function calling support.
    /// Returns events that the agent loop can process in the same pattern as Anthropic.
    func streamWithTools(
        contents: [[String: Any]],
        systemPrompt: String?,
        maxTokens: Int,
        tools: [[String: Any]],
        thinkingLevel: ThinkingLevel = .off
    ) async throws -> AsyncThrowingStream<GeminiStreamEvent, Error> {
        var body: [String: Any] = ["contents": contents]

        if let sys = systemPrompt, !sys.isEmpty {
            body["systemInstruction"] = ["parts": [["text": sys]]]
        }

        var genConfig: [String: Any] = [
            "maxOutputTokens": maxTokens,
            "temperature": 0.7,
        ]
        let thinkCfg = thinkingLevel.isEnabled ? elevatedThinkingConfig(level: thinkingLevel) : minimalThinkingConfig()
        if !thinkCfg.isEmpty { genConfig["thinkingConfig"] = thinkCfg }
        body["generationConfig"] = genConfig

        if !tools.isEmpty {
            body["tools"] = [["functionDeclarations": tools]]
            body["toolConfig"] = ["functionCallingConfig": ["mode": "AUTO"]]
        }

        let request = try await buildRequest(path: "streamGenerateContent?alt=sse", body: body)

        #if DEBUG
        logRequest(body)
        logFullRequest(request)
        #endif

        let (bytes, response) = try await geminiStreamingSession.bytes(for: request)
        try await checkStreamHTTPResponse(response, bytes: bytes)

        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    for try await line in bytes.lines {
                        guard !Task.isCancelled else { break }
                        guard line.hasPrefix("data: ") else { continue }

                        let jsonStr = String(line.dropFirst(6))
                        guard let jsonData = jsonStr.data(using: .utf8),
                              let json = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] else { continue }

                        let events = self.parseStreamChunk(json)
                        #if DEBUG
                        if !events.isEmpty {
                            self.logStreamChunk(json, events: events)
                        }
                        #endif
                        for event in events {
                            continuation.yield(event)
                        }
                    }
                    continuation.yield(.done)
                    continuation.finish()
                } catch {
                    if !Task.isCancelled {
                        continuation.finish(throwing: self.mapError(error))
                    }
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Non-streaming call with tools — used when streaming is not needed.
    func generateWithTools(
        contents: [[String: Any]],
        systemPrompt: String?,
        maxTokens: Int,
        tools: [[String: Any]]
    ) async throws -> GeminiGenerateResponse {
        var body: [String: Any] = ["contents": contents]

        if let sys = systemPrompt, !sys.isEmpty {
            body["systemInstruction"] = ["parts": [["text": sys]]]
        }

        var genConfig: [String: Any] = [
            "maxOutputTokens": maxTokens,
            "temperature": 0.7,
        ]
        let thinkCfg = self.minimalThinkingConfig()
        if !thinkCfg.isEmpty { genConfig["thinkingConfig"] = thinkCfg }
        body["generationConfig"] = genConfig

        if !tools.isEmpty {
            body["tools"] = [["functionDeclarations": tools]]
            body["toolConfig"] = ["functionCallingConfig": ["mode": "AUTO"]]
        }

        let request = try await buildRequest(path: "generateContent", body: body)

        #if DEBUG
        logRequest(body)
        logFullRequest(request)
        #endif

        // Use the 600s session — same image-generation timeout rationale as sendMessage.
        let (data, response) = try await geminiStreamingSession.data(for: request)
        try checkHTTPResponse(response, data: data)

        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]

        #if DEBUG
        logGenerateResponse(json)
        #endif

        return parseGenerateResponse(json)
    }

    // MARK: - Request Building

    // Rules per model family:
    //   Gemini 3.x Pro  — thinkingLevel, cannot disable, minimum "low"
    //   Gemini 3.x Flash — thinkingLevel, "minimal" effectively disables
    //   Gemini 2.5 Pro  — thinkingBudget, cannot disable, minimum 128
    //   Gemini 2.5 Flash — thinkingBudget 0 disables
    //   Gemini 2.5 Flash Lite — no thinking support
    /// [T-thinking-rules-phase2] The per-family rules now live in
    /// `ThinkingRuleResolver.geminiThinkingConfig` so every vendor's thinking contract is
    /// described in ONE place. Behaviour is byte-for-byte unchanged — pinned by
    /// ThinkingWireGeminiAnthropicSnapshotTests (182 rows generated from the previous
    /// implementation of this method and committed before the migration).
    /// True for models whose response modality is not text (TTS today; image/embedding
    /// share the trait). The Gemini API rejects `systemInstruction` on these with
    /// 400 "Developer instruction is not enabled for this model", exactly as it rejects
    /// a thinking config.
    ///
    /// [T-gemini-tts-thinking-400 / OpenMinis#226] Keyed off the DECLARED MODALITY rather
    /// than the model id, because that is the property actually responsible: the id-suffix
    /// list in `ThinkingRuleResolver` exists only because the thinking rules are consulted
    /// without a capability object in scope. Here we have one, so use it.
    private var rejectsSystemInstruction: Bool {
        model.capabilities.supportedModalities.contains(.audioOutput)
    }

    private func minimalThinkingConfig() -> [String: Any] {
        let cfg = ThinkingRuleResolver.geminiThinkingConfig(modelId: model.id, level: .off)
        thinkingLogger.info("[resolve] provider=gemini model=\(model.id) level=off keys=[\(cfg.keys.sorted().joined(separator: ","))]")
        return cfg
    }

    /// Thinking config when user explicitly enables thinking mode.
    /// Thinking config when the user explicitly enables thinking mode.
    /// [T-thinking-rules-phase2] See `minimalThinkingConfig` — the family rules now live
    /// in `ThinkingRuleResolver.geminiThinkingConfig`.
    private func elevatedThinkingConfig(level: ThinkingLevel) -> [String: Any] {
        let cfg = ThinkingRuleResolver.geminiThinkingConfig(modelId: model.id, level: level)
        thinkingLogger.info("[resolve] provider=gemini model=\(model.id) level=\(level.rawValue) keys=[\(cfg.keys.sorted().joined(separator: ","))]")
        return cfg
    }

    private func buildRequestBody(
        messages: [LLMMessage],
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?
    ) -> [String: Any] {
        var body: [String: Any] = [:]

        // Convert messages to Gemini format
        var contents: [[String: Any]] = []
        for msg in messages {
            let role = msg.role == .user ? "user" : "model"
            var parts: [[String: Any]] = []
            // Add image parts first
            for img in msg.images {
                parts.append([
                    "inlineData": [
                        "mimeType": img.mimeType,
                        "data": img.data.base64EncodedString()
                    ]
                ])
            }
            if !msg.content.isEmpty {
                parts.append(["text": msg.content])
            }
            contents.append(["role": role, "parts": parts])
        }
        body["contents"] = contents

        // [OpenMinis#226] Audio-output models reject systemInstruction outright. Dropping
        // it costs nothing here: the sub-agent preamble it carries is guidance for a text
        // responder, and a TTS model's job is to speak `contents` verbatim.
        if let sys = systemPrompt, !sys.isEmpty, !rejectsSystemInstruction {
            body["systemInstruction"] = ["parts": [["text": sys]]]
        }

        var config: [String: Any] = ["maxOutputTokens": maxTokens]
        if let temp = temperature {
            config["temperature"] = temp
        }
        let thinkCfg = self.minimalThinkingConfig()
        if !thinkCfg.isEmpty {
            config["thinkingConfig"] = thinkCfg
        }

        // Set responseModalities based on model capabilities
        let modalities = model.capabilities.supportedModalities
        if modalities.contains(.audioOutput) {
            config["responseModalities"] = ["AUDIO"]
        } else if modalities.contains(.imageOutput) {
            config["responseModalities"] = ["TEXT", "IMAGE"]
        }

        // Merge extra generation config (e.g. aspect ratio from minis-model-use)
        if let extra = extraGenerationConfig {
            for (key, value) in extra {
                config[key] = value
            }
        }

        body["generationConfig"] = config

        return body
    }

    private func buildRequest(path: String, body: [String: Any]) async throws -> URLRequest {
        let urlString: String
        let finalBody: [String: Any]

        if useCloudCode {
            // Cloud Code Assist endpoint — `<base>:<method>` (Google method syntax,
            // not a path segment). Pass the colon-form as a single trailing
            // segment so URLBuilding.join doesn't tokenize it.
            urlString = URLBuilding.join(cloudCodeBase + ":" + path)
            finalBody = wrapCloudCodeBody(body)
        } else {
            switch authMode {
            case .apiKey(let key):
                let separator = path.contains("?") ? "&" : "?"
                urlString = URLBuilding.join(basePath, "models", "\(model.id):\(path)\(separator)key=\(key)")
            case .oauth:
                urlString = URLBuilding.join(basePath, "models", "\(model.id):\(path)")
            }
            finalBody = body
        }

        guard let url = URL(string: urlString) else {
            throw LLMError.providerError(message: "Invalid Gemini API URL")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if case .oauth(let tokenProvider) = authMode {
            let token = try await tokenProvider()
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        if useCloudCode {
            // Cloud Code Assist (OAuth) requires the identity-locked GeminiCLI UA.
            applyGeminiCLIHeaders(&request)
        } else {
            // Direct REST (api-key / manual-token) path sets no UA otherwise, so
            // URLSession would send its build-number default. Use the app default
            // (Minis/<marketing>) instead. Never overrides the Cloud Code UA above.
            request.setValue(MinisUserAgent.default, forHTTPHeaderField: "User-Agent")
        }

        // sortedKeys: stable byte-level prefix so server-side prompt caches
        // (Gemini implicit cache, disk-cached providers, etc.) can match across calls.
        request.httpBody = try JSONSerialization.data(withJSONObject: finalBody, options: [.sortedKeys])
        return request
    }

    // MARK: - Cloud Code Assist Helpers

    /// Client metadata for Cloud Code Assist — matches Gemini CLI's enum values.
    private let cloudCodeClientMetadata: [String: String] = [
        "ideType": "GEMINI_CLI",
        "platform": "DARWIN_ARM64",
        "pluginType": "GEMINI",
    ]

    /// Apply Gemini CLI-compatible headers for Cloud Code Assist requests.
    /// User-Agent + Client-Metadata are set — the OAuth2 Bearer token is added separately in buildRequest().
    private func applyGeminiCLIHeaders(_ request: inout URLRequest) {
        request.setValue("GeminiCLI/0.30.0/\(model.id) (darwin; arm64)", forHTTPHeaderField: "User-Agent")
        if let metadataJSON = try? JSONSerialization.data(withJSONObject: cloudCodeClientMetadata),
           let metadataStr = String(data: metadataJSON, encoding: .utf8) {
            request.setValue(metadataStr, forHTTPHeaderField: "Client-Metadata")
        }
    }

    /// Generate a short random hex prompt ID matching Gemini CLI format:
    /// `Math.random().toString(16).slice(2)` → e.g. "a1b2c3d4e5f67"
    private func generatePromptId() -> String {
        let hex = String(UInt64.random(in: 0...UInt64.max), radix: 16)
        return String(hex.prefix(13))
    }

    /// Wrap the standard Gemini request body in the Cloud Code Assist envelope (Gemini CLI format).
    private func wrapCloudCodeBody(_ body: [String: Any]) -> [String: Any] {
        var request = body
        request["session_id"] = sessionId
        return [
            "model": model.id,
            "project": gcpProjectID!,
            "user_prompt_id": generatePromptId(),
            "request": request,
        ]
    }

    // MARK: - Response Parsing

    /// Unwrap Cloud Code Assist envelope if present.
    /// Cloud Code wraps: `{ "response": { "candidates": [...] } }` instead of `{ "candidates": [...] }`.
    private func unwrapCloudCodeResponse(_ json: [String: Any]) -> [String: Any] {
        if useCloudCode, let inner = json["response"] as? [String: Any] {
            return inner
        }
        return json
    }

    /// Parsed function call from a response, including optional thought signature.
    struct ParsedFunctionCall {
        let functionCall: [String: Any]
        let thoughtSignature: String?
    }

    private func extractResponseContent(_ json: [String: Any]) -> (text: String, functionCalls: [ParsedFunctionCall], media: [LLMMediaAttachment]) {
        let effective = unwrapCloudCodeResponse(json)
        guard let candidates = effective["candidates"] as? [[String: Any]],
              let first = candidates.first,
              let content = first["content"] as? [String: Any],
              let parts = content["parts"] as? [[String: Any]] else {
            return ("", [], [])
        }

        var text = ""
        var functionCalls: [ParsedFunctionCall] = []
        var media: [LLMMediaAttachment] = []

        for part in parts {
            if let t = part["text"] as? String {
                text += t
            }
            if let fc = part["functionCall"] as? [String: Any] {
                let thoughtSig = part["thoughtSignature"] as? String
                functionCalls.append(ParsedFunctionCall(functionCall: fc, thoughtSignature: thoughtSig))
            }
            // Gemini returns generated images/audio as inlineData parts
            if let inlineData = part["inlineData"] as? [String: Any],
               let mimeType = inlineData["mimeType"] as? String,
               let b64 = inlineData["data"] as? String,
               let data = Data(base64Encoded: b64) {
                let mediaType: LLMMediaAttachment.MediaType
                if mimeType.hasPrefix("image/") { mediaType = .image }
                else if mimeType.hasPrefix("audio/") { mediaType = .audio }
                else if mimeType.hasPrefix("video/") { mediaType = .video }
                else { mediaType = .image } // fallback
                media.append(LLMMediaAttachment(type: mediaType, mimeType: mimeType, data: data))
            }
        }

        return (text, functionCalls, media)
    }

    private func extractUsage(_ json: [String: Any]) -> LLMUsage? {
        let effective = unwrapCloudCodeResponse(json)
        guard let usage = effective["usageMetadata"] as? [String: Any] else { return nil }
        let input = usage["promptTokenCount"] as? Int ?? 0
        let output = usage["candidatesTokenCount"] as? Int ?? 0
        return LLMUsage(inputTokens: input, outputTokens: output,
                        cacheCreationInputTokens: nil, cacheReadInputTokens: nil)
    }

    /// Parse a single SSE chunk from streaming response into events.
    private func parseStreamChunk(_ json: [String: Any]) -> [GeminiStreamEvent] {
        var events: [GeminiStreamEvent] = []
        let effective = unwrapCloudCodeResponse(json)

        guard let candidates = effective["candidates"] as? [[String: Any]],
              let first = candidates.first,
              let content = first["content"] as? [String: Any],
              let parts = content["parts"] as? [[String: Any]] else {
            // Could be usage-only chunk
            if let usage = extractUsage(json) {
                events.append(.usage(usage))
            }
            return events
        }

        for part in parts {
            if let text = part["text"] as? String, !text.isEmpty {
                let isThought = part["thought"] as? Bool ?? false
                events.append(isThought ? .thinkingDelta(text) : .textDelta(text))
            }
            if let fc = part["functionCall"] as? [String: Any],
               let name = fc["name"] as? String {
                let args = fc["args"] as? [String: Any] ?? [:]
                let thoughtSig = part["thoughtSignature"] as? String
                events.append(.functionCall(name: name, args: args, thoughtSignature: thoughtSig))
            }
        }

        // Check finish reason
        if let finishReason = first["finishReason"] as? String {
            if finishReason == "STOP" {
                events.append(.finishReason("end_turn"))
            } else if finishReason == "MAX_TOKENS" {
                events.append(.finishReason("max_tokens"))
            }
            // Note: Gemini doesn't have a "tool_use" finish reason; function calls
            // come as parts in the same response chunk. We detect them from the events.
        }

        if let usage = extractUsage(json) {
            events.append(.usage(usage))
        }

        return events
    }

    private func parseGenerateResponse(_ json: [String: Any]) -> GeminiGenerateResponse {
        let (text, functionCalls, _) = extractResponseContent(json)
        let usage = extractUsage(json)

        // Check finish reason
        let effective = unwrapCloudCodeResponse(json)
        var finishReason: String? = nil
        if let candidates = effective["candidates"] as? [[String: Any]],
           let first = candidates.first {
            finishReason = first["finishReason"] as? String
        }

        return GeminiGenerateResponse(
            text: text,
            functionCalls: functionCalls,
            finishReason: finishReason,
            usage: usage
        )
    }

    // MARK: - Helpers

    /// Check streaming response — reads the byte stream to extract error body on non-2xx status.
    private func checkStreamHTTPResponse(_ response: URLResponse, bytes: URLSession.AsyncBytes) async throws {
        guard let http = response as? HTTPURLResponse else { return }
        guard !(200..<300).contains(http.statusCode) else { return }

        // Drain the stream to get the error body
        var bodyData = Data()
        for try await byte in bytes.lines {
            bodyData.append(contentsOf: byte.utf8)
            if bodyData.count > 4096 { break }
        }
        let body = String(data: bodyData, encoding: .utf8) ?? ""
        #if DEBUG
        logger.error("Gemini streaming API error \(http.statusCode): \(body.prefix(1000))")
        #else
        logger.error("Gemini streaming API error \(http.statusCode)")
        #endif

        if http.statusCode == 401 || http.statusCode == 403 {
            throw LLMError.invalidAPIKey(detail: "Gemini HTTP \(http.statusCode): \(String(body.prefix(200)))")
        }
        if http.statusCode == 429 {
            throw LLMError.rateLimited
        }
        let transientStatusCodes: Set<Int> = [500, 502, 503, 504, 529]
        if transientStatusCodes.contains(http.statusCode) {
            throw LLMError.transientError(message: "Gemini API error \(http.statusCode): \(body.prefix(200))")
        }
        throw LLMError.providerError(message: "Gemini API error \(http.statusCode): \(body.prefix(500))")
    }

    private func checkHTTPResponse(_ response: URLResponse, data: Data?) throws {
        guard let http = response as? HTTPURLResponse else { return }

        if http.statusCode == 401 || http.statusCode == 403 {
            let bodyStr = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            throw LLMError.invalidAPIKey(detail: "Gemini HTTP \(http.statusCode): \(String(bodyStr.prefix(200)))")
        }

        if http.statusCode == 429 {
            throw LLMError.rateLimited
        }

        guard (200..<300).contains(http.statusCode) else {
            let body: String
            if let data {
                body = String(data: data, encoding: .utf8) ?? ""
            } else {
                body = "HTTP \(http.statusCode)"
            }
            #if DEBUG
            logger.error("Gemini API error \(http.statusCode): \(body.prefix(500))")
            #else
            logger.error("Gemini API error \(http.statusCode)")
            #endif
            let transientStatusCodes: Set<Int> = [500, 502, 503, 504, 529]
            if transientStatusCodes.contains(http.statusCode) {
                throw LLMError.transientError(message: "Gemini API error \(http.statusCode): \(body.prefix(200))")
            }
            throw LLMError.providerError(message: "Gemini API error \(http.statusCode): \(body.prefix(200))")
        }
    }

    func mapError(_ error: Error) -> LLMError {
        if error is CancellationError { return .cancelled }
        if let llm = error as? LLMError { return llm }

        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain {
            if nsError.code == NSURLErrorCancelled { return .cancelled }
            return .networkError(underlying: error)
        }
        return .providerError(message: error.localizedDescription)
    }

    #if DEBUG
    private func logRequest(_ body: [String: Any]) {
        var parts: [String] = ["📤 Gemini Request"]
        parts.append("  model: \(model.id)")
        parts.append("  endpoint: \(useCloudCode ? "Cloud Code Assist" : "standard")")
        if useCloudCode, let project = gcpProjectID {
            parts.append("  project: \(project)")
        }
        if let contents = body["contents"] as? [[String: Any]] {
            parts.append("  messages: \(contents.count)")
        }
        if let tools = body["tools"] as? [[String: Any]],
           let declarations = tools.first?["functionDeclarations"] as? [[String: Any]] {
            let names = declarations.compactMap { $0["name"] as? String }
            parts.append("  tools: [\(names.joined(separator: ", "))]")
        }
        logger.debug("\(parts.joined(separator: "\n"))")
    }

    /// Log the full outgoing URLRequest (URL, headers, body as pretty JSON).
    private func logFullRequest(_ request: URLRequest) {
        var parts: [String] = ["📤 Gemini HTTP Request"]
        // Mask `key=` query parameter to avoid logging API keys
        let urlDisplay: String = {
            guard let url = request.url,
                  var comps = URLComponents(url: url, resolvingAgainstBaseURL: false),
                  let items = comps.queryItems,
                  items.contains(where: { $0.name == "key" }) else {
                return request.url?.absoluteString ?? "<nil>"
            }
            comps.queryItems = items.map {
                $0.name == "key" ? URLQueryItem(name: "key", value: "***") : $0
            }
            return comps.url?.absoluteString ?? request.url?.absoluteString ?? "<nil>"
        }()
        parts.append("  URL: \(urlDisplay)")
        parts.append("  Method: \(request.httpMethod ?? "?")")

        // Headers (mask Authorization token)
        if let headers = request.allHTTPHeaderFields {
            parts.append("  Headers:")
            for key in headers.keys.sorted() {
                let value = headers[key] ?? ""
                if key.lowercased() == "authorization" {
                    parts.append("    \(key): Bearer ***")
                } else {
                    parts.append("    \(key): \(value)")
                }
            }
        }

        // Body as pretty JSON
        if let body = request.httpBody {
            parts.append("  Body (\(body.count) bytes):")
            if let json = try? JSONSerialization.jsonObject(with: body),
               let pretty = try? JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted, .sortedKeys]),
               let str = String(data: pretty, encoding: .utf8) {
                LastAPIRequestBody.shared.set(str, provider: "Gemini")
                let truncated = String(str.prefix(3000))
                parts.append(truncated)
                if str.count > 3000 {
                    parts.append("  ... (\(str.count) chars total)")
                }
            } else if let str = String(data: body, encoding: .utf8) {
                LastAPIRequestBody.shared.set(str, provider: "Gemini")
                parts.append("  \(String(str.prefix(1000)))")
            }
        }

        logger.debug("\(parts.joined(separator: "\n"))")
    }

    /// Log an incoming SSE chunk (raw JSON + parsed events).
    private func logStreamChunk(_ json: [String: Any], events: [GeminiStreamEvent]) {
        var parts: [String] = ["📥 Gemini SSE Chunk"]

        // Compact JSON preview
        if let data = try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys]),
           let str = String(data: data, encoding: .utf8) {
            let preview = String(str.prefix(500))
            parts.append("  raw: \(preview)" + (str.count > 500 ? "..." : ""))
        }

        // Parsed events
        for event in events {
            switch event {
            case .textDelta(let text):
                let preview = String(text.prefix(100))
                parts.append("  → textDelta(\(text.count) chars): \"\(preview)\"" + (text.count > 100 ? "..." : ""))
            case .functionCall(let name, let args, let thoughtSig):
                let argsStr = (try? JSONSerialization.data(withJSONObject: args))
                    .flatMap { String(data: $0, encoding: .utf8) } ?? "\(args)"
                var line = "  → functionCall(\(name)): \(String(argsStr.prefix(200)))"
                if let sig = thoughtSig {
                    line += " [thoughtSig: \(sig.prefix(20))...]"
                }
                parts.append(line)
            case .finishReason(let reason):
                parts.append("  → finishReason: \(reason)")
            case .thinkingDelta(let text):
                let preview = String(text.prefix(100))
                parts.append("  → thinkingDelta(\(text.count) chars): \"\(preview)\"" + (text.count > 100 ? "..." : ""))
            case .usage(let usage):
                parts.append("  → usage: in=\(usage.inputTokens) out=\(usage.outputTokens)")
            case .done:
                parts.append("  → done")
            }
        }

        logger.debug("\(parts.joined(separator: "\n"))")
    }

    /// Log a non-streaming (generateContent) response.
    private func logGenerateResponse(_ json: [String: Any]) {
        var parts: [String] = ["📥 Gemini Response"]
        if let data = try? JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted, .sortedKeys]),
           let str = String(data: data, encoding: .utf8) {
            let truncated = String(str.prefix(2000))
            parts.append(truncated)
            if str.count > 2000 {
                parts.append("... (\(str.count) chars total)")
            }
        }
        logger.debug("\(parts.joined(separator: "\n"))")
    }
    #endif
}

// MARK: - Gemini Stream Event

enum GeminiStreamEvent {
    case textDelta(String)
    case thinkingDelta(String)
    case functionCall(name: String, args: [String: Any], thoughtSignature: String?)
    case finishReason(String)
    case usage(LLMUsage)
    case done
}

// MARK: - Gemini Generate Response (non-streaming)

struct GeminiGenerateResponse {
    let text: String
    let functionCalls: [GeminiProvider.ParsedFunctionCall]
    let finishReason: String?
    let usage: LLMUsage?

    var hasFunctionCalls: Bool { !functionCalls.isEmpty }
}

// MARK: - Gemini Conversation Builder

/// Helper to build Gemini conversation history in the native format.
enum GeminiConversation {

    /// Build a user message with text content.
    static func userMessage(_ text: String) -> [String: Any] {
        ["role": "user", "parts": [["text": text]]]
    }

    /// Build a user message with multiple content objects (for tool results).
    static func userMessage(parts: [[String: Any]]) -> [String: Any] {
        ["role": "user", "parts": parts]
    }

    /// Build a model message with text.
    static func modelMessage(_ text: String) -> [String: Any] {
        ["role": "model", "parts": [["text": text]]]
    }

    /// Build a model message with parts (text + function calls).
    static func modelMessage(parts: [[String: Any]]) -> [String: Any] {
        ["role": "model", "parts": parts]
    }

    /// Build a function call part for a model message.
    /// Includes `thoughtSignature` when present (required by Gemini 3 models).
    static func functionCallPart(name: String, args: [String: Any], thoughtSignature: String? = nil) -> [String: Any] {
        var part: [String: Any] = ["functionCall": ["name": name, "args": args]]
        if let sig = thoughtSignature {
            part["thoughtSignature"] = sig
        }
        return part
    }

    /// Build a function response part for a user message.
    static func functionResponsePart(name: String, response: [String: Any]) -> [String: Any] {
        ["functionResponse": ["name": name, "response": response]]
    }

    /// Build a tool definition for the Gemini API.
    static func toolDefinition(
        name: String,
        description: String,
        parameters: [String: Any]
    ) -> [String: Any] {
        [
            "name": name,
            "description": description,
            "parameters": parameters,
        ]
    }
}
