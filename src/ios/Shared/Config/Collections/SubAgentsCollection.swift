import Foundation

/// Exposes the Sub Agents roster to `minis-config` under `subagents.<id>.…`.
///
/// [T-sub-agents-cli] Mirrors what Settings › Sub Agents can do — add, edit,
/// delete, reorder — so an agent can configure its own sub agents without a
/// human tapping through the UI.
///
/// WHY A FLAT ID. Unlike `ThinkingRulesCollection`, which has to encode
/// `<instanceId>:<ruleId>` because a rule belongs to a provider, a sub agent is
/// global, so the child key is just its id — with ONE substitution: the
/// built-in's id contains a dot, which the path resolver would mis-split, so it
/// is addressed as `subagents.general` (see `builtInAlias`).
///
/// THE BUILT-IN IS PARTIALLY WRITABLE, and that asymmetry is the whole reason
/// this file needs care. Its `name` and `description` are canonical English:
/// the name is the value the model must emit for `subagent_task.agent` and the
/// key `SubAgentRoster.resolve(name:)` matches on, and the description is what
/// the model reads to decide what to delegate. `SubAgentRoster.normalize`
/// restores both on every load, so a write here would appear to succeed and
/// then silently revert. Rather than let the CLI lie, those two fields are
/// exposed READ-ONLY for the built-in and writable for everyone else, and
/// `remove` refuses it outright. What the user CAN change on the built-in —
/// its model and its instructions — is writable exactly like any other agent's.
@MainActor
struct SubAgentsCollection: ConfigCollection {
    let basePath = "subagents"
    let displayName = "Sub agents"
    let description = "Named sub agents the assistant can delegate to (Settings › Sub Agents). The built-in one cannot be deleted and its name/description are fixed."
    let addable = true
    let removable = true
    /// Sensitive rather than normal: a definition changes what work the model
    /// hands off and which model runs it, and a bad description degrades
    /// delegation quietly rather than erroring.
    let risk: ConfigRisk = .sensitive

    /// Add payload:
    /// {
    ///   "name": "Translator",                    // required, ≤40, unique
    ///   "description": "Translate zh/en docs.",  // required, ≤200
    ///   "instructions": "Always keep tone.",     // optional, ≤4000
    ///   "model_group": "<groupId or group name>",// optional; omit = Auto
    ///   "thinking": "high",                       // optional; omit = follow the group
    ///   "position": 1                            // optional insert index
    /// }
    let addPayloadSchema: ConfigValueSchema = .json

    /// [T-subagent-own-store] The roster lives in SubAgentStore now; only
    /// `resolveGroupId` still reaches into ProviderConfigStore, and it does so
    /// explicitly, because model groups really are provider configuration.
    private var store: SubAgentStore { .shared }

    /// [T-sub-agents-cli] The path segment used for the built-in.
    ///
    /// Its real id is `builtin.general`, which contains a DOT. The resolver
    /// used to cut an id at its first dot, so the built-in got this dot-free
    /// alias to stay editable. Since [T-config-path-dotted-id] (OpenMinis#390)
    /// the resolver keeps everything between the first and last dot as the id,
    /// so `subagents.builtin.general.instructions` resolves too; the alias
    /// stays the canonical spelling so existing paths and audit keys are
    /// unchanged. The stored id is untouched: this is a CLI-surface concern.
    static let builtInAlias = "general"

    /// Map a path segment back to a real definition id.
    private func realId(_ segment: String) -> String {
        segment == Self.builtInAlias ? SubAgentDefinition.builtInId : segment
    }

    /// Map a definition id to the segment used in paths.
    private static func pathSegment(_ id: String) -> String {
        id == SubAgentDefinition.builtInId ? builtInAlias : id
    }

    // MARK: - Children

    func childIds() -> [String] {
        store.subAgents.map { Self.pathSegment($0.id) }
    }

