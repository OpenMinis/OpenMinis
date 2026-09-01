import SwiftUI
import UIKit
import UniformTypeIdentifiers

private let logger = AppLogger(category: "Backup")

/// Hands a finished `.minisbak` package to the user
/// (docs/backup-restore-design.md §6.2 path 1).
///
/// Until this existed, `BackupExporter` left the package in the app's `tmp/`
/// where nothing could reach it — the export chain was complete but the result
/// was unreachable.
///
/// Why this is the whole remote-destination story: the share sheet and
/// "Save to Files" reach **any** provider the user has mounted in Files —
/// iCloud Drive, Dropbox, OneDrive, Google Drive, 坚果云, an SMB or AFP server
/// (Files has native "Connect to Server"), or any third-party WebDAV client.
/// The protocol is the FileProvider's job, not ours, so no network code is
/// needed for the destinations §6.2 lists.
enum BackupDelivery {

    /// The registered UTI for `.minisbak` (Info.plist `UTExportedTypeDeclarations`).
    ///
    /// Registering it matters for two reasons beyond tidiness:
    ///   1. `MinisShareSheet.sanitizedShareURL` copies the file to a `.bin`
    ///      neighbour whenever `UTType(filenameExtension:)` can't resolve the
    ///      extension. For a multi-GB backup that would silently double disk
    ///      use and hand the user a file named `backup-….bin`. With the type
    ///      declared, the extension resolves and no copy happens.
    ///   2. It sets up "open the file to import it", which the restore side
    ///      will want.
    static let contentTypeIdentifier = "com.openminis.app.minisbak"

    static var contentType: UTType {
        UTType(contentTypeIdentifier)
            ?? UTType(filenameExtension: BackupFormat.fileExtension)
            ?? .data
    }

    /// Directory name for delivered packages, a SIBLING of `shared` / `skills`
    /// / `memory` under the App Group's FileProvider root.
    ///
    /// Also used by the Shared Files exporter as a defensive exclusion — see
    /// `BackupFileTreeExporter`.
    static let backupsDirectoryName = "Backups"

    /// Where delivered packages live: `<AppGroup>/MinisFileProvider/Backups/`.
    static var backupsDirectory: URL {
        AIChatViewModel.minisAppGroupRoot
            .appendingPathComponent(backupsDirectoryName, isDirectory: true)
    }

