import Foundation
import os.log

private let logger = AppLogger(category: "GeminiAgent")

/// AgentProvider implementation that wraps GeminiProvider for the unified agent loop.
final class GeminiAgentProvider: AgentProvider {

    let provider: GeminiProvider

    var name: String { provider.name }
    var model: LLMModel { provider.model }
    var defaultMaxTokens: Int { 16_384 }

    init(provider: GeminiProvider) {
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
                // [T-gemini-thinking-persist] Gemini streams thoughts as
                // `.thinkingDelta` only, which paints the UI block but is never
                // persisted: `reasoning_content` (the column ChatStore rebuilds
                // the thinking block from on reload) is fed exclusively by
                // `.reasoningContent`. Without this the thinking text was
                // visible live and gone after a restart. Accumulate the turn's
                // thoughts and emit them ONCE at the terminal event, mirroring
                // how OpenAIAgentProvider emits at `[DONE]` / `response.completed`.
                var reasoningAccumulator = ""
                var emittedReasoning = false

                // Emit before `.done` so the consumer has the full reasoning on
                // the same turn it finalises. Idempotent: several terminal paths
                // exist (`.finishReason`, `.done`, and plain stream end), and a
                // duplicate emit would overwrite `result.reasoningContent` with
                // the same text at best, or double it at worst.
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
                            // Reset text tracking — next text delta will start a new block
                            emittedTextStart = false
                            hasToolCalls = true

                            let id = UUID().uuidString
                            let metadata = thoughtSignature.map { ToolCallMetadata(thoughtSignature: $0) }
                            continuation.yield(.contentBlockStart(.toolUse(id: id, name: name)))
                            // Gemini delivers complete function calls at once (no streaming partial JSON)
                            continuation.yield(.toolCallComplete(id: id, name: name, args: args, metadata: metadata))

                        case .usage(let u):
                            continuation.yield(.usage(u))

                        case .responseModel(let m):
                            continuation.yield(.responseModel(m))

                        case .finishReason(let reason):
                            // Gemini sends STOP even when function calls are present,
                            // so override to .toolUse if any tool calls were emitted.
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
                    // Stream ended without a terminal event (server closed the
                    // connection after the last chunk): the thoughts so far are
                    // still real and must not be dropped.
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

    // Internal rather than private ONLY so ToolResultImageWireTests can pin the
    // tool-result image contract directly. See [T-openai-tool-result-image].
    func convertMessages(_ messages: [AgentMessage]) -> [[String: Any]] {
        // Pre-scan: build tool call ID → name map so functionResponse can always
        // include the required name (ToolResult loaded from DB may have name="").
        var toolNameMap: [String: String] = [:]
        for msg in messages {
            for part in msg.parts {
                if case .toolUse(let id, let name, _, _) = part {
                    toolNameMap[id] = name
                }
            }
        }

        // Gemini 3.x requires thoughtSignature on every functionCall when thinking
        // is enabled. Collect IDs of unsigned tool calls so we can convert them
        // (and their paired functionResponses) to text summaries instead.
        //
        // [T-gemini3-parallel-thoughtsig] The unit is the MESSAGE, not the call.
        //
        // Gemini emits ONE thoughtSignature per parallel BATCH, attached to the
        // first functionCall; the rest of the batch legitimately carries none.
        // Judging each call on its own therefore replayed the signed one as a
        // real functionCall while its siblings became text — splitting a batch
        // the signature describes as a whole. Gemini rejects that with
        // `400 "Corrupted thought signature."`.
        //
        // Found on Android first (only because concurrent tool dispatch made
        // parallel batches common there) and confirmed from device data: one
        // assistant message with five parallel subagent_task calls, exactly one
        // signature (length 4096) and four nil, failing on every retry after a
        // restart — a restart being what forces the history to be rebuilt from
        // the persisted parts, which is when this rule runs.
        //
        // A signature belongs to the model turn, so the turn is the unit that
        // can be replayed or downgraded. All-or-nothing per message: a
        // fully-signed turn keeps the fast path, a mixed one goes entirely to
        // text, which is always accepted. The condemnation deliberately does not
        // spread further — an older unsigned turn must not drag down a later
        // fully-signed one, which would discard reasoning context that replays
        // perfectly well.
        let requiresSig = model.id.lowercased().contains("gemini-3")
        var unsignedToolCallIds: Set<String> = []
        if requiresSig {
            for msg in messages {
                var idsInMessage: [String] = []
                var anyUnsigned = false
                for part in msg.parts {
                    if case .toolUse(let id, _, _, _) = part {
                        idsInMessage.append(id)
                        if toolCallMetadataMap[id]?.thoughtSignature == nil { anyUnsigned = true }
                    }
                }
                if anyUnsigned { unsignedToolCallIds.formUnion(idsInMessage) }
            }
            if !unsignedToolCallIds.isEmpty {
                logger.info("[GeminiAgent] Converting \(unsignedToolCallIds.count) unsigned tool call(s) to text for Gemini 3.x compatibility")
            }
        }

        return messages.map { msg in
            let role = msg.role == .user ? "user" : "model"
            var parts: [[String: Any]] = []

            for part in msg.parts {
                switch part {
                case .text(let text):
                    // [T-gemini-empty-part-oneof-400] Gemini rejects {"text": ""}
                    // with an uninitialized-oneof 400. Skip a truly empty text
                    // part when the turn has other parts; the parts.isEmpty
                    // fallback below covers a turn whose only content was empty.
                    if !text.isEmpty {
                        parts.append(GeminiWireFormat.textPart(text))
                    }

                case .toolUse(let id, let name, let input, _):
                    if unsignedToolCallIds.contains(id) {
                        parts.append(["text": Self.narratedToolCall(name: name, input: input)])
                    } else {
                        let sig = toolCallMetadataMap[id]?.thoughtSignature
                        parts.append(GeminiConversation.functionCallPart(
                            name: name, args: input, thoughtSignature: sig
                        ))
                    }

                case .toolResult(let id, let name, let content, _, let imageData, let imageMimeType, _, _, _):
                    // Resolve tool name: prefer the name from the matching toolUse part
                    // (ToolResult loaded from DB may have name="" since it's not persisted there)
                    let resolvedName = (!name.isEmpty ? name : toolNameMap[id]) ?? "unknown"
                    if unsignedToolCallIds.contains(id) {
                        parts.append(["text": Self.narratedToolResult(name: resolvedName, content: content)])
                        // Still include image data if present
                        if let data = imageData {
                            let mime = imageMimeType ?? "image/jpeg"
                            parts.append(["inlineData": ["mimeType": mime, "data": data.base64EncodedString()]])
                        }
                    } else {
                        // [T-gemini-empty-part-oneof-400] Never ship an empty
                        // result string into the functionResponse payload.
                        parts.append(GeminiConversation.functionResponsePart(
                            name: resolvedName,
                            response: GeminiWireFormat.functionResponseResult(content)
                        ))
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
                    // Gemini doesn't support enum directly in all cases, but we can add it to description
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

    /// Stores thought signatures per tool call ID so they can be echoed back.
    /// Updated by the agent loop when it processes toolCallComplete events,
    /// and restored from persisted data on session load.
    private var toolCallMetadataMap: [String: ToolCallMetadata] = [:]

    /// Store metadata from a tool call so it can be echoed in subsequent messages.
    func recordToolCallMetadata(id: String, metadata: ToolCallMetadata?) {
        if let metadata {
            toolCallMetadataMap[id] = metadata
        }
    }

    /// Restore thought signatures from a pre-built metadata map on session resume.
    func restoreToolCallMetadata(_ map: [String: ToolCallMetadata]) {
        toolCallMetadataMap.merge(map) { _, new in new }
    }

    // MARK: - Unsigned tool-call narration [T-gemini-unsigned-narration]

    /// How much of a downgraded tool result to inline. The old cap was 500
    /// chars, which cut a 14k-char page fetch down to its first sentence and
    /// left the model unable to use its own earlier work. Large outputs are
    /// already offloaded upstream (the `[CONTEXT OFFLOADED]` stub carries a
    /// `file_read` path), so this only bounds genuinely inline results.
    static let narratedResultLimit = 2000

    /// A Gemini 3.x request must carry a `thoughtSignature` on every
    /// `functionCall`; calls that have none (produced by another model before a
    /// switch, or by a run whose signatures we no longer hold) cannot be sent
    /// structurally and are rendered as history text instead.
    ///
    /// That text is deliberately PLAIN PROSE, not a bracketed pseudo-marker.
    /// The previous form — `[Called shell_execute with: {...}]` — is a format
    /// the model reads as part of the transcript and imitates: it starts
    /// emitting `[Called …]` as literal output instead of calling the tool,
    /// which is the exact in-context-learning failure that made the
    /// Responses-API encrypted-reasoning summary get stripped rather than
    /// echoed (see OpenAIAgentProvider's `encryptedReasoningMarker` branch).
    /// Prose describing what happened carries the same information with no
    /// syntax worth copying.
    static func narratedToolCall(name: String, input: [String: Any]) -> String {
        // `isValidJSONObject` FIRST: JSONSerialization raises an ObjC
        // NSInvalidArgumentException on a non-JSON leaf (Data, Date, NaN…),
        // and `try?` does not catch an ObjC exception — it would crash the
        // request build. Tool args normally come from decoded JSON, but a
        // replayed/synthesised call is not guaranteed to.
        let argsDesc: String = {
            guard JSONSerialization.isValidJSONObject(input),
                  let data = try? JSONSerialization.data(withJSONObject: input, options: [.sortedKeys]),
                  let str = String(data: data, encoding: .utf8) else { return "{}" }
            return str
        }()
        return "Earlier in this conversation, the \(name) tool was run with arguments \(argsDesc)."
    }

    static func narratedToolResult(name: String, content: String) -> String {
        guard !content.isEmpty else {
            return "It returned no output."
        }
        if content.count <= narratedResultLimit {
            return "It returned:\n\(content)"
        }
        // Keep the head — the tail is what a truncation marker would occupy —
        // and say plainly that more exists, without inventing a marker syntax.
        let head = String(content.prefix(narratedResultLimit))
        return "It returned (showing the first \(narratedResultLimit) of \(content.count) characters):\n\(head)"
    }
}

// MARK: - AgentParamType Extension

extension AgentParamType {
    var geminiType: String {
        switch self {
        case .string: return "STRING"
        case .integer: return "INTEGER"
        case .boolean: return "BOOLEAN"
        }
    }
}
