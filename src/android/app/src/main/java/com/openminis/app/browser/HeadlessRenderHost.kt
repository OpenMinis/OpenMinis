package com.openminis.app.browser

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [T-android-browser-gpu-capture] Renders a headless (window-less) agent
 * WebView through the real GPU compositor so a screenshot contains what a
 * user would see — including WebGL, `<video>` and other GPU-only layers.
 *
 * Why this exists: the pool's WebViews are never attached to a window while
 * the browser sheet is closed, and the old capture drew them with
 * `WebView.draw(softwareCanvas)`. Chromium answers a software draw by
 * re-rasterising the page on the CPU, which has no access to GPU surfaces, so
 * a WebGL canvas came out as an empty box (reproduced on a Pixel 4a: DOM text
 * and a 2D canvas captured, the WebGL canvas beside them blank white, while
 * the same page on screen showed the rendered scene). A detached View has no
 * window surface, so reading screen pixels (PixelCopy) is not an option
 * either — there is nothing on screen to read.
 *
 * The fix gives the WebView a real surface only for the duration of a
 * capture: a PRIVATE virtual display (visible to nothing but this app)
 * backed by an [ImageReader], with a [Presentation] window on it. The WebView
 * is parented into that window, drawn by the normal hardware pipeline, one
 * composited frame is read back from the ImageReader, and the WebView is
 * detached again. This is how an offscreen browser is given a GPU on Android.
 *
 * Cost control, deliberately:
 *  - Nothing exists until the first capture, and the display, window and
 *    buffers are released [IDLE_RELEASE_MS] after the last one, so an idle
 *    app pays no rendering, memory or battery cost. The WebView is only in
 *    the window during a capture, so pages do not start animating in the
 *    background as a permanently attached view would.
 *  - Captures are serialised; the host is sized to the viewport being
 *    captured and never larger than [MAX_EDGE_PX] on either edge — a
 *    full-page capture (tens of thousands of px tall) stays on the software
 *    path rather than allocating a display that size.
 *  - Any failure returns null and the caller falls back to the software
 *    draw, so the worst case is the previous behaviour. Repeated failures to
 *    create the host trip [disabled] for the rest of the process, so a device
 *    that cannot do this does not pay the attempt on every screenshot.
 *  - The window is NOT_FOCUSABLE / NOT_TOUCHABLE: a focused input inside the
 *    page must never raise the IME on the phone's real screen.
 */
internal object HeadlessRenderHost {
    private const val TAG = "HeadlessRenderHost"

    const val IDLE_RELEASE_MS = 15_000L
    const val MAX_EDGE_PX = 4096
    private const val VISUAL_STATE_TIMEOUT_MS = 1_500L
    /**
     * Upper bound on waiting for the page to finish drawing. Only a page that
     * never stops changing (a running animation) waits this long; re-raster
     * of a detached page measured ~100 ms (Wikipedia, Pixel 4a), so this
     * leaves wide margin without making every animated page cost 1.5 s.
     */
    private const val SETTLE_TIMEOUT_MS = 700L
    /**
     * A frame counts as final once no newer one has arrived for this long.
     *
     * Why a quiet window and not a fixed frame count: Chromium drops a
     * page's rasterised tiles while the WebView is detached, so on re-attach
     * it presents what it has (white where tiles are missing) and keeps
     * presenting as raster completes. Two frames was enough for a tiny page
     * and gave a mostly-white Wikipedia article (measured on a Pixel 4a).
     * A finished static page stops producing frames, which is the signal.
     */
    private const val QUIET_MS = 150L
    /** At least this many frames after the content is ready. */
    private const val MIN_FRAMES = 2
    private const val MAX_HOST_FAILURES = 3

    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    // All state below is touched on the main thread only.
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var container: FrameLayout? = null
    private var hostW = 0
    private var hostH = 0
    private var latest: Image? = null
    private var frameCount = 0L
    private var lastFrameAt = 0L
    private var hostFailures = 0
    private var visualStateRequest = 0L

    /** Non-null once the GPU path has been given up on for this process. */
    @Volatile
    var disabled: String? = null
        private set

