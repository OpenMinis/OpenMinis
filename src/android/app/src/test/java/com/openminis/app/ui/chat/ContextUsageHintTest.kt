package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-context-usage-hint] Pins the product rules of the context-usage
 * hint: which numbers produce which tier, when a mid-loop crossing is allowed
 * to interrupt, and how focus/typing retire the line.
 *
 * Everything under test is deliberately free of Android types, so these run on
 * the JVM with no device — the same contract `ComposerPlaceholderRotationTest`
 * holds for the rotation logic.
 */
class ContextUsageHintTest {

    // ---- Tier thresholds ------------------------------------------------

    @Test
    fun `below 70 percent is normal and shows nothing`() {
        val usage = ContextUsage(usedTokens = 69_000, windowTokens = 100_000)
        assertEquals(ContextUsage.Tier.NORMAL, usage.tier)
        assertEquals(69, usage.percent)
    }

    @Test
    fun `exactly 70 percent is warning`() {
        // The boundary belongs to the HIGHER tier — ">= warningFraction" —
        // matching iOS. A user sitting exactly on the line should be warned,
        // not left one token short of it.
        val usage = ContextUsage(usedTokens = 70_000, windowTokens = 100_000)
        assertEquals(ContextUsage.Tier.WARNING, usage.tier)
    }

    @Test
    fun `exactly 80 percent is critical`() {
        val usage = ContextUsage(usedTokens = 80_000, windowTokens = 100_000)
        assertEquals(ContextUsage.Tier.CRITICAL, usage.tier)
    }

    @Test
    fun `percent rounds rather than truncates`() {
        // 69.6% must read "70%", not "69%" — truncation would show a figure
        // that disagrees with the tier the same number produced.
        val usage = ContextUsage(usedTokens = 69_600, windowTokens = 100_000)
        assertEquals(70, usage.percent)
        assertEquals(ContextUsage.Tier.NORMAL, usage.tier)
    }

    // ---- Missing / untrustworthy data -----------------------------------

    @Test
    fun `no usage data yields no hint rather than zero percent`() {
        // The bug this prevents: a provider that omits usage rendering a
        // confident "Context 0% used · 0 / 0".
        assertNull(ContextUsage.from(usedTokens = 0, windowTokens = 200_000))
        assertNull(ContextUsage.from(usedTokens = 124_000, windowTokens = null))
        assertNull(ContextUsage.from(usedTokens = 124_000, windowTokens = 0))
    }

    @Test
    fun `valid numbers produce a usage`() {
        val usage = ContextUsage.from(usedTokens = 124_000, windowTokens = 200_000)
        assertEquals(ContextUsage(124_000, 200_000), usage)
        assertEquals(62, usage!!.percent)
    }

    // ---- Formatting ------------------------------------------------------

    @Test
    fun `token counts format like the usage capsule`() {
        assertEquals("850", TokenCountFormatter.short(850))
        assertEquals("1.2k", TokenCountFormatter.short(1_200))
        assertEquals("124k", TokenCountFormatter.short(124_000))
        assertEquals("200k", TokenCountFormatter.short(200_000))
        assertEquals(
            "124k / 200k",
            TokenCountFormatter.sizePair(ContextUsage(124_000, 200_000)),
        )
    }

    // ---- Crossing detection ---------------------------------------------

    @Test
    fun `upward crossing fires and same tier does not`() {
        val t = ContextTierCrossingTracker()
        assertTrue(t.observe(ContextUsage.Tier.WARNING, nowMs = 1_000))
        t.markFired(ContextUsage.Tier.WARNING, nowMs = 1_000)
        // Same tier again: nothing crossed, so no second line.
        assertFalse(t.observe(ContextUsage.Tier.WARNING, nowMs = 2_000))
        assertFalse(t.observe(ContextUsage.Tier.WARNING, nowMs = 60_000))
    }

