import XCTest
@testable import Minis

/// Regression tests for `.minisbak` format acceptance and manifest tolerance
/// (docs/backup-restore-design.md §2.2; review findings S1 and S3).
///
/// Both behaviours are the kind that fail silently: S3 accepted a package
/// written by a completely different product, and S1 rejected a whole valid
/// package because one optional counter was absent. Neither shows up in a
/// device smoke test, because the packages this app writes itself are always
/// well-formed — you only see it when someone else's writer, or a future
/// version, produces the file.
final class BackupFormatCompatibilityTests: XCTestCase {

    // MARK: - §2.2 rule 1 / rule 2 — version acceptance (review S3)

    /// The exact cases named in the review. The pre-fix implementation took
    /// everything after the "/" as the "major", which both accepted foreign
    /// formats and refused same-major minor bumps.
    func testFormatAcceptanceMatrix() {
        // Accepted: same prefix, same major.
        XCTAssertTrue(BackupImporter.isFormatSupported("minisbak/1"))
        // Same major with a minor component — §2.2 rule 2 requires this to
        // import. The pre-fix code REFUSED it (Int("1.1") == nil).
        XCTAssertTrue(BackupImporter.isFormatSupported("minisbak/1.1"))
        XCTAssertTrue(BackupImporter.isFormatSupported("minisbak/1.0.3"))

        // Refused: a different product that happens to use the same shape.
        // The pre-fix code ACCEPTED this — there was no prefix check at all.
        XCTAssertFalse(BackupImporter.isFormatSupported("otherformat/1"))
        // Refused: no prefix whatsoever. Also accepted pre-fix.
        XCTAssertFalse(BackupImporter.isFormatSupported("1"))
        // Refused: a future major. Rule 1 is a hard refusal.
        XCTAssertFalse(BackupImporter.isFormatSupported("minisbak/2"))
        XCTAssertFalse(BackupImporter.isFormatSupported("minisbak/2.0"))
    }

    func testFormatAcceptanceRejectsMalformedStrings() {
        XCTAssertFalse(BackupImporter.isFormatSupported(""))
        XCTAssertFalse(BackupImporter.isFormatSupported("minisbak"))
        XCTAssertFalse(BackupImporter.isFormatSupported("minisbak/"))
        XCTAssertFalse(BackupImporter.isFormatSupported("minisbak/x"))
        XCTAssertFalse(BackupImporter.isFormatSupported("/1"))
        // Case matters: the prefix is compared literally.
        XCTAssertFalse(BackupImporter.isFormatSupported("MinisBak/1"))
    }

    /// Whatever this build writes must be something this build accepts.
    /// Catches a future bump of `BackupFormat.current` that forgets the reader.
    func testCurrentFormatIsSelfCompatible() {
        XCTAssertTrue(BackupImporter.isFormatSupported(BackupFormat.current))
    }

    // MARK: - §2.2 rule 2 — manifest tolerance (review S1)

    private func decode(_ json: String) throws -> BackupManifest {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(BackupManifest.self, from: Data(json.utf8))
    }

    /// The floor of the tolerance promise: an empty object must still decode.
    /// Swift's synthesized `Decodable` ignores struct default values and throws
    /// `keyNotFound`, so this only passes because of the hand-written init.
    func testEmptyObjectDecodesToDefaults() throws {
        let m = try decode("{}")
        XCTAssertEqual(m.format, BackupFormat.current)
        XCTAssertEqual(m.createdAt, Date(timeIntervalSince1970: 0))
        XCTAssertEqual(m.app.platform, "unknown")
        XCTAssertEqual(m.deviceName, "Unknown device")
        XCTAssertTrue(m.categories.isEmpty)
        XCTAssertNil(m.limits.maxFileBytes)
        XCTAssertEqual(m.limits.skippedFiles, 0)
        XCTAssertNil(m.encryption)
        XCTAssertTrue(m.integrity.isEmpty)
        XCTAssertNil(m.manifestMac)
        // A default backupId must still be usable, not empty.
        XCTAssertFalse(m.backupId.isEmpty)
    }

