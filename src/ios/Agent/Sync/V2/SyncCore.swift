import Foundation
import os.log
import Network

private let logger = AppLogger(category: "SyncCore")

/// Why a record was marked dirty. Used in logs.
enum SyncDirtyReason: String {
    case localUpsert
    case localDelete
    case migrationInitialPush
    case resurrectCascade
    case retryAfterTransientFailure
    case other
}

/// Central dispatcher between application writes and the configured
/// transports. SyncCore owns:
///
///   - the dirty queue (delegates persistence to `ChatStore.markDirty`)
///   - send debouncing (3s default, mirrors v1 behavior)
///   - in-flight protection (no overlapping send to the same transport)
///   - broadcast to multiple transports (each transport sees the same
///     PortableRecord stream)
///   - inbound merging from any transport into local SQLite
///
/// SyncCore does NOT know about CloudKit, CKSyncEngine, zones, etag,
/// silent push, or anything transport-specific. All that lives behind
/// the `SyncTransport` protocol.
@MainActor
final class SyncCore {
    static let shared = SyncCore()
    private init() {}

    // MARK: - Configuration

    /// Transports SyncCore broadcasts to. Multiple transports run in
    /// parallel — the first one to deliver a record wins (others see it
    /// as a no-op because the record id already merged locally).
    private(set) var transports: [SyncTransport] = []

    /// [T-ios-log-noise-reduction] First-seen dedup for the
    /// `[SyncSchema] unknownFields` log. The server sends new fields
    /// (asset_size/asset_mime on SessionFileV2 etc.) on EVERY record of a
    /// batch — ~18.7k identical INFO lines per full fetch. We only care that
    /// the (type, sorted-keys) combination appeared at all, so log it once
    /// per combination per process and skip the rest.
    private var loggedUnknownFieldKeys: Set<String> = []

    /// True after `start()` finishes successfully. Reset by `stop()`.
    private(set) var isRunning: Bool = false

    /// When startIfEnabled defers the network boot, this is the absolute
    /// time at which the deferred SyncCore.start is expected to fire.
    /// Cleared once start() actually runs.
    var bootDelayUntil: Date?

    /// Timestamps for the Sync status UI. Updated on every successful
    /// send/fetch round; nil = none yet this session.
    private(set) var lastSendAt: Date?
    private(set) var lastFetchAt: Date?
    /// Cumulative counters for the current session. Reset on app relaunch.
    private(set) var totalSent: Int = 0
    private(set) var totalReceived: Int = 0

    // MARK: - Dynamic rate throttling

    /// True while the iCloud Sync sheet is the visible context — caller
    /// (SyncMigrationDetailView) flips this in .task / on dismiss. When
    /// true the chained-send cadence runs at full speed; when false we
    /// throttle so the foreground UI stays responsive.
    var userOnSyncSheet: Bool = false
    /// True while the app is in background. Switched by ContentView's
    /// scenePhase observer. Background applies a uniform 1/4× multiplier
    /// regardless of which page the user was on when they backgrounded.
    var isAppInBackground: Bool = false
    /// Current network type. Updated by an NWPathMonitor in start().
    /// .cellular adds a 0.5× multiplier on top of the foreground throttle.
    enum NetworkClass { case wifi, cellular, other }
    private(set) var networkClass: NetworkClass = .wifi
    private var pathMonitor: NWPathMonitor?

    /// Effective chained-send delay seconds for the current context.
    /// Base 5s when the user is staring at the sync sheet. Foreground
    /// (default) ×3. Background ×4 (overrides foreground). Cellular ×2
    /// stacks on top.
    var currentSendDelay: TimeInterval {
        let base: TimeInterval = 5
        let foregroundMultiplier: TimeInterval
        if isAppInBackground { foregroundMultiplier = 4.0 }
        else if userOnSyncSheet { foregroundMultiplier = 1.0 }
        else { foregroundMultiplier = 3.0 }
        let networkMultiplier: TimeInterval = networkClass == .cellular ? 2.0 : 1.0
        return base * foregroundMultiplier * networkMultiplier
    }
    /// Human-readable label for the current throttle multiplier.
    var throttleLabel: String {
        let f: String
        if isAppInBackground { f = "1/4× (bg)" }
        else if userOnSyncSheet { f = "1×" }
        else { f = "1/3×" }
        let n = networkClass == .cellular ? " · cellular ½" : ""
        return f + n
    }

    /// Sliding window of (timestamp, recordCount) successful save events
    /// for the recent-rate readout.
    private var sendEvents: [(Date, Int)] = []
    private static let rateWindow: TimeInterval = 60
    /// Average records/sec across the last `rateWindow` seconds.
    var recentRatePerSecond: Double {
        let cutoff = Date().addingTimeInterval(-Self.rateWindow)
        let recent = sendEvents.filter { $0.0 >= cutoff }
        guard !recent.isEmpty else { return 0 }
        let total = recent.reduce(0) { $0 + $1.1 }
        return Double(total) / Self.rateWindow
    }

    /// When CloudKit replies with `requestRateLimited` (CKError code 7,
    /// HTTP 429), this absolute time gates the next send attempt. Prevents
    /// the chained sendNow / scheduleSend retry loop from hammering iCloud
    /// inside the throttle window.
    private(set) var nextEarliestSendAt: Date?

