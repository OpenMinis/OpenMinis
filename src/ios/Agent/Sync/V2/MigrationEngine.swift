import Foundation

private let logger = AppLogger(category: "SyncMigration")

/// Status of the v1 → v2 migration. Persisted to UserDefaults so it
/// survives app restarts; the in-progress detail lives in
/// migration-state.json.
enum MigrationStatus: String, Codable {
    case pending
    case inProgress = "in_progress"
    case completed
    case failed
}

/// Per-phase state machine. Each phase persists its checkpoint so a crash
/// in the middle restarts at the last completed boundary.
enum MigrationPhase: String, Codable {
    case detect            // 0: just decided to migrate
    case v1FullFetch       // 1: fetch every device-* zone via v1 fetcher
    case v2ZonesCreate     // 2: ensure minis-shared / -devices / -secrets exist
    case v2InitialPush     // 3: push every local row to v2 zones
    case v1ZoneDelete      // 4: delete THIS device's device-<id> zone
    case lock              // 5: write completed flag
    case completed         // 6: terminal — never re-enter
}

/// Persisted migration state. Lives in
/// Library/MinisChat/cloud-sync-v2/migration-state.json.
struct MigrationState: Codable {
    var status: MigrationStatus
    var phase: MigrationPhase
    var startedAt: Date?
    var lastUpdatedAt: Date
    /// Phase-specific progress (e.g. "Messages.pushed=400/1289").
    var progress: [String: Int]
    /// Last error if status == .failed.
    var lastError: String?
    /// Consecutive failures in current phase (for backoff cap).
    var phaseFailureCount: Int
}

