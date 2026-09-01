//
//  ModelUseOffloadBridge.swift
//  MinisApp
//
//  Swift bridge for minis-model-use offload — lists, searches,
//  and invokes LLM models using configured providers.
//

import Foundation
import os.log
import UIKit
import UniformTypeIdentifiers

private let logger = AppLogger(category: "ModelUseOffload")

@objc public class ModelUseOffloadBridge: NSObject {

    private static let noModelsHint = "No models available. Go to Settings > Model Groups to add models that the agent can use."

    // MARK: - List Models

    @MainActor
    @objc public static func listModels(provider providerFilter: String?,
                                         modalityFilter: String?,
                                         showAll: Bool) -> NSDictionary {
        let store = ProviderConfigStore.shared
        // Always restrict to agent loop models — showAll is accepted but ignored
        var entries = store.resolvedAgentLoopEntries

        if let modalityFilter, !modalityFilter.isEmpty {
            entries = Self.filterByModality(entries, filter: modalityFilter)
        }

        let models = entries.map { Self.entryDict($0, store: store) }
        var result: [String: Any] = ["models": models, "count": models.count]
        if models.isEmpty {
            result["hint"] = Self.noModelsHint
        } else {
            result["usage"] = Self.usageHint
        }
        return result as NSDictionary
    }

    // MARK: - Search Models

    @MainActor
    @objc public static func searchModels(query: String,
                                           modalityFilter: String?,
                                           showAll: Bool) -> NSDictionary {
        let store = ProviderConfigStore.shared
        let q = query.lowercased()

        // Always restrict to agent loop models — showAll is accepted but ignored
        let pool = store.resolvedAgentLoopEntries
            .filter { store.instance(for: $0.providerInstanceId)?.isEnabled == true }

        var matches = pool.filter { entry in
            entry.model.id.lowercased().contains(q)
                || entry.model.displayName.lowercased().contains(q)
                || entry.model.provider.lowercased().contains(q)
        }

        if let modalityFilter, !modalityFilter.isEmpty {
            matches = Self.filterByModality(matches, filter: modalityFilter)
        }

        let models = matches.map { Self.entryDict($0, store: store) }
        var result: [String: Any] = ["models": models, "count": models.count, "query": query]
        if models.isEmpty {
            result["hint"] = Self.noModelsHint
        } else {
            result["usage"] = Self.usageHint
        }
        return result as NSDictionary
    }

    /// Hint string appended to non-empty list/search results so the agent knows
    /// exactly how to invoke a model. Three forms are supported by `run --model`:
    ///   1. Plain `model_id` (e.g. `claude-sonnet-4-6`) — works when unique.
    ///   2. `instance_label/model_id` (e.g. `deepseek/deepseek-v4-flash`) — required
    ///      when the same model_id is configured under multiple provider instances.
    ///   3. `--model <model_id> --provider <instance_label>` — equivalent to (2),
    ///      preferred when the model_id itself contains slashes.
    /// `entry_id` (UUID) also works but is opaque; prefer the human-readable forms.
    private static let usageHint =
        "To invoke a model, pass `--model <model_id>` to `minis-model-use run`. " +
        "If multiple providers expose the same `model_id`, disambiguate either with " +
        "`--model <instance_label>/<model_id>` (e.g. `--model deepseek/deepseek-v4-flash`) " +
        "or with `--model <model_id> --provider <instance_label>` " +
        "(e.g. `--model deepseek-v4-flash --provider deepseek`). " +
        "The opaque `entry_id` (UUID) is also accepted. " +
        // [T-model-use-image-passthrough GH#62] Progressive-disclosure breadcrumbs:
        // surface capability keywords up front so the model knows what's possible
        // and which model's per-entry `hint` to read for the exact param shape.
        "Capabilities by modality (run without --model, or inspect a model's `hint` field, " +
        "for the exact JSON shape): text generation, image generation, image-to-image, " +
        "image editing, audio (TTS/STT), embeddings. " +
        "Topics/params you can pass per call: messages, generation_config (size/quality/n/" +
        "aspect_ratio/image_size), image_endpoint, and — for OpenAI-compatible image models — " +
        "PASSTHROUGH via `extra_body`, `extra_headers`, `endpoint_path`, or any unknown " +
        "top-level field (forwarded verbatim; enables provider-specific params like Seedream " +
        "image-to-image). Read the target model's `hint` for concrete examples."

    // MARK: - Entry Serialization

    @MainActor
    private static func entryDict(_ entry: ModelEntry, store: ProviderConfigStore) -> [String: Any] {
        let instance = store.instance(for: entry.providerInstanceId)
        let caps = entry.model.capabilities
        let modality = caps.supportedModalities
        var supported: [String] = []
        if modality.contains(.textInput)   { supported.append("text_input") }
        if modality.contains(.textOutput)  { supported.append("text_output") }
        if modality.contains(.imageInput)  { supported.append("image_input") }
        if modality.contains(.pdfInput)    { supported.append("pdf_input") }
        if modality.contains(.audioInput)  { supported.append("audio_input") }
        if modality.contains(.videoInput)  { supported.append("video_input") }
        if modality.contains(.imageOutput) { supported.append("image_output") }
        if modality.contains(.audioOutput) { supported.append("audio_output") }
        if modality.contains(.videoOutput) { supported.append("video_output") }
        var dict: [String: Any] = [
            "entry_id": entry.id,
            "model_id": entry.model.id,
            "display_name": entry.model.displayName,
            "provider": entry.model.provider,
            "instance_label": instance?.label ?? "unknown",
            "provider_type": instance?.providerType.displayName ?? "unknown",
            "modalities": supported,
            "context_window": entry.model.contextWindowTokens,
        ]
        // Surface the configured + resolved image endpoint for image-output models
        // so the agent can see which path will be used (and which one already worked).
        if modality.contains(.imageOutput), let instance, instance.supportsImageEndpointSetting {
            dict["image_endpoint_mode"] = instance.imageEndpointMode.rawValue
            if let resolved = instance.imageEndpointResolved {
                dict["image_endpoint_resolved"] = resolved.rawValue
            }
        }
        return dict
    }

    // MARK: - Modality Filtering

    private static func filterByModality(_ entries: [ModelEntry], filter: String) -> [ModelEntry] {
        let required = parseModalityFilter(filter)
        guard !required.isEmpty else { return entries }
        return entries.filter { entry in
            let modality = entry.model.capabilities.supportedModalities
            return required.isSubset(of: modality)
        }
    }

    private static func parseModalityFilter(_ filter: String) -> ModelModality {
        var result = ModelModality()
        let terms = filter.lowercased().split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
        for term in terms {
            switch term {
            case "text":            result.insert(.textInput); result.insert(.textOutput)
            case "text_input":      result.insert(.textInput)
            case "text_output":     result.insert(.textOutput)
            case "image", "image_input", "vision":
                                    result.insert(.imageInput)
            case "image_output", "image_gen":
                                    result.insert(.imageOutput)
            case "pdf", "pdf_input":
                                    result.insert(.pdfInput)
            case "audio", "audio_input":
                                    result.insert(.audioInput)
            case "audio_output", "audio_gen", "tts":
                                    result.insert(.audioOutput)
            case "video", "video_input":
                                    result.insert(.videoInput)
            case "video_output", "video_gen":
                                    result.insert(.videoOutput)
            case "multimodal":      result = .fullMultimodal
            default: break
            }
        }
        return result
    }

    // MARK: - Transient-error retry

    /// [T-modeluse-transient-retry] Bounded exponential-backoff retry for
    /// `LLMError.transientError` — the error providers already throw for
    /// vendor-side temporary failures (Gemini marks 500/502/503/504/529 as
    /// transient; other providers use the same case). The CLI previously
    /// exited 1 on the FIRST 503 ("This model is currently experiencing high
    /// demand"), forcing the calling agent to re-run the whole command by
    /// hand — three manual re-runs over 21 minutes in the 8/17 device log.
    /// Nothing above the CLI retries either: shell_execute just reports the
    /// exit code, and the agent loop treats it as tool output, not an error.
    ///
    /// Only `.transientError` retries — a 400/`providerError` (bad input)
    /// still fails fast on the first attempt. `canRetry` lets the streaming
    /// path veto retries once output bytes have already been emitted to the
    /// caller's fd (a retry there would duplicate visible text; transient
    /// failures at that point surface immediately instead).
    ///
    /// Delays are 2s, 4s, 8s, 16s, 32s (maxRetries=5 → up to 6 attempts,
    /// ~62s of total waiting). Fixed defaults rather than a --max-retries
    /// flag: the CLI's contract is "behave like a well-configured client",
    /// and a knob would push the retry decision back onto the agent this
    /// exists to relieve.
    ///
    /// [T-modeluse-503-budget] The original budget (3 retries, 1s/2s/4s = 7s)
    /// was too small for the case that actually reaches users: an
    /// image-bearing request to gemini-3.7-flash. Reproduced OUTSIDE the app
    /// with plain curl against generativelanguage.googleapis.com — 3 of 5
    /// image requests answered 503 while 5 of 5 text-only requests in the same
    /// window answered 200, and one sequence needed a 4th attempt to succeed.
    /// So the congestion window for large multipart bodies routinely outlives
    /// 7s. Gemini's 503 carries no `Retry-After` (verified), so the schedule
    /// is a guess either way; starting at 2s instead of 1s stops the first
    /// retry from being spent while the vendor is provably still saturated.
    static func withTransientRetry<T>(
        label: String,
        maxRetries: Int = 5,
        initialDelaySeconds: Double = 2,
        canRetry: @escaping () -> Bool = { true },
        sleeper: (UInt64) async -> Void = { try? await Task.sleep(nanoseconds: $0) },
        operation: () async throws -> T
    ) async rethrows -> T {
        var attempt = 0
        while true {
            do {
                return try await operation()
            } catch let error as LLMError {
                guard case .transientError(let message) = error,
                      attempt < maxRetries,
                      canRetry() else { throw error }
                attempt += 1
                let delaySeconds = initialDelaySeconds * pow(2.0, Double(attempt - 1))
                logger.warning("[ModelUseRetry] \(label): transient provider error — retry \(attempt)/\(maxRetries) in \(Int(delaySeconds))s: \(message.prefix(200))")
                await sleeper(UInt64(delaySeconds * 1_000_000_000))
            }
        }
    }

    // MARK: - Run Model

    @objc public static func runModel(idOrName modelIdOrName: String,
                                       providerFilter: String?,
                                       inputJSON: String,
                                       systemPrompt: String?,
                                       maxTokens: Int,
                                       temperature: Double,
                                       outputHostPath: String?,
                                       streamFd: Int32,
                                       completion: @escaping ([String: Any]?, String?) -> Void) {
        Task {
            do {
                let result = try await performRun(
                    modelIdOrName: modelIdOrName,
                    providerFilter: providerFilter,
                    inputJSON: inputJSON,
                    systemPrompt: systemPrompt,
                    maxTokens: maxTokens,
                    temperature: temperature,
                    outputHostPath: outputHostPath,
                    streamFd: streamFd
                )
                completion(result, nil)
            } catch {
                logger.error("Model run failed: \(error)")
                let base = error.localizedDescription
                let hint = await Self.imageParamHint(forModelIdOrName: modelIdOrName)
                let combined = hint.isEmpty ? base : "\(base)\n\n\(hint)"
                completion(nil, combined)
            }
        }
    }

