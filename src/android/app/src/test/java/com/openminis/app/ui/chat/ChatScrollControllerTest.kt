package com.openminis.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatScrollControllerTest {

    private class RecordingTarget(private val delayMs: Long = 0) : ScrollTarget {
        val scrollTo = mutableListOf<Pair<Int, Int>>()
        val scrollBys = mutableListOf<Float>()

        override suspend fun scrollToItem(index: Int, offset: Int) {
            if (delayMs > 0) delay(delayMs)
            scrollTo += index to offset
        }

        override suspend fun scrollBy(delta: Float) {
            scrollBys += delta
        }
    }

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

    @Test
    fun commandQueuedBeforeGestureIsDroppedWhenItReachesTheLock() = runTest {
        val target = RecordingTarget(delayMs = 100)
        val controller = ChatScrollController(target)

        // A occupies the mutex; B queues behind it.
        launch(StandardTestDispatcher(testScheduler)) { controller.scrollToItem("A", 0, 0) }
        testScheduler.runCurrent()
        launch(StandardTestDispatcher(testScheduler)) { controller.scrollToItem("B", 1, 0) }
        testScheduler.runCurrent()

        // The user takes the viewport while B is still waiting.
        controller.beginUserGesture()
        controller.endUserGesture()
        advanceUntilIdle()

        // A already ran; B must not land after the gesture.
        assertEquals(listOf(0 to 0), target.scrollTo)
    }

    @Test
    fun commandsAreSuppressedWhileTheFingerIsDown() = runTest {
        val target = RecordingTarget()
        val controller = ChatScrollController(target)
        controller.beginUserGesture()
        controller.scrollToItem("stream", 0, 0)
        controller.scrollBy("stream", -10f)
        assertTrue(target.scrollTo.isEmpty())
        assertTrue(target.scrollBys.isEmpty())
        controller.endUserGesture()
        controller.scrollToItem("fab", 2, 0)
        assertEquals(listOf(2 to 0), target.scrollTo)
    }

    @Test
    fun invalidateDropsCommandsIssuedBeforeTheNewGeneration() = runTest {
        val target = RecordingTarget(delayMs = 50)
        val controller = ChatScrollController(target)
        launch(StandardTestDispatcher(testScheduler)) { controller.scrollToItem("old", 0, 0) }
        testScheduler.runCurrent()
        launch(StandardTestDispatcher(testScheduler)) { controller.scrollToItem("stale", 1, 0) }
        testScheduler.runCurrent()
        controller.invalidate("session-switch")
        advanceUntilIdle()
        assertEquals(listOf(0 to 0), target.scrollTo)
    }

    @Test
    fun gestureStartCancelsQueuedPreviousTurnWalk() = runTest {
        val controller = ChatScrollController(LazyListState())
        var ran = 0
        controller.enqueuePreviousTurn(this) {
            ran++
            delay(100)
        }
        testScheduler.runCurrent()
        controller.beginUserGesture()
        advanceUntilIdle()
        assertEquals(1, ran)
    }
}
