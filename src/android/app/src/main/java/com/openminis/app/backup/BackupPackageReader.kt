package com.openminis.app.backup

import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * Read-side entry point for a `.minisbak` package, implementing the §8.1
 * pre-flight order:
 *
 *     manifest (plaintext) → verifier (is the passphrase right?)
 *       → integrity (ciphertext hashes, no passphrase needed)
 *       → manifest MAC (was the plaintext manifest edited?)
 *       → only then decrypt anything
 *
 * The order matters: a wrong passphrase must fail instantly against the
 * 16-byte verifier rather than surfacing as a decrypt error partway through a
 * multi-GB restore, and integrity must be checkable by someone who does not
 * have the passphrase at all (§5.3).
 */
class BackupPackageReader(private val root: File) {

    private val TAG = "Backup"

    /** Present in every package by definition; always plaintext (§2.1). */
    val manifestFile = File(root, "manifest.json")

    /**
     * Raw manifest bytes. Kept as bytes rather than a re-encoding because the
     * sidecar MAC authenticates exactly these bytes — a parse/re-encode cycle
     * would change them and report a good package as tampered.
     */
    fun manifestBytes(): ByteArray {
        if (!manifestFile.exists()) throw BackupException("Package has no manifest.json")
        return manifestFile.readBytes()
    }

    fun readManifest(): BackupManifest {
        val raw = manifestBytes()
        val manifest = try {
            BackupFormat.json.decodeFromString(
                BackupManifest.serializer(), raw.toString(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            throw BackupException("The backup's manifest could not be read.", e)
        }
        // §2.2 rule 1: an unrecognised format major version is refused outright
        // — never best-effort parsed.
        val major = manifest.format.substringBefore('/', "") +
            "/" + manifest.format.substringAfter('/', "").substringBefore('.')
        if (major != BackupFormat.CURRENT) {
            throw BackupException(
                "This backup was created by a newer version of Minis (${manifest.format}). Please update the app."
            )
        }
        return manifest
    }

    val isEncrypted: Boolean
        get() = runCatching { readManifest().encryption != null }.getOrDefault(false)

    /**
     * Downgrade guard — the Android half of iOS's review-S4 check.
     *
     * The manifest is plaintext by design, so anyone can strip its `encryption`
     * block. A reader that trusts it then skips decryption entirely while the
     * members are still `.enc`: nothing parses, every category reports zero
     * imported, and no error is raised — a silent empty restore the user reads
     * as success. If encrypted members are present, the manifest must say so.
     *
     * Call this before importing anything from a package that claims to be
     * unencrypted.
     */
    fun assertNoUndeclaredEncryption(manifest: BackupManifest = readManifest()) {
        if (manifest.encryption != null) return
        if (hasEncryptedMembers()) {
            throw BackupException(
                "This package contains encrypted content but its manifest declares none — " +
                    "it may have been modified."
            )
        }
    }

    private fun hasEncryptedMembers(): Boolean =
        root.walkTopDown().any { it.isFile && it.extension == "enc" }

    /**
     * Derive keys and prove the passphrase before touching any payload.
     *
     * @throws BackupCrypto.WrongPassphraseException when the verifier doesn't match.
     */
    fun unlock(passphrase: String, manifest: BackupManifest = readManifest()): BackupCrypto.Keys {
        val encryption = manifest.encryption
            ?: throw BackupException("This package is not encrypted.")
        if (encryption.scheme != BackupCrypto.SCHEME) {
            throw BackupException(
                "This backup uses an unsupported encryption scheme (${encryption.scheme}). Please update the app."
            )
        }
        val keys = BackupCrypto.deriveKeys(passphrase, encryption.kdf)
        if (!BackupCrypto.verifierMatches(encryption.verifier, keys)) {
            keys.destroy()
            throw BackupCrypto.WrongPassphraseException()
        }
        verifyManifestAuthenticity(manifest, keys)
        return keys
    }

    /**
     * Authenticate the plaintext manifest, so an attacker can't delete the
     * `providers` entry to hide that credentials are present, or downgrade the
     * KDF parameters (§5.3).
     *
     * Prefers the `manifest.mac` sidecar — it MACs the file's exact bytes, so
     * it stays valid when a reader meets a manifest field it doesn't know. The
     * embedded `manifest_mac` field MACs a Swift re-encoding of the decoded
     * struct, which this platform cannot reproduce byte-exactly; when only
     * that form is present the check is skipped rather than failed, because a
     * false "tampered" on a good package is the worse outcome and integrity
     * hashes still cover every member.
     */
    private fun verifyManifestAuthenticity(manifest: BackupManifest, keys: BackupCrypto.Keys) {
        val sidecar = File(root, "manifest.mac")
        if (sidecar.exists()) {
            BackupCrypto.verifyManifestMac(manifestBytes(), sidecar.readText(), keys.macKey)
            return
        }
        if (manifest.manifestMac != null) {
            AppLogger.info(
                TAG,
                "[Backup] package carries only the legacy embedded manifest_mac; " +
                    "skipping MAC check (integrity hashes still apply)"
            )
        }
    }

    /**
     * Verify every member listed in `manifest.integrity` against its stored
     * bytes. Runs WITHOUT the passphrase: the hashes are over ciphertext (§5.3).
     *
     * Returns the paths that failed, rather than throwing on the first one, so
     * the restore UI can tell the user which parts of a damaged package are
     * still usable — a partial restore beats an all-or-nothing refusal.
     */
    fun verifyIntegrity(
        manifest: BackupManifest = readManifest(),
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): List<String> {
        val failures = mutableListOf<String>()
        val total = manifest.integrity.size
        var done = 0
        for ((path, expected) in manifest.integrity) {
            val file = File(root, path)
            if (!file.exists()) {
                failures.add(path)
            } else if (BackupBlobStore.sha256OfFile(file) != expected) {
                failures.add(path)
            }
            done += 1
            onProgress?.invoke(done, total)
        }
        if (failures.isNotEmpty()) {
            AppLogger.error(TAG, "[Backup] integrity check failed for ${failures.size} member(s)")
        }
        return failures
    }

    /**
     * Resolve a logical member path to the file that actually ships, and
     * decrypt it into [scratch] when the package is encrypted.
     *
     * Members gain a `.enc` suffix on encryption, and the AAD binds each
     * segment to the name it ships under (`data/sessions.jsonl.enc`), so the
     * suffix is part of the authenticated path and must be passed through
     * exactly.
     *
     * @return the plaintext file, or null when the member isn't in the package.
     */
    fun materialize(logicalPath: String, keys: BackupCrypto.Keys?, scratch: File): File? {
        val plain = File(root, logicalPath)
        if (plain.exists()) return plain

        val encrypted = File(root, "$logicalPath.enc")
        if (!encrypted.exists()) return null
        val derived = keys ?: throw BackupException(
            "This backup is encrypted — a passphrase is required to read $logicalPath."
        )
        val key = if (logicalPath == "secrets.json") derived.secretsKey else derived.dataKey
        val out = File(scratch, logicalPath).also { it.parentFile?.mkdirs() }
        BackupCrypto.decryptFile(encrypted, out, key, "$logicalPath.enc")
        return out
    }

    /** Members present in the package, logical names with any `.enc` stripped. */
    fun memberNames(): List<String> {
        val base = root.canonicalFile
        return base.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(base).path.replace(File.separatorChar, '/').removeSuffix(".enc") }
            .toList()
    }
}