/// Coordinates the v1 → v2 migration. Run once per device, on app start.
///
/// Public entrypoints:
///
///   - `currentStatus()` — synchronous read; UI uses this to gate the
///     iCloud Sync settings page.
///   - `runIfNeeded()` — async; called from app launch after v1 paused
///     and SyncableTypeRegistry populated. Runs the full state machine,
///     resuming from wherever a previous run stopped.
///
/// During migration, v1 sync MUST be stopped (caller's responsibility —
/// MigrationEngine doesn't reach into v1 to disable it because v1 is
/// not aware of v2's existence). Only ad-hoc v1 fetch happens via the
/// minimal fetcher in `V1FetcherShim` below.
@MainActor
final class MigrationEngine {
    static let shared = MigrationEngine()
    private init() {
        let dir = FileManager.default
            .urls(for: .libraryDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MinisChat/cloud-sync-v2", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        self.stateURL = dir.appendingPathComponent("migration-state.json")
        self.statusKey = "cloudSync.v2.migrationStatus"
    }

    private let stateURL: URL
    private let statusKey: String

    /// Cap on consecutive phase failures before declaring `failed`.
    private let maxConsecutiveFailures = 5
    /// [T-icloud-migration-suspended-ui] Set when a phase defers, cleared the
    /// moment one completes. Drives `ProgressSummary.isSuspended`.
    fileprivate static let suspendedKey = "cloudSync.v2.migrationSuspended"

    /// Re-entrancy guard. The migration can be kicked from two places that
    /// may overlap: the launch path (SyncV2Bootstrap.startIfEnabled) and the
    /// user tapping "Start / Retry Migration" in the Sync sheet. Two state
    /// machines mutating the same migration-state.json + dirty queue race and
    /// can leave half-written state that makes the NEXT retry early-exit on a
    /// stale phase — a plausible "retry keeps failing" cause with no logs.
    /// [T-ios-icloud-v1v2-migration-fails]
    private var isRunning = false

    // MARK: - Public

    /// Persistent status — single source of truth for "is v2 running?".
    /// Survives crashes / restarts via UserDefaults.
    func currentStatus() -> MigrationStatus {
        let raw = UserDefaults.standard.string(forKey: statusKey) ?? MigrationStatus.pending.rawValue
        return MigrationStatus(rawValue: raw) ?? .pending
    }

    /// Lightweight summary for UI display while migration is running.
    /// Returns nil when status is `completed` (no banner to show).
    /// `done` / `total` are derived from the V2 dirty queue: `total` is
    /// snapshotted on the first call and persisted, `done = total - currentDirty`.
    /// Best-effort, monotonic; if `currentDirty` somehow exceeds the snapshot,
    /// `done` is clamped to 0.
    struct ProgressSummary {
        let status: MigrationStatus
        let phase: MigrationPhase
        let pushDone: Int
        let pushTotal: Int
        let v1Deleted: Int
        let v1DeletePending: Int
        /// Snapshot of how many records this device's v1 zone held when
        /// migration started (recorded by v1FullFetch). nil if migration
        /// began before the snapshot was added — UI should fall back to
        /// "unknown".
        let v1ZoneTotalAtStart: Int?
        /// Approximate records still on the v1 zone for THIS device:
        /// `v1ZoneTotalAtStart - v1Deleted`, clamped ≥ 0. nil if total is
        /// unknown.
        let v1ZoneRemaining: Int?
        let lastError: String?
        /// [T-icloud-migration-suspended-ui] True when the phase deferred and
        /// nothing is actively driving the migration until the next cold start.
        ///
        /// `status` alone cannot express this: a deferral deliberately leaves it
        /// `.inProgress` (it is not a failure), so the sheet rendered "in
        /// progress" over a frozen counter and an ETA that assumed active
        /// pushing. That combination is exactly what OpenMinis#141 reported as
        /// "stuck" — the migration was suspended by design, but nothing on
        /// screen said so, and the one action that would have resumed it
        /// (relaunching the app) was the one the user had no reason to take.
        let isSuspended: Bool
    }

    /// Cancel an in-progress migration. Drops the priority=1 backlog from
    /// the dirty queue so we stop pushing legacy data, flips the
    /// `migrationRequested` flag back off, and records the un-pushed
    /// count so the Sync sheet can show "X records not migrated" until
    /// the user requests again. Already-uploaded V2 records stay on the
    /// server (no zone-level cleanup — irreversible deletion isn't worth
    /// the round trips for an opt-in cancel).
    func cancelMigration() async {
        let dropped = await ChatStore.shared.clearMigrationBacklog()
        SyncV2Bootstrap.setMigrationRequested(false)
        UserDefaults.standard.set(dropped, forKey: "cloudSync.v2.migrationCanceledRemaining")
        // Stop the historical backlog scan: ask the running pagination loop to
        // bail out at its next checkpoint via the ChatStore-side cancel flag.
        // Without this, runHistoricalBacklogScan keeps walking older sessions
        // and re-staging priority=1 dirty rows, which immediately undoes the
        // clearMigrationBacklog() call above — the user sees migration "still
        // running" because it really is.
        //
        // [T-icloud-migration-restage-storm] The cursor is deliberately LEFT
        // ALONE. It used to be zeroed here, and zero means "fully drained" to
        // runHistoricalBacklogScan (`guard cursor > 0`). That was survivable
        // only because requestMigration then cleared the cursor and the
        // Phase-A guard, re-staging the entire corpus — the very storm that
        // made this migration unable to converge. Now that retry PRESERVES
        // both, zeroing here would instead make Phase B believe it had
        // finished and silently skip every session older than the cancel
        // point. Cancelling stops the scan; it must not erase where it got to.
        UserDefaults.standard.set(true, forKey: "cloudSync.v2.backlogScanCanceled")
        // Mark migration state as a non-error stop so progressSummary
        // returns nil and the Migration section disappears.
        UserDefaults.standard.set(MigrationStatus.completed.rawValue, forKey: statusKey)
        if var s = loadState() {
            s.status = .completed
            s.lastUpdatedAt = Date()
            saveState(s)
        }
        logger.info("[SyncMigration] migration canceled by user — dropped \(dropped) backlog rows")
    }

    /// Re-enable migration after a previous cancel or failure. Caller
    /// (UI) is expected to schedule MigrationEngine.runIfNeeded()
    /// afterward.
    func requestMigration() {
        SyncV2Bootstrap.setMigrationRequested(true)
        UserDefaults.standard.removeObject(forKey: "cloudSync.v2.migrationCanceledRemaining")
        // Clear the cancel-scan flag so the historical backlog scan
        // (paused by a previous Cancel Migration tap) can resume on
        // its next kick. The `initialBacklogMarked` guard also needs
        // to relax so markAllLocalForReupload restages Phase A — we
        // do that by clearing it; markAllLocalForReupload will set it
        // again after re-staging. Same for the cursor sentinel: 0
        // means "drained" to runHistoricalBacklogScan, but Phase A
        // will set a fresh cursor at the recent-window boundary.
        UserDefaults.standard.set(false, forKey: "cloudSync.v2.backlogScanCanceled")
        // [T-icloud-migration-restage-storm] Deliberately NOT clearing
        // `initialBacklogMarked` or `backlogScanCursorAt` any more.
        //
        // Clearing them made every Cancel→Retry re-run Phase A from scratch and
        // rewind Phase B to the start of history, re-marking the ENTIRE local
        // corpus dirty. Users watched the record count climb by thousands per
        // round and the migration could never converge — the reason OpenMinis#154
        // was filed ("keeps adding thousands of records when cancelling ... and
        // restarting"). Under CloudKit throttling that is unwinnable: each retry
        // enqueues more than the window can drain.
        //
        // Preserving both is safe because neither is the record of what still
        // needs pushing. Anything genuinely un-pushed is still dirty (cancel only
        // drops the priority=1 backlog rows, and Phase B resumes from its saved
        // cursor), and `sync_pushed_records` remains the authority on what has
        // already been saved to iCloud. A full re-stage is still available
        // deliberately via resetMigrationProgress().
        // Rewind any failed-state phase so the engine retries from a
        // safe earlier checkpoint instead of immediately re-failing the
        // last phase. v1ZoneDelete's C4 safeguard can permanently abort
        // when cloud V2 count is 0; resetting to v2InitialPush makes the
        // retry actually push records before attempting deletion again.
        if var state = loadState() {
            state.status = .pending
            state.lastError = nil
            state.phaseFailureCount = 0
            // Don't rewind a phase that was already past v2InitialPush
            // unless it was the failing one.
            if state.phase == .v1ZoneDelete || state.phase == .lock || state.phase == .completed {
                state.phase = .v2InitialPush
            }
            state.lastUpdatedAt = Date()
            saveState(state)
        }
        UserDefaults.standard.set(MigrationStatus.pending.rawValue, forKey: statusKey)
        logger.info("[SyncMigration] migration requested by user — state reset to retry-safe checkpoint")
    }

    /// [T-icloud-migration-reset] Throw away all LOCAL migration progress so the
    /// next attempt starts from a clean slate. OpenMinis#154's actual request.
    ///
    /// Clears: the phase/status state machine, the pushed-record ledger, both
    /// backlog staging guards, and the derived progress counters — i.e. every
    /// piece of local bookkeeping that can leave a migration wedged in a state
    /// no retry escapes (a mismatched `41 / 14` counter, a phase that keeps
    /// re-entering the same failing check).
    ///
    /// IMPORTANT — this is NOT "delete my iCloud data". Nothing on the server is
    /// touched: records already uploaded stay, and the v1 zone is untouched
    /// (that is the separate, irreversible "Force Delete V1 Zone" action). The
    /// worst case here is re-uploading records iCloud already has, which
    /// CloudKit absorbs as no-op saves. Keeping the destructive and
    /// non-destructive resets clearly apart matters: #154's reporter had already
    /// been pushed into the irreversible one by a stuck migration.
    ///
    /// Does not start a migration — the caller decides whether to re-request.
    func resetMigrationProgress() async {
        let clearedBacklog = await ChatStore.shared.clearMigrationBacklog()
        let clearedPushed = await ChatStore.shared.clearAllPushedRecords()

        // Phase/progress state is a file, not a default.
        try? FileManager.default.removeItem(at: stateURL)
        UserDefaults.standard.removeObject(forKey: statusKey)
        // Staging guards: cleared HERE (unlike requestMigration, which now
        // preserves them) because a full reset is exactly when re-staging the
        // whole corpus is the intended behaviour.
        UserDefaults.standard.removeObject(forKey: "cloudSync.v2.initialBacklogMarked")
        UserDefaults.standard.removeObject(forKey: "cloudSync.v2.backlogScanCursorAt")
        UserDefaults.standard.set(false, forKey: "cloudSync.v2.backlogScanCanceled")
        // Derived counters — stale values here are what produce impossible
        // readouts like "41 / 14 (100%)" after the local corpus shrinks.
        UserDefaults.standard.removeObject(forKey: "cloudSync.v2.pushedCumulative")
        UserDefaults.standard.removeObject(forKey: "cloudSync.v2.v1RecordsDeleted")
        UserDefaults.standard.removeObject(forKey: "cloudSync.v2.v1RecordsDeletePending")
        UserDefaults.standard.removeObject(forKey: "cloudSync.v2.estimatedExtraSessionFiles")
        UserDefaults.standard.removeObject(forKey: "cloudSync.v2.migrationCanceledRemaining")
        UserDefaults.standard.removeObject(forKey: Self.suspendedKey)
        SyncV2Bootstrap.setMigrationRequested(false)

        logger.warning("[SyncMigration] migration progress RESET by user — droppedBacklog=\(clearedBacklog) clearedPushedLedger=\(clearedPushed); server-side data untouched")
    }

    /// True when the user explicitly canceled an in-progress migration
    /// and never re-requested it. Used by the Sync sheet to surface a
    /// Retry Migration button when the user wants to resume.
    func isCanceledByUser() -> Bool {
        // Migration was canceled iff: requested flag is now false AND we
        // have a recorded canceledRemaining count (i.e. canceled, not
        // just never started). Fresh installs have neither.
        let requested = SyncV2Bootstrap.isMigrationRequested
        let canceledRemaining = UserDefaults.standard.integer(forKey: "cloudSync.v2.migrationCanceledRemaining")
        return !requested && canceledRemaining > 0
    }

    /// If the last migration attempt ended in `.failed` (e.g. C4
    /// safeguard aborted v1ZoneDelete), return its error message for
    /// the Historical Data section. Returns nil for other statuses.
    func lastErrorIfFailed() -> String? {
        guard currentStatus() == .failed else { return nil }
        return loadState()?.lastError
    }

    /// Approximate count of pre-v2 local records that would be uploaded
    /// if migration ran now. Used by the Sync sheet's "Historical Data"
    /// row when migration is opt-in (not yet running).
    func unmigratedHistoryCount() async -> Int {
        let local = await ChatStore.shared.countLocalV2Eligible()
        let pushed = UserDefaults.standard.integer(forKey: "cloudSync.v2.pushedCumulative")
        return max(0, local - pushed)
    }

    func progressSummary() async -> ProgressSummary? {
        // If the user has never explicitly requested migration, do NOT
        // surface a "migration in progress" banner. The default UserDefaults
        // value for statusKey is nil → currentStatus() returns .pending,
        // which would otherwise fall through below and render an active
        // banner with derived counters — making the app look like it's
        // auto-migrating even though runIfNeeded() will short-circuit on
        // the same isMigrationRequested gate.
        guard SyncV2Bootstrap.isMigrationRequested else { return nil }
        let status = currentStatus()
        // Both completed and failed are terminal for the active-progress
        // banner. failed gets surfaced separately as a "last attempt failed"
        // hint in the Historical Data section so the user can retry.
        if status == .completed || status == .failed { return nil }
        let state = loadState()
        let phase = state?.phase ?? .detect
        let counts = await ChatStore.shared.countDirtyRecords()
        let v2Dirty = counts.byType.filter { $0.key.hasSuffix("V2") }.values.reduce(0, +)
        // pushDone = cumulative pushes since migration started (persisted
        // by transport via cloudSync.v2.pushedCumulative). Uses an explicit
        // counter rather than `baseline - v2Dirty` so that user-driven new
        // writes during migration don't push the denominator past pushDone
        // and pin progress at 0%.
        // Backfill: on devices that have already pushed records under an
        // older build (no `pushedCumulative` counter), v1Deleted is a
        // conservative lower bound — every v1 deletion was triggered by
        // a confirmed V2 save.
        let v1Del = UserDefaults.standard.integer(forKey: "cloudSync.v2.v1RecordsDeleted")
        let cumulative = UserDefaults.standard.integer(forKey: "cloudSync.v2.pushedCumulative")
        // Distinct recordNames that have ever been saved on iCloud,
        // tracked in sync_pushed_records (INSERT OR IGNORE — set
        // semantics). This is what the UI should show; the old
        // `cloudSync.v2.pushedCumulative` was a save-event counter
        // that double-counted every relaunch's re-mark-dirty cycle.
        let pushedUnique = await ChatStore.shared.countPushedRecords()
        // pushDone preference order:
        //   1. pushedUnique — accurate when running on a build that
        //      populates sync_pushed_records (post-fix).
        //   2. v1RecordsDeleted — conservative lower bound for old
        //      builds that only ever wrote the legacy counter; every
        //      v1 deletion was triggered by a confirmed v2 save.
        //   3. cumulativePushEvents — last resort, inflated.
        let pushDone = max(pushedUnique, v1Del)

        // Denominator: real local record-count baseline rather than
        // pushDone+dirtyRemaining. The previous formula's denominator
        // shrank or jumped whenever markAllLocalForReupload re-staged
        // a fresh batch — UI users saw the "Records pushed" %
        // ratchet backwards. Using the actual local counts keeps it
        // monotonic.
        //   sessions / messages / compactMarkers come from SQLite
        //   counts (cheap).
        //   singletons = ProviderConfig + EnvVar(legacy).
        //   sessionFiles is filesystem-discovered, so we use a
        //     persistent running counter that Phase B bumps as it
        //     pages through history.
        //   skills + envVarItems pulled from their own stores via
        //     MainActor hops.
        let baseline = await ChatStore.shared.countBacklogBaseline()
        let estimatedFiles = UserDefaults.standard.integer(
            forKey: "cloudSync.v2.estimatedExtraSessionFiles")
        let skillCount = await MainActor.run { SkillStore.shared.skills.count }
        let envItemCount = await MainActor.run { EnvVarStore.shared.entries.count }
        let rawPushTotal = baseline.sessions + baseline.messages
            + baseline.compactMarkers + baseline.singletons
            + estimatedFiles + skillCount + envItemCount
        // [T-icloud-migration-progress-overflow] The numerator is CUMULATIVE
        // (records ever saved to iCloud, from sync_pushed_records) while the
        // denominator is a LIVE local row count. Delete conversations mid-
        // migration and the denominator collapses below the numerator —
        // OpenMinis#154 shipped a screenshot reading "41 / 14 (100%)" after the
        // user cleared everything trying to make the migration finish.
        // Clamping keeps the readout coherent; it does not paper over a stall,
        // because the phase/status lines next to it still show where it is.
        let pushTotal = max(rawPushTotal, pushDone)
        logger.info("[SyncMigration] progressSummary inputs: cumulativePushEvents=\(cumulative) pushedUnique=\(pushedUnique) v1RecordsDeleted=\(v1Del) v2DirtyRemaining=\(v2Dirty) baseline(sess=\(baseline.sessions) msg=\(baseline.messages) cm=\(baseline.compactMarkers) singl=\(baseline.singletons)) skills=\(skillCount) envItems=\(envItemCount) estFiles=\(estimatedFiles) → pushDone=\(pushDone) pushTotal=\(pushTotal)")
        let v1Deleted = UserDefaults.standard.integer(forKey: "cloudSync.v2.v1RecordsDeleted")
        let v1Pending = UserDefaults.standard.integer(forKey: "cloudSync.v2.v1RecordsDeletePending")
        // v1FullFetch records the total record count it observed in this
        // device's v1 zone(s). Use it as the baseline for "records still
        // on v1 zone" — that subtraction is monotonically non-increasing
        // since v2 deletes only roll forward. Old migrations (pre-this
        // commit) may have completed without recording the value; keep
        // nil so the UI can render "unknown" rather than a misleading 0.
        let v1Total = state?.progress["v1FullFetch.records"]
        // v1ZoneRemaining preference order:
        //   1. latest "live" count from a real CKQuery (refreshV1ZoneCount).
        //      This is authoritative — reflects actual server state.
        //   2. fallback: v1Total - v1Deleted, the static accounting from
        //      what we've seen + what we've deleted. Diverges from server
        //      if peers wrote into the same v1 zone after our snapshot.
        let v1Live = state?.progress["v1Zone.liveCount"]
        let v1Remaining: Int? = {
            if let live = v1Live { return live }
            if let total = v1Total { return max(0, total - v1Deleted) }
            return nil
        }()
        return ProgressSummary(
            status: status, phase: phase,
            pushDone: pushDone, pushTotal: pushTotal,
            v1Deleted: v1Deleted, v1DeletePending: v1Pending,
            v1ZoneTotalAtStart: v1Total, v1ZoneRemaining: v1Remaining,
            lastError: state?.lastError,
            isSuspended: UserDefaults.standard.bool(forKey: Self.suspendedKey)
        )
    }

    /// Public method: query iCloud for the current v1 zone record count
    /// and persist it under `progress["v1Zone.liveCount"]`. Cheap-ish —
    /// uses `desiredKeys = []` so only systemFields/recordIDs come back,
    /// not record bodies. Caller responsible for cadence (the Sync
    /// detail view drives a 10-min loop while it's visible).
    ///
    /// No-op once migration is fully complete (v1 zone is gone) or
    /// failed (zone state is unknown / unsafe to query).
    func refreshV1ZoneCount() async {
        let status = currentStatus()
        if status == .completed || status == .failed { return }
        if #available(iOS 17.0, *) {
            do {
                let count = try await V1FetcherShim.countOwnZone()
                if var s = loadState() {
                    s.progress["v1Zone.liveCount"] = count
                    s.lastUpdatedAt = Date()
                    saveState(s)
                }
                logger.info("[SyncMigration] refreshV1ZoneCount: liveCount=\(count)")
            } catch {
                logger.warning("[SyncMigration] refreshV1ZoneCount failed: \(error.localizedDescription)")
            }
        }
    }

