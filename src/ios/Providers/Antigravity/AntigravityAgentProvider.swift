import Foundation
import os.log

private let logger = AppLogger(category: "AntigravityAgent")

/// AgentProvider implementation that wraps AntigravityProvider for the unified agent loop.
final class AntigravityAgentProvider: AgentProvider {

    let provider: AntigravityProvider

    var name: String { provider.name }
    var model: LLMModel { provider.model }
    var defaultMaxTokens: Int { 16_384 }

    init(provider: AntigravityProvider) {
        self.provider = provider
    }

    func streamAgentMessageClamped(
        messages: [AgentMessage],
        systemPrompt: String?,
        tools: [AgentToolDefinition],
        maxTokens: Int,
        thinkingLevel: ThinkingLevel
    ) async throws -> AsyncThrowingStream<AgentStreamEvent, Error> {
        let geminiContents = convertMessages(messages)
        let geminiTools = convertTools(tools)

        let stream: AsyncThrowingStream<GeminiStreamEvent, Error>
        do {
            stream = try await provider.streamWithTools(
                contents: geminiContents,
                systemPrompt: systemPrompt,
                maxTokens: maxTokens,
                tools: geminiTools,
                thinkingLevel: thinkingLevel
            )
        } catch {
            throw provider.mapError(error)
        }

        return AsyncThrowingStream { continuation in
            let task = Task {
                var emittedTextStart = false
                var hasToolCalls = false
                // [T-gemini-thinking-persist] Same gap as GeminiAgentProvider
                // (shared GeminiStreamEvent): thoughts arrived only as
                // `.thinkingDelta`, which paints the UI but never reaches the
                // `reasoning_content` column, so the thinking block vanished on
                // reload. Accumulate and emit once at the terminal event.
                var reasoningAccumulator = ""
                var emittedReasoning = false

                func flushReasoning() {
                    guard !emittedReasoning, !reasoningAccumulator.isEmpty else { return }
                    emittedReasoning = true
                    continuation.yield(.reasoningContent(reasoningAccumulator))
                }

                do {
                    for try await event in stream {
                        switch event {
                        case .textDelta(let text):
                            if !emittedTextStart {
                                continuation.yield(.contentBlockStart(.text))
                                emittedTextStart = true
                            }
                            continuation.yield(.textDelta(text))

                        case .thinkingDelta(let text):
                            reasoningAccumulator += text
                            continuation.yield(.thinkingDelta(text))

                        case .functionCall(let name, let args, let thoughtSignature):
                            emittedTextStart = false
                            hasToolCalls = true

                            let id = UUID().uuidString
                            let metadata = thoughtSignature.map { ToolCallMetadata(thoughtSignature: $0) }
                            continuation.yield(.contentBlockStart(.toolUse(id: id, name: name)))
                            continuation.yield(.toolCallComplete(id: id, name: name, args: args, metadata: metadata))

                        case .usage(let u):
                            continuation.yield(.usage(u))

                        case .responseModel(let m):
                            continuation.yield(.responseModel(m))

                        case .finishReason(let reason):
                            let mapped: AgentStopReason
                            if hasToolCalls {
                                mapped = .toolUse
                            } else {
                                mapped = switch reason {
                                case "max_tokens": .maxTokens
                                default: .endTurn
                                }
                            }
                            flushReasoning()
                            continuation.yield(.done(stopReason: mapped))

                        case .done:
                            flushReasoning()
                            continuation.yield(.done(stopReason: hasToolCalls ? .toolUse : .endTurn))
                        }
                    }
                    // Stream closed with no terminal event — keep the thoughts.
                    flushReasoning()
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: self.provider.mapError(error))
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - Message Conversion

    /// Whether the current model is a Claude model (requires `id` on functionCall/functionResponse).
    private var isClaude: Bool { model.id.lowercased().contains("claude") }

    // Internal rather than private ONLY so ToolResultImageWireTests can pin the
    // tool-result image contract directly. See [T-openai-tool-result-image].
    func convertMessages(_ messages: [AgentMessage]) -> [[String: Any]] {
        var toolNameMap: [String: String] = [:]
        for msg in messages {
            for part in msg.parts {
                if case .toolUse(let id, let name, _, _) = part {
                    toolNameMap[id] = name
                }
            }
        }

        let requiresSig = model.id.lowercased().contains("gemini-3")
        var unsignedToolCallIds: Set<String> = []
        if requiresSig {
            for msg in messages {
                for part in msg.parts {
                    if case .toolUse(let id, _, _, _) = part {
                        if toolCallMetadataMap[id]?.thoughtSignature == nil {
                            unsignedToolCallIds.insert(id)
                        }
                    }
                }
            }
            if !unsignedToolCallIds.isEmpty {
                logger.info("[AntigravityAgent] Converting \(unsignedToolCallIds.count) unsigned tool call(s) to text")
            }
        }

        return messages.map { msg in
            let role = msg.role == .user ? "user" : "model"
            var parts: [[String: Any]] = []

            for part in msg.parts {
                switch part {
                case .text(let text):
                    parts.append(["text": text])

                case .toolUse(let id, let name, let input, _):
                    if unsignedToolCallIds.contains(id) {
                        // [T-gemini-unsigned-narration] Plain prose, not a
                        // bracketed marker the model would imitate. Shared with
                        // GeminiAgentProvider — same wire family, same hazard.
                        parts.append(["text": GeminiAgentProvider.narratedToolCall(name: name, input: input)])
                    } else {
                        let sig = toolCallMetadataMap[id]?.thoughtSignature
                        var fcPart = GeminiConversation.functionCallPart(
                            name: name, args: input, thoughtSignature: sig
                        )
                        // Claude models require an `id` field on functionCall parts.
                        if isClaude, var fc = fcPart["functionCall"] as? [String: Any] {
                            fc["id"] = "\(name)-\(id)"
                            fcPart["functionCall"] = fc
                        }
                        parts.append(fcPart)
                    }

                case .toolResult(let id, let name, let content, _, let imageData, let imageMimeType, _, _, _):
                    let resolvedName = (!name.isEmpty ? name : toolNameMap[id]) ?? "unknown"
                    if unsignedToolCallIds.contains(id) {
                        parts.append(["text": GeminiAgentProvider.narratedToolResult(name: resolvedName, content: content)])
                        if let data = imageData {
                            let mime = imageMimeType ?? "image/jpeg"
                            parts.append(["inlineData": ["mimeType": mime, "data": data.base64EncodedString()]])
                        }
                    } else {
                        var frPart = GeminiConversation.functionResponsePart(
                            name: resolvedName, response: ["result": content]
                        )
                        // Claude models require an `id` field on functionResponse parts.
                        if isClaude, var fr = frPart["functionResponse"] as? [String: Any] {
                            fr["id"] = "\(resolvedName)-\(id)"
                            frPart["functionResponse"] = fr
                        }
                        parts.append(frPart)
                        if let data = imageData {
                            let mime = imageMimeType ?? "image/jpeg"
                            parts.append(["inlineData": ["mimeType": mime, "data": data.base64EncodedString()]])
                        }
                    }

                case .imageData(let data, let mimeType, _):
                    let base64 = data.base64EncodedString()
                    parts.append(["inlineData": ["mimeType": mimeType, "data": base64]])
                }
            }

            if parts.isEmpty {
                parts.append(["text": "(empty)"])
            }

            return ["role": role, "parts": parts]
        }
    }

    // MARK: - Tool Conversion

    private func convertTools(_ tools: [AgentToolDefinition]) -> [[String: Any]] {
        tools.map { tool in
            var properties: [String: [String: Any]] = [:]
            for (name, param) in tool.parameters {
                var prop: [String: Any] = [
                    "type": param.type.geminiType,
                    "description": param.description,
                ]
                if let enumValues = param.enumValues {
                    prop["description"] = "\(param.description) (values: \(enumValues.joined(separator: ", ")))"
                }
                properties[name] = prop
            }

            var params: [String: Any] = [
                "type": "OBJECT",
                "properties": properties,
                "required": tool.required,
            ]
            if let ordering = tool.propertyOrdering {
                params["propertyOrdering"] = ordering
            }
            return GeminiConversation.toolDefinition(
                name: tool.name,
                description: tool.description,
                parameters: params
            )
        }
    }

    // MARK: - Thought Signature Tracking

    private var toolCallMetadataMap: [String: ToolCallMetadata] = [:]

    func recordToolCallMetadata(id: String, metadata: ToolCallMetadata?) {
        if let metadata {
            toolCallMetadataMap[id] = metadata
        }
    }

    func restoreToolCallMetadata(_ map: [String: ToolCallMetadata]) {
        toolCallMetadataMap.merge(map) { _, new in new }
    }
}
