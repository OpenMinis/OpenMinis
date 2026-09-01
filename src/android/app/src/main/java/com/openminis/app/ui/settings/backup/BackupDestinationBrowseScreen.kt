package com.openminis.app.ui.settings.backup

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.backup.remote.RcloneChunkedUpload
import com.openminis.app.backup.remote.RcloneRemoteStore
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection

/**
 * [T-backup-destination-browse] What is actually stored on one destination.
 *
 * Reached by tapping a destination in a backup record, so "did this really
 * land?" is answerable without hunting through Settings for the server —
 * which is the question a user opens an old record to ask.
 *
 * Scoped deliberately to browsing and deleting. iOS's
 * `BackupDestinationDetailView` also edits the connection, renames, tests and
 * removes the destination; Android already has those under Manage
 * Destinations, and duplicating them here would give the same server two
 * places to be edited from.
 */
@Composable
fun BackupDestinationBrowseScreen(
    remote: RcloneRemoteStore.Remote,
    vm: BackupViewModel,
    onBack: () -> Unit,
) {
    val packages by vm.serverPackages.collectAsState()
    val running by vm.isRunning.collectAsState()
    val error by vm.errorText.collectAsState()
    var pendingDelete by remember { mutableStateOf<RcloneChunkedUpload.RemotePackage?>(null) }

    LaunchedEffect(remote.name) { vm.clearError(); vm.listServerPackages(remote) }

    SettingsScaffold(
        title = remote.name,
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.backup_dest_stored_here),
            footer = stringResource(
                if (packages.isEmpty()) R.string.backup_dest_browse_footer_empty
                else R.string.backup_dest_browse_footer,
            ),
        ) {
            when {
                running -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.backup_dest_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                packages.isEmpty() -> {
                    Text(
                        stringResource(R.string.backup_dest_no_packages),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                    )
                }
                else -> packages.forEachIndexed { i, pkg ->
                    PackageRow(
                        pkg = pkg,
                        showDivider = i < packages.lastIndex,
                        onDelete = { pendingDelete = pkg },
                    )
                }
            }
        }
        // A failed delete leaves the list unchanged, so without this the user
        // sees the file still there and no reason why.
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

    pendingDelete?.let { pkg ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.backup_dest_delete_title)) },
            // A deleted package cannot be recovered, and this is the copy the
            // record points at — say so before it goes.
            text = { Text(stringResource(R.string.backup_dest_delete_note, pkg.displayName)) },
            confirmButton = {
                MinisTextButton(
                    onClick = {
                        vm.deleteServerPackage(remote, pkg)
                        pendingDelete = null
                    },
                ) {
                    Text(
                        stringResource(R.string.backup_dest_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.backup_dest_cancel))
                }
            },
        )
    }
}

@Composable
private fun PackageRow(
    pkg: RcloneChunkedUpload.RemotePackage,
    showDivider: Boolean,
    onDelete: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    pkg.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    buildString {
                        append(humanBytes(pkg.size))
                        pkg.modified?.let { append(" · "); append(formatTimestamp(it)) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.backup_dest_delete_confirm),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showDivider) {
            Box(
                Modifier.fillMaxWidth().padding(start = 14.dp)
                    .height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}
