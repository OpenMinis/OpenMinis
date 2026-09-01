import Foundation

/// Mirror types used as the "syncable shape" of local models. Kept as
/// separate structs (not Syncable conformance directly on the local
/// models) so v2 sync metadata stays decoupled from the rest of the app —
/// adding/removing sync fields doesn't ripple into ChatStore, etc.
///
/// Each Synced* type exposes a `from(...)` builder that converts a local
/// model into its synced shape. The reverse direction (Synced* → local
/// SQLite write) lives in SyncCore (P3) where the merge policy executes.

// MARK: - SyncedSession

struct SyncedSession: Syncable {
    var id: String
    var title: String?
    var category: String?
    var modelId: String
    var createdAt: Date
    var updatedAt: Date
    var memoryEnabled: Int          // 0/1
    var modelBinding: String?
    var pinnedAt: Date?
    var folderId: String?

    static let syncMetadata: SyncTypeMetadata<SyncedSession> = {
        typealias F = FieldDescriptor<SyncedSession>
        return SyncTypeMetadata<SyncedSession>(
            recordType: "SessionV2",
            idKeyPath: \SyncedSession.id,
            scope: .perObject(\SyncedSession.id),
            fields: [
                F.string("sessionId",        \SyncedSession.id),
                F.optionalString("title",    \SyncedSession.title),
                F.optionalString("category", \SyncedSession.category),
                F.string("modelId",          \SyncedSession.modelId),
                F.date("createdAt",          \SyncedSession.createdAt),
                F.date("updatedAt",          \SyncedSession.updatedAt),
                F.int("memoryEnabled",       \SyncedSession.memoryEnabled),
                F.optionalString("modelBinding", \SyncedSession.modelBinding),
                F.optionalDate("pinnedAt",   \SyncedSession.pinnedAt),
                // Optional field added the same way pinnedAt was (no version
                // bump): old devices ignore the unknown CKRecord field on read
                // and omit it on write, both of which decode as nil here.
                F.optionalString("folderId", \SyncedSession.folderId),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedSession.updatedAt),
            version: 1
        )
    }()

    static func from(_ s: ChatSession, memoryEnabled: Bool, modelBinding: String?) -> SyncedSession {
        SyncedSession(
            id: s.id,
            title: s.title,
            category: s.category,
            modelId: s.modelId,
            createdAt: s.createdAt,
            updatedAt: s.updatedAt,
            memoryEnabled: memoryEnabled ? 1 : 0,
            modelBinding: modelBinding,
            pinnedAt: s.pinnedAt,
            folderId: s.folderId
        )
    }
}

// MARK: - SyncedMessage

struct SyncedMessage: Syncable {
    var id: String
    var sessionId: String
    var role: String
    var partsJson: String           // JSON-encoded [ContentPart]
    var tokenUsageJson: String?
    var reasoningContent: String?
    var streamInterruptCount: Int
    /// Best-effort hint only. Receivers MUST derive their own sort_order from
    /// (created_at, id) rank — see `ChatStore.mergeRemoteMessage`. Trusting
    /// the wire integer leaks ordering bugs across devices with differing
    /// message counts.
    var sortOrder: Int
    var createdAt: Date
    var updatedAt: Date
    /// [T-token-attribution-snapshot] Which model actually produced this
    /// message. Optional so a device on an older build — which sends none of
    /// these — still decodes: `optionalString` returns nil for a missing key
    /// rather than throwing, so no protocol version bump is needed.
    ///
    /// A receiver must treat nil as "no information", never as "empty": see the
    /// COALESCE guards in `ChatStore.mergeRemoteMessage`, without which an
    /// older device echoing a message back would wipe a newer device's
    /// correctly-recorded attribution.
    var modelId: String?
    var modelDisplayName: String?
    var providerType: String?
    var providerInstanceId: String?

    static let syncMetadata: SyncTypeMetadata<SyncedMessage> = {
        typealias F = FieldDescriptor<SyncedMessage>
        return SyncTypeMetadata<SyncedMessage>(
            recordType: "MessageV2",
            idKeyPath: \SyncedMessage.id,
            scope: .perParent(parentType: "SessionV2", \SyncedMessage.sessionId),
            fields: [
                F.string("messageId",                 \SyncedMessage.id),
                F.string("sessionId",                 \SyncedMessage.sessionId),
                F.string("role",                      \SyncedMessage.role),
                F.string("partsJson",                 \SyncedMessage.partsJson),
                F.optionalString("tokenUsageJson",    \SyncedMessage.tokenUsageJson),
                F.optionalString("reasoningContent",  \SyncedMessage.reasoningContent),
                F.int("streamInterruptCount",         \SyncedMessage.streamInterruptCount),
                F.int("sortOrder",                    \SyncedMessage.sortOrder),
                F.date("createdAt",                   \SyncedMessage.createdAt),
                F.date("updatedAt",                   \SyncedMessage.updatedAt),
                F.optionalString("modelId",           \SyncedMessage.modelId),
                F.optionalString("modelDisplayName",  \SyncedMessage.modelDisplayName),
                F.optionalString("providerType",      \SyncedMessage.providerType),
                F.optionalString("providerInstanceId", \SyncedMessage.providerInstanceId),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedMessage.updatedAt),
            version: 1
        )
    }()

