import Foundation
import CloudKit

private let logger = AppLogger(category: "SyncTransport")

/// CloudKit transport for v2. All same-Apple-ID devices share three
/// fixed zones in the private database (no per-device zones — that's
/// the whole point of v2). The transport translates PortableRecord ↔
/// CKRecord, drives a CKSyncEngine to do the actual work, and surfaces
/// conflicts / transient errors back to SyncCore.
///
/// Zones:
///   - minis-shared   : Session/Message/CompactMarker/SessionFile/Skill records
///   - minis-devices  : SyncDevice heartbeat records
///   - minis-secrets  : ProviderConfig + EnvVar (separate so users can
///                      toggle this category off without losing chat sync)
@available(iOS 17.0, *)
@MainActor
final class ICloudSharedZoneTransport: NSObject, SyncTransport {

    // MARK: - SyncTransport conformance

    let name = "iCloud"
    let capabilities: TransportCapabilities = [.deltaFetch, .assets, .persistence, .conflictDetect]

    private var observeHandler: ((SyncInboundBatch) -> Void)?

    // MARK: - Configuration

    static let containerIdentifier = "iCloud.com.openminis.app"

    /// Fixed zone names. Never include device id.
    static let sharedZoneName  = "minis-shared"
    static let devicesZoneName = "minis-devices"
    static let secretsZoneName = "minis-secrets"

    /// recordType → zone name. Drives where each type's records live.
    /// Stays consistent with §3.1 of the design.
    private static let zoneByRecordType: [String: String] = [
        "SessionV2":          sharedZoneName,
        "MessageV2":          sharedZoneName,
        "CompactMarkerV2":    sharedZoneName,
        "SessionFileV2":      sharedZoneName,
        "FolderV2":           sharedZoneName,
        "SkillV2":            sharedZoneName,
        "SyncDeviceV2":       devicesZoneName,
        "ProviderConfigV2":   secretsZoneName,
        "EnvVarV2":           secretsZoneName,
        "EnvVarItem":         secretsZoneName,
        "SoulV2":             sharedZoneName,
        "MemoryGlobalV2":     sharedZoneName,
        "MemoryDailyV2":      sharedZoneName,
        // [T-provider-sync-v3-zone-mapping] Per-record provider sync v3.
        // Instances carry secretBlob (encrypted API keys), so they belong
        // in the secrets zone alongside ProviderConfigV2. Entries and
        // groups have no secret fields, but we keep all three V3 types
        // co-located in secretsZoneName so a single push batch lands the
        // related records together (LWW conflict resolution is per-record
        // anyway, and "wipe my synced providers" can target one zone
        // instead of two). Without these entries, V3 dirty rows were
        // silently dropped by the transport — CloudKit Dashboard never
        // showed the new RecordTypes and no zones were created.
        "ProviderInstanceV3":   secretsZoneName,
        "ProviderModelEntryV3": secretsZoneName,
        "ProviderModelGroupV3": secretsZoneName,
        "ProviderThinkingRuleV3": secretsZoneName,
        // [T-mcp-sync-zone-mapping] servers.json whole-file record. Was missing
        // from this map, so every MCPServersV2 portable was silently dropped at
        // the push loop's zone guard — MCP servers never reached CloudKit at
        // all. Headers/env values can hold literal tokens, so co-locate with
        // ProviderConfigV2 in the secrets zone.
        "MCPServersV2":         secretsZoneName,
        // [T-mcp-per-server-sync] Per-server MCP records (recordName =
        // server name). Same secrets-zone placement as the legacy
        // whole-file record: headers/env can hold literal tokens.
        "MCPServerItem":        secretsZoneName,
    ]

    /// Asset threshold — payloads larger than this are written as a
    /// CKAsset; smaller ones as inline CKRecordValue strings. Mirrors v1
    /// (matches the partsAsset / configAsset logic at 750KB).
    private static let assetInlineThreshold = 750_000

    // MARK: - State

    private let container: CKContainer
    private var syncEngine: CKSyncEngine?
    private var stateSerialization: CKSyncEngine.State.Serialization?
    private let stateURL: URL

    /// In-memory etag cache. Records survived a save/fetch cycle so we
    /// reuse their CKRecord system fields on the next write — preserving
    /// any unknown fields that newer clients may have populated (per
    /// §3.6.4 of the design). Persisted to disk so a fresh launch
    /// already knows which records exist on cloud → second push attempt
    /// can use the right etag instead of round-tripping
    /// "record already exists" via the conflict path.
    /// Bounded record cache. Keeps the most recent server-issued
    /// CKRecords so we can reuse their systemFields/etag on next push
    /// (avoids one round-trip's "record already exists" conflict). Hard
    /// cap enforced because an unbounded dictionary holding 120k+
    /// CKRecord (each ~3-5KB of NSData systemFields) was responsible
    /// for ~1.5GB resident on a heavily-migrated device.
    /// On miss we just construct a fresh CKRecord — the next save will
    /// hit a serverRecordChanged conflict that the existing path
    /// recovers from cleanly.
    private static let serverRecordCacheCap = 5000
    private var serverRecordCache: [CKRecord.ID: CKRecord] = [:] {
        didSet {
            guard serverRecordCache.count > Self.serverRecordCacheCap else { return }
            // Evict oldest 20% (insertion-order via Dictionary's stable
            // iteration on small additions; not strict LRU, but cheap).
            let evictCount = serverRecordCache.count / 5
            for key in serverRecordCache.keys.prefix(evictCount) {
                serverRecordCache.removeValue(forKey: key)
            }
        }
    }
    private var etagCacheURL: URL {
        FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MinisChat/cloud-sync-v2/etag-cache.plist")
    }
    /// Set to true whenever serverRecordCache mutates; cleared after
    /// flush. Avoids writing on every tiny change.
    private var etagCacheDirty: Bool = false

    /// Pending records to upload, accumulated by SyncCore's send() call
    /// and consumed by the engine's nextRecordZoneChangeBatch delegate.
    private var pendingRecords: [CKRecord] = []
    private var pendingDeletes: [CKRecord.ID] = []

    /// Record names for which we've already attempted the `.unknownItem`
    /// etag-clear + re-create recovery once. Bounds the recovery to a single
    /// retry per record per process lifetime so a record that genuinely can't
    /// be created (e.g. a permanent server rejection masquerading as
    /// NOT_FOUND) can't re-enter the recreate path every wake and reopen the
    /// retry loop the recovery is meant to close. [T-icloud-notfound-retry-loop]
    private var unknownItemRecreateAttempted: Set<String> = []

    /// Continuations resolving back to send()'s caller, keyed by
    /// recordName so we can fan outcomes back to the SyncOutcome list.
    private var pendingOutcomes: [String: SyncOutcome] = [:]
    private var sendCompletion: CheckedContinuation<[SyncOutcome], Error>?

    /// Set during fullFetch / fetchChanges so the inbound delegate can
    /// accumulate records into a single batch the caller awaits.
    private var fetchAccumulator: (records: [PortableRecord], deletes: [SyncRecordID])?
    private var fetchCompletion: CheckedContinuation<SyncInboundBatch, Error>?

    /// V1 record IDs corresponding to V2 records that have been confirmed
    /// saved on the server. Drained in batches of `v1DeleteBatchSize` so
    /// space is reclaimed throughout migration instead of all-at-once at
    /// phaseV1ZoneDelete (which would temporarily double iCloud usage).
    private var pendingV1Deletions: [CKRecord.ID] = []
    private static let v1DeleteBatchSize = 100
    /// Hard cap on the resident v1-deletion queue. Each entry is a CKRecord.ID
    /// (plus its CKRecordZone.ID) and there is one per migrated v1 record —
    /// dominated by Message → so on a large history this array grew to tens of
    /// thousands of IDs (32k seen in a memgraph), a major resident-memory
    /// contributor toward jetsam. Overflow past the cap is dropped, not
    /// retained: the v1 zone is deleted wholesale at end-of-migration
    /// (`deleteOwnZone`), so any un-reclaimed v1 record is cleaned up there —
    /// incremental per-record deletion is only a quota optimization during the
    /// migration window, never correctness-critical. [T-ios-v1delete-queue-cap]
    private static let pendingV1DeletionsCap = 500
    private var v1DeletionsDroppedOverCap = 0
    /// Counters mirrored to UserDefaults for the migration progress UI.
    private static let v1DeletedKey = "cloudSync.v2.v1RecordsDeleted"

    /// Runtime-only dedup set for the diagnostic "firstSeen vs repeats"
    /// split in handleSentRecordZoneChanges. Resets on app launch (we
    /// don't want a 437k-entry Set persisted to UserDefaults).
    /// Persistent dedup for pushedCumulative is a separate change.
    nonisolated(unsafe) private static var observedSavedRecordIDs: Set<String> = []
    /// [OwnEchoSkip transport-level] recordName → timestamp of our last
    /// successful save. Used by handleFetchedZoneChanges to drop modifications
    /// whose recordName matches a save we made in the last `echoTTL` seconds —
    /// CKSyncEngine routinely re-delivers our own writes back to us via the
    /// changeToken stream, and without filtering they show up as fresh peer
    /// records, fire cloudSyncDidFetchChanges, and trigger a session reload.
    /// SyncCore's outbound-side echo map (`recentlyPushedIds`) covers the
    /// SyncCore.sendOnce path, but CKSyncEngine also self-schedules pushes
    /// from `state.add(pendingRecordZoneChanges:)` that don't necessarily
    /// flow through SyncCore — this transport-level mirror catches those too.
    private var recentlyPushedRecordNames: [String: Date] = [:]
    private let recentlyPushedTTL: TimeInterval = 30
    private static let v1DeletePendingKey = "cloudSync.v2.v1RecordsDeletePending"
    /// In-flight guard so concurrent saves don't fire overlapping delete ops.
    private var v1DeleteInFlight: Bool = false
    /// Consecutive failures in the v1-delete pipeline; pauses retries when
    /// it hits the cap so a chronic failure doesn't spam the log forever.
    private var v1DeleteConsecutiveFailures: Int = 0
    private static let v1DeleteFailureCap: Int = 5
    /// Recent-window fast-path fetch (CKQueryOperation) — runs in
    /// parallel with CKSyncEngine's token-driven fetch so newly-typed
    /// peer messages don't have to wait their turn behind a multi-day
    /// migration backlog. Last successful execution time, persisted so
    /// we don't re-query the same window after a quick relaunch.
    private static let recentFetchLastKey = "cloudSync.v2.recentFetchLastAt"
    /// In-flight guard.
    private var recentFetchInFlight: Bool = false
    /// Repeating timer that re-runs fetchRecentV2 every recentFetchInterval
    /// seconds while the engine is up.
    private var recentFetchTimer: DispatchSourceTimer?
    // [T-ios-cloudkit-nsoperation-weak-clear-crash] Each fetchRecentV2 run
    // fires one CKQuery (records(matching:)) per record type — 10 XPC
    // round-trips through cloudd, each serializing an NSPredicate. iOS 26.5
    // CloudKit has an intermittent framework crash where a CKQueryOperation
    // deallocated inside the XPC reply autorelease-pool drain clears a weak
    // table at a corrupt 0x1_0000_0000 pointer (EXC_BAD_ACCESS). We can't
    // fix Apple's framework, but we cut the exposure: poll every 120s
    // instead of 60s (token-driven CKSyncEngine + silent-push still cover
    // freshness; this fast-path only trades worst-case active-poll latency
    // 60s→120s for halving the XPC surface), and back off exponentially on
    // ANY fetch error (not just server-signalled throttle) so a flaky/
    // crashing CloudKit layer is given room instead of being hammered.
    private static let recentFetchInterval: TimeInterval = 120  // 2 min (was 60)
    private static let recentFetchWindow: TimeInterval = 24 * 60 * 60   // last 24 h

