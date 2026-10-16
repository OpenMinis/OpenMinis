package com.openminis.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-llm-connection-warmup] Pure decision cores for the connection warmup.
 * The network call itself (HEAD into the shared pool) cannot be exercised
 * in CI — these tests pin the host resolution, the default-host mapping,
 * and the debounce window contract.
 */
class ConnectionWarmupTest {

    // ---- shouldWarm: per-host debounce -----------------------------------

    @Test
    fun `never-warmed host always needs warming`() {
        assertTrue(ConnectionWarmup.shouldWarm(lastWarmAtMs = null, nowMs = 1_000L))
    }

    @Test
    fun `recently-warmed host inside window is skipped`() {
        assertTrue(
            !ConnectionWarmup.shouldWarm(lastWarmAtMs = 1_000L, nowMs = 1_000L + ConnectionWarmup.WARM_DEBOUNCE_MS - 1)
        )
    }

    @Test
    fun `stale warm past the window needs warming again`() {
        assertTrue(
            ConnectionWarmup.shouldWarm(lastWarmAtMs = 1_000L, nowMs = 1_000L + ConnectionWarmup.WARM_DEBOUNCE_MS)
        )
    }

    @Test
    fun `custom debounce window is honoured`() {
        assertFalse(ConnectionWarmup.shouldWarm(lastWarmAtMs = 1_000L, nowMs = 5_000L, debounceMs = 10_000L))
        assertTrue(ConnectionWarmup.shouldWarm(lastWarmAtMs = 1_000L, nowMs = 11_000L, debounceMs = 10_000L))
    }

    // ---- hostOf: base URL host extraction --------------------------------

    @Test
    fun `host extracted from base URL with path`() {
        assertEquals("api.deepseek.com", ConnectionWarmup.hostOf("https://api.deepseek.com/v1"))
    }

    @Test
    fun `host extracted from trailing-slash base URL`() {
        assertEquals("api.deepseek.com", ConnectionWarmup.hostOf("https://api.deepseek.com/"))
    }

    @Test
    fun `host extracted from gemini beta path`() {
        assertEquals("generativelanguage.googleapis.com", ConnectionWarmup.hostOf("https://generativelanguage.googleapis.com/v1beta"))
    }

    @Test
    fun `unparseable base yields null so warmup is skipped`() {
        assertNull(ConnectionWarmup.hostOf("not a url"))
    }

    // ---- defaultHostFor: provider-type mapping ---------------------------

    @Test
    fun `official default host per provider type`() {
        assertEquals(
            "api.anthropic.com",
            ConnectionWarmup.defaultHostFor(com.openminis.app.data.model.ProviderType.anthropic),
        )
        assertEquals(
            "generativelanguage.googleapis.com",
            ConnectionWarmup.defaultHostFor(com.openminis.app.data.model.ProviderType.gemini),
        )
        assertEquals(
            "openrouter.ai",
            ConnectionWarmup.defaultHostFor(com.openminis.app.data.model.ProviderType.openRouter),
        )
        assertEquals(
            "api.x.ai",
            ConnectionWarmup.defaultHostFor(com.openminis.app.data.model.ProviderType.xAI),
        )
        assertEquals(
            "api.kimi.com",
            ConnectionWarmup.defaultHostFor(com.openminis.app.data.model.ProviderType.kimiCode),
        )
    }

    @Test
    fun `openai is the fallback for openai-family types`() {
        assertEquals(
            "api.openai.com",
            ConnectionWarmup.defaultHostFor(com.openminis.app.data.model.ProviderType.openAI),
        )
    }
}
