package com.openminis.app.backup

import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * Walks a directory tree into the package: content into the blob store,
 * structure into `files.index.jsonl`. Mirrors
 * `src/ios/Agent/Backup/BackupFileTreeExporter.swift`.
 *
 * Shared by every category that carries files — Chats' `<sid>/` directories,
 * Shared Files, and Skills' attached files — so the §3.4 size cap and the dedup
 * behaviour are identical everywhere by construction, rather than by three
 * exporters agreeing to behave the same way.
 */
class BackupFileTreeExporter(
    private val blobStore: BackupBlobStore,
    private val fileIndex: BackupFileIndexWriter,
    /**
     * Snapshot cut-off. Files modified after this instant are not part of this
     * backup's view of the data.
     *
     * **Excludes only, never includes.** mtime is not always trustworthy: a
     * file restored from another backup, synced down by a provider, or copied
     * in can carry an mtime unrelated to when this device saw it, and a clock
     * change moves every comparison at once. So the rule is asymmetric on
     * purpose — a file is dropped ONLY when its mtime is clearly after the
     * cut-off, and anything unreadable or ambiguous is INCLUDED. Wrongly
     * including a slightly-too-new file costs a little package size; wrongly
     * excluding one silently loses data from the user's backup, which is the
     * failure this feature exists to prevent.
     */
    private val snapshotAtMillis: Long = Long.MAX_VALUE,
) {

    data class Result(
        var filesIncluded: Int = 0,
        var filesSkipped: Int = 0,
        var bytesIncluded: Long = 0,
        var directories: Int = 0,
        /**
         * Files excluded because they were modified after the snapshot cut-off.
         * NOT a gap in the backup — they are outside this backup's point in
         * time, so they get no tombstone.
         */
        var filesAfterSnapshot: Int = 0,
    )

    /**
     * Export everything under [root] into the package beneath [logicalPrefix].
     *
     * A missing root is not an error: a user with no skills, or a session with
     * no files, is the normal case and must not fail the whole backup.
     */
    fun export(
        root: File,
        logicalPrefix: String,
        category: BackupCategory,
        sessionId: String? = null,
    ): Result {
        val result = Result()
        if (!root.exists() || !root.isDirectory) return result

        val base = root.canonicalFile
        for (entry in base.walkTopDown()) {
            // walkTopDown yields the root itself first; it is the prefix, not
            // an entry inside the package.
            if (entry == base) continue

            val rel = entry.relativeTo(base).path.replace(File.separatorChar, '/')
            if (rel.isEmpty()) continue
            val logicalPath = "$logicalPrefix/$rel"

            // Never package a backup artifact — see isBackupArtifact.
            if (isBackupArtifact(rel)) continue

            if (entry.isDirectory) {
                // Empty directories would otherwise vanish, since nothing
                // references them.
                if (entry.listFiles()?.isEmpty() == true) {
                    fileIndex.write(BackupFileIndexEntry.directory(logicalPath, category))
                    result.directories += 1
                }
                continue
            }
            if (!entry.isFile) continue // sockets, fifos, dangling symlinks

            // Snapshot cut-off. `> 0` guards against a filesystem reporting 0
            // for an unknown mtime, which must not read as "ancient".
            val modified = entry.lastModified()
            if (modified > 0 && modified > snapshotAtMillis) {
                result.filesAfterSnapshot += 1
                continue
            }

            when (val outcome = addFile(entry, logicalPath, sessionId, category, result)) {
                is BackupBlobStore.Outcome.Stored -> {
                    fileIndex.write(
                        BackupFileIndexEntry.file(logicalPath, outcome.size, outcome.sha256, category)
                    )
                    result.filesIncluded += 1
                    result.bytesIncluded += outcome.size
                }
                is BackupBlobStore.Outcome.Duplicate -> {
                    // The bytes are already in the package under this digest;
                    // only the tree needs a second entry pointing at them.
                    fileIndex.write(
                        BackupFileIndexEntry.file(logicalPath, outcome.size, outcome.sha256, category)
                    )
                    result.filesIncluded += 1
                }
                is BackupBlobStore.Outcome.SkippedTooLarge -> {
                    // §3.4: tombstone, never a silent drop.
                    fileIndex.write(
                        BackupFileIndexEntry.sizeSkipped(logicalPath, outcome.size, category)
                    )
                    result.filesSkipped += 1
                }
                null -> {
                    // Unreadable — permissions, a file deleted mid-walk, or I/O
                    // error. Recorded as a tombstone so the gap is visible on
                    // restore instead of the file simply not being there.
                    fileIndex.write(
                        BackupFileIndexEntry.unreadable(logicalPath, entry.length(), category)
                    )
                    result.filesSkipped += 1
                }
            }
        }
        return result
    }

    private fun addFile(
        file: File,
        logicalPath: String,
        sessionId: String?,
        category: BackupCategory,
        result: Result,
    ): BackupBlobStore.Outcome? = try {
        blobStore.addFile(file, logicalPath, sessionId)
    } catch (e: Exception) {
        // One bad file must not sink an entire backup — the tombstone above
        // keeps the gap visible.
        AppLogger.error(TAG, "[Backup] unreadable file $logicalPath: ${e.message}")
        null
    }

    /**
     * True for anything that is itself a backup artifact — a delivered
     * `.minisbak`, or the directory they are delivered into.
     *
     * Without this a backup sweeps up the previous backup as "user data",
     * nesting packages inside packages until the size runs away. Matched on the
     * leading path component as well as the extension, so a whole `Backups/`
     * tree is skipped in one step rather than file by file.
     */
    private fun isBackupArtifact(rel: String): Boolean {
        if (rel.endsWith(".${BackupFormat.FILE_EXTENSION}")) return true
        return rel.substringBefore('/') == BACKUPS_DIR_NAME
    }

    companion object {
        private const val TAG = "Backup"

        /** Sibling of `shared`/`skills`/`memory`, matching iOS's delivery directory. */
        const val BACKUPS_DIR_NAME = "Backups"
    }
}

/**
 * Append-only writer for `files.index.jsonl`.
 *
 * Flushed after every record, deliberately. This file is what a resumed run
 * reads back to discover which blobs an interrupted attempt already stored
 * ([BackupBlobStore.rehydrateFromFileIndex]) — and the interruption it has to
 * survive is the process dying, which is precisely when buffered-but-unwritten
 * lines are lost. A buffer here would make resume silently forget the tail of
 * the previous attempt and re-copy blobs that are already staged.
 *
 * The cost is one small write per file rather than per 8KB; next to hashing and
 * copying the file's contents, that is noise.
 */
class BackupFileIndexWriter(file: File) : AutoCloseable {
    private val stream = file.also { it.parentFile?.mkdirs() }.outputStream()

    var written = 0
        private set

    fun write(entry: BackupFileIndexEntry) {
        val line = BackupFormat.json.encodeToString(BackupFileIndexEntry.serializer(), entry) + "\n"
        stream.write(line.toByteArray(Charsets.UTF_8))
        stream.flush()
        written += 1
    }

    override fun close() {
        stream.flush()
        stream.close()
    }
}
