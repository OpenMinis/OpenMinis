package com.openminis.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.session.SessionDeleter
import com.openminis.app.data.session.SessionStorage
import com.openminis.app.data.session.SessionTree
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One row of the storage list: a ROOT session with its hidden children folded in. */
internal data class SessionStorageInfo(
    val id: String,
    val title: String?,
    val minisSize: Long,
    val mediaSize: Long,
    /** Hidden helper (child) sessions folded into this row. */
    val childCount: Int = 0,
) {
    val totalSize: Long get() = minisSize + mediaSize
}

/**
 * [T-android-storage-usage-cache] Pure bookkeeping for the cached session
 * list, kept apart from the ViewModel so the incremental rules are testable
 * without a device.
 *
 * The invariant every operation keeps: the list is sorted by total size,
 * largest first, and holds each root session at most once.
 */
internal object SessionStorageRows {

    /** Largest first; ties keep a stable order by id so rows do not jitter. */
    fun sorted(rows: Collection<SessionStorageInfo>): List<SessionStorageInfo> =
        rows.sortedWith(compareByDescending<SessionStorageInfo> { it.totalSize }.thenBy { it.id })

    /**
     * Apply fresh measurements for [measured] root ids. A value of null means
     * the session no longer exists (deleted), so its row goes; any other value
     * replaces the row. Rows not mentioned are left exactly as they were —
     * that is the whole point: nothing else is re-measured.
     */
    fun applyMeasurements(
        rows: List<SessionStorageInfo>,
        measured: Map<String, SessionStorageInfo?>,
    ): List<SessionStorageInfo> {
        if (measured.isEmpty()) return rows
        val byId = LinkedHashMap<String, SessionStorageInfo>()
        rows.forEach { byId[it.id] = it }
        for ((id, info) in measured) {
            if (info == null) byId.remove(id) else byId[id] = info
        }
        return sorted(byId.values)
    }

    fun total(rows: List<SessionStorageInfo>): Long = rows.sumOf { it.totalSize }

    /**
     * Measure only [rootIds]: each one's own files plus its hidden children's,
     * the same folding [aggregateSessionStorage] does for the full scan, so an
     * incremental value always equals what a full rescan would show.
     *
     * A root that is not in [sessions] (deleted) maps to null. A child id
     * passed by mistake is measured as the root it folds into, because the
     * list only ever shows roots.
     */
    fun measureRoots(
        rootIds: Collection<String>,
        sessions: List<ChatSessionEntity>,
        storage: SessionStorage,
    ): Map<String, SessionStorageInfo?> {
        if (rootIds.isEmpty()) return emptyMap()
        val byId = sessions.associateBy { it.id }
        val parentOf: Map<String, String?> = sessions.associate { it.id to it.parentSessionId }
        val roots = rootIds.map { id -> if (id in byId) SessionTree.rootOf(id, parentOf) else id }.toSet()
        val membersByRoot = roots.associateWith { root ->
            if (root in byId) SessionTree.membersOf(root, parentOf).ifEmpty { listOf(root) } else emptyList()
        }
        // One walk of the media tree for every root measured here together.
        val media = storage.mediaSizesBySession(membersByRoot.values.flatten().toSet())
        return roots.associateWith { root ->
            val session = byId[root] ?: return@associateWith null
            val members = membersByRoot.getValue(root)
            SessionStorageInfo(
                id = root,
                title = session.title,
                minisSize = members.sumOf { storage.minisSize(it) },
                mediaSize = members.sumOf { media[it] ?: 0L },
                childCount = members.size - 1,
            )
        }
    }
}

/**
 * [T-android-storage-usage-cache] Settings › Storage, measured once per visit.
 *
 * The screen used to keep its numbers in `remember` state and measure in
 * `LaunchedEffect(Unit)`. Navigation Compose disposes a destination's
 * composition when another screen is pushed on top, so every return from a
 * session's detail page re-ran the whole scan: the Alpine rootfs walk, the
 * database, logs, and every session's workspace plus the media tree. Clearing
 * logs re-ran it too.
 *
 * This ViewModel is scoped to the Storage back-stack entry, so it lives while
 * the user moves between the list and a detail page and is dropped when they
 * leave Storage. It measures everything once, then only ever re-measures what
 * an action changed:
 *  - a detail page reports the sizes it measured for its own session
 *    ([onSessionMeasured]) — no extra disk work at all;
 *  - batch clear / delete re-measure only the sessions they touched;
 *  - clearing logs re-measures only logs & caches;
 *  - opening the rootfs screen marks the shell size stale, re-measured when
 *    the list is shown again (a reset there changes it).
 */
