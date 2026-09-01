import Foundation
import CloudKit
import CryptoKit
import os.log

private let logger = AppLogger(category: "CloudSync")

extension Notification.Name {
    static let cloudSyncDidFetchChanges = Notification.Name("cloudSyncDidFetchChanges")
}

// MARK: - Sync Log

/// A single sync event for display in the Sync Log viewer.
struct SyncLogEntry: Identifiable {
    let id = UUID()
    let timestamp: Date
    let direction: Direction
    let recordType: String
    let recordName: String
    let summary: String   // human-readable: session title, file name, skill name, etc.
    let detail: String
    let deviceName: String?

    enum Direction: String {
        case sent = "sent"
        case received = "received"
        case conflict = "conflict"
        case error = "error"
    }
}

/// Observable store of recent sync events (max 1000, newest first).
/// Persisted to disk in the Logs directory alongside app logs.
@MainActor
final class SyncLogStore: ObservableObject {
    static let shared = SyncLogStore()
    @Published var entries: [SyncLogEntry] = []
    private let maxEntries = 1000
    private let fileURL: URL

    private struct PersistEntry: Codable {
        let timestamp: Date
        let direction: String
        let recordType: String
        let recordName: String
        let summary: String?
        let detail: String
        let deviceName: String?
    }

    private init() {
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let logsDir = library.appendingPathComponent("Logs")
        try? FileManager.default.createDirectory(at: logsDir, withIntermediateDirectories: true)
        self.fileURL = logsDir.appendingPathComponent("sync-log.json")
        self.entries = Self.load(from: fileURL)
    }

    func append(_ entry: SyncLogEntry) {
        guard LoggingManager.shared.isEnabled else { return }
        entries.insert(entry, at: 0)
        if entries.count > maxEntries {
            entries.removeLast(entries.count - maxEntries)
        }
        saveToDisk()
    }

    func clear() {
        entries.removeAll()
        try? FileManager.default.removeItem(at: fileURL)
    }

    /// Export sync log as a sanitized plain-text report for sharing.
    /// Strips sensitive data: session titles are omitted, UUIDs are truncated,
    /// message content is never included. Safe for sharing with developers.
    func exportSanitizedReport() -> String {
        var lines: [String] = []
        lines.append("=== MinisApp iCloud Sync Diagnostic Log ===")
        lines.append("Exported: \(ISO8601DateFormatter().string(from: Date()))")
        lines.append("Entries: \(entries.count)")
        lines.append("")
        lines.append("This log does not contain message content, session text,")
        lines.append("API keys, or other sensitive data. It is safe to share")
        lines.append("with developers for diagnosing iCloud sync issues.")
        lines.append(String(repeating: "=", count: 50))
        lines.append("")

        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd HH:mm:ss"
        df.timeZone = .current

        for entry in entries.reversed() {   // oldest first for readability
            let dir = entry.direction.rawValue.uppercased().padding(toLength: 8, withPad: " ", startingAt: 0)
            let time = df.string(from: entry.timestamp)
            let type = entry.recordType

            // Sanitize: truncate record IDs, redact session titles
            let safeId = sanitizeRecordName(entry.recordName)
            let safeSummary = sanitizeSummary(entry.summary, recordType: entry.recordType)
            let device = entry.deviceName ?? "-"

            var line = "[\(time)] \(dir) \(type) \(safeId)"
            if !safeSummary.isEmpty { line += " | \(safeSummary)" }
            line += " | device=\(device)"

            // Include error/conflict details (these are CKError messages, not user data)
            if entry.direction == .error || entry.direction == .conflict {
                line += " | \(entry.detail)"
            }
            lines.append(line)
        }
        return lines.joined(separator: "\n")
    }

    /// Truncate UUIDs in record names to 8-char prefix.
    private func sanitizeRecordName(_ name: String) -> String {
        // "Session:ABC12345-6789-..." → "Session:ABC12345..."
        guard let colonIdx = name.firstIndex(of: ":") else {
            return String(name.prefix(12)) + (name.count > 12 ? "..." : "")
        }
        let idPart = name[name.index(after: colonIdx)...]
        return String(name[...colonIdx]) + String(idPart.prefix(8)) + (idPart.count > 8 ? "..." : "")
    }

    /// Redact user-generated content from summaries.
    private func sanitizeSummary(_ summary: String, recordType: String) -> String {
        switch recordType {
        case "Session":
            return "(session)"            // session titles may contain private info
        case "Message":
            return "(message)"
        case "CompactMarker":
            return "(compact)"
        case "Skill":
            return summary                // skill names are not sensitive
        case "ProviderConfig", "EnvVar":
            return summary                // "provider-config" / "env-vars" — safe labels
        case "SyncDevice":
            return summary                // device names — needed for diagnosis
        default:
            if recordType.hasPrefix("Delete:") {
                return sanitizeSummary(summary, recordType: String(recordType.dropFirst("Delete:".count)))
            }
            return String(summary.prefix(20))
        }
    }

    private func saveToDisk() {
        let persisted = entries.map { PersistEntry(
            timestamp: $0.timestamp, direction: $0.direction.rawValue,
            recordType: $0.recordType, recordName: $0.recordName,
            summary: $0.summary, detail: $0.detail, deviceName: $0.deviceName
        )}
        if let data = try? JSONEncoder().encode(persisted) {
            try? data.write(to: fileURL, options: .atomic)
        }
    }

    private static func load(from url: URL) -> [SyncLogEntry] {
        guard let data = try? Data(contentsOf: url),
              let persisted = try? JSONDecoder().decode([PersistEntry].self, from: data) else { return [] }
        return persisted.compactMap { p in
            guard let dir = SyncLogEntry.Direction(rawValue: p.direction) else { return nil }
            return SyncLogEntry(
                timestamp: p.timestamp, direction: dir,
                recordType: p.recordType, recordName: p.recordName,
                summary: p.summary ?? "", detail: p.detail, deviceName: p.deviceName
            )
        }
    }
}

/// Thread-safe container for pre-built CKRecords, shared between MainActor and nonisolated delegate callbacks.
@available(iOS 17.0, *)
final class PendingRecordChanges: @unchecked Sendable {
    private let lock = NSLock()
    private var records: [CKRecord] = []
    private var deleteIDs: [CKRecord.ID] = []

    func append(records newRecords: [CKRecord], deleteIDs newDeleteIDs: [CKRecord.ID] = []) {
        lock.lock()
        records.append(contentsOf: newRecords)
        deleteIDs.append(contentsOf: newDeleteIDs)
        lock.unlock()
    }

    func appendRecord(_ record: CKRecord) {
        lock.lock()
        records.append(record)
        lock.unlock()
    }

    /// Returns the set of recordNames currently queued. Used to avoid building duplicates.
    func queuedRecordNames() -> Set<String> {
        lock.lock()
        let names = Set(records.map { $0.recordID.recordName })
        lock.unlock()
        return names
    }

    /// Take all pending changes (deduplicated) and clear the buffer. Safe to call from any isolation context.
    func takeAll() -> (records: [CKRecord], deleteIDs: [CKRecord.ID]) {
        lock.lock()
        // Deduplicate by recordID — keep the last occurrence (most recent data)
        var seen = Set<CKRecord.ID>()
        var deduped: [CKRecord] = []
        for record in records.reversed() {
            if seen.insert(record.recordID).inserted {
                deduped.append(record)
            }
        }
        let uniqueDeleteIDs = Array(Set(deleteIDs))
        records = []
        deleteIDs = []
        lock.unlock()
        return (deduped, uniqueDeleteIDs)
    }
}

// MARK: - CloudSyncEngine

@available(iOS 17.0, *)
@MainActor
final class CloudSyncEngine: ObservableObject {
    static let shared = CloudSyncEngine()

    private lazy var container = CKContainer(identifier: "iCloud.com.openminis.app")
    private let devicesZoneName = "devices"

    private var syncEngine: CKSyncEngine?
    private var stateSerialization: CKSyncEngine.State.Serialization?
    private let stateURL: URL
    /// Directory for per-session record etag caches.
    private let sessionCacheDir: URL
    /// Single-file plist of etags for the non-per-session record types
    /// (SyncDevice, Skill, ProviderConfig, EnvVar). Persisted across
    /// restarts so that the next upload re-uses the server etag and
    /// doesn't hit "record to insert already exists" conflict loops.
    private let globalEtagCacheURL: URL
    /// Set when a global etag needs to be flushed to disk.
    private var globalEtagCacheDirty: Bool = false
    /// When non-nil, the engine is in the middle of a `forceFullSync()` call.
    /// `.sentRecordZoneChanges` tallies successful uploads by type into this
    /// dict so we can log a per-type summary when the full sync finishes —
    /// crucial for diagnosing "iPhone full-synced but iPad never received
    /// Skill/EnvVar" reports.
    private var fullSyncUploadCounts: [String: Int]?
    /// Periodic foreground sync timer — fires every 60s to push/pull changes.
    private var foregroundSyncTimer: Timer?
    /// Pending debounced send task — cancelled and replaced on each scheduleSend call.
    private var pendingSendTask: Task<Void, Never>?
    /// Cache server records (preserves etag) for conflict-free re-saves.
    /// Global records (SyncDevice, ProviderConfig, EnvVar) are in-memory only (few records).
    /// Session-scoped records (Session, Message, SessionFile, etc.) are persisted per-session
    /// using lightweight CKRecord system fields (~200 bytes each) so etags survive app restarts
    /// without the O(N) cost of serializing all records into a single file.
    private var serverRecordCache: [CKRecord.ID: CKRecord] = [:]
    /// Session IDs whose per-session cache has been loaded into serverRecordCache.
    private var loadedSessionCaches: Set<String> = []
    /// Session IDs whose per-session cache has unsaved changes.
    private var dirtySessionCaches: Set<String> = []
    /// [T-ios-sync-etag-cache-lru] Access order of loaded per-session caches,
    /// most-recently-used LAST. Used to evict the coldest sessions' etags from
    /// `serverRecordCache` once too many are resident — the etags still live on
    /// disk (`sessionCacheDir/<sessionId>.plist`) and are reloaded on demand by
    /// `ensureSessionCacheLoaded`, so eviction is lossless. Without a bound the
    /// cache grew unbounded (every browsed session's full record set stayed
    /// resident for the whole launch → hundreds of MB of CKRecord/ID/path
    /// strings after a long session).
    private var sessionCacheLRU: [String] = []
    /// Max per-session caches kept resident in memory. Coldest beyond this are
    /// flushed to disk and dropped from `serverRecordCache`.
    private static let maxResidentSessionCaches = 12

    /// Record names for which we've already cleared a stale etag after a
    /// NOT_FOUND and let the dirty scanner re-create them. Bounds recovery to a
    /// single attempt per record while it stays stuck: although a create
    /// (no etag) normally can't return NOT_FOUND, CloudKit can still reject the
    /// recreated record (e.g. a transient masquerade or repeated zone churn), so
    /// without this guard the un-cleared dirty mark would re-create it forever.
    /// Cleared on a successful save so a future legitimate NOT_FOUND (second
    /// peer-delete) is recoverable again. [T-icloud-notfound-retry-loop]
    private var unknownItemEtagCleared: Set<String> = []

    /// Record types that belong to a session (persisted per-session).
    private static let sessionRecordTypes: Set<String> = [
        "Session", "Message", "CompactMarker", "SessionFile",
    ]
    /// Timestamp of last ProviderConfig re-upload trigger — throttles bidirectional sync ping-pong.
    private static var lastProviderConfigReuploadDate: Date = .distantPast
    /// Timestamp of last EnvVar re-upload trigger — throttles bidirectional sync ping-pong.
    private static var lastEnvVarReuploadDate: Date = .distantPast
    /// SHA-256 hash of the last ProviderConfig JSON this device uploaded — used to detect own echoes.
    /// Internal (not private) so the V2 hydrator can stamp it on its own uploads.
    static var lastUploadedProviderConfigHash: String?
    /// SHA-256 hash of the last EnvVar JSON this device uploaded — used to detect own echoes.
    /// Internal (not private) so the V2 hydrator can stamp it on its own uploads.
    static var lastUploadedEnvVarHash: String?
    /// Consecutive SyncDevice save failures — stops immediate re-queue after 3 failures.
    private var deviceRecordFailCount = 0
    /// Tracks whether the current fetch cycle received any remote record changes.
    /// Reset on willFetchChanges, set on fetchedRecordZoneChanges, checked on didFetchChanges.
    private var fetchHadRemoteChanges = false
    /// True when local DB is empty at sync start (fresh install / reinstall).
    /// Allows importing own-zone records from iCloud to restore data.
    private var isRestoringFromCloud = false

    /// Thread-safe storage for pre-computed records, accessible from nonisolated delegate callbacks.
    private let pendingChanges = PendingRecordChanges()
    /// Guard against concurrent sendChanges() calls — CKSyncEngine asserts if re-entered.
    private var isSending = false

    @Published var syncStatus: SyncStatus = .idle
    @Published var knownDevices: [SyncDevice] = []
    @Published var lastSyncDate: Date?
    @Published var isEnabled: Bool {
        didSet {
            UserDefaults.standard.set(isEnabled, forKey: "cloudSync.enabled")
            if isEnabled {
                Task { await start() }
            } else {
                syncEngine = nil
                syncStatus = .disabled
                SyncDirtyScanner.shared.stop()
                stopForegroundSyncTimer()
                pendingSendTask?.cancel()
                pendingSendTask = nil
            }
        }
    }

    @Published var syncSessions: Bool {
        didSet { UserDefaults.standard.set(syncSessions, forKey: "cloudSync.syncSessions") }
    }
    @Published var syncFiles: Bool {
        didSet { UserDefaults.standard.set(syncFiles, forKey: "cloudSync.syncFiles") }
    }
    @Published var syncSkills: Bool {
        didSet { UserDefaults.standard.set(syncSkills, forKey: "cloudSync.syncSkills") }
    }
    @Published var syncProviders: Bool {
        didSet { UserDefaults.standard.set(syncProviders, forKey: "cloudSync.syncProviders") }
    }
    @Published var syncEnvironments: Bool {
        didSet { UserDefaults.standard.set(syncEnvironments, forKey: "cloudSync.syncEnvironments") }
    }

    /// Maximum file size (in bytes) for automatic sync. Files larger than this are skipped.
    /// Default: 1 MB. User-configurable via settings.
    @Published var maxSyncFileSize: Int {
        didSet { UserDefaults.standard.set(maxSyncFileSize, forKey: "cloudSync.maxSyncFileSize") }
    }

    /// Record types that should be synced based on current category toggles.
    var enabledRecordTypes: Set<String> {
        var types = Set<String>()
        if syncSessions { types.formUnion(["Session", "Message", "CompactMarker", "SessionFile"]) }

        if syncSkills { types.insert("Skill") }
        if syncProviders { types.insert("ProviderConfig") }
        if syncEnvironments { types.insert("EnvVar") }
        return types
    }

    // MARK: - Per-Device Sync Preferences (Download)

    /// Whether syncing FROM a specific remote device is enabled.
    func isDeviceSyncEnabled(_ deviceId: String) -> Bool {
        UserDefaults.standard.bool(forKey: "cloudSync.device.\(deviceId).enabled")
    }

    /// Enable or disable syncing FROM a specific remote device.
    /// When enabling for the first time, defaults Sessions to on.
    func setDeviceSyncEnabled(_ deviceId: String, enabled: Bool) {
        let ud = UserDefaults.standard
        let key = "cloudSync.device.\(deviceId).enabled"
        let wasEverSet = ud.object(forKey: key) != nil
        ud.set(enabled, forKey: key)

        // First time enabling: default Sessions + Skills to true, Providers to false
        if enabled && !wasEverSet {
            ud.set(true, forKey: "cloudSync.device.\(deviceId).syncSessions")
            ud.set(true, forKey: "cloudSync.device.\(deviceId).syncSkills")
        }

        objectWillChange.send()
        logger.info("[CloudSync] Device \(deviceId) sync enabled: \(enabled)")
    }

    /// Whether a specific content type is enabled for a remote device.
    func isDeviceContentEnabled(_ deviceId: String, type: String) -> Bool {
        UserDefaults.standard.bool(forKey: "cloudSync.device.\(deviceId).sync\(type)")
    }

