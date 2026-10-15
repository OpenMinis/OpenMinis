package com.openminis.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.openminis.app.ui.sandbox.FileBrowserViewModel
import com.openminis.app.ui.sandbox.FileItem
import java.io.File

/**
 * Navigation plumbing for the file-preview destinations, extracted from
 * AppNavigation so that file stays under the architecture-policy ceiling.
 * Three concerns live here: the process-static hand-off holder, the
 * percent-encoding recipe shared by all path-carrying routes, and the
 * process-death rehydration of the FILE_PREVIEW destination.
 */

/**
 * Holder for file preview navigation state (not serializable via nav args).
 *
 * [T-android-filepreview-holder-lifetime] Both slots are released by the
 * destination that consumes them (see the DisposableEffect in FILE_BROWSER /
 * FILE_PREVIEW): this object is process-static and the browser VM it carries is
 * hand-built by the navigating screen — it is attached to no ViewModelStore, so
 * onCleared() never runs and without the clear the last browsed
 * FileBrowserViewModel (plus its FileItem and every listed FileItem) lived for
 * the rest of the process. The CHAT_FILES destination builds its VM with
 * remember() and had a different lifetime for the same class; clearing on
 * dispose makes the two converge on "only while the destination is on the
 * stack".
 */
internal object FilePreviewHolder {
    var currentItem: FileItem? = null
    var fileBrowserViewModel: FileBrowserViewModel? = null
}

/**
 * Percent-encoding recipe for path/query values carried in nav routes.
 * URLEncoder is plain-JVM (unit-testable without Robolectric), and `+` must
 * become `%20` because Navigation percent-decodes query params — a bare `+`
 * would reach the screen as a literal plus (PR6 media-path bug class).
 */
internal fun navEnc(v: String): String =
    java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")

/**
 * Resolves the item to preview for the FILE_PREVIEW destination.
 * [T-android-filepreview-nav-args]
 *
 * Holder first (hot path — the navigating screen just statted this file),
 * nav-arg rehydration second (process-death restore: the Holder is empty, the
 * back stack entry still carries the path). A path that no longer resolves
 * (file deleted while the app was dead) pops the preview instead of parking
 * the user on a blank screen.
 */
@Composable
internal fun rememberFilePreviewItem(
    navController: NavHostController,
    entry: NavBackStackEntry,
): FileItem? {
    val item = FilePreviewHolder.currentItem
        ?: entry.arguments?.getString("path")?.takeIf { it.isNotEmpty() }
            ?.let { FileItem.from(File(it)) }
    if (item == null) {
        LaunchedEffect(Unit) { navController.safePopBackStack() }
    }
    return item
}
