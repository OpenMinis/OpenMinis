import Foundation
import SwiftUI

private let logger = AppLogger(category: "Backup")

/// Routes a `.minisbak` file opened from outside the app (Files, AirDrop, a
/// share sheet) into the restore flow.
///
/// Without this, `ExternalFileImporter.canIngest` accepts ANY file URL and a
/// backup package would be staged as a chat attachment — the user would open
/// their backup and get an unusable blob attached to a conversation.
///
/// The incoming URL is security-scoped and only valid inside a
/// start/stopAccessing pair, so the file is copied into the app's own tmp/
/// immediately and everything downstream reads that copy.
@MainActor
final class BackupOpenRouter: ObservableObject {
    static let shared = BackupOpenRouter()

    /// A package waiting to be restored. Wrapped rather than a bare `URL`
    /// because SwiftUI's `.sheet(item:)` needs Identifiable, and adding a
    /// global `URL: Identifiable` conformance would affect every other file.
    struct PendingPackage: Identifiable {
        let id = UUID()
        let url: URL
    }

    /// Set when a package is waiting to be restored; the Settings UI observes
    /// this and presents `BackupRestoreView` for it.
    @Published var pendingPackage: PendingPackage?

    private init() {}

    /// True when this URL is a backup package and should bypass the normal
    /// attachment ingest path.
    nonisolated static func isBackupPackage(_ url: URL) -> Bool {
        url.isFileURL
            && url.pathExtension.lowercased() == BackupFormat.fileExtension
    }

    /// Stage the package and flag it for the restore UI.
    @discardableResult
    static func handle(_ url: URL) -> Bool {
        guard isBackupPackage(url) else { return false }

        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        let dest = FileManager.default.temporaryDirectory
            .appendingPathComponent("opened-\(UUID().uuidString).\(BackupFormat.fileExtension)")
        let fm = FileManager.default
        do {
            // Coordinated read so a not-yet-downloaded iCloud/provider file is
            // materialised before the copy — the same reason
            // ExternalFileImporter coordinates its own ingest.
            var coordErr: NSError?
            var copyErr: Error?
            NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordErr) { readURL in
                do {
                    if fm.fileExists(atPath: dest.path) { try fm.removeItem(at: dest) }
                    try fm.copyItem(at: readURL, to: dest)
                } catch { copyErr = error }
            }
            if let coordErr { throw coordErr }
            if let copyErr { throw copyErr }
        } catch {
            logger.error("[Restore] couldn't stage opened package \(url.lastPathComponent): \(error.localizedDescription)")
            return false
        }

        logger.info("[Restore] package opened from outside the app: \(url.lastPathComponent)")
        Task { @MainActor in
            // Clear whatever is already on screen BEFORE publishing the
            // package.
            //
            // The restore sheet is mounted at the WindowGroup root, and iOS
            // will not present a second sheet from the same root while one is
            // already up. Settings is itself a `.sheet`, so a user who opened a
            // .minisbak while sitting in Settings got nothing at all — the
            // package was staged, `pendingPackage` was set, and the sheet
            // silently never appeared. That is the primary device-migration
            // entry point failing in the one place a user is most likely to be
            // when they go looking for backup.
            //
            // `.dismissAllImmersivePresentations` is the established mechanism
            // for this (ShareCoordinator.raisePendingShare does the same dance
            // for incoming shares); the delay gives SwiftUI a beat to tear the
            // old sheet down, since setting the item in the same runloop turn
            // races the dismissal and loses.
            NotificationCenter.default.post(name: .dismissAllImmersivePresentations,
                                            object: nil)
            try? await Task.sleep(nanoseconds: 350_000_000)
            BackupOpenRouter.shared.pendingPackage = .init(url: dest)
        }
        return true
    }
}