    /// If the target model is an image_output model, return a one-paragraph hint
    /// listing the params it actually accepts. Empty string for non-image models
    /// (or when resolution fails — we don't want to mask the original error).
    private static func imageParamHint(forModelIdOrName idOrName: String) async -> String {
        guard let entry = try? await resolveModelEntry(idOrName) else { return "" }
        guard entry.model.capabilities.supportedModalities.contains(.imageOutput) else {
            return ""
        }
        let providerType: ProviderType? = await MainActor.run {
            ProviderConfigStore.shared.instance(for: entry.providerInstanceId)?.providerType
        }
        switch providerType {
        case .gemini, .antigravity:
            return """
            Hint — \(entry.model.displayName) is a Gemini image model. Pass image params under \
            `generation_config` in the input JSON:
              aspect_ratio       "1:1" | "16:9" | "9:16" | "4:3" | "3:4"
              image_size         "512px" | "1K" | "2K" | "4K"
              number_of_images   1-4
              person_generation  "DONT_ALLOW" | "ALLOW_ADULT"
            Example:
              {"messages":[{"role":"user","content":"<prompt>"}],
               "generation_config":{"aspect_ratio":"16:9","image_size":"2K"}}
            """
        case .unsupported:
            return ""
        case .openAI, .openAIResponses, .openRouter, .xAI, .kimiCode:
            return """
            Hint — \(entry.model.displayName) is an OpenAI-compatible image model. Use the same \
            messages format as text models: put the prompt in the user message and image params \
            under `generation_config`:
              n         integer, number of images (default 1)
              size      "1024x1024" | "1792x1024" | "1024x1792" | etc.
              quality   "standard" | "hd"
            Example:
              {"messages":[{"role":"user","content":"<prompt>"}],
               "generation_config":{"size":"1792x1024","quality":"hd","n":1}}

            Passthrough (topics: image-to-image, extra_body, extra_headers, endpoint_path) — \
            any field our schema doesn't model is forwarded VERBATIM into the \
            /images/generations JSON body, so you can drive provider-specific params \
            (e.g. Volcengine Seedream image-to-image via an `image` field, `watermark`, \
            `tools`). Two ways, both optional & additive:
              • implicit: any unknown TOP-LEVEL key → request body
              • explicit: `extra_body` (object, merged into body), `extra_headers` \
                (string map, added to request headers), `endpoint_path` (string, \
                overrides "/images/generations" for non-standard endpoints)
            Your keys win over our defaults; `model` is always forced to the resolved id. \
            If you set `response_format`, the b64_json auto-probe is skipped.
            Image-to-image example (Seedream — image goes in the BODY, not messages):
              {"prompt":"make the eyes blue","image":"data:image/png;base64,<...>",
               "size":"2K","watermark":false}
            Explicit-envelope example:
              {"messages":[{"role":"user","content":"<prompt>"}],
               "extra_body":{"image":"<url-or-data-uri>","seed":42},
               "extra_headers":{"X-Custom":"1"},"endpoint_path":"/api/v3/images/generations"}
            """
        case .anthropic, .none:
            return ""
        }
    }

    // MARK: - Internal

