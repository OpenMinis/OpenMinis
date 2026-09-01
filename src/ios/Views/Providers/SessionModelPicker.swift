import SwiftUI

private let pickerLog = AppLogger(category: "ModelPicker")

/// In-session model picker — thin wrapper around `UnifiedModelPicker` that adds
/// session binding logic and the non-text-output confirmation alert.
struct SessionModelPicker: View {
    let sessionId: String?
    var ensureSessionId: (() async -> String)?
    @ObservedObject private var store = ProviderConfigStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var pendingNonTextOutput: PendingSelection?

    private struct PendingSelection: Identifiable {
        let id = UUID()
        let entry: ModelEntry
        let group: ModelGroup?
        let modalityLabel: String
        let isGroupBind: Bool
    }

    init(sessionId: String?, ensureSessionId: (() async -> String)? = nil) {
        self.sessionId = sessionId
        self.ensureSessionId = ensureSessionId
    }

    private var currentEntryId: String? {
        guard let sid = sessionId,
              let binding = store.binding(for: sid) else { return nil }
        switch binding.primarySource {
        case .directEntry(let entryId, _): return entryId
        case .group(_, let resolvedEntryId): return resolvedEntryId
        }
    }

    private var currentGroupId: String? {
        guard let sid = sessionId else { return store.defaultPrimaryGroupId }
        guard let binding = store.binding(for: sid) else {
            return store.defaultPrimaryGroupId
        }
        if case .group(let groupId, _) = binding.primarySource {
            return groupId
        }
        return nil
    }

    var body: some View {
        UnifiedModelPicker(config: .init(
            title: "Choose Model",
            mode: .single,
            groupScope: .all,
            dismissOnSelect: false,
            currentEntryId: { [self] in currentEntryId },
            currentGroupId: { [self] in currentGroupId },
            onSelect: { entry in handleEntryTap(entry) },
            onSelectGroup: { group in handleGroupTap(group) },
            onSelectInGroup: { entry, group in handleEntryTap(entry, inGroup: group) }
        ))
        .alert(
            AppLocalized("This model may not work as an Agent"),
            isPresented: Binding(
                get: { pendingNonTextOutput != nil },
                set: { if !$0 { pendingNonTextOutput = nil } }
            ),
            presenting: pendingNonTextOutput
        ) { selection in
            Button(AppLocalized("Choose another model"), role: .cancel) {
                pendingNonTextOutput = nil
            }
            Button(AppLocalized("Use anyway")) {
                let entry = selection.entry
                let group = selection.group
                let isGroupBind = selection.isGroupBind
                pendingNonTextOutput = nil
                if isGroupBind, let group {
                    bindToGroup(group)
                } else {
                    bindToEntry(entry, inGroup: group)
                }
            }
        } message: { selection in
            Text(AppLocalized("\(selection.entry.model.displayName) outputs \(selection.modalityLabel) and usually can't drive an Agent task. Pick a text-output model for the session, or add this model to Available Models in Agent Loop so the agent can call it via minis-model-use when it needs to generate \(selection.modalityLabel)."))
        }
    }

    // MARK: - Non-text output guard

    private func nonTextOutputLabel(for model: LLMModel) -> String? {
        guard let modality = model.modalityOverride else { return nil }
        if modality.contains(.imageOutput) { return AppLocalized("image") }
        if modality.contains(.audioOutput) { return AppLocalized("audio") }
        if modality.contains(.videoOutput) { return AppLocalized("video") }
        return nil
    }

    private func handleEntryTap(_ entry: ModelEntry, inGroup group: ModelGroup? = nil) {
        if let label = nonTextOutputLabel(for: entry.model) {
            pendingNonTextOutput = PendingSelection(entry: entry, group: group, modalityLabel: label, isGroupBind: false)
            return
        }
        bindToEntry(entry, inGroup: group)
    }

