package com.openminis.app.io

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * The single copy-then-verify primitive for every user-visible save path:
 * SAF documents, MediaStore rows (gallery / crash-zip Downloads) and pre-Q
 * public directories.
 *
 * Why one primitive: before this, `FilePreviewScreen`, `FullscreenImageViewer`,
 * `ImageGalleryViewer`, `CrashFrequencyDetector` and `MainActivity` each had
 * their own copy loop, and each one decided "success" differently. Two of them
 * published a MediaStore row before verifying (`IS_PENDING = 0`), one treated
 * `-1` as success, and one ignored `Bitmap.compress`'s return value. A save
 * that reports success while the destination is empty is worse than a failed
 * save, because the user stops looking.
 *
 * Contract:
 *  - `VERIFIED`   — length verified, or the destination had no measurable
 *                   length and a read-back probe found bytes (empty sources
 *                   are probed for EOF).
 *  - `UNVERIFIED` — bytes may have landed but could not be proven. Callers
 *                   must NOT show a success toast; they may keep the file
 *                   (SAF) or must delete the unpublished row (MediaStore).
 *  - `FAILED`     — the copy threw, wrote zero bytes, or the writer refused.
 *
 * All functions are blocking; call them from an IO dispatcher.
 */
object VerifiedCopy {

    /** `AssetFileDescriptor.UNKNOWN_LENGTH`. */
    const val UNKNOWN_LENGTH = FileSaveGuard.UNKNOWN_LENGTH

    private const val PROBE_BYTES = 4096

    enum class Status { VERIFIED, UNVERIFIED, FAILED }

    data class Result(
        val status: Status,
        val reason: String = "",
        val uri: Uri? = null,
        val dstLen: Long = UNKNOWN_LENGTH,
    ) {
        val ok: Boolean get() = status == Status.VERIFIED
    }

    /** Length-only mapping; never returns `VERIFIED` for an unknown length. */
    fun statusForLengths(expectedLen: Long, actualLen: Long): Status =
        when (FileSaveGuard.verify(expectedLen, actualLen)) {
            FileSaveGuard.Verdict.INTACT -> Status.VERIFIED
            FileSaveGuard.Verdict.EMPTY -> Status.FAILED
            FileSaveGuard.Verdict.UNKNOWN -> Status.UNVERIFIED
        }

    /**
     * Verify a document/MediaStore URI after writing.
     *
     * @param exact when true, a known destination length must equal
     *        [expectedLen] (used for crash zips and pre-Q files, where the
     *        target is a regular file we fully control). SAF providers only
     *        get the emptiness class check because they may report a
     *        transcoded/rounded length.
     */
    fun verifyDocument(
        resolver: ContentResolver,
        uri: Uri,
        expectedLen: Long,
        exact: Boolean = false,
    ): Result {
        // A negative expected length means "non-empty is enough" (a rendered
        // bitmap has no source length). It must not go through the
        // empty-source branch of FileSaveGuard.
        val expectedUnknown = expectedLen < 0L
        val actual = documentLength(resolver, uri)
        if (actual != UNKNOWN_LENGTH) {
            if (expectedUnknown) {
                if (actual > 0L) {
                    return Result(Status.VERIFIED, "wrote $actual bytes", uri, actual)
                }
                // MediaStore can report 0 for a pending row until it is
                // published. Read the content back before declaring failure.
                val probe = probeBytes(resolver, uri)
                return if (probe != null && probe > 0) {
                    Result(Status.VERIFIED, "size reported 0, probe=$probe bytes", uri, probe.toLong())
                } else {
                    Result(Status.FAILED, "writer produced 0 readable bytes", uri, actual)
                }
            }
            var verdict = FileSaveGuard.verify(expectedLen, actual)
            if (exact && expectedLen >= 0L && actual != expectedLen) {
                verdict = FileSaveGuard.Verdict.EMPTY
            }
            // Some MediaStore providers report 0 until the row is published;
            // a readable byte proves the copy landed. Exact mode skips this
            // for a partial length but still probes a reported 0 because that
            // is the pending-row shape.
            if (verdict == FileSaveGuard.Verdict.EMPTY && expectedLen > 0L &&
                (!exact || actual == 0L)
            ) {
                val probe = probeBytes(resolver, uri)
                if (probe != null && probe > 0) {
                    return Result(
                        Status.VERIFIED,
                        "len reported 0 but probe=$probe bytes",
                        uri,
                        probe.toLong(),
                    )
                }
            }
            return Result(verdict.toStatus(), "len expected=$expectedLen actual=$actual", uri, actual)
        }
        val probe = probeBytes(resolver, uri)
            ?: return Result(
                Status.UNVERIFIED,
                "length unknown and content unreadable (expected=$expectedLen)",
                uri,
            )
        if (expectedUnknown) {
            return if (probe > 0) {
                Result(Status.VERIFIED, "len unknown, probe=$probe bytes", uri, probe.toLong())
            } else {
                Result(Status.UNVERIFIED, "len unknown and probe empty", uri, probe.toLong())
            }
        }
        val verdict = FileSaveGuard.resolve(expectedLen, UNKNOWN_LENGTH, probe > 0)
        val detail = "len unknown, probe=$probe bytes (expected=$expectedLen)"
        return when (verdict) {
            FileSaveGuard.Verdict.INTACT -> Result(Status.VERIFIED, detail, uri, probe.toLong())
            FileSaveGuard.Verdict.EMPTY -> Result(Status.FAILED, detail, uri, probe.toLong())
            // Probe returned EOF but could not prove emptiness (e.g. a provider
            // still flushing). Never report success on this path.
            FileSaveGuard.Verdict.UNKNOWN -> Result(Status.UNVERIFIED, detail, uri, probe.toLong())
        }
    }

