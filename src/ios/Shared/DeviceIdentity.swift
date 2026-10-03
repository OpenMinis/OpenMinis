import Foundation
import Security
import UIKit

private let logger = AppLogger(category: "DeviceIdentity")

/// Stable device identity persisted in Keychain (survives app reinstall).
/// Used for per-device CKRecordZone naming in iCloud sync.
enum DeviceIdentity {
    private static let keychainService = "com.openminis.app.device"
    private static let keychainAccount = "deviceId"
    /// [T-icloud-device-retire-old-id] Last id this install used (UserDefaults
    /// outlives the ThisDeviceOnly keychain item across reinstalls).
    private static let previousIdKey = "deviceIdentity.previousId"
    /// An id that was replaced and still needs its cloud record retired.
    /// Consumed once by SyncV2Bootstrap.
    private static let retiredIdKey = "deviceIdentity.retiredId"
    static func takeRetiredDeviceId() -> String? {
        let v = UserDefaults.standard.string(forKey: retiredIdKey)
        if v != nil { UserDefaults.standard.removeObject(forKey: retiredIdKey) }
        return v
    }

    /// Stable UUID for this device, persisted in Keychain.
    ///
    /// [T-ios-deviceid-blank-ckrecordid-crash] The Keychain value is VALIDATED, not
    /// merely unwrapped. `readKeychain()` returns any UTF-8-decodable payload, so a
    /// zero-length or whitespace-only item decodes to `Optional("")` — non-nil, so
    /// the old `if let` accepted it and skipped UUID generation entirely.
    ///
    /// That empty id then flowed straight into
    /// `CKRecord.ID(recordName: DeviceIdentity.deviceId, …)` in
    /// `CloudSyncEngine.queueDeviceRecord()`, which is the ONE CKRecord.ID
    /// construction site with neither a pre-check nor an ObjC try/catch. CloudKit
    /// raises NSInvalidArgumentException on an empty recordName, and an ObjC
    /// exception crossing Swift frames is uncatchable — it reaches objc_terminate
    /// and aborts. Field signature: SIGABRT on the main thread right after
    /// foregrounding (the 180s foreground sync timer and the CloudKit
    /// accountChange handler both call queueDeviceRecord from a @MainActor Task),
    /// and it stopped when the user turned iCloud sync off.
    ///
    /// Trimming before the emptiness test matters: a stray newline written by an
    /// older build (or a partially-restored Keychain item) yields a name that is
    /// non-empty but still illegal, so " \n" must regenerate too rather than be
    /// stored as an id.
    /// [T-ios-reboot-keychain-identity-rotation] MEMOIZED, not a `static let`.
    ///
    /// The old `static let` conflated "the Keychain has no id" with "the Keychain
    /// could not be read right now", and those have opposite correct responses.
    /// After a reboot iOS can relaunch this app in the background BEFORE first
    /// unlock (BGTask / CloudKit push / location session). The item is stored
    /// `AfterFirstUnlockThisDeviceOnly`, so in that window `SecItemCopyMatching`
    /// returns `errSecInteractionNotAllowed` (-25308) rather than
    /// `errSecItemNotFound`. The old code read that as "nothing stored", minted a
    /// fresh UUID, and — because `writeKeychain` deletes before adding —
    /// OVERWROTE the real identity. A `static let` then froze the wrong value for
    /// the whole process lifetime.
    ///
    /// The consequence is not cosmetic: `zoneName` is `"device-\(deviceId)"`, so a
    /// rotated id points the engine at a brand-new, empty CKRecordZone. The
    /// device's real zone is orphaned, `queueDeviceRecord` registers a duplicate
    /// device, and every `$0.id != DeviceIdentity.deviceId` filter in
    /// CloudSyncEngine starts treating this device's own records as a peer's.
    ///
    /// So: only mint-and-persist when the Keychain positively reports the item is
    /// absent. When it is merely unreadable, return an ephemeral id WITHOUT
    /// writing it, and do not memoize — the next call after unlock re-reads and
    /// gets the real one. Callers that must not act on a provisional identity can
    /// check `isProvisional`.
    static var deviceId: String {
        lock.lock()
        if let cached = _cachedDeviceId {
            lock.unlock()
            return cached
        }
        lock.unlock()

        let outcome = readKeychainDetailed()
        switch outcome {
        case .found(let raw):
            UserDefaults.standard.set(raw.trimmingCharacters(in: .whitespacesAndNewlines), forKey: previousIdKey)
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                lock.lock(); _cachedDeviceId = trimmed; lock.unlock()
                return trimmed
            }
            // [T-ios-deviceid-blank-ckrecordid-crash] Stored but blank/corrupt: a
            // zero-length or whitespace-only payload decodes to a non-nil String,
            // so it used to pass an `if let` and reach
            // `CKRecord.ID(recordName:)`, which raises an uncatchable ObjC
            // NSInvalidArgumentException on an empty name. Regenerate; trimming
            // before the test is what makes a stray "\n" from an older build
            // regenerate too instead of being stored as an id.
            fallthrough
        case .absent:
            let newId = UUID().uuidString
            writeKeychain(newId)
            lock.lock(); _cachedDeviceId = newId; lock.unlock()
            // [T-icloud-device-retire-old-id] The keychain item is ThisDeviceOnly
            // and dies with an uninstall, so a reinstall lands here and mints a
            // fresh id — leaving the OLD SyncDeviceV2 record on every peer as a
            // permanent ghost (nothing ever deleted it). Remember ids in
            // UserDefaults, which survives what the keychain item does not, so
            // SyncV2Bootstrap can tombstone + op=delete the previous one.
            let prev = UserDefaults.standard.string(forKey: previousIdKey)
            if let prev, prev != newId, !prev.hasPrefix("provisional-") {
                UserDefaults.standard.set(prev, forKey: retiredIdKey)
                logger.info("[DeviceIdentity] minted new deviceId; previous \(prev.prefix(8)) queued for retirement")
            } else {
                logger.info("[DeviceIdentity] minted new deviceId (keychain reported the item absent)")
            }
            UserDefaults.standard.set(newId, forKey: previousIdKey)
            return newId
        case .unreadable(let status):
            // Locked device. Do NOT write, do NOT memoize — writing would destroy
            // the real id, and memoizing would pin this process to the wrong one
            // even after the user unlocks.
            let ephemeral = provisionalId
            logger.error(
                "[DeviceIdentity] keychain unreadable (OSStatus=\(status)) — device is likely "
                + "locked after reboot. Using a PROVISIONAL id for this call; the real id will "
                + "be read once protected data is available. Nothing was written."
            )
            return ephemeral
        }
    }

    /// True when `deviceId` would answer with a provisional value because the
    /// Keychain cannot be read right now. Sync/zone work must not treat a
    /// provisional identity as this device's real one.
    ///
    /// Deliberately RESOLVES the id rather than just inspecting the cache: before
    /// the first read the cache is empty, so a bare `_cachedDeviceId == nil` test
    /// would report "provisional" on a perfectly healthy launch and block sync
    /// forever. Resolving first is also free — the value is memoized on success,
    /// so the caller's own `deviceId` read right after this is a cache hit.
    static var isProvisional: Bool {
        _ = deviceId
        lock.lock(); defer { lock.unlock() }
        return _cachedDeviceId == nil
    }

    /// Stable for the lifetime of the process so repeated calls during a locked
    /// window at least agree with each other, but never persisted and never
    /// memoized into `_cachedDeviceId`.
    private static let provisionalId = "provisional-\(UUID().uuidString)"

    private static let lock = NSLock()
    private static var _cachedDeviceId: String?

    /// Human-readable device name with short ID suffix for disambiguation.
    ///
    /// Prefers a name the user typed in Settings ([T-backup-device-name-setting]),
    /// then the name iOS reports (which is only personalized when the privacy
    /// entitlement is present), then the hardware model (e.g. "iPhone 16 Pro").
    /// Always appends a 4-char ID suffix (e.g. "· A3F2") so two devices that
    /// resolve to the same words are still distinguishable in the sync list.
    static var deviceName: String {
        let shortId = String(deviceId.suffix(4)).uppercased()
        return "\(displayName) · \(shortId)"
    }

    // MARK: - User-set device name

    /// [T-backup-device-name-setting] A name the user typed for this device,
    /// or nil when they have not set one.
    ///
    /// Exists because `UIDevice.current.name` is not the name the user sees on
    /// their own device: since iOS 16 it returns the MODEL ("iPhone") unless
    /// the app holds the user-assigned-device-name entitlement. So the
    /// automatic identity cannot tell two iPhones apart, which is precisely
    /// what backup filenames and the sync device list need it to do. Letting
    /// the user type a name is the fix that needs no entitlement.
    ///
    /// Stored in UserDefaults rather than the Keychain alongside `deviceId`
    /// on purpose: the id must survive reinstall because zone names are keyed
    /// on it, whereas a display name is a preference — resurrecting a name the
    /// user set on a since-deleted install would be surprising, not helpful.
    ///
    /// Setting it to nil, empty, or whitespace clears the override and returns
    /// to the automatic name. Values are trimmed and length-capped on the way
    /// in so a stray paste cannot store something unusable.
    static var customName: String? {
        get {
            let raw = UserDefaults.standard.string(forKey: customNameKey)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return (raw?.isEmpty == false) ? raw : nil
        }
        set {
            let trimmed = newValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !trimmed.isEmpty else {
                UserDefaults.standard.removeObject(forKey: customNameKey)
                return
            }
            UserDefaults.standard.set(String(trimmed.prefix(customNameMaxLength)),
                                      forKey: customNameKey)
        }
    }

    static let customNameKey = "device.customName"
    /// Generous enough for "Alex's Work iPhone 17 Pro", short enough that it
    /// cannot dominate a backup filename or a row in the sync device list.
    static let customNameMaxLength = 48

    /// The automatic name, i.e. what `displayName` falls back to when the user
    /// has set nothing. Kept separate so the settings field can show it as its
    /// placeholder — the user sees the default they are about to override.
    static var automaticName: String {
        let userName = UIDevice.current.name
        let genericNames: Set<String> = ["iPhone", "iPad", "iPod touch", "Mac", "Apple Watch"]
        return genericNames.contains(userName) ? modelName : userName
    }

    /// The name to show for this device anywhere in the UI: the user's own if
    /// they set one, otherwise the automatic name. No id suffix — see
    /// `deviceName` for the disambiguated form used by sync.
    static var displayName: String {
        customName ?? automaticName
    }

    /// Hardware model name (e.g. "iPhone 16 Pro", "iPad Pro 13\" (M4)", "MacBook Pro").
    static var modelName: String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let machine = withUnsafePointer(to: &systemInfo.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: 1) {
                String(validatingUTF8: $0) ?? "Unknown"
            }
        }
        return modelNameMap(for: machine)
    }

    private static func modelNameMap(for identifier: String) -> String {
        let map: [String: String] = [
            // iPhone 17 (2025) & iPhone 17e (2026)
            "iPhone18,1": "iPhone 17 Pro", "iPhone18,2": "iPhone 17 Pro Max",
            "iPhone18,3": "iPhone 17", "iPhone18,4": "iPhone Air",
            "iPhone18,5": "iPhone 17e",
            // iPhone 16 (2024)
            "iPhone17,1": "iPhone 16 Pro", "iPhone17,2": "iPhone 16 Pro Max",
            "iPhone17,3": "iPhone 16", "iPhone17,4": "iPhone 16 Plus",
            "iPhone17,5": "iPhone 16e",
            // iPhone 15 (2023)
            "iPhone16,1": "iPhone 15 Pro", "iPhone16,2": "iPhone 15 Pro Max",
            "iPhone15,4": "iPhone 15", "iPhone15,5": "iPhone 15 Plus",
            // iPhone 14 (2022)
            "iPhone15,2": "iPhone 14 Pro", "iPhone15,3": "iPhone 14 Pro Max",
            "iPhone14,7": "iPhone 14", "iPhone14,8": "iPhone 14 Plus",
            // iPhone 13 (2021)
            "iPhone14,2": "iPhone 13 Pro", "iPhone14,3": "iPhone 13 Pro Max",
            "iPhone14,4": "iPhone 13 mini", "iPhone14,5": "iPhone 13",
            // iPhone SE
            "iPhone12,8": "iPhone SE (2nd generation)",
            "iPhone14,6": "iPhone SE (3rd generation)",
            // iPad Pro M4 (2024)
            "iPad16,3": "iPad Pro 11\" (M4)", "iPad16,4": "iPad Pro 11\" (M4)",
            "iPad16,5": "iPad Pro 13\" (M4)", "iPad16,6": "iPad Pro 13\" (M4)",
            // iPad Pro M2 (2022)
            "iPad14,3": "iPad Pro 11\" (M2)", "iPad14,4": "iPad Pro 11\" (M2)",
            "iPad14,5": "iPad Pro 12.9\" (M2)", "iPad14,6": "iPad Pro 12.9\" (M2)",
            // iPad Pro M1 (2021)
            "iPad13,4": "iPad Pro 11\" (M1)", "iPad13,5": "iPad Pro 11\" (M1)",
            "iPad13,6": "iPad Pro 11\" (M1)", "iPad13,7": "iPad Pro 11\" (M1)",
            "iPad13,8": "iPad Pro 12.9\" (M1)", "iPad13,9": "iPad Pro 12.9\" (M1)",
            "iPad13,10": "iPad Pro 12.9\" (M1)", "iPad13,11": "iPad Pro 12.9\" (M1)",
            // iPad Air M3 (2025)
            "iPad15,3": "iPad Air 11\" (M3)", "iPad15,4": "iPad Air 11\" (M3)",
            "iPad15,5": "iPad Air 13\" (M3)", "iPad15,6": "iPad Air 13\" (M3)",
            // iPad Air M2 (2024)
            "iPad14,8": "iPad Air 11\" (M2)", "iPad14,9": "iPad Air 11\" (M2)",
            "iPad14,10": "iPad Air 13\" (M2)", "iPad14,11": "iPad Air 13\" (M2)",
            // iPad Air M1 (2022)
            "iPad13,16": "iPad Air (M1)", "iPad13,17": "iPad Air (M1)",
            // iPad mini
            "iPad14,1": "iPad mini (6th generation)",
            "iPad14,2": "iPad mini (6th generation)",
            "iPad16,1": "iPad mini (A17 Pro)", "iPad16,2": "iPad mini (A17 Pro)",
            // iPad (10th generation, 2022)
            "iPad13,18": "iPad (10th generation)",
            "iPad13,19": "iPad (10th generation)",
            // iPad (11th generation, 2025)
            "iPad15,7": "iPad (11th generation)",
            "iPad15,8": "iPad (11th generation)",
            // Mac (Catalyst) — uname returns "arm64" or "x86_64"
            "arm64": "Mac", "x86_64": "Mac",
        ]
        if let name = map[identifier] { return name }
        if identifier.hasPrefix("iPhone") { return "iPhone" }
        if identifier.hasPrefix("iPad") { return "iPad" }
        // Catalyst/Mac fallback: try to get Mac model from sysctl
        #if targetEnvironment(macCatalyst)
        return macModelName() ?? "Mac"
        #else
        return UIDevice.current.model
        #endif
    }

    #if targetEnvironment(macCatalyst)
    private static func macModelName() -> String? {
        var size = 0
        sysctlbyname("hw.model", nil, &size, nil, 0)
        guard size > 0 else { return nil }
        var model = [CChar](repeating: 0, count: size)
        sysctlbyname("hw.model", &model, &size, nil, 0)
        let hwModel = String(cString: model) // e.g. "Mac14,6"
        let macMap: [String: String] = [
            "Mac14,6": "MacBook Pro 16\" (M2 Pro)",
            "Mac14,10": "MacBook Pro 16\" (M2 Max)",
            "Mac14,5": "MacBook Pro 14\" (M2 Pro)",
            "Mac14,9": "MacBook Pro 14\" (M2 Max)",
            "Mac15,3": "MacBook Pro 14\" (M3)",
            "Mac15,6": "MacBook Pro 14\" (M3 Pro)",
            "Mac15,7": "MacBook Pro 14\" (M3 Pro)",
            "Mac15,8": "MacBook Pro 14\" (M3 Max)",
            "Mac15,9": "MacBook Pro 16\" (M3 Pro)",
            "Mac15,10": "MacBook Pro 16\" (M3 Pro)",
            "Mac15,11": "MacBook Pro 16\" (M3 Max)",
            "Mac16,1": "MacBook Pro 14\" (M4)",
            "Mac16,5": "MacBook Pro 14\" (M4 Pro)",
            "Mac16,6": "MacBook Pro 14\" (M4 Pro)",
            "Mac16,7": "MacBook Pro 16\" (M4 Pro)",
            "Mac16,8": "MacBook Pro 14\" (M4 Max)",
            "Mac16,10": "MacBook Pro 16\" (M4 Max)",
            "Mac15,12": "MacBook Air 13\" (M3)",
            "Mac15,13": "MacBook Air 15\" (M3)",
            "Mac16,12": "MacBook Air 13\" (M4)",
            "Mac16,13": "MacBook Air 15\" (M4)",
            "Mac14,2": "MacBook Air 13\" (M2)",
            "Mac14,15": "MacBook Air 15\" (M2)",
            "Mac15,4": "iMac 24\" (M3)",
            "Mac15,5": "iMac 24\" (M3)",
            "Mac16,2": "iMac 24\" (M4)",
            "Mac16,3": "Mac mini (M4)",
            "Mac16,4": "Mac mini (M4 Pro)",
            "Mac14,12": "Mac mini (M2)",
            "Mac14,13": "Mac mini (M2 Pro)",
            "Mac14,14": "Mac Pro (M2 Ultra)",
            "Mac14,8": "Mac Studio (M2 Max)",
        ]
        return macMap[hwModel] ?? (hwModel.hasPrefix("Mac") ? "Mac" : nil)
    }
    #endif

    /// CKRecordZone name for this device.
    static var zoneName: String {
        "device-\(deviceId)"
    }

    /// OS version string (e.g. "18.3.2").
    static var osVersion: String {
        UIDevice.current.systemVersion
    }

    // MARK: - Keychain Helpers

    /// Why a Keychain read did not produce a value. The distinction is the whole
    /// point of this type: `.absent` means "mint one", `.unreadable` means
    /// "come back later and touch nothing".
    private enum KeychainOutcome {
        case found(String)
        /// The item genuinely is not there (`errSecItemNotFound`), or is there but
        /// undecodable — either way, regenerating is correct.
        case absent
        /// Present-or-not is unknown because the Keychain refused to answer.
        case unreadable(OSStatus)
    }

    private static func readKeychainDetailed() -> KeychainOutcome {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            guard let data = result as? Data,
                  let s = String(data: data, encoding: .utf8) else {
                // Item exists but its payload is not UTF-8: unrecoverable as an
                // id, so treat it the way a blank value is treated.
                return .absent
            }
            return .found(s)
        case errSecItemNotFound:
            return .absent
        default:
            // errSecInteractionNotAllowed (-25308) is the reboot-before-unlock
            // case this exists for; errSecNotAvailable and any other unexpected
            // status get the same conservative treatment, because none of them
            // is evidence that the item is gone.
            return .unreadable(status)
        }
    }

    @discardableResult
    private static func writeKeychain(_ value: String) -> Bool {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        // Delete any existing entry first
        SecItemDelete(query as CFDictionary)
        let status = SecItemAdd(query as CFDictionary, nil)
        if status != errSecSuccess {
            // Callers only reach here on the `.absent` branch, so the id they are
            // holding is not persisted and the NEXT launch will mint a different
            // one. Worth a loud line: it is the only trace of an identity that
            // silently failed to stick.
            logger.error("[DeviceIdentity] failed to persist deviceId (OSStatus=\(status)); id will not survive relaunch")
            return false
        }
        return true
    }
}
