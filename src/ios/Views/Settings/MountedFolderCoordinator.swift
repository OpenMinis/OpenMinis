//
//  MountedFolderCoordinator.swift
//  MinisApp
//
//  Thin wrapper around NSFileCoordinator + FileManager for user-mounted
//  external folders (see MountedFoldersManager). When a path descends into
//  a mounted folder that's backed by another app's File Provider (e.g. an
//  Obsidian vault in iCloud Drive), every write MUST be coordinated or the
//  provider can lose or corrupt data.
//
//  For paths that are NOT under a mount, this helper falls through to plain
//  FileManager calls, so existing FileBrowserView behaviour is preserved.
//

import Foundation

private let coordLog = AppLogger(category: "MountedFolderCoordinator")

enum MountedFolderCoordinator {
    /// iCloud download wait timeout for placeholder files. Short because
    /// `ensureDownloaded` blocks the calling thread via polling — keeping it
    /// small avoids pinning the main thread or coordinator actor for long.
    private static let downloadTimeout: TimeInterval = 8

    // MARK: - Mount detection

    /// Returns true if `url` resolves to a location under any active mount.
    /// Checks both the fakefs symlink path (/var/minis/mounts/<name>/...) and
    /// the underlying resolved host path.
    static func isUnderMount(_ url: URL) -> Bool {
        mountRoot(for: url) != nil
    }

    /// Returns true if `url` is under any active mount that was probed as
    /// read-only at add time. Used by FileBrowserView and LLM file tools to
    /// pre-reject writes with a friendly message instead of failing deep in
    /// the call stack with EACCES.
    static func isUnderReadOnlyMount(_ url: URL) -> Bool {
        let entries = mountSnapshot()
        let candidates = comparablePaths(for: url)
        for entry in entries where entry.readOnly {
            if candidates.contains(where: { pathIsAtOrUnder($0, root: entry.path) }) {
                return true
            }
        }
        return false
    }

    /// Candidate path spellings for `url` to compare against mount roots.
    ///
    /// Always includes the standardized path (pure string work). Adds the
    /// symlink-resolved path **only off the main thread**:
    /// `resolvingSymlinksInPath()` on a path inside a network- or
    /// FileProvider-backed mount is a `getattrlist(2)` that traps into a
    /// synchronous XPC call to the owning provider, and on a slow or
    /// unreachable volume it parks the calling thread. On the main thread that
    /// means a 10s scene-update watchdog SIGKILL, so there we accept the
    /// slightly weaker string-only comparison. Mount roots on the other side of
    /// the comparison are already canonical (resolved once at activation), and
    /// callers reach these helpers with paths built from those same roots, so
    /// the string form matches in practice.
    private static func comparablePaths(for url: URL) -> [String] {
        let standardized = url.standardized.path
        guard !Thread.isMainThread else { return [standardized] }
        let resolved = url.resolvingSymlinksInPath().standardized.path
        return resolved == standardized ? [standardized] : [standardized, resolved]
    }

    /// True if `path` is the mount root itself or lives beneath it, with the
    /// prefix test aligned to a directory boundary.
    private static func pathIsAtOrUnder(_ path: String, root: String) -> Bool {
        if path == root { return true }
        let prefix = root.hasSuffix("/") ? root : root + "/"
        return path.hasPrefix(prefix)
    }

    /// Fetch the mount snapshot, hopping to the main actor only when needed.
    private static func mountSnapshot() -> [(path: String, readOnly: Bool)] {
        if Thread.isMainThread {
            return MainActor.assumeIsolated { Self.readOnlyMountSnapshot() }
        }
        var snapshot: [(path: String, readOnly: Bool)] = []
        DispatchQueue.main.sync { snapshot = Self.readOnlyMountSnapshot() }
        return snapshot
    }

    /// Returns true if `linuxPath` (a `/var/minis/mounts/<name>/...` path from
    /// the iSH namespace) is under a mount entry that is effectively read-only.
    /// Compares by mount name under `/var/minis/mounts/`, so it's immune to the
    /// firmlink / symlink / case-sensitivity edge cases that `isUnderReadOnlyMount`
    /// (which operates on resolved host URLs) has to worry about.
    static func isLinuxPathUnderReadOnlyMount(_ linuxPath: String) -> Bool {
        let prefix = AIChatViewModel.minisMountsLinuxDir + "/"
        guard linuxPath.hasPrefix(prefix) else { return false }
        let rest = linuxPath.dropFirst(prefix.count)
        let name = rest.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: true)
            .first.map(String.init) ?? String(rest)
        guard !name.isEmpty else { return false }

