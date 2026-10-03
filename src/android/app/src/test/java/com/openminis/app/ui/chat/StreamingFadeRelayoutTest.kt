package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-stream-fade-relayout] The streamed-text fade must never be applied
 * by rebuilding the live block's AnnotatedString per frame.
 *
 * Measured on a Pixel 4a while streaming one 14k-char paragraph: the fade
 * runs 350 ms + up to 300 ms stagger per publish and publishes arrive every
 * 500 ms, so `FadeController.overlay()` rebuilt the whole string with alpha
 * spans on EVERY vsync, `Text` re-laid-out and re-recorded the entire
 * paragraph each frame, record(draw) climbed 8→18 ms as the block grew, and
 * ~93 % of frames missed budget for the whole stream. The fix paints the fade
 * as a mask over the fading words inside its own graphics layer and reads the
 * per-frame alpha ONLY from that draw lambda.
 *
 * These are source-fact assertions (the composable cannot be instantiated in
 * a JVM test); they fail the build if the per-frame read migrates back into
 * composition or the layer sandwich that isolates the re-record is removed.
 */
class StreamingFadeRelayoutTest {

    private fun src(rel: String): String {
        val f = File("src/main/java/com/openminis/app/ui/chat/$rel")
        assertTrue("missing source: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val md by lazy { src("StreamingMarkdownText.kt") }
    private val fade by lazy { src("StreamingFade.kt") }

    @Test
    fun `the per-frame AnnotatedString overlay is gone`() {
        assertFalse("FadeController.overlay() rebuilt the whole string every frame", fade.contains("fun overlay("))
        assertFalse("MdText must not derive its Text from a per-frame overlay", md.contains("effectiveText"))
    }

    @Test
    fun `fade alphas are consumed from a draw lambda, not composition`() {
        val idx = md.indexOf("fadeController.forEachActive")
        assertTrue("MdText must consume the fade via forEachActive", idx >= 0)
        // The nearest enclosing modifier lambda before the call must be drawWithContent.
        val before = md.substring((idx - 800).coerceAtLeast(0), idx)
        assertTrue("forEachActive must be called inside drawWithContent", before.lastIndexOf(".drawWithContent {") > before.lastIndexOf("Text("))
        // `return@forEachActive` labels inside the lambda also contain the name;
        // count the receiver call, which is the actual consumer.
        assertEquals("exactly one consumer of forEachActive in MdText", 1, Regex("fadeController\\.forEachActive").findAll(md).count())
    }

    @Test
    fun `the mask lives in its own layer above the text`() {
        val start = md.indexOf("val fadeMaskModifier")
        assertTrue(start >= 0)
        val block = md.substring(start, md.indexOf("} else Modifier", start))
        // outer layer → mask draw → inner layer (text). Order is the whole point.
        val outer = block.indexOf(".graphicsLayer()")
        val draw = block.indexOf(".drawWithContent {")
        val inner = block.indexOf(".graphicsLayer()", outer + 1)
        assertTrue("outer graphicsLayer must precede drawWithContent", outer in 0 until draw)
        assertTrue("an inner graphicsLayer must follow drawWithContent", inner > draw)
        assertTrue("mask draws ABOVE the text: drawContent() first", block.indexOf("drawContent()") in (draw + 1) until block.indexOf("forEachActive"))
    }

    @Test
    fun `the mask modifier is applied to the Text and only when fading`() {
        assertTrue(md.contains(".then(fadeMaskModifier)"))
        assertTrue("non-fading blocks must pay nothing", md.contains("val fadeMaskModifier = if (fadeController != null) {"))
    }
}
