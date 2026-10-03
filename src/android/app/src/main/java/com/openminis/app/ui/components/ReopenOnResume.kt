package com.openminis.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch

/**
 * [T-android-picker-provider-edit] Reopen a sheet after the screen it handed
 * off to is popped.
 *
 * iOS opens a provider's settings (or Model Groups) as a sheet over the model
 * picker, so closing them lands back in the picker. Android pushes a route
 * instead, and the picker must close first (a bottom sheet left open lingers
 * behind the pushed screen). Call the returned function right before
 * navigating; [onReopen] then runs on the first ON_RESUME after it. The flag is
 * saveable because the host screen leaves composition while the pushed route
 * is on top.
 */
@Composable
fun rememberReopenOnResume(onReopen: () -> Unit): () -> Unit {
    var pending by rememberSaveable { mutableStateOf(false) }
    val latestOnReopen by rememberUpdatedState(onReopen)
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pending) {
                pending = false
                // Wait until the returning screen has drawn two frames before
                // composing the sheet. Opening it in the same frame as the
                // screen coming back put both compositions (the picker with
                // hundreds of models, the chat behind it) into one main-thread
                // burst; on a Pixel 4a under memory pressure that burst held
                // the window's FocusEvent past 5 s and raised an ANR. Two frames
                // let the focus change and the first draw go through first.
                scope.launch {
                    androidx.compose.runtime.withFrameNanos { }
                    androidx.compose.runtime.withFrameNanos { }
                    latestOnReopen()
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return remember { { pending = true } }
}
