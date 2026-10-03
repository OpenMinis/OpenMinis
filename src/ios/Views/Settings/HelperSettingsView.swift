import SwiftUI

// [T-sub-agents-v1] Settings › Sub Agents — the switch and the definitions in
// one place. The type keeps its name (the navigation link that reaches it is
// wired by name in several places, and 8f09afa45 established that this rename
// wave only changes user-visible text, not identifiers).
//
// This page OWNS the `agent.tools.agents.enabled` switch. Settings › Tools no
// longer mentions agents at all — not even a read-only row — so there is
// exactly one place to look and no cross-reference to keep in sync.
struct HelperSettingsView: View {
    /// Legacy key of the switch this page used to own. No longer read by the
    /// tool gate; kept only so an old value never resurrects a toggle, and so
    /// `AgentToolSwitch.migrateLegacyIfNeeded()` can still consult it.
    static let legacyEnabledKey = "agent.helpers.enabled"

    @AppStorage(AgentToolSwitch.agents.key) private var agentsEnabled: Bool = AgentToolSwitch.agents.defaultValue
    @ObservedObject private var store = SubAgentStore.shared
    @State private var editorTarget: SubAgentEditorTarget?
    @State private var editMode: EditMode = .inactive

    private var roster: [SubAgentDefinition] { store.subAgents }

    var body: some View {
        Form {
            Section {
                // No leading icon here, unlike the same switch on Settings ›
                // Tools: that page lists several tools and the glyph tells them
                // apart, while this page is already the sub agents page — the
                // icon would only repeat the title it sits next to.
                Toggle(isOn: $agentsEnabled) {
                    // [T-sub-agents-ga] The orange "Exp" badge that sat beside
                    // the title is gone with the experimental status, and so is
                    // the HStack that existed only to hold it.
                    VStack(alignment: .leading, spacing: 2) {
                        Text(AppLocalized("Sub Agents"))
                        Text(SubAgentDefinition.toolName)
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                    }
                }
            } footer: {
                Text(AppLocalized("Delegate self-contained sub-tasks to a sub agent that works in its own hidden session and reports back. The same tool also lets the assistant check on or stop what it started. Several agents can run at once, so a large fan-out uses correspondingly more of your quota. On by default; when off, it is not offered to the model at all."))
            }

            Section {
                ForEach(roster) { def in
                    Button {
                        editorTarget = SubAgentEditorTarget(definition: def)
                    } label: {
                        row(def)
                    }
                    .buttonStyle(.plain)
                    // The built-in is the target of every delegation that names
                    // no agent, so it must not be deletable.
                    .deleteDisabled(def.isBuiltIn)
                    .moveDisabled(def.isBuiltIn)
                }
                .onDelete(perform: delete)
                .onMove(perform: move)

                Button {
                    editorTarget = SubAgentEditorTarget(definition: nil)
                } label: {
                    Label(AppLocalized("Add Sub Agent"), systemImage: "plus.circle")
                }
                .disabled(!store.canAddSubAgent)
            } header: {
                HStack {
                    Text(AppLocalized("Sub Agents"))
                    Spacer()
                    if roster.count > 1 { EditButton().font(.caption) }
                }
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(AppLocalized("The assistant picks a sub agent by its description, so write the description as “when to use this”. Order is the order it sees them in."))
                    if !store.canAddSubAgent {
                        Text(String(format: AppLocalized("You can define up to %d sub agents."), SubAgentLimits.maxCount))
                            .foregroundStyle(.secondary)
                    }
                }
                .font(.caption)
            }
            // Greyed out but still editable when the switch is off: off means
            // "not offered to the model", not "cannot be configured" — same
            // rule the other tool switches follow.
            .opacity(agentsEnabled ? 1 : 0.5)
        }
        .environment(\.editMode, $editMode)
        .navigationTitle(AppLocalized("Sub Agents"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $editorTarget) { target in
            SubAgentEditorView(existing: target.definition)
        }
    }

    @ViewBuilder
    private func row(_ def: SubAgentDefinition) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(def.displayName)
                    if def.isBuiltIn {
                        Text(AppLocalized("Built-in"))
                            .font(.caption2)
                            .padding(.horizontal, 5).padding(.vertical, 1)
                            .background(Color.secondary.opacity(0.15), in: Capsule())
                            .foregroundStyle(.secondary)
                    }
                }
                if !def.displayDescription.isEmpty {
                    Text(def.displayDescription)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Text(modelLabel(def))
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
    }

    private func modelLabel(_ def: SubAgentDefinition) -> String {
        if let gid = def.modelGroupId, let g = ProviderConfigStore.shared.group(for: gid) { return g.name }
        return AppLocalized("Auto")
    }

    private func delete(at offsets: IndexSet) {
        for idx in offsets {
            let def = roster[idx]
            guard !def.isBuiltIn else { continue }
            store.removeSubAgent(id: def.id)
        }
    }

    private func move(from source: IndexSet, to destination: Int) {
        var ids = roster.map(\.id)
        ids.move(fromOffsets: source, toOffset: destination)
        store.reorderSubAgents(ids)
    }
}

