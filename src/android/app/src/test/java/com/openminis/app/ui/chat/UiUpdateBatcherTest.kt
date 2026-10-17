package com.openminis.app.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UiUpdateBatcherTest {
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun latestSnapshotWinsWithinOneBatchWindow() = runTest {
        val applied = mutableListOf<String>()
        val batcher = UiUpdateBatcher(
            scope = this,
            updateFn = { _, content, _, _, _ -> applied += content },
            batchWindowMs = 200L,
            consumerDispatcher = StandardTestDispatcher(testScheduler),
        )

        batcher.start()
        batcher.enqueue("assistant", "old", true, emptyList())
        runCurrent()
        batcher.enqueue("assistant", "new", true, emptyList())
        runCurrent()
        advanceTimeBy(200L)
        runCurrent()

        assertEquals(listOf("new"), applied)
        batcher.stop()
    }

    @Test
    fun flushPublishesTheFinalStreamSnapshot() = runTest {
        val applied = mutableListOf<Pair<String, Boolean>>()
        val batcher = UiUpdateBatcher(
            scope = this,
            updateFn = { _, content, streaming, _, _ -> applied += content to streaming },
            batchWindowMs = 200L,
            consumerDispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            batcher.start()
            batcher.enqueue("assistant", "thinking-final", true, emptyList())
            runCurrent()
            batcher.enqueue("assistant", "answer-final", false, emptyList())
            val flushJob = launch { batcher.flush() }
            advanceUntilIdle()
            flushJob.join()

            assertEquals(listOf("answer-final" to false), applied)
        } finally {
            batcher.stop()
        }
    }

    @Test
    fun visibleThinkingTailKeepsTheNewestWindow() {
        val content = "old-content-".repeat(20) + "latest-line"

        val visible = visibleThinkingTail(content, window = 30)

        assertEquals(content.takeLast(30), visible)
        assertEquals(true, visible.endsWith("latest-line"))
    }
}
