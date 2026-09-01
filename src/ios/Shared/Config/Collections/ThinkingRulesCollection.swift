import Foundation

/// Exposes per-provider thinking rules to `minis-config` under
/// `thinkingrules.<instanceId>:<ruleId>.…`.
///
/// WHY THE COMPOSITE CHILD ID. A rule belongs to a provider instance, so the natural
/// path would be `providers.<instanceId>.thinkingRules.<ruleId>.<field>`. That cannot
/// work: `ConfigRegistry.resolveField` splits a path with `maxSplits: 2` into exactly
/// `[base, id, leaf]`, so a collection gets ONE id segment. Encoding both halves into
/// that segment as `<instanceId>:<ruleId>` keeps the whole feature inside the existing
/// resolver contract instead of changing path parsing for every collection.
/// `:` is safe as the separator — instance ids are UUIDs and rule ids are either UUIDs
/// or `builtin:<label>:<scope>:<pattern>` (see below for how those are kept out).
///
/// WHAT IS WRITABLE. Only user-authored (`.custom`) rules. Built-ins are a static code
/// registry computed per request — they are never persisted and never synced — so they
/// are exposed READ-ONLY through the aggregate `thinkingrules` summary and are not
/// enumerated as children at all. A built-in therefore has no writable path, which is
/// what makes "cannot modify a built-in" a structural guarantee rather than a runtime
/// check that could be forgotten. Attempts to remove one by id still fail loudly.
///
/// ORDERING IS PRIORITY. Rules are first-match-wins, so list order is a real setting.
/// It is exposed the same way `GroupsCollection` exposes `entries`: one array-valued
/// field (`thinkingrules.<instanceId>.order`) holding the ordered rule ids. Writing it
/// reorders in one audited operation.
@MainActor
struct ThinkingRulesCollection: ConfigCollection {
    let basePath = "thinkingrules"
    let displayName = "Thinking rules"
    let description = "Per-provider rules deciding which thinking parameters a request carries. First match wins."
    let addable = true
    let removable = true
    /// Sensitive, not normal: a wrong rule silently changes the wire format of every
    /// request to that provider, and the failure mode is a degraded answer rather than
    /// an error — exactly the class of change that deserves an explicit confirmation.
    let risk: ConfigRisk = .sensitive

    /// Add payload:
    /// {
    ///   "provider": "<instanceId>",              // required
    ///   "label": "my rule",                      // required
    ///   "scope": "all" | "<model-pattern>",      // default "all"; "*" wildcards
    ///   "wire_format": {"kind": "reasoningEffort", "offValue": "none"},   // required
    ///   "reasoning_echo_field": "reasoning_content",                      // optional
    ///   "reasoning_echo_timing": "everyTurn"|"afterToolUseOnly"|"never",  // optional
    ///   "position": 0                            // optional insert index; default = top
    /// }
    let addPayloadSchema: ConfigValueSchema = .json

    // MARK: - Children

    /// Only `.custom` rules are addressable. Built-ins are deliberately absent — see
    /// the type doc. Composite id keeps the owning instance recoverable from the path.
    func childIds() -> [String] {
        let store = ProviderConfigStore.shared
        var out: [String] = []
        for inst in store.config.instances {
            for rule in ThinkingRuleCache.shared.rules(for: inst.id) where rule.kind == .custom {
                out.append("\(inst.id):\(rule.id)")
            }
        }
        return out
    }

    func fields(for id: String) -> [ConfigField] {
        guard let (instanceId, ruleId) = split(id), rule(instanceId, ruleId) != nil else { return [] }
        return [
            labelField(id, instanceId, ruleId),
            scopeField(id, instanceId, ruleId),
            wireFormatField(id, instanceId, ruleId),
            reasoningEchoField(id, instanceId, ruleId),
            providerField(id, instanceId),
        ]
    }

    // MARK: - Add / remove