    /// A newer writer adding fields must not break an older reader.
    func testUnknownFieldsAreIgnored() throws {
        let m = try decode("""
        {"format":"minisbak/1","device_name":"iPhone",
         "future_field":{"nested":[1,2,3]},"another":"x"}
        """)
        XCTAssertEqual(m.deviceName, "iPhone")
        XCTAssertEqual(m.format, "minisbak/1")
    }

    /// §2.2 rule 2 turned into a test case directly: drop each optional key in
    /// turn from a complete manifest and assert the result still decodes.
    func testEveryOptionalFieldMayBeAbsent() throws {
        let full: [String: Any] = [
            "format": "minisbak/1",
            "created_at": "2026-08-14T00:00:00Z",
            "app": ["platform": "ios", "version": "1.13", "build": "42"],
            "device_name": "iPhone",
            "backup_id": "ABC123",
            "categories": ["chats": ["entries": 3, "bytes": 99, "encrypted": false]],
            "limits": ["max_file_bytes": 1024, "skipped_files": 1, "skipped_bytes": 2048],
            "integrity": ["manifest.json": "deadbeef"],
            "manifest_mac": "bWFj",
        ]
        // `format` is the one key with real meaning when absent (it defaults to
        // current), but it must still decode — so every key is exercised.
        for key in full.keys {
            var reduced = full
            reduced.removeValue(forKey: key)
            let data = try JSONSerialization.data(withJSONObject: reduced)
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            XCTAssertNoThrow(try decoder.decode(BackupManifest.self, from: data),
                             "manifest must still decode without '\(key)'")
        }
    }

    /// Nested optional counters get the same treatment — S1's concrete trigger
    /// was a missing `limits.skipped_files`.
    func testNestedOptionalCountersMayBeAbsent() throws {
        let m = try decode("""
        {"format":"minisbak/1","limits":{},
         "categories":{"chats":{},"providers":{"entries":2}},
         "app":{}}
        """)
        XCTAssertEqual(m.limits.skippedFiles, 0)
        XCTAssertEqual(m.limits.skippedBytes, 0)
        XCTAssertNil(m.limits.maxFileBytes)
        XCTAssertEqual(m.categories["chats"]?.entries, 0)
        XCTAssertEqual(m.categories["chats"]?.encrypted, false)
        XCTAssertEqual(m.categories["providers"]?.entries, 2)
        XCTAssertNil(m.categories["providers"]?.includesCredentials)
        XCTAssertEqual(m.app.version, "?")
    }

    /// Snake_case keys are part of the wire format. A rename to camelCase would
    /// silently produce defaults instead of failing, which is exactly the class
    /// of bug this file exists to stop.
    func testSnakeCaseKeysAreTheWireFormat() throws {
        let m = try decode("""
        {"format":"minisbak/1","device_name":"A","backup_id":"B",
         "limits":{"max_file_bytes":7,"skipped_files":8,"skipped_bytes":9},
         "categories":{"providers":{"entries":1,"includes_credentials":true}},
         "manifest_mac":"TUFD"}
        """)
        XCTAssertEqual(m.deviceName, "A")
        XCTAssertEqual(m.backupId, "B")
        XCTAssertEqual(m.limits.maxFileBytes, 7)
        XCTAssertEqual(m.limits.skippedFiles, 8)
        XCTAssertEqual(m.limits.skippedBytes, 9)
        XCTAssertEqual(m.categories["providers"]?.includesCredentials, true)
        XCTAssertEqual(m.manifestMac, "TUFD")
    }

    // MARK: - The deliberate exception: kdf.alg / kdf.salt stay required

