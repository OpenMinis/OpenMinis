package com.openminis.app.backup.remote

import android.content.Context
import com.openminis.app.backup.BackupFormat
import com.openminis.app.logging.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Uploads a backup package to an rclone remote as ONE file, and reads packages
 * back. Mirrors `src/ios/Agent/Backup/Remote/RcloneChunkedUpload.swift`.
 *
 * ## History: this used to chunk
 *
 * An earlier revision split the package into 8 MiB parts under
 * `.minis-parts/<backupId>/` with a resume journal, because rclone has no
 * cross-process resume for a single file and a killed 300-of-500 MB upload
 * restarts from zero. That bought resumability at a real cost: every package
 * over 8 MiB lived on the server as a directory of anonymous fragments —
 * unusable by hand, dependent on our own reassembly logic on the restore side,
 * and invisible to the iOS restore picker, which lists `.minisbak` files only.
 * A user looking at their NAS saw no backup at all.
 *
 * iOS re-evaluated the trade on 2026-08-16 and decided it the other way; this
 * is the Android side of that change. The server always holds a clean,
 * self-contained `.minisbak` a user can grab with any client, and an
 * interrupted upload simply re-runs. A failure is surfaced per-destination by
 * the caller, so it is visible, not silent.
 *
 * **New uploads never write `.minis-parts` again.** Reading it back is kept as
 * backward compatibility only: packages a previous build already uploaded in
 * fragments must stay restorable (see [listPackages] / [download]).
 *
 * ## What "success" means
 *
 * A transfer only counts once the remote object's SIZE matches the local file.
 * rclone's copyfile returning cleanly is necessary but not sufficient — a
 * backend can acknowledge a truncated write. Size is the strongest check that
 * works everywhere (WebDAV mostly has no server-side hashes, and rclone's own
 * transport already error-checks each request).
 *
 * The upload goes to a `<name>.partial` scratch object first and is renamed
 * only after the size check passes: a half-uploaded file must never be
 * listable under the real package name.
 */
class RcloneChunkedUpload(private val context: Context) {

    /** Progress across the whole file. */
    data class Progress(
        val bytesSent: Long,
        val totalBytes: Long,
        /** Bytes/second, when the transport can report or derive it. */
        val bytesPerSecond: Double = 0.0,
    ) {
        val fraction: Double get() = if (totalBytes > 0) bytesSent.toDouble() / totalBytes else 0.0

        /** Seconds left at the current rate, or null when it cannot be judged. */
        val secondsRemaining: Long?
            get() {
                if (bytesPerSecond <= 0 || totalBytes <= 0) return null
                val left = totalBytes - bytesSent
                if (left <= 0) return 0
                return (left / bytesPerSecond).toLong()
            }
    }

    /**
     * [T-android-restore-cancel] Cancellation shared across threads.
     *
     * A plain captured Boolean is not enough: the flag is set on the main
     * thread while the download loop reads it on an IO thread, so it needs a
     * single piece of storage both sides genuinely share. iOS hit the same
     * problem from the other direction — its flag lived in SwiftUI @State, the
     * view struct was replaced on every state change, and the closure kept
     * reading a stale copy that never turned true.
     */
    class CancelFlag {
        @Volatile
        private var cancelled = false
        fun cancel() { cancelled = true }
        fun isCancelled(): Boolean = cancelled
    }

    class UploadException(message: String) : Exception(message)
    class CancelledException : Exception("Upload cancelled.")

    // MARK: - Upload

