import Foundation
import SwiftAnthropic
import UIKit
import os.log

private let logger = AppLogger(category: "AnthropicAgent")

/// [T-thinking-rules-phase2] Shared category with the OpenAI path so one grep shows
/// every provider's thinking resolution (design §8).
private let thinkingLogger = AppLogger(category: "Thinking")

/// AgentProvider implementation that wraps AnthropicProvider for the unified agent loop.
final class AnthropicAgentProvider: AgentProvider {

    let provider: AnthropicProvider

    var name: String { provider.name }
    var model: LLMModel { provider.model }
    var defaultMaxTokens: Int { 64_000 }

    /// Max transparent retries when OPENING the stream fails with a transient
    /// network error. 2 retries = up to 3 total attempts, with 1s + 2s backoff.
    private static let maxStreamConnectRetries = 2

    /// True for transient connection drops that are safe to retry transparently:
    /// the request never reached the server intent (or the socket died before any
    /// bytes), so re-issuing it can't duplicate work. Scoped to NSURLError codes
    /// only — auth (401), rate-limit (429), and HTTP 4xx/5xx are NOT in
    /// NSURLErrorDomain (the SDK surfaces them as decoding/provider errors), so
    /// they never match here and continue to flow to mapError() → group fallback.
    private static func isTransientNetworkError(_ error: Error) -> Bool {
        let nsError = error as NSError
        guard nsError.domain == NSURLErrorDomain else { return false }
        switch nsError.code {
        case NSURLErrorNetworkConnectionLost,   // -1005 (the observed mid/post-turn drop)
             NSURLErrorNotConnectedToInternet:  // -1009
            return true
        default:
            return false
        }
    }

    /// Images attached to tool results, keyed by tool_use_id.
    /// Populated during `convertMessages()`, consumed by `RequestBodyPatcher`.
    private var pendingToolResultImages: [String: (data: Data, mimeType: String)] = [:]

    init(provider: AnthropicProvider) {
        self.provider = provider
    }

