import Foundation

/// On-the-wire types for the `.minisbak` backup package.
///
/// Spec: `docs/backup-restore-design.md` §2 / §2.1. Everything in this file is
/// the FORMAT, deliberately kept free of any read-side dependency so the
/// importer (a later stage) can link the same declarations.
///
/// Two rules from §2.2 that shape every type here:
///   - Unknown fields are ignored, missing fields get defaults. So every
///     decodable field that isn't structurally required is `Optional` or has a
///     default; there are no "must exist" assertions anywhere in this file.
///   - Records carry `t` (type) and `v` (record version) so a reader can
///     dispatch by type and migrate per record.
enum BackupFormat {
    /// Format major version. A reader that doesn't recognise it must refuse the
    /// package and tell the user to update (§2.2 rule 1) — never attempt a
    /// best-effort parse.
    static let current = "minisbak/1"

    /// File extension registered to the app for "open to import".
    static let fileExtension = "minisbak"

    /// Cap for a single JSONL shard (§2). Beyond this the writer rolls over to
    /// `messages-0002.jsonl` etc. Exists so the importer never has to hold one
    /// giant file in memory — the app already has a jetsam history from
    /// whole-file reads on large payloads.
    static let maxShardBytes = 64 * 1024 * 1024
}

// MARK: - Categories

/// User-facing backup categories (§3). The raw value is the manifest key and
/// the on-disk directory name, so renaming one is a format change.
enum BackupCategory: String, Codable, CaseIterable, Sendable {
    case chats
    case sharedFiles = "shared_files"
    case skills
    case memory
    case providers
    case mcpServers = "mcp_servers"
    case voiceCorrections = "voice_corrections"
    /// Shell environment variables (`Library/MinisChat/env-vars.json`).
    ///
    /// Their VALUES were already collected into secrets.json, but the metadata
    /// file — id, key, createdAt, note — never was. Restoring therefore wrote
    /// values into the Keychain with no entry pointing at them: invisible in
    /// the UI and absent from `allAsDict()`, so the agent could not use them
    /// either. iCloud sync covered this all along, which is why it went
    /// unnoticed by anyone who had sync switched on.
    case environmentVariables = "environment_variables"

    /// Default checkbox state on the backup screen (§3 table).
    var defaultsOn: Bool { true }

    /// Categories a NEW backup may include.
    ///
    /// [2026-08-15] Voice Corrections is excluded: the feature isn't mature
    /// enough to back up yet. Deliberately a filter on the export side rather
    /// than deleting the case — the enum value is part of the wire format, and
    /// packages already exist that contain a `voice_corrections` section.
    /// Removing it outright would make those sections undecodable and silently
    /// drop data the user already backed up. So: not written into new
    /// packages, still restored from old ones.
    static var backupable: [BackupCategory] {
        allCases.filter { $0 != .voiceCorrections }
    }

    /// Categories that stream file trees through the blob store, i.e. the ones
    /// the §3.4 size cap applies to.
    var carriesFileTree: Bool {
        switch self {
        case .chats, .sharedFiles, .skills: return true
        case .memory, .providers, .mcpServers, .voiceCorrections,
             .environmentVariables: return false
        }
    }
}

// MARK: - Manifest

/// `manifest.json` — ALWAYS plaintext, even in an encrypted package (§2.1), so
/// the user can see what a package holds before being asked for a passphrase.
struct BackupManifest: Codable {
    var format: String = BackupFormat.current
    var createdAt: Date
    /// Data cut-off for this package (review S7 / snapshot task): nothing
    /// modified after this instant is included. Optional so a package written
    /// by an older build still decodes — nil simply means "not recorded".
    var snapshotAt: Date?
    var app: AppInfo
    /// Display-only. Deliberately NOT the deviceId — that identifier is on the
    /// never-backup list (§3.1) because restoring it makes two devices fight
    /// over one CloudKit zone.
    var deviceName: String
    var backupId: String
    var categories: [String: CategoryStat]
    var limits: Limits
    /// Absent on unencrypted packages (stage 1 always omits it).
    var encryption: Encryption?
    /// Path → SHA-256 of the bytes as stored in the package. On an encrypted
    /// package that is the CIPHERTEXT hash, so integrity can be checked before
    /// the passphrase is known (§5.3).
    var integrity: [String: String]
    var manifestMac: String?

