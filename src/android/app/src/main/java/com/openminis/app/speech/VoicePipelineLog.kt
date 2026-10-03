package com.openminis.app.speech

import com.openminis.app.logging.AppLogger

/**
 * [T-android-voice-pipeline-trace] One tagged event per stage of a voice-input
 * take, so a "recording finished but no text appeared" report can be pinned to
 * a specific seam instead of guessed at.
 *
 * The chain a take passes through:
 *
 *   panel.start → manager.start → engine.start → capture.begin →
 *   capture.end → transcribe.begin → (transcribe.try …) →
 *   engine.final | engine.error → manager.final | manager.error →
 *   panel.commit
 *
 * A missing event names the broken link: capture.end with no transcribe.begin
 * is a capture-side stall, transcribe.begin with no engine.final is the
 * network round-trip, engine.final with no panel.commit is the UI hand-off
 * (the panel drops a result while editing, and dropping it silently is
 * exactly the reported symptom).
 *
 * PRIVACY: events and metadata only — never recognised text, never audio.
 * Lengths and byte counts are recorded because "empty vs non-empty" is the
 * question being asked; the content itself never is. Model and engine ids are
 * configuration, not user data. Provider error messages are NOT logged
 * verbatim (they can echo request content); only the exception class name.
 */
internal object VoicePipelineLog {
    private const val TAG = "VoicePipeline"

    /** @param meta already-sanitised `k=v` pairs. */
    fun event(name: String, vararg meta: Pair<String, Any?>) {
        val suffix = if (meta.isEmpty()) "" else
            meta.joinToString(" ", prefix = " ") { (k, v) -> "$k=${v ?: "-"}" }
        AppLogger.info(TAG, "$name$suffix")
    }

    /** Class name only — a provider message can quote the request. */
    fun errorKind(e: Throwable?): String = e?.javaClass?.simpleName ?: "none"
}
