import Foundation

private let logger = AppLogger(category: "ModelsDevAPI")

/// Fetches and caches the models.dev provider registry.
/// Used as a fallback when a provider's /v1/models endpoint is unavailable,
/// and as the source of truth for model capabilities (modality, context window, output limit).
enum ModelsDevAPI {

    private static let sourceURL = "https://models.dev/api.json"
    private static let cacheTTL: TimeInterval = 48 * 3600 // 48 hours

    // MARK: - Provider-key mapping for enrichment lookups

    /// Maps the `provider` string stored in LLMModel to one or more models.dev provider keys.
    /// Order matters — first match wins.
    private static let providerKeyMap: [String: [String]] = [
        "Anthropic": ["anthropic"],
        "Google": ["google", "google-vertex"],
        "OpenAI": ["openai"],
        "OpenRouter": ["openrouter"],
        "Antigravity": [],  // Custom proxy, no public models.dev entry
    ]

    // MARK: - Public: Fetch models by base URL

    /// Look up models for a provider by matching its API base URL against models.dev entries.
    /// Tries both with and without `/v1` suffix, normalizing trailing slashes.
    /// Returns immediately from bundled/cached data; network refresh happens in the background.
    static func fetchModels(forBaseURL baseURL: String) -> [LLMModel] {
        guard let registry = loadRegistry() else {
            logger.info("models.dev registry not loaded")
            return []
        }

        logger.info("models.dev lookup: baseURL=\(baseURL), registry has \(registry.count) providers")

        // Phase 1: Exact API base match (with/without /v1)
        let candidates = normalizedCandidates(for: baseURL)
        logger.info("models.dev phase1: candidates=\(candidates)")
        for (_, provider) in registry {
            guard let api = provider.api, !api.isEmpty else { continue }
            let normalizedAPI = stripTrailingSlash(api)
            for candidate in candidates {
                if candidate == normalizedAPI {
                    let models = buildModels(from: provider)
                    logger.info("Exact match \(provider.id) (api=\(api)) — \(models.count) models")
                    return models
                }
            }
        }

        // Phase 2: Hostname fallback — match by full hostname when exact path doesn't match
        let inputHost = extractHost(from: baseURL)
        logger.info("models.dev phase2: inputHost=\(inputHost ?? "nil")")
        if let inputHost {
            for (_, provider) in registry {
                guard let api = provider.api, !api.isEmpty,
                      let providerHost = extractHost(from: api) else { continue }
                if inputHost == providerHost {
                    let models = buildModels(from: provider)
                    logger.info("Host match \(provider.id) (host=\(providerHost), api=\(api)) — \(models.count) models")
                    return models
                }
            }
        }

        logger.info("No models.dev match for base URL: \(baseURL)")
        return []
    }

    private static func buildModels(from provider: ModelsDevProvider) -> [LLMModel] {
        provider.models.compactMap { (_, model) -> LLMModel? in
            let family = model.family?.lowercased() ?? ""
            if family.contains("embedding") || family.contains("moderation") { return nil }
            var built = LLMModel(
                id: model.id,
                displayName: model.name ?? model.id,
                provider: provider.name ?? provider.id,
                modalityOverride: model.resolvedModality,
                contextWindow: model.limit?.context,
                maxOutputTokens: model.limit?.output,
                supportsReasoning: model.reasoning,
                interleavedReasoningField: model.interleaved?.field,
                reasoningEffortValues: model.effortValues,
                declaresNoEffortTiers: model.declaresNoEffortTiers
            )
            // [T-thinking-off-custom-provider] These models ARE the catalog entry for
            // this provider — matched by base URL — so their declarations describe the
            // endpoint being called and may suppress an explicit "thinking off".
            built.effortDeclarationIsAuthoritative = (model.effortValues != nil) ? true : nil
            return built
        }
    }

    /// Extract the full hostname from a URL string.
    /// e.g. "https://coding.dashscope.aliyuncs.com/v1" → "coding.dashscope.aliyuncs.com"
    private static func extractHost(from urlString: String) -> String? {
        URL(string: stripTrailingSlash(urlString))?.host?.lowercased()
    }

    // MARK: - Public: Enrich a single model with models.dev data

