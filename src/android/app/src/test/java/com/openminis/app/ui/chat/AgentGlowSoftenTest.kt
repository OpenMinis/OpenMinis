package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-agent-glow-soften] The sub agent tile's inner glow.
 *
 * Asked for: wider spread, inward rather than clinging to the edge, and a
 * paler purple. The original was a straight port of iOS's 6pt inset stroke at
 * 0.55 alpha; on a 100x65dp tile that read as a hard bright ring around the
 * rim rather than a glow.
 *
 * The draw is inside a @Composable's drawWithContent and this module has no
 * Compose test infrastructure, so the geometry is reproduced here and the
 * production constants are pinned against the source. The arithmetic tests are
 * real — they are what caught both defects that widening the band exposed.
 */
class AgentGlowSoftenTest {

    private val src by lazy { ProductionSources.read("ui/chat/ChatComposerWidgets.kt") }

    /** The production profile: band 32dp, peak 0.11, cubic falloff. */
    private fun alphaAt(depthDp: Double, band: Double = 32.0, peak: Double = 0.11): Double {
        if (depthDp >= band) return 0.0
        val fade = 1.0 - depthDp / band
        return peak * fade * fade * fade
    }

    /** The old profile, for contrast. */
    private fun oldAlphaAt(depthDp: Double): Double {
        if (depthDp >= 10.0) return 0.0
        val fade = 1.0 - depthDp / 10.0
        return 0.55 * fade * fade
    }

    @Test
    fun `the glow is paler at the edge than it was`() {
        // The peak sits at the rim, where it also overlapped the hairline
        // border — the most saturated part of the whole tile.
        assertTrue("edge alpha must drop well below the old 0.55", alphaAt(0.0) < 0.25)
        assertEquals(0.11, alphaAt(0.0), 1e-9)
        assertTrue(alphaAt(0.0) < oldAlphaAt(0.0) / 2)
    }

    @Test
    fun `the glow reaches much further in`() {
        // Old: nothing past 10dp. New: still tinting at 14dp.
        assertEquals(0.0, oldAlphaAt(12.0), 1e-9)
        assertTrue("must still carry colour at 14dp", alphaAt(14.0) > 0.0)
        assertTrue("still tinting at 22dp since the 32dp widening", alphaAt(22.0) > 0.0)
        assertEquals(0.0, alphaAt(32.0), 1e-9)
    }

    @Test
    fun `past the old band the new glow is the stronger of the two`() {
        // This is the "diffuses inward" half of the request: fainter at the
        // rim, but present where the old one had already stopped.
        for (d in listOf(9.0, 10.0, 12.0, 15.0)) {
            assertTrue("at ${d}dp the new glow should still be there", alphaAt(d) > oldAlphaAt(d))
        }
    }

    @Test
    fun `total colour laid down is less than before`() {
        // Integrate alpha over depth — a wider band must not add up to MORE
        // purple just because it covers more of the tile.
        fun integral(f: (Double) -> Double, to: Double): Double {
            var s = 0.0
            var x = 0.0
            while (x < to) { s += f(x) * 0.01; x += 0.01 }
            return s
        }
        val old = integral(::oldAlphaAt, 10.0)
        val new = integral({ alphaAt(it) }, 32.0)
        assertTrue("new=$new must be below old=$old", new < old)
    }

    @Test
    fun `the band never collapses the rect on the tile it draws on`() {
        // The tile is 100x65dp; a band reaching past half the short side would
        // invert the rect. Production also guards this at runtime because the
        // size comes from the layout.
        val band = 32.0
        assertTrue("band must stay clear of the 32.5dp half-height", band < 65.0 / 2)
        assertTrue("production keeps the degenerate-rect guard", src.contains("if (w <= 0f || h <= 0f) break"))
    }

    @Test
    fun `strokes overlap so the corners do not band`() {
        // Rings are `step` apart on the axes but step*sqrt(2) apart on the
        // diagonals. Equal-width strokes leave a gap there; the 10dp band was
        // opaque enough to hide it, a 22dp one is not.
        val step = 0.5
        val diagonalSpacing = step * Math.sqrt(2.0)
        assertTrue("a 1x-width stroke would gap on the diagonal", step < diagonalSpacing)
        assertTrue("a 2x-width stroke covers it", step * 2 > diagonalSpacing)
        assertTrue("production draws at 2x the spacing", src.contains("style = Stroke(width = step * 2f)"))
    }

    @Test
    fun `the corner radius is inset by subtraction, not scaled`() {
        // A rounded rect inset by more than its radius genuinely has square
        // corners — that is geometry, not a defect. Scaling the radius
        // proportionally instead was tried and bulges the corner outward by up
        // to 5dp mid-band, drawing visible arcs.
        assertTrue(
            "radius must stay (radius - inset), floored at 0",
            src.contains("CornerRadius((radius - inset).coerceAtLeast(0f))"),
        )
    }

    @Test
    fun `the production constants are the ones tested here`() {
        val draw = src.substringAfter("val subAgentGlow = helperAccent()")
        assertTrue("band", draw.contains("val band = 32.dp.toPx()"))
        assertTrue("step", draw.contains("val step = 0.5.dp.toPx()"))
        assertTrue("peak alpha + cubic falloff", draw.contains("0.11f * fade * fade * fade"))
    }

    @Test
    fun `the glow is drawn inside the clip, after the content`() {
        // What makes it an INNER glow: drawn after drawContent() and inside
        // the tile's clip, so the preview stays legible and the rounded
        // corners are not smeared square.
        val i = src.indexOf("val subAgentGlow = helperAccent()")
        val clip = src.lastIndexOf(".clip(thumbnailShape)", i.coerceAtLeast(0) + 4000)
        val draw = src.indexOf(".drawWithContent {", i)
        assertTrue("clip must precede the glow draw", clip in 1 until draw)
        val body = src.substring(draw, draw + 600)
        assertTrue("content is drawn first", body.indexOf("drawContent()") < body.indexOf("subAgentLive"))
    }
}