    /// Run the migration if needed. Idempotent — if status is already
    /// completed or failed, returns immediately. Caller is expected to
    /// ensure v1 sync is paused before calling.
    func runIfNeeded() async {
        // Re-entrancy guard. Launch (startIfEnabled) and the Sync-sheet
        // "Start / Retry Migration" button can both call this; overlapping
        // runs corrupt migration-state.json. If one is already driving the
        // state machine, the second call is a no-op. [T-ios-icloud-v1v2-migration-fails]
        if isRunning {
            logger.info("[SyncMigration] runIfNeeded: already running — ignoring re-entrant call")
            return
        }
        isRunning = true
        defer { isRunning = false }

        // Migration is opt-in: don't backfill history unless the user has
        // explicitly tapped Request Migration in the Sync sheet. Without
        // this gate, every fresh v2 install kicks off a multi-hour
        // throttled push of legacy data the user may not even want online.
        guard SyncV2Bootstrap.isMigrationRequested else {
            logger.info("[SyncMigration] phase=detect: not requested by user (history sync skipped)")
            return
        }

        // [T-ios-icloud-v1v2-migration-fails] Ensure the v2 sync stack is
        // actually up before driving phases. When the user taps Retry
        // Migration mid-session (not the launch path), SyncCore / the iCloud
        // transport may not be started — e.g. the previous attempt ended in
        // .failed (which makes shouldPauseV1 return false, so v1 ran and v2's
        // network boot may not have completed), or the user just flipped the
        // toggle on. Without a running SyncCore + registered transport,
        // phaseV2InitialPush's sendNow is a silent no-op, the dirty queue
        // never drains, and after the 30-min window the attempt looks like a
        // failure / stall. startIfEnabled is idempotent (SyncCore.start guards
        // on isRunning, register dedups by name), so calling it here is safe
        // even if launch already ran it.
        if #available(iOS 17.0, *), SyncV2Bootstrap.isEnabled, !SyncCore.shared.isRunning {
            logger.info("[SyncMigration] runIfNeeded: SyncCore not running — bootstrapping v2 stack before migration")
            await SyncV2Bootstrap.startIfEnabled()
            // startIfEnabled defers the network boot; give the core a brief
            // window to come up so the push phase doesn't immediately warn.
            var waited = 0
            while !SyncCore.shared.isRunning && waited < 30 {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                waited += 1
            }
            logger.info("[SyncMigration] runIfNeeded: post-bootstrap SyncCore.isRunning=\(SyncCore.shared.isRunning) waited=\(waited)s")
        }

