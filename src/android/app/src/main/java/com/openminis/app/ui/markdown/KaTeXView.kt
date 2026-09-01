package com.openminis.app.ui.markdown

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.LruCache
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "KaTeXView"

/**
 * [GH#206] Hard ceiling on a captured formula bitmap, in PHYSICAL pixels.
 * Mirrors the constants in KatexWebViewPool — bitmap pixels are NATIVE heap on
 * Android 8+, and these sites previously had no upper bound at all, so one wide
 * display formula could cost several MB. Oversized formulas are scaled DOWN
 * (never clipped), trading sharpness for memory.
 */
private const val MAX_BITMAP_EDGE_PX = 2048
private const val MAX_BITMAP_PIXELS = 4_000_000

/**
 * Renders a LaTeX string using KaTeX in an offscreen WebView, capturing the result as a bitmap.
 * Uses an LRU cache to avoid re-rendering identical expressions.
 */
object KaTeXRendererCache {
    /** [width]/[height] are the bitmap's physical-pixel dimensions
     *  (CSS px * device density) so the image stays sharp on hi-DPI screens.
     *  [cssWidth]/[cssHeight] are KaTeX's reported size in CSS pixels — used
     *  as the dp size for `Image` so the formula displays at the same visual
     *  scale as surrounding text instead of the raw bitmap-pixel size. (T206) */
    data class CacheEntry(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val cssWidth: Int,
        val cssHeight: Int,
    )

    /**
     * [GH#206] Byte budget, mirroring KatexWebViewPool.
     *
     * This was `LruCache(200)` — capacity in ENTRIES with no `sizeOf`, holding
     * ARGB_8888 bitmaps whose pixels live in the NATIVE heap on Android 8+.
     * 200 large formulas could therefore pin hundreds of MB that nothing ever
     * released: this is a process-lifetime `object`, and Java GC does not
     * reclaim native bitmap pixels while the cache still references them.
     *
     * NOTE: entries are NOT recycled on eviction. The bitmap is handed to
     * Compose (`asImageBitmap()`) and an on-screen formula may outlive its
     * cache entry; recycling it would crash the composition. Dropping the
     * reference lets GC reclaim it once nothing draws it. See the matching
     * note in KatexWebViewPool.
     */
    private val cacheBudgetBytes: Int = run {
        val maxHeap = Runtime.getRuntime().maxMemory()
        (maxHeap / 8).coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024).toInt()
    }

    val cache = object : LruCache<String, CacheEntry>(cacheBudgetBytes) {
        override fun sizeOf(key: String, value: CacheEntry): Int =
            value.bitmap.allocationByteCount.coerceAtLeast(1)
    }

    /** [GH#206] Drop every cached formula. Safe at any time — see the note above. */
    fun evictAll() {
        val before = cache.size()
        cache.evictAll()
        android.util.Log.i(
            "KaTeXRendererCache",
            "evictAll: released ~${before / 1024}KB of cached formula bitmaps",
        )
    }

    /**
     * [T-android-inapp-theme-popups #187] `isDark` is part of the key because the
     * cached value is a rendered BITMAP whose glyphs are baked light-on-dark or
     * dark-on-light. Keying only on (latex, displayMode) meant flipping the app
     * theme re-read the colour but still hit the previous theme's bitmap, so a
     * formula kept its old ink until the entry was evicted. Mirrors the sibling
     * cache in KatexWebViewPool.cacheKey, which has always included it.
     */
    fun cacheKey(latex: String, displayMode: Boolean, isDark: Boolean): String =
        (if (displayMode) "D:" else "I:") + (if (isDark) "k:" else "l:") + latex
}

/**
 * Composable that renders a display-mode LaTeX math block.
 */
@Composable
fun MathBlockView(
    latex: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        KaTeXRenderView(latex = latex, displayMode = true)
    }
}

