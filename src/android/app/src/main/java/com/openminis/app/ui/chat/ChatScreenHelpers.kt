package com.openminis.app.ui.chat

// [T-android-split-chat] Camera/file URI helpers + PendingNonTextSelection model
// extracted verbatim from ChatScreen.kt. Imports trimmed after ChatScreen.kt split.
// (unused=warnings); all internal (used by the ChatScreen composable).

import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * [P0-6] Whether the chat top bar has room for the three high-frequency
 * quick actions (terminal / browser / chat files) as direct icons beside
 * the "..." menu. Width ≥ 600dp (MEDIUM) — below that the centered
 * two-line title (session title + provider·model row) needs the horizontal
 * budget, and the menu's 快捷 section carries the entries instead.
 *
 * Same window-size source as `shouldUseTwoPane()` in ChatSplitScaffold
 * (currentWindowAdaptiveInfo, NOT LocalConfiguration.screenWidthDp — the
 * latter measures the display, so in split-screen/freeform windows the
 * predicate would answer for a window the app does not have).
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun shouldShowTopBarQuickActions(): Boolean =
    currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
    )

/**
 * Create an empty JPEG file under filesDir/camera-photos/ and return a
 * FileProvider content URI for it, plus the underlying File. The camera
 * app writes the captured image into this URI synchronously.
 *
 * Bug 1 (MIUI): the previous version put the staging file under cacheDir,
 * which MIUI may clear while the camera Activity is in flight. Moving to
 * filesDir keeps the URI valid until we explicitly delete it.
 */
internal fun createCameraOutputUri(context: android.content.Context): Pair<Uri, java.io.File> {
    val dir = java.io.File(context.filesDir, "camera-photos").also { it.mkdirs() }
    val ts = System.currentTimeMillis()
    val file = java.io.File(dir, "photo-$ts.jpg")
    file.createNewFile()
    val authority = context.packageName + ".fileprovider"
    val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
    return uri to file
}

internal fun getFileName(context: android.content.Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
    return cursor.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) it.getString(nameIndex) else null
        } else null
    }
}

// ─── Model Picker Bottom Sheet ──────────────────────────────────────────────

/**
 * Captures a tap on a model whose output modality (image/audio/video) makes
 * it unsuitable for driving an Agent task, so the picker can show a
 * confirmation dialog before applying the binding.
 */
internal sealed class PendingNonTextSelection {
    abstract val modelDisplayName: String
    abstract val modalityLabel: String

    data class Group(
        val groupId: String,
        override val modelDisplayName: String,
        override val modalityLabel: String,
    ) : PendingNonTextSelection()

    data class GroupEntry(
        val groupId: String,
        val entryId: String,
        override val modelDisplayName: String,
        override val modalityLabel: String,
    ) : PendingNonTextSelection()

    data class Entry(
        val entryId: String,
        override val modelDisplayName: String,
        override val modalityLabel: String,
    ) : PendingNonTextSelection()
}

// ── Voice-correction capture from plain text edits ────────────────────────────

/**
 * [T-android-voice-correction] Capability #3: detect a select-and-replace edit
 * in the composer and hand the before/after pair to the learning recorder.
 *
 * The trigger is structural rather than heuristic: [previous] must have had a
 * NON-EMPTY selection, and [next] must have swapped exactly that span for
 * different text. Ordinary typing is append-only and never matches, so this
 * stays silent during normal composition.
 *
 * Whether the replacement is a genuine CORRECTION is not decided here — the
 * recorder applies the same phonetic-similarity and length-ratio test used for
 * ASR transcripts, so "I changed my mind" is discarded rather than learned.
 *
 * Nothing is captured unless the user has opted in; the call is fire-and-forget
 * so keystrokes never wait on segmentation or SQLite.
 */
internal fun captureSelectionReplacement(
    context: android.content.Context,
    previous: androidx.compose.ui.text.input.TextFieldValue,
    next: androidx.compose.ui.text.input.TextFieldValue,
) {
    val selection = previous.selection
    if (selection.collapsed) return

    val start = minOf(selection.start, selection.end)
    val end = maxOf(selection.start, selection.end)
    val oldText = previous.text
    if (start < 0 || end > oldText.length || start >= end) return

    val replaced = oldText.substring(start, end)
    if (replaced.isBlank()) return

    // The unchanged prefix/suffix around the selection must still match, which
    // confirms this edit replaced exactly that span rather than something else.
    val prefix = oldText.substring(0, start)
    val suffix = oldText.substring(end)
    val newText = next.text
    if (!newText.startsWith(prefix) || !newText.endsWith(suffix)) return

    val insertedEnd = newText.length - suffix.length
    if (insertedEnd < prefix.length) return
    val inserted = newText.substring(prefix.length, insertedEnd)
    // An empty replacement is a deletion — it carries no "should have been"
    // information, so there is nothing to learn from it.
    if (inserted.isBlank() || inserted == replaced) return

    com.openminis.app.speech.correction.VoiceCorrection.captureTextInputEdit(
        context = context,
        before = replaced,
        after = inserted,
    )
}
