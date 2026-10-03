package com.openminis.app.provider

import com.openminis.app.logging.AppLogger

/**
 * [T-model-release-ranking] Ranks models so the newest / most capable land at
 * the top of any list the user picks from — without anyone hand-maintaining an
 * order. Mirrors iOS `ModelReleaseIndex` (Providers/ModelReleaseIndex.swift).
 *
 * Why this exists: a picker whose first entries are stale models is an active
 * hazard, not just untidy. OpenMinis#83 was filed as "GPT-5.3 CodeX Spark
 * cannot call tools"; the real cause was that the Codex backend refuses that
 * model on a ChatGPT account (`400 … not supported`), and the refusal renders
 * as an EMPTY assistant turn. A new user picked a dead model off the list and
 * concluded the app was broken. Sorting the same 12 Codex ids by release date
 * puts all 6 callable ones at the top.
 *
 * IMPORTANT — this ranks, it does NOT gate. The date/availability correlation
 * is a by-product of OpenAI's current retention policy, not a contract. Never
 * infer "callable" from a release date.
 */
object ModelReleaseIndex {
    private const val TAG = "ModelReleaseIndex"

    /** Ranking inputs for one model; `releaseDay == null` sorts last. */
    data class Rank(
        val releaseDay: Int?,
        val outputCostPerMTok: Double,
        val contextWindow: Int,
        val displayName: String,
    )

    /**
     * Newest first, then pricier (≈ larger / more capable), then bigger context,
     * then name so the order is stable and never jitters between reads.
     *
     * Undated models sink below every dated one but are NOT hidden — custom,
     * local and relay-hosted models legitimately have no catalog entry.
     */
    val comparator: Comparator<Rank> = Comparator { a, b ->
        val ad = a.releaseDay
        val bd = b.releaseDay
        if (ad != bd) {
            return@Comparator when {
                ad == null -> 1
                bd == null -> -1
                else -> bd.compareTo(ad)
            }
        }
        if (a.outputCostPerMTok != b.outputCostPerMTok) {
            return@Comparator b.outputCostPerMTok.compareTo(a.outputCostPerMTok)
        }
        if (a.contextWindow != b.contextWindow) {
            return@Comparator b.contextWindow.compareTo(a.contextWindow)
        }
        a.displayName.compareTo(b.displayName, ignoreCase = true)
    }

    private class Entry(val day: Int, val outputCost: Double?, val context: Int?)

    private var byFullId: Map<String, Entry> = emptyMap()
    private var byTail: Map<String, Entry> = emptyMap()
    private var built = false

    @Synchronized
    private fun ensureIndex() {
        if (built) return
        val full = HashMap<String, Entry>()
        val tail = HashMap<String, Entry>()
        for (provider in ModelsDevApi.registrySnapshot().values) {
            for ((rawId, m) in provider.models) {
                val day = parseReleaseDay(m.releaseDate) ?: continue
                val e = Entry(day, m.outputCost, m.contextWindow)
                val id = rawId.lowercase()
                // The same model is republished by many providers (glm-5.2 under
                // 24) and their dates disagree. Keep the NEWEST so a lagging
                // relay can't drag a current model down the list.
                full[id]?.let { if (it.day >= day) return@let else full[id] = e } ?: run { full[id] = e }
                val t = id.substringAfterLast('/')
                tail[t]?.let { if (it.day >= day) return@let else tail[t] = e } ?: run { tail[t] = e }
            }
        }
        byFullId = full
        byTail = tail
        built = true
        AppLogger.info(TAG, "release index built: full=${full.size} tail=${tail.size}")
    }

    /**
     * [T-android-model-sort-anr] Build the index now, off the main thread.
     *
     * Same work [ensureIndex] does lazily, exposed so app start can pay for it
     * on a background thread instead of leaving the bill to whichever caller
     * happens to be first. That caller was `ChatViewModel.init` on the main
     * thread, and parsing a 4.5 MB catalog there ANR'd the app while opening a
     * chat on a loaded device.
     *
     * Idempotent and safe to call from any thread: [ensureIndex] is
     * @Synchronized and returns immediately once built, so this races correctly
     * with a real caller rather than duplicating the work.
     */
    fun warmUp() = ensureIndex()

