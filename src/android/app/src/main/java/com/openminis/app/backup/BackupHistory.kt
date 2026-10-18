package com.openminis.app.backup

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * [T-android-backup-history] Persistent record of backup runs. The Android peer
 * of `src/ios/Agent/Backup/BackupHistory.swift`.
 *
 * ## Why this exists
 *
 * The screen used to show a transient "Backup ready" card: it reflected only
 * the LAST run, held nothing once the user navigated away, and — the part that
 * actually mattered — never said which destinations the package reached. A
 * delivery that failed to one server out of three surfaced as a single red
 * line of text and then vanished, so "did last night's backup actually land on
 * the NAS?" was unanswerable.
 *
 * A record is written when a run starts and updated as it progresses, so an
 * interrupted run still leaves evidence rather than disappearing.
 *
 * ## Storage
 *
 * One JSON file in filesDir (NOT cacheDir — the system may evict the cache, and
 * a history that silently empties itself is worse than none). Records older
 * than [RETENTION_MS] are pruned on load: a heavy week can't push out a record
 * the user still cares about, and an idle month leaves nothing stale behind.
 * Same 30-day window as iOS.
 */
class BackupHistory private constructor(private val context: Context) {

    /**
     * Terminal state of a run.
     *
     * [COMPLETED_WITH_ISSUES] is the one that carries its weight: a run that
     * produced a package but couldn't reach a destination, or skipped files at
     * the size cap. Collapsing it into either "succeeded" or "failed" is what
     * made partial delivery invisible before.
     */
    @Serializable
    enum class Status { RUNNING, SUCCEEDED, COMPLETED_WITH_ISSUES, FAILED }

    /** One line of run progress, kept so a finished run can be inspected. */
    @Serializable
    data class LogEntry(
        val at: Long,
        val message: String,
        /** Rendered as a problem (destination failure, skipped file). */
        val isProblem: Boolean = false,
    )

    /** Per-destination delivery outcome — the thing the old UI never showed. */
    @Serializable
    data class DestinationOutcome(
        val name: String,
        val succeeded: Boolean,
        val detail: String? = null,
        /**
         * Backend tag (`smb`, `webdav`, `sftp`, `s3`, …) and the folder the
         * package was written to, so a row can say WHERE the backup went
         * rather than only which saved destination was picked. The saved name
         * alone ("HomeLab") does not tell someone opening a months-old record
         * how to find the file by hand.
         *
         * Both optional: records written before these existed decode with null
         * and render exactly as they did before.
         */
        val kind: String? = null,
        val path: String? = null,
    )

    /** A file left out of the package, with the reason. */
    @Serializable
    data class SkippedEntry(
        val path: String,
        val size: Long,
        val reason: String? = null,
    ) {
        val fileName: String get() = path.substringAfterLast('/')
    }

    @Serializable
    data class Record(
        val id: String = UUID.randomUUID().toString(),
        val backupId: String,
        val startedAt: Long,
        val finishedAt: Long? = null,
        val status: Status,
        val categories: List<String> = emptyList(),
        val encrypted: Boolean = false,
        val totalBytes: Long = 0,
        val skippedFiles: Int = 0,
        val skippedEntries: List<SkippedEntry> = emptyList(),
        val packageName: String? = null,
        val destinations: List<DestinationOutcome> = emptyList(),
        val log: List<LogEntry> = emptyList(),
        val errorMessage: String? = null,
    ) {
        val durationMillis: Long? get() = finishedAt?.let { it - startedAt }
        val hasProblems: Boolean
            get() = destinations.any { !it.succeeded } || skippedFiles > 0
    }

    // MARK: - Storage

    private val storeFile: File
        get() = File(context.filesDir, "backup-history").apply { mkdirs() }
            .let { File(it, RECORD_FILE) }

    @Volatile
    private var cache: List<Record>? = null

    /** Newest first. Expired records are dropped (and rewritten) on first read. */
    @Synchronized
    fun records(): List<Record> {
        cache?.let { return it }
        val loaded = runCatching {
            storeFile.takeIf { it.exists() }?.readText()
                ?.let { JSON.decodeFromString(RECORD_LIST, it) }
        }.getOrNull() ?: emptyList()
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val kept = loaded.filter { it.startedAt >= cutoff }.sortedByDescending { it.startedAt }
        // [T-android-backup-log-cap] Trim oversized logs written by earlier
        // versions (or by a run that produced thousands of progress lines)
        // while we already have the decoded records in hand. Comparing by
        // identity is exact here: cappedLog returns the SAME list when nothing
        // was dropped.
        var rewritten = kept.size != loaded.size
        val trimmed = kept.map { record ->
            val capped = cappedLog(record.log)
            if (capped !== record.log) {
                rewritten = true
                record.copy(log = capped)
            } else {
                record
            }
        }
        if (rewritten) persist(trimmed)
        cache = trimmed
        return trimmed
    }

    @Synchronized
    private fun persist(list: List<Record>) {
        cache = list
        runCatching {
            val encoded = JSON.encodeToString(RECORD_LIST, list)
            // [T-android-backup-atomic-write] Write-then-rename: a run killed
            // mid-`writeText` used to leave a truncated JSON file, and the
            // decode above swallows the error (`runCatching ?: emptyList`), so
            // the ENTIRE history silently disappeared. A rename over the old
            // file is atomic on the same POSIX filesystem, so a reader either
            // sees the previous complete file or the new complete one.
            val tmp = File(storeFile.parentFile, RECORD_FILE + ".tmp")
            tmp.writeText(encoded)
            if (!tmp.renameTo(storeFile)) {
                // Defensive: rename cannot cross filesystems and both paths are
                // in filesDir, so this is unreachable in practice — but a
                // history that stops updating is worse than a non-atomic write.
                storeFile.writeText(encoded)
                tmp.delete()
            }
        }.onFailure { AppLogger.error(TAG, "[Backup] history write failed: ${it.message}") }
    }

