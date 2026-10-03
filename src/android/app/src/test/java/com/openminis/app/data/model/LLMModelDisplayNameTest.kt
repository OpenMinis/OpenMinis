package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-modeldisplayname-wireup] Regression guard: LLMModel.modelDisplayName()
 * exists (mirrors iOS modelDisplayName(from:)) but was previously never called by
 * any vendor Models API. When a provider's /v1/models response omits a human
 * readable name field, the raw model id (e.g. "deepseek-v4-pro") leaked straight
 * into the UI instead of being formatted.
 *
 * [T-android-modeldisplayname-ios-parity] The formatter now reflows separators
 * into spaces, matching iOS `modelDisplayName(from:)` exactly. It previously
 * kept `-` inside a segment and joined `/` as " / ", so the same DeepSeek model
 * read "DeepSeek-Flash" on Android and "DeepSeek Flash" on iOS — a user-visible
 * split reported from the field. These cases pin the shared contract; any
 * divergence from iOS should fail here.
 */
class LLMModelDisplayNameTest {

    /** The exact ids DeepSeek's official /v1/models returns — it sends no
     *  `name` field, so both platforms fall back to this formatter. */
    @Test fun `brand names are rewritten to their canonical casing`() {
        assertEquals("DeepSeek V4 Pro", LLMModel.modelDisplayName("deepseek-v4-pro"))
        assertEquals("DeepSeek Flash", LLMModel.modelDisplayName("deepseek-flash"))
    }

    @Test fun `known uppercase acronyms are preserved`() {
        assertEquals("GPT 5.6 Sol", LLMModel.modelDisplayName("gpt-5.6-sol"))
    }

    @Test fun `plain hyphenated ids are title-cased`() {
        assertEquals("Gemini 3.5 Flash Lite", LLMModel.modelDisplayName("gemini-3.5-flash-lite"))
    }

    @Test fun `slash-namespaced ids are formatted per segment`() {
        assertEquals(
            "Kimi Kimi K2.6",
            LLMModel.modelDisplayName("kimi/kimi-k2.6"),
        )
    }

    /** iOS carries "xxl" in its acronym set; Android was missing it. */
    @Test fun `xxl is uppercased like the other acronyms`() {
        assertEquals("Flan T5 XXL", LLMModel.modelDisplayName("flan-t5-xxl"))
    }

    /** Collapsed separators must not leave double spaces. */
    @Test fun `repeated and mixed separators collapse cleanly`() {
        assertEquals("Org Model V2", LLMModel.modelDisplayName("org//model--v2"))
    }

    @Test fun `blank id returns blank`() {
        assertEquals("", LLMModel.modelDisplayName(""))
    }
}
