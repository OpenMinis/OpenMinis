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

    // MARK: - [T-model-custom-params] User-set request parameters
    //
    // Phase one: storage only. These are carried on the entry and round-trip
    // through config / backup / sync; nothing reads them into a request yet,
    // so adding them changes no behaviour on its own.
    //
    // All optional, and absence means "don't send it" rather than "send a
    // default" — a model whose provider has its own sampling defaults must
    // keep getting them, so `nil` has to stay distinguishable from any value
    // the user could pick.

    /// Sampling temperature. nil = leave the provider's default alone.
    var temperature: Double?

    /// Nucleus-sampling cutoff. nil = leave the provider's default alone.
    var topP: Double?

    /// Extra HTTP headers for this model's requests. nil (not `[:]`) means
    /// unset, so an empty dictionary the user deliberately created is not
    /// silently the same as never having configured one.
    var customHeaders: [String: String]?

    /// Free-form body parameters passed through to the provider.
    ///
    /// `[String: String]` per the phase-one spec. Worth stating plainly: this
    /// cannot express a non-string JSON value (a number, bool, array or nested
    /// object), so a parameter the provider expects as e.g. `{"n": 2}` cannot
    /// be represented here yet. The type is the storage contract for this
    /// phase; whoever implements the send path will have to decide between
    /// coercing on the way out and widening this to a JSON value type.
    var extraBodyParams: [String: String]?

    init(displayName: String? = nil,
         maxOutputTokens: Int? = nil,
         modalityOverride: ModelModality? = nil,
         contextWindow: Int? = nil,
         supportsReasoning: Bool? = nil,
         maxThinkingLevel: ThinkingLevel? = nil,
         temperature: Double? = nil,
         topP: Double? = nil,
         customHeaders: [String: String]? = nil,
         extraBodyParams: [String: String]? = nil) {
        self.displayName = displayName
        self.maxOutputTokens = maxOutputTokens
        self.modalityOverride = modalityOverride
        self.contextWindow = contextWindow
        self.supportsReasoning = supportsReasoning
        self.maxThinkingLevel = maxThinkingLevel
        self.temperature = temperature
        self.topP = topP
        self.customHeaders = customHeaders
        self.extraBodyParams = extraBodyParams
    }

    /// True when the user has not set any override.
    var isEmpty: Bool {
        displayName == nil
            && maxOutputTokens == nil
            && modalityOverride == nil
            && contextWindow == nil
            && supportsReasoning == nil
            && maxThinkingLevel == nil
            && temperature == nil
            && topP == nil
            && customHeaders == nil
            && extraBodyParams == nil
    }

    private enum CodingKeys: String, CodingKey {
        case displayName, maxOutputTokens, modalityOverride, contextWindow, supportsReasoning, maxThinkingLevel
        // [T-model-custom-params] Additive: an older payload simply lacks
        // these keys and decodes them as nil.
        case temperature, topP, customHeaders, extraBodyParams
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
        // [T-model-custom-params] `decodeIfPresent` throughout, so an override
        // blob written before these fields existed — an old backup, a config
        // synced from an older build — decodes to nil rather than throwing and
        // losing every other override alongside it.
        self.temperature = try container.decodeIfPresent(Double.self, forKey: .temperature)
        self.topP = try container.decodeIfPresent(Double.self, forKey: .topP)
        self.customHeaders = try container.decodeIfPresent([String: String].self, forKey: .customHeaders)
        self.extraBodyParams = try container.decodeIfPresent([String: String].self, forKey: .extraBodyParams)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(displayName, forKey: .displayName)
        try container.encodeIfPresent(maxOutputTokens, forKey: .maxOutputTokens)
        try container.encodeIfPresent(modalityOverride, forKey: .modalityOverride)
        try container.encodeIfPresent(contextWindow, forKey: .contextWindow)
        try container.encodeIfPresent(supportsReasoning, forKey: .supportsReasoning)
        try container.encodeIfPresent(maxThinkingLevel?.rawValue, forKey: .maxThinkingLevel)
        // [T-model-custom-params] `encodeIfPresent`, so an entry with none of
        // these set serialises byte-identically to before this change — no
        // diff churn in provider_config.json, and no LWW noise in sync.
        try container.encodeIfPresent(temperature, forKey: .temperature)
        try container.encodeIfPresent(topP, forKey: .topP)
        try container.encodeIfPresent(customHeaders, forKey: .customHeaders)
        try container.encodeIfPresent(extraBodyParams, forKey: .extraBodyParams)
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

    /// [T-model-absence-grace] When the provider's `/v1/models` first stopped
    /// listing this model, or nil while the provider is still reporting it.
    ///
    /// Exists because "this refresh did not list the model" and "this model is
    /// gone" are different facts, and `replaceEntries` used to treat them as
    /// one: a catalog entry missing from a single response was deleted outright,
    /// taking its `overrides` with it. Relay/aggregator endpoints (the reported
    /// case: CPA-Mini2 serving `gemini-3.8-flash-high`) drop a model from the
    /// list transiently — upstream quota, a backend rotation, a partial outage —
    /// and list it again minutes later. The user saw the model vanish from the
    /// provider, and separately saw a context-window override "revert", which
    /// was the same deletion seen from the other side.
    ///
    /// While set, the entry is kept and shown as unavailable rather than
    /// deleted; it is only really removed once the absence has persisted past
    /// `ProviderConfigStore.modelAbsenceGracePeriod`. A model that comes back is
    /// cleared back to nil and is indistinguishable from one that never left —
    /// overrides included.
    ///
    /// DELIBERATELY LOCAL-ONLY, and not part of `isUserModified`: absence is a
    /// per-device observation (each device refreshes on its own schedule against
    /// possibly different upstream state), not user intent, so it must neither
    /// travel over iCloud nor make an otherwise-untouched entry start syncing.
    /// It is dropped on encode for exactly that reason — see `encode(to:)`.
    var absentSince: Date?

    /// True while the provider is not currently listing this model.
    var isUnavailableFromProvider: Bool { absentSince != nil }

    /// True if this entry carries any user intent that should propagate via iCloud sync.
    /// Purely derived from existing state — no extra field to maintain. New override fields
    /// plug in automatically via `ModelOverrides.isEmpty`.
    ///
    /// `absentSince` is intentionally absent from this list: it is a local
    /// observation about the provider, not something the user did.
    var isUserModified: Bool {
        isCustom || isHidden || !overrides.isEmpty
    }

    /// Effective model as seen by the rest of the app: `baseModel` with `overrides` applied.
    /// New override fields: add them to the memberwise rebuild below.
    var model: LLMModel {
        guard !overrides.isEmpty else { return baseModel }
        // LLMModel.displayName is a `let`, so rebuild via memberwise init to apply any override.
        var rebuilt = LLMModel(
            id: baseModel.id,
            displayName: overrides.displayName ?? baseModel.displayName,
            provider: baseModel.provider,
            modalityOverride: overrides.modalityOverride ?? baseModel.modalityOverride,
            contextWindow: overrides.contextWindow ?? baseModel.contextWindow,
            maxOutputTokens: overrides.maxOutputTokens ?? baseModel.maxOutputTokens,
            supportsReasoning: overrides.supportsReasoning ?? baseModel.supportsReasoning,
            interleavedReasoningField: baseModel.interleavedReasoningField
        )
        // Not an init parameter, so the rebuild would drop it and a renamed or
        // re-moded OpenRouter entry would fall back to the audio-bit rule.
        // [T-openrouter-voice-catalog]
        rebuilt.voiceRole = baseModel.voiceRole
        return rebuilt
    }

    init(
        uuid: String = UUID().uuidString,
        providerInstanceId: String,
        model: LLMModel,
        overrides: ModelOverrides = ModelOverrides(),
        isCustom: Bool = false,
        isHidden: Bool = false,
        userModifiedAt: Date? = nil,
        absentSince: Date? = nil
    ) {
        self.uuid = uuid
        self.providerInstanceId = providerInstanceId
        self.baseModel = model
        self.overrides = overrides
        self.isCustom = isCustom
        self.isHidden = isHidden
        self.userModifiedAt = userModifiedAt
        self.absentSince = absentSince
    }

    private enum CodingKeys: String, CodingKey {
        case uuid, providerInstanceId, model, overrides, isCustom, isHidden, userModifiedAt
        case absentSince
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
        // [T-model-absence-grace] Decoded so the grace window survives a relaunch;
        // written only to the LOCAL config file (see encode(to:)).
        self.absentSince = try container.decodeIfPresent(Date.self, forKey: .absentSince)
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
        // [T-model-absence-grace] Encoded so the grace window survives a
        // relaunch. It reaches the local config file only: the iCloud upload
        // filters to `isUserModified` entries (CloudSyncEngine :1363/:2184),
        // which absence deliberately does not set, and an entry that IS user
        // modified carries a field the receiving device simply re-derives from
        // its own next refresh. Each device observes its own provider, so a
        // synced absence would be a claim one device cannot make for another.
        try container.encodeIfPresent(absentSince, forKey: .absentSince)
    }

    // Hashable/Equatable based on stored properties only
    static func == (lhs: ModelEntry, rhs: ModelEntry) -> Bool {
        lhs.uuid == rhs.uuid &&
        lhs.providerInstanceId == rhs.providerInstanceId &&
        lhs.baseModel == rhs.baseModel &&
        lhs.overrides == rhs.overrides &&
        lhs.isCustom == rhs.isCustom &&
        lhs.isHidden == rhs.isHidden &&
        lhs.userModifiedAt == rhs.userModifiedAt &&
        // [T-model-absence-grace] Part of equality so a change in availability
        // is seen as a change: callers that diff entries before persisting
        // (ProviderConfigDB's row-dirty check) would otherwise treat a model
        // going unavailable — or coming back — as a no-op and never write it.
        lhs.absentSince == rhs.absentSince
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(uuid)
        hasher.combine(providerInstanceId)
        hasher.combine(baseModel)
        hasher.combine(overrides)
        hasher.combine(isCustom)
        hasher.combine(isHidden)
        hasher.combine(userModifiedAt)
        hasher.combine(absentSince)
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
        // [T-thinking-max-unreachable] The ceiling can legitimately sit ABOVE
        // every declared tier — a catalog family rule that knows the model
        // reaches Max while models.dev lists only ["low","medium","high"].
        // Offering just the declared tiers would then hide the very levels the
        // rule exists to expose, which is how Max went missing from the picker.
        // Extend the ladder up to the ceiling, keeping the declared tiers as a
        // floor so a sparse declaration still collapses the tiers BELOW its top
        // (its whole point: one option per distinct wire value).
        if let declaredTop = declared.last, ceiling > declaredTop {
            let above = ThinkingLevel.allCases.filter { $0 != .off && $0 > declaredTop && $0 <= ceiling }
            return capped + above
        }
        // An override below every declared tier would empty the picker and
        // strand the toggle in an unusable state — keep the weakest tier.
        return capped.isEmpty ? [declared[0]] : capped
    }
}
