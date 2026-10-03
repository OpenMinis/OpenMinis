import Foundation
import Security
import os.log

private let logger = AppLogger(category: "EnvVarStore")

// MARK: - EnvVarEntry

struct EnvVarEntry: Identifiable, Codable {
    let id: String
    var key: String
    var createdAt: Date
    /// Optional human-readable description of what this variable is for. Empty
    /// string when omitted. Stored in the JSON metadata file; not secret.
    var note: String

    init(id: String = UUID().uuidString, key: String, createdAt: Date = Date(), note: String = "") {
        self.id = id
        self.key = key
        self.createdAt = createdAt
        self.note = note
    }

    // Custom decoder so older entries without a `note` field still load.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(String.self, forKey: .id)
        self.key = try c.decode(String.self, forKey: .key)
        self.createdAt = try c.decode(Date.self, forKey: .createdAt)
        self.note = (try? c.decode(String.self, forKey: .note)) ?? ""
    }
}

// MARK: - EnvVarStore

@MainActor
final class EnvVarStore: ObservableObject {
    static let shared = EnvVarStore()

    @Published private(set) var entries: [EnvVarEntry] = []

    private let fileURL: URL
    nonisolated private static let keychainService = "com.openminis.app.envvar"

    init() {
        let libraryURL = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let baseURL = libraryURL.appendingPathComponent("MinisChat", isDirectory: true)
        try? FileManager.default.createDirectory(at: baseURL, withIntermediateDirectories: true)
        self.fileURL = baseURL.appendingPathComponent("env-vars.json")
        self.entries = Self.loadEntries(from: fileURL)
        scheduleLegacyRecordCleanupIfNeeded()
    }

    // MARK: - Legacy whole-file record cleanup

    private static let legacyCleanupKey = "cloudSync.envVar.legacyV2RecordDeleted"

