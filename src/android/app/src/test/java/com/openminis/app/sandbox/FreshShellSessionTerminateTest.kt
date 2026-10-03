package com.openminis.app.sandbox

import android.content.ContextWrapper
import com.openminis.app.ProductionSources
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [T-android-fresh-shells-concurrent] + [T-android-fresh-session-terminate]
 *
 * Drives the real ExecutionCoordinator registry with real host processes
 * (`sleep`) standing in for the proot child of a FreshProcessShell.
 */
class FreshShellSessionTerminateTest {

    private val spawned = mutableListOf<Process>()

    @After
    fun tearDown() {
        spawned.forEach { it.destroyForcibly() }
        registry().clear()
    }

    @Suppress("UNCHECKED_CAST")
    private fun registry(): ConcurrentHashMap<String, MutableSet<FreshProcessShell>> {
        val f = ExecutionCoordinator::class.java.getDeclaredField("freshShells")
        f.isAccessible = true
        return f.get(ExecutionCoordinator) as ConcurrentHashMap<String, MutableSet<FreshProcessShell>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun newSet(): MutableSet<FreshProcessShell> {
        val m = ExecutionCoordinator::class.java.getDeclaredMethod("newFreshShellSet")
        m.isAccessible = true
        return m.invoke(ExecutionCoordinator) as MutableSet<FreshProcessShell>
    }

    private fun shell(sessionId: String, process: Process? = null): FreshProcessShell {
        val s = FreshProcessShell(ContextWrapper(null), sessionId, emptyMap())
        if (process != null) {
            val f = FreshProcessShell::class.java.getDeclaredField("live")
            f.isAccessible = true
            f.set(s, process)
        }
        return s
    }

    private fun register(sessionId: String, s: FreshProcessShell) {
        registry().compute(sessionId) { _, existing -> (existing ?: newSet()).also { it.add(s) } }
    }

    private fun unregister(sessionId: String, s: FreshProcessShell) {
        registry().computeIfPresent(sessionId) { _, set ->
            set.remove(s)
            if (set.isEmpty()) null else set
        }
    }

    private fun sleeper(): Process = ProcessBuilder("sleep", "30").start().also { spawned += it }

    @Test
    fun `terminating a session kills its running fresh commands and no one else's`() {
        val a1 = sleeper()
        val a2 = sleeper()
        val b = sleeper()
        register("sess-A", shell("sess-A", a1))
        register("sess-A", shell("sess-A", a2))
        register("sess-AB", shell("sess-AB", b)) // id with sess-A as a prefix

        ExecutionCoordinator.sessionDidTerminate("sess-A")

        assertTrue("A's first command killed", a1.waitFor(5, TimeUnit.SECONDS))
        assertTrue("A's second command killed", a2.waitFor(5, TimeUnit.SECONDS))
        assertTrue("another session's command keeps running", b.isAlive)
    }

    @Test
    fun `the per-session set is concurrent`() {
        assertTrue(newSet().javaClass.name.contains("ConcurrentHashMap"))
    }

    /** Stop iterates while other threads register/unregister: must never throw. */
    @Test
    fun `stop racing registration does not throw`() {
        val sid = "sess-race"
        val pool = Executors.newFixedThreadPool(4)
        val failure = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(4)
        repeat(3) {
            pool.execute {
                try {
                    repeat(3000) {
                        val s = shell(sid)
                        register(sid, s)
                        unregister(sid, s)
                    }
                } catch (t: Throwable) { failure.compareAndSet(null, t) } finally { done.countDown() }
            }
        }
        pool.execute {
            try {
                repeat(3000) { ExecutionCoordinator.stopCurrentCommand(sid) }
            } catch (t: Throwable) { failure.compareAndSet(null, t) } finally { done.countDown() }
        }
        assertTrue(done.await(60, TimeUnit.SECONDS))
        pool.shutdownNow()
        failure.get()?.let { throw AssertionError("concurrent stop threw", it) }
    }

    @Test
    fun `source keeps the snapshot and the terminate wiring`() {
        val src = ProductionSources.read("sandbox/ExecutionCoordinator.kt")
        assertFalse(src.contains("(existing ?: mutableSetOf())"))
        assertTrue(src.contains("ConcurrentHashMap.newKeySet()"))
        // Kotlin toList() on a shrinking concurrent collection can throw.
        assertFalse(src.contains("freshShells[sessionId]?.toList()"))
        assertFalse(src.contains("freshShells.keys.toList()"))
        val terminate = src.substringAfter("fun sessionDidTerminate(sessionId: String) {")
            .substringBefore("fun stopCurrentCommand(")
        assertTrue(terminate.contains("stopFreshShells(sessionId)"))
    }
}
