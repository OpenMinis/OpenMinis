package com.openminis.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.openminis.app.data.model.ModelEntry
import kotlinx.coroutines.delay

/**
 * [T-picker-search-relevance] [T-picker-search-debounce] [T-picker-search-cap]
 * Model-picker search: relevance scoring, a debounced query and a result cap.
 * iOS parity: ModelSearchScorer + UnifiedModelPicker (30fb01839, 0dcb55a0b;
 * GH#271, GH#272).
 *
 * Android filtered with a Bool match (`contains` / subsequence) on every
 * keystroke, kept the provider's order — so a loose subsequence hit could
 * bury an exact one — and put every match on screen. With an aggregator
 * holding 7,000+ models each keystroke re-ran the whole pass and a short query
 * mounted thousands of rows.
 *
 * Pure except [rememberDebouncedQuery], so the ranking is unit-tested
 * directly (ModelSearchTest).
 */
object ModelSearch {

    /** Tiers as on iOS, spread wide so no bonus lifts a lower tier over a higher one. */
    const val EXACT = 1000
    const val PREFIX = 900
    const val TOKEN_PREFIX = 800
    const val SUBSTRING = 700
    const val SUBSEQUENCE = 100

    /** Typing must settle this long before the list re-filters (iOS: 120 ms). */
    const val DEBOUNCE_MS = 120L

    /**
     * Rows shown for one search, counted ACROSS provider sections: one
     * aggregator with 7,000 models is the reported case, and a per-section
     * cap would not bound it. iOS: 150.
     */
    const val MAX_RESULTS = 150

    /**
     * Score [text] against [query]; 0 = no match. [query] must already be
     * lowercased and trimmed — scoring runs per candidate per search, so the
     * caller lowercases the query once, outside the loop.
     */
    fun score(text: String, query: String): Int {
        if (query.isEmpty()) return EXACT
        val target = text.lowercase()
        if (target == query) return EXACT
        if (target.startsWith(query)) return PREFIX + lengthBonus(target)
        if (tokenHasPrefix(target, query)) return TOKEN_PREFIX + lengthBonus(target)
        if (target.contains(query)) return SUBSTRING + lengthBonus(target)
        if (isSubsequence(query, target)) return SUBSEQUENCE
        return 0
    }

    /** Best score across fields (display name, id): an exact id hit ranks as exact. */
    fun bestScore(texts: List<String>, query: String): Int {
        var best = 0
        for (t in texts) {
            val s = score(t, query)
            if (s > best) best = s
            if (best >= EXACT) break
        }
        return best
    }

    /** Normalize what the user typed into the form [score] expects. */
    fun normalize(raw: String): String = raw.trim().lowercase()

    /** Up to 99 points, shorter first, never reaching the next tier. */
    private fun lengthBonus(target: String): Int = maxOf(0, 99 - minOf(99, target.length))

    /**
     * True when any word of [target] starts with [query]. Model ids are
     * separated by `- / . _ :` far more often than by spaces, so all count as
     * boundaries — otherwise "sonnet" would not token-match `claude-3-5-sonnet`.
     */
    private fun tokenHasPrefix(target: String, query: String): Boolean {
        var atBoundary = true
        for (i in target.indices) {
            if (atBoundary && target.startsWith(query, i)) return true
            val ch = target[i]
            atBoundary = ch == '-' || ch == '/' || ch == '.' || ch == '_' || ch == ':' || ch == ' '
        }
        return false
    }

    /** The old loose match, kept as the lowest tier so nothing findable becomes unfindable. */
    private fun isSubsequence(query: String, target: String): Boolean {
        var idx = 0
        for (ch in query) {
            val found = target.indexOf(ch, idx)
            if (found < 0) return false
            idx = found + 1
        }
        return true
    }

    /**
     * Keep the entries of one section that match [query], most relevant
     * first. Ties keep the incoming order — callers pass sections already in
     * release-rank order, which is how the list reads unsearched, so equal
     * relevance keeps the familiar newest-first order.
     */
    fun rank(entries: List<ModelEntry>, query: String): List<ModelEntry> {
        if (query.isEmpty()) return entries
        val scored = ArrayList<Pair<ModelEntry, Int>>()
        for (e in entries) {
            val s = bestScore(listOf(e.model.displayName, e.model.id), query)
            if (s > 0) scored += e to s
        }
        // sortedByDescending is stable, so ties keep release-rank order.
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    /** One provider section's search result. */
    data class Section<K>(val key: K, val entries: List<ModelEntry>)

    data class Result<K>(
        val sections: List<Section<K>>,
        /** Matches before the cap — drives the "showing N of M" footer. */
        val totalMatches: Int,
    ) {
        val shown: Int get() = sections.sumOf { it.entries.size }
        val truncated: Boolean get() = totalMatches > shown
    }

    /**
     * Search sections of entries: a section whose [label] matches shows ALL
     * its entries (a self-hosted provider's label is often its only memorable
     * name — iOS T-picker-search-provider-name); otherwise its matching
     * entries, ranked. Empty sections drop out. Then the cap is applied across
     * sections, in section order, so what survives is the most relevant part
     * of each section rather than an arbitrary slice. An empty [query] returns
     * the sections unchanged and uncapped.
     */
    fun <K> search(
        sections: List<Pair<K, List<ModelEntry>>>,
        label: (K) -> String,
        query: String,
        maxResults: Int = MAX_RESULTS,
    ): Result<K> {
        if (query.isEmpty()) {
            val all = sections.filter { it.second.isNotEmpty() }.map { Section(it.first, it.second) }
            return Result(all, all.sumOf { it.entries.size })
        }
        val matched = sections.mapNotNull { (key, entries) ->
            val hit = if (score(label(key), query) > 0) entries else rank(entries, query)
            if (hit.isEmpty()) null else Section(key, hit)
        }
        val total = matched.sumOf { it.entries.size }
        var remaining = maxResults
        val capped = ArrayList<Section<K>>()
        for (s in matched) {
            if (remaining <= 0) break
            if (s.entries.size <= remaining) {
                capped += s
                remaining -= s.entries.size
            } else {
                capped += Section(s.key, s.entries.take(remaining))
                remaining = 0
            }
        }
        return Result(capped, total)
    }
}

/**
 * [T-picker-search-debounce] The search text, trailing the field by
 * [ModelSearch.DEBOUNCE_MS] and normalized, so a burst of typing runs the
 * search once. Clearing applies immediately: waiting to restore the full list
 * reads as lag, and the unsearched list costs nothing.
 */
@Composable
fun rememberDebouncedQuery(raw: String, delayMs: Long = ModelSearch.DEBOUNCE_MS): String {
    val normalized = ModelSearch.normalize(raw)
    var debounced by remember { mutableStateOf(normalized) }
    LaunchedEffect(normalized) {
        if (normalized.isNotEmpty()) delay(delayMs)
        debounced = normalized
    }
    return if (normalized.isEmpty()) "" else debounced
}
