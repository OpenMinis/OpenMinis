package com.openminis.app.ui.preview

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay

/**
 * Fullscreen variant of the in-chat HTML preview. Hosts the same
 * [WebViewHolder] as [WebPreviewBottomSheet]; toggling expand/collapse
 * never reloads the page.
 *
 * T-html-preview-immersive: now mirrors [com.openminis.app.webapp.WebAppActivity]'s
 * polish — system bars are hidden while the dialog is open (swipe to
 * reveal transient), restored on dismiss, and the toolbar is a floating
 * pill that auto-fades after 4s of inactivity.
 *
 * Distinct from WebAppActivity: that path is the standalone WebApp
 * shortcut launcher; this is the in-chat attachment preview.
 */
@Composable
fun WebPreviewFullscreenScreen(
    holder: WebViewHolder,
    onDismiss: () -> Unit,
    onCollapseToSheet: (() -> Unit)? = null,
    fallbackTitle: String = "",
) {
    val context = LocalContext.current
    val view = LocalView.current
    val darkTheme = ChatColors.isDark

    LaunchedEffect(holder) {
        holder.startIfNeeded()
    }

    DisposableEffect(holder) {
        onDispose {
            holder.detach()
        }
    }

    // T-html-preview-immersive: hide system bars while fullscreen preview
    // is open; restore them on dispose so dismissing the dialog (or the
    // user backgrounding the app) always leaves the host activity in a
    // sane state. We also flip status-bar icon appearance to light, since
    // the WebView content paints under the bar and the swipe-revealed
    // bars need contrast.
    DisposableEffect(view, darkTheme) {
        val window = (view.context as? Activity)?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowInsetsControllerCompat(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                controller.show(WindowInsetsCompat.Type.systemBars())
                // [T-android-preview-statusbar-leak] Restore to what the THEME
                // implies rather than to a value captured on entry — see the
                // matching note in WebPreviewBottomSheet.
                //
                // The capture was actively harmful here: the bottom sheet sets
                // this same activity-window flag to `!darkTheme`, and tapping
                // "fullscreen" swaps sheet → dialog in one recomposition with
                // no guaranteed ordering between the sheet's onDispose and this
                // effect's enter. In light theme this could snapshot the
                // sheet's `true`, then write it back on dismiss, leaving DARK
                // status-bar icons over the app's dark chrome — the reported
                // "顶部状态条变成白色，系统状态条看不清楚".
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    Dialog(
        onDismissRequest = {
            AppLogger.info("WebPreviewFullscreen", "dismiss")
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // The Dialog hosts its own window — make its status / nav bar
        // transparent so the black WebView background paints under them
        // (matches the activity-edge-to-edge style). Without this the
        // dialog window's default opaque bar background leaks as a grey
        // strip above the WebView.
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val win = findDialogWindow(dialogView)
            win?.let { w ->
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
                // FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS: tell the system that THIS
                // window paints under the system bars itself, so the system
                // doesn't fill the status/nav bar region with its own opaque
                // background. Without this the dialog window leaves a grey
                // band under the status bar even with statusBarColor=TRANSPARENT.
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                @Suppress("DEPRECATION")
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                @Suppress("DEPRECATION")
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                val ctrl = WindowInsetsControllerCompat(w, w.decorView)
                ctrl.isAppearanceLightStatusBars = false
                ctrl.isAppearanceLightNavigationBars = false
            }
            onDispose { }
        }

        // BackHandler ensures BACK collapses to sheet (if available) or
        // dismisses the dialog — Dialog's dismissOnBackPress would skip
        // the collapse path otherwise.
        BackHandler(enabled = true) {
            if (onCollapseToSheet != null) {
                AppLogger.info("WebPreviewFullscreen", "back → collapse to sheet")
                onCollapseToSheet()
            } else {
                AppLogger.info("WebPreviewFullscreen", "back → dismiss")
                onDismiss()
            }
        }

        var toolbarVisible by remember { mutableStateOf(true) }
        var interactionTick by remember { mutableStateOf(0) }

        LaunchedEffect(toolbarVisible, interactionTick) {
            if (toolbarVisible) {
                delay(AUTO_HIDE_MS)
                toolbarVisible = false
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AndroidView(
                factory = {
                    val wv = holder.webView
                    // Tap-to-show — return false so WebView still handles input.
                    wv.setOnTouchListener { _, ev ->
                        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                            toolbarVisible = true
                            interactionTick += 1
                        }
                        false
                    }
                    wv
                },
                modifier = Modifier.fillMaxSize(),
            )

            val navInset = WindowInsets.navigationBars.asPaddingValues()
                .calculateBottomPadding()

            AnimatedVisibility(
                visible = toolbarVisible,
                enter = fadeIn(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(300)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = navInset + 16.dp),
            ) {
                FloatingMenuButton(
                    isLoading = holder.isLoading,
                    onClose = onDismiss,
                    onReload = {
                        toolbarVisible = true
                        interactionTick += 1
                        holder.reload()
                    },
                    onStop = {
                        toolbarVisible = true
                        interactionTick += 1
                        holder.stopLoading()
                    },
                    onOpenExternal = {
                        toolbarVisible = true
                        interactionTick += 1
                        // T-htmlpreview-2d5c4f3d: share the FileProvider-
                        // aware helper from WebPreviewBottomSheet so the
                        // fullscreen "open in browser" button also works
                        // for file:// URLs (sandbox HTML).
                        com.openminis.app.ui.preview.openExternalFromSheet(
                            context, holder.currentUrl,
                        )
                    },
                    onCollapse = onCollapseToSheet?.let {
                        {
                            toolbarVisible = true
                            interactionTick += 1
                            it()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FloatingMenuButton(
    isLoading: Boolean,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onOpenExternal: () -> Unit,
    onCollapse: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = androidx.compose.foundation.shape.CircleShape,
                )
                .clickable(onClick = { menuOpen = true }),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Outlined.MoreHoriz,
                contentDescription = stringResource(R.string.webpreview_more),
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
        com.openminis.app.ui.components.MinisMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            alignEnd = true,
        ) {
            if (onCollapse != null) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { androidx.compose.material3.Text(stringResource(R.string.webapp_action_exit_fullscreen)) },
                    leadingIcon = {
                        Icon(Icons.Outlined.FullscreenExit, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onCollapse()
                    },
                )
            }
            if (isLoading) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { androidx.compose.material3.Text(stringResource(R.string.webpreview_stop)) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Stop, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onStop()
                    },
                )
            } else {
                androidx.compose.material3.DropdownMenuItem(
                    text = { androidx.compose.material3.Text(stringResource(R.string.webpreview_reload)) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onReload()
                    },
                )
            }
            androidx.compose.material3.DropdownMenuItem(
                text = { androidx.compose.material3.Text(stringResource(R.string.webpreview_open_external)) },
                leadingIcon = {
                    Icon(Icons.Outlined.OpenInBrowser, contentDescription = null)
                },
                onClick = {
                    menuOpen = false
                    onOpenExternal()
                },
            )
            com.openminis.app.ui.components.MinisMenuDivider()
            androidx.compose.material3.DropdownMenuItem(
                text = {
                    androidx.compose.material3.Text(
                        stringResource(R.string.webpreview_close),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = null,
                        tint = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    menuOpen = false
                    onClose()
                },
            )
        }
    }
}

private const val AUTO_HIDE_MS = 4000L

private fun findDialogWindow(view: android.view.View): android.view.Window? {
    var p: android.view.ViewParent? = view.parent
    while (p != null) {
        if (p is androidx.compose.ui.window.DialogWindowProvider) return p.window
        p = p.parent
    }
    return null
}