    /// User-initiated pause deadline. When set and in the future, sendNow
    /// is a no-op (markDirty still records, so nothing is lost — the queue
    /// drains once the deadline passes). Persisted to UserDefaults so the
    /// pause survives app restart.
    private static let pauseUntilKey = "cloudSync.v2.pausedUntil"
    var pausedUntil: Date? {
        get {
            let t = UserDefaults.standard.double(forKey: Self.pauseUntilKey)
            guard t > 0 else { return nil }
            let date = Date(timeIntervalSince1970: t)
            return date > Date() ? date : nil
        }
        set {
            if let d = newValue {
                UserDefaults.standard.set(d.timeIntervalSince1970, forKey: Self.pauseUntilKey)
            } else {
                UserDefaults.standard.removeObject(forKey: Self.pauseUntilKey)
            }
        }
    }
    func pause(for duration: TimeInterval) {
        pausedUntil = Date().addingTimeInterval(duration)
        logger.info("[SyncCore] paused until \(pausedUntil!) (\(Int(duration))s)")
    }
    func resume() {
        pausedUntil = nil
        logger.info("[SyncCore] resumed")
        scheduleSend(delay: 1)
    }

    /// Debounce for `scheduleSend`. Mirrors v1's 3s default.
    private let defaultSendDelay: TimeInterval = 3.0

    /// Set to true while an agent loop is streaming — markDirty still
    /// records into the dirty table (crash-safe) but does NOT trigger
    /// a debounced send. Mirrors v1's `syncSendDeferred`.
    private var sendDeferred: Bool = false

    // MARK: - State

    private var pendingSendTask: Task<Void, Never>?
    /// [T-icloud-device-heartbeat] 30-min SyncDeviceV2 re-announce loop.
    private var heartbeatTask: Task<Void, Never>?
    /// [T-icloud-inbound-serial-apply] The most recently started inbound
    /// apply task. Every `processInbound` call awaits this before touching a
    /// record, so batches apply strictly in hand-off order. See
    /// `processInbound` for why.
    private var inboundTail: Task<Void, Never>?
    private var inboundHandlers: [(SyncInboundBatch) -> Void] = []
    private var isSending = false

    /// recordIds we just successfully pushed to a transport. When the
    /// next fetchRecentV2 round-trips them back to us as "inbound", the
    /// merger would re-apply them (LWW skip → no real write, but still
    /// counted as `applied` which fires `cloudSyncDidFetchChanges` and
    /// makes every open AIChatViewModel reload its session for nothing).
    /// Entries older than `echoTTL` are pruned on access; effectively
    /// each push is shielded from echo for ~30s, plenty of time for the
    /// 60s fetch cycle to fire once and recognize itself.
    private var recentlyPushedIds: [String: Date] = [:]
    private let echoTTL: TimeInterval = 30

    // MARK: - Lifecycle

    /// Register a transport. Must be called before `start()`. LANTransport
    /// (and any other unimplemented transport) is rejected.
    func register(_ transport: SyncTransport) {
        if let lan = transport as? LANTransport, !LANTransport.isImplemented {
            logger.warning("[SyncCore] refusing to register LANTransport (skeleton only)")
            _ = lan
            return
        }
        // T-v2-hot-enable: register may be invoked twice when the user
        // hot-enables iCloud V2 (UI toggle off → on after launch). De-dup
        // by transport name so we don't accidentally drive every send/
        // fetch through two parallel ICloudSharedZoneTransport instances.
        if transports.contains(where: { $0.name == transport.name }) {
            logger.info("[SyncCore] transport \(transport.name) already registered — skipping")
            return
        }
        transports.append(transport)
        logger.info("[SyncCore] transport registered: \(transport.name) caps=\(String(transport.capabilities.rawValue, radix: 16))")
    }

