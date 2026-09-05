//
//  MountDetailView.swift
//  MinisApp
//
//  Unified detail page for both "Shared Folders" (shared/skills/memory) and
//  "Mount External Folders" (user-picked external directories). Provides:
//   - Header card with display name, source, linux path, status
//   - Editable name field (external mounts only)
//   - Allow-writes toggle (external mounts, when OS-writable)
//   - Files-visibility toggle (shared folders only)
//   - "Browse Files" button (both)
//   - "Unmount" destructive button (external mounts only)
//   - Top-right Save button enabled only when changes are pending
//

import SwiftUI
import FileProvider

/// Kind of mount this detail page is editing.
enum MountDetailKind {
    case shared
    case external
}

/// Immutable input to the detail view — describes the mount being edited.
struct MountDetailContext: Equatable {
    let kind: MountDetailKind

    // Display
    let linuxPath: String
    let sourceDisplayName: String?
    /// The resolved host URL for the "Browse Files" action.
    let hostURL: URL
    /// SF Symbol name used in the header icon.
    let iconName: String
    /// Icon background color.
    let iconColor: Color
    /// Shown in a subtitle line; optional.
    let subtitle: String?

    // Editable defaults
    let initialName: String
    /// Whether the name can be edited. Shared folders disable this.
    let canRename: Bool

    // External-mount-only state
    let externalMountId: UUID?
    /// OS-level writability. If false, the allow-writes toggle is disabled + off.
    let isOSWritable: Bool
    /// Current user-intent allow-writes flag.
    let initialUserAllowWrite: Bool

    // Shared-folders-only state
    /// For shared folders: the UserDefaults key / subdir name.
    let sharedFolderName: String?
    let initialVisibleInFiles: Bool
    /// Read-only badge for shared folders (skills/memory are read-only from Files).
    let isReadOnlyFromFiles: Bool
}

struct MountDetailView: View {
    let context: MountDetailContext
    let onDismiss: () -> Void

    @Environment(\.dismiss) private var dismissEnvironment

    @State private var nameText: String
    @State private var allowWrite: Bool
    @State private var visibleInFiles: Bool
    @State private var showingBrowser = false
    @State private var showingUnmountConfirm = false
    @State private var errorText: String?

    init(context: MountDetailContext, onDismiss: @escaping () -> Void) {
        self.context = context
        self.onDismiss = onDismiss
        _nameText = State(initialValue: context.initialName)
        _allowWrite = State(initialValue: context.initialUserAllowWrite)
        _visibleInFiles = State(initialValue: context.initialVisibleInFiles)
    }

    // MARK: - Dirty tracking

    private var nameChanged: Bool {
        nameText.trimmingCharacters(in: .whitespaces) != context.initialName
    }

    private var allowWriteChanged: Bool {
        context.kind == .external && allowWrite != context.initialUserAllowWrite
    }

    private var visibilityChanged: Bool {
        context.kind == .shared && visibleInFiles != context.initialVisibleInFiles
    }

    private var hasChanges: Bool {
        nameChanged || allowWriteChanged || visibilityChanged
    }

    private var nameIsValid: Bool {
        MountedFolderEntry.isValidMountName(nameText)
    }

    private var canSave: Bool {
        guard hasChanges else { return false }
        if nameChanged && !nameIsValid { return false }
        return true
    }

    // MARK: - Body

    var body: some View {
        List {
            headerSection

            if context.canRename {
                nameSection
            }

            if context.kind == .external && context.isOSWritable {
                allowWriteSection
            }

            if context.kind == .shared {
                visibilitySection
            }

            browseSection

            if context.kind == .external {
                unmountSection
            }
        }
        .navigationTitle(context.canRename ? AppLocalized("Edit Mount") : AppLocalized("Folder Details"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(AppLocalized("Save")) {
                    save()
                }
                .disabled(!canSave)
            }
        }
        .sheet(isPresented: $showingBrowser) {
            NavigationStack {
                FileBrowserView(
                    rootPath: context.hostURL,
                    rootLabel: context.linuxPath
                )
            }
        }
        .alert(
            AppLocalized("Unmount this folder?"),
            isPresented: $showingUnmountConfirm
        ) {
            Button(AppLocalized("Unmount"), role: .destructive) {
                unmountExternal()
            }
            Button(AppLocalized("Cancel"), role: .cancel) {}
        } message: {
            Text("The source folder in Files will not be deleted. You can re-mount it later.")
        }
        .alert(
            AppLocalized("Error"),
            isPresented: Binding(
                get: { errorText != nil },
                set: { if !$0 { errorText = nil } }
            )
        ) {
            Button(AppLocalized("OK"), role: .cancel) { errorText = nil }
        } message: {
            Text(errorText ?? "")
        }
    }

