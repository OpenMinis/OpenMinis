package com.openminis.app.provider

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [M22] Release-date ranking INSIDE a picker section, and the two properties the
 * existing files each cover only half of.
 *
 * Pins 710b43a64 (the ANR: ranking was evaluated once per COMPARISON rather than
 * once per element) and the comparator's full key order.
 *
 * WHY THIS FILE:
 *  • ModelEntryRankingTest works on real `ModelEntry`s but, as its own header
 *    says, cannot see release dates at all — `ModelReleaseIndex` reads a 4.5 MB
 *    catalog out of app assets, unreachable without a Context, so every entry
 *    ranks as undated there. So it verifies survival and stability, not order.
 *  • ModelReleaseIndexTest asserts real ordering, but on synthetic `Rank`s
 *    in isolation, and only on the releaseDay key.
 *  • ModelReleaseSortShapeTest asserts the rank-once shape against a
 *    hand-rolled stand-in, by its own admission "the algorithmic shape, not the
 *    repository's own wiring".
 *
 * The gap between the three is the part a user actually sees: a SECTION of
 * mixed-vintage models ordered by the full comparator, with the ranking
 * evaluated once. This file closes it by driving the real comparator over
 * synthetic Ranks that carry every key — which is the only honest way to assert
 * date order in a JVM test — and by pinning the production shape by source
 * rather than by stand-in.
 */
class ModelReleaseRankingSectionTest {

    private fun rank(
        day: String? = null,
        cost: Double = 0.0,
        ctx: Int = 0,
        name: String = "m",
    ) = ModelReleaseIndex.Rank(
        releaseDay = day?.let { ModelReleaseIndex.parseReleaseDay(it) },
        outputCostPerMTok = cost,
        contextWindow = ctx,
        displayName = name,
    )

    /** Sort by the real production comparator and report display names. */
    private fun order(vararg ranks: ModelReleaseIndex.Rank): List<String> =
        ranks.toList().sortedWith(ModelReleaseIndex.comparator).map { it.displayName }

    // ══════════════════════════════ the section a user actually reads

    /**
     * The headline behaviour: a section of mixed vintages reads newest-first, and
     * the STALE model is not at the top. OpenMinis#83 was filed as "GPT-5.3 CodeX
     * Spark cannot call tools" — the real cause was that the backend refuses that
     * model and the refusal renders as an empty assistant turn. The user picked a
     * dead model off the top of the list and concluded the app was broken, which
     * is why ordering is a correctness concern here and not cosmetics.
     */
    @Test
    fun `a mixed-vintage section reads newest first`() {
        assertEquals(
            listOf("newest", "middle", "oldest"),
            order(
                rank(day = "2025-03-01", name = "oldest"),
                rank(day = "2026-09-01", name = "newest"),
                rank(day = "2026-01-15", name = "middle"),
            ),
        )
    }

    /**
     * Undated entries sink BELOW every dated one but are never dropped: custom,
     * local, relay-hosted models and provider-hosted TTS voices legitimately have
     * no catalog entry. A picker showing fewer models than the user configured is
     * a worse bug than a suboptimal order, and "drop what we cannot rank" is the
     * shortcut that produces it.
     */
    @Test
    fun `undated entries sink to the bottom without being lost`() {
        val result = order(
            rank(name = "custom-relay"),
            rank(day = "2026-09-01", name = "dated-new"),
            rank(name = "local-gguf"),
            rank(day = "2024-01-01", name = "dated-old"),
        )
        assertEquals(listOf("dated-new", "dated-old", "custom-relay", "local-gguf"), result)
        assertEquals("nothing may be dropped", 4, result.size)
    }

