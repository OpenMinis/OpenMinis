import Foundation
import Security
import UIKit
import os.log

private let logger = AppLogger(category: "ProviderConfigStore")

// MARK: - Persisted Config

/// One soft-delete record. Persisted in `ProviderConfig` so the deletion
/// can travel through iCloud sync as positive data, and so `mergeProviderConfig`'s
/// set-union semantics can be told "this id is intentionally gone, don't
/// resurrect it from the other side's snapshot".
struct ProviderConfigTombstone: Codable, Hashable {
    let id: String
    let deletedAt: Date
}

/// Top-level JSON structure for provider-config.json.
struct ProviderConfig: Codable, Equatable {
    var instances: [ProviderInstance]
    var modelEntries: [ModelEntry]
    var modelGroups: [ModelGroup]
    var defaultPrimaryGroupId: String?
    var defaultSubGroupId: String?
    /// Stores per-session model bindings keyed by sessionId.
    var sessionBindings: [String: SessionModelBinding]
    /// ModelEntry IDs for individual models available in agent loop (minis-model-use).
    var agentLoopModelEntryIds: [String]
    /// ModelGroup IDs whose members are available in agent loop (minis-model-use).
    var agentLoopGroupIds: [String]
    /// Model group used for voice INPUT (speech-to-text), parallel to the
    /// Default Primary/Sub group selectors. Per-device (local-only, not synced).
    /// nil = offline System voice. The resolver picks the first audio-capable
    /// member of this group, else falls back to System.
    var voiceInputGroupId: String?
    /// Model group used for voice OUTPUT (text-to-speech). Same semantics.
    var voiceOutputGroupId: String?
    /// [T-ios-vision-group #182] Model group used for IMAGE UNDERSTANDING when the
    /// session's own model has no native vision. Same per-device, local-only
    /// semantics as the voice group selectors — a plain pointer at an ordinary
    /// ModelGroup, so the existing fallback/loadBalance routing is reused as-is
    /// and `ModelGroup` needs no purpose/kind field. nil = feature off (a
    /// non-vision model simply doesn't get the `read_image` tool, as before).
    var visionGroupId: String?
    /// Per-session inference settings (thinking toggle, etc.).
    var sessionInferenceConfigs: [String: SessionInferenceConfig]
    /// Soft-delete tombstones. Required to make deletes survive
    /// `mergeProviderConfig`'s union-by-id merge — without them a peer's
    /// older snapshot of the same id resurrects an instance/group/entry
    /// the user already deleted on this device.
    var deletedInstances: [ProviderConfigTombstone]
    var deletedModelEntries: [ProviderConfigTombstone]
    var deletedModelGroups: [ProviderConfigTombstone]

    init(instances: [ProviderInstance], modelEntries: [ModelEntry], modelGroups: [ModelGroup],
         defaultPrimaryGroupId: String?, defaultSubGroupId: String?,
         sessionBindings: [String: SessionModelBinding],
         agentLoopModelEntryIds: [String] = [], agentLoopGroupIds: [String] = [],
         voiceInputGroupId: String? = nil, voiceOutputGroupId: String? = nil,
         visionGroupId: String? = nil,
         sessionInferenceConfigs: [String: SessionInferenceConfig] = [:],
         deletedInstances: [ProviderConfigTombstone] = [],
         deletedModelEntries: [ProviderConfigTombstone] = [],
         deletedModelGroups: [ProviderConfigTombstone] = []) {
        self.instances = instances
        self.modelEntries = modelEntries
        self.modelGroups = modelGroups
        self.defaultPrimaryGroupId = defaultPrimaryGroupId
        self.defaultSubGroupId = defaultSubGroupId
        self.sessionBindings = sessionBindings
        self.agentLoopModelEntryIds = agentLoopModelEntryIds
        self.agentLoopGroupIds = agentLoopGroupIds
        self.voiceInputGroupId = voiceInputGroupId
        self.voiceOutputGroupId = voiceOutputGroupId
        self.visionGroupId = visionGroupId
        self.sessionInferenceConfigs = sessionInferenceConfigs
        self.deletedInstances = deletedInstances
        self.deletedModelEntries = deletedModelEntries
        self.deletedModelGroups = deletedModelGroups
    }

    // Backwards-compatible decode: newer fields may be absent in old data.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        instances = try container.decode([ProviderInstance].self, forKey: .instances)
        modelEntries = try container.decode([ModelEntry].self, forKey: .modelEntries)
        if let groups = try? container.decode([ModelGroup].self, forKey: .modelGroups) {
            modelGroups = groups
        } else {
            modelGroups = Self.decodeModelGroupsLeniently(container: container, key: .modelGroups)
        }
        defaultPrimaryGroupId = try container.decodeIfPresent(String.self, forKey: .defaultPrimaryGroupId)
        defaultSubGroupId = try container.decodeIfPresent(String.self, forKey: .defaultSubGroupId)
        sessionBindings = try container.decode([String: SessionModelBinding].self, forKey: .sessionBindings)
        agentLoopModelEntryIds = try container.decodeIfPresent([String].self, forKey: .agentLoopModelEntryIds) ?? []
        agentLoopGroupIds = try container.decodeIfPresent([String].self, forKey: .agentLoopGroupIds) ?? []
        voiceInputGroupId = try container.decodeIfPresent(String.self, forKey: .voiceInputGroupId)
        voiceOutputGroupId = try container.decodeIfPresent(String.self, forKey: .voiceOutputGroupId)
        visionGroupId = try container.decodeIfPresent(String.self, forKey: .visionGroupId)
        sessionInferenceConfigs = try container.decodeIfPresent([String: SessionInferenceConfig].self, forKey: .sessionInferenceConfigs) ?? [:]
        deletedInstances = try container.decodeIfPresent([ProviderConfigTombstone].self, forKey: .deletedInstances) ?? []
        deletedModelEntries = try container.decodeIfPresent([ProviderConfigTombstone].self, forKey: .deletedModelEntries) ?? []
        deletedModelGroups = try container.decodeIfPresent([ProviderConfigTombstone].self, forKey: .deletedModelGroups) ?? []
    }

    private enum CodingKeys: String, CodingKey {
        case instances, modelEntries, modelGroups, defaultPrimaryGroupId, defaultSubGroupId
        case sessionBindings, agentLoopModelEntryIds, agentLoopGroupIds, sessionInferenceConfigs
        case voiceInputGroupId, voiceOutputGroupId, visionGroupId
        case deletedInstances, deletedModelEntries, deletedModelGroups
    }

    private static func decodeModelGroupsLeniently(
        container: KeyedDecodingContainer<CodingKeys>, key: CodingKeys
    ) -> [ModelGroup] {
        struct Failable<T: Decodable>: Decodable {
            let value: T?
            init(from decoder: Decoder) throws {
                value = try? T(from: decoder)
            }
        }
        guard let wrappers = try? container.decode([Failable<ModelGroup>].self, forKey: key) else {
            return []
        }
        return wrappers.compactMap(\.value)
    }

    static let empty = ProviderConfig(
        instances: [],
        modelEntries: [],
        modelGroups: [],
        defaultPrimaryGroupId: nil,
        defaultSubGroupId: nil,
        sessionBindings: [:],
        agentLoopModelEntryIds: [],
        agentLoopGroupIds: []
    )
}

// MARK: - ProviderConfigStore

/// Single source of truth for provider instances, model entries, groups, and bindings.
/// Replaces scattered APIKeyStore, ActiveProviderStore, LastModelStore, AgentModelSettingsStore.
@MainActor
final class ProviderConfigStore: ObservableObject {
    static let shared = ProviderConfigStore()

    @Published private(set) var config: ProviderConfig

    /// Bumped whenever an OAuth token or string is saved/deleted in the Keychain,
    /// so views observing the store re-evaluate auth state. Also an L1 cache key
    /// component (T-new-session-hang-credential-cache): any credential change
    /// invalidates cached resolveCurrentEntry results.
    @Published var authRevision: UInt = 0

    /// Bumped in `save()` — i.e. on ANY provider/group/member add/update/remove.
    /// An L1 cache key component: any config mutation invalidates cached
    /// resolveCurrentEntry results. [T-new-session-hang-credential-cache]
    private(set) var configRevision: UInt = 0

    private let fileURL: URL

    /// [review B3] The on-disk config path, exposed so the backup importer can
    /// snapshot it before a restore. Read-only accessor — the file itself is
    /// still written exclusively through `save()`.
    nonisolated static var configFileURLForBackup: URL {
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
        return library.appendingPathComponent("MinisChat/provider-config.json")
    }

    /// v3 SQLite store. Populated on first launch from `provider-config.json`
    /// (migration); then becomes the source of truth for sync record
    /// emission and inbound merge. The JSON file is kept as a downgrade
    /// safety mirror — every mutate rewrites both.
    /// Held as Optional so init can short-circuit if DB open fails (rare,
    /// but we'd rather degrade to JSON-only than crash).
    private(set) var db: ProviderConfigDB?

    /// Snapshot of the config the last time `save()` ran, used to diff
    /// against the current `config` for per-record v3 markDirty emission.
    /// `nil` until the first save() runs after init.
    private var lastSavedSnapshot: ProviderConfig?

    /// [T-provider-entry-composite-key] legacyUuid → compositeKey map.
    /// Populated by migration (every old entry's uuid → its composite key) and
    /// by inbound sync (a peer's entry record's uuid / legacyUuid → composite
    /// key, computed from the record's instanceId+modelId). `entry(for:)` uses
    /// it to resolve a still-uuid reference; `normalizeReferences(using:)`
    /// rewrites group/binding/agent-loop references that match a known
    /// legacyUuid to the composite key. Persisted in provider_local_kv so it
    /// survives restarts and deferred (out-of-order) record arrival.
    private(set) var legacyUuidToCompositeKey: [String: String] = [:]

    /// True while the one-shot composite-key migration is running. Resolve /
    /// save use the pre-migration snapshot during this window; the migrated
    /// config is swapped in atomically when it flips back to false. Prevents a
    /// half-migrated state from being read or pushed.
    private(set) var compositeKeyMigrationInFlight = false