    /// Builds a SyncedMessage from a RawMessage. `updatedAt` defaults to
    /// `createdAt` when the local DB doesn't track an explicit message
    /// updated_at column for this row (legacy rows pre-migration).
    static func from(_ m: RawMessage, updatedAt: Date) -> SyncedMessage {
        let partsJson: String = (try? String(data: JSONEncoder().encode(m.parts), encoding: .utf8)) ?? "[]"
        let tokenUsageJson: String? = m.tokenUsage.flatMap {
            (try? JSONEncoder().encode($0)).flatMap { String(data: $0, encoding: .utf8) }
        }
        return SyncedMessage(
            id: m.id,
            sessionId: m.sessionId,
            role: m.role.rawValue,
            partsJson: partsJson,
            tokenUsageJson: tokenUsageJson,
            reasoningContent: m.reasoningContent,
            streamInterruptCount: m.streamInterruptCount,
            sortOrder: m.sortOrder,
            createdAt: m.createdAt,
            updatedAt: updatedAt,
            modelId: m.modelId,
            modelDisplayName: m.modelDisplayName,
            providerType: m.providerType,
            providerInstanceId: m.providerInstanceId
        )
    }
}

// MARK: - SyncedCompactMarker

struct SyncedCompactMarker: Syncable {
    var id: String
    var sessionId: String
    var summary: String
    var firstKeptSortOrder: Int
    var compactedCount: Int
    var createdAt: Date
    var uiBoundarySortOrder: Int?
    var boundaryMessageId: String?
    var firstKeptMessageId: String?
    var lastCompactedMessageId: String?
    /// Marker schema version (see CompactMarker.version). Synced so peer
    /// devices know whether to use the v2 id-only resolution path or the v1
    /// multi-field fallback chain.
    var version: Int

    static let syncMetadata: SyncTypeMetadata<SyncedCompactMarker> = {
        typealias F = FieldDescriptor<SyncedCompactMarker>
        return SyncTypeMetadata<SyncedCompactMarker>(
            recordType: "CompactMarkerV2",
            idKeyPath: \SyncedCompactMarker.id,
            scope: .perParent(parentType: "SessionV2", \SyncedCompactMarker.sessionId),
            fields: [
                F.string("markerId",                     \SyncedCompactMarker.id),
                F.string("sessionId",                    \SyncedCompactMarker.sessionId),
                F.string("summary",                      \SyncedCompactMarker.summary),
                F.int("firstKeptSortOrder",              \SyncedCompactMarker.firstKeptSortOrder),
                F.int("compactedCount",                  \SyncedCompactMarker.compactedCount),
                F.date("createdAt",                      \SyncedCompactMarker.createdAt),
                F.optionalInt("uiBoundarySortOrder",     \SyncedCompactMarker.uiBoundarySortOrder),
                F.optionalString("boundaryMessageId",    \SyncedCompactMarker.boundaryMessageId),
                F.optionalString("firstKeptMessageId",   \SyncedCompactMarker.firstKeptMessageId),
                F.optionalString("lastCompactedMessageId", \SyncedCompactMarker.lastCompactedMessageId),
                F.int("markerVersion",                   \SyncedCompactMarker.version),
            ],
            conflictPolicy: .alwaysAccept,
            version: 1
        )
    }()

    static func from(_ m: CompactMarker) -> SyncedCompactMarker {
        SyncedCompactMarker(
            id: m.id,
            sessionId: m.sessionId,
            summary: m.summary,
            firstKeptSortOrder: m.firstKeptSortOrder,
            compactedCount: m.compactedCount,
            createdAt: m.createdAt,
            uiBoundarySortOrder: m.uiBoundarySortOrder,
            boundaryMessageId: m.boundaryMessageId,
            firstKeptMessageId: m.firstKeptMessageId,
            lastCompactedMessageId: m.lastCompactedMessageId,
            version: m.version
        )
    }
}

// MARK: - SyncedSessionFile

struct SyncedSessionFile: Syncable {
    var sessionId: String
    var relativePath: String
    var fileSize: Int
    var mimeType: String?
    /// composite identity — mirrors the v1 recordName format.
    var compositeKey: String { "\(sessionId):\(relativePath)" }
    var updatedAt: Date

    /// Local file URL referenced by this record. Not transmitted directly
    /// — transports surface it through PortableAsset.
    var fileURL: URL

    static let syncMetadata: SyncTypeMetadata<SyncedSessionFile> = {
        typealias F = FieldDescriptor<SyncedSessionFile>
        return SyncTypeMetadata<SyncedSessionFile>(
            recordType: "SessionFileV2",
            idKeyPath: \SyncedSessionFile.compositeKey,
            scope: .perParent(parentType: "SessionV2", \SyncedSessionFile.sessionId),
            fields: [
                F.string("sessionId",        \SyncedSessionFile.sessionId),
                F.string("relativePath",     \SyncedSessionFile.relativePath),
                F.int("fileSize",            \SyncedSessionFile.fileSize),
                F.optionalString("mimeType", \SyncedSessionFile.mimeType),
                F.date("updatedAt",          \SyncedSessionFile.updatedAt),
            ],
            conflictPolicy: .lastWriteWinsByServerEtag,
            version: 1
        )
    }()
}

