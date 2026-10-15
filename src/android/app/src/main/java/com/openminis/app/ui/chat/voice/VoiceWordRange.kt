package com.openminis.app.ui.chat.voice

import androidx.compose.ui.text.TextRange
import java.text.BreakIterator

/**
 * Word range covering [offset] in [text], used to seed the transcript editor's
 * initial selection when a double-tap opens it (upstream 1.15-beta.4:
 * "Voice input: double-tapping a word while editing the transcript selects
 * it.").
 *
 * Uses the platform word [BreakIterator] so the picked range matches what a
 * native text field's own double-tap would select — including CJK dictionary
 * words on device. A tap that lands on whitespace (or empty text) yields a
 * collapsed cursor at the offset instead: selecting a run of spaces is never
 * what the user meant.
 */
internal fun voiceWordRangeAt(text: String, offset: Int): TextRange {
    if (text.isEmpty()) return TextRange(0)
    val at = offset.coerceIn(0, text.length)
    val boundaries = BreakIterator.getWordInstance()
    boundaries.setText(text)
    val start = boundaries.preceding(at).let { if (it == BreakIterator.DONE) 0 else it }
    val end = boundaries.following(at).let { if (it == BreakIterator.DONE) text.length else it }
    if (start >= end) return TextRange(at)
    // A tap exactly ON a boundary spans the previous and next segments
    // ("word " / " word"); trim whitespace from both ends so the selection
    // is the adjacent word, not the word plus its padding. A tap inside a
    // pure whitespace run trims to nothing and collapses to a cursor.
    var s = start
    var e = end
    while (s < e && text[s].isWhitespace()) s++
    while (e > s && text[e - 1].isWhitespace()) e--
    return if (s < e) TextRange(s, e) else TextRange(at)
}
