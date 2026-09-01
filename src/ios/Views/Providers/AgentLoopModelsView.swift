import SwiftUI

/// Inline section for ModelGroupsView — manages models available via minis-model-use.
/// Supports both individual model entries and entire model groups.
/// Sheet bindings are passed in from the parent to avoid hanging .sheet on a Section inside a List.
struct AgentLoopModelsSection: View {
    @ObservedObject private var store = ProviderConfigStore.shared
    @Binding var showAddModels: Bool
    @Binding var showAddGroups: Bool

    private var currentEntries: [ModelEntry] {
        store.agentLoopModelEntryIds.compactMap { store.entry(for: $0) }
    }

    private var currentGroups: [ModelGroup] {
        store.agentLoopGroupIds.compactMap { store.group(for: $0) }
    }

    var body: some View {
        Section {
            if currentGroups.isEmpty && currentEntries.isEmpty {
                Text("No models or groups added")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if !currentGroups.isEmpty {
                ForEach(currentGroups) { group in
                    groupRow(group)
                }
                .onMove(perform: moveGroups)
                .onDelete(perform: deleteGroups)
            }

            if !currentEntries.isEmpty {
                ForEach(currentEntries) { entry in
                    entryRow(entry)
                }
                .onMove(perform: moveEntries)
                .onDelete(perform: deleteEntries)
            }

            Menu {
                Button {
                    showAddModels = true
                } label: {
                    Label("Add Models", systemImage: "cpu")
                }
                if !availableGroups.isEmpty {
                    Button {
                        showAddGroups = true
                    } label: {
                        Label("Add Group", systemImage: "square.stack.3d.up")
                    }
                }
            } label: {
                Label("Add Models or Groups", systemImage: "plus.circle")
                    .font(.subheadline)
            }
        } header: {
            HStack {
                Text("Models Minis Can Call at Runtime")
                Spacer()
                if !currentEntries.isEmpty || !currentGroups.isEmpty {
                    EditButton()
                        .font(.caption)
                }
            }
        } footer: {
            Text("During a Minis task, the agent calls these models for sub-tasks such as generating an image or summarizing text — work its own model can't do. Also callable from the terminal via minis-model-use. Only these are visible to the agent.")
        }
    }

    // MARK: - Rows

    private func groupRow(_ group: ModelGroup) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "square.stack.3d.up.fill")
                .font(.caption)
                .foregroundStyle(.blue)
                .frame(width: 16)

            VStack(alignment: .leading, spacing: 1) {
                Text(group.name)
                    .font(.subheadline.weight(.medium))
                let count = group.memberEntryIds.count
                Text(AppLocalized("\(count) models") + " · \(group.strategy == .fallback ? AppLocalized("Fallback") : AppLocalized("Load Balance"))")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }

            Spacer()

            Text("Group")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private func entryRow(_ entry: ModelEntry) -> some View {
        HStack(spacing: 10) {
            Circle()
                .fill(providerColor(entry.model.provider))
                .frame(width: 6, height: 6)
                .frame(width: 16)

            VStack(alignment: .leading, spacing: 1) {
                Text(entry.model.displayName)
                    .font(.subheadline)
                HStack(spacing: 4) {
                    if let instance = store.instance(for: entry.providerInstanceId) {
                        Text(instance.label)
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                    let badges = modalityBadges(entry.model)
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
                }
            }

            Spacer()

            Text(entry.model.provider)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Available groups (not yet added)

    private var availableGroups: [ModelGroup] {
        let existing = Set(store.agentLoopGroupIds)
        return store.modelGroups.filter { !existing.contains($0.id) }
    }

    // MARK: - Actions

    // The IndexSet SwiftUI hands us is based on `currentEntries`, which is the raw
    // id array filtered through `compactMap { store.entry(for: $0) }`. If the raw
    // `agentLoopModelEntryIds` contains any orphan ids (entries that no longer
    // resolve — e.g. from deleted providers or iCloud-synced ids with no local
    // counterpart), the two arrays have different lengths and indexing into the
    // raw array with visible indices corrupts the result — reorders snap back,
    // deletes remove the wrong row. Work on visible ids and drop orphans in the
    // same pass so the store stays clean.
    private func moveEntries(from source: IndexSet, to destination: Int) {
        var visibleIds = currentEntries.map { $0.id }
        visibleIds.move(fromOffsets: source, toOffset: destination)
        store.agentLoopModelEntryIds = visibleIds
    }

    private func deleteEntries(at offsets: IndexSet) {
        var visibleIds = currentEntries.map { $0.id }
        visibleIds.remove(atOffsets: offsets)
        store.agentLoopModelEntryIds = visibleIds
    }

    private func moveGroups(from source: IndexSet, to destination: Int) {
        var visibleIds = currentGroups.map { $0.id }
        visibleIds.move(fromOffsets: source, toOffset: destination)
        store.agentLoopGroupIds = visibleIds
    }

    private func deleteGroups(at offsets: IndexSet) {
        var visibleIds = currentGroups.map { $0.id }
        visibleIds.remove(atOffsets: offsets)
        store.agentLoopGroupIds = visibleIds
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

    private func providerColor(_ provider: String) -> Color {
        switch provider {
        case "Anthropic": return .purple
        case "Google", "Google Gemini": return .blue
        case "OpenAI": return .green
        case "Antigravity": return .orange
        case "OpenRouter": return .cyan
        default: return .gray
        }
    }
}

// MARK: - Add Groups Sheet

struct AddAgentLoopGroupsSheet: View {
    @ObservedObject private var store = ProviderConfigStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var selectedGroupIds: Set<String> = []

    private var availableGroups: [ModelGroup] {
        let existing = Set(store.agentLoopGroupIds)
        return store.modelGroups.filter { !existing.contains($0.id) }
    }

    var body: some View {
        List {
            if availableGroups.isEmpty {
                Section {
                    Text("All groups are already added.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                }
            }

            ForEach(availableGroups) { group in
                Button {
                    toggleSelection(group.id)
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: selectedGroupIds.contains(group.id) ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 20))
                            .foregroundStyle(selectedGroupIds.contains(group.id) ? Color.accentColor : Color(UIColor.tertiaryLabel))

                        VStack(alignment: .leading, spacing: 2) {
                            Text(group.name)
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(Color(UIColor.label))
                            let entries = group.memberEntryIds.compactMap { store.entry(for: $0) }
                            let names = entries.prefix(3).map(\.model.displayName)
                            let suffix = entries.count > 3 ? " +\(entries.count - 3)" : ""
                            Text(names.joined(separator: ", ") + suffix)
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                                .lineLimit(1)
                        }

                        Spacer()

                        Text("\(group.memberEntryIds.count) models")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Add Groups")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Add (\(selectedGroupIds.count))") {
                    addSelected()
                }
                .font(.body.weight(.semibold))
                .disabled(selectedGroupIds.isEmpty)
            }
        }
    }

    private func toggleSelection(_ groupId: String) {
        if selectedGroupIds.contains(groupId) {
            selectedGroupIds.remove(groupId)
        } else {
            selectedGroupIds.insert(groupId)
        }
    }

    private func addSelected() {
        for id in selectedGroupIds.sorted() {
            store.addAgentLoopGroup(id)
        }
        dismiss()
    }
}
