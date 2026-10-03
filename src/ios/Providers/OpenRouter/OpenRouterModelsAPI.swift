import Foundation
import CryptoKit
import os.log

private let logger = AppLogger(category: "OpenRouterModelsAPI")

enum OpenRouterModelsAPI {

    private static let baseURL = "https://openrouter.ai/api/v1"

    static func fetchModels(apiKey: String, forceRefresh: Bool = false) async throws -> [LLMModel] {
        if !forceRefresh, let cached = OpenRouterModelsCache.load(credential: apiKey) {
            logger.info("Returning \(cached.count) cached OpenRouter models")
            return cached
        }

        // [T-openrouter-voice-catalog] OpenMinis#280. The default list holds
        // only chat models; speech and transcription models are listed solely
        // by their output_modalities filters. Fetch all three at once and tag
        // each model with the list it came from.
        async let defaultList = fetchList(path: "/models", apiKey: apiKey)
        async let speechList = fetchList(path: "/models?output_modalities=speech", apiKey: apiKey)
        async let transcriptionList = fetchList(path: "/models?output_modalities=transcription", apiKey: apiKey)

        // The chat catalog is required, so its failure propagates exactly as
        // before. A failed filter endpoint only costs its voice models.
        let chatModels = try await defaultList
        let speech: [LLMModel]
        let transcription: [LLMModel]
        do { speech = try await speechList } catch {
            logger.warning("OpenRouter speech catalog unavailable: \(error.localizedDescription)")
            speech = []
        }
        do { transcription = try await transcriptionList } catch {
            logger.warning("OpenRouter transcription catalog unavailable: \(error.localizedDescription)")
            transcription = []
        }

        let merged = mergeVoiceCatalogs(
            defaultModels: ModelsDevAPI.enrichModels(chatModels),
            speech: speech,
            transcription: transcription)
        logger.info("Fetched OpenRouter models: chat=\(chatModels.count) speech=\(speech.count) transcription=\(transcription.count)")
        OpenRouterModelsCache.save(merged, credential: apiKey)
        return merged
    }

