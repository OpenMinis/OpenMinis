package com.openminis.app.backup

import android.content.Context
import android.net.Uri
import com.openminis.app.R
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [T-android-backup-run-controller] Owns a backup run for the life of the
 * PROCESS, not the screen. The Android analogue of iOS `BackupRunController`.
 *
 * The run used to live on `BackupViewModel.viewModelScope`. That ViewModel is
 * scoped to the Backup nav entry, so leaving the screen, switching tab or
 * popping back to Settings cleared it and cancelled the export mid-package
 * (user reports: "can't leave the backup screen"). A backup is a job the user
 * starts and walks away from; it has to outlive the UI that started it.
 *
 * Hence a process-level singleton with its own [SupervisorJob] scope: the
 * ViewModel becomes a thin reader of the flows below plus a forwarder of
 * start / stop, and any number of screen instances can come and go while one
 * run proceeds. Staying alive once the whole APP is backgrounded is a separate
 * problem, handled by [com.openminis.app.service.BackupForegroundService],
 * which [start] promotes the process with.
 *
 * One run at a time, like iOS: [start] is a no-op while [isRunning].
 */
object BackupRunController {

    private const val TAG = "BackupRunController"

    /**
     * Stored sentinel for a user-cancelled run, mapped to a translated string
     * at render time. Persisted in history, so it must not be locale-dependent
     * (same treatment as [BackupHistory.INTERRUPTED_MARKER]).
     */
    const val STOPPED_MARKER = "stopped"

