package com.openminis.app.ui.settings.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.backup.BackupCategory
import com.openminis.app.backup.BackupExporter
import com.openminis.app.backup.BackupHistory
import com.openminis.app.backup.BackupImporter
import com.openminis.app.backup.BackupManifest
import com.openminis.app.backup.BackupPackageReader
import com.openminis.app.backup.BackupRunController
import com.openminis.app.backup.BackupZip
import com.openminis.app.R
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [T-android-backup-ui] Drives the Backup & Restore screen — the Android analog
 * of iOS `BackupRunController` + the export/restore state held by
 * BackupSettingsView / BackupRestoreView. One instance per screen; serialises
 * to BackupExporter / BackupImporter (which are themselves process-serialised).
 *
 * Phase 2 delivers the local path only: export → Android share / Save-to-Files
 * (SAF); restore ← SAF-picked `.minisbak`. rclone remote destinations are
 * Phase 3.
 */
class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val db get() = AppDatabase.getInstance(getApplication())

    // -- Export state -----------------------------------------------------

    /** Backupable categories selected for the NEXT export (all on by default,
     *  matching iOS `Set(BackupCategory.backupable)`). Persisted across launches. */
    private val prefs = app.getSharedPreferences("backup_ui", android.content.Context.MODE_PRIVATE)

    private val _selected = MutableStateFlow(loadSelectedCategories())
    val selected: StateFlow<Set<BackupCategory>> = _selected.asStateFlow()

    private val _encrypt = MutableStateFlow(prefs.getBoolean(KEY_ENCRYPT, false))
    val encrypt: StateFlow<Boolean> = _encrypt.asStateFlow()

    /**
     * Max per-file size, in MB, using iOS's sentinel tags: -1 = don't back up
     * files, 0 = unlimited (the default), otherwise the MB cap. Persisted.
     */
    private val _maxFileSizeMB = MutableStateFlow(prefs.getInt(KEY_MAX_FILE_MB, MAX_FILE_UNLIMITED))
    val maxFileSizeMB: StateFlow<Int> = _maxFileSizeMB.asStateFlow()

    /**
     * Busy flag for the RESTORE side (open / list / download / import). The
     * backup run's own flag lives in [BackupRunController]; [isRunning] is the
     * union, so neither can start while the other is in flight.
     */
    private val _isRunning = MutableStateFlow(false)

    /**
     * [T-android-backup-run-controller] The export itself is owned by
     * [BackupRunController], a process singleton — NOT this ViewModel. It used
     * to run on `viewModelScope`, and this ViewModel is scoped to the Backup
     * nav entry, so leaving the screen cancelled the backup mid-package. The
     * flows below just read the controller; any number of screen visits can
     * come and go while one run proceeds.
     */
    private val runner = BackupRunController

    /** True while a backup runs (restore excluded — see [isRunning]). */
    val exportRunning: StateFlow<Boolean> = runner.isRunning

    val isRunning: StateFlow<Boolean> = combine(_isRunning, runner.isRunning) { restore, export ->
        restore || export
    }.stateIn(viewModelScope, SharingStarted.Eagerly, runner.isRunning.value)

    /** Stop the running backup (at its next suspension point). */
    fun stopExport() = runner.stop()

    /**
     * Enabled rclone / folder destinations, refreshed whenever the screen
     * appears (the user may have just added one and come back). Mirrors iOS
     * `BackupSettingsView.hasDestination`.
     *
     * [T-android-backup-local-export] No longer gates Start: a run with no
     * destination is allowed and ends in Save to Device / Share. It still
     * drives the hint under the button, which tells the user where the
     * package will (not) go before they start.
     */
    private val _destinations =
        MutableStateFlow<List<com.openminis.app.backup.remote.RcloneRemoteStore.Remote>>(emptyList())
    val destinations: StateFlow<List<com.openminis.app.backup.remote.RcloneRemoteStore.Remote>> =
        _destinations.asStateFlow()

    /** True when at least one ENABLED destination can receive the package. */
    val hasDestination: Boolean get() = _destinations.value.any { it.enabled }

    /**
     * Re-read configured destinations. Call on screen resume.
     *
     * Lists ALL remotes, not just the enabled ones (iOS parity): a disabled
     * destination must stay visible so it can be switched back on.
     */
    fun refreshDestinations() {
        _destinations.value = runCatching {
            com.openminis.app.backup.remote.RcloneRemoteStore(getApplication()).remotes
        }.getOrDefault(emptyList())
    }

    /** Flip delivery for one destination without touching its credential. */
    fun setDestinationEnabled(name: String, on: Boolean) {
        runCatching {
            com.openminis.app.backup.remote.RcloneRemoteStore(getApplication())
                .setEnabled(name, on)
        }
        refreshDestinations()
    }

    /** Restore-side status; merged with the backup's in [statusText]. */
    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = combine(_statusText, runner.statusText) { restore, export ->
        export ?: restore
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Non-null once an export finished and its package is ready to share/save. */
    val exportReady: StateFlow<BackupRunController.ExportResult?> = runner.exportReady

    /** Progress of a Save to Device copy. */
    val saveState: StateFlow<BackupRunController.SaveState?> = runner.saveState

    /** Restore-side / validation errors; merged with the backup's in [errorText]. */
    private val _errorText = MutableStateFlow<String?>(null)
    val errorText: StateFlow<String?> = combine(_errorText, runner.errorText) { local, export ->
        local ?: export
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The run that just finished, shown under the Start button. Transient: the
     * same facts live permanently in Backup History.
     */
    val lastResult: StateFlow<BackupRunController.RunResult?> = runner.lastResult

    /** Drop the finished card when it settled outside the app. */
    fun clearSettledSuccess() = runner.clearSettledSuccess()

    fun toggleCategory(category: BackupCategory, on: Boolean) {
        _selected.value = _selected.value.toMutableSet().apply {
            if (on) add(category) else remove(category)
        }
        prefs.edit().putString(
            KEY_CATEGORIES, _selected.value.joinToString(",") { it.key }
        ).apply()
    }

    fun setEncrypt(on: Boolean) {
        _encrypt.value = on
        prefs.edit().putBoolean(KEY_ENCRYPT, on).apply()
    }

    fun setMaxFileSizeMB(value: Int) {
        _maxFileSizeMB.value = value
        prefs.edit().putInt(KEY_MAX_FILE_MB, value).apply()
    }

    /** Convert the MB sentinel tag to BackupExporter.Options.maxFileBytes:
     *  unlimited(0) → null, no-files(-1) → 0 bytes (tombstones only), else MB. */
    private fun maxFileBytesOption(): Long? = when (val mb = _maxFileSizeMB.value) {
        MAX_FILE_UNLIMITED -> null
        MAX_FILE_NO_FILES -> 0L
        else -> mb.toLong() * 1024L * 1024L
    }

    fun clearError() {
        _errorText.value = null
        runner.clearError()
    }
    fun clearExportReady() = runner.clearExportReady()

    /**
     * Run an export. [passphrase] must be non-empty when [encrypt] is on.
     *
     * Credentials are ALWAYS included (T-backup-credentials-without-
     * encryption); only the passphrase depends on [encrypt]. The run itself is
     * handed to [BackupRunController] so it survives this screen.
     */
    fun startExport(passphrase: String?) {
        if (isRunning.value) return
        val cats = _selected.value
        if (cats.isEmpty()) { _errorText.value = "Choose at least one thing to include."; return }
        val encrypting = _encrypt.value
        if (encrypting && passphrase.isNullOrEmpty()) {
            _errorText.value = "Set a passphrase to encrypt this backup."
            return
        }
        _errorText.value = null
        refreshDestinations()
        runner.start(
            getApplication(),
            BackupRunController.Request(
                categories = cats,
                maxFileBytes = maxFileBytesOption(),
                passphrase = passphrase?.takeIf { encrypting },
            ),
        )
    }

    /**
     * [T-android-backup-local-export] Save the finished package to a document
     * the user picked (SAF `CreateDocument`). Runs on the controller's process
     * scope, so a large copy survives leaving the screen.
     */
    fun saveExportTo(target: Uri) = runner.saveTo(getApplication(), target)

    fun clearSaveState() = runner.clearSaveState()
    // -- History ----------------------------------------------------------

    private val history by lazy { BackupHistory.get(getApplication()) }

    private val _historyRecords = MutableStateFlow<List<BackupHistory.Record>>(emptyList())
    val historyRecords: StateFlow<List<BackupHistory.Record>> = _historyRecords.asStateFlow()

    fun refreshHistory() { _historyRecords.value = history.records() }

    init {
        // [T-android-backup-run-controller] The run writes history from the
        // controller, which outlives this screen; re-read on every write so a
        // screen opened mid-run shows the live record and its log.
        viewModelScope.launch {
            runner.historyVersion.collect { _historyRecords.value = history.records() }
        }
    }

    /**
     * [T-backup-delete-files-too] Delete the package from every destination
     * that received it, then forget the record.
     *
     * Best-effort per destination: one unreachable server must not stop the
     * others being cleaned, and the record goes regardless — keeping it would
     * leave the user an entry whose files are already half-gone, which is a
     * worse state to reason about than no entry at all. Failures are logged
     * rather than surfaced, since the screen is leaving anyway.
     */
    /**
     * [T-android-backup-delete-files-feedback] Outcome of a
     * "delete record and files" run, so the screen can tell the user what
     * actually happened instead of silently popping.
     */
    sealed interface DeleteWithFilesResult {
        /** Every destination that held the package accepted the delete. */
        data class Success(val destinations: Int) : DeleteWithFilesResult

        /**
         * At least one destination refused. [failures] is "name: reason" per
         * destination; the history record is KEPT so the user can retry
         * rather than losing the only pointer to an orphaned remote file.
         */
        data class Failed(val failures: List<String>) : DeleteWithFilesResult
    }

    /**
     * Delete the backup package from every destination that received it, then
     * drop the history record.
     *
     * [T-android-backup-delete-files-feedback] Returns the outcome rather than
     * firing and forgetting. Three things were wrong before:
     *
     *  1. the caller popped the screen on the same frame it invoked this, and
     *     the ViewModel is scoped to that nav entry — so `viewModelScope` was
     *     cancelled before the IO block ran and NOTHING was deleted. That is
     *     the reported "button does nothing";
     *  2. a destination that refused the delete was only logged, so a network
     *     or permission failure looked identical to success;
     *  3. the record was removed unconditionally, which on failure threw away
     *     the only record naming the file left behind on the remote.
     *
     * This is a `suspend` function so the caller can await it, keep the screen
     * up while it runs, and act on the result. The record is removed only when
     * every destination succeeded.
     */
    suspend fun removeHistoryRecordWithFiles(id: String): DeleteWithFilesResult {
        val record = history.records().firstOrNull { it.id == id }
        val name = record?.packageName
        if (record == null || name.isNullOrEmpty()) {
            // Nothing nameable to delete remotely — dropping the record is the
            // whole operation, and it succeeded.
            removeHistoryRecord(id)
            return DeleteWithFilesResult.Success(destinations = 0)
        }
        val targets = record.destinations.filter { it.succeeded }
        val failures = withContext(Dispatchers.IO) {
            val failed = mutableListOf<String>()
            try {
                val store = com.openminis.app.backup.remote.RcloneRemoteStore(getApplication())
                // [T-android-backup-local-folder-delete] Mirror deliverToRemotes:
                // only pay for the rclone config sync when a destination
                // actually needs it. A record whose only destination is a
                // folder on this phone must not start rclone at all.
                val hasNonLocalDest = targets.any { outcome ->
                    val remote = store.remotes.firstOrNull { it.name == outcome.name }
                    remote != null &&
                        !com.openminis.app.backup.remote.RcloneRemoteStore.isLocalFolder(remote.backend)
                }
                if (hasNonLocalDest) {
                    store.syncToRclone()
                }
                val uploader =
                    com.openminis.app.backup.remote.RcloneChunkedUpload(getApplication())
                val localDelivery =
                    com.openminis.app.backup.remote.LocalFolderDelivery(getApplication())
                for (outcome in targets) {
                    val remote = store.remotes.firstOrNull { it.name == outcome.name }
                    if (remote == null) {
                        // The destination was deleted since the backup ran, so
                        // its copy is unreachable from here. Report it instead
                        // of skipping silently — the file may still exist.
                        failed += "${outcome.name}: ${getApplication<android.app.Application>()
                            .getString(R.string.backup_delete_files_dest_missing)}"
                        continue
                    }
                    runCatching {
                        // [T-android-backup-local-folder-delete] Issue #367
                        // root cause B: this branch did not exist, so a local
                        // folder's copy was handed to rclone, which has no
                        // remote by that name — the delete could never work.
                        if (com.openminis.app.backup.remote.RcloneRemoteStore.isLocalFolder(remote.backend)) {
                            val treeUri = remote.params[
                                com.openminis.app.backup.remote.RcloneRemoteStore.PARAM_TREE_URI,
                            ].orEmpty()
                            if (treeUri.isEmpty()) {
                                throw IllegalStateException(
                                    "This folder destination is missing its location.",
                                )
                            }
                            localDelivery.delete(treeUri, name)
                        } else {
                            uploader.deletePackage(remote, name)
                        }
                    }
                        .onFailure {
                            AppLogger.error(
                                TAG,
                                "[Backup] deleting '$name' from '${outcome.name}' failed: ${it.message}",
                            )
                            failed += "${outcome.name}: ${it.message ?: it::class.java.simpleName}"
                        }
                }
            } catch (t: Throwable) {
                // syncToRclone / store construction blew up: no destination was
                // even attempted, so attribute it to the whole operation.
                AppLogger.error(TAG, "[Backup] delete-with-files setup failed: ${t.message}")
                failed += t.message ?: t::class.java.simpleName
            }
            failed
        }
        if (failures.isNotEmpty()) return DeleteWithFilesResult.Failed(failures)
        removeHistoryRecord(id)
        return DeleteWithFilesResult.Success(destinations = targets.size)
    }

    fun removeHistoryRecord(id: String) {
        history.remove(id)
        _historyRecords.value = history.records()
    }

    // -- Restore state ----------------------------------------------------

    private val _pending = MutableStateFlow<PendingRestore?>(null)
    val pending: StateFlow<PendingRestore?> = _pending.asStateFlow()

    private val _report = MutableStateFlow<BackupImporter.Report?>(null)
    val report: StateFlow<BackupImporter.Report?> = _report.asStateFlow()

    /** A picked package, extracted and its manifest read, awaiting confirm. */
    data class PendingRestore(
        val extractedRoot: File,
        val manifest: BackupManifest,
        val availableCategories: Set<BackupCategory>,
    )

    /**
     * Copy the SAF-picked `.minisbak` into cache, unzip it, and read its
     * manifest so the screen can preview contents + prompt for a passphrase
     * before anything is written to the device.
     */
    fun loadPackage(uri: Uri) {
        _isRunning.value = true
        _statusText.value = "Reading backup…"
        _report.value = null
        viewModelScope.launch {
            try {
                val pending = withContext(Dispatchers.IO) {
                    val zip = File(getApplication<Application>().cacheDir, "restore-pick.minisbak")
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { inp ->
                        zip.outputStream().use { inp.copyTo(it) }
                    } ?: throw IllegalStateException("Could not open the selected file.")
                    extractAndInspect(zip)
                }
                setPending(pending)
                _statusText.value = null
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Restore] load failed: ${e.message}")
                _errorText.value = e.message ?: "This file could not be read as a backup."
                _statusText.value = null
            } finally {
                _isRunning.value = false
            }
        }
    }

    /** Shared extract+manifest-read core, off the main thread. */
    private fun extractAndInspect(zip: File): PendingRestore {
        val extracted = File(getApplication<Application>().cacheDir, "restore-extract")
            .apply { deleteRecursively(); mkdirs() }
        // [T-android-open-progress] `extract` already reported every entry it
        // wrote; the value was simply discarded, leaving the user watching a
        // spinner driven by a timer. Publishing it turns a blind wait on a
        // multi-GB package into a visible one.
        var files = 0
        var bytes = 0L
        BackupZip.extract(zip, extracted) { name ->
            files += 1
            bytes += File(extracted, name).length()
            _openProgress.value = OpenProgress(files, bytes, name.substringAfterLast('/'))
        }
        val root = BackupZip.packageRoot(extracted)
        val manifest = BackupPackageReader(root).readManifest()
        val avail = manifest.categories.keys.mapNotNull(BackupCategory::fromKey).toSet()
        return PendingRestore(extracted, manifest, avail)
    }

    private fun setPending(pending: PendingRestore) {
        _pending.value = pending
        _restoreSelected.value = pending.availableCategories // default-select all present
    }

    // -- Restore sources: Server -----------------------------------------

    fun listServerRemotes(): List<com.openminis.app.backup.remote.RcloneRemoteStore.Remote> =
        com.openminis.app.backup.remote.RcloneRemoteStore(getApplication()).remotes

    private val _serverPackages =
        MutableStateFlow<List<com.openminis.app.backup.remote.RcloneChunkedUpload.RemotePackage>>(emptyList())
    val serverPackages: StateFlow<List<com.openminis.app.backup.remote.RcloneChunkedUpload.RemotePackage>> =
        _serverPackages.asStateFlow()

    /** List the `.minisbak` packages on one configured remote. */
    fun listServerPackages(remote: com.openminis.app.backup.remote.RcloneRemoteStore.Remote) {
        _isRunning.value = true
        _statusText.value = "Listing…"
        _serverPackages.value = emptyList()
        viewModelScope.launch {
            try {
                val pkgs = withContext(Dispatchers.IO) {
                    val store = com.openminis.app.backup.remote.RcloneRemoteStore(getApplication())
                    store.syncToRclone()
                    com.openminis.app.backup.remote.RcloneChunkedUpload(getApplication())
                        .listPackages(remote)
                }
                _serverPackages.value = pkgs
                _statusText.value = null
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Restore] list server packages failed: ${e.message}")
                _errorText.value = e.message ?: "Could not list backups on that server."
                _statusText.value = null
            } finally {
                _isRunning.value = false
            }
        }
    }

    fun clearServerPackages() { _serverPackages.value = emptyList() }

    // -- Destination browsing (T-android-restore-browse) ------------------

    /**
     * One directory level of the destination being browsed, so a user can walk
     * into subfolders instead of being handed one flat list of every package
     * on the server.
     */
    private val _browseEntries =
        MutableStateFlow<List<com.openminis.app.backup.remote.RcloneChunkedUpload.RemoteEntry>>(emptyList())
    val browseEntries: StateFlow<List<com.openminis.app.backup.remote.RcloneChunkedUpload.RemoteEntry>> =
        _browseEntries.asStateFlow()

    /** Path being listed, relative to the remote's own root. */
    private val _browsePath = MutableStateFlow("")
    val browsePath: StateFlow<String> = _browsePath.asStateFlow()

    private val _browsing = MutableStateFlow(false)
    val browsing: StateFlow<Boolean> = _browsing.asStateFlow()

    /**
     * List one level of [remote]. rclone's config is in-memory only, so it is
     * re-synced before the first call rather than assuming a previous screen
     * did it.
     */
    fun browseDestination(
        remote: com.openminis.app.backup.remote.RcloneRemoteStore.Remote,
        path: String = remote.path,
    ) {
        _browsing.value = true
        _errorText.value = null
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    val store = com.openminis.app.backup.remote.RcloneRemoteStore(getApplication())
                    store.syncToRclone()
                    com.openminis.app.backup.remote.RcloneChunkedUpload(getApplication())
                        .listDirectory(remote, path)
                }
                _browsePath.value = path
                _browseEntries.value = entries
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Restore] listing '$path' failed: ${e.message}")
                _errorText.value = e.message ?: "Could not list that folder."
            } finally {
                _browsing.value = false
            }
        }
    }

    /**
     * [T-restore-browse-swipe-delete] Delete a package seen in the restore
     * browser, then re-list the same folder so the row reflects the server,
     * not an assumption. The caller confirms first; a failure leaves the list
     * as it was and says why.
     */
    fun deleteBrowsedPackage(
        remote: com.openminis.app.backup.remote.RcloneRemoteStore.Remote,
        entry: com.openminis.app.backup.remote.RcloneChunkedUpload.RemoteEntry,
    ) {
        if (entry.isDirectory) return
        val folder = _browsePath.value.ifEmpty { remote.path }
        _browsing.value = true
        _errorText.value = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val store = com.openminis.app.backup.remote.RcloneRemoteStore(getApplication())
                    store.syncToRclone()
                    com.openminis.app.backup.remote.RcloneChunkedUpload(getApplication()).deletePackage(
                        remote,
                        com.openminis.app.backup.remote.RcloneChunkedUpload.RemotePackage(
                            key = entry.path,
                            displayName = entry.name,
                            size = entry.size,
                            modified = entry.modified,
                            partCount = 1,
                        ),
                    )
                }
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Restore] delete '${entry.path}' failed: ${e.message}")
                _browsing.value = false
                _errorText.value = e.message ?: "Could not delete that backup."
                return@launch
            }
            browseDestination(remote, folder)
        }
    }

    fun clearBrowse() {
        _browseEntries.value = emptyList()
        _browsePath.value = ""
    }

    // -- Transfer state ---------------------------------------------------

    /** Live download figures, so the sheet can show speed and time left. */
    data class TransferInfo(
        val name: String,
        val bytesDone: Long,
        val totalBytes: Long,
        val bytesPerSecond: Double,
        val secondsRemaining: Long?,
    ) {
        val fraction: Float
            get() = if (totalBytes > 0) (bytesDone.toFloat() / totalBytes) else 0f
    }

    private val _transfer = MutableStateFlow<TransferInfo?>(null)
    val transfer: StateFlow<TransferInfo?> = _transfer.asStateFlow()

    private var downloadCancel: com.openminis.app.backup.remote.RcloneChunkedUpload.CancelFlag? = null

    /** Ask the in-flight download to stop. */
    fun cancelDownload() {
        AppLogger.info(TAG, "[Restore] user cancelled download")
        downloadCancel?.cancel()
    }

    // -- Opening a package ------------------------------------------------

    /**
     * Stage of a package being opened, rotated while the work runs.
     *
     * Not a fake percentage: unzip reports nothing along the way, and inventing
     * a bar that jumps 0 -> 100 is worse than saying which step is underway.
     */
    private val _openStage = MutableStateFlow<Int?>(null)
    val openStage: StateFlow<Int?> = _openStage.asStateFlow()

    /**
     * [T-android-open-progress] What the open is ACTUALLY doing, as opposed to
     * which label a timer has reached.
     *
     * [files] and [bytes] count entries already written; [current] is the one
     * in flight. Null when nothing is open.
     *
     * There is deliberately no percentage: a forward `ZipInputStream` scan does
     * not know the entry count until it hits the end, and a bar that crawls to
     * 90% and then sits there is a worse lie than an honest running total. On a
     * multi-GB package the numbers moving is the signal that matters — it is
     * the difference between "working" and "hung".
     */
    data class OpenProgress(val files: Int, val bytes: Long, val current: String)

    private val _openProgress = MutableStateFlow<OpenProgress?>(null)
    val openProgress: StateFlow<OpenProgress?> = _openProgress.asStateFlow()

    /**
     * [T-android-restore-progress-counts] Live position of a running restore,
     * for the Start Restore button's label. Null when nothing is running.
     */
    private val _restoreProgress = MutableStateFlow<BackupImporter.Progress?>(null)
    val restoreProgress: StateFlow<BackupImporter.Progress?> = _restoreProgress.asStateFlow()

    /**
     * [T-android-restore-ui] Remaining-time estimate, in seconds, or null when
     * one cannot be made yet (first sample, a stall, or a category with no
     * declared total).
     *
     * A separate flow from [restoreProgress] on purpose: the two change on the
     * same tick, but keeping them apart lets the button observe only the
     * counter and the subtitle only the estimate, so neither recomposes for
     * the other's sake.
     */
    private val _restoreEtaSeconds = MutableStateFlow<Long?>(null)
    val restoreEtaSeconds: StateFlow<Long?> = _restoreEtaSeconds.asStateFlow()

    private val restoreEta = RestoreEta()

    /**
     * The running restore, held so [stopRunningRestore] can cancel it. Null
     * whenever no restore is in flight.
     */
    private var restoreJob: kotlinx.coroutines.Job? = null

    private var openJob: kotlinx.coroutines.Job? = null

    /** Abandon a slow package open. */
    fun cancelOpen() {
        AppLogger.info(TAG, "[Restore] user cancelled package open")
        openJob?.cancel()
    }

    /** Reset every restore-side piece of state. Called on teardown. */
    fun resetRestoreState() {
        clearBrowse()
        _transfer.value = null
        _openStage.value = null
        _serverPackages.value = emptyList()
        _errorText.value = null
        _statusText.value = null
    }

    /**
     * [T-backup-destination-browse] Delete one package from a destination and
     * refresh the list.
     *
     * A deleted package is gone for good, so the caller confirms first; this
     * only reports a failure, leaving the list as it was so the user can see
     * the file is still there.
     */
    fun deleteServerPackage(
        remote: com.openminis.app.backup.remote.RcloneRemoteStore.Remote,
        pkg: com.openminis.app.backup.remote.RcloneChunkedUpload.RemotePackage,
    ) {
        _isRunning.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val store = com.openminis.app.backup.remote.RcloneRemoteStore(getApplication())
                    store.syncToRclone()
                    com.openminis.app.backup.remote.RcloneChunkedUpload(getApplication())
                        .deletePackage(remote, pkg)
                }
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Backup] delete '${pkg.displayName}' failed: ${e.message}")
                _errorText.value = e.message ?: "Could not delete that backup."
            } finally {
                _isRunning.value = false
            }
            listServerPackages(remote)
        }
    }

    /** Download a remote package to a temp file, then load it for preview. */
    fun downloadServerPackage(
        pkg: com.openminis.app.backup.remote.RcloneChunkedUpload.RemotePackage,
        remote: com.openminis.app.backup.remote.RcloneRemoteStore.Remote,
    ) {
        _isRunning.value = true
        _report.value = null
        _errorText.value = null
        val flag = com.openminis.app.backup.remote.RcloneChunkedUpload.CancelFlag()
        downloadCancel = flag
        _transfer.value = TransferInfo(pkg.displayName, 0, pkg.size, 0.0, null)
        val dest = File(getApplication<Application>().cacheDir, "restore-server.minisbak")
        openJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val store = com.openminis.app.backup.remote.RcloneRemoteStore(getApplication())
                    store.syncToRclone()
                    com.openminis.app.backup.remote.RcloneChunkedUpload(getApplication())
                        .download(pkg, remote, dest, flag) { p ->
                            _transfer.value = TransferInfo(
                                pkg.displayName, p.bytesSent, p.totalBytes,
                                p.bytesPerSecond, p.secondsRemaining,
                            )
                        }
                }
                _transfer.value = null
                // Opening is its own phase with its own Cancel: unzipping a
                // multi-GB package takes long enough that a user who picked the
                // wrong one needs a way out that isn't force-quitting.
                openPackageFile(dest)
            } catch (e: kotlinx.coroutines.CancellationException) {
                AppLogger.info(TAG, "[Restore] download cancelled")
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { dest.delete() }
                _transfer.value = null
                _statusText.value = null
                throw e
            } catch (e: com.openminis.app.backup.remote.RcloneChunkedUpload.CancelledException) {
                // The transport cancelled the rclone job and already freed the
                // partial file; nothing to report, the user asked for this.
                AppLogger.info(TAG, "[Restore] download cancelled by user")
                _transfer.value = null
                _statusText.value = null
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Restore] server download failed: ${e.message}")
                _errorText.value = e.message ?: "Could not download that backup."
                _transfer.value = null
                _statusText.value = null
            } finally {
                downloadCancel = null
                _isRunning.value = false
            }
        }
    }

    /**
     * Extract + read a package's manifest, with a rotating stage label and a
     * working Cancel.
     *
     * The unzip itself cannot be interrupted partway, so cancellation is
     * honoured AFTER it returns: the result is discarded and no pending
     * restore is set. That still ends the wait for the user, which is the
     * point — the alternative was a screen with no way out.
     */
    private suspend fun openPackageFile(file: File) {
        _openStage.value = 0
        val ticker = viewModelScope.launch {
            // Advance through the stages and STOP on the last one rather than
            // looping: a label that cycles back to "Reading…" after four
            // minutes reads as though the work restarted.
            var stage = 0
            while (stage < OPEN_STAGE_COUNT - 1) {
                kotlinx.coroutines.delay(OPEN_STAGE_MS)
                stage++
                _openStage.value = stage
            }
        }
        try {
            val pending = withContext(Dispatchers.IO) { extractAndInspect(file) }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            setPending(pending)
            _serverPackages.value = emptyList()
            // NOT clearBrowse() here: the browser is still on screen until the
            // navigation triggered by `pending` completes, and emptying the
            // listing first flashed "no backups here" over a folder that was
            // full of them. The browser clears its own state on dispose.
        } finally {
            ticker.cancel()
            _openStage.value = null
            _openProgress.value = null
            _statusText.value = null
        }
    }

    private val _restoreSelected = MutableStateFlow<Set<BackupCategory>>(emptySet())
    val restoreSelected: StateFlow<Set<BackupCategory>> = _restoreSelected.asStateFlow()

    fun toggleRestoreCategory(category: BackupCategory, on: Boolean) {
        _restoreSelected.value = _restoreSelected.value.toMutableSet().apply {
            if (on) add(category) else remove(category)
        }
    }

    fun cancelRestore() {
        _pending.value?.extractedRoot?.deleteRecursively()
        _pending.value = null
        _restoreSelected.value = emptySet()
    }

    fun dismissReport() { _report.value = null }

    /** Confirm + run the restore of the currently pending package. */
    fun startRestore(passphrase: String?) {
        val pending = _pending.value ?: return
        if (isRunning.value) return
        val cats = _restoreSelected.value
        if (cats.isEmpty()) { _errorText.value = "Choose at least one thing to restore."; return }
        if (pending.manifest.encryption != null && passphrase.isNullOrEmpty()) {
            _errorText.value = "This backup is encrypted. Enter its passphrase to restore."
            return
        }
        _isRunning.value = true
        _statusText.value = "Restoring…"
        restoreEta.reset()
        _restoreEtaSeconds.value = null
        restoreJob = viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    BackupImporter(getApplication(), db).import(
                        pending.extractedRoot,
                        BackupImporter.Options(categories = cats, passphrase = passphrase),
                        onProgress = { line -> _statusText.value = line },
                        onCount = { p ->
                            _restoreProgress.value = p
                            _restoreEtaSeconds.value = restoreEta.update(p.categoryKey, p.done, p.total)
                        },
                    )
                }
                _report.value = report
                _restoreProgress.value = null
                _restoreEtaSeconds.value = null
                _pending.value?.extractedRoot?.deleteRecursively()
                _pending.value = null
                _statusText.value = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                // [T-android-restore-ui] The user pressed Stop. NOT an error:
                // rethrow so the coroutine machinery sees a normal
                // cancellation, and leave the UI copy to stopRunningRestore()
                // — which has already set it, and would otherwise be overwritten
                // here by the generic failure path below.
                //
                // This clause exists only because the generic failure handler
                // beneath it would otherwise swallow the cancellation and
                // report it to the user as "Restore failed".
                AppLogger.info(TAG, "[Restore] cancelled by user")
                throw e
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Restore] failed: ${e.message}")
                _errorText.value = e.message ?: "Restore failed."
                _statusText.value = null
                _restoreProgress.value = null
                _restoreEtaSeconds.value = null
            } finally {
                // [T-restore-stale-finally] Stop re-enables the button at once, so
                // a new restore can start before this (cancelled) job's finally
                // runs — withContext waits for the IO block to wind down. Only the
                // job that still owns the slot may clear it; a stale finally must
                // not null the newer job's handle or flip isRunning off under it.
                if (restoreJob === coroutineContext[kotlinx.coroutines.Job]) {
                    _isRunning.value = false
                    restoreJob = null
                    restoreEta.reset()
                }
            }
        }
    }

    /**
     * [T-android-restore-ui] Stops a running restore at the user's request.
     *
     * What survives: the importer writes into the live database and runs its
     * bulk loops inside transactions, so the one in flight rolls back whole
     * rather than leaving half-written rows — cancellation lands on a
     * `ensureActive()` check inside the loop, which throws out of the
     * transaction block. Earlier categories and batches stay written, with one
     * exception: [T-android-restore-cancel-empty-sessions] sessions the run
     * newly inserted whose messages were rolled back are deleted on the way
     * out, so a Stop mid-messages does not leave empty "No messages yet" rows.
     *
     * The UI copy is set HERE rather than in the job's catch clause, because
     * cancelling a coroutine does not guarantee its handlers run before this
     * method returns, and the user has to see the button change state on the
     * same frame they tapped.
     */
    fun stopRunningRestore() {
        val job = restoreJob ?: return
        AppLogger.info(TAG, "[Restore] stop requested by user")
        job.cancel()
        restoreJob = null
        _isRunning.value = false
        _restoreProgress.value = null
        _restoreEtaSeconds.value = null
        restoreEta.reset()
        _statusText.value = null
    }

    // -- Persistence helpers ---------------------------------------------

    private fun loadSelectedCategories(): Set<BackupCategory> {
        val raw = prefs.getString(KEY_CATEGORIES, null) ?: return BackupCategory.backupable.toSet()
        val restored = raw.split(",").mapNotNull(BackupCategory::fromKey)
            .filter { it in BackupCategory.backupable }.toSet()
        return restored.ifEmpty { BackupCategory.backupable.toSet() }
    }


    companion object {
        private const val TAG = "BackupViewModel"

        /** Stage labels shown while a package is being opened. */
        const val OPEN_STAGE_COUNT = 4
        private const val OPEN_STAGE_MS = 2_500L

        /**
         * Stored sentinel for a user-cancelled run, mapped to a translated
         * string at render time — the same treatment as
         * [BackupHistory.INTERRUPTED_MARKER], and for the same reason: it is
         * persisted, so it must not be locale-dependent.
         */
        const val STOPPED_MARKER = BackupRunController.STOPPED_MARKER

        private const val KEY_CATEGORIES = "selectedCategories"
        private const val KEY_ENCRYPT = "encrypt"
        private const val KEY_MAX_FILE_MB = "maxFileSizeMB"
    }
}
