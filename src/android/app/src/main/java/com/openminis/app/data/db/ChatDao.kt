package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * Hot-path projection. Large parts_json cells are not selected. A body_ref
 * row already stores a stub. A small inline row is safe to return. Everything
 * else returns the preview written by migration 19 or the next write.
 */
private const val SAFE_MESSAGE_FROM = """
SELECT id, session_id, role,
CASE
  WHEN body_ref IS NOT NULL THEN parts_json
  WHEN body_bytes <= 2048 THEN parts_json
  ELSE COALESCE(preview, '[{"type":"text","value":"[body kept on disk]"}]')
END AS parts_json,
created_at, token_usage, sort_order, reasoning_content,
stream_interrupt_count, updated_at, error_info,
model_id, model_display_name, provider_type, provider_instance_id,
body_bytes, body_ref, body_sha, preview
FROM messages
"""

/**
 * Row projection for `ChatRepository.querySessionsMeta` (T188 — backing
 * the `minis-sessions-cli list` offload command). The SELECT shape is
 * dynamic (built from optional keyword/date/IN-list conditions), so we
 * use [RawQuery] + this POJO instead of a static `@Query`. Column names
 * here must exactly match the aliases the dynamic SQL emits — Room
 * binds by column name, not by ordinal.
 */
private const val MAX_SORT_RETRIES = 4

data class MessageBodyMeta(
    val id: String,
    @ColumnInfo(name = "body_bytes") val bodyBytes: Long,
    @ColumnInfo(name = "body_ref") val bodyRef: String?,
)

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
 * [T-android-huge-session-load-oom] Lightweight projection for callers that
 * only need each message's role and a bounded text head (title generation,
 * content-search snippets, evolution harvest). SQL `substr` bounds the
 * payload BEFORE it crosses the CursorWindow, so a 5.4M-char session costs
 * ~4.6k chars here instead of materialising every 500KB parts_json blob.
 */
data class MessageHeadRow(
    val id: String,
    val role: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "head_text") val headText: String?,
)

data class MessageCountRow(val count: Int)

data class MessageAnchorRow(
    val id: String,
    val role: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "head_text") val headText: String?,
)

data class MessageSortAnchor(
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val role: String,
)

