import Foundation

/// Transport-neutral record identifier. Combines record type + opaque id.
/// Multiple transports (iCloud / LAN / etc.) all use this same identity
/// so a record routed through any transport refers to the same logical thing.
struct SyncRecordID: Hashable, Codable, CustomStringConvertible {
    let type: String   // e.g. "MessageV2"
    let id: String     // e.g. "AB12CDEF-..."

    /// Format MUST be `"type:id"` — `ChatStore.clearDirtyRecord(recordName:)`
    /// parses on the first `:` to separate them. Don't change this without
    /// updating the parser at the same time.
    var description: String { "\(type):\(id)" }

    /// Reverse of description: parses "Type:id" back into a SyncRecordID.
    /// Returns nil if the input doesn't contain a single ':' separator.
    static func parse(_ s: String) -> SyncRecordID? {
        let parts = s.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2 else { return nil }
        let t = String(parts[0]); let i = String(parts[1])
        if t.isEmpty || i.isEmpty { return nil }
        return SyncRecordID(type: t, id: i)
    }
}

/// Transport-neutral field value. Every supported scalar / blob type maps
/// here. Both the iCloud transport (CKRecord field setter) and any future
/// LAN transport (JSON encode) consume this same enum.
enum PortableFieldValue: Codable, Equatable {
    case null
    case string(String)
    case int(Int)
    case double(Double)
    case bool(Bool)
    case date(Date)
    case data(Data)

    /// JSON-encoded text payload. The transport stores it as a string;
    /// callers reconstruct the original Codable model via JSONDecoder.
    case json(String)

    // MARK: - Codable

    private enum Tag: String, Codable {
        case null, string, int, double, bool, date, data, json
    }

    private enum CodingKeys: String, CodingKey { case t, v }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .null:           try c.encode(Tag.null,   forKey: .t)
        case .string(let s):  try c.encode(Tag.string, forKey: .t); try c.encode(s, forKey: .v)
        case .int(let i):     try c.encode(Tag.int,    forKey: .t); try c.encode(i, forKey: .v)
        case .double(let d):  try c.encode(Tag.double, forKey: .t); try c.encode(d, forKey: .v)
        case .bool(let b):    try c.encode(Tag.bool,   forKey: .t); try c.encode(b, forKey: .v)
        case .date(let d):    try c.encode(Tag.date,   forKey: .t); try c.encode(d, forKey: .v)
        case .data(let d):    try c.encode(Tag.data,   forKey: .t); try c.encode(d, forKey: .v)
        case .json(let s):    try c.encode(Tag.json,   forKey: .t); try c.encode(s, forKey: .v)
        }
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let tag = try c.decode(Tag.self, forKey: .t)
        switch tag {
        case .null:   self = .null
        case .string: self = .string(try c.decode(String.self, forKey: .v))
        case .int:    self = .int(try c.decode(Int.self, forKey: .v))
        case .double: self = .double(try c.decode(Double.self, forKey: .v))
        case .bool:   self = .bool(try c.decode(Bool.self, forKey: .v))
        case .date:   self = .date(try c.decode(Date.self, forKey: .v))
        case .data:   self = .data(try c.decode(Data.self, forKey: .v))
        case .json:   self = .json(try c.decode(String.self, forKey: .v))
        }
    }
}

/// Transport-neutral asset reference. Always points to a local file URL —
/// the transport is responsible for uploading/streaming/etc as appropriate
/// (CKAsset wrap for iCloud, multipart over WebSocket for LAN, etc.).
struct PortableAsset: Codable, Equatable {
    let key: String          // field name on the record (e.g. "partsAsset")
    let fileURL: URL         // local path
    let size: Int            // bytes
    let mimeType: String?    // optional content type hint
}

/// A complete, transport-neutral record. The unit of synchronization.
///
/// `fields` are values explicitly described by the owning Syncable type's
/// metadata. `unknownFields` are values present on the wire that the local
/// app version does not recognise — they MUST be carried back into any
/// outbound write so newer-version data is not silently destroyed by an
/// older client. See §3.6 of the v2 design.
struct PortableRecord: Codable, Equatable {
    let id: SyncRecordID
    let fields: [String: PortableFieldValue]
    let assets: [String: PortableAsset]

    /// Per-type schema version of the writer. Bumped manually in
    /// SyncTypeMetadata when the type adds fields. Used by readers to know
    /// "newer / same / older than my local schema".
    let schemaVersion: Int

    /// If non-nil, readers whose local version is below this MUST skip
    /// applying the record to local state (the record stays on the wire,
    /// no error). Used as an emergency hatch for breaking schema changes.
    let minimumCompatibleVersion: Int?

    /// Fields the local version's metadata did not declare. Carried so a
    /// write-back round-trip does not destroy them. Empty for records this
    /// version fully understands.
    let unknownFields: [String: PortableFieldValue]

    /// Authoritative wall-clock timestamp used by record-level
    /// last-write-wins conflict resolution. Should mirror the underlying
    /// model's `updatedAt` field.
    let updatedAt: Date

    init(
        id: SyncRecordID,
        fields: [String: PortableFieldValue] = [:],
        assets: [String: PortableAsset] = [:],
        schemaVersion: Int = 1,
        minimumCompatibleVersion: Int? = nil,
        unknownFields: [String: PortableFieldValue] = [:],
        updatedAt: Date
    ) {
        self.id = id
        self.fields = fields
        self.assets = assets
        self.schemaVersion = schemaVersion
        self.minimumCompatibleVersion = minimumCompatibleVersion
        self.unknownFields = unknownFields
        self.updatedAt = updatedAt
    }
}

/// A batch of records to push (or that arrived from a remote). Used by
/// `SyncTransport.send` and observation callbacks alike.
struct SyncOutboundBatch {
    let records: [PortableRecord]
    let deletes: [SyncRecordID]
}

struct SyncInboundBatch {
    let records: [PortableRecord]
    let deletes: [SyncRecordID]
    /// Origin device id when known (for SyncDevice records, audit trails).
    /// `nil` for records the transport can't attribute to a specific source.
    let sourceDeviceId: String?
    /// [T-icloud-sync-tier1-cursor-after-apply] Invoked once SyncCore has
    /// finished applying (or skipping) every record in this batch. The
    /// transport uses it to advance its fetch cursor only AFTER the data is
    /// on disk, so a kill mid-apply re-fetches instead of losing the batch.
    /// Optional so other transports and older call sites are untouched.
    var onApplied: (() -> Void)? = nil

    init(records: [PortableRecord], deletes: [SyncRecordID], sourceDeviceId: String?, onApplied: (() -> Void)? = nil) {
        self.records = records
        self.deletes = deletes
        self.sourceDeviceId = sourceDeviceId
        self.onApplied = onApplied
    }
}

/// Outcome of sending a single record. `transientFailure` means the caller
/// must keep the dirty mark and retry; `permanentFailure` means drop.
enum SyncOutcome: Equatable {
    case success(SyncRecordID)
    case conflict(SyncRecordID, serverRecord: PortableRecord)
    case transientFailure(SyncRecordID, retryAfter: TimeInterval?)
    case permanentFailure(SyncRecordID, reason: String)
}
