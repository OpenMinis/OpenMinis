package com.openminis.app.backup

import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ZIP packaging for `.minisbak`, on both sides of the wire.
 *
 * ## Why STORED and not DEFLATE
 *
 * iOS reads packages with a hand-rolled parser
 * (`BackupZipExtractor.swift` / `BackupPackageReader.swift`) that streams
 * STORED entries in 4MB chunks but must inflate a DEFLATE entry **as a unit**,
 * into memory. A backup's largest members are already-compressed blobs
 * (media, zips) that deflate would barely shrink, so compressing them buys
 * almost nothing and hands the iOS importer a multi-hundred-MB allocation on a
 * device with a documented jetsam history. Everything is therefore written
 * STORED, which is also what `NSFileCoordinator(.forUploading)` produces on
 * the iOS side — one archive shape for both writers.
 *
 * STORED via [ZipOutputStream] requires size and CRC up front, which means one
 * hashing pass over each member before it is written. That pass is streaming,
 * so peak memory stays at the copy buffer regardless of member size.
 *
 * ZIP64 must NEVER be emitted, and that constraint is sharper than it looks.
 * iOS's reader takes the entry count from the classic EOCD's 16-bit field and
 * loops exactly that many times. In a ZIP64 archive that field is saturated to
 * 0xFFFF, so iOS does not fail — it extracts 65 535 members, skips the rest,
 * and reports success. Silent data loss on restore, which is the one outcome a
 * backup must never produce. `ZipOutputStream` switches to ZIP64 on its own
 * once any of three limits is crossed, so all three are checked up front:
 * per-member size, total archive size, and entry count.
 */
object BackupZip {

    private const val TAG = "Backup"

    /** Classic-ZIP ceiling for a member's size and for the archive's total. */
    private const val MAX_MEMBER_BYTES = 0xFFFFFFFFL - 1

    /**
     * Classic EOCD stores the entry count in 16 bits. One more than this and
     * ZipOutputStream emits a ZIP64 record that iOS silently under-reads.
     */
    private const val MAX_ENTRIES = 65_535

    /**
     * [T-android-restore-gc-storm] Shared copy-buffer size. 256KB stays under
     * ART's Large Object Space threshold's practical impact zone while still
     * amortising syscalls; the buffer is allocated ONCE per archive/extract
     * call and reused across members, so the size no longer multiplies by the
     * package's file count.
     */
    private const val COPY_BUFFER_BYTES = 256 * 1024

    /**
     * [T-android-zip-store-by-content] How much of a member to sniff.
     *
     * Bounded on purpose: the probe exists to avoid a full deflate pass, so it
     * must not scale with the member. 64 KB is enough for both the magic check
     * and a representative entropy sample.
     */
    private const val PROBE_BYTES = 64 * 1024

    /**
     * Largest member that may be DEFLATED.
     *
     * Not a compression judgement — a memory one. iOS's reader inflates a
     * DEFLATE member as a single in-memory unit, so the cap is what keeps a
     * huge member from becoming one huge allocation on the restoring device.
     * 32 MB comfortably covers the jsonl families this change exists for while
     * leaving every large blob on the STORED path it was already using.
     */
    private const val MAX_DEFLATE_BYTES = 32L * 1024 * 1024

