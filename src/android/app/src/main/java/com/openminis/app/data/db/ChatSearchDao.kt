package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query

/**
 * Session/message search, kept out of [ChatDao] so the hot CRUD interface does
 * not carry the search SQL (and its architecture-policy ceiling).
 *
 * `preview` is the compact searchable projection populated by
 * MIGRATION_18_19 / updateMessageBody: for rows ≤2 KB it is the full parts
 * JSON; larger rows carry a ≤2 KB text-only excerpt. Searching it instead of
 * `parts_json` keeps the scan bounded — the old query read every multi-megabyte
 * body cell for a substring match. COALESCE only touches legacy rows that
 * predate the preview backfill. FTS5 is not viable here: unicode61 cannot
 * match a CJK substring (`会话` inside `这是一个会话`), the primary search case.
 */
@Dao
interface ChatSearchDao {

    @Query("""
        SELECT DISTINCT s.* FROM sessions s
        LEFT JOIN messages m ON m.session_id = s.id
        WHERE s.title LIKE :pattern
           OR COALESCE(m.preview, m.parts_json) LIKE :pattern
        ORDER BY s.updated_at DESC
        LIMIT 200
    """)
    suspend fun searchSessions(pattern: String): List<ChatSessionEntity>

    /**
     * First content hit per session, in ONE query, using the compact preview.
     *
     * The previous flow ran a per-session snippet query (N+1). SQLite's
     * bare-column-with-MIN rule returns the id/window from the earliest
     * matching message without a window function (unavailable on older
     * Android SQLite).
     */
    @Query("""
        SELECT m.session_id AS session_id, m.id AS id,
               substr(
                   m.preview,
                   max(1, instr(lower(m.preview), lower(:query)) - :radius),
                   :windowChars
               ) AS head_text
        FROM messages m
        WHERE m.session_id IN (:sessionIds)
          AND m.preview IS NOT NULL
          AND instr(lower(m.preview), lower(:query)) > 0
        GROUP BY m.session_id
        HAVING m.sort_order = MIN(m.sort_order)
    """)
    suspend fun firstSnippetPerSession(
        sessionIds: List<String>,
        query: String,
        radius: Int,
        windowChars: Int,
    ): List<MessageSnippetRow>
}

/** One snippet per session for search results. */
data class MessageSnippetRow(
    @ColumnInfo(name = "session_id") val sessionId: String,
    val id: String,
    @ColumnInfo(name = "head_text") val headText: String,
)
