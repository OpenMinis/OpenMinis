package com.openminis.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openminis.app.service.AskUserGate

/**
 * [T-question-bg-notify] Receives the option-button taps from the question
 * notification and forwards them to [AskUserGate], which completes the
 * deferred the waiting `ask_user_question` tool dispatch is suspended on.
 *
 * Registered in the manifest as a non-exported receiver — the system can
 * still deliver notification-action PendingIntents because
 * [PendingIntent.getBroadcast] explicitly targets this component class
 * (same override as [ApprovalBroadcastReceiver]). The action string is
 * app-internal, so spoofed answer intents from other apps are dropped.
 *
 * A tap on a stale entry (already answered in-app, gate entry unregistered)
 * is a safe no-op: [AskUserGate.answerWithLabel] returns false and the
 * notification is still cleared.
 */
class AskUserBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AskUserIntents.EXTRA_REQUEST_ID)
        if (intent.action == AskUserIntents.ACTION_ANSWER) {
            val label = intent.getStringExtra(AskUserIntents.EXTRA_ANSWER_LABEL)
            if (id != null && label != null) {
                // Snapshot the session BEFORE answering — answerWithLabel
                // consumes nothing, but a concurrent in-app resolution may
                // unregister the entry between here and the cancel below.
                val sessionId = AskUserGate.detailOf(id)?.sessionId
                val delivered = AskUserGate.answerWithLabel(id, label)
                if (sessionId != null) {
                    AskUserNotifier.cancelQuestion(context, sessionId)
                }
                Log.d(TAG, "answer for id=${id.take(8)}… delivered=$delivered")
            }
        }
        // Unknown/missing id: nothing to clear through the gate — the
        // notification id is session-keyed and the session is only knowable
        // from the gate entry. A stale bar row is cleared by its own tap
        // (contentIntent opens the app) or by the resolution path that
        // already ran.
    }

    companion object {
        private const val TAG = "AskUserBR"
    }
}
