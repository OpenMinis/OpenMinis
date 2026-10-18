package com.openminis.app.ui.sandbox

/**
 * [T-android-preview-long-line] Bounds a text preview before it reaches Compose.
 *
 * The preview reads up to 512 KB (`MAX_TEXT_PREVIEW_BYTES`) and hands it to a
 * `Text`/markdown renderer. That is fine for prose, but tool output is often a
 * single line: a minified JSON blob, a base64 payload, or a one-line log. A
 * 512 KB string with no line breaks is one enormous paragraph for the text
 * layout engine — it has to shape every glyph to decide where to wrap, on the
 * frame that opens the preview, which reads as the app freezing (and can ANR
 * on slower devices).
 *
 * Two independent bounds, because either one alone can be pathological:
 *  • every line is capped at [MAX_LINE_CHARS] characters — this is the fix;
 *  • the total line count is capped at [MAX_LINES] — a 512 KB file of 40-char
 *    lines is still 13k lines of layout.
 *
 * Truncation is marked with an ellipsis so the user can tell the preview is
 * clipped rather than the file being short. The full file remains openable in a
 * real editor / via share — this only bounds the built-in preview.
 */
internal object PreviewTextGuard {
    /** ~25–30 visual lines' worth of a single unbroken line. */
    const val MAX_LINE_CHARS = 2_000

    /** Enough for any document a phone preview can scroll sensibly. */
    const val MAX_LINES = 20_000

    fun clamp(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(minOf(text.length, MAX_LINE_CHARS * 64))
        var index = 0
        var lines = 0
        while (index <= text.length) {
            if (lines == MAX_LINES) {
                // No leading newline: the loop already terminated the previous
                // line, so adding one here would insert a blank line before the
                // marker (and push the count one past the cap).
                out.append("… (preview truncated)")
                break
            }
            var end = text.indexOf('\n', index)
            val hasNewline = end >= 0
            if (!hasNewline) end = text.length
            val lineLength = end - index
            if (lineLength > MAX_LINE_CHARS) {
                out.append(text, index, index + MAX_LINE_CHARS)
                out.append("… (line truncated)")
            } else {
                out.append(text, index, end)
            }
            lines++
            if (!hasNewline) break
            out.append('\n')
            index = end + 1
        }
        return out.toString()
    }
}
