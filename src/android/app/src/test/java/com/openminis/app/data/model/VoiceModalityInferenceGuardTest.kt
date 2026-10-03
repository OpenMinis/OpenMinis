package com.openminis.app.data.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-asr-vendor-family-names] (GH#305) Several widely-used Chinese ASR models
 * never registered as voice models, so the voice-input picker never listed
 * them even though the provider served them.
 *
 * `inferDedicatedVoiceModality` matches an id/name against
 * `asrInferencePatterns` and returns the EXACT ASR shape (audio in → text out).
 * That list only held delimiter-bracketed forms of the literal string "asr"
 * (`-asr`, `asr-`, `_asr`, `asr_`) plus whisper / stt / transcribe. Families
 * named after the model ARCHITECTURE rather than the task matched nothing:
 *
 *   paraformer-realtime-v2   (Alibaba DashScope)  -> no match
 *   SenseVoiceSmall          (FunAudioLLM)        -> no match
 *
 * A non-match is not a loud failure. It falls through to the broad inference,
 * the model gets a general text shape, and the voice pickers — which gate on
 * the EXACT audio-input shape — simply never show it. Silent absence, which is
 * why it went unreported.
 *
 * Both halves of each assertion matter: an ASR model must resolve to audio IN,
 * and it must NOT be mistaken for TTS. A TTS verdict would file it in the wrong
 * picker and make it text-in/audio-out — the exact inverse of what it does.
 */
class VoiceModalityInferenceGuardTest {

    private fun model(id: String, displayName: String = id) =
        LLMModel(id = id, displayName = displayName, provider = "dashscope")
            .withInferredVoiceModality()

    private fun assertIsAsr(id: String, displayName: String = id) {
        val m = model(id, displayName)
        assertTrue("$id must be recognised as audio-IN (ASR)", m.hasAudioInput)
        assertFalse("$id must NOT be treated as TTS (audio-OUT)", m.hasAudioOutput)
    }

    // ── the five real model names from the issue ────────────────────────────

    @Test
    fun `qwen3-asr-flash is an ASR model`() = assertIsAsr("qwen3-asr-flash")

    @Test
    fun `fun-asr-flash is an ASR model`() = assertIsAsr("fun-asr-flash")

    @Test
    fun `paraformer-realtime-v2 is an ASR model and never a TTS one`() {
        // The load-bearing case: "paraformer" contains no "asr" substring at
        // all, so before the fix this matched nothing.
        assertIsAsr("paraformer-realtime-v2")
    }

    @Test
    fun `SenseVoiceSmall is an ASR model`() {
        // Mixed case on purpose — inference lowercases the haystack, and a
        // regression that dropped that would surface here rather than as a
        // model missing from the picker on a user's device.
        assertIsAsr("SenseVoiceSmall")
    }

    @Test
    fun `Qwen3-ASR-1_7B is an ASR model`() = assertIsAsr("Qwen3-ASR-1.7B")

    @Test
    fun `the inferred ASR shape is exactly audio-in text-out`() {
        // Not just "has audio in": the paired text side is mandatory, or the
        // model collides with the single-flag voice-template-seed shape that
        // launch cleanup removes by exact match.
        val m = model("paraformer-realtime-v2")
        assertEquals(listOf("audio"), m.inputModalities)
        assertEquals(listOf("text"), m.outputModalities)
        assertFalse("an inferred model must not look like a template seed", m.isVoiceTemplateSeedShape)
    }

    // ── the TTS side must stay intact ───────────────────────────────────────

    @Test
    fun `a real TTS model is still inferred as audio-out`() {
        // Guards the other direction: widening the ASR list must not swallow
        // TTS models. `cosyvoice` is a FunAudioLLM sibling of SenseVoice, so it
        // is the most plausible thing to over-match.
        val tts = model("cosyvoice-tts-v2")
        assertTrue(tts.hasAudioOutput)
        assertFalse(tts.hasAudioInput)
    }

    @Test
    fun `an ordinary chat model gains no voice modality`() {
        val chat = model("gpt-5.6-terra")
        assertFalse(chat.hasAudioInput)
        assertFalse(chat.hasAudioOutput)
    }

    // ── user override normalization (the second half of GH#305) ─────────────

    @Test
    fun `a manual audio_input override is normalized and actually takes effect`() {
        // The detail screen writes the suffixed OpenAI/OpenRouter spelling.
        // `effective*Modalities` normalizes, but the OVERRIDE branch did not,
        // so the stored "audio_input" never compared equal to the bare "audio"
        // every reader checks: the switch showed ON while the capability stayed
        // OFF. That is precisely the workaround a user reaches for when
        // inference misses their model, so it failing silently is worse than
        // the inference gap it was meant to paper over.
        val entry = ModelEntry(
            providerInstanceId = "inst-A",
            baseModel = LLMModel(id = "some-asr-box", displayName = "Some ASR Box", provider = "custom"),
            overrides = ModelOverrides(inputModalities = listOf("audio_input")),
        )
        assertTrue("a hand-set audio_input override must report audio IN", entry.model.hasAudioInput)
        assertEquals(listOf("audio"), entry.model.inputModalities)
    }

    @Test
    fun `an audio_output override normalizes too`() {
        val entry = ModelEntry(
            providerInstanceId = "inst-A",
            baseModel = LLMModel(id = "some-tts-box", displayName = "Some TTS Box", provider = "custom"),
            overrides = ModelOverrides(outputModalities = listOf("audio_output")),
        )
        assertTrue(entry.model.hasAudioOutput)
        assertEquals(listOf("audio"), entry.model.outputModalities)
    }

    @Test
    fun `an already-bare override is unchanged`() {
        // Normalization must be idempotent — the common case (a caller that
        // already wrote the bare name) must not be perturbed.
        val entry = ModelEntry(
            providerInstanceId = "inst-A",
            baseModel = LLMModel(id = "m", displayName = "m", provider = "custom"),
            overrides = ModelOverrides(inputModalities = listOf("audio", "text")),
        )
        assertEquals(listOf("audio", "text"), entry.model.inputModalities)
        assertTrue(entry.model.hasAudioInput)
    }

    // ── cross-platform drift guard ──────────────────────────────────────────

    @Test
    fun `the four vendor-family patterns are present in the shipping source`() {
        // Re-read the real file rather than trusting the behaviour above: the
        // assertions would still pass if someone re-added the names via some
        // other mechanism while deleting the patterns, and the point of this
        // list is that it stays byte-identical to iOS
        // LLMTypes.asrInferencePatterns.
        val src = File("src/main/java/com/openminis/app/data/model/VoiceModality.kt")
        assertTrue("missing ${src.absolutePath}", src.exists())
        val code = src.readText()
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        for (p in listOf("paraformer", "sensevoice", "fun-asr", "qwen-asr")) {
            assertTrue("asrInferencePatterns must contain \"$p\"", code.contains("\"$p\""))
        }
    }
}