    /// [review S2] snake_case on the wire, matching the design doc's §2.1
    /// contract — that document is what the Android implementation will be
    /// written against, and §5.4 explicitly forbids inventing a second format.
    /// The shipped code was emitting camelCase, which would have made every
    /// package mutually unreadable across platforms. Changed now because the
    /// field names are sealed by `manifest_mac`: once a real user holds a
    /// package, renaming a key invalidates its MAC.
    enum CodingKeys: String, CodingKey {
        case format
        case createdAt = "created_at"
        case snapshotAt = "snapshot_at"
        case app
        case deviceName = "device_name"
        case backupId = "backup_id"
        case categories
        case limits
        case encryption
        case integrity
        case manifestMac = "manifest_mac"
    }

    /// [review S1] Hand-written so the tolerance §2.2 rule 2 promises is real.
    ///
    /// Swift's SYNTHESIZED Decodable ignores struct default values and throws
    /// `keyNotFound` for any missing non-optional key — verified. The previous
    /// code therefore rejected an entire package if a writer omitted, say,
    /// `limits.skipped_files`, while this file's own comment claimed missing
    /// fields got defaults. Only `format` is genuinely required; everything
    /// else falls back so an older or newer same-major writer still imports.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        format = try c.decodeIfPresent(String.self, forKey: .format) ?? BackupFormat.current
        createdAt = try c.decodeIfPresent(Date.self, forKey: .createdAt) ?? Date(timeIntervalSince1970: 0)
        snapshotAt = try c.decodeIfPresent(Date.self, forKey: .snapshotAt)
        app = try c.decodeIfPresent(AppInfo.self, forKey: .app)
            ?? AppInfo(platform: "unknown", version: "?", build: "?")
        deviceName = try c.decodeIfPresent(String.self, forKey: .deviceName) ?? "Unknown device"
        backupId = try c.decodeIfPresent(String.self, forKey: .backupId) ?? UUID().uuidString
        categories = try c.decodeIfPresent([String: CategoryStat].self, forKey: .categories) ?? [:]
        limits = try c.decodeIfPresent(Limits.self, forKey: .limits) ?? .unlimited
        encryption = try c.decodeIfPresent(Encryption.self, forKey: .encryption)
        integrity = try c.decodeIfPresent([String: String].self, forKey: .integrity) ?? [:]
        manifestMac = try c.decodeIfPresent(String.self, forKey: .manifestMac)
    }

    init(createdAt: Date, app: AppInfo, deviceName: String, backupId: String,
         categories: [String: CategoryStat], limits: Limits,
         encryption: Encryption?, integrity: [String: String], manifestMac: String?,
         snapshotAt: Date? = nil) {
        self.createdAt = createdAt
        self.snapshotAt = snapshotAt
        self.app = app
        self.deviceName = deviceName
        self.backupId = backupId
        self.categories = categories
        self.limits = limits
        self.encryption = encryption
        self.integrity = integrity
        self.manifestMac = manifestMac
    }

    struct AppInfo: Codable {
        var platform: String
        var version: String
        var build: String

        init(platform: String, version: String, build: String) {
            self.platform = platform; self.version = version; self.build = build
        }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            platform = try c.decodeIfPresent(String.self, forKey: .platform) ?? "unknown"
            version = try c.decodeIfPresent(String.self, forKey: .version) ?? "?"
            build = try c.decodeIfPresent(String.self, forKey: .build) ?? "?"
        }
    }

    /// Per-category counters shown in the picker before anything is decrypted.
    struct CategoryStat: Codable {
        var entries: Int
        var bytes: Int64
        var encrypted: Bool
        /// Chats only: split the single `entries` number so the UI can say
        /// "342 messages + 1204 files" (§2.1).
        var messages: Int?
        var files: Int?
        /// Providers only: user-authored thinking rules travelling in the same
        /// category, counted SEPARATELY rather than folded into `entries`.
        ///
        /// [T-backup-category-counts] `entries` is the count in the unit the
        /// user thinks in — provider instances here — so adding rules to it
        /// made "8 providers + 1 rule" render as "9 providers". Optional so a
        /// package written before this field existed still decodes; the UI
        /// simply omits the detail. Matches Android's `thinking_rules`
        /// (acab8c732), and a reader that does not know the key ignores it.
        var thinkingRules: Int?
        /// Providers only: false marks a "shared copy" with credentials
        /// stripped, so the importer can warn up front (§3.3).
        var includesCredentials: Bool?

        enum CodingKeys: String, CodingKey {
            case entries, bytes, encrypted, messages, files
            case thinkingRules = "thinking_rules"
            case includesCredentials = "includes_credentials"
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            entries = try c.decodeIfPresent(Int.self, forKey: .entries) ?? 0
            bytes = try c.decodeIfPresent(Int64.self, forKey: .bytes) ?? 0
            encrypted = try c.decodeIfPresent(Bool.self, forKey: .encrypted) ?? false
            messages = try c.decodeIfPresent(Int.self, forKey: .messages)
            files = try c.decodeIfPresent(Int.self, forKey: .files)
            thinkingRules = try c.decodeIfPresent(Int.self, forKey: .thinkingRules)
            includesCredentials = try c.decodeIfPresent(Bool.self, forKey: .includesCredentials)
        }

        init(entries: Int, bytes: Int64, encrypted: Bool,
             messages: Int? = nil, files: Int? = nil, thinkingRules: Int? = nil,
             includesCredentials: Bool? = nil) {
            self.entries = entries
            self.bytes = bytes
            self.encrypted = encrypted
            self.messages = messages
            self.files = files
            self.thinkingRules = thinkingRules
            self.includesCredentials = includesCredentials
        }
    }

    /// §3.4. `maxFileBytes == nil` means unlimited, which is the DEFAULT —
    /// unlike iCloud, which ships a finite cap. Rationale in the design doc:
    /// this is a user-initiated migration package, so silently dropping files
    /// would create exactly the "thought it was backed up, it wasn't" gap a
    /// backup must never have.
    struct Limits: Codable {
        var maxFileBytes: Int64?
        var skippedFiles: Int
        var skippedBytes: Int64

        enum CodingKeys: String, CodingKey {
            case maxFileBytes = "max_file_bytes"
            case skippedFiles = "skipped_files"
            case skippedBytes = "skipped_bytes"
        }

        init(maxFileBytes: Int64?, skippedFiles: Int, skippedBytes: Int64) {
            self.maxFileBytes = maxFileBytes
            self.skippedFiles = skippedFiles
            self.skippedBytes = skippedBytes
        }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            maxFileBytes = try c.decodeIfPresent(Int64.self, forKey: .maxFileBytes)
            skippedFiles = try c.decodeIfPresent(Int.self, forKey: .skippedFiles) ?? 0
            skippedBytes = try c.decodeIfPresent(Int64.self, forKey: .skippedBytes) ?? 0
        }

        static let unlimited = Limits(maxFileBytes: nil, skippedFiles: 0, skippedBytes: 0)
    }

    struct Encryption: Codable {
        var scheme: String
        var kdf: KDF
        var verifier: String

        struct KDF: Codable {
            var alg: String
            var mKib: Int?
            var t: Int?
            var p: Int?
            var iterations: Int?
            var salt: String

            enum CodingKeys: String, CodingKey {
                case alg, t, p, iterations, salt
                case mKib = "m_kib"
            }

            init(alg: String, mKib: Int?, t: Int?, p: Int?, iterations: Int?, salt: String) {
                self.alg = alg; self.mKib = mKib; self.t = t
                self.p = p; self.iterations = iterations; self.salt = salt
            }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                // `alg` and `salt` genuinely have no safe default — a wrong
                // guess would derive the wrong key and surface as "wrong
                // passphrase", so these stay required.
                alg = try c.decode(String.self, forKey: .alg)
                salt = try c.decode(String.self, forKey: .salt)
                mKib = try c.decodeIfPresent(Int.self, forKey: .mKib)
                t = try c.decodeIfPresent(Int.self, forKey: .t)
                p = try c.decodeIfPresent(Int.self, forKey: .p)
                iterations = try c.decodeIfPresent(Int.self, forKey: .iterations)
            }
        }
    }
}