// MARK: - SyncedSkill

struct SyncedSkill: Syncable {
    var id: String
    var name: String
    var description: String
    var version: String
    var importSource: String
    var isEnabled: Int
    var installedAt: Date
    var updatedAt: Date
    var bodyText: String?
    /// Local URL of the bundled ZIP (SKILL.md + scripts/ + references/ + assets/).
    /// Transmitted by the transport as a PortableAsset.
    var bundleZipURL: URL?

    static let syncMetadata: SyncTypeMetadata<SyncedSkill> = {
        typealias F = FieldDescriptor<SyncedSkill>
        return SyncTypeMetadata<SyncedSkill>(
            recordType: "SkillV2",
            idKeyPath: \SyncedSkill.id,
            scope: .global,
            fields: [
                F.string("skillId",           \SyncedSkill.id),
                F.string("name",              \SyncedSkill.name),
                F.string("description",       \SyncedSkill.description),
                F.string("version",           \SyncedSkill.version),
                F.string("importSource",      \SyncedSkill.importSource),
                F.int("isEnabled",            \SyncedSkill.isEnabled),
                F.date("installedAt",         \SyncedSkill.installedAt),
                F.date("updatedAt",           \SyncedSkill.updatedAt),
                F.optionalString("bodyText",  \SyncedSkill.bodyText),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedSkill.updatedAt),
            version: 1
        )
    }()
}

// MARK: - SyncedProviderConfig

struct SyncedProviderConfig: Syncable {
    /// Constant id — only one ProviderConfig per app.
    var id: String = "provider-config"
    var configJson: String
    var secretsJsonBase64: String       // base64-wrapped secrets blob
    var updatedAt: Date

    static let syncMetadata: SyncTypeMetadata<SyncedProviderConfig> = {
        typealias F = FieldDescriptor<SyncedProviderConfig>
        return SyncTypeMetadata<SyncedProviderConfig>(
            recordType: "ProviderConfigV2",
            idKeyPath: \SyncedProviderConfig.id,
            scope: .global,
            fields: [
                F.string("configJson",   \SyncedProviderConfig.configJson),
                F.string("secretsJson",  \SyncedProviderConfig.secretsJsonBase64),
                F.date("updatedAt",      \SyncedProviderConfig.updatedAt),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedProviderConfig.updatedAt),
            version: 1
        )
    }()
}

// MARK: - SyncedMCPServers

/// [T-mcp-integration-ios] Whole-file sync of /var/minis/mcp-servers/servers.json
/// (the MCP server list). One record per app; last-write-wins by updatedAt
/// (the file mtime), aligned with how the other config files sync.
struct SyncedMCPServers: Syncable {
    /// Constant id — only one MCP server list per app.
    var id: String = "mcp-servers"
    var serversJson: String
    var updatedAt: Date

    static let syncMetadata: SyncTypeMetadata<SyncedMCPServers> = {
        typealias F = FieldDescriptor<SyncedMCPServers>
        return SyncTypeMetadata<SyncedMCPServers>(
            recordType: "MCPServersV2",
            idKeyPath: \SyncedMCPServers.id,
            scope: .global,
            fields: [
                F.string("serversJson", \SyncedMCPServers.serversJson),
                F.date("updatedAt",     \SyncedMCPServers.updatedAt),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedMCPServers.updatedAt),
            version: 1
        )
    }()
}

// MARK: - SyncedMCPServer (per-server record, current schema)
//
// [T-mcp-per-server-sync] One CK record per MCP server; recordName = the
// server's name (the key under `mcpServers` in servers.json). Replaces the
// whole-file MCPServersV2 outbound, which had the classic whole-file-LWW
// failure mode: two devices each adding a different server, the later file
// mtime wins and silently drops the other device's addition (the same
// defect the EnvVarV2 → EnvVarItem migration fixed for env vars).
//
// Per-server records resolve as a natural union (different recordNames);
// double-edits of the same server resolve via per-entry updatedAt LWW;
// deletes use the standard op=delete tombstone path and cannot resurrect
// (recently-deleted guard in the merger).
//
// `entryJson` is the full on-disk ServerEntry object serialized verbatim
// (note/enabled/url/headers/command/args/env/startupTimeoutSeconds/...)
// so fields added by a newer build round-trip through an older peer
// without being stripped. Headers/env can hold literal tokens → the type
// lives in the minis-secrets zone alongside ProviderConfigV2.
struct SyncedMCPServer: Syncable {
    var id: String          // server name — also the CK recordName
    var entryJson: String   // full ServerEntry JSON, verbatim round-trip
    var createdAt: Date
    var updatedAt: Date     // per-entry LWW clock (servers.json `updatedAt`)

