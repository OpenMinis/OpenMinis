import CryptoKit
import XCTest
@testable import Minis

/// Tests for `minisbak-enc/1` (docs/backup-restore-design.md §5).
///
/// The review singled out the AAD binding as "the core anti-tampering claim,
/// currently entirely unverified". That claim is what stops an attacker
/// renaming one member of a package to impersonate another — swapping an old
/// `sessions.jsonl.enc` in as `skills.jsonl.enc` — and nothing exercised it.
/// A round-trip test alone would not have: encrypt-then-decrypt with the same
/// path passes whether or not the AAD is bound at all. So the negative cases
/// below are the point of this file, and the positive round-trips exist mainly
/// to prove the negatives fail for the right reason.
final class BackupCryptoTests: XCTestCase {

    private var dir: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("crypto-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let dir { try? FileManager.default.removeItem(at: dir) }
        try super.tearDownWithError()
    }

    // MARK: - Helpers

    private func file(_ name: String, _ data: Data) throws -> URL {
        let url = dir.appendingPathComponent(name)
        try data.write(to: url)
        return url
    }

    private func key(_ seed: String = "k") -> SymmetricKey {
        // A fixed key: these tests are about the format's binding properties,
        // not the KDF, and 600k PBKDF2 rounds per test would make the suite
        // unusable. The KDF itself is covered separately below.
        SymmetricKey(data: Data(SHA256.hash(data: Data(seed.utf8))))
    }

    private func roundTrip(_ plaintext: Data, path: String = "data/x.jsonl") throws -> Data {
        let src = try file("src", plaintext)
        let enc = dir.appendingPathComponent("enc")
        let dec = dir.appendingPathComponent("dec")
        try BackupCrypto.encryptFile(at: src, to: enc, key: key(), path: path)
        try BackupCrypto.decryptFile(at: enc, to: dec, key: key(), path: path)
        return try Data(contentsOf: dec)
    }

    // MARK: - Round-trip, including the segment boundaries (§5.3)

    func testEmptyFileRoundTrips() throws {
        XCTAssertEqual(try roundTrip(Data()), Data())
    }

    func testSmallFileRoundTrips() throws {
        let plaintext = Data("hello backup".utf8)
        XCTAssertEqual(try roundTrip(plaintext), plaintext)
    }

    /// Exactly one segment — the boundary where an off-by-one in the length
    /// prefix or the read loop would show up.
    func testExactlyOneSegmentRoundTrips() throws {
        let plaintext = Data(repeating: 0xAB, count: BackupCrypto.segmentSize)
        XCTAssertEqual(try roundTrip(plaintext), plaintext)
    }

    func testOneByteOverASegmentRoundTrips() throws {
        let plaintext = Data(repeating: 0xCD, count: BackupCrypto.segmentSize + 1)
        XCTAssertEqual(try roundTrip(plaintext), plaintext)
    }

    /// Several segments, with non-uniform content so a segment swap can't be
    /// masked by every segment being identical.
    func testMultiSegmentRoundTrips() throws {
        var plaintext = Data()
        for i in 0..<3 {
            plaintext.append(Data(repeating: UInt8(i + 1), count: BackupCrypto.segmentSize))
        }
        plaintext.append(Data("tail".utf8))
        XCTAssertEqual(try roundTrip(plaintext), plaintext)
    }

    /// An empty file writes zero segments, so the ciphertext is the magic alone.
    /// That is what marks it encrypted despite carrying no payload.
    func testEmptyFileCiphertextIsMagicOnly() throws {
        let src = try file("empty", Data())
        let enc = dir.appendingPathComponent("empty.enc")
        try BackupCrypto.encryptFile(at: src, to: enc, key: key(), path: "data/empty")
        XCTAssertEqual(try Data(contentsOf: enc), BackupCrypto.magic)
    }

    // MARK: - AAD path binding — the core anti-tampering claim

    /// **The rename-replay attack.** Encrypt as one path, try to decrypt as
    /// another. It must fail: the AAD binds every segment to its path, so a
    /// member cannot be renamed to impersonate a different member of the
    /// package. Without the binding this would succeed and the whole §5.3
    /// guarantee would be decorative.
    func testDecryptingUnderADifferentPathFails() throws {
        let src = try file("a", Data("secret sessions".utf8))
        let enc = dir.appendingPathComponent("a.enc")
        let dec = dir.appendingPathComponent("a.dec")
        try BackupCrypto.encryptFile(at: src, to: enc, key: key(),
                                     path: "data/sessions.jsonl")

        XCTAssertThrowsError(
            try BackupCrypto.decryptFile(at: enc, to: dec, key: key(),
                                         path: "data/skills.jsonl")
        ) { error in
            guard case BackupCrypto.CryptoError.corruptMember = error else {
                return XCTFail("expected corruptMember, got \(error)")
            }
        }
    }