    /**
     * Copy [src] into an already-created SAF document. Deletes the document on
     * a hard failure (the historical "0-byte husk" behaviour), but leaves it
     * in place when the result is merely [Status.UNVERIFIED]: deleting a file
     * the user may be able to open is worse than telling them we could not
     * confirm it.
     */
    fun copyFileToDocument(
        resolver: ContentResolver,
        src: File,
        uri: Uri,
        exact: Boolean = false,
    ): Result {
        val expected = src.length()
        val out = try {
            resolver.openOutputStream(uri)
        } catch (e: Exception) {
            null
        } ?: return Result(Status.FAILED, "openOutputStream returned null", uri)
        try {
            out.use { o -> src.inputStream().use { input -> input.copyTo(o) } }
        } catch (e: Exception) {
            deleteQuietly(resolver, uri)
            return Result(Status.FAILED, "copy failed: ${e.message ?: e.javaClass.simpleName}", uri)
        }
        val result = verifyDocument(resolver, uri, expected, exact)
        if (result.status == Status.FAILED) deleteQuietly(resolver, uri)
        return result
    }

    /**
     * Copy [src] to a regular file via a sidecar `.part` and an atomic rename.
     * Used by pre-Q public-directory saves and the crash-zip fallback.
     */
    fun copyFileToFile(src: File, dest: File): Result {
        val expected = src.length()
        dest.parentFile?.mkdirs()
        return try {
            writeFileAtomically(dest, expected) { out ->
                src.inputStream().use { input -> input.copyTo(out) }
                true
            }
        } catch (e: Exception) {
            Result(Status.FAILED, "copy failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Write [dest] through a `.part` sidecar and rename only after the length
     * check passes. [expectedLen] `-1` means "unknown": require a non-empty
     * result. The writer returns false to refuse (e.g. `Bitmap.compress`).
     */
    fun writeFileAtomically(
        dest: File,
        expectedLen: Long,
        write: (OutputStream) -> Boolean,
    ): Result {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, ".${dest.name}.${UUID.randomUUID()}.part")
        return try {
            var wrote = false
            tmp.outputStream().use { out ->
                wrote = write(out)
                out.flush()
            }
            if (!wrote) {
                tmp.delete()
                return Result(Status.FAILED, "writer returned false")
            }
            val actual = tmp.length()
            val verdict = if (expectedLen >= 0L) {
                FileSaveGuard.verify(expectedLen, actual)
            } else if (actual > 0L) {
                FileSaveGuard.Verdict.INTACT
            } else {
                FileSaveGuard.Verdict.EMPTY
            }
            if (verdict != FileSaveGuard.Verdict.INTACT) {
                tmp.delete()
                return Result(
                    if (verdict == FileSaveGuard.Verdict.UNKNOWN) Status.UNVERIFIED else Status.FAILED,
                    "len expected=$expectedLen actual=$actual",
                    dstLen = actual,
                )
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return Result(Status.FAILED, "rename to ${dest.name} failed", dstLen = actual)
            }
            Result(Status.VERIFIED, "", dstLen = actual)
        } catch (e: Exception) {
            tmp.delete()
            Result(Status.FAILED, "write failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Insert a MediaStore row as pending, write, verify, and only then publish
     * (`IS_PENDING = 0`). A failed or unprovable write deletes the pending row
     * so no empty media item ever reaches the gallery/Downloads.
     */
    fun writeToMediaStore(
        resolver: ContentResolver,
        collection: Uri,
        values: ContentValues,
        expectedLen: Long,
        exact: Boolean = false,
        write: (OutputStream) -> Boolean,
    ): Result {
        val insertValues = ContentValues(values)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insertValues.put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(collection, insertValues)
        } catch (e: Exception) {
            null
        } ?: return Result(Status.FAILED, "MediaStore insert returned null")
        var wrote = false
        try {
            val out = try {
                resolver.openOutputStream(uri)
            } catch (e: Exception) {
                null
            }
            if (out == null) {
                deleteQuietly(resolver, uri)
                return Result(Status.FAILED, "openOutputStream returned null", uri)
            }
            out.use { o ->
                wrote = write(o)
                o.flush()
            }
        } catch (e: Exception) {
            deleteQuietly(resolver, uri)
            return Result(Status.FAILED, "write failed: ${e.message ?: e.javaClass.simpleName}", uri)
        }
        if (!wrote) {
            deleteQuietly(resolver, uri)
            return Result(Status.FAILED, "writer returned false", uri)
        }
        val result = verifyDocument(resolver, uri, expectedLen, exact)
        if (result.status != Status.VERIFIED) {
            // Never publish a row we could not prove. UNVERIFIED is treated as
            // a failure here because the row has no user value until verified.
            deleteQuietly(resolver, uri)
            return result.copy(
                status = if (result.status == Status.UNVERIFIED) Status.UNVERIFIED else Status.FAILED,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val publish = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                val updated = resolver.update(uri, publish, null, null)
                if (updated <= 0) {
                    // The row stayed pending: it is invisible to the user, so
                    // reporting success would be a lie. Drop it.
                    deleteQuietly(resolver, uri)
                    return Result(Status.FAILED, "publish updated 0 rows", uri)
                }
            } catch (e: Exception) {
                deleteQuietly(resolver, uri)
                return Result(Status.FAILED, "publish failed: ${e.message}", uri)
            }
        }
        return result.copy(uri = uri)
    }

    /** Insert + file copy + verify + publish; shared by gallery and crash zip. */
    fun copyFileToMediaStore(
        resolver: ContentResolver,
        collection: Uri,
        values: ContentValues,
        src: File,
    ): Result = writeToMediaStore(resolver, collection, values, src.length(), exact = true) { out ->
        src.inputStream().use { input -> input.copyTo(out) }
        true
    }

    private fun documentLength(resolver: ContentResolver, uri: Uri): Long = try {
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: UNKNOWN_LENGTH
    } catch (_: Exception) {
        UNKNOWN_LENGTH
    }

    /** Bytes readable from the destination, or null when the probe failed. */
    private fun probeBytes(resolver: ContentResolver, uri: Uri): Int? = try {
        val input: InputStream = resolver.openInputStream(uri) ?: return null
        input.use {
            val n = it.read(ByteArray(PROBE_BYTES))
            if (n < 0) 0 else n
        }
    } catch (_: Exception) {
        null
    }

    private fun deleteQuietly(resolver: ContentResolver, uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (_: Exception) {
            // Best effort: the row may already be gone or the provider may
            // refuse deletes. Never let cleanup mask the original failure.
        }
    }

    private fun FileSaveGuard.Verdict.toStatus(): Status = when (this) {
        FileSaveGuard.Verdict.INTACT -> Status.VERIFIED
        FileSaveGuard.Verdict.EMPTY -> Status.FAILED
        FileSaveGuard.Verdict.UNKNOWN -> Status.UNVERIFIED
    }
}
