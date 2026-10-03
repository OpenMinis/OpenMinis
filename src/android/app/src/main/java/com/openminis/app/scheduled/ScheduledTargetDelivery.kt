package com.openminis.app.scheduled

/**
 * [T-android-scheduled-default-follow-up] Where a scheduled task's fires land,
 * decided and announced in one place. Port of iOS `ScheduledTargetDelivery`
 * (c32dda5a7), without its child-of-current case (Android's CLI has no such
 * target).
 *
 * 1. Default target. `minis-scheduled create` without `--target` used to mean
 *    `new`, so a model that asked for "remind me in this chat" and simply left
 *    the flag out got every fire in a separate chat: the result never came
 *    back to the conversation that asked for it. Run from inside a chat, the
 *    default is now `follow-up` (this chat). With no chat behind the command
 *    (a script, an external automation) it stays `new`.
 * 2. Delivery sentence. `create` answers with one plain sentence saying where
 *    the fires will land, and whether the target was defaulted, so a wrong
 *    target is visible at once and can be corrected with delete + create
 *    instead of being discovered when the first fire goes missing.
 *
 * Pure (no Android types) so `ScheduledTargetDeliveryTest` runs on the JVM.
 */
object ScheduledTargetDelivery {

    /**
     * `--target` to use: the explicit one (lower-cased), else `follow-up` when a
     * session is known (`--session` or the chat running the CLI), else `new`.
     * Returns the target and whether it was defaulted.
     */
    fun resolveTarget(explicit: String?, session: String?, callerSession: String?): Pair<String, Boolean> {
        val e = explicit?.trim()
        if (!e.isNullOrEmpty()) return e.lowercase() to false
        return (if ((session ?: callerSession) != null) "follow-up" else "new") to true
    }

    /** Where each fire lands, with what the sentence needs to name it. */
    sealed class Destination {
        abstract val kind: String

        data class FollowUp(val sessionId: String, val title: String?, val isCallerSession: Boolean) : Destination() {
            override val kind = "follow-up"
        }

        data class NewChat(val label: String?) : Destination() {
            override val kind = "new"
        }

        data class Rerun(val sessionId: String, val messageId: String, val title: String?) : Destination() {
            override val kind = "rerun"
        }
    }

    /** One sentence for the model (English: CLI output is read by the model). */
    fun sentence(d: Destination, defaulted: Boolean): String = when (d) {
        is Destination.FollowUp -> {
            val name = sessionName(d.sessionId, d.title)
            if (d.isCallerSession) {
                "Delivery: each fire runs as a new turn IN THIS CHAT ($name)." +
                    if (defaulted) {
                        " --target defaulted to follow-up because this command ran inside a chat; " +
                            "to use a separate chat instead, delete this task and re-create it with --target new."
                    } else ""
            } else {
                "Delivery: each fire runs as a new turn in ANOTHER chat ($name), NOT in this conversation. " +
                    "If the result should come back here, delete this task and re-create it without --session."
            }
        }
        is Destination.NewChat -> {
            val titled = d.label?.takeIf { it.isNotBlank() }?.let { " titled “Scheduled · $it”" } ?: ""
            "Delivery: each fire opens a NEW, separate chat$titled; the result will NOT appear in this conversation." +
                if (defaulted) {
                    " --target defaulted to new because no chat was running this command."
                } else {
                    " If the user wanted it in this chat, delete this task and re-create it with --target follow-up."
                }
        }
        is Destination.Rerun ->
            "Delivery: each fire RE-RUNS user message ${d.messageId.take(8)} in ${sessionName(d.sessionId, d.title)}: " +
                "the existing reply is regenerated, and no new prompt is added."
    }

    /** Structured twin of [sentence] for scripts. */
    fun summary(d: Destination, defaulted: Boolean): Map<String, Any> {
        val out = linkedMapOf<String, Any>("target" to d.kind, "defaulted" to defaulted)
        when (d) {
            is Destination.FollowUp -> {
                out["sessionId"] = d.sessionId
                out["isThisChat"] = d.isCallerSession
                d.title?.let { out["sessionTitle"] = it }
            }
            is Destination.NewChat -> d.label?.takeIf { it.isNotBlank() }?.let { out["newChatTitle"] = "Scheduled · $it" }
            is Destination.Rerun -> {
                out["sessionId"] = d.sessionId
                out["messageId"] = d.messageId
                d.title?.let { out["sessionTitle"] = it }
            }
        }
        return out
    }

    private fun sessionName(sid: String, title: String?): String =
        if (!title.isNullOrEmpty()) "session ${sid.take(8)} “$title”" else "session ${sid.take(8)}"
}
