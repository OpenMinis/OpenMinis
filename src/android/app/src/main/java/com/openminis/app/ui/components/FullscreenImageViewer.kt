package com.openminis.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen immersive image preview with pinch-to-zoom, pan, tap-to-toggle chrome,
 * and Copy / Share / Save action buttons.
 *
 * Shown as a true immersive fullscreen dialog: status bar and navigation bar are hidden
 * while the viewer is open, matching the iOS image preview experience.
 */
@Composable
fun FullscreenImageViewer(
    model: Any,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // T169: Hide system bars on entry, restore on exit.
    //
    // T152's first cut also called `WindowCompat.setDecorFitsSystemWindows(
    // window, false)` on entry and `..., true)` on dispose. That broke the
    // activity's edge-to-edge state — MainActivity has already called
    // enableEdgeToEdge() at startup (decorFitsSystemWindows=false), so the
    // dispose path was reverting the activity OUT of edge-to-edge instead
    // of back to its prior state. After the first viewer dismiss, the
    // activity's status / nav bars rendered with the platform's default
    // light-grey scrim instead of the transparent edge-to-edge surface,
    // and re-entering the viewer never recovered because the appearance
    // flag flips don't paint a transparent bar by themselves.
    //
    // Keep the appearance-flag flip + status/nav bar transparency for the
    // viewer's lifetime, snapshot the previous values, restore them on
    // dispose. Don't touch decorFitsSystemWindows on the activity window —
    // that's MainActivity's lifecycle concern.
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
            ?: findWindowViaDialog(view)
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        val prevLightStatus = controller?.isAppearanceLightStatusBars
        val prevLightNav = controller?.isAppearanceLightNavigationBars
        @Suppress("DEPRECATION")
        val prevStatusBarColor = window?.statusBarColor
        @Suppress("DEPRECATION")
        val prevNavBarColor = window?.navigationBarColor

        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        @Suppress("DEPRECATION")
        window?.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window?.navigationBarColor = android.graphics.Color.TRANSPARENT
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            prevLightStatus?.let { controller.isAppearanceLightStatusBars = it }
            prevLightNav?.let { controller.isAppearanceLightNavigationBars = it }
            @Suppress("DEPRECATION")
            prevStatusBarColor?.let { window.statusBarColor = it }
            @Suppress("DEPRECATION")
            prevNavBarColor?.let { window.navigationBarColor = it }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Apply immersive flags to the dialog window itself. T152: also flip
        // appearance flags to dark-bar (light icons) so any transient peek of
        // status/nav bars shows light icons on the black image canvas instead
        // of the activity's light-theme dark icons leaking through.
        // T169: scope these mutations to a DisposableEffect so they fire
        // exactly once per dialog composition (not on every recomposition).
        // The dialog window itself is destroyed when the viewer dismisses,
        // so no restore work is needed — the activity-window block above
        // takes care of restoring the underlying activity's appearance.
        val dialogContainer = LocalView.current.parent as? android.view.ViewGroup
        DisposableEffect(dialogContainer) {
            val win = dialogContainer?.let { findDialogWindow(it) }
            win?.let { w ->
                w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                WindowCompat.setDecorFitsSystemWindows(w, false)
                @Suppress("DEPRECATION")
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                @Suppress("DEPRECATION")
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                val ctrl = WindowInsetsControllerCompat(w, w.decorView)
                ctrl.isAppearanceLightStatusBars = false
                ctrl.isAppearanceLightNavigationBars = false
                ctrl.hide(WindowInsetsCompat.Type.systemBars())
                ctrl.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            onDispose { /* dialog window is going away — nothing to restore */ }
        }

        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        var showChrome by remember { mutableStateOf(true) }
        // [T-android-image-save-original] Full-resolution decode once the user
        // asks for the original: AsyncImage renders a Coil request sized to the
        // composable, so zooming past 1x on a large photo shows interpolation
        // rather than detail. Non-null here means "displaying the original".
        var originalBitmap by remember(model) { mutableStateOf<android.graphics.Bitmap?>(null) }
        var loadingOriginal by remember(model) { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            // ── Image ──────────────────────────────────────────────────────────
            AsyncImage(
                model = originalBitmap ?: model,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f; offsetX = 0f; offsetY = 0f
                                } else {
                                    scale = 2.5f
                                }
                            },
                            onTap = { showChrome = !showChrome },
                        )
                    },
            )

            // ── Close button (top-end) ─────────────────────────────────────────
            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(8.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            // ── Bottom action buttons ──────────────────────────────────────────
            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                // T170: viewer canvas is always Color.Black regardless of
                // theme, so the pre-T170 0xCC1C1C1E pill (≈ #1C1B1F at 80%
                // alpha) blended into the canvas — users couldn't see the
                // button row's edges. Lift the bg to iOS Photos' #3A3A3C
                // (one shade lighter than systemGray6 dark) and add a 1dp
                // white@15% hairline border so the pill shape reads cleanly
                // against pure black. The opaque bg also makes label text
                // contrast more reliable than the prior 0xCC alpha blend.
                val pillShape = RoundedCornerShape(32.dp)
                Row(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                        .background(color = Color(0xFF3A3A3C), shape = pillShape)
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.15f),
                            shape = pillShape,
                        )
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Copy
                    ImageActionButton(
                        icon = Icons.Outlined.ContentCopy,
                        label = stringResource(R.string.image_action_copy),
                        onClick = {
                            // T139: copyBitmapToClipboard is now self-contained —
                            // moves IO off main, grants URI read perm to all
                            // packages, and toasts success/failure on main.
                            copyBitmapToClipboard(context, scope, model)
                        },
                    )
                    // Share — T197: in-flight dedup so rapid taps don't spawn N
                    // concurrent loadBitmap coroutines (OOM on big PNGs).
                    var sharing by remember { mutableStateOf(false) }
                    ImageActionButton(
                        icon = Icons.Outlined.Share,
                        label = stringResource(R.string.image_action_share),
                        onClick = onClick@{
                            if (sharing) return@onClick
                            sharing = true
                            scope.launch {
                                try {
                                    shareImage(context, model)
                                } finally {
                                    sharing = false
                                }
                            }
                        },
                    )
                    // Save
                    val savedToAlbumMsg = stringResource(R.string.image_saved_to_album_toast)
                    val saveFailedMsg = stringResource(R.string.image_save_failed_toast)
                    ImageActionButton(
                        icon = Icons.Outlined.Download,
                        label = stringResource(R.string.image_action_save),
                        onClick = {
                            scope.launch {
                                // [T-android-image-save-original] Save the ORIGINAL
                                // bytes when they are readable: the format and its
                                // metadata survive, and a JPEG no longer leaves the
                                // gallery as a much larger re-encoded PNG.
                                val saved = saveImageToGallery(context, model)
                                Toast.makeText(
                                    context,
                                    if (saved) savedToAlbumMsg else saveFailedMsg,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                    // Original — swap in a full-resolution decode for inspection.
                    ImageActionButton(
                        icon = Icons.Outlined.Image,
                        label = stringResource(R.string.image_action_original),
                        onClick = onClick@{
                            if (originalBitmap != null || loadingOriginal) return@onClick
                            loadingOriginal = true
                            scope.launch {
                                try {
                                    originalBitmap = loadOriginalImage(context, model)
                                        ?.let { original ->
                                            android.graphics.BitmapFactory.decodeByteArray(
                                                original.bytes, 0, original.bytes.size,
                                            )
                                        }
                                    if (originalBitmap == null) {
                                        Toast.makeText(
                                            context,
                                            saveFailedMsg,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                } finally {
                                    loadingOriginal = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

// ── Helper Composable ──────────────────────────────────────────────────────────

@Composable
internal fun ImageActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ── Image loading helpers ──────────────────────────────────────────────────────

internal suspend fun loadBitmap(context: Context, model: Any): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            val loader = ImageLoader(context)
            val req = ImageRequest.Builder(context).data(model).allowHardware(false).build()
            val result = loader.execute(req)
            (result as? SuccessResult)?.drawable?.toBitmap()
        } catch (e: Exception) {
            null
        }
    }

/**
 * T139: Copy the displayed image to the system clipboard. Two issues with
 * the previous implementation crashed the app on big images:
 *  1) `bitmap.compress()` ran on the main thread (it was wrapped in
 *     `scope.launch {}` with the default Main dispatcher), causing ANR on
 *     multi-MB PNG encodes.
 *  2) The clipboard URI was handed to ClipboardManager without
 *     `grantUriPermission` + `FLAG_GRANT_READ_URI_PERMISSION`, so the
 *     receiving paste target (any process reading the clip) hit
 *     SecurityException reading our FileProvider authority.
 *
 * The fix loads the bitmap + writes the temp file on Dispatchers.IO,
 * explicitly grants read permission to all packages for the temp URI,
 * then hops to Main to set the clip + toast. All paths surface a Toast
 * so the user sees a result instead of a silent dismiss.
 */
internal fun copyBitmapToClipboard(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    model: Any,
) {
    scope.launch(Dispatchers.IO) {
        try {
            val bitmap = loadBitmap(context, model)
                ?: error("decode failed")
            // T207: write under cache/share/ so FileProvider's <cache-path
            // name="share"> root matches. Files in cacheDir root aren't
            // covered by any declared root and would throw
            // IllegalArgumentException at getUriForFile.
            val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(shareDir, "clipboard_img_${System.currentTimeMillis()}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            // Grant read access to anyone who pastes the clip. Without this
            // the receiving app (Photos, Gboard preview, Files) gets a
            // SecurityException on contentResolver.openInputStream(uri),
            // which the system surfaces as an immediate crash on some OEMs.
            context.grantUriPermission("*", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val clip = ClipData.newUri(context.contentResolver, "image", uri)
            withContext(Dispatchers.Main) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(clip)
                Toast.makeText(context, context.getString(R.string.image_copied_toast), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.image_copy_failed_toast, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

internal suspend fun shareImage(context: Context, model: Any) {
    // T197: previously a silent `?: return` on decode failure made users think
    // the button did nothing, and a missing FLAG_ACTIVITY_NEW_TASK threw
    // AndroidRuntimeException when LocalContext resolved to a non-Activity
    // (Dialog inside an inner ContextWrapper). Toast on every failure path,
    // and stamp NEW_TASK only when the context is not an Activity.
    try {
        val bmp = loadBitmap(context, model) ?: run {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.image_load_failed_toast), Toast.LENGTH_SHORT).show()
            }
            return
        }
        withContext(Dispatchers.IO) {
            // T207: see clipboard path above — must live under cache/share/
            // for FileProvider to resolve the URI.
            val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(shareDir, "share_img_${System.currentTimeMillis()}.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            val chooser = Intent.createChooser(intent, context.getString(R.string.image_share_chooser_title)).apply {
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            withContext(Dispatchers.Main) {
                context.startActivity(chooser)
            }
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(R.string.image_share_failed_toast, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }
}

// ── Window helpers ─────────────────────────────────────────────────────────────

private fun findWindowViaDialog(view: android.view.View): android.view.Window? {
    var v: android.view.View? = view
    while (v != null) {
        val ctx = v.context
        if (ctx is android.app.Activity) return ctx.window
        v = v.parent as? android.view.View
    }
    return null
}

private fun findDialogWindow(root: android.view.ViewGroup): android.view.Window? {
    // Walk up to find the dialog's PhoneWindow via the ViewRootImpl token
    var p: android.view.ViewParent? = root.parent
    while (p != null) {
        if (p is android.view.View) {
            val ctx = p.context
            if (ctx is android.app.Activity) return ctx.window
        }
        p = (p as? android.view.View)?.parent
    }
    return null
}