    func add(_ payload: ConfigValue) throws -> String {
        guard case .object(let dict) = payload else {
            throw ConfigError.invalidValue("Expected JSON object")
        }
        guard case .string(let instanceId)? = dict["provider"], !instanceId.isEmpty else {
            throw ConfigError.invalidValue("`provider` (instance id) required")
        }
        guard ProviderConfigStore.shared.instance(for: instanceId) != nil else {
            throw ConfigError.invalidValue("Unknown provider instance: \(instanceId)")
        }
        guard case .string(let label)? = dict["label"], !label.isEmpty else {
            throw ConfigError.invalidValue("`label` required")
        }
        guard let wfValue = dict["wire_format"] else {
            throw ConfigError.invalidValue("`wire_format` required, e.g. {\"kind\":\"reasoningEffort\"}")
        }
        let wireFormat = try Self.decodeWireFormat(wfValue)

        var scope: ThinkingRule.Scope = .allModels
        if case .string(let s)? = dict["scope"], !s.isEmpty, s != "all" {
            scope = .modelPattern(s)
        }

        var echo: ReasoningEchoPolicy? = nil
        if case .string(let f)? = dict["reasoning_echo_field"], !f.isEmpty {
            var timingRaw = "afterToolUseOnly"
            if case .string(let t)? = dict["reasoning_echo_timing"], !t.isEmpty { timingRaw = t }
            // Reuse the persistence decoder rather than re-deriving the mapping, so the
            // CLI can never accept a timing the database would store differently.
            echo = ReasoningEchoPolicy.fromPersisted(field: f, timing: timingRaw)
        }

        let rule = ThinkingRule(kind: .custom, scope: scope, wireFormat: wireFormat,
                                reasoningEcho: echo, label: label)

        // Default insert position is the TOP: a rule authored to override a built-in is
        // useless below it, and "first match wins" makes top the only position that
        // guarantees the user's intent takes effect.
        var position = 0
        if case .int(let i)? = dict["position"], i >= 0 { position = i }

        let existing = ThinkingRuleCache.shared.rules(for: instanceId).filter { $0.kind == .custom }
        var ordered = existing.map(\.id)
        ordered.insert(rule.id, at: min(position, ordered.count))

        // Optimistic cache write so a subsequent `get` in the same CLI session sees the
        // rule even before the DB actor round-trips.
        var newList = existing
        newList.insert(rule, at: min(position, newList.count))
        ThinkingRuleCache.shared.set(newList, for: instanceId)

        let store = ProviderConfigStore.shared
        Task {
            _ = await store.saveThinkingRule(rule, instanceId: instanceId,
                                             sortOrder: min(position, ordered.count - 1))
            if ordered.count > 1 {
                _ = await store.reorderThinkingRules(instanceId: instanceId, orderedIds: ordered)
            }
        }
        return "\(instanceId):\(rule.id)"
    }

    func remove(id: String) throws {
        guard let (instanceId, ruleId) = split(id) else {
            throw ConfigError.invalidValue("Expected '<instanceId>:<ruleId>', got '\(id)'")
        }
        // Built-in ids are `builtin:<label>:<scope>:<pattern>` and never appear in
        // childIds(), but a caller can still type one — reject explicitly rather than
        // reporting a confusing "not found".
        if ruleId.hasPrefix("builtin:") {
            throw ConfigError.permissionDenied(
                reason: "Built-in rules are part of the app, not user data — they cannot be deleted. Add a rule above one to override it.")
        }
        guard let existing = rule(instanceId, ruleId) else {
            throw ConfigError.unknownPath("thinkingrules.\(id)")
        }
        guard existing.kind == .custom else {
            throw ConfigError.permissionDenied(reason: "Only user-authored rules can be deleted.")
        }
        let remaining = ThinkingRuleCache.shared.rules(for: instanceId).filter { $0.id != ruleId }
        ThinkingRuleCache.shared.set(remaining, for: instanceId)
        let store = ProviderConfigStore.shared
        Task { _ = await store.deleteThinkingRule(id: ruleId, instanceId: instanceId) }
    }

    // MARK: - Ordering

    /// `thinkingrules.<instanceId>.order` — the ordered rule ids for one provider.
    ///
    /// Registered as a FLAT field (one per instance, from `ConfigRegistry+Builtins`)
    /// rather than as a collection child, because the collection's own children are
    /// keyed `<instanceId>:<ruleId>` and `<instanceId>.order` would otherwise be read as
    /// a child named `<instanceId>` with leaf `order`, which does not exist. Writing the
    /// whole array covers reorder in one audited operation, exactly like
    /// `groups.<id>.entries`.
    static func orderField(instanceId: String, instanceLabel: String) -> ConfigField {
        ClosureField(
            path: "thinkingrules.\(instanceId).order",
            displayName: "Rule order — \(instanceLabel)",
            description: "Ordered custom-rule ids. First match wins, so index 0 has highest priority.",
            valueSchema: .array(.string()),
            risk: .sensitive, revertable: true,
            reader: {
                .array(ThinkingRuleCache.shared.rules(for: instanceId)
                    .filter { $0.kind == .custom }
                    .map { .string($0.id) })
            },
            writer: { v in
                guard case .array(let arr) = v else { throw ConfigError.typeMismatch(expected: "array") }
                let ids: [String] = arr.compactMap {
                    if case .string(let s) = $0 { return s } else { return nil }
                }
                let current = ThinkingRuleCache.shared.rules(for: instanceId).filter { $0.kind == .custom }
                // Reject the whole batch unless it is a permutation of the existing ids.
                // A partial list would silently drop rules; an unknown id would silently
                // do nothing. Both are worse than an error the caller can act on.
                guard Set(ids) == Set(current.map(\.id)), ids.count == current.count else {
                    throw ConfigError.invalidValue(
                        "Must be a permutation of this provider's \(current.count) custom rule id(s).")
                }
                let byId = Dictionary(uniqueKeysWithValues: current.map { ($0.id, $0) })
                ThinkingRuleCache.shared.set(ids.compactMap { byId[$0] }, for: instanceId)
                let store = ProviderConfigStore.shared
                Task { _ = await store.reorderThinkingRules(instanceId: instanceId, orderedIds: ids) }
            }
        )
    }

