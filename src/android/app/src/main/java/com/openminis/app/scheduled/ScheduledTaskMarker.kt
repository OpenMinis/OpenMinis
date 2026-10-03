package com.openminis.app.scheduled

/**
 * [T-android-scheduled-task-card] The envelope a scheduled task's prompt is
 * wrapped in so the transcript can tell it apart from something the user typed.
 *
 * A fired task sends its prompt through the ordinary prompt path, so without a
 * marker the resulting message is indistinguishable from a user message — the
 * user sees text they never wrote and has no way to learn which task produced
 * it, or that a task produced it at all.
 *
 * Modelled on `AgentCallback`: a leading tag the flattener recognises, parses,
 * and lifts into a card instead of a bubble. Kept deliberately small — it
 * carries only what the card must show and what the cancel action needs, and
 * the prompt itself follows verbatim so a build that does not know the tag
 * still shows something sensible rather than an empty bubble.
 *
 * Wire shape (attributes are single-line, the body is the untouched prompt):
 *
 *     <scheduled_task id="…" label="…" next="…">
 *     the prompt text
 *     </scheduled_task>
 *
 * [T-scheduled-tool-prefill] A task with a prefilled tool call adds
 * `prefilled="<tool>"` and opens the body with [prefillNote]. The model reads
 * the raw envelope, and the attribute alone did not say what to DO: the note
 * (verbatim the iOS wording, ScheduledPresetToolCall.envelopeNote) tells it
 * the tool call that follows was already made for it and that it should work
 * from that output, re-running only on failure — without it the model sees a
 * call it has no memory of deciding on, and may run it again. [parse] strips
 * the note back off, so the card shows the prompt alone. Readers that predate
 * the attribute ignore it.
 *
 * [T-scheduled-preemptive-insert] A fire delivered into a session whose agent
 * loop is still running is slipped in between two tool calls rather than held
 * until the whole run ends. Its envelope adds `inserted="1"` and opens with
 * [insertionReminder] instead of the prefill note: the model must learn that
 * this turn was dropped into the middle of its own work (not produced by its
 * last tool call, not started by it), what to do with a prefilled result, and
 * that it should go back to the interrupted task afterwards. [parse] strips it
 * like the note.
 */
