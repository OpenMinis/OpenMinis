package com.openminis.app.backup

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
 *
 * Readers only ever see the in-memory list; the file is replaced atomically
 * (temp file + rename, like iOS `.atomic`) on a background writer.
 */
class BackupHistory internal constructor(
    /** `filesDir/backup-history`; a parameter so JVM tests can use a temp dir. */
    private val dir: File,
    /** Where the file is written — off the caller's thread and off the cache lock. */
    private val writer: Executor = WRITER,
    /** [T-android-backup-log-sanitize] Applied once to each record's log on first load. */
    private val sanitize: (List<LogEntry>) -> List<LogEntry> = BackupLogRules::sanitize,
) {

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
        /**
         * [T-android-backup-progress-log] A live progress line ("Saving to X…
         * 42%") that the NEXT line replaces instead of following — iOS
         * `LogEntry.isTransient`. See [BackupLogRules.append].
         *
         * Defaulted, so records written before this field existed decode with
         * `false` (kotlinx fills a missing key from the default).
         */
        val isTransient: Boolean = false,
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
        val isScheduled: Boolean = false,
    ) {
        val durationMillis: Long? get() = finishedAt?.let { it - startedAt }
        val hasProblems: Boolean
            get() = destinations.any { !it.succeeded } || skippedFiles > 0
    }

    // MARK: - Storage

    private val storeFile: File
        get() = File(dir.apply { mkdirs() }, STORE_NAME)

    @Volatile
    private var cache: List<Record>? = null

    /**
     * [T-android-backup-history-atomic] Bumped under this object's lock with
     * every change to [cache]; the writer records the version it put on disk,
     * so an older snapshot can never land after a newer one.
     */
    private var version = 0L
    private val writeLock = Any()
    private var writtenVersion = 0L
    private val writeQueued = AtomicBoolean(false)

    /** Test seam: runs inside the disk step, under [writeLock] only. */
    @Volatile
    internal var writeHook: (() -> Unit)? = null

    /** Newest first. Expired records are dropped (and rewritten) on first read. */
    @Synchronized
    fun records(): List<Record> {
        cache?.let { return it }
        val (raw, loaded) = load()
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val kept = loaded.filter { it.startedAt >= cutoff }.sortedByDescending { it.startedAt }
        val cleaned = clean(kept)
        if (cleaned !== kept && raw != null) keepBackup(raw)
        if (cleaned !== kept || kept.size != loaded.size) persist(cleaned)
        cache = cleaned
        return cleaned
    }

    /**
     * [T-android-backup-log-sanitize] Records written before progress lines
     * replaced each other can hold tens of thousands of log lines (16,827 in
     * the KILO repro), and the detail screen froze on them for as long as the
     * record was kept. Only `log` changes. Returns [records] itself when
     * nothing changed — or when cleaning failed, which must never cost the
     * history.
     */
    private fun clean(records: List<Record>): List<Record> = runCatching {
        var changed = false
        val out = records.map { r ->
            val log = sanitize(r.log)
            if (log == r.log) r else { changed = true; r.copy(log = log) }
        }
        if (changed) out else records
    }.getOrElse {
        AppLogger.error(TAG, "[Backup] history log cleanup failed, kept as is: ${it.message}")
        records
    }

    /**
     * The file as it was before cleanup, as `records.json.bak` (the latest
     * only). Queued on [writer] ahead of the rewrite, so it lands first.
     */
    private fun keepBackup(raw: String) {
        runCatching {
            writer.execute {
                runCatching {
                    val tmp = File(dir, "$STORE_NAME.bak.tmp")
                    tmp.writeText(raw)
                    Files.move(
                        tmp.toPath(), File(dir, "$STORE_NAME.bak").toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                    )
                    AppLogger.info(TAG, "[Backup] history log cleaned; original kept as $STORE_NAME.bak")
                }.onFailure { AppLogger.error(TAG, "[Backup] history .bak write failed: ${it.message}") }
            }
        }.onFailure { AppLogger.error(TAG, "[Backup] history .bak not scheduled: ${it.message}") }
    }

    /**
     * Read the file once. A file that does not decode — truncated by a write
     * the process did not survive, say — is renamed aside rather than
     * silently treated as "no history", so what it held can still be looked
     * at; the history then starts empty.
     */
    private fun load(): Pair<String?, List<Record>> {
        val f = storeFile
        if (!f.exists()) return null to emptyList()
        val text = runCatching { f.readText() }.getOrElse {
            AppLogger.error(TAG, "[Backup] history read failed: ${it.message}")
            return null to emptyList()
        }
        return text to runCatching { JSON.decodeFromString(RECORD_LIST, text) }.getOrElse { e ->
            val aside = File(dir, "$STORE_NAME.corrupt-${System.currentTimeMillis()}")
            val moved = f.renameTo(aside)
            AppLogger.error(
                TAG,
                "[Backup] history file does not decode (${e.javaClass.simpleName}, ${text.length} chars); " +
                    "kept as ${aside.name} (moved=$moved), starting empty",
            )
            emptyList()
        }
    }

    /**
     * Update the cache now; write the file later, on [writer].
     *
     * Serializing and writing used to happen right here, under the lock every
     * reader takes — so the backup page's `records()` waited for a delivery
     * thread's disk write every few seconds. Only the cache swap is locked now.
     */
    @Synchronized
    private fun persist(list: List<Record>) {
        cache = list
        version++
        if (writeQueued.compareAndSet(false, true)) {
            runCatching { writer.execute { writeLatest() } }.onFailure {
                writeQueued.set(false)
                AppLogger.error(TAG, "[Backup] history write not scheduled: ${it.message}")
            }
        }
    }

    /**
     * Write whatever the cache holds NOW, if it is newer than the file: to a
     * temp file in the same directory, synced, then renamed over the store,
     * so a reader — or the next launch after a crash — sees the old file or
     * the new one, never half of one. A failure is logged only: history is a
     * convenience and must never fail a backup.
     */
    private fun writeLatest() = synchronized(writeLock) {
        // Cleared before the snapshot is taken: a change after this point
        // queues another pass, so it cannot be missed.
        writeQueued.set(false)
        val (snapshot, v) = synchronized(this) { cache to version }
        if (snapshot == null || v <= writtenVersion) return@synchronized
        runCatching {
            writeHook?.invoke()
            val bytes = JSON.encodeToString(RECORD_LIST, snapshot).toByteArray(Charsets.UTF_8)
            val tmp = File(dir.apply { mkdirs() }, "$STORE_NAME.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(
                tmp.toPath(), storeFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            writtenVersion = v
        }.onFailure { AppLogger.error(TAG, "[Backup] history write failed: ${it.message}") }
    }

    /** Write the latest state now, on the calling thread (tests). */
    internal fun flushWrites() = writeLatest()

    // MARK: - Mutation

    /** Insert a record, or replace the one carrying the same id. */
    @Synchronized
    fun upsert(record: Record) {
        val cur = records().toMutableList()
        val i = cur.indexOfFirst { it.id == record.id }
        if (i >= 0) cur[i] = record else cur.add(0, record)
        persist(cur.sortedByDescending { it.startedAt })
    }

    @Synchronized
    fun remove(id: String) {
        persist(records().filterNot { it.id == id })
    }

    /**
     * [T-android-backup-dest-edit] Follow a destination rename into the
     * records. An outcome is matched back to its destination BY NAME — the
     * "open destination" link and "delete with files" both look it up that way
     * — so without this every record written before the rename would report
     * the destination as removed while it is still configured under a new name.
     */
    @Synchronized
    fun renameDestination(from: String, to: String) {
        val cur = records()
        if (cur.none { r -> r.destinations.any { it.name == from } }) return
        persist(
            cur.map { r ->
                if (r.destinations.none { it.name == from }) r
                else r.copy(destinations = r.destinations.map { if (it.name == from) it.copy(name = to) else it })
            },
        )
    }

    @Synchronized
    fun removeAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        persist(records().filterNot { it.id in set })
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

        /**
         * Recorded as the error for a run that never reported a terminal state.
         * Not localized on purpose: it is a stored marker, and the UI maps it to
         * a translated string at render time.
         */
        const val INTERRUPTED_MARKER = "interrupted"

        private const val STORE_NAME = "records.json"

        /** One writer thread for the process: writes are coalesced, never parallel. */
        private val WRITER: Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "BackupHistoryWriter").apply { isDaemon = true }
        }

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val RECORD_LIST = ListSerializer(Record.serializer())

        @Volatile
        private var instance: BackupHistory? = null

        fun get(context: Context): BackupHistory =
            instance ?: synchronized(this) {
                instance ?: BackupHistory(File(context.applicationContext.filesDir, "backup-history"))
                    .also { it.reconcileInterrupted(); instance = it }
            }
    }
}
