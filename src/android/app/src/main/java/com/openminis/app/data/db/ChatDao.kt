package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * Row projection for `ChatRepository.querySessionsMeta` (T188 — backing
 * the `minis-sessions-cli list` offload command). The SELECT shape is
 * dynamic (built from optional keyword/date/IN-list conditions), so we
 * use [RawQuery] + this POJO instead of a static `@Query`. Column names
 * here must exactly match the aliases the dynamic SQL emits — Room
 * binds by column name, not by ordinal.
 */
data class SessionMetaRow(
    val id: String,
    val title: String?,
    @ColumnInfo(name = "first_user_msg") val firstUserMsg: String?,
    val source: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "msg_count") val msgCount: Int,
)

/**
 * Row projection for `ChatRepository.searchMessages` (T188 — backing
 * the `minis-sessions-cli search` offload command). Same RawQuery
 * pattern as [SessionMetaRow] — keyword count varies per call, so the
 * WHERE clause is built dynamically and bound with positional args.
 */
data class MessageSearchRow(
    @ColumnInfo(name = "session_id") val sessionId: String,
    val id: String,
    val role: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "parts_json") val partsJson: String,
)

/**
 * Row projection for [ChatDao.lastMessageTailPerSession] (T-android-session-
 * paused-badge-hardkill). One row = the last message of a session (by
 * sort_order), carrying just the fields needed to decide whether the agent loop
 * was left interrupted, without loading the full history.
 */
data class SessionTailRow(
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,
    @ColumnInfo(name = "parts_json") val partsJson: String,
)

