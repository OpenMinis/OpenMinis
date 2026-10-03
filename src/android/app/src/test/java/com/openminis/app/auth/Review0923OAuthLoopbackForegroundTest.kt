package com.openminis.app.auth

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 2026-09-23 — every loopback OAuth flow must hold its token exchange
 * until Minis is foreground.
 *
 * Guards dcf6085db (T-android-oauth-foreground-exchange) and 65e240734 /
 * 6b4bc40b6 (OpenMinis#360, Claude CLI mimicry on auth requests).
 *
 * dcf6085db found that the loopback callback lands while the user is still in
 * the Custom Tab, i.e. with Minis in the background, where Android blocks the
 * app's network (`blocked=APP_BACKGROUND`): the exchange fails in ~10 ms with
 * UnknownHostException. It added OAuthForegroundGate.awaitForeground to the
 * five provider managers — but the gate is opt-in per call site, and the MCP
 * server OAuth flow (MCPOAuthController) uses the same OAuthCallbackServer +
 * Custom Tab pattern and exchanges the code immediately.
 *
 * [BUG] mcp/oauth/MCPOAuthController.kt: `exchangeCode(...)` runs straight
 * after the callback resumes, with no awaitForeground — same background
 * network block, same instant failure ("Token exchange failed: Unable to
 * resolve host"). Minimal fix: call
 * `OAuthForegroundGate.awaitForeground(TAG)` right before `exchangeCode(...)`
 * (it is already inside a suspend withContext).
 */
class Review0923OAuthLoopbackForegroundTest {

    @Test
    fun `every file that starts a loopback callback server gates its exchange on foreground`() {
        val offenders = ProductionSources.allKotlinFiles()
            .filter { it.name != "OAuthCallbackServer.kt" && it.name != "OAuthForegroundGate.kt" }
            .filter { f ->
                val t = f.readText()
                // Constructs a callback server (not merely references the type).
                Regex("OAuthCallbackServer\\s*\\(").containsMatchIn(t)
            }
            .filterNot { it.readText().contains("OAuthForegroundGate.awaitForeground(") }
            .map { it.name }
        assertTrue(
            "loopback OAuth flows exchanging the code while Minis may still be background " +
                "(network blocked): $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `every Anthropic token request carries the CLI fingerprint and the claude_ai host`() {
        val src = ProductionSources.read("auth/ClaudeOAuthManager.kt")
        assertTrue(src.contains("override val tokenURL = \"https://claude.ai/v1/oauth/token\""))
        val builders = Regex("\\.url\\(tokenURL\\)").findAll(src).count()
        val fingerprinted = Regex("\\.url\\(tokenURL\\)\\s*\\n\\s*\\.applyClaudeCliMimicryHeaders\\(\\)")
            .findAll(src).count()
        assertTrue("no token request found", builders > 0)
        assertTrue(
            "every request to tokenURL (exchange AND refresh) must apply the CLI fingerprint " +
                "($fingerprinted of $builders do)",
            builders == fingerprinted,
        )
        // The old host must not come back anywhere in the tree.
        val stale = ProductionSources.allKotlinFiles()
            .filter { it.name != "ClaudeCliMimicryHeaders.kt" && it.name != "ClaudeOAuthManager.kt" }
            .filter { it.readText().contains("console.anthropic.com/v1/oauth/token") }
            .map { it.name }
        assertTrue("stale token host in $stale", stale.isEmpty())
    }
}
