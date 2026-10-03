package com.openminis.app.provider

import com.openminis.app.provider.openai.CodexModelsApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-codex-dynamic-discovery GH#319] Parser contract for the Codex discovery
 * response.
 *
 * Scope, stated honestly: these drive [CodexModelsApi.parseModels] directly.
 * The HTTP call, the cache and the three-tier fallback chain live behind
 * `Context` / OkHttp and are not reachable from a plain JVM test — they were
 * exercised on-device instead (see the commit message). What IS covered here is
 * the part where a silent mistake is most likely and least visible: field
 * mapping, the visibility filter, and ordering.
 */
class CodexModelsApiTest {

    /** The GH#319 headline case: an id nobody compiled into the app. */
    @Test
    fun `a model absent from the built-in list is parsed`() {
        val models = CodexModelsApi.parseModels(
            """{"models":[{"slug":"gpt-7-nova","display_name":"GPT-7 Nova"}]}"""
        )
        assertEquals(1, models.size)
        assertEquals("gpt-7-nova", models[0].id)
        assertEquals("GPT-7 Nova", models[0].displayName)
    }

    /**
     * GH#319: "隐藏/不可见的模型条目保持隐藏". Both spellings are filtered —
     * the upstream reference accepts either, and shipping a hidden SKU into
     * the picker gives the user a model that errors on first send.
     */
    @Test
    fun `hidden models are dropped`() {
        val models = CodexModelsApi.parseModels(
            """{"models":[
                {"slug":"visible-one"},
                {"slug":"hidden-one","visibility":"hidden"},
                {"slug":"hide-one","visibility":"hide"},
                {"slug":"loud-case","visibility":"HIDDEN"}
            ]}"""
        )
        assertEquals(listOf("visible-one"), models.map { it.id })
    }

    /** `data` is accepted as an alias for `models`; neither present = empty. */
    @Test
    fun `data alias is accepted and an unknown shape yields empty`() {
        assertEquals(
            listOf("a"),
            CodexModelsApi.parseModels("""{"data":[{"slug":"a"}]}""").map { it.id },
        )
        assertTrue(CodexModelsApi.parseModels("""{"something_else":[]}""").isEmpty())
    }

    /** `id` stands in when `slug` is missing; an entry with neither is skipped. */
    @Test
    fun `id falls back for slug and an idless entry is skipped`() {
        val models = CodexModelsApi.parseModels(
            """{"models":[{"id":"from-id"},{"display_name":"nameless"},{"slug":"  "}]}"""
        )
        assertEquals(listOf("from-id"), models.map { it.id })
    }

    /** Display name defaults to the id rather than to an empty label. */
    @Test
    fun `missing display name falls back to the id`() {
        val models = CodexModelsApi.parseModels("""{"models":[{"slug":"bare-model"}]}""")
        assertEquals("bare-model", models[0].displayName)
    }

    @Test
    fun `context window and modalities are mapped`() {
        val models = CodexModelsApi.parseModels(
            """{"models":[{
                "slug":"m","context_window":400000,"max_output_tokens":100000,
                "input_modalities":["text","image"]
            }]}"""
        )
        assertEquals(400_000, models[0].contextWindow)
        assertEquals(100_000, models[0].maxOutputTokens)
        assertEquals(listOf("text", "image"), models[0].inputModalities)
    }

    /**
     * A zero / negative window is treated as absent, not as a real answer —
     * otherwise enrichment could not fill the gap and the UI would show a
     * 0-token context.
     */
    @Test
    fun `nonpositive context window is treated as unknown`() {
        val models = CodexModelsApi.parseModels("""{"models":[{"slug":"m","context_window":0}]}""")
        assertNull(models[0].contextWindow)
    }

    /**
     * Reasoning is TRUE if either signal says so. The two-signal check matters:
     * a model can default to no reasoning while still accepting it.
     */
    @Test
    fun `reasoning is detected from either signal`() {
        val fromDefault = CodexModelsApi.parseModels(
            """{"models":[{"slug":"m","default_reasoning_level":"medium"}]}"""
        )
        assertEquals(true, fromDefault[0].supportsReasoning)

        val fromLevels = CodexModelsApi.parseModels(
            """{"models":[{"slug":"m","default_reasoning_level":"none",
                "supported_reasoning_levels":[{"effort":"none"},{"effort":"high"}]}]}"""
        )
        assertEquals(true, fromLevels[0].supportsReasoning)
    }