    // MARK: - Mutation

    /** Insert a record, or replace the one carrying the same id. */
    @Synchronized
    fun upsert(record: Record) {
        val cur = records().toMutableList()
        // [T-android-backup-log-cap] Cap on the way in as well, so a live run
        // never has more than MAX_LOG_ENTRIES lines on disk even before the
        // next load-time trim.
        val capped = record.copy(log = cappedLog(record.log))
        val i = cur.indexOfFirst { it.id == capped.id }
        if (i >= 0) cur[i] = capped else cur.add(0, capped)
        persist(cur.sortedByDescending { it.startedAt })
    }

    @Synchronized
    fun remove(id: String) {
        persist(records().filterNot { it.id == id })
    }

    @Synchronized
    fun clear() {
        persist(emptyList())
    }

    /**
     * Mark any record still flagged RUNNING as failed.
     *
     * A run that was killed — process death, a crash, the user swiping the app
     * away — leaves its record RUNNING forever, which would render as a
     * perpetual spinner in the list. Called at construction so the state is
     * reconciled before the UI ever sees it.
     */
    @Synchronized
    private fun reconcileInterrupted() {
        val cur = records()
        if (cur.none { it.status == Status.RUNNING }) return
        persist(
            cur.map {
                if (it.status != Status.RUNNING) it
                else it.copy(
                    status = Status.FAILED,
                    finishedAt = it.finishedAt ?: System.currentTimeMillis(),
                    errorMessage = it.errorMessage ?: INTERRUPTED_MARKER,
                )
            },
        )
    }

    companion object {
        private const val TAG = "Backup"

        /** 30 days, matching iOS `BackupHistory.retention`. */
        const val RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000

        private const val RECORD_FILE = "records.json"

        /**
         * [T-android-backup-log-cap] Hard ceiling on progress lines kept per
         * record.
         *
         * A local-folder / SMB destination reports one line per file, so a
         * 4 000-file workspace wrote 4 000 progress entries — each carrying its
         * own text, serialised into a single JSON file that is re-encoded on
         * EVERY `note()` (twice: upsert + publishRunning). That is both a
         * storage and a jank problem: the file reached megabytes, and the
         * re-encode ran on the UI-visible path while the backup was running.
         *
         * 200 lines is far more than the history row or the detail screen ever
         * shows (the row renders `log.last`, the detail sheet a scrollback) and
         * keeps the file in the tens of KB.
         */
        const val MAX_LOG_ENTRIES: Int = 200

        /**
         * How many of the opening lines survive the cap. The first lines of a
         * run say what was being backed up and where to; the tail says how it
         * ended. Dropping only the middle keeps both answers readable.
         */
        private const val LOG_HEAD_ENTRIES = 20

        /**
         * Recorded as the error for a run that never reported a terminal state.
         * Not localized on purpose: it is a stored marker, and the UI maps it to
         * a translated string at render time.
         */
        const val INTERRUPTED_MARKER = "interrupted"

        /**
         * Cap [log] to [MAX_LOG_ENTRIES], keeping the opening [LOG_HEAD_ENTRIES]
         * lines, the newest remainder, and one explicit marker saying how many
         * lines went missing (a silently shortened log reads as a complete run).
         *
         * Returns [log] ITSELF when nothing had to be dropped, so callers can
         * detect "no change" by identity.
         */
        fun cappedLog(log: List<LogEntry>): List<LogEntry> {
            if (log.size <= MAX_LOG_ENTRIES) return log
            val head = log.take(LOG_HEAD_ENTRIES)
            val tail = log.takeLast(MAX_LOG_ENTRIES - LOG_HEAD_ENTRIES - 1)
            val dropped = log.size - head.size - tail.size
            val marker = LogEntry(
                at = tail.firstOrNull()?.at ?: head.lastOrNull()?.at ?: System.currentTimeMillis(),
                message = "… $dropped earlier progress line(s) dropped",
                isProblem = false,
            )
            return head + marker + tail
        }

        /**
         * [T-android-backup-log-cap] Append one progress line, keeping the list
         * capped in memory too.
         *
         * Used by [com.openminis.app.ui.settings.backup.BackupViewModel]; the
         * in-memory list is what `publishRunning` copies into the record on
         * every line, so letting it grow uncaught would keep re-encoding the
         * whole thing.
         */
        fun appendLog(target: MutableList<LogEntry>, message: String, isProblem: Boolean = false) {
            target.add(LogEntry(System.currentTimeMillis(), message, isProblem))
            if (target.size > MAX_LOG_ENTRIES) {
                val capped = cappedLog(target)
                target.clear()
                target.addAll(capped)
            }
        }

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val RECORD_LIST = ListSerializer(Record.serializer())

        @Volatile
        private var instance: BackupHistory? = null

        fun get(context: Context): BackupHistory =
            instance ?: synchronized(this) {
                instance ?: BackupHistory(context.applicationContext)
                    .also { it.reconcileInterrupted(); instance = it }
            }
    }
}
