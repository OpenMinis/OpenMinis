// SessionFileChangeTracker.swift
//
// Receives file change events from the iSH fakefs bind-mount tracker
// (`fakefs_record_change` in deps/ish/fs/fake.c) via ISHKernel, dedupes
// them per (sessionId, relPath), and exposes drain APIs for SyncCore to
// translate them into SessionFile dirty rows just before push.
//
// Design:
//   - Producer: ISHKernel handler queue → `recordBatch(_:)`.
//   - Storage: in-memory dict, last-write-wins per relPath.
//   - Consumer: SyncCore.sendNow() calls `drainAll()` before scanning
//     sync_dirty_records, so any pending file changes turn into
//     SessionFile dirty rows for this push cycle.
//
// Concurrency: actor isolates producer/consumer. Producers `await` to
// record (cheap; producer lives on a serial queue and accepts arbitrary
// latency since the C-side ring buffer absorbs bursts). The consumer
// drains and clears atomically — events recorded between drain and
// markDirty stay in the next snapshot, never lost.

import Foundation
import OSLog

actor SessionFileChangeTracker {
    static let shared = SessionFileChangeTracker()

    private let logger = AppLogger(category: "SessionFileTracker")

    enum Op {
        case upsert
        case delete
    }

    struct Change {
        let op: Op
        let lastTouchedAt: Date
    }

    /// sessionId → relPath → Change
    private var pending: [String: [String: Change]] = [:]

    /// Total events received (diagnostic).
    private(set) var totalRecorded: UInt64 = 0
    /// Total events ignored (path didn't parse to sessionId+rel).
    private(set) var totalIgnored: UInt64 = 0

    private init() {}

    // MARK: - Producer

    /// Called by the ISHKernel handler block on its serial queue. Each
    /// event is a (sessionId, linuxPath, op, ts_ns) tuple — the sessionId
    /// is captured *synchronously* on the producer side (the ISHKernel
    /// handler block) at event-receive time, NOT here, because by the
    /// time this actor method runs the user could have switched sessions
    /// and the mount snapshot may have moved. See `installSessionFileChangeTracker`
    /// for where the sid is captured.
    func recordBatch(
        _ events: [(sid: String, rawPath: String, op: Int, tsNs: Int64)],
        minisBaseURL: URL
    ) {
        var accepted = 0, ignored = 0, upserts = 0, deletes = 0
        var firstSid: String? = nil
        var firstRel: String? = nil
        var firstIgnoredPath: String? = nil
        for (sid, rawPath, opCode, _) in events {
            totalRecorded &+= 1
            guard let rel = Self.resolveSessionRelative(
                rawPath: rawPath, sessionId: sid, minisBaseURL: minisBaseURL
            ) else {
                totalIgnored &+= 1
                ignored += 1
                if firstIgnoredPath == nil { firstIgnoredPath = rawPath }
                continue
            }
            let isDelete = (opCode == 1)
            let change = Change(
                op: isDelete ? .delete : .upsert,
                lastTouchedAt: Date()
            )
            pending[sid, default: [:]][rel] = change
            accepted += 1
            if isDelete { deletes += 1 } else { upserts += 1 }
            if firstSid == nil { firstSid = sid; firstRel = rel }
        }
        if accepted > 0 {
            let sample = "\(firstSid?.prefix(8) ?? "?"):\(firstRel ?? "?")"
            logger.info("[SessionFileTracker] batch recv: events=\(events.count) accepted=\(accepted) ignored=\(ignored) upsert=\(upserts) delete=\(deletes) sample=\(sample) pending_sessions=\(self.pending.count)")
        } else if ignored > 0 {
            // Bumped to info + sample so we can diagnose why otherwise
            // valid-looking shell writes never reach the SessionFile pipeline.
            logger.verbose("[SessionFileTracker] batch recv: events=\(events.count) all ignored (paths outside session subdirs) sample=\(firstIgnoredPath ?? "?")")
        }
    }

    // MARK: - Consumer

    /// Atomically read-and-clear all pending changes. Returns a snapshot
    /// of [sessionId: [relPath: Change]]. Caller (SyncCore) translates
    /// each entry into a markDirty call.
    func drainAll() -> [String: [String: Change]] {
        let snapshot = pending
        pending.removeAll(keepingCapacity: true)
        if !snapshot.isEmpty {
            let totalFiles = snapshot.values.reduce(0) { $0 + $1.count }
            logger.info("[SessionFileTracker] drainAll: sessions=\(snapshot.count) files=\(totalFiles)")
        }
        return snapshot
    }

    /// Drain entries whose host file shows real modification, AND keep
    /// entries whose mtime hasn't caught up yet (the open() was recorded
    /// but the actual write may still be in flight). Used by SyncCore
    /// instead of `drainAll()` to suppress false positives where a
    /// process opened a file write-mode but never wrote anything.
    ///
    /// Rules:
    ///   • op = .delete → always drain (file is gone, stat will fail)
    ///   • op = .upsert + file missing → drain anyway (race vs delete);
    ///     downstream markDirty + buildPortable will skip if file doesn't
    ///     exist by the time it builds the record
    ///   • op = .upsert + mtime >= lastTouchedAt - 1s → real write,
    ///     drain
    ///   • op = .upsert + mtime < lastTouchedAt - 1s → write may still
    ///     be in flight, KEEP in pending for next drain
    ///
    /// The 1s slack covers clock skew between open-time wall clock and
    /// host-FS mtime resolution. Also covers cases where the kernel
    /// flushes mtime slightly *before* we record the open (very tight
    /// schedule races).
    func drainAllWithMtimeFilter(minisBaseURL: URL) -> [String: [String: Change]] {
        guard !pending.isEmpty else { return [:] }
        var drained: [String: [String: Change]] = [:]
        var keptCount = 0
        var droppedFalsePositive = 0
        var totalUpsert = 0, totalDelete = 0
        let now = Date()

        for (sid, files) in pending {
            var sidDrained: [String: Change] = [:]
            var sidKept: [String: Change] = [:]
            for (rel, change) in files {
                if change.op == .delete {
                    sidDrained[rel] = change
                    totalDelete += 1
                    continue
                }
                let hostURL = minisBaseURL
                    .appendingPathComponent(sid, isDirectory: true)
                    .appendingPathComponent(rel)
                let attrs = try? FileManager.default.attributesOfItem(atPath: hostURL.path)
                guard let mtime = attrs?[.modificationDate] as? Date else {
                    // File missing — let downstream resolve the race.
                    sidDrained[rel] = change
                    totalUpsert += 1
                    continue
                }
                if mtime >= change.lastTouchedAt.addingTimeInterval(-1.0) {
                    sidDrained[rel] = change
                    totalUpsert += 1
                } else {
                    // Open recorded but mtime hasn't caught up. Either
                    // the process opened-then-skipped-the-write (false
                    // positive) or the write is still in flight. Keep
                    // for next drain; if it stays unchanged for several
                    // cycles the entry effectively "decays" out.
                    let age = now.timeIntervalSince(change.lastTouchedAt)
                    if age > 30.0 {
                        // Stale enough to be confident it was a no-op
                        // open. Drop without queuing markDirty.
                        droppedFalsePositive += 1
                    } else {
                        sidKept[rel] = change
                        keptCount += 1
                    }
                }
            }
            if !sidDrained.isEmpty { drained[sid] = sidDrained }
            if sidKept.isEmpty {
                pending.removeValue(forKey: sid)
            } else {
                pending[sid] = sidKept
            }
        }

        if !drained.isEmpty || keptCount > 0 || droppedFalsePositive > 0 {
            logger.info("[SessionFileTracker] drainAllWithMtimeFilter: drained=\(totalUpsert + totalDelete) (upsert=\(totalUpsert) delete=\(totalDelete)) kept_pending=\(keptCount) dropped_no_op=\(droppedFalsePositive)")
        }
        return drained
    }

    /// Drain only one session's pending changes. Used by deletion paths
    /// (deleteSession, FileBrowser delete) so the explicit op=delete
    /// markDirty wins over a stale upsert sitting in the tracker.
    func drainSession(_ sessionId: String) -> [String: Change] {
        return pending.removeValue(forKey: sessionId) ?? [:]
    }

    /// Diagnostic snapshot of how many sessions / how many files are
    /// currently buffered. Cheap; for debug RPC.
    func snapshot() -> (sessionCount: Int, fileCount: Int) {
        let files = pending.values.reduce(0) { $0 + $1.count }
        return (pending.count, files)
    }

    // MARK: - Path parsing

    /// Resolve a path emitted by the iSH realfs hook into a SessionFile
    /// relative path ("<subdir>/<rel>"), or return nil to ignore.
    ///
    /// realfs may surface either of two path shapes for the same logical
    /// file:
    ///
    ///   • Guest path:  `/var/minis/<subdir>/<rel>` (fakefs path-normalize
    ///     left the bind-mount symlink alone, e.g. unlink/rename)
    ///   • Host path:   `<minisBaseURL>/<sid>/<subdir>/<rel>` (path-normalize
    ///     dereferenced the symlink — common for open() of a regular file
    ///     under a bind mount)
    ///
    /// The mapping decision lives entirely on the Swift side so iSH
    /// stays unaware of the "session" concept. The expected sessionId
    /// is passed in from the producer (captured at event-receive time
    /// from ISHExecutionCoordinator's mount snapshot).
    nonisolated static func resolveSessionRelative(
        rawPath: String, sessionId: String, minisBaseURL: URL
    ) -> String? {
        // Guest-shape: trivial prefix strip.
        let guestPrefix = "/var/minis/"
        if rawPath.hasPrefix(guestPrefix) {
            let after = String(rawPath.dropFirst(guestPrefix.count))
            return validateAndReturnSubpath(after)
        }

        // Host-shape: must live under this session's per-session minis dir.
        // We use the *current* sessionId (capture happens at producer
        // time), so a path like ".../<sid>/workspace/foo.txt" matches.
        // /var/folders symlink resolution and similar host quirks: we
        // intentionally do a string-prefix check here without
        // resolvingSymlinksInPath because that would block the consumer
        // queue on disk syscalls. iOS sandbox paths are stable enough
        // that string matching is sufficient.
        let hostBase = minisBaseURL.path
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        // Match "<hostBase>/<sid>/" — accept both leading-slash and
        // /private-prefixed variants (iOS resolves /var → /private/var).
        let candidates = [
            "/\(hostBase)/\(sessionId)/",
            "/private/\(hostBase)/\(sessionId)/",
        ]
        for cand in candidates {
            if rawPath.hasPrefix(cand) {
                let after = String(rawPath.dropFirst(cand.count))
                return validateAndReturnSubpath(after)
            }
        }
        return nil
    }

    /// Validate that "<subdir>/<rest>" is one of the session-scoped
    /// SessionFile subdirs. memory/skills/shared/mounts have their own
    /// sync mechanisms and do NOT produce SessionFile rows.
    private nonisolated static func validateAndReturnSubpath(_ subpath: String) -> String? {
        guard let slash = subpath.firstIndex(of: "/") else { return nil }
        let subdir = String(subpath[..<slash])
        let scoped: Set<String> = ["workspace", "attachments", "browser", "offloads"]
        guard scoped.contains(subdir) else { return nil }
        return subpath
    }
}