    init() {
        let libraryURL = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let baseURL = libraryURL.appendingPathComponent("MinisChat", isDirectory: true)
        try? FileManager.default.createDirectory(at: baseURL, withIntermediateDirectories: true)
        self.fileURL = baseURL.appendingPathComponent("provider-config.json")
        // First-frame value comes from the V2 JSON so the UI has something to
        // render before the SQLite DB finishes opening. Once the DB is ready
        // and V3 is the authoritative store (migration done), we overwrite
        // `config` from `dumpProviderConfig()` — the V3 path that preserves
        // group members verbatim — so the JSON's (potentially member-truncated
        // from an older build) snapshot never becomes the persisted/pushed
        // source of truth. [T-icloud-modelgroup-member-loss]
        // [T-ios-reboot-config-loss] Load with missing-vs-unreadable
        // discrimination. After a device reboot iOS can relaunch the app in
        // the background BEFORE first unlock (BGTask / CloudKit push /
        // location session); the default file-protection class
        // (completeUntilFirstUserAuthentication) makes provider-config.json
        // unreadable then, and the old silent `.empty` fallback plus any
        // later save() overwrote the real JSON, bulk-replaced the V3 DB from
        // the empty snapshot, and pushed the wipe to iCloud — the "blank app
        // after reboot" field report.
        let loaded = Self.loadGuarded(from: fileURL)
        self.config = loaded.config
        self.loadDegradation = loaded.degradation
        self.lastSavedSnapshot = self.config
        if loaded.degradation == .none {
            ensureVoiceTemplateModels()
        } else {
            registerDegradedRecovery()
        }
        Self.setupDBAndMigrate(jsonURL: fileURL) { [weak self] db in
            Task { @MainActor in
                await self?.adoptDB(db)
            }
        }
        registerKeychainSyncObserver()
        // [T-provider-sync-apply-interaction-defer] Timestamp foreground
        // activations so applyMergedConfigFromSync can hold its whole-store
        // publish out of the fragile post-foreground rebuild window.
        syncApplyForegroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.lastDidBecomeActiveAt = Date() }
        }
    }

    /// [T-ios-reboot-config-loss] Adopt the opened V3 DB and, when it is
    /// authoritative, replace the JSON-seeded config with its dump. Extracted
    /// verbatim from init's completion closure so degraded-load recovery can
    /// re-run the same adoption after protected data becomes available.
    private func adoptDB(_ db: ProviderConfigDB?) async {
                self.db = db
                guard let db else { return }
                // [T-thinking-rules-phase2] Prime the synchronous thinking-rule cache the
                // resolver reads during request assembly. Done here because this is the
                // first point where the DB is known-open; before this the cache is empty,
                // which resolves to built-in behaviour rather than anything wrong.
                await self.reloadThinkingRuleCache()
                // [T-provider-entry-composite-key] Load the persisted
                // legacyUuid→compositeKey map (normalization runs AFTER the
                // authoritative config is loaded below, so it operates on the
                // real config, not the JSON seed).
                await self.loadLegacyUuidMap()
                // V3 authoritative-load: replace the JSON-seeded config with
                // the DB dump when V3 is enabled. dumpProviderConfig() never
                // truncates group memberEntryIds.
                //
                // [T-icloud-fresh-restore-provider-groups] Previously this also
                // required the `migrationCompleted` flag — but that flag is ONLY
                // set when a local `provider-config.json` was migrated at first
                // launch. A fresh device restored from iCloud has no JSON, so the
                // flag stayed false forever, and providers/model-groups that
                // arrived via inbound sync (written into the V3 DB) were never
                // loaded authoritatively — they vanished on every relaunch even
                // though the rows were in the DB. Treat the DB as authoritative
                // whenever V3 is on AND the DB is non-empty (or migration ran),
                // and stamp the flag so the rest of the store stays consistent.
                let migratedFlag = UserDefaults.standard.bool(forKey: "cloudSync.providerV3.migrationCompleted")
                let dbNonEmpty = !(await db.isEmpty())
                guard ProviderV3Bootstrap.isEnabled, (migratedFlag || dbNonEmpty) else {
                    logger.info("[GroupLoad] init: staying on V2 JSON config (v3Enabled=\(ProviderV3Bootstrap.isEnabled) migrated=\(migratedFlag) dbNonEmpty=\(dbNonEmpty))")
                    return
                }
                if !migratedFlag && dbNonEmpty {
                    UserDefaults.standard.set(true, forKey: "cloudSync.providerV3.migrationCompleted")
                    logger.info("[GroupLoad] init: V3 DB is authoritative via inbound sync (no local JSON migration) — stamping migrationCompleted")
                }
                let fresh = await db.dumpProviderConfig()
                let jsonGroupMembers = self.config.modelGroups.reduce(0) { $0 + $1.memberEntryIds.count }
                let dbGroupMembers = fresh.modelGroups.reduce(0) { $0 + $1.memberEntryIds.count }
                // [T-icloud-provider-sync-consistency] Heal any cross-device
                // duplicate entries already on disk at load time (e.g. a DB
                // that accumulated dup rows before this fix shipped). The fold
                // is deterministic so it converges with peers; pruned rows are
                // deleted + tombstoned so they don't resurrect.
                let (deduped, prunedAtLoad) = Self.dedupeEntriesByModel(fresh)
                self.config = deduped
                self.lastSavedSnapshot = deduped
                // [T-provider-entry-composite-key] Build the legacyUuid map from
                // the local entries (each entry's random uuid → its composite
                // key), then normalize any group/binding/agent-loop reference
                // still pointing at a uuid to the composite key. This is what
                // heals group memberEntryIds that were written as uuids before
                // the entry id became a composite key. Detailed logging so the
                // migration/normalization is auditable in the field.
                var localPairs: [String: String] = [:]
                for e in deduped.modelEntries where e.uuid != e.compositeKey {
                    localPairs[e.uuid] = e.compositeKey
                }
                let danglingBefore = deduped.modelGroups.reduce(0) { acc, g in
                    acc + g.memberEntryIds.filter { ref in !deduped.modelEntries.contains { $0.id == ref || $0.uuid == ref } }.count
                }
                logger.info("[CompositeKeyMigrate] init: entries=\(deduped.modelEntries.count) localUuid→ckPairs=\(localPairs.count) danglingGroupRefs(before)=\(danglingBefore) lmapSize(persisted)=\(self.legacyUuidToCompositeKey.count)")
                for (u, ck) in localPairs where self.legacyUuidToCompositeKey[u] != ck {
                    self.legacyUuidToCompositeKey[u] = ck
                }
                if self.normalizeReferences() {
                    self.lastSavedSnapshot = self.config
                    self.save()
                    let danglingAfter = self.config.modelGroups.reduce(0) { acc, g in
                        acc + g.memberEntryIds.filter { ref in !self.config.modelEntries.contains { $0.id == ref || $0.uuid == ref } }.count
                    }
                    logger.info("[CompositeKeyMigrate] init: normalizeReferences rewrote refs → danglingGroupRefs(after)=\(danglingAfter); persisted+saved")
                } else {
                    logger.info("[CompositeKeyMigrate] init: normalizeReferences no-op (no uuid refs matched lmap; \(danglingBefore) dangling refs need a cloud entry record to normalize)")
                }
                logger.info("[GroupLoad] init: loaded authoritative config from V3 DB — groups=\(deduped.modelGroups.count) entries=\(deduped.modelEntries.count) groupMembers(json=\(jsonGroupMembers)→db=\(dbGroupMembers)) prunedDuplicates=\(prunedAtLoad.count)")
                self.ensureVoiceTemplateModels()
                self.objectWillChange.send()
                if !prunedAtLoad.isEmpty {
                    let snapshot = deduped
                    let toDelete = prunedAtLoad
                    Task.detached {
                        await db.bulkReplace(from: snapshot)
                        for eid in toDelete { await db.deleteEntryRow(id: eid) }
                    }
                    for eid in prunedAtLoad {
                        Task { await ChatStore.shared.markDirty(recordType: "ProviderModelEntryV3", recordId: eid, operation: "delete") }
                    }
                }
    }

    /// [T-new-session-hang-credential-cache] L2 invalidation hook #3: iCloud
    /// Keychain sync. A `-25300` (item-not-synced) miss cached as `false` on
    /// THIS device must be dropped once the sibling device's credential syncs
    /// in, or routing would keep skipping a now-credentialed provider until
    /// the 15s TTL. The sync event carries no instanceId, so clear all.
    private func registerKeychainSyncObserver() {
        CFNotificationCenterAddObserver(
            CFNotificationCenterGetDarwinNotifyCenter(),
            Unmanaged.passUnretained(self).toOpaque(),
            { _, _, _, _, _ in
                ProviderCredentialCache.shared.invalidateAll()
                // [T-ios-provider-row-keychain-in-body] The Providers-list row cache
                // keys on `authRevision`, which a Keychain sync does NOT bump — clear
                // it here too or the row would show stale credential state until its
                // own TTL lapses.
                ProviderRowCredentialCache.shared.invalidateAll()
            },
            "com.apple.security.view-change" as CFString,
            nil,
            .deliverImmediately
        )
    }

    // MARK: - [T-ios-reboot-config-loss] Degraded-load guard

    /// Why the load was degraded. `.unreadable` = the file exists but Data(contentsOf:)
    /// failed — the classic pre-first-unlock file-protection state after a device
    /// reboot (or a transient I/O error). `.undecodable` = bytes were readable but
    /// JSON decoding failed (corrupt file). In both cases the on-disk state must
    /// not be overwritten by the empty in-memory seed.
    private enum ConfigLoadDegradation { case none, unreadable, undecodable }
    private var loadDegradation: ConfigLoadDegradation = .none
    private var degradedRecoveryObservers: [NSObjectProtocol] = []

    /// Load wrapper distinguishing "no file" (fresh install → empty is correct)
    /// from "file present but unreadable/undecodable" (must NOT clobber disk).
    private static func loadGuarded(from url: URL) -> (config: ProviderConfig, degradation: ConfigLoadDegradation) {
        guard FileManager.default.fileExists(atPath: url.path) else { return (.empty, .none) }
        guard let data = try? Data(contentsOf: url) else {
            logger.error("[RebootGuard] provider-config.json exists but is UNREADABLE (protected data locked before first unlock, or I/O error) — degraded mode, save() suppressed")
            return (.empty, .unreadable)
        }
        guard (try? JSONDecoder().decode(ProviderConfig.self, from: data)) != nil else {
            logger.error("[RebootGuard] provider-config.json exists but FAILED TO DECODE — degraded mode, not overwriting the on-disk bytes")
            return (.empty, .undecodable)
        }
        return (load(from: url), .none)
    }

    /// Arm one-shot recovery: retry the load when protected data becomes
    /// available (post-unlock) and again on every foreground activation
    /// (covers the race where unlock happened between load and registration).
    private func registerDegradedRecovery() {
        let names: [Notification.Name] = [
            UIApplication.protectedDataDidBecomeAvailableNotification,
            UIApplication.didBecomeActiveNotification,
        ]
        for name in names {
            degradedRecoveryObservers.append(NotificationCenter.default.addObserver(
                forName: name, object: nil, queue: .main
            ) { [weak self] _ in
                Task { @MainActor in self?.recoverFromDegradedLoad() }
            })
        }
    }

    private func recoverFromDegradedLoad() {
        guard loadDegradation != .none else { return }
        let reloaded = Self.loadGuarded(from: fileURL)
        switch reloaded.degradation {
        case .none:
            loadDegradation = .none
            config = reloaded.config
            lastSavedSnapshot = reloaded.config
            ensureVoiceTemplateModels()
            objectWillChange.send()
            logger.info("[RebootGuard] recovered provider config after unlock — instances=\(reloaded.config.instances.count) entries=\(reloaded.config.modelEntries.count) groups=\(reloaded.config.modelGroups.count)")
        case .unreadable:
            // Still locked (e.g. didBecomeActive raced the unlock) — keep waiting.
            return
        case .undecodable:
            // Bytes readable but corrupt while the device is unlocked: the JSON
            // itself is unrecoverable. Exit degraded mode so the app is usable
            // again and rely on the V3 DB's authoritative dump (adoptDB below)
            // to restore the real config; the corrupt JSON gets rewritten by
            // the next legitimate save().
            guard UIApplication.shared.isProtectedDataAvailable else { return }
            loadDegradation = .none
            logger.error("[RebootGuard] provider-config.json is corrupt (readable but undecodable while unlocked) — exiting degraded mode; V3 DB dump will restore state if present")
        }
        for o in degradedRecoveryObservers { NotificationCenter.default.removeObserver(o) }
        degradedRecoveryObservers.removeAll()
        // Re-run DB adoption: if the DB never opened (pre-unlock open failure)
        // this establishes it now; if it is open and non-empty, its dump
        // becomes authoritative again on top of the reloaded JSON seed.
        if self.db == nil {
            Self.setupDBAndMigrate(jsonURL: fileURL) { [weak self] db in
                Task { @MainActor in await self?.adoptDB(db) }
            }
        } else {
            let existing = self.db
            Task { @MainActor in await self.adoptDB(existing) }
        }
    }

    /// Test-only initializer.
    init(fileURL: URL) {
        self.fileURL = fileURL
        self.config = Self.load(from: fileURL)
        self.lastSavedSnapshot = self.config
        // Tests don't open the DB by default.
    }

    /// Async DB open + one-shot migration from provider-config.json.
    /// Runs off the main thread so a slow open doesn't block UI launch.
    private static func setupDBAndMigrate(jsonURL: URL, completion: @escaping (ProviderConfigDB?) -> Void) {
        Task.detached {
            do {
                let db = try ProviderConfigDB()
                // Migration: if DB is empty (fresh install or first v3
                // launch) and JSON exists, ingest the JSON. The
                // UserDefaults flag prevents re-running migration even
                // if the DB is later emptied (rare; usually a sync
                // bulk-replace would re-populate it).
                let migratedKey = "cloudSync.providerV3.migrationCompleted"
                let alreadyMigrated = UserDefaults.standard.bool(forKey: migratedKey)
                if await db.isEmpty(), !alreadyMigrated,
                   FileManager.default.fileExists(atPath: jsonURL.path) {
                    let ok = await db.migrateFromLegacyJSON(at: jsonURL)
                    if ok {
                        UserDefaults.standard.set(true, forKey: migratedKey)
                        logger.info("[v3] ProviderConfig migration to SQLite complete")
                    }
                }
                completion(db)
            } catch {
                logger.error("[v3] ProviderConfigDB open failed: \(error) — staying on JSON-only mode")
                completion(nil)
            }
        }
    }

    // MARK: - Persistence

    private static func load(from url: URL) -> ProviderConfig {
        guard let data = try? Data(contentsOf: url),
              var config = try? JSONDecoder().decode(ProviderConfig.self, from: data) else {
            return .empty
        }
        // Migrate group memberEntryIds from legacy composite keys to stable UUIDs.
        //
        // [T-icloud-modelgroup-member-loss] CRITICAL: this path must NOT drop a
        // member just because the referenced entry isn't in `modelEntries`
        // right now. The entry can be legitimately absent for a transient
        // reason (its own ProviderModelEntryV3 record hasn't merged in yet,
        // or a per-instance model refresh is still in flight) — dropping it
        // here would truncate the fully-synced member list, then `save()`
        // would write the truncated list back to the V2 JSON *and* re-push a
        // truncated V3
        // group record, propagating the deletion to every device. A member
        // that is a syntactically valid UUID but currently unresolved is the
        // ModelGroupRouter's problem to filter at ROUTE time, NOT ours to
        // delete from the persisted group. We only rewrite legacy composite
        // keys → UUID; anything that already looks like a UUID is preserved
        // verbatim even when unresolved.
        let compositeToUUID = Dictionary(
            config.modelEntries.map { ($0.compositeKey, $0.id) },
            uniquingKeysWith: { first, _ in first }
        )
        let validEntryIds = Set(config.modelEntries.map(\.id))
        func looksLikeUUID(_ s: String) -> Bool { UUID(uuidString: s) != nil }
        for i in config.modelGroups.indices {
            let before = config.modelGroups[i].memberEntryIds
            var unresolvedPreserved = 0
            config.modelGroups[i].memberEntryIds = before.compactMap { entryId in
                // 1. Already valid UUID resolvable in the current store → keep.
                if validEntryIds.contains(entryId) { return entryId }
                // 2. Legacy composite key → map to UUID.
                if let mapped = compositeToUUID[entryId] { return mapped }
                // 3. Looks like a UUID but isn't in the store yet → PRESERVE.
                //    Do not treat a transient sync gap as a permanent stale id.
                if looksLikeUUID(entryId) {
                    unresolvedPreserved += 1
                    return entryId
                }
                // 3.5. Built-in System engine member (sentinel-prefixed) → PRESERVE.
                //    These never live in the store (synthetic, local-only) and are
                //    resolved by id-prefix at route time, so they'd otherwise be
                //    wrongly dropped as "unrecognisable" on a V2 JSON load.
                if entryId == SystemVoiceProvider.builtinProviderId
                    || entryId.hasPrefix(SystemVoiceProvider.builtinProviderId + "/") {
                    return entryId
                }
                // 4. Neither a known composite key nor a UUID → genuinely
                //    unrecognisable; drop (this is the only safe drop).
                logger.warning("[GroupLoad] group \(config.modelGroups[i].id.prefix(8)) dropping unrecognisable member token '\(entryId.prefix(12))' (not a UUID, not a known composite key)")
                return nil
            }
            if unresolvedPreserved > 0 {
                logger.info("[GroupLoad] group \(config.modelGroups[i].id.prefix(8)) (\(config.modelGroups[i].name)) preserved \(unresolvedPreserved) unresolved UUID member(s) across load — entries may still be syncing; members=\(before.count)→\(config.modelGroups[i].memberEntryIds.count)")
            }
        }
        // Remove groups that became empty after cleanup and fix up default
        // pointers. With step 3 above preserving unresolved UUIDs, a group
        // only becomes empty here if it genuinely had zero members or only
        // unrecognisable (non-UUID, non-composite) tokens — never from a
        // transient sync gap.
        let emptyGroupIds = Set(config.modelGroups.filter { $0.memberEntryIds.isEmpty }.map(\.id))
        if !emptyGroupIds.isEmpty {
            logger.warning("[GroupLoad] removing \(emptyGroupIds.count) group(s) that are empty after member normalisation: \(emptyGroupIds.map { $0.prefix(8) }.joined(separator: ","))")
            config.modelGroups.removeAll { emptyGroupIds.contains($0.id) }
            if let def = config.defaultPrimaryGroupId, emptyGroupIds.contains(def) {
                config.defaultPrimaryGroupId = config.modelGroups.first?.id
            }
            if let def = config.defaultSubGroupId, emptyGroupIds.contains(def) {
                config.defaultSubGroupId = nil
            }
        }
        // Restore default group pointer if lost (e.g. missing key in JSON) but groups exist.
        if config.defaultPrimaryGroupId == nil, let firstGroup = config.modelGroups.first {
            config.defaultPrimaryGroupId = firstGroup.id
        }
        // Infer output modalities from model names for entries that don't have modalityOverride yet.
        for i in config.modelEntries.indices {
            let entry = config.modelEntries[i]
            let base = entry.baseModel
            if base.modalityOverride == nil {
                let inferred = base.withInferredModality()
                if inferred.modalityOverride != nil {
                    config.modelEntries[i] = ModelEntry(
                        uuid: entry.id,
                        providerInstanceId: entry.providerInstanceId,
                        model: inferred,
                        overrides: entry.overrides,
                        isCustom: entry.isCustom,
                        isHidden: entry.isHidden
                    )
                }
            }
        }
        return config
    }

    private func save() {
        // [T-ios-reboot-config-loss] HARD STOP while the on-disk config could
        // not be read (pre-first-unlock launch after a device reboot, or a
        // corrupt file pending DB restore). Persisting now would overwrite
        // the real provider-config.json with the empty in-memory seed,
        // bulk-replace the V3 SQLite store from it, AND markDirty-push the
        // wipe to iCloud — propagating the loss to every synced device.
        guard loadDegradation == .none else {
            logger.error("[RebootGuard] save() suppressed — config load was degraded (\(String(describing: self.loadDegradation))); awaiting post-unlock recovery")
            return
        }
        // Any config mutation funnels through here — bump the L1 cache epoch so
        // resolveCurrentEntry re-resolves. [T-new-session-hang-credential-cache]
        configRevision &+= 1

        // 1. Downgrade-safety mirror: keep provider-config.json fully
        //    current so a user reverting to a v2 build sees a complete
        //    snapshot. v2 builds ignore unknown SQLite files; the JSON
        //    remains the cross-version source.
        do {
            let data = try JSONEncoder().encode(config)
            try data.write(to: fileURL, options: .atomic)
        } catch {
            logger.error("Failed to save provider config: \(error)")
        }

        // 2. v3 SQLite mirror — bulk-replace the whole DB from the
        //    current ProviderConfig. At single-instance-per-row sizes
        //    (< 1MB total) full-replace per mutate is < 1ms inside a
        //    single transaction; not a hot path.
        let snapshot = config
        let prior = lastSavedSnapshot
        lastSavedSnapshot = snapshot
        if let db {
            Task.detached {
                await db.bulkReplace(from: snapshot)
                await Self.emitV3MarkDirty(prior: prior, current: snapshot)
            }
        }

        // 3. v2 compatibility: keep marking the legacy whole-file dirty
        //    so old peer devices that haven't upgraded to v3 still
        //    receive a current snapshot via the legacy
        //    ProviderConfigV2 record. New peer devices ignore inbound
        //    V2 records once their v3 flag is on (S7).
        Task { await ChatStore.shared.markDirty(recordType: "ProviderConfig", recordId: "provider-config") }
    }

    /// Diff `prior` vs `current` and emit per-record V3 markDirty calls
    /// for what changed. Insertions and updates both go as op="upsert";
    /// removals go as op="delete" so CloudKit propagates a real
    /// deletion tombstone (the v3 fix for "deleted provider resurrects").
    private static func emitV3MarkDirty(
        prior: ProviderConfig?,
        current: ProviderConfig
    ) async {
        // Without a prior snapshot, treat everything as an upsert. This
        // happens on first save() after a fresh launch when the
        // lastSavedSnapshot is the initial JSON-loaded state — we
        // still want all of it in the dirty queue so the v3 sync
        // engine's first batch carries the full picture to cloud.
        guard let prior else {
            for inst in current.instances {
                await ChatStore.shared.markDirty(recordType: "ProviderInstanceV3", recordId: inst.id, operation: "upsert")
            }
            for entry in current.modelEntries {
                // Plan-X: V3 entry record is keyed by uuid (= DB primary key).
                await ChatStore.shared.markDirty(recordType: "ProviderModelEntryV3", recordId: entry.uuid, operation: "upsert")
            }
            for group in current.modelGroups {
                await ChatStore.shared.markDirty(recordType: "ProviderModelGroupV3", recordId: group.id, operation: "upsert")
            }
            return
        }

        // Instances
        let priorInst = Self.dictByIdLastWins(prior.instances.map { ($0.id, $0) })
        let curInst = Self.dictByIdLastWins(current.instances.map { ($0.id, $0) })
        for (id, inst) in curInst {
            if priorInst[id] != inst {
                await ChatStore.shared.markDirty(recordType: "ProviderInstanceV3", recordId: id, operation: "upsert")
            }
        }
        // [T-ios-provider-reorder] A pure reorder changes no instance STRUCT
        // (order is positional in config.instances), so the per-record diff
        // above emits nothing — the new sort_order values written by
        // bulkReplace never uploaded and peers pulled devices back to the old
        // order. Detect an order change explicitly and mark every instance
        // dirty so records carry the fresh sortOrder (+ updated_at bumped by
        // bulkReplace, so LWW protects the new order against stale echoes).
        if prior.instances.map(\.id) != current.instances.map(\.id) {
            for inst in current.instances {
                await ChatStore.shared.markDirty(recordType: "ProviderInstanceV3", recordId: inst.id, operation: "upsert")
            }
        }
        // [T-icloud-provider-sync-consistency] Do NOT diff-infer instance
        // deletions — same reasoning as entries/groups below. A "prior had it,
        // current doesn't" gap is also produced when dumpProviderConfig drops
        // an instance it couldn't parse (e.g. a providerType from a newer build)
        // or when the in-memory config is transiently incomplete; inferring a
        // delete from that wipes the instance on every device. removeInstance
        // emits its own explicit ProviderInstanceV3 delete record.
        for id in priorInst.keys where curInst[id] == nil {
            logger.info("[v3] emitV3MarkDirty: instance \(id.prefix(8)) absent in current snapshot — NOT auto-deleting (explicit removal emits its own tombstone)")
        }

        // Model entries — Plan-X: key the diff + V3 record by uuid (DB primary
        // key), not the composite key, so markDirty targets the right record.
        let priorEntries = Self.dictByIdLastWins(prior.modelEntries.map { ($0.uuid, $0) })
        let curEntries = Self.dictByIdLastWins(current.modelEntries.map { ($0.uuid, $0) })
        for (uuid, entry) in curEntries {
            if priorEntries[uuid] != entry {
                await ChatStore.shared.markDirty(recordType: "ProviderModelEntryV3", recordId: uuid, operation: "upsert")
            }
        }
        // [T-icloud-provider-sync-consistency] Do NOT diff-infer entry/group
        // deletions here. A "prior had it, current doesn't" gap is NOT proof of
        // a user deletion — it also happens when the in-memory config is
        // transiently incomplete (a peer's entry record hasn't merged in yet, a
        // refresh is mid-flight, a reload raced). Diff-inferred deletes were the
        // amplifier that propagated member/entry loss to every device. Real
        // deletions are emitted explicitly by removeEntry/removeGroup/
        // removeInstance via markDirtyDelete(...) at the moment the user acts.
        for id in priorEntries.keys where curEntries[id] == nil {
            logger.info("[v3] emitV3MarkDirty: entry \(id.prefix(8)) absent in current snapshot — NOT auto-deleting (explicit removal emits its own tombstone)")
        }

        // Groups
        let priorGroups = Self.dictByIdLastWins(prior.modelGroups.map { ($0.id, $0) })
        let curGroups = Self.dictByIdLastWins(current.modelGroups.map { ($0.id, $0) })
        for (id, group) in curGroups {
            if priorGroups[id] != group {
                await ChatStore.shared.markDirty(recordType: "ProviderModelGroupV3", recordId: id, operation: "upsert")
            }
        }
        for id in priorGroups.keys where curGroups[id] == nil {
            logger.info("[v3] emitV3MarkDirty: group \(id.prefix(8)) absent in current snapshot — NOT auto-deleting (explicit removal emits its own tombstone)")
        }
    }

    /// Build `[String: T]` from a sequence of `(id, value)` pairs where
    /// later occurrences win on duplicates. Safe replacement for
    /// `Dictionary(uniqueKeysWithValues:)` — that initializer traps on
    /// duplicate keys (Swift stdlib precondition), which has crashed
    /// the app when sync-merge paths or external Share Extension flows
    /// produce arrays containing the same provider/entry/group id more
    /// than once.
    private static func dictByIdLastWins<T>(_ pairs: [(String, T)]) -> [String: T] {
        var out: [String: T] = [:]
        out.reserveCapacity(pairs.count)
        for (k, v) in pairs { out[k] = v }
        return out
    }

    /// Reload config from disk (e.g. after iCloud sync overwrites the file).
    ///
    /// [T-icloud-modelgroup-member-loss] When V3 is the authoritative store,
    /// reload from the SQLite DB (dumpProviderConfig — preserves group members
    /// verbatim) instead of the V2 JSON. The legacy whole-file V2 sync path
    /// (CloudSyncEngine) calls this after merging; routing it through the
    /// member-truncating `load(from:)` is exactly what propagated group-member
    /// deletions. The JSON branch remains for pre-migration / kill-switched
    /// devices.
    func reloadFromDisk() async {
        // [T-icloud-fresh-restore-provider-groups] Mirror the init gate: the V3
        // DB is authoritative when V3 is on AND (migration ran OR the DB is
        // non-empty). On a fresh iCloud-restore device the migrationCompleted
        // flag is never set (no local JSON to migrate), so without the
        // dbNonEmpty clause a reload here would fall back to the truncating
        // V2 JSON path and drop inbound-synced group members.
        let migratedFlag = UserDefaults.standard.bool(forKey: "cloudSync.providerV3.migrationCompleted")
        var dbAuthoritative = false
        if let db, ProviderV3Bootstrap.isEnabled {
            if migratedFlag {
                dbAuthoritative = true
            } else {
                dbAuthoritative = !(await db.isEmpty())
            }
        }
        if let db, dbAuthoritative {
            let priorMembers = config.modelGroups.reduce(0) { $0 + $1.memberEntryIds.count }
            let fresh = await db.dumpProviderConfig()
            let freshMembers = fresh.modelGroups.reduce(0) { $0 + $1.memberEntryIds.count }
            config = fresh
            lastSavedSnapshot = fresh
            logger.info("[GroupLoad] reloadFromDisk: V3 DB dump — groups=\(fresh.modelGroups.count) groupMembers(\(priorMembers)→\(freshMembers))")
            objectWillChange.send()
            return
        }
        logger.info("[GroupLoad] reloadFromDisk: V2 JSON path (no DB / v3 disabled / not migrated)")
        config = Self.load(from: fileURL)
        objectWillChange.send()
    }

    // MARK: - Tombstones

    /// Append (or refresh `deletedAt` on) tombstones for the given ids.
    /// `mergeProviderConfig` reads these to suppress resurrection from a
    /// peer's older snapshot.
    static func recordTombstone(in list: inout [ProviderConfigTombstone], ids: [String]) {
        guard !ids.isEmpty else { return }
        let now = Date()
        var byId = Self.dictByIdLastWins(list.map { ($0.id, $0) })
        for id in ids {
            byId[id] = ProviderConfigTombstone(id: id, deletedAt: now)
        }
        list = Array(byId.values)
    }

    // MARK: - Provider Instances

    var instances: [ProviderInstance] { config.instances }

    func addInstance(_ instance: ProviderInstance) {
        config.instances.append(instance)
        if instance.credentialType == .oauth {
            // OAuth instances: pre-populate with static built-in list, enriched with models.dev data.
            let builtIn: [LLMModel]
            let hasManualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") != nil
            if instance.providerType == .openAI {
                builtIn = ModelsDevAPI.enrichModels(LLMModel.allOpenAICodexOAuth)
            } else if instance.providerType == .openRouter {
                builtIn = ModelsDevAPI.enrichModels(instance.providerType.builtInModels)
            } else {
                builtIn = ModelsDevAPI.enrichModels(instance.providerType.builtInModels)
            }
            let entries = builtIn.map { model in
                ModelEntry(providerInstanceId: instance.id, model: model)
            }
            config.modelEntries.append(contentsOf: entries)
            logger.info("[ModelList] addInstance (OAuth): instance=\(instance.label) seeded \(entries.count) built-in entries: [\(entries.map { $0.baseModel.id }.prefix(10).joined(separator: ","))]")
            // Manual OAuth tokens and OpenRouter OAuth can fetch models from the API
            if hasManualToken || instance.providerType == .openRouter {
                Task { await refreshModels(for: instance) }
            }
        } else if !VoiceProviderTemplate.mockEntries(for: instance).isEmpty {
            let mock = VoiceProviderTemplate.mockEntries(for: instance)
            config.modelEntries.append(contentsOf: mock)
            logger.info("[ModelList] addInstance (voice): instance=\(instance.label) seeded \(mock.count) mock voice entries: [\(mock.map { $0.baseModel.id }.joined(separator: ","))]")
            // Dual-purpose providers (MiMo, DashScope) also serve text models
            // via /v1/models. Fetch them now; replaceEntries preserves the
            // voice seeds above (T-mimo-shadow-voice guard).
            if instance.credentialType == .apiKey {
                Task { await refreshModels(for: instance) }
            }
        } else {
            Task { await refreshModels(for: instance) }
        }
        save()
    }

    /// Sync voice-template instances' model entries with the current template.
    /// Adds new entries introduced in template updates and removes stale ones
    /// that no longer exist in the template (e.g. the old generic "seed-tts-2.0"
    /// Doubao entry replaced by per-speaker entries in 502a0854).
    func ensureVoiceTemplateModels() {
        var changed = false
        for instance in config.instances {
            guard let tpl = VoiceProviderTemplate.template(forBaseURL: instance.effectiveCustomBaseURL) else { continue }
            let templateById = Dictionary(tpl.mockModels.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
            let templateIds = Set(templateById.keys)
            let existingIds = Set(config.modelEntries
                .filter { $0.providerInstanceId == instance.id }
                .map { $0.baseModel.id })

            // Only remove stale VOICE-TEMPLATE SEEDS not in the current template
            // (seeds a previous template version added, e.g. Doubao's retired
            // seed-tts-2.0). Never remove API-fetched models — dual-purpose
            // vendors serve both template voice seeds and API models (from
            // /v1/models) on one host, and the API models must survive launch.
            //
            // This guard distinguishes "template seed" from "API model" by
            // modality SHAPE. Two prior attempts widened the shape too far:
            //   - 9485394c scoped removal to "has .audioInput OR .audioOutput",
            //     assuming API models never carry an audio modality. False for
            //     MiMo, whose /v1/models returns audio-capable models.
            //   - [T-mimo-launch-model-count-shrink] tightened to "audio AND NOT
            //     .textInput", which spared API TTS ([.textInput,.audioOutput])
            //     and chat-with-audio (has .textInput) — but NOT a future
            //     API-fetched dedicated ASR model, which is [.audioInput,
            //     .textOutput] (no .textInput) and would still be wrongly wiped
            //     every launch once a vendor exposes one via /v1/models.
            //
            // Robust discriminator [T-voice-seed-shape-exact]: every template
            // mockModel seed is authored as EXACTLY ONE modality flag —
            // .audioOutput alone (TTS voices) or .audioInput alone (ASR). No
            // API-derived model is ever a single audio flag: OpenAIModelsAPI /
            // inferDedicatedVoiceModality always attach the paired text side
            // (TTS → [.textInput,.audioOutput], ASR → [.audioInput,.textOutput],
            // chat → text ∪ …), i.e. ≥2 flags. So only remove entries whose
            // modality is EXACTLY .audioOutput or EXACTLY .audioInput — this
            // still cleans up any retired single-flag template seed while
            // sparing every API model regardless of its ASR/TTS/omni shape.
            let seedShapes: [ModelModality] = [.audioOutput, .audioInput]
            let voiceExistingIds = Set(config.modelEntries
                .filter { $0.providerInstanceId == instance.id }
                .filter { seedShapes.contains($0.baseModel.modalityOverride ?? []) }
                .map { $0.baseModel.id })
            let toRemove = voiceExistingIds.subtracting(templateIds)
            let toAdd = templateIds.subtracting(existingIds)
            if !toRemove.isEmpty {
                config.modelEntries.removeAll { $0.providerInstanceId == instance.id && toRemove.contains($0.baseModel.id) }
                changed = true
                logger.info("[VoiceTemplateMigrate] \(instance.label): removed stale voice entries: \(toRemove.sorted())")
            }
            if !toAdd.isEmpty {
                let newEntries = tpl.mockModels
                    .filter { toAdd.contains($0.id) }
                    .map { ModelEntry(providerInstanceId: instance.id, model: $0) }
                config.modelEntries.append(contentsOf: newEntries)
                changed = true
                logger.info("[VoiceTemplateMigrate] \(instance.label): added \(newEntries.count) new entries: \(toAdd.sorted())")
            }
            // Heal corrupted modality: auto-refresh can overwrite template
            // entries with text-only modality from /v1/models. Restore the
            // template's authoritative modalityOverride for any entry whose
            // baseModel modality diverged.
            for i in config.modelEntries.indices {
                let e = config.modelEntries[i]
                guard e.providerInstanceId == instance.id,
                      let tplModel = templateById[e.baseModel.id],
                      e.baseModel.modalityOverride != tplModel.modalityOverride else { continue }
                logger.info("[VoiceTemplateMigrate] \(instance.label): healing modality for \(e.baseModel.id): \(e.baseModel.modalityOverride?.rawValue ?? -1) → \(tplModel.modalityOverride?.rawValue ?? -1)")
                config.modelEntries[i] = ModelEntry(
                    uuid: e.uuid,
                    providerInstanceId: e.providerInstanceId,
                    model: tplModel,
                    overrides: e.overrides,
                    isCustom: e.isCustom,
                    isHidden: e.isHidden,
                    userModifiedAt: e.userModifiedAt
                )
                changed = true
            }
        }
        if changed { save() }
    }

    func updateInstance(_ instance: ProviderInstance) {
        guard let idx = config.instances.firstIndex(where: { $0.id == instance.id }) else { return }
        // If the user changed the base URL or v1 suffix, the previously probed image
        // endpoint may no longer be valid (different upstream). Drop the cached
        // resolution so the next image-gen request re-probes.
        var updated = instance
        let old = config.instances[idx]
        if old.customBaseURL != updated.customBaseURL || old.appendV1Suffix != updated.appendV1Suffix {
            updated.imageEndpointResolved = nil
        }
        config.instances[idx] = updated
        save()
    }

    /// Persist the probed image-generation endpoint for an instance under `.auto` mode.
    /// Called by `ModelUseOffloadBridge` after a successful image-gen call so subsequent
    /// requests skip the probe and go straight to the working endpoint.
    func setImageEndpointResolved(instanceId: String, endpoint: ImageEndpointMode) {
        guard let idx = config.instances.firstIndex(where: { $0.id == instanceId }) else { return }
        guard config.instances[idx].imageEndpointResolved != endpoint else { return }
        config.instances[idx].imageEndpointResolved = endpoint
        save()
    }

    /// Reorder provider instances. This order drives the display order in
    /// SessionModelPicker and ProviderInstancesView. Unknown ids are dropped,
    /// and any instances missing from newOrder are appended in their existing
    /// relative order to keep state consistent.
    func reorderInstances(_ newOrder: [String]) {
        let existingById = Self.dictByIdLastWins(config.instances.map { ($0.id, $0) })
        var seen = Set<String>()
        var reordered: [ProviderInstance] = []
        for id in newOrder {
            guard !seen.contains(id), let inst = existingById[id] else { continue }
            seen.insert(id)
            reordered.append(inst)
        }
        for inst in config.instances where !seen.contains(inst.id) {
            reordered.append(inst)
        }
        config.instances = reordered
        save()
    }

    func removeInstance(_ instanceId: String) {
        // Collect entry UUIDs to remove from groups before deleting entries
        let removedEntryIds = Set(config.modelEntries.filter { $0.providerInstanceId == instanceId }.map(\.id))
        // Capture groups that go empty as a side effect of this removal so we
        // can tombstone them too — otherwise the other device's snapshot of
        // those groups would resurrect them post-merge with no members.
        let removedGroupIds = Set(config.modelGroups.filter { g in
            // group will be empty after removing the entries we're about to drop
            !g.memberEntryIds.isEmpty &&
            g.memberEntryIds.allSatisfy { removedEntryIds.contains($0) }
        }.map(\.id))
        config.instances.removeAll { $0.id == instanceId }
        // Remove associated model entries
        config.modelEntries.removeAll { $0.providerInstanceId == instanceId }
        // Remove from groups — stamp member-removal tombstones so the removal
        // survives the inbound union-merge on peers.
        let nowTs = Date()
        for i in config.modelGroups.indices {
            let hit = config.modelGroups[i].memberEntryIds.filter { removedEntryIds.contains($0) }
            guard !hit.isEmpty else { continue }
            config.modelGroups[i].memberEntryIds.removeAll { removedEntryIds.contains($0) }
            for e in hit {
                config.modelGroups[i].removedMembers[e] = nowTs
                config.modelGroups[i].addedMembers[e] = nil
            }
        }
        // [T-icloud-provider-sync-consistency] Remove ONLY the groups that
        // became empty as a direct result of THIS instance removal (computed
        // above as removedGroupIds). The old code removed EVERY empty group —
        // which also deleted a user's intentionally-empty group (newly created,
        // or temporarily cleared) and propagated that deletion via tombstone.
        config.modelGroups.removeAll { removedGroupIds.contains($0.id) }
        // Remove from agent loop list
        config.agentLoopModelEntryIds.removeAll { removedEntryIds.contains($0) }
        // Stamp tombstones so iCloud sync can propagate the delete instead
        // of resurrecting these ids from the peer's snapshot.
        Self.recordTombstone(in: &config.deletedInstances, ids: [instanceId])
        Self.recordTombstone(in: &config.deletedModelEntries, ids: Array(removedEntryIds))
        if !removedGroupIds.isEmpty {
            Self.recordTombstone(in: &config.deletedModelGroups, ids: Array(removedGroupIds))
        }
        // Clean up credentials from Keychain
        ProviderKeychainHelper.deleteAPIKey(instanceId: instanceId)
        ProviderKeychainHelper.deleteOAuthToken(instanceId: instanceId)
        ProviderKeychainHelper.deleteOAuthString(instanceId: instanceId, account: "oauth-email")
        ProviderKeychainHelper.deleteOAuthString(instanceId: instanceId, account: "oauth-gcp-project")
        ProviderKeychainHelper.deleteOAuthString(instanceId: instanceId, account: "manual-oauth-token")
        save()
        // [T-icloud-provider-sync-consistency] Explicit V3 delete tombstones —
        // emitV3MarkDirty no longer diff-infers deletions, so the instance, its
        // cascaded entries, and any groups emptied by the removal must each
        // emit their own delete record here.
        let entryIds = removedEntryIds
        let groupIds = removedGroupIds
        Task {
            await ChatStore.shared.markDirty(recordType: "ProviderInstanceV3", recordId: instanceId, operation: "delete")
            for eid in entryIds {
                await ChatStore.shared.markDirty(recordType: "ProviderModelEntryV3", recordId: eid, operation: "delete")
            }
            for gid in groupIds {
                await ChatStore.shared.markDirty(recordType: "ProviderModelGroupV3", recordId: gid, operation: "delete")
            }
        }
    }

    func instance(for id: String) -> ProviderInstance? {
        // The built-in System engine is a synthetic, local-only instance (never in
        // config.instances → never synced). Return it for the sentinel id and for
        // any System composite id ("<sentinel>/<voice|asr>") so the factory /
        // resolver / picker can treat it like a real provider instance.
        if id == SystemVoiceProvider.builtinProviderId
            || id.hasPrefix(SystemVoiceProvider.builtinProviderId + "/") {
            return SystemVoiceProvider.providerInstance
        }
        return config.instances.first { $0.id == id }
    }

    func enabledInstances(for providerType: ProviderType) -> [ProviderInstance] {
        config.instances.filter { $0.providerType == providerType && $0.isEnabled }
    }

    /// Export an instance as shareable JSON (includes API key if present).
    func exportInstanceJSON(_ instanceId: String) -> String? {
        guard let instance = instance(for: instanceId) else { return nil }
        let entries = entries(for: instanceId)
        var dict: [String: Any] = [
            "providerType": instance.providerType.rawValue,
            "label": instance.label,
            "credentialType": instance.credentialType.rawValue,
            "models": entries.map { entry -> [String: Any] in
                // Export baseModel (API-reported values) for all metadata fields,
                // and user overrides in a separate "overrides" object so the importing
                // device preserves both layers and survives future API refreshes correctly.
                let base = entry.baseModel
                var m: [String: Any] = [
                    "modelId": base.id,
                    "displayName": base.displayName,
                    "isHidden": entry.isHidden,
                ]
                if entry.isCustom { m["isCustom"] = true }
                if let modality = base.modalityOverride {
                    m["modalityOverride"] = modality.rawValue
                }
                if let ctx = base.contextWindow {
                    m["contextWindow"] = ctx
                }
                if let maxOut = base.maxOutputTokens {
                    m["maxOutputTokens"] = maxOut
                }
                if let reasoning = base.supportsReasoning {
                    m["supportsReasoning"] = reasoning
                }
                if let field = base.interleavedReasoningField {
                    m["interleavedReasoningField"] = field
                }
                if !entry.overrides.isEmpty {
                    // [T-provider-export-model-overrides] Serialize the FULL
                    // ModelOverrides layer (the user's manual edits), not just
                    // displayName/maxOutputTokens. modalityOverride, contextWindow
                    // and supportsReasoning were previously dropped on export, so a
                    // hand-corrected proxied model lost those edits on round-trip.
                    // Each key is additive + optional: older builds ignore unknown
                    // keys, and import below reads each independently so a partial
                    // override object restores exactly the fields present.
                    var o: [String: Any] = [:]
                    if let dn = entry.overrides.displayName { o["displayName"] = dn }
                    if let mt = entry.overrides.maxOutputTokens { o["maxOutputTokens"] = mt }
                    if let mod = entry.overrides.modalityOverride { o["modalityOverride"] = mod.rawValue }
                    if let ctx = entry.overrides.contextWindow { o["contextWindow"] = ctx }
                    if let sr = entry.overrides.supportsReasoning { o["supportsReasoning"] = sr }
                    m["overrides"] = o
                }
                return m
            },
        ]
        if let key = ProviderKeychainHelper.loadAPIKey(instanceId: instanceId) {
            dict["apiKey"] = Data(key.utf8).base64EncodedString()
        }
        if let manualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instanceId, account: "manual-oauth-token") {
            dict["manualOAuthToken"] = Data(manualToken.utf8).base64EncodedString()
        }
        // [T-ios-provider-export-oauth-token] Export the structured OAuth-login
        // credential (access + refresh + expiry) saved by the OAuth flow via
        // ProviderKeychainHelper.saveOAuthToken<T:Codable>. This is a DIFFERENT
        // keychain account ("oauth-token") than apiKey / manual-oauth-token, and
        // was previously omitted — so an OAuth-logged-in Claude/OpenAI/Gemini/xAI
        // provider exported with an empty credential and imported as
        // hasCredential=false. We JSON-encode the typed token blob and base64 it
        // (matching the apiKey / manualOAuthToken encoding) under "oauthToken".
        let oauthTokenBlob: Data? = {
            switch instance.providerType {
            case .anthropic:
                return ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: ClaudeTokenStorage.self).flatMap { try? JSONEncoder().encode($0) }
            case .openAI:
                return ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: CodexTokenStorage.self).flatMap { try? JSONEncoder().encode($0) }
            case .gemini:
                return ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: GeminiTokenStorage.self).flatMap { try? JSONEncoder().encode($0) }
            case .xAI:
                return ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: XAITokenStorage.self).flatMap { try? JSONEncoder().encode($0) }
            case .kimiCode:
                return ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: KimiTokenStorage.self).flatMap { try? JSONEncoder().encode($0) }
            default:
                return nil
            }
        }()
        if let blob = oauthTokenBlob {
            dict["oauthToken"] = blob.base64EncodedString()
        }
        // Gemini also stores the resolved account email + GCP project as separate
        // OAuth strings; carry them so the imported instance can call the API.
        if instance.providerType == .gemini {
            if let email = ProviderKeychainHelper.loadOAuthString(instanceId: instanceId, account: "oauth-email") {
                dict["oauthEmail"] = Data(email.utf8).base64EncodedString()
            }
            if let project = ProviderKeychainHelper.loadOAuthString(instanceId: instanceId, account: "oauth-gcp-project") {
                dict["oauthGcpProject"] = Data(project.utf8).base64EncodedString()
            }
        }
        if let url = instance.customBaseURL {
            dict["customBaseURL"] = url
        }
        if !instance.appendV1Suffix {
            dict["appendV1Suffix"] = false
        }
        // [T-provider-custom-user-agent] Additive + optional: only emitted when set.
        // Old builds ignore the key; a new build reading an old export leaves it nil
        // (default UA). No credential exposure — UA is not secret.
        if let ua = instance.effectiveCustomUserAgent {
            dict["customUserAgent"] = ua
        }
        guard let data = try? JSONSerialization.data(withJSONObject: dict, options: [.prettyPrinted, .sortedKeys]),
              let json = String(data: data, encoding: .utf8) else { return nil }
        return json
    }

    /// Import a provider from exported JSON. Returns the new instance label on success.
    /// - Auto-renames on label conflict (appends " (2)", " (3)", etc.)
    /// - Decodes base64-encoded API key and saves to Keychain
    /// - Also supports plain-text API key for backward compatibility
    @discardableResult
    func importInstanceJSON(_ json: String) -> String? {
        guard let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let providerTypeRaw = dict["providerType"] as? String,
              let providerType = ProviderType(rawValue: providerTypeRaw),
              let label = dict["label"] as? String else {
            return nil
        }

        let credentialType: ProviderCredential
        if let raw = dict["credentialType"] as? String, let ct = ProviderCredential(rawValue: raw) {
            credentialType = ct
        } else {
            credentialType = .apiKey
        }

        // Resolve label conflict
        let existingLabels = Set(config.instances.map(\.label))
        var resolvedLabel = label
        if existingLabels.contains(resolvedLabel) {
            var suffix = 2
            while existingLabels.contains("\(label) (\(suffix))") { suffix += 1 }
            resolvedLabel = "\(label) (\(suffix))"
        }

        // Create instance
        let customBaseURL = dict["customBaseURL"] as? String
        let appendV1 = dict["appendV1Suffix"] as? Bool ?? true
        // [T-provider-custom-user-agent] Optional; absent in old exports → nil (default UA).
        let customUserAgent = dict["customUserAgent"] as? String
        let instance = ProviderInstance(
            label: resolvedLabel,
            providerType: providerType,
            credentialType: credentialType,
            customBaseURL: customBaseURL,
            appendV1Suffix: appendV1,
            customUserAgent: customUserAgent
        )

        // Save credentials to Keychain BEFORE addInstance(), so that
        // addInstance() can detect hasManualToken and skip built-in model population.

        // Decode API key (base64 or plain text for backward compat)
        if let keyValue = dict["apiKey"] as? String, !keyValue.isEmpty {
            let apiKey: String
            if let decoded = Data(base64Encoded: keyValue), let str = String(data: decoded, encoding: .utf8) {
                apiKey = str
            } else {
                apiKey = keyValue // plain text fallback
            }
            ProviderKeychainHelper.saveAPIKey(apiKey, instanceId: instance.id)
        }

        // Decode manual OAuth token (base64 or plain text for backward compat)
        if let tokenValue = dict["manualOAuthToken"] as? String, !tokenValue.isEmpty {
            let token: String
            if let decoded = Data(base64Encoded: tokenValue), let str = String(data: decoded, encoding: .utf8) {
                token = str
            } else {
                token = tokenValue
            }
            ProviderKeychainHelper.saveOAuthString(token, instanceId: instance.id, account: "manual-oauth-token")
        }

        // [T-ios-provider-export-oauth-token] Restore the structured OAuth-login
        // credential. Decode base64 → JSON → the provider-specific Codable token,
        // then saveOAuthToken back to the keychain so the imported instance is
        // authenticated. saveOAuthToken already calls notifyAuthChanged()
        // (bumps authRevision), so the auth UI refreshes without extra work.
        if let oauthB64 = dict["oauthToken"] as? String, !oauthB64.isEmpty,
           let blob = Data(base64Encoded: oauthB64) {
            switch providerType {
            case .anthropic:
                if let t = (try? JSONDecoder().decode(ClaudeTokenStorage.self, from: blob))
                    ?? Self.decodeClaudeTokenFromRawOAuth(blob) {
                    ProviderKeychainHelper.saveOAuthToken(t, instanceId: instance.id)
                }
            case .openAI:
                if let t = (try? JSONDecoder().decode(CodexTokenStorage.self, from: blob))
                    ?? Self.decodeCodexTokenFromRawOAuth(blob) {
                    ProviderKeychainHelper.saveOAuthToken(t, instanceId: instance.id)
                }
            case .gemini:
                if let t = (try? JSONDecoder().decode(GeminiTokenStorage.self, from: blob))
                    ?? Self.decodeGeminiTokenFromRawOAuth(blob) {
                    ProviderKeychainHelper.saveOAuthToken(t, instanceId: instance.id)
                }
            case .xAI:
                if let t = (try? JSONDecoder().decode(XAITokenStorage.self, from: blob))
                    ?? Self.decodeXAITokenFromRawOAuth(blob) {
                    ProviderKeychainHelper.saveOAuthToken(t, instanceId: instance.id)
                }
            case .kimiCode:
                if let t = try? JSONDecoder().decode(KimiTokenStorage.self, from: blob) {
                    ProviderKeychainHelper.saveOAuthToken(t, instanceId: instance.id)
                }
            default:
                break
            }
        }
        // Gemini account email + GCP project (base64-encoded OAuth strings).
        if providerType == .gemini {
            if let b64 = dict["oauthEmail"] as? String, let d = Data(base64Encoded: b64),
               let email = String(data: d, encoding: .utf8) {
                ProviderKeychainHelper.saveOAuthString(email, instanceId: instance.id, account: "oauth-email")
            }
            if let b64 = dict["oauthGcpProject"] as? String, let d = Data(base64Encoded: b64),
               let project = String(data: d, encoding: .utf8) {
                ProviderKeychainHelper.saveOAuthString(project, instanceId: instance.id, account: "oauth-gcp-project")
            }
        }

        // Append instance directly (skip addInstance to avoid built-in model
        // population and async refreshModels — we already have models from the JSON).
        config.instances.append(instance)
        save()

        // Import models
        if let models = dict["models"] as? [[String: Any]] {
            for m in models {
                guard let modelId = m["modelId"] as? String else { continue }
                let displayName = m["displayName"] as? String ?? modelDisplayName(from: modelId)
                let isCustom = m["isCustom"] as? Bool ?? false
                let isHidden = m["isHidden"] as? Bool ?? false
                let modalityOverride = (m["modalityOverride"] as? Int).map { ModelModality(rawValue: $0) }
                let contextWindow = m["contextWindow"] as? Int
                let maxOutputTokens = m["maxOutputTokens"] as? Int
                let supportsReasoning = m["supportsReasoning"] as? Bool
                let interleavedReasoningField = m["interleavedReasoningField"] as? String
                var model = LLMModel(
                    id: modelId, displayName: displayName, provider: providerType.displayName,
                    modalityOverride: modalityOverride,
                    contextWindow: contextWindow,
                    maxOutputTokens: maxOutputTokens,
                    supportsReasoning: supportsReasoning,
                    interleavedReasoningField: interleavedReasoningField
                )
                // Only run inference if no attributes were provided in the export
                if modalityOverride == nil && contextWindow == nil && maxOutputTokens == nil && supportsReasoning == nil {
                    model = model.withInferredModality()
                }
                // Ensure modalityOverride is never nil after import — a nil
                // falls through to knownCapabilities["OpenAI"] = .vision,
                // which includes .imageInput even for text-only models.
                // Mirrors OpenAIModelsAPI.swift line 139 (always-write).
                if model.modalityOverride == nil {
                    model = LLMModel(id: model.id, displayName: model.displayName,
                                     provider: model.provider,
                                     modalityOverride: [.textInput, .textOutput],
                                     contextWindow: model.contextWindow,
                                     maxOutputTokens: model.maxOutputTokens,
                                     supportsReasoning: model.supportsReasoning,
                                     interleavedReasoningField: model.interleavedReasoningField)
                }
                var overrides = ModelOverrides()
                if let o = m["overrides"] as? [String: Any] {
                    // [T-provider-export-model-overrides] Restore the full
                    // overrides layer. Each key is read independently with `as?`
                    // so a missing key (old export, or a partial override object)
                    // simply stays nil → the field falls back to the baseModel /
                    // inferred default. modalityOverride is an OptionSet stored as
                    // its Int rawValue (same encoding as the baseModel modality).
                    overrides.displayName = o["displayName"] as? String
                    overrides.maxOutputTokens = o["maxOutputTokens"] as? Int
                    overrides.modalityOverride = (o["modalityOverride"] as? Int).map { ModelModality(rawValue: $0) }
                    overrides.contextWindow = o["contextWindow"] as? Int
                    overrides.supportsReasoning = o["supportsReasoning"] as? Bool
                }
                let entry = ModelEntry(
                    providerInstanceId: instance.id,
                    model: model,
                    overrides: overrides,
                    isCustom: isCustom,
                    isHidden: isHidden
                )
                addEntry(entry)
            }
        }

        let importedModelCount = config.modelEntries.filter { $0.providerInstanceId == instance.id }.count
        logger.info("Imported provider '\(resolvedLabel)' (\(providerType.rawValue)) with \(importedModelCount) models")
        // If the imported JSON had no `models` array (older exports / a
        // bare-bones template), the new instance would be left with an
        // empty model list — exactly the "empty list" symptom the user
        // reported. Kick off the same refreshModels path addInstance()
        // uses so the new provider ends up with a populated picker.
        if importedModelCount == 0 {
            Task { await refreshModels(for: instance) }
        }
        return resolvedLabel
    }

    // MARK: - Model Entries

    // MARK: - Entry access (stable sort)
    //
    // The storage order of `config.modelEntries` is an implementation detail —
    // it drifts as a side-effect of `replaceEntries` (which removes all entries
    // for an instance and re-appends them at the end), concurrent auto-refresh
    // finishing in non-deterministic order, and iCloud merge insertions. None
    // of that should leak into the UI.
    //
    // All UI-facing reads go through these three getters, which apply a stable
    // sort: by `instance.createdAt` (so entries of the same provider cluster
    // together in a device-consistent order), then by `baseModel.id`
    // (alphabetic within each provider). This gives the user a predictable
    // layout that survives refreshes and syncs unchanged.
    var modelEntries: [ModelEntry] {
        sortedEntries(config.modelEntries)
    }

    /// All entries for one instance (hidden included), newest/most capable
    /// first. [T-model-release-ranking] Shares `visibleEntries`' ordering so the
    /// provider-detail list and the pickers can't disagree about which model is
    /// "first" — see that method for why alphabetical was actively harmful.
    func entries(for instanceId: String) -> [ModelEntry] {
        config.modelEntries
            .filter { $0.providerInstanceId == instanceId }
            .sorted(by: Self.releaseRankOrder)
    }

    /// Entries a picker should show for one instance, newest/most capable first.
    ///
    /// [T-model-release-ranking] Deliberately NOT alphabetical. Sorting by
    /// `baseModel.id` puts `gpt-5` above `gpt-5.6-sol` — i.e. it surfaces the
    /// oldest model first, and in the Codex OAuth case the top of the list was
    /// a model the account cannot even call (OpenMinis#83: the 400 renders as an
    /// empty reply, so a new user reads it as "the app is broken"). Ranking by
    /// release date, then output price, then context window puts the six
    /// callable Codex models in the top six with no hand-maintained order.
    /// Undated models keep their alphabetical order and sit at the end — they
    /// are custom/local/relay entries, so they must stay visible.
    func visibleEntries(for instanceId: String) -> [ModelEntry] {
        config.modelEntries
            .filter { $0.providerInstanceId == instanceId && !$0.isHidden }
            .sorted(by: Self.releaseRankOrder)
    }

    /// [T-model-release-ranking] The shared comparator. Falls back to the model
    /// id so the order is total and stable when two entries rank identically
    /// (both undated, same price, same context) — otherwise `sorted` could
    /// reshuffle equal elements between reads and the list would visibly jitter.
    static func releaseRankOrder(_ a: ModelEntry, _ b: ModelEntry) -> Bool {
        let ra = ModelReleaseIndex.rank(modelId: a.baseModel.id,
                                        displayName: a.baseModel.displayName,
                                        contextWindow: a.baseModel.contextWindow)
        let rb = ModelReleaseIndex.rank(modelId: b.baseModel.id,
                                        displayName: b.baseModel.displayName,
                                        contextWindow: b.baseModel.contextWindow)
        if ra < rb { return true }
        if rb < ra { return false }
        return a.baseModel.id < b.baseModel.id
    }

    /// Shared sort used by the flat-list getter. Clusters entries by provider
    /// instance (in `createdAt` order), then alphabetizes by `baseModel.id`
    /// within each cluster. Instances not found in the current config fall to
    /// the end in a stable tail (handles the brief window where an entry exists
    /// but its owning instance was just removed).
    private func sortedEntries(_ entries: [ModelEntry]) -> [ModelEntry] {
        var instanceOrder: [String: Int] = [:]
        for (idx, inst) in config.instances.sorted(by: { $0.createdAt < $1.createdAt }).enumerated() {
            instanceOrder[inst.id] = idx
        }
        let orphanRank = Int.max
        return entries.sorted { a, b in
            let ai = instanceOrder[a.providerInstanceId] ?? orphanRank
            let bi = instanceOrder[b.providerInstanceId] ?? orphanRank
            if ai != bi { return ai < bi }
            return a.baseModel.id < b.baseModel.id
        }
    }

    func entry(for entryId: String) -> ModelEntry? {
        // Built-in System engine members are virtual (synthetic, never stored). They
        // must resolve here so a group with System members (e.g. a [Doubao, System]
        // fallback list) doesn't silently drop System at the entry-lookup guard.
        // Mirrors instance(for:)'s System branch.
        if entryId == SystemVoiceProvider.builtinProviderId
            || entryId.hasPrefix(SystemVoiceProvider.builtinProviderId + "/") {
            return SystemVoiceCatalog.entry(forCompositeId: entryId)
        }
        // [T-provider-entry-composite-key] `id` is now the compositeKey
        // ("{instanceId}/{modelId}"). Resolve by composite key first; then fall
        // back to the legacy random uuid and the old ":" composite key so a
        // reference written by an older build (or synced from a not-yet-migrated
        // peer) still resolves. The legacyUuid normalization map rewrites these
        // to composite keys over time, but this lookup keeps them working in the
        // meantime — never returns nil just because a reference is still in an
        // old form.
        if let hit = config.modelEntries.first(where: { $0.id == entryId }) { return hit }
        if let hit = config.modelEntries.first(where: { $0.uuid == entryId }) { return hit }
        if let mapped = legacyUuidToCompositeKey[entryId],
           let hit = config.modelEntries.first(where: { $0.id == mapped }) { return hit }
        if let hit = config.modelEntries.first(where: { $0.legacyColonCompositeKey == entryId }) { return hit }
        return nil
    }

    /// [T-ios-minis-config-entry-id-composite] Normalize an entry reference in
    /// any historical form (composite key, legacy random uuid, legacy ":"
    /// composite) to the entry's CURRENT id (the composite key). Returns the
    /// input unchanged when nothing resolves — callers validate afterwards, so
    /// a truly-unknown reference still fails their existence check.
    func normalizeEntryRef(_ ref: String) -> String {
        entry(for: ref)?.id ?? ref
    }

    /// Add a model entry, deduplicating by providerInstanceId + baseModel.id across all entries.
    /// Returns `false` if a duplicate already exists.
    @discardableResult
    func addEntry(_ entry: ModelEntry) -> Bool {
        guard !config.modelEntries.contains(where: {
            $0.providerInstanceId == entry.providerInstanceId && $0.baseModel.id == entry.baseModel.id
        }) else { return false }
        let inferredModel = entry.baseModel.withInferredModality()
        // Stamp userModifiedAt — addEntry is only called when the user explicitly
        // creates a custom entry (UI "Add Model" flow), so the entry carries user intent.
        // [T-provider-entry-composite-key] uuid param wants the random uuid,
        // not entry.id (which is now the composite key).
        let e = ModelEntry(uuid: entry.uuid, providerInstanceId: entry.providerInstanceId,
                           model: inferredModel, overrides: entry.overrides,
                           isCustom: entry.isCustom, isHidden: entry.isHidden,
                           userModifiedAt: Date())
        config.modelEntries.append(e)
        save()
        return true
    }

    func updateEntry(_ entry: ModelEntry) {
        guard let idx = config.modelEntries.firstIndex(where: { $0.id == entry.id }) else { return }
        // Stamp userModifiedAt on every UI-driven edit so iCloud merge can resolve
        // same-field conflicts by last-write-wins. This is the single funnel for
        // override edits from ProviderInstanceDetailView.
        var stamped = entry
        stamped.userModifiedAt = Date()
        config.modelEntries[idx] = stamped
        save()
    }

    func removeEntry(_ entryId: String) {
        config.modelEntries.removeAll { $0.id == entryId }
        // Also remove from groups — stamp a member-removal tombstone on each
        // group so the removal survives the inbound union-merge on peers.
        let now = Date()
        for i in config.modelGroups.indices where config.modelGroups[i].memberEntryIds.contains(entryId) {
            config.modelGroups[i].memberEntryIds.removeAll { $0 == entryId }
            config.modelGroups[i].removedMembers[entryId] = now
            config.modelGroups[i].addedMembers[entryId] = nil
        }
        // Remove from agent loop list
        config.agentLoopModelEntryIds.removeAll { $0 == entryId }
        Self.recordTombstone(in: &config.deletedModelEntries, ids: [entryId])
        save()
        // [T-icloud-provider-sync-consistency] emitV3MarkDirty no longer
        // infers deletes from the snapshot diff, so an explicit removal must
        // emit its own V3 delete tombstone here.
        Task { await ChatStore.shared.markDirty(recordType: "ProviderModelEntryV3", recordId: entryId, operation: "delete") }
    }

    /// Replace model entries for an instance with fresh ones (e.g. after API fetch).
    /// Reuses existing entry UUIDs by model ID so that group memberEntryIds remain valid.
    /// Preserves user state across refreshes:
    ///   - `overrides` (user edits like displayName / maxOutputTokens) carries forward
    ///   - `isHidden` carries forward
    /// When a built-in model matches a previously custom entry, the custom entry's UUID and
    /// overrides are preserved and the entry is converted to non-custom (so the user's
    /// selected model keeps working after refresh).
    func replaceEntries(for instanceId: String, models rawModels: [LLMModel], caller: String = #function) {
        // [T-icloud-provider-sync-consistency] Deduplicate the incoming model
        // list by id BEFORE building entries. Some provider /v1/models
        // endpoints (notably the OpenAI-compatible OAuth path) can return the
        // same model id multiple times; without this guard each duplicate
        // became its own entry row (one model showing up 2-3× in the picker).
        // Keep first occurrence, preserve order.
        var seenModelIds = Set<String>()
        let models = rawModels.filter { seenModelIds.insert($0.id).inserted }
        if models.count != rawModels.count {
            logger.warning("[ModelList] replaceEntries caller=\(caller) instance=\(instanceId.prefix(8)) — provider returned \(rawModels.count - models.count) duplicate model id(s); deduped to \(models.count)")
        }
        let existing = config.modelEntries.filter { $0.providerInstanceId == instanceId }
        // Capture UUIDs that existed before the refresh. Any that are absent afterward
        // must be pruned from groups / agent-loop list so stale references don't linger.
        let existingEntryIds = Set(existing.map(\.id))
        let beforeIds = existing.map { $0.baseModel.id }.sorted()
        let afterIds = models.map { $0.id }.sorted()
        let beforeSet = Set(beforeIds)
        let afterSet = Set(afterIds)
        let added = afterSet.subtracting(beforeSet).sorted()
        let removed = beforeSet.subtracting(afterSet).sorted()
        let kept = beforeSet.intersection(afterSet).count
        let instanceLabel = config.instances.first(where: { $0.id == instanceId })?.label ?? "?"
        logger.info("[ModelList] replaceEntries caller=\(caller) instance=\(instanceLabel)(\(instanceId.prefix(8))) before=\(beforeIds.count) after=\(afterIds.count) kept=\(kept) added=\(added.count)[\(added.prefix(10).joined(separator: ","))] removed=\(removed.count)[\(removed.prefix(10).joined(separator: ","))]")

        // Build a single lookup: baseModel.id → existing entry (prefer non-custom if both exist).
        // Read baseModel.id here so lookup keys align with API-reported ids, not overridden names.
        var existingByModelId: [String: ModelEntry] = [:]
        for entry in existing {
            if let prev = existingByModelId[entry.baseModel.id] {
                // Prefer non-custom so its UUID is reused over a custom duplicate
                if entry.isCustom && !prev.isCustom { continue }
            }
            existingByModelId[entry.baseModel.id] = entry
        }

        config.modelEntries.removeAll { $0.providerInstanceId == instanceId }

        let refreshedModelIds = Set(models.map(\.id))
        // Template-sourced voice models have authoritative modalityOverride that
        // must never be overwritten by API-inferred modality (which typically lacks
        // audio bits). Build a lookup: model.id → template's modalityOverride.
        let templateModalityById: [String: ModelModality] = {
            guard let tpl = VoiceProviderTemplate.template(forBaseURL: config.instances.first(where: { $0.id == instanceId })?.effectiveCustomBaseURL) else { return [:] }
            var dict: [String: ModelModality] = [:]
            for m in tpl.mockModels {
                if let mod = m.modalityOverride, mod.contains(.audioInput) || mod.contains(.audioOutput) {
                    dict[m.id] = mod
                }
            }
            return dict
        }()
        let newEntries = models.map { model -> ModelEntry in
            let prior = existingByModelId[model.id]
            var resolved = model.withInferredModality()
            if let templateModality = templateModalityById[model.id] {
                resolved = resolved.withModalityOverride(templateModality)
            }
            return ModelEntry(
                uuid: prior?.uuid ?? UUID().uuidString,  // [composite-key] keep random uuid, not id(=compositeKey)
                providerInstanceId: instanceId,
                model: resolved,
                overrides: prior?.overrides ?? ModelOverrides(),
                isCustom: false,
                isHidden: prior?.isHidden ?? false,
                userModifiedAt: prior?.userModifiedAt
            )
        }
        config.modelEntries.append(contentsOf: newEntries)

        // Keep custom entries whose model ID was NOT covered by the refreshed list — enrich them too
        let remainingCustom = existing.filter { $0.isCustom && !refreshedModelIds.contains($0.baseModel.id) }
        let enrichedCustom = remainingCustom.map { entry in
            ModelEntry(
                uuid: entry.uuid,
                providerInstanceId: entry.providerInstanceId,
                model: entry.baseModel.withInferredModality(),
                overrides: entry.overrides,
                isCustom: true,
                isHidden: entry.isHidden,
                userModifiedAt: entry.userModifiedAt
            )
        }
        config.modelEntries.append(contentsOf: enrichedCustom)
        if !enrichedCustom.isEmpty {
            logger.info("[ModelList] replaceEntries caller=\(caller) instance=\(instanceLabel)(\(instanceId.prefix(8))) preserved \(enrichedCustom.count) custom entries: [\(enrichedCustom.map { $0.baseModel.id }.prefix(10).joined(separator: ","))]")
        }

        // [T-mimo-shadow-voice] Preserve AUDIO-modality template seed entries that
        // the real /models list didn't return. Vendors like MiMo ride their ASR/TTS
        // on /v1/chat/completions and never expose those models via /v1/models, so
        // the voice seed entries (from VoiceProviderTemplate.mockModels) aren't in
        // `refreshedModelIds` — without this they'd be wiped on every refresh (the
        // dual-purpose DashScope bug), breaking the Voice Services shadow view. Keep
        // any non-custom (custom already handled above) existing entry that is (a)
        // absent from the refreshed list, (b) audio-modality, and (c) a member of
        // this instance's voice template mockModels — so we only protect genuine
        // template seeds, not stray audio entries.
        if let tpl = VoiceProviderTemplate.template(forBaseURL: config.instances.first(where: { $0.id == instanceId })?.effectiveCustomBaseURL) {
            let templateVoiceIds = Set(tpl.mockModels
                .filter { ($0.modalityOverride ?? []).contains(.audioInput) || ($0.modalityOverride ?? []).contains(.audioOutput) }
                .map { $0.id })
            let preservedVoice = existing.filter { e in
                !e.isCustom
                && !refreshedModelIds.contains(e.baseModel.id)
                && templateVoiceIds.contains(e.baseModel.id)
            }
            if !preservedVoice.isEmpty {
                config.modelEntries.append(contentsOf: preservedVoice)
                logger.info("[ModelList] replaceEntries caller=\(caller) instance=\(instanceLabel)(\(instanceId.prefix(8))) preserved \(preservedVoice.count) voice-template seed entries: [\(preservedVoice.map { $0.baseModel.id }.prefix(10).joined(separator: ","))]")
            }
        }

        // Prune dangling references: any entry UUID that was for this instance before the
        // refresh but is no longer present (because the provider dropped the model from its
        // list and it wasn't a custom entry) is a candidate for removal from groups and the
        // agent-loop list.
        //
        // SAFETY GUARD against API degradation (e.g. provider returns a temporarily shrunk
        // list during an outage): if the new list is less than half the size of the old one
        // AND the old list had at least 4 models, we skip the group/agent-loop prune. The
        // now-stale entries are still removed from `modelEntries` (API is authoritative for
        // what the provider exposes), so the router won't try to use them; but group and
        // agent-loop references are left dangling so the UI can surface them as
        // `staleMemberRow` and the user can decide. If the next refresh confirms the drop,
        // the references remain visible until the user clears them or the model returns.
        //
        // Why this is safe with iCloud sync:
        //   - `replaceEntries` is a local-only side effect of a device's own refresh; iCloud
        //     merge never calls into it. So the skip decision is per-device and the guard
        //     can't create cross-device reverberation.
        //   - `memberEntryIds` IS synced. When device A prunes on a healthy refresh, device B
        //     will also prune on its next healthy refresh (both APIs agree) — they converge
        //     without needing to coordinate. When device A skips-prune on a degraded refresh,
        //     it uploads unchanged memberEntryIds; device B also uploads unchanged on its own
        //     skip — no flip-flop. The merge's union-of-members semantics means a member
        //     removal only fully propagates when both devices have done a healthy refresh and
        //     pruned the same UUIDs in their own local state, which is exactly the convergent
        //     outcome we want.
        //   - We never "un-prune" a reference we already removed: skip happens before removal,
        //     not after. So a genuine local removal, once uploaded, is final.
        let survivingEntryIds = Set(config.modelEntries.map(\.id))
        let prunedEntryIds = existingEntryIds.subtracting(survivingEntryIds)
        let suspiciousShrink = existing.count >= 4 && models.count * 2 < existing.count
        if !prunedEntryIds.isEmpty {
            if suspiciousShrink {
                logger.warning("[ModelList] replaceEntries caller=\(caller) instance=\(instanceLabel)(\(instanceId.prefix(8))) SUSPICIOUS SHRINK before=\(existing.count) after=\(models.count) — stale entries removed from modelEntries but group/agent-loop references PRESERVED as stale (user will see them as unavailable)")
            } else {
                var affectedGroups: [String] = []
                for i in config.modelGroups.indices {
                    let before = config.modelGroups[i].memberEntryIds.count
                    config.modelGroups[i].memberEntryIds.removeAll { prunedEntryIds.contains($0) }
                    if config.modelGroups[i].memberEntryIds.count != before {
                        affectedGroups.append(config.modelGroups[i].name)
                    }
                }
                config.agentLoopModelEntryIds.removeAll { prunedEntryIds.contains($0) }
                logger.info("[ModelList] replaceEntries caller=\(caller) instance=\(instanceLabel)(\(instanceId.prefix(8))) pruned \(prunedEntryIds.count) stale entries from groups=[\(affectedGroups.joined(separator: ","))]")
            }
        }

        save()
    }

    // MARK: - Model Groups

    var modelGroups: [ModelGroup] { config.modelGroups }

    func addGroup(_ group: ModelGroup) {
        config.modelGroups.append(group)
        save()
    }

    func updateGroup(_ group: ModelGroup) {
        guard let idx = config.modelGroups.firstIndex(where: { $0.id == group.id }) else { return }
        // [T-icloud-provider-sync-consistency] Stamp per-member add/remove
        // timestamps by diffing the prior member list against the new one, so
        // the inbound union-merge on other devices can arbitrate concurrent
        // edits. A member newly present → addedMembers[m]=now (and clear any
        // stale removed tombstone). A member newly absent → removedMembers[m]=now
        // (and clear its added entry). Carries forward existing timestamps for
        // unchanged members.
        let prior = config.modelGroups[idx]
        var stamped = group
        let now = Date()
        let priorSet = Set(prior.memberEntryIds)
        let newSet = Set(group.memberEntryIds)
        var added = prior.addedMembers
        var removed = prior.removedMembers
        // Carry forward caller-supplied maps if it set any (UI usually doesn't).
        for (k, v) in group.addedMembers { added[k] = v }
        for (k, v) in group.removedMembers { removed[k] = v }
        for m in newSet where !priorSet.contains(m) {   // newly added
            added[m] = now
            removed[m] = nil
        }
        for m in priorSet where !newSet.contains(m) {   // newly removed
            removed[m] = now
            added[m] = nil
        }
        // Prune maps to relevant ids (present → added only; absent → removed only).
        stamped.addedMembers = added.filter { newSet.contains($0.key) }
        stamped.removedMembers = removed.filter { !newSet.contains($0.key) }
        config.modelGroups[idx] = stamped
        save()
    }

    /// Reorder model groups. This order drives the display order in the
    /// Model Groups list and SessionModelPicker. Unknown ids are dropped,
    /// and any groups missing from newOrder are appended in their existing
    /// relative order to keep state consistent.
    func reorderGroups(_ newOrder: [String]) {
        let existingById = Self.dictByIdLastWins(config.modelGroups.map { ($0.id, $0) })
        var seen = Set<String>()
        var reordered: [ModelGroup] = []
        for id in newOrder {
            guard !seen.contains(id), let group = existingById[id] else { continue }
            seen.insert(id)
            reordered.append(group)
        }
        for group in config.modelGroups where !seen.contains(group.id) {
            reordered.append(group)
        }
        config.modelGroups = reordered
        save()
    }

    func removeGroup(_ groupId: String) {
        config.modelGroups.removeAll { $0.id == groupId }
        if config.defaultPrimaryGroupId == groupId { config.defaultPrimaryGroupId = nil }
        if config.defaultSubGroupId == groupId { config.defaultSubGroupId = nil }
        // [T-ios-vision-group #182] Clear the vision pointer too, so deleting the
        // group turns the feature off cleanly instead of leaving a dangling id
        // that would keep `read_image` exposed to a non-vision model with no
        // describer behind it. (The resolver also guards, but the tool-exposure
        // gate reads the pointer directly.)
        if config.visionGroupId == groupId { config.visionGroupId = nil }
        config.agentLoopGroupIds.removeAll { $0 == groupId }
        Self.recordTombstone(in: &config.deletedModelGroups, ids: [groupId])
        save()
        // [T-icloud-provider-sync-consistency] Explicit V3 delete tombstone —
        // emitV3MarkDirty no longer diff-infers group deletions.
        Task { await ChatStore.shared.markDirty(recordType: "ProviderModelGroupV3", recordId: groupId, operation: "delete") }
    }

    func group(for id: String) -> ModelGroup? {
        config.modelGroups.first { $0.id == id }
    }

    // MARK: - Agent Loop Models

    var agentLoopModelEntryIds: [String] {
        get { config.agentLoopModelEntryIds }
        set {
            config.agentLoopModelEntryIds = newValue
            save()
        }
    }

    var agentLoopGroupIds: [String] {
        get { config.agentLoopGroupIds }
        set {
            config.agentLoopGroupIds = newValue
            save()
        }
    }

    func addAgentLoopEntry(_ entryId: String) {
        guard !config.agentLoopModelEntryIds.contains(entryId) else { return }
        config.agentLoopModelEntryIds.append(entryId)
        save()
    }

    func removeAgentLoopEntry(_ entryId: String) {
        config.agentLoopModelEntryIds.removeAll { $0 == entryId }
        save()
    }

    func addAgentLoopGroup(_ groupId: String) {
        guard !config.agentLoopGroupIds.contains(groupId) else { return }
        config.agentLoopGroupIds.append(groupId)
        save()
    }

    func removeAgentLoopGroup(_ groupId: String) {
        config.agentLoopGroupIds.removeAll { $0 == groupId }
        save()
    }

    // MARK: - Voice group selectors (ASR / TTS)
    //
    // Parallel to Default Primary/Sub: each points at a ModelGroup whose
    // audio-capable members serve voice. Local-only (per-device), not synced.

    var voiceInputGroupId: String? {
        get { config.voiceInputGroupId }
        set { config.voiceInputGroupId = newValue; save() }
    }

    var voiceOutputGroupId: String? {
        get { config.voiceOutputGroupId }
        set { config.voiceOutputGroupId = newValue; save() }
    }

    // MARK: - Vision group selector (image understanding fallback)
    //
    // [T-ios-vision-group #182] Same shape as the voice selectors: a pointer at
    // an ordinary ModelGroup whose image-capable members describe images for a
    // main model that can't see them. Local-only (per-device), not synced.

    var visionGroupId: String? {
        get { config.visionGroupId }
        set { config.visionGroupId = newValue; save() }
    }

    /// Ensure a default Voice INPUT group exists and is bound when the user hasn't
    /// configured one. Called on first entry into voice-input mode. Creates a
    /// "Voice Input" fallback group with the two built-in System ASR models —
    /// Online (cloud, accurate) first, then Offline (on-device, private) — and
    /// binds it. No-op if a group is already set. Members are the System sentinel
    /// composite ids, which resolve via SystemVoiceCatalog (never stored/synced).
    @discardableResult
    func ensureDefaultVoiceInputGroup() -> String? {
        if let gid = config.voiceInputGroupId, group(for: gid) != nil { return gid }
        let sentinel = SystemVoiceProvider.builtinProviderId
        let group = ModelGroup(
            name: AppLocalized("Voice Input", comment: "Default voice input group name"),
            memberEntryIds: ["\(sentinel)/system-asr-online", "\(sentinel)/system-asr-offline"])
        config.modelGroups.append(group)
        config.voiceInputGroupId = group.id
        save()
        logger.info("[Voice] auto-created default Voice Input group \(group.id.prefix(8)) [System ASR online+offline]")
        return group.id
    }

    /// Ensure a default Voice OUTPUT group exists and is bound when the user hasn't
    /// configured one. Creates a "Voice Output" group with "System Voice (Auto)" —
    /// which picks the best installed voice for each reply's language automatically
    /// (a Chinese sentence reads in a Chinese voice, English in an English one). The
    /// user can add/replace with specific voices later. No-op if already set.
    @discardableResult
    func ensureDefaultVoiceOutputGroup() -> String? {
        if let gid = config.voiceOutputGroupId, group(for: gid) != nil { return gid }
        let sentinel = SystemVoiceProvider.builtinProviderId
        let group = ModelGroup(
            name: AppLocalized("Voice Output", comment: "Default voice output group name"),
            memberEntryIds: ["\(sentinel)/system-tts"])
        config.modelGroups.append(group)
        config.voiceOutputGroupId = group.id
        save()
        logger.info("[Voice] auto-created default Voice Output group \(group.id.prefix(8)) [System Voice (Auto)]")
        return group.id
    }

    /// All unique model entries available in agent loop: individual entries + entries from groups.
    var resolvedAgentLoopEntries: [ModelEntry] {
        var seen = Set<String>()
        var result: [ModelEntry] = []
        // Individual entries first
        for id in config.agentLoopModelEntryIds {
            guard !seen.contains(id), let entry = entry(for: id) else { continue }
            seen.insert(id)
            result.append(entry)
        }
        // Then entries from groups
        for groupId in config.agentLoopGroupIds {
            guard let group = group(for: groupId) else { continue }
            for entryId in group.memberEntryIds {
                guard !seen.contains(entryId), let entry = entry(for: entryId) else { continue }
                seen.insert(entryId)
                result.append(entry)
            }
        }
        return result
    }

    // MARK: - Defaults

    var defaultPrimaryGroupId: String? {
        get { config.defaultPrimaryGroupId }
        set {
            config.defaultPrimaryGroupId = newValue
            save()
        }
    }

    var defaultSubGroupId: String? {
        get { config.defaultSubGroupId }
        set {
            config.defaultSubGroupId = newValue
            save()
        }
    }

    // MARK: - Session Bindings

    func binding(for sessionId: String) -> SessionModelBinding? {
        config.sessionBindings[sessionId]
    }

    func setBinding(_ binding: SessionModelBinding, for sessionId: String) {
        config.sessionBindings[sessionId] = binding
        save()
    }

    func removeBinding(for sessionId: String) {
        config.sessionBindings.removeValue(forKey: sessionId)
        save()
    }

    // MARK: - Session Inference Config

    func inferenceConfig(for sessionId: String) -> SessionInferenceConfig? {
        config.sessionInferenceConfigs[sessionId]
    }

    func setInferenceConfig(_ cfg: SessionInferenceConfig, for sessionId: String) {
        config.sessionInferenceConfigs[sessionId] = cfg
        save()
    }

    func removeInferenceConfig(for sessionId: String) {
        config.sessionInferenceConfigs.removeValue(forKey: sessionId)
        save()
    }

    // MARK: - Bulk Update (for migration)

    func applyConfig(_ newConfig: ProviderConfig) {
        config = newConfig
        save()
    }

    /// Apply a merged config from iCloud sync without triggering a markDirty upload.
    /// Used by `CloudSyncEngine.mergeProviderConfig` — it replaces the in-memory config,
    /// writes it to disk, and publishes the change, but does NOT schedule a re-upload.
    /// The caller decides separately whether the merge produced data that needs to be
    /// pushed back to iCloud (via the existing "localHasUnique" re-upload path).
    // [T-provider-sync-apply-interaction-defer] Deferral plumbing for
    // applyMergedConfigFromSync — see the gate comment inside it.
    private var pendingSyncApply: ProviderConfig?
    private var syncApplyRetryTimer: Timer?
    var syncApplyForegroundObserver: NSObjectProtocol?
    private var lastDidBecomeActiveAt: Date = .distantPast
    private static let postForegroundSettleSeconds: TimeInterval = 1.5

    private func shouldDeferSyncApply() -> Bool {
        // Backgrounded: apply immediately as before. Rendering is suspended
        // (no async-render race), and a long-running background agent must
        // keep reading fresh provider config for routing.
        guard UIApplication.shared.applicationState == .active else { return false }
        if Date().timeIntervalSince(lastDidBecomeActiveAt) < Self.postForegroundSettleSeconds {
            return true
        }
        // Any UIKit scroll drag/deceleration puts the main runloop into
        // tracking mode — a global signal that covers every scrollable
        // surface, including the provider instance and model picker lists.
        if RunLoop.main.currentMode == .tracking { return true }
        return false
    }

    private func armSyncApplyRetry() {
        guard syncApplyRetryTimer == nil else { return }
        logger.info("[v3sync] applyMergedConfigFromSync DEFERRED (settleWindow=\(Date().timeIntervalSince(self.lastDidBecomeActiveAt) < Self.postForegroundSettleSeconds) tracking=\(RunLoop.main.currentMode == .tracking)) — will retry on quiet")
        // .default runloop mode only: while a scroll keeps the runloop in
        // tracking mode the timer stays parked, and it fires on the first
        // idle frame after the interaction ends — exactly the quiet moment
        // the deferral is waiting for.
        let timer = Timer(timeInterval: 0.3, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.syncApplyRetryTimer = nil
                guard let pending = self.pendingSyncApply else { return }
                self.pendingSyncApply = nil
                self.applyMergedConfigFromSync(pending)
            }
        }
        syncApplyRetryTimer = timer
        RunLoop.main.add(timer, forMode: .default)
    }

    func applyMergedConfigFromSync(_ newConfig: ProviderConfig) {
        // Compute the set of instances that ended up with zero model
        // entries after the merge. The remote ProviderConfig snapshot
        // can be missing model rows when the peer hadn't refreshed yet
        // (or pushed before its own model fetch completed). Without a
        // follow-up refresh the model picker would be empty for those
        // instances on this device.
        let priorInstanceIds = Set(config.instances.map(\.id))
        let instancesNeedingRefresh: [ProviderInstance] = newConfig.instances.filter { inst in
            // Only instances that are either brand-new on this device
            // (never seen before) OR have no model entries after the
            // merge are worth refreshing — an existing instance with a
            // populated picker should be left alone.
            let entriesAfter = newConfig.modelEntries.filter { $0.providerInstanceId == inst.id }
            return entriesAfter.isEmpty && (!priorInstanceIds.contains(inst.id) ||
                                            !config.modelEntries.contains { $0.providerInstanceId == inst.id })
        }

        // [T-icloud-provider-sync-consistency] Collapse cross-device duplicate
        // entries. Each device assigns a RANDOM uuid to a freshly-refreshed
        // model, so two devices refreshing the same instance produce two
        // ProviderModelEntryV3 records for the same (instanceId, modelId) —
        // surfacing as the same model appearing 2-3× in the picker. Fold them
        // to one deterministic representative (lexicographically smallest uuid,
        // non-custom preferred) AND rewrite every group member reference from
        // the pruned uuids to the representative, so a group that pointed at a
        // now-removed duplicate doesn't become a dangling reference. Because
        // the representative is chosen deterministically, every device
        // converges to the SAME survivor and the SAME group references.
        let (deduped, prunedEntryIds) = Self.dedupeEntriesByModel(newConfig)

        // [T-ios-config-noop-publish-storm] No-op compensation short-circuit.
        // The periodic fetchRecent poll is purely a safety net — the common
        // case is "nothing changed since the last push", so `deduped` comes
        // back byte-identical to the in-memory `config`. Assigning it anyway
        // fired `@Published config`'s objectWillChange unconditionally, which
        // invalidated EVERY SwiftUI view observing ProviderConfigStore.shared —
        // including ContentView (whose body re-eval re-creates AIChatView, each
        // re-init wastefully constructing a throwaway CachedViewModel) and
        // AIChatView itself. During a scroll glide that landed on the main
        // thread as a deceleration-phase frame drop (see the MLTRACE decel
        // trace: AIChatView.init + updateUIViewController + applySubViewport-
        // Compensation firing mid-decel right before a ~100ms dropped frame).
        // When the merged result is identical AND there's nothing to prune /
        // refresh, skip the publish + disk + DB write entirely — the poll was
        // genuinely a no-op. Any real change (diff, prune, empty-picker
        // instance) still falls through to the full apply below.
        if deduped == config, prunedEntryIds.isEmpty, instancesNeedingRefresh.isEmpty {
            return
        }

        // [T-provider-sync-apply-interaction-defer] Hold the whole-store
        // publish out of the two windows where it has raced the async
        // renderer into an AttributeGraph SIGSEGV (field pair 2026-08-01,
        // 1.12(2) iOS 27: both crashes = foreground return + large inbound
        // provider merge, one mid-scroll — ViewGraph.updateOutputsAsync /
        // AGGraphWithMainThreadHandler, the FB13213926 async-render family):
        //   1. the first seconds after didBecomeActive, while SwiftUI is
        //      rebuilding the hosting graph for the returning scene — and
        //      exactly when the foreground sync fetch delivers its burst;
        //   2. any live scroll (RunLoop.main in UITracking mode — global,
        //      covers the provider/model pickers, not just the chat list).
        // Assigning `config` invalidates EVERY observer of the shared store
        // at once; deferring it a few hundred ms to the next quiet moment is
        // invisible to the user and keeps the giant graph update out of the
        // fragile frames. Latest snapshot wins while deferred; DB/disk
        // mirrors move with the apply, so state stays consistent.
        if shouldDeferSyncApply() {
            pendingSyncApply = newConfig
            armSyncApplyRetry()
            return
        }

        config = deduped
        // [T-icloud-fresh-restore-provider-groups] Summary of what this device
        // holds after an inbound merge — the single most useful triage line for
        // "fresh restore is missing providers/groups": shows instance / entry /
        // group counts plus total group members actually present.
        let groupMembers = deduped.modelGroups.reduce(0) { $0 + $1.memberEntryIds.count }
        logger.info("[v3sync] applyMergedConfigFromSync: instances=\(deduped.instances.count) entries=\(deduped.modelEntries.count) groups=\(deduped.modelGroups.count) groupMembers=\(groupMembers) prunedDup=\(prunedEntryIds.count)")
        // [T-icloud-provider-sync-apply-offmain] Persist to disk WITHOUT going
        // through `save()` (which would markDirty) — and do the encode + atomic
        // write OFF the main thread. Encoding the full provider config (hundreds
        // of model entries) + a synchronous file write was a measurable chunk of
        // the per-apply main-thread cost behind the scroll stalls. The in-memory
        // `config` (@Published) is already assigned above, so the disk mirror is
        // a fire-and-forget snapshot write with no ordering dependency; a later
        // apply simply overwrites it atomically. The SQLite mirror below is
        // likewise already off-main.
        let snapshotForDisk = config
        let diskURL = fileURL
        Task.detached(priority: .utility) {
            do {
                let data = try JSONEncoder().encode(snapshotForDisk)
                try data.write(to: diskURL, options: .atomic)
            } catch {
                AppLogger(category: "ProviderConfigStore").error("Failed to persist merged provider config: \(error)")
            }
        }
        // Mirror the merged config into SQLite too — but suppress v3
        // markDirty emission since this update originated INBOUND from
        // sync (re-emitting it would round-trip the same payload back
        // to cloud, exactly the v2 ping-pong we're trying to escape).
        // The lastSavedSnapshot is also updated so the next mutate's
        // diff is correctly anchored against the freshly-merged state.
        lastSavedSnapshot = config
        if let db {
            let snapshot = config
            let toDelete = prunedEntryIds
            Task.detached {
                // [T-icloud-agentloop-wipe] INBOUND path — turn on the
                // local-only preserve guard. agentLoopModelEntryIds is
                // per-device state that a peer's config knows nothing about, so
                // an inbound snapshot legitimately carries `[]` for it; without
                // this the replace below would erase the user's agent-loop
                // selection on every merge (OpenMinis#98 defect 2). The
                // user-initiated save() path deliberately does NOT set this —
                // there, an empty list really does mean "clear it".
                await db.bulkReplace(from: snapshot, preserveLocalOnlyStateIfEmpty: true)
                // Physically remove the pruned duplicate entry rows so they
                // don't reappear from the DB, and tombstone them so the
                // deletion propagates instead of resurrecting from a peer.
                for eid in toDelete { await db.deleteEntryRow(id: eid) }
            }
        }
        if !prunedEntryIds.isEmpty {
            logger.info("[ModelList] applyMergedConfigFromSync: collapsed \(prunedEntryIds.count) cross-device duplicate entry/entries; rewrote group refs to representatives")
            // Tombstone the pruned uuids so the fold propagates to peers.
            for eid in prunedEntryIds {
                Task { await ChatStore.shared.markDirty(recordType: "ProviderModelEntryV3", recordId: eid, operation: "delete") }
            }
        }

        // Fire-and-forget model refreshes for instances left empty.
        // refreshModels handles errors internally and writes back via
        // replaceEntries — which will markDirty for the next sync round
        // (idempotent if the picker is genuinely empty).
        if !instancesNeedingRefresh.isEmpty {
            logger.info("[ModelList] applyMergedConfigFromSync: \(instancesNeedingRefresh.count) instance(s) have empty model lists, scheduling refresh")
            for inst in instancesNeedingRefresh {
                Task { await refreshModels(for: inst) }
            }
        }
    }

    /// [T-icloud-provider-sync-consistency] Collapse duplicate model entries
    /// that share the same (providerInstanceId, baseModel.id) — the cross-device
    /// duplication caused by random per-device entry uuids — and rewrite all
    /// group member references onto the surviving representative.
    ///
    /// Determinism (so every device converges identically):
    ///   representative = among entries with the same composite key, prefer
    ///   isCustom==false; tie-break by lexicographically smallest uuid.
    ///
    /// Returns the deduped config and the list of pruned (non-representative)
    /// entry uuids so the caller can delete + tombstone them.
    static func dedupeEntriesByModel(_ input: ProviderConfig) -> (config: ProviderConfig, prunedEntryIds: [String]) {
        var config = input
        // Group entries by composite key.
        var byKey: [String: [ModelEntry]] = [:]
        for e in config.modelEntries {
            byKey[e.compositeKey, default: []].append(e)
        }
        guard byKey.values.contains(where: { $0.count > 1 }) else {
            return (config, [])  // no duplicates — fast path
        }
        // For each duplicated key pick a deterministic representative and map
        // every other uuid → representative uuid.
        var uuidRewrite: [String: String] = [:]  // prunedUuid → representativeUuid
        var pruned: [String] = []
        var survivors: [ModelEntry] = []
        for (_, entries) in byKey {
            if entries.count == 1 { survivors.append(entries[0]); continue }
            var rep = entries.sorted { a, b in
                if a.isCustom != b.isCustom { return !a.isCustom }  // non-custom first
                return a.uuid < b.uuid                              // then smallest uuid
            }.first!
            // [T-ios-hidden-models-restored] The representative choice is
            // deterministic by uuid, NOT by user intent — a duplicate that
            // carries the user's overlay (isHidden / overrides) could be the
            // one pruned, silently unhiding the model. Fold the overlay from
            // the duplicate with the newest userModifiedAt into the survivor.
            if let best = entries
                .compactMap({ e in e.userModifiedAt.map { (e, $0) } })
                .max(by: { $0.1 < $1.1 }),
               (rep.userModifiedAt ?? .distantPast) < best.1 {
                rep.isHidden = best.0.isHidden
                rep.overrides = best.0.overrides
                rep.userModifiedAt = best.1
            }
            survivors.append(rep)
            for e in entries where e.uuid != rep.uuid {
                uuidRewrite[e.uuid] = rep.uuid
                pruned.append(e.uuid)
            }
        }
        config.modelEntries = survivors
        if !uuidRewrite.isEmpty {
            // Rewrite group member references from pruned uuids → representative.
            for i in config.modelGroups.indices {
                var seen = Set<String>()
                config.modelGroups[i].memberEntryIds = config.modelGroups[i].memberEntryIds.compactMap { mid in
                    let mapped = uuidRewrite[mid] ?? mid
                    return seen.insert(mapped).inserted ? mapped : nil  // de-dup after rewrite
                }
                // Carry add/remove tombstones across the rewrite too.
                for (pruned, rep) in uuidRewrite {
                    if let t = config.modelGroups[i].addedMembers.removeValue(forKey: pruned) {
                        config.modelGroups[i].addedMembers[rep] = max(config.modelGroups[i].addedMembers[rep] ?? .distantPast, t)
                    }
                    if let t = config.modelGroups[i].removedMembers.removeValue(forKey: pruned) {
                        config.modelGroups[i].removedMembers[rep] = max(config.modelGroups[i].removedMembers[rep] ?? .distantPast, t)
                    }
                }
            }
            // Rewrite agent-loop entry references too.
            var seenAL = Set<String>()
            config.agentLoopModelEntryIds = config.agentLoopModelEntryIds.compactMap { id in
                let mapped = uuidRewrite[id] ?? id
                return seenAL.insert(mapped).inserted ? mapped : nil
            }
        }
        return (config, pruned)
    }

    // MARK: - [T-provider-entry-composite-key] legacyUuid normalization

    /// Rewrite every group / binding / agent-loop reference that matches a known
    /// legacyUuid to its composite key, using `legacyUuidToCompositeKey`.
    /// Idempotent. Returns true if anything changed (so caller can persist).
    /// References that don't match any known legacyUuid are LEFT AS-IS — never
    /// dropped — so an out-of-order arrival just normalizes later when the entry
    /// record (and thus its legacyUuid mapping) shows up.
    @discardableResult
    func normalizeReferences() -> Bool {
        guard !legacyUuidToCompositeKey.isEmpty else { return false }
        let map = legacyUuidToCompositeKey
        var changed = false

        func remap(_ ref: String) -> String { map[ref] ?? ref }

        // Groups: memberEntryIds + added/removed member keys.
        for i in config.modelGroups.indices {
            let before = config.modelGroups[i].memberEntryIds
            var seen = Set<String>()
            let after = before.compactMap { mid -> String? in
                let m = remap(mid)
                return seen.insert(m).inserted ? m : nil
            }
            if after != before { config.modelGroups[i].memberEntryIds = after; changed = true }

            for (oldKey, t) in config.modelGroups[i].addedMembers where map[oldKey] != nil {
                let nk = map[oldKey]!
                config.modelGroups[i].addedMembers.removeValue(forKey: oldKey)
                config.modelGroups[i].addedMembers[nk] = Swift.max(config.modelGroups[i].addedMembers[nk] ?? .distantPast, t)
                changed = true
            }
            for (oldKey, t) in config.modelGroups[i].removedMembers where map[oldKey] != nil {
                let nk = map[oldKey]!
                config.modelGroups[i].removedMembers.removeValue(forKey: oldKey)
                config.modelGroups[i].removedMembers[nk] = Swift.max(config.modelGroups[i].removedMembers[nk] ?? .distantPast, t)
                changed = true
            }
        }

        // Agent-loop entry ids.
        let alBefore = config.agentLoopModelEntryIds
        var seenAL = Set<String>()
        let alAfter = alBefore.compactMap { id -> String? in
            let m = remap(id); return seenAL.insert(m).inserted ? m : nil
        }
        if alAfter != alBefore { config.agentLoopModelEntryIds = alAfter; changed = true }

        // Session bindings: directEntry.compositeKey + group.resolvedEntryId.
        for (sid, var binding) in config.sessionBindings {
            var bChanged = false
            func fixSource(_ src: SessionModelSource) -> SessionModelSource {
                switch src {
                case .directEntry(let mid, let ck):
                    // Prefer composite key; if absent but mid is a known legacy
                    // uuid, fill composite key (keep mid for downgrade).
                    if ck == nil, let mapped = map[mid] {
                        bChanged = true
                        return .directEntry(modelEntryId: mid, compositeKey: mapped)
                    }
                    return src
                case .group(let gid, let rid):
                    let nr = remap(rid)
                    if nr != rid { bChanged = true; return .group(groupId: gid, resolvedEntryId: nr) }
                    return src
                }
            }
            binding.primarySource = fixSource(binding.primarySource)
            if let sub = binding.subModelSource { binding.subModelSource = fixSource(sub) }
            if bChanged { config.sessionBindings[sid] = binding; changed = true }
        }

        if changed {
            logger.info("[CompositeKeyMigrate] normalizeReferences: rewrote uuid→compositeKey references (lmap=\(map.count))")
        }
        return changed
    }

    /// Merge new legacyUuid→compositeKey entries into the map and persist.
    /// Triggers normalizeReferences when anything new was learned.
    func learnLegacyUuids(_ pairs: [String: String]) {
        var learnedNew = false
        var learnedKeys: [String] = []
        for (uuid, ck) in pairs where legacyUuidToCompositeKey[uuid] != ck {
            legacyUuidToCompositeKey[uuid] = ck
            learnedNew = true
            learnedKeys.append("\(uuid.prefix(8))→\(ck)")
        }
        guard learnedNew else {
            logger.info("[CompositeKeyMigrate] learnLegacyUuids: \(pairs.count) inbound pair(s), none new (lmap=\(self.legacyUuidToCompositeKey.count))")
            return
        }
        logger.info("[CompositeKeyMigrate] learnLegacyUuids: learned \(learnedKeys.count) new mapping(s) [\(learnedKeys.prefix(6).joined(separator: ","))] lmap=\(self.legacyUuidToCompositeKey.count)")
        persistLegacyUuidMap()
        if normalizeReferences() {
            save()
            logger.info("[CompositeKeyMigrate] learnLegacyUuids: normalizeReferences rewrote references after learning + saved")
        }
    }

    /// [T-provider-entry-id-canonicalize] Resolve a (possibly legacy random-uuid)
    /// entry id to its canonical composite key, if a mapping is known. Returns
    /// nil when the id is already composite (contains "/") or unknown — callers
    /// then fall back to the id as-is. Used by the inbound delete path so a
    /// raw-uuid `op=delete` from the cloud removes the locally composite-keyed
    /// row instead of silently no-op'ing.
    func canonicalEntryId(forLegacy id: String) -> String? {
        if id.contains("/") { return nil }
        return legacyUuidToCompositeKey[id]
    }

    private func persistLegacyUuidMap() {
        guard let db else { return }
        let snapshot = legacyUuidToCompositeKey
        let json = (try? JSONEncoder().encode(snapshot)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        Task.detached { await db.setLegacyUuidMapKV(json) }
    }

    func loadLegacyUuidMap() async {
        guard let db else { return }
        if let s = await db.localKV("legacyUuidMap"),
           let data = s.data(using: .utf8),
           let map = try? JSONDecoder().decode([String: String].self, from: data) {
            self.legacyUuidToCompositeKey = map
        }
    }

    // MARK: - Shadow Voice Providers [T-mimo-shadow-voice]

    /// True if `instance` has ANY model entry with an audio input/output modality.
    /// This replaces the old base-URL `isVoiceOnlyProvider` whitelist: voice
    /// ability is a per-MODEL concern, computed dynamically from the instance's
    /// entries rather than hardcoded per vendor host.
    func hasVoiceModels(for instanceId: String) -> Bool {
        config.modelEntries.contains { e in
            guard e.providerInstanceId == instanceId else { return false }
            let m = e.baseModel.capabilities.supportedModalities
            return m.contains(.audioInput) || m.contains(.audioOutput)
        }
    }

    /// Per-instance UserDefaults flag: user hid this instance's shadow voice
    /// entry ("I only want the text models, not the Voice Services row"). Nil
    /// key = enabled (shadow shown) by default.
    private static func voiceShadowDisabledKey(_ instanceId: String) -> String {
        "voiceShadowDisabled.\(instanceId)"
    }
    func isVoiceShadowDisabled(_ instanceId: String) -> Bool {
        UserDefaults.standard.bool(forKey: Self.voiceShadowDisabledKey(instanceId))
    }
    func setVoiceShadowDisabled(_ disabled: Bool, for instanceId: String) {
        UserDefaults.standard.set(disabled, forKey: Self.voiceShadowDisabledKey(instanceId))
        objectWillChange.send()
    }

    /// A read-only MIRROR of a Chat/OpenAI instance's voice capability, surfaced
    /// in Voice Services. NOT a stored entity: it shares the underlying instance's
    /// credential + endpoint and reads its audio-modality model entries. Pure
    /// runtime view — building it writes nothing.
    struct ShadowVoiceProvider: Identifiable {
        let instanceId: String
        var id: String { instanceId }
        let displayName: String
        let inputModels: [ModelEntry]   // audioInput entries (ASR)
        let outputModels: [ModelEntry]  // audioOutput entries (TTS)
    }

    /// Normalize a base URL for cross-instance de-dup: lowercased, trailing
    /// "/v1"/"/" stripped. Two instances pointing at the same MiMo host fold to
    /// one shadow row.
    static func normalizedShadowKey(_ baseURL: String?) -> String {
        guard var s = baseURL?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(), !s.isEmpty else { return "" }
        while s.hasSuffix("/") { s.removeLast() }
        if s.hasSuffix("/v1") { s = String(s.dropLast(3)) }
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }

    /// All shadow voice providers to show in Voice Services: one per enabled
    /// instance that has audio models and isn't shadow-disabled, then FOLDED by
    /// normalized base URL so two instances on the same host show a single row
    /// (representative = enabled first, then most-recently-modified, then oldest
    /// createdAt, then id — deterministic across devices).
    func shadowVoiceProviders() -> [ShadowVoiceProvider] {
        // Candidate instances.
        let candidates = config.instances.filter { inst in
            inst.isEnabled
            && hasVoiceModels(for: inst.id)
            && !isVoiceShadowDisabled(inst.id)
            && VoiceProviderFactory.make(for: inst) != nil
        }
        // Fold by normalized base URL (empty key = no custom base → keep separate by id).
        var byKey: [String: [ProviderInstance]] = [:]
        for inst in candidates {
            let key = Self.normalizedShadowKey(inst.effectiveCustomBaseURL)
            let bucket = key.isEmpty ? "id:\(inst.id)" : key
            byKey[bucket, default: []].append(inst)
        }
        func mostRecentModified(_ instanceId: String) -> Date {
            config.modelEntries
                .filter { $0.providerInstanceId == instanceId }
                .compactMap { $0.userModifiedAt }
                .max() ?? .distantPast
        }
        var result: [ShadowVoiceProvider] = []
        for (_, insts) in byKey {
            // Representative selection — deterministic.
            let rep = insts.sorted { a, b in
                if a.isEnabled != b.isEnabled { return a.isEnabled }
                let ma = mostRecentModified(a.id), mb = mostRecentModified(b.id)
                if ma != mb { return ma > mb }
                if a.createdAt != b.createdAt { return a.createdAt < b.createdAt }
                return a.id < b.id
            }.first!
            let entries = config.modelEntries.filter { $0.providerInstanceId == rep.id }
            let inputs = entries.filter { $0.baseModel.capabilities.supportedModalities.contains(.audioInput) }
            let outputs = entries.filter { $0.baseModel.capabilities.supportedModalities.contains(.audioOutput) }
            result.append(ShadowVoiceProvider(
                instanceId: rep.id,
                displayName: rep.label,
                inputModels: inputs,
                outputModels: outputs
            ))
        }
        // Stable display order: by displayName then id.
        return result.sorted { ($0.displayName, $0.id) < ($1.displayName, $1.id) }
    }

    /// True when ≥2 enabled instances share a normalized base URL AND have voice
    /// models — the migration/dup case (场景 B). UI shows a non-destructive hint.
    func hasFoldedShadowDuplicates() -> Bool {
        var seen = Set<String>()
        for inst in config.instances where inst.isEnabled && hasVoiceModels(for: inst.id) {
            let key = Self.normalizedShadowKey(inst.effectiveCustomBaseURL)
            guard !key.isEmpty else { continue }
            if !seen.insert(key).inserted { return true }
        }
        return false
    }

    // MARK: - Model Refresh

    /// Manual refresh: fetch models from the API (with models.dev fallback) and merge into entries.
    /// Appends new models without removing user-added custom entries.
    /// Errors are logged but not thrown (fire-and-forget friendly).
    func refreshModels(for instance: ProviderInstance) async {
        // [T-mimo-shadow-voice] No longer skipped for "voice-only" providers.
        // Refresh ALWAYS runs the real /models fetch so a mixed vendor (e.g.
        // MiMo: text chat + voice on one host) gets its text models; audio-modality
        // template seed entries are preserved by replaceEntries (see below), so
        // the voice models are never wiped by the text list.
        do {
            logger.info("[ModelList] refreshModels (MANUAL): instance=\(instance.label) starting fetch")
            let result = try await Self.fetchModelsWithFallback(instance, forceRefresh: true)
            replaceEntries(for: instance.id, models: result.models, caller: "refreshModels(manual)")
            logger.info("[ModelList] refreshModels (MANUAL): instance=\(instance.label) source=\(result.source) count=\(result.models.count)")
            for w in result.warnings { logger.warning("⚠️ \(w)") }
        } catch {
            logger.error("[ModelList] refreshModels (MANUAL): instance=\(instance.label) FAILED — \(error)")
        }
    }

    /// Auto-refresh: fetch models for a single instance, skipping if user has custom models.
    private func autoRefreshModels(for instance: ProviderInstance) async {
        // [T-mimo-shadow-voice] No longer skipped for "voice-only" providers —
        // audio-modality template seed entries survive replaceEntries.
        let hasCustomModels = config.modelEntries.contains { $0.providerInstanceId == instance.id && $0.isCustom }
        if hasCustomModels {
            logger.info("[ModelList] autoRefreshModels (DAILY): instance=\(instance.label) SKIP — user has custom models")
            return
        }
        do {
            logger.info("[ModelList] autoRefreshModels (DAILY): instance=\(instance.label) starting fetch")
            let result = try await Self.fetchModelsWithFallback(instance, forceRefresh: true)
            replaceEntries(for: instance.id, models: result.models, caller: "autoRefreshModels(daily)")
            logger.info("[ModelList] autoRefreshModels (DAILY): instance=\(instance.label) source=\(result.source) count=\(result.models.count)")
        } catch {
            logger.error("[ModelList] autoRefreshModels (DAILY): instance=\(instance.label) FAILED — \(error)")
        }
    }

    /// [T-mimo-shadow-voice] One-time upgrade migration: existing users whose
    /// mixed-modality provider (MiMo, DashScope/百炼, …) was mis-classified as
    /// voice-only by the old base-URL whitelist have polluted model lists (text
    /// models dropped, only voice mock left). Waiting for the next natural
    /// refresh is a poor experience. On first launch after this fix ships, force
    /// ONE real `refreshModels` for every third-party OpenAI-compatible instance
    /// so the corrected logic (never-skip fetch + replaceEntries voice-seed
    /// preservation) restores the correct model list. Runs once (guarded by a
    /// UserDefaults flag), silently, in the background, per-instance failure
    /// isolated. Once entries land, `shadowVoiceProviders()` recomputes off the
    /// updated `modelEntries` and the store's `objectWillChange` (fired by
    /// `save()` inside replaceEntries) refreshes the UI — no app restart needed.
    private static let voiceModalityMigrationKey = "voiceModalityMigration.v1.done"
    func migrateVoiceModalityIfNeeded() {
        guard !UserDefaults.standard.bool(forKey: Self.voiceModalityMigrationKey) else { return }
        UserDefaults.standard.set(true, forKey: Self.voiceModalityMigrationKey)

        // Third-party OpenAI-compat instances are the affected population (covers
        // MiMo/DashScope and any similar mixed vendor); official OpenAI/OpenRouter
        // hosts were never mis-classified, so skip them.
        let affected = config.instances.filter { inst in
            inst.isEnabled
            && (inst.providerType == .openAI || inst.providerType == .openAIResponses)
            && Self.isThirdPartyOpenAICompat(inst)
        }
        guard !affected.isEmpty else {
            logger.info("[VoiceMigrate] one-time refresh: no affected third-party OpenAI-compat instances")
            return
        }
        logger.info("[VoiceMigrate] one-time refresh: FIRE for \(affected.count) instance(s): [\(affected.map { $0.label }.joined(separator: ","))]")
        // Force the manual-refresh path (does NOT skip instances with custom
        // models — the affected users often re-added custom text models). Each
        // instance in its own Task so one failure never blocks the others.
        for instance in affected {
            Task { [weak self] in
                await self?.refreshModels(for: instance)
                logger.info("[VoiceMigrate] one-time refresh done: instance=\(instance.label) hasVoiceModels=\(self?.hasVoiceModels(for: instance.id) ?? false)")
            }
        }
    }

    /// Refresh models for all enabled provider instances.
    /// Called on first daily launch to keep model lists up-to-date.
    /// Skips instances where the user has manually added custom models.
    func refreshAllModelsIfNeeded() {
        let key = "lastModelsRefreshDate"
        let lastRefresh = UserDefaults.standard.object(forKey: key) as? Date
        let calendar = Calendar.current
        let lastStr = lastRefresh.map { ISO8601DateFormatter().string(from: $0) } ?? "nil"

        if let lastRefresh, calendar.isDateInToday(lastRefresh) {
            logger.info("[ModelList] refreshAllModelsIfNeeded: SKIP — already refreshed today (lastRefresh=\(lastStr))")
            return
        }

        let enabledInstances = config.instances.filter(\.isEnabled)
        guard !enabledInstances.isEmpty else {
            logger.info("[ModelList] refreshAllModelsIfNeeded: SKIP — no enabled instances")
            return
        }

        logger.info("[ModelList] refreshAllModelsIfNeeded: FIRE — lastRefresh=\(lastStr) instanceCount=\(enabledInstances.count) instances=[\(enabledInstances.map { $0.label }.joined(separator: ","))]")
        UserDefaults.standard.set(Date(), forKey: key)

        for instance in enabledInstances {
            Task {
                await autoRefreshModels(for: instance)
            }
        }
    }

    /// For existing users who have providers but no model groups, silently create a "Default Models"
    /// group using the past week's most-used model (or the first visible entry as fallback).
    func createDefaultGroupIfNeeded() async {
        guard !config.instances.isEmpty, config.modelGroups.isEmpty else { return }

        let cutoff = Date().addingTimeInterval(-7 * 24 * 3600)
        let topModelId = await ChatStore.shared.fetchMostUsedModelId(since: cutoff)

        let entry: ModelEntry?
        if let modelId = topModelId {
            entry = config.modelEntries.first { !$0.isHidden && $0.model.id == modelId }
        } else {
            entry = config.modelEntries.first { !$0.isHidden }
        }

        guard let entry else { return }

        let group = ModelGroup(
            name: "Default Models",
            memberEntryIds: [entry.id],
            strategy: .fallback
        )
        config.modelGroups.append(group)
        config.defaultPrimaryGroupId = group.id
        save()
        logger.info("Created default model group with \(entry.model.displayName)")
    }

    /// Fetch the model list from a provider's API for the given instance.
    static func fetchModelsForInstance(_ instance: ProviderInstance, forceRefresh: Bool = false) async throws -> [LLMModel] {
        let customBase = instance.effectiveCustomBaseURL
        let appendV1 = instance.appendV1Suffix
        // Custom UA only for custom-base OpenAI/Anthropic-compat instances (proxy/relay);
        // OAuth-login paths keep their required client UA, so we never pass it there.
        let ua = instance.supportsCustomUserAgent ? instance.effectiveCustomUserAgent : nil
        switch (instance.providerType, instance.credentialType) {
        case (.anthropic, .apiKey):
            guard let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) else {
                throw ModelRefreshError.noCredential
            }
            return try await AnthropicModelsAPI.fetchModels(apiKey: key, baseURL: customBase, appendV1Suffix: appendV1, forceRefresh: forceRefresh, userAgent: ua)
        case (.anthropic, .oauth):
            if let manualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") {
                // Manual token — try Bearer auth first, fall back to x-api-key for compatibility
                return try await AnthropicModelsAPI.fetchModels(bearerToken: manualToken, baseURL: customBase, appendV1Suffix: appendV1, forceRefresh: forceRefresh, userAgent: ua)
            }
            let token = try await ClaudeOAuthManager.shared.validAccessToken(instanceId: instance.id)
            return try await AnthropicModelsAPI.fetchModels(oauthToken: token, baseURL: customBase, appendV1Suffix: appendV1, forceRefresh: forceRefresh)
        case (.gemini, .apiKey):
            guard let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) else {
                throw ModelRefreshError.noCredential
            }
            return try await GeminiModelsAPI.fetchModels(apiKey: key, customBaseURL: customBase, forceRefresh: forceRefresh)
        case (.gemini, .oauth):
            if let manualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") {
                return try await GeminiModelsAPI.fetchModels(apiKey: manualToken, customBaseURL: customBase, forceRefresh: forceRefresh)
            }
            let token = try await GeminiOAuthManager.shared.validAccessToken(instanceId: instance.id)
            let projectID = GeminiOAuthManager.shared.gcpProjectID(instanceId: instance.id)
            return try await GeminiModelsAPI.fetchModels(oauthToken: token, gcpProjectID: projectID ?? "", customBaseURL: customBase)
        case (.openAI, .apiKey):
            guard let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) else {
                throw ModelRefreshError.noCredential
            }
            return try await OpenAIModelsAPI.fetchModels(apiKey: key, baseURL: customBase, appendV1Suffix: appendV1, forceRefresh: forceRefresh, userAgent: ua)
        case (.openAI, .oauth):
            if let manualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") {
                return try await OpenAIModelsAPI.fetchModels(apiKey: manualToken, baseURL: customBase, appendV1Suffix: appendV1, forceRefresh: forceRefresh, userAgent: ua)
            }
            return OpenAIModelsAPI.fetchModelsOAuth()
        case (.antigravity, .apiKey):
            // Antigravity only supports OAuth
            return ModelsDevAPI.enrichModels(AntigravityModelsAPI.fetchModelsBuiltIn())
        case (.antigravity, .oauth):
            let token = try await AntigravityOAuthManager.shared.validAccessToken(instanceId: instance.id)
            let projectID = AntigravityOAuthManager.shared.projectID(instanceId: instance.id) ?? ""
            return try await AntigravityModelsAPI.fetchModels(oauthToken: token, projectID: projectID, forceRefresh: forceRefresh)
        case (.openRouter, .apiKey):
            guard let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) else {
                throw ModelRefreshError.noCredential
            }
            return try await OpenRouterModelsAPI.fetchModels(apiKey: key, forceRefresh: forceRefresh)
        case (.openRouter, .oauth):
            if let manualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") {
                return try await OpenRouterModelsAPI.fetchModels(apiKey: manualToken, forceRefresh: forceRefresh)
            }
            // OpenRouter OAuth produces a permanent API key stored via ProviderKeychainHelper.saveAPIKey
            guard let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) else {
                throw ModelRefreshError.noCredential
            }
            return try await OpenRouterModelsAPI.fetchModels(apiKey: key, forceRefresh: forceRefresh)
        case (.openAIResponses, .apiKey):
            guard let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) else {
                throw ModelRefreshError.noCredential
            }
            return try await OpenAIModelsAPI.fetchModels(apiKey: key, baseURL: customBase, appendV1Suffix: appendV1, forceRefresh: forceRefresh, userAgent: ua)
        case (.openAIResponses, .oauth):
            // Responses API provider type only supports API key auth
            return ModelsDevAPI.enrichModels(ProviderType.openAIResponses.builtInModels)
        case (.xAI, .apiKey):
            guard let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) else {
                throw ModelRefreshError.noCredential
            }
            let xaiBase = customBase ?? "https://api.x.ai/v1"
            let xaiAppendV1 = customBase == nil ? false : appendV1
            return try await OpenAIModelsAPI.fetchModels(apiKey: key, baseURL: xaiBase, appendV1Suffix: xaiAppendV1, forceRefresh: forceRefresh, userAgent: ua)
        case (.xAI, .oauth):
            let token: String
            // Custom UA applies only to the manual-token (relay) sub-case; the xAI
            // OAuth-login token keeps the default UA, matching the other OAuth paths.
            var xaiUA: String? = nil
            if let manualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") {
                token = manualToken
                xaiUA = ua
            } else {
                token = try await XAIOAuthManager.shared.validAccessToken(instanceId: instance.id)
            }
            let xaiBase = customBase ?? "https://api.x.ai/v1"
            let xaiAppendV1 = customBase == nil ? false : appendV1
            return try await OpenAIModelsAPI.fetchModels(apiKey: token, baseURL: xaiBase, appendV1Suffix: xaiAppendV1, forceRefresh: forceRefresh, userAgent: xaiUA)
        case (.kimiCode, .apiKey):
            guard let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) else {
                throw ModelRefreshError.noCredential
            }
            let kimiBase = customBase ?? "https://api.kimi.com/coding"
            let kimiAppendV1 = customBase == nil ? true : appendV1  // default base …/coding needs /v1 appended
            return try await OpenAIModelsAPI.fetchModels(apiKey: key, baseURL: kimiBase, appendV1Suffix: kimiAppendV1, forceRefresh: forceRefresh, userAgent: ua)
        case (.kimiCode, .oauth):
            let token = try await KimiOAuthManager.shared.validAccessToken(instanceId: instance.id)
            let kimiBase = customBase ?? "https://api.kimi.com/coding"
            let kimiAppendV1 = customBase == nil ? true : appendV1  // default base …/coding needs /v1 appended
            return try await OpenAIModelsAPI.fetchModels(apiKey: token, baseURL: kimiBase, appendV1Suffix: kimiAppendV1, forceRefresh: forceRefresh, userAgent: nil)
        case (.unsupported, _):
            // Synced from a newer build — can't fetch; keep whatever's stored.
            return []
        }
    }

    /// Result of a model fetch with fallback — includes diagnostic warnings for each step.
    struct ModelFetchResult {
        let models: [LLMModel]
        let source: String            // "api", "models.dev", or "none"
        let warnings: [String]        // Diagnostic messages from each failed step
    }

    /// Whether the instance uses a third-party (non-official) OpenAI-compatible base URL
    /// (e.g. vLLM, Ollama, LiteLLM). For these endpoints, we must never fall back to
    /// built-in GPT model lists when the API is unreachable — keep existing models instead.
    /// Decode a raw OpenAI OAuth token response (snake_case keys, `expire_at`
    /// as epoch-ms) into a `CodexTokenStorage`. Used when importing a provider
    /// exported from Android, which stores the raw OAuth JSON rather than the
    /// iOS-native camelCase `CodexTokenStorage` encoding.
    private static func decodeCodexTokenFromRawOAuth(_ blob: Data) -> CodexTokenStorage? {
        guard let json = try? JSONSerialization.jsonObject(with: blob) as? [String: Any],
              let accessToken = json["access_token"] as? String else { return nil }
        let refreshToken = json["refresh_token"] as? String
        let idToken = json["id_token"] as? String
        let expireDate = expireDateFromRawOAuth(json)
        var accountId: String?
        var planType: String?
        if let idToken, let payload = CodexOAuthManager.decodeJWTPayload(idToken) {
            let auth = payload["https://api.openai.com/auth"] as? [String: Any]
            accountId = auth?["chatgpt_account_id"] as? String
            planType = auth?["chatgpt_plan_type"] as? String
        }
        return CodexTokenStorage(
            accessToken: accessToken,
            refreshToken: refreshToken,
            idToken: idToken,
            expireDate: expireDate,
            lastRefresh: Date(),
            accountId: accountId,
            planType: planType
        )
    }

    /// Parse an Android-exported raw OAuth JSON into `ClaudeTokenStorage`.
    private static func decodeClaudeTokenFromRawOAuth(_ blob: Data) -> ClaudeTokenStorage? {
        guard let json = try? JSONSerialization.jsonObject(with: blob) as? [String: Any],
              let accessToken = json["access_token"] as? String else { return nil }
        return ClaudeTokenStorage(
            accessToken: accessToken,
            refreshToken: json["refresh_token"] as? String,
            expireDate: expireDateFromRawOAuth(json),
            lastRefresh: Date()
        )
    }

    /// Parse an Android-exported raw OAuth JSON into `GeminiTokenStorage`.
    private static func decodeGeminiTokenFromRawOAuth(_ blob: Data) -> GeminiTokenStorage? {
        guard let json = try? JSONSerialization.jsonObject(with: blob) as? [String: Any],
              let accessToken = json["access_token"] as? String else { return nil }
        return GeminiTokenStorage(
            accessToken: accessToken,
            refreshToken: json["refresh_token"] as? String,
            expireDate: expireDateFromRawOAuth(json),
            lastRefresh: Date()
        )
    }

    /// Parse an Android-exported raw OAuth JSON into `XAITokenStorage`.
    private static func decodeXAITokenFromRawOAuth(_ blob: Data) -> XAITokenStorage? {
        guard let json = try? JSONSerialization.jsonObject(with: blob) as? [String: Any],
              let accessToken = json["access_token"] as? String else { return nil }
        return XAITokenStorage(
            accessToken: accessToken,
            refreshToken: json["refresh_token"] as? String,
            idToken: json["id_token"] as? String,
            expireDate: expireDateFromRawOAuth(json),
            lastRefresh: Date(),
            tokenEndpoint: json["token_endpoint"] as? String,
            email: json["email"] as? String,
            displayName: json["display_name"] as? String ?? json["displayName"] as? String,
            accountId: json["account_id"] as? String
        )
    }

    private static func expireDateFromRawOAuth(_ json: [String: Any]) -> Date? {
        if let ms = json["expire_at"] as? Double { return Date(timeIntervalSince1970: ms / 1000) }
        if let secs = json["expires_in"] as? Double { return Date(timeIntervalSinceNow: secs) }
        return nil
    }

    private static func isThirdPartyOpenAICompat(_ instance: ProviderInstance) -> Bool {
        guard let custom = instance.effectiveCustomBaseURL?.lowercased() else { return false }
        let officialHosts = ["api.openai.com", "chatgpt.com", "openrouter.ai"]
        return !officialHosts.contains(where: { custom.contains($0) })
    }

    /// Fetch models from the provider API, falling back to models.dev when the API
    /// returns an empty list or fails (e.g. custom base URL without /v1/models support).
    static func fetchModelsWithFallback(_ instance: ProviderInstance, forceRefresh: Bool = false) async throws -> ModelFetchResult {
        var warnings: [String] = []
        let isThirdParty = isThirdPartyOpenAICompat(instance)

        // Step 1: Try /v1/models API
        do {
            let models = try await fetchModelsForInstance(instance, forceRefresh: forceRefresh)
            if !models.isEmpty {
                return ModelFetchResult(models: models, source: "api", warnings: [])
            }
            let msg = "API returned empty model list"
            warnings.append(msg)
            logger.info("\(msg) for \(instance.label)")
        } catch let error as ModelRefreshError {
            throw error  // No credential — don't attempt fallback
        } catch {
            let msg = "API fetch failed: \(error.localizedDescription)"
            warnings.append(msg)
            logger.info("\(msg) for \(instance.label)")
            // Third-party endpoints (vLLM, Ollama, etc.): do not fall back to
            // built-in GPT models — keep the previously fetched list intact.
            if isThirdParty {
                logger.info("Third-party endpoint unreachable, preserving existing models for \(instance.label)")
                throw ModelRefreshError.modelsDevNoMatch(warnings: warnings)
            }
        }

        // Step 2: Try models.dev fallback (skip for third-party endpoints)
        let baseURL = modelsDevBaseURL(for: instance)
        guard let baseURL else {
            let msg = "No base URL resolved, cannot try models.dev"
            warnings.append(msg)
            logger.info("\(msg) for \(instance.label)")
            if isThirdParty {
                throw ModelRefreshError.modelsDevNoMatch(warnings: warnings)
            }
            // Fall back to built-in models
            let builtIn = ModelsDevAPI.enrichModels(instance.providerType.builtInModels)
            if !builtIn.isEmpty {
                logger.info("Using \(builtIn.count) built-in models for \(instance.label)")
                return ModelFetchResult(models: builtIn, source: "built-in", warnings: warnings)
            }
            throw ModelRefreshError.modelsDevNoMatch(warnings: warnings)
        }

        logger.info("models.dev fallback: matching baseURL=\(baseURL) for \(instance.label)")
        let fallbackModels = ModelsDevAPI.fetchModels(forBaseURL: baseURL)
        if !fallbackModels.isEmpty {
            logger.info("models.dev fallback returned \(fallbackModels.count) models for \(instance.label)")
            return ModelFetchResult(models: fallbackModels, source: "models.dev", warnings: warnings)
        }
        let msg = "models.dev: no match for baseURL \(baseURL)"
        warnings.append(msg)
        logger.info("\(msg)")

        // Step 3: Fall back to provider's built-in models (skip for third-party endpoints)
        if isThirdParty {
            logger.info("Third-party endpoint, skipping built-in fallback for \(instance.label)")
            throw ModelRefreshError.modelsDevNoMatch(warnings: warnings)
        }
        let builtIn = ModelsDevAPI.enrichModels(instance.providerType.builtInModels)
        if !builtIn.isEmpty {
            logger.info("Using \(builtIn.count) built-in models for \(instance.label)")
            return ModelFetchResult(models: builtIn, source: "built-in", warnings: warnings)
        }

        throw ModelRefreshError.modelsDevNoMatch(warnings: warnings)
    }

    /// Resolve the effective API base URL for an instance (for models.dev matching).
    private static func modelsDevBaseURL(for instance: ProviderInstance) -> String? {
        if let custom = instance.effectiveCustomBaseURL {
            return custom
        }
        // Use the provider's well-known default base URL
        switch instance.providerType {
        case .anthropic: return "https://api.anthropic.com"
        case .openAI, .openAIResponses: return "https://api.openai.com"
        case .xAI: return "https://api.x.ai"
        case .kimiCode: return "https://api.kimi.com/coding"
        case .gemini: return "https://generativelanguage.googleapis.com"
        case .openRouter: return "https://openrouter.ai/api"
        case .antigravity: return nil // No public base URL
        case .unsupported: return nil // synced from newer build
        }
    }

    // MARK: - Thinking rules (Phase 2 §2)

    /// Reload every instance's user-authored thinking rules into the synchronous cache
    /// the resolver reads. Call after any mutation, and once at DB adoption.
    func reloadThinkingRuleCache() async {
        guard let db else { return }
        var map: [String: [ThinkingRule]] = [:]
        for inst in config.instances {
            let rules = await db.loadThinkingRules(instanceId: inst.id)
            if !rules.isEmpty { map[inst.id] = rules }
        }
        ThinkingRuleCache.shared.replaceAll(map)
        logger.info("[ThinkingRules] cache primed: \(map.count) instance(s) with custom rules")
    }

    /// Rules for one instance, for the UI.
    func thinkingRules(for instanceId: String) async -> [ThinkingRule] {
        guard let db else { return [] }
        return await db.loadThinkingRules(instanceId: instanceId)
    }

    /// Create or update one rule, then refresh the cache so the next request sees it.
    ///
    /// [T-icloud-thinking-rules-sync] Emits a per-record markDirty so the rule reaches
    /// peer devices, exactly as instances / entries / groups do.
    @discardableResult
    func saveThinkingRule(_ rule: ThinkingRule, instanceId: String, sortOrder: Int) async -> Bool {
        guard let db else { return false }
        let ok = await db.upsertThinkingRule(rule, instanceId: instanceId, sortOrder: sortOrder)
        await reloadThinkingRuleCache()
        if ok {
            await ChatStore.shared.markDirty(recordType: "ProviderThinkingRuleV3",
                                             recordId: rule.id, operation: "upsert")
        }
        return ok
    }

    /// Delete one rule locally and propagate the deletion.
    ///
    /// [T-icloud-thinking-rules-sync] The row is really removed (no soft-delete column):
    /// the anti-resurrection guarantee comes from the tombstone that `markDirty(op:
    /// "delete")` writes into `deleted_record_tombstones`, which is the same generic
    /// mechanism Skill / EnvVarItem / Folder / the other provider V3 types use. Without
    /// it, a `fetchRecentV2` that races ahead of the cloud delete would re-pull the
    /// still-live record and the merger would happily re-insert it.
    ///
    /// markDirty is emitted BEFORE the row is gone is NOT required — the tombstone is
    /// keyed by id and the builder returning nil for a missing row is an accepted
    /// outcome — but the delete op must be emitted regardless of whether the local row
    /// still existed, so a peer's copy is removed even if ours was already gone.
    @discardableResult
    func deleteThinkingRule(id: String, instanceId: String) async -> Bool {
        guard let db else { return false }
        let ok = await db.deleteThinkingRule(id: id)
        await reloadThinkingRuleCache()
        await ChatStore.shared.markDirty(recordType: "ProviderThinkingRuleV3",
                                         recordId: id, operation: "delete")
        return ok
    }

    /// Reordering changes each affected rule's `sort_order`, and order IS priority
    /// (first match wins), so every id in the list needs its own upsert — a single
    /// record cannot express "the list moved".
    @discardableResult
    func reorderThinkingRules(instanceId: String, orderedIds: [String]) async -> Bool {
        guard let db else { return false }
        let ok = await db.reorderThinkingRules(instanceId: instanceId, orderedIds: orderedIds)
        await reloadThinkingRuleCache()
        if ok {
            for rid in orderedIds {
                await ChatStore.shared.markDirty(recordType: "ProviderThinkingRuleV3",
                                                 recordId: rid, operation: "upsert")
            }
        }
        return ok
    }

}

