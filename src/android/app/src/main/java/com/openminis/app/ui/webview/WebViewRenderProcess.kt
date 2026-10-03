package com.openminis.app.ui.webview

import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import com.openminis.app.logging.AppLogger

/**
 * [T-android-webview-render-process-gone] (GH#341) Shared handling for a dead
 * WebView renderer.
 *
 * Android's contract: when the renderer process dies, every WebView attached to
 * it gets `onRenderProcessGone`, and a client that does not override it and
 * return `true` takes the whole app down with
 * `Fatal signal 5 (SIGTRAP) ... Render process's crash wasn't handled by all
 * associated webviews`. The renderer dies for reasons outside our control —
 * most often the OS reclaiming it under memory pressure — so this is a normal
 * condition to survive, not an error to propagate.
 *
 * This helper deliberately does NOT destroy anything. Every WebView in this app
 * is owned by something that already knows how to tear it down properly, and
 * those routines do strictly more than `destroy()`:
 *
 *   - [com.openminis.app.ui.chat.KatexWebViewPool.releaseWebView] destroys AND
 *     nulls the singleton + clears `isReady`, so the next render rebuilds it.
 *   - [com.openminis.app.ui.preview.WebViewHolder.destroy] detaches, stops
 *     loading and blanks the page first, and is documented safe to call twice.
 *   - `KaTeXView`'s `onRelease` stamps `KATEX_WEBVIEW_RELEASED_TAG` so an
 *     already-queued capture runnable does not touch the dead view.
 *
 * A generic "destroy and return true" would bypass all of that and leave the
 * owner holding a destroyed object — turning a crash into a permanently blank
 * formula, preview, or browser, which is not obviously an improvement. So the
 * shared part is only what is genuinely common (diagnostics + the return
 * value); the call site routes to its own cleanup entry point.
 *
 * NOTE the renderer is shared: by default every WebView in the process uses the
 * same one, so a single death fans out to ALL of them. Handling one is not
 * enough — every client must override, which is why all eight sites do.
 */
object WebViewRenderProcess {

    private const val TAG = "WebViewRenderProcess"

    /**
     * Log the death and report it handled.
     *
     * @param owner short label for the call site, so the log says which surface
     *   was affected when several report the same renderer dying.
     * @param detail nullable: the parameter is non-null on API 26+, but it
     *   arrives from the framework and callers pass it straight through, so it
     *   is treated as optional rather than trusted.
     * @return always `true` — "we handled it, do not kill the app".
     */
    fun handle(owner: String, detail: RenderProcessGoneDetail?): Boolean {
        val didCrash = detail?.didCrash()
        val priority = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            detail?.rendererPriorityAtExit()
        } else {
            null
        }
        // `didCrash == false` is the common, benign case: the system killed the
        // renderer to reclaim memory. `true` means it actually crashed.
        AppLogger.warning(
            TAG,
            "renderer gone owner=$owner didCrash=$didCrash priorityAtExit=$priority — " +
                "handled, host process kept alive",
        )
        return true
    }
}

/**
 * Convenience for the common shape at a call site that has nothing of its own
 * to clean up (the Compose `AndroidView` cases, where the next recomposition
 * rebuilds the WebView anyway).
 */
fun WebView.onRenderProcessGoneHandled(owner: String, detail: RenderProcessGoneDetail?): Boolean =
    WebViewRenderProcess.handle(owner, detail)
