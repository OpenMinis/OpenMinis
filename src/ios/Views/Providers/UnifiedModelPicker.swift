import SwiftUI
import AVFoundation

private let pickerLog = AppLogger(category: "UnifiedPicker")

// MARK: - Virtual System Voice Entries

extension ModelEntry {
    /// System ASR — Online (server-based / cloud). Higher accuracy + more languages,
    /// but needs network, caps at 1 min / request and 1000 requests/hour per device,
    /// and uploads audio to Apple. `system-asr` (no suffix) kept as the legacy /
    /// default id so old selections still resolve.
    static let systemASROnline = ModelEntry(
        uuid: "system-asr-online",
        providerInstanceId: SystemVoiceProvider.builtinProviderId,
        model: LLMModel(
            id: "system-asr-online",
            displayName: AppLocalized("System Recognition (Online)", comment: "Built-in cloud ASR option"),
            provider: "system",
            modalityOverride: [.audioInput]
        ),
        isHidden: true
    )
    /// System ASR — Offline (on-device). Fully offline, no time/rate limits, audio
    /// stays on device, and the recognition-language switch actually matters
    /// (on-device is monolingual per locale). Falls back to Online when the chosen
    /// language has no on-device model.
    static let systemASROffline = ModelEntry(
        uuid: "system-asr-offline",
        providerInstanceId: SystemVoiceProvider.builtinProviderId,
        model: LLMModel(
            id: "system-asr-offline",
            displayName: AppLocalized("System Recognition (Offline)", comment: "Built-in on-device ASR option"),
            provider: "system",
            modalityOverride: [.audioInput]
        ),
        isHidden: true
    )
    /// Legacy/default ASR entry (maps to Offline-preferred behavior). Retained so
    /// existing "…/system-asr" references and the bare sentinel keep resolving.
    static let systemASR = ModelEntry(
        uuid: "system-asr",
        providerInstanceId: SystemVoiceProvider.builtinProviderId,
        model: LLMModel(
            id: "system-asr",
            displayName: AppLocalized("System Speech Recognition", comment: "Built-in ASR option"),
            provider: "system",
            modalityOverride: [.audioInput]
        ),
        isHidden: true
    )
    static let systemTTS = ModelEntry(
        uuid: "system-tts",
        providerInstanceId: SystemVoiceProvider.builtinProviderId,
        model: LLMModel(
            id: "system-tts",
            displayName: AppLocalized("System Voice (Auto)", comment: "Built-in TTS auto-by-language option"),
            provider: "system",
            modalityOverride: [.audioOutput]
        ),
        isHidden: true
    )

    /// One selectable ModelEntry per filtered Apple TTS voice (Stage 1). The
    /// entry id is the composite "<sentinel>/<voice.identifier>" so selection is
    /// preserved through VoiceSelectionStore and resolved back to a concrete
    /// AVSpeechSynthesisVoice at synthesis time. The always-present "System Voice
    /// (Auto)" default (`.systemTTS`) leads the list for by-language auto-select.
    @MainActor
    static func systemTTSVoiceEntries() -> [ModelEntry] {
        // providerInstanceId = the bare sentinel keeps isSystemEntry(...) true;
        // model.id = the voice identifier makes ModelEntry.id (= "{provider}/{model.id}")
        // the composite "<sentinel>/<voice.identifier>" the resolver reads back.
        SystemVoiceCatalog.ttsModels().map { model in
            ModelEntry(
                providerInstanceId: SystemVoiceProvider.builtinProviderId,
                model: model,
                isHidden: true
            )
        }
    }
}

// MARK: - ModelPickerConfig

struct ModelPickerConfig {
    var title: LocalizedStringKey = "Select Model"
    var mode: Mode = .single

    var explicitPreferModality: [ModelModality]?

    var groupScope: GroupScope = .all

    var candidateFilter: ((ModelEntry) -> Bool)?
    var isDisabled: ((ModelEntry) -> Bool)?
    var existingIds: (@MainActor () -> Set<String>)?
    var headerNote: String?
    var showGroups: Bool = true
    var showCreateGroup: Bool = false
    var createGroupDirection: VoiceDirection?
    var dismissOnSelect: Bool = true

    var currentEntryId: (@MainActor () -> String?)?
    var currentGroupId: (@MainActor () -> String?)?

    var onSelect: (@MainActor (ModelEntry) -> Void)?
    var onSelectGroup: (@MainActor (ModelGroup) -> Void)?
    /// Tap on a member row inside an expanded group. Receives the parent group so
    /// the caller can keep the group binding (pin the entry within the group's
    /// strategy) instead of downgrading to a direct-entry binding. Falls back to
    /// `onSelect` when nil.
    var onSelectInGroup: (@MainActor (ModelEntry, ModelGroup) -> Void)?
    var onAddMulti: (@MainActor (Set<String>) -> Void)?

    enum Mode { case single, multi }
    enum GroupScope {
        case all
        case single(String?)
        case none
    }

    @MainActor
    var effectivePreferModality: [ModelModality]? {
        if let explicit = explicitPreferModality { return explicit }
        guard case .single(let groupId) = groupScope, let gid = groupId else { return nil }
        let store = ProviderConfigStore.shared
        if gid == store.voiceInputGroupId  { return [.audioInput] }
        if gid == store.voiceOutputGroupId { return [.audioOutput] }
        // [T-ios-vision-group #182] Adding models to the Vision Group surfaces
        // image-capable models first — a text-only member there would be dead
        // weight the resolver silently filters out.
        if gid == store.visionGroupId      { return [.imageInput] }
        return nil
    }

    // MARK: - Factory Methods

