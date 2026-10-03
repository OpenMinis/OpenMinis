package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-modelsdev-aggregate-fallback] Stage 3: the per-prefix aggregate a
 * model falls back to when neither an exact nor an alias-prefix match exists.
 *
 * The reported failure is a relay publishing renamed CPA models: every metadata
 * field came back `unknown_path` because stages 1 and 2 both require some
 * catalog id to equal the requested one. The aggregate answers from the FAMILY
 * instead — deliberately optimistic, since under-reporting a context window
 * silently truncates conversations while over-reporting merely surfaces the
 * provider's own error.
 *
 * The gate is the thing most worth pinning: an aggregate is a MAXIMUM over a
 * family, so letting it reach a direct OpenAI/Anthropic connection could
 * overwrite a precisely-known value with a larger sibling's.
 *
 * Mirrors iOS `ModelsDevAggregateFallbackTests` (commit 4a5d1578c).
 */
class ModelsDevAggregateFallbackTest {

    private fun entry(
        id: String,
        context: Int? = null,
        maxOut: Int? = null,
        reasoning: Boolean? = null,
        input: List<String>? = null,
        output: List<String>? = null,
        efforts: List<String>? = null,
        interleaved: String? = null,
    ) = ModelsDevApi.ModelDevEntry(
        id = id,
        name = null,
        family = null,
        contextWindow = context,
        maxOutputTokens = maxOut,
        reasoning = reasoning,
        interleavedField = interleaved,
        inputModalities = input,
        outputModalities = output,
        reasoningEffortValues = efforts,
        releaseDate = null,
        outputCost = null,
    )

    private fun registryOf(providerKey: String, vararg models: ModelsDevApi.ModelDevEntry) =
        mapOf(
            providerKey to ModelsDevApi.ProviderEntry(
                id = providerKey,
                name = providerKey,
                api = null,
                models = models.associateBy { it.id },
            ),
        )

    // ── 1. numeric fields take the MAX ───────────────────────────────────

    @Test
    fun `numeric attributes aggregate to the maximum`() {
        val agg = ModelsDevApi.aggregate(
            listOf(
                entry("glm-5.3-flash", context = 128_000, maxOut = 4_096),
                entry("glm-5.3-flash-lite", context = 1_000_000, maxOut = 8_192),
                entry("glm-5.3-flash-air", context = 64_000, maxOut = 2_048),
            ),
            id = "glm-5.3-flash",
        )
        assertEquals(1_000_000, agg.contextWindow)
        assertEquals(8_192, agg.maxOutputTokens)
    }

    @Test
    fun `a field no record declares stays null rather than zero`() {
        // Load-bearing: applyDevData merges with `?:`, so a fabricated 0 would
        // OVERWRITE whatever the provider's own API reported.
        val agg = ModelsDevApi.aggregate(
            listOf(entry("a-b", reasoning = true), entry("a-b-c", reasoning = false)),
            id = "a-b",
        )
        assertNull("no record declared a context window", agg.contextWindow)
        assertNull(agg.maxOutputTokens)
    }

    // ── 2. boolean fields take the logical OR ────────────────────────────

    @Test
    fun `boolean attributes aggregate to the logical OR`() {
        val agg = ModelsDevApi.aggregate(
            listOf(
                entry("x-y", reasoning = false),
                entry("x-y-pro", reasoning = true),
                entry("x-y-lite", reasoning = false),
            ),
            id = "x-y",
        )
        assertEquals(true, agg.reasoning)
    }

    @Test
    fun `all-false stays false and silence stays null`() {
        // "Nobody said true" and "nobody said anything" are different answers.
        assertEquals(
            false,
            ModelsDevApi.aggregate(
                listOf(entry("p-q", reasoning = false), entry("p-q-r", reasoning = false)),
                id = "p-q",
            ).reasoning,
        )
        assertNull(
            ModelsDevApi.aggregate(listOf(entry("p-q"), entry("p-q-r")), id = "p-q").reasoning,
        )
    }

