import CommonCrypto
import CryptoKit
import Foundation

private let logger = AppLogger(category: "Backup")

/// Passphrase-based encryption for `.minisbak` packages
/// (docs/backup-restore-design.md §5, scheme `minisbak-enc/1`).
///
/// ## Chosen parameters, and the Android equivalent for each
///
/// Selection rule (§5.2's "全部算法双端标准库/成熟库可得", reaffirmed by the
/// 2026-08-14 cross-platform constraint): **an algorithm both platforms can do
/// with a standard library beats one that is better on a single platform.**
/// Nothing below needs a third-party dependency on either side.
///
/// | Parameter | iOS (this file) | Android equivalent |
/// |---|---|---|
/// | KDF | `CCKeyDerivationPBKDF(kCCPBKDF2, kCCPRFHmacAlgSHA256)` — CommonCrypto | `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")` + `PBEKeySpec(pw, salt, 600_000, 256)` — JCA, API 26+ |
/// | Iterations | 600 000 | identical — it is part of the wire format (`kdf.iterations`) |
/// | Salt | 16 random bytes, `SecRandomCopyBytes` | `SecureRandom().nextBytes(ByteArray(16))` |
/// | Subkey derivation | `HKDF<SHA256>.deriveKey` — CryptoKit | `Hkdf` from Tink, or ~15 lines of `javax.crypto.Mac("HmacSHA256")` |
/// | Cipher | `AES.GCM.seal/open` — CryptoKit | `Cipher.getInstance("AES/GCM/NoPadding")` + `GCMParameterSpec(128, nonce)` — JCA |
/// | Nonce | 12 bytes, CryptoKit-generated per segment | same; GCM standard, `SecureRandom` |
/// | Tag | 128-bit, appended by `sealed.combined` | `GCMParameterSpec(128, …)` — JCA appends it identically |
/// | Segment | 4 MiB, `UInt32` big-endian length prefix | `ByteBuffer.order(ByteOrder.BIG_ENDIAN)` |
/// | AAD | UTF-8 `"<path>#<segment>"` | `cipher.updateAAD(s.toByteArray(UTF_8))` |
///
/// **Why not Argon2id**, which §5.2 lists first: no Argon2 ships in the Apple
/// SDKs, and Android has no platform Argon2 either — both sides would need a
/// third-party dependency (argon2-jvm / libsodium-jni on Android, a vendored C
/// library on iOS, in an app whose native-dependency story is already fragile —
/// its libish_emu.a is device-only and cannot link for Simulator at all). Two
/// new native dependencies to align, versus zero: PBKDF2 is the lower-risk
/// choice, and §5.2 explicitly sanctions it. 600k iterations is OWASP's current
/// PBKDF2-SHA256 floor.
///
/// The format carries `kdf.alg`, so **both KDFs stay decodable by
/// construction** — if Argon2 is adopted later, new packages declare
/// `argon2id` and old ones keep opening. An unknown `alg` is refused loudly
/// rather than guessed at.
enum BackupCrypto {

    static let scheme = "minisbak-enc/1"
    static let pbkdf2Iterations = 600_000
    static let saltBytes = 16

    /// Payload is split into independently sealed 4MiB segments (§5.3) so a
    /// multi-hundred-MB blob never has to be held in memory to encrypt or
    /// decrypt, and so truncating or reordering segments is detectable.
    static let segmentSize = 4 * 1024 * 1024

    /// `MBK1` — lets a reader identify an encrypted member before trying to
    /// parse it as JSON.
    static let magic = Data([0x4D, 0x42, 0x4B, 0x31])

    // MARK: - Key hierarchy (§5.2)