    /// Whether `model` can serve the voice direction: dedicated voice model OR
    /// multimodal with the required audio modality (chat-based transcription).
    private static func canServe(_ model: LLMModel, direction: VoiceDirection) -> Bool {
        if direction.isVoiceModel(model) { return true }
        let m = model.capabilities.supportedModalities
        return direction == .input ? m.contains(.audioInput) : m.contains(.audioOutput)
    }

    @MainActor
    static func voiceInput() -> ModelPickerConfig {
        let store = ProviderConfigStore.shared
        let selection = VoiceSelectionStore.shared
        return ModelPickerConfig(
            title: "Voice Input",
            mode: .single,
            explicitPreferModality: [.audioInput],
            groupScope: .single(store.voiceInputGroupId),
            isDisabled: { !canServe($0.model, direction: .input) },
            showCreateGroup: true,
            createGroupDirection: .input,
            // Effective selection mirrors VoiceProviderResolver: explicit override
            // first; with no override the configured group is the selection; with
            // neither, the offline System engine is the effective default.
            currentEntryId: {
                if let sel = selection.inputEntryId { return sel }
                return store.voiceInputGroupId == nil ? VoiceProviderResolver.systemEntryId : nil
            },
            currentGroupId: {
                selection.inputEntryId == nil ? store.voiceInputGroupId : nil
            },
            onSelect: { entry in
                if VoiceProviderResolver.isSystemEntry(entry.providerInstanceId) {
                    // Preserve the Online/Offline composite id (entry.id =
                    // "<sentinel>/system-asr-online|offline") so the choice survives;
                    // the bare/legacy System entry collapses to the sentinel.
                    let eid = entry.id
                    selection.inputEntryId = (eid.hasSuffix("/system-asr-online")
                        || eid.hasSuffix("/system-asr-offline")) ? eid : VoiceProviderResolver.systemEntryId
                } else {
                    selection.inputEntryId = entry.id
                }
            },
            onSelectGroup: { group in
                let entries = group.memberEntryIds.compactMap { store.entry(for: $0) }
                if let e = entries.first(where: { canServe($0.model, direction: .input) }) ?? entries.first {
                    selection.inputEntryId = e.id
                }
            }
        )
    }

    @MainActor
    static func voiceOutput() -> ModelPickerConfig {
        let store = ProviderConfigStore.shared
        let selection = VoiceSelectionStore.shared
        return ModelPickerConfig(
            title: "Voice Output",
            mode: .single,
            explicitPreferModality: [.audioOutput],
            groupScope: .single(store.voiceOutputGroupId),
            isDisabled: { !canServe($0.model, direction: .output) },
            showCreateGroup: true,
            createGroupDirection: .output,
            currentEntryId: {
                if let sel = selection.outputEntryId { return sel }
                return store.voiceOutputGroupId == nil ? VoiceProviderResolver.systemEntryId : nil
            },
            currentGroupId: {
                selection.outputEntryId == nil ? store.voiceOutputGroupId : nil
            },
            onSelect: { entry in
                if VoiceProviderResolver.isSystemEntry(entry.providerInstanceId) {
                    // A specific System voice (composite id "<sentinel>/<voiceId>")
                    // is preserved so synthesis uses that exact AVSpeechSynthesisVoice;
                    // the bare "System Voice (auto)" default collapses to the sentinel.
                    selection.outputEntryId = VoiceProviderResolver.selectedSystemVoiceId(entry.id) != nil
                        ? entry.id
                        : VoiceProviderResolver.systemEntryId
                } else {
                    selection.outputEntryId = entry.id
                }
                VoiceOutputPlayer.shared.resetActiveModel()
            },
            onSelectGroup: { group in
                let entries = group.memberEntryIds.compactMap { store.entry(for: $0) }
                if let e = entries.first(where: { canServe($0.model, direction: .output) }) ?? entries.first {
                    selection.outputEntryId = e.id
                }
                VoiceOutputPlayer.shared.resetActiveModel()
            }
        )
    }

    @MainActor
    static func agentLoopAddModels() -> ModelPickerConfig {
        let store = ProviderConfigStore.shared
        return ModelPickerConfig(
            mode: .multi,
            groupScope: .none,
            existingIds: {
                let entries = Set(store.agentLoopModelEntryIds)
                let groupEntryIds = Set(store.agentLoopGroupIds.compactMap { store.group(for: $0) }.flatMap(\.memberEntryIds))
                return entries.union(groupEntryIds)
            },
            onAddMulti: { ids in for id in ids.sorted() { store.addAgentLoopEntry(id) } }
        )
    }
}

// MARK: - UnifiedModelPicker

struct UnifiedModelPicker: View {
    let config: ModelPickerConfig
    @ObservedObject private var store = ProviderConfigStore.shared
    /// Observed so the System voice rows rebuild when the available-voices roster
    /// changes (Enhanced/Premium pack download, Personal Voice creation).
    @ObservedObject private var systemVoiceRoster = SystemVoiceRoster.shared
    @Environment(\.dismiss) private var dismiss

    @State private var searchText = ""
    @State private var selectedEntryIds: Set<String> = []
    @State private var expandedGroupIds: Set<String> = []
    @State private var collapsedInstanceIds: Set<String> = []
    @State private var collapseSeeded = false
    @State private var showCreateGroupSheet = false
    /// Presents the full Model Groups management page (Settings → Model Groups)
    /// on top of the picker via the "Edit" affordance in the group section header,
    /// so users can reconfigure groups without dismissing the picker and digging
    /// through Settings.
    @State private var showGroupsManager = false
    /// The model row whose Quick Test sheet is open (nil = none). Set by the
    /// per-row bolt button so any model — cloud, group member, or System voice —
    /// can be smoke-tested (speak / text output) without leaving the picker.
    @State private var quickTestEntry: ModelEntry?

