package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-model-sort-anr] Pins the SHAPE of the model sort: ranking must be
 * evaluated once per element, not once per comparison.
 *
 * Why this test exists rather than a comment: the original code read perfectly
 * naturally — a `Comparator<ModelEntry>` that ranked both sides — and nothing
 * about it looked expensive. The cost only showed up on a device carrying 939
 * models, where an O(n log n) rank count (~18,600 calls) on the main thread
 * during `ChatViewModel.init` blew the 5s input-dispatch deadline and ANR'd the
 * app while opening a chat. A future refactor could reintroduce exactly that
 * shape without any visible symptom on a small test roster, so the invariant is
 * asserted by counting calls, not by inspection.
 *
 * `ProviderRepository` needs a Context and cannot be built in a plain JVM test,
 * so this reproduces its decorate-sort-undecorate against a counting stand-in.
 * That keeps the test honest about what it covers: the algorithmic shape, not
 * the repository's own wiring.
 */
class ModelReleaseSortShapeTest {

    private data class Entry(val id: String, val day: Int?)

    private var rankCalls = 0

    private fun rankOf(e: Entry): ModelReleaseIndex.Rank {
        rankCalls++
        return ModelReleaseIndex.Rank(e.day, 0.0, 0, e.id)
    }

    private val order = Comparator<Pair<Entry, ModelReleaseIndex.Rank>> { a, b ->
        val byRank = ModelReleaseIndex.comparator.compare(a.second, b.second)
        if (byRank != 0) byRank else a.first.id.compareTo(b.first.id)
    }

    /** The fixed shape: rank once, then sort the ranked pairs. */
    private fun sorted(entries: List<Entry>): List<Entry> {
        if (entries.size < 2) return entries
        return entries.map { it to rankOf(it) }.sortedWith(order).map { it.first }
    }

    @Test
    fun `ranks each entry exactly once regardless of list size`() {
        // 200 entries: n log n would be ~1,500 comparisons and ~3,000 rank
        // calls, so an accidental return to per-comparison ranking fails loudly
        // here instead of only on a real device.
        val entries = (1..200).map { Entry("m$it", 2026_01_01 + it) }

        rankCalls = 0
        val out = sorted(entries)

        assertEquals("one rank per entry, never per comparison", 200, rankCalls)
        assertEquals(200, out.size)
    }

    @Test
    fun `an empty or single-element list never builds the index`() {
        // Guards the early return. A device with no models configured must not
        // pay a 4.5 MB catalog parse just to sort nothing.
        rankCalls = 0
        assertTrue(sorted(emptyList()).isEmpty())
        assertEquals(1, sorted(listOf(Entry("only", null))).size)
        assertEquals("no ranking work for a list that cannot be reordered", 0, rankCalls)
    }

    @Test
    fun `ordering is unchanged - newest first, undated last, ties by id`() {
        // The point of the refactor was speed, NOT a new order. This pins the
        // observable result so a future change cannot quietly reorder pickers.
        val entries = listOf(
            Entry("zeta", 2026_03_01),
            Entry("alpha", 2026_03_01),   // same day as zeta -> id breaks the tie
            Entry("undated", null),        // sinks below every dated model
            Entry("newest", 2026_09_01),
            Entry("oldest", 2024_01_01),
        )

        assertEquals(
            listOf("newest", "alpha", "zeta", "oldest", "undated"),
            sorted(entries).map { it.id },
        )
    }
}