    // [T-icloud-fresh-restore-provider-groups] Secrets-zone "config" record
    // types whose payload is low-volume but long-lived (created once, edited
    // rarely). The global 24h `recentFetchWindow` is wrong for these on a fresh
    // device restored from iCloud: the user's providers / model groups were
    // created months ago, so a `now − 24h` floor never sees them and they stay
    // invisible. These types instead pull their FULL history (no time floor)
    // until they have been anchored once via `configTypeAnchoredKey`.
    private static let fullHistoryConfigTypes: Set<String> = [
        "ProviderInstanceV3", "ProviderModelEntryV3", "ProviderModelGroupV3",
        "ProviderThinkingRuleV3",
        "ProviderConfigV2", "MCPServersV2", "MCPServerItem", "EnvVarItem",
        // Folders are low-volume config-like records: a fresh device must
        // pull the full set (a folder created months ago would never fall
        // inside the 24h window), and sessions arriving before their folder
        // render as ungrouped until this pull anchors.
        "FolderV2",
    ]
    /// Per-type "we have completed at least one full-history pull AND the
    /// consumer was ready to apply it" flag. Until set, the type pulls full
    /// history every run. Keyed `cloudSync.v2.configAnchored.<type>`.
    private static func configTypeAnchoredKey(_ type: String) -> String {
        "cloudSync.v2.configAnchored.\(type)"
    }
    /// [T-provider-anchor-two-run-confirm] Count fetched by the LAST clean
    /// full-history pull of an un-anchored type. Anchoring requires TWO
    /// consecutive clean full-history pulls returning the same count: CK
    /// queries are eventually consistent, and anchoring on a single
    /// "successful" pull permanently trusted a snapshot that could be a
    /// subset — observed on device as a peer's Default Model group never
    /// arriving because the anchor closed the full-history window before the
    /// record was visible to the query.
    private static func configTypeAnchorPendingCountKey(_ type: String) -> String {
        "cloudSync.v2.configAnchorPendingCount.\(type)"
    }

    /// [T-provider-forcesync-v3] Forget the provider types' "history fully
    /// pulled" anchors (and any pending confirmation counts) so the next
    /// fetchRecentV2 re-pulls their FULL history. Called by the Providers
    /// force-sync button — the manual escape hatch for a device whose anchor
    /// was set from an incomplete (eventually-consistent) pull and whose
    /// incremental window can therefore never see older-updatedAt records.
    static func resetProviderConfigAnchors() {
        let types = ["ProviderInstanceV3", "ProviderModelEntryV3",
                     "ProviderModelGroupV3", "ProviderThinkingRuleV3",
                     "ProviderConfigV2"]
        for t in types {
            UserDefaults.standard.removeObject(forKey: configTypeAnchoredKey(t))
            UserDefaults.standard.removeObject(forKey: configTypeAnchorPendingCountKey(t))
        }
        logger.info("[iCloudTrace] provider config anchors reset — next fetchRecentV2 pulls full history for \(types.count) types")
    }
    /// Consecutive fetchRecentV2 runs that hit at least one CKQuery error.
    /// Drives exponential backoff via `retryAfter`; reset to 0 on a clean run.
    private var consecutiveFetchFailures: Int = 0
    /// Backoff schedule (seconds) indexed by failure count, capped at the
    /// last entry. ~2min → 5min → 10min → 20min → 30min ceiling.
    private static let fetchBackoffSchedule: [TimeInterval] = [120, 300, 600, 1200, 1800]

