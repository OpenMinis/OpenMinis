import Foundation
import CryptoKit
import os.log

private let logger = AppLogger(category: "OpenAIAgent")

/// [T-thinking-rules-phase1] Separate category so the resolution trace can be grepped
/// on its own — "which rule produced this request" is the first question in any
/// thinking-parameter report (design §8).
private let thinkingLogger = AppLogger(category: "Thinking")

/// AgentProvider implementation that wraps OpenAIProvider for the unified agent loop.
/// Handles both Chat Completions (API key) and Responses API (OAuth/Codex) formats.
final class OpenAIAgentProvider: AgentProvider {

    /// Invisible (zero-width) marker prepended to the localized
    /// "Reasoning: N tokens (encrypted)" placeholder produced by the Responses-API
    /// path. Lets the Chat-Completions converter recognize this as a UI-only
    /// summary (not real model reasoning) regardless of the user's locale, and
    /// substitute an empty string when forwarding history to non-Codex providers
    /// like DeepSeek / Kimi — fabricated reasoning trains those models to imitate
    /// the placeholder (the same failure mode T249 hit with `[no prior reasoning]`).
    /// The marker is a U+FEFF (zero-width no-break space): invisible in all
    /// text renderers so the user-facing thinking block looks unchanged.
    static let encryptedReasoningMarker: String = "\u{FEFF}"

    /// Provider-family tag for `ReasoningEcho.providerKind`. Encrypted
    /// reasoning items are only safe to echo back inside the same family
    /// AND same model id.
    static let responsesAPIProviderKind: String = "openai-responses"

    let provider: OpenAIProvider

    var name: String { provider.name }
    var model: LLMModel { provider.model }
    var defaultMaxTokens: Int { provider.usesChatCompletionsAPI ? 16_384 : 32_768 }

    init(provider: OpenAIProvider) {
        self.provider = provider
    }

    func streamAgentMessageClamped(
        messages: [AgentMessage],
        systemPrompt: String?,
        tools: [AgentToolDefinition],
        maxTokens: Int,
        thinkingLevel: ThinkingLevel
    ) async throws -> AsyncThrowingStream<AgentStreamEvent, Error> {
        if provider.usesChatCompletionsAPI {
            return try await streamChatCompletions(messages: messages, systemPrompt: systemPrompt, tools: tools, maxTokens: maxTokens, thinkingLevel: thinkingLevel)
        } else {
            return try await streamResponsesAPI(messages: messages, systemPrompt: systemPrompt, tools: tools, maxTokens: maxTokens, thinkingLevel: thinkingLevel)
        }
    }

    // MARK: - Chat Completions (API Key mode)

