package com.openminis.app.webapp

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.openminis.app.MainActivity
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * T-pwa-1 (renamed Pwa → WebApp): immersive WebView host for a pinned
 * WebApp shortcut. Receives the `webapp_shortcut_id` intent extra (sent
 * by [ShortcutPinner]'s launchIntent), resolves the backing HTML file
 * via [WebAppPathResolver], and renders it
 * via [WebViewAssetLoader] under `https://appassets.androidplatform.net/`.
 * External navigations are blocked — only the asset-loader host and
 * `about:` URIs are allowed.
 *
 * T-pwa-preview-polish: replaces the bare full-bleed WebView with a
 * Compose host that overlays a translucent floating toolbar (close,
 * fullscreen toggle, open-in-browser, share, reload). Toolbar auto-fades
 * after 4s of inactivity and reappears on tap. Default state is
 * non-fullscreen (system bars visible); fullscreen is opt-in via the
 * toolbar button. iOS analog is the edge-swipe-to-dismiss minimal host —
 * Android lacks that gesture vocabulary so we surface a small set of
 * controls instead.
 */
class WebAppActivity : ComponentActivity() {

    private val logTag = "WebAppActivity"
    private var webViewRef: WebView? = null
    private var resolvedFile: File? = null
    private var isFullscreen: Boolean = false

    // T-n01-andmenu-l10n: pre-Tiramisu locale override (see MainActivity).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.openminis.app.i18n.LocaleWrap.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Default state: draw under system bars but keep them visible.
        // Fullscreen toggle hides them on demand. Both bars are made
        // transparent so the WebView truly bleeds edge-to-edge instead of
        // sitting under an opaque status-bar strip.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            // Default to light (white) icons since arbitrary WebApp content
            // could be either light or dark — the dark scrim behind the
            // floating toolbar covers the worst-case white-on-white.
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        applySystemBars(fullscreen = false)

        // T-pwa-3: back press — exit fullscreen if active, else finish.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    isFullscreen = false
                    applySystemBars(fullscreen = false)
                    // Re-render Compose host so toolbar icon updates.
                    renderHost()
                } else {
                    finish()
                }
            }
        })

        val shortcutId = intent?.getStringExtra(EXTRA_SHORTCUT_ID)
        if (shortcutId.isNullOrBlank()) {
            renderSourceMissing(sourceSessionId = null)
            return
        }

        lifecycleScope.launch {
            val app = applicationContext as MinisApp
            val shortcut = withContext(Dispatchers.IO) {
                app.webAppShortcutRepository.get(shortcutId)
            }
            if (shortcut == null) {
                renderSourceMissing(sourceSessionId = null)
                return@launch
            }
            val file = withContext(Dispatchers.IO) {
                WebAppPathResolver.resolve(this@WebAppActivity, shortcut)
            }
            if (file == null) {
                AppLogger.warning(logTag, "WebApp source missing for shortcut ${shortcut.id}: ${shortcut.htmlPath}")
                renderSourceMissing(sourceSessionId = shortcut.sourceSessionId)
                return@launch
            }
            shortcut.title.takeIf { it.isNotBlank() }?.let { title = it }
            resolvedFile = file
            renderHost()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-apply current bar state — dialogs / external activities can
        // resurrect bars even when we asked for fullscreen.
        applySystemBars(fullscreen = isFullscreen)
    }

    private fun applySystemBars(fullscreen: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun renderHost() {
        val file = resolvedFile ?: return
        setContent {
            MaterialTheme {
                WebAppHost(
                    file = file,
                    initialFullscreen = isFullscreen,
                    onFullscreenChange = { fs ->
                        isFullscreen = fs
                        applySystemBars(fullscreen = fs)
                    },
                    onClose = { finish() },
                    onWebViewReady = { wv -> webViewRef = wv },
                )
            }
        }
    }

    /**
     * T-pwa-3: replaces the prior toast-and-finish with an inline error
     * screen offering an "Open in Minis" escape hatch.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun renderSourceMissing(sourceSessionId: String?) {
        webViewRef?.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webViewRef = null

        setContent {
            MaterialTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text(stringResource(R.string.webapp_activity_label)) })
                    },
                ) { padding ->
                    Box(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Card(modifier = Modifier.padding(24.dp)) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(R.string.webapp_source_missing_title),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(R.string.webapp_source_missing_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                MinisButton(onClick = { openInMinis(sourceSessionId) }) {
                                    Text(stringResource(R.string.webapp_open_in_minis))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openInMinis(sourceSessionId: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            sourceSessionId?.let { putExtra(EXTRA_TARGET_SESSION_ID, it) }
        }
        startActivity(intent)
        finish()
    }

    @Composable
    private fun WebAppHost(
        file: File,
        initialFullscreen: Boolean,
        onFullscreenChange: (Boolean) -> Unit,
        onClose: () -> Unit,
        onWebViewReady: (WebView) -> Unit,
    ) {
        val context = LocalContext.current
        var fullscreen by remember { mutableStateOf(initialFullscreen) }
        var toolbarVisible by remember { mutableStateOf(true) }
        // Bumped on each user interaction; LaunchedEffect uses it as a key
        // so the auto-hide timer restarts on every tap.
        var interactionTick by remember { mutableStateOf(0) }
        var webViewState by remember { mutableStateOf<WebView?>(null) }

        LaunchedEffect(toolbarVisible, interactionTick) {
            if (toolbarVisible) {
                delay(AUTO_HIDE_MS)
                toolbarVisible = false
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val wv = WebView(ctx)
                    CookieManager.getInstance().apply {
                        setAcceptCookie(false)
                        setAcceptThirdPartyCookies(wv, false)
                    }
                    configureWebView(wv)
                    // Tap-to-show — return false so WebView still handles the input.
                    wv.setOnTouchListener { _, ev ->
                        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                            toolbarVisible = true
                            interactionTick += 1
                        }
                        false
                    }
                    loadHtml(wv, file)
                    onWebViewReady(wv)
                    webViewState = wv
                    wv
                },
            )

            // Top inset spacer reserves room when system bars are visible so
            // the toolbar doesn't sit under the status bar.
            val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

            AnimatedVisibility(
                visible = toolbarVisible,
                enter = fadeIn(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(300)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topInset + 12.dp),
            ) {
                FloatingToolbar(
                    fullscreen = fullscreen,
                    onClose = onClose,
                    onToggleFullscreen = {
                        fullscreen = !fullscreen
                        onFullscreenChange(fullscreen)
                        toolbarVisible = true
                        interactionTick += 1
                    },
                    onOpenInBrowser = {
                        toolbarVisible = true
                        interactionTick += 1
                        openInExternalBrowser(context, file)
                    },
                    onShare = {
                        toolbarVisible = true
                        interactionTick += 1
                        shareWebAppFile(context, file)
                    },
                    onReload = {
                        toolbarVisible = true
                        interactionTick += 1
                        webViewState?.reload()
                    },
                )
            }
        }
    }

    @Composable
    private fun FloatingToolbar(
        fullscreen: Boolean,
        onClose: () -> Unit,
        onToggleFullscreen: () -> Unit,
        onOpenInBrowser: () -> Unit,
        onShare: () -> Unit,
        onReload: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .background(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(28.dp),
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.webapp_action_close),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onReload) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.webapp_action_reload),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    imageVector = if (fullscreen) Icons.Outlined.FullscreenExit
                    else Icons.Outlined.Fullscreen,
                    contentDescription = stringResource(
                        if (fullscreen) R.string.webapp_action_exit_fullscreen
                        else R.string.webapp_action_fullscreen
                    ),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onShare) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = stringResource(R.string.webapp_action_share),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onOpenInBrowser) {
                Icon(
                    imageVector = Icons.Outlined.OpenInBrowser,
                    contentDescription = stringResource(R.string.webapp_action_open_browser),
                    tint = Color.White,
                )
            }
        }
    }

    /**
     * Stage the HTML file under cacheDir/share/ (covered by FileProvider's
     * <cache-path name="share" path="share/"/>) and hand its content URI
     * to the system browser. Copying — rather than exposing the original
     * `<filesDir>/sessions/...` path — keeps the FileProvider authority
     * surface narrow and matches LogManagementScreen's pattern.
     */
    private fun openInExternalBrowser(context: android.content.Context, file: File) {
        val uri = stagedShareUri(context, file) ?: run {
            Toast.makeText(context, R.string.webapp_no_browser_app, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/html")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            AppLogger.warning(logTag, "No browser app for WebApp preview: ${e.message}")
            Toast.makeText(context, R.string.webapp_no_browser_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareWebAppFile(context: android.content.Context, file: File) {
        val uri = stagedShareUri(context, file) ?: run {
            Toast.makeText(context, R.string.webapp_no_browser_app, Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            send,
            context.getString(R.string.webapp_share_chooser_title),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try {
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            AppLogger.warning(logTag, "Share chooser failed: ${e.message}")
        }
    }

    /**
     * Copy [file] into `cacheDir/share/` and return a FileProvider Uri for
     * it. Returns null on IO failure. The staged file overwrites any prior
     * copy with the same name — share/ accumulates one entry per WebApp.
     */
    private fun stagedShareUri(context: android.content.Context, file: File): Uri? {
        return try {
            val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
            val stem = file.nameWithoutExtension.ifBlank { "webapp" }
            val staged = File(shareDir, "$stem.html")
            file.copyTo(staged, overwrite = true)
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                staged,
            )
        } catch (t: Throwable) {
            AppLogger.warning(logTag, "FileProvider staging failed: ${t.message}")
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mediaPlaybackRequiresUserGesture = false
        }
    }

    private fun loadHtml(webView: WebView, file: File) {
        val parent = file.parentFile ?: run {
            renderSourceMissing(sourceSessionId = null)
            return
        }
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/",
                WebViewAssetLoader.InternalStoragePathHandler(this, parent),
            )
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ) = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url ?: return true
                val scheme = url.scheme?.lowercase()
                val host = url.host?.lowercase()
                if (scheme == "https" && host == ASSET_LOADER_HOST) return false
                if (scheme == "about") return false
                AppLogger.info(logTag, "Blocked external navigation: $url")
                Toast.makeText(
                    this@WebAppActivity,
                    R.string.webapp_external_nav_blocked,
                    Toast.LENGTH_SHORT,
                ).show()
                return true
            }
        }

        val url = "https://$ASSET_LOADER_HOST/${file.name}"
        webView.loadUrl(url)
    }

    override fun onDestroy() {
        webViewRef?.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webViewRef = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SHORTCUT_ID = "webapp_shortcut_id"
        const val ACTION_OPEN_WEBAPP = "com.openminis.app.action.OPEN_WEBAPP"
        const val EXTRA_TARGET_SESSION_ID = "target_session_id"
        private const val ASSET_LOADER_HOST = "appassets.androidplatform.net"
        private const val AUTO_HIDE_MS = 4000L
    }
}
