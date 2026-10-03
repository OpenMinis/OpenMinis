package com.openminis.app.data.session

import java.io.File

/**
 * [T-android-child-session-delete-storage] The on-disk footprint of a session,
 * in one place. Two roots under `filesDir`:
 *
 *   minis-sessions/<sessionId>/…        workspace, attachments, offloads, browser
 *   media/<yyyy>/<MM>/<dd>/<sessionId>/… dated media (images, audio) — one
 *                                         session can have dirs under many dates
 *
 * Takes `filesDir` as a [File] rather than a Context so it is usable from a
 * plain JVM test with a temp directory. Every caller that sizes or clears a
 * session's files goes through here; the storage screen used to carry its own
 * copies of these walks and the delete paths had none at all.
 */
class SessionStorage(private val filesDir: File) {

    val sessionsRoot: File get() = File(filesDir, SESSIONS_DIR)
    val mediaRoot: File get() = File(filesDir, MEDIA_DIR)

    fun sessionDir(sessionId: String): File = File(sessionsRoot, sessionId)

    /** Every dated media directory belonging to [sessionId]. */
    fun mediaDirs(sessionId: String): List<File> {
        if (!mediaRoot.exists()) return emptyList()
        return mediaRoot.walkTopDown()
            .filter { it.isDirectory && it.name == sessionId && it != mediaRoot }
            .toList()
    }

    fun minisSize(sessionId: String): Long = directorySize(sessionDir(sessionId))

    fun mediaSize(sessionId: String): Long = mediaDirs(sessionId).sumOf { directorySize(it) }