    /// Boot all registered transports. Idempotent.
    func start() async {
        guard !isRunning else { return }
        logger.info("[SyncCore] start STEP=enter transports=\(self.transports.count)")
        // Hook each transport's observe(handler:) so inbound batches
        // funnel through SyncCore's merge path. Doing this BEFORE start()
        // means any messages the transport delivers during its own
        // boot sequence are not dropped.
        for t in transports {
            t.observe { [weak self] batch in
                Task { @MainActor [weak self] in
                    self?.processInbound(batch, from: t.name)
                }
            }
        }
        logger.info("[SyncCore] start STEP=observersHooked")
        // [T-icloud-device-heartbeat] Periodic re-announce while the app is
        // running. 30 min is far above the poll/push cadence and far below
        // "days"; the record is ~200 bytes. Cancelled on stop().
        heartbeatTask?.cancel()
        heartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30 * 60 * 1_000_000_000)
                guard !Task.isCancelled, self != nil, !DeviceIdentity.isProvisional else { continue }
                await ChatStore.shared.markDirty(recordType: "SyncDeviceV2", recordId: DeviceIdentity.deviceId)
                logger.info("[SyncCore] device heartbeat re-marked SyncDeviceV2 (30 min)")
            }
        }
        for t in transports {
            logger.info("[SyncCore] start STEP=startTransport begin name=\(t.name)")
            do {
                try await t.start()
                logger.info("[SyncCore] start STEP=startTransport done name=\(t.name)")
            } catch {
                logger.error("[SyncCore] transport \(t.name) start failed: \(error.localizedDescription)")
            }
        }
        isRunning = true
        logger.info("[SyncCore] start STEP=exit isRunning=true")
        startPathMonitor()
        // Drain any dirty rows left over from a previous session (e.g. a
        // markDirty that happened before the last shutdown). Without this
        // kick, leftover priority=0 rows just sit forever until the user
        // happens to write something new.
        scheduleSend(delay: 2)
    }

    private func startPathMonitor() {
        guard pathMonitor == nil else { return }
        let m = NWPathMonitor()
        m.pathUpdateHandler = { [weak self] path in
            // Mac Catalyst / macOS often don't report .wifi for the
            // active interface (path returns ethernet or no usable type).
            // Treat anything that isn't explicitly cellular as wifi-class
            // so the throttle multiplier is sane on those platforms.
            let cls: NetworkClass = path.usesInterfaceType(.cellular) ? .cellular : .wifi
            Task { @MainActor [weak self] in
                guard let self else { return }
                if self.networkClass != cls {
                    self.networkClass = cls
                    logger.info("[SyncCore] network class changed → \(cls)")
                }
            }
        }
        m.start(queue: DispatchQueue(label: "com.openminis.sync.pathMonitor"))
        pathMonitor = m
    }

    func stop() async {
        heartbeatTask?.cancel(); heartbeatTask = nil
        pendingSendTask?.cancel()
        pendingSendTask = nil
        for t in transports {
            await t.stop()
        }
        isRunning = false
        logger.info("[SyncCore] stopped")
    }

    // MARK: - Zone purge bracket

    /// [T-icloud-zone-delete-resurrect] Hold the engine still while the user
    /// deletes one of the `minis-*` zones from the Zones inventory. The
    /// transport keeps `saveZone` changes pending for all three, so a delete
    /// that is not bracketed like this lands and is then immediately undone by
    /// the engine re-creating the zone.
    ///
    /// Reuses `stop()` rather than adding a second teardown path, so the
    /// transports' own stop semantics stay in one place.
    func suspendForZonePurge() async {
        guard isRunning else { return }
        logger.info("[SyncCore] suspending for zone purge")
        await stop()
    }

    /// Restart after a zone purge. `start()` self-guards on `isRunning`, and
    /// re-creates any zone still genuinely in use.
    func resumeAfterZonePurge() async {
        guard SyncV2Bootstrap.isEnabled else { return }
        logger.info("[SyncCore] resuming after zone purge")
        await start()
    }

    // MARK: - Deferred mode

    func setSendDeferred(_ deferred: Bool) {
        let was = sendDeferred
        sendDeferred = deferred
        if was != deferred {
            logger.info("[SyncCore] sendDeferred: \(deferred)")
        }
    }

    // MARK: - markDirty

    /// Mark a single record as needing to be pushed. `model` becomes the
    /// authoritative source for the build step (we extract via the
    /// type's metadata). The record is persisted to the dirty queue
    /// immediately (crash-safe) and a debounced send is scheduled
    /// unless `sendDeferred` is on.
    func markDirty<T: Syncable>(_ model: T, reason: SyncDirtyReason = .localUpsert) async {
        let m = T.syncMetadata
        let id = m.id(of: model)
        await ChatStore.shared.markDirty(recordType: m.recordType, recordId: id)
        logger.debug("[SyncCore] markDirty: type=\(m.recordType) id=\(id.prefix(8)) reason=\(reason.rawValue)")
        if !sendDeferred {
            scheduleSend()
        }
    }

    /// Mark for delete. The record will be pushed as a deletion (transport
    /// translates this into the appropriate API — CKDatabase.delete for
    /// iCloud, broadcast tombstone for LAN).
    func markDeleted<T: Syncable>(_ recordType: T.Type, id: String, reason: SyncDirtyReason = .localDelete) async {
        let m = T.syncMetadata
        await ChatStore.shared.markDirty(recordType: m.recordType, recordId: id, operation: "delete")
        logger.debug("[SyncCore] markDeleted: type=\(m.recordType) id=\(id.prefix(8)) reason=\(reason.rawValue)")
        if !sendDeferred {
            scheduleSend()
        }
    }

    // MARK: - scheduleSend / sendNow

    /// Coalesce-then-send with `defaultSendDelay`. Cancels any prior
    /// pending send so a burst of markDirty calls produces one push.
    func scheduleSend(delay: TimeInterval? = nil) {
        let actualDelay = delay ?? defaultSendDelay
        pendingSendTask?.cancel()
        pendingSendTask = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: UInt64(actualDelay * 1_000_000_000))
            } catch {
                return // cancelled — a newer scheduleSend will run
            }
            await self?.sendNow(trigger: .scheduledDebounce)
        }
    }

    /// Immediately push everything in the dirty table, fanning out to
    /// every transport. Returns when all transports have completed (or
    /// errored).
    func sendNow(trigger: SyncSendTrigger) async {
        guard isRunning, !transports.isEmpty else {
            logger.warning("[SyncCore] sendNow skipped — not running (isRunning=\(self.isRunning) transports=\(self.transports.count)) trigger=\(trigger.rawValue)")
            return
        }
        // User-initiated pause. markDirty keeps recording so nothing is
        // lost — we just don't push until the deadline passes.
        if let until = pausedUntil {
            let wait = until.timeIntervalSinceNow
            logger.info("[SyncCore] sendNow paused — until \(until) (\(String(format: "%.0f", wait))s remaining) trigger=\(trigger.rawValue)")
            scheduleSend(delay: max(wait + 1, 60))
            return
        }
        // Honor server-issued throttle window. CloudKit returns CKError
        // requestRateLimited (HTTP 429) with a retryAfterSeconds value;
        // hammering iCloud inside that window just extends it.
        if let gate = nextEarliestSendAt, gate > Date() {
            let wait = gate.timeIntervalSinceNow
            logger.info("[SyncCore] sendNow deferred — throttled until \(gate) (\(String(format: "%.1f", wait))s remaining) trigger=\(trigger.rawValue)")
            scheduleSend(delay: wait + 0.5)
            return
        }
        guard !isSending else {
            logger.debug("[SyncCore] sendNow skipped — already sending")
            return
        }
        isSending = true; defer { isSending = false }

        // Drain any pending file changes recorded by the iSH fakefs
        // change tracker (shell tools writing under /var/minis/<subdir>)
        // and translate them into SessionFile dirty rows. This must run
        // BEFORE loadDirtyRecords so the freshly-marked rows are picked
        // up in this push cycle. Cheap when nothing pending.
        await drainSessionFileChangesIntoDirty()

        // Snapshot dirty rows.
        let dirty = await ChatStore.shared.loadDirtyRecords(v2Only: true)
        guard !dirty.isEmpty else {
            // DIAG: was debug-level, but "nothing dirty when user expects
            // a write to have just happened" is the single most useful
            // signal for sync regressions (proves markDirty was the silent
            // step). Promote to info so it lands in user-shared logs.
            logger.info("[SyncCore] sendNow: nothing dirty trigger=\(trigger.rawValue)")
            return
        }

        // Build a PortableRecord for every dirty row whose recordType is
        // registered. Records the registry doesn't recognize are left in
        // the dirty table — a future build (with that type registered)
        // will pick them up.
        let registry = SyncableTypeRegistry.shared
        var batchRecords: [PortableRecord] = []
        var batchDeletes: [SyncRecordID] = []

        for row in dirty {
            guard let metadata = registry.metadata(for: row.recordType) else {
                continue
            }
            let recordId = SyncRecordID(type: row.recordType, id: row.recordId)
            if row.operation == "delete" {
                batchDeletes.append(recordId)
                _ = metadata
                if row.recordType == "Message" {
                    logger.warning("[EditSync] [SyncCore] queued delete tombstone Message id=\(row.recordId.prefix(8)) — will be sent to iCloud")
                }
                continue
            }
            // Build via the type's owner — SyncCore doesn't know how to
            // hydrate a SyncedX from local SQLite (that's per-type
            // ChatStore work). We delegate to a hydrator registered per
            // recordType (see `SyncCoreHydrators`).
            if let portable = await SyncCoreHydrators.shared.buildPortable(
                recordType: row.recordType, id: row.recordId
            ) {
                batchRecords.append(portable)
            }
            // If buildPortable returned nil there are two distinct cases:
            //   (a) no builder is registered for this recordType — KEEP
            //       the dirty row so a future patch with a registered
            //       builder can pick it up. Clearing it here would lose
            //       data silently (audit C3).
            //   (b) builder is registered but returned nil — local row
            //       is gone, clear the dirty so we don't loop trying to
            //       rebuild a missing record.
            else if !SyncCoreHydrators.shared.hasBuilder(recordType: row.recordType) {
                logger.warning("[SyncSchema] noBuilderForType: \(row.recordType):\(row.recordId.prefix(8)) — keeping dirty for future builder")
            } else {
                logger.info("[SyncCore] dropping stale dirty: \(row.recordType):\(row.recordId.prefix(8)) (no local row)")
                await ChatStore.shared.clearDirtyRecord(recordName: "\(row.recordType):\(row.recordId)")
            }
        }

        // [T-sync-message-resurrection] Remember which operation each
        // record was sent with, so an ack only clears the dirty row if it
        // still carries that operation (a delete marked mid-flight survives).
        var sentOps: [String: String] = [:]
        for row in dirty { sentOps["\(row.recordType):\(row.recordId)"] = row.operation }

        let batch = SyncOutboundBatch(records: batchRecords, deletes: batchDeletes)
        guard !batch.records.isEmpty || !batch.deletes.isEmpty else {
            return
        }

        var typeCounts: [String: Int] = [:]
        for r in batch.records { typeCounts[r.id.type, default: 0] += 1 }
        let breakdown = typeCounts.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " ")
        var deleteCounts: [String: Int] = [:]
        for d in batch.deletes { deleteCounts[d.type, default: 0] += 1 }
        let deleteBreakdown = deleteCounts.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " ")
        logger.info("[SyncCore] sending batch: records=\(batch.records.count) deletes=\(batch.deletes.count) [\(breakdown)] delTypes=[\(deleteBreakdown)] trigger=\(trigger.rawValue) transports=\(self.transports.count)")
        // Trace: list the head IDs so we can follow specific messages
        // through send → server-ack → peer hydrate.
        let traceIds = batch.records.prefix(5)
            .map { "\($0.id.type):\($0.id.id.prefix(8))" }
            .joined(separator: " ")
        if !traceIds.isEmpty {
            logger.info("[iCloudTrace] outgoing batch head: \(traceIds)")
        }
        let traceDelIds = batch.deletes.prefix(5)
            .map { "\($0.type):\($0.id.prefix(8))" }
            .joined(separator: " ")
        if !traceDelIds.isEmpty {
            logger.info("[iCloudTrace] outgoing deletes head: \(traceDelIds)")
        }

        // [OwnEchoSkip 2026-05-17 follow-up] Pre-register every id we're
        // about to push into the echo-suppress map BEFORE the network
        // call, not only after a successful outcome. fetchRecentV2 runs
        // on a separate task and can race the push — if it pulls our
        // own freshly-written record back during the ~1s window between
        // engine.sendChanges() start and pendingOutcomes finalization,
        // applyOutcomes hasn't stamped the id yet, so inbound treats it
        // as a peer write, fires cloudSyncDidFetchChanges, and the
        // chat view runs reloadMessagesFromDB.
        //
        // Stamping at send-time guarantees the suppression window opens
        // before any race can fire. The success path still re-stamps
        // (refreshes TTL), and we never clear an id here even on
        // transient/permanent failure — worst case the id stays in the
        // map for the full echoTTL, which is fine.
        let nowStamp = Date()
        for r in batch.records {
            recentlyPushedIds[r.id.description] = nowStamp
        }
        for d in batch.deletes {
            recentlyPushedIds[d.description] = nowStamp
        }

        // Fan out to each transport. Each transport's outcomes drive
        // dirty cleanup independently.
        var retryNeeded = false
        for t in transports {
            do {
                let outcomes = try await t.send(batch, trigger: trigger)
                await applyOutcomes(outcomes, transport: t.name, sentOps: sentOps)
                // Pull any throttle gate the transport learned about
                // server-side (e.g. CloudKit returned 429 with a partial
                // batch — we got outcomes back rather than a throw, but
                // CKError still carried retryAfterSeconds).
                if let gate = t.retryAfter, gate > Date(),
                   (nextEarliestSendAt ?? .distantPast) < gate {
                    nextEarliestSendAt = gate
                    logger.warning("[SyncCore] iCloud throttle gate set: nextEarliest=\(gate) (\(String(format: "%.1f", gate.timeIntervalSinceNow))s)")
                }
                // If any outcome was transient, schedule another send
                // so the engine drains the queue without waiting for
                // an external markDirty.
                if outcomes.contains(where: {
                    if case .transientFailure = $0 { return true }
                    if case .conflict = $0 { return true }
                    return false
                }) {
                    retryNeeded = true
                }
            } catch {
                let nse = error as NSError
                let ck = error as? CKError
                logger.error("[SyncCore] \(t.name) send threw: \(error.localizedDescription) domain=\(nse.domain) code=\(nse.code) userInfo=\(nse.userInfo)")
                // CloudKit asks us to back off. Read CKErrorRetryAfterKey
                // (also exposed as CKError.retryAfterSeconds) and gate the
                // next send so we don't make the throttle window worse.
                if let retryAfter = ck?.retryAfterSeconds {
                    let until = Date().addingTimeInterval(retryAfter)
                    if (nextEarliestSendAt ?? .distantPast) < until {
                        nextEarliestSendAt = until
                        logger.warning("[SyncCore] iCloud throttle gate set: nextEarliest=\(until) retryAfter=\(retryAfter)s")
                    }
                }
                retryNeeded = true
            }
        }
        if retryNeeded {
            // If a throttle gate was set, schedule respecting it; otherwise
            // a generic 10s backoff (network blip, transient partial).
            let delay: TimeInterval
            if let gate = nextEarliestSendAt, gate > Date() {
                delay = max(gate.timeIntervalSinceNow + 0.5, 10)
            } else {
                delay = 10
            }
            scheduleSend(delay: delay)
        } else {
            // Even on full success, the dirty queue may still have rows
            // we couldn't fit in this batch (loadDirtyRecords LIMIT 395
            // when 100k+ rows pending — the migration initial-push case).
            // Schedule another debounced send so the queue actually
            // drains. Skip when queue is empty.
            let after = await ChatStore.shared.countDirtyRecords()
            if after.total > 0 {
                logger.info("[SyncCore] sendNow done, dirty.total=\(after.total) remaining — chaining another send (delay=\(currentSendDelay)s, throttle=\(throttleLabel))")
                scheduleSend(delay: currentSendDelay)
            }
        }
    }

    /// Process per-record outcomes from a single transport. Successes →
    /// clear dirty. Transient failures → keep dirty. Permanent failures
    /// → clear dirty (we'd loop forever otherwise). Conflicts are
    /// handled by re-merging the server record locally; the transport
    /// is expected to also re-queue the record if it wants a retry.
    private func applyOutcomes(_ outcomes: [SyncOutcome], transport: String,
                               sentOps: [String: String] = [:]) async {
        var ok = 0, transient = 0, permanent = 0, conflict = 0
        for outcome in outcomes {
            switch outcome {
            case .success(let id):
                ok += 1
                // Remember this id so a fetchRecentV2 echoing it back in
                // the next 30s is recognized as our own write and skipped
                // — avoids re-triggering reloadMessagesFromDB for every
                // freshly-typed message.
                recentlyPushedIds[id.description] = Date()
                await ChatStore.shared.clearDirtyRecord(recordName: id.description,
                                                        ifOperation: sentOps[id.description])
            case .conflict(_, let serverRecord):
                conflict += 1
                processInbound(SyncInboundBatch(records: [serverRecord], deletes: [], sourceDeviceId: nil), from: transport, countAsReceived: false)
                // Note: we deliberately do NOT clear dirty here. The
                // transport (CloudKit) re-queues the record so the next
                // send retries with the fresh server etag.
            case .transientFailure:
                transient += 1
                // dirty preserved — next send retries
            case .permanentFailure(let id, let reason):
                permanent += 1
                logger.error("[SyncCore] permanent failure \(id) via \(transport): \(reason)")
                await ChatStore.shared.clearDirtyRecord(recordName: id.description,
                                                        ifOperation: sentOps[id.description])
            }
        }
        logger.info("[SyncCore] \(transport) outcomes: ok=\(ok) conflict=\(conflict) transient=\(transient) permanent=\(permanent)")
        if ok > 0 || conflict > 0 {
            lastSendAt = Date()
            totalSent += ok
            // Track for the recent-rate readout. Drop events older than
            // the window so the array can't grow unbounded.
            let now = Date()
            if ok > 0 { sendEvents.append((now, ok)) }
            let cutoff = now.addingTimeInterval(-Self.rateWindow)
            while let first = sendEvents.first, first.0 < cutoff {
                sendEvents.removeFirst()
            }
        }
    }

    // MARK: - Inbound

    /// Apply a remote batch to local SQLite via per-type appliers
    /// registered through `SyncCoreHydrators`. Skips records whose
    /// recordType is not registered (per §3.6.3).
    /// Stable sort: containers (folders, sessions) first, then their
    /// children, then everything else in arrival order.
    private static let applyRank: [String: Int] = [
        "FolderV2": 0, "SessionV2": 1,
        "MessageV2": 2, "CompactMarkerV2": 2, "SessionFileV2": 2,
    ]
    static func parentsFirst(_ records: [PortableRecord]) -> [PortableRecord] {
        // Fast path: nothing to reorder when no ranked type is present or
        // the batch is a single type.
        guard records.count > 1,
              records.contains(where: { applyRank[$0.id.type] != nil }) else { return records }
        return records.enumerated().sorted { a, b in
            let ra = applyRank[a.element.id.type] ?? 3, rb = applyRank[b.element.id.type] ?? 3
            return ra != rb ? ra < rb : a.offset < b.offset
        }.map(\.element)
    }

    func processInbound(_ batch: SyncInboundBatch, from transport: String, countAsReceived: Bool = true) {
        // [T-icloud-inbound-serial-apply] Batches apply strictly in the order
        // processInbound is called. Transports hand a fetch over in 50-record
        // slices (fetchRecentV2; CKSyncEngine's fetched-changes path, which
        // also sends its deletes as a separate trailing batch), and each call
        // used to start an independent MainActor task. Those tasks interleave
        // at every await (the ChatStore actor hop per record, the 50 ms yield
        // per 25 records), so parentsFirst — which only sorts WITHIN a batch —
        // did not hold across slices: slice 2's MessageV2 records ran while
        // slice 1 was still writing their SessionV2 rows, hit
        // mergeRemoteMessage's `guard sessionExists` and were dropped. Worse,
        // the small last slice finished first and its onApplied committed the
        // poll cursor past those dropped rows, so they were never re-fetched.
        //
        // Fix: each apply task awaits the previous one (`inboundTail`) before
        // it starts. processInbound is synchronous on the main actor, so the
        // tail swap below is atomic and the chain order is the call order.
        // A task only ever awaits a task created before it, never itself, so
        // the chain cannot deadlock; the apply body does not throw (every
        // await is `try?` or non-throwing), so a slice always completes and
        // releases the next one. Hydrators never call back into
        // processInbound and wait on it, which is the only way a cycle could
        // form. "Last slice applied" therefore really implies "every earlier
        // slice applied", which is what the cursor commit relies on.
        let previous = inboundTail
        guard !batch.records.isEmpty || !batch.deletes.isEmpty else {
            // An empty batch has nothing to apply, but its completion must
            // still wait for every batch handed over before it.
            guard let onApplied = batch.onApplied else { return }
            guard let previous else { onApplied(); return }
            inboundTail = Task { @MainActor in
                await previous.value
                onApplied()
            }
            return
        }
        // Don't inflate Sync Activity counters when this batch is just the
        // server-side echo from a conflict (our own send racing another
        // device). Real remote fetches and observe-driven inbound do
        // count.
        if countAsReceived {
            lastFetchAt = Date()
            totalReceived += batch.records.count + batch.deletes.count
        }
        let registry = SyncableTypeRegistry.shared
        // Hydrate inbound in 25-record chunks with a 50ms yield between
        // chunks. Without this, a 600-record fetch holds the full
        // PortableRecord array (each carrying message content + maybe
        // inline attachments) in main-actor scope while the merger
        // thrashes ChatStore + UI for tens of seconds, and ARC has no
        // chance to release intermediate Codable structs. Observed:
        // 3+GB resident before OOM crash on a 1196-session migration.
        inboundTail = Task { @MainActor [weak self] in
            // [T-icloud-inbound-serial-apply] Wait for every earlier batch.
            await previous?.value
            var applied = 0, skipped = 0, blocked = 0, ownEcho = 0
            // [T-icloud-sync-tier1-parents-first] Apply parents before
            // children. A batch arrives in server order, and a session's
            // MessageV2 records routinely precede its SessionV2 in the same
            // fetch. mergeRemoteMessage refuses a message whose session row
            // does not exist yet (`guard sessionExists`), so those messages
            // were dropped and only re-pulled on the next 120 s poll —
            // measured on 2026-09-19: the peer showed an empty session for
            // 73 s. Ordering by type inside the batch is one sort and makes
            // the guard a true no-op for the in-order case. Types not listed
            // keep their relative order after the listed ones.
            let allRecords = Self.parentsFirst(batch.records)
            let allDeletes = batch.deletes
            let chunkSize = 25
            var i = 0
            // Prune stale echo-suppress entries once per batch so the map
            // doesn't grow unbounded over an idle session.
            let now = Date()
            if let strongSelf = self {
                let ttl = strongSelf.echoTTL
                strongSelf.recentlyPushedIds = strongSelf.recentlyPushedIds.filter { now.timeIntervalSince($0.value) < ttl }
            }
            while i < allRecords.count {
                let end = min(i + chunkSize, allRecords.count)
                for j in i..<end {
                    let record = allRecords[j]
                    guard let metadata = registry.metadata(for: record.id.type) else {
                        logger.info("[SyncSchema] unknownRecordType: type=\(record.id.type) source=\(transport) action=ignored")
                        skipped += 1
                        continue
                    }
                    if let minimum = record.minimumCompatibleVersion, minimum > metadata.version {
                        logger.warning("[SyncSchema] minimumCompatibleVersionBlocked: type=\(record.id.type) required=\(minimum) local=\(metadata.version)")
                        blocked += 1
                        continue
                    }
                    // [OwnEchoSkip 2026-05-17] If we just pushed this exact
                    // record id within echoTTL, fetchRecentV2 is echoing
                    // it back at us. Hydrator's LWW would skip the write
                    // anyway, but it would still count toward `applied`
                    // and fire cloudSyncDidFetchChanges — re-rendering
                    // every cached AIChatViewModel for nothing. Drop it
                    // from the inbound flow so the notification only
                    // fires for genuinely new peer writes.
                    if let pushedAt = self?.recentlyPushedIds[record.id.description],
                       now.timeIntervalSince(pushedAt) < (self?.echoTTL ?? 30) {
                        ownEcho += 1
                        continue
                    }
                    if record.schemaVersion != metadata.version {
                        logger.info("[SyncSchema] schemaVersionMismatch: type=\(record.id.type) local=\(metadata.version) remote=\(record.schemaVersion)")
                    }
                    if !record.unknownFields.isEmpty {
                        // [T-ios-log-noise-reduction] Dedup by (type + sorted
                        // keys): log the first occurrence of each combination
                        // once, then suppress. Downgraded to debug so even the
                        // first-seen line is Debug-only.
                        let dedupKey = "\(record.id.type)|\(record.unknownFields.keys.sorted().joined(separator: ","))"
                        if loggedUnknownFieldKeys.insert(dedupKey).inserted {
                            logger.debug("[SyncSchema] unknownFields (first-seen, further occurrences suppressed): type=\(record.id.type) keys=\(record.unknownFields.keys.sorted())")
                        }
                    }
                    await SyncCoreHydrators.shared.mergeRemote(record)
                    applied += 1
                }
                i = end
                // Yield so the main thread can run UI / scrolling / SwiftUI
                // diff for whatever the user is doing in the foreground,
                // and so ARC has a chance to release the chunk's Codable
                // intermediaries before the next chunk allocates more.
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
            for deleteId in allDeletes {
                guard registry.metadata(for: deleteId.type) != nil else {
                    skipped += 1
                    if deleteId.type == "Message" {
                        logger.warning("[EditSync] inbound delete SKIPPED — no registry metadata for type=Message id=\(deleteId.id.prefix(8))")
                    }
                    continue
                }
                if deleteId.type == "Message" {
                    logger.warning("[EditSync] applying inbound delete Message id=\(deleteId.id.prefix(8)) — calling hydrator.applyRemoteDeletion")
                }
                await SyncCoreHydrators.shared.applyRemoteDeletion(deleteId)
                applied += 1
            }
            logger.info("[SyncCore] inbound from \(transport): applied=\(applied) skipped=\(skipped) blocked=\(blocked) ownEcho=\(ownEcho)")
            // Wake up any AIChatViewModel currently rendering an affected
            // session so it can `reloadMessagesFromDB`. Without this, the
            // SQLite rows landed but the on-screen messages array stays
            // frozen — `lastMessage` in session list updates (it reads
            // from DB) yet the chat view doesn't. The legacy v1
            // CloudSyncEngine posts the same notification; the v2 SyncCore
            // had been silently skipping it, leaving v2-only setups stale.
            //
            // [OwnEchoSkip 2026-05-17] `applied` no longer counts records
            // we just pushed (those are filtered as `ownEcho` above), so
            // this notification only fires for genuinely new peer
            // changes — no more reload thrash from our own writes
            // round-tripping through fetchRecentV2.
            if applied > 0 {
                NotificationCenter.default.post(name: .cloudSyncDidFetchChanges, object: nil)
            }
            // [T-icloud-sync-tier1-cursor-after-apply] Every record above is
            // now written (or deliberately skipped). Tell the transport so it
            // can commit its cursor. Runs after the notification on purpose:
            // the cursor is bookkeeping, the data is what matters.
            batch.onApplied?()
            _ = self
        }
    }

    // MARK: - Diagnostics

    /// Snapshot of dirty queue state (v2 path), surfaced via
    /// `debug.sync.dirtyByType`.
    func dirtySummary() async -> (total: Int, byType: [String: Int]) {
        await ChatStore.shared.countDirtyRecords()
    }

    // MARK: - Fetch (active)

    /// Trigger an incremental fetch on every transport that supports
    /// `.deltaFetch`. Inbound batches arrive via the observe callback so
    /// no return value is needed.
    func fetchNow(trigger: SyncFetchTrigger) async {
        guard isRunning else { return }
        for t in transports where t.capabilities.contains(.deltaFetch) {
            do {
                let batch = try await t.fetchChanges(trigger: trigger)
                if !batch.records.isEmpty || !batch.deletes.isEmpty {
                    processInbound(batch, from: t.name)
                }
            } catch {
                logger.error("[SyncCore] \(t.name) fetchChanges threw: \(error.localizedDescription)")
            }
        }
    }

    /// Full-fetch every transport. Reconcile-after-fetch is currently
    /// DISABLED because the partial-batch risk (audit C2) is not yet
    /// resolved: CKSyncEngine.fetchChanges() does not guarantee the
    /// returned `SyncInboundBatch` is the complete cloud snapshot — for
    /// large libraries it may resolve before all pages are observed,
    /// and tombstoning every local-only session against that partial
    /// view would silently destroy live data. Until the transport
    /// surfaces a "fetch fully drained" signal (CKSyncEngine's
    /// `didFetchChanges` with no more events), we apply the inbound
    /// batch but skip the tombstone reconcile. The "ghost session"
    /// case described in §3.3.5 / §3.6.0 falls back to its safe state
    /// (local copy preserved, eventual user mutation triggers
    /// resurrect cascade).
    func fullFetchAndReconcile(trigger: SyncFetchTrigger) async {
        guard isRunning else { return }
        for t in transports {
            do {
                let batch = try await t.fullFetch(trigger: trigger)
                processInbound(batch, from: t.name)
                logger.info("[SyncCore] fullFetch via \(t.name): records=\(batch.records.count) deletes=\(batch.deletes.count) (reconcile skipped — see C2 in audit)")
            } catch {
                logger.error("[SyncCore] \(t.name) fullFetch threw: \(error.localizedDescription)")
            }
        }
    }

    /// Per-session force pull — locates the iCloud transport and asks it
    /// to CKQuery the session's Session/Message/CompactMarker records.
    /// Returns the number of inbound portables observed (so the caller can
    /// surface a "Pulled N records" toast). Records flow through the
    /// normal observer path, so they end up in SQLite via the standard
    /// hydrators just like any other inbound batch.
    /// Fetch the cloud-side portables for a given session WITHOUT applying
    /// them to local SQLite. Used by ChatStore.forcePullSession to look
    /// before it leaps: caller swaps local rows only after confirming the
    /// cloud copy is non-empty. Returns (portables, error). On a clean
    /// "cloud returned nothing" outcome both elements are nil/empty.
    @available(iOS 17.0, *)
    func fetchSessionPortables(sessionId: String) async -> ([PortableRecord], Error?) {
        logger.warning("[ForcePull] SyncCore.fetchSessionPortables sid=\(sessionId.prefix(8)) isRunning=\(self.isRunning) transports=\(self.transports.count)")
        for t in transports {
            logger.warning("[ForcePull] inspecting transport name=\(t.name)")
            if let iCloud = t as? ICloudSharedZoneTransport {
                do {
                    let portables = try await iCloud.fetchSessionPortables(sessionId: sessionId)
                    logger.warning("[ForcePull] fetchSessionPortables sid=\(sessionId.prefix(8)) records=\(portables.count)")
                    return (portables, nil)
                } catch {
                    logger.error("[ForcePull] fetchSessionPortables threw: \(error.localizedDescription)")
                    return ([], error)
                }
            }
        }
        logger.warning("[ForcePull] no ICloudSharedZoneTransport found in transports list — returning empty")
        return ([], nil)
    }

    /// Apply a pre-fetched portable batch through the standard inbound
    /// path (hydrators land them in SQLite). Used by forcePullSession
    /// after it has committed to swapping local for cloud.
    func applyPortables(_ portables: [PortableRecord], transportName: String) async {
        guard !portables.isEmpty else { return }
        let chunk = 50
        var i = 0
        while i < portables.count {
            let end = min(i + chunk, portables.count)
            let slice = Array(portables[i..<end])
            await MainActor.run {
                self.processInbound(SyncInboundBatch(records: slice, deletes: [], sourceDeviceId: nil), from: transportName)
            }
            i = end
        }
    }

    // MARK: - SessionFile change tracker drain

    /// Drain pending file changes from `SessionFileChangeTracker` (events
    /// emitted by the iSH fakefs realfs hooks for any shell-tool write,
    /// truncate, unlink, rename inside `/var/minis/<workspace|attachments|browser|offloads>/...`)
    /// and turn them into SessionFile v2 dirty rows.
    ///
    /// Called from `sendNow` immediately before reading dirty rows so the
    /// freshly-marked SessionFile rows are picked up in this same push.
    /// Tracker is cleared atomically; events recorded between drain and
    /// next sendNow stay in the next snapshot — never lost.
    private func drainSessionFileChangesIntoDirty() async {
        // Filter on host mtime: a recorded open(W) only converts to a
        // dirty row if the file actually changed since (or just before)
        // we observed the open. Suppresses false positives where a
        // process opens write-mode but never writes. Entries whose
        // mtime hasn't caught up yet stay in the tracker for the next
        // push cycle — the actual write may still be in flight.
        let baseURL = await ChatStore.shared.minisBaseURL
        let snapshot = await SessionFileChangeTracker.shared.drainAllWithMtimeFilter(
            minisBaseURL: baseURL)
        guard !snapshot.isEmpty else { return }

        var totalUpsert = 0, totalDelete = 0
        var sampleRecordId: String? = nil
        for (sid, files) in snapshot {
            for (rel, change) in files {
                let recordId = "\(sid):\(rel)"
                if sampleRecordId == nil { sampleRecordId = recordId }
                switch change.op {
                case .upsert:
                    totalUpsert += 1
                    await ChatStore.shared.markDirty(
                        recordType: "SessionFile",
                        recordId: recordId,
                        operation: "upsert",
                        priority: 0
                    )
                case .delete:
                    totalDelete += 1
                    await ChatStore.shared.markDirty(
                        recordType: "SessionFile",
                        recordId: recordId,
                        operation: "delete",
                        priority: 0
                    )
                }
            }
        }
        let totalFiles = totalUpsert + totalDelete
        let sampleStr = sampleRecordId.map { String($0.prefix(80)) } ?? "?"
        logger.info("[SyncCore] file-tracker → markDirty: sessions=\(snapshot.count) files=\(totalFiles) (upsert=\(totalUpsert) delete=\(totalDelete)) sample=\(sampleStr) — these will be pushed in this cycle")
    }
}
