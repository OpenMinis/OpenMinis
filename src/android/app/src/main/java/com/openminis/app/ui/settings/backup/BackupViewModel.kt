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
import com.openminis.app.backup.BackupZip
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /**
     * [T-android-backup-stop] The in-flight export, held so it can be
     * cancelled. iOS keeps the same handle in BackupRunController for the same
     * reason: "a running task nobody holds is one nobody can stop" — and the
     * screen can be left mid-backup.
     */
    private var exportJob: kotlinx.coroutines.Job? = null

    /**
     * Stop the running backup.
     *
     * Cancels at the next suspension point rather than killing mid-write. The
     * package under construction is left where it is; Start Backup begins a
     * NEW run of the data as it stands then, matching iOS (which only resumes
     * a stopped run from its own history row).
     */
    fun stopExport() {
        val job = exportJob ?: return
        AppLogger.info(TAG, "[Backup] user requested stop")
        job.cancel()
    }

    /**
     * [T-android-backup-destination-gate] Enabled rclone destinations, refreshed
     * whenever the screen appears (the user may have just added one and come
     * back). Mirrors iOS `BackupSettingsView.hasDestination`.
     *
     * This exists because a backup with NO destination only ever reaches the
     * app's own sandbox — where it dies with the very app it exists to protect.
     * iOS refuses to produce one and says so; Android used to run the export
     * anyway and show "Backup ready", which reads as success.
     */
    private val _destinations =
        MutableStateFlow<List<com.openminis.app.backup.remote.RcloneRemoteStore.Remote>>(emptyList())
    val destinations: StateFlow<List<com.openminis.app.backup.remote.RcloneRemoteStore.Remote>> =
        _destinations.asStateFlow()

    /**
     * True when at least one ENABLED destination can receive the package.
     *
     * Note the difference from [destinations], which lists every configured
     * server: a user who switched all of them off has destinations but nowhere
     * for the package to go, so the Start button must still refuse.
     */
    val hasDestination: Boolean get() = _destinations.value.any { it.enabled }

    /**
     * Re-read configured destinations. Call on screen resume.
     *
     * Lists ALL remotes, not just the enabled ones (iOS parity). Filtering to
     * `enabledRemotes` here made a disabled destination vanish from the screen
     * entirely — the user could neither see that it still existed nor switch it
     * back on, which is the opposite of what disabling is for: it keeps the
     * server and its credential precisely so skipping one for a while costs
     * nothing to undo.
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

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    /** Non-null once an export finished and its package is ready to share/save. */
    private val _exportReady = MutableStateFlow<ExportResult?>(null)
    val exportReady: StateFlow<ExportResult?> = _exportReady.asStateFlow()

    private val _errorText = MutableStateFlow<String?>(null)
    val errorText: StateFlow<String?> = _errorText.asStateFlow()

    /**
     * [T-android-backup-transient-success] The run that just finished, shown
     * under the Start button until the user next arrives on this screen.
     *
     * Deliberately transient: the same facts live permanently in Backup
     * History, so keeping the card forever would make every later visit open
     * onto a stale result. Cleared by [clearSettledSuccess] on appear.
     */
    private val _lastResult = MutableStateFlow<RunResult?>(null)
    val lastResult: StateFlow<RunResult?> = _lastResult.asStateFlow()

    data class RunResult(
        val totalBytes: Long,
        val skippedFiles: Int,
        val destinations: List<BackupHistory.DestinationOutcome>,
        val localCopyRemoved: Boolean = false,
    ) {
        val allDelivered: Boolean
            get() = destinations.isNotEmpty() && destinations.all { it.succeeded }
    }

    /**
     * Drop the finished card, but ONLY when it reports a settled, fully
     * successful run.
     *
     * A run still in flight obviously stays. A run with a failed destination
     * stays too: that is the one result a user actually needs to come back
     * to, and it is the case where "it vanished before I read it" costs them
     * something. Mirrors iOS `clearIfSettledAndSuccessful`.
     */
    fun clearSettledSuccess() {
        if (_isRunning.value) return
        val r = _lastResult.value ?: return
        if (r.allDelivered) _lastResult.value = null
    }

    data class ExportResult(
        val packageFile: File,
        val totalBytes: Long,
        val skippedFiles: Int,
    )

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

    fun clearError() { _errorText.value = null }
    fun clearExportReady() { _exportReady.value = null }

    /**
     * Run an export. [passphrase] must be non-empty when [encrypt] is on.
     *
     * [T-backup-credentials-without-encryption] Credentials are ALWAYS
     * included — `includeCredentials = true` unconditionally, no longer
     * derived from [encrypt]. Deriving it meant the default (unencrypted)
     * export restored a half-working device: providers with no key, env vars
     * with no value, and nothing anywhere saying so. A backup exists to
     * reconstitute a device, so it carries what that takes; the encryption
     * footer states plainly what an unencrypted package contains.
     *
     * Only the PASSPHRASE still depends on [encrypt] — that is what "not
     * encrypted" means. Matches iOS 08904c7b1; the two sides must agree or
     * packages stop being interchangeable.
     */
    fun startExport(passphrase: String?) {
        if (_isRunning.value) return
        val cats = _selected.value
        if (cats.isEmpty()) { _errorText.value = "Choose at least one thing to include."; return }
        // [T-android-backup-destination-gate] Refuse to produce a package that
        // can only land in our own sandbox. Re-read here rather than trusting
        // the cached list: the user may have removed the last destination in
        // another screen since this one was composed.
        refreshDestinations()
        if (!hasDestination) {
            _errorText.value = getApplication<Application>()
                .getString(com.openminis.app.R.string.backup_needs_destination)
            return
        }
        val encrypting = _encrypt.value
        if (encrypting && passphrase.isNullOrEmpty()) {
            _errorText.value = "Set a passphrase to encrypt this backup."
            return
        }
        _isRunning.value = true
        _lastResult.value = null
        _statusText.value = "Starting…"
        _exportReady.value = null
        // [T-android-backup-history] Open the record BEFORE any work, so a run
        // killed mid-flight still leaves evidence. BackupHistory reconciles a
        // record left RUNNING into FAILED on next launch.
        val log = mutableListOf<BackupHistory.LogEntry>()
        var record = BackupHistory.Record(
            backupId = "",
            startedAt = System.currentTimeMillis(),
            status = BackupHistory.Status.RUNNING,
            categories = cats.map { it.key }.sorted(),
            encrypted = encrypting,
        )
        history.upsert(record)
        _historyRecords.value = history.records()

        fun note(line: String, problem: Boolean = false) {
            log.add(BackupHistory.LogEntry(System.currentTimeMillis(), line, problem))
        }
        exportJob = viewModelScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    BackupExporter(getApplication(), db).export(
                        BackupExporter.Options(
                            categories = cats,
                            maxFileBytes = maxFileBytesOption(),
                            // Unconditional — see the KDoc above. Only the
                            // passphrase tracks `encrypting`.
                            includeCredentials = true,
                            passphrase = passphrase?.takeIf { encrypting },
                        ),
                    ) { line ->
                        _statusText.value = line
                        note(line)
                        // Push the live line into the record so the history row
                        // can show it. iOS does the same: the running row's
                        // subtitle IS `record.log.last`, so progress lives with
                        // the run rather than inside the button.
                        publishRunning(record, log)
                    }
                }

                // Deliver to every enabled rclone remote. Failures are surfaced
                // but do NOT discard the local package — the user can still
                // Share / Save it, and re-run delivery later. Mirrors iOS's
                // "package is ready even if a destination failed" behaviour.
                val outcomes = withContext(Dispatchers.IO) {
                    deliverToRemotes(summary.packageFile, summary.backupId) { line ->
                        _statusText.value = line
                        note(line)
                        publishRunning(record, log)
                    }
                }
                outcomes.forEach {
                    note(
                        if (it.succeeded) "Delivered to ${it.name}"
                        else "Delivery to ${it.name} failed: ${it.detail}",
                        problem = !it.succeeded,
                    )
                }
                // [T-android-backup-local-cleanup] The local package is a
                // FALLBACK, not an archive. Once every enabled destination
                // holds a verified copy, a third one on the phone just spends
                // the user's storage — this device was accumulating 32 MB a
                // run, permanently. Any failure, or having no destinations at
                // all, keeps it, so the backup always exists somewhere.
                var localRemoved = false
                if (outcomes.isNotEmpty() && outcomes.all { it.succeeded }) {
                    val freed = summary.packageFile.length()
                    if (withContext(Dispatchers.IO) { summary.packageFile.delete() }) {
                        localRemoved = true
                        // Say so. Deleting the package is the right call once
                        // every destination is verified, but doing it silently
                        // is the wrong way: the user has no way to tell
                        // "cleaned up" from "the backup is gone". The line
                        // also lands in the record, because "did it clean up
                        // after itself?" is asked long after the card is gone.
                        val msg = getApplication<Application>().getString(
                            com.openminis.app.R.string.backup_local_removed,
                            humanBytesPlain(freed),
                        )
                        AppLogger.info(TAG, "[Backup] $msg")
                        note(msg)
                    }
                } else if (outcomes.isNotEmpty()) {
                    note(
                        getApplication<Application>()
                            .getString(com.openminis.app.R.string.backup_local_kept),
                    )
                }

                _exportReady.value = ExportResult(
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
                    // A run that produced a package but couldn't reach every
                    // destination, or dropped files at the cap, is neither a
                    // clean success nor a failure — that distinction is the
                    // whole point of the record.
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
                _statusText.value = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Stopped by the user. Recorded as a real outcome rather than
                // vanishing: a run that was cancelled deliberately still
                // explains where the time went, and leaving it RUNNING would
                // render as a permanent spinner.
                AppLogger.info(TAG, "[Backup] export cancelled by user")
                note(STOPPED_MARKER, problem = true)
                record = record.copy(
                    finishedAt = System.currentTimeMillis(),
                    status = BackupHistory.Status.FAILED,
                    errorMessage = STOPPED_MARKER,
                    log = log.toList(),
                )
                _statusText.value = null
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
                _statusText.value = null
            } finally {
                exportJob = null
                history.upsert(record)
                _historyRecords.value = history.records()
                _isRunning.value = false
            }
        }
    }

    /**
     * Write the in-flight record so the history list reflects it live.
     *
     * Cheap enough per progress line (one small JSON file), and the alternative
     * — updating only at the end — is what left a running backup invisible in
     * the very list that exists to report it.
     */
    private fun publishRunning(base: BackupHistory.Record, log: List<BackupHistory.LogEntry>) {
        val snapshot = base.copy(log = log.toList())
        history.upsert(snapshot)
        _historyRecords.value = history.records()
    }

    // -- History ----------------------------------------------------------

    private val history by lazy { BackupHistory.get(getApplication()) }

    private val _historyRecords = MutableStateFlow<List<BackupHistory.Record>>(emptyList())
    val historyRecords: StateFlow<List<BackupHistory.Record>> = _historyRecords.asStateFlow()

    fun refreshHistory() { _historyRecords.value = history.records() }

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
    fun removeHistoryRecordWithFiles(id: String) {
        val record = history.records().firstOrNull { it.id == id }
        val name = record?.packageName
        if (record == null || name.isNullOrEmpty()) {
            removeHistoryRecord(id)
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val store = com.openminis.app.backup.remote.RcloneRemoteStore(getApplication())
                    store.syncToRclone()
                    val uploader =
                        com.openminis.app.backup.remote.RcloneChunkedUpload(getApplication())
                    for (outcome in record.destinations.filter { it.succeeded }) {
                        val remote = store.remotes.firstOrNull { it.name == outcome.name }
                            ?: continue
                        runCatching { uploader.deletePackage(remote, name) }
                            .onFailure {
                                AppLogger.error(
                                    TAG,
                                    "[Backup] deleting '$name' from '${outcome.name}' failed: ${it.message}",
                                )
                            }
                    }
                }
            }
            removeHistoryRecord(id)
        }
    }

    fun removeHistoryRecord(id: String) {
        history.remove(id)
        _historyRecords.value = history.records()
    }

    /**
     * Upload the produced package to every enabled rclone remote as a single
     * verified `.minisbak` (see RcloneChunkedUpload, which no longer chunks).
     * Returns a list of "name: reason"
     * strings for remotes that failed; empty when all succeeded (or none are
     * configured). Never throws — a failed remote must not lose the local copy.
     */
    private fun deliverToRemotes(
        packageFile: File,
        backupId: String,
        onProgress: (String) -> Unit,
    ): List<BackupHistory.DestinationOutcome> {
        val store = com.openminis.app.backup.remote.RcloneRemoteStore(getApplication())
        val enabled = store.enabledRemotes
        if (enabled.isEmpty()) return emptyList()
        store.syncToRclone()
        val uploader = com.openminis.app.backup.remote.RcloneChunkedUpload(getApplication())
        // [T-android-backup-history] Report EVERY destination, not just the
        // failures. "Which servers did last night's backup actually reach?"
        // was unanswerable while only errors were returned.
        val outcomes = mutableListOf<BackupHistory.DestinationOutcome>()
        for (remote in enabled) {
            try {
                onProgress("Sending to ${remote.name}…")
                uploader.upload(packageFile, remote, backupId) { p ->
                    val pct = if (p.totalBytes > 0) (p.bytesSent * 100 / p.totalBytes) else 0
                    onProgress("Sending to ${remote.name}… $pct%")
                }
                outcomes.add(
                    BackupHistory.DestinationOutcome(
                        remote.name, succeeded = true,
                        kind = remote.backend, path = remote.path,
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
        if (_isRunning.value) return
        val cats = _restoreSelected.value
        if (cats.isEmpty()) { _errorText.value = "Choose at least one thing to restore."; return }
        if (pending.manifest.encryption != null && passphrase.isNullOrEmpty()) {
            _errorText.value = "This backup is encrypted. Enter its passphrase to restore."
            return
        }
        _isRunning.value = true
        _statusText.value = "Restoring…"
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    BackupImporter(getApplication(), db).import(
                        pending.extractedRoot,
                        BackupImporter.Options(categories = cats, passphrase = passphrase),
                        onProgress = { line -> _statusText.value = line },
                        onCount = { _restoreProgress.value = it },
                    )
                }
                _report.value = report
                _restoreProgress.value = null
                _pending.value?.extractedRoot?.deleteRecursively()
                _pending.value = null
                _statusText.value = null
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Restore] failed: ${e.message}")
                _errorText.value = e.message ?: "Restore failed."
                _statusText.value = null
            } finally {
                _isRunning.value = false
            }
        }
    }

    // -- Persistence helpers ---------------------------------------------

    private fun loadSelectedCategories(): Set<BackupCategory> {
        val raw = prefs.getString(KEY_CATEGORIES, null) ?: return BackupCategory.backupable.toSet()
        val restored = raw.split(",").mapNotNull(BackupCategory::fromKey)
            .filter { it in BackupCategory.backupable }.toSet()
        return restored.ifEmpty { BackupCategory.backupable.toSet() }
    }

    /** Bytes as "32.0 MB", for a message rather than a UI row. */
    private fun humanBytesPlain(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1e6)
        bytes >= 1_000 -> String.format(java.util.Locale.US, "%.0f kB", bytes / 1e3)
        else -> "$bytes B"
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
        const val STOPPED_MARKER = "stopped"

        private const val KEY_CATEGORIES = "selectedCategories"
        private const val KEY_ENCRYPT = "encrypt"
        private const val KEY_MAX_FILE_MB = "maxFileSizeMB"
    }
}