    private var isMulti: Bool { config.mode == .multi }

    // MARK: - Candidate Filtering

    private func matchesPreference(_ modality: ModelModality, prefs: [ModelModality]) -> Bool {
        prefs.contains { modality.isSuperset(of: $0) }
    }

    private var candidateEntries: [ModelEntry] {
        let prefs = config.effectivePreferModality
        var pool = store.modelEntries.filter { entry in
            guard !entry.isHidden else { return false }
            guard store.instance(for: entry.providerInstanceId)?.isEnabled == true else { return false }
            guard let prefs, !prefs.isEmpty else { return true }
            return matchesPreference(entry.model.capabilities.supportedModalities, prefs: prefs)
        }
        if let prefs, !prefs.isEmpty {
            if prefs.contains(where: { $0 == .audioInput }) {
                // Two selectable System ASR models: Online (cloud) leads — higher
                // accuracy + more languages — then Offline (on-device) for
                // privacy/offline. The user picks the trade-off explicitly.
                pool.append(.systemASROnline)
                pool.append(.systemASROffline)
            }
            if prefs.contains(where: { $0 == .audioOutput }) {
                // "System Voice (Auto)" default first, then one row per installed
                // Apple voice (Stage 1). `systemVoiceRoster` is observed so the
                // list rebuilds when the user downloads/removes voice packs.
                pool.append(.systemTTS)
                pool.append(contentsOf: ModelEntry.systemTTSVoiceEntries())
            }
        }
        if let f = config.candidateFilter { pool = pool.filter(f) }
        if let existing = config.existingIds?() { pool = pool.filter { !existing.contains($0.id) } }
        return pool
    }

    private var systemEntries: [ModelEntry] {
        candidateEntries.filter { VoiceProviderResolver.isSystemEntry($0.providerInstanceId) }
    }

    private var regularEntries: [ModelEntry] {
        candidateEntries.filter { !VoiceProviderResolver.isSystemEntry($0.providerInstanceId) }
    }

    private var entriesByInstance: [(instance: ProviderInstance, entries: [ModelEntry])] {
        var result: [(ProviderInstance, [ModelEntry])] = []
        // Built-in System engine FIRST — as its own provider section (Phase C), the
        // same collapsible header treatment as any cloud provider, driven by the
        // synthetic local-only instance rather than a parallel systemSection.
        if !systemEntries.isEmpty {
            result.append((SystemVoiceProvider.providerInstance, systemEntries))
        }
        let grouped = Dictionary(grouping: regularEntries, by: { $0.providerInstanceId })
        var seen = Set<String>()
        // [T-ios-model-picker-provider-order] Walk providerType-major, exactly
        // like ProviderInstancesView's section list, then `store.instances`
        // order WITHIN each type.
        //
        // Both screens already read the same `store.instances` array in the
        // same order — the bug was never a different sort key. The management
        // page renders `ForEach(ProviderType.allCases) { type in
        // store.instances.filter { $0.providerType == type } }`, i.e. it is
        // grouped by protocol ("OpenAI", "Responses API (v3)", …), so the
        // global array's type-interleaving is invisible there. This picker
        // walked the same array FLAT, which exposed that interleaving and made
        // the two orders disagree (user report: management shows
        // 球球 → 球球图片 → 球球Grok …, picker shows DeepSeek → 球球 → Codex CPA
        // → 球球图片 …).
        //
        // Reordering cannot fix this from the data side: `moveInstances`
        // deliberately permutes only WITHIN a type section and pins every other
        // instance to its existing global slot, so an OpenAI provider can never
        // be dragged past a Responses-API one. Type-major iteration here is
        // what makes the picker reproduce what the user actually arranged.
        for type in ProviderType.allCases {
            for instance in store.instances
            where instance.isEnabled && instance.providerType == type {
                guard !seen.contains(instance.id) else { continue }
                seen.insert(instance.id)
                if let entries = grouped[instance.id], !entries.isEmpty {
                    // [T-model-release-ranking] Order models WITHIN the section
                    // newest-first. This is a different axis from the provider
                    // ordering above and must not be confused with it: provider
                    // sections follow the user's arrangement, models inside a
                    // section follow release date.
                    //
                    // `candidateEntries` comes from `store.modelEntries`, whose
                    // getter applies `sortedEntries` — instance cluster, then
                    // `baseModel.id` ALPHABETICALLY. It does NOT apply
                    // `releaseRankOrder`, so this picker was showing models
                    // alphabetically and silently losing the release ranking
                    // that `visibleEntries(for:)` gives the provider-detail
                    // list. That matters most in the COLLAPSED state, where the
                    // section renders `entries.prefix(1)`: the one model on
                    // screen was the alphabetically-first, which is exactly the
                    // "first entry is a stale/dead model" hazard
                    // T-model-release-ranking exists to prevent (OpenMinis#83 —
                    // the unusable model's 400 renders as an empty reply and
                    // reads as "the app is broken").
                    result.append((instance, entries.sorted(by: ProviderConfigStore.releaseRankOrder)))
                }
            }
        }
        return result
    }

    // MARK: - Search

    private func fuzzyMatch(_ text: String) -> Bool {
        guard !searchText.isEmpty else { return true }
        let query = searchText.lowercased()
        let target = text.lowercased()
        if target.contains(query) { return true }
        var idx = target.startIndex
        for ch in query {
            guard let found = target[idx...].firstIndex(of: ch) else { return false }
            idx = target.index(after: found)
        }
        return true
    }

