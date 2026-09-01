import Foundation

private let logger = AppLogger(category: "Backup")

/// Walks a directory tree into the package: content into the blob store,
/// structure into `files.index.jsonl`.
///
/// Shared by every category that carries files — Chats' `<sid>/` directories,
/// Shared Files, and Skills' attached files — so the §3.4 size cap and the
/// dedup behaviour are identical everywhere by construction rather than by
/// three exporters agreeing to behave the same way.
struct BackupFileTreeExporter {
    let blobStore: BackupBlobStore
    let fileIndex: BackupFileIndexWriter

    /// Snapshot cut-off (review S7 / snapshot task). Files modified after this
    /// instant are not part of this backup's view of the data.
    ///
    /// **Deliberately excludes only, never includes.** The task flagged that
    /// mtime is not always trustworthy, and it is right: a file restored from
    /// another backup, synced down by a FileProvider, or copied with
    /// `copyItem` can carry an mtime unrelated to when this device saw it, and
    /// a clock change moves every comparison at once. So the rule here is
    /// asymmetric on purpose — a file is dropped ONLY when its mtime is
    /// clearly after the cut-off, and anything unreadable, missing, or
    /// ambiguous is INCLUDED. Wrongly including a slightly-too-new file costs
    /// a little package size; wrongly excluding one silently loses user data
    /// from their backup, which is the failure this whole feature exists to
    /// prevent.
    var snapshotAt: Date = .distantFuture

    struct Result {
        var filesIncluded = 0
        var filesSkipped = 0
        var bytesIncluded: Int64 = 0
        var directories = 0
        /// [review S9] Subset of `filesSkipped` that was excluded because it
        /// lives in iCloud and isn't on this device. Tracked separately because
        /// the user-facing message differs from the §3.4 size cap: "too big to
        /// include" is a setting they chose, "not downloaded" is fixable by
        /// downloading the files and running the backup again.
        var filesNotDownloaded = 0
        /// Files excluded because they were modified after the snapshot
        /// cut-off. NOT a gap in the backup — they are simply outside this
        /// backup's point in time, so they get no tombstone.
        var filesAfterSnapshot = 0
    }

    /// True for anything that is itself a backup artifact — a delivered
    /// `.minisbak` package, or the directory they are delivered into.
    ///
    /// Matched on the leading path component as well as the extension so a
    /// whole `Backups/` tree is skipped in one step rather than file by file.
    private func isBackupArtifact(relativePath rel: String) -> Bool {
        if rel.hasSuffix("." + BackupFormat.fileExtension) { return true }
        let first = rel.split(separator: "/").first.map(String.init)
        return first == BackupDelivery.backupsDirectoryName
    }

    /// [review S9] Outcome of trying to materialise an iCloud/FileProvider
    /// placeholder before packaging it.
    enum DownloadOutcome {
        /// Local file, or already downloaded — package it normally.
        case ready
        /// Still a placeholder. Its bytes are NOT on this device, so packaging
        /// it would store an empty file.
        case notDownloaded(size: Int64)
    }