    /// Enable or disable a specific content type for a remote device.
    func setDeviceContentEnabled(_ deviceId: String, type: String, enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: "cloudSync.device.\(deviceId).sync\(type)")
        objectWillChange.send()
    }

    /// Returns the set of remote device IDs that have sync enabled.
    func enabledRemoteDeviceIds() -> Set<String> {
        var ids = Set<String>()
        for device in knownDevices {
            if device.id != DeviceIdentity.deviceId && isDeviceSyncEnabled(device.id) {
                ids.insert(device.id)
            }
        }
        return ids
    }

    private init() {
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        self.stateURL = library.appendingPathComponent("MinisChat/sync_state.json")
        self.sessionCacheDir = library.appendingPathComponent("MinisChat/sync_etag_cache")
        self.globalEtagCacheURL = library.appendingPathComponent("MinisChat/sync_etag_global.plist")
        try? FileManager.default.createDirectory(at: sessionCacheDir, withIntermediateDirectories: true)
        self.isEnabled = UserDefaults.standard.bool(forKey: "cloudSync.enabled")

        let ud = UserDefaults.standard
        // Default all categories to true if never set
        if ud.object(forKey: "cloudSync.syncSessions") == nil { ud.set(true, forKey: "cloudSync.syncSessions") }
        if ud.object(forKey: "cloudSync.syncFiles") == nil { ud.set(true, forKey: "cloudSync.syncFiles") }

        if ud.object(forKey: "cloudSync.syncSkills") == nil { ud.set(true, forKey: "cloudSync.syncSkills") }
        if ud.object(forKey: "cloudSync.syncProviders") == nil { ud.set(true, forKey: "cloudSync.syncProviders") }
        if ud.object(forKey: "cloudSync.syncEnvironments") == nil { ud.set(true, forKey: "cloudSync.syncEnvironments") }
        self.syncSessions = ud.bool(forKey: "cloudSync.syncSessions")
        self.syncFiles = ud.bool(forKey: "cloudSync.syncFiles")

        self.syncSkills = ud.bool(forKey: "cloudSync.syncSkills")
        self.syncProviders = ud.bool(forKey: "cloudSync.syncProviders")
        self.syncEnvironments = ud.bool(forKey: "cloudSync.syncEnvironments")
        if ud.object(forKey: "cloudSync.maxSyncFileSize") == nil { ud.set(1_048_576, forKey: "cloudSync.maxSyncFileSize") }
        self.maxSyncFileSize = ud.integer(forKey: "cloudSync.maxSyncFileSize")

        // Load persisted state
        if let data = try? Data(contentsOf: stateURL) {
            self.stateSerialization = try? JSONDecoder().decode(CKSyncEngine.State.Serialization.self, from: data)
        }

        // Migration: remove legacy disk cache (replaced by in-memory-only cache).
        // The old cache could grow to tens of thousands of NSKeyedArchiver'd CKRecords,
        // causing multi-second main-thread blocks on load and save.
        let legacyCacheURL = library.appendingPathComponent("MinisChat/sync_record_cache.bin")
        if FileManager.default.fileExists(atPath: legacyCacheURL.path) {
            try? FileManager.default.removeItem(at: legacyCacheURL)
            logger.info("[CloudSync] Removed legacy sync_record_cache.bin")
        }
    }

    // MARK: - Start / Stop

    func start() async {
        guard isEnabled else {
            syncStatus = .disabled
            return
        }
        guard syncEngine == nil else { return }

        logger.info("[CloudSync] Starting sync engine for device \(DeviceIdentity.zoneName)")

        // Set zone name on ChatStore so markDirty() works
        await ChatStore.shared.setSyncZoneName(DeviceIdentity.zoneName)

        // Detect fresh install: if local DB has no sessions, allow importing
        // own-zone records from iCloud (normally skipped to avoid duplicates).
        let localSessions = await ChatStore.shared.listSessions()
        isRestoringFromCloud = localSessions.isEmpty
        if isRestoringFromCloud {
            logger.info("[CloudSync] Fresh install detected — will import own-zone records from iCloud")
            // Reset backfill key so remote→local merge runs after restore
            UserDefaults.standard.removeObject(forKey: "cloudSync.backfillDone.v1")
        }

        let config: CKSyncEngine.Configuration
        if let state = stateSerialization {
            config = CKSyncEngine.Configuration(
                database: container.privateCloudDatabase,
                stateSerialization: state,
                delegate: self
            )
        } else {
            config = CKSyncEngine.Configuration(
                database: container.privateCloudDatabase,
                stateSerialization: nil,
                delegate: self
            )
        }

        let engine = CKSyncEngine(config)
        self.syncEngine = engine

        // Restore global etags (SyncDevice/Skill/ProviderConfig/EnvVar)
        // before any upload so the first post-launch save reuses the
        // server's existing etag instead of colliding with "record to
        // insert already exists".
        loadGlobalEtagCache()

        // Queue zones + device record via CKSyncEngine pending changes (no direct CloudKit calls)
        queueZonesAndDeviceRecord()

        // Discover and subscribe to remote device zones
        await discoverDeviceZones()

        // One-time backfill: merge remote_sessions/messages into local tables
        // (handles data fetched before mergeRemoteSession was added)
        let backfillKey = "cloudSync.backfillDone.v1"
        if !UserDefaults.standard.bool(forKey: backfillKey) {
            await ChatStore.shared.backfillRemoteToLocal()
            UserDefaults.standard.set(true, forKey: backfillKey)
        }

        // Start periodic dirty scanner for skills
        SyncDirtyScanner.shared.start()

        // Start periodic foreground sync (every 60s)
        startForegroundSyncTimer()

        // Pre-compute pending changes so nextRecordZoneChangeBatch can return them synchronously
        await refreshPendingChanges()
    }

    private func startForegroundSyncTimer() {
        foregroundSyncTimer?.invalidate()
        foregroundSyncTimer = Timer.scheduledTimer(withTimeInterval: 180, repeats: true) { [weak self] _ in
            guard let self else { return }
            Task { @MainActor in
                guard self.syncEngine != nil else { return }
                let t0 = CFAbsoluteTimeGetCurrent()
                logger.info("[CloudSync] Periodic foreground sync")
                // Reset device record fail counter so periodic sync can retry
                self.deviceRecordFailCount = 0
                self.queueDeviceRecord()
                let t1 = CFAbsoluteTimeGetCurrent()
                // Re-discover device zones periodically in case new devices registered
                await self.discoverDeviceZones()
                let t2 = CFAbsoluteTimeGetCurrent()
                await self.triggerSend()
                let t3 = CFAbsoluteTimeGetCurrent()
                await self.triggerFetch()
                let t4 = CFAbsoluteTimeGetCurrent()
                logger.info("[CloudSync] Periodic sync done: queue=\(String(format: "%.0f", (t1-t0)*1000))ms discover=\(String(format: "%.0f", (t2-t1)*1000))ms send=\(String(format: "%.0f", (t3-t2)*1000))ms fetch=\(String(format: "%.0f", (t4-t3)*1000))ms total=\(String(format: "%.0f", (t4-t0)*1000))ms")
            }
        }
    }

    private func stopForegroundSyncTimer() {
        foregroundSyncTimer?.invalidate()
        foregroundSyncTimer = nil
    }

    /// Build CKRecords from dirty records. Must be called OUTSIDE delegate callbacks.
    func refreshPendingChanges() async {
        let refreshStart = CFAbsoluteTimeGetCurrent()
        let enabled = enabledRecordTypes
        let dirtyRecords = await ChatStore.shared.loadDirtyRecords()
            .filter { enabled.contains($0.recordType) }

        // Skip records that are already queued in pendingChanges to avoid
        // building duplicate CKRecords when willSendChanges is called before
        // the previous batch's clearDirtyRecord has completed.
        let alreadyQueued = pendingChanges.queuedRecordNames()
        let filteredDirty = dirtyRecords.filter { dirty in
            let recordName = "\(dirty.recordType):\(dirty.recordId)"
            return !alreadyQueued.contains(recordName)
        }
        guard !filteredDirty.isEmpty else { return }

        // Build CKRecords off the main actor to avoid blocking UI.
        // buildCKRecord reads SQLite (via ChatStore actor), encodes JSON, and may
        // ZIP skill files — all CPU/IO-intensive work that shouldn't run on @MainActor.
        let (records, deleteIDs) = await Task.detached { [weak self] () -> ([CKRecord], [CKRecord.ID]) in
            guard let self else { return ([], []) }
            var records: [CKRecord] = []
            var deleteIDs: [CKRecord.ID] = []

            for dirty in filteredDirty {
                // Guard against empty values — CKRecord.ID throws NSException for empty recordName
                guard !dirty.recordType.isEmpty, !dirty.recordId.isEmpty, !dirty.zoneName.isEmpty else {
                    logger.warning("[CloudSync] Skipping dirty record with empty fields: type='\(dirty.recordType)' id='\(dirty.recordId)' zone='\(dirty.zoneName)'")
                    let recordName = "\(dirty.recordType):\(dirty.recordId)"
                    await ChatStore.shared.clearDirtyRecord(recordName: recordName)
                    continue
                }
                if dirty.operation == "delete" {
                    // Sync the deletion to CloudKit so the cloud record is removed.
                    // Other devices will receive the deletion but won't delete local data —
                    // they just clean up reference tables. The deleted content will only
                    // re-appear in the cloud if it's genuinely modified on another device.
                    let zoneID = CKRecordZone.ID(zoneName: dirty.zoneName)
                    let deleteRecordName = "\(dirty.recordType):\(dirty.recordId)"
                    var deleteRecordID: CKRecord.ID?
                    let deleteOk = noff_try_objc {
                        deleteRecordID = CKRecord.ID(recordName: deleteRecordName, zoneID: zoneID)
                    }
                    guard deleteOk, let deleteRecordID else {
                        logger.error("[CloudSync] CKRecord.ID threw for delete recordName '\(deleteRecordName)' — clearing")
                        await ChatStore.shared.clearDirtyRecord(recordName: deleteRecordName)
                        continue
                    }
                    deleteIDs.append(deleteRecordID)
                    logger.info("[CloudSync] Queued cloud deletion: \(deleteRecordName)")
                } else {
                    if let record = await self.buildCKRecord(type: dirty.recordType, id: dirty.recordId) {
                        records.append(record)
                    } else {
                        // For Skills, nil may mean "not stable yet" — keep dirty for retry.
                        // For other types, nil means the record no longer exists — clear it.
                        if dirty.recordType == "Skill" {
                            let localExists = await MainActor.run { SkillStore.shared.skills.contains { $0.id == dirty.recordId } }
                            if localExists {
                                logger.info("[SkillSync] QUEUE deferred: '\(dirty.recordId)' still exists but unstable — keeping dirty for next cycle")
                                continue
                            } else {
                                logger.info("[SkillSync] QUEUE clear: '\(dirty.recordId)' no longer exists locally — removing dirty entry")
                            }
                        }
                        let recordName = "\(dirty.recordType):\(dirty.recordId)"
                        logger.debug("[CloudSync] buildCKRecord returned nil for \(recordName), clearing stale dirty")
                        await ChatStore.shared.clearDirtyRecord(recordName: recordName)
                    }
                }
            }
            return (records, deleteIDs)
        }.value

        pendingChanges.append(records: records, deleteIDs: deleteIDs)

        if !records.isEmpty || !deleteIDs.isEmpty {
            let refreshMs = (CFAbsoluteTimeGetCurrent() - refreshStart) * 1000
            var typeCounts: [String: Int] = [:]
            for r in records { typeCounts[r.recordType, default: 0] += 1 }
            let breakdown = typeCounts.sorted { $0.value > $1.value }.map { "\($0.key)=\($0.value)" }.joined(separator: " ")
            logger.info("[CloudSync] ⏱ refreshPendingChanges: \(records.count) to save, \(deleteIDs.count) to delete in \(String(format: "%.0f", refreshMs))ms (dirty=\(dirtyRecords.count) filtered=\(filteredDirty.count)) [\(breakdown)]")
        }
    }

    func triggerFetch() async {
        guard let engine = syncEngine else { return }
        syncStatus = .syncing
        // Call engine.fetchChanges() in a detached task to avoid re-entering
        // CKSyncEngine from @MainActor context that may overlap with delegate callbacks.
        do {
            try await Task.detached { [engine] in
                try await engine.fetchChanges()
            }.value
            syncStatus = .idle
            lastSyncDate = Date()
        } catch {
            logger.error("[CloudSync] Fetch error: \(error)")
            syncStatus = .error(error.localizedDescription)
        }
    }

    func triggerSend() async {
        guard let engine = syncEngine else { return }
        guard !isSending else {
            logger.debug("[CloudSync] triggerSend skipped — already sending")
            return
        }
        isSending = true
        let s0 = CFAbsoluteTimeGetCurrent()
        await cleanStalePendingRecords()
        let s1 = CFAbsoluteTimeGetCurrent()
        await refreshPendingChanges()
        let s2 = CFAbsoluteTimeGetCurrent()
        syncStatus = .syncing
        do {
            try await Task.detached { [engine] in
                try await engine.sendChanges()
            }.value
            syncStatus = .idle
            lastSyncDate = Date()
        } catch {
            logger.error("[CloudSync] Send error: \(error)")
            syncStatus = .error(error.localizedDescription)
        }
        let s3 = CFAbsoluteTimeGetCurrent()
        logger.info("[CloudSync] triggerSend: cleanStale=\(String(format: "%.0f", (s1-s0)*1000))ms refresh=\(String(format: "%.0f", (s2-s1)*1000))ms sendChanges=\(String(format: "%.0f", (s3-s2)*1000))ms")
        isSending = false
    }

    /// Schedule a debounced send after a short delay (default 3s).
    /// Cancels any previously scheduled send, coalescing rapid markDirty calls.
    func scheduleSend(delay: TimeInterval = 3.0) {
        guard isEnabled, syncEngine != nil else { return }
        pendingSendTask?.cancel()
        pendingSendTask = Task { [weak self] in
            guard let self else { return }
            do {
                try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            } catch {
                return // cancelled
            }
            await self.triggerSend()
        }
    }

    /// Full sync reset: clear all sync state, caches, and remote reference tables,
    /// then re-fetch everything from iCloud and re-upload all local content.
    /// Local data (sessions, messages, skills, files) is never deleted.
    func forceFullSync() async {
        let prior = await ChatStore.shared.countDirtyRecords()
        logger.info("[CloudSync] forceFullSync START priorDirty total=\(prior.total) byType=\(prior.byType)")
        // Start tallying uploads by type — the .sentRecordZoneChanges delegate
        // checks this and bumps counts so we can print a completion summary.
        fullSyncUploadCounts = [:]
        // 1. Tear down the sync engine
        syncEngine = nil

        // 2. Clear CKSyncEngine persisted state (change tokens) so next fetch is full
        stateSerialization = nil
        try? FileManager.default.removeItem(at: stateURL)

        // 3. Clear server record cache (etags) to avoid stale conflict detection
        serverRecordCache.removeAll()
        loadedSessionCaches.removeAll()
        dirtySessionCaches.removeAll()
        sessionCacheLRU.removeAll()
        globalEtagCacheDirty = false
        try? FileManager.default.removeItem(at: sessionCacheDir)
        try? FileManager.default.removeItem(at: globalEtagCacheURL)
        try? FileManager.default.createDirectory(at: sessionCacheDir, withIntermediateDirectories: true)

        // 4. Clear remote reference tables (will be re-populated by the full fetch).
        //    Preserve sync_dirty_records so pending local changes still get pushed.
        await ChatStore.shared.clearRemoteReferenceTables()
        knownDevices = []

        // 5. Restart engine, full fetch (merges remote → local)
        await start()
        await triggerFetch()

        // 6. Mark all local content as dirty so everything is re-uploaded.
        //    This ensures the cloud gets the latest local state (e.g. skill files
        //    that were missed due to updatedAt mismatches).
        await markAllLocalContentDirty()

        // 7. Push all dirty records (including our SyncDevice record queued by start())
        await triggerSend()

        // Summary of what actually got uploaded during this full-sync window.
        // Warn about any category with zero uploads — that's the signature of
        // the "iPhone full-synced but iPad saw no Skill/EnvVar" bug and the
        // precise diagnostic the user needs to confirm a fix works.
        let uploadCounts = fullSyncUploadCounts ?? [:]
        fullSyncUploadCounts = nil
        logger.info("[CloudSync] forceFullSync upload tally: \(uploadCounts)")
        for category in ["Session", "Message", "CompactMarker", "SessionFile",
                          "Skill", "ProviderConfig", "EnvVar", "SyncDevice"] {
            if (uploadCounts[category] ?? 0) == 0 {
                logger.warning("[CloudSync] forceFullSync: no \(category) uploaded during full sync")
            }
        }

        // 8. Refresh known devices from DB and notify UI
        knownDevices = await ChatStore.shared.listSyncDevices()
            .filter { $0.id != DeviceIdentity.deviceId }
        NotificationCenter.default.post(name: .cloudSyncDidFetchChanges, object: nil)
        logger.info("[CloudSync] forceFullSync DONE")
    }

    /// Re-mark every local record type as dirty for a full re-upload.
    ///
    /// `forceFullSync` runs this after wiping the CKSyncEngine state/etag
    /// caches. If another device has independently deleted the iCloud data
    /// (per the user's reported scenario), the server zone is effectively
    /// empty and this device's content is the only source of truth — so we
    /// need every local Session/Message/CompactMarker/SessionFile/Skill/
    /// ProviderConfig/EnvVar re-queued. A conservative "only if filesystem
    /// is newer than DB" check (the previous behavior) left Sessions/Messages
    /// untouched and broke cross-device restore after an iCloud wipe.
    private func markAllLocalContentDirty() async {
        // Sessions, messages, compact markers, session files, provider config,
        // env vars — ChatStore already has an enumerate-everything helper used
        // by the Delete-iCloud-Data flow. Reuse it so the two "restart sync
        // from scratch" paths stay in sync.
        // force=true bypasses the once-per-install guard — this caller
        // genuinely needs a full re-mark after wiping cloud state.
        await ChatStore.shared.markAllLocalForReupload(force: true)

        // Skills live in SkillStore's own DB, so enumerate them here and mark
        // each one dirty unconditionally. Unlike the previous filesystem
        // mtime check, we must push every skill even when local state matches
        // the DB — the remote zone may have been wiped and is relying on
        // this device to republish.
        let skills = await MainActor.run { SkillStore.shared.skills }
        for skill in skills {
            await ChatStore.shared.markDirty(recordType: "Skill", recordId: skill.id)
            logger.info("[CloudSync] markAllLocalContentDirty Skill marked dirty id=\(skill.id) name=\(skill.name) updatedAt=\(skill.updatedAt)")
        }
        if skills.isEmpty {
            logger.warning("[CloudSync] markAllLocalContentDirty: 0 skills on device — no Skill records will be uploaded")
        }

        // SOUL.md is a per-account singleton — only enqueue when the
        // file actually exists, otherwise the dirty row would just
        // keep cycling through buildSoul returning nil.
        let soulURL = await MainActor.run { SoulStore.fileURL }
        if FileManager.default.fileExists(atPath: soulURL.path) {
            await ChatStore.shared.markDirty(recordType: "SoulV2", recordId: "soul")
            logger.info("[CloudSync] markAllLocalContentDirty Soul marked dirty (file present)")
        } else {
            logger.info("[CloudSync] markAllLocalContentDirty: SOUL.md missing on disk — skipping SoulV2 enqueue")
        }

        // GLOBAL.md + recent daily memory logs. Reuses ForceSyncHelper
        // so the file-presence rules stay in lockstep with the manual
        // "Force iCloud Sync" button on the Memory screen.
        let memoryMarked = await ForceSyncHelper.markMemoryDirty()
        logger.info("[CloudSync] markAllLocalContentDirty Memory rows marked: \(memoryMarked)")

        // Summary: count dirty records broken down by type so we can confirm
        // every category actually got queued for upload.
        let after = await ChatStore.shared.countDirtyRecords()
        logger.info("[CloudSync] markAllLocalContentDirty DONE total=\(after.total) byType=\(after.byType)")
        for category in ["Session", "Message", "CompactMarker", "SessionFile",
                          "Skill", "ProviderConfig", "EnvVar",
                          "SoulV2", "MemoryGlobalV2", "MemoryDailyV2"] {
            if (after.byType[category] ?? 0) == 0 {
                logger.warning("[CloudSync] markAllLocalContentDirty: 0 \(category) records queued — category will not be uploaded")
            }
        }
    }

    /// Destructively delete every record this device has uploaded to iCloud across
    /// all of this user's device zones (not just the local device). Local data is
    /// never touched. After the wipe, the engine restarts and re-uploads this
    /// device's local content into a fresh zone.
    ///
    /// Guarded by two UI confirmations — see CloudSyncSettingsView.
    func deleteAllCloudData() async throws {
        logger.warning("[CloudSync] Delete iCloud Data: STARTING destructive wipe")
        syncStatus = .syncing

        // 1. Tear down the engine and any pending work so nothing races the wipe.
        syncEngine = nil
        stopForegroundSyncTimer()
        SyncDirtyScanner.shared.stop()
        pendingSendTask?.cancel()
        pendingSendTask = nil

        // 2. Enumerate every zone in this user's private database and delete them.
        //    `allRecordZones()` only returns zones owned by the current iCloud user,
        //    so this cannot touch another user's data. We delete *every* zone
        //    (device-* and the shared "devices" zone) — deleting a zone removes
        //    every record it contains in a single server operation.
        let db = container.privateCloudDatabase
        var deletedZones = 0
        var failedZones: [String] = []
        do {
            let zones = try await Task.detached { try await db.allRecordZones() }.value
            logger.warning("[CloudSync] Delete iCloud Data: found \(zones.count) zones to delete")
            for zone in zones {
                do {
                    _ = try await Task.detached {
                        try await db.deleteRecordZone(withID: zone.zoneID)
                    }.value
                    deletedZones += 1
                    logger.warning("[CloudSync] Delete iCloud Data: deleted zone \(zone.zoneID.zoneName)")
                } catch {
                    failedZones.append(zone.zoneID.zoneName)
                    logger.error("[CloudSync] Delete iCloud Data: failed to delete zone \(zone.zoneID.zoneName): \(error)")
                }
            }
        } catch {
            logger.error("[CloudSync] Delete iCloud Data: failed to list zones: \(error)")
            syncStatus = .error("Failed to list iCloud zones")
            throw error
        }

        // 3. Clear all local sync bookkeeping so the next start() begins from scratch.
        //    Local sessions/messages/files are NOT touched — only sync state is cleared.
        stateSerialization = nil
        try? FileManager.default.removeItem(at: stateURL)
        serverRecordCache.removeAll()
        loadedSessionCaches.removeAll()
        dirtySessionCaches.removeAll()
        sessionCacheLRU.removeAll()
        globalEtagCacheDirty = false
        try? FileManager.default.removeItem(at: sessionCacheDir)
        try? FileManager.default.removeItem(at: globalEtagCacheURL)
        try? FileManager.default.createDirectory(at: sessionCacheDir, withIntermediateDirectories: true)
        await ChatStore.shared.clearAllRemoteTables() // also wipes sync_dirty_records
        knownDevices = []
        lastSyncDate = nil

        // 4. Re-mark all local content as dirty so the next send re-populates the
        //    fresh zone from this device's local state. Category toggles still apply.
        // force=true: this is the "Delete iCloud Data" recovery flow,
        // explicitly the case the once-per-install guard exempts.
        await ChatStore.shared.markAllLocalForReupload(force: true)
        // Skills live in SkillStore — enumerate and mark each dirty so they
        // get re-uploaded alongside sessions/messages.
        let skillsForReupload = await MainActor.run { SkillStore.shared.skills }
        for skill in skillsForReupload {
            await ChatStore.shared.markDirty(recordType: "Skill", recordId: skill.id)
            logger.info("[CloudSync] Delete iCloud Data Skill marked dirty id=\(skill.id) name=\(skill.name)")
        }
        if !skillsForReupload.isEmpty {
            logger.info("[CloudSync] Delete iCloud Data: marked \(skillsForReupload.count) skills dirty for reupload")
        } else {
            logger.warning("[CloudSync] Delete iCloud Data: 0 skills on device — no Skill records will be uploaded")
        }
        let dirtyAfter = await ChatStore.shared.countDirtyRecords()
        logger.info("[CloudSync] Delete iCloud Data: dirty records after reupload-mark total=\(dirtyAfter.total) byType=\(dirtyAfter.byType)")

        // 5. Restart the sync engine — it will re-create this device's zone on
        //    first send and push everything we just marked dirty.
        await start()
        await triggerSend()

        if failedZones.isEmpty {
            logger.warning("[CloudSync] Delete iCloud Data: DONE (deleted \(deletedZones) zones, re-uploading local state)")
            syncStatus = .idle
            NotificationCenter.default.post(name: .cloudSyncDidFetchChanges, object: nil)
        } else {
            let msg = "Deleted \(deletedZones) zones; \(failedZones.count) failed"
            logger.error("[CloudSync] Delete iCloud Data: partial failure — \(msg)")
            syncStatus = .error(msg)
        }
    }

    /// Full state wipe for account switches — clears everything including dirty records.
    private func resetSyncState() async {
        syncEngine = nil
        stateSerialization = nil
        try? FileManager.default.removeItem(at: stateURL)
        serverRecordCache.removeAll()
        loadedSessionCaches.removeAll()
        dirtySessionCaches.removeAll()
        sessionCacheLRU.removeAll()
        try? FileManager.default.removeItem(at: sessionCacheDir)
        try? FileManager.default.createDirectory(at: sessionCacheDir, withIntermediateDirectories: true)
        await ChatStore.shared.clearAllRemoteTables()
        knownDevices = []
        lastSyncDate = nil
        syncStatus = .idle
    }

    /// Remove any CKSyncEngine pending saves not backed by a dirty record.
    /// Prevents "ghost" pendings left from crash recovery that block the queue.
    private func cleanStalePendingRecords() async {
        guard let engine = syncEngine else { return }
        let dirtyRecords = await ChatStore.shared.loadDirtyRecords()
        let dirtyNames = Set(dirtyRecords.map { "\($0.recordType):\($0.recordId)" })

        var staleChanges: [CKSyncEngine.PendingRecordZoneChange] = []
        for pending in engine.state.pendingRecordZoneChanges {
            if case .saveRecord(let recordID) = pending {
                let name = recordID.recordName
                if !dirtyNames.contains(name) {
                    staleChanges.append(pending)
                }
            }
        }
        if !staleChanges.isEmpty {
            logger.info("[CloudSync] Removing \(staleChanges.count) stale pending records")
            engine.state.remove(pendingRecordZoneChanges: staleChanges)
        }
    }

    // MARK: - Zone & Device Record (via CKSyncEngine pending changes)

    /// Queue zone creation and device record via CKSyncEngine's pending changes.
    /// Never call `modifyRecordZones` or `modifyRecords` directly — CKSyncEngine owns the database.
    private func queueZonesAndDeviceRecord() {
        guard let engine = syncEngine else { return }

        let deviceZoneID = CKRecordZone.ID(zoneName: DeviceIdentity.zoneName)
        let devicesZoneID = CKRecordZone.ID(zoneName: devicesZoneName)
        engine.state.add(pendingDatabaseChanges: [
            .saveZone(CKRecordZone(zoneID: deviceZoneID)),
            .saveZone(CKRecordZone(zoneID: devicesZoneID))
        ])

        queueDeviceRecord()
        logger.info("[CloudSync] Queued zones + device record")
    }

    /// Queue the SyncDevice record as a pending change for the next send cycle.
    private func queueDeviceRecord() {
        guard let engine = syncEngine else { return }

        let devicesZoneID = CKRecordZone.ID(zoneName: devicesZoneName)

        // [T-ios-deviceid-blank-ckrecordid-crash] This was the ONLY CKRecord.ID
        // construction site with neither a validity pre-check nor an ObjC
        // try/catch — the sibling sites (buildCKRecord, the delete path) already
        // wrap theirs. An empty/whitespace `deviceId` (a blank Keychain payload;
        // fixed at source in DeviceIdentity) made CloudKit raise
        // NSInvalidArgumentException, and an ObjC exception crossing Swift frames
        // is uncatchable — objc_terminate → SIGABRT on the main thread, right
        // after foregrounding, since both the 180s foreground timer and the
        // accountChange handler call this from a @MainActor Task.
        //
        // Kept even though the source is fixed: a device that already stored a bad
        // id keeps whatever DeviceIdentity now returns, and this file must not be
        // one bad input away from aborting the app. Skipping the device record
        // costs only this heartbeat — the next timer tick re-queues it — whereas
        // the alternative is a crash loop the user can only escape by disabling
        // iCloud sync, which is exactly what was reported.
        let deviceRecordName = DeviceIdentity.deviceId
        guard ICloudSharedZoneTransport.isValidCKRecordName(deviceRecordName) else {
            logger.error("[CloudSync] Skipping device record — invalid deviceId recordName \(ICloudSharedZoneTransport.escapeForLog(deviceRecordName))")
            return
        }
        var recordIDOpt: CKRecord.ID?
        let recordIDOk = noff_try_objc {
            recordIDOpt = CKRecord.ID(recordName: deviceRecordName, zoneID: devicesZoneID)
        }
        guard recordIDOk, let recordID = recordIDOpt else {
            logger.error("[CloudSync] CKRecord.ID threw for deviceId \(ICloudSharedZoneTransport.escapeForLog(deviceRecordName)) — skipping device record")
            return
        }

        // Reuse cached server record (preserves etag) to avoid "record already exists" errors
        let record = serverRecordCache[recordID] ?? CKRecord(recordType: "SyncDevice", recordID: recordID)
        record["deviceId"] = DeviceIdentity.deviceId
        record["deviceName"] = DeviceIdentity.deviceName
        record["lastSeen"] = Date()
        record["osVersion"] = DeviceIdentity.osVersion
        record["zoneName"] = DeviceIdentity.zoneName
        // Report which content types this device is uploading
        var uploadTypes: [String] = []
        if syncSessions { uploadTypes.append("Sessions") }
        if syncSkills { uploadTypes.append("Skills") }
        if syncProviders { uploadTypes.append("Providers") }
        if syncEnvironments { uploadTypes.append("Environments") }
        record["uploadTypes"] = uploadTypes.joined(separator: ",")
        pendingChanges.appendRecord(record)

        // Tell the engine there's a pending record change
        engine.state.add(pendingRecordZoneChanges: [
            .saveRecord(recordID)
        ])
    }

    // MARK: - CKRecord ↔ Local DB Mapping

    func buildCKRecord(type: String, id: String) async -> CKRecord? {
        guard !type.isEmpty, !id.isEmpty else {
            logger.warning("[CloudSync] buildCKRecord called with empty type or id")
            return nil
        }
        let recordName = "\(type):\(id)"
        let zoneID = CKRecordZone.ID(zoneName: DeviceIdentity.zoneName)
        // CKRecord.ID throws NSException (not Swift Error) for invalid recordName —
        // wrap in ObjC @try/@catch to prevent crash loop
        var recordID: CKRecord.ID?
        let ok = noff_try_objc {
            recordID = CKRecord.ID(recordName: recordName, zoneID: zoneID)
        }
        guard ok, let recordID else {
            logger.error("[CloudSync] CKRecord.ID threw for recordName '\(recordName)' — skipping")
            return nil
        }
        // Ensure per-session etag cache is loaded before lookup
        if Self.sessionRecordTypes.contains(type),
           let sessionId = Self.extractSessionId(from: recordName, type: type) {
            await MainActor.run { ensureSessionCacheLoaded(sessionId) }
        }
        // Reuse cached server record (preserves etag) to avoid "record already exists" errors
        let record = serverRecordCache[recordID] ?? CKRecord(recordType: type, recordID: recordID)

        switch type {
        case "Session":
            guard let session = await ChatStore.shared.getSession(id) else { return nil }
            record["sessionId"] = session.id
            record["title"] = session.title
            record["category"] = session.category
            record["modelId"] = session.modelId
            record["createdAt"] = session.createdAt
            record["updatedAt"] = session.updatedAt
            // Sync additional session state
            let extras = await ChatStore.shared.getSessionExtras(id)
            record["memoryEnabled"] = extras.memoryEnabled ? 1 : 0
            record["modelBinding"] = extras.modelBinding
            // pinnedAt: 0 = explicitly unpinned, nil = never set (old device), positive = pinned timestamp
            if let pinnedAt = session.pinnedAt {
                record["pinnedAt"] = pinnedAt
            } else {
                // Send 0 sentinel to propagate "not pinned" state
                record["pinnedAt"] = Date(timeIntervalSince1970: 0)
            }

        case "CompactMarker":
            guard let marker = await ChatStore.shared.getCompactMarker(id: id) else { return nil }
            record["markerId"] = marker.id
            record["sessionId"] = marker.sessionId
            record["summary"] = marker.summary
            record["firstKeptSortOrder"] = marker.firstKeptSortOrder
            record["compactedCount"] = marker.compactedCount
            record["createdAt"] = marker.createdAt
            if let uiBoundary = marker.uiBoundarySortOrder {
                record["uiBoundarySortOrder"] = uiBoundary
            }
            // Legacy id field (pre-Phase A name). Send both for cross-version compat.
            if let bmId = marker.boundaryMessageId {
                record["boundaryMessageId"] = bmId
            }
            // Phase A: primary id-based boundary fields.
            if let fkmId = marker.firstKeptMessageId {
                record["firstKeptMessageId"] = fkmId
            }
            if let lcmId = marker.lastCompactedMessageId {
                record["lastCompactedMessageId"] = lcmId
            }

        case "Message":
            guard let msg = await ChatStore.shared.loadSingleMessage(id: id) else { return nil }
            record["messageId"] = msg.id
            record["sessionId"] = msg.sessionId
            record["role"] = msg.role.rawValue
            record["createdAt"] = msg.createdAt

            // Encode parts
            if let partsData = try? JSONEncoder().encode(msg.parts) {
                let partsString = String(data: partsData, encoding: .utf8) ?? "[]"
                // If >750KB, store as asset
                if partsData.count > 750_000 {
                    let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent("\(msg.id)_parts.json")
                    try? partsData.write(to: tempURL)
                    record["partsAsset"] = CKAsset(fileURL: tempURL)
                    record["partsJson"] = nil
                } else {
                    record["partsJson"] = partsString
                }
            }

            if let usage = msg.tokenUsage, let usageData = try? JSONEncoder().encode(usage) {
                record["tokenUsageJson"] = String(data: usageData, encoding: .utf8)
            }
            record["reasoningContent"] = msg.reasoningContent
            record["streamInterruptCount"] = msg.streamInterruptCount
            record["sortOrder"] = await ChatStore.shared.messageSortOrder(id: id)
            record["updatedAt"] = await ChatStore.shared.messageUpdatedAt(id: id)

        case "SessionFile":
            // id format: "sessionId:relativePath" (e.g. "ABC123:workspace/notes.md")
            let parts = id.split(separator: ":", maxSplits: 1)
            guard parts.count == 2 else { return nil }
            let sessionId = String(parts[0])
            let relativePath = String(parts[1])
            let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
            let fileURL = library.appendingPathComponent("MinisChat/minis/\(sessionId)/\(relativePath)")
            guard FileManager.default.fileExists(atPath: fileURL.path),
                  FileManager.default.isReadableFile(atPath: fileURL.path) else { return nil }
            let attrs = try? FileManager.default.attributesOfItem(atPath: fileURL.path)
            let fileSize = (attrs?[.size] as? Int) ?? 0
            if fileSize > maxSyncFileSize {
                logger.info("[CloudSync] Skipping session file (too large: \(fileSize)): \(relativePath)")
                return nil
            }
            record["sessionId"] = sessionId
            record["relativePath"] = relativePath
            record["asset"] = CKAsset(fileURL: fileURL)
            record["mimeType"] = Self.mimeType(for: fileURL.pathExtension)
            record["fileSize"] = fileSize

        case "Skill":
            guard let skill = await MainActor.run(body: { SkillStore.shared.skills.first { $0.id == id } }) else {
                logger.info("[SkillSync] SEND skip: skill '\(id)' not found locally (deleted?)")
                return nil
            }

            logger.info("[SkillSync] SEND begin: '\(skill.name)' id=\(id) version=\(skill.version) updatedAt=\(skill.updatedAt)")

            // Skip if any file was modified within the last 60s (skill may still be installing).
            let isStable = await MainActor.run { SkillStore.shared.isSkillStable(id) }
            if !isStable {
                logger.info("[SkillSync] SEND deferred: '\(skill.name)' has recently modified files — will retry next cycle")
                return nil   // handled below: caller preserves dirty entry for Skill type
            }

            record["skillId"] = skill.id
            record["name"] = skill.name
            record["description"] = skill.description
            record["version"] = skill.version
            record["importSource"] = skill.importSource.dbValue
            record["isEnabled"] = skill.isEnabled ? 1 : 0
            record["installedAt"] = skill.installedAt

            // Use the filesystem's max modification date if it's newer than the DB's
            // updatedAt. This covers files created outside SkillStore (e.g. via iSH shell)
            // that don't bump updatedAt in the DB.
            let fsMaxDate = await MainActor.run { SyncDirtyScanner.maxModDate(inSkill: id) }
            var effectiveUpdatedAt = skill.updatedAt
            if fsMaxDate > skill.updatedAt {
                effectiveUpdatedAt = fsMaxDate
                logger.info("[SkillSync] SEND updatedAt bumped: '\(skill.name)' DB=\(skill.updatedAt) → FS=\(fsMaxDate)")
                await MainActor.run { SkillStore.shared.bumpUpdatedAt(skillId: id, to: fsMaxDate) }
            }
            record["updatedAt"] = effectiveUpdatedAt

            if let body = await MainActor.run(body: { SkillStore.shared.readSkillContent(skill.id) }) {
                record["bodyText"] = body
                logger.info("[SkillSync] SEND body: '\(skill.name)' SKILL.md \(body.count) chars")
            } else {
                logger.warning("[SkillSync] SEND warning: '\(skill.name)' has no SKILL.md content")
            }

            // Pack all skill files (SKILL.md + scripts/references/assets) into a ZIP CKAsset
            if let zipData = await MainActor.run(body: { SkillStore.shared.buildSkillZipData(id) }) {
                let tempURL = FileManager.default.temporaryDirectory
                    .appendingPathComponent("skill-sync-\(id).zip")
                try? zipData.write(to: tempURL)
                record["bundleAsset"] = CKAsset(fileURL: tempURL)
                logger.info("[SkillSync] SEND bundle: '\(skill.name)' ZIP \(zipData.count) bytes")
            } else {
                logger.info("[SkillSync] SEND bundle: '\(skill.name)' no bundled files (SKILL.md only)")
            }

            logger.info("[SkillSync] SEND ready: '\(skill.name)' record prepared for upload")

        case "ProviderConfig":
            // Sync the provider config JSON (instances, model entries, groups, defaults).
            //
            // Upload filter: strip non-user-modified modelEntries before serializing
            // for iCloud. API-truth entries (what the local `replaceEntries` fetched
            // from each provider's API) are per-device and must not cross devices —
            // letting them propagate produces ghost lists that disagree with what
            // a local Refresh would produce. Only entries carrying actual user
            // intent (custom additions, overrides, isHidden) are sent.
            //
            // The local on-disk `provider-config.json` is left untouched; we only
            // re-serialize a filtered copy for the wire.
            let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
            let configURL = library.appendingPathComponent("MinisChat/provider-config.json")
            guard let rawData = try? Data(contentsOf: configURL),
                  var uploadConfig = try? JSONDecoder().decode(ProviderConfig.self, from: rawData) else {
                return nil
            }
            let beforeEntryCount = uploadConfig.modelEntries.count
            uploadConfig.modelEntries = uploadConfig.modelEntries.filter(\.isUserModified)
            let afterEntryCount = uploadConfig.modelEntries.count
            guard let data = try? JSONEncoder().encode(uploadConfig) else { return nil }
            let configJson = String(data: data, encoding: .utf8) ?? "{}"
            logger.info("[ModelList] buildCKRecord (upload): entries filtered \(beforeEntryCount) -> \(afterEntryCount) (dropped \(beforeEntryCount - afterEntryCount) API-truth entries)")
            // Record hash so we can detect our own echo on receive
            Self.lastUploadedProviderConfigHash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            // If >750KB, store as asset
            if data.count > 750_000 {
                let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent("provider-config-sync.json")
                try? data.write(to: tempURL)
                record["configAsset"] = CKAsset(fileURL: tempURL)
                record["configJson"] = nil
            } else {
                record["configJson"] = configJson
            }
            // Also export API keys as base64 so new devices get them immediately
            // (iCloud Keychain sync can lag behind CloudKit by minutes/hours).
            record["secretsJson"] = Self.exportProviderSecrets(configJson: configJson)
            record["updatedAt"] = Date()

        case "EnvVar":
            // Sync env var keys and base64-encoded values.
            let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
            let envURL = library.appendingPathComponent("MinisChat/env-vars.json")
            guard let data = try? Data(contentsOf: envURL) else { return nil }
            let envJson = String(data: data, encoding: .utf8) ?? "[]"
            // Record hash so we can detect our own echo on receive
            Self.lastUploadedEnvVarHash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            record["envVarsJson"] = envJson
            // Also export env var values as base64 for immediate availability on new devices.
            record["envSecretsJson"] = Self.exportEnvVarSecrets(envJson: envJson)
            record["updatedAt"] = Date()

        default:
            return nil
        }

        return record
    }

    // MARK: - Process Remote Records

    func processRemoteRecord(_ record: CKRecord) async {
        let deviceId = record.recordID.zoneID.zoneName.replacingOccurrences(of: "device-", with: "")

        // Skip records from our own device zone (we already have the data locally),
        // except SyncDevice records which update our own device info.
        // When restoring from cloud (fresh install), import own-zone records too.
        if deviceId == DeviceIdentity.deviceId && record.recordType != "SyncDevice" && !isRestoringFromCloud {
            return
        }

        switch record.recordType {
        case "SyncDevice":
            let device = SyncDevice(
                id: record["deviceId"] as? String ?? record.recordID.recordName,
                deviceName: record["deviceName"] as? String ?? "Unknown",
                zoneName: record["zoneName"] as? String ?? "",
                lastSeen: record["lastSeen"] as? Date ?? Date(),
                osVersion: record["osVersion"] as? String ?? "",
                uploadTypes: (record["uploadTypes"] as? String ?? "").components(separatedBy: ",").filter { !$0.isEmpty }
            )
            logger.info("[iCloud] processRemoteRecord SyncDevice: id=\(device.id) name=\(device.deviceName) zone=\(device.zoneName) upload=\(device.uploadTypes)")
            await ChatStore.shared.upsertSyncDevice(device)
            if let idx = knownDevices.firstIndex(where: { $0.id == device.id }) {
                knownDevices[idx] = device
            } else if device.id != DeviceIdentity.deviceId {
                knownDevices.append(device)
            }

        case "Session":
            // Parse pinnedAt: nil = old device (field absent), 0 = explicitly unpinned, >0 = pinned
            let remotePinnedAt: Date? = {
                guard let date = record["pinnedAt"] as? Date else { return nil }
                return date.timeIntervalSince1970 > 0 ? date : nil
            }()
            var session = ChatSession(
                id: record["sessionId"] as? String ?? "",
                title: record["title"] as? String,
                category: record["category"] as? String,
                modelId: record["modelId"] as? String ?? "unknown",
                createdAt: record["createdAt"] as? Date ?? Date(),
                updatedAt: record["updatedAt"] as? Date ?? Date()
            )
            session.pinnedAt = remotePinnedAt
            let memoryEnabled = (record["memoryEnabled"] as? Int ?? 1) == 1
            let modelBinding = record["modelBinding"] as? String
            // remotePinnedAtRaw distinguishes "old device / field absent" (nil) from "explicitly unpinned" (0)
            let remotePinnedAtRaw = record["pinnedAt"] as? Date
            logger.info("[iCloud] processRemoteRecord Session: id=\(session.id) title=\(session.title ?? "nil") deviceId=\(deviceId)")
            // Merge into local sessions (last-write-wins by updated_at)
            await ChatStore.shared.mergeRemoteSession(session, fromDeviceId: deviceId, memoryEnabled: memoryEnabled, modelBinding: modelBinding, remotePinnedAtRaw: remotePinnedAtRaw)
            // Also keep in remote_sessions for browsing/reference
            await ChatStore.shared.upsertRemoteSession(session, deviceId: deviceId)

        case "CompactMarker":
            let marker = CompactMarker(
                id: record["markerId"] as? String ?? record.recordID.recordName,
                sessionId: record["sessionId"] as? String ?? "",
                summary: record["summary"] as? String ?? "",
                firstKeptSortOrder: record["firstKeptSortOrder"] as? Int ?? 0,
                compactedCount: record["compactedCount"] as? Int ?? 0,
                createdAt: record["createdAt"] as? Date ?? Date(),
                uiBoundarySortOrder: record["uiBoundarySortOrder"] as? Int,
                boundaryMessageId: record["boundaryMessageId"] as? String,
                firstKeptMessageId: record["firstKeptMessageId"] as? String,
                lastCompactedMessageId: record["lastCompactedMessageId"] as? String
            )
            logger.info("[iCloud] processRemoteRecord CompactMarker: id=\(marker.id) session=\(marker.sessionId)")
            await ChatStore.shared.mergeRemoteCompactMarker(marker)

        case "Message":
            let partsJson: String
            if let asset = record["partsAsset"] as? CKAsset, let fileURL = asset.fileURL,
               let data = try? Data(contentsOf: fileURL) {
                partsJson = String(data: data, encoding: .utf8) ?? "[]"
            } else {
                partsJson = record["partsJson"] as? String ?? "[]"
            }

            let messageId = record["messageId"] as? String ?? ""
            let sessionId = record["sessionId"] as? String ?? ""
            let role = record["role"] as? String ?? "user"
            let createdAt = record["createdAt"] as? Date ?? Date()
            let tokenUsageJson = record["tokenUsageJson"] as? String
            let sortOrder = record["sortOrder"] as? Int ?? 0
            let reasoningContent = record["reasoningContent"] as? String
            let streamInterruptCount = record["streamInterruptCount"] as? Int ?? 0
            // updatedAt may be nil when sent from older app versions — fallback handled by mergeRemoteMessage
            let updatedAt = record["updatedAt"] as? Date

            // Keep in remote_messages for reference
            await ChatStore.shared.upsertRemoteMessage(
                id: messageId, deviceId: deviceId, sessionId: sessionId,
                role: role, partsJson: partsJson, createdAt: createdAt,
                tokenUsageJson: tokenUsageJson, sortOrder: sortOrder,
                reasoningContent: reasoningContent, streamInterruptCount: streamInterruptCount
            )
            // Merge into local messages if the session exists locally (was merged via mergeRemoteSession)
            await ChatStore.shared.mergeRemoteMessage(
                id: messageId, sessionId: sessionId, role: role, partsJson: partsJson,
                createdAt: createdAt, tokenUsageJson: tokenUsageJson, sortOrder: sortOrder,
                reasoningContent: reasoningContent, streamInterruptCount: streamInterruptCount,
                updatedAt: updatedAt
            )

        case "SessionFile":
            let sessionId = record["sessionId"] as? String ?? ""
            let relativePath = record["relativePath"] as? String ?? ""
            if let asset = record["asset"] as? CKAsset, let srcURL = asset.fileURL {
                let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
                let destURL = library.appendingPathComponent("MinisChat/minis/\(sessionId)/\(relativePath)")
                let fm = FileManager.default
                try? fm.createDirectory(at: destURL.deletingLastPathComponent(), withIntermediateDirectories: true)
                // Overwrite if exists (last-write-wins)
                if fm.fileExists(atPath: destURL.path) { try? fm.removeItem(at: destURL) }
                try? fm.copyItem(at: srcURL, to: destURL)
                logger.info("[iCloud] synced session file: \(sessionId)/\(relativePath)")
            }

        case "Skill":
            let skillId = record["skillId"] as? String ?? ""
            let name = record["name"] as? String ?? ""
            let description = record["description"] as? String ?? ""
            let version = record["version"] as? String ?? "1.0.0"
            let importSource = record["importSource"] as? String ?? "file"
            let isEnabled = (record["isEnabled"] as? Int ?? 1) == 1
            let installedAt = record["installedAt"] as? Date ?? Date()
            let updatedAt = record["updatedAt"] as? Date ?? Date()
            let bodyText = record["bodyText"] as? String ?? ""

            logger.info("[SkillSync] RECV begin: '\(name)' id=\(skillId) version=\(version) source=\(importSource) updatedAt=\(updatedAt) from device=\(deviceId)")
            logger.info("[SkillSync] RECV body: '\(name)' SKILL.md \(bodyText.count) chars")

            // Extract bundled files ZIP if present
            var zipData: Data?
            if let asset = record["bundleAsset"] as? CKAsset, let fileURL = asset.fileURL {
                zipData = try? Data(contentsOf: fileURL)
                let fileCount: Int
                if let zd = zipData, let entries = try? SkillStore.readZipEntries(data: zd) {
                    fileCount = entries.filter { !$0.isDirectory && $0.name != "SKILL.md" }.count
                } else {
                    fileCount = -1
                }
                logger.info("[SkillSync] RECV bundle: '\(name)' ZIP \(zipData?.count ?? 0) bytes, \(fileCount) bundled files")
            } else {
                logger.info("[SkillSync] RECV bundle: '\(name)' no bundleAsset (SKILL.md only — old sender or no bundled files)")
            }

            // Keep in remote_skills for reference
            await ChatStore.shared.upsertRemoteSkill(
                id: skillId, deviceId: deviceId, name: name, description: description,
                version: version, importSource: importSource, isEnabled: isEnabled,
                installedAt: installedAt, updatedAt: updatedAt, bodyText: bodyText
            )

            // Merge into local skills using remote skillId directly (last-write-wins)
            let localSkill = await MainActor.run { SkillStore.shared.skills.first { $0.id == skillId } }
            let source = SkillImportSource.fromDB(importSource)
            if let local = localSkill {
                if updatedAt > local.updatedAt {
                    logger.info("[SkillSync] RECV merge UPDATE: '\(name)' remote(\(updatedAt)) > local(\(local.updatedAt)) — applying")
                    await MainActor.run {
                        SkillStore.shared.importSkillFromSyncWithAsset(
                            skillId: skillId, content: bodyText, zipData: zipData,
                            source: source,
                            isEnabled: isEnabled, installedAt: installedAt, updatedAt: updatedAt
                        )
                    }
                    logger.info("[SkillSync] RECV merge UPDATE done: '\(name)'")
                } else {
                    logger.info("[SkillSync] RECV merge SKIP: '\(name)' local(\(local.updatedAt)) >= remote(\(updatedAt)) — keeping local")
                }
            } else {
                logger.info("[SkillSync] RECV merge INSERT: '\(name)' id=\(skillId) — new skill from remote")
                await MainActor.run {
                    SkillStore.shared.importSkillFromSyncWithAsset(
                        skillId: skillId, content: bodyText, zipData: zipData,
                        source: source,
                        isEnabled: isEnabled, installedAt: installedAt, updatedAt: updatedAt
                    )
                }
                logger.info("[SkillSync] RECV merge INSERT done: '\(name)'")
            }

        case "ProviderConfig":
            let configJson: String
            if let asset = record["configAsset"] as? CKAsset, let fileURL = asset.fileURL,
               let data = try? Data(contentsOf: fileURL) {
                configJson = String(data: data, encoding: .utf8) ?? "{}"
            } else {
                configJson = record["configJson"] as? String ?? "{}"
            }
            let updatedAt = record["updatedAt"] as? Date ?? Date()

            // Import API keys from base64 if Keychain doesn't have them yet.
            if let secretsJson = record["secretsJson"] as? String {
                Self.importProviderSecrets(secretsJson: secretsJson)
            }

            // Keep in remote table for reference
            await ChatStore.shared.upsertRemoteProviderConfig(
                deviceId: deviceId, configJson: configJson, updatedAt: updatedAt
            )

            // Merge: union instances/modelEntries/modelGroups by ID, prefer newer file for conflicts
            await Self.mergeProviderConfig(remoteJson: configJson)

        case "EnvVar":
            let envVarsJson = record["envVarsJson"] as? String ?? "[]"
            let updatedAt = record["updatedAt"] as? Date ?? Date()

            // Import env var values from base64 if Keychain doesn't have them yet.
            if let envSecretsJson = record["envSecretsJson"] as? String {
                Self.importEnvVarSecrets(secretsJson: envSecretsJson)
            }

            await ChatStore.shared.upsertRemoteEnvVars(
                deviceId: deviceId, envVarsJson: envVarsJson, updatedAt: updatedAt
            )

            // Merge: union env vars by ID, prefer newer file for conflicts
            await MainActor.run {
                Self.mergeEnvVars(remoteJson: envVarsJson)
            }

        default:
            // V2 record types live in v2's shared zone and are handled by
            // ICloudSharedZoneTransport. CKSyncEngine still hands them to
            // v1's fetched-changes delegate when v1 fetches anything from
            // minis-shared (e.g. when v1 is briefly active before pause
            // takes effect). Silently ignore — warning would spam the log
            // tens of thousands of times during migration.
            if record.recordType.hasSuffix("V2") {
                return
            }
            logger.warning("[CloudSync] Unknown record type: \(record.recordType)")
        }
    }

    /// Process a remote deletion.
    /// - `deletedSessionIds`: session IDs whose Session record is also deleted in this batch.
    ///   When a whole session is deleted, its child Message deletes are ignored (the session
    ///   stays intact on other devices). When only individual messages are deleted (retry,
    ///   manual delete), the deletion propagates to keep message lists consistent.
    func processRemoteDeletion(_ recordID: CKRecord.ID, recordType: CKRecord.RecordType, deletedSessionIds: Set<String> = []) async {
        let deviceId = recordID.zoneID.zoneName.replacingOccurrences(of: "device-", with: "")
        let name = recordID.recordName
        // recordName format is "Type:id"
        let id = name.contains(":") ? String(name.split(separator: ":", maxSplits: 1).last ?? "") : name

        switch recordType {
        case "Message":
            // Always clean up the remote reference table.
            await ChatStore.shared.deleteRemoteMessage(id: id, deviceId: deviceId)

            // Determine if this is a message-level deletion (retry / manual) vs.
            // a cascading deletion from a whole-session delete.
            // recordName = "Message:sessionId:messageId"
            let components = id.split(separator: ":", maxSplits: 1)
            let sessionId = components.count > 1 ? String(components[0]) : ""
            let messageId = components.count > 1 ? String(components[1]) : id

            if !sessionId.isEmpty && deletedSessionIds.contains(sessionId) {
                // Session itself is being deleted in this batch — don't touch local messages.
                logger.info("[iCloud] Skipping Message deletion (session also deleted): '\(messageId)' session=\(sessionId) device=\(deviceId)")
            } else {
                // Individual message deletion — propagate to local DB to keep lists in sync.
                await ChatStore.shared.deleteLocalMessage(messageId: messageId)
                logger.info("[iCloud] Propagated remote Message deletion: '\(messageId)' session=\(sessionId) device=\(deviceId)")
            }
        case "Session":
            // Session deletions are never propagated — other devices keep their copy.
            await ChatStore.shared.deleteRemoteSession(id: id, deviceId: deviceId)
            logger.info("[iCloud] Ignoring remote Session deletion: '\(id)' from device=\(deviceId)")
        case "Skill":
            await ChatStore.shared.deleteRemoteSkill(id: id, deviceId: deviceId)
            logger.info("[SkillSync] RECV delete ignored: '\(id)' from device=\(deviceId)")
        case "ProviderConfig":
            await ChatStore.shared.deleteRemoteProviderConfig(deviceId: deviceId)
            logger.info("[iCloud] Ignoring remote ProviderConfig deletion: '\(id)' from device=\(deviceId)")
        case "EnvVar":
            await ChatStore.shared.deleteRemoteEnvVars(deviceId: deviceId)
            logger.info("[iCloud] Ignoring remote EnvVar deletion: '\(id)' from device=\(deviceId)")
        default:
            logger.info("[iCloud] Ignoring remote deletion for unknown type '\(recordType)': '\(id)' from device=\(deviceId)")
        }
    }

    // MARK: - Discover Remote Devices

    /// Subscribe to remote device zones so CKSyncEngine fetches their changes.
    /// Called outside delegate callbacks (e.g. from start or on foreground).
    func discoverDeviceZones() async {
        do {
            // Run the network call off MainActor to avoid resume-hop CPU spike.
            let ownZoneName = DeviceIdentity.zoneName
            let db = container.privateCloudDatabase
            let newZoneIDs: [CKRecordZone.ID] = try await Task.detached {
                let zones = try await db.allRecordZones()
                return zones.compactMap { zone -> CKRecordZone.ID? in
                    let name = zone.zoneID.zoneName
                    guard name.hasPrefix("device-"), name != ownZoneName else { return nil }
                    return zone.zoneID
                }
            }.value
            for zid in newZoneIDs {
                logger.info("[CloudSync] Found remote zone: \(zid.zoneName)")
            }
            if !newZoneIDs.isEmpty, let engine = syncEngine {
                engine.state.add(pendingDatabaseChanges: newZoneIDs.map {
                    .saveZone(CKRecordZone(zoneID: $0))
                })
            }
        } catch {
            logger.error("[CloudSync] Failed to discover zones: \(error)")
        }
    }

    // MARK: - State Persistence

    private func saveState(_ stateSer: CKSyncEngine.State.Serialization) {
        self.stateSerialization = stateSer
        if let data = try? JSONEncoder().encode(stateSer) {
            try? data.write(to: stateURL)
        }
    }

    /// Classify a `CKError` as transient (worth retrying) vs permanent
    /// (drop the dirty mark). Mirrors Apple's "retry these and the cloud
    /// might accept on the next attempt" guidance for CKSyncEngine. Without
    /// this distinction, a single `.networkFailure` / TLS blip on an upload
    /// batch would clear the local dirty marks for every record in the
    /// batch, stranding those rows on disk forever — they are never
    /// uploaded again because nothing re-marks them dirty. See
    /// T-icloudsync-diag (8 messages lost between iPad and iPhone after a
    /// 12:19:21 NSURLErrorDomain -1200 batch).
    nonisolated private static func isTransientCKError(_ error: CKError) -> Bool {
        switch error.code {
        case .networkUnavailable,
             .networkFailure,
             .serviceUnavailable,
             .requestRateLimited,
             .zoneBusy,
             .resultsTruncated,
             .changeTokenExpired,
             .partialFailure,
             .internalError:
            return true
        default:
            return false
        }
    }

    /// Extract a human-readable summary from a CKRecord for sync log display.
    private static func summaryForRecord(_ record: CKRecord) -> String {
        switch record.recordType {
        case "Session":
            return record["title"] as? String ?? "Untitled"
        case "Message":
            let sessionId = record["sessionId"] as? String ?? ""
            let role = record["role"] as? String ?? ""
            return "\(role) in \(sessionId.prefix(8))..."
        case "SessionFile":
            return record["relativePath"] as? String ?? ""
        case "Skill":
            return record["name"] as? String ?? record["skillId"] as? String ?? ""
        case "CompactMarker":
            return "session \(( record["sessionId"] as? String ?? "").prefix(8))..."
        case "ProviderConfig":
            return "provider-config"
        case "EnvVar":
            return "env-vars"
        case "SyncDevice":
            return record["deviceName"] as? String ?? ""
        default:
            return ""
        }
    }

    /// Extract summary from a record name (for sent records where we don't have the full CKRecord).
    private static func summaryForRecordName(_ name: String, type: String) -> String {
        let id = name.contains(":") ? String(name.split(separator: ":", maxSplits: 1).last ?? "") : name
        switch type {
        case "Session":
            // Look up title from DB
            return id.prefix(8) + "..."
        case "SessionFile":
            return id // "sessionId:workspace/file.md"
        case "Skill":
            return id
        default:
            return id.prefix(12) + (id.count > 12 ? "..." : "")
        }
    }

    private static func mimeType(for ext: String) -> String {
        switch ext.lowercased() {
        case "png": return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        case "pdf": return "application/pdf"
        case "txt": return "text/plain"
        case "json": return "application/json"
        case "mp4": return "video/mp4"
        case "mp3": return "audio/mpeg"
        case "wav": return "audio/wav"
        default: return "application/octet-stream"
        }
    }

    // MARK: - Provider Config & EnvVar Merge

    // MARK: - Secret Export/Import (base64 via CloudKit)

    /// Export provider API keys as JSON:
    ///   [{"instanceId": "...", "apiKey": "<b64>", "updatedAt": <unix_ts>}]
    ///
    /// `updatedAt` is the local UserDefaults-tracked time this key was last saved
    /// via `ProviderKeychainHelper.saveAPIKey`. It lets receiving devices do
    /// last-write-wins on key rotation — without this, the import path defaults
    /// to "only write if empty", silently blocking any rotated key from
    /// propagating across devices.
    static func exportProviderSecrets(configJson: String) -> String {
        guard let data = configJson.data(using: .utf8),
              let config = try? JSONDecoder().decode(ProviderConfig.self, from: data) else { return "[]" }
        var secrets: [[String: Any]] = []
        for instance in config.instances {
            if let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id), !key.isEmpty {
                let b64 = Data(key.utf8).base64EncodedString()
                let savedAt = ProviderKeychainHelper.apiKeySavedAt(instanceId: instance.id)
                secrets.append([
                    "instanceId": instance.id,
                    "apiKey": b64,
                    "updatedAt": savedAt.timeIntervalSince1970
                ])
            }
        }
        guard let jsonData = try? JSONSerialization.data(withJSONObject: secrets),
              let jsonStr = String(data: jsonData, encoding: .utf8) else { return "[]" }
        return jsonStr
    }

    /// Import provider API keys from JSON. Conflict resolution (per instance):
    ///   - Keychain empty locally → always write.
    ///   - Remote entry has no `updatedAt` (legacy payload from old app version) →
    ///     write only if local is empty (current behavior for pre-LWW data).
    ///   - Remote `updatedAt` > local `savedAt` → remote is newer, overwrite.
    ///   - Otherwise → skip (local is newer or equal).
    static func importProviderSecrets(secretsJson: String) {
        guard let data = secretsJson.data(using: .utf8),
              let secrets = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return }
        for entry in secrets {
            guard let instanceId = entry["instanceId"] as? String,
                  let b64Key = entry["apiKey"] as? String,
                  let keyData = Data(base64Encoded: b64Key),
                  let key = String(data: keyData, encoding: .utf8),
                  !key.isEmpty else { continue }

            let remoteUpdatedAt: Date? = {
                if let n = entry["updatedAt"] as? Double { return Date(timeIntervalSince1970: n) }
                if let s = entry["updatedAt"] as? String, let n = Double(s) { return Date(timeIntervalSince1970: n) }
                return nil
            }()
            let localExisting = ProviderKeychainHelper.loadAPIKey(instanceId: instanceId)
            let localSavedAt = ProviderKeychainHelper.apiKeySavedAt(instanceId: instanceId)

            let shouldWrite: Bool
            let reason: String
            if localExisting == nil {
                shouldWrite = true
                reason = "local-empty"
            } else if localExisting == key {
                shouldWrite = false
                reason = "identical"
            } else if let remoteTs = remoteUpdatedAt {
                if remoteTs > localSavedAt {
                    shouldWrite = true
                    reason = "remote-newer(\(remoteTs) > \(localSavedAt))"
                } else {
                    shouldWrite = false
                    reason = "local-newer-or-equal(\(localSavedAt) >= \(remoteTs))"
                }
            } else {
                // Legacy payload (no timestamp) — preserve the old "only-if-empty"
                // behavior to avoid quietly stomping on keys the user has already set.
                shouldWrite = false
                reason = "legacy-payload-no-timestamp"
            }

            if shouldWrite {
                ProviderKeychainHelper.saveAPIKey(key, instanceId: instanceId)
                // saveAPIKey stamps local savedAt to Date() via UserDefaults. But since
                // this write was a sync-in, the correct stamp is the REMOTE timestamp
                // (so subsequent merges on this device compare against the winning value
                // and we don't ping-pong).
                if let remoteTs = remoteUpdatedAt {
                    ProviderKeychainHelper.stampAPIKeySavedAt(remoteTs, instanceId: instanceId)
                }
                logger.info("[iCloud] Imported API key for instance \(instanceId.prefix(8)) — \(reason)")
            } else {
                logger.info("[iCloud] Skipped API key for instance \(instanceId.prefix(8)) — \(reason)")
            }
        }
    }

    /// Export env var values as base64 JSON: [{"key": "VAR_NAME", "value": "base64..."}]
    static func exportEnvVarSecrets(envJson: String) -> String {
        guard let data = envJson.data(using: .utf8),
              let entries = try? JSONDecoder().decode([EnvVarEntry].self, from: data) else { return "[]" }
        var secrets: [[String: String]] = []
        for entry in entries {
            if let value = EnvVarStore.loadValueSync(forKey: entry.key), !value.isEmpty {
                let b64 = Data(value.utf8).base64EncodedString()
                secrets.append(["key": entry.key, "value": b64])
            }
        }
        guard let jsonData = try? JSONSerialization.data(withJSONObject: secrets),
              let jsonStr = String(data: jsonData, encoding: .utf8) else { return "[]" }
        return jsonStr
    }

    /// Import env var values from base64 JSON. Only writes if Keychain doesn't already have the value.
    static func importEnvVarSecrets(secretsJson: String) {
        guard let data = secretsJson.data(using: .utf8),
              let secrets = try? JSONSerialization.jsonObject(with: data) as? [[String: String]] else { return }
        for entry in secrets {
            guard let key = entry["key"],
                  let b64Value = entry["value"],
                  let valueData = Data(base64Encoded: b64Value),
                  let value = String(data: valueData, encoding: .utf8),
                  !value.isEmpty else { continue }
            if EnvVarStore.loadValueSync(forKey: key) == nil {
                EnvVarStore.saveValueSync(value, forKey: key)
                logger.info("[iCloud] Imported env var value for \(key)")
            }
        }
    }

    /// Merge remote ProviderConfig into local.
    /// - Instances: union by id, conflicts → newer file wins
    /// - ModelEntries: only user-modified remote entries are consumed; API-truth is
    ///   dropped. Overlay uses (providerInstanceId, baseModel.id) alignment with
    ///   timestamp-based conflict resolution via `userModifiedAt`.
    /// - ModelGroups: union by id, with same-name different-id collapse (so two
    ///   devices that independently created a "Coding" group converge to one).
    /// - Defaults / agent loop IDs / sessionBindings / sessionInferenceConfigs:
    ///   keep local (per-device independent).
    /// Outcome of a `mergeProviderConfig(remoteJson:)` call. Lets callers
    /// log honestly — the previous "applied via v1 merger" log was
    /// printed even when one of the early-skip guards bailed out, which
    /// masked the dirty-zombie ping-pong that kept two devices' provider
    /// lists out of sync (mergeProviderConfig was returning early on
    /// every inbound, but the hydrator's blanket log made it look like
    /// the merge had run).
    enum ProviderConfigMergeOutcome {
        case applied
        case skippedDecodeFailure
        case skippedOwnEcho
        case skippedLocalDirty
    }

    /// Merge a ProviderConfig into local state for a BACKUP RESTORE.
    ///
    /// [T-ios-backup-provider-merge] Restore used to call
    /// `ProviderConfigStore.applyConfig`, which REPLACES the whole config — so
    /// restoring a backup silently deleted every provider, model group and
    /// session binding that existed locally but not in the backup, while the
    /// confirmation dialog promised "Nothing is deleted". This routes restore
    /// through the same union-by-id merger the sync engine uses, which already
    /// handles instances/entries/groups correctly and keeps the per-device
    /// pointer fields (voiceInput/voiceOutput/vision group ids, session
    /// bindings, default pointers) local by construction.
    ///
    /// The three sync-only guards are deliberately NOT applied here:
    ///   - own-echo: a restore is never our own upload echoing back;
    ///   - local-dirty: a pending upload must not silently cancel a restore the
    ///     user explicitly asked for;
    ///   - re-upload throttle: handled by the caller's normal dirty marking.
    /// Skipping a user-initiated restore for any of those would reproduce the
    /// exact "reported success, changed nothing" failure this feature must not
    /// have.
    @MainActor
    @discardableResult
    static func mergeProviderConfigForRestore(_ remote: ProviderConfig) async
        -> ProviderConfigMergeOutcome {
        await mergeProviderConfigBody(remote: remote, isRestore: true)
    }

    @MainActor
    @discardableResult
    static func mergeProviderConfig(remoteJson: String) async -> ProviderConfigMergeOutcome {
        guard let remoteData = remoteJson.data(using: .utf8),
              let remote = try? JSONDecoder().decode(ProviderConfig.self, from: remoteData) else {
            logger.info("[iCloud] mergeProviderConfig: failed to decode remote JSON")
            return .skippedDecodeFailure
        }

        // Echo detection: if the remote data matches what we last uploaded, skip.
        // This prevents our own upload from being re-applied as a "remote change".
        let remoteHash = SHA256.hash(data: remoteData).map { String(format: "%02x", $0) }.joined()
        if remoteHash == Self.lastUploadedProviderConfigHash {
            logger.info("[iCloud] mergeProviderConfig: remote matches our last upload — skipping (own echo)")
            return .skippedOwnEcho
        }

        // Dirty-state guard: if the local ProviderConfig has unsynced changes
        // waiting in the debounced upload queue, do NOT merge. The incoming
        // remote is by definition stale (the server hasn't received our upload
        // yet), and merging it would clobber the in-flight edit — e.g. the
        // user reorders a group's members, taps Done, then within the 3s
        // upload debounce a periodic fetch pulls the *prior* server version
        // and overwrites the reorder. Skipping here is safe: the next fetch
        // after our upload lands will merge the up-to-date remote.
        //
        // Look at the v2 dirty row, not the v1 "ProviderConfig" one. Under
        // the V2 engine no one consumes or clears the legacy v1 row, so
        // checking it pinned this guard to "always skip" once any save()
        // had run since V2 was enabled — i.e. forever in practice. That
        // was the dirty-zombie ping-pong root cause: peers' upserts arrived
        // fine but were silently dropped here, the merger never ran, and
        // each device kept re-uploading its own stale snapshot.
        let dirty = await ChatStore.shared.loadDirtyRecords(v2Only: true)
        if dirty.contains(where: { $0.recordType == "ProviderConfigV2" }) {
            logger.info("[iCloud] mergeProviderConfig: local has pending ProviderConfigV2 upload — skipping merge to avoid clobbering in-flight edit")
            return .skippedLocalDirty
        }

        return await mergeProviderConfigBody(remote: remote, isRestore: false)
    }

    /// The actual union-by-id merge, shared by inbound sync and backup restore.
    /// `isRestore` only affects the re-upload decision at the end.
    @MainActor
    @discardableResult
    private static func mergeProviderConfigBody(remote: ProviderConfig,
                                                isRestore: Bool) async
        -> ProviderConfigMergeOutcome {
        // Read the authoritative current state from the store's in-memory config,
        // NOT from disk. Reading from disk would lose any changes still in-flight
        // between a UI edit and the debounced disk write (tiny but real window),
        // and would silently overwrite per-device-only fields (sessionBindings,
        // sessionInferenceConfigs, default pointers) if the disk was stale.
        let store = ProviderConfigStore.shared
        var local = store.config

        let localInstanceIds = Set(local.instances.map { $0.id })
        let localGroupIds = Set(local.modelGroups.map { $0.id })

        // Merge strategy: genuine remote change (not our own echo).
        // Remote wins for same-ID items (propagates edits, renames, member changes).
        // Local-only items (not in remote) are kept (they haven't been synced yet).
        //
        // Tombstones drive deletion semantics. Union by id of local + remote
        // tombstones survives the merge (so the next device that sees this
        // record also sees the delete intent). An item whose id is in the
        // tombstone union with `deletedAt > the would-be-resurrected entry's
        // updatedAt-ish stamp` is suppressed. ProviderInstance has no per-row
        // updatedAt, so we apply tombstones unconditionally to instances —
        // a same-id "re-add" would have to happen on the device that holds
        // the tombstone (where removeInstance has already wiped it).
        let mergedInstanceTombstones = mergeTombstones(local.deletedInstances, remote.deletedInstances)
        let mergedEntryTombstones    = mergeTombstones(local.deletedModelEntries, remote.deletedModelEntries)
        let mergedGroupTombstones    = mergeTombstones(local.deletedModelGroups, remote.deletedModelGroups)
        let tombstonedInstanceIds = Set(mergedInstanceTombstones.map(\.id))
        let tombstonedEntryIds    = Set(mergedEntryTombstones.map(\.id))
        let tombstonedGroupIds    = Set(mergedGroupTombstones.map(\.id))

        // 1. Merge instances — remote wins for same-id, keep local-only,
        //    suppress anything in the tombstone union.
        var instanceMap = Dictionary(local.instances.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        for ri in remote.instances where !tombstonedInstanceIds.contains(ri.id) {
            instanceMap[ri.id] = ri  // remote wins for existing; adds new
        }
        for tid in tombstonedInstanceIds {
            instanceMap.removeValue(forKey: tid)
        }
        // [T-backup-restore-order] Ordering is USER DATA here: the provider
        // list is drag-reorderable, and `config.instances`'s array order is
        // what carries it (ProviderConfigDB writes the array index into
        // `sort_order` and reads back `ORDER BY sort_order`).
        //
        // Sorting by `createdAt` is right for SYNC — two devices must converge
        // on the same order from the same set, and neither one's arrangement is
        // authoritative. It is wrong for RESTORE: the package records the order
        // the user arranged, and rebuilding by creation date discards it, which
        // is the reported "恢复会把服务商、分组的排序全部打乱".
        //
        // On restore: take the backup's order first, then append local-only
        // instances (which the backup could not know about) after it, keeping
        // their relative order. That makes the restored device match the
        // backup for everything the backup contained, and leaves anything
        // added since at the end rather than interleaved by date.
        if isRestore {
            var ordered: [ProviderInstance] = []
            var placed = Set<String>()
            for ri in remote.instances {
                if let merged = instanceMap[ri.id] {
                    ordered.append(merged)
                    placed.insert(ri.id)
                }
            }
            for li in local.instances where !placed.contains(li.id) {
                if let merged = instanceMap[li.id] {
                    ordered.append(merged)
                    placed.insert(li.id)
                }
            }
            // Anything only reachable through the map (defensive; should be
            // covered above) keeps the previous deterministic ordering.
            for inst in instanceMap.values where !placed.contains(inst.id) {
                ordered.append(inst)
            }
            local.instances = ordered
        } else {
            local.instances = Array(instanceMap.values).sorted(by: { $0.createdAt < $1.createdAt })
        }
        // Drop modelEntries whose providerInstanceId was tombstoned. The
        // remote snapshot may still carry orphaned entries pointing at the
        // tombstoned instance; the local snapshot was already pruned by
        // removeInstance. Either way, after this merge no instance =>
        // no entries that belong to it.
        local.modelEntries.removeAll { tombstonedInstanceIds.contains($0.providerInstanceId) }

        // 2. Merge modelEntries — consume only user-modified remote entries.
        //
        // iCloud must NOT propagate API-truth model metadata between devices: each
        // device can fetch its own models.dev/provider response, and copying one
        // device's fetched list on top of another's produces ghost entries when
        // the two fetches disagree (different UUIDs for the same model id, stale
        // contextWindow, etc.). This is what caused model lists to update
        // "spontaneously" with results that differed from a manual Refresh.
        //
        // New semantics: remote entries are only applied when they carry a user
        // intent — custom additions, overrides (displayName / maxOutputTokens),
        // or an explicit isHidden. Everything else is API truth and is dropped.
        // Pure API-truth entries are never touched; the local list stays exactly
        // as the last Refresh left it.
        //
        // Remote user-modified entries are aligned to local by (providerInstanceId,
        // baseModel.id), NOT by UUID — that way a model the user renamed on
        // device A ends up mapped onto device B's same-id local entry even if
        // the two devices generated different UUIDs during independent refreshes.
        let localEntriesSnapshot = local.modelEntries

        // Index local entries for lookup by (instanceId, baseModelId).
        struct EntryKey: Hashable { let instanceId: String; let modelId: String }
        var localIndex: [EntryKey: Int] = [:]
        for (idx, e) in local.modelEntries.enumerated() {
            localIndex[EntryKey(instanceId: e.providerInstanceId, modelId: e.baseModel.id)] = idx
        }

        var accepted = 0
        var skipped = 0
        var appliedOverrides = 0
        var appliedHidden = 0
        var insertedCustom = 0
        var skippedLegacyOverlay = 0   // remote had no userModifiedAt → overrides/isHidden not applied
        var skippedOlderRemote = 0     // remote userModifiedAt <= local userModifiedAt

        // Drop locally-known entries the union of tombstones marks as deleted.
        // Done before the remote loop so localIndex (built above) stays
        // consistent with the post-merge state. modelEntries that belong
        // to a tombstoned instance were already removed above.
        if !tombstonedEntryIds.isEmpty {
            local.modelEntries.removeAll { tombstonedEntryIds.contains($0.id) }
            // Rebuild localIndex so the per-key lookup below still works.
            localIndex.removeAll(keepingCapacity: true)
            for (idx, e) in local.modelEntries.enumerated() {
                localIndex[EntryKey(instanceId: e.providerInstanceId, modelId: e.baseModel.id)] = idx
            }
        }

        for re in remote.modelEntries {
            // Suppress entries the union of tombstones marks as deleted,
            // including any entries that hung off an instance the user
            // deleted on this device.
            if tombstonedEntryIds.contains(re.id) { continue }
            if tombstonedInstanceIds.contains(re.providerInstanceId) { continue }
            guard re.isUserModified else {
                skipped += 1
                continue
            }
            accepted += 1

            let key = EntryKey(instanceId: re.providerInstanceId, modelId: re.baseModel.id)
            if let idx = localIndex[key] {
                // Local already has this model id under this instance.
                //
                // Timestamp-based conflict resolution for overlay (overrides + isHidden):
                //   - Remote has no userModifiedAt → LEGACY data (pre-upgrade or not
                //     stamped by the uploader). Skip the overlay entirely to prevent
                //     stale override names from quietly re-appearing on newer devices.
                //   - Local has userModifiedAt but remote is older-or-equal → local wins.
                //   - Otherwise → remote wins, apply.
                //
                // We never touch baseModel (API truth) or isCustom (local Refresh owns it).
                let localEntry = local.modelEntries[idx]

                let remoteIsApplicable: Bool
                if re.userModifiedAt == nil {
                    remoteIsApplicable = false
                    skippedLegacyOverlay += 1
                } else if let localStamp = localEntry.userModifiedAt,
                          let remoteStamp = re.userModifiedAt,
                          remoteStamp <= localStamp {
                    remoteIsApplicable = false
                    skippedOlderRemote += 1
                } else {
                    remoteIsApplicable = true
                }

                guard remoteIsApplicable else { continue }

                var updated = localEntry
                var didChange = false
                if updated.overrides != re.overrides {
                    updated.overrides = re.overrides
                    appliedOverrides += 1
                    didChange = true
                }
                if updated.isHidden != re.isHidden {
                    updated.isHidden = re.isHidden
                    appliedHidden += 1
                    didChange = true
                }
                if didChange {
                    // Carry remote's userModifiedAt forward so subsequent merges on
                    // this device compare against the winning timestamp.
                    updated.userModifiedAt = re.userModifiedAt
                    local.modelEntries[idx] = updated
                }
            } else if re.isCustom {
                // Remote has a custom model that local doesn't know about — insert
                // it, preserving the remote UUID so modelGroups.memberEntryIds
                // references (which live in the same ProviderConfig blob and were
                // recorded against the remote UUID) stay valid.
                //
                // Legacy custom entries (no userModifiedAt) are still inserted:
                // refusing them would silently lose a user-authored model across the
                // upgrade boundary, which is strictly worse than the legacy-overlay
                // problem (adding a ghost is recoverable; deleting user data isn't).
                local.modelEntries.append(re)
                localIndex[key] = local.modelEntries.count - 1
                insertedCustom += 1
            }
            // else: remote has a non-custom entry for a model id local doesn't have.
            // That means the other device's API fetch returned a model this device's
            // fetch didn't — treat it as phantom and drop. Local Refresh is the only
            // authority for which non-custom models exist on this device.
        }

        // Per-instance before/after diff (unchanged structure — but now should be
        // mostly empty in the steady state, which is the whole point of the rewrite).
        let beforeByInstance = Dictionary(grouping: localEntriesSnapshot, by: { $0.providerInstanceId })
        let afterByInstance = Dictionary(grouping: local.modelEntries, by: { $0.providerInstanceId })
        let allInstanceIds = Set(beforeByInstance.keys).union(afterByInstance.keys)
        for iid in allInstanceIds {
            let beforeIds = Set((beforeByInstance[iid] ?? []).map { $0.baseModel.id })
            let afterIds = Set((afterByInstance[iid] ?? []).map { $0.baseModel.id })
            guard beforeIds != afterIds else { continue }
            let added = afterIds.subtracting(beforeIds).sorted()
            let removed = beforeIds.subtracting(afterIds).sorted()
            let instanceLabel = local.instances.first(where: { $0.id == iid })?.label ?? "?"
            logger.info("[ModelList] mergeProviderConfig: instance=\(instanceLabel)(\(iid.prefix(8))) before=\(beforeIds.count) after=\(afterIds.count) added=\(added.count)[\(added.prefix(10).joined(separator: ","))] removed=\(removed.count)[\(removed.prefix(10).joined(separator: ","))]")
        }
        logger.info("[ModelList] mergeProviderConfig: remoteEntries=\(remote.modelEntries.count) accepted=\(accepted) skipped=\(skipped) appliedOverrides=\(appliedOverrides) appliedHidden=\(appliedHidden) insertedCustom=\(insertedCustom) skippedLegacyOverlay=\(skippedLegacyOverlay) skippedOlderRemote=\(skippedOlderRemote) totalAfter=\(local.modelEntries.count)")

        // 3. Merge modelGroups — same-id: remote wins. Same-name different-id:
        // collapse (both devices independently created a group with the same
        // name; converge them onto a single id — prefer the smaller id string
        // as the stable winner so both devices deterministically agree).
        //
        // This replaces the old "(device name) suffix on name collision" logic
        // that produced permanent forks: two devices with a "Coding" group ended
        // up showing BOTH "Coding" and "Coding (iPhone...)" on both sides forever.
        // Pre-filter locally-known groups by the tombstone union (the local
        // side already pruned them in removeGroup; this guard handles
        // an incoming remote that still has them, plus same-id resurrection).
        if !tombstonedGroupIds.isEmpty {
            local.modelGroups.removeAll { tombstonedGroupIds.contains($0.id) }
        }
        var groupMap = Dictionary(local.modelGroups.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        var groupIdRewrites: [String: String] = [:]  // oldRemoteId -> canonicalLocalId
        // Build a local name index for collision detection.
        var localGroupByName: [String: String] = [:]  // name -> id
        for g in local.modelGroups { localGroupByName[g.name] = g.id }
        for rg in remote.modelGroups {
            if tombstonedGroupIds.contains(rg.id) { continue }
            // Strip out member references to tombstoned entries so a stale
            // remote member list doesn't carry deleted ids forward.
            var rg = rg
            if !tombstonedEntryIds.isEmpty {
                rg.memberEntryIds.removeAll { tombstonedEntryIds.contains($0) }
            }
            if let localGroup = groupMap[rg.id] {
                // Same id — use REMOTE member order as primary. This function
                // is only reached when the remote is NOT our own echo (own-echo
                // skip above) AND local has no pending dirty upload (dirty-skip
                // above), so the remote represents a peer's latest edit whose
                // order should be respected. Local-only members (concurrent adds
                // the peer hasn't seen) are appended at the end so no member is
                // lost. This aligns with the V3 DB-level upsertGroupFromInbound
                // which uses updatedAt LWW — the two converge because the remote
                // arriving here is the newer-or-equal side (local is clean).
                var merged = rg  // start from remote for scalar fields + order
                var order = rg.memberEntryIds
                let remoteSet = Set(order)
                for mid in localGroup.memberEntryIds where !remoteSet.contains(mid) {
                    order.append(mid)
                }
                merged.memberEntryIds = order
                groupMap[rg.id] = merged
            } else if let collidingLocalId = localGroupByName[rg.name], collidingLocalId != rg.id {
                // Same name, different id → collapse onto the lexicographically
                // smaller id so both devices deterministically pick the same winner.
                let canonicalId = min(collidingLocalId, rg.id)
                let otherId = max(collidingLocalId, rg.id)
                groupIdRewrites[otherId] = canonicalId

                if canonicalId == collidingLocalId {
                    // Local id wins: merge remote's memberEntryIds union into local, prefer local's other fields.
                    if var winner = groupMap[canonicalId] {
                        var seen = Set(winner.memberEntryIds)
                        for mid in rg.memberEntryIds where !seen.contains(mid) {
                            winner.memberEntryIds.append(mid)
                            seen.insert(mid)
                        }
                        groupMap[canonicalId] = winner
                    }
                } else {
                    // Remote id wins: install remote, but merge in the old local memberEntryIds
                    // so nothing is lost. Drop the old local entry under collidingLocalId.
                    var winner = rg
                    if let oldLocal = groupMap[collidingLocalId] {
                        var seen = Set(winner.memberEntryIds)
                        for mid in oldLocal.memberEntryIds where !seen.contains(mid) {
                            winner.memberEntryIds.append(mid)
                            seen.insert(mid)
                        }
                    }
                    groupMap.removeValue(forKey: collidingLocalId)
                    groupMap[canonicalId] = winner
                    localGroupByName[winner.name] = canonicalId
                }
                logger.info("[ModelList] mergeProviderConfig: group name collision '\(rg.name)' collapsed: \(otherId.prefix(8)) -> \(canonicalId.prefix(8))")
            } else {
                // Brand new group — no id clash, no name clash. Just add it.
                groupMap[rg.id] = rg
                localGroupByName[rg.name] = rg.id
            }
        }
        // [T-backup-restore-order] `Dictionary.values` has NO defined order, so
        // this line alone could reshuffle the group list on every merge —
        // worse than the instances case, which at least sorted by date.
        // Rebuild deterministically: the backup's order first on a restore,
        // then whatever is local-only; for sync, keep the local arrangement
        // and append newly-arrived groups, so a pull does not rearrange a list
        // the user dragged into place.
        if isRestore {
            var orderedGroups: [ModelGroup] = []
            var placedGroups = Set<String>()
            for rg in remote.modelGroups {
                // The id may have been rewritten by the name-collision pass
                // above, so resolve through the map rather than trusting `rg`.
                if let merged = groupMap[rg.id] {
                    orderedGroups.append(merged)
                    placedGroups.insert(merged.id)
                }
            }
            for lg in local.modelGroups where !placedGroups.contains(lg.id) {
                if let merged = groupMap[lg.id] {
                    orderedGroups.append(merged)
                    placedGroups.insert(merged.id)
                }
            }
            for g in groupMap.values where !placedGroups.contains(g.id) {
                orderedGroups.append(g)
                placedGroups.insert(g.id)
            }
            local.modelGroups = orderedGroups
        } else {
            var orderedGroups: [ModelGroup] = []
            var placedGroups = Set<String>()
            for lg in local.modelGroups {
                if let merged = groupMap[lg.id] {
                    orderedGroups.append(merged)
                    placedGroups.insert(merged.id)
                }
            }
            for g in groupMap.values where !placedGroups.contains(g.id) {
                orderedGroups.append(g)
                placedGroups.insert(g.id)
            }
            local.modelGroups = orderedGroups
        }

        // Fix up dangling references to group ids we rewrote. Defaults / agent
        // loop lists live outside the group array itself but point at group ids.
        if !groupIdRewrites.isEmpty {
            if let def = local.defaultPrimaryGroupId, let replacement = groupIdRewrites[def] {
                local.defaultPrimaryGroupId = replacement
            }
            if let def = local.defaultSubGroupId, let replacement = groupIdRewrites[def] {
                local.defaultSubGroupId = replacement
            }
            local.agentLoopGroupIds = local.agentLoopGroupIds.map { groupIdRewrites[$0] ?? $0 }
        }

        // 4. Don't touch: defaultPrimaryGroupId, defaultSubGroupId, agentLoopModelEntryIds,
        //    agentLoopModelGroupIds, sessionBindings, sessionInferenceConfigs.
        //    These stay per-device independent (reading from store.config above
        //    ensures we're preserving the latest in-memory values, not stale disk).

        // L1 — "localHasUnique" is now computed over *user intent*, not UUID sets.
        // Under the new model, modelEntries on the wire carry the full list but
        // only `isUserModified` entries are semantically meaningful. A UUID-diff
        // falsely reported "unique" for API-truth entries that differ purely
        // because each device fetched the list independently, triggering an
        // infinite ping-pong re-upload loop throttled only by 60s.
        //
        // Real uniqueness signal: local has a user-modified entry (by instance +
        // model.id) that isn't present in remote as user-modified, OR local has
        // an instance/group id remote doesn't. Anything else is noise.
        let remoteInstanceIds = Set(remote.instances.map { $0.id })
        let remoteGroupIds = Set(remote.modelGroups.map { $0.id })
        struct EntryIntentKey: Hashable { let instanceId: String; let modelId: String }
        let remoteUserIntentKeys: Set<EntryIntentKey> = Set(
            remote.modelEntries.filter(\.isUserModified).map {
                EntryIntentKey(instanceId: $0.providerInstanceId, modelId: $0.baseModel.id)
            }
        )
        let localUserIntentKeys: Set<EntryIntentKey> = Set(
            local.modelEntries.filter(\.isUserModified).map {
                EntryIntentKey(instanceId: $0.providerInstanceId, modelId: $0.baseModel.id)
            }
        )
        // Tombstones the local side knows about but remote doesn't — these
        // need to be re-uploaded so the remote learns of the delete.
        let remoteTombstoneIds = Set(remote.deletedInstances.map(\.id))
            .union(remote.deletedModelEntries.map(\.id))
            .union(remote.deletedModelGroups.map(\.id))
        let localTombstoneIds = Set(local.deletedInstances.map(\.id))
            .union(local.deletedModelEntries.map(\.id))
            .union(local.deletedModelGroups.map(\.id))
        let localHasUniqueTombstones = !localTombstoneIds.subtracting(remoteTombstoneIds).isEmpty
        let localHasUnique = !localInstanceIds.subtracting(remoteInstanceIds).isEmpty
            || !localUserIntentKeys.subtracting(remoteUserIntentKeys).isEmpty
            || !localGroupIds.subtracting(remoteGroupIds).isEmpty
            || localHasUniqueTombstones

        // Write back the union of tombstones so a third device pulling this
        // record also learns of the delete. Without this the delete intent
        // would only live on the device that issued it.
        local.deletedInstances    = mergedInstanceTombstones
        local.deletedModelEntries = mergedEntryTombstones
        local.deletedModelGroups  = mergedGroupTombstones

        // Commit: push merged config into store (in-memory + disk) WITHOUT
        // triggering a markDirty upload. We control the re-upload decision
        // explicitly below via localHasUnique.
        store.applyMergedConfigFromSync(local)
        logger.info("[iCloud] mergeProviderConfig: merged \(instanceMap.count) instances, \(local.modelEntries.count) entries, \(local.modelGroups.count) groups")

        // A restore is user-initiated local authorship, not an inbound echo:
        // mark dirty unconditionally so the restored providers propagate, and
        // skip the anti-ping-pong throttle (which exists to damp device-to-
        // device loops that a manual restore is not part of). §8.3 wants
        // restored rows re-uploaded under this device's identity.
        if isRestore {
            Task {
                await ChatStore.shared.markDirty(recordType: "ProviderConfig",
                                                 recordId: "provider-config")
            }
            logger.info("[iCloud] mergeProviderConfigForRestore: merged and marked dirty")
            return .applied
        }

        // Re-upload merged result so other device gets our unique data too.
        // Throttle to at most once per 60s to prevent ping-pong between devices.
        let now = Date()
        if localHasUnique && now.timeIntervalSince(Self.lastProviderConfigReuploadDate) > 60 {
            Self.lastProviderConfigReuploadDate = now
            Task {
                await ChatStore.shared.markDirty(recordType: "ProviderConfig", recordId: "provider-config")
            }
            logger.info("[iCloud] mergeProviderConfig: marked dirty for re-upload (localHasUnique=true, throttled)")
        } else if localHasUnique {
            logger.info("[iCloud] mergeProviderConfig: skipping re-upload (throttled, last=\(Self.lastProviderConfigReuploadDate))")
        }
        return .applied
    }

    /// Union of two tombstone arrays by id, keeping the most recent
    /// `deletedAt` per id. The result drives both the suppression filter
    /// (later resurrections of the same id are blocked) and the merged
    /// list that gets written back so peers downstream also see the delete.
    private static func mergeTombstones(_ a: [ProviderConfigTombstone],
                                        _ b: [ProviderConfigTombstone]) -> [ProviderConfigTombstone] {
        var byId: [String: ProviderConfigTombstone] = [:]
        for t in a { byId[t.id] = t }
        for t in b {
            if let existing = byId[t.id] {
                if t.deletedAt > existing.deletedAt { byId[t.id] = t }
            } else {
                byId[t.id] = t
            }
        }
        return Array(byId.values)
    }

    /// Merge remote env vars into local by unioning by ID.
    /// For items with the same ID, the version from the newer file wins.
    @MainActor
    static func mergeEnvVars(remoteJson: String) {
        guard let remoteData = remoteJson.data(using: .utf8),
              let remoteVars = try? JSONDecoder().decode([EnvVarEntry].self, from: remoteData) else {
            logger.info("[iCloud] mergeEnvVars: failed to decode remote JSON")
            return
        }

        // Echo detection: skip if remote data matches our last upload
        let remoteHash = SHA256.hash(data: remoteData).map { String(format: "%02x", $0) }.joined()
        if remoteHash == Self.lastUploadedEnvVarHash {
            logger.info("[iCloud] mergeEnvVars: remote matches our last upload — skipping (own echo)")
            return
        }

        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let localURL = library.appendingPathComponent("MinisChat/env-vars.json")
        let localVars: [EnvVarEntry]
        if let data = try? Data(contentsOf: localURL),
           let decoded = try? JSONDecoder().decode([EnvVarEntry].self, from: data) {
            localVars = decoded
        } else {
            localVars = []
        }

        let localIdSet = Set(localVars.map { $0.id })

        // Merge by id — remote wins for same-id (propagates edits), keep local-only
        var varMap = Dictionary(localVars.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        var addedNew = false
        for rv in remoteVars {
            if varMap[rv.id] == nil { addedNew = true }
            varMap[rv.id] = rv  // remote wins for existing; adds new
        }

        // Also check if remote has keys we don't — means local has keys remote doesn't
        let remoteIdSet = Set(remoteVars.map { $0.id })
        let localHasUniqueKeys = !localIdSet.subtracting(remoteIdSet).isEmpty

        let merged = Array(varMap.values).sorted(by: { $0.createdAt < $1.createdAt })
        if let data = try? JSONEncoder().encode(merged) {
            try? data.write(to: localURL, options: .atomic)
            // Reload EnvVarStore
            EnvVarStore.shared.reloadFromDisk()
            logger.info("[iCloud] mergeEnvVars: merged \(merged.count) vars (addedNew=\(addedNew), localHasUnique=\(localHasUniqueKeys))")

            // If local has keys that remote doesn't, re-upload our merged result
            // so the other device can pick up our keys too (bidirectional merge).
            // Throttle to at most once per 60s to prevent ping-pong between devices.
            let now = Date()
            if (localHasUniqueKeys || addedNew) && now.timeIntervalSince(Self.lastEnvVarReuploadDate) > 60 {
                Self.lastEnvVarReuploadDate = now
                Task {
                    await ChatStore.shared.markDirty(recordType: "EnvVar", recordId: "env-vars")
                }
                logger.info("[iCloud] mergeEnvVars: marked dirty for re-upload (bidirectional sync, throttled)")
            } else if localHasUniqueKeys || addedNew {
                logger.info("[iCloud] mergeEnvVars: skipping re-upload (throttled, last=\(Self.lastEnvVarReuploadDate))")
            }
        }
    }

    // MARK: - Server Record Cache Persistence

    // MARK: - Server Record Cache (Per-Session Persistence)

    /// [T-ios-sync-etag-cache-payload] Strip a CKRecord down to just its system
    /// fields (recordID, recordType, change tag, timestamps) — the ONLY thing
    /// `serverRecordCache` is used for: preserving the etag so the next save
    /// reuses it and avoids "record already exists". Both cache readers
    /// (buildCKRecord / updateSyncDeviceRecord) immediately overwrite every
    /// data field, so the payload is never read back — yet storing the full
    /// server record kept its entire parts_json / image blobs resident (memgraph
    /// showed ~5KB/record × thousands = hundreds of MB). Round-tripping through
    /// encodeSystemFields (the exact same shape the on-disk plist already uses)
    /// drops the payload, so cached etags cost ~200B instead of ~5KB. Returns
    /// the original record if the round-trip fails (never worse than before).
    private func systemFieldsOnly(_ record: CKRecord) -> CKRecord {
        let encoder = NSKeyedArchiver(requiringSecureCoding: true)
        record.encodeSystemFields(with: encoder)
        encoder.finishEncoding()
        guard let coder = try? NSKeyedUnarchiver(forReadingFrom: encoder.encodedData) else {
            return record
        }
        coder.requiresSecureCoding = true
        defer { coder.finishDecoding() }
        return CKRecord(coder: coder) ?? record
    }

    /// Update the in-memory cache with the server's version of a record (preserves etag).
    private func cacheServerRecord(_ record: CKRecord) {
        let previousTag = serverRecordCache[record.recordID]?.recordChangeTag
        // [T-ios-sync-etag-cache-payload] cache the etag, NOT the payload.
        serverRecordCache[record.recordID] = systemFieldsOnly(record)
        // Mark the owning session's cache as dirty for deferred disk write.
        if Self.sessionRecordTypes.contains(record.recordType),
           let sessionId = Self.extractSessionId(from: record.recordID.recordName, type: record.recordType) {
            dirtySessionCaches.insert(sessionId)
        } else {
            // Non-per-session record (SyncDevice/Skill/ProviderConfig/EnvVar):
            // flag the global etag cache for deferred disk write so the etag
            // survives app restart.
            globalEtagCacheDirty = true
            let newTag = record.recordChangeTag ?? "(nil)"
            let oldTag = previousTag ?? "(nil)"
            logger.info("[CloudSync] cacheServerRecord global \(record.recordType) \(record.recordID.recordName) etag \(oldTag) -> \(newTag)")
        }
    }

    /// Extract session ID from a recordName like "Message:sessionId:messageId" or "Session:sessionId".
    private static func extractSessionId(from recordName: String, type: String) -> String? {
        // recordName format: "Type:id" where id varies by type:
        //   Session      → "Session:sessionId"
        //   Message      → "Message:sessionId:messageId"
        //   CompactMarker→ "CompactMarker:sessionId:markerId"
        //   SessionFile  → "SessionFile:sessionId:relativePath"
        let afterType = recordName.dropFirst(type.count + 1) // drop "Type:"
        if type == "Session" {
            return String(afterType)
        }
        // For other types, sessionId is the first component before ":"
        if let colonIdx = afterType.firstIndex(of: ":") {
            return String(afterType[afterType.startIndex..<colonIdx])
        }
        return String(afterType)
    }

    /// Ensure the per-session cache is loaded into memory for a given session.
    private func ensureSessionCacheLoaded(_ sessionId: String) {
        // [T-ios-sync-etag-cache-lru] Mark this session most-recently-used on
        // EVERY access (even when already resident) so the LRU reflects real
        // usage, then bound the resident set below.
        touchSessionLRU(sessionId)

        guard !loadedSessionCaches.contains(sessionId) else { return }
        loadedSessionCaches.insert(sessionId)

        let cacheURL = sessionCacheDir.appendingPathComponent("\(sessionId).plist")
        if let data = try? Data(contentsOf: cacheURL),
           let dict = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Data] {
            var loaded = 0
            for (_, systemFieldsData) in dict {
                let coder = try? NSKeyedUnarchiver(forReadingFrom: systemFieldsData)
                coder?.requiresSecureCoding = true
                if let coder, let record = CKRecord(coder: coder) {
                    coder.finishDecoding()
                    serverRecordCache[record.recordID] = record
                    loaded += 1
                } else {
                    coder?.finishDecoding()
                }
            }
            if loaded > 0 {
                logger.info("[CloudSync] Loaded \(loaded) etags for session \(sessionId.prefix(8))")
            }
        }

        // Evict the coldest sessions once we exceed the resident cap. Done after
        // the load so a just-needed session is never the eviction victim.
        evictColdSessionCachesIfNeeded()
    }

    /// [T-ios-sync-etag-cache-lru] Move `sessionId` to the most-recently-used
    /// end of the LRU order.
    private func touchSessionLRU(_ sessionId: String) {
        if let idx = sessionCacheLRU.firstIndex(of: sessionId) {
            sessionCacheLRU.remove(at: idx)
        }
        sessionCacheLRU.append(sessionId)
    }

    /// [T-ios-sync-etag-cache-lru] While more than `maxResidentSessionCaches`
    /// per-session caches are resident, flush the coldest (so its freshest etags
    /// survive on disk) and drop its records from `serverRecordCache`. The etags
    /// remain in `<sessionId>.plist` and are reloaded on the next access, so this
    /// is lossless — it only trades a little memory for a possible disk read when
    /// a cold session is touched again.
    private func evictColdSessionCachesIfNeeded() {
        while loadedSessionCaches.count > Self.maxResidentSessionCaches,
              let coldest = sessionCacheLRU.first(where: { loadedSessionCaches.contains($0) }) {
            // Persist any unsaved etags for the victim before dropping it.
            if dirtySessionCaches.contains(coldest) {
                flushDirtySessionCaches(only: coldest)
            }
            // Collect this session's resident record IDs, then drop them. Only
            // per-session record types are eligible — global etags (SyncDevice /
            // Skill / ProviderConfig / EnvVar) are never session-scoped and stay.
            let victimIDs = serverRecordCache.compactMap { (rid, record) -> CKRecord.ID? in
                guard Self.sessionRecordTypes.contains(record.recordType),
                      Self.extractSessionId(from: rid.recordName, type: record.recordType) == coldest
                else { return nil }
                return rid
            }
            for rid in victimIDs { serverRecordCache.removeValue(forKey: rid) }
            loadedSessionCaches.remove(coldest)
            if let idx = sessionCacheLRU.firstIndex(of: coldest) {
                sessionCacheLRU.remove(at: idx)
            }
            logger.info("[CloudSync] [etag-lru] evicted session \(coldest.prefix(8)) (\(victimIDs.count) etags) — resident=\(self.loadedSessionCaches.count)")
        }
    }

    /// Flush dirty per-session caches to disk. Each session is a small plist file
    /// containing only CKRecord system fields (~200 bytes/record).
    private func flushDirtySessionCaches() {
        guard !dirtySessionCaches.isEmpty else { return }
        let sessionIds = dirtySessionCaches
        dirtySessionCaches.removeAll()
        for sessionId in sessionIds {
            writeSessionCachePlist(sessionId)
        }
    }

    /// [T-ios-sync-etag-cache-lru] Flush exactly one session's dirty etags (used
    /// by LRU eviction before dropping a victim so its freshest etags survive).
    private func flushDirtySessionCaches(only sessionId: String) {
        guard dirtySessionCaches.remove(sessionId) != nil else { return }
        writeSessionCachePlist(sessionId)
    }

    /// Serialize one session's resident records' system fields to its plist.
    private func writeSessionCachePlist(_ sessionId: String) {
        var dict: [String: Data] = [:]
        for (recordID, record) in serverRecordCache {
            guard Self.sessionRecordTypes.contains(record.recordType),
                  Self.extractSessionId(from: recordID.recordName, type: record.recordType) == sessionId else {
                continue
            }
            let encoder = NSKeyedArchiver(requiringSecureCoding: true)
            record.encodeSystemFields(with: encoder)
            encoder.finishEncoding()
            dict[recordID.recordName] = encoder.encodedData
        }
        guard !dict.isEmpty else { return }

        let cacheURL = sessionCacheDir.appendingPathComponent("\(sessionId).plist")
        if let plistData = try? PropertyListSerialization.data(fromPropertyList: dict, format: .binary, options: 0) {
            try? plistData.write(to: cacheURL, options: .atomic)
        }
    }

    /// Remove the per-session cache file when a session is deleted.
    func removeSessionCache(_ sessionId: String) {
        loadedSessionCaches.remove(sessionId)
        dirtySessionCaches.remove(sessionId)
        // [T-ios-sync-etag-cache-lru] Also drop the session's resident records
        // from serverRecordCache (previously left in memory until a global
        // reset) and from the LRU order.
        let staleIDs = serverRecordCache.compactMap { (rid, record) -> CKRecord.ID? in
            guard Self.sessionRecordTypes.contains(record.recordType),
                  Self.extractSessionId(from: rid.recordName, type: record.recordType) == sessionId
            else { return nil }
            return rid
        }
        for rid in staleIDs { serverRecordCache.removeValue(forKey: rid) }
        if let idx = sessionCacheLRU.firstIndex(of: sessionId) {
            sessionCacheLRU.remove(at: idx)
        }
        let cacheURL = sessionCacheDir.appendingPathComponent("\(sessionId).plist")
        try? FileManager.default.removeItem(at: cacheURL)
    }

    /// Populate `serverRecordCache` with etags for the non-per-session record
    /// types (SyncDevice, Skill, ProviderConfig, EnvVar). Called once during
    /// engine startup so the next upload can use the persisted etag instead
    /// of creating a fresh CKRecord — which the server would reject with
    /// "record to insert already exists" and send us into a conflict loop
    /// until a fetch happens.
    private func loadGlobalEtagCache() {
        guard let data = try? Data(contentsOf: globalEtagCacheURL),
              let dict = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Data] else {
            logger.info("[CloudSync] loadGlobalEtagCache: no cache file at \(self.globalEtagCacheURL.lastPathComponent)")
            return
        }
        var loaded = 0
        var byType: [String: Int] = [:]
        for (_, systemFieldsData) in dict {
            let coder = try? NSKeyedUnarchiver(forReadingFrom: systemFieldsData)
            coder?.requiresSecureCoding = true
            if let coder, let record = CKRecord(coder: coder) {
                coder.finishDecoding()
                serverRecordCache[record.recordID] = record
                byType[record.recordType, default: 0] += 1
                loaded += 1
            } else {
                coder?.finishDecoding()
            }
        }
        logger.info("[CloudSync] loadGlobalEtagCache: loaded \(loaded) global etags byType=\(byType)")
    }

    /// Persist the in-memory etags for non-per-session record types to disk.
    /// Called after each successful save / conflict resolution that touches
    /// one of those record types. Writes are cheap (each record is ~200
    /// bytes of system fields) so we just rewrite the whole file.
    private func flushGlobalEtagCacheIfNeeded() {
        guard globalEtagCacheDirty else { return }
        globalEtagCacheDirty = false

        var dict: [String: Data] = [:]
        var byType: [String: Int] = [:]
        for (recordID, record) in serverRecordCache {
            guard !Self.sessionRecordTypes.contains(record.recordType) else { continue }
            let encoder = NSKeyedArchiver(requiringSecureCoding: true)
            record.encodeSystemFields(with: encoder)
            encoder.finishEncoding()
            dict[recordID.recordName] = encoder.encodedData
            byType[record.recordType, default: 0] += 1
        }

        if dict.isEmpty {
            try? FileManager.default.removeItem(at: globalEtagCacheURL)
            logger.info("[CloudSync] flushGlobalEtagCache: empty, removed cache file")
            return
        }
        if let plistData = try? PropertyListSerialization.data(fromPropertyList: dict, format: .binary, options: 0) {
            try? plistData.write(to: globalEtagCacheURL, options: .atomic)
            logger.info("[CloudSync] flushGlobalEtagCache: wrote \(dict.count) etags byType=\(byType) (\(plistData.count) bytes)")
        }
    }
}

