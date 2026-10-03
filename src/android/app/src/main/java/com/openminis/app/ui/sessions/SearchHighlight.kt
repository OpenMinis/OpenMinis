package com.openminis.app.ui.sessions

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Build an [AnnotatedString] that highlights every case-insensitive
 * occurrence of [query] inside [text] with the theme's tertiaryContainer
 * background. Blank query returns the plain string with no spans.
 *
 * Used by the home session-list search results to point the user at the
 * exact span that matched their query, in both the session title and the
 * extracted message snippet.
 */
@Composable
fun highlightedAnnotatedString(text: String, query: String): AnnotatedString {
    if (query.isBlank() || text.isEmpty()) return AnnotatedString(text)
    val highlightBg = MaterialTheme.colorScheme.tertiaryContainer
    val highlightFg = MaterialTheme.colorScheme.onTertiaryContainer
    val lower = text.lowercase()
    // [T-android-search-visible-only] Highlight the TRIMMED query, as iOS does
    // (6b0ee14c1). The search itself trims (ChatRepository.searchSessionsWithHits),
    // so " GPT" finds every "GPT" — but highlighting the raw text marked only
    // hits that happened to follow a space. Seen on a Pixel 6: of six results
    // for " GPT", only "🔧 GPT Image 2" was highlighted.
    val q = query.trim().lowercase()
    return buildAnnotatedString {
        var idx = 0
        while (idx < text.length) {
            val match = lower.indexOf(q, idx)
            if (match < 0) {
                append(text.substring(idx))
                break
            }
            if (match > idx) append(text.substring(idx, match))
            withStyle(SpanStyle(background = highlightBg, color = highlightFg)) {
                append(text.substring(match, match + q.length))
            }
            idx = match + q.length
        }
    }
}

/**
 * Locate the first case-insensitive occurrence of [query] in [text] and
 * return a ~[radius]-char window centred on the hit. Newlines collapse to
 * spaces; ellipses prefix/suffix when truncated. Returns null if no hit.
 */
fun snippetAround(text: String, query: String, radius: Int = 50): String? {
    if (query.isBlank() || text.isEmpty()) return null
    val lower = text.lowercase()
    val pos = lower.indexOf(query.lowercase())
    if (pos < 0) return null
    val start = (pos - radius).coerceAtLeast(0)
    val end = (pos + query.length + radius).coerceAtMost(text.length)
    val core = text.substring(start, end).replace('\n', ' ').replace('\r', ' ')
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < text.length) "…" else ""
    return prefix + core + suffix
}
