package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-usage-topbar-brand-icon] Keyword-scoring matrix for the top-bar vendor
 * logo. The resolver asserts a brand from a model name — a misattributed
 * logo reads as a routing fact, so every edge below is a contract.
 */
class ModelBrandResolverTest {

    /** Unrecognized non-empty references fall back to the OpenAI mark. */
    private val FALLBACK = ModelBrandResolver.FALLBACK_BRAND

    private fun resolve(modelId: String? = null, displayName: String? = null) =
        ModelBrandResolver.resolve(modelId, displayName)

    // ── Type independence (the relay bug) ─────────────────────────────

    /**
     * [T-brand-model-name-first] Regression for "picker shows only fallbacks":
     * the resolver used to take the provider type and short-circuit
     * `openAI -> openai` BEFORE scoring the model name, so a relay configured
     * as OpenAI painted its whole 22-model catalog with one logo. The type is
     * no longer an input at all — resolution depends on the model reference
     * alone, which is also why every call site now behaves identically.
     */
    @Test
    fun relayCatalogResolvesEachVendorIndependently() {
        // The exact ids from the reported relay catalog.
        assertEquals("zhipu", resolve("glm-5.3"))
        assertEquals("zhipu", resolve("GLM-5.3-Flash", "GLM-5.3-Flash"))
        assertEquals("kimi", resolve("Kimi-K2.6"))
        assertEquals("kimi", resolve("Kimi-K3"))
        assertEquals("minimax", resolve("minimax-m3"))
        assertEquals("deepseek", resolve("deepseek-v4.1-flash"))
        assertEquals("gemini", resolve("gemini-4-argon"))
        assertEquals("grok", resolve("grok-4.7"))
        assertEquals("openai", resolve("gpt-6-luna"))
    }

    /**
     * [T-brand-resolve-exact] Group-chat avatars need the scan WITHOUT the
     * OpenAI fallback: an unrecognized speaker must return null (→ initial
     * letter), while resolve() keeps filling the icon slot with the fallback
     * mark. Two entry points, two documented semantics, one keyword table.
     */
    @Test
    fun resolveExactReturnsNullForUnrecognizedButResolveFallsBack() {
        assertNull(ModelBrandResolver.resolveExact("custom-alias-7", null))
        assertEquals("openai", ModelBrandResolver.resolve("custom-alias-7", null))
        assertEquals("zhipu", ModelBrandResolver.resolveExact("glm-5.3"))
        assertNull(ModelBrandResolver.resolveExact(null, null))
        // Chinese aliases merged from the retired GroupChat table.
        assertEquals("zhipu", ModelBrandResolver.resolveExact(null, "智谱清言"))
        assertEquals("kimi", ModelBrandResolver.resolveExact(null, "月之暗面"))
    }

    /**
     * [T-brand-avatar-single-source] VendorMark must render the SAME
     * ModelBrandIcon the picker uses — the retired hand-drawn geometric glyphs
     * were a second truth source that drifted from the real logos. Guard
     * against reintroducing a private drawing path.
     */
    @Test
    fun vendorMarkRendersBrandIconsNotHandDrawnGlyphs() {
        val source = com.openminis.app.ProductionSources.read("ui/chat/VendorMark.kt")
        assertTrue(
            "VendorMark must delegate to ModelBrandIcon",
            source.contains("ModelBrandIcon("),
        )
        assertTrue(
            "VendorMark must NOT reintroduce hand-drawn vendor glyphs",
            !source.contains("vendorPaint") && !source.contains("VendorPaint"),
        )
    }

