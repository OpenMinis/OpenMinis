import Foundation

private let logger = AppLogger(category: "SubAgentStore")

/// [T-subagent-own-store] The sub agent roster's own persistence.
///
/// ## Why this is not part of ProviderConfig
///
/// Sub agents used to live in `ProviderConfig.subAgents`. The original
/// reasoning was that a definition points at a ModelGroup, so it looked like
/// the same shape as `defaultSubGroupId` / `visionGroupId`, and ProviderConfig
/// already synced as one blob — so it cost "zero wiring points".
///
/// That produced a data-loss bug. `ProviderConfigStore.save()` deliberately
/// writes TWO mirrors of one in-memory config: `provider-config.json` (the
/// complete, downgrade-safe source) and the v3 SQLite DB. The mirror design is
/// fine, but sub agents only ever existed in ONE of them — `bulkReplace` has no
/// sub-agent table, so `dumpProviderConfig()` rebuilt a ProviderConfig with
/// `subAgents` defaulted to `[]`. On every launch where v3 is on and the DB is
/// non-empty (i.e. the user has at least one provider — the normal case), the
/// store does `self.config = <that dump>`, so the lossy mirror overwrote the
/// complete one and every custom agent was gone. It was not *visibly* empty
/// only because the read accessor ran `SubAgentRoster.normalize`, which
/// re-inserts the built-in — so the roster silently collapsed to just "general".
///
/// Patching the mirror would have worked. Moving out makes the whole class of
/// bug structurally impossible, and is also the honest modelling: name /
/// description / instructions are agent-behaviour fields with no provider
/// relationship. Only `modelGroupId` reaches into provider-land, and that is a
/// plain foreign key — the same way a session binding references a group.
///
/// ## Shape
///
/// Modelled on `EnvVarStore`: a `@MainActor` singleton over one atomic JSON
/// file in `Library/MinisChat`, publishing its list, emitting per-item
/// `markDirty` on every mutation. Sync is per-record (`SubAgentV3`) rather than
/// whole-file, so two devices editing different agents merge as a natural union
/// instead of one overwriting the other.
@MainActor
final class SubAgentStore: ObservableObject {
    static let shared = SubAgentStore()

    /// The roster in disclosure order, built-in first, always normalized.
    @Published private(set) var subAgents: [SubAgentDefinition] = []

    private let fileURL: URL

    init() {
        let libraryURL = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let baseURL = libraryURL.appendingPathComponent("MinisChat", isDirectory: true)
        try? FileManager.default.createDirectory(at: baseURL, withIntermediateDirectories: true)
        self.fileURL = baseURL.appendingPathComponent("sub-agents.json")
        self.subAgents = Self.load(from: fileURL)
    }

    // MARK: - Persistence

    /// Never throws and never returns a roster without the built-in: this reads
    /// data that may have arrived over iCloud from a newer build, and a bad
    /// roster must not be able to block startup.
    private static func load(from url: URL) -> [SubAgentDefinition] {
        guard let data = try? Data(contentsOf: url) else {
            // No file yet — first launch, or the roster has never been touched.
            return SubAgentRoster.normalize([])
        }
        guard let decoded = try? JSONDecoder().decode([SubAgentDefinition].self, from: data) else {
            logger.error("[SubAgents] sub-agents.json could not be decoded — falling back to the built-in only")
            return SubAgentRoster.normalize([])
        }
        return SubAgentRoster.normalize(decoded) { logger.warning("\($0)") }
    }

    private func persist() {
        do {
            let data = try JSONEncoder().encode(subAgents)
            try data.write(to: fileURL, options: .atomic)
        } catch {
            logger.error("[SubAgents] failed to save sub-agents.json: \(error)")
        }
    }

    /// Re-read from disk. Used after an inbound sync merge writes the file
    /// underneath us, mirroring `EnvVarStore.reloadFromDisk`.
    func reloadFromDisk() {
        subAgents = Self.load(from: fileURL)
    }

    // MARK: - Reads

    func subAgent(id: String) -> SubAgentDefinition? {
        subAgents.first { $0.id == id }
    }