/**
 * Core KaTeX rendering composable. Uses a hidden WebView to render LaTeX, then captures
 * the result as a Bitmap displayed via Image composable. Falls back to monospace text on error.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KaTeXRenderView(
    latex: String,
    displayMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val isDark = ChatColors.isDark
    val fontSize = 16f
    // [T-android-inapp-theme-popups #187] `isDark` must be a remember key too, not
    // just a cacheKey ingredient: without it the memo survives a theme flip and
    // hands back the previous theme's key, so the new key is never even computed.
    val cacheKey = remember(latex, displayMode, isDark) {
        KaTeXRendererCache.cacheKey(latex, displayMode, isDark)
    }

    // Check cache first
    val cached = remember(cacheKey) { KaTeXRendererCache.cache.get(cacheKey) }

    if (cached != null) {
        // T206: bitmap is rendered at density × CSS px for sharpness, but
        // we display at CSS-pixel dp so the formula visually matches the
        // surrounding 16sp text (otherwise Image maps physical pixels 1:1
        // and the formula renders ~density× too large).
        Image(
            bitmap = cached.bitmap.asImageBitmap(),
            contentDescription = "Math: $latex",
            modifier = modifier.size(cached.cssWidth.dp, cached.cssHeight.dp),
        )
        return
    }

    // State for the rendered bitmap
    var renderedBitmap by remember(cacheKey) { mutableStateOf<Bitmap?>(null) }
    // T206: keep CSS size alongside the bitmap so the post-render Image
    // can size itself the same way the cache-hit path does.
    var renderedCssWidth by remember(cacheKey) { mutableStateOf(0) }
    var renderedCssHeight by remember(cacheKey) { mutableStateOf(0) }
    var renderError by remember(cacheKey) { mutableStateOf<String?>(null) }

    if (renderedBitmap != null) {
        Image(
            bitmap = renderedBitmap!!.asImageBitmap(),
            contentDescription = "Math: $latex",
            modifier = modifier.size(renderedCssWidth.dp, renderedCssHeight.dp),
        )
    } else if (renderError != null) {
        // Fallback: show raw LaTeX in monospace
        Text(
            text = latex,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier,
        )
    } else {
        // Placeholder while rendering
        Box(
            modifier = modifier
                .then(
                    if (displayMode) Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                    else Modifier
                        .widthIn(min = 20.dp)
                        .height(20.dp)
                ),
        )

        // Render with offscreen WebView
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(1, 1)
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    // T208 Layer A: keep WebView text-zoom at 100% regardless
                    // of the user's accessibility font-size setting. Without
                    // this, the bitmap KaTeX renders is multiplied by the
                    // system font scale, but our bridge reports the
                    // pre-scale CSS dimensions — Image then displays the
                    // (over-rendered) bitmap inside an undersized box and
                    // ContentScale.Fit makes the formula appear physically
                    // larger than the surrounding 16sp text.
                    settings.textZoom = 100
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onRendered(width: Int, height: Int, error: String) {
                            if (error.isNotEmpty()) {
                                AppLogger.warning(TAG, "KaTeX render failed: $error · latex=${latex.take(80)}")
                                renderError = error
                                return
                            }
                            if (width <= 0 || height <= 0) {
                                AppLogger.warning(TAG, "KaTeX render produced zero dimensions · latex=${latex.take(80)}")
                                renderError = "zero dimensions"
                                return
                            }
                            // Scale for device density
                            val scale = ctx.resources.displayMetrics.density
                            val bitmapW = (width * scale).toInt()
                            val bitmapH = (height * scale).toInt()

                            // Resize WebView to content size, then capture
                            post {
                                layoutParams = ViewGroup.LayoutParams(bitmapW, bitmapH)
                                requestLayout()
                                postDelayed({
                                    // [GH#206] Clamp the captured bitmap. bitmapW/H
                                    // were previously unbounded, so a wide display
                                    // formula allocated multi-MB of NATIVE heap.
                                    // The WebView keeps its full layout size (above)
                                    // so the formula still lays out correctly; only
                                    // the captured bitmap is scaled DOWN, never
                                    // clipped, trading sharpness for memory.
                                    val edgeScale = minOf(
                                        1f,
                                        MAX_BITMAP_EDGE_PX.toFloat() / bitmapW,
                                        MAX_BITMAP_EDGE_PX.toFloat() / bitmapH,
                                    )
                                    val pixelScale =
                                        if (bitmapW.toLong() * bitmapH > MAX_BITMAP_PIXELS) {
                                            kotlin.math.sqrt(
                                                MAX_BITMAP_PIXELS.toDouble() /
                                                    (bitmapW.toDouble() * bitmapH),
                                            ).toFloat()
                                        } else {
                                            1f
                                        }
                                    val capScale = minOf(edgeScale, pixelScale)
                                    val capW = (bitmapW * capScale).toInt().coerceAtLeast(1)
                                    val capH = (bitmapH * capScale).toInt().coerceAtLeast(1)
                                    if (capScale < 1f) {
                                        AppLogger.info(
                                            TAG,
                                            "bitmap clamped ${bitmapW}x$bitmapH -> ${capW}x$capH " +
                                                "(scale=$capScale)",
                                        )
                                    }
                                    val bitmap = Bitmap.createBitmap(capW, capH, Bitmap.Config.ARGB_8888)
                                    val canvas = android.graphics.Canvas(bitmap)
                                    if (capScale < 1f) canvas.scale(capScale, capScale)
                                    draw(canvas)
                                    KaTeXRendererCache.cache.put(
                                        cacheKey,
                                        KaTeXRendererCache.CacheEntry(
                                            // Record the ACTUAL bitmap size, not the
                                            // pre-clamp request — `width`/`height`
                                            // document the bitmap's physical pixels.
                                            // Display sizing uses cssWidth/cssHeight,
                                            // which are unchanged, so a clamped
                                            // formula still lays out identically.
                                            bitmap = bitmap,
                                            width = capW,
                                            height = capH,
                                            cssWidth = width,
                                            cssHeight = height,
                                        )
                                    )
                                    renderedCssWidth = width
                                    renderedCssHeight = height
                                    renderedBitmap = bitmap
                                }, 100)
                            }
                        }
                    }, "AndroidBridge")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            val escapedLatex = latex
                                .replace("\\", "\\\\")
                                .replace("'", "\\'")
                                .replace("\n", "\\n")
                                .replace("\r", "")
                            evaluateJavascript(
                                "renderMath('$escapedLatex', $displayMode, $fontSize, $isDark)",
                                null
                            )
                        }
                    }

                    loadUrl("file:///android_asset/katex/katex-render.html")
                }
            },
            modifier = Modifier.height(0.dp), // Hidden
        )
    }
}
