package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Group-chat speaker avatar. [T-brand-avatar-single-source] The brand mark is
 * the SAME lobe-icons asset the picker/top bar render — this used to draw 22
 * hand-made geometric glyphs from its own vendor table, a second truth source
 * that drifted from ModelBrandResolver (PR70 lesson: two implementations =
 * divergent behaviour; the picker showed real logos while the group chat drew
 * abstract shapes).
 *
 * Unknown vendors (or legacy persisted keys that no longer resolve) degrade to
 * the initial-letter avatar — never to another vendor's mark.
 */
@Composable
internal fun VendorMark(
    vendor: String,
    fallbackName: String,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    val resId = remember(vendor) { ModelBrandResolver.iconResId(vendor) }
    if (resId != null) {
        ModelBrandIcon(
            slug = vendor,
            modifier = modifier,
            sizeDp = size,
        )
        return
    }
    Box(
        modifier = modifier
            .size(size)
            .background(fallbackVendorColor(fallbackName), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = fallbackName.take(1),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun fallbackVendorColor(name: String): Color {
    val palette = listOf(
        Color(0xFF5B8DEF),
        Color(0xFF3FA36A),
        Color(0xFFD4654A),
        Color(0xFF8A6AD6),
        Color(0xFFC48A2A),
        Color(0xFF3A9CA8),
    )
    return palette[name.hashCode().ushr(1) % palette.size]
}
