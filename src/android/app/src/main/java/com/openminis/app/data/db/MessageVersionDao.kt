package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** [T-msg-version-archive] DAO for archived message versions. */
@Dao
interface MessageVersionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(versions: List<MessageVersionEntity>)

    /** Highest archived version index for a message; 0 when never archived. */
    @Query("SELECT COALESCE(MAX(version_index), 0) FROM message_versions WHERE message_id = :messageId")
    suspend fun maxVersionIndex(messageId: String): Int

    /**
     * [T-archive-batch-version-index] [maxVersionIndex] for every message of a
     * session in ONE pass. A bulk archive (a regenerate that dooms the whole
     * tail, ~290 rows observed) would otherwise run one query per row inside
     * the archive+truncate transaction.
     */
    @Query(
        "SELECT message_id, MAX(version_index) AS max_index FROM message_versions " +
            "WHERE session_id = :sessionId GROUP BY message_id"
    )
    suspend fun maxVersionIndexesForSession(sessionId: String): List<MessageVersionIndexRow>

    @Query("SELECT * FROM message_versions WHERE message_id = :messageId ORDER BY version_index DESC")
    suspend fun versionsFor(messageId: String): List<MessageVersionEntity>

    @Query("SELECT COUNT(*) FROM message_versions WHERE session_id = :sessionId")
    suspend fun countForSession(sessionId: String): Int

    /**
     * [T-msg-version-restore] All archived versions for a session ordered by
     * source creation time (oldest first). Powers the one-shot restore that
     * re-inserts rows lost to the 2026-10-14 ordinal-mismatch truncation.
     */
    @Query(
        "SELECT * FROM message_versions WHERE session_id = :sessionId " +
            "ORDER BY source_created_at ASC, version_index ASC"
    )
    suspend fun versionsForSession(sessionId: String): List<MessageVersionEntity>

    /**
     * Message ids whose every archived version has no live row left — the
     * candidates a restore may put back. Counts DISTINCT message ids, not
     * version rows: one message can carry several archived versions.
     */
    @Query(
        "SELECT DISTINCT message_id FROM message_versions WHERE session_id = :sessionId " +
            "AND message_id NOT IN (SELECT id FROM messages WHERE session_id = :sessionId)"
    )
    suspend fun restorableMessageIds(sessionId: String): List<String>

    /**
     * Retires specific archive rows after they were restored. NEVER delete by
     * session: [message_versions] doubles as the pre-edit history of live
     * rows, and wiping the session's archive destroyed exactly that history.
     *
     * [T-archive-delete-chunked] Callers pass a CHUNK of ids, not the whole
     * restored set: `IN` binds one SQL variable per id, and devices whose
     * bundled SQLite caps SQLITE_MAX_VARIABLE_NUMBER at 999 (Android 11 and
     * earlier) threw on a long list inside the restore transaction.
     */
    @Query("DELETE FROM message_versions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

/** (message_id, highest archived version_index) — [T-archive-batch-version-index]. */
data class MessageVersionIndexRow(
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "max_index") val maxIndex: Int,
)
