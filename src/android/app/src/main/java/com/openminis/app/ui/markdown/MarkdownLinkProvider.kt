package com.openminis.app.ui.markdown

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.ui.browser.BrowserExternalSchemeHandler
import com.openminis.app.ui.chat.LocalMarkdownUrlClickHandler

/**
 * [T-android-md-doc-links] Give rendered markdown a URL handler when the caller
 * didn't provide one.
 *
 * Why this exists: block-rendered markdown taps links through
 * [LocalMarkdownUrlClickHandler], which is a nullable CompositionLocal that only
 * `ChatScreen` populated. Surfaces that render markdown *outside* chat — the
 * sandbox file preview (`FilePreviewScreen.MarkdownPreview`), and any future
 * document viewer — therefore rendered links that looked tappable and did
 * nothing at all.
 *
 * The handler deliberately mirrors what a chat link tap already does, so a link
 * behaves identically wherever it is tapped: non-http(s) schemes go through
 * [BrowserExternalSchemeHandler] (which understands `intent://`, `mailto:`,
 * `minis://`), and everything else falls back to a plain `ACTION_VIEW`.
 *
 * An outer provider always wins — if `ChatScreen` (or an in-app browser hand-off)
 * already set a handler, this is a pass-through and changes nothing.
 */
@Composable
internal fun DefaultMarkdownLinks(content: @Composable () -> Unit) {
    val existing = LocalMarkdownUrlClickHandler.current
    if (existing != null) {
        content()
        return
    }
    val context = LocalContext.current
    val handler = remember(context) {
        { url: String ->
            val handled = BrowserExternalSchemeHandler.handle(
                context,
                url,
                BrowserExternalSchemeHandler.Origin.USER_INITIATED,
            )
            if (!handled) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }
    CompositionLocalProvider(LocalMarkdownUrlClickHandler provides handler) {
        content()
    }
}