// MARK: - File index

/// One line of `files.index.jsonl` — the directory-tree index (§2).
///
/// This exists ALONGSIDE `blobs.index.jsonl` rather than being folded into it:
/// blobs.index is a content map (sha256 → where it came from) and structurally
/// cannot express an empty directory, a path that was skipped, or the tree
/// shape itself. Those are exactly what a restore needs to rebuild `<sid>/`.
struct BackupFileIndexEntry: Codable {
    // Kept camelCase-free on the wire: these are already single lowercase
    // words except `isDirectory`, mapped below.
    /// Package-relative logical path, e.g. `chats/<sid>/offloads/out.zip`.
    var path: String
    /// Original byte size — recorded even for skipped entries so the restore
    /// UI can say how big the missing file was.
    var size: Int64
    /// nil for directories and for size-skipped tombstones.
    var sha256: String?
    var category: String
    /// Why the content is absent from the package. Present ONLY on tombstones;
    /// a normal entry omits it entirely.
    ///
    /// - `"size"` — §3.4's cap excluded it.
    /// - `"not_downloaded"` — an iCloud/FileProvider placeholder that could not
    ///   be materialised (review S9).
    /// - `"unreadable"` — the file could not be read at all.
    var skipped: String?
    var isDirectory: Bool?

    static func file(path: String, size: Int64, sha256: String, category: BackupCategory) -> Self {
        .init(path: path, size: size, sha256: sha256, category: category.rawValue,
              skipped: nil, isDirectory: nil)
    }