    func fields(for idSegment: String) -> [ConfigField] {
        let id = realId(idSegment)
        guard let def = store.subAgent(id: id) else { return [] }
        let seg = Self.pathSegment(id)
        return [
            nameField(id, seg: seg, isBuiltIn: def.isBuiltIn),
            descriptionField(id, seg: seg, isBuiltIn: def.isBuiltIn),
            instructionsField(id, seg: seg),
            modelGroupField(id, seg: seg),
            thinkingField(id, seg: seg),
            builtInField(id, seg: seg),
        ]
    }

    // MARK: - Add / remove

    func add(_ payload: ConfigValue) throws -> String {
        guard case .object(let dict) = payload else {
            throw ConfigError.invalidValue("Expected a JSON object")
        }
        guard case .string(let rawName)? = dict["name"] else {
            throw ConfigError.invalidValue("`name` is required")
        }
        let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { throw ConfigError.invalidValue("`name` must not be empty") }
        guard name.count <= SubAgentLimits.nameMaxLength else {
            throw ConfigError.invalidValue("`name` must be at most \(SubAgentLimits.nameMaxLength) characters")
        }
        guard !store.subAgentNameIsTaken(name, excluding: nil) else {
            throw ConfigError.invalidValue("Another sub agent is already called '\(name)'. Names are how the model addresses an agent, so they must be unique.")
        }
        guard case .string(let rawDesc)? = dict["description"] else {
            throw ConfigError.invalidValue("`description` is required — it is what the assistant reads to decide when to use this sub agent")
        }
        let desc = rawDesc.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !desc.isEmpty else { throw ConfigError.invalidValue("`description` must not be empty") }
        guard desc.count <= SubAgentLimits.descriptionMaxLength else {
            throw ConfigError.invalidValue("`description` must be at most \(SubAgentLimits.descriptionMaxLength) characters — it costs main-conversation tokens on every turn")
        }
        var instructions = ""
        if case .string(let i)? = dict["instructions"] {
            instructions = i.trimmingCharacters(in: .whitespacesAndNewlines)
            guard instructions.count <= SubAgentLimits.instructionsMaxLength else {
                throw ConfigError.invalidValue("`instructions` must be at most \(SubAgentLimits.instructionsMaxLength) characters")
            }
        }
        var groupId: String? = nil
        if case .string(let g)? = dict["model_group"], !g.isEmpty {
            groupId = try Self.resolveGroupId(g)
        }
        // Checked before writing so the CLI reports the limit instead of the
        // store silently declining the append.
        guard store.canAddSubAgent else {
            throw ConfigError.invalidValue("You can define at most \(SubAgentLimits.maxCount) sub agents (including the built-in one). Remove one first.")
        }

        var thinking: ThinkingLevel? = nil
        if case .string(let t)? = dict["thinking"] {
            let raw = t.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            if !raw.isEmpty {
                guard let level = ThinkingLevel(rawValue: raw), level != .off else {
                    let valid = ThinkingLevel.allCases.filter { $0 != .off }.map(\.rawValue).joined(separator: ", ")
                    throw ConfigError.invalidValue("`thinking` must be one of: \(valid)")
                }
                thinking = level
            }
        }

        let def = SubAgentDefinition(name: name, description: desc, instructions: instructions,
                                     modelGroupId: groupId, thinkingLevelOverride: thinking,
                                     isBuiltIn: false)
        store.upsertSubAgent(def)

        // `position` is applied as a reorder afterwards: upsert always appends,
        // and order is what the model sees, so honouring it here saves the
        // caller a second command.
        if case .int(let pos)? = dict["position"], pos >= 0 {
            var ids = store.subAgents.filter { !$0.isBuiltIn }.map(\.id)
            if let cur = ids.firstIndex(of: def.id) {
                ids.remove(at: cur)
                ids.insert(def.id, at: min(pos, ids.count))
                store.reorderSubAgents(ids)
            }
        }
        // The caller addresses a new agent by its id; only the built-in has an
        // alias, and `add` never creates that one.
        return def.id
    }

    func remove(id idSegment: String) throws {
        let id = realId(idSegment)
        guard let def = store.subAgent(id: id) else {
            throw ConfigError.unknownPath("subagents.\(idSegment)")
        }
        guard !def.isBuiltIn else {
            throw ConfigError.permissionDenied(
                reason: "The built-in sub agent is the target of every delegation that names no agent, so it cannot be deleted. You can change its model and instructions instead.")
        }
        store.removeSubAgent(id: id)
    }

