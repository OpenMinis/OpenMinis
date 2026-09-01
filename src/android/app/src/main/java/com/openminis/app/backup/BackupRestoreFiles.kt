package com.openminis.app.backup

import com.openminis.app.logging.AppLogger
import java.io.File
import java.util.UUID

/**
 * Writes a package's file tree back onto the device.
 *
 * Split out of the importer because the two rules it enforces are the ones with
 * teeth, and they must apply identically to every category rather than being
 * re-stated per call site.
 */
object BackupRestoreFiles {

    private const val TAG = "Restore"

    data class Result(
        var written: Int = 0,
        var bytes: Long = 0,
        /**
         * Index entries whose content blob is MISSING from the package.
         *
         * The export is only a snapshot to the extent its cut-off holds: a file
         * recorded in files.index between the index pass and the blob copy can
         * be referenced and then never stored. This used to be worth a silent
         * skip; it is not. The file simply never appears and the restore still
         * reports success, which is the one outcome a backup tool must never
         * produce. Counted and surfaced instead (iOS review S7).
         */
        var missingBlobs: Int = 0,
        /** §3.4 tombstones — the export excluded these by the size cap. */
        var sizeSkippedInPackage: Int = 0,
        /** Tombstones for files that were not downloaded on the source device. */
        var notDownloadedInPackage: Int = 0,
        /** Entries refused because their path escaped the category root. */
        var rejectedPaths: Int = 0,
    )

    /**
     * Restore every [fileIndex] entry belonging to [category].
     *
     * @param destinationFor maps a package-relative logical path to its
     *   destination on this device, or null to ignore the entry.
     * @param containmentRoot every write must land inside this directory.
     */
    fun restore(
        packageRoot: File,
        fileIndex: List<BackupFileIndexEntry>,
        category: BackupCategory,
        containmentRoot: File,
        destinationFor: (String) -> File?,
    ): Result {
        val result = Result()
        val root = containmentRoot.canonicalFile

        for (entry in fileIndex) {
            if (entry.category != category.key) continue

            // Tombstones carry no content by design — counted, never written.
            if (entry.skipped != null) {
                when (entry.skipped) {
                    "size" -> result.sizeSkippedInPackage += 1
                    "not_downloaded" -> result.notDownloadedInPackage += 1
                    else -> result.sizeSkippedInPackage += 1
                }
                continue
            }

            val dest = destinationFor(entry.path) ?: continue

            // Path traversal via index CONTENT.
            //
            // `entry.path` comes from files.index.jsonl INSIDE the package,
            // which is attacker-controlled whenever a user restores a file
            // someone sent them — and this feature exists to accept packages
            // shared over messaging apps and Files. The ZIP extractor's own
            // traversal guard does NOT cover this: the archive's entry names
            // are all benign (`blobs/<xx>/<sha>`), and the malicious path lives
            // in the index content, not in an entry name.
            //
            // Checked on the canonical path, which is what the filesystem will
            // actually use.
            if (!isContained(dest, root)) {
                AppLogger.error(TAG, "[Restore] REJECTED path escaping its category root: ${entry.path}")
                result.rejectedPaths += 1
                continue
            }

            if (entry.isDirectory == true) {
                dest.mkdirs()
                continue
            }
            val sha = entry.sha256 ?: continue
            val blob = File(File(File(packageRoot, "blobs"), sha.take(2)), sha)
            if (!blob.isFile) {
                AppLogger.info(
                    TAG,
                    "[Restore] index references a blob not in the package: ${entry.path} sha=${sha.take(12)}"
                )
                result.missingBlobs += 1
                continue
            }

            dest.parentFile?.mkdirs()
            if (writeViaStagedSwap(blob, dest, entry.path)) {
                result.written += 1
                result.bytes += entry.size
            }
        }
        return result
    }

    /**
     * Copy to a sibling temp file, then rename over the destination.
     *
     * NOT delete-then-copy. That pair leaves the user's existing file
     * NON-EXISTENT for the whole duration of the copy, so a process kill in
     * that window deletes it permanently with nothing written back — and the
     * window is entered once per file. Merge idempotency does not help a file
     * that was deleted and never rewritten. A rename within one filesystem is
     * atomic, so the destination is either the old bytes or the new ones, never
     * missing (iOS review I2).
     */
    private fun writeViaStagedSwap(blob: File, dest: File, logicalPath: String): Boolean {
        val staged = File(dest.parentFile, ".restore-${UUID.randomUUID()}.tmp")
        return try {
            blob.copyTo(staged, overwrite = true)
            if (!staged.renameTo(dest)) {
                // Rename can fail across filesystems or if the destination is a
                // non-empty directory. Fall back to an explicit replace, which
                // still never leaves a gap longer than the delete itself.
                if (dest.exists() && !dest.delete()) throw BackupException("cannot replace $logicalPath")
                if (!staged.renameTo(dest)) throw BackupException("cannot finalise $logicalPath")
            }
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "[Restore] failed to write $logicalPath: ${e.message}")
            staged.delete()
            false
        }
    }

    /**
     * True when [candidate] resolves inside [root].
     *
     * Canonicalises both sides so `..` segments and symlinks are resolved the
     * way the kernel will resolve them. The parent is canonicalised when the
     * candidate does not exist yet, since a non-existent path has no canonical
     * form of its own on every filesystem.
     */
    private fun isContained(candidate: File, root: File): Boolean = runCatching {
        val rootPath = root.canonicalPath
        val target = if (candidate.exists()) {
            candidate.canonicalFile
        } else {
            File(candidate.parentFile?.canonicalFile ?: return false, candidate.name)
        }
        val path = target.path
        path == rootPath || path.startsWith(rootPath + File.separator)
    }.getOrDefault(false)
}
