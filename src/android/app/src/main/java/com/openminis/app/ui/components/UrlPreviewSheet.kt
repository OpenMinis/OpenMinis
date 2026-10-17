package com.openminis.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.net.Uri
import com.openminis.app.ui.preview.WebPreviewBottomSheet
import com.openminis.app.ui.preview.rememberWebViewHolder

/**
 * Bottom-sheet web preview for a URL tapped inside chat markdown.
 *
 * Thin wrapper around [WebPreviewBottomSheet]: callers that don't manage
 * their own [com.openminis.app.ui.preview.WebViewHolder] (most chat-side
 * link taps) get the same toolbar / sheet UX as the in-chat HTML preview
 * by going through this helper.
 *
 * Fullscreen expand is intentionally not exposed here — chat URL preview
 * was a single-sheet flow on iOS too.
 */
@Composable
fun UrlPreviewSheet(
    url: String,
    onDismiss: () -> Unit,
) {
    val holder = rememberWebViewHolder(url)
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        callback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
    }
    DisposableEffect(holder) {
        holder.onShowFileChooser = { params, callback ->
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = callback
            try {
                fileChooserLauncher.launch(params.createIntent())
            } catch (t: Throwable) {
                pendingFileCallback = null
                callback.onReceiveValue(null)
            }
        }
        onDispose {
            holder.onShowFileChooser = null
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
        }
    }
    WebPreviewBottomSheet(
        holder = holder,
        onDismiss = {
            holder.destroy()
            onDismiss()
        },
    )
}
