package com.openminis.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openminis.app.ProductionSources

/** [T-android-oauth-log-redact] OAuth secrets reach logs only as length + first 4 chars. */
class OAuthSecretRedactionTest {

    @Test
    fun `secret shows only length and first four chars`() {
        val code = "abcdEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        assertEquals("<len=36 prefix=abcd…>", OAuthLogRedaction.secret(code))
        assertFalse(OAuthLogRedaction.secret(code).contains("EFGH"))
    }

    @Test
    fun `short and null secrets reveal no characters`() {
        assertEquals("<len=6>", OAuthLogRedaction.secret("abc123"))
        assertEquals("<null>", OAuthLogRedaction.secret(null))
    }

    @Test
    fun `url masks code state and pkce params but keeps the rest`() {
        val line = "GET /callback?code=AUTHCODE-SECRET-123&state=STATE-SECRET-456&scope=openid HTTP/1.1"
        val out = OAuthLogRedaction.url(line)
        assertFalse(out.contains("AUTHCODE-SECRET-123"))
        assertFalse(out.contains("STATE-SECRET-456"))
        assertTrue(out.contains("code=<len=19 prefix=AUTH…>"))
        assertTrue(out.contains("state=<len=16 prefix=STAT…>"))
        assertTrue(out.contains("scope=openid"))
        assertTrue(out.endsWith(" HTTP/1.1"))

        val auth = "https://x.ai/authorize?client_id=abc&code_challenge=CHALLENGE-VALUE-XYZ&code_challenge_method=S256"
        val authOut = OAuthLogRedaction.url(auth)
        assertFalse(authOut.contains("CHALLENGE-VALUE-XYZ"))
        assertTrue(authOut.contains("client_id=abc"))
        assertTrue(authOut.contains("code_challenge_method=S256"))
    }

    @Test
    fun `sanitizeBody masks user_code and verifier`() {
        val out = OAuthManager.sanitizeBody("""{"user_code":"UC-SECRET","code_verifier":"V-SECRET"}""")
        assertFalse(out.contains("UC-SECRET"))
        assertFalse(out.contains("V-SECRET"))
    }

    /** Source-fact guard: the verbatim code/verifier/body logs must not come back. */
    @Test
    fun `openrouter manager no longer logs raw code or verifier`() {
        val src = ProductionSources.read("auth/OpenRouterOAuthManager.kt")
        assertFalse(src.contains("\"Code: \$code\""))
        assertFalse(src.contains("verifier.take(20)"))
        assertFalse(src.contains("Exchange request body: \${body"))
    }
}
