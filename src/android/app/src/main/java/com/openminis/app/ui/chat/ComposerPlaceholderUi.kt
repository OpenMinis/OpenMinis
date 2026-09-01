package com.openminis.app.ui.chat

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R

/**
 * [T-android-composer-placeholder-rotation] Whether a screen reader (TalkBack)
 * is currently driving the UI.
 *
 * Registers a listener rather than sampling once, so toggling TalkBack
 * mid-session takes effect immediately — a user who turns it on partway
 * through must not keep getting placeholder rotations under it.
 */
@Composable
internal fun rememberScreenReaderEnabled(): Boolean {
    val context = LocalContext.current
    val am = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    var enabled by remember { mutableStateOf(am?.isTouchExplorationEnabled == true) }
    DisposableEffect(am) {
        if (am == null) return@DisposableEffect onDispose { }
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on ->
            enabled = on
        }
        am.addTouchExplorationStateChangeListener(listener)
        // Re-sync on (re)subscribe in case it changed while we weren't listening.
        enabled = am.isTouchExplorationEnabled
        onDispose { am.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * [T-android-composer-placeholder-rotation] Whether the user has asked the
 * system to suppress animations ("Remove animations" in Accessibility, or a
 * developer-options animator scale of 0).
 *
 * Android's equivalent of iOS Reduce Motion is the global animator duration
 * scale; a value of 0 means animations are off system-wide, and honouring it
 * is what turns the placeholder crossfade into an instant swap.
 */
internal fun animationsDisabled(context: Context): Boolean =
    android.provider.Settings.Global.getFloat(
        context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f

/**
 * [T-android-composer-placeholder-rotation] Resolve pool entry [index] to its
 * display string.
 *
 * Index 0 is the default "Message <soul> (@ to mention files)"; 1..N are the
 * feature hints. Resolving by index at RENDER time (rather than caching the
 * resolved string when the rotation happens) is deliberate: a soul rename or
 * an in-app locale switch then re-resolves to the correctly-translated current
 * hint instead of leaving a stale string on screen. iOS records the same
 * requirement in `refreshPlaceholderText`.
 *
 * The soul name is interpolated INTO each localized hint via `%1$s`, never
 * concatenated onto it, so every hint stays one translatable entry rather than
 * one per soul name.
 */
@Composable
internal fun composerPlaceholderText(index: Int, soulName: String): String {
    val hints = listOf(
        R.string.chat_input_placeholder_hint_1,
        R.string.chat_input_placeholder_hint_2,
        R.string.chat_input_placeholder_hint_3,
        R.string.chat_input_placeholder_hint_4,
        // Takes no `%1$s` — this hint points at the global Return Key setting
        // rather than the soul. `stringResource` ignores the surplus argument.
        R.string.chat_input_placeholder_hint_5,
    )
    // Defensive clamp: a saved index from a build with a larger pool must not
    // crash after the pool shrinks (rememberSaveable survives app upgrades).
    val safe = index.coerceIn(0, hints.size)
    return if (safe == ComposerPlaceholderRotation.DEFAULT_INDEX) {
        stringResource(R.string.chat_input_placeholder, soulName)
    } else {
        stringResource(hints[safe - 1], soulName)
    }
}
