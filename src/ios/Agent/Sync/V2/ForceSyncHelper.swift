import Foundation

/// One-button bidirectional force sync for the management views
/// (Memory / Providers / Model Groups / etc).
///
/// Push side: re-marks every local row of `recordTypes` as dirty so the
/// next outbound batch carries the current local state — useful when the
/// dirty queue was cleared or rows were never enqueued.
///
/// Pull side: tells the iCloud transport to ignore its change token and
/// query CK for every record of those types, then runs them through the
/// normal merger pipeline.
///
/// The two halves are scheduled back-to-back; the call returns as soon
/// as both are kicked off (the actual network round-trips finish later
/// asynchronously). Callers typically show a short toast on return.
@available(iOS 17.0, *)
enum ForceSyncHelper {

    /// Mark every dirty-eligible row of these types and trigger send +
    /// full fetch. Pass the record types that the calling management
    /// view actually owns. Returns the number of rows newly re-marked
    /// (purely informational — useful for the toast).
    @MainActor
    @discardableResult
    static func bidirectionalSync(recordTypes: [String]) async -> Int {
        // 1. Re-mark every row of the given types as dirty. We don't
        //    have a generic "for every X" enumerator here, so each
        //    record type's own helper is responsible for re-marking
        //    its current local rows. This helper is invoked by callers
        //    that have already done that (e.g. SkillStore.forceMarkDirty
        //    per skill); the rows are now sitting in sync_dirty_records.
        //
        //    The count we report is whatever the caller passed back to
        //    us, not something computed here.
        _ = recordTypes
        // 2. Kick off send immediately. Use sendNow (not scheduleSend)
        //    so we bypass the debounce-cancel chain — when many markDirty
        //    calls have just happened (which is the whole point of
        //    force-sync) every new one cancels the in-flight 3s sleep,
        //    so scheduleSend can spin forever without firing.
        Task.detached {
            await SyncCore.shared.sendNow(trigger: .manual)
            await SyncCore.shared.fullFetchAndReconcile(trigger: .manual)
        }
        return 0
    }

    /// Convenience for record types whose local rows live in a single
    /// place we can enumerate from this file (singletons + memory dir).
    /// Returns the number of rows newly marked.
    @MainActor
    @discardableResult
    static func markMemoryDirty() async -> Int {
        let fm = FileManager.default
        let memDir = AIChatViewModel.minisMemoryPersistentDir
        var count = 0
        // GLOBAL.md
        let globalURL = memDir.appendingPathComponent("GLOBAL.md")
        if fm.fileExists(atPath: globalURL.path) {
            await ChatStore.shared.markDirty(recordType: "MemoryGlobalV2",
                                             recordId: "memory-global")
            count += 1
        }
        // Daily logs within the 30-day window (same rule as
        // SyncDirtyScanner.scanMemoryFiles).
        let fmt = DateFormatter(); fmt.dateFormat = "yyyy-MM-dd"
        let cutoff = Calendar.current.date(byAdding: .day, value: -30, to: Date()) ?? Date()
        if let entries = try? fm.contentsOfDirectory(at: memDir,
                                                     includingPropertiesForKeys: nil,
                                                     options: [.skipsHiddenFiles]) {
            for url in entries where url.pathExtension == "md"
                && url.lastPathComponent != "GLOBAL.md" {
                let stem = (url.lastPathComponent as NSString).deletingPathExtension
                if let fileDate = fmt.date(from: stem), fileDate >= cutoff {
                    await ChatStore.shared.markDirty(recordType: "MemoryDailyV2",
                                                     recordId: stem)
                    count += 1
                }
            }
        }
        return count
    }

    /// Mark the ProviderConfig singleton dirty (covers both providers
    /// and model groups — they share the same record).
    @MainActor
    @discardableResult
    static func markProvidersDirty() async -> Int {
        // v2 whole-file record: kept for pre-v3 peers. A v3 receiver drops
        // inbound v2 by design, so on modern fleets this line alone made the
        // Providers force-sync button a silent NO-OP — the actual sync surface
        // (the per-record v3 types below) was never re-marked, and the button
        // could not repair a diverged peer. [T-provider-forcesync-v3]
        await ChatStore.shared.markDirty(recordType: "ProviderConfig",
                                         recordId: "provider-config")
        var count = 1
        // [T-provider-forcesync-v3] Push side: re-mark EVERY v3 provider row so
        // the next outbound batch re-uploads current local state with a fresh
        // updatedAt. This is what lets a peer whose incremental window missed
        // these records (anchored-too-early, see resetProviderConfigAnchors)
        // finally receive them. Cost is bounded and user-initiated: ~1000 small
        // records ≈ 3 CK batches, only when the user presses Force Sync.
        let store = ProviderConfigStore.shared
        for inst in store.instances {
            await ChatStore.shared.markDirty(recordType: "ProviderInstanceV3", recordId: inst.id)
            count += 1
        }
        for entry in store.modelEntries {
            // .uuid, not .id — mirrors the store's own markDirty call sites
            // (the v3 record is keyed by the row uuid).
            await ChatStore.shared.markDirty(recordType: "ProviderModelEntryV3", recordId: entry.uuid)
            count += 1
        }
        for group in store.modelGroups {
            await ChatStore.shared.markDirty(recordType: "ProviderModelGroupV3", recordId: group.id)
            count += 1
        }
        if let db = store.db {
            for rid in await db.allCustomThinkingRuleIds() {
                await ChatStore.shared.markDirty(recordType: "ProviderThinkingRuleV3", recordId: rid)
                count += 1
            }
        }
        // Pull side: forget the per-type "history fully pulled" anchors so the
        // next fetchRecentV2 re-pulls FULL history for provider types instead
        // of the incremental window. Without this, records whose cloud
        // updatedAt predates the window (the observed "Default Model missing"
        // state) stay invisible forever no matter how often the user syncs.
        ICloudSharedZoneTransport.resetProviderConfigAnchors()
        return count
    }

    /// Mark SOUL.md dirty for push. Returns 1 when the file exists on
    /// disk (otherwise 0 — there's nothing to push and a dirty row
    /// would just cycle through buildSoul returning nil).
    @MainActor
    @discardableResult
    static func markSoulDirty() async -> Int {
        let url = SoulStore.fileURL
        guard FileManager.default.fileExists(atPath: url.path) else { return 0 }
        await ChatStore.shared.markDirty(recordType: "SoulV2", recordId: "soul")
        return 1
    }
}