    /** Process scope. SupervisorJob so one failed run cannot poison the next. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class Request(
        val categories: Set<BackupCategory>,
        val maxFileBytes: Long?,
        /** Non-null only when the user chose to encrypt. */
        val passphrase: String?,
    ) {
        val encrypted: Boolean get() = !passphrase.isNullOrEmpty()
    }

    data class ExportResult(
        val backupId: String,
        val packageFile: File,
        val totalBytes: Long,
        val skippedFiles: Int,
    )

    data class RunResult(
        val totalBytes: Long,
        val skippedFiles: Int,
        val destinations: List<BackupHistory.DestinationOutcome>,
        val localCopyRemoved: Boolean = false,
        /**
         * [T-android-backup-local-export] Display name of the document the
         * user saved the package to via Save to Device, once they have.
         */
        val savedAs: String? = null,
    ) {
        val allDelivered: Boolean
            get() = destinations.isNotEmpty() && destinations.all { it.succeeded }

        /**
         * [T-android-backup-local-export] This run had nowhere to go: no
         * destination received it. Its only copy is inside the app until the
         * user saves or shares it — the condition under which Save / Share
         * are offered (iOS: `deliveryResults.isEmpty`).
         */
        val localOnly: Boolean get() = destinations.isEmpty()

        /** Settled somewhere outside the app — safe to drop the card. */
        val settled: Boolean get() = allDelivered || savedAs != null
    }

    /** Progress of a Save to Device copy. */
    sealed interface SaveState {
        data class Saving(val percent: Int) : SaveState
        data class Saved(val name: String) : SaveState
        data class Failed(val message: String) : SaveState
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    private val _errorText = MutableStateFlow<String?>(null)
    val errorText: StateFlow<String?> = _errorText.asStateFlow()

    private val _exportReady = MutableStateFlow<ExportResult?>(null)
    val exportReady: StateFlow<ExportResult?> = _exportReady.asStateFlow()

    private val _lastResult = MutableStateFlow<RunResult?>(null)
    val lastResult: StateFlow<RunResult?> = _lastResult.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState?>(null)
    val saveState: StateFlow<SaveState?> = _saveState.asStateFlow()

    /**
     * Bumped on every history write so screens can re-read [BackupHistory].
     * The history file is the source of truth; this is only the "look again"
     * signal, so a screen composed mid-run shows the live record.
     */
    private val _historyVersion = MutableStateFlow(0L)
    val historyVersion: StateFlow<Long> = _historyVersion.asStateFlow()

    /** The in-flight run, held so [stop] can reach it. */
    @Volatile private var runJob: Job? = null

    fun clearError() { _errorText.value = null }
    fun clearExportReady() { _exportReady.value = null }
    fun clearSaveState() { _saveState.value = null }

    /**
     * Drop the finished card only once it reports a run that settled outside
     * the app. A failed destination stays (that is the result a user needs to
     * come back to), and so does a local-only run nobody has saved yet —
     * dropping that one would hide the only prompt to get it off the device.
     */
    fun clearSettledSuccess() {
        if (_isRunning.value) return
        val r = _lastResult.value ?: return
        if (r.settled) _lastResult.value = null
    }

    /** Cancel the running backup at its next suspension point. */
    fun stop() {
        val job = runJob ?: return
        AppLogger.info(TAG, "[Backup] user requested stop")
        job.cancel()
    }

    /**
     * Start a backup. Returns false if one is already running.
     *
     * [T-android-backup-local-export] No longer refuses when no destination is
     * enabled. It used to (T-android-backup-destination-gate), for a real
     * reason: a package that never leaves the sandbox dies with the app, and
     * reporting it as "Backup ready" read as success. That concern is now met
     * differently rather than by refusing — the run is recorded as local-only,
     * the result card says in words that it is not saved outside the app yet,
     * and it keeps offering Save to Device / Share until the user acts. Users
     * with no server (reported: "no way to back up locally on Android") get a
     * backup instead of a dead button.
     */
    fun start(context: Context, request: Request): Boolean {
        if (_isRunning.value) return false
        val app = context.applicationContext
        val cats = request.categories
        _isRunning.value = true
        _errorText.value = null
        _lastResult.value = null
        _exportReady.value = null
        _saveState.value = null
        _statusText.value = "Starting…"

        val history = BackupHistory.get(app)
        // Open the record BEFORE any work, so a run killed mid-flight still
        // leaves evidence. BackupHistory reconciles a record left RUNNING into
        // FAILED on next launch.
        val log = mutableListOf<BackupHistory.LogEntry>()
        var record = BackupHistory.Record(
            backupId = "",
            startedAt = System.currentTimeMillis(),
            status = BackupHistory.Status.RUNNING,
            categories = cats.map { it.key }.sorted(),
            encrypted = request.encrypted,
        )
        writeHistory(history, record)

        fun note(line: String, problem: Boolean = false) {
            log.add(BackupHistory.LogEntry(System.currentTimeMillis(), line, problem))
        }
        fun progress(line: String) {
            _statusText.value = line
            note(line)
            // The running history row's subtitle IS the latest log line, so
            // progress lives with the run rather than inside the button (iOS).
            writeHistory(history, record.copy(log = log.toList()))
        }

        runJob = scope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    BackupExporter(app, AppDatabase.getInstance(app)).export(
                        BackupExporter.Options(
                            categories = cats,
                            maxFileBytes = request.maxFileBytes,
                            // Unconditional (T-backup-credentials-without-
                            // encryption): a backup exists to reconstitute a
                            // device. Only the passphrase tracks encryption.
                            includeCredentials = true,
                            passphrase = request.passphrase,
                        ),
                    ) { line -> progress(line) }
                }

                // Deliver to every enabled destination. A failure never
                // discards the local package.
                val outcomes = withContext(Dispatchers.IO) {
                    // [T-android-backup-webdav-deadline] The delivery below is
                    // BLOCKING code, so job.cancel() (Stop) could not reach it:
                    // it waited out every upload before the run saw the
                    // cancellation. The job's state is passed in as a flag that
                    // the upload and folder copy poll.
                    val job = coroutineContext[kotlinx.coroutines.Job]
                    deliverToDestinations(
                        app, summary.packageFile, summary.backupId,
                        isCancelled = { job?.isActive == false },
                    ) { progress(it) }
                }
                outcomes.forEach {
                    note(
                        if (it.succeeded) "Delivered to ${it.name}"
                        else "Delivery to ${it.name} failed: ${it.detail}",
                        problem = !it.succeeded,
                    )
                }

                // The local package is a FALLBACK once every destination holds
                // a verified copy (T-android-backup-local-cleanup). With no
                // destinations, or any failure, it stays — it is then the
                // backup.
                var localRemoved = false
                if (outcomes.isNotEmpty() && outcomes.all { it.succeeded }) {
                    val freed = summary.packageFile.length()
                    if (withContext(Dispatchers.IO) { summary.packageFile.delete() }) {
                        localRemoved = true
                        val msg = app.getString(R.string.backup_local_removed, humanBytesPlain(freed))
                        AppLogger.info(TAG, "[Backup] $msg")
                        note(msg)
                    }
                } else if (outcomes.isNotEmpty()) {
                    note(app.getString(R.string.backup_local_kept))
                } else {
                    // [T-android-backup-local-export] Say where it is, in the
                    // record too: "did that backup go anywhere?" is asked long
                    // after the card is gone.
                    note(app.getString(R.string.backup_result_local_pending))
                }

                _exportReady.value = ExportResult(
                    backupId = summary.backupId,
                    packageFile = summary.packageFile,
                    totalBytes = summary.totalBytes,
                    skippedFiles = summary.skippedFiles,
                )
                _lastResult.value = RunResult(
                    totalBytes = summary.totalBytes,
                    skippedFiles = summary.skippedFiles,
                    destinations = outcomes,
                    localCopyRemoved = localRemoved,
                )
                val failed = outcomes.filterNot { it.succeeded }
                if (failed.isNotEmpty()) {
                    _errorText.value = "Saved locally, but delivery failed for: " +
                        failed.joinToString("; ") { "${it.name}: ${it.detail ?: "failed"}" }
                }
                record = record.copy(
                    backupId = summary.backupId,
                    finishedAt = System.currentTimeMillis(),
                    // A run that could not reach every destination, or dropped
                    // files at the cap, is neither clean success nor failure.
                    status = if (failed.isEmpty() && summary.skippedFiles == 0) {
                        BackupHistory.Status.SUCCEEDED
                    } else {
                        BackupHistory.Status.COMPLETED_WITH_ISSUES
                    },
                    totalBytes = summary.totalBytes,
                    skippedFiles = summary.skippedFiles,
                    skippedEntries = summary.skippedPaths.map {
                        BackupHistory.SkippedEntry(it.path, it.size)
                    },
                    packageName = summary.packageFile.name,
                    destinations = outcomes,
                    log = log.toList(),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Stopped by the user: a real outcome, not a vanished run —
                // left RUNNING it would render as a permanent spinner.
                AppLogger.info(TAG, "[Backup] export cancelled by user")
                note(STOPPED_MARKER, problem = true)
                record = record.copy(
                    finishedAt = System.currentTimeMillis(),
                    status = BackupHistory.Status.FAILED,
                    errorMessage = STOPPED_MARKER,
                    log = log.toList(),
                )
                throw e
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Backup] export failed: ${e.message}")
                _errorText.value = e.message ?: "Backup failed."
                note(e.message ?: "Backup failed.", problem = true)
                record = record.copy(
                    finishedAt = System.currentTimeMillis(),
                    status = BackupHistory.Status.FAILED,
                    errorMessage = e.message,
                    log = log.toList(),
                )
            } finally {
                runJob = null
                _statusText.value = null
                writeHistory(history, record)
                _isRunning.value = false
            }
        }
        // [T-android-backup-fgs] Promote the process for the run's duration so
        // backgrounding the app does not freeze or reclaim it. The service
        // watches isRunning and stops itself; nothing here has to remember to.
        com.openminis.app.service.BackupForegroundService.start(app)
        return true
    }

    /**
     * [T-android-backup-local-export] Copy the finished package to a document
     * the user picked with the Storage Access Framework (`CreateDocument`).
     *
     * Runs on the process scope for the same reason as the backup: a
     * multi-hundred-MB copy must not die because the user navigated away while
     * it ran. The local package is kept afterwards — Save can be repeated, and
     * Share still works.
     */
    fun saveTo(context: Context, target: Uri) {
        val ready = _exportReady.value ?: return
        val app = context.applicationContext
        if (_saveState.value is SaveState.Saving) return
        _saveState.value = SaveState.Saving(0)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    copyWithProgress(app, ready.packageFile, target) { pct ->
                        _saveState.value = SaveState.Saving(pct)
                    }
                }
                val name = displayName(app, target) ?: ready.packageFile.name
                _saveState.value = SaveState.Saved(name)
                _lastResult.value = _lastResult.value?.copy(savedAs = name)
                recordSavedToDevice(app, ready.backupId, name)
                AppLogger.info(TAG, "[Backup] saved to device as $name")
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Backup] save to device failed: ${e.message}")
                // A half-written document is worse than none: it has a valid
                // name and a plausible size. Best effort only — some providers
                // do not support delete.
                runCatching {
                    android.provider.DocumentsContract.deleteDocument(app.contentResolver, target)
                }
                _saveState.value = SaveState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // -- internals ------------------------------------------------------------

    private fun writeHistory(history: BackupHistory, record: BackupHistory.Record) {
        history.upsert(record)
        _historyVersion.value = _historyVersion.value + 1
    }

    /**
     * Append the device save to the run's history record, so the permanent
     * record shows where the package ended up — not only the transient card.
     */
    private fun recordSavedToDevice(context: Context, backupId: String, name: String) {
        if (backupId.isEmpty()) return
        val history = BackupHistory.get(context)
        val rec = history.records().firstOrNull { it.backupId == backupId } ?: return
        val line = context.getString(R.string.backup_saved_to_device_log, name)
        writeHistory(
            history,
            rec.copy(log = rec.log + BackupHistory.LogEntry(System.currentTimeMillis(), line, false)),
        )
    }

    internal fun copyWithProgress(
        context: Context,
        source: File,
        target: Uri,
        onPercent: (Int) -> Unit,
    ) {
        val total = source.length()
        val out = context.contentResolver.openOutputStream(target, "w")
            ?: throw IllegalStateException("Could not open the chosen location for writing.")
        out.use { sink ->
            source.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                var copied = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    copied += n
                    val pct = if (total > 0) (copied * 100 / total).toInt() else 100
                    if (pct != lastPct) { lastPct = pct; onPercent(pct) }
                }
                sink.flush()
            }
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /**
     * Upload the package to every enabled destination (rclone remotes and
     * SAF folders). Reports EVERY destination, not only failures. Never throws
     * — a failed destination must not lose the local copy. Empty when none is
     * enabled, which is what marks a run local-only.
     */
    private fun deliverToDestinations(
        context: Context,
        packageFile: File,
        backupId: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (String) -> Unit,
    ): List<BackupHistory.DestinationOutcome> {
        val store = com.openminis.app.backup.remote.RcloneRemoteStore(context)
        val enabled = store.enabledRemotes
        if (enabled.isEmpty()) return emptyList()
        // Only pay for the rclone config sync when a destination needs it
        // (T-android-backup-local-folder).
        if (enabled.any { !com.openminis.app.backup.remote.RcloneRemoteStore.isLocalFolder(it.backend) }) {
            store.syncToRclone()
        }
        val uploader = com.openminis.app.backup.remote.RcloneChunkedUpload(context)
        val localDelivery = com.openminis.app.backup.remote.LocalFolderDelivery(context)
        val outcomes = mutableListOf<BackupHistory.DestinationOutcome>()
        for (remote in enabled) {
            // Between destinations is a clean place to stop: a copy already
            // delivered stays delivered, the next one never starts.
            if (isCancelled()) {
                AppLogger.info(TAG, "[Backup] delivery stopped before '${remote.name}'")
                break
            }
            try {
                onProgress("Sending to ${remote.name}…")
                if (com.openminis.app.backup.remote.RcloneRemoteStore.isLocalFolder(remote.backend)) {
                    val treeUri = remote.params[
                        com.openminis.app.backup.remote.RcloneRemoteStore.PARAM_TREE_URI,
                    ].orEmpty()
                    if (treeUri.isEmpty()) {
                        throw IllegalStateException("This folder destination is missing its location.")
                    }
                    localDelivery.deliver(packageFile, treeUri, isCancelled) { sent, total ->
                        val pct = if (total > 0) (sent * 100 / total) else 0
                        onProgress("Saving to ${remote.name}… $pct%")
                    }
                } else {
                    uploader.upload(packageFile, remote, backupId, isCancelled = isCancelled) { p ->
                        val pct = if (p.totalBytes > 0) (p.bytesSent * 100 / p.totalBytes) else 0
                        onProgress("Sending to ${remote.name}… $pct%")
                    }
                }
                outcomes.add(
                    BackupHistory.DestinationOutcome(
                        remote.name, succeeded = true, kind = remote.backend, path = remote.path,
                    ),
                )
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Backup] delivery to ${remote.name} failed: ${e.message}")
                outcomes.add(
                    BackupHistory.DestinationOutcome(
                        remote.name, succeeded = false, detail = e.message ?: "failed",
                        kind = remote.backend, path = remote.path,
                    ),
                )
            }
        }
        return outcomes
    }

    /** Bytes as "32.0 MB", for a message rather than a UI row. */
    internal fun humanBytesPlain(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1e6)
        bytes >= 1_000 -> String.format(java.util.Locale.US, "%.0f kB", bytes / 1e3)
        else -> "$bytes B"
    }
}