    @Test
    fun `downward movement never fires but does re-arm`() {
        val t = ContextTierCrossingTracker()
        assertTrue(t.observe(ContextUsage.Tier.CRITICAL, nowMs = 0))
        t.markFired(ContextUsage.Tier.CRITICAL, nowMs = 0)
        // A compaction drops us back to normal — silent...
        assertFalse(t.observe(ContextUsage.Tier.NORMAL, nowMs = 10_000))
        // ...but climbing again re-announces, which is the whole point of
        // tracking the last tier rather than a one-shot "already warned" flag.
        assertTrue(t.observe(ContextUsage.Tier.WARNING, nowMs = 20_000))
    }

    @Test
    fun `flapping across one line is rate limited`() {
        val t = ContextTierCrossingTracker()
        assertTrue(t.observe(ContextUsage.Tier.WARNING, nowMs = 0))
        t.markFired(ContextUsage.Tier.WARNING, nowMs = 0)
        // 71% -> 69% -> 71% inside the interval must not flash the composer.
        assertFalse(t.observe(ContextUsage.Tier.NORMAL, nowMs = 500))
        assertFalse(t.observe(ContextUsage.Tier.WARNING, nowMs = 1_000))
        // Past the interval the same tier may be re-announced.
        assertFalse(t.observe(ContextUsage.Tier.NORMAL, nowMs = 5_000))
        assertTrue(t.observe(ContextUsage.Tier.WARNING, nowMs = 5_100))
    }

    @Test
    fun `a rise to a higher tier is never rate limited`() {
        // The case that matters most: warning then critical a second later.
        // Holding this back to satisfy a cooldown would suppress the single
        // most urgent line the feature has.
        val t = ContextTierCrossingTracker()
        assertTrue(t.observe(ContextUsage.Tier.WARNING, nowMs = 0))
        t.markFired(ContextUsage.Tier.WARNING, nowMs = 0)
        assertTrue(t.observe(ContextUsage.Tier.CRITICAL, nowMs = 1_000))
    }

    @Test
    fun `suppressed crossing does not start the cooldown`() {
        // observe() reports a crossing but the caller vetoes it (composer had
        // text). Since markFired was never called, the next genuine chance to
        // show the line must not be rate-limited away.
        val t = ContextTierCrossingTracker()
        assertTrue(t.observe(ContextUsage.Tier.WARNING, nowMs = 0))
        // No markFired here.
        assertFalse(t.observe(ContextUsage.Tier.NORMAL, nowMs = 100))
        assertTrue(t.observe(ContextUsage.Tier.WARNING, nowMs = 200))
    }

    @Test
    fun `reset adopts the loaded tier without firing`() {
        // Opening an already-long session must not announce a threshold the
        // user crossed hours ago in a different conversation.
        val t = ContextTierCrossingTracker()
        t.reset(ContextUsage.Tier.CRITICAL)
        assertEquals(ContextUsage.Tier.CRITICAL, t.lastTier)
        assertFalse(t.observe(ContextUsage.Tier.CRITICAL, nowMs = 1_000))
    }

    @Test
    fun `recentlyFired lets loop end skip a line mid loop just showed`() {
        val t = ContextTierCrossingTracker()
        t.observe(ContextUsage.Tier.WARNING, nowMs = 0)
        t.markFired(ContextUsage.Tier.WARNING, nowMs = 0)
        assertTrue(t.recentlyFired(ContextUsage.Tier.WARNING, nowMs = 1_000))
        // A different tier is not a duplicate.
        assertFalse(t.recentlyFired(ContextUsage.Tier.CRITICAL, nowMs = 1_000))
        // Nor is the same tier once the window has passed.
        assertFalse(t.recentlyFired(ContextUsage.Tier.WARNING, nowMs = 9_000))
    }

    // ---- Lifecycle: focus protection and typing --------------------------

    private fun hint(generation: Int, tier: ContextUsage.Tier = ContextUsage.Tier.WARNING) =
        ContextUsageHint(
            generation = generation,
            text = "Context 70% used · 124k / 200k",
            highlights = listOf("70%", "124k / 200k"),
            tier = tier,
        )

    @Test
    fun `first focus is protected and second focus retires the line`() {
        val lifecycle = ContextUsageHintLifecycle()
        assertTrue(lifecycle.present(hint(1)))
        assertTrue(lifecycle.isShowing)

        // First focus — e.g. the keyboard auto-raising after the reply.
        // Returns false = "do not rotate", and the line stays.
        assertFalse(lifecycle.onFocusGained())
        assertTrue(lifecycle.isShowing)

        // Second focus — the user has had their chance to read it.
        assertTrue(lifecycle.onFocusGained())
        assertFalse(lifecycle.isShowing)
    }