    private var filteredEntriesByInstance: [(instance: ProviderInstance, entries: [ModelEntry])] {
        guard !searchText.isEmpty else { return entriesByInstance }
        return entriesByInstance.compactMap { item in
            let filtered = item.entries.filter { entry in
                fuzzyMatch(entry.model.displayName) || fuzzyMatch(entry.model.id)
            }
            return filtered.isEmpty ? nil : (item.instance, filtered)
        }
    }

    // MARK: - Groups

    private var visibleGroups: [ModelGroup] {
        switch config.groupScope {
        case .all:
            let groups = store.modelGroups
            guard !searchText.isEmpty else { return groups }
            return groups.filter { fuzzyMatch($0.name) }
        case .single(let groupId):
            guard let gid = groupId, let g = store.group(for: gid) else { return [] }
            guard !searchText.isEmpty else { return [g] }
            if fuzzyMatch(g.name) { return [g] }
            let memberMatch = g.memberEntryIds.contains { id in
                store.entry(for: id).map { fuzzyMatch($0.model.displayName) } ?? false
            }
            return memberMatch ? [g] : []
        case .none:
            return []
        }
    }

    // MARK: - Body

    var body: some View {
        List {
            if let note = config.headerNote {
                Section {
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: "info.circle")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(note)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            // System now renders through the generic instanceSection loop below
            // (its synthetic instance leads entriesByInstance) — no parallel section.

            if config.showGroups && !visibleGroups.isEmpty {
                Section {
                    ForEach(visibleGroups) { group in
                        groupRow(group)
                        if expandedGroupIds.contains(group.id) {
                            groupMemberRows(group)
                        }
                    }
                } header: {
                    HStack {
                        Text("Model Groups")
                        Spacer()
                        Button {
                            showGroupsManager = true
                        } label: {
                            Text("Edit")
                                .font(.caption)
                                .textCase(nil)
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(.tint)
                    }
                } footer: {
                    if searchText.isEmpty {
                        switch config.groupScope {
                        case .all:
                            Text("Bind this session to a group for automatic fallback or load balancing.")
                        case .single:
                            Text("Pick a group whose audio-capable models drive this direction.")
                        case .none:
                            EmptyView()
                        }
                    }
                }
            }

            ForEach(filteredEntriesByInstance, id: \.instance.id) { item in
                instanceSection(item)
            }

            if visibleGroups.isEmpty && filteredEntriesByInstance.isEmpty {
                emptySection
            }

            if config.showCreateGroup {
                Section {
                    Button {
                        showCreateGroupSheet = true
                    } label: {
                        Label("Create group from models…", systemImage: "plus.rectangle.on.folder")
                            .font(.subheadline)
                    }
                }
            }
        }
        .searchable(text: $searchText, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search models")
        .navigationTitle(config.title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            seedCollapse()
            SystemVoiceCatalog.startObservingVoiceChanges()
        }
        .toolbar { toolbarContent }
        .sheet(isPresented: $showCreateGroupSheet) {
            NavigationStack {
                UnifiedModelPicker(config: createGroupConfig())
            }
        }
        .sheet(isPresented: $showGroupsManager) {
            NavigationStack {
                ModelGroupsView()
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Done") { showGroupsManager = false }
                        }
                    }
            }
        }
        .sheet(item: $quickTestEntry) { entry in
            // [T-quicktest-stale-session] .id(entry.id) forces a FRESH view
            // identity per model: @StateObject's initial-value closure only
            // evaluates when the identity is first established, and an
            // interactive swipe-dismiss followed by opening another model's
            // test could reuse the previous identity — header showed the new
            // model while TestSession still ran the OLD one.
            ModelQuickTestSheet(entry: entry)
                .id(entry.id)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
    }

    /// Compact per-row Quick Test button — opens the SAME ModelQuickTestSheet used
    /// by the provider model list, for a consistent experience everywhere (the
    /// sheet auto-plays audio results, so a voice test speaks on its own). Reused
    /// across cloud rows, group members, and System voices.
    private func quickTestButton(_ entry: ModelEntry) -> some View {
        Button {
            quickTestEntry = entry
        } label: {
            Image(systemName: "bolt.badge.checkmark")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(.tint)
                .frame(width: 32, height: 32)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(Text("Quick Test \(entry.model.displayName)"))
    }

    /// Nested multi-select picker for "Create group from models…". The voice
    /// direction is carried via `explicitPreferModality` (no group exists yet,
    /// so scope inference can't apply) — the System virtual entry unlocks
    /// automatically for the matching direction.
    @MainActor
    private func createGroupConfig() -> ModelPickerConfig {
        let dir = config.createGroupDirection
        let parentConfig = config
        let dismissPicker = dismiss
        return ModelPickerConfig(
            title: "Create Group",
            mode: .multi,
            explicitPreferModality: dir.map { $0 == .input ? [.audioInput] : [.audioOutput] },
            groupScope: .none,
            headerNote: dir?.filterNote,
            onAddMulti: { ids in
                guard !ids.isEmpty else { return }
                let store = ProviderConfigStore.shared
                let name = Self.uniqueGroupName(for: dir, store: store)
                let group = ModelGroup(name: name, memberEntryIds: ids.sorted())
                store.addGroup(group)
                if let dir {
                    if dir == .input { store.voiceInputGroupId = group.id }
                    else { store.voiceOutputGroupId = group.id }
                }
                if let firstId = ids.sorted().first {
                    if let entry = store.entry(for: firstId) {
                        parentConfig.onSelect?(entry)
                    } else if VoiceProviderResolver.isSystemEntry(firstId) {
                        parentConfig.onSelect?(dir == .output ? .systemTTS : .systemASR)
                    }
                }
                dismissPicker()
            }
        )
    }

    private static func uniqueGroupName(for dir: VoiceDirection?, store: ProviderConfigStore) -> String {
        let base: String
        switch dir {
        case .input:  base = AppLocalized("Voice Input", comment: "Default voice input group name")
        case .output: base = AppLocalized("Voice Output", comment: "Default voice output group name")
        case nil:     base = AppLocalized("New Group", comment: "Default group name")
        }
        let existing = Set(store.modelGroups.map(\.name))
        if !existing.contains(base) { return base }
        var n = 2
        while existing.contains("\(base) \(n)") { n += 1 }
        return "\(base) \(n)"
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        if isMulti {
            ToolbarItem(placement: .topBarLeading) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Add (\(selectedEntryIds.count))") {
                    config.onAddMulti?(selectedEntryIds)
                    dismiss()
                }
                .font(.body.weight(.semibold))
                .disabled(selectedEntryIds.isEmpty)
            }
        } else {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Done") { dismiss() }
            }
        }
    }

