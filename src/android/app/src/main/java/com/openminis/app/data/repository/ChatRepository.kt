package com.openminis.app.data.repository

import androidx.room.withTransaction
import android.database.sqlite.SQLiteBlobTooBigException
import com.openminis.app.data.body.BodyStore
import com.openminis.app.data.body.BudgetDecision
import com.openminis.app.data.body.PreviewBudget
import com.openminis.app.data.body.ResourceLimits
import com.openminis.app.data.display.DisplayParts
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.GoalDao
import com.openminis.app.data.db.SessionGoalEntity
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.db.MessageVersionDao
import com.openminis.app.data.db.MessageVersionEntity
import com.openminis.app.data.db.PersistedMessageStatus
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.SessionWorkspace
import com.openminis.app.sandbox.WorkspaceMover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ChatRepository(
    internal val dao: ChatDao,
    internal val goalDao: GoalDao,
    private val filesDir: File? = null,
    // [T-msg-version-archive] Optional so JVM tests that never truncate keep
    // constructing with the old three-arg shape.
    private val versionDao: MessageVersionDao? = null,
    // [T-archive-truncate-txn] DB handle for the archive+truncate transaction; null → sequential fallback.
    private val db: AppDatabase? = null,
) {
    internal fun searchDao(): com.openminis.app.data.db.ChatSearchDao = checkNotNull(db).chatSearchDao()

    fun observeSessions(): Flow<List<ChatSessionEntity>> = dao.observeSessions()

    suspend fun createSession(
        modelId: String,
        title: String? = null,
        // [T-memory-global-toggle-settings-ui-android] honor the global
        // memory default at row-insert time. Caller (ChatViewModel) reads
        // MemoryGlobalPrefs.isGlobalEnabled and passes the value through
        // here; existing call sites that omit it keep the prior
        // memoryEnabled=1 behavior (legacy default).
        memoryEnabled: Boolean = true,
        permissionMode: String = "ASK",
    ): ChatSessionEntity {
        val now = System.currentTimeMillis()
        val session = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            modelId = modelId,
            createdAt = now,
            updatedAt = now,
            memoryEnabled = if (memoryEnabled) 1 else 0,
            permissionMode = permissionMode,
        )
        dao.insertSession(session)
        return session
    }

    suspend fun getSession(id: String): ChatSessionEntity? = dao.getSession(id)

    suspend fun listSessions(): List<ChatSessionEntity> = dao.listSessions()

    /**
     * [T-android-huge-session-load-oom] Tail-bounded session load for
     * loadSession-style callers. Returns at most [limit] most-recent messages
     * (ASC order) plus the session's total row count, so the caller knows how
     * many older messages exist above the window.
     *
     * Rows are fetched in pages of [pageSize] to keep every underlying query
     * small enough for a CursorWindow (the same invariant as [loadMessagesPage]
     * — Issue #17's SQLiteBlobTooBigException). The pages walk BACKWARD from
     * the newest row and are re-assembled oldest-first.
     */
    data class SessionTail(
        val messages: List<com.openminis.app.data.db.MessageEntity>,
        val totalMessages: Int,
        val firstMessageOffset: Int,
    )

    suspend fun loadSessionTail(
        sessionId: String,
        limit: Int = MAX_TAIL_MESSAGES,
        pageSize: Int = 50,
    ): SessionTail {
        val total = dao.messageCountForSession(sessionId)
        if (total <= 0) return SessionTail(emptyList(), 0, 0)
        return loadMessagesBefore(sessionId, total, limit, pageSize, totalMessages = total)
    }

    /** [T-task-anchor] The earliest user row's raw `parts_json` — see [ChatDao.firstUserPartsJson]. */
    suspend fun firstUserPartsJson(sessionId: String): String? =
        if (sessionId.isEmpty()) null else dao.firstUserPartsJson(sessionId)

    suspend fun loadMessagesBefore(
        sessionId: String,
        endExclusive: Int,
        limit: Int,
        pageSize: Int = 50,
        totalMessages: Int,
    ): SessionTail = loadBoundedMessageRange(
        sessionId = sessionId,
        startInclusive = (endExclusive - limit).coerceAtLeast(0),
        endExclusive = endExclusive.coerceIn(0, totalMessages),
        pageSize = pageSize,
        totalMessages = totalMessages,
        fromNewest = true,
    )

    /**
     * Contiguous rows in a sort_order range. The cursor is the stable sequence,
     * not an offset. Chunked so one huge turn does not open a single cursor
     * over the whole range. Does not skip a row that does not fit a budget:
     * the caller publishes whatever this returns and continues from its edge.
     */
    suspend fun loadMessagesInSortRange(
        sessionId: String,
        startInclusive: Int,
        endExclusive: Int,
    ): List<com.openminis.app.data.db.MessageEntity> {
        require(startInclusive <= endExclusive) { "Invalid sort range: [$startInclusive, $endExclusive)" }
        if (startInclusive == endExclusive) return emptyList()
        val rows = ArrayList<com.openminis.app.data.db.MessageEntity>()
        var cursor = startInclusive
        while (cursor < endExclusive) {
            val page = dao.loadMessagesFromSortOrder(sessionId, cursor, endExclusive, limit = 50)
            if (page.isEmpty()) break
            rows.addAll(page)
            // sort_order is allocated uniquely by appendMessage. The +1
            // advance therefore cannot skip a row and makes gaps harmless.
            val next = page.last().sortOrder + 1
            if (next <= cursor) break
            cursor = next
        }
        return rows
    }

    suspend fun loadMessagesAfter(
        sessionId: String,
        startInclusive: Int,
        limit: Int,
        pageSize: Int = 50,
        totalMessages: Int,
    ): SessionTail = loadBoundedMessageRange(
        sessionId = sessionId,
        startInclusive = startInclusive.coerceIn(0, totalMessages),
        endExclusive = (startInclusive + limit).coerceAtMost(totalMessages),
        pageSize = pageSize,
        totalMessages = totalMessages,
        fromNewest = false,
    )

    private suspend fun loadBoundedMessageRange(
        sessionId: String,
        startInclusive: Int,
        endExclusive: Int,
        pageSize: Int,
        totalMessages: Int,
        fromNewest: Boolean,
    ): SessionTail {
        if (startInclusive >= endExclusive) return SessionTail(emptyList(), totalMessages, startInclusive)
        // Admission is a process-wide concurrency hint, not a statement that the
        // database range is empty. Treating a temporary denial as an empty result
        // made loadSession clear both history edges and permanently strand the
        // middle of long conversations. Keep the local per-row budget below, and
        // continue with this bounded query even when another preview owns the
        // reservation; this preserves a real cursor that the UI can page from.
        val admitted = com.openminis.app.data.body.Admission.tryAdmit(
            ResourceLimits.SESSION_PREVIEW_BUDGET.toLong(),
        )
        try {
            val rows = ArrayList<com.openminis.app.data.db.MessageEntity>()
            var previewBytes = 0L
            var stoppedAtBudget = false
            if (fromNewest) {
                var cursor = endExclusive
                while (cursor > startInclusive && rows.size < endExclusive - startInclusive) {
                    val pageStart = maxOf(startInclusive, cursor - pageSize)
                    val expected = cursor - pageStart
                    val page = dao.loadMessagesPage(sessionId, pageStart, expected)
                    if (page.isEmpty()) break
                    for (index in page.indices.reversed()) {
                        val row = page[index]
                        val rowBytes = row.partsJson.length.toLong() * 2L
                        when (PreviewBudget.decide(
                            previewBytes,
                            rowBytes,
                            ResourceLimits.SESSION_PREVIEW_BUDGET.toLong(),
                            rows.size,
                        )) {
                            BudgetDecision.STOP -> {
                                stoppedAtBudget = true
                                break
                            }
                            BudgetDecision.TAKE_AND_STOP -> {
                                rows.add(row)
                                stoppedAtBudget = true
                                break
                            }
                            BudgetDecision.TAKE -> {
                                rows.add(row)
                                previewBytes += rowBytes
                            }
                        }
                    }
                    cursor = pageStart
                    if (stoppedAtBudget || page.size < expected) break
                }
                val chronological = rows.asReversed()
                return SessionTail(chronological, totalMessages, endExclusive - rows.size)
            }

            var cursor = startInclusive
            while (cursor < endExclusive) {
                val page = dao.loadMessagesPage(sessionId, cursor, minOf(pageSize, endExclusive - cursor))
                if (page.isEmpty()) break
                for (row in page) {
                    val rowBytes = row.partsJson.length.toLong() * 2L
                    when (PreviewBudget.decide(
                        previewBytes,
                        rowBytes,
                        ResourceLimits.SESSION_PREVIEW_BUDGET.toLong(),
                        rows.size,
                    )) {
                        BudgetDecision.STOP -> {
                            stoppedAtBudget = true
                            break
                        }
                        BudgetDecision.TAKE_AND_STOP -> {
                            rows.add(row)
                            stoppedAtBudget = true
                            break
                        }
                        BudgetDecision.TAKE -> {
                            rows.add(row)
                            previewBytes += rowBytes
                        }
                    }
                }
                cursor += page.size
                if (stoppedAtBudget) break
            }
            return SessionTail(rows, totalMessages, startInclusive)
        } finally {
            if (admitted) {
                com.openminis.app.data.body.Admission.release(ResourceLimits.SESSION_PREVIEW_BUDGET.toLong())
            }
        }
    }

    /**
     * Replace projected stubs with complete, display-sized JSON. One body is
     * read at a time and discarded after shrinking. The stored row is unchanged.
     */
    suspend fun hydrateDisplayRows(rows: List<MessageEntity>): List<MessageEntity> {
        if (rows.none { DisplayParts.needsHydration(it.bodyRef, it.bodyBytes, it.partsJson) }) {
            return rows
        }
        val store = filesDir?.let { BodyStore(File(it, "bodies")) }
        return rows.map { row ->
            if (!DisplayParts.needsHydration(row.bodyRef, row.bodyBytes, row.partsJson)) {
                row
            } else {
                val raw = readDisplayBody(row, store)
                val parts = if (raw.isNullOrBlank()) {
                    DisplayParts.note("这条消息的原文在本地，但这次没能读出来。")
                } else {
                    DisplayParts.shrink(raw)
                }
                if (parts == row.partsJson) row else row.copy(partsJson = parts)
            }
        }
    }

    private suspend fun readDisplayBody(row: MessageEntity, store: BodyStore?): String? {
        val ref = row.bodyRef
        if (!ref.isNullOrBlank() && store != null) {
            val bytes = runCatching {
                store.read(ref, maxBytes = ResourceLimits.MAX_DECLARED_UNCOMPRESSED)
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return bytes.toString(Charsets.UTF_8)
        }
        return runCatching { dao.loadRawPartsJson(row.id) }.getOrNull()
    }

    suspend fun loadMessageIds(sessionId: String, pageSize: Int = 500): Set<String> {
        val total = dao.messageCountForSession(sessionId)
        val ids = HashSet<String>(total.coerceAtLeast(16))
        var offset = 0
        while (offset < total) {
            val page = dao.loadMessageIdsPage(sessionId, offset, pageSize)
            if (page.isEmpty()) break
            ids.addAll(page)
            offset += page.size
        }
        return ids
    }

    suspend fun loadMessagesTail(sessionId: String, limit: Int): List<com.openminis.app.data.db.MessageEntity> =
        loadSessionTail(sessionId, limit = limit).messages

    /**
     * Request-side transcript rows. Unlike [loadSessionTail], this bypasses the
     * SAFE_MESSAGE_FROM projection and never applies DisplayParts.shrink: the
     * model must receive the persisted body, while the UI may receive a compact
     * rendering projection of the same row.
     */
    suspend fun loadRequestHistory(
        sessionId: String,
        limit: Int,
    ): List<com.openminis.app.data.db.MessageEntity> {
        val total = dao.messageCountForSession(sessionId)
        if (total <= 0) return emptyList()
        val start = (total - limit).coerceAtLeast(0)
        val pageSize = 50
        val store = filesDir?.let { BodyStore(File(it, "bodies")) }
        val rows = ArrayList<com.openminis.app.data.db.MessageEntity>(limit.coerceAtMost(total))
        var offset = start
        while (offset < total && rows.size < limit) {
            val page = dao.loadMessagesTailRaw(
                sessionId,
                minOf(pageSize, limit - rows.size),
                offset,
            )
            if (page.isEmpty()) break
            for (row in page) {
                val raw = if (row.bodyRef.isNullOrBlank()) null else readDisplayBody(row, store)
                rows.add(if (raw.isNullOrBlank() || raw == row.partsJson) row else row.copy(partsJson = raw))
            }
            offset += page.size
        }
        return rows
    }

    /**
     * [T-android-huge-session-load-oom] Bounded heads for title generation,
     * content snippets and evolution harvest. Each row carries at most
     * [headChars] characters of parts_json (SQL-side substr), so a 5.4M-char
     * session costs a few KB here instead of materialising every 500KB blob.
     */
    suspend fun loadMessageHeads(
        sessionId: String,
        headChars: Int = 800,
        limit: Int = 32,
    ): List<com.openminis.app.data.db.MessageHeadRow> =
        dao.loadMessageHeads(sessionId, headChars, limit)

    suspend fun loadMessageHeadsByRole(
        sessionId: String,
        role: String,
        headChars: Int = 800,
        limit: Int = 32,
        newest: Boolean = false,
    ): List<com.openminis.app.data.db.MessageHeadRow> = if (newest) {
        dao.loadMessageHeadsByRoleNewest(sessionId, role, headChars, limit)
    } else {
        dao.loadMessageHeadsByRole(sessionId, role, headChars, limit)
    }

    /** All persisted token_usage JSON strings for a session (one per LLM call). */
    suspend fun sessionTokenUsages(sessionId: String): List<String> = dao.tokenUsages(sessionId)

    /**
     * [T-android-session-paused-badge-hardkill] Session ids whose agent loop was
     * left interrupted, derived purely from the persisted message tail — so the
     * PAUSED badge survives a hard process death (where the lifecycle-callback
     * push never runs). Lightweight: one query for the last message per session,
     * then the SAME interrupted-tail predicate as ChatViewModel.loadSession's
     * detection (kept in sync intentionally). Mirrors iOS
     * ChatStore.interruptedSessionIds.
     */
    suspend fun interruptedSessionIds(): Set<String> {
        val tails = runCatching { dao.lastMessageTailPerSession() }.getOrElse { emptyList() }
        val result = HashSet<String>()
        for (row in tails) {
            if (isInterruptedTail(row.role, row.partsJson)) result.add(row.sessionId)
        }
        return result
    }

    /**
     * [T-android-session-paused-badge-hardkill] The interrupted-tail predicate
     * over a raw `parts_json` string, matching ChatViewModel.loadSession's
     * AgentContentPart-based logic:
     *   - role USER + ALL parts are tool_result (tools ran, next model call never
     *     fired), OR the single synthetic "Continue" reminder text part, OR
     *   - role ASSISTANT + any tool_use part (model asked for tools that never ran)
     * Part type discriminator is the JSON "type" field — the @SerialName values
     * from [com.openminis.app.data.model.ContentPart]: "toolUse" / "toolResult"
     * / "text" (camelCase, NOT snake_case); text payload is the "value" field.
     */
    private fun isInterruptedTail(role: String, partsJson: String): Boolean {
        val arr = runCatching { org.json.JSONArray(partsJson) }.getOrNull() ?: return false
        val types = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { types.add(it.optString("type")) }
        }
        return when (role.uppercase()) {
            "USER" -> {
                val allToolResults = types.isNotEmpty() && types.all { it == "toolResult" }
                val isContinueReminder = arr.length() == 1 &&
                    arr.optJSONObject(0)?.takeIf { it.optString("type") == "text" }
                        ?.optString("value")
                        ?.contains("The user stopped the previous response") == true
                allToolResults || isContinueReminder
            }
            "ASSISTANT" -> types.any { it == "toolUse" }
            else -> false
        }
    }

    suspend fun updateSessionTitle(id: String, title: String) {
        dao.updateSessionTitle(id, title, System.currentTimeMillis())
    }

    suspend fun updateSessionTitleAndCategory(id: String, title: String, category: String?) {
        dao.updateSessionTitleAndCategory(id, title, category, System.currentTimeMillis())
    }

    suspend fun updateSessionModel(sessionId: String, modelId: String) {
        dao.updateSessionModel(sessionId, modelId)
    }

    suspend fun updateSessionBinding(sessionId: String, binding: String, modelId: String) {
        dao.updateSessionBinding(sessionId, binding, modelId)
    }

    suspend fun deleteSession(id: String) {
        dao.deleteMessages(id)
        goalDao.delete(id)
        dao.deleteSession(id)
        val dir = filesDir
        if (dir != null) {
            runCatching { ExecutionCoordinator.sessionDidTerminate(id) }
            SessionWorkspace.deleteEntire(dir, id)
        }
    }

    // ─── Session groups ("folders") ────────────────────────────────────────
    // [T-android-session-grouping] Ported from iOS ChatStore's Folders section.
    // Code says Folder, UI says Group — see FolderEntity for why.

    fun observeFolders(): Flow<List<FolderEntity>> = dao.observeFolders()

    suspend fun listFolders(): List<FolderEntity> = dao.listFolders()

    suspend fun getFolder(id: String): FolderEntity? = dao.getFolder(id)

    /**
     * Create a group. The description is trimmed and capped at
     * [FolderEntity.DESC_MAX_CHARS]; blank collapses to null so "no
     * description" is one value rather than two.
     */
    suspend fun createFolder(
        name: String,
        description: String? = null,
        origin: String = FolderEntity.ORIGIN_MANUAL,
    ): FolderEntity {
        val now = System.currentTimeMillis()
        val folder = FolderEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            origin = origin,
            description = description?.trim()?.take(FolderEntity.DESC_MAX_CHARS)?.ifBlank { null },
            createdAt = now,
            updatedAt = now,
        )
        dao.insertFolder(folder)
        filesDir?.let { SessionWorkspace.ensureProjectDirs(it, folder.id) }
        return folder
    }

    suspend fun ensureDefaultWorkspace(name: String): FolderEntity {
        dao.getFolder(FolderEntity.DEFAULT_ID)?.let { return it }
        val now = System.currentTimeMillis()
        val folder = FolderEntity(
            id = FolderEntity.DEFAULT_ID,
            name = name.trim().ifEmpty { "Workspace" },
            origin = FolderEntity.ORIGIN_MANUAL,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertFolder(folder)
        filesDir?.let { SessionWorkspace.ensureProjectDirs(it, folder.id) }
        return folder
    }

    suspend fun warmupWorkspaceOwners() {
        // [T-android-startup-splash-hang] Two-column projection over FILED
        // sessions only, replacing `SELECT * FROM sessions ORDER BY updated_at
        // DESC` + full entity hydration. See SessionFolderId for why unfiled
        // rows are skipped without changing behaviour.
        try {
            for (row in dao.listSessionFolderIds()) {
                SessionWorkspace.rememberFolder(row.id, row.folderId)
            }
        } finally {
            // Always release the gate: a failed warmup must degrade to "this
            // session looks unfiled", never wedge every shell behind an await
            // that can never complete.
            SessionWorkspace.completeWarmup()
        }
    }

    /**
     * Every launch: if a session is filed in a project but still holds private
     * copies of the shared subdirs (the 1.36.14 partial-filing leftover), move
     * those copies into the project. Idempotent; unfiled sessions are skipped.
     */
    suspend fun reconcileWorkspaceFiles() {
        val dir = filesDir ?: return
        val sessions = dao.listSessions()
        withContext(Dispatchers.IO) {
            for (s in sessions) {
                val target = s.folderId ?: continue
                runCatching {
                    WorkspaceMover.moveSessionIntoProject(dir, s.id, target)
                }.onFailure {
                    AppLogger.warning(TAG, "reconcile failed for ${s.id}: ${it.message}")
                }
            }
        }
    }

    /**
     * Rename / re-describe. The UUID key is untouched, so members never move —
     * that is the whole reason the group is keyed by UUID and not by name.
     *
     * @param description null LEAVES the stored value alone; an empty string
     *   clears it. Callers that always pass the field through (e.g. a rename
     *   dialog) must therefore seed the field from the current value, or they
     *   will wipe descriptions they never meant to touch.
     */
    suspend fun renameFolder(id: String, name: String, description: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        dao.renameFolder(
            id = id,
            name = trimmed,
            description = description?.trim()?.take(FolderEntity.DESC_MAX_CHARS),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** @return the new pinned state. Bumps `updated_at` so the edit is stamped. */
    suspend fun toggleFolderPin(id: String): Boolean {
        val current = dao.getFolder(id) ?: return false
        val nowPinned = current.pinnedAt == null
        val now = System.currentTimeMillis()
        dao.setFolderPinned(id, if (nowPinned) now else null, now)
        return nowPinned
    }

    /**
     * Dissolve a group: drop the group row and return its members to ungrouped.
     * **No session is deleted** — this is the ONLY delete operation on a group,
     * and it can never cost the user a conversation.
     *
     * Members are read BEFORE the clear because the ids are the return value
     * (callers use them to refresh, and a future sync layer would need them to
     * push each freed session).
     *
     * @return ids of the sessions that became ungrouped.
     */
    suspend fun dissolveFolder(id: String): List<String> {
        val memberIds = dao.sessionIdsInFolder(id)
        dao.clearFolderForSessions(id)
        dao.deleteFolder(id)
        for (sid in memberIds) SessionWorkspace.rememberFolder(sid, null)
        val dir = filesDir
        if (dir != null && memberIds.isNotEmpty()) {
            copyBackAndMaybeDropProjects(dir, memberIds.associateWith { id })
        }
        return memberIds
    }

    suspend fun sessionIdsInFolder(folderId: String): List<String> = dao.sessionIdsInFolder(folderId)

    /**
     * Move sessions into a group, or out of one when [folderId] is null.
     *
     * Writes only `folder_id`, never `updated_at` — filing is organizational
     * and must not re-sort the session list.
     */
    suspend fun setFolderForSessions(folderId: String?, sessionIds: List<String>) {
        if (sessionIds.isEmpty()) return
        val leavingProject = if (folderId == null) {
            sessionIds.associateWith { SessionWorkspace.folderIdFor(it) }
                .mapNotNull { (sid, fid) -> fid?.let { sid to it } }
                .toMap()
        } else {
            emptyMap()
        }
        for (sid in sessionIds) {
            dao.setSessionFolder(sid, folderId)
            SessionWorkspace.rememberFolder(sid, folderId)
        }
        val dir = filesDir ?: return
        if (folderId != null) {
            SessionWorkspace.ensureProjectDirs(dir, folderId)
            withContext(Dispatchers.IO) {
                for (sid in sessionIds) {
                    runCatching {
                        WorkspaceMover.moveSessionIntoProject(dir, sid, folderId)
                    }.onFailure {
                        AppLogger.warning(TAG, "file-move failed for $sid: ${it.message}")
                    }
                }
            }
        } else {
            copyBackAndMaybeDropProjects(dir, leavingProject)
        }
    }

    /**
     * File a session only if it is still ungrouped. The condition is part of the
     * UPDATE, so a hand-filed session can never be overridden by an automatic
     * write racing it.
     *
     * @return true if this call actually filed the session.
     */
    suspend fun setFolderIfUnfiled(folderId: String, sessionId: String): Boolean {
        val n = dao.setSessionFolderIfUnfiled(sessionId, folderId)
        if (n > 0) {
            SessionWorkspace.rememberFolder(sessionId, folderId)
            val d = filesDir
            if (d != null) {
                SessionWorkspace.ensureProjectDirs(d, folderId)
                withContext(Dispatchers.IO) {
                    runCatching {
                        WorkspaceMover.moveSessionIntoProject(d, sessionId, folderId)
                    }.onFailure {
                        AppLogger.warning(TAG, "file-move failed for $sessionId: ${it.message}")
                    }
                }
            }
        }
        return n > 0
    }

    /**
     * Copy a project's shared files into each leaving session's private dir.
     * Drop the project tree only when no remaining member exists AND every
     * leaver copied back without refusal, exception, or conflict. A conflict
     * means the private subdir already had files, so deleting the project
     * would throw away the shared copy.
     */
    private suspend fun copyBackAndMaybeDropProjects(
        filesDir: File,
        leavingProject: Map<String, String>,
    ) {
        if (leavingProject.isEmpty()) return
        withContext(Dispatchers.IO) {
            val independent = mutableSetOf<String>()
            for ((sid, oldProject) in leavingProject) {
                val result = runCatching {
                    WorkspaceMover.copyProjectToSession(filesDir, sid, oldProject)
                }.onFailure {
                    AppLogger.warning(TAG, "copy-back failed for $sid: ${it.message}")
                }.getOrNull()
                if (result != null && result.independent) independent.add(sid)
            }
            for (proj in leavingProject.values.toSet()) {
                val remaining = dao.sessionIdsInFolder(proj)
                if (remaining.isNotEmpty()) continue
                val leavers = leavingProject.filterValues { it == proj }.keys
                val blocked = leavers - independent
                if (blocked.isNotEmpty()) {
                    AppLogger.info(
                        TAG,
                        "keeping project $proj; copy-back incomplete for $blocked",
                    )
                    continue
                }
                if (SessionWorkspace.deleteProject(filesDir, proj)) {
                    AppLogger.info(TAG, "dropped empty project $proj after copy-back")
                }
            }
        }
    }

    /**
     * Name → group, case- and whitespace-insensitive. Duplicate-tolerant by
     * construction (names are not unique); returns the most recently updated
     * match, which is what [listFolders]' ordering already puts first.
     */
    suspend fun findFolderByName(name: String): FolderEntity? {
        val needle = name.trim().lowercase()
        if (needle.isEmpty()) return null
        return dao.listFolders().firstOrNull { it.name.trim().lowercase() == needle }
    }

    suspend fun searchSessions(query: String): List<ChatSessionEntity> =
        searchDao().searchSessions("%$query%")

    fun observeMessages(sessionId: String): Flow<List<MessageEntity>> =
        dao.observeMessages(sessionId)

    /**
     * Load all messages for a session in bounded pages instead of a
     * single SELECT * batch. The legacy `dao.loadMessages` path issued
     * one query whose Cursor result, once materialised, easily exceeded
     * the per-CursorWindow 2 MB ceiling on a session containing even one
     * large tool_result blob (Issue #17) — Android then aborted with
     * SQLiteBlobTooBigException and the chat loader hung the UI thread.
     *
     * This paginated loader keeps each underlying query small enough that
     * the CursorWindow can hold a normal-shaped page. If a single page
     * still contains an individual >2MB row we fall back to fetching
     * that range row-by-row and substitute a proxy MessageEntity for
     * any single row that genuinely can't be materialised — the
     * transcript stays continuous instead of crashing the load.
     *
     * Existing oversized rows are not migrated; new oversized inserts
     * are prevented by the cap in [appendMessage].
     */
    suspend fun loadMessages(sessionId: String): List<MessageEntity> {
        // T-android-crash-safe-mode-v2: defensive guard. ChatViewModel.loadSession
        // is already gated upstream, but loadMessages has other call sites
        // (compaction, fork, regenerate-title, debug menu) that could fire
        // from a foreground retry or a Flow collector before the safe-mode
        // dialog is dismissed. Returning an empty list mirrors the "no rows
        // for this session" branch and is harmless for every caller.
        if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            android.util.Log.w(
                "ChatRepository",
                "loadMessages: safe-mode active, skipping (sessionId=$sessionId)",
            )
            return emptyList()
        }
        throw IllegalStateException("Unbounded session loading is disabled; page through loadMessagesTail or forEachMessagePage.")
        val total = dao.messageCountForSession(sessionId)
        if (total == 0) return emptyList()
        val out = ArrayList<MessageEntity>(total)
        var offset = 0
        while (offset < total) {
            val limit = LOAD_PAGE_SIZE
            val page = try {
                dao.loadMessagesPage(sessionId, offset, limit)
            } catch (e: SQLiteBlobTooBigException) {
                // Fall back to single-row pages so we can isolate the
                // offending blob(s) and serve the rest of the slice.
                loadPageRowByRow(sessionId, offset, limit)
            } catch (e: IllegalStateException) {
                // Some Room/SQLite combinations wrap the CursorWindow
                // overflow in IllegalStateException("Couldn't read row N,
                // col N from CursorWindow"); treat the same way.
                if (e.message?.contains("CursorWindow", ignoreCase = true) == true) {
                    loadPageRowByRow(sessionId, offset, limit)
                } else {
                    throw e
                }
            }
            if (page.isEmpty()) break
            out.addAll(page)
            offset += limit
        }
        return out
    }

    private suspend fun loadPageRowByRow(
        sessionId: String,
        baseOffset: Int,
        limit: Int,
    ): List<MessageEntity> {
        val result = ArrayList<MessageEntity>(limit)
        for (i in 0 until limit) {
            val row = try {
                dao.loadMessagesPage(sessionId, baseOffset + i, 1).firstOrNull()
            } catch (e: SQLiteBlobTooBigException) {
                null
            } catch (e: IllegalStateException) {
                if (e.message?.contains("CursorWindow", ignoreCase = true) == true) null else throw e
            } ?: continue
            result.add(row)
        }
        return result
    }

    suspend fun deleteMessagesAfter(sessionId: String, keepCount: Int) {
        // [T-msg-version-archive] Snapshot the doomed rows into
        // message_versions BEFORE deleting them — re-generating a reply must
        // archive the old version, not destroy it (cuplivo parity).
        // [T-archive-truncate-txn] Archiving and truncation run in ONE Room
        // transaction, and an archiving failure ABORTS the truncation. The
        // old code logged "delete proceeds" and dropped the messages anyway
        // — exactly the data loss the archive was built to prevent. (A row
        // too large for the CursorWindow makes rowsFrom throw; the safe
        // outcome there is a kept message, not an unarchived delete.)
        val vdao = versionDao
        if (vdao == null) {
            dao.deleteMessagesAfter(sessionId, keepCount)
            return
        }
        val archived = runCatching { archiveVersionsFor(vdao, sessionId, keepCount) }
        val versions = archived.getOrNull()
        if (versions == null) {
            AppLogger.error(
                "ChatRepository",
                "[T-msg-version-archive] archiving failed; truncation ABORTED so no message is lost without its archive: " +
                    "${archived.exceptionOrNull()?.message}",
            )
            return
        }
        val dbRef = db
        if (dbRef != null) {
            dbRef.withTransaction {
                if (versions.isNotEmpty()) vdao.insertAll(versions)
                dao.deleteMessagesAfter(sessionId, keepCount)
            }
        } else {
            if (versions.isNotEmpty()) vdao.insertAll(versions)
            dao.deleteMessagesAfter(sessionId, keepCount)
        }
    }

    private suspend fun archiveVersionsFor(
        vdao: MessageVersionDao,
        sessionId: String,
        keepCount: Int,
    ): List<MessageVersionEntity> {
        val doomed = dao.rowsFrom(sessionId, keepCount)
        if (doomed.isEmpty()) return emptyList()
        val nowMs = System.currentTimeMillis()
        // [T-archive-batch-version-index] ONE grouped MAX instead of one
        // `SELECT MAX(version_index) … WHERE message_id = ?` per doomed row: a
        // regenerate-from-turn-1 truncation archives the whole tail (~290 rows
        // observed) and ran ~290 individual queries inside the single
        // archive+truncate transaction.
        //
        // Scoping by session_id is equivalent to the old global lookup: a
        // version row's session_id is copied from its source message, a message
        // id belongs to exactly one session (session forks mint new ids, a
        // backup re-import writes the same id back into the same session), and
        // ids are random UUIDs. `doomed` is read off `messages`, whose PK is the
        // id, so each message appears once and the per-message counter needs no
        // in-batch bumping.
        val baseIndexes = vdao.maxVersionIndexesForSession(sessionId)
            .associate { it.messageId to it.maxIndex }
        return doomed.map { it.toVersionEntity(baseIndexes[it.id] ?: 0, nowMs) }
    }

    /**
     * Single-row archive with a fresh lookup — the trimmed-row snapshot in
     * [updateMessageParts], where there is no batch to amortise.
     */
    private suspend fun MessageEntity.toVersionEntity(vdao: MessageVersionDao, nowMs: Long): MessageVersionEntity =
        toVersionEntity(vdao.maxVersionIndex(id), nowMs)

    /** [baseVersionIndex] is the message's current highest archived version. */
    private fun MessageEntity.toVersionEntity(baseVersionIndex: Int, nowMs: Long): MessageVersionEntity =
        MessageVersionEntity(
            id = UUID.randomUUID().toString(),
            messageId = id,
            sessionId = sessionId,
            versionIndex = baseVersionIndex + 1,
            role = role,
            partsJson = partsJson,
            bodyRef = bodyRef,
            bodySha = bodySha,
            reasoningContent = reasoningContent,
            errorInfo = errorInfo,
            modelId = modelId,
            modelDisplayName = modelDisplayName,
            sourceCreatedAt = createdAt,
            archivedAt = nowMs,
        )

    /**
     * How many deleted messages the local archive could still put back
     * (messages with archived versions and no live row). Powers the manual
     * restore confirmation.
     */
    suspend fun restorableArchivedMessageCount(sessionId: String): Int {
        val vdao = versionDao ?: return 0
        return runCatching { vdao.restorableMessageIds(sessionId).size }.getOrDefault(0)
    }

    /**
     * [T-msg-version-restore] Recovery for rows lost to a truncation whose
     * cutoff landed too early (the 2026-10-14 ordinal-mismatch incident:
     * 1386 rows archived then deleted from one retry tap). Re-inserts every
     * archived version whose source row no longer exists in `messages`.
     *
     * Contract:
     *  - CHRONOLOGY is rebuilt, not appended: restored rows slot back where
     *    their created_at places them (see [chronologicalRestoreOrder]), so
     *    old messages never land after messages the user typed since.
     *  - Only the RESTORED archive rows are retired afterwards. The session's
     *    other versions are the pre-edit history of LIVE rows — the reason
     *    the archive exists — and deleting the whole session archive here
     *    (the old behaviour) destroyed exactly that history.
     *  - INVOKED EXPLICITLY by the user ("restore deleted messages"), never
     *    automatically on session load: a truncation is also how a retry or
     *    an intentional delete discards content, and silently resurrecting it
     *    would overrule the user. The confirmation dialog states what the
     *    archive still holds.
     *
     * Only LATEST versions are restored (highest version_index per
     * message_id): earlier versions are pre-edit history, not rows to
     * resurrect. Rows whose message_id still exists live are skipped, so a
     * repeat call after the first restore is a no-op.
     */
    suspend fun restoreArchivedVersions(sessionId: String): RestoreResult {
        val vdao = versionDao ?: return RestoreResult(0, 0, "versionDao unavailable")
        val all = runCatching { vdao.versionsForSession(sessionId) }.getOrNull()
            ?: return RestoreResult(0, 0, "versionsForSession failed")
        if (all.isEmpty()) return RestoreResult(0, 0, null)

        // Latest version per message_id.
        val latest = all.groupBy { it.messageId }
            .mapValues { (_, versions) -> versions.maxBy { it.versionIndex } }
            .values
            .sortedBy { it.sourceCreatedAt }

        // Skip message_ids that still exist live.
        val live = dao.messageCountForSession(sessionId)
        val existingIds = if (live > 0) {
            runCatching { loadMessageIds(sessionId) }.getOrDefault(emptySet())
        } else emptySet()

        val toRestore = latest.filter { it.messageId !in existingIds }
        if (toRestore.isEmpty()) return RestoreResult(0, all.size, null)

        // [T-msg-version-restore] Re-insert by CHRONOLOGY, not at the tail.
        // A truncation removes a span from the middle; appending restored rows
        // after everything the user typed since reads as old messages arriving
        // after new ones. Rebuild the sequence from (created_at, live-first)
        // order and renumber in one transaction: two passes over the live rows
        // (first to a scratch range, then into the final slot) so the unique
        // (session_id, sort_order) index never sees two rows claiming the same
        // slot mid-flight.
        val restoredById = toRestore.associateBy { it.messageId }
        val restoredArchiveIds = toRestore.map { it.id }
        val apply: suspend () -> List<MessageEntity> = {
            val meta = dao.sortMetaForSession(sessionId)
            val order = chronologicalRestoreOrder(
                existing = meta.map { SortEntry(it.id, it.createdAt) },
                restored = toRestore.map {
                    SortEntry(it.messageId, it.sourceCreatedAt, restored = true)
                },
            )
            val scratchBase = order.size + 1_000_000
            meta.forEachIndexed { index, row ->
                dao.updateSortOrder(row.id, scratchBase + index)
            }
            val restoredRows = mutableListOf<MessageEntity>()
            order.forEachIndexed { index, entry ->
                if (entry.restored) {
                    val v = restoredById.getValue(entry.id)
                    // [T-restore-preview-column] `preview` is re-derived with
                    // the SAME truncation the normal write path applies
                    // (storeBody: partsJson.take(PREVIEW_BYTES)). Left NULL, the
                    // restored rows fall back to `substr(COALESCE(preview,
                    // parts_json), 1, 65536)` in the search/digest projections —
                    // the 64 KB cell scan the column exists to avoid, on the
                    // very rows this restore touches in bulk (1386 in the
                    // 2026-10-14 incident). Bodies still render either way:
                    // inline parts and bodyRef hydration are untouched.
                    val storedParts = v.partsJson ?: "[]"
                    val row = MessageEntity(
                        id = v.messageId,
                        sessionId = sessionId,
                        role = v.role,
                        partsJson = storedParts,
                        createdAt = v.sourceCreatedAt,
                        sortOrder = index,
                        reasoningContent = v.reasoningContent,
                        errorInfo = v.errorInfo,
                        modelId = v.modelId,
                        modelDisplayName = v.modelDisplayName,
                        bodyRef = v.bodyRef,
                        bodySha = v.bodySha,
                        preview = storedParts.take(ResourceLimits.PREVIEW_BYTES),
                        // UTF-8 size of the stored cell, same definition as
                        // storeBody. Offloaded rows report 0 rather than the
                        // stub's size: message_versions snapshots the row's
                        // `parts_json` column, and the original body length is
                        // not among the archived columns (adding it would be a
                        // schema migration). Hydration is unaffected — it keys
                        // off bodyRef, not this number.
                        bodyBytes = if (v.bodyRef == null) {
                            storedParts.toByteArray(Charsets.UTF_8).size.toLong()
                        } else 0L,
                    )
                    dao.insertMessage(row)
                    restoredRows.add(row)
                } else {
                    dao.updateSortOrder(entry.id, index)
                }
            }
            // Retire ONLY the restored archive rows — the session's remaining
            // versions are live pre-edit history and must survive.
            //
            // [T-archive-delete-chunked] One chunked DELETE, not a single
            // `IN (:ids)`: `IN` binds one SQL variable per id and this is the
            // recovery path for exactly the loss scale where the id list is
            // long (1386 rows in the 2026-10-14 truncation incident). Devices
            // whose bundled SQLite caps SQLITE_MAX_VARIABLE_NUMBER at 999
            // (Android 11 and earlier) threw here INSIDE db.withTransaction, so
            // the whole restore rolled back and the exception escaped the
            // caller's `viewModelScope.launch` uncaught. 500 sits under the
            // smallest cap we ship against.
            restoredArchiveIds.chunked(ARCHIVE_DELETE_CHUNK).forEach { vdao.deleteByIds(it) }
            restoredRows
        }
        val dbRef = db
        val restored = if (dbRef != null) dbRef.withTransaction { apply() } else apply()
        AppLogger.info(
            TAG,
            "[T-msg-version-restore] restored ${restored.size} rows to session " +
                "${sessionId.take(8)} (archive had ${all.size}, skipped ${latest.size - toRestore.size} live)",
        )
        return RestoreResult(restored.size, all.size, null)
    }

    data class RestoreResult(
        val restoredCount: Int,
        val archiveCount: Int,
        val error: String?,
    )

    /**
     * One slot in a restored transcript. [restored] rows come from the
     * archive; the rest are live rows being renumbered around them.
     */
    internal data class SortEntry(
        val id: String,
        val createdAt: Long,
        val restored: Boolean = false,
    )

    /**
     * Rewrite a single message row's parts_json in place. Used by
     * [com.openminis.app.ui.chat.ChatViewModel.rerunFromToolBlock]'s block-
     * boundary cut to trim the kept assistant row to the parts before the
     * target tool_use. Mirrors iOS ChatStore.updateMessageParts.
     */
    suspend fun updateMessageParts(id: String, partsJson: String) {
        val meta = dao.bodyMeta(id) ?: return
        // [T-archive-trimmed-parts] The rerun-from-tool-block cut rewrites the
        // kept assistant row IN PLACE; the pre-cut parts sit below the
        // truncation cutoff, so deleteMessagesAfter never archives them.
        // Snapshot them here, and abort the rewrite when archiving fails —
        // a failed archive must not turn into silent data loss.
        versionDao?.let { vdao ->
            runCatching {
                dao.messageById(id)?.let { row ->
                    vdao.insertAll(listOf(row.toVersionEntity(vdao, System.currentTimeMillis())))
                }
            }.onFailure {
                AppLogger.error(
                    "ChatRepository",
                    "[T-msg-version-archive] archiving the trimmed row failed; rewrite ABORTED: ${it.message}",
                )
                throw it
            }
        }
        val stored = storeBody(partsJson, requireOffload = partsJson.toByteArray(Charsets.UTF_8).size > ResourceLimits.INLINE_BODY_BYTES)
        if (stored.ref == null && stored.bodyBytes > ResourceLimits.INLINE_BODY_BYTES) {
            throw IllegalStateException("Unable to store oversized message body")
        }
        // Publish the DB pointer only after BodyStore has durably committed the file.
        dao.updateMessageBody(
            id = id,
            partsJson = stored.inline,
            bodyBytes = stored.bodyBytes,
            bodyRef = stored.ref,
            bodySha = stored.sha,
            preview = stored.preview,
        )
    }

    /** [T-error-persist-android] Set/clear the error sticker on a row by id. */
    suspend fun updateMessageErrorInfo(messageId: String, errorInfo: String?) =
        dao.updateMessageErrorInfo(messageId, errorInfo)

    /**
     * [T-error-persist-android] Set/clear the error sticker on a session's last
     * assistant row. See [ChatDao.updateLastAssistantError]. No-op when no
     * assistant row exists yet.
     */
    suspend fun updateLastAssistantError(sessionId: String, errorInfo: String?) =
        dao.updateLastAssistantError(sessionId, errorInfo)

    suspend fun lastAssistantMessageId(sessionId: String): String? =
        dao.lastAssistantMessageId(sessionId)

    suspend fun goal(sessionId: String): SessionGoalEntity? = goalDao.get(sessionId)

    suspend fun createGoal(goal: SessionGoalEntity) = goalDao.insert(goal)
    suspend fun saveGoal(goal: SessionGoalEntity) = goalDao.saveState(
        sessionId = goal.sessionId,
        objective = goal.objective,
        status = goal.status,
        tokenBudget = goal.tokenBudget,
        tokensUsed = goal.tokensUsed,
        elapsedMs = goal.elapsedMs,
        blockedCondition = goal.blockedCondition,
        blockedCount = goal.blockedCount,
        pausedAt = goal.pausedAt,
        updatedAt = goal.updatedAt,
    )

    /**
     * [T-token-attribution-snapshot] `modelSnapshot` records which model
     * ACTUALLY produced this message.
     *
     * It must be supplied by the caller from the request context — do NOT
     * resolve it in here by reading the session. The session's `model_id` is
     * rewritten on every switch, including automatic failover, and by the time
     * a turn finishes it may already point at a different model than the one
     * that served it. Reading it here would reproduce the exact bug this
     * snapshot exists to fix, only scoped to one row instead of the whole
     * session.
     */
    suspend fun appendMessage(
        sessionId: String,
        role: String,
        partsJson: String,
        tokenUsage: String? = null,
        reasoningContent: String? = null,
        modelSnapshot: ModelAttributionSnapshot? = null,
        errorInfo: String? = null,
    ): MessageEntity {
        val stored = storeBody(partsJson)
        return run {
            val now = System.currentTimeMillis()
        // Cap the body so a runaway tool_result (e.g. a 13 MB browser_use
        // dump — Issue #17) cannot land an oversize blob into a Room row
        // that later fails CursorWindow's 2 MB ceiling on read. We keep
        // the row in the same parts_json shape (text part) so downstream
        // parsers — UI rendering and JSON-array consumers in DAO/search
        // — never break on the truncated payload.
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = role,
            partsJson = stored.inline,
            errorInfo = errorInfo,
            bodyBytes = stored.bodyBytes,
            bodyRef = stored.ref,
            bodySha = stored.sha,
            preview = stored.preview,
            createdAt = now,
            tokenUsage = tokenUsage,
            sortOrder = 0,
            reasoningContent = reasoningContent,
            modelId = modelSnapshot?.modelId,
            modelDisplayName = modelSnapshot?.displayName,
            providerType = modelSnapshot?.providerTypeRaw,
            providerInstanceId = modelSnapshot?.providerInstanceId,
        )
        val inserted = dao.appendMessage(message)
        // [T-android-preview-flicker-toolresult] Only overwrite the preview
        // when this row actually yields one. A tool-result row is
        // `[{"type":"toolResult",…}]`, a shape extractTextPreview does not
        // summarize (it handles text / mediaRef / toolUse), so it returns
        // null — and writing that null blanked the column, flipping the
        // session list to "No messages yet" the instant a tool finished. The
        // live preview pushed before the tool ran had just put the tool title
        // there, so a multi-tool run visibly oscillated between the title and
        // the empty state on every tool boundary.
        //
        // The row's own timestamp is still worth recording: it is what keeps
        // the session sorted as recently-active while a long tool chain runs.
        val preview = extractTextPreview(stored.inline)
        if (preview != null) {
            dao.updateLastMessage(sessionId, preview, now)
        } else {
            dao.touchSession(sessionId, now)
        }
        return inserted
        }
    }

    /**
     * [T-android-session-last-message-live-tool-call] Update ONLY the session's
     * `last_message` preview (and `updated_at`) from an in-progress assistant
     * turn's parts_json — WITHOUT inserting a message row. The agent loop
     * persists the authoritative assistant row only at turn end (after tools
     * execute); during a long tool call the session list would otherwise show a
     * stale preview (or "No messages yet" for a turn with no prior text). This
     * pushes the live tool-call summary / partial text into the list the moment
     * the model emits it, mirroring how iOS overlays the live VM's last message.
     *
     * Uses the same [extractTextPreview] as [appendMessage], so a text-only turn
     * shows its text and a tool-only turn shows the tool summary. No-op when the
     * payload yields no preview (avoids overwriting a good preview with null).
     */
    suspend fun updateSessionPreview(sessionId: String, partsJson: String) {
        val preview = extractTextPreview(partsJson) ?: return
        dao.updateLastMessage(sessionId, preview, System.currentTimeMillis())
    }

    //
    // Three high-level queries surfaced to SessionsOffloadHandler. The DAO
    // side handles raw SQL + result projection; we add the JSON parsing,
    // text extraction, and snippet trimming. Mirrors iOS
    // `ChatStore.swift` L774-1023 line-by-line so the offload tool's
    // output shape is identical across platforms.

    /**
     * Backs `minis-sessions-cli list`. Returns sessions ordered by
     * last_active DESC, optionally filtered by id list, keyword AND, and
     * a date range on `updated_at` (so the user's "show me sessions
     * touched in March 2026" works on the timestamp the session-list UI
     * already exposes).
     *
     * Keyword AND semantics: each keyword has to land *somewhere* — in
     * the title or in any message's parts_json. Two keywords mean both
     * must match (possibly in different messages). This matches iOS,
     * which intentionally avoids requiring keywords to co-occur in one
     * row so a multi-turn session about "python" + "flask" still hits.
     *
     * [keywordsMatchBody] = false restricts keyword matching to titles.
     * `minis-sessions-cli list` passes false: that command is ungated
     * (1.36.25 — "list stays open: only returns id and title"), so matching
     * — and thereby revealing hits against — message bodies of foreign
     * sessions would hand an unauthorized caller a content oracle that
     * defeats the session_read boundary.
     */
    suspend fun querySessionsMeta(
        sessionIds: List<String>?,
        keywords: List<String>?,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
        keywordsMatchBody: Boolean = true,
    ): List<SessionMeta> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()

        if (!sessionIds.isNullOrEmpty()) {
            conditions += "s.id IN (${sessionIds.joinToString(",") { "?" }})"
            args.addAll(sessionIds)
        }
        if (startMs != null) {
            conditions += "s.updated_at >= ?"
            args += startMs
        }
        if (endMs != null) {
            conditions += "s.updated_at <= ?"
            args += endMs
        }
        if (!keywords.isNullOrEmpty()) {
            for (kw in keywords) {
                val pat = "%$kw%"
                if (keywordsMatchBody) {
                    conditions +=
                        "(s.title LIKE ? OR EXISTS (SELECT 1 FROM messages m " +
                        "WHERE m.session_id = s.id AND m.parts_json LIKE ?))"
                    args += pat
                    args += pat
                } else {
                    conditions += "s.title LIKE ?"
                    args += pat
                }
            }
        }
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        val sql = """
            SELECT s.id, s.title,
                   (SELECT m2.parts_json FROM messages m2
                    WHERE m2.session_id = s.id AND m2.role = 'user'
                    ORDER BY m2.sort_order ASC LIMIT 1) AS first_user_msg,
                   s.source, s.created_at, s.updated_at,
                   (SELECT COUNT(*) FROM messages m3 WHERE m3.session_id = s.id) AS msg_count
            FROM sessions s
            $where
            ORDER BY s.updated_at DESC
            LIMIT ?
        """.trimIndent()
        args += limit

        val rows = dao.runSessionsMetaQuery(
            androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray()),
        )
        return rows.map { r ->
            val preview = r.firstUserMsg?.let { extractTextForOffload(it) }
                ?.takeIf { it.isNotBlank() }
                ?.take(60)
            SessionMeta(
                id = r.id,
                title = r.title,
                preview = preview,
                source = r.source,
                startedAt = r.createdAt,
                lastActive = r.updatedAt,
                messageCount = r.msgCount,
            )
        }
    }

    /**
     * Backs `minis-sessions-cli search`. Over-fetches `limit * 3` rows
     * because parts_json LIKE matches can hit tool-call JSON metadata
     * (e.g. a tool name that happens to contain the keyword) rather than
     * actual user-visible text. We parse each row's parts_json on the
     * Kotlin side, drop rows whose extracted text is blank or whose
     * keyword didn't survive the parse, and trim to [limit] on the way
     * out. Mirrors iOS ChatStore.searchMessages.
     */
    suspend fun searchMessages(
        sessionIds: List<String>?,
        keywords: List<String>,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
    ): List<MessageSearchMatch> {
        if (keywords.isEmpty()) return emptyList()
        val budget = limit.coerceIn(1, 32).toLong() * ResourceLimits.SQL_CELL_BYTES
        if (!com.openminis.app.data.body.Admission.tryAdmit(budget)) return emptyList()
        return try {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()

        for (kw in keywords) {
            conditions += "m.parts_json LIKE ?"
            args += "%$kw%"
        }
        if (!sessionIds.isNullOrEmpty()) {
            conditions += "m.session_id IN (${sessionIds.joinToString(",") { "?" }})"
            args.addAll(sessionIds)
        }
        if (startMs != null) {
            conditions += "m.created_at >= ?"
            args += startMs
        }
        if (endMs != null) {
            conditions += "m.created_at <= ?"
            args += endMs
        }
        val where = conditions.joinToString(" AND ")
        val sql = """
            SELECT m.session_id, m.id, m.role, m.created_at,
                   substr(COALESCE(m.preview, m.parts_json), 1, ${ResourceLimits.SQL_CELL_BYTES})
            FROM messages m
            WHERE $where
            ORDER BY m.created_at DESC
            LIMIT ?
        """.trimIndent()
        args += (limit * 3)

        val rows = dao.runMessageSearchQuery(
            androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray()),
        )
        val out = mutableListOf<MessageSearchMatch>()
        for (r in rows) {
            val text = extractTextForOffload(r.partsJson)
            if (text.isBlank()) continue
            val snip = keywordSnippet(text, keywords, SNIPPET_MAX)
            if (snip.isBlank()) continue
            out += MessageSearchMatch(r.sessionId, r.id, r.role, r.createdAt, snip)
            if (out.size >= limit) break
        }
        out
        } finally {
            com.openminis.app.data.body.Admission.release(budget)
        }
    }

    /**
     * Backs `minis-sessions-cli messages --id ... --offset --limit`.
     * Skips messages whose extracted text is blank (system-only reminder
     * content, all-tool-use turns) so the agent sees a contiguous
     * user-visible transcript.
     */
    suspend fun loadMessagePage(
        sessionId: String,
        offset: Int,
        limit: Int,
        // [T-android-sessions-cli-full] Per-message text cap. Default stays the
        // documented 600; `minis-sessions-cli messages --full` passes
        // MESSAGE_TEXT_MAX_FULL (50000) so exports aren't silently gutted.
        maxChars: Int = MESSAGE_TEXT_MAX,
        // [T-android-sessions-cli-messages-daterange] GH#200. Inclusive,
        // independently optional created_at bounds; null = unbounded on that
        // side, so existing callers keep the previous behaviour untouched.
        startMs: Long? = null,
        endMs: Long? = null,
    ): List<MessagePageItem> {
        val rows = if (startMs == null && endMs == null) {
            dao.loadMessagesPage(sessionId, offset, limit)
        } else {
            dao.loadMessagesPageInRange(sessionId, offset, limit, startMs, endMs)
        }
        return rows.mapNotNull { e ->
            val text = extractTextForOffload(e.partsJson)
            if (text.isBlank()) return@mapNotNull null
            MessagePageItem(
                e.id, e.role, e.createdAt, text.take(maxChars),
                // Mark messages that exceeded the cap so the caller can emit
                // "truncated": true (mirrors iOS SessionsOffloadBridge).
                truncated = text.length > maxChars,
            )
        }
    }

    suspend fun messageCount(sessionId: String): Int = dao.messageCountForSession(sessionId)

    /**
     * [T-android-sessions-cli-messages-daterange] Count under the same optional
     * range [loadMessagePage] filters by, so `total` and the returned slice
     * always describe the same set.
     */
    suspend fun messageCountInRange(sessionId: String, startMs: Long?, endMs: Long?): Int =
        if (startMs == null && endMs == null) {
            dao.messageCountForSession(sessionId)
        } else {
            dao.messageCountForSessionInRange(sessionId, startMs, endMs)
        }

    /**
     * Paginated raw [MessageEntity] page — used by [com.openminis.app.share.ChatExporter]
     * to stream-export long sessions without loading every message into
     * memory. Unlike [loadMessagePage] this does not strip / project the
     * row; the exporter needs the full `parts_json` payload to serialize.
     */
    suspend fun loadMessagePageRaw(
        sessionId: String,
        offset: Int,
        limit: Int,
    ): List<MessageEntity> =
        dao.loadMessagesPage(sessionId, offset, limit)

    /**
     * Walk parts_json and concatenate every `{type:"text", value:...}`
     * block (newline-joined) after running [stripSystemReminders] on
     * each. Distinct from [extractTextPreview] / [cleanPreview] above —
     * those collapse markdown for a 100-char single-line preview, while
     * this preserves the full text the offload caller wants to inspect.
     */
    private fun extractTextForOffload(partsJson: String): String {
        return try {
            val arr = org.json.JSONArray(partsJson)
            val texts = mutableListOf<String>()
            var hasMedia = false
            val toolUses = mutableListOf<org.json.JSONObject>()
            val toolResults = mutableListOf<org.json.JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                when (o.optString("type")) {
                    "text" -> {
                        val v = o.optString("value", "")
                        if (v.isNotBlank()) texts.add(stripSystemReminders(v))
                    }
                    "mediaRef" -> hasMedia = true
                    "toolUse" -> o.optJSONObject("value")?.let { toolUses.add(it) }
                    "toolResult" -> o.optJSONObject("value")?.let { toolResults.add(it) }
                }
            }
            if (texts.isNotEmpty()) return texts.joinToString("\n")
            if (hasMedia) return "[Image]"
            if (toolUses.isNotEmpty()) {
                return toolUses.joinToString(", ") { tu ->
                    val title = tu.optString("name", "tool")
                    val inp = tu.optString("input", "")
                    val toolTitle = try {
                        org.json.JSONObject(inp).optString("tool_title", "")
                    } catch (_: Exception) { "" }
                    if (toolTitle.isNotBlank()) toolTitle.take(100) else title
                }
            }
            if (toolResults.isNotEmpty()) {
                return toolResults.joinToString("\n") { tr ->
                    val output = tr.optString("output", "").take(200)
                    "[Tool result: $output]"
                }
            }
            ""
        } catch (_: Exception) {
            stripSystemReminders(partsJson)
        }
    }

    /**
     * Center a snippet of [maxLength] chars on the earliest keyword
     * match (case-insensitive). Tail/head ellipses indicate truncation
     * boundaries. If no keyword survives the parts_json → text reduction
     * (rare but possible — a SQL LIKE hit on tool-use JSON that the text
     * extractor strips), we return the leading [maxLength] chars so the
     * offload caller still sees *something*.
     */
    private fun keywordSnippet(text: String, keywords: List<String>, maxLength: Int): String {
        if (text.isEmpty()) return ""
        val lower = text.lowercase()
        var earliest = text.length
        for (kw in keywords) {
            val pos = lower.indexOf(kw.lowercase())
            if (pos in 0 until earliest) earliest = pos
        }
        if (earliest == text.length) return text.take(maxLength)
        val half = maxLength / 2
        val start = (earliest - half).coerceAtLeast(0)
        val end = (start + maxLength).coerceAtMost(text.length)
        var s = text.substring(start, end)
        if (start > 0) s = "…$s"
        if (end < text.length) s = "$s…"
        return s
    }

    private data class StoredBody(
        val inline: String,
        val bodyBytes: Long,
        val ref: String?,
        val sha: String?,
        val preview: String?,
    )

    private fun storeBody(partsJson: String, requireOffload: Boolean = false): StoredBody {
        val bytes = partsJson.toByteArray(Charsets.UTF_8)
        val preview = partsJson.take(ResourceLimits.PREVIEW_BYTES)
        if (bytes.size <= ResourceLimits.INLINE_BODY_BYTES) {
            return StoredBody(partsJson, bytes.size.toLong(), null, null, preview)
        }
        val dir = filesDir?.let { File(it, "bodies") }
        val put = if (dir == null) null else BodyStore(dir).put(bytes)
        if (requireOffload && put?.ok != true) {
            throw IllegalStateException("Unable to persist oversized message body: ${put?.error ?: "body directory unavailable"}")
        }
        val note = if (put?.ok == true) {
            "body stored (${bytes.size} bytes)"
        } else {
            "body omitted; store failed"
        }
        val stub = DisplayParts.note(note)
        return StoredBody(
            inline = stub,
            bodyBytes = bytes.size.toLong(),
            ref = put?.ref,
            sha = put?.sha,
            preview = preview,
        )
    }

    companion object {
        private const val TAG = "ChatRepository"

        /**
         * [T-archive-delete-chunked] Ids per `DELETE … WHERE id IN (:ids)` on
         * the restore path. SQLite binds one variable per id and older bundled
         * builds (Android 11 and below) cap SQLITE_MAX_VARIABLE_NUMBER at 999;
         * 500 stays clear of that and of the CursorWindow growth a longer
         * prepared statement brings.
         */
        private const val ARCHIVE_DELETE_CHUNK = 500

        /**
         * Merge live rows and restored rows back into one timeline.
         *
         * Order is created_at ascending. A restored row and a live row that
         * share a timestamp keep the LIVE row first: a retry that re-persists
         * the same user turn gets a new id and a later-or-equal created_at,
         * and the restored predecessor must not jump ahead of the turn the
         * user actually kept.
         */
        internal fun chronologicalRestoreOrder(
            existing: List<SortEntry>,
            restored: List<SortEntry>,
        ): List<SortEntry> = (existing + restored).sortedWith(
            compareBy<SortEntry> { it.createdAt }.thenBy { it.restored },
        )

        /**
         * Upper bound for one authoritative context hydrate. The UI remains
         * tail-windowed, but model context must be able to recover history
         * beyond the old 400-row cache when a long session is reopened.
         *
         * [T-android-long-session-render-oom] Lowered from 4_000 to 500:
         * the UI tail IS the initial render window (loadSession →
         * toChatMessages → LazyColumn). At 4_000 a 1441-message session
         * loaded ALL rows into the UI on open, and RenderThread/SKIA
         * allocated 4.68 GB of native graphics memory in 11 s → Scudo
         * map exhaustion → abort (2026-10-14 crash, tombstone_09/12).
         * 500 rows keeps every real conversation fully loaded (the 95th
         * percentile session is far smaller) while bounding the worst
         * case; older history stays one tap away via loadOlderMessages
         * turn-aligned paging, and the DB remains the full transcript.
         */
        internal const val MAX_TAIL_MESSAGES = 500

        // Session-list preview only needs ~100 chars. Regex.replace on a
        // 500 KB parts_json body was a leftover ICU Matcher.reset path.
        private const val PREVIEW_CLEAN_CHARS = 400

        private fun cleanPreview(raw: String): String {
            val src = if (raw.length <= PREVIEW_CLEAN_CHARS) raw else raw.take(PREVIEW_CLEAN_CHARS)
            return stripSystemReminders(src)
                .replace(Regex("[\r\n]+"), " ")      // newlines → space
                .replace(Regex("#{1,6}\\s"), "")      // headings: ## Title → Title
                // [T-android-preview-underscore-eaten] `_{1,3}` stripped EVERY
                // underscore, so "Count_1_to_30" previewed as "Count1to30".
                // Only remove PAIRED emphasis markers wrapping text
                // (___bold___ / *italic*), never lone underscores inside words.
                .replace(Regex("\\*{1,3}(?=\\S)(.*?\\S)\\*{1,3}"), "$1") // paired * emphasis
                .replace(Regex("_{2,3}(?=\\S)(.*?\\S)_{2,3}"), "$1")     // paired __ emphasis
                .replace(Regex("~~"), "")              // strikethrough
                .replace(Regex("`{1,3}"), "")          // inline/fenced code markers
                .replace(Regex("^\\s*[-*+]\\s", RegexOption.MULTILINE), "") // list bullets
                .replace(Regex("^\\s*\\d+\\.\\s", RegexOption.MULTILINE), "") // ordered list
                .replace(Regex("^>\\s?", RegexOption.MULTILINE), "")       // blockquote
                .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1") // [text](url) → text
                .replace(Regex("!\\[([^]]*)]\\([^)]+\\)"), "$1") // ![alt](url) → alt
                .replace(Regex("\\s{2,}"), " ")        // collapse whitespace
                .trim()
                .take(100)
        }

        /**
         * Build a short preview string for a `toolUse` value block. Used by the
         * session list when an assistant turn is mid-tool-call and has no text
         * part yet. Strategy mirrors iOS ChatStore.summarizeToolUse (T-ios-
         * session-last-message-tool-call):
         *   1. Prefer model-supplied `tool_title` (carried in the on-disk shape
         *      as `value.description`, or inside the embedded `input` JSON).
         *   2. Else pick the most meaningful arg per known tool family.
         *   3. Else fall back to `🔧 <toolName>`.
         * Output capped at 100 chars to match cleanPreview's text ceiling.
         */
        private fun summarizeToolUse(value: org.json.JSONObject): String {
            val toolName = value.optString("name", "")
            // `description` is where ChatViewModel persists the captured
            // tool_title (see writeAssistantParts / writeAssistantPartsForLive
            // in ChatViewModel.kt — both pass block.toolTitle into "description").
            val description = value.optString("description", "").trim()

            // `input` is stored as an escaped JSON STRING, not a nested object
            // (see ChatViewModel.kt:6491 / :6539). Parse defensively.
            val input: org.json.JSONObject = try {
                val raw = value.opt("input")
                when (raw) {
                    is org.json.JSONObject -> raw
                    is String -> if (raw.isBlank()) org.json.JSONObject() else org.json.JSONObject(raw)
                    else -> org.json.JSONObject()
                }
            } catch (_: Exception) {
                org.json.JSONObject()
            }

            fun str(key: String): String? {
                val v = input.optString(key, "").trim()
                return if (v.isEmpty()) null else v
            }
            fun cap(s: String, n: Int = 100): String =
                if (s.length > n) s.substring(0, n) + "…" else s

            // 1. tool_title — checked both on the outer `description` field and
            //    inside `input` (the model writes it into args; we mirror what
            //    iOS does and accept either location).
            val title = str("tool_title") ?: description.takeIf { it.isNotEmpty() }
            if (title != null) return cap(cleanPreview(title))

            // 2. per-tool key argument
            when (toolName) {
                "shell_execute" -> str("command")?.let { return cap(cleanPreview("$ $it")) }
                "file_read" -> str("path")?.let { return cap(cleanPreview("Reading $it")) }
                "file_write" -> str("path")?.let { return cap(cleanPreview("Writing $it")) }
                "file_edit" -> str("path")?.let { return cap(cleanPreview("Editing $it")) }
                "browser_use" -> {
                    val action = str("action") ?: "browse"
                    val url = str("url")
                    return if (url != null) cap(cleanPreview("$action $url"))
                    else cap(cleanPreview("browser_use $action"))
                }
                "memory_write" -> str("content")?.let { return cap(cleanPreview("memory_write: $it")) }
                "memory_get" -> {
                    val arr = input.optJSONArray("keywords")
                    if (arr != null && arr.length() > 0) {
                        val joined = buildString {
                            for (i in 0 until arr.length()) {
                                if (i > 0) append(", ")
                                append(arr.optString(i))
                            }
                        }
                        if (joined.isNotBlank()) return cap(cleanPreview("memory_get: $joined"))
                    }
                    str("keywords")?.let { return cap(cleanPreview("memory_get: $it")) }
                }
            }

            // 3. final fallback
            return cap("🔧 ${toolName.ifBlank { "tool" }}")
        }

        // `internal` so the restore path can rebuild a session's preview from the
        // messages it just imported — see BackupImporter [T-android-restore-preview].
        internal fun extractTextPreview(partsJson: String): String? {
            try {
                val array = org.json.JSONArray(partsJson)
                var hasMedia = false
                // T-android-session-last-message-tool-call: also track the most
                // recent tool_use so a mid-tool-call assistant turn (no text yet)
                // renders as a short tool summary instead of falling through to
                // "No messages yet" in the session list.
                var lastToolUse: org.json.JSONObject? = null
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val type = obj.optString("type")
                    if (type == "text") {
                        val text = obj.optString("value", "")
                        if (text.isNotBlank()) {
                            return cleanPreview(text)
                        }
                    } else if (type == "mediaRef") {
                        hasMedia = true
                    } else if (type == "toolUse") {
                        val v = obj.optJSONObject("value")
                        if (v != null) lastToolUse = v
                    }
                }
                if (hasMedia) return "[Image]"
                if (lastToolUse != null) return summarizeToolUse(lastToolUse)
            } catch (_: Exception) {
                if (partsJson.isNotBlank()) return cleanPreview(partsJson)
            }
            return null
        }
        // <system-reminder>...</system-reminder> blocks are runtime nudges
        // injected into user-role messages by the harness (e.g. task-tracker
        // reminders). They never represent what the user actually typed, so
        // they must not show up in the session-list "last message" preview.
        // DOTALL flag covers multi-line reminder bodies; reluctant
        // quantifier so back-to-back reminders don't merge into one match.
        private val SYSTEM_REMINDER_RE =
            Regex("""<system-reminder>.*?</system-reminder>""", RegexOption.DOT_MATCHES_ALL)

        internal fun stripSystemReminders(raw: String): String =
            SYSTEM_REMINDER_RE.replace(raw, "").trim()

        // T188: snippet/length caps mirror iOS SessionsOffload.m. 600 chars
        // is a balance between giving the agent enough context to
        // disambiguate similar messages and not blowing past the agent's
        // context budget on a long search result.
        internal const val SNIPPET_MAX = 600
        internal const val MESSAGE_TEXT_MAX = 600
        // [T-android-sessions-cli-full] Per-message cap when the caller passes
        // `--full` — matches iOS SessionsOffloadBridge's 50_000 and the CLI
        // help's documented upper bound. A single message beyond this is still
        // truncated and flagged with "truncated": true.
        internal const val MESSAGE_TEXT_MAX_FULL = 50_000

        // Issue #17 — page size for the chat loader. 200 rows per query
        // keeps a normal-shaped CursorWindow well under 2 MB while
        // still amortising query overhead for long sessions.
        private const val LOAD_PAGE_SIZE = 200

        // Issue #17 — hard cap on a single message's parts_json. 500_000
        // chars ≈ 500 KB ASCII (worst case ~2 MB UTF-8 for 4-byte runs;
        // still small enough that any single resulting row fits inside
        // a single CursorWindow). New oversize payloads (browser_use
        // dumps, paste-bomb tool_results) are truncated at insert time
        // and replaced with a single text part carrying a marker, so
        // they remain JSON-parseable downstream.
        internal const val MAX_MESSAGE_PARTS_JSON_LENGTH = 500_000

        internal fun buildTruncatedPartsJson(original: String): String {
            val keep = original.take(MAX_MESSAGE_PARTS_JSON_LENGTH)
            val marker = "\n\n[Content truncated at " +
                "${MAX_MESSAGE_PARTS_JSON_LENGTH / 1000} KB — original length " +
                "${original.length} chars]"
            val combined = keep + marker
            // Wrap in a single text part so JSONArray parsers (preview
            // extractor, search, exporter) see a well-formed payload.
            val textObj = org.json.JSONObject()
                .put("type", "text")
                .put("value", combined)
            return org.json.JSONArray().put(textObj).toString()
        }
    }
}

/** T188: shape of a session row surfaced to `minis-sessions-cli list`. */
data class SessionMeta(
    val id: String,
    val title: String?,
    val preview: String?,
    val source: String?,
    val startedAt: Long,    // ms — sessions.created_at
    val lastActive: Long,   // ms — sessions.updated_at
    val messageCount: Int,
)

/** T188: a single matching message returned by `minis-sessions-cli search`. */
data class MessageSearchMatch(
    val sessionId: String,
    val messageId: String,
    val role: String,
    val createdAt: Long,
    val snippet: String,
)

/** T188: a single message in the paginated transcript returned by
 *  `minis-sessions-cli messages`. */
data class MessagePageItem(
    val messageId: String,
    val role: String,
    val createdAt: Long,
    val text: String,
    // [T-android-sessions-cli-full] True when the stored text exceeded the
    // requested cap and [text] is a prefix. Surfaced as "truncated": true.
    val truncated: Boolean = false,
)
