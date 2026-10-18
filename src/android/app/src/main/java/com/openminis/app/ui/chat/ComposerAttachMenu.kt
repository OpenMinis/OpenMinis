package com.openminis.app.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.logging.AppLogger
import com.openminis.app.R
import com.openminis.app.ui.components.MinisMenu

/**
 * The composer "+" menu: Take Photo / Choose Photos & Videos / Add File.
 *
 * [T-android-picker-unavailable] Split out of `ChatScreen` for two reasons:
 *
 *  1. Every entry point here called `launcher.launch(...)` unguarded. On a
 *     device with no camera app, no documents provider, or a disabled Photo
 *     Picker, `launch` throws `ActivityNotFoundException` and took the whole
 *     chat screen down — the other pickers in the app already report a message
 *     instead (`MainActivity`, `BrowserExternalSchemeHandler`,
 *     `OpenOffloadHandler`), so this menu was the one remaining crash path.
 *  2. `ChatScreen` is under a shrink-only line ceiling, so keeping the menu
 *     here is also what let the guards be added without inflating it.
 *
 * The three callbacks stay caller-owned: this composable only decides *when*
 * to fire them and how to fail gracefully when the platform can't.
 */
@Composable
internal fun ComposerAttachMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhotos: () -> Unit,
    onPickFile: () -> Unit,
) {
    val context = LocalContext.current
    MinisMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // iOS parity: Take Photo / Choose Photos & Videos / Add File
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_attach_take_photo)) },
            leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
            onClick = {
                onDismiss()
                launchPickerOrReport(context, "camera", onTakePhoto)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_attach_choose_photos_videos)) },
            leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
            onClick = {
                onDismiss()
                launchPickerOrReport(context, "photos", onPickPhotos)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_attach_add_file)) },
            leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
            onClick = {
                onDismiss()
                // OpenMultipleDocuments takes a mime-type array; "*/*" stays
                // the wildcard.
                launchPickerOrReport(context, "documents", onPickFile)
            },
        )
    }
}

/**
 * Run [launch], turning "no app can handle this" into a toast instead of a
 * process-level crash. Only [ActivityNotFoundException] is caught: it is the
 * documented signal for "nothing matched", and swallowing anything else would
 * hide real bugs in the picker wiring.
 */
private fun launchPickerOrReport(context: Context, label: String, launch: () -> Unit) {
    try {
        launch()
    } catch (e: ActivityNotFoundException) {
        AppLogger.warning(TAG, "no activity for $label picker: ${e.message}")
        Toast.makeText(context, R.string.picker_unavailable_toast, Toast.LENGTH_SHORT).show()
    }
}

private const val TAG = "ComposerAttach"