    /// A stable "which System row" key from an entry/selection id: the specific
    /// voice id, the ASR online/offline variant, or "" for the bare sentinel / auto
    /// default. Lets exactly ONE System row show selected in the generic entryRow.
    private static func systemRowKey(_ id: String?) -> String {
        guard let id else { return "" }
        if let v = VoiceProviderResolver.selectedSystemVoiceId(id) { return v }
        if id.hasSuffix("/system-asr-online")  { return "asr-online" }
        if id.hasSuffix("/system-asr-offline") { return "asr-offline" }
        return ""   // bare sentinel / auto default
    }

    // MARK: - Group Row

    @ViewBuilder
    private func groupRow(_ group: ModelGroup) -> some View {
        let isSelected = isGroupSelected(group)

        HStack(spacing: 10) {
            Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                .font(.system(size: 20))
                .foregroundStyle(isSelected ? Color.accentColor : Color(UIColor.tertiaryLabel))
            Image(systemName: "square.stack.3d.up.fill")
                .font(.caption).foregroundStyle(.blue)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(group.name)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Color(UIColor.label))
                    strategyBadge(group.strategy)
                }
                groupSubtitle(group)
            }

            Spacer()

            if case .all = config.groupScope,
               store.defaultPrimaryGroupId == group.id {
                Text("Default")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.blue)
                    .padding(.horizontal, 5)
                    .padding(.vertical, 1)
                    .background(Color.blue.opacity(0.1))
                    .clipShape(Capsule())
            }

            Button {
                withAnimation(.easeInOut(duration: 0.2)) {
                    if expandedGroupIds.contains(group.id) {
                        _ = expandedGroupIds.remove(group.id)
                    } else {
                        expandedGroupIds.insert(group.id)
                    }
                }
            } label: {
                Image(systemName: expandedGroupIds.contains(group.id) ? "chevron.up" : "chevron.down")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.secondary)
                    .frame(width: 28, height: 28)
                    .background(Color(UIColor.tertiarySystemFill))
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            config.onSelectGroup?(group)
            dismissIfNeeded()
        }
        .contextMenu {
            Button {
                UIPasteboard.general.string = "group:\(group.id)"
                MinisToast.show(AppLocalized("Copied: \(group.name)"))
            } label: {
                Label(AppLocalized("Copy Shortcut Model ID"), systemImage: "link")
            }
        }
    }

    private func isGroupSelected(_ group: ModelGroup) -> Bool {
        config.currentGroupId?() == group.id
    }

    @ViewBuilder
    private func groupSubtitle(_ group: ModelGroup) -> some View {
        if group.memberEntryIds.isEmpty {
            Text(AppLocalized("No models"))
                .font(.caption)
                .foregroundStyle(.tertiary)
        } else if isGroupSelected(group),
                  let eid = config.currentEntryId?(),
                  let entry = store.entry(for: eid) {
            Text("→ \(entry.model.displayName)")
                .font(.caption)
                .foregroundStyle(.secondary)
        } else {
            let available = availableMemberEntryIds(group).count
            let total = group.memberEntryIds.count
            if available == total {
                Text(AppLocalized("\(total) models"))
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            } else if available == 0 {
                Text(AppLocalized("\(total) models · all unavailable"))
                    .font(.caption)
                    .foregroundStyle(.red.opacity(0.7))
            } else {
                Text(AppLocalized("\(available)/\(total) available"))
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
        }
    }

    private func strategyBadge(_ strategy: RoutingStrategy) -> some View {
        HStack(spacing: 2) {
            Image(systemName: strategy == .fallback ? "arrow.down.circle" : "arrow.triangle.branch")
                .font(.system(size: 8))
            Text(strategy == .fallback ? "FB" : "LB")
                .font(.system(size: 9, weight: .medium, design: .rounded))
        }
        .foregroundStyle(.secondary)
        .padding(.horizontal, 4)
        .padding(.vertical, 1)
        .background(Color(UIColor.quaternarySystemFill))
        .clipShape(Capsule())
    }

    private func availableMemberEntryIds(_ group: ModelGroup) -> [String] {
        group.memberEntryIds.filter { memberUnavailableReason($0) == nil }
    }

    private func memberUnavailableReason(_ entryId: String) -> String? {
        // Resolve the entry — real store entry, or a virtual built-in (System)
        // entry. A model that needs no credential (offline built-in) is always
        // available, so it short-circuits BEFORE the instance/credential checks.
        // This is property-driven: no "is this System?" branch here.
        guard let entry = store.entry(for: entryId) ?? Self.systemEntry(for: entryId) else {
            return AppLocalized("Model not found")
        }
        if !entry.displayTraits.requiresCredential { return nil }
        if entry.isHidden { return AppLocalized("Hidden") }
        guard let instance = store.instance(for: entry.providerInstanceId) else {
            return AppLocalized("Provider not found")
        }
        if !instance.isEnabled { return AppLocalized("Provider disabled") }
        if !instance.hasAnyCredential { return AppLocalized("Not signed in") }
        return nil
    }

    /// Maps an entry id that refers to the built-in System engine to the matching
    /// virtual entry, nil for regular ids. Order matters:
    ///   1. A SPECIFIC voice id ("…/com.apple.voice.…") → a real per-voice entry
    ///      carrying that voice's localized display name (so a group lists e.g.
    ///      "Samantha (English, Female voice)", NOT the generic "System Voice").
    ///   2. The TTS auto default ("…/output" / "…/system-tts") → .systemTTS.
    ///   3. The ASR Online/Offline variants ("…/system-asr-online|offline").
    ///   4. Everything else (bare sentinel, "…/system-asr", "…/input") → .systemASR.
    @MainActor
    static func systemEntry(for id: String) -> ModelEntry? {
        guard VoiceProviderResolver.isSystemEntry(id) else { return nil }
        if let voiceId = VoiceProviderResolver.selectedSystemVoiceId(id) {
            // The voice IS a TTS voice member. If it's currently installed, use its
            // rich localized name; if NOT installed on this device (e.g. a synced
            // group picked a voice this device doesn't have), still render it as a
            // TTS entry with a name derived from the identifier — never fall through
            // to the generic ASR row (which showed the wrong "System Speech
            // Recognition" + mic icon for uninstalled voices).
            let name: String
            if let voice = AVSpeechSynthesisVoice(identifier: voiceId) {
                name = SystemVoiceCatalog.displayName(for: voice)
            } else {
                // Not installed on this device (e.g. a synced group named a voice
                // this device lacks). Derive "Tingting (zh-CN) · not installed" from
                // the identifier "com.apple.voice.super-compact.zh-CN.Tingting".
                let parts = voiceId.split(separator: ".").map(String.init)
                let leaf = parts.last ?? voiceId
                let lang = parts.count >= 2 ? parts[parts.count - 2] : ""
                let notInstalled = AppLocalized("not installed", comment: "voice pack not downloaded")
                name = lang.isEmpty ? "\(leaf) · \(notInstalled)" : "\(leaf) (\(lang)) · \(notInstalled)"
            }
            return ModelEntry(
                providerInstanceId: SystemVoiceProvider.builtinProviderId,
                model: LLMModel(
                    id: voiceId,
                    displayName: name,
                    provider: SystemVoiceProvider.builtinProviderId,
                    modalityOverride: [.audioOutput]),
                isHidden: true)
        }
        if id.hasSuffix("/output") || id.hasSuffix("/system-tts") { return .systemTTS }
        if id.hasSuffix("/system-asr-online") { return .systemASROnline }
        if id.hasSuffix("/system-asr-offline") { return .systemASROffline }
        return .systemASR
    }

    /// Instance variant that resolves a bare sentinel by the picker's own
    /// modality preference (TTS context → System Voice).
    private func systemVirtualEntry(forMemberId id: String) -> ModelEntry {
        // A specific voice id → its real per-voice entry (localized voice name).
        if VoiceProviderResolver.selectedSystemVoiceId(id) != nil,
           let e = Self.systemEntry(for: id) { return e }
        if id.hasSuffix("/output") || id.hasSuffix("/system-tts") { return .systemTTS }
        if id.hasSuffix("/input") || id.hasSuffix("/system-asr") { return .systemASR }
        // Bare sentinel — pick by the picker's modality preference.
        if config.effectivePreferModality?.contains(where: { $0 == .audioOutput }) == true {
            return .systemTTS
        }
        return .systemASR
    }

    @ViewBuilder
    private func groupMemberRows(_ group: ModelGroup) -> some View {
        if group.memberEntryIds.isEmpty {
            Text("No models in this group")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.leading, 30)
                .padding(.vertical, 4)
        } else {
            ForEach(group.memberEntryIds, id: \.self) { entryId in
                // System member ids never resolve via store.entry — in a voice
                // context (audio preference active) render the virtual System
                // entry (selectable) instead of "Model not found". In non-voice
                // contexts (e.g. the session picker listing a voice group) fall
                // through to the unavailable row so System can't be bound as a
                // chat model.
                if VoiceProviderResolver.isSystemEntry(entryId), config.effectivePreferModality != nil {
                    expandedEntryRow(systemVirtualEntry(forMemberId: entryId), parentGroup: group)
                        .padding(.leading, 30)
                } else if let reason = memberUnavailableReason(entryId) {
                    unavailableMemberRow(entryId: entryId, reason: reason)
                        .padding(.leading, 30)
                } else if let entry = store.entry(for: entryId) {
                    expandedEntryRow(entry, parentGroup: group)
                        .padding(.leading, 30)
                }
            }
        }
    }

    @ViewBuilder
    private func expandedEntryRow(_ entry: ModelEntry, parentGroup: ModelGroup) -> some View {
        let isSystem = VoiceProviderResolver.isSystemEntry(entry.providerInstanceId)
        let isActive = (config.currentGroupId?() == parentGroup.id && config.currentEntryId?() == entry.id)
            || (isSystem && VoiceProviderResolver.isSystemEntry(config.currentEntryId?()))
        let disabled = config.isDisabled?(entry) ?? false

        HStack(spacing: 10) {
            Image(systemName: isActive ? "checkmark.circle.fill" : "circle")
                .font(.system(size: 17))
                .foregroundStyle(isActive ? Color.accentColor : Color(UIColor.quaternaryLabel))

            providerDot(entry.model.provider)
                .opacity(disabled ? 0.4 : 1)

            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 4) {
                    Text(entry.model.displayName)
                        .font(.subheadline)
                        .foregroundStyle(disabled ? Color(UIColor.tertiaryLabel) : Color(UIColor.label))
                    if disabled {
                        Text("unavailable", comment: "Modality-incompatible model tag")
                            .font(.system(size: 9, weight: .medium))
                            .foregroundStyle(.orange)
                    }
                }
                HStack(spacing: 4) {
                    if let instanceLabel = store.instance(for: entry.providerInstanceId)?.label {
                        Text(instanceLabel)
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(.secondary)
                        Text("·")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                    Text(entry.model.id)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
            }

            Spacer()

            if isActive {
                Text("Active")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.green)
                    .padding(.horizontal, 5)
                    .padding(.vertical, 1)
                    .background(Color.green.opacity(0.1))
                    .clipShape(Capsule())
            }

            quickTestButton(entry)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            guard !disabled else { return }
            if let inGroup = config.onSelectInGroup {
                inGroup(entry, parentGroup)
            } else {
                config.onSelect?(entry)
            }
            dismissIfNeeded()
        }
        .contextMenu {
            Button {
                UIPasteboard.general.string = "entry:\(entry.compositeKey)"
                MinisToast.show(AppLocalized("Copied: \(entry.model.displayName)"))
            } label: {
                Label(AppLocalized("Copy Shortcut Model ID"), systemImage: "link")
            }
        }
    }

    @ViewBuilder
    private func unavailableMemberRow(entryId: String, reason: String) -> some View {
        let entry = store.entry(for: entryId)
        HStack(spacing: 10) {
            Image(systemName: "circle")
                .font(.system(size: 17))
                .foregroundStyle(Color(UIColor.quaternaryLabel))

            if let entry {
                providerDot(entry.model.provider)
                    .opacity(0.4)

                VStack(alignment: .leading, spacing: 1) {
                    Text(entry.model.displayName)
                        .font(.subheadline)
                        .foregroundStyle(Color(UIColor.tertiaryLabel))
                    HStack(spacing: 4) {
                        if let instanceLabel = store.instance(for: entry.providerInstanceId)?.label {
                            Text(instanceLabel)
                                .font(.caption2.weight(.medium))
                                .foregroundStyle(.tertiary)
                        }
                        Text("·")
                            .font(.caption2)
                            .foregroundStyle(.quaternary)
                        Text(reason)
                            .font(.caption2)
                            .foregroundStyle(.red.opacity(0.7))
                    }
                }
            } else {
                VStack(alignment: .leading, spacing: 1) {
                    Text(entryId.components(separatedBy: "/").last ?? entryId)
                        .font(.subheadline)
                        .foregroundStyle(Color(UIColor.tertiaryLabel))
                    Text(reason)
                        .font(.caption2)
                        .foregroundStyle(.red.opacity(0.7))
                }
            }

            Spacer()
        }
    }

    // MARK: - Instance Section

    @ViewBuilder
    private func instanceSection(_ item: (instance: ProviderInstance, entries: [ModelEntry])) -> some View {
        let isCollapsed = searchText.isEmpty && collapsedInstanceIds.contains(item.instance.id)
        // When collapsed, surface the currently-selected entry (if it lives in
        // this section) rather than blindly the first — so the active model stays
        // visible with its checkmark even while the section is folded.
        let collapsedEntry: [ModelEntry] = {
            if !isMulti, config.currentGroupId?() == nil, let eid = config.currentEntryId?() {
                // System selection may be the bare sentinel — match by row key.
                if VoiceProviderResolver.isSystemEntry(item.instance.id),
                   let sel = item.entries.first(where: { Self.systemRowKey($0.id) == Self.systemRowKey(eid) }) {
                    return [sel]
                }
                if let selected = item.entries.first(where: { $0.id == eid }) {
                    return [selected]
                }
            }
            return Array(item.entries.prefix(1))
        }()
        let visibleEntries = isCollapsed ? collapsedEntry : item.entries
        Section {
            ForEach(visibleEntries) { entry in
                entryRow(entry)
            }
            if isCollapsed && item.entries.count > 1 {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        _ = collapsedInstanceIds.remove(item.instance.id)
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "chevron.down")
                            .font(.system(size: 11, weight: .medium))
                        Text(AppLocalized("Show \(item.entries.count) models"))
                            .font(.caption)
                    }
                    .foregroundStyle(.tint)
                }
            }
        } header: {
            HStack {
                Text(item.instance.label)
                Spacer()
                if searchText.isEmpty && item.entries.count > 1 {
                    let collapsed = collapsedInstanceIds.contains(item.instance.id)
                    Button {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            if collapsed {
                                _ = collapsedInstanceIds.remove(item.instance.id)
                            } else {
                                collapsedInstanceIds.insert(item.instance.id)
                            }
                        }
                    } label: {
                        Image(systemName: collapsed ? "chevron.down" : "chevron.up")
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(.secondary)
                            .frame(width: 28, height: 28)
                            .background(Color(UIColor.tertiarySystemFill))
                            .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .textCase(nil)
                }
            }
        }
    }

    // MARK: - Entry Row

    private func entryRow(_ entry: ModelEntry) -> some View {
        let disabled = config.isDisabled?(entry) ?? false
        let traits = entry.displayTraits
        let isSelected: Bool = {
            if isMulti { return selectedEntryIds.contains(entry.id) }
            guard config.currentGroupId?() == nil, let eid = config.currentEntryId?() else { return false }
            // Built-in System entries select by their row key (voice / asr-online|
            // offline / auto), since the stored selection may be the bare sentinel;
            // regular entries match by exact id.
            if VoiceProviderResolver.isSystemEntry(entry.providerInstanceId) {
                return VoiceProviderResolver.isSystemEntry(eid)
                    && Self.systemRowKey(eid) == Self.systemRowKey(entry.id)
            }
            return eid == entry.id
        }()

        // Tap-gesture pattern (not a Button wrapper) so the trailing Quick Test
        // Button nests correctly — a Button inside a Button's label doesn't route
        // taps in SwiftUI.
        return HStack(spacing: 10) {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 20))
                    .foregroundStyle(isSelected ? Color.accentColor : Color(UIColor.tertiaryLabel))

                Image(systemName: traits.iconSymbol)
                    .font(.system(size: 11))
                    .foregroundStyle(traits.tint)
                    .frame(width: 14)

                VStack(alignment: .leading, spacing: 1) {
                    Text(entry.model.displayName)
                        .font(.subheadline)
                        .foregroundStyle(disabled ? Color(UIColor.tertiaryLabel) : Color(UIColor.label))
                    HStack(spacing: 4) {
                        // Subtitle from traits (e.g. "iOS built-in, works offline")
                        // for built-ins; the raw model id for cloud models.
                        Text(traits.subtitle ?? entry.model.id)
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                        let badges = traits.subtitle == nil ? modalityBadges(entry.model) : []
                        if !badges.isEmpty {
                            Text("·")
                                .font(.caption2)
                                .foregroundStyle(.quaternary)
                            ForEach(badges, id: \.self) { badge in
                                Text(badge)
                                    .font(.system(size: 9, weight: .medium))
                                    .foregroundStyle(.secondary)
                                    .padding(.horizontal, 4)
                                    .padding(.vertical, 1)
                                    .background(Color(UIColor.tertiarySystemFill))
                                    .clipShape(RoundedRectangle(cornerRadius: 3))
                            }
                        }
                        if disabled {
                            Text("unavailable", comment: "Modality-incompatible model tag")
                                .font(.system(size: 9, weight: .medium))
                                .foregroundStyle(.orange)
                        }
                    }
                }

                Spacer()

                if !isMulti, config.currentGroupId?() != nil,
                   config.currentEntryId?() == entry.id {
                    Text("Active")
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(.green)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(Color.green.opacity(0.1))
                        .clipShape(Capsule())
                }

                quickTestButton(entry)
        }
        .opacity(disabled ? 0.6 : 1)
        .contentShape(Rectangle())
        .onTapGesture {
            guard !disabled else { return }
            if isMulti {
                toggleSelection(entry.id)
            } else {
                config.onSelect?(entry)
                dismissIfNeeded()
            }
        }
        .contextMenu {
            Button {
                UIPasteboard.general.string = "entry:\(entry.compositeKey)"
                MinisToast.show(AppLocalized("Copied: \(entry.model.displayName)"))
            } label: {
                Label(AppLocalized("Copy Shortcut Model ID"), systemImage: "link")
            }
        }
    }

    // MARK: - Empty State

    private var emptySection: some View {
        Section {
            VStack(spacing: 8) {
                Image(systemName: searchText.isEmpty ? "cpu" : "magnifyingglass")
                    .font(.system(size: 28))
                    .foregroundStyle(.quaternary)
                Text(searchText.isEmpty ? "No models available" : "No results")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Text(searchText.isEmpty
                     ? "Configure providers in Settings to see models here."
                     : "Try a different search term.")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
        }
    }

    // MARK: - Helpers

    private func dismissIfNeeded() {
        if config.dismissOnSelect { dismiss() }
    }

    @ViewBuilder
    private func providerDot(_ provider: String) -> some View {
        Circle()
            .fill(providerColor(provider))
            .frame(width: 6, height: 6)
    }

    private func providerColor(_ provider: String) -> Color {
        ModelEntry.providerTint(provider)
    }

    private func modalityBadges(_ model: LLMModel) -> [String] {
        let m = model.capabilities.supportedModalities
        var badges: [String] = []
        if m.contains(.imageInput)  { badges.append("img") }
        if m.contains(.audioInput)  { badges.append("audio") }
        if m.contains(.videoInput)  { badges.append("video") }
        if m.contains(.pdfInput)    { badges.append("pdf") }
        if m.contains(.imageOutput) { badges.append("img-out") }
        if m.contains(.audioOutput) { badges.append("audio-out") }
        if m.contains(.videoOutput) { badges.append("video-out") }
        return badges
    }

    private func toggleSelection(_ entryId: String) {
        if selectedEntryIds.contains(entryId) {
            selectedEntryIds.remove(entryId)
        } else {
            selectedEntryIds.insert(entryId)
        }
    }

    private func seedCollapse() {
        guard !collapseSeeded else { return }
        collapseSeeded = true
        // Voice pickers (Voice Input / Output group binding, or an explicit audio
        // modality preference) exist specifically to browse and add TTS/ASR voices.
        // Dedicated voice providers carry many rows (Azure TTS ~39 voices, Doubao
        // 8, MiMo 9), so the default ">1 model → collapse to one row + Show N"
        // behavior hid every voice but the first behind a disclosure — users
        // reported the voices as "missing" from the Add Models list. In a voice
        // scenario, keep all sections expanded so every voice is immediately
        // visible. Non-voice pickers are unaffected.
        let prefs = config.effectivePreferModality
        let isVoiceScenario = prefs?.contains { $0 == .audioInput || $0 == .audioOutput } == true
        guard !isVoiceScenario else {
            collapsedInstanceIds = []
            return
        }
        var ids = Set<String>()
        // System is now an ordinary entry in entriesByInstance (its synthetic
        // instance), so this single loop collapses it too when it has >1 model
        // (the ~60 voice roster shouldn't fill the list).
        for item in entriesByInstance where item.entries.count > 1 {
            ids.insert(item.instance.id)
        }
        collapsedInstanceIds = ids
    }
}
