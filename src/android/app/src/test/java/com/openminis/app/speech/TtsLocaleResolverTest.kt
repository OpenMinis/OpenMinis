package com.openminis.app.speech

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-tts-locale GH#324] The TTS voice must stop being pinned to US
 * English for every non-Chinese language.
 *
 * The reported failure: Spanish replies were read aloud by an American English
 * voice, which is not an accent but unintelligible. The old rule was literally
 * `if (hasHan) zh-CN else Locale.US`, so es/de/fr/hr/ru/ja/ko all landed on
 * English — and no amount of UI localisation could change it, because nothing
 * on that path consulted the app's language at all.
 */
class TtsLocaleResolverTest {

    // ---- the reported bug -------------------------------------------------

    /**
     * THE regression test for GH#324. Under the old code this returned
     * Locale.US no matter what the user had selected.
     */
    @Test
    fun `spanish text with the app in spanish is spoken in spanish`() {
        val l = TtsLocaleResolver.resolve("Hola, ¿cómo estás hoy?", appLanguageTag = "es")
        assertEquals("es", l.language)
    }

    /** The same for the other Latin-script languages the app ships. */
    @Test
    fun `other latin-script app languages are honoured`() {
        val cases = mapOf(
            "de" to "Guten Morgen, wie geht es dir?",
            "fr" to "Bonjour, comment allez-vous ?",
            "hr" to "Dobro jutro, kako ste?",
            "pl" to "Dzień dobry, jak się masz?",
            "tr" to "Günaydın, nasılsın?",
            "ro" to "Bună dimineața, ce mai faci?",
            "ms" to "Selamat pagi, apa khabar?",
            "fil" to "Magandang umaga, kumusta ka?",
        )
        for ((tag, text) in cases) {
            assertEquals(
                "text in $tag must not be spoken as English",
                tag,
                TtsLocaleResolver.resolve(text, appLanguageTag = tag).language,
            )
        }
    }

    /** Region-qualified tags keep their region so pt-BR gets a Brazilian voice. */
    @Test
    fun `region qualified app language keeps its region`() {
        val l = TtsLocaleResolver.resolve("Bom dia, tudo bem?", appLanguageTag = "pt-BR")
        assertEquals("pt", l.language)
        assertEquals("BR", l.country)
    }

    // ---- script detection -------------------------------------------------

    /**
     * [T-android-tts-locale-priority] The app language is AUTHORITATIVE, so a
     * Chinese quotation inside a Spanish-UI session is spoken in Spanish.
     *
     * This assertion is deliberately the inverse of what this test file said
     * before: the first implementation put script detection on top, which meant
     * one Chinese word could flip the voice for a user who had explicitly
     * chosen Spanish. The user's rule is that their chosen UI language decides
     * the default, and this pins it.
     */
    @Test
    fun `the app language wins over the text's script`() {
        val l = TtsLocaleResolver.resolve("你好，今天过得怎么样？", appLanguageTag = "es")
        assertEquals("es", l.language)
    }

    /**
     * Script detection survives as a FALLBACK: with no app language chosen
     * (follow-system) and an unusable system locale, Chinese text is still
     * spoken in Chinese rather than English. Deleting this tier would regress
     * the one case the original zh/US branch got right.
     */
    @Test
    fun `script still decides when no app language is set`() {
        val l = TtsLocaleResolver.resolve(
            "你好，今天过得怎么样？",
            appLanguageTag = null,
            systemLocale = Locale(""),
        )
        assertEquals("zh", l.language)
    }

    /**
     * Japanese mixes kanji with kana. Testing Han first — as the old code
     * effectively did — would speak Japanese prose in Mandarin, so kana is
     * checked first and this pins that ordering.
     */
    @Test
    fun `japanese with kanji is japanese, not chinese`() {
        // appLanguageTag = null so the SCRIPT tier is the one under test.
        assertEquals("ja", TtsLocaleResolver.resolve("今日はいい天気ですね", null).language)
        assertEquals("ja", TtsLocaleResolver.resolve("カタカナのテスト", null).language)
    }

    @Test
    fun `korean, russian, greek, thai, hebrew, arabic and hindi are detected`() {
        val cases = mapOf(
            "ko" to "안녕하세요, 오늘 어떠세요?",
            "ru" to "Привет, как дела?",
            "el" to "Γεια σου, τι κάνεις;",
            "th" to "สวัสดีครับ สบายดีไหม",
            "he" to "שלום, מה שלומך?",
            "ar" to "مرحبا، كيف حالك؟",
            "hi" to "नमस्ते, आप कैसे हैं?",
        )
        for ((lang, text) in cases) {
            assertEquals(text, lang, TtsLocaleResolver.resolve(text, null).language)
        }
    }

