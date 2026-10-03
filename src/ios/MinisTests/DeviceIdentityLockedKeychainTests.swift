import XCTest
@testable import Minis

/// [T-ios-reboot-keychain-identity-rotation] The device identity must survive a
/// launch that happens while the Keychain is locked.
///
/// WHY THIS MATTERS
/// ----------------
/// After a reboot iOS can relaunch the app in the background BEFORE first unlock
/// (BGTask, CloudKit push, a location session). `DeviceIdentity`'s item is stored
/// `AfterFirstUnlockThisDeviceOnly`, so `SecItemCopyMatching` answers
/// `errSecInteractionNotAllowed` (-25308) in that window — NOT
/// `errSecItemNotFound`.
///
/// The original code could not tell those apart: any non-success status became
/// `nil`, `nil` meant "nothing stored", and "nothing stored" meant *mint a fresh
/// UUID and write it*. Since the writer deletes before adding, that OVERWROTE the
/// real identity, and because the property was a `static let` the wrong value was
/// then frozen for the whole process.
///
/// `zoneName` is `"device-\(deviceId)"`, so the damage is not cosmetic: a rotated
/// id points the sync engine at a brand-new empty CKRecordZone, orphans the real
/// one, registers a duplicate SyncDevice, and flips every
/// `$0.id != DeviceIdentity.deviceId` ownership test in CloudSyncEngine.
///
/// WHAT IS ACTUALLY TESTED
/// -----------------------
/// `DeviceIdentity` talks to the real Keychain through free functions and caches
/// process-wide, so it cannot be driven into the locked state from a unit test —
/// there is no way to make the Security framework return -25308 on demand, and
/// the memoized value would leak between cases. What IS testable, and what
/// actually broke, is the DECISION TABLE: which Keychain outcome may mint, which
/// may write, and which may be cached. `Resolver` below is that table, extracted
/// so the test drives it directly; `DeviceIdentity.deviceId` implements the same
/// three cases in the same order.
///
/// A test that could only assert "a real device returns some id" would have
/// passed against the broken code too.
final class DeviceIdentityLockedKeychainTests: XCTestCase {

    /// The three answers a Keychain read can give, and the only three the caller
    /// needs to distinguish.
    enum Outcome: Equatable {
        case found(String)
        /// Confirmed not present, or present but undecodable — minting is correct.
        case absent
        /// Presence unknown: the Keychain refused to answer. Never evidence of
        /// absence, so it must never trigger a write.
        case unreadable(OSStatus)
    }

    /// Mirrors `DeviceIdentity`'s resolution, with the Keychain replaced by a
    /// scriptable stub so the locked branch is reachable.
    final class Resolver {
        var stored: String?
        var status: OSStatus
        private(set) var writes: [String] = []
        private(set) var cached: String?

        init(stored: String?, status: OSStatus = errSecSuccess) {
            self.stored = stored
            self.status = status
        }

        private func read() -> Outcome {
            switch status {
            case errSecSuccess:
                guard let s = stored else { return .absent }
                return .found(s)
            case errSecItemNotFound:
                return .absent
            default:
                return .unreadable(status)
            }
        }

        var deviceId: String {
            if let cached { return cached }
            switch read() {
            case .found(let raw):
                let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty {
                    cached = trimmed
                    return trimmed
                }
                fallthrough
            case .absent:
                let minted = "minted-\(writes.count)"
                writes.append(minted)
                stored = minted
                cached = minted
                return minted
            case .unreadable:
                // No write, no memoization — the next call re-reads.
                return "provisional-stub"
            }
        }

        var isProvisional: Bool {
            _ = deviceId
            return cached == nil
        }
    }

    // MARK: - The regression