    // MARK: - Sections

    private var headerSection: some View {
        Section {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: context.iconName)
                    .font(.system(size: 13))
                    .foregroundStyle(.white)
                    .frame(width: 32, height: 32)
                    .background(context.iconColor, in: RoundedRectangle(cornerRadius: 8))
                VStack(alignment: .leading, spacing: 4) {
                    Text(context.initialName)
                        .font(.headline)
                    Text(context.linuxPath)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    if let source = context.sourceDisplayName {
                        Text(verbatim: "← \(source)")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                            .lineLimit(1)
                    }
                    if let subtitle = context.subtitle {
                        Text(subtitle)
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                }
                Spacer()
            }
            .padding(.vertical, 4)
        }
    }

    private var nameSection: some View {
        Section {
            TextField("name", text: $nameText)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            if nameChanged && !nameIsValid {
                Text("Mount name must not be empty, contain '/', or be '.' or '..'.")
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        } header: {
            Text("Name")
        } footer: {
            Text("Becomes the folder name under /var/minis/mounts/")
        }
    }

    private var allowWriteSection: some View {
        Section {
            Toggle(isOn: $allowWrite) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Allow writes")
                    Text(allowWrite
                         ? AppLocalized("AI, shell, and Files browser can modify files in this mount.")
                         : AppLocalized("This mount is exposed as read-only to protect it from accidental edits."))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        } header: {
            Text("Permissions")
        } footer: {
            Text("When off, the iSH shell and AI tools cannot create, edit, or delete files in this mount.")
        }
    }

    private var visibilitySection: some View {
        Section {
            if !SharedContainerStore.isAppGroupAvailable {
                Label("Shared access is unavailable. This folder is accessible inside Minis only.", systemImage: "info.circle")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                Toggle(isOn: $visibleInFiles) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Show in Files app")
                        Text(visibleInFiles
                             ? AppLocalized("This folder appears in Files → On My iPhone → Minis.")
                             : AppLocalized("This folder is hidden from the iOS Files app."))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                if context.isReadOnlyFromFiles {
                    Text("Read-only from the Files app. The app itself can always read and write.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        } header: {
            Text("Files app")
        }
    }

    private var browseSection: some View {
        Section {
            Button {
                showingBrowser = true
            } label: {
                Label {
                    Text("Browse Files")
                } icon: {
                    Image(systemName: "folder.fill")
                        .font(.system(size: 9))
                        .foregroundStyle(.white)
                        .frame(width: 21, height: 21)
                        .background(.blue, in: Circle())
                }
            }
        }
    }

    private var unmountSection: some View {
        Section {
            Button(role: .destructive) {
                showingUnmountConfirm = true
            } label: {
                Label {
                    Text("Unmount")
                } icon: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 9))
                        .foregroundStyle(.white)
                        .frame(width: 21, height: 21)
                        .background(.red, in: Circle())
                }
            }
        }
    }

    // MARK: - Actions

    private func save() {
        switch context.kind {
        case .external:
            guard let id = context.externalMountId else { return }
            let manager = MountedFoldersManager.shared
            do {
                if nameChanged {
                    try manager.rename(id: id, to: nameText.trimmingCharacters(in: .whitespaces))
                }
                if allowWriteChanged {
                    manager.setUserAllowWrite(id: id, to: allowWrite)
                }
            } catch {
                errorText = error.localizedDescription
                return
            }
        case .shared:
            if visibilityChanged, let name = context.sharedFolderName {
                SharedFolderVisibility.setVisible(name, to: visibleInFiles)
                // Ask the FileProvider to re-enumerate so iOS Files reflects the change.
                signalFileProviderRoot()
            }
        }
        onDismiss()
        dismissEnvironment()
    }

    private func unmountExternal() {
        guard let id = context.externalMountId else { return }
        MountedFoldersManager.shared.remove(id: id)
        onDismiss()
        dismissEnvironment()
    }

    private func signalFileProviderRoot() {
        guard SharedContainerStore.isAppGroupAvailable else { return }
        let domainIdentifier = NSFileProviderDomainIdentifier("com.openminis.app.files")
        NSFileProviderManager.getDomainsWithCompletionHandler { domains, _ in
            guard let domain = domains.first(where: { $0.identifier == domainIdentifier }) else {
                return
            }
            NSFileProviderManager(for: domain)?.signalEnumerator(for: .rootContainer) { _ in }
        }
    }
}
