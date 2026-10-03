package com.openminis.app.speech

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-android-voice-asr-stall-skip] Port of iOS VoiceASRStallSkipTests' compiled
 * scenarios (9f6207b05 / 7d9a86e0b), with a 200 ms threshold.
 */
class SkippableAsrAttemptsTest {

    private fun attempts() = SkippableAsrAttempts(stallThresholdMs = 200)

    @Test fun `a fast attempt never stalls`() = runBlocking {
        val a = attempts()
        assertEquals("hi", a.run("fast", "next", canSkip = true) { delay(20); "hi" })
        delay(300)
        assertFalse(a.stalled.value)
    }

    @Test fun `a slow attempt that ignores cancellation is marked stalled and a skip releases its caller at once`() = runBlocking {
        val a = attempts()
        val caller = async {
            // Thread.sleep does not honour coroutine cancellation — like a
            // blocking OkHttp call that never returns.
            runCatching { a.run("hung", "next-model", canSkip = true) { Thread.sleep(5_000); "late" } }
        }
        withTimeout(2_000) { while (!a.stalled.value) delay(20) }
        val started = System.currentTimeMillis()
        assertEquals(listOf("next-model"), a.skipStalled())
        val result = withTimeout(1_000) { caller.await() }
        assertTrue(result.exceptionOrNull() is SkippableAsrAttempts.Skipped)
        assertTrue(System.currentTimeMillis() - started < 1_000)
        assertFalse(a.stalled.value)
    }

    @Test fun `canSkip false is never offered`() = runBlocking {
        val a = attempts()
        val caller = async { a.run("last", null, canSkip = false) { delay(500); "ok" } }
        delay(350)
        assertFalse(a.stalled.value)
        assertEquals("ok", caller.await())
        assertTrue(a.skipStalled().isEmpty())
    }

    @Test fun `errors pass through unchanged`() = runBlocking {
        val a = attempts()
        try {
            a.run("bad", "next", canSkip = true) { throw java.io.IOException("boom") }
            fail("expected IOException")
        } catch (e: java.io.IOException) {
            assertEquals("boom", e.message)
        }
    }

    @Test fun `a stalled attempt that finishes on its own clears the stall`() = runBlocking {
        val a = attempts()
        val seen = mutableListOf<Boolean>()
        val caller = async { a.run("slow", "next", canSkip = true) { delay(500); "done" } }
        withTimeout(2_000) { while (!a.stalled.value) delay(10) }
        seen += a.stalled.value
        assertEquals("done", caller.await())
        seen += a.stalled.value
        assertEquals(listOf(true, false), seen)
    }

    @Test fun `caller cancellation returns promptly and clears the stall`() = runBlocking {
        val a = attempts()
        val caller = async { a.run("hung", "next", canSkip = true) { delay(10_000); "never" } }
        withTimeout(2_000) { while (!a.stalled.value) delay(10) }
        caller.cancel()
        withTimeout(1_000) { runCatching { caller.await() } }
        assertFalse(a.stalled.value)
    }

    @Test fun `concurrent stalls skip together`() = runBlocking {
        val a = attempts()
        val c1 = async { runCatching { a.run("a", "n1", canSkip = true) { Thread.sleep(3_000); "x" } } }
        val c2 = async { runCatching { a.run("b", "n2", canSkip = true) { Thread.sleep(3_000); "y" } } }
        withTimeout(2_000) { while (true) { delay(20); if (a.stalled.value) { delay(100); break } } }
        assertEquals(setOf("n1", "n2"), a.skipStalled().toSet())
        assertTrue(withTimeout(1_000) { c1.await() }.exceptionOrNull() is SkippableAsrAttempts.Skipped)
        assertTrue(withTimeout(1_000) { c2.await() }.exceptionOrNull() is SkippableAsrAttempts.Skipped)
    }
}