    // [T-ios-icloud-ckrecordid-nilname-crash] Pre-flight validation for
    // `CKRecord.ID(recordName:zoneID:)`. iOS 26.6 Beta raises an ObjC
    // exception (crashing launch via objc_terminate) on empty / whitespace /
    // out-of-range names; earlier 26.x stable was permissive.
    //
    // [T-ios-sessionfile-recordname-overreject] The original guard used an
    // ASCII letters/digits/_-:. whitelist, assuming every recordName was
    // `"<type>:<uuid>"`. But SessionFileV2 recordNames embed a file relative
    // path — `"<type>:<sessionId>:<dir>/<name>"` — so they legitimately
    // contain `/` and may contain non-ASCII (CJK / emoji) filenames. The
    // whitelist rejected 100% of SessionFile records, so file sync silently
    // never drained and peers never received workspace/attachment/browser
    // files. CloudKit itself accepts a far broader recordName set than the
    // old whitelist allowed.
    //
    // We don't enumerate an allow-list of characters or pin any filename
    // shape. Instead we reject only what is genuinely illegal — the same
    // bytes a POSIX pathname forbids (NUL) plus other C0/DEL control
    // characters — and the CloudKit structural limits (non-empty, ≤255,
    // no leading `_`, no trailing `:` for the degenerate empty-id row).
    // Everything else — letters, digits, `/`, `.`, `-`, `_`, spaces, and
    // any printable Unicode incl. Chinese — passes through.
    ///
    /// [T-ckrecordname-length-bytes] The 255 limit is measured in BYTES, so the
    /// check counts UTF-8 bytes rather than Swift `Characters`. `String.count`
    /// counts GRAPHEME CLUSTERS, which badly under-counts non-ASCII: a 204-char
    /// Chinese filename is 612 UTF-8 bytes, and a family emoji (👨‍👩‍👧‍👦, seven
    /// scalars joined by ZWJ) counts as ONE Character while occupying 25 bytes.
    /// This is reachable, not theoretical — `SessionFileV2` recordNames embed a
    /// user-controlled file path (`"<type>:<sessionId>:<dir>/<name>"`) and the
    /// guard deliberately admits CJK and emoji, so a long non-ASCII filename
    /// could pass a `count <= 255` test while being several times over the real
    /// limit.
    static func isValidCKRecordName(_ name: String) -> Bool {
        guard !name.isEmpty, name.utf8.count <= 255 else { return false }
        if name.hasPrefix("_") { return false }
        // Reject the degenerate "type:" with empty id — easy to construct
        // when a dirty row's id column landed as "" instead of NULL.
        if name.hasSuffix(":") { return false }
        // Control characters (C0 range + DEL) are the only illegal bytes in a
        // POSIX pathname besides the path separator, which we must keep.
        if name.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value == 0x7F }) {
            return false
        }
        return true
    }

    /// Render a possibly-dirty recordName safely for logs: caps length and
    /// escapes control / non-printable / non-ASCII bytes so we can grep
    /// the offender on next repro without spraying user data into logs.
    static func escapeForLog(_ s: String) -> String {
        let capped = s.count > 64 ? String(s.prefix(64)) + "…" : s
        var out = "\""
        for scalar in capped.unicodeScalars {
            let v = scalar.value
            if v >= 0x20 && v < 0x7F && v != 0x22 && v != 0x5C {
                out.append(Character(scalar))
            } else {
                out.append(String(format: "\\u%04X", v))
            }
        }
        out.append("\"")
        return out
    }

    /// IDs the server has rejected with .unknownItem (already deleted) so
    /// we don't re-queue them indefinitely.
    private var v1DeleteDropped: Set<String> = []

    /// Most recent CloudKit-issued throttle deadline. Read by SyncCore
    /// to gate sendNow.
    private(set) var retryAfter: Date?

    // MARK: - Init

    override init() {
        self.container = CKContainer(identifier: Self.containerIdentifier)
        let dir = FileManager.default
            .urls(for: .libraryDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MinisChat/cloud-sync-v2", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        self.stateURL = dir.appendingPathComponent("state-v2.bin")
        super.init()
        if let data = try? Data(contentsOf: stateURL) {
            self.stateSerialization = try? JSONDecoder().decode(CKSyncEngine.State.Serialization.self, from: data)
        }
    }

    /// Load persisted etag cache from disk so a fresh launch already
    /// knows what's on cloud. Avoids re-uploading every record after
    /// app restart — without this, every record save would round-trip
    /// "record already exists" → conflict → re-push, doubling traffic.
    ///
    /// [T-ios-etag-cache-background-load] Dispatches the decode (the heavy
    /// part: N × NSKeyedUnarchiver + CKRecord(coder:), measured at ~170ms for
    /// 4500 entries on iPhone 17 Pro) onto a utility-QoS background queue so
    /// SyncTransport.start() — and the SwiftUI/scroll runloop that may be
    /// active in the same instant — never block on it. Cost of starting with
    /// an empty cache during those few hundred ms is bounded: a record saved
    /// in that window may hit one `serverRecordChanged` conflict, which the
    /// existing recovery path handles cleanly (and we merge non-destructively
    /// below so an engine-written newer entry is never clobbered by the stale
    /// disk version).
    private func loadEtagCache() {
        Task.detached(priority: .utility) { [weak self, url = self.etagCacheURL] in
            let (loaded, poisoned, skippedCap, byType) = Self.decodeEtagCache(from: url)
            await MainActor.run {
                guard let self else { return }
                self.applyLoadedEtagCache(
                    loaded: loaded,
                    poisoned: poisoned,
                    skippedCap: skippedCap,
                    byType: byType
                )
            }
        }
    }

    /// Pure decode, runs on a background queue. Returns the decoded records +
    /// diagnostic counts; does NOT touch any instance state. The autoreleasepool
    /// keeps NSKeyedUnarchiver's ObjC intermediates from piling up.
    nonisolated private static func decodeEtagCache(from url: URL)
        -> (loaded: [CKRecord.ID: CKRecord], poisoned: Int, skippedCap: Int, byType: [String: Int])
    {
        guard let data = try? Data(contentsOf: url),
              let dict = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Data] else {
            return ([:], 0, 0, [:])
        }
        var loaded: [CKRecord.ID: CKRecord] = [:]
        var poisoned = 0
        var skippedCap = 0
        var byType: [String: Int] = [:]
        autoreleasepool {
            for (recordName, systemFieldsData) in dict {
                if loaded.count >= Self.serverRecordCacheCap {
                    skippedCap += 1
                    continue
                }
                guard let coder = try? NSKeyedUnarchiver(forReadingFrom: systemFieldsData) else { continue }
                coder.requiresSecureCoding = true
                if let record = CKRecord(coder: coder) {
                    coder.finishDecoding()
                    let keys = record.allKeys()
                    if keys.contains("_schemaVersion") || keys.contains("_minimumCompatibleVersion") {
                        poisoned += 1
                        continue
                    }
                    loaded[record.recordID] = record
                    let rtype = record.recordID.recordName.split(separator: ":").first.map(String.init) ?? "?"
                    byType[rtype, default: 0] += 1
                } else {
                    coder.finishDecoding()
                }
                _ = recordName
            }
        }
        return (loaded, poisoned, skippedCap, byType)
    }

    /// Merge the background-loaded etag cache into the live serverRecordCache
    /// on the main actor. Non-destructive: entries the engine has ALREADY
    /// written (a save/fetch between start() and decode completion) carry the
    /// freshest etag and must win — we only fill in slots that are still nil.
    @MainActor
    private func applyLoadedEtagCache(
        loaded: [CKRecord.ID: CKRecord],
        poisoned: Int,
        skippedCap: Int,
        byType: [String: Int]
    ) {
        var added = 0
        var skippedExisting = 0
        for (id, record) in loaded {
            if serverRecordCache[id] == nil {
                serverRecordCache[id] = record
                added += 1
            } else {
                skippedExisting += 1
            }
        }
        if added > 0 || poisoned > 0 || skippedCap > 0 {
            let breakdown = byType.sorted { $0.value > $1.value }
                .map { "\($0.key)=\($0.value)" }
                .joined(separator: " ")
            logger.info("[SyncTransport] etag cache: loaded \(added) entries (skipped \(poisoned) poisoned, \(skippedCap) over cap=\(Self.serverRecordCacheCap), skipped \(skippedExisting) already-fresh) byType=[\(breakdown)] (background)")
            if poisoned > 0 || skippedCap > 0 { etagCacheDirty = true } // re-flush trimmed
        }
    }

    /// Background queue used by flushEtagCacheIfDirty so the per-record
    /// NSKeyedArchive + plist serialization (can be 5–10MB at scale) does
    /// not block the main thread between sends.
    private static let etagFlushQueue = DispatchQueue(label: "com.openminis.sync.etagFlush", qos: .utility)
    private var etagFlushScheduled: Bool = false
    private static let etagFlushDebounce: TimeInterval = 2.0

    /// Flush dirty etag cache to disk. Coalesces multiple flush requests
    /// over a short window so back-to-back sends don't each rewrite the
    /// whole 7MB file. The actual archive + atomic write runs on a
    /// utility-QoS background queue.
    private func flushEtagCacheIfDirty() {
        guard etagCacheDirty else { return }
        guard !etagFlushScheduled else { return }
        etagFlushScheduled = true
        // Snapshot the records up-front on the main actor so background
        // encoding doesn't race against subsequent mutations.
        let snapshot = serverRecordCache
        let url = etagCacheURL
        etagCacheDirty = false
        Task.detached { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.etagFlushDebounce * 1_000_000_000))
            await MainActor.run { self?.etagFlushScheduled = false }
            Self.etagFlushQueue.async {
                var dict: [String: Data] = [:]
                for (rid, record) in snapshot {
                    let encoder = NSKeyedArchiver(requiringSecureCoding: true)
                    record.encodeSystemFields(with: encoder)
                    encoder.finishEncoding()
                    dict[rid.recordName] = encoder.encodedData
                }
                guard let plist = try? PropertyListSerialization.data(fromPropertyList: dict, format: .binary, options: 0) else { return }
                try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
                try? plist.write(to: url, options: .atomic)
                logger.info("[SyncTransport] etag cache: flushed \(dict.count) entries (\(plist.count) bytes) [bg]")
            }
        }
    }

    func start() async throws {
        guard syncEngine == nil else { return }
        logger.info("[SyncTransport] start STEP=enter")
        // Restore etag cache before any send; without this, the first
        // post-launch send rebuilds CKRecords without etags and the
        // server rejects them all with serverRecordChanged.
        loadEtagCache()
        // [T-ios-etag-cache-background-load] loadEtagCache dispatched the
        // decode to a utility queue and returned immediately; the merged
        // count lands later via applyLoadedEtagCache. We log the in-flight
        // dispatch here, and the (background)-tagged "loaded N entries"
        // line lands once the decode completes.
        logger.info("[SyncTransport] start STEP=etagLoadDispatched cacheCount=\(self.serverRecordCache.count) (background decode in flight)")
        let config: CKSyncEngine.Configuration
        if let s = stateSerialization {
            config = CKSyncEngine.Configuration(database: container.privateCloudDatabase, stateSerialization: s, delegate: self)
        } else {
            config = CKSyncEngine.Configuration(database: container.privateCloudDatabase, stateSerialization: nil, delegate: self)
        }
        logger.info("[SyncTransport] start STEP=configBuilt hasState=\(self.stateSerialization != nil)")
        let engine = CKSyncEngine(config)
        self.syncEngine = engine
        logger.info("[SyncTransport] start STEP=engineCreated")
        // Ensure the three zones exist. Idempotent on the engine side.
        engine.state.add(pendingDatabaseChanges: [
            .saveZone(CKRecordZone(zoneID: CKRecordZone.ID(zoneName: Self.sharedZoneName))),
            .saveZone(CKRecordZone(zoneID: CKRecordZone.ID(zoneName: Self.devicesZoneName))),
            .saveZone(CKRecordZone(zoneID: CKRecordZone.ID(zoneName: Self.secretsZoneName))),
        ])
        logger.info("[SyncTransport] start STEP=zonesQueued")
        // Direct zone create — blocks until server commits. Run BEFORE
        // any engine.sendChanges so subsequent record saves don't hit
        // code=10 zoneNotFound. CKSyncEngine's pendingDatabaseChanges
        // mechanism is async + opaque; modifyRecordZones gives a clean
        // "the zones exist now" signal.
        logger.info("[SyncTransport] start STEP=ensureZonesExist begin")
        await ensureZonesExist()
        logger.info("[SyncTransport] start STEP=ensureZonesExist done")
        // Now drive engine to flush pendingDatabaseChanges (the saveZone
        // entries we queued above). With zones already on cloud the
        // engine's own attempt is a no-op success, but we still need
        // this so engine.state knows the saves are done.
        logger.info("[SyncTransport] start STEP=initialSendChanges begin")
        do {
            try await engine.sendChanges()
            logger.info("[SyncTransport] start STEP=initialSendChanges done")
        } catch {
            let nse = error as NSError
            logger.warning("[SyncTransport] start STEP=initialSendChanges error code=\(nse.code) desc=\(error.localizedDescription)")
        }
        logger.info("[SyncTransport] start STEP=exit")
        // Kick the recent-window fast-path immediately, then schedule
        // a periodic re-run. Independent of CKSyncEngine's token-driven
        // fetch so newly-typed peer messages don't have to wait behind
        // a multi-day migration backlog.
        scheduleRecentFetchTimer()
        Task { await fetchRecentV2() }
    }

    private func scheduleRecentFetchTimer() {
        recentFetchTimer?.cancel()
        let t = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "com.openminis.sync.recentFetch", qos: .utility))
        t.schedule(deadline: .now() + Self.recentFetchInterval, repeating: Self.recentFetchInterval)
        t.setEventHandler { [weak self] in
            logger.info("[iCloudTrace] recentFetchTimer fired")
            Task { @MainActor [weak self] in
                await self?.fetchRecentV2()
            }
        }
        t.resume()
        recentFetchTimer = t
        logger.info("[iCloudTrace] recentFetchTimer scheduled interval=\(Int(Self.recentFetchInterval))s")
    }

    /// Periodic active fetch: ask CKSyncEngine to pull whatever changes
    /// the server has accumulated since our last token. This is faster
    /// than waiting for a silent-push notification (which Apple may
    /// delay or coalesce under throttle). Earlier attempt used a
    /// CKQueryOperation with `modificationDate > cutoff` predicate, but
    /// CloudKit rejected with code=12 'Field ___modTime is not marked
    /// queryable' — that field requires manual schema indexing in the
    /// CloudKit dashboard which we can't do programmatically.
    @MainActor
    private func fetchRecentV2() async {
        logger.info("[iCloudTrace] fetchRecentV2 entering")
        guard !recentFetchInFlight else {
            logger.info("[iCloudTrace] fetchRecentV2 skip — already in flight")
            // Safety net: if a previous run got stuck for any reason
            // (CK never returning, system suspension, actor deadlock),
            // force-release the flag after 90s so the next timer cycle
            // can try again. Without this, one stuck run permanently
            // disables the fast path.
            return
        }
        if let gate = retryAfter, gate > Date() {
            logger.info("[iCloudTrace] fetchRecentV2 skip — throttled until \(gate)")
            return
        }
        recentFetchInFlight = true
        defer { recentFetchInFlight = false }
        // Watchdog: forcibly clear the in-flight guard after 90s in
        // case of unrecoverable hang. Doesn't cancel the in-flight
        // CK call (Swift can't kill an awaiting URLSession data task),
        // but at least lets the next timer cycle start fresh.
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 90_000_000_000)
            await MainActor.run {
                guard let self = self else { return }
                if self.recentFetchInFlight {
                    self.recentFetchInFlight = false
                    logger.warning("[iCloudTrace] fetchRecentV2 watchdog cleared in-flight after 90s")
                }
            }
        }
        // CK rejects predicates on system __modTime / __createTime
        // metadata fields ('not marked queryable'); they can only be
        // indexed via CloudKit Dashboard schema edit. But our v2
        // schema defines explicit `createdAt` / `updatedAt` custom
        // fields per record type, and those are queryable + sortable
        // once the dashboard indexes are in place. Use them.
        //
        // Bandwidth optimisation: track per-type last-seen timestamp
        // so each call only pulls records newer than what we already
        // saw. First call after install / re-install pulls 24h; steady
        // state pulls only what's new since the last 30s tick. Saves
        // ~99% of the bytes vs. naive "always 24h" predicate.
        let now = Date()
        // Default (high-volume types: sessions / messages / files) cutoff: the
        // bandwidth-optimised 24h window anchored to the last successful run.
        let defaultCutoff: Date
        if let lastTs = UserDefaults.standard.object(forKey: Self.recentFetchLastKey) as? Double, lastTs > 0 {
            // 5min slack so a record whose createdAt is just barely
            // before our cursor still gets caught (clock skew, write
            // ordering on server side).
            defaultCutoff = max(Date(timeIntervalSince1970: lastTs - 300), now.addingTimeInterval(-Self.recentFetchWindow))
        } else {
            defaultCutoff = now.addingTimeInterval(-Self.recentFetchWindow)
        }
        // [T-icloud-fresh-restore-provider-groups] Per-type cutoff: a config
        // type that hasn't been anchored yet (fresh device / never successfully
        // applied) pulls its FULL history so months-old providers/groups are not
        // missed by the 24h floor. `cutoffFor` returns the right floor per type.
        func cutoffFor(_ type: String) -> Date {
            if Self.fullHistoryConfigTypes.contains(type) {
                if !UserDefaults.standard.bool(forKey: Self.configTypeAnchoredKey(type)) {
                    return .distantPast
                }
                // [T-provider-anchor-two-run-confirm] Anchored config types get
                // a 7-DAY overlap instead of the high-volume types' 24h/5min
                // window. These are low-volume (tens of instances/groups, ~1k
                // entries at most), so the wide window costs almost nothing —
                // and it absorbs clock skew, delayed pushes and CK indexing
                // lag that the tight window turned into permanently-missed
                // records.
                return min(defaultCutoff, now.addingTimeInterval(-7 * 24 * 3600))
            }
            return defaultCutoff
        }
        // (recordType, fieldName) — use createdAt where present, else
        // updatedAt for records that have no createdAt.
        //
        // Any record type added here MUST have the chosen sort key
        // (createdAt or updatedAt) marked queryable + sortable in the
        // CloudKit Dashboard schema, otherwise the CKQuery for that
        // type fails with "field not queryable" and is logged below.
        // EnvVarV2 (legacy whole-file) is deliberately excluded —
        // builder no longer emits it and migration cleans it up.
        // SyncDeviceV2 is excluded — device records have their own
        // bootstrap path; polling them every 30s would be wasteful.
        //
        // [T-icloud-fetchrecent-secrets-zone] Each type is queried against
        // ITS OWN zone via zoneByRecordType, not a single hardcoded shared
        // zone. Secrets-zone types (ProviderConfigV2, EnvVarItem, the three
        // Provider*V3 records, MCPServersV2) live in minis-secrets; the old
        // code hardcoded minis-shared for every query, so those types always
        // came back count=0 inbound — provider/model-entry/MCP changes from a
        // peer device never arrived (the CKSyncEngine token path did not cover
        // them either). ProviderModelEntryV3 / ProviderModelGroupV3 carry only
        // updatedAt (no createdAt), so sort all V3 types by updatedAt.
        let typesAndKeys: [(String, String)] = [
            ("SessionV2", "createdAt"),
            ("MessageV2", "createdAt"),
            ("CompactMarkerV2", "createdAt"),
            ("SessionFileV2", "updatedAt"),
            ("FolderV2", "updatedAt"),
            ("SkillV2", "updatedAt"),
            ("ProviderConfigV2", "updatedAt"),
            ("ProviderInstanceV3", "updatedAt"),
            ("ProviderModelEntryV3", "updatedAt"),
            ("ProviderModelGroupV3", "updatedAt"),
            ("ProviderThinkingRuleV3", "updatedAt"),
            ("MCPServersV2", "updatedAt"),
            ("MCPServerItem", "updatedAt"),
            ("EnvVarItem", "updatedAt"),
            ("SoulV2", "updatedAt"),
            ("MemoryGlobalV2", "updatedAt"),
            ("MemoryDailyV2", "updatedAt"),
        ]
        var totalFetched = 0
        var byType: [String: Int] = [:]
        var portables: [PortableRecord] = []
        // [T-ios-cloudkit-nsoperation-weak-clear-crash] Track whether any
        // per-type CKQuery threw this run, to drive exponential backoff below.
        var fetchHadError = false
        // [T-icloud-provider-anchor-per-type] Track WHICH types failed this run.
        // A full-history config type must anchor as soon as ITS OWN pull
        // succeeds — gating anchoring on the batch-wide `fetchHadError` meant a
        // failure in ANY type (e.g. a transient backoff on another record type)
        // prevented provider types from ever anchoring, so they re-pulled their
        // entire history every cold start (the full 700+-record fetchRecentV2 +
        // apply storm behind the scroll stalls). Anchor per-type instead.
        var typesWithError: Set<String> = []
        let registry = SyncableTypeRegistry.shared
        for (type, dateKey) in typesAndKeys {
            // Resolve the type's home zone; fall back to the shared zone for
            // any type not explicitly mapped (keeps behavior safe if a new
            // type is added to the poll list before the zone map).
            let zoneName = Self.zoneByRecordType[type] ?? Self.sharedZoneName
            let zoneID = CKRecordZone.ID(zoneName: zoneName)
            let cutoff = cutoffFor(type)
            logger.info("[iCloudTrace] fetchRecentV2 querying type=\(type) zone=\(zoneName) fullHistory=\(cutoff == .distantPast)")
            let predicate = NSPredicate(format: "\(dateKey) > %@", cutoff as NSDate)
            let query = CKQuery(recordType: type, predicate: predicate)
            query.sortDescriptors = [NSSortDescriptor(key: dateKey, ascending: false)]
            // [T-ios-fetchrecent-main-thread] Run the CKQuery network round-trip
            // + decode on a background task. This function is @MainActor (it
            // touches recentFetchInFlight / serverRecordCache / etc.), so a
            // naked `await records(matching:)` resumed on the main thread and
            // unpacked all matchResults there too — and we make 10 of these per
            // fetchRecentV2 cycle (every 120s post-sync-start). DecelDisplay
            // Link traced ~125ms scroll-deceleration drops directly to this
            // burst. Hopping to Task.detached for the await + decode keeps the
            // main thread idle through each CK round-trip; we only return back
            // to the actor with the cheap [(CKRecord.ID, CKRecord, Portable?)]
            // result list, then mutate state in a tight loop.
            let db = container.privateCloudDatabase
            // [T-icloud-fresh-restore-provider-groups] A full-history pull
            // (fresh device, cutoff=.distantPast) must follow queryCursor to
            // completion: a single page is capped at 200 records, but a power
            // user can have far more model entries than that (e.g. OpenRouter
            // ships 300+), and silently truncating to 200 would drop the rest —
            // including model-group members. Steady-state 24h windows almost
            // never exceed one page, so keep their single-page behavior.
            let paginate = (cutoff == .distantPast)
            let outcome: Result<[(CKRecord, PortableRecord?)], Error>
            outcome = await Task.detached(priority: .utility) { [self] () -> Result<[(CKRecord, PortableRecord?)], Error> in
                do {
                    var rows: [(CKRecord, PortableRecord?)] = []
                    var result = try await db.records(
                        matching: query,
                        inZoneWith: zoneID,
                        desiredKeys: nil,
                        resultsLimit: 200
                    )
                    while true {
                        for (_, recResult) in result.matchResults {
                            if case .success(let rec) = recResult {
                                // toPortable is nonisolated — safe to call off-actor.
                                // Reads only rec + registry, no instance mutable state.
                                let p = self.toPortable(rec, registry: registry)
                                rows.append((rec, p))
                            }
                        }
                        guard paginate, let cursor = result.queryCursor else { break }
                        result = try await db.records(continuingMatchFrom: cursor, desiredKeys: nil, resultsLimit: 200)
                    }
                    return .success(rows)
                } catch {
                    return .failure(error)
                }
            }.value
            switch outcome {
            case .success(let rows):
                for (rec, p) in rows {
                    totalFetched += 1
                    byType[type, default: 0] += 1
                    serverRecordCache[rec.recordID] = rec
                    etagCacheDirty = true
                    if let p { portables.append(p) }
                }
                // [T-icloud-fresh-restore-provider-groups] For the long-lived
                // config types, log the count at INFO with the full-history flag
                // so a field log shows whether providers/groups actually arrived
                // from CloudKit (vs being dropped downstream by a not-yet-open DB).
                if Self.fullHistoryConfigTypes.contains(type) {
                    logger.info("[iCloudTrace][v3sync] fetchRecentV2 config type=\(type) fetched=\(byType[type] ?? 0) fullHistory=\(paginate)")
                } else {
                    logger.info("[iCloudTrace] fetchRecentV2 type=\(type) done count=\(byType[type] ?? 0)")
                }
            case .failure(let error):
                let nse = error as NSError
                let ck = error as? CKError
                logger.warning("[iCloudTrace] fetchRecentV2 type=\(type) field=\(dateKey) failed: code=\(nse.code) ck=\(ck?.code.rawValue ?? -1) desc=\(error.localizedDescription)")
                fetchHadError = true
                typesWithError.insert(type)
                if let after = ck?.retryAfterSeconds {
                    let until = Date().addingTimeInterval(after)
                    if (self.retryAfter ?? .distantPast) < until {
                        self.retryAfter = until
                    }
                }
                continue
            }
        }
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: Self.recentFetchLastKey)
        // [T-icloud-fresh-restore-provider-groups] Anchor each full-history
        // config type so it stops re-pulling its whole history — but ONLY once
        // its consumer is actually ready to APPLY what we fetched. The V3
        // provider/group/entry mergers `guard let db = ProviderConfigStore.db`
        // and silently drop records while the SQLite store is still opening
        // (async Task.detached on a fresh device). If we anchored now, those
        // dropped records would never be re-pulled (the cursor advanced past
        // them). So gate the three provider V3 types + ProviderConfigV2 on the
        // provider DB being open; other config types anchor on a clean run.
        let providerDBReady = ProviderConfigStore.shared.db != nil
        let providerGatedTypes: Set<String> = [
            "ProviderInstanceV3", "ProviderModelEntryV3", "ProviderModelGroupV3", "ProviderConfigV2",
            "ProviderThinkingRuleV3",
        ]
        // [T-icloud-provider-anchor-per-type] Anchor each full-history type on
        // ITS OWN successful pull (no error for THAT type), not on a clean batch.
        // A transient failure in an unrelated type no longer blocks provider
        // types from anchoring, so they stop re-pulling their whole history every
        // cold start.
        for type in Self.fullHistoryConfigTypes
        where !UserDefaults.standard.bool(forKey: Self.configTypeAnchoredKey(type)) {
            if typesWithError.contains(type) {
                logger.info("[iCloudTrace] fetchRecentV2 NOT anchoring \(type) — this type's pull errored, will re-pull next run")
                continue
            }
            if providerGatedTypes.contains(type) && !providerDBReady {
                logger.info("[iCloudTrace] fetchRecentV2 NOT anchoring \(type) — provider DB not open yet, will re-pull full history next run")
                continue
            }
            // [T-provider-anchor-two-run-confirm] CK queries are eventually
            // consistent: one clean pull can still return a SUBSET, and
            // anchoring on it permanently hides the missed records behind the
            // incremental window. Require two consecutive clean full-history
            // pulls with the SAME count before trusting the snapshot. Costs
            // one extra full-history query per type on a fresh device (low
            // volume, and the second run's applies all hit the updatedAt
            // guard); the steady-state incremental path is unchanged.
            let fetchedThisRun = byType[type] ?? 0
            let pendingKey = Self.configTypeAnchorPendingCountKey(type)
            if let prev = UserDefaults.standard.object(forKey: pendingKey) as? Int,
               prev == fetchedThisRun {
                UserDefaults.standard.set(true, forKey: Self.configTypeAnchoredKey(type))
                UserDefaults.standard.removeObject(forKey: pendingKey)
                logger.info("[iCloudTrace] fetchRecentV2 anchored config type \(type) (confirmed: two full-history pulls both fetched=\(fetchedThisRun))")
            } else {
                UserDefaults.standard.set(fetchedThisRun, forKey: pendingKey)
                logger.info("[iCloudTrace] fetchRecentV2 NOT anchoring \(type) yet — first clean full-history pull fetched=\(fetchedThisRun), awaiting a confirming run")
            }
        }
        let breakdown = byType.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " ")
        logger.info("[iCloudTrace] fetchRecentV2 ok total=\(totalFetched) byType=[\(breakdown)] window=\(Int(Self.recentFetchWindow))s")
        if !portables.isEmpty, let h = observeHandler {
            // Slice into 50-record chunks (same as token-driven path).
            let chunk = 50
            var i = 0
            while i < portables.count {
                let end = min(i + chunk, portables.count)
                h(SyncInboundBatch(records: Array(portables[i..<end]), deletes: [], sourceDeviceId: nil))
                i = end
            }
        }
        // Also poke CKSyncEngine to keep its token catching up.
        if let engine = syncEngine {
            do {
                try await engine.fetchChanges()
                logger.info("[iCloudTrace] fetchRecentV2 token-fetch ok")
            } catch {
                let nse = error as NSError
                let ck = error as? CKError
                logger.warning("[iCloudTrace] fetchRecentV2 engine.fetchChanges failed: code=\(nse.code) ck=\(ck?.code.rawValue ?? -1) desc=\(error.localizedDescription)")
                fetchHadError = true
                if let after = ck?.retryAfterSeconds {
                    let until = Date().addingTimeInterval(after)
                    if (self.retryAfter ?? .distantPast) < until {
                        self.retryAfter = until
                    }
                }
            }
        }
        // [T-ios-cloudkit-nsoperation-weak-clear-crash] Exponential backoff on
        // any fetch error this run (per-type CKQuery or token-fetch). A flaky
        // CloudKit layer — the iOS 26.5 XPC/CKQuery dealloc crash window often
        // coincides with connection errors — gets progressively more breathing
        // room via `retryAfter` instead of being polled hard every cycle.
        // Server-signalled retryAfterSeconds above already sets a floor; this
        // ensures even errors WITHOUT a retryAfter hint back off. A fully
        // clean run resets the streak so steady-state latency is unaffected.
        if fetchHadError {
            consecutiveFetchFailures += 1
            let idx = min(consecutiveFetchFailures - 1, Self.fetchBackoffSchedule.count - 1)
            let backoff = Self.fetchBackoffSchedule[idx]
            let until = Date().addingTimeInterval(backoff)
            if (self.retryAfter ?? .distantPast) < until {
                self.retryAfter = until
            }
            logger.warning("[iCloudTrace] fetchRecentV2 backoff — failures=\(consecutiveFetchFailures) next attempt gated for \(Int(backoff))s")
        } else if consecutiveFetchFailures > 0 {
            logger.info("[iCloudTrace] fetchRecentV2 recovered — clearing failure streak (was \(consecutiveFetchFailures))")
            consecutiveFetchFailures = 0
        }
    }

    /// Per-session CKQuery returning portables WITHOUT applying them.
    /// Caller (ChatStore.forcePullSession) decides whether to commit
    /// the swap based on what cloud returned. Queries MessageV2,
    /// CompactMarkerV2, and SessionV2 with `sessionId == X` (and
    /// `id == X` for SessionV2 since the parent record uses `id`).
    /// Bypasses the 24h time window of fetchRecentV2.
    func fetchSessionPortables(sessionId: String) async throws -> [PortableRecord] {
        let zoneID = CKRecordZone.ID(zoneName: Self.sharedZoneName)
        let registry = SyncableTypeRegistry.shared
        var portables: [PortableRecord] = []
        var byType: [String: Int] = [:]
        // T-forcepull-id-field: SessionV2 has no user-defined `id` field —
        // the row id is encoded into the CKRecord's recordName, while the
        // queryable field is `sessionId` (see SyncedSession in
        // SyncedTypes.swift: fields[F.string("sessionId", \.id)]).
        // Querying `id == X` returns CloudKit "Unknown field 'id'" and
        // aborts the whole Force Pull. Match the same `sessionId` field
        // used by MessageV2/CompactMarkerV2.
        let typesAndPredicate: [(String, NSPredicate)] = [
            ("SessionV2", NSPredicate(format: "sessionId == %@", sessionId)),
            ("MessageV2", NSPredicate(format: "sessionId == %@", sessionId)),
            ("CompactMarkerV2", NSPredicate(format: "sessionId == %@", sessionId)),
        ]
        for (type, predicate) in typesAndPredicate {
            logger.info("[iCloudTrace] fetchSessionPortables querying type=\(type) sid=\(sessionId.prefix(8))")
            let query = CKQuery(recordType: type, predicate: predicate)
            do {
                let result = try await container.privateCloudDatabase.records(
                    matching: query,
                    inZoneWith: zoneID,
                    desiredKeys: nil,
                    resultsLimit: CKQueryOperation.maximumResults
                )
                for (_, recResult) in result.matchResults {
                    if case .success(let rec) = recResult {
                        byType[type, default: 0] += 1
                        serverRecordCache[rec.recordID] = rec
                        etagCacheDirty = true
                        if let portable = toPortable(rec, registry: registry) {
                            portables.append(portable)
                        }
                    }
                }
                logger.info("[iCloudTrace] fetchSessionPortables type=\(type) count=\(byType[type] ?? 0)")
            } catch {
                let nse = error as NSError
                let ck = error as? CKError
                logger.warning("[iCloudTrace] fetchSessionPortables type=\(type) failed: code=\(nse.code) ck=\(ck?.code.rawValue ?? -1) desc=\(error.localizedDescription)")
                throw error
            }
        }
        return portables
    }

    /// Direct CKDatabase zone-create that completes only when the
    /// server has actually committed the zone. Uses CKModifyRecordZonesOperation
    /// because CKSyncEngine doesn't expose a "wait for zone create" hook.
    private func ensureZonesExist() async {
        logger.info("[SyncTransport] ensureZonesExist: starting modifyRecordZones")
        let zones = [Self.sharedZoneName, Self.devicesZoneName, Self.secretsZoneName]
            .map { CKRecordZone(zoneID: CKRecordZone.ID(zoneName: $0)) }
        do {
            let result = try await container.privateCloudDatabase.modifyRecordZones(
                saving: zones,
                deleting: []
            )
            logger.info("[SyncTransport] ensureZonesExist: modifyRecordZones returned, parsing results")
            var saved: [String] = []
            var failed: [(String, Error)] = []
            for (id, r) in result.saveResults {
                switch r {
                case .success:           saved.append(id.zoneName)
                case .failure(let e):    failed.append((id.zoneName, e))
                }
            }
            logger.info("[SyncTransport] ensureZonesExist: saved=\(saved) failed=\(failed.map{ $0.0 })")
            for (zone, err) in failed {
                let nse = err as NSError
                logger.warning("[SyncTransport] ensureZonesExist zone=\(zone) error code=\(nse.code) desc=\(err.localizedDescription)")
            }
        } catch {
            let nse = error as NSError
            let ck = error as? CKError
            logger.warning("[SyncTransport] ensureZonesExist threw: code=\(nse.code) ckCode=\(ck?.code.rawValue ?? -1) desc=\(error.localizedDescription) userInfoKeys=\(Array(nse.userInfo.keys))")
        }
    }

    func stop() async {
        syncEngine = nil
        recentFetchTimer?.cancel()
        recentFetchTimer = nil
    }

    func observe(handler: @escaping (SyncInboundBatch) -> Void) {
        self.observeHandler = handler
    }

    // MARK: - send

    func send(_ batch: SyncOutboundBatch, trigger: SyncSendTrigger) async throws -> [SyncOutcome] {
        guard let engine = syncEngine else { throw SyncTransportError.notStarted }
        // H2 fix: refuse overlapping sends rather than clobbering the
        // pending continuation (the first awaiter would hang forever).
        // SyncCore's isSending guard makes this rare, but migration +
        // observe-driven schedule can interleave under suspension points.
        if sendCompletion != nil {
            throw SyncTransportError.sendInFlight
        }

        // Reset pendingRecords/Deletes from any prior failed-and-not-drained
        // send. The CKSyncEngine state.add below uses recordIDs (deduped by
        // the engine), but pendingRecords is the actual CKRecord array we
        // hand the delegate — leaving stale entries here causes "You can't
        // save the same record twice" permanent failures when the same
        // record is rebuilt on a subsequent send.
        pendingRecords.removeAll()
        pendingDeletes.removeAll()

        // Convert PortableRecord → CKRecord, accumulate into pendingRecords
        // for the delegate's nextRecordZoneChangeBatch call.
        var ckIds: [CKRecord.ID] = []
        var seenIDs: Set<String> = []
        for portable in batch.records {
            guard let zoneName = Self.zoneByRecordType[portable.id.type] else {
                logger.warning("[SyncTransport] no zone mapping for type=\(portable.id.type) id=\(portable.id.id.prefix(8))")
                continue
            }
            let zoneID = CKRecordZone.ID(zoneName: zoneName)
            let recordName = "\(portable.id.type):\(portable.id.id)"
            // Defensive: skip duplicate IDs within the same batch. CloudKit
            // rejects the entire batch with permanent "You can't save the
            // same record twice" if duplicates slip through.
            if !seenIDs.insert(recordName).inserted { continue }
            // [T-ios-icloud-ckrecordid-nilname-crash] Validate recordName
            // before handing to CloudKit. iOS 26.6 Beta's
            // `-[CKRecordID initWithRecordName:zoneID:]` raises an ObjC
            // exception (and crashes launch — caught only at libc++abi
            // terminate) on empty / whitespace / out-of-range names; the
            // 26.x stable releases were more permissive. Skip the bad
            // record and log the (escaped) name so a future log capture
            // can surface the dirty data without exposing user content.
            guard Self.isValidCKRecordName(recordName) else {
                logger.warning("[SyncTransport] skipping invalid recordName for save: \(Self.escapeForLog(recordName)) type=\(portable.id.type)")
                continue
            }
            // [T-ckrecordname-defense-in-depth] Belt AND braces, matching the
            // CloudSyncEngine sites. The guard above is the primary defence and
            // must stay primary: this enclosing `send` is `async`, and an
            // NSException that crosses an `await` boundary escapes the ObjC
            // @try frame entirely (see SyncV2Bootstrap's notes), so a try/catch
            // can never be the thing we rely on here. It is safe and useful at
            // THIS call site only because the wrapped work is purely
            // synchronous — no `await` occurs between @try and @catch — so the
            // frame is intact and it catches whatever the validator failed to
            // anticipate about CloudKit's rules.
            var recordIDOpt: CKRecord.ID?
            let recordIDOk = noff_try_objc {
                recordIDOpt = CKRecord.ID(recordName: recordName, zoneID: zoneID)
            }
            guard recordIDOk, let recordID = recordIDOpt else {
                logger.error("[SyncTransport] CKRecord.ID threw for save recordName \(Self.escapeForLog(recordName)) type=\(portable.id.type) — skipping")
                continue
            }
            // Reuse server record (preserves system fields + unknown fields)
            let ck = serverRecordCache[recordID] ?? CKRecord(recordType: portable.id.type, recordID: recordID)
            applyPortable(portable, to: ck)
            pendingRecords.append(ck)
            ckIds.append(recordID)
        }
        for d in batch.deletes {
            guard let zoneName = Self.zoneByRecordType[d.type] else {
                logger.warning("[SyncTransport] delete: no zone mapping for type=\(d.type) id=\(d.id.prefix(8)) — dropped")
                continue
            }
            let zoneID = CKRecordZone.ID(zoneName: zoneName)
            let recordName = "\(d.type):\(d.id)"
            // [T-ios-icloud-ckrecordid-nilname-crash] Same validation as the
            // save loop above — a dirty deletion row with an empty id must
            // not be allowed to reach `CKRecord.ID(recordName:zoneID:)`.
            guard Self.isValidCKRecordName(recordName) else {
                logger.warning("[SyncTransport] skipping invalid recordName for delete: \(Self.escapeForLog(recordName)) type=\(d.type)")
                continue
            }
            // [T-ckrecordname-defense-in-depth] Same shape as the save loop
            // above — synchronous body, so the @try frame survives.
            var recordIDOpt: CKRecord.ID?
            let recordIDOk = noff_try_objc {
                recordIDOpt = CKRecord.ID(recordName: recordName, zoneID: zoneID)
            }
            guard recordIDOk, let recordID = recordIDOpt else {
                logger.error("[SyncTransport] CKRecord.ID threw for delete recordName \(Self.escapeForLog(recordName)) type=\(d.type) — skipping")
                continue
            }
            pendingDeletes.append(recordID)
            logger.info("[iCloudTrace] queue delete \(d.type):\(d.id.prefix(8)) → zone=\(zoneName)")
        }
        engine.state.add(pendingRecordZoneChanges:
            ckIds.map { .saveRecord($0) } +
            pendingDeletes.map { .deleteRecord($0) }
        )

        let recordCount = pendingRecords.count
        let deleteCount = pendingDeletes.count
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<[SyncOutcome], Error>) in
            self.sendCompletion = cont
            Task.detached { [weak self] in
                do {
                    try await engine.sendChanges()
                    await self?.completeSend()
                } catch {
                    let nse = error as NSError
                    let ck = error as? CKError
                    logger.error("[SyncTransport] iCloud send error: pending=\(recordCount) deletes=\(deleteCount) domain=\(nse.domain) code=\(nse.code) ckCode=\(ck?.code.rawValue ?? -1) desc=\(error.localizedDescription)")
                    if let after = ck?.retryAfterSeconds {
                        let until = Date().addingTimeInterval(after)
                        await MainActor.run { [weak self] in
                            if let self, (self.retryAfter ?? .distantPast) < until {
                                self.retryAfter = until
                                logger.warning("[SyncTransport] CloudKit throttle: retry-after \(after)s (gate=\(until))")
                            }
                        }
                    }
                    // Enumerate CKPartialErrorsByItemID — that's where
                    // per-record failure detail lives for code=.partialFailure.
                    if let partials = nse.userInfo[CKPartialErrorsByItemIDKey] as? [AnyHashable: Error] {
                        var byCode: [Int: Int] = [:]
                        var firstSamples: [String] = []
                        for (recordId, perError) in partials {
                            let pe = perError as NSError
                            byCode[pe.code, default: 0] += 1
                            if firstSamples.count < 5 {
                                firstSamples.append("\(recordId) → code=\(pe.code) \(pe.localizedDescription.prefix(120))")
                            }
                        }
                        logger.error("[SyncTransport] CKPartialErrors total=\(partials.count) byCode=\(byCode) samples=\(firstSamples.joined(separator: " | "))")
                    } else {
                        logger.error("[SyncTransport] no CKPartialErrorsByItemID; userInfo keys=\(Array(nse.userInfo.keys))")
                    }
                    // On hard server-side throttle (requestRateLimited /
                    // serviceUnavailable / batchRequestFailed), drop the
                    // unsent record intents from engine.state so the next
                    // sendChanges does NOT retry the same stale batch.
                    // SyncCore will requery dirty rows next round and
                    // pick whatever's currently highest-priority — newly
                    // typed messages then jump ahead of the migration
                    // backlog instead of being stuck behind a permanently-
                    // throttled SessionV2=200 batch.
                    if let ckCode = ck?.code,
                       ckCode == .requestRateLimited
                        || ckCode == .serviceUnavailable
                        || ckCode == .zoneBusy
                        || ckCode == .networkUnavailable
                        || ckCode == .networkFailure {
                        await self?.dropUnsentPending(error: error)
                    }
                    // partialFailure / serviceUnavailable / other engine-
                    // wide errors still mean the per-record .sentRecordZoneChanges
                    // delegate may have already populated pendingOutcomes
                    // for the records that *did* finish (save / fail /
                    // conflict). Don't drop those — return them through
                    // the normal completion path so SyncCore clears
                    // dirty for successes and preserves dirty for
                    // failures.
                    await self?.completeSendDespiteError(error)
                }
            }
        }
    }

    /// Throw away the saveRecord intents the engine still has queued
    /// (i.e. the batch we just tried to send and CK rate-limited /
    /// rejected at the engine level). Without this CKSyncEngine keeps
    /// retrying the same stale batch every time the throttle window
    /// expires, blocking newly-marked dirty rows from ever getting a
    /// turn. SyncCore will requery sync_dirty_records next round and
    /// rebuild the batch by current priority order.
    @MainActor
    private func dropUnsentPending(error: Error) {
        guard let engine = syncEngine else { return }
        // Records that already have a pendingOutcome were processed
        // per-record by `sentRecordZoneChanges` — don't strip those
        // (their outcome will be applied normally). Drop the rest.
        let keep = Set(pendingOutcomes.keys)
        let stale = pendingRecords.filter { !keep.contains($0.recordID.recordName) }
        guard !stale.isEmpty else { return }
        let intents: [CKSyncEngine.PendingRecordZoneChange] = stale.map { .saveRecord($0.recordID) }
        engine.state.remove(pendingRecordZoneChanges: intents)
        // Also clear our own mirror so the delegate's nextRecordZoneChangeBatch
        // doesn't try to re-emit them.
        pendingRecords.removeAll { !keep.contains($0.recordID.recordName) }
        logger.warning("[SyncTransport] dropped \(stale.count) unsent pending intents after throttle so SyncCore can re-prioritize next batch")
    }

    /// Used when engine.sendChanges() throws but we still received some
    /// per-record outcomes through `.sentRecordZoneChanges`. Resume the
    /// caller with whatever outcomes we have rather than treating the
    /// whole batch as lost.
    private func completeSendDespiteError(_ error: Error) {
        let outcomes = Array(pendingOutcomes.values)
        pendingOutcomes.removeAll()
        flushEtagCacheIfDirty()
        if outcomes.isEmpty {
            // No partial outcomes — propagate the error so SyncCore can
            // schedule a retry without applying anything.
            sendCompletion?.resume(throwing: error)
        } else {
            logger.warning("[SyncTransport] iCloud send error preserved \(outcomes.count) partial outcomes")
            sendCompletion?.resume(returning: outcomes)
        }
        sendCompletion = nil
    }

    private func completeSend() {
        let outcomes = Array(pendingOutcomes.values)
        pendingOutcomes.removeAll()
        flushEtagCacheIfDirty()
        sendCompletion?.resume(returning: outcomes)
        sendCompletion = nil
    }

    private func completeSendWithError(_ error: Error) {
        sendCompletion?.resume(throwing: error)
        sendCompletion = nil
        pendingOutcomes.removeAll()
    }

    // MARK: - fetch

    func fetchChanges(trigger: SyncFetchTrigger) async throws -> SyncInboundBatch {
        guard let engine = syncEngine else { throw SyncTransportError.notStarted }
        if fetchCompletion != nil {
            throw SyncTransportError.fetchInFlight
        }
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<SyncInboundBatch, Error>) in
            self.fetchAccumulator = (records: [], deletes: [])
            self.fetchCompletion = cont
            Task.detached {
                do {
                    try await engine.fetchChanges()
                } catch {
                    await self.completeFetchWithError(error)
                    return
                }
                await self.completeFetch()
            }
        }
    }

    func fullFetch(trigger: SyncFetchTrigger) async throws -> SyncInboundBatch {
        // H5: resume any in-flight send/fetch awaiters with an error
        // before tearing down the engine, otherwise their continuations
        // are silently abandoned (caller hangs forever).
        if let cont = sendCompletion {
            cont.resume(throwing: SyncTransportError.notStarted)
            sendCompletion = nil
        }
        if let cont = fetchCompletion {
            cont.resume(throwing: SyncTransportError.notStarted)
            fetchCompletion = nil
        }
        pendingRecords.removeAll()
        pendingDeletes.removeAll()
        pendingOutcomes.removeAll()
        fetchAccumulator = nil
        // Reset state.bin so the next fetch re-fetches everything.
        stateSerialization = nil
        try? FileManager.default.removeItem(at: stateURL)
        syncEngine = nil
        try await start()
        return try await fetchChanges(trigger: trigger)
    }

    private func completeFetch() {
        let acc = fetchAccumulator ?? (records: [], deletes: [])
        let batch = SyncInboundBatch(records: acc.records, deletes: acc.deletes, sourceDeviceId: nil)
        fetchAccumulator = nil
        flushEtagCacheIfDirty()
        fetchCompletion?.resume(returning: batch)
        fetchCompletion = nil
    }

    private func completeFetchWithError(_ error: Error) {
        fetchAccumulator = nil
        fetchCompletion?.resume(throwing: error)
        fetchCompletion = nil
    }

    // MARK: - delete

    func delete(_ ids: [SyncRecordID]) async throws -> [SyncOutcome] {
        let batch = SyncOutboundBatch(records: [], deletes: ids)
        return try await send(batch, trigger: .manual)
    }

    // MARK: - PortableRecord → CKRecord

    /// Write metadata-known fields from a PortableRecord onto a CKRecord.
    /// Fields the local schema doesn't know about are LEFT UNTOUCHED on
    /// the CKRecord. Combined with the serverRecordCache reuse above,
    /// this is what makes write-back forward-compatible (§3.6.4).
    private func applyPortable(_ portable: PortableRecord, to record: CKRecord) {
        for (key, value) in portable.fields {
            switch value {
            case .null:
                record[key] = nil
            case .string(let s):
                if let n = portable.fields[key + "_inlineSize"], case .int(let size) = n, size > Self.assetInlineThreshold {
                    // Caller flagged this as oversize; route via asset.
                    if let url = portable.assets[key]?.fileURL {
                        record[key] = nil
                        record[key + "Asset"] = CKAsset(fileURL: url)
                    } else {
                        record[key] = s as CKRecordValue
                    }
                } else {
                    record[key] = s as CKRecordValue
                }
            case .int(let i):    record[key] = i as CKRecordValue
            case .double(let d): record[key] = d as CKRecordValue
            case .bool(let b):   record[key] = (b ? 1 : 0) as CKRecordValue
            case .date(let d):   record[key] = d as CKRecordValue
            case .data(let d):   record[key] = d as CKRecordValue
            case .json(let s):   record[key] = s as CKRecordValue
            }
        }
        // Asset fields registered in `assets` (e.g. SessionFile asset,
        // Skill bundleZip) — written even when no string field exists.
        for (key, asset) in portable.assets {
            // Skip keys we already routed inline above.
            if portable.fields[key] != nil { continue }
            record[key] = CKAsset(fileURL: asset.fileURL)
            record[key + "_size"] = asset.size as CKRecordValue
            if let mime = asset.mimeType { record[key + "_mime"] = mime as CKRecordValue }
        }
        // Schema metadata — readable by old / new clients alike.
        record["syncSchemaVersion"] = portable.schemaVersion as CKRecordValue
        if let mcv = portable.minimumCompatibleVersion {
            record["syncMinimumCompatibleVersion"] = mcv as CKRecordValue
        }
    }

    /// Reverse direction: CKRecord → PortableRecord. Fields the local
    /// schema declares are placed in `fields`; everything else lands in
    /// `unknownFields` so write-back preserves them.
    /// [T-ios-fetchrecent-main-thread] Marked `nonisolated` so fetchRecentV2's
    /// detached decode task can call it without hopping back to the MainActor.
    /// Reads only the CKRecord + registry (no instance state), so this is safe.
    nonisolated private func toPortable(_ record: CKRecord, registry: SyncableTypeRegistry) -> PortableRecord? {
        guard let metadata = registry.metadata(for: record.recordType) else { return nil }
        let knownKeys = metadata.knownCloudKeys
        var fields: [String: PortableFieldValue] = [:]
        var unknown: [String: PortableFieldValue] = [:]
        var assets: [String: PortableAsset] = [:]
        for key in record.allKeys() {
            // CloudKit reserves leading-underscore field names as system
            // fields and rejects writes; v2 schema-meta fields use a
            // visible prefix instead. Skip them so they don't end up in
            // fields/unknownFields and confuse the round-trip.
            if key == "syncSchemaVersion" || key == "syncMinimumCompatibleVersion" { continue }
            let value = record[key]
            if let asset = value as? CKAsset, let url = asset.fileURL {
                let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
                assets[key] = PortableAsset(key: key, fileURL: url, size: size, mimeType: nil)
                continue
            }
            let portable: PortableFieldValue
            switch value {
            case let s as String: portable = .string(s)
            case let i as Int:    portable = .int(i)
            case let d as Double: portable = .double(d)
            case let d as Date:   portable = .date(d)
            case let d as Data:   portable = .data(d)
            case .none:           portable = .null
            default:              portable = .string(String(describing: value as Any))
            }
            if knownKeys.contains(key) { fields[key] = portable }
            else                       { unknown[key] = portable }
        }
        let parts = record.recordID.recordName.split(separator: ":", maxSplits: 1)
        guard parts.count == 2 else { return nil }
        let rid = SyncRecordID(type: String(parts[0]), id: String(parts[1]))
        let updatedAt: Date = (record["updatedAt"] as? Date)
            ?? record.modificationDate
            ?? Date()
        let schemaVersion = (record["syncSchemaVersion"] as? Int) ?? 1
        let mcv = record["syncMinimumCompatibleVersion"] as? Int
        return PortableRecord(
            id: rid,
            fields: fields,
            assets: assets,
            schemaVersion: schemaVersion,
            minimumCompatibleVersion: mcv,
            unknownFields: unknown,
            updatedAt: updatedAt
        )
    }
}

