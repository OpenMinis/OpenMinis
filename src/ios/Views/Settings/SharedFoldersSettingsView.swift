//
//  SharedFoldersSettingsView.swift
//  MinisApp
//
//  Settings screen that lists the three FileProvider-exposed shared directories
//  (shared, skills, memory), lets the user tap each row to browse its contents,
//  and toggles whether each one is visible in the iOS Files app under
//  "On My iPhone → Minis".
//

import SwiftUI
import FileProvider

struct SharedFoldersSettingsView: View {
    @StateObject private var model = SharedFoldersViewModel()

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Label("Shared folders", systemImage: "info.circle")
                        .font(.subheadline.weight(.semibold))
                    Text("These directories appear in iOS Files under On My iPhone → Minis. Tap a row to see details, browse, or toggle visibility.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            Section(AppLocalized("Folders")) {
                ForEach(model.entries) { entry in
                    NavigationLink {
                        MountDetailView(
                            context: detailContext(for: entry),
                            onDismiss: { model.refresh() }
                        )
                    } label: {
                        SharedFolderRow(
                            entry: entry,
                            isVisible: model.isVisible(entry.name)
                        )
                    }
                }
            }
        }
        .navigationTitle("Shared Folders")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func detailContext(for entry: SharedFolderEntry) -> MountDetailContext {
        MountDetailContext(
            kind: .shared,
            linuxPath: entry.linuxPath,
            sourceDisplayName: nil,
            hostURL: entry.hostURL,
            iconName: entry.iconName,
            iconColor: entry.iconColor,
            subtitle: nil,
            initialName: entry.displayName,
            canRename: false,
            externalMountId: nil,
            isOSWritable: entry.isWritableFromFiles,
            initialUserAllowWrite: true,
            sharedFolderName: entry.name,
            initialVisibleInFiles: model.isVisible(entry.name),
            isReadOnlyFromFiles: !entry.isWritableFromFiles
        )
    }
}

// MARK: - Row

private struct SharedFolderRow: View {
    let entry: SharedFolderEntry
    let isVisible: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: entry.iconName)
                .font(.system(size: 9))
                .foregroundStyle(.white)
                .frame(width: 21, height: 21)
                .background(entry.iconColor, in: Circle())

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(entry.displayName)
                        .font(.body)
                    accessBadge
                    if !isVisible {
                        Text("Hidden")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(
                                Capsule().strokeBorder(Color.secondary.opacity(0.5), lineWidth: 1)
                            )
                    }
                }
                Text(entry.linuxPath)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
        }
        .padding(.vertical, 2)
    }

    /// Small pill showing whether Files app access is read/write or read-only.
    @ViewBuilder
    private var accessBadge: some View {
        if entry.isWritableFromFiles {
            Text("R/W")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.green)
                .padding(.horizontal, 5)
                .padding(.vertical, 1)
                .background(
                    Capsule().strokeBorder(Color.green.opacity(0.5), lineWidth: 1)
                )
        } else {
            Text("Read-only")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.orange)
                .padding(.horizontal, 5)
                .padding(.vertical, 1)
                .background(
                    Capsule().strokeBorder(Color.orange.opacity(0.5), lineWidth: 1)
                )
        }
    }
}

// MARK: - Model

struct SharedFolderEntry: Identifiable, Hashable {
    /// UserDefaults key / FileProvider subdir name ("shared", "skills", "memory").
    let name: String
    let displayName: String
    /// Linux path inside iSH (e.g. "/var/minis/shared").
    let linuxPath: String
    /// Host filesystem URL (App Group container) for FileBrowserView.
    let hostURL: URL
    let iconName: String
    let iconColor: Color
    /// Whether the Files app (via FileProvider extension) can write to this
    /// folder. `memory` and `skills` are read-only from Files; `shared` is r/w.
    /// Note: the MinisApp itself always reads/writes all three — this flag only
    /// describes the Files-app-facing surface.
    let isWritableFromFiles: Bool

    var id: String { name }
}

@MainActor
final class SharedFoldersViewModel: ObservableObject {
    @Published private(set) var entries: [SharedFolderEntry]
    @Published private var visibilityState: [String: Bool]

    init() {
        self.entries = Self.buildEntries()
        var state: [String: Bool] = [:]
        for name in SharedFolderVisibility.allFolderNames {
            state[name] = SharedFolderVisibility.isVisible(name)
        }
        self.visibilityState = state
    }

    func isVisible(_ name: String) -> Bool {
        visibilityState[name] ?? true
    }

    func setVisible(_ name: String, to visible: Bool) {
        visibilityState[name] = visible
        SharedFolderVisibility.setVisible(name, to: visible)
        // Signal the FileProvider to re-enumerate so iOS Files reflects the
        // change. This is best-effort — iOS Files may take a moment to refresh.
        SharedFoldersViewModel.signalFileProviderRoot()
    }

    /// Re-read visibility state from the underlying UserDefaults store. Called
    /// by the detail view's onDismiss so the parent list updates after a save.
    func refresh() {
        var state: [String: Bool] = [:]
        for name in SharedFolderVisibility.allFolderNames {
            state[name] = SharedFolderVisibility.isVisible(name)
        }
        self.visibilityState = state
    }

    private static func buildEntries() -> [SharedFolderEntry] {
        let root = AIChatViewModel.minisAppGroupRoot
        return [
            SharedFolderEntry(
                name: "shared",
                displayName: AppLocalized("Shared"),
                linuxPath: "/var/minis/shared",
                hostURL: root.appendingPathComponent("shared", isDirectory: true),
                iconName: "folder.fill",
                iconColor: .blue,
                isWritableFromFiles: true
            ),
            SharedFolderEntry(
                name: "skills",
                displayName: AppLocalized("Skills"),
                linuxPath: "/var/minis/skills",
                hostURL: root.appendingPathComponent("skills", isDirectory: true),
                iconName: "sparkles",
                iconColor: .purple,
                isWritableFromFiles: false
            ),
            SharedFolderEntry(
                name: "memory",
                displayName: AppLocalized("Memory"),
                linuxPath: "/var/minis/memory",
                hostURL: root.appendingPathComponent("memory", isDirectory: true),
                iconName: "brain.head.profile",
                iconColor: .pink,
                isWritableFromFiles: false
            ),
        ]
    }

    private static func signalFileProviderRoot() {
        let domainIdentifier = NSFileProviderDomainIdentifier("com.openminis.app.files")
        NSFileProviderManager.getDomainsWithCompletionHandler { domains, _ in
            guard let domain = domains.first(where: { $0.identifier == domainIdentifier }) else {
                return
            }
            NSFileProviderManager(for: domain)?.signalEnumerator(for: .rootContainer) { _ in }
        }
    }
}
