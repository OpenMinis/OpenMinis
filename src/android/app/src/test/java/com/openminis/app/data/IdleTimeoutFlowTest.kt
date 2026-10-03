package com.openminis.app.data

import com.openminis.app.provider.withIdleTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-compact-idle-timeout] Behaviour of the idle-timeout stream operator.
 *
 * The constants in CompactBudgetTest only pin arithmetic; this exercises the
 * operator itself, which is where the concurrency risk actually is. The
 * property that matters — and the one the old total-elapsed budget got wrong —
 * is the first test: total duration far beyond the limit is fine as long as no
 * single gap exceeds it.
 *
 * Uses runTest's virtual clock, so "10 minutes of streaming" costs no real
 * wall-clock time and the timings are exact rather than flaky.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleTimeoutFlowTest {

    @Test
    fun `a slow but continuous stream survives far past the idle limit`() = runTest {
        // 600 chunks, one per second = 600s total, with a 100s idle limit.
        // Under the old total-elapsed budget this is the exact shape that got
        // cancelled at 150s despite being perfectly healthy.
        val idleMs = 100_000L
        val received = flow {
            repeat(600) {
                delay(1_000)
                emit(it)
            }
        }.withIdleTimeout(idleMs) { fail("healthy stream must not time out") }
            .toList()

        assertEquals(600, received.size)
        assertTrue(
            "elapsed must exceed the idle limit — that is the point",
            testScheduler.currentTime > idleMs,
        )
    }

    @Test
    fun `a stream that goes quiet trips the timer`() = runTest {
        val idleMs = 30_000L
        var timedOutAfter: Long? = null

        try {
            flow {
                emit(1)
                emit(2)
                delay(idleMs * 2) // silence
                emit(3)
            }.withIdleTimeout(idleMs) { timedOutAfter = it; throw IllegalStateException("idle") }
                .toList()
            fail("expected the idle timeout to throw")
        } catch (e: IllegalStateException) {
            assertEquals("idle", e.message)
        }

        assertEquals(idleMs, timedOutAfter)
    }

    @Test
    fun `the timer resets per chunk rather than accumulating`() = runTest {
        // Each gap is just under the limit, so none should fire even though
        // their sum is several times over it. This is the reset behaviour the
        // fix depends on; without it this stream would die on the third chunk.
        val idleMs = 10_000L
        val received = flow {
            repeat(5) {
                delay(idleMs - 1_000)
                emit(it)
            }
        }.withIdleTimeout(idleMs) { fail("gaps under the limit must not time out") }
            .toList()

        assertEquals(5, received.size)
        assertTrue("sum of gaps must exceed the limit", testScheduler.currentTime > idleMs)
    }

    @Test
    fun `an upstream error propagates instead of looking like a clean end`() = runTest {
        // The channel-based implementation closes the channel on upstream
        // failure; if the cause were dropped the collector would see a normal
        // completion and silently compact a truncated summary.
        try {
            flow<Int> {
                emit(1)
                throw IllegalArgumentException("upstream boom")
            }.withIdleTimeout(60_000L) { fail("should not time out") }
                .toList()
            fail("expected the upstream error to propagate")
        } catch (e: IllegalArgumentException) {
            assertEquals("upstream boom", e.message)
        }
    }

    @Test
    fun `an empty stream completes normally`() = runTest {
        val received = flow<Int> { }
            .withIdleTimeout(60_000L) { fail("empty stream must not time out") }
            .toList()
        assertEquals(0, received.size)
    }
}
