import Foundation
import SwiftAnthropic
import os.log

private let logger = AppLogger(category: "AnthropicProvider")

/// Strips a trailing "/v1" segment (with or without trailing slashes) so the
/// SwiftAnthropic SDK can safely append "/v1/messages" itself. Slash hygiene
/// (collapsing multiple `/` into one) is also handled here — the SDK does a
/// raw string concat, so passing `https://x/anthropic/` would produce
/// `https://x/anthropic//v1/messages`.
private func stripV1Suffix(_ base: String) -> String {
    var s = base
    while s.hasSuffix("/") { s = String(s.dropLast()) }
    if s.hasSuffix("/v1") { s = String(s.dropLast(3)) }
    while s.hasSuffix("/") { s = String(s.dropLast()) }
    return s
}

final class AnthropicProvider: LLMProvider {
    let name = "Anthropic"
    var model: LLMModel

    /// The underlying SDK service — exposed for direct tool-use calls.
    let service: AnthropicService

    /// Whether this provider uses Claude Code OAuth credentials.
    let isClaudeCode: Bool

    /// Beta headers sent with each request (for debug logging).
    let betaHeaders: [String]?

    /// True when the resolved base path is the official Anthropic API.
    /// Third-party Anthropic-compat proxies (e.g. DeepSeek's
    /// `eai.dpinfra.com/anthropic` `deepseek-v4-pro` endpoint) require
    /// the previous turn's `thinking` block to be echoed back when
    /// thinking is on, but lack the cryptographic signature the real
    /// API would emit — so we synthesize a thinking block locally for
    /// those endpoints only, and leave the official endpoint alone
    /// where signature-less thinking blocks are rejected.
    let isOfficialAnthropicEndpoint: Bool

    /// Required system prompt prefix for Claude Code OAuth credentials.
    ///
    /// The value is not hardcoded here: at build time the "Generate Provider
    /// Customization" script phase reads the `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT`
    /// build setting (sourced from `Configs/ProviderCustomization.xcconfig`) and
    /// emits `ProviderCustomizationGenerated.anthropicOAuthIdentifierPrompt`,
    /// which is compiled into the binary. The value is deliberately NOT stored
    /// in Info.plist. That xcconfig is tracked in the private repo (filled in)
    /// but excluded from the public mirror, which ships only
    /// `ProviderCustomization.xcconfig.example` with an empty value — so a build
    /// there generates an empty string and fails here the first time an OAuth
    /// (Claude Code) request needs the prompt, by design.
    static let claudeCodeSystemPrompt: String = {
        let value = ProviderCustomizationGenerated.anthropicOAuthIdentifierPrompt
        guard !value.isEmpty else {
            fatalError(
                "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT is not configured. Copy "
                + "Configs/ProviderCustomization.xcconfig.example to "
                + "Configs/ProviderCustomization.xcconfig and set "
                + "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT before building with Claude Code OAuth."
            )
        }
        return value
    }()

    /// Parse the `-<major>-<minor>` (or `-<major>.<minor>`) version out of a Claude model id.
    /// The minor segment is optional: a single-segment version (`claude-fable-5`,
    /// `claude-opus-5`) parses as `(major, 0)`. Returns nil for non-Claude ids or
    /// ids without any version segment.
    static func parseClaudeVersion(_ modelId: String) -> (major: Int, minor: Int)? {
        let lower = modelId.lowercased()
        guard lower.contains("claude") else { return nil }
        // Accepts `claude-opus-4-7`, `claude-sonnet-4-6-thinking`,
        // `anthropic/claude-opus-4.7`, `claude-fable-5`, `claude-opus-5`, etc.
        // The minor group is optional but greedy, so `claude-3-5-sonnet` still
        // parses its first PAIR as (3,5) — never (3,0).
        let pattern = #"[-/]?(\d+)(?:[-.](\d+))?(?:\b|[^0-9])"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(lower.startIndex..., in: lower)
        guard let match = regex.firstMatch(in: lower, range: range),
              match.numberOfRanges >= 3,
              let majorRange = Range(match.range(at: 1), in: lower),
              let major = Int(lower[majorRange]) else {
            return nil
        }
        // Group 2 (minor) is absent for single-segment versions → minor 0.
        let minor: Int
        if let minorRange = Range(match.range(at: 2), in: lower),
           let parsed = Int(lower[minorRange]) {
            minor = parsed
        } else {
            minor = 0
        }
        return (major, minor)
    }

