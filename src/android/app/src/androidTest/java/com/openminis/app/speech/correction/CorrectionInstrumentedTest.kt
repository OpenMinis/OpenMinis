package com.openminis.app.speech.correction

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** [T-android-voice-correction] Full-stack device coverage for Phases 1-3. */
@RunWith(AndroidJUnit4::class)
class CorrectionInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val seg: (String) -> List<String> = {
        com.openminis.app.shared.TextSegmenter.getInstance(ctx).segment(it)
    }
    private fun db() = VoiceCorrectionDb.getInstance(ctx)!!
    private fun recorder() = VoiceCorrectionRecorder(ctx, db(), seg)

    @Before fun reset() {
        VoiceCorrectionConsent.setEnabled(ctx, false)
        db().clearAll()
    }

    // --- phonetic keys must match iOS byte for byte ---
    @Test fun pinyinKeysMatchIos() {
        assertEquals("zhangsan", PinyinNormalizer.normalize("张三"))
        assertEquals("shitang", PinyinNormalizer.normalize("食堂"))
        assertEquals(PinyinNormalizer.normalize("张三"), PinyinNormalizer.normalize("章三"))
        assertTrue(PinyinNormalizer.normalize("iPhone手机").contains("iphone"))
        assertEquals("", PinyinNormalizer.normalize("  "))
    }

    // --- storage ---
    @Test fun repeatedUpsertFoldsAndBumpsFrequency() {
        val k = PinyinNormalizer.normalize("食堂")
        db().upsertConfusion(k, "石塘", "食堂", "zh", null, "")
        db().upsertConfusion(k, "十堂", "食堂", "zh", null, "")
        val rows = db().lookupConfusion(listOf(k), "zh", 0.0, 10)
        assertEquals(1, rows.size)
        assertEquals(2, rows[0].frequency)
        assertEquals(2, rows[0].variants.size)
    }

    @Test fun negativeFeedbackRetiresAPair() {
        val k = PinyinNormalizer.normalize("张三")
        db().upsertConfusion(k, "张山", "张三", "zh", null, "")
        repeat(3) { db().recordNegativeFeedback(k, "张三", "zh") }
        assertTrue(db().lookupConfusion(
            listOf(k), "zh", VoiceCorrectionConfig.MIN_CONFUSION_CONFIDENCE, 10).isEmpty())
    }

    @Test fun vocabularyFloorsApplyAtRetrieval() {
        val k = PinyinNormalizer.normalize("闪退")
        db().upsertVocabulary("闪退", k, "zh", "n", null, 1, "2026-07-19")
        assertTrue(db().lookupVocabulary(listOf(k), "zh", 10).isEmpty())
        db().upsertVocabulary("闪退", k, "zh", "n", null, 2, "2026-07-20")
        val rows = db().lookupVocabulary(listOf(k), "zh", 10)
        assertEquals(1, rows.size)
        assertEquals(2, rows[0].distinctDays)
    }

    // --- consent + capture (capabilities #2 and #3) ---
    @Test fun captureBlockedWithoutConsent() {
        recorder().recordTranscriptEdit("张山说今天有空", "张三说今天有空")
        recorder().recordTextInputEdit("石塘", "食堂")
        assertEquals(0, db().counts()["confusion_dictionary"])
    }

    @Test fun transcriptEditLearnsAfterConsent() {
        VoiceCorrectionConsent.setEnabled(ctx, true)
        recorder().recordTranscriptEdit("张山说今天有空", "张三说今天有空")
        val rows = db().lookupConfusion(
            listOf(PinyinNormalizer.normalize("张山")), "zh", 0.0, 10)
        assertTrue("got $rows", rows.isNotEmpty())
        assertEquals("张三", rows[0].correctedTerm)
    }

    @Test fun textInputEditLearnsAfterConsent() {
        VoiceCorrectionConsent.setEnabled(ctx, true)
        // Keep the edit local to a sentence: the admission policy deliberately
        // rejects a whole 2-character field as too_global (see
        // CorrectionAdmissionTest.`locality alone flips the verdict`). A
        // select-replace inside a sentence is the text-input path's real case.
        recorder().recordTextInputEdit("我们约在石塘见面", "我们约在食堂见面")
        val rows = db().lookupConfusion(
            listOf(PinyinNormalizer.normalize("石塘")), "zh", 0.0, 10)
        assertTrue("got $rows", rows.isNotEmpty())
        assertEquals("食堂", rows[0].correctedTerm)
    }

    @Test fun rewriteIsNotLearned() {
        VoiceCorrectionConsent.setEnabled(ctx, true)
        recorder().recordTextInputEdit("吃饭", "看电影")
        assertEquals(0, db().counts()["confusion_dictionary"])
    }

    @Test fun acceptedSuggestionIsMetricsOnly() {
        VoiceCorrectionConsent.setEnabled(ctx, true)
        recorder().recordSuggestionAccepted("张山说", "张三说", "张山→张三")
        assertEquals(1, db().counts()["correction_events"])
        assertEquals(0, db().counts()["confusion_dictionary"])
    }

    // --- retrieval end-to-end (needs ICU + jieba, so device only) ---
    @Test fun learnedPairIsRetrievedAsACandidate() {
        VoiceCorrectionConsent.setEnabled(ctx, true)
        recorder().recordTranscriptEdit("我们约在石塘见面", "我们约在食堂见面")
        val source = ConfusionDictionarySource(db(), minConfidence = 0.0)
        val engine = VoiceCorrectionEngine(
            listOf(source),
            object : CorrectionStrategy {
                override val strategyIdentifier = "noop"
                override suspend fun correct(
                    transcript: String, candidates: List<CorrectionCandidate>,
                    context: ConversationContext, locale: String,
                ) = CorrectionOutcome(transcript, "none", 0)
            },
            db(), seg, { true },
        )
        val candidates = kotlinx.coroutines.runBlocking {
            engine.retrieveCandidates("我们约在石塘见面", "zh")
        }
        android.util.Log.i("Ph5", "candidates=${candidates.map { it.term }}")
        assertTrue("expected 食堂 among $candidates", candidates.any { it.term == "食堂" })
    }

    @Test fun clearAllWipesEverything() {
        VoiceCorrectionConsent.setEnabled(ctx, true)
        recorder().recordTranscriptEdit("张山说今天有空", "张三说今天有空")
        db().clearAll()
        assertEquals(0, db().counts()["confusion_dictionary"])
        assertEquals(0, db().counts()["typed_vocabulary"])
        assertEquals(0, db().counts()["correction_events"])
    }

    @Test fun posTagAndBackgroundListAreAvailable() {
        val tagged = com.openminis.app.shared.TextSegmenter.getInstance(ctx)
            .posTag("马云在杭州创办了阿里巴巴集团")
        assertTrue(tagged.isNotEmpty())
        assertTrue(tagged.any { it.second in setOf("nr", "ns", "nz", "nt", "nrt") })
        assertTrue(BackgroundWordFrequency.getInstance(ctx).size > 100)
    }
}
