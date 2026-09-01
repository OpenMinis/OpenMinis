import SwiftUI

/// Container for the two halves of the backup feature.
///
/// The Settings entry used to be called "Backup" and dropped the user straight
/// into the create-a-backup form, with restore buried as a row inside it. That
/// under-sold what the screen does — restoring is a full flow of its own, and on
/// a new device it is the ONLY one the user wants — so the entry is now
/// "Backup & Restore" and the two flows are peers behind a segmented control.
///
/// Deliberately a thin container: it owns tab selection and the navigation
/// title, nothing else. `BackupSettingsView` and `BackupRestoreView` keep all
/// of their own state and logic exactly as they were, so this reorganisation
/// cannot regress export or restore behaviour.
struct BackupAndRestoreView: View {

    enum Tab: Hashable {
        case backup
        case restore
    }

    /// Which half to show. Defaults to `.backup` so the existing path through
    /// the app is unchanged; the restore deep-link passes `.restore`.
    @State private var tab: Tab

    /// A package to open immediately in the restore tab — set when the user
    /// opened a `.minisbak` from Files / AirDrop.
    private let initialPackageURL: URL?

    init(initialTab: Tab = .backup, initialPackageURL: URL? = nil) {
        _tab = State(initialValue: initialTab)
        self.initialPackageURL = initialPackageURL
    }

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $tab) {
                Text("Backup").tag(Tab.backup)
                Text("Restore").tag(Tab.restore)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.top, 8)
            .padding(.bottom, 4)

            // Both children are Forms that own their own scrolling, so they sit
            // directly under the picker rather than inside another container.
            //
            // [T-restore-keep-tab-state] BOTH are always in the hierarchy, with
            // the inactive one hidden — they are NOT switched between.
            //
            // This used to be a `switch`, which removes the inactive branch
            // from the view tree entirely and destroys its `@State` with it.
            // The comment here claimed the opposite, but switching tabs threw
            // away a picked package (and the multi-GB copy it had downloaded),
            // a half-filled passphrase, and the category selection — so a user
            // who glanced at the Backup tab mid-restore came back to an empty
            // screen and had to download the package again.
            //
            // ZStack + opacity/disabled keeps each child alive and keeps its
            // state. `allowsHitTesting` matters as much as `opacity`: an
            // invisible view still receives touches, so without it the hidden
            // tab would swallow taps meant for the visible one.
            ZStack {
                BackupSettingsView(embedded: true)
                    .opacity(tab == .backup ? 1 : 0)
                    .allowsHitTesting(tab == .backup)
                    .accessibilityHidden(tab != .backup)
                BackupRestoreView(initialPackageURL: initialPackageURL, embedded: true)
                    .opacity(tab == .restore ? 1 : 0)
                    .allowsHitTesting(tab == .restore)
                    .accessibilityHidden(tab != .restore)
            }
        }
        .navigationTitle("Backup & Restore")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Folder picker for choosing a backup destination.
///
/// A thin wrapper over `UIDocumentPickerViewController(forOpeningContentTypes:
/// [.folder])` — the same control Settings ▸ Mount External Folders uses, which
/// is deliberately not shared from there because that copy is `private` to its
/// file and this needed no changes to it.
///
/// This IS the "pick a network drive, browse into a directory, save here" flow
/// the feature calls for: servers connected in the Files app (SMB, WebDAV) and
/// cloud accounts all appear in this picker as browsable locations, and iOS
/// handles their protocols and credentials. Writing our own server browser
/// would mean shipping SMB/WebDAV clients to duplicate that.
struct BackupFolderPicker: UIViewControllerRepresentable {
    let onPick: (URL) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder])
        picker.delegate = context.coordinator
        picker.allowsMultipleSelection = false
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL) -> Void
        init(onPick: @escaping (URL) -> Void) { self.onPick = onPick }

        func documentPicker(_ controller: UIDocumentPickerViewController,
                            didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else { return }
            onPick(url)
        }
    }
}

/// Applies a navigation title only when a view is presented standalone.
///
/// `.navigationTitle("")` is NOT a no-op — an empty title is still a title, and
/// it overrode the container's "Backup & Restore", leaving the nav bar blank.
/// (Caught on device, not by reading the code.) Skipping the modifier entirely
/// is what actually lets the parent's title stand.
struct StandaloneTitle: ViewModifier {
    let title: String
    let active: Bool

    func body(content: Content) -> some View {
        if active {
            content
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
        } else {
            content
        }
    }
}