    /// Claude models from 4.6 onward reject the `temperature` parameter.
    /// Returns true if the given model id is a Claude model whose version is >= 4.6.
    static func modelRejectsTemperature(_ modelId: String) -> Bool {
        guard let v = parseClaudeVersion(modelId) else { return false }
        return v.major > 4 || (v.major == 4 && v.minor >= 6)
    }

    /// Claude 4.6 and later use *adaptive* thinking — `thinking.type="adaptive"` plus
    /// `output_config.effort = "low"|"medium"|"high"|"xhigh"|"max"` — and silently
    /// ignore the older `thinking.type="enabled" + budget_tokens` form. Older Claude
    /// models still need the budget-based form.
    static func modelUsesAdaptiveThinking(_ modelId: String) -> Bool {
        guard let v = parseClaudeVersion(modelId) else { return false }
        return v.major > 4 || (v.major == 4 && v.minor >= 6)
    }

    /// [T-ios-claude5-thinking-disabled-400] Whether this model accepts an
    /// explicit `thinking: {"type": "disabled"}` on the wire.
    ///
    /// Claude 4.6-4.x adaptive models DO: they think by default when the field
    /// is absent, so "thinking off" has to be stated, or a small-max_tokens
    /// call burns its whole budget on thinking and returns empty text (the
    /// reason `setThinkingDisabled` exists at all).
    ///
    /// Claude 5+ does NOT. The API rejects the value outright:
    ///
    ///     [invalid_request_error] "thinking.type.disabled" is not supported
    ///     for this model. Thinking defaults to adaptive mode when not
    ///     specified; use "thinking.type.enabled" with "budget_tokens" for
    ///     extended thinking.
    ///
    /// So on 5+ the correct encoding of "off" is to send NO thinking field —
    /// the server's adaptive default handles it. Reported against fable 5
    /// (Claude Opus 5, official OAuth) where every non-thinking message 400'd.
    ///
    /// Deliberately keyed on the version rather than a model allowlist: this is
    /// a generation-wide API contract change, and an allowlist would 400 again
    /// on the next Claude 5 variant nobody remembered to add.
    static func modelAcceptsExplicitThinkingDisabled(_ modelId: String) -> Bool {
        guard let v = parseClaudeVersion(modelId) else { return false }
        return v.major == 4 && v.minor >= 6
    }

    /// Applies the per-model temperature policy: returns nil if the model rejects temperature.
    func effectiveTemperature(_ temperature: Double?) -> Double? {
        if Self.modelRejectsTemperature(model.id) { return nil }
        return temperature
    }

    /// True iff the resolved base path is the official Anthropic API host.
    /// Used to decide whether to synthesize signature-less `thinking` content
    /// blocks for Anthropic-compat proxies that demand the field but don't
    /// produce real signatures.
    static func isOfficialEndpoint(_ base: String) -> Bool {
        guard let url = URL(string: base), let host = url.host?.lowercased() else { return false }
        return host == "api.anthropic.com" || host.hasSuffix(".anthropic.com")
    }

    init(apiKey: String, model: LLMModel = .claudeHaiku45, basePath: String? = nil, appendV1Suffix: Bool = true, customUserAgent: String? = nil) {
        self.model = model
        self.isClaudeCode = false
        self.betaHeaders = nil
        let resolvedBase = appendV1Suffix
            ? stripV1Suffix(basePath ?? "https://api.anthropic.com")
            : (basePath ?? "https://api.anthropic.com")
        self.isOfficialAnthropicEndpoint = Self.isOfficialEndpoint(resolvedBase)
        self.service = AnthropicServiceFactory.service(
            apiKey: apiKey,
            basePath: resolvedBase,
            betaHeaders: nil,
            httpClient: EagerStreamingHTTPClient(customUserAgent: customUserAgent)
        )
    }