    /// Enrich an LLMModel with capabilities from models.dev (modality, context window, output limit).
    /// Looks up by provider name + model ID. Returns the original model if no match found.
    /// Only fills in fields that are currently nil/unset on the model.
    static func enrichModel(_ model: LLMModel) -> LLMModel {
        guard let registry = loadRegistry() else { return model }
        guard let match = resolveDevModel(for: model, in: registry) else { return model }
        return applyDevData(to: model, from: match.model, authoritative: match.authoritative)
    }

    /// [T-modelsdev-id-normalization] Normalize a model id for catalog lookup.
    ///
    /// Relays publish the same model under many spellings — `glm-5.2`,
    /// `z-ai/glm-5.2`, `zai-org/GLM-5.2`, `@cf/…/…`, `accounts/fireworks/models/…`.
    /// Exact-id matching made capabilities depend on which spelling the relay
    /// happened to use: verified on-device 2026-08-01, `glm-5.2` resolved to
    /// `["high","max"]` while `z-ai/glm-5.2` resolved to an entry declaring
    /// nothing, so the SAME model answered XHigh with `high` under one id and
    /// `xhigh` under the other.
    ///
    /// Normalization is deliberately conservative: drop the vendor/namespace
    /// path, lowercase, and unify `.` / `_` to `-`. It never merges distinct
    /// families (`glm-5.2` vs `glm-5.1` stay separate) — the last path segment
    /// is the model name in every publisher convention present in the catalog.
    static func normalizedModelKey(_ id: String) -> String {
        let bare = id.split(separator: "/").last.map(String.init) ?? id
        return bare.lowercased()
            .replacingOccurrences(of: ".", with: "-")
            .replacingOccurrences(of: "_", with: "-")
    }

    /// [T-modelsdev-id-normalization] Find the best catalog entry for a model,
    /// deterministically. Shared by `enrichModel` and `enrichModels` so a single
    /// model and a bulk refresh can never disagree about the same id.
    ///
    /// Resolution order, most trustworthy first:
    ///   1. the model's OWN provider (mapped key), exact id then normalized —
    ///      an authoritative statement about this exact endpoint;
    ///   2. every provider, matching on the NORMALIZED id.
    ///
    /// Stage 2 matches normalized rather than exact on purpose. Matching exact
    /// first would defeat the whole fix: each spelling that happens to exist
    /// verbatim in the catalog would resolve to its own entry and keep
    /// disagreeing — `zai-org/GLM-5.2` (`["none","high"]`) vs `GLM-5.2`
    /// (the full ladder) vs `glm-5.2` (`["high","max"]`), all the same model.
    /// Pooling every spelling and choosing once is what makes the answer
    /// independent of which alias the relay happens to publish.
    ///
    /// The publishers genuinely DISAGREE (129 bare names carry conflicting
    /// effort declarations), so there is no authoritative pick — only a stable
    /// and well-supported one. Among candidates that declare effort tiers, the
    /// MOST COMMONLY declared set wins, ties broken by sorted provider key.
    ///
    /// Majority rule beats alphabetical because relays overwhelmingly copy the
    /// vendor's real declaration, so the mode converges on it while a first-key
    /// pick lands on whichever obscure relay sorts first: for `glm-5.2` the
    /// counts are 23x `["high","max"]` (zhipuai's own) vs 4x `["high","xhigh"]`
    /// and 3x others, and for `deepseek-v4-pro` 11x `["high","max"]` (DeepSeek's
    /// own) vs 6x `["high","xhigh"]`. Alphabetical would have picked a
    /// seven-tier outlier for both.
    /// [T-thinking-off-custom-provider] Carries WHERE the match came from alongside it.
    /// `authoritative` is true only for a stage-1 hit (the model's own provider); the
    /// stage-2 cross-provider vote is a guess about a different endpoint and callers must
    /// not treat it as a statement about this one.
    private struct DevModelMatch {
        let model: ModelsDevModel
        let authoritative: Bool
    }

