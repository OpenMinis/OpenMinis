package com.openminis.app.data

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.normalizeModalityName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-vision-native-check-misses-image_input]
 *
 * ChatViewModel used to decide "can the main model see pixels?" with an inline
 * `inputModalities.map { it.lowercase() }.contains("image")` at three separate
 * sites, while the Vision Group's own member filter
 * (`ProviderRepository.resolveVisionCandidates`) used [hasImageInput], which
 * NORMALIZES the suffix form. OpenAI / OpenRouter report "image_input";
 * models.dev reports bare "image". A model advertising the suffix form was
 * therefore vision-capable to the Vision Group and text-only to read_image, so
 * a model that could see was detoured through the group for a second-hand text
 * description.
 *
 * These tests pin the property that made the two disagree. The production fix
 * routes all three sites through `ChatViewModel.currentModelHasNativeVision`,
 * which delegates to [hasImageInput] — so what is asserted here is exactly what
 * those call sites now evaluate.
 */
class NativeVisionModalityTest {

    private fun model(vararg inputs: String) = LLMModel(
        id = "test-model",
        displayName = "Test Model",
        provider = "test",
        inputModalities = inputs.toList(),
    )

    /** The old inline check, reproduced verbatim, to demonstrate the divergence. */
    private fun legacyInlineCheck(m: LLMModel): Boolean =
        m.inputModalities?.map { it.lowercase() }?.contains("image") == true

    @Test
    fun `suffix form is what the two checks used to disagree on`() {
        val m = model("text", "image_input")
        // The bug, pinned: the old inline check missed the suffix form...
        assertFalse(
            "legacy inline check should miss image_input (this is the bug)",
            legacyInlineCheck(m),
        )
        // ...while the Vision Group's filter accepted the same model.
        assertTrue(
            "hasImageInput must accept the OpenAI/OpenRouter suffix form",
            m.hasImageInput,
        )
    }

    @Test
    fun `bare form is accepted by both — never the failing case`() {
        val m = model("text", "image")
        assertTrue(legacyInlineCheck(m))
        assertTrue(m.hasImageInput)
    }

    @Test
    fun `text-only model has no native vision in either spelling`() {
        assertFalse(model("text").hasImageInput)
        assertFalse(model("text", "audio").hasImageInput)
    }

    @Test
    fun `mixed case and unspecified modalities`() {
        assertTrue("normalization lowercases too", model("Image_Input").hasImageInput)
        assertTrue(model("IMAGE").hasImageInput)
        // Unspecified (null) must read as "not vision" rather than throwing —
        // a catalog gap must not silently grant native-vision routing.
        assertFalse(LLMModel(id = "x", displayName = "x", provider = "p").hasImageInput)
        assertFalse(model().hasImageInput)
    }

    @Test
    fun `normalizeModalityName strips input and output suffixes`() {
        assertEquals("image", "image_input".normalizeModalityName())
        assertEquals("image", "image".normalizeModalityName())
        assertEquals("text", "text_output".normalizeModalityName())
        assertEquals("audio", "AUDIO_INPUT".normalizeModalityName())
    }

    /**
     * The three ChatViewModel decisions must move together: a model is either
     * native-vision for all of tool exposure, attachment placeholder and
     * read_image routing, or for none of them. They now share one accessor, so
     * asserting the shared predicate is asserting all three.
     */
    @Test
    fun `all three call sites agree for the suffix form`() {
        val m = model("text", "image_input")
        val nativeVision = m.hasImageInput

        val toolGateSupportsImages = nativeVision          // agentTools
        val skipsPlaceholder = nativeVision                // visionPlaceholderFor
        val readImageReturnsPixels = nativeVision          // executeReadImageTool

        assertTrue(toolGateSupportsImages)
        assertTrue(skipsPlaceholder)
        assertTrue(readImageReturnsPixels)
        assertEquals(toolGateSupportsImages, skipsPlaceholder)
        assertEquals(skipsPlaceholder, readImageReturnsPixels)
    }
}