    /// Manual token constructor: sends both `x-api-key` and `Authorization: Bearer` headers
    /// for maximum compatibility with third-party proxies and Coding Plan endpoints.
    init(manualToken: String, model: LLMModel = .claudeHaiku45, basePath: String? = nil, appendV1Suffix: Bool = true, customUserAgent: String? = nil) {
        self.model = model
        self.isClaudeCode = false
        self.betaHeaders = nil
        let resolvedBase = appendV1Suffix
            ? stripV1Suffix(basePath ?? "https://api.anthropic.com")
            : (basePath ?? "https://api.anthropic.com")
        self.isOfficialAnthropicEndpoint = Self.isOfficialEndpoint(resolvedBase)
        self.service = AnthropicServiceFactory.service(
            apiKey: manualToken,
            basePath: resolvedBase,
            betaHeaders: nil,
            httpClient: DualAuthHTTPClient(customUserAgent: customUserAgent)
        )
    }

    init(
        oauthTokenProvider: @escaping @Sendable () async throws -> String,
        model: LLMModel = .claudeHaiku45,
        basePath: String? = nil,
        appendV1Suffix: Bool = true
    ) {
        let headers = ["oauth-2025-04-20"]
        self.model = model
        self.isClaudeCode = true
        self.betaHeaders = headers
        let resolvedBase = appendV1Suffix
            ? stripV1Suffix(basePath ?? "https://api.anthropic.com")
            : (basePath ?? "https://api.anthropic.com")
        self.isOfficialAnthropicEndpoint = Self.isOfficialEndpoint(resolvedBase)
        self.service = AnthropicServiceFactory.service(
            apiKey: "oauth-placeholder",
            basePath: resolvedBase,
            betaHeaders: headers,
            httpClient: OAuthHTTPClient(tokenProvider: oauthTokenProvider)
        )
    }

    /// Build the effective system prompt.
    /// For Claude Code OAuth, uses array format with the required prefix as a separate text block.
    /// This ensures Anthropic's server-side check sees the exact Claude Code prompt as the first block.
    func resolveSystemPrompt(_ userPrompt: String?) -> MessageParameter.System? {
        if isClaudeCode {
            // OAuth (Claude Code): two blocks — base system prompt + user system prompt with cache
            let base = MessageParameter.Cache(text: Self.claudeCodeSystemPrompt, cacheControl: nil)
            guard let extra = userPrompt, !extra.isEmpty else {
                return .list([base])
            }
            let userBlock = MessageParameter.Cache(text: extra,
                cacheControl: .init(type: .ephemeral))
            return .list([base, userBlock])
        }
        // API key (including Anthropic-compatible proxies): single block with cache_control
        // so prompt caching works on all Anthropic-compatible endpoints
        guard let prompt = userPrompt, !prompt.isEmpty else { return nil }
        let block = MessageParameter.Cache(text: prompt,
            cacheControl: .init(type: .ephemeral))
        return .list([block])
    }

    func sendMessage(
        messages: [LLMMessage],
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?
    ) async throws -> LLMResponse {
        let parameter = MessageParameter(
            model: .other(model.id),
            messages: messages.map { $0.toAnthropicMessage() },
            maxTokens: maxTokens,
            system: resolveSystemPrompt(systemPrompt),
            temperature: effectiveTemperature(temperature)
        )

        #if DEBUG
        logRequestParameter(parameter)
        #endif

        let response: MessageResponse
        do {
            response = try await service.createMessage(parameter)
        } catch {
            logger.error("createMessage failed: \(String(describing: error))")
            #if DEBUG
            let debugBody = "// ERROR: \(String(describing: error).prefix(500))\n// model: \(model.id)"
            LastAPIRequestBody.shared.set(debugBody, provider: "Anthropic")
            LastAPIRequestBody.shared.setRequestMeta(url: "anthropic:///v1/messages", method: "POST", headers: nil)
            #endif
            throw mapError(error)
        }

        #if DEBUG
        logResponse(response)
        #endif

        let text = response.content.compactMap { block -> String? in
            if case .text(let str, _) = block { return str }
            return nil
        }.joined()

        return LLMResponse(
            text: text,
            stopReason: response.stopReason,
            usage: response.usage.toLLMUsage()
        )
    }