// MARK: - Model Refresh Error

enum ModelRefreshError: LocalizedError {
    case noCredential
    case modelsDevNoMatch(warnings: [String])

    var errorDescription: String? {
        switch self {
        case .noCredential:
            return "No API key configured for this provider instance."
        case .modelsDevNoMatch(let warnings):
            let detail = warnings.isEmpty ? "" : "\n" + warnings.joined(separator: "\n")
            return "Could not fetch models from API or models.dev fallback.\(detail)"
        }
    }
}

// MARK: - Keychain Helper

enum ProviderKeychainHelper {
    private static let account = "api-key"

    /// UserDefaults key for the local "last saved at" timestamp of an API key.
    /// Used for iCloud sync LWW: when a newer device uploads a rotated key, receiving
    /// devices compare `remote.updatedAt` against this stamp to decide whether to
    /// overwrite the local Keychain entry.
    private static func apiKeySavedAtUDKey(instanceId: String) -> String {
        "providerKey.savedAt.\(instanceId)"
    }

    /// Timestamp of the last local save for this instance's API key, or
    /// `.distantPast` if never stamped (legacy entries written before this field existed).
    static func apiKeySavedAt(instanceId: String) -> Date {
        let ud = UserDefaults.standard
        if let ts = ud.object(forKey: apiKeySavedAtUDKey(instanceId: instanceId)) as? Date {
            return ts
        }
        return .distantPast
    }

