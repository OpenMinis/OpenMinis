import Foundation

/// Exposes ModelEntry overrides under `models.<entryUUID>.…`.
///
/// `add` creates a custom entry (isCustom=true). `remove` is allowed
/// only for those custom entries — API-reported models can be hidden
/// (`isHidden=true`) but never deleted, since the same id may reappear
/// after the next refresh and any prior hide/override should resume.
@MainActor
struct ModelsCollection: ConfigCollection {
    let basePath = "models"
    let displayName = "LLM models"
    let description = "Per-model overrides (display name, max output tokens, hidden flag) and custom-model add/remove."
    let addable = true
    let removable = true
    let risk: ConfigRisk = .sensitive

    /// Add payload schema:
    /// {
    ///   "instance_id": "<provider instance uuid>",
    ///   "model_id":    "gpt-4o",
    ///   "display_name": "GPT 4o (custom)",        // optional
    ///   "context_window": 128000,                  // optional int
    ///   "max_output_tokens": 4096,                 // optional int
    ///   "supports_reasoning": false                // optional bool
    /// }
    let addPayloadSchema: ConfigValueSchema = .json

    func childIds() -> [String] {
        ProviderConfigStore.shared.config.modelEntries.map(\.id)
    }

    func fields(for id: String) -> [ConfigField] {
        guard let entry = entry(id) else { return [] }
        return [
            providerInstanceId(for: id),
            modelId(for: id),
            isCustom(for: id),
            displayNameField(for: id),
            maxOutputTokensField(for: id),
            isHiddenField(for: id, currentRisk: entry.isHidden ? .normal : .sensitive),
            modalitiesField(for: id),
            modalitiesOverrideField(for: id),
            contextWindowField(for: id),
            contextWindowOverrideField(for: id),
            voiceOverrideField(for: id),
            supportsToolsField(for: id),
            supportsVisionField(for: id),
        ]
    }

    func add(_ payload: ConfigValue) throws -> String {
        guard case .object(let dict) = payload else {
            throw ConfigError.invalidValue("Expected JSON object")
        }
        guard case .string(let instanceId)? = dict["instance_id"], !instanceId.isEmpty else {
            throw ConfigError.invalidValue("`instance_id` required")
        }
        guard case .string(let modelId)? = dict["model_id"], !modelId.isEmpty else {
            throw ConfigError.invalidValue("`model_id` required")
        }
        // Verify the instance exists.
        let store = ProviderConfigStore.shared
        guard store.config.instances.contains(where: { $0.id == instanceId }) else {
            throw ConfigError.invalidValue("No provider instance with id \(instanceId)")
        }
        var displayName = modelId
        if case .string(let s)? = dict["display_name"], !s.isEmpty { displayName = s }
        var contextWindow: Int? = nil
        if case .int(let i)? = dict["context_window"], i > 0 { contextWindow = i }
        var maxOut: Int? = nil
        if case .int(let i)? = dict["max_output_tokens"], i > 0 { maxOut = i }
        var supportsReasoning: Bool? = nil
        if case .bool(let b)? = dict["supports_reasoning"] { supportsReasoning = b }

        // `LLMModel.provider` is a DISPLAY string (built-in catalogues put
        // "OpenAI" / "Anthropic" here), not an identity — the identity lives in
        // `ModelEntry.providerInstanceId`. Writing the instance UUID here made
        // the model-detail row read "A6FC13AE-…" instead of a provider name.
        // Store the instance's label, matching what the in-app "Add Custom
        // Model" form does.
        let providerName: String = {
            guard let inst = store.config.instances.first(where: { $0.id == instanceId }) else {
                return instanceId
            }
            let label = inst.label.trimmingCharacters(in: .whitespacesAndNewlines)
            return label.isEmpty ? inst.providerType.displayName : label
        }()
        let model = LLMModel(
            id: modelId,
            displayName: displayName,
            provider: providerName,
            contextWindow: contextWindow,
            maxOutputTokens: maxOut,
            supportsReasoning: supportsReasoning
        )
        let entry = ModelEntry(
            providerInstanceId: instanceId,
            model: model,
            isCustom: true,
            isHidden: false,
            userModifiedAt: Date()
        )
        let added = store.addEntry(entry)
        guard added else {
            throw ConfigError.invalidValue("Entry already exists for this model")
        }
        return entry.id
    }

    func remove(id: String) throws {
        guard let entry = entry(id) else {
            throw ConfigError.unknownPath("models.\(id)")
        }
        guard entry.isCustom else {
            throw ConfigError.permissionDenied(
                reason: "Only custom-added models can be removed; use `set models.\(id).isHidden true` for API-reported models"
            )
        }
        ProviderConfigStore.shared.removeEntry(id)
    }

    // MARK: Field factories

    private func entry(_ id: String) -> ModelEntry? {
        ProviderConfigStore.shared.config.modelEntries.first { $0.id == id }
    }