    func streamAgentMessageClamped(
        messages: [AgentMessage],
        systemPrompt: String?,
        tools: [AgentToolDefinition],
        maxTokens: Int,
        thinkingLevel: ThinkingLevel
    ) async throws -> AsyncThrowingStream<AgentStreamEvent, Error> {
        pendingToolResultImages.removeAll()
        #if DEBUG
        AgentRequestTrace.shared.step("anthropic.convertMessages", detail: "input messages=\(messages.count)")
        #endif
        let anthropicMessages = convertMessages(messages)
        let anthropicTools = convertTools(tools)
        let cachedMessages = messagesWithCacheBreakpoints(anthropicMessages)

        // Pass tool result images to the patcher via thread-safe shared storage
        if !pendingToolResultImages.isEmpty {
            RequestBodyPatcher.setToolResultImages(pendingToolResultImages)
            #if DEBUG
            AgentRequestTrace.shared.step("anthropic.setToolResultImages", detail: "ids=\(Array(pendingToolResultImages.keys))")
            #endif
        }

        // Set thinking config for RequestBodyPatcher injection (SDK doesn't support thinking natively).
        //
        // Claude 4.6+ uses *adaptive* thinking (output_config.effort), which silently
        // ignores legacy budget_tokens and produces empty thinking on the wire — the
        // exact "thinking enabled but no thoughts" symptom that surfaced in 4.7.
        // Older Claude models still take budget_tokens. Pick the right one per model.
        // [T-thinking-rules-phase2] WHICH shape this model takes is decided by
        // ThinkingRuleResolver.anthropicThinkingShape — one place now owns every
        // vendor's thinking contract. This site still performs the injection, because
        // Anthropic's thinking is applied by RequestBodyPatcher rather than written
        // into the body dictionary the way the OpenAI path does. Behaviour is
        // byte-for-byte unchanged, pinned by
        // ThinkingWireGeminiAnthropicSnapshotTests (182 rows from the OLD code).
        let thinkShape = ThinkingRuleResolver.anthropicThinkingShape(
            modelId: model.id,
            supportsReasoning: model.supportsReasoning,
            level: thinkingLevel,
            maxTokens: maxTokens
        )
        thinkingLogger.info("[resolve] provider=anthropic model=\(model.id) level=\(thinkingLevel.rawValue) shape=[\(thinkShape.keys.sorted().joined(separator: ","))]")
        if let effort = thinkShape["effort"] as? String {
            RequestBodyPatcher.setThinkingEffort(effort)
            logger.info("Thinking enabled (adaptive): effort=\(effort)")
        } else if let budget = thinkShape["budget_tokens"] as? Int {
            RequestBodyPatcher.setThinkingBudget(budget)
            logger.info("Thinking enabled (budget): budget_tokens=\(budget)")
        } else if thinkShape["disabled"] != nil {
            // Adaptive-generation models (4.6+/5) think by DEFAULT when the
            // request carries no thinking field at all — "off" must be sent
            // explicitly, or small-maxTokens calls burn the whole budget on
            // thinking_tokens and return zero text (observed: voice-correction
            // call with max_tokens=256 → stop_reason=max_tokens,
            // thinking_tokens=256/256, empty body). Legacy (<=4.5) models
            // default to no thinking, so absence is fine there.
            // [T-ios-thinking-flag-cross-request] Stamp the model this intent
            // belongs to — the flag is process-global and another Anthropic
            // request (e.g. title generation, also `.off`, possibly a different
            // model) may reach the wire between this set and its take.
            RequestBodyPatcher.setThinkingDisabled(modelId: model.id)
            logger.info("Thinking explicitly DISABLED (adaptive default-on model, level=off) model=\(self.model.id)")
        }

        // Anthropic-compat proxies (e.g. DeepSeek's deepseek-v4-pro endpoint
        // that speaks the Anthropic protocol) reject history requests where a
        // previous assistant turn produced thinking but the field isn't echoed
        // back: `content[].thinking in the thinking mode must be passed back
        // to the API`. The official Anthropic API enforces this with signed
        // thinking blocks we never carry, so leave it alone there. Stash a
        // chronological array of reasoningContent values per assistant turn
        // for the patcher to splice back in as synthesized thinking blocks.
        //
        // Gate mirrors OpenAIAgentProvider.flattenChatCompletionsMessages's
        // three-signal echo:
        //   echo = (thinkingLevel on  OR  model always reasons)  AND  model may reason
        // `thinkingLevel.isEnabled` alone is not enough — DeepSeek V4 produces
        // thinking blocks even when we don't send `thinking` in the request, so
        // catalog-level `supportsReasoning == true` must also flip the gate on.
        // `injectPlaceholder` covers turns whose reasoningContent is nil/empty
        // (interrupted streams, history loaded from before thinking captured,
        // or cross-provider sessions) — without it the compat-proxy still 400s.
        // Echo of unsigned `thinking` blocks is only safe on proxies that
        // *require* history to carry interleaved reasoning (DeepSeek V4 /
        // GLM-4.5+ / Kimi K2.5+ / Nemotron etc.) — those proxies don't
        // verify the `signature` field, so an unsigned block is accepted.
        // Strict Anthropic-compat proxies (and the real api.anthropic.com)
        // reject unsigned thinking with `messages.X.content.0.thinking.
        // signature: invalid_request_error`. Since we never capture the
        // signature off the wire, we must NOT echo for Claude-class models
        // even when supportsReasoning=true — they speak the standard
        // signed-thinking-block protocol. Gate by the model's catalog-level
        // `interleavedReasoningField`, which is set only on models that
        // need (and tolerate) interleaved unsigned echo.
        //
        // DeepSeek V4 produces thinking blocks even when we don't send
        // `thinking` in the request, so we trigger echo whenever the model
        // requires interleave — independent of the user-facing thinking
        // level toggle.
        let modelMayReason          = model.supportsReasoning ?? true
        let modelRequiresInterleave = model.interleavedReasoningField != nil
        let echoReasoning           = modelMayReason && modelRequiresInterleave
        let injectPlaceholder       = echoReasoning
        if echoReasoning,
           !provider.isOfficialAnthropicEndpoint,
           messages.contains(where: { $0.role == .assistant }) {
            // Walk messages preserving role-run boundaries so the produced
            // history array aligns with `mergeConsecutiveSameRole` further
            // downstream — reasoning from N adjacent assistant messages is
            // merged into the single on-wire assistant turn they collapse
            // into. Without this, the wire-side patcher's assistantIdx would
            // walk past entries that no longer exist post-merge.
            var history: [String?] = []
            var lastRole: AgentMessage.Role?
            for msg in messages {
                if msg.role == .assistant {
                    if lastRole == .assistant {
                        let prev = history.last ?? nil
                        let merged: String?
                        switch (prev, msg.reasoningContent) {
                        case (nil, nil): merged = nil
                        case (let p?, nil): merged = p
                        case (nil, let r?): merged = r
                        case (let p?, let r?): merged = p + "\n" + r
                        }
                        if !history.isEmpty { history[history.count - 1] = merged }
                    } else {
                        history.append(msg.reasoningContent)
                    }
                }
                lastRole = msg.role
            }
            RequestBodyPatcher.setReasoningHistory(history, injectPlaceholder: injectPlaceholder)
            logger.info("Compat-proxy thinking echo: queued \(history.compactMap { $0 }.count) reasoning block(s) across \(history.count) assistant turn(s), placeholder=\(injectPlaceholder)")
        }

        let parameter = MessageParameter(
            model: .other(model.id),
            messages: cachedMessages,
            maxTokens: maxTokens,
            system: provider.resolveSystemPrompt(systemPrompt),
            temperature: provider.effectiveTemperature(0.7),
            tools: anthropicTools,
            toolChoice: .init(type: .auto)
        )

        #if DEBUG
        // Note: request body is captured by the URLProtocol layer (OAuthURLProtocol / EagerStreamingURLProtocol).
        // Do NOT call JSONEncoder().encode(parameter) here — MessageParameter can contain
        // non-Encodable or deeply nested content that triggers a runtime trap (EXC_BREAKPOINT).
        AgentRequestTrace.shared.step("anthropic.streamMessage.prepare")
        provider.logRequestParameter(parameter)
        #endif

        #if DEBUG
        AgentRequestTrace.shared.step("anthropic.streamMessage", detail: "calling provider.service.streamMessage")
        #endif
        // Bounded transparent retry for transient network drops while OPENING the
        // stream. A `-1005` (NetworkConnectionLost) / `-1009` (NotConnectedToInternet)
        // here means the connection died before any event was yielded to the
        // consumer, so retrying is invisible — nothing has been emitted to replay.
        // Retries are intentionally confined to this establishment phase: a drop
        // mid-stream (after deltas have been yielded, see the consumer Task below)
        // CANNOT be transparently retried without duplicating content, so that
        // path stays terminal. Only transient NSURLError codes are retried; auth
        // (401), rate-limit (429), 4xx/5xx and any non-network error fall straight
        // through to mapError() → group fallback, unchanged. [diag-0800 Q2 / -1005]
        let stream: AsyncThrowingStream<MessageStreamResponse, Error>
        var establishAttempt = 0
        while true {
            do {
                stream = try await provider.service.streamMessage(parameter)
                if establishAttempt > 0 {
                    logger.info("streamMessage recovered after \(establishAttempt) network retry(ies)")
                }
                break
            } catch {
                // Transparent retry only for transient network drops, capped.
                if Self.isTransientNetworkError(error), establishAttempt < Self.maxStreamConnectRetries {
                    try Task.checkCancellation()
                    establishAttempt += 1
                    // 1s, 2s backoff (attempt 1 → 1s, attempt 2 → 2s).
                    let backoffNs = UInt64(establishAttempt) * 1_000_000_000
                    logger.warning("streamMessage transient network error (\((error as NSError).code)) — retry \(establishAttempt)/\(Self.maxStreamConnectRetries) after \(establishAttempt)s")
                    try? await Task.sleep(nanoseconds: backoffNs)
                    continue
                }
                // Capture failed request for debug server / Copy Requests
                let errorDesc = (error as NSError).localizedDescription
                logger.error("streamMessage failed: \(errorDesc)")
                #if DEBUG
                let debugBody = "// ERROR: \(errorDesc.prefix(500))\n// model: \(model.id)\n// maxTokens: \(maxTokens)\n// messages: \(cachedMessages.count)\n// tools: \(anthropicTools.count)"
                LastAPIRequestBody.shared.set(debugBody, provider: "Anthropic")
                LastAPIRequestBody.shared.setRequestMeta(
                    url: "anthropic:///v1/messages",
                    method: "POST",
                    headers: nil
                )
                #endif
                #if DEBUG
                AgentRequestTrace.shared.step("anthropic.error", detail: "\(error)")
                AgentRequestTrace.shared.finish(error: "\(error)")
                #endif
                throw provider.mapError(error)
            }
        }

        return AsyncThrowingStream { continuation in
            let task = Task {
                // Track current tool for accumulating partial JSON.
                // We accumulate chunks in an array and lazy-join only when we
                // actually yield a delta — `String += String` reallocates and
                // copies the entire prefix on every chunk (Anthropic
                // eager_input_streaming = ~150–200 chunks/sec for large tool
                // payloads), turning a 50KB Edit's old_string into ~25MB of
                // memcpy at the provider layer alone.
                var currentToolId: String?
                var currentToolName: String?
                var currentToolJsonChunks: [String] = []
                var currentToolJsonByteCount: Int = 0
                var lastToolJoined: String = ""
                var lastToolJoinedAtCount: Int = 0
                var lastToolYieldAt: Date = .distantPast
                // Yield throttle: 80ms gives the consumer at most ~12 events/sec
                // even when SSE chunks land at 200/sec. The consumer
                // (AIChatViewModel.toolInputDelta) further throttles to
                // 200ms / 1s, so dropped intermediate yields are by design.
                let toolYieldInterval: TimeInterval = 0.08
                func currentToolJsonJoined() -> String {
                    if lastToolJoinedAtCount == currentToolJsonChunks.count {
                        return lastToolJoined
                    }
                    let joined = currentToolJsonChunks.joined()
                    lastToolJoined = joined
                    lastToolJoinedAtCount = currentToolJsonChunks.count
                    return joined
                }
                var isThinkingBlock = false
                var thinkingContent = ""

                do {
                    for try await response in stream {
                        guard let event = response.streamEvent else { continue }

                        switch event {
                        case .contentBlockStart:
                            if let block = response.contentBlock {
                                if block.type == "tool_use" {
                                    let id = block.id ?? UUID().uuidString
                                    let name = block.name ?? "unknown"
                                    // [T-ios-concurrent-toolcall-dup-id] Log
                                    // the SSE-level raw id + content-block
                                    // index so we can verify the upstream is
                                    // emitting unique ids for parallel calls.
                                    logger.info("[AnthropicStream] contentBlockStart tool_use raw_id=\(block.id ?? "<NIL→\(id)>") name=\(name) index=\(response.index ?? -1)")
                                    currentToolId = id
                                    currentToolName = name
                                    currentToolJsonChunks.removeAll(keepingCapacity: true)
                                    currentToolJsonByteCount = 0
                                    lastToolJoined = ""
                                    lastToolJoinedAtCount = 0
                                    lastToolYieldAt = .distantPast
                                    isThinkingBlock = false
                                    continuation.yield(.contentBlockStart(.toolUse(id: id, name: name)))
                                } else if block.type == "thinking" {
                                    isThinkingBlock = true
                                } else if block.type == "text" {
                                    isThinkingBlock = false
                                    continuation.yield(.contentBlockStart(.text))
                                }
                            }

                        case .contentBlockDelta:
                            if let delta = response.delta {
                                if isThinkingBlock {
                                    // Thinking block: SDK exposes thinking content via delta.thinking
                                    let thinkText = delta.thinking ?? delta.text ?? ""
                                    if !thinkText.isEmpty {
                                        thinkingContent += thinkText
                                        if thinkingLevel.isEnabled {
                                            continuation.yield(.thinkingDelta(thinkText))
                                        }
                                    }
                                } else if let text = delta.text {
                                    continuation.yield(.textDelta(text))
                                } else if let partialJson = delta.partialJson {
                                    currentToolJsonChunks.append(partialJson)
                                    currentToolJsonByteCount += partialJson.utf8.count
                                    if let name = currentToolName {
                                        let now = Date()
                                        if now.timeIntervalSince(lastToolYieldAt) >= toolYieldInterval {
                                            lastToolYieldAt = now
                                            continuation.yield(.toolInputDelta(name: name, accumulated: currentToolJsonJoined()))
                                        }
                                    }
                                }
                            }

                        case .contentBlockStop:
                            if let toolId = currentToolId, let toolName = currentToolName {
                                let finalJson = currentToolJsonJoined()
                                let args = Self.parseJsonToDict(finalJson)
                                if args.isEmpty {
                                    // [ToolArgsProbe] Diagnose Kimi/etc. empty-args bug
                                    // — distinguish never-received-delta vs literal "{}"
                                    // vs truncated JSON. See OpenAIAgentProvider.diagEmptyToolArgs
                                    // for the same probe used on the OpenAI path.
                                    // Raw payload logged UNTRUNCATED — empty-args is rare.
                                    let utf8Bytes = finalJson.utf8.count
                                    let trimmed = finalJson.trimmingCharacters(in: .whitespacesAndNewlines)
                                    let parseOk = (try? JSONSerialization.jsonObject(with: Data(finalJson.utf8), options: [])) != nil
                                    let hexPreview = finalJson.utf8.prefix(80).map { String(format: "%02x", $0) }.joined()
                                    AppLogger(category: "ToolArgsProbe").warning("[ToolArgsProbe] EMPTY ARGS source=anthropic.contentBlockStop model=\(self.model.id) tool=\(toolName) id=\(toolId) bytes=\(utf8Bytes) chunks=\(currentToolJsonChunks.count) trimmedEmpty=\(trimmed.isEmpty ? 1 : 0) literalEmptyObj=\(trimmed == "{}" ? 1 : 0) parseOk=\(parseOk ? 1 : 0) hex80=\(hexPreview) raw=<<<\(finalJson)>>>")
                                }
                                continuation.yield(.toolCallComplete(
                                    id: toolId, name: toolName, args: args, metadata: nil
                                ))
                                currentToolId = nil
                                currentToolName = nil
                                currentToolJsonChunks.removeAll(keepingCapacity: true)
                                currentToolJsonByteCount = 0
                                lastToolJoined = ""
                                lastToolJoinedAtCount = 0
                            }

                        case .messageDelta:
                            if let usage = response.usage {
                                continuation.yield(.usage(usage.toLLMUsage()))
                            }
                            if let stopReason = response.delta?.stopReason {
                                if !thinkingContent.isEmpty {
                                    continuation.yield(.reasoningContent(thinkingContent))
                                }
                                let reason: AgentStopReason = switch stopReason {
                                case "tool_use": .toolUse
                                case "max_tokens": .maxTokens
                                // Fable 5 / Opus 4.7+ safety classifier declines land here as a
                                // 200 with stop_reason="refusal" and empty content. Map to a
                                // dedicated case so the agent loop reports it as a refusal (and
                                // does not blindly retry) instead of a generic empty endTurn.
                                case "refusal": .refusal
                                default: .endTurn
                                }
                                continuation.yield(.done(stopReason: reason))
                            }

                        case .messageStart:
                            if let usage = response.message?.usage {
                                continuation.yield(.usage(usage.toLLMUsage()))
                            }

                        case .messageStop:
                            break
                        }
                    }
                    continuation.finish()
                } catch {
                    // Terminal: a drop HERE is mid-stream. Even a transient
                    // network error (-1005/-1009) is NOT auto-retried at this
                    // point — deltas may already have been yielded to the
                    // consumer, and re-issuing the request would duplicate the
                    // partial assistant content. Transparent retry is confined to
                    // the establishment phase above (pre-yield). This surfaces to
                    // the agent loop, which attaches the error to the assistant
                    // bubble and lets the group's fallback / Resume handle it.
                    let errorDesc = (error as NSError).localizedDescription
                    logger.error("Stream error: \(errorDesc)")
                    // Try appending to the most recent captured request (from URLProtocol),
                    // and also create a standalone error entry as fallback.
                    #if DEBUG
                    let errorNote = "\n\n// STREAM ERROR: \(errorDesc.prefix(1000))"
                    LastAPIRequestBody.shared.appendResponseBody(errorNote)
                    // Standalone entry so Copy Requests always shows something
                    let debugBody = "// STREAM ERROR (model: \(self.model.id))\n// \(errorDesc.prefix(1500))"
                    LastAPIRequestBody.shared.set(debugBody, provider: "Anthropic")
                    #endif
                    continuation.finish(throwing: self.provider.mapError(error))
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - Thinking Config

    /// Compute thinking budget for Anthropic models based on level.
    /// Returns 0 if the model doesn't support thinking.
    static func thinkingBudget(for model: LLMModel, maxTokens: Int, level: ThinkingLevel = .medium) -> Int {
        guard model.supportsReasoning ?? false else { return 0 }
        let cap: Int = switch level {
        case .off: 0
        case .low: 8192
        case .medium: 32768
        case .high: min(maxTokens, 65536)
        case .xhigh, .max, .ultra: maxTokens
        }
        // Anthropic requires budget_tokens < max_tokens (strict inequality).
        let clamped = min(cap, maxTokens)
        return clamped >= maxTokens && maxTokens > 1 ? maxTokens - 1 : clamped
    }

    /// Map our ThinkingLevel onto Anthropic adaptive thinking effort strings.
    /// Used for Claude 4.6+ which take `output_config.effort` instead of
    /// `thinking.budget_tokens`.
    ///
    /// xhigh maps to "max" to match Anthropic's nomenclature (low/medium/high/max);
    /// CLIProxyAPI's models.json shows opus 4.7 advertises levels
    /// `[low, medium, high, xhigh, max]` so both `xhigh` and `max` are accepted,
    /// but `max` is the more universally recognized token across 4.6 and 4.7.
    static func thinkingEffort(for level: ThinkingLevel) -> String {
        switch level {
        case .off: return "low"  // never reached: caller checks isEnabled first
        case .low: return "low"
        case .medium: return "medium"
        case .high: return "high"
        case .xhigh, .max, .ultra: return "max"
        }
    }

    // MARK: - Message Conversion

    private func convertMessages(_ messages: [AgentMessage]) -> [MessageParameter.Message] {
        // Two complementary self-healing passes, both on a value-typed copy so
        // the caller's stored history is untouched:
        //   1. injectMissingToolResults — every tool_use must be followed by a
        //      tool_result (Anthropic 400 "tool_use ids were found without
        //      tool_result blocks immediately after"). A tool_use whose result
        //      never arrived (stream interrupted mid-tool, VM/session torn down,
        //      fallback re-send of a half-finished turn) gets a synthetic error
        //      tool_result spliced in right after its assistant message.
        //   2. stripOrphanToolResults — the inverse: drop tool_results with no
        //      matching tool_use.
        // (1) runs first so any result it injects is then validated by (2).
        let healed = Self.injectMissingToolResults(messages)
        let cleaned = Self.stripOrphanToolResults(healed)
        let mapped: [MessageParameter.Message] = cleaned.map { msg in
            let role: MessageParameter.Message.Role = msg.role == .user ? .user : .assistant
            let objects: [MessageParameter.Message.Content.ContentObject] = msg.parts.compactMap { part in
                switch part {
                case .text(let text):
                    return .text(text)
                case .toolUse(let id, let name, let input):
                    return .toolUse(sanitizeToolId(id), name, convertToInput(input))
                case .toolResult(let id, _, let content, let isError, let imageData, let imageMimeType, _, _):
                    let safeId = sanitizeToolId(id)
                    if let data = imageData {
                        // Downscale to Anthropic's recommended 1568px to avoid
                        // "many-image" dimension errors in long conversations.
                        // downscaleForAnthropic always returns JPEG data, so force
                        // the mime type to match — prevents media_type/data mismatch
                        // when restored sessions have PNG data sniffed as image/png.
                        let downscaled = Self.downscaleForAnthropic(data)
                        let finalData = downscaled ?? data
                        let finalMime = downscaled != nil ? "image/jpeg" : (imageMimeType ?? "image/jpeg")
                        self.pendingToolResultImages[safeId] = (data: finalData, mimeType: finalMime)
                    }
                    return .toolResult(safeId, content, isError: isError)
                case .imageData(let data, let mimeType, _):
                    let finalData = Self.downscaleForAnthropic(data) ?? data
                    let base64 = finalData.base64EncodedString()
                    let mediaType: MessageParameter.Message.Content.ImageSource.MediaType
                    switch mimeType {
                    case "image/png": mediaType = .png
                    case "image/gif": mediaType = .gif
                    case "image/webp": mediaType = .webp
                    default: mediaType = .jpeg
                    }
                    return .image(.init(type: .base64, mediaType: mediaType, data: base64))
                }
            }
            return MessageParameter.Message(role: role, content: .list(objects))
        }

        // Merge consecutive messages with the same role. Retry, cancel, and
        // provider-switch flows can produce adjacent assistant (or user) messages.
        // Anthropic API requires tool_result immediately after tool_use — if an
        // intervening same-role message splits them, the request returns 400.
        return Self.mergeConsecutiveSameRole(mapped)
    }

    /// Ensure every `tool_use` is answered by a `tool_result` in the message
    /// directly after its assistant turn. Anthropic rejects a request with 400
    /// (`tool_use ids were found without tool_result blocks immediately after`)
    /// when any tool_use is left dangling.
    ///
    /// A tool_use can end up unanswered when the stream was interrupted while
    /// the tool was still executing (network drop, VM/session torn down mid
    /// tool, app backgrounded then the request retried, or a model-group
    /// fallback re-sending a turn whose tool_result was never committed to
    /// agentHistory). The startup-time orphan sweep in `runAgentLoop` only
    /// catches dangling tool_uses present *before* the loop began — one
    /// produced mid-run (then re-sent on fallback) slips past it. Healing here,
    /// at the provider boundary, covers every outbound payload regardless of
    /// how the dangle arose.
    ///
    /// For each assistant message, any tool_use id not satisfied by a
    /// tool_result in the immediately-following user message gets a synthetic
    /// error tool_result spliced into a user message right after the assistant
    /// turn. Operates on a value-typed copy; stored history is untouched.
    private static func injectMissingToolResults(_ messages: [AgentMessage]) -> [AgentMessage] {
        var result: [AgentMessage] = []
        result.reserveCapacity(messages.count + 2)
        var i = 0
        while i < messages.count {
            let msg = messages[i]
            result.append(msg)
            guard msg.role == .assistant else { i += 1; continue }

            let toolUses = msg.parts.compactMap { part -> (id: String, name: String)? in
                if case .toolUse(let id, let name, _) = part { return (id, name) }
                return nil
            }
            guard !toolUses.isEmpty else { i += 1; continue }

            // Collect tool_result ids from the immediately-following user
            // message (if any). Anthropic only accepts the result in the very
            // next message, so we look exactly one ahead.
            var satisfied: Set<String> = []
            if i + 1 < messages.count, messages[i + 1].role == .user {
                for part in messages[i + 1].parts {
                    if case .toolResult(let id, _, _, _, _, _, _, _) = part {
                        satisfied.insert(id)
                    }
                }
            }

            let missing = toolUses.filter { !satisfied.contains($0.id) }
            guard !missing.isEmpty else { i += 1; continue }

            let placeholders = missing.map { tu in
                AgentContentPart.toolResult(
                    id: tu.id, name: tu.name,
                    content: "Tool execution was interrupted before a result was returned (the session or stream ended mid-tool). Treat this tool call as unfinished and retry or proceed without its result.",
                    isError: true
                )
            }
            if i + 1 < messages.count, messages[i + 1].role == .user {
                // Prepend synthetic results to the existing next user message so
                // the matched-tool_result-first ordering still holds after merge.
                var next = messages[i + 1]
                next.parts = placeholders + next.parts
                result.append(next)
                logger.warning("Injected \(placeholders.count) synthetic tool_result(s) into existing user message for unanswered tool_use(s) at idx=\(i)")
                i += 2
            } else {
                // No following user message — insert a fresh one.
                result.append(AgentMessage(role: .user, parts: placeholders))
                logger.warning("Inserted user message with \(placeholders.count) synthetic tool_result(s) for unanswered tool_use(s) at idx=\(i)")
                i += 1
            }
        }
        return result
    }

    /// Drop `tool_result` parts whose `tool_use_id` does not match a `tool_use`
    /// in the most recent assistant message. Anthropic rejects orphan
    /// tool_results with 400 (`unexpected tool_use_id ... no corresponding
    /// tool_use block`). Operates on a value-typed copy so the caller's stored
    /// history is untouched — only the outbound payload is sanitized.
    private static func stripOrphanToolResults(_ messages: [AgentMessage]) -> [AgentMessage] {
        var result = messages
        var liveToolUseIds: Set<String> = []
        for i in 0..<result.count {
            switch result[i].role {
            case .assistant:
                liveToolUseIds = Set(result[i].parts.compactMap { part -> String? in
                    if case .toolUse(let id, _, _) = part { return id }
                    return nil
                })
            case .user:
                let original = result[i].parts
                let kept = original.filter { part in
                    if case .toolResult(let id, _, _, _, _, _, _, _) = part {
                        return liveToolUseIds.contains(id)
                    }
                    return true
                }
                if kept.count != original.count {
                    let dropped = original.count - kept.count
                    logger.info("Stripped \(dropped) orphan tool_result block(s) from outbound payload (idx=\(i))")
                    result[i].parts = kept
                }
                // Consume the matched tool_use ids so a later duplicate
                // tool_result for the same id is treated as an orphan. Do NOT
                // clear the entire set — a single assistant turn's tool_uses
                // can have their tool_results delivered across multiple
                // consecutive user messages (e.g. the user types a text
                // message while a tool is still executing, then the tool's
                // cancel/error tool_result arrives in a separate user turn).
                // Clearing wholesale here would drop those late-arriving
                // tool_results, producing exactly the 400 we are guarding
                // against. The set is naturally reset on the next assistant
                // turn (top of this loop).
                for part in kept {
                    if case .toolResult(let id, _, _, _, _, _, _, _) = part {
                        liveToolUseIds.remove(id)
                    }
                }
            }
        }
        // Drop user messages that became empty (only had orphan tool_results).
        // Sending an empty content array is itself a 400.
        return result.filter { !$0.parts.isEmpty }
    }

    /// Merge adjacent messages that share the same role into a single message
    /// by concatenating their content blocks.  Retry, cancel, and provider-switch
    /// flows can produce consecutive assistant (or user) messages.  Anthropic API
    /// requires tool_result immediately after tool_use — merging prevents 400 errors.
    ///
    /// For assistant messages, content blocks are reordered so text/image/cache
    /// appear before tool_use.  Anthropic rejects requests where text follows
    /// tool_use within the same assistant message.
    private static func mergeConsecutiveSameRole(_ messages: [MessageParameter.Message]) -> [MessageParameter.Message] {
        guard !messages.isEmpty else { return messages }
        var roles: [String] = []
        var contents: [MessageParameter.Message.Content] = []
        for msg in messages {
            if let lastRole = roles.last, lastRole == msg.role {
                contents[contents.count - 1] = concatContent(contents[contents.count - 1], msg.content)
            } else {
                roles.append(msg.role)
                contents.append(msg.content)
            }
        }
        return zip(roles, contents).map { role, content in
            let r: MessageParameter.Message.Role = role == "user" ? .user : .assistant
            let ordered: MessageParameter.Message.Content
            if role == "assistant" {
                ordered = reorderAssistantContent(content)
            } else {
                ordered = reorderUserContent(content)
            }
            return MessageParameter.Message(role: r, content: ordered)
        }
    }

    /// Reorder user content blocks: tool_result blocks first, everything else
    /// after. Anthropic requires the user message following an assistant
    /// `tool_use` to lead with the matching `tool_result`; if the user typed a
    /// plain text message while the tool was still running, the merged user
    /// turn would otherwise put text before tool_result and trip a 400.
    private static func reorderUserContent(_ content: MessageParameter.Message.Content) -> MessageParameter.Message.Content {
        guard case .list(let objects) = content, objects.count > 1 else { return content }
        var hasToolResult = false
        var firstNonToolResultIdx: Int? = nil
        for (idx, obj) in objects.enumerated() {
            if case .toolResult = obj {
                hasToolResult = true
                if let nonIdx = firstNonToolResultIdx, idx > nonIdx {
                    // tool_result follows a non-tool_result block → reorder.
                    var toolResults: [MessageParameter.Message.Content.ContentObject] = []
                    var others: [MessageParameter.Message.Content.ContentObject] = []
                    for o in objects {
                        if case .toolResult = o { toolResults.append(o) }
                        else { others.append(o) }
                    }
                    return .list(toolResults + others)
                }
            } else if firstNonToolResultIdx == nil {
                firstNonToolResultIdx = idx
            }
        }
        _ = hasToolResult
        return content
    }

    /// Reorder assistant content blocks: text/image/cache first, tool_use last.
    /// Anthropic API rejects assistant messages where text appears after tool_use.
    private static func reorderAssistantContent(_ content: MessageParameter.Message.Content) -> MessageParameter.Message.Content {
        guard case .list(let objects) = content, objects.count > 1 else { return content }

        // Check if reordering is needed (any text/image/cache after a tool_use?)
        var sawToolUse = false
        var needsReorder = false
        for obj in objects {
            if case .toolUse = obj { sawToolUse = true }
            else if sawToolUse {
                needsReorder = true
                break
            }
        }
        guard needsReorder else { return content }

        // Partition: non-tool_use blocks first, tool_use blocks last
        var nonToolUse: [MessageParameter.Message.Content.ContentObject] = []
        var toolUse: [MessageParameter.Message.Content.ContentObject] = []
        for obj in objects {
            if case .toolUse = obj { toolUse.append(obj) }
            else { nonToolUse.append(obj) }
        }
        return .list(nonToolUse + toolUse)
    }

    /// Concatenate two Message.Content values into a single .list.
    private static func concatContent(
        _ a: MessageParameter.Message.Content,
        _ b: MessageParameter.Message.Content
    ) -> MessageParameter.Message.Content {
        var objects: [MessageParameter.Message.Content.ContentObject] = []
        switch a {
        case .text(let t): objects.append(.text(t))
        case .list(let list): objects.append(contentsOf: list)
        @unknown default: break
        }
        switch b {
        case .text(let t): objects.append(.text(t))
        case .list(let list): objects.append(contentsOf: list)
        @unknown default: break
        }
        return .list(objects)
    }

    /// Convert `[String: Any]` to the SDK's `MessageResponse.Content.Input` type.
    private func convertToInput(_ dict: [String: Any]) -> MessageResponse.Content.Input {
        var result: MessageResponse.Content.Input = [:]
        for (key, value) in dict {
            if let s = value as? String {
                result[key] = .string(s)
            } else if let n = value as? NSNumber {
                if CFBooleanGetTypeID() == CFGetTypeID(n) {
                    result[key] = .bool(n.boolValue)
                } else {
                    result[key] = .integer(n.intValue)
                }
            }
        }
        return result
    }

    // MARK: - Image Helpers

    /// Anthropic supports up to 8000×8000 / 5MB; we standardize at 2000 long edge
    /// across attachments / browser / read_image. Returns nil if already within bounds.
    private static func downscaleForAnthropic(_ data: Data, maxLongEdge: CGFloat = 2000) -> Data? {
        guard let image = UIImage(data: data), let cgImage = image.cgImage else { return nil }
        let pixelW = CGFloat(cgImage.width)
        let pixelH = CGFloat(cgImage.height)
        let longest = max(pixelW, pixelH)
        guard longest > maxLongEdge else { return nil }
        let scale = maxLongEdge / longest
        let newSize = CGSize(width: pixelW * scale, height: pixelH * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        let renderer = UIGraphicsImageRenderer(size: newSize, format: format)
        let resized = renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: newSize)) }
        return resized.jpegData(compressionQuality: 0.85)
    }

    // MARK: - Tool Conversion

    private func convertTools(_ tools: [AgentToolDefinition]) -> [MessageParameter.Tool] {
        tools.map { tool in
            var properties: [String: JSONSchema.Property] = [:]
            for (name, param) in tool.parameters {
                let type: JSONSchema.JSONType = switch param.type {
                case .string: .string
                case .integer: .integer
                case .boolean: .boolean
                }
                properties[name] = JSONSchema.Property(
                    type: type,
                    description: param.description,
                    enumValues: param.enumValues
                )
            }
            return MessageParameter.Tool.function(
                name: tool.name,
                description: tool.description,
                inputSchema: JSONSchema(
                    type: .object,
                    properties: properties,
                    required: tool.required
                )
            )
        }
    }

    // MARK: - Prompt Caching

    /// Cache breakpoint strategy (4 breakpoints max per Anthropic API):
    ///   1. tools[last]        — via RequestBodyPatcher.injectToolsCacheControl
    ///   2. system[last]       — via AnthropicProvider.resolveSystemPrompt
    ///   3. messages[N-2 user] — penultimate user message (within lookback window)
    ///   4. messages[N-1 user] — last user message (current turn)
    ///
    /// During agent loop iterations, each turn appends assistant tool_use + user
    /// tool_result. Breakpoint 3 on the penultimate user message creates a prefix
    /// that the next iteration can cache-hit (it becomes the new N-3, still within
    /// the 20-block lookback window). Breakpoint 4 caches the tail.
    private func messagesWithCacheBreakpoints(_ messages: [MessageParameter.Message]) -> [MessageParameter.Message] {
        guard messages.count >= 3 else { return messages }

        var result = messages
        let cacheControl = MessageParameter.CacheControl(type: .ephemeral)

        // Scan backwards from the end to find the two most recent messages where
        // cache_control can be injected (user messages with .toolResult or .text content).
        // This is more robust than blindly picking the last two user messages, since
        // some user messages may only contain content types that don't support cache_control.
        var injectedCount = 0
        for i in stride(from: result.count - 1, through: 0, by: -1) {
            guard result[i].role == MessageParameter.Message.Role.user.rawValue else { continue }
            let (injected, success) = tryInjectCacheControl(on: result[i], cacheControl: cacheControl)
            if success {
                result[i] = injected
                injectedCount += 1
                #if DEBUG
                logger.debug("cache breakpoint [\(i)/\(result.count)] injected (#\(injectedCount))")
                #endif
                if injectedCount >= 2 { break }
            } else {
                #if DEBUG
                logger.debug("cache breakpoint [\(i)/\(result.count)] SKIP contentTypes=\(Self.contentTypesSummary(result[i]))")
                #endif
            }
        }

        return result
    }

    #if DEBUG
    private static func contentTypesSummary(_ message: MessageParameter.Message) -> String {
        switch message.content {
        case .list(let objects):
            let types = objects.map { obj -> String in
                switch obj {
                case .text: return "text"
                case .toolResult: return "toolResult"
                case .toolUse: return "toolUse"
                case .image: return "image"
                case .cache: return "cache"
                @unknown default: return "unknown"
                }
            }
            return ".list([\(types.joined(separator: ", "))])"
        case .text: return ".text"
        }
    }
    #endif

    /// Try to inject cache_control on a cacheable content object of a user message.
    /// Returns (message, true) if injection succeeded, (original, false) if no cacheable object found.
    /// Scans backwards from the last object to find one that supports cache_control
    /// (.toolResult or .text), since other types like .toolUse cannot carry it.
    private func tryInjectCacheControl(on message: MessageParameter.Message,
                                       cacheControl: MessageParameter.CacheControl) -> (MessageParameter.Message, Bool) {
        switch message.content {
        case .list(let objects) where !objects.isEmpty:
            var newObjects = objects
            // Scan backwards to find a cacheable content object
            for i in stride(from: newObjects.count - 1, through: 0, by: -1) {
                switch newObjects[i] {
                case .toolResult(let id, let content, let isError, _):
                    newObjects[i] = .toolResult(id, content, isError: isError,
                        cacheControl: cacheControl)
                    return (.init(role: .user, content: .list(newObjects)), true)
                case .text(let text):
                    newObjects[i] = .cache(MessageParameter.Cache(
                        text: text, cacheControl: cacheControl))
                    return (.init(role: .user, content: .list(newObjects)), true)
                default:
                    continue
                }
            }
            return (message, false)

        case .text(let text):
            return (.init(role: .user, content: .list([
                .cache(MessageParameter.Cache(
                    text: text, cacheControl: cacheControl))
            ])), true)

        default:
            return (message, false)
        }
    }

    // MARK: - Warmup (Cache Keep-Alive)

    /// Send a minimal `max_tokens:1` request that replicates the same prefix as
    /// real requests so the Anthropic prompt cache TTL is refreshed.
    func sendWarmupRequest(
        messages: [AgentMessage],
        systemPrompt: String?,
        tools: [AgentToolDefinition]
    ) async throws -> LLMUsage {
        pendingToolResultImages.removeAll()
        let anthropicMessages = convertMessages(messages)
        let anthropicTools = convertTools(tools)
        let cachedMessages = messagesWithCacheBreakpoints(anthropicMessages)

        if !pendingToolResultImages.isEmpty {
            RequestBodyPatcher.setToolResultImages(pendingToolResultImages)
        }

        // Append a lightweight user message to form a valid request
        let warmupUserMsg = MessageParameter.Message(
            role: .user,
            content: .list([.text("keepalive")])
        )
        let allMessages = cachedMessages + [warmupUserMsg]

        logger.info("🔥 warmup request: model=\(self.model.id) messages=\(allMessages.count) (original=\(messages.count) + 1 keepalive) tools=\(anthropicTools.count) systemPromptLen=\(systemPrompt?.count ?? 0) maxTokens=1 temp=0")

        let parameter = MessageParameter(
            model: .other(model.id),
            messages: allMessages,
            maxTokens: 1,
            system: provider.resolveSystemPrompt(systemPrompt),
            temperature: provider.effectiveTemperature(0),
            tools: anthropicTools
        )

        let response = try await provider.service.createMessage(parameter)
        let usage = response.usage.toLLMUsage()

        let responseText = response.content.compactMap { block -> String? in
            if case .text(let str, _) = block { return str }
            return nil
        }.joined()
        logger.info("🔥 warmup response: stopReason=\(response.stopReason ?? "nil") responseLen=\(responseText.count) input=\(usage.inputTokens) output=\(usage.outputTokens) cache_read=\(usage.cacheReadInputTokens ?? 0) cache_create=\(usage.cacheCreationInputTokens ?? 0)")

        return usage
    }

    // MARK: - Helpers

    private static func parseJsonToDict(_ json: String) -> [String: Any] {
        guard let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        return dict
    }
}