// MARK: - CKSyncEngineDelegate

@available(iOS 17.0, *)
extension CloudSyncEngine: CKSyncEngineDelegate {
    nonisolated func handleEvent(_ event: CKSyncEngine.Event, syncEngine: CKSyncEngine) {
        // CKSyncEngine requires that delegate callbacks don't re-enter the engine.
        // Use Task.detached to avoid actor isolation issues, and fire-and-forget for
        // events that don't need to block the engine.
        switch event {
        case .stateUpdate(let stateUpdate):
            Task { @MainActor [weak self] in
                self?.saveState(stateUpdate.stateSerialization)
            }

        case .accountChange(let accountChange):
            Task { @MainActor [weak self] in
                guard let self else { return }
                switch accountChange.changeType {
                case .signIn:
                    logger.info("[CloudSync] Account signed in")
                    self.queueZonesAndDeviceRecord()
                case .signOut:
                    logger.info("[CloudSync] Account signed out")
                    self.syncStatus = .error("Not signed in to iCloud")
                case .switchAccounts:
                    logger.info("[CloudSync] Account switched")
                    await self.resetSyncState()
                    await self.start()
                @unknown default:
                    break
                }
            }

        case .fetchedDatabaseChanges(let dbChanges):
            for modification in dbChanges.modifications {
                let zoneName = modification.zoneID.zoneName
                if zoneName.hasPrefix("device-") {
                    logger.info("[CloudSync] Discovered remote device zone: \(zoneName)")
                }
            }

        case .fetchedRecordZoneChanges(let zoneChanges):
            Task.detached { [weak self] in
                guard let self else { return }
                let ownDeviceId = await MainActor.run { DeviceIdentity.deviceId }
                // Only count changes from OTHER devices (not our own zone echoed back).
                let hasRemoteModifications = zoneChanges.modifications.contains { mod in
                    let zoneDeviceId = mod.record.recordID.zoneID.zoneName.replacingOccurrences(of: "device-", with: "")
                    return zoneDeviceId != ownDeviceId
                }
                let hasRemoteDeletions = zoneChanges.deletions.contains { del in
                    let zoneDeviceId = del.recordID.zoneID.zoneName.replacingOccurrences(of: "device-", with: "")
                    return zoneDeviceId != ownDeviceId
                }
                if hasRemoteModifications || hasRemoteDeletions {
                    await MainActor.run { self.fetchHadRemoteChanges = true }
                }
                let knownDevices = await MainActor.run { self.knownDevices }
                for modification in zoneChanges.modifications {
                    let rec = modification.record
                    let zoneName = rec.recordID.zoneID.zoneName
                    let deviceId = zoneName.replacingOccurrences(of: "device-", with: "")
                    let deviceName = knownDevices.first(where: { $0.id == deviceId })?.deviceName
                    // V2 record types belong to v2 transport; v1 just sees
                    // them when fetching minis-shared. Skip logging to
                    // avoid tens of thousands of noise lines during
                    // migration.
                    if rec.recordType.hasSuffix("V2") { continue }
                    logger.info("[iCloud] fetchedRecordZoneChanges: type=\(rec.recordType) recordName=\(rec.recordID.recordName) zone=\(zoneName)")
                    await MainActor.run {
                        SyncLogStore.shared.append(SyncLogEntry(
                            timestamp: Date(), direction: .received,
                            recordType: rec.recordType,
                            recordName: rec.recordID.recordName,
                            summary: Self.summaryForRecord(rec),
                            detail: zoneName,
                            deviceName: deviceName ?? deviceId
                        ))
                    }
                    await self.processRemoteRecord(rec)
                }
                // Collect session IDs that are being deleted in this batch so
                // message-level deletions can distinguish "session deleted" (ignore)
                // from "individual message deleted" (propagate to keep lists in sync).
                let deletedSessionIds: Set<String> = Set(zoneChanges.deletions.compactMap { del in
                    guard del.recordType == "Session" else { return nil }
                    let name = del.recordID.recordName
                    // recordName = "Session:sessionId"
                    return name.contains(":") ? String(name.split(separator: ":", maxSplits: 1).last ?? "") : name
                })

                for deletion in zoneChanges.deletions {
                    let zoneName = deletion.recordID.zoneID.zoneName
                    let deviceId = zoneName.replacingOccurrences(of: "device-", with: "")
                    let deviceName = knownDevices.first(where: { $0.id == deviceId })?.deviceName
                    await MainActor.run {
                        SyncLogStore.shared.append(SyncLogEntry(
                            timestamp: Date(), direction: .received,
                            recordType: "Delete:\(deletion.recordType)",
                            recordName: deletion.recordID.recordName,
                            summary: Self.summaryForRecordName(deletion.recordID.recordName, type: deletion.recordType),
                            detail: zoneName,
                            deviceName: deviceName ?? deviceId
                        ))
                    }
                    await self.processRemoteDeletion(deletion.recordID, recordType: deletion.recordType, deletedSessionIds: deletedSessionIds)
                }
                await MainActor.run { [weak self] in self?.lastSyncDate = Date() }
            }

        case .sentRecordZoneChanges(let sentChanges):
            // Process results off the main thread to avoid blocking UI.
            // Only hop to MainActor for state mutations (cacheServerRecord, etc.).
            Task { [weak self] in
                guard let self else { return }
                let batchStart = CFAbsoluteTimeGetCurrent()
                let savedCount = sentChanges.savedRecords.count
                let failedCount = sentChanges.failedRecordSaves.count
                let deletedCount = sentChanges.deletedRecordIDs.count
                // Count by type
                var savedTypes: [String: Int] = [:]
                for r in sentChanges.savedRecords { savedTypes[r.recordType, default: 0] += 1 }
                let typeBreakdown = savedTypes.sorted { $0.value > $1.value }.map { "\($0.key)=\($0.value)" }.joined(separator: " ")
                logger.info("[CloudSync] ⏱ sentRecordZoneChanges: saved=\(savedCount) failed=\(failedCount) deleted=\(deletedCount) [\(typeBreakdown)]")

                // -- Collect all data off MainActor, batch state updates at the end --
                var syncLogEntries: [SyncLogEntry] = []
                var syncedSessionIds: [String] = []
                var recordNamesToClean: [String] = []
                var serverRecordsToCache: [CKRecord] = []
                var savedRecordsToCache: [CKRecord] = []
                var recordIDsToRemove: [CKRecord.ID] = []
                var hasSyncDeviceConflict = false
                var hasSyncDeviceSaved = false
                // [T-icloud-notfound-retry-loop] Snapshot the MainActor-isolated
                // once-guard for the off-MainActor classification gate; collect
                // mutations into locals and apply them inside the MainActor block.
                let clearedSnapshot = await MainActor.run { self.unknownItemEtagCleared }
                var unknownItemMarkInserted: [String] = []   // recreate attempted now
                var unknownItemMarkCleared: [String] = []     // saved -> reset budget

                // Process successful saves
                for saved in sentChanges.savedRecords {
                    logger.debug("[CloudSync] Saved record: \(saved.recordID.recordName)")
                    savedRecordsToCache.append(saved)
                    recordNamesToClean.append(saved.recordID.recordName)
                    // [T-icloud-notfound-retry-loop] A successful save (incl. a
                    // post-NOT_FOUND recreate) refreshes the recovery budget, so
                    // a future legitimate NOT_FOUND on this record is recoverable
                    // again. Also bounds the set: healthy records don't linger.
                    unknownItemMarkCleared.append(saved.recordID.recordName)
                    if saved.recordType == "SyncDevice" { hasSyncDeviceSaved = true }
                    if saved.recordType == "Session" {
                        let parts = saved.recordID.recordName.split(separator: ":", maxSplits: 1)
                        if parts.count > 1 { syncedSessionIds.append(String(parts[1])) }
                    }
                }

                // Process failed saves
                var conflictRetryIDs: [CKRecord.ID] = []
                var transientRetryIDs: [CKRecord.ID] = []
                // Records whose stale etag must be evicted from serverRecordCache
                // after a NOT_FOUND, so the next rebuild re-creates them clean.
                // [T-icloud-notfound-retry-loop]
                var unknownItemEtagToClear: [CKRecord.ID] = []
                for failed in sentChanges.failedRecordSaves {
                    let recordName = failed.record.recordID.recordName
                    logger.error("[CloudSync] Failed to save: \(recordName) — \(failed.error)")
                    if failed.error.code == .serverRecordChanged {
                        let localTag = failed.record.recordChangeTag ?? "(nil)"
                        let serverTag = failed.error.serverRecord?.recordChangeTag ?? "(nil)"
                        logger.info("[CloudSync] serverRecordChanged \(failed.record.recordType) \(recordName) localEtag=\(localTag) serverEtag=\(serverTag)")
                        if let serverRecord = failed.error.serverRecord {
                            serverRecordsToCache.append(serverRecord)
                        }
                        if failed.record.recordType == "SyncDevice" {
                            hasSyncDeviceConflict = true
                        } else {
                            // Re-queue non-SyncDevice conflicts explicitly.
                            // The engine clears failed records from its pending
                            // state, so without this the record would only be
                            // retried when something else re-marks it dirty —
                            // which is what drove the repeating 3-minute
                            // CONFLICT Skill/ProviderConfig loops seen in the
                            // 2026-04-20 diagnostic.
                            conflictRetryIDs.append(failed.record.recordID)
                        }
                    } else if Self.isTransientCKError(failed.error) {
                        // Transient (network, throttling, server busy). Keep
                        // the dirty mark in DB and re-queue with the engine so
                        // the record is retried on the next send. Without
                        // this, a single TLS / network blip on a batch
                        // permanently strands those rows: clearDirtyRecord
                        // would forget them locally while the cloud never
                        // received them. See T-icloudsync-diag.
                        logger.warning("[CloudSync] transient save error — re-queueing \(failed.record.recordType) \(recordName) (code=\(failed.error.code.rawValue))")
                        transientRetryIDs.append(failed.record.recordID)
                    } else if failed.error.code == .unknownItem,
                              !clearedSnapshot.contains(recordName) {
                        // NOT_FOUND ("recordChangeTag specified, but record not
                        // found"): our cached etag points at a server record
                        // that no longer exists (peer-deleted / zone wiped).
                        // Clearing the dirty mark alone is NOT enough: SyncDevice
                        // is re-queued on every heartbeat and ProviderConfig on
                        // every config change, and queueDeviceRecord /
                        // buildCKRecord both rebuild from `serverRecordCache`
                        // (line ~966 / ~1012), so the STALE ETAG would be reused
                        // and CloudKit would return NOT_FOUND again — the forever
                        // retry loop seen in the 2026-06-04 diagnostic
                        // (ProviderConfig + SyncDevice looping every few seconds
                        // for ~47 min). [T-icloud-notfound-retry-loop]
                        //
                        // Drop the stale etag so the next rebuild produces a
                        // clean, etag-less CKRecord that CloudKit accepts as a
                        // fresh create. Do NOT clear the dirty mark — we WANT the
                        // record re-sent (as a create) on the next pass. Gated by
                        // unknownItemEtagCleared so if the etag-less recreate
                        // ITSELF keeps failing NOT_FOUND we don't loop forever;
                        // the second NOT_FOUND falls through to the permanent
                        // branch (clears dirty + drops). The marker is cleared on
                        // a successful save so a later legitimate NOT_FOUND is
                        // recoverable again.
                        logger.warning("[CloudSync] NOT_FOUND \(failed.record.recordType) \(recordName) — clearing stale etag so next send re-creates the record")
                        unknownItemMarkInserted.append(recordName)
                        unknownItemEtagToClear.append(failed.record.recordID)
                        recordIDsToRemove.append(failed.record.recordID)
                    } else {
                        if failed.error.code == .unknownItem {
                            // Recreate already attempted and still NOT_FOUND —
                            // stop looping: clear dirty + drop from pending.
                            logger.error("[CloudSync] NOT_FOUND \(failed.record.recordType) \(recordName) — recreate already attempted, permanent-failing")
                        }
                        // Permanent failure (badRequest, invalidArguments,
                        // unknown record-type, etc.) — drop dirty mark and
                        // remove from pending so we don't retry forever.
                        recordNamesToClean.append(recordName)
                        recordIDsToRemove.append(failed.record.recordID)
                    }
                }

                // Process deletes
                for (recordID, deleteError) in sentChanges.failedRecordDeletes {
                    logger.warning("[CloudSync] Failed to delete: \(recordID.recordName) — \(deleteError)")
                    recordNamesToClean.append(recordID.recordName)
                }
                for deletedID in sentChanges.deletedRecordIDs {
                    recordNamesToClean.append(deletedID.recordName)
                }

                // Clear dirty records in DB (ChatStore is an actor, safe from any context)
                for name in recordNamesToClean {
                    await ChatStore.shared.clearDirtyRecord(recordName: name)
                }
                for sid in syncedSessionIds {
                    await ChatStore.shared.markSessionSynced(id: sid)
                }

                // Batch all MainActor state updates into a single hop
                await MainActor.run { [weak self] in
                    guard let self else { return }
                    // Cache saved records (etags)
                    for record in savedRecordsToCache {
                        self.cacheServerRecord(record)
                    }
                    if hasSyncDeviceSaved { self.deviceRecordFailCount = 0 }
                    // Tally successful uploads into the full-sync bucket if
                    // one is currently active (see `forceFullSync`).
                    if self.fullSyncUploadCounts != nil {
                        for record in savedRecordsToCache {
                            self.fullSyncUploadCounts?[record.recordType, default: 0] += 1
                        }
                    }
                    // Cache conflict server records (etags for retry)
                    for record in serverRecordsToCache {
                        self.cacheServerRecord(record)
                    }
                    // Evict stale etags for NOT_FOUND records so the next send
                    // rebuilds them as fresh creates (breaks the retry loop).
                    // Flag the matching cache tier dirty (per-session vs global)
                    // so the cleared etag survives an app restart — mirroring
                    // cacheServerRecord's tiering. [T-icloud-notfound-retry-loop]
                    if !unknownItemEtagToClear.isEmpty {
                        for rid in unknownItemEtagToClear {
                            let cached = self.serverRecordCache[rid]
                            self.serverRecordCache.removeValue(forKey: rid)
                            if let rtype = cached?.recordType,
                               Self.sessionRecordTypes.contains(rtype),
                               let sessionId = Self.extractSessionId(from: rid.recordName, type: rtype) {
                                self.dirtySessionCaches.insert(sessionId)
                            } else {
                                self.globalEtagCacheDirty = true
                            }
                        }
                        logger.info("[CloudSync] cleared \(unknownItemEtagToClear.count) stale etag(s) after NOT_FOUND; records will re-create on next send")
                    }
                    // Apply the once-guard mutations collected off-MainActor.
                    // [T-icloud-notfound-retry-loop]
                    for name in unknownItemMarkInserted { self.unknownItemEtagCleared.insert(name) }
                    for name in unknownItemMarkCleared { self.unknownItemEtagCleared.remove(name) }
                    if hasSyncDeviceConflict {
                        self.deviceRecordFailCount += 1
                        if self.deviceRecordFailCount < 3 { self.queueDeviceRecord() }
                    }
                    // Re-queue conflicted records so the engine retries the
                    // save now that `serverRecordCache` holds the fresh etag.
                    if !conflictRetryIDs.isEmpty {
                        self.syncEngine?.state.add(pendingRecordZoneChanges: conflictRetryIDs.map { .saveRecord($0) })
                        logger.info("[CloudSync] re-queued \(conflictRetryIDs.count) records for immediate retry after serverRecordChanged")
                    }
                    // Re-queue transient-error records (network, throttling,
                    // server busy) so they actually get retried instead of
                    // having their dirty marks cleared. The dirty rows in DB
                    // are NOT cleared for these — clearDirtyRecord was only
                    // called for permanent-failure recordNames above.
                    if !transientRetryIDs.isEmpty {
                        self.syncEngine?.state.add(pendingRecordZoneChanges: transientRetryIDs.map { .saveRecord($0) })
                        logger.info("[CloudSync] re-queued \(transientRetryIDs.count) records after transient save error")
                    }
                    // Remove non-recoverable failures from pending queue
                    if !recordIDsToRemove.isEmpty {
                        self.syncEngine?.state.remove(pendingRecordZoneChanges: recordIDsToRemove.map { .saveRecord($0) })
                    }
                    // Write SyncLog entries (batched)
                    let now = Date()
                    for saved in sentChanges.savedRecords {
                        SyncLogStore.shared.append(SyncLogEntry(
                            timestamp: now, direction: .sent,
                            recordType: saved.recordType,
                            recordName: saved.recordID.recordName,
                            summary: Self.summaryForRecord(saved),
                            detail: saved.recordID.zoneID.zoneName,
                            deviceName: nil
                        ))
                    }
                    for failed in sentChanges.failedRecordSaves {
                        let dir: SyncLogEntry.Direction = failed.error.code == .serverRecordChanged ? .conflict : .error
                        SyncLogStore.shared.append(SyncLogEntry(
                            timestamp: now, direction: dir,
                            recordType: failed.record.recordType,
                            recordName: failed.record.recordID.recordName,
                            summary: Self.summaryForRecord(failed.record),
                            detail: failed.error.code == .serverRecordChanged
                                ? "Server record changed, will retry with updated etag"
                                : failed.error.localizedDescription,
                            deviceName: nil
                        ))
                    }
                    // Persist per-session etag caches
                    self.flushDirtySessionCaches()
                    // Persist global etag cache (Skill/EnvVar/ProviderConfig/SyncDevice)
                    self.flushGlobalEtagCacheIfNeeded()
                }

                let batchMs = (CFAbsoluteTimeGetCurrent() - batchStart) * 1000
                if batchMs > 500 {
                    await MainActor.run { [weak self] in
                        guard let self else { return }
                        logger.warning("[CloudSync] ⏱ sentRecordZoneChanges total: \(String(format: "%.0f", batchMs))ms (saved=\(savedCount) cacheSize=\(self.serverRecordCache.count)) ⚠️ SLOW")
                    }
                }
            }

        case .willFetchChanges:
            Task { @MainActor [weak self] in
                self?.syncStatus = .syncing
                self?.fetchHadRemoteChanges = false
            }

        case .didFetchChanges:
            Task { @MainActor [weak self] in
                guard let self else { return }
                if self.isRestoringFromCloud {
                    logger.info("[CloudSync] Cloud restore complete — resuming normal sync mode")
                    self.isRestoringFromCloud = false
                }
                self.syncStatus = .idle
                self.lastSyncDate = Date()
                if self.fetchHadRemoteChanges {
                    logger.info("[iCloud] didFetchChanges completed WITH changes, posting notification")
                    NotificationCenter.default.post(name: .cloudSyncDidFetchChanges, object: nil)
                } else {
                    logger.info("[iCloud] didFetchChanges completed, no remote changes — skipping notification")
                }
            }

        case .willSendChanges:
            // Don't call refreshPendingChanges here — this is a delegate callback
            // where async work may not complete before nextRecordZoneChangeBatch runs.
            // refreshPendingChanges is called in triggerSend() BEFORE engine.sendChanges().
            Task { @MainActor [weak self] in
                self?.syncStatus = .syncing
            }

        case .didSendChanges:
            // Don't manually retry here. CKSyncEngine manages its own send cycles —
            // if pendingChanges still has records, it will call willSendChanges again.
            // Manual triggerSend() caused double refreshPendingChanges (once from
            // willSendChanges callback, once from the retry), building duplicate
            // CKRecords that hit "record to insert already exists" errors.
            Task { @MainActor [weak self] in
                self?.syncStatus = .idle
            }

        case .willFetchRecordZoneChanges, .didFetchRecordZoneChanges:
            break

        @unknown default:
            break
        }
    }

