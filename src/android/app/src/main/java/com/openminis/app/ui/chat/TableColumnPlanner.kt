package com.openminis.app.ui.chat

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * [T-android-table-width-by-content] Decides markdown table column widths from
 * how much text each column holds. Port of iOS `TableColumnPlanner` as of
 * b3ec29cee (T-table-width-by-content); widths are in dp, the iOS constants
 * are points, so the numbers match one to one.
 *
 * History: every column at its single-line width (up to 5× the viewport) made
 * prose tables several screens wide; the next plan guaranteed three columns,
 * then two plus a peek — both capped every visible column at the SAME width,
 * so a column with three times the text came out as narrow as its neighbour.
 * Now widths follow the content ratio and a wordier column is never narrower.
 *
 * Per column (all widths include the cell's horizontal padding):
 *   n = natural width: widest cell on one line, never below FLOOR_WIDTH
 *   h = hard minimum: min(n, max(floor, min(longest Latin word, scrollCap), hardMin))
 *   c = comfortable width: min(n, max(h, COMFORT_FRACTION · W))
 * Modes:
 *   1. Σn ≤ W  → NATURAL: every column grows in proportion to n to fill W.
 *   2. Σc ≤ W  → WRAPPED: max(h, min(n, C + (n − C) · t)) with C = COMFORT · W
 *                and one t ∈ [0, 1] chosen so the table exactly fills W.
 *   3. else    → SCROLLS: clamp(n, c, max(c, scrollCap)); the column crossing
 *                the right edge shows at least PEEK_MIN.
 * Invariant (tested): n_i ≥ n_j ⇒ width_i ≥ width_j, except where a long word
 * or an inline formula forces a minimum.
 *
 * Android-only: [Column.hardMin]. iOS fits an over-wide cell formula to the
 * cell (wrap at relations / scale); Android's inline formula is a fixed-size
 * Placeholder sized before layout, so the column must hold it instead.
 *
 * Pure (no Android types) so `TableColumnPlannerTest` runs it on the JVM.
 */
internal object TableColumnPlanner {

    /**
     * @param natural widest cell of the column on a single line, padding included
     * @param minUnbreakable widest run that should not be split (longest Latin word), padding included
     * @param hardMin width the column can never go below, padding included: the
     *   widest inline-formula slot, which cannot wrap. 0 when the column has none.
     */
    data class Column(val natural: Float, val minUnbreakable: Float, val hardMin: Float = 0f)

    enum class Mode { NATURAL, WRAPPED, SCROLLS }

    data class Plan(val widths: FloatArray, val tableWidth: Float, val mode: Mode) {
        override fun equals(other: Any?): Boolean = other is Plan && widths.contentEquals(other.widths) &&
            tableWidth == other.tableWidth && mode == other.mode

        override fun hashCode(): Int = widths.contentHashCode()
    }

    /** Narrowest column (the long-standing minimum). */
    const val FLOOR_WIDTH = 80f
    /** A prose column gets at least this share of the viewport before the table scrolls instead. */
    const val COMFORT_FRACTION = 0.35f
    /** No column (and no unbreakable word) is wider than this share when the table scrolls. */
    const val SCROLL_CAP_FRACTION = 0.7f
    /** Least visible width of the column crossing the right edge, the cue that the table scrolls. */
    const val PEEK_MIN = 24f

    fun plan(columns: List<Column>, availableWidth: Float): Plan {
        val n = columns.size
        val w = max(availableWidth, 0f)
        if (n == 0 || w <= 0f) return Plan(FloatArray(0), w, Mode.NATURAL)
        val floorW = min(FLOOR_WIDTH, w)
        val scrollCap = floor(w * SCROLL_CAP_FRACTION)
        val comfortW = floor(w * COMFORT_FRACTION)
        val natural = FloatArray(n) { max(floorW, ceil(columns[it].natural)) }
        val hard = FloatArray(n) { i ->
            min(natural[i], maxOf(floorW, min(ceil(columns[i].minUnbreakable), scrollCap), ceil(columns[i].hardMin)))
        }
        val comfort = FloatArray(n) { i -> min(natural[i], max(hard[i], comfortW)) }
        val sumNatural = natural.sum()
        val sumComfort = comfort.sum()

        if (sumNatural <= w) {
            // Everything fits on one line: spread the spare width in proportion
            // to content, so no column ends up wider than a wordier one.
            return Plan(FloatArray(n) { natural[it] * w / sumNatural }, w, Mode.NATURAL)
        }
        if (sumComfort <= w) {
            // One growth factor for every column, so width stays a function of
            // content alone; a long word only ever raises its own column to
            // that word, never gives it a bigger share of the spare width.
            fun widths(t: Float) = FloatArray(n) {
                max(hard[it], min(natural[it], comfortW + (natural[it] - comfortW) * t))
            }
            var lo = 0f
            var hi = 1f
            repeat(40) {
                val mid = (lo + hi) / 2
                if (widths(mid).sum() < w) lo = mid else hi = mid
            }
            val result = widths(hi)
            // Bisection lands within a hair of w; the widest column takes the rest.
            val widest = natural.indices.maxByOrNull { natural[it] }!!
            result[widest] += w - result.sum()
            return Plan(result, w, Mode.WRAPPED)
        }
        val widths = FloatArray(n) { max(comfort[it], min(natural[it], scrollCap)) }
        applyPeek(widths, natural, comfort, w)
        return Plan(widths, max(widths.sum(), w), Mode.SCROLLS)
    }

    /**
     * If a column boundary lands just inside the right edge, the next column
     * shows as a sliver (or not at all) and the table reads as finished.
     * Narrow the column before it — never below its comfortable width and never
     * below a column with no more text — so at least PEEK_MIN shows.
     */
    fun applyPeek(widths: FloatArray, natural: FloatArray, comfort: FloatArray, viewport: Float) {
        var x = 0f
        for (k in widths.indices) {
            if (x + widths[k] > viewport) {
                val visible = viewport - x
                if (k < 1 || visible >= PEEK_MIN) return
                val j = k - 1
                val lessText = widths.indices.filter { it != j && natural[it] <= natural[j] }
                    .maxOfOrNull { widths[it] } ?: 0f
                val give = min(PEEK_MIN - visible, widths[j] - max(comfort[j], lessText))
                if (give > 0f) widths[j] -= give
                return
            }
            x += widths[k]
        }
    }

    /**
     * Streaming stability: column widths only grow while a table streams in (a
     * later row can't make an earlier column jump narrower). A table the plan
     * fits in the viewport stays within it: any overshoot from the lift is
     * taken back from the most-lifted columns, never below the plan. A
     * scrolling table keeps its lifted widths.
     */
    fun applyingStreamingFloor(plan: Plan, previous: FloatArray?, availableWidth: Float): Plan {
        if (previous == null || previous.size != plan.widths.size || plan.widths.isEmpty()) return plan
        val widths = FloatArray(plan.widths.size) { max(plan.widths[it], previous[it]) }
        if (plan.mode != Mode.SCROLLS) {
            var over = widths.sum() - max(availableWidth, plan.widths.sum())
            while (over >= 0.5f) {
                val i = widths.indices.maxByOrNull { widths[it] - plan.widths[it] } ?: break
                val lifted = widths[i] - plan.widths[i]
                if (lifted <= 0f) break
                val take = min(over, lifted)
                widths[i] -= take
                over -= take
            }
        }
        return Plan(widths, max(widths.sum(), availableWidth), plan.mode)
    }

    /**
     * Longest run of the text that should not be split across lines: a
     * whitespace-delimited run with CJK counted as a break (CJK wraps per
     * character). Null when there is no run longer than one character.
     */
    fun longestUnbreakableRun(text: String): String? {
        var best: String? = null
        var start = -1
        var i = 0
        fun close(end: Int) {
            if (start < 0) return
            val run = text.substring(start, end)
            if (run.codePointCount(0, run.length) > (best?.let { it.codePointCount(0, it.length) } ?: 0)) best = run
            start = -1
        }
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val breaks = Character.isWhitespace(cp) || isCjk(cp)
            if (breaks) close(i) else if (start < 0) start = i
            i += Character.charCount(cp)
        }
        close(text.length)
        val b = best ?: return null
        return if (b.codePointCount(0, b.length) > 1) b else null
    }

    private fun isCjk(cp: Int): Boolean = cp in 0x1100..0x11FF || cp in 0x2E80..0x2FDF ||
        cp in 0x3000..0x303F || cp in 0x3040..0x30FF || cp in 0x3100..0x31FF ||
        cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xAC00..0xD7AF ||
        cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFFEF ||
        cp in 0x20000..0x2FA1F
}
