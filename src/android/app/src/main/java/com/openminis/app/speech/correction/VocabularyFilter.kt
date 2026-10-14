package com.openminis.app.speech.correction

import android.content.Context
import android.util.Log
import com.openminis.app.R

/**
 * [T-android-voice-correction] Decides which mined terms are worth storing as
 * personal vocabulary. Port of iOS `Agent/Speech/VocabularyFilter.swift`.
 *
 * Absolute rejects first, then positive evidence scored to a threshold — a term
 * has to EARN its way in, because everything admitted here becomes prompt
 * budget later.
 */
object VocabularyFilter {

    /**
     * Why a term scored what it did. Returned alongside the decision so a debug
     * surface never has to recompute scoring — on iOS two copies of the formula
     * had already drifted apart.
     */
    data class ScoreBreakdown(
        var backgroundRank: Int = 0,
        var posTag: Int = 0,
        var hasLatinOrDigit: Int = 0,
        var lengthOk: Int = 0,
    ) {
        val total: Int get() = backgroundRank + posTag + hasLatinOrDigit + lengthOk
    }

    sealed interface Decision {
        data class Accepted(val score: Int, val breakdown: ScoreBreakdown) : Decision
        data class Rejected(val reason: String) : Decision
    }

    /** jieba tags that mark a proper noun: person, place, other proper, org. */
    private val PROPER_NOUN_TAGS = setOf("nr", "ns", "nz", "nt")

    /**
     * @param rank background-frequency lookup; injected so tests need no asset.
     *   Returning non-null means "ordinary word" and SUPPRESSES the rare bonus.
     */
    fun evaluate(
        term: String,
        posTag: String?,
        rank: (String) -> Int?,
    ): Decision {
        if (StopWords.contains(term)) return Decision.Rejected("stopword_blacklist")
        if (term.length < 2) return Decision.Rejected("too_short")
        if (term.length > 12) return Decision.Rejected("too_long")
        if (term.all { (it.isAsciiDigit()) || it.isPunctuationLike() || it.isWhitespace() }) {
            return Decision.Rejected("no_lexical_content")
        }

        val b = ScoreBreakdown()
        val r = rank(term)
        if (r == null || r > VoiceCorrectionConfig.VOCAB_BACKGROUND_RANK_THRESHOLD) {
            b.backgroundRank = 2
        }
        if (posTag != null && posTag in PROPER_NOUN_TAGS) b.posTag = 2

        // ⚠️ The ASCII guards below are LOAD-BEARING, not defensive noise.
        // Java's Character.isLetter() is true for Han, so an unguarded check
        // scores every Chinese term as "contains Latin"; Character.isDigit() is
        // true for CJK numerals 一/二/三, so "张三" would collect a phantom digit
        // point and "三星" would be rejected as no_lexical_content. Both were
        // real on-device bugs on iOS.
        val hasLatin = term.any { it.isAsciiLetter() }
        val hasDigit = term.any { it.isAsciiDigit() }
        if (hasLatin || hasDigit) b.hasLatinOrDigit = 1
        if (term.length in 2..6) b.lengthOk = 1

        return if (b.total >= VoiceCorrectionConfig.VOCAB_MIN_SCORE) {
            Decision.Accepted(b.total, b)
        } else {
            Decision.Rejected("low_score(${b.total})")
        }
    }

    /**
     * Candidate `(term, occurrences, posTag)` triples from [text]. Falls back to
     * plain segmentation with null tags when POS tagging yields nothing
     * (non-Chinese text, or jieba unavailable).
     */
    fun candidates(
        text: String,
        posTag: (String) -> List<Pair<String, String>>,
        segment: (String) -> List<String>,
    ): List<Triple<String, Int, String?>> {
        val tagged = runCatching { posTag(text) }.getOrElse { emptyList() }
        if (tagged.isNotEmpty()) {
            val counts = LinkedHashMap<String, Int>()
            val tags = HashMap<String, String>()
            for ((token, tag) in tagged) {
                counts[token] = (counts[token] ?: 0) + 1
                tags[token] = tag   // last tag wins on a repeated token
            }
            return counts.map { (t, n) -> Triple(t, n, tags[t]) }
        }
        val counts = LinkedHashMap<String, Int>()
        for (token in runCatching { segment(text) }.getOrElse { emptyList() }) {
            counts[token] = (counts[token] ?: 0) + 1
        }
        return counts.map { (t, n) -> Triple(t, n, null) }
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
    private fun Char.isPunctuationLike(): Boolean =
        !isLetterOrDigit() && !isWhitespace()
}

/**
 * Function words that would otherwise dominate mined vocabulary.
 *
 * The English list is not hypothetical: measured on device, the top learned
 * entries by frequency were `the`(×31), `and`(×18), `to`, `with`, `of`, `me`,
 * `for`, `from` — each scoring 4 (rare +2, length +1, ASCII letters +1), which
 * outranks genuine domain terms.
 */
object StopWords {

