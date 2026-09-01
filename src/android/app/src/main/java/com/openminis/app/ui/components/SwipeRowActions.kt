package com.openminis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One revealed action behind a swipeable row. */
data class SwipeRowAction(
    val label: String,
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
    val onClick: () -> Unit,
)

/** Anchor states for [SwipeRowActions]. */
private enum class SwipeAnchor { Resting, Revealed }

/**
 * [T-android-swipe-row-actions] Swipe-LEFT-to-reveal action buttons behind a
 * list row, for lists that ALSO support long-press drag-to-reorder.
 *
 * Why not SwipeToDismissBox: the two existing usages in this app
 * (ModelGroupsScreen, MountedFoldersScreen) wire `currentValue == EndToStart`
 * straight to a delete call, so the row is destroyed the moment the swipe
 * passes its threshold — no confirmation, no undo, and no way to offer a
 * second (non-destructive) action like Edit. A reveal model keeps the row
 * alive, shows real buttons the user must then tap, and lets the caller run
 * its existing confirmation dialog.
 *
 * Gesture coexistence with drag-to-reorder is the delicate part. Both
 * gestures start as a press on the same row, so they are separated by AXIS,
 * not by priority:
 *  - `anchoredDraggable(orientation = Horizontal)` only claims pointer events
 *    once movement crosses the touch slop HORIZONTALLY. A vertical drag never
 *    reaches that condition, so it falls through to the reorderable library's
 *    long-press handle untouched.
 *  - The reorder handle in these lists is engaged by LONG PRESS (or by a
 *    dedicated handle in ModelGroupsScreen), which is a time-based trigger
 *    rather than a horizontal-movement one, so neither gesture starves the
 *    other.
 * The net effect: horizontal = reveal, vertical/long-press = reorder, and the
 * caller keeps its existing reorder wiring unchanged.
 *
 * [actions] renders right-to-left in the order given (first action sits
 * closest to the row's trailing edge). Pass an empty list to make the row
 * non-swipeable — used for entries the caller has decided must not expose
 * destructive actions, so there is no fake affordance to discover.
 */
@Composable
fun SwipeRowActions(
    actions: List<SwipeRowAction>,
    modifier: Modifier = Modifier,
    actionWidth: androidx.compose.ui.unit.Dp = 88.dp,
    /** Opaque fill for the sliding row — must match the surface the row sits on,
     *  or the action buttons behind it show through at rest. */
    rowColor: Color = SectionDesign.cardColor(),
    content: @Composable () -> Unit,
) {
    if (actions.isEmpty()) {
        Box(modifier = modifier) { content() }
        return
    }

    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val revealPx = with(density) { (actionWidth * actions.size).toPx() }

    val state = remember(revealPx) {
        AnchoredDraggableState(
            initialValue = SwipeAnchor.Resting,
            anchors = DraggableAnchors {
                SwipeAnchor.Resting at 0f
                SwipeAnchor.Revealed at -revealPx
            },
        )
    }

    // Buzz once when the row latches open, matching the long-press feedback
    // the rest of this app's list interactions give.
    LaunchedEffect(state.settledValue) {
        if (state.settledValue == SwipeAnchor.Revealed) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    var rowHeightPx by remember { mutableIntStateOf(0) }
    val rowHeight = with(density) { rowHeightPx.toDp() }

    Box(modifier = modifier.clipToBounds()) {
        // Action buttons sit UNDER the row, pinned to the trailing edge, and
        // are sized to the row's measured height so they never dictate it.
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .then(if (rowHeightPx > 0) Modifier.height(rowHeight) else Modifier),
            horizontalArrangement = Arrangement.End,
        ) {
            actions.forEach { action ->
                Column(
                    modifier = Modifier
                        .width(actionWidth)
                        .fillMaxHeight()
                        .background(action.containerColor)
                        .clickable {
                            // Close first so the row is resting again whatever
                            // the action does (open a dialog, navigate away).
                            scope.launch { state.animateTo(SwipeAnchor.Resting) }
                            action.onClick()
                        },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        action.icon,
                        contentDescription = action.label,
                        tint = action.contentColor,
                    )
                    Text(
                        action.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = action.contentColor,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowHeightPx = it.height }
                // offset{} reads the drag value in the LAYOUT phase, so a drag
                // reposition never invalidates composition.
                .offset { IntOffset(state.requireOffset().roundToInt(), 0) }
                // The row must be OPAQUE: the action buttons are painted behind
                // it, so a transparent row leaves them permanently visible
                // instead of revealed-on-swipe. [rowColor] defaults to the card
                // surface these lists actually sit on.
                .background(rowColor)
                .anchoredDraggable(
                    state = state,
                    orientation = Orientation.Horizontal,
                ),
        ) {
            content()
        }
    }
}