    private func mutate(_ id: String, _ apply: (inout ModelEntry) -> Void) throws {
        guard var e = entry(id) else { throw ConfigError.unknownPath("models.\(id)") }
        apply(&e)
        ProviderConfigStore.shared.updateEntry(e)
    }

    private func providerInstanceId(for id: String) -> ConfigField {
        ReadOnlyField(
            path: "models.\(id).providerInstanceId",
            displayName: "Provider instance",
            description: "Owning provider instance (read-only).",
            valueSchema: .string(),
            reader: { [self] in
                guard let e = entry(id) else { return .null }
                return .string(e.providerInstanceId)
            }
        )
    }

    private func modelId(for id: String) -> ConfigField {
        ReadOnlyField(
            path: "models.\(id).modelId",
            displayName: "Model id",
            description: "API model identifier (read-only).",
            valueSchema: .string(),
            reader: { [self] in
                guard let e = entry(id) else { return .null }
                return .string(e.baseModel.id)
            }
        )
    }

    private func isCustom(for id: String) -> ConfigField {
        ReadOnlyField(
            path: "models.\(id).isCustom",
            displayName: "Is custom",
            description: "True for entries created via `models add`; false for API-reported.",
            valueSchema: .bool,
            reader: { [self] in
                guard let e = entry(id) else { return .null }
                return .bool(e.isCustom)
            }
        )
    }

    private func displayNameField(for id: String) -> ConfigField {
        ClosureField(
            path: "models.\(id).displayName",
            displayName: "Display name",
            description: "User-visible label override. Empty restores the API default.",
            valueSchema: .string(maxLength: 200),
            risk: .normal, revertable: true,
            reader: { [self] in
                guard let e = entry(id) else { return .null }
                return .string(e.overrides.displayName ?? e.baseModel.displayName)
            },
            writer: { [self] v in
                guard case .string(let s) = v else { throw ConfigError.typeMismatch(expected: "string") }
                try mutate(id) { e in
                    if s.isEmpty || s == e.baseModel.displayName {
                        e.overrides.displayName = nil
                    } else {
                        e.overrides.displayName = s
                    }
                }
            }
        )
    }

    private func maxOutputTokensField(for id: String) -> ConfigField {
        ClosureField(
            path: "models.\(id).maxOutputTokens",
            displayName: "Max output tokens",
            description: "Override the API-reported max. 0 restores the default.",
            valueSchema: .int(min: 0, max: 1_000_000),
            risk: .sensitive, revertable: true,
            reader: { [self] in
                guard let e = entry(id) else { return .null }
                return .int(e.overrides.maxOutputTokens ?? e.baseModel.maxOutputTokens ?? 0)
            },
            writer: { [self] v in
                guard case .int(let i) = v else { throw ConfigError.typeMismatch(expected: "int") }
                try mutate(id) { e in
                    e.overrides.maxOutputTokens = (i == 0) ? nil : i
                }
            }
        )
    }

    private func isHiddenField(for id: String, currentRisk: ConfigRisk) -> ConfigField {
        // Hiding a model the agent currently uses is mildly destructive
        // (it'll fall back to other models); flag it as `.sensitive`.
        ClosureField(
            path: "models.\(id).isHidden",
            displayName: "Hidden",
            description: "When true, the model is excluded from pickers and agent loop.",
            valueSchema: .bool,
            risk: .sensitive, revertable: true,
            reader: { [self] in
                guard let e = entry(id) else { return .null }
                return .bool(e.isHidden)
            },
            writer: { [self] v in
                guard case .bool(let b) = v else { throw ConfigError.typeMismatch(expected: "bool") }
                try mutate(id) { $0.isHidden = b }
            }
        )
    }

    // MARK: - Capability fields (modalities / contextWindow / derived)

    /// Canonical names matching `minis-model-use list` so the two tools
    /// agree on terminology. Order is meaningful for `modalitiesField`'s
    /// stable output.
    private static let modalityNamesInOrder: [(name: String, flag: ModelModality)] = [
        ("text_input",   .textInput),
        ("text_output",  .textOutput),
        ("image_input",  .imageInput),
        ("pdf_input",    .pdfInput),
        ("audio_input",  .audioInput),
        ("video_input",  .videoInput),
        ("image_output", .imageOutput),
        ("audio_output", .audioOutput),
        ("video_output", .videoOutput),
    ]

    private static func encodeModality(_ m: ModelModality) -> [String] {
        modalityNamesInOrder.compactMap { m.contains($0.flag) ? $0.name : nil }
    }

