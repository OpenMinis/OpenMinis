package com.openminis.app.ui.markdown

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-katex-webview-release] Source guard: the per-formula offscreen
 * WebView in `KaTeXRenderView` must be destroyed when the composable leaves.
 *
 * `KaTeXRenderView` builds a fresh `WebView` per formula inside an
 * `AndroidView` factory. Its `AndroidView` call passed only `factory`, so
 * leaving composition dropped the Java reference and never called
 * `destroy()`; a WebView's renderer state is native memory that only
 * `destroy()` (or eventual finalization) reclaims, so every formula in a
 * markdown document leaked a whole KaTeX page. The chat list is unaffected —
 * it renders math through the single pooled WebView in `KatexWebViewPool` —
 * but the file preview and skills screens go through this path.
 *
 * A JVM test cannot instantiate a WebView (no Robolectric in this module), so
 * this pins the shape of the fix the way `InAppThemeSourceGuardTest` does: the
 * `onRelease` hook must exist and must call `destroy()`. Someone refactoring
 * the `AndroidView` call and dropping the parameter fails here instead of
 * shipping the leak a second time.
 */
class KaTeXViewReleaseGuardTest {

    private val source: String by lazy {
        val f = File("src/main/java/com/openminis/app/ui/markdown/KaTeXView.kt")
        assertTrue(
            "KaTeXView.kt not found — this test locates sources relative to the " +
                "module dir (cwd=${File(".").absolutePath})",
            f.isFile,
        )
        f.readText()
    }

    @Test
    fun `the per-formula WebView is released through AndroidView onRelease`() {
        assertTrue(
            "KaTeXRenderView's AndroidView must pass an onRelease hook",
            Regex("""onRelease\s*=\s*\{""").containsMatchIn(source),
        )
    }

    @Test
    fun `onRelease destroys the WebView`() {
        // Anchor on the CALL, not the first mention — the constant's KDoc
        // names `onRelease` long before the AndroidView invocation.
        val idx = Regex("""onRelease\s*=\s*\{""").find(source)?.range?.first ?: -1
        assertTrue("onRelease hook not present", idx >= 0)
        val body = source.substring(idx, minOf(source.length, idx + 600))
        assertTrue(
            "onRelease must call destroy() on the WebView it receives",
            body.contains(".destroy()"),
        )
    }

    @Test
    fun `a capture queued before release is skipped`() {
        // The 100 ms capture runnable may still fire after the view is
        // destroyed; drawing then yields a blank bitmap that would be cached
        // under the formula's key. The release marker is what prevents that.
        assertTrue(source.contains("KATEX_WEBVIEW_RELEASED_TAG"))
        assertTrue(
            "the capture path must check the release marker before drawing",
            Regex("""tag\s*==\s*KATEX_WEBVIEW_RELEASED_TAG""").containsMatchIn(source),
        )
    }
}