    private static func resolveDevModel(
        for model: LLMModel, in registry: [String: ModelsDevProvider]
    ) -> DevModelMatch? {
        let wanted = normalizedModelKey(model.id)

        // Stage 1 is left as a direct scan on purpose: it only ever touches the
        // model's OWN provider (≤339 models for the widest, openrouter) and its
        // exact-id hit is a plain dictionary lookup. Indexing it would buy
        // nothing measurable while adding a second structure to keep coherent.
        for key in providerKeyMap[model.provider] ?? [] {
            guard let prov = registry[key] else { continue }
            if let devModel = prov.models[model.id] {
                return DevModelMatch(model: devModel, authoritative: true)
            }
            for id in prov.models.keys.sorted() where normalizedModelKey(id) == wanted {
                if let devModel = prov.models[id] {
                    return DevModelMatch(model: devModel, authoritative: true)
                }
            }
        }

        // Stage 2 was the launch hot spot: the cross-provider fallback used to
        // re-sort all 182 provider keys, re-sort each provider's model keys, and
        // re-run `normalizedModelKey` over all 6,243 catalog ids — for EVERY
        // model being enriched. Measured 1,418ms of a 8,971ms launch on an
        // iPhone 11 (Time Profiler, 2026-08-11), of which 997ms was
        // `normalizedModelKey` alone recomputing a value that cannot change
        // while the registry is unchanged.
        //
        // The scan is now precomputed once into `stage2Index` and reduced to a
        // single dictionary lookup. The RESOLUTION SEMANTICS ARE UNCHANGED: the
        // index stores the winner picked by exactly the vote below, evaluated in
        // exactly the old scan order (see `buildStage2Index`).
        guard let index = stage2Index(for: registry) else { return nil }
        return index[wanted]
    }

    /// The stage-2 winner for every normalized id in the catalog.
    ///
    /// Keyed by `normalizedModelKey`; the value is the same `DevModelMatch` the
    /// old inline scan produced, so callers cannot tell the difference.
    private static var cachedStage2Index: [String: DevModelMatch]?
    private static var stage2IndexBuiltFrom: Date?

    /// Build (and memoize) the stage-2 index. Rebuilt whenever the registry
    /// timestamp moves, so a background models.dev refresh is picked up without
    /// a relaunch — same invalidation rule as `releaseIndex()`.
    private static func stage2Index(for registry: [String: ModelsDevProvider]) -> [String: DevModelMatch]? {
        if let cached = cachedStage2Index, stage2IndexBuiltFrom == cacheTimestamp {
            return cached
        }
        let index = buildStage2Index(registry)
        cachedStage2Index = index
        stage2IndexBuiltFrom = cacheTimestamp
        logger.info("[ModelsDev] stage-2 index built: \(index.count) normalized keys")
        return index
    }

    /// Group every catalog entry by normalized id, then resolve each group with
    /// the original majority-vote rule.
    ///
    /// Grouping walks providers and model ids in the SAME sorted order the old
    /// scan used, so each group's candidate list is byte-for-byte the list the
    /// old code built — which is what makes the vote's "first-seen wins on a
    /// tie" tie-break resolve identically.
    private static func buildStage2Index(_ registry: [String: ModelsDevProvider]) -> [String: DevModelMatch] {
        var grouped: [String: [ModelsDevModel]] = [:]
        for key in registry.keys.sorted() {
            guard let prov = registry[key] else { continue }
            for id in prov.models.keys.sorted() {
                guard let devModel = prov.models[id] else { continue }
                grouped[normalizedModelKey(id), default: []].append(devModel)
            }
        }

        var index: [String: DevModelMatch] = [:]
        index.reserveCapacity(grouped.count)
        for (normalized, candidates) in grouped {
            // Majority vote over the declared effort sets (see doc comment on
            // `resolveDevModel`). The scan order above is deterministic, so equal
            // counts resolve to the first-seen set and the whole result is
            // reproducible across launches.
            //
            // Everything here is NON-authoritative: it describes whichever
            // publishers happen to serve this bare id, not the endpoint being
            // called.
            let declaring = candidates.filter { $0.effortValues != nil }
            guard !declaring.isEmpty else {
                if let first = candidates.first {
                    index[normalized] = DevModelMatch(model: first, authoritative: false)
                }
                continue
            }
            var counts: [[String]: Int] = [:]
            for candidate in declaring {
                guard let values = candidate.effortValues else { continue }
                counts[values, default: 0] += 1
            }
            var winner: [String]?
            var winnerCount = 0
            for candidate in declaring {
                guard let values = candidate.effortValues else { continue }
                let count = counts[values] ?? 0
                if count > winnerCount {
                    winnerCount = count
                    winner = values
                }
            }
            let picked = declaring.first { $0.effortValues == winner } ?? declaring[0]
            index[normalized] = DevModelMatch(model: picked, authoritative: false)
        }
        return index
    }