    @Test
    fun `set fields take the union`() {
        val agg = ModelsDevApi.aggregate(
            listOf(
                entry("m-n", input = listOf("text"), efforts = listOf("low")),
                entry("m-n-vision", input = listOf("text", "image"), efforts = listOf("high")),
            ),
            id = "m-n",
        )
        assertEquals(listOf("image", "text"), agg.inputModalities)
        assertTrue(agg.reasoningEffortValues!!.containsAll(listOf("low", "high")))
    }

    // ── 3. a known mainstream provider must NOT be aggregated ────────────

    @Test
    fun `known providers are never treated as unrecognized`() {
        // These map to real catalog keys in providerKeyMap, so they must keep
        // getting the exact answer or none.
        assertFalse(ModelsDevApi.isUnrecognizedProvider("OpenAI"))
        assertFalse(ModelsDevApi.isUnrecognizedProvider("Anthropic"))
        assertFalse(ModelsDevApi.isUnrecognizedProvider("Google"))
        assertFalse(ModelsDevApi.isUnrecognizedProvider("OpenRouter"))
    }

    // ── 4. an unknown third-party provider DOES trigger it ───────────────

    @Test
    fun `unrecognized third-party providers trigger the fallback`() {
        // "Custom" is what OpenAIModelsApi stamps on a custom-base-URL relay.
        assertTrue(ModelsDevApi.isUnrecognizedProvider("Custom"))
        assertTrue(ModelsDevApi.isUnrecognizedProvider("some-unknown-relay"))
        // Antigravity maps to an EMPTY list on purpose — a custom proxy with no
        // public catalog entry, so it is unrecognised too.
        assertTrue(ModelsDevApi.isUnrecognizedProvider("Antigravity"))
    }

    @Test
    fun `the index registers a record under its own prefixes`() {
        val index = ModelsDevApi.buildAggregateIndex(
            registryOf("zhipuai", entry("glm-5.3-flash-air", context = 200_000)),
        )
        assertTrue("own id is a key: ${index.keys}", index.containsKey("glm-5.3-flash-air"))
        assertTrue(index.containsKey("glm-5.3-flash"))
        assertTrue(index.containsKey("glm-5.3"))
        // MIN_ALIAS_SEGMENTS floors the walk, so a bare generic head is never
        // registered — otherwise an unrelated model could inherit this one.
        assertFalse("must not register a single-segment head", index.containsKey("glm"))
    }

    @Test
    fun `the reported relay id resolves through the family`() {
        // The end-to-end shape of the bug: the relay's `-cpa` suffix matches no
        // catalog id, but the family aggregate answers.
        val index = ModelsDevApi.buildAggregateIndex(
            registryOf(
                "zhipuai",
                entry("glm-5.3-flash", context = 128_000, reasoning = false),
                entry("glm-5.3-flash-pro", context = 1_000_000, reasoning = true),
            ),
        )
        val agg = ModelsDevApi.aliasCandidates("glm-5.3-flash-cpa")
            .firstNotNullOfOrNull { index[it] }
        assertEquals(1_000_000, agg!!.contextWindow)
        assertEquals(true, agg.reasoning)
    }

    // ── 5. rebuild on snapshot refresh ───────────────────────────────────

    @Test
    fun `a refreshed snapshot yields a larger value and leaves the old index alone`() {
        val oldIndex = ModelsDevApi.buildAggregateIndex(
            registryOf("zhipuai", entry("glm-5.3-flash", context = 128_000)),
        )
        assertEquals(128_000, oldIndex["glm-5.3-flash"]!!.contextWindow)

        val newIndex = ModelsDevApi.buildAggregateIndex(
            registryOf("zhipuai", entry("glm-5.3-flash", context = 1_000_000)),
        )
        assertEquals(1_000_000, newIndex["glm-5.3-flash"]!!.contextWindow)

        // The previously-built index is a separate immutable value — a rebuild
        // must not mutate it in place, or a caller holding the old snapshot
        // would silently observe the new one.
        assertEquals(128_000, oldIndex["glm-5.3-flash"]!!.contextWindow)
    }
}
