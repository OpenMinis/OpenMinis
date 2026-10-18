package com.openminis.app.data.repository

import android.content.Context

/**
 * [T-android-delete-api-model] Remember the API-listed models a user DELETED, so
 * the next model refresh does not resurrect them.
 *
 * ## Why a tombstone is required
 *
 * Delete used to be offered only for custom entries, because an API-reported
 * entry has no local-only existence: the row is a projection of whatever
 * `GET /models` returned, and [ProviderRepository.replaceEntries] rebuilds that
 * projection on every refresh (startup auto-refresh, pull-to-refresh, provider
 * switch). Removing the row alone therefore produced a model that vanished and
 * came back a minute later — worse than not offering the action, because it
 * looked like the app had ignored the user.
 *
 * Upstream 1.15-beta.5 makes every row deletable; the mechanism there is a
 * per-instance set of deleted model ids consulted when the list is rebuilt.
 * This is that set, stored in SharedPreferences rather than in the provider
 * config so it needs no schema migration and survives the config round-trip
 * (backup export/import rewrites `modelEntries` wholesale, and a tombstone that
 * travelled with a backup would hide a model on the restoring device that the
 * user never deleted there — the device-local reading is the correct one).
 *
 * Hidden is a different intent and keeps its own flag: hiding keeps the row in
 * the list (collapsed into the "hidden models" group) so it can be un-hidden;
 * deleting takes it out entirely, including future refreshes.
 */
internal object DeletedModelTombstones {

    private const val PREFS = "deleted_model_tombstones"

    /** True when [modelId] was deleted for [instanceId] and must not be rebuilt. */
    fun isDeleted(context: Context, instanceId: String, modelId: String): Boolean =
        all(context, instanceId).contains(modelId)

    /** Record a deletion. Idempotent. */
    fun add(context: Context, instanceId: String, modelId: String) {
        val current = all(context, instanceId)
        if (modelId in current) return
        write(context, instanceId, current + modelId)
    }

    /**
     * Drop a tombstone — used when the user re-adds the same model by hand
     * (a custom entry) or explicitly restores it, so the intent is not silently
     * re-applied later.
     */
    fun forget(context: Context, instanceId: String, modelId: String) {
        val current = all(context, instanceId)
        if (modelId !in current) return
        write(context, instanceId, current - modelId)
    }

    /** Every deleted model id for [instanceId]. Never null; copy is safe to mutate. */
    fun all(context: Context, instanceId: String): Set<String> {
        val stored = prefs(context).getStringSet(key(instanceId), emptySet()) ?: emptySet()
        // getStringSet hands back the live instance on some OEM builds; copy it.
        return stored.toSet()
    }

    private fun write(context: Context, instanceId: String, ids: Set<String>) {
        prefs(context).edit().putStringSet(key(instanceId), ids.toSet()).apply()
    }

    private fun key(instanceId: String) = "instance:$instanceId"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