    static let syncMetadata: SyncTypeMetadata<SyncedMCPServer> = {
        typealias F = FieldDescriptor<SyncedMCPServer>
        return SyncTypeMetadata<SyncedMCPServer>(
            recordType: "MCPServerItem",
            idKeyPath: \SyncedMCPServer.id,
            scope: .global,
            fields: [
                F.string("serverName", \SyncedMCPServer.id),
                F.string("entryJson",  \SyncedMCPServer.entryJson),
                F.date("createdAt",    \SyncedMCPServer.createdAt),
                F.date("updatedAt",    \SyncedMCPServer.updatedAt),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedMCPServer.updatedAt),
            version: 1
        )
    }()
}

// MARK: - SyncedProviderInstanceV3 / EntryV3 / GroupV3 (per-record v3 sync)
//
// Replaces the file-level SyncedProviderConfig with one CKRecord per
// ProviderInstance / ModelEntry / ModelGroup. Deletes propagate via the
// CKRecord `op="delete"` path instead of application-layer tombstones
// — a deleted row is gone for good and cannot be resurrected by a
// peer's stale snapshot.
//
// Stale-field strategy: every record carries an `extrasJson` field
// envelope so a v3.0 device round-tripping a record from a future
// v3.x build doesn't silently strip unknown fields. This is the exact
// failure mode that pinned v2 into the deletedInstances ping-pong loop
// — solved here at the record-shape level, not application-layer.
//
// Secret storage: per the agreed design (decision #2), secrets are
// inlined on the instance record as `secretBlob` (base64-wrapped
// ciphertext). Changes to instance metadata (label, baseURL, etc.) and
// secret rotation both touch the same record, but the cost of
// re-uploading secret bytes on a label change is negligible at the
// payload sizes involved (single-instance record is ~hundreds of
// bytes vs the legacy whole-file 290KB).

struct SyncedProviderInstanceV3: Syncable {
    var id: String                       // = ProviderInstance.id (UUID)
    var label: String
    var providerType: String             // ProviderType.rawValue
    var credentialType: String?          // ProviderCredential.rawValue
    var customBaseURL: String?
    /// 0/1 — keeps wire shape uniform with existing booleans on
    /// SyncedSession.memoryEnabled and SyncedSkill.isEnabled.
    var appendV1Suffix: Int
    var imageEndpointMode: String?       // ImageEndpointMode.rawValue
    var imageEndpointResolved: String?
    var isEnabled: Int                   // 0/1
    var sortOrder: Int
    var secretBlob: String?              // base64-wrapped Keychain secret; nil = no secret yet
    var secretKind: String?              // 'apiKey' | 'manual-oauth-token' | 'oauth-token' | nil
    var secretUpdatedAt: Date?
    var createdAt: Date
    var updatedAt: Date
    var extrasJson: String?              // forward-compat envelope
    var customUserAgent: String?         // per-provider outbound UA override; nil = default
    /// [T-ios-azure-openai] 0/1 — Azure OpenAI mode (api-key auth + Azure URL).
    /// Older records / older builds lack this CloudKit field; it decodes to 0
    /// (off), so a peer that never heard of Azure simply treats the instance as
    /// a normal OpenAI/Responses instance. New value rides extrasJson-free on its
    /// own field; LWW by updatedAt as with every other field.
    var azureMode: Int                   // 0/1

    static let syncMetadata: SyncTypeMetadata<SyncedProviderInstanceV3> = {
        typealias F = FieldDescriptor<SyncedProviderInstanceV3>
        return SyncTypeMetadata<SyncedProviderInstanceV3>(
            recordType: "ProviderInstanceV3",
            idKeyPath: \SyncedProviderInstanceV3.id,
            scope: .global,
            fields: [
                F.string("label",                  \SyncedProviderInstanceV3.label),
                F.string("providerType",           \SyncedProviderInstanceV3.providerType),
                F.optionalString("credentialType", \SyncedProviderInstanceV3.credentialType),
                F.optionalString("customBaseURL",  \SyncedProviderInstanceV3.customBaseURL),
                F.int("appendV1Suffix",            \SyncedProviderInstanceV3.appendV1Suffix),
                F.optionalString("imageEndpointMode",     \SyncedProviderInstanceV3.imageEndpointMode),
                F.optionalString("imageEndpointResolved", \SyncedProviderInstanceV3.imageEndpointResolved),
                F.int("isEnabled",                 \SyncedProviderInstanceV3.isEnabled),
                F.int("sortOrder",                 \SyncedProviderInstanceV3.sortOrder),
                F.optionalString("secretBlob",     \SyncedProviderInstanceV3.secretBlob),
                F.optionalString("secretKind",     \SyncedProviderInstanceV3.secretKind),
                F.optionalDate("secretUpdatedAt",  \SyncedProviderInstanceV3.secretUpdatedAt),
                F.date("createdAt",                \SyncedProviderInstanceV3.createdAt),
                F.date("updatedAt",                \SyncedProviderInstanceV3.updatedAt),
                F.optionalString("extrasJson",     \SyncedProviderInstanceV3.extrasJson),
                F.optionalString("customUserAgent", \SyncedProviderInstanceV3.customUserAgent),
                F.int("azureMode",                 \SyncedProviderInstanceV3.azureMode),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedProviderInstanceV3.updatedAt),
            version: 1
        )
    }()
}

