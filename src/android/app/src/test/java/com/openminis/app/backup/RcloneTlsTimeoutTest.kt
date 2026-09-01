package com.openminis.app.backup

import com.openminis.app.backup.remote.RcloneBridge
import com.openminis.app.backup.remote.RcloneRemoteStore
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-rclone-tls-timeout] The bounded-wait budget and the self-signed
 * certificate opt-in, ported from iOS a28f8721b.
 *
 * Two things here are easy to get wrong in ways no compiler catches and no
 * happy-path device test would notice, so they are pinned:
 *
 *  1. rclone's Duration options are int64 NANOSECONDS. Writing the seconds
 *     straight in yields a 45-billionths-of-a-second timeout, which fails
 *     every transfer instantly rather than obviously.
 *  2. `allowInsecureTLS` must decode to false when the key is ABSENT. Remotes
 *     were persisted before the field existed; a strict decode would make
 *     every previously-configured destination vanish from the user's list.
 */
class RcloneTlsTimeoutTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val obscure: (String) -> String? = { "OBSCURED(${it.length})" }

    // -- 1. Timeout budget ------------------------------------------------

    @Test
    fun `durations are expressed in nanoseconds`() {
        assertEquals(45_000_000_000L, RcloneBridge.durationNanos(45))
        assertEquals(20_000_000_000L, RcloneBridge.durationNanos(20))
        assertEquals(0L, RcloneBridge.durationNanos(0))
    }

    @Test
    fun `global options carry the timeouts rclone expects`() {
        val opts = RcloneBridge.globalTimeoutOptions()

        // Nanoseconds, not seconds — the whole point of the conversion.
        assertEquals(
            RcloneBridge.IO_TIMEOUT_SECONDS * 1_000_000_000L,
            opts["Timeout"],
        )
        assertEquals(
            RcloneBridge.CONNECT_TIMEOUT_SECONDS * 1_000_000_000L,
            opts["ConnectTimeout"],
        )
        assertEquals(RcloneBridge.LOW_LEVEL_RETRIES, opts["LowLevelRetries"])
        assertEquals(RcloneBridge.RETRIES, opts["Retries"])
    }

    @Test
    fun `worst case wait stays within a budget a person will sit through`() {
        // The timeout applies PER ATTEMPT, so retries multiply it. This is the
        // property that actually matters to the user — an unreachable host must
        // fail while they are still looking at the screen, not after rclone's
        // default 300s x 10 retries. Guard the product, not the parts, so a
        // future tweak to either number still has to keep the promise.
        val attempts = RcloneBridge.LOW_LEVEL_RETRIES * RcloneBridge.RETRIES
        val worstCaseSeconds = RcloneBridge.IO_TIMEOUT_SECONDS * attempts
        assertTrue(
            "worst-case wait $worstCaseSeconds s is too long to feel like a failure",
            worstCaseSeconds <= 120,
        )
        assertTrue(
            "connect timeout should not exceed the io timeout",
            RcloneBridge.CONNECT_TIMEOUT_SECONDS <= RcloneBridge.IO_TIMEOUT_SECONDS,
        )
    }

    // -- 2. Certificate-failure detection ---------------------------------

    @Test
    fun `real TLS trust errors are recognised`() {
        // The exact strings Go's crypto/tls and x509 surface through rclone.
        listOf(
            "x509: certificate signed by unknown authority",
            "x509: certificate is valid for foo, not bar",
            "tls: failed to verify certificate: x509: certificate signed by unknown authority",
            "Get \"https://nas.local/dav\": x509: cannot validate certificate",
            "self-signed certificate in certificate chain",
        ).forEach {
            assertTrue("should match: $it", RcloneRemoteStore.looksLikeCertificateFailure(it))
        }
    }

    @Test
    fun `ordinary failures do not offer to disable verification`() {
        // Matching too eagerly would put a "don't verify certificates" control
        // in front of a user whose real problem is a wrong password — the exact
        // outcome the gated toggle exists to avoid.
        listOf(
            null,
            "",
            "401 Unauthorized",
            "Login incorrect",
            "dial tcp 10.0.0.9:443: i/o timeout",
            "directory not found",
            "SignatureDoesNotMatch",
        ).forEach {
            assertFalse("should NOT match: $it", RcloneRemoteStore.looksLikeCertificateFailure(it))
        }
    }

    // -- 3. Persistence is backward compatible ----------------------------

    @Test
    fun `a remote stored before the field existed decodes with verification ON`() {
        // Exactly the JSON an older build wrote: no allowInsecureTLS key.
        val legacy = """
            {"name":"nas","backend":"webdav","params":{"url":"https://nas.local/dav"},
             "path":"/backups","createdAt":1,"enabled":true}
        """.trimIndent()

        val decoded = json.decodeFromString(RcloneRemoteStore.Remote.serializer(), legacy)

        assertEquals("nas", decoded.name)
        assertFalse(
            "an existing destination must not silently start skipping verification",
            decoded.allowInsecureTLS,
        )
    }

    @Test
    fun `the opt-in survives a save and load round trip`() {
        val remote = RcloneRemoteStore.Remote(
            name = "nas", backend = "webdav",
            params = mapOf("url" to "https://nas.local/dav"),
            path = "/backups", createdAt = 1, allowInsecureTLS = true,
        )
        val round = json.decodeFromString(
            RcloneRemoteStore.Remote.serializer(),
            json.encodeToString(RcloneRemoteStore.Remote.serializer(), remote),
        )
        assertTrue(round.allowInsecureTLS)
    }

    // -- 4. FTP gets the per-remote option --------------------------------

    @Test
    fun `ftp carries no_check_certificate when the user opted in`() {
        val params = RcloneRemoteStore.buildConfigParams(
            backend = "ftp",
            params = mapOf("host" to "nas.local"),
            secret = "pw",
            obscure = obscure,
            allowInsecureTLS = true,
        )
        // FTP is the one backend with a real per-remote switch, so it is set
        // explicitly rather than left to the global InsecureSkipVerify.
        assertEquals("true", params["no_check_certificate"])
    }

    @Test
    fun `ftp keeps verification when the user did not opt in`() {
        val params = RcloneRemoteStore.buildConfigParams(
            backend = "ftp",
            params = mapOf("host" to "nas.local"),
            secret = "pw",
            obscure = obscure,
            allowInsecureTLS = false,
        )
        assertNull(params["no_check_certificate"])
    }

    @Test
    fun `webdav does not get a per-remote certificate key`() {
        // WebDAV exposes no such option; writing one would be a silent no-op
        // that reads like protection. It relies on the global flag instead.
        val params = RcloneRemoteStore.buildConfigParams(
            backend = "webdav",
            params = mapOf("url" to "https://nas.local/dav"),
            secret = "pw",
            obscure = obscure,
            allowInsecureTLS = true,
        )
        assertNull(params["no_check_certificate"])
    }

    @Test
    fun `opting in does not disturb the existing credential rules`() {
        // The S3-verbatim and obscured-empty-password rules are load-bearing
        // (RcloneRemoteConfigTest); adding the TLS flag must not perturb them.
        val s3 = RcloneRemoteStore.buildConfigParams(
            backend = "s3",
            params = mapOf("region" to "us-east-1"),
            secret = "SECRET",
            obscure = obscure,
            allowInsecureTLS = true,
        )
        assertEquals("SECRET", s3["secret_access_key"])

        val anon = RcloneRemoteStore.buildConfigParams(
            backend = "webdav",
            params = mapOf("url" to "https://nas.local/dav"),
            secret = null,
            obscure = obscure,
            allowInsecureTLS = true,
        )
        assertEquals("OBSCURED(0)", anon["pass"])
    }
}