// MARK: - ISHKernel installation

/// Install the iSH fakefs change handler that pumps events into the
/// tracker actor. Call once at app launch after ISHKernel has booted.
///
/// iSH stays agnostic about Minis sessions — this Swift bridge knows the
/// per-session host directory layout (`<minisBaseURL>/<sid>/<subdir>/...`)
/// and the bind-mount guest layout (`/var/minis/<subdir>/...`), and resolves
/// either shape to a SessionFile recordId on the consumer (actor) side.
func installSessionFileChangeTracker(kernel: ISHKernel) {
    let installLogger = AppLogger(category: "SessionFileTracker")
    installLogger.info("[SessionFileTracker] installing fakefs change handler — iSH realfs writes/unlinks/renames under /var/minis/{workspace,attachments,browser,offloads} (or the equivalent host APFS path) will be queued for iCloud SessionFile sync")
    // Captured at install time; minisBaseURL doesn't change for the
    // lifetime of the app process.
    let minisBaseURL = ChatStore.shared.minisBaseURL
    kernel.installFakefsChangeHandler { events in
        // Block runs on ISHKernel's private serial queue. Each event carries
        // the fs_context u64 that was stamped on the task group that issued
        // the write/delete; MinisFsRouter maps that back to the sid that
        // owns the per-session bucket. Events from skills/shared/memory
        // (no per-session routing → fs_context = 0) fall back to the most
        // recently active session — those buckets are global so the SessionFile
        // ownership is approximate by design.
        guard !events.isEmpty else { return }
        let fallbackSid = ISHExecutionCoordinator.mountedSessionIdSnapshot
        var tuples: [(sid: String, rawPath: String, op: Int, tsNs: Int64)] = []
        tuples.reserveCapacity(events.count)
        for event in events {
            let resolvedSid: String?
            if event.fsContext != 0 {
                resolvedSid = MinisFsRouter.shared.sid(for: event.fsContext) ?? fallbackSid
            } else {
                resolvedSid = fallbackSid
            }
            guard let sid = resolvedSid else { continue }
            tuples.append((sid, event.linuxPath, Int(event.op), event.timestampNs))
        }
        if !tuples.isEmpty {
            Task.detached(priority: .utility) {
                await SessionFileChangeTracker.shared.recordBatch(tuples, minisBaseURL: minisBaseURL)
            }
        }

        // Skills side-channel: if any event in this batch touched a
        // path under `/var/minis/skills/` (or the equivalent host APFS
        // path), kick the debounced skill reload. SkillStore owns the
        // truth — we just nudge it to re-scan disk + markDirty any
        // newly-discovered / modified / deleted skills it spots.
        // Done synchronously here (cheap path check) so we don't lose
        // the signal if the consumer queue is backed up.
        if FakefsSkillPathCheck.batchTouchesSkills(events: events, minisBaseURL: minisBaseURL) {
            SkillFilesystemNotifier.shared.notifyChanged()
        }
    }
}

