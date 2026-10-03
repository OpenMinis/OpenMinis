import Foundation

/// [T-copilot-provider] Model discovery for GitHub Copilot.
///
/// Not routed through `OpenAIModelsAPI` even though the chat surface is
/// OpenAI-compatible, for two reasons the generic path cannot express:
///  1. `/models` needs the editor identity headers, or Copilot rejects the call.
///  2. The response is richer than OpenAI's — each entry carries capability and
///     policy fields, notably `model_picker_enabled`, which says whether the
///     account is actually allowed to choose that model. Offering a model the
///     server has hidden produces a confusing failure at first use, so that
///     flag is honoured here rather than shipping a hard-coded list.
enum CopilotModelsAPI {

    private static let logger = AppLogger(category: "CopilotModels")

    /// Copilot's `/models` payload. Only the fields that affect what we show.
    private struct ModelsResponse: Decodable {
        struct Entry: Decodable {
            let id: String
            let name: String?
            let modelPickerEnabled: Bool?
            let capabilities: Capabilities?

            struct Capabilities: Decodable {
                let type: String?
                let limits: Limits?
                let supports: Supports?

                struct Limits: Decodable {
                    let maxContextWindowTokens: Int?
                    let maxOutputTokens: Int?
                    enum CodingKeys: String, CodingKey {
                        case maxContextWindowTokens = "max_context_window_tokens"
                        case maxOutputTokens = "max_output_tokens"
                    }
                }
                struct Supports: Decodable {
                    let vision: Bool?
                    /// [T-copilot-reasoning-fields] Copilot does NOT send this.
                    ///
                    /// The original implementation read it as "the" reasoning
                    /// flag, so `supportsReasoning` was always nil — "unknown" —
                    /// and every thinking gate in the app tests `== true`. The
                    /// toggle therefore never appeared, or (against a stale
                    /// catalogue that claimed reasoning) appeared while the
                    /// request went out carrying no reasoning parameter at all.
                    ///
                    /// Kept as a fallback rather than deleted: it costs one
                    /// optional decode, and if Copilot ever does emit it, the
                    /// value is the most direct statement of the capability.
                    /// The real signals are the sibling fields below.
                    let thinking: Bool?
                }

                /// [T-copilot-reasoning-fields] What Copilot actually sends for
                /// a reasoning-capable model, captured from a live payload:
                ///
                ///     "adaptive_thinking": true,
                ///     "max_thinking_budget": 32000,
                ///     "reasoning_effort": ["low","medium","high","xhigh","max"]
                ///
                /// These sit on `capabilities`, NOT inside `supports`.
                let adaptiveThinking: Bool?
                let maxThinkingBudget: Int?
                let reasoningEffort: [String]?

                enum CodingKeys: String, CodingKey {
                    case type, limits, supports
                    case adaptiveThinking = "adaptive_thinking"
                    case maxThinkingBudget = "max_thinking_budget"
                    case reasoningEffort = "reasoning_effort"
                }
            }
            enum CodingKeys: String, CodingKey {
                case id, name, capabilities
                case modelPickerEnabled = "model_picker_enabled"
            }
        }
        let data: [Entry]
    }

    /// Fetch the models this account may actually use.
    static func fetchModels(sessionToken: String) async throws -> [LLMModel] {
        guard let url = URL(string: CopilotConstants.apiBaseURL + "/models") else {
            throw LLMError.providerError(message: "Bad Copilot models URL")
        }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
        for (k, v) in CopilotConstants.baseHeaders { req.setValue(v, forHTTPHeaderField: k) }
        req.timeoutInterval = 30

        let (data, response) = try await URLSession.shared.data(for: req)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200...299).contains(code) else {
            throw LLMError.providerError(message: "Copilot /models returned HTTP \(code)")
        }
        guard let parsed = try? JSONDecoder().decode(ModelsResponse.self, from: data) else {
            throw LLMError.providerError(message: "Could not read the Copilot model list")
        }
        // [T-copilot-models-raw-caps] One bounded line per fetch showing the
        // RAW `capabilities` object of the first chat-type entry. The typed
        // decode above only keeps the keys it knows; when GitHub renames or
        // nests a capability (they have, more than once) every model quietly
        // decodes as "no reasoning, text-only" and the only clue is a wrong
        // picker. Seeing the actual shape in a Release log is what makes the
        // next such drift a one-line fix instead of a field report.
        if let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let arr = root["data"] as? [[String: Any]],
           let first = arr.first(where: { ($0["capabilities"] as? [String: Any])?["type"] as? String == "chat" }) ?? arr.first,
           let caps = first["capabilities"],
           let capData = try? JSONSerialization.data(withJSONObject: caps),
           let capStr = String(data: capData, encoding: .utf8) {
            logger.info("[Copilot] raw capabilities sample id=\(first["id"] ?? "?"): \(capStr.prefix(400))")
        }