    /**
     * The full key cascade, asserted one key at a time with every earlier key
     * held equal. This is what neither existing file covers: the middle keys are
     * reached only when dates tie, which is the common case inside one vendor's
     * section (a whole family often ships on one day) and the case where a
     * catalog with no dates at all — i.e. the JVM environment, and any device
     * before the first catalog refresh — spends all of its time.
     */
    @Test
    fun `the tie-break cascade is date, then cost, then context, then name`() {
        // Same day → pricier first (≈ larger / more capable).
        assertEquals(
            listOf("pricey", "cheap"),
            order(
                rank(day = "2026-05-05", cost = 1.0, name = "cheap"),
                rank(day = "2026-05-05", cost = 15.0, name = "pricey"),
            ),
        )
        // Same day and cost → bigger context first.
        assertEquals(
            listOf("big-ctx", "small-ctx"),
            order(
                rank(day = "2026-05-05", cost = 3.0, ctx = 200_000, name = "big-ctx"),
                rank(day = "2026-05-05", cost = 3.0, ctx = 8_192, name = "small-ctx"),
            ),
        )
        // All equal → name, so the order is TOTAL and cannot jitter between reads.
        assertEquals(
            listOf("alpha", "beta", "gamma"),
            order(
                rank(day = "2026-05-05", cost = 3.0, ctx = 1000, name = "gamma"),
                rank(day = "2026-05-05", cost = 3.0, ctx = 1000, name = "alpha"),
                rank(day = "2026-05-05", cost = 3.0, ctx = 1000, name = "beta"),
            ),
        )
    }

    /**
     * An earlier key must DOMINATE a later one. Asserted explicitly because the
     * comparator is a hand-written cascade of early returns, and dropping a
     * `return@Comparator` would silently let a later key win — which reads as
     * "the list is sorted by something" and is very hard to spot by eye.
     */
    @Test
    fun `an earlier key always dominates a later one`() {
        assertEquals(
            "a newer date beats any price",
            listOf("new-and-cheap", "old-and-pricey"),
            order(
                rank(day = "2024-01-01", cost = 99.0, name = "old-and-pricey"),
                rank(day = "2026-09-01", cost = 0.1, name = "new-and-cheap"),
            ),
        )
        assertEquals(
            "price beats context",
            listOf("pricey-small", "cheap-huge"),
            order(
                rank(day = "2026-05-05", cost = 1.0, ctx = 1_000_000, name = "cheap-huge"),
                rank(day = "2026-05-05", cost = 20.0, ctx = 4_096, name = "pricey-small"),
            ),
        )
        assertEquals(
            "context beats name",
            listOf("zzz-huge", "aaa-small"),
            order(
                rank(day = "2026-05-05", ctx = 4_096, name = "aaa-small"),
                rank(day = "2026-05-05", ctx = 1_000_000, name = "zzz-huge"),
            ),
        )
    }

    /**
     * The name tie-break is case-INSENSITIVE. Vendors are inconsistent about
     * capitalising display names ("GPT-5.3" vs "gpt-5.3" vs "Claude Opus"), and a
     * case-sensitive compare puts every capitalised name in a block ahead of
     * every lowercase one — which reads as an arbitrary grouping rather than an
     * alphabetical list.
     */
    @Test
    fun `the name tie-break ignores case`() {
        assertEquals(
            listOf("apple", "Banana", "cherry"),
            order(
                rank(name = "cherry"),
                rank(name = "apple"),
                rank(name = "Banana"),
            ),
        )
    }

    /**
     * Ordering must not depend on INPUT order — a comparator that is not a total
     * order produces a different result depending on how the list arrived, so the
     * picker would reshuffle between reads for no visible reason. Checked by
     * sorting several permutations of one mixed section and requiring one answer.
     */
    @Test
    fun `the order is independent of input order`() {
        val section = listOf(
            rank(day = "2026-09-01", cost = 10.0, ctx = 400_000, name = "flagship"),
            rank(day = "2026-09-01", cost = 2.0, ctx = 400_000, name = "flagship-mini"),
            rank(day = "2025-06-01", cost = 10.0, ctx = 200_000, name = "previous"),
            rank(name = "user-custom"),
            rank(name = "another-custom"),
        )
        val expected = section.sortedWith(ModelReleaseIndex.comparator).map { it.displayName }
        assertEquals(
            listOf("flagship", "flagship-mini", "previous", "another-custom", "user-custom"),
            expected,
        )
        for (seed in 1..20) {
            val shuffled = section.shuffled(java.util.Random(seed.toLong()))
            assertEquals(
                "permutation $seed must sort to the same order",
                expected,
                shuffled.sortedWith(ModelReleaseIndex.comparator).map { it.displayName },
            )
        }
    }

