package com.openminis.app.scheduled

import com.openminis.app.data.model.ModelEntry

/**
 * [T-android-scheduled-model-resolve] Resolve `minis-scheduled create --model`
 * at CREATION time, not at fire time.
 *
 * ## The defect
 *
 * The CLI wrote the raw `--model` string straight into `ScheduledTask.modelId`.
 * Resolution was deferred to [ScheduledAgentRunner], where the value is used as
 * the new session's `modelId`. So a typo (`--model gtp-5`), a stale id from a
 * removed provider, or a model that lives on another instance all produced a
 * task that SAVES cleanly, lists cleanly, and then fails every single fire —
 * unattended, hours later, with nobody watching the chat that never answered.
 * The user's only signal was a notification days after the mistake.
 *
 * ## What resolution accepts
 *
 * The same three handles the UI offers:
 *  - an entry uuid (`ModelEntry.id`) — what the model picker stores;
 *  - a wire model id (`ModelEntry.model.id`, e.g. `gpt-5`) — what a human would
 *    write in a script, and what this feature is mostly typed with;
 *  - a display name (`ModelEntry.model.displayName`, case-insensitive) — the
 *    most human form, accepted when it is unambiguous.
 *
 * Resolution stores the canonical WIRE id, so the scheduled session boots with a
 * value the provider layer is guaranteed to know.
 *
 * Hidden entries resolve too: a task may legitimately target a model the user
 * keeps out of the picker. Deleted/tombstoned entries do not, because they are
 * not in the config anymore.
 */
object ScheduledModelResolver {

    /** How a raw `--model` value was understood; reported for logs/tests. */
    enum class Via { MODEL_ID, ENTRY_ID, DISPLAY_NAME }

    sealed interface Result {
        /** No `--model` given: leave the task on the runtime default. */
        data object Unspecified : Result

        /** A known model; [modelId] is the canonical wire id. */
        data class Resolved(val modelId: String, val via: Via) : Result

        /** Not in the provider config; [known] is a short list for the error. */
        data class Unknown(val raw: String, val known: List<String>) : Result
    }

    private const val MAX_KNOWN_IN_ERROR = 12

    /**
     * @param raw the `--model` value verbatim (null/blank = unspecified)
     * @param entries every configured entry across instances
     */
    fun resolve(raw: String?, entries: List<ModelEntry>): Result {
        val wanted = raw?.trim()
        if (wanted.isNullOrEmpty()) return Result.Unspecified

        entries.firstOrNull { it.id == wanted }?.let {
            return Result.Resolved(it.model.id, Via.ENTRY_ID)
        }
        entries.firstOrNull { it.model.id == wanted }?.let {
            return Result.Resolved(it.model.id, Via.MODEL_ID)
        }
        // Wire ids are matched case-insensitively too: `GPT-5` is the same model
        // to every provider we talk to, and a script author should not have to
        // remember the vendor's capitalisation.
        entries.firstOrNull { it.model.id.equals(wanted, ignoreCase = true) }?.let {
            return Result.Resolved(it.model.id, Via.MODEL_ID)
        }
        val byName = entries.filter { it.model.displayName.equals(wanted, ignoreCase = true) }
        // Ambiguous display names ("GPT-5" on two instances) must not silently
        // pick one: the user is told to use the id instead.
        if (byName.size == 1) return Result.Resolved(byName.first().model.id, Via.DISPLAY_NAME)

        val known = entries.map { it.model.id }.distinct().sorted().take(MAX_KNOWN_IN_ERROR)
        return Result.Unknown(wanted, known)
    }
}
