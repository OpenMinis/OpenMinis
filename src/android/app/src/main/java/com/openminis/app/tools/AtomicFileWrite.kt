package com.openminis.app.tools

import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Serialises and verifies the two filesystem-mutating tools.
 *
 * ## The bug this fixes
 *
 * `FileEditTool` is a read-modify-write over the whole file with no locking:
 *
 * ```
 * val content = file.readText()   // v1
 * ... compute newContent ...
 * file.writeText(newContent)      // overwrites with v1+edit
 * ```
 *
 * Two edits running concurrently each read v1 and each write back their own
 * view of it, so the first edit is silently discarded — and BOTH calls return
 * success, because neither `writeText` threw. Callers that re-check the tree
 * afterwards see their change gone with no error anywhere in the log. The same
 * race swallows a `file_write` that lands while a `file_edit` is mid-flight.
 *
 * The tools already run in parallel (sub-agents, multi-tool turns), so this is
 * not hypothetical: it is the reported "reported success but nothing landed"
 * failure.
 *
 * ## What it does
 *
 *  1. **Per-path lock.** Every write to a given canonical path is serialised,
 *     so a read-modify-write can never interleave with another write to the
 *     same file. Different paths stay concurrent — locking globally would
 *     needlessly serialise independent sub-agents.
 *  2. **Post-write verification.** After the write the file is re-read from
 *     disk and compared against what was meant to be written. A short write,
 *     a shadowed FUSE view, or a full disk now returns an ERROR instead of a
 *     success the caller will act on.
 *  3. **Atomic replace.** Content is written to a sibling temp file and
 *     renamed over the target. `writeText` truncates in place, so a crash
 *     mid-write leaves a half-written file; rename makes the old-or-new
 *     choice atomic. The temp file is removed on every failure path.
 *
 * Append mode cannot use rename (it must build on the existing bytes), so it
 * is verified but not made atomic — that is a property of append, not an
 * omission.
 */
/**
 * [T-file-edit-silent-drop] Result payloads that carry the text to persist.
 *
 * `readModifyWrite` historically understood only two shapes — a plain
 * String (full new content) and a Pair (String first + metadata second).
 * `FileEditTool`'s transform returned a `ReplaceOutcome` (a data class
 * wrapping `newContent`), which matched NEITHER branch, fell into the
 * `else` arm, and was returned verbatim WITHOUT any write. FileEditTool
 * then saw a non-null outcome and reported success — "Edited N
 * replacements" — while the file on disk never changed. That is the
 * exact reported failure: tools claim success, nothing landed.
 *
 * Any transform payload that wants to persist implements this interface.
 */
interface HasPersistableText {
    /** The exact text to write to the file, or null for "nothing to persist". */
    fun persistableText(): String?
}

object AtomicFileWrite {

    private const val TAG = "AtomicFileWrite"
    private const val TEMP_SUFFIX = ".minis-tmp"

    /** Unit tests set this so a failed replace can be forced on every OS. */
    internal var failReplaceForTest = false

    /**
     * [T-append-verify-vacuous] Unit tests set this to simulate an append that
     * silently lands FEWER bytes than asked for (disk full / EIO swallowed by
     * `appendText`, which is exactly the failure the old post-hoc
     * `file.length()` sampling could not see). The value is the byte count the
     * file is truncated to right after the append.
     */
    internal var truncateAfterAppendForTest: Long = -1L

    /**
     * One lock per canonical path. The map is unbounded in theory; in practice
     * a session touches at most a few hundred distinct files, and an entry
     * costs a few dozen bytes. Reaping would need its own lock to do safely,
     * which is a worse trade than the memory.
     */
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    // [T-fileedit-size-cap] readModifyWrite + verify each read the whole
    // file; without a cap, `file_edit` on a multi-GB log doubles resident
    // memory and can OOM. 2 MB covers every legitimate source edit.
    internal const val MAX_EDIT_BYTES = 2 * 1024 * 1024

    /** [T-fileedit-locks-bounded] Idle-eviction threshold for [locks]. */
    internal const val MAX_LOCK_ENTRIES = 256

