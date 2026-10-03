package com.openminis.app.data.repository

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.provider.ModelReleaseIndex

/**
 * [T-model-release-ranking] The one place that orders a list of [ModelEntry]
 * for display: newest / most capable first.
 *
 * Extracted from `ProviderRepository` because the ordering has two kinds of
 * caller and only one of them goes through the repository. `entriesFor` /
 * `visibleEntries` / `allVisibleEntries` return sorted lists, but the model
 * picker sheets build their per-provider lists by filtering a `ProviderConfig`
 * snapshot directly — they never touch those accessors, so they were showing
 * raw config order (instance cluster, then model id alphabetically) and
 * silently losing the release ranking the provider-detail list already had.
 *
 * Why the ranking matters at all: a picker whose first entries are stale is an
 * active hazard. OpenMinis#83 was filed as "GPT-5.3 CodeX Spark cannot call
 * tools"; the real cause was that the backend refuses that model, and the
 * refusal renders as an EMPTY assistant turn. A user picked a dead model off
 * the top of the list and concluded the app was broken.
 *
 * Mirrors iOS `ProviderConfigStore.releaseRankOrder`, which
 * `UnifiedModelPicker` applies for exactly the same reason.
 */
object ModelEntryRanking {

    /**
     * Falls back to the model id so the ordering is total and stable when two
     * entries rank identically; otherwise the list could visibly reshuffle
     * between reads.
     */
    private val releaseRankOrder = Comparator<Pair<ModelEntry, ModelReleaseIndex.Rank>> { a, b ->
        val byRank = ModelReleaseIndex.comparator.compare(a.second, b.second)
        if (byRank != 0) byRank else a.first.baseModel.id.compareTo(b.first.baseModel.id)
    }

    /**
     * [T-android-model-sort-anr] Rank each entry ONCE, then sort the ranked
     * pairs — the decorate-sort-undecorate shape.
     *
     * A plain `sortedWith(comparator)` that called [ModelReleaseIndex.rank] on
     * both sides of every comparison ran ranking O(n log n) times instead of
     * O(n). On a real device carrying 939 visible entries across 19 provider
     * instances that is ~18,600 rank computations for one sort, on the main
     * thread during `ChatViewModel.init` -> `loadSession` ->
     * `applyNewChatDefaultModel`. Under memory pressure it blew past the 5s
     * input-dispatch deadline and the app ANR'd while opening a chat. `rank()`
     * is a hash lookup, not free: it also faults in the lazily-built index on
     * first call.
     *
     * Keep this shape in any new caller — the picker sheets sort per provider
     * instance, so the naive form would pay that cost once per section.
     */
    fun sortedByReleaseRank(entries: List<ModelEntry>): List<ModelEntry> {
        // Nothing to order, and — more importantly — nothing that should force
        // the release index to be built. A device with no models configured
        // must not pay a catalog parse here.
        if (entries.size < 2) return entries
        return entries
            .map {
                it to ModelReleaseIndex.rank(
                    it.baseModel.id, it.baseModel.displayName, it.baseModel.contextWindow,
                )
            }
            .sortedWith(releaseRankOrder)
            .map { it.first }
    }
}
