package com.openminis.app.data.repository

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * [T-android-search-visible-only] Session-list search: what a result shows and
 * which messages may make a session one. Port of iOS ChatStore
 * (6b0ee14c1 T-search-hit-line, bbd21900a T-search-visible-only).
 *
 * A result must show WHY it matched: its title holds the query, or a line of a
 * message the user can actually see does. Search used to match the raw
 * `parts_json` — tool-call ids and Gemini thought signatures included, random
 * base64 in which a short keyword like "gpt" turns up by chance — and then
 * showed a snippet from the oldest text hit only, so rows appeared with
 * nothing in them explaining the match.
 *
 * Pure: no database, no Android. [ChatRepository.searchSessions] runs the SQL.
 */
object SessionSearch {

    /**
     * SQL condition: message `m` holds pattern `?1` in a field the user can
     * see — a text part, a tool call's input, or a tool's output. The plain
     * LIKE stays first as the cheap prefilter; `json_each` only runs on its
     * survivors. Identical to iOS `visibleHitClause`.
     */
    const val VISIBLE_HIT_CLAUSE = """
        m.parts_json LIKE ?1 AND EXISTS (
            SELECT 1 FROM json_each(m.parts_json) p
            WHERE (json_extract(p.value, '$.type') = 'text' AND json_extract(p.value, '$.value') LIKE ?1)
               OR (json_extract(p.value, '$.type') = 'toolUse' AND json_extract(p.value, '$.value.input') LIKE ?1)
               OR (json_extract(p.value, '$.type') = 'toolResult' AND json_extract(p.value, '$.value.output') LIKE ?1))
    """

    /**
     * [T-android-search-blob-cap] Longest `parts_json` a search query returns
     * whole. Android reads a result row through a 2 MB CursorWindow: one bigger
     * row throws SQLiteBlobTooBigException, which is also an SQLiteException,
     * so it used to be mistaken for "no JSON1" and the legacy query re-read the
     * same row and threw again, uncaught — every search for a word inside a big
     * restored tool output crashed the app. Such rows exist: iOS caps nothing,
     * and a backup restore inserts them as they are. Also bounds the memory a
     * broad query holds, since every result carries its hit message.
     */
    const val MAX_HIT_JSON_CHARS = 200_000

    /** Characters of an oversized row returned around its first hit. */
    const val HIT_WINDOW_CHARS = 4_000

    /**
     * Prefix of such a window. A real `parts_json` always starts with `[`, so
     * the mark cannot collide with one; it tells [searchSnippet] to read the
     * window as raw text, while any other unparseable row still gives no line,
     * as on iOS.
     */
    const val WINDOW_MARK = "#minis-search-window#"

    /**
     * `col` whole when it is short enough, else a [HIT_WINDOW_CHARS] window
     * starting a little before the first (ASCII case-insensitive, like LIKE)
     * occurrence of `?q`, marked with [WINDOW_MARK]. The window is not valid
     * JSON; [searchSnippet] reads it as raw text.
     */
    fun cappedParts(col: String, q: String): String = """
        CASE WHEN length($col) <= $MAX_HIT_JSON_CHARS THEN $col
             ELSE '$WINDOW_MARK' || substr($col, max(1, instr(lower($col), lower($q)) - ${HIT_WINDOW_CHARS / 4}), $HIT_WINDOW_CHARS)
        END"""

    /**
     * Matching sessions (title, or a visible-field hit) with each session's
     * NEWEST such message: SQLite returns a bare column beside MAX() from that
     * row. `?1` is the LIKE pattern, `?2` the bare query (for the window of an
     * oversized row, see [MAX_HIT_JSON_CHARS]).
     */
    const val VISIBLE_SEARCH_SQL = """
        WITH newest AS (
            SELECT m.session_id, m.parts_json, MAX(m.sort_order)
            FROM messages m
            WHERE $VISIBLE_HIT_CLAUSE
            GROUP BY m.session_id)
        SELECT s.*,
            CASE WHEN length(n.parts_json) <= $MAX_HIT_JSON_CHARS THEN n.parts_json
                 ELSE '$WINDOW_MARK' || substr(n.parts_json, max(1, instr(lower(n.parts_json), lower(?2)) - ${HIT_WINDOW_CHARS / 4}), $HIT_WINDOW_CHARS)
            END AS hit_parts_json
        FROM sessions s
        LEFT JOIN newest n ON n.session_id = s.id
        WHERE s.title LIKE ?1 OR n.session_id IS NOT NULL
        ORDER BY s.updated_at DESC
    """

