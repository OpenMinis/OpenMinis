package com.openminis.app.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
// java.util.Base64 rather than android.util.Base64 (minSdk 26 has it): this
// layer is pure wire format with no framework dependency, so it stays testable
// under plain JVM unit tests — the module sets `unitTests.isReturnDefaultValues`,
// which would make android.util.Base64 silently return null and turn every
// cross-platform vector assertion into a false pass.
import java.util.Base64

/**
 * Passphrase-based encryption for `.minisbak` packages
 * (docs/backup-restore-design.md §5, scheme `minisbak-enc/1`).
 *
 * This is the Android column of the §5.2.1 parameter table, implemented
 * against `src/ios/Agent/Backup/BackupCrypto.swift`. Every value here is wire
 * format — a deviation makes packages mutually unreadable:
 *
 *   - KDF: PBKDF2-HMAC-SHA256, 600 000 iterations, 16-byte salt, 256-bit KEK.
 *   - Subkeys: HKDF-SHA256 (empty salt — identical to CryptoKit's no-salt
 *     form, since HMAC pads both an empty key and 32 zero bytes to the same
 *     block) with infos `minisbak/{data,secrets,mac,verify}`.
 *   - Cipher: AES-256-GCM, 12-byte random nonce, 128-bit tag, wire layout per
 *     segment = `UInt32 BE length ‖ nonce ‖ ciphertext ‖ tag` (the length
 *     covers nonce+ct+tag, matching CryptoKit's `sealed.combined`).
 *   - Segments: 4 MiB plaintext each, AAD = UTF-8 `"<path>#<segment>"` —
 *     binds each segment to its member path AND index, so members can't be
 *     renamed to impersonate each other and segments can't be reordered.
 *   - Member magic: ASCII `MBK1`.
 *
 * Why PBKDF2 and not Argon2id: neither platform ships Argon2 in its standard
 * library, and §5.2.1 rules that dual-platform standard-library availability
 * beats single-platform optimality. `kdf.alg` keeps both decodable by
 * construction; an unknown alg is refused loudly, never silently substituted
 * (a silent fallback derives a different key and masquerades as "wrong
 * passphrase").
 */
object BackupCrypto {

    const val SCHEME = "minisbak-enc/1"
    const val PBKDF2_ITERATIONS = 600_000
    const val SALT_BYTES = 16

    /** 4 MiB plaintext per independently sealed segment (§5.3). */
    const val SEGMENT_SIZE = 4 * 1024 * 1024

    /** `MBK1` — identifies an encrypted member before any JSON parse attempt. */
    val MAGIC = byteArrayOf(0x4D, 0x42, 0x4B, 0x31)

    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    class CryptoException(message: String) : Exception(message)
    class WrongPassphraseException : Exception("Incorrect passphrase.")
    class ManifestTamperedException :
        Exception("The backup's manifest failed authentication — it may have been modified.")
    class CorruptMemberException(path: String) :
        Exception("Encrypted content is damaged or was tampered with: $path")
    class UnsupportedKDFException(alg: String) :
        Exception("This backup uses an unsupported key-derivation algorithm ($alg). Please update the app.")

    // MARK: - Key hierarchy (§5.2)

    /**
     * The derived key set. Lives only for the duration of an export/import;
     * call [destroy] when finished — the passphrase itself is never persisted.
     */
    class Keys internal constructor(kek: ByteArray) {
        /** Everything under `data/` and `blobs/`. */
        val dataKey: ByteArray = hkdfSha256(kek, "minisbak/data")
        /**
         * `secrets.json` only — the separate subkey is what makes "strip the
         * credentials from this package" a file removal, not a re-encrypt.
         */
        val secretsKey: ByteArray = hkdfSha256(kek, "minisbak/secrets")
        /** Authenticates manifest.json. */
        val macKey: ByteArray = hkdfSha256(kek, "minisbak/mac")
        /** Answers "is this passphrase right?" without touching any payload. */
        val verifierKey: ByteArray = hkdfSha256(kek, "minisbak/verify")

