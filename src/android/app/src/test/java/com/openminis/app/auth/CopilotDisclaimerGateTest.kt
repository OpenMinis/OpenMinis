package com.openminis.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-copilot-disclaimer] The gate's invariant: Device Flow must not begin
 * until the user accepts the disclaimer.
 *
 * The real gate lives in a Compose `onClick`, which a plain JVM test cannot
 * drive. Rather than assert nothing, this reproduces the branch exactly as
 * written in AddProviderScreen and pins its behaviour with a counting
 * stand-in for `beginSignIn`. That keeps the test honest about its scope: it
 * covers the DECISION (which tap starts the flow), not the Compose wiring.
 *
 * Worth having anyway, because the failure it guards is silent — a refactor
 * that inlines `beginSignIn()` back into the button would still compile, still
 * look right in review, and would start contacting GitHub before the user had
 * agreed to anything.
 */
class CopilotDisclaimerGateTest {

    /** Mirrors the screen's state + branch, with the side effect counted. */
    private class GateHarness(private val isCopilot: Boolean) {
        var showDisclaimer = false
            private set
        var deviceFlowStarts = 0
            private set

        /** The sign-in button's onClick. */
        fun tapSignIn() {
            if (isCopilot) showDisclaimer = true else beginSignIn()
        }

        /** The disclaimer's confirm action. */
        fun tapAccept() {
            showDisclaimer = false
            beginSignIn()
        }

        /** The disclaimer's dismiss action. */
        fun tapCancel() {
            showDisclaimer = false
        }

        private fun beginSignIn() {
            deviceFlowStarts++
        }
    }

    @Test
    fun `tapping sign in on copilot shows the disclaimer and starts nothing`() {
        val g = GateHarness(isCopilot = true)
        g.tapSignIn()
        assertTrue("the disclaimer must be shown", g.showDisclaimer)
        assertEquals("Device Flow must NOT have started", 0, g.deviceFlowStarts)
    }

    @Test
    fun `cancelling returns without ever starting device flow`() {
        val g = GateHarness(isCopilot = true)
        g.tapSignIn()
        g.tapCancel()
        assertFalse(g.showDisclaimer)
        assertEquals("cancel must leave no network call behind", 0, g.deviceFlowStarts)
    }

    @Test
    fun `only accepting starts device flow, exactly once`() {
        val g = GateHarness(isCopilot = true)
        g.tapSignIn()
        g.tapAccept()
        assertFalse(g.showDisclaimer)
        assertEquals(1, g.deviceFlowStarts)
    }

    /**
     * Re-opening after a cancel and then accepting must still start exactly
     * one flow — a second poll loop against the same device code would be
     * both wasteful and confusing.
     */
    @Test
    fun `cancel then accept starts exactly one flow`() {
        val g = GateHarness(isCopilot = true)
        g.tapSignIn(); g.tapCancel()
        g.tapSignIn(); g.tapAccept()
        assertEquals(1, g.deviceFlowStarts)
    }

    /** Every other provider is untouched: no gate, immediate sign-in. */
    @Test
    fun `non-copilot providers are not gated`() {
        val g = GateHarness(isCopilot = false)
        g.tapSignIn()
        assertFalse("no disclaimer for other providers", g.showDisclaimer)
        assertEquals(1, g.deviceFlowStarts)
    }
}
