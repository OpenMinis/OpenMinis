package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 2026-09-23 — a manual modality override must be able to switch a
 * capability OFF, not only on.
 *
 * Guards 414745155 (GH#305, T-android-modality-normalize-override) and the
 * surrounding voice-modality work (aff8058f5).
 *
 * [BUG] 414745155 changed `ModelEntry.model` from
 *     overrides.outputModalities ?: effOut
 * to
 *     overrides.outputModalities.normalizeModalities() ?: effOut
 * and `normalizeModalities()` maps an EMPTY list to null. The detail screen
 * (ModelEntryDetailScreen) writes only the ticked non-text modalities, so
 * turning off the last one saves `[]` — which now falls back to the inferred
 * value. A model inference mis-files as TTS/ASR (the exact case the new
 * "paraformer"/"sensevoice" patterns widen) can no longer be corrected: the
 * switch saves, then re-opens ON, and the voice pickers keep listing it.
 * Before 414745155, `[] ?: effOut` was `[]` and the override applied.
 *
 * Minimal fix: normalize without collapsing emptiness for overrides, e.g.
 *     overrides.outputModalities?.let { it.normalizeModalities() ?: emptyList() } ?: effOut
 */
class Review0923ModalityOverrideOffTest {

    private fun inferred(id: String) =
        LLMModel(id = id, displayName = id, provider = "custom").withInferredVoiceModality()

    @Test
    fun `precondition - the base models are inferred as voice models`() {
        assertTrue(inferred("cosyvoice-tts-v2").hasAudioOutput)
        assertTrue(inferred("paraformer-realtime-v2").hasAudioInput)
    }

    @Test
    fun `no override still tracks the inferred shape`() {
        val e = ModelEntry(providerInstanceId = "i", baseModel = inferred("cosyvoice-tts-v2"))
        assertTrue(e.model.hasAudioOutput)
    }

    @Test
    fun `turning audio output off on an inferred TTS model takes effect`() {
        val e = ModelEntry(
            providerInstanceId = "i",
            baseModel = inferred("cosyvoice-tts-v2"),
            overrides = ModelOverrides(outputModalities = emptyList()),
        )
        assertFalse(
            "an explicit empty output override must switch audio output OFF, not fall back to inference",
            e.model.hasAudioOutput,
        )
        assertFalse(e.model.isVoiceOutputCandidate)
    }

    @Test
    fun `turning every input modality off on an inferred ASR model takes effect`() {
        val e = ModelEntry(
            providerInstanceId = "i",
            baseModel = inferred("paraformer-realtime-v2"),
            overrides = ModelOverrides(inputModalities = emptyList()),
        )
        assertFalse(
            "an explicit empty input override must switch audio input OFF, not fall back to inference",
            e.model.hasAudioInput,
        )
        assertFalse(e.model.isVoiceInputCandidate)
    }

    @Test
    fun `suffixed overrides still normalize (the 414745155 fix itself)`() {
        val e = ModelEntry(
            providerInstanceId = "i",
            baseModel = LLMModel(id = "m", displayName = "m", provider = "custom"),
            overrides = ModelOverrides(outputModalities = listOf("audio_output", "audio")),
        )
        assertEquals(listOf("audio"), e.model.outputModalities)
    }
}
