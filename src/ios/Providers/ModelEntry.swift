import Foundation

/// User-editable overrides layered on top of a model's API-reported metadata.
///
/// Design: this is the single place where user edits live, separate from `baseModel`
/// (which reflects API truth). `ModelEntry.model` computes the effective view by
/// overlaying these fields. New editable fields should be added here — no other
/// layer needs to change to make them survive API refreshes.
struct ModelOverrides: Codable, Hashable, Sendable {
    var displayName: String?
    var maxOutputTokens: Int?
    /// User-set modality override. Wins over `baseModel.modalityOverride`
    /// when non-nil so a third-party / proxied model whose API doesn't
    /// report capabilities can be hand-corrected (e.g. declaring an
    /// image-output model that the upstream returns as text-only).
    var modalityOverride: ModelModality?
    /// User-set context-window override in tokens. Wins over
    /// `baseModel.contextWindow` when non-nil. Useful for proxied
    /// endpoints that under-report or omit context size.
    var contextWindow: Int?
    /// User-set thinking/reasoning capability flag. Wins over
    /// `baseModel.supportsReasoning` when non-nil so a third-party
    /// model that the API doesn't advertise as supporting reasoning
    /// can still be flagged manually (and survive both `replaceEntries`
    /// and the models.dev `applyDevData` pass that otherwise resets
    /// the field every refresh — which is what made the user's manual
    /// Thinking toggle revert).
    var supportsReasoning: Bool?
    var maxThinkingLevel: ThinkingLevel?

    init(displayName: String? = nil,
         maxOutputTokens: Int? = nil,
         modalityOverride: ModelModality? = nil,
         contextWindow: Int? = nil,
         supportsReasoning: Bool? = nil,
         maxThinkingLevel: ThinkingLevel? = nil) {
        self.displayName = displayName
        self.maxOutputTokens = maxOutputTokens
        self.modalityOverride = modalityOverride
        self.contextWindow = contextWindow
        self.supportsReasoning = supportsReasoning
        self.maxThinkingLevel = maxThinkingLevel
    }

    /// True when the user has not set any override.
    var isEmpty: Bool {
        displayName == nil
            && maxOutputTokens == nil
            && modalityOverride == nil
            && contextWindow == nil
            && supportsReasoning == nil
            && maxThinkingLevel == nil
    }