    /// Enrich an array of models in bulk.
    static func enrichModels(_ models: [LLMModel]) -> [LLMModel] {
        guard let registry = loadRegistry() else { return models }
        return models.map { model in
            // [T-modelsdev-id-normalization] Same resolver as the single-model
            // path: own provider → exact id → normalized id, each stage picking
            // deterministically and preferring an entry that declares effort
            // tiers. The fallback scan is what third-party gateways actually
            // hit, since a relay's provider name matches no catalog key.
            guard let match = resolveDevModel(for: model, in: registry) else { return model }
            return applyDevData(to: model, from: match.model, authoritative: match.authoritative)
        }
    }

    // MARK: - Apply models.dev data to LLMModel

    private static func applyDevData(
        to model: LLMModel, from devModel: ModelsDevModel, authoritative: Bool = false
    ) -> LLMModel {
        // The account-scoped Codex protocol catalog is authoritative. Replacing
        // its new effort tiers/context with a lagging global catalog caused new
        // models to disappear or issue invalid requests after enrichment.
        if model.codexCatalogMetadata == true { return model }
        var result = model

        // Modality: models.dev is the source of truth — always apply when available.
        // This overrides both provider-level defaults and API-parsed modalities,
        // since models.dev has accurate per-model data (e.g. pdf support distinctions).
        if let devModality = devModel.resolvedModality {
            result.modalityOverride = devModality
        }

        // Context window: models.dev is the source of truth
        if let ctx = devModel.limit?.context {
            result.contextWindow = ctx
        }

        // Max output tokens: models.dev is the source of truth
        if let out = devModel.limit?.output {
            result.maxOutputTokens = out
        }

        // Reasoning capability
        if let reasoning = devModel.reasoning {
            result.supportsReasoning = reasoning
        }

        // Interleaved reasoning field (e.g. "reasoning_content" for DeepSeek/Kimi)
        if let field = devModel.interleaved?.field {
            result.interleavedReasoningField = field
        }

        // [T-reasoning-effort-data-driven] Declared effort tiers — drives both
        // "is this model effort-controlled?" and the allowed-tier clamp.
        if let efforts = devModel.effortValues {
            result.reasoningEffortValues = efforts
            // [T-thinking-off-custom-provider] Record whether this came from the model's
            // OWN provider. Only that may suppress an explicit "thinking off".
            result.effortDeclarationIsAuthoritative = authoritative
        }
        // [OpenMinis#163] Carry the affirmative "no effort tiers" answer too.
        // Only set when the catalog actually says so, so enriching a model the
        // catalog is silent about cannot overwrite a prior real answer with a
        // meaningless `false`.
        if devModel.declaresNoEffortTiers {
            result.declaresNoEffortTiers = true
        }

        return result
    }

    // MARK: - URL Matching Helpers

    private static func normalizedCandidates(for url: String) -> [String] {
        let stripped = stripTrailingSlash(url)
        var results = [stripped]
        if stripped.hasSuffix("/v1") {
            results.append(String(stripped.dropLast(3)))
        } else {
            results.append(stripped + "/v1")
        }
        return results
    }

    private static func stripTrailingSlash(_ s: String) -> String {
        var r = s
        while r.hasSuffix("/") { r = String(r.dropLast()) }
        return r
    }

    // MARK: - Registry Cache

    private static var cachedRegistry: [String: ModelsDevProvider]?
    private static var cacheTimestamp: Date?
    /// True while a background network fetch is in flight (prevents concurrent fetches).
    private static var isRefreshing = false

    /// Returns the registry immediately from memory/disk/bundle (never blocks on network).
    /// Triggers a background refresh when the cache is stale (>24h), at most one at a time.
    // MARK: - Release ranking index [T-model-release-ranking]

    /// One catalog entry reduced to just what ranking needs.
    struct ReleaseEntry {
        let date: Date
        let day: Int
        let outputCost: Double?
        let context: Int?
    }

    /// Lookup tables consumed by `ModelReleaseIndex`.
    struct ReleaseIndex {
        /// Keyed by the full catalog id, vendor prefix included.
        let byFullId: [String: ReleaseEntry]
        /// Keyed by the id's last `/` segment.
        let byTail: [String: ReleaseEntry]
    }

    private static var cachedReleaseIndex: ReleaseIndex?
    private static var releaseIndexBuiltFrom: Date?