    private func streamChatCompletions(
        messages: [AgentMessage],
        systemPrompt: String?,
        tools: [AgentToolDefinition],
        maxTokens: Int,
        thinkingLevel: ThinkingLevel = .off
    ) async throws -> AsyncThrowingStream<AgentStreamEvent, Error> {
        let openAIMessages = flattenChatCompletionsMessages(messages, thinkingLevel: thinkingLevel)
        let openAITools = convertToolsChatCompletions(tools)

        var allMessages = openAIMessages
        var toolsPayload = openAITools

        // DashScope Qwen cache control: inject cache_control breakpoints on
        // system prompt (content-block array), last tool, and last 2 user messages.
        let useDashScopeCache = provider.isDashScope && model.id.lowercased().contains("qwen")
        if useDashScopeCache {
            Self.injectDashScopeCacheControl(messages: &allMessages, tools: &toolsPayload)
        }

        if let sys = systemPrompt, !sys.isEmpty {
            if useDashScopeCache {
                // Content-block array format so we can attach cache_control
                allMessages.insert([
                    "role": "system",
                    "content": [
                        ["type": "text", "text": sys, "cache_control": ["type": "ephemeral"]],
                    ],
                ], at: 0)
            } else {
                allMessages.insert(["role": "system", "content": sys], at: 0)
            }
        }

        var body: [String: Any] = [
            "model": model.id,
            "messages": allMessages,
            "stream": true,
        ]
        // [T-codex-prompt-cache-headers] Same stable per-conversation key the
        // Responses path sends. OpenAI's prompt-caching guide states that on
        // GPT-5.6+ you *must* set `prompt_cache_key` to get the more reliable
        // prefix matching, and this path had no key at all — so any GPT-5.x
        // model reached over an OpenAI-compatible chat-completions endpoint
        // was matching on the weaker heuristic.
        //
        // [T-ios-prompt-cache-key-400] Gated as of this fix. The original
        // version sent the key UNCONDITIONALLY on the assumption that vendors
        // which don't recognise it would ignore it as a harmless extra key.
        // That assumption is false: strict-schema third-party gateways reject
        // unknown request fields outright with
        // `400 UNKNOWN_FIELD: 未知请求字段：prompt_cache_key`, which fails the
        // whole request rather than degrading to an uncached one. Only the
        // official OpenAI endpoint gets the key on this path.
        if Self.shouldSendPromptCacheKey(for: provider) {
            body["prompt_cache_key"] = Self.derivePromptCacheKey(from: messages)
        }
        if provider.useOpenRouterCompat {
            body["max_tokens"] = maxTokens
        } else {
            body["max_completion_tokens"] = maxTokens
            body["stream_options"] = ["include_usage": true]
        }
        // [GH#191] Opt this request into Anthropic prompt caching. OpenRouter
        // passes the field through to Anthropic but never injects it for us, so
        // without it Claude requests cache nothing at all. Top-level "automatic"
        // form: the breakpoint advances to the last cacheable block on its own as
        // the conversation grows, which is what an agent loop wants — the
        // per-block form would need us to manage a 4-breakpoint budget by hand.
        // Gated on host + `anthropic/` prefix, so no other model's body changes.
        if provider.needsOpenRouterAnthropicCacheControl {
            body["cache_control"] = ["type": "ephemeral"]
        }
        if !toolsPayload.isEmpty {
            body["tools"] = toolsPayload
        }
        // Inject provider-specific thinking parameters.
        // Some families (Qwen3, DeepSeek V4, etc.) think by default server-side —
        // when the user toggles thinking off, we MUST explicitly disable, otherwise
        // the server runs in thinking mode and later rejects our tool-call history
        // for missing `reasoning_content` (observed as DeepSeek 400 on
        // deepseek-v4-flash with the toggle off).
        // Always inject for these families and OpenRouter; other models only
        // when thinking is enabled.
        // `supportsReasoning == nil` (catalog unknown — e.g. DeepSeek V4 fetched
        // from /v1/models) is treated as "allowed"; the UI lets the user toggle
        // in this case, so honor their choice and send the params.
        if model.supportsReasoning ?? true {
            // Mistral rejects `reasoning: {effort: ...}` with 422. Don't
            // inject thinking params at all on this endpoint.
            //
            // [T-thinking-off-explicit] Previously the OFF case only injected
            // for the families that need an explicit disable (OpenRouter /
            // Qwen / DeepSeek-V4) and OMITTED the field everywhere else —
            // letting each vendor apply its own default reasoning tier. Now
            // injectThinkingParams is always consulted (it owns the per-family
            // rules, including which families must keep omitting) and receives
            // the provider-appropriate off value: official OpenAI understands
            // "none"; custom-base vendors get "minimal" (Ark's smallest tier,
            // also a valid OpenAI value, so relays serving gpt models work too).
            // [T-thinking-rules-phase1] The Mistral prohibition is now a RULE inside the
            // resolver (`mistral-official` → .omitEverything) rather than an `if` wrapped
            // around this call. Same outcome — zero thinking keys for Mistral — but the
            // reason is now visible in the trace instead of being invisible at the call
            // site, and it can no longer be lost when a second injection path is added
            // (which is exactly how the Responses API path ended up ungated, 37bab14c).
            let offEffort = Self.explicitOffEffort(for: provider, model: model, level: thinkingLevel)
            Self.injectThinkingParams(into: &body, model: model, level: thinkingLevel, isOpenRouter: provider.useOpenRouterCompat, maxTokens: maxTokens, offEffort: offEffort, unifiedReasoningEffort: provider.usesUnifiedReasoningEffort, isMistral: provider.isMistral, isXAI: provider.isXAI, isDashScope: provider.isDashScope, providerInstanceId: provider.providerInstanceId)
        }

        let (lineStream, _) = try await provider.streamRaw(body: body, isResponsesAPI: false)

        return AsyncThrowingStream { continuation in
            let task = Task {
                var emittedTextStart = false
                var hasToolCalls = false
                // Track parallel tool calls by index: [index: (id, name, accumulatedJSON)]
                var toolCallAccum: [Int: (id: String, name: String, json: String)] = [:]
                // Accumulate reasoning_content from thinking models (Kimi, DeepSeek, QwQ, etc.)
                var reasoningContent = ""
                // True once we've seen ANY reasoning field on the wire — even an empty
                // string. DeepSeek V4 emits `reasoning_content: ""` legitimately and we
                // round-trip that empty value back as history rather than fabricating
                // placeholder text the model would learn to imitate.
                var sawReasoningFieldEver = false
                // [T-ios-think-prefix-live-stream] Parser for content-embedded
                // <think> prefixes (MiniMax M-series, Qwen, …): streams the
                // reasoning live and trims the post-</think> body.
                var thinkParser = ThinkPrefixStreamParser()

                // Diagnostic state: capture the first few raw SSE lines and a
                // count of chunks parsed so we can attribute "empty response"
                // failures to the actual on-wire shape (e.g. servers that
                // ship JSON without the `data: ` SSE prefix, send `\r\n`
                // separators we drop, or emit a single blob then close). The
                // capture is replayed only when the stream closes without
                // having yielded any visible content, so the happy path
                // stays quiet.
                var diagFirstLines: [String] = []
                var diagLineCount = 0
                var diagDataLineCount = 0
                var diagYieldedAnyContent = false
                let diagCaptureLimit = 8

                // Emit a (thinking, visible) pair from the think parser:
                // thinking accumulates for end-of-turn persistence and streams
                // live as .thinkingDelta (same gating as the reasoning_content
                // field path); visible opens the text block on first output.
                func emitParsed(_ out: (thinking: String, visible: String)) {
                    if !out.thinking.isEmpty {
                        reasoningContent += out.thinking
                        if thinkingLevel.isEnabled { continuation.yield(.thinkingDelta(out.thinking)) }
                    }
                    if !out.visible.isEmpty {
                        if !emittedTextStart {
                            continuation.yield(.contentBlockStart(.text))
                            emittedTextStart = true
                        }
                        continuation.yield(.textDelta(out.visible))
                        diagYieldedAnyContent = true
                    }
                }

                do {
                    for try await line in lineStream {
                        diagLineCount += 1
                        if diagFirstLines.count < diagCaptureLimit {
                            diagFirstLines.append(line.count > 240 ? String(line.prefix(240)) + "…" : line)
                        }
                        guard let payload = Self.ssePayload(from: line) else { continue }
                        diagDataLineCount += 1
                        if payload == "[DONE]" {
                            // Flush the think parser's tail (cross-chunk tag
                            // fragment / withheld trailing whitespace).
                            emitParsed(thinkParser.finishTurn())
                            if sawReasoningFieldEver || !reasoningContent.isEmpty {
                                continuation.yield(.reasoningContent(reasoningContent))
                            }
                            let reason: AgentStopReason = hasToolCalls ? .toolUse : .endTurn
                            continuation.yield(.done(stopReason: reason))
                            break
                        }

                        guard let data = payload.data(using: .utf8),
                              let event = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { continue }

                        // Check for error in SSE stream (OpenRouter / proxy providers)
                        if let error = event["error"] as? [String: Any] {
                            let message = error["message"] as? String ?? "Unknown streaming error"
                            let code = error["code"] as? Int
                            logger.error("SSE stream error: \(message)")
                            throw LLMError.providerError(message: "[\(code ?? -1)] \(message)")
                        }

                        // Usage
                        if let usage = event["usage"] as? [String: Any] {
                            let details = usage["prompt_tokens_details"] as? [String: Any]
                            // DeepSeek reports cache hits at `usage.prompt_cache_hit_tokens`
                            // instead of the OpenAI-native `prompt_tokens_details.cached_tokens`.
                            let cacheRead = (details?["cached_tokens"] as? Int)
                                ?? (usage["prompt_cache_hit_tokens"] as? Int)
                            let promptTokens = usage["prompt_tokens"] as? Int ?? 0
                            // `prompt_tokens` is the FULL input (cached + fresh); subtract the
                            // cached portion so `inputTokens` is fresh-only — matching
                            // OpenAIProvider.parseChatCompletionsUsage(). Without this,
                            // latestContextTokens = inputTokens + cacheReadTokens double-counts
                            // the cached tokens, inflating "Input (incl. cache)" by ~2x.
                            let freshInput = (cacheRead.map { promptTokens - $0 }).flatMap { $0 >= 0 ? $0 : nil } ?? promptTokens
                            let u = LLMUsage(
                                inputTokens: freshInput,
                                outputTokens: usage["completion_tokens"] as? Int ?? 0,
                                cacheCreationInputTokens: details?["cache_creation_input_tokens"] as? Int,
                                cacheReadInputTokens: cacheRead
                            )
                            continuation.yield(.usage(u))
                        }

                        guard let choices = event["choices"] as? [[String: Any]],
                              let choice = choices.first else { continue }

                        let finishReason = choice["finish_reason"] as? String
                        let delta = choice["delta"] as? [String: Any] ?? [:]

                        // Reasoning content from thinking models via dedicated fields.
                        // Preserve empty strings — DeepSeek V4 emits them legitimately
                        // and round-tripping the empty value avoids fabricating placeholder
                        // text that the model would otherwise learn to imitate.
                        var sawReasoningField = false
                        if let rc = delta["reasoning_content"] as? String {
                            sawReasoningField = true
                            reasoningContent += rc
                            if thinkingLevel.isEnabled, !rc.isEmpty { continuation.yield(.thinkingDelta(rc)) }
                        }
                        if let rc = delta["reasoning"] as? String {
                            sawReasoningField = true
                            reasoningContent += rc
                            if thinkingLevel.isEnabled, !rc.isEmpty { continuation.yield(.thinkingDelta(rc)) }
                        }
                        // Also check model-specific interleaved field (e.g. "reasoning_details")
                        if thinkingLevel.isEnabled,
                           let field = self.model.interleavedReasoningField,
                           field != "reasoning_content",
                           let rc = delta[field] as? String {
                            sawReasoningField = true
                            reasoningContent += rc
                            if !rc.isEmpty { continuation.yield(.thinkingDelta(rc)) }
                        }
                        if sawReasoningField { sawReasoningFieldEver = true }

                        // Text delta — route through the think-prefix parser so
                        // models that embed reasoning in content (MiniMax
                        // M-series, Qwen, DeepSeek via OpenRouter, etc.) stream
                        // their thinking live and get a trimmed body.
                        if let text = delta["content"] as? String, !text.isEmpty {
                            emitParsed(thinkParser.consume(text))
                        }

                        // Tool call deltas
                        if let toolCalls = delta["tool_calls"] as? [[String: Any]] {
                            // Resolve a still-undecided short buffer (e.g. text
                            // shorter than "<think>") to visible body NOW —
                            // otherwise the ViewModel's pre-tool flush would
                            // commit a truncated snapshot and the trailing
                            // chars wouldn't reach the UI until finish_reason
                            // (seconds later, after the full tool_call
                            // arguments have streamed in).
                            emitParsed(thinkParser.resolveAtToolBoundary())
                            for tc in toolCalls {
                                guard let index = tc["index"] as? Int else { continue }
                                let fn = tc["function"] as? [String: Any] ?? [:]

                                // Guard against empty id/name: DeepSeek V4 Flash sends
                                // id:"" name:"" on every arguments delta chunk (unlike
                                // OpenAI, which omits them after the first chunk). Since
                                // `"" as? String` binds successfully, an unguarded check
                                // would treat each delta as a NEW tool call and overwrite
                                // the valid accumulator entry with empty values.
                                if let id = tc["id"] as? String, !id.isEmpty,
                                   let name = fn["name"] as? String, !name.isEmpty {
                                    // New tool call start
                                    emittedTextStart = false
                                    hasToolCalls = true
                                    toolCallAccum[index] = (id: id, name: name, json: "")
                                    continuation.yield(.contentBlockStart(.toolUse(id: id, name: name)))
                                    diagYieldedAnyContent = true
                                }

                                if let argDelta = fn["arguments"] as? String,
                                   var entry = toolCallAccum[index] {
                                    entry.json += argDelta
                                    toolCallAccum[index] = entry
                                    continuation.yield(.toolInputDelta(name: entry.name, accumulated: entry.json))
                                }
                            }
                        }

                        // Finish reason
                        // [T-intern-s2-empty-finish-reason] Some providers
                        // (e.g. intern-s2-preview) send `"finish_reason": ""` on
                        // EVERY streaming chunk instead of OpenAI's `null`/omitted.
                        // An empty string still binds via `if let`, so without the
                        // `!isEmpty` guard the very first chunk would flush the
                        // tool call before its arguments finished accumulating —
                        // emitting an empty-args ({}) tool call and orphaning the
                        // real argument deltas in later chunks.
                        if let fr = finishReason, !fr.isEmpty {
                            // Flush the think parser's tail (idempotent — the
                            // [DONE] handler may flush again harmlessly).
                            emitParsed(thinkParser.finishTurn())
                            logger.info("SSE finish_reason=\(fr) hasToolCalls=\(hasToolCalls) emittedTextStart=\(emittedTextStart) reasoningLen=\(reasoningContent.count)")
                            // Emit completed tool calls
                            for (_, entry) in toolCallAccum.sorted(by: { $0.key < $1.key }) {
                                let args = Self.parseJsonToDict(entry.json)
                                if args.isEmpty {
                                    Self.diagEmptyToolArgs(rawJson: entry.json, toolName: entry.name, toolId: entry.id, model: self.model.id, source: "chatCompletions.finishReason")
                                }
                                continuation.yield(.toolCallComplete(
                                    id: entry.id, name: entry.name, args: args, metadata: nil
                                ))
                            }
                            toolCallAccum.removeAll()

                            // Emit accumulated reasoning content from thinking models.
                            // Also emit when the field appeared on the wire as an empty
                            // string (DeepSeek V4) so the assistant turn round-trips an
                            // empty `reasoning_content` instead of nil → placeholder.
                            if sawReasoningFieldEver || !reasoningContent.isEmpty {
                                continuation.yield(.reasoningContent(reasoningContent))
                            }

                            let reason: AgentStopReason
                            switch fr {
                            case "tool_calls": reason = .toolUse
                            case "length": reason = .maxTokens
                            case "content_filter":
                                logger.warning("[OpenAIStream] finish_reason=content_filter — OpenAI safety system blocked this response")
                                reason = .refusal
                            default: reason = hasToolCalls ? .toolUse : .endTurn
                            }
                            continuation.yield(.done(stopReason: reason))
                        }
                    }
                    // Empty-stream diagnostic. The "no content, no stop reason"
                    // detector higher up will throw transientError on this
                    // outcome; before that fires, dump what actually came over
                    // the wire so a third-party provider that ships raw JSON,
                    // unusual delimiters, or a one-shot blob can be triaged
                    // from logs without re-running the request.
                    if !diagYieldedAnyContent && !sawReasoningFieldEver {
                        let preview = diagFirstLines.joined(separator: " | ")
                        logger.warning("[OpenAIStream] empty stream diagnostic — totalLines=\(diagLineCount) dataLines=\(diagDataLineCount) firstLines=\(preview)")
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: self.provider.mapError(error))
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - Responses API (OAuth/Codex mode)

    private func streamResponsesAPI(
        messages: [AgentMessage],
        systemPrompt: String?,
        tools: [AgentToolDefinition],
        maxTokens: Int,
        thinkingLevel: ThinkingLevel = .off
    ) async throws -> AsyncThrowingStream<AgentStreamEvent, Error> {
        let inputMessages = convertMessagesResponsesAPI(messages)
        let responsesTools = convertToolsResponsesAPI(tools)

        let isCodexOAuth = !provider.forceResponsesAPI && provider.isOAuth && provider.customBaseURL == nil

        var body: [String: Any] = [
            "model": model.id,
            "stream": true,
            "store": false,
            "parallel_tool_calls": true,
            "input": inputMessages,
        ]
        // Stable per-conversation key so the Responses API can hit prompt cache
        // across turns. Codex CLI sets this to its conversation_id; we don't have
        // one at this layer, so we derive a stable hash from the first user
        // message's text, which is prepended every turn of the same chat.
        // Required for custom/third-party endpoints (e.g. sub2api) that do not
        // synthesize a fallback key server-side — see Wei-Shaw/sub2api#1134.
        //
        // [T-ios-prompt-cache-key-400] Now gated. Unlike the chat path this one
        // deliberately KEEPS the key for `forceResponsesAPI` relays: that flag
        // is an explicit "this base speaks the Responses API" opt-in, sub2api is
        // the documented reason the field was added here, and those relays need
        // it because they synthesize no server-side fallback. What is excluded
        // is the un-opted-in custom base — the case that produced the 400.
        if Self.shouldSendPromptCacheKey(for: provider) {
            body["prompt_cache_key"] = Self.derivePromptCacheKey(from: messages)
        }
        // Thinking level → Responses API `reasoning.effort`.
        // Applies to every Responses flavor (Codex OAuth + forceResponsesAPI +
        // custom-base). Previously only Codex honored the user's level, so
        // Responses-API provider types silently ignored thinking settings.
        var reasoningRequested = false
        // [T-ios-mistral-reasoning-422] Mistral rejects the reasoning request
        // parameter outright (`422 extra_forbidden body.reasoning`, OpenMinis#87),
        // and its ChatCompletionRequest-era guard only ever covered the Chat
        // Completions path (the `if !provider.isMistral` around
        // injectThinkingParams). This builder is a SECOND, independent injection
        // site: a Mistral instance with useResponsesAPI enabled reached it
        // ungated and put `reasoning` back on the wire. Suppress the whole block
        // — for Mistral the answer to "should any thinking field be sent" is
        // NEVER, on every request path.
        if provider.isMistral {
            // No reasoning key, and reasoningRequested stays false so the
            // encrypted-reasoning include below is skipped too.
        } else if isCodexOAuth {
            // The authenticated Codex catalog owns its effort tiers. Preserve
            // newly advertised values such as "ultra" instead of applying the
            // API-compatible mapping below, then resolve against this account's
            // model declaration. Thinking off retains the Codex low-effort
            // fallback, adjusted when this model does not accept "low".
            let requestedEffort = thinkingLevel.isEnabled ? thinkingLevel.rawValue : "low"
            let effort = CodexModelCatalog.resolvedEffort(
                requested: requestedEffort,
                supported: model.reasoningEffortValues
            )
            body["reasoning"] = ["effort": effort, "summary": "auto"]
            reasoningRequested = true
        } else if thinkingLevel.isEnabled, let effort = Self.reasoningEffort(for: model, level: thinkingLevel) {
            // `summary: "auto"` opts in to streaming the human-readable
            // reasoning summary (delivered as `response.reasoning_summary_text.delta`
            // SSE events). Without it the Responses API only returns
            // `encrypted_content`, so the UI has nothing to show except a
            // token count. Safe to send to every Responses-flavor endpoint:
            // OpenAI ignores unknown variants and falls back to "auto"-equiv.
            body["reasoning"] = ["effort": effort, "summary": "auto"]
            reasoningRequested = true
        } else if model.supportsReasoning ?? false,
                  let offEffort = Self.explicitOffEffort(for: provider, model: model, level: thinkingLevel) {
            // [T-thinking-off-explicit] Thinking OFF on a reasoning-capable
            // model: send the explicit off tier instead of omitting the
            // `reasoning` key — omission lets the vendor default kick in.
            // Same ALLOWLIST as the Chat path (official OpenAI → "none",
            // Volcano Ark → "minimal"); vendors with undocumented off
            // semantics keep the historical omission. No summary/include:
            // nothing should stream back.
            body["reasoning"] = ["effort": offEffort]
        }
        // Request the encrypted reasoning payload whenever reasoning is
        // requested. The `encrypted_content` is opaque + model-specific; we
        // capture it on each turn and echo back to the SAME model id on
        // subsequent requests (see ReasoningEcho + convertMessagesResponsesAPI
        // for the model-id-gated round-trip). Applies to every Responses-API
        // model that supports reasoning — Codex OAuth, gpt-5.x, o-series,
        // and any future model the server recognizes. Not model-name-gated.
        if reasoningRequested {
            body["include"] = ["reasoning.encrypted_content"]
        }
        // [T-responses-max-output-tokens] The function always RECEIVED
        // maxTokens (dynamicMaxTokens result: model override + context-window
        // aware) but never wrote it into the body — so Responses-API vendors
        // fell back to their own tiny defaults (Ark 4096, some relays 1000)
        // and long outputs were truncated. Official field name for the
        // Responses API is max_output_tokens (NOT max_completion_tokens).
        // Codex OAuth is EXCLUDED: its body shape is part of the codex_cli
        // client fingerprint (same discipline as the extra-body skip in
        // OpenAIProvider) and the ChatGPT backend applies its own generous
        // output limit — the truncation complaint only concerns third-party
        // Responses vendors.
        if maxTokens > 0, !isCodexOAuth {
            body["max_output_tokens"] = maxTokens
        }
        if let sys = systemPrompt, !sys.isEmpty {
            body["instructions"] = sys
        }
        if !responsesTools.isEmpty {
            body["tools"] = responsesTools
            body["tool_choice"] = "auto"
        }
        // [T-codex-fast-mode] Fast Mode toggle ("..." menu). This is the CHAT
        // agent-loop body builder — the injection in
        // OpenAIProvider.buildResponsesAPIRequest covers only the offload /
        // non-agent paths, which is why the first device trace showed Codex
        // requests without the field. Wire value: codex_cli_rs sends
        // service_tier="priority" for its Fast mode. Broadened per user
        // request from Codex-OAuth-only to every Responses-API flavor (this
        // function IS the Responses path — incl. forceResponsesAPI relays
        // like sub2api, which pass service_tier through) gated on a
        // gpt-family model id, mirroring the official fast catalog.
        if UserDefaults.standard.bool(forKey: OpenAIProvider.fastModeDefaultsKey),
           model.id.lowercased().contains("gpt") {
            body["service_tier"] = "priority"
        }

        let (lineStream, _) = try await provider.streamRaw(body: body, isResponsesAPI: true)

        return AsyncThrowingStream { continuation in
            let task = Task {
                var emittedTextStart = false
                var hasToolCalls = false
                // Track current function call items: [item_id: (call_id, name, accumulatedJSON)]
                var functionCallAccum: [String: (callId: String, name: String, json: String)] = [:]

                // Per-item streamed summary text. Keyed by reasoning item id.
                // OpenAI Responses API can emit multiple reasoning items in a
                // single response (think → tool → think → tool …). Each item is
                // its own thinking block on the UI side; merging them into one
                // accumulator was the source of the "block 2 overwrites block 1"
                // and stale-prefix bugs.
                var reasoningTextPerItem: [String: String] = [:]
                // Insertion order so we can stitch items back together for the
                // legacy aggregated `reasoningContent` carry-back below.
                var reasoningItemOrder: [String] = []
                // Id of the most recently `output_item.added`-announced reasoning
                // item — deltas reference items by `item_id` on the event payload,
                // but a defensive fallback in case the field is absent.
                var currentReasoningItemId: String? = nil

                do {
                    for try await line in lineStream {
                        guard let payload = Self.ssePayload(from: line) else { continue }
                        if payload == "[DONE]" { break }

                        guard let data = payload.data(using: .utf8),
                              let event = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { continue }

                        let type = event["type"] as? String ?? ""

                        switch type {
                        // Reasoning text deltas (Responses API)
                        case "response.reasoning_text.delta",
                             "response.reasoning_summary_text.delta":
                            if let delta = event["delta"] as? String, !delta.isEmpty {
                                let itemId = (event["item_id"] as? String) ?? currentReasoningItemId
                                if let itemId {
                                    if reasoningTextPerItem[itemId] == nil {
                                        reasoningTextPerItem[itemId] = ""
                                        if !reasoningItemOrder.contains(itemId) {
                                            reasoningItemOrder.append(itemId)
                                        }
                                    }
                                    reasoningTextPerItem[itemId, default: ""] += delta
                                }
                                if thinkingLevel.isEnabled {
                                    continuation.yield(.thinkingDelta(delta))
                                }
                            }

                        case "response.output_text.delta":
                            if !emittedTextStart {
                                continuation.yield(.contentBlockStart(.text))
                                emittedTextStart = true
                            }
                            if let delta = event["delta"] as? String {
                                continuation.yield(.textDelta(delta))
                            }

                        case "response.function_call_arguments.delta":
                            if let itemId = event["item_id"] as? String,
                               let delta = event["delta"] as? String,
                               var entry = functionCallAccum[itemId] {
                                entry.json += delta
                                functionCallAccum[itemId] = entry
                                continuation.yield(.toolInputDelta(name: entry.name, accumulated: entry.json))
                            }

                        case "response.output_item.done":
                            if let item = event["item"] as? [String: Any],
                               let itemType = item["type"] as? String,
                               itemType == "reasoning" {
                                // Encrypted-only reasoning items (no streamed
                                // summary deltas) are NOT rendered in the UI
                                // — server emits these for short / trivial
                                // reasoning where it skipped summary generation.
                                // A "Reasoning (encrypted)" placeholder gave
                                // no actionable info to the user. The
                                // encrypted_content is still captured into
                                // ReasoningEcho at response.completed and
                                // round-tripped to the same model on next
                                // turn — UI hiding is decoupled from chain-
                                // of-thought continuity. Matches Hermes Agent
                                // (NousResearch) and Codex CLI behavior.
                                let itemId = item["id"] as? String ?? ""
                                if currentReasoningItemId == itemId {
                                    currentReasoningItemId = nil
                                }
                            } else if let item = event["item"] as? [String: Any],
                               let itemType = item["type"] as? String,
                               itemType == "function_call",
                               let itemId = item["id"] as? String,
                               let entry = functionCallAccum[itemId] {
                                let combinedId = Self.combineResponsesAPIIds(callId: entry.callId, fcId: itemId)
                                let args = Self.parseJsonToDict(entry.json)
                                if args.isEmpty {
                                    Self.diagEmptyToolArgs(rawJson: entry.json, toolName: entry.name, toolId: combinedId, model: self.model.id, source: "responsesAPI.outputItemDone")
                                }
                                continuation.yield(.toolCallComplete(
                                    id: combinedId, name: entry.name, args: args, metadata: nil
                                ))
                                functionCallAccum.removeValue(forKey: itemId)
                            }

                        case "response.output_item.added":
                            if let item = event["item"] as? [String: Any],
                               let itemType = item["type"] as? String,
                               itemType == "reasoning" {
                                let itemId = item["id"] as? String ?? ""
                                currentReasoningItemId = itemId
                                if !itemId.isEmpty {
                                    reasoningTextPerItem[itemId] = ""
                                    if !reasoningItemOrder.contains(itemId) {
                                        reasoningItemOrder.append(itemId)
                                    }
                                }
                                logger.info("Reasoning item detected (id: \(itemId.isEmpty ? "?" : itemId))")
                            } else if let item = event["item"] as? [String: Any],
                               let itemType = item["type"] as? String,
                               itemType == "function_call",
                               let itemId = item["id"] as? String,
                               let callId = item["call_id"] as? String,
                               let name = item["name"] as? String {
                                emittedTextStart = false
                                hasToolCalls = true
                                let combinedId = Self.combineResponsesAPIIds(callId: callId, fcId: itemId)
                                functionCallAccum[itemId] = (callId: callId, name: name, json: "")
                                continuation.yield(.contentBlockStart(.toolUse(id: combinedId, name: name)))
                            }

                        case "response.failed":
                            // [T-responses-terminal-events] Explicit terminal
                            // handling instead of the generic fallthrough: pull
                            // the structured error off the response object so
                            // the thrown LLMError carries the real reason (and
                            // so retry/fallback classification can act on it).
                            // Official shape: response.status == "failed",
                            // response.error = {code, message}.
                            let response = event["response"] as? [String: Any]
                            let err = response?["error"] as? [String: Any]
                            let code = (err?["code"] as? String) ?? "unknown"
                            let message = (err?["message"] as? String) ?? "response.failed with no error detail"
                            logger.error("response.failed — code: \(code), message: \(message)")
                            if code == "server_error" || code == "rate_limit_exceeded" {
                                // Transient family: retry on the same model
                                // rather than falling back through the group.
                                throw LLMError.transientError(message: "[\(code)] \(message)")
                            }
                            throw LLMError.providerError(message: "[\(code)] \(message)")

                        case "response.incomplete":
                            // [T-responses-terminal-events] The server ended
                            // the response early; incomplete_details.reason is
                            // "max_output_tokens" or "content_filter". Partial
                            // output has already been streamed — surface WHY it
                            // stopped instead of silently ending the stream.
                            let response = event["response"] as? [String: Any]
                            let reason = ((response?["incomplete_details"] as? [String: Any])?["reason"] as? String) ?? "unknown"
                            logger.error("response.incomplete — reason: \(reason)")
                            throw LLMError.providerError(
                                message: "Response ended incomplete (reason: \(reason))"
                                    + (reason == "max_output_tokens"
                                       ? " — output hit max_output_tokens; raise the model's Max Output Tokens or shorten the request."
                                       : ""))

                        case "response.completed":
                            let response = event["response"] as? [String: Any]
                            let apiStatus = response?["status"] as? String
                            let apiStopReason = response?["stop_reason"] as? String
                                ?? (response?["incomplete_details"] as? [String: Any])?["reason"] as? String

                            // Check for reasoning items in output and log reasoning token usage
                            let outputItems = (response?["output"] as? [[String: Any]]) ?? []
                            let reasoningItems = outputItems.filter { ($0["type"] as? String) == "reasoning" }
                            let usage = response?["usage"] as? [String: Any]
                            let reasoningTokens = (usage?["output_tokens_details"] as? [String: Any])?["reasoning_tokens"] as? Int ?? 0

                            // [T-codex-fast-mode] Server-side receipt: the
                            // response object echoes the EFFECTIVE tier —
                            // "priority" here is the definitive proof Fast
                            // Mode was honored (vs merely requested); the
                            // server silently downgrades to default when the
                            // account/model isn't eligible.
                            let effectiveTier = response?["service_tier"] as? String
                            logger.info("response.completed — status: \(apiStatus ?? "nil"), stop_reason: \(apiStopReason ?? "nil"), service_tier: \(effectiveTier ?? "nil"), hasToolCalls: \(hasToolCalls), reasoningItems: \(reasoningItems.count), reasoningTokens: \(reasoningTokens)")

                            // Aggregate per-item streamed summary text into a
                            // single `reasoningContent` field on the assistant
                            // message. This carries:
                            //   - real summary text (preserved verbatim, no marker)
                            //   - per-item encrypted placeholders (with marker)
                            // through the existing chat-completions converter so
                            // cross-family switches (gpt-5.x → deepseek) strip
                            // appropriately. The UI's thinking blocks are NOT
                            // updated from this string — they were already filled
                            // incrementally by `.thinkingDelta` events at the
                            // correct stream positions.
                            let allText = reasoningItemOrder
                                .compactMap { reasoningTextPerItem[$0] }
                                .filter { !$0.isEmpty }
                                .joined(separator: "\n\n")
                            if !allText.isEmpty {
                                continuation.yield(.reasoningContent(allText))
                            }
                            // No text — leave reasoningContent nil. The
                            // chat-completions converter's
                            // `injectReasoningPlaceholder` branch handles
                            // cross-family carry-back with an empty string.
                            // No UI block was created so nothing to fill.

                            // Capture native reasoning items (id + encrypted_content
                            // + summary[]) for in-memory multi-turn replay. These
                            // are echoed back to the SAME model id only — cross-
                            // model switches strip them in convertMessagesResponsesAPI.
                            // See ReasoningEcho for the isolation contract.
                            if !reasoningItems.isEmpty {
                                let captured: [ReasoningEcho.Item] = reasoningItems.compactMap { item in
                                    guard let id = item["id"] as? String else { return nil }
                                    let encrypted = item["encrypted_content"] as? String
                                    let summary: [String] = ((item["summary"] as? [[String: Any]]) ?? []).compactMap { part in
                                        part["text"] as? String
                                    }
                                    // Drop items with neither encrypted content nor any
                                    // summary text — nothing useful to echo or display.
                                    if encrypted == nil && summary.isEmpty { return nil }
                                    return .openaiReasoning(id: id, encryptedContent: encrypted, summary: summary)
                                }
                                if !captured.isEmpty {
                                    let echo = ReasoningEcho(
                                        providerKind: Self.responsesAPIProviderKind,
                                        modelId: self.model.id,
                                        items: captured
                                    )
                                    continuation.yield(.reasoningEcho(echo))
                                }
                            }
                            // Usage
                            if let usage {
                                let inputDetails = usage["input_tokens_details"] as? [String: Any]
                                let cacheRead = inputDetails?["cached_tokens"] as? Int
                                // `input_tokens` is the FULL input and ALREADY INCLUDES the
                                // cached portion, so subtract it to keep `inputTokens`
                                // fresh-only — the convention every other consumer assumes
                                // (UsageStatsView's hit rate divides by
                                // input + cacheRead + cacheCreate, and latestContextTokens
                                // sums the same way). This parser used to pass `input_tokens`
                                // through raw, double-counting the cached tokens: measured on
                                // device at 9817 input / 7680 cached, the UI showed 43.9%
                                // when the true rate was 78.2%, and latestContextTokens read
                                // 17497 for a context that was really 9817.
                                // Matches OpenAIProvider.parseResponsesAPIUsageDict and
                                // parseChatCompletionsUsage, which both already subtract.
                                let totalInput = usage["input_tokens"] as? Int ?? 0
                                let freshInput = (cacheRead.map { totalInput - $0 }).flatMap { $0 >= 0 ? $0 : nil } ?? totalInput
                                let u = LLMUsage(
                                    inputTokens: freshInput,
                                    outputTokens: usage["output_tokens"] as? Int ?? 0,
                                    cacheCreationInputTokens: nil,
                                    cacheReadInputTokens: cacheRead
                                )
                                continuation.yield(.usage(u))
                            }
                            // Use the API's actual stop reason to decide loop continuation.
                            // Codex models may emit text + function_calls in the same response;
                            // relying on hasToolCalls alone could miss edge cases.
                            let reason: AgentStopReason
                            if hasToolCalls {
                                reason = .toolUse
                            } else if apiStatus == "incomplete" && apiStopReason == "content_filter" {
                                logger.warning("[OpenAIStream] response.completed status=incomplete reason=content_filter — OpenAI safety system blocked this response")
                                reason = .refusal
                            } else if apiStatus == "incomplete" {
                                reason = .maxTokens
                            } else {
                                reason = .endTurn
                            }
                            continuation.yield(.done(stopReason: reason))

                        default:
                            break
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: self.provider.mapError(error))
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - Prompt Cache Key

    /// [T-ios-prompt-cache-key-400] Whether this provider may receive the
    /// `prompt_cache_key` request field.
    ///
    /// ALLOWLIST, not blanket — same shape as `explicitOffEffort`. The field is
    /// a pure cache-locality hint with no behavioural meaning, so the cost of
    /// omitting it is a weaker cache hit rate, while the cost of sending it to a
    /// vendor that doesn't know it can be total request failure: strict-schema
    /// gateways answer `400 UNKNOWN_FIELD: 未知请求字段：prompt_cache_key`
    /// (reported against self-hosted deepseek-v4 endpoints) instead of ignoring
    /// the extra key. That asymmetry is why the default must be "don't send".
    ///
    /// Allowed:
    ///   • official OpenAI base (`customBaseURL == nil`, non-Azure) — the vendor
    ///     that defines the field; required on GPT-5.6+ for reliable prefix
    ///     matching.
    ///   • `forceResponsesAPI` relays — an explicit user opt-in declaring the
    ///     base speaks the Responses API (sub2api and friends). These need the
    ///     key because they synthesize no server-side fallback
    ///     (Wei-Shaw/sub2api#1134), and opting in is the user asserting
    ///     Responses-API compatibility.
    ///
    /// Everyone else — plain custom-base OpenAI-compatible endpoints, Azure —
    /// gets the field omitted, which is the pre-regression behaviour.
    ///
    /// TODO: if a specific non-`forceResponsesAPI` gateway is later confirmed to
    /// handle the field correctly, add it here by base-URL match rather than
    /// widening the default.
    static func shouldSendPromptCacheKey(for provider: OpenAIProvider) -> Bool {
        if provider.customBaseURL == nil && !provider.isAzure { return true }
        return provider.forceResponsesAPI
    }

    /// Derives a stable per-conversation `prompt_cache_key` for the Responses API.
    ///
    /// The Responses API hits prompt cache by matching stable prefixes keyed on
    /// `prompt_cache_key`. Codex CLI sets this to its `conversation_id`; at this
    /// layer we don't have one, so we hash the first user message's text, which
    /// is re-sent verbatim on every turn of the same chat and therefore stable
    /// across turns while differing between chats.
    ///
    /// When there is no user text yet (first turn with an empty placeholder,
    /// tool-only input, etc.) it falls back to hashing the FIRST message's
    /// structural shape instead. That shape is re-sent verbatim on later turns,
    /// so the key stays stable for the conversation.
    ///
    /// It used to return a fresh `UUID()` in that case, which guaranteed a
    /// different key on every request of such a conversation and therefore a
    /// permanent 100% cache miss — the exact failure mode this key exists to
    /// prevent. A constant is not an option either: distinct conversations must
    /// not collide onto one key.
    private static func derivePromptCacheKey(from messages: [AgentMessage]) -> String {
        for msg in messages where msg.role == .user {
            for part in msg.parts {
                if case .text(let t) = part, !t.isEmpty {
                    return cacheKey(hashing: t)
                }
            }
        }
        // No user text anywhere — derive from the first message's shape so the
        // key is still deterministic across turns.
        if let first = messages.first {
            let shape = first.parts.map { part -> String in
                switch part {
                case .text(let t): return "t:\(t.count)"
                case .toolUse(let id, let name, _): return "tu:\(name):\(id)"
                case .toolResult(let id, let name, _, _, _, _, _, _): return "tr:\(name):\(id)"
                case .imageData(_, let mime, _): return "img:\(mime)"
                }
            }.joined(separator: "|")
            if !shape.isEmpty {
                return cacheKey(hashing: "\(first.role)|\(shape)")
            }
        }
        // Truly nothing to key on (empty message list). Constant rather than a
        // random UUID: with no content there is no prefix to collide over, and a
        // fixed value at least lets consecutive such requests share a cache
        // entry instead of guaranteeing a miss.
        return cacheKey(hashing: "minis-empty-conversation")
    }

    /// `minis-<first 32 hex of SHA256>` — the shared shape for every cache key
    /// this type derives.
    private static func cacheKey(hashing input: String) -> String {
        let digest = SHA256.hash(data: Data(input.utf8))
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return "minis-\(hex.prefix(32))"
    }

    // MARK: - Thinking Config

    /// [T-thinking-off-explicit] The wire value for "thinking OFF", or nil to
    /// keep the historical omit-the-field behavior. ALLOWLIST, not blanket:
    /// the first pass sent "minimal" to every custom-base vendor and the
    /// on-device sweep showed why that's wrong — MiMo's strict enum rejects
    /// it outright, and generic-branch vendors with unknown semantics (xAI
    /// grok, NVIDIA NIM-hosted models) can't be assumed to accept it either.
    /// Only vendors whose off tier is DOCUMENTED get an explicit value:
    ///   • official OpenAI base → "none" (their documented off tier);
    ///   • Volcano Ark (volces/ark bases, seed/doubao families) → "minimal"
    ///     (their smallest tier — the vendor whose non-off default motivated
    ///     this fix in the first place).
    /// Everyone else keeps field omission = the vendor's own default, which
    /// is exactly the pre-fix behavior.
    static func explicitOffEffort(for provider: OpenAIProvider, model: LLMModel, level: ThinkingLevel) -> String? {
        guard !level.isEnabled else { return nil }
        if provider.customBaseURL == nil && !provider.isAzure { return "none" }
        let base = provider.customBaseURL?.lowercased() ?? ""
        let lid = model.id.lowercased()
        if base.contains("volces") || base.contains("ark.")
            || lid.contains("seed-") || lid.contains("doubao") {
            return "minimal"
        }
        return nil
    }

    /// Returns the reasoning effort level string for the model, or nil if not supported.
    /// [T-xhigh-effort-clamp] Model families whose backends only accept
    /// low/medium/high for reasoning_effort and reject "xhigh" outright
    /// (MiMo-2.5/Pro → 400 literal_error; Agnes → 422 unknown variant).
    static func reasoningEffort(for model: LLMModel, level: ThinkingLevel = .medium) -> String? {
        guard model.supportsReasoning ?? false else { return nil }
        return switch level {
        case .off: nil
        case .low: "low"
        case .medium: "medium"
        case .high: "high"
        case .xhigh: "xhigh"
        // [T-ios-ultra-effort-clamp] Ultra is a CLIENT-SIDE "Max + multi-agent
        // orchestration" toggle, not a server reasoning-effort tier. The wire
        // `reasoning.effort` field tops out at "max": ChatGPT/Codex compact
        // endpoints reject a literal "ultra" (400 invalid effort / silently
        // ignored), and CLIProxyAPI / sub2api both send "max" for Ultra (their
        // forwarding registries list levels only up to "max"; the "ultra" entry
        // is display-manifest-only and never enters the request body). So clamp
        // ultra → "max" here. If/when we implement the orchestration layer it
        // stays a local behavior; the effort string never becomes "ultra".
        case .max, .ultra: "max"
        }
    }

    /// Inject provider-specific thinking parameters into the Chat Completions request body.
    /// Different OpenAI-compatible providers use different mechanisms:
    ///   - OpenAI native: `reasoning_effort` (low/medium/high)
    ///   - Qwen3: `enable_thinking: true` + `thinking_budget` in extra body
    ///   - DeepSeek/GLM/Kimi/MiniMax: no params needed (model always reasons)
    /// [T-thinking-off-explicit] `offEffort` is the provider-appropriate wire
    /// value for "thinking OFF" ("none" for official OpenAI, "minimal" for
    /// custom-base providers like Volcano Ark whose smallest tier is minimal).
    /// nil = keep the historical omit-the-field behavior. Only the branches
    /// where an explicit off value is known-safe consume it; forced-reasoning
    /// families (DeepSeek pre-V4 / GLM / Kimi / MiniMax) and OpenRouter keep
    /// omitting, because those backends reject or misinterpret an off tier
    /// ("Reasoning is mandatory for this endpoint").
    /// - Parameter unifiedReasoningEffort: [T-unified-reasoning-effort] true when
    ///   the endpoint (Volcengine Ark / Azure) exposes EVERY model through a single
    ///   OpenAI `reasoning_effort` surface. When set, the vendor-native id branches
    ///   (`deepseek-v4` → `thinking:{}`, and the `deepseek/glm/kimi/minimax`
    ///   self-reasoning skip) are bypassed so those models fall through to the
    ///   generic `reasoning_effort` path instead. Only Ark/Azure set this, so
    ///   official direct DeepSeek/GLM/Kimi endpoints keep their native behavior.
    /// Inject the provider-specific thinking parameters for one request.
    ///
    /// [T-thinking-rules-phase1] The body of this function used to be a six-branch
    /// if-return chain keyed on model-id substrings. That logic now lives in
    /// `ThinkingRuleResolver` as a data-driven rule registry (see design §4/§5); this
    /// remains as the call-site-compatible entry point so every caller — and the golden
    /// snapshot that pins this exact signature — is unchanged.
    ///
    /// Behaviour is byte-for-byte identical to the pre-refactor chain, enforced by
    /// ThinkingWireGoldenSnapshotTests (105 rows generated against the old code and
    /// committed before the refactor, fdc28e2b).
    ///
    /// PHASE 1 SCOPE: OpenAI-compatible endpoints only. Gemini and Anthropic keep their
    /// own emitters and are not routed through the resolver yet.
    static func injectThinkingParams(into body: inout [String: Any], model: LLMModel, level: ThinkingLevel, isOpenRouter: Bool = false, maxTokens: Int = 0, offEffort: String? = nil, unifiedReasoningEffort: Bool = false, isMistral: Bool = false, isXAI: Bool = false, isDashScope: Bool = false, providerInstanceId: String? = nil) {
        // [T-thinking-rules-phase2] Load this instance's user-authored rules. Absent an
        // instance id (title-gen references, tests) or with no rules stored, this is []
        // and resolution is byte-for-byte the Phase 1 behaviour.
        let userRules = providerInstanceId.map { ThinkingRuleCache.shared.rules(for: $0) } ?? []
        let ctx = ThinkingResolveContext(
            modelId: model.id,
            supportsReasoning: model.supportsReasoning,
            declaredEffortValues: model.reasoningEffortValues,
            declaresNoEffortTiers: model.declaresNoEffortTiers ?? false,
            effortDeclarationIsAuthoritative: model.effortDeclarationIsAuthoritative ?? false,
            isXAI: isXAI,
            level: level,
            maxTokens: maxTokens,
            isOpenRouter: isOpenRouter,
            usesUnifiedReasoningEffort: unifiedReasoningEffort,
            isMistral: isMistral,
            isDashScope: isDashScope,
            offEffort: offEffort,
            userRules: userRules
        )
        // [T-thinking-vision-diag] The INPUTS the decision turned on, logged before the
        // decision itself. Every one of these was reconstructed by hand at least once
        // during the thinking-off 400 investigation — `authoritative` in particular is
        // invisible from the request body, from the model id, and from the endpoint URL,
        // so without it a log reader cannot tell a vendor's own declaration from a guess
        // about somebody else's relay.
        //
        // Debug-level: this is one extra line per request and, unlike the `[resolve]`
        // line below, it is only of interest once something already looks wrong.
        // Metadata only — no prompt, no key, no image.
        thinkingLogger.debug(
            "[resolve.in] model=\(model.id) level=\(level.rawValue)"
            + " supportsReasoning=\(model.supportsReasoning.map(String.init(describing:)) ?? "nil")"
            + " declaredTiers=\(model.reasoningEffortValues.map { "[\($0.joined(separator: ","))]" } ?? "nil")"
            + " authoritative=\(model.effortDeclarationIsAuthoritative.map(String.init(describing:)) ?? "nil")"
            + " noEffortTiers=\(model.declaresNoEffortTiers.map(String.init(describing:)) ?? "nil")"
            + " offEffort=\(offEffort ?? "nil")"
            + " endpoint=[openrouter=\(isOpenRouter) unified=\(unifiedReasoningEffort)"
            + " mistral=\(isMistral) xai=\(isXAI) dashscope=\(isDashScope)]"
            + " userRules=\(userRules.count)"
        )
        let trace = ThinkingRuleResolver.apply(to: &body, ctx: ctx)
        // [T-thinking-rules-observability] Design §8 / OpenMinis#100: which rule actually
        // won must be inspectable, or a rule layer just replaces one hidden variable with
        // a more complicated one. minis-config exposure is Phase 2.
        //
        // [T-thinking-vision-diag] `trace.logLine` now also carries `gates=[…]` whenever a
        // gate intervened, so this single INFO line answers both "which rule won" and
        // "did anything override it, on what evidence" — the two questions the original
        // "Provider error: 400" report could not answer at all.
        thinkingLogger.info("[resolve] model=\(model.id) level=\(level.rawValue) \(trace.logLine)")
    }

    /// [T-thinking-levels-data-driven] The wire effort string for a UI level,
    /// before any per-model clamp. Single source for the mapping that was
    /// previously re-spelled in each branch.
    ///
    /// `.off` maps to "low" so callers can use this unconditionally — every
    /// caller guards on `level.isEnabled` first, and the OFF wire value is
    /// chosen separately by `explicitOffEffort`.
    /// [T-ios-ultra-effort-clamp] `.ultra` is a client-side orchestration
    /// concept; on the wire it tops out at "max".
    static func wireEffort(for level: ThinkingLevel) -> String {
        switch level {
        case .off, .low: "low"
        case .medium: "medium"
        case .high: "high"
        case .xhigh: "xhigh"
        case .max, .ultra: "max"
        }
    }

    /// [T-reasoning-effort-data-driven] Snap an effort string onto the tiers the
    /// model actually declares.
    ///
    /// Necessary because the catalog's effort sets are far from uniform —
    /// `["low","medium","high"]`, `["high","max"]`, `["high","xhigh"]`,
    /// `["none","high","max"]` all occur. Sending an undeclared tier is the same
    /// class of failure the MiMo/Agnes xhigh clamp already exists for ("Invalid
    /// reasoning_effort: xhigh" 400s), so honoring the declaration is what makes
    /// enabling these families safe.
    ///
    /// Nearest-tier semantics: walk down to the closest declared tier at or below
    /// the request, and only if none exists walk up to the lowest declared one.
    /// Downgrading is preferred because overshooting costs the user money and
    /// latency they did not ask for. `values == nil` (no declaration) passes the
    /// string through untouched, preserving the pre-existing behavior.
    static func clampEffort(_ effort: String, to values: [String]?) -> String {
        guard let values, !values.isEmpty else { return effort }
        if values.contains(effort) { return effort }
        // Canonical weakest → strongest ladder. Anything the catalog lists that
        // isn't on this ladder (unknown future tier) is ignored for ordering
        // purposes but still honored by the exact-match check above.
        let ladder = ["none", "minimal", "low", "medium", "high", "xhigh", "max"]
        guard let want = ladder.firstIndex(of: effort) else { return effort }
        let declared = values.compactMap { v in ladder.firstIndex(of: v).map { ($0, v) } }
            .sorted { $0.0 < $1.0 }
        guard !declared.isEmpty else { return effort }
        if let below = declared.last(where: { $0.0 <= want }) { return below.1 }
        return declared[0].1
    }

    // MARK: - DashScope Cache Control

    /// Inject `cache_control: {"type": "ephemeral"}` breakpoints for DashScope Qwen models.
    /// Breakpoints (up to 4 total per DashScope API):
    ///   1. system prompt — handled at call site (content-block array format)
    ///   2. tools[last]
    ///   3. messages[N-2 user] — penultimate user message
    ///   4. messages[N-1 user] — last user message
    static func injectDashScopeCacheControl(
        messages: inout [[String: Any]],
        tools: inout [[String: Any]]
    ) {
        let cacheControl: [String: String] = ["type": "ephemeral"]

        // Inject on last tool definition
        if !tools.isEmpty {
            var lastTool = tools[tools.count - 1]
            if var fn = lastTool["function"] as? [String: Any] {
                fn["cache_control"] = cacheControl
                lastTool["function"] = fn
            }
            tools[tools.count - 1] = lastTool
        }

        // Inject on last 2 user messages (scanning backwards)
        var injected = 0
        for i in stride(from: messages.count - 1, through: 0, by: -1) {
            guard injected < 2 else { break }
            guard let role = messages[i]["role"] as? String, role == "user" else { continue }

            // Convert content to array format if it's a plain string
            if let text = messages[i]["content"] as? String {
                messages[i]["content"] = [
                    ["type": "text", "text": text, "cache_control": cacheControl],
                ] as [[String: Any]]
            } else if var parts = messages[i]["content"] as? [[String: Any]], !parts.isEmpty {
                // Attach cache_control to the last content block
                parts[parts.count - 1]["cache_control"] = cacheControl
                messages[i]["content"] = parts
            }
            injected += 1
        }
    }

    // MARK: - Chat Completions Message Conversion

    private func convertMessagesChatCompletions(_ messages: [AgentMessage]) -> [[String: Any]] {
        messages.map { msg in
            let role = msg.role == .user ? "user" : "assistant"
            var result: [String: Any] = ["role": role]

            // Check if this is a tool_result message
            let toolResults = msg.parts.compactMap { part -> (String, String, Bool)? in
                if case .toolResult(let id, _, let content, let isError, _, _, _, _) = part {
                    return (id, content, isError)
                }
                return nil
            }

            if !toolResults.isEmpty {
                // Tool result messages use role "tool" in Chat Completions
                var toolMessages: [[String: Any]] = []
                for (id, content, _) in toolResults {
                    toolMessages.append([
                        "role": "tool",
                        "tool_call_id": Self.capChatId(id),
                        "content": content,
                    ])
                }
                // Return first tool message; remaining will need separate entries
                // For simplicity, concatenate results into one
                // Actually for Chat Completions, each tool result is a separate message
                // We'll handle this in the flattening below
                return toolMessages.first ?? ["role": "tool", "content": ""]
            }

            // Check for tool_use parts (assistant message with function calls)
            let toolUses = msg.parts.compactMap { part -> (String, String, [String: Any])? in
                if case .toolUse(let id, let name, let input) = part { return (id, name, input) }
                return nil
            }

            if !toolUses.isEmpty {
                var toolCalls: [[String: Any]] = []
                for (id, name, input) in toolUses {
                    let argsJSON = (try? JSONSerialization.data(withJSONObject: input)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
                    toolCalls.append([
                        "id": Self.capChatId(id),
                        "type": "function",
                        "function": ["name": name, "arguments": argsJSON],
                    ])
                }
                result["tool_calls"] = toolCalls

                // Also include any text parts
                let textParts = msg.parts.compactMap { part -> String? in
                    if case .text(let t) = part { return t }
                    return nil
                }
                if !textParts.isEmpty {
                    result["content"] = textParts.joined()
                }
            } else {
                // Pure text or mixed content
                let supportsImages = model.capabilities.supportedModalities.contains(.imageInput)
                var contentParts: [[String: Any]] = []
                for part in msg.parts {
                    switch part {
                    case .text(let text):
                        contentParts.append(["type": "text", "text": text])
                    case .imageData(let data, let mimeType, let linuxPath):
                        if supportsImages {
                            let base64 = data.base64EncodedString()
                            contentParts.append([
                                "type": "image_url",
                                "image_url": ["url": "data:\(mimeType);base64,\(base64)"],
                            ])
                        } else {
                            // [T-ios-vision-group-t264 #182] Points the model at
                            // read_image (with the real path) when a Vision Group
                            // is configured; unchanged wording otherwise.
                            contentParts.append(["type": "text", "text": VisionGroupResolver.attachmentPlaceholder(linuxPath: linuxPath)])
                        }
                    default:
                        break
                    }
                }
                if contentParts.count == 1, let text = contentParts.first?["text"] as? String {
                    result["content"] = text
                } else if !contentParts.isEmpty {
                    result["content"] = contentParts
                }
            }

            return result
        }
    }

    /// Flatten messages for Chat Completions: tool results need to be separate "tool" role messages.
    ///
    /// Gate for echoing captured `reasoning_content`:
    ///
    ///   echo = (thinkingLevel on  OR  model always reasons)  AND  model may reason
    ///
    /// - `thinkingLevel.isEnabled` — user intent for *this* request. When the
    ///   user has thinking off, we never echo. This is the cross-provider /
    ///   cross-session guard (commit 37b2bdd): `AgentMessage.reasoningContent`
    ///   persists in the DB and can come from a prior Claude thinking turn, a
    ///   Codex Responses turn, or DeepSeek-when-thinking-was-on. Leaking it
    ///   into a later non-reasoning request gets rejected by strict
    ///   OpenAI-compatible servers ("Extra inputs are not permitted") and by
    ///   DeepSeek when thinking is explicitly disabled.
    /// - `modelAlwaysReasons` — forced-reasoning models (OpenRouter DeepSeek
    ///   R1, Kimi K2.5, etc.) always produce reasoning and require the field
    ///   echoed even when the UI shows the toggle off.
    /// - `modelMayReason` — a veto: only clamp to `false` when we actively
    ///   know the model rejects reasoning (catalog says `false`). `nil` =
    ///   unknown stays permissive so DeepSeek V4 fetched from `/v1/models`
    ///   (no capability metadata) still works.
    func flattenChatCompletionsMessages(_ messages: [AgentMessage], thinkingLevel: ThinkingLevel = .off) -> [[String: Any]] {
        let modelAlwaysReasons = model.supportsReasoning == true
        let modelMayReason     = model.supportsReasoning ?? true
        let includeReasoning   = (thinkingLevel.isEnabled || modelAlwaysReasons) && modelMayReason
        // [T-ios-mistral-reasoning-422] Mistral message-level validation is
        // strict (AssistantMessage additionalProperties:false) — the
        // reasoning_content field may NEVER be sent there, regardless of
        // thinking level or where the history came from. Same detection the
        // thinking-param skip already uses (provider.isMistral, base URL
        // contains mistral.ai).
        let forbidReasoningField = provider.isMistral
        // Placeholder is a subset of the echo path. Two triggers:
        //  1. forced-reasoning model with thinking enabled (legacy: tool-call turns
        //     where reasoning wasn't captured),
        //  2. model uses an interleaved reasoning field (DeepSeek V4, Kimi, GLM…)
        //     and current request includes reasoning — the server rejects history
        //     where any assistant turn lacks reasoning_content.
        // Note: OpenAI o-series satisfies (1) but its API ignores unknown
        // message-level `reasoning_content` fields, so injection is harmless there.
        let placeholderAllowed = includeReasoning &&
            (modelAlwaysReasons || model.interleavedReasoningField != nil)

        let supportsImages = model.capabilities.supportedModalities.contains(.imageInput)
        var result: [[String: Any]] = []
        for msg in messages {
            // Destructure WITH imageData so vision-capable models can see images
            // produced by read_image / browser_use. OpenAI Chat Completions
            // protocol does NOT allow image_url inside role:"tool" messages, so
            // we emit the tool result as a text-only "tool" message and then
            // append a synthetic role:"user" message carrying the image_url
            // right after it. The synthetic user is transient — it only lives
            // in the outbound HTTP body; agentHistory / ChatStore / UI still
            // see the original .toolResult(.., imageData) untouched.
            // [T-openai-tool-result-image]
            let toolResults = msg.parts.compactMap { part -> (id: String, name: String, content: String, isError: Bool, imageData: Data?, imageMime: String?)? in
                if case .toolResult(let id, let name, let content, let isError, let imageData, let imageMime, _, _) = part {
                    return (id, name, content, isError, imageData, imageMime)
                }
                return nil
            }

            if !toolResults.isEmpty {
                for tr in toolResults {
                    result.append([
                        "role": "tool",
                        "tool_call_id": Self.capChatId(tr.id),
                        "content": tr.content,
                    ])
                    // Attach the image (if any) as a synthetic user message
                    // right after the tool reply. Skipped for non-vision
                    // models so we don't 400 on text-only endpoints.
                    if let data = tr.imageData, supportsImages {
                        let mime = tr.imageMime ?? "image/jpeg"
                        let base64 = data.base64EncodedString()
                        result.append([
                            "role": "user",
                            "content": [[
                                "type": "image_url",
                                "image_url": ["url": "data:\(mime);base64,\(base64)"],
                            ]] as [[String: Any]],
                        ])
                    }
                }
            } else {
                // Defensive: drop assistant messages that carry no echoable
                // content (no text, no tool_use). Such rows can land in DB if a
                // stream was aborted mid-flight; sending them as-is triggers
                // `400 content or tool_calls must be set` on DeepSeek/OpenAI-compat.
                if msg.role == .assistant {
                    let hasEcho = msg.parts.contains { part in
                        switch part {
                        case .text(let s): return !s.isEmpty
                        case .toolUse, .imageData: return true
                        case .toolResult: return false
                        }
                    }
                    if !hasEcho { continue }
                }
                result.append(convertSingleMessageChatCompletions(
                    msg,
                    includeReasoning: includeReasoning,
                    injectReasoningPlaceholder: placeholderAllowed,
                    forbidReasoningField: forbidReasoningField
                ))
            }
        }
        return Self.sanitizeToolCallAdjacency(Self.globallyDedupeToolCallIds(result))
    }

    /// Cross-message defense-in-depth: rename any `tool_call_id` that
    /// collides with one already seen elsewhere in this request. The
    /// stream-time dedupe (9421990) handles same-turn collisions, but
    /// historical messages reloaded from the DB — or messages produced by
    /// a different provider before the user switched — bypass that pass.
    /// DeepSeek (and several OpenAI-compat gateways) reject the assembled
    /// request with `Duplicate value for tool_call_id ... in message[N]`
    /// whenever any id repeats across the full `messages` array, not just
    /// within a single assistant turn.
    ///
    /// We walk messages in order, collect every id used (in assistant
    /// `tool_calls.id` *and* in role:"tool" `tool_call_id`), and on
    /// collision rename to `{id}-{N}` with N starting at 2. Both ends of
    /// each tool_use ↔ tool_result pair are rewritten in lockstep so the
    /// adjacency invariant downstream still holds — collisions across
    /// pair-boundaries map to a fresh unique id for the *second* pair,
    /// keeping the first pair's id intact.
    ///
    /// Identity stays stable: we never drop tool_calls or tool replies,
    /// only rename ids. The model never sees these renamed ids — they're
    /// pure client→server transport tokens.
    private static func globallyDedupeToolCallIds(_ messages: [[String: Any]]) -> [[String: Any]] {
        // Two-pass: first walk to build a per-id rename plan that
        // matches each role:"tool" reply to its preceding assistant
        // `tool_calls` entry. Single linear scan works because OpenAI
        // Chat Completions requires every role:"tool" to follow its
        // claiming assistant tool_calls (sanitizeToolCallAdjacency
        // already enforces this just below).
        var seen: [String: Int] = [:]                 // raw id → max suffix issued
        var renameForPendingResults: [String: String] = [:] // raw id → renamed id, queued for the next role:"tool" with that id
        var out: [[String: Any]] = []
        out.reserveCapacity(messages.count)
        var renamedCount = 0

        // Build a `{trimmedBase}-{N}` id that still fits within the Chat
        // Completions id length cap. capChatId() has already trimmed raw
        // ids to 40 chars upstream, so a naive `"\(raw)-\(n)"` could
        // overflow. Trim the base just enough to leave room for the
        // suffix.
        func renamedId(rawId: String, n: Int) -> String {
            let suffix = "-\(n)"
            let cap = maxChatCompletionsIdLength
            let baseRoom = max(0, cap - suffix.count)
            let base = rawId.count <= baseRoom ? rawId : String(rawId.prefix(baseRoom))
            return base + suffix
        }

        for msg in messages {
            let role = msg["role"] as? String
            if role == "assistant", var toolCalls = msg["tool_calls"] as? [[String: Any]], !toolCalls.isEmpty {
                var newToolCalls: [[String: Any]] = []
                newToolCalls.reserveCapacity(toolCalls.count)
                for var call in toolCalls {
                    guard let rawId = call["id"] as? String else {
                        newToolCalls.append(call)
                        continue
                    }
                    let used = seen[rawId] ?? 0
                    let renamed: String
                    if used == 0 {
                        renamed = rawId
                        seen[rawId] = 1
                    } else {
                        let n = used + 1
                        renamed = renamedId(rawId: rawId, n: n)
                        seen[rawId] = n
                        renamedCount += 1
                    }
                    // Queue the rename so the *next* role:"tool" entry
                    // that claims this rawId picks up the same renamed
                    // id. Stash the latest rename for each raw id so
                    // pairs across tool loop iterations stay paired.
                    renameForPendingResults[rawId] = renamed
                    call["id"] = renamed
                    newToolCalls.append(call)
                }
                toolCalls = newToolCalls
                var newMsg = msg
                newMsg["tool_calls"] = toolCalls
                out.append(newMsg)
            } else if role == "tool", let rawId = msg["tool_call_id"] as? String {
                // Pick up the most recently issued rename for this raw id
                // (the matching assistant tool_calls came before us in
                // the array thanks to sanitizeToolCallAdjacency ordering;
                // here we only get called pre-sanitize, but downstream
                // adjacency repair will still pair on the renamed id
                // because both sides are now consistent).
                if let renamedId = renameForPendingResults[rawId], renamedId != rawId {
                    var newMsg = msg
                    newMsg["tool_call_id"] = renamedId
                    out.append(newMsg)
                } else {
                    out.append(msg)
                }
            } else {
                out.append(msg)
            }
        }

        if renamedCount > 0 {
            logger.warning("[dedupe-tool-call-id] renamed \(renamedCount) duplicate tool_call_id(s) across messages — likely DB-loaded history or cross-provider switch")
        }
        return out
    }

    /// OpenAI Chat Completions requires every `tool_calls` entry on an assistant
    /// message to be immediately followed by a matching `role:"tool"` reply for
    /// each `tool_call_id`. The agent layer pairs tool_use/tool_result by id but
    /// doesn't enforce ordering, so cross-provider history (Anthropic/Gemini →
    /// OpenAI-compat fallback) or queued user prompts inserted mid-turn can leave
    /// the flattened array with a tool_calls block whose tool replies appear
    /// later — DeepSeek/OpenAI reject it with 400 "insufficient tool messages
    /// following tool_calls message".
    ///
    /// Two-pass repair: (1) collect every role:tool entry by id (skipping it
    /// from the body), (2) re-emit, splicing the matching tool replies right
    /// after each assistant.tool_calls and injecting error placeholders for any
    /// still missing. Replies that no assistant turn claims are dropped.
    private static func sanitizeToolCallAdjacency(_ messages: [[String: Any]]) -> [[String: Any]] {
        var pendingToolReplies: [String: [String: Any]] = [:]
        var body: [[String: Any]] = []
        body.reserveCapacity(messages.count)

        for msg in messages {
            if (msg["role"] as? String) == "tool", let id = msg["tool_call_id"] as? String {
                if pendingToolReplies[id] != nil {
                    logger.warning("[sanitize] duplicate role:tool entry for id=\(id) — keeping first")
                } else {
                    pendingToolReplies[id] = msg
                }
            } else {
                body.append(msg)
            }
        }

        var out: [[String: Any]] = []
        out.reserveCapacity(messages.count)
        for msg in body {
            out.append(msg)
            guard (msg["role"] as? String) == "assistant",
                  let toolCalls = msg["tool_calls"] as? [[String: Any]], !toolCalls.isEmpty else {
                continue
            }
            for call in toolCalls {
                guard let id = call["id"] as? String else { continue }
                if let reply = pendingToolReplies.removeValue(forKey: id) {
                    out.append(reply)
                } else {
                    let toolName = (call["function"] as? [String: Any])?["name"] as? String ?? "unknown"
                    logger.warning("[sanitize] injecting placeholder tool reply for orphan tool_call id=\(id) name=\(toolName)")
                    out.append([
                        "role": "tool",
                        "tool_call_id": id,
                        "content": "Tool execution result is unavailable (history was truncated or interrupted).",
                    ])
                }
            }
        }

        if !pendingToolReplies.isEmpty {
            logger.warning("[sanitize] dropped \(pendingToolReplies.count) orphan role:tool entr(ies) with no matching assistant.tool_calls: \(pendingToolReplies.keys.sorted().joined(separator: ","))")
        }

        return out
    }

    private func convertSingleMessageChatCompletions(
        _ msg: AgentMessage,
        includeReasoning: Bool = false,
        injectReasoningPlaceholder: Bool = false,
        forbidReasoningField: Bool = false
    ) -> [String: Any] {
        let role = msg.role == .user ? "user" : "assistant"
        var result: [String: Any] = ["role": role]

        // Reasoning-content echo. Split into two independent paths so a
        // captured value always round-trips even when the *current*
        // request has thinking turned off (matches 9d957faa's stated
        // intent: "presence is the trigger"). The placeholder-injection
        // path remains gated by includeReasoning because that one synth-
        // esizes a field that didn't actually exist on the original
        // streaming turn (T-mimo-reasoning-echo-34671).
        //
        // Why this matters for Mimo V2.5: Mimo's API rejects multi-turn
        // requests with 400 "Param Incorrect" when an assistant turn
        // carries tool_calls but no reasoning_content. For users who
        // configure Mimo via the generic OpenAI-compat provider:
        //   - model.supportsReasoning == nil → modelAlwaysReasons=false
        //   - UI default thinkingLevel == .off
        //   - → includeReasoning = false
        //   - → previously the gate dropped the captured rc → 400
        //
        // [T-ios-mistral-reasoning-422] …and why the OPPOSITE holds for
        // Mistral (issue OpenMinis#87): Mistral's OpenAPI declares
        // AssistantMessage with `additionalProperties: false` (only
        // role/content/tool_calls/prefix) — `reasoning_content` is
        // categorically forbidden and 422s the whole request the moment a
        // history turn carries one (e.g. after switching a conversation
        // from a reasoning model to a Mistral model). Their native
        // reasoning representation is content ThinkChunks, not this field,
        // so `forbidReasoningField` suppresses BOTH echo paths entirely.
        if msg.role == .assistant, !forbidReasoningField {
            if let rc = msg.reasoningContent {
                // The Responses-API encrypted-reasoning summary ("Reasoning: N
                // tokens (encrypted)") is a UI-only string that has no value as
                // history input — Codex ignores it and non-Codex providers
                // (DeepSeek / Kimi / GLM / QwQ) will in-context-learn it and
                // start echoing it back as their own reasoning. Strip it down
                // to "" for those providers; the empty value still satisfies
                // the field-presence requirement.
                if rc.hasPrefix(Self.encryptedReasoningMarker) {
                    result["reasoning_content"] = ""
                } else {
                    // Round-trip exactly what the model emitted — including empty
                    // strings. DeepSeek V4 emits `reasoning_content: ""` for non-
                    // thinking turns and accepts the same shape on input. A real
                    // empty value is preferable to a fabricated marker, which the
                    // model would otherwise learn to imitate across turns.
                    result["reasoning_content"] = rc
                }
            } else if includeReasoning, injectReasoningPlaceholder {
                // No reasoning was captured for this turn (e.g. a prior assistant
                // turn produced before thinking was enabled, or a fallback to a
                // non-thinking model). Servers like DeepSeek V4 still require the
                // field to be present once thinking is on — send an empty string
                // rather than a synthetic marker, since fabricated text trains
                // the model to mimic that style. Gated on includeReasoning so we
                // only synthesize a field the user actually opted into.
                result["reasoning_content"] = ""
            }
        }

        let toolUses = msg.parts.compactMap { part -> (String, String, [String: Any])? in
            if case .toolUse(let id, let name, let input) = part { return (id, name, input) }
            return nil
        }

        if !toolUses.isEmpty {
            var toolCalls: [[String: Any]] = []
            for (id, name, input) in toolUses {
                let argsJSON = (try? JSONSerialization.data(withJSONObject: input)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
                toolCalls.append([
                    "id": Self.capChatId(id),
                    "type": "function",
                    "function": ["name": name, "arguments": argsJSON],
                ])
            }
            result["tool_calls"] = toolCalls
            let textParts = msg.parts.compactMap { part -> String? in
                if case .text(let t) = part { return t }
                return nil
            }
            if !textParts.isEmpty {
                result["content"] = textParts.joined()
            }
        } else {
            let supportsImages = model.capabilities.supportedModalities.contains(.imageInput)
            var contentParts: [[String: Any]] = []
            for part in msg.parts {
                switch part {
                case .text(let text):
                    contentParts.append(["type": "text", "text": text])
                case .imageData(let data, let mimeType, let linuxPath):
                    if supportsImages {
                        let base64 = data.base64EncodedString()
                        contentParts.append([
                            "type": "image_url",
                            "image_url": ["url": "data:\(mimeType);base64,\(base64)"],
                        ])
                    } else {
                        // [T-ios-vision-group-t264 #182] See the sibling site in
                        // convertMessagesChatCompletions.
                        contentParts.append(["type": "text", "text": VisionGroupResolver.attachmentPlaceholder(linuxPath: linuxPath)])
                    }
                default:
                    break
                }
            }
            if contentParts.count == 1, let text = contentParts.first?["text"] as? String {
                result["content"] = text
            } else if !contentParts.isEmpty {
                result["content"] = contentParts
            }
        }

        return result
    }

    // MARK: - Responses API Message Conversion

    private func convertMessagesResponsesAPI(_ messages: [AgentMessage]) -> [[String: Any]] {
        var result: [[String: Any]] = []
        for msg in messages {
            // Replay native reasoning items at the head of this assistant turn.
            // Order matters: Responses API rejects reasoning items that appear
            // after function_call items belonging to the same turn. Cross-model
            // and cross-family payloads are stripped — encrypted_content is
            // model-specific and meaningless (often 400-inducing) elsewhere.
            if msg.role == .assistant,
               let echo = msg.reasoningEcho,
               echo.providerKind == Self.responsesAPIProviderKind,
               echo.modelId == self.model.id {
                for item in echo.items {
                    if case .openaiReasoning(let id, let encrypted, let summary) = item {
                        // `summary` is a required field on input reasoning items
                        // even when empty (server returns
                        //   400 Missing required parameter: 'input[N].summary'
                        // otherwise). Always emit an array — populated when we
                        // captured summary text, empty otherwise.
                        var entry: [String: Any] = [
                            "type": "reasoning",
                            "id": id,
                            "summary": summary.map { ["type": "summary_text", "text": $0] },
                        ]
                        if let encrypted, !encrypted.isEmpty {
                            entry["encrypted_content"] = encrypted
                        }
                        result.append(entry)
                    }
                }
            }
            for part in msg.parts {
                switch part {
                case .text(let text):
                    let role = msg.role == .user ? "user" : "assistant"
                    result.append(["role": role, "content": text])

                case .toolUse(let id, let name, let input):
                    let argsStr = (try? JSONSerialization.data(withJSONObject: input)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
                    let (callId, fcId) = Self.splitResponsesAPIIds(id)
                    let safeCallId = Self.capResponsesId(callId)
                    var entry: [String: Any] = [
                        "type": "function_call",
                        "call_id": safeCallId,
                        "name": name,
                        "arguments": argsStr,
                    ]
                    // Responses API requires `function_call.id` to begin with
                    // `fc_`. gpt-5.5 enforces the prefix strictly and returns
                    // 400 Invalid 'input[N].id': 'item_xxx'. Expected an ID
                    // that begins with 'fc' when given anything else. History
                    // may carry non-`fc_` item ids from model switches, proxy /
                    // Responses-compatible forward layers, or earlier turns;
                    // the `call_id` itself remains valid because it is how
                    // `function_call_output` links back. Sanitize: reuse the
                    // captured fcId only when it has the `fc_` prefix,
                    // otherwise synthesize a deterministic `fc_syn_` id from
                    // the call_id so the API accepts the replay.
                    let safeFcId = fcId.map { Self.capResponsesId($0) }
                    if let safeFcId, safeFcId.hasPrefix("fc_") {
                        entry["id"] = safeFcId
                    } else {
                        if let safeFcId {
                            logger.info("sanitizing non-fc_ function_call id in replay: '\(safeFcId.prefix(40))' → fc_syn_…")
                        }
                        entry["id"] = "fc_syn_\(safeCallId.suffix(24))"
                    }
                    result.append(entry)

                case .toolResult(let id, _, let content, _, let imageData, let imageMime, _, _):
                    let (callId, _) = Self.splitResponsesAPIIds(id)
                    result.append([
                        "type": "function_call_output",
                        "call_id": Self.capResponsesId(callId),
                        "output": content,
                    ])
                    // Same trick as Chat Completions: Responses API's
                    // function_call_output items don't carry images either,
                    // so attach any tool-emitted image as a synthetic
                    // role:"user" item with input_image. Gated on vision
                    // support so we don't poison text-only o-mini variants.
                    // [T-openai-tool-result-image]
                    if let data = imageData,
                       model.capabilities.supportedModalities.contains(.imageInput) {
                        let mime = imageMime ?? "image/jpeg"
                        let base64 = data.base64EncodedString()
                        result.append([
                            "role": "user",
                            "content": [
                                ["type": "input_image", "image_url": "data:\(mime);base64,\(base64)"],
                            ],
                        ])
                    }

                case .imageData(let data, let mimeType, _):
                    if model.capabilities.supportedModalities.contains(.imageInput) {
                        let base64 = data.base64EncodedString()
                        result.append([
                            "role": msg.role == .user ? "user" : "assistant",
                            "content": [
                                ["type": "input_image", "image_url": "data:\(mimeType);base64,\(base64)"],
                            ],
                        ])
                    }
                }
            }
        }
        return result
    }

    // MARK: - Tool Conversion

    private func convertToolsChatCompletions(_ tools: [AgentToolDefinition]) -> [[String: Any]] {
        tools.map { tool in
            var properties: [String: [String: Any]] = [:]
            for (name, param) in tool.parameters {
                var prop: [String: Any] = [
                    "type": param.type.rawValue,
                    "description": param.description,
                ]
                if let enumValues = param.enumValues {
                    prop["enum"] = enumValues
                }
                properties[name] = prop
            }

            return [
                "type": "function",
                "function": [
                    "name": tool.name,
                    "description": tool.description,
                    "parameters": [
                        "type": "object",
                        "properties": properties,
                        "required": tool.required,
                    ] as [String: Any],
                ] as [String: Any],
            ]
        }
    }

    private func convertToolsResponsesAPI(_ tools: [AgentToolDefinition]) -> [[String: Any]] {
        tools.map { tool in
            var properties: [String: [String: Any]] = [:]
            for (name, param) in tool.parameters {
                var prop: [String: Any] = [
                    "type": param.type.rawValue,
                    "description": param.description,
                ]
                if let enumValues = param.enumValues {
                    prop["enum"] = enumValues
                }
                properties[name] = prop
            }

            return [
                "type": "function",
                "name": tool.name,
                "description": tool.description,
                "parameters": [
                    "type": "object",
                    "properties": properties,
                    "required": tool.required,
                ] as [String: Any],
            ]
        }
    }

    // MARK: - <think> Tag Extraction

    /// [T-ios-think-prefix-live-stream] Streaming parser for models that embed
    /// reasoning as a `<think>…</think>` PREFIX of the `content` field
    /// (MiniMax M-series, Qwen, some DeepSeek proxies) instead of using the
    /// `reasoning_content` field. Rules:
    ///
    ///  - Only a `<think>` at the very START of the turn's content (leading
    ///    whitespace tolerated and dropped) enters thinking mode. A `<think>`
    ///    appearing mid-text stays in the visible body verbatim — the old
    ///    extractor stripped it anywhere, which could eat legitimate body
    ///    text that merely quotes the tag.
    ///  - Thinking text is returned incrementally so the caller can yield
    ///    live `.thinkingDelta`s (the old extractor withheld ALL reasoning
    ///    until end-of-turn, so think-prefix models never showed a live
    ///    thinking bubble).
    ///  - After `</think>` the remainder is the reply body with whitespace
    ///    trimmed at both ends: leading (MiniMax always emits "\n\n" after
    ///    the closing tag — the source of every stored MiniMax message
    ///    starting with "\n") and trailing (a think-only turn's body
    ///    collapses to nothing instead of persisting as a whitespace-only
    ///    text block — the "blank gap between tool cards" bug). Interior
    ///    whitespace is untouched; a trailing-whitespace run is withheld
    ///    until the next non-whitespace chunk proves it interior.
    ///  - Turns that do NOT start with `<think>` pass through verbatim —
    ///    no trimming, matching the previous behavior for plain models.
    struct ThinkPrefixStreamParser {
        private enum Mode { case undecided, thinking, body }
        private var mode: Mode = .undecided
        private var buf = ""
        /// True when this turn opened with a think prefix — enables body trim.
        private var hadThinkPrefix = false
        /// Leading-body trim armed from `</think>` until the first
        /// non-whitespace body character.
        private var trimLeadingBody = false

        private static let openTag = "<think>"
        private static let closeTag = "</think>"

        /// Feed one streamed content chunk. Returns text to emit now.
        mutating func consume(_ chunk: String) -> (thinking: String, visible: String) {
            buf += chunk
            var thinking = ""
            var visible = ""

            loop: while !buf.isEmpty {
                switch mode {
                case .undecided:
                    // Decide against the buffer with leading whitespace skipped.
                    let t = buf.drop(while: { $0.isWhitespace })
                    if t.isEmpty { break loop }          // whitespace so far — wait
                    if t.count < Self.openTag.count {
                        if Self.openTag.hasPrefix(String(t)) { break loop }  // may still become <think>
                    } else if t.hasPrefix(Self.openTag) {
                        // Think turn: drop the pre-tag whitespace + the tag itself.
                        hadThinkPrefix = true
                        trimLeadingBody = true
                        mode = .thinking
                        buf = String(t.dropFirst(Self.openTag.count))
                        continue loop
                    }
                    // Plain turn: everything buffered (incl. leading
                    // whitespace) is body, verbatim from here on.
                    mode = .body
                    visible += buf
                    buf = ""

                case .thinking:
                    if let close = buf.range(of: Self.closeTag) {
                        thinking += String(buf[..<close.lowerBound])
                        buf = String(buf[close.upperBound...])
                        mode = .body
                        continue loop
                    }
                    // No close tag yet — emit all but the last 8 chars, which
                    // could be a partial "</think>" spanning chunks.
                    if buf.count > 8 {
                        thinking += String(buf.dropLast(8))
                        buf = String(buf.suffix(8))
                    }
                    break loop

                case .body:
                    if !hadThinkPrefix {
                        visible += buf
                        buf = ""
                        break loop
                    }
                    if trimLeadingBody {
                        buf = String(buf.drop(while: { $0.isWhitespace }))
                        if buf.isEmpty { break loop }
                        trimLeadingBody = false
                    }
                    // Withhold any trailing-whitespace run: emitted next chunk
                    // if interior, dropped at end-of-turn if truly trailing.
                    if let lastNonWS = buf.lastIndex(where: { !$0.isWhitespace }) {
                        let emitEnd = buf.index(after: lastNonWS)
                        visible += String(buf[..<emitEnd])
                        buf = String(buf[emitEnd...])
                    }
                    break loop
                }
            }
            return (thinking, visible)
        }

        /// Flush at a hard end-of-content boundary ([DONE] / finish_reason).
        /// Idempotent — a second call returns empty strings.
        mutating func finishTurn() -> (thinking: String, visible: String) {
            defer { buf = "" }
            switch mode {
            case .undecided:
                // Never resolved (short turn, possibly a partial "<thin"):
                // it's plain body text, verbatim.
                mode = .body
                return ("", buf)
            case .thinking:
                // Unclosed think — remainder is reasoning, not body.
                return (buf, "")
            case .body:
                // hadThinkPrefix: buf holds a withheld trailing-whitespace
                // run → drop it (trailing trim). Plain turns never hold.
                return ("", hadThinkPrefix ? "" : buf)
            }
        }

        /// Resolve a still-undecided buffer when a tool_call delta arrives —
        /// content preceding a tool call can no longer become a think prefix
        /// mid-way, and the ViewModel's pre-tool flush must see it now, not
        /// at finish_reason (seconds later, after tool args stream in).
        /// Thinking/body modes need no action here: `consume` already emitted
        /// everything except cross-chunk tag tails / withheld whitespace,
        /// both of which must stay buffered until `finishTurn`.
        mutating func resolveAtToolBoundary() -> (thinking: String, visible: String) {
            guard mode == .undecided, !buf.isEmpty else { return ("", "") }
            mode = .body
            defer { buf = "" }
            return ("", buf)
        }
    }

    // MARK: - Helpers

    /// Extract the JSON payload from an SSE `data:` line. Returns `nil` for
    /// non-data lines (comments, `event:`, blank). Tolerates the optional
    /// space after the colon — the HTML5 SSE spec only treats one leading
    /// space as ignorable, but some OpenAI-compatible servers (e.g. China
    /// Telecom's `eaichat.ctyun.cn` deepseek-v4-oc endpoint) ship `data:{...}`
    /// with no space and our previous strict `data: ` check dropped every
    /// chunk on that provider, surfacing as "empty stream" failures.
    private static func ssePayload(from line: String) -> String? {
        guard line.hasPrefix("data:") else { return nil }
        let after = line.dropFirst(5)
        if after.first == " " {
            return String(after.dropFirst())
        }
        return String(after)
    }

    private static func parseJsonToDict(_ json: String) -> [String: Any] {
        guard let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        return dict
    }

    /// [ToolArgsProbe] Diagnose empty-args problem: log enough to tell apart
    /// "stream never received any arg delta" (raw == "") vs "model truly sent
    /// empty `{}`" (raw == "{}") vs "stream truncated mid-JSON" (raw == `{"path"…`
    /// not closed). Called from both Chat Completions and Responses paths
    /// when the resolved dict is empty so we never print noise on the happy
    /// path. Output goes through AppLogger so it lands in the daily log file.
    fileprivate static func diagEmptyToolArgs(rawJson: String, toolName: String, toolId: String, model: String, source: String) {
        let utf8Bytes = rawJson.utf8.count
        let trimmedEmpty = rawJson.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let isLiteralEmptyObj = rawJson.trimmingCharacters(in: .whitespacesAndNewlines) == "{}"
        let parseOk = (try? JSONSerialization.jsonObject(with: Data(rawJson.utf8), options: [])) != nil
        // Hex of the first 80 bytes is enough to spot BOMs / hidden chars
        // when the raw text "looks" like "{}". The full raw payload below
        // is intentionally UNTRUNCATED — empty-args bugs are rare so log
        // volume is not a concern, and seeing the entire stream content is
        // the only way to tell apart "no delta ever arrived" / "literal
        // `{}`" / "truncated mid-stream" / "schema mismatch (other keys
        // present, just no `command`)".
        let hexPreview = rawJson.utf8.prefix(80).map { String(format: "%02x", $0) }.joined()
        AppLogger(category: "ToolArgsProbe").warning("[ToolArgsProbe] EMPTY ARGS source=\(source) model=\(model) tool=\(toolName) id=\(toolId) bytes=\(utf8Bytes) trimmedEmpty=\(trimmedEmpty ? 1 : 0) literalEmptyObj=\(isLiteralEmptyObj ? 1 : 0) parseOk=\(parseOk ? 1 : 0) hex80=\(hexPreview) raw=<<<\(rawJson)>>>")
    }

    // MARK: - Responses API Dual ID Helpers

    /// Maximum tool call ID lengths enforced by OpenAI's APIs.
    private static let maxChatCompletionsIdLength = 40
    private static let maxResponsesAPIIdLength = 64

    /// Combine call_id and fc_id (item id) into a single string for transport through the agent loop.
    /// Format: "call_id|fc_id"
    private static func combineResponsesAPIIds(callId: String, fcId: String) -> String {
        "\(callId)|\(fcId)"
    }

    /// Split a combined ID back into (call_id, fc_id).
    /// If the ID doesn't contain "|", treats it as call_id only (backward compatible).
    private static func splitResponsesAPIIds(_ combined: String) -> (callId: String, fcId: String?) {
        if let pipe = combined.firstIndex(of: "|") {
            let callId = String(combined[combined.startIndex..<pipe])
            let fcId = String(combined[combined.index(after: pipe)...])
            return (callId, fcId)
        }
        return (combined, nil)
    }

    /// Cap a tool ID to the Chat Completions limit (40 chars).
    private static func capChatId(_ id: String) -> String {
        id.count <= maxChatCompletionsIdLength ? id : String(id.prefix(maxChatCompletionsIdLength))
    }

    /// Cap a tool ID to the Responses API limit (64 chars).
    private static func capResponsesId(_ id: String) -> String {
        id.count <= maxResponsesAPIIdLength ? id : String(id.prefix(maxResponsesAPIIdLength))
    }
}