    func testLockedKeychainNeitherRotatesNorPersists() {
        let r = Resolver(stored: "REAL-DEVICE-ID", status: errSecInteractionNotAllowed)

        let id = r.deviceId

        XCTAssertTrue(id.hasPrefix("provisional-"), "a locked read must not answer with a real-looking id")
        XCTAssertTrue(r.writes.isEmpty, "THE bug: a locked read must never write — writing destroys the stored identity")
        XCTAssertEqual(r.stored, "REAL-DEVICE-ID", "the stored identity must be left exactly as it was")
        XCTAssertTrue(r.isProvisional, "callers need to be able to tell this id is not authoritative")
        XCTAssertNil(r.cached, "memoizing here would pin the process to the wrong id even after unlock")
    }

    func testIdentityRecoversOnceProtectedDataBecomesAvailable() {
        let r = Resolver(stored: "REAL-DEVICE-ID", status: errSecInteractionNotAllowed)
        XCTAssertTrue(r.deviceId.hasPrefix("provisional-"))

        // Device unlocks; the very next read must produce the real id, with the
        // Keychain still untouched.
        r.status = errSecSuccess

        XCTAssertEqual(r.deviceId, "REAL-DEVICE-ID")
        XCTAssertTrue(r.writes.isEmpty)
        XCTAssertFalse(r.isProvisional)
    }

    /// errSecNotAvailable and any other unexpected status get the same treatment:
    /// none of them is evidence the item is gone.
    func testOtherFailureStatusesAreTreatedAsUnreadableNotAbsent() {
        for status in [errSecNotAvailable, errSecAuthFailed, OSStatus(-99999)] {
            let r = Resolver(stored: "REAL-DEVICE-ID", status: status)
            _ = r.deviceId
            XCTAssertTrue(r.writes.isEmpty, "status \(status) must not trigger a write")
            XCTAssertEqual(r.stored, "REAL-DEVICE-ID", "status \(status) must not rotate the identity")
        }
    }

    // MARK: - Behaviour that must NOT regress

    func testGenuinelyAbsentStillMintsAndPersists() {
        let r = Resolver(stored: nil, status: errSecItemNotFound)

        let id = r.deviceId

        XCTAssertTrue(id.hasPrefix("minted-"), "a confirmed-absent item is exactly when minting is right")
        XCTAssertEqual(r.writes.count, 1, "the new id must be persisted so it survives relaunch")
        XCTAssertEqual(r.cached, id, "and memoized, since it is now authoritative")
    }

    /// [T-ios-deviceid-blank-ckrecordid-crash] A stored-but-blank value must still
    /// regenerate. An empty recordName makes CloudKit raise an uncatchable ObjC
    /// exception, so " \n" has to be treated as corrupt rather than stored as an
    /// id — the earlier fix this one must not undo.
    func testBlankStoredValueStillRegenerates() {
        for blank in ["", "   ", " \n ", "\t"] {
            let r = Resolver(stored: blank)
            let id = r.deviceId
            XCTAssertTrue(id.hasPrefix("minted-"), "blank value \(blank.debugDescription) must regenerate")
            XCTAssertFalse(id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            XCTAssertEqual(r.writes.count, 1, "the replacement must be persisted over the bad value")
        }
    }

    func testHealthyReadIsMemoizedAndWritesNothing() {
        let r = Resolver(stored: "REAL-DEVICE-ID")

        XCTAssertEqual(r.deviceId, "REAL-DEVICE-ID")
        XCTAssertEqual(r.deviceId, "REAL-DEVICE-ID", "second call must hit the memo")
        XCTAssertTrue(r.writes.isEmpty, "reading an existing id must never write")
        XCTAssertFalse(r.isProvisional)
    }

    /// The real type must agree with the table above on a healthy device: a
    /// non-empty id that is stable across calls and not flagged provisional.
    /// (The locked branch is unreachable here — see the note at the top.)
    func testRealDeviceIdentityIsStableAndNonProvisionalWhenUnlocked() {
        let first = DeviceIdentity.deviceId
        XCTAssertFalse(first.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        XCTAssertEqual(first, DeviceIdentity.deviceId, "deviceId must be stable within a process")
        XCTAssertFalse(DeviceIdentity.isProvisional, "the test host runs unlocked")
        XCTAssertEqual(DeviceIdentity.zoneName, "device-\(first)")
    }
}