    /// Build (and memoize) the release index from whatever registry is loaded.
    ///
    /// Rebuilt whenever the underlying registry timestamp moves, so a background
    /// refresh of models.dev is picked up without a relaunch.
    static func releaseIndex() -> ReleaseIndex? {
        guard let registry = loadRegistry() else { return nil }
        if let cached = cachedReleaseIndex, releaseIndexBuiltFrom == cacheTimestamp {
            return cached
        }
        var byFullId: [String: ReleaseEntry] = [:]
        var byTail: [String: ReleaseEntry] = [:]
        for provider in registry.values {
            for (rawId, model) in provider.models {
                guard let raw = model.releaseDate,
                      let parsed = ModelReleaseIndex.parseReleaseDate(raw) else { continue }
                let entry = ReleaseEntry(
                    date: parsed.date,
                    day: parsed.day,
                    outputCost: model.cost?.output,
                    context: model.limit?.context
                )
                let full = rawId.lowercased()
                // The same model is republished by many providers (glm-5.2
                // appears under 24) and their dates disagree. Keep the NEWEST:
                // a relay that lags the vendor must not drag a current model
                // down the list.
                if let existing = byFullId[full], existing.day >= entry.day {} else { byFullId[full] = entry }
                let tail = full.split(separator: "/").last.map(String.init) ?? full
                if let existing = byTail[tail], existing.day >= entry.day {} else { byTail[tail] = entry }
            }
        }
        let index = ReleaseIndex(byFullId: byFullId, byTail: byTail)
        cachedReleaseIndex = index
        releaseIndexBuiltFrom = cacheTimestamp
        logger.info("[ModelRank] release index built: full=\(byFullId.count) tail=\(byTail.count)")
        return index
    }

    private static func loadRegistry() -> [String: ModelsDevProvider]? {
        // 1. In-memory cache (fresh)
        if let cached = cachedRegistry, let ts = cacheTimestamp,
           Date().timeIntervalSince(ts) < cacheTTL {
            return cached
        }

        // 2. In-memory cache exists but stale — return it, schedule refresh
        if let cached = cachedRegistry {
            scheduleBackgroundRefresh()
            return cached
        }

        // 3. Disk cache (downloaded data) — fallback to bundled on parse failure
        if let (diskData, diskDate) = loadDiskCache() {
            if let parsed = parseRegistry(diskData) {
                cachedRegistry = parsed
                cacheTimestamp = diskDate
                if Date().timeIntervalSince(diskDate) >= cacheTTL {
                    scheduleBackgroundRefresh()
                }
                return parsed
            } else {
                logger.error("Downloaded models.dev cache failed to parse, falling back to bundled")
            }
        }

        // 4. Bundled fallback — must always succeed
        if let bundled = loadBundledRegistry() {
            cachedRegistry = bundled
            cacheTimestamp = Date() // Treat as fresh to avoid repeated bundled loads within same session
            scheduleBackgroundRefresh()
            return bundled
        }

        return nil
    }

    /// Schedule a background refresh if one isn't already in flight.
    private static func scheduleBackgroundRefresh() {
        guard !isRefreshing else { return }
        isRefreshing = true
        Task.detached(priority: .utility) {
            await refreshFromNetwork()
        }
    }

    private static func refreshFromNetwork() async {
        defer { isRefreshing = false }
        do {
            guard let url = URL(string: sourceURL) else { return }
            var request = URLRequest(url: url)
            request.timeoutInterval = 15
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                logger.error("models.dev HTTP error: \((response as? HTTPURLResponse)?.statusCode ?? -1)")
                return
            }
            if let parsed = parseRegistry(data) {
                cachedRegistry = parsed
                cacheTimestamp = Date()
                saveDiskCache(data)
                logger.info("Background-refreshed models.dev registry: \(parsed.count) providers")
            }
        } catch {
            logger.error("Failed to fetch models.dev: \(error.localizedDescription)")
        }
    }

    private static func parseRegistry(_ data: Data) -> [String: ModelsDevProvider]? {
        do {
            return try JSONDecoder().decode([String: ModelsDevProvider].self, from: data)
        } catch {
            logger.error("Failed to parse models.dev JSON (\(data.count) bytes): \(error)")
            return nil
        }
    }

    // MARK: - Bundled Fallback

    private static func loadBundledRegistry() -> [String: ModelsDevProvider]? {
        guard let url = Bundle.main.url(forResource: "models-dev-api", withExtension: "json") else {
            logger.error("Bundled models-dev-api.json not found in bundle")
            assertionFailure("Bundled models-dev-api.json missing from app bundle")
            return nil
        }
        guard let data = try? Data(contentsOf: url) else {
            logger.error("Bundled models-dev-api.json failed to read: \(url.path)")
            assertionFailure("Bundled models-dev-api.json unreadable")
            return nil
        }
        guard let parsed = parseRegistry(data) else {
            logger.error("Bundled models-dev-api.json failed to parse (\(data.count) bytes)")
            assertionFailure("Bundled models-dev-api.json failed to parse — update the bundled file or fix ModelsDevModel decoding")
            return nil
        }
        logger.info("Loaded bundled models.dev registry: \(parsed.count) providers")
        return parsed
    }

    // MARK: - Disk Cache

    private static var cacheFileURL: URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        return caches.appendingPathComponent("com.openminis.app.models-dev-cache").appendingPathComponent("api.json")
    }

    private static func loadDiskCache() -> (Data, Date)? {
        let url = cacheFileURL
        guard FileManager.default.fileExists(atPath: url.path),
              let data = try? Data(contentsOf: url),
              let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              let modified = attrs[.modificationDate] as? Date else { return nil }
        return (data, modified)
    }

    private static func saveDiskCache(_ data: Data) {
        let url = cacheFileURL
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url, options: .atomic)
    }
}

