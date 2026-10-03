package com.openminis.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-overlay-wide-screen-width] The floating tool capsule must stay a
 * compact pill on wide screens.
 *
 * Reported 2026-09-18: in landscape and on tablets the capsule stretched to
 * "巨长", nearly spanning the display. A cap already existed from the June fix
 * (ec2c759e7, T-android-overlay-landscape-width-rotation-drift) — so this was
 * neither a regression nor a missing tablet branch. The cap was simply set too
 * loose: 400dp is roughly DOUBLE the portrait-phone capsule (196dp on a 393dp
 * Pixel), so the pill still grew noticeably whenever the screen widened.
 *
 * The user's stated reference is the portrait-phone look, so the ceiling is
 * now 240dp. These pin the resulting geometry, because the defect was never
 * "the code has no cap" — it was "the number is wrong", and only arithmetic
 * over real screen widths can catch that.
 *
 * [capsuleWidthDp] mirrors `ToolOverlayController.fixedCapsuleWidthPx()`;
 * that function needs a Context and live displayMetrics, so (as with the other
 * controller-adjacent suites here) the DECISION is reproduced and the source
 * constants are asserted against it.
 */
class OverlayCapsuleWidthTest {

    private val fraction = 0.50f
    private val capFraction = 0.70f
    private val capDp = 240
    private val floorDp = 180

    /** Mirror of fixedCapsuleWidthPx(), in dp. */
    private fun capsuleWidthDp(screenDp: Int): Int {
        val cap = minOf(screenDp * capFraction, capDp.toFloat())
        return maxOf(minOf(screenDp * fraction, cap), floorDp.toFloat()).toInt()
    }

    /** Text budget the label rows get: capsule minus ~76dp of chrome. */
    private fun textBudgetDp(screenDp: Int) = maxOf(capsuleWidthDp(screenDp) - 76, 80)

    // ── the reported bug ──────────────────────────────────────────────────

    @Test
    fun `landscape phone no longer stretches the capsule`() {
        // 851dp = Pixel 4a rotated. Was 400dp (47% of the screen).
        val w = capsuleWidthDp(851)
        assertEquals(240, w)
        assertTrue("capsule must be well under a third of a landscape screen", w < 851 / 3)
    }

    @Test
    fun `tablet landscape stays compact`() {
        // The configuration the June fix never visibly helped: 400dp on a
        // 1280dp screen still read as a banner.
        val w = capsuleWidthDp(1280)
        assertEquals(240, w)
        assertTrue("must be under a quarter of a tablet landscape screen", w < 1280 / 4)
    }

    @Test
    fun `tablet portrait is capped too, not just landscape`() {
        // sw800 portrait: 0.50 of 800dp is 400dp, so without the ceiling a
        // tablet is as bad as a landscape phone even without rotating. This is
        // the "missing tablet branch" the report suspected — covered by the
        // same ceiling rather than a separate configuration.
        assertEquals(240, capsuleWidthDp(800))
    }

    // ── what must NOT change ──────────────────────────────────────────────

    @Test
    fun `portrait phones are untouched by the tighter ceiling`() {
        // The whole point: only wide configurations move. 0.50 of a phone
        // width is far below 240dp, so the fraction still binds.
        assertEquals(196, capsuleWidthDp(393))   // Pixel 4a
        assertEquals(205, capsuleWidthDp(411))   // Xiaomi / HyperOS
        assertTrue("phone portrait must not hit the ceiling", capsuleWidthDp(411) < capDp)
    }

    @Test
    fun `tiny screens keep their floor`() {
        // 0.50 of 320dp is 160dp, below the 180dp floor.
        assertEquals(floorDp, capsuleWidthDp(320))
    }

    @Test
    fun `the narrow-screen fraction still guards the ceiling`() {
        // On a hypothetical 300dp-wide display 240dp would be 80% of the
        // screen; the 0.70 factor is what stops the ceiling being applied
        // blindly. (Floor still wins here, but the ordering must hold.)
        val cap = minOf(300 * capFraction, capDp.toFloat())
        assertEquals(210f, cap)
        assertTrue("the 0.70 fraction must bind below the dp ceiling", cap < capDp)
    }

    @Test
    fun `wide screens never get a smaller text budget than a portrait phone`() {
        // Narrowing the pill must not introduce truncation that portrait
        // phones don't already have — otherwise the fix trades one visual
        // defect for another.
        val phone = textBudgetDp(393)
        for (wide in listOf(851, 914, 800, 1280, 1350)) {
            assertTrue(
                "wide screen $wide dp budget ${textBudgetDp(wide)} must be >= phone $phone",
                textBudgetDp(wide) >= phone,
            )
        }
    }

    // ── the source must agree with the arithmetic above ───────────────────

    @Test
    fun `source constants match the values these tests pin`() {
        val src = File("src/main/java/com/openminis/app/service/ToolOverlayController.kt")
        assertTrue("missing ToolOverlayController source", src.exists())
        val text = src.readText()
        assertTrue(
            "CAPSULE_WIDTH_CAP_DP must be $capDp — a looser ceiling is the bug",
            text.contains("CAPSULE_WIDTH_CAP_DP = $capDp"),
        )
        // Literal match, not interpolated: Kotlin renders 0.70f as "0.7f",
        // while the source writes "0.70f". Asserting the interpolated form
        // fails on formatting rather than on the value it means to pin.
        assertTrue(text.contains("CAPSULE_WIDTH_CAP_FRACTION = 0.70f"))
        assertTrue(text.contains("CAPSULE_WIDTH_FRACTION = 0.50f"))
        assertTrue(text.contains("CAPSULE_WIDTH_FLOOR_DP = $floorDp"))
    }

    @Test
    fun `the capsule width is applied to both the window and the text rows`() {
        // The WM container and the label maxWidth must come from the SAME
        // function, or a capped box would still be pushed open by its text.
        val src = File("src/main/java/com/openminis/app/service/ToolOverlayController.kt").readText()
        assertTrue(
            "text rows must derive their budget from fixedCapsuleWidthPx()",
            src.contains("val maxW = (fixedCapsuleWidthPx()"),
        )
        assertTrue(
            "rotation must recompute the width rather than keep the stale one",
            src.substringAfter("fun onConfigurationChanged()").contains("fixedCapsuleWidthPx()"),
        )
    }
}
