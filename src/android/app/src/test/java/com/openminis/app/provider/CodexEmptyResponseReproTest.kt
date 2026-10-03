package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.ui.chat.EmptyTurnRecovery
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * LIVE bisection harness for the "Model returned an empty response" retry on
 * session e01a065e (Pixel 6, GPT-5.6 Terra via Codex OAuth).
 *
 * Not a regression test — it talks to the real chatgpt.com Codex endpoint,
 * spends real quota, and is skipped unless MINIS_LIVE_REPRO=1. It replays the
 * exact history retryLast() re-sends (DB rows 0..21, Case B: tail is a user
 * row so nothing is popped), through the app's OWN OAuth provider and
 * Responses serializer, so the bytes on the wire are what the phone sends.
 * It then removes suspects one at a time and reports, per scenario, what the
 * model produced (text / tool call / nothing).
 *
 * Fixtures (pulled from the device via debug RPC, not hand-written):
 *   app/history.json      DB rows with full tool payloads
 *   app/instructions.txt  the 41.5KB system prompt as sent
 *   app/tools.json        the 8 tool schemas recovered from the capture
 *   app/auth_header.txt   the live OAuth bearer
 */
class CodexEmptyResponseReproTest {

    private fun fixture(name: String) = File(name).also { require(it.exists()) { "missing fixture $name" } }

    private data class Outcome(
        val text: String, val toolCalls: List<String>, val stop: String?, val err: String?,
    ) {
        val isEmpty get() = text.isBlank() && toolCalls.isEmpty() && err == null
        override fun toString() =
            if (err != null) "ERROR: ${err.take(160)}"
            else if (isEmpty) "EMPTY (stop=$stop)"
            else "text=${text.length}ch stop=$stop tools=$toolCalls text=«${text.take(200).replace('\n',' ')}»"
    }

    private fun loadHistory(): List<LLMMessage> {
        val arr = JSONArray(fixture("history.json").readText())
        return (0 until arr.length()).map { i ->
            val row = arr.getJSONObject(i)
            val role = if (row.getString("role") == "user") LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT
            val parts = row.getJSONArray("parts")
            val cps = mutableListOf<AgentContentPart>()
            var text = ""
            for (j in 0 until parts.length()) {
                val p = parts.getJSONObject(j)
                when (p.getString("kind")) {
                    "text" -> { val t = p.getString("text"); text += t; cps += AgentContentPart.Text(t) }
                    "toolUse" -> cps += AgentContentPart.ToolUse(
                        id = p.getString("id"), name = p.getString("name"),
                        input = runCatching { JSONObject(p.getString("input")) }.getOrDefault(JSONObject()),
                    )
                    "toolResult" -> cps += AgentContentPart.ToolResult(
                        id = p.getString("id"), name = p.getString("name"),
                        content = p.getString("output"), isError = !p.optBoolean("success", true),
                    )
                }
            }
            LLMMessage(role = role, content = text, contentParts = cps, dbMessageId = row.getString("id"))
        }
    }

    private fun loadTools(): List<AgentToolDefinition> {
        val arr = JSONArray(fixture("tools.json").readText())
        return (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            val schema = t.optJSONObject("parameters") ?: JSONObject()
            val props = schema.optJSONObject("properties") ?: JSONObject()
            val params = linkedMapOf<String, AgentToolParam>()
            for (key in props.keys()) {
                val p = props.getJSONObject(key)
                val enums = p.optJSONArray("enum")?.let { e -> (0 until e.length()).map { e.getString(it) } }
                params[key] = AgentToolParam(
                    type = p.optString("type", "string"),
                    description = p.optString("description", ""),
                    enumValues = enums,
                )
            }
            val req = schema.optJSONArray("required")?.let { r -> (0 until r.length()).map { r.getString(it) } } ?: emptyList()
            AgentToolDefinition(
                name = t.getString("name"),
                description = t.optString("description", ""),
                parameters = params,
                required = req,
            )
        }
    }

    private fun provider(): OpenAIProvider {
        val bearer = fixture("auth_header.txt").readText().trim().removePrefix("Bearer ").trim()
        val model = LLMModel(id = "gpt-5.6-terra", displayName = "GPT-5.6 Terra", provider = "OpenAI", supportsReasoning = true)
        return OpenAIProvider(oauthTokenProvider = { bearer }, model = model)
    }

    private fun run(label: String, history: List<LLMMessage>, system: String?, tools: List<AgentToolDefinition>): Outcome {
        val out = runBlocking {
            runCatching {
                provider().streamMessage(
                    messages = history, systemPrompt = system, maxTokens = -1,
                    tools = tools, thinkingLevel = ThinkingLevel.MEDIUM,
                ).toList()
            }
        }
        val o = out.fold(
            onSuccess = { chunks ->
                Outcome(
                    text = chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text },
                    toolCalls = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>().map { "${it.name}(${it.args.toString().take(220)})" },
                    stop = chunks.filterIsInstance<LLMStreamChunk.Finished>().lastOrNull()?.stopReason,
                    err = null,
                )
            },
            onFailure = { Outcome("", emptyList(), null, it.toString()) },
        )
        println("SCENARIO %-44s rows=%-2d -> %s".format(label, history.size, o))
        System.out.flush()
        return o
    }

    @Test
    fun `bisect the empty response against the live Codex endpoint`() {
        assumeTrue("set MINIS_LIVE_REPRO=1 to run the live bisection", System.getenv("MINIS_LIVE_REPRO") == "1")
        val full = loadHistory()
        val sys = fixture("instructions.txt").readText()
        val tools = loadTools()
        val N = System.getenv("MINIS_LIVE_N")?.toIntOrNull() ?: 6
        fun userText(t: String) = LLMMessage(LLMMessage.Role.USER, t, contentParts = listOf(AgentContentPart.Text(t)))
        val cleanBase = full.subList(0, 20).toList()

        fun rate(label: String, hist: List<LLMMessage>): Int {
            var empty = 0
            repeat(N) { k -> if (run("$label #${k + 1}", hist, sys, tools).isEmpty) empty++ }
            println(">>> $label : EMPTY $empty/$N"); return empty
        }
        // Apply the production recovery nudge to a user-text tail, exactly as the loop would.
        fun nudge(hist: List<LLMMessage>): List<LLMMessage> {
            val h = hist.toMutableList()
            val tail = h.last(); val parts = tail.contentParts.toMutableList()
            val ti = parts.indexOfLast { it is AgentContentPart.Text }
            parts[ti] = AgentContentPart.Text((parts[ti] as AgentContentPart.Text).text + EmptyTurnRecovery.REMINDER)
            h[h.size - 1] = tail.copy(contentParts = parts); return h
        }

        val progressNotice = cleanBase + userText("[Background task progress: 3 of 4 sub-tasks complete.]")
        println("=== A/B on the S1-progress trigger (5/6 empty before) ===")
        val before = rate("progress-notice RAW", progressNotice)
        val after = rate("progress-notice NUDGED", nudge(progressNotice))
        println(">>> RESULT progress-notice before=$before/$N after=$after/$N")
    }
}