    /// Stamp the "saved at" time for this instance's API key. Called automatically by
    /// `saveAPIKey`; exposed for the iCloud import path to record when a remote update
    /// won and was applied locally.
    static func stampAPIKeySavedAt(_ date: Date, instanceId: String) {
        UserDefaults.standard.set(date, forKey: apiKeySavedAtUDKey(instanceId: instanceId))
    }

    static func saveAPIKey(_ key: String, instanceId: String, caller: String = #function) {
        let service = "com.openminis.app.provider.\(instanceId)"
        // Delete both legacy (non-sync) and synchronizable entries
        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(deleteQuery as CFDictionary)
        var syncDelete = deleteQuery
        syncDelete[kSecAttrSynchronizable as String] = true
        SecItemDelete(syncDelete as CFDictionary)
        // Save with iCloud Keychain sync enabled
        var addQuery = deleteQuery
        addQuery[kSecValueData as String] = Data(key.utf8)
        addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        addQuery[kSecAttrSynchronizable as String] = true
        let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
        // Stamp the local save time for iCloud LWW conflict resolution.
        stampAPIKeySavedAt(Date(), instanceId: instanceId)
        AppLogger(category: "Keychain").info("write apiKey instanceId=\(instanceId.prefix(8)) keyLen=\(key.count) addStatus=\(addStatus) caller=\(caller)")
        // Refresh credential-derived UI (provider list "configured" dot, etc).
        notifyAuthChanged(instanceId: instanceId)
    }