    /// Move a freshly exported package out of `tmp/` into a stable location.
    ///
    /// `BackupExporter` writes into `tmp/`, which iOS may purge at any time and
    /// which the Files app cannot browse. Anything the user is expected to act
    /// on later has to leave it.
    ///
    /// **Why a sibling of `shared/` and not inside it.** An earlier version of
    /// this used `shared/Backups/`, which was wrong in three ways:
    ///   1. `shared/` is bind-mounted into the guest at `/var/minis/shared`
    ///      (`ISHExecutionCoordinator.swift:498-503`), so every delivered
    ///      package — including one holding API keys — was readable and
    ///      deletable by the agent from a shell command. A backup is an app
    ///      asset, not part of the agent's workspace.
    ///   2. `shared/` IS the Shared Files backup category (§3.2), so the next
    ///      backup would scan the previous package in as "user data" and nest
    ///      packages inside packages, growing without bound.
    ///   3. Semantically, a delivery destination should not reuse the
    ///      "agent-visible shared workspace" concept at all.
    ///
    /// Files-app visibility does NOT require registering this name with the
    /// FileProvider: `FileProviderEnumerator` lists whatever is on disk under
    /// `providerRoot` and only filters names it knows about
    /// (`FileProviderEnumerator.swift:147-157` passes unknown entries through
    /// unchanged, deliberately "so future additions are safe"). Leaving it out
    /// of `topLevelSubdirs` is therefore correct AND desirable: that list also
    /// drives the Shared Folders visibility toggles and the rename/delete
    /// blocks, none of which should apply to a backups folder the user may well
    /// want to clear out.
    @discardableResult
    static func moveToVisibleStorage(_ packageURL: URL) throws -> URL {
        let dir = backupsDirectory
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let dest = dir.appendingPathComponent(packageURL.lastPathComponent)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: packageURL, to: dest)
        logger.info("[Backup] package delivered to \(backupsDirectoryName)/\(dest.lastPathComponent)")
        return dest
    }

    /// Remove any packages left under the OLD `shared/Backups/` location.
    ///
    /// One-shot migration for builds that shipped the earlier path: those files
    /// are sitting in the agent-visible workspace and would be swept into the
    /// next Shared Files export. Called on app start.
    static func migrateLegacySharedBackups() {
        let legacy = AIChatViewModel.minisSharedPersistentDir
            .appendingPathComponent(backupsDirectoryName, isDirectory: true)
        let fm = FileManager.default
        guard fm.fileExists(atPath: legacy.path) else { return }
        let names = (try? fm.contentsOfDirectory(atPath: legacy.path)) ?? []
        var moved = 0
        for name in names where name.hasSuffix("." + BackupFormat.fileExtension) {
            let dest = backupsDirectory.appendingPathComponent(name)
            try? fm.createDirectory(at: backupsDirectory, withIntermediateDirectories: true)
            try? fm.removeItem(at: dest)
            if (try? fm.moveItem(at: legacy.appendingPathComponent(name), to: dest)) != nil {
                moved += 1
            }
        }
        // Only remove the legacy directory if it is now empty — anything else
        // in there was not put there by us and is the user's.
        if ((try? fm.contentsOfDirectory(atPath: legacy.path))?.isEmpty ?? false) {
            try? fm.removeItem(at: legacy)
        }
        if moved > 0 {
            logger.info("[Backup] migrated \(moved) package(s) out of the agent-visible shared/ directory")
        }
    }

    /// Copy a package into a user-authorised mounted folder (§6.2 path 2).
    ///
    /// This is the unattended path: once the user has mounted a WebDAV / SMB /
    /// cloud folder in Files and authorised it in Minis, a backup can be
    /// written straight into it with no share sheet and no network code. Uses
    /// the existing coordinated-write helper so a third-party FileProvider
    /// actually sees the write and uploads it.
    /// [review I1] `nonisolated` so the transfer can run off the main thread —
    /// see BackupDestinations.deliver. Takes an already-resolved root rather
    /// than a folder id, because resolving it requires the main actor and this
    /// deliberately does not.
    ///
    /// [review I7] Writes to a `.partial` sibling and renames on success. The
    /// previous version copied straight to the final name, and NSFileCoordinator
    /// guarantees coordination with the FileProvider — not atomicity of the
    /// underlying byte transfer. A suspension or dropped share mid-copy left a
    /// TRUNCATED file carrying a perfectly valid `.minisbak` name, which then
    /// appeared in the restore picker with a plausible size and date. The user
    /// would discover it only when the restore failed, plausibly on a new device
    /// after wiping the old one. A rename within one volume is atomic and cheap,
    /// so the final name now appears only once the bytes are all there.
    nonisolated static func copyToMountedFolder(_ packageURL: URL, into root: URL) throws -> URL {
        try MountedFolderCoordinator.requireWritable(root)
        let dest = root.appendingPathComponent(packageURL.lastPathComponent)
        let partial = root.appendingPathComponent(".\(packageURL.lastPathComponent).partial")

        try? FileManager.default.removeItem(at: partial)
        do {
            try MountedFolderCoordinator.copy(from: packageURL, to: partial)
            // Verify BEFORE the rename. A FileProvider backend (iCloud, a
            // third-party cloud) can acknowledge a truncated write without
            // erroring, and a short package would only surface months later
            // as "corrupt archive" during a restore. Size is the strongest
            // check every provider supports.
            let want = (try? FileManager.default.attributesOfItem(
                atPath: packageURL.path)[.size] as? Int64) ?? -1
            let got = (try? FileManager.default.attributesOfItem(
                atPath: partial.path)[.size] as? Int64) ?? -2
            guard want == got else {
                throw NSError(domain: "Backup", code: 1, userInfo: [
                    NSLocalizedDescriptionKey: AppLocalized("Copy verification failed: the folder has \(max(got, 0)) bytes but the backup is \(want) bytes."),
                ])
            }
        } catch {
            // Never leave the scratch file behind on a failed transfer.
            try? FileManager.default.removeItem(at: partial)
            throw error
        }
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: partial, to: dest)
        logger.info("[Backup] package copied to mounted folder at \(root.lastPathComponent)")
        return dest
    }
}

// MARK: - Share sheet

/// `UIActivityItemSource` for the package.
///
/// A bare `URL` is not enough: on iPad "Save to Files" resolves it through a
/// file-provider domain, which fails for an app-`tmp/` URL and saves an EMPTY
/// file — the failure mode already documented for skill export
/// ([T-ios-ipad-skill-export-file-empty-save]). Declaring the concrete UTI lets
/// the document picker copy the real bytes.
final class BackupActivityItemSource: NSObject, UIActivityItemSource {
    private let fileURL: URL

    init(fileURL: URL) {
        self.fileURL = fileURL
        super.init()
    }

    func activityViewControllerPlaceholderItem(_ controller: UIActivityViewController) -> Any {
        fileURL
    }

    func activityViewController(_ controller: UIActivityViewController,
                                itemForActivityType activityType: UIActivity.ActivityType?) -> Any? {
        fileURL
    }

    func activityViewController(_ controller: UIActivityViewController,
                                dataTypeIdentifierForActivityType activityType: UIActivity.ActivityType?) -> String {
        BackupDelivery.contentTypeIdentifier
    }

    func activityViewController(_ controller: UIActivityViewController,
                                subjectForActivityType activityType: UIActivity.ActivityType?) -> String {
        fileURL.lastPathComponent
    }
}

/// Share sheet for a backup package.
struct BackupShareSheet: UIViewControllerRepresentable {
    let url: URL
    var onDismiss: (() -> Void)?

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(
            activityItems: [BackupActivityItemSource(fileURL: url)],
            applicationActivities: nil)
        controller.completionWithItemsHandler = { _, _, _, _ in onDismiss?() }
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

/// "Save to Files" — `UIDocumentPickerViewController` in export mode, the same
/// component the session export already uses.
struct BackupDocumentExportPicker: UIViewControllerRepresentable {
    let url: URL
    var onDismiss: (() -> Void)?

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forExporting: [url])
        picker.shouldShowFileExtensions = true
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onDismiss: onDismiss) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let onDismiss: (() -> Void)?
        init(onDismiss: (() -> Void)?) { self.onDismiss = onDismiss }

        func documentPicker(_ controller: UIDocumentPickerViewController,
                            didPickDocumentsAt urls: [URL]) {
            onDismiss?()
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            onDismiss?()
        }
    }
}