    /// The same path still works — proves the test above fails because of the
    /// path, not because decryption is broken outright.
    func testDecryptingUnderTheSamePathSucceeds() throws {
        let plaintext = Data("secret sessions".utf8)
        XCTAssertEqual(try roundTrip(plaintext, path: "data/sessions.jsonl"), plaintext)
    }

    /// Even a one-character difference must be rejected — the AAD is compared
    /// as bytes, not fuzzily.
    func testNearMissPathIsRejected() throws {
        let src = try file("b", Data("payload".utf8))
        let enc = dir.appendingPathComponent("b.enc")
        let dec = dir.appendingPathComponent("b.dec")
        try BackupCrypto.encryptFile(at: src, to: enc, key: key(), path: "blobs/aa/ff01")
        XCTAssertThrowsError(try BackupCrypto.decryptFile(
            at: enc, to: dec, key: key(), path: "blobs/aa/ff02"))
    }

    /// The AAD also carries the segment index, so segments cannot be reordered
    /// within a member. Swap the two sealed segments and decryption must fail.
    func testReorderingSegmentsIsDetected() throws {
        let seg = BackupCrypto.segmentSize
        var plaintext = Data(repeating: 0x01, count: seg)
        plaintext.append(Data(repeating: 0x02, count: seg))
        let src = try file("multi", plaintext)
        let enc = dir.appendingPathComponent("multi.enc")
        try BackupCrypto.encryptFile(at: src, to: enc, key: key(), path: "data/m")

        // Layout: magic ‖ [len|body] ‖ [len|body]. Both segments seal the same
        // number of bytes, so the two records have equal length — swap them.
        var bytes = try Data(contentsOf: enc)
        let magicLen = BackupCrypto.magic.count
        let recordLen = (bytes.count - magicLen) / 2
        XCTAssertEqual((bytes.count - magicLen) % 2, 0, "expected two equal records")
        let first = bytes[magicLen..<(magicLen + recordLen)]
        let second = bytes[(magicLen + recordLen)...]
        var swapped = Data(bytes[0..<magicLen])
        swapped.append(contentsOf: second)
        swapped.append(contentsOf: first)
        bytes = swapped
        let tampered = dir.appendingPathComponent("multi.swapped.enc")
        try bytes.write(to: tampered)

        XCTAssertThrowsError(try BackupCrypto.decryptFile(
            at: tampered, to: dir.appendingPathComponent("multi.dec"),
            key: key(), path: "data/m"))
    }

    /// A flipped bit inside the ciphertext must be caught by the GCM tag.
    func testBitFlipInCiphertextIsDetected() throws {
        let src = try file("c", Data("important".utf8))
        let enc = dir.appendingPathComponent("c.enc")
        try BackupCrypto.encryptFile(at: src, to: enc, key: key(), path: "data/c")

        var bytes = try Data(contentsOf: enc)
        bytes[bytes.count - 1] ^= 0x01
        let tampered = dir.appendingPathComponent("c.tampered.enc")
        try bytes.write(to: tampered)

        XCTAssertThrowsError(try BackupCrypto.decryptFile(
            at: tampered, to: dir.appendingPathComponent("c.dec"),
            key: key(), path: "data/c"))
    }

    /// A file that isn't one of ours must be refused on the magic, not parsed.
    func testMissingMagicIsRejected() throws {
        let bogus = try file("bogus.enc", Data("not encrypted at all".utf8))
        XCTAssertThrowsError(try BackupCrypto.decryptFile(
            at: bogus, to: dir.appendingPathComponent("bogus.dec"),
            key: key(), path: "data/x"))
    }

    /// A truncated member must fail rather than silently yielding a short file
    /// — a restore that quietly drops the tail is worse than one that errors.
    func testTruncatedCiphertextIsRejected() throws {
        let src = try file("d", Data(repeating: 0x7F, count: 4096))
        let enc = dir.appendingPathComponent("d.enc")
        try BackupCrypto.encryptFile(at: src, to: enc, key: key(), path: "data/d")

        let bytes = try Data(contentsOf: enc)
        let cut = dir.appendingPathComponent("d.cut.enc")
        try bytes.prefix(bytes.count - 8).write(to: cut)

        XCTAssertThrowsError(try BackupCrypto.decryptFile(
            at: cut, to: dir.appendingPathComponent("d.dec"),
            key: key(), path: "data/d"))
    }