    static func loadAPIKey(instanceId: String, caller: String = #function) -> String? {
        let service = "com.openminis.app.provider.\(instanceId)"
        // Try synchronizable first, then fallback to legacy
        let syncQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: true,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let syncStatus = SecItemCopyMatching(syncQuery as CFDictionary, &result)
        if syncStatus == errSecSuccess, let data = result as? Data {
            let s = String(data: data, encoding: .utf8)
            AppLogger(category: "Keychain").info("read apiKey instanceId=\(instanceId.prefix(8)) src=sync hit=\(s != nil) keyLen=\(s?.count ?? 0) caller=\(caller)")
            return s
        }
        // Fallback to legacy non-sync entry
        let legacyQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        result = nil
        let legacyStatus = SecItemCopyMatching(legacyQuery as CFDictionary, &result)
        guard legacyStatus == errSecSuccess, let data = result as? Data else {
            AppLogger(category: "Keychain").info("read apiKey instanceId=\(instanceId.prefix(8)) hit=false syncStatus=\(syncStatus) legacyStatus=\(legacyStatus) caller=\(caller)")
            return nil
        }
        let s = String(data: data, encoding: .utf8)
        AppLogger(category: "Keychain").info("read apiKey instanceId=\(instanceId.prefix(8)) src=legacy hit=\(s != nil) keyLen=\(s?.count ?? 0) syncStatus=\(syncStatus) caller=\(caller)")
        return s
    }

