import Foundation
import CryptoKit

private let logger = AppLogger(category: "SyncCore")

/// Wires ChatStore (and a couple of sibling stores) into SyncCoreHydrators.
/// Each Syncable record type gets a builder (id → PortableRecord) and,
/// where available, a merger (PortableRecord → local SQLite write).
///
/// First wiring is intentionally minimal:
///   - SessionV2 / MessageV2: builder + merger (full round-trip)
///   - CompactMarkerV2 / SessionFileV2 / SkillV2 / ProviderConfigV2 /
///     EnvVarV2 / SyncDeviceV2: builder only (push works, inbound is
///     logged "merger not yet wired" and skipped). Will be filled in
///     subsequent patches.
@MainActor
enum ChatStoreSyncHydrators {

    static func registerAll() async {
        let h = SyncCoreHydrators.shared

        h.register(
            recordType: "SessionV2",
            builder: { id in await buildSession(id: id) },
            merger: { record in await mergeSession(record: record) },
            deletionApplier: { id in await deleteSession(id: id) }
        )

        h.register(
            recordType: "MessageV2",
            builder: { id in await buildMessage(id: id) },
            merger: { record in await mergeMessage(record: record) },
            deletionApplier: { id in await deleteMessage(id: id) }
        )

        h.register(
            recordType: "CompactMarkerV2",
            builder: { id in await buildCompactMarker(id: id) },
            merger: { record in await mergeCompactMarker(record: record) },
            deletionApplier: { id in await deleteCompactMarker(id: id) }
        )
        h.register(
            recordType: "FolderV2",
            builder: { id in await buildFolder(id: id) },
            merger: { record in await mergeFolder(record: record) },
            deletionApplier: { id in await applyFolderDeletion(id: id) }
        )
        h.register(
            recordType: "SessionFileV2",
            builder: { id in await buildSessionFile(id: id) },
            merger: { record in await mergeSessionFile(record: record) }
        )
        h.register(
            recordType: "SkillV2",
            builder: { id in await buildSkill(id: id) },
            merger: { record in await mergeSkill(record: record) },
            deletionApplier: { id in await applySkillDeletion(id: id) }
        )
        h.register(
            recordType: "ProviderConfigV2",
            builder: { _ in await buildProviderConfig() },
            merger: { record in await mergeProviderConfig(record: record) }
        )
        // [T-mcp-per-server-sync] Legacy whole-file servers.json record.
        // New devices NEVER emit it (builder returns nil — leftover upsert
        // dirty rows drain as no-ops; the one-time op=delete row still
        // pushes). The merger is kept so a peer on an old build can seed
        // our list — applied per-server (union) instead of a file
        // overwrite, so a stale whole-file snapshot can't clobber
        // per-item state. MCPStore.scheduleLegacyRecordCleanupIfNeeded
        // removes the cloud record once items have been pushed.
        h.register(
            recordType: "MCPServersV2",
            builder: { _ -> PortableRecord? in nil },
            merger: { record in await mergeMCPServers(record: record) }
        )
        h.register(
            recordType: "MCPServerItem",
            builder: { id in await buildMCPServerItem(id: id) },
            merger: { record in await mergeMCPServerItem(record: record) },
            deletionApplier: { id in await applyMCPServerItemDeletion(id: id) }
        )
        // v3 per-record provider sync. Inbound applies via SQL UPSERT
        // and refreshes the ProviderConfigStore in-memory cache; op=delete
        // routes through deletionApplier which uses CASCADE on the
        // instance FK to also remove the instance's model entries. Local
        // outbound emission lives in ProviderConfigStore.save() (S3).
        h.register(
            recordType: "ProviderInstanceV3",
            builder: { id in await buildProviderInstanceV3(id: id) },
            merger: { record in await mergeProviderInstanceV3(record: record) },
            deletionApplier: { id in await deleteProviderInstanceV3(id: id) }
        )
        h.register(
            recordType: "ProviderModelEntryV3",
            builder: { id in await buildProviderModelEntryV3(id: id) },
            merger: { record in await mergeProviderModelEntryV3(record: record) },
            deletionApplier: { id in await deleteProviderModelEntryV3(id: id) }
        )
        h.register(
            recordType: "ProviderModelGroupV3",
            builder: { id in await buildProviderModelGroupV3(id: id) },
            merger: { record in await mergeProviderModelGroupV3(record: record) },
            deletionApplier: { id in await deleteProviderModelGroupV3(id: id) }
        )
        h.register(
            recordType: "ProviderThinkingRuleV3",
            builder: { id in await buildProviderThinkingRuleV3(id: id) },
            merger: { record in await mergeProviderThinkingRuleV3(record: record) },
            deletionApplier: { id in await deleteProviderThinkingRuleV3(id: id) }
        )
        // Legacy whole-file env-vars record. New devices NEVER emit it
        // (builder returns nil — caller treats nil as "this builder
        // chose not to push" and drops the dirty row). The merger is
        // kept so a peer running the old build can still seed our
        // env-vars on first sync. Once at least one EnvVarItem has been
        // pushed to cloud, EnvVarMigration.cleanupLegacyRecord deletes
        // the old whole-file record server-side.
        h.register(
            recordType: "EnvVarV2",
            builder: { _ -> PortableRecord? in nil },
            merger: { record in await mergeEnvVars(record: record) }
        )
        h.register(
            recordType: "EnvVarItem",
            builder: { id in await buildEnvVarItem(id: id) },
            merger: { record in await mergeEnvVarItem(record: record) },
            deletionApplier: { id in await applyEnvVarItemDeletion(id: id) }
        )
        h.register(
            recordType: "SyncDeviceV2",
            builder: { id in await buildDevice(id: id) },
            merger: { record in await mergeDevice(record: record) }
        )
        // SyncCore loads dirty rows with v2Only: true, so the hydrator
        // must register under the V2-suffixed name; otherwise lookup
        // misses and the row keeps requeueing forever without ever
        // hitting buildSoul. (Audit found: register("Soul") combined
        // with v2-only dirty-row reader silently dropped every Soul
        // push for the entire history of the v2 sync engine — the
        // dashboard's NOT_FOUND-on-SoulV2 traffic is the downstream
        // symptom because the schema was never auto-created.)
        h.register(
            recordType: "SoulV2",
            builder: { _ in await buildSoul() },
            merger: { record in await mergeSoul(record: record) }
        )
        h.register(
            recordType: "MemoryGlobalV2",
            builder: { _ in await buildMemoryGlobal() },
            merger: { record in await mergeMemoryGlobal(record: record) }
        )
        h.register(
            recordType: "MemoryDailyV2",
            builder: { id in await buildMemoryDaily(dateKey: id) },
            merger: { record in await mergeMemoryDaily(record: record) }
        )

        logger.info("[SyncCore] ChatStoreSyncHydrators registered: \(SyncCoreHydrators.shared.registeredRecordTypes.count) types")
    }

    // MARK: - Session

    private static func buildSession(id: String) async -> PortableRecord? {
        guard let session = await ChatStore.shared.getSession(id) else { return nil }
        let extras = await ChatStore.shared.getSessionExtras(id)
        let synced = SyncedSession.from(session, memoryEnabled: extras.memoryEnabled, modelBinding: extras.modelBinding)
        return SyncableTypeRegistry.shared
            .metadata(for: "SessionV2")?
            .buildPortable(synced)
    }

    private static func mergeSession(record: PortableRecord) async {
        // Reconstruct a SyncedSession from PortableRecord fields, then
        // pass through ChatStore.mergeRemoteSession (which already has
        // LWW-by-updatedAt logic for v1 — reused as-is).
        guard let id = stringField(record, "sessionId") else { return }
        let title = optionalStringField(record, "title")
        let modelId = stringField(record, "modelId") ?? "unknown"
        let createdAt = dateField(record, "createdAt") ?? Date()
        let updatedAt = dateField(record, "updatedAt") ?? record.updatedAt
        let category = optionalStringField(record, "category")
        let memoryEnabled = (intField(record, "memoryEnabled") ?? 1) == 1
        let modelBinding = optionalStringField(record, "modelBinding")
        let pinnedAt = dateField(record, "pinnedAt")
        // Presence of the key (even as explicit null) distinguishes a
        // new-build peer saying "ungrouped" from an old-build peer that
        // doesn't know the field exists — only the former may clear local.
        let folderFieldPresent = record.fields["folderId"] != nil
        let folderId = optionalStringField(record, "folderId")

        var session = ChatSession(
            id: id, title: title, category: category, modelId: modelId,
            createdAt: createdAt, updatedAt: updatedAt
        )
        session.pinnedAt = pinnedAt
        // v2 doesn't currently track per-device origin in PortableRecord;
        // pass an empty string as a sentinel ("unknown remote device").
        await ChatStore.shared.mergeRemoteSession(
            session, fromDeviceId: "",
            memoryEnabled: memoryEnabled,
            modelBinding: modelBinding,
            remotePinnedAtRaw: pinnedAt,
            remoteFolderId: folderId,
            remoteHasFolderField: folderFieldPresent
        )
    }

    private static func deleteSession(id: String) async {
        // Per §3.3.2: SessionV2 deletions are NOT propagated as hard
        // deletes — they soft-tombstone instead.
        _ = await TombstoneManager.shared.applyRemoteSessionDeletion(sessionId: id)
    }

    // MARK: - Folder

    private static func buildFolder(id: String) async -> PortableRecord? {
        guard let folder = await ChatStore.shared.getFolder(id) else { return nil }
        let synced = SyncedFolder.from(folder)
        return SyncableTypeRegistry.shared
            .metadata(for: "FolderV2")?
            .buildPortable(synced)
    }

    private static func mergeFolder(record: PortableRecord) async {
        guard let id = stringField(record, "folderId") else { return }
        let updatedAt = dateField(record, "updatedAt") ?? record.updatedAt
        // [T-icloud-record-delete-resurrection] Same guard as mergeSkill: a
        // folder dissolved locally moments ago can be re-pulled by
        // fetchRecentV2 before the cloud delete lands.
        if await ChatStore.shared.isRecentlyDeletedRecord(type: "Folder", id: id, remoteUpdatedAt: updatedAt) {
            logger.info("[SyncCore] mergeFolder SKIP (recently deleted locally): id=\(id.prefix(8))")
            return
        }
        let folder = ChatFolder(
            id: id,
            name: stringField(record, "name") ?? "",
            icon: optionalStringField(record, "icon"),
            color: optionalStringField(record, "color"),
            origin: stringField(record, "origin") ?? "manual",
            sortIndex: intField(record, "sortIndex") ?? 0,
            pinnedAt: dateField(record, "pinnedAt"),
            desc: optionalStringField(record, "desc"),
            createdAt: dateField(record, "createdAt") ?? Date(),
            updatedAt: updatedAt
        )
        guard !folder.name.isEmpty else { return }
        await ChatStore.shared.applyRemoteFolder(folder)
    }

