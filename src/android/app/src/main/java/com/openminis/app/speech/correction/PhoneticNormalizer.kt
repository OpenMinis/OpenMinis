package com.openminis.app.speech.correction

import java.util.Locale

/**
 * [T-android-voice-correction] Maps a term to a phonetic key so homophone
 * confusions collapse to one lookup key. Port of iOS
 * `Providers/Voice/PhoneticNormalizer.swift`.
 *
 * The interface is deliberately locale-agnostic — "pinyin" must never leak into
 * schema or interface names (the columns are `phonetic_key` /
 * `original_phonetic_key`), so a future Metaphone or kana normalizer drops in
 * without a migration.
 */
interface PhoneticNormalizer {
    val locale: String

    /** Phonetic key for [text], or "" when it has no phonetic content. */
    fun normalize(text: String): String
}

/**
 * Locale → normalizer lookup. Only `zh` is implemented, matching iOS; `null`
 * means "cannot compare phonetically here", and callers must degrade to no
 * correction rather than crash.
 */
object PhoneticNormalizerRegistry {

    fun normalizer(locale: String): PhoneticNormalizer? =
        when (normalizedLocaleKey(locale)) {
            "zh" -> PinyinNormalizer
            // Future: "en" -> MetaphoneNormalizer, "ja" -> KanaNormalizer
            else -> null
        }

    /**
     * Folds `zh-Hans` / `zh_CN` / `zh-Hant` to `zh` so Simplified and
     * Traditional users share one dictionary instead of fragmenting it.
     */
    fun normalizedLocaleKey(locale: String): String {
        // Locale.ROOT: this key is compared against fixed literals ("zh") and
        // persisted — under a Turkish device default "hi-IN".lowercase() folds
        // to "hı-ın", corrupting key matching ([T-android-turkish-dotted-i]).
        val lower = locale.lowercase(Locale.ROOT)
        val base = lower.split('-', '_').firstOrNull() ?: return lower
        return base
    }
}

/**
 * Mandarin pinyin key, tones discarded.
 *
 * Uses `android.icu.text.Transliterator` — the SAME ICU data iOS reaches
 * through `CFStringTransform(kCFStringTransformMandarinLatin)`, so the two
 * platforms produce byte-identical keys and a dictionary is portable between
 * them. This was verified on device before being chosen: "北京大学" →
 * "bei jing da xue". A third-party pinyin library (pinyin4j / TinyPinyin) would
 * have used different polyphone heuristics and produced DIFFERENT keys,
 * silently breaking cross-device parity.
 *
 * Tones are dropped on purpose: ASR confusion happens within a syllable across
 * tones (张三/章三), so keeping tone marks would split exactly the pairs we want
 * to collapse. Polyphones are NOT disambiguated — ICU picks one reading per
 * character with no context, matching iOS. Latin and digits pass through
 * lowercased, so "iPhone手机" → "iphoneshouji".
 */
object PinyinNormalizer : PhoneticNormalizer {

    override val locale: String = "zh"

    /**
     * `Han-Latin` gives space-separated toneful pinyin; the NFD + nonspacing-mark
     * removal strips the tone diacritics. Equivalent to iOS's MandarinLatin +
     * StripDiacritics pair.
     *
     * Built lazily and held forever: Transliterator construction parses ICU
     * rules and is far too expensive to repeat per call. It is documented
     * thread-safe for `transliterate` on a shared instance.
     */
    private val transliterator: android.icu.text.Transliterator? by lazy {
        runCatching {
            android.icu.text.Transliterator.getInstance(
                "Han-Latin; nfd; [:nonspacing mark:] remove",
            )
        }.onFailure {
            android.util.Log.e(TAG, "ICU Han-Latin unavailable — phonetic keys disabled", it)
        }.getOrNull()
    }

    override fun normalize(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val t = transliterator ?: return ""
        val latin = runCatching { t.transliterate(trimmed) }.getOrNull() ?: return ""
        // Collapse the whitespace ICU inserts between syllables so "zhang san"
        // and "zhangsan" key identically. Locale.ROOT: the result is the
        // persisted phonetic key — it must be byte-stable across device locales
        // (a Turkish default must not poison keys, and iOS parity is byte-exact).
        return latin.lowercase(Locale.ROOT).split(' ', '\t', '\n')
            .filter { it.isNotEmpty() }
            .joinToString("")
    }

    /**
     * Normalized edit similarity in 0.0…1.0, used to tell a CORRECTION
     * (phonetically close) from a REWRITE (user changed their mind). iOS uses
     * a 0.6 threshold.
     */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / maxOf(a.length, b.length).toDouble()
    }

    /** Two-row DP Levenshtein. */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val swap = prev; prev = cur; cur = swap
        }
        return prev[b.length]
    }

    private const val TAG = "PinyinNormalizer"
}
