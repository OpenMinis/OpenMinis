package com.openminis.app.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-oauth-loopback-binding] RFC 8252 §7.3 says a native app's loopback redirect
 * listener must reject a request whose `Host` is not a loopback name. Every other
 * loopback server in this app binds to 127.0.0.1 explicitly (McpHttpServer,
 * SandboxHttpProxy); OAuthCallbackServer used to `ServerSocket(p)`, i.e. the
 * wildcard address, and ignored the Host header entirely — so a LAN peer could
 * reach the callback port and its request was accepted.
 *
 * These pin the header predicate (pure, no socket) — including the two spellings
 * the app actually registers as redirect URIs: `localhost` (Claude / OpenAI /
 * Gemini / Antigravity) and `127.0.0.1` (xAI).
 */
class OAuthCallbackServerHostTest {

    @Test
    fun `the two registered redirect spellings pass`() {
        assertTrue(OAuthCallbackServer.isLoopbackHostHeader("localhost:54545"))
        assertTrue(OAuthCallbackServer.isLoopbackHostHeader("127.0.0.1:56121"))
        assertTrue(OAuthCallbackServer.isLoopbackHostHeader("LocalHost:8080"))
    }

    @Test
    fun `loopback forms without a port pass`() {
        assertTrue(OAuthCallbackServer.isLoopbackHostHeader("localhost"))
        assertTrue(OAuthCallbackServer.isLoopbackHostHeader("127.0.0.1"))
        // The whole 127/8 block is loopback, not just .0.0.1.
        assertTrue(OAuthCallbackServer.isLoopbackHostHeader("127.0.0.53"))
        assertTrue(OAuthCallbackServer.isLoopbackHostHeader("[::1]"))
        assertTrue(OAuthCallbackServer.isLoopbackHostHeader("[::1]:6000"))
    }

    @Test
    fun `absolute-form authority passes on its host part only`() {
        assertTrue(OAuthCallbackServer.isLoopbackHostHeader("http://localhost:54545"))
    }

    @Test
    fun `non-loopback hosts are rejected`() {
        // A LAN peer reaching the listener by the device's own address.
        assertFalse(OAuthCallbackServer.isLoopbackHostHeader("192.168.1.20:54545"))
        assertFalse(OAuthCallbackServer.isLoopbackHostHeader("10.0.0.7"))
        assertFalse(OAuthCallbackServer.isLoopbackHostHeader("0.0.0.0:54545"))
        // A public / attacker-controlled name — the case §7.3 exists for.
        assertFalse(OAuthCallbackServer.isLoopbackHostHeader("evil.example"))
        assertFalse(OAuthCallbackServer.isLoopbackHostHeader("localhost.evil.example"))
        assertFalse(OAuthCallbackServer.isLoopbackHostHeader("attacker.localhost"))
        // Not an address at all.
        assertFalse(OAuthCallbackServer.isLoopbackHostHeader("127.0.0.256"))
        assertFalse(OAuthCallbackServer.isLoopbackHostHeader("127.0.0"))
    }

    @Test
    fun `absent or empty host is rejected`() {
        assertFalse(OAuthCallbackServer.isLoopbackHostHeader(null))
        assertFalse(OAuthCallbackServer.isLoopbackHostHeader(""))
        assertFalse(OAuthCallbackServer.isLoopbackHostHeader("   "))
        assertFalse(OAuthCallbackServer.isLoopbackHostHeader(":54545"))
    }
}