    private val releaseTask = Runnable { release("idle") }

    /**
     * Capture [webView] at [w]×[h] physical px through the GPU compositor, or
     * null when this path does not apply (the WebView is on screen in the
     * browser sheet, the size is out of range, the host could not be built)
     * or fails — callers then use the software draw.
     */
    suspend fun capture(webView: WebView, w: Int, h: Int): Bitmap? {
        if (disabled != null) return null
        return mutex.withLock { withContext(Dispatchers.Main) { captureOnMain(webView, w, h) } }
    }

    private suspend fun captureOnMain(webView: WebView, w: Int, h: Int): Bitmap? {
        // Shown in the browser sheet: it is not ours to move, and taking it
        // would blank the sheet for the length of the capture.
        if (webView.parent != null || webView.isAttachedToWindow) return null
        if (w <= 0 || h <= 0 || w > MAX_EDGE_PX || h > MAX_EDGE_PX) return null

        main.removeCallbacks(releaseTask)
        val t0 = SystemClock.elapsedRealtime()
        // Main-thread time actually spent blocking (the waits in between
        // suspend and let the UI run) — the number that matters for jank.
        var mainBlockMs = 0L
        fun <T> onMain(block: () -> T): T {
            val s = SystemClock.elapsedRealtime()
            try { return block() } finally { mainBlockMs += SystemClock.elapsedRealtime() - s }
        }
        var image: Image? = null
        var scrollbars: Pair<Boolean, Boolean>? = null
        try {
            val host = onMain { ensureHost(webView.context, w, h) } ?: return null
            val tHost = SystemClock.elapsedRealtime()
            onMain {
                // Scrollbars fade in on attach and would be baked into the
                // image; the software path never drew them.
                scrollbars = webView.isVerticalScrollBarEnabled to webView.isHorizontalScrollBarEnabled
                webView.isVerticalScrollBarEnabled = false
                webView.isHorizontalScrollBarEnabled = false
                host.addView(webView, FrameLayout.LayoutParams(w, h))
            }
            val ready = awaitVisualState(webView)
            val tReady = SystemClock.elapsedRealtime()
            val start = frameCount
            onMain { webView.invalidate() }
            val settled = withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
                while (frameCount < start + MIN_FRAMES ||
                    SystemClock.elapsedRealtime() - lastFrameAt < QUIET_MS
                ) delay(8)
                true
            } == true
            // Timing out is normal for an animated page (it never goes
            // quiet); any frame of it is a true picture. Only NO frame fails.
            val gotFrames = frameCount >= start + 1
            val tFrame = SystemClock.elapsedRealtime()
            // The sheet can open mid-capture and re-parent the WebView
            // (BrowserWebView detaches it from whatever holds it); the frame
            // we have would then be of an empty window.
            if (webView.parent !== host) {
                Log.w(TAG, "WebView left the host during capture; falling back")
                return null
            }
            if (!gotFrames || latest == null) {
                Log.w(TAG, "No composited frame (ready=$ready frames=${frameCount - start}); falling back")
                return null
            }
            // Take ownership so the listener cannot close it under us, and
            // hand the WebView back before the copy: the copy is ~w*h*4
            // bytes and runs off the main thread.
            image = latest
            latest = null
            onMain { host.removeView(webView) }
            val owned = image!!
            val bitmap = withContext(Dispatchers.Default) { toBitmap(owned, w, h) }
            Log.i(
                TAG,
                "gpu capture ${w}x$h total=${SystemClock.elapsedRealtime() - t0}ms " +
                    "host=${tHost - t0}ms ready=${tReady - tHost}ms($ready) " +
                    "frame=${tFrame - tReady}ms(frames=${frameCount - start} settled=$settled) " +
                    "mainBlock=${mainBlockMs}ms",
            )
            return bitmap
        } catch (e: Exception) {
            Log.w(TAG, "gpu capture failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            image?.close()
            scrollbars?.let { (v, hz) ->
                webView.isVerticalScrollBarEnabled = v
                webView.isHorizontalScrollBarEnabled = hz
            }
            val host = container
            if (host != null && webView.parent === host) host.removeView(webView)
            main.postDelayed(releaseTask, IDLE_RELEASE_MS)
        }
    }