    func streamMessage(
        messages: [LLMMessage],
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?
    ) async throws -> AsyncThrowingStream<LLMStreamChunk, Error> {
        let parameter = MessageParameter(
            model: .other(model.id),
            messages: messages.map { $0.toAnthropicMessage() },
            maxTokens: maxTokens,
            system: resolveSystemPrompt(systemPrompt),
            temperature: effectiveTemperature(temperature)
        )

        #if DEBUG
        logRequestParameter(parameter)
        #endif

        let stream: AsyncThrowingStream<MessageStreamResponse, Error>
        do {
            stream = try await service.streamMessage(parameter)
        } catch {
            logger.error("streamMessage failed: \(String(describing: error))")
            #if DEBUG
            let debugBody = "// ERROR: \(String(describing: error).prefix(500))\n// model: \(model.id)"
            LastAPIRequestBody.shared.set(debugBody, provider: "Anthropic")
            LastAPIRequestBody.shared.setRequestMeta(url: "anthropic:///v1/messages", method: "POST", headers: nil)
            #endif
            throw mapError(error)
        }

        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    for try await response in stream {
                        #if DEBUG
                        self.logStreamEvent(response)
                        #endif
                        guard let event = response.streamEvent else { continue }
                        switch event {
                        case .messageStart:
                            continuation.yield(.started)
                            if let usage = response.message?.usage {
                                continuation.yield(.usage(usage.toLLMUsage()))
                            }
                        case .contentBlockDelta:
                            if let text = response.delta?.text {
                                continuation.yield(.text(text))
                            }
                        case .messageDelta:
                            if let usage = response.usage {
                                continuation.yield(.usage(usage.toLLMUsage()))
                            }
                            continuation.yield(.finished(stopReason: response.delta?.stopReason))
                        case .messageStop, .contentBlockStart, .contentBlockStop:
                            break
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: mapError(error))
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - Debug Logging

    #if DEBUG
    func logRequestParameter(_ parameter: MessageParameter) {
        var parts: [String] = []
        parts.append("📤 Anthropic Request")
        parts.append("  model: \(parameter.model)")
        parts.append("  maxTokens: \(parameter.maxTokens)")
        parts.append("  temperature: \(parameter.temperature.map { String($0) } ?? "nil")")
        parts.append("  stream: \(parameter.stream)")
        parts.append("  betaHeaders: \(betaHeaders?.joined(separator: ", ") ?? "none")")
        parts.append("  authMode: \(isClaudeCode ? "OAuth (Claude Code)" : "API Key")")
        parts.append("  messages: \(parameter.messages.count)")

        // System prompt — encode to JSON then extract text previews
        if let system = parameter.system {
            switch system {
            case .text(let text):
                let preview = String(text.prefix(200))
                parts.append("  system: .text(\"\(preview)\"" + (text.count > 200 ? "..." : "") + ")")
            case .list(let blocks):
                parts.append("  system: .list [\(blocks.count) block(s)]")
                if let data = try? JSONEncoder().encode(blocks),
                   let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] {
                    for (i, dict) in arr.enumerated() {
                        let text = (dict["text"] as? String) ?? ""
                        let preview = String(text.prefix(200))
                        let cached = dict["cache_control"] != nil ? " [cached]" : ""
                        parts.append("    [\(i)] \"\(preview)\"" + (text.count > 200 ? "..." : "") + cached)
                    }
                }
            }
        } else {
            parts.append("  system: nil")
        }

        // Tools — encode to JSON to read names
        if let data = try? JSONEncoder().encode(parameter),
           let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let tools = dict["tools"] as? [[String: Any]] {
            let names = tools.compactMap { $0["name"] as? String }
            parts.append("  tools: [\(names.joined(separator: ", "))]")
        }

        // Full request body as pretty JSON (for detailed debugging)
        if let data = try? JSONEncoder().encode(parameter),
           let json = try? JSONSerialization.jsonObject(with: data),
           let pretty = try? JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted, .sortedKeys]),
           let str = String(data: pretty, encoding: .utf8) {
            let truncated = String(str.prefix(3000))
            parts.append("  Full Body:")
            parts.append(truncated)
            if str.count > 3000 {
                parts.append("  ... (\(str.count) chars total)")
            }
        }