data class MessagePreviewRow(
    val role: String,
    val preview: String?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

data class MessageUsageRow(
    val id: String,
    val role: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "token_usage") val tokenUsage: String,
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

    /**
     * [T-android-startup-splash-hang] Existence probe for the launch resolver.
     * `listSessions().isNotEmpty()` hydrated every row of the widest table in
     * the schema to answer a yes/no question, on the cold-start path.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM sessions)")
    suspend fun hasAnySession(): Boolean

    /**
     * [T-android-startup-splash-hang] The newest session by `updated_at`
     * (indexed) without pulling the whole table through the cursor. Replaces
     * `listSessions().firstOrNull()`, which loaded every row to keep one.
     */
    @Query("SELECT * FROM sessions ORDER BY updated_at DESC LIMIT 1")
    suspend fun newestSession(): ChatSessionEntity?

    /**
     * [T-android-startup-splash-hang] id + folder_id for FILED sessions only —
     * exactly what `warmupWorkspaceOwners` consumes. Unfiled rows are excluded
     * because they cannot contribute an entry (see [SessionFolderId]).
     */
    @Query(
        "SELECT id AS id, folder_id AS folderId FROM sessions " +
            "WHERE folder_id IS NOT NULL AND folder_id <> ''",
    )
    suspend fun listSessionFolderIds(): List<SessionFolderId>

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

    /** Drop a session pin that names a model entry the provider list no longer has. */
    @Query("UPDATE sessions SET model_binding = NULL, updated_at = :updatedAt WHERE model_binding LIKE '%' || :entryId || '%'")
    suspend fun clearBindingReferencing(entryId: String, updatedAt: Long = System.currentTimeMillis())

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
    @Query("$SAFE_MESSAGE_FROM WHERE session_id = :sessionId ORDER BY sort_order ASC")
    suspend fun loadMessages(sessionId: String): List<MessageEntity>

    @Query("$SAFE_MESSAGE_FROM WHERE id = :messageId AND session_id = :sessionId LIMIT 1")
    suspend fun getMessage(sessionId: String, messageId: String): MessageEntity?

    /** One cell, for display hydration only. List loads must keep using [SAFE_MESSAGE_FROM]. */
    @Query("SELECT parts_json FROM messages WHERE id = :id")
    suspend fun loadRawPartsJson(id: String): String?

    @Query("SELECT sort_order FROM messages WHERE id = :id")
    suspend fun sortOrderOf(id: String): Int?

    @Query("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId AND sort_order < :sortOrder")
    suspend fun countMessagesBeforeSort(sessionId: String, sortOrder: Int): Int

    @Query("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId AND sort_order > :sortOrder")
    suspend fun countMessagesAfterSort(sessionId: String, sortOrder: Int): Int

    /** Count a half-open stable-sequence range: [startInclusive, endExclusive). */
    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE session_id = :sessionId
          AND sort_order >= :startInclusive
          AND sort_order < :endExclusive
    """)
    suspend fun countMessagesInSortRange(
        sessionId: String,
        startInclusive: Int,
        endExclusive: Int,
    ): Int

    /** Role + sort_order only. Used to align a page to a user turn without reading bodies. */
    @Query("""
        SELECT sort_order, role FROM messages
        WHERE session_id = :sessionId AND sort_order < :beforeSortOrder
        ORDER BY sort_order DESC, created_at DESC, id DESC
        LIMIT :limit
    """)
    suspend fun loadOlderSortAnchors(
        sessionId: String,
        beforeSortOrder: Int,
        limit: Int,
    ): List<MessageSortAnchor>

    @Query("""
        SELECT sort_order, role FROM messages
        WHERE session_id = :sessionId AND sort_order > :afterSortOrder
        ORDER BY sort_order ASC, created_at ASC, id ASC
        LIMIT :limit
    """)
    suspend fun loadNewerSortAnchors(
        sessionId: String,
        afterSortOrder: Int,
        limit: Int,
    ): List<MessageSortAnchor>

    @Query("""
        $SAFE_MESSAGE_FROM
        WHERE session_id = :sessionId
          AND sort_order >= :startInclusive
          AND sort_order < :endExclusive
        ORDER BY sort_order ASC, created_at ASC, id ASC
        LIMIT :limit
    """)
    suspend fun loadMessagesFromSortOrder(
        sessionId: String,
        startInclusive: Int,
        endExclusive: Int,
        limit: Int,
    ): List<MessageEntity>

    /** Preview text only. Digest construction must not select parts_json. */
    @Query("""
        SELECT role, preview, sort_order FROM messages
        WHERE session_id = :sessionId AND sort_order < :beforeSortOrder
        ORDER BY sort_order DESC, created_at DESC, id DESC
        LIMIT :limit
    """)
    suspend fun loadPreviewPageBefore(
        sessionId: String,
        beforeSortOrder: Int,
        limit: Int,
    ): List<MessagePreviewRow>

    @Query("$SAFE_MESSAGE_FROM WHERE session_id = :sessionId ORDER BY sort_order DESC LIMIT 1")
    suspend fun lastMessage(sessionId: String): MessageEntity?

    @Query("$SAFE_MESSAGE_FROM WHERE session_id = :sessionId AND role = :role ORDER BY sort_order DESC LIMIT 1")
    suspend fun lastMessageByRole(sessionId: String, role: String): MessageEntity?
    /**
     * [T-android-huge-session-load-oom] Tail-bounded variant of [loadMessages].
     *
     * A 4562-message / 5.4M-char session crashed the process: loading EVERY row
     * kept three full copies alive at once (raw MessageEntity list + the
     * toChatMessages UI transform + the toLLMMessage history rebuild), pushing
     * RSS to 2.3GB -> GC storm -> Scudo OOM -> SIGABRT restart loop. The UI tail
     * window (`uiMessages` cap 200) and the request-boundary `ContextPolicy`
     * only bound what is RENDERED and SENT - nothing bounded what loadSession
     * KEPT.
     *
     * [offset] must be non-negative; callers pass `max(0, total - limit)` so the
     * query selects the LAST [limit] rows. The list arrives in sort_order ASC
     * (same order as [loadMessages]). When total <= limit the offset is 0 and
     * this degrades to the full history - identical to [loadMessages].
     */
    @Query("""
        $SAFE_MESSAGE_FROM
        WHERE session_id = :sessionId
        ORDER BY sort_order ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun loadMessagesTail(sessionId: String, limit: Int, offset: Int): List<MessageEntity>

    /** Full request-side body projection. Callers must page and hydrate boundedly. */
    @Query("""
        SELECT id, session_id, role, parts_json, created_at, token_usage, sort_order,
               reasoning_content, stream_interrupt_count, updated_at, error_info,
               model_id, model_display_name, provider_type, provider_instance_id,
               body_bytes, body_ref, body_sha, preview
        FROM messages
        WHERE session_id = :sessionId
        ORDER BY sort_order ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun loadMessagesTailRaw(sessionId: String, limit: Int, offset: Int): List<MessageEntity>

    @Query("$SAFE_MESSAGE_FROM WHERE session_id = :sessionId ORDER BY sort_order ASC LIMIT 200")
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(message: MessageEntity)

    /** Allocate and insert under SQLite's write transaction, shared across DAO/database instances. */
    @Transaction
    suspend fun appendMessage(message: MessageEntity): MessageEntity {
        var candidate = nextSortOrder(message.sessionId)
        repeat(MAX_SORT_RETRIES) {
            val row = message.copy(sortOrder = candidate)
            try {
                insertMessage(row)
                return row
            } catch (conflict: android.database.sqlite.SQLiteConstraintException) {
                val detail = conflict.message.orEmpty()
                if (!detail.contains("messages.session_id, messages.sort_order") &&
                    !detail.contains("index_messages_session_id_sort_order")
                ) throw conflict
                // A competing connection may have committed this cursor first.
                val retry = nextSortOrder(message.sessionId)
                if (retry == candidate || retry < 0) throw conflict
                candidate = retry
            }
        }
        throw IllegalStateException("Could not allocate unique message sort_order")
    }

    /**
     * [T-android-voice-correction] User messages newer than [since] (epoch ms),
     * across every session, for typed-vocabulary mining.
     *
     * Filtering in SQL rather than loading all sessions and discarding in
     * Kotlin (which is what iOS does) keeps an incremental build proportional
     * to what is actually new. [limit] bounds a first run over a long history.
     */
    @Query(
        "$SAFE_MESSAGE_FROM WHERE role = 'user' AND created_at > :since " +
            "ORDER BY created_at ASC LIMIT :limit",
    )
    suspend fun loadUserMessagesSince(since: Long, limit: Int): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM messages WHERE session_id = :sessionId")
    suspend fun nextSortOrder(sessionId: String): Int

    @Query("SELECT sort_order FROM messages WHERE session_id = :sessionId")
    suspend fun sortOrders(sessionId: String): List<Int>

    @Query("DELETE FROM messages WHERE session_id = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    @Query("DELETE FROM messages WHERE session_id = :sessionId AND sort_order >= :keepCount")
    suspend fun deleteMessagesAfter(sessionId: String, keepCount: Int)

    /**
     * [T-msg-version-archive] Raw rows (no preview substitution) that
     * [deleteMessagesAfter] is about to remove, so the caller can archive
     * them into `message_versions` first.
     */
    // NOTE: explicit column list (never SELECT *) — ResourceBoundaryTest
    // forbids star/raw projections on the messages table. All columns are
    // listed because the archived version needs the full row.
    @Query(
        "SELECT id, session_id, role, parts_json, created_at, token_usage, sort_order, " +
            "reasoning_content, stream_interrupt_count, updated_at, error_info, " +
            "model_id, model_display_name, provider_type, provider_instance_id, " +
            "body_bytes, body_ref, body_sha, preview " +
            "FROM messages WHERE session_id = :sessionId AND sort_order >= :cutoff ORDER BY sort_order ASC"
    )
    suspend fun rowsFrom(sessionId: String, cutoff: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun totalMessageCount(): Int

    @Query("SELECT token_usage FROM messages WHERE session_id = :sessionId AND token_usage IS NOT NULL")
    suspend fun tokenUsages(sessionId: String): List<String>

    @Query("""
        SELECT id, role, created_at, token_usage
        FROM messages
        WHERE session_id = :sessionId AND token_usage IS NOT NULL
        ORDER BY sort_order ASC
    """)
    suspend fun messageUsages(sessionId: String): List<MessageUsageRow>

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
               m.provider_instance_id AS providerInstanceId,
               (m.model_id IS NOT NULL) AS hasSnapshot,
               m.token_usage AS tokenUsage, m.created_at AS createdAt, m.session_id AS sessionId
        FROM messages m LEFT JOIN sessions s ON m.session_id = s.id
        WHERE m.token_usage IS NOT NULL
    """)
    suspend fun allUsageRecords(): List<UsageRecord>

    // Last message preview for session list
    @Query("""
        SELECT substr(COALESCE(preview, parts_json), 1, 8192) FROM messages
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
        SELECT m.session_id AS session_id, m.role AS role,
               substr(COALESCE(m.preview, m.parts_json), 1, 8192) AS parts_json
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

    @Query("UPDATE sessions SET permission_mode = :value, updated_at = :updatedAt WHERE id = :id")
    suspend fun updatePermissionMode(id: String, value: String, updatedAt: Long = System.currentTimeMillis())

    @Query(
        """
        UPDATE sessions SET permission_mode = 'ASK'
        WHERE permission_mode IS NULL
           OR TRIM(permission_mode) = ''
           OR permission_mode IN ('DENY_ALL', 'READ_ONLY', 'PLAN')
        """,
    )
    suspend fun backfillBlankPermissionModesToAsk()

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

    @Query(
        "UPDATE messages SET parts_json = :partsJson, body_bytes = :bodyBytes, " +
            "body_ref = :bodyRef, body_sha = :bodySha, preview = :preview, updated_at = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun updateMessageBody(
        id: String,
        partsJson: String,
        bodyBytes: Long,
        bodyRef: String?,
        bodySha: String?,
        preview: String?,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("SELECT substr(parts_json, :offset, :len) FROM messages WHERE id = :id")
    suspend fun readPartsChunk(id: String, offset: Int, len: Int): String?

    @Query("SELECT id, body_bytes, body_ref FROM messages WHERE id = :id")
    suspend fun bodyMeta(id: String): MessageBodyMeta?

    // [T-archive-trimmed-parts] Full-row read for archiving a row that is
    // about to be rewritten in place (updateMessageParts). Same explicit
    // projection as rowsFrom — never SELECT * (ResourceBoundaryTest).
    @Query(
        "SELECT id, session_id, role, parts_json, created_at, token_usage, sort_order, " +
            "reasoning_content, stream_interrupt_count, updated_at, error_info, " +
            "model_id, model_display_name, provider_type, provider_instance_id, " +
            "body_bytes, body_ref, body_sha, preview " +
            "FROM messages WHERE id = :id"
    )
    suspend fun messageById(id: String): MessageEntity?

    @Query("SELECT substr(COALESCE(preview, parts_json), 1, 65536) FROM messages WHERE id = :id")
    suspend fun messagePartsJson(id: String): String?

    /**
     * [T-delete-anchor-by-id] Direct row-id → sort_order lookup. The ordinal
     * walk in visibleUserCutoff counted a DIFFERENT set of user rows than the
     * UI list (UI counts every user-role ChatMessage; the DB walk skips
     * toolResult-only user rows), so the resolved anchor could land dozens
     * of turns early and deleteMessagesAfter wiped the session tail
     * (incident 2026-10-14: 1386 rows archived at 01:16:12 from one retry).
     * Anchoring by the message id the user actually tapped removes the
     * two-counter mismatch entirely: the UI ChatMessage.id IS the DB row id.
     */
    @Query("SELECT sort_order FROM messages WHERE id = :id")
    suspend fun sortOrderForMessageId(id: String): Int?

    // [T-error-persist-android] Write/clear the terminal error sticker on a
    // specific message row by id. Used when the persisted DB id is known
    // (clear-on-retry via sourceDbIds).
    @Query("UPDATE messages SET error_info = :errorInfo WHERE id = :messageId")
    suspend fun updateMessageErrorInfo(messageId: String, errorInfo: String?)

    @Query("SELECT id FROM messages WHERE session_id = :sessionId AND role = 'assistant' ORDER BY sort_order DESC LIMIT 1")
    suspend fun lastAssistantMessageId(sessionId: String): String?

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

    @RawQuery
    suspend fun runMessagesQuery(query: SupportSQLiteQuery): List<MessageEntity>

    @RawQuery
    suspend fun runMessageCountQuery(query: SupportSQLiteQuery): MessageCountRow

    /**
     * Paginated message page for `minis-sessions-cli messages --offset --limit`.
     * Sorted by `sort_order ASC` (stable insertion order) with `created_at ASC`
     * as a tie-breaker for messages inserted in the same millisecond.
     */
    @Query("""
        $SAFE_MESSAGE_FROM
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
        $SAFE_MESSAGE_FROM
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

    @Query("""
        SELECT id FROM messages
        WHERE session_id = :sessionId
        ORDER BY sort_order ASC, created_at ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun loadMessageIdsPage(sessionId: String, offset: Int, limit: Int): List<String>

    @Query("""
        SELECT id, role, sort_order, substr(parts_json, 1, :headChars) AS head_text
        FROM messages
        WHERE session_id = :sessionId
        ORDER BY sort_order ASC, created_at ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun loadMessageAnchorsPage(
        sessionId: String,
        offset: Int,
        limit: Int,
        headChars: Int,
    ): List<MessageAnchorRow>
    /**
     * [T-android-huge-session-load-oom] Title / snippet / harvest callers only
     * need the first USER text head of a session (plus its char length to
     * detect blankness). Extracting `value` in SQL via json_extract would be
     * ideal but org.json-style parts are a JSON ARRAY whose text parts are
     * objects; SQLite's json1 IS available on Android 9+ (minSdk 26), so this
     * stays a plain substring head and the caller re-parses only [limit]
     * bounded rows. `head_chars` bounds each row's payload at the CURSOR, so a
     * 5.4M-char parts_json costs <= head_chars + overhead per row here — never
     * the full blob.
     */
    @Query("""
        SELECT id, role, created_at,
               substr(parts_json, 1, :headChars) AS head_text
        FROM messages
        WHERE session_id = :sessionId
        ORDER BY sort_order ASC, created_at ASC
        LIMIT :limit
    """)
    suspend fun loadMessageHeads(sessionId: String, headChars: Int, limit: Int): List<MessageHeadRow>

    @Query("""
        SELECT id, role, created_at,
               substr(parts_json, 1, :headChars) AS head_text
        FROM messages
        WHERE session_id = :sessionId
        ORDER BY sort_order DESC, created_at DESC
        LIMIT :limit
    """)
    suspend fun loadMessageHeadsNewest(sessionId: String, headChars: Int, limit: Int): List<MessageHeadRow>

    @Query("""
        SELECT id, role, created_at,
               substr(parts_json, 1, :headChars) AS head_text
        FROM messages
        WHERE session_id = :sessionId AND role = :role
        ORDER BY sort_order ASC, created_at ASC
        LIMIT :limit
    """)
    suspend fun loadMessageHeadsByRole(
        sessionId: String,
        role: String,
        headChars: Int,
        limit: Int,
    ): List<MessageHeadRow>

    @Query("""
        SELECT id, role, created_at,
               substr(parts_json, 1, :headChars) AS head_text
        FROM messages
        WHERE session_id = :sessionId AND role = :role
        ORDER BY sort_order DESC, created_at DESC
        LIMIT :limit
    """)
    suspend fun loadMessageHeadsByRoleNewest(
        sessionId: String,
        role: String,
        headChars: Int,
        limit: Int,
    ): List<MessageHeadRow>

    @Query("""
        SELECT id, role, created_at,
               substr(parts_json,
                      max(1, instr(lower(parts_json), lower(:query)) - :radius),
                      :windowChars) AS head_text
        FROM messages
        WHERE session_id = :sessionId
          AND instr(lower(parts_json), lower(:query)) > 0
        ORDER BY sort_order ASC, created_at ASC
        LIMIT 1
    """)
    suspend fun findFirstMessageSnippet(
        sessionId: String,
        query: String,
        radius: Int,
        windowChars: Int,
    ): MessageHeadRow?

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