        let status = currentStatus()
        logger.info("[SyncMigration] runIfNeeded entry: status=\(status.rawValue) phase=\(self.loadState()?.phase.rawValue ?? "nil") requested=\(SyncV2Bootstrap.isMigrationRequested) v2Enabled=\(SyncV2Bootstrap.isEnabled) deviceId=\(DeviceIdentity.deviceId.prefix(8))")
        switch status {
        case .completed:
            logger.info("[SyncMigration] phase=detect: status=completed (skip)")
            return
        case .failed:
            // Auto-recover from the legacy fail mode where 5×30min windows
            // ran out before 200k+ rows could drain. Older builds threw
            // pushDidNotDrain (now deferredUntilNextLaunch) which counted
            // toward maxConsecutiveFailures and permanently flagged the
            // migration as failed. The push was actually still progressing
            // — flip it back to pending so the new logic can resume.
            let prior = loadState()?.lastError ?? ""
            if prior.contains("Initial push did not drain") || prior.contains("Deferred") {
                logger.info("[SyncMigration] phase=detect: auto-recovering from legacy pushDidNotDrain failure — resetting to pending")
                if var s = loadState() {
                    s.status = .pending
                    s.lastError = nil
                    s.phaseFailureCount = 0
                    s.phase = .v2InitialPush
                    saveState(s)
                }
                UserDefaults.standard.set(MigrationStatus.pending.rawValue, forKey: statusKey)
                // fall through to proceed with .pending
            } else {
                logger.info("[SyncMigration] phase=detect: status=failed — manual recovery required (lastError=\(prior))")
                return
            }
        case .pending, .inProgress:
            break
        }

        var state = loadState() ?? MigrationState(
            status: .inProgress,
            phase: .detect,
            startedAt: Date(),
            lastUpdatedAt: Date(),
            progress: [:],
            lastError: nil,
            phaseFailureCount: 0
        )
        state.status = .inProgress
        if state.startedAt == nil { state.startedAt = Date() }
        UserDefaults.standard.set(MigrationStatus.inProgress.rawValue, forKey: statusKey)
        saveState(state)
        logger.info("[SyncMigration] phase=detect: status=\(state.status.rawValue) startedAt=\(state.startedAt?.description ?? "?") deviceId=\(DeviceIdentity.deviceId.prefix(8))")

        // Drive the state machine forward.
        while state.phase != .completed {
            do {
                state = try await runPhase(state)
                state.phaseFailureCount = 0
                saveState(state)
                // A phase completed — the migration is demonstrably moving
                // again, so drop any stale suspended marker.
                UserDefaults.standard.set(false, forKey: Self.suspendedKey)
            } catch let mErr as MigrationError {
                if case .deferredUntilNextLaunch(let n) = mErr {
                    // Not a failure — phase made progress but didn't
                    // finish its window. Save state, exit the loop,
                    // pick up where we left off on the next launch.
                    state.lastUpdatedAt = Date()
                    state.lastError = nil
                    saveState(state)
                    // [T-icloud-migration-suspended-ui] Record that nothing is
                    // driving the migration any more, so the sheet can say
                    // "suspended — reopen the app to continue" instead of
                    // showing an active-looking status over a frozen counter.
                    UserDefaults.standard.set(true, forKey: Self.suspendedKey)
                    logger.info("[SyncMigration] phase=\(state.phase.rawValue) deferred: \(n) records remain — suspending until next launch")
                    return
                }
                state.phaseFailureCount += 1
                state.lastError = mErr.errorDescription ?? mErr.localizedDescription
                state.lastUpdatedAt = Date()
                logger.error("[SyncMigration] phase=\(state.phase.rawValue) ERROR: \(state.lastError ?? "?") attempt=\(state.phaseFailureCount)/\(self.maxConsecutiveFailures)")
                if state.phaseFailureCount >= maxConsecutiveFailures {
                    state.status = .failed
                    saveState(state)
                    UserDefaults.standard.set(MigrationStatus.failed.rawValue, forKey: statusKey)
                    logger.error("[SyncMigration] failed: lastPhase=\(state.phase.rawValue) lastError=\(state.lastError ?? "?")")
                    return
                }
                let backoff = TimeInterval(1 << state.phaseFailureCount) * 2
                try? await Task.sleep(nanoseconds: UInt64(backoff * 1_000_000_000))
            } catch {
                state.phaseFailureCount += 1
                state.lastError = error.localizedDescription
                state.lastUpdatedAt = Date()
                logger.error("[SyncMigration] phase=\(state.phase.rawValue) ERROR: \(error.localizedDescription) attempt=\(state.phaseFailureCount)/\(self.maxConsecutiveFailures)")
                if state.phaseFailureCount >= maxConsecutiveFailures {
                    state.status = .failed
                    saveState(state)
                    UserDefaults.standard.set(MigrationStatus.failed.rawValue, forKey: statusKey)
                    logger.error("[SyncMigration] failed: lastPhase=\(state.phase.rawValue) lastError=\(state.lastError ?? "?")")
                    return
                }
                let backoff = TimeInterval(1 << state.phaseFailureCount) * 2
                try? await Task.sleep(nanoseconds: UInt64(backoff * 1_000_000_000))
            }
        }