    /**
     * Silence is not "no". A payload that says nothing about reasoning must
     * leave the field null so models.dev enrichment can still answer; pinning
     * `false` would disable the Thinking pill for a model that reasons.
     */
    @Test
    fun `silence about reasoning stays unknown but an explicit none is false`() {
        val silent = CodexModelsApi.parseModels("""{"models":[{"slug":"m"}]}""")
        assertNull(silent[0].supportsReasoning)

        val explicit = CodexModelsApi.parseModels(
            """{"models":[{"slug":"m","default_reasoning_level":"none"}]}"""
        )
        assertEquals(false, explicit[0].supportsReasoning)
    }

    /** Effort tiers keep backend order, drop "none", and dedupe. */
    @Test
    fun `effort tiers are extracted in order`() {
        val models = CodexModelsApi.parseModels(
            """{"models":[{"slug":"m","supported_reasoning_levels":[
                {"effort":"none"},{"effort":"low"},{"effort":"high"},{"effort":"low"}
            ]}]}"""
        )
        assertEquals(listOf("low", "high"), models[0].reasoningEffortValues)
    }

    /** Some rows carry bare strings instead of `{effort:…}` objects. */
    @Test
    fun `string reasoning levels are accepted`() {
        val models = CodexModelsApi.parseModels(
            """{"models":[{"slug":"m","supported_reasoning_levels":["low","high"]}]}"""
        )
        assertEquals(listOf("low", "high"), models[0].reasoningEffortValues)
        assertEquals(true, models[0].supportsReasoning)
    }

    /**
     * Backend priority ascending, id as the tiebreak. The tiebreak is the point:
     * without it two equal-priority models could swap places between refreshes
     * and the picker would reshuffle for no reason.
     */
    @Test
    fun `models are ordered by priority then id`() {
        val models = CodexModelsApi.parseModels(
            """{"models":[
                {"slug":"z-low-pri","priority":10},
                {"slug":"b-top","priority":1},
                {"slug":"a-top","priority":1},
                {"slug":"no-pri"}
            ]}"""
        )
        assertEquals(listOf("a-top", "b-top", "z-low-pri", "no-pri"), models.map { it.id })
    }

    /** A malformed element must not take the whole response down with it. */
    @Test
    fun `a junk element does not discard its siblings`() {
        val models = CodexModelsApi.parseModels(
            """{"models":["not-an-object",{"slug":"survivor"},42]}"""
        )
        assertEquals(listOf("survivor"), models.map { it.id })
    }

    /**
     * GH#319 requirement 3: "不要用过期的 enrichment 数据覆盖来自权威端点的新鲜
     * 元数据".
     *
     * This is the case that catches the obvious implementation — calling
     * `ModelsDevApi.enrichModels` and returning it, whose `applyDevData`
     * resolves every field as `devModel.x ?: model.x`, i.e. the CATALOG wins.
     * Here models.dev is made to disagree on every field at once (a stale 272k
     * window for a model the backend has since widened to 1M is the real-world
     * shape of this), and the authoritative answer must win each time.
     *
     * It passes the enriched list in explicitly rather than going through
     * [CodexModelsApi.enrichPreservingFresh]: models.dev's registry needs an
     * Android Context, so on the JVM enrichment is a no-op and a test written
     * the natural way would pass with the guard deleted.
     */
    @Test
    fun `authoritative fields beat a disagreeing catalog`() {
        val fresh = CodexModelsApi.parseModels(
            """{"models":[{
                "slug":"gpt-5.6-luna","display_name":"GPT-5.6 Luna",
                "context_window":1000000,"max_output_tokens":128000,
                "input_modalities":["text","image"],
                "supported_reasoning_levels":[{"effort":"high"}]
            }]}"""
        )
        // What a lagging catalog would say — wrong on every field.
        val stale = listOf(
            fresh[0].copy(
                contextWindow = 272_000,
                maxOutputTokens = 64_000,
                supportsReasoning = false,
                inputModalities = listOf("text"),
                reasoningEffortValues = listOf("low"),
            )
        )

        val out = CodexModelsApi.restoreAuthoritative(fresh, stale)

        assertEquals(1_000_000, out[0].contextWindow)
        assertEquals(128_000, out[0].maxOutputTokens)
        assertEquals(true, out[0].supportsReasoning)
        assertEquals(listOf("text", "image"), out[0].inputModalities)
        assertEquals(listOf("high"), out[0].reasoningEffortValues)
    }

