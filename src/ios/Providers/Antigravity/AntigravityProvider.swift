import Foundation
import os.log

private let logger = AppLogger(category: "AntigravityProvider")

/// LLMProvider implementation for Antigravity (Cloud Code) models.
/// Routes requests through the daily-cloudcode-pa.googleapis.com endpoints
/// using the Antigravity request envelope format.
final class AntigravityProvider: LLMProvider {
    let name = "Antigravity"
    var model: LLMModel

    private let authTokenProvider: () async throws -> String
    var projectID: String?

    /// Stable session ID for the lifetime of this provider instance.
    private let sessionId = UUID().uuidString

    /// Cloud Code base URL resolved during project discovery.
    var activeBaseURL: String = AntigravityOAuthManager.cloudCodeBaseURLs[0]

    init(oauthTokenProvider: @escaping () async throws -> String, model: LLMModel) {
        self.model = model
        self.authTokenProvider = oauthTokenProvider
    }

    // MARK: - LLMProvider

    func sendMessage(
        messages: [LLMMessage],
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?
    ) async throws -> LLMResponse {
        let innerBody = buildInnerBody(messages: messages, systemPrompt: systemPrompt, maxTokens: maxTokens, temperature: temperature)
        let envelope = wrapEnvelope(innerBody)
        let request = try await buildRequest(path: "generateContent", body: envelope)

        #if DEBUG
        logFullRequest(request)
        #endif

        let (data, response) = try await URLSession.shared.data(for: request)
        try checkHTTPResponse(response, data: data)

        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]

        #if DEBUG
        logResponse(json)
        #endif

        let (text, _) = extractResponseContent(json)
        let usage = extractUsage(json)

