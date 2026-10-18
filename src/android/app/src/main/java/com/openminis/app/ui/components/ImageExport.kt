package com.openminis.app.ui.components

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

private const val EXPORT_TAG = "MinisImageExport"

/**
 * [T-android-image-save-original] Save/open the ORIGINAL image bytes instead of
 * a re-encode.
 *
 * The viewer used to implement Save as `loadBitmap` + `Bitmap.compress(PNG)`.
 * That is lossless in pixels but wrong in practice: a 3 MB JPEG became a 20 MB
 * PNG, EXIF (orientation, capture time, GPS) was dropped, and an animated GIF
 * or HEIC came out as a single still frame. Upstream (`saveImageToGallery` /
 * `loadOriginalImage`) keeps the source bytes whenever they are readable and
 * only falls back to a full-size PNG when they are not — this file is that
 * behaviour, kept separate from the viewer so both Save and View-original share
 * one loader.
 */

/** Original bytes behind a viewer model, with the format needed to re-save them. */
internal class OriginalImage(val bytes: ByteArray, val mime: String, val extension: String)

/**
 * The original bytes behind [model], or null when unreadable.
 *
 * Accepts everything the viewer accepts: a [File], a [Uri] (`content:` /
 * `file:`), or a String (`content:` / `file:` / `http(s):`). Never throws — a
 * failed read is a null, and the caller falls back to a re-encode.
 */
internal suspend fun loadOriginalImage(context: Context, model: Any): OriginalImage? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes: ByteArray = when (model) {
                is File -> model.takeIf { it.isFile }?.readBytes()
                is Uri -> readUri(context, model)
                is String -> when {
                    model.startsWith("http://") || model.startsWith("https://") ->
                        java.net.URL(model).openStream().use { it.readBytes() }
                    model.startsWith("content://") || model.startsWith("file://") ->
                        readUri(context, Uri.parse(model))
                    else -> File(model).takeIf { it.isFile }?.readBytes()
                }
                else -> null
            } ?: return@runCatching null

            // Sniff the format from the bytes themselves: a `content://` URI often
            // reports no type, and the extension decides the MIME we hand to
            // MediaStore (which rejects a mismatch).
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val mime = opts.outMimeType?.lowercase() ?: "image/jpeg"
            OriginalImage(bytes, mime, extensionFor(mime))
        }.onFailure { Log.w(EXPORT_TAG, "original unreadable: ${it.message}") }
            .getOrNull()
    }

/**
 * Save [model] to the system gallery.
 *
 * Prefers the original bytes (correct format, metadata intact, no re-encode);
 * falls back to a full-size PNG decode when the source cannot be read as bytes
 * (e.g. an SVG or a transform-only drawable).
 */
internal suspend fun saveImageToGallery(context: Context, model: Any): Boolean {
    loadOriginalImage(context, model)?.let { return saveBytesToGallery(context, it) }
    val bmp = loadBitmap(context, model) ?: return false
    Log.w(EXPORT_TAG, "original unavailable — saving ${bmp.width}x${bmp.height} PNG")
    return saveToGallery(context, bmp)
}

private fun readUri(context: Context, uri: Uri): ByteArray? =
    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

private fun extensionFor(mime: String): String = when (mime) {
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "image/heic", "image/heif" -> "heic"
    "image/bmp" -> "bmp"
    else -> "jpg"
}

private suspend fun saveBytesToGallery(context: Context, original: OriginalImage): Boolean =
    withContext(Dispatchers.IO) {
        val filename = "minis_${System.currentTimeMillis()}.${original.extension}"
        writeToGallery(context, filename, original.mime) { it.write(original.bytes) }
    }

/**
 * Save an already-decoded bitmap as PNG. Kept for callers that only have pixels
 * (e.g. a transformed preview) and for the fallback path above.
 */
internal suspend fun saveToGallery(context: Context, bitmap: Bitmap): Boolean =
    withContext(Dispatchers.IO) {
        val filename = "minis_${System.currentTimeMillis()}.png"
        writeToGallery(context, filename, "image/png") {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

/**
 * Insert one row into the gallery and stream [write] into it.
 *
 * API 29+ goes through MediaStore with `RELATIVE_PATH` (scoped storage: writing
 * the public Pictures directory directly throws on 10+). Older releases write
 * the file and register it with a media scan, which is the only mechanism they
 * have.
 */
private fun writeToGallery(
    context: Context,
    filename: String,
    mime: String,
    write: (OutputStream) -> Unit,
): Boolean {
    return try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Minis")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Log.w(EXPORT_TAG, "gallery insert returned null")
            return false
        }
        resolver.openOutputStream(uri)?.use(write) ?: run {
            resolver.delete(uri, null, null)
            return false
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } else {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val minisDir = File(dir, "Minis").apply { mkdirs() }
        val file = File(minisDir, filename)
        file.outputStream().use(write)
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(mime),
            null,
        )
        true
    }
    } catch (e: Exception) {
        Log.w(EXPORT_TAG, "gallery save failed: ${e.message}")
        false
    }
}