    /**
     * The capacity exception, and the one regression this feature actually
     * caused before it was caught.
     *
     * Refreshing a live ChatGPT account moved every 5.6/6-family model from a
     * 1,050,000 context window down to 272,000, because the backend's registry
     * still reports the pre-1M number. So capacity takes the LARGER of the two
     * sources rather than the authoritative one — an under-report costs the
     * user usable context, while an over-report is bounded by the server
     * refusing the request.
     */
    @Test
    fun `a stale backend capacity cannot shrink the catalog value`() {
        val fresh = CodexModelsApi.parseModels(
            """{"models":[{"slug":"gpt-5.6-luna","context_window":272000,"max_output_tokens":64000}]}"""
        )
        val catalog = listOf(fresh[0].copy(contextWindow = 1_050_000, maxOutputTokens = 128_000))

        val out = CodexModelsApi.restoreAuthoritative(fresh, catalog)

        assertEquals(1_050_000, out[0].contextWindow)
        assertEquals(128_000, out[0].maxOutputTokens)
    }

    /** …and symmetrically, a stale CATALOG cannot shrink the backend's value. */
    @Test
    fun `a stale catalog capacity cannot shrink the backend value`() {
        val fresh = CodexModelsApi.parseModels(
            """{"models":[{"slug":"m","context_window":1000000}]}"""
        )
        val catalog = listOf(fresh[0].copy(contextWindow = 272_000))
        assertEquals(1_000_000, CodexModelsApi.restoreAuthoritative(fresh, catalog)[0].contextWindow)
    }

    /**
     * The other on-device regression: discovery renamed "GPT-6 Astra" to
     * "GPT-6-Astra". The backend's display_name is slug-derived, so for models
     * we already ship it is strictly worse — and renaming what the user has
     * been reading for months buys nothing.
     */
    @Test
    fun `a slug-derived rename of a known model is suppressed`() {
        val fresh = CodexModelsApi.parseModels(
            """{"models":[{"slug":"gpt-6-astra","display_name":"GPT-6-Astra"}]}"""
        )
        val out = CodexModelsApi.restoreAuthoritative(fresh, fresh)
        assertEquals("GPT-6 Astra", out[0].displayName)
    }

    /**
     * But a NEW model — the entire point of this feature, so by definition not
     * in the built-in list — keeps whatever the backend calls it. If this ever
     * failed, discovery would surface new models under their bare id.
     */
    @Test
    fun `a new model keeps the name the backend gave it`() {
        val fresh = CodexModelsApi.parseModels(
            """{"models":[{"slug":"gpt-7-nova","display_name":"GPT-7 Nova"}]}"""
        )
        assertEquals("GPT-7 Nova", CodexModelsApi.restoreAuthoritative(fresh, fresh)[0].displayName)
    }

    /**
     * And a genuinely different name for a known model is NOT suppressed —
     * the guard keys on "differs only in punctuation/case", so a real rebrand
     * still comes through.
     */
    @Test
    fun `a substantive rename of a known model is respected`() {
        val fresh = CodexModelsApi.parseModels(
            """{"models":[{"slug":"gpt-6-astra","display_name":"GPT-6 Astra Turbo"}]}"""
        )
        assertEquals(
            "GPT-6 Astra Turbo",
            CodexModelsApi.restoreAuthoritative(fresh, fresh)[0].displayName,
        )
    }