struct SyncedProviderModelEntryV3: Syncable {
    /// [T-provider-entry-composite-key] Plan-X: this is the entry's random
    /// UUID = its DB primary key, NOT the composite key. The DB id column stays
    /// the uuid so old group/binding references (also uuids) keep matching and
    /// downgrade is safe. Cross-device de-dup of the same (instance, model) is
    /// handled at the MERGE layer: on inbound the receiver computes the
    /// composite key from instanceId+modelId, learns legacyUuid→compositeKey,
    /// and normalizes references. (An earlier iteration keyed the record by the
    /// composite key and wrote composite keys into the DB id column — that
    /// orphaned every uuid reference; reverted.)
    var id: String
    var providerInstanceId: String
    var baseModelJson: String            // LLMModel JSON (contains modelId)
    var overridesJson: String?           // ModelOverrides JSON; nil if no overrides
    var isCustom: Int                    // 0/1
    var isHidden: Int                    // 0/1
    var userModifiedAt: Date?
    var sortOrder: Int
    var updatedAt: Date
    var extrasJson: String?
    /// Transition field: the random uuid this entry had before migration, so a
    /// receiver can build legacyUuid→compositeKey and normalize old references.
    /// nil for entries created fresh under the composite-key scheme.
    var legacyUuid: String?

    static let syncMetadata: SyncTypeMetadata<SyncedProviderModelEntryV3> = {
        typealias F = FieldDescriptor<SyncedProviderModelEntryV3>
        return SyncTypeMetadata<SyncedProviderModelEntryV3>(
            recordType: "ProviderModelEntryV3",
            idKeyPath: \SyncedProviderModelEntryV3.id,
            scope: .global,
            fields: [
                F.string("providerInstanceId",  \SyncedProviderModelEntryV3.providerInstanceId),
                F.string("baseModelJson",       \SyncedProviderModelEntryV3.baseModelJson),
                F.optionalString("overridesJson", \SyncedProviderModelEntryV3.overridesJson),
                F.int("isCustom",               \SyncedProviderModelEntryV3.isCustom),
                F.int("isHidden",               \SyncedProviderModelEntryV3.isHidden),
                F.optionalDate("userModifiedAt", \SyncedProviderModelEntryV3.userModifiedAt),
                F.int("sortOrder",              \SyncedProviderModelEntryV3.sortOrder),
                F.date("updatedAt",             \SyncedProviderModelEntryV3.updatedAt),
                F.optionalString("extrasJson",  \SyncedProviderModelEntryV3.extrasJson),
                F.optionalString("legacyUuid",  \SyncedProviderModelEntryV3.legacyUuid),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedProviderModelEntryV3.updatedAt),
            version: 1
        )
    }()
}

struct SyncedProviderModelGroupV3: Syncable {
    var id: String                       // = ModelGroup.id (UUID)
    var name: String
    var strategy: String                 // RoutingStrategy.rawValue
    var fallbackStrategy: String         // FallbackStrategy.rawValue
    var defaultThinkingLevel: String?    // ThinkingLevel.rawValue
    var contextLimitTokens: Int?
    var contextLimitRemembered: Int?     // = ModelGroup.lastContextLimitTokens
    var memberEntryIdsJson: String       // JSON array, ordered
    var sortOrder: Int
    var updatedAt: Date
    var extrasJson: String?
    /// [T-icloud-provider-sync-consistency] JSON object {entryId: removedAtEpoch}.
    /// Member-removal tombstones; travels with the record so every device can
    /// honour a removal across the UNION merge. optionalString (defaults "{}"
    /// for records from pre-v2 builds, which simply never removed members).
    var removedMembersJson: String?

    static let syncMetadata: SyncTypeMetadata<SyncedProviderModelGroupV3> = {
        typealias F = FieldDescriptor<SyncedProviderModelGroupV3>
        return SyncTypeMetadata<SyncedProviderModelGroupV3>(
            recordType: "ProviderModelGroupV3",
            idKeyPath: \SyncedProviderModelGroupV3.id,
            scope: .global,
            fields: [
                F.string("name",                 \SyncedProviderModelGroupV3.name),
                F.string("strategy",             \SyncedProviderModelGroupV3.strategy),
                F.string("fallbackStrategy",     \SyncedProviderModelGroupV3.fallbackStrategy),
                F.optionalString("defaultThinkingLevel", \SyncedProviderModelGroupV3.defaultThinkingLevel),
                F.optionalInt("contextLimitTokens",      \SyncedProviderModelGroupV3.contextLimitTokens),
                F.optionalInt("contextLimitRemembered",  \SyncedProviderModelGroupV3.contextLimitRemembered),
                F.string("memberEntryIdsJson",   \SyncedProviderModelGroupV3.memberEntryIdsJson),
                F.int("sortOrder",               \SyncedProviderModelGroupV3.sortOrder),
                F.date("updatedAt",              \SyncedProviderModelGroupV3.updatedAt),
                F.optionalString("extrasJson",   \SyncedProviderModelGroupV3.extrasJson),
                F.optionalString("removedMembersJson", \SyncedProviderModelGroupV3.removedMembersJson),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedProviderModelGroupV3.updatedAt),
            version: 1
        )
    }()
}