/// Helper sitting outside the actor so it can be called from the
/// non-isolated installFakefsChangeHandler block.
fileprivate enum FakefsSkillPathCheck {
    static func batchTouchesSkills(events: [ISHFakefsChangeEvent], minisBaseURL: URL) -> Bool {
        let guestPrefix = "/var/minis/skills"
        // host APFS path looks like ".../MinisChat/minis/skills/..."
        // — minisBaseURL = "<lib>/MinisChat/minis", so the host prefix
        //   to check is "<minisBaseURL>/skills".
        let hostPrefix = minisBaseURL.standardizedFileURL.path + "/skills"
        for evt in events {
            let p = evt.linuxPath
            if p.hasPrefix(guestPrefix) { return true }
            if p.hasPrefix(hostPrefix) { return true }
            // /private prefix variant on iOS.
            if hostPrefix.hasPrefix("/var/")
                && p.hasPrefix("/private" + hostPrefix) { return true }
        }
        return false
    }
}

/// Marks "skills directory changed; rescan owed" and drains the flag
/// at known-idle moments instead of on a fixed debounce timer. Keeps
/// the rescan + markDirty work off the critical path while an AI turn
/// is processing, since that's when iSH bursts hit the hardest.
///
/// Drain triggers:
///   • AI turn finishes (AIChatViewModel observes isProcessing
///     transition and calls drainIfDirty)
///   • App scene goes to background / inactive
///   • Fallback debounce: 60s after the last notify, if no other drain
///     trigger has fired (so a session that never finishes streaming
///     still eventually rescans)
///
/// All public entry points are nonisolated thunks that hop onto
/// MainActor before touching state.
@MainActor
final class SkillFilesystemNotifier {
    static let shared = SkillFilesystemNotifier()
    private init() {}