    private enum CodingKeys: String, CodingKey {
        case displayName, maxOutputTokens, modalityOverride, contextWindow, supportsReasoning, maxThinkingLevel
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.displayName = try container.decodeIfPresent(String.self, forKey: .displayName)
        self.maxOutputTokens = try container.decodeIfPresent(Int.self, forKey: .maxOutputTokens)
        self.modalityOverride = try container.decodeIfPresent(ModelModality.self, forKey: .modalityOverride)
        self.contextWindow = try container.decodeIfPresent(Int.self, forKey: .contextWindow)
        self.supportsReasoning = try container.decodeIfPresent(Bool.self, forKey: .supportsReasoning)
        if let raw = try container.decodeIfPresent(String.self, forKey: .maxThinkingLevel) {
            self.maxThinkingLevel = ThinkingLevel.decoded(raw)
        } else {
            self.maxThinkingLevel = nil
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(displayName, forKey: .displayName)
        try container.encodeIfPresent(maxOutputTokens, forKey: .maxOutputTokens)
        try container.encodeIfPresent(modalityOverride, forKey: .modalityOverride)
        try container.encodeIfPresent(contextWindow, forKey: .contextWindow)
        try container.encodeIfPresent(supportsReasoning, forKey: .supportsReasoning)
        try container.encodeIfPresent(maxThinkingLevel?.rawValue, forKey: .maxThinkingLevel)
    }
}

/// A model available through a specific provider instance.
/// Each entry has a stable UUID used as its Identifiable `id`.
/// The legacy composite key "{providerInstanceId}:{model.id}" is available as `compositeKey`.
///
/// Storage model:
/// - `baseModel` holds the API-reported metadata and is overwritten on every refresh.
/// - `overrides` holds user edits and is preserved across refreshes.
/// - `model` is a computed view combining the two. UI code reads `model`; persistence
///   and refresh logic read `baseModel`.
struct ModelEntry: Identifiable, Codable, Hashable {
    /// [T-provider-entry-composite-key] Per-device random uuid. NO LONGER the
    /// identity / sync key / reference key — that role moved to `compositeKey`.
    /// Retained for: (1) downgrade compatibility (an old build reads this as
    /// `id`), (2) legacyUuid → compositeKey normalization during migration and
    /// cross-version sync. New entries still get a uuid so downgrade keeps
    /// working.
    let uuid: String
    /// Stable cross-device identity = "{providerInstanceId}/{baseModel.id}".
    /// Same model under the same instance resolves to the SAME id on every
    /// device, so sync de-dups naturally and group/binding references never go
    /// dangling from a uuid mismatch. Separator is "/" (not the legacy ":")
    /// so migration can tell new keys from the old ":" composite keys that
    /// group membership used in a much earlier schema.
    var id: String { compositeKey }
    var compositeKey: String { "\(providerInstanceId)/\(baseModel.id)" }
    /// The pre-composite-key legacy composite (":" separator). Kept ONLY for
    /// reading data written by the very old group-membership scheme.
    var legacyColonCompositeKey: String { "\(providerInstanceId):\(baseModel.id)" }
    let providerInstanceId: String
    /// API-reported model metadata. Do not read from UI — read `model` instead.
    let baseModel: LLMModel
    /// User-editable overrides. Preserved across API refreshes.
    var overrides: ModelOverrides
    var isCustom: Bool
    var isHidden: Bool
    /// Timestamp of the last user modification to `overrides`, `isHidden`, or `isCustom`.
    /// Nil for legacy entries written before this field existed. Used by iCloud merge to
    /// resolve same-field conflicts (last-write-wins by timestamp) and to avoid applying
    /// stale override names from old devices that never stamped a modification time.
    var userModifiedAt: Date?

    /// True if this entry carries any user intent that should propagate via iCloud sync.
    /// Purely derived from existing state — no extra field to maintain. New override fields
    /// plug in automatically via `ModelOverrides.isEmpty`.
    var isUserModified: Bool {
        isCustom || isHidden || !overrides.isEmpty
    }

    /// Effective model as seen by the rest of the app: `baseModel` with `overrides` applied.
    /// New override fields: add them to the memberwise rebuild below.
    var model: LLMModel {
        guard !overrides.isEmpty else { return baseModel }
        // LLMModel.displayName is a `let`, so rebuild via memberwise init to apply any override.
        var effective = LLMModel(
            id: baseModel.id,
            displayName: overrides.displayName ?? baseModel.displayName,
            provider: baseModel.provider,
            modalityOverride: overrides.modalityOverride ?? baseModel.modalityOverride,
            contextWindow: overrides.contextWindow ?? baseModel.contextWindow,
            maxOutputTokens: overrides.maxOutputTokens ?? baseModel.maxOutputTokens,
            supportsReasoning: overrides.supportsReasoning ?? baseModel.supportsReasoning,
            interleavedReasoningField: baseModel.interleavedReasoningField,
            reasoningEffortValues: baseModel.reasoningEffortValues,
            declaresNoEffortTiers: baseModel.declaresNoEffortTiers
        )
        effective.effortDeclarationIsAuthoritative = baseModel.effortDeclarationIsAuthoritative
        effective.codexCatalogMetadata = baseModel.codexCatalogMetadata
        return effective
    }