    /**
     * Media bytes per session for [sessionIds], from ONE walk of the media
     * tree (the storage list sizes every session at once; walking per id
     * would be quadratic in files).
     */
    fun mediaSizesBySession(sessionIds: Set<String>): Map<String, Long> {
        if (!mediaRoot.exists()) return emptyMap()
        val sizes = mutableMapOf<String, Long>()
        mediaRoot.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val sid = file.parentFile?.name ?: return@forEach
            if (sid in sessionIds) sizes[sid] = (sizes[sid] ?: 0L) + file.length()
        }
        return sizes
    }

    /**
     * Outcome of a delete: bytes freed, plus whatever could NOT be removed.
     *
     * [T-android-storage-delete-feedback] `deleteRecursively()` returns a
     * boolean that every caller here used to drop on the floor, so a partial
     * failure looked exactly like a success — the user tapped "delete" and
     * nothing happened, with no error. openminis/openminis#375 is the report:
     * Git leaves `.git/objects/pack/` at mode 0555, the walk cannot unlink
     * inside it, and the screen stayed silent about it.
     */
    data class DeleteResult(val freed: Long, val failedPaths: List<String>) {
        val isComplete: Boolean get() = failedPaths.isEmpty()
    }

    /** Delete every file this session owns. Returns bytes freed (best effort). */
    fun deleteFiles(sessionId: String): Long = deleteFilesDetailed(sessionId).freed

    /**
     * As [deleteFiles], but reports what survived so the caller can tell the
     * user instead of failing silently.
     */
    fun deleteFilesDetailed(sessionId: String): DeleteResult {
        var freed = 0L
        val failed = mutableListOf<String>()

        fun wipe(target: File) {
            if (!target.exists()) return
            val size = directorySize(target)
            // Restore write permission first. A directory needs +w for its
            // entries to be unlinkable at all, and Git deliberately drops it
            // on pack dirs. Without this the delete fails on exactly the
            // files #375 is about. Best effort: a chmod that fails just
            // means the delete below reports the path.
            restoreWritable(target)
            if (deleteTreeNoFollow(target) && !target.exists()) {
                freed += size
            } else {
                failed += target.absolutePath
                // Some of it may still have gone; count only what really left.
                freed += size - directorySize(target)
            }
        }

        wipe(sessionDir(sessionId))
        for (m in mediaDirs(sessionId)) wipe(m)
        return DeleteResult(freed, failed)
    }

    /**
     * Give every entry under [root] its owner-write bit back, deepest first.
     *
     * Directories are what actually matter — unlinking a file is governed by
     * its PARENT's permissions, not its own — but read-only regular files are
     * restored too so a later re-scan does not trip over them.
     */
    private fun restoreWritable(root: File) {
        // [T-android-session-delete-nofollow] Never follow symlinks. The old
        // `walkBottomUp()` descended into a symlinked directory, so a link in
        // the workspace (the sandbox can create one; a relative guest link
        // climbing out of /var/minis/workspace resolves on the host into a
        // neighbouring directory) got a directory OUTSIDE this session
        // chmod'ed. walkFileTree without FOLLOW_LINKS reports a link as a
        // plain entry, and File.setWritable on a link would change its
        // target, so links are skipped outright.
        try {
            java.nio.file.Files.walkFileTree(
                root.toPath(),
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun preVisitDirectory(
                        dir: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        val f = dir.toFile()
                        if (!f.canWrite()) runCatching { f.setWritable(true, true) }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        if (!attrs.isSymbolicLink) {
                            val f = file.toFile()
                            if (!f.canWrite()) runCatching { f.setWritable(true, true) }
                        }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: java.nio.file.Path,
                        exc: java.io.IOException,
                    ): java.nio.file.FileVisitResult = java.nio.file.FileVisitResult.CONTINUE
                },
            )
        } catch (_: Exception) {
            // A walk failure here is not fatal: the delete below still runs
            // and will report whatever it could not remove.
        }
    }

    /**
     * [T-android-session-delete-nofollow] Delete [root] and everything under
     * it WITHOUT following symlinks: a link is unlinked as a link and its
     * target is left alone.
     *
     * `File.deleteRecursively()` walks with `walkBottomUp()`, which descends
     * into a symlinked directory and deletes the TARGET's contents — so
     * "Clear files" on session A, whose workspace held a link to session B's
     * workspace, wiped B's files. That is also the rule [directorySize]
     * already applies when measuring (a link's target is someone else's
     * bytes), so the freed figure and the deletion now agree.
     *
     * Best effort, like deleteRecursively: keeps going past failures and
     * returns true only when nothing is left.
     */
    private fun deleteTreeNoFollow(root: File): Boolean {
        val rootPath = root.toPath()
        if (!java.nio.file.Files.exists(rootPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
        try {
            java.nio.file.Files.walkFileTree(
                rootPath,
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(
                        file: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        // Without FOLLOW_LINKS a link (to a file OR a
                        // directory) arrives here; Files.delete removes the
                        // link itself.
                        runCatching { java.nio.file.Files.delete(file) }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: java.nio.file.Path,
                        exc: java.io.IOException,
                    ): java.nio.file.FileVisitResult {
                        runCatching { java.nio.file.Files.delete(file) }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: java.nio.file.Path,
                        exc: java.io.IOException?,
                    ): java.nio.file.FileVisitResult {
                        runCatching { java.nio.file.Files.delete(dir) }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: Exception) {
            // Fall through: the existence check below is the verdict.
        }
        return !java.nio.file.Files.exists(rootPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    }

    fun hasFiles(sessionId: String): Boolean =
        sessionDir(sessionId).exists() || mediaDirs(sessionId).isNotEmpty()

    companion object {
        const val SESSIONS_DIR = "minis-sessions"
        const val MEDIA_DIR = "media"

        /**
         * Bytes really stored under [dir], counting each file once and never
         * leaving the subtree.
         *
         * [T-android-storage-symlink-inflation] This used to be a plain
         * `walkTopDown()` summing `length()` of everything `isFile` said yes
         * to — and every one of those three calls resolves symlinks. So the
         * walk behaved like `du -L`, not `du`:
         *
         *   - a symlink to a FILE was counted as a second full copy of the
         *     target's bytes (the Alpine rootfs ships ~335 of these — busybox
         *     applets — and a user's apk installs add more);
         *   - a symlink to a DIRECTORY was DESCENDED INTO, so the whole target
         *     subtree was counted again, once per link pointing at it. This is
         *     what turns a few GB of real files into tens of GB on the storage
         *     screen: `lib -> usr/lib`, `.venv/lib64 -> lib`, node_modules and
         *     apk layouts all create them, and the inflation multiplies rather
         *     than adds.
         *
         * A user reported "Shell 容器 43.89 GB" while Android's own app-storage
         * page said 9.60 GB of data for the same install. Android counts real
         * blocks; we counted symlink targets repeatedly. That is the whole gap
         * — nothing was double-counted from bind mounts, which a host-side walk
         * cannot even see (PRoot's `-b` exists only inside the PRoot process,
         * and `var/minis/mounts/<name>` on disk is an empty placeholder dir).
         *
         * A symlink contributes nothing here on purpose: its target is either
         * inside the subtree (already counted at its real path) or outside it
         * (someone else's bytes, which deleting this directory would not free).
         * That also makes the number honest for [deleteFiles], which reports it
         * as "bytes freed".
         *
         * Still an apparent-size sum, not `du`'s block count: sparse files read
         * high and small files read low against a 4 KiB block. That difference
         * is small and stable, unlike the symlink multiplier.
         */
        fun directorySize(dir: File): Long {
            if (!dir.exists()) return 0L
            var total = 0L
            // walkTopDown()'s own traversal follows directory symlinks, so the
            // filter below cannot prevent descending into one — the link has to
            // be pruned via onEnter before the walk steps through it.
            dir.walkTopDown()
                .onEnter { !isSymlink(it) }
                .forEach { f ->
                    if (!isSymlink(f) && f.isFile) total += f.length()
                }
            return total
        }

        /**
         * True when [f] is itself a symbolic link.
         *
         * `Files.isSymbolicLink` does not follow the final component, which is
         * exactly the question being asked. It is also cheap — one lstat — and
         * total: a broken link (target deleted) answers true rather than
         * throwing, and an unreadable path answers false and is then simply
         * counted as the zero-length entry it appears to be.
         */
        private fun isSymlink(f: File): Boolean =
            try {
                java.nio.file.Files.isSymbolicLink(f.toPath())
            } catch (_: Exception) {
                // InvalidPathException / SecurityException — treat as a normal
                // entry rather than letting a sizing call take down the screen.
                false
            }
    }
}