    /**
     * Upload [packageFile] into [remote] as a single object, verifying the
     * uploaded size before the final rename. Retries the transfer once — a
     * transient network drop shouldn't fail the whole backup run. Blocking;
     * call off the main thread.
     *
     * [backupId] is accepted for call-site compatibility and logging only; it
     * no longer names anything on the remote.
     */
    fun upload(
        packageFile: File,
        remote: RcloneRemoteStore.Remote,
        backupId: String = "",
        isCancelled: () -> Boolean = { false },
        onProgress: ((Progress) -> Unit)? = null,
    ) {
        if (!packageFile.exists()) throw UploadException("Couldn't read the backup file.")
        val size = packageFile.length()
        val name = packageFile.name
        val fs = remote.fsSpec

        // NOT dot-prefixed. WebDAV gateways routinely filter dotfiles out of
        // directory listings (verified against alist), and [sweepAbandonedPartials]
        // finds its victims by LISTING — so on those servers a dotted scratch
        // name could never be seen, and never deleted, stranding a full-size
        // object on the user's NAS after every interrupted upload.
        //
        // A plain suffix is invisible to that filter and still cannot be
        // mistaken for a backup: the restore list matches `.minisbak` exactly.
        val partial = remote.join("$name.$PARTIAL_SUFFIX")
        val final = remote.join(name)

        sweepAbandonedPartials(remote, keeping = partial)

        var lastError: Exception? = null
        for (attempt in 1..2) {
            if (isCancelled()) throw CancelledException()
            try {
                // copyfile blocks until the whole package has been sent, so it
                // can report nothing along the way. rclone tracks the transfer
                // itself, so live bytes are polled from core/stats on a
                // separate thread. `bytes` is cumulative for the session, hence
                // the baseline taken before the copy starts.
                val baseline = statsBytes()
                // AtomicBoolean, not a plain local: the poller runs on another
                // thread and must see the stop flag without a data race.
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                val poller = if (onProgress != null) {
                    Thread {
                        while (!done.get()) {
                            try {
                                Thread.sleep(500)
                            } catch (_: InterruptedException) {
                                break
                            }
                            if (done.get()) break
                            val moved = (statsBytes() - baseline).coerceAtLeast(0)
                            // Never report more than the file — stats can
                            // include other bookkeeping, and a bar that
                            // overshoots reads as a bug even when the transfer
                            // is fine.
                            onProgress(Progress(minOf(moved, size), size))
                        }
                    }.apply { isDaemon = true; start() }
                } else {
                    null
                }

                try {
                    RcloneBridge.rpc(
                        "operations/copyfile",
                        mapOf(
                            "srcFs" to (packageFile.parentFile?.absolutePath ?: ""),
                            "srcRemote" to name,
                            "dstFs" to fs,
                            "dstRemote" to partial,
                        ),
                    )
                } finally {
                    done.set(true)
                    poller?.interrupt()
                }

                // The size check IS the success condition, not decoration.
                val uploaded = remoteSize(fs, partial)
                if (uploaded != size) {
                    throw UploadException(
                        "Upload verification failed: the server has $uploaded bytes " +
                            "but the backup is $size bytes."
                    )
                }
                RcloneBridge.rpc(
                    "operations/movefile",
                    mapOf(
                        "srcFs" to fs, "srcRemote" to partial,
                        "dstFs" to fs, "dstRemote" to final,
                    ),
                )
                onProgress?.invoke(Progress(size, size))
                AppLogger.info(
                    TAG,
                    "[Rclone] uploaded $name ($size bytes, verified) -> ${remote.name}, attempt $attempt"
                )
                return
            } catch (e: Exception) {
                lastError = e
                // A failed or unverified transfer must not leave the scratch
                // object behind — a later attempt overwrites it anyway, but a
                // permanent failure shouldn't strand junk on the server.
                runCatching {
                    RcloneBridge.rpc("operations/deletefile", mapOf("fs" to fs, "remote" to partial))
                }
                AppLogger.warning(
                    TAG,
                    "[Rclone] upload attempt $attempt to '${remote.name}' failed: ${e.message}"
                )
                if (isCancelled()) throw CancelledException()
            }
        }
        throw UploadException(lastError?.message ?: "The remote rejected the upload.")
    }

