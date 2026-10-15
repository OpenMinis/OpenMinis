package com.openminis.app.ui.sandbox

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * Save actions shared by [FilePreviewScreen], extracted so the screen file
 * stays under the architecture-policy ceiling. Both entry points enforce
 * [FileSaveGuard]: a null stream or an empty copy is a FAILURE, never a
 * silent "saved" toast, and the husk (empty SAF document / pending
 * MediaStore row) is deleted so failed saves do not litter user storage
 * with 0-byte files. [T-android-save-as-null-stream]
 */

/** Result of a Save-As copy, with a loggable reason for the failure path. */
internal data class SaveAsOutcome(val ok: Boolean, val reason: String)

/**
 * Copies [src] into the SAF document the user just picked ([uri] — the
 * destination document already exists by the time this runs). Returns the
 * outcome; the caller owns toasting and deleting the husk on failure.
 */
internal suspend fun saveAsToDocumentUri(context: Context, src: File, uri: Uri): SaveAsOutcome =
    withContext(Dispatchers.IO) {
        val srcLen = src.length()
        var ok = false
        var reason = ""
        try {
            val out = context.contentResolver.openOutputStream(uri)
            if (out == null) {
                reason = "openOutputStream returned null"
            } else {
                out.use { o -> src.inputStream().use { it.copyTo(o) } }
                val dstLen = runCatching {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")
                        ?.use { it.length } ?: FileSaveGuard.UNKNOWN_LENGTH
                }.getOrDefault(FileSaveGuard.UNKNOWN_LENGTH)
                ok = FileSaveGuard.copiedIntact(srcLen, dstLen)
                if (!ok) reason = "empty after copy (src=$srcLen dst=$dstLen)"
            }
        } catch (e: Exception) {
            reason = e.message ?: e.javaClass.simpleName
        }
        if (!ok) runCatching { context.contentResolver.delete(uri, null, null) }
        SaveAsOutcome(ok, reason)
    }

/**
 * Saves an image file from the rootfs to the user's gallery. Q+ uses
 * MediaStore (scoped storage); pre-Q falls back to the public Pictures
 * dir. Mirrors FullscreenImageViewer.saveToGallery so the two save paths
 * stay consistent.
 */
internal suspend fun saveImageToGallery(context: Context, src: File): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val ext = src.extension.lowercase().ifEmpty { "png" }
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/png"
            val filename = "minis_${System.currentTimeMillis()}.$ext"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/Minis",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
                ) ?: return@withContext false
                // A null stream used to fall through `?.use` and the row was
                // still published (IS_PENDING=0) — a 0-byte gallery image.
                // On any failure the pending row is deleted instead.
                var copied = false
                try {
                    val out = context.contentResolver.openOutputStream(uri)
                    if (out == null) {
                        AppLogger.warning("FilePreview", "saveImageToGallery: openOutputStream returned null")
                    } else {
                        out.use { o: OutputStream -> src.inputStream().use { it.copyTo(o) } }
                        val dstLen = runCatching {
                            context.contentResolver.openAssetFileDescriptor(uri, "r")
                                ?.use { it.length } ?: FileSaveGuard.UNKNOWN_LENGTH
                        }.getOrDefault(FileSaveGuard.UNKNOWN_LENGTH)
                        copied = FileSaveGuard.copiedIntact(srcLen = src.length(), dstLen = dstLen)
                    }
                } finally {
                    if (copied) {
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        context.contentResolver.update(uri, values, null, null)
                    } else {
                        runCatching { context.contentResolver.delete(uri, null, null) }
                    }
                }
                if (!copied) return@withContext false
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES,
                )
                val minisDir = File(dir, "Minis Ultra").also { it.mkdirs() }
                val dest = File(minisDir, filename)
                src.inputStream().use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
            }
            true
        } catch (e: Exception) {
            AppLogger.warning("FilePreview", "saveImageToGallery failed: ${e.message}")
            false
        }
    }
