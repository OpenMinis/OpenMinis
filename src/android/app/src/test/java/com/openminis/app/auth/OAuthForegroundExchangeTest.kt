package com.openminis.app.auth

import androidx.lifecycle.Lifecycle
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-oauth-foreground-exchange] A loopback OAuth sign-in must exchange
 * its code only once Minis is back in the foreground.
 *
 * Reported on a Pixel 6 (Android 17 beta): authorize Anthropic in the browser,
 * the page says the tab can be closed, return to Minis — nothing. Logcat:
 * `Unable to resolve host "claude.ai": No address associated with hostname`,
 * 10 ms after the POST. `dumpsys netpolicy` for the app uid:
 * `blocked=APP_BACKGROUND, allowed=FOREGROUND|TOP|…`. The callback server
 * receives the redirect over loopback (never blocked) while the user is still
 * in the Custom Tab, so the exchange fired from a background app whose network
 * the OS refuses. OpenAI only seemed to work because a ~3 s retry sometimes
 * outlasted the user's return; Anthropic had none.
 *
 * Wiring is pinned against source (the gate needs a real ProcessLifecycleOwner
 * and the managers a browser), the foreground rule directly.
 */
class OAuthForegroundExchangeTest {

    @Test
    fun `only STARTED or RESUMED counts as foreground`() {
        assertTrue(OAuthForegroundGate.isForeground(Lifecycle.State.STARTED))
        assertTrue(OAuthForegroundGate.isForeground(Lifecycle.State.RESUMED))
        // CREATED is what the process sits in while the Custom Tab is on top —
        // exactly the state the OS blocks the network in.
        assertFalse(OAuthForegroundGate.isForeground(Lifecycle.State.CREATED))
        assertFalse(OAuthForegroundGate.isForeground(Lifecycle.State.INITIALIZED))
    }

    @Test
    fun `the wait is bounded so a sign-in can never hang forever`() {
        assertTrue(OAuthForegroundGate.MAX_WAIT_MS in 60_000L..30 * 60_000L)
    }

    // ── Every loopback exchange goes through the gate ─────────────────────

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private fun code(s: String) = s.lineSequence()
        .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
        .joinToString("\n")

    private fun auth(name: String) = code(src("src/main/java/com/openminis/app/auth/$name.kt"))

    /** The gate call must come before the exchange call that follows the callback. */
    private fun assertGatedBefore(file: String, exchangeCall: String) {
        val s = auth(file)
        val gate = s.indexOf("OAuthForegroundGate.awaitForeground(TAG)")
        assertTrue("$file must wait for the foreground", gate >= 0)
        val exchange = s.indexOf(exchangeCall, gate)
        assertTrue("$file must exchange AFTER the foreground wait ($exchangeCall)", exchange > gate)
    }

    @Test
    fun `anthropic waits for the foreground before exchanging`() =
        assertGatedBefore("ClaudeOAuthManager", "exchangeCodeJson(code)")

    @Test
    fun `openai waits for the foreground before exchanging`() =
        assertGatedBefore("OpenAIOAuthManager", "exchangeCodeJson(code)")

    @Test
    fun `openrouter waits for the foreground before exchanging`() =
        assertGatedBefore("OpenRouterOAuthManager", "exchangeCode(code, verifier)")

    @Test
    fun `xai waits for the foreground before exchanging`() =
        assertGatedBefore("XAIOAuthManager", "exchangeCodeForToken(code)")

    @Test
    fun `the base loopback flow waits too`() =
        assertGatedBefore("OAuthManager", "exchangeCode(code)")

    @Test
    fun `anthropic retries briefly and reports network failure as such`() {
        val exchange = auth("ClaudeOAuthManager").substringAfter("private fun exchangeCodeJson(")
        assertTrue("retry for the lag before the block lifts", exchange.contains("for (attempt in 1..3)"))
        assertTrue(
            "a network failure must reach the UI as a network message, not raw UnknownHostException",
            exchange.contains("OAuthNetworkUnreachableException(e)"),
        )
    }

    @Test
    fun `the browser page tells the user to go back instead of claiming completion`() {
        val server = auth("OAuthCallbackServer")
        assertFalse(
            "'You can close this tab' after only receiving the code is what made this look done",
            server.contains("You can close this tab"),
        )
        assertTrue(server.contains("Return to Minis to finish signing in."))
    }

    // ── The screen shows progress and failure ──────────────────────────────

    private val screen by lazy {
        code(src("src/main/java/com/openminis/app/ui/settings/ProviderDetailScreen.kt"))
    }

    @Test
    fun `a pending sign-in is visibly different from signed out`() {
        assertTrue(screen.contains("OAuthForegroundGate.begin()"))
        assertTrue("the phase must be cleared however the sign-in ends", screen.contains("OAuthForegroundGate.end()"))
        assertTrue(screen.contains("OAuthProgressLine(isAuthenticating = isAuthenticating, phase = oauthPhase, error = authError)"))
        assertTrue(screen.contains("R.string.oauth_waiting_for_authorization"))
        assertTrue(screen.contains("R.string.oauth_completing_sign_in"))
    }

    @Test
    fun `a failed sign-in is shown, not only logged`() {
        val catch = screen.substringAfter("OAuth sign-in failed for").substringBefore("} finally {")
        assertTrue("the failure must reach the screen", catch.contains("authError ="))
        assertTrue(catch.contains("add_provider_oauth_network_unreachable"))
        assertTrue(
            "a cancelled sign-in must not be shown as an error",
            screen.contains("if (e is kotlinx.coroutines.CancellationException) throw e"),
        )
    }
}
