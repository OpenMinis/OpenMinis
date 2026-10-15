package com.openminis.app.ui.sandbox

/**
 * Post-copy verification for SAF / MediaStore saves.
 *
 * [T-android-save-as-null-stream] Two failure shapes used to surface to the
 * user as "已保存" + a 0-byte file in their storage:
 *
 * 1. `ContentResolver.openOutputStream(uri)` returned null and the call site
 *    used `?.use { copy }` — a no-op that still evaluated to success.
 * 2. The copy completed without throwing but the provider-side document
 *    stayed empty (stream accepted, bytes never landed).
 *
 * Call sites now read back the destination length (via
 * `openAssetFileDescriptor(uri, "r").length`) and consult this pure decision
 * core. Providers legitimately report UNKNOWN_LENGTH (-1) — that is accepted
 * rather than misjudged as failure.
 */
object FileSaveGuard {

    /** AssetFileDescriptor reports this when the length is unknown. */
    const val UNKNOWN_LENGTH = -1L

    /**
     * @param srcLen source file length in bytes (`File.length()`).
     * @param dstLen destination length after the copy, or [UNKNOWN_LENGTH].
     * @return true when the copy is consistent; false when a non-empty source
     *         demonstrably landed zero bytes.
     */
    fun copiedIntact(srcLen: Long, dstLen: Long): Boolean = when {
        dstLen == UNKNOWN_LENGTH -> true // cannot verify — do not misjudge
        srcLen <= 0L -> dstLen == 0L // empty source: an empty copy is correct
        else -> dstLen > 0L // non-empty source must land bytes
    }
}
