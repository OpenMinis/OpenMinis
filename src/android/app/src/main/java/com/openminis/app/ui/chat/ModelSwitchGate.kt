package com.openminis.app.ui.chat

/**
 * [T-android-model-switch-next-request] Pure decision for the model-switch
 * writeback epoch gate (see ChatViewModel.modelSelectionEpoch for the full
 * narrative).
 *
 * A run's group-fallback writeback — persisting the fallback model onto the
 * VM (currentProvider / activeEntryId / currentModel / persistBinding) — may
 * only land when the model-selection epoch it snapshotted at its start is
 * STILL the current one, i.e. no user-driven model selection happened in
 * between. Otherwise the loop's tail would clobber the model the user just
 * picked mid-run.
 *
 * Mirrors [ResumeBannerRace] (PR18): bump on user action, snapshot at run
 * start, gate the deferred writeback on snapshot == current.
 */
internal object ModelSwitchGate {
    fun mayWriteBack(snapshottedEpoch: Long, currentEpoch: Long): Boolean =
        snapshottedEpoch == currentEpoch
}
