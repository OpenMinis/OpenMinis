package com.openminis.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/**
 * [T-android-picker-scroll-guard] Stops a FLING inside a bottom sheet's list
 * from continuing into the sheet and dismissing it, without taking away the
 * deliberate drag-down-to-close gesture.
 *
 * The problem: a `LazyColumn` inside a `ModalBottomSheet` hands its leftover
 * scroll to the sheet. Flick the model list upward, hit the top, and the
 * remaining fling velocity becomes a downward drag on the sheet — the picker
 * closes while the user was only scrolling.
 *
 * The first fix for this consumed EVERY unconsumed downward delta, which cured
 * the flick but also disabled drag-to-dismiss: once the list was at the top,
 * pulling the sheet down with a finger did nothing, leaving the scrim tap and
 * the back gesture as the only ways out. That trades a rare accident for a
 * permanent regression of the platform-standard gesture.
 *
 * So the guard discriminates by SOURCE rather than by direction alone:
 *
 *  - [NestedScrollSource.SideEffect] — momentum from a fling the user has
 *    already let go of. They are no longer steering, so carrying it into a
 *    dismissal is never what they asked for. Consumed.
 *  - [NestedScrollSource.UserInput] — the finger is still down and the user is
 *    actively dragging. Passed through, so drag-to-dismiss works exactly as the
 *    platform intends.
 *
 * Only downward (`available.y > 0`) deltas are considered at all; upward
 * leftovers belong to the sheet's own expand behaviour.
 */
@Composable
fun rememberSheetScrollGuard(): NestedScrollConnection = remember {
    object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            val isFlingMomentum = source == NestedScrollSource.SideEffect
            return if (available.y > 0f && isFlingMomentum) {
                Offset(0f, available.y)
            } else {
                Offset.Zero
            }
        }
    }
}
