import SwiftUI

// MARK: - GroupSlotPicker
//
// A single "slot → model group" selector used for Default Primary / Default Sub
// / Voice Input / Voice Output. Shows the current group via a Menu listing all
// groups + None, plus a "Create new group from models…" action that opens the
// unified model picker in multi-select mode, builds a ModelGroup from the chosen
// entries, and assigns it to this slot.

struct GroupSlotPicker: View {
    @ObservedObject private var store = ProviderConfigStore.shared
    let label: LocalizedStringKey
    @Binding var selection: String?
    var voiceDirection: VoiceDirection? = nil
    /// [T-ios-vision-group #182] Vision slot: filter the create-group picker to
    /// image-capable models. Orthogonal to `voiceDirection` (never both set) —
    /// kept as a separate flag rather than a third VoiceDirection case so the
    /// voice resolver's exhaustive switches stay untouched.
    var isVision: Bool = false

    @State private var showCreate = false

    private var selectedName: String {
        if let id = selection, let g = store.group(for: id) { return g.name }
        return AppLocalized("None", comment: "No group selected")
    }

    var body: some View {
        Menu {
            Picker(selection: $selection) {
                Text("None", comment: "No group selected").tag(String?.none)
                ForEach(store.modelGroups) { group in
                    Text(group.name).tag(Optional(group.id))
                }
            } label: { EmptyView() }

            Divider()
            Button {
                showCreate = true
            } label: {
                Label("Create group from models…", systemImage: "plus.rectangle.on.folder")
            }
        } label: {
            HStack {
                Text(label)
                    .foregroundStyle(Color(UIColor.label))
                Spacer()
                Text(selectedName)
                    .foregroundStyle(.secondary)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .sheet(isPresented: $showCreate) {
            NavigationStack {
                UnifiedModelPicker(config: createGroupConfig())
            }
        }
    }

    @MainActor
    private func createGroupConfig() -> ModelPickerConfig {
        let dir = voiceDirection
        let assign: (String) -> Void = { selection = $0 }
        return ModelPickerConfig(
            title: "Create Group",
            mode: .multi,
            explicitPreferModality: isVision
                ? [.imageInput]
                : dir.map { $0 == .input ? [.audioInput] : [.audioOutput] },
            groupScope: .none,
            headerNote: isVision
                ? AppLocalized("Showing models that can read images.", comment: "Vision group picker filter note")
                : dir?.filterNote,
            onAddMulti: { ids in
                guard !ids.isEmpty else { return }
                let name = Self.suggestedName(for: dir, isVision: isVision, store: ProviderConfigStore.shared)
                let group = ModelGroup(name: name, memberEntryIds: ids.sorted())
                ProviderConfigStore.shared.addGroup(group)
                assign(group.id)
            }
        )
    }

    private static func suggestedName(for dir: VoiceDirection?, isVision: Bool = false, store: ProviderConfigStore) -> String {
        let base: String
        if isVision {
            base = AppLocalized("Vision Input", comment: "Default vision group name")
        } else {
            switch dir {
            case .input:  base = AppLocalized("Voice Input", comment: "Default voice input group name")
            case .output: base = AppLocalized("Voice Output", comment: "Default voice output group name")
            case nil:     base = AppLocalized("New Group", comment: "Default group name")
            }
        }
        let existing = Set(store.modelGroups.map(\.name))
        if !existing.contains(base) { return base }
        var n = 2
        while existing.contains("\(base) \(n)") { n += 1 }
        return "\(base) \(n)"
    }
}
