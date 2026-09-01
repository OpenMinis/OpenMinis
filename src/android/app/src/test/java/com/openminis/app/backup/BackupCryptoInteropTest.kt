package com.openminis.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Cross-platform crypto vectors for `minisbak-enc/1`.
 *
 * The design doc's test plan requires that both platforms agree byte-for-byte
 * on the encryption layer, because a package written on one must open on the
 * other. These expectations were produced by running the REAL iOS primitives —
 * CommonCrypto `CCKeyDerivationPBKDF` + CryptoKit `HKDF`/`HMAC`/`AES.GCM`, the
 * same calls `src/ios/Agent/Backup/BackupCrypto.swift` makes — over fixed
 * inputs, so this checks the port against iOS's actual output rather than
 * against a second reading of the spec.
 *
 * The iteration count here is 1000, not the shipped 600 000: iterations are a
 * plain loop parameter of PBKDF2 that travels in `kdf.iterations`, and a
 * 600k-round KDF in a unit test costs a second per assertion for no extra
 * coverage. Everything that could actually diverge between platforms — the
 * HKDF info strings and empty-salt convention, the verifier truncation, the
 * segment framing, the AAD, the GCM tag placement — is exercised at full
 * fidelity.
 *
 * Regenerate with the script in the design doc's test section if the format
 * ever changes. If one of these fails, the two platforms have stopped being
 * able to read each other's backups — do not "fix" it by updating the constant.
 */
class BackupCryptoInteropTest {

    private val passphrase = "correct horse battery staple"
    private val saltB64 = "AAECAwQFBgcICQoLDA0ODw=="
    private val iterations = 1000

    private fun keys(): BackupCrypto.Keys = BackupCrypto.deriveKeys(
        passphrase,
        BackupManifest.Encryption.KDF(
            alg = "pbkdf2-hmac-sha256", salt = saltB64, iterations = iterations
        ),
    )

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `subkeys match iOS HKDF output`() {
        val k = keys()
        assertEquals(
            "ae303eafb0a710d3e05d5534afa94cf3fe54f6f84bee1321fa9695e69105f8d3",
            k.dataKey.hex(),
        )
        assertEquals(
            "91ca94b4dbab6c1a77a5444728cc9308823320338b487d66ab5e18ff9bcb1cf5",
            k.secretsKey.hex(),
        )
        assertEquals(
            "59bcc9ccbc482af8c8eb747159f3c0d1a72c45d9ff716d700e22889ab3204ef4",
            k.macKey.hex(),
        )
        assertEquals(
            "46650d28362b9ac3451420286efd1205b9ac450f7273d0bfe8466fb3b66e4d27",
            k.verifierKey.hex(),
        )
    }

    @Test
    fun `verifier matches iOS, so a wrong passphrase fails fast on either platform`() {
        val k = keys()
        assertEquals("OOjvlVxNdfSCOrpE9JMI8A==", k.verifier)
        assertTrue(BackupCrypto.verifierMatches("OOjvlVxNdfSCOrpE9JMI8A==", k))
        assertTrue(!BackupCrypto.verifierMatches("AAAAAAAAAAAAAAAAAAAAAA==", k))
    }

    @Test
    fun `manifest sidecar MAC matches iOS over identical raw bytes`() {
        val k = keys()
        val raw = """{"format":"minisbak/1","backup_id":"vector"}""".toByteArray(Charsets.UTF_8)
        assertEquals("14yuPzidILM2o72V74e2Nau1bMWeji2FxCXgEWVV3fI=", BackupCrypto.manifestMac(raw, k.macKey))
        // Must not throw for the good MAC…
        BackupCrypto.verifyManifestMac(raw, "14yuPzidILM2o72V74e2Nau1bMWeji2FxCXgEWVV3fI=", k.macKey)
        // …and must reject an edited manifest.
        val tampered = """{"format":"minisbak/1","backup_id":"forged"}""".toByteArray(Charsets.UTF_8)
        var threw = false
        try {
            BackupCrypto.verifyManifestMac(
                tampered, "14yuPzidILM2o72V74e2Nau1bMWeji2FxCXgEWVV3fI=", k.macKey
            )
        } catch (e: BackupCrypto.ManifestTamperedException) {
            threw = true
        }
        assertTrue("a tampered manifest must fail authentication", threw)
    }

