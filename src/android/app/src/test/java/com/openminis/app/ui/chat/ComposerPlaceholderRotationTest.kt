package com.openminis.app.ui.chat

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-composer-placeholder-rotation] Pins the picker semantics ported
 * from iOS `457ee8cb4`.
 *
 * These are simulation tests over the shipped logic, mirroring how the iOS
 * change was verified: the interesting properties are statistical (never
 * repeats, every entry reachable) or conditional (first focus depends on
 * session state), and both are cheap to check exhaustively here.
 */
class ComposerPlaceholderRotationTest {

    private val rng = Random(20260826)
    private fun realRandom(bound: Int) = rng.nextInt(bound)

    private fun next(
        current: Int,
        hasFocusedBefore: Boolean = true,
        sessionHasMessages: Boolean = true,
        screenReaderOn: Boolean = false,
        randomIndex: (Int) -> Int = ::realRandom,
    ) = ComposerPlaceholderRotation.nextIndex(
        current = current,
        hasFocusedBefore = hasFocusedBefore,
        sessionHasMessages = sessionHasMessages,
        screenReaderOn = screenReaderOn,
        randomIndex = randomIndex,
    )

    // ─── First-focus split ───────────────────────────────────────────────

    @Test
    fun `empty session keeps the default on first focus`() {
        // The default is a new user's only explanation of the field; it must
        // survive the first tap. Checked over many draws — no rng value may
        // move it.
        repeat(5000) {
            val result = next(
                current = ComposerPlaceholderRotation.DEFAULT_INDEX,
                hasFocusedBefore = false,
                sessionHasMessages = false,
            )
            assertEquals(ComposerPlaceholderRotation.DEFAULT_INDEX, result)
        }
    }

    @Test
    fun `non-empty session rotates on first focus`() {
        // Mid-conversation the default has nothing left to teach, so the very
        // first focus should already move.
        repeat(5000) {
            val result = next(
                current = ComposerPlaceholderRotation.DEFAULT_INDEX,
                hasFocusedBefore = false,
                sessionHasMessages = true,
            )
            assertNotEquals(ComposerPlaceholderRotation.DEFAULT_INDEX, result)
        }
    }

    @Test
    fun `empty session rotates on subsequent focuses`() {
        // The default only gets protection on the FIRST focus. After that the
        // user has seen it and rotation proceeds normally.
        val result = next(
            current = ComposerPlaceholderRotation.DEFAULT_INDEX,
            hasFocusedBefore = true,
            sessionHasMessages = false,
        )
        assertNotEquals(ComposerPlaceholderRotation.DEFAULT_INDEX, result)
    }

    // ─── Never repeat what's on screen ───────────────────────────────────

    @Test
    fun `rotation never lands on the entry already displayed`() {
        // A rotation that returns the same index looks broken — the user
        // focused the field and nothing happened.
        var current = ComposerPlaceholderRotation.DEFAULT_INDEX
        repeat(40_000) {
            val nextIdx = next(current)
            assertNotEquals("repeat at index $current", current, nextIdx)
            current = nextIdx
        }
    }

    @Test
    fun `result always lands inside the pool`() {
        // The shift-past-current trick must never push the index off the end.
        var current = 0
        repeat(40_000) {
            val nextIdx = next(current)
            assertTrue(
                "index $nextIdx out of range",
                nextIdx >= 0 && nextIdx < ComposerPlaceholderRotation.POOL_SIZE,
            )
            current = nextIdx
        }
    }

    @Test
    fun `every pool entry is reachable including the default`() {
        // The default must cycle back rather than being retired after the
        // first rotation away from it.
        val seen = mutableSetOf<Int>()
        var current = ComposerPlaceholderRotation.DEFAULT_INDEX
        repeat(40_000) {
            current = next(current)
            seen.add(current)
        }
        assertEquals(
            (0 until ComposerPlaceholderRotation.POOL_SIZE).toSet(),
            seen,
        )
    }

    @Test
    fun `distribution covers every alternative from each starting point`() {
        // From any given index, all other indices must be achievable — a bug
        // in the shift could silently make one unreachable.
        for (start in 0 until ComposerPlaceholderRotation.POOL_SIZE) {
            val seen = mutableSetOf<Int>()
            repeat(5000) { seen.add(next(start)) }
            val expected = (0 until ComposerPlaceholderRotation.POOL_SIZE).toSet() - start
            assertEquals("from index $start", expected, seen)
        }
    }

    // ─── Accessibility ───────────────────────────────────────────────────

    @Test
    fun `screen reader pins the current entry`() {
        // Swapping the label under TalkBack retriggers announcements over a
        // user who may be mid-read.
        for (start in 0 until ComposerPlaceholderRotation.POOL_SIZE) {
            repeat(1000) {
                assertEquals(start, next(start, screenReaderOn = true))
            }
        }
    }

    @Test
    fun `screen reader pin outranks the first-focus rotation`() {
        // Even the "mid-conversation, rotate immediately" path must yield to
        // the accessibility pin.
        assertEquals(
            2,
            next(
                current = 2,
                hasFocusedBefore = false,
                sessionHasMessages = true,
                screenReaderOn = true,
            ),
        )
    }

    // ─── Deterministic boundary checks ───────────────────────────────────

    @Test
    fun `draw below current maps to itself`() {
        // current=3, draw=1 → 1 (below, unshifted)
        assertEquals(1, next(current = 3) { 1 })
    }

    @Test
    fun `draw at or above current shifts up by one to skip it`() {
        // current=1, draw=1 → 2 (collides, so shift past)
        assertEquals(2, next(current = 1) { 1 })
        // current=0, draw=0 → 1
        assertEquals(1, next(current = 0) { 0 })
    }

    @Test
    fun `top of the draw range reaches the last pool entry`() {
        // The largest draw (POOL_SIZE-2) from current=0 must reach the final
        // index — proving the shift doesn't truncate the range.
        val maxDraw = ComposerPlaceholderRotation.POOL_SIZE - 2
        assertEquals(
            ComposerPlaceholderRotation.POOL_SIZE - 1,
            next(current = 0) { maxDraw },
        )
    }
}
