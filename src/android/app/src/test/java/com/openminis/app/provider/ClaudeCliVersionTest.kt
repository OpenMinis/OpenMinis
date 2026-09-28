package com.openminis.app.provider

import com.openminis.app.provider.anthropic.ClaudeCliVersion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-anthropic-cli-version] Unit coverage for the dynamic `claude-cli`
 * fingerprint resolution that replaced the hardcoded `2.1.195` constant.
 *
 * The regression these guard against: Anthropic gates new models on a minimum
 * Claude Code version (Fable 5.1 needed >= 2.1.251, Opus 5.5 needs >= 2.1.280)
 * and rejects anything older with `claude_code_version_too_old`.
 */
class ClaudeCliVersionTest {

    @After
    fun tearDown() {
        ClaudeCliVersion.resetForTest()
    }

    // --- version comparison -------------------------------------------------

    @Test
    fun `compares dotted versions numerically not lexically`() {
        // The bug a string compare would introduce: "2.1.9" > "2.1.280"
        assertTrue(ClaudeCliVersion.compareVersions("2.1.280", "2.1.9") > 0)
        assertTrue(ClaudeCliVersion.compareVersions("2.1.280", "2.1.99") > 0)
        assertTrue(ClaudeCliVersion.compareVersions("2.1.195", "2.1.280") < 0)
        assertEquals(0, ClaudeCliVersion.compareVersions("2.1.284", "2.1.284"))
    }

    @Test
    fun `compares across major and minor components`() {
        assertTrue(ClaudeCliVersion.compareVersions("3.0.0", "2.9.999") > 0)
        assertTrue(ClaudeCliVersion.compareVersions("2.2.0", "2.1.999") > 0)
    }

    @Test
    fun `treats missing components as zero`() {
        assertEquals(0, ClaudeCliVersion.compareVersions("2.1", "2.1.0"))
        assertTrue(ClaudeCliVersion.compareVersions("2.1.1", "2.1") > 0)
    }

    // --- dist-tags parsing --------------------------------------------------

    @Test
    fun `picks the highest version among published dist-tags`() {
        // Real payload shape, 2026-09-28.
        val json = """{"stable":"2.1.277","latest":"2.1.284","next":"2.1.284"}"""
        assertEquals("2.1.284", ClaudeCliVersion.parseDistTags(json))
    }

    @Test
    fun `prefers the newest tag even when stable lags behind a gate`() {
        // anthropics/claude-code#96130: stable was 2.1.267 while Opus 5.5
        // required 2.1.280. Picking `stable` would still 400.
        val json = """{"stable":"2.1.267","latest":"2.1.284"}"""
        assertEquals("2.1.284", ClaudeCliVersion.parseDistTags(json))
    }

    @Test
    fun `ignores non-version tag values`() {
        val json = """{"stable":"2.1.277","experimental":"nightly","latest":"2.1.284"}"""
        assertEquals("2.1.284", ClaudeCliVersion.parseDistTags(json))
    }

    @Test
    fun `returns null for malformed or empty payloads`() {
        assertNull(ClaudeCliVersion.parseDistTags(""))
        assertNull(ClaudeCliVersion.parseDistTags("not json"))
        assertNull(ClaudeCliVersion.parseDistTags("{}"))
        assertNull(ClaudeCliVersion.parseDistTags("""{"latest":""}"""))
        assertNull(ClaudeCliVersion.parseDistTags("""{"latest":"2.1"}"""))
        assertNull(ClaudeCliVersion.parseDistTags("""{"latest":"v2.1.284"}"""))
    }

    // --- fallback + header shape -------------------------------------------

    @Test
    fun `falls back to the compiled-in version before any fetch`() {
        ClaudeCliVersion.resetForTest()
        assertEquals(ClaudeCliVersion.FALLBACK_VERSION, ClaudeCliVersion.current())
    }

    @Test
    fun `compiled-in fallback clears the gates known at release time`() {
        // Fable 5.1 needs >= 2.1.251, Opus 5.5 needs >= 2.1.280. A fresh
        // offline install must not reintroduce the 2.1.195 regression.
        assertTrue(
            "fallback must satisfy the Fable 5.1 gate",
            ClaudeCliVersion.compareVersions(ClaudeCliVersion.FALLBACK_VERSION, "2.1.251") >= 0,
        )
        assertTrue(
            "fallback must satisfy the Opus 5.5 gate",
            ClaudeCliVersion.compareVersions(ClaudeCliVersion.FALLBACK_VERSION, "2.1.280") >= 0,
        )
    }

    @Test
    fun `user agent keeps the exact claude-cli header shape`() {
        ClaudeCliVersion.resetForTest()
        val ua = ClaudeCliVersion.userAgent()
        // Anthropic pairs UA with the X-Stainless-* headers to identify the
        // official CLI; the surrounding format must stay byte-identical.
        assertEquals("claude-cli/${ClaudeCliVersion.FALLBACK_VERSION} (external, cli)", ua)
        assertTrue(ua.startsWith("claude-cli/"))
        assertTrue(ua.endsWith(" (external, cli)"))
        assertFalse("the stale pin must not come back", ua.contains("2.1.195"))
    }
}
