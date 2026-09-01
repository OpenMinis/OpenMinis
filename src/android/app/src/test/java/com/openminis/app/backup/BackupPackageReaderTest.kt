package com.openminis.app.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The §8.1 pre-flight order: manifest → verifier → integrity → MAC → decrypt.
 *
 * The order is the point. Integrity must be checkable without the passphrase
 * (the hashes are over ciphertext), and a wrong passphrase must fail against
 * the 16-byte verifier rather than surfacing as a decrypt error partway through
 * a multi-GB restore.
 */
class BackupPackageReaderTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("minisbak-reader", "").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun writeManifest(json: String) = File(root, "manifest.json").apply { writeText(json) }

    @Test
    fun `refuses a future format major version instead of guessing`() {
        writeManifest("""{"format":"minisbak/2","backup_id":"future"}""")
        var message: String? = null
        try {
            BackupPackageReader(root).readManifest()
        } catch (e: BackupException) {
            message = e.message
        }
        assertTrue("must tell the user to update, got: $message", message?.contains("update") == true)
    }

    @Test
    fun `accepts a same-major minor bump`() {
        writeManifest("""{"format":"minisbak/1.4","backup_id":"newer-minor"}""")
        assertEquals("newer-minor", BackupPackageReader(root).readManifest().backupId)
    }

    @Test
    fun `integrity failures are listed, not thrown, so a partial restore stays possible`() {
        File(root, "data").mkdirs()
        File(root, "data/good.jsonl").writeText("good")
        File(root, "data/bad.jsonl").writeText("tampered")
        val goodSha = BackupBlobStore.sha256OfFile(File(root, "data/good.jsonl"))

        writeManifest(
            """
            {"format":"minisbak/1","integrity":{
              "data/good.jsonl":"$goodSha",
              "data/bad.jsonl":"0000000000000000000000000000000000000000000000000000000000000000",
              "data/missing.jsonl":"1111111111111111111111111111111111111111111111111111111111111111"
            }}
            """.trimIndent()
        )
        val failures = BackupPackageReader(root).verifyIntegrity()
        assertEquals(setOf("data/bad.jsonl", "data/missing.jsonl"), failures.toSet())
    }

    /**
     * A wrong passphrase must be caught by the verifier before any payload is
     * touched. Uses a low iteration count — the KDF's cost is not what is under
     * test here.
     */
    @Test
    fun `a wrong passphrase fails against the verifier before touching payload`() {
        val kdf = BackupManifest.Encryption.KDF(
            alg = "pbkdf2-hmac-sha256", salt = "AAECAwQFBgcICQoLDA0ODw==", iterations = 1000
        )
        val right = BackupCrypto.deriveKeys("right passphrase", kdf)
        writeManifest(
            """
            {"format":"minisbak/1","encryption":{"scheme":"minisbak-enc/1",
             "kdf":{"alg":"pbkdf2-hmac-sha256","salt":"AAECAwQFBgcICQoLDA0ODw==","iterations":1000},
             "verifier":"${right.verifier}"}}
            """.trimIndent()
        )

        val reader = BackupPackageReader(root)
        var threw = false
        try {
            reader.unlock("wrong passphrase")
        } catch (e: BackupCrypto.WrongPassphraseException) {
            threw = true
        }
        assertTrue("a wrong passphrase must be rejected by the verifier", threw)

        // The right one must succeed and yield usable keys.
        val keys = reader.unlock("right passphrase")
        assertEquals(right.verifier, keys.verifier)
    }

    @Test
    fun `an unsupported encryption scheme is refused`() {
        writeManifest(
            """
            {"format":"minisbak/1","encryption":{"scheme":"minisbak-enc/99",
             "kdf":{"alg":"pbkdf2-hmac-sha256","salt":"AAECAwQFBgcICQoLDA0ODw=="},"verifier":"x"}}
            """.trimIndent()
        )
        var message: String? = null
        try {
            BackupPackageReader(root).unlock("whatever")
        } catch (e: BackupException) {
            message = e.message
        }
        assertTrue("got: $message", message?.contains("unsupported encryption scheme") == true)
    }

    /**
     * A tampered plaintext manifest must fail the sidecar MAC — that is what
     * stops an attacker deleting the `providers` entry to hide that credentials
     * are present, or downgrading the KDF parameters.
     */
    @Test
    fun `an edited manifest fails the sidecar MAC`() {
        val kdf = BackupManifest.Encryption.KDF(
            alg = "pbkdf2-hmac-sha256", salt = "AAECAwQFBgcICQoLDA0ODw==", iterations = 1000
        )
        val keys = BackupCrypto.deriveKeys("pass", kdf)
        val body = """
            {"format":"minisbak/1","encryption":{"scheme":"minisbak-enc/1",
             "kdf":{"alg":"pbkdf2-hmac-sha256","salt":"AAECAwQFBgcICQoLDA0ODw==","iterations":1000},
             "verifier":"${keys.verifier}"}}
        """.trimIndent()
        writeManifest(body)
        // Sidecar MAC computed over the ORIGINAL bytes…
        File(root, "manifest.mac").writeText(
            BackupCrypto.manifestMac(body.toByteArray(Charsets.UTF_8), keys.macKey)
        )
        // …then the manifest is edited underneath it.
        writeManifest(body.replace("minisbak/1\"", "minisbak/1\",\"device_name\":\"forged\""))

        var threw = false
        try {
            BackupPackageReader(root).unlock("pass")
        } catch (e: BackupCrypto.ManifestTamperedException) {
            threw = true
        }
        assertTrue("an edited manifest must fail authentication", threw)
    }

    /**
     * The manifest is plaintext, so its `encryption` block can be stripped.
     * Without this guard the reader skips decryption, nothing parses, and every
     * category reports zero imported with no error — a silent empty restore
     * that reads as success (iOS review S4).
     */
    @Test
    fun `a stripped encryption block is caught when encrypted members remain`() {
        writeManifest("""{"format":"minisbak/1"}""")
        File(root, "data").mkdirs()
        File(root, "data/sessions.jsonl.enc").writeBytes(BackupCrypto.MAGIC + ByteArray(16))

        var message: String? = null
        try {
            BackupPackageReader(root).assertNoUndeclaredEncryption()
        } catch (e: BackupException) {
            message = e.message
        }
        assertTrue("got: $message", message?.contains("declares none") == true)
    }

    @Test
    fun `a genuinely unencrypted package passes the downgrade guard`() {
        writeManifest("""{"format":"minisbak/1"}""")
        File(root, "data").mkdirs()
        File(root, "data/sessions.jsonl").writeText("{}")
        // Must not throw.
        BackupPackageReader(root).assertNoUndeclaredEncryption()
    }

    @Test
    fun `materialize returns null for a member the package does not carry`() {
        writeManifest("""{"format":"minisbak/1"}""")
        val scratch = File(root, "scratch").apply { mkdirs() }
        assertNull(BackupPackageReader(root).materialize("data/absent.jsonl", null, scratch))
    }

    @Test
    fun `materialize decrypts an encrypted member and leaves plaintext ones alone`() {
        val kdf = BackupManifest.Encryption.KDF(
            alg = "pbkdf2-hmac-sha256", salt = "AAECAwQFBgcICQoLDA0ODw==", iterations = 1000
        )
        val keys = BackupCrypto.deriveKeys("pass", kdf)
        writeManifest("""{"format":"minisbak/1"}""")

        // A plaintext member comes back as-is.
        File(root, "data").mkdirs()
        File(root, "data/plain.jsonl").writeText("plain content")
        val scratch = File(root, "scratch").apply { mkdirs() }
        val reader = BackupPackageReader(root)
        assertEquals(
            "plain content",
            reader.materialize("data/plain.jsonl", keys, scratch)?.readText(),
        )

        // An encrypted one is decrypted under the name it ships as, `.enc`
        // included — that suffix is part of the authenticated AAD path.
        val source = File(root, "src.tmp").apply { writeText("secret content") }
        BackupCrypto.encryptFile(
            source, File(root, "data/enc.jsonl.enc"), keys.dataKey, "data/enc.jsonl.enc"
        )
        assertEquals(
            "secret content",
            reader.materialize("data/enc.jsonl", keys, scratch)?.readText(),
        )
    }

    /** secrets.json uses its own subkey, which is what makes "strip credentials" a file deletion. */
    @Test
    fun `secrets use the secrets subkey, not the data key`() {
        val kdf = BackupManifest.Encryption.KDF(
            alg = "pbkdf2-hmac-sha256", salt = "AAECAwQFBgcICQoLDA0ODw==", iterations = 1000
        )
        val keys = BackupCrypto.deriveKeys("pass", kdf)
        writeManifest("""{"format":"minisbak/1"}""")

        val source = File(root, "s.tmp").apply { writeText("""{"v":1,"providers":[]}""") }
        BackupCrypto.encryptFile(
            source, File(root, "secrets.json.enc"), keys.secretsKey, "secrets.json.enc"
        )
        val scratch = File(root, "scratch").apply { mkdirs() }
        assertEquals(
            """{"v":1,"providers":[]}""",
            BackupPackageReader(root).materialize("secrets.json", keys, scratch)?.readText(),
        )
    }
}
