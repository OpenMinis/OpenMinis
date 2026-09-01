package com.openminis.app.backup

import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Content-addressed blob writer for the staging directory (§2), mirroring
 * `src/ios/Agent/Backup/BackupBlobStore.swift`.
 *
 * Two jobs:
 *   1. **Dedup by content.** The same bytes referenced from several sessions —
 *      or from both a message attachment and a session's file tree — are stored
 *      once under their SHA-256. Callers don't coordinate; they offer files and
 *      get told which hash they landed on.
 *   2. **Enforce the §3.4 size cap.** Over-limit files are refused as
 *      [Outcome.SkippedTooLarge] and the caller records a tombstone instead of
 *      a blob. The cap is checked before any hashing or copying, so a 2GB file
 *      costs a `length()`, not a read.
 *
 * @param maxFileBytes null = unlimited (the §3.4 default).
 */
class BackupBlobStore(stagingRoot: File, private val maxFileBytes: Long?) {

    /**
     * `blobs/<first 2 hex>/<sha256>` — the two-level fan-out keeps any single
     * directory small enough to stay fast on huge packages.
     */
    private val blobsRoot = File(stagingRoot, "blobs")

    /**
     * The configured cap, for `manifest.limits.max_file_bytes`. Recorded even
     * when nothing was skipped, so a reader can tell "unlimited" apart from
     * "capped, but nothing exceeded it".
     */
    val maxFileBytesForManifest: Long? get() = maxFileBytes

    /** Hashes already written this run — cheap in-memory dedup. */
    private val seen = mutableSetOf<String>()

    private val _blobIndex = mutableListOf<BackupBlobIndexEntry>()
    val blobIndex: List<BackupBlobIndexEntry> get() = _blobIndex

    var totalBytesStored = 0L
        private set
    var skippedFiles = 0
        private set
    var skippedBytes = 0L
        private set

    /** Paths dropped by the cap, surfaced on the completion screen (§3.4). */
    val skippedPaths = mutableListOf<SkippedPath>()

    data class SkippedPath(val path: String, val size: Long)

    sealed interface Outcome {
        data class Stored(val sha256: String, val size: Long) : Outcome
        /** Same content already in the package; no new bytes written. */
        data class Duplicate(val sha256: String, val size: Long) : Outcome
        /** Excluded by the §3.4 cap. Caller must write a tombstone. */
        data class SkippedTooLarge(val size: Long) : Outcome
    }

    /**
     * Rebuild in-memory state from a staging tree left by an interrupted run.
     *
     * `blobs.index.jsonl` is only written at the very end of an export, so a
     * resumed run cannot read its own prior index back. `files.index.jsonl` IS
     * written incrementally and every stored entry carries the digest, so the
     * index is reconstructed from there and cross-checked against the blobs
     * actually on disk.
     *
     * Entries whose blob is missing (the run died between writing the index
     * line and copying the content) are deliberately NOT re-registered: the
     * digest stays out of [seen], so the next pass re-stores that file. That is
     * what stops a resumed package from referencing content it doesn't contain.
     */
    fun rehydrateFromFileIndex(fileIndex: File) {
        if (!fileIndex.exists()) return
        var restored = 0
        fileIndex.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val entry = runCatching {
                    BackupFormat.json.decodeFromString(BackupFileIndexEntry.serializer(), line)
                }.getOrNull() ?: continue
                val sha = entry.sha256 ?: continue
                if (entry.skipped != null || entry.isDirectory == true) continue
                if (!seen.add(sha)) continue
                val blob = blobFile(sha)
                if (!blob.exists()) {
                    seen.remove(sha)
                    continue
                }
                totalBytesStored += entry.size
                _blobIndex.add(
                    BackupBlobIndexEntry(sha, entry.size, entry.path, null, mimeType(entry.path))
                )
                restored += 1
            }
        }
        if (restored > 0) {
            AppLogger.info(TAG, "[Backup] resume: recovered $restored blob(s) from a previous attempt")
        }
    }

    /**
     * Offer a file on disk to the package. [logicalPath] is only recorded in
     * the index — the blob's location is derived purely from its content hash.
     */
    fun addFile(file: File, logicalPath: String, sessionId: String? = null): Outcome {
        val size = file.length()

        // Cap check FIRST — before hashing or copying. A skipped 2GB file must
        // cost a stat, not a full read.
        if (maxFileBytes != null && size > maxFileBytes) {
            skippedFiles += 1
            skippedBytes += size
            skippedPaths.add(SkippedPath(logicalPath, size))
            AppLogger.info(TAG, "[Backup] skip (size cap) $logicalPath size=$size > $maxFileBytes")
            return Outcome.SkippedTooLarge(size)
        }

        val digest = file.inputStream().use { sha256(it) }
        if (digest in seen) return Outcome.Duplicate(digest, size)

        val dest = blobFile(digest)
        dest.parentFile?.mkdirs()
        // An identical digest means identical content, so an existing file is
        // already correct — but `seen` missing it means a prior interrupted run
        // left it. Replace to be safe.
        if (dest.exists()) dest.delete()
        file.copyTo(dest, overwrite = true)

        seen.add(digest)
        totalBytesStored += size
        _blobIndex.add(BackupBlobIndexEntry(digest, size, logicalPath, sessionId, mimeType(logicalPath)))
        return Outcome.Stored(digest, size)
    }

    /** Offer in-memory bytes (generated content that never hits disk). */
    fun addBytes(data: ByteArray, logicalPath: String, sessionId: String? = null): Outcome {
        val size = data.size.toLong()
        if (maxFileBytes != null && size > maxFileBytes) {
            skippedFiles += 1
            skippedBytes += size
            skippedPaths.add(SkippedPath(logicalPath, size))
            return Outcome.SkippedTooLarge(size)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(data).toHex()
        if (digest in seen) return Outcome.Duplicate(digest, size)

        val dest = blobFile(digest)
        dest.parentFile?.mkdirs()
        dest.writeBytes(data)

        seen.add(digest)
        totalBytesStored += size
        _blobIndex.add(BackupBlobIndexEntry(digest, size, logicalPath, sessionId, mimeType(logicalPath)))
        return Outcome.Stored(digest, size)
    }

    private fun blobFile(digest: String) = File(File(blobsRoot, digest.take(2)), digest)

    companion object {
        private const val TAG = "Backup"

        /**
         * Streaming SHA-256 — never loads the file into memory. Media offloads
         * run to hundreds of MB, so this reads in 1MB chunks regardless of size.
         */
        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
            return digest.digest().toHex()
        }

        fun sha256OfFile(file: File): String = file.inputStream().buffered().use { sha256(it) }

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }

        fun mimeType(path: String): String? =
            when (path.substringAfterLast('.', "").lowercase()) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "heic" -> "image/heic"
                "pdf" -> "application/pdf"
                "txt", "log" -> "text/plain"
                "md" -> "text/markdown"
                "json" -> "application/json"
                "zip" -> "application/zip"
                "mp4", "m4v" -> "video/mp4"
                "mov" -> "video/quicktime"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                else -> null
            }
    }
}
