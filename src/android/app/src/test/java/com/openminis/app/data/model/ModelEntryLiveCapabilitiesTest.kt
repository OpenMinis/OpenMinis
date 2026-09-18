package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#340] A model entry's capabilities used to be a snapshot taken at add
 * time. `deepseek-flash` arrived as text-only and stayed that way even after
 * the catalog learned image input. `ModelEntry.model` now re-derives live
 * capabilities (and applies a known-id overlay when the bundled catalog is
 * still stale). Persistence (`baseModel`) is unchanged; user overrides still win.
 */
class ModelEntryLiveCapabilitiesTest {

    private fun frozenFlash(
        id: String = "deepseek-flash",
    ) = LLMModel(
        id = id,
        displayName = "DeepSeek Flash",
        provider = "Custom",
        inputModalities = listOf("text"),
        outputModalities = listOf("text"),
        contextWindow = 128_000,
        maxOutputTokens = 8_192,
        supportsReasoning = null,
    )

    @Test
    fun frozenDeepseekFlashGainsImageAnd1MContext() {
        val entry = ModelEntry(providerInstanceId = "p", baseModel = frozenFlash())
        assertTrue("frozen text-only snapshot must gain image input", entry.model.hasImageInput)
        assertEquals(1_000_000, entry.model.contextWindow)
        assertEquals(384_000, entry.model.maxOutputTokens)
        assertEquals(true, entry.model.supportsReasoning)
        assertFalse("persisted snapshot must stay frozen", entry.baseModel.hasImageInput)
        assertEquals(128_000, entry.baseModel.contextWindow)
    }

    @Test
    fun namespacedIdAlsoUpgrades() {
        val entry = ModelEntry(
            providerInstanceId = "p",
            baseModel = frozenFlash(id = "deepseek/deepseek-flash"),
        )
        assertTrue(entry.model.hasImageInput)
    }

    @Test
    fun aliasPrefixAlsoUpgrades() {
        val entry = ModelEntry(
            providerInstanceId = "p",
            baseModel = frozenFlash(id = "deepseek-flash-0731"),
        )
        assertTrue(entry.model.hasImageInput)
    }

    @Test
    fun userModalityOverrideStillWins() {
        val entry = ModelEntry(
            providerInstanceId = "p",
            baseModel = frozenFlash(),
            overrides = ModelOverrides(
                inputModalities = listOf("text"),
                outputModalities = listOf("text"),
            ),
        )
        assertFalse(
            "an explicit text-only override must not be upgraded to vision",
            entry.model.hasImageInput,
        )
    }

    @Test
    fun unrelatedModelIsUnchanged() {
        val base = LLMModel(
            id = "some-local-llama",
            displayName = "llama",
            provider = "Custom",
            inputModalities = listOf("text"),
            outputModalities = listOf("text"),
        )
        val entry = ModelEntry(providerInstanceId = "p", baseModel = base)
        assertFalse(entry.model.hasImageInput)
        assertEquals(listOf("text"), entry.model.inputModalities)
    }
}