    nonisolated func nextRecordZoneChangeBatch(
        _ context: CKSyncEngine.SendChangesContext,
        syncEngine: CKSyncEngine
    ) async -> CKSyncEngine.RecordZoneChangeBatch? {
        // No await — reads from thread-safe PendingRecordChanges, no re-entry possible.
        let (allRecords, allDeleteIDs) = pendingChanges.takeAll()

        guard !allRecords.isEmpty || !allDeleteIDs.isEmpty else { return nil }

        // CloudKit enforces a 400-item batch limit. Prioritize deletes (small),
        // then records. Overflow goes back into pendingChanges for next cycle.
        let maxBatchSize = 400
        let deleteIDs = Array(allDeleteIDs.prefix(maxBatchSize))
        let remaining = maxBatchSize - deleteIDs.count
        let records = Array(allRecords.prefix(remaining))

        // Put overflow back for next batch
        let overflowRecords = Array(allRecords.dropFirst(remaining))
        let overflowDeletes = Array(allDeleteIDs.dropFirst(maxBatchSize))
        if !overflowRecords.isEmpty || !overflowDeletes.isEmpty {
            pendingChanges.append(records: overflowRecords, deleteIDs: overflowDeletes)
        }

        return CKSyncEngine.RecordZoneChangeBatch(
            recordsToSave: records,
            recordIDsToDelete: deleteIDs,
            atomicByZone: false
        )
    }
}