    /// Tolerance stops at the KDF. Defaulting either field would derive the
    /// wrong key and surface to the user as "wrong passphrase" — sending them
    /// to look for a password problem that doesn't exist. Assert the constraint
    /// is actually enforced rather than merely documented.
    func testKDFAlgAndSaltAreRequired() {
        let base = """
        {"format":"minisbak/1","encryption":{"scheme":"minisbak-enc/1",
         "verifier":"dg==","kdf":{%@}}}
        """
        func manifest(_ kdf: String) -> Data {
            Data(base.replacingOccurrences(of: "%@", with: kdf).utf8)
        }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601

        // Both present -> decodes.
        XCTAssertNoThrow(try decoder.decode(
            BackupManifest.self,
            from: manifest("\"alg\":\"pbkdf2-hmac-sha256\",\"salt\":\"c2FsdA==\"")))

        // `salt` missing -> must throw.
        XCTAssertThrowsError(try decoder.decode(
            BackupManifest.self,
            from: manifest("\"alg\":\"pbkdf2-hmac-sha256\"")))

        // `alg` missing -> must throw.
        XCTAssertThrowsError(try decoder.decode(
            BackupManifest.self, from: manifest("\"salt\":\"c2FsdA==\"")))

        // Both missing -> must throw.
        XCTAssertThrowsError(try decoder.decode(
            BackupManifest.self, from: manifest("")))
    }

    /// The optional Argon2 parameters may be absent even though alg/salt aren't.
    func testKDFOptionalParametersMayBeAbsent() throws {
        let m = try decode("""
        {"format":"minisbak/1","encryption":{"scheme":"minisbak-enc/1",
         "verifier":"dg==","kdf":{"alg":"pbkdf2-hmac-sha256","salt":"c2FsdA==",
         "iterations":600000}}}
        """)
        let kdf = try XCTUnwrap(m.encryption?.kdf)
        XCTAssertEqual(kdf.alg, "pbkdf2-hmac-sha256")
        XCTAssertEqual(kdf.iterations, 600_000)
        XCTAssertNil(kdf.mKib)
        XCTAssertNil(kdf.t)
        XCTAssertNil(kdf.p)
    }

    // MARK: - Round-trip

    /// Encode then decode: the keys written must be the keys read. Catches a
    /// CodingKeys change on one side only.
    func testManifestRoundTripsThroughItsOwnEncoder() throws {
        let original = BackupManifest(
            createdAt: Date(timeIntervalSince1970: 1_760_000_000),
            app: .init(platform: "ios", version: "1.13", build: "7"),
            deviceName: "Test Device",
            backupId: "ID-1",
            categories: ["chats": .init(entries: 5, bytes: 512, encrypted: true,
                                        messages: 3, files: 2),
                         "providers": .init(entries: 1, bytes: 64, encrypted: true,
                                            includesCredentials: false)],
            limits: .init(maxFileBytes: 4096, skippedFiles: 2, skippedBytes: 9000),
            encryption: nil,
            integrity: ["data/sessions.jsonl": "abc"],
            manifestMac: nil)

        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let back = try decoder.decode(BackupManifest.self,
                                      from: try encoder.encode(original))

        XCTAssertEqual(back.deviceName, original.deviceName)
        XCTAssertEqual(back.backupId, original.backupId)
        XCTAssertEqual(back.format, original.format)
        XCTAssertEqual(back.createdAt.timeIntervalSince1970,
                       original.createdAt.timeIntervalSince1970, accuracy: 1)
        XCTAssertEqual(back.limits.maxFileBytes, 4096)
        XCTAssertEqual(back.limits.skippedFiles, 2)
        XCTAssertEqual(back.limits.skippedBytes, 9000)
        XCTAssertEqual(back.categories["chats"]?.messages, 3)
        XCTAssertEqual(back.categories["chats"]?.files, 2)
        XCTAssertEqual(back.categories["providers"]?.includesCredentials, false)
        XCTAssertEqual(back.integrity["data/sessions.jsonl"], "abc")
    }
}