    /** Fallback when the SQLite build has no JSON functions: raw-JSON LIKE, as before. */
    const val LEGACY_SEARCH_SQL = """
        SELECT s.*,
               (SELECT CASE WHEN length(m2.parts_json) <= $MAX_HIT_JSON_CHARS THEN m2.parts_json
                            ELSE '$WINDOW_MARK' || substr(m2.parts_json, max(1, instr(lower(m2.parts_json), lower(?2)) - ${HIT_WINDOW_CHARS / 4}), $HIT_WINDOW_CHARS)
                       END
                FROM messages m2
                WHERE m2.session_id = s.id AND m2.parts_json LIKE ?1
                ORDER BY m2.sort_order DESC LIMIT 1) AS hit_parts_json
        FROM sessions s
        WHERE s.title LIKE ?1
           OR EXISTS (SELECT 1 FROM messages m WHERE m.session_id = s.id AND m.parts_json LIKE ?1)
        ORDER BY s.updated_at DESC
    """

    /**
     * Up to 20 matching messages of session `?2`, newest first (the
     * look-further-back pass). `?3` is the bare query, for [cappedParts].
     */
    fun olderMatchesSql(visibleOnly: Boolean): String = """
        SELECT ${cappedParts("m.parts_json", "?3")} AS parts_json FROM messages m
        WHERE m.session_id = ?2 AND ${if (visibleOnly) VISIBLE_HIT_CLAUSE else "m.parts_json LIKE ?1"}
        ORDER BY m.sort_order DESC LIMIT $OLDER_LOOKBACK
    """

    /** Messages tried per session when the newest match shows nothing. */
    const val OLDER_LOOKBACK = 20

    /** Sessions per search that may take the look-further-back pass (newest first). */
    const val OLDER_LOOKBACK_SESSIONS = 40

    /**
     * The line of [text] holding the first case-insensitive hit of [query], cut
     * to at most [maxLength] characters that keep the hit visible: [lead]
     * characters before it, the rest after, "…" marking each cut. Null when
     * [text] does not contain [query]. Port of iOS `searchHitLine`.
     *
     * Always [lead] characters before the hit, even near the line's end: a
     * row shows two lines, and a window that back-fills to [maxLength] before
     * a late hit pushed the keyword out of view.
     *
     * Counted in code points (iOS counts Characters), so a window never splits
     * a surrogate pair.
     */
    fun searchHitLine(text: String, query: String, maxLength: Int = 90, lead: Int = 18): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val hit = text.indexOf(q, ignoreCase = true)
        if (hit < 0) return null
        var lineStart = hit
        while (lineStart > 0 && !isNewline(text[lineStart - 1])) lineStart--
        var lineEnd = hit + q.length
        while (lineEnd < text.length && !isNewline(text[lineEnd])) lineEnd++
        val line = text.substring(lineStart, lineEnd)

