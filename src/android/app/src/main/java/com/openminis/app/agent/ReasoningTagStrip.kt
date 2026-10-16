package com.openminis.app.agent

import com.openminis.app.text.ReasoningTagVariants

/**
 * [T-regex-bomb] Linear reasoning-tag stripper for the persisted-history
 * send path (`effectiveAgentHistory` → `MessageTransformerChain.apply`).
 *
 * This replaces the previous regex implementation. That one was correct on
 * every input the tests could think of, but it carried two structural
 * defects that only surfaced in production tombstones:
 *
 *  1. **Native allocation rate.** Every `Regex.replace` call compiles a
 *     fresh ICU `RegexMatcher` on the native heap (Android's `Matcher`
 *     frees via finalizer). The send path re-runs the whole chain on EVERY
 *     assistant message of EVERY request assembly — retries, group
 *     fallbacks and rebuilds included. A long agent session (hundreds of
 *     assistant turns × several matcher creations per turn × the retry
 *     multiplier of a flaky provider) sustained a matcher-creation rate
 *     the finalizer queue could never drain; the process died in
 *     `scudo::allocate` with ~122k live secondary mappings, all born at
 *     `Matcher.usePattern`.
 *  2. **Backtracking recursion.** The unterminated-block patterns
 *     (`(?s)<\s*(name)\s*>(?!.*?</\s*\1\s*>).*$`) scan the entire tail of
 *     the text per candidate opener; on a pathological assistant turn this
 *     recursed deep enough to blow the 8 MB thread stack (SIGSEGV on the
 *     guard page, stack pointer deep below the mapping).
 *
 * The scanner below is a single left-to-right pass with per-form monotonic
 * close-tag cursors: worst case O(n + tags·forms) character operations,
 * zero regex objects, zero backtracking, and no allocation beyond the
 * output builder and the (usually tiny) hit list. Semantics are pinned
 * byte-for-byte against the old regexes by `ReasoningTagStripTest` and the
 * pre-existing `MessageTransformerChain` contract tests.
 *
 * Matching rules (identical to the old regexes):
 *  - named forms `<\s*name\s*>` / `</\s*name\s*>` — case-insensitive,
 *    `\s` = the six ASCII whitespace chars Java regex means;
 *  - special forms `<|name|>` / `<|/name|>` — exact, no whitespace
 *    tolerance (the old patterns had none);
 *  - a paired open strips through its nearest same-name close (lazy `.*?`
 *    semantics; anything consumed inside the span goes with it);
 *  - the leftmost open with no same-name close anywhere after it strips to
 *    end-of-text (the old `unterminatedOpen.replace` leftmost-match
 *    behavior);
 *  - stray closes stay verbatim;
 *  - code fences and inline-code spans are protected before any of this
 *    runs (fence splitting and masking moved here unchanged).
 */
object ReasoningTagStrip {

    /** Marker-wrapped index used to mask inline-code spans during matching.
     *  Private-use control chars (U+E000/U+E001) never appear in ordinary
     *  model output, and are written here as escapes, never raw. */
    private const val MASK_OPEN = '\uE000'
    private const val MASK_CLOSE = '\uE001'

    private class Form(val name: String, val special: Boolean)

    /** All tag forms, lowercased. Single source of truth: [ReasoningTagVariants]. */
    private val forms: List<Form> =
        (ReasoningTagVariants.NAMES.map { Form(it.lowercase(), false) } +
            ReasoningTagVariants.SPECIAL_NAMES.map { Form(it.lowercase(), true) })

    /**
     * Non-special form indices grouped by the lowercased first char of the
     * tag name, longest name first — the dispatch that keeps the scan
     * linear: a `<` that is not followed (after optional `\s*`) by one of
     * these lead chars, or by `/` + one of them, is rejected in O(1).
     */
    private val openDispatch: Map<Char, List<Int>> =
        forms.indices.filter { !forms[it].special }
            .groupBy { forms[it].name.first() }
            .mapValues { (_, v) -> v.sortedByDescending { forms[it].name.length } }

