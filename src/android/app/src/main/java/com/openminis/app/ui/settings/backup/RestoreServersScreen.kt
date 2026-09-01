package com.openminis.app.ui.settings.backup

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection

/**
 * [T-android-restore-server-list] The servers you can RESTORE FROM.
 *
 * Reached from the restore tab's "Choose from Server…", unconditionally —
 * whether or not anything is configured. That is the point: the entry the
 * user needs sits in the same place either way, so "no servers yet" stops
 * being a special case with its own dead-end dialog and becomes simply a list
 * with nothing above the Add row.
 *
 * ## Why not just reuse the destinations screen
 *
 * [RcloneDestinationsScreen] shows the same servers, but it is the MANAGEMENT
 * screen: its rows carry enable/disable switches and Remove buttons, and
 * tapping one is an edit gesture. Here a row means "show me this server's
 * backups" — a different verb on the same nouns. Reusing that screen would
 * put destructive controls in front of someone whose stated intent is to
 * read, and would make a tap ambiguous. So this is a separate, deliberately
 * thin screen.
 *
 * What it does NOT duplicate is the form: adding a server hosts the shared
 * [AddServerForm] and [FolderBrowser] from that screen, so there is exactly
 * one Add Server implementation in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreServersScreen(
    onBack: () -> Unit,
    /** Browse one server's packages. */
    onPickServer: (String) -> Unit,
) {
    val vm: RcloneDestinationsViewModel = viewModel()
    val remotes by vm.remotes.collectAsState()
    var adding by remember { mutableStateOf(false) }

    // Re-read on every entry: the user may have just added a server and come
    // back, and a stale list would hide it.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }

    SettingsScaffold(title = stringResource(R.string.backup_server_title), onBack = onBack) {
        // [T-android-connect-and-save] Deliberately NO folder-browser branch
        // here. `connectAndBrowse` still populates `browse` (it is how the
        // connection is verified and how the secret is held until the save),
        // but rendering it would flash the folder picker between a successful
        // connect and the save that immediately follows — showing, for one
        // frame, the very step this flow exists to remove.
        if (adding) {
            // Cancel returns to THIS list, not out of the screen: the list is
            // where the user was, and with a server already configured it has
            // content worth returning to.
            AddServerForm(
                vm = vm,
                onCancel = { adding = false },
                // [T-android-connect-and-save] No folder step here. The saved
                // path is only where browsing starts, and the user browses for
                // the .minisbak next anyway — so a successful connection saves
                // the server and goes straight to its packages.
                pickFolder = false,
                onSaved = { savedName ->
                    adding = false
                    vm.refresh()
                    onPickServer(savedName)
                },
            )
            return@SettingsScaffold
        }

        SettingsSection(
            footer = stringResource(R.string.backup_restore_servers_footer),
        ) {
            remotes.forEach { r ->
                RestoreSourceRow(
                    icon = Icons.Outlined.Cloud,
                    iconColor = MaterialTheme.colorScheme.primary,
                    label = r.name,
                    subtitle = stringResource(
                        R.string.backup_server_row,
                        r.backend.uppercase(),
                        r.path.trimStart('/'),
                    ),
                    enabled = true,
                    onClick = { onPickServer(r.name) },
                    showDivider = true,
                )
            }
            // ALWAYS last, present in both states. With no servers it is the
            // only row, which is exactly the affordance the old empty dialog
            // withheld.
            RestoreSourceRow(
                icon = Icons.Outlined.Add,
                iconColor = MaterialTheme.colorScheme.primary,
                label = stringResource(R.string.backup_dest_add_server),
                enabled = true,
                onClick = { adding = true },
                showDivider = false,
            )
        }
    }
}
