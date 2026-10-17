package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

/**
 * Vendor logo for a model reference, rendered from a locally packed
 * lobe-icons SVG (res/raw, color variants as-is; res/raw-night for mono
 * brands — the night qualifier implements the SVG's currentColor
 * semantics, no color conversion happens at render time).
 *
 * Behaviour contract (top bar is a high-frequency surface):
 *  - **No placeholder**: renders nothing while loading — the model text
 *    next to it never shifts.
 *  - **Silent failure**: unknown slug or decode error renders nothing.
 *    A wrong or broken icon is worse than none; the text alone already
 *    carries the semantics.
 *  - Coil's memory + disk cache serve repeat renders; SVG decode runs
 *    on Coil's IO dispatcher, never the main thread.
 */
@Composable
internal fun ModelBrandIcon(
    slug: String?,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 14.dp,
) {
    if (slug == null) return
    val resId = remember(slug) { ModelBrandResolver.iconResId(slug) } ?: return
    val context = LocalContext.current
    val request = remember(resId) {
        ImageRequest.Builder(context)
            .data(resId)
            .crossfade(false) // no fade-in flicker on a stable top bar
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null, // decorative; the model text carries meaning
        contentScale = ContentScale.Fit,
        modifier = modifier.size(sizeDp),
        loading = {},
        error = {},
    )
}