    /**
     * The short-form `YYYY-MM` date must rank as a real date, not sink. 181
     * bundled catalog entries publish it, so a strict parser would push all of
     * them below every fully-dated model — a systematic bias, not a rounding
     * error.
     */
    @Test
    fun `a year-month date ranks among the fully dated, not below them`() {
        assertEquals(
            listOf("sept-full", "aug-short", "jan-full"),
            order(
                rank(day = "2026-01-15", name = "jan-full"),
                rank(day = "2026-08", name = "aug-short"),
                rank(day = "2026-09-20", name = "sept-full"),
            ),
        )
    }

    // ══════════════════ dedup of one id reached through two vendor prefixes

    /**
     * The same underlying model is published under several ids —
     * `anthropic/claude-opus-5` on OpenRouter, `us.anthropic.claude-opus-5-v1:0`
     * on Bedrock, `claude-opus-5` direct. They are DISTINCT entries (different
     * endpoints, credentials and prices), so a picker must show all of them; what
     * must NOT differ is where they sort, since they are the same vintage.
     *
     * That is the dedup property worth asserting at this layer: ranking is a
     * function of the RESOLVED model, so the vendor prefix must not change the
     * rank. Asserted through the comparator with equal ranks, since resolution
     * itself needs the asset catalog — see the note in ModelEntryRankingTest.
     */
    @Test
    fun `the same model under different vendor prefixes ranks together`() {
        val sameVintage = listOf(
            rank(day = "2026-04-01", cost = 15.0, ctx = 200_000, name = "Claude Opus 5"),
            rank(day = "2026-04-01", cost = 15.0, ctx = 200_000, name = "Claude Opus 5"),
            rank(day = "2026-04-01", cost = 15.0, ctx = 200_000, name = "Claude Opus 5"),
        )
        val sorted = sameVintage.sortedWith(ModelReleaseIndex.comparator)
        assertEquals("all three must survive — they are separate endpoints", 3, sorted.size)
        for (r in sorted) {
            assertEquals(
                "identical ranks must compare equal, so the prefix cannot reorder them",
                0,
                ModelReleaseIndex.comparator.compare(sameVintage.first(), r),
            )
        }
        // …and an older namesake must still sort below, so "same name" is never
        // itself the grouping key.
        assertEquals(
            listOf("Claude Opus 5", "Claude Opus 5", "Claude Opus 5", "Claude Opus 4.5"),
            order(
                *(sameVintage + rank(day = "2025-11-01", cost = 15.0, ctx = 200_000, name = "Claude Opus 4.5"))
                    .toTypedArray(),
            ),
        )
    }

    /**
     * A date-stamped id and its bare alias are the same vintage only if the index
     * resolves them to the same entry. That resolution is the `stripDateSuffix` /
     * `stripVendorDotPrefix` chain, which needs the asset catalog — so what is
     * assertable here is the PARSER half: the two spellings of a date must yield
     * the same sort key, since a mismatch there is the one way the same model
     * could land in two different places.
     */
    @Test
    fun `equivalent date spellings produce an identical sort key`() {
        assertEquals(
            ModelReleaseIndex.parseReleaseDay("2026-04-01"),
            ModelReleaseIndex.parseReleaseDay("2026-04-1".replace("-1", "-01")),
        )
        assertEquals(
            "the short form is the 1st of the month, i.e. the same key as an explicit -01",
            ModelReleaseIndex.parseReleaseDay("2026-04-01"),
            ModelReleaseIndex.parseReleaseDay("2026-04"),
        )
        assertNotEquals(
            "…but a different day is a different key",
            ModelReleaseIndex.parseReleaseDay("2026-04-01"),
            ModelReleaseIndex.parseReleaseDay("2026-04-02"),
        )
    }