    init(
        uuid: String = UUID().uuidString,
        providerInstanceId: String,
        model: LLMModel,
        overrides: ModelOverrides = ModelOverrides(),
        isCustom: Bool = false,
        isHidden: Bool = false,
        userModifiedAt: Date? = nil
    ) {
        self.uuid = uuid
        self.providerInstanceId = providerInstanceId
        self.baseModel = model
        self.overrides = overrides
        self.isCustom = isCustom
        self.isHidden = isHidden
        self.userModifiedAt = userModifiedAt
    }

    private enum CodingKeys: String, CodingKey {
        case uuid, providerInstanceId, model, overrides, isCustom, isHidden, userModifiedAt
    }

    // Decode with backwards compatibility: generate uuid if missing in old data,
    // default overrides to empty if absent.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.uuid = try container.decodeIfPresent(String.self, forKey: .uuid) ?? UUID().uuidString
        self.providerInstanceId = try container.decode(String.self, forKey: .providerInstanceId)
        self.baseModel = try container.decode(LLMModel.self, forKey: .model)
        self.overrides = try container.decodeIfPresent(ModelOverrides.self, forKey: .overrides) ?? ModelOverrides()
        self.isCustom = try container.decode(Bool.self, forKey: .isCustom)
        self.isHidden = try container.decode(Bool.self, forKey: .isHidden)
        self.userModifiedAt = try container.decodeIfPresent(Date.self, forKey: .userModifiedAt)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(uuid, forKey: .uuid)
        try container.encode(providerInstanceId, forKey: .providerInstanceId)
        try container.encode(baseModel, forKey: .model)
        if !overrides.isEmpty {
            try container.encode(overrides, forKey: .overrides)
        }
        try container.encode(isCustom, forKey: .isCustom)
        try container.encode(isHidden, forKey: .isHidden)
        try container.encodeIfPresent(userModifiedAt, forKey: .userModifiedAt)
    }

    // Hashable/Equatable based on stored properties only
    static func == (lhs: ModelEntry, rhs: ModelEntry) -> Bool {
        lhs.uuid == rhs.uuid &&
        lhs.providerInstanceId == rhs.providerInstanceId &&
        lhs.baseModel == rhs.baseModel &&
        lhs.overrides == rhs.overrides &&
        lhs.isCustom == rhs.isCustom &&
        lhs.isHidden == rhs.isHidden &&
        lhs.userModifiedAt == rhs.userModifiedAt
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(uuid)
        hasher.combine(providerInstanceId)
        hasher.combine(baseModel)
        hasher.combine(overrides)
        hasher.combine(isCustom)
        hasher.combine(isHidden)
        hasher.combine(userModifiedAt)
    }
}

extension ModelEntry {
    var effectiveMaxThinkingLevel: ThinkingLevel {
        overrides.maxThinkingLevel ?? model.catalogMaxThinkingLevel
    }

    /// [T-thinking-levels-data-driven] The thinking levels to OFFER for this
    /// entry, in ascending order and never including `.off`.
    ///
    /// Prefers the catalog's declared effort tiers (one option per DISTINCT
    /// wire value, so every option provably changes the request) and falls back
    /// to the historical "every level up to the ceiling" ladder when the model
    /// declares nothing.
    ///
    /// A user override (`overrides.maxThinkingLevel`) always wins as a CEILING:
    /// it is a manual correction for a model the catalog describes wrongly, so
    /// it must be able to cut the list down — but it is never allowed to invent
    /// tiers the backend didn't declare, which is what the pre-existing
    /// `clampEffort` would silently undo anyway.
    var selectableThinkingLevels: [ThinkingLevel] {
        let ceiling = effectiveMaxThinkingLevel
        guard ceiling != .off else { return [] }
        let declared = model.selectableThinkingLevels
        guard !declared.isEmpty else {
            return ThinkingLevel.allCases.filter { $0 != .off && $0 <= ceiling }
        }
        let capped = declared.filter { $0 <= ceiling }
        // An override below every declared tier would empty the picker and
        // strand the toggle in an unusable state — keep the weakest tier.
        return capped.isEmpty ? [declared[0]] : capped
    }
}
