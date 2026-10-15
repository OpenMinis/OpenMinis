package com.openminis.app.service

import com.openminis.app.tools.AskUserQuestion
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred

/**
 * [T-question-bg-notify] Process-global registry for in-flight
 * `ask_user_question` requests.
 *
 * Mirrors [ApprovalGate]: the pending question's [CompletableDeferred] lives
 * inside a ChatViewModel, which a notification-action BroadcastReceiver
 * cannot reach. The VM registers the deferred here under a request id; the
 * receiver (and the AgentForegroundService fallback delivery) resolves it
 * through this object, so the user can answer from the notification shade
 * without opening the app.
 *
 * ## Resolution contract
 *
 * Every path that completes the deferred goes through exactly one of:
 *  - **In-app card**: the VM completes the deferred directly
 *    (`submitUserQuestionAnswers` / `dismissPendingUserQuestions`). The gate
 *    entry is then stale; [unregister] in the tool-dispatch `finally` removes
 *    it. A late notification tap on the stale entry finds the deferred already
 *    completed — `CompletableDeferred.complete` is a no-op — so double
 *    resolution is structurally impossible.
 *  - **Notification action**: [answer] completes the deferred with the same
 *    `formatAnswers` JSON the in-app card produces.
 *  - **Teardown**: [cancelAll] completes every pending deferred with the
 *    skip-shaped verdict (`{"answers":[],"status":"…"}`) so a stopped agent
 *    loop never hangs on a question nobody will see.
 *
 * The gate never touches the notification manager — clearing the bar entry
 * is the caller's job (same split as ApprovalGate).
 */
object AskUserGate {

    /** A registered in-flight question set. */
    class Pending internal constructor(
        val requestId: String,
        val sessionId: String,
        val questions: List<AskUserQuestion.Question>,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Pending>()
    private val deferreds = HashMap<String, CompletableDeferred<String>>()

    /**
     * Registers [deferred] under a fresh request id. One registration per
     * in-flight tool call; the caller keeps the id for [unregister].
     */
    fun register(
        sessionId: String,
        questions: List<AskUserQuestion.Question>,
        deferred: CompletableDeferred<String>,
    ): String {
        val id = UUID.randomUUID().toString()
        synchronized(lock) {
            entries[id] = Pending(id, sessionId, questions)
            deferreds[id] = deferred
        }
        return id
    }

    /** Removes the registration without resolving it (tool-dispatch finally). */
    fun unregister(requestId: String) {
        synchronized(lock) {
            entries.remove(requestId)
            deferreds.remove(requestId)
        }
    }

    /**
     * Resolves the request with a full selections payload (same shape the
     * in-app card submits). Returns false when the id is unknown or already
     * resolved — both are safe no-ops for a stale notification tap.
     */
    fun answer(requestId: String, selections: List<List<String>>): Boolean {
        val (pending, deferred) = synchronized(lock) {
            val p = entries[requestId] ?: return false
            val d = deferreds[requestId] ?: return false
            p to d
        }
        val json = AskUserQuestion.formatAnswers(pending.questions, selections)
        return deferred.complete(json)
    }

    /**
     * Single-question convenience for notification action buttons: answers
     * `[label]` for the one question. Refuses (false) when the request is not
     * exactly one single-select question — the notification only builds
     * buttons for that shape, so a mismatched tap means a stale or forged
     * intent and must not fabricate an answer.
     */
    fun answerWithLabel(requestId: String, label: String): Boolean {
        val pending = synchronized(lock) { entries[requestId] } ?: return false
        if (pending.questions.size != 1 || pending.questions[0].multiSelect) return false
        if (pending.questions[0].options.none { it.label == label }) return false
        return answer(requestId, listOf(listOf(label)))
    }

    /**
     * Resolves the request with the skip-shaped verdict. Used by teardown
     * paths (service stop/interrupt) where nobody will ever see the card.
     */
    fun cancel(requestId: String, reason: String = "cancelled"): Boolean {
        val deferred = synchronized(lock) { deferreds[requestId] } ?: return false
        return deferred.complete("{\"answers\":[],\"status\":\"$reason\"}")
    }

    /**
     * Resolves every pending request with the skip verdict and returns the
     * affected session ids, so the caller can clear the matching
     * notifications after the gate is drained. Mirrors
     * [ApprovalGate.cleanupAll] + `pendingIds` in one call.
     */
    fun cancelAll(reason: String = "cancelled"): List<String> {
        val snapshot = synchronized(lock) { entries.values.toList() }
        snapshot.forEach { cancel(it.requestId, reason) }
        synchronized(lock) {
            entries.clear()
            deferreds.clear()
        }
        return snapshot.map { it.sessionId }.distinct()
    }

    /** Snapshot of the pending request ids (notification teardown bookkeeping). */
    fun pendingIds(): List<String> = synchronized(lock) { entries.keys.toList() }

    /** The registration for [requestId], or null (stale/unknown tap). */
    fun detailOf(requestId: String): Pending? = synchronized(lock) { entries[requestId] }
}
