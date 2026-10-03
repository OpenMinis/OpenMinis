package com.openminis.app.speech.correction

/**
 * [T-android-voice-correction] Turns a before/after edit into learnable
 * confusion pairs. Port of iOS `Agent/Speech/VoiceCorrectionDiff.swift`.
 *
 * ## Why character-level LCS and not token-level
 * Token-level alignment was tried on iOS and FAILS. Fixing a name changes how
 * the segmenter cuts its neighbour:
 *
 *     "张山说..."  -> ["张山", "说", ...]
 *     "张三说..."  -> ["张三说", ...]
 *
 * The LCS then emits a 2-for-1 replace, which any "1-for-1 only" filter
 * silently discards, and the summary comes back empty. Re-segmentation after a
 * substitution is the NORM, not an edge case.
 *
 * So: diff at character level (stable), then widen each replacement out to the
 * word boundaries of the ORIGINAL so the learned pair reads as 张山→张三 rather
 * than the minimal 山→三.
 */
object VoiceCorrectionDiff {

    /** One learned substitution. */
    data class Pair(val from: String, val to: String)

    /**
     * Share of the text that changed: Levenshtein over edit units, normalized by
     * the longer side. Compared against [VoiceCorrectionConfig.MAX_CHAR_CHANGE_RATIO]
     * to reject an LLM that rewrote the whole utterance instead of fixing it.
     *
     * [T-android-voice-mixed-term-restore] Each CJK character or symbol is one
     * unit, and each run of ASCII letters/digits is ONE unit. Counting raw
     * characters made restoring an English term look like a rewrite: "看看遗穴"
     * -> "看看Issue" is one word swapped, but 5 of 7 characters differ (0.71 >
     * 0.5) and the correction was thrown away. A Latin word is one spoken word,
     * so it is one unit here (2 of 4 = 0.5, accepted). Port of iOS 8622ea275.
     */
    fun charChangeRatio(from: String, to: String): Double {
        val a = editUnits(from)
        val b = editUnits(to)
        if (a.isEmpty() && b.isEmpty()) return 0.0
        return levenshtein(a, b).toDouble() / maxOf(a.size, b.size, 1).toDouble()
    }

    internal fun editUnits(text: String): List<String> {
        val units = mutableListOf<String>()
        val word = StringBuilder()
        for (c in text) {
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') { word.append(c); continue }
            if (word.isNotEmpty()) { units.add(word.toString()); word.setLength(0) }
            units.add(c.toString())
        }
        if (word.isNotEmpty()) units.add(word.toString())
        return units
    }