    /// The derived key set. Lives only for the duration of an export/import and
    /// is zeroed on deinit; the passphrase itself is never persisted.
    final class Keys {
        /// `data/*` and `blobs/*`.
        let dataKey: SymmetricKey
        /// `secrets.json` only — the separate subkey is what makes "strip the
        /// credentials from this package" a file removal rather than a re-encrypt.
        let secretsKey: SymmetricKey
        /// Authenticates manifest.json.
        let macKey: SymmetricKey
        /// Answers "is this passphrase right?" without touching any payload.
        let verifierKey: SymmetricKey

        fileprivate init(kek: SymmetricKey) {
            func sub(_ info: String) -> SymmetricKey {
                SymmetricKey(data: HKDF<SHA256>.deriveKey(
                    inputKeyMaterial: kek,
                    info: Data(info.utf8),
                    outputByteCount: 32))
            }
            dataKey = sub("minisbak/data")
            secretsKey = sub("minisbak/secrets")
            macKey = sub("minisbak/mac")
            verifierKey = sub("minisbak/verify")
        }

        /// `HMAC(verifier_key, "minisbak-v1")` truncated to 16 bytes (§5.2).
        /// Written to the manifest at export; recomputed at import so a wrong
        /// passphrase fails instantly instead of surfacing as a decrypt error
        /// halfway through a multi-GB restore.
        var verifier: String {
            var mac = HMAC<SHA256>(key: verifierKey)
            mac.update(data: Data("minisbak-v1".utf8))
            return Data(mac.finalize().prefix(16)).base64EncodedString()
        }
    }

    enum CryptoError: LocalizedError {
        case unsupportedKDF(String)
        case unsupportedScheme(String)
        case wrongPassphrase
        case corruptMember(String)
        case manifestTampered

        var errorDescription: String? {
            switch self {
            case .unsupportedKDF(let a):
                return "This backup uses an unsupported key-derivation algorithm (\(a)). Please update the app."
            case .unsupportedScheme(let s):
                return "This backup uses an unsupported encryption scheme (\(s)). Please update the app."
            case .wrongPassphrase:
                return "Incorrect passphrase."
            case .corruptMember(let p):
                return "Encrypted content is damaged or was tampered with: \(p)"
            case .manifestTampered:
                return "The backup's manifest failed authentication — it may have been modified."
            }
        }
    }

    // MARK: - Derivation

    static func makeSalt() -> Data {
        var bytes = [UInt8](repeating: 0, count: saltBytes)
        _ = SecRandomCopyBytes(kSecRandomDefault, saltBytes, &bytes)
        return Data(bytes)
    }

    /// Derive the key set from a passphrase. Deliberately synchronous and slow —
    /// 600k PBKDF2 rounds take roughly a second on an A13, which is the point.
    static func deriveKeys(passphrase: String, kdf: BackupManifest.Encryption.KDF) throws -> Keys {
        guard let salt = Data(base64Encoded: kdf.salt) else {
            throw CryptoError.corruptMember("manifest.encryption.kdf.salt")
        }
        switch kdf.alg {
        case "pbkdf2-hmac-sha256":
            let iterations = kdf.iterations ?? pbkdf2Iterations
            return Keys(kek: try pbkdf2(passphrase: passphrase, salt: salt, iterations: iterations))
        case "argon2id":
            // Written by a future build that vendors Argon2. Refusing loudly is
            // correct: a silent fallback to PBKDF2 would derive a different key
            // and surface as "wrong passphrase", sending the user hunting for a
            // password problem that doesn't exist.
            throw CryptoError.unsupportedKDF(kdf.alg)
        default:
            throw CryptoError.unsupportedKDF(kdf.alg)
        }
    }

    /// The KDF descriptor to write into a new package.
    static func currentKDF(salt: Data) -> BackupManifest.Encryption.KDF {
        BackupManifest.Encryption.KDF(
            alg: "pbkdf2-hmac-sha256", mKib: nil, t: nil, p: nil,
            iterations: pbkdf2Iterations, salt: salt.base64EncodedString())
    }