    /** Drop a cached index so a refreshed catalog is picked up. */
    @Synchronized
    fun invalidate() {
        built = false
        byFullId = emptyMap()
        byTail = emptyMap()
    }

    /** Ranking key for a model. Unknown ids get `releaseDay = null` (sink). */
    fun rank(modelId: String, displayName: String, contextWindow: Int?): Rank {
        ensureIndex()
        val hit = resolve(modelId)
        return Rank(
            releaseDay = hit?.day,
            outputCostPerMTok = hit?.outputCost ?: 0.0,
            contextWindow = contextWindow ?: hit?.context ?: 0,
            displayName = displayName,
        )
    }

    private fun resolve(raw: String): Entry? {
        val cleaned = clean(raw)
        // 1. Full id FIRST. Entries like `aion-labs/aion-2.0` and
        //    `amazon/nova-lite-v1` exist ONLY under their namespaced id; going
        //    tail-first silently loses them (58% → 80% resolution measured on a
        //    real 868-model device catalog).
        byFullId[cleaned]?.let { return it }

        var tail = stripVendorDotPrefix(cleaned.substringAfterLast('/'))
        byTail[tail]?.let { return it }

        val undated = stripDateSuffix(tail)
        if (undated != tail) byTail[undated]?.let { return it }

        // Walk the family up: gpt-5.6-sol → gpt-5.6 → gpt
        val parts = undated.split('-').toMutableList()
        while (parts.size > 1) {
            parts.removeAt(parts.size - 1)
            byTail[parts.joinToString("-")]?.let { return it }
        }

        // Deliberately NO fuzzy/substring fallback: an earlier prototype matched
        // `us.anthropic.claude-opus-4-5-20251101-v1:0` onto `claude-opus-5` — a
        // different model six months off. A wrong date is worse than none; it
        // promotes a stale model to the top, the exact failure this prevents.
        return null
    }

    private fun clean(raw: String): String {
        var s = raw.trim().lowercase().substringBefore(':').substringBefore('@')
        for (region in listOf("us.", "eu.", "apac.", "global.")) {
            if (s.startsWith(region)) { s = s.removePrefix(region); break }
        }
        return s
    }

    /** Strip a leading `vendor.` namespace but never a version dot (`gpt-5.6`). */
    private fun stripVendorDotPrefix(s: String): String {
        val dot = s.indexOf('.')
        if (dot <= 0) return s
        val head = s.substring(0, dot)
        if (!head.all { it.isLetter() }) return s
        return s.substring(dot + 1)
    }

    /** Remove a trailing `-20YYMMDD` snapshot stamp. */
    private fun stripDateSuffix(s: String): String {
        if (s.length <= 9) return s
        val tail = s.takeLast(9)
        if (tail[0] != '-') return s
        val digits = tail.drop(1)
        if (digits.length != 8 || !digits.all { it.isDigit() } || !digits.startsWith("20")) return s
        return s.dropLast(9)
    }

    /**
     * Parse `YYYY-MM-DD` or `YYYY-MM` into a sortable day number. Returns null
     * for anything else rather than guessing — 181 bundled entries use the
     * short form, and a strict parser would silently sink all of them.
     */
    fun parseReleaseDay(raw: String?): Int? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val parts = s.split('-')
        if (parts.size != 2 && parts.size != 3) return null
        val year = parts[0].takeIf { it.length == 4 }?.toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        val day = if (parts.size == 3) {
            parts[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
        } else 1
        // Ordering only — a plain y/m/d packing sorts identically to real dates
        // and avoids dragging in a calendar for what is purely a sort key.
        return year * 10_000 + month * 100 + day
    }
}