    // MARK: - Helpers

    private func split(_ id: String) -> (String, String)? {
        // Rule ids may themselves contain ':' (built-in ids do), so split ONCE on the
        // first separator — the instance id is a UUID and never contains one.
        guard let idx = id.firstIndex(of: ":") else { return nil }
        let inst = String(id[id.startIndex..<idx])
        let rule = String(id[id.index(after: idx)...])
        return (inst.isEmpty || rule.isEmpty) ? nil : (inst, rule)
    }

    private func rule(_ instanceId: String, _ ruleId: String) -> ThinkingRule? {
        ThinkingRuleCache.shared.rules(for: instanceId).first { $0.id == ruleId }
    }

    /// Persist an edited rule, keeping its position in the list.
    private func mutate(_ instanceId: String, _ ruleId: String,
                        _ apply: (ThinkingRule) -> ThinkingRule) throws {
        guard let old = rule(instanceId, ruleId) else {
            throw ConfigError.unknownPath("thinkingrules.\(instanceId):\(ruleId)")
        }
        guard old.kind == .custom else {
            throw ConfigError.permissionDenied(reason: "Built-in rules are read-only.")
        }
        let updated = apply(old)
        var list = ThinkingRuleCache.shared.rules(for: instanceId)
        let idx = list.firstIndex { $0.id == ruleId } ?? 0
        list[idx] = updated
        ThinkingRuleCache.shared.set(list, for: instanceId)
        let store = ProviderConfigStore.shared
        Task { _ = await store.saveThinkingRule(updated, instanceId: instanceId, sortOrder: idx) }
    }

    /// The `kind` values `ThinkingWireFormat.fromPersistedJSON` accepts. Spelled out
    /// because that enum is `private` to the coding file; kept here purely to make the
    /// error message actionable. A value missing from this list still round-trips fine —
    /// the list only affects help text.
    static let wireFormatKinds = [
        "anthropicThinking", "booleanToggle", "customPath", "deepSeekSibling",
        "extraBodyToggle", "geminiBudget", "geminiThinkingLevel", "omitEverything",
        "qwenDual", "qwenRootOnly", "reasoningEffort", "reasoningEffortNested",
    ]

    /// Shared by `add` and the `wireFormat` field writer so the CLI accepts exactly one
    /// vocabulary — the same `{"kind":…}` object the DB persists.
    ///
    /// ConfigValue and the persistence layer speak different currencies (`ConfigValue`
    /// vs `[String: Any]`) and neither ships a converter, so JSON is used as the bridge.
    /// That is not a workaround for its own sake: it guarantees the CLI can express
    /// exactly what the DB can store, with no hand-written mapping to drift.
    static func decodeWireFormat(_ value: ConfigValue) throws -> ThinkingWireFormat {
        guard case .object = value else {
            throw ConfigError.typeMismatch(expected: "object like {\"kind\":\"reasoningEffort\"}")
        }
        guard let data = value.jsonString().data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let parsed = ThinkingWireFormat.fromPersistedJSON(obj) else {
            throw ConfigError.invalidValue(
                "Unrecognised wire_format. Valid `kind` values: \(wireFormatKinds.joined(separator: ", "))")
        }
        return parsed
    }

    /// Inverse bridge, same reasoning as `decodeWireFormat`.
    static func encodeWireFormat(_ wf: ThinkingWireFormat) -> ConfigValue {
        guard let data = try? JSONSerialization.data(withJSONObject: wf.persistedJSON),
              let str = String(data: data, encoding: .utf8),
              let value = ConfigValue.decode(json: str) else { return .null }
        return value
    }

    // MARK: - Field factories

    private func labelField(_ cid: String, _ inst: String, _ rid: String) -> ConfigField {
        ClosureField(
            path: "thinkingrules.\(cid).label",
            displayName: "Label",
            description: "User-visible rule name.",
            valueSchema: .string(maxLength: 200),
            risk: .normal, revertable: true,
            reader: { [self] in rule(inst, rid).map { .string($0.label) } ?? .null },
            writer: { [self] v in
                guard case .string(let s) = v, !s.isEmpty else {
                    throw ConfigError.invalidValue("Label must be a non-empty string")
                }
                try mutate(inst, rid) {
                    ThinkingRule(kind: $0.kind, scope: $0.scope, wireFormat: $0.wireFormat,
                                 reasoningEcho: $0.reasoningEcho, label: s, id: $0.id)
                }
            }
        )
    }