/// What the editor sheet should open with. `.sheet(item:)` rather than a Bool
/// plus a payload, so presentation is driven by the value's identity.
struct SubAgentEditorTarget: Identifiable {
    let definition: SubAgentDefinition?
    var id: String { definition?.id ?? "new" }
}

/// Add / edit one sub agent. [T-sub-agents-v1]
struct SubAgentEditorView: View {
    let existing: SubAgentDefinition?

    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = SubAgentStore.shared
    /// [T-subagent-own-store] The model-group picker reads provider
    /// configuration, which is a different store from the roster. Observed so
    /// the picker refreshes when groups change.
    @ObservedObject private var providerStore = ProviderConfigStore.shared

    @State private var name = ""
    @State private var descriptionText = ""
    @State private var instructions = ""
    @State private var modelGroupId: String?
    @State private var thinkingOverride: ThinkingLevel?
    @State private var didSeed = false

    private var isBuiltIn: Bool { existing?.isBuiltIn ?? false }

    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var nameIsDuplicate: Bool {
        store.subAgentNameIsTaken(trimmedName, excluding: existing?.id)
    }

    private var isValid: Bool {
        !trimmedName.isEmpty
            && !descriptionText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !nameIsDuplicate
    }

    var body: some View {
        NavigationStack {
            Form {
                // [T-sub-agents-v1] The built-in's name and description are
                // fixed. They are what the delegating model reads to decide
                // what to hand over, and it is the fallback every unnamed
                // delegation lands on — a user who rewrote them to something
                // vague would quietly degrade every delegation with no way to
                // get the original wording back. Model and Instructions stay
                // editable: those are genuinely the user's call.
                Section {
                    if isBuiltIn {
                        Text(existing?.displayName ?? name).foregroundStyle(.secondary)
                    } else {
                        TextField(AppLocalized("Name"), text: $name)
                            .autocorrectionDisabled()
                        counter(name.count, SubAgentLimits.nameMaxLength)
                    }
                } header: {
                    Text(AppLocalized("Name"))
                } footer: {
                    if nameIsDuplicate {
                        Text(AppLocalized("Another sub agent already uses this name."))
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    if isBuiltIn {
                        Text(existing?.displayDescription ?? descriptionText).foregroundStyle(.secondary)
                    } else {
                        TextField(AppLocalized("Description"), text: $descriptionText, axis: .vertical)
                            .lineLimit(2...4)
                        counter(descriptionText.count, SubAgentLimits.descriptionMaxLength)
                    }
                } header: {
                    Text(AppLocalized("Description"))
                } footer: {
                    Text(isBuiltIn
                         ? AppLocalized("The built-in sub agent's name and description are fixed. You can still change its model and instructions.")
                         : AppLocalized("Describe when this sub agent should be used. Keep it short — details go in Instructions."))
                }

                Section {
                    TextField(AppLocalized("Instructions"), text: $instructions, axis: .vertical)
                        .lineLimit(4...12)
                    counter(instructions.count, SubAgentLimits.instructionsMaxLength)
                } header: {
                    Text(AppLocalized("Instructions"))
                } footer: {
                    // Two different footers, because the built-in's instructions
                    // are the default for every unnamed delegation while a
                    // custom agent's apply only to itself. Without saying so
                    // here, a user would reasonably expect the built-in's text
                    // to be a global prefix — it deliberately is not.
                    Text(isBuiltIn
                         ? AppLocalized("These instructions apply to every delegation that does not name a sub agent. Custom sub agents use their own.")
                         : AppLocalized("These instructions apply only to this sub agent."))
                }

                Section {
                    Picker(AppLocalized("Model"), selection: $modelGroupId) {
                        Text(AppLocalized("Auto")).tag(String?.none)
                        ForEach(providerStore.config.modelGroups) { group in
                            Text(group.name).tag(String?.some(group.id))
                        }
                    }
                } header: {
                    Text(AppLocalized("Model"))
                } footer: {
                    Text(AppLocalized("Auto lets the assistant choose per task: the same model as the conversation, your Default group for hard reasoning, or your light group for simple well-defined work. Pick a specific Model Group instead to run this sub agent on it every time."))
                }

                // [T-subagent-thinking-override] Same shape as a Model Group's
                // own Session Defaults section: a toggle for "set one at all",
                // and an intensity picker once it is on.
                Section {
                    Toggle(isOn: Binding(
                        get: { thinkingOverride != nil },
                        set: { thinkingOverride = $0 ? (thinkingOverride ?? .medium) : nil }
                    )) {
                        HStack {
                            Image("ThinkingIcon")
                                .resizable()
                                .renderingMode(.template)
                                .foregroundStyle(.white)
                                .frame(width: 11, height: 11)
                                .frame(width: 21, height: 21)
                                .background(.purple, in: Circle())
                            Text(AppLocalized("Override Reasoning"))
                        }
                    }

                    if thinkingOverride != nil {
                        let levels = overrideThinkingLevels
                        Picker(AppLocalized("Intensity"), selection: Binding(
                            get: {
                                // Same snap-to-offered-tier rule the group
                                // picker uses: a segmented picker whose
                                // selection matches no tag renders with nothing
                                // highlighted.
                                let current = thinkingOverride ?? .medium
                                if levels.contains(current) { return current }
                                return levels.last(where: { $0 <= current })
                                    ?? levels.first
                                    ?? current
                            },
                            set: { thinkingOverride = $0 }
                        )) {
                            ForEach(levels, id: \.self) { level in
                                Text(level.displayName).tag(level)
                            }
                        }
                        .pickerStyle(.segmented)
                    }
                } header: {
                    Text(AppLocalized("Reasoning"))
                } footer: {
                    Text(thinkingOverride == nil
                         ? AppLocalized("Off: runs use the reasoning level of the Model Group above, or of the conversation that delegated when the model is Auto.")
                         : AppLocalized("Every run of this sub agent uses this level, overriding the Model Group's own default. Models that cannot reason this hard are clamped to their own ceiling."))
                }
            }
            .navigationTitle(existing == nil ? AppLocalized("New Sub Agent") : AppLocalized("Edit Sub Agent"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppLocalized("Cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(AppLocalized("Save")) { save() }
                        .disabled(!isValid)
                }
            }
            .onAppear(perform: seed)
        }
    }

    /// [T-subagent-thinking-override] Tiers the intensity picker offers.
    ///
    /// With a group pinned these are the group's own — derived the same way
    /// `ModelGroupDetailView` does, as the UNION of what its reasoning-capable
    /// members support, so the list matches what the user sees there. On Auto
    /// there is no group to ask (the model picks one per task), so every tier
    /// is offered; the request path clamps per model anyway
    /// (`min(level, model.catalogMaxThinkingLevel)`).
    private var overrideThinkingLevels: [ThinkingLevel] {
        let store = ProviderConfigStore.shared
        guard let gid = modelGroupId, let group = store.group(for: gid) else {
            return ThinkingLevel.allCases.filter { $0 != .off }
        }
        let entries = group.memberEntryIds.compactMap { store.entry(for: $0) }
            .filter { $0.effectiveMaxThinkingLevel != .off }
        let ceiling = entries.map(\.effectiveMaxThinkingLevel).max() ?? .xhigh
        let union = Set(entries.flatMap { $0.selectableThinkingLevels })
        guard !union.isEmpty else {
            return ThinkingLevel.allCases.filter { $0 != .off && $0 <= ceiling }
        }
        return union.filter { $0 <= ceiling }.sorted()
    }

    private func counter(_ count: Int, _ limit: Int) -> some View {
        HStack {
            Spacer()
            Text("\(count)/\(limit)")
                .font(.caption2)
                .foregroundStyle(count > limit ? .red : .secondary)
        }
    }

    private func seed() {
        guard !didSeed else { return }
        didSeed = true
        guard let existing else { return }
        name = existing.name
        descriptionText = existing.description
        instructions = existing.instructions
        modelGroupId = existing.modelGroupId
        thinkingOverride = existing.thinkingLevelOverride
    }

    private func save() {
        // Clamp here as well as in the store: a paste can exceed the limit even
        // though the counter turns red.
        let def = SubAgentDefinition(
            id: existing?.id ?? UUID().uuidString,
            name: String(trimmedName.prefix(SubAgentLimits.nameMaxLength)),
            description: String(descriptionText.trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(SubAgentLimits.descriptionMaxLength)),
            instructions: String(instructions.trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(SubAgentLimits.instructionsMaxLength)),
            modelGroupId: modelGroupId,
            thinkingLevelOverride: thinkingOverride,
            isBuiltIn: isBuiltIn,
            sortOrder: existing?.sortOrder ?? 0
        )
        store.upsertSubAgent(def)
        dismiss()
    }
}