    static func deleteAPIKey(instanceId: String, caller: String = #function) {
        let service = "com.openminis.app.provider.\(instanceId)"
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let s1 = SecItemDelete(query as CFDictionary)
        var syncQuery = query
        syncQuery[kSecAttrSynchronizable as String] = true
        let s2 = SecItemDelete(syncQuery as CFDictionary)
        // Clear the LWW stamp so any subsequent import (e.g. rebinding from another
        // device) isn't blocked by a stale "local is newer" comparison.
        UserDefaults.standard.removeObject(forKey: apiKeySavedAtUDKey(instanceId: instanceId))
        AppLogger(category: "Keychain").info("delete apiKey instanceId=\(instanceId.prefix(8)) legacyStatus=\(s1) syncStatus=\(s2) caller=\(caller)")
        notifyAuthChanged(instanceId: instanceId)
    }

    // MARK: - OAuth Token (per-instance)

    /// Called at the end of every credential write/delete. Bumps `authRevision`
    /// (invalidates the L1 resolveCurrentEntry cache) and drops this instance's
    /// L2 credential cache entry so the next `hasAnyCredential` re-reads Keychain.
    /// [T-new-session-hang-credential-cache]
    private static func notifyAuthChanged(instanceId: String) {
        ProviderCredentialCache.shared.invalidate(instanceId)
        Task { @MainActor in ProviderConfigStore.shared.authRevision &+= 1 }
    }

