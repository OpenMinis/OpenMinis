package com.openminis.app.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-batcher-seal] Behavioral contract for UiUpdateBatcher.seal —
 * the barrier that retires a message id mid-loop when the queued-prompt
 * inject finalizes an interrupted bubble while the batcher keeps running
 * for the next turn.
 *
 * Without the seal, a coalesced isStreaming=true update still sitting in
 * the consumer's 200ms batch window would land AFTER the inject's direct
 * isStreaming=false finalize and resurrect the retired thinking box (it
 * re-expands and never collapses — the id never receives another update).
 *
 * The contract, pinned here:
 *  1. a push already committed before the seal stays (it happened);
 *  2. the still-coalesced update for the sealed id is DROPPED by the barrier,
 *     not pushed — the inject's direct push is newer and final;
 *  3. updates enqueued AFTER the seal are tombstoned and never pushed;
 *  4. other ids keep flowing through the same batcher unaffected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UiUpdateBatcherSealTest {

    private val dispatcher = StandardTestDispatcher()
    private val pushed = mutableListOf<Pair<String, String>>() // id to content

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        pushed.clear()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * consumerDispatcher = the test scheduler: the batch window and the seal
     * barrier become virtual-time deterministic (no real-thread race with
     * runCurrent). Named arguments are required — the trailing-lambda form
     * cannot bind `updateFn` once the constructor takes `batchWindowMs`
     * between it and `consumerDispatcher`.
     */
    private fun makeBatcher(scope: kotlinx.coroutines.CoroutineScope): UiUpdateBatcher =
        UiUpdateBatcher(
            scope = scope,
            updateFn = { id, content, _, _, _ -> pushed.add(id to content) },
            consumerDispatcher = dispatcher,
        )

    @Test
    fun `sealed id receives no push after seal returns`() = runTest(dispatcher) {
        val batcher = makeBatcher(backgroundScope)
        batcher.start()

        // (1) the first update reaches the Main thread when its window expires.
        batcher.enqueue("A", "a1", true, emptyList())
        runCurrent()
        advanceTimeBy(200L)
        runCurrent()
        assertEquals("the pre-seal push must have happened", listOf("A" to "a1"), pushed)

        // (2) second update coalesces inside the NEXT batch window.
        batcher.enqueue("A", "a2", true, emptyList())
        runCurrent()

        // (3) seal A: the coalesced a2 is dropped by the barrier, the
        //     tombstone drops everything enqueued for A afterwards.
        batcher.seal("A")
        batcher.enqueue("A", "a3-late", true, emptyList())

        // (4) B keeps flowing through the same batcher.
        batcher.enqueue("B", "b1", true, emptyList())
        advanceTimeBy(400L)
        runCurrent()
        batcher.flush()

        assertEquals(
            "A must have exactly its pre-seal push — the coalesced a2 is dropped " +
                "by the barrier and a3-late by the tombstone",
            listOf("A" to "a1"),
            pushed.filter { it.first == "A" },
        )
        assertTrue(
            "the live id B must still be pushed after another id was sealed",
            pushed.contains("B" to "b1"),
        )
    }

    @Test
    fun `seal is safe when nothing is pending for the id`() = runTest(dispatcher) {
        val batcher = makeBatcher(backgroundScope)
        batcher.start()

        batcher.seal("never-enqueued")
        batcher.enqueue("C", "c1", true, emptyList())
        advanceTimeBy(400L)
        runCurrent()
        batcher.flush()

        assertEquals(
            "sealing an idle id must not disturb other traffic",
            listOf("C" to "c1"),
            pushed,
        )
    }

    @Test
    fun `seal before the consumer starts still tombstones the id`() = runTest(dispatcher) {
        val batcher = makeBatcher(backgroundScope)

        // No start(): there is no consumer to answer the barrier, so the seal
        // must degrade to the tombstone alone and still return.
        batcher.seal("A")
        batcher.start()
        batcher.enqueue("A", "a1", true, emptyList())
        batcher.enqueue("B", "b1", true, emptyList())
        advanceTimeBy(400L)
        runCurrent()
        batcher.flush()

        assertEquals(
            "no push may target the sealed id",
            emptyList<Pair<String, String>>(),
            pushed.filter { it.first == "A" },
        )
        assertTrue("other ids still flow", pushed.contains("B" to "b1"))
    }
}