// MARK: - models.dev JSON Models

private struct ModelsDevProvider: Decodable {
    let id: String
    let name: String?
    let api: String?
    let models: [String: ModelsDevModel]

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        api = try container.decodeIfPresent(String.self, forKey: .api)
        models = try container.decodeIfPresent([String: ModelsDevModel].self, forKey: .models) ?? [:]
    }

    enum CodingKeys: String, CodingKey {
        case id, name, api, models
    }
}

private struct ModelsDevModel: Decodable {
    let id: String
    let name: String?
    let family: String?
    let modalities: ModelsDevModalities?
    let limit: ModelsDevLimit?
    let reasoning: Bool?
    let interleaved: ModelsDevInterleaved?
    let reasoningOptions: [ModelsDevReasoningOption]?
    /// [T-model-release-ranking] Raw `release_date`. models.dev populates this
    /// for 100% of entries, but NOT always as a full ISO day: 181 entries in
    /// the bundled catalog carry `YYYY-MM` only. Parsed leniently by
    /// `releaseSortKey` — a strict `YYYY-MM-DD` decode would silently drop
    /// those models to the bottom of every sorted list.
    let releaseDate: String?
    /// [T-model-release-ranking] Per-million-token pricing. `output` is the
    /// tie-breaker for models that share a release date: on 2026-07-09 OpenAI
    /// shipped sol/terra/luna together, and output price (30 / 12 / 1.2) is
    /// what actually separates their capability tiers.
    let cost: ModelsDevCost?

    /// [T-reasoning-effort-data-driven] Effort tiers declared by the catalog,
    /// or nil when this model exposes no `effort`-type reasoning option (it may
    /// still declare `toggle` / `budget_tokens`, which are different mechanisms
    /// and must NOT be treated as effort support).
    var effortValues: [String]? {
        guard let opts = reasoningOptions else { return nil }
        let values = opts
            .first { $0.type == "effort" }?
            .values?
            .map { $0.lowercased() }
        guard let values, !values.isEmpty else { return nil }
        return values
    }

    /// [OpenMinis#163] True when the catalog AFFIRMATIVELY says this model has
    /// no effort tiers, as opposed to saying nothing at all.
    ///
    /// `effortValues` collapses both cases to nil, which loses the difference
    /// that matters on the wire:
    ///   • `reasoning_options` absent/null → the catalog has no opinion. Stay
    ///     permissive and keep sending `reasoning_effort`, because third-party
    ///     relays serve models the catalog has never heard of.
    ///   • `reasoning_options` PRESENT but carrying no usable `effort` entry
    ///     (`[]`, or an effort entry whose `values` is empty) → the catalog is
    ///     stating this model reasons WITHOUT an effort parameter. Sending the
    ///     field is a hard 400: xAI `grok-build-0.1` and
    ///     `grok-4.20-0309-reasoning` both ship `"reasoning": true` with
    ///     `"reasoning_options": []` and reject `reasoningEffort`.
    ///
    /// Note this is deliberately NOT "reasoningOptions is empty": a model that
    /// declares only `toggle` or `budget_tokens` is also affirmatively saying
    /// "not effort-controlled", and must be treated the same way.
    var declaresNoEffortTiers: Bool {
        guard reasoningOptions != nil else { return false }
        return effortValues == nil
    }