        // Messages summary (last 3)
        let recent = parameter.messages.suffix(3)
        for msg in recent {
            let contentPreview: String
            switch msg.content {
            case .text(let text):
                contentPreview = String(text.prefix(100)) + (text.count > 100 ? "..." : "")
            case .list(let objects):
                contentPreview = "\(objects.count) content block(s)"
            }
            parts.append("  [\(msg.role)] \(contentPreview)")
        }

        logger.debug("\(parts.joined(separator: "\n"))")
    }

    /// Log a non-streaming API response.
    func logResponse(_ response: MessageResponse) {
        var parts: [String] = ["📥 Anthropic Response"]
        parts.append("  stopReason: \(response.stopReason ?? "nil")")
        parts.append("  usage: in=\(response.usage.inputTokens ?? 0) out=\(response.usage.outputTokens)" +
                     " cache_create=\(response.usage.cacheCreationInputTokens ?? 0)" +
                     " cache_read=\(response.usage.cacheReadInputTokens ?? 0)")
        parts.append("  content blocks: \(response.content.count)")
        for (i, block) in response.content.enumerated() {
            switch block {
            case .text(let text, _):
                let preview = String(text.prefix(200))
                parts.append("    [\(i)] text(\(text.count) chars): \"\(preview)\"" + (text.count > 200 ? "..." : ""))
            case .toolUse(let toolUse):
                let inputStr = "\(toolUse.input)"
                parts.append("    [\(i)] tool_use: \(toolUse.name) (id: \(toolUse.id)) input: \(String(inputStr.prefix(200)))")
            default:
                parts.append("    [\(i)] \(block)")
            }
        }
        logger.debug("\(parts.joined(separator: "\n"))")
    }

    /// Log a streaming event from the Anthropic API.
    func logStreamEvent(_ response: MessageStreamResponse) {
        guard let event = response.streamEvent else { return }
        var parts: [String] = []
        switch event {
        case .messageStart:
            parts.append("📥 Anthropic Stream: messageStart")
            if let msg = response.message {
                parts.append("  model: \(msg.model)")
                parts.append("  usage: in=\(msg.usage.inputTokens ?? 0)")
            }
        case .contentBlockStart:
            if let block = response.contentBlock {
                if block.type == "tool_use" {
                    parts.append("📥 Anthropic Stream: contentBlockStart tool_use name=\(block.name ?? "?") id=\(block.id ?? "?")")
                } else {
                    parts.append("📥 Anthropic Stream: contentBlockStart type=\(block.type)")
                }
            }
        case .contentBlockDelta:
            if let delta = response.delta {
                if let text = delta.text {
                    let preview = String(text.prefix(80))
                    parts.append("📥 Anthropic Stream: contentBlockDelta text(\(text.count) chars): \"\(preview)\"" + (text.count > 80 ? "..." : ""))
                } else if let json = delta.partialJson {
                    let preview = String(json.prefix(120))
                    parts.append("📥 Anthropic Stream: contentBlockDelta partialJson(\(json.count) chars): \(preview)" + (json.count > 120 ? "..." : ""))
                }
            }
        case .contentBlockStop:
            parts.append("📥 Anthropic Stream: contentBlockStop")
        case .messageDelta:
            let stop = response.delta?.stopReason ?? "nil"
            parts.append("📥 Anthropic Stream: messageDelta stopReason=\(stop)")
            if let usage = response.usage {
                parts.append("  usage: out=\(usage.outputTokens)")
            }
        case .messageStop:
            parts.append("📥 Anthropic Stream: messageStop")
        }
        if !parts.isEmpty {
            logger.debug("\(parts.joined(separator: "\n"))")
        }
    }
    #endif

    func mapError(_ error: Error) -> LLMError {
        if error is CancellationError {
            return .cancelled
        }

        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain {
            if nsError.code == NSURLErrorCancelled { return .cancelled }
            return .networkError(underlying: error)
        }

        // Check for captured error body from URLProtocol (has the full JSON error)
        let capturedBody = LastAPIErrorBody.shared.take()
        let description = String(describing: error)

        if description.contains("authentication") || description.contains("401") {
            let detail = Self.extractMessageFromBody(capturedBody)
                ?? Self.extractAPIErrorMessage(from: description)
            return .invalidAPIKey(detail: detail)
        }
        if description.contains("429") || description.lowercased().contains("rate") {
            return .rateLimited
        }

        // Transient server errors (5xx): retry same model, do not trigger group fallback.
        let transientCodes = ["500", "502", "503", "504", "529"]
        if transientCodes.contains(where: { description.contains($0) }) {
            let detailedMessage = Self.extractMessageFromBody(capturedBody)
                ?? Self.extractAPIErrorMessage(from: description)
            return .transientError(message: detailedMessage)
        }

        // Try to extract a detailed message from the captured error body first,
        // then fall back to parsing the SDK's error description
        let detailedMessage = Self.extractMessageFromBody(capturedBody)
            ?? Self.extractAPIErrorMessage(from: description)
        return .providerError(message: detailedMessage)
    }

    /// Parse the JSON error body captured by the URLProtocol to extract the human-readable message.
    /// Supports both Anthropic format `{"error":{"type":"...","message":"..."}}` and
    /// proxy format `{"error":"string message"}`.
    static func extractMessageFromBody(_ body: String?) -> String? {
        guard let body, let data = body.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        // Anthropic official format: error is a dict with type + message
        if let error = json["error"] as? [String: Any],
           let message = error["message"] as? String {
            let errorType = error["type"] as? String ?? "error"
            return "[\(errorType)] \(message)"
        }
        // Proxy format: error is a plain string
        if let message = json["error"] as? String {
            return message
        }
        // Generic: return raw body if it's short enough
        if body.count <= 500 {
            return body
        }
        return nil
    }

    /// Extract a human-readable error message from SwiftAnthropic's error description.
    /// Input format: `responseUnsuccessful(description: "status code 400some error message")`
    /// Output: `"[400] some error message"` or the original string if parsing fails.
    static func extractAPIErrorMessage(from description: String) -> String {
        // Try to extract the description string from the enum case
        let pattern = #"responseUnsuccessful\(description:\s*"(.+?)"\)"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators]),
              let match = regex.firstMatch(in: description, range: NSRange(description.startIndex..., in: description)),
              let range = Range(match.range(at: 1), in: description) else {
            return description
        }
        let inner = String(description[range])
        // The SDK concatenates: "status code NNN" + errorResponse.error.message
        let codePattern = #"^status code (\d+)"#
        guard let codeRegex = try? NSRegularExpression(pattern: codePattern),
              let codeMatch = codeRegex.firstMatch(in: inner, range: NSRange(inner.startIndex..., in: inner)),
              let codeRange = Range(codeMatch.range(at: 1), in: inner) else {
            return inner
        }
        let code = String(inner[codeRange])
        let messageStart = inner.index(inner.startIndex, offsetBy: codeMatch.range.length)
        let message = String(inner[messageStart...]).trimmingCharacters(in: .whitespaces)
        if message.isEmpty {
            return "HTTP \(code)"
        }
        return "[\(code)] \(message)"
    }
}

