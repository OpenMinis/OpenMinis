package com.openminis.app.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-oauth-keep-credentials] A "rejected" verdict marks the instance for
 * re-login (it no longer deletes credentials), but a false positive still turns
 * a working login red and makes routing skip it — so the classifier must only
 * fire on a real rejection.
 */
class OAuthRefreshRejectedTest {

    private val claudeCodes = setOf("invalid_grant", "invalid_token", "invalid_request", "unauthorized_client")

    @Test
    fun authRejectionStatuses_areRejected() {
        for (status in listOf(400, 401, 403)) {
            assertTrue("$status", OAuthManager.isRefreshRejected(status, "", claudeCodes))
        }
    }

    @Test
    fun fatalErrorCode_isRejectedEvenOnOddStatus() {
        assertTrue(OAuthManager.isRefreshRejected(500, """{"error":"invalid_grant"}""", claudeCodes))
        assertTrue(
            OAuthManager.isRefreshRejected(
                500, """{"error":"refresh_token_reused"}""", OAuthManager.DEFAULT_FATAL_REFRESH_CODES,
            ),
        )
    }

    @Test
    fun transientFailures_areNotRejected() {
        assertFalse(OAuthManager.isRefreshRejected(503, "<html>Service Unavailable</html>", claudeCodes))
        assertFalse(OAuthManager.isRefreshRejected(502, "", OAuthManager.DEFAULT_FATAL_REFRESH_CODES))
        assertFalse(OAuthManager.isRefreshRejected(500, """{"error":"server_error"}""", claudeCodes))
    }

    @Test
    fun benignMentionOfRefreshToken_isNotRejected() {
        // The old Claude classifier matched the bare substring `refresh_token`,
        // so this 5xx body counted as a revoked login.
        val body = """{"error":"overloaded","refresh_token_expiry_ms":3600000}"""
        assertFalse(OAuthManager.isRefreshRejected(529, body, claudeCodes))
    }
}
