package com.openminis.app.ui.media

import android.content.Context
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts

/**
 * AndroidX Photo Picker (`PickVisualMedia`) on API 30–32 — and some OEM
 * Android 13 builds (Flyme/Meizu) — often presents a blank white activity
 * instead of the gallery. DocumentsUI (`OpenDocument` / `GetContent`) is
 * the reliable fallback on those devices.
 */
object VisualMediaPickers {
    val IMAGE_AND_VIDEO_MIME: Array<String> = arrayOf("image/*", "video/*")
    const val IMAGE_MIME: String = "image/*"

    fun isReliablePhotoPicker(context: Context): Boolean =
        isReliablePhotoPicker(
            sdkInt = Build.VERSION.SDK_INT,
            photoPickerAvailable = ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context),
        )

    /** Test seam — production calls [isReliablePhotoPicker] with live SDK / GMS. */
    fun isReliablePhotoPicker(sdkInt: Int, photoPickerAvailable: Boolean): Boolean =
        sdkInt >= 33 && photoPickerAvailable
}