/// [T-icloud-thinking-rules-sync] One USER-AUTHORED thinking rule.
///
/// Built-in rules are deliberately absent from this type. They are code constants
/// recomputed per request on every device, are never persisted (`is_builtin = 0` is
/// hardcoded on every write path), and must never travel over the wire — a built-in
/// arriving as a record would become an undeletable duplicate on the receiver.
///
/// `wireFormatJson` is carried as an OPAQUE STRING, not a decoded enum. This is the
/// forward-compatibility guarantee: a rule authored on a newer build using a
/// `wire_format.kind` this build has never seen round-trips byte-for-byte instead of
/// being dropped or coerced into a wrong shape. Decoding happens only at the point of
/// use (`ProviderConfigDB.loadThinkingRules`), which already skips an unreadable row
/// individually rather than failing the whole read.
struct SyncedProviderThinkingRuleV3: Syncable {
    var id: String                  // rule UUID (custom rules only)
    var instanceId: String          // owning ProviderInstance UUID
    var sortOrder: Int              // position = priority; first match wins
    var scopeKind: String           // "allModels" | "modelPattern"
    var scopePattern: String?       // non-nil only for .modelPattern
    var wireFormatJson: String      // opaque {"kind":…} payload — see above
    var echoField: String?          // ReasoningEchoPolicy.fieldName
    var echoTiming: String?         // "everyTurn" | "afterToolUseOnly" | "never"
    var label: String
    var createdAt: Date
    var updatedAt: Date

    static let syncMetadata: SyncTypeMetadata<SyncedProviderThinkingRuleV3> = {
        typealias F = FieldDescriptor<SyncedProviderThinkingRuleV3>
        return SyncTypeMetadata<SyncedProviderThinkingRuleV3>(
            recordType: "ProviderThinkingRuleV3",
            idKeyPath: \SyncedProviderThinkingRuleV3.id,
            scope: .global,
            fields: [
                F.string("instanceId",           \SyncedProviderThinkingRuleV3.instanceId),
                F.int("sortOrder",               \SyncedProviderThinkingRuleV3.sortOrder),
                F.string("scopeKind",            \SyncedProviderThinkingRuleV3.scopeKind),
                F.optionalString("scopePattern", \SyncedProviderThinkingRuleV3.scopePattern),
                F.string("wireFormatJson",       \SyncedProviderThinkingRuleV3.wireFormatJson),
                F.optionalString("echoField",    \SyncedProviderThinkingRuleV3.echoField),
                F.optionalString("echoTiming",   \SyncedProviderThinkingRuleV3.echoTiming),
                F.string("label",                \SyncedProviderThinkingRuleV3.label),
                F.date("createdAt",              \SyncedProviderThinkingRuleV3.createdAt),
                F.date("updatedAt",              \SyncedProviderThinkingRuleV3.updatedAt),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedProviderThinkingRuleV3.updatedAt),
            version: 1
        )
    }()
}

// MARK: - SyncedEnvVars (legacy whole-file record)
//
// Kept for inbound compatibility ONLY. New devices push per-variable
// EnvVarItem records (below); the whole-file record is no longer
// produced. Receiver-side logic still applies an inbound EnvVarV2 once
// to seed `env-vars.json` + Keychain values, after which the device
// switches to per-key mode. Cloud copies of EnvVarV2 records get
// cleaned up after every device has migrated (see EnvVarMigration).
struct SyncedEnvVars: Syncable {
    /// Constant id — only one env-vars record per app.
    var id: String = "env-vars"
    var envVarsJson: String
    var envSecretsJsonBase64: String
    var updatedAt: Date

    static let syncMetadata: SyncTypeMetadata<SyncedEnvVars> = {
        typealias F = FieldDescriptor<SyncedEnvVars>
        return SyncTypeMetadata<SyncedEnvVars>(
            recordType: "EnvVarV2",
            idKeyPath: \SyncedEnvVars.id,
            scope: .global,
            fields: [
                F.string("envVarsJson",     \SyncedEnvVars.envVarsJson),
                F.string("envSecretsJson",  \SyncedEnvVars.envSecretsJsonBase64),
                F.date("updatedAt",         \SyncedEnvVars.updatedAt),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedEnvVars.updatedAt),
            version: 1
        )
    }()
}

