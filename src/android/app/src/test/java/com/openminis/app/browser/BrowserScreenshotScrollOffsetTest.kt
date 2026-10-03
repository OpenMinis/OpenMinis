package com.openminis.app.browser

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-browser-screenshot-scroll-offset] Screenshots must show the part
 * of the page the user is actually looking at.
 *
 * Reported by a user driving the built-in browser: after any scroll, the
 * capture was shifted down by exactly `scrollY` — a blank band on top, the
 * bottom cropped — and once the scroll passed one viewport height the frame
 * came back 100% white. Measured on a Pixel 6 before the fix, at CSS
 * scrollY=300: 788 physical px of white (788 / 2.625 density = 300.2 CSS px,
 * i.e. exactly scrollY) with the Y300 band sitting at viewport y=300 instead
 * of y=0; at the clamped scrollY=1085, 100.0% white with zero non-white
 * sample points.
 *
 * Cause: `captureWebViewBitmap` drew straight into its own Bitmap with
 * `webView.draw(canvas)`. `View.draw(Canvas)` does not compensate scroll —
 * the PARENT does, in `draw(Canvas, ViewGroup, long)` via
 * `canvas.translate(mLeft - sx, mTop - sy)` — so drawing without a parent
 * skips that step entirely.
 *
 * `captureWebViewBitmap` needs a live WebView and a real renderer, so it
 * cannot run on the JVM. These are source-text assertions in the style of
 * `ShellExecutionStrategyTest` / `A11yScreenshotPathTest`, pinning the parts
 * of the contract that a future edit could silently undo. The behavioural
 * proof is the on-device measurement recorded above and in the commit.
 */
class BrowserScreenshotScrollOffsetTest {

    private val src: String by lazy {
        val f = File("src/main/java/com/openminis/app/browser/BrowserUseManager.kt")
        assertTrue("missing ${f.absolutePath}", f.exists())
        f.readText()
    }

    /** Source with comment lines stripped — prose must not satisfy a code assertion. */
    private val code: String by lazy {
        src.lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
    }

    private val captureBody: String by lazy {
        code.substringAfter("private suspend fun captureWebViewBitmap(").substringBefore("\n    private fun saveBitmapToFile")
    }

    @Test
    fun `the canvas is translated before the webview is drawn`() {
        val translateAt = captureBody.indexOf("canvas.translate(")
        val drawAt = captureBody.indexOf("webView.draw(canvas)")
        assertTrue("capture must compensate the scroll offset", translateAt >= 0)
        assertTrue("webView.draw(canvas) must still be the draw call", drawAt >= 0)
        assertTrue(
            "the translate must happen BEFORE the draw, or it compensates nothing",
            translateAt < drawAt,
        )
    }

    @Test
    fun `the offset is probed from JS, not taken from the View alone`() {
        // The pool WebView is detached whenever the browser sheet is closed,
        // and a detached View never receives the scroll pass that would update
        // its own scrollX/scrollY. Measured on device: after
        // window.scrollTo(0,300) the capture logged `scrollY=0 attached=false`
        // while the bitmap was still offset by 300 CSS px, because the scroll
        // lives in Chromium's compositor. A translate driven only by
        // webView.scrollY would move by zero there and fix nothing.
        assertTrue(
            "must read the page scroll offset from the renderer",
            code.contains("private suspend fun readCssScrollOffset()"),
        )
        assertTrue(
            "and the capture must use it",
            captureBody.contains("readCssScrollOffset()"),
        )
        assertTrue(
            "the CSS offset must be scaled to physical px by density",
            captureBody.contains("cssScroll.second * density"),
        )
    }

    @Test
    fun `the two scroll sources are selected between, never summed`() {
        // They describe ONE quantity. Adding them double-counts: measured on
        // device while the sheet was mounted as `css=300.19 view=788
        // offset=1576` (788 = 300.19 x 2.625), scrolling the capture twice as
        // far as the page actually was. This test exists because that bug was
        // written, shipped to the device, and caught by the offset log.
        assertTrue(
            "view scroll must win when populated",
            captureBody.contains("if (webView.scrollY != 0) webView.scrollY.toFloat() else"),
        )
        assertFalse(
            "the two sources must never be added together",
            captureBody.contains("* density + webView.scrollY") ||
                captureBody.contains("* density + webView.scrollX"),
        )
    }

    @Test
    fun `the scroll probe cannot stall a capture forever`() {
        // A wedged renderer is exactly when a screenshot is most wanted, so the
        // probe is bounded and degrades to the uncompensated (pre-fix) capture
        // rather than returning nothing.
        assertTrue(code.contains("SCROLL_PROBE_TIMEOUT_MS"))
        assertTrue(
            "the probe must be wrapped in a timeout",
            code.substringAfter("private suspend fun readCssScrollOffset()")
                .substringBefore("\n    private")
                .contains("withTimeoutOrNull(SCROLL_PROBE_TIMEOUT_MS)"),
        )
    }

    @Test
    fun `full page capture waits for a repaint after stretching`() {
        // applyViewport only measure/layouts the view. Without a gap the draw
        // runs in the same main-thread pass and can read back a region that was
        // never rendered, which is why --full-page shots were partially blank
        // below the first viewport.
        val fullPage = code.substringAfter("if (fullPage) {").substringBefore("val bitmap = try {")
        val applyAt = fullPage.lastIndexOf("applyViewport(savedW, cssCappedHeight)")
        val delayAt = fullPage.indexOf("delay(FULL_PAGE_REPAINT_DELAY_MS)")
        assertTrue("full-page must stretch the viewport", applyAt >= 0)
        assertTrue("and then wait for the repaint", delayAt >= 0)
        assertTrue("the wait must come after the stretch", applyAt < delayAt)
        assertTrue(code.contains("FULL_PAGE_REPAINT_DELAY_MS"))
    }
}