        val cpCount = line.codePointCount(0, line.length)
        val hitStart = line.codePointCount(0, hit - lineStart)
        val hitLength = line.codePointCount(hit - lineStart, hit - lineStart + q.length)
        var from = 0
        var to = cpCount
        if (cpCount > maxLength) {
            from = maxOf(0, hitStart - lead)
            to = minOf(cpCount, maxOf(from + maxLength, hitStart + hitLength))
        }
        var window = line.substring(line.offsetByCodePoints(0, from), line.offsetByCodePoints(0, to))
        // Trim the window's own edge whitespace, but never into the hit.
        val body = window.trim { it == ' ' || it == '\t' }
        if (body.contains(q, ignoreCase = true)) window = body
        return (if (from > 0) "…" else "") + window + (if (to < cpCount) "…" else "")
    }

    private fun isNewline(c: Char) = c == '\n' || c == '\r' || c == ' ' || c == ' ' || c == '\u0085'

    /**
     * The string values of a tool call's JSON input, depth-first, object keys
     * in sorted order for determinism. Empty when the input is not JSON. Port
     * of iOS `toolInputStrings`.
     */
    fun toolInputStrings(input: String): List<String> {
        // org.json's tokener is lenient: it reads unquoted text ("ls -la") as a
        // string. iOS JSONSerialization rejects that, so only a real object,
        // array or quoted string that is the WHOLE input counts as JSON here.
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed[0] !in "{[\"") return emptyList()
        val root = try {
            val tokener = JSONTokener(trimmed)
            tokener.nextValue().also { if (tokener.more()) return emptyList() }
        } catch (_: Exception) {
            return emptyList()
        }
        val out = mutableListOf<String>()
        fun walk(v: Any?) {
            when (v) {
                is String -> out.add(v)
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
                is JSONObject -> v.keys().asSequence().sorted().forEach { walk(v.opt(it)) }
            }
        }
        walk(root)
        return out
    }

    /**
     * The display line for a matched message: the first hit in its visible
     * text, then in its tool calls' inputs, then in their outputs. Tool hits
     * are prefixed with 🔧 (and the tool name for an input). Null when no part
     * shows [query]. Port of iOS `searchSnippet(fromPartsJSON:)`.
     */
    fun searchSnippet(partsJson: String, query: String): String? {
        // [T-android-search-blob-cap] The window cut out of an oversized row
        // (see MAX_HIT_JSON_CHARS) is raw JSON text: its raw line still shows
        // why the session matched, which beats dropping the result.
        if (partsJson.startsWith(WINDOW_MARK)) return searchHitLine(partsJson.removePrefix(WINDOW_MARK), query)
        val parts = try {
            val arr = JSONArray(partsJson)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (_: Exception) {
            return null
        }
        for (p in parts) {
            if (p.optString("type") != "text") continue
            val t = stripAttachmentMarkers(stripAttachedFiles(ChatRepository.stripSystemReminders(p.optString("value"))))
            searchHitLine(t, query)?.let { return readable(it, query) }
        }
        for (p in parts) {
            if (p.optString("type") != "toolUse") continue
            val v = p.optJSONObject("value") ?: continue
            val input = v.optString("input")
            // The input's string values (what the tool card shows), so the line
            // is real text rather than escaped JSON; the raw JSON only as a last
            // resort (a hit in a key name).
            for (text in toolInputStrings(input) + input) {
                searchHitLine(text, query)?.let { return "🔧 ${v.optString("name")}: $it" }
            }
        }
        for (p in parts) {
            if (p.optString("type") != "toolResult") continue
            val output = p.optJSONObject("value")?.optString("output").orEmpty()
            searchHitLine(output, query)?.let { return "🔧 $it" }
        }
        return null
    }

    /** Markdown syntax stripped from the line, when that keeps the hit intact. */
    internal fun readable(line: String, query: String): String {
        val plain = stripInlineMarkdown(line).trim { it == ' ' || it == '\t' }
        return if (plain.contains(query.trim(), ignoreCase = true)) plain else line
    }

    private val MD_LINK = Regex("""!?\[([^\]]*)]\([^)]*\)""")
    private val MD_EMPHASIS = Regex("""(\*\*|__|~~|`)""")
    private val MD_LINE_PREFIX = Regex("""^\s*(#{1,6}\s+|>\s?|[-*+]\s+|\d+[.)]\s+)""")

    /**
     * Inline Markdown only (a single line): links and images to their text,
     * emphasis / code / strike markers, and a leading heading, quote or list
     * marker. iOS uses its full MarkdownStripper; a hit line needs no more.
     */
    internal fun stripInlineMarkdown(line: String): String {
        var s = MD_LINE_PREFIX.replace(line, "")
        s = MD_LINK.replace(s) { it.groupValues[1] }
        s = MD_EMPHASIS.replace(s, "")
        return s
    }

    private fun stripAttachedFiles(text: String): String {
        val start = text.indexOf("<user-attached-files>")
        if (start < 0) return text
        val endTag = "</user-attached-files>"
        val end = text.indexOf(endTag, start)
        return if (end >= 0) text.substring(0, start) + text.substring(end + endTag.length) else text.substring(0, start)
    }

    private val ATTACHMENT_MARKERS = listOf(
        Regex("""\[attached [A-Za-z]+:[^\]]*]"""),
        Regex("""\[image omitted to save context[^\]]*]"""),
    )

    /** iOS `stripAttachmentMarkers`: drop the model-facing attachment captions. */
    private fun stripAttachmentMarkers(text: String): String {
        if (!text.contains('[')) return text
        var out = text
        for (re in ATTACHMENT_MARKERS) out = re.replace(out, "")
        return out
    }
}
