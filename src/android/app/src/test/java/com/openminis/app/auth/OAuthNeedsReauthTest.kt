package com.openminis.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-oauth-keep-credentials] Contract for the "refresh token rejected" path.
 *
 * A rejected refresh (invalid_grant class) must never delete the stored
 * bundle: the manager marks the rejected bundle's fingerprint, and the
 * provider list shows a red dot until a fresh login replaces the bundle
 * (which lapses the mark with no explicit clear). These tests pin the two
 * pure halves of that contract: the rejection classifier and the mark
 * evaluation. The managers' refresh paths only consume them.
 */
class OAuthNeedsReauthTest {

    // ── isRefreshRejected: structured classification ────────────────────

    @Test
    fun `auth-rejection statuses are rejections regardless of body`() {
        assertTrue(OAuthManager.isRefreshRejected(400, """{"error":"invalid_grant"}""", setOf("invalid_grant")))
        assertTrue(OAuthManager.isRefreshRejected(401, "", setOf("invalid_grant")))
        assertTrue(OAuthManager.isRefreshRejected(403, "forbidden", setOf("invalid_grant")))
    }

    @Test
    fun `error code from body matches case-insensitively`() {
        val codes = setOf("invalid_grant", "invalid_token")
        assertTrue(OAuthManager.isRefreshRejected(200, """{"error":"Invalid_Grant"}""", codes))
        assertTrue(OAuthManager.isRefreshRejected(429, """{"error":"invalid_token"}""", codes))
    }

    @Test
    fun `no substring matching - a body merely mentioning refresh_token is not a rejection`() {
        // Regression guard: the old classifier lowercased the whole body and
        // substring-matched, so a body like this flagged a healthy refresh.
        val body = """{"error":"","hint":"refresh_token_expiry_ms":123}"""
        assertFalse(OAuthManager.isRefreshRejected(429, body, setOf("invalid_grant", "invalid_token")))
        assertFalse(OAuthManager.isRefreshRejected(200, """{"error":"rate_limited"}""", setOf("invalid_grant")))
    }

    @Test
    fun `malformed json body is not a rejection`() {
        assertFalse(OAuthManager.isRefreshRejected(503, "<html>gateway</html>", setOf("invalid_grant")))
    }

    // ── evaluateNeedsReauth: mark / lapse / bearer override ─────────────

    private fun markFor(blob: String): String {
        // Same digest as the manager writes: SHA-256 hex of the stored JSON.
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(blob.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `no mark means not flagged`() {
        assertFalse(OAuthManager.evaluateNeedsReauth(mark = null, blob = """{"a":1}""", manualBearer = null))
    }

    @Test
    fun `mark without stored bundle is not flagged`() {
        assertFalse(OAuthManager.evaluateNeedsReauth(mark = "deadbeef", blob = null, manualBearer = null))
    }

    @Test
    fun `mark matching the stored bundle flags re-login`() {
        val blob = """{"refresh_token":"rt-1","access_token":"at-1"}"""
        assertTrue(OAuthManager.evaluateNeedsReauth(mark = markFor(blob), blob = blob, manualBearer = null))
    }

    @Test
    fun `fresh login lapses the mark - fingerprint mismatch is not flagged`() {
        val rejected = """{"refresh_token":"rt-old"}"""
        val fresh = """{"refresh_token":"rt-new"}"""
        assertNotEquals(markFor(rejected), markFor(fresh))
        assertFalse(OAuthManager.evaluateNeedsReauth(mark = markFor(rejected), blob = fresh, manualBearer = null))
    }

    @Test
    fun `manual bearer overrides the mark`() {
        val blob = """{"refresh_token":"rt-1"}"""
        assertFalse(
            OAuthManager.evaluateNeedsReauth(
                mark = markFor(blob), blob = blob, manualBearer = "sk-manual-bearer",
            ),
        )
        // Empty string is "no bearer" — still flagged.
        assertTrue(
            OAuthManager.evaluateNeedsReauth(mark = markFor(blob), blob = blob, manualBearer = ""),
        )
    }
}
