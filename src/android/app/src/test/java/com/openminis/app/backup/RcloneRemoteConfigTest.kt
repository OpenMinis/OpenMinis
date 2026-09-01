package com.openminis.app.backup

import com.openminis.app.backup.remote.RcloneRemoteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a destination's credentials reach rclone.
 *
 * Two rules here are counter-intuitive and were each found against a real
 * server, so they are pinned rather than left to a future reader's judgement:
 *
 *  1. `secret_access_key` is consumed VERBATIM. Obscuring it — which is right
 *     for every password field — makes S3 sign each request with the obscured
 *     blob and every call fails with SignatureDoesNotMatch.
 *  2. Anonymous access needs an OBSCURED EMPTY password, not an absent one.
 *     rclone de-obscures whatever it finds in `pass`, including the option's
 *     own "" default, so both a bare "" and a missing key fail with
 *     "input too short when revealing password".
 *
 * No test asserts on a real credential value beyond the fixtures defined here,
 * and nothing is logged.
 */
class RcloneRemoteConfigTest {

    /** Stand-in for `core/obscure`: prefixed so it is unmistakably transformed. */
    private val obscure: (String) -> String? = { "OBSCURED(${it.length})" }

    /** The RPC being unavailable — exercises each fallback. */
    private val obscureFails: (String) -> String? = { null }

    private fun build(
        backend: String,
        params: Map<String, String> = emptyMap(),
        secret: String? = null,
        obscureFn: (String) -> String? = obscure,
    ) = RcloneRemoteStore.buildConfigParams(backend, params, secret, obscureFn)

    // MARK: - A. S3 secret must not be obscured

    @Test
    fun `s3 secret is passed through verbatim`() {
        val secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        val out = build("s3", mapOf("endpoint" to "s3.example.com"), secret)

        assertEquals(secret, out["secret_access_key"])
        assertNull("s3 must not write a pass key", out["pass"])
    }

    /**
     * The reported shape: base64-ish secrets carry `/`, `+` and `=`, which is
     * exactly the class of value a naive transform mangles.
     */
    @Test
    fun `s3 secret survives special characters unchanged`() {
        for (secret in listOf(
            "abc/def+ghi=",
            "a+b/c=d+e/f=",
            "Sp3c!@#\$%^&*()_+-=[]{}|;:',.<>?",
            "trailing   spaces   ",
            "ünïcödé-ключ-密钥",
        )) {
            val out = build("s3", secret = secret)
            assertEquals("s3 secret must be verbatim", secret, out["secret_access_key"])
        }
    }

    @Test
    fun `password backends still obscure their secret`() {
        for (backend in listOf("webdav", "sftp", "ftp", "smb")) {
            val out = build(backend, mapOf("user" to "u"), "hunter2")
            assertEquals(
                "$backend must obscure its password",
                "OBSCURED(7)", out["pass"],
            )
            assertNull("$backend must not write secret_access_key", out["secret_access_key"])
        }
    }

    @Test
    fun `obscure failure falls back to the plaintext rather than dropping the key`() {
        val out = build("webdav", mapOf("user" to "u"), "hunter2", obscureFails)
        // Better a working config than a silently credential-less one.
        assertEquals("hunter2", out["pass"])
    }

    @Test
    fun `secretNeedsObscuring is false only for s3`() {
        assertFalse(RcloneRemoteStore.secretNeedsObscuring("s3"))
        for (b in listOf("webdav", "sftp", "ftp", "smb")) {
            assertTrue(b, RcloneRemoteStore.secretNeedsObscuring(b))
        }
    }

    @Test
    fun `secret key name is per backend`() {
        assertEquals("secret_access_key", RcloneRemoteStore.secretKeyFor("s3"))
        for (b in listOf("webdav", "sftp", "ftp", "smb")) {
            assertEquals(b, "pass", RcloneRemoteStore.secretKeyFor(b))
        }
    }

    // MARK: - B. Anonymous access

    @Test
    fun `anonymous webdav gets an obscured empty password, not an absent key`() {
        val out = build("webdav", mapOf("url" to "https://example.com/dav"), secret = null)

        // The whole point: present, and obscured.
        assertEquals("OBSCURED(0)", out["pass"])
    }

