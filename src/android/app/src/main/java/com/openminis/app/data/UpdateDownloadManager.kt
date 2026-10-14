package com.openminis.app.data

import android.content.Context
import android.net.ConnectivityManager
import com.openminis.app.BuildConfig
import com.openminis.app.logging.AppLogger
import com.openminis.app.network.withDohDns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Resumable, background-tolerant APK downloader for app updates.
 *
 * Design notes:
 *
 *  - **User-selected source.** [T-update-source-choice] The old mirror race
 *    (probe every mirror, stream from the fastest) is gone: the update dialog
 *    lists sources with live reachability probes, the user picks one, and
 *    [start] honors that choice via [UpdateSourceRegistry.fallbackOrder].
 *    Fallback to the remaining sources on error is a safety net that reports
 *    which source actually served the bytes — not a selection mechanism.
 *
 *  - **Background tolerant.** The download runs on a process-wide
 *    [CoroutineScope] (SupervisorJob + IO), NOT the composable's scope, so
 *    leaving the About screen (or the whole settings) does not cancel it.
 *    UI re-attaches by collecting [state]. A partial download survives a
 *    process kill as a `.part` file; the next attempt resumes via HTTP Range.
 *
 *  - **Resume with ETag.** We stream into `<name>.part` and rename to the
 *    final name on completion. A `.part` from an interrupted run is resumed
 *    from its length with `Range: bytes=N-` plus `If-Range: <etag>` — if the
 *    upstream asset was re-published the server answers 200 (not 206) and we
 *    restart cleanly instead of splicing two different files together.
 *
 *  - **Integrity gates.** After the final rename the APK's sha256 is compared
 *    against the GitHub asset digest (when the API provided one) BEFORE the
 *    pending-install record is written; a mismatch deletes the file and
 *    surfaces an error. The signature-certificate gate runs later, at
 *    install time (see UpdateChecker.verifyApkSignature).
 */
object UpdateDownloadManager {

    private const val TAG = "UpdateDownloadManager"
    private const val PROGRESS_NOTIFY_MIN_MS = 400L
    private const val ETAG_SUFFIX = ".etag"
    private const val UPDATE_PREFS = "update_download_prefs"
    private const val KEY_WIFI_ONLY = "wifi_only"

