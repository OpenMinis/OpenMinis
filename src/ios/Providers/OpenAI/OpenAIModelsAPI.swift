import Foundation
import CryptoKit
import os.log

private let logger = AppLogger(category: "OpenAIModelsAPI")

private func stripV1Suffix(_ base: String) -> String {
    var s = base
    while s.hasSuffix("/") { s = String(s.dropLast()) }
    if s.hasSuffix("/v1") { s = String(s.dropLast(3)) }
    return s
}

enum OpenAIModelsAPI {

    private static let defaultBaseURL = "https://api.openai.com"

    static func fetchModels(apiKey: String, baseURL: String? = nil, appendV1Suffix: Bool = true, forceRefresh: Bool = false, userAgent: String? = nil) async throws -> [LLMModel] {
        if !forceRefresh, let cached = OpenAIModelsCache.load(credential: apiKey) {
            logger.info("Returning \(cached.count) cached models (API key)")
            return cached
        }

        let isCustomBase = baseURL != nil
        let base = appendV1Suffix ? stripV1Suffix(baseURL ?? defaultBaseURL) : (baseURL ?? defaultBaseURL)
        let v1Path = appendV1Suffix ? "/v1" : ""
        guard let url = URL(string: URLBuilding.join(base, v1Path, "/models")) else {
            throw LLMError.providerError(message: "Invalid base URL: \(base)")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        if let ua = userAgent { request.setValue(ua, forHTTPHeaderField: "User-Agent") }
        logger.info("Fetching OpenAI models (API key auth, custom base: \(isCustomBase), appendV1: \(appendV1Suffix))")
        let models = try await performFetch(request, filterOpenAIOnly: !isCustomBase)
        OpenAIModelsCache.save(models, credential: apiKey)
        return models
    }

    // MARK: - [T-codex-model-discovery] Codex OAuth model discovery (issue #319)

    /// Codex OAuth model discovery, with the same three-tier degradation the
    /// other providers use:
    ///
    ///   1. the Codex backend's own model endpoint — authoritative, reflects
    ///      what THIS account may actually use;
    ///   2. models.dev, for the ids we know of but could not fetch;
    ///   3. the compiled-in `allOpenAICodexOAuth` list, so an offline first run
    ///      still shows something usable.
    ///
    /// The old behaviour was tier 3 only, with a comment saying OAuth "cannot
    /// call /v1/models". That is true of `/v1/models`, but Codex publishes a
    /// separate endpoint that does work with an OAuth token — so a model the
    /// account gained (or lost) was invisible until someone edited the list.
    ///
    /// - Parameters:
    ///   - instanceId: provider instance, used to fetch a fresh token + account id.
    ///   - forceRefresh: a manual "Refresh" must really re-fetch, so it bypasses
    ///     the cache rather than returning what is already on screen.
    static func fetchModelsOAuth(instanceId: String, forceRefresh: Bool = false) async throws -> [LLMModel] {
        // Resolved once so the catch blocks can build the same cache key
        // without another main-actor hop (and without it being nil there).
        var resolvedAccountId: String?

        // Tier 1 — the authoritative endpoint.
        do {
            let token = try await CodexOAuthManager.shared.validAccessToken(instanceId: instanceId)
            let accountId = await MainActor.run { CodexOAuthManager.shared.accountId(instanceId: instanceId) }
            resolvedAccountId = accountId
            // Cache is keyed on token + account + client version: switching
            // accounts must not reuse the other account's catalog, and a client
            // version bump can change what the backend offers.
            let cacheKey = "codex-oauth|\(accountId ?? "-")|\(OpenAIProvider.codexClientVersion)|\(token)"

            if !forceRefresh, let cached = OpenAIModelsCache.load(credential: cacheKey) {
                logger.info("Codex discovery: returning \(cached.count) cached models")
                return cached
            }

            let fresh = try await fetchCodexBackendModels(token: token, accountId: accountId)
            if !fresh.isEmpty {
                // Fill only the gaps the endpoint left. `ModelsDevAPI.enrichModels`
                // treats models.dev as the source of truth and OVERWRITES
                // modality / context / output / reasoning — correct when the
                // input is our static list, wrong here, where the endpoint is
                // the authority and models.dev may be months stale about a model
                // that only appeared today. So enrich a copy and keep whichever
                // fields the endpoint actually stated.
                let discovered = mergeKeepingAuthoritative(fresh: fresh,
                                                           enriched: ModelsDevAPI.enrichModels(fresh))
                // [T-codex-image-models-survive-discovery] Re-append the image
                // generators. `/backend-api/codex/models` lists CHAT models
                // only — the image models reach the same account over a
                // different path entirely (the `image_generation` tool on
                // `/codex/responses`, see ModelUseOffloadBridge's
                // `isCodexImage` branch), so the endpoint has no reason to
                // mention them and does not.
                //
                // Without this, a successful tier-1 refresh REPLACED the list
                // and silently dropped gpt-image-2 and the 2.5 variants from
                // the picker — verified on device: an instance refreshed this
                // way came back with 11 chat models and no image model at all.
                // Tiers 2 and 3 were never affected, since they are built from
                // `allOpenAICodexOAuth`, which contains them.
                let models = appendBuiltInImageModels(to: discovered)
                OpenAIModelsCache.save(models, credential: cacheKey)
                // Second copy under a token-independent key so an offline
                // refresh can still serve this catalog after the token rotates.
                OpenAIModelsCache.save(models, credential: lastKnownGoodKey(instanceId: instanceId, accountId: resolvedAccountId))
                logger.info("Codex discovery: \(models.count) models from backend")
                return models
            }
            // 200 with nothing usable — fall through rather than show an empty
            // picker, but do NOT treat it as an auth problem.
            logger.warning("Codex discovery: endpoint returned no usable models — falling back")
        } catch let error as LLMError {
            // [T-codex-model-discovery] An auth failure is reported as one.
            // Silently degrading to a stale/built-in list here would show the
            // user a healthy-looking catalog for an account that can no longer
            // call anything — the failure has to surface at refresh time.
            if case .invalidAPIKey = error { throw error }
            logger.warning("Codex discovery failed (\(error.localizedDescription)) — falling back")
            if let cached = OpenAIModelsCache.load(credential: lastKnownGoodKey(instanceId: instanceId, accountId: resolvedAccountId)) {
                logger.info("Codex discovery: serving \(cached.count) previously cached models")
                return cached
            }
        } catch {
            logger.warning("Codex discovery failed (\(error.localizedDescription)) — falling back")
            if let cached = OpenAIModelsCache.load(credential: lastKnownGoodKey(instanceId: instanceId, accountId: resolvedAccountId)) {
                logger.info("Codex discovery: serving \(cached.count) previously cached models")
                return cached
            }
        }

        // Tiers 2 + 3 — models.dev metadata over the built-in ids. `enrichModels`
        // is exactly tier 2: it fills each built-in id from the models.dev
        // registry and leaves it as-is when there is no match.
        let enriched = ModelsDevAPI.enrichModels(LLMModel.allOpenAICodexOAuth)
        logger.info("Codex discovery: using built-in list (\(enriched.count) models, models.dev enriched)")
        return enriched
    }

    /// Combine endpoint truth with models.dev metadata.
    ///
    /// Field-by-field: a value the ENDPOINT stated wins; everything it left nil
    /// is taken from the enriched copy. This is the "do not let stale
    /// enrichment overwrite fresh authoritative metadata" rule from the issue,
    /// and it is why we cannot simply return `enrichModels(fresh)`.
    private static func mergeKeepingAuthoritative(fresh: [LLMModel], enriched: [LLMModel]) -> [LLMModel] {
        let byId = Dictionary(enriched.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        return fresh.map { authoritative in
            guard var merged = byId[authoritative.id] else { return authoritative }
            if authoritative.contextWindow != nil { merged.contextWindow = authoritative.contextWindow }
            if authoritative.maxOutputTokens != nil { merged.maxOutputTokens = authoritative.maxOutputTokens }
            if authoritative.supportsReasoning != nil { merged.supportsReasoning = authoritative.supportsReasoning }
            if authoritative.modalityOverride != nil { merged.modalityOverride = authoritative.modalityOverride }
            // Mutate in place rather than rebuilding: `LLMModel` also carries
            // effort-tier fields that only models.dev knows about, and a
            // re-`init` with the arguments we happen to remember would silently
            // drop them. `displayName` needs no handling — `enrichModels` never
            // replaces it, so `merged` already carries the endpoint's own name.
            return merged
        }
    }

    /// [T-codex-image-models-survive-discovery] Add the built-in Codex image
    /// generators to a discovered list, unless the endpoint already named one.
    ///
    /// Kept as an explicit, named step rather than folded into the merge: these
    /// models are not "metadata the endpoint forgot", they are a different
    /// capability that travels a different route, and a future reader needs to
    /// see that they are deliberately union'd in rather than wonder why the
    /// authoritative list is being topped up.
    ///
    /// The `contains` guard means that if Codex ever does start listing them,
    /// the endpoint's own entry wins and nothing is duplicated.
    private static func appendBuiltInImageModels(to discovered: [LLMModel]) -> [LLMModel] {
        let known = Set(discovered.map(\.id))
        let images = ModelsDevAPI.enrichModels(
            LLMModel.allOpenAICodexOAuth.filter {
                LLMModel.allCodexOAuthImageModelIDs.contains($0.id) && !known.contains($0.id)
            })
        guard !images.isEmpty else { return discovered }
        logger.info("Codex discovery: re-appended \(images.count) built-in image model(s)")
        return discovered + images
    }

    /// Stable per-instance key for the "last successful fetch" copy, so an
    /// offline refresh can still serve the previous catalog after the access
    /// token has rotated (the token is part of the primary key and therefore
    /// changes on every refresh).
    private static func lastKnownGoodKey(instanceId: String, accountId: String?) -> String {
        "codex-oauth-last|\(instanceId)|\(accountId ?? "-")"
    }

    /// GET the Codex backend's model catalogue.
    ///
    /// Headers mirror `OpenAIProvider`'s Codex request path EXACTLY — same
    /// `Version`, same `codex_cli_rs` originator and User-Agent. The reporter's
    /// probe used `originator: omp`, which is that tool's identity, not ours;
    /// sending a different fingerprint for discovery than for inference would
    /// be both dishonest and a good way to have the two disagree about which
    /// models are allowed.
    private static func fetchCodexBackendModels(token: String, accountId: String?) async throws -> [LLMModel] {
        let version = OpenAIProvider.codexClientVersion
        guard let url = URL(string: "https://chatgpt.com/backend-api/codex/models?client_version=\(version)") else {
            throw LLMError.providerError(message: "Invalid Codex models URL")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue(version, forHTTPHeaderField: "Version")
        request.setValue("responses=experimental", forHTTPHeaderField: "Openai-Beta")
        request.setValue("codex_cli_rs/\(version) (iOS; arm64)", forHTTPHeaderField: "User-Agent")
        request.setValue("codex_cli_rs", forHTTPHeaderField: "Originator")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let accountId { request.setValue(accountId, forHTTPHeaderField: "Chatgpt-Account-Id") }

        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? -1
        guard (200..<300).contains(status) else {
            let body = String(data: data, encoding: .utf8) ?? ""
            if status == 401 || status == 403 {
                throw LLMError.invalidAPIKey(
                    detail: "Codex models HTTP \(status): \(String(body.prefix(200)))")
            }
            throw LLMError.providerError(
                message: "Codex model discovery failed (HTTP \(status)): \(body.prefix(300))")
        }
        return parseCodexModels(data)
    }

    /// Map the Codex catalogue onto `LLMModel`.
    ///
    /// Tolerant of shape: the payload has been seen as a bare array and as an
    /// object wrapping `models`/`data`, and this endpoint is undocumented, so a
    /// key appearing or moving must degrade to "fewer fields", never to a throw.
    static func parseCodexModels(_ data: Data) -> [LLMModel] {
        let root = try? JSONSerialization.jsonObject(with: data)
        let items: [[String: Any]]
        if let arr = root as? [[String: Any]] {
            items = arr
        } else if let obj = root as? [String: Any] {
            items = (obj["models"] as? [[String: Any]])
                ?? (obj["data"] as? [[String: Any]])
                ?? []
        } else {
            items = []
        }

        return items.compactMap { item -> LLMModel? in
            guard let id = (item["id"] as? String) ?? (item["slug"] as? String) ?? (item["model"] as? String),
                  !id.isEmpty else { return nil }

            // Respect visibility: a model the backend marks hidden/unavailable
            // stays out of the picker rather than becoming a 400 the user only
            // discovers by sending a message.
            if let visibility = (item["visibility"] as? String)?.lowercased(),
               visibility == "hidden" || visibility == "internal" || visibility == "none" {
                return nil
            }
            for flag in ["is_visible", "visible", "enabled", "available"] {
                if let v = item[flag] as? Bool, v == false { return nil }
            }

            let display = (item["display_name"] as? String)
                ?? (item["displayName"] as? String)
                ?? (item["name"] as? String)
                ?? id
            var model = LLMModel(id: id, displayName: display, provider: "OpenAI")

            // Only the fields the endpoint actually states. Anything absent is
            // left nil so the models.dev pass below can supply it.
            if let ctx = (item["context_window"] as? Int)
                ?? (item["contextWindow"] as? Int)
                ?? ((item["limit"] as? [String: Any])?["context"] as? Int) {
                model.contextWindow = ctx
            }
            if let out = (item["max_output_tokens"] as? Int)
                ?? (item["maxOutputTokens"] as? Int)
                ?? ((item["limit"] as? [String: Any])?["output"] as? Int) {
                model.maxOutputTokens = out
            }
            if let reasoning = (item["supports_reasoning"] as? Bool)
                ?? (item["reasoning"] as? Bool) {
                model.supportsReasoning = reasoning
            }
            return model
        }
    }

    private static func performFetch(_ request: URLRequest, filterOpenAIOnly: Bool = true) async throws -> [LLMModel] {
        let (data, response) = try await URLSession.shared.data(for: request)
        let http = response as? HTTPURLResponse
        let statusCode = http?.statusCode ?? -1

        guard (200..<300).contains(statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? ""
            logger.error("OpenAI models API error — status \(statusCode)")
            if statusCode == 401 || statusCode == 403 {
                throw LLMError.invalidAPIKey(detail: "OpenAI HTTP \(statusCode): \(String(body.prefix(200)))")
            }
            throw LLMError.providerError(message: "Failed to fetch OpenAI models (HTTP \(statusCode)): \(body.prefix(500))")
        }

        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
        guard let modelsArray = json["data"] as? [[String: Any]] else {
            throw LLMError.decodingError(underlying: NSError(domain: "OpenAIModelsAPI", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Missing data array in response"]))
        }

        // For official OpenAI endpoints, filter to chat-capable models only.
        // For custom/third-party endpoints, return all models as-is (their IDs won't match OpenAI prefixes).
        let chatPrefixes = ["gpt-", "o1", "o3", "o4-", "codex-", "chatgpt-"]
        let excludeSuffixes = ["-instruct", "-realtime", "-audio", "-transcribe", "-tts", "-embedding"]

        let models = modelsArray.compactMap { item -> LLMModel? in
            guard let id = item["id"] as? String else { return nil }

            if filterOpenAIOnly {
                let isChatModel = chatPrefixes.contains { id.hasPrefix($0) }
                guard isChatModel else { return nil }

                let isExcluded = excludeSuffixes.contains { id.hasSuffix($0) }
                guard !isExcluded else { return nil }

                // Skip fine-tuned models
                guard !id.contains(":ft-") else { return nil }
            }

            let displayName = (item["name"] as? String) ?? modelDisplayName(from: id)

            // Parse modalities. Two wire shapes are supported:
            //   - OpenRouter: nested under `architecture.input_modalities` /
            //     `.output_modalities`, suffixed (`image_input`).
            //   - Groq / other OpenAI-compatible: TOP-LEVEL `input_modalities` /
            //     `output_modalities`, bare. Groq additionally uses voice-specific
            //     output values `transcription` (ASR) and `speech` (TTS).
            // models.dev returns bare forms too. normalizeModality folds the
            // shapes together. When the API DOES report modalities we honour them
            // verbatim — crucially NOT seeding text — so a pure-audio ASR model
            // (Whisper: input=[audio], output=[transcription]) resolves to exactly
            // `.audioInput` and is recognised as a voice model. Only when the API
            // says nothing do we fall back to the text default (load-bearing: a
            // bare default of nil would let text-only endpoints inherit the
            // provider-level `.vision` capability and emit image blocks DeepSeek
            // rejects — see note below).
            let inputArr = (item["input_modalities"] as? [String])
                ?? ((item["architecture"] as? [String: Any])?["input_modalities"] as? [String])
            let outputArr = (item["output_modalities"] as? [String])
                ?? ((item["architecture"] as? [String: Any])?["output_modalities"] as? [String])

            var modality: ModelModality = (inputArr == nil && outputArr == nil) ? [.textInput, .textOutput] : []
            if let inputs = inputArr {
                let bare = Set(inputs.map(Self.normalizeModality))
                if bare.contains("text")  { modality.insert(.textInput) }
                if bare.contains("image") { modality.insert(.imageInput) }
                if bare.contains("pdf")   { modality.insert(.pdfInput) }
                if bare.contains("audio") { modality.insert(.audioInput) }
                if bare.contains("video") { modality.insert(.videoInput) }
            }
            if let outputs = outputArr {
                let bare = Set(outputs.map(Self.normalizeModality))
                if bare.contains("text")          { modality.insert(.textOutput) }
                if bare.contains("transcription") { modality.insert(.textOutput) }   // ASR → text out
                if bare.contains("image")         { modality.insert(.imageOutput) }
                if bare.contains("audio")         { modality.insert(.audioOutput) }
                if bare.contains("speech")        { modality.insert(.audioOutput) }  // TTS → audio out
                if bare.contains("video")         { modality.insert(.videoOutput) }
            }

            // Always record the modality we computed, even when it's plain
            // text. Leaving this nil falls through to the provider-level
            // default (`knownCapabilities["OpenAI"] = .vision`), which then
            // misclassifies text-only OpenAI-compatible endpoints (DeepSeek
            // V4, Mistral, etc.) as vision-capable. The sanitize sites in
            // OpenAIAgentProvider (lines 744 / 900 / 968) then keep emitting
            // `image_url` content blocks into history, which DeepSeek
            // rejects with `unknown variant 'image_url'`. Always-write means
            // text-only endpoints stay text-only, while real OpenAI vision
            // models still get `.imageInput` from the architecture block
            // above (or from models.dev / pattern inference downstream).
            return LLMModel(id: id, displayName: displayName, provider: "OpenAI", modalityOverride: modality)
        }

        let enriched = ModelsDevAPI.enrichModels(models)
        logger.info("Fetched \(modelsArray.count) total models, \(enriched.count) returned (filterOpenAIOnly: \(filterOpenAIOnly))")
        if let first = modelsArray.first,
           let debugData = try? JSONSerialization.data(withJSONObject: first, options: [.prettyPrinted, .sortedKeys]),
           let debugStr = String(data: debugData, encoding: .utf8) {
            logger.info("First model raw JSON:\n\(debugStr)")
        }
        return enriched
    }

    /// Strip `_input` / `_output` suffix and lowercase. Provider APIs are inconsistent —
    /// OpenAI returns `image_input` / `text_output` while models.dev returns bare `image`
    /// / `text`. Mirrors Android's `String.normalizeModalityName`.
    static func normalizeModality(_ raw: String) -> String {
        var s = raw.lowercased()
        if s.hasSuffix("_input") { s = String(s.dropLast("_input".count)) }
        if s.hasSuffix("_output") { s = String(s.dropLast("_output".count)) }
        return s
    }
}

// MARK: - Cache

private enum OpenAIModelsCache {

    private struct Entry: Codable {
        let models: [LLMModel]
        let date: Date
    }

    private static let ttl: TimeInterval = 7 * 24 * 3600

    private static var cacheDir: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("com.openminis.app.openai-models-cache", isDirectory: true)
    }

    private static func cacheKey(for credential: String) -> String {
        let digest = SHA256.hash(data: Data(credential.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private static func cacheFile(for credential: String) -> URL {
        cacheDir.appendingPathComponent(cacheKey(for: credential) + ".json")
    }

    static func load(credential: String) -> [LLMModel]? {
        let file = cacheFile(for: credential)
        guard let data = try? Data(contentsOf: file),
              let entry = try? JSONDecoder().decode(Entry.self, from: data),
              Date().timeIntervalSince(entry.date) < ttl else {
            return nil
        }
        return entry.models
    }

    static func save(_ models: [LLMModel], credential: String) {
        let entry = Entry(models: models, date: Date())
        guard let data = try? JSONEncoder().encode(entry) else { return }
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
        try? data.write(to: cacheFile(for: credential), options: .atomic)
    }
}