    /**
     * The end-to-end proof: a member sealed by iOS decrypts here. Exercises the
     * `MBK1` magic, the big-endian segment length, the nonce‖ciphertext‖tag
     * layout of CryptoKit's `sealed.combined`, and the `"<path>#<segment>"` AAD
     * — all at once, which is what a real package needs.
     */
    @Test
    fun `decrypts a member sealed by iOS`() {
        val k = keys()
        val member = java.util.Base64.getDecoder().decode(IOS_SEALED_MEMBER_B64)
        assertArrayEquals(BackupCrypto.MAGIC, member.copyOf(4))

        val out = ByteArrayOutputStream()
        BackupCrypto.decryptStream(
            ByteArrayInputStream(member, 4, member.size - 4),
            out, k.dataKey, "data/sessions.jsonl.enc",
        )
        assertEquals("hello minisbak — 跨平台备份\n", out.toString("UTF-8"))
    }

    /**
     * The AAD binds a member to its path, so an attacker cannot swap an old
     * `sessions.jsonl.enc` in under a different name — §5.3's rename-replay
     * defence. A wrong path must fail authentication, not silently decrypt.
     */
    @Test
    fun `refuses a member decrypted under the wrong path`() {
        val k = keys()
        val member = java.util.Base64.getDecoder().decode(IOS_SEALED_MEMBER_B64)
        var threw = false
        try {
            BackupCrypto.decryptStream(
                ByteArrayInputStream(member, 4, member.size - 4),
                ByteArrayOutputStream(), k.dataKey, "data/skills.jsonl.enc",
            )
        } catch (e: BackupCrypto.CorruptMemberException) {
            threw = true
        }
        assertTrue("a renamed member must fail its AAD check", threw)
    }

    /**
     * Round-trip across the segment boundary: more than one 4 MiB segment
     * exercises the per-segment AAD index, which is what stops segments being
     * reordered or dropped within a member.
     */
    @Test
    fun `round-trips a multi-segment member`() {
        val k = keys()
        val plain = ByteArray(BackupCrypto.SEGMENT_SIZE + 12345) { (it % 251).toByte() }
        val source = createTempFile()
        val encrypted = createTempFile()
        val decrypted = createTempFile()
        source.writeBytes(plain)

        BackupCrypto.encryptFile(source, encrypted, k.dataKey, "blobs/ab/deadbeef")
        BackupCrypto.decryptFile(encrypted, decrypted, k.dataKey, "blobs/ab/deadbeef")

        assertArrayEquals(plain, decrypted.readBytes())
        listOf(source, encrypted, decrypted).forEach { it.delete() }
    }

    @Test
    fun `an unknown KDF is refused loudly, never silently substituted`() {
        var threw = false
        try {
            BackupCrypto.deriveKeys(
                passphrase,
                BackupManifest.Encryption.KDF(alg = "argon2id", salt = saltB64, mKib = 65536, t = 3, p = 1),
            )
        } catch (e: BackupCrypto.UnsupportedKDFException) {
            threw = true
        }
        assertTrue("an unknown alg must throw rather than fall back to PBKDF2", threw)
    }

    private fun createTempFile() =
        java.io.File.createTempFile("minisbak-test", null).apply { deleteOnExit() }

    companion object {
        /**
         * One member as iOS actually wrote it: `MBK1` ‖ big-endian length ‖
         * CryptoKit `sealed.combined`, sealed with `K_data` under the AAD
         * `"data/sessions.jsonl.enc#0"`.
         */
        private const val IOS_SEALED_MEMBER_B64 =
            "TUJLMQAAAD8EgVPFKPTCBDN3EmM5sEjMaXvnbYAFoh2TaHYP30AS1vzNgZXgkGPajHA0" +
                "unS8EG/0xljHOifRGMXozEKsRqg="
    }
}
