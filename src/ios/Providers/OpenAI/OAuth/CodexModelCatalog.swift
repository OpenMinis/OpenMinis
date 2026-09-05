import Foundation

/// Wire schema for the account-scoped Codex catalog, not the public /v1/models
/// API. Ignore unknown fields so future models do not require an app release.
enum CodexModelCatalog {
    // Protocol compatibility baseline verified against OpenAI's 0.153.4 release.
    // https://github.com/openai/codex/releases/tag/rust-v0.153.4
    static let clientVersion = "0.153.4"
    static let modelsURL = URL(string: "https://chatgpt.com/backend-api/codex/models?client_version=\(clientVersion)")!

    struct Entry: Equatable, Sendable {
        let id: String
        let displayName: String
        let contextWindow: Int?
        let inputModalities: [String]?
        let reasoningEfforts: [String]?
        let priority: Int
    }

    static func parse(_ data: Data) throws -> [Entry] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let models = object["models"] as? [[String: Any]] else {
            throw NSError(domain: "CodexModelCatalog", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "Missing Codex models array"])
        }
        var seen = Set<String>()
        return models.compactMap { model -> Entry? in
            guard let id = model["slug"] as? String, !id.isEmpty,
                  model["visibility"] as? String == "list", seen.insert(id).inserted else { return nil }
            // supported_in_api is about API-key availability, not this user's
            // ChatGPT OAuth entitlement; never use it to filter this catalog.
            let name = model["display_name"] as? String
            let rawEfforts = model["supported_reasoning_levels"] as? [[String: Any]]
            let efforts = rawEfforts.map { levels in
                var seenEfforts = Set<String>()
                return levels.compactMap { ($0["effort"] as? String)?.lowercased() }
                    .filter { !$0.isEmpty && seenEfforts.insert($0).inserted }
            }
            let context = (model["context_window"] as? Int) ?? (model["max_context_window"] as? Int)
            return Entry(
                id: id,
                displayName: name.flatMap { $0.isEmpty ? nil : $0 } ?? id,
                contextWindow: context.flatMap { $0 > 0 ? $0 : nil },
                inputModalities: model["input_modalities"] as? [String],
                reasoningEfforts: efforts,
                priority: model["priority"] as? Int ?? Int.max
            )
        }.sorted { $0.priority == $1.priority ? $0.id < $1.id : $0.priority < $1.priority }
    }

    /// Choose a valid level without silently turning the model's mandatory
    /// reasoning off. Preserve new server effort strings if requested exactly.
    static func resolvedEffort(requested: String, supported: [String]?) -> String {
        guard let supported, !supported.isEmpty else { return requested }
        if supported.contains(requested) { return requested }
        let order = ["none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"]
        guard let rank = order.firstIndex(of: requested) else { return supported[0] }
        let known = supported.compactMap { value -> (String, Int)? in
            guard let index = order.firstIndex(of: value) else { return nil }
            return (value, index)
        }.sorted { $0.1 < $1.1 }
        return known.last(where: { $0.1 <= rank })?.0 ?? known.first?.0 ?? supported[0]
    }
}
