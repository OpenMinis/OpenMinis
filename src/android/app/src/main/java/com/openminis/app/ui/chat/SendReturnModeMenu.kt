package com.openminis.app.ui.chat

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.openminis.app.R
import com.openminis.app.ui.settings.KEY_RETURN_KEY_BEHAVIOR
import com.openminis.app.ui.settings.getAppearancePrefs
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-send-longpress-return-mode] Pure logic behind the composer Send button's
 * long-press "Return key" chooser. Kept free of Android/Compose types so it is
 * JUnit-testable; the composables below are thin wiring over it.
 *
 * The chooser writes the SAME preference the Appearance → Return Key setting
 * owns (`returnKeyBehavior`, Int 0=Newline, 1=Send) so the two can never
 * disagree — there is deliberately no second "temporary" flag.
 */
object SendReturnMode {
    /** Matches AppearanceScreen's `returnKeyBehavior` values. */
    const val PREF_NEWLINE = 0
    const val PREF_SEND = 1

    /**
     * A tap that lands within this window after the chooser was dismissed is
     * the same finger that dismissed it (outside-tap on a non-focusable popup
     * also reaches the window below). Swallow it so closing the menu by tapping
     * the Send button does not also send the draft.
     */
    const val DISMISS_TAP_GUARD_MS = 350L

    /** Pref value → "does Return send?". Unknown values read as Newline, same
     *  as `returnKeySendsMessage()` (only exactly 1 means Send). */
    fun sendsFromPref(value: Int): Boolean = value == PREF_SEND

    /** "does Return send?" → pref value written back. */
    fun prefFromSends(sends: Boolean): Int = if (sends) PREF_SEND else PREF_NEWLINE

    /**
     * Whether a TAP on the Send button should send. The button stays
     * gesture-enabled even when there is nothing to send (so long-press works
     * on the grey button); the "disabled" part lives here instead.
     */
    fun tapShouldSend(canActivate: Boolean, menuVisible: Boolean, msSinceMenuDismiss: Long): Boolean {
        if (!canActivate) return false
        if (menuVisible) return false
        return msSinceMenuDismiss < 0 || msSinceMenuDismiss >= DISMISS_TAP_GUARD_MS
    }

    /**
     * Pivot, as a fraction of the popup's size, that sits on the anchor's
     * centre. Used as the scale transformOrigin so the menu visibly grows out of
     * the Send button. May fall outside 0..1 (the anchor is outside the popup
     * when the menu sits above it) — TransformOrigin accepts that and it is
     * exactly what makes the scale originate at the button.
     */
    fun pivotFraction(anchorCenter: Int, popupStart: Int, popupSize: Int): Float {
        if (popupSize <= 0) return 0.5f
        return (anchorCenter - popupStart).toFloat() / popupSize.toFloat()
    }

    /**
     * Popup placement: right edges aligned with the anchor (Send sits at the
     * trailing edge of the composer), preferring ABOVE the button with [gapPx]
     * clearance, flipping below only when there is no room above. Returns
     * (x, y) clamped inside the window.
     */
    fun placement(
        anchorLeft: Int, anchorTop: Int, anchorRight: Int, anchorBottom: Int,
        windowWidth: Int, windowHeight: Int,
        popupWidth: Int, popupHeight: Int,
        gapPx: Int,
    ): Pair<Int, Int> {
        val x = (anchorRight - popupWidth)
            .coerceIn(0, (windowWidth - popupWidth).coerceAtLeast(0))
        val above = anchorTop - gapPx - popupHeight
        val y = if (above >= 0) above else anchorBottom + gapPx
        return x to y.coerceIn(0, (windowHeight - popupHeight).coerceAtLeast(0))
    }
}