    private func handleGroupTap(_ group: ModelGroup) {
        guard let sid = sessionId,
              let resolvedEntryId = ModelGroupRouter.resolve(group: group, sessionId: sid, store: store),
              let resolvedEntry = store.entry(for: resolvedEntryId) else {
            bindToGroup(group)
            return
        }
        if let label = nonTextOutputLabel(for: resolvedEntry.model) {
            pendingNonTextOutput = PendingSelection(entry: resolvedEntry, group: group, modalityLabel: label, isGroupBind: true)
            return
        }
        bindToGroup(group)
    }

    // MARK: - Session Binding

    private func resolveSessionId() async -> String? {
        if let sid = sessionId { return sid }
        return await ensureSessionId?()
    }

    private func bindToGroup(_ group: ModelGroup) {
        Task {
            guard let sid = await resolveSessionId() else { return }
            let resolvedEntryId = ModelGroupRouter.resolve(group: group, sessionId: sid, store: store) ?? ""
            let source = SessionModelSource.group(groupId: group.id, resolvedEntryId: resolvedEntryId)
            let existing = store.binding(for: sid)
            let binding = SessionModelBinding(
                sessionId: sid,
                primarySource: source,
                subModelSource: existing?.subModelSource
            )
            store.setBinding(binding, for: sid)
            if let thinkLevel = group.defaultThinkingLevel {
                var cfg = store.inferenceConfig(for: sid) ?? SessionInferenceConfig()
                cfg.thinkingLevel = thinkLevel
                store.setInferenceConfig(cfg, for: sid)
            }
            NotificationCenter.default.post(
                name: .sessionModelBindingChanged,
                object: nil,
                userInfo: ["groupId": group.id, "sessionId": sid]
            )
            if let entry = store.entry(for: resolvedEntryId) {
                await ChatStore.shared.updateSessionModelId(sid, modelId: entry.model.id)
            }
            dismiss()
        }
    }

    private func bindToEntry(_ entry: ModelEntry, inGroup group: ModelGroup? = nil) {
        Task {
            guard let sid = await resolveSessionId() else { return }
            let source: SessionModelSource
            if let group {
                source = .group(groupId: group.id, resolvedEntryId: entry.id)
            } else {
                source = .directEntry(modelEntryId: entry.id)
            }
            let existing = store.binding(for: sid)
            let binding = SessionModelBinding(
                sessionId: sid,
                primarySource: source,
                subModelSource: existing?.subModelSource
            )
            store.setBinding(binding, for: sid)
            if let group, let thinkLevel = group.defaultThinkingLevel {
                var cfg = store.inferenceConfig(for: sid) ?? SessionInferenceConfig()
                cfg.thinkingLevel = thinkLevel
                store.setInferenceConfig(cfg, for: sid)
            }
            var info: [String: Any] = ["sessionId": sid]
            if let group { info["groupId"] = group.id }
            NotificationCenter.default.post(
                name: .sessionModelBindingChanged,
                object: nil,
                userInfo: info
            )
            await ChatStore.shared.updateSessionModelId(sid, modelId: entry.model.id)
            dismiss()
        }
    }
}

// MARK: - Compact Display Helper

@MainActor
struct SessionModelDisplay {
    let store: ProviderConfigStore
    var draftGroupId: String? = nil

    func displayName(for sessionId: String?) -> String {
        if sessionId == nil, let gid = draftGroupId,
           let group = store.group(for: gid) {
            return group.name
        }
        guard let sid = sessionId,
              let binding = store.binding(for: sid) else {
            return defaultDisplayName()
        }

        switch binding.primarySource {
        case .directEntry(let entryId, _):
            if let entry = store.entry(for: entryId) {
                return entry.model.displayName
            }
            return entryId
        case .group(let groupId, _):
            return store.group(for: groupId)?.name ?? "Group"
        }
    }

