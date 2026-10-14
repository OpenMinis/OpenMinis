package com.openminis.app.ui.chat

/**
 * [T-android-resume-banner-race] Pure decision for the resume-banner epoch
 * gate (see ChatViewModel.resumeEpoch for the full race narrative).
 *
 * A deferred `_canResume = true` (old run's winding-down tail, or a
 * loadSession interruption re-detection) may only land when the epoch it
 * snapshotted at its start is STILL the current one — i.e. no newer
 * send/resume has claimed the conversation in between. Otherwise the
 * banner would reappear over a conversation that is already running a
 * fresh turn.
 */
internal object ResumeBannerRace {
    fun mayRaise(snapshottedEpoch: Long, currentEpoch: Long): Boolean =
        snapshottedEpoch == currentEpoch
}
