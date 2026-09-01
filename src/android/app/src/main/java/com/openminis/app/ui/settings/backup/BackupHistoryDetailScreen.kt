package com.openminis.app.ui.settings.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.backup.BackupCategory
import com.openminis.app.backup.BackupHistory
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection

/**
 * [T-android-backup-history-detail] One backup run, on its own screen.
 *
 * Mirrors iOS `BackupHistoryDetailView`: Summary (status / started / duration /
 * size / encrypted / file / files-excluded, with the included categories as the
 * section footer), then Destinations, then the timestamped log.
 *
 * This replaces an AlertDialog. A dialog was the wrong container: the log alone
 * runs to dozens of lines, the package name wraps to two, and a modal that
 * needs its own scroll region is a screen wearing a dialog's clothes. It also
 * had nowhere to put the delete action except a button competing with the
 * dismiss button.
 */
@Composable
fun BackupHistoryDetailScreen(
    record: BackupHistory.Record,
    onBack: () -> Unit,
    onRemove: () -> Unit,
    onRemoveWithFiles: (() -> Unit)? = null,
    onOpenSkipped: () -> Unit = {},
    onOpenDestination: ((String) -> Unit)? = null,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    // [T-backup-delete-files-too] Offering to delete the packages too only
    // makes sense when we can name the file AND some destination actually
    // received it.
    val canDeleteFiles = onRemoveWithFiles != null &&
        !record.packageName.isNullOrEmpty() &&
        record.destinations.any { it.succeeded }

    SettingsScaffold(
        title = formatTimestamp(record.startedAt),
        onBack = onBack,
        actions = {
            IconButton(onClick = { confirmRemove = true }) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.backup_history_remove),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    ) {
        // -- Summary --
        SettingsSection(
            header = stringResource(R.string.backup_history_summary),
            // iOS puts the category list here rather than in a row: it is a
            // sentence, not a value, and as a row it wrapped to three lines
            // against a one-word label.
            footer = record.categories
                .takeIf { it.isNotEmpty() }
                // Records store the wire key ("chats"); show the localized
                // label, falling back to the key for a category this build
                // no longer knows about.
                ?.mapNotNull { key ->
                    BackupCategory.fromKey(key)?.let { stringResource(categoryNameRes(it)) } ?: key
                }
                ?.joinToString(", ")
                ?.let { stringResource(R.string.backup_history_included, it) },
        ) {
            DetailRow(label = stringResource(R.string.backup_history_status)) {
                StatusValue(record.status)
            }
            DetailRow(
                label = stringResource(R.string.backup_history_started),
                value = formatTimestamp(record.startedAt),
            )
            record.durationMillis?.let {
                DetailRow(
                    label = stringResource(R.string.backup_history_duration),
                    value = durationText(it),
                )
            }
            if (record.totalBytes > 0) {
                DetailRow(
                    label = stringResource(R.string.backup_history_size),
                    value = humanBytes(record.totalBytes),
                )
            }
            DetailRow(
                label = stringResource(R.string.backup_history_encrypted),
                value = stringResource(
                    if (record.encrypted) R.string.common_yes else R.string.common_no,
                ),
            )
            record.packageName?.let {
                DetailRow(label = stringResource(R.string.backup_history_file), value = it)
            }
            if (record.skippedFiles > 0) {
                // Tappable when the list is available: a bare count answers
                // "how many" but never "which ones", which is the real
                // question. Records written before the list existed stay plain.
                DetailRow(
                    label = stringResource(R.string.backup_history_excluded),
                    value = stringResource(R.string.backup_history_file_count, record.skippedFiles),
                    onClick = if (record.skippedEntries.isNotEmpty()) onOpenSkipped else null,
                    showDivider = record.errorMessage != null,
                )
            }
            // Why it failed, in the summary rather than only buried in the log.
            record.errorMessage
                ?.takeIf { it != BackupHistory.INTERRUPTED_MARKER }
                ?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                    )
                }
        }

        // -- Destinations --
        if (record.destinations.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.backup_history_destinations)) {
                record.destinations.forEachIndexed { i, d ->
                    DestinationOutcomeRow(
                        outcome = d,
                        showDivider = i < record.destinations.lastIndex,
                        // Only when the saved destination still exists — a row
                        // for a server the user has since removed has nowhere
                        // to go.
                        onClick = onOpenDestination?.let { open -> { open(d.name) } },
                    )
                }
            }
        }

        // -- Log --
        SettingsSection(header = stringResource(R.string.backup_history_log)) {
            if (record.log.isEmpty()) {
                Text(
                    stringResource(R.string.backup_history_no_log),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                )
            } else {
                // Newest first: the last thing that happened is what the user
                // opened this screen to read.
                val entries = record.log.asReversed()
                entries.forEachIndexed { i, e ->
                    LogRow(entry = e, showDivider = i < entries.lastIndex)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.backup_history_remove_title)) },
            // Spell out the difference. Removing a record looks like it could
            // delete the backup it describes; when both options are on offer,
            // which one destroys data has to be unambiguous.
            text = {
                Text(
                    stringResource(
                        if (canDeleteFiles) R.string.backup_history_remove_choice_note
                        else R.string.backup_history_remove_note,
                    ),
                )
            },
            confirmButton = {
                Column {
                    MinisTextButton(onClick = { confirmRemove = false; onRemove() }) {
                        Text(
                            stringResource(
                                if (canDeleteFiles) R.string.backup_history_remove_record_only
                                else R.string.backup_history_remove,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (canDeleteFiles) {
                        MinisTextButton(
                            onClick = { confirmRemove = false; onRemoveWithFiles?.invoke() },
                        ) {
                            Text(
                                stringResource(R.string.backup_history_remove_with_files),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { confirmRemove = false }) {
                    Text(stringResource(R.string.backup_dest_cancel))
                }
            },
        )
    }
}

/** Label on the left, value on the right — the peer of iOS `LabeledContent`. */
@Composable
private fun DetailRow(
    label: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    content: @Composable (() -> Unit)? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                when {
                    content != null -> content()
                    // The package name is long enough to need two lines; every
                    // other value is short. Right-aligned so both read as one
                    // trailing column.
                    value != null -> Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
            if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showDivider) RowDivider(inset = 14.dp)
    }
}

/** Status glyph + word, tinted by outcome. */
@Composable
private fun StatusValue(status: BackupHistory.Status) {
    val (icon, tint, textRes) = when (status) {
        BackupHistory.Status.RUNNING ->
            Triple(null, MaterialTheme.colorScheme.onSurfaceVariant, R.string.backup_history_running)
        BackupHistory.Status.SUCCEEDED ->
            Triple(Icons.Outlined.CheckCircle, Color(0xFF34C759), R.string.backup_status_succeeded)
        BackupHistory.Status.COMPLETED_WITH_ISSUES ->
            Triple(Icons.Outlined.Warning, Color(0xFFFF9500), R.string.backup_status_issues)
        BackupHistory.Status.FAILED ->
            Triple(Icons.Outlined.ErrorOutline, Color(0xFFFF3B30), R.string.backup_status_failed)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        } else {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(6.dp))
        Text(stringResource(textRes), style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

/** One destination's outcome: a tick or a cross, the name, and the reason. */
@Composable
private fun DestinationOutcomeRow(
    outcome: BackupHistory.DestinationOutcome,
    showDivider: Boolean,
    onClick: (() -> Unit)? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (outcome.succeeded) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = if (outcome.succeeded) Color(0xFF34C759) else Color(0xFFFF3B30),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(outcome.name, style = MaterialTheme.typography.bodyLarge)
                // The saved name alone ("HomeLab") does not say WHERE the file
                // went. Backend + folder underneath, so someone opening a
                // months-old record can find the package by hand.
                destinationSubtitle(outcome)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                outcome.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (outcome.succeeded) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showDivider) RowDivider(inset = 48.dp)
    }
}

/** One log line: time on the left, message on the right (iOS's log section). */
@Composable
private fun LogRow(entry: BackupHistory.LogEntry, showDivider: Boolean) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                formatTimeOnly(entry.at),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                entry.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.isProblem) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        if (showDivider) RowDivider(inset = 14.dp)
    }
}

@Composable
private fun RowDivider(inset: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.fillMaxWidth().padding(start = inset)
            .height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * "SMB · /backups" — backend tag and folder, uppercased because the stored
 * kind is a lower-case rclone type and reads as noise that way.
 *
 * Returns null when a record predates these fields, so old rows render exactly
 * as they did before rather than showing an empty caption.
 */
private fun destinationSubtitle(d: BackupHistory.DestinationOutcome): String? {
    val kind = d.kind?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    val path = d.path?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        kind != null && path != null -> "$kind · /$path"
        kind != null -> kind
        else -> path
    }
}

/** "3m 53s" / "45s" — matches iOS's durationText. */
internal fun durationText(millis: Long): String {
    val total = millis / 1000
    return if (total < 60) "${total}s" else "${total / 60}m ${total % 60}s"
}

/** Local time-of-day for a log line. */
internal fun formatTimeOnly(millis: Long): String =
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM)
        .format(java.util.Date(millis))