        init {
            kek.fill(0)
        }

        /**
         * `HMAC(verifier_key, "minisbak-v1")` truncated to 16 bytes, base64
         * (§5.2). Written to the manifest at export; recomputed at import so a
         * wrong passphrase fails instantly instead of surfacing as a decrypt
         * error halfway through a multi-GB restore.
         */
        val verifier: String
            get() {
                val mac = hmacSha256(verifierKey, "minisbak-v1".toByteArray(Charsets.UTF_8))
                return Base64.getEncoder().encodeToString(mac.copyOf(16))
            }

        fun destroy() {
            dataKey.fill(0); secretsKey.fill(0); macKey.fill(0); verifierKey.fill(0)
        }
    }

    // MARK: - Derivation

    fun makeSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * Derive the key set from a passphrase. Deliberately synchronous and slow —
     * 600k PBKDF2 rounds are the point. Call off the main thread.
     */
    fun deriveKeys(passphrase: String, kdf: BackupManifest.Encryption.KDF): Keys {
        val salt = try {
            Base64.getDecoder().decode(kdf.salt)
        } catch (e: IllegalArgumentException) {
            throw CorruptMemberException("manifest.encryption.kdf.salt")
        }
        return when (kdf.alg) {
            "pbkdf2-hmac-sha256" -> {
                val iterations = kdf.iterations ?: PBKDF2_ITERATIONS
                Keys(pbkdf2(passphrase, salt, iterations))
            }
            // "argon2id" would come from a future build that vendors Argon2;
            // refusing loudly is correct — see the header comment.
            else -> throw UnsupportedKDFException(kdf.alg)
        }
    }

    /** The KDF descriptor to write into a new package. */
    fun currentKDF(salt: ByteArray) = BackupManifest.Encryption.KDF(
        alg = "pbkdf2-hmac-sha256",
        salt = Base64.getEncoder().encodeToString(salt),
        iterations = PBKDF2_ITERATIONS,
    )