    /// The wrong key must not decrypt. (In the real flow the verifier catches
    /// this first; this asserts the payload itself is still protected.)
    func testWrongKeyFails() throws {
        let src = try file("e", Data("payload".utf8))
        let enc = dir.appendingPathComponent("e.enc")
        try BackupCrypto.encryptFile(at: src, to: enc, key: key("right"), path: "data/e")
        XCTAssertThrowsError(try BackupCrypto.decryptFile(
            at: enc, to: dir.appendingPathComponent("e.dec"),
            key: key("wrong"), path: "data/e"))
    }

    // MARK: - Passphrase verifier (§5.2)

    /// The verifier answers "is this passphrase right?" without touching the
    /// payload. Right passphrase matches; wrong one doesn't.
    func testVerifierAcceptsCorrectPassphraseAndRejectsWrongOne() throws {
        let kdf = BackupCrypto.currentKDF(salt: BackupCrypto.makeSalt())
        let good = try BackupCrypto.deriveKeys(passphrase: "correct horse", kdf: kdf)
        let stored = good.verifier

        XCTAssertTrue(BackupCrypto.verifierMatches(stored, keys: good))

        let bad = try BackupCrypto.deriveKeys(passphrase: "correct horst", kdf: kdf)
        XCTAssertFalse(BackupCrypto.verifierMatches(stored, keys: bad))
    }

    func testVerifierRejectsGarbage() throws {
        let kdf = BackupCrypto.currentKDF(salt: BackupCrypto.makeSalt())
        let keys = try BackupCrypto.deriveKeys(passphrase: "pw", kdf: kdf)
        XCTAssertFalse(BackupCrypto.verifierMatches("", keys: keys))
        XCTAssertFalse(BackupCrypto.verifierMatches("!!!not base64!!!", keys: keys))
        // Right encoding, wrong length — must not match, must not crash.
        XCTAssertFalse(BackupCrypto.verifierMatches(
            Data([1, 2, 3]).base64EncodedString(), keys: keys))
    }

    /// The same passphrase under a different salt must derive different keys,
    /// which is what stops one package's verifier validating another's.
    func testSaltChangesTheDerivedKeys() throws {
        let a = try BackupCrypto.deriveKeys(
            passphrase: "same", kdf: BackupCrypto.currentKDF(salt: Data(repeating: 1, count: 16)))
        let b = try BackupCrypto.deriveKeys(
            passphrase: "same", kdf: BackupCrypto.currentKDF(salt: Data(repeating: 2, count: 16)))
        XCTAssertNotEqual(a.verifier, b.verifier)
    }

    /// The four subkeys must be distinct — that separation is what makes
    /// "strip the credentials from this package" a file removal rather than a
    /// re-encrypt (§5.2).
    func testSubkeysAreDistinct() throws {
        let keys = try BackupCrypto.deriveKeys(
            passphrase: "pw", kdf: BackupCrypto.currentKDF(salt: Data(repeating: 9, count: 16)))
        let all = [keys.dataKey, keys.secretsKey, keys.macKey, keys.verifierKey]
            .map { $0.withUnsafeBytes { Data($0) } }
        XCTAssertEqual(Set(all).count, 4, "the four subkeys must all differ")
    }

    /// An unknown KDF must be refused loudly. A silent fallback to PBKDF2 would
    /// derive the wrong key and misreport as "wrong passphrase".
    func testUnknownKDFIsRejected() {
        let kdf = BackupManifest.Encryption.KDF(
            alg: "scrypt-but-not-really", mKib: nil, t: nil, p: nil,
            iterations: 1, salt: Data(repeating: 3, count: 16).base64EncodedString())
        XCTAssertThrowsError(try BackupCrypto.deriveKeys(passphrase: "pw", kdf: kdf)) { error in
            guard case BackupCrypto.CryptoError.unsupportedKDF = error else {
                return XCTFail("expected unsupportedKDF, got \(error)")
            }
        }
    }

    /// argon2id is a declared-but-unimplemented future alg: it must throw the
    /// same explicit error rather than being silently treated as PBKDF2.
    func testArgon2IsRejectedUntilImplemented() {
        let kdf = BackupManifest.Encryption.KDF(
            alg: "argon2id", mKib: 65536, t: 3, p: 4,
            iterations: nil, salt: Data(repeating: 4, count: 16).base64EncodedString())
        XCTAssertThrowsError(try BackupCrypto.deriveKeys(passphrase: "pw", kdf: kdf))
    }

    // MARK: - Manifest MAC (§5.3)