// MARK: - SyncedEnvVar (per-variable record, current schema)
//
// One CK record per environment variable. Eliminates the
// "whole-file LWW overwrites the other side's edits" failure mode
// that whole-file EnvVarV2 had. Two devices each adding a different
// variable now resolve as a natural union (different recordIds);
// double-edits of the same variable resolve via per-record
// updatedAt LWW; deletes use the standard op=delete tombstone path.
//
// `valueB64`: base64(utf8(value)). Values still go through the sync
// layer (rather than relying solely on iCloud Keychain
// synchronization) because Keychain sync timing is opaque and a
// brand-new entry could otherwise show up on a peer with an empty
// value. The receiver decodes and writes the value into Keychain
// itself. Same trust model as the legacy whole-file path: base64
// is encoding, not encryption — if the user's iCloud account is
// compromised, the values are readable. Existing users have already
// accepted that.
struct SyncedEnvVar: Syncable {
    var id: String         // EnvVarEntry.id (UUID) — also CK recordName
    var key: String        // variable name, e.g. "API_KEY"
    var valueB64: String   // base64(value); empty for "no value yet"
    var note: String       // human description; "" when omitted
    var createdAt: Date
    var updatedAt: Date

    static let syncMetadata: SyncTypeMetadata<SyncedEnvVar> = {
        typealias F = FieldDescriptor<SyncedEnvVar>
        return SyncTypeMetadata<SyncedEnvVar>(
            recordType: "EnvVarItem",
            idKeyPath: \SyncedEnvVar.id,
            scope: .global,
            fields: [
                F.string("entryId",   \SyncedEnvVar.id),
                F.string("key",       \SyncedEnvVar.key),
                F.string("valueB64",  \SyncedEnvVar.valueB64),
                F.string("note",      \SyncedEnvVar.note),
                F.date("createdAt",   \SyncedEnvVar.createdAt),
                F.date("updatedAt",   \SyncedEnvVar.updatedAt),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedEnvVar.updatedAt),
            version: 1
        )
    }()
}

// MARK: - SyncedDevice

struct SyncedDevice: Syncable {
    var id: String                  // device UUID
    var deviceName: String
    var lastSeen: Date
    var osVersion: String
    var uploadTypes: String         // CSV: "Sessions,Messages,..." for diagnostic
    /// App version code (e.g. CFBundleVersion as Int) — see §3.6.6 for use
    /// alongside minimumCompatibleVersion.
    var appVersion: Int

    static let syncMetadata: SyncTypeMetadata<SyncedDevice> = {
        typealias F = FieldDescriptor<SyncedDevice>
        return SyncTypeMetadata<SyncedDevice>(
            recordType: "SyncDeviceV2",
            idKeyPath: \SyncedDevice.id,
            scope: .global,
            fields: [
                F.string("deviceId",    \SyncedDevice.id),
                F.string("deviceName",  \SyncedDevice.deviceName),
                F.date("lastSeen",      \SyncedDevice.lastSeen),
                F.string("osVersion",   \SyncedDevice.osVersion),
                F.string("uploadTypes", \SyncedDevice.uploadTypes),
                F.int("appVersion",     \SyncedDevice.appVersion),
            ],
            conflictPolicy: .alwaysAccept,
            version: 1
        )
    }()
}

// MARK: - Bootstrap

/// Call once during app launch (before any sync activity) to register
/// every Syncable type with the singleton registry. Idempotent.
// MARK: - SyncedSoul (SOUL.md personality/identity file)
//
// One singleton record per iCloud account holding the full SOUL.md text
// (frontmatter + body). LWW by updatedAt — SOUL.md is small and edited
// rarely; per-field merging would not buy anything. The file lives at
// <minisMemoryPersistentDir>/SOUL.md and is read/written by SoulStore.
struct SyncedSoul: Syncable {
    /// Constant id — only one SOUL.md per app/account.
    var id: String = "soul"
    /// Full SOUL.md serialized text (yaml frontmatter + markdown body).
    var contentMarkdown: String
    var updatedAt: Date

    static let syncMetadata: SyncTypeMetadata<SyncedSoul> = {
        typealias F = FieldDescriptor<SyncedSoul>
        return SyncTypeMetadata<SyncedSoul>(
            recordType: "SoulV2",
            idKeyPath: \SyncedSoul.id,
            scope: .global,
            fields: [
                F.string("contentMarkdown", \SyncedSoul.contentMarkdown),
                F.date("updatedAt",          \SyncedSoul.updatedAt),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedSoul.updatedAt),
            version: 1
        )
    }()
}

// MARK: - SyncedMemoryGlobal (GLOBAL.md singleton)
//
// One singleton record per iCloud account holding the full GLOBAL.md text.
// LWW by updatedAt (file mtime). Empty file is never pushed (builder returns nil).
// Lives alongside SoulV2 in minis-shared zone.
struct SyncedMemoryGlobal: Syncable {
    var id: String = "memory-global"   // constant — one record per account
    var contentMarkdown: String        // full GLOBAL.md text
    var updatedAt: Date                // file mtime

    static let syncMetadata: SyncTypeMetadata<SyncedMemoryGlobal> = {
        typealias F = FieldDescriptor<SyncedMemoryGlobal>
        return SyncTypeMetadata<SyncedMemoryGlobal>(
            recordType: "MemoryGlobalV2",
            idKeyPath: \SyncedMemoryGlobal.id,
            scope: .global,
            fields: [
                F.string("contentMarkdown", \SyncedMemoryGlobal.contentMarkdown),
                F.date("updatedAt",          \SyncedMemoryGlobal.updatedAt),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedMemoryGlobal.updatedAt),
            version: 1
        )
    }()
}