    private val specialIndices: List<Int> = forms.indices.filter { forms[it].special }

    /** Java regex `\s`: exactly these six ASCII chars, nothing Unicode. */
    private fun isRegexSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\r' || c == '\u000C'

    /** One tag occurrence found by [scanTags]. */
    private class Hit(val form: Int, val start: Int, val end: Int, val close: Boolean)

    /**
     * Full send-path transform: split fences, mask inline code in prose,
     * strip reasoning tags from the masked prose, restore the masks.
     * Untouched content round-trips byte-identical.
     */
    fun strip(text: String): String {
        val sb = StringBuilder(text.length)
        var from = 0
        for ((range, isCode) in fenceSegments(text)) {
            if (from < range.first) sb.append(text, from, range.first)
            val chunk = text.substring(range)
            sb.append(if (isCode) chunk else stripMasked(chunk))
            from = range.last + 1
        }
        if (from < text.length) sb.append(text, from, text.length)
        return sb.toString()
    }

    private fun stripMasked(chunk: String): String {
        if (!chunk.contains('`')) return stripTags(chunk)
        val masked = maskInlineCode(chunk)
        val stripped = stripTags(masked.text)
        return restoreMasked(stripped, masked.spans)
    }

    /**
     * The tag stripper proper: linear scan, per-form monotonic close
     * cursors, leftmost-unpaired-open → end-of-text. Public for tests.
     */
    fun stripTags(text: String): String {
        val hits = scanTags(text)
        if (hits.isEmpty()) return text
        val closes = HashMap<Int, MutableList<Int>>()
        hits.forEachIndexed { k, h ->
            if (h.close) closes.getOrPut(h.form) { mutableListOf() }.add(k)
        }
        val cursor = HashMap<Int, Int>()
        val remove = ArrayList<Pair<Int, Int>>() // (start, endExclusive)
        var i = 0
        while (i < hits.size) {
            val h = hits[i]
            if (!h.close) {
                val list = closes[h.form]
                var claimed = -1
                if (list != null) {
                    var cur = cursor.getOrDefault(h.form, 0)
                    while (cur < list.size && list[cur] <= i) cur++
                    cursor[h.form] = cur
                    if (cur < list.size) {
                        claimed = list[cur]
                        cursor[h.form] = cur + 1
                    }
                }
                if (claimed >= 0) {
                    remove += h.start to hits[claimed].end
                    i = claimed + 1
                } else {
                    // Leftmost open with no same-name close after it: strip
                    // to end-of-text (old `unterminatedOpen` leftmost match).
                    remove += h.start to text.length
                    break
                }
            } else {
                i++
            }
        }
        if (remove.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var from = 0
        for ((s, e) in remove) {
            sb.append(text, from, s)
            from = e
        }
        sb.append(text, from, text.length)
        return sb.toString()
    }

    /** Collect every tag occurrence in one left-to-right pass. */
    private fun scanTags(text: String): List<Hit> {
        val hits = mutableListOf<Hit>()
        val n = text.length
        var i = 0
        while (i < n) {
            val lt = text.indexOf('<', i)
            if (lt < 0 || lt + 1 >= n) break
            var end = -1
            var form = -1
            var close = false
            if (text[lt + 1] == '|') {
                for (fi in specialIndices) {
                    val eOpen = matchSpecialAt(text, lt, fi, false)
                    if (eOpen > 0) { end = eOpen; form = fi; break }
                    val eClose = matchSpecialAt(text, lt, fi, true)
                    if (eClose > 0) { end = eClose; form = fi; close = true; break }
                }
            } else {
                var p = lt + 1
                while (p < n && isRegexSpace(text[p])) p++
                if (p < n) {
                    val ch = text[p]
                    if (ch == '/') {
                        var q = p + 1
                        while (q < n && isRegexSpace(text[q])) q++
                        if (q < n) {
                            val d = openDispatch[text[q].lowercaseChar()]
                            if (d != null) {
                                for (fi in d) {
                                    val e = matchNamedAt(text, lt, fi, true)
                                    if (e > 0) { end = e; form = fi; close = true; break }
                                }
                            }
                        }
                    } else {
                        val d = openDispatch[ch.lowercaseChar()]
                        if (d != null) {
                            for (fi in d) {
                                val e = matchNamedAt(text, lt, fi, false)
                                if (e > 0) { end = e; form = fi; break }
                            }
                        }
                    }
                }
            }
            if (end > 0) {
                hits += Hit(form, lt, end, close)
                i = end
            } else {
                i = lt + 1
            }
        }
        return hits
    }

    /** `<\s*name\s*>` or `</\s*name\s*>`, case-insensitive. End index or -1. */
    private fun matchNamedAt(text: String, i: Int, formIdx: Int, wantClose: Boolean): Int {
        val name = forms[formIdx].name
        val n = text.length
        var p = i + 1
        if (wantClose) {
            if (p >= n || text[p] != '/') return -1
            p++
        }
        while (p < n && isRegexSpace(text[p])) p++
        val endName = p + name.length
        if (endName > n) return -1
        for (k in name.indices) {
            if (text[p + k].lowercaseChar() != name[k]) return -1
        }
        p = endName
        while (p < n && isRegexSpace(text[p])) p++
        if (p >= n || text[p] != '>') return -1
        return p + 1
    }

    /** `<|name|>` or `<|/name|>`, exact (no whitespace tolerance). End index or -1. */
    private fun matchSpecialAt(text: String, i: Int, formIdx: Int, wantClose: Boolean): Int {
        val name = forms[formIdx].name
        val n = text.length
        var p = i + 1
        if (text[p] != '|') return -1
        p++
        if (wantClose) {
            if (p >= n || text[p] != '/') return -1
            p++
        }
        val endName = p + name.length
        if (endName > n) return -1
        for (k in name.indices) {
            if (text[p + k].lowercaseChar() != name[k]) return -1
        }
        p = endName
        if (p >= n || text[p] != '|') return -1
        p++
        if (p >= n || text[p] != '>') return -1
        return p + 1
    }

    /** Masked inline-code result: [text] with spans replaced by index markers. */
    class Masked(val text: String, val spans: List<String>)

    /**
     * Linear replacement for the old `` `+[^`]*`+ `` masking regex. A
     * backtick run pairs with the next backtick run after it (gap of
     * non-backticks, possibly empty); a run of 2+ backticks with no
     * following run masks itself (the regex's within-run backtrack). A
     * lone backtick with no successor stays verbatim.
     */
    fun maskInlineCode(chunk: String): Masked {
        if (!chunk.contains('`')) return Masked(chunk, emptyList())
        val spans = mutableListOf<String>()
        val out = StringBuilder(chunk.length)
        val n = chunk.length
        var i = 0
        while (i < n) {
            if (chunk[i] != '`') {
                val nxt = chunk.indexOf('`', i)
                if (nxt < 0) {
                    out.append(chunk, i, n)
                    break
                }
                out.append(chunk, i, nxt)
                i = nxt
                continue
            }
            var j = i
            while (j < n && chunk[j] == '`') j++
            val runLen = j - i
            var k = j
            while (k < n && chunk[k] != '`') k++
            val span: String?
            if (k < n) {
                var m = k
                while (m < n && chunk[m] == '`') m++
                span = chunk.substring(i, m)
            } else if (runLen >= 2) {
                span = chunk.substring(i, j)
            } else {
                span = null
            }
            if (span == null) {
                out.append(chunk, i, j)
                i = j
            } else {
                spans += span
                out.append(MASK_OPEN).append(spans.size - 1).append(MASK_CLOSE)
                i += span.length
            }
        }
        return Masked(out.toString(), spans)
    }

    /** Linear replacement for the old mask-marker restore regex. */
    fun restoreMasked(transformed: String, spans: List<String>): String {
        if (transformed.indexOf(MASK_OPEN) < 0) return transformed
        val out = StringBuilder(transformed.length)
        val n = transformed.length
        var i = 0
        while (i < n) {
            if (transformed[i] != MASK_OPEN) {
                val nxt = transformed.indexOf(MASK_OPEN, i)
                if (nxt < 0) {
                    out.append(transformed, i, n)
                    break
                }
                out.append(transformed, i, nxt)
                i = nxt
                continue
            }
            var j = i + 1
            while (j < n && transformed[j] in '0'..'9') j++
            if (j < n && transformed[j] == MASK_CLOSE) {
                val idx = transformed.substring(i + 1, j).toIntOrNull()
                out.append(
                    if (idx != null && idx < spans.size) spans[idx]
                    else transformed.substring(i, j + 1),
                )
                i = j + 1
            } else {
                out.append(transformed[i])
                i++
            }
        }
        return out.toString()
    }

    /**
     * Contiguous (range, isCode) segments of [text]. Moved verbatim from the
     * old private implementation — its behavior is pinned by
     * `FenceAwareTagStripperTest` and `ReasoningTagStripperCoverageTest`.
     */
    internal fun fenceSegments(text: String): List<Pair<IntRange, Boolean>> {
        val out = mutableListOf<Pair<IntRange, Boolean>>()
        var inFence = false
        var fenceMark = ""
        var segStart = 0
        var searchFrom = 0
        while (true) {
            val nl = text.indexOf('\n', searchFrom)
            val lineEnd = if (nl >= 0) nl else text.length
            val line = text.substring(searchFrom, lineEnd)
            val trimmed = line.trimStart()
            if (!inFence && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
                // [T-think-tag-fence-open] Flush the prose that precedes the
                // fence and move segStart onto the fence line. Without this
                // only the CLOSE branch advanced segStart, so the segment
                // emitted at close ran from the PREVIOUS segment's start —
                // i.e. everything the model wrote before this fence was
                // classified isCode=true, and the stripper appends isCode
                // chunks VERBATIM (no transform). A `<thinking>` block or a
                // raw `<|...|>` special token sitting in prose before a code
                // block therefore survived into the replayed assistant
                // history untouched — the exact amplification the
                // T-think-tag-code-fence fix was for. The fence line itself
                // stays part of the code segment, as before.
                if (searchFrom > segStart) out += segStart until searchFrom to false
                segStart = searchFrom
                inFence = true
                fenceMark = trimmed.take(3)
            } else if (inFence && trimmed.trimEnd().isNotEmpty() &&
                trimmed.trimEnd().all { it == fenceMark[0] } &&
                trimmed.trimEnd().length >= fenceMark.length
            ) {
                // A closing fence is a line of nothing but the fence
                // character, at least as long as the opener (CommonMark):
                // ```` ```` and ``` followed by trailing spaces all close a
                // ``` block. Requiring an exact 3-character match left those
                // unclosed, so the rest of the reply was treated as one code
                // segment and its tags were never stripped.
                // The close fence is the last line with no trailing
                // newline: nl < 0 makes lineEnd == text.length, and
                // `until lineEnd + 1` would set range.last = text.length,
                // so the downstream text.substring(range) asks for
                // end = length + 1 → StringIndexOutOfBoundsException
                // (the reported "begin 0, end 376, length 375"; an
                // assistant turn whose closing ``` sits on the final
                // line without a trailing newline — DeepSeek/QwQ do
                // this). Coerce the upper bound to the text end: with a
                // real newline we keep it (lineEnd + 1 == nl + 1), without
                // one there is nothing past lineEnd to include anyway.
                out += segStart until (lineEnd + 1).coerceAtMost(text.length) to true
                segStart = lineEnd + 1
                inFence = false
            }
            if (nl < 0) break
            searchFrom = nl + 1
        }
        if (inFence && segStart < text.length) {
            out += segStart until text.length to true
            segStart = text.length
        }
        if (segStart < text.length) out += segStart until text.length to false
        return out
    }
}
