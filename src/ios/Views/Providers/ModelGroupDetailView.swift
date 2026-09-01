import SwiftUI

/// Detail view for editing a ModelGroup: name, strategy, ordered member list.
struct ModelGroupDetailView: View {
    let groupId: String
    @ObservedObject private var store = ProviderConfigStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var editingName = ""
    @State private var showAddModels = false
    @State private var showDeleteConfirm = false
    /// Holding `EditMode` directly (rather than a `Bool` + a derived
    /// `editMode` binding) lets SwiftUI own the same value the
    /// ForEach internals read from the environment. The earlier
    /// `Bool` + custom-binding combo passed through a getter/setter
    /// pair whose `set` closure was only invoked when EditButton
    /// (system-managed) toggled it — externally flipping the Bool
    /// updated the getter, but the editing affordances inside the
    /// ForEach rows (drag handle + minus button) kept their stale
    /// `.active` state on iOS 26. Owning the `EditMode` directly
    /// makes both sides agree.
    @State private var editMode: EditMode = .inactive

    private var group: ModelGroup? {
        store.group(for: groupId)
    }

    private func groupMaxThinkingLevel(_ group: ModelGroup) -> ThinkingLevel {
        // [T-ios-group-thinking-picker-empty] The group's thinking-strength
        // CEILING is the HIGHEST level any reasoning-capable member supports —
        // `.max()`, not `.min()`. Using `.min()` was wrong twice over:
        //   1. A non-reasoning member (GPT Image 2, effectiveMaxThinkingLevel ==
        //      .off) dragged the ceiling to .off and emptied the Intensity picker
        //      (`filter { $0 != .off && $0 <= maxLevel }` → zero rows), so the
        //      "Enable Reasoning" toggle showed on with no strength selector.
        //   2. Even among reasoning members it capped the group at the WEAKEST
        //      model's ceiling — a group with [Opus .max, some .high model] would
        //      only offer up to .high, hiding .max/.xhigh that Opus supports.
        // Take the max over reasoning-capable members (exclude .off). The group's
        // default level is later clamped per-model at request time
        // (AgentProvider: min(thinkingLevel, model.catalogMaxThinkingLevel)), so
        // offering the group's true ceiling here is safe. Fall back to .xhigh when
        // no member reasons (the picker is only reached with reasoning enabled).
        let levels = group.memberEntryIds
            .compactMap { store.entry(for: $0)?.effectiveMaxThinkingLevel }
            .filter { $0 != .off }
        return levels.max() ?? .xhigh
    }

    /// [T-thinking-levels-data-driven] The levels the group's Intensity picker
    /// offers: the UNION of every reasoning member's selectable tiers, matching
    /// the `.max()` ceiling policy above (offer what ANY member supports; the
    /// per-model clamp at request time narrows it for the others).
    ///
    /// Each member contributes what IT can offer: its declared tiers, or — when
    /// it declares none — the full ladder up to its own ceiling. Contributing
    /// the ladder is load-bearing for MIXED groups: taking only declared tiers
    /// would let one sparse member (glm-5.2, `["high","max"]`) erase Low/Med for
    /// an un-catalogued member that genuinely supports them, since the
    /// un-catalogued model contributes nothing to the union.
    private func groupThinkingLevels(_ group: ModelGroup) -> [ThinkingLevel] {
        let ceiling = groupMaxThinkingLevel(group)
        let union = Set(group.memberEntryIds.compactMap { store.entry(for: $0) }
            .filter { $0.effectiveMaxThinkingLevel != .off }
            .flatMap { $0.selectableThinkingLevels })
        guard !union.isEmpty else {
            return ThinkingLevel.allCases.filter { $0 != .off && $0 <= ceiling }
        }
        return union.filter { $0 <= ceiling }.sorted()
    }

