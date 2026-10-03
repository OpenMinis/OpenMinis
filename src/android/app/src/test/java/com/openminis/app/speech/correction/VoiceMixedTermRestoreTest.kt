package com.openminis.app.speech.correction

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-voice-mixed-term-restore] Port of the iOS 8622ea275 tests
 * (CorrectionLanguageProfileTests, the three digest cases and the ratio case in
 * VoiceCorrectionTests, and the prompt wiring of VoiceMixedTermRestoreTests).
 *
 * The on-device report: "接下来再看看下一个 Issue" was recognised as "…下一个遗穴",
 * and the correction answered "…下一个议题" although "Issue" was on screen ten
 * times — nothing told the model the user mixes English into Chinese speech.
 */
class VoiceMixedTermRestoreTest {

    private val fakeRank: (String) -> Int? = { term ->
        if (term in setOf("今天", "可以", "我们", "hello")) 1 else null
    }
    /** Space / punctuation split: the fixtures are pre-segmented like iOS's. */
    private val words: (String) -> List<String> = { s -> s.split(Regex("[\\s。，、]+")).filter { it.isNotBlank() } }

    private fun digest(vararg msgs: Pair<CorrectionSourceMessage.Role, String>): String =
        CorrectionContextBuilder.build(msgs.map { CorrectionSourceMessage(it.first, it.second) }, words, fakeRank)
            .rareTermsDigest.orEmpty()

    // ── Language profile ─────────────────────────────────────────────────

    private fun profile(locale: String, t: String) = CorrectionLanguageProfile.resolve(locale, t)

    @Test fun `a Chinese transcript with an English term is Chinese`() {
        assertEquals(CorrectionLanguageProfile.CHINESE, profile("zh-CN", "接下来再看看下一个遗穴。"))
        assertEquals(CorrectionLanguageProfile.CHINESE, profile("zh-CN", "看看 Issue 399"))
    }

    @Test fun `script beats locale when they disagree`() {
        assertEquals(CorrectionLanguageProfile.WESTERN, profile("zh-CN", "please fix the get hub issue"))
        assertEquals(CorrectionLanguageProfile.CHINESE, profile("en-US", "看看这个"))
    }

    @Test fun `Japanese and Korean`() {
        assertEquals(CorrectionLanguageProfile.JAPANESE, profile("ja-JP", "次のイシューを見て"))
        // Kanji only: the script alone reads as Chinese, the locale says Japanese.
        assertEquals(CorrectionLanguageProfile.JAPANESE, profile("ja-JP", "機能確認"))
        assertEquals(CorrectionLanguageProfile.KOREAN, profile("ko-KR", "다음 이슈 보자"))
    }

    @Test fun `western languages and fallbacks`() {
        assertEquals(CorrectionLanguageProfile.WESTERN, profile("fr-FR", "regarde le problème"))
        assertEquals(CorrectionLanguageProfile.WESTERN, profile("ru-RU", "посмотри задачу"))
        assertEquals(CorrectionLanguageProfile.GENERIC, profile("ar-SA", "انظر إلى المشكلة"))
        assertEquals(CorrectionLanguageProfile.CHINESE, profile("yue-CN", "123"))
    }

    @Test fun `every profile has its own rules`() {
        val rules = CorrectionLanguageProfile.entries.map { it.failureModes }
        assertEquals(rules.size, rules.toSet().size)
        assertTrue(CorrectionLanguageProfile.CHINESE.failureModes.contains("\"遗穴\" for \"Issue\""))
        assertFalse(CorrectionLanguageProfile.WESTERN.failureModes.contains("遗穴"))
        assertTrue(CorrectionLanguageProfile.JAPANESE.failureModes.contains("katakana"))
        assertTrue(CorrectionLanguageProfile.KOREAN.failureModes.contains("Hangul"))
    }

    // ── Prompt ───────────────────────────────────────────────────────────