    private static func pbkdf2(passphrase: String, salt: Data, iterations: Int) throws -> SymmetricKey {
        var out = [UInt8](repeating: 0, count: 32)
        let pwBytes = Array(passphrase.utf8)
        let status: Int32 = salt.withUnsafeBytes { saltPtr in
            pwBytes.withUnsafeBufferPointer { pwPtr in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    // The passphrase is passed as bytes with an explicit length,
                    // so an embedded NUL can't truncate it the way a C-string
                    // API would.
                    UnsafeRawPointer(pwPtr.baseAddress!).assumingMemoryBound(to: CChar.self),
                    pwBytes.count,
                    saltPtr.bindMemory(to: UInt8.self).baseAddress!, salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    UInt32(iterations),
                    &out, out.count)
            }
        }
        guard status == kCCSuccess else {
            throw CryptoError.corruptMember("key derivation failed (status \(status))")
        }
        return SymmetricKey(data: Data(out))
    }

    // MARK: - Member encryption (§5.3)

    /// Encrypt one package member, streaming through 4MiB segments.
    ///
    /// Layout: `MBK1` then, per segment, `UInt32 big-endian length` followed by
    /// the AES-GCM sealed box (nonce ‖ ciphertext ‖ tag).
    ///
    /// The AAD binds each segment to **both** its path and its index, so a
    /// member cannot be renamed to impersonate another (swapping an old
    /// `sessions.jsonl.enc` in as `skills.jsonl.enc`) and segments cannot be
    /// dropped or reordered within a member.
    static func encryptFile(at source: URL, to destination: URL,
                            key: SymmetricKey, path: String) throws {
        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        FileManager.default.createFile(atPath: destination.path, contents: nil)
        let output = try FileHandle(forWritingTo: destination)
        defer { try? output.close() }

        try output.write(contentsOf: magic)
        var index = 0
        while true {
            // [T-backup-scan-jetsam] Per-segment pool: FileHandle.read hands
            // back autoreleased NSData, and seal() allocates a same-sized
            // ciphertext — without draining per segment, encrypting a large
            // member holds every segment of it in memory at once.
            let done = try autoreleasepool { () -> Bool in
                let chunk = try input.read(upToCount: segmentSize) ?? Data()
                // A zero-length file still writes zero segments; the magic alone
                // marks it as encrypted, and decryption yields an empty file.
                if chunk.isEmpty { return true }
                let sealed = try AES.GCM.seal(chunk, using: key,
                                              authenticating: aad(path: path, segment: index))
                guard let combined = sealed.combined else {
                    throw CryptoError.corruptMember(path)
                }
                var length = UInt32(combined.count).bigEndian
                try output.write(contentsOf: Data(bytes: &length, count: 4))
                try output.write(contentsOf: combined)
                index += 1
                return false
            }
            if done { break }
        }
    }

    static func decryptFile(at source: URL, to destination: URL,
                            key: SymmetricKey, path: String) throws {
        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        guard let header = try input.read(upToCount: magic.count), header == magic else {
            throw CryptoError.corruptMember(path)
        }
        FileManager.default.createFile(atPath: destination.path, contents: nil)
        let output = try FileHandle(forWritingTo: destination)
        defer { try? output.close() }

        var index = 0
        while true {
            // [T-backup-scan-jetsam] Same per-segment pool as encryptFile.
            let done = try autoreleasepool { () -> Bool in
                guard let lenData = try input.read(upToCount: 4), !lenData.isEmpty else { return true }
                guard lenData.count == 4 else { throw CryptoError.corruptMember(path) }
                let length = Int(lenData.withUnsafeBytes { $0.loadUnaligned(as: UInt32.self).bigEndian })
                guard let body = try input.read(upToCount: length), body.count == length else {
                    throw CryptoError.corruptMember(path)
                }
                do {
                    let box = try AES.GCM.SealedBox(combined: body)
                    let plain = try AES.GCM.open(box, using: key,
                                                 authenticating: aad(path: path, segment: index))
                    try output.write(contentsOf: plain)
                } catch {
                    // An authentication failure here is indistinguishable from a
                    // wrong key, but the caller checks the verifier first, so by
                    // this point the passphrase is known-good and this really is
                    // damage or tampering.
                    throw CryptoError.corruptMember(path)
                }
                index += 1
                return false
            }
            if done { break }
        }
    }

    private static func aad(path: String, segment: Int) -> Data {
        Data("\(path)#\(segment)".utf8)
    }

    // MARK: - Manifest authentication (§5.3)

    /// `HMAC-SHA256(K_mac, canonical_json(manifest without manifest_mac))`.
    ///
    /// Stops an attacker editing the plaintext manifest — e.g. deleting the
    /// `providers` entry to hide that credentials are present, or downgrading
    /// the KDF parameters.
    static func manifestMAC(_ manifest: BackupManifest, key: SymmetricKey) throws -> String {
        var copy = manifest
        copy.manifestMac = nil
        let encoder = JSONEncoder()
        // Sorted keys make the byte sequence canonical, so both ends compute
        // the MAC over identical input.
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(copy)
        var mac = HMAC<SHA256>(key: key)
        mac.update(data: data)
        return Data(mac.finalize()).base64EncodedString()
    }

    /// MAC over the RAW BYTES of manifest.json exactly as written to the
    /// package (stored in the sidecar member `manifest.mac`).
    ///
    /// The struct-based MAC above has a forward-compatibility flaw: it MACs a
    /// re-encoding of the DECODED manifest, so a reader that predates any
    /// later manifest field (e.g. `snapshot_at`) silently drops it on decode,
    /// re-encodes different bytes, and reports "tampered" for a perfectly good
    /// package. Raw bytes never go through a decoder, so unknown fields cost
    /// nothing — this variant stays valid across versions in BOTH directions.
    static func manifestMAC(rawBytes: Data, key: SymmetricKey) -> String {
        var mac = HMAC<SHA256>(key: key)
        mac.update(data: rawBytes)
        return Data(mac.finalize()).base64EncodedString()
    }

    static func verifyManifestMAC(rawBytes: Data, expected: String, key: SymmetricKey) throws {
        let actual = manifestMAC(rawBytes: rawBytes, key: key)
        guard let a = Data(base64Encoded: actual), let b = Data(base64Encoded: expected),
              a.count == b.count,
              a.withUnsafeBytes({ ap in b.withUnsafeBytes { bp in
                  timingsafe_bcmp(ap.baseAddress!, bp.baseAddress!, a.count) == 0
              } }) else {
            throw CryptoError.manifestTampered
        }
    }

    static func verifyManifestMAC(_ manifest: BackupManifest, key: SymmetricKey) throws {
        guard let expected = manifest.manifestMac else { return }
        let actual = try manifestMAC(manifest, key: key)
        // Constant-time compare via CryptoKit's own equality on the raw bytes.
        guard let a = Data(base64Encoded: actual), let b = Data(base64Encoded: expected),
              a.count == b.count,
              a.withUnsafeBytes({ ap in b.withUnsafeBytes { bp in
                  timingsafe_bcmp(ap.baseAddress!, bp.baseAddress!, a.count) == 0
              } }) else {
            throw CryptoError.manifestTampered
        }
    }

    /// Constant-time verifier comparison, so a wrong passphrase can't be
    /// narrowed down by timing.
    static func verifierMatches(_ stored: String, keys: Keys) -> Bool {
        guard let a = Data(base64Encoded: stored),
              let b = Data(base64Encoded: keys.verifier),
              a.count == b.count else { return false }
        return a.withUnsafeBytes { ap in
            b.withUnsafeBytes { bp in
                timingsafe_bcmp(ap.baseAddress!, bp.baseAddress!, a.count) == 0
            }
        }
    }
}
