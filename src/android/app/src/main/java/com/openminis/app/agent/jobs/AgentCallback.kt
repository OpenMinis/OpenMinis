package com.openminis.app.agent.jobs

// [T-p2-agent-callback] Wire format for the messages an agent injects into
// its parent conversation: the completion report and mid-run progress
// reports. Byte-compatible with iOS AgentCallback.swift — both clients feed
// the same models, so the envelope must read identically. They travel as
// USER messages (every provider accepts those) wrapped in one fixed XML
// element so that the model can tell a system-injected callback from a
// human message, and the UI can render it as a callback card.
//
//   <agent_callback kind="finished" job="…" session="…" title="…"
//                   status="done" model="primary" elapsed="1m02s">
//   <summary>tools browser_use×2 · turns 4 · tokens in 12k / out 2k</summary>
//   <result>
//   …the agent's final answer…
//   </result>
//   </agent_callback>
//
// Progress reports use kind="progress", carry tool/activity/turn attributes
// and a <last_message> element instead of <result>.
data class AgentCallback(
    val kind: Kind,
    val jobId: String,
    val childSessionId: String?,
    val title: String,
    /** running · done · cancelled · timeout · failed · rejected */
    val status: String,
    val tier: String? = null,
    val elapsed: String? = null,
    val tool: String? = null,
    val activity: String? = null,
    val turn: Int? = null,
    /**
     * [T-sub-agents-v1] Which named sub agent produced this. Omitted for the
     * built-in — there is nothing to disambiguate.
     */
    val agentName: String? = null,
    val summary: String? = null,
    /**
     * [T-sub-agents-sibling-status] One line about the OTHER sub agents of this
     * conversation, on completion AND progress alike.
     *
     * The parent sees one result at a time and nothing else tells it whether
     * the rest of a batch is still coming, already lost, or never started — so
     * a model that fanned out three delegations and received one can quite
     * reasonably summarise as if it had them all.
     */
    val siblings: String? = null,
    val body: String,
) {
    enum class Kind(val wire: String) { PROGRESS("progress"), FINISHED("finished") }

    val xml: String
        get() {
            val attrs = mutableListOf("kind" to kind.wire, "job" to jobId)
            if (!childSessionId.isNullOrEmpty()) attrs += "session" to childSessionId
            attrs += "title" to title
            attrs += "status" to status
            if (!tier.isNullOrEmpty()) attrs += "model" to tier
            // [T-sub-agents-v1] Position matches iOS: model, agent, elapsed…
            if (!agentName.isNullOrEmpty()) attrs += "agent" to agentName
            if (!elapsed.isNullOrEmpty()) attrs += "elapsed" to elapsed
            if (!tool.isNullOrEmpty()) attrs += "tool" to tool
            if (!activity.isNullOrEmpty()) attrs += "activity" to activity
            if (turn != null && turn > 0) attrs += "turn" to turn.toString()
            val open = "<$TAG " + attrs.joinToString(" ") { "${it.first}=\"${escapeAttr(it.second)}\"" } + ">"
            val lines = mutableListOf(open)
            if (!summary.isNullOrEmpty()) lines += "<summary>${escapeText(summary)}</summary>"
            if (!siblings.isNullOrEmpty()) lines += "<other_sub_agents>${escapeText(siblings)}</other_sub_agents>"
            val bodyTag = if (kind == Kind.FINISHED) "result" else "last_message"
            lines += "<$bodyTag>"
            lines += body
            lines += "</$bodyTag>"
            lines += "</$TAG>"
            return lines.joinToString("\n")
        }

    /** Elapsed "1m02s" / "45s" → seconds. */
    val elapsedSeconds: Int?
        get() {
            val e = elapsed ?: return null
            val m = e.indexOf('m')
            return if (m >= 0) {
                val mins = e.substring(0, m).toIntOrNull() ?: 0
                val secs = e.substring(m + 1).removeSuffix("s").toIntOrNull() ?: 0
                mins * 60 + secs
            } else e.removeSuffix("s").toIntOrNull()
        }

    companion object {
        const val TAG = "agent_callback"

        /** Cheap prefix test used on every render / preview pass. */
        fun isCallbackText(text: String): Boolean =
            text.startsWith("<$TAG ") || text.startsWith("<$TAG>")

        fun parse(text: String): AgentCallback? {
            if (!isCallbackText(text)) return null
            val openEnd = text.indexOf('>').takeIf { it >= 0 } ?: return null
            // `<agent_callback>` with no attributes is accepted by
            // isCallbackText, and for it the attribute span is EMPTY — start
            // (TAG.length + 1) lands one past `openEnd`. Substring would throw
            // on a message a user could simply type, taking the renderer with
            // it, so the span is clamped instead.
            val attrStart = (TAG.length + 1).coerceAtMost(openEnd)
            val attrs = parseAttributes(text.substring(attrStart, openEnd))
            val kind = when (attrs["kind"]) {
                "progress" -> Kind.PROGRESS
                "finished" -> Kind.FINISHED
                else -> return null
            }
            val jobId = attrs["job"] ?: return null
            val after = text.substring(openEnd + 1)
            val close = after.lastIndexOf("</$TAG>")
            val inner = if (close >= 0) after.substring(0, close) else after
            val bodyTag = if (kind == Kind.FINISHED) "result" else "last_message"
            return AgentCallback(
                kind = kind,
                jobId = jobId,
                childSessionId = attrs["session"],
                title = attrs["title"] ?: "",
                status = attrs["status"] ?: (if (kind == Kind.FINISHED) "done" else "running"),
                tier = attrs["model"],
                elapsed = attrs["elapsed"],
                tool = attrs["tool"],
                activity = attrs["activity"],
                turn = attrs["turn"]?.toIntOrNull(),
                agentName = attrs["agent"],
                summary = element("summary", inner)?.let(::unescapeText),
                siblings = element("other_sub_agents", inner)?.let(::unescapeText),
                body = element(bodyTag, inner) ?: inner.trim(),
            )
        }

        private fun parseAttributes(s: String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            var rest = s
            while (true) {
                val eq = rest.indexOf('=').takeIf { it >= 0 } ?: break
                val key = rest.substring(0, eq).trim()
                val afterEq = rest.substring(eq + 1)
                val q1 = afterEq.indexOf('"').takeIf { it >= 0 } ?: break
                val q2 = afterEq.indexOf('"', q1 + 1).takeIf { it >= 0 } ?: break
                out[key] = unescapeText(afterEq.substring(q1 + 1, q2))
                rest = afterEq.substring(q2 + 1)
            }
            return out
        }

        private fun element(name: String, s: String): String? {
            val open = s.indexOf("<$name>").takeIf { it >= 0 } ?: return null
            val close = s.lastIndexOf("</$name>").takeIf { it >= 0 } ?: return null
            val start = open + name.length + 2
            if (start > close) return null
            return s.substring(start, close).trim()
        }

        private fun escapeAttr(s: String): String =
            escapeText(s).replace("\"", "&quot;").replace("\n", " ")

        private fun escapeText(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun unescapeText(s: String): String =
            s.replace("&quot;", "\"").replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&")
    }
}

/**
 * Localized nouns for [AgentCallback] previews (session list, notifications).
 * Installed once by MinisApp from resources so the data layer never touches a
 * Context. Defaults are the English words.
 */
object AgentCallbackLabels {
    @Volatile var result: String = "Agent result"
    @Volatile var progress: String = "Agent progress"
    @Volatile var status: Map<String, String> = emptyMap()

    fun install(context: android.content.Context) {
        val r = context.applicationContext.resources
        result = r.getString(com.openminis.app.R.string.agent_callback_result)
        progress = r.getString(com.openminis.app.R.string.agent_callback_progress)
        status = mapOf(
            "running" to r.getString(com.openminis.app.R.string.helper_status_running),
            "done" to r.getString(com.openminis.app.R.string.helper_status_completed),
            "cancelled" to r.getString(com.openminis.app.R.string.helper_status_cancelled),
            "timeout" to r.getString(com.openminis.app.R.string.helper_status_timeout),
            "failed" to r.getString(com.openminis.app.R.string.helper_status_failed),
            "rejected" to r.getString(com.openminis.app.R.string.helper_status_rejected),
        )
    }

    fun localizedStatus(s: String): String = status[s] ?: s

    /** One-line stand-in for session previews, where the raw XML would be noise. */
    fun previewLine(cb: AgentCallback): String =
        "${if (cb.kind == AgentCallback.Kind.FINISHED) result else progress} · ${cb.title} · ${localizedStatus(cb.status)}"
}
