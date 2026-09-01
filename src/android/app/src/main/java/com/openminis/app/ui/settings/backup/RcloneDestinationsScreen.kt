package com.openminis.app.ui.settings.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.NorthWest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.backup.remote.RcloneBackendCatalog
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSwitchRow

/**
 * [T-android-rclone-ui] Backup destinations — the Android peer of iOS
 * BackupDestinationPicker + RcloneAddServerView. Lists saved rclone remotes
 * (toggle on/off, remove) and hosts the add-server flow (pick backend → fill
 * connection fields → connect → browse/create a destination folder → save).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RcloneDestinationsScreen(onBack: () -> Unit) {
    val vm: RcloneDestinationsViewModel = viewModel()
    val remotes by vm.remotes.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val browse by vm.browse.collectAsState()

    var adding by remember { mutableStateOf(false) }
    // Name of the destination saved by the last completed save, so the list
    // can confirm it. Cleared once acknowledged.
    var justSaved by remember { mutableStateOf<String?>(null) }

    SettingsScaffold(title = stringResource(R.string.backup_dest_title), onBack = onBack) {
        if (browse != null) {
            // Returning to the list on success is the whole point: the form
            // used to stay put, so a successful save looked identical to one
            // that silently failed and users re-saved the same destination.
            FolderBrowser(vm) { savedName ->
                adding = false
                justSaved = savedName
            }
            return@SettingsScaffold
        }

        if (adding) {
            AddServerForm(
                vm = vm,
                onCancel = { adding = false },
            )
            return@SettingsScaffold
        }

        justSaved?.let { name ->
            Text(
                stringResource(R.string.backup_dest_saved_confirm, name),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        SettingsSection(
            header = stringResource(R.string.backup_dest_saved),
            footer = stringResource(R.string.backup_dest_footer),
        ) {
            if (remotes.isEmpty()) {
                Text(
                    stringResource(R.string.backup_dest_none),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                remotes.forEachIndexed { i, r ->
                    SettingsSwitchRow(
                        title = r.name,
                        subtitle = stringResource(
                            R.string.backup_dest_row_subtitle,
                            r.backend.uppercase(), r.path.trimStart('/'),
                        ),
                        checked = r.enabled,
                        onCheckedChange = { vm.setEnabled(r.name, it) },
                        showDivider = i < remotes.lastIndex,
                    )
                }
            }
        }

        Column(Modifier.padding(16.dp)) {
            MinisButton(
                onClick = { adding = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.backup_dest_add_server)) }

            if (remotes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                remotes.forEach { r ->
                    MinisOutlinedButton(
                        onClick = { vm.remove(r.name) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) { Text(stringResource(R.string.backup_dest_remove, r.name)) }
                }
            }

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
// [T-android-restore-server-list] `internal`, not `private`: the
// Restore-from-Server list hosts this SAME form rather than growing a second
// copy of a 150-line screen with its own backend catalog, TLS opt-in and
// validation. One form, two hosts.
@Composable
internal fun AddServerForm(
    vm: RcloneDestinationsViewModel,
    onCancel: () -> Unit,
    // [T-android-connect-and-save] Does a successful connection END here, or
    // continue into the folder picker?
    //
    // For a BACKUP DESTINATION the folder is where files will be written, so
    // it has to be chosen — true.
    //
    // For a RESTORE SOURCE it is only where browsing starts, and the user
    // browses for the .minisbak afterwards regardless. Making them pick a
    // folder first asked a question whose answer did not matter, in a picker
    // that looked like it was saving something. Connect saves the server and
    // hands straight back — false.
    pickFolder: Boolean = true,
    /** Called with the saved name when [pickFolder] is false. */
    onSaved: (String) -> Unit = {},
) {
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val certificateRejected by vm.certificateRejected.collectAsState()

    var backendExpanded by remember { mutableStateOf(false) }
    var backend by remember { mutableStateOf(RcloneBackendCatalog.all.first()) }
    var displayName by remember { mutableStateOf("") }
    val fieldValues = remember { mutableStateMapOf<String, String>() }
    // [T-android-rclone-tls-timeout] Opt-in to trusting a self-signed
    // certificate. Not persisted here — it rides along to connectAndBrowse and
    // is stored with the remote only if the connection actually succeeds.
    var allowInsecureTLS by remember { mutableStateOf(false) }
    // Absolute path field — the SFTP-absolute-path lesson: always offer a
    // free-text path so a non-chrooted server can start anywhere, not just home.
    var startPath by remember { mutableStateOf("") }

    // NO verticalScroll here — SettingsScaffold already wraps its content in a
    // scrolling Column, and nesting a second verticalScroll gives this child an
    // infinite max-height constraint, which Compose throws on ("Vertically
    // scrollable component was measured with an infinity maximum height").
    // That crash fired every time "Add Server" was opened.
    Column(Modifier.padding(16.dp)) {
        // Backend picker
        ExposedDropdownMenuBox(
            expanded = backendExpanded,
            onExpandedChange = { backendExpanded = it },
        ) {
            OutlinedTextField(
                value = backend.title,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.backup_dest_server_type)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(backendExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = backendExpanded,
                onDismissRequest = { backendExpanded = false },
            ) {
                RcloneBackendCatalog.all.forEach { b ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.backup_dest_backend_label, b.title, b.subtitle)) },
                        onClick = {
                            backend = b; backendExpanded = false
                            fieldValues.clear()
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text(stringResource(R.string.backup_dest_display_name)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )

        backend.fields.forEach { f ->
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = fieldValues[f.key] ?: "",
                onValueChange = { fieldValues[f.key] = it },
                label = {
                    Text(
                        if (f.isOptional) stringResource(R.string.backup_dest_field_optional, f.label)
                        else f.label,
                    )
                },
                placeholder = { Text(f.placeholder) },
                singleLine = true,
                enabled = !busy,
                visualTransformation = if (f.isSecret) PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = startPath,
            onValueChange = { startPath = it },
            label = { Text(stringResource(R.string.backup_dest_start_path)) },
            placeholder = { Text(stringResource(R.string.backup_dest_start_path_hint)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )

        // [T-android-rclone-tls-timeout] Shown ONLY after the server has been
        // refused on certificate grounds. A permanently visible "don't verify
        // certificates" switch sitting next to a password field invites turning
        // off a protection that was never in the way; surfacing it at the moment
        // it would actually help keeps the choice informed.
        if (certificateRejected) {
            Spacer(Modifier.height(16.dp))
            SettingsSwitchRow(
                title = stringResource(R.string.backup_dest_trust_certificate),
                subtitle = stringResource(R.string.backup_dest_trust_certificate_warning),
                checked = allowInsecureTLS,
                onCheckedChange = { allowInsecureTLS = it },
                enabled = !busy,
                showDivider = false,
            )
        }

        Spacer(Modifier.height(16.dp))
        val nameOk = displayName.trim().isNotEmpty() &&
            displayName.trim().all { it.isLetterOrDigit() || it == '-' || it == '_' }
        MinisButton(
            onClick = {
                vm.connectAndBrowse(
                    backend = backend.type,
                    displayName = displayName.trim(),
                    values = fieldValues.toMap(),
                    startPath = startPath.trim(),
                    allowInsecureTLS = allowInsecureTLS,
                    // [T-android-connect-and-save] Restore mode: a successful
                    // connection IS the confirmation, so persist it here and
                    // return. Nothing is saved when the connection fails —
                    // this runs only on success.
                    onConnected = if (pickFolder) null else {
                        { vm.saveConnected(onSaved) }
                    },
                )
            },
            enabled = !busy && nameOk,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                // size(), not height() — see BackupAndRestoreScreen: an
                // unconstrained width falls back to the 40.dp default and
                // overflows the button.
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    stringResource(
                        // "Connect" alone did not say what happens next, and
                        // what happened next differed by host.
                        if (pickFolder) R.string.backup_dest_connect
                        else R.string.backup_dest_connect_and_save,
                    ),
                )
            }
        }
        MinisOutlinedButton(
            onClick = onCancel,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text(stringResource(R.string.backup_dest_cancel)) }

        if (displayName.isNotEmpty() && !nameOk) {
            Text(
                stringResource(R.string.backup_dest_name_invalid),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// [T-android-restore-server-list] internal for the same reason as
// [AddServerForm] — saving a destination goes through this folder step from
// either host.
/**
 * [T-android-folder-picker-ios-parity] One row of the folder picker: icon,
 * left-aligned label, optional right-aligned detail.
 *
 * Left-aligned deliberately. These rows were `Button`s, which centre their
 * content, so every folder name floated at a different x depending on its
 * length — there was no common edge to read down, and scanning the list meant
 * re-finding where each name began.
 */
@Composable
private fun FolderPickerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(
                label,
                color = tint,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
            trailing?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
internal fun FolderBrowser(vm: RcloneDestinationsViewModel, onSaved: (String) -> Unit) {
    val browse by vm.browse.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val b = browse ?: return

    var showNewFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.backup_dest_choose_folder), style = MaterialTheme.typography.titleMedium)

        // [T-android-folder-picker-ios-parity] Path + New Folder on one line,
        // matching iOS: the path reads as a pill and the only action that
        // belongs to it — create a folder HERE — sits at the far end as an
        // icon. It used to be a full-width button far below the list, which
        // separated it from the path it acts on and cost a row of height.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "/${b.path.trimStart('/')}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                // Truncate the FRONT: the deepest component is the one that
                // says where you are, and a long path's leading segments are
                // the least informative part to keep.
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            IconButton(onClick = { showNewFolder = true }, enabled = !busy) {
                Icon(
                    Icons.Outlined.CreateNewFolder,
                    contentDescription = stringResource(R.string.backup_dest_new_folder),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // One card holding "up one level" and the folders, as on iOS — rather
        // than a stack of full-width outlined buttons, which gave every row
        // the visual weight of an action and left folder names centred.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            // First row in the list, not a separate button above it: going up
            // is navigation of the same kind as going down.
            if (b.path.isNotEmpty()) {
                FolderPickerRow(
                    icon = Icons.Outlined.NorthWest,
                    label = stringResource(R.string.backup_dest_up),
                    tint = MaterialTheme.colorScheme.primary,
                    enabled = !busy,
                    showDivider = true,
                    onClick = { vm.navigateTo(b.path.substringBeforeLast('/', "")) },
                )
            }

            b.entries.forEachIndexed { i, entry ->
                FolderPickerRow(
                    icon = Icons.Outlined.Folder,
                    label = entry.name,
                    tint = MaterialTheme.colorScheme.primary,
                    enabled = !busy,
                    showDivider = i < b.entries.lastIndex,
                    // Right-hand column: when the backend reports one. Several
                    // do not for directories — see [RcloneBrowser.Entry].
                    trailing = entry.modified?.let { formatTimestamp(it) },
                    onClick = { vm.navigateTo(entry.path) },
                )
            }

            if (b.entries.isEmpty() && !busy) {
                Text(
                    stringResource(R.string.backup_dest_no_subfolders),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        MinisButton(
            // savePending only invokes this on success (the catch sets _error
            // instead), so leaving the form here cannot strand a failed save
            // on a screen the user has already been navigated away from.
            onClick = { vm.savePending(b.path) { onSaved(b.remote.name) } },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.backup_dest_use_folder)) }

        MinisOutlinedButton(
            onClick = { vm.cancelPending() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text(stringResource(R.string.backup_dest_cancel)) }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false; newFolderName = "" },
            title = { Text(stringResource(R.string.backup_dest_new_folder_title)) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text(stringResource(R.string.backup_dest_folder_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                // Create is always rendered (greyed when empty) — an Android
                // AlertDialog button, unlike a SwiftUI alert action, is not
                // omitted when disabled, so the iOS "Create button missing"
                // trap (e747b6da1) cannot occur here.
                MinisTextButton(
                    onClick = {
                        vm.createFolder(newFolderName.trim())
                        showNewFolder = false; newFolderName = ""
                    },
                    enabled = newFolderName.trim().isNotEmpty(),
                ) { Text(stringResource(R.string.backup_dest_create)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showNewFolder = false; newFolderName = "" }) {
                    Text(stringResource(R.string.backup_dest_cancel))
                }
            },
        )
    }
}
