import Foundation

private let logger = AppLogger(category: "Backup")

/// Persistent staging + progress record for an export in flight, so a backup
/// interrupted by suspension, jetsam or a crash can be resumed instead of
/// restarting from zero.
///
/// This is the export-side counterpart to `BackupRestoreJournal` (which the B4
/// fix added for restores), and it exists for the same reason: `defer`-based
/// cleanup only runs when the process survives, so an interrupted export used
/// to leave its staging directory orphaned in `tmp/` — hundreds of MB that
/// nothing ever swept (review finding I6) — while the user's next attempt threw
/// all that work away and re-hashed every blob from scratch.
///
/// Two changes make resume possible:
///
/// 1. **Staging lives in Application Support, not `tmp/`.** iOS purges `tmp/`
///    on its own schedule, so a directory there cannot be relied on to survive
///    to the next launch — which is exactly when a resume needs it.
/// 2. **Each category writes a `<category>.done` marker when it completes.**
///    On resume the finished categories are skipped and the rest run against
///    the ORIGINAL `snapshotAt`, so however many times an export is
///    interrupted, the package it finally produces is one consistent view of
///    the data as of the first attempt.
enum BackupExportJournal {

    private static var supportDir: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("BackupExport", isDirectory: true)
    }

    private static var markerURL: URL {
        supportDir.appendingPathComponent("in-progress.json")
    }

    /// Staging directory for an export run.
    ///
    /// Deliberately still named `minisbak-<backupId>`: `archive()` zips this
    /// directory, so its NAME becomes the top-level folder inside every
    /// package, and the integrity map is keyed on paths relative to it. Naming
    /// it `staging-…` (one character shorter) silently shifted every integrity
    /// key by one character — the package built fine and then failed its own
    /// verification on restore with hundreds of "integrity check failed"
    /// entries. Caught on device; keeping the historical name keeps packages
    /// byte-compatible with what previous builds produced.
    static func stagingRoot(backupId: String) -> URL {
        supportDir.appendingPathComponent("minisbak-\(backupId)", isDirectory: true)
    }

    /// What an interrupted export was doing, so it can be picked up again.
    struct Marker: Codable {
        var backupId: String
        var startedAt: Date
        /// The ORIGINAL cut-off. Reusing it is what keeps a resumed export a
        /// snapshot rather than a moving target.
        var snapshotAt: Date
        var categories: [String]
        var includeCredentials: Bool
        var maxFileBytes: Int64?
        /// Whether the interrupted run was producing an encrypted package. The
        /// passphrase itself is NEVER persisted — a resume of an encrypted
        /// export requires the user to supply it again, and without it the
        /// stale staging is discarded rather than silently downgraded to a
        /// plaintext package.
        var encrypted: Bool
    }

    static func begin(_ marker: Marker) {
        do {
            try FileManager.default.createDirectory(at: supportDir,
                                                    withIntermediateDirectories: true)
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            try encoder.encode(marker).write(to: markerURL, options: .atomic)
        } catch {
            // A missing marker costs resume, not correctness.
            logger.warning("[Backup] couldn't write export marker: \(error.localizedDescription)")
        }
    }

    /// The export that never finished, if any.
    static func interrupted() -> Marker? {
        guard let data = try? Data(contentsOf: markerURL) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(Marker.self, from: data)
    }

    /// Clear the marker and the staging tree after a clean finish.
    static func finish(backupId: String) {
        try? FileManager.default.removeItem(at: markerURL)
        try? FileManager.default.removeItem(at: stagingRoot(backupId: backupId))
    }

    // MARK: - Per-category completion

    private static func doneMarker(in staging: URL, category: BackupCategory) -> URL {
        staging.appendingPathComponent("\(category.rawValue).done")
    }

    /// Record that a category finished, along with the stats needed to rebuild
    /// the manifest without re-exporting it.
    static func markDone(_ category: BackupCategory, stat: BackupManifest.CategoryStat,
                         in staging: URL) {
        do {
            let encoder = JSONEncoder()
            try encoder.encode(stat).write(to: doneMarker(in: staging, category: category),
                                           options: .atomic)
        } catch {
            // Losing a marker only costs re-doing that category on resume.
            logger.warning("[Backup] couldn't mark \(category.rawValue) done: \(error.localizedDescription)")
        }
    }

    /// Stats for a category already completed in a previous attempt, if any.
    static func completedStat(_ category: BackupCategory,
                              in staging: URL) -> BackupManifest.CategoryStat? {
        guard let data = try? Data(contentsOf: doneMarker(in: staging, category: category))
        else { return nil }
        return try? JSONDecoder().decode(BackupManifest.CategoryStat.self, from: data)
    }

    /// Delete the per-category completion markers.
    ///
    /// They are resume bookkeeping and must not ship inside the package: the
    /// archive step zips the staging directory wholesale, so anything left here
    /// becomes a package member, lands in the integrity map, and shows up as a
    /// mystery `chats.done` entry to any reader. Caught on device.
    static func clearDoneMarkers(in staging: URL) {
        let fm = FileManager.default
        for name in (try? fm.contentsOfDirectory(atPath: staging.path)) ?? []
        where name.hasSuffix(".done") {
            try? fm.removeItem(at: staging.appendingPathComponent(name))
        }
    }

    /// Throw away the in-flight run's marker and staging.
    ///
    /// Used by "stop and delete": a plain stop leaves staging so the next run
    /// resumes, which is wrong when the user's intent is to abandon this backup
    /// entirely. Without clearing the marker, the next export would silently
    /// pick the discarded run back up.
    static func abandonInProgress() {
        guard let marker = interrupted() else { return }
        finish(backupId: marker.backupId)
    }

    // MARK: - Housekeeping

    /// [review I6] Delete abandoned staging trees and stale `tmp/` working
    /// directories at launch.
    ///
    /// Called from app startup. Anything belonging to the CURRENT in-progress
    /// marker is preserved — that is the tree a resume needs. Everything else
    /// is unreachable by definition: no marker points at it, so nothing can
    /// ever resume it, and it is only occupying disk.
    static func sweepAbandoned() {
        let fm = FileManager.default
        let keep = interrupted().map { "minisbak-\($0.backupId)" }

        var removed = 0
        for name in (try? fm.contentsOfDirectory(atPath: supportDir.path)) ?? [] {
            guard name.hasPrefix("minisbak-"), name != keep else { continue }
            try? fm.removeItem(at: supportDir.appendingPathComponent(name))
            removed += 1
        }

        // Everything the backup and restore paths leave in tmp/ when they are
        // interrupted. iOS purges tmp on its own schedule, but "eventually" is
        // not good enough when a single leftover can be several GB — the
        // failure this whole sweeper exists to prevent is a phone that fills
        // up because a killed backup never cleaned up after itself.
        //
        // Prefixes, and what leaks without them:
        //   minisbak-        pre-resume export staging; import/peek work dirs
        //   backup-          a half-written STREAMING package produced by a
        //                    build older than [T-backup-package-name-device]
        //   restore-         a package downloaded from a mounted folder
        //   server-restore-  a package downloaded from an rclone remote
        //
        // Plus ANY `*.minisbak` in tmp/, which is what actually catches the
        // half-written streaming package now. That package is the big one — it
        // is the whole backup, so it can be gigabytes — and it used to be
        // matched by the `backup-` prefix alone. Package names now lead with
        // the device instead ([T-backup-package-name-device]), so a prefix
        // match no longer sees them; matching the extension is both correct
        // for the new shape and immune to the next rename. `backup-` stays so
        // that a leftover written by a previous build is still swept.
        //
        // Nothing here is ever resumable: exports resume from staging in
        // Application Support, and a partial download is always re-fetched.
        let tmp = fm.temporaryDirectory
        let prefixes = ["minisbak-", "backup-", "restore-", "server-restore-"]
        let packageSuffix = "." + BackupFormat.fileExtension
        for name in (try? fm.contentsOfDirectory(atPath: tmp.path)) ?? []
        where prefixes.contains(where: name.hasPrefix) || name.hasSuffix(packageSuffix) {
            let url = tmp.appendingPathComponent(name)
            let bytes = (try? url.resourceValues(forKeys: [.totalFileAllocatedSizeKey]))?
                .totalFileAllocatedSize ?? 0
            try? fm.removeItem(at: url)
            removed += 1
            logger.info("[Backup] swept leftover \(name) (\(bytes) bytes)")
        }

        if removed > 0 {
            logger.info("[Backup] swept \(removed) abandoned staging director(ies)")
        }
    }
}