    private fun levenshtein(a: List<String>, b: List<String>): Int {
        if (a.isEmpty()) return b.size
        if (b.isEmpty()) return a.size
        var previous = IntArray(b.size + 1) { it }
        var current = IntArray(b.size + 1)
        for (i in 1..a.size) {
            current[0] = i
            for (j in 1..b.size) {
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            val t = previous; previous = current; current = t
        }
        return previous[b.size]
    }

    /**
     * Extract word-level substitution pairs between [from] and [to].
     *
     * Only `.replace` operations become pairs — a pure insertion or deletion
     * carries no "this was misheard as that" information.
     */
    fun tokenPairs(from: String, to: String, tokenize: (String) -> List<String>): List<Pair> {
        if (from.isEmpty() || to.isEmpty() || from == to) return emptyList()

        val a = from.map { it.toString() }
        val b = to.map { it.toString() }

        // Word spans of the ORIGINAL only. Segmenters drop whitespace, so we
        // cannot assume the tokens concatenate back to the source — locate each
        // one forward from a cursor instead.
        // Half-open [start, end) spans of each word in the original.
        val spans = wordSpans(from, tokenize)
        fun wordContaining(index: Int): kotlin.Pair<Int, Int>? =
            spans.firstOrNull { index >= it.first && index < it.second }

        val out = mutableListOf<Pair>()
        var ai = 0
        var bi = 0
        for (op in LcsDiff.diff(a, b)) {
            when (op) {
                is LcsDiff.Op.Equal -> { ai += op.items.size; bi += op.items.size }
                is LcsDiff.Op.Insert -> bi += op.items.size
                is LcsDiff.Op.Delete -> ai += op.items.size
                is LcsDiff.Op.Replace -> {
                    val oldStart = ai
                    val oldEnd = ai + op.old.size
                    val newStart = bi
                    val newEnd = bi + op.new.size

                    // Widen the "was" side to whole words…
                    var lo = oldStart
                    var hi = oldEnd
                    wordContaining(oldStart)?.let { lo = minOf(lo, it.first) }
                    if (oldEnd > oldStart) {
                        wordContaining(oldEnd - 1)?.let { hi = maxOf(hi, it.second) }
                    }
                    // …and pad the "became" side by the same amount on each
                    // side. Safe because the padding characters came from
                    // `.equal` runs, so they are identical in both strings.
                    val padLeft = oldStart - lo
                    val padRight = hi - oldEnd
                    val nLo = maxOf(0, newStart - padLeft)
                    val nHi = minOf(b.size, newEnd + padRight)

                    val was = a.subList(lo, minOf(hi, a.size)).joinToString("")
                    val became = b.subList(minOf(nLo, b.size), nHi).joinToString("")

                    ai = oldEnd
                    bi = newEnd

                    if (was.isEmpty() || became.isEmpty()) continue
                    if (was == became) continue
                    // Drop length-imbalanced runs — those are rewrites, not
                    // mishearings.
                    val ratio = maxOf(was.length, became.length).toDouble() /
                        maxOf(1, minOf(was.length, became.length)).toDouble()
                    if (ratio > 2.0) continue
                    out.add(Pair(was, became))
                }
            }
        }
        return out
    }

    /**
     * Half-open [start, end) spans of each segmented word within [text],
     * located by walking forward so whitespace between tokens doesn't desync.
     */
    private fun wordSpans(
        text: String,
        tokenize: (String) -> List<String>,
    ): List<kotlin.Pair<Int, Int>> {
        val tokens = runCatching { tokenize(text) }.getOrElse { emptyList() }
        val spans = mutableListOf<kotlin.Pair<Int, Int>>()
        var cursor = 0
        for (t in tokens) {
            if (t.isEmpty()) continue
            val idx = text.indexOf(t, cursor)
            if (idx < 0) continue
            spans.add(idx to idx + t.length)
            cursor = idx + t.length
        }
        return spans
    }
}

/**
 * Classic O(n·m) LCS diff over token lists. Contiguous runs coalesce, and a
 * region where both sides have content becomes a single [Op.Replace] rather
 * than a delete followed by an insert — which is what lets the caller widen it
 * to word boundaries.
 */
object LcsDiff {

    sealed interface Op {
        data class Equal(val items: List<String>) : Op
        data class Replace(val old: List<String>, val new: List<String>) : Op
        data class Insert(val items: List<String>) : Op
        data class Delete(val items: List<String>) : Op
    }

    fun diff(a: List<String>, b: List<String>): List<Op> {
        if (a.isEmpty() && b.isEmpty()) return emptyList()
        if (a.isEmpty()) return listOf(Op.Insert(b))
        if (b.isEmpty()) return listOf(Op.Delete(a))

        // Suffix LCS table, filled backwards.
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) {
            for (j in b.size - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }

        val ops = mutableListOf<Op>()
        val pendingOld = mutableListOf<String>()
        val pendingNew = mutableListOf<String>()
        val pendingEqual = mutableListOf<String>()

        fun flushChanges() {
            if (pendingOld.isEmpty() && pendingNew.isEmpty()) return
            when {
                pendingOld.isNotEmpty() && pendingNew.isNotEmpty() ->
                    ops.add(Op.Replace(pendingOld.toList(), pendingNew.toList()))
                pendingOld.isNotEmpty() -> ops.add(Op.Delete(pendingOld.toList()))
                else -> ops.add(Op.Insert(pendingNew.toList()))
            }
            pendingOld.clear(); pendingNew.clear()
        }
        fun flushEqual() {
            if (pendingEqual.isEmpty()) return
            ops.add(Op.Equal(pendingEqual.toList()))
            pendingEqual.clear()
        }

        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            if (a[i] == b[j]) {
                flushChanges()
                pendingEqual.add(a[i]); i++; j++
            } else {
                flushEqual()
                if (lcs[i + 1][j] >= lcs[i][j + 1]) { pendingOld.add(a[i]); i++ }
                else { pendingNew.add(b[j]); j++ }
            }
        }
        flushEqual()
        while (i < a.size) { pendingOld.add(a[i]); i++ }
        while (j < b.size) { pendingNew.add(b[j]); j++ }
        flushChanges()
        return ops
    }
}