    /**
     * Resolves once the WebView's current content will be in the next frame
     * it draws — the documented way to know a (re)attached WebView is not
     * about to present a stale or empty frame. Bounded: a wedged renderer
     * must not wedge the screenshot.
     */
    private suspend fun awaitVisualState(webView: WebView): Boolean {
        val done = CompletableDeferred<Unit>()
        val id = ++visualStateRequest
        webView.postVisualStateCallback(
            id,
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (requestId == id) done.complete(Unit)
                }
            },
        )
        return withTimeoutOrNull(VISUAL_STATE_TIMEOUT_MS) { done.await() } != null
    }

    private fun ensureHost(context: Context, w: Int, h: Int): FrameLayout? {
        val existing = container
        if (existing != null && presentation != null && hostW == w && hostH == h) return existing
        release(if (existing == null) "create" else "resize")

        val app = context.applicationContext
        var r: ImageReader? = null
        var vd: VirtualDisplay? = null
        try {
            val dm = app.getSystemService(DisplayManager::class.java) ?: return fail("no DisplayManager")
            r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            // Keep only the newest frame: an ImageReader whose images are
            // never acquired stops receiving once its queue is full.
            r.setOnImageAvailableListener({ rr ->
                val img = runCatching { rr.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                latest?.close()
                latest = img
                frameCount++
                lastFrameAt = SystemClock.elapsedRealtime()
            }, main)
            // No PUBLIC flag: a private display only this app can put
            // content on, needing no permission and invisible to the user.
            vd = dm.createVirtualDisplay(
                "minis-headless-browser", w, h, app.resources.displayMetrics.densityDpi,
                r.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            ) ?: return fail("createVirtualDisplay returned null").also { r.close() }

            val p = Presentation(app, vd.display)
            p.window?.apply {
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                )
                setBackgroundDrawable(ColorDrawable(Color.WHITE))
            }
            val c = FrameLayout(p.context)
            p.setContentView(c)
            p.show()

            reader = r
            display = vd
            presentation = p
            container = c
            hostW = w
            hostH = h
            hostFailures = 0
            Log.i(TAG, "host created ${w}x$h dpi=${app.resources.displayMetrics.densityDpi}")
            return c
        } catch (e: Exception) {
            runCatching { vd?.release() }
            runCatching { r?.close() }
            return fail("host setup failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun fail(reason: String): FrameLayout? {
        hostFailures++
        Log.w(TAG, "$reason (failure $hostFailures/$MAX_HOST_FAILURES)")
        if (hostFailures >= MAX_HOST_FAILURES) {
            disabled = reason
            Log.w(TAG, "GPU capture disabled for this process: $reason")
        }
        return null
    }

    /** Tear everything down; the next capture rebuilds on demand. Main thread. */
    private fun release(why: String) {
        if (presentation == null && display == null && reader == null) return
        main.removeCallbacks(releaseTask)
        container?.removeAllViews()
        runCatching { presentation?.dismiss() }
        runCatching { display?.release() }
        latest?.close()
        latest = null
        runCatching { reader?.close() }
        presentation = null
        display = null
        reader = null
        container = null
        Log.i(TAG, "host released ($why) ${hostW}x$hostH")
        hostW = 0
        hostH = 0
    }

    /**
     * Copy an RGBA_8888 [image] into a [w]×[h] ARGB_8888 bitmap. The plane's
     * rows may be padded (rowStride > w*4), and the last row is not padded, so
     * the buffer is re-packed row by row rather than bulk-copied.
     */
    private fun toBitmap(image: Image, w: Int, h: Int): Bitmap {
        val plane = image.planes[0]
        val src = plane.buffer.duplicate().apply { rewind() }
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowBytes = w * pixelStride
        val packed = ByteBuffer.allocateDirect(rowBytes * h)
        val row = ByteArray(rowBytes)
        for (y in 0 until h) {
            src.position(y * rowStride)
            src.get(row, 0, rowBytes)
            packed.put(row)
        }
        packed.rewind()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(packed) }
    }
}