    /**
     * Delete `.partial` scratch objects left by interrupted uploads.
     *
     * A killed transfer leaves its scratch file on the server for good. They
     * are hidden from the restore list (the filter requires a `.minisbak`
     * suffix) so they never look like backups, but they are full-size — one per
     * interruption, each potentially gigabytes, quietly consuming the user's
     * NAS. Nothing else ever removes them.
     *
     * Best-effort by design: a server that refuses the listing or the delete
     * must not fail the backup that is about to run — the point is to reclaim
     * space, not to gate the transfer on housekeeping. It deletes ONLY
     * `.partial` scratch objects, never a user's `.minisbak` and never the
     * historical `.minis-parts` directory.
     */
    private fun sweepAbandonedPartials(remote: RcloneRemoteStore.Remote, keeping: String) {
        val fs = remote.fsSpec
        val list = runCatching {
            RcloneBridge.rpc("operations/list", mapOf("fs" to fs, "remote" to remote.listRoot))
                .optJSONArray("list")
        }.getOrNull() ?: return

        var removed = 0
        var bytes = 0L
        for (i in 0 until list.length()) {
            val e = list.optJSONObject(i) ?: continue
            if (e.optBoolean("IsDir")) continue
            val name = e.optString("Name")
            if (!name.endsWith(".$PARTIAL_SUFFIX")) continue
            val path = remote.join(name)
            if (path == keeping) continue // this run reuses its own
            bytes += e.optLong("Size")
            runCatching {
                RcloneBridge.rpc("operations/deletefile", mapOf("fs" to fs, "remote" to path))
            }.onSuccess { removed++ }
        }
        if (removed > 0) {
            AppLogger.info(
                TAG,
                "[Rclone] swept $removed abandoned .$PARTIAL_SUFFIX upload(s) from " +
                    "'${remote.name}', freeing $bytes bytes"
            )
        }
    }

    /**
     * Delete one package from a remote. Mirrors iOS `RcloneTransfer.deletePackage`.
     *
     * Throws on failure so the caller can report which destination refused —
     * "deleted from 2 of 3 servers" is only useful if the third can be named.
     */
    fun deletePackage(remote: RcloneRemoteStore.Remote, packageName: String) {
        RcloneBridge.rpc(
            "operations/deletefile",
            mapOf("fs" to remote.fsSpec, "remote" to remote.join(packageName)),
        )
        AppLogger.info(TAG, "[Rclone] deleted $packageName from ${remote.name}")
    }

    /**
     * Delete a listed package, whole or legacy-chunked.
     *
     * A `.minis-parts/<id>/` upload is a DIRECTORY, and `deletefile` on it
     * does nothing while still reporting success — the UI would show the
     * package gone and a refresh would bring it straight back. iOS never hits
     * this because it dropped chunking before the delete path existed; Android
     * still lists those packages for backward compatibility, so it has to be
     * able to remove them too.
     */
    fun deletePackage(remote: RcloneRemoteStore.Remote, pkg: RemotePackage) {
        if (!pkg.isChunked) {
            deletePackage(remote, pkg.displayName)
            return
        }
        RcloneBridge.rpc("operations/purge", mapOf("fs" to remote.fsSpec, "remote" to pkg.key))
        AppLogger.info(
            TAG,
            "[Rclone] purged legacy parts dir ${pkg.key} (${pkg.partCount} parts) from ${remote.name}",
        )
    }

    /** Size of a remote object, or throws if it does not exist. */
    private fun remoteSize(fs: String, path: String): Long {
        val item = RcloneBridge.rpc("operations/stat", mapOf("fs" to fs, "remote" to path))
            .optJSONObject("item")
            ?: throw UploadException("Uploaded file not found on the server.")
        return item.optLong("Size", -1L)
    }

    private fun statsBytes(): Long =
        runCatching { RcloneBridge.rpc("core/stats").optLong("bytes", 0L) }.getOrDefault(0L)

    // MARK: - Reading back

    /** A backup found on a remote — a whole file, or a legacy set of parts. */
    data class RemotePackage(
        /** Path used to fetch it: the file, or the legacy parts directory. */
        val key: String,
        val displayName: String,
        val size: Long,
        val modified: Long?,
        /**
         * >1 only for a `.minis-parts` upload written by an older build.
         * Uploads produced by this class are always 1.
         */
        val partCount: Int,
    ) {
        val isChunked: Boolean get() = partCount > 1
    }

    /**
     * [T-android-restore-browse] One entry in a directory listing — a folder to
     * descend into, or a package to restore from.
     */
    data class RemoteEntry(
        val path: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val modified: Long?,
    )