data class ScheduledTaskMarker(
    val taskId: String,
    val label: String,
    /** Epoch millis of the NEXT fire, or null when this was the last one. */
    val nextFireAtMs: Long?,
    /** The prompt the task actually sent. */
    val prompt: String,
    /** [T-scheduled-tool-prefill] Name of the tool the scheduler ran before
     *  the model was asked, or null for an ordinary task. */
    val prefilledTool: String? = null,
    /** [T-scheduled-preemptive-insert] True when this fire is being slipped
     *  into a running agent loop between tool calls. */
    val insertedMidTask: Boolean = false,
    /** [T-scheduled-task-detail] Epoch millis this fire started. The same value
     *  is stored as the fire's [ScheduledRun.firedAt], which is how the detail
     *  page finds "the fire whose card was tapped" in the task's history.
     *  Null on envelopes written before this existed. */
    val firedAtMs: Long? = null,
) {
    val xml: String
        get() {
            val attrs = buildString {
                append("id=\"").append(escapeAttr(taskId)).append('"')
                append(" label=\"").append(escapeAttr(label)).append('"')
                nextFireAtMs?.let { append(" next=\"").append(it).append('"') }
                firedAtMs?.let { append(" fired=\"").append(it).append('"') }
                prefilledTool?.let { append(" prefilled=\"").append(escapeAttr(it)).append('"') }
                if (insertedMidTask) append(" inserted=\"1\"")
            }
            // The insertion reminder already says what the prefill note says,
            // so an inserted fire carries one or the other, never both.
            val note = when {
                insertedMidTask -> insertionReminder(label, prefilledTool) + "\n"
                prefilledTool != null -> prefillNote(prefilledTool) + "\n"
                else -> ""
            }
            return "<$TAG $attrs>\n$note$prompt\n</$TAG>"
        }

    companion object {
        const val TAG = "scheduled_task"

        /**
         * [T-scheduled-tool-prefill] The line a prefilled task's envelope opens
         * with. Kept word-for-word identical to iOS so both platforms tell the
         * model the same thing.
         */
        fun prefillNote(tool: String): String =
            "[This task's first step already ran for you: $tool — its call and output follow. " +
                "Work from that output; re-run only if it failed.]"

        /**
         * [T-scheduled-preemptive-insert] What an inserted fire tells the model,
         * in the short declarative style of a system reminder. It must carry
         * three facts and nothing more: this is an inserted scheduled fire (not
         * the last tool result, not the user), a prefilled command already ran
         * and should not be run again, and the prior task resumes afterwards.
         * [prefilledTool] only decides whether the prefill sentence is added.
         */
        fun insertionReminder(label: String, prefilledTool: String?): String {
            val name = label.replace("\n", " ").trim().ifEmpty { null }
                ?.let { "Scheduled task \"$it\"" } ?: "A scheduled task"
            val preset = if (prefilledTool != null) " Preset command already ran; read the result below, don't re-run it." else ""
            return "<system-reminder>$name fired and was inserted here — not your last tool result, not a user request." +
                "$preset Handle briefly, then resume your prior task.</system-reminder>"
        }

        fun isMarkerText(text: String): Boolean =
            text.startsWith("<$TAG ") || text.startsWith("<$TAG>")

        /**
         * Parse a marker, or null when [text] is not one.
         *
         * Never throws: this runs on every user message as the transcript is
         * flattened, including text a user could type by hand.
         */
        fun parse(text: String): ScheduledTaskMarker? {
            if (!isMarkerText(text)) return null
            val openEnd = text.indexOf('>').takeIf { it >= 0 } ?: return null
            // `<scheduled_task>` with no attributes leaves an EMPTY span, whose
            // start would otherwise land one past openEnd and throw.
            val attrStart = (TAG.length + 1).coerceAtMost(openEnd)
            val attrs = parseAttributes(text.substring(attrStart, openEnd))
            val id = attrs["id"]?.takeIf { it.isNotEmpty() } ?: return null
            val after = text.substring(openEnd + 1)
            val close = after.lastIndexOf("</$TAG>")
            val body = (if (close >= 0) after.substring(0, close) else after).trim()
            val prefilled = attrs["prefilled"]?.takeIf { it.isNotEmpty() }
            val inserted = attrs["inserted"] == "1"
            val label = attrs["label"].orEmpty()
            val note = when {
                inserted -> insertionReminder(label, prefilled)
                prefilled != null -> prefillNote(prefilled)
                else -> null
            }
            val prompt = if (note != null && body.startsWith(note)) body.removePrefix(note).trimStart() else body
            return ScheduledTaskMarker(
                taskId = id,
                label = label,
                nextFireAtMs = attrs["next"]?.toLongOrNull(),
                prompt = prompt,
                prefilledTool = prefilled,
                insertedMidTask = inserted,
                firedAtMs = attrs["fired"]?.toLongOrNull(),
            )
        }

        /** The prompt without the envelope — what a plain reader should see. */
        fun stripMarker(text: String): String = parse(text)?.prompt ?: text

        private fun parseAttributes(s: String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            var rest = s
            while (true) {
                val eq = rest.indexOf('=').takeIf { it >= 0 } ?: break
                val key = rest.substring(0, eq).trim()
                val afterEq = rest.substring(eq + 1)
                val q = afterEq.indexOf('"').takeIf { it >= 0 } ?: break
                val endQ = afterEq.indexOf('"', q + 1).takeIf { it >= 0 } ?: break
                if (key.isNotEmpty()) out[key] = unescapeAttr(afterEq.substring(q + 1, endQ))
                rest = afterEq.substring(endQ + 1)
            }
            return out
        }

        private fun escapeAttr(s: String): String = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("\n", " ")

        private fun unescapeAttr(s: String): String = s
            .replace("&quot;", "\"").replace("&gt;", ">")
            .replace("&lt;", "<").replace("&amp;", "&")
    }
}