    private func sampleManifest(deviceName: String = "Device A") -> BackupManifest {
        BackupManifest(
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            app: .init(platform: "ios", version: "1.13", build: "9"),
            deviceName: deviceName,
            backupId: "BID",
            categories: ["chats": .init(entries: 2, bytes: 10, encrypted: true),
                         "providers": .init(entries: 1, bytes: 20, encrypted: true,
                                            includesCredentials: true),
                         "skills": .init(entries: 7, bytes: 30, encrypted: true)],
            limits: .init(maxFileBytes: nil, skippedFiles: 0, skippedBytes: 0),
            encryption: nil,
            integrity: ["data/a.jsonl": "h1", "data/b.jsonl": "h2", "blobs/aa/bb": "h3"],
            manifestMac: nil)
    }

    func testManifestMACVerifies() throws {
        let macKey = key("mac")
        var m = sampleManifest()
        m.manifestMac = try BackupCrypto.manifestMAC(m, key: macKey)
        XCTAssertNoThrow(try BackupCrypto.verifyManifestMAC(m, key: macKey))
    }

    /// Editing the plaintext manifest must be detected — e.g. deleting the
    /// `providers` entry to hide that credentials are present.
    func testTamperedManifestIsDetected() throws {
        let macKey = key("mac")
        var m = sampleManifest()
        m.manifestMac = try BackupCrypto.manifestMAC(m, key: macKey)

        m.categories.removeValue(forKey: "providers")
        XCTAssertThrowsError(try BackupCrypto.verifyManifestMAC(m, key: macKey)) { error in
            guard case BackupCrypto.CryptoError.manifestTampered = error else {
                return XCTFail("expected manifestTampered, got \(error)")
            }
        }
    }

    func testManifestMACIsKeyBound() throws {
        var m = sampleManifest()
        m.manifestMac = try BackupCrypto.manifestMAC(m, key: key("mac"))
        XCTAssertThrowsError(try BackupCrypto.verifyManifestMAC(m, key: key("other")))
    }

    /// Any changed field must move the MAC, not just the ones we thought of.
    func testMACChangesWithContent() throws {
        let macKey = key("mac")
        let a = try BackupCrypto.manifestMAC(sampleManifest(deviceName: "A"), key: macKey)
        let b = try BackupCrypto.manifestMAC(sampleManifest(deviceName: "B"), key: macKey)
        XCTAssertNotEqual(a, b)
    }

    /// **Canonical ordering.** The MAC is computed over JSON built from
    /// dictionaries, whose iteration order is not stable across runs or
    /// processes. If the encoder's `.sortedKeys` were dropped, the MAC would
    /// verify most of the time and fail unpredictably — the worst kind of bug,
    /// since it would look like tampering to the user.
    ///
    /// Dictionaries built by inserting the same pairs in different orders are
    /// the practical way to vary internal layout; the MAC must be identical.
    func testManifestMACIsStableAcrossDictionaryOrdering() throws {
        let macKey = key("mac")

        var forward = sampleManifest()
        forward.categories = [:]
        forward.integrity = [:]
        for k in ["chats", "providers", "skills", "memory", "mcp_servers"] {
            forward.categories[k] = .init(entries: k.count, bytes: 1, encrypted: true)
        }
        for k in ["data/a", "data/b", "data/c", "blobs/aa/bb", "blobs/cc/dd"] {
            forward.integrity[k] = "hash-\(k.count)"
        }

        var reversed = sampleManifest()
        reversed.categories = [:]
        reversed.integrity = [:]
        for k in ["mcp_servers", "memory", "skills", "providers", "chats"] {
            reversed.categories[k] = .init(entries: k.count, bytes: 1, encrypted: true)
        }
        for k in ["blobs/cc/dd", "blobs/aa/bb", "data/c", "data/b", "data/a"] {
            reversed.integrity[k] = "hash-\(k.count)"
        }

        XCTAssertEqual(try BackupCrypto.manifestMAC(forward, key: macKey),
                       try BackupCrypto.manifestMAC(reversed, key: macKey))
    }

    /// The MAC must also be stable when recomputed repeatedly in one process —
    /// a cheap guard against any nondeterminism in the encoder configuration.
    func testManifestMACIsDeterministic() throws {
        let macKey = key("mac")
        let m = sampleManifest()
        let first = try BackupCrypto.manifestMAC(m, key: macKey)
        for _ in 0..<8 {
            XCTAssertEqual(try BackupCrypto.manifestMAC(m, key: macKey), first)
        }
    }

    /// The MAC covers the manifest *without* `manifest_mac`, so an already-MACed
    /// manifest must verify — i.e. the field is excluded, not folded in.
    func testMACExcludesTheMACFieldItself() throws {
        let macKey = key("mac")
        let m = sampleManifest()
        let bare = try BackupCrypto.manifestMAC(m, key: macKey)
        var stamped = m
        stamped.manifestMac = bare
        XCTAssertEqual(try BackupCrypto.manifestMAC(stamped, key: macKey), bare)
    }
}