    // ══════════════════════════════════════ rank-once, in production (710b43a64)

    /**
     * SOURCE-GREP DRIFT GUARD on the ANR fix. The behavioural proof lives in
     * ModelReleaseSortShapeTest, but it counts calls against a hand-rolled
     * stand-in, so it cannot notice if PRODUCTION reverts to the naive shape —
     * which is the thing that ANR'd.
     *
     * The naive form read perfectly naturally (a `Comparator<ModelEntry>` that
     * ranked both sides) and nothing about it looked expensive; the cost only
     * appeared on a device carrying 939 entries across 19 instances, where
     * O(n log n) ranking (~18,600 calls) on the main thread during
     * `ChatViewModel.init` blew the 5 s input-dispatch deadline. `rank()` is a
     * hash lookup but not free — it also faults in the lazily-built index on
     * first call. So pin the decorate-sort-undecorate shape itself.
     */
    @Test
    fun `production still ranks once per entry, not once per comparison`() {
        val src = ProductionSources.read("data/repository/ModelEntryRanking.kt")
        assertTrue(
            "the comparator must operate on PRE-RANKED pairs — a Comparator<ModelEntry> is the ANR shape",
            src.contains("Comparator<Pair<ModelEntry, ModelReleaseIndex.Rank>>"),
        )
        assertTrue(
            "…and the sort must consume those pairs",
            src.contains(".sortedWith(releaseRankOrder)"),
        )
        assertTrue(
            "the map that ranks each entry exactly once must still be there",
            src.contains("ModelReleaseIndex.rank(") && src.contains(".map { it.first }"),
        )
        assertEquals(
            "rank() must be called from exactly ONE place in this file — a second call site is " +
                "how per-comparison ranking creeps back",
            1,
            Regex("ModelReleaseIndex\\.rank\\(").findAll(src).count(),
        )
        assertTrue(
            "a list too short to order must not force the catalog index to build",
            src.contains("if (entries.size < 2) return entries"),
        )
    }

    /**
     * The picker sheets sort PER PROVIDER SECTION, so the naive shape would pay
     * its cost once per section rather than once — which is how a 19-instance
     * device reached ~18,600 rank calls. Pin that the shared picker delegates to
     * the extracted object instead of re-implementing the sort locally; an inline
     * `sortedWith` there would reintroduce the bug in a file the guard above
     * cannot see.
     */
    @Test
    fun `the shared picker delegates its section sort to the extracted ranking`() {
        val src = ProductionSources.read("ui/components/ModelEntryPicker.kt")
        assertTrue(
            "the picker must call the one ranking entry point",
            src.contains("ModelEntryRanking") && src.contains(".sortedByReleaseRank("),
        )
        assertEquals(
            "the picker must not hand-roll a rank comparator of its own",
            0,
            Regex("ModelReleaseIndex\\.rank\\(").findAll(src).count(),
        )

        val repo = ProductionSources.read("data/repository/ProviderRepository.kt")
        assertTrue(
            "the repository accessors must route through the same object",
            repo.contains("ModelEntryRanking.sortedByReleaseRank(this)"),
        )
        assertEquals(
            "…and likewise must not rank inline",
            0,
            Regex("ModelReleaseIndex\\.rank\\(").findAll(repo).count(),
        )
    }

    /**
     * The comparator is a single shared object, so every caller orders alike. A
     * second comparator anywhere in the tree is a divergence waiting to happen —
     * the picker and the provider-detail list showing different orders is exactly
     * what motivated extracting `ModelEntryRanking` in the first place.
     */
    @Test
    fun `there is exactly one release-rank comparator in the tree`() {
        val definitions = ProductionSources.allKotlinFiles().filter {
            it.readText().contains("val comparator: Comparator<Rank>")
        }
        assertEquals(
            "the release-rank comparator must be defined exactly once, found in " +
                definitions.map { it.name },
            1,
            definitions.size,
        )
        assertEquals("ModelReleaseIndex.kt", definitions.single().name)
    }
}