    /**
     * List ONE directory level on a remote. Mirrors iOS `listDirectory`.
     *
     * Level-by-level rather than one recursive sweep: a NAS with a deep tree
     * (or one shared root holding far more than backups) made a single
     * exhaustive listing slow enough to look hung, and it surfaced folders the
     * user had no interest in. This asks only for what is being looked at.
     *
     * Dotfiles are skipped — that hides `.minis-parts` scratch and the
     * `.partial` upload objects, neither of which is something to restore from
     * by hand.
     */
    fun listDirectory(remote: RcloneRemoteStore.Remote, path: String): List<RemoteEntry> {
        val cleaned = path.trim('/')
        val listing = RcloneBridge.rpc(
            "operations/list", mapOf("fs" to remote.fsSpec, "remote" to cleaned),
        ).optJSONArray("list")
        val dirs = mutableListOf<RemoteEntry>()
        val files = mutableListOf<RemoteEntry>()
        for (i in 0 until (listing?.length() ?: 0)) {
            val e = listing?.optJSONObject(i) ?: continue
            val name = e.optString("Name")
            if (name.isEmpty() || name.startsWith(".")) continue
            val isDir = e.optBoolean("IsDir")
            val entry = RemoteEntry(
                path = if (cleaned.isEmpty()) name else "$cleaned/$name",
                name = name,
                isDirectory = isDir,
                size = e.optLong("Size"),
                modified = parseTime(e.optString("ModTime")),
            )
            if (isDir) dirs.add(entry)
            else if (name.endsWith(".${BackupFormat.FILE_EXTENSION}")) files.add(entry)
        }
        // Folders first and alphabetical, which is how a file browser reads;
        // packages newest-first, which is the order someone restoring wants.
        return dirs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name } ) +
            files.sortedByDescending { it.modified ?: 0L }
    }

    /**
     * Everything restorable in [remote]: whole `.minisbak` packages, plus any
     * chunked upload a PREVIOUS build left behind.
     *
     * In-flight scratch files never show up here: they are named
     * `<package>.minisbak.partial`, and the filter below requires the name to
     * END in `.minisbak`.
     */
    fun listPackages(remote: RcloneRemoteStore.Remote): List<RemotePackage> {
        val found = mutableListOf<RemotePackage>()

        val root = RcloneBridge.rpc(
            "operations/list", mapOf("fs" to remote.fsSpec, "remote" to remote.listRoot)
        ).optJSONArray("list")
        for (i in 0 until (root?.length() ?: 0)) {
            val e = root?.optJSONObject(i) ?: continue
            val name = e.optString("Name")
            if (e.optBoolean("IsDir") || !name.endsWith(".${BackupFormat.FILE_EXTENSION}")) continue
            found.add(
                RemotePackage(
                    key = remote.join(name),
                    displayName = name,
                    size = e.optLong("Size"),
                    modified = parseTime(e.optString("ModTime")),
                    partCount = 1,
                )
            )
        }

        // BACKWARD COMPATIBILITY ONLY. Nothing writes `.minis-parts` any more,
        // but a package uploaded in fragments by an older build must stay
        // restorable, so the directory is still discovered and surfaced in the
        // same list as whole packages.
        val partsRoot = remote.join(PARTS_DIR)
        val dirs = runCatching {
            RcloneBridge.rpc("operations/list", mapOf("fs" to remote.fsSpec, "remote" to partsRoot))
                .optJSONArray("list")
        }.getOrNull()
        for (i in 0 until (dirs?.length() ?: 0)) {
            val d = dirs?.optJSONObject(i) ?: continue
            if (!d.optBoolean("IsDir")) continue
            val backupId = d.optString("Name")
            val dir = "$partsRoot/$backupId"
            val parts = runCatching {
                RcloneBridge.rpc("operations/list", mapOf("fs" to remote.fsSpec, "remote" to dir))
                    .optJSONArray("list")
            }.getOrNull() ?: continue
            if (parts.length() == 0) continue
            var total = 0L
            for (j in 0 until parts.length()) total += parts.optJSONObject(j)?.optLong("Size") ?: 0
            found.add(
                RemotePackage(
                    key = dir,
                    displayName = backupId,
                    size = total,
                    modified = parseTime(parts.optJSONObject(0)?.optString("ModTime")),
                    partCount = parts.length(),
                )
            )
        }
        return found.sortedByDescending { it.modified ?: 0 }
    }

    /**
     * Fetch a package to a local file, reassembling it when it is a legacy
     * chunked upload.
     *
     * Parts are concatenated in NAME order, which is why they were written as
     * zero-padded `%06d` — lexical order then equals numeric order, and a
     * missing part shows up as a gap rather than silently shifting everything
     * after it. A short count is refused outright: half a ZIP would fail later
     * as "corrupt archive", which is a much worse thing to hand a user
     * restoring on a new device.
     */
    /**
     * Fetch a single-file package, cancellably, reporting speed.
     *
     * Runs the copy as an ASYNC rclone job rather than a blocking call. That is
     * what makes Cancel work at all: a blocking `operations/copyfile` owns its
     * thread inside Go until the whole file has landed, so there is nothing to
     * interrupt from Kotlin. `_async` returns a jobid immediately, and
     * `job/stop` with that id aborts the transfer for real.
     *
     * `_group` scopes the byte counter to this job, so progress is not skewed
     * by anything else transferring at the same time.
     */
    private fun downloadWholeFile(
        pkg: RemotePackage,
        remote: RcloneRemoteStore.Remote,
        destination: File,
        cancelFlag: CancelFlag?,
        onProgress: ((Progress) -> Unit)?,
    ) {
        val fs = remote.fsSpec
        val dstFs = destination.parentFile?.absolutePath ?: ""
        val group = "restore-${java.util.UUID.randomUUID()}"
        val started = RcloneBridge.rpc(
            "operations/copyfile",
            mapOf(
                "srcFs" to fs, "srcRemote" to pkg.key,
                "dstFs" to dstFs, "dstRemote" to destination.name,
                "_async" to true, "_group" to group,
            ),
        )
        val jobId = started.optInt("jobid", -1)
        if (jobId < 0) {
            // The async request itself was refused. Fall back rather than
            // silently doing nothing — better a download that cannot be
            // cancelled than no download at all.
            AppLogger.error(TAG, "[Rclone] copyfile returned no jobid; falling back to blocking copy")
            RcloneBridge.rpc(
                "operations/copyfile",
                mapOf(
                    "srcFs" to fs, "srcRemote" to pkg.key,
                    "dstFs" to dstFs, "dstRemote" to destination.name,
                ),
            )
            verifyDownload(pkg, destination, onProgress)
            return
        }

        var cancelled = false
        // Rolling rate. Smoothed rather than differenced between consecutive
        // 250ms polls: at that interval one slow tick reads as a near-stall and
        // the number flickers unusably. An EMA settles within a second or two
        // and then tracks real changes (Wi-Fi dropping to cellular) without
        // the jitter.
        var lastBytes = 0L
        var lastTick = System.currentTimeMillis()
        var smoothed: Double? = null
        while (true) {
            Thread.sleep(POLL_INTERVAL_MS)

            if (!cancelled && cancelFlag?.isCancelled() == true) {
                cancelled = true
                // Stop THIS job by id, then keep polling until rclone reports
                // it finished — deleting the file out from under a transfer
                // that is still writing would be worse than waiting.
                runCatching { RcloneBridge.rpc("job/stop", mapOf("jobid" to jobId)) }
            }

            val status = runCatching {
                RcloneBridge.rpc("job/status", mapOf("jobid" to jobId))
            }.getOrNull()
            val finished = status?.optBoolean("finished") ?: false

            if (!finished) {
                val stats = runCatching {
                    RcloneBridge.rpc("core/stats", mapOf("group" to group))
                }.getOrNull()
                // Per-group stats start at zero for this job, so there is no
                // baseline to subtract the way a process-wide counter needs.
                val moved = (stats?.optLong("bytes") ?: 0L).coerceAtLeast(0L)
                val now = System.currentTimeMillis()
                val elapsed = (now - lastTick) / 1000.0
                // Prefer rclone's own speed when it reports one; it already
                // averages over the transfer.
                val reported = stats?.optDouble("speed", 0.0) ?: 0.0
                if (reported > 0) {
                    smoothed = reported
                } else if (elapsed > 0) {
                    val instant = (moved - lastBytes) / elapsed
                    smoothed = smoothed?.let { it * (1 - RATE_ALPHA) + instant * RATE_ALPHA }
                        ?: instant
                }
                lastBytes = moved
                lastTick = now
                onProgress?.invoke(
                    Progress(minOf(moved, pkg.size), pkg.size, smoothed ?: 0.0),
                )
                continue
            }

            val error = status?.optString("error").orEmpty()
            if (cancelled) {
                // Free the scratch: a cancelled download leaves a partial file
                // that is useless and, for a large package, expensive to keep.
                destination.delete()
                throw CancelledException()
            }
            if (error.isNotEmpty()) {
                destination.delete()
                throw UploadException(error)
            }
            break
        }
        verifyDownload(pkg, destination, onProgress)
    }

    /**
     * Size check, the same rule as upload in the other direction: a short
     * package would otherwise fail later as a corrupt archive, which is a far
     * worse message to hand someone restoring a new device.
     */
    private fun verifyDownload(
        pkg: RemotePackage,
        destination: File,
        onProgress: ((Progress) -> Unit)?,
    ) {
        val local = destination.length()
        if (pkg.size > 0 && local != pkg.size) {
            destination.delete()
            throw UploadException(
                "Download verification failed: got $local bytes but the backup is ${pkg.size} bytes.",
            )
        }
        onProgress?.invoke(Progress(pkg.size, pkg.size))
        AppLogger.info(TAG, "[Rclone] downloaded ${pkg.displayName} ($local bytes, verified)")
    }

    fun download(
        pkg: RemotePackage,
        remote: RcloneRemoteStore.Remote,
        destination: File,
        cancelFlag: CancelFlag? = null,
        onProgress: ((Progress) -> Unit)? = null,
    ) {
        destination.delete()
        destination.parentFile?.mkdirs()

        if (!pkg.isChunked) {
            downloadWholeFile(pkg, remote, destination, cancelFlag, onProgress)
            return
        }

        val listing = RcloneBridge.rpc(
            "operations/list", mapOf("fs" to remote.fsSpec, "remote" to pkg.key)
        ).optJSONArray("list")
        val names = (0 until (listing?.length() ?: 0))
            .mapNotNull { listing?.optJSONObject(it)?.optString("Name") }
            .filter { it.isNotEmpty() }
            .sorted()
        if (names.size != pkg.partCount) {
            throw UploadException(
                "Expected ${pkg.partCount} parts but found ${names.size} — the upload is incomplete."
            )
        }

        val scratch = File(context.cacheDir, "minis-dl-${pkg.displayName}").apply { mkdirs() }
        try {
            destination.outputStream().buffered().use { out ->
                var written = 0L
                for (name in names) {
                    RcloneBridge.rpc(
                        "operations/copyfile",
                        mapOf(
                            "srcFs" to remote.fsSpec, "srcRemote" to "${pkg.key}/$name",
                            "dstFs" to scratch.absolutePath, "dstRemote" to name,
                        ),
                    )
                    val local = File(scratch, name)
                    local.inputStream().buffered().use { it.copyTo(out) }
                    written += local.length()
                    local.delete() // one part on disk at a time
                    onProgress?.invoke(Progress(written, pkg.size))
                }
            }
        } finally {
            scratch.deleteRecursively()
        }
        AppLogger.info(TAG, "[Rclone] reassembled ${names.size} legacy part(s) -> ${destination.name}")
    }

    private fun parseTime(s: String?): Long? {
        if (s.isNullOrEmpty()) return null
        for (pattern in TIME_PATTERNS) {
            runCatching {
                val f = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return f.parse(s)?.time
            }
        }
        return null
    }

    companion object {
        private const val TAG = "Rclone"

        /** 250ms, not 500: Cancel is user-facing and the poll interval is the
         *  floor on how long it appears to hang. */
        private const val POLL_INTERVAL_MS = 250L

        /** EMA weight for the derived transfer rate. */
        private const val RATE_ALPHA = 0.3

        /**
         * Scratch suffix for an in-flight upload. Deliberately not dot-prefixed
         * — see the note in [upload].
         */
        const val PARTIAL_SUFFIX = "partial"

        /**
         * Legacy chunked-upload directory. READ-ONLY: retained so packages an
         * older build uploaded in fragments stay restorable. Nothing writes it.
         */
        const val PARTS_DIR = ".minis-parts"

        private val TIME_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )
    }
}