    var body: some View {
        Group {
            if let group = group {
                groupContent(group)
            } else {
                Text("Group not found")
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle(group?.name ?? "Group")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .secondaryAction) {
                if let group {
                    Button {
                        UIPasteboard.general.string = "group:\(group.id)"
                        MinisToast.show(AppLocalized("Copied: \(group.name)"))
                    } label: {
                        Label(AppLocalized("Copy Shortcut Model ID"), systemImage: "link")
                    }
                }
            }
        }
        .alert("Delete Group", isPresented: $showDeleteConfirm) {
            Button("Delete", role: .destructive) {
                store.removeGroup(groupId)
                dismiss()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This will delete the group. Model entries and provider instances are not affected.")
        }
    }

    @ViewBuilder
    private func groupContent(_ group: ModelGroup) -> some View {
        List {
            // MARK: Name
            Section("Name") {
                HStack {
                    TextField("Group name", text: $editingName)
                        .onAppear { editingName = group.name }
                    if editingName != group.name && !editingName.trimmingCharacters(in: .whitespaces).isEmpty {
                        Button("Save") {
                            var updated = group
                            updated.name = editingName.trimmingCharacters(in: .whitespaces)
                            store.updateGroup(updated)
                        }
                        .font(.caption.weight(.semibold))
                    }
                }
            }

            // MARK: Strategy
            Section {
                Picker("Strategy", selection: Binding(
                    get: { group.strategy },
                    set: { newStrategy in
                        var updated = group
                        updated.strategy = newStrategy
                        store.updateGroup(updated)
                    }
                )) {
                    HStack {
                        settingsIcon("arrow.down.circle", color: .blue)
                        Text("Fallback")
                    }
                    .tag(RoutingStrategy.fallback)
                    HStack {
                        settingsIcon("arrow.triangle.branch", color: .orange)
                        Text("Load Balance")
                    }
                    .tag(RoutingStrategy.loadBalance)
                }
                .pickerStyle(.inline)
                .labelsHidden()
            } header: {
                Text("Routing Strategy")
            } footer: {
                if group.strategy == .fallback {
                    Text("Models are tried in order. If one fails, the next is used.")
                } else {
                    Text("Sessions are distributed across models. Each session sticks to its assigned model unless it fails.")
                }
            }

            // MARK: Fallback Strategy
            if group.strategy == .fallback {
                Section {
                    Picker("Fallback Strategy", selection: Binding(
                        get: { group.fallbackStrategy },
                        set: { newStrategy in
                            var updated = group
                            updated.fallbackStrategy = newStrategy
                            store.updateGroup(updated)
                        }
                    )) {
                        HStack {
                            settingsIcon("hand.raised.circle", color: .teal)
                            Text("Default")
                        }
                        .tag(FallbackStrategy.limited)
                        HStack {
                            settingsIcon("arrow.2.squarepath", color: .mint)
                            Text("Always")
                        }
                        .tag(FallbackStrategy.always)
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } header: {
                    Text("Fallback Strategy")
                } footer: {
                    if group.fallbackStrategy == .limited {
                        Text("Only switch to the next model on provider-level errors such as rate limiting, invalid API key, or provider rejection. Network and server errors are retried on the current model first.")
                    } else {
                        Text("Switch to the next model on any error — including network failures and server unavailability — without retrying. All models in the group are tried in order until one succeeds.")
                    }
                }
            }

            // MARK: Members
            Section {
                if group.memberEntryIds.isEmpty {
                    Text("No models in this group")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(group.memberEntryIds, id: \.self) { entryId in
                        // System engine member ids ("__builtin_system_speech__/…")
                        // are virtual — they never resolve via store.entry, so
                        // check them first or they'd render as stale.
                        if let sysEntry = UnifiedModelPicker.systemEntry(for: entryId) {
                            systemMemberRow(entry: sysEntry)
                        } else if let entry = store.entry(for: entryId) {
                            memberRow(entry: entry, group: group)
                        } else {
                            staleMemberRow(entryId: entryId, group: group)
                        }
                    }
                    .onMove { from, to in
                        moveMember(from: from, to: to, in: group)
                    }
                    .onDelete { offsets in
                        deleteMember(at: offsets, from: group)
                    }
                }

                Button {
                    showAddModels = true
                } label: {
                    Label("Add Models", systemImage: "plus.circle")
                        .font(.subheadline)
                }
            } header: {
                HStack {
                    Text("Models")
                    Spacer()
                    Button(editMode.isEditing ? AppLocalized("Done") : AppLocalized("Edit")) {
                        withAnimation {
                            editMode = editMode.isEditing ? .inactive : .active
                        }
                    }
                    .font(.caption)
                }
            } footer: {
                if group.strategy == .fallback {
                    Text("Drag to reorder. The first model is tried first.")
                }
            }

            // MARK: Session Defaults
            Section {
                // Thinking
                Toggle(isOn: Binding(
                    get: { group.defaultThinkingLevel != nil },
                    set: { enabled in
                        var updated = group
                        updated.defaultThinkingLevel = enabled ? .medium : nil
                        store.updateGroup(updated)
                    }
                )) {
                    HStack {
                        Image("ThinkingIcon")
                            .resizable()
                            .renderingMode(.template)
                            .foregroundStyle(.white)
                            .frame(width: 11, height: 11)
                            .frame(width: 21, height: 21)
                            .background(.purple, in: Circle())
                        Text("Enable Reasoning")
                    }
                }

                if group.defaultThinkingLevel != nil {
                    let maxLevel = groupMaxThinkingLevel(group)
                    let levels = groupThinkingLevels(group)
                    Picker("Intensity", selection: Binding(
                        get: {
                            // [T-thinking-levels-data-driven] A stored level may
                            // no longer be offered (the catalog now declares a
                            // sparse set, or membership changed). A segmented
                            // picker whose selection matches no tag renders with
                            // NOTHING highlighted, so snap the displayed value
                            // onto the nearest offered tier at or below it.
                            let current = group.defaultThinkingLevel ?? .medium
                            if levels.contains(current) { return current }
                            return levels.last(where: { $0 <= current })
                                ?? levels.first
                                ?? current
                        },
                        set: { level in
                            var updated = group
                            updated.defaultThinkingLevel = min(level, maxLevel)
                            store.updateGroup(updated)
                        }
                    )) {
                        ForEach(levels, id: \.self) { level in
                            Text(level.displayName).tag(level)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                // Context Limit
                Toggle(isOn: Binding(
                    get: { group.contextLimitTokens != nil },
                    set: { enabled in
                        var updated = group
                        if enabled {
                            // Restore the user's last selection verbatim, defaulting
                            // to 128K for first-time activation. The slider is fixed
                            // 7-stop and accepts any value; we no longer cap by group
                            // member context window because that field is noisy /
                            // unreliable for image-output and "router"-style models.
                            updated.contextLimitTokens = updated.lastContextLimitTokens ?? 128_000
                        } else {
                            // Remember the current value so the next ON restores it.
                            if let cur = updated.contextLimitTokens {
                                updated.lastContextLimitTokens = cur
                            }
                            updated.contextLimitTokens = nil
                        }
                        store.updateGroup(updated)
                    }
                )) {
                    HStack {
                        settingsIcon("memorychip", color: .indigo)
                        Text("Limit Context Window")
                    }
                }

                if group.contextLimitTokens != nil {
                    ContextLimitSlider(
                        value: Binding(
                            get: { group.contextLimitTokens },
                            set: { newVal in
                                var updated = group
                                updated.contextLimitTokens = newVal
                                if let v = newVal {
                                    updated.lastContextLimitTokens = v
                                }
                                store.updateGroup(updated)
                            }
                        )
                    )
                }
            } header: {
                Text("Session Defaults")
            } footer: {
                Text("Applied automatically when a new session is bound to this group.")
            }

            // MARK: Danger Zone
            Section {
                Button("Delete Group", role: .destructive) {
                    showDeleteConfirm = true
                }
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .environment(\.editMode, $editMode)
        .sheet(isPresented: $showAddModels) {
            NavigationStack {
                UnifiedModelPicker(config: addModelsConfig())
            }
        }
    }

    /// Multi-select picker for adding members to this group. `groupScope:
    /// .single(groupId)` lets `effectivePreferModality` kick in automatically
    /// when this group is bound as the global Voice Input / Voice Output group —
    /// the candidate list narrows to audio-capable models (and the virtual
    /// System voice entry becomes selectable).
    @MainActor
    private func addModelsConfig() -> ModelPickerConfig {
        let gid = groupId
        let store = self.store
        let voiceNote: String? = {
            if store.voiceInputGroupId == gid {
                return AppLocalized("This group drives Voice Input — only models that accept audio input are listed.", comment: "Add-models filter note for the bound voice input group")
            }
            if store.voiceOutputGroupId == gid {
                return AppLocalized("This group drives Voice Output — only models that can output audio are listed.", comment: "Add-models filter note for the bound voice output group")
            }
            if store.visionGroupId == gid {
                return AppLocalized("This group describes images for models that cannot see them — only models that accept image input are listed.", comment: "Add-models filter note for the bound vision group")
            }
            return nil
        }()
        return ModelPickerConfig(
            title: "Add Models",
            mode: .multi,
            groupScope: .single(gid),
            existingIds: { Set(store.group(for: gid)?.memberEntryIds ?? []) },
            headerNote: voiceNote,
            showGroups: false,
            onAddMulti: { ids in
                guard var group = store.group(for: gid) else { return }
                group.memberEntryIds.append(contentsOf: ids.sorted())
                store.updateGroup(group)
            }
        )
    }

    // MARK: - Member Row

    private func memberRow(entry: ModelEntry, group: ModelGroup) -> some View {
        let instance = store.instance(for: entry.providerInstanceId)
        let providerDisabled = instance?.isEnabled == false
        return HStack(spacing: 10) {
            providerDot(entry)
                .opacity(providerDisabled ? 0.35 : 1)

            VStack(alignment: .leading, spacing: 1) {
                Text(entry.model.displayName)
                    .font(.subheadline)
                    .foregroundStyle(providerDisabled ? Color.secondary : Color(UIColor.label))
                if let instance = instance {
                    HStack(spacing: 4) {
                        Text(instance.label)
                        if providerDisabled {
                            Text("·")
                            Text("Provider disabled")
                                .foregroundStyle(.orange)
                        }
                    }
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                }
            }

            Spacer()

            // Same reasoning as ModelEntryDetailSheet.providerDisplayName:
            // `model.provider` is a denormalized display string whose content
            // depends on which path created the model (vendor name / protocol
            // type / instance UUID), so prefer the owning instance's label.
            // `instance` is already resolved above for the disabled check.
            Text({
                if let instance {
                    let label = instance.label.trimmingCharacters(in: .whitespacesAndNewlines)
                    return label.isEmpty ? instance.providerType.displayName : label
                }
                return entry.model.provider
            }())
                .font(.caption2)
                .foregroundStyle(.secondary)
                .opacity(providerDisabled ? 0.5 : 1)
        }
    }

    /// Row for the built-in System voice engine (virtual member — not in
    /// store.modelEntries). Shown with its proper name + icon as a valid,
    /// available model instead of the misleading "Model no longer available"
    /// stale row. Deletion goes through Edit mode like any regular member.
    private func systemMemberRow(entry: ModelEntry) -> some View {
        // TTS = the auto default OR any concrete voice (audioOutput); ASR otherwise.
        let isTTS = entry.model.id == "system-tts"
            || (entry.model.modalityOverride?.contains(.audioOutput) ?? false)
        return HStack(spacing: 10) {
            Image(systemName: isTTS ? "speaker.wave.2.fill" : "mic.fill")
                .font(.system(size: 12))
                .foregroundStyle(.blue)

            VStack(alignment: .leading, spacing: 1) {
                Text(entry.model.displayName)
                    .font(.subheadline)
                    .foregroundStyle(Color(UIColor.label))
                Text("iOS built-in, works offline")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }

            Spacer()

            Text("System")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    /// Row for a memberEntryId that no longer resolves to a ModelEntry —
    /// typically because the provider's model list changed and dropped this model.
    private func staleMemberRow(entryId: String, group: ModelGroup) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 12))
                .foregroundStyle(.orange)

            VStack(alignment: .leading, spacing: 1) {
                Text("Model no longer available")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Text(entryId)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }

            Spacer()

            Button {
                removeMember(entryId: entryId, from: group)
            } label: {
                Image(systemName: "trash")
                    .font(.caption)
                    .foregroundStyle(.red)
            }
            .buttonStyle(.plain)
        }
    }

    @ViewBuilder
    private func providerDot(_ entry: ModelEntry) -> some View {
        Circle()
            .fill(providerColor(entry.model.provider))
            .frame(width: 6, height: 6)
    }

    private func providerColor(_ provider: String) -> Color {
        switch provider {
        case "Anthropic": return .purple
        case "Google", "Google Gemini": return .blue
        case "OpenAI": return .green
        default: return .gray
        }
    }

    // MARK: - Settings Icon

    private func settingsIcon(_ systemName: String, color: Color) -> some View {
        Image(systemName: systemName)
            .font(.system(size: 9))
            .foregroundStyle(.white)
            .frame(width: 21, height: 21)
            .background(color, in: Circle())
    }

    // MARK: - Actions

    private func removeMember(entryId: String, from group: ModelGroup) {
        var updated = group
        updated.memberEntryIds.removeAll { $0 == entryId }
        clampThinkingLevelIfNeeded(&updated)
        store.updateGroup(updated)
    }

    private func moveMember(from source: IndexSet, to destination: Int, in group: ModelGroup) {
        var updated = group
        updated.memberEntryIds.move(fromOffsets: source, toOffset: destination)
        store.updateGroup(updated)
    }

    private func deleteMember(at offsets: IndexSet, from group: ModelGroup) {
        var updated = group
        updated.memberEntryIds.remove(atOffsets: offsets)
        clampThinkingLevelIfNeeded(&updated)
        store.updateGroup(updated)
    }

    private func clampThinkingLevelIfNeeded(_ group: inout ModelGroup) {
        guard let current = group.defaultThinkingLevel else { return }
        let maxLevel = groupMaxThinkingLevel(group)
        if current > maxLevel {
            group.defaultThinkingLevel = maxLevel
        }
    }

    /// Largest context window across the group's resolvable members. Uses
    /// `LLMModel.contextWindowTokens`, which falls back to a model-id heuristic
    /// (Anthropic 200K / Gemini 1M / GPT-4o 128K …) when the API hasn't enriched
    /// the field. Returns 64K only when the group has no resolvable members.
}

/// Discrete slider for context window limit selection.
/// Lower steps are fixed (32K…1M). The final "Unlimited" step stores a concrete
/// value — the largest member context window in the group — so the slider never
/// collapses the binding to nil (which would dismiss the parent toggle).
private struct ContextLimitSlider: View {
    @Binding var value: Int?

    /// Fixed 7-stop ladder, always shown verbatim regardless of which models
    /// (if any) are in the group. The previous behaviour clipped the ladder
    /// to the largest member-declared context window, but model metadata is
    /// unreliable (e.g. `google/nano-banana` reports no `gemini` substring
    /// and falls through to the 128K default) and the per-model field is
    /// already enforced at runtime by `effectiveContextWindow`. Keeping the
    /// ladder fixed makes the UI a pure user-intent slider — "I want this
    /// group to cap context at N" — and removes the entire family of bugs
    /// where adding/removing a model would silently shrink the picker.
    ///
    /// The "Unlimited" stop maps to `Int.max` in the binding; the consumer
    /// (`AIChatViewModel.effectiveContextWindow`) treats anything ≥ Int.max
    /// as "no override, use the model's native window".
    private let steps: [(label: String, tokens: Int)] = [
        ("32K",  32_000),
        ("64K",  64_000),
        ("128K", 128_000),
        ("200K", 200_000),
        ("400K", 400_000),
        ("1M",   1_000_000),
        (AppLocalized("Unlimited"), Int.max),
    ]

    private var sliderIndex: Double {
        guard let v = value else { return Double(steps.count - 1) }
        // Snap to nearest stop ≤ v (so a legacy saved 350K lands on 200K,
        // not 400K). Values at or above Int.max snap to Unlimited.
        if v >= Int.max { return Double(steps.count - 1) }
        let idx = steps.lastIndex(where: { $0.tokens <= v }) ?? 0
        return Double(idx)
    }

    private func indexToValue(_ index: Int) -> Int {
        steps[min(max(index, 0), steps.count - 1)].tokens
    }

    private var currentLabel: String {
        guard let v = value else { return steps.last?.label ?? AppLocalized("Unlimited") }
        if v >= Int.max { return AppLocalized("Unlimited") }
        if let match = steps.first(where: { $0.tokens == v }) { return match.label }
        return "\(v / 1000)K"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Max Context")
                    .font(.subheadline)
                Spacer()
                Text(currentLabel)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.accentColor)
                    .monospacedDigit()
            }
            Slider(
                value: Binding(
                    get: { sliderIndex },
                    set: { newIndex in
                        value = indexToValue(Int(newIndex.rounded()))
                    }
                ),
                in: 0...Double(steps.count - 1),
                step: 1
            )
            HStack {
                Text(steps.first?.label ?? "32K")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                Spacer()
                Text("Unlimited")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 4)
    }
}
