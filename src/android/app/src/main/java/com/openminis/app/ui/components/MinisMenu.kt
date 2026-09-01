package com.openminis.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.openminis.app.ui.theme.ChatColors

/**
 * App-wide popup menu with a unified look: softer rounded corners, a lifted
 * shadow, and a clean tonal surface that matches the Teal theme.
 *
 * Drop-in replacement for [androidx.compose.material3.DropdownMenu] — pass
 * [DropdownMenuItem] children as before.
 */
@Composable
fun MinisMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    // [T-android-menu-launcher-shadow] 20dp (was 14) — moved toward the Pixel
    // Launcher shortcut popup's plumper corner language the user asked us to
    // mirror (the launcher itself sits near 28dp, but that reads bloated on a
    // dense 8-item list menu).
    shape: Shape = RoundedCornerShape(20.dp),
    // [T-android-popup-dark-bg] In dark mode bare `surface` is nearly the same
    // as the chat/list background, so these popups (session-row long-press menu,
    // top-bar tools menu, message-bubble menu) read as near-invisible light
    // cards. Use the distinctly-elevated `surfaceContainerHigh` (a lighter grey
    // in dark, a subtle shade in light) so the menu pops out — same fix already
    // validated on the text-selection toolbar (T-android-text-toolbar-dark-
    // visibility). The dark-mode `border` below adds the extra edge definition.
    containerColor: Color = if (ChatColors.isDark) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White,
    tonalElevation: Dp = 3.dp,
    // [T-android-menu-launcher-shadow] Analyzed against the Pixel Launcher
    // long-press popup (screenshot sample): its shadow is a WIDE, very
    // diffuse, low-opacity halo with no tight dark edge — i.e. a large
    // elevation whose shadow color is knocked way down, not a small
    // elevation at full black. The old Surface shadowElevation=4dp gave the
    // opposite (a short, comparatively dark rim). Rendered now via
    // Modifier.shadow with alpha-reduced ambient/spot colors (API 28+;
    // colors are ignored on 26/27, which just fall back to the default
    // tint at the same blur). Surface's own shadowElevation is left at 0.
    shadowElevation: Dp = 24.dp,
    // Light mode now also gets a hairline (was null): with the launcher-style
    // soft halo replacing the old tight shadow, the popup's edge needs a
    // subtle line of its own to stay crisply bounded on light surfaces.
    border: BorderStroke? = if (ChatColors.isDark) {
        BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    } else {
        BorderStroke(0.5.dp, Color.Black.copy(alpha = 0.05f))
    },
    minWidth: Dp = 180.dp,
    // T238: when true, anchor the menu's RIGHT edge to the anchor box's
    // right edge instead of Material3 DropdownMenu's default left-anchor
    // behaviour. Used by the user-message bubble menu so the popup tracks
    // the right-aligned chat bubble — mirrors iOS Messages.app context
    // menu. Vertical placement still goes below the anchor with auto-flip
    // above when there's no room.
    alignEnd: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    // [T-android-minis-menu-ios26-anim] BOTH branches (default left-anchored and
    // alignEnd right-anchored) now render through ONE custom Popup +
    // AnimatedVisibility so every MinisMenu gets the same iOS 26-style
    // 200ms scale-from-anchor + fade enter animation. Material3's DropdownMenu
    // (the old default branch) had a fixed, non-tunable ~120ms scale; converting
    // it to this Popup lets us control duration/origin uniformly. Popup unmounts
    // immediately on expanded=false, so this is an enter-only animation — the
    // dismiss is instant, matching the prior alignEnd behaviour.
    if (!expanded) return
    val density = LocalDensity.current
    val offsetXPx = with(density) { offset.x.roundToPx() }
    val offsetYPx = with(density) { offset.y.roundToPx() }
    Popup(
        onDismissRequest = onDismissRequest,
        properties = properties,
        popupPositionProvider = remember(offsetXPx, offsetYPx, alignEnd) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    // alignEnd: right edge of popup = right edge of anchor.
                    // Default: left edge of popup = left edge of anchor (the
                    // Material3 DropdownMenu default we're replacing).
                    val x = if (alignEnd) {
                        (anchorBounds.right - popupContentSize.width + offsetXPx)
                    } else {
                        (anchorBounds.left + offsetXPx)
                    }.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                    // Below the anchor; auto-flip above when there's no room.
                    // Mirrors DropdownMenu's auto-flip semantics.
                    val belowY = anchorBounds.bottom + offsetYPx
                    val aboveY = anchorBounds.top - popupContentSize.height - offsetYPx
                    val fitsBelow = belowY + popupContentSize.height <= windowSize.height
                    val y = if (fitsBelow || aboveY < 0) belowY else aboveY
                    return IntOffset(
                        x,
                        y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
                    )
                }
            }
        },
    ) {
        // iOS 26-style enter: scale up from the anchor corner + fade, 200ms.
        // Seeded false then flipped true on first composition so the enter
        // animation runs as the Popup mounts. transformOrigin is the anchor
        // corner: top-right for alignEnd (right-anchored), top-left otherwise.
        val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
        val origin = if (alignEnd) {
            androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
        } else {
            androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
        }
        AnimatedVisibility(
            visibleState = visibleState,
            enter = scaleIn(
                animationSpec = tween(200),
                initialScale = 0.85f,
                transformOrigin = origin,
            ) + fadeIn(animationSpec = tween(200)),
            exit = scaleOut(animationSpec = tween(120)) + fadeOut(animationSpec = tween(120)),
        ) {
            Surface(
                // [T-android-minis-menu-width] Size to the CONTENT's intrinsic
                // width (the widest menu item), then clamp to [minWidth, 280dp].
                // This restores Material3 DropdownMenu's "hug the content" sizing
                // that the unified-Popup rewrite lost: without IntrinsicSize.Max,
                // DropdownMenuItem's internal fillMaxWidth expanded the Column to
                // the 280dp cap, making short menus (the top-bar tools menu) look
                // far too wide (T252's "blows out" symptom). 280dp stays as the
                // upper bound so a very long localised item can't span the screen.
                modifier = modifier
                    .width(IntrinsicSize.Max)
                    .widthIn(min = minWidth, max = 280.dp)
                    // Launcher-style soft halo — see the shadowElevation note.
                    .shadow(
                        elevation = shadowElevation,
                        shape = shape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.35f),
                        spotColor = Color.Black.copy(alpha = 0.35f),
                    ),
                shape = shape,
                color = containerColor,
                tonalElevation = tonalElevation,
                shadowElevation = 0.dp,
                border = border,
            ) {
                // Mirrors DropdownMenu's content layout: a vertically-scrollable
                // Column inside the surface (scrollState preserved from the old
                // DropdownMenu branch so long menus still scroll).
                Column(
                    modifier = Modifier.verticalScroll(scrollState),
                    content = content,
                )
            }
        }
    }
}