    private fun lockFor(file: File): ReentrantLock {
        // [T-fileedit-locks-bounded] A per-process map keyed by absolute path
        // has no natural end on long sessions. The earlier cap policy was a
        // blanket clear() — which is exactly wrong for a MUTEX table: dropping
        // a lock another writer is holding (or queued on) lets a concurrent
        // writer of the SAME path take a fresh lock object and interleave
        // its read-modify-write. Evict IDLE locks only (unlocked, no waiters);
        // if every entry is contended the map temporarily exceeds the cap
        // instead — overshoot bounded by concurrently-edited file count.
        val path = file.canonicalPath
        if (locks.size >= MAX_LOCK_ENTRIES && !locks.containsKey(path)) {
            val iter = locks.entries.iterator()
            while (iter.hasNext() && locks.size > MAX_LOCK_ENTRIES / 2) {
                val e = iter.next()
                if (e.key != path && !e.value.isLocked && !e.value.hasQueuedThreads()) iter.remove()
            }
        }
        return locks.getOrPut(path) { ReentrantLock() }
    }

    /**
     * Overwrite [file] with [content], atomically and verified.
     *
     * @param append when true, append instead of overwrite (verified, not atomic)
     * @return the byte length reported on disk, or null when the write did not
     *   verifiably land — callers must treat null as a failure, NOT as success.
     */
    fun write(file: File, content: String, append: Boolean = false): Long? {
        val lock = lockFor(file)
        lock.lock()
        try {
            return writeLocked(file, content, append)
        } finally {
            lock.unlock()
        }
    }

    /**
     * Move [tmp] onto [file] without deleting [file] first. A failed rename
     * copies over the destination; if that also fails the previous bytes stay.
     */
    private fun replaceSibling(tmp: File, file: File): Boolean {
        if (failReplaceForTest) return false
        if (tmp.renameTo(file)) return true
        return try {
            tmp.copyTo(file, overwrite = true)
            file.isFile && file.length() == tmp.length()
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Move an already-written [tmp] file onto [file] atomically, under the
     * same per-path lock every other writer takes.
     *
     * [T-checkpoint-restore-atomic] Callers that hand-roll `renameTo` tend to
     * "fix" a failed rename with `target.delete(); rename again` — and when
     * the retry also fails, the only copy of the user's file is gone. This
     * never deletes the target first: rename, or copy-over as a fallback, or
     * return false with the previous bytes intact.
     */
    fun moveIntoPlace(tmp: File, file: File): Boolean {
        val lock = lockFor(file)
        lock.lock()
        try {
            if (!replaceSibling(tmp, file)) {
                AppLogger.error(TAG, "replace ${tmp.name} -> ${file.name} failed; previous bytes kept")
                return false
            }
            if (tmp.exists()) tmp.delete()
            return true
        } finally {
            lock.unlock()
        }
    }

    private fun writeLocked(file: File, content: String, append: Boolean): Long? {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) {
                AppLogger.error(TAG, "could not create parent ${parent.absolutePath}")
                return null
            }
        }

        if (append) {
            // Append cannot go through rename — the result depends on the
            // bytes already there. Verify instead of making atomic.
            //
            // [T-append-verify-vacuous] The check used to be
            // `verify(file, file.length(), content, appended = true)`: the
            // "expected" length was sampled AFTER appendText, so it was the same
            // reading as the one compared against — `file.length() == file.length()`
            // always true — and the `appended` branch skipped the byte
            // comparison entirely. Nothing about the append was verified: a
            // silent short write (disk full, EIO swallowed by appendText) was
            // reported as success. Now the PREVIOUS length is captured, growth is
            // checked against the exact byte count of [content], and the tail is
            // re-read and compared.
            return try {
                val priorLen = if (file.exists()) file.length() else 0L
                file.appendText(content)
                if (truncateAfterAppendForTest >= 0) {
                    // Test-only: emulate the short write the check must catch.
                    try {
                        java.io.RandomAccessFile(file, "rw").use { it.setLength(truncateAfterAppendForTest) }
                    } catch (_: Exception) {
                    }
                }
                verifyAppend(file, priorLen, content)
            } catch (e: IOException) {
                AppLogger.error(TAG, "append failed for ${file.name}: ${e.message}")
                null
            }
        }

        val tmp = File(parent, file.name + TEMP_SUFFIX)
        try {
            tmp.writeText(content)
            val written = tmp.length()
            val expected = content.toByteArray(Charsets.UTF_8).size.toLong()
            if (written != expected) {
                AppLogger.error(
                    TAG,
                    "short write to temp ${tmp.name}: $written of $expected bytes (disk full?)",
                )
                tmp.delete()
                return null
            }
            // Do not delete [file] before the new bytes are in place.
            // renameTo fails when the destination exists on some hosts; deleting
            // first then failing the rename discarded the only copy.
            if (!replaceSibling(tmp, file)) {
                AppLogger.error(TAG, "replace ${tmp.name} -> ${file.name} failed; previous bytes kept")
                tmp.delete()
                return null
            }
            if (tmp.exists()) tmp.delete()
            return verify(file, written, content)
        } catch (e: IOException) {
            AppLogger.error(TAG, "write failed for ${file.name}: ${e.message}")
            tmp.delete()
            return null
        } catch (e: SecurityException) {
            AppLogger.error(TAG, "write denied for ${file.name}: ${e.message}")
            tmp.delete()
            return null
        }
    }

