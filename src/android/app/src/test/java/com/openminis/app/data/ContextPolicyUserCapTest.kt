package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ctx-user-cap] / [T-ctx-overflow-hard-stop]
 *
 * A user-chosen group context cap used to be judged by the native-window tier
 * table, where anything under 64K sets `compactThreshold = 0` — which
 * [ContextPolicy.check] reads as "auto-compact disabled". A 32K cap (the
 * lowest slider stop) therefore never compacted and never blocked, and an
 * over-capacity session kept sending. iOS parity: `ContextPolicyTests.swift`.
 */
class ContextPolicyUserCapTest {

    @Test
    fun `user cap of 32K keeps auto-compact available`() {
        val policy = ContextPolicy.forUserCap(32_000)
        assertEquals(27_200, policy.compactThreshold) // 85%
        assertEquals(22_400, policy.offloadThreshold) // 70%
        assertEquals(17_600, policy.offloadTarget) // 55%
        assertFalse(policy.exhaustedOnly)
        assertTrue(policy.manualCompactAllowed)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(27_200, 32_000),
        )
        assertEquals(ContextPolicy.CheckResult.OK, policy.check(27_199, 32_000))
    }

    /**
     * The same number as a model's NATIVE window must keep the legacy tier: a
     * genuinely small model still cannot pay for a summary plus warm-up turns.
     */
    @Test
    fun `native window of 32K keeps the legacy tier`() {
        val policy = ContextPolicy.forContextWindow(32_000)
        assertEquals(0, policy.compactThreshold)
        assertTrue(policy.exhaustedOnly)
    }

    @Test
    fun `user cap of 128K compacts proportionally`() {
        val policy = ContextPolicy.forUserCap(128_000)
        assertEquals(108_800, policy.compactThreshold) // 85%
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(108_800, 128_000),
        )
    }

    /** The reported screenshot: 138.8k used against a 128k cap = 108%. */
    @Test
    fun `user cap of 128K compacts when already over the cap`() {
        val policy = ContextPolicy.forUserCap(128_000)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(138_800, 128_000),
        )
    }

    /**
     * Previously OK: an exhausted-only tier returned OK up to its advisory line
     * and kept returning OK past 100%, so the over-length request still went.
     */
    @Test
    fun `overflow on an exhausted-only tier compacts when a summary is viable`() {
        val policy = ContextPolicy.forContextWindow(48_000)
        assertEquals(0, policy.compactThreshold) // precondition
        assertTrue(policy.manualCompactAllowed) // precondition
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(48_000, 48_000),
        )
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(60_000, 48_000),
        )
    }

    /** Below 32K a summary is not viable, so overflow must prompt instead. */
    @Test
    fun `overflow on a tiny window reports exhausted`() {
        val policy = ContextPolicy.forContextWindow(8_192)
        assertFalse(policy.manualCompactAllowed) // precondition
        assertEquals(
            ContextPolicy.CheckResult.EXHAUSTED,
            policy.check(9_000, 8_192),
        )
    }

    @Test
    fun `overflow handling leaves the large-window path alone`() {
        val policy = ContextPolicy.forContextWindow(200_000)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(180_000, 200_000),
        )
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(250_000, 200_000),
        )
        assertEquals(ContextPolicy.CheckResult.OK, policy.check(179_999, 200_000))
    }
}
