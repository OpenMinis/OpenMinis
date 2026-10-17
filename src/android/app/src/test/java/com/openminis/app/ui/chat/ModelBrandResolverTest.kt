package com.openminis.app.ui.chat

import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-usage-topbar-brand-icon] Keyword-scoring matrix for the top-bar vendor
 * logo. The resolver asserts a brand from a model name — a misattributed
 * logo reads as a routing fact, so every edge below is a contract.
 */
class ModelBrandResolverTest {

    private fun resolve(modelId: String? = null, displayName: String? = null, type: ProviderType? = null) =
        ModelBrandResolver.resolve(type, modelId, displayName)

    // ── ProviderType authoritative mapping ────────────────────────────

    @Test
    fun providerTypeMappingWinsOverNameScoring() {
        assertEquals("claude", resolve(modelId = "gpt-5", type = ProviderType.anthropic))
        assertEquals("gemini", resolve(modelId = "gpt-5", type = ProviderType.gemini))
        assertEquals("openai", resolve(modelId = "claude-3", type = ProviderType.openAI))
        assertEquals("openai", resolve(modelId = "claude-3", type = ProviderType.openAIResponses))
        assertEquals("xai", resolve(modelId = "m2", type = ProviderType.xAI))
        assertEquals("kimi", resolve(modelId = "m2", type = ProviderType.kimiCode))
    }

    @Test
    fun compatTypesFallThroughToScoring() {
        // Relay/compat types carry no brand; the model name decides.
        assertEquals("zhipu", resolve(modelId = "glm-5.3", type = ProviderType.antigravity))
        assertEquals("zhipu", resolve(modelId = "glm-5.3", type = ProviderType.unsupported))
    }

    // ── Keyword scoring ───────────────────────────────────────────────

    @Test
    fun domesticBrandsResolve() {
        assertEquals("zhipu", resolve(modelId = "glm-5.3"))
        assertEquals("zhipu", resolve(modelId = "chatglm-4"))
        assertEquals("zhipu", resolve(displayName = "GLM-4.6"))
        assertEquals("deepseek", resolve(modelId = "deepseek-chat"))
        assertEquals("qwen", resolve(modelId = "qwen3-max"))
        assertEquals("qwen", resolve(modelId = "qwq-32b"))
        assertEquals("kimi", resolve(modelId = "kimi-k2"))
        assertEquals("moonshot", resolve(modelId = "moonshot-v1-8k"))
        assertEquals("doubao", resolve(modelId = "doubao-pro-32k"))
        assertEquals("hunyuan", resolve(modelId = "hunyuan-turbo"))
        assertEquals("wenxin", resolve(modelId = "ernie-4.5"))
        assertEquals("baichuan", resolve(modelId = "baichuan2-53b"))
        assertEquals("yi", resolve(modelId = "yi-34b-chat"))
        assertEquals("minimax", resolve(modelId = "minimax-m2"))
    }

    @Test
    fun catalogExpansionBrandsResolve() {
        // v6.0.0-catalog expansion (358 slugs): domestic newcomers + tools.
        assertEquals("hailuo", resolve(modelId = "hailuo-02"))
        assertEquals("jimeng", resolve(modelId = "jimeng-2.1"))
        assertEquals("kolors", resolve(modelId = "kolors-v2"))
        assertEquals("vidu", resolve(modelId = "vidu-q1"))
        assertEquals("stepfun", resolve(modelId = "stepfun-step3"))
        assertEquals("internlm", resolve(modelId = "internlm2.5-20b"))
        assertEquals("codegeex", resolve(modelId = "codegeex-4"))
        assertEquals("coze", resolve(modelId = "coze-loop"))
        assertEquals("nanobanana", resolve(modelId = "nano-banana-pro"))
        assertEquals("xuanyuan", resolve(modelId = "xuanyuan-70b"))
    }

    @Test
    fun shortSlugsAreExcludedFromScoring() {
        // "pi"/"v0"/"lg" (<=2 chars) are packed as icons but must NOT score:
        // a 2-char contains() hits unrelated ids. Unrecognized ids fall
        // back to the OpenAI mark, never to a 2-char slug's icon.
        assertEquals("openai", resolve(modelId = "pi-3.5-turbo"))
        assertEquals("openai", resolve(modelId = "lg-express"))
    }

    @Test
    fun overseasBrandsResolve() {
        assertEquals("claude", resolve(modelId = "claude-sonnet-4-5"))
        assertEquals("claude", resolve(modelId = "claude-opus-4-1"))
        assertEquals("claude", resolve(modelId = "claude-3-5-haiku"))
        assertEquals("openai", resolve(modelId = "gpt-5.2"))
        assertEquals("openai", resolve(modelId = "o3-mini"))
        assertEquals("openai", resolve(modelId = "chatgpt-4o-latest"))
        assertEquals("gemini", resolve(modelId = "gemini-2.5-pro"))
        assertEquals("gemini", resolve(modelId = "gemma-3-27b"))
        assertEquals("grok", resolve(modelId = "grok-4"))
        assertEquals("meta", resolve(modelId = "llama-3.3-70b"))
        assertEquals("mistral", resolve(modelId = "mistral-large"))
        assertEquals("mistral", resolve(modelId = "codestral-latest"))
        assertEquals("sora", resolve(modelId = "sora-2"))
    }

    @Test
    fun longestKeywordWins() {
        // "deepseek" (8 chars) must beat any shorter fragment that also hits.
        assertEquals("deepseek", resolve(modelId = "deepseek-reasoner"))
        // "moonshot" (8) beats "moon"-like fragments; kimi brand stays distinct.
        assertEquals("moonshot", resolve(modelId = "moonshot-v1"))
    }

    @Test
    fun displayNameIsASignal() {
        assertEquals("claude", resolve(modelId = "relay-x7", displayName = "Claude Sonnet 4.5"))
        // Unrecognized id with no display name → OpenAI fallback.
        assertEquals("openai", resolve(modelId = "relay-x7", displayName = null))
    }

    @Test
    fun noMatchFallsBackToOpenAI() {
        // Product decision: unrecognized non-empty references show the
        // OpenAI mark so the icon slot keeps a stable rhythm.
        assertEquals("openai", resolve(modelId = "custom-finetune-a"))
        assertEquals("openai", resolve(modelId = "relay-x7"))
        // No reference at all → no icon (empty row).
        assertNull(resolve(modelId = ""))
        assertNull(resolve(null, null))
    }

    @Test
    fun scoringIsCaseInsensitive() {
        assertEquals("zhipu", resolve(modelId = "GLM-5.3"))
        assertEquals("claude", resolve(displayName = "CLAUDE OPUS"))
    }

    // ── Resource mapping ──────────────────────────────────────────────

    @Test
    fun everyScoredSlugHasAPackedIcon() {
        // Every slug the keyword table can emit must map to a raw resource —
        // a scoring hit with no icon silently renders nothing, which is the
        // failure this test exists to catch at build time.
        val emittable = ModelBrandResolver::class.java
            .getDeclaredField("KEYWORDS")
            .apply { isAccessible = true }
            .let { field ->
                @Suppress("UNCHECKED_CAST")
                (field.get(null) as List<Pair<String, String>>).map { it.second }
            }.toSet()
        for (slug in emittable) {
            assertNotNull(
                "slug $slug scored by keywords but iconResId is null",
                ModelBrandResolver.iconResId(slug),
            )
        }
    }

    @Test
    fun unknownSlugHasNoIcon() {
        assertNull(ModelBrandResolver.iconResId("not-a-brand"))
    }
}
