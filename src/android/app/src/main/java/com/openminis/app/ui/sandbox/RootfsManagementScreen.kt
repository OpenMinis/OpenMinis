package com.openminis.app.ui.sandbox

import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import android.widget.Toast
import com.openminis.app.sandbox.ContainerDns
import com.openminis.app.sandbox.RootfsManager

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.openminis.app.ui.components.MinisCenterTopBar
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.ui.components.SettingsRowDivider
import com.openminis.app.ui.components.SettingsSection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootfsManagementScreen(
    onBack: () -> Unit,
    onBrowseFiles: () -> Unit,
    onMirrorCategoryClick: (MirrorCategory) -> Unit = {},
    viewModel: RootfsManagementViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showResetDialog by remember { mutableStateOf(false) }
    var showResetBackupDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refresh(context)
    }

    // T168: iOS-style inset-grouped background. surfaceContainerLowest sits
    // a hair below `surface`, so the rounded section cards (which use
    // `surface`) read as raised pads against the page bg.
    val groupedBg = MaterialTheme.colorScheme.surfaceContainerLowest
    Scaffold(
        topBar = {
            MinisCenterTopBar(
                title = { Text(stringResource(R.string.rootfs_management_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = groupedBg),
            )
        },
        containerColor = groupedBg,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(groupedBg)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // --- Status section ---
            SettingsSection(title = stringResource(R.string.rootfs_status_section)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.rootfs_installed_label)) },
                    trailingContent = {
                        Icon(
                            if (state.isInstalled) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                            contentDescription = null,
                            tint = if (state.isInstalled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error,
                        )
                    },
                    colors = transparentListItemColors(),
                )
                if (state.isInstalled) {
                    SettingsRowDivider()
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rootfs_size_label)) },
                        trailingContent = {
                            if (state.rootfsSize > 0) {
                                Text(
                                    Formatter.formatFileSize(context, state.rootfsSize),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                        },
                        colors = transparentListItemColors(),
                    )
                    SettingsRowDivider()
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rootfs_path_label)) },
                        supportingContent = {
                            Text(
                                state.rootfsPath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        },
                        colors = transparentListItemColors(),
                    )
                }
            }

            if (state.isInstalled) {
                // --- Browse section ---
                SettingsSection(title = stringResource(R.string.rootfs_browse_section)) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rootfs_browse_files_label)) },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableListItem(onClick = onBrowseFiles),
                        colors = transparentListItemColors(),
                    )
                }

                // --- Mirrors section ---
                SettingsSection(title = stringResource(R.string.rootfs_mirrors_section)) {
                    MirrorsSectionView(onNavigate = onMirrorCategoryClick)
                }

                // --- Container network (DNS) ---
                // [T-container-network-dns] Custom mode lists exactly the entered
                // servers in /etc/resolv.conf and network changes stop rewriting
                // it; AUTO keeps following the system (the only correct choice
                // under a proxy's Fake-IP/TUN mode, which is why it stays default).
                ContainerNetworkSection()
            }

            // --- Actions section ---
            SettingsSection(title = stringResource(R.string.rootfs_actions_section)) {
                if (!state.isInstalled) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rootfs_install_label)) },
                        leadingContent = {
                            Icon(Icons.Filled.Download, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableListItem(
                                enabled = !state.isProcessing,
                                onClick = { viewModel.install(context) },
                            ),
                        colors = transparentListItemColors(),
                    )
                } else {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rootfs_reset_label)) },
                        supportingContent = { Text(stringResource(R.string.rootfs_reset_description)) },
                        leadingContent = {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableListItem(
                                enabled = !state.isProcessing,
                                onClick = { showResetDialog = true },
                            ),
                        colors = transparentListItemColors(),
                    )
                    SettingsRowDivider()
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rootfs_reset_backup_label)) },
                        supportingContent = { Text(stringResource(R.string.rootfs_reset_backup_description)) },
                        leadingContent = {
                            Icon(Icons.Filled.Archive, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableListItem(
                                enabled = !state.isProcessing,
                                onClick = { showResetBackupDialog = true },
                            ),
                        colors = transparentListItemColors(),
                    )
                }

                if (state.hasBackup) {
                    SettingsRowDivider()
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rootfs_restore_label)) },
                        supportingContent = { Text(stringResource(R.string.rootfs_restore_description)) },
                        leadingContent = {
                            Icon(Icons.Filled.Restore, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableListItem(
                                enabled = !state.isProcessing && state.isInstalled,
                                onClick = { viewModel.restoreBackup(context) },
                            ),
                        colors = transparentListItemColors(),
                    )
                }
            }

            // --- Progress / Result ---
            if (state.isProcessing) {
                SettingsSection(title = stringResource(R.string.rootfs_progress_section)) {
                    ListItem(
                        headlineContent = { Text(state.statusMessage) },
                        supportingContent = state.installProgress?.let { p ->
                            {
                                LinearProgressIndicator(
                                    progress = { p },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                        leadingContent = {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        },
                        colors = transparentListItemColors(),
                    )
                }
            }

            state.resultMessage?.let { msg ->
                SettingsSection(title = stringResource(R.string.rootfs_result_section)) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.lastOperationSuccess)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            // --- Info section ---
            SettingsSection(title = stringResource(R.string.rootfs_info_section)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.rootfs_about_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.rootfs_explain),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.rootfs_actions_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // --- Dialogs ---
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.rootfs_reset_confirm_title)) },
            text = { Text(stringResource(R.string.rootfs_reset_confirm_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showResetDialog = false
                    viewModel.resetRootfs(context, keepUserData = false)
                }) {
                    Text(stringResource(R.string.action_reset), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showResetBackupDialog) {
        AlertDialog(
            onDismissRequest = { showResetBackupDialog = false },
            title = { Text(stringResource(R.string.rootfs_reset_backup_confirm_title)) },
            text = {
                Text(stringResource(R.string.rootfs_reset_backup_confirm_message))
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    showResetBackupDialog = false
                    viewModel.resetRootfs(context, keepUserData = true)
                }) {
                    Text(stringResource(R.string.rootfs_reset_backup_confirm_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showResetBackupDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/**
 * ListItem default `containerColor` is `colorScheme.surface`. Inside a
 * SettingsSection card we already supply `surface` as the section's
 * Surface bg, so the per-row default doubles up and breaks the rounded
 * card edges where ListItem extends past the curve. Force the ListItem
 * container transparent so the section card alone owns the visible bg.
 */
@Composable
private fun transparentListItemColors() =
    ListItemDefaults.colors(containerColor = Color.Transparent)

/**
 * [T-container-network-dns] Sandbox DNS mode: follow the system (default) or
 * list exactly the servers the user enters.
 *
 * Saving applies immediately ([RootfsManager.refreshDns]) so the change is
 * visible in the guest without a restart, and an invalid address is rejected
 * before it can reach resolv.conf.
 */
@Composable
private fun ContainerNetworkSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var mode by remember { mutableStateOf(ContainerDns.mode(context)) }
    var serversText by remember { mutableStateOf(ContainerDns.rawServers(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    val savedMessage = stringResource(R.string.rootfs_dns_saved)

    SettingsSection(title = stringResource(R.string.rootfs_dns_section)) {
        listOf(
            ContainerDns.DnsMode.AUTO to Pair(R.string.rootfs_dns_auto, R.string.rootfs_dns_auto_desc),
            ContainerDns.DnsMode.CUSTOM to Pair(R.string.rootfs_dns_custom, R.string.rootfs_dns_custom_desc),
        ).forEach { (candidate, labels) ->
            ListItem(
                headlineContent = { Text(stringResource(labels.first)) },
                supportingContent = { Text(stringResource(labels.second)) },
                trailingContent = {
                    androidx.compose.material3.RadioButton(
                        selected = mode == candidate,
                        onClick = {
                            mode = candidate
                            error = null
                        },
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableListItem(onClick = {
                        mode = candidate
                        error = null
                    }),
                colors = transparentListItemColors(),
            )
        }
        if (mode == ContainerDns.DnsMode.CUSTOM) {
            androidx.compose.material3.OutlinedTextField(
                value = serversText,
                onValueChange = {
                    serversText = it
                    error = null
                },
                label = { Text(stringResource(R.string.rootfs_dns_servers)) },
                placeholder = { Text("223.5.5.5, 1.1.1.1") },
                isError = error != null,
                supportingText = { Text(error ?: stringResource(R.string.rootfs_dns_servers_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        MinisTextButton(onClick = {
            val parsed = ContainerDns.parse(serversText)
            if (mode == ContainerDns.DnsMode.CUSTOM && !parsed.isValid) {
                // Name the offending entries: "invalid" alone leaves the user
                // hunting through a pasted list for the one bad address.
                error = context.getString(R.string.rootfs_dns_invalid, parsed.invalid.joinToString(", "))
            } else {
                ContainerDns.save(context, mode, serversText)
                RootfsManager.getInstance(context).refreshDns()
                Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
            }
        }) {
            Text(stringResource(R.string.rootfs_dns_save))
        }
    }
}

private fun Modifier.clickableListItem(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    return if (enabled) {
        this.then(Modifier.clickable(onClick = onClick))
    } else {
        this
    }
}
