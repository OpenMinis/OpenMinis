package com.openminis.app.sandbox

import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * Moves a session's private workspace files into a project folder, safely.
 *
 * ## Why this is not just `File.renameTo`
 *
 * A session's `workspace/` can hold anything the user put there — a cloned
 * repo is the normal case, and this device has a 569 MB one. Three things go
 * wrong with a naive move:
 *
 *  1. `renameTo` fails silently across filesystems, leaving the source intact
 *     and the caller believing the move happened. `copyRecursively` + `delete`
 *     has the opposite failure: a kill halfway leaves BOTH trees and no record
 *     of which is authoritative.
 *  2. A multi-second copy with no progress and no cancel looks like a hang, and
 *     the process is exactly what Android reclaims while it is in the
 *     background doing it.
 *  3. There is no way to tell a completed move from an interrupted one.
 *
 * ## The protocol
 *
 * Copy to `<dest>.staging`, verify byte counts, rename staging into place, then
 * delete the source. An empty destination is removed first; renameTo will not
 * replace one. The `.staging` suffix marks an interrupted move for [recoverInterrupted].
 *
 * Ordering matters: the source is only deleted after the staging copy is
 * complete, so an interruption at any point leaves at least one intact tree
 * and a `.staging` sibling to resume from. Only an empty placeholder is
 * removed before the verified copy is in place.
 *
 * ## Scope
 *
 * Only the four shared subdirs (`attachments`, `offloads`, `workspace`,
 * `browser`) move. `memory/` stays with the session — it is the session's
 * private diary and the migrator copies it separately.
 */
object WorkspaceMover {

    private const val TAG = "WorkspaceMover"
    private const val STAGING_SUFFIX = ".staging"
    private const val COPY_BUFFER = 64 * 1024

    data class MoveResult(
        val movedSubdirs: List<String>,
        val bytesMoved: Long,
        val alreadyInPlace: List<String>,
        val conflicts: List<String> = emptyList(),
        val refused: Boolean = false,
        // [T-per-subdir-isolation] Subdirs whose staging copy threw. Each is
        // handled independently so one failing tree does not strand the others;
        // a failed move keeps that source intact.
        val failed: List<String> = emptyList(),
    ) {
        val succeeded: Boolean get() = !refused && conflicts.isEmpty() && failed.isEmpty()
    }

    data class CopyResult(
        val copiedSubdirs: List<String>,
        val bytesCopied: Long,
        val skippedEmpty: List<String>,
        val conflicts: List<String>,
        val refused: Boolean = false,
        // [T-per-subdir-isolation] Copy-back subdirs whose staging copy threw;
        // the source project tree is untouched for them.
        val failed: List<String> = emptyList(),
    ) {
        /** True iff this session no longer needs the project tree to keep its files. */
        val independent: Boolean
            get() = !refused && conflicts.isEmpty() && failed.isEmpty()
    }