    /// Ensure a file's real bytes are present locally, or report that they are
    /// not.
    ///
    /// Strategy (the task left the choice open): **try to download, then
    /// degrade to a tombstone.** Requesting the download is what makes an
    /// unattended export of a mostly-offloaded library actually complete over
    /// time, but waiting on iCloud is unbounded in the worst case and a backup
    /// must not hang forever on one file — so the wait is capped and anything
    /// still absent becomes an explicit tombstone rather than a fake empty file.
    ///
    /// `MountedFolderCoordinator.ensureDownloaded` is reused deliberately: it
    /// already encapsulates the start-download-then-poll dance and its own
    /// timeout, and duplicating that logic here would let the two drift.
    /// It polls with `Thread.sleep`, which is why this must stay off the main
    /// thread — `BackupExporter` is an actor, so it already is.
    private func ensureLocallyAvailable(_ url: URL) -> DownloadOutcome {
        let keys: Set<URLResourceKey> = [.isUbiquitousItemKey,
                                         .ubiquitousItemDownloadingStatusKey,
                                         .fileSizeKey]
        guard let values = try? url.resourceValues(forKeys: keys),
              values.isUbiquitousItem == true else {
            // Not a ubiquitous item at all — the overwhelmingly common case,
            // and it costs one resourceValues call.
            return .ready
        }
        if values.ubiquitousItemDownloadingStatus == .current { return .ready }

        // A placeholder's `.fileSizeKey` is the LOGICAL size the provider
        // advertises, not 0 — that's what makes the tombstone informative.
        let logicalSize = values.fileSize.map(Int64.init) ?? 0

        do {
            try MountedFolderCoordinator.ensureDownloaded(url)
        } catch {
            logger.warning("[Backup] iCloud download failed/timed out for \(url.lastPathComponent): \(error.localizedDescription)")
            return .notDownloaded(size: logicalSize)
        }

        // ensureDownloaded returns without waiting when called on the main
        // thread, and a download can also complete partially, so re-read the
        // status rather than trusting a non-throwing return.
        let after = try? url.resourceValues(forKeys: keys)
        if after?.ubiquitousItemDownloadingStatus == .current { return .ready }
        return .notDownloaded(size: after?.fileSize.map(Int64.init) ?? logicalSize)
    }

    /// Export everything under `root`.
    ///
    /// - Parameters:
    ///   - root: host directory to walk. Missing is not an error — a session
    ///     with no attachments simply has no directory.
    ///   - logicalPrefix: package-relative prefix, e.g. `chats/<sid>`.
    ///   - category: recorded on every index line so a selective restore can
    ///     filter without re-deriving ownership from the path.
    @discardableResult
    func export(root: URL,
                logicalPrefix: String,
                category: BackupCategory,
                sessionId: String? = nil) throws -> Result {
        var result = Result()
        let fm = FileManager.default

        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: root.path, isDirectory: &isDir), isDir.boolValue else {
            return result
        }

        // `.skipsHiddenFiles` matches the app's own session-tree scan
        // (ChatStore.scanAndMarkSessionFilesForResurrect) — dotfiles in these
        // directories are OS bookkeeping (.DS_Store, .Trash), not user content.
        guard let enumerator = fm.enumerator(
            at: root,
            includingPropertiesForKeys: [.isRegularFileKey, .isDirectoryKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        ) else { return result }

        for case let url as URL in enumerator {
            let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .isDirectoryKey])
            // Relative path via path-prefix strip: the enumerator yields
            // absolute URLs, and `url.relativePath` is not meaningful here.
            let rel = url.path.hasPrefix(root.path + "/")
                ? String(url.path.dropFirst(root.path.count + 1))
                : url.lastPathComponent
            let logicalPath = "\(logicalPrefix)/\(rel)"

            // Never let a backup package become part of the next backup.
            //
            // Delivered packages now live OUTSIDE `shared/` (see
            // BackupDelivery.backupsDirectory), so this cannot trigger today —
            // it is deliberate defence in depth. An earlier build delivered
            // into `shared/Backups/`, which meant the next Shared Files export
            // would scan the previous package in as user data and nest packages
            // inside packages without bound. Keeping the guard means a future
            // change that puts something back under a scanned root cannot
            // silently reintroduce that.
            if isBackupArtifact(relativePath: rel) {
                if values?.isDirectory == true { enumerator.skipDescendants() }
                logger.info("[Backup] skipping backup artifact inside \(category.rawValue): \(rel)")
                continue
            }

            if values?.isDirectory == true {
                // Recorded so empty directories survive the round trip —
                // nothing else in the package references them.
                fileIndex.write(.directory(path: logicalPath, category: category))
                result.directories += 1
                continue
            }
            guard values?.isRegularFile == true else { continue }

            // Snapshot cut-off. Only a file whose mtime is CLEARLY after the
            // cut-off is dropped; see `snapshotAt` for why this is asymmetric.
            if snapshotAt != .distantFuture,
               let mtime = try? url.resourceValues(forKeys: [.contentModificationDateKey])
                   .contentModificationDate,
               mtime > snapshotAt {
                result.filesAfterSnapshot += 1
                continue
            }

