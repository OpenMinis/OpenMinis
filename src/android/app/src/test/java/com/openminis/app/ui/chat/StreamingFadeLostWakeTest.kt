package com.openminis.app.ui.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [T-android-stream-fade-lost-wake] Text appended while the fade ticker is
 * finishing must still fade in, never stay masked.
 *
 * Reported on a Pixel 4a: a streamed reply showed only its first character
 * above a running tool call, and stayed that way. The whole paragraph was laid
 * out (the accessibility tree had all 27 characters) but everything after the
 * first character sat under a fully opaque fade mask.
 *
 * A frame runs animation callbacks — the fade tick — before recomposition.
 * The old driver's loop exited as soon as a tick finished the last range; if
 * the same frame's recomposition then ingested more text, nothing restarted
 * it, and ranges that are never ticked draw at alpha 0. These tests drive
 * [FadeController.runTicks] with a fake frame source that ingests at exactly
 * that point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingFadeLostWakeTest {

    private fun masked(c: FadeController): List<Pair<Int, Float>> {
        val out = mutableListOf<Pair<Int, Float>>()
        c.forEachActive { start, _, alpha -> if (alpha < 1f) out += start to alpha }
        return out
    }

    @Test
    fun `text ingested in the frame whose tick finished the last range still fades in`() = runTest {
        val c = FadeController()
        c.ingest("P")
        var clock = System.nanoTime()
        var pendingAppend = true
        val ticker = launch {
            c.runTicks { onFrame ->
                yield()
                clock += 100_000_000L // 100 ms per frame
                val stillActive = onFrame(clock)
                // The reported race: the tick just emptied the ranges, and this
                // frame's recomposition delivers the rest of the paragraph.
                if (!stillActive && pendingAppend) {
                    pendingAppend = false
                    c.ingest("Pika~ let me search the map for outdoor spots nearby Pika!")
                }
                stillActive
            }
        }
        advanceUntilIdle()

        assertFalse("the append must have happened inside the race frame", pendingAppend)
        assertFalse("every appended word must finish fading", c.hasActiveRanges)
        assertEquals("nothing may stay under the mask", emptyList<Pair<Int, Float>>(), masked(c))
        ticker.cancel()
    }

    @Test
    fun `text ingested after the ticker went idle wakes it`() = runTest {
        val c = FadeController()
        var clock = System.nanoTime()
        val ticker = launch {
            c.runTicks { onFrame -> yield(); clock += 100_000_000L; onFrame(clock) }
        }
        c.ingest("first words")
        advanceUntilIdle()
        assertFalse(c.hasActiveRanges)

        c.ingest("first words and then some more")
        advanceUntilIdle()
        assertFalse("an idle ticker must wake for new ranges", c.hasActiveRanges)
        assertEquals(emptyList<Pair<Int, Float>>(), masked(c))
        ticker.cancel()
    }

    @Test
    fun `seeded text never creates a mask`() = runTest {
        // Guard the existing seed contract alongside the new wake path.
        val c = FadeController()
        c.seed("already visible text")
        assertFalse(c.hasActiveRanges)
        assertEquals(emptyList<Pair<Int, Float>>(), masked(c))
    }
}