    /// Whether another definition already uses this name.
    ///
    /// Folded through `SubAgentRoster.nameKey`, the same key the sync merge and
    /// `resolve(name:)` use, so "already taken" means exactly "the model could
    /// not tell these two apart".
    func subAgentNameIsTaken(_ name: String, excluding id: String?) -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        return subAgents.contains {
            $0.id != id && SubAgentRoster.nameKey($0.name) == SubAgentRoster.nameKey(trimmed)
        }
    }

    var canAddSubAgent: Bool { subAgents.count < SubAgentLimits.maxCount }

    // MARK: - Mutations

    /// Insert or update one definition. Clamped on the way in, so the stored
    /// roster is valid regardless of what the caller passed.
    func upsertSubAgent(_ definition: SubAgentDefinition) {
        var list = subAgents
        var incoming = definition.clamped()
        incoming.updatedAt = Date()
        // [T-sub-agents-v1] The built-in's identity is not the user's to change:
        // its name and description are what the delegating model reads, and it
        // is the fallback for every delegation that names no agent. The editor
        // already shows them read-only; enforcing it here too covers a synced
        // payload written by another device or a future caller. Model and
        // instructions are kept as supplied.
        if incoming.isBuiltIn {
            let canonical = SubAgentDefinition.makeBuiltIn()
            incoming.name = canonical.name
            incoming.description = canonical.description
        }
        if let idx = list.firstIndex(where: { $0.id == incoming.id }) {
            incoming.sortOrder = list[idx].sortOrder
            list[idx] = incoming
        } else {
            guard list.count < SubAgentLimits.maxCount else {
                logger.warning("[SubAgents] refusing to add '\(incoming.name)' — roster already at \(SubAgentLimits.maxCount)")
                return
            }
            incoming.sortOrder = list.count
            list.append(incoming)
        }
        apply(SubAgentRoster.normalize(list), dirtyIds: [incoming.id])
    }

    /// Delete one definition. The built-in cannot be removed — it is the target
    /// of every delegation that names no agent.
    func removeSubAgent(id: String) {
        guard id != SubAgentDefinition.builtInId else {
            logger.warning("[SubAgents] refusing to delete the built-in definition")
            return
        }
        var list = subAgents
        guard list.contains(where: { $0.id == id }) else { return }
        list.removeAll { $0.id == id }
        subAgents = SubAgentRoster.normalize(list)
        persist()
        // [T-subagent-own-store] The row is really removed; the anti-resurrection
        // guarantee is the tombstone that markDirty(op: "delete") writes, the
        // same mechanism the provider V3 types use. Without it a fetchRecentV2
        // racing ahead of the cloud delete would re-pull the live record and the
        // merger would re-insert the agent the user just deleted.
        Task { await ChatStore.shared.markDirty(recordType: "SubAgentV3", recordId: id, operation: "delete") }
    }

    /// Reorder the custom entries. Order is disclosure order in the roster the
    /// main model reads, so it is a real setting. The built-in stays first.
    func reorderSubAgents(_ orderedIds: [String]) {
        let byId = Dictionary(uniqueKeysWithValues: subAgents.map { ($0.id, $0) })
        var list: [SubAgentDefinition] = []
        for id in orderedIds where id != SubAgentDefinition.builtInId {
            if let d = byId[id] { list.append(d) }
        }
        // Anything the caller omitted keeps its relative position at the end,
        // so a partial list can never silently drop a definition.
        for d in subAgents where d.id != SubAgentDefinition.builtInId && !orderedIds.contains(d.id) {
            list.append(d)
        }
        for i in list.indices { list[i].sortOrder = i + 1 }
        let normalized = SubAgentRoster.normalize(list)
        // [T-subagent-own-store] One upsert PER id: a single record cannot
        // express "the list moved", and sortOrder lives on each definition.
        // Same reasoning as the thinking-rule reorder emission.
        apply(normalized, dirtyIds: normalized.map(\.id))
    }

    /// Clear a dangling group reference. Called when a model group is emptied or
    /// deleted out from under a definition — the agent stays, it just reverts to
    /// letting the delegating model choose.
    func clearModelGroup(_ groupId: String) {
        var list = subAgents
        var touched: [String] = []
        for i in list.indices where list[i].modelGroupId == groupId {
            logger.warning("[SubAgents] '\(list[i].name)' pointed at a removed group — reverting to model-chosen")
            list[i].modelGroupId = nil
            list[i].updatedAt = Date()
            touched.append(list[i].id)
        }
        guard !touched.isEmpty else { return }
        apply(SubAgentRoster.normalize(list), dirtyIds: touched)
    }

    /// Replace the whole roster from an inbound sync merge, WITHOUT re-emitting
    /// markDirty — the caller decides what to push back.
    func applyMergedFromSync(_ roster: [SubAgentDefinition]) {
        subAgents = SubAgentRoster.normalize(roster)
        persist()
    }

    private func apply(_ roster: [SubAgentDefinition], dirtyIds: [String]) {
        subAgents = roster
        persist()
        // Only push ids that still exist — normalize's count bound can drop a
        // record, and pushing a missing id would upload nothing anyway (the
        // builder returns nil) while leaving a dirty row behind.
        let live = Set(roster.map(\.id))
        for id in dirtyIds where live.contains(id) {
            Task { await ChatStore.shared.markDirty(recordType: "SubAgentV3", recordId: id, operation: "upsert") }
        }
    }

    /// Re-emit an upsert for every definition. Used by the force-sync path so a
    /// device can re-push its whole roster.
    func markAllDirty() {
        for d in subAgents {
            Task { await ChatStore.shared.markDirty(recordType: "SubAgentV3", recordId: d.id, operation: "upsert") }
        }
    }
}