    /**
     * The other half of the same rule: where the route said nothing, the
     * catalog's answer must survive. Without this, "authoritative wins" would
     * be indistinguishable from "never enrich at all", and the models.dev tier
     * would be dead weight.
     */
    @Test
    fun `catalog fills the gaps the route left`() {
        val fresh = CodexModelsApi.parseModels("""{"models":[{"slug":"m"}]}""")
        val enriched = listOf(
            fresh[0].copy(
                contextWindow = 200_000,
                maxOutputTokens = 32_000,
                supportsReasoning = true,
                inputModalities = listOf("text", "image"),
                reasoningEffortValues = listOf("low", "high"),
            )
        )

        val out = CodexModelsApi.restoreAuthoritative(fresh, enriched)

        assertEquals(200_000, out[0].contextWindow)
        assertEquals(32_000, out[0].maxOutputTokens)
        assertEquals(true, out[0].supportsReasoning)
        assertEquals(listOf("text", "image"), out[0].inputModalities)
        assertEquals(listOf("low", "high"), out[0].reasoningEffortValues)
    }

    /**
     * The discovery route describes inputs only. Deriving an output modality
     * from silence would mark every chat model as an image generator or strip
     * the real ones — so it stays null and the existing defaults apply.
     */
    @Test
    fun `output modalities are never invented`() {
        val models = CodexModelsApi.parseModels(
            """{"models":[{"slug":"m","input_modalities":["text"]}]}"""
        )
        assertNull(models[0].outputModalities)
    }

    /** Provider is stamped so models.dev lookup and the UI group correctly. */
    @Test
    fun `provider is OpenAI`() {
        val models = CodexModelsApi.parseModels("""{"models":[{"slug":"m"}]}""")
        assertEquals("OpenAI", models[0].provider)
        assertFalse(models[0].id.isEmpty())
    }
}

/**
 * [T-codex-dynamic-discovery GH#319] The image models are NOT part of the
 * discovery response — they are not chat SKUs — but they are routable on the
 * Codex OAuth path through OpenAIProvider's image_generation branch.
 *
 * So a SUCCESSFUL discovery is the dangerous case, not a failing one: replacing
 * the built-in list wholesale with the discovered chat models would silently
 * delete three working models from the picker. `discoverCodexModels` merges
 * `OpenAIModelsApi.codexImageModels()` back in, and this pins the two
 * properties that merge depends on.
 */
class CodexImageModelMergeTest {

    /**
     * The image ids must match the set OpenAIProvider routes through the Codex
     * image path. If someone adds a fourth image model to one place and not the
     * other, the picker and the router disagree — the model appears and then
     * fails on send, or works but is invisible.
     */
    @Test
    fun `image models are the three codex image SKUs`() {
        val ids = com.openminis.app.provider.openai.OpenAIModelsApi.codexImageModels().map { it.id }
        assertEquals(
            listOf("gpt-image-2", "gpt-image-2.5-sunburst", "gpt-image-2.5-flare"),
            ids,
        )
    }

    /**
     * They must declare image OUTPUT. This is exactly why they are appended
     * after enrichment rather than run through it: models.dev does not carry
     * them, and a null output modality would drop them out of image routing.
     */
    @Test
    fun `image models declare image output`() {
        for (m in com.openminis.app.provider.openai.OpenAIModelsApi.codexImageModels()) {
            assertEquals("${m.id} must output image", listOf("image"), m.outputModalities)
            assertTrue("${m.id} must accept text", m.inputModalities?.contains("text") == true)
        }
    }

    /**
     * The built-in OAuth list must still contain them, unchanged by the
     * extraction into a shared accessor — the fallback tier is what users get
     * when discovery is down.
     */
    @Test
    fun `built-in oauth list still ends with the image models`() {
        val all = com.openminis.app.provider.openai.OpenAIModelsApi.fetchModelsOAuth().map { it.id }
        assertEquals(
            listOf("gpt-image-2", "gpt-image-2.5-sunburst", "gpt-image-2.5-flare"),
            all.takeLast(3),
        )
    }

