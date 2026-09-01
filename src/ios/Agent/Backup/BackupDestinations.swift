import Foundation

private let logger = AppLogger(category: "Backup")

/// The mounted folders a finished backup is delivered into
/// (docs/backup-restore-design.md §6.2 path 2).
///
/// Deliberately stores nothing but a set of `MountedFolderEntry` ids. The
/// bookmark, the security scope, the resolved URL and the writability probe all
/// stay owned by `MountedFoldersManager` — duplicating any of that here would
/// mean two places that can disagree about whether a folder is usable, and the
/// stale one would be the one deciding where a backup gets written.
///
/// This is also why network destinations need no networking code: the user
/// mounts an SMB / AFP / WebDAV / cloud folder in Files, authorises it once in
/// Minis, and iOS's FileProvider handles the protocol. From here it is an
/// ordinary directory.
@MainActor
enum BackupDestinations {

    private static let defaultsKey = "backup.destinations.mountIds"

    /// Ids of the mounted folders selected as backup destinations.
    ///
    /// Reads filter out ids that no longer exist, so a folder the user removed
    /// in Settings silently stops being a destination instead of failing every
    /// export forever.
    static var selectedIds: [UUID] {
        get {
            let raw = UserDefaults.standard.stringArray(forKey: defaultsKey) ?? []
            let known = Set(MountedFoldersManager.shared.entries.map(\.id))
            return raw.compactMap(UUID.init(uuidString:)).filter(known.contains)
        }
        set {
            UserDefaults.standard.set(newValue.map(\.uuidString), forKey: defaultsKey)
        }
    }

    static func isSelected(_ id: UUID) -> Bool { selectedIds.contains(id) }

    static func toggle(_ id: UUID, on: Bool) {
        var ids = selectedIds
        ids.removeAll { $0 == id }
        if on { ids.append(id) }
        selectedIds = ids
    }

    /// Register a folder the user picked in the Files browser as a destination,
    /// and select it.
    ///
    /// The Files picker IS the server browser: an SMB / WebDAV / cloud account
    /// connected in Files appears there as a location the user can drill into,
    /// so "choose a network drive, navigate to a directory, save here" is the
    /// system picker's own flow. Reimplementing it would mean writing SMB and
    /// WebDAV clients to duplicate something iOS already does — and something
    /// the user has already authenticated.
    ///
    /// Reuses `MountedFoldersManager.add`, so a destination added here is an
    /// ordinary Mounted Folder: it appears in Settings ▸ Mount External
    /// Folders, survives relaunch via the same security-scoped bookmark, and is
    /// governed by the same writability rules. No parallel storage.
    @discardableResult
    static func addDestination(pickedURL: URL) throws -> MountedFolderEntry {
        let entry = try MountedFoldersManager.shared.add(
            pickedURL: pickedURL,
            customName: suggestedName(for: pickedURL),
            userAllowWrite: true)
        register(entry.id)
        toggle(entry.id, on: true)
        logger.info("[Backup] destination added: '\(entry.name)'")
        return entry
    }

