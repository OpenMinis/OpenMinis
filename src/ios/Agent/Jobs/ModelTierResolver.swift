import Foundation

private let logger = AppLogger(category: "SubAgentModelResolver")

// [T-sub-agents-v1] Where a sub agent's model comes from.
//
// There are exactly two sources, and they are the whole mechanism: the sub
// agent definition either pins a Model Group or it does not. The earlier
// primary/sub tier — chosen by the delegating model per call, with an automatic
// downgrade when a "Sub" group happened to be configured — is gone. It asked
// the model to make a routing decision it had no basis for, and the downgrade
// fired on the existence of a cheaper group rather than on task fitness.
//
// Persisted as the raw value in `model_origin` / `HelperModelIdentity`. Old
// payloads carrying `tier_used: primary|sub` are read by tolerant accessors
// that fall back to nil, so history renders without them.
enum HelperModelOrigin: String {
    /// The definition names a Model Group and that group routed.
    case pinned
    /// Auto + `same_as_me`: the child runs on the parent conversation's binding.
    case inherited
    /// Auto + `default_model`: the user's configured default group.
    case defaultGroup = "default_group"
    /// Auto + `sub_model`: the user's configured light/economical group.
    case subGroup = "sub_group"
}

/// [T-sub-agents-v1] What the delegating model asked for when the chosen sub
/// agent is set to Auto (no pinned group). A definition that pins a group
/// ignores this entirely — the user's configuration outranks the model.
///
/// The three options are deliberately about INTENT, not about model names: the
/// delegating model knows how hard the task is, the user knows which of their
/// models is strong and which is cheap. The wire values are what the tool
/// schema exposes.
enum SubAgentModelChoice: String {
    /// Continue with the same model this conversation runs on.
    case sameAsParent = "same_as_me"
    /// The user's default group — typically their strongest model.
    case defaultModel = "default_model"
    /// The user's light group — typically smaller, faster, cheaper.
    case subModel = "sub_model"

    /// Unknown / absent values fall back to the parent's model: the safe
    /// direction, since it is what the conversation is already using.
    static func parse(_ raw: String?) -> SubAgentModelChoice {
        guard let raw, let v = SubAgentModelChoice(rawValue: raw.lowercased()) else { return .sameAsParent }
        return v
    }
}

/// The resolved binding for a sub agent session plus where it came from.
struct HelperModelResolution {
    let source: SessionModelSource
    let entry: ModelEntry
    let origin: HelperModelOrigin
    /// True when the definition pinned a group that could not be routed, so
    /// this resolution fell back to inheriting. Surfaced in the result JSON as
    /// `model_group_unavailable`: silently running on a different model than
    /// the user pinned is exactly the kind of thing that reads as a bug months
    /// later.
    var modelGroupUnavailable: Bool = false
    /// Human label for the result JSON / helper sheet badge.
    var modelLabel: String { entry.model.displayName }
}

@MainActor
enum SubAgentModelResolver {

    /// Resolve the model source a new sub agent session should be bound to.
    ///
    /// - `pinned`: the definition's `modelGroupId` → `ModelGroupRouter.resolve`
    ///   → `.group`, routed with the PARENT's session id so a load-balanced
    ///   group picks deterministically per conversation (same rule title
    ///   generation uses).
    /// - `inherited`: the parent's `primarySource` passed through VERBATIM.
    ///   This is load-bearing: a parent bound to a group stays bound to the
    ///   group, keeping its routing strategy and fallback. Collapsing it to
    ///   whichever entry the parent resolves to right now would silently drop
    ///   group fallback for the child. `resolveSourceForSubTasks` is used only
    ///   to compute the concrete entry to report, never to replace the source.
    ///   With no binding at all, the parent's current entry is pinned instead.
    static func resolve(subAgent: SubAgentDefinition?,
                        parent: AIChatViewModel,
                        choice: SubAgentModelChoice = .sameAsParent) -> HelperModelResolution? {
        let store = ProviderConfigStore.shared

        func inherited() -> HelperModelResolution? {
            if let sid = parent.sessionId, let binding = store.binding(for: sid),
               let entry = AIChatViewModel.resolveSourceForSubTasks(binding.primarySource, sessionId: sid, store: store) {
                return HelperModelResolution(source: binding.primarySource, entry: entry, origin: .inherited)
            }
            guard let entry = parent.resolveCurrentEntry() else {
                logger.warning("resolve(inherited): parent has no resolvable model")
                return nil
            }
            return HelperModelResolution(source: .directEntry(modelEntryId: entry.id), entry: entry, origin: .inherited)
        }

        /// One of the two configured group pointers, when it is routable.
        /// Returns nil (rather than a fallback) so the caller can decide —
        /// an unconfigured Sub group must land on the parent's model, not on
        /// the Default group the user did not ask for.
        func configuredGroup(_ gid: String?, label: String,
                             origin: HelperModelOrigin) -> HelperModelResolution? {
            guard let gid, let group = store.group(for: gid) else {
                logger.info("resolve(\(label)): no group configured — using the parent's model")
                return nil
            }
            let routeSid = parent.sessionId ?? UUID().uuidString
            guard let entryId = ModelGroupRouter.resolve(group: group, sessionId: routeSid, store: store),
                  let entry = store.entry(for: entryId) else {
                logger.info("resolve(\(label)): group '\(group.name)' has no routable member — using the parent's model")
                return nil
            }
            return HelperModelResolution(source: .group(groupId: gid, resolvedEntryId: entryId),
                                         entry: entry, origin: origin)
        }

        // Auto: the definition pins nothing, so the delegating model's
        // `model_choice` decides between the parent's model and the user's two
        // configured groups. Every branch degrades to the parent's model rather
        // than failing the delegation.
        guard let gid = subAgent?.modelGroupId else {
            switch choice {
            case .sameAsParent:
                return inherited()
            case .defaultModel:
                return configuredGroup(store.defaultPrimaryGroupId, label: "default_model",
                                       origin: .defaultGroup) ?? inherited()
            case .subModel:
                return configuredGroup(store.defaultSubGroupId, label: "sub_model",
                                       origin: .subGroup) ?? inherited()
            }
        }

        guard let group = store.group(for: gid) else {
            logger.info("resolve(pinned): group \(gid.prefix(8)) not found — inheriting the parent's model")
            guard var fallback = inherited() else { return nil }
            fallback.modelGroupUnavailable = true
            return fallback
        }
        let routeSid = parent.sessionId ?? UUID().uuidString
        guard let entryId = ModelGroupRouter.resolve(group: group, sessionId: routeSid, store: store),
              let entry = store.entry(for: entryId) else {
            logger.info("resolve(pinned): group '\(group.name)' has no routable member — inheriting the parent's model")
            guard var fallback = inherited() else { return nil }
            fallback.modelGroupUnavailable = true
            return fallback
        }
        return HelperModelResolution(source: .group(groupId: gid, resolvedEntryId: entryId),
                                     entry: entry, origin: .pinned)
    }
}
