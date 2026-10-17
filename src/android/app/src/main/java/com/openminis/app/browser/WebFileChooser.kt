package com.openminis.app.browser

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/**
 * Hand WebView's one-shot file-picker callback to the Compose host that owns
 * the ActivityResultLauncher. Kept out of [BrowserUseManager] so the 1900-line
 * manager does not grow past its architecture ceiling for a UI concern.
 *
 * Returning false without answering the callback would leave the page's
 * `<input type=file>` permanently pending; every failure path answers null.
 */
internal fun dispatchWebFileChooser(
    host: ((WebChromeClient.FileChooserParams, ValueCallback<Array<Uri>>) -> Unit)?,
    filePathCallback: ValueCallback<Array<Uri>>?,
    params: WebChromeClient.FileChooserParams?,
): Boolean {
    val callback = filePathCallback ?: return false
    if (host == null || params == null) {
        callback.onReceiveValue(null)
        return false
    }
    return try {
        host(params, callback)
        true
    } catch (_: Throwable) {
        callback.onReceiveValue(null)
        false
    }
}
