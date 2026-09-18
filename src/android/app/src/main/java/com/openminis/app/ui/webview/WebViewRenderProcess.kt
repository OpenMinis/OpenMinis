package com.openminis.app.ui.webview

import android.util.Log
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView

/**
 * [GH#341] Chromium's contract: when a WebView's renderer process dies, the whole
 * application is killed unless *every* affected WebViewClient returns true from
 * onRenderProcessGone(). The framework default returns false, which is what produced:
 *
 *   Fatal signal 5 (SIGTRAP), code -6 (SI_TKILL) in tid 9960
 *   Abort message: '[FATAL:crashpad_client_linux.cc(667)] Render process (22277)'s
 *   crash wasn't handled by all associated webviews, triggering application crash.'
 *
 * A renderer can die for reasons that have nothing to do with the app (OOM in the
 * sandboxed process, a Chromium bug, a dying GPU process), so this is not an
 * exceptional path — it is a normal one that must be survivable.
 *
 * Call this from every WebViewClient.onRenderProcessGone override. The dead WebView
 * is torn down (a WebView whose renderer died cannot be reused) and true is returned
 * so the framework leaves the rest of the app alive.
 */
fun WebView.handleRenderProcessGone(detail: RenderProcessGoneDetail?, tag: String): Boolean {
    Log.e(tag, "render process gone (crashed=${detail?.didCrash()}) — tearing down WebView")
    try {
        (parent as? ViewGroup)?.removeView(this)
        stopLoading()
        destroy()
    } catch (t: Throwable) {
        Log.w(tag, "teardown after renderer death failed", t)
    }
    return true
}