    @Test
    fun `focus rotates normally when no hint is showing`() {
        val lifecycle = ContextUsageHintLifecycle()
        assertTrue(lifecycle.onFocusGained())
        assertTrue(lifecycle.onFocusGained())
    }

    @Test
    fun `typing retires the line`() {
        val lifecycle = ContextUsageHintLifecycle()
        lifecycle.present(hint(1))
        lifecycle.onTextChanged("h")
        assertFalse(lifecycle.isShowing)
    }

    @Test
    fun `empty text does not retire and clearing does not revive`() {
        val lifecycle = ContextUsageHintLifecycle()
        lifecycle.present(hint(1))
        // Still empty (e.g. an IME composition that produced nothing).
        lifecycle.onTextChanged("")
        assertTrue(lifecycle.isShowing)

        lifecycle.onTextChanged("draft")
        assertFalse(lifecycle.isShowing)
        // Clearing the box is not a request to see the old figure again.
        lifecycle.onTextChanged("")
        assertFalse(lifecycle.isShowing)
        // And re-presenting the SAME generation must not resurrect it.
        assertFalse(lifecycle.present(hint(1)))
        assertFalse(lifecycle.isShowing)
    }

    @Test
    fun `a new generation shows a line again after one was retired`() {
        val lifecycle = ContextUsageHintLifecycle()
        lifecycle.present(hint(1))
        lifecycle.onTextChanged("typed")
        assertFalse(lifecycle.isShowing)

        // The next reply produces new data — that DOES show a line.
        assertTrue(lifecycle.present(hint(2)))
        assertTrue(lifecycle.isShowing)
        // And its focus protection is fresh, not inherited from generation 1.
        assertFalse(lifecycle.hasProtectedFirstFocus)
        assertFalse(lifecycle.onFocusGained())
        assertTrue(lifecycle.isShowing)
    }

    @Test
    fun `re-presenting the same generation is idempotent`() {
        // Compose re-delivering the same state across recompositions must not
        // reset the protection counter, or the line could never be retired.
        val lifecycle = ContextUsageHintLifecycle()
        assertTrue(lifecycle.present(hint(7)))
        assertFalse(lifecycle.onFocusGained())
        assertTrue(lifecycle.hasProtectedFirstFocus)

        assertFalse(lifecycle.present(hint(7)))
        assertTrue(lifecycle.hasProtectedFirstFocus)
        // Still retires on the second focus, as if the re-delivery never happened.
        assertTrue(lifecycle.onFocusGained())
        assertFalse(lifecycle.isShowing)
    }

    // ---- [T-android-context-usage-second-focus] IME-driven visits ---------

    @Test
    fun `keyboard hidden then shown again retires the line`() {
        // The regression this fixes. On a Pixel 4a the back key hides the
        // keyboard WITHOUT clearing Compose focus, so the old wiring (an
        // `isFocused` rising edge) never saw the second visit and the line
        // stayed protected forever. Driving the visit off IME visibility
        // reproduces the intended two-visit lifecycle.
        val lifecycle = ContextUsageHintLifecycle()
        lifecycle.present(hint(1))

        // Visit 1: keyboard rises (auto-focus after the reply, or a tap).
        assertFalse(lifecycle.onImeShown())
        assertTrue(lifecycle.isShowing)

        // Back key hides the keyboard. No call here on purpose: hiding is not
        // a visit, and the line must survive it.
        assertTrue(lifecycle.isShowing)

        // Visit 2: keyboard rises again -> retire, rotation resumes.
        assertTrue(lifecycle.onImeShown())
        assertFalse(lifecycle.isShowing)
    }