// MARK: - CKSyncEngineDelegate

@available(iOS 17.0, *)
extension ICloudSharedZoneTransport: CKSyncEngineDelegate {
    nonisolated func handleEvent(_ event: CKSyncEngine.Event, syncEngine: CKSyncEngine) {
        switch event {
        case .stateUpdate(let stateUpdate):
            Task { @MainActor [weak self] in
                self?.persistState(stateUpdate.stateSerialization)
            }
        case .accountChange:
            // Treat as start-from-scratch on next start().
            Task { @MainActor [weak self] in
                self?.serverRecordCache.removeAll()
            }
        case .fetchedRecordZoneChanges(let zoneChanges):
            Task { @MainActor [weak self] in
                self?.handleFetchedZoneChanges(zoneChanges)
            }
        case .sentRecordZoneChanges(let sent):
            Task { @MainActor [weak self] in
                self?.handleSentRecordZoneChanges(sent)
            }
        case .fetchedDatabaseChanges, .didSendChanges, .didFetchChanges,
             .didFetchRecordZoneChanges, .willSendChanges, .willFetchChanges,
             .willFetchRecordZoneChanges, .sentDatabaseChanges:
            break
        @unknown default:
            break
        }
    }

    nonisolated func nextRecordZoneChangeBatch(
        _ context: CKSyncEngine.SendChangesContext,
        syncEngine: CKSyncEngine
    ) async -> CKSyncEngine.RecordZoneChangeBatch? {
        await MainActor.run { [weak self] in
            guard let self else { return nil as CKSyncEngine.RecordZoneChangeBatch? }
            let recs = self.pendingRecords; self.pendingRecords.removeAll()
            let dels = self.pendingDeletes; self.pendingDeletes.removeAll()
            guard !recs.isEmpty || !dels.isEmpty else { return nil }
            // [T-icloud-batch-byte-budget] Bound the batch by BYTES, not by
            // record count.
            //
            // History: this was 400, dropped to a flat 20 after resident memory
            // spiked past 2 GB on a large backlog. That fixed the spike but
            // mispriced the cost — peak memory here tracks payload size (each
            // batch materializes PortableRecord payloads and reads asset files),
            // not how many records are in it. A flat 20 therefore throttles the
            // common case, which is thousands of small text records, to 1/20th
            // of its former rate: OpenMinis#141's backlog became ~190 CKSyncEngine
            // round trips at ~94 records/hour, every one of them able to draw a
            // fresh retryAfter penalty and make the throttling worse.
            //
            // Budgeting by bytes keeps the 2 GB fix intact — a batch of large
            // assets still cuts off early — while letting a batch of ordinary
            // messages carry a useful number of records again. The record count
            // remains capped as a backstop for pathological cases (very many
            // near-empty records) and to keep the migration progress UI moving
            // in visible steps.
            let maxBatchRecords = 200
            let maxBatchBytes = 8 * 1024 * 1024

            let deleteSlice = Array(dels.prefix(maxBatchRecords))
            var recordSlice: [CKRecord] = []
            var budget = maxBatchBytes
            let recordRoom = maxBatchRecords - deleteSlice.count
            for rec in recs {
                if recordSlice.count >= recordRoom { break }
                // Always admit the first record even if it alone blows the
                // budget — otherwise a single oversized record would stall the
                // queue forever, never shipping and never making room.
                if !recordSlice.isEmpty && budget <= 0 { break }
                recordSlice.append(rec)
                budget -= Self.estimatedRecordBytes(rec)
            }
            // Spill overflow back for the next batch.
            let overflowRecs = Array(recs.dropFirst(recordSlice.count))
            let overflowDels = Array(dels.dropFirst(deleteSlice.count))
            self.pendingRecords.append(contentsOf: overflowRecs)
            self.pendingDeletes.append(contentsOf: overflowDels)
            // [T-icloud-batch-byte-budget] Makes the batching decision visible:
            // a backlog draining slowly should show large record counts here,
            // and a run cut short by bytes should show a small count with the
            // budget consumed. Without this the only observable was the record
            // count, which is exactly what mispriced the original 400→20 change.
            logger.info("[iCloudTrace] batch: records=\(recordSlice.count) deletes=\(deleteSlice.count) bytesUsed=\(maxBatchBytes - budget) overflow=\(overflowRecs.count + overflowDels.count)")
            return CKSyncEngine.RecordZoneChangeBatch(
                recordsToSave: recordSlice,
                recordIDsToDelete: deleteSlice,
                atomicByZone: false
            )
        }
    }