        UserDefaults.standard.set(MigrationStatus.completed.rawValue, forKey: statusKey)
        let elapsed = Date().timeIntervalSince(state.startedAt ?? Date()) * 1000
        logger.info("[SyncMigration] migration=success totalElapsedMs=\(Int(elapsed)) finishedAt=\(Date().description)")
    }

    /// Reset state — used in dev to re-run migration. Not exposed in UI.
    func resetForTesting() {
        try? FileManager.default.removeItem(at: stateURL)
        UserDefaults.standard.removeObject(forKey: statusKey)
    }

    /// Snapshot of the current v1→v2 migration state.
    func snapshot() -> MigrationState? {
        loadState()
    }

    // MARK: - Phase machine

    private func runPhase(_ state: MigrationState) async throws -> MigrationState {
        var s = state
        switch s.phase {
        case .detect:
            logger.info("[SyncMigration] phase=detect end: advancing to v1FullFetch")
            s.phase = .v1FullFetch
        case .v1FullFetch:
            try await phaseV1FullFetch(&s)
        case .v2ZonesCreate:
            try await phaseV2ZonesCreate(&s)
        case .v2InitialPush:
            try await phaseV2InitialPush(&s)
        case .v1ZoneDelete:
            try await phaseV1ZoneDelete(&s)
        case .lock:
            try await phaseLock(&s)
        case .completed:
            break
        }
        s.lastUpdatedAt = Date()
        return s
    }

    private func phaseV1FullFetch(_ s: inout MigrationState) async throws {
        logger.info("[SyncMigration] phase=v1FullFetch start")
        let result = try await V1FetcherShim.fetchAll()
        logger.info("[SyncMigration] phase=v1FullFetch end: zonesDone=\(result.zonesProcessed) records=\(result.recordsFetched)")
        s.phase = .v2ZonesCreate
        s.progress["v1FullFetch.zones"] = result.zonesProcessed
        s.progress["v1FullFetch.records"] = result.recordsFetched
    }

    private func phaseV2ZonesCreate(_ s: inout MigrationState) async throws {
        logger.info("[SyncMigration] phase=v2ZonesCreate start: zones=[minis-shared,minis-devices,minis-secrets]")
        // Booting the transport queues the three zones via
        // pendingDatabaseChanges; sendChanges then materializes them.
        // SyncCore must already have ICloudSharedZoneTransport registered.
        await SyncCore.shared.start()
        // No explicit "create zone" call — engine handles it on the
        // first send. Treat phase as done after start succeeds.
        logger.info("[SyncMigration] phase=v2ZonesCreate end: success")
        s.phase = .v2InitialPush
    }

    private func phaseV2InitialPush(_ s: inout MigrationState) async throws {
        logger.info("[SyncMigration] phase=v2InitialPush start")
        // Authority is local SQLite — not whatever phase 1 fetched. If
        // phase 1 missed records due to v1 modificationDate bugs, those
        // records are still on this device's local DB and get pushed
        // here.
        let counts = try await V2InitialPusher.markAllLocalDirty()
        logger.info("[SyncMigration] phase=v2InitialPush start: localCounts=\(counts)")
        for (k, v) in counts { s.progress["v2InitialPush.\(k)"] = v }
        // SyncCore may still be inside its 120s boot-delay window
        // (SyncV2Bootstrap defers .start to keep cold-launch CPU low).
        // Wait for it before issuing sendNow, otherwise the call is a
        // no-op ("sendNow skipped — not running") and the migration
        // appears stuck for two minutes.
        var waited = 0
        while !SyncCore.shared.isRunning && waited < 180 {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            waited += 2
        }
        if !SyncCore.shared.isRunning {
            logger.warning("[SyncMigration] phase=v2InitialPush: SyncCore still not running after \(waited)s — proceeding anyway")
        } else if waited > 0 {
            logger.info("[SyncMigration] phase=v2InitialPush: waited \(waited)s for SyncCore boot delay")
        }
        // Trigger the send (transport-agnostic; broadcasts to whatever
        // is registered — ICloudSharedZoneTransport in this case).
        await SyncCore.shared.sendNow(trigger: .migration)
        // Wait until dirty drains. We poll in the simplest possible way
        // (every 2s up to 30 minutes); real production would expose a
        // semaphore from SyncCore.
        let deadline = Date().addingTimeInterval(30 * 60)
        while Date() < deadline {
            let summary = await SyncCore.shared.dirtySummary()
            if summary.total == 0 { break }
            try? await Task.sleep(nanoseconds: 2_000_000_000)
        }
        let final = await SyncCore.shared.dirtySummary()
        logger.info("[SyncMigration] phase=v2InitialPush end: dirtyRemaining=\(final.total) byType=\(final.byType)")
        if final.total > 0 {
            // Push didn't drain within the 30-min window. With 200k+
            // legacy rows + iCloud throttling this is normal — pushes
            // are still succeeding in the background. Throw a special
            // sentinel that runIfNeeded recognizes as 'pause migration
            // until next launch' instead of counting it toward the
            // failure cap (which would permanently mark the migration
            // .failed after 5 windows).
            s.progress["v2InitialPush.dirtyRemaining"] = final.total
            throw MigrationError.deferredUntilNextLaunch(remaining: final.total)
        }
        s.phase = .v1ZoneDelete
    }

    private func phaseV1ZoneDelete(_ s: inout MigrationState) async throws {
        let myDeviceId = DeviceIdentity.deviceId
        let myZoneName = "device-\(myDeviceId)"
        logger.info("[SyncMigration] phase=v1ZoneDelete start: zone=\(myZoneName)")

        // Audit C4 safeguard: verify v2 actually has at least the local
        // session count before we delete the v1 zone. Phase 3's
        // "dirty == 0" gate can be reached either by successful pushes
        // OR by clear-without-push (no longer possible after the C3
        // fix, but defense in depth). If the cloud-side count is wildly
        // smaller than local, abort and leave v1 zone intact for next
        // migration attempt.
        let localSessions = await ChatStore.shared.allSessionIds().count

        // [T-icloud-v1delete-count-deadlock] With no local sessions the
        // safeguard has nothing left to protect — its threshold below is
        // `max(0, localSessions / 2)` == 0, so ANY count it manages to read
        // would pass. Yet the cloud-count query still runs first, and when
        // CloudKit throttles it that query fails, the guard throws, the phase
        // never advances, and the retry hits the same throttle: a permanent
        // loop with nothing being guarded. OpenMinis#154 sat here for five
        // days — the user even deleted every conversation trying to finish the
        // migration, which made it strictly worse (localSessions → 0) because
        // the blocker was the QUERY, not the data volume.
        //
        // Skip the round trip entirely in that case. This is not a weakening:
        // a zero-threshold check cannot reject anything.
        if localSessions == 0 {
            // One guard before taking the shortcut: `allSessionIds()` returns
            // an empty array on a SQL prepare failure too, so "0 sessions" can
            // also mean "the local DB was unreadable this launch" (the
            // pre-first-unlock / corrupt-DB case ProviderConfigStore's
            // RebootGuard exists for). Deleting the v1 zone on THAT reading
            // would destroy the cloud copy of data that is merely unreadable
            // right now. Verify the store is actually healthy and genuinely
            // empty by asking for a count that a broken DB cannot fake.
            let messageCount = await ChatStore.shared.countBacklogBaseline().messages
            guard messageCount == 0 else {
                logger.error("[SyncMigration] phase=v1ZoneDelete: 0 sessions but \(messageCount) messages — local store looks INCONSISTENT (unreadable DB?); refusing the no-local-sessions shortcut")
                throw MigrationError.deferredUntilNextLaunch(remaining: messageCount)
            }
            logger.info("[SyncMigration] phase=v1ZoneDelete: no local sessions or messages — safeguard threshold is 0, skipping cloud count and proceeding")
            try await V1FetcherShim.deleteOwnZone(zoneName: myZoneName)
            logger.info("[SyncMigration] phase=v1ZoneDelete end: deleted (no-local-sessions path)")
            s.phase = .lock
            return
        }

        // countCloudSessionsV2 now returns nil when the COUNT QUERY ITSELF
        // failed (transient CloudKit error), as distinct from a confirmed
        // low count. [T-ios-icloud-v1v2-migration-fails] Previously it
        // returned 0 on error, which tripped the safeguard below and burned
        // a failure attempt even when the v2 push had actually succeeded —
        // a plausible "retry keeps failing" loop, since the retry re-pushes
        // (already drained, fast) then re-hits the same flaky count and
        // fails again. We now treat an UNKNOWN count as retryable-but-safe:
        // throw a transient error (so we don't delete the v1 zone on
        // incomplete info) without claiming the migration is broken.
        let cloudCount: Int?
        if #available(iOS 17.0, *) {
            cloudCount = await Self.countCloudSessionsV2()
        } else {
            cloudCount = 0
        }
        guard let actualCloud = cloudCount else {
            // [T-icloud-v1delete-count-deadlock] Deferral, NOT a failure.
            //
            // This throw used to be `v1FetchFailed`, which counts toward
            // maxConsecutiveFailures. The retry backoff is 2^n*2s, so all five
            // attempts burn in ~2 minutes — far inside a CloudKit throttle
            // window, which runs minutes to hours. The migration therefore
            // flipped to .failed while the only thing wrong was a transient
            // throttle, and the user's "Retry" restarted the identical
            // two-minute burn (OpenMinis#154: five days of exactly this).
            //
            // `deferredUntilNextLaunch` suspends the state machine without
            // burning an attempt, so the next launch retries after the window
            // has realistically passed instead of hammering through it.
            logger.warning("[SyncMigration] phase=v1ZoneDelete: cloud session count unavailable (CK query failed — likely throttled) — suspending until next launch (localSessions=\(localSessions))")
            throw MigrationError.deferredUntilNextLaunch(remaining: localSessions)
        }
        // Allow 50% slack — multi-device fan-in not yet complete is OK,
        // but >50% missing is a strong "something went wrong" signal.
        let minimumCloud = max(0, localSessions / 2)
        if actualCloud < minimumCloud {
            logger.error("[SyncMigration] phase=v1ZoneDelete safeguard tripped: localSessions=\(localSessions) cloudSessions=\(actualCloud) min=\(minimumCloud); aborting delete to preserve v1 cloud copy")
            throw MigrationError.v1FetchFailed(reason: "v2 cloud count (\(actualCloud)) far below local (\(localSessions)) — aborting v1 zone delete")
        }
        logger.info("[SyncMigration] phase=v1ZoneDelete safeguard OK: localSessions=\(localSessions) cloudSessions=\(actualCloud)")

        try await V1FetcherShim.deleteOwnZone(zoneName: myZoneName)
        logger.info("[SyncMigration] phase=v1ZoneDelete end: deleted")
        s.phase = .lock
    }

    /// Count SessionV2 records in the v2 shared zone. Used by the C4
    /// safeguard to verify v2 push actually populated cloud before we
    /// drop the v1 zone fallback.
    ///
    /// Returns nil when the count query ITSELF failed (transient CloudKit
    /// error) — distinct from a confirmed 0/low count. The caller treats nil
    /// as "unknown, defer and retry" rather than "cloud is empty, fail",
    /// so a flaky CK query no longer false-trips the safeguard into a
    /// permanent failure. [T-ios-icloud-v1v2-migration-fails]
    @available(iOS 17.0, *)
    private static func countCloudSessionsV2() async -> Int? {
        let container = CKContainer(identifier: ICloudSharedZoneTransport.containerIdentifier)
        let zoneID = CKRecordZone.ID(zoneName: ICloudSharedZoneTransport.sharedZoneName)
        let query = CKQuery(recordType: "SessionV2", predicate: NSPredicate(value: true))
        do {
            let (matches, _) = try await container.privateCloudDatabase.records(
                matching: query, inZoneWith: zoneID, desiredKeys: ["sessionId"], resultsLimit: 10_000
            )
            return matches.count
        } catch let error as CKError where error.code == .zoneNotFound || error.code == .unknownItem {
            // Zone/type genuinely absent → a real zero, not a transient
            // failure. Return 0 so the safeguard can evaluate it.
            logger.warning("[SyncMigration] countCloudSessionsV2: zone/type not found (treating as 0): \(error.localizedDescription)")
            return 0
        } catch {
            logger.warning("[SyncMigration] countCloudSessionsV2 query failed (unknown count): \(error.localizedDescription)")
            return nil
        }
    }

    private func phaseLock(_ s: inout MigrationState) async throws {
        logger.info("[SyncMigration] phase=lock start")
        // Status flag is written by runIfNeeded after this returns;
        // here we also publish a SyncDeviceV2 record showing that this
        // device migrated, so other devices can see migration progress.
        // (NOT yet wired up — depends on SyncedDevice having a builder
        // hook in SyncCoreHydrators; enabled in P5+.)
        logger.info("[SyncMigration] phase=lock end: writing migrationStatus=completed")
        s.phase = .completed
    }
}