@Dao
interface ChatDao {
    // Sessions
    @Query("SELECT * FROM sessions ORDER BY updated_at DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY updated_at DESC")
    suspend fun listSessions(): List<ChatSessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: String): ChatSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity)

    @Query("UPDATE sessions SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSessionTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE sessions SET title = :title, category = COALESCE(:category, category), updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSessionTitleAndCategory(id: String, title: String, category: String?, updatedAt: Long)

    @Query("UPDATE sessions SET updated_at = :updatedAt WHERE id = :id")
    suspend fun touchSession(id: String, updatedAt: Long)

    @Query("UPDATE sessions SET last_message = :preview, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateLastMessage(id: String, preview: String?, updatedAt: Long)

    @Query("UPDATE sessions SET model_id = :modelId, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSessionModel(id: String, modelId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET model_binding = :binding, model_id = :modelId, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSessionBinding(id: String, binding: String, modelId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    // Full-text search across session titles and message content
    @Query("""
        SELECT DISTINCT s.* FROM sessions s
        LEFT JOIN messages m ON m.session_id = s.id
        WHERE s.title LIKE :pattern OR m.parts_json LIKE :pattern
        ORDER BY s.updated_at DESC
    """)
    suspend fun searchSessions(pattern: String): List<ChatSessionEntity>

    // ─── Folders (session groups) ──────────────────────────────────────────
    // [T-android-session-grouping] "Folder" in code, "Group" in the UI.

    /**
     * Ordered `updated_at DESC` to match iOS `listFolders()`. Rows with a blank
     * id are skipped — such a row could not be opened, filed into, or synced,
     * so surfacing it would only produce a dead card.
     */
    @Query("SELECT * FROM folders WHERE id != '' ORDER BY updated_at DESC")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id != '' ORDER BY updated_at DESC")
    suspend fun listFolders(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getFolder(id: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity)

    /**
     * `description = COALESCE(:description, description)` so passing null LEAVES
     * the stored description alone while an empty string clears it — the
     * caller's two intents stay distinguishable, matching iOS renameFolder.
     */
    @Query(
        """
        UPDATE folders
        SET name = :name,
            description = COALESCE(:description, description),
            updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun renameFolder(id: String, name: String, description: String?, updatedAt: Long)

    /** Pin toggles bump `updated_at` too, so the change carries a fresh LWW stamp. */
    @Query("UPDATE folders SET pinned_at = :pinnedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun setFolderPinned(id: String, pinnedAt: Long?, updatedAt: Long)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolder(id: String)

    @Query("SELECT id FROM sessions WHERE folder_id = :folderId ORDER BY updated_at DESC")
    suspend fun sessionIdsInFolder(folderId: String): List<String>

    @Query("SELECT COUNT(*) FROM sessions WHERE folder_id = :folderId")
    suspend fun sessionCountInFolder(folderId: String): Int

    /**
     * Move a session in (non-null) or out (null) of a group.
     *
     * Writes ONLY `folder_id` — `updated_at` is deliberately untouched, because
     * an organizational move must not re-sort the session list (which orders by
     * `updated_at DESC`). Filing a months-old chat should not shove it to the
     * top of Today.
     */
    @Query("UPDATE sessions SET folder_id = :folderId WHERE id = :sessionId")
    suspend fun setSessionFolder(sessionId: String, folderId: String?)

    /**
     * Conditional variant for any future automatic-grouping path: the
     * `AND folder_id IS NULL` lives in the STATEMENT rather than a caller-side
     * read-then-write, so a group the user chose by hand can never be
     * overwritten by a machine guess racing it.
     */
    @Query("UPDATE sessions SET folder_id = :folderId WHERE id = :sessionId AND folder_id IS NULL")
    suspend fun setSessionFolderIfUnfiled(sessionId: String, folderId: String): Int

    /** Clear membership for every session of a group — the dissolve half. */
    @Query("UPDATE sessions SET folder_id = NULL WHERE folder_id = :folderId")
    suspend fun clearFolderForSessions(folderId: String)

    // Messages
    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY sort_order ASC")
    suspend fun loadMessages(sessionId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY sort_order ASC")
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    /**
     * [T-android-voice-correction] User messages newer than [since] (epoch ms),
     * across every session, for typed-vocabulary mining.
     *
     * Filtering in SQL rather than loading all sessions and discarding in
     * Kotlin (which is what iOS does) keeps an incremental build proportional
     * to what is actually new. [limit] bounds a first run over a long history.
     */
    @Query(
        "SELECT * FROM messages WHERE role = 'user' AND created_at > :since " +
            "ORDER BY created_at ASC LIMIT :limit",
    )
    suspend fun loadUserMessagesSince(since: Long, limit: Int): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM messages WHERE session_id = :sessionId")
    suspend fun nextSortOrder(sessionId: String): Int

    @Query("DELETE FROM messages WHERE session_id = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    @Query("DELETE FROM messages WHERE session_id = :sessionId AND sort_order >= :keepCount")
    suspend fun deleteMessagesAfter(sessionId: String, keepCount: Int)

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun totalMessageCount(): Int

    @Query("SELECT token_usage FROM messages WHERE session_id = :sessionId AND token_usage IS NOT NULL")
    suspend fun tokenUsages(sessionId: String): List<String>

    /**
     * Fetch all token usage records joined with session model_id for aggregation.
     *
     * [T-android-usage-orphan-rows] GH#168 (iOS a192fad0f): LEFT JOIN, not
     * INNER JOIN. The Usage page is built entirely from this query, so any row
     * it drops is silently missing from the user's totals. With an INNER JOIN,
     * a message whose `sessions` row is gone — orphaned by a failed sync or
     * migration, or a partially-deleted session — vanished from the totals even
     * though its `token_usage` is still sitting in the table. Those tokens were
     * really billed, so the page under-reported with nothing to indicate it.
     *
     * LEFT JOIN keeps those rows, which is why [UsageRecord.modelId] is
     * nullable: it comes back NULL for an orphan, and the caller groups those
     * under "Unknown" rather than discarding them.
     *
     * This does NOT recover usage from sessions the user deleted outright —
     * deleting a session removes its message rows too. It only stops
     * orphaned-but-present rows from being thrown away.
     *
     * [T-token-attribution-snapshot] The model now comes from
     * `COALESCE(m.model_id, s.model_id)`, preferring the per-message snapshot
     * written when the turn was persisted.
     *
     * `s.model_id` remains only as the fallback for rows written before that
     * column existed. It is a single MUTABLE column per session, rewritten on
     * every model switch (including silent failover) with no history, and the
     * join carries no time dimension — so on its own it re-attributed a
     * session's entire history to whatever model it currently pointed at.
     * That is why `hasSnapshot` is selected alongside: the UI must show
     * fallback rows as ESTIMATED rather than passing them off as measured.
     */
    @Query("""
        SELECT COALESCE(m.model_id, s.model_id) AS modelId,
               m.model_display_name  AS modelDisplayName,
               m.provider_type       AS providerType,
               (m.model_id IS NOT NULL) AS hasSnapshot,
               m.token_usage AS tokenUsage, m.created_at AS createdAt, m.session_id AS sessionId
        FROM messages m LEFT JOIN sessions s ON m.session_id = s.id
        WHERE m.token_usage IS NOT NULL
    """)
    suspend fun allUsageRecords(): List<UsageRecord>

    // Last message preview for session list
    @Query("""
        SELECT parts_json FROM messages
        WHERE session_id = :sessionId
        ORDER BY sort_order DESC LIMIT 1
    """)
    suspend fun lastMessageParts(sessionId: String): String?

    /**
     * [T-android-session-paused-badge-hardkill] The last message (role +
     * parts_json) of every session, in one query — used at launch to derive
     * which sessions were left interrupted, so the PAUSED badge survives a hard
     * process death (where the lifecycle-callback push never runs). Picks the row
     * with the max sort_order per session via a correlated subquery, mirroring
     * iOS ChatStore.interruptedSessionIds().
     */
    @Query("""
        SELECT m.session_id AS session_id, m.role AS role, m.parts_json AS parts_json
        FROM messages m
        WHERE m.sort_order = (
            SELECT MAX(m2.sort_order) FROM messages m2 WHERE m2.session_id = m.session_id
        )
    """)
    suspend fun lastMessageTailPerSession(): List<SessionTailRow>

    // Session: memory_enabled
    @Query("UPDATE sessions SET memory_enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateMemoryEnabled(id: String, enabled: Int, updatedAt: Long = System.currentTimeMillis())

    // Session: thinking_override (T239) — null clears the explicit choice and
    // falls back to the current model/group default; non-null is a
    // ThinkingLevel.name string ("OFF"/"LOW"/"MEDIUM"/"HIGH"/"XHIGH").
    @Query("UPDATE sessions SET thinking_override = :value, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateThinkingOverride(id: String, value: String?, updatedAt: Long = System.currentTimeMillis())

    // Session: pinned_at
    @Query("UPDATE sessions SET pinned_at = :pinnedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun updatePinnedAt(id: String, pinnedAt: Long?, updatedAt: Long = System.currentTimeMillis())

    // Session: source
    @Query("UPDATE sessions SET source = :source WHERE id = :id")
    suspend fun updateSource(id: String, source: String?)

    // Messages: increment stream_interrupt_count
    @Query("UPDATE messages SET stream_interrupt_count = stream_interrupt_count + 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun incrementStreamInterruptCount(id: String, updatedAt: Long = System.currentTimeMillis())

    // Messages: rewrite a single row's parts_json in place. Mirrors iOS
    // ChatStore.updateMessageParts. Used by rerunFromToolBlock's block-
    // boundary cut: deleteMessagesAfter drops whole rows after the boundary,
    // but the kept assistant row itself needs its parts trimmed to before the
    // target tool_use — that's an UPDATE of an existing row, not a delete.
    @Query("UPDATE messages SET parts_json = :partsJson, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateMessageParts(id: String, partsJson: String, updatedAt: Long = System.currentTimeMillis())

    // [T-error-persist-android] Write/clear the terminal error sticker on a
    // specific message row by id. Used when the persisted DB id is known
    // (clear-on-retry via sourceDbIds).
    @Query("UPDATE messages SET error_info = :errorInfo WHERE id = :messageId")
    suspend fun updateMessageErrorInfo(messageId: String, errorInfo: String?)

    /**
     * [T-error-persist-android] Stamp the error sticker onto the LAST assistant
     * row of a session (max sort_order among role='assistant'). The agent loop
     * persists each turn with a fresh random row id while the in-memory UI
     * bubble keeps its own id, so setInlineError() can't address the row by the
     * in-memory ChatMessage id. Targeting "last assistant row" matches both iOS
     * (which attaches the error to `messages.last(where role==.assistant)`) and
     * the load-side merge that folds consecutive assistant rows keeping the last
     * row's identity. No-op when the session has no assistant row yet (e.g. a
     * first-turn failure before any turn persisted).
     */
    @Query("""
        UPDATE messages SET error_info = :errorInfo
        WHERE id = (
            SELECT id FROM messages
            WHERE session_id = :sessionId AND role = 'assistant'
            ORDER BY sort_order DESC LIMIT 1
        )
    """)
    suspend fun updateLastAssistantError(sessionId: String, errorInfo: String?)

    // Pinned sessions first, then by updated_at
    @Query("SELECT * FROM sessions ORDER BY CASE WHEN pinned_at IS NOT NULL THEN 0 ELSE 1 END, pinned_at DESC, updated_at DESC")
    fun observeSessionsSorted(): Flow<List<ChatSessionEntity>>

    // Compact markers — session-scoped archival summaries. "Append-only": rows
    // are never updated, only inserted and (on session delete) cascade-removed.
    // Lookups prefer id-first columns over the legacy sort-order columns.

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCompactMarker(marker: CompactMarkerEntity)

    /**
     * Replace a marker by id. Used by Phase 2.5 self-heal: when the
     * createdAt fallback resolves an anchor for an orphaned marker, we
     * rewrite that marker in place — same id/summary/createdAt, swapped
     * lcmId + version=2 + cleared legacy fields. Mirrors iOS
     * `ChatStore.updateCompactMarker` (used by `rewriteMarkerForHeal`).
     */
    @androidx.room.Update
    suspend fun updateCompactMarker(marker: CompactMarkerEntity)

    @Query("SELECT * FROM compact_markers WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT 1")
    suspend fun latestCompactMarker(sessionId: String): CompactMarkerEntity?

    @Query("SELECT * FROM compact_markers WHERE session_id = :sessionId ORDER BY created_at ASC")
    suspend fun listCompactMarkers(sessionId: String): List<CompactMarkerEntity>

    @Query("DELETE FROM compact_markers WHERE session_id = :sessionId")
    suspend fun deleteCompactMarkers(sessionId: String)

    /** Delete a single compact marker by id (for revert-compact). */
    @Query("DELETE FROM compact_markers WHERE id = :id")
    suspend fun deleteCompactMarker(id: String): Int

    // ─── T188: minis-sessions-cli backing queries ─────────────────────────────

    /**
     * Run a fully-built sessions meta query. The caller (ChatRepository.
     * querySessionsMeta) composes the WHERE/IN/keyword fragments because
     * @Query templates can't express variable-length IN lists or N-keyword
     * ANDs over `parts_json LIKE`. SELECT shape must produce columns matching
     * [SessionMetaRow].
     */
    @RawQuery
    suspend fun runSessionsMetaQuery(query: SupportSQLiteQuery): List<SessionMetaRow>

    /**
     * Same dynamic-SQL pattern as [runSessionsMetaQuery] but for the messages
     * table. Backs `minis-sessions-cli search`. SELECT must produce columns
     * matching [MessageSearchRow].
     */
    @RawQuery
    suspend fun runMessageSearchQuery(query: SupportSQLiteQuery): List<MessageSearchRow>

    /**
     * Paginated message page for `minis-sessions-cli messages --offset --limit`.
     * Sorted by `sort_order ASC` (stable insertion order) with `created_at ASC`
     * as a tie-breaker for messages inserted in the same millisecond.
     */
    @Query("""
        SELECT * FROM messages
        WHERE session_id = :sessionId
        ORDER BY sort_order ASC, created_at ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun loadMessagesPage(sessionId: String, offset: Int, limit: Int): List<MessageEntity>

    /**
     * [T-android-sessions-cli-messages-daterange] GH#200 (iOS 8f3189a73).
     * Date-filtered variant of [loadMessagesPage]. `--start` / `--end` were
     * documented in the CLI help and honoured by `list` / `search`, but
     * `messages` parsed neither and silently returned the whole session.
     *
     * Both bounds are inclusive and independently optional — a NULL bound means
     * "unbounded on that side", which keeps one query serving all four
     * combinations instead of four hand-written ones.
     */
    @Query("""
        SELECT * FROM messages
        WHERE session_id = :sessionId
          AND (:startMs IS NULL OR created_at >= :startMs)
          AND (:endMs IS NULL OR created_at <= :endMs)
        ORDER BY sort_order ASC, created_at ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun loadMessagesPageInRange(
        sessionId: String,
        offset: Int,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
    ): List<MessageEntity>

    /** Used by `minis-sessions-cli messages` to surface the total count
     *  alongside the paginated slice so callers can compute `hasMore`. */
    @Query("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId")
    suspend fun messageCountForSession(sessionId: String): Int

    /**
     * [T-android-sessions-cli-messages-daterange] Count under the SAME range as
     * [loadMessagesPageInRange]. Using the unfiltered count alongside a
     * filtered page would make `total` describe the whole session while the
     * slice covers only the filtered subset, so `hasMore` would lie — the
     * specific trap called out in iOS 8f3189a73.
     */
    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE session_id = :sessionId
          AND (:startMs IS NULL OR created_at >= :startMs)
          AND (:endMs IS NULL OR created_at <= :endMs)
    """)
    suspend fun messageCountForSessionInRange(
        sessionId: String,
        startMs: Long?,
        endMs: Long?,
    ): Int
}
