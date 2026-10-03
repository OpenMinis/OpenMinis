package com.openminis.app.speech

import java.util.Locale

/**
 * [T-android-tts-locale GH#324] Decides which [Locale] the system TTS engine
 * should speak a given piece of text in.
 *
 * ## The bug this replaces
 *
 * `TextToSpeechManager.autoDetectAndSetLanguage` was a two-way branch:
 *
 * ```
 * val locale = if (HAN_REGEX.containsMatchIn(text)) SIMPLIFIED_CHINESE else Locale.US
 * ```
 *
 * Han characters got Chinese; **everything else got US English**. Spanish,
 * German, French, Russian, Japanese, Korean, Croatian and every other shipped
 * language was read aloud by an American English voice, which for es/de/hr is
 * not an accent but genuinely unintelligible. Reported as GH#324.
 *
 * Critically, that branch ran on EVERY utterance and consulted nothing but the
 * text's script — not the app's UI language, not the system locale. So no
 * amount of localisation work could fix it: shipping `values-es` changes which
 * strings the UI renders, and changes nothing about which voice speaks them.
 *
 * ## Why the UI language is the DEFAULT, and not text detection
 *
 * Reliable natural-language identification needs a real model (ML Kit's
 * language-id, or similar); this project has no such dependency and adding one
 * to pick a TTS voice would be disproportionate. More importantly, statistical
 * detection is least reliable exactly where TTS needs it most — short replies
 * ("Vale.", "Danke!", "OK") carry almost no signal, and a wrong guess is the
 * same disaster we are fixing.
 *
 * Script is different: it is decidable from the characters themselves with no
 * model and no ambiguity, so scripts that identify a language (Han, Kana,
 * Hangul, Cyrillic, Greek, Thai, Hebrew, Arabic, Devanagari) are honoured
 * first. For Latin script — where es/de/fr/hr/pt/… are mutually
 * indistinguishable without a model — the best available evidence is the
 * language the user chose for the app, which is also what they are almost
 * certainly reading and conversing in.
 *
 * Everything here is pure: no Android types, so it is unit-testable.
 */
object TtsLocaleResolver {

    /**
     * Scripts that identify a language on sight. Order matters only in that
     * each range is disjoint, so the first match wins unambiguously.
     *
     * Deliberately NOT here: Latin (shared by ~40 of the world's languages and
     * by most of what this app ships) and Han-vs-Kana overlap, handled below.
     */
    private val HAN = Regex("[\\u4e00-\\u9fff\\u3400-\\u4dbf]")

    /** Hiragana + Katakana. Japanese text almost always contains some. */
    private val KANA = Regex("[\\u3040-\\u309f\\u30a0-\\u30ff]")

    private val HANGUL = Regex("[\\uac00-\\ud7af\\u1100-\\u11ff\\u3130-\\u318f]")
    private val CYRILLIC = Regex("[\\u0400-\\u04ff]")
    private val GREEK = Regex("[\\u0370-\\u03ff]")
    private val THAI = Regex("[\\u0e00-\\u0e7f]")
    private val HEBREW = Regex("[\\u0590-\\u05ff]")
    private val ARABIC = Regex("[\\u0600-\\u06ff]")
    private val DEVANAGARI = Regex("[\\u0900-\\u097f]")