// MARK: - Errors

enum MigrationError: Error, LocalizedError {
    case pushDidNotDrain(remaining: Int)
    case v1FetchFailed(reason: String)
    case zoneDeleteFailed(zone: String, reason: String)
    /// [T-icloud-zone-delete-resurrect] The delete call itself reported
    /// success, but the zone was still in the private database when we read
    /// it back — i.e. a running sync engine re-created it. Distinct from
    /// `zoneDeleteFailed` so the UI can explain the actual cause (an engine
    /// owns this zone) rather than blaming CloudKit.
    case zoneResurrected(zone: String)
    /// Sentinel: the current phase didn't finish in its window but is
    /// still making progress in the background (CK-throttled). Caller
    /// should suspend the state machine and resume on next app launch.
    /// Does NOT count toward phaseFailureCount.
    case deferredUntilNextLaunch(remaining: Int)

    var errorDescription: String? {
        switch self {
        case .pushDidNotDrain(let n):
            return "Initial push did not drain (still \(n) dirty)"
        case .v1FetchFailed(let r):
            return "v1 fetch failed: \(r)"
        case .zoneDeleteFailed(let z, let r):
            return "Failed to delete v1 zone \(z): \(r)"
        case .zoneResurrected(let z):
            return "Zone \(z) was deleted but immediately re-created by a running sync engine. Turn off iCloud Sync, then delete it again."
        case .deferredUntilNextLaunch(let n):
            return "Deferred — \(n) records still in queue, will resume on next launch"
        }
    }
}

// MARK: - State persistence