    private static func performRun(modelIdOrName: String,
                                    providerFilter: String?,
                                    inputJSON: String,
                                    systemPrompt: String?,
                                    maxTokens: Int,
                                    temperature: Double,
                                    outputHostPath: String?,
                                    streamFd: Int32) async throws -> [String: Any] {
        // 1. Resolve model entry
        let entry = try await resolveModelEntry(modelIdOrName, providerFilter: providerFilter)
        let provider = try await LLMProviderFactory.makeProvider(for: entry)

        // [T-model-use-passthrough-mode] Explicit passthrough envelope — the
        // raw-mode escape hatch. Parsed before message validation because
        // body_mode=replace requests may carry no messages at all.
        let ptSpec = Self.parsePassthroughEnvelope(inputJSON)
        if ptSpec.active {
            guard let openAI = provider as? OpenAIProvider else {
                throw ModelUseError.invalidInput(
                    "passthrough mode is not supported for provider type "
                    + "'\(type(of: provider))' yet — only OpenAI-compatible "
                    + "providers. Use the standard OpenAI input format instead.")
            }
            return try await Self.performRawPassthrough(
                spec: ptSpec, entry: entry, openAI: openAI,
                inputJSON: inputJSON, systemPrompt: systemPrompt,
                maxTokens: maxTokens, outputHostPath: outputHostPath)
        }

        // 2. Parse OpenAI-format messages and optional generation config
        let messages = try parseOpenAIMessages(inputJSON)
        let generationConfig = Self.parseGenerationConfig(inputJSON)

        // [T-model-use-chat-passthrough GH#72] Standard mode: promote the
        // explicit `extra_body` / `extra_headers` envelope (previously image-
        // path-only) to ALL OpenAI-compatible endpoints. Explicit envelope
        // only — implicit unknown top-level keys stay image-path-only so the
        // chat schema's top level keeps its OpenAI semantics. A custom
        // endpoint address ("/..." via --endpoint / `endpoint` key) maps to
        // the absolute-path override (baseline p03: legacy endpoint_path
        // can't escape base prefixes like /compatible-mode/v1).
        // [T-model-use-passthrough-warnings] Standard-mode call feedback:
        // `callWarnings` collects every provided-but-ignored/downgraded field
        // (starting with envelope-lookalike warnings from the passthrough
        // parser); `appliedExtras` confirms which passthrough capabilities
        // actually took effect. Both are appended to the final result so an
        // AI caller can verify/self-correct without reading device logs.
        var callWarnings: [String] = ptSpec.warnings
        var appliedExtras: [String: Any] = [:]
        if let openAI = provider as? OpenAIProvider {
            if let data = inputJSON.data(using: .utf8),
               let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                if let extraRaw = parsed["extra_body"] {
                    if let extra = extraRaw as? [String: Any], !extra.isEmpty {
                        openAI.chatExtraBody = extra
                        appliedExtras["extra_body_keys"] = extra.keys.sorted()
                        logger.info("[ModelUseRoute] CHAT-PASSTHROUGH extra_body keys=[\(extra.keys.sorted().joined(separator: ","))]")
                    } else if !(extraRaw is [String: Any]) {
                        callWarnings.append("extra_body is not a JSON object (actual type: \(Self.jsonTypeName(extraRaw))) — ignored. Wrap your fields in an object: \"extra_body\":{...}.")
                    }
                }
                if let hdrsRaw = parsed["extra_headers"] {
                    if let hdrs = hdrsRaw as? [String: Any] {
                        var appliedHeaderKeys: [String] = []
                        for (k, v) in hdrs {
                            if let s = v as? String {
                                openAI.extraHeaders[k] = s
                                appliedHeaderKeys.append(k)
                            } else {
                                callWarnings.append("extra_headers.\(k) value is not a string (actual type: \(Self.jsonTypeName(v))) — this header was ignored. Header values must be JSON strings.")
                            }
                        }
                        if !appliedHeaderKeys.isEmpty {
                            appliedExtras["extra_headers_keys"] = appliedHeaderKeys.sorted()
                        }
                    } else {
                        callWarnings.append("extra_headers is not a JSON object (actual type: \(Self.jsonTypeName(hdrsRaw))) — all custom headers were ignored.")
                    }
                }
            }
            if let customPath = Self.parseCustomEndpointPath(inputJSON) {
                openAI.absoluteEndpointOverride = customPath
                appliedExtras["custom_endpoint"] = customPath
                // [T-log-noise-privacy 2026-07-18] Query stripped (can carry
                // ?key=... credentials on custom endpoints).
                logger.info("[ModelUseRoute] CUSTOM-ENDPOINT absolute path=\(customPath.split(separator: "?", maxSplits: 1).first.map(String.init) ?? customPath)")
            }
        } else if let data = inputJSON.data(using: .utf8),
                  let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            // [T-gemini37-flash-minimal-400 §2] extra_body / extra_headers only
            // exist on the OpenAI-compatible path above. They used to be dropped
            // SILENTLY for Gemini/Anthropic, so a caller passing
            // `extra_body.generationConfig.thinkingConfig` believed it took
            // effect. Say so, and point Gemini callers at the field that DOES
            // work (top-level snake_case `generation_config`, applied below via
            // parseGenerationConfig → GeminiProvider.extraGenerationConfig).
            if parsed["extra_body"] != nil {
                let hint = provider is GeminiProvider
                    ? " For Gemini, use the top-level \"generation_config\" field (snake_case) instead — it is merged into the request's generationConfig."
                    : ""
                callWarnings.append("extra_body is only applied to OpenAI-compatible providers; ignored for \(type(of: provider)).\(hint)")
            }
            if parsed["extra_headers"] != nil {
                callWarnings.append("extra_headers is only applied to OpenAI-compatible providers; ignored for \(type(of: provider)).")
            }
        }

        // Apply generation_config to providers that support it
        if let genCfg = generationConfig, let gemini = provider as? GeminiProvider {
            gemini.extraGenerationConfig = genCfg
        }

        // 3. Validate modality compatibility
        let modalities = entry.model.capabilities.supportedModalities
        let outputExt0 = outputHostPath.map { ($0 as NSString).pathExtension.lowercased() } ?? ""

        // Check if input contains images but model doesn't support image input
        let hasImageInput = messages.contains { !$0.images.isEmpty }
        if hasImageInput && !modalities.contains(.imageInput) {
            throw ModelUseError.modalityNotSupported(
                model: entry.model.displayName,
                required: "image_input",
                supported: Self.modalityList(modalities)
            )
        }

        // [GH#67] Audio input needs a model that declares audio_input —
        // explicit error instead of the historical silent drop.
        let hasAudioInput = messages.contains { !$0.audios.isEmpty }
        if hasAudioInput && !modalities.contains(.audioInput) {
            throw ModelUseError.modalityNotSupported(
                model: entry.model.displayName,
                required: "audio_input",
                supported: Self.modalityList(modalities)
            )
        }
        // [GH#67] input_audio serialization is implemented for the OpenAI
        // chat/completions + responses paths only. Other provider types would
        // drop the audio at their own serialization layer — fail loudly here
        // instead.
        if hasAudioInput && !(provider is OpenAIProvider) {
            throw ModelUseError.invalidInput(
                "input_audio is only supported for OpenAI-compatible providers "
                + "(chat/completions and responses paths) for now; provider type "
                + "'\(type(of: provider))' would silently drop the audio.")
        }

        // Check if --output requires a modality the model doesn't support
        if Self.isImageExtension(outputExt0) && !modalities.contains(.imageOutput) {
            throw ModelUseError.modalityNotSupported(
                model: entry.model.displayName,
                required: "image_output",
                supported: Self.modalityList(modalities)
            )
        }
        if Self.isAudioExtension(outputExt0) && !modalities.contains(.audioOutput) {
            throw ModelUseError.modalityNotSupported(
                model: entry.model.displayName,
                required: "audio_output",
                supported: Self.modalityList(modalities)
            )
        }
        if Self.isVideoExtension(outputExt0) && !modalities.contains(.videoOutput) {
            throw ModelUseError.modalityNotSupported(
                model: entry.model.displayName,
                required: "video_output",
                supported: Self.modalityList(modalities)
            )
        }

        // 4. Image-output models route through /v1/images/generations or /v1/chat/completions.
        //    The choice is controlled by the provider instance's `imageEndpointMode`:
        //      - .imagesGenerations / .chatCompletions: forced by user
        //      - .auto: try /images/generations first; on 4xx fall back to chat/completions
        //               and persist the working endpoint to `imageEndpointResolved` so the
        //               next request skips the probe.
        //    Endpoint override CLI flag (`--endpoint`) is parsed from inputJSON below and
        //    takes precedence over the persisted instance setting for this single call.
        // When the chat-completions branch ends up serving an image-output model
        // (forced, cached, or auto-fallback), we still want to surface which
        // endpoint was used in the result.
        var imageEndpointForResult: ImageEndpointMode?
        let wantsImageOutput = modalities.contains(.imageOutput)
        if wantsImageOutput, let openAI = provider as? OpenAIProvider {
            let instance: ProviderInstance? = await MainActor.run {
                ProviderConfigStore.shared.instance(for: entry.providerInstanceId)
            }

            let imgConfig = Self.parseImageGenerationConfig(inputJSON)
            let prompt = imgConfig.prompt
                ?? messages.last(where: { $0.role == .user })?.content ?? ""
            let inputImages = messages.flatMap { $0.images }

            // [T-model-use-image-passthrough GH#62] Pass arbitrary provider-specific
            // body fields, an endpoint-path override, and extra headers straight
            // through to the /images/generations request. Lets users drive models
            // our fixed schema never modeled (e.g. Volcengine Seedream image-to-image
            // via the `image` body field) without any persisted config — fully
            // per-call. Set on the provider so every image call site below picks
            // them up without threading new params through each signature.
            let passthrough = Self.parseImagePassthrough(inputJSON)
            openAI.imageExtraBody = passthrough.body
            openAI.imagePathOverride = passthrough.path
            for (k, v) in passthrough.headers { openAI.extraHeaders[k] = v }
            callWarnings.append(contentsOf: passthrough.warnings)
            if !passthrough.body.isEmpty || passthrough.path != nil || !passthrough.headers.isEmpty {
                logger.info("[ModelUseRoute] PASSTHROUGH bodyKeys=[\(passthrough.body.keys.sorted().joined(separator: ","))] pathOverride=\(passthrough.path ?? "<nil>") extraHeaderKeys=[\(passthrough.headers.keys.sorted().joined(separator: ","))]")
            }

            // [ModelUseRoute] Up-front dump of every input the image-routing
            // decision will read, so we can grep one line and see exactly which
            // branch SHOULD fire. Each branch below logs `route=<name>` so the
            // pair (decision-inputs + chosen branch) is self-contained in logs.
            // Keyword for grep: `[ModelUseRoute]`.
            let modalityList = Self.modalityList(modalities)
            let endpointModeStr = instance?.imageEndpointMode.rawValue ?? "<no-instance>"
            let endpointResolvedStr = instance?.imageEndpointResolved?.rawValue ?? "<nil>"
            let cliEndpointPreview = Self.parseEndpointOverride(inputJSON)?.rawValue ?? "<none>"
            logger.info("[ModelUseRoute] DECISION-INPUTS model=\(entry.model.id) modalities=[\(modalityList.joined(separator: ","))] instance=\(instance?.label ?? "<nil>") instanceCred=\(instance?.credentialType.rawValue ?? "<nil>") image_endpoint_mode=\(endpointModeStr) image_endpoint_resolved=\(endpointResolvedStr) cliEndpointOverride=\(cliEndpointPreview) providerIsOAuth=\(openAI.isOAuth) codexAccountIdPresent=\(openAI.codexAccountId != nil) customBaseURL=\(openAI.customBaseURL ?? "<nil>") forceResponsesAPI=\(openAI.forceResponsesAPI)")

            // Codex OAuth image generation — STRICTLY scoped to the gpt-image-2
            // model so this is a pure additive branch with zero regression on
            // normal OpenAI OAuth chat/responses flows (those models lack
            // .imageOutput and never reach this block anyway; the explicit
            // model-id gate makes the intent unambiguous). Generates via the
            // image_generation tool on chatgpt.com/backend-api/codex/responses
            // (NOT /v1/images/generations: a Codex OAuth token lacks the
            // api.model.images.request scope on the public Images API, so that
            // path 401s — see codex_oauth_image_generation_summary.md §11.1).
            // Runs BEFORE the endpoint-mode probe so an OAuth instance never
            // even considers the /images path. [T-gpt-image2-codex-backend-route]
            //
            // The earlier `codexAccountId != nil` gate was too strict — some
            // OAuth tokens are valid Codex tokens but ship without the
            // `chatgpt_account_id` claim, which would silently drop the request
            // back to the (wrong) Images API path. Codex backend tolerates a
            // missing Chatgpt-Account-Id header, so any OAuth instance is
            // routed to codex for this model.
            let isCodexImage = entry.model.id == LLMModel.gptImage2.id
                && openAI.isOAuth
                && openAI.customBaseURL == nil
                && !openAI.forceResponsesAPI
            if isCodexImage {
                let topModel = await Self.resolveCodexTopLevelModel(
                    forInstance: entry.providerInstanceId, inputJSON: inputJSON)
                // size/quality/n aren't supported by the codex image_generation
                // shape (auto only) — log that we're ignoring them.
                if imgConfig.size != nil || imgConfig.quality != nil || imgConfig.n != 1 {
                    logger.info("🖼️ codex image_generation ignores size/quality/n (backend is auto-only): size=\(imgConfig.size ?? "nil") quality=\(imgConfig.quality ?? "nil") n=\(imgConfig.n)")
                }
                logger.info("🖼️ image_endpoint=codex_responses model=\(entry.model.id) topLevel=\(topModel ?? "nil") inputImages=\(inputImages.count)")
                logger.info("[ModelUseRoute] route=codex-oauth-image method=POST url=https://chatgpt.com/backend-api/codex/responses model=\(entry.model.id) topLevelModel=\(topModel ?? "<nil>")")
                let response = try await openAI.generateImageViaCodexResponses(
                    prompt: prompt,
                    inputImages: inputImages,
                    topLevelModel: topModel,
                    size: imgConfig.size,
                    quality: imgConfig.quality
                )
                return Self.attachCallFeedback(try Self.buildMediaResult(
                    response: response, entry: entry,
                    outputHostPath: outputHostPath, outputExt: outputExt0,
                    imageEndpointUsed: nil
                ), warnings: callWarnings, extras: appliedExtras)
            }

            // A pure image generator (image_output but NO text_output, e.g.
            // gpt-image-2) can ONLY use the Images API — routing it to
            // /v1/chat/completions fails with 401 "Missing scopes:
            // api.model.images.request" because that endpoint can't serve image
            // generation. So for such models force /v1/images/generations and
            // never fall back to chat: a stale cached `.chatCompletions`
            // resolution (or an auto-probe failure) must NOT pin them to the
            // chat endpoint. Mixed text+image models keep the auto/probe/fallback
            // behavior below. [T-gpt-image2-route-images-endpoint]
            // Pure image generators on API-KEY instances (i.e. not OpenAI OAuth)
            // are forced to /v1/images/generations — the standard Images API
            // (gpt-image-1 etc.). OAuth instances must NEVER fall into this
            // path: a Codex OAuth token lacks the api.model.images.request
            // scope and would 401, and the gpt-image-2 case was already routed
            // through the codex backend above by `isCodexImage`. Excluding
            // OAuth here makes the two branches strictly disjoint.
            let isPureImageGenerator = modalities.contains(.imageOutput)
                && !modalities.contains(.textOutput)
                && !openAI.isOAuth

            let cliEndpointOverride = Self.parseEndpointOverride(inputJSON)
            let effectiveMode: ImageEndpointMode = {
                if let cli = cliEndpointOverride { return cli }
                if isPureImageGenerator { return .imagesGenerations }
                guard let instance else { return .auto }
                if instance.imageEndpointMode == .auto, let resolved = instance.imageEndpointResolved {
                    return resolved
                }
                return instance.imageEndpointMode
            }()
            logger.info("[ModelUseRoute] EFFECTIVE-MODE model=\(entry.model.id) isPureImageGenerator=\(isPureImageGenerator) (imageOutput=\(modalities.contains(.imageOutput)) textOutput=\(modalities.contains(.textOutput))) cliOverride=\(cliEndpointOverride?.rawValue ?? "<nil>") effectiveMode=\(effectiveMode.rawValue)")

            // Forced or already-resolved → use that endpoint directly.
            if effectiveMode == .imagesGenerations {
                logger.info("🖼️ image_endpoint=images_generations (forced/cached) for \(entry.model.id)")
                logger.info("[ModelUseRoute] route=images-api method=POST url=\(openAI.customBaseURL ?? "https://api.openai.com")/v1/images/\(inputImages.isEmpty ? "generations" : "edits") model=\(entry.model.id) inputImages=\(inputImages.count)")
                let response: LLMResponse
                if inputImages.isEmpty {
                    response = try await openAI.generateImage(
                        prompt: prompt, n: imgConfig.n,
                        size: imgConfig.size, quality: imgConfig.quality
                    )
                } else {
                    response = try await openAI.editImage(
                        prompt: prompt, images: inputImages, n: imgConfig.n,
                        size: imgConfig.size, quality: imgConfig.quality
                    )
                }
                return Self.attachCallFeedback(try Self.buildMediaResult(
                    response: response, entry: entry,
                    outputHostPath: outputHostPath, outputExt: outputExt0,
                    imageEndpointUsed: .imagesGenerations
                ), warnings: callWarnings, extras: appliedExtras)
            }

            // Auto mode without prior resolution: probe /images/generations, fall back on 4xx.
            if effectiveMode == .auto, let instance {
                logger.info("🖼️ image_endpoint=auto, probing /v1/images/generations for \(entry.model.id)")
                logger.info("[ModelUseRoute] route=images-api-auto-probe method=POST url=\(openAI.customBaseURL ?? "https://api.openai.com")/v1/images/\(inputImages.isEmpty ? "generations" : "edits") model=\(entry.model.id) inputImages=\(inputImages.count)")
                do {
                    let response: LLMResponse
                    if inputImages.isEmpty {
                        response = try await openAI.generateImage(
                            prompt: prompt, n: imgConfig.n,
                            size: imgConfig.size, quality: imgConfig.quality
                        )
                    } else {
                        response = try await openAI.editImage(
                            prompt: prompt, images: inputImages, n: imgConfig.n,
                            size: imgConfig.size, quality: imgConfig.quality
                        )
                    }
                    await MainActor.run {
                        ProviderConfigStore.shared.setImageEndpointResolved(
                            instanceId: instance.id, endpoint: .imagesGenerations)
                    }
                    return try Self.buildMediaResult(
                        response: response, entry: entry,
                        outputHostPath: outputHostPath, outputExt: outputExt0,
                        imageEndpointUsed: .imagesGenerations
                    )
                } catch let LLMError.providerError(message) where Self.looksLikeEndpointMissing(message) {
                    logger.info("🖼️ /images/generations rejected (\(message.prefix(120))) — falling back to /chat/completions")
                    await MainActor.run {
                        ProviderConfigStore.shared.setImageEndpointResolved(
                            instanceId: instance.id, endpoint: .chatCompletions)
                    }
                    // fall through to chat-completions branch below
                } catch let LLMError.invalidAPIKey(detail) where Self.looksLikeEndpointMissing(detail) {
                    logger.info("🖼️ /images/generations rejected as 401-with-route-error — falling back to /chat/completions")
                    await MainActor.run {
                        ProviderConfigStore.shared.setImageEndpointResolved(
                            instanceId: instance.id, endpoint: .chatCompletions)
                    }
                }
                // Any other error (real auth failure, network, etc.) propagates from the do-block above.
            }

            // .chatCompletions branch (forced, cached, or auto-fallback): fall through to step 5.
            logger.info("🖼️ image_endpoint=chat_completions for \(entry.model.id) — using standard chat path")
            logger.info("[ModelUseRoute] route=chat-completions-for-image-output method=POST url=\(openAI.customBaseURL ?? "https://api.openai.com")/v1/chat/completions model=\(entry.model.id) — falls through to streamMessage path (THIS IS WHERE Missing scopes:api.model.images.request COMES FROM if it fires for a pure image generator)")
            imageEndpointForResult = .chatCompletions
        }

        // 5. Send request via standard chat completions / streaming
        // [ModelUseRoute] If we land here without an image route having fired,
        // this is the catch-all standard chat path. For image-output models that
        // means none of the image branches matched (e.g. wantsImageOutput=false
        // because modalities lack imageOutput, or the provider isn't OpenAIProvider).
        // Pair with the DECISION-INPUTS log above to see which condition failed.
        if let openAI = provider as? OpenAIProvider {
            logger.info("[ModelUseRoute] route=standard-streamMessage method=POST url=\(openAI.customBaseURL ?? "https://api.openai.com")/v1/chat/completions model=\(entry.model.id) wantsImageOutput=\(modalities.contains(.imageOutput)) isStreaming=\(streamFd >= 0)")
        } else {
            logger.info("[ModelUseRoute] route=standard-streamMessage providerKind=\(type(of: provider)) model=\(entry.model.id) wantsImageOutput=\(modalities.contains(.imageOutput))")
        }
        let isStreaming = streamFd >= 0

        if isStreaming {
            var fullText = ""
            // [T-modeluse-transient-retry] Retry covers stream OPEN and any
            // failure before the first emitted byte (the Gemini 503 fires at
            // request time, before any chunk). Once text has reached the
            // caller's fd a retry would duplicate it — canRetry vetoes.
            var emittedOutput = false
            try await withTransientRetry(label: "run/stream \(entry.model.id)",
                                         canRetry: { !emittedOutput }) {
                // [T-modeluse-503-budget] Reset the accumulator per attempt.
                // A retry is only permitted while `emittedOutput == false`, so
                // nothing has reached the caller's fd — but a partial `.text`
                // chunk could still have landed in `fullText` before the
                // failure (it is appended one statement before the flag is
                // set). Without this reset that fragment would be prepended to
                // the successful attempt's text and written to --output.
                fullText = ""
                let stream = try await provider.streamMessage(
                    messages: messages,
                    systemPrompt: systemPrompt,
                    maxTokens: maxTokens,
                    temperature: temperature >= 0 ? temperature : nil
                )
                for try await chunk in stream {
                    switch chunk {
                    case .text(let t):
                        fullText += t
                        emittedOutput = true
                        let data = Data(t.utf8)
                        data.withUnsafeBytes { buf in
                            _ = write(streamFd, buf.baseAddress!, buf.count)
                        }
                    case .finished:
                        // Write newline at end of stream
                        _ = write(streamFd, "\n", 1)
                    default:
                        break
                    }
                }
            }

            // Write output file if specified
            if let outputHostPath {
                try writeOutputFile(text: fullText, path: outputHostPath)
            }

            return Self.attachCallFeedback([
                "model_id": entry.model.id,
                "model_name": entry.model.displayName,
                "output_text": fullText,
                "streamed": true,
                "output_file": outputHostPath.map { Self.toLinuxPath($0) } ?? NSNull(),
            ], warnings: callWarnings, extras: appliedExtras)
        } else {
            // [T-modeluse-transient-retry] Non-streaming: nothing has been
            // surfaced to the caller until sendMessage returns, so the whole
            // request is always safe to retry.
            let response = try await withTransientRetry(label: "run/send \(entry.model.id)") {
                try await provider.sendMessage(
                    messages: messages,
                    systemPrompt: systemPrompt,
                    maxTokens: maxTokens,
                    temperature: temperature >= 0 ? temperature : nil
                )
            }

            // Determine output format from --output extension
            let outputExt = outputHostPath.map { ($0 as NSString).pathExtension.lowercased() } ?? ""
            let outputIsStructured = ["json", "yaml", "yml"].contains(outputExt)
            let outputIsMedia = Self.isMediaExtension(outputExt)

            // Save media attachments to files.
            // Write directly to the session-specific persistent directory
            // instead of through the /var/minis/attachments symlink, which
            // is racy under concurrent sessions (symlink target can change
            // mid-write when another session's view appears).
            var mediaFiles: [[String: Any]] = []
            let callerSid = ISHExecutionCoordinator.mountedSessionIdSnapshot ?? ""
            let attachDir: String
            if !callerSid.isEmpty {
                attachDir = AIChatViewModel.minisAttachmentsPersistentDir(for: callerSid).path
            } else {
                attachDir = RootfsManager.shared.dataPath
                    .appendingPathComponent("var/minis/attachments").path
            }
            let modelSlug = entry.model.id.replacingOccurrences(of: "/", with: "_")
            let sessionShort = callerSid
                .replacingOccurrences(of: "-", with: "")
                .prefix(8)
            let sessionTag = sessionShort.isEmpty ? "" : "\(sessionShort)-"
            let ts = Int(Date().timeIntervalSince1970)

            for (idx, attachment) in response.mediaAttachments.enumerated() {
                let nativeExt = Self.fileExtension(for: attachment.mimeType)

                let filePath: String
                if idx == 0, let outputHostPath, response.text.isEmpty, outputIsMedia {
                    // First attachment + media output path → write to --output with format conversion
                    let dir = (outputHostPath as NSString).deletingLastPathComponent
                    try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
                    filePath = outputHostPath
                } else if let outputHostPath, !outputIsStructured {
                    // Save alongside --output file (non-structured output only).
                    let dir = (outputHostPath as NSString).deletingLastPathComponent
                    try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
                    filePath = (dir as NSString).appendingPathComponent("model-use-\(sessionTag)\(modelSlug)-\(ts)-\(idx).\(nativeExt)")
                } else {
                    // No --output, or a STRUCTURED (.json/.yaml) --output whose
                    // media belongs in attachments and is REFERENCED by the
                    // structured file → /var/minis/attachments/.
                    // [T-model-use-media-path-mismatch] Aligns this save site
                    // with buildMediaResult (which already guarded structured
                    // output): previously a structured --output in workspace
                    // dropped the image into workspace while the response path
                    // claimed attachments/.
                    try? FileManager.default.createDirectory(atPath: attachDir, withIntermediateDirectories: true)
                    filePath = (attachDir as NSString).appendingPathComponent("model-use-\(sessionTag)\(modelSlug)-\(ts)-\(idx).\(nativeExt)")
                }

                // Convert media format if target extension differs from source
                let targetExt = (filePath as NSString).pathExtension.lowercased()
                let outputData = Self.convertMediaIfNeeded(
                    data: attachment.data,
                    sourceMime: attachment.mimeType,
                    targetExtension: targetExt
                )

                try outputData.write(to: URL(fileURLWithPath: filePath))
                // [T-model-use-media-path-mismatch] Announce the path the file
                // was ACTUALLY written to (attachments / workspace / --output
                // dir), not a hardcoded attachments/ string.
                let linuxPath = Self.guestLogicalPath(forSavedFile: filePath, callerSid: callerSid)
                mediaFiles.append([
                    "type": attachment.type.rawValue,
                    "mime_type": Self.mimeType(for: targetExt) ?? attachment.mimeType,
                    "path": linuxPath,
                    "size": outputData.count,
                ])
                logger.info("Saved \(attachment.type.rawValue) attachment (\(outputData.count) bytes) → \(filePath)")
            }

            // Write --output file based on extension
            if let outputHostPath {
                if outputIsStructured {
                    // JSON/YAML: structured output with text + media file references
                    try writeStructuredOutput(
                        text: response.text, mediaFiles: mediaFiles,
                        model: entry.model, usage: response.usage,
                        path: outputHostPath, format: outputExt
                    )
                } else if !outputIsMedia || mediaFiles.isEmpty {
                    // Text extension or no media → write text content
                    if !response.text.isEmpty {
                        try writeOutputFile(text: response.text, path: outputHostPath)
                    }
                }
                // Media extension with attachments: already written above
            }

            var result: [String: Any] = [
                "model_id": entry.model.id,
                "model_name": entry.model.displayName,
                "output_text": response.text,
                "stop_reason": response.stopReason ?? "end_turn",
                "streamed": false,
                "output_file": outputHostPath.map { Self.toLinuxPath($0) } ?? NSNull(),
            ]
            if !mediaFiles.isEmpty {
                result["media_files"] = mediaFiles
            }
            if let usage = response.usage {
                result["usage"] = [
                    "input_tokens": usage.inputTokens,
                    "output_tokens": usage.outputTokens,
                ]
            }
            if let imageEndpointForResult {
                result["image_endpoint"] = imageEndpointForResult.rawValue
            }
            return Self.attachCallFeedback(result, warnings: callWarnings, extras: appliedExtras)
        }
    }

    // MARK: - Model Resolution

    @MainActor
    private static func resolveModelEntry(_ idOrName: String, providerFilter: String? = nil) throws -> ModelEntry {
        let store = ProviderConfigStore.shared
        let q = idOrName.lowercased()

        // Only resolve from agent loop models
        var agentEntries = store.resolvedAgentLoopEntries
            .filter { store.instance(for: $0.providerInstanceId)?.isEnabled == true }

        // Optional --provider filter: match against instance label (case-insensitive)
        // OR instance UUID. Applied before the lookup chain so the same model_id
        // under multiple providers can be disambiguated explicitly.
        if let providerFilter, !providerFilter.isEmpty {
            let pf = providerFilter.lowercased()
            let filtered = agentEntries.filter { entry in
                guard let inst = store.instance(for: entry.providerInstanceId) else { return false }
                return inst.label.lowercased() == pf || inst.id.lowercased() == pf
            }
            if filtered.isEmpty {
                throw ModelUseError.modelNotFound("No provider matches --provider '\(providerFilter)'. Use 'minis-model-use list' to see provider labels.")
            }
            agentEntries = filtered
        }

        // 0. Qualified form `<instance_label>/<model_id>` — disambiguates when
        //    the same model_id exists under multiple provider instances.
        //    Match is case-insensitive on both halves; model_id may itself contain
        //    '/' (e.g. "deepseek-ai/DeepSeek-V4-Flash"), so split on the FIRST '/' only.
        if let slashIdx = idOrName.firstIndex(of: "/") {
            let labelPart = idOrName[..<slashIdx].lowercased()
            let modelPart = idOrName[idOrName.index(after: slashIdx)...].lowercased()
            if !labelPart.isEmpty && !modelPart.isEmpty {
                if let entry = agentEntries.first(where: { entry in
                    guard let inst = store.instance(for: entry.providerInstanceId) else { return false }
                    return inst.label.lowercased() == labelPart
                        && entry.model.id.lowercased() == modelPart
                }) {
                    return entry
                }
            }
        }

        // 1. Exact match by model ID
        if let entry = agentEntries.first(where: { $0.model.id.lowercased() == q }) {
            return entry
        }

        // 2. Exact match by display name
        if let entry = agentEntries.first(where: { $0.model.displayName.lowercased() == q }) {
            return entry
        }

        // 3. Prefix match by model ID
        if let entry = agentEntries.first(where: { $0.model.id.lowercased().hasPrefix(q) }) {
            return entry
        }

        // 4. Contains match by display name
        if let entry = agentEntries.first(where: { $0.model.displayName.lowercased().contains(q) }) {
            return entry
        }

        // 5. Try entry ID directly (must be in agent loop list)
        let allAgentEntryIds = Set(agentEntries.map(\.id))
        if allAgentEntryIds.contains(idOrName),
           let entry = store.entry(for: idOrName),
           store.instance(for: entry.providerInstanceId)?.isEnabled == true {
            return entry
        }

        if agentEntries.isEmpty {
            throw ModelUseError.modelNotFound("\(idOrName) — \(Self.noModelsHint)")
        }
        throw ModelUseError.modelNotFound(idOrName)
    }

    // MARK: - OpenAI Message Parsing

    private static func parseOpenAIMessages(_ json: String) throws -> [LLMMessage] {
        guard let data = json.data(using: .utf8) else {
            throw ModelUseError.invalidInput("Failed to decode input as UTF-8")
        }

        let parsed = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard let parsed else {
            throw ModelUseError.invalidInput("Input must be a JSON object with a 'messages' array")
        }

        guard let rawMessages = parsed["messages"] as? [[String: Any]] else {
            // [T-model-use-image-gen-top-level-prompt] The HELP_TEXT documents
            // a top-level image-generation shape with no messages array:
            //   {"prompt":"...","size":"1024x1024","quality":"hd","n":1}
            // (matches OpenAI /v1/images/generations). Previously this threw
            // "Missing 'messages' array" before the image-gen branch ever
            // ran, so the documented format was rejected and callers had to
            // fall back to --prompt or --endpoint images-gen. Tolerate it:
            // if there's a top-level `prompt` string, synthesize a single
            // user message. Image-gen reads imgConfig.prompt (top-level) and
            // ignores the messages; text models get a usable user turn.
            if let topPrompt = parsed["prompt"] as? String, !topPrompt.isEmpty {
                return [LLMMessage(role: .user, content: topPrompt)]
            }
            throw ModelUseError.invalidInput("Missing 'messages' array in input JSON. For image generation, pass a top-level \"prompt\" (e.g. {\"prompt\":\"...\",\"size\":\"1024x1024\"}); for chat, pass a \"messages\" array.")
        }

        var messages: [LLMMessage] = []
        for msg in rawMessages {
            guard let roleStr = msg["role"] as? String else { continue }

            // Skip tool-related messages
            if roleStr == "tool" || roleStr == "function" { continue }

            let role: LLMMessage.Role
            switch roleStr {
            case "user": role = .user
            case "assistant": role = .assistant
            case "system": continue // system handled separately via --system
            default: continue
            }

            // Extract content - handle both string and array forms
            var content = ""
            var images: [LLMMessage.ImageAttachment] = []
            var audios: [LLMMessage.AudioAttachment] = []

            if let text = msg["content"] as? String {
                content = text
            } else if let parts = msg["content"] as? [[String: Any]] {
                for part in parts {
                    let partType = part["type"] as? String ?? ""
                    if partType == "text", let t = part["text"] as? String {
                        if !content.isEmpty { content += "\n" }
                        content += t
                    } else if partType == "image_url",
                              let imgObj = part["image_url"] as? [String: Any],
                              let url = imgObj["url"] as? String {
                        // [T-model-use-image-url-resolution] resolveImageURL
                        // now throws on unresolvable URLs (minis:// to a
                        // missing file, file:// to nonexistent path, http(s)
                        // not yet supported, unknown scheme). Previously it
                        // returned nil silently, so the agent's call would
                        // succeed with the image dropped and the model would
                        // hallucinate ("unknown color"). Surface the failure
                        // as a hard input error instead.
                        let img = try Self.resolveImageURL(url)
                        images.append(img)
                    } else if partType == "input_audio" {
                        // [GH#67] input_audio was previously skipped with no
                        // trace — the request went out text-only and the model
                        // answered without ever seeing the audio. Parse the
                        // official OpenAI shape; malformed blocks are a hard
                        // input error, not a silent drop.
                        guard let audioObj = part["input_audio"] as? [String: Any],
                              let b64 = audioObj["data"] as? String, !b64.isEmpty else {
                            throw ModelUseError.invalidInput(
                                "input_audio block is malformed. Expected {\"type\":\"input_audio\",\"input_audio\":{\"data\":\"<base64>\",\"format\":\"wav\"}}.")
                        }
                        guard Data(base64Encoded: b64, options: [.ignoreUnknownCharacters]) != nil else {
                            throw ModelUseError.invalidInput(
                                "input_audio.data is not valid base64.")
                        }
                        let format = (audioObj["format"] as? String)?.lowercased() ?? "wav"
                        audios.append(LLMMessage.AudioAttachment(format: format, base64Data: b64))
                    }
                }
            } else {
                continue
            }

            guard !content.isEmpty || !images.isEmpty || !audios.isEmpty else { continue }
            var message = LLMMessage(role: role, content: content)
            message.images = images
            message.audios = audios
            messages.append(message)
        }

        guard !messages.isEmpty else {
            throw ModelUseError.invalidInput("No valid user/assistant messages found in input")
        }

        return messages
    }

    // MARK: - Generation Config Passthrough

    /// Parse optional `generation_config` from the input JSON.
    /// Allows callers to pass provider-specific generation parameters.
    ///
    /// Image config keys (mapped to Gemini `generationConfig.imageConfig`):
    ///   - `aspect_ratio`: "1:1", "16:9", "9:16", "4:3", "3:4", etc.
    ///   - `image_size`: "512px", "1K", "2K", "4K"
    ///   - `number_of_images`: 1–4
    ///   - `person_generation`: "DONT_ALLOW", "ALLOW_ADULT"
    ///
    /// Any other keys are passed through verbatim at the generationConfig level.
    /// Unsupported providers silently ignore the entire config.
    private static func parseGenerationConfig(_ json: String) -> [String: Any]? {
        guard let data = json.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let config = parsed["generation_config"] as? [String: Any],
              !config.isEmpty else {
            return nil
        }

        // Keys that belong in generationConfig.imageConfig
        let imageConfigKeys: Set<String> = [
            "aspect_ratio", "aspectRatio",
            "image_size", "imageSize",
            "number_of_images", "numberOfImages",
            "person_generation", "personGeneration",
        ]

        var imageConfig: [String: Any] = [:]
        var topLevel: [String: Any] = [:]

        for (key, value) in config {
            if imageConfigKeys.contains(key) {
                // Map snake_case → camelCase for Gemini API
                let mapped: String
                switch key {
                case "aspect_ratio": mapped = "aspectRatio"
                case "image_size": mapped = "imageSize"
                case "number_of_images": mapped = "numberOfImages"
                case "person_generation": mapped = "personGeneration"
                default: mapped = key
                }
                imageConfig[mapped] = value
            } else {
                topLevel[key] = value
            }
        }

        if !imageConfig.isEmpty {
            topLevel["imageConfig"] = imageConfig
        }

        return topLevel.isEmpty ? nil : topLevel
    }

    // MARK: - Image Generation Config (for /images/generations)

    private struct ImageGenConfig {
        var prompt: String?
        var n: Int = 1
        var size: String?
        var quality: String?
    }

    /// Parse image generation parameters for the /images/generations endpoint.
    /// Reads from both top-level input JSON fields and nested `generation_config`.
    /// Top-level fields take precedence (closer to /images/generations native format).
    ///
    /// Supported input formats:
    /// ```json
    /// // Direct /images/generations format
    /// { "prompt": "...", "n": 1, "size": "1024x1024", "quality": "hd" }
    /// // OpenAI chat format with generation_config
    /// { "messages": [...], "generation_config": { "n": 2, "size": "1024x1024" } }
    /// ```
    private static func parseImageGenerationConfig(_ json: String) -> ImageGenConfig {
        var config = ImageGenConfig()
        guard let data = json.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return config
        }

        // Read from generation_config first (lower priority)
        if let genCfg = parsed["generation_config"] as? [String: Any] {
            if let n = genCfg["n"] as? Int ?? genCfg["number_of_images"] as? Int {
                config.n = max(1, n)
            }
            if let size = genCfg["size"] as? String ?? genCfg["image_size"] as? String {
                config.size = size
            }
            if let quality = genCfg["quality"] as? String {
                config.quality = quality
            }
        }

        // Top-level fields override (native /images/generations format)
        if let prompt = parsed["prompt"] as? String, !prompt.isEmpty {
            config.prompt = prompt
        }
        if let n = parsed["n"] as? Int {
            config.n = max(1, n)
        }
        if let size = parsed["size"] as? String {
            config.size = size
        }
        if let quality = parsed["quality"] as? String {
            config.quality = quality
        }
        return config
    }

    /// [T-model-use-image-passthrough GH#62] Provider-specific extras forwarded
    /// verbatim to the /images/generations request.
    private struct ImagePassthrough {
        var body: [String: Any] = [:]
        var headers: [String: String] = [:]
        var path: String? = nil
        /// [T-model-use-passthrough-warnings] Type-mismatch feedback.
        var warnings: [String] = []
    }

    /// Keys the bridge already interprets itself — never treated as implicit
    /// passthrough body fields, so existing callers' structure/behavior is
    /// unchanged. (Explicit `extra_body` can still re-introduce any of these.)
    private static let imageReservedKeys: Set<String> = [
        "messages", "model", "chat_model", "prompt", "n", "number_of_images",
        "size", "image_size", "quality", "generation_config", "endpoint",
        "image_endpoint", "endpoint_path", "extra_body", "extra_headers",
        "stream", "temperature", "max_tokens",
    ]

    /// Parse passthrough extras from the input JSON. Two complementary modes,
    /// both fully optional and additive (absent → empty, old calls unaffected):
    ///   • Explicit envelope: `extra_body` (object), `extra_headers` (string map),
    ///     `endpoint_path` (string). Unambiguous; recommended in help.
    ///   • Implicit: any TOP-LEVEL key not in `imageReservedKeys` is folded into
    ///     the body — so `{"prompt":..,"image":"data:..","watermark":false}` just
    ///     works for Seedream-style image-to-image without an envelope.
    /// Explicit `extra_body` wins over implicit keys on conflict.
    private static func parseImagePassthrough(_ json: String) -> ImagePassthrough {
        var result = ImagePassthrough()
        guard let data = json.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return result }

        // Implicit: unknown top-level keys → body.
        for (k, v) in parsed where !imageReservedKeys.contains(k) {
            result.body[k] = v
        }
        // Explicit envelope overrides/augments.
        if let extraRaw = parsed["extra_body"] {
            if let extra = extraRaw as? [String: Any] {
                for (k, v) in extra { result.body[k] = v }
            } else {
                result.warnings.append("extra_body is not a JSON object (actual type: \(Self.jsonTypeName(extraRaw))) — ignored. Wrap your fields in an object: \"extra_body\":{...}.")
            }
        }
        if let hdrsRaw = parsed["extra_headers"] {
            if let hdrs = hdrsRaw as? [String: Any] {
                for (k, v) in hdrs {
                    if let s = v as? String {
                        result.headers[k] = s
                    } else {
                        result.warnings.append("extra_headers.\(k) value is not a string (actual type: \(Self.jsonTypeName(v))) — this header was ignored. Header values must be JSON strings.")
                    }
                }
            } else {
                result.warnings.append("extra_headers is not a JSON object (actual type: \(Self.jsonTypeName(hdrsRaw))) — all custom headers were ignored.")
            }
        }
        if let p = (parsed["endpoint_path"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
           !p.isEmpty {
            result.path = p
        }
        return result
    }

    // MARK: - Call Feedback [T-model-use-passthrough-warnings]

    /// Append the collected `warnings` and `applied_extras` to a result dict.
    /// Both are omitted when empty (no noise on clean calls). Warnings are
    /// de-duplicated (the same type-mismatch can be detected by more than one
    /// parser on the image path).
    private static func attachCallFeedback(
        _ result: [String: Any],
        warnings: [String],
        extras: [String: Any]
    ) -> [String: Any] {
        var r = result
        if !warnings.isEmpty {
            var merged = r["warnings"] as? [String] ?? []
            var seen = Set(merged)
            for w in warnings where !seen.contains(w) {
                merged.append(w); seen.insert(w)
            }
            r["warnings"] = merged
        }
        if !extras.isEmpty, r["applied_extras"] == nil {
            r["applied_extras"] = extras
        }
        return r
    }

    // MARK: - Passthrough Mode [T-model-use-passthrough-mode]

    /// Explicit passthrough envelope: verbatim body/headers/endpoint, raw
    /// (unparsed) response output. Activated by a top-level `passthrough`
    /// object in the input JSON.
    private struct PassthroughSpec {
        var endpoint: String? = nil          // "/abs/path?q" (absolute) or "rel/segment"
        var method: String = "POST"
        var headers: [String: String] = [:]
        var body: [String: Any] = [:]
        var bodyMode: String = "merge"       // "merge" | "replace"
        var active: Bool = false
        /// [T-model-use-passthrough-warnings] Fields the caller provided but
        /// we ignored/downgraded — surfaced in the result's `warnings` array
        /// so an AI caller can self-correct on the next attempt.
        var warnings: [String] = []
    }

    /// Human-readable JSON type name for warning messages.
    private static func jsonTypeName(_ v: Any) -> String {
        switch v {
        case is String: return "string"
        case is NSNumber: return (v as? NSNumber).map { CFGetTypeID($0) == CFBooleanGetTypeID() ? "bool" : "number" } ?? "number"
        case is [Any]: return "array"
        case is [String: Any]: return "object"
        case is NSNull: return "null"
        default: return "\(type(of: v))"
        }
    }

    private static func parsePassthroughEnvelope(_ json: String) -> PassthroughSpec {
        var spec = PassthroughSpec()
        guard let data = json.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return spec }

        guard let env = parsed["passthrough"] as? [String: Any] else {
            // [T-model-use-passthrough-warnings] Exact key absent. Two silent
            // failure shapes to surface: (a) `passthrough` present but not an
            // object; (b) a lookalike top-level key — case/spelling variant,
            // or an object that carries envelope-shaped keys. Deliberately
            // simple heuristics (no edit-distance machinery).
            if let wrongType = parsed["passthrough"] {
                spec.warnings.append("Top-level 'passthrough' is not a JSON object (actual type: \(Self.jsonTypeName(wrongType))) — it was ignored and this call ran in STANDARD mode. Expected shape: {\"passthrough\":{\"endpoint\":...,\"body\":{...}}}.")
                return spec
            }
            let envelopeKeys: Set<String> = ["endpoint", "body", "body_mode", "headers", "method"]
            for (k, v) in parsed where !imageReservedKeys.contains(k) {
                guard let obj = v as? [String: Any] else { continue }
                let normalized = k.lowercased()
                    .replacingOccurrences(of: "_", with: "")
                    .replacingOccurrences(of: "-", with: "")
                let looksLikeName = normalized == "passthrough" || normalized == "passthru"
                    || normalized == "passthough" || normalized == "pathrough"
                let looksLikeShape = !Set(obj.keys).isDisjoint(with: envelopeKeys)
                if looksLikeName || looksLikeShape {
                    spec.warnings.append("Top-level key '\(k)' looks like a passthrough envelope, but the exact key must be lowercase 'passthrough' — it was ignored and this call ran in STANDARD mode.")
                }
            }
            return spec
        }
        spec.active = true

        if let epRaw = env["endpoint"] {
            if let ep = (epRaw as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !ep.isEmpty {
                spec.endpoint = ep
            } else {
                spec.warnings.append("passthrough.endpoint is not a non-empty string (actual type: \(Self.jsonTypeName(epRaw))) — ignored; the default chat/completions path was used.")
            }
        }
        if let mRaw = env["method"] {
            // PATCH added to the whitelist — provider-native update APIs use it.
            let allowed = ["GET", "POST", "PUT", "DELETE", "PATCH"]
            if let m = (mRaw as? String)?.uppercased(), allowed.contains(m) {
                spec.method = m
            } else {
                let desc = (mRaw as? String) ?? Self.jsonTypeName(mRaw)
                spec.warnings.append("method value '\(desc)' is not supported (supported: GET/POST/PUT/DELETE/PATCH) — sent as POST.")
            }
        }
        if let hRaw = env["headers"] {
            if let hdrs = hRaw as? [String: Any] {
                for (k, v) in hdrs {
                    if let s = v as? String {
                        spec.headers[k] = s
                    } else {
                        spec.warnings.append("passthrough.headers.\(k) value is not a string (actual type: \(Self.jsonTypeName(v))) — this header was ignored. Header values must be JSON strings.")
                    }
                }
            } else {
                spec.warnings.append("passthrough.headers is not a JSON object (actual type: \(Self.jsonTypeName(hRaw))) — all custom headers were ignored.")
            }
        }
        if let bRaw = env["body"] {
            if let b = bRaw as? [String: Any] {
                spec.body = b
            } else {
                spec.warnings.append("passthrough.body is not a JSON object (actual type: \(Self.jsonTypeName(bRaw))) — ignored. Wrap your body fields in an object: \"body\":{...}.")
            }
        }
        if let bmRaw = env["body_mode"] {
            if let bm = (bmRaw as? String)?.lowercased(), ["merge", "replace"].contains(bm) {
                spec.bodyMode = bm
            } else {
                let desc = (bmRaw as? String) ?? Self.jsonTypeName(bmRaw)
                spec.warnings.append("body_mode value '\(desc)' is invalid (only merge/replace are supported) — treated as merge.")
            }
        }
        return spec
    }

    /// Parse a CUSTOM endpoint address from `--endpoint` / top-level
    /// `endpoint` / `image_endpoint` values. A value starting with "/" is an
    /// absolute path replacing the entire URL path after scheme+host.
    /// Symbolic values (auto/images-gen/chat/…) are handled by
    /// `parseEndpointOverride` and return nil here.
    private static func parseCustomEndpointPath(_ json: String) -> String? {
        guard let data = json.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let raw = (parsed["image_endpoint"] as? String)
                  ?? (parsed["endpoint"] as? String)
        else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        // A "/..." value arriving via `--endpoint` argv may have been rewritten
        // by the offload layer's guest→host path translation (rootfs dataPath
        // prefix prepended — observed on-device: URL contained
        // /private/var/.../alpine-rootfs/data/api/v1/...). Strip it back to
        // the guest-relative path before treating it as a URL path.
        return trimmed.hasPrefix("/") ? Self.toLinuxPath(trimmed) : nil
    }

    /// Execute a raw passthrough request and build the metadata result.
    /// Output contract (user decision 2026-07-16): the response is NOT
    /// parsed — full raw bytes go to --output (or, when text-decodable and
    /// small, inline into the result); type detection and post-processing
    /// belong to the agent / follow-up scripts. The fully-assembled URL is
    /// always surfaced in the result.
    private static func performRawPassthrough(
        spec: PassthroughSpec,
        entry: ModelEntry,
        openAI: OpenAIProvider,
        inputJSON: String,
        systemPrompt: String?,
        maxTokens: Int,
        outputHostPath: String?
    ) async throws -> [String: Any] {
        // Baseline body for merge mode: the standard OpenAI-shape conversion
        // of the input (messages + system + max_tokens), so callers add ONLY
        // their provider-specific extras. replace mode: spec.body verbatim.
        var warnings = spec.warnings
        var bodyObject: [String: Any]?
        if spec.bodyMode == "replace" {
            bodyObject = spec.body.isEmpty ? nil : spec.body
            if spec.body.isEmpty {
                // Legit for GET-style calls, but a common symptom of body
                // content accidentally nested at the wrong JSON level — say so.
                warnings.append("body_mode=replace but body is empty or missing — this request was sent with NO body. If that is not what you intended, check that your fields are nested inside passthrough.body (not at the top level).")
            }
        } else {
            var baseline: [String: Any] = ["model": entry.model.id]
            if let msgs = try? parseOpenAIMessages(inputJSON), !msgs.isEmpty {
                var arr: [[String: Any]] = []
                if let sys = systemPrompt, !sys.isEmpty {
                    arr.append(["role": "system", "content": sys])
                }
                for m in msgs {
                    arr.append(["role": m.role == .user ? "user" : "assistant", "content": m.content])
                }
                baseline["messages"] = arr
                baseline["max_tokens"] = maxTokens
                baseline["stream"] = false
            }
            for (k, v) in spec.body { baseline[k] = v }
            baseline["model"] = entry.model.id   // merge mode locks model
            bodyObject = baseline
        }

        let result = try await openAI.rawPassthroughRequest(
            endpoint: spec.endpoint,
            method: spec.method,
            headers: spec.headers,
            bodyObject: bodyObject
        )

        var out: [String: Any] = [
            "passthrough": true,
            "http_status": result.status,
            "content_type": result.contentType ?? NSNull(),
            "bytes": result.data.count,
            "endpoint_url": result.url,
            "body_mode": spec.bodyMode,
            "model_id": entry.model.id,
        ]
        if let outputHostPath {
            let dir = (outputHostPath as NSString).deletingLastPathComponent
            try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
            do {
                try result.data.write(to: URL(fileURLWithPath: outputHostPath))
                out["output_file"] = Self.toLinuxPath(outputHostPath)
            } catch {
                // Don't fail the whole call over an unwritable --output (e.g.
                // a session bucket that no longer exists after reinstall) —
                // the request already succeeded. Surface what happened and
                // fall back to inline text when possible.
                out["output_error"] = "Could not write --output at \(Self.toLinuxPath(outputHostPath)): \(error.localizedDescription)"
                if let text = String(data: result.data, encoding: .utf8) {
                    out["response_text"] = text.count > 65536 ? String(text.prefix(65536)) + "…[truncated]" : text
                }
            }
        } else if let text = String(data: result.data, encoding: .utf8) {
            // No --output: inline text-decodable responses (truncated) so the
            // caller still sees something without a file round-trip.
            let capped = text.count > 65536 ? String(text.prefix(65536)) + "…[truncated]" : text
            out["response_text"] = capped
            if text.count > 65536 { out["truncated"] = true }
        } else {
            out["hint"] = "Response is binary (\(result.data.count) bytes) and no --output was given — re-run with --output <path> to save it."
        }
        if result.status < 200 || result.status >= 300 {
            out["error_hint"] = "Provider returned HTTP \(result.status); the raw response body above/in the output file is unmodified."
        }
        if !warnings.isEmpty {
            out["warnings"] = warnings
        }
        // [T-log-noise-privacy 2026-07-18] Strip the query/fragment before
        // logging: custom passthrough endpoints can carry credentials in the
        // query (Gemini-style ?key=...). The full URL still reaches the agent
        // via the result envelope's endpoint_url.
        let logURL = result.url.split(separator: "?", maxSplits: 1).first.map(String.init) ?? result.url
        logger.info("[ModelUseRoute] raw-passthrough done status=\(result.status) bytes=\(result.data.count) url=\(logURL) warnings=\(warnings.count)")
        return out
    }

    /// Parse the optional `image_endpoint` override from the input JSON.
    /// Accepts the same raw values as `ImageEndpointMode` (`auto`, `images_generations`,
    /// `chat_completions`) plus a few common shorthands. Returns nil when absent or unknown.
    private static func parseEndpointOverride(_ json: String) -> ImageEndpointMode? {
        guard let data = json.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let raw = (parsed["image_endpoint"] as? String)
                  ?? (parsed["endpoint"] as? String)
                  ?? ((parsed["generation_config"] as? [String: Any])?["image_endpoint"] as? String)
        else { return nil }
        switch raw.lowercased() {
        case "auto": return .auto
        case "images_generations", "images-generations", "images-gen": return .imagesGenerations
        case "chat_completions", "chat-completions", "chat": return .chatCompletions
        default: return nil
        }
    }

    /// Resolve the Codex text model that drives the image_generation tool. The
    /// codex /responses body needs a `model` (it triggers the tool; it does not
    /// affect image quality). Pick the highest-version `gpt-`-prefixed model
    /// configured on the instance, so the freshest available Codex model is used
    /// without hardcoding a version that may age out.
    ///
    /// Precedence:
    ///   1. Explicit override — `generation_config.model` / top-level `chat_model`.
    ///   2. Highest-version `gpt-*` (non-image) model on the instance.
    ///   3. Fallback "gpt-5.5".
    @MainActor
    private static func resolveCodexTopLevelModel(forInstance instanceId: String,
                                                  inputJSON: String) -> String? {
        if let data = inputJSON.data(using: .utf8),
           let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            let explicit = (parsed["chat_model"] as? String)
                ?? ((parsed["generation_config"] as? [String: Any])?["model"] as? String)
            if let explicit, !explicit.isEmpty { return explicit }
        }

        let models = ProviderConfigStore.shared.entries(for: instanceId).map(\.model)
        let candidates = models.filter {
            $0.id.hasPrefix("gpt-")
                && !$0.capabilities.supportedModalities.contains(.imageOutput)
        }
        if let latest = candidates.max(by: {
            gptVersionTuple($0.id).lexicographicallyPrecedes(gptVersionTuple($1.id))
        }) {
            return latest.id
        }
        return "gpt-5.5"
    }

    /// Extract the first dotted-number run from a `gpt-` model id as a comparable
    /// tuple, e.g. "gpt-5.5" → [5,5], "gpt-5.3-codex" → [5,3], "gpt-5" → [5].
    private static func gptVersionTuple(_ id: String) -> [Int] {
        let afterDigit = id.drop { !$0.isNumber }
        let run = afterDigit.prefix { $0.isNumber || $0 == "." }
        return run.split(separator: ".").compactMap { Int($0) }
    }

    /// Heuristic: does this provider error indicate the route doesn't exist on this base URL?
    /// We only fall back to /chat/completions when this returns true; real errors (auth,
    /// content moderation, model-not-found) bubble up unchanged.
    private static func looksLikeEndpointMissing(_ message: String) -> Bool {
        let m = message.lowercased()
        return m.contains("404")
            || m.contains("not found")
            || m.contains("no such endpoint")
            || m.contains("unknown endpoint")
            || m.contains("invalid url")
            || m.contains("method not allowed")
            || m.contains("unsupported route")
            || m.contains("405")
    }

    // MARK: - Build Media Result

    /// Build the standard result dict from an LLMResponse that contains media attachments.
    /// Shared by the /images/generations path and the regular sendMessage path.
    private static func buildMediaResult(
        response: LLMResponse,
        entry: ModelEntry,
        outputHostPath: String?,
        outputExt: String,
        imageEndpointUsed: ImageEndpointMode? = nil
    ) throws -> [String: Any] {
        let outputIsStructured = ["json", "yaml", "yml"].contains(outputExt)
        let outputIsMedia = Self.isMediaExtension(outputExt)

        var mediaFiles: [[String: Any]] = []
        let callerSid2 = ISHExecutionCoordinator.mountedSessionIdSnapshot ?? ""
        let attachDir: String
        if !callerSid2.isEmpty {
            attachDir = AIChatViewModel.minisAttachmentsPersistentDir(for: callerSid2).path
        } else {
            attachDir = RootfsManager.shared.dataPath
                .appendingPathComponent("var/minis/attachments").path
        }
        let modelSlug = entry.model.id.replacingOccurrences(of: "/", with: "_")
        let sessionShort = callerSid2
            .replacingOccurrences(of: "-", with: "")
            .prefix(8)
        let sessionTag = sessionShort.isEmpty ? "" : "\(sessionShort)-"
        let ts = Int(Date().timeIntervalSince1970)

        for (idx, attachment) in response.mediaAttachments.enumerated() {
            let nativeExt = Self.fileExtension(for: attachment.mimeType)

            let filePath: String
            if idx == 0, let outputHostPath, outputIsMedia {
                // --output has a media extension (e.g. .png, .jpg) → write image directly there
                let dir = (outputHostPath as NSString).deletingLastPathComponent
                try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
                filePath = outputHostPath
            } else if let outputHostPath, !outputIsStructured {
                let dir = (outputHostPath as NSString).deletingLastPathComponent
                try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
                filePath = (dir as NSString).appendingPathComponent("model-use-\(sessionTag)\(modelSlug)-\(ts)-\(idx).\(nativeExt)")
            } else {
                try? FileManager.default.createDirectory(atPath: attachDir, withIntermediateDirectories: true)
                filePath = (attachDir as NSString).appendingPathComponent("model-use-\(sessionTag)\(modelSlug)-\(ts)-\(idx).\(nativeExt)")
            }

            let targetExt = (filePath as NSString).pathExtension.lowercased()
            let outputData = Self.convertMediaIfNeeded(
                data: attachment.data,
                sourceMime: attachment.mimeType,
                targetExtension: targetExt
            )

            try outputData.write(to: URL(fileURLWithPath: filePath))
            // [T-model-use-media-path-mismatch] see saveMediaAttachments.
            let linuxPath2 = Self.guestLogicalPath(forSavedFile: filePath, callerSid: callerSid2)
            mediaFiles.append([
                "type": attachment.type.rawValue,
                "mime_type": Self.mimeType(for: targetExt) ?? attachment.mimeType,
                "path": linuxPath2,
                "size": outputData.count,
            ])
            logger.info("🖼️ Saved \(attachment.type.rawValue) (\(outputData.count) bytes) → \(filePath)")
        }

        // Write structured output if requested
        if let outputHostPath, outputIsStructured {
            try writeStructuredOutput(
                text: response.text, mediaFiles: mediaFiles,
                model: entry.model, usage: response.usage,
                path: outputHostPath, format: outputExt
            )
        } else if let outputHostPath, !outputIsMedia, !response.text.isEmpty {
            try writeOutputFile(text: response.text, path: outputHostPath)
        }

        var result: [String: Any] = [
            "model_id": entry.model.id,
            "model_name": entry.model.displayName,
            "output_text": response.text,
            "stop_reason": response.stopReason ?? "end_turn",
            "streamed": false,
            "output_file": outputHostPath.map { Self.toLinuxPath($0) } ?? NSNull(),
        ]
        if !mediaFiles.isEmpty {
            result["media_files"] = mediaFiles
        }
        if let usage = response.usage {
            result["usage"] = [
                "input_tokens": usage.inputTokens,
                "output_tokens": usage.outputTokens,
            ]
        }
        if let imageEndpointUsed {
            result["image_endpoint"] = imageEndpointUsed.rawValue
        }
        return result
    }

    // MARK: - Image URL Resolution

    /// Resolve an image URL from OpenAI-format content blocks. Throws
    /// `ModelUseError.invalidInput` on any unresolvable URL so the CLI
    /// exits non-zero with a descriptive stderr — never silently drops
    /// the image (which previously caused models to hallucinate about
    /// images they never received). See T-model-use-image-url-resolution.
    ///
    /// Supports:
    ///   - `data:<mime>;base64,...` — decode inline base64
    ///   - `file:///path` — read local file, infer MIME from extension
    ///   - `minis://<scope>/<path>` — resolve to host storage via the
    ///     per-session lookup used by the in-app image preview
    ///     (attachments, workspace, offloads, shared, skills, memory,
    ///     mounts). Active session id is inferred from the calling
    ///     agent's chat context.
    ///   - `/var/minis/<scope>/<path>` — bare Linux paths under
    ///     `/var/minis/`, mapped to the same scopes as `minis://`.
    ///   - `/<absolute/host/path>` (other) — fall back to direct iSH
    ///     rootfs lookup.
    ///
    /// Not supported (raises invalidInput so the agent learns):
    ///   - `http(s)://...` — would need a network fetch and risks
    ///     egressing user content; require the caller to download
    ///     explicitly with shell_execute + curl first.
    ///   - Anything else (unknown scheme, relative path).
    private static func resolveImageURL(_ url: String) throws -> LLMMessage.ImageAttachment {
        // data: URL — inline base64.
        if url.hasPrefix("data:") {
            let rest = url.dropFirst(5)
            guard let semiIdx = rest.firstIndex(of: ";"),
                  let commaIdx = rest.firstIndex(of: ",") else {
                throw ModelUseError.invalidInput("Malformed data: URL — expected `data:<mime>;base64,<...>`")
            }
            let mime = String(rest[rest.startIndex..<semiIdx])
            let b64 = String(rest[rest.index(after: commaIdx)...])
            guard let data = Data(base64Encoded: b64) else {
                throw ModelUseError.invalidInput("data: URL base64 payload failed to decode")
            }
            return LLMMessage.ImageAttachment(mimeType: mime, data: data)
        }

        // minis:// — route through the in-app minis URL resolver so
        // attachments/workspace/shared/etc. all work without the caller
        // knowing host paths.
        if url.hasPrefix("minis://") {
            guard let parsed = URL(string: url),
                  let resolved = AIChatViewModel.resolveMinisURL(parsed) else {
                throw ModelUseError.invalidInput("Could not resolve minis:// URL '\(url)' — file not found in any session scope. Try /var/minis/<scope>/<path> or file:///<host-path>.")
            }
            guard let data = FileManager.default.contents(atPath: resolved.path) else {
                throw ModelUseError.invalidInput("minis:// URL '\(url)' resolved to \(resolved.path) but the file is unreadable")
            }
            let mime = Self.imageMimeForExtension(url)
            return LLMMessage.ImageAttachment(mimeType: mime, data: data)
        }

        // /var/minis/<scope>/<path> — Linux bare path equivalent of
        // minis://<scope>/<path>. Rewrite to a minis:// URL and reuse
        // the same resolver so behavior is identical.
        if url.hasPrefix("/var/minis/") {
            let stripped = String(url.dropFirst("/var/minis/".count))
            // Split into scope + subpath. The minis URL "host" is the
            // first path component.
            let parts = stripped.split(separator: "/", maxSplits: 1).map(String.init)
            guard !parts.isEmpty, !parts[0].isEmpty else {
                throw ModelUseError.invalidInput("/var/minis/ path must include a scope (attachments, workspace, shared, etc.): '\(url)'")
            }
            let scope = parts[0]
            let sub = parts.count > 1 ? parts[1] : ""
            let minisURLStr = sub.isEmpty ? "minis://\(scope)" : "minis://\(scope)/\(sub)"
            guard let parsed = URL(string: minisURLStr),
                  let resolved = AIChatViewModel.resolveMinisURL(parsed) else {
                throw ModelUseError.invalidInput("Could not resolve '\(url)' — file not found in scope '\(scope)'. Check the path and that the file exists.")
            }
            guard let data = FileManager.default.contents(atPath: resolved.path) else {
                throw ModelUseError.invalidInput("'\(url)' resolved to \(resolved.path) but the file is unreadable")
            }
            let mime = Self.imageMimeForExtension(url)
            return LLMMessage.ImageAttachment(mimeType: mime, data: data)
        }

        // http(s):// — not supported. Tell the agent how to do it.
        if url.hasPrefix("http://") || url.hasPrefix("https://") {
            throw ModelUseError.invalidInput("http(s):// image URLs are not supported by minis-model-use. Download first with `shell_execute` (curl/wget) into /var/minis/workspace/, then reference the local path.")
        }

        // file:// — keep behavior, but throw on missing.
        if url.hasPrefix("file://") {
            let filePath = String(url.dropFirst("file://".count))
            // file:///abs/path lives directly on host (no rootfs prefix).
            // Match the previous behavior: try host path under rootfs first
            // (which is where iSH-visible files live), then fall back to
            // the literal path.
            if let data = Self.readImageFile(linuxPath: filePath) {
                let mime = Self.imageMimeForExtension(filePath)
                return LLMMessage.ImageAttachment(mimeType: mime, data: data)
            }
            throw ModelUseError.invalidInput("Could not read file:// URL '\(url)' — file not found.")
        }

        // /absolute/path — treat as Linux path inside the iSH rootfs.
        if url.hasPrefix("/") {
            if let data = Self.readImageFile(linuxPath: url) {
                let mime = Self.imageMimeForExtension(url)
                return LLMMessage.ImageAttachment(mimeType: mime, data: data)
            }
            throw ModelUseError.invalidInput("Image file not found at '\(url)'. For minis-scope files prefer /var/minis/<scope>/<path> or minis://<scope>/<path>.")
        }

        throw ModelUseError.invalidInput("Unsupported image_url '\(url)'. Use a data: URL, file:///host/path, /var/minis/<scope>/<path>, or minis://<scope>/<path>.")
    }

    /// Read a file given a Linux-side path. Tries the iSH rootfs data
    /// directory first (where bind-mounted /var/minis/* and /tmp/* land
    /// for direct host access), then the literal host path.
    private static func readImageFile(linuxPath: String) -> Data? {
        let hostPath = RootfsManager.shared.dataPath.appendingPathComponent(linuxPath).path
        if let data = FileManager.default.contents(atPath: hostPath) {
            return data
        }
        if let data = FileManager.default.contents(atPath: linuxPath) {
            return data
        }
        return nil
    }

    /// Infer image MIME from a path/URL's extension. Defaults to PNG
    /// when unknown — matches the prior behavior.
    private static func imageMimeForExtension(_ path: String) -> String {
        let ext = (path as NSString).pathExtension.lowercased()
        switch ext {
        case "png": return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "webp": return "image/webp"
        case "gif": return "image/gif"
        case "heic": return "image/heic"
        case "heif": return "image/heif"
        case "bmp": return "image/bmp"
        default: return "image/png"
        }
    }

    // MARK: - Output

    /// Convert a host filesystem path back to the Linux (shell) path.
    /// e.g. "/private/var/mobile/.../Documents/alpine-rootfs/data/var/minis/foo" → "/var/minis/foo"
    private static func toLinuxPath(_ hostPath: String) -> String {
        let dataPrefix = RootfsManager.shared.dataPath.path
        // Try with and without /private prefix (iOS symlink)
        for prefix in [dataPrefix, "/private" + dataPrefix] {
            if hostPath.hasPrefix(prefix) {
                let relative = String(hostPath.dropFirst(prefix.count))
                return relative.hasPrefix("/") ? relative : "/" + relative
            }
        }
        // Also handle dataPrefix itself starting with /private
        if dataPrefix.hasPrefix("/private") {
            let shortPrefix = String(dataPrefix.dropFirst("/private".count))
            if hostPath.hasPrefix(shortPrefix) {
                let relative = String(hostPath.dropFirst(shortPrefix.count))
                return relative.hasPrefix("/") ? relative : "/" + relative
            }
        }
        return hostPath
    }

    /// [T-model-use-media-path-mismatch] Derive the guest (`/var/minis/...`)
    /// path a just-saved media file is actually reachable at, from the REAL
    /// host `filePath` it was written to — so the response `media_files[].path`
    /// always matches where the bytes landed.
    ///
    /// Two host-storage shapes exist and each maps to a different guest path:
    ///   1. Session-persistent dirs under app Library (`.../minis/<sid>/{attachments,
    ///      workspace,offloads}/`). These are NOT under rootfs `dataPath`, so
    ///      `toLinuxPath` can't strip them — they're reachable in the guest only
    ///      via the per-session symlinks `ensureMinisSymlinks` maintains
    ///      (`/var/minis/attachments` → `.../<sid>/attachments`, etc.). Map by
    ///      matching the persistent-dir prefix for THIS caller session and
    ///      substituting the corresponding `/var/minis/<name>` root, preserving
    ///      any sub-path + filename.
    ///   2. A path already inside rootfs `dataPath` (e.g. a user `--output`
    ///      under `/var/minis/...` resolved by the CLI). `toLinuxPath` strips
    ///      it directly.
    ///
    /// The previous code hardcoded `/var/minis/attachments/<filename>` in the
    /// fallback branch regardless of where the file actually went — so a media
    /// file saved next to a `--output` in workspace was announced under
    /// attachments and read back as "file not found".
    private static func guestLogicalPath(forSavedFile filePath: String, callerSid: String) -> String {
        // Case 2 first: file already under rootfs dataPath → direct strip.
        let direct = toLinuxPath(filePath)
        if direct != filePath {
            return direct
        }

        // Case 1: match a session-persistent dir prefix for this caller.
        if !callerSid.isEmpty {
            let mappings: [(persistDir: String, linuxDir: String)] = [
                (AIChatViewModel.minisAttachmentsPersistentDir(for: callerSid).path,
                 AIChatViewModel.minisAttachmentsLinuxDir),
                (AIChatViewModel.minisWorkspacePersistentDir(for: callerSid).path,
                 AIChatViewModel.minisWorkspaceLinuxDir),
                (AIChatViewModel.minisOffloadsPersistentDir(for: callerSid).path,
                 AIChatViewModel.minisOffloadsLinuxDir),
            ]
            for (persistDir, linuxDir) in mappings {
                for prefix in [persistDir, "/private" + persistDir] {
                    if filePath.hasPrefix(prefix + "/") {
                        let sub = String(filePath.dropFirst(prefix.count))
                        return linuxDir + sub  // sub starts with "/"
                    }
                    if filePath == prefix {
                        return linuxDir
                    }
                }
            }
        }

        // Last resort: preserve prior behavior (announce under attachments by
        // basename). Only reachable when the file is neither under dataPath nor
        // any known persistent dir — shouldn't happen for our own save paths.
        return AIChatViewModel.minisAttachmentsLinuxDir + "/" + (filePath as NSString).lastPathComponent
    }

    /// Strip MIME parameters (e.g. "audio/L16;codec=pcm;rate=24000" → "audio/L16")
    private static func baseMimeType(_ mimeType: String) -> String {
        if let semi = mimeType.firstIndex(of: ";") {
            return String(mimeType[..<semi]).trimmingCharacters(in: .whitespaces).lowercased()
        }
        return mimeType.lowercased()
    }

    private static func fileExtension(for mimeType: String) -> String {
        switch baseMimeType(mimeType) {
        case "image/png": return "png"
        case "image/jpeg", "image/jpg": return "jpg"
        case "image/webp": return "webp"
        case "image/gif": return "gif"
        case "audio/l16", "audio/pcm": return "wav"  // raw PCM → save as .wav (header added in convertMediaIfNeeded)
        case "audio/wav", "audio/x-wav": return "wav"
        case "audio/mp3", "audio/mpeg": return "mp3"
        case "audio/aac", "audio/mp4": return "m4a"
        case "video/mp4": return "mp4"
        default: return "bin"
        }
    }

    /// Convert media data to match the target file extension if needed.
    /// e.g. model returns JPEG but --output asks for .png → re-encode as PNG via UIImage.
    private static func convertMediaIfNeeded(data: Data, sourceMime: String, targetExtension: String) -> Data {
        let baseMime = baseMimeType(sourceMime)
        let sourceExt = fileExtension(for: sourceMime)
        // No conversion needed if formats match or target is generic
        guard !targetExtension.isEmpty, targetExtension != "bin",
              sourceExt != targetExtension else {
            // Still need to wrap PCM as WAV even when sourceExt == targetExt == "wav"
            if (baseMime == "audio/l16" || baseMime == "audio/pcm") && targetExtension == "wav" {
                return Self.wrapPCMAsWAV(pcmData: data, sourceMime: sourceMime)
            }
            return data
        }

        // Image format conversion via UIImage
        if baseMime.hasPrefix("image/") && isImageExtension(targetExtension) {
            guard let image = UIImage(data: data) else { return data }
            switch targetExtension {
            case "png":
                return image.pngData() ?? data
            case "jpg", "jpeg":
                return image.jpegData(compressionQuality: 0.92) ?? data
            default:
                // webp/gif etc. — no native UIImage encoder, keep original
                return data
            }
        }

        // PCM (L16) → WAV: just add a 44-byte RIFF header
        if (baseMime == "audio/l16" || baseMime == "audio/pcm") && targetExtension == "wav" {
            return Self.wrapPCMAsWAV(pcmData: data, sourceMime: sourceMime)
        }

        // Other audio conversions not supported — pass through
        return data
    }

    /// Wrap raw PCM data in a WAV (RIFF) container — 44-byte header + PCM payload.
    /// Parses sample rate from MIME parameters if available (e.g. "audio/L16;codec=pcm;rate=24000").
    private static func wrapPCMAsWAV(pcmData: Data, sourceMime: String = "", sampleRate: Int = 24000, channels: Int = 1, bitsPerSample: Int = 16) -> Data {
        // Try to extract rate from MIME parameters
        var rate = sampleRate
        if sourceMime.contains("rate=") {
            let parts = sourceMime.split(separator: ";").map { $0.trimmingCharacters(in: .whitespaces) }
            for part in parts {
                if part.lowercased().hasPrefix("rate="), let r = Int(part.dropFirst(5)) {
                    rate = r
                }
            }
        }
        return Self._buildWAV(pcmData: pcmData, sampleRate: rate, channels: channels, bitsPerSample: bitsPerSample)
    }

    private static func _buildWAV(pcmData: Data, sampleRate: Int, channels: Int, bitsPerSample: Int) -> Data {
        let byteRate = sampleRate * channels * (bitsPerSample / 8)
        let blockAlign = channels * (bitsPerSample / 8)
        let dataSize = pcmData.count
        let chunkSize = 36 + dataSize

        var header = Data(capacity: 44)
        func appendUInt32LE(_ value: UInt32) { withUnsafeBytes(of: value.littleEndian) { header.append(contentsOf: $0) } }
        func appendUInt16LE(_ value: UInt16) { withUnsafeBytes(of: value.littleEndian) { header.append(contentsOf: $0) } }

        header.append(contentsOf: [0x52, 0x49, 0x46, 0x46]) // "RIFF"
        appendUInt32LE(UInt32(chunkSize))
        header.append(contentsOf: [0x57, 0x41, 0x56, 0x45]) // "WAVE"
        header.append(contentsOf: [0x66, 0x6D, 0x74, 0x20]) // "fmt "
        appendUInt32LE(16)                                     // fmt chunk size
        appendUInt16LE(1)                                      // PCM format
        appendUInt16LE(UInt16(channels))
        appendUInt32LE(UInt32(sampleRate))
        appendUInt32LE(UInt32(byteRate))
        appendUInt16LE(UInt16(blockAlign))
        appendUInt16LE(UInt16(bitsPerSample))
        header.append(contentsOf: [0x64, 0x61, 0x74, 0x61]) // "data"
        appendUInt32LE(UInt32(dataSize))

        return header + pcmData
    }

    private static func isImageExtension(_ ext: String) -> Bool {
        ["png", "jpg", "jpeg", "webp", "gif", "heic"].contains(ext)
    }

    /// Map file extension → MIME type (inverse of fileExtension(for:))
    private static func mimeType(for ext: String) -> String? {
        switch ext.lowercased() {
        case "png": return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "webp": return "image/webp"
        case "gif": return "image/gif"
        case "wav": return "audio/wav"
        case "mp3": return "audio/mpeg"
        case "m4a": return "audio/mp4"
        case "mp4": return "video/mp4"
        default: return nil
        }
    }

    private static func isMediaExtension(_ ext: String) -> Bool {
        isImageExtension(ext) || isAudioExtension(ext) || isVideoExtension(ext)
    }

    private static func isAudioExtension(_ ext: String) -> Bool {
        ["wav", "mp3", "m4a", "aac", "ogg", "flac"].contains(ext)
    }

    private static func isVideoExtension(_ ext: String) -> Bool {
        ["mp4", "mov", "webm", "mkv"].contains(ext)
    }

    private static func modalityList(_ modalities: ModelModality) -> [String] {
        var list: [String] = []
        if modalities.contains(.textInput)   { list.append("text_input") }
        if modalities.contains(.textOutput)  { list.append("text_output") }
        if modalities.contains(.imageInput)  { list.append("image_input") }
        if modalities.contains(.pdfInput)    { list.append("pdf_input") }
        if modalities.contains(.audioInput)  { list.append("audio_input") }
        if modalities.contains(.videoInput)  { list.append("video_input") }
        if modalities.contains(.imageOutput) { list.append("image_output") }
        if modalities.contains(.audioOutput) { list.append("audio_output") }
        if modalities.contains(.videoOutput) { list.append("video_output") }
        return list
    }

    /// Write structured output (JSON or YAML) containing text + media file references.
    private static func writeStructuredOutput(text: String,
                                               mediaFiles: [[String: Any]],
                                               model: LLMModel,
                                               usage: LLMUsage?,
                                               path: String,
                                               format: String) throws {
        let dir = (path as NSString).deletingLastPathComponent
        try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)

        var output: [String: Any] = [
            "model_id": model.id,
            "timestamp": ISO8601DateFormatter().string(from: Date()),
        ]
        if !text.isEmpty { output["content"] = text }
        if !mediaFiles.isEmpty { output["media_files"] = mediaFiles }
        if let usage {
            output["usage"] = [
                "input_tokens": usage.inputTokens,
                "output_tokens": usage.outputTokens,
            ]
        }

        if format == "yaml" || format == "yml" {
            // Simple YAML serialization for flat/shallow structures
            var lines: [String] = []
            for (key, value) in output.sorted(by: { $0.key < $1.key }) {
                if let str = value as? String {
                    lines.append("\(key): \"\(str.replacingOccurrences(of: "\"", with: "\\\""))\"")
                } else if let num = value as? Int {
                    lines.append("\(key): \(num)")
                } else if let arr = value as? [[String: Any]] {
                    lines.append("\(key):")
                    for item in arr {
                        var first = true
                        for (k, v) in item.sorted(by: { $0.key < $1.key }) {
                            let prefix = first ? "  - " : "    "
                            first = false
                            lines.append("\(prefix)\(k): \(v)")
                        }
                    }
                } else if let dict = value as? [String: Any] {
                    lines.append("\(key):")
                    for (k, v) in dict.sorted(by: { $0.key < $1.key }) {
                        lines.append("  \(k): \(v)")
                    }
                } else {
                    lines.append("\(key): \(value)")
                }
            }
            let yaml = lines.joined(separator: "\n") + "\n"
            try yaml.write(toFile: path, atomically: true, encoding: .utf8)
        } else {
            // JSON (default structured format)
            let data = try JSONSerialization.data(withJSONObject: output, options: [.prettyPrinted, .sortedKeys])
            try data.write(to: URL(fileURLWithPath: path))
        }
    }

    private static func writeOutputFile(text: String, path: String) throws {
        // Ensure parent directory exists
        let dir = (path as NSString).deletingLastPathComponent
        try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)

        // Determine output format by extension
        let ext = (path as NSString).pathExtension.lowercased()
        if ext == "json" {
            let output: [String: Any] = [
                "content": text,
                "type": "text",
                "timestamp": ISO8601DateFormatter().string(from: Date()),
            ]
            let data = try JSONSerialization.data(withJSONObject: output, options: [.prettyPrinted, .sortedKeys])
            try data.write(to: URL(fileURLWithPath: path))
        } else {
            try text.write(toFile: path, atomically: true, encoding: .utf8)
        }
    }
}

// MARK: - Errors

enum ModelUseError: LocalizedError {
    case modelNotFound(String)
    case invalidInput(String)
    case modalityNotSupported(model: String, required: String, supported: [String])

    var errorDescription: String? {
        switch self {
        case .modelNotFound(let id):
            return "Model '\(id)' not found. Use 'minis-model-use list' to see available models."
        case .invalidInput(let msg):
            return msg
        case .modalityNotSupported(let model, let required, let supported):
            return "Model '\(model)' does not support \(required). Supported modalities: \(supported.joined(separator: ", ")). Use 'minis-model-use list' to find a model with the required capability."
        }
    }
}