/**
 * Subtle inset divider for grouping items inside a [MinisMenu]. T289:
 * `outlineVariant×0.35 + 0.5dp` was effectively invisible on the light
 * surface — the divider lines between sections looked like nothing was
 * there. Bumped to `Color.Black/White × 0.12 + 1.dp` so the rule is
 * legible without feeling heavy. Theme-aware so dark mode uses
 * white instead of black for contrast against the dark surface.
 */
@Composable
fun MinisMenuDivider(modifier: Modifier = Modifier) {
    val tint = if (ChatColors.isDark) Color.White else Color.Black
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        thickness = 1.dp,
        color = tint.copy(alpha = 0.12f),
    )
}

/**
 * Consistent row padding + colors for menu items. Use this to tighten the
 * default Material3 48dp row to 44dp and give the leading icon breathing room
 * that matches the iOS context-menu look.
 */
object MinisMenuDefaults {
    val ItemPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 0.dp)

    @Composable
    fun itemColors(
        textColor: Color = MaterialTheme.colorScheme.onSurface,
        leadingIconColor: Color = MaterialTheme.colorScheme.primary,
        trailingIconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    ): MenuItemColors = MenuDefaults.itemColors(
        textColor = textColor,
        leadingIconColor = leadingIconColor,
        trailingIconColor = trailingIconColor,
    )

    @Composable
    fun destructiveItemColors(): MenuItemColors = MenuDefaults.itemColors(
        textColor = MaterialTheme.colorScheme.error,
        leadingIconColor = MaterialTheme.colorScheme.error,
        trailingIconColor = MaterialTheme.colorScheme.error,
    )
}