            // [review S9] Before hashing anything, make sure the bytes are
            // actually on this device. An undownloaded iCloud placeholder is a
            // readable, regular, zero-byte file — indistinguishable from a real
            // empty file to every check that used to run here.
            if case .notDownloaded(let logicalSize) = ensureLocallyAvailable(url) {
                logger.warning("[Backup] skip (not downloaded from iCloud) \(logicalPath) logicalSize=\(logicalSize)")
                fileIndex.write(.notDownloaded(path: logicalPath, size: logicalSize,
                                               category: category))
                result.filesSkipped += 1
                result.filesNotDownloaded += 1
                continue
            }

            // [T-backup-scan-jetsam] Brake between files when the system is
            // under memory pressure (the 02:01 kill kept scanning straight
            // through CRITICAL), and drain autoreleased allocations per file —
            // hashing + copying is where the bytes flow, and this loop visits
            // tens of thousands of blobs in one run.
            BackupMemoryGovernor.shared.throttleIfNeeded(context: "fileTree \(category.rawValue)")
            autoreleasepool { () -> Void in
            do {
                let outcome = try blobStore.addFile(at: url, logicalPath: logicalPath,
                                                    sessionId: sessionId)
                switch outcome {
                case .stored(let sha, let size), .duplicate(let sha, let size):
                    fileIndex.write(.file(path: logicalPath, size: size,
                                          sha256: sha, category: category))
                    result.filesIncluded += 1
                    result.bytesIncluded += size
                case .skippedTooLarge(let size):
                    // §3.4: tombstone, never a silent drop. The restore side
                    // renders this as an explicit "excluded, was N bytes"
                    // placeholder instead of the file just being absent.
                    fileIndex.write(.sizeSkipped(path: logicalPath, size: size,
                                                 category: category))
                    result.filesSkipped += 1
                }
            } catch {
                // One unreadable file must not abort a multi-GB backup. Record
                // it as a tombstone so the gap is still visible, and continue.
                logger.warning("[Backup] unreadable file \(logicalPath): \(error.localizedDescription)")
                let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).flatMap { Int64($0) } ?? 0
                fileIndex.write(BackupFileIndexEntry(
                    path: logicalPath, size: size, sha256: nil,
                    category: category.rawValue, skipped: "unreadable", isDirectory: nil))
                result.filesSkipped += 1
            }
            }
        }
        return result
    }
}

/// Buffered writer for `files.index.jsonl`.
///
/// Separate from `BackupJSONLWriter` because these lines are bare entries, not
/// `t`/`v` envelopes — the file is a package-level index rather than a category
/// data stream, and the importer reads it before it knows which categories the
/// user picked.
final class BackupFileIndexWriter {
    private let url: URL
    private var handle: FileHandle?
    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return e
    }()
    private(set) var count = 0

    init(url: URL) {
        self.url = url
    }

    func write(_ entry: BackupFileIndexEntry) {
        do {
            if handle == nil {
                try FileManager.default.createDirectory(
                    at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
                // Resume-safe: an index left by an interrupted attempt is
                // APPENDED to, not truncated. `createFile` on an existing path
                // would discard every entry the previous run had already
                // written, so the categories skipped as "already done" would
                // have no rows in files.index and their content would be
                // unreachable at restore — a package that looks complete and
                // silently isn't.
                if !FileManager.default.fileExists(atPath: url.path) {
                    FileManager.default.createFile(atPath: url.path, contents: nil)
                }
                handle = try FileHandle(forWritingTo: url)
                try handle?.seekToEnd()
            }
            var line = try encoder.encode(entry)
            line.append(0x0A)
            try handle?.write(contentsOf: line)
            count += 1
        } catch {
            logger.error("[Backup] files.index write failed for \(entry.path): \(error.localizedDescription)")
        }
    }

    func close() {
        try? handle?.close()
        handle = nil
    }
}