        // `model_picker_enabled == nil` is treated as visible: the field is
        // absent on older responses, and hiding everything on an unknown shape
        // would look like "no models" rather than a parsing gap.
        var seen = Set<String>()
        // [T-copilot-model-filter-diagnostics] Record what each filter removes.
        //
        // Both filters below can legitimately empty the list, and an empty list
        // is indistinguishable from a broken request from the outside — the
        // built-in list for Copilot is deliberately empty and models.dev has no
        // entry for `api.githubcopilot.com`, so there is no fallback to mask it.
        // That matters most for a FREE account, which is reported to be limited
        // to the auto-select model: if that entry's `capabilities.type` is
        // anything other than "chat", the second filter would silently discard
        // the only model such an account can use, and the symptom would be "no
        // models" with no clue why.
        //
        // So the drops are logged with their ids and types rather than the
        // filters being loosened on a guess. One line, only when it drops
        // something.
        var hiddenByPolicy: [String] = []
        var droppedByType: [String] = []
        var reasoningCapable: [String] = []
        let models: [LLMModel] = parsed.data.compactMap { entry in
            guard entry.modelPickerEnabled ?? true else {
                hiddenByPolicy.append(entry.id)
                return nil
            }
            // Only chat models; Copilot also lists embeddings, which cannot
            // serve a conversation.
            if let type = entry.capabilities?.type, type != "chat" {
                droppedByType.append("\(entry.id):\(type)")
                return nil
            }
            guard seen.insert(entry.id).inserted else { return nil }
            let supports = entry.capabilities?.supports
            let caps = entry.capabilities

            // [T-copilot-reasoning-fields] Derive the capability from whichever
            // signal is present, in order of directness. Any ONE of them makes
            // the model reasoning-capable — they are alternative statements of
            // the same fact, not conditions to be met together.
            //
            // Deliberately resolves to a definite `false` rather than nil when
            // Copilot answers and names none of them: nil means "unknown", and
            // an unknown here is what produced the original symptom.
            let effortTiers = caps?.reasoningEffort?.filter { !$0.isEmpty }
            let reasoning: Bool = (supports?.thinking ?? false)
                || (caps?.adaptiveThinking ?? false)
                || ((caps?.maxThinkingBudget ?? 0) > 0)
                || !(effortTiers?.isEmpty ?? true)
            if reasoning { reasoningCapable.append(entry.id) }

            return LLMModel(
                id: entry.id,
                displayName: entry.name ?? entry.id,
                provider: "GitHub Copilot",
                modalityOverride: (supports?.vision ?? false) ? .vision : .textOnly,
                contextWindow: caps?.limits?.maxContextWindowTokens,
                maxOutputTokens: caps?.limits?.maxOutputTokens,
                supportsReasoning: reasoning,
                // Carry the tiers Copilot itself lists so the request builder
                // clamps onto the set this model accepts, instead of sending a
                // level from the app's generic ladder that Copilot may reject.
                reasoningEffortValues: effortTiers
            )
        }
        logger.info("[Copilot] \(models.count) selectable models of \(parsed.data.count) returned; ids=[\(models.map(\.id).joined(separator: ","))]")
        if !hiddenByPolicy.isEmpty {
            logger.info("[Copilot] hidden by model_picker_enabled=false: \(hiddenByPolicy.joined(separator: ","))")
        }
        if !droppedByType.isEmpty {
            logger.info("[Copilot] dropped by capabilities.type != chat: \(droppedByType.joined(separator: ","))")
        }
        // [T-copilot-reasoning-fields] Log which models came back
        // reasoning-capable. The bug this replaces was invisible precisely
        // because "no thinking toggle" and "no reasoning-capable model" look
        // identical from the outside.
        logger.info("[Copilot] reasoning-capable: \(reasoningCapable.isEmpty ? "none" : reasoningCapable.joined(separator: ","))")
        if models.isEmpty {
            // There is no fallback behind this, so say so loudly.
            logger.error("[Copilot] model list is EMPTY after filtering — \(parsed.data.count) entries returned by /models")
        }
        return models
    }
}
