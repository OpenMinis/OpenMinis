import Foundation

private let logger = AppLogger(category: "UploadPolicy")

/// Per-device user preference: which v2 record types this device is
/// allowed to push to iCloud, and the per-file size cap on SessionFile
/// asset content.
///
/// Settings live in UserDefaults so they survive launches and can be
/// flipped from the Settings sheet without restarting the engine.
/// Defaults are permissive — fresh installs sync everything until the
/// user opts out.
///
/// Filtering happens at `ChatStore.markDirty` so disabled types never
/// even enter the dirty queue. Records that are already on iCloud from
/// a prior policy are NOT pulled back; turning a category off only
/// stops new writes.
enum UploadPolicy {

    enum Category: String, CaseIterable {
        case chatSessions          // Session, Message, CompactMarker
        case sessionFiles          // SessionFile (binary attachments)
        case skills                // Skill (and SkillFile if any)
        case providers             // ProviderConfig
        case envVars               // EnvVar
        case memory                // MemoryGlobalV2 + MemoryDailyV2

        /// SQLite record_type values that fall under this category.
        var recordTypes: Set<String> {
            switch self {
            case .chatSessions:
                return ["Session", "SessionV2", "Message", "MessageV2", "CompactMarker", "CompactMarkerV2",
                        // Folders organize sessions; the chat-sessions toggle governs them.
                        "Folder", "FolderV2"]
            case .sessionFiles:
                return ["SessionFile", "SessionFileV2"]
            case .skills:
                return ["Skill", "SkillV2"]
            case .providers:
                // [T-icloud-uploadpolicy-v3-gap] The three V3 types MUST be
                // listed here. They are the AUTHORITATIVE provider sync path
                // (v2 is dropped on inbound), and they were wired into the zone
                // map, the whitelist and the fetch list — but not into this
                // policy. Combined with the permissive `return true` fallthrough
                // in `allowsRecordType`, that meant turning `sync.providers` OFF
                // still uploaded every provider record: the user's switch did
                // nothing. (OpenMinis#98, found while investigating that issue.)
                return ["ProviderConfig", "ProviderConfigV2",
                        "ProviderInstanceV3", "ProviderModelEntryV3", "ProviderModelGroupV3",
                        "ProviderThinkingRuleV3",
                        // [T-subagent-own-store] Sub agent definitions no longer
                        // live in ProviderConfig, but they stay under the
                        // PROVIDERS toggle rather than gaining a switch of their
                        // own: a definition's only external reference is a model
                        // group, the Sub Agents screen sits in the same settings
                        // area, and inventing a seventh user-facing sync switch
                        // for one small roster is a UX decision this refactor
                        // should not make unilaterally. Listing it here is what
                        // keeps `sync.providers = off` from silently uploading
                        // it anyway — the exact gap [T-icloud-uploadpolicy-v3-gap]
                        // records for the V3 types.
                        "SubAgentV3"]
            case .envVars:
                return ["EnvVar", "EnvVarV2"]
            case .memory:
                return ["MemoryGlobalV2", "MemoryDailyV2"]
            }
        }

        var defaultsKey: String { "cloudSync.v2.upload.\(rawValue)" }

        /// Display name for the Settings UI.
        var displayName: String {
            switch self {
            case .chatSessions: return "Chat Sessions"
            case .sessionFiles: return "Session Files"
            case .skills:       return "Skills"
            case .providers:    return "Providers"
            case .envVars:      return "Environments"
            case .memory:       return "Memory Files"
            }
        }
    }

    /// Read whether the user has opted IN to syncing a category.
    /// Defaults to true on first read (permissive).
    static func isEnabled(_ cat: Category) -> Bool {
        if UserDefaults.standard.object(forKey: cat.defaultsKey) == nil {
            UserDefaults.standard.set(true, forKey: cat.defaultsKey)
            return true
        }
        return UserDefaults.standard.bool(forKey: cat.defaultsKey)
    }

    static func setEnabled(_ cat: Category, _ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: cat.defaultsKey)
    }

    /// Quick lookup: should `markDirty(recordType:)` be allowed through?
    /// SyncDeviceV2 is always allowed (device discovery itself can't be
    /// disabled — without it the device list would never populate).
    static func allowsRecordType(_ recordType: String) -> Bool {
        if recordType == "SyncDevice" || recordType == "SyncDeviceV2" {
            return true
        }
        for cat in Category.allCases where cat.recordTypes.contains(recordType) {
            return isEnabled(cat)
        }
        // Unknown types still pass through for forward-compat, but LOUDLY:
        // silence here is what let the V3 provider types bypass the providers
        // toggle unnoticed. A new record type that forgets its category now
        // leaves a trace in the log instead of quietly ignoring the user's
        // switch. [T-icloud-uploadpolicy-v3-gap]
        logger.warning("[UploadPolicy] recordType '\(recordType)' matches NO category — allowing by default; add it to UploadPolicy.Category.recordTypes")
        return true
    }

    // MARK: - Per-file cap

    /// Max per-file size in bytes that this device is willing to push
    /// for SessionFile asset content. Files above the cap are skipped
    /// at markDirty time. UserDefaults default = 1 MB.
    static let maxFileSizeKey = "cloudSync.v2.upload.maxFileSize"
    static var maxFileSizeBytes: Int {
        get {
            let v = UserDefaults.standard.integer(forKey: maxFileSizeKey)
            return v > 0 ? v : (1 * 1024 * 1024)
        }
        set { UserDefaults.standard.set(newValue, forKey: maxFileSizeKey) }
    }

    /// Comma-joined string of category record-types currently enabled,
    /// used in SyncedDevice.uploadTypes so peers can see what we sync.
    static func currentUploadTypesString() -> String {
        Category.allCases
            .filter { isEnabled($0) }
            .map { $0.rawValue }
            .joined(separator: ",")
    }

    // MARK: - Custom device name

    static let deviceNameKey = "cloudSync.v2.deviceName"
    static var customDeviceName: String? {
        get { UserDefaults.standard.string(forKey: deviceNameKey) }
        set {
            if let s = newValue, !s.isEmpty {
                UserDefaults.standard.set(s, forKey: deviceNameKey)
            } else {
                UserDefaults.standard.removeObject(forKey: deviceNameKey)
            }
        }
    }
}
