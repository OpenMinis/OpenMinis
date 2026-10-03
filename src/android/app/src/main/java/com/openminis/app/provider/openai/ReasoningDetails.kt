package com.openminis.app.provider.openai

import org.json.JSONObject

/**
 * [T-android-openrouter-reasoning-details] Reads OpenRouter's structured
 * `delta.reasoning_details` array (iOS 8d17198d6, GH#263).
 *
 * The chat-completions parser only read STRING reasoning fields
 * (`reasoning_content`, `reasoning`, `reasoning_text`). OpenRouter also sends
 *
 *   "reasoning_details":[{"type":"reasoning.text","text":"…"},
 *                        {"type":"reasoning.summary","summary":"…"},
 *                        {"type":"reasoning.encrypted","data":"…"}]
 *
 * and for some models ONLY that. A model that spent its budget reasoning this
 * way ended the turn with text empty AND reasoning empty, which the agent loop
 * reports as "Model returned an empty response".
 */
internal object ReasoningDetails {

    /** What one chunk's `reasoning_details` contributed. */
    data class Parsed(
        /** Concatenated text of the `reasoning.text` / `reasoning.summary` items, in order. */
        val text: String,
        /** Opaque `reasoning.encrypted` items: no text, but the model did reason. */
        val encryptedCount: Int,
        /** True when at least one known item was present (even empty or encrypted). */
        val sawReasoning: Boolean,
    )

    private val STRING_FIELDS = listOf("reasoning_content", "reasoning", "reasoning_text")

    /**
     * Parses [delta]'s `reasoning_details`, or returns null when there is
     * nothing to read.
     *
     * Also null when the same chunk already carried NON-EMPTY string
     * reasoning: OpenRouter then sends the same text twice (string + details),
     * and reading both would double every sentence. An empty string field does
     * not count, so it cannot suppress real details.
     */
    fun parse(delta: JSONObject): Parsed? {
        val details = delta.optJSONArray("reasoning_details") ?: return null
        val chunkHadStringReasoning = STRING_FIELDS.any {
            (delta.opt(it) as? String).orEmpty().isNotEmpty()
        }
        if (chunkHadStringReasoning) return null

        val text = StringBuilder()
        var encrypted = 0
        var saw = false
        for (i in 0 until details.length()) {
            val item = details.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "reasoning.text" -> { saw = true; text.append(stringOrEmpty(item, "text")) }
                "reasoning.summary" -> { saw = true; text.append(stringOrEmpty(item, "summary")) }
                "reasoning.encrypted" -> { saw = true; encrypted++ }
                // Unknown future types are ignored rather than guessed at.
            }
        }
        if (!saw) return null
        return Parsed(text.toString(), encrypted, saw)
    }

    // optString would turn a JSON null into the literal "null".
    private fun stringOrEmpty(o: JSONObject, key: String): String = (o.opt(key) as? String).orEmpty()
}