    /// [T-ios-backup-credential-restore] Raw-bytes accessors for the structured
    /// OAuth blob.
    ///
    /// The backup importer has the token as opaque JSON straight out of the
    /// package and must not have to switch on every provider type to re-type it
    /// (which would also silently drop any provider type added later). These
    /// read/write the exact same Keychain item as the typed
    /// `saveOAuthToken`/`loadOAuthToken` pair.
    static func saveRawOAuthToken(_ data: Data, instanceId: String) {
        let service = "com.openminis.app.provider.\(instanceId)"
        let acct = "oauth-token"
        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: acct,
        ]
        SecItemDelete(deleteQuery as CFDictionary)
        var syncDelete = deleteQuery
        syncDelete[kSecAttrSynchronizable as String] = true
        SecItemDelete(syncDelete as CFDictionary)
        var addQuery = deleteQuery
        addQuery[kSecValueData as String] = data
        addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        addQuery[kSecAttrSynchronizable as String] = true
        let status = SecItemAdd(addQuery as CFDictionary, nil)
        if status != errSecSuccess {
            AppLogger(category: "Keychain").warning("write rawOAuthToken instanceId=\(instanceId.prefix(8)) status=\(status)")
        }
    }

    static func loadRawOAuthToken(instanceId: String) -> Data? {
        let service = "com.openminis.app.provider.\(instanceId)"
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "oauth-token",
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        query[kSecAttrSynchronizable as String] = kSecAttrSynchronizableAny
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    static func saveOAuthToken<T: Codable>(_ token: T, instanceId: String, caller: String = #function) {
        guard let data = try? JSONEncoder().encode(token) else {
            AppLogger(category: "Keychain").warning("write oauthToken instanceId=\(instanceId.prefix(8)) ENCODE FAILED caller=\(caller)")
            return
        }
        let service = "com.openminis.app.provider.\(instanceId)"
        let acct = "oauth-token"
        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: acct,
        ]
        SecItemDelete(deleteQuery as CFDictionary)
        var syncDelete = deleteQuery
        syncDelete[kSecAttrSynchronizable as String] = true
        SecItemDelete(syncDelete as CFDictionary)
        var addQuery = deleteQuery
        addQuery[kSecValueData as String] = data
        addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        addQuery[kSecAttrSynchronizable as String] = true
        let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
        AppLogger(category: "Keychain").info("write oauthToken instanceId=\(instanceId.prefix(8)) blobLen=\(data.count) addStatus=\(addStatus) caller=\(caller)")
        notifyAuthChanged(instanceId: instanceId)
    }

    static func loadOAuthToken<T: Codable>(instanceId: String, as type: T.Type, caller: String = #function) -> T? {
        let service = "com.openminis.app.provider.\(instanceId)"
        let acct = "oauth-token"
        // Try synchronizable first
        let syncQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: acct,
            kSecAttrSynchronizable as String: true,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let syncStatus = SecItemCopyMatching(syncQuery as CFDictionary, &result)
        if syncStatus == errSecSuccess, let data = result as? Data {
            let decoded = try? JSONDecoder().decode(type, from: data)
            AppLogger(category: "Keychain").info("read oauthToken instanceId=\(instanceId.prefix(8)) src=sync hit=true decoded=\(decoded != nil) caller=\(caller)")
            return decoded
        }
        // Fallback to legacy
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: acct,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        let legacyStatus = SecItemCopyMatching(query as CFDictionary, &result)
        guard legacyStatus == errSecSuccess, let data = result as? Data else {
            AppLogger(category: "Keychain").info("read oauthToken instanceId=\(instanceId.prefix(8)) hit=false syncStatus=\(syncStatus) legacyStatus=\(legacyStatus) caller=\(caller)")
            return nil
        }
        let decoded = try? JSONDecoder().decode(type, from: data)
        AppLogger(category: "Keychain").info("read oauthToken instanceId=\(instanceId.prefix(8)) src=legacy hit=true decoded=\(decoded != nil) syncStatus=\(syncStatus) caller=\(caller)")
        return decoded
    }

    static func deleteOAuthToken(instanceId: String, caller: String = #function) {
        let service = "com.openminis.app.provider.\(instanceId)"
        let acct = "oauth-token"
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: acct,
        ]
        let s1 = SecItemDelete(query as CFDictionary)
        var syncQuery = query
        syncQuery[kSecAttrSynchronizable as String] = true
        let s2 = SecItemDelete(syncQuery as CFDictionary)
        AppLogger(category: "Keychain").info("delete oauthToken instanceId=\(instanceId.prefix(8)) legacyStatus=\(s1) syncStatus=\(s2) caller=\(caller)")
        notifyAuthChanged(instanceId: instanceId)
    }

    // MARK: - OAuth Strings (per-instance, e.g. email, project ID)

    static func saveOAuthString(_ value: String, instanceId: String, account: String, caller: String = #function) {
        let service = "com.openminis.app.provider.\(instanceId)"
        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(deleteQuery as CFDictionary)
        var syncDelete = deleteQuery
        syncDelete[kSecAttrSynchronizable as String] = true
        SecItemDelete(syncDelete as CFDictionary)
        var addQuery = deleteQuery
        addQuery[kSecValueData as String] = Data(value.utf8)
        addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        addQuery[kSecAttrSynchronizable as String] = true
        let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
        AppLogger(category: "Keychain").info("write oauthString instanceId=\(instanceId.prefix(8)) acct=\(account) valLen=\(value.count) addStatus=\(addStatus) caller=\(caller)")
        notifyAuthChanged(instanceId: instanceId)
    }

    static func loadOAuthString(instanceId: String, account: String, caller: String = #function) -> String? {
        let service = "com.openminis.app.provider.\(instanceId)"
        // Try synchronizable first
        let syncQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: true,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let syncStatus = SecItemCopyMatching(syncQuery as CFDictionary, &result)
        if syncStatus == errSecSuccess, let data = result as? Data {
            let s = String(data: data, encoding: .utf8)
            AppLogger(category: "Keychain").info("read oauthString instanceId=\(instanceId.prefix(8)) acct=\(account) src=sync hit=\(s != nil) valLen=\(s?.count ?? 0) caller=\(caller)")
            return s
        }
        // Fallback to legacy
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        let legacyStatus = SecItemCopyMatching(query as CFDictionary, &result)
        guard legacyStatus == errSecSuccess, let data = result as? Data else {
            AppLogger(category: "Keychain").info("read oauthString instanceId=\(instanceId.prefix(8)) acct=\(account) hit=false syncStatus=\(syncStatus) legacyStatus=\(legacyStatus) caller=\(caller)")
            return nil
        }
        let s = String(data: data, encoding: .utf8)
        AppLogger(category: "Keychain").info("read oauthString instanceId=\(instanceId.prefix(8)) acct=\(account) src=legacy hit=\(s != nil) valLen=\(s?.count ?? 0) syncStatus=\(syncStatus) caller=\(caller)")
        return s
    }

    static func deleteOAuthString(instanceId: String, account: String, caller: String = #function) {
        let service = "com.openminis.app.provider.\(instanceId)"
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let s1 = SecItemDelete(query as CFDictionary)
        var syncQuery = query
        syncQuery[kSecAttrSynchronizable as String] = true
        let s2 = SecItemDelete(syncQuery as CFDictionary)
        AppLogger(category: "Keychain").info("delete oauthString instanceId=\(instanceId.prefix(8)) acct=\(account) legacyStatus=\(s1) syncStatus=\(s2) caller=\(caller)")
        notifyAuthChanged(instanceId: instanceId)
    }
}
