package com.openminis.app.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trackers are written from several threads: a stream finishing on an IO
 * thread, a service callback on the main thread, sub-agent runners on their
 * own dispatchers. `flow.value = flow.value + x` loses one of two concurrent
 * updates; these tests make the race deterministic with a start latch.
 */
class ActivityTrackerConcurrencyTest {

    private fun runConcurrently(count: Int, action: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(count)
        repeat(count) { i ->
            pool.execute {
                start.await()
                try {
                    action(i)
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
    }

    @Test
    fun concurrentSessionActivationKeepsEverySession() {
        val ids = (0 until 64).map { "race-active-$it" }
        runConcurrently(ids.size) { i -> SessionActivityTracker.setActive(ids[i]) }
        assertEquals(ids.toSet(), SessionActivityTracker.activeSessions.value)

        runConcurrently(ids.size) { i -> SessionActivityTracker.setInactive(ids[i]) }
        assertTrue(SessionActivityTracker.activeSessions.value.isEmpty())
    }

    @Test
    fun concurrentSubAgentUpdatesKeepEveryMember() {
        val parent = "race-parent"
        SubAgentActivityTracker.clearSession(parent)
        val ids = (0 until 32).map { i ->
            SubAgentActivityTracker.start(
                parentSessionId = parent,
                title = "agent-$i",
                role = "worker",
                model = null,
            )
        }
        runConcurrently(ids.size) { i ->
            SubAgentActivityTracker.updateStep(ids[i], "step-$i")
            SubAgentActivityTracker.setPhase(ids[i], "working")
        }
        val members = SubAgentActivityTracker.membersFor(parent)
        assertEquals(ids.toSet(), members.map { it.id }.toSet())
        assertEquals(ids.size, members.size)

        runConcurrently(ids.size) { i -> SubAgentActivityTracker.finish(ids[i], true) }
        assertTrue(SubAgentActivityTracker.membersFor(parent).isEmpty())
    }
}
