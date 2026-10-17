package com.openminis.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatScrollControllerTest {
    @Test
    fun targetWaitRequiresNewVersionAndTargetRow() = runTest {
        val targetKey = FlatKeys.of(FlatKeys.KIND_HEADER, "target")
        val items = MutableStateFlow<List<FlatChatItem>>(emptyList())
        val version = MutableStateFlow(4L)
        val waiter = launch(StandardTestDispatcher(testScheduler)) {
            assertTrue(
                ChatScrollController.awaitTarget(
                    items,
                    version,
                    requestedVersion = 4L,
                    key = targetKey,
                ),
            )
        }
        testScheduler.runCurrent()
        version.value = 5L
        testScheduler.runCurrent()
        assertFalse(waiter.isCompleted)
        items.value = listOf(FlatChatItem.AssistantHeader("target"))
        testScheduler.runCurrent()
        assertTrue(waiter.isCompleted)
    }

    @Test
    fun longThinkingPublishResolvesTheNewestRowWithoutExtraScroll() = runTest {
        val rows = MutableStateFlow<List<FlatChatItem>>(emptyList())
        val version = MutableStateFlow(10L)
        val target = FlatChatItem.AssistantHeader("thinking-target")
        val waiter = launch(StandardTestDispatcher(testScheduler)) {
            assertTrue(ChatScrollController.awaitTarget(rows, version, 10L, target.key))
        }
        testScheduler.runCurrent()
        rows.value = (0 until 2_000).map { FlatChatItem.AssistantHeader("thinking-$it") } + target
        version.value = 11L
        testScheduler.runCurrent()
        assertTrue(waiter.isCompleted)
    }

    @Test
    fun rapidPreviousTurnClicksAreSerializedAndBounded() = runTest {
        val controller = ChatScrollController(LazyListState(), maxQueuedPreviousTurns = 2)
        val order = mutableListOf<Int>()
        var next = 0
        val action: suspend () -> Unit = {
            val id = next++
            order += id
            kotlinx.coroutines.yield()
        }
        controller.enqueuePreviousTurn(this, action)
        controller.enqueuePreviousTurn(this, action)
        controller.enqueuePreviousTurn(this, action)
        controller.enqueuePreviousTurn(this, action)
        controller.enqueuePreviousTurn(this, action)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(0, 1, 2), order)
    }

}