    /**
     * Move [sessionId]'s shared subdirs into [folderId]'s project directory.
     *
     * Idempotent: a subdir that is already the project's (or absent) is left
     * alone and reported in [MoveResult.alreadyInPlace], so re-running after a
     * partial failure converges instead of duplicating data.
     */
    fun moveSessionIntoProject(
        filesDir: File,
        sessionId: String,
        folderId: String,
    ): MoveResult {
        if (!SessionWorkspace.isSafeId(sessionId) || !SessionWorkspace.isSafeId(folderId)) {
            AppLogger.warning(TAG, "refusing move: unsafe id session=$sessionId folder=$folderId")
            return MoveResult(emptyList(), 0L, emptyList(), refused = true)
        }
        val sessionRoot = SessionWorkspace.base(filesDir, sessionId)
        val projectRoot = SessionWorkspace.projectBase(filesDir, folderId)
        SessionWorkspace.ensureProjectDirs(filesDir, folderId)


        val moved = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var bytes = 0L

        for (sub in SessionWorkspace.SHARED_SUBDIRS) {
            val src = File(sessionRoot, sub)
            if (!src.isDirectory() || src.listFiles().isNullOrEmpty()) {
                // Nothing to move. Also covers the already-filed case, where
                // the session never had its own copy to begin with.
                skipped.add(sub)
                continue
            }
            val dst = File(projectRoot, sub)
            if (dst.isDirectory() && !dst.listFiles().isNullOrEmpty()) {
                // Destination already holds files. Merging two non-empty trees
                // needs a conflict policy this does not have, so leave the
                // session's copy alone rather than silently interleaving them.
                AppLogger.warning(
                    TAG,
                    "merge conflict for $sub (session=$sessionId folder=$folderId); " +
                        "session copy left in place",
                )
                conflicts.add(sub)
                continue
            }
            // [T-per-subdir-isolation] One failing subdir must not strand the
            // others: moveTree throws after discarding its own staging copy and
            // keeping the source, so catch per subdir and continue.
            try {
                bytes += moveTree(src, dst)
                moved.add(sub)
            } catch (t: Throwable) {
                AppLogger.warning(
                    TAG,
                    "move of $sub failed (session=$sessionId folder=$folderId): ${t.message}; " +
                        "source kept, other subdirs unaffected",
                )
                failed.add(sub)
            }
        }

        if (conflicts.isNotEmpty()) {
            writeConflictRecord(filesDir, sessionId, folderId, conflicts)
            AppLogger.warning(
                TAG,
                "merge conflict session=$sessionId folder=$folderId subdirs=$conflicts; " +
                    "not a successful move",
            )
        } else {
            AppLogger.info(
                TAG,
                "moved session=$sessionId into folder=$folderId subdirs=$moved " +
                    "bytes=$bytes skipped=$skipped failed=$failed",
            )
        }
        return MoveResult(moved, bytes, skipped, conflicts, failed = failed)
    }

    private fun writeConflictRecord(
        filesDir: File,
        sessionId: String,
        folderId: String,
        conflicts: List<String>,
    ) {
        val dir = File(filesDir, "workspace-conflicts")
        dir.mkdirs()
        val file = File(dir, "$sessionId.json")
        val body = buildString {
            append("{\"sessionId\":\"").append(sessionId)
            append("\",\"folderId\":\"").append(folderId)
            append("\",\"recoverable\":true,\"conflicts\":[")
            append(conflicts.joinToString(",") { "\"$it\"" })
            append("]}")
        }
        val tmp = File(dir, "$sessionId.json.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
            file.writeText(body)
            tmp.delete()
        }
    }

    /**
     * Copy a project's shared subdirs back into [sessionId]'s private dir, for
     * the moment a session is REMOVED from that project: its [SessionWorkspace.hostDir]
     * starts resolving to the private dir again, so without this the session
     * comes up looking empty.
     *
     * COPY, not move — the project tree is shared by its other members and must
     * survive this call untouched. Whether the project copy is then redundant
     * is the caller's call (it depends on whether any member is left), not
     * ours.
     *
     * Same staging protocol as [moveSessionIntoProject]: copy to `<dst>.staging`,
     * verify byte counts, then rename into place. An interruption leaves either
     * nothing or a complete tree in the private dir — never a half-written one
     * the session would boot with.
     *
     * A non-empty private subdir is a [CopyResult.conflicts] entry, not a
     * success: overwriting it would destroy the session's own files, and the
     * caller must not delete the project while that shared copy is the only
     * remaining one.
     */
    fun copyProjectToSession(
        filesDir: File,
        sessionId: String,
        folderId: String,
    ): CopyResult {
        if (!SessionWorkspace.isSafeId(sessionId) || !SessionWorkspace.isSafeId(folderId)) {
            AppLogger.warning(TAG, "refusing copy-back: unsafe id session=$sessionId folder=$folderId")
            return CopyResult(emptyList(), 0L, emptyList(), emptyList(), refused = true)
        }
        val projectRoot = SessionWorkspace.projectBase(filesDir, folderId)
        val sessionRoot = SessionWorkspace.base(filesDir, sessionId)

        val copied = mutableListOf<String>()
        val skippedEmpty = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var bytes = 0L

        for (sub in SessionWorkspace.SHARED_SUBDIRS) {
            val src = File(projectRoot, sub)
            if (!src.isDirectory() || src.listFiles().isNullOrEmpty()) {
                skippedEmpty.add(sub)
                continue
            }
            val dst = File(sessionRoot, sub)
            if (dst.isDirectory() && !dst.listFiles().isNullOrEmpty()) {
                AppLogger.warning(
                    TAG,
                    "copy-back conflict for $sub (session=$sessionId folder=$folderId); " +
                        "private copy kept, shared tree untouched",
                )
                conflicts.add(sub)
                continue
            }
            // [T-per-subdir-isolation] A failed copy-back of one subdir keeps
            // the shared project tree untouched and does not block the others.
            try {
                bytes += copyTreeVerified(src, dst)
                copied.add(sub)
            } catch (t: Throwable) {
                AppLogger.warning(
                    TAG,
                    "copy-back of $sub failed (session=$sessionId folder=$folderId): ${t.message}; " +
                        "shared tree untouched, other subdirs unaffected",
                )
                failed.add(sub)
            }
        }

        AppLogger.info(
            TAG,
            "copy-back folder=$folderId to session=$sessionId subdirs=$copied " +
                "bytes=$bytes skippedEmpty=$skippedEmpty conflicts=$conflicts failed=$failed",
        )
        return CopyResult(copied, bytes, skippedEmpty, conflicts, failed = failed)
    }