extension MigrationEngine {
    fileprivate func loadState() -> MigrationState? {
        guard let data = try? Data(contentsOf: stateURL) else { return nil }
        return try? JSONDecoder().decode(MigrationState.self, from: data)
    }

    fileprivate func saveState(_ state: MigrationState) {
        guard let data = try? JSONEncoder().encode(state) else { return }
        try? data.write(to: stateURL, options: .atomic)
    }
}

// MARK: - V1 fetcher shim (fetch-only, no engine start)

/// Minimal v1 fetcher used during migration. Does NOT start
/// CloudSyncEngine v1 (no timer / no markDirty / no send) — the goal is
/// strictly to pull every record from device-* zones into local SQLite
/// once.
@MainActor
enum V1FetcherShim {
    struct Result { let zonesProcessed: Int; let recordsFetched: Int }

    /// Fetch all v1 device-* zones once, awaiting actual completion.
    /// Does NOT call CloudSyncEngine.start() here (that would also boot
    /// the periodic timer + queueDeviceRecord, both of which would
    /// race against the v2 path). Instead we rely on v1 already having
    /// been started — or, when v1 was paused via shouldPauseV1, we
    /// accept that the v2 push from local SQLite is the authoritative
    /// source and treat phase 1 as a no-op.
    static func fetchAll() async throws -> Result {
        if #available(iOS 17.0, *) {
            // If v2 is enabled, shouldPauseV1 == true and v1 was never
            // started this launch — there's no v1 engine instance to
            // drive a fetch through. Local SQLite already contains
            // everything pre-existing v1 fetched in past launches, so
            // phase 4 (v2 initial push) writes from local data, which
            // is the authoritative source per §6.0 / §10.2.6.
            //
            // We still return a positive zonesProcessed so the state
            // machine advances; granular per-zone log lines are emitted
            // by v1's existing fetch path on previous launches and are
            // not re-emitted here.
            return Result(zonesProcessed: 0, recordsFetched: 0)
        } else {
            return Result(zonesProcessed: 0, recordsFetched: 0)
        }
    }

    /// Count records currently in this device's v1 zone (device-<myId>)
    /// via a paginated query. Uses `desiredKeys = []` so only
    /// systemFields/recordID come back over the wire — body & assets
    /// stay on the server. Returns 0 if the zone is gone.
    @available(iOS 17.0, *)
    static func countOwnZone() async throws -> Int {
        let myDeviceId = DeviceIdentity.deviceId
        let zoneID = CKRecordZone.ID(zoneName: "device-\(myDeviceId)")
        let container = CKContainer(identifier: ICloudSharedZoneTransport.containerIdentifier)
        let db = container.privateCloudDatabase

        // v1 record types in the device-<id> zone, paired with a
        // predicate that only references *queryable* fields per the
        // CloudKit Dashboard schema. NSPredicate(value:true) won't work
        // because CloudKit compiles it against `recordName`, which is
        // not marked queryable on these types.
        //   - Session/Message/CompactMarker — `createdAt` is queryable
        //     and present on every row. Anchor `createdAt > epoch`.
        //   - SessionFile — has neither createdAt nor updatedAt. Use
        //     `sessionId != "__none__"` (sessionId IS queryable, every
        //     row has a non-empty value).
        let epoch = Date(timeIntervalSince1970: 946_684_800) // 2000-01-01 UTC
        let queries: [(type: String, predicate: NSPredicate)] = [
            ("Session",       NSPredicate(format: "createdAt > %@", epoch as NSDate)),
            ("Message",       NSPredicate(format: "createdAt > %@", epoch as NSDate)),
            ("CompactMarker", NSPredicate(format: "createdAt > %@", epoch as NSDate)),
            ("SessionFile",   NSPredicate(format: "sessionId != %@", "__none__" as NSString)),
        ]
        var total = 0
        for (type, predicate) in queries {
            do {
                total += try await countRecords(of: type, predicate: predicate,
                                                in: zoneID, db: db)
            } catch let error as CKError where error.code == .zoneNotFound {
                return 0  // entire zone gone — short-circuit
            } catch let error as CKError where error.code == .unknownItem
                                            || error.code == .invalidArguments {
                // Schema may have lost a record type — skip and keep
                // counting the others.
                continue
            }
        }
        return total
    }

    @available(iOS 17.0, *)
    private static func countRecords(of recordType: String,
                                     predicate: NSPredicate,
                                     in zoneID: CKRecordZone.ID,
                                     db: CKDatabase) async throws -> Int {
        let query = CKQuery(recordType: recordType, predicate: predicate)
        var cursor: CKQueryOperation.Cursor? = nil
        var count = 0
        // Cap pages so a runaway zone doesn't make us hang.
        for _ in 0..<200 {
            let resp: (matchResults: [(CKRecord.ID, Swift.Result<CKRecord, Error>)],
                       queryCursor: CKQueryOperation.Cursor?)
            if let cur = cursor {
                resp = try await db.records(continuingMatchFrom: cur,
                                            desiredKeys: [],
                                            resultsLimit: CKQueryOperation.maximumResults)
            } else {
                resp = try await db.records(matching: query,
                                            inZoneWith: zoneID,
                                            desiredKeys: [],
                                            resultsLimit: CKQueryOperation.maximumResults)
            }
            count += resp.matchResults.count
            if let next = resp.queryCursor {
                cursor = next
            } else {
                break
            }
        }
        return count
    }

    /// Delete this device's own v1 zone (device-<myId>). Other devices'
    /// v1 zones are NOT touched — they belong to those devices.
    /// [T-icloud-v1delete-count-deadlock] CloudKit conditions that heal on their
    /// own and must NOT count toward the migration's consecutive-failure cap.
    /// Everything else stays a real failure so a genuinely broken migration is
    /// still surfaced instead of retrying forever.
    @available(iOS 17.0, *)
    static func isTransientCKError(_ error: CKError) -> Bool {
        switch error.code {
        case .requestRateLimited, .zoneBusy, .serviceUnavailable,
             .networkUnavailable, .networkFailure, .internalError:
            return true
        default:
            return false
        }
    }

    /// [T-icloud-zone-delete-resurrect] Zones the user deletes from the Zones
    /// inventory must STAY deleted. Both sync engines hold a CKSyncEngine whose
    /// pending database changes re-create the zones they own — v1 re-adds
    /// `device-<myId>` AND the legacy bare `devices` zone
    /// (CloudSyncEngine.queueZonesAndDeviceRecord), v2 re-adds the three
    /// `minis-*` zones (ICloudSharedZoneTransport). A plain
    /// `deleteRecordZone` therefore succeeds and the zone reappears seconds
    /// later, which is exactly the "deleted it, still in the list" report.
    ///
    /// So the delete is bracketed: quiesce whichever engine owns the zone
    /// first, then delete, then read the zone list back to confirm. Without the
    /// read-back a resurrected zone is indistinguishable from a successful
    /// delete, which is how this shipped silently.
    ///
    /// Returns normally only when the zone is verified absent.
    static func purgeZone(zoneName: String) async throws {
        guard #available(iOS 17.0, *) else { return }
        // Quiesce the owner BEFORE deleting, so no pending `saveZone` is
        // sitting in an engine's queue waiting to land after our delete.
        let owner = ZoneOwner.owning(zoneName: zoneName)
        await owner.quiesce()
        do {
            try await deleteOwnZone(zoneName: zoneName)
            // Tombstone BEFORE resuming: `resume()` restarts the engine, whose
            // very first act is to re-queue zone creation. Recording the purge
            // first is what makes the delete outlive the resume — and the next
            // launch, where start() would otherwise re-create it unprompted.
            owner.markPurged(zoneName: zoneName)
            // Read back. `allRecordZones` is the same call the inventory uses,
            // so "gone here" means "gone in the list the user is looking at".
            let container = CKContainer(identifier: ICloudSharedZoneTransport.containerIdentifier)
            let remaining = try await container.privateCloudDatabase.allRecordZones()
            if remaining.contains(where: { $0.zoneID.zoneName == zoneName }) {
                logger.error("[SyncMigration] zone \(zoneName) reappeared after delete — engine re-created it")
                throw MigrationError.zoneResurrected(zone: zoneName)
            }
            logger.info("[SyncMigration] zone purge verified: \(zoneName)")
        } catch {
            // Restore the engine on failure; a failed delete must not leave
            // sync switched off behind the user's back.
            await owner.resume()
            throw error
        }
        await owner.resume()
    }

    /// Which engine re-creates a given zone, and how to hold it still while we
    /// delete. Resolved by zone name because that is what the inventory row
    /// carries, and it is the same naming both engines key off.
    @available(iOS 17.0, *)
    enum ZoneOwner {
        case v1
        case v2
        case none

        static func owning(zoneName: String) -> ZoneOwner {
            if zoneName == ICloudSharedZoneTransport.sharedZoneName
                || zoneName == ICloudSharedZoneTransport.devicesZoneName
                || zoneName == ICloudSharedZoneTransport.secretsZoneName {
                return .v2
            }
            // v1 owns its per-device zone AND the legacy bare "devices" zone
            // (CloudSyncEngine.devicesZoneName) — the `[?]` row users hit.
            if zoneName.hasPrefix("device-") || zoneName == "devices" {
                return .v1
            }
            return .none
        }

        @MainActor
        func quiesce() async {
            switch self {
            case .v1:
                // Drops the CKSyncEngine (and its queued saveZone changes)
                // without touching the user's `cloudSync.enabled` preference.
                CloudSyncEngine.shared.suspendForZonePurge()
            case .v2:
                await SyncCore.shared.suspendForZonePurge()
            case .none:
                break
            }
        }

        @MainActor
        func resume() async {
            switch self {
            case .v1:
                await CloudSyncEngine.shared.resumeAfterZonePurge()
            case .v2:
                await SyncCore.shared.resumeAfterZonePurge()
            case .none:
                break
            }
        }

        /// Record the delete durably so the owning engine does not re-create
        /// the zone — neither when it resumes moments from now, nor on any
        /// later launch.
        ///
        /// Only v1 gets a tombstone. Its zones are legacy and the user is
        /// deliberately reclaiming that space. A v2 zone is where sync
        /// actively lives, so suppressing its re-creation would quietly break
        /// sync instead of freeing space; deleting one is a data-clearing
        /// action, and v2 legitimately rebuilds it on the next run.
        @MainActor
        func markPurged(zoneName: String) {
            guard case .v1 = self else { return }
            CloudSyncEngine.markZonePurged(zoneName)
        }
    }

    static func deleteOwnZone(zoneName: String) async throws {
        if #available(iOS 17.0, *) {
            let container = CKContainer(identifier: ICloudSharedZoneTransport.containerIdentifier)
            let zoneID = CKRecordZone.ID(zoneName: zoneName)
            do {
                _ = try await container.privateCloudDatabase.deleteRecordZone(withID: zoneID)
                logger.info("[SyncMigration] deleted v1 zone: \(zoneName)")
            } catch let error as CKError where error.code == .zoneNotFound {
                logger.info("[SyncMigration] v1 zone already gone: \(zoneName)")
            } catch let error as CKError where Self.isTransientCKError(error) {
                // [T-icloud-v1delete-count-deadlock] Throttling / network is not
                // a broken migration. Reported as a hard failure it burns the
                // 5-attempt cap inside ~124s of backoff — far shorter than a
                // CloudKit throttle window — and flips the whole migration to
                // .failed over something that would have healed on its own.
                // Defer instead, so the next launch retries after the window.
                logger.warning("[SyncMigration] v1 zone delete deferred (transient CK error \(error.code.rawValue)): \(error.localizedDescription)")
                throw MigrationError.deferredUntilNextLaunch(remaining: 0)
            } catch {
                throw MigrationError.zoneDeleteFailed(zone: zoneName, reason: error.localizedDescription)
            }
        }
    }

    /// Enumerate every CKRecordZone in the user's private database that
    /// belongs to this app's container. Used by the Sync sheet to render
    /// the "iCloud Zones" inventory + per-zone delete control.
    @available(iOS 17.0, *)
    static func listAllZones() async throws -> [ZoneInfo] {
        let container = CKContainer(identifier: ICloudSharedZoneTransport.containerIdentifier)
        let zones = try await container.privateCloudDatabase.allRecordZones()
        let myDeviceId = DeviceIdentity.deviceId
        let myZoneName = "device-\(myDeviceId)"
        return zones.map { zone in
            let name = zone.zoneID.zoneName
            let kind: ZoneKind = {
                if name == ICloudSharedZoneTransport.sharedZoneName
                    || name == ICloudSharedZoneTransport.devicesZoneName
                    || name == ICloudSharedZoneTransport.secretsZoneName {
                    return .v2
                }
                if name.hasPrefix("device-") {
                    return .v1
                }
                if name == "_defaultZone" {
                    return .system
                }
                return .other
            }()
            return ZoneInfo(
                name: name,
                kind: kind,
                isOwn: name == myZoneName
            )
        }
    }

    enum ZoneKind: String {
        case v2     // minis-shared / minis-devices / minis-secrets
        case v1     // device-<id>
        case system // _defaultZone
        case other  // legacy "devices", unknown
    }

    struct ZoneInfo: Identifiable {
        let name: String
        let kind: ZoneKind
        /// True when this zone was created by THIS device (its v1 zone).
        /// Used by the UI to mark / warn before delete.
        let isOwn: Bool
        var id: String { name }
    }
}

