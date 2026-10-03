package com.openminis.app.data.repository

import android.util.Log
import com.openminis.app.data.model.ProviderInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * [T-provider-refresh-outlives-screen] (GH#265) Runs a provider's model-list
 * refresh in a scope that belongs to the repository, not to the screen that
 * asked for it.
 *
 * The bug: Add Provider saved the instance, launched
 * `refreshModels(instance)` on the screen's `rememberCoroutineScope()`, and
 * called `onSaved()` on the very next line, which pops the screen. Popping
 * disposes the composition and cancels that scope, so the fetch died before
 * it reached the network and the new provider kept the built-in placeholder
 * catalog — for xAI, a list without `grok-4.6` — until the user found
 * Models ▸ Refresh by hand. A model refresh is background data sync; it must
 * not share the lifetime of a screen that is about to go away.
 *
 * The instance is looked up by id when the job RUNS, so it refreshes what is
 * saved at that moment (a deleted instance is skipped), and a failure is
 * logged rather than thrown into a scope with no one listening.
 */
internal class ModelReconciler(
    private val scope: CoroutineScope,
    private val lookup: (String) -> ProviderInstance?,
    private val refresh: suspend (ProviderInstance, Boolean) -> Unit,
) {
    fun trigger(instanceId: String, forceRefresh: Boolean): Job = scope.launch {
        val instance = lookup(instanceId)
        if (instance == null) {
            Log.i(TAG, "[ModelReconcile] $instanceId no longer exists; skipped")
            return@launch
        }
        try {
            refresh(instance, forceRefresh)
            Log.i(TAG, "[ModelReconcile] refreshed ${instance.id} (${instance.providerType}) force=$forceRefresh")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "[ModelReconcile] refresh failed for ${instance.id}: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "ProviderRepo"
    }
}