    @Test fun `the system prompt carries the setting, the language's failure modes and the no-translate rule`() {
        val p = LlmCorrectionStrategy.systemPrompt(CorrectionLanguageProfile.CHINESE)
        assertTrue(p.startsWith("You fix speech-recognition errors in transcribed text. Output ONLY the corrected text"))
        assertTrue(p.contains("the user is talking to an AI agent in a chat app by voice"))
        assertTrue(p.contains("\"遗穴\" for \"Issue\""))
        assertTrue(p.contains("never translate it into another language"))
        assertFalse(LlmCorrectionStrategy.systemPrompt(CorrectionLanguageProfile.WESTERN).contains("遗穴"))
    }

    @Test fun `the user prompt frames the transcript as a message to the AI and names the error kinds`() {
        val p = LlmCorrectionStrategy.buildPrompt("看看遗穴", emptyList(), ConversationContext.EMPTY, CorrectionLanguageProfile.CHINESE)
        assertTrue(p.contains("推断用户这句要发给 AI 的话的整体意图"))
        assertTrue(p.contains("英文请按参考信息中的原拼写还原，不要翻译成中文"))
        assertFalse("no longer homophones only", p.contains("同音字/识别错误"))
    }

    @Test fun `the live call and the dry run both pick the profile from the locale and transcript`() {
        val strategy = ProductionSources.read("speech/correction/CorrectionStrategy.kt")
        assertTrue(strategy.contains("val profile = CorrectionLanguageProfile.resolve(locale, transcript)"))
        assertTrue(strategy.contains("systemPrompt = systemPrompt(profile),"))
        assertFalse(strategy.contains("systemPrompt = SYSTEM_PROMPT"))
        val engine = ProductionSources.read("speech/correction/VoiceCorrectionEngine.kt")
        assertTrue(engine.contains("CorrectionLanguageProfile.resolve(locale, transcript)"))
    }

    // ── Rare-term digest ─────────────────────────────────────────────────

    @Test fun `a recurring plain Latin word reaches the digest ahead of a common-char word`() {
        val d = digest(
            CorrectionSourceMessage.Role.ASSISTANT to "下一个 Issue 是 闪退 问题。",
            CorrectionSourceMessage.Role.USER to "这个 Issue 怎么修",
            CorrectionSourceMessage.Role.ASSISTANT to "所有 Issue 都已经 闭环。",
        )
        assertTrue("recurring Latin word must reach the digest: $d", d.contains("Issue"))
        val issue = d.indexOf("Issue")
        val crash = d.indexOf("闪退")
        if (crash >= 0) assertTrue("Issue (50) ranks above 闪退 (40): $d", issue < crash)
    }

    @Test fun `an English chat does not promote ordinary words`() {
        val d = digest(
            CorrectionSourceMessage.Role.ASSISTANT to "open the file and check the code",
            CorrectionSourceMessage.Role.USER to "which file has the code",
            CorrectionSourceMessage.Role.ASSISTANT to "this file holds the code",
        )
        assertFalse("ordinary English words must stay out: $d", d.contains("file"))
    }

    @Test fun `a rare plain Latin word and function words stay out`() {
        val d = digest(
            CorrectionSourceMessage.Role.ASSISTANT to "the world and the world 今天 我们 看看",
            CorrectionSourceMessage.Role.USER to "the and the and 可以 看看 东西",
        )
        assertFalse("two occurrences are below the bar: $d", d.contains("world"))
        assertFalse("function words never promote: $d", d.contains("the"))
        assertFalse("function words never promote: $d", d.contains("and"))
    }

    // ── Change ratio ─────────────────────────────────────────────────────

    @Test fun `restoring a transliterated English term is one unit, not a rewrite`() {
        assertTrue(VoiceCorrectionDiff.charChangeRatio("看看遗穴", "看看Issue") <= VoiceCorrectionConfig.MAX_CHAR_CHANGE_RATIO)
        assertTrue(
            VoiceCorrectionDiff.charChangeRatio("接下来再看看下一个遗穴。", "接下来再看看下一个Issue。") <
                VoiceCorrectionConfig.MAX_CHAR_CHANGE_RATIO,
        )
        assertEquals(listOf("修", " ", "Issue", " ", "399"), VoiceCorrectionDiff.editUnits("修 Issue 399"))
    }
}
