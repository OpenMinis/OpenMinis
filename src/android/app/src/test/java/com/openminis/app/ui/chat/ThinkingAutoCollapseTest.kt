package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-thinking-collapse-latch] When a thinking block folds itself away.
 *
 * The composable needs a live composition, so this pins the decision it makes,
 * which is the whole user-visible behaviour: "Deep Thinking opens itself while
 * the model thinks and folds away when it stops — unless you deliberately held
 * it shut."
 *
 * The reported bug: a thinking block that had been expanded never collapsed
 * again. `ThinkingBlock` guarded the stream-end auto-collapse with a single
 * `userTouched: Boolean`, so a user who tapped to collapse and tapped again to
 * re-open latched it true permanently and the auto-collapse stopped firing for
 * the rest of the conversation. A one-bit flag cannot tell WHICH way the user
 * pushed; the guard is now the direction itself.
 */
class ThinkingAutoCollapseTest {

    @Test
    fun `a block still streaming never auto-collapses`() {
        assertFalse(shouldAutoCollapseThinking(isStreaming = true, userIntent = null))
        assertFalse(shouldAutoCollapseThinking(isStreaming = true, userIntent = true))
        assertFalse(shouldAutoCollapseThinking(isStreaming = true, userIntent = false))
    }

    @Test
    fun `an untouched block folds away when the model stops thinking`() {
        assertTrue(shouldAutoCollapseThinking(isStreaming = false, userIntent = null))
    }

    /**
     * The regression this exists for. Expanding is a request to read the block
     * WHILE it streams — the same thing auto-expand does unprompted — so there
     * is nothing left to defend once streaming ends.
     */
    @Test
    fun `a block the user expanded still folds away at stream end`() {
        assertTrue(shouldAutoCollapseThinking(isStreaming = false, userIntent = true))
    }

    /** The half of "never fight the user" that is real: a deliberate collapse stays collapsed. */
    @Test
    fun `a block the user collapsed is left alone`() {
        assertFalse(shouldAutoCollapseThinking(isStreaming = false, userIntent = false))
    }

    /**
     * The exact reported sequence, walked end to end: auto-expand while
     * streaming, user collapses, user re-expands to keep reading, stream ends.
     * Under the old one-bit latch the final step was skipped.
     */
    @Test
    fun `collapse then re-expand while streaming still collapses at the end`() {
        var intent: Boolean? = null          // auto-expanded, untouched
        assertFalse(shouldAutoCollapseThinking(isStreaming = true, userIntent = intent))

        intent = false                       // tap 1: user collapses
        assertFalse(shouldAutoCollapseThinking(isStreaming = true, userIntent = intent))

        intent = true                        // tap 2: user re-expands to read
        assertFalse(shouldAutoCollapseThinking(isStreaming = true, userIntent = intent))

        // Model stops thinking — this is the step that used to be skipped.
        assertTrue(shouldAutoCollapseThinking(isStreaming = false, userIntent = intent))
    }

    /**
     * The mirror sequence: a user who ends on a deliberate collapse keeps it,
     * however many times they toggled on the way there.
     */
    @Test
    fun `expand then collapse while streaming stays collapsed at the end`() {
        var intent: Boolean? = true
        intent = false
        assertFalse(shouldAutoCollapseThinking(isStreaming = false, userIntent = intent))
    }
}
