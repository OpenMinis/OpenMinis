package com.openminis.app.ui.chat.voice

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract for [voiceWordRangeAt] — the word range a transcript double-tap
 * seeds into the editor (upstream 1.15-beta.4: "double-tapping a word while
 * editing the transcript selects it").
 *
 * Cases stick to boundaries where the JDK (CI) and on-device ICU agree:
 * Latin words, whitespace runs, single CJK characters isolated by spaces.
 * Multi-character CJK dictionary joining differs between the two runtimes
 * by design and is deliberately not pinned.
 */
class VoiceWordRangeTest {

    @Test
    fun `tap inside a word selects that word`() {
        assertEquals(TextRange(0, 5), voiceWordRangeAt("hello world", 2))
        assertEquals(TextRange(6, 11), voiceWordRangeAt("hello world", 8))
    }

    @Test
    fun `tap on the boundary between word and space selects the word`() {
        // Offset 5 sits exactly on the hello/space boundary; the naive
        // preceding/following span would be "hello " — the word, not its
        // trailing space, must be selected.
        assertEquals(TextRange(0, 5), voiceWordRangeAt("hello world", 5))
        // Offset 6 sits on the space/world boundary; span " world" trims to
        // "world".
        assertEquals(TextRange(6, 11), voiceWordRangeAt("hello world", 6))
    }

    @Test
    fun `tap inside a whitespace run collapses to a cursor`() {
        val range = voiceWordRangeAt("a   b", 2)
        assertEquals(2, range.start)
        assertEquals(2, range.end)
    }

    @Test
    fun `tap at the end of the text selects the last word`() {
        assertEquals(TextRange(6, 11), voiceWordRangeAt("hello world", 11))
        assertEquals(TextRange(6, 11), voiceWordRangeAt("hello world", 99))
    }

    @Test
    fun `tap at the start selects the first word`() {
        assertEquals(TextRange(0, 5), voiceWordRangeAt("hello world", 0))
        assertEquals(TextRange(0, 5), voiceWordRangeAt("hello world", -3))
    }

    @Test
    fun `empty text yields a zero cursor`() {
        assertEquals(TextRange(0), voiceWordRangeAt("", 0))
        assertEquals(TextRange(0), voiceWordRangeAt("", 5))
    }

    @Test
    fun `isolated CJK character is its own word`() {
        // 你 isolated by spaces: one segment on both JDK and device ICU.
        assertEquals(TextRange(2, 3), voiceWordRangeAt("a 你 b", 2))
    }

    @Test
    fun `digits are part of the word`() {
        assertEquals(TextRange(0, 4), voiceWordRangeAt("gpt4 model", 1))
        assertEquals(TextRange(5, 10), voiceWordRangeAt("gpt4 model", 7))
    }

    @Test
    fun `single word text selects it from any offset`() {
        assertEquals(TextRange(0, 5), voiceWordRangeAt("hello", 0))
        assertEquals(TextRange(0, 5), voiceWordRangeAt("hello", 3))
        assertEquals(TextRange(0, 5), voiceWordRangeAt("hello", 5))
    }

    @Test
    fun `whitespace-only text collapses to a cursor`() {
        val range = voiceWordRangeAt("   ", 1)
        assertEquals(1, range.start)
        assertEquals(1, range.end)
    }
}