    private static func decodeModality(_ names: [String]) throws -> ModelModality {
        let lookup = Dictionary(uniqueKeysWithValues: modalityNamesInOrder.map { ($0.name, $0.flag) })
        var out = ModelModality()
        for n in names {
            guard let flag = lookup[n] else {
                let allowed = modalityNamesInOrder.map(\.name).joined(separator: ", ")
                throw ConfigError.invalidValue("Unknown modality '\(n)'. Allowed: \(allowed)")
            }
            out.insert(flag)
        }
        return out
    }

    private func modalitiesField(for id: String) -> ConfigField {
        ReadOnlyField(
            path: "models.\(id).modalities",
            displayName: "Modalities",
            description: "Effective modality list (override applied if set, otherwise inferred from provider / models.dev).",
            valueSchema: .array(.string()),
            reader: { [self] in
                guard let e = entry(id) else { return .null }
                let names = Self.encodeModality(e.model.capabilities.supportedModalities)
                return .array(names.map { .string($0) })
            }
        )
    }

    private func modalitiesOverrideField(for id: String) -> ConfigField {
        ClosureField(
            path: "models.\(id).modalitiesOverride",
            displayName: "Modalities override",
            description: "User override list. Pass [] or null to clear and fall back to inferred. Allowed: text_input, text_output, image_input, pdf_input, audio_input, video_input, image_output, audio_output, video_output.",
            valueSchema: .array(.string()),
            risk: .sensitive, revertable: true,
            reader: { [self] in
                guard let e = entry(id) else { return .null }
                if let override = e.overrides.modalityOverride {
                    return .array(Self.encodeModality(override).map { .string($0) })
                }
                return .array([])
            },
            writer: { [self] v in
                guard case .array(let arr) = v else { throw ConfigError.typeMismatch(expected: "array") }
                let names: [String] = arr.compactMap {
                    if case .string(let s) = $0 { return s } else { return nil }
                }
                if names.isEmpty {
                    try mutate(id) { $0.overrides.modalityOverride = nil }
                    return
                }
                let parsed = try Self.decodeModality(names)
                try mutate(id) { $0.overrides.modalityOverride = parsed }
            }
        )
    }

    private func contextWindowField(for id: String) -> ConfigField {
        ReadOnlyField(
            path: "models.\(id).contextWindow",
            displayName: "Context window (tokens)",
            description: "Effective context window in tokens (override applied if set, otherwise from models.dev / provider API / heuristic).",
            valueSchema: .int(),
            reader: { [self] in
                guard let e = entry(id) else { return .null }
                return .int(e.model.contextWindowTokens)
            }
        )
    }

    private func contextWindowOverrideField(for id: String) -> ConfigField {
        ClosureField(
            path: "models.\(id).contextWindowOverride",
            displayName: "Context window override (tokens)",
            description: "User override in tokens. 0 clears the override and restores the API/heuristic value.",
            valueSchema: .int(min: 0, max: 10_000_000),
            risk: .sensitive, revertable: true,
            reader: { [self] in
                guard let e = entry(id) else { return .null }
                return .int(e.overrides.contextWindow ?? 0)
            },
            writer: { [self] v in
                guard case .int(let i) = v else { throw ConfigError.typeMismatch(expected: "int") }
                try mutate(id) { $0.overrides.contextWindow = (i == 0) ? nil : i }
            }
        )
    }

    private func voiceOverrideField(for id: String) -> ConfigField {
        ClosureField(
            path: "models.\(id).voiceOverride",
            displayName: "Voice override",
            description: "User-set voice id for TTS. Sent as the `voice` field of /v1/audio/speech (or audio-modal requests). Empty string clears the override and restores the provider default.",
            valueSchema: .string(maxLength: 256),
            risk: .sensitive, revertable: true,
            reader: { [self] in
                guard let e = entry(id) else { return .null }
                guard let v = e.overrides.voiceOverride, !v.isEmpty else { return .null }
                return .string(v)
            },
            writer: { [self] v in
                guard case .string(let s) = v else { throw ConfigError.typeMismatch(expected: "string") }
                try mutate(id) { $0.overrides.voiceOverride = s.isEmpty ? nil : s }
            }
        )
    }

    private func supportsToolsField(for id: String) -> ConfigField {
        ReadOnlyField(
            path: "models.\(id).supportsTools",
            displayName: "Supports tools",
            description: "Derived. True when the model can produce text output (every text-output model in this app supports the tool-use protocol).",
            valueSchema: .bool,
            reader: { [self] in
                guard let e = entry(id) else { return .null }
                return .bool(e.model.capabilities.supportedModalities.contains(.textOutput))
            }
        )
    }

    private func supportsVisionField(for id: String) -> ConfigField {
        ReadOnlyField(
            path: "models.\(id).supportsVision",
            displayName: "Supports vision",
            description: "Derived. True when the model accepts image input.",
            valueSchema: .bool,
            reader: { [self] in
                guard let e = entry(id) else { return .null }
                return .bool(e.model.capabilities.supportedModalities.contains(.imageInput))
            }
        )
    }
}
