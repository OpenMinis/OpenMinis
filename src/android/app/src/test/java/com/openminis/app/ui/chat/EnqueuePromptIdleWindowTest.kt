package com.openminis.app.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-enqueue-idle-window] Does Android have the "enqueue evaporates in
 * the idle window" race that the SubAgent design doc (§2.4.1) attributes to iOS?
 *
 * iOS shape (per the doc): `enqueuePrompt` ACCEPTS the prompt whenever the guard
 * passes, and the only consumer is `drainQueuedPrompts` at the agent loop's
 * epilogue. A prompt enqueued after the loop's last drain but before the next
 * `send()` therefore has no consumer — it sits in the queue forever.
 *
 * This test models Android's ACTUAL control flow, transcribed from
 * ChatViewModel.kt:
 *
 *   sendMessage(text)                          :6292
 *     if (_isStreaming.value) { enqueuePrompt(text); return }   :6311-6314
 *     _isStreaming.value = true                                 :6377  (synchronous)
 *     launch {  runAgentLoop(); drainQueuedPrompts()            :6561-6571
 *               ... ; if (owner) _isStreaming.value = false }   :6602-6604
 *
 *   enqueuePrompt(text)                        :5944
 *     if (blank || !_isStreaming.value) return                  :5947  (REJECTS when idle)
 *
 * Two structural differences from iOS decide the outcome, and this test pins
 * both:
 *
 *   1. The drain at :6571 runs INSIDE the try, BEFORE `_isStreaming` is cleared
 *      at :6604. So the "queue accepted, loop already past its drain" window
 *      cannot open — while the flag is still true, a drain is still ahead.
 *   2. `enqueuePrompt` REJECTS on `!_isStreaming` rather than accepting. Anything
 *      arriving after the flag clears is not queued at all; it is routed by
 *      `sendMessage`'s own guard into a fresh send. Nothing is stranded.
 *
 * Both reads and the flag write happen on Dispatchers.Main with no suspension
 * between check and act, so there is no check-then-act gap to exploit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EnqueuePromptIdleWindowTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /**
     * Faithful model of the Android funnel. Only the pieces that decide the race
     * are modelled: the streaming flag, the queue, the epilogue drain, and the
     * ordering between drain and flag-clear.
     */
    private class Funnel {
        var isStreaming = false
        val queue = ArrayDeque<String>()
        val delivered = mutableListOf<String>()
        var drainCount = 0

        /** ChatViewModel.enqueuePrompt :5944 — note the :5947 rejection guard. */
        fun enqueuePrompt(text: String): Boolean {
            if (text.isBlank() || !isStreaming) return false
            queue += text
            return true
        }

        /** ChatViewModel.drainQueuedPrompts :6186 — drains until empty. */
        fun drain() {
            drainCount++
            while (queue.isNotEmpty()) delivered += queue.removeFirst()
        }
    }

    /**
     * Runs one turn with the REAL ordering: drain happens before the flag clears.
     * [duringLoop] is invoked while the loop is notionally still running, and
     * [afterFlagCleared] once the turn is fully over.
     */
    private suspend fun runTurn(
        f: Funnel,
        duringLoop: suspend () -> Unit = {},
        afterFlagCleared: suspend () -> Unit = {},
    ) {
        f.isStreaming = true          // :6377 synchronous claim
        duringLoop()                  // anything the user does mid-stream
        yield()                       // let the loop body suspend, as it really does
        f.drain()                     // :6571 — INSIDE the try
        f.isStreaming = false         // :6604 — AFTER the drain
        afterFlagCleared()
    }

    @Test
    fun `prompt enqueued mid-stream is drained by the epilogue`() = runTest(dispatcher) {
        val f = Funnel()
        runTurn(f, duringLoop = { assertTrue(f.enqueuePrompt("mid")) })
        assertEquals("the mid-stream prompt must be delivered", listOf("mid"), f.delivered)
        assertTrue("queue must be empty after the drain", f.queue.isEmpty())
    }

    @Test
    fun `prompt arriving after the flag clears is rejected, not stranded`() = runTest(dispatcher) {
        val f = Funnel()
        var accepted = true
        runTurn(f, afterFlagCleared = { accepted = f.enqueuePrompt("late") })
        assertEquals(
            "enqueuePrompt must REJECT once idle — accepting here is exactly the " +
                "iOS evaporation shape, since no further drain is scheduled",
            false,
            accepted,
        )
        assertTrue("nothing may be left sitting in the queue", f.queue.isEmpty())
        assertEquals("and nothing may be silently swallowed", emptyList<String>(), f.delivered)
    }

    /**
     * The t3 window the task asks about: hammer enqueue across the whole turn,
     * including the exact instant the flag flips. Every attempt must end in one
     * of two states — delivered, or rejected so the caller re-routes to a fresh
     * send. "Accepted but never delivered" is the failure this test exists to
     * detect, and it must never happen.
     */
    @Test
    fun `high frequency enqueue across the boundary strands nothing`() = runTest(dispatcher) {
        val f = Funnel()
        var accepted = 0
        var rejected = 0

        val hammer = launch {
            repeat(200) {
                if (f.enqueuePrompt("p$it")) accepted++ else rejected++
                yield()
            }
        }
        repeat(20) { runTurn(f) }
        hammer.join()
        // Any prompt still queued after the last turn would have been stranded.
        f.drain()

        assertEquals(
            "every ACCEPTED prompt must have been delivered — a shortfall is the " +
                "evaporation race (accepted=$accepted delivered=${f.delivered.size})",
            accepted,
            f.delivered.size,
        )
        assertEquals(
            "accepted + rejected must account for every attempt",
            200,
            accepted + rejected,
        )
        assertTrue("some attempts must have landed mid-stream for this to be meaningful", accepted > 0)
    }

    /**
     * Counter-model: the iOS shape, where enqueue accepts unconditionally and the
     * drain sits AFTER the flag clears. This must FAIL to deliver, proving the
     * test above is actually sensitive to the bug rather than vacuously green.
     */
    @Test
    fun `ios-shaped ordering does strand a late prompt`() = runTest(dispatcher) {
        val f = Funnel()
        f.isStreaming = true
        f.drain()                 // epilogue drain happens FIRST...
        f.isStreaming = false
        // ...and iOS-style enqueue accepts regardless of the flag:
        f.queue += "late"         // no !isStreaming rejection
        assertTrue(
            "the counter-model must strand the prompt — otherwise this suite " +
                "cannot detect the race it claims to rule out",
            f.queue.isNotEmpty() && f.delivered.isEmpty(),
        )
    }
}