    /**
     * [T-android-tts-locale-priority] Same rule for every language pair: a
     * user on a German UI hears German, whatever script the reply happens to
     * contain.
     */
    @Test
    fun `a german ui speaks german even for non-latin text`() {
        assertEquals("de", TtsLocaleResolver.resolve("这是中文", appLanguageTag = "de").language)
    }

    /**
     * The full chain in one test, in the order the user specified:
     * app language > (script) > system > Locale.US.
     */
    @Test
    fun `the documented priority chain holds end to end`() {
        // app language beats both script and system
        assertEquals(
            "hr",
            TtsLocaleResolver.resolve("你好", appLanguageTag = "hr", systemLocale = Locale.JAPAN).language,
        )
        // no app language -> script
        assertEquals(
            "ko",
            TtsLocaleResolver.resolve("안녕하세요", appLanguageTag = "", systemLocale = Locale("")).language,
        )
        // no app language, no script -> system
        assertEquals(
            "fr",
            TtsLocaleResolver.resolve("Bonjour", appLanguageTag = "", systemLocale = Locale.FRANCE).language,
        )
        // nothing at all -> US
        assertEquals(
            Locale.US,
            TtsLocaleResolver.resolve("Hello", appLanguageTag = null, systemLocale = Locale("")),
        )
    }

    /** Latin script identifies nothing on its own. */
    @Test
    fun `latin script yields no script locale`() {
        assertNull(TtsLocaleResolver.scriptLocale("Hola, buenos días"))
        assertNull(TtsLocaleResolver.scriptLocale("12345 !!! 🎉"))
        assertNull(TtsLocaleResolver.scriptLocale("   "))
    }

    // ---- fallback chain ---------------------------------------------------

    /** "Follow system" (empty tag) uses the system locale, not English. */
    @Test
    fun `following the system uses the system locale`() {
        val l = TtsLocaleResolver.resolve("Guten Tag", appLanguageTag = "", systemLocale = Locale.GERMANY)
        assertEquals("de", l.language)
    }

    /** Null tag behaves the same as empty. */
    @Test
    fun `a null app language falls through to the system`() {
        val l = TtsLocaleResolver.resolve("Bonjour", appLanguageTag = null, systemLocale = Locale.FRANCE)
        assertEquals("fr", l.language)
    }

    /** US English remains reachable — as a last resort, not as the default. */
    @Test
    fun `english is still reachable as the final fallback`() {
        assertEquals(
            "en",
            TtsLocaleResolver.resolve("Hello there", appLanguageTag = "en").language,
        )
        // A system locale with no language at all must not be handed to the engine.
        assertEquals(
            Locale.US,
            TtsLocaleResolver.resolve("Hello", appLanguageTag = null, systemLocale = Locale(""))
        )
    }

    // ---- tag parsing ------------------------------------------------------

    @Test
    fun `tags are parsed the way the picker stores them`() {
        assertEquals("es", TtsLocaleResolver.parseTag("es")?.language)
        assertEquals("zh", TtsLocaleResolver.parseTag("zh-Hant")?.language)
        assertEquals("Hant", TtsLocaleResolver.parseTag("zh-Hant")?.script)
        assertEquals("BR", TtsLocaleResolver.parseTag("pt-BR")?.country)
        assertNull(TtsLocaleResolver.parseTag(""))
        assertNull(TtsLocaleResolver.parseTag("   "))
        assertNull(TtsLocaleResolver.parseTag(null))
    }

    /**
     * Indonesian round-trips to SOMETHING the engine can use, whichever of the
     * two codes the platform prefers.
     *
     * This test originally asserted `"in"` — the legacy code Android
     * normalises to — and failed on the JVM, which reports `"id"` (and maps
     * `Locale("in")` to `id`, i.e. the opposite direction). The two platforms
     * genuinely disagree, so pinning either spelling would make the suite
     * assert a portability claim that is false on one of them. What actually
     * matters for TTS is that the tag parses to a usable language at all.
     */
    @Test
    fun `indonesian parses to a usable language on either platform`() {
        val l = TtsLocaleResolver.parseTag("id")
        assertEquals(true, l != null && l.language.isNotBlank())
        assertEquals(true, l!!.language == "id" || l.language == "in")
    }

    // ---- availability degradation ----------------------------------------

    /**
     * A device may have `pt` voice data but not `pt-BR`. Offering the bare
     * language as a second candidate is what turns "no exact match" into a
     * usable voice instead of silently keeping whatever was set last — which,
     * before this change, was English.
     */
    @Test
    fun `candidates degrade from region-qualified to bare language`() {
        assertEquals(
            listOf(Locale("pt", "BR"), Locale("pt")),
            TtsLocaleResolver.candidates(Locale("pt", "BR")),
        )
    }

    /** A bare language offers exactly one candidate — no pointless retry. */
    @Test
    fun `a bare language yields a single candidate`() {
        assertEquals(listOf(Locale("es")), TtsLocaleResolver.candidates(Locale("es")))
    }
}
