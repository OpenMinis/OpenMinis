import Foundation

private let logger = AppLogger(category: "Backup")

/// Crash-safety marker + persistent staging for a restore in flight
/// (docs/backup-restore-design.md §8.1, review finding B4).
///
/// The rollback snapshot used to live inside the importer's tmp work directory,
/// which a `defer` deletes — so it was guaranteed to be gone or unreachable on
/// the exact failure it existed for. Two consequences: a restore killed
/// mid-flight left the live directories half-overwritten with no recovery path,
/// and nothing on the next launch even knew a restore had been interrupted.
///
/// This moves the snapshot to Application Support (persistent, not purged like
/// tmp) and writes a small marker around the restore so the next launch can
/// reconcile.
enum BackupRestoreJournal {

    private static var supportDir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                            in: .userDomainMask)[0]
        return base.appendingPathComponent("BackupRestore", isDirectory: true)
    }

    private static var markerURL: URL {
        supportDir.appendingPathComponent("in-progress.json")
    }

    /// Snapshot directory for one restore run.
    ///
    /// [review I3] Per-run rather than a fixed path. It used to be a single
    /// shared `rollback/` directory, while `finish()` deletes the whole thing
    /// on a `defer` — so two overlapping restores clobbered each other's
    /// snapshots, and whichever finished first deleted the other's only
    /// recovery state. Overlap is reachable: every call site builds a FRESH
    /// `BackupImporter()` (actor isolation only serialises calls on one
    /// instance), the root-level restore sheet can be opened from any screen,
    /// and the debug RPC path is independent of both. `runId` keeps the paths
    /// disjoint even if the exclusion guard is ever bypassed.
    static func stagingRoot(runId: String) -> URL {
        supportDir.appendingPathComponent("rollback-\(runId)", isDirectory: true)
    }

    /// Every rollback staging directory currently on disk.
    static func allStagingRoots() -> [URL] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: supportDir.path)) ?? []
        return names.filter { $0.hasPrefix("rollback") }
            .map { supportDir.appendingPathComponent($0, isDirectory: true) }
    }

    struct Marker: Codable {
        var backupId: String
        var startedAt: Date
        var categories: [String]
    }

    static func begin(backupId: String, categories: [String]) {
        do {
            try FileManager.default.createDirectory(at: supportDir,
                                                    withIntermediateDirectories: true)
            let marker = Marker(backupId: backupId, startedAt: Date(), categories: categories)
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            try encoder.encode(marker).write(to: markerURL, options: .atomic)
        } catch {
            // A missing marker degrades crash reporting, not correctness — the
            // restore itself should still proceed.
            logger.warning("[Restore] couldn't write in-progress marker: \(error.localizedDescription)")
        }
    }

    /// Clear the marker and this run's snapshot after a clean finish.
    ///
    /// Scoped to `runId` so a concurrent restore's staging survives (I3).
    static func finish(runId: String) {
        try? FileManager.default.removeItem(at: markerURL)
        try? FileManager.default.removeItem(at: stagingRoot(runId: runId))
    }

    /// A restore that never completed, if any.
    static func interrupted() -> Marker? {
        guard let data = try? Data(contentsOf: markerURL) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(Marker.self, from: data)
    }

    /// Called at launch. Reports an interrupted restore and restores whatever
    /// snapshot survived.
    ///
    /// Deliberately conservative: it puts back the directories it has a
    /// complete copy-aside for and then clears the marker. It does NOT try to
    /// undo the DB-backed categories — those are Merge/LWW and idempotent, so
    /// a half-applied merge is a superset of the previous state rather than a
    /// corruption, and re-running the same restore converges.
    @discardableResult
    static func reconcileAtLaunch() -> Marker? {
        guard let marker = interrupted() else {
            // No marker, but staging can still be here: a restore that died
            // before writing the marker, or after clearing it, leaves rollback
            // directories nothing will ever look at again. They hold full
            // copies of the categories being replaced, so for shared files or
            // chats that is the entire directory — silently, for good.
            //
            // Safe to delete precisely BECAUSE there is no marker: the marker
            // is what says a restore is mid-flight and its snapshots are still
            // needed. Without one there is nothing to roll back to.
            let fm = FileManager.default
            var swept = 0
            for root in allStagingRoots() {
                let bytes = (try? root.resourceValues(forKeys: [.totalFileAllocatedSizeKey]))?
                    .totalFileAllocatedSize ?? 0
                try? fm.removeItem(at: root)
                swept += 1
                logger.info("[Restore] swept orphaned rollback staging (\(bytes) bytes)")
            }
            if swept > 0 {
                logger.info("[Restore] swept \(swept) orphaned rollback director(ies)")
            }
            return nil
        }
        logger.error("[Restore] previous restore did NOT complete: backupId=\(marker.backupId) startedAt=\(marker.startedAt) categories=\(marker.categories.joined(separator: ","))")

        let fm = FileManager.default
        var restored = 0
        // Sweep EVERY staging root, not just one: roots are per-run now, and a
        // crash can leave more than one behind.
        for root in allStagingRoots() {
            for name in (try? fm.contentsOfDirectory(atPath: root.path)) ?? [] {
                let saved = root.appendingPathComponent(name, isDirectory: true)

                // [review I4] File-backed snapshots (providers' single
                // provider-config.json) record their live target in a sidecar,
                // because a plain file has nowhere to put a `.live-path` child.
                // Before this, reconcile skipped them outright — so the
                // crash-recovery path from B4 did not cover the category B3 had
                // just given a rollback to, and the two fixes gave less
                // protection together than either appeared to alone.
                if name.hasSuffix(Self.fileSnapshotSidecarSuffix) {
                    guard let livePath = try? String(contentsOf: saved, encoding: .utf8),
                          !livePath.isEmpty else { continue }
                    let payload = root.appendingPathComponent(
                        String(name.dropLast(Self.fileSnapshotSidecarSuffix.count)))
                    guard fm.fileExists(atPath: payload.path) else { continue }
                    let live = URL(fileURLWithPath: livePath)
                    if fm.fileExists(atPath: live.path) {
                        if (try? fm.replaceItemAt(live, withItemAt: payload)) != nil { restored += 1 }
                    } else if (try? fm.moveItem(at: payload, to: live)) != nil {
                        restored += 1
                    }
                    continue
                }

                // Snapshot dirs are named "<category>-<lastPathComponent>"; the
                // live target is recorded alongside so reconciliation doesn't
                // have to re-derive it.
                guard let liveData = try? Data(contentsOf: saved.appendingPathComponent(".live-path")),
                      let livePath = String(data: liveData, encoding: .utf8), !livePath.isEmpty
                else { continue }
                let live = URL(fileURLWithPath: livePath)
                if (try? atomicReplace(directory: saved, onto: live)) != nil { restored += 1 }
            }
            try? fm.removeItem(at: root)
        }
        logger.info("[Restore] launch reconcile: restored \(restored) snapshot(s)")
        try? fm.removeItem(at: markerURL)
        return marker
    }

    /// Suffix for the sidecar that records a FILE snapshot's live target.
    static let fileSnapshotSidecarSuffix = ".live-path-of"

    /// Replace `live` with `saved` without a window where neither exists.
    ///
    /// The previous `removeItem` + `copyItem` pair left the user's directory
    /// deleted and not yet replaced if the process died between the two — for
    /// the shared-files category that is the whole directory. `replaceItemAt`
    /// is the atomic swap this needs.
    static func atomicReplace(directory saved: URL, onto live: URL) throws {
        let fm = FileManager.default
        // replaceItemAt needs the replacement to sit next to the target, and it
        // consumes it, so work from a copy.
        let staged = live.deletingLastPathComponent()
            .appendingPathComponent(".restore-swap-\(UUID().uuidString)", isDirectory: true)
        try? fm.removeItem(at: staged)
        try fm.copyItem(at: saved, to: staged)
        // Don't carry the bookkeeping file into the live tree.
        try? fm.removeItem(at: staged.appendingPathComponent(".live-path"))
        if fm.fileExists(atPath: live.path) {
            _ = try fm.replaceItemAt(live, withItemAt: staged)
        } else {
            try fm.moveItem(at: staged, to: live)
        }
    }
}