    /// [T-icloud-batch-byte-budget] Rough in-memory cost of one outbound record,
    /// used only to decide where to cut a batch.
    ///
    /// Assets dominate and are the reason the batch needs a byte budget at all,
    /// so they are counted from the `_size` field the writer already records
    /// (see applyPortable) — the file is not opened here. Everything else is a
    /// small flat estimate: precision doesn't matter for a cut-off decision, and
    /// walking every value of every record on the main actor would cost more
    /// than it saves.
    private static func estimatedRecordBytes(_ record: CKRecord) -> Int {
        var total = 4096   // record overhead + typical small text fields
        for key in record.allKeys() {
            if record[key] is CKAsset {
                // The writer stores the byte count alongside as "<key>_size".
                if let size = record[key + "_size"] as? Int {
                    total += size
                } else {
                    // Unknown asset size: assume it is large enough to matter,
                    // so an un-sized asset can't silently blow the budget.
                    total += assetInlineThreshold
                }
            } else if let s = record[key] as? String {
                total += s.utf8.count
            } else if let d = record[key] as? Data {
                total += d.count
            }
        }
        return total
    }

    @MainActor
    private func handleFetchedZoneChanges(_ zoneChanges: CKSyncEngine.Event.FetchedRecordZoneChanges) {
        logger.info("[iCloudTrace] handleFetchedZoneChanges called: mods=\(zoneChanges.modifications.count) dels=\(zoneChanges.deletions.count)")
        let registry = SyncableTypeRegistry.shared
        let v2Zones: Set<String> = [Self.sharedZoneName, Self.devicesZoneName, Self.secretsZoneName]
        // [OwnEchoSkip transport-level] Prune expired echo-suppress entries
        // once per fetch so the map stays bounded over a long-running session.
        let _now = Date()
        let _ttl = recentlyPushedTTL
        recentlyPushedRecordNames = recentlyPushedRecordNames.filter {
            _now.timeIntervalSince($0.value) < _ttl
        }
        var records: [PortableRecord] = []
        var deletes: [SyncRecordID] = []
        var skippedV1 = 0
        var skippedOwnEcho = 0
        // Filter + parse inside an autoreleasepool so the temporary
        // CKRecord/CKAsset objects from a 600-record fetch are released
        // promptly instead of piling up until the function returns.
        // Without this we observed ~3GB resident on a single big fetch
        // and OOM crash.
        autoreleasepool {
            for modification in zoneChanges.modifications {
                let rec = modification.record
                // Skip v1 device-* zones — CKSyncEngine fetches across the
                // entire private database; minis-* zones belong to v2 only.
                guard v2Zones.contains(rec.recordID.zoneID.zoneName) else {
                    skippedV1 += 1
                    continue
                }
                // [OwnEchoSkip transport-level] If we just saved this record
                // ourselves within `recentlyPushedTTL`, the engine is
                // re-delivering our own write through the changeToken stream.
                // Drop it from inbound so it never reaches SyncCore and
                // therefore never fires cloudSyncDidFetchChanges. The
                // SyncCore-side filter still catches stragglers from
                // SyncCore.sendOnce-driven pushes.
                if let pushedAt = recentlyPushedRecordNames[rec.recordID.recordName],
                   _now.timeIntervalSince(pushedAt) < _ttl {
                    skippedOwnEcho += 1
                    continue
                }
                // Cache server etag for next write.
                serverRecordCache[rec.recordID] = rec
                etagCacheDirty = true
                if let portable = toPortable(rec, registry: registry) {
                    records.append(portable)
                }
            }
        }
        if skippedV1 > 0 {
            logger.info("[iCloudTrace] skipped \(skippedV1) v1-zone records (handled by legacy CloudSyncEngine)")
        }
        if skippedOwnEcho > 0 {
            logger.info("[iCloudTrace] skipped \(skippedOwnEcho) own-echo records (re-delivery of our recent saves within \(Int(recentlyPushedTTL))s)")
        }
        if !records.isEmpty {
            var byType: [String: Int] = [:]
            for r in records { byType[r.id.type, default: 0] += 1 }
            let breakdown = byType.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " ")
            logger.info("[iCloudTrace] v2 inbound parsed=\(records.count) byType=[\(breakdown)]")
        }
        // Trace: list the first few inbound IDs + arrival latency vs.
        // their server modification time so we can correlate against
        // the sender's [iCloudTrace] outgoing batch head log.
        if !zoneChanges.modifications.isEmpty {
            let head = zoneChanges.modifications.prefix(5).map { mod -> String in
                let rec = mod.record
                let ageMs: String
                if let mod = rec.modificationDate {
                    ageMs = "\(Int(Date().timeIntervalSince(mod) * 1000))ms"
                } else {
                    ageMs = "?"
                }
                return "\(rec.recordType):\(rec.recordID.recordName.split(separator: ":").last?.prefix(8) ?? "") arrival=\(ageMs)"
            }.joined(separator: " | ")
            logger.info("[iCloudTrace] inbound fetched count=\(zoneChanges.modifications.count) head: \(head)")
        }
        for deletion in zoneChanges.deletions {
            let parts = deletion.recordID.recordName.split(separator: ":", maxSplits: 1)
            guard parts.count == 2 else { continue }
            let sid = SyncRecordID(type: String(parts[0]), id: String(parts[1]))
            deletes.append(sid)
            if sid.type == "Message" {
                logger.warning("[EditSync] [iCloudTrace] inbound delete tombstone Message id=\(sid.id.prefix(8)) — will hand off to deletionApplier")
            }
        }
        // Append into fetch accumulator (if a fetchChanges() is in flight)
        // and broadcast to the live observer.
        if let acc = fetchAccumulator {
            fetchAccumulator = (records: acc.records + records, deletes: acc.deletes + deletes)
        }
        if (!records.isEmpty || !deletes.isEmpty), let h = observeHandler {
            // Slice into 50-record sub-batches before fan-out so the
            // downstream hydrator (SyncCore.processInbound) doesn't
            // hold a 600-portable array in main-actor scope all at once.
            let chunk = 50
            var i = 0
            while i < records.count {
                let end = min(i + chunk, records.count)
                let slice = Array(records[i..<end])
                h(SyncInboundBatch(records: slice, deletes: [], sourceDeviceId: nil))
                i = end
            }
            if !deletes.isEmpty {
                h(SyncInboundBatch(records: [], deletes: deletes, sourceDeviceId: nil))
            }
        }
    }

    @MainActor
    private func handleSentRecordZoneChanges(_ sent: CKSyncEngine.Event.SentRecordZoneChanges) {
        if !sent.savedRecords.isEmpty {
            // Diagnostic split: how many of this batch's recordIDs we've
            // never seen saved before (this process lifetime) vs how
            // many are repeats of records we already pushed at least
            // once. Repeats are 100% expected — every CKSyncEngine batch
            // includes records the engine retried, plus everything we
            // re-marked dirty after relaunch. They should NOT inflate
            // the migration UI %; that's why pushedCumulative is now
            // backed by SQLite `sync_pushed_records` (set semantics)
            // rather than the old UserDefaults event counter.
            var firstSeen = 0
            var repeats = 0
            for r in sent.savedRecords {
                if Self.observedSavedRecordIDs.insert(r.recordID.recordName).inserted {
                    firstSeen += 1
                } else {
                    repeats += 1
                }
            }
            // Persistent unique-record counter. INSERT OR IGNORE keeps
            // the same recordName from being counted twice across
            // launches.
            let names = sent.savedRecords.map { $0.recordID.recordName }
            Task { await ChatStore.shared.markRecordsPushed(names) }
            // Legacy UserDefaults counter — keep updating it for
            // backwards compatibility with any old code path that still
            // reads it; new UI code reads from sync_pushed_records.
            let prev = UserDefaults.standard.integer(forKey: "cloudSync.v2.pushedCumulative")
            UserDefaults.standard.set(prev + sent.savedRecords.count, forKey: "cloudSync.v2.pushedCumulative")
            let head = sent.savedRecords.prefix(5).map {
                "\($0.recordType):\($0.recordID.recordName.split(separator: ":").last?.prefix(8) ?? "")"
            }.joined(separator: " | ")
            logger.info("[iCloudTrace] server-saved count=\(sent.savedRecords.count) firstSeen=\(firstSeen) repeats=\(repeats) cumulativeEvents=\(prev + sent.savedRecords.count) uniqueObserved=\(Self.observedSavedRecordIDs.count) head: \(head)")
        }
        let _nowStamp = Date()
        for saved in sent.savedRecords {
            serverRecordCache[saved.recordID] = saved
            etagCacheDirty = true
            // [T-icloud-notfound-retry-loop] A successful save (including a
            // successful post-NOT_FOUND recreate) clears the once-guard so a
            // FUTURE, legitimate NOT_FOUND on this same record (e.g. a second
            // peer-delete in a long-lived session) is recoverable again. The
            // guard only persists while the record is stuck failing — a record
            // that saves is healthy and earns a fresh recovery budget. Also
            // bounds the set's growth: healthy records don't accumulate.
            unknownItemRecreateAttempted.remove(saved.recordID.recordName)
            let id = parseRecordName(saved.recordID.recordName)
            pendingOutcomes[saved.recordID.recordName] = .success(id)
            // [OwnEchoSkip transport-level] Stamp now — even though SyncCore
            // also stamps via applyOutcomes, that path only fires after
            // completeSend(); the engine's internal re-deliver of the same
            // record via fetchChanges can race us, so stamping at the exact
            // moment CK acknowledges the save plugs the window.
            recentlyPushedRecordNames[saved.recordID.recordName] = _nowStamp
            // Schedule the corresponding v1 record for deletion. Reclaiming
            // v1 space incrementally (every v1DeleteBatchSize saves) avoids
            // the user's iCloud quota holding both v1 + v2 copies for the
            // entire migration window.
            if let v1id = Self.v1RecordID(forV2Saved: saved.recordID) {
                // Bound the resident queue. Once it's already backed up to the
                // cap (delete pipeline throttled, failing, or migration
                // out-running deletes), stop accumulating IDs — the leftover v1
                // records are reclaimed wholesale by deleteOwnZone later.
                if pendingV1Deletions.count < Self.pendingV1DeletionsCap {
                    pendingV1Deletions.append(v1id)
                } else {
                    v1DeletionsDroppedOverCap += 1
                }
            }
        }
        if v1DeletionsDroppedOverCap > 0, v1DeletionsDroppedOverCap % 500 == 1 {
            logger.warning("[SyncTransport] v1-delete queue at cap (\(Self.pendingV1DeletionsCap)); dropped \(self.v1DeletionsDroppedOverCap) overflow ID(s) — will be reclaimed by deleteOwnZone")
        }
        if pendingV1Deletions.count >= Self.v1DeleteBatchSize, !v1DeleteInFlight {
            triggerV1Deletion()
        }
        Self.republishV1DeletePending(pendingV1Deletions.count)
        for failed in sent.failedRecordSaves {
            let id = parseRecordName(failed.record.recordID.recordName)
            if failed.error.code == .serverRecordChanged, let server = failed.error.serverRecord,
               let portable = toPortable(server, registry: SyncableTypeRegistry.shared) {
                serverRecordCache[server.recordID] = server
                etagCacheDirty = true
                pendingOutcomes[failed.record.recordID.recordName] = .conflict(id, serverRecord: portable)
                // Re-queue with the fresh etag. CKSyncEngine consults
                // both engine.state AND nextRecordZoneChangeBatch — only
                // adding to engine.state without re-appending the record
                // body silently drops the retry. So rebuild a fresh
                // CKRecord on top of the server's system fields, copy
                // failed.record's known fields onto it, and re-append
                // to pendingRecords so the next batch ships the merge.
                let mergedRecord = server   // server has correct etag + system fields
                for key in failed.record.allKeys() {
                    mergedRecord[key] = failed.record[key]
                }
                pendingRecords.append(mergedRecord)
                syncEngine?.state.add(pendingRecordZoneChanges: [.saveRecord(failed.record.recordID)])
            } else if Self.isTransientCKError(failed.error) {
                pendingOutcomes[failed.record.recordID.recordName] = .transientFailure(id, retryAfter: failed.error.retryAfterSeconds)
                // Same C1 fix: re-append the record body so the engine
                // actually has something to send next pass. The record
                // already has the correct etag (it just transiently
                // failed, server didn't change anything).
                pendingRecords.append(failed.record)
                syncEngine?.state.add(pendingRecordZoneChanges: [.saveRecord(failed.record.recordID)])
            } else if failed.error.code == .unknownItem,
                      unknownItemRecreateAttempted.insert(failed.record.recordID.recordName).inserted {
                // NOT_FOUND (Code 11 UnknownItem / "recordChangeTag specified,
                // but record not found"): our cached etag points at a server
                // record that no longer exists — typically peer-deleted or the
                // device zone was wiped. The old behavior permanent-failed and
                // moved on, BUT the stale etag stayed in serverRecordCache, so
                // every subsequent send rebuilt the same update-with-stale-etag
                // request and CloudKit kept returning NOT_FOUND — a forever
                // retry loop (observed: one device's ProviderConfig + SyncDevice
                // looping every few seconds for ~47 min).
                //
                // Fix [T-icloud-notfound-retry-loop]: drop the stale cache entry
                // and re-queue the record as a FRESH create — a brand-new
                // CKRecord carrying NO recordChangeTag — so CloudKit accepts it
                // as a new record instead of a doomed update. Guarded by
                // unknownItemRecreateAttempted so each record only triggers this
                // once; if the create itself fails it falls through to a real
                // permanent failure next time rather than re-looping.
                let recordID = failed.record.recordID
                let rtype = failed.record.recordType
                let cachedTag = (serverRecordCache[recordID]?.recordChangeTag).map { String($0.prefix(12)) } ?? "nil"
                logger.error("[iCloudTrace] NOT_FOUND code=11 type=\(rtype) id=\(recordID.recordName.prefix(40)) cachedTag=\(cachedTag) — clearing stale etag and re-queuing as fresh create.")
                serverRecordCache.removeValue(forKey: recordID)
                etagCacheDirty = true
                // Rebuild an etag-less CKRecord and copy the failed record's
                // fields onto it (failed.record may itself carry the stale etag
                // via system fields, so we must start from a clean instance).
                let freshRecord = CKRecord(recordType: rtype, recordID: recordID)
                for key in failed.record.allKeys() {
                    freshRecord[key] = failed.record[key]
                }
                pendingRecords.append(freshRecord)
                syncEngine?.state.add(pendingRecordZoneChanges: [.saveRecord(recordID)])
                pendingOutcomes[recordID.recordName] = .transientFailure(id, retryAfter: nil)
            } else {
                if failed.error.code == .unknownItem {
                    // Recreate already attempted once for this record — stop
                    // looping; report a real permanent failure.
                    logger.error("[iCloudTrace] NOT_FOUND code=11 type=\(failed.record.recordType) id=\(failed.record.recordID.recordName.prefix(40)) — recreate already attempted, permanent-failing.")
                } else {
                    logger.error("[iCloudTrace] permanent push failure type=\(failed.record.recordType) id=\(failed.record.recordID.recordName.prefix(40)) code=\(failed.error.code.rawValue) desc=\(failed.error.localizedDescription)")
                }
                pendingOutcomes[failed.record.recordID.recordName] = .permanentFailure(id, reason: failed.error.localizedDescription)
            }
        }
        for deletedID in sent.deletedRecordIDs {
            let id = parseRecordName(deletedID.recordName)
            pendingOutcomes[deletedID.recordName] = .success(id)
            serverRecordCache.removeValue(forKey: deletedID)
        }
        if !sent.deletedRecordIDs.isEmpty {
            let head = sent.deletedRecordIDs.prefix(5)
                .map { "\($0.recordName.prefix(40))@\($0.zoneID.zoneName)" }
                .joined(separator: " | ")
            logger.info("[iCloudTrace] server-deleted count=\(sent.deletedRecordIDs.count) head: \(head)")
        }
    }

    private func parseRecordName(_ name: String) -> SyncRecordID {
        let parts = name.split(separator: ":", maxSplits: 1)
        if parts.count == 2 {
            return SyncRecordID(type: String(parts[0]), id: String(parts[1]))
        }
        return SyncRecordID(type: "Unknown", id: name)
    }

    @MainActor
    private func persistState(_ state: CKSyncEngine.State.Serialization) {
        self.stateSerialization = state
        if let data = try? JSONEncoder().encode(state) {
            try? data.write(to: stateURL, options: .atomic)
        }
    }

    // MARK: - Incremental v1 deletion

    /// Map a freshly-saved V2 CKRecord.ID to the corresponding V1 record
    /// in this device's v1 zone. Returns nil for record types that have
    /// no v1 counterpart (e.g. SyncDeviceV2 — v1 had no devices table).
    private static func v1RecordID(forV2Saved v2: CKRecord.ID) -> CKRecord.ID? {
        // recordName format is "TypeV2:id"; strip the V2 suffix.
        let parts = v2.recordName.split(separator: ":", maxSplits: 1)
        guard parts.count == 2 else { return nil }
        let v2Type = String(parts[0])
        guard v2Type.hasSuffix("V2") else { return nil }
        let v1Type = String(v2Type.dropLast(2))
        // Type-name allowlist — only types that actually existed in v1.
        let v1Types: Set<String> = ["Session", "Message", "CompactMarker", "SessionFile", "ProviderConfig", "EnvVar"]
        guard v1Types.contains(v1Type) else { return nil }
        let v1Name = "\(v1Type):\(parts[1])"
        // [T-ckrecordname-defense-in-depth] Validate here too, for consistency
        // with every other construction site in this file.
        //
        // Today this cannot fail: the input is a recordID CloudKit itself
        // accepted and echoed back, and the derivation only DROPS the "V2"
        // suffix while copying the id verbatim, so the result is always shorter
        // than an already-legal name. The one shape that would produce an
        // illegal name is a `"<Type>V2:"` with an empty id (deriving a trailing
        // `:`), and the save path above rejects those before they can reach the
        // server. The guard exists so that reasoning does not have to be
        // re-derived by the next person to touch this — and so a future change
        // to either side cannot silently reintroduce the crash that 8af19b48
        // fixed.
        guard isValidCKRecordName(v1Name) else {
            logger.warning("[SyncTransport] v1RecordID: derived invalid recordName \(escapeForLog(v1Name)) from \(escapeForLog(v2.recordName)) — skipping v1 delete")
            return nil
        }
        let zoneID = CKRecordZone.ID(zoneName: DeviceIdentity.zoneName)
        var out: CKRecord.ID?
        let ok = noff_try_objc {
            out = CKRecord.ID(recordName: v1Name, zoneID: zoneID)
        }
        guard ok else {
            logger.error("[SyncTransport] v1RecordID: CKRecord.ID threw for \(escapeForLog(v1Name)) — skipping v1 delete")
            return nil
        }
        return out
    }

    @MainActor
    private func triggerV1Deletion() {
        guard !v1DeleteInFlight else { return }
        guard !pendingV1Deletions.isEmpty else { return }
        guard v1DeleteConsecutiveFailures < Self.v1DeleteFailureCap else { return }
        // Respect server throttle gate: don't issue more delete ops while
        // CloudKit is asking us to back off.
        if let gate = retryAfter, gate > Date() {
            let wait = gate.timeIntervalSinceNow
            logger.info("[SyncTransport] v1-delete deferred — throttled \(String(format: "%.1f", wait))s")
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000) + 500_000_000)
                self?.triggerV1Deletion()
            }
            return
        }
        let batch = Array(pendingV1Deletions.prefix(Self.v1DeleteBatchSize))
        pendingV1Deletions.removeFirst(batch.count)
        v1DeleteInFlight = true
        Self.republishV1DeletePending(pendingV1Deletions.count)
        let db = CKContainer.default().privateCloudDatabase
        let op = CKModifyRecordsOperation(recordsToSave: nil, recordIDsToDelete: batch)
        op.savePolicy = .changedKeys
        // Per-record completion lets us classify .unknownItem (already
        // deleted server-side) as success and drop them from the queue.
        var successCount = 0
        var transientReQueue: [CKRecord.ID] = []
        op.perRecordDeleteBlock = { [weak self] recordID, result in
            Task { @MainActor in
                guard let self else { return }
                switch result {
                case .success:
                    successCount += 1
                case .failure(let err):
                    let ck = err as? CKError
                    if ck?.code == .unknownItem {
                        // Already gone server-side — accept as a successful
                        // reclamation so we don't get stuck retrying.
                        successCount += 1
                        self.v1DeleteDropped.insert(recordID.recordName)
                    } else {
                        transientReQueue.append(recordID)
                    }
                }
            }
        }
        op.modifyRecordsResultBlock = { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                self.v1DeleteInFlight = false
                if successCount > 0 {
                    let prev = UserDefaults.standard.integer(forKey: Self.v1DeletedKey)
                    UserDefaults.standard.set(prev + successCount, forKey: Self.v1DeletedKey)
                    logger.info("[SyncTransport] v1-incremental-delete: removed \(successCount) v1 records (cumulative \(prev + successCount))")
                }
                switch result {
                case .success:
                    self.v1DeleteConsecutiveFailures = 0
                case .failure(let err):
                    let nse = err as NSError
                    let ck = err as? CKError
                    self.v1DeleteConsecutiveFailures += 1
                    if let after = ck?.retryAfterSeconds {
                        let until = Date().addingTimeInterval(after)
                        if (self.retryAfter ?? .distantPast) < until {
                            self.retryAfter = until
                            logger.warning("[SyncTransport] v1-delete CloudKit throttle: retry-after \(after)s (gate=\(until))")
                        }
                    }
                    if !transientReQueue.isEmpty {
                        self.pendingV1Deletions.append(contentsOf: transientReQueue)
                        Self.republishV1DeletePending(self.pendingV1Deletions.count)
                    }
                    if self.v1DeleteConsecutiveFailures >= Self.v1DeleteFailureCap {
                        // The pipeline is halted until next launch. Don't retain
                        // the (potentially huge) queue across the whole session
                        // for nothing — free it. The un-deleted v1 records are
                        // still reclaimed by deleteOwnZone at end-of-migration.
                        let dropped = self.pendingV1Deletions.count
                        self.pendingV1Deletions.removeAll()
                        Self.republishV1DeletePending(0)
                        logger.warning("[SyncTransport] v1-incremental-delete halted after \(self.v1DeleteConsecutiveFailures) consecutive failures (last code=\(nse.code) ck=\(ck?.code.rawValue ?? -1) desc=\(err.localizedDescription)) — freed \(dropped) queued ID(s); v1 zone reclaimed by deleteOwnZone, will retry on next app launch")
                    } else {
                        logger.warning("[SyncTransport] v1-incremental-delete partial failure (\(transientReQueue.count) requeued, \(successCount) succeeded): code=\(nse.code) ck=\(ck?.code.rawValue ?? -1)")
                    }
                }
                // Drain remaining queue if still over threshold AND we haven't
                // tripped the failure cap.
                if self.pendingV1Deletions.count >= Self.v1DeleteBatchSize,
                   self.v1DeleteConsecutiveFailures < Self.v1DeleteFailureCap {
                    self.triggerV1Deletion()
                }
            }
        }
        db.add(op)
    }

    /// Publish pending count to UserDefaults so the migration progress UI
    /// can read it without a callback channel.
    private static func republishV1DeletePending(_ count: Int) {
        UserDefaults.standard.set(count, forKey: v1DeletePendingKey)
    }

    nonisolated private static func isTransientCKError(_ error: CKError) -> Bool {
        switch error.code {
        case .networkUnavailable, .networkFailure, .serviceUnavailable,
             .requestRateLimited, .zoneBusy, .resultsTruncated,
             .changeTokenExpired, .partialFailure, .internalError,
             .zoneNotFound, .userDeletedZone:
            // .zoneNotFound: zone create not yet committed when record
            // saves race ahead — transient, retry next pass.
            // .userDeletedZone: peer deleted zone; we re-create on next
            // start() and retry.
            return true
        default:
            return false
        }
    }
}

enum SyncTransportError: Error, LocalizedError {
    case notStarted
    case sendInFlight
    case fetchInFlight

    var errorDescription: String? {
        switch self {
        case .notStarted:    return "Transport not started"
        case .sendInFlight:  return "Another send is already in flight"
        case .fetchInFlight: return "Another fetch is already in flight"
        }
    }
}