    /// A file the size cap excluded. Carries no sha256 (the bytes aren't in the
    /// package) but keeps the path and true size so the gap stays visible —
    /// the whole point of §3.4's "tombstone, don't silently drop".
    static func sizeSkipped(path: String, size: Int64, category: BackupCategory) -> Self {
        .init(path: path, size: size, sha256: nil, category: category.rawValue,
              skipped: "size", isDirectory: nil)
    }

    /// [review S9] A file that lives in iCloud (or another FileProvider) and is
    /// not downloaded to this device, which the export could not materialise in
    /// time.
    ///
    /// This MUST be a tombstone rather than a normal entry. A placeholder reads
    /// back as `size == 0` with no error, so the previous code packaged it as a
    /// genuinely empty file — and since every empty file hashes to the same
    /// digest, an entire folder of undownloaded documents deduped down to one
    /// shared empty blob. Restoring then overwrote the user's real files with
    /// 0 bytes, and the report called it a success. Silent data loss, reported
    /// as done.
    ///
    /// `size` is the true logical size reported by the provider, so the restore
    /// UI can still say how big the missing file was.
    static func notDownloaded(path: String, size: Int64, category: BackupCategory) -> Self {
        .init(path: path, size: size, sha256: nil, category: category.rawValue,
              skipped: "not_downloaded", isDirectory: nil)
    }

    /// Empty directories would otherwise vanish, since nothing references them.
    static func directory(path: String, category: BackupCategory) -> Self {
        .init(path: path, size: 0, sha256: nil, category: category.rawValue,
              skipped: nil, isDirectory: true)
    }
}

/// One line of `blobs.index.jsonl` — content-addressed payload map (§2).
struct BackupBlobIndexEntry: Codable {
    var sha256: String
    var size: Int64
    /// First logical path this content was seen at. Later duplicates reuse the
    /// same blob and only add a `files.index` line, so the bytes are stored once.
    var path: String
    var sessionId: String?
    var mime: String?
}

// MARK: - JSONL records

/// Envelope for every `data/*.jsonl` line (§2.2 rule 3): `t` dispatches, `v`
/// allows per-record migration on the way in.
struct BackupRecordEnvelope<Payload: Codable>: Codable {
    var t: String
    var v: Int
    var d: Payload

    init(t: String, v: Int = 1, d: Payload) {
        self.t = t
        self.v = v
        self.d = d
    }
}

/// [T-backup-thinking-rules] One user-authored thinking rule, as carried in
/// `data/thinking_rules.jsonl`.
///
/// Field-for-field the same shape as `SyncedProviderThinkingRuleV3`
/// (Agent/Sync/V2/SyncedTypes.swift) on purpose: that record already crosses
/// devices through iCloud and its layout is proven, so mirroring it keeps one
/// definition of what a portable rule is instead of inventing a second.
///
/// `wireFormatJson` is carried OPAQUELY — it is the serialized
/// `ThinkingWireFormat` (including `extraBodyToggle` custom request-body
/// params), and the DDL deliberately stores it as one JSON column so a new
/// wire-format case needs no migration. The backup must not try to interpret
/// it either.
///
/// Built-in rules are never written here: they ship with the app, and
/// restoring an old definition onto a newer build would resurrect or duplicate
/// a rule the app already defines. The exporter reads
/// `allCustomThinkingRuleIds()`, whose SQL is `WHERE is_builtin = 0`.
struct BackupThinkingRuleRecord: Codable {
    var id: String
    var instanceId: String
    var sortOrder: Int
    var scopeKind: String
    var scopePattern: String?
    var wireFormatJson: String
    var echoField: String?
    var echoTiming: String?
    var label: String
    var createdAt: Date
    var updatedAt: Date
}

// MARK: - Errors

enum BackupError: LocalizedError {
    case stagingFailed(String)
    case writeFailed(String, underlying: Error?)
    case archiveFailed(Error?)
    case cancelled

    var errorDescription: String? {
        switch self {
        case .stagingFailed(let m): return "Backup staging failed: \(m)"
        case .writeFailed(let m, let e):
            return "Backup write failed: \(m)" + (e.map { " (\($0.localizedDescription))" } ?? "")
        case .archiveFailed(let e):
            return "Backup archive failed" + (e.map { ": \($0.localizedDescription)" } ?? "")
        case .cancelled: return "Backup cancelled"
        }
    }
}
