package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A typed context-window override must survive live catalog re-enrichment.
 * Storing 1M only on baseModel was overwritten to 128K at use time.
 */
class ModelContextOverrideTest {

    @Test
    fun overrideBeatsLiveCatalogSnapshot() {
        val frozen = LLMModel(
            id = "some-local-llama",
            displayName = "llama",
            provider = "Custom",
            contextWindow = 128_000,
        )
        val entry = ModelEntry(
            providerInstanceId = "p",
            baseModel = frozen,
            overrides = ModelOverrides(contextWindow = 1_000_000),
        )
        assertEquals(1_000_000, entry.model.contextWindow)
        assertEquals(128_000, entry.baseModel.contextWindow)
    }
}
