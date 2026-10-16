package com.openminis.app.ui.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos

/**
 * Owns the per-thinking-block inner-scroll pause and streaming follow effects.
 * Kept outside ChatAssistantMessageUI so the rendering file remains within its
 * architecture-policy ceiling.
 */
@Composable
internal fun ThinkingAutoFollow(
    blockId: String,
    contentVersionValue: Int,
    isStreaming: Boolean,
    scrollState: ScrollState,
) {
    var paused by remember(blockId) { mutableStateOf(false) }
    LaunchedEffect(scrollState, blockId) {
        snapshotFlow {
            Triple(scrollState.value, scrollState.maxValue, scrollState.isScrollInProgress)
        }.collect { (value, max, dragging) ->
            val gap = (max - value).coerceAtLeast(0)
            paused = ThinkingFollowPolicy.onScrollObserved(dragging, gap, paused)
        }
    }

    // AssistantBlock is an immutable data class replaced during streaming.
    // Observe this state-backed version, not a captured block property.
    val contentVersion = remember(blockId) { mutableIntStateOf(contentVersionValue) }
    SideEffect { contentVersion.intValue = contentVersionValue }
    LaunchedEffect(scrollState, blockId, isStreaming) {
        if (!isStreaming) return@LaunchedEffect
        // Observe both the throttled content revision and the measured max
        // scroll. Content is published before LazyColumn/Column measurement,
        // so watching only the revision races the layout and can leave the
        // viewport one update behind. The max-value change is the second half
        // of the handshake: once layout has committed the new height, follow
        // again using the real latest max.
        snapshotFlow { contentVersion.intValue to scrollState.maxValue }.collect {
            if (!ThinkingFollowPolicy.shouldFollowOnContentGrowth(paused)) return@collect
            withFrameNanos { }
            scrollState.scrollTo(scrollState.maxValue)
        }
    }
}
