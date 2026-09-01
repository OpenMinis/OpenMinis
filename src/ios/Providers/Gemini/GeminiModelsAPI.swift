import Foundation
import CryptoKit
import os.log

private let logger = AppLogger(category: "GeminiModelsAPI")

enum GeminiModelsAPI {

    private static let defaultBaseURL = "https://generativelanguage.googleapis.com/v1beta/models"

    static func fetchModels(apiKey: String, customBaseURL: String? = nil, forceRefresh: Bool = false) async throws -> [LLMModel] {
        if !forceRefresh, let cached = GeminiModelsCache.load(credential: apiKey) {
            logger.info("Returning \(cached.count) cached models (API key)")
            return cached
        }

        let modelsURL = customBaseURL.map { base -> String in
            // Trim trailing slashes to detect a /models suffix reliably.
            var trimmed = base
            while trimmed.hasSuffix("/") { trimmed.removeLast() }
            return trimmed.hasSuffix("/models") ? trimmed : URLBuilding.join(trimmed, "/models")
        } ?? defaultBaseURL
        // [T-ios-gemini-baseurl-force-unwrap] `modelsURL` is built from the user's
        // editable Base URL, so it is untrusted input and MUST NOT be force-unwrapped.
        // A single space, full-width punctuation, or an invisible bidi character
        // (all easy to introduce by pasting or via an IME) makes URLComponents
        // return nil, and the trap crashed the app at LAUNCH, because
        // autoRefreshModels runs on startup — an unrecoverable boot loop that the
        // user cannot escape from inside the app.
        //
        // Throwing matches what OpenAIModelsAPI/AnthropicModelsAPI already do for
        // the same input; only this path was still force-unwrapping. The refresh
        // then fails softly and the existing model list is kept.
        guard var components = URLComponents(string: modelsURL) else {
            throw LLMError.providerError(message: "Invalid Gemini base URL: \(modelsURL)")
        }
        components.queryItems = [URLQueryItem(name: "key", value: apiKey)]

        // Also guarded: `components.url` is a SECOND failure point. Percent-encoding
        // rules differ between the parser and the serializer, so a string that parsed
        // fine can still fail to reassemble once query items are attached.
        guard let url = components.url else {
            throw LLMError.providerError(message: "Invalid Gemini base URL: \(modelsURL)")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        logger.info("Fetching Gemini models (API key auth)")
        let models = try await performFetch(request)
        GeminiModelsCache.save(models, credential: apiKey)
        return models
    }

    static func fetchModels(oauthToken: String, customBaseURL: String? = nil, forceRefresh: Bool = false) async throws -> [LLMModel] {
        if !forceRefresh, let cached = GeminiModelsCache.load(credential: oauthToken) {
            logger.info("Returning \(cached.count) cached models (OAuth)")
            return cached
        }

        let modelsURL = customBaseURL.map { base -> String in
            // Trim trailing slashes to detect a /models suffix reliably.
            var trimmed = base
            while trimmed.hasSuffix("/") { trimmed.removeLast() }
            return trimmed.hasSuffix("/models") ? trimmed : URLBuilding.join(trimmed, "/models")
        } ?? defaultBaseURL
        // [T-ios-gemini-baseurl-force-unwrap] Same untrusted `customBaseURL` as the
        // API-key path above — the OAuth variant crashed identically.
        guard let url = URL(string: modelsURL) else {
            throw LLMError.providerError(message: "Invalid Gemini base URL: \(modelsURL)")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(oauthToken)", forHTTPHeaderField: "Authorization")
        logger.info("Fetching Gemini models (OAuth auth)")
        #if DEBUG
        logger.debug("OAuth token prefix: \(String(oauthToken.prefix(20)))...")
        #endif
        do {
            let models = try await performFetch(request)
            GeminiModelsCache.save(models, credential: oauthToken)
            return models
        } catch {
            // Standard API returns 403 for OAuth clients without generative-language scope.
            // Fall back to built-in model list.
            logger.warning("Standard models API failed for OAuth: \(error.localizedDescription). Using built-in list.")
            return ModelsDevAPI.enrichModels(LLMModel.allGemini)
        }
    }

    /// Fetch models for OAuth users.
    /// Cloud Code Assist has no listModels endpoint and the standard API returns 403 for
    /// this OAuth client (insufficient scopes), so we return the built-in model list directly.
    /// When a custom base URL is set, attempt a real fetch before falling back.
    static func fetchModels(oauthToken: String, gcpProjectID: String, customBaseURL: String? = nil) async throws -> [LLMModel] {
        if let customBaseURL, !customBaseURL.isEmpty {
            logger.info("OAuth + Cloud Code: custom base URL set, attempting real fetch")
            return try await fetchModels(oauthToken: oauthToken, customBaseURL: customBaseURL)
        }
        logger.info("Using built-in Gemini model list (OAuth + Cloud Code Assist)")
        return LLMModel.allGemini
    }

    private static func performFetch(_ request: URLRequest) async throws -> [LLMModel] {
        // Redact API key from logged URL
        let logURL: String
        if let url = request.url, var comps = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            let redacted = comps.queryItems?.map { item in
                item.name == "key" ? URLQueryItem(name: "key", value: "***") : item
            }
            comps.queryItems = redacted
            logURL = comps.string ?? url.absoluteString
        } else {
            logURL = "<nil>"
        }
        logger.info("GET \(logURL)")

        let (data, response) = try await URLSession.shared.data(for: request)
        let http = response as? HTTPURLResponse
        let statusCode = http?.statusCode ?? -1
        let responseBody = String(data: data, encoding: .utf8) ?? "<binary>"

        logger.info("Response status: \(statusCode)")

        guard (200..<300).contains(statusCode) else {
            logger.error("Gemini models API error — status \(statusCode)")
            #if DEBUG
            logger.error("Response body: \(responseBody)")
            #endif
            if statusCode == 401 || statusCode == 403 {
                throw LLMError.invalidAPIKey(detail: "Gemini HTTP \(statusCode): \(String(responseBody.prefix(200)))")
            }
            throw LLMError.providerError(message: "Failed to fetch Gemini models (HTTP \(statusCode)): \(responseBody.prefix(500))")
        }

        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
        guard let modelsArray = json["models"] as? [[String: Any]] else {
            logger.error("Missing 'models' array in response. Keys: \(Array(json.keys).joined(separator: ", "))")
            #if DEBUG
            logger.error("Response body: \(responseBody.prefix(500))")
            #endif
            throw LLMError.decodingError(underlying: NSError(domain: "GeminiModelsAPI", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Missing models array in response"]))
        }

        let models = modelsArray.compactMap { item -> LLMModel? in
            // name format: "models/gemini-2.5-flash"
            guard let fullName = item["name"] as? String else { return nil }
            let id = fullName.replacingOccurrences(of: "models/", with: "")
            let displayName = item["displayName"] as? String ?? id

            // Filter to chat-capable models only
            guard let methods = item["supportedGenerationMethods"] as? [String],
                  methods.contains("generateContent") else { return nil }

            return LLMModel(id: id, displayName: displayName, provider: "Google")
        }

        let enriched = ModelsDevAPI.enrichModels(models)
        logger.info("Fetched \(modelsArray.count) total models, \(enriched.count) chat-capable")
        if let first = modelsArray.first,
           let debugData = try? JSONSerialization.data(withJSONObject: first, options: [.prettyPrinted, .sortedKeys]),
           let debugStr = String(data: debugData, encoding: .utf8) {
            logger.info("First model raw JSON:\n\(debugStr)")
        }
        return enriched
    }
}

// MARK: - Cache

private enum GeminiModelsCache {

    private struct Entry: Codable {
        let models: [LLMModel]
        let date: Date
    }

    private static let ttl: TimeInterval = 7 * 24 * 3600

    private static var cacheDir: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("com.openminis.app.gemini-models-cache", isDirectory: true)
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
