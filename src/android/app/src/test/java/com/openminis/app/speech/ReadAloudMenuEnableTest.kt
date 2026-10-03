package com.openminis.app.speech

import com.openminis.app.ProductionSources
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-readaloud-menu-enable] "Read Selection" / "Read from Start" were
 * silent with default settings: every utterance passes ReadAloudPlayer.enqueue,
 * which drops it unless VoiceOutputState.canPlay (read-replies ON, not muted),
 * and read-replies defaults to OFF. iOS switches it on and un-mutes before an
 * explicit read-aloud; Android now does the same.
 */
class ReadAloudMenuEnableTest {

    @After
    fun reset() {
        VoiceOutputState.setEnabled(false)
        VoiceOutputState.setMuted(false)
    }

    @Test
    fun `default settings cannot play - the reported state`() {
        VoiceOutputState.setEnabled(false)
        assertFalse(VoiceOutputState.canPlay)
    }

    @Test
    fun `an explicit read-aloud turns read-replies on`() {
        VoiceOutputState.setEnabled(false)
        VoiceOutputState.activateForExplicitReadAloud()
        assertTrue(VoiceOutputState.isEnabled.value)
        assertTrue(VoiceOutputState.canPlay)
    }

    @Test
    fun `an explicit read-aloud lifts a temporary mute`() {
        VoiceOutputState.setEnabled(true)
        VoiceOutputState.setMuted(true)
        assertFalse(VoiceOutputState.canPlay)
        VoiceOutputState.activateForExplicitReadAloud()
        assertFalse(VoiceOutputState.isMuted.value)
        assertTrue(VoiceOutputState.canPlay)
    }

    @Test
    fun `a long reply is split into pieces the system engine accepts`() {
        // [T-android-readaloud-split] "Read from Start" hands over a whole
        // reply. As one utterance, anything over getMaxSpeechInputLength()
        // (~4000 chars) was refused and nothing played.
        val sentence = "这是一段用来测试朗读切分的句子，包含一些逗号和标点。"
        val reply = sentence.repeat(600) // ~15k chars, far over the engine limit
        val buf = StringBuilder(reply)
        val pieces = SpeechSentenceSplitter.extractCompleteSentences(buf, streaming = false) +
            listOfNotNull(buf.toString().trim().ifEmpty { null })
        assertTrue("split into many utterances", pieces.size > 100)
        assertTrue("every piece is well under the engine limit", pieces.all { it.length < 4000 })
        assertTrue(
            "nothing is lost",
            pieces.joinToString("").replace(" ", "") == reply.replace(" ", ""),
        )
    }

    @Test
    fun `speak queues sentence by sentence, not one utterance`() {
        val src = ProductionSources.read("speech/ReadAloudPlayer.kt")
        val body = src.substring(src.indexOf("fun speak(text: String)")).substringBefore("\n    }")
        assertTrue(body.contains("sentenceBuffer.append(text)"))
        assertTrue(body.contains("flush()"))
        assertFalse("no single whole-text utterance", body.contains("enqueue(text)"))
    }

    @Test
    fun `the menu speaker activates before it speaks`() {
        // Source guard: the activation only helps if it runs BEFORE the player
        // enqueues (and drops) the text.
        val src = ProductionSources.read("ui/chat/LazyReadAloudPlayer.kt")
        val body = src.substring(src.indexOf("fun speak(text: String)"))
        val activate = body.indexOf("VoiceOutputState.activateForExplicitReadAloud()")
        val speak = body.indexOf("p.speak(text)")
        assertTrue("speak() must activate read-aloud", activate > 0)
        assertTrue("activation must precede p.speak", activate in 1 until speak)
    }
}
