package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [GH#366] Android's formatter must match iOS `modelDisplayName(from:)`:
 * `/` and `-` become spaces. The old hyphen-preserving form produced
 * `DeepSeek-V4-Flash` next to the catalog's `DeepSeek V4 Flash` after an
 * iOS backup restore.
 */
class ModelDisplayNameTest {

    @Test
    fun deepseekFlashMatchesIosCatalogName() {
        assertEquals("DeepSeek V4 Flash", LLMModel.modelDisplayName("deepseek-v4-flash"))
        assertEquals("DeepSeek V4 Pro", LLMModel.modelDisplayName("deepseek-v4-pro"))
        assertEquals("DeepSeek Flash", LLMModel.modelDisplayName("deepseek-flash"))
    }

    @Test
    fun slashProviderPrefixBecomesSpaceNotSlash() {
        assertEquals("OpenAI GPT 4o Mini", LLMModel.modelDisplayName("openai/gpt-4o-mini"))
    }

    @Test
    fun blankIdIsUnchanged() {
        assertEquals("", LLMModel.modelDisplayName(""))
        assertEquals("   ", LLMModel.modelDisplayName("   "))
    }
}