        return LLMResponse(text: text, stopReason: "end_turn", usage: usage)
    }

    func streamMessage(
        messages: [LLMMessage],
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?
    ) async throws -> AsyncThrowingStream<LLMStreamChunk, Error> {
        let innerBody = buildInnerBody(messages: messages, systemPrompt: systemPrompt, maxTokens: maxTokens, temperature: temperature)
        let envelope = wrapEnvelope(innerBody)
        let request = try await buildRequest(path: "streamGenerateContent?alt=sse", body: envelope)

        #if DEBUG
        logFullRequest(request)
        #endif

        let (bytes, response) = try await URLSession.shared.bytes(for: request)
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

                        let (text, _) = extractResponseContent(json)
                        if !text.isEmpty {
                            continuation.yield(.text(text))
                        }

                        if let usage = extractUsage(json) {
                            continuation.yield(.usage(usage))
                        }
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

    func streamWithTools(
        contents: [[String: Any]],
        systemPrompt: String?,
        maxTokens: Int,
        tools: [[String: Any]],
        thinkingLevel: ThinkingLevel = .off
    ) async throws -> AsyncThrowingStream<GeminiStreamEvent, Error> {
        var inner: [String: Any] = ["contents": contents]

        if let sys = systemPrompt, !sys.isEmpty {
            inner["systemInstruction"] = ["parts": [["text": sys]]]
        }

        var genConfig: [String: Any] = [
            "maxOutputTokens": maxTokens,
            "temperature": 0.7,
        ]
        let thinkCfg = thinkingLevel.isEnabled ? elevatedThinkingConfig(level: thinkingLevel) : minimalThinkingConfig()
        if !thinkCfg.isEmpty { genConfig["thinkingConfig"] = thinkCfg }
        inner["generationConfig"] = genConfig

        if !tools.isEmpty {
            inner["tools"] = [["functionDeclarations": tools]]
            inner["toolConfig"] = ["functionCallingConfig": ["mode": "AUTO"]]
        }

        let envelope = wrapEnvelope(inner)
        let request = try await buildRequest(path: "streamGenerateContent?alt=sse", body: envelope, thinkingEnabled: thinkingLevel.isEnabled)

        #if DEBUG
        logFullRequest(request)
        #endif

        let (bytes, response) = try await URLSession.shared.bytes(for: request)
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

    func generateWithTools(
        contents: [[String: Any]],
        systemPrompt: String?,
        maxTokens: Int,
        tools: [[String: Any]]
    ) async throws -> GeminiGenerateResponse {
        var inner: [String: Any] = ["contents": contents]

        if let sys = systemPrompt, !sys.isEmpty {
            inner["systemInstruction"] = ["parts": [["text": sys]]]
        }

        var genConfig: [String: Any] = [
            "maxOutputTokens": maxTokens,
            "temperature": 0.7,
        ]
        let thinkCfg = minimalThinkingConfig()
        if !thinkCfg.isEmpty { genConfig["thinkingConfig"] = thinkCfg }
        inner["generationConfig"] = genConfig

        if !tools.isEmpty {
            inner["tools"] = [["functionDeclarations": tools]]
            inner["toolConfig"] = ["functionCallingConfig": ["mode": "AUTO"]]
        }

        let envelope = wrapEnvelope(inner)
        let request = try await buildRequest(path: "generateContent", body: envelope)

        #if DEBUG
        logFullRequest(request)
        #endif

        let (data, response) = try await URLSession.shared.data(for: request)
        try checkHTTPResponse(response, data: data)

        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]

        #if DEBUG
        logResponse(json)
        #endif

        return parseGenerateResponse(json)
    }

    // MARK: - Request Building

    private func minimalThinkingConfig() -> [String: Any] {
        let id = model.id.lowercased()

        // Claude models: no thinkingConfig (not supported by Cloud Code for Claude).
        // Thinking models (e.g. claude-opus-4-6-thinking) use server-side defaults.
        if id.contains("claude") {
            return [:]
        }

        if id.contains("gemini-3") {
            if id.contains("flash") {
                return ["thinkingLevel": "minimal"]
            } else {
                return ["thinkingLevel": "low"]
            }
        }
        if id.contains("2.5-pro") {
            return ["thinkingBudget": 128]
        }
        if id.contains("2.5-flash-lite") {
            return [:]
        }
        return ["thinkingBudget": 0]
    }

    /// Thinking config when user explicitly enables thinking mode.
    private func elevatedThinkingConfig(level: ThinkingLevel) -> [String: Any] {
        let id = model.id.lowercased()
        if id.contains("claude") { return [:] }  // Claude uses Anthropic-native thinking
        if id.contains("gemini-3") {
            let geminiLevel: String = switch level {
            case .off: "minimal"; case .low: "low"; case .medium: "medium"; case .high, .xhigh, .max, .ultra: "high"
            }
            return ["thinkingLevel": geminiLevel, "includeThoughts": true]
        }
        if id.contains("2.5-pro") {
            let budget: Int = switch level {
            case .off: 128; case .low: 2048; case .medium: 8192; case .high: 16384; case .xhigh, .max, .ultra: 32768
            }
            return ["thinkingBudget": budget, "includeThoughts": true]
        }
        if id.contains("2.5-flash") && !id.contains("lite") {
            let budget: Int = switch level {
            case .off: 0; case .low: 1024; case .medium: 4096; case .high: 8192; case .xhigh, .max, .ultra: 16384
            }
            return ["thinkingBudget": budget, "includeThoughts": true]
        }
        return minimalThinkingConfig()
    }

    private func buildInnerBody(
        messages: [LLMMessage],
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?
    ) -> [String: Any] {
        var body: [String: Any] = [:]

        var contents: [[String: Any]] = []
        for msg in messages {
            let role = msg.role == .user ? "user" : "model"
            contents.append([
                "role": role,
                "parts": [["text": msg.content]]
            ])
        }
        body["contents"] = contents

        if let sys = systemPrompt, !sys.isEmpty {
            body["systemInstruction"] = ["parts": [["text": sys]]]
        }

        var config: [String: Any] = ["maxOutputTokens": maxTokens]
        if let temp = temperature {
            config["temperature"] = temp
        }
        let thinkCfg = minimalThinkingConfig()
        if !thinkCfg.isEmpty {
            config["thinkingConfig"] = thinkCfg
        }
        body["generationConfig"] = config

        return body
    }

    /// Wrap inner request body in the Antigravity Cloud Code envelope.
    /// Matches the format used by the Antigravity IDE / antigravity-claude-proxy.
    private func wrapEnvelope(_ inner: [String: Any]) -> [String: Any] {
        var request = inner
        request["sessionId"] = sessionId
        return [
            "model": model.id,
            "userAgent": "antigravity",
            "requestType": "agent",
            "project": projectID ?? "",
            "requestId": "agent-\(UUID().uuidString)",
            "request": request,
        ]
    }

    private func buildRequest(path: String, body: [String: Any], thinkingEnabled: Bool = false) async throws -> URLRequest {
        let urlString = URLBuilding.join(activeBaseURL, "v1internal:\(path)")
        guard let url = URL(string: urlString) else {
            throw LLMError.providerError(message: "Invalid Antigravity API URL")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let token = try await authTokenProvider()
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("antigravity/1.107.0 darwin/arm64", forHTTPHeaderField: "User-Agent")
        request.setValue("antigravity", forHTTPHeaderField: "X-Client-Name")
        request.setValue("1.107.0", forHTTPHeaderField: "X-Client-Version")
        request.setValue("gl-node/18.18.2 fire/0.8.6 grpc/1.10.x", forHTTPHeaderField: "x-goog-api-client")

        // Streaming endpoints need text/event-stream accept header
        if path.contains("stream") {
            request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        } else {
            request.setValue("application/json", forHTTPHeaderField: "Accept")
        }

        // Claude thinking models need this header (either -thinking suffix or user-enabled thinking)
        let lid = model.id.lowercased()
        if lid.contains("claude") && (lid.contains("thinking") || thinkingEnabled) {
            request.setValue("interleaved-thinking-2025-05-14", forHTTPHeaderField: "anthropic-beta")
        }

        // sortedKeys: stable byte-level prefix so server-side prompt caches can
        // match across calls (Antigravity routes to multiple upstreams, some of
        // which use prefix-based caching).
        request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
        return request
    }

    // MARK: - Response Parsing

    /// Unwrap Antigravity envelope: `{ "response": { "candidates": [...] } }`.
    private func unwrapResponse(_ json: [String: Any]) -> [String: Any] {
        if let inner = json["response"] as? [String: Any] {
            return inner
        }
        return json
    }

    struct ParsedFunctionCall {
        let functionCall: [String: Any]
        let thoughtSignature: String?
    }

    private func extractResponseContent(_ json: [String: Any]) -> (text: String, functionCalls: [ParsedFunctionCall]) {
        let effective = unwrapResponse(json)
        guard let candidates = effective["candidates"] as? [[String: Any]],
              let first = candidates.first,
              let content = first["content"] as? [String: Any],
              let parts = content["parts"] as? [[String: Any]] else {
            return ("", [])
        }

        var text = ""
        var functionCalls: [ParsedFunctionCall] = []

        for part in parts {
            if let t = part["text"] as? String {
                text += t
            }
            if let fc = part["functionCall"] as? [String: Any] {
                let thoughtSig = part["thoughtSignature"] as? String
                functionCalls.append(ParsedFunctionCall(functionCall: fc, thoughtSignature: thoughtSig))
            }
        }

        return (text, functionCalls)
    }

    /// [GH#384] Antigravity speaks the same Gemini `usageMetadata` protocol, so
    /// it shares GeminiProvider's parser rather than keeping the copy-pasted
    /// version that had the same two defects (thinking tokens dropped, cache
    /// never read). See `GeminiProvider.parseUsageMetadata` for the reasoning.
    private func extractUsage(_ json: [String: Any]) -> LLMUsage? {
        let effective = unwrapResponse(json)
        guard let usage = effective["usageMetadata"] as? [String: Any] else { return nil }
        return GeminiProvider.parseUsageMetadata(usage)
    }

    private func parseStreamChunk(_ json: [String: Any]) -> [GeminiStreamEvent] {
        var events: [GeminiStreamEvent] = []
        let effective = unwrapResponse(json)
        // [T-agent-model-identity] The served model, as the API names it.
        if let mv = effective["modelVersion"] as? String, !mv.isEmpty {
            events.append(.responseModel(mv))
        }

        guard let candidates = effective["candidates"] as? [[String: Any]],
              let first = candidates.first,
              let content = first["content"] as? [String: Any],
              let parts = content["parts"] as? [[String: Any]] else {
            if let usage = extractUsage(json) {
                events.append(.usage(usage))
            }
            return events
        }

        for part in parts {
            if let text = part["text"] as? String, !text.isEmpty {
                events.append(.textDelta(text))
            }
            if let fc = part["functionCall"] as? [String: Any],
               let name = fc["name"] as? String {
                let args = fc["args"] as? [String: Any] ?? [:]
                let thoughtSig = part["thoughtSignature"] as? String
                events.append(.functionCall(name: name, args: args, thoughtSignature: thoughtSig))
            }
        }

        if let finishReason = first["finishReason"] as? String {
            if finishReason == "STOP" {
                events.append(.finishReason("end_turn"))
            } else if finishReason == "MAX_TOKENS" {
                events.append(.finishReason("max_tokens"))
            }
        }

        if let usage = extractUsage(json) {
            events.append(.usage(usage))
        }

        return events
    }

    private func parseGenerateResponse(_ json: [String: Any]) -> GeminiGenerateResponse {
        let (text, functionCalls) = extractResponseContent(json)
        let usage = extractUsage(json)

        let effective = unwrapResponse(json)
        var finishReason: String? = nil
        if let candidates = effective["candidates"] as? [[String: Any]],
           let first = candidates.first {
            finishReason = first["finishReason"] as? String
        }

        // Map to GeminiProvider.ParsedFunctionCall for GeminiGenerateResponse compatibility
        let mappedCalls = functionCalls.map {
            GeminiProvider.ParsedFunctionCall(functionCall: $0.functionCall, thoughtSignature: $0.thoughtSignature)
        }

        return GeminiGenerateResponse(
            text: text,
            functionCalls: mappedCalls,
            finishReason: finishReason,
            usage: usage
        )
    }

    // MARK: - Error Handling

    private func checkStreamHTTPResponse(_ response: URLResponse, bytes: URLSession.AsyncBytes) async throws {
        guard let http = response as? HTTPURLResponse else { return }
        guard !(200..<300).contains(http.statusCode) else { return }

        var bodyData = Data()
        for try await byte in bytes.lines {
            bodyData.append(contentsOf: byte.utf8)
            if bodyData.count > 4096 { break }
        }
        let body = String(data: bodyData, encoding: .utf8) ?? ""
        #if DEBUG
        logger.error("Antigravity streaming API error \(http.statusCode): \(body.prefix(1000))")
        #else
        logger.error("Antigravity streaming API error \(http.statusCode)")
        #endif

        if http.statusCode == 401 || http.statusCode == 403 {
            throw LLMError.invalidAPIKey(detail: "Antigravity HTTP \(http.statusCode): \(String(body.prefix(200)))")
        }
        if http.statusCode == 429 {
            throw LLMError.rateLimited
        }
        let transientStatusCodes: Set<Int> = [500, 502, 503, 504, 529]
        if transientStatusCodes.contains(http.statusCode) {
            throw LLMError.transientError(message: "Antigravity API error \(http.statusCode): \(body.prefix(200))", statusCode: http.statusCode)
        }
        throw LLMError.providerError(message: "Antigravity API error \(http.statusCode): \(body.prefix(500))")
    }

    private func checkHTTPResponse(_ response: URLResponse, data: Data?) throws {
        guard let http = response as? HTTPURLResponse else { return }

        if http.statusCode == 401 || http.statusCode == 403 {
            let bodyStr = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            throw LLMError.invalidAPIKey(detail: "Antigravity HTTP \(http.statusCode): \(String(bodyStr.prefix(200)))")
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
            logger.error("Antigravity API error \(http.statusCode): \(body.prefix(500))")
            #else
            logger.error("Antigravity API error \(http.statusCode)")
            #endif
            let transientStatusCodes: Set<Int> = [500, 502, 503, 504, 529]
            if transientStatusCodes.contains(http.statusCode) {
                throw LLMError.transientError(message: "Antigravity API error \(http.statusCode): \(body.prefix(200))", statusCode: http.statusCode)
            }
            throw LLMError.providerError(message: "Antigravity API error \(http.statusCode): \(body.prefix(200))")
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
    private func logFullRequest(_ request: URLRequest) {
        var parts: [String] = ["📤 Antigravity HTTP Request"]
        parts.append("  URL: \(request.url?.absoluteString ?? "<nil>")")
        parts.append("  Method: \(request.httpMethod ?? "?")")

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

        if let body = request.httpBody {
            parts.append("  Body (\(body.count) bytes):")
            if let json = try? JSONSerialization.jsonObject(with: body),
               let pretty = try? JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted, .sortedKeys]),
               let str = String(data: pretty, encoding: .utf8) {
                LastAPIRequestBody.shared.set(str, provider: "Antigravity")
                let truncated = String(str.prefix(3000))
                parts.append(truncated)
            }
        }

        logger.debug("\(parts.joined(separator: "\n"))")
    }

    private func logResponse(_ json: [String: Any]) {
        var parts: [String] = ["📥 Antigravity Response"]
        if let data = try? JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted, .sortedKeys]),
           let str = String(data: data, encoding: .utf8) {
            parts.append(String(str.prefix(2000)))
        }
        logger.debug("\(parts.joined(separator: "\n"))")
    }
    #endif
}
