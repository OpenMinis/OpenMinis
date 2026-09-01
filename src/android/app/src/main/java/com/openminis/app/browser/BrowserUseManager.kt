package com.openminis.app.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Manages a single Android WebView for browser automation.
 * Mirrors iOS BrowserUseManager.
 */
class BrowserUseManager(
    val webView: WebView,
    profile: UserAgentProfile = UserAgentProfile.MOBILE_CHROME,
    /**
     * [T-android-minis-url-session-scope] Which chat session's sandbox a
     * `minis://` URL should resolve against, or null when unknown.
     *
     * A LAMBDA, not a value: tabs are created before `BrowserTabPool.setSession`
     * runs, so a snapshot taken at construction time would be permanently null
     * for the first tab. Reading it lazily at intercept time gets whatever the
     * pool knows when the request actually fires.
     */
    private val sessionIdProvider: () -> String? = { null },
    /** App context for the session-scoped path resolver. Null disables it. */
    private val appContext: android.content.Context? = null,
) {
    companion object {
        private const val TAG = "BrowserUseManager"
        private const val NAVIGATION_TIMEOUT_MS = 30_000L
        private const val SCREENSHOT_QUALITY = 80        // Explicit screenshot action (iOS: 0.8)
        private const val SNAPSHOT_QUALITY = 70          // Auto-snapshot after visual-change actions (iOS: 0.7)
        private const val DEFAULT_DOM_STABLE_TIMEOUT_MS = 5_000

        /**
         * Cap full_page screenshot stretched viewport at 32768 px. Above this,
         * Bitmap.createBitmap risks OOM (e.g. 32768 × ~1130 px × 4 B/ARGB ≈ 144 MB
         * at desktop 1280 CSS × 2.75 density). Pages taller than the cap are
         * truncated and the metadata exposes `truncated:true` + the original
         * scrollHeight so the agent can scroll-then-stitch if it needs more.
         */
        private const val MAX_FULL_PAGE_HEIGHT_PX = 32768

        /**
         * Minimal HTML used in place of `about:blank` when a fresh tab
         * needs a page refresh (e.g. `set_viewport` before any navigation).
         * `<meta name="viewport" content="width=device-width">` makes
         * `window.innerWidth` track the container we just laid out, instead
         * of Android WebView's hardcoded 980px fallback for blank pages.
         * Loaded via `loadDataWithBaseURL` so WebView accepts raw HTML
         * without URL encoding.
         */
        private const val BLANK_PAGE_HTML =
            "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width\"></head><body></body></html>"
        // [T-android-domstable-min-budget] C5: with the old 200ms floor and a
        // 200ms poll interval the loop could only ever take ONE sample
        // (first sample never matches lastSize=-1, then the delay exhausts
        // the budget) — wait_for_dom_stable failed on every page at the
        // minimum. 1000ms fits >=4 samples so the smallest budget can
        // actually observe two equal readings.
        private const val MIN_DOM_STABLE_TIMEOUT_MS = 1_000
        private const val MAX_DOM_STABLE_TIMEOUT_MS = 60_000

        @SuppressLint("SetJavaScriptEnabled")
        fun configureWebView(webView: WebView, profile: UserAgentProfile, customUA: String? = null) {
            // [T-android-browser-blank] The browser lives inside a Material3
            // ModalBottomSheet, which hosts content in its own secondary
            // window. On some OEM GPUs the WebView's hardware draw functor
            // fails to composite in that window — the page loads (title/URL
            // update normally) but the content area stays black or white.
            // Rendering the WebView through its own hardware layer texture is
            // the standard workaround for WebView-in-dialog blank rendering.
            webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
                setSupportMultipleWindows(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                val ua = customUA ?: profile.userAgentString
                if (ua != null) userAgentString = ua
            }
            // T-android-webview-v3-port: enable first- + third-party cookies.
            // WebView ships with third-party cookies disabled by default; that
            // breaks hCaptcha / Turnstile / reCAPTCHA flows where the
            // verification widget lives in a cross-origin iframe and posts its
            // token back to the parent through a Set-Cookie round-trip. The
            // agent-driven browser is the user's surrogate; matching Chrome's
            // default unblocks the same captcha flows the user would clear in
            // a real browser tab. First-party setAcceptCookie defaults to
            // true on every Android version we support — calling it
            // explicitly anyway so the intent is grep-able.
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
        }
    }

    private val _currentURL = MutableStateFlow("")
    val currentURL: StateFlow<String> = _currentURL.asStateFlow()

    /**
     * [T-android-js-dialogs-256] JS dialogs this tab intercepted but did not
     * show, drained into the next tool result so the model learns the page
     * tried to open one. Per-tab (the manager is per-tab) and in-memory only,
     * like the other diagnostics here. See [InterceptedDialogQueue].
     */
    private val dialogQueue = InterceptedDialogQueue()

    private val _pageTitle = MutableStateFlow("")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    private var currentProfile: UserAgentProfile = profile

    /** Callback for window.open / target="_blank" — TabPool hooks this. */
    var onNewWindow: ((Message) -> Unit)? = null

    /** Callback for window.close — TabPool hooks this. */
    var onCloseWindow: (() -> Unit)? = null

    /**
     * Callback when the page triggers a file download over http/https
     * (Content-Disposition attachment, <a download>, unrenderable MIME type).
     * TabPool hooks this and streams the URL into the session workspace.
     */
    var onDownloadStart: ((url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, contentLength: Long) -> Unit)? = null

    /**
     * Callback delivering the bytes of a blob: download. blob: URLs only exist
     * inside the page's JS context, so [fetchBlobDownload] reads them via an
     * injected FileReader and hands the decoded bytes back through the bridge.
     */
    var onBlobDownloadData: ((data: ByteArray, filename: String, mimeType: String?) -> Unit)? = null

    /** Deferred for awaiting navigation completion. */
    private var navigationDeferred: CompletableDeferred<Unit>? = null

    /** Screenshots directory. */
    private val screenshotsDir: File by lazy {
        File(webView.context.cacheDir, "browser_screenshots").also { it.mkdirs() }
    }

    /** Deferred used by executeJS to receive results from async scripts via JS bridge. */
    private var asyncJsDeferred: CompletableDeferred<String>? = null

    /** JavaScript interface for async script result callbacks. */
    private val jsBridge = object {
        @JavascriptInterface
        fun resolve(result: String) {
            asyncJsDeferred?.complete(result)
        }

        @JavascriptInterface
        fun reject(error: String) {
            asyncJsDeferred?.complete("{\"error\":${JSONObject.quote(error)}}")
        }

        /**
         * Receives a blob: download read as a data URL by [fetchBlobDownload]'s
         * injected FileReader. Runs on the WebView's JavaBridge thread — file
         * I/O downstream is fine, but don't touch the WebView from here.
         */
        @JavascriptInterface
        fun saveBlobDownload(dataUrl: String, filename: String) {
            val comma = dataUrl.indexOf(',')
            if (comma < 0 || !dataUrl.startsWith("data:")) {
                Log.w(TAG, "blob download: malformed data URL (len=${dataUrl.length})")
                return
            }
            val header = dataUrl.substring(5, comma)
            val mime = header.substringBefore(';').ifEmpty { null }
            val bytes = try {
                if (header.endsWith(";base64")) {
                    android.util.Base64.decode(dataUrl.substring(comma + 1), android.util.Base64.DEFAULT)
                } else {
                    // Non-base64 data: URL — payload is percent-encoded text.
                    java.net.URLDecoder.decode(dataUrl.substring(comma + 1), "UTF-8")
                        .toByteArray(Charsets.UTF_8)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "blob download: payload decode failed: ${t.message}")
                return
            }
            Log.i(TAG, "blob download decoded: $filename (${bytes.size} bytes, mime=$mime)")
            onBlobDownloadData?.invoke(bytes, filename, mime)
        }

        @JavascriptInterface
        fun blobDownloadError(error: String) {
            Log.w(TAG, "blob download failed in page JS: $error")
        }
    }

    init {
        configureWebView(webView, profile)
        webView.addJavascriptInterface(jsBridge, "__minis__")
        setupWebViewClient()
        setupWebChromeClient()
        // Intercept page-triggered downloads (Content-Disposition attachment,
        // <a download>, unrenderable MIME types). Without a listener, WebView
        // silently drops these — the user taps "download" and nothing happens.
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            Log.i(TAG, "onDownloadStart: ${url.take(120)} mime=$mimetype len=$contentLength")
            when {
                // blob: object URLs only exist inside the page — read via JS.
                url.startsWith("blob:") -> fetchBlobDownload(url, contentDisposition, mimetype)
                // data: URLs carry the payload inline — decode directly
                // (java.net.URL can't fetch them in the pool's downloader).
                url.startsWith("data:") -> {
                    val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                    jsBridge.saveBlobDownload(url, name)
                }
                else -> onDownloadStart?.invoke(url, userAgent, contentDisposition, mimetype, contentLength)
            }
        }
        // Track the on-screen WebView width so applyViewport() can compute a
        // shrink-to-fit initial scale. The synthetic measure/layout pass that
        // applyViewport performs to make `window.innerWidth` match the agent
        // viewport is independent of the container the AndroidView is hosted
        // in — without this listener we have no way to learn the container's
        // visible width, and oversize CSS viewports (e.g. 1280×800 on a
        // ~1080px-wide phone) would render off-screen to the right.
        webView.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = r - l
            if (w > 0 && w != lastKnownContainerWidthPx) {
                lastKnownContainerWidthPx = w
                // Re-apply the initial scale if we already have an active
                // viewport — covers rotation, sheet resize, container layout
                // settling after the WebView is first parented.
                lastAppliedViewport?.let { (vw, _) -> applyShrinkToFit(vw) }
            }
        }
    }

    /**
     * Read a blob: URL from inside the page's JS context and deliver its bytes
     * through the `__minis__.saveBlobDownload` bridge. blob: object URLs are
     * scoped to the page — they cannot be fetched from native code, so this
     * injected fetch + FileReader round-trip is the only way to get the data.
     */
    private fun fetchBlobDownload(blobUrl: String, contentDisposition: String?, mimeType: String?) {
        val guessedName = android.webkit.URLUtil.guessFileName(blobUrl, contentDisposition, mimeType)
        val js = """
            (function() {
                fetch(${JSONObject.quote(blobUrl)})
                    .then(function(r) { return r.blob(); })
                    .then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            __minis__.saveBlobDownload(reader.result, ${JSONObject.quote(guessedName)});
                        };
                        reader.onerror = function() { __minis__.blobDownloadError('FileReader error'); };
                        reader.readAsDataURL(blob);
                    })
                    .catch(function(e) { __minis__.blobDownloadError(String(e)); });
            })();
        """.trimIndent()
        webView.post { webView.evaluateJavascript(js, null) }
    }

    /**
     * Latest on-screen width (in physical pixels) of the AndroidView hosting
     * this WebView. Updated by the layout listener installed in [init];
     * 0 until the WebView is parented and laid out for the first time.
     */
    private var lastKnownContainerWidthPx: Int = 0

    /**
     * Compute and install a `setInitialScale` so a page authored at
     * [cssWidth] CSS pixels fits inside the visible WebView container. Called
     * after every [applyViewport] and on every container size change.
     *
     * `setInitialScale(percent)` is sticky — it applies on the next page
     * load. The tab pool's `applyViewportToAllTabs()` already reloads each
     * tab after viewport changes, so the scale is picked up on that reload.
     * Plain navigation between pages reuses whatever scale was last set.
     *
     * Passing 0 restores WebView's default behavior (use page's own scale).
     * We pass 0 whenever the CSS viewport already fits — no point shrinking
     * a 412-wide viewport on a 1080-wide container.
     */
    private fun applyShrinkToFit(cssWidth: Int) {
        val containerPx = lastKnownContainerWidthPx
        if (containerPx <= 0 || cssWidth <= 0) return
        val density = webView.resources.displayMetrics.density
        val cssWidthPx = (cssWidth * density).toInt()
        var scalePct = if (cssWidthPx > containerPx) {
            ((containerPx.toLong() * 100) / cssWidthPx).toInt().coerceAtLeast(1)
        } else {
            0 // CSS viewport already fits — let WebView use its default scale.
        }
        // [T-android-browser-blank] Guard against a corrupted container width
        // (e.g. a synthetic 1px layout leaking into the layout listener): a
        // microscopic sticky initial scale renders the next page load
        // effectively blank. No legitimate shrink-to-fit is below ~10%
        // (desktop 1280 CSS on a 360dp phone is ~28%) — fall back to the
        // WebView default instead.
        if (scalePct in 1..9) {
            Log.w(TAG, "applyShrinkToFit: implausible scale $scalePct% (container=${containerPx}px, css=${cssWidthPx}px) — using default")
            scalePct = 0
        }
        webView.setInitialScale(scalePct)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewClient() {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val urlStr = request.url?.toString()
                // T234: Google permanently disallows WebView for sign-in /
                // OAuth. Hand any auth-domain navigation to Chrome Custom
                // Tab so the user can complete login in their real Chrome
                // session instead of hitting 403 disallowed_useragent.
                if (GoogleAuthRouter.shouldRouteExternally(urlStr)) {
                    if (urlStr != null) {
                        GoogleAuthRouter.openInCustomTab(view.context, urlStr)
                    }
                    return true
                }
                // T134: route intent://, market://, tel:, mailto:, … out
                // of the WebView so they reach the matching app instead of
                // surfacing as ERR_UNKNOWN_URL_SCHEME.
                //
                // [T-android-user-initiated-scheme-dispatch] AGENT_BACKGROUND:
                // this WebView is pool-managed and never rendered to the user,
                // so any navigation here came from the PAGE, not a tap. Keep
                // T318's block — otherwise a page the agent visits can throw
                // the user into another app mid-task.
                return com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                    .handle(
                        view.context,
                        request.url,
                        com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                            .Origin.AGENT_BACKGROUND,
                    )
            }

            override fun onPageFinished(view: WebView, url: String?) {
                _isLoading.value = false
                _currentURL.value = url ?: ""
                _pageTitle.value = view.title ?: ""
                _canGoBack.value = view.canGoBack()
                _canGoForward.value = view.canGoForward()
                navigationDeferred?.complete(Unit)
                navigationDeferred = null
                // Record in browser history
                val histUrl = url ?: ""
                val histTitle = view.title ?: ""
                if (histUrl.isNotEmpty() && histUrl != "about:blank") {
                    BrowserHistoryStore.getInstance(view.context).record(histUrl, histTitle)
                }
                // T-webview-popup-d3c6e10f (Issue 1): after the pool WebView's
                // setInitialScale settles, force a JS `resize` event so
                // `position:fixed` elements (sticky headers, cookie banners,
                // floating chat) recompute against the post-scale visual
                // viewport instead of the pre-scale layout viewport. Without
                // this, fixed-positioned UI on some sites drifts off the
                // visible region after the synthetic measure+layout pass.
                view.postDelayed({
                    view.evaluateJavascript(
                        "window.dispatchEvent(new Event('resize'));", null,
                    )
                }, 80)
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    _isLoading.value = false
                    Log.e(TAG, "Navigation error: ${error.description}")
                    navigationDeferred?.complete(Unit)
                    navigationDeferred = null
                }
            }

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                val url = request.url ?: return null
                if (url.scheme != "minis") return null
                return interceptMinisURL(url)
            }
        }
    }

    /** Resolve minis:// URLs to local workspace files. */
    private fun interceptMinisURL(uri: android.net.Uri): android.webkit.WebResourceResponse? {
        try {
            // minis://workspace/foo.html → /var/minis/workspace/foo.html, then
            // resolve to the host file via PRoot bind mounts (per-session
            // workspace lives under filesDir/minis-sessions/<sid>/workspace/).
            val host = uri.host ?: return null
            val path = uri.path ?: ""
            val linuxPath = "/var/minis/$host$path"

            // [T-android-minis-url-session-scope] Resolve against THIS session
            // first, and only then fall back to the global bind-mount map.
            //
            // `workspace`, `attachments`, `offloads` and `browser` live under
            // `minis-sessions/<sid>/`, but the global map only gains a
            // `/var/minis/workspace` entry while some session's PRoot shell is
            // running — and it is last-writer-wins across sessions. So the old
            // global-only lookup failed in two ordinary situations: the shell
            // had exited (path fell through to the rootfs copy of
            // /var/minis/workspace, which is empty), or another session had
            // booted more recently and the mount pointed at ITS workspace.
            //
            // Measured on a GEM-W09: `minis://workspace/jump-jump.html` 404'd
            // while the file sat intact at 5969 bytes in
            // minis-sessions/145d6883…/workspace/. The rootfs directory the
            // resolver actually reached contained nothing but `.` and `..`.
            // That is also why the failure looked intermittent and looked like
            // it depended on subdirectory depth — it depends on neither, only
            // on whether the global mount happens to point at the right
            // session at that moment.
            val sessionId = sessionIdProvider()
            val ctx = appContext
            val localFile = if (sessionId != null && ctx != null) {
                com.openminis.app.sandbox.PRootKernel
                    .resolveSessionHostPath(sessionId, linuxPath, ctx)
                    ?.takeIf { it.isFile }
                    ?: com.openminis.app.sandbox.PRootKernel.resolveHostPath(linuxPath)
            } else {
                com.openminis.app.sandbox.PRootKernel.resolveHostPath(linuxPath)
            }
            if (localFile == null || !localFile.exists() || !localFile.isFile) {
                return android.webkit.WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                    emptyMap(), "File not found: $host$path".byteInputStream())
            }
            val mimeType = guessMimeType(localFile.name)
            // For HTML mainframe responses, inject a `<meta viewport>` matching
            // the agent's session viewport when the page doesn't declare one.
            // Without this, Android WebView falls back to a hardcoded 980 CSS
            // px width regardless of the WebView's measured size, making
            // `set_viewport` look like a no-op for `minis://` HTML pages.
            val stream = if (mimeType == "text/html" && lastAppliedViewport != null) {
                ensureMetaViewport(localFile.readBytes(), lastAppliedViewport!!.first)
            } else {
                localFile.inputStream()
            }
            return android.webkit.WebResourceResponse(mimeType, "UTF-8", 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*"),
                stream)
        } catch (e: Exception) {
            Log.w(TAG, "minis:// intercept error: ${e.message}")
            return null
        }
    }

    /**
     * If the HTML lacks a `<meta name="viewport">`, splice one in matching
     * the agent's CSS-px viewport. Leaves pages that already declare a
     * viewport untouched so author intent (e.g. `width=1200`) wins.
     */
    private fun ensureMetaViewport(html: ByteArray, cssWidth: Int): java.io.InputStream {
        val text = String(html, Charsets.UTF_8)
        if (text.contains("name=\"viewport\"", ignoreCase = true) ||
            text.contains("name='viewport'", ignoreCase = true)) {
            return text.toByteArray(Charsets.UTF_8).inputStream()
        }
        // user-scalable=yes is the default but state it explicitly so a
        // future change to WebView defaults can't silently disable
        // pinch-zoom on the agent's auto-injected viewport.
        val meta = "<meta name=\"viewport\" content=\"width=$cssWidth, initial-scale=1.0, user-scalable=yes\">"
        val headIdx = text.indexOf("<head", ignoreCase = true).takeIf { it >= 0 }?.let {
            text.indexOf('>', it).takeIf { gt -> gt >= 0 }?.plus(1)
        }
        val rewritten = if (headIdx != null) {
            text.substring(0, headIdx) + meta + text.substring(headIdx)
        } else {
            // No <head>: prepend the meta so it's still parsed before body.
            "$meta$text"
        }
        return rewritten.toByteArray(Charsets.UTF_8).inputStream()
    }

    private fun guessMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "txt", "md" -> "text/plain"
            "xml" -> "text/xml"
            else -> "application/octet-stream"
        }
    }

    private fun setupWebChromeClient() {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                _pageTitle.value = title ?: ""
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                onNewWindow?.invoke(resultMsg)
                return onNewWindow != null
            }

            override fun onCloseWindow(window: WebView) {
                onCloseWindow?.invoke()
            }

            // [T-android-js-dialogs-256] Answer JS dialogs with a conservative
            // default INSTEAD of showing them, and record what happened.
            //
            // This is unattended automation: showing a modal would block the
            // page's JS thread on a human who isn't there, wedging the agent
            // loop until the action dead-timeout fires. So the page is always
            // answered immediately and never actually blocks — the only change
            // versus the old silent-swallow behaviour is that the model now
            // finds out it happened (see drainInterceptedDialogReport).
            //
            // confirm() answers false and prompt() answers null deliberately:
            // the agent must not silently consent on the user's behalf to
            // whatever was asked. The user-facing web preview does the exact
            // opposite and shows real dialogs; see WebViewHolder.
            //
            // Returning true is what claims the dialog. Returning false (the
            // WebChromeClient default) would let WebView apply the same default
            // itself, but with no record — which is exactly the bug.

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?,
            ): Boolean {
                recordInterceptedDialog(
                    kind = "alert",
                    message = message.orEmpty(),
                    defaultText = null,
                    defaultResponse = "(dismissed)",
                )
                result?.confirm()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?,
            ): Boolean {
                recordInterceptedDialog(
                    kind = "confirm",
                    message = message.orEmpty(),
                    defaultText = null,
                    defaultResponse = "false",
                )
                result?.cancel()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: android.webkit.JsPromptResult?,
            ): Boolean {
                recordInterceptedDialog(
                    kind = "prompt",
                    message = message.orEmpty(),
                    defaultText = defaultValue,
                    defaultResponse = "null",
                )
                result?.cancel()
                return true
            }
        }
    }

    /** [T-android-js-dialogs-256] Record an intercepted dialog for the model. */
    private fun recordInterceptedDialog(
        kind: String,
        message: String,
        defaultText: String?,
        defaultResponse: String,
    ) {
        val url = _currentURL.value
        dialogQueue.record(
            kind = kind,
            message = message,
            defaultText = defaultText,
            pageURL = url.ifEmpty { null },
            defaultResponse = defaultResponse,
        )
        Log.i(TAG, "[JSDialog] intercepted $kind on $url — answered $defaultResponse")
    }

    /**
     * Drain this tab's intercepted-dialog queue into text for the model, or null
     * when nothing was intercepted. Draining clears it, so each dialog is
     * reported exactly once. Called by [BrowserTabPool] on the next tool result.
     */
    fun drainInterceptedDialogReport(): String? = dialogQueue.drainReport()

    // -- Execute Action --

    suspend fun execute(input: BrowserActionInput): BrowserActionResult {
        val prevUrl = withContext(Dispatchers.Main) { webView.url }
        var result: BrowserActionResult = when (input.action) {
            BrowserAction.NAVIGATE -> navigate(input.url)
            BrowserAction.SCREENSHOT -> return screenshot(fullPage = input.fullPage)
            BrowserAction.CLICK -> click(input.selector, input.coordinateX, input.coordinateY)
            BrowserAction.TYPE -> type(input.selector, input.text)
            BrowserAction.GET_TEXT -> return getText(input.selector)
            BrowserAction.SCROLL -> scroll(input.selector, input.direction, input.amount)
            BrowserAction.GET_PAGE_INFO -> return getPageInfo()
            BrowserAction.EXECUTE_JS -> return executeJS(input.script)
            BrowserAction.FIND_ELEMENTS -> return findElements(input.selector)
            BrowserAction.HOVER -> hover(input.selector)
            BrowserAction.GET_READABLE -> return getReadable()
            BrowserAction.SET_USER_AGENT -> return setUserAgent(input.userAgent)
            BrowserAction.SET_VIEWPORT ->
                return BrowserActionResult.error("set_viewport must be routed through BrowserTabPool")
            BrowserAction.GET_BACKBONE -> return getBackbone(input.maxDepth)
            BrowserAction.FETCH -> return fetch(input.url)
            BrowserAction.GET_COOKIES -> return getCookies(input.keywords, input.fuzzy)
            BrowserAction.SET_COOKIES -> return setCookies(input.cookies)
            BrowserAction.SCROLL_AND_COLLECT -> return scrollAndCollect(
                input.scrollCount, input.itemSelector, input.keywords,
            )
            BrowserAction.WAIT_FOR_DOM_STABLE -> return waitForDomStable(input.timeoutMs)
            BrowserAction.NEW_TAB, BrowserAction.CLOSE_TAB, BrowserAction.LIST_TABS ->
                return BrowserActionResult.error("Tab management actions must be routed through BrowserTabPool")
        }

        // Auto-capture screenshot after visual-change actions
        if (result.success && BrowserAction.visualChangeActions.contains(input.action)) {
            result = attachSnapshot(result)
        }

        // Detect URL change after visual-change actions (ignore hash-only changes)
        if (result.success && BrowserAction.visualChangeActions.contains(input.action)) {
            val newUrl = withContext(Dispatchers.Main) { webView.url }
            if (prevUrl != null && newUrl != null) {
                val prevNoHash = prevUrl.substringBefore("#")
                val curNoHash = newUrl.substringBefore("#")
                if (prevNoHash != curNoHash) {
                    result = result.copy(
                        text = result.text + "\n[URL Changed] Page navigated: $prevUrl -> $newUrl. Take a screenshot to see the current state before continuing."
                    )
                }
            }
        }

        return result
    }

    private suspend fun attachSnapshot(result: BrowserActionResult): BrowserActionResult {
        return try {
            delay(300) // Let page settle
            val bitmap = captureWebViewBitmap() ?: return result
            val file = saveBitmapToFile(bitmap, "snapshot", SNAPSHOT_QUALITY)
            bitmap.recycle()
            result.copy(imageFilePath = file.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "Auto-snapshot failed: ${e.message}")
            result
        }
    }

    // -- Navigate --

    private suspend fun navigate(urlString: String?): BrowserActionResult {
        if (urlString.isNullOrEmpty()) return BrowserActionResult.error("Missing 'url' parameter")

        var normalized = urlString
        if (!normalized.contains("://")) normalized = "https://$normalized"

        val deferred = CompletableDeferred<Unit>()
        navigationDeferred = deferred
        _isLoading.value = true

        withContext(Dispatchers.Main) {
            // Re-assert the last applied viewport before loadUrl. Intercepted
            // navigations (minis://) served via shouldInterceptRequest skip
            // the layout pass that a real network load triggers, so without
            // this the page reports Android WebView's 980px no-meta fallback
            // even when a session override (e.g. 960x540) is active.
            lastAppliedViewport?.let { (w, h) -> applyViewport(w, h) }
            webView.loadUrl(normalized)
        }

        // Wait with timeout
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (navigationDeferred === deferred) {
                Log.w(TAG, "Navigation timed out for $normalized")
                _isLoading.value = false
                deferred.complete(Unit)
                navigationDeferred = null
            }
        }
        handler.postDelayed(timeoutRunnable, NAVIGATION_TIMEOUT_MS)

        try {
            deferred.await()
        } finally {
            handler.removeCallbacks(timeoutRunnable)
        }

        _currentURL.value = _currentURL.value.ifEmpty { normalized }
        _isLoading.value = false

        val meta = navigationMetadata()
        return BrowserActionResult(text = meta)
    }

    private suspend fun navigationMetadata(): String {
        val url = _currentURL.value
        val title = _pageTitle.value
        // Read the viewport directly from the page (`window.innerWidth/Height`)
        // so a session or global viewport override shows the actual layout
        // size, not the UA profile default. Matches iOS which likewise queries
        // the WKWebView's live bounds rather than a profile constant.
        val scrollInfo = evaluateJavascript(
            "JSON.stringify({" +
                "sx:window.scrollX||0,sy:window.scrollY||0," +
                "pw:document.documentElement.scrollWidth||0," +
                "ph:document.documentElement.scrollHeight||0," +
                "vw:window.innerWidth||0,vh:window.innerHeight||0" +
                "})"
        )
        var scrollX = 0; var scrollY = 0; var pageW = 0; var pageH = 0
        var vpW = 0; var vpH = 0
        try {
            val info = JSONObject(scrollInfo)
            scrollX = info.optInt("sx"); scrollY = info.optInt("sy")
            pageW = info.optInt("pw"); pageH = info.optInt("ph")
            vpW = info.optInt("vw"); vpH = info.optInt("vh")
        } catch (_: Exception) {}

        // Fall back to the UA profile default when the page hasn't populated
        // `window.inner*` yet (e.g. navigation failure / about:blank).
        val fallback = currentProfile.viewportSize
        val effectiveVpW = if (vpW > 0) vpW else fallback.first
        val effectiveVpH = if (vpH > 0) vpH else fallback.second

        return buildString {
            appendLine("Navigated to $url")
            if (title.isNotEmpty()) appendLine("  Title: $title")
            appendLine("  Viewport: ${effectiveVpW}x$effectiveVpH")
            if (pageW > 0 || pageH > 0) appendLine("  Page size: ${pageW}x$pageH")
            append("  Scroll position: ($scrollX, $scrollY)")
        }
    }

    // -- Screenshot --

    private suspend fun screenshot(fullPage: Boolean = false): BrowserActionResult {
        var truncated = false
        var originalHeightPx = 0
        var didStretch = false
        var savedW = 0
        var savedH = 0

        if (fullPage) {
            // Measure full document height in CSS pixels.
            val cssScrollHeight = evaluateJavascript("document.documentElement.scrollHeight").let {
                it.trim().toIntOrNull() ?: 0
            }
            val density = webView.resources.displayMetrics.density
            val scrollHeightPx = if (cssScrollHeight > 0) {
                (cssScrollHeight * density).toInt()
            } else {
                withContext(Dispatchers.Main) { webView.height }
            }
            originalHeightPx = scrollHeightPx
            val cappedPx = scrollHeightPx.coerceAtMost(MAX_FULL_PAGE_HEIGHT_PX)
            truncated = scrollHeightPx > MAX_FULL_PAGE_HEIGHT_PX

            // Eagerize lazy images and wait two RAFs so layout settles before capture.
            try {
                evaluateJavascript(
                    """
                    (async () => {
                        document.querySelectorAll('img[loading="lazy"]').forEach(i => i.loading = 'eager');
                        await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
                        return 'ok';
                    })()
                    """.trimIndent()
                )
            } catch (_: Exception) { /* best-effort */ }
            delay(50)

            // Snapshot current viewport, stretch to cssScrollHeight, capture, restore.
            val applied = lastAppliedViewport ?: currentProfile.viewportSize
            savedW = applied.first
            savedH = applied.second
            val cssCappedHeight = (cappedPx / density).toInt().coerceAtLeast(savedH)
            withContext(Dispatchers.Main) {
                applyViewport(savedW, cssCappedHeight)
            }
            didStretch = true
            Log.i(TAG, "full_page stretch: ${savedW}x$cssCappedHeight CSS (px=$cappedPx, original=$scrollHeightPx, truncated=$truncated)")
        }

        val bitmap = try {
            captureWebViewBitmap()
        } finally {
            if (didStretch) {
                withContext(Dispatchers.Main) {
                    applyViewport(savedW, savedH)
                }
            }
        } ?: return BrowserActionResult.error("Failed to capture screenshot")

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, out)
        val jpegBytes = out.toByteArray()

        val file = saveBitmapToFile(bitmap, "screenshot")
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val w = bitmap.width; val h = bitmap.height
        bitmap.recycle()

        Log.i(TAG, "Screenshot saved: ${file.absolutePath}, ${w}x$h, ${jpegBytes.size} bytes (full_page=$fullPage)")

        val meta = viewportMetadata(
            imageW = w,
            imageH = h,
            fileSize = jpegBytes.size,
            fullPage = fullPage,
            truncated = truncated,
            originalHeightPx = originalHeightPx,
        )

        return BrowserActionResult(
            text = meta, base64Image = base64, imageFilePath = file.absolutePath
        )
    }

    /** Collect viewport + page metadata for screenshot results. Mirrors iOS viewportMetadata. */
    private suspend fun viewportMetadata(
        imageW: Int,
        imageH: Int,
        fileSize: Int,
        fullPage: Boolean = false,
        truncated: Boolean = false,
        originalHeightPx: Int = 0,
    ): String {
        val url = _currentURL.value
        val title = _pageTitle.value

        val scrollInfo = evaluateJavascript(
            "JSON.stringify({" +
                "sx:window.scrollX||0,sy:window.scrollY||0," +
                "pw:document.documentElement.scrollWidth||0," +
                "ph:document.documentElement.scrollHeight||0," +
                "vw:window.innerWidth||0,vh:window.innerHeight||0" +
                "})"
        )
        var scrollX = 0; var scrollY = 0; var pageW = 0; var pageH = 0
        var vpW = 0; var vpH = 0
        try {
            val info = JSONObject(scrollInfo)
            scrollX = info.optInt("sx"); scrollY = info.optInt("sy")
            pageW = info.optInt("pw"); pageH = info.optInt("ph")
            vpW = info.optInt("vw"); vpH = info.optInt("vh")
        } catch (_: Exception) {}

        val fallback = currentProfile.viewportSize
        val effectiveVpW = if (vpW > 0) vpW else fallback.first
        val effectiveVpH = if (vpH > 0) vpH else fallback.second

        return buildString {
            appendLine("Screenshot captured")
            appendLine("  URL: $url")
            if (title.isNotEmpty()) appendLine("  Title: $title")
            appendLine("  Image: ${imageW}x$imageH (${fileSize / 1024}KB)")
            appendLine("  Viewport: ${effectiveVpW}x$effectiveVpH")
            if (pageW > 0 || pageH > 0) appendLine("  Page size: ${pageW}x$pageH")
            if (fullPage) {
                appendLine("  Full page: true")
                if (originalHeightPx > 0) appendLine("  Original height: ${originalHeightPx}px")
                if (truncated) appendLine("  Truncated: true (capped at ${MAX_FULL_PAGE_HEIGHT_PX}px)")
            }
            append("  Scroll position: ($scrollX, $scrollY)")
        }
    }

    /**
     * Public live-preview snapshot — mirrors iOS `webView.takeSnapshot()`.
     * Called by the UI on a timer (e.g. every 3s while a tool is streaming) so
     * the Minis Computer sheet and FloatingToolStatusBar can show the browser
     * state even for actions that don't save an imageFilePath (get_readable,
     * get_text, execute_js, fetch, etc.).
     */
    suspend fun captureLiveSnapshot(): Bitmap? = captureWebViewBitmap()

    private suspend fun captureWebViewBitmap(): Bitmap? = withContext(Dispatchers.Main) {
        try {
            // WebView may be detached (pool-owned, never added to a window), so
            // width/height can be 0. Ensure it has a layout box matching the
            // agent viewport before drawing.
            val vp = currentProfile.viewportSize
            val density = webView.resources.displayMetrics.density
            var w = webView.width
            var h = webView.height
            if (w <= 0 || h <= 0) {
                // Profile sizes are CSS px; scale to physical px so the CSS
                // viewport actually matches. See applyViewport() for context.
                val targetW = (vp.first * density).toInt().coerceAtLeast(1)
                val targetH = (vp.second * density).toInt().coerceAtLeast(1)
                webView.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(targetW, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(targetH, android.view.View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, targetW, targetH)
                w = targetW; h = targetH
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            Log.d(TAG, "captureWebViewBitmap ${w}x$h")
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "captureWebViewBitmap failed: ${e.message}")
            null
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap, prefix: String, quality: Int = SCREENSHOT_QUALITY): File {
        val filename = "${prefix}_${System.currentTimeMillis()}.jpg"
        val file = File(screenshotsDir, filename)
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        return file
    }

    // -- Click --

    private suspend fun click(selector: String?, x: Int?, y: Int?): BrowserActionResult {
        val js = when {
            selector != null -> BrowserUseJS.click(selector)
            x != null && y != null -> BrowserUseJS.clickCoordinate(x, y)
            else -> return BrowserActionResult.error("click requires 'selector' or 'coordinate_x'/'coordinate_y'")
        }
        return evaluateAndReturn(js)
    }

    // -- Type --

    private suspend fun type(selector: String?, text: String?): BrowserActionResult {
        if (selector == null) return BrowserActionResult.error("type requires 'selector'")
        if (text == null) return BrowserActionResult.error("type requires 'text'")
        return evaluateAndReturn(BrowserUseJS.type(selector, text))
    }

    // -- Get Text --

    private suspend fun getText(selector: String?): BrowserActionResult {
        val js = BrowserUseJS.getText(selector)
        return evaluateJSAndParse(js)
    }

    // -- Get Readable --

    private suspend fun getReadable(): BrowserActionResult {
        return evaluateJSAndParse(BrowserUseJS.getReadable())
    }

    // -- Scroll --

    private suspend fun scroll(selector: String?, direction: ScrollDirection?, amount: Int?): BrowserActionResult {
        val dir = direction ?: ScrollDirection.DOWN
        val px = amount ?: 500
        val js = BrowserUseJS.scroll(dir, px, selector)
        return evaluateJSAndParse(js)
    }

    // -- Get Page Info --

    private suspend fun getPageInfo(): BrowserActionResult {
        return evaluateAndReturn(BrowserUseJS.getPageInfo())
    }

    // -- Execute JS --

    private suspend fun executeJS(script: String?): BrowserActionResult {
        if (script.isNullOrEmpty()) return BrowserActionResult.error("execute_js requires 'script'")
        // Wrap in an async IIFE so `await` works in user scripts.
        // Android WebView doesn't resolve Promises from evaluateJavascript,
        // so we use a JS bridge callback (__minis__.resolve / __minis__.reject).
        return try {
            val deferred = CompletableDeferred<String>()
            asyncJsDeferred = deferred
            val wrapped = """
                (async function(){
                    try {
                        var __r__ = (async function(){ $script })();
                        var __v__ = await __r__;
                        if (__v__ === undefined || __v__ === null) {
                            __minis__.resolve(String(__v__));
                        } else if (typeof __v__ === 'object') {
                            __minis__.resolve(JSON.stringify(__v__));
                        } else {
                            __minis__.resolve(String(__v__));
                        }
                    } catch(e) {
                        __minis__.reject(e.message || String(e));
                    }
                })();
            """.trimIndent()
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript(wrapped, null)
            }
            val raw = withTimeoutOrNull(30_000L) { deferred.await() }
                ?: run {
                    asyncJsDeferred = null
                    return BrowserActionResult.error("JavaScript execution timed out (30s)")
                }
            asyncJsDeferred = null
            val json = try { JSONObject(raw) } catch (_: Exception) { null }
            if (json != null) {
                if (json.has("error")) {
                    BrowserActionResult.error(json.getString("error"))
                } else {
                    BrowserActionResult(text = formatJSONResult(json))
                }
            } else {
                BrowserActionResult(text = raw)
            }
        } catch (e: Exception) {
            asyncJsDeferred = null
            BrowserActionResult.error("JavaScript error: ${e.message}")
        }
    }

    // -- Find Elements --

    private suspend fun findElements(selector: String?): BrowserActionResult {
        if (selector == null) return BrowserActionResult.error("find_elements requires 'selector'")
        return evaluateAndReturn(BrowserUseJS.findElements(selector))
    }

    // -- Hover --

    private suspend fun hover(selector: String?): BrowserActionResult {
        if (selector == null) return BrowserActionResult.error("hover requires 'selector'")
        return evaluateAndReturn(BrowserUseJS.hover(selector))
    }

    // -- Get Backbone --

    private suspend fun getBackbone(maxDepth: Int?): BrowserActionResult {
        val depth = maxDepth ?: 5
        val js = BrowserUseJS.getBackbone(depth)
        val raw = evaluateJavascript(js)
        return try {
            val json = JSONObject(raw)
            if (json.has("error")) {
                BrowserActionResult.error(json.getString("error"))
            } else {
                BrowserActionResult(text = formatBackboneResult(json))
            }
        } catch (e: Exception) {
            BrowserActionResult.error("JavaScript error: ${e.message}")
        }
    }

    // -- Fetch --

    private suspend fun fetch(urlString: String?): BrowserActionResult {
        if (urlString.isNullOrEmpty()) return BrowserActionResult.error("fetch requires 'url' parameter")

        // The fetch JS runs `await fetch(...)` inside an async IIFE, which
        // resolves to a Promise. Android's `WebView.evaluateJavascript` does
        // NOT await Promises, so calling `evaluateJavascript(js)` returns the
        // Promise's `{}` string representation and the caller sees a
        // "No value for base64" parse error. Route through the __minis__
        // bridge so we actually wait for the Promise to resolve.
        val raw = awaitPromiseJs(BrowserUseJS.fetch(urlString))
            ?: return BrowserActionResult.error("fetch timed out")
        return try {
            val json = JSONObject(raw)
            if (json.has("error")) {
                return BrowserActionResult.error("Fetch failed: ${json.getString("error")}")
            }
            val contentType = json.optString("contentType", "")
            val status = json.optInt("status", 0)
            val finalURL = json.optString("url", urlString)

            // `base64` is optional — only present when the JS captured bytes.
            // If missing, fall back to `text` (JSON / plain text) so callers
            // can fetch human-readable payloads without forcing a byte roundtrip.
            val data: ByteArray? = json.optString("base64").takeIf { it.isNotEmpty() }?.let {
                try { Base64.decode(it, Base64.DEFAULT) } catch (_: Exception) { null }
            } ?: json.optString("text").takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)

            if (data == null) {
                return BrowserActionResult.error("Fetch returned no body (status=$status)")
            }
            val size = json.optInt("size", data.size)
            val filename = "fetch_${System.currentTimeMillis()}.${extensionForMimeType(contentType)}"

            val text = buildString {
                appendLine("Fetched $finalURL")
                appendLine("  Status: $status")
                appendLine("  Content-Type: $contentType")
                appendLine("  Size: ${formatBytes(size)}")
                append("  Filename: $filename")
            }

            BrowserActionResult(
                text = text,
                fetchedFileData = data,
                fetchedFileName = filename,
            )
        } catch (e: Exception) {
            BrowserActionResult.error("Fetch parse error: ${e.message}")
        }
    }

    /**
     * Evaluate an `(async function(){...})()` expression and wait for the
     * returned Promise to resolve via the `__minis__` bridge. Returns the
     * resolved string (JSON or plain) or null on timeout. Mirrors the same
     * pattern used by [executeJS].
     */
    private suspend fun awaitPromiseJs(js: String): String? {
        val deferred = CompletableDeferred<String>()
        asyncJsDeferred = deferred
        val wrapped = """
            (async function(){
                try {
                    var __v__ = await ($js);
                    if (__v__ === undefined || __v__ === null) {
                        __minis__.resolve('null');
                    } else if (typeof __v__ === 'object') {
                        __minis__.resolve(JSON.stringify(__v__));
                    } else {
                        __minis__.resolve(String(__v__));
                    }
                } catch(e) {
                    __minis__.reject(e && e.message ? e.message : String(e));
                }
            })();
        """.trimIndent()
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(wrapped, null)
        }
        val raw = withTimeoutOrNull(60_000L) { deferred.await() }
        asyncJsDeferred = null
        return raw
    }

    // -- Set User Agent --

    /** Set user agent from UI settings (public, non-result). */
    fun setUserAgent(profile: UserAgentProfile, customUA: String? = null) {
        currentProfile = profile
        val ua = if (profile == UserAgentProfile.CUSTOM && !customUA.isNullOrEmpty()) customUA
            else profile.userAgentString
        if (ua != null) {
            webView.settings.userAgentString = ua
        }
        applyViewport()
        if (_currentURL.value.isNotEmpty()) {
            webView.reload()
        }
    }

    /**
     * Force the detached pool WebView to lay out at the agent viewport size so
     * page scripts see `window.innerWidth` matching the selected profile (Mobile
     * 412×915 / Desktop 1280×800). Without this, a detached WebView has
     * width/height = 0 and pages render using WebView defaults.
     *
     * The profile dimensions are CSS pixels (iOS "points"). Android WebView
     * uses physical pixels for measure/layout and derives CSS px via
     * window.devicePixelRatio = system density. Passing 412 px directly on a
     * 2.75-density device makes the CSS viewport ~150px wide, causing pages
     * to render at a tiny logical width then upscale, making elements look
     * oversized and clipping on the right. Scale by density so the CSS
     * viewport actually matches the profile.
     */
    fun applyViewport() {
        val vp = currentProfile.viewportSize
        applyViewport(vp.first, vp.second)
    }

    /**
     * Last CSS-pixel viewport applied via [applyViewport]. Used so [navigate]
     * can re-assert the same size before `loadUrl()` — intercepted
     * (`minis://`) loads skip WebView's measure pass, otherwise stranding the
     * page at the 980px no-meta fallback.
     */
    private var lastAppliedViewport: Pair<Int, Int>? = null

    /**
     * Lay out the detached WebView at the given CSS-pixel viewport. Used by
     * [BrowserTabPool] to apply a session or global custom viewport override
     * — mirrors iOS `BrowserUseManager.setViewport(width:height:...)`.
     */
    fun applyViewport(cssWidth: Int, cssHeight: Int) {
        val density = webView.resources.displayMetrics.density
        val w = ((cssWidth * density).toInt()).coerceAtLeast(1)
        val h = ((cssHeight * density).toInt()).coerceAtLeast(1)
        webView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, w, h)
        lastAppliedViewport = cssWidth to cssHeight
        // Pages authored at this CSS width need to fit inside the visible
        // container. Compute and stash a setInitialScale so the next reload
        // (the tab pool always reloads after a viewport change) renders at
        // the shrink-to-fit ratio. pinch-zoom remains enabled because we
        // never touch builtInZoomControls or the page's user-scalable hint.
        applyShrinkToFit(cssWidth)
    }

    private suspend fun setUserAgent(profile: UserAgentProfile?): BrowserActionResult {
        val newProfile = profile ?: UserAgentProfile.MOBILE_CHROME
        currentProfile = newProfile
        val ua = newProfile.userAgentString
        // Every WebView method must be called on the main thread, but the
        // offload handler's `runBlocking { ... execute(...) }` dispatches on
        // a worker. `applyViewport(...)` measures/layouts the detached
        // WebView; settings / reload likewise. Hop to main so we don't
        // crash with "A WebView method was called on thread 'worker-N'".
        withContext(Dispatchers.Main) {
            if (ua != null) {
                webView.settings.userAgentString = ua
            }
            applyViewport()
            val oldUrl = _currentURL.value
            if (oldUrl.isNotEmpty()) {
                webView.reload()
            }
        }
        val vp = newProfile.viewportSize
        return BrowserActionResult(text = "Switched to ${newProfile.value} (${vp.first}x${vp.second})")
    }

    // -- User Navigation --

    fun goBack() { if (webView.canGoBack()) webView.goBack() }
    fun goForward() { if (webView.canGoForward()) webView.goForward() }
    fun reload() { webView.reload() }
    fun stopLoading() { webView.stopLoading(); _isLoading.value = false }

    /**
     * Reload the current page and suspend until `onPageFinished` fires (or
     * the navigation timeout expires). Used by [BrowserTabPool] after a
     * viewport change so a follow-up `get_page_info` reads the new CSS
     * viewport instead of a stale snapshot. Must be called on the main
     * thread.
     *
     * When the tab has no loaded URL (fresh WebView) or is sitting on
     * `about:blank`, a bare `webView.reload()` is a no-op and
     * `onPageFinished` never fires — we'd time out for no reason. Explicit
     * `loadUrl("about:blank")` always triggers the lifecycle, so the
     * viewport-change callers still get a deterministic page refresh.
     */
    suspend fun reloadAndWait() {
        val deferred = CompletableDeferred<Unit>()
        navigationDeferred = deferred
        _isLoading.value = true
        val url = _currentURL.value
        if (url.isEmpty() || url == "about:blank") {
            // Android WebView's `about:blank` reports `window.innerWidth=980`
            // regardless of container size (the no-meta-viewport fallback),
            // so a plain blank reload wouldn't reflect the new viewport. Load
            // an empty page that declares `width=device-width` instead —
            // `window.innerWidth` then tracks the container we just laid out.
            // `loadDataWithBaseURL(null, html, ...)` lands on `about:blank`
            // as the reported URL but with our meta-viewport in effect.
            webView.loadDataWithBaseURL(null, BLANK_PAGE_HTML, "text/html", "utf-8", null)
        } else {
            webView.reload()
        }
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (navigationDeferred === deferred) {
                _isLoading.value = false
                deferred.complete(Unit)
                navigationDeferred = null
            }
        }
        handler.postDelayed(timeoutRunnable, NAVIGATION_TIMEOUT_MS)
        try { deferred.await() } finally { handler.removeCallbacks(timeoutRunnable) }
        _isLoading.value = false
    }

    /**
     * Load a minimal HTML page with `<meta viewport content="width=device-width">`
     * so `window.innerWidth` tracks the just-laid-out container size. Used by
     * [BrowserTabPool.createTab] when no initial URL is supplied, so a follow-up
     * `get_page_info` on a fresh tab reports the session viewport instead of
     * WebView's hardcoded `about:blank` 980px fallback.
     *
     * Suspends until `onPageFinished` fires so a follow-up JS evaluation sees
     * `document.body` populated. Must be called on the main thread.
     */
    suspend fun loadBlankPage() {
        val deferred = CompletableDeferred<Unit>()
        navigationDeferred = deferred
        _isLoading.value = true
        webView.loadDataWithBaseURL(null, BLANK_PAGE_HTML, "text/html", "utf-8", null)
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (navigationDeferred === deferred) {
                _isLoading.value = false
                deferred.complete(Unit)
                navigationDeferred = null
            }
        }
        handler.postDelayed(timeoutRunnable, NAVIGATION_TIMEOUT_MS)
        try { deferred.await() } finally { handler.removeCallbacks(timeoutRunnable) }
        _isLoading.value = false
    }

    fun loadURL(urlString: String) {
        var normalized = urlString
        if (!normalized.contains("://")) normalized = "https://$normalized"
        _isLoading.value = true
        webView.loadUrl(normalized)
    }

    // -- JS Evaluation Helpers --

    private suspend fun evaluateJavascript(js: String): String = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String>()
        webView.evaluateJavascript(js) { result ->
            // Android WebView returns JSON-encoded strings, so unquote
            val unquoted = if (result != null && result.startsWith("\"") && result.endsWith("\"")) {
                try {
                    JSONObject("{\"v\":$result}").getString("v")
                } catch (_: Exception) {
                    result
                }
            } else {
                result ?: "null"
            }
            deferred.complete(unquoted)
        }
        deferred.await()
    }

    private suspend fun evaluateAndReturn(js: String): BrowserActionResult {
        return try {
            val raw = evaluateJavascript(js)
            val json = try { JSONObject(raw) } catch (_: Exception) { null }
            if (json != null) {
                if (json.has("error")) {
                    BrowserActionResult.error(json.getString("error"))
                } else {
                    BrowserActionResult(text = formatJSONResult(json))
                }
            } else {
                BrowserActionResult(text = raw)
            }
        } catch (e: Exception) {
            BrowserActionResult.error("JavaScript error: ${e.message}")
        }
    }

    private suspend fun evaluateJSAndParse(js: String): BrowserActionResult {
        return try {
            val raw = evaluateJavascript(js)
            val json = try { JSONObject(raw) } catch (_: Exception) { null }
            if (json != null) {
                if (json.has("error")) {
                    BrowserActionResult.error(json.getString("error"))
                } else {
                    BrowserActionResult(text = formatJSONResult(json))
                }
            } else {
                BrowserActionResult(text = raw)
            }
        } catch (e: Exception) {
            BrowserActionResult.error("JavaScript error: ${e.message}")
        }
    }

    // -- Result Formatting --

    private fun formatJSONResult(json: JSONObject): String = buildString {
        when {
            json.optBoolean("clicked") -> {
                val tag = json.optString("tag", "?")
                appendLine("Clicked <$tag>")
                if (json.has("x") && json.has("y")) appendLine("  Position: (${json.optInt("x")}, ${json.optInt("y")})")
                val text = json.optString("text", "")
                if (text.isNotEmpty()) append("  Text: ${text.take(200)}")
            }
            json.optBoolean("typed") -> {
                val sel = json.optString("selector", "?")
                val len = json.optInt("length", 0)
                append("Typed $len chars into $sel")
            }
            json.optBoolean("scrolled") -> {
                val dir = json.optString("direction", "?")
                val amt = json.optInt("amount", 0)
                appendLine("Scrolled $dir ${amt}px")
                if (json.has("scrollY")) appendLine("  Scroll Y: ${json.optInt("scrollY")}")
                if (json.has("scrollHeight")) appendLine("  Page height: ${json.optInt("scrollHeight")}")
                if (json.has("viewportHeight")) append("  Viewport height: ${json.optInt("viewportHeight")}")
            }
            json.has("scrolledTo") -> {
                val sel = json.optString("scrolledTo")
                val tag = json.optString("tag", "?")
                append("Scrolled to <$tag> ($sel)")
            }
            json.optBoolean("hovered") -> {
                val tag = json.optString("tag", "?")
                appendLine("Hovered <$tag>")
                val text = json.optString("text", "")
                if (text.isNotEmpty()) append("  Text: ${text.take(200)}")
            }
            json.has("text") && json.has("length") -> {
                val title = json.optString("title", "")
                if (title.isNotEmpty()) appendLine("Title: $title")
                val text = json.optString("text", "")
                val len = json.optInt("length", text.length)
                appendLine("Text ($len chars):")
                append(text.take(10000))
            }
            json.has("count") && json.has("elements") -> {
                val count = json.optInt("count")
                val elements = json.optJSONArray("elements")
                val shown = json.optInt("shown", elements?.length() ?: 0)
                appendLine("Found $count element(s) (showing $shown):")
                if (elements != null) {
                    for (i in 0 until elements.length()) {
                        val el = elements.getJSONObject(i)
                        val idx = el.optInt("index")
                        val tag = el.optString("tag", "?")
                        val text = el.optString("text", "").take(80)
                        val line = buildString {
                            append("  [$idx] <$tag>")
                            val id = el.optString("id", "")
                            if (id.isNotEmpty()) append(" #$id")
                            if (text.isNotEmpty()) append(" \"$text\"")
                            val href = el.optString("href", "")
                            if (href.isNotEmpty()) append(" -> $href")
                        }
                        appendLine(line)
                    }
                }
            }
            else -> {
                // Fallback: format each key-value pair
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    appendLine("  $key: ${json.opt(key)}")
                }
            }
        }
    }.trimEnd()

    private fun formatBackboneResult(json: JSONObject): String = buildString {
        val nodeCount = json.optInt("nodeCount")
        val depth = json.optInt("depth")
        val merged = json.optInt("merged")
        appendLine("Page backbone: $nodeCount nodes, depth $depth, $merged merged")

        val backbone = json.optJSONArray("backbone")
        if (backbone != null) {
            for (i in 0 until backbone.length()) {
                formatBackboneNode(backbone.getJSONObject(i), 0, this)
            }
        }
    }.trimEnd()

    private fun formatBackboneNode(node: JSONObject, indent: Int, sb: StringBuilder) {
        val pad = "  ".repeat(indent)
        val tag = node.optString("tag", "?")
        val sel = node.optString("sel", "")
        val rect = node.optString("rect", "")

        val header = buildString {
            append("$pad<$tag>")
            val id = node.optString("id", "")
            if (id.isNotEmpty()) append(" #$id")
            val cls = node.optString("cls", "")
            if (cls.isNotEmpty()) append(" .${cls.replace(" ", ".")}")
            val role = node.optString("role", "")
            if (role.isNotEmpty()) append(" [$role]")
            append(" | $sel | $rect")
        }
        sb.appendLine(header)

        val text = node.optString("text", "")
        if (text.isNotEmpty()) sb.appendLine("$pad  \"$text\"")
        val href = node.optString("href", "")
        if (href.isNotEmpty()) sb.appendLine("$pad  -> $href")
        val img = node.optString("img", "")
        if (img.isNotEmpty()) sb.appendLine("$pad  img: $img")
        val input = node.optString("input", "")
        if (input.isNotEmpty()) sb.appendLine("$pad  input: $input")

        val children = node.optJSONArray("children")
        if (children != null) {
            for (i in 0 until children.length()) {
                formatBackboneNode(children.getJSONObject(i), indent + 1, sb)
            }
        }
    }

    private fun extensionForMimeType(mime: String): String {
        val lower = mime.lowercase().split(";").firstOrNull()?.trim() ?: ""
        return when (lower) {
            "text/html" -> "html"; "text/plain" -> "txt"; "text/css" -> "css"; "text/csv" -> "csv"
            "application/json" -> "json"; "application/xml", "text/xml" -> "xml"
            "application/pdf" -> "pdf"; "image/png" -> "png"; "image/jpeg" -> "jpg"
            "image/gif" -> "gif"; "image/webp" -> "webp"; "image/svg+xml" -> "svg"
            "application/zip" -> "zip"; "application/gzip" -> "gz"
            else -> "bin"
        }
    }

    private fun formatBytes(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    // -- Get Cookies --

    /**
     * Return cookies for the current page's origin, filtered by keyword.
     * Android exposes cookies as a single `Cookie` header string via
     * [CookieManager]; we split on `;` and match each `name=value` pair.
     *
     * `fuzzy=false` (default): exact name match (case-insensitive).
     * `fuzzy=true`: substring match within the cookie name.
     */
    private fun getCookies(keywords: List<String>?, fuzzy: Boolean): BrowserActionResult {
        val url = _currentURL.value.takeIf { it.isNotEmpty() }
            ?: return BrowserActionResult.error("get_cookies: no page is loaded (navigate first)")
        val cookieMgr = runCatching { CookieManager.getInstance() }.getOrNull()
            ?: return BrowserActionResult.error("get_cookies: CookieManager unavailable")
        val raw = cookieMgr.getCookie(url).orEmpty()
        if (raw.isEmpty()) {
            return BrowserActionResult(text = "No cookies set for $url")
        }
        val pairs = raw.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull {
                val eq = it.indexOf('=')
                if (eq <= 0) null else it.substring(0, eq).trim() to it.substring(eq + 1).trim()
            }
        val filtered = if (keywords.isNullOrEmpty()) pairs else pairs.filter { (name, _) ->
            keywords.any { kw ->
                if (fuzzy) name.contains(kw, ignoreCase = true)
                else name.equals(kw, ignoreCase = true)
            }
        }
        val text = buildString {
            appendLine("Cookies for $url (${filtered.size} of ${pairs.size}):")
            for ((name, value) in filtered) {
                val preview = if (value.length > 80) value.take(77) + "…" else value
                appendLine("  $name = $preview")
            }
        }.trimEnd()
        return BrowserActionResult(text = text)
    }

    // -- Set Cookies --

    /**
     * Write cookies into the WebView cookie store via [CookieManager.setCookie],
     * which accepts a Set-Cookie-style string and (unlike `document.cookie`) can
     * set HttpOnly cookies. Symmetric with [getCookies]. Each entry needs
     * `name` + `value`; `domain` defaults to the current page host, `path` to
     * "/". Mirrors iOS `setCookies`.
     */
    private fun setCookies(cookies: List<Map<String, Any?>>?): BrowserActionResult {
        val url = _currentURL.value.takeIf { it.isNotEmpty() }
            ?: return BrowserActionResult.error("set_cookies: no page is loaded (navigate first)")
        // Distinguish "field omitted/unparseable" from "field present but empty".
        // The schema types `cookies` as a string, so a model may send a JSON
        // STRING that failed to re-parse, or the CLI's shell-escaping mangled the
        // array — guide the caller instead of a confusing empty result.
        if (cookies == null) {
            return BrowserActionResult.error(
                "set_cookies: 'cookies' must be a JSON array of cookie objects " +
                    "(e.g. [{\"name\":\"foo\",\"value\":\"bar\"}]). It was missing or could not be " +
                    "parsed — if you passed it as a string, ensure it is valid JSON; from the CLI " +
                    "prefer --cookies-file <path> to avoid shell escaping.",
            )
        }
        if (cookies.isEmpty()) {
            return BrowserActionResult.error(
                "set_cookies: 'cookies' array is empty — provide at least one {name, value} object.",
            )
        }
        val cookieMgr = runCatching { CookieManager.getInstance() }.getOrNull()
            ?: return BrowserActionResult.error("set_cookies: CookieManager unavailable")

        // Default domain = current page host.
        val defaultDomain = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()

        // expires (Unix seconds) → RFC-1123 "Expires=" date in GMT.
        val httpDateFmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }

        val setNames = mutableListOf<String>()
        val domainsTouched = linkedSetOf<String>()
        val failures = mutableListOf<String>()

        for (raw in cookies) {
            // Accept the field-name variants common cookie exports use (browser
            // extensions EditThisCookie / Cookie-Editor, Playwright / Puppeteer
            // storage), so a model can paste cookies verbatim. [set-cookies-formats]
            val name = cookieString(raw, "name")?.takeIf { it.isNotEmpty() }
            val value = cookieString(raw, "value")
            if (name == null || value == null) {
                failures.add("(missing name/value)")
                continue
            }
            val domain = cookieString(raw, "domain")?.takeIf { it.isNotEmpty() } ?: defaultDomain
            val path = cookieString(raw, "path")?.takeIf { it.isNotEmpty() } ?: "/"

            val sb = StringBuilder()
            sb.append(name).append('=').append(value)
            if (domain.isNotEmpty()) sb.append("; Domain=").append(domain)
            sb.append("; Path=").append(path)
            if (cookieBool(raw, "secure") == true) sb.append("; Secure")
            // camelCase httpOnly (extensions / Playwright) + snake_case http_only.
            if (cookieBool(raw, "http_only", "httpOnly") == true) sb.append("; HttpOnly")
            // Expiry in Unix seconds. Aliases: expires (Puppeteer) + expirationDate
            // (EditThisCookie / Cookie-Editor, often fractional). <= 0 (Puppeteer's
            // -1, or 0) → session cookie (no Expires attribute).
            cookieNumber(raw, "expires", "expirationDate")?.takeIf { it > 0 }?.let { expires ->
                val date = java.util.Date(expires.toLong() * 1000L)
                sb.append("; Expires=").append(httpDateFmt.format(date))
            }
            // sameSite accepted (Lax/Strict/None, any case) so exports including
            // it aren't rejected, but NOT applied yet — CookieManager.setCookie
            // honors a SameSite attribute, but wiring it needs validation against
            // the cross-site captcha flows. TODO [set-cookies-samesite].
            @Suppress("UNUSED_VARIABLE")
            val sameSite = cookieString(raw, "sameSite", "same_site")

            cookieMgr.setCookie(url, sb.toString())
            setNames.add(name)
            domainsTouched.add(domain)
        }
        cookieMgr.flush()

        if (setNames.isEmpty()) {
            return BrowserActionResult.error(
                "set_cookies: no cookies were set (every entry was invalid: ${failures.joinToString(", ")})",
            )
        }
        val domainLabel = if (domainsTouched.size == 1) domainsTouched.first() else domainsTouched.joinToString(", ")
        var text = "Set ${setNames.size} cookie(s) for $domainLabel: ${setNames.joinToString(", ")}"
        if (failures.isNotEmpty()) {
            text += "\nSkipped ${failures.size} invalid entry(ies): ${failures.joinToString(", ")}"
        }
        return BrowserActionResult(text = text)
    }

    // -- Cookie field readers (format-tolerant) --

    /** Look up `aliases` in the map: exact match first, then case-insensitive,
     *  so httpOnly / HttpOnly / http_only all resolve. */
    private fun cookieValue(raw: Map<String, Any?>, vararg aliases: String): Any? {
        for (key in aliases) raw[key]?.let { return it }
        val lowered = aliases.map { it.lowercase() }.toSet()
        for ((k, v) in raw) if (k.lowercase() in lowered && v != null) return v
        return null
    }

    /** String reader; numbers are stringified so a numeric `value` still works. */
    private fun cookieString(raw: Map<String, Any?>, vararg aliases: String): String? =
        when (val v = cookieValue(raw, *aliases)) {
            is String -> v
            is Number -> v.toString()
            else -> null
        }

    /** Bool reader; tolerates JSON bool, 0/1, and stringified "true"/"false". */
    private fun cookieBool(raw: Map<String, Any?>, vararg aliases: String): Boolean? =
        when (val v = cookieValue(raw, *aliases)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.lowercase() in setOf("true", "1", "yes")
            else -> null
        }

    /** Numeric (seconds) reader; accepts JSON number or numeric string. */
    private fun cookieNumber(raw: Map<String, Any?>, vararg aliases: String): Double? =
        when (val v = cookieValue(raw, *aliases)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }

    // -- Wait for DOM Stable --

    /**
     * Poll the DOM for stability: repeatedly measures `document.body.innerHTML.length`
     * at ~200ms intervals and returns when two successive readings match, or the
     * timeout elapses. Matches iOS `wait_for_dom_stable`.
     */
    private suspend fun waitForDomStable(timeoutMs: Int?): BrowserActionResult {
        val budget = (timeoutMs ?: DEFAULT_DOM_STABLE_TIMEOUT_MS).coerceIn(
            MIN_DOM_STABLE_TIMEOUT_MS, MAX_DOM_STABLE_TIMEOUT_MS,
        )
        val pollInterval = 200L
        var lastSize = -1L
        var stable = false
        val deadline = System.currentTimeMillis() + budget
        // [T-android-domstable-min-budget] C5 fast path: a document that has
        // already finished loading (readyState === 'complete') is almost
        // always stable — confirm with two samples 50ms apart and return
        // without paying the 200ms poll interval. Keeps trivial static
        // pages (e.g. minis:// docs) fast at any budget.
        val readyState = evaluateJavascript(
            "(function(){try{return document.readyState;}catch(e){return '';}})()"
        ).trim('"')
        if (readyState == "complete") {
            val first = evaluateJavascript(
                "(function(){try{return (document.body&&document.body.innerHTML.length)||0;}catch(e){return -1;}})()"
            ).toLongOrNull() ?: -1L
            delay(50)
            val second = evaluateJavascript(
                "(function(){try{return (document.body&&document.body.innerHTML.length)||0;}catch(e){return -1;}})()"
            ).toLongOrNull() ?: -1L
            // [T-android-review-p1-fixes] F4: require a NON-EMPTY body.
            // SPAs report readyState=complete with an empty/skeleton body
            // before the JS app renders — `first >= 0` declared those
            // "stable" instantly and the agent read a blank page. Empty
            // bodies fall through to the polling loop unchanged (which can
            // legitimately conclude an actually-empty page is stable, but
            // only after giving scripts the full budget to render).
            if (first == second && first > 0) {
                return BrowserActionResult(
                    text = "DOM stable immediately (readyState=complete, body length=$first)",
                )
            }
            // Fast path inconclusive (DOM still mutating post-load, or body
            // still empty) — fall through to the normal polling loop with
            // lastSize untouched so its stability criterion stays exactly
            // as before.
        }
        while (System.currentTimeMillis() < deadline) {
            val raw = evaluateJavascript(
                "(function(){try{return (document.body&&document.body.innerHTML.length)||0;}catch(e){return -1;}})()"
            )
            val size = raw.toLongOrNull() ?: -1L
            if (size == lastSize && size >= 0) { stable = true; break }
            lastSize = size
            delay(pollInterval)
        }
        val elapsed = budget - (deadline - System.currentTimeMillis())
        return if (stable) {
            BrowserActionResult(text = "DOM stable after ${elapsed}ms (body length=$lastSize)")
        } else {
            BrowserActionResult(
                text = "DOM did not stabilize within ${budget}ms (last body length=$lastSize)",
                success = false,
            )
        }
    }

    // -- Scroll and Collect --

    /**
     * Scroll the page [scrollCount] times, collecting text of every element
     * matching [itemSelector]. Optional [keywords] filter the collected text
     * (case-insensitive substring match). Mirrors iOS `scroll_and_collect`.
     */
    private suspend fun scrollAndCollect(
        scrollCount: Int?,
        itemSelector: String?,
        keywords: List<String>?,
    ): BrowserActionResult {
        val iterations = (scrollCount ?: 5).coerceIn(1, 50)
        val selector = itemSelector?.takeIf { it.isNotBlank() }
            ?: return BrowserActionResult.error("scroll_and_collect requires --item-selector")

        val collected = LinkedHashSet<String>()
        for (i in 0 until iterations) {
            val escaped = selector.replace("\\", "\\\\").replace("'", "\\'")
            val raw = evaluateJavascript(
                """(function(){
                    try {
                        var nodes = document.querySelectorAll('$escaped');
                        var out = [];
                        for (var i=0;i<nodes.length;i++) {
                            var t = (nodes[i].innerText || nodes[i].textContent || '').trim();
                            if (t) out.push(t);
                        }
                        return JSON.stringify(out);
                    } catch(e) { return '[]'; }
                })()"""
            )
            try {
                val arr = org.json.JSONArray(raw)
                for (j in 0 until arr.length()) {
                    val s = arr.optString(j)
                    if (s.isNotBlank()) collected.add(s)
                }
            } catch (_: Exception) { /* ignore malformed batches */ }

            // Scroll one viewport down and let the page settle before re-querying.
            evaluateJavascript("window.scrollBy(0, window.innerHeight);")
            delay(400)
        }

        val filtered = if (keywords.isNullOrEmpty()) collected.toList()
            else collected.filter { text -> keywords.any { k -> text.contains(k, ignoreCase = true) } }

        val text = buildString {
            appendLine("scroll_and_collect: $iterations scrolls, selector='$selector'")
            appendLine("  matched: ${filtered.size} / ${collected.size} total")
            for ((i, item) in filtered.withIndex()) {
                val preview = if (item.length > 160) item.take(157) + "…" else item
                appendLine("  [${i + 1}] $preview")
            }
        }.trimEnd()
        return BrowserActionResult(text = text)
    }

}