    /// Fetch and parse one `/models` listing.
    private static func fetchList(path: String, apiKey: String) async throws -> [LLMModel] {
        var request = URLRequest(url: URL(string: URLBuilding.join(baseURL, path))!)
        request.httpMethod = "GET"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("https://github.com/OpenMinis/OpenMinis", forHTTPHeaderField: "HTTP-Referer")
        request.setValue("Minis App", forHTTPHeaderField: "X-Title")
        logger.info("Fetching OpenRouter models \(path)")

        let (data, response) = try await URLSession.shared.data(for: request)
        let http = response as? HTTPURLResponse
        let statusCode = http?.statusCode ?? -1

        guard (200..<300).contains(statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? ""
            logger.error("OpenRouter models API error — status \(statusCode) path=\(path)")
            if statusCode == 401 || statusCode == 403 {
                throw LLMError.invalidAPIKey(detail: "OpenRouter HTTP \(statusCode): \(String(body.prefix(200)))")
            }
            throw LLMError.providerError(message: "Failed to fetch OpenRouter models (HTTP \(statusCode)): \(body.prefix(500))")
        }

        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
        guard let modelsArray = json["data"] as? [[String: Any]] else {
            throw LLMError.decodingError(underlying: NSError(domain: "OpenRouterModelsAPI", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Missing data array in response"]))
        }

        return modelsArray.compactMap { item -> LLMModel? in
            guard let id = item["id"] as? String else { return nil }

            let displayName = (item["name"] as? String) ?? modelDisplayName(from: id)

            // Parse modalities from architecture if available. OpenRouter returns
            // suffixed forms (`image_input`, `text_output`); models.dev returns bare
            // forms. Normalize before matching so both shapes resolve to the same
            // ModelModality bits.
            var modality: ModelModality = [.textInput, .textOutput]
            if let arch = item["architecture"] as? [String: Any] {
                if let inputs = arch["input_modalities"] as? [String] {
                    let bare = Set(inputs.map(Self.normalizeModality))
                    if bare.contains("image") { modality.insert(.imageInput) }
                    if bare.contains("pdf")   { modality.insert(.pdfInput) }
                    if bare.contains("audio") { modality.insert(.audioInput) }
                    if bare.contains("video") { modality.insert(.videoInput) }
                }
                if let outputs = arch["output_modalities"] as? [String] {
                    let bare = Set(outputs.map(Self.normalizeModality))
                    if bare.contains("image") { modality.insert(.imageOutput) }
                    if bare.contains("audio") { modality.insert(.audioOutput) }
                    if bare.contains("video") { modality.insert(.videoOutput) }
                }
            }
            let modalityOverride: ModelModality? = modality == [.textInput, .textOutput] ? nil : modality

            // Context window and max output tokens
            let contextWindow = item["context_length"] as? Int
            var maxOutputTokens: Int? = nil
            if let topProvider = item["top_provider"] as? [String: Any] {
                maxOutputTokens = topProvider["max_completion_tokens"] as? Int
            }

            // Reasoning support: check if "reasoning" is in supported_parameters
            var supportsReasoning: Bool? = nil
            if let params = item["supported_parameters"] as? [String] {
                supportsReasoning = params.contains("reasoning")
            }

            return LLMModel(
                id: id, displayName: displayName, provider: "OpenRouter",
                modalityOverride: modalityOverride,
                contextWindow: contextWindow,
                maxOutputTokens: maxOutputTokens,
                supportsReasoning: supportsReasoning
            )
        }
    }

    /// [T-openrouter-voice-catalog] Tag every model with the catalog it came
    /// from and give voice models their exact dedicated shape. The filtered
    /// lists declare `speech` / `transcription` rather than `audio`, so without
    /// the shape they would show no audio at all.
    ///
    ///   default list         → voiceRole "none", modalities as declared
    ///   speech filter        → voiceRole "tts",  text in → audio out
    ///   transcription filter → voiceRole "stt",  audio in → text out
    ///
    /// The filtered lists win an id collision (none today): the filter IS the
    /// authoritative "this is a voice model" signal. Order: chat catalog, then
    /// TTS, then ASR, each as OpenRouter returned it. Mirrors Android
    /// `OpenRouterModelsApi.mergeVoiceCatalogs`.
    static func mergeVoiceCatalogs(defaultModels: [LLMModel],
                                   speech: [LLMModel],
                                   transcription: [LLMModel]) -> [LLMModel] {
        var order: [String] = []
        var byId: [String: LLMModel] = [:]
        func put(_ m: LLMModel) {
            if byId[m.id] == nil { order.append(m.id) }
            byId[m.id] = m
        }
        for var m in defaultModels { m.voiceRole = VoiceRole.none; put(m) }
        for var m in speech {
            m.modalityOverride = [.textInput, .audioOutput]
            m.voiceRole = VoiceRole.tts
            put(m)
        }
        for var m in transcription {
            m.modalityOverride = [.audioInput, .textOutput]
            m.voiceRole = VoiceRole.stt
            put(m)
        }
        return order.compactMap { byId[$0] }
    }

    /// Strip `_input` / `_output` suffix and lowercase. Provider APIs are inconsistent —
    /// OpenRouter returns `image_input` / `text_output` while models.dev returns bare
    /// `image` / `text`. Mirrors Android's `String.normalizeModalityName`.
    static func normalizeModality(_ raw: String) -> String {
        var s = raw.lowercased()
        if s.hasSuffix("_input") { s = String(s.dropLast("_input".count)) }
        if s.hasSuffix("_output") { s = String(s.dropLast("_output".count)) }
        return s
    }
}

// MARK: - Cache

private enum OpenRouterModelsCache {

    private struct Entry: Codable {
        let models: [LLMModel]
        let date: Date
    }

    private static let ttl: TimeInterval = 7 * 24 * 3600

    private static var cacheDir: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            // [T-openrouter-voice-catalog] "-v2": entries saved before the
            // speech / transcription catalogs were merged in hold no voice
            // models and no voiceRole, and would otherwise be served for up to
            // 7 more days — the fix would look like it did nothing.
            .appendingPathComponent("com.openminis.app.openrouter-models-cache-v2", isDirectory: true)
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