    @Test
    fun `anonymous ftp gets an obscured empty password`() {
        val out = build("ftp", mapOf("host" to "ftp.example.com"), secret = null)
        assertEquals("OBSCURED(0)", out["pass"])
    }

    @Test
    fun `an empty stored secret counts as anonymous`() {
        val out = build("webdav", mapOf("url" to "https://x/dav"), secret = "")
        assertEquals("OBSCURED(0)", out["pass"])
    }

    /**
     * S3 is the exception: an anonymous bucket wants NO key, because the value
     * would be used literally rather than de-obscured.
     */
    @Test
    fun `anonymous s3 writes no secret key at all`() {
        val out = build("s3", mapOf("endpoint" to "s3.example.com"), secret = null)

        assertFalse("anonymous s3 must not carry a secret", out.containsKey("secret_access_key"))
        assertFalse("anonymous s3 must not carry a pass", out.containsKey("pass"))
    }

    @Test
    fun `an empty secret is never written as a parameter`() {
        // s3 takes the no-key branch; the others take the obscured-blank one,
        // so neither ever stores a bare empty string.
        assertFalse(build("s3", secret = "").containsKey("secret_access_key"))
        assertEquals("OBSCURED(0)", build("ftp", secret = "")["pass"])
    }

    // MARK: - B. SMB share-less + guest

    @Test
    fun `a legacy stored share is stripped from smb config`() {
        val out = build(
            "smb",
            mapOf("host" to "192.168.1.10", "share" to "backups", "user" to "admin"),
            "pw",
        )

        assertFalse("share must not reach rclone", out.containsKey("share"))
        assertEquals("192.168.1.10", out["host"])
    }

    @Test
    fun `share is only stripped for smb`() {
        // No other backend uses the key, but a blanket strip would be a
        // surprising thing to leave in place.
        val out = build("webdav", mapOf("url" to "https://x/dav", "share" to "keepme"), "pw")
        assertEquals("keepme", out["share"])
    }

    /**
     * rclone's smb `user` defaults to the OS login name when absent, so an
     * omitted username authenticates as some unrelated account instead of
     * connecting as guest. It has to be an explicit empty string.
     */
    @Test
    fun `smb guest forces an explicit empty username`() {
        for (params in listOf(
            emptyMap<String, String>(),
            mapOf("user" to ""),
            mapOf("user" to "   "),
        )) {
            val out = build("smb", params + mapOf("host" to "nas"), secret = null)
            assertEquals("guest smb must send user=\"\"", "", out["user"])
            assertEquals("guest smb still needs an obscured blank", "OBSCURED(0)", out["pass"])
        }
    }

    @Test
    fun `smb keeps a real username untouched`() {
        val out = build("smb", mapOf("host" to "nas", "user" to "admin"), "pw")
        assertEquals("admin", out["user"])
        assertEquals("OBSCURED(2)", out["pass"])
    }

    // MARK: - Untouched backends

    @Test
    fun `sftp and s3 required fields are unaffected`() {
        val sftp = build("sftp", mapOf("host" to "h", "user" to "u", "port" to "22"), "pw")
        assertEquals("h", sftp["host"])
        assertEquals("u", sftp["user"])
        assertEquals("22", sftp["port"])
        assertEquals("OBSCURED(2)", sftp["pass"])

        val s3 = build("s3", mapOf("endpoint" to "e", "access_key_id" to "AK"), "sk")
        assertEquals("e", s3["endpoint"])
        assertEquals("AK", s3["access_key_id"])
        assertEquals("sk", s3["secret_access_key"])
    }

    @Test
    fun `type is always set from the backend`() {
        for (b in listOf("smb", "webdav", "sftp", "s3", "ftp")) {
            assertEquals(b, build(b, secret = "x")["type"])
        }
    }

    @Test
    fun `non-credential params are carried through untouched`() {
        val out = build(
            "s3",
            mapOf("endpoint" to "s3.example.com", "region" to "us-east-1", "access_key_id" to "AK"),
            "sk",
        )
        assertEquals("s3.example.com", out["endpoint"])
        assertEquals("us-east-1", out["region"])
        assertEquals("AK", out["access_key_id"])
    }
}
