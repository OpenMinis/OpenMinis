package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [T-nonstream-json-default] Performance roadmap §4-P0: full-body JSON parsing
 * is a CPU-bound operation (a 3 MB response costs 500-800 ms of tokenizer work)
 * that all three providers used to run on `Dispatchers.IO` — the pool sized for
 * blocking I/O. While one request parses, concurrent DB reads and network calls
 * queue behind it. The raw-body READ (network I/O) stays on the caller's
 * thread; only the PARSE moves to `Dispatchers.Default` (== CPU core count,
 * sized for compute).
 *
 * Defense is centralized here too: a raw `org.json.JSONException` escaping the
 * provider names tokenizer internals ("Value data of type java.lang.String
 * cannot be converted..."), not the actual mismatch. Anthropic/Gemini
 * previously let it escape; OpenAI's chat-completions JSON branch already
 * wrapped it. All three now throw [LLMError.DecodingError] with the endpoint
 * label and a bounded body prefix — the same shape the sniff branch
 * ([T-stream-content-type-sniff]) already used, so the agent-loop handling
 * chain (persisted inline errors, retry affordance) treats all three
 * identically.
 */
internal object FullBodyJson {

    /**
     * Parses [raw] on `Dispatchers.Default`. [debugLabel] names the endpoint /
     * content-type context so a decoding error stays actionable ("expected a
     * JSON body (chat-completions body, Content-Type: application/json) but
     * got: …" instead of a bare tokenizer stack).
     */
    internal suspend fun parse(raw: String, debugLabel: String): JSONObject =
        withContext(Dispatchers.Default) {
            try {
                JSONObject(raw)
            } catch (e: Exception) {
                throw LLMError.DecodingError(
                    IllegalStateException(
                        "expected a JSON object body ($debugLabel) but got: " +
                            raw.take(120).replace('\n', ' '),
                        e,
                    ),
                )
            }
        }
}