// MARK: - V2 initial pusher

/// Walks the local SQLite tables and marks every row dirty so SyncCore
/// pushes them through ICloudSharedZoneTransport into the v2 zones.
@MainActor
enum V2InitialPusher {
    /// Returns counts by recordType for diagnostic purposes.
    static func markAllLocalDirty() async throws -> [String: Int] {
        // Implementation deferred to ChatStore / SkillStore / etc., each
        // of which exposes a "force markDirty all" helper. v1 already has
        // markAllLocalForReupload — we reuse it for sessions/messages/
        // markers/files/configs. SyncCore picks up the dirty rows on the
        // next sendNow.
        //
        // No force=true here — this path is the migration phase retry
        // loop, which can be hit repeatedly across launches while the
        // backlog drains. The first call stages the entire backlog and
        // sets the once-per-install guard; subsequent calls observe
        // dirty rows still queued (or none, if migration finished) and
        // short-circuit instead of redoing 290k SQLite INSERTs +
        // 89k file stats per pass. The "Delete iCloud Data" recovery
        // flow in CloudSyncEngine is the only caller that uses
        // force=true.
        await ChatStore.shared.markAllLocalForReupload()
        let after = await ChatStore.shared.countDirtyRecords()
        return after.byType
    }
}

// CKError import alias since CloudKit is conditionally available.
@_exported import CloudKit