    /**
     * Leading bytes of formats that already carry their own compression.
     * Checked before the entropy probe because they are exact.
     */
    private val INCOMPRESSIBLE_MAGIC: List<ByteArray> = listOf(
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),                 // JPEG
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),                             // PNG
        byteArrayOf(0x47, 0x49, 0x46, 0x38),                                      // GIF8
        byteArrayOf(0x52, 0x49, 0x46, 0x46),                                      // RIFF (WebP/WAV/AVI)
        byteArrayOf(0x50, 0x4B, 0x03, 0x04),                                      // ZIP
        byteArrayOf(0x25, 0x50, 0x44, 0x46),                                      // %PDF
        byteArrayOf(0x49, 0x44, 0x33),                                            // MP3 w/ ID3
        byteArrayOf(0xFF.toByte(), 0xFB.toByte()),                                // MP3 frame
        byteArrayOf(0x4F, 0x67, 0x67, 0x53),                                      // OggS
        byteArrayOf(0x66, 0x4C, 0x61, 0x43),                                      // FLAC
        byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()),                    // Matroska/WebM
        byteArrayOf(0x42, 0x5A, 0x68),                                            // BZh
        byteArrayOf(0x1F, 0x8B.toByte()),                                         // gzip
        byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A),                       // xz
        byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C),        // 7z
        byteArrayOf(0x52, 0x61, 0x72, 0x21),                                      // Rar!
    )

    class ZipException(message: String) : Exception(message)

    // MARK: - Writing

    /**
     * Archive every file under [staging] into [destination], STORED, with
     * package-relative entry names.
     *
     * Entry order follows a directory walk; the manifest is written last by
     * the exporter, so it lands near the end of the archive exactly as it does
     * on iOS (which is why a duplicate copy exists for rescue).
     */
    fun archive(staging: File, destination: File) {
        val base = staging.canonicalFile
        val members = base.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(base).invariantPath() }
            .toList()

        // Refuse before writing anything, rather than discovering it at the
        // end: ZipOutputStream would quietly promote the archive to ZIP64, and
        // the resulting package looks perfectly valid right up until iOS reads
        // 65 535 of its members and calls the restore a success.
        if (members.size > MAX_ENTRIES) {
            throw ZipException(
                "Package would hold ${members.size} files, beyond the ${MAX_ENTRIES}-entry " +
                    "limit of the cross-platform ZIP format."
            )
        }
        val totalBytes = members.sumOf { it.length() }
        if (totalBytes > MAX_MEMBER_BYTES) {
            throw ZipException(
                "Package would be $totalBytes bytes, beyond the 4GB limit of the " +
                    "cross-platform ZIP format."
            )
        }

        ZipOutputStream(destination.outputStream().buffered()).use { zos ->
            // Per-entry method; see [shouldStore]. The stream-level default
            // stays STORED so an entry that does not set one explicitly keeps
            // the old behaviour.
            zos.setMethod(ZipOutputStream.STORED)
            // [T-android-restore-gc-storm] ONE copy buffer for the whole
            // archive. Allocating per member (as `copyTo(…, 4MB)` does — a
            // fresh buffer every call) put every allocation in the Large
            // Object Space, and a package with thousands of blobs churned
            // gigabytes of cumulative LOS. The GC log showed it plainly:
            // every collection was "N (N×4MB) LOS objects" with almost
            // nothing actually freed, and allocator threads stalling in
            // "Waiting for a blocking GC Alloc" often enough to jank the
            // main thread into an ANR kill on a Pixel 6.
            val buf = ByteArray(COPY_BUFFER_BYTES)
            members.forEach { file ->
                writeEntry(zos, file.relativeTo(base).invariantPath(), file, buf)
            }
        }
    }

    /**
     * [T-android-zip-store-by-content] Write one member, choosing STORED or
     * DEFLATED from what the member actually contains.
     *
     * Every entry used to be STORED unconditionally. That is right for the
     * media blobs — they carry their own compression, and deflating them costs
     * CPU on the way in and again on every restore — but it was also being
     * applied to the `data/` jsonl members, which are the bulk of a text-heavy
     * package and compress to roughly a sixth of their size. On a measured
     * Android package `data/messages.jsonl` alone was 7.11 MB of 10.4 MB,
     * stored raw, and every byte is re-uploaded on every backup.
     *
     * The mirror of iOS `BackupZipWriter.shouldStore` (1fbcf5886), which had
     * the opposite half of the same bug: it decided from the filename
     * extension, and blobs are content-addressed with no extension, so it
     * deflated 22,845 of 23,206 entries including all the photos and video.
     * One writer stored everything, the other deflated everything; both now
     * decide from content.
     */
    private fun writeEntry(zos: ZipOutputStream, name: String, file: File, buf: ByteArray) {
        val size = file.length()
        if (size > MAX_MEMBER_BYTES) {
            throw ZipException(
                "Package member '$name' is ${size} bytes, beyond the 4GB classic-ZIP limit."
            )
        }
        // A DEFLATE entry forces the iOS reader to inflate the WHOLE member
        // into one allocation (`compression_decode_buffer`,
        // BackupZipExtractor.swift:364) — an app with a jetsam history cannot
        // hand it a multi-hundred-MB member. So size gates the decision before
        // content does: anything large is stored no matter how well it would
        // compress. The members that motivated this change are the jsonl
        // families, which are megabytes, not hundreds of them.
        val deflatable = size <= MAX_DEFLATE_BYTES && !shouldStore(file, buf)
        if (deflatable) writeDeflatedEntry(zos, name, file, buf)
        else writeStoredEntry(zos, name, file, size, buf)
    }

    /**
     * True when the member will not meaningfully shrink, so deflating it would
     * only burn CPU here and again on every restore.
     *
     * Magic first — exact, and it covers the blobs that matter. The entropy
     * probe runs only when the magic says nothing, and reads a bounded sample
     * rather than the whole file, so the check cannot itself become the cost
     * it exists to avoid.
     */
    internal fun shouldStore(file: File, buf: ByteArray): Boolean {
        val head = ByteArray(minOf(PROBE_BYTES.toLong(), file.length()).toInt())
        if (head.isEmpty()) return true // Nothing to compress.
        file.inputStream().use { input ->
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            if (read < head.size) return shouldStoreBytes(head.copyOf(read))
        }
        return shouldStoreBytes(head)
    }

    /** Split out so a test can drive the decision without touching disk. */
    internal fun shouldStoreBytes(head: ByteArray): Boolean {
        if (head.isEmpty()) return true
        for (magic in INCOMPRESSIBLE_MAGIC) {
            if (head.size >= magic.size && magic.indices.all { head[it] == magic[it] }) return true
        }
        // ISO base media (MP4 / MOV / M4A / HEIC / AVIF): "ftyp" at byte 4,
        // not 0, so it cannot be expressed as a leading-bytes match.
        if (head.size >= 8 && head[4] == 0x66.toByte() && head[5] == 0x74.toByte() &&
            head[6] == 0x79.toByte() && head[7] == 0x70.toByte()
        ) {
            return true
        }
        // Deflate the sample and see whether it was worth it. Under a 5% gain
        // the pass does not pay for itself — here, or on the restore that has
        // to undo it.
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(head)
            deflater.finish()
            val out = ByteArray(head.size + 64)
            var total = 0
            while (!deflater.finished() && total < out.size) {
                val n = deflater.deflate(out, total, out.size - total)
                if (n == 0) break
                total += n
            }
            // Did not finish inside the bound → it is not shrinking. Store it.
            if (!deflater.finished()) return true
            return total > (head.size * 95) / 100
        } finally {
            deflater.end()
        }
    }

    private fun writeStoredEntry(
        zos: ZipOutputStream,
        name: String,
        file: File,
        size: Long,
        buf: ByteArray,
    ) {
        // STORED demands size and CRC up front, which is why this path reads
        // the member twice. DEFLATED does not, so it reads once.
        val crc = CRC32()
        file.inputStream().buffered().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            this.size = size
            compressedSize = size
            this.crc = crc.value
            time = file.lastModified()
        }
        zos.putNextEntry(entry)
        file.inputStream().buffered().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                zos.write(buf, 0, n)
            }
        }
        zos.closeEntry()
    }

    private fun writeDeflatedEntry(zos: ZipOutputStream, name: String, file: File, buf: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.DEFLATED
            time = file.lastModified()
        }
        zos.putNextEntry(entry)
        file.inputStream().buffered().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                zos.write(buf, 0, n)
            }
        }
        zos.closeEntry()
    }

    // MARK: - Reading

    /**
     * Extract every entry of [zipFile] under [destination].
     *
     * Uses a forward [ZipInputStream] scan rather than random access, so a
     * package whose central directory is damaged still yields whatever
     * precedes the damage — the same tolerance iOS's forward-scan rescue path
     * provides.
     */
    fun extract(zipFile: File, destination: File, onProgress: ((String) -> Unit)? = null) {
        destination.mkdirs()
        val root = destination.canonicalFile
        // [T-android-restore-gc-storm] One buffer for the whole archive, not
        // one per entry — see `archive` for the GC storm the per-entry
        // allocation caused during a large restore.
        val buf = ByteArray(COPY_BUFFER_BYTES)
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = safeResolve(root, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered().use { sink ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                        }
                    }
                    onProgress?.invoke(entry.name)
                }
                zis.closeEntry()
            }
        }
    }

    /**
     * Read one small entry by name, without extracting the archive.
     *
     * Names are matched on suffix as well as equality: iOS packages the
     * staging tree through `NSFileCoordinator(.forUploading)`, which wraps
     * everything in an outer folder, so entries arrive as
     * `minisbak-<uuid>/manifest.json` rather than bare `manifest.json`.
     */
    fun readEntry(zipFile: File, name: String, maxBytes: Int = 32 * 1024 * 1024): ByteArray? {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name == name || entry.name.endsWith("/$name")) {
                    return zis.readAtMost(maxBytes)
                }
                zis.closeEntry()
            }
        }
        return null
    }

    /** Entry names in the archive, in stream order. */
    fun listEntries(zipFile: File): List<String> {
        val out = mutableListOf<String>()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                out.add(entry.name)
                zis.closeEntry()
            }
        }
        return out
    }

    /**
     * Strip the outer wrapper directory iOS's zipper adds, if there is one.
     *
     * After extraction the real package root may be `<dest>/minisbak-<uuid>/`
     * rather than `<dest>/`. Detected by looking for `manifest.json`, which
     * every package has at its root by definition.
     */
    fun packageRoot(extracted: File): File {
        if (File(extracted, "manifest.json").exists()) return extracted
        val dirs = extracted.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val wrapped = dirs.firstOrNull { File(it, "manifest.json").exists() }
        if (wrapped != null) {
            AppLogger.info(TAG, "[Backup] package root is wrapped in '${wrapped.name}'")
            return wrapped
        }
        return extracted
    }

    /**
     * §5.5's path-traversal rule: a malicious package must not write outside
     * the destination. Absolute paths and any `..` component are refused
     * rather than normalised away.
     */
    private fun safeResolve(root: File, entryName: String): File {
        if (entryName.startsWith("/") || entryName.startsWith("\\")) {
            throw ZipException("Refusing unsafe entry path: $entryName")
        }
        val parts = entryName.split('/', '\\').filter { it.isNotEmpty() }
        if (parts.any { it == ".." }) {
            throw ZipException("Refusing unsafe entry path: $entryName")
        }
        val resolved = parts.fold(root) { acc, part -> File(acc, part) }
        // Belt and braces: a symlink or exotic name must not escape either.
        if (!resolved.canonicalPath.startsWith(root.canonicalPath + File.separator) &&
            resolved.canonicalPath != root.canonicalPath
        ) {
            throw ZipException("Refusing unsafe entry path: $entryName")
        }
        return resolved
    }

    private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')

    private fun InputStream.readAtMost(max: Int): ByteArray {
        val buf = ByteArray(minOf(max, 64 * 1024))
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (total < max) {
            val n = read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}

// The `copyTo(out, bufferSize)` helper that used to live here is deliberately
// gone: it allocated its buffer per call, which is exactly the per-member
// allocation storm [T-android-restore-gc-storm] removed. Callers now thread
// one reusable buffer through the whole archive/extract operation.
