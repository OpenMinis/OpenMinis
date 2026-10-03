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
            // [T-ios-groupslot-ipad-submenu] The group list is emitted as plain
            // Buttons, NOT as a nested `Picker`.
            //
            // A `Picker` inside a `Menu` is rendered by the idiom, not by us:
            // on iPhone UIKit inlines its options into the parent menu, but on
            // iPad and Mac it becomes a SUBMENU whose title comes from the
            // Picker's own label. This one passed `EmptyView()` as that label
            // (it is never meant to be seen -- the row's own label is drawn in
            // the `Menu`'s label below), so the submenu was titled with nothing:
            // an unnamed row that has to be opened before the groups appear.
            //
            // Buttons carry no such label requirement and are inlined
            // identically on every idiom, so the list looks the same
            // everywhere. The checkmark that `Picker` drew for the current
            // value is reproduced explicitly.
            Button {
                selection = nil
            } label: {
                if selection == nil {
                    Label(AppLocalized("None", comment: "No group selected"), systemImage: "checkmark")
                } else {
                    Text("None", comment: "No group selected")
                }
            }
            ForEach(store.modelGroups) { group in
                Button {
                    selection = group.id
                } label: {
                    if selection == group.id {
                        Label(group.name, systemImage: "checkmark")
                    } else {
                        Text(group.name)
                    }
                }
            }

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