/**
 * [T-send-longpress-return-mode] Observable read of the Return-key preference.
 * The old composer read `returnKeySendsMessage(context)` as a plain value, so a
 * change only showed up whenever something else happened to recompose the
 * composer. Backing it with a SharedPreferences listener makes a change from the
 * Send-button chooser (or Settings) recompose the text field immediately, which
 * also swaps `ImeAction` — CoreTextField restarts the IME session when its
 * ImeOptions change, so the soft keyboard's action key updates in place.
 */
@Composable
fun rememberReturnKeySendsMessage(context: Context): State<Boolean> {
    val prefs = remember(context) { getAppearancePrefs(context) }
    val state = remember(prefs) {
        mutableStateOf(SendReturnMode.sendsFromPref(prefs.getInt(KEY_RETURN_KEY_BEHAVIOR, SendReturnMode.PREF_NEWLINE)))
    }
    DisposableEffect(prefs) {
        // Held strongly by this effect: SharedPreferences keeps listeners in a
        // WeakHashMap, so an inline lambda would be collected and go silent.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == KEY_RETURN_KEY_BEHAVIOR) {
                state.value = SendReturnMode.sendsFromPref(p.getInt(KEY_RETURN_KEY_BEHAVIOR, SendReturnMode.PREF_NEWLINE))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        // Re-sync in case it changed between remember and registration.
        state.value = SendReturnMode.sendsFromPref(prefs.getInt(KEY_RETURN_KEY_BEHAVIOR, SendReturnMode.PREF_NEWLINE))
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

/** [T-send-longpress-return-mode] Single writer used by the chooser. */
fun setReturnKeySendsMessage(context: Context, sends: Boolean) {
    getAppearancePrefs(context).edit()
        .putInt(KEY_RETURN_KEY_BEHAVIOR, SendReturnMode.prefFromSends(sends))
        .apply()
}

/**
 * [T-send-longpress-return-mode] The composer's Send / Enqueue button.
 *
 * - Tap sends exactly as before (via [onSend]) — but only when [canActivate];
 *   otherwise the tap is a no-op. The button is NOT `clickable(enabled=false)`
 *   any more, because a disabled clickable swallows long-press too and the user
 *   asked for the chooser to work on the grey (empty-composer) button.
 * - Long-press opens [SendReturnModeMenu] anchored to this button.
 * - TalkBack gets a custom action that opens the same menu, so it is reachable
 *   without a long-press gesture.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposerSendButton(
    canActivate: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val sendsOnReturn by rememberReturnKeySendsMessage(context)
    var menuVisible by remember { mutableStateOf(false) }
    var lastDismissAt by remember { mutableLongStateOf(-1L) }
    val closeMenu = {
        if (menuVisible) {
            menuVisible = false
            lastDismissAt = SystemClock.uptimeMillis()
        }
    }
    val a11yLabel = stringResource(R.string.send_return_mode_a11y_action)
    Box(
        modifier = modifier
            .size(38.dp)
            .background(
                if (canActivate) ChatColors.sendButton else ChatColors.sendButtonDisabled,
                CircleShape,
            )
            .clip(CircleShape)
            .combinedClickable(
                onClick = {
                    val since = if (lastDismissAt < 0) -1L else SystemClock.uptimeMillis() - lastDismissAt
                    if (SendReturnMode.tapShouldSend(canActivate, menuVisible, since)) onSend()
                },
                // Unconditional: the chooser must open on the grey (nothing
                // to send) button too. combinedClickable already fires the
                // LongPress haptic.
                onLongClick = { menuVisible = true },
            )
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(a11yLabel) {
                        menuVisible = true
                        true
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.ArrowUpward,
            contentDescription = "Send",
            tint = if (canActivate) ChatColors.background
            else ChatColors.primaryText.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
        SendReturnModeMenu(
            expanded = menuVisible,
            sendsOnReturn = sendsOnReturn,
            onSelect = { sends ->
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                if (sends != sendsOnReturn) setReturnKeySendsMessage(context, sends)
                closeMenu()
            },
            onDismissRequest = closeMenu,
        )
    }
}

/**
 * [T-send-longpress-return-mode] Popup that grows out of the Send button.
 *
 * Animation: one spring-driven `progress` 0→1 feeds scale (0.35→1) and alpha
 * through `graphicsLayer`, with `transformOrigin` computed from the real
 * placement so the pivot sits on the button's centre — the menu visibly
 * expands out of the button and collapses back into it. Unlike [MinisMenu]
 * (enter-only; the Popup unmounts instantly on dismiss) the Popup here stays
 * mounted until the reverse spring finishes, and a re-open mid-collapse simply
 * retargets the same Animatable, so it never jumps.
 *
 * Non-focusable on purpose: a focusable popup window takes input focus, which
 * hides the soft keyboard — wrong for a "quick temporary switch" while typing.
 * Outside taps still dismiss it (dismissOnClickOutside).
 */
@Composable
private fun SendReturnModeMenu(
    expanded: Boolean,
    sendsOnReturn: Boolean,
    onSelect: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            mounted = true
            // Slightly under-damped → a small, lively overshoot on open.
            progress.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 420f))
        } else if (mounted) {
            // Critically damped and stiffer → quick, clean collapse.
            progress.animateTo(0f, spring(dampingRatio = 1f, stiffness = 900f))
            mounted = false
        }
    }
    if (!mounted) return

    val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val origin = remember { mutableStateOf(TransformOrigin(1f, 1f)) }
    val positionProvider = remember(gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val (x, y) = SendReturnMode.placement(
                    anchorBounds.left, anchorBounds.top, anchorBounds.right, anchorBounds.bottom,
                    windowSize.width, windowSize.height,
                    popupContentSize.width, popupContentSize.height,
                    gapPx,
                )
                val o = TransformOrigin(
                    SendReturnMode.pivotFraction(anchorBounds.center.x, x, popupContentSize.width),
                    SendReturnMode.pivotFraction(anchorBounds.center.y, y, popupContentSize.height),
                )
                if (o != origin.value) origin.value = o
                return IntOffset(x, y)
            }
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
    ) {
        val shape = RoundedCornerShape(20.dp)
        // Same surface language as MinisMenu (elevated container in dark,
        // white in light, launcher-style soft halo, hairline border), with a
        // touch of translucency so the composer reads through the edges.
        val container = if (ChatColors.isDark) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White
        val border = if (ChatColors.isDark) {
            BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            BorderStroke(0.5.dp, Color.Black.copy(alpha = 0.05f))
        }
        Surface(
            modifier = Modifier
                .graphicsLayer {
                    val p = progress.value
                    val s = 0.35f + 0.65f * p
                    scaleX = s
                    scaleY = s
                    alpha = p.coerceIn(0f, 1f)
                    transformOrigin = origin.value
                }
                .width(IntrinsicSize.Max)
                .widthIn(min = 200.dp, max = 280.dp)
                .shadow(
                    elevation = 24.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.35f),
                    spotColor = Color.Black.copy(alpha = 0.35f),
                ),
            shape = shape,
            color = container.copy(alpha = 0.97f),
            tonalElevation = 3.dp,
            border = border,
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp).selectableGroup()) {
                val header = stringResource(R.string.appearance_section_return_key)
                Text(
                    text = header,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp)
                        .semantics { heading() },
                )
                ReturnModeRow(
                    label = stringResource(R.string.send_return_mode_sends),
                    icon = Icons.AutoMirrored.Outlined.Send,
                    selected = sendsOnReturn,
                    onClick = { onSelect(true) },
                )
                ReturnModeRow(
                    label = stringResource(R.string.send_return_mode_newline),
                    icon = Icons.AutoMirrored.Outlined.KeyboardReturn,
                    selected = !sendsOnReturn,
                    onClick = { onSelect(false) },
                )
            }
        }
    }
}

@Composable
private fun ReturnModeRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Spacer(modifier = Modifier.size(20.dp))
        }
    }
}