    /// True when at least one fakefs event touching /var/minis/skills/
    /// has landed since the last successful drain. Caller-side drain
    /// triggers check this; rescan only runs when this is true.
    private var pendingRescan: Bool = false
    /// Wall-clock time of the most recent notify, kept so logging can
    /// report how long a rescan has been queued.
    private var lastNotifyAt: Date?
    /// Fallback timer Task — only used if neither AI-turn-end nor
    /// scenePhase drains arrive within 60s.
    private var fallbackTask: Task<Void, Never>?
    private let fallbackInterval: TimeInterval = 60

    /// Hook entry. Called from the fakefs handler block on any thread.
    /// Sets the dirty flag and (re)arms the fallback timer; does NOT
    /// run a rescan synchronously.
    nonisolated func notifyChanged() {
        Task { @MainActor in self.markDirty() }
    }

    private func markDirty() {
        if !pendingRescan {
            AppLogger(category: "SkillSync").info("[SkillSync] fakefs notifier → pendingRescan=true (waiting for idle drain)")
        }
        pendingRescan = true
        lastNotifyAt = Date()
        // Restart fallback timer so a quiet session still gets a
        // rescan even if no other drain trigger fires.
        fallbackTask?.cancel()
        fallbackTask = Task { @MainActor [weak self] in
            let nanos = UInt64((self?.fallbackInterval ?? 60) * 1_000_000_000)
            try? await Task.sleep(nanoseconds: nanos)
            if Task.isCancelled { return }
            self?.drainIfDirty(reason: "fallback timer")
        }
    }

    /// Called from idle moments — AI turn done, scenePhase change,
    /// foreground reload, etc. Cheap no-op when `pendingRescan` is
    /// false. Safe to call as often as you want; the rescan body
    /// itself only runs once per dirty cycle.
    func drainIfDirty(reason: String) {
        guard pendingRescan else { return }
        pendingRescan = false
        fallbackTask?.cancel()
        let queuedFor = lastNotifyAt.map { Int(Date().timeIntervalSince($0)) } ?? -1
        AppLogger(category: "SkillSync").info("[SkillSync] drain triggered (reason=\(reason), queued for \(queuedFor)s)")
        SkillStore.shared.rescanAndMarkChangedSkillsDirty()
    }

    /// Same as drainIfDirty but exposed as a nonisolated thunk for
    /// callers that can't await MainActor inline. Asynchronous; OK to
    /// fire-and-forget.
    nonisolated func drainIfDirtyAsync(reason: String) {
        Task { @MainActor in self.drainIfDirty(reason: reason) }
    }
}
