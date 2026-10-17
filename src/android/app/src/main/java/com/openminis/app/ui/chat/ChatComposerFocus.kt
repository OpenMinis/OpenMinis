package com.openminis.app.ui.chat

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay

/**
 * [T-android-entry-ime-guard] Entry/exit keyboard-focus policy for the chat
 * composer.
 *
 * Entering or switching a conversation is navigation, not an input action, so
 * the screen must not request composer focus on its own: doing so opens the IME
 * on every new draft/session visit. The user explicitly focuses the composer
 * when ready.
 *
 * Clearing focus on the first frame alone is not enough: the screen it replaces
 * (the session list, usually with its search field focused) is still composed
 * for the pane transition, and the platform can re-show the IME for that view's
 * still-live input connection a frame or two after the hide. Repeating the
 * clear+hide through the transition window is what actually keeps the keyboard
 * down — a single call is a no-op whenever it races the animation.
 *
 * Extracted from ChatScreen so that file stays under its architecture ceiling.
 */
internal class ComposerEntryFocusGuard {
    var guardUntil: Long = 0L
    private var touched: Boolean = false

    /** The user did something with the composer; stop dropping focus. */
    fun markTouched() {
        touched = true
    }

    /** An intentional focus request (reply-end autofocus, swipe-up) wins. */
    fun release() {
        guardUntil = 0L
    }

    /** True while a focus change should be refused as an entry ghost. */
    fun shouldDropFocus(): Boolean =
        !touched && SystemClock.uptimeMillis() < guardUntil
}

@Composable
internal fun rememberComposerEntryFocusGuard(sessionId: String): ComposerEntryFocusGuard {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val guard = remember(sessionId) {
        ComposerEntryFocusGuard().also {
            it.guardUntil = SystemClock.uptimeMillis() + 600L
        }
    }
    LaunchedEffect(sessionId) {
        repeat(3) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            delay(90)
        }
        // Keep refusing focus for the rest of the entry window: if something
        // still hands focus to the composer (OEM IME restoring state), the
        // onFocusChanged hook in ChatScreen drops it instead of opening the
        // keyboard.
        guard.guardUntil = SystemClock.uptimeMillis() + 400L
    }
    return guard
}

/**
 * Reply-end focus is opt-in (Appearance → Auto-Focus After Reply). The setting
 * must be real: when enabled, refocus only on a streaming→idle edge observed by
 * this screen; entering an already-running session never opens the IME by
 * itself.
 */
@Composable
internal fun ReplyEndAutofocusEffect(
    isStreaming: Boolean,
    sessionId: String,
    enabled: Boolean,
    onIntentionalFocus: () -> Unit,
    requestFocus: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var sawStreamingThisVisit by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(isStreaming, sessionId) {
        if (isStreaming) {
            sawStreamingThisVisit = true
            return@LaunchedEffect
        }
        if (!sawStreamingThisVisit) return@LaunchedEffect
        sawStreamingThisVisit = false
        if (!enabled) return@LaunchedEffect
        // Let the final markdown reflow settle before taking focus so the IME
        // doesn't fight the stream-end layout pass.
        delay(250)
        // This focus is intentional; it must not be mistaken for the entry
        // ghost focus the guard exists to drop.
        onIntentionalFocus()
        requestFocus()
        keyboardController?.show()
    }
}