    /// Inbound op=delete on FolderV2: members' folder_id cleared, folder row
    /// dropped, NO session deleted, no re-queue (delete-loop guard — the
    /// deleting peer already pushed the tombstone and its members' updates).
    private static func applyFolderDeletion(id: String) async {
        await ChatStore.shared.applyRemoteFolderDeletion(id: id)
        logger.info("[SyncCore] applied FolderV2 deletion: id=\(id.prefix(8))")
    }

    // MARK: - Message

    private static func buildMessage(id: String) async -> PortableRecord? {
        guard let msg = await ChatStore.shared.loadSingleMessage(id: id) else { return nil }
        let updatedAt = await ChatStore.shared.messageUpdatedAt(id: id)
        let synced = SyncedMessage.from(msg, updatedAt: updatedAt)
        return SyncableTypeRegistry.shared
            .metadata(for: "MessageV2")?
            .buildPortable(synced)
    }

    private static func mergeMessage(record: PortableRecord) async {
        guard let id = stringField(record, "messageId"),
              let sessionId = stringField(record, "sessionId"),
              let role = stringField(record, "role"),
              let createdAt = dateField(record, "createdAt") else { return }
        let partsJson = stringField(record, "partsJson") ?? "[]"
        let tokenUsageJson = optionalStringField(record, "tokenUsageJson")
        let reasoningContent = optionalStringField(record, "reasoningContent")
        let streamInterruptCount = intField(record, "streamInterruptCount") ?? 0
        let sortOrder = intField(record, "sortOrder") ?? 0
        let updatedAt = dateField(record, "updatedAt") ?? record.updatedAt

        // [T-token-attribution-snapshot] Absent from a sender on an older
        // build; nil flows through to the COALESCE guards in
        // mergeRemoteMessage, which keep whatever this device already has.
        await ChatStore.shared.mergeRemoteMessage(
            id: id, sessionId: sessionId, role: role,
            partsJson: partsJson, createdAt: createdAt,
            tokenUsageJson: tokenUsageJson, sortOrder: sortOrder,
            reasoningContent: reasoningContent,
            streamInterruptCount: streamInterruptCount,
            updatedAt: updatedAt,
            modelId: optionalStringField(record, "modelId"),
            modelDisplayName: optionalStringField(record, "modelDisplayName"),
            providerType: optionalStringField(record, "providerType"),
            providerInstanceId: optionalStringField(record, "providerInstanceId")
        )
    }

    private static func deleteMessage(id: String) async {
        // Per §3.3.1: single-message deletions are propagated. Use the
        // same ChatStore helper v1 uses; it already takes care of *not*
        // re-marking the row dirty (the deletion came from cloud, no
        // need to re-push).
        await ChatStore.shared.deleteLocalMessage(messageId: id)
        // [T-ios-log-noise-reduction] INFO→DEBUG: per-record deletion trace,
        // ~6.4k lines per batch sync; not actionable in production.
        logger.debug("[SyncCore] applied MessageV2 deletion: id=\(id.prefix(8))")
    }

    // MARK: - CompactMarker

    private static func buildCompactMarker(id: String) async -> PortableRecord? {
        guard let m = await ChatStore.shared.getCompactMarker(id: id) else { return nil }
        let synced = SyncedCompactMarker.from(m)
        return SyncableTypeRegistry.shared
            .metadata(for: "CompactMarkerV2")?
            .buildPortable(synced)
    }

    // MARK: - Device

    private static func buildDevice(id: String) async -> PortableRecord? {
        // Only build for our own deviceId. Other devices' SyncDeviceV2
        // records arrive via fetch and shouldn't be re-emitted.
        guard id == DeviceIdentity.deviceId else { return nil }
        let displayName = UploadPolicy.customDeviceName ?? DeviceIdentity.deviceName
        let synced = SyncedDevice(
            id: id,
            deviceName: displayName,
            lastSeen: Date(),
            osVersion: DeviceIdentity.osVersion,
            uploadTypes: UploadPolicy.currentUploadTypesString(),
            appVersion: Self.appVersionCode
        )
        return SyncableTypeRegistry.shared
            .metadata(for: "SyncDeviceV2")?
            .buildPortable(synced)
    }

    private static let appVersionCode: Int = {
        let dict = Bundle.main.infoDictionary
        if let s = dict?["CFBundleVersion"] as? String, let i = Int(s) { return i }
        return 0
    }()

    // MARK: - CompactMarker merge

    private static func mergeCompactMarker(record: PortableRecord) async {
        guard let id = stringField(record, "markerId"),
              let sessionId = stringField(record, "sessionId") else { return }
        let summary = stringField(record, "summary") ?? ""
        let firstKeptSortOrder = intField(record, "firstKeptSortOrder") ?? 0
        let compactedCount = intField(record, "compactedCount") ?? 0
        let createdAt = dateField(record, "createdAt") ?? Date()
        let uiBoundarySortOrder = intField(record, "uiBoundarySortOrder")
        let boundaryMessageId = optionalStringField(record, "boundaryMessageId")
        let firstKeptMessageId = optionalStringField(record, "firstKeptMessageId")
        let lastCompactedMessageId = optionalStringField(record, "lastCompactedMessageId")
        // markerVersion absent on records from older builds → treat as v1.
        let markerVersion = intField(record, "markerVersion") ?? 1
        let marker = CompactMarker(
            id: id, sessionId: sessionId, summary: summary,
            firstKeptSortOrder: firstKeptSortOrder, compactedCount: compactedCount,
            createdAt: createdAt,
            uiBoundarySortOrder: uiBoundarySortOrder,
            boundaryMessageId: boundaryMessageId,
            firstKeptMessageId: firstKeptMessageId,
            lastCompactedMessageId: lastCompactedMessageId,
            version: markerVersion
        )
        await ChatStore.shared.mergeRemoteCompactMarker(marker)
    }

    /// Apply a remote tombstone for a compact marker. Mirrors deleteMessage:
    /// uses the local-only delete path so we don't bounce the deletion back
    /// to the cloud. Required for revertCompact to propagate across devices —
    /// without this, peer devices ignore the tombstone and re-push the marker
    /// on their next sync, resurrecting it.
    private static func deleteCompactMarker(id: String) async {
        let removed = await ChatStore.shared.deleteLocalCompactMarker(id: id)
        logger.info("[SyncCore] applied CompactMarkerV2 deletion: id=\(id.prefix(8)) removed=\(removed)")
    }

    // MARK: - SessionFile

    /// SessionFile recordId is "sessionId:relativePath" (compositeKey).
    private static func buildSessionFile(id: String) async -> PortableRecord? {
        let parts = id.split(separator: ":", maxSplits: 1)
        guard parts.count == 2 else { return nil }
        let sessionId = String(parts[0])
        let relativePath = String(parts[1])
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let fileURL = library.appendingPathComponent("MinisChat/minis/\(sessionId)/\(relativePath)")
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        let attrs = try? FileManager.default.attributesOfItem(atPath: fileURL.path)
        let fileSize = (attrs?[.size] as? Int) ?? 0
        let modDate = (attrs?[.modificationDate] as? Date) ?? Date()
        // Skip oversized files (single source of truth: UploadPolicy.maxFileSizeBytes).
        let maxSize = UploadPolicy.maxFileSizeBytes
        if fileSize > maxSize {
            logger.info("[SyncCore] skipping oversized SessionFileV2: \(relativePath) size=\(fileSize) cap=\(maxSize)")
            return nil
        }
        let mime = SessionFileMimeMap.mimeType(for: fileURL.pathExtension)
        let synced = SyncedSessionFile(
            sessionId: sessionId,
            relativePath: relativePath,
            fileSize: fileSize,
            mimeType: mime,
            updatedAt: modDate,
            fileURL: fileURL
        )
        var portable = SyncableTypeRegistry.shared.metadata(for: "SessionFileV2")?.buildPortable(synced)
        // Inject the asset (the registry's buildPortable doesn't know
        // about transport-managed asset payloads).
        if var p = portable {
            let asset = PortableAsset(key: "asset", fileURL: fileURL, size: fileSize, mimeType: mime)
            p = PortableRecord(
                id: p.id, fields: p.fields, assets: ["asset": asset],
                schemaVersion: p.schemaVersion,
                minimumCompatibleVersion: p.minimumCompatibleVersion,
                unknownFields: p.unknownFields, updatedAt: p.updatedAt
            )
            portable = p
        }
        return portable
    }

