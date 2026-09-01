package com.openminis.app.ui.settings.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.backup.remote.RcloneChunkedUpload
import com.openminis.app.backup.remote.RcloneRemoteStore
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection

/**
 * [T-android-restore-browse] Walk a destination's folders and pick a backup.
 *
 * Mirrors the iOS server/folder browsers. Three things it is deliberately NOT:
 *
 * - Not a flat dump of every package on the server. Listing is one level at a
 *   time, because a shared root holding far more than backups made a single
 *   recursive sweep slow enough to look hung.
 * - Not silent about where it is looking. The pinned bar names the directory,
 *   so a live listing is never mistaken for the local backup history.
 * - Not a one-tap download. Tapping a package asks first, leading with its
 *   SIZE — an accidental tap on a multi-GB file over cellular is expensive.
 */
@Composable
fun RestoreBrowseScreen(
    remote: RcloneRemoteStore.Remote,
    vm: BackupViewModel,
    onBack: () -> Unit,
    onPicked: () -> Unit,
) {
    val entries by vm.browseEntries.collectAsState()
    val path by vm.browsePath.collectAsState()
    val loading by vm.browsing.collectAsState()
    val error by vm.errorText.collectAsState()
    val transfer by vm.transfer.collectAsState()
    val openStage by vm.openStage.collectAsState()
    val openProgress by vm.openProgress.collectAsState()
    val pending by vm.pending.collectAsState()

    var confirming by remember { mutableStateOf<RcloneChunkedUpload.RemoteEntry?>(null) }

    LaunchedEffect(remote.name) { vm.browseDestination(remote) }
    // Leaving mid-browse must not strand listing state for the next visit.
    DisposableEffect(Unit) { onDispose { vm.clearBrowse() } }
    // The package opened: hand control back so the restore screen can show it.
    LaunchedEffect(pending) { if (pending != null) onPicked() }

    // The remote's own configured folder is the floor — a user restoring should
    // not wander out of the directory they nominated as their backup location.
    val root = remote.path.trim('/')
    val current = path.trim('/')
    val segments = remember(current, root) {
        val rel = current.removePrefix(root).trim('/')
        if (rel.isEmpty()) emptyList() else rel.split('/')
    }

    SettingsScaffold(title = remote.name, onBack = onBack, scrollable = false) {
        // Pinned, NOT part of the scrolling list: as a list section it scrolled
        // away with the content, so a user deep in a long folder could no
        // longer see — or tap — the way back up.
        BreadcrumbBar(
            rootLabel = remote.name,
            segments = segments,
            onNavigate = { depth ->
                val target = if (depth == 0) root
                else (listOf(root) + segments.take(depth)).filter { it.isNotEmpty() }.joinToString("/")
                vm.browseDestination(remote, target)
            },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(
                header = stringResource(R.string.restore_browse_header),
                footer = stringResource(
                    if (entries.isEmpty()) R.string.restore_browse_footer_empty
                    else R.string.restore_browse_footer,
                ),
            ) {
                when {
                    loading -> LoadingRow(stringResource(R.string.backup_dest_loading))
                    entries.isEmpty() -> Text(
                        stringResource(R.string.restore_browse_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                    )
                    else -> entries.forEachIndexed { i, e ->
                        EntryRow(
                            entry = e,
                            showDivider = i < entries.lastIndex,
                            onClick = {
                                if (e.isDirectory) vm.browseDestination(remote, e.path)
                                else confirming = e
                            },
                        )
                    }
                }
            }
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // Size first, as on iOS: it is the fact that decides whether to proceed,
    // especially on a metered connection.
    confirming?.let { e ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.restore_download_title)) },
            text = {
                Text(stringResource(R.string.restore_download_note, e.name, humanBytes(e.size)))
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    confirming = null
                    vm.downloadServerPackage(
                        RcloneChunkedUpload.RemotePackage(
                            key = e.path,
                            displayName = e.name,
                            size = e.size,
                            modified = e.modified,
                            partCount = 1,
                        ),
                        remote,
                    )
                }) { Text(stringResource(R.string.restore_download_confirm)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { confirming = null }) {
                    Text(stringResource(R.string.backup_dest_cancel))
                }
            },
        )
    }

    transfer?.let { t -> TransferSheet(t, onCancel = vm::cancelDownload) }
    if (transfer == null) openStage?.let {
        OpenStageSheet(it, openProgress, onCancel = vm::cancelOpen)
    }
}