internal class StorageUsageViewModel(
    context: Context,
    private val repo: ChatRepository,
) : ViewModel() {

    private val appContext = context.applicationContext
    private val storage = SessionStorage(appContext.filesDir)

    data class UiState(
        val isLoading: Boolean = true,
        val shellSize: Long = 0L,
        val dbSize: Long = 0L,
        val logsCachesSize: Long = 0L,
        val rows: List<SessionStorageInfo> = emptyList(),
        val isClearingLogs: Boolean = false,
        val selecting: Boolean = false,
        val selectedIds: Set<String> = emptySet(),
        val batchBusy: Boolean = false,
    ) {
        val totalSessionSize: Long get() = SessionStorageRows.total(rows)
        val selectedSize: Long get() = rows.filter { it.id in selectedIds }.sumOf { it.totalSize }
    }

    /** Outcome of a batch action, shown once as a toast. */
    sealed interface BatchResult {
        data class Cleared(val sessions: Int, val freed: Long, val failedPaths: List<String>) : BatchResult
        data class Deleted(val sessions: Int, val freed: Long) : BatchResult
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _result = MutableStateFlow<BatchResult?>(null)
    val result: StateFlow<BatchResult?> = _result.asStateFlow()

    private var shellStale = false

    init {
        scanAll()
    }

    /** The one full measurement of a visit. */
    private fun scanAll() {
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val scanned = withContext(Dispatchers.IO) {
                val allSessions = repo.dao.listSessions()
                val parentOf = allSessions.associate { it.id to it.parentSessionId }
                val rows = aggregateSessionStorage(allSessions, storage).map { (session, minis, media) ->
                    SessionStorageInfo(
                        id = session.id,
                        title = session.title,
                        minisSize = minis,
                        mediaSize = media,
                        childCount = SessionTree.membersOf(session.id, parentOf).size - 1,
                    )
                }
                UiState(
                    isLoading = false,
                    shellSize = SessionStorage.directorySize(File(appContext.filesDir, "alpine-rootfs")),
                    dbSize = databaseSize(appContext),
                    logsCachesSize = LogsAndCachesCleaner.size(appContext),
                    rows = SessionStorageRows.sorted(rows),
                )
            }
            _state.update { scanned.copy(selecting = it.selecting, selectedIds = it.selectedIds) }
            AppLogger.info(
                TAG,
                "full scan: ${scanned.rows.size} sessions in ${System.currentTimeMillis() - started}ms",
            )
        }
    }

    /** Called each time the list is shown; cheap unless something is stale. */
    fun onListShown() {
        if (!shellStale) return
        shellStale = false
        viewModelScope.launch {
            val size = withContext(Dispatchers.IO) {
                SessionStorage.directorySize(File(appContext.filesDir, "alpine-rootfs"))
            }
            _state.update { it.copy(shellSize = size) }
        }
    }

    fun onRootfsOpened() {
        shellStale = true
    }

    /**
     * A detail page measured [sessionId] (on open, after returning from its
     * file browser, after a clear). Those are exactly the numbers the list
     * row needs, so take them as they are instead of measuring again.
     */
    fun onSessionMeasured(sessionId: String, minisSize: Long, mediaSize: Long) {
        _state.update { st ->
            val row = st.rows.firstOrNull { it.id == sessionId } ?: return@update st
            if (row.minisSize == minisSize && row.mediaSize == mediaSize) return@update st
            AppLogger.info(TAG, "incremental: ${sessionId.take(8)} ${row.totalSize}B -> ${minisSize + mediaSize}B (detail)")
            st.copy(
                rows = SessionStorageRows.applyMeasurements(
                    st.rows,
                    mapOf(sessionId to row.copy(minisSize = minisSize, mediaSize = mediaSize)),
                ),
            )
        }
    }

    fun clearLogsAndCaches() {
        if (_state.value.isClearingLogs) return
        _state.update { it.copy(isClearingLogs = true) }
        viewModelScope.launch {
            val size = withContext(Dispatchers.IO) {
                LogsAndCachesCleaner.clear(appContext)
                LogsAndCachesCleaner.size(appContext)
            }
            _state.update { it.copy(isClearingLogs = false, logsCachesSize = size) }
        }
    }

    // ─── Multi-select ──────────────────────────────────────────────────────

    fun startSelecting(initial: String? = null) {
        _state.update { it.copy(selecting = true, selectedIds = setOfNotNull(initial)) }
    }

    fun stopSelecting() {
        _state.update { it.copy(selecting = false, selectedIds = emptySet()) }
    }

    fun toggle(id: String) {
        _state.update { st ->
            st.copy(selectedIds = if (id in st.selectedIds) st.selectedIds - id else st.selectedIds + id)
        }
    }

    fun toggleSelectAll() {
        _state.update { st ->
            val all = st.rows.map { it.id }.toSet()
            st.copy(selectedIds = if (st.selectedIds.containsAll(all)) emptySet() else all)
        }
    }

    /** Clear the selected sessions' files (their hidden children's too); rows stay. */
    fun clearSelectedFiles() {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty() || _state.value.batchBusy) return
        _state.update { it.copy(batchBusy = true) }
        viewModelScope.launch {
            val before = _state.value.rows.filter { it.id in ids }.sumOf { it.totalSize }
            val (failed, measured) = withContext(Dispatchers.IO) {
                val sessions = repo.dao.listSessions()
                val parentOf = sessions.associate { it.id to it.parentSessionId }
                // Same cascade as the detail page's single clear: the root's
                // files and every hidden child's.
                val failed = ids.flatMap { root ->
                    SessionTree.membersOf(root, parentOf).ifEmpty { listOf(root) }
                        .flatMap { storage.deleteFilesDetailed(it).failedPaths }
                }
                failed to SessionStorageRows.measureRoots(ids, sessions, storage)
            }
            val after = measured.values.sumOf { it?.totalSize ?: 0L }
            _state.update {
                it.copy(
                    rows = SessionStorageRows.applyMeasurements(it.rows, measured),
                    batchBusy = false,
                    selecting = false,
                    selectedIds = emptySet(),
                )
            }
            AppLogger.info(TAG, "incremental: cleared ${ids.size} sessions, ${before - after}B freed, ${failed.size} failed")
            _result.value = BatchResult.Cleared(ids.size, (before - after).coerceAtLeast(0L), failed)
        }
    }

    /** Delete the selected sessions through the one delete funnel; rows go. */
    fun deleteSelected() {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty() || _state.value.batchBusy) return
        _state.update { it.copy(batchBusy = true) }
        viewModelScope.launch {
            val before = _state.value.rows.filter { it.id in ids }.sumOf { it.totalSize }
            val (measured, db) = withContext(Dispatchers.IO) {
                ids.forEach { SessionDeleter.deleteTree(appContext, repo, it, "storage-multi") }
                // Re-measure the touched ids: deleted ones come back null and
                // drop out. The database shrank too; its size is two stat calls.
                SessionStorageRows.measureRoots(ids, repo.dao.listSessions(), storage) to databaseSize(appContext)
            }
            val remaining = measured.values.sumOf { it?.totalSize ?: 0L }
            val deleted = measured.values.count { it == null }
            _state.update {
                it.copy(
                    rows = SessionStorageRows.applyMeasurements(it.rows, measured),
                    dbSize = db,
                    batchBusy = false,
                    selecting = false,
                    selectedIds = emptySet(),
                )
            }
            AppLogger.info(TAG, "incremental: deleted $deleted/${ids.size} sessions, ${before - remaining}B freed")
            _result.value = BatchResult.Deleted(deleted, (before - remaining).coerceAtLeast(0L))
        }
    }

    fun consumeResult() {
        _result.value = null
    }

    companion object {
        private const val TAG = "StorageUsage"

        fun factory(context: Context, repo: ChatRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    StorageUsageViewModel(context.applicationContext, repo) as T
            }
    }
}

internal fun databaseSize(context: Context): Long {
    val dbFile = context.getDatabasePath("minis.db")
    var size = if (dbFile.exists()) dbFile.length() else 0L
    val wal = File(dbFile.path + "-wal")
    val shm = File(dbFile.path + "-shm")
    if (wal.exists()) size += wal.length()
    if (shm.exists()) size += shm.length()
    return size
}
