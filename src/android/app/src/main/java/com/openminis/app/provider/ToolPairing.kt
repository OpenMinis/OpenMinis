package com.openminis.app.provider

/**
 * [T-android-responses-tool-id-normalize] The one notion of "this tool result
 * answers that tool call", shared by every pairing check. Port of iOS
 * `AIChatViewModel.pairingKey` (bc142f750).
 *
 * The OpenAI Responses path stores a COMBINED id on each tool call and its
 * result, `"<call_id>|<fc_id>"` (OpenAIProvider.combineResponsesAPIIds), and
 * the wire splits it and matches on the call_id half. A history can hold both
 * forms for the same call - a model switch or a fallback to a Chat
 * Completions model, history reloaded from the DB, a relay that rewrote ids -
 * so comparing raw ids calls a correctly paired call/result an orphan:
 * the history repair then deleted a real result and injected an "interrupted"
 * placeholder for a tool that had worked.
 *
 * The key is the part before "|". Chat Completions, Anthropic and Gemini ids
 * contain no "|", so for them this is the identity and nothing changes.
 */
object ToolPairing {
    fun key(id: String): String {
        val sep = id.indexOf('|')
        return if (sep < 0) id else id.substring(0, sep)
    }
}
