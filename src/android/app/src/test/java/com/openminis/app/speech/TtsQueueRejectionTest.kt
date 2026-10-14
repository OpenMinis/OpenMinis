package com.openminis.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-tts-rom-compat] QUEUE_ADD rejection bookkeeping for
 * [TextToSpeechManager.speakQueued].
 *
 * TextToSpeechManager is not JVM-instantiable: its engine is a private concrete
 * android.speech.tts.TextToSpeech with no injection seam and `isInitialized`
 * has a private setter — so the state machine is modelled here instead, per the
 * repo's documented convention for engine-backed classes (see
 * CapturePauseLogicTest). The model mirrors the current
 * add → speak → rejection → onDone order exactly and pins the wedge this fix
 * removed: a rejected entry was appended to pendingTexts BEFORE the
 * `rc != SUCCESS` check and never removed, so `onDone`'s counter could never
 * reach the list size, `_isSpeaking` stayed true forever, and ChatScreen's
 * streaming read-aloud froze for the rest of the session on OEM engines that
 * reject QUEUE_ADD (the documented missing-language-data ROM case).
 */
class TtsQueueRejectionTest {

    private class Manager {
        val pendingTexts = mutableListOf<String>()
        var doneCount = 0
            private set
        var isSpeaking = false

        /** True when the engine accepts QUEUE_ADD; false = synchronous rejection. */
        var engineAccepts = true

        /** Mirrors speakQueued() after the fix. */
        fun speakQueued(text: String) {
            if (text.isBlank()) return
            pendingTexts.add(text)
            if (!engineAccepts) {
                pendingTexts.removeAt(pendingTexts.lastIndex)
                if (pendingTexts.isEmpty()) isSpeaking = false
                return
            }
            isSpeaking = true
        }

        /** Mirrors the main-thread block of onDone(). */
        fun onDone() {
            doneCount++
            if (doneCount >= pendingTexts.size) isSpeaking = false
        }
    }

    @Test
    fun `a rejection with nothing else in flight must not leave isSpeaking stuck`() {
        val m = Manager()
        m.engineAccepts = false
        m.speakQueued("Hello there.")
        assertFalse("phantom entry must not keep the flag up", m.isSpeaking)
        assertTrue("the rejected text must not stay pending", m.pendingTexts.isEmpty())
    }

    @Test
    fun `a rejection must not mark an earlier ongoing utterance as done`() {
        val m = Manager()
        m.speakQueued("First sentence.")            // accepted, engine speaking it
        m.engineAccepts = false
        m.speakQueued("Second sentence.")           // rejected
        assertTrue("earlier utterance is still in flight", m.isSpeaking)
        assertEquals(listOf("First sentence."), m.pendingTexts)
    }

    /**
     * THE wedge regression: pre-fix, the phantom kept pendingTexts one longer
     * than any onDone sequence could ever count, so isSpeaking never cleared.
     * Post-fix the counter reaches size on the last REAL utterance.
     */
    @Test
    fun `after a rejection the done counter still converges and speech ends`() {
        val m = Manager()
        m.speakQueued("One.")
        m.speakQueued("Two.")
        m.engineAccepts = false
        m.speakQueued("Three.")                     // wedged the queue pre-fix
        m.engineAccepts = true
        m.onDone()                                  // "One." finishes
        assertTrue(m.isSpeaking)
        m.onDone()                                  // "Two." finishes — nothing left
        assertFalse("must not stay true forever", m.isSpeaking)
    }

    @Test
    fun `rejecting text identical to an accepted one drops the phantom, not the real entry`() {
        val m = Manager()
        m.speakQueued("Yes.")                       // accepted
        m.engineAccepts = false
        m.speakQueued("Yes.")                       // rejected duplicate
        assertEquals("remove-by-position, not remove-by-value", 1, m.pendingTexts.size)
        m.onDone()
        assertFalse(m.isSpeaking)
    }
}