        let entries: [MountedFolderEntry]
        if Thread.isMainThread {
            entries = MainActor.assumeIsolated { MountedFoldersManager.shared.entries }
        } else {
            var snapshot: [MountedFolderEntry] = []
            DispatchQueue.main.sync { snapshot = MountedFoldersManager.shared.entries }
            entries = snapshot
        }
        guard let entry = entries.first(where: { $0.name == name }) else { return false }
        return !entry.effectiveWritable
    }

    @MainActor
    private static func readOnlyMountSnapshot() -> [(path: String, readOnly: Bool)] {
        let manager = MountedFoldersManager.shared
        return manager.entries.compactMap { entry in
            // Use the canonical path memoized at activation rather than
            // resolving here — this runs on the main actor, where
            // `resolvingSymlinksInPath()` on a mount root can block on the
            // backing volume (see MountedFoldersManager.canonicalMountPaths).
            guard let path = manager.canonicalPath(for: entry.id) else { return nil }
            // A mount is effectively read-only if either the OS doesn't allow
            // writes OR the user has turned off allow-write in Settings.
            return (path: path, readOnly: !entry.effectiveWritable)
        }
    }

    /// Returns the resolved host root URL of the mount containing `url`, or nil.
    static func mountRoot(for url: URL) -> URL? {
        let candidates = comparablePaths(for: url)

        // Gather active mounts as (url, canonicalPath) pairs. The canonical
        // path is memoized at activation, so nothing here resolves symlinks —
        // doing that per root on the main thread is what blocked on
        // unreachable network / FileProvider volumes.
        // We access MainActor state via assumeIsolated when on main, otherwise
        // fall back to a snapshot path — MountedFoldersManager entries rarely
        // change and resolution here only needs a point-in-time check.
        let roots: [(url: URL, path: String)]
        if Thread.isMainThread {
            roots = MainActor.assumeIsolated { Self.activeMountRoots() }
        } else {
            // Dispatch sync to main is safe here because this runs on a
            // file-browser worker queue. Keep timeout small.
            var snapshot: [(url: URL, path: String)] = []
            DispatchQueue.main.sync { snapshot = Self.activeMountRoots() }
            roots = snapshot
        }

        for root in roots {
            if candidates.contains(where: { pathIsAtOrUnder($0, root: root.path) }) {
                return root.url
            }
        }
        return nil
    }

    @MainActor
    private static func activeMountRoots() -> [(url: URL, path: String)] {
        let manager = MountedFoldersManager.shared
        return manager.entries.compactMap { entry in
            guard let url = manager.resolvedURL(for: entry.id),
                  let path = manager.canonicalPath(for: entry.id) else { return nil }
            return (url: url, path: path)
        }
    }

    // MARK: - iCloud download

    /// Intentionally a no-op.
    ///
    /// Earlier versions of this helper enumerated a directory's children and
    /// fired `startDownloadingUbiquitousItem` on every placeholder. That turned
    /// out to be a bandwidth/storage hazard on large iCloud-backed mounts
    /// (an Obsidian vault `attachments/` folder with thousands of images would
    /// kick off thousands of simultaneous downloads when the user merely
    /// navigated to the directory).
    ///
    /// The correct behaviour is **on-demand** download: `ensureDownloaded` is
    /// already called just-in-time when the user opens or reads an individual
    /// file. Directory listings show placeholder files at size 0 — that's fine.
    static func prefetchIfNeeded(at directory: URL) {
        // no-op
    }

    /// Ensure a single ubiquitous item is fully downloaded. No-op for local
    /// files or items already in `.current` state.
    ///
    /// If the item is a placeholder, fires `startDownloadingUbiquitousItem` and
    /// then polls up to `downloadTimeout` seconds for completion.
    ///
    /// **Important**: this polls with `Thread.sleep`, so it must NOT be called
    /// from the main thread. `FileBrowserView` already dispatches reads/writes
    /// through background queues; if a future caller needs main-thread safety
    /// they should hop to a background queue first.
    static func ensureDownloaded(_ url: URL) throws {
        let keys: Set<URLResourceKey> = [
            .isUbiquitousItemKey,
            .ubiquitousItemDownloadingStatusKey,
        ]
        guard let values = try? url.resourceValues(forKeys: keys),
              values.isUbiquitousItem == true else { return }
        if values.ubiquitousItemDownloadingStatus == .current { return }

        try FileManager.default.startDownloadingUbiquitousItem(at: url)

        // Never block the main thread waiting for iCloud — fire the download
        // request and return immediately; the caller will see a short read or
        // can retry later.
        if Thread.isMainThread {
            return
        }

        let deadline = Date().addingTimeInterval(downloadTimeout)
        while Date() < deadline {
            let v = try url.resourceValues(forKeys: keys)
            if v.ubiquitousItemDownloadingStatus == .current { return }
            Thread.sleep(forTimeInterval: 0.2)
        }
        throw NSError(
            domain: "MountedFolderCoordinator",
            code: -1,
            userInfo: [NSLocalizedDescriptionKey: AppLocalized("Timed out downloading file from iCloud.")]
        )
    }

    // MARK: - Read-only enforcement

    /// Error thrown when a write operation is attempted on a read-only mount.
    struct ReadOnlyMountError: LocalizedError {
        let url: URL
        var errorDescription: String? {
            String(
                format: AppLocalized("This location is inside a read-only mounted folder and cannot be modified: %@"),
                url.lastPathComponent
            )
        }
    }

    /// Throws `ReadOnlyMountError` if the URL lives under a read-only mount.
    static func requireWritable(_ url: URL) throws {
        if isUnderReadOnlyMount(url) {
            throw ReadOnlyMountError(url: url)
        }
    }

    // MARK: - Coordinated file operations

    /// Delete an item, using NSFileCoordinator if the path is inside a mount.
    static func remove(at url: URL) throws {
        try requireWritable(url)
        if isUnderMount(url) {
            var coordError: NSError?
            var innerError: Error?
            NSFileCoordinator().coordinate(writingItemAt: url,
                                           options: .forDeleting,
                                           error: &coordError) { target in
                do { try FileManager.default.removeItem(at: target) }
                catch { innerError = error }
            }
            if let coordError { throw coordError }
            if let innerError { throw innerError }
            return
        }
        try FileManager.default.removeItem(at: url)
    }

    /// Move an item, coordinating both src and dst when either is under a mount.
    static func move(from src: URL, to dst: URL) throws {
        // A move both removes the src and creates at dst, so both must be
        // writable if they live under a mount.
        try requireWritable(src)
        try requireWritable(dst)
        let srcMounted = isUnderMount(src)
        let dstMounted = isUnderMount(dst)
        if !srcMounted && !dstMounted {
            try FileManager.default.moveItem(at: src, to: dst)
            return
        }
        var coordError: NSError?
        var innerError: Error?
        let coordinator = NSFileCoordinator()
        coordinator.coordinate(
            writingItemAt: src, options: .forMoving,
            writingItemAt: dst, options: .forReplacing,
            error: &coordError
        ) { srcNew, dstNew in
            do { try FileManager.default.moveItem(at: srcNew, to: dstNew) }
            catch { innerError = error }
        }
        if let coordError { throw coordError }
        if let innerError { throw innerError }
    }

    /// Copy an item, coordinating both src and dst when either is under a mount.
    static func copy(from src: URL, to dst: URL) throws {
        // Reading from a read-only mount is fine; writing to one is not.
        try requireWritable(dst)
        let srcMounted = isUnderMount(src)
        let dstMounted = isUnderMount(dst)
        if !srcMounted && !dstMounted {
            try FileManager.default.copyItem(at: src, to: dst)
            return
        }
        // For reads under a mount, ensure the source is downloaded.
        if srcMounted { try ensureDownloaded(src) }

        var coordError: NSError?
        var innerError: Error?
        let coordinator = NSFileCoordinator()
        coordinator.coordinate(
            readingItemAt: src, options: [.withoutChanges],
            writingItemAt: dst, options: .forReplacing,
            error: &coordError
        ) { srcNew, dstNew in
            do { try FileManager.default.copyItem(at: srcNew, to: dstNew) }
            catch { innerError = error }
        }
        if let coordError { throw coordError }
        if let innerError { throw innerError }
    }

    /// Write `data` to `url`, coordinating if the target is under a mount.
    static func write(_ data: Data, to url: URL) throws {
        try requireWritable(url)
        if isUnderMount(url) {
            var coordError: NSError?
            var innerError: Error?
            NSFileCoordinator().coordinate(writingItemAt: url,
                                           options: .forReplacing,
                                           error: &coordError) { target in
                do { try data.write(to: target, options: .atomic) }
                catch { innerError = error }
            }
            if let coordError { throw coordError }
            if let innerError { throw innerError }
            return
        }
        try data.write(to: url, options: .atomic)
    }

    /// Read `Data` from `url`, ensuring iCloud download + coordinating if under a mount.
    static func read(at url: URL) throws -> Data {
        if isUnderMount(url) {
            try ensureDownloaded(url)
            var coordError: NSError?
            var innerError: Error?
            var out = Data()
            NSFileCoordinator().coordinate(readingItemAt: url,
                                           options: [.withoutChanges],
                                           error: &coordError) { target in
                do { out = try Data(contentsOf: target) }
                catch { innerError = error }
            }
            if let coordError { throw coordError }
            if let innerError { throw innerError }
            return out
        }
        return try Data(contentsOf: url)
    }
}
