package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable

/**
 * Recomposition boundaries for the two high-frequency halves of `ChatScreen`.
 *
 * `ChatScreen` is one 7,600-line composable that reads ~84 state flows in its
 * own scope: a keystroke or a streaming tick therefore invalidated the whole
 * screen. Wrapping the existing inline subtrees in a composable lambda moves
 * the reads into a child restart group. The code stays where it is (a physical
 * split is a separate, much riskier move); only the invalidation scope changes.
 *
 * The `ColumnScope` receiver is load-bearing: both subtrees use
 * `Modifier.weight`/`align` as direct children of the screen Column, and a
 * plain lambda would lose that receiver.
 */
@Composable
internal fun ColumnScope.ChatMessagesScope(content: @Composable ColumnScope.() -> Unit) {
    content()
}

@Composable
internal fun ColumnScope.ChatComposerScope(content: @Composable ColumnScope.() -> Unit) {
    content()
}