    /**
     * The merge RULE itself, as `discoverCodexModels` applies it on a
     * successful discovery:
     *
     *     result.models + imageModels.filter { it.id !in discoveredIds }
     *
     * The tests above pin what the accessor CONTAINS; this pins what the tier-1
     * path DOES with it, which is the property GH#319's follow-up is actually
     * about. Reproduced rather than invoked because `discoverCodexModels` is a
     * private suspend member needing a Context, an instance and a live token —
     * none of which a JVM unit test can supply. If the merge is ever dropped
     * from the repository, this test keeps passing and the one below it
     * (`built-in oauth list still ends with the image models`) does too — so
     * the comment at ProviderRepository's Success branch is the anchor a
     * reviewer should check against.
     */
    @Test
    fun `a successful discovery keeps the image models and adds no duplicates`() {
        val image = com.openminis.app.provider.openai.OpenAIModelsApi.codexImageModels()

        fun merge(discovered: List<String>): List<String> {
            val discoveredIds = discovered.toSet()
            return discovered + image.map { it.id }.filter { it !in discoveredIds }
        }

        // The normal case: discovery answers with chat SKUs only, so all three
        // image models must be appended — this is the regression GH#319's
        // follow-up describes, where a fresh list would otherwise replace the
        // built-in one and delete them.
        val chatOnly = merge(listOf("gpt-5.6-terra", "gpt-5.5"))
        assertEquals(listOf("gpt-5.6-terra", "gpt-5.5") + image.map { it.id }, chatOnly)

        // An empty discovery still yields the image models rather than nothing.
        assertEquals(image.map { it.id }, merge(emptyList()))

        // And if the backend ever DOES start listing one, the filter must keep
        // it single — a duplicate id in the picker is its own bug.
        val withOne = merge(listOf("gpt-5.6-terra", "gpt-image-2"))
        assertEquals(withOne.size, withOne.toSet().size)
        for (m in image) assertTrue("${m.id} must survive", m.id in withOne)
    }
}

/**
 * [T-codex-dynamic-discovery GH#319] Cache partitioning.
 *
 * GH#319 lists "切换账号后不能复用另一账号缓存的模型目录" as a case to verify.
 * The catalog is genuinely per-account (plan tier gates which models come
 * back), so a shared key would show one user another's models. The client
 * version is in the key for the same reason: the backend filters on it, so a
 * version bump must re-fetch rather than replay a list computed under the old
 * gate.
 */
class CodexCacheKeyTest {

    private val v = "0.153.3"

    @Test
    fun `different accounts never share a key`() {
        assertNotEquals(
            CodexModelsApi.cacheKey("account-A", "token-A", v),
            CodexModelsApi.cacheKey("account-B", "token-B", v),
        )
    }

    /**
     * The account id is preferred precisely so a token refresh does NOT
     * invalidate the cache — the account is the same account.
     */
    @Test
    fun `a token refresh keeps the same key when the account is known`() {
        assertEquals(
            CodexModelsApi.cacheKey("account-A", "old-token", v),
            CodexModelsApi.cacheKey("account-A", "new-token", v),
        )
    }

    /**
     * With no account id — a real case on this project's own test account,
     * whose id_token carries no `chatgpt_account_id` — the token stands in.
     * Isolation still holds, which is the property that matters; the cost is
     * that a token refresh re-fetches once.
     */
    @Test
    fun `without an account id the token still isolates`() {
        assertNotEquals(
            CodexModelsApi.cacheKey(null, "token-A", v),
            CodexModelsApi.cacheKey(null, "token-B", v),
        )
        assertNotEquals(
            CodexModelsApi.cacheKey(null, "token-A", v),
            CodexModelsApi.cacheKey(null, "token-A", "0.154.0"),
        )
    }

    /** A blank account id must be treated as absent, not as a shared key. */
    @Test
    fun `a blank account id does not become a shared partition`() {
        assertNotEquals(
            CodexModelsApi.cacheKey("  ", "token-A", v),
            CodexModelsApi.cacheKey("", "token-B", v),
        )
    }

    /** Bumping the advertised client version re-partitions. */
    @Test
    fun `client version participates in the key`() {
        assertNotEquals(
            CodexModelsApi.cacheKey("account-A", "token-A", "0.153.3"),
            CodexModelsApi.cacheKey("account-A", "token-A", "0.154.0"),
        )
    }
}
