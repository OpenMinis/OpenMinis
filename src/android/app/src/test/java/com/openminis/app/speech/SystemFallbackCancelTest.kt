package com.openminis.app.speech

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-voice-system-fallback-cancel] The System recognition fallback
 * belongs to one take: a new take, a retry or a cancel supersedes it, and
 * anything it reports afterwards is dropped. The engine needs a real
 * SpeechRecognizer, so the wiring is pinned from source.
 */
class SystemFallbackCancelTest {

    private val src = ProductionSources.read("speech/ProviderSpeechRecognitionEngine.kt")

    @Test
    fun `every new take, retry and cancel supersedes the previous one`() {
        // start(), transcribeRetained() (retry) and cancel(), plus the definition.
        assertEquals(4, Regex("""supersedeTake\(\)""").findAll(src).count())
        val cancel = src.substringAfter("override fun cancel() {").substringBefore("\n    }")
        assertTrue(cancel.trimStart().startsWith("supersedeTake()"))
        assertTrue(src.contains("if (systemReplayGeneration.compareAndSet(old, -1))"))
        assertTrue(src.contains("systemEngine()?.cancel()"))
    }

    @Test
    fun `the System fallback reports only to its own take`() {
        val fn = src.substringAfter("private fun transcribeWithSystem(").substringBefore("private fun supersedeTake()")
        assertTrue(fn.contains("val gen = takeGeneration.get()"))
        // The caller's listener is never handed to the System engine directly.
        assertTrue(!fn.contains("system.transcribeRetained(wav, locale, listener)"))
        for (cb in listOf("onPartial", "onFinal", "onError", "onRetainedAudio")) {
            assertTrue("$cb is gated", Regex("""override fun $cb\([^)]*\)[^\n]*\{[^\n]*live\(""").containsMatchIn(fn) ||
                fn.substringAfter("override fun $cb(").substringBefore("override fun").contains("live("))
        }
    }
}