    @Test
    fun `ime and focus triggers share one protection budget`() {
        // Both wiring paths stay live (a hardware keyboard produces a focus
        // edge but never shows an IME). Whichever fires first consumes the
        // protection; the other retires. A doubled trigger must not grant a
        // second grace period, and the order must not matter.
        val viaImeFirst = ContextUsageHintLifecycle()
        viaImeFirst.present(hint(1))
        assertFalse(viaImeFirst.onImeShown())
        assertTrue(viaImeFirst.onFocusGained())
        assertFalse(viaImeFirst.isShowing)

        val viaFocusFirst = ContextUsageHintLifecycle()
        viaFocusFirst.present(hint(1))
        assertFalse(viaFocusFirst.onFocusGained())
        assertTrue(viaFocusFirst.onImeShown())
        assertFalse(viaFocusFirst.isShowing)
    }

    @Test
    fun `ime shown with no hint just rotates`() {
        // Ordinary case: no usage line on screen, so every keyboard raise is
        // a plain rotation trigger.
        val lifecycle = ContextUsageHintLifecycle()
        assertTrue(lifecycle.onImeShown())
        assertTrue(lifecycle.onImeShown())
    }

    @Test
    fun `a new generation re-arms protection for the ime path`() {
        val lifecycle = ContextUsageHintLifecycle()
        lifecycle.present(hint(1))
        assertFalse(lifecycle.onImeShown())
        assertTrue(lifecycle.onImeShown())
        assertFalse(lifecycle.isShowing)

        // Next reply: a fresh line gets its own protected visit.
        assertTrue(lifecycle.present(hint(2)))
        assertFalse(lifecycle.onImeShown())
        assertTrue(lifecycle.isShowing)
    }

    // ---- [T-android-glow-threshold-only] Glow is a pure function of usage ---

    @Test
    fun `glow tier depends only on the measurement crossing a threshold`() {
        // The user's rule: "光晕消失的和改变的条件应该是在对应的阈值" — the glow
        // turns on, changes and turns off at the thresholds and nowhere else.
        // Since the glow renders `ContextUsage.from(...)?.tier`, pinning that
        // mapping pins the glow.
        val w = 32_000
        assertEquals(ContextUsage.Tier.NORMAL, ContextUsage.from(22_000, w)!!.tier)   // 69%
        assertEquals(ContextUsage.Tier.WARNING, ContextUsage.from(22_400, w)!!.tier)  // 70%
        assertEquals(ContextUsage.Tier.WARNING, ContextUsage.from(25_000, w)!!.tier)  // 78%
        assertEquals(ContextUsage.Tier.CRITICAL, ContextUsage.from(25_600, w)!!.tier) // 80%
        assertEquals(ContextUsage.Tier.CRITICAL, ContextUsage.from(30_000, w)!!.tier) // 94%
    }

    @Test
    fun `sending a message does not by itself change the glow`() {
        // Regression: the glow vanished on send. It did so because the value
        // it derived from was momentarily zeroed by the auto-compact guard,
        // not because usage had fallen below a threshold. Same measurement in
        // must always mean same tier out — there is no "a send happened"
        // input to this function at all, which is the property that makes the
        // regression impossible once the glow derives from the measurement.
        val before = ContextUsage.from(25_000, 32_000)
        val afterSend = ContextUsage.from(25_000, 32_000)
        assertEquals(before, afterSend)
        assertEquals(before!!.tier, afterSend!!.tier)
    }

    @Test
    fun `a dropped measurement is not a below-threshold reading`() {
        // 0 tokens means "we have not measured", NOT "the context is empty".
        // Mapping it to null (glow hidden) rather than to NORMAL keeps a
        // missing measurement from being rendered as a confident 0%.
        assertNull(ContextUsage.from(0, 32_000))
    }

    @Test
    fun `changing the window alone moves the tier`() {
        // Binding a group with a smaller context limit must repaint the glow
        // even though the token count is unchanged — which is why the derived
        // flow re-resolves the window on every emission.
        // 25k is 19.5% of a 128k window but 78% of a 32k one.
        assertEquals(ContextUsage.Tier.NORMAL, ContextUsage.from(25_000, 128_000)!!.tier)
        assertEquals(ContextUsage.Tier.WARNING, ContextUsage.from(25_000, 32_000)!!.tier)
        // ...and 27k crosses into critical on the smaller window (84%).
        assertEquals(ContextUsage.Tier.CRITICAL, ContextUsage.from(27_000, 32_000)!!.tier)
    }
}