    // MARK: - Ordering

    /// `subagents.order` — the custom agents in disclosure order.
    ///
    /// A flat field rather than a per-child one, for the same reason
    /// `ThinkingRulesCollection` does it: writing the whole array reorders in
    /// one audited operation. The built-in is not in the list — it is always
    /// first — so a caller cannot accidentally demote it.
    static func orderField() -> ConfigField {
        ClosureField(
            path: "subagents.order",
            displayName: "Sub agent order",
            description: "Ordered ids of the CUSTOM sub agents. This is the order the assistant sees them in; the built-in one is always first and is not part of this list.",
            valueSchema: .array(.string()),
            risk: .normal, revertable: true,
            reader: {
                .array(SubAgentStore.shared.subAgents
                    .filter { !$0.isBuiltIn }
                    .map { .string($0.id) })
            },
            writer: { v in
                guard case .array(let arr) = v else { throw ConfigError.typeMismatch(expected: "array") }
                let ids: [String] = arr.compactMap {
                    if case .string(let s) = $0 { return s } else { return nil }
                }
                let current = SubAgentStore.shared.subAgents.filter { !$0.isBuiltIn }.map(\.id)
                // Reject anything that is not a permutation: a short list would
                // silently move the omitted agents to the end, and an unknown id
                // would do nothing at all. Both are worse than an error.
                guard Set(ids) == Set(current), ids.count == current.count else {
                    throw ConfigError.invalidValue(
                        "Must be a permutation of the \(current.count) custom sub agent id(s). The built-in one is always first and must not appear.")
                }
                SubAgentStore.shared.reorderSubAgents(ids)
            }
        )
    }

    // MARK: - Helpers