    private fun pbkdf2(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        try {
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * HKDF-SHA256, RFC 5869, empty salt, single-block expand (32 bytes out).
     * Matches CryptoKit's `HKDF<SHA256>.deriveKey(inputKeyMaterial:info:)`.
     */
    private fun hkdfSha256(ikm: ByteArray, info: String): ByteArray {
        val prk = hmacSha256(ByteArray(0), ikm) // extract, salt = empty
        return hmacSha256(prk, info.toByteArray(Charsets.UTF_8) + byteArrayOf(0x01))
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // HMAC forbids a zero-length key in JCA; pad to the block-size
        // equivalent, which HMAC's own key padding makes identical.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // MARK: - Member encryption (§5.3)

    /**
     * Encrypt one package member, streaming through 4 MiB segments.
     *
     * Layout: `MBK1` then, per segment, `UInt32 big-endian length` followed by
     * `nonce ‖ ciphertext ‖ tag`. A zero-length file writes zero segments; the
     * magic alone marks it as encrypted.
     */
    fun encryptFile(source: File, destination: File, key: ByteArray, path: String) {
        source.inputStream().buffered().use { input ->
            destination.outputStream().buffered().use { output ->
                output.write(MAGIC)
                var index = 0
                while (true) {
                    val chunk = readUpTo(input, SEGMENT_SIZE) ?: break
                    val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
                        GCMParameterSpec(TAG_BITS, nonce))
                    cipher.updateAAD(aad(path, index))
                    val sealed = cipher.doFinal(chunk)
                    val combined = nonce + sealed
                    output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                        .putInt(combined.size).array())
                    output.write(combined)
                    index += 1
                }
            }
        }
    }

    fun decryptFile(source: File, destination: File, key: ByteArray, path: String) {
        source.inputStream().buffered().use { input ->
            val header = ByteArray(MAGIC.size)
            if (!readFully(input, header) || !header.contentEquals(MAGIC)) {
                throw CorruptMemberException(path)
            }
            destination.outputStream().buffered().use { output ->
                decryptStream(input, output, key, path)
            }
        }
    }

    /** Decrypt a member whose `MBK1` magic has already been consumed. */
    fun decryptStream(input: InputStream, output: OutputStream, key: ByteArray, path: String) {
        var index = 0
        while (true) {
            val lenBytes = ByteArray(4)
            val first = input.read()
            if (first < 0) break // clean EOF at a segment boundary
            lenBytes[0] = first.toByte()
            if (!readFully(input, lenBytes, offset = 1)) throw CorruptMemberException(path)
            val length = ByteBuffer.wrap(lenBytes).order(ByteOrder.BIG_ENDIAN).int
            if (length < NONCE_BYTES + TAG_BITS / 8 || length > SEGMENT_SIZE + NONCE_BYTES + TAG_BITS / 8 + 64) {
                throw CorruptMemberException(path)
            }
            val body = ByteArray(length)
            if (!readFully(input, body)) throw CorruptMemberException(path)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                    GCMParameterSpec(TAG_BITS, body, 0, NONCE_BYTES))
                cipher.updateAAD(aad(path, index))
                output.write(cipher.doFinal(body, NONCE_BYTES, body.size - NONCE_BYTES))
            } catch (e: Exception) {
                // An auth failure here is indistinguishable from a wrong key,
                // but the caller checks the verifier first, so by this point
                // the passphrase is known-good and this really is damage.
                throw CorruptMemberException(path)
            }
            index += 1
        }
    }

    private fun aad(path: String, segment: Int): ByteArray =
        "$path#$segment".toByteArray(Charsets.UTF_8)

    // MARK: - Manifest authentication (§5.3)

    /**
     * MAC over the RAW BYTES of manifest.json exactly as written to the
     * package — stored in the sidecar member `manifest.mac`, which both
     * platforms' readers prefer. (The embedded `manifest_mac` field is a
     * Swift-re-encoding MAC that Android neither writes nor can verify;
     * raw bytes never go through a decoder, so unknown fields cost nothing
     * and the sidecar stays valid across versions in both directions.)
     */
    fun manifestMac(rawBytes: ByteArray, key: ByteArray): String =
        Base64.getEncoder().encodeToString(hmacSha256(key, rawBytes))

    fun verifyManifestMac(rawBytes: ByteArray, expected: String, key: ByteArray) {
        val actual = Base64.getDecoder().decode(manifestMac(rawBytes, key))
        val exp = try {
            Base64.getDecoder().decode(expected.trim())
        } catch (e: IllegalArgumentException) {
            throw ManifestTamperedException()
        }
        // MessageDigest.isEqual is constant-time since API 16.
        if (!MessageDigest.isEqual(actual, exp)) throw ManifestTamperedException()
    }

    /** Constant-time verifier comparison, so a wrong passphrase can't be timed. */
    fun verifierMatches(stored: String, keys: Keys): Boolean {
        val a = try {
            Base64.getDecoder().decode(stored)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val b = Base64.getDecoder().decode(keys.verifier)
        return MessageDigest.isEqual(a, b)
    }

    // MARK: - Stream helpers

    /** Read up to [max] bytes; null at clean EOF, never a zero-length array. */
    private fun readUpTo(input: InputStream, max: Int): ByteArray? {
        val buf = ByteArray(max)
        var read = 0
        while (read < max) {
            val n = input.read(buf, read, max - read)
            if (n < 0) break
            read += n
        }
        if (read == 0) return null
        return if (read == max) buf else buf.copyOf(read)
    }

    private fun readFully(input: InputStream, buf: ByteArray, offset: Int = 0): Boolean {
        var read = offset
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }
}