    /**
     * Resolve the locale for [text].
     *
     * @param appLanguageTag the user's saved in-app language (the value the
     *   Appearance screen writes to `KEY_LANGUAGE`), or null/blank for
     *   "follow system".
     * @param systemLocale the device default, used when the app follows the
     *   system.
     *
     * Resolution order ([T-android-tts-locale-priority], set by the user):
     *   1. the app's UI language — the default, and the tier the user asked to
     *      be authoritative;
     *   2. a language-identifying SCRIPT in the text (zh / ja / ko / ru / …),
     *      consulted only when no app language is set;
     *   3. the system locale;
     *   4. [Locale.US] as the final backstop — reachable, but no longer the
     *      default for four-fifths of the world.
     *
     * A tier ABOVE all of these lives in [TextToSpeechManager]: an explicitly
     * chosen voice (`preferredVoiceName`) or an explicit `setLanguage` call
     * wins outright and never reaches this resolver.
     */
    fun resolve(
        text: String,
        appLanguageTag: String?,
        systemLocale: Locale = Locale.getDefault(),
    ): Locale {
        // [T-android-tts-locale-priority] App UI language FIRST.
        //
        // The user's product rule: the default speaking language follows the
        // language they picked for the app. Switch the UI to Spanish and
        // replies are spoken in Spanish; switch to Croatian and they are
        // spoken in Croatian — without having to find a second, separate
        // voice setting.
        parseTag(appLanguageTag)?.let { return it }

        // Script detection is the FALLBACK, not the default. It applies only
        // when no app language is set (follow-system). It still earns its
        // place: it is what keeps Chinese sounding like Chinese for a user on
        // an English UI — the one case the original zh/US branch got right,
        // and which deleting this tier would regress.
        scriptLocale(text)?.let { return it }

        // A system locale with no language at all (possible on odd ROMs) is
        // useless to the engine — fall through rather than hand it garbage.
        if (systemLocale.language.isNotBlank()) return systemLocale
        return Locale.US
    }

    /**
     * The locale implied by the text's script, or null when the script does
     * not identify one (Latin, digits, punctuation, emoji only).
     */
    fun scriptLocale(text: String): Locale? {
        if (text.isBlank()) return null
        // Kana BEFORE Han: Japanese mixes kanji with kana, so a text
        // containing both is Japanese, not Chinese. Testing Han first — as the
        // old code effectively did — would speak Japanese prose in Mandarin.
        if (KANA.containsMatchIn(text)) return Locale.JAPANESE
        if (HANGUL.containsMatchIn(text)) return Locale.KOREAN
        if (HAN.containsMatchIn(text)) return Locale.SIMPLIFIED_CHINESE
        if (CYRILLIC.containsMatchIn(text)) return Locale("ru")
        if (GREEK.containsMatchIn(text)) return Locale("el")
        if (THAI.containsMatchIn(text)) return Locale("th")
        if (HEBREW.containsMatchIn(text)) return Locale("he")
        if (ARABIC.containsMatchIn(text)) return Locale("ar")
        if (DEVANAGARI.containsMatchIn(text)) return Locale("hi")
        return null
    }

    /**
     * Parse a tag the way the Appearance picker stores it.
     *
     * Mirrors `LocaleWrap.parseLocale`: bare codes (`es`, `de`) go through the
     * `Locale(code)` constructor, anything with a separator (`zh-Hant`,
     * `pt-BR`) through `forLanguageTag`.
     *
     * Indonesian is deliberately NOT special-cased. Android normalises the tag
     * to the legacy `in` internally while the JVM reports `id` (and maps
     * `Locale("in")` back to `id`) — verified, not assumed, after a test that
     * asserted one spelling failed on the other platform. Either code reaches
     * the same voice data, so forcing a spelling here would only encode a
     * platform difference that does not affect the outcome.
     */
    fun parseTag(tag: String?): Locale? {
        val code = tag?.trim().orEmpty()
        if (code.isEmpty()) return null
        return runCatching {
            if ('-' in code || '_' in code) {
                Locale.forLanguageTag(code.replace('_', '-'))
            } else {
                Locale(code)
            }
        }.getOrNull()?.takeIf { it.language.isNotBlank() }
    }

    /**
     * Progressively less specific candidates to hand the engine, most specific
     * first.
     *
     * A device may have `es` voice data but not `es-MX`, or `pt` but not
     * `pt-BR`. Trying the region-qualified form and then the bare language is
     * what turns "no exact match" into "close enough voice" instead of the
     * silent fallback to whatever was set last — which, before this change,
     * meant English.
     */
    fun candidates(locale: Locale): List<Locale> {
        val out = LinkedHashSet<Locale>()
        out.add(locale)
        if (locale.country.isNotBlank() || locale.variant.isNotBlank()) {
            out.add(Locale(locale.language))
        }
        return out.toList()
    }
}
