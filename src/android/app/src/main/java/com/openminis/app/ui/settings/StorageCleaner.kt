package com.openminis.app.ui.settings

import android.content.Context
import java.io.File

/**
 * [T-android-storage-clean-logs-caches] "Clear logs & caches" for the storage
 * screen.
 *
 * Previously the screen could only show sizes and delete whole sessions: there
 * was no way to reclaim the space that logs and cache directories quietly
 * accumulate (upstream ships this as `LogsAndCachesCleaner`). Chat history,
 * session files and backups must never be touched by this path — only
 * directories the app treats as disposable.
 */
internal object StorageCleaner {

    /** Outcome of one cleanup pass, in user-facing units. */
    data class Result(val freedBytes: Long, val filesDeleted: Int, val failedFiles: Int)

    /**
     * Directories whose CONTENTS are disposable. The directories themselves are
     * kept (the app recreates files inside them on next use, and deleting
     * `cacheDir` itself upsets the platform).
     */
    fun candidateDirs(context: Context): List<File> = buildList {
        context.cacheDir?.let { add(it) }
        context.codeCacheDir?.let { add(it) }
        context.externalCacheDir?.let { add(it) }
        add(File(context.filesDir, "logs"))
    }.filter { it.isDirectory }.distinctBy { it.absolutePath }

    /** Bytes currently held by the disposable directories. */
    fun measure(context: Context): Long = candidateDirs(context).sumOf { sizeOf(it) }

    /** Recursive size of [dir]'s contents (the directory itself is not counted). */
    fun sizeOf(dir: File): Long {
        val children = dir.listFiles() ?: return 0L
        var total = 0L
        for (child in children) {
            total += if (child.isDirectory) sizeOf(child) else child.length()
        }
        return total
    }

    /**
     * Delete the contents of every disposable directory.
     *
     * Files that cannot be deleted (in use by a writer, permission) are counted
     * instead of aborting the pass: a stuck log file must not stop the rest of
     * the cleanup.
     */
    fun clean(context: Context): Result {
        var freed = 0L
        var deleted = 0
        var failed = 0
        for (dir in candidateDirs(context)) {
            val (bytes, count, fails) = cleanDir(dir)
            freed += bytes
            deleted += count
            failed += fails
        }
        return Result(freed, deleted, failed)
    }

    /**
     * Delete everything INSIDE [dir], keeping the directory.
     *
     * Returns (bytes freed, files deleted, files that could not be deleted).
     * Split out from [clean] so it can be unit-tested against a temp directory
     * without an Android [Context].
     */
    fun cleanDir(dir: File): Triple<Long, Int, Int> {
        val children = dir.listFiles() ?: return Triple(0L, 0, 0)
        var freed = 0L
        var deleted = 0
        var failed = 0
        for (child in children) {
            // A directory's own `length()` is filesystem-dependent (0 on ext4's
            // stat through java.io on some platforms, 4096 on others). Counting
            // it made "freed" report directory inode blocks the user never had
            // bytes for — see StorageCleanerTest, which caught 4446 vs 350 on
            // Linux. Only real file bytes count.
            val bytes = if (child.isDirectory) sizeOf(child) else child.length()
            val gone = if (child.isDirectory) child.deleteRecursively() else child.delete()
            if (gone) {
                freed += bytes
                deleted++
            } else {
                failed++
            }
        }
        return Triple(freed, deleted, failed)
    }
}