    /// Accept a group id or a group name, so a caller can write what they see
    /// in `minis-config get groups` without copying a UUID.
    private static func resolveGroupId(_ raw: String) throws -> String {
        // Model groups are provider configuration — this one genuinely reads
        // ProviderConfigStore, unlike the roster accesses around it.
        let store = ProviderConfigStore.shared
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if store.group(for: trimmed) != nil { return trimmed }
        let matches = store.config.modelGroups.filter {
            $0.name.compare(trimmed, options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame
        }
        if matches.count == 1 { return matches[0].id }
        if matches.count > 1 {
            throw ConfigError.invalidValue("More than one model group is called '\(trimmed)' — use its id instead.")
        }
        let known = store.config.modelGroups.map(\.name).joined(separator: ", ")
        throw ConfigError.invalidValue("Unknown model group '\(trimmed)'. Available: \(known.isEmpty ? "(none configured)" : known)")
    }

    private func mutate(_ id: String, _ apply: (inout SubAgentDefinition) throws -> Void) throws {
        guard var def = store.subAgent(id: id) else {
            throw ConfigError.unknownPath("subagents.\(id)")
        }
        try apply(&def)
        store.upsertSubAgent(def)
    }

    /// The refusal used for the built-in's two fixed fields. Spelled out rather
    /// than a generic "read-only" so the caller learns WHY and what to do
    /// instead — the value is load-bearing for the model, not just protected.
    private func builtInFieldDenied(_ field: String) -> ConfigError {
        .permissionDenied(
            reason: "The built-in sub agent's \(field) is fixed: the assistant matches on the name and reads the description to decide what to delegate, and both are restored on every load. Change its model or instructions instead, or add your own sub agent.")
    }

    // MARK: - Field factories

    private func nameField(_ id: String, seg: String, isBuiltIn: Bool) -> ConfigField {
        ClosureField(
            path: "subagents.\(seg).name",
            displayName: "Name",
            description: isBuiltIn
                ? "Fixed for the built-in sub agent — it is the value the assistant passes as `agent`."
                : "How the assistant addresses this sub agent. Must be unique; at most \(SubAgentLimits.nameMaxLength) characters.",
            valueSchema: .string(maxLength: SubAgentLimits.nameMaxLength),
            // [T-subagent-config-honesty] The built-in's name is fixed and
            // every write is refused by the `guard !isBuiltIn` below. Without
            // this, ClosureField's `.readwrite` default made `topic-help`
            // advertise the path as writable, so an agent would try, get
            // permission_denied, and reasonably read that as a bug rather than
            // a rule. The guard stays as the enforcement; this only makes the
            // advertised metadata agree with it.
            access: isBuiltIn ? .readonly : .readwrite,
            risk: .sensitive, revertable: true,
            reader: { [self] in store.subAgent(id: id).map { .string($0.name) } ?? .null },
            writer: { [self] v in
                guard !isBuiltIn else { throw builtInFieldDenied("name") }
                guard case .string(let s) = v else { throw ConfigError.typeMismatch(expected: "string") }
                let name = s.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !name.isEmpty else { throw ConfigError.invalidValue("Name must not be empty") }
                guard name.count <= SubAgentLimits.nameMaxLength else {
                    throw ConfigError.invalidValue("Name must be at most \(SubAgentLimits.nameMaxLength) characters")
                }
                guard !store.subAgentNameIsTaken(name, excluding: id) else {
                    throw ConfigError.invalidValue("Another sub agent is already called '\(name)'")
                }
                try mutate(id) { $0.name = name }
            }
        )
    }

    private func descriptionField(_ id: String, seg: String, isBuiltIn: Bool) -> ConfigField {
        ClosureField(
            path: "subagents.\(seg).description",
            displayName: "Description",
            description: isBuiltIn
                ? "Fixed for the built-in sub agent — it is what the assistant reads to decide what to delegate."
                : "When to use this sub agent. The assistant picks by this text, so write it as \"use this when …\"; at most \(SubAgentLimits.descriptionMaxLength) characters. Details belong in instructions.",
            valueSchema: .string(maxLength: SubAgentLimits.descriptionMaxLength),
            // [T-subagent-config-honesty] The built-in's description is fixed and
            // every write is refused by the `guard !isBuiltIn` below. Without
            // this, ClosureField's `.readwrite` default made `topic-help`
            // advertise the path as writable, so an agent would try, get
            // permission_denied, and reasonably read that as a bug rather than
            // a rule. The guard stays as the enforcement; this only makes the
            // advertised metadata agree with it.
            access: isBuiltIn ? .readonly : .readwrite,
            risk: .sensitive, revertable: true,
            reader: { [self] in store.subAgent(id: id).map { .string($0.description) } ?? .null },
            writer: { [self] v in
                guard !isBuiltIn else { throw builtInFieldDenied("description") }
                guard case .string(let s) = v else { throw ConfigError.typeMismatch(expected: "string") }
                let desc = s.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !desc.isEmpty else { throw ConfigError.invalidValue("Description must not be empty") }
                guard desc.count <= SubAgentLimits.descriptionMaxLength else {
                    throw ConfigError.invalidValue("Description must be at most \(SubAgentLimits.descriptionMaxLength) characters — it costs main-conversation tokens on every turn")
                }
                try mutate(id) { $0.description = desc }
            }
        )
    }

    /// Writable for EVERY agent including the built-in: the built-in's
    /// instructions are the standing instructions for every delegation that
    /// names no agent, which is exactly the thing a user wants to set globally.
    private func instructionsField(_ id: String, seg: String) -> ConfigField {
        let isBuiltIn = id == SubAgentDefinition.builtInId
        return ClosureField(
            path: "subagents.\(seg).instructions",
            displayName: "Instructions",
            description: isBuiltIn
                ? "Standing instructions appended to every delegation that does not name a sub agent. Custom sub agents use their own instead — these are NOT prepended to them. At most \(SubAgentLimits.instructionsMaxLength) characters."
                : "Standing instructions appended to this sub agent's brief. Only this sub agent uses them. At most \(SubAgentLimits.instructionsMaxLength) characters. Empty to clear.",
            valueSchema: .string(maxLength: SubAgentLimits.instructionsMaxLength),
            risk: .normal, revertable: true,
            reader: { [self] in store.subAgent(id: id).map { .string($0.instructions) } ?? .null },
            writer: { [self] v in
                guard case .string(let s) = v else { throw ConfigError.typeMismatch(expected: "string") }
                let text = s.trimmingCharacters(in: .whitespacesAndNewlines)
                guard text.count <= SubAgentLimits.instructionsMaxLength else {
                    throw ConfigError.invalidValue("Instructions must be at most \(SubAgentLimits.instructionsMaxLength) characters")
                }
                try mutate(id) { $0.instructions = text }
            }
        )
    }

    /// Also writable for the built-in — "which model runs my sub agents" is a
    /// user decision, not part of the definition's identity.
    private func modelGroupField(_ id: String, seg: String) -> ConfigField {
        ClosureField(
            path: "subagents.\(seg).model_group",
            displayName: "Model group",
            description: "Model Group this sub agent runs on, by id or name. Empty string = Auto: the assistant chooses per task between this conversation's model, the default group and the light group.",
            valueSchema: .string(),
            risk: .sensitive, revertable: true,
            reader: { [self] in
                guard let def = store.subAgent(id: id) else { return .null }
                guard let gid = def.modelGroupId else { return .string("") }
                // Report the name when it resolves, so `get` output is readable;
                // a dangling id is reported verbatim rather than hidden.
                if let g = ProviderConfigStore.shared.group(for: gid) { return .string(g.name) }
                return .string(gid)
            },
            writer: { [self] v in
                guard case .string(let s) = v else { throw ConfigError.typeMismatch(expected: "string") }
                let raw = s.trimmingCharacters(in: .whitespacesAndNewlines)
                let gid = raw.isEmpty ? nil : try Self.resolveGroupId(raw)
                try mutate(id) { $0.modelGroupId = gid }
            }
        )
    }

    /// [T-subagent-thinking-override] The definition's reasoning override.
    ///
    /// Reported and written as the ThinkingLevel raw value ("high"), with the
    /// empty string meaning "not set" — the same "" = inherit convention
    /// `model_group` uses for Auto, rather than a second spelling for absent.
    private func thinkingField(_ id: String, seg: String) -> ConfigField {
        ClosureField(
            path: "subagents.\(seg).thinking",
            displayName: "Reasoning override",
            description: "Reasoning level every run of this sub agent uses, overriding its Model Group's default: \(ThinkingLevel.allCases.filter { $0 != .off }.map(\.rawValue).joined(separator: ", ")). Empty string = not set: follow the Model Group, or the delegating conversation when the model is Auto. Models that cannot reason this hard are clamped to their own ceiling.",
            valueSchema: .string(),
            risk: .sensitive, revertable: true,
            reader: { [self] in
                guard let def = store.subAgent(id: id) else { return .null }
                return .string(def.thinkingLevelOverride?.rawValue ?? "")
            },
            writer: { [self] v in
                guard case .string(let s) = v else { throw ConfigError.typeMismatch(expected: "string") }
                let raw = s.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                if raw.isEmpty {
                    try mutate(id) { $0.thinkingLevelOverride = nil }
                    return
                }
                guard let level = ThinkingLevel(rawValue: raw), level != .off else {
                    let valid = ThinkingLevel.allCases.filter { $0 != .off }.map(\.rawValue).joined(separator: ", ")
                    throw ConfigError.invalidValue("`thinking` must be one of: \(valid) — or \"\" to unset it")
                }
                try mutate(id) { $0.thinkingLevelOverride = level }
            }
        )
    }

    /// Read-only: whether this is the built-in definition. Exposed so `get`
    /// output explains on its own why some writes are refused.
    private func builtInField(_ id: String, seg: String) -> ConfigField {
        ReadOnlyField(
            path: "subagents.\(seg).built_in",
            displayName: "Built-in",
            description: "True for the general sub agent, which cannot be deleted and whose name and description are fixed.",
            valueSchema: .bool,
            reader: { SubAgentStore.shared.subAgent(id: id).map { .bool($0.isBuiltIn) } ?? .null }
        )
    }
}
