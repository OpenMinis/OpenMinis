package com.openminis.app.ui.chat

import com.openminis.app.data.db.MessageEntity

/**
 * [T-android-bubble-anchor] Maps a user bubble to the persisted row it came
 * from, for retry, delete-from-here and edit. Port of iOS `locateUserBubble`
 * (6c0de68f5, which replaced 8a1bf2e8b): link, don't count.
 *
 * All three operations cut the DB at a row and rebuild the model-facing
 * history from what survives, so the row they pick is exactly what the model
 * keeps. They used to pick it with three private copies of an ordinal match
 * ("the n-th user bubble is the n-th visible user row"), each with its own
 * idea of "visible":
 *  - retry and delete skipped rows with no text part and `<system-reminder>`
 *    rows;
 *  - edit additionally stripped the `<user-attached-files>` XML first;
 *  - the UI side counted every user bubble.
 * An image-only message renders a bubble but has no text part, so it was
 * counted on screen and skipped in the DB: every later retry/delete/edit
 * anchored one turn too late. When the target was the last user turn the
 * match ran off the end, nothing was cut, and an edit then appended the new
 * text after the old one - the model still read the pre-edit message (the
 * iOS field report, reproduced here through a different skew).
 *
 * Every user bubble already carries its row: a live send, a mid-loop queue
 * injection and a steer take the persisted row's id as the bubble id, a loaded
 * bubble has `id == row id` plus [ChatMessage.sourceDbIds], and the bubbles of
 * an end-of-run queue drain are linked through [ChatMessage.sourceDbIds]
 * ([T-android-bubble-anchor-drain]). So the row is found by id, with no
 * counting. Only a bubble with no row - a queued placeholder that was
 * never persisted - falls back to the ordinal rule, with one shared
 * definition of a visible row and a warning in the log.
 *
 * iOS also needs a "pending" state for a bubble whose history entry is not
 * appended yet. Android creates the bubble only after its row is persisted,
 * and all three operations are refused while a turn is running, so that
 * window does not exist here.
 */
internal object BubbleRowLocator {

    enum class Via { LINKED, ORDINAL, NONE }

    class Located(val row: MessageEntity?, val via: Via)

    /** The persisted user row behind the user bubble at [index] in [messages]. */
    fun locateUserRow(messages: List<ChatMessage>, index: Int, rows: List<MessageEntity>): Located {
        val bubble = messages[index]
        val ids = HashSet<String>(bubble.sourceDbIds.size + 1).apply {
            add(bubble.id)
            addAll(bubble.sourceDbIds)
        }
        rows.firstOrNull { it.role == "user" && it.id in ids }?.let { return Located(it, Via.LINKED) }

        // Unlinked: the ordinal rule, counting the same rows the chat renders.
        val ordinal = messages.subList(0, index).count { it.role == "user" }
        var n = 0
        for (row in rows) {
            if (row.role != "user" || !isVisibleUserRow(row.partsJson)) continue
            if (n == ordinal) return Located(row, Via.ORDINAL)
            n++
        }
        return Located(null, Via.NONE)
    }

    /**
     * [T-android-bubble-anchor-drain] The bubbles that share the target's
     * persisted row: an end-of-run queue drain folds several queued prompts
     * into ONE user row but keeps one bubble per prompt, each linked to that
     * row through [ChatMessage.sourceDbIds]. Port of iOS 6c0de68f5's merged
     * group.
     *
     * The DB cut is the same for every member (they share the row), so the
     * chat must be cut as a whole group too, or it would show what the model
     * no longer has (or hide what it still has):
     *  - delete / edit remove from the group's FIRST bubble;
     *  - retry keeps through its LAST bubble.
     *
     * A bubble with no [ChatMessage.sourceDbIds] (live send, mid-loop inject,
     * steer, unpersisted placeholder) is a group of one.
     */
    fun groupSpan(messages: List<ChatMessage>, index: Int): IntRange {
        val target = messages[index]
        if (target.role != "user" || target.sourceDbIds.isEmpty()) return index..index
        val rows = target.sourceDbIds.toSet()
        var first = index
        var last = index
        messages.forEachIndexed { i, m ->
            if (m.role == "user" && (m.id in rows || m.sourceDbIds.any { it in rows })) {
                if (i < first) first = i
                if (i > last) last = i
            }
        }
        return first..last
    }

    /**
     * Where to cut so the located bubble is kept ([keepBubble], retry) or
     * removed with everything after it (delete / edit): a `sort_order` for
     * `ChatDao.deleteMessagesAfter` (`sort_order >= cutoff`), or -1 when no
     * row was found, which callers treat as "leave the DB alone".
     */
    fun cutoffFor(located: Located, keepBubble: Boolean): Int {
        val row = located.row ?: return -1
        return if (keepBubble) row.sortOrder + 1 else row.sortOrder
    }

    /**
     * Whether a user row renders a bubble, matching loadSessionMessages: it
     * has text other than a synthetic `<system-reminder>` or the
     * `<user-attached-files>` inventory, or it carries media. Tool-result-only
     * rows and the stop-continue reminder row do not.
     */
    fun isVisibleUserRow(partsJson: String): Boolean = try {
        val arr = org.json.JSONArray(partsJson)
        (0 until arr.length()).any { i ->
            val o = arr.getJSONObject(i)
            when (o.optString("type")) {
                "text" -> {
                    val v = o.optString("value", "")
                    !v.trimStart().startsWith("<system-reminder>") && stripAttachedFiles(v).isNotBlank()
                }
                "mediaRef", "image" -> true
                else -> false
            }
        }
    } catch (_: Exception) {
        true
    }

    private fun stripAttachedFiles(text: String): String {
        val start = text.indexOf("<user-attached-files>")
        if (start < 0) return text
        val endTag = "</user-attached-files>"
        val end = text.indexOf(endTag, start)
        return if (end >= 0) text.substring(0, start) + text.substring(end + endTag.length) else text.substring(0, start)
    }
}