    /// A mount name derived from the folder, de-duplicated against existing
    /// mounts — `add` rejects a name that is already taken, and a user picking
    /// "Documents" on two different servers should not hit an error they can't
    /// interpret.
    private static func suggestedName(for url: URL) -> String {
        let raw = url.lastPathComponent
            .trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: "/", with: "-")
        let base = raw.isEmpty ? "backup" : raw
        guard !MountedFoldersManager.shared.isNameAvailable(base) else { return base }
        for n in 2...99 {
            let candidate = "\(base)-\(n)"
            if MountedFoldersManager.shared.isNameAvailable(candidate) { return candidate }
        }
        return "\(base)-\(UUID().uuidString.prefix(4))"
    }

    /// Ids of mounts the user explicitly added HERE as backup destinations.
    ///
    /// [2026-08-16] Separate from the mount list on purpose. Mounted folders
    /// exist so the AGENT can read and write a user's Obsidian vault, Scriptable
    /// scripts and so on — they are working directories, not places to drop
    /// backup archives. Listing them all as destinations put four unrelated
    /// folders in front of the user and invited them to scatter `.minisbak`
    /// files through directories they use for something else.
    ///
    /// So this section shows only what was added through "Add Destination…".
    /// A mount added for the agent never appears here; a folder added here is
    /// still a real mount underneath (same bookmark, same lifecycle), it is
    /// just also tagged as somewhere backups may go.
    private static let registeredKey = "backup.destinations.registeredIds"

    static var registeredIds: [UUID] {
        get {
            let raw = UserDefaults.standard.stringArray(forKey: registeredKey) ?? []
            let known = Set(MountedFoldersManager.shared.entries.map(\.id))
            return raw.compactMap(UUID.init(uuidString:)).filter(known.contains)
        }
        set {
            UserDefaults.standard.set(newValue.map(\.uuidString), forKey: registeredKey)
        }
    }

    private static func register(_ id: UUID) {
        var ids = registeredIds
        guard !ids.contains(id) else { return }
        ids.append(id)
        registeredIds = ids
    }

    /// Forget a destination: it stops being offered here and is deselected.
    ///
    /// Deliberately does NOT remove the underlying mount — the user may have
    /// added the same folder for the agent, and deleting that from a backup
    /// screen would be a surprise. Removing the mount itself stays in
    /// Settings ▸ Mount External Folders.
    static func forget(_ id: UUID) {
        registeredIds = registeredIds.filter { $0 != id }
        toggle(id, on: false)
    }

    /// Destinations to show: only what was added here, and only while it is
    /// still writable.
    ///
    /// `effectiveWritable` covers both halves of the existing model: the OS
    /// probe (`isWritable`) and the user's own "don't let Minis write here"
    /// switch (`userAllowWrite`). Offering a read-only mount as a destination
    /// would only produce a failure at the end of a long export.
    static var eligibleFolders: [MountedFolderEntry] {
        let registered = Set(registeredIds)
        return MountedFoldersManager.shared.entries
            .filter { registered.contains($0.id) && $0.effectiveWritable }
    }

    /// Selected destinations, in the order the user's mounts are listed.
    static var selectedFolders: [MountedFolderEntry] {
        // Intersected with `registeredIds`, not just `selectedIds`. A build
        // before 2026-08-16 offered every mount here, so an upgrading user can
        // have an AGENT mount (an Obsidian vault, a Scriptable folder) sitting
        // in the selection. Delivering a backup archive into it would be a
        // write the user never asked for, into a directory they use for
        // something else — so the registration list is the authority.
        let ids = Set(selectedIds).intersection(registeredIds)
        return MountedFoldersManager.shared.entries.filter { ids.contains($0.id) }
    }

    /// Outcome of delivering one package to one destination.
    struct DeliveryResult: Identifiable {
        let id: UUID
        let folderName: String
        let destination: URL?
        let error: String?
        /// [T-backup-destination-detail] Backend tag (`smb`, `webdav`, …) and
        /// the remote folder, carried through so the history row can show
        /// WHERE the package landed. Nil for a mounted-folder destination,
        /// which has no remote of either kind.
        var kind: String? = nil
        var remotePath: String? = nil
        var succeeded: Bool { destination != nil }
    }

    /// Deliver `packageURL` to every selected destination.
    ///
    /// Each destination is independent: a failure is recorded and the loop
    /// continues. A backup that reached 2 of 3 places is a meaningfully
    /// different outcome from one that reached none, and the user has to be
    /// able to tell them apart — an unreachable SMB share is the expected
    /// steady state for a laptop that is asleep, not an error worth aborting
    /// the whole delivery over.
    ///
    /// The local copy in Minis ▸ Backups is written before this runs and is
    /// never affected by what happens here.
    /// [review I1] Async, and the copy itself runs OFF the main actor.
    ///
    /// The previous version was a synchronous function on this `@MainActor`
    /// type, so `MountedFolderCoordinator.copy` — an NSFileCoordinator copy of
    /// the whole package into a FileProvider-backed folder — executed on the
    /// main thread. For an SMB/WebDAV destination that is a network transfer of
    /// potentially hundreds of MB with no timeout, which froze the UI and
    /// risked a `0x8BADF00D` scene-update watchdog kill. MountedFolderCoordinator
    /// carries a comment warning about exactly this hazard for FileProvider
    /// paths, and delivery walked straight into it.
    ///
    /// Only the mount *lookup* genuinely needs the main actor
    /// (`MountedFoldersManager` is `@MainActor`), so the resolved roots are
    /// captured here and the transfer happens on a detached executor.
    static func deliver(packageURL: URL, backupId: String = "") async -> [DeliveryResult] {
        let folders = selectedFolders
        guard !folders.isEmpty || !RcloneRemoteStore.enabledRemotes.isEmpty else { return [] }

        // Resolve every root while still on the main actor, then hand plain
        // URLs to the background copy.
        let manager = MountedFoldersManager.shared
        let targets: [(id: UUID, name: String, root: URL?)] = folders.map {
            ($0.id, $0.name, manager.resolvedURL(for: $0.id))
        }

        let mountResults = await Task.detached(priority: .utility) { () -> [DeliveryResult] in
            var results: [DeliveryResult] = []
            for target in targets {
                guard let root = target.root else {
                    results.append(.init(id: target.id, folderName: target.name,
                                         destination: nil,
                                         error: AppLocalized("This folder isn't available right now.")))
                    continue
                }
                do {
                    let dest = try BackupDelivery.copyToMountedFolder(packageURL, into: root)
                    logger.info("[Backup] delivered to '\(target.name)': \(dest.lastPathComponent)")
                    results.append(.init(id: target.id, folderName: target.name,
                                         destination: dest, error: nil))
                } catch {
                    logger.error("[Backup] delivery to '\(target.name)' failed: \(error.localizedDescription)")
                    results.append(.init(id: target.id, folderName: target.name,
                                         destination: nil, error: error.localizedDescription))
                }
            }
            return results
        }.value

        // rclone remotes (SMB / WebDAV / S3 / …) — a second, independent
        // delivery path. Kept separate from the mounted-folder loop rather
        // than merged: one copies into a directory iOS already has mounted,
        // the other speaks a protocol. Failures stay isolated per destination
        // either way, and the local copy is untouched by both.
        let remoteResults = await deliverToRemotes(packageURL: packageURL,
                                                   backupId: backupId)
        return mountResults + remoteResults
    }

    /// Upload to every configured rclone remote, chunked and resumable.
    private static func deliverToRemotes(packageURL: URL,
                                         backupId: String) async -> [DeliveryResult] {
        // Disabled remotes stay configured but are skipped.
        let remotes = RcloneRemoteStore.enabledRemotes
        guard !remotes.isEmpty else { return [] }
        RcloneRemoteStore.syncToRclone()

        return await Task.detached(priority: .utility) { () -> [DeliveryResult] in
            var out: [DeliveryResult] = []
            for r in remotes {
                do {
                    // Report bytes as they go. RcloneTransfer already polls
                    // core/stats for this; nothing was listening, so the UI sat
                    // on "Copying to destinations…" for the whole upload — the
                    // longest phase of a backup and the one most likely to
                    // stall.
                    try RcloneTransfer.upload(packageURL: packageURL,
                                              remote: r, backupId: backupId,
                                              progress: { p in
                        Task { @MainActor in
                            BackupTransferStatus.shared.update(name: r.name,
                                                               bytesSent: p.bytesSent)
                        }
                    })
                    logger.info("[Backup] uploaded to remote '\(r.name)'")
                    await MainActor.run {
                        BackupTransferStatus.shared.finish(name: r.name, error: nil)
                    }
                    out.append(.init(id: UUID(), folderName: r.name,
                                     destination: URL(fileURLWithPath: "\(r.name):\(r.path)"),
                                     error: nil,
                                     kind: r.backend, remotePath: r.path))
                } catch {
                    logger.error("[Backup] upload to '\(r.name)' failed: \(error.localizedDescription)")
                    await MainActor.run {
                        BackupTransferStatus.shared.finish(name: r.name,
                                                           error: error.localizedDescription)
                    }
                    out.append(.init(id: UUID(), folderName: r.name,
                                     destination: nil, error: error.localizedDescription,
                                     kind: r.backend, remotePath: r.path))
                }
            }
            return out
        }.value
    }

    // MARK: - Reading packages back

    /// A `.minisbak` found inside a mounted destination.
    struct FoundPackage: Identifiable {
        var id: URL { url }
        let url: URL
        let folderName: String
        let size: Int64
        let modified: Date
    }

    /// List the packages sitting in the selected destinations, newest first.
    ///
    /// Used by the restore screen so a user restoring onto a new device can
    /// pick straight off their NAS instead of hunting through the Files
    /// picker.
    ///
    /// Non-throwing by design: an unreachable mount contributes nothing rather
    /// than failing the whole listing, because the common case for multiple
    /// destinations is that some are offline.
    ///
    /// - Parameter folderId: when given, list only that destination.
    ///   [T-restore-destinations-first] The restore screen now browses ONE
    ///   destination at a time, and scanning every mount to then discard all
    ///   but one would make an offline share slow down a folder that is
    ///   perfectly reachable.
    static func listPackages(folderId: UUID? = nil) -> [FoundPackage] {
        var found: [FoundPackage] = []
        let fm = FileManager.default

        let folders = folderId.map { id in selectedFolders.filter { $0.id == id } }
            ?? selectedFolders
        for folder in folders {
            guard let root = MountedFoldersManager.shared.resolvedURL(for: folder.id) else {
                logger.info("[Backup] destination '\(folder.name)' is not currently available")
                continue
            }
            let names = (try? fm.contentsOfDirectory(atPath: root.path)) ?? []
            for name in names where name.hasSuffix("." + BackupFormat.fileExtension) {
                let url = root.appendingPathComponent(name)
                let attrs = try? fm.attributesOfItem(atPath: url.path)
                found.append(.init(
                    url: url,
                    folderName: folder.name,
                    size: (attrs?[.size] as? Int64) ?? 0,
                    modified: (attrs?[.modificationDate] as? Date) ?? .distantPast))
            }
        }
        return found.sorted { $0.modified > $1.modified }
    }
}