// MARK: - Mapping Helpers

private extension LLMMessage {
    func toAnthropicMessage() -> MessageParameter.Message {
        let role: MessageParameter.Message.Role = self.role == .user ? .user : .assistant
        guard !images.isEmpty else {
            return MessageParameter.Message(role: role, content: .text(content))
        }
        // Build multi-part content with images + text
        typealias ContentObject = MessageParameter.Message.Content.ContentObject
        typealias ImageSource = MessageParameter.Message.Content.ImageSource
        var parts: [ContentObject] = []
        for img in images {
            let mediaType: ImageSource.MediaType
            switch img.mimeType {
            case "image/jpeg", "image/jpg": mediaType = .jpeg
            case "image/gif": mediaType = .gif
            case "image/webp": mediaType = .webp
            default: mediaType = .png
            }
            let source = ImageSource(
                type: .base64,
                mediaType: mediaType,
                data: img.data.base64EncodedString()
            )
            parts.append(.image(source))
        }
        if !content.isEmpty {
            parts.append(.text(content))
        }
        return MessageParameter.Message(role: role, content: .list(parts))
    }
}

extension MessageResponse.Usage {
    func toLLMUsage() -> LLMUsage {
        LLMUsage(
            inputTokens: inputTokens ?? 0,
            outputTokens: outputTokens,
            cacheCreationInputTokens: cacheCreationInputTokens,
            cacheReadInputTokens: cacheReadInputTokens
        )
    }
}