    private static func mergeSessionFile(record: PortableRecord) async {
        guard let sessionId = stringField(record, "sessionId"),
              let relativePath = stringField(record, "relativePath"),
              let asset = record.assets["asset"] else {
            logger.warning("[SyncCore] mergeSessionFile: missing asset for id=\(record.id.id.prefix(16))")
            return
        }
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let destURL = library.appendingPathComponent("MinisChat/minis/\(sessionId)/\(relativePath)")
        let fm = FileManager.default
        let remoteFileUpdatedAt = dateField(record, "updatedAt") ?? record.updatedAt
        // [T-icloud-deleted-session-resurrection] Refuse to recreate a file
        // whose session was just deleted locally and isn't newer than the
        // deletion. fetchRecentV2 can pull the still-present cloud SessionFile
        // record back before the delete tombstone lands, writing the file back
        // under a session the user already removed. (The session itself is
        // blocked by mergeRemoteSession's guard; this covers the workspace
        // files so a deleted session's content doesn't reappear on disk.)
        if await ChatStore.shared.isResurrectionOfDeleted(sessionId, remoteUpdatedAt: remoteFileUpdatedAt) {
            logger.info("[SyncCore] mergeSessionFile SKIP (session recently deleted — resurrection blocked): \(sessionId.prefix(8))/\(relativePath)")
            return
        }
        // [T-icloud-cloud-overwrites-local-edits] Local-newer guard. The
        // inbound record carries `updatedAt` (= sender's file mtime), and
        // SessionFile content is user-editable on disk. Without this check
        // we unconditionally removeItem+copyItem below, so a stale cloud
        // record landing during the window between a local edit and its
        // push silently destroys the newer local file (issue #41 —
        // "cloud overwrites local"). Mirror the LWW already used by
        // mergeRemoteSession/mergeRemoteMessage: if the local file is
        // newer than the remote record (+ slack for clock drift), keep
        // local and skip the apply. A missing local file always applies.
        if let localAttrs = try? fm.attributesOfItem(atPath: destURL.path),
           let localMtime = localAttrs[.modificationDate] as? Date {
            let slack: TimeInterval = 5
            if localMtime.timeIntervalSince(remoteFileUpdatedAt) > slack {
                logger.info("[SyncCore] mergeSessionFile SKIP (local newer): \(sessionId.prefix(8))/\(relativePath) localMtime=\(localMtime) remoteUpdatedAt=\(remoteFileUpdatedAt)")
                return
            }
        }
        do {
            try fm.createDirectory(at: destURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            if fm.fileExists(atPath: destURL.path) { try? fm.removeItem(at: destURL) }
            try fm.copyItem(at: asset.fileURL, to: destURL)
            // [T-ios-log-noise-reduction] INFO→DEBUG: per-file apply trace,
            // high-volume during batch sync; not actionable in production.
            logger.debug("[SyncCore] applied SessionFileV2: \(sessionId.prefix(8))/\(relativePath) size=\(asset.size)")
        } catch {
            logger.error("[SyncCore] mergeSessionFile failed \(sessionId)/\(relativePath): \(error.localizedDescription)")
        }
    }

    // MARK: - Skill

    private static func buildSkill(id: String) async -> PortableRecord? {
        let snapshot = await MainActor.run { () -> SkillSnapshot? in
            guard let s = SkillStore.shared.skills.first(where: { $0.id == id }) else { return nil }
            let zipData = SkillStore.shared.buildSkillZipData(id)
            let body = SkillStore.shared.readSkillContent(id) ?? ""
            return SkillSnapshot(
                id: s.id, name: s.name, description: s.description,
                version: s.version, importSource: s.importSource.dbValue,
                isEnabled: s.isEnabled, installedAt: s.installedAt,
                updatedAt: s.updatedAt, bodyText: body, zipData: zipData
            )
        }
        guard let snap = snapshot else { return nil }
        // Persist zip to a temp URL so we can reference it as a PortableAsset.
        var bundleAssetURL: URL? = nil
        if let zip = snap.zipData {
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("skill-v2-\(id).zip")
            try? zip.write(to: url)
            bundleAssetURL = url
        }
        let synced = SyncedSkill(
            id: snap.id, name: snap.name, description: snap.description,
            version: snap.version, importSource: snap.importSource,
            isEnabled: snap.isEnabled ? 1 : 0,
            installedAt: snap.installedAt, updatedAt: snap.updatedAt,
            bodyText: snap.bodyText, bundleZipURL: bundleAssetURL
        )
        var portable = SyncableTypeRegistry.shared.metadata(for: "SkillV2")?.buildPortable(synced)
        if var p = portable, let zipURL = bundleAssetURL,
           let size = try? FileManager.default.attributesOfItem(atPath: zipURL.path)[.size] as? Int {
            let asset = PortableAsset(key: "bundleAsset", fileURL: zipURL, size: size, mimeType: "application/zip")
            p = PortableRecord(
                id: p.id, fields: p.fields, assets: ["bundleAsset": asset],
                schemaVersion: p.schemaVersion,
                minimumCompatibleVersion: p.minimumCompatibleVersion,
                unknownFields: p.unknownFields, updatedAt: p.updatedAt
            )
            portable = p
        }
        return portable
    }

    private static func mergeSkill(record: PortableRecord) async {
        guard let id = stringField(record, "skillId") else { return }
        let bodyText = stringField(record, "bodyText") ?? ""
        let updatedAt = dateField(record, "updatedAt") ?? record.updatedAt
        // [T-icloud-record-delete-resurrection] A skill deleted locally moments
        // ago can be re-fetched by fetchRecentV2 (CKQuery never sees deletions)
        // before the cloud delete lands; without this check the import path's
        // "doesn't exist locally → always apply" branch would resurrect it.
        if await ChatStore.shared.isRecentlyDeletedRecord(type: "Skill", id: id, remoteUpdatedAt: updatedAt) {
            logger.info("[SyncCore] mergeSkill SKIP (recently deleted locally): id=\(id.prefix(8))")
            return
        }
        let installedAt = dateField(record, "installedAt") ?? Date()
        let isEnabled = (intField(record, "isEnabled") ?? 1) == 1
        let importSource = stringField(record, "importSource") ?? "file"
        var zipData: Data? = nil
        if let asset = record.assets["bundleAsset"] {
            zipData = try? Data(contentsOf: asset.fileURL)
        }
        await MainActor.run {
            SkillStore.shared.importSkillFromSyncWithAsset(
                skillId: id, content: bodyText, zipData: zipData,
                source: SkillImportSource.fromDB(importSource),
                isEnabled: isEnabled,
                installedAt: installedAt, updatedAt: updatedAt
            )
        }
        logger.info("[SyncCore] applied SkillV2: id=\(id.prefix(8)) bodyLen=\(bodyText.count) hasZip=\(zipData != nil)")
    }

    /// Apply an inbound op=delete on SkillV2: hard-delete the local
    /// skill without re-queueing a deletion back into the sync layer.
    /// Same pattern as SessionV2 / EnvVarItem: the cloud already has
    /// the tombstone; re-queueing would amplify into a delete-loop.
    /// SkillStore.applyRemoteDeletion is the local-only counterpart of
    /// deleteSkill that skips the markDirty step.
    private static func applySkillDeletion(id: String) async {
        await MainActor.run {
            SkillStore.shared.applyRemoteDeletion(id: id)
        }
        logger.info("[SyncCore] applied SkillV2 deletion: id=\(id.prefix(8))")
    }

    // MARK: - ProviderConfig

    private static func buildProviderConfig() async -> PortableRecord? {
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let configURL = library.appendingPathComponent("MinisChat/provider-config.json")
        guard let data = try? Data(contentsOf: configURL),
              let json = String(data: data, encoding: .utf8) else { return nil }
        // Reuse v1's export — it already pulls API keys out of the
        // Keychain and base64-encodes them with savedAt timestamps.
        let secrets: String = {
            if #available(iOS 17.0, *) {
                return CloudSyncEngine.exportProviderSecrets(configJson: json)
            }
            return "[]"
        }()
        // [T-apikey fc9a35ec] Stamp the upload hash so V1's merger echo
        // detection recognises our own record bouncing back via fetchRecentV2.
        // Without this, the v2 hydrator's inbound merge sees its own upload
        // as a "remote change" and may clobber later in-memory edits.
        if #available(iOS 17.0, *) {
            CloudSyncEngine.lastUploadedProviderConfigHash =
                SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        }
        let synced = SyncedProviderConfig(
            configJson: json,
            secretsJsonBase64: secrets,
            updatedAt: Date()
        )
        return SyncableTypeRegistry.shared.metadata(for: "ProviderConfigV2")?.buildPortable(synced)
    }

    private static func mergeProviderConfig(record: PortableRecord) async {
        // [T-provider-sync-v3 S7] If v3 is the active sync surface for
        // ProviderConfig, silently drop inbound v2 records. New devices
        // emit BOTH v2 and v3 outbound during rollout so old peers can
        // still receive a current snapshot, but inbound v2 on a v3 device
        // would only let a stale peer's round-tripped snapshot overwrite
        // tombstones — the exact ping-pong loop v3 exists to escape.
        if ProviderV3Bootstrap.isEnabled {
            logger.info("[v3] inbound ProviderConfigV2 dropped — v3 is the authoritative provider sync surface")
            return
        }
        guard let json = stringField(record, "configJson") else { return }
        // [T-apikey fc9a35ec] Delegate to the v1 merger which:
        //   - skips own echo via lastUploadedProviderConfigHash
        //   - bails out if a local dirty ProviderConfig upload is pending
        //     (avoids clobbering in-flight user edits)
        //   - does a per-instance / per-modelEntry / per-modelGroup union
        //     with timestamp-based conflict resolution, instead of blindly
        //     writing the remote JSON to disk
        //
        // The previous implementation atomically replaced provider-config.json
        // with the remote string. When the local instance list was a superset
        // of the remote (e.g. user just added a provider on this device but
        // an older snapshot is being replayed by the CK fetch cursor) the
        // local-only instance vanished from ProviderConfigStore.instances.
        // Its Keychain entry remained intact under the same UUID, which is
        // why toggling the disabled provider's enabled switch off→on
        // (which re-runs updateInstance → save → re-export) appears to
        // "restore" the key.
        //
        // The v1 merger writes the merged result to disk and reloads the
        // store internally, so we don't repeat the file write here.
        if #available(iOS 17.0, *) {
            let outcome = await CloudSyncEngine.mergeProviderConfig(remoteJson: json)
            // Import remote secrets after the config merge so any instance
            // newly added by the merger has its Keychain entry seeded.
            // V1's importProviderSecrets is itself last-write-wins by
            // remote updatedAt vs local Keychain savedAt — it will not
            // overwrite a locally-newer key.
            if let secretsJson = stringField(record, "secretsJson"), !secretsJson.isEmpty {
                CloudSyncEngine.importProviderSecrets(secretsJson: secretsJson)
            }
            // Honest outcome log. The previous "applied via v1 merger" line
            // was printed unconditionally, even when the merger had bailed
            // out at the echo or dirty-state guard — which masked the
            // dirty-zombie ping-pong that prevented two devices' provider
            // lists from converging.
            switch outcome {
            case .applied:
                logger.info("[SyncCore] applied ProviderConfigV2 via v1 merger (\(json.count) chars)")
            case .skippedDecodeFailure:
                logger.warning("[SyncCore] ProviderConfigV2 merge skipped: remote JSON decode failed (\(json.count) chars)")
            case .skippedOwnEcho:
                logger.info("[SyncCore] ProviderConfigV2 merge skipped: own echo (\(json.count) chars)")
            case .skippedLocalDirty:
                logger.info("[SyncCore] ProviderConfigV2 merge skipped: local pending upload (\(json.count) chars)")
            }
        } else {
            logger.info("[SyncCore] ProviderConfigV2 merge skipped: pre-iOS-17 (\(json.count) chars)")
        }
    }

    // MARK: - MCPServers (legacy whole-file, inbound-only)

    /// [T-mcp-per-server-sync] Apply an inbound legacy whole-file snapshot
    /// from a peer still on the pre-per-item build. Decomposed into
    /// per-server applies (union + per-entry LWW) instead of the old
    /// whole-file overwrite, so a stale snapshot cannot clobber servers
    /// added locally or received as MCPServerItem records. Deletes are not
    /// expressible through this legacy path (acceptable: old-build peers
    /// couldn't sync at all — their whitelist never included the type).
    private static func mergeMCPServers(record: PortableRecord) async {
        guard let json = stringField(record, "serversJson"),
              let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rawServers = root["mcpServers"] as? [String: Any] else { return }
        let remoteUpdatedAt = dateField(record, "updatedAt") ?? record.updatedAt
        for (name, rawEntry) in rawServers {
            guard let obj = rawEntry as? [String: Any],
                  let entryData = try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys]),
                  let entryJson = String(data: entryData, encoding: .utf8) else { continue }
            let entryUpdated = (obj["updatedAt"] as? Double).map { Date(timeIntervalSince1970: $0) } ?? remoteUpdatedAt
            let entryCreated = (obj["createdAt"] as? Double).map { Date(timeIntervalSince1970: $0) } ?? remoteUpdatedAt
            await MainActor.run {
                MCPStore.shared.applyRemoteServerItem(
                    name: name, entryJson: entryJson,
                    remoteCreatedAt: entryCreated, remoteUpdatedAt: entryUpdated
                )
            }
        }
        logger.info("[SyncCore] applied legacy MCPServersV2 as \(rawServers.count) per-server merges")
    }

    // MARK: - MCPServerItem (per-server)

    /// Build a per-server record. `id` is the server name (also the CK
    /// recordName). Entry JSON is read straight from disk so the builder
    /// never races the MainActor in-memory list.
    private static func buildMCPServerItem(id: String) async -> PortableRecord? {
        guard let (json, createdAt, updatedAt) = MCPStore.entryJsonForSync(name: id) else {
            // Server gone locally — drop the stale upsert dirty row; the
            // op=delete path is separate (same convention as EnvVarItem).
            return nil
        }
        let synced = SyncedMCPServer(id: id, entryJson: json, createdAt: createdAt, updatedAt: updatedAt)
        return SyncableTypeRegistry.shared.metadata(for: "MCPServerItem")?.buildPortable(synced)
    }

    private static func mergeMCPServerItem(record: PortableRecord) async {
        guard let name = stringField(record, "serverName"), !name.isEmpty else {
            logger.warning("[SyncCore] mergeMCPServerItem: missing serverName field")
            return
        }
        guard let entryJson = stringField(record, "entryJson") else {
            logger.warning("[SyncCore] mergeMCPServerItem: missing entryJson for '\(name)'")
            return
        }
        let createdAt = dateField(record, "createdAt") ?? Date()
        let updatedAt = dateField(record, "updatedAt") ?? record.updatedAt
        // [T-icloud-record-delete-resurrection] Same guard as Skill/EnvVarItem:
        // a just-deleted server re-fetched by fetchRecentV2 before the cloud
        // delete lands must not resurrect.
        if await ChatStore.shared.isRecentlyDeletedRecord(type: "MCPServerItem", id: name, remoteUpdatedAt: updatedAt) {
            logger.info("[SyncCore] mergeMCPServerItem SKIP (recently deleted locally): '\(name)'")
            return
        }
        // [T-icloud-local-edit-clobber] While this server has a pending local
        // upsert, skip the inbound apply — a stale server copy re-fetched
        // between a local edit and its push would otherwise overwrite the
        // fresh edit (per-entry LWW alone can't save it because the stale
        // fetch may carry a marginally newer wall clock from another device).
        let pendingDirty = await ChatStore.shared.loadDirtyRecords()
        if pendingDirty.contains(where: { $0.recordType == "MCPServerItem" && $0.recordId == name && $0.operation != "delete" }) {
            logger.info("[SyncCore] mergeMCPServerItem SKIP (local upsert pending): '\(name)'")
            return
        }
        await MainActor.run {
            MCPStore.shared.applyRemoteServerItem(
                name: name, entryJson: entryJson,
                remoteCreatedAt: createdAt, remoteUpdatedAt: updatedAt
            )
        }
    }

    /// Apply an inbound op=delete tombstone: hard-delete the server by name.
    private static func applyMCPServerItemDeletion(id: String) async {
        await MainActor.run {
            MCPStore.shared.applyRemoteServerDeletion(name: id)
        }
        logger.info("[SyncCore] applied MCPServerItem deletion '\(id)'")
    }

    // MARK: - EnvVars

    private static func buildEnvVars() async -> PortableRecord? {
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let envURL = library.appendingPathComponent("MinisChat/env-vars.json")
        guard let data = try? Data(contentsOf: envURL),
              let json = String(data: data, encoding: .utf8) else { return nil }
        let secrets: String = {
            if #available(iOS 17.0, *) {
                return CloudSyncEngine.exportEnvVarSecrets(envJson: json)
            }
            return "[]"
        }()
        // [T-apikey fc9a35ec] Stamp upload hash so v1's merger recognises
        // our own echo. Same rationale as buildProviderConfig.
        if #available(iOS 17.0, *) {
            CloudSyncEngine.lastUploadedEnvVarHash =
                SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        }
        let synced = SyncedEnvVars(
            envVarsJson: json,
            envSecretsJsonBase64: secrets,
            updatedAt: Date()
        )
        return SyncableTypeRegistry.shared.metadata(for: "EnvVarV2")?.buildPortable(synced)
    }

    private static func mergeEnvVars(record: PortableRecord) async {
        guard let json = stringField(record, "envVarsJson") else { return }
        // [T-apikey fc9a35ec] Delegate to v1 merger (echo detection +
        // per-id union with local-only preservation). Same reasoning as
        // mergeProviderConfig above. The previous blind file write would
        // wipe locally-added env vars whenever a stale snapshot arrived.
        if #available(iOS 17.0, *) {
            await MainActor.run {
                CloudSyncEngine.mergeEnvVars(remoteJson: json)
            }
            if let secretsJson = stringField(record, "envSecretsJson"), !secretsJson.isEmpty {
                CloudSyncEngine.importEnvVarSecrets(secretsJson: secretsJson)
            }
        }
        logger.info("[SyncCore] applied EnvVarV2 via v1 merger (\(json.count) chars)")
    }

    // MARK: - EnvVarItem (per-variable)

    /// Build a per-variable record. The `id` is the EnvVarEntry UUID,
    /// also used as the CK recordName. Values are pulled from Keychain
    /// at build time and base64-encoded into the record so a peer that
    /// hasn't yet received the matching iCloud Keychain sync entry still
    /// gets the value.
    private static func buildEnvVarItem(id: String) async -> PortableRecord? {
        let entry = await MainActor.run { EnvVarStore.shared.entry(id: id) }
        guard let entry else {
            // Local entry gone — caller (SyncCore) treats nil as "drop
            // dirty row, no record to push". The op=delete path is
            // separate (deletionApplier on the receiver side, op=delete
            // on the dirty row on the sender side) so this nil only
            // fires when an upsert was queued for an entry that's since
            // been removed.
            return nil
        }
        let value = EnvVarStore.loadValueSync(forKey: entry.key) ?? ""
        let valueB64 = Data(value.utf8).base64EncodedString()
        let synced = SyncedEnvVar(
            id: entry.id,
            key: entry.key,
            valueB64: valueB64,
            note: entry.note,
            createdAt: entry.createdAt,
            updatedAt: Date()
        )
        return SyncableTypeRegistry.shared.metadata(for: "EnvVarItem")?.buildPortable(synced)
    }

    /// Apply an inbound per-variable record. Adds-or-replaces the entry
    /// in env-vars.json by id, decodes valueB64 → Keychain. LWW
    /// resolution by updatedAt happens in EnvVarStore.applyRemoteItem.
    private static func mergeEnvVarItem(record: PortableRecord) async {
        guard let id = stringField(record, "entryId") else {
            logger.warning("[SyncCore] mergeEnvVarItem: missing entryId field")
            return
        }
        guard let key = stringField(record, "key") else {
            logger.warning("[SyncCore] mergeEnvVarItem: missing key for id=\(id.prefix(8))")
            return
        }
        let valueB64 = stringField(record, "valueB64") ?? ""
        let note = stringField(record, "note") ?? ""
        let createdAt = dateField(record, "createdAt") ?? Date()
        let updatedAt = dateField(record, "updatedAt") ?? record.updatedAt
        // [T-icloud-record-delete-resurrection] Same guard as mergeSkill:
        // applyRemoteItem unconditionally re-inserts an unknown id, so a
        // just-deleted variable re-fetched by fetchRecentV2 before the cloud
        // delete lands would resurrect without this check.
        if await ChatStore.shared.isRecentlyDeletedRecord(type: "EnvVarItem", id: id, remoteUpdatedAt: updatedAt) {
            logger.info("[SyncCore] mergeEnvVarItem SKIP (recently deleted locally): id=\(id.prefix(8))")
            return
        }
        // [T-icloud-local-edit-clobber] In-flight local edit guard.
        // EnvVarEntry has no local per-entry updatedAt, so applyRemoteItem
        // can't LWW — it applies whatever arrives. A stale server copy
        // re-fetched between a local edit and its push would overwrite the
        // fresh value, and the pending dirty row would then push the stale
        // value back up (the edit is fully lost). Mirror the ProviderConfig
        // merger's rule: while this id has a pending local upsert, skip the
        // inbound apply; the post-push fetch reconciles normally.
        let pendingDirty = await ChatStore.shared.loadDirtyRecords()
        if pendingDirty.contains(where: { $0.recordType == "EnvVarItem" && $0.recordId == id && $0.operation != "delete" }) {
            logger.info("[SyncCore] mergeEnvVarItem SKIP (local upsert pending): id=\(id.prefix(8))")
            return
        }
        let value: String = {
            guard !valueB64.isEmpty,
                  let data = Data(base64Encoded: valueB64),
                  let s = String(data: data, encoding: .utf8) else { return "" }
            return s
        }()
        await MainActor.run {
            EnvVarStore.shared.applyRemoteItem(
                id: id, key: key, value: value, note: note,
                createdAt: createdAt, updatedAt: updatedAt
            )
        }
        logger.info("[SyncCore] applied EnvVarItem id=\(id.prefix(8)) key=\(key)")
    }

    /// Apply an inbound op=delete tombstone. Removes the entry by id
    /// (NOT by key) — receiver doesn't need to know the key, the CK
    /// record's recordName carries the entry UUID.
    private static func applyEnvVarItemDeletion(id: String) async {
        await MainActor.run {
            EnvVarStore.shared.applyRemoteDeletion(id: id)
        }
        logger.info("[SyncCore] applied EnvVarItem deletion id=\(id.prefix(8))")
    }

    // MARK: - SyncDevice

    private static func mergeDevice(record: PortableRecord) async {
        guard let id = stringField(record, "deviceId") else { return }
        let device = SyncDevice(
            id: id,
            deviceName: stringField(record, "deviceName") ?? "Unknown",
            zoneName: "minis-devices",
            lastSeen: dateField(record, "lastSeen") ?? Date(),
            osVersion: stringField(record, "osVersion") ?? "",
            uploadTypes: (stringField(record, "uploadTypes") ?? "")
                .components(separatedBy: ",").filter { !$0.isEmpty }
        )
        await ChatStore.shared.upsertSyncDevice(device)
    }

    // MARK: - Helper

    private struct SkillSnapshot {
        let id: String
        let name: String
        let description: String
        let version: String
        let importSource: String
        let isEnabled: Bool
        let installedAt: Date
        let updatedAt: Date
        let bodyText: String
        let zipData: Data?
    }

    /// Minimal MIME helper for SessionFile builds. Keep tiny — full helper
    /// lives on CloudSyncEngine for v1; v2 only needs the common cases.
    private enum SessionFileMimeMap {
        static func mimeType(for ext: String) -> String? {
            switch ext.lowercased() {
            case "txt", "md":  return "text/plain"
            case "json":       return "application/json"
            case "png":        return "image/png"
            case "jpg", "jpeg": return "image/jpeg"
            case "pdf":        return "application/pdf"
            default:           return nil
            }
        }
    }

    // MARK: - Soul (SOUL.md singleton)

    /// Build the outbound SoulV2 record from the on-disk SOUL.md file.
    /// Returns nil when the file is missing (e.g. first launch before
    /// ensureExists has run) so we don't push an empty record and clobber
    /// peer copies.
    private static func buildSoul() async -> PortableRecord? {
        let url = await MainActor.run { SoulStore.fileURL }
        let fm = FileManager.default
        guard fm.fileExists(atPath: url.path),
              let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .utf8) else {
            logger.info("[SyncCore] buildSoul: SOUL.md missing on disk, skipping push")
            return nil
        }
        // Use the file's mtime as updatedAt so a peer's older snapshot
        // can't clobber a locally newer edit just because clocks drift.
        let mtime: Date = {
            if let attrs = try? fm.attributesOfItem(atPath: url.path),
               let d = attrs[.modificationDate] as? Date { return d }
            return Date()
        }()
        let synced = SyncedSoul(contentMarkdown: text, updatedAt: mtime)
        return SyncableTypeRegistry.shared.metadata(for: "SoulV2")?.buildPortable(synced)
    }

    /// Apply an inbound SoulV2 record. LWW-by-updatedAt against the local
    /// file mtime is implemented inside SoulStore.applyRemoteContent.
    private static func mergeSoul(record: PortableRecord) async {
        guard let text = stringField(record, "contentMarkdown") else {
            logger.warning("[SyncCore] mergeSoul: missing contentMarkdown field")
            return
        }
        let updatedAt = dateField(record, "updatedAt") ?? record.updatedAt
        await MainActor.run {
            SoulStore.applyRemoteContent(text, remoteUpdatedAt: updatedAt)
        }
        logger.info("[SyncCore] applied SoulV2 (\(text.count) chars, updatedAt=\(updatedAt))")
    }

    // MARK: - Memory Global (GLOBAL.md singleton)

    private static func buildMemoryGlobal() async -> PortableRecord? {
        let url = AIChatViewModel.minisMemoryPersistentDir
                    .appendingPathComponent("GLOBAL.md")
        let fm = FileManager.default
        guard fm.fileExists(atPath: url.path),
              let text = try? String(contentsOf: url, encoding: .utf8),
              !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else {
            // Empty or missing — don't push, avoid clobbering remote content
            return nil
        }
        let mtime: Date = {
            if let attrs = try? fm.attributesOfItem(atPath: url.path),
               let d = attrs[.modificationDate] as? Date { return d }
            return Date()
        }()
        let synced = SyncedMemoryGlobal(contentMarkdown: text, updatedAt: mtime)
        return SyncableTypeRegistry.shared.metadata(for: "MemoryGlobalV2")?.buildPortable(synced)
    }

    private static func mergeMemoryGlobal(record: PortableRecord) async {
        guard let text = stringField(record, "contentMarkdown") else {
            logger.warning("[SyncCore] mergeMemoryGlobal: missing contentMarkdown")
            return
        }
        let remoteUpdatedAt = dateField(record, "updatedAt") ?? record.updatedAt

        let url = AIChatViewModel.minisMemoryPersistentDir
                    .appendingPathComponent("GLOBAL.md")
        let fm = FileManager.default

        // [T-icloud-local-edit-clobber] Content-equality short circuit. An
        // echo of what we already have (including our own record bouncing
        // back) must not rewrite the file: the rewrite bumps the mtime, which
        // is both the LWW clock and the dirty-scanner trigger, so it would
        // re-push identical content with an ever-newer timestamp — a
        // cross-device echo loop whose ratcheting clock can beat (and thus
        // discard) an offline peer's genuinely newer edit.
        if let localText = try? String(contentsOf: url, encoding: .utf8),
           localText == text {
            return
        }

        // LWW: skip if local file is newer or same age
        if fm.fileExists(atPath: url.path),
           let attrs = try? fm.attributesOfItem(atPath: url.path),
           let localMtime = attrs[.modificationDate] as? Date,
           localMtime >= remoteUpdatedAt {
            logger.info("[SyncCore] mergeMemoryGlobal: local is newer, skipping")
            return
        }

        try? fm.createDirectory(at: url.deletingLastPathComponent(),
                                 withIntermediateDirectories: true)
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            // [T-icloud-local-edit-clobber] Backdate the applied file's mtime
            // to the record's updatedAt so the apply doesn't masquerade as a
            // fresh local edit (same fix as MCPServers / SOUL.md).
            try? fm.setAttributes([.modificationDate: remoteUpdatedAt], ofItemAtPath: url.path)
            logger.info("[SyncCore] applied MemoryGlobalV2 (\(text.count) chars, remoteUpdatedAt=\(remoteUpdatedAt))")
            NotificationCenter.default.post(name: .memoryFilesDidChange, object: nil)
        } catch {
            logger.error("[SyncCore] mergeMemoryGlobal write failed: \(error)")
        }
    }

    // MARK: - Memory Daily (per-day log, Set Union merge)

    /// Lightweight entry parsed from a daily log block delimited by <!-- timestamp -->.
    private struct MemoryEntry: Codable, Equatable {
        let timestamp: String   // "YYYY-MM-DD HH:mm:ss" — dedup key
        let content: String     // block body (everything after the <!-- --> line)
    }

    /// Parse a daily log file into its constituent MemoryEntry blocks.
    /// Splits on "<!-- " prefix lines (used as block boundaries by executeMemoryWrite).
    private static func parseMemoryEntries(from text: String) -> [MemoryEntry] {
        let lines = text.components(separatedBy: "\n")
        var entries: [MemoryEntry] = []
        var currentTimestamp: String? = nil
        var currentLines: [String] = []

        for line in lines {
            if line.hasPrefix("<!-- "), line.hasSuffix(" -->") {
                // Flush previous block
                if let ts = currentTimestamp {
                    let body = currentLines.joined(separator: "\n")
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    if !body.isEmpty {
                        entries.append(MemoryEntry(timestamp: ts, content: body))
                    }
                }
                // Start new block — extract timestamp between "<!-- " and " -->"
                let inner = String(line.dropFirst(5).dropLast(4))
                currentTimestamp = inner
                currentLines = []
            } else {
                currentLines.append(line)
            }
        }
        // Flush last block
        if let ts = currentTimestamp {
            let body = currentLines.joined(separator: "\n")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !body.isEmpty {
                entries.append(MemoryEntry(timestamp: ts, content: body))
            }
        }
        return entries
    }

    /// Serialise a list of MemoryEntry back into daily log file format (newest first).
    private static func serialiseMemoryEntries(_ entries: [MemoryEntry]) -> String {
        let sorted = entries.sorted { $0.timestamp > $1.timestamp }
        return sorted.map { "<!-- \($0.timestamp) -->\n\($0.content)\n" }
                     .joined(separator: "\n")
    }

    private static func buildMemoryDaily(dateKey: String) async -> PortableRecord? {
        // Only sync last 30 days
        let cutoff = Calendar.current.date(byAdding: .day, value: -30, to: Date()) ?? Date()
        let fmt = DateFormatter(); fmt.dateFormat = "yyyy-MM-dd"
        if let fileDate = fmt.date(from: dateKey), fileDate < cutoff {
            return nil  // Too old — skip
        }

        let url = AIChatViewModel.minisMemoryPersistentDir
                    .appendingPathComponent("\(dateKey).md")
        guard let text = try? String(contentsOf: url, encoding: .utf8),
              !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return nil }

        let entries = parseMemoryEntries(from: text)
        guard !entries.isEmpty else { return nil }

        guard let jsonData = try? JSONEncoder().encode(entries),
              let jsonStr = String(data: jsonData, encoding: .utf8) else { return nil }

        let mtime: Date = {
            if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
               let d = attrs[.modificationDate] as? Date { return d }
            return Date()
        }()

        let synced = SyncedMemoryDaily(id: dateKey, entriesJson: jsonStr, updatedAt: mtime)
        return SyncableTypeRegistry.shared.metadata(for: "MemoryDailyV2")?.buildPortable(synced)
    }

    private static func mergeMemoryDaily(record: PortableRecord) async {
        guard let dateKey = stringField(record, "dateKey"),
              let entriesJson = stringField(record, "entriesJson"),
              let remoteEntries = try? JSONDecoder().decode(
                  [MemoryEntry].self, from: Data(entriesJson.utf8))
        else {
            logger.warning("[SyncCore] mergeMemoryDaily: missing or unparseable fields")
            return
        }

        let url = AIChatViewModel.minisMemoryPersistentDir
                    .appendingPathComponent("\(dateKey).md")
        let fm = FileManager.default

        // Read existing local entries
        var localEntries: [MemoryEntry] = []
        if fm.fileExists(atPath: url.path),
           let localText = try? String(contentsOf: url, encoding: .utf8) {
            localEntries = parseMemoryEntries(from: localText)
        }

        // Set Union by timestamp key.
        // - Same timestamp + same content → idempotent (skip).
        // - Same timestamp + different content → keep BOTH as independent
        //   entries, with the remote one stored under a content-hashed
        //   suffix key so it doesn't collide with the local one. The
        //   serialised file will show both as separate `<!-- ts -->` blocks
        //   sharing the same timestamp (effectively "append the conflicting
        //   block to the end" once sorted). Hashing the content makes the
        //   suffix deterministic — re-merging the same pair never produces
        //   a third copy, so the file can't grow on each sync round.
        var merged: [String: MemoryEntry] = [:]
        for e in localEntries { merged[e.timestamp] = e }
        for e in remoteEntries {
            if let existing = merged[e.timestamp] {
                if existing.content != e.content {
                    // Conflict: deterministic suffix from content hash.
                    let hashSuffix = String(abs(e.content.hashValue) % 100_000_000)
                    let conflictKey = "\(e.timestamp)#\(hashSuffix)"
                    if merged[conflictKey] == nil {
                        merged[conflictKey] = e
                        logger.warning("[SyncCore] mergeMemoryDaily: same-timestamp conflict at \(e.timestamp), kept both as separate entries")
                    }
                    // else: this exact remote variant already merged in
                    // — idempotent, no-op (prevents file growth on re-sync)
                }
                // else: identical content, idempotent — no-op
            } else {
                merged[e.timestamp] = e   // new entry from remote
            }
        }

        let newCount = merged.count - localEntries.count
        // [T-icloud-local-edit-clobber] Union added nothing → don't rewrite.
        // The rewrite would bump the file mtime, the dirty scanner would
        // re-push the (unchanged) day, and every peer's apply would do the
        // same — a perpetual cross-device echo of identical content. The
        // union can only grow, so equal counts means no new entries.
        if newCount == 0, fm.fileExists(atPath: url.path) {
            return
        }
        let allEntries = Array(merged.values)
        let newText = serialiseMemoryEntries(allEntries)

        try? fm.createDirectory(at: url.deletingLastPathComponent(),
                                 withIntermediateDirectories: true)
        do {
            try newText.write(to: url, atomically: true, encoding: .utf8)
            logger.info("[SyncCore] applied MemoryDailyV2 dateKey=\(dateKey) totalEntries=\(allEntries.count) newFromRemote=\(newCount)")
            NotificationCenter.default.post(name: .memoryFilesDidChange, object: nil)
        } catch {
            logger.error("[SyncCore] mergeMemoryDaily write failed: \(error)")
        }
    }

    // MARK: - Field accessors

    private static func stringField(_ r: PortableRecord, _ key: String) -> String? {
        if case .string(let s) = r.fields[key] ?? .null { return s }
        return nil
    }
    private static func optionalStringField(_ r: PortableRecord, _ key: String) -> String? {
        switch r.fields[key] {
        case .string(let s): return s
        default: return nil
        }
    }
    private static func intField(_ r: PortableRecord, _ key: String) -> Int? {
        if case .int(let i) = r.fields[key] ?? .null { return i }
        return nil
    }
    private static func dateField(_ r: PortableRecord, _ key: String) -> Date? {
        if case .date(let d) = r.fields[key] ?? .null { return d }
        return nil
    }

    // MARK: - V3 Provider sync (per-record)

    /// Build a SyncedProviderInstanceV3 portable record from the DB row.
    /// Returns nil if no row exists (caller treats nil as "drop dirty,
    /// nothing to push").
    private static func buildProviderInstanceV3(id: String) async -> PortableRecord? {
        guard let db = ProviderConfigStore.shared.db else { return nil }
        guard let row = await db.instanceRow(id: id) else { return nil }
        let synced = SyncedProviderInstanceV3(
            id: id,
            label: (row["label"] as? String) ?? "",
            providerType: (row["provider_type"] as? String) ?? "",
            credentialType: row["credential_type"] as? String,
            customBaseURL: row["custom_base_url"] as? String,
            appendV1Suffix: ((row["append_v1_suffix"] as? Bool) ?? true) ? 1 : 0,
            imageEndpointMode: row["image_endpoint_mode"] as? String,
            imageEndpointResolved: row["image_endpoint_resolved"] as? String,
            isEnabled: ((row["is_enabled"] as? Bool) ?? true) ? 1 : 0,
            sortOrder: (row["sort_order"] as? Int) ?? 0,
            secretBlob: row["secret_blob"] as? String,
            secretKind: row["secret_kind"] as? String,
            secretUpdatedAt: (row["secret_updated_at"] as? Double).map { Date(timeIntervalSince1970: $0) },
            createdAt: Date(timeIntervalSince1970: (row["created_at"] as? Double) ?? 0),
            updatedAt: Date(timeIntervalSince1970: (row["updated_at"] as? Double) ?? 0),
            extrasJson: row["extras_json"] as? String,
            customUserAgent: row["custom_user_agent"] as? String,
            azureMode: ((row["azure_mode"] as? Bool) ?? false) ? 1 : 0
        )
        return SyncableTypeRegistry.shared
            .metadata(for: "ProviderInstanceV3")?
            .buildPortable(synced)
    }

    private static func mergeProviderInstanceV3(record: PortableRecord) async {
        guard let db = ProviderConfigStore.shared.db else {
            // [T-icloud-fresh-restore-provider-groups] Inbound dropped because the
            // provider SQLite store hasn't finished its async open yet (fresh
            // device race). fetchRecentV2 will NOT anchor this type until the DB
            // is open, so the record is re-pulled next run — log it so the
            // recovery is visible in the field.
            logger.warning("[v3] mergeProviderInstanceV3 DROPPED (DB not open yet) id=\(record.id.id.prefix(8)) — will re-pull")
            return
        }
        let id = record.id.id
        let label = stringField(record, "label") ?? ""
        let providerType = stringField(record, "providerType") ?? ""
        let credentialType = optionalStringField(record, "credentialType")
        let customBaseURL = optionalStringField(record, "customBaseURL")
        let appendV1Suffix = (intField(record, "appendV1Suffix") ?? 1) != 0
        let imageEndpointMode = optionalStringField(record, "imageEndpointMode")
        let imageEndpointResolved = optionalStringField(record, "imageEndpointResolved")
        let isEnabled = (intField(record, "isEnabled") ?? 1) != 0
        let sortOrder = intField(record, "sortOrder") ?? 0
        let secretBlob = optionalStringField(record, "secretBlob")
        let secretKind = optionalStringField(record, "secretKind")
        let secretUpdatedAt = dateField(record, "secretUpdatedAt")?.timeIntervalSince1970
        let createdAt = (dateField(record, "createdAt") ?? Date()).timeIntervalSince1970
        let updatedAt = (dateField(record, "updatedAt") ?? Date()).timeIntervalSince1970
        let extrasJson = optionalStringField(record, "extrasJson")
        let customUserAgent = optionalStringField(record, "customUserAgent")
        // [T-ios-azure-openai] Absent on records from older builds → 0 (off), so
        // a peer that predates Azure simply sees a normal OpenAI/Responses instance.
        let azureMode = (intField(record, "azureMode") ?? 0) != 0
        // [T-icloud-record-delete-resurrection] upsertInstanceFromInbound
        // inserts when the row is absent (its LWW only guards EXISTING rows),
        // so a provider deleted locally moments ago — its row already gone —
        // would be resurrected by the same record fetchRecentV2 re-pulls
        // before the op=delete reaches cloud. Refuse it unless genuinely newer.
        if await ChatStore.shared.isRecentlyDeletedRecord(
            type: "ProviderInstanceV3", id: id,
            remoteUpdatedAt: Date(timeIntervalSince1970: updatedAt)) {
            logger.info("[v3] mergeProviderInstanceV3 SKIP (recently deleted locally): id=\(id.prefix(8))")
            return
        }
        let applied = await db.upsertInstanceFromInbound(
            id: id, label: label, providerType: providerType,
            credentialType: credentialType, customBaseURL: customBaseURL,
            appendV1Suffix: appendV1Suffix,
            imageEndpointMode: imageEndpointMode, imageEndpointResolved: imageEndpointResolved,
            isEnabled: isEnabled, sortOrder: sortOrder,
            secretBlob: secretBlob, secretKind: secretKind, secretUpdatedAt: secretUpdatedAt,
            createdAt: createdAt, updatedAt: updatedAt, extrasJson: extrasJson,
            customUserAgent: customUserAgent,
            azureMode: azureMode
        )
        if applied {
            logger.info("[v3] applied ProviderInstanceV3 \(id.prefix(8)) label=\(label.prefix(20))")
            await refreshStoreFromDB()
        } else {
            logger.info("[v3] skipped ProviderInstanceV3 \(id.prefix(8)) — local row is newer (LWW)")
        }
    }

    private static func deleteProviderInstanceV3(id: String) async {
        guard let db = ProviderConfigStore.shared.db else {
            logger.warning("[v3] deleteProviderInstanceV3 DROPPED (DB not open yet) id=\(id.prefix(8))")
            return
        }
        await db.deleteInstanceRow(id: id)
        // CASCADE FK on provider_model_entries.provider_instance_id
        // already removes the instance's entries server-side too — but
        // peers issue their own per-entry op=delete records, so the
        // cascade here is defense-in-depth for peers that batch
        // out-of-order.
        logger.info("[v3] deleted ProviderInstanceV3 \(id.prefix(8))")
        await refreshStoreFromDB()
    }

    private static func buildProviderModelEntryV3(id: String) async -> PortableRecord? {
        guard let db = ProviderConfigStore.shared.db else { return nil }
        guard let row = await db.entryRow(id: id) else { return nil }
        let synced = SyncedProviderModelEntryV3(
            id: id,
            providerInstanceId: (row["provider_instance_id"] as? String) ?? "",
            baseModelJson: (row["base_model_json"] as? String) ?? "{}",
            overridesJson: row["overrides_json"] as? String,
            isCustom: ((row["is_custom"] as? Bool) ?? false) ? 1 : 0,
            isHidden: ((row["is_hidden"] as? Bool) ?? false) ? 1 : 0,
            userModifiedAt: (row["user_modified_at"] as? Double).map { Date(timeIntervalSince1970: $0) },
            sortOrder: (row["sort_order"] as? Int) ?? 0,
            updatedAt: Date(timeIntervalSince1970: (row["updated_at"] as? Double) ?? 0),
            extrasJson: row["extras_json"] as? String,
            // [T-provider-entry-composite-key] carry the pre-migration uuid so a
            // receiver can normalize old uuid references to this composite key.
            legacyUuid: row["uuid"] as? String
        )
        return SyncableTypeRegistry.shared
            .metadata(for: "ProviderModelEntryV3")?
            .buildPortable(synced)
    }

    private static func mergeProviderModelEntryV3(record: PortableRecord) async {
        guard let db = ProviderConfigStore.shared.db else {
            logger.warning("[v3] mergeProviderModelEntryV3 DROPPED (DB not open yet) id=\(record.id.id.prefix(8)) — will re-pull")
            return
        }
        let rawId = record.id.id
        let entryUpdatedAt = dateField(record, "updatedAt") ?? Date()
        // [T-provider-entry-id-canonicalize] Key the local upsert on the
        // CANONICAL composite key (instanceId/modelId), NOT the record's raw id.
        // The local DB migrated entry ids to compositeKey (ModelEntry.id =
        // compositeKey), but the cloud still holds pre-migration records keyed by
        // random per-device UUIDs. Upserting under the raw UUID meant every such
        // inbound record missed the local row (rowExisted=false) and got inserted
        // as a NEW entry — the same model piling up under different uuids on each
        // cold start (observed: iPad re-applying 119 "new" entries, cloud growing
        // 732→772+). Resolve the composite key from the record's own
        // instanceId + baseModel.id and upsert under THAT, so a raw-uuid record
        // and the local composite-keyed row collapse onto one identity and go
        // through LWW merge instead of duplicate insertion. The uuid is ignored
        // for identity (it remains only the cloud record's container id +
        // legacyUuid mapping below).
        let canonInstanceId = stringField(record, "providerInstanceId") ?? ""
        let canonBaseJson = stringField(record, "baseModelJson") ?? "{}"
        let canonModelId = (try? JSONSerialization.jsonObject(with: Data(canonBaseJson.utf8)) as? [String: Any])?["id"] as? String
        let id: String = {
            // Already composite-keyed (peer migrated, or id contains "/") — keep.
            if rawId.contains("/") { return rawId }
            // Raw uuid + resolvable model → use the canonical composite key.
            if let m = canonModelId, !canonInstanceId.isEmpty, !m.isEmpty {
                return "\(canonInstanceId)/\(m)"
            }
            // Can't resolve (missing fields) — fall back to raw id (no worse than before).
            return rawId
        }()
        // [T-icloud-record-delete-resurrection] Same resurrection guard as the
        // instance merger: upsertEntryFromInbound re-inserts an absent row, so
        // a just-deleted model entry re-pulled by fetchRecentV2 before its
        // op=delete lands would come back. Refuse unless genuinely newer.
        // Check BOTH the canonical id and the raw uuid so a delete tombstone
        // recorded under either form still suppresses resurrection. (Evaluate
        // the two awaits as plain statements — `||` would put the second await
        // inside a non-concurrent autoclosure.)
        var recentlyDeleted = await ChatStore.shared.isRecentlyDeletedRecord(
            type: "ProviderModelEntryV3", id: id, remoteUpdatedAt: entryUpdatedAt)
        if !recentlyDeleted && id != rawId {
            recentlyDeleted = await ChatStore.shared.isRecentlyDeletedRecord(
                type: "ProviderModelEntryV3", id: rawId, remoteUpdatedAt: entryUpdatedAt)
        }
        if recentlyDeleted {
            logger.info("[v3] mergeProviderModelEntryV3 SKIP (recently deleted locally): id=\(id.prefix(16))")
            return
        }
        let applied = await db.upsertEntryFromInbound(
            id: id,
            providerInstanceId: stringField(record, "providerInstanceId") ?? "",
            baseModelJson: stringField(record, "baseModelJson") ?? "{}",
            overridesJson: optionalStringField(record, "overridesJson"),
            isCustom: (intField(record, "isCustom") ?? 0) != 0,
            isHidden: (intField(record, "isHidden") ?? 0) != 0,
            userModifiedAt: dateField(record, "userModifiedAt")?.timeIntervalSince1970,
            sortOrder: intField(record, "sortOrder") ?? 0,
            updatedAt: (dateField(record, "updatedAt") ?? Date()).timeIntervalSince1970,
            extrasJson: optionalStringField(record, "extrasJson")
        )
        if applied {
            logger.info("[v3] applied ProviderModelEntryV3 \(id.prefix(8))")
            await refreshStoreFromDB()
        }
        // [T-provider-entry-composite-key] Learn legacyUuid → compositeKey so any
        // group/binding/agent-loop reference still pointing at an old random uuid
        // gets normalized to the composite key (deferred, order-independent).
        // Compute compositeKey from the record's instanceId + modelId; the
        // mapping sources are (a) this record's legacyUuid field (peer migrated),
        // and (b) the record's own id when it's still a raw uuid (peer not yet
        // migrated — id won't contain "/").
        let instanceId = stringField(record, "providerInstanceId") ?? ""
        let baseJson = stringField(record, "baseModelJson") ?? "{}"
        let modelId = (try? JSONSerialization.jsonObject(with: Data(baseJson.utf8)) as? [String: Any])?["id"] as? String
        if let modelId, !instanceId.isEmpty {
            let compositeKey = "\(instanceId)/\(modelId)"
            var pairs: [String: String] = [:]
            if let legacy = optionalStringField(record, "legacyUuid") { pairs[legacy] = compositeKey }
            if !id.contains("/") { pairs[id] = compositeKey }  // peer still uuid-keyed
            if !pairs.isEmpty {
                await MainActor.run { ProviderConfigStore.shared.learnLegacyUuids(pairs) }
            }
        }
    }

    private static func deleteProviderModelEntryV3(id: String) async {
        guard let db = ProviderConfigStore.shared.db else {
            logger.warning("[v3] deleteProviderModelEntryV3 DROPPED (DB not open yet) id=\(id.prefix(8))")
            return
        }
        // [T-provider-entry-id-canonicalize] The local DB keys entries by the
        // composite key, but the cloud can issue a delete for a pre-migration
        // RANDOM-UUID record id. Deleting only that raw uuid would no-op (the
        // local row is composite-keyed) and the entry would survive a remote
        // delete. Resolve the uuid → composite key via the persisted legacy map
        // and delete the canonical row too. Delete both forms so an
        // un-normalized raw-uuid row (if any) is also cleared.
        let canonical = await MainActor.run { ProviderConfigStore.shared.canonicalEntryId(forLegacy: id) }
        await db.deleteEntryRow(id: id)
        if let canonical, canonical != id {
            await db.deleteEntryRow(id: canonical)
            logger.info("[v3] deleted ProviderModelEntryV3 \(id.prefix(8)) (+canonical \(canonical))")
        } else {
            logger.info("[v3] deleted ProviderModelEntryV3 \(id.prefix(16))")
        }
        await refreshStoreFromDB()
    }

    private static func buildProviderModelGroupV3(id: String) async -> PortableRecord? {
        guard let db = ProviderConfigStore.shared.db else { return nil }
        guard let row = await db.groupRow(id: id) else { return nil }
        let synced = SyncedProviderModelGroupV3(
            id: id,
            name: (row["name"] as? String) ?? "",
            strategy: (row["strategy"] as? String) ?? "fallback",
            fallbackStrategy: (row["fallback_strategy"] as? String) ?? "limited",
            defaultThinkingLevel: row["default_thinking_level"] as? String,
            contextLimitTokens: row["context_limit_tokens"] as? Int,
            contextLimitRemembered: row["context_limit_remembered"] as? Int,
            memberEntryIdsJson: (row["member_entry_ids_json"] as? String) ?? "[]",
            sortOrder: (row["sort_order"] as? Int) ?? 0,
            updatedAt: Date(timeIntervalSince1970: (row["updated_at"] as? Double) ?? 0),
            extrasJson: row["extras_json"] as? String,
            // [T-icloud-provider-sync-consistency] Combine added + removed
            // member timestamps into ONE field for the wire: a JSON object
            // {"added":{id:ts}, "removed":{id:ts}}. Keeping it in a single
            // optionalString record field avoids touching the Syncable field
            // schema twice; the receiver splits it back apart.
            removedMembersJson: Self.encodeGroupMemberTombstones(
                added: (row["added_members_json"] as? String) ?? "{}",
                removed: (row["removed_members_json"] as? String) ?? "{}"
            )
        )
        return SyncableTypeRegistry.shared
            .metadata(for: "ProviderModelGroupV3")?
            .buildPortable(synced)
    }

    /// Pack the group's added/removed member-timestamp maps into one JSON
    /// envelope for the single `removedMembersJson` record field.
    private static func encodeGroupMemberTombstones(added: String, removed: String) -> String {
        // Both inputs are already JSON objects; wrap them without re-parsing.
        let a = added.isEmpty ? "{}" : added
        let r = removed.isEmpty ? "{}" : removed
        if a == "{}" && r == "{}" { return "{}" }
        return "{\"added\":\(a),\"removed\":\(r)}"
    }

    /// Split the wire envelope back into (added, removed) timestamp maps.
    /// [T-icloud-modelgroup-orphan-members] Report group members that resolve to
    /// no local model entry.
    ///
    /// Member ids are composite (`<providerInstanceUUID>/<modelId>`), so an id
    /// minted on another device points at THAT device's instance UUID. With no
    /// cross-device instance remapping on inbound, those members are permanently
    /// unresolvable here. The UI drops them silently (`compactMap`), which is
    /// what made OpenMinis#98 hard to characterise — the group just looked
    /// short. This logs what was dropped and the distinct peer instance ids
    /// involved, which is the signal that identifies a remap gap vs. a genuine
    /// user deletion.
    private static func logOrphanGroupMembers(groupId: String, source: String) async {
        guard let db = ProviderConfigStore.shared.db,
              let json = await db.groupRow(id: groupId)?["member_entry_ids_json"] as? String,
              let members = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String],
              !members.isEmpty else { return }

        let known = Set(await MainActor.run { ProviderConfigStore.shared.config.modelEntries.map(\.id) })
        let orphans = members.filter { !known.contains($0) }
        guard !orphans.isEmpty else { return }

        // The instance UUID prefix identifies WHICH device minted the id — the
        // most useful field for telling "peer instance never synced/remapped"
        // apart from "entry deleted locally".
        let peerInstances = Set(orphans.compactMap { $0.split(separator: "/").first.map(String.init) })
        logger.warning("""
            [OrphanMembers] group=\(groupId.prefix(8)) source=\(source) \
            orphans=\(orphans.count)/\(members.count) \
            peerInstances=\(peerInstances.map { String($0.prefix(8)) }.sorted()) \
            sample=\(orphans.prefix(3).map { String($0.prefix(50)) })
            """)
    }

    private static func decodeGroupMemberTombstones(_ json: String?) -> (added: [String: Date], removed: [String: Date]) {
        guard let json, !json.isEmpty, json != "{}",
              let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return ([:], [:]) }
        func mapOf(_ key: String) -> [String: Date] {
            guard let sub = obj[key] as? [String: Double] else { return [:] }
            var out: [String: Date] = [:]
            for (k, v) in sub { out[k] = Date(timeIntervalSince1970: v) }
            return out
        }
        return (mapOf("added"), mapOf("removed"))
    }

    private static func mergeProviderModelGroupV3(record: PortableRecord) async {
        guard let db = ProviderConfigStore.shared.db else {
            logger.warning("[v3] mergeProviderModelGroupV3 DROPPED (DB not open yet) id=\(record.id.id.prefix(8)) — will re-pull")
            return
        }
        let id = record.id.id
        // [T-icloud-record-delete-resurrection] Resurrection guard, as with the
        // instance/entry mergers: a just-deleted group re-pulled by fetchRecentV2
        // before its op=delete lands must not be re-inserted. Member-removal
        // tombstones inside a LIVE group are a separate mechanism; this guards
        // the whole-group delete.
        if await ChatStore.shared.isRecentlyDeletedRecord(
            type: "ProviderModelGroupV3", id: id,
            remoteUpdatedAt: dateField(record, "updatedAt") ?? Date()) {
            logger.info("[v3] mergeProviderModelGroupV3 SKIP (recently deleted locally): id=\(id.prefix(8))")
            return
        }
        // [T-icloud-modelgroup-member-loss] Diagnostic: log the member count
        // we currently hold vs. the inbound record's, so a member-count drop
        // caused by an LWW overwrite (older peer snapshot winning, or a
        // truncated push from a buggy device) is visible in the log without a
        // repro. memberEntryIdsJson is the authoritative whole-list field —
        // upsertGroupFromInbound applies it as a unit under an updatedAt guard.
        let inboundMembersJson = stringField(record, "memberEntryIdsJson") ?? "[]"
        let inboundCount = ((try? JSONSerialization.jsonObject(with: Data(inboundMembersJson.utf8))) as? [Any])?.count ?? -1
        let localCount = (await db.groupRow(id: id)?["member_entry_ids_json"] as? String)
            .flatMap { ((try? JSONSerialization.jsonObject(with: Data($0.utf8))) as? [Any])?.count } ?? -1
        let (inboundAdded, inboundRemoved) = decodeGroupMemberTombstones(optionalStringField(record, "removedMembersJson"))
        let applied = await db.upsertGroupFromInbound(
            id: id,
            name: stringField(record, "name") ?? "",
            strategy: stringField(record, "strategy") ?? "fallback",
            fallbackStrategy: stringField(record, "fallbackStrategy") ?? "limited",
            defaultThinkingLevel: optionalStringField(record, "defaultThinkingLevel"),
            contextLimitTokens: intField(record, "contextLimitTokens"),
            contextLimitRemembered: intField(record, "contextLimitRemembered"),
            memberEntryIdsJson: inboundMembersJson,
            sortOrder: intField(record, "sortOrder") ?? 0,
            updatedAt: (dateField(record, "updatedAt") ?? Date()).timeIntervalSince1970,
            extrasJson: optionalStringField(record, "extrasJson"),
            inboundAddedMembers: inboundAdded,
            inboundRemovedMembers: inboundRemoved
        )
        if applied {
            // [T-icloud-modelgroup-orphan-members] Orphan diagnostic
            // (OpenMinis#98 defect 1). A group references model ENTRIES, and an
            // entry id embeds the minting device's provider-instance UUID
            // (`<instanceUUID>/<modelId>`, see ModelEntry.compositeKey). Nothing
            // remaps that on inbound, so a group synced from a peer can land
            // holding member ids no local entry will ever satisfy — the UI then
            // silently `compactMap`s them away and the group looks half-empty
            // for no visible reason.
            //
            // Log the unresolvable members (and which peer instance they point
            // at) so a recurrence is diagnosable from a Release log instead of
            // needing a two-device repro.
            await logOrphanGroupMembers(groupId: id, source: "v3-inbound")
            // Recompute post-merge member count for the union-merge log.
            let mergedCount = (await db.groupRow(id: id)?["member_entry_ids_json"] as? String)
                .flatMap { ((try? JSONSerialization.jsonObject(with: Data($0.utf8))) as? [Any])?.count } ?? -1
            logger.info("[v3] applied ProviderModelGroupV3 \(id.prefix(8)) members union-merged: local=\(localCount) inbound=\(inboundCount) → \(mergedCount) (added-tombstones=\(inboundAdded.count) removed-tombstones=\(inboundRemoved.count))")
            await refreshStoreFromDB()
        } else {
            logger.info("[v3] skipped ProviderModelGroupV3 \(id.prefix(8)) (no scalar/member change) members(local=\(localCount) inbound=\(inboundCount))")
        }
    }

    // MARK: - ProviderThinkingRuleV3 (T-icloud-thinking-rules-sync)

    /// Outbound. Returns nil when the rule no longer exists locally (deleted between
    /// the markDirty and the push) — the caller treats nil as "nothing to upload" and
    /// drops the dirty row, which is the same contract the group builder relies on.
    private static func buildProviderThinkingRuleV3(id: String) async -> PortableRecord? {
        guard let db = ProviderConfigStore.shared.db else { return nil }
        guard let row = await db.thinkingRuleRow(id: id) else { return nil }
        let synced = SyncedProviderThinkingRuleV3(
            id: id,
            instanceId: (row["instance_id"] as? String) ?? "",
            sortOrder: (row["sort_order"] as? Int) ?? 0,
            scopeKind: (row["scope_kind"] as? String) ?? "allModels",
            scopePattern: row["scope_pattern"] as? String,
            // Opaque passthrough — see SyncedProviderThinkingRuleV3's doc comment.
            wireFormatJson: (row["wire_format_json"] as? String) ?? "{}",
            echoField: row["echo_field"] as? String,
            echoTiming: row["echo_timing"] as? String,
            label: (row["label"] as? String) ?? "",
            createdAt: Date(timeIntervalSince1970: (row["created_at"] as? Double) ?? 0),
            updatedAt: Date(timeIntervalSince1970: (row["updated_at"] as? Double) ?? 0)
        )
        return SyncableTypeRegistry.shared
            .metadata(for: "ProviderThinkingRuleV3")?
            .buildPortable(synced)
    }

    /// Inbound upsert. LWW by `updatedAt` is enforced inside the DB call.
    private static func mergeProviderThinkingRuleV3(record: PortableRecord) async {
        guard let db = ProviderConfigStore.shared.db else {
            logger.warning("[v3] mergeProviderThinkingRuleV3 DROPPED (DB not open yet) id=\(record.id.id.prefix(8)) — will re-pull")
            return
        }
        let id = record.id.id
        // [T-icloud-record-delete-resurrection] Same resurrection guard the other
        // provider mergers use: a rule just deleted here must not be re-inserted by a
        // fetchRecentV2 that raced ahead of the op=delete reaching the cloud.
        if await ChatStore.shared.isRecentlyDeletedRecord(
            type: "ProviderThinkingRuleV3", id: id,
            remoteUpdatedAt: dateField(record, "updatedAt") ?? Date()) {
            logger.info("[v3] mergeProviderThinkingRuleV3 SKIP (recently deleted locally): id=\(id.prefix(8))")
            return
        }
        guard let instanceId = stringField(record, "instanceId"), !instanceId.isEmpty else {
            logger.warning("[v3] mergeProviderThinkingRuleV3 SKIP (no instanceId) id=\(id.prefix(8))")
            return
        }
        // A rule whose owning instance does not exist here would be invisible and
        // un-deletable in the UI (the provider page is the only place it renders), so
        // keep it out of the DB. The instance record syncs independently; when it
        // arrives, a later push of this rule re-delivers it.
        guard ProviderConfigStore.shared.config.instances.contains(where: { $0.id == instanceId }) else {
            logger.info("[v3] mergeProviderThinkingRuleV3 SKIP (unknown instance \(instanceId.prefix(8))) id=\(id.prefix(8))")
            return
        }
        let applied = await db.upsertThinkingRuleFromInbound(
            id: id,
            instanceId: instanceId,
            sortOrder: intField(record, "sortOrder") ?? 0,
            scopeKind: stringField(record, "scopeKind") ?? "allModels",
            scopePattern: optionalStringField(record, "scopePattern"),
            wireFormatJson: stringField(record, "wireFormatJson") ?? "{}",
            echoField: optionalStringField(record, "echoField"),
            echoTiming: optionalStringField(record, "echoTiming"),
            label: stringField(record, "label") ?? "",
            createdAt: (dateField(record, "createdAt") ?? Date()).timeIntervalSince1970,
            updatedAt: (dateField(record, "updatedAt") ?? Date()).timeIntervalSince1970
        )
        logger.info("[v3] mergeProviderThinkingRuleV3 \(applied ? "APPLIED" : "SKIPPED (local newer)") id=\(id.prefix(8))")
        if applied {
            // The resolver reads a synchronous in-memory cache, so an inbound change is
            // invisible to the request path until the cache is reloaded.
            await ProviderConfigStore.shared.reloadThinkingRuleCache()
        }
    }

    /// Inbound delete. The local row really is removed — the tombstone written by the
    /// ORIGINATING device is what stops the resurrection race; this side just applies.
    private static func deleteProviderThinkingRuleV3(id: String) async {
        guard let db = ProviderConfigStore.shared.db else {
            logger.warning("[v3] deleteProviderThinkingRuleV3 DROPPED (DB not open yet) id=\(id.prefix(8))")
            return
        }
        await db.deleteThinkingRule(id: id)
        logger.info("[v3] deleted ProviderThinkingRuleV3 \(id.prefix(8))")
        await ProviderConfigStore.shared.reloadThinkingRuleCache()
    }

    private static func deleteProviderModelGroupV3(id: String) async {
        guard let db = ProviderConfigStore.shared.db else {
            logger.warning("[v3] deleteProviderModelGroupV3 DROPPED (DB not open yet) id=\(id.prefix(8))")
            return
        }
        await db.deleteGroupRow(id: id)
        logger.info("[v3] deleted ProviderModelGroupV3 \(id.prefix(8))")
        await refreshStoreFromDB()
    }

    /// After any V3 inbound mutation, re-hydrate the
    /// ProviderConfigStore's in-memory `config` from the DB so the UI
    /// reflects the change.
    ///
    /// [T-icloud-provider-sync-apply-coalesce] COALESCED. The sync framework
    /// calls the per-record `mergeProviderXxxV3` once per inbound record, and
    /// each applied record used to run a FULL refresh inline (dump all ~800
    /// entries → dedupe → encode → write → bulkReplace). A single fetchRecentV2
    /// batch can carry hundreds of provider records, so that produced hundreds
    /// of full refreshes back-to-back on the main thread — the multi-frame
    /// scroll stalls we traced (each [v3sync] applyMergedConfigFromSync line is
    /// one full rebuild; the burst hit dozens per second). Instead, debounce:
    /// each call marks the store "dirty" and (re)arms a short timer; the actual
    /// refresh runs ONCE after the inbound burst goes quiet, collapsing N
    /// rebuilds into 1. The dump itself already runs off the main thread; only
    /// the final apply hops to @MainActor.
    private static func refreshStoreFromDB() async {
        await RefreshCoalescer.shared.schedule()
    }

    /// Serializes + debounces provider-store refreshes so a burst of inbound
    /// V3 merges collapses into a single full rebuild.
    private actor RefreshCoalescer {
        static let shared = RefreshCoalescer()
        private var pending = false
        private var task: Task<Void, Never>?
        /// Quiet window: a refresh fires this long after the LAST schedule()
        /// call, so a tight burst of per-record merges produces one refresh.
        private static let debounceNanos: UInt64 = 250_000_000  // 250ms

        func schedule() {
            pending = true
            task?.cancel()
            task = Task { [weak self] in
                try? await Task.sleep(nanoseconds: RefreshCoalescer.debounceNanos)
                if Task.isCancelled { return }
                await self?.fire()
            }
        }

        private func fire() async {
            guard pending else { return }
            pending = false
            await ChatStoreSyncHydrators.performRefreshStoreFromDB()
        }
    }

    /// The actual refresh: dump the DB off-main, then apply on the main actor.
    /// Calls applyMergedConfigFromSync which writes disk+DB and notifies
    /// observers WITHOUT marking dirty (inbound updates don't loop back).
    private static func performRefreshStoreFromDB() async {
        guard let db = ProviderConfigStore.shared.db else {
            logger.warning("[v3] refreshStoreFromDB skipped — DB not open yet")
            return
        }
        let fresh = await db.dumpProviderConfig()
        await MainActor.run {
            ProviderConfigStore.shared.applyMergedConfigFromSync(fresh)
        }
    }
}

// MARK: - V3 bootstrap / feature flag

/// Controls whether the v3 per-record sync surface is the authoritative
/// inbound path for ProviderConfig data. When ON:
///   - Outbound: v2 (legacy whole-file) + v3 (per-record) are emitted.
///   - Inbound: v3 is merged; v2 is silently dropped (S7 short-circuit).
/// Default ON for builds that include this commit. Kill-switch via
/// UserDefaults key `cloudSync.providerV3.enabled` lets us flip back to
/// V2-only behavior in the field if v3 has unforeseen bugs.
@MainActor
enum ProviderV3Bootstrap {
    private static let key = "cloudSync.providerV3.enabled"
    static var isEnabled: Bool {
        UserDefaults.standard.object(forKey: key) as? Bool ?? true
    }
    static func setEnabled(_ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: key)
        logger.info("[v3] ProviderV3Bootstrap.setEnabled(\(enabled))")
    }
}
