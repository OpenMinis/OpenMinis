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
        snapshotFlow { contentVersion.intValue }.collect {
            if (!ThinkingFollowPolicy.shouldFollowOnContentGrowth(paused)) return@collect
            // Let layout publish its new maxValue; snapshotFlow conflates
            // bursts while this effect waits, so at most one snap runs at once.
            withFrameNanos { }
            scrollState.scrollTo(scrollState.maxValue)
        }
    }
}
