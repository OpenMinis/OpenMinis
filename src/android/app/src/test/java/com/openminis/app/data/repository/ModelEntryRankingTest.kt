package com.openminis.app.data.repository

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [T-model-release-ranking] Covers the ordering the model picker sheets now
 * share with `ProviderRepository`.
 *
 * NOTE what a JVM test can and cannot see here. `ModelReleaseIndex` builds
 * itself from `ModelsDevApi.registrySnapshot()`, which reads the 4.5 MB
 * catalog out of app assets — unavailable without a Context, so every entry
 * ranks as undated in this environment. Asserting "newer model first" with
 * real model ids would therefore assert nothing (the first draft of this file
 * did exactly that: it passed, and still passed when the comparator was
 * replaced with a plain id sort). Release-date ordering is covered where it
 * can be honest, on synthetic Ranks, in `ModelReleaseIndexTest`.
 *
 * What IS verifiable here is the part that made extracting this object worth
 * doing: undated entries survive, the tie-break is total and stable, and short
 * lists never touch the index at all.
 */
class ModelEntryRankingTest {

    private fun entry(id: String, name: String = id, ctx: Int? = null) = ModelEntry(
        providerInstanceId = "inst",
        baseModel = LLMModel(id = id, displayName = name, provider = "openai", contextWindow = ctx),
    )

    /**
     * Undated entries — custom, local, relay-hosted models and provider TTS
     * voices — must all survive. A picker showing fewer models than the user
     * configured is a worse bug than a suboptimal order, and it is the failure
     * mode a "drop what we can't rank" shortcut would produce.
     */
    @Test
    fun `nothing is dropped, whatever the ranking says`() {
        val entries = listOf(entry("my-local-llama"), entry("gpt-5"), entry("azure-voice-xiaoxiao"))
        val sorted = ModelEntryRanking.sortedByReleaseRank(entries)
        assertEquals(entries.size, sorted.size)
        assertEquals(entries.map { it.baseModel.id }.toSet(), sorted.map { it.baseModel.id }.toSet())
    }

    /**
     * With everything ranked equal, the id tie-break has to produce one order
     * regardless of input order — otherwise the list visibly reshuffles
     * between reads of the same data.
     */
    @Test
    fun `equally ranked entries order by id, independent of input order`() {
        val a = entry("zzz-unknown-a")
        val b = entry("zzz-unknown-b")
        val c = entry("zzz-unknown-c")
        val expected = listOf("zzz-unknown-a", "zzz-unknown-b", "zzz-unknown-c")
        assertEquals(expected, ModelEntryRanking.sortedByReleaseRank(listOf(c, a, b)).map { it.baseModel.id })
        assertEquals(expected, ModelEntryRanking.sortedByReleaseRank(listOf(b, c, a)).map { it.baseModel.id })
    }

    /**
     * Context window outranks the id tie-break, so a bigger model wins among
     * otherwise-identical undated entries. This is the one ordering key a JVM
     * test can drive end to end, because it comes off the entry rather than
     * the catalog.
     */
    @Test
    fun `larger context window sorts ahead among undated entries`() {
        val small = entry("zzz-model-a", ctx = 8_000)
        val large = entry("zzz-model-b", ctx = 200_000)
        // `small` wins on id and loses on context — so if context did not
        // outrank the tie-break, this would come back the other way round.
        val sorted = ModelEntryRanking.sortedByReleaseRank(listOf(small, large))
        assertEquals("zzz-model-b", sorted.first().baseModel.id)
    }

    /**
     * A device with no models must not pay a catalog parse, so short lists are
     * returned untouched — asserted by identity, which is what "untouched"
     * actually means.
     */
    @Test
    fun `lists too short to order are returned as-is`() {
        val empty = emptyList<ModelEntry>()
        assertSame(empty, ModelEntryRanking.sortedByReleaseRank(empty))
        val single = listOf(entry("only"))
        assertSame(single, ModelEntryRanking.sortedByReleaseRank(single))
    }
}