    /**
     * Copy [src] to [dst] via a sibling `.staging` dir. Never touches the source.
     * [T-symlink-expansion] Verification no longer re-walks the live source; the
     * copy is the single walk, so a per-entry failure (not a byte-count mismatch)
     * is what discards staging and keeps the source.
     */
    private fun copyTreeVerified(src: File, dst: File): Long {
        val parent = dst.parentFile ?: error("no parent for $dst")
        clearEmptyDestination(dst)
        val staging = File(parent, dst.name + STAGING_SUFFIX)
        if (staging.exists()) staging.deleteRecursively()

        val outcome = copyTree(src, staging)
        if (outcome.failures > 0) {
            staging.deleteRecursively()
            throw IllegalStateException(
                "staging copy incomplete for ${src.name}: ${outcome.failures} entry(ies) failed to copy",
            )
        }
        if (dst.exists() && !dst.deleteRecursively()) {
            staging.deleteRecursively()
            throw IllegalStateException("could not clear ${dst.absolutePath} before rename")
        }
        if (!staging.renameTo(dst)) {
            // Leave staging in place for [recoverInterrupted]; the source is intact.
            throw IllegalStateException("could not move ${src.name} into place")
        }
        return outcome.bytes
    }

    /**
     * Move one tree via staging. [src] is deleted only after [dst] has a
     * complete copy.
     */
    private fun moveTree(src: File, dst: File): Long {
        val parent = dst.parentFile ?: error("no parent for $dst")
        val staging = File(parent, dst.name + STAGING_SUFFIX)
        if (staging.exists()) staging.deleteRecursively()

        val outcome = copyTree(src, staging)
        if (outcome.failures > 0) {
            // A failed copy means the staging tree is not a faithful mirror.
            // Keep the source, drop the partial copy.
            staging.deleteRecursively()
            throw IllegalStateException(
                "staging copy incomplete for ${src.name}: ${outcome.failures} entry(ies) failed to copy",
            )
        }
        val bytes = outcome.bytes

        // ensureProjectDirs leaves an empty destination. renameTo will not
        // replace it, and deleting the source first hides the only copy behind
        // that failed rename. Clear the placeholder, rename, then drop source.
        clearEmptyDestination(dst)
        if (!staging.renameTo(dst)) {
            staging.deleteRecursively()
            throw IllegalStateException("could not move ${src.name} into place")
        }
        if (!src.deleteRecursively()) {
            AppLogger.warning(TAG, "moved ${src.name} but could not delete source ${src.absolutePath}")
        }
        return bytes
    }

    /** renameTo fails when the destination exists. Only an empty directory is cleared. */
    private fun clearEmptyDestination(dst: File) {
        if (!dst.exists()) return
        if (!dst.isDirectory) {
            throw IllegalStateException("move destination is a file: ${dst.absolutePath}")
        }
        val kids = dst.listFiles()
            ?: throw IllegalStateException("cannot list move destination: ${dst.absolutePath}")
        if (kids.isNotEmpty()) {
            throw IllegalStateException("move destination is not empty: ${dst.absolutePath}")
        }
        if (!dst.delete()) {
            throw IllegalStateException("cannot remove empty move destination: ${dst.absolutePath}")
        }
    }