    func resolvedDetail(for sessionId: String?) -> (providerLabel: String, modelName: String)? {
        if sessionId == nil, let gid = draftGroupId {
            return resolvedDetail(forGroupId: gid)
        }
        guard let sid = sessionId,
              let binding = store.binding(for: sid) else {
            return defaultResolvedDetail()
        }

        let entryId: String
        switch binding.primarySource {
        case .directEntry(let eid, _): entryId = eid
        case .group(_, let eid): entryId = eid
        }

        guard let entry = store.entry(for: entryId),
              let instance = store.instance(for: entry.providerInstanceId) else { return nil }
        return (instance.label, entry.model.displayName)
    }

    private func defaultResolvedDetail() -> (providerLabel: String, modelName: String)? {
        guard let groupId = store.defaultPrimaryGroupId else { return nil }
        return resolvedDetail(forGroupId: groupId)
    }

    /// [T-codex-fast-mode] Resolve the ProviderInstance AND model id behind
    /// the session's active model — same resolution order as resolvedDetail
    /// (draft group → binding → default group). Callers inspect
    /// credentialType/providerType/model (e.g. the "..." menu's Fast Mode
    /// gate: Responses-API providers with a gpt-family model).
    func resolvedInstanceAndModel(for sessionId: String?) -> (instance: ProviderInstance, modelId: String)? {
        if sessionId == nil, let gid = draftGroupId {
            return resolvedInstanceAndModel(forGroupId: gid)
        }
        guard let sid = sessionId,
              let binding = store.binding(for: sid) else {
            guard let groupId = store.defaultPrimaryGroupId else { return nil }
            return resolvedInstanceAndModel(forGroupId: groupId)
        }
        let entryId: String
        switch binding.primarySource {
        case .directEntry(let eid, _): entryId = eid
        case .group(_, let eid): entryId = eid
        }
        guard let entry = store.entry(for: entryId),
              let instance = store.instance(for: entry.providerInstanceId) else { return nil }
        return (instance, entry.model.id)
    }

    private func resolvedInstanceAndModel(forGroupId groupId: String) -> (instance: ProviderInstance, modelId: String)? {
        guard let group = store.group(for: groupId) else { return nil }
        let resolved = group.memberEntryIds.lazy.compactMap { eid -> (ProviderInstance, String)? in
            guard let entry = store.entry(for: eid), !entry.isHidden,
                  let instance = store.instance(for: entry.providerInstanceId),
                  instance.isEnabled, instance.hasAnyCredential else { return nil }
            return (instance, entry.model.id)
        }.first
        if let resolved { return resolved }
        guard let firstEntryId = group.memberEntryIds.first,
              let entry = store.entry(for: firstEntryId),
              let instance = store.instance(for: entry.providerInstanceId) else { return nil }
        return (instance, entry.model.id)
    }

    private func resolvedDetail(forGroupId groupId: String) -> (providerLabel: String, modelName: String)? {
        guard let group = store.group(for: groupId) else { return nil }
        let resolved = group.memberEntryIds.lazy.compactMap { eid -> (ProviderInstance, ModelEntry)? in
            guard let entry = store.entry(for: eid), !entry.isHidden,
                  let instance = store.instance(for: entry.providerInstanceId),
                  instance.isEnabled, instance.hasAnyCredential else { return nil }
            return (instance, entry)
        }.first
        if let (instance, entry) = resolved {
            return (instance.label, entry.model.displayName)
        }
        guard let firstEntryId = group.memberEntryIds.first,
              let entry = store.entry(for: firstEntryId),
              let instance = store.instance(for: entry.providerInstanceId) else { return nil }
        return (instance.label, entry.model.displayName)
    }

    func isGroupBound(for sessionId: String?) -> Bool {
        if sessionId == nil, let gid = draftGroupId,
           store.group(for: gid) != nil {
            return true
        }
        guard let sid = sessionId,
              let binding = store.binding(for: sid) else { return false }
        if case .group = binding.primarySource { return true }
        return false
    }

    private func defaultDisplayName() -> String {
        if let groupId = store.defaultPrimaryGroupId,
           let group = store.group(for: groupId) {
            return group.name
        }
        return AppLocalized("No model selected")
    }
}