    val chinese: Set<String> = setOf(
        "的", "了", "是", "我", "你", "他", "她", "它", "我们", "你们", "他们",
        "这", "那", "这个", "那个", "什么", "怎么", "为什么", "哪里", "谁",
        "有", "没有", "不", "也", "都", "很", "太", "就", "还", "又", "再", "才", "只",
        "可以", "能", "会", "要", "想", "需要", "应该", "可能", "或者", "但是", "因为", "所以",
        "然后", "而且", "不过", "虽然", "如果", "继续", "好的", "嗯", "就是", "其实", "反正",
        "一下", "一点", "一样", "一起", "现在", "今天", "明天", "昨天",
        "说", "讲", "看", "听", "做", "用", "给", "让", "把", "被", "在", "和", "与", "对",
    )

    val english: Set<String> = setOf(
        "the", "a", "an", "and", "or", "but", "if", "then", "than", "that", "this", "these", "those",
        "is", "are", "was", "were", "be", "been", "being", "am",
        "do", "does", "did", "done", "have", "has", "had",
        "can", "could", "will", "would", "shall", "should", "may", "might", "must",
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them",
        "my", "your", "his", "its", "our", "their",
        "to", "of", "in", "on", "at", "by", "for", "with", "from", "into", "about", "as",
        "not", "no", "yes", "ok", "okay", "so", "just", "very", "too", "also", "only",
        "what", "which", "who", "when", "where", "why", "how",
        "there", "here", "some", "any", "all", "more", "most", "one", "two",
        "get", "got", "let", "make", "made", "use", "used", "like", "want", "need",
        "please", "thanks", "thank", "hi", "hello", "now", "still", "even", "up", "out", "over",
    )

    /** Chinese matched exactly; English matched case-insensitively. */
    fun contains(term: String): Boolean =
        // Locale.ROOT — key matching against a fixed set; the device locale
        // must not change which terms are stopwords ([T-android-turkish-dotted-i]).
        chinese.contains(term) || english.contains(term.lowercase(java.util.Locale.ROOT))
}

/**
 * Background word-frequency list, loaded from `res/raw/zh_background_wordfreq.txt`
 * (the identical 204-line file iOS bundles, so both platforms score alike).
 *
 * A term's PRESENCE means "ordinary word" and suppresses the rare bonus;
 * absence proves nothing (the list is short by design), which is why rank is
 * only ever used as a NEGATIVE signal. A missing file degrades to an empty map
 * — everything then scores as rare, which is conservative, never reckless.
 */
class BackgroundWordFrequency private constructor(private val ranks: Map<String, Int>) {

    /** Null = not in the list = treat as rare. */
    fun rank(of: String): Int? = ranks[of]

    val size: Int get() = ranks.size

    companion object {
        private const val TAG = "BackgroundWordFreq"

        @Volatile
        private var instance: BackgroundWordFrequency? = null

        fun getInstance(context: Context): BackgroundWordFrequency {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: load(context.applicationContext).also { instance = it }
            }
        }

        private fun load(context: Context): BackgroundWordFrequency {
            val map = runCatching {
                context.resources.openRawResource(R.raw.zh_background_wordfreq)
                    .bufferedReader().useLines { lines -> parse(lines) }
            }.getOrElse {
                Log.w(TAG, "word-frequency list unavailable — every term scores as rare", it)
                emptyMap()
            }
            Log.i(TAG, "loaded ${map.size} background terms")
            return BackgroundWordFrequency(map)
        }

        /** `<term> <rank>` per line; `#` comments and malformed lines skipped. */
        internal fun parse(lines: Sequence<String>): Map<String, Int> {
            val out = HashMap<String, Int>()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                if (parts.size != 2) continue
                val rank = parts[1].toIntOrNull() ?: continue
                out[parts[0]] = rank
            }
            return out
        }
    }
}