    /**
     * [T-symlink-expansion] Copy [src] into [dst] as links, never following.
     *
     * The old walker copied regular files via `File.inputStream()`, which
     * FOLLOWS symlinks: a guest `ln -s <another-session>/memory …` inside its
     * workspace had the LINKED-TO CONTENT copied into `minis-workspaces/<folder>`,
     * a tree every other filed session mounts — a cross-session leak. A symlinked
     * DIRECTORY made `walkTopDown` descend and eventually throw, aborting the
     * whole move. And because the byte verification re-walked the live,
     * guest-writable source afterwards, any concurrent write guaranteed a spurious
     * "staging copy incomplete".
     *
     * Now: one top-down pass, symlinks recreated as symlinks via
     * `Files.createSymbolicLink` (the recorded target string is preserved, its
     * content is never materialised); real directories are descended, special
     * files (fifo/socket/device) are skipped rather than opened. The returned
     * byte total is accumulated DURING that same pass, so there is no second walk
     * to race. A per-entry IO error is recorded in [failed] and the caller
     * discards the staging copy, keeping the source intact.
     */
    private class CopyOutcome {
        var bytes: Long = 0L
        var failures: Int = 0
    }

    private fun copyTree(src: File, dst: File): CopyOutcome {
        val outcome = CopyOutcome()
        dst.mkdirs()
        copyInto(src, dst, outcome)
        return outcome
    }

    private fun copyInto(dir: File, dstDir: File, outcome: CopyOutcome) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            val target = File(dstDir, child.name)
            try {
                val path = child.toPath()
                when {
                    java.nio.file.Files.isSymbolicLink(path) -> {
                        // Copy as a link — do NOT follow, do NOT descend.
                        target.parentFile?.mkdirs()
                        java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Files.readSymbolicLink(path))
                    }
                    child.isDirectory() -> copyInto(child, target, outcome)
                    child.isFile() -> {
                        target.parentFile?.mkdirs()
                        child.inputStream().use { input ->
                            target.outputStream().use { output ->
                                val buf = ByteArray(COPY_BUFFER)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    output.write(buf, 0, n)
                                    outcome.bytes += n
                                }
                            }
                        }
                    }
                    else -> Unit // special file: skip (never open)
                }
            } catch (t: Throwable) {
                outcome.failures++
                AppLogger.warning(TAG, "copyTree: could not copy ${child.absolutePath}: ${t.message}")
            }
        }
    }

    /**
     * Find `.staging` trees left behind by an interrupted move or copy-back.
     *
     * If the real destination is missing, finish the rename. For a move the
     * source may already be gone, so the staging tree is the remaining copy.
     * For a copy-back the source is still intact; finishing the rename is still
     * the right recovery. A staging tree whose real destination already exists
     * is ambiguous — both trees exist and neither is provably newer — so it is
     * reported and left for the caller to resolve, never auto-deleted.
     */
    fun recoverInterrupted(filesDir: File): List<File> {
        val found = mutableListOf<File>()
        for (root in sequenceOf(
            File(filesDir, SessionWorkspace.WORKSPACES_DIR),
            File(filesDir, SessionWorkspace.SESSIONS_DIR),
        )) {
            if (!root.isDirectory()) continue
            root.walkTopDown()
                .filter { it.name.endsWith(STAGING_SUFFIX) }
                .forEach { staging ->
                    val real = File(staging.parentFile, staging.name.removeSuffix(STAGING_SUFFIX))
                    if (real.exists()) {
                        val placeholder = real.isDirectory && real.listFiles()?.isEmpty() == true
                        if (placeholder && real.delete() && staging.renameTo(real)) {
                            AppLogger.info(TAG, "recovered staging tree over empty placeholder -> ${real.absolutePath}")
                        } else {
                            AppLogger.warning(
                                TAG,
                                "ambiguous staging tree (both exist): ${staging.absolutePath}",
                            )
                        }
                    } else if (staging.renameTo(real)) {
                        AppLogger.info(TAG, "recovered staging tree -> ${real.absolutePath}")
                    } else {
                        AppLogger.warning(TAG, "could not recover ${staging.absolutePath}")
                    }
                    found.add(staging)
                }
        }
        return found
    }
}