    private func scopeField(_ cid: String, _ inst: String, _ rid: String) -> ConfigField {
        ClosureField(
            path: "thinkingrules.\(cid).scope",
            displayName: "Scope",
            description: "\"all\" for every model, or a model-id pattern where * matches any characters.",
            valueSchema: .string(maxLength: 200),
            risk: .sensitive, revertable: true,
            reader: { [self] in
                guard let r = rule(inst, rid) else { return .null }
                switch r.scope {
                case .allModels: return .string("all")
                case .modelPattern(let p): return .string(p)
                }
            },
            writer: { [self] v in
                guard case .string(let s) = v, !s.isEmpty else {
                    throw ConfigError.invalidValue("Scope must be \"all\" or a model pattern")
                }
                let scope: ThinkingRule.Scope = (s == "all") ? .allModels : .modelPattern(s)
                try mutate(inst, rid) {
                    ThinkingRule(kind: $0.kind, scope: scope, wireFormat: $0.wireFormat,
                                 reasoningEcho: $0.reasoningEcho, label: $0.label, id: $0.id)
                }
            }
        )
    }

    private func wireFormatField(_ cid: String, _ inst: String, _ rid: String) -> ConfigField {
        ClosureField(
            path: "thinkingrules.\(cid).wireFormat",
            displayName: "Wire format",
            description: "JSON object {\"kind\":…} describing the fields this rule puts on the request. For qwen models pick by ENDPOINT, not by model name: \"qwenDual\" (enable_thinking + thinking_budget at the root AND inside extra_body) is for Alibaba DashScope itself, while \"qwenRootOnly\" sends a bare root-level enable_thinking with no extra_body and no thinking_budget — use it for self-hosted vLLM/SGLang and any OpenAI-compatible relay, which commonly reject both of those fields with a 400.",
            valueSchema: .json,
            risk: .sensitive, revertable: true,
            reader: { [self] in
                guard let r = rule(inst, rid), let wf = r.wireFormat else { return .null }
                return Self.encodeWireFormat(wf)
            },
            writer: { [self] v in
                let wf = try Self.decodeWireFormat(v)
                try mutate(inst, rid) {
                    ThinkingRule(kind: $0.kind, scope: $0.scope, wireFormat: wf,
                                 reasoningEcho: $0.reasoningEcho, label: $0.label, id: $0.id)
                }
            }
        )
    }

    private func reasoningEchoField(_ cid: String, _ inst: String, _ rid: String) -> ConfigField {
        ClosureField(
            path: "thinkingrules.\(cid).reasoningEcho",
            displayName: "Reasoning echo",
            description: "JSON {\"field\":\"reasoning_content\",\"timing\":\"everyTurn|afterToolUseOnly|never\"}, or null to disable.",
            valueSchema: .json,
            risk: .normal, revertable: true,
            reader: { [self] in
                guard let r = rule(inst, rid), let echo = r.reasoningEcho else { return .null }
                return .object(["field": .string(echo.fieldName),
                                "timing": .string(echo.persistedTiming)])
            },
            writer: { [self] v in
                var echo: ReasoningEchoPolicy? = nil
                if case .object(let o) = v {
                    guard case .string(let f)? = o["field"], !f.isEmpty else {
                        throw ConfigError.invalidValue("`field` required, or pass null to disable")
                    }
                    var timingRaw = "afterToolUseOnly"
                    if case .string(let t)? = o["timing"], !t.isEmpty { timingRaw = t }
                    echo = ReasoningEchoPolicy.fromPersisted(field: f, timing: timingRaw)
                } else if case .null = v {
                    echo = nil
                } else {
                    throw ConfigError.typeMismatch(expected: "object or null")
                }
                try mutate(inst, rid) {
                    ThinkingRule(kind: $0.kind, scope: $0.scope, wireFormat: $0.wireFormat,
                                 reasoningEcho: echo, label: $0.label, id: $0.id)
                }
            }
        )
    }

    /// Read-only: a rule cannot be moved between providers (its id is scoped by the
    /// owning instance in storage). Exposed so `get` output is self-describing.
    private func providerField(_ cid: String, _ inst: String) -> ConfigField {
        ReadOnlyField(
            path: "thinkingrules.\(cid).provider",
            displayName: "Provider instance",
            description: "Owning provider instance id. Delete and re-add to move a rule.",
            valueSchema: .string(),
            reader: {
                guard let i = ProviderConfigStore.shared.instance(for: inst) else { return .string(inst) }
                return .string("\(i.label) (\(inst))")
            }
        )
    }
}
