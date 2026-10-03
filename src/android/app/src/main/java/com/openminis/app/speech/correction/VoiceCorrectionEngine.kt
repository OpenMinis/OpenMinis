package com.openminis.app.speech.correction

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** The engine's verdict on one transcript. */
data class CorrectionSuggestion(
    val original: String,
    val corrected: String,
    val hasChange: Boolean,
    val modelGroupUsed: String,
    val durationMs: Int,
    /** Non-null when we deliberately declined to change anything. */
    val rejectedReason: String? = null,
    val diffSummary: String = "",
    val appliedPairs: List<VoiceCorrectionDiff.Pair> = emptyList(),
)

/**
 * [T-android-voice-correction] Retrieve → fuse → correct.
 * Port of iOS `Agent/Speech/VoiceCorrectionEngine.swift`.
 *
 * Never throws: every failure path returns a suggestion carrying the ORIGINAL
 * text and a `rejectedReason`. Correction is an enhancement, so a broken model
 * or a network blip must leave the user's transcript exactly as it was.
 */
class VoiceCorrectionEngine(
    private val sources: List<CorrectionSignalSource>,
    private val strategy: CorrectionStrategy,
    private val db: VoiceCorrectionDb?,
    private val segment: (String) -> List<String>,
    private val consent: () -> Boolean,
) {

    private data class CacheEntry(val candidates: List<CorrectionCandidate>, val at: Long)

    private val cacheLock = Mutex()
    private val cache = LinkedHashMap<String, CacheEntry>()

    /**
     * Full correction pass. `persistEvent` is honoured only when collection
     * consent is granted.
     */
    suspend fun correct(
        transcript: String,
        locale: String = "zh",
        context: ConversationContext = ConversationContext.EMPTY,
        persistEvent: Boolean = true,
    ): CorrectionSuggestion {
        val started = System.currentTimeMillis()
        val text = transcript.trim()
        if (text.isEmpty()) {
            return CorrectionSuggestion(transcript, transcript, false, "none", 0, "empty_input")
        }

        // Zero candidates is NOT a bail-out: the model can still fix things
        // using the conversation context and rare-term digest alone.
        val candidates = retrieveCandidates(text, locale)

        // The strategy's HTTP call is a BLOCKING OkHttp request inside
        // withContext(Dispatchers.IO), and a blocking call has no suspension
        // point for cancellation to land on — so wrapping it in
        // withTimeoutOrNull alone does NOT bound it. Measured on device: a
        // 5000ms budget still returned after 9461ms.
        //
        // Running it in its own async and awaiting THAT under the timeout gives
        // the timer a real suspension point (the await), so the budget is
        // honoured from the caller's perspective. The orphaned request is
        // cancelled and its result discarded; it cannot come back and mutate
        // anything because the suggestion is built purely from the return value.
        val outcome = try {
            coroutineScope {
                val work = async(Dispatchers.IO) {
                    strategy.correct(text, candidates, context, locale)
                }
                val result = withTimeoutOrNull(VoiceCorrectionConfig.CORRECTION_BUDGET_MS) {
                    work.await()
                }
                if (result == null) work.cancel()
                result
            } ?: return timedOut(transcript, started)
        } catch (t: Throwable) {
            Log.w(TAG, "correction failed", t)
            return CorrectionSuggestion(
                transcript, transcript, false, "none",
                (System.currentTimeMillis() - started).toInt(),
                "llm_error:${t.javaClass.simpleName}",
            )
        }

        val corrected = outcome.correctedText
        if (corrected == text) {
            // Not a failure — the model looked and found nothing to fix.
            return CorrectionSuggestion(
                transcript, transcript, false, outcome.modelGroupUsed, outcome.durationMs,
            )
        }

        // A model that rewrote more than half the characters did not correct
        // the transcript, it replaced it.
        val ratio = VoiceCorrectionDiff.charChangeRatio(text, corrected)
        if (ratio > VoiceCorrectionConfig.MAX_CHAR_CHANGE_RATIO) {
            Log.i(TAG, "[Correct] rejected diff_too_large ratio=${"%.2f".format(ratio)}")
            return CorrectionSuggestion(
                transcript, transcript, false, outcome.modelGroupUsed, outcome.durationMs,
                "diff_too_large",
            )
        }

        val pairs = VoiceCorrectionDiff.tokenPairs(text, corrected, segment)
        val summary = pairs.joinToString("，") { "${it.from}→${it.to}" }

        if (persistEvent && consent()) {
            db?.recordCorrectionEvent(text, corrected, "suggested", summary.ifEmpty { null })
        }

        return CorrectionSuggestion(
            original = transcript,
            corrected = corrected,
            hasChange = true,
            modelGroupUsed = outcome.modelGroupUsed,
            durationMs = (System.currentTimeMillis() - started).toInt(),
            diffSummary = summary,
            appliedPairs = pairs,
        )
    }

    private fun timedOut(transcript: String, started: Long) = CorrectionSuggestion(
        transcript, transcript, false, "none",
        (System.currentTimeMillis() - started).toInt(), "llm_timeout",
    )

    /**
     * Stage 1 + 2: phonetic keys → per-source lookup (concurrent) → fusion.
     */
    suspend fun retrieveCandidates(transcript: String, locale: String): List<CorrectionCandidate> {
        val normalizer = PhoneticNormalizerRegistry.normalizer(locale) ?: return emptyList()
        val started = System.currentTimeMillis()

        val keys = runCatching { segment(transcript) }.getOrElse { emptyList() }
            // Single characters are dropped: too many homophones, all noise.
            .filter { it.length >= 2 }
            .map { normalizer.normalize(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (keys.isEmpty()) return emptyList()

        val (cached, missing) = partitionByCache(keys, locale)
        val fetched = if (missing.isEmpty()) emptyList() else coroutineScope {
            // One task per source, each seeing all missing keys: wall-clock is
            // the slowest source, not the sum.
            sources.map { source ->
                async(Dispatchers.IO) {
                    runCatching {
                        source.lookupCandidates(missing, locale, VoiceCorrectionConfig.MAX_CANDIDATES)
                    }.getOrElse { emptyList() }
                }
            }.flatMap { it.await() }
        }
        if (missing.isNotEmpty()) storeInCache(missing, fetched, locale)

        val elapsed = System.currentTimeMillis() - started
        if (elapsed > VoiceCorrectionConfig.RETRIEVAL_BUDGET_MS) {
            // Observational only — matching iOS, retrieval is never aborted.
            Log.i(TAG, "[Retrieve] OVER BUDGET ${elapsed}ms keys=${keys.size}")
        }
        return fuse(cached + fetched)
    }

    /**
     * Score = sourceWeight × confidence, deduped by `phoneticKey|term` keeping
     * the better score, then the top [VoiceCorrectionConfig.MAX_CANDIDATES].
     */
    private fun fuse(candidates: List<CorrectionCandidate>): List<CorrectionCandidate> {
        val weights = sources.associate { it.sourceIdentifier to it.defaultWeight }
        fun score(c: CorrectionCandidate) = (weights[c.sourceIdentifier] ?: 0.5) * c.confidence

        val best = HashMap<String, CorrectionCandidate>()
        for (c in candidates) {
            val k = "${c.phoneticKey}|${c.term}"
            val existing = best[k]
            if (existing != null && score(existing) >= score(c)) continue
            best[k] = c
        }
        return best.values.sortedByDescending { score(it) }
            .take(VoiceCorrectionConfig.MAX_CANDIDATES)
    }

    // -- cache --
    //
    // Keys are namespaced by locale, which iOS does NOT do: its cache is keyed
    // on the bare phonetic key, so switching language can serve candidates
    // learned in the other locale. Cheap to get right here.

    private suspend fun partitionByCache(
        keys: List<String>,
        locale: String,
    ): Pair<List<CorrectionCandidate>, List<String>> = cacheLock.withLock {
        val now = System.currentTimeMillis()
        val hits = mutableListOf<CorrectionCandidate>()
        val missing = mutableListOf<String>()
        for (key in keys) {
            val entry = cache["$locale|$key"]
            if (entry != null && now - entry.at < VoiceCorrectionConfig.CACHE_TTL_MS) {
                hits.addAll(entry.candidates)
            } else {
                missing.add(key)
            }
        }
        hits to missing
    }

    private suspend fun storeInCache(
        keys: List<String>,
        candidates: List<CorrectionCandidate>,
        locale: String,
    ) = cacheLock.withLock {
        val byKey = candidates.groupBy { it.phoneticKey }
        val now = System.currentTimeMillis()
        // NEGATIVE results are cached deliberately: a key with no candidates is
        // a useful fact, and re-querying it every pass is pure cost.
        for (key in keys) cache["$locale|$key"] = CacheEntry(byKey[key] ?: emptyList(), now)

        if (cache.size > VoiceCorrectionConfig.CACHE_LIMIT) {
            val excess = cache.size - VoiceCorrectionConfig.CACHE_LIMIT
            cache.entries.sortedBy { it.value.at }.take(excess).forEach { cache.remove(it.key) }
        }
    }

    suspend fun clearCache() = cacheLock.withLock { cache.clear() }

    /**
     * Fusion in isolation. Exposed because the full retrieval path normalizes
     * through `android.icu`, which is unavailable on the JVM, so scoring and
     * dedup would otherwise only ever be covered on a device.
     */
    internal fun fuseForTest(candidates: List<CorrectionCandidate>) = fuse(candidates)

    /**
     * Prime the segmenter off the critical path. The first jieba call loads a
     * ~5MB dictionary plus the HMM model — measured at ~1.9s on iOS, versus
     * 10-12ms once warm. Without this the first correction feels broken.
     * Idempotent and safe to call repeatedly.
     */
    fun warmUp() {
        runCatching {
            segment("预热分词词典")
            PinyinNormalizer.normalize("预热")
        }.onFailure { Log.w(TAG, "warmUp failed", it) }
    }

    /**
     * Everything [correct] does except calling the model — for diagnostics.
     * Populates the cache as a side effect; never persists.
     */
    suspend fun dryRun(
        transcript: String,
        locale: String = "zh",
        context: ConversationContext = ConversationContext.EMPTY,
    ): Triple<List<String>, List<CorrectionCandidate>, String> {
        val tokens = runCatching { segment(transcript) }.getOrElse { emptyList() }
        val candidates = retrieveCandidates(transcript, locale)
        val prompt = LlmCorrectionStrategy.buildPrompt(
            transcript, candidates, context, CorrectionLanguageProfile.resolve(locale, transcript),
        )
        return Triple(tokens, candidates, prompt)
    }

    companion object {
        private const val TAG = "VoiceCorrectionEngine"
    }
}