// MARK: - SyncedMemoryDaily (per-day daily log)
//
// One record per calendar day (id = "YYYY-MM-DD"). Payload is a JSON array
// of MemoryEntry (timestamp + content). Merger does Set Union by timestamp key,
// so entries from different devices accumulate rather than overwriting each other.
// Only the most recent 30 days are pushed; older files are never enqueued.
struct SyncedMemoryDaily: Syncable {
    var id: String            // "YYYY-MM-DD" — also the CK recordName
    var entriesJson: String   // JSON-encoded [MemoryEntry]
    var updatedAt: Date       // last merge write time

    static let syncMetadata: SyncTypeMetadata<SyncedMemoryDaily> = {
        typealias F = FieldDescriptor<SyncedMemoryDaily>
        return SyncTypeMetadata<SyncedMemoryDaily>(
            recordType: "MemoryDailyV2",
            idKeyPath: \SyncedMemoryDaily.id,
            scope: .global,
            fields: [
                F.string("dateKey",     \SyncedMemoryDaily.id),
                F.string("entriesJson", \SyncedMemoryDaily.entriesJson),
                F.date("updatedAt",     \SyncedMemoryDaily.updatedAt),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedMemoryDaily.updatedAt),
            version: 1
        )
    }()
}

// MARK: - SyncedFolder

/// Home-list session folder (FolderV2). Identity is the locally generated
/// UUID; `name` intentionally carries no uniqueness guarantee — two devices
/// may each create "Work" offline and both records coexist after sync (no
/// auto-merge). Rename is a single LWW field here precisely because members
/// reference the UUID, so a rename never touches SessionV2 records.
/// Deleting a folder propagates as a tombstone whose apply-side clears
/// members' folderId — it never deletes sessions.
struct SyncedFolder: Syncable {
    var id: String
    var name: String
    var icon: String?
    var color: String?
    var origin: String
    var sortIndex: Int
    var pinnedAt: Date?
    var desc: String?
    var createdAt: Date
    var updatedAt: Date

    static let syncMetadata: SyncTypeMetadata<SyncedFolder> = {
        typealias F = FieldDescriptor<SyncedFolder>
        return SyncTypeMetadata<SyncedFolder>(
            recordType: "FolderV2",
            idKeyPath: \SyncedFolder.id,
            scope: .global,
            fields: [
                F.string("folderId",        \SyncedFolder.id),
                F.string("name",            \SyncedFolder.name),
                F.optionalString("icon",    \SyncedFolder.icon),
                F.optionalString("color",   \SyncedFolder.color),
                F.string("origin",          \SyncedFolder.origin),
                F.int("sortIndex",          \SyncedFolder.sortIndex),
                F.optionalDate("pinnedAt",  \SyncedFolder.pinnedAt),
                F.optionalString("desc",    \SyncedFolder.desc),
                F.date("createdAt",         \SyncedFolder.createdAt),
                F.date("updatedAt",         \SyncedFolder.updatedAt),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedFolder.updatedAt),
            version: 1
        )
    }()

    static func from(_ f: ChatFolder) -> SyncedFolder {
        SyncedFolder(
            id: f.id, name: f.name, icon: f.icon, color: f.color,
            origin: f.origin, sortIndex: f.sortIndex, pinnedAt: f.pinnedAt,
            desc: f.desc,
            createdAt: f.createdAt, updatedAt: f.updatedAt
        )
    }
}

enum SyncedTypesBootstrap {
    static func registerAll() {
        let r = SyncableTypeRegistry.shared
        r.register(SyncedSession.self)
        r.register(SyncedMessage.self)
        r.register(SyncedCompactMarker.self)
        r.register(SyncedFolder.self)
        r.register(SyncedSessionFile.self)
        r.register(SyncedSkill.self)
        r.register(SyncedProviderConfig.self)
        r.register(SyncedMCPServers.self)   // legacy whole-file, inbound only
        r.register(SyncedMCPServer.self)    // per-server, current schema
        // v3 per-record provider sync. These coexist with the legacy
        // SyncedProviderConfig dual-write outbound until all peer
        // devices upgrade; inbound v2 is silently dropped on v3 builds
        // (see ChatStoreSyncHydrators.mergeProviderConfig).
        r.register(SyncedProviderInstanceV3.self)
        r.register(SyncedProviderModelEntryV3.self)
        r.register(SyncedProviderModelGroupV3.self)
        r.register(SyncedProviderThinkingRuleV3.self)
        r.register(SyncedEnvVars.self)   // legacy whole-file, inbound only
        r.register(SyncedEnvVar.self)    // per-variable, current schema
        r.register(SyncedDevice.self)
        r.register(SyncedSoul.self)
        r.register(SyncedMemoryGlobal.self)
        r.register(SyncedMemoryDaily.self)
    }
}