    /**
     * [T-brand-single-entry] GroupChat.vendorKey must delegate to
     * ModelBrandResolver.resolveExact — no second keyword table.
     */
    @Test
    fun groupChatVendorKeyDelegatesToTheResolver() {
        val source = com.openminis.app.ProductionSources.read("tools/GroupChat.kt")
        assertTrue(
            "vendorKey must call ModelBrandResolver.resolveExact",
            source.contains("ModelBrandResolver.resolveExact"),
        )
        assertTrue(
            "GroupChat must not keep a private vendor alias table",
            !source.contains("VENDOR_ALIASES"),
        )
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
    fun shortEnablementNamesHitTheirLongSlug() {
        // [T-brand-short-name] Products ship enablement names shorter than the
        // icon slug: a model called "agnes-2.0-flash" must still find
        // agnesai's logo, and hy3/hy4 are Hunyuan's compact naming.
        assertEquals("agnesai", resolve(modelId = "agnes-2.0-flash"))
        assertEquals("hunyuan", resolve(modelId = "hy3"))
        assertEquals("hunyuan", resolve(modelId = "hy4"))
        assertEquals("hunyuan", resolve(modelId = "hy3-preview"))
        assertEquals("vertexai", resolve(modelId = "vertex-llama-3.3-70b"))
        assertEquals("leptonai", resolve(modelId = "lepton-llama-3"))
    }

    @Test
    fun stepSeriesAndFriendsHitTheirIcon() {
        // [T-brand-short-name] Real enablement ids are shorter than the icon
        // slug: StepFun ships step-3/step-5-preview (not stepfun-*), Xiaomi
        // ships mimo-*, Aion ships aion-2.
        assertEquals("stepfun", resolve(modelId = "step-5-preview"))
        assertEquals("stepfun", resolve(modelId = "step-3"))
        assertEquals("stepfun", resolve(modelId = "step2"))
        assertEquals("stepfun", resolve(modelId = "step-1v-8k"))
        assertEquals("xiaomimimo", resolve(modelId = "mimo-v2-flash"))
        assertEquals("aionlabs", resolve(modelId = "aion-2.0"))
        assertEquals("kling", resolve(modelId = "kling-v2"))
    }

    @Test
    fun genericWordsNeverScoreAsBrands() {
        // [T-brand-generic-word-guard] Bare generic English words (magic,
        // sync, nova, flux, spark...) were in the keyword table straight
        // from the icon catalog and swallowed unrelated model ids. Every one
        // of them is now excluded; a brand that NEEDS such a word must use a
        // longer, unambiguous keyword (flux-1/flux-pro → bfl, nova-pro → nova).
        assertEquals(FALLBACK, resolve(modelId = "myth-7b"))
        assertEquals(FALLBACK, resolve(modelId = "hyphen-magic"))
        assertEquals(FALLBACK, resolve(modelId = "flux-dev"))
        assertEquals("bfl", resolve(modelId = "flux-pro"))
        assertEquals("nova", resolve(modelId = "nova-micro"))
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

    /**
     * [T-brand-icon-in-picker] The model picker used to draw a flat
     * provider-color dot, which encoded the relay's ProviderType only — every
     * OpenAI-typed relay showed the same green dot and told the user nothing
     * about the model. Guard both picker surfaces against regressing to a dot.
     */
    @Test
    fun modelPickerUsesBrandIconsNotColorDots() {
        val source = com.openminis.app.ProductionSources.read("ui/chat/ChatModelPickerSheet.kt")
        assertEquals(
            "ChatModelPickerSheet must render a brand icon per model row",
            3,
            Regex("ModelBrandIcon\\(").findAll(source).count(),
        )
        assertTrue(
            "ChatModelPickerSheet must NOT reintroduce the provider color dot",
            !source.contains("providerDotColor"),
        )
    }

    /**
     * [T-brand-single-entry] Every surface must call the ONE resolver entry with
     * the same shape. This bug shipped because three call sites assembled their
     * own arguments: the top bar passed null and worked, the picker passed the
     * provider type and produced nothing but fallbacks. A new call site that
     * reintroduces a per-site variant fails here.
     */
    @Test
    fun everyCallSiteUsesTheSingleEntryPoint() {
        val files = listOf(
            "ui/chat/ChatScreen.kt",
            "ui/chat/ChatModelPickerSheet.kt",
            "ui/settings/ProviderDetailScreen.kt",
        )
        for (f in files) {
            val source = com.openminis.app.ProductionSources.read(f)
            val calls = Regex("ModelBrandResolver\\.resolve\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL)
                .findAll(source).map { it.groupValues[1] }.toList()
            assertTrue("$f should resolve brand icons", calls.isNotEmpty())
            for (args in calls) {
                // resolve(modelId) or resolve(modelId, displayName) — never a
                // provider type in first position.
                assertTrue(
                    "$f passes an unexpected argument list: resolve(${args.trim()})",
                    !args.contains("providerType"),
                )
            }
        }
    }
}