    private val client = OkHttpClient.Builder()
        // [T-doh-resolver-fallback] A failed APK download is expensive enough
        // that a name-resolution fix is worth taking.
        .withDohDns()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS) // no overall cap; big files stream a while
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Observable download state; the UI collects this to render progress. */
    data class DownloadState(
        val running: Boolean = false,
        /** 0..1 when total size known, else -1 (indeterminate). */
        val progress: Float = 0f,
        val totalBytes: Long = -1L,
        val downloadedBytes: Long = 0L,
        /** The source currently serving bytes (host), for the status line. */
        val activeNode: String? = null,
        /** Registry id of the source currently serving bytes. */
        val activeSourceId: String? = null,
        /** True while we are still probing sources (download not yet started). */
        val probing: Boolean = false,
        val doneFile: File? = null,
        val error: String? = null,
        /** True once the OS installer intent has been fired for doneFile. */
        val installLaunched: Boolean = false,
        /**
         * True when the start request was refused because the active network
         * is metered and the user has not confirmed the download yet. The UI
         * shows a confirm dialog and re-calls [start] with allowMetered=true.
         */
        val needsMeteredConfirm: Boolean = false,
    )

    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    // Process-wide scope so the download outlives any single screen.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Parameters of the last start() call, replayed by [confirmMetered]. */
    private data class StartRequest(
        val apkUrl: String,
        val versionName: String,
        val expectedSizeBytes: Long,
        val expectedSha256: String?,
        val preferredSourceId: String?,
    )

    private var lastRequest: StartRequest? = null

    /**
     * Start (or restart) a download of [apkUrl] for [versionName]. If one is
     * already running this is a no-op; the caller simply keeps collecting
     * [state].
     *
     * [preferredSourceId] is the user's chosen source (see
     * [UpdateSourceRegistry]); the download tries it first and falls back to
     * the remaining sources in registry order when it errors. [expectedSha256]
     * is the GitHub asset digest — when non-null the completed file must hash
     * to exactly this or the download is discarded.
     *
     * Metered-network guard: when the active network is metered and
     * [allowMetered] is false, the request is parked (state.needsMeteredConfirm
     * = true) instead of silently burning the user's data plan; the UI
     * confirms and re-invokes via [confirmMetered].
     *
     * If a COMPLETE APK for this exact version is already staged on disk it is
     * reused instead of re-fetched. This matters because a finished download
     * leaves no `.part` behind (it is renamed to the final name), so
     * [downloadWithResume] would otherwise restart from byte 0 — that is how
     * "cancel the system installer, tap download again" turned into a second
     * 98 MB transfer for a file we already had.
     *
     * [expectedSizeBytes] is the release asset size when known; a staged file
     * whose length disagrees is treated as a truncated leftover and refetched.
     */
    fun start(
        context: Context,
        apkUrl: String,
        versionName: String,
        expectedSizeBytes: Long = -1L,
        expectedSha256: String? = null,
        preferredSourceId: String? = null,
        allowMetered: Boolean = false,
    ) {
        if (job?.isActive == true) return
        val appCtx = context.applicationContext
        val outDir = File(appCtx.filesDir, "updates").apply { mkdirs() }
        val safeName = "minis-" + versionName.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"
        val finalFile = File(outDir, safeName)
        val partFile = File(outDir, "$safeName.part")

        lastRequest = StartRequest(apkUrl, versionName, expectedSizeBytes, expectedSha256, preferredSourceId)

        // [T-update-wifi-only] The user's standing preference outranks the
        // confirmation flow: refuse outright, with an error the UI shows.
        if (isWifiOnlyPreferred(appCtx) && isActiveNetworkMetered(appCtx)) {
            val msg = "Wi-Fi only is enabled; metered network active"
            AppLogger.info(TAG, msg)
            _state.value = DownloadState(running = false, error = msg)
            return
        }
        // [T-update-metered-guard] Refuse to silently stream 130 MB over a
        // metered connection. The UI observes needsMeteredConfirm and asks.
        if (!allowMetered && isActiveNetworkMetered(appCtx)) {
            AppLogger.info(TAG, "metered network active; parking download request for confirmation")
            _state.value = DownloadState(running = false, needsMeteredConfirm = true)
            return
        }

        // [T-update-space-guard] The download needs room for the file itself
        // plus the installer's extraction working set — check for 2x up front
        // instead of dying at 95% with ENOSPC.
        if (expectedSizeBytes > 0L) {
            val needed = expectedSizeBytes * 2L
            // Fast path: if even the optimistic estimate says no, don't
            // bother writing a probe file.
            val usable = outDir.usableSpace
            if (usable < needed) {
                val msg = "insufficient space: need ~${needed / (1024L * 1024L)} MB, free ${usable / (1024L * 1024L)} MB"
                AppLogger.warning(TAG, msg)
                _state.value = DownloadState(running = false, error = msg)
                return
            }
            // Honest path: the estimate lies on shared/reserved volumes —
            // actually write the bytes before committing to a 130 MB stream.
            if (!probeWritableSpace(outDir, needed)) {
                val msg = "insufficient space: could not write ${needed / (1024L * 1024L)} MB (probe failed)"
                AppLogger.warning(TAG, msg)
                _state.value = DownloadState(running = false, error = msg)
                return
            }
        }

        // [T-update-download-fgs] Hold a dataSync foreground slot + mirror
        // progress into a notification while the download runs, so leaving
        // the app neither kills the process nor hides the progress.
        UpdateDownloadService.ensureStarted(appCtx)
        _state.value = DownloadState(running = true, probing = true, progress = 0f)
        job = scope.launch {
            try {
                if (reusableCompleteApk(finalFile, expectedSizeBytes)) {
                    AppLogger.info(
                        TAG,
                        "reusing complete APK ${finalFile.name} size=${finalFile.length()} — skipping download",
                    )
                    publishDownloaded(appCtx, finalFile, versionName, expectedSha256)
                    return@launch
                }
                // [T-update-source-choice] User's pick first, then the rest of
                // the registry as a fallback chain. Each failure moves to the
                // next source; only when every source fails do we surface the
                // last error.
                val chain = UpdateSourceRegistry.fallbackOrder(preferredSourceId)
                var lastError: Exception? = null
                for (source in chain) {
                    val url = source.resolve(apkUrl)
                    try {
                        _state.value = _state.value.copy(
                            probing = false,
                            activeSourceId = source.id,
                            activeNode = hostOf(url),
                        )
                        AppLogger.info(TAG, "downloading from ${source.id}: $url")
                        downloadWithResume(url, partFile, finalFile, expectedSizeBytes)
                        publishDownloaded(appCtx, finalFile, versionName, expectedSha256)
                        return@launch
                    } catch (e: Exception) {
                        lastError = e
                        AppLogger.warning(
                            TAG,
                            "source ${source.id} failed: ${e.javaClass.simpleName}: ${e.message}; trying next",
                        )
                        // A corrupt splice must not survive into the next
                        // source's resume attempt.
                        if (e is IntegrityException) {
                            partFile.delete()
                            etagFileFor(partFile).delete()
                        }
                    }
                }
                throw lastError ?: IllegalStateException("no download sources available")
            } catch (e: Exception) {
                AppLogger.error(TAG, "download failed: ${e.javaClass.simpleName}: ${e.message}")
                _state.value = _state.value.copy(
                    running = false,
                    probing = false,
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    /** Replay the parked metered-network request after user confirmation. */
    fun confirmMetered(context: Context) {
        val req = lastRequest ?: return
        _state.value = _state.value.copy(needsMeteredConfirm = false)
        start(
            context,
            req.apkUrl,
            req.versionName,
            req.expectedSizeBytes,
            req.expectedSha256,
            req.preferredSourceId,
            allowMetered = true,
        )
    }

    /** Dismiss the metered-network confirmation without downloading. */
    fun dismissMeteredConfirm() {
        _state.value = _state.value.copy(needsMeteredConfirm = false)
    }

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: url
    } catch (_: Exception) {
        url
    }

    /**
     * [T-update-wifi-only] User preference: never download updates over a
     * metered network, no confirmation dialog. Stronger than the metered
     * guard (which asks); this one refuses outright.
     */
    fun isWifiOnlyPreferred(context: Context): Boolean =
        context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIFI_ONLY, false)

    fun setWifiOnlyPreferred(context: Context, value: Boolean) {
        context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WIFI_ONLY, value).apply()
    }

    /**
     * [T-update-space-probe] usableSpace/freeSpace report what the
     * filesystem *claims*; shared volumes (emulated storage) and OEM
     * reserved blocks make them optimistic. The only honest check is to
     * actually write the bytes. We probe with the full 2x requirement
     * (download copy + installer extraction working set) in 1 MB chunks
     * and delete the probe immediately — a few seconds of flash wear for
     * a guarantee ENOSPC can't ambush us at 95%.
     */
    private fun probeWritableSpace(dir: File, neededBytes: Long): Boolean {
        val probe = File(dir, ".space-probe")
        return try {
            RandomAccessFile(probe, "rw").use { raf ->
                val buf = ByteArray(1024 * 1024)
                var written = 0L
                while (written < neededBytes) {
                    val chunk = minOf(buf.size.toLong(), neededBytes - written).toInt()
                    raf.write(buf, 0, chunk)
                    written += chunk
                }
            }
            true
        } catch (e: Exception) {
            AppLogger.warning(TAG, "space probe failed at needed=$neededBytes: ${e.javaClass.simpleName}")
            false
        } finally {
            runCatching { probe.delete() }
        }
    }

    private fun isActiveNetworkMetered(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.isActiveNetworkMetered ?: false
    } catch (_: Exception) {
        false
    }

    /** Raised when a completed download fails its sha256 gate. */
    private class IntegrityException(message: String) : IllegalStateException(message)

    /**
     * True when [f] is a complete staged APK for the version being requested.
     * [expectedSize] <= 0 means the release asset size was unknown, in which
     * case any non-empty file is accepted.
     */
    private fun reusableCompleteApk(f: File, expectedSize: Long): Boolean {
        if (!f.exists() || f.length() <= 0L) return false
        if (expectedSize > 0L && f.length() != expectedSize) {
            AppLogger.warning(
                TAG,
                "staged APK ${f.name} len=${f.length()} != expected=$expectedSize; refetching",
            )
            return false
        }
        return true
    }

    /** Persist the pending record and publish the completed state. */
    private fun publishDownloaded(
        appCtx: Context,
        file: File,
        versionName: String,
        expectedSha256: String?,
    ) {
        // [T-update-sha-gate] The GitHub asset digest is authoritative when
        // present: hash the finished file and refuse to stage it on mismatch.
        // A wrong-size or corrupted splice would otherwise be recorded as a
        // perfectly installable pending update.
        if (expectedSha256.isNullOrBlank()) {
            // [T-update-sha-required] …and its ABSENCE is not a licence to skip
            // the check. Nothing else anchors this download: the mirror sources
            // resolve the same asset URL (so there is no independent attestation),
            // and the install-time gate only compares the signing certificate
            // against the running install — a certificate whose keystore is
            // published in the repository. An unadvertised-checksum APK was
            // therefore installable on trust alone. Refuse it; the release page
            // keeps the manual path open.
            AppLogger.error(TAG, "release asset advertises no sha256 — refusing to stage ${file.name}")
            file.delete()
            _state.value = _state.value.copy(
                running = false,
                probing = false,
                error = "update refused: the release asset carries no published checksum " +
                    "(install manually from the release page)",
            )
            return
        }
        val actual = runCatching { PendingUpdateStore.sha256(file) }.getOrNull()
        if (actual == null || !actual.equals(expectedSha256, ignoreCase = true)) {
            AppLogger.error(
                TAG,
                "sha256 mismatch: expected=$expectedSha256 actual=$actual — discarding ${file.name}",
            )
            file.delete()
            _state.value = _state.value.copy(
                running = false,
                probing = false,
                error = "integrity check failed (sha256 mismatch)",
            )
            return
        }
        PendingUpdateStore.setPending(
            appCtx,
            PendingUpdateStore.PendingUpdate(
                targetVersionName = versionName,
                apkPath = file.absolutePath,
                apkSize = file.length(),
                sha256 = expectedSha256,
                downloadedAtMs = System.currentTimeMillis(),
            ),
        )
        // The download request is satisfied — drop the "waiting on permission"
        // intent so a later resume doesn't try to start it a second time.
        PendingUpdateStore.clearPendingIntent(appCtx)
        _state.value = _state.value.copy(
            running = false,
            probing = false,
            progress = 1f,
            doneFile = file,
            needsMeteredConfirm = false,
        )
        AppLogger.info(TAG, "download complete ${file.absolutePath} size=${file.length()}")
    }

    /** Cancel the in-flight download (keeps the .part file for later resume). */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = false, probing = false)
    }

    /** Mark that the system installer was launched (so cleanup can treat it as installed). */
    fun markInstallLaunched() {
        _state.value = _state.value.copy(installLaunched = true)
    }

    private fun etagFileFor(partFile: File): File =
        File(partFile.parentFile, partFile.name + ETAG_SUFFIX)

    /**
     * Stream [url] into [partFile], resuming from its existing length via
     * Range + If-Range, then atomically rename to [finalFile]. Progress
     * reported to [state] throttled to ~[PROGRESS_NOTIFY_MIN_MS].
     *
     * The ETag from the first response is persisted next to the `.part` file;
     * on resume it is sent as `If-Range`. Per RFC 7233 the server then answers
     * 206 only when the entity is unchanged — a re-published asset yields 200
     * and the caller restarts from byte 0 instead of splicing two different
     * files into one corrupt APK.
     */
    private fun downloadWithResume(
        url: String,
        partFile: File,
        finalFile: File,
        expectedSizeBytes: Long = -1L,
    ) {
        val existing = if (partFile.exists()) partFile.length() else 0L
        val etagFile = etagFileFor(partFile)
        val savedEtag = if (existing > 0 && etagFile.exists()) etagFile.readText().trim() else null

        val reqBuilder = Request.Builder().url(url)
        if (existing > 0) {
            reqBuilder.header("Range", "bytes=$existing-")
            // If-Range turns a stale resume into a clean 200 restart instead
            // of a corrupt splice when the upstream asset was re-published.
            if (!savedEtag.isNullOrEmpty()) reqBuilder.header("If-Range", savedEtag)
        }
        val req = reqBuilder.build()

        client.newCall(req).execute().use { resp ->
            var resumeFrom = existing
            when (resp.code) {
                200 -> {
                    // Server ignored our Range (or If-Range said the entity
                    // changed) — restart cleanly.
                    if (existing > 0) {
                        AppLogger.warning(TAG, "server ignored Range / entity changed; restarting from 0")
                        resumeFrom = 0L
                        partFile.delete()
                        etagFile.delete()
                    }
                }
                206 -> {
                    // Content-Range must start exactly at our existing
                    // length. A server that answers 206 with a body from
                    // byte 0 (non-compliant mirror) would splice a full
                    // copy onto the .part tail; sha256 catches it only
                    // after wasting a full transfer. Refuse up front.
                    val cr = resp.header("Content-Range")
                    val start = cr?.let { h ->
                        Regex("bytes (\\d+)-").find(h)?.groupValues?.get(1)?.toLongOrNull()
                    }
                    if (existing > 0 && start != null && start != existing) {
                        AppLogger.warning(
                            TAG,
                            "206 Content-Range starts at $start but .part has $existing bytes; refusing splice",
                        )
                        partFile.delete()
                        etagFile.delete()
                        throw IntegrityException("server resumed from wrong offset ($start != $existing)")
                    }
                }
                416 -> {
                    // Range not satisfiable. That means the part is complete only
                    // when its length matches the published asset. A shorter part
                    // (mirror rejected Range) must not be installed as the APK.
                    val sizeOk = expectedSizeBytes <= 0L || existing == expectedSizeBytes
                    if (existing > 0 && sizeOk) {
                        AppLogger.info(TAG, "416: treating existing .part as complete")
                        promotePart(partFile, finalFile)
                        return
                    }
                    if (existing > 0) {
                        AppLogger.warning(
                            TAG,
                            "416 but part $existing != expected $expectedSizeBytes; discarding part",
                        )
                        partFile.delete()
                        etagFile.delete()
                    }
                    throw IllegalStateException("HTTP 416 with incomplete local file")
                }
                else -> throw IllegalStateException("HTTP ${resp.code}")
            }
            // Persist the ETag for the next interrupted-resume attempt.
            val etag = resp.header("ETag")
            if (!etag.isNullOrEmpty()) {
                runCatching { etagFile.writeText(etag) }
            }
            val body = resp.body ?: throw IllegalStateException("empty body")
            val contentLen = body.contentLength().takeIf { it > 0 } ?: -1L
            val total = if (contentLen > 0) resumeFrom + contentLen else -1L
            if (expectedSizeBytes > 0 && total > 0 && total != expectedSizeBytes) {
                // The resolved URL is not serving the published asset — a
                // parked lander or a swapped mirror file. Bail before writing
                // a single byte of it.
                AppLogger.warning(
                    TAG,
                    "content length $total != expected asset size $expectedSizeBytes; refusing",
                )
                partFile.delete()
                etagFile.delete()
                throw IntegrityException("source served $total bytes, expected $expectedSizeBytes")
            }

            _state.value = _state.value.copy(totalBytes = total, downloadedBytes = resumeFrom)

            body.byteStream().use { input ->
                RandomAccessFile(partFile, "rw").use { raf ->
                    raf.seek(resumeFrom)
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var downloaded = resumeFrom
                    var lastNotify = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        raf.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastNotify >= PROGRESS_NOTIFY_MIN_MS) {
                            lastNotify = now
                            val pct = if (total > 0) downloaded.toFloat() / total.toFloat() else -1f
                            _state.value = _state.value.copy(
                                progress = pct,
                                downloadedBytes = downloaded,
                            )
                        }
                    }
                    raf.fd.sync()
                }
            }
            _state.value = _state.value.copy(progress = 1f, downloadedBytes = total)
        }

        promotePart(partFile, finalFile)
    }

    private fun promotePart(partFile: File, finalFile: File) {
        if (finalFile.exists() && !finalFile.delete()) {
            throw IllegalStateException("cannot replace ${finalFile.name}")
        }
        if (!partFile.renameTo(finalFile)) {
            // renameTo can fail across filesystems; copy+delete as fallback.
            partFile.copyTo(finalFile, overwrite = true)
            if (!partFile.delete()) {
                AppLogger.warning(TAG, "installed ${finalFile.name} but leftover part remains")
            }
        }
        etagFileFor(partFile).delete()
        if (!finalFile.isFile || finalFile.length() <= 0L) {
            throw IllegalStateException("download did not produce ${finalFile.name}")
        }
    }

    /**
     * Housekeeping for the private `updates/` dir, run every time the
     * check-update section is composed. Deletes:
     *  - APKs for an OLDER version than the running build (stale leftovers),
     *  - the APK for the CURRENT running version (already installed),
     *  - orphaned `.part` files whose download is not currently running.
     * Keeps the pending/next-version APK so resume + install still work.
     */
    fun pruneUpdateDir(context: Context) {
        scope.launch {
            runCatching {
                val dir = File(context.filesDir, "updates")
                if (!dir.exists()) return@runCatching
                // Normalize the same way UpdateChecker.resumableInstall does —
                // comparing a normalized filename version against a raw
                // "1.36.9-linux" local build made the two code paths disagree
                // about whether a staged APK was still needed.
                val current = UpdateVersionLogic.normalizeTag(BuildConfig.VERSION_NAME)
                val running = job?.isActive == true
                // Never delete the APK the pending record points at, whatever
                // the version arithmetic says: it is the one the user already
                // downloaded and is about to install.
                val protectedPath = PendingUpdateStore.getPending(context)?.apkPath
                dir.listFiles()?.forEach { f ->
                    val name = f.name
                    when {
                        name.endsWith(ETAG_SUFFIX) -> {
                            // Orphaned ETag sidecar (its .part is gone or done).
                            if (!File(dir, name.removeSuffix(ETAG_SUFFIX)).exists()) {
                                f.delete()
                            }
                        }
                        !name.endsWith(".apk") && !name.endsWith(".apk.part") -> Unit
                        name.endsWith(".apk.part") -> {
                            // Drop orphan .part unless a download is running for it.
                            if (!running) {
                                AppLogger.info(TAG, "prune orphan part ${f.name}")
                                f.delete()
                                etagFileFor(f).delete()
                            }
                        }
                        f.absolutePath == protectedPath -> {
                            AppLogger.info(TAG, "keep pending apk ${f.name}")
                        }
                        else -> {
                            val v = UpdateVersionLogic.normalizeTag(
                                name.removePrefix("minis-").removeSuffix(".apk"),
                            )
                            val cmp = UpdateVersionLogic.compareVersions(v, current)
                            if (cmp <= 0) {
                                // Older than, or equal to (already installed) running build.
                                AppLogger.info(TAG, "prune stale/installed apk ${f.name} (v=$v cmp=$cmp)")
                                f.delete()
                            }
                        }
                    }
                }
            }.onFailure { AppLogger.warning(TAG, "pruneUpdateDir failed: ${it.message}") }
        }
    }
}