    // Explicit keys: this type uses synthesized decoding with no
    // keyDecodingStrategy, so the snake_case JSON key must be mapped by hand.
    enum CodingKeys: String, CodingKey {
        case id, name, family, modalities, limit, reasoning, interleaved, cost
        case reasoningOptions = "reasoning_options"
        case releaseDate = "release_date"
    }

    /// Convert models.dev modalities to app ModelModality.
    var resolvedModality: ModelModality? {
        guard let mod = modalities else { return nil }
        var result: ModelModality = []
        let inp = mod.input ?? []
        let out = mod.output ?? []
        if inp.contains("text")  { result.insert(.textInput) }
        if inp.contains("image") { result.insert(.imageInput) }
        if inp.contains("pdf")   { result.insert(.pdfInput) }
        if inp.contains("audio") { result.insert(.audioInput) }
        if inp.contains("video") { result.insert(.videoInput) }
        if out.contains("text")  { result.insert(.textOutput) }
        if out.contains("image") { result.insert(.imageOutput) }
        if out.contains("audio") { result.insert(.audioOutput) }
        if out.contains("video") { result.insert(.videoOutput) }
        return result.isEmpty ? nil : result
    }
}

private struct ModelsDevModalities: Decodable {
    let input: [String]?
    let output: [String]?
}

private struct ModelsDevLimit: Decodable {
    let context: Int?
    let output: Int?
}

/// [T-model-release-ranking] models.dev `cost` block (USD per million tokens).
/// Several entries omit it entirely (free/local models), and some publish only
/// a subset of the keys, so every field is optional.
private struct ModelsDevCost: Decodable {
    let input: Double?
    let output: Double?
}

/// `interleaved` can be either a bool (`true`) or an object (`{"field": "reasoning_content"}`).
/// [T-reasoning-effort-data-driven] One entry of models.dev `reasoning_options`.
/// Observed shapes in the bundled catalog: `{"type":"toggle"}`,
/// `{"type":"effort","values":[…]}`, `{"type":"budget_tokens","max":131072}`.
/// Only `type` is always present, so every other field is optional.
private struct ModelsDevReasoningOption: Decodable {
    let type: String?
    let values: [String]?

    // `values` is NOT a clean [String] in the wild: models.dev ships null
    // elements (sarvam-105b / sarvam-30b are `[null,"low","medium","high"]`).
    // Decoding straight into [String] throws valueNotFound, and because the
    // whole registry decodes as one document a single bad element takes down
    // the ENTIRE catalog — which trips the `Bundled models-dev-api.json failed
    // to parse` fatalError at launch. Caught on-device (iPhone 11) rather than
    // in the build, since the payload only matters at runtime.
    //
    // Decode element-wise and drop anything that isn't a string, so one
    // malformed entry degrades to "this model declares fewer tiers" instead of
    // bricking the app. The catalog is refreshed from the network, so this has
    // to tolerate whatever upstream publishes, not just today's snapshot.
    enum CodingKeys: String, CodingKey { case type, values }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        type = try container.decodeIfPresent(String.self, forKey: .type)
        guard var list = try? container.nestedUnkeyedContainer(forKey: .values) else {
            values = nil
            return
        }
        var parsed: [String] = []
        while !list.isAtEnd {
            if let s = try? list.decode(String.self) {
                parsed.append(s)
            } else {
                // Consume the slot so the cursor advances past null / number /
                // object; skipping this would spin forever on a non-string.
                _ = try? list.decode(AnyDecodableSkip.self)
            }
        }
        values = parsed.isEmpty ? nil : parsed
    }
}

/// Consumes exactly one value of unknown type so an unkeyed container can skip
/// past elements it can't use.
private struct AnyDecodableSkip: Decodable {
    init(from decoder: Decoder) throws {
        if let c = try? decoder.singleValueContainer(), c.decodeNil() { return }
    }
}

private struct ModelsDevInterleaved: Decodable {
    let field: String?

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        // Try object first
        if let dict = try? container.decode([String: String].self) {
            field = dict["field"]
        } else if let flag = try? container.decode(Bool.self) {
            // bool true → default field name "reasoning_content"
            field = flag ? "reasoning_content" : nil
        } else {
            field = nil
        }
    }
}