    /**
     * Read [file] under the same per-path lock the writers take.
     *
     * A read that does not hold the lock can observe bytes that a concurrent
     * write is halfway through replacing. Callers that need a consistent view
     * of a file they also write should use this rather than `File.readText()`.
     */
    fun read(file: File): String? {
        val lock = lockFor(file)
        lock.lock()
        try {
            return try {
                if (file.exists()) file.readText() else ""
            } catch (e: IOException) {
                AppLogger.error(TAG, "read failed for ${file.name}: ${e.message}")
                null
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Read [file], apply [transform] to its current contents, and write the
     * result back — all under the per-path lock.
     *
     * This is what makes `file_edit` safe. The read has to happen INSIDE the
     * lock, not before it: a read taken before acquiring the lock can observe a
     * snapshot that another writer is already replacing, and the computed edit
     * then silently reverts that writer's change.
     *
     * @return the transform's result, or null when the write did not verify.
     *   The lock is held for the whole read-compute-write, so a null result
     *   never leaves a half-applied edit.
     */
    fun <T> readModifyWrite(file: File, transform: (String) -> T): T? {
        val lock = lockFor(file)
        lock.lock()
        try {
            // [T-fileedit-binary-guard] Read BYTES and decode strictly. The
            // old `file.readText()` silently replaced malformed sequences
            // with U+FFFD and then wrote the CORRUPTED text back to disk;
            // verify() compared against the already-corrupted string and was
            // blind to the damage. NUL bytes and invalid UTF-8 are refused.
            // [T-fileedit-size-cap] The length check runs BEFORE readBytes:
            // checking after the read loaded a multi-GB file fully into
            // memory before refusing it — the cap existed to prevent exactly
            // that. The post-read `raw.size` check stays as a race guard
            // (the file can grow between length() and readBytes()).
            if (file.exists() && file.length() > MAX_EDIT_BYTES) {
                AppLogger.error(TAG, "file_edit refused: ${file.name} is ${file.length()} bytes (cap $MAX_EDIT_BYTES)")
                return null
            }
            val raw = try {
                if (file.exists()) file.readBytes() else ByteArray(0)
            } catch (e: IOException) {
                AppLogger.error(TAG, "read failed for ${file.name}: ${e.message}")
                return null
            }
            if (raw.size > MAX_EDIT_BYTES) {
                AppLogger.error(TAG, "file_edit refused: ${file.name} is ${raw.size} bytes (cap $MAX_EDIT_BYTES)")
                return null
            }
            if (raw.contains(0.toByte())) {
                AppLogger.error(TAG, "file_edit refused: ${file.name} looks binary (NUL byte)")
                return null
            }
            val hadBom = raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()
            val current = try {
                java.nio.ByteBuffer.wrap(raw).let { buf ->
                    kotlin.text.Charsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(buf).toString()
                }
            } catch (e: java.nio.charset.CharacterCodingException) {
                AppLogger.error(TAG, "file_edit refused: ${file.name} is not valid UTF-8 (${e.message})")
                return null
            }.removePrefix("\uFEFF")

            val result = transform(current)

            // [T-fileedit-bom] A BOM was stripped before matching (an
            // old_string written without one used to fail all three match
            // tiers against a BOM-prefixed file); restore it on write so the
            // file's original encoding convention survives the edit.
            fun withBom(text: String): String =
                if (hadBom) "﻿" + text else text

            return when (result) {
                // A String payload is the plain "new content" shape; a Pair is
                // what file_edit returns so it can report the replacement count
                // alongside the text. Both are written; anything else is passed
                // through untouched (the caller ran the transform for its own
                // reasons and there is nothing to persist).
                is String -> writeLocked(file, withBom(result), append = false)?.let { result }
                is Pair<*, *> -> {
                    val text = result.first as? String
                    if (text == null) {
                        AppLogger.error(TAG, "readModifyWrite: pair without a String payload")
                        null
                    } else {
                        writeLocked(file, withBom(text), append = false)?.let { result }
                    }
                }
                is HasPersistableText -> {
                    val text = result.persistableText()
                    if (text == null) {
                        AppLogger.info(TAG, "readModifyWrite: payload declined persist, nothing written")
                        result
                    } else {
                        writeLocked(file, withBom(text), append = false)?.let { result }
                    }
                }
                else -> {
                    AppLogger.info(TAG, "readModifyWrite: non-string result, nothing written")
                    result
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Re-read from disk and confirm the bytes are really there.
     *
     * The check is on the FILE, not on the writer's return value: `writeText`
     * returning without throwing is not evidence that the bytes reached the
     * filesystem, which is exactly how the original bug stayed invisible.
     */
    private fun verify(file: File, expectedLen: Long, content: String): Long? {
        if (!file.exists()) {
            AppLogger.error(TAG, "verify failed: ${file.absolutePath} does not exist after write")
            return null
        }
        if (file.length() != expectedLen) {
            AppLogger.error(
                TAG,
                "verify failed: ${file.name} is ${file.length()} bytes, expected $expectedLen",
            )
            return null
        }
        val onDisk = try {
            file.readText()
        } catch (e: IOException) {
            AppLogger.error(TAG, "verify failed: cannot re-read ${file.name}: ${e.message}")
            return null
        }
        if (onDisk != content) {
            AppLogger.error(TAG, "verify failed: ${file.name} content differs after write")
            return null
        }
        return file.length()
    }

    /**
     * Verify an APPEND: the file must be exactly `priorLen + content.toByteArray()`
     * bytes long, and those new bytes must actually be at the end.
     *
     * [T-append-verify-vacuous] Only the tail is re-read (bounded by the appended
     * content itself), so this stays cheap on a log file — the previous length is
     * supplied by the caller, captured BEFORE appendText ran, never after.
     */
    private fun verifyAppend(file: File, priorLen: Long, content: String): Long? {
        if (!file.exists()) {
            AppLogger.error(TAG, "append verify failed: ${file.absolutePath} vanished")
            return null
        }
        val payload = content.toByteArray(Charsets.UTF_8)
        val expectedLen = priorLen + payload.size
        val actual = file.length()
        if (actual != expectedLen) {
            AppLogger.error(
                TAG,
                "append verify failed: ${file.name} grew ${actual - priorLen} bytes, " +
                    "expected ${payload.size} (was $priorLen, now $actual, expected $expectedLen)",
            )
            return null
        }
        if (payload.isEmpty()) return actual
        val tail = try {
            file.inputStream().use { input ->
                // skip() may return short on a generic InputStream — loop it.
                var toSkip = priorLen
                while (toSkip > 0) {
                    val s = input.skip(toSkip)
                    if (s <= 0) break
                    toSkip -= s
                }
                if (toSkip > 0) return@use null
                val buf = ByteArray(payload.size)
                var read = 0
                while (read < payload.size) {
                    val n = input.read(buf, read, payload.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read != payload.size) return@use null
                buf
            }
        } catch (e: IOException) {
            AppLogger.error(TAG, "append verify failed: cannot re-read ${file.name}: ${e.message}")
            null
        }
        if (tail == null || !tail.contentEquals(payload)) {
            AppLogger.error(TAG, "append verify failed: ${file.name} tail bytes differ after append")
            return null
        }
        return actual
    }
}
