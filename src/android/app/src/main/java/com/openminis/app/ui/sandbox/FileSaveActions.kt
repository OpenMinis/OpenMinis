package com.openminis.app.ui.sandbox

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.openminis.app.io.VerifiedCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Save actions shared by FilePreviewScreen, FullscreenImageViewer and
 * ImageGalleryViewer.
 *
 * Every path funnels through [VerifiedCopy]: a null stream, a zero-byte copy,
 * an unreadable document, a failed `Bitmap.compress` and an unverifiable
 * provider are all failures — never a success toast. MediaStore rows stay
 * `IS_PENDING` until verification passes and are deleted otherwise.
 */

/** Result of a Save-As copy, with a loggable reason for the failure path. */
internal data class SaveAsOutcome(val ok: Boolean, val reason: String)

/**
 * Copies [src] into the SAF document the user just picked ([uri] — the
 * destination document already exists by the time this runs).
 */
internal suspend fun saveAsToDocumentUri(
    context: Context,
    src: File,
    uri: Uri,
): SaveAsOutcome = withContext(Dispatchers.IO) {
    val result = VerifiedCopy.copyFileToDocument(context.contentResolver, src, uri)
    SaveAsOutcome(result.ok, result.reason)
}

/**
 * Saves an image file from the rootfs to the user's gallery. Q+ uses
 * MediaStore (scoped storage); pre-Q writes the public Pictures dir. The
 * file is copied byte-for-byte so the original format (PNG/WebP/HEIC) is
 * preserved.
 */
internal suspend fun saveImageToGallery(context: Context, src: File): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
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
                }
                VerifiedCopy.copyFileToMediaStore(
                    context.contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                    src,
                ).ok
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES,
                )
                val dest = File(File(dir, "Minis Ultra").also { it.mkdirs() }, filename)
                VerifiedCopy.copyFileToFile(src, dest).ok
            }
        }.getOrDefault(false)
    }

/**
 * Saves an already-decoded [bitmap] (the fullscreen/gallery viewers own the
 * bitmap, not a rootfs file). `Bitmap.compress` returning false is a hard
 * failure; the destination row/file is only kept after a length or read-back
 * probe proves bytes landed.
 */
internal suspend fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val filename = "minis_${System.currentTimeMillis()}.png"
            val wrote: (java.io.OutputStream) -> Boolean = { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/Minis",
                    )
                }
                VerifiedCopy.writeToMediaStore(
                    context.contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                    VerifiedCopy.UNKNOWN_LENGTH,
                    write = wrote,
                ).ok
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES,
                )
                val dest = File(File(dir, "Minis Ultra").also { it.mkdirs() }, filename)
                VerifiedCopy.writeFileAtomically(dest, VerifiedCopy.UNKNOWN_LENGTH, wrote).ok
            }
        }.getOrDefault(false)
    }
