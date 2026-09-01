import Foundation
import UIKit

private let logger = AppLogger(category: "Backup")

/// Builds a `.minisbak` package (docs/backup-restore-design.md §2, §9 stage 1).
///
/// Current scope: export only, **unencrypted**. The Providers category now
/// carries credentials (§3.3, stage 3a) as base64 in `secrets.json` — base64 is
/// an encoding, not protection, so until §5's AES-GCM layer lands a package is
/// a plaintext copy of the user's API keys. `manifest.categories.providers`
/// reports `encrypted: false` alongside `includesCredentials: true` so that is
/// stated rather than implied, and a package can never claim to hold keys it
/// doesn't (or to protect keys it doesn't).
///
/// Shape: stage everything into a temp directory, then zip that directory once.
/// Staging (rather than streaming straight into an archive) is what the
/// existing iCloud backup and session-export paths already do, and it means a
/// failure part-way leaves no half-written package at the destination.
actor BackupExporter {

    struct Options {
        var categories: Set<BackupCategory> = Set(BackupCategory.backupable)
        /// §3.4 — nil means unlimited, which is the DEFAULT. See the design doc
        /// for why this differs from iCloud's finite default.
        var maxFileBytes: Int64?
        /// §3.3: credentials ship WITH the Providers category by default, so a
        /// restored provider actually works. Setting this false produces the
        /// "export copy without credentials" share package.
        var includeCredentials = true
        /// nil / empty = unencrypted package (§5 not applied). §3.3 requires a
        /// passphrase whenever credentials are included; the caller enforces
        /// that policy, and `export` refuses the unsafe combination outright.
        var passphrase: String?
        /// Data cut-off for this export (review S7 / snapshot task).
        ///
        /// Everything the package contains is the state as of this instant:
        /// rows updated after it, and files modified after it, are excluded.
        /// Without a cut-off the export is not a snapshot — a session written
        /// between the index pass and the blob copy lands in `files.index`
        /// while its content never gets stored, producing a package that
        /// references content it does not contain.
        ///
        /// Also what makes resume well-defined: the scope is pinned at the
        /// first attempt, so a resumed export cannot keep growing as the user
        /// keeps using the app.
        var snapshotAt: Date = Date()

        /// Whether this run may adopt an interrupted previous attempt.
        ///
        /// OFF for "Start Backup", which always means a NEW backup of the data
        /// as it is now. Resume used to be implicit: pressing Start silently
        /// adopted an older run, so the package that came out was a snapshot of
        /// whenever that run began — the export deliberately keeps the original
        /// cut-off — and anything written since was missing from a backup the
        /// user had just asked for. The staging was also invisible, so there
        /// was no way to tell which of the two had happened.
        ///
        /// Resuming is now a deliberate act from the interrupted run's own row
        /// in Backup History, where the run being continued is named.
        var allowResume = false

        init(categories: Set<BackupCategory> = Set(BackupCategory.backupable),
             maxFileBytes: Int64? = nil,
             includeCredentials: Bool = true,
             passphrase: String? = nil,
             snapshotAt: Date = Date(),
             allowResume: Bool = false) {
            self.categories = categories
            self.maxFileBytes = maxFileBytes
            self.includeCredentials = includeCredentials
            self.passphrase = passphrase
            self.snapshotAt = snapshotAt
            self.allowResume = allowResume
        }
    }

    struct Summary {
        var packageURL: URL
        var backupId: String
        /// Categories taken from an interrupted previous attempt instead of
        /// being re-exported. >0 means this run was a resume.
        var resumedCategories = 0
        var totalBytes: Int64
        var categories: [String: BackupManifest.CategoryStat]
        var skippedFiles: Int
        var skippedBytes: Int64
        var skippedPaths: [(path: String, size: Int64)]
        /// Session id → title, for turning a skipped file's path into
        /// something a user recognises. Captured during the export because a
        /// conversation deleted afterwards can no longer be looked up.
        var sessionTitles: [String: String] = [:]
        var duration: TimeInterval
    }

    private let fm = FileManager.default

    /// Session summaries gathered during the chats export, for `rescue.json`.
    /// Collected here rather than re-queried later because the export already
    /// holds every session and its message count — a second pass over the
    /// store would be pure duplicated work that could also disagree with what
    /// actually got written.
    private var rescueSessions: [BackupRescueIndex.SessionSummary] = []

    // MARK: - Entry point

    /// [review I3] Serialised process-wide: a second export (or an export
    /// racing a restore) is refused rather than run concurrently. Both would
    /// otherwise read the same stores while a restore mutates them.
    /// - Parameter onBackupId: called once, as soon as this run's id is known
    ///   (which is either a fresh UUID or the adopted interrupted run's). The
    ///   caller opens its history record BEFORE the export starts, so this is
    ///   what lets that record name the staging tree it owns — and therefore
    ///   what makes an interrupted run offerable for Resume later.
    /// - Parameter progressDetailed: like `progress`, but told whether a line
    ///   is TRANSIENT — a live "120/400 · about 40s left" that the next one
    ///   replaces rather than follows. Callers that keep a log want this;
    ///   callers that just show the latest status can use `progress` alone.
    func export(options: Options = Options(),
                onBackupId: (@Sendable (String) -> Void)? = nil,
                progress: (@Sendable (String) -> Void)? = nil,
                progressDetailed: (@Sendable (String, Bool) -> Void)? = nil) async throws -> Summary {
        try await BackupActivityLock.shared.withLock(.export) {
            try await exportBody(options: options, onBackupId: onBackupId,
                                 progress: progress, progressDetailed: progressDetailed)
        }
    }

    private func exportBody(options: Options,
                            onBackupId: (@Sendable (String) -> Void)? = nil,
                            progress: (@Sendable (String) -> Void)?,
                            progressDetailed: (@Sendable (String, Bool) -> Void)? = nil) async throws -> Summary {
        // §3.3 / §5.4: a package that carries credentials MUST be encrypted.
        // Enforced here rather than only in the UI, so no caller — debug RPC,
        // a future scheduler, anything — can produce a plaintext copy of the
        // user's API keys by omitting a passphrase.
        let wantsCredentials = options.includeCredentials
            && options.categories.contains(.providers)
        let hasPassphrase = !(options.passphrase ?? "").isEmpty
        guard !wantsCredentials || hasPassphrase else {
            throw BackupError.writeFailed(
                "Refusing to export credentials without a passphrase. Set one, or pass includeCredentials=false for a share copy.",
                underlying: nil)
        }

        let started = Date()

        // One emit point for every non-transient status line, so a caller that
        // supplied the detailed channel sees ALL of them (not just the chats
        // progress) and one that didn't still gets the plain text.
        let say: @Sendable (String) -> Void = { text in
            if let progressDetailed { progressDetailed(text, false) }
            else { progress?(text) }
        }

        // Resume, if a previous attempt was interrupted and is compatible with
        // what is being asked for now. `options.snapshotAt` is deliberately
        // REPLACED by the original cut-off: a resumed export must remain a
        // snapshot of the instant the user first pressed Create Backup, not
        // silently re-scope to now.
        var options = options
        let resumed = options.allowResume ? Self.resumableRun(for: options) : nil
        let backupId: String
        if let resumed {
            backupId = resumed.backupId
            options.snapshotAt = resumed.snapshotAt
            logger.info("[Backup] resuming interrupted export id=\(backupId) snapshotAt=\(resumed.snapshotAt)")
        } else {
            backupId = UUID().uuidString
            BackupExportJournal.begin(.init(
                backupId: backupId, startedAt: started, snapshotAt: options.snapshotAt,
                categories: options.categories.map(\.rawValue).sorted(),
                includeCredentials: options.includeCredentials,
                maxFileBytes: options.maxFileBytes,
                encrypted: hasPassphrase))
        }
        onBackupId?(backupId)

        // Bookend the run. Without this the log simply started mid-work with
        // "Exporting chats…", so there was no line saying what the backup set
        // out to do — and on a resume, no indication that some of it was
        // already done.
        let categoryCount = options.categories.count
        say(resumed == nil
                  ? AppLocalized("Starting backup — \(categoryCount) categor(ies)")
                  : AppLocalized("Resuming previous backup — \(categoryCount) categor(ies)"))

        // Staging lives in Application Support, NOT tmp/: iOS purges tmp on its
        // own schedule, and a resume needs this tree to survive to the next
        // launch (review I6).
        let staging = BackupExportJournal.stagingRoot(backupId: backupId)

        // Cleared only on a clean finish. An interrupted run deliberately
        // leaves its staging behind so the next attempt can resume it; the
        // launch sweeper removes anything no marker points at.
        //
        // CANCELLATION is an interruption, not a finish: `defer` runs on the
        // way out of a thrown CancellationError too, and wiping staging there
        // would throw away exactly the work a "pause" is supposed to preserve.
        // So the cleanup is conditional on having got to the end.
        var completed = false
        defer { if completed { BackupExportJournal.finish(backupId: backupId) } }

        try fm.createDirectory(at: staging, withIntermediateDirectories: true)
        let dataDir = staging.appendingPathComponent("data", isDirectory: true)
        try fm.createDirectory(at: dataDir, withIntermediateDirectories: true)

        let blobStore = BackupBlobStore(stagingRoot: staging, maxFileBytes: options.maxFileBytes)

        // Stream blobs straight into the package instead of copying them into
        // staging first — the difference between needing ~11.5 GB free for a
        // 3.84 GB backup and needing roughly the size of the package.
        //
        // NOT used for encrypted or resumed runs:
        //   - encryption still works by rewriting the staged tree in place
        //     after every category has run, so its members have to BE there.
        //     Moving encryption into the write path is the next step in
        //     docs/backup-streaming-package-design.md;
        //   - a resume adopts staging written by the previous attempt, which
        //     may predate this build.
        // Both keep the old path, which is unchanged and still correct.
        let streaming = !hasPassphrase && resumed == nil
        // [T-backup-package-name-parity] Same name shape as the non-streaming
        // path below. This filename is what `RcloneChunkedUpload` uploads
        // under (it takes `packageURL.lastPathComponent`), so it is the name
        // the user sees on their NAS — and the streaming path used to produce
        // a bare `backup-f9c34c.minisbak` with no date, leaving a folder of
        // packages that cannot be told apart. See `packageFileName` for the
        // current shape and why it leads with the device.
        //
        // Read once here and passed to both naming call sites: `UIDevice` is
        // main-actor bound while this is an actor, and the two sites must
        // agree on the name they produce.
        //
        // `displayName`, not `UIDevice.current.name`: since iOS 16 the latter
        // is just the model ("iPhone") without the user-assigned-device-name
        // entitlement, so it cannot tell two iPhones apart — which is the one
        // thing the device field in a filename exists to do. `displayName`
        // returns the name the user set in Settings when there is one
        // ([T-backup-device-name-setting]) and the automatic name otherwise.
        let deviceName = await MainActor.run { DeviceIdentity.displayName }
        let packageURLInProgress = fm.temporaryDirectory
            .appendingPathComponent(Self.packageFileName(
                backupId: backupId, at: options.snapshotAt, deviceName: deviceName,
                encrypted: hasPassphrase))
        var writer: BackupZipWriter?
        if streaming {
            writer = try BackupZipWriter(url: packageURLInProgress)
            blobStore.packageSink = writer
        }

        let fileIndexURL = staging.appendingPathComponent("files.index.jsonl")
        if resumed != nil {
            // Recover what the interrupted attempt already stored, so those
            // blobs are neither re-hashed nor re-copied — and so the final
            // blobs.index still describes every blob in the package.
            blobStore.rehydrate(fromFileIndexAt: fileIndexURL)
        }
        let fileIndex = BackupFileIndexWriter(url: fileIndexURL)
        let trees = BackupFileTreeExporter(blobStore: blobStore, fileIndex: fileIndex,
                                           snapshotAt: options.snapshotAt)

        var stats: [String: BackupManifest.CategoryStat] = [:]
        var resumedCategories = 0

        /// Run one category, unless a previous attempt already finished it.
        ///
        /// The completed category's stats come back from its `.done` marker, so
        /// the manifest is identical to what an uninterrupted run would have
        /// produced — resume must not change the package's contents, only how
        /// much work it takes to get there.
        /// - Parameter announces: true when the body emits its own progress
        ///   lines (chats does — it reports counts and an ETA), so `run` must
        ///   not also emit the bare label and leave two "starting" lines for
        ///   the same category.
        func run(_ category: BackupCategory, _ label: String,
                 announces: Bool = false,
                 _ body: () async throws -> BackupManifest.CategoryStat?) async throws {
            guard options.categories.contains(category) else { return }
            if resumed != nil,
               let done = BackupExportJournal.completedStat(category, in: staging) {
                stats[category.rawValue] = done
                resumedCategories += 1
                logger.info("[Backup] resume: skipping \(category.rawValue) — already complete")
                return
            }
            // Cancellation checkpoint. A category is the right granularity:
            // it is the unit the resume journal already tracks, so stopping
            // here leaves a state a later run can pick up rather than a
            // half-written one it has to discard.
            try Task.checkCancellation()
            if !announces { say(label) }
            guard let stat = try await body() else { return }
            stats[category.rawValue] = stat
            BackupExportJournal.markDone(category, stat: stat, in: staging)
        }

        try await run(.chats, AppLocalized("Exporting chats…"), announces: true) {
            try await exportChats(dataDir: dataDir, trees: trees,
                                  snapshotAt: options.snapshotAt,
                                  progress: { text, transient in
                // Prefer the detailed channel when the caller supplied one;
                // otherwise fall back to the plain one, which simply loses the
                // transient distinction.
                if let progressDetailed {
                    progressDetailed(text, transient)
                } else {
                    progress?(text)
                }
            })
        }
        try await run(.sharedFiles, AppLocalized("Exporting shared files…")) {
            try exportSharedFiles(trees: trees)
        }
        try await run(.skills, AppLocalized("Exporting skills…")) {
            try await exportSkills(dataDir: dataDir, trees: trees)
        }
        try await run(.memory, AppLocalized("Exporting memory…")) {
            try exportMemory(dataDir: dataDir)
        }
        try await run(.providers, AppLocalized("Exporting providers…")) {
            try await exportProviders(dataDir: dataDir, staging: staging,
                                      includeCredentials: options.includeCredentials)
        }
        try await run(.mcpServers, AppLocalized("Exporting MCP servers…")) {
            try exportMCPServers(dataDir: dataDir)
        }
        try await run(.environmentVariables,
                      AppLocalized("Exporting environment variables…")) {
            try await exportEnvironmentVariables(dataDir: dataDir)
        }
        // [2026-08-15] Not reached in normal use: `.voiceCorrections` is absent
        // from `BackupCategory.backupable`, so it never appears in
        // `options.categories` and `run` skips it. Kept wired up rather than
        // deleted — the exporter is the only way to produce a package
        // containing this section, which the importer still has to handle for
        // packages made before the exclusion. A caller that passes the category
        // explicitly (the debug RPC does) still gets it.
        try await run(.voiceCorrections, AppLocalized("Exporting voice corrections…")) {
            try await exportVoiceCorrections(dataDir: dataDir)
        }

        // Resume bookkeeping must not become package content — the archive
        // step zips this whole directory.
        BackupExportJournal.clearDoneMarkers(in: staging)

        // Rescue index (plaintext, never encrypted — see BackupRescueIndex).
        // Written before the manifest so the manifest's `integrity` map can
        // carry its hash: that makes tampering detectable without making the
        // rescue index REQUIRED for verification, which would be a deadlock —
        // a damaged rescue.json must never be able to fail a package that is
        // otherwise perfectly restorable.
        BackupRescueIndex.build(
            backupId: backupId,
            snapshotAt: options.snapshotAt,
            blobIndex: blobStore.blobIndex,
            fileIndexURL: fileIndexURL,
            sessions: rescueSessions
        ).writeIgnoringFailure(to: staging)

        // Blob index last — it is only complete once every category has offered
        // its files.
        fileIndex.close()
        try writeBlobIndex(blobStore.blobIndex, to: staging)

        // Encryption happens BEFORE the manifest is built, because §5.3 says
        // `integrity` records the CIPHERTEXT hash — that is what lets a reader
        // verify a package's completeness without holding the passphrase.
        var encryption: BackupManifest.Encryption?
        var keys: BackupCrypto.Keys?
        if let passphrase = options.passphrase, !passphrase.isEmpty {
            say("Encrypting…")
            let salt = BackupCrypto.makeSalt()
            let kdf = BackupCrypto.currentKDF(salt: salt)
            let derived = try BackupCrypto.deriveKeys(passphrase: passphrase, kdf: kdf)
            try encryptStagedMembers(in: staging, keys: derived)
            encryption = BackupManifest.Encryption(
                scheme: BackupCrypto.scheme, kdf: kdf, verifier: derived.verifier)
            keys = derived
            // Every category's bytes are now ciphertext, so the per-category
            // `encrypted` flag must say so rather than keeping stage 1's false.
            for key in stats.keys { stats[key]?.encrypted = true }
        }

        say("Writing manifest…")
        var manifest = try await buildManifest(
            backupId: backupId, categories: stats, blobStore: blobStore,
            staging: staging, encryption: encryption,
            snapshotAt: options.snapshotAt)
        if let keys {
            manifest.manifestMac = try BackupCrypto.manifestMAC(manifest, key: keys.macKey)
        }
        let manifestURL = staging.appendingPathComponent("manifest.json")
        try BackupJSONFile.write(manifest, to: manifestURL)
        if let keys {
            // Sidecar MAC over the file's exact bytes. The embedded
            // manifest_mac above authenticates a RE-ENCODING of the decoded
            // struct, which breaks the moment an older reader meets a newer
            // manifest field (unknown key dropped on decode → different bytes
            // → false "tampered"). Readers prefer this member; the embedded
            // one stays for packages/readers that predate it.
            let raw = try Data(contentsOf: manifestURL)
            try BackupCrypto.manifestMAC(rawBytes: raw, key: keys.macKey)
                .write(to: staging.appendingPathComponent("manifest.mac"),
                       atomically: true, encoding: .utf8)
        }
        // Byte-identical second copy. The manifest can only be written after
        // every blob (its integrity map hashes them), so it lands near the end
        // of the archive — the region a truncation destroys first. A duplicate
        // costs a few KB and gives a forward scan a second chance at the one
        // file that says what the package contained.
        try? BackupJSONFile.write(
            manifest, to: staging.appendingPathComponent(BackupRescueIndex.manifestCopyFilename))

        try Task.checkCancellation()
        say("Packaging…")
        let packageURL: URL
        if let writer {
            // The blobs are already in the package. Only the staged members —
            // the records, indexes and manifest, all small — remain to be
            // appended, so this is fast and needs no second copy of the bulk
            // data.
            try appendStagedMembers(from: staging, to: writer)
            try writer.close()
            packageURL = writer.url
        } else {
            packageURL = try await archive(staging: staging, backupId: backupId,
                                           at: options.snapshotAt, deviceName: deviceName,
                                           encrypted: hasPassphrase)
        }

        completed = true
        let total = (try? fm.attributesOfItem(atPath: packageURL.path)[.size] as? Int64) ?? 0
        logger.info("[Backup] done id=\(backupId) bytes=\(total) skipped=\(blobStore.skippedFiles)")

        // Closing line, so the log ends with an outcome rather than trailing
        // off after "Packaging…". Size and duration are what the user checks
        // ("did it actually get everything?"), and skipped files are called
        // out because a size-capped package has real gaps.
        let elapsed = Date().timeIntervalSince(started)
        let sizeText = ByteCountFormatter.string(fromByteCount: total, countStyle: .file)
        if blobStore.skippedFiles > 0 {
            say(AppLocalized("Export complete — \(sizeText) in \(BackupProgressReporter.durationText(elapsed)), \(blobStore.skippedFiles) file(s) excluded"))
        } else {
            say(AppLocalized("Export complete — \(sizeText) in \(BackupProgressReporter.durationText(elapsed))"))
        }

        var titles: [String: String] = [:]
        for s in rescueSessions where s.title?.isEmpty == false {
            titles[s.id] = s.title
        }

        return Summary(
            packageURL: packageURL, backupId: backupId,
            resumedCategories: resumedCategories, totalBytes: total,
            categories: stats, skippedFiles: blobStore.skippedFiles,
            skippedBytes: blobStore.skippedBytes, skippedPaths: blobStore.skippedPaths,
            sessionTitles: titles,
            duration: Date().timeIntervalSince(started))
    }


    /// A previous interrupted export that this request can pick up.
    ///
    /// Compatibility is intentionally strict: resuming into a package whose
    /// scope differs from what the user asked for would produce a backup that
    /// silently contains the wrong things. Category set, size cap, credential
    /// policy and encryption must all match, or the stale staging is discarded
    /// and a fresh export starts.
    ///
    /// Encrypted runs are resumable only when a passphrase is supplied again —
    /// the passphrase is never persisted, and silently producing a PLAINTEXT
    /// package from a run the user asked to encrypt would leak their API keys.
    private static func resumableRun(for options: Options) -> BackupExportJournal.Marker? {
        guard let marker = BackupExportJournal.interrupted() else { return nil }
        let staging = BackupExportJournal.stagingRoot(backupId: marker.backupId)
        guard FileManager.default.fileExists(atPath: staging.path) else { return nil }

        let wantsEncryption = !(options.passphrase ?? "").isEmpty
        guard marker.categories == options.categories.map(\.rawValue).sorted(),
              marker.maxFileBytes == options.maxFileBytes,
              marker.includeCredentials == options.includeCredentials,
              marker.encrypted == wantsEncryption
        else {
            logger.info("[Backup] previous export is not compatible with this request — starting fresh")
            BackupExportJournal.finish(backupId: marker.backupId)
            return nil
        }
        return marker
    }

    // MARK: - Chats

    /// Chats = messages **plus the whole `<sid>/` directory** (§3, second-round
    /// decision). No "was this file referenced by a message" test: the category
    /// covers everything the in-app file browser shows.
    private func exportChats(dataDir: URL, trees: BackupFileTreeExporter,
                             snapshotAt: Date,
                             progress: (@Sendable (String, Bool) -> Void)? = nil) async throws
        -> BackupManifest.CategoryStat {
        let store = ChatStore.shared
        let sessionIds = await store.allSessionIds()
        let tombstoned = Set(await store.tombstonedSessionIds())

        // Chats is the only category that runs long enough to need live
        // progress — the rest finish inside a second. Counted against the
        // sessions actually in scope, so the denominator matches what the
        // loop below will really walk.
        let inScope = sessionIds.filter { !tombstoned.contains($0) }
        var reporter = BackupProgressReporter(
            noun: AppLocalized("conversations"),
            total: inScope.count,
            emit: { text, transient in progress?(text, transient) })
        reporter.begin(AppLocalized("Exporting chats…"))

        let sessions = BackupJSONLWriter(directory: dataDir, baseName: "sessions")
        let messages = BackupJSONLWriter(directory: dataDir, baseName: "messages")
        let markers = BackupJSONLWriter(directory: dataDir, baseName: "compact_markers")
        let folders = BackupJSONLWriter(directory: dataDir, baseName: "folders")
        defer {
            try? sessions.close(); try? messages.close()
            try? markers.close(); try? folders.close()
        }

        var messageCount = 0
        var fileCount = 0
        var fileBytes: Int64 = 0

        for sid in sessionIds {
            // [T-backup-scan-jetsam] Per-session brake — a 2284-session run
            // must yield to system memory pressure instead of charging
            // through CRITICAL until jetsam kills the app.
            BackupMemoryGovernor.shared.throttleIfNeeded(context: "chats \(sid.prefix(8))")
            // Soft-deleted sessions are excluded: restoring them would
            // resurrect rows the user already deleted.
            guard !tombstoned.contains(sid) else { continue }
            // Counted before the snapshot filter below, so the number always
            // advances — a run whose tail is all newer-than-snapshot sessions
            // would otherwise appear to stall just before finishing.
            reporter.step()
            guard let session = await store.getSession(sid) else { continue }
            // Snapshot cut-off: a session created/updated after the user
            // pressed Create Backup is not part of this backup's view of the
            // data. Excluding the whole session (rather than trimming its
            // messages) keeps the package internally consistent — a session row
            // is never written without the messages that belong to it.
            guard session.updatedAt <= snapshotAt else {
                logger.info("[Backup] skip session newer than snapshot: \(sid)")
                continue
            }

            let extras = await store.getSessionExtras(sid)
            try sessions.write(BackupRecordEnvelope(t: "SessionV2", d: SessionRecord(
                session: session,
                memoryEnabled: extras.memoryEnabled,
                modelBinding: extras.modelBinding)))

            var sessionMessages = 0
            // [T-backup-scan-jetsam] Load, write, then drain per session: the
            // JSON-encoding of a long session's messages produces a burst of
            // autoreleased buffers that otherwise pile up across all 2284
            // sessions (the pool can't drain inside a loop that suspends).
            let sessionMsgs = await store.loadMessages(sessionId: sid)
            try autoreleasepool {
                for msg in sessionMsgs where msg.createdAt <= snapshotAt {
                    try messages.write(BackupRecordEnvelope(t: "MessageV2", d: msg))
                    messageCount += 1
                    sessionMessages += 1
                }
            }
            // Record what this session actually contributed, so a rescue can
            // tell the user which conversations the package held.
            rescueSessions.append(.init(id: sid, title: session.title,
                                        messageCount: sessionMessages))
            for marker in await store.compactMarkers(sessionId: sid) {
                try markers.write(BackupRecordEnvelope(t: "CompactMarkerV2", d: marker))
            }

            // The session's whole on-disk tree: attachments / offloads /
            // workspace / browser.
            let dir = AIChatViewModel.minisPersistentBase
                .appendingPathComponent(sid, isDirectory: true)
            let r = try trees.export(root: dir, logicalPrefix: "chats/\(sid)",
                                     category: .chats, sessionId: sid)
            fileCount += r.filesIncluded
            fileBytes += r.bytesIncluded
        }

        for folder in await store.listFolders() {
            try folders.write(BackupRecordEnvelope(t: "FolderV2", d: folder))
        }

        try sessions.close(); try messages.close()
        try markers.close(); try folders.close()

        // What this category actually produced. The counts are the useful
        // part: "did my 400 conversations really go in?" is answerable from
        // the log rather than only from the finished package.
        reporter.finish(AppLocalized("Chats exported"),
                        detail: AppLocalized("\(inScope.count) conversations, \(messageCount) messages, \(fileCount) files"))

        return BackupManifest.CategoryStat(
            entries: messageCount + fileCount,
            bytes: sessions.totalBytes + messages.totalBytes + markers.totalBytes + fileBytes,
            encrypted: false, messages: messageCount, files: fileCount)
    }

    /// Session row + the two extras the sync layer keeps outside `ChatSession`,
    /// so a restore rebuilds the same state without a second lookup.
    private struct SessionRecord: Codable {
        let session: ChatSession
        let memoryEnabled: Bool
        let modelBinding: String?
    }

    // MARK: - Shared files

    /// §3.2 — the cross-session `/var/minis/shared` bucket. Host-side this is
    /// the App Group directory, NOT anything inside the rootfs.
    private func exportSharedFiles(trees: BackupFileTreeExporter) throws
        -> BackupManifest.CategoryStat {
        let r = try trees.export(root: AIChatViewModel.minisSharedPersistentDir,
                                 logicalPrefix: "shared",
                                 category: .sharedFiles)
        return BackupManifest.CategoryStat(
            entries: r.filesIncluded, bytes: r.bytesIncluded, encrypted: false)
    }

    // MARK: - Skills

    private func exportSkills(dataDir: URL, trees: BackupFileTreeExporter) async throws
        -> BackupManifest.CategoryStat {
        let rows = await MainActor.run { SkillStore.shared.skills.map(SkillRecord.init) }
        let writer = BackupJSONLWriter(directory: dataDir, baseName: "skills")
        defer { try? writer.close() }
        for row in rows {
            try writer.write(BackupRecordEnvelope(t: "SkillV2", d: row))
        }
        try writer.close()

        var fileBytes: Int64 = 0
        var fileCount = 0
        for row in rows {
            let dir = await MainActor.run { SkillStore.shared.skillDirectoryURL(for: row.id) }
            let r = try trees.export(root: dir, logicalPrefix: "skills/\(row.id)",
                                     category: .skills)
            fileBytes += r.bytesIncluded
            fileCount += r.filesIncluded
        }
        // [T-backup-category-counts] `entries` is skills, `files` is the files
        // inside them. A 12-skill tree holding 569 files reports 12 and 569,
        // not 569 — the user thinks in skills here, and the file count is the
        // supporting detail.
        return BackupManifest.CategoryStat(
            entries: rows.count, bytes: writer.totalBytes + fileBytes, encrypted: false,
            files: fileCount)
    }

    /// `SkillStore.Skill` is not Codable (it is a UI model), so the format
    /// declares its own record. That is the right boundary anyway: the package
    /// is cross-platform and must not drift with an internal UI type.
    private struct SkillRecord: Codable {
        let id: String
        let name: String
        let description: String
        let version: String
        let isEnabled: Bool
        let installedAt: Date
        let updatedAt: Date
        let body: String
        let sourceURL: String?

        init(_ s: Skill) {
            id = s.id; name = s.name; description = s.description
            version = s.version; isEnabled = s.isEnabled
            installedAt = s.installedAt; updatedAt = s.updatedAt
            body = s.body; sourceURL = s.sourceURL
        }
    }

    // MARK: - Memory & Soul

    /// GLOBAL.md / SOUL.md / daily files, copied verbatim into `data/memory/`.
    ///
    /// Note this deliberately does NOT reuse the sync hydrator's daily-memory
    /// path: that one drops anything older than 30 days, which is correct for
    /// sync traffic and wrong for a full backup.
    private func exportMemory(dataDir: URL) throws -> BackupManifest.CategoryStat {
        let src = AIChatViewModel.minisMemoryPersistentDir
        let dst = dataDir.appendingPathComponent("memory", isDirectory: true)
        try fm.createDirectory(at: dst, withIntermediateDirectories: true)

        var count = 0
        var bytes: Int64 = 0
        let names = (try? fm.contentsOfDirectory(atPath: src.path)) ?? []
        for name in names where name.hasSuffix(".md") {
            let from = src.appendingPathComponent(name)
            guard (try? from.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true
            else { continue }
            let to = dst.appendingPathComponent(name)
            try? fm.removeItem(at: to)
            try fm.copyItem(at: from, to: to)
            count += 1
            bytes += (try? fm.attributesOfItem(atPath: from.path)[.size] as? Int64) ?? 0
        }
        return BackupManifest.CategoryStat(entries: count, bytes: bytes, encrypted: false)
    }

    // MARK: - Providers

    /// Provider structure plus, unless the caller opts out, the credential
    /// section (§3.3: they are one category and restore together).
    ///
    /// `ProviderConfig` is `Codable` and holds instances / model entries /
    /// groups / agent-loop bindings with no secrets — those live in the
    /// Keychain keyed by instance UUID and are collected separately into
    /// `secrets.json` (§5.4).
    private func exportProviders(dataDir: URL, staging: URL,
                                 includeCredentials: Bool) async throws
        -> BackupManifest.CategoryStat {
        let config = await MainActor.run { ProviderConfigStore.shared.config }
        let url = dataDir.appendingPathComponent("provider_config.json")
        try BackupJSONFile.write(config, to: url)
        var bytes = (try? fm.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0

        // [T-backup-thinking-rules] User-authored thinking rules live in their
        // OWN table (`provider_thinking_rules`), not in `ProviderConfig`, so
        // serializing the config object above misses them entirely. They carry
        // the user's custom request-body parameters (`extraBodyToggle` and the
        // rest of ThinkingWireFormat, opaque in `wire_format_json`), so a
        // restore used to silently drop exactly the settings the user had gone
        // out of their way to author.
        //
        // `allCustomThinkingRuleIds()` is `WHERE is_builtin = 0`, which is the
        // filter this needs: built-ins ship with the app and must not be
        // resurrected from an old package onto a newer build.
        let ruleCount = try await exportThinkingRules(dataDir: dataDir)
        if ruleCount > 0 {
            let rulesURL = dataDir.appendingPathComponent("thinking_rules.jsonl")
            bytes += (try? fm.attributesOfItem(atPath: rulesURL.path)[.size] as? Int64) ?? 0
        }

        guard includeCredentials else {
            // The "export copy without credentials" path (§3.3/§5.4). A reader
            // is told plainly that this package cannot restore working keys.
            // [T-backup-category-counts] Rules are reported alongside, not
            // added in: `entries` stays the provider count the user recognises.
            return BackupManifest.CategoryStat(
                entries: config.instances.count, bytes: bytes, encrypted: false,
                thinkingRules: ruleCount > 0 ? ruleCount : nil,
                includesCredentials: false)
        }

        let secrets = await MainActor.run {
            BackupSecretsCollector.collect(instances: config.instances)
        }
        // Named `secrets.json`, not `secrets.enc`: the §5.4 filename implies
        // encryption, and this stage does not encrypt. Calling it .enc would
        // misrepresent the file to anyone inspecting the package, which is
        // exactly the "thought it was protected" failure the design warns about.
        // The encryption stage renames it and the importer accepts both.
        let secretsURL = staging.appendingPathComponent("secrets.json")
        try BackupJSONFile.write(secrets, to: secretsURL)
        bytes += (try? fm.attributesOfItem(atPath: secretsURL.path)[.size] as? Int64) ?? 0

        logger.warning("[Backup] package contains UNENCRYPTED credentials (stage 3a): providers=\(secrets.providers.count) envVars=\(secrets.envVars.count) mcpOAuth=\(secrets.mcpOAuth.count)")

        return BackupManifest.CategoryStat(
            entries: config.instances.count, bytes: bytes, encrypted: false,
            thinkingRules: ruleCount > 0 ? ruleCount : nil,
            includesCredentials: true)
    }

    /// [T-backup-thinking-rules] Write every user-authored thinking rule to
    /// `data/thinking_rules.jsonl`. Returns how many were written.
    ///
    /// Reads the RAW ROWS rather than `loadThinkingRules`, which returns
    /// decoded `ThinkingRule` values: the point is to carry the stored
    /// representation across unchanged — above all `wire_format_json`, whose
    /// whole design is to be an opaque blob so a new wire-format case needs no
    /// migration. Decoding and re-encoding here would put this file in the
    /// business of understanding formats it deliberately does not.
    ///
    /// Nothing is written when the user has authored no rules, so an ordinary
    /// package gains no file at all.
    private func exportThinkingRules(dataDir: URL) async throws -> Int {
        guard let db = await MainActor.run(body: { ProviderConfigStore.shared.db }) else { return 0 }
        let ids = await db.allCustomThinkingRuleIds()   // WHERE is_builtin = 0
        guard !ids.isEmpty else { return 0 }

        let writer = BackupJSONLWriter(directory: dataDir, baseName: "thinking_rules")
        defer { try? writer.close() }
        var written = 0
        for id in ids {
            guard let row = await db.thinkingRuleRow(id: id) else { continue }
            let record = BackupThinkingRuleRecord(
                id: id,
                instanceId: (row["instance_id"] as? String) ?? "",
                sortOrder: (row["sort_order"] as? Int) ?? 0,
                scopeKind: (row["scope_kind"] as? String) ?? "allModels",
                scopePattern: row["scope_pattern"] as? String,
                wireFormatJson: (row["wire_format_json"] as? String) ?? "{}",
                echoField: row["echo_field"] as? String,
                echoTiming: row["echo_timing"] as? String,
                label: (row["label"] as? String) ?? "",
                createdAt: Date(timeIntervalSince1970: (row["created_at"] as? Double) ?? 0),
                updatedAt: Date(timeIntervalSince1970: (row["updated_at"] as? Double) ?? 0)
            )
            try writer.write(BackupRecordEnvelope(t: "ThinkingRuleV1", d: record))
            written += 1
        }
        try writer.close()
        logger.info("[Backup] exported \(written) custom thinking rule(s)")
        return written
    }

    // MARK: - MCP servers

    /// `servers.json` is copied verbatim.
    ///
    /// Caveat worth carrying forward: this file can hold Authorization headers
    /// and API keys inline, which is why it lives under the hidden config root
    /// rather than the FileProvider-visible one. Stage 3 must decide whether it
    /// belongs behind the credential subkey; for now it is exported as-is and
    /// the whole package is expected to be treated as sensitive.
    private func exportMCPServers(dataDir: URL) throws -> BackupManifest.CategoryStat? {
        let src = MCPStore.syncFileURL
        guard fm.fileExists(atPath: src.path) else { return nil }
        let dst = dataDir.appendingPathComponent("mcp_servers.json")
        try? fm.removeItem(at: dst)
        try fm.copyItem(at: src, to: dst)
        let bytes = (try? fm.attributesOfItem(atPath: dst.path)[.size] as? Int64) ?? 0
        // [T-backup-category-counts] Count the SERVERS, not the file. This used
        // to report `entries: 1` for any number of servers, so a user with
        // seven MCP servers saw "1". Parsed leniently from the copy we just
        // wrote — the same `{"mcpServers": {…}}` shape MCPStore reads, via
        // JSONSerialization rather than the typed decoder so an
        // externally-authored config still counts. An unreadable file falls
        // back to 1, which is the old behaviour and never zero for a file that
        // exists.
        let serverCount = Self.mcpServerCount(at: dst) ?? 1
        return BackupManifest.CategoryStat(entries: serverCount, bytes: bytes, encrypted: false)
    }

    /// Number of servers declared in a `servers.json`, or nil if it cannot be
    /// read as one. Kept `static` and free of store state so the export path
    /// and the tests measure the very same bytes on disk.
    static func mcpServerCount(at url: URL) -> Int? {
        guard let data = try? Data(contentsOf: url),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let servers = root["mcpServers"] as? [String: Any] else {
            return nil
        }
        return servers.count
    }

    // MARK: - Environment variables

    /// The env-var METADATA (id, key, createdAt, note).
    ///
    /// Values are deliberately NOT here: they live in the Keychain and travel
    /// through `secrets.json` under the credential policy, so an unencrypted
    /// package carries the list of variables without their contents. Restoring
    /// such a package recreates the entries and leaves each value to be typed
    /// in again — visibly incomplete rather than silently absent.
    ///
    /// Before this category existed the collector already put env-var VALUES in
    /// secrets.json while nothing exported this file, so a restore wrote
    /// Keychain values that no entry referenced: invisible in the UI and
    /// missing from `allAsDict()`.
    private func exportEnvironmentVariables(dataDir: URL) async throws
        -> BackupManifest.CategoryStat? {
        let entries = await MainActor.run { EnvVarStore.shared.entries }
        guard !entries.isEmpty else { return nil }
        let url = dataDir.appendingPathComponent("env_vars.json")
        try BackupJSONFile.write(entries, to: url)
        let bytes = (try? fm.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0
        return BackupManifest.CategoryStat(entries: entries.count, bytes: bytes,
                                           encrypted: false)
    }

    // MARK: - Voice corrections

    private func exportVoiceCorrections(dataDir: URL) async throws
        -> BackupManifest.CategoryStat? {
        guard let db = VoiceCorrectionDB.shared else { return nil }

        // `rowCount` first so the bounded readers are given a limit big enough
        // to return everything — there is no unbounded enumerator on this DB.
        let confusionTotal = await db.rowCount(table: "confusion_dictionary")
        let vocabTotal = await db.rowCount(table: "typed_vocabulary")
        let confusion = await db.allConfusion(orderBy: "confidence", locale: nil,
                                              limit: max(confusionTotal, 1))
        let vocabulary = await db.allVocabulary(orderBy: "frequency",
                                                limit: max(vocabTotal, 1))

        let writer = BackupJSONLWriter(directory: dataDir, baseName: "voice_correction")
        defer { try? writer.close() }
        for row in confusion {
            try writer.write(BackupRecordEnvelope(t: "VoiceConfusion", d: ConfusionRecord(row)))
        }
        for row in vocabulary {
            try writer.write(BackupRecordEnvelope(t: "VoiceVocabulary", d: VocabularyRecord(row)))
        }
        try writer.close()
        return BackupManifest.CategoryStat(
            entries: confusion.count + vocabulary.count,
            bytes: writer.totalBytes, encrypted: false)
    }

    private struct ConfusionRecord: Codable {
        let id: String, phoneticKey: String, variants: [String], correctedTerm: String
        let locale: String, frequency: Int, negativeFeedbackCount: Int
        let confidence: Double, lastSeen: Double, source: String
        init(_ r: ConfusionRow) {
            id = r.id; phoneticKey = r.phoneticKey; variants = r.variants
            correctedTerm = r.correctedTerm; locale = r.locale; frequency = r.frequency
            negativeFeedbackCount = r.negativeFeedbackCount; confidence = r.confidence
            lastSeen = r.lastSeen; source = r.source
        }
    }

    private struct VocabularyRecord: Codable {
        let id: String, term: String, phoneticKey: String, locale: String
        let posTag: String?, frequency: Int, distinctDays: Int
        let backgroundRank: Int?, lastSeen: Double, source: String
        init(_ r: VocabularyRow) {
            id = r.id; term = r.term; phoneticKey = r.phoneticKey; locale = r.locale
            posTag = r.posTag; frequency = r.frequency; distinctDays = r.distinctDays
            backgroundRank = r.backgroundRank; lastSeen = r.lastSeen; source = r.source
        }
    }

    // MARK: - Package assembly

    /// Encrypt every staged member in place, appending `.enc` to its name (§5.3).
    ///
    /// `manifest.json` is excluded — §2.1 requires it to stay plaintext so the
    /// user can see what a package holds *before* being asked for a passphrase.
    /// `secrets.json` gets `K_secrets` rather than `K_data`; the separate subkey
    /// is what makes "strip the credentials" a file deletion instead of a
    /// re-encrypt of everything else (§5.4).
    private func encryptStagedMembers(in staging: URL, keys: BackupCrypto.Keys) throws {
        var members: [URL] = []
        if let e = fm.enumerator(at: staging, includingPropertiesForKeys: [.isRegularFileKey]) {
            for case let url as URL in e {
                guard (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true
                else { continue }
                // manifest.json is plaintext by §2.1 so a user can see what a
                // package holds before being asked for a passphrase; rescue.json
                // is plaintext for a sharper reason — rescue is precisely the
                // case where the passphrase may be lost, and an encrypted
                // rescue index is useless to the person who needs it most.
                // Encrypting it would have silently defeated the whole feature.
                guard url.lastPathComponent != "manifest.json",
                      url.lastPathComponent != BackupRescueIndex.filename,
                      url.lastPathComponent != BackupRescueIndex.manifestCopyFilename
                else { continue }
                members.append(url)
            }
        }

        for url in members {
            // Standardize BOTH sides before the prefix strip. Application
            // Support resolves through /private/var/… while `staging.path` is
            // the unresolved /var/… form, so a raw character-count drop cut 8
            // characters too few and produced integrity keys like
            // "71B13F/blobs/…". The package then failed its OWN verification on
            // restore with hundreds of bogus "integrity check failed" entries.
            // Caught on device; a relative-path computation is immune to it.
            let base = staging.standardizedFileURL.resolvingSymlinksInPath().path
            let full = url.standardizedFileURL.resolvingSymlinksInPath().path
            let rel = full.hasPrefix(base + "/")
                ? String(full.dropFirst(base.count + 1))
                : url.lastPathComponent
            let key = rel == "secrets.json" ? keys.secretsKey : keys.dataKey
            let dest = url.appendingPathExtension("enc")
            // The AAD is the member's path INCLUDING the .enc suffix, i.e. the
            // name it actually ships under, so the importer can bind to exactly
            // what it reads off disk.
            try BackupCrypto.encryptFile(at: url, to: dest, key: key, path: rel + ".enc")
            try fm.removeItem(at: url)
        }
        logger.info("[Backup] encrypted \(members.count) member(s)")
    }

    private func writeBlobIndex(_ entries: [BackupBlobIndexEntry], to staging: URL) throws {
        let url = staging.appendingPathComponent("blobs.index.jsonl")
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        var out = Data()
        for e in entries {
            out.append(try encoder.encode(e))
            out.append(0x0A)
        }
        try out.write(to: url, options: .atomic)
    }

    private func buildManifest(backupId: String,
                               categories: [String: BackupManifest.CategoryStat],
                               blobStore: BackupBlobStore,
                               staging: URL,
                               encryption: BackupManifest.Encryption?,
                               snapshotAt: Date) async throws -> BackupManifest {
        let bundle = Bundle.main
        // Same source as the filename, so the manifest agrees with the name on
        // the package rather than recording a bare "iPhone" beside it.
        let deviceName = await MainActor.run { DeviceIdentity.displayName }

        // Integrity covers every packaged file. Stage 1 is unencrypted, so
        // these are plaintext hashes; §5.3 makes them ciphertext hashes once
        // encryption lands, which is why they're computed here over whatever
        // bytes actually ship rather than over the source data.
        var integrity: [String: String] = [:]
        if let e = fm.enumerator(at: staging, includingPropertiesForKeys: [.isRegularFileKey]) {
            for case let url as URL in e {
                guard (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true
                else { continue }
                // Standardize BOTH sides before the prefix strip. Application
            // Support resolves through /private/var/… while `staging.path` is
            // the unresolved /var/… form, so a raw character-count drop cut 8
            // characters too few and produced integrity keys like
            // "71B13F/blobs/…". The package then failed its OWN verification on
            // restore with hundreds of bogus "integrity check failed" entries.
            // Caught on device; a relative-path computation is immune to it.
            let base = staging.standardizedFileURL.resolvingSymlinksInPath().path
            let full = url.standardizedFileURL.resolvingSymlinksInPath().path
            let rel = full.hasPrefix(base + "/")
                ? String(full.dropFirst(base.count + 1))
                : url.lastPathComponent
                guard rel != "manifest.json" else { continue }  // can't hash itself
                integrity[rel] = try BackupBlobStore.sha256OfFile(at: url)
            }
        }

        // Streamed blobs never touched staging, so the walk above cannot see
        // them — without this the package would omit them from its own
        // integrity map and fail verification on restore. Their entry name IS
        // their digest, so no re-hashing is needed.
        if blobStore.packageSink != nil {
            for entry in blobStore.blobIndex {
                integrity[BackupBlobStore.packagePath(for: entry.sha256)] = entry.sha256
            }
        }

        return BackupManifest(
            createdAt: Date(),
            app: .init(platform: "ios",
                       version: bundle.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?",
                       build: bundle.infoDictionary?["CFBundleVersion"] as? String ?? "?"),
            deviceName: deviceName,
            backupId: backupId,
            categories: categories,
            limits: BackupManifest.Limits(
                maxFileBytes: blobStore.maxFileBytesForManifest,
                skippedFiles: blobStore.skippedFiles,
                skippedBytes: blobStore.skippedBytes),
            encryption: encryption,
            integrity: integrity,
            manifestMac: nil,
            snapshotAt: snapshotAt)
    }

    /// Zip the staging tree via NSFileCoordinator — no ZIP library is linked,
    /// and this handles arbitrarily large trees without loading them into
    /// memory (the same primitive the existing session export uses).
    /// Append everything left in the staging tree to an in-progress package.
    ///
    /// With streaming on, staging holds only the small members — `data/*.jsonl`,
    /// the indexes, the manifest — because blobs went straight into the
    /// package. Entry names are the paths relative to the staging root, which
    /// is exactly what `NSFileCoordinator` produced, so the resulting layout is
    /// identical and the reader needs no changes.
    private func appendStagedMembers(from staging: URL, to writer: BackupZipWriter) throws {
        let root = staging.standardizedFileURL.path
        guard let e = fm.enumerator(at: staging, includingPropertiesForKeys: [.isRegularFileKey])
        else { return }
        // Sorted so a package is reproducible for a given input, and so the
        // resume scan sees a stable order.
        var members: [(URL, String)] = []
        for case let url as URL in e {
            guard (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true
            else { continue }
            var rel = url.standardizedFileURL.path
            guard rel.hasPrefix(root) else { continue }
            rel.removeFirst(root.count)
            if rel.hasPrefix("/") { rel.removeFirst() }
            guard !rel.isEmpty else { continue }
            members.append((url, rel))
        }
        for (url, name) in members.sorted(by: { $0.1 < $1.1 }) {
            try writer.addFile(at: url, name: name)
        }
    }

    private func archive(staging: URL, backupId: String,
                         at date: Date, deviceName: String,
                         encrypted: Bool) async throws -> URL {
        // A date-resolution stamp alone collides whenever two exports run on
        // the same day (trivially reachable when testing, or when a user
        // retries with a different category selection) — the second silently
        // replaced the first. The id suffix is unique per run, so distinct
        // exports are always distinct files.
        let out = fm.temporaryDirectory
            .appendingPathComponent(Self.packageFileName(
                backupId: backupId, at: date, deviceName: deviceName,
                encrypted: encrypted))
        try? fm.removeItem(at: out)

        return try await withCheckedThrowingContinuation { cont in
            let coordinator = NSFileCoordinator()
            var nsError: NSError?
            coordinator.coordinate(readingItemAt: staging, options: .forUploading,
                                   error: &nsError) { zipped in
                do {
                    try self.fm.copyItem(at: zipped, to: out)
                    cont.resume(returning: out)
                } catch {
                    cont.resume(throwing: BackupError.archiveFailed(error))
                }
            }
            if let nsError { cont.resume(throwing: BackupError.archiveFailed(nsError)) }
        }
    }

    /// [T-backup-package-name-parity] The one place a package filename is
    /// built, so the streaming and staged paths cannot drift apart again.
    ///
    /// This is also the on-remote name — the uploader takes
    /// `packageURL.lastPathComponent` — so it has to be meaningful to a person
    /// browsing their NAS, not just unique.
    ///
    /// [T-backup-package-name-device] Shape is
    /// `<device>-<yyyyMMdd>-<sortable-id>.minisbak`, e.g.
    /// `Ethans-iPhone-20260823-mf3k9q2phz.minisbak`. Three deliberate changes
    /// from the old `backup-20260823-1259-f69c00.minisbak`:
    ///
    ///  - **Device first.** Several devices back up into one NAS folder and
    ///    the old names were indistinguishable. Leading with the device makes
    ///    a name-sorted listing group each device's packages together, which
    ///    is the question someone browsing that folder actually has. The
    ///    `backup-` prefix is dropped to pay for it — `.minisbak` already says
    ///    what the file is.
    ///  - **Date only, no `HHmm`.** The id below is what separates two runs;
    ///    the clock time was just making the name longer.
    ///  - **A time-ordered id instead of a UUID slice.** See `sortableID`.
    ///
    /// [T-backup-package-name-encrypted] An encrypted package additionally
    /// carries `-encrypted` before the extension, e.g.
    /// `Ethans-iPhone-20260823-mf3k9q2phz-encrypted.minisbak`. Whether a
    /// package needs its passphrase is otherwise invisible until someone tries
    /// to open it — which, for a backup found on a NAS months later, is
    /// exactly the wrong moment to find out. It goes AFTER the id rather than
    /// in front so it cannot disturb the chronological name sort the id
    /// exists to provide.
    static func packageFileName(backupId: String, at date: Date,
                                deviceName: String, encrypted: Bool = false) -> String {
        let device = filenameDeviceToken(deviceName)
        let stamp = filenameFormatter.string(from: date)
        let suffix = encrypted ? "-encrypted" : ""
        return "\(device)-\(stamp)-\(sortableID(backupId: backupId, at: date))\(suffix)"
            + ".\(BackupFormat.fileExtension)"
    }

    /// Lowercase Crockford-style Base32 of the run's millisecond timestamp,
    /// plus two characters derived from the backup id.
    ///
    /// The point is that **lexicographic order matches chronological order**:
    /// the alphabet `0-9a-z` (minus i/l/o/u) is monotonic in ASCII, so a later
    /// timestamp always encodes to a string that sorts after an earlier one.
    /// The old id was `UUID().uuidString.prefix(6)` — a v4 UUID is pure
    /// randomness, so once `HHmm` came out of the name there would have been
    /// nothing left to order two backups made on the same day. Making the id
    /// sortable is what allows the clock time to be dropped at all.
    ///
    /// 8 characters = 40 bits, which covers milliseconds past the epoch until
    /// the year 36812; a shorter field would have overflowed and wrapped,
    /// silently breaking the ordering it exists to provide. The trailing 3
    /// characters come from the backup id so that two runs starting in the
    /// same millisecond still differ — 3 rather than 2 because 2 is only 1024
    /// values, and the tie it breaks (two exports in one millisecond) is
    /// exactly the case where a collision would overwrite a real backup.
    ///
    /// Derived from `date` (the run's `snapshotAt`, which a resumed export
    /// carries over from its first attempt) rather than `Date()`, so resuming
    /// an interrupted backup reproduces the same filename instead of leaving
    /// two differently-named copies of one backup on the destination.
    static func sortableID(backupId: String, at date: Date) -> String {
        let alphabet = Array("0123456789abcdefghjkmnpqrstvwxyz")
        func encode(_ value: UInt64, width: Int) -> String {
            var v = value
            var out = [Character]()
            for _ in 0..<width {
                out.append(alphabet[Int(v & 31)])
                v >>= 5
            }
            return String(out.reversed())
        }
        let ms = UInt64(max(0, date.timeIntervalSince1970 * 1000))
        // Mask to the 40 bits the 8-character field can actually represent, so
        // the encoding stays total rather than trapping on an absurd clock.
        let time = encode(ms & 0xFF_FFFF_FFFF, width: 8)
        // A stable hash of the id, not a slice of it: the id is a UUID whose
        // leading characters are hex, so a slice would only ever use 16 of the
        // 32 available symbols.
        var h: UInt64 = 0xcbf2_9ce4_8422_2325
        for b in backupId.utf8 {
            h = (h ^ UInt64(b)) &* 0x1000_0000_01b3
        }
        return time + encode(h, width: 3)
    }

    /// A filename-safe, ASCII device token, e.g. `Ethans-iPhone`.
    ///
    /// `UIDevice.current.name` is user-controlled and lands on SMB/exFAT
    /// shares, so it cannot go into a filename as-is: it may hold spaces,
    /// apostrophes, `/`, `:`, emoji or CJK. Non-ASCII is dropped rather than
    /// transliterated — a name that survives a round trip through every
    /// filesystem matters more here than preserving the exact characters, and
    /// the full name is still recorded in the package manifest's `device_name`.
    ///
    /// Falls back to `UIDevice.current.model` when nothing ASCII survives (a
    /// device named purely in Chinese, say), so the token is never empty.
    ///
    /// Note that on iOS 16+ `UIDevice.current.name` returns the MODEL name
    /// ("iPhone") unless the app holds the user-assigned-device-name
    /// entitlement, so the AUTOMATIC name cannot tell two iPhones apart. That
    /// is why callers pass `DeviceIdentity.displayName`, which prefers a name
    /// the user typed in Backup settings ([T-backup-device-name-setting]) —
    /// the fix for that collision, and one that needs no entitlement.
    static func filenameDeviceToken(_ raw: String, fallback: String = "device") -> String {
        var out = ""
        var lastWasSeparator = false
        for ch in raw {
            if ch.isASCII && (ch.isLetter || ch.isNumber) {
                out.append(ch)
                lastWasSeparator = false
            } else if ch == "'" || ch == "\u{2019}" {
                // Elide apostrophes rather than treating them as separators:
                // the overwhelmingly common device name is "Ethan's iPhone",
                // and splitting on the apostrophe yields `Ethan-s-iPhone`,
                // which reads as three words. Both the ASCII quote and the
                // curly one iOS substitutes are handled.
                continue
            } else if !out.isEmpty && !lastWasSeparator {
                // Collapse any run of spaces/punctuation/dropped non-ASCII into
                // a single dash instead of emitting `Ethan--s---iPhone`.
                out.append("-")
                lastWasSeparator = true
            }
        }
        while out.hasSuffix("-") { out.removeLast() }
        if out.count > 24 {
            out = String(out.prefix(24))
            while out.hasSuffix("-") { out.removeLast() }
        }
        return out.isEmpty ? fallback : out
    }

    private static let filenameFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()
}