    /// One-time job that removes the old EnvVarV2 whole-file record
    /// from this device's cloud zone after the first per-variable
    /// EnvVarItem push round. Without this, the legacy record stays
    /// on iCloud forever, taking storage and confusing other devices'
    /// inbound merger.
    ///
    /// Sequencing:
    ///   1. Re-mark every local entry dirty as EnvVarItem (idempotent —
    ///      no-op if cloud already has them).
    ///   2. Queue an op=delete dirty row for EnvVarV2 (recordId
    ///      "env-vars"). SyncCore.sendNow drains the EnvVarItem
    ///      upserts in the same batch as the EnvVarV2 delete; CloudKit
    ///      orders the upserts before the delete by record id, so the
    ///      new schema is in cloud before the old record disappears.
    ///   3. Persist `legacyCleanupKey = true` so subsequent launches
    ///      skip this work.
    private func scheduleLegacyRecordCleanupIfNeeded() {
        guard !UserDefaults.standard.bool(forKey: Self.legacyCleanupKey) else { return }
        // Defer past actor init to avoid touching ChatStore before its
        // SyncCore wiring has completed (ChatStore actor is also being
        // brought up around the same time on app launch).
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 5_000_000_000) // 5s
            guard let self else { return }
            await MainActor.run {
                self.markAllEntriesDirty()
                Task { await ChatStore.shared.markDirty(
                    recordType: "EnvVar",
                    recordId: "env-vars",
                    operation: "delete"
                ) }
                UserDefaults.standard.set(true, forKey: Self.legacyCleanupKey)
                logger.info("[EnvVarStore] legacy EnvVarV2 cleanup scheduled (\(self.entries.count) entries re-emitted as EnvVarItem)")
            }
        }
    }

    // MARK: - Persistence (key list)

    private static func loadEntries(from url: URL) -> [EnvVarEntry] {
        guard let data = try? Data(contentsOf: url),
              let entries = try? JSONDecoder().decode([EnvVarEntry].self, from: data) else {
            return []
        }
        return entries
    }

    /// Reload entries from disk (e.g. after iCloud sync merges new data).
    func reloadFromDisk() {
        entries = Self.loadEntries(from: fileURL)
        // [T-envvar-redactor-reload-invalidate] The iCloud whole-file merger
        // (CloudSyncEngine.mergeEnvVars) rewrites env-vars.json directly and
        // then calls this. When the new key's value already arrived via iCloud
        // Keychain, importEnvVarSecrets skips saveValue, so nothing else drops
        // the redactor cache and the new secret would stay unmasked.
        EnvVarRedactor.invalidateCache()
    }

    private func saveEntries() {
        do {
            let data = try JSONEncoder().encode(entries)
            try data.write(to: fileURL, options: .atomic)
        } catch {
            logger.error("Failed to save env var entries: \(error)")
        }
        // [T-envvar-redactor-main-thread-keychain] The redactor caches the
        // decoded values so it does not re-read disk + keychain on every tool
        // result. Every entry mutation funnels through here, so this is the one
        // place that has to drop the cache — a newly added secret is then
        // masked from the very next tool output.
        EnvVarRedactor.invalidateCache()
        // v2 sync uses per-variable EnvVarItem records. Per-key markDirty
        // is issued by the call sites that actually mutate a specific
        // entry (add / update / delete / updateNote). Bulk re-emit when
        // we don't know which key changed (e.g. file rewritten via
        // SyncDirtyScanner) is handled by markAllEntriesDirty.
        //
        // Legacy EnvVar (whole-file) markDirty is no longer issued —
        // SyncedEnvVars builder returns nil, so any leftover dirty rows
        // get drained as no-ops.
    }

    /// Mark a single variable's per-key sync record as dirty. Caller
    /// passes the entry's UUID (EnvVarEntry.id), which is also the
    /// EnvVarItem record's recordName.
    fileprivate func markEntryDirty(entryId: String, operation: String = "upsert") {
        Task { await ChatStore.shared.markDirty(
            recordType: "EnvVarItem",
            recordId: entryId,
            operation: operation
        ) }
    }

    /// Re-emit per-key markDirty for every current entry. Used by
    /// markAllLocalForReupload and any post-restore path that doesn't
    /// know which keys changed.
    func markAllEntriesDirty() {
        for entry in entries {
            markEntryDirty(entryId: entry.id, operation: "upsert")
        }
    }

    // MARK: - Keychain (values)

    /// Persist a value under `key` in the synchronizable Keychain.
    ///
    /// Previously this did delete-then-add unconditionally. That had a
    /// data-loss window: if the two SecItemDelete calls didn't fully remove
    /// the prior synchronizable item (iCloud Keychain replication lag), the
    /// following SecItemAdd returned errSecDuplicateItem — the old value was
    /// already deleted and the new value never landed, so the variable read
    /// back EMPTY. That is the intermittent "saved a value but it comes back
    /// blank" the user hit (e.g. WEBDAV_USER).
    ///
    /// Now: update-in-place first; only add when the item genuinely doesn't
    /// exist; and on a duplicate race, fall back to update. The prior value is
    /// never deleted before the new one is committed, so a failed write leaves
    /// the old value intact instead of blanking it. Returns whether the value
    /// is now persisted.
    @discardableResult
    nonisolated private static func saveValue(_ value: String, forKey key: String) -> Bool {
        let syncMatch: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: key,
            kSecAttrSynchronizable as String: true,
        ]
        let attrs: [String: Any] = [
            kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]

        // Remove any legacy NON-synchronizable item for this key so reads
        // (which prefer the sync item) don't shadow it — but do this only
        // after we know the sync write will be attempted, and never let its
        // result blank the value.
        func dropLegacyNonSyncItem() {
            var legacy = syncMatch
            legacy[kSecAttrSynchronizable as String] = false
            SecItemDelete(legacy as CFDictionary)
        }

        var status = SecItemUpdate(syncMatch as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            var addQuery = syncMatch
            addQuery.merge(attrs) { _, new in new }
            status = SecItemAdd(addQuery as CFDictionary, nil)
            // Lost a race: the item appeared between our update and add
            // (replication or a concurrent write). Update it instead of
            // failing — this is the exact case that used to blank the value.
            if status == errSecDuplicateItem {
                status = SecItemUpdate(syncMatch as CFDictionary, attrs as CFDictionary)
            }
        }

        if status == errSecSuccess {
            dropLegacyNonSyncItem()
            // [T-envvar-redactor-main-thread-keychain] A VALUE can change
            // without `saveEntries()` running (the sync path writes values
            // directly), so the redactor's cache has to be dropped here too or
            // it would keep masking the old secret and miss the new one.
            EnvVarRedactor.invalidateCache()
            return true
        }
        logger.error("Keychain save failed for \(key): OSStatus \(status) — prior value left intact")
        return false
    }

    /// [T-envvar-redactor-crash] One fresh `result` per SecItemCopyMatching.
    ///
    /// This used to declare a single `var result: AnyObject?` and pass `&result`
    /// to BOTH calls. SecItemCopyMatching follows the Copy rule: it hands back a
    /// +1 reference by writing straight through the pointer, without releasing
    /// whatever was there. Swift, meanwhile, treats the inout `AnyObject?` as an
    /// owning reference. So on the fallback call the first call's +1 object was
    /// overwritten (leaked) while ARC's ownership bookkeeping still pointed at
    /// it, and the eventual release landed on a stale pointer — EXC_BAD_ACCESS
    /// at 0xffffffff inside CFRelease, surfacing as a crash in whatever happened
    /// to be tearing down at the time (field report: EnvVarRedactor's decoded
    /// entry array, mid-tool-call, iOS 16.7).
    ///
    /// Only reachable when the synchronizable lookup misses AND a legacy
    /// non-synchronizable item exists, which is why it took a long-lived
    /// session to hit.
    nonisolated private static func loadValue(forKey key: String) -> String? {
        func copyData(_ query: [String: Any]) -> Data? {
            var result: AnyObject?
            guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
            return result as? Data
        }
        // Try synchronizable first
        let syncQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: key,
            kSecAttrSynchronizable as String: true,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        if let data = copyData(syncQuery) { return String(data: data, encoding: .utf8) }
        // Fallback to legacy non-sync
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        guard let data = copyData(query) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    nonisolated private static func deleteValue(forKey key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
        var syncQuery = query
        syncQuery[kSecAttrSynchronizable as String] = true
        SecItemDelete(syncQuery as CFDictionary)
        // [T-envvar-redactor-main-thread-keychain] Deleting is a value change
        // too — without this the redactor would keep masking a secret the user
        // has removed.
        EnvVarRedactor.invalidateCache()
    }

    /// Non-isolated read for use from CloudSyncEngine (background thread).
    nonisolated static func loadValueSync(forKey key: String) -> String? {
        loadValue(forKey: key)
    }

    /// Non-isolated write for use from CloudSyncEngine (background thread).
    nonisolated static func saveValueSync(_ value: String, forKey key: String) {
        saveValue(value, forKey: key)
    }

    // MARK: - Validation

    /// Valid env var name: starts with a letter, contains only letters, digits, and underscores.
    static let keyRegex = /^[A-Za-z][A-Za-z0-9_]*$/

    static func isValidKey(_ key: String) -> Bool {
        key.wholeMatch(of: keyRegex) != nil
    }

    // MARK: - Value Sanitization

    /// Strip invisible/control characters that iOS paste can introduce (e.g. \u{9b} CSI).
    /// Keeps only printable ASCII and common whitespace (space, tab).
    private static func sanitizeValue(_ value: String) -> String {
        String(value.unicodeScalars.filter { scalar in
            // Allow printable ASCII (0x20-0x7E) and tab (0x09)
            (scalar.value >= 0x20 && scalar.value <= 0x7E) || scalar.value == 0x09
        })
    }

    // MARK: - Public API

    func add(key: String, value: String, note: String = "") {
        let trimmedKey = key.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !trimmedKey.isEmpty, Self.isValidKey(trimmedKey) else { return }

        // Don't allow duplicate keys
        guard !entries.contains(where: { $0.key == trimmedKey }) else {
            logger.warning("Duplicate env var key: \(trimmedKey)")
            return
        }

        let cleanValue = Self.sanitizeValue(value)
        // Commit the value to the Keychain FIRST. If it fails, don't insert a
        // list entry — otherwise the variable shows up with a blank value.
        guard Self.saveValue(cleanValue, forKey: trimmedKey) else {
            logger.error("Add env var aborted — Keychain write failed for \(trimmedKey)")
            return
        }
        let entry = EnvVarEntry(key: trimmedKey, note: note)
        entries.append(entry)
        saveEntries()
        markEntryDirty(entryId: entry.id, operation: "upsert")
        logger.info("Added env var: \(trimmedKey)")
    }

    /// Update an entry. Pass `note: nil` to leave the existing note untouched
    /// (used by the deep-link overwrite path which only updates the value).
    func update(id: String, key: String, value: String, note: String? = nil) {
        guard let idx = entries.firstIndex(where: { $0.id == id }) else { return }

        let oldKey = entries[idx].key
        let trimmedKey = key.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !trimmedKey.isEmpty, Self.isValidKey(trimmedKey) else { return }

        // If key changed, delete old keychain entry and check for duplicates
        if oldKey != trimmedKey {
            guard !entries.contains(where: { $0.key == trimmedKey && $0.id != id }) else {
                logger.warning("Cannot rename to duplicate key: \(trimmedKey)")
                return
            }
            Self.deleteValue(forKey: oldKey)
        }

        let cleanValue = Self.sanitizeValue(value)
        // Commit the new value FIRST; only mutate the entry (and persist the
        // key rename) once the Keychain write succeeds, so a failed write
        // never leaves the list pointing at a blanked value. On key rename the
        // old value was already migrated/removed above; if the new write
        // fails, re-log so the caller can retry.
        guard Self.saveValue(cleanValue, forKey: trimmedKey) else {
            logger.error("Update env var aborted — Keychain write failed for \(trimmedKey)")
            return
        }
        entries[idx].key = trimmedKey
        if let note { entries[idx].note = note }
        saveEntries()
        markEntryDirty(entryId: entries[idx].id, operation: "upsert")
        logger.info("Updated env var: \(trimmedKey)")
    }

    /// Update only the human-readable note without touching the
    /// Keychain-stored value. Used by minis-config so the agent can
    /// annotate variables without ever seeing or overwriting the
    /// secret.
    func updateNote(id: String, note: String) {
        guard let idx = entries.firstIndex(where: { $0.id == id }) else { return }
        entries[idx].note = note
        saveEntries()
        markEntryDirty(entryId: id, operation: "upsert")
    }

    func delete(id: String) {
        guard let idx = entries.firstIndex(where: { $0.id == id }) else { return }
        let key = entries[idx].key
        entries.remove(at: idx)
        Self.deleteValue(forKey: key)
        saveEntries()
        // Mark the entry's per-key record for cloud deletion. Peers will
        // hard-delete it via applyEnvVarItemDeletion. The recordId is the
        // entry UUID, not the key — same as upsert path.
        markEntryDirty(entryId: id, operation: "delete")
        logger.info("Deleted env var: \(key)")
    }

    func value(forKey key: String) -> String? {
        Self.loadValue(forKey: key)
    }

    func entry(id: String) -> EnvVarEntry? {
        entries.first(where: { $0.id == id })
    }

    // MARK: - Sync inbound (per-variable)

    /// Apply an inbound EnvVarItem. Lookup by id; if a local entry with
    /// the same id exists, LWW-by-updatedAt decides the winner. If no
    /// local entry exists by id but another one with the same key
    /// exists (created independently on two devices before they ever
    /// synced), keep both — they have distinct UUIDs and the user can
    /// reconcile manually.
    ///
    /// `value` is empty when the sender sent an empty/missing valueB64
    /// (e.g. legacy build that didn't fill it). In that case we leave
    /// the local Keychain value untouched rather than wiping it.
    func applyRemoteItem(
        id: String, key: String, value: String, note: String,
        createdAt: Date, updatedAt: Date
    ) {
        if let idx = entries.firstIndex(where: { $0.id == id }) {
            // Local entry exists — LWW by updatedAt. We don't track a
            // per-entry updatedAt locally yet (the JSON file only has
            // createdAt), so we accept any inbound update if the field
            // values actually differ. This gives us last-writer-wins
            // semantics relative to the inbound stream — both sides'
            // mutations go through markEntryDirty and produce records
            // with monotonic updatedAt = Date(), so the cloud serializes
            // them and the receiver applies the latest one it sees.
            let existing = entries[idx]
            let keyChanged = existing.key != key
            entries[idx].key = key
            entries[idx].note = note
            // Don't overwrite createdAt — keep the earliest timestamp
            // we've ever seen for this id.
            if existing.createdAt > createdAt { entries[idx].createdAt = createdAt }
            if !value.isEmpty {
                if keyChanged {
                    Self.deleteValue(forKey: existing.key)
                }
                Self.saveValue(value, forKey: key)
            } else if keyChanged {
                // Key renamed but no value supplied — migrate the existing
                // value under the new key so we don't strand it.
                if let oldVal = Self.loadValue(forKey: existing.key) {
                    Self.saveValue(oldVal, forKey: key)
                    Self.deleteValue(forKey: existing.key)
                }
            }
            saveEntries()
            logger.info("[EnvVarStore] applyRemoteItem UPDATE id=\(id.prefix(8)) key=\(key)")
        } else {
            // New entry from a peer.
            let entry = EnvVarEntry(id: id, key: key, createdAt: createdAt, note: note)
            entries.append(entry)
            if !value.isEmpty {
                Self.saveValue(value, forKey: key)
            }
            saveEntries()
            logger.info("[EnvVarStore] applyRemoteItem INSERT id=\(id.prefix(8)) key=\(key)")
        }
        _ = updatedAt  // reserved for a future per-entry updatedAt column
    }

    /// Hard-delete a per-variable entry locally without re-queueing the
    /// delete back into the sync layer. Mirrors the SessionV2 inbound
    /// delete pattern. Caller is the sync hydrator; the cloud already
    /// holds the tombstone.
    func applyRemoteDeletion(id: String) {
        guard let idx = entries.firstIndex(where: { $0.id == id }) else { return }
        let key = entries[idx].key
        entries.remove(at: idx)
        Self.deleteValue(forKey: key)
        saveEntries()
        logger.info("[EnvVarStore] applyRemoteDeletion id=\(id.prefix(8)) key=\(key)")
    }

    /// Returns all env vars as a dictionary for injection into shell execution.
    nonisolated func allAsDict() -> [String: String] {
        var dict: [String: String] = [:]
        // Read entries from the JSON file directly (avoid MainActor requirement)
        let libraryURL = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let fileURL = libraryURL.appendingPathComponent("MinisChat/env-vars.json")
        guard let data = try? Data(contentsOf: fileURL),
              let entries = try? JSONDecoder().decode([EnvVarEntry].self, from: data) else {
            return dict
        }
        for entry in entries {
            if let val = Self.loadValue(forKey: entry.key) {
                dict[entry.key] = val
            }
        }
        return dict
    }
}