/**
 * Tappable path, anchored to the trailing edge.
 *
 * A single static label plus an Up button meant climbing several levels one tap
 * at a time. Each crumb jumps straight to that level. The row scrolls
 * horizontally and sits at its end, because on a deep path the levels nearest
 * the user are the ones worth showing.
 */
@Composable
private fun BreadcrumbBar(
    rootLabel: String,
    segments: List<String>,
    onNavigate: (Int) -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(segments.size) { scroll.animateScrollTo(scroll.maxValue) }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Crumb(rootLabel, enabled = segments.isNotEmpty()) { onNavigate(0) }
            segments.forEachIndexed { i, seg ->
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Crumb(seg, enabled = i < segments.lastIndex) { onNavigate(i + 1) }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun Crumb(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = Modifier
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun EntryRow(
    entry: RcloneChunkedUpload.RemoteEntry,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(30.dp).background(
                    if (entry.isDirectory) Color(0xFF8E8E93) else Color(0xFF007AFF),
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Inventory2,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                if (!entry.isDirectory) {
                    Text(
                        buildString {
                            append(humanBytes(entry.size))
                            entry.modified?.let { append(" · "); append(formatTimestamp(it)) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entry.isDirectory) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showDivider) {
            Box(
                Modifier.fillMaxWidth().padding(start = 56.dp).height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

/**
 * [T-android-restore-progress-sheet] A long-running step, presented as a
 * bottom sheet that only its own Cancel can close.
 *
 * These used to be `AlertDialog`s. A dialog floats in the middle of the
 * screen, so the live area of a multi-GB download sat directly under the
 * thumb, surrounded by scrim on every side — and while `onDismissRequest = {}`
 * blocks the outside tap, the shape still reads as dismissable and invites the
 * tap in the first place. A sheet anchored to the bottom edge puts the content
 * where a phone is actually held, and Cancel becomes a deliberate press rather
 * than the thing you hit by accident.
 *
 * Non-dismissable on purpose, in all three ways it could be closed:
 *   - `onDismissRequest = {}` — scrim taps do nothing
 *   - `confirmValueChange = { it != Hidden }` — the drag handle cannot swipe
 *     it away, which `onDismissRequest` alone does NOT prevent
 *   - `dragHandle = null` — no affordance suggesting it can be
 *
 * Abandoning the work stays possible; it just has to be said out loud.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgressSheet(
    title: String,
    onCancel: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = sheetState,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 24.dp, bottom = 28.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(14.dp))
            content()
            Spacer(Modifier.height(20.dp))
            MinisOutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.backup_dest_cancel)) }
        }
    }
}

/** Live download: bar, bytes, speed, time left, and a Cancel that works. */
@Composable
private fun TransferSheet(t: BackupViewModel.TransferInfo, onCancel: () -> Unit) {
    ProgressSheet(
        title = stringResource(R.string.restore_downloading),
        onCancel = onCancel,
    ) {
        Text(t.name, style = MaterialTheme.typography.bodySmall, maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { t.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            buildString {
                append(humanBytes(t.bytesDone)); append(" / "); append(humanBytes(t.totalBytes))
                if (t.bytesPerSecond > 0) {
                    append("  ·  "); append(humanBytes(t.bytesPerSecond.toLong())); append("/s")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        t.secondsRemaining?.let {
            Text(
                stringResource(R.string.restore_time_left, durationText(it * 1000)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Which step of opening the package is underway.
 *
 * Deliberately not a percentage: unzip reports nothing along the way, and a bar
 * that sits at 0 and jumps to 100 is a worse lie than naming the step.
 */
@Composable
private fun OpenStageSheet(
    stage: Int,
    progress: BackupViewModel.OpenProgress?,
    onCancel: () -> Unit,
) {
    val label = when (stage) {
        0 -> R.string.restore_stage_reading
        1 -> R.string.restore_stage_expanding
        2 -> R.string.restore_stage_checking
        else -> R.string.restore_stage_almost
    }
    ProgressSheet(
        title = stringResource(R.string.restore_opening),
        onCancel = onCancel,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
        }
        // [T-android-open-progress] Real counts under the stage label. Absent
        // until the first entry lands, so the row does not flash "0 files".
        progress?.let { p ->
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.restore_open_progress, p.files, humanBytes(p.bytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                p.current,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
