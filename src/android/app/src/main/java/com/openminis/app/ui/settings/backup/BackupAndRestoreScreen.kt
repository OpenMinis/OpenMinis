package com.openminis.app.ui.settings.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.data.DeviceIdentity
import com.openminis.app.ui.settings.SettingsSwitch
import com.openminis.app.R
import com.openminis.app.backup.BackupCategory
import com.openminis.app.backup.BackupFormat
import com.openminis.app.backup.BackupHistory
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-backup-primary-action] Shared content for the two primary actions —
 * Start Backup and Start Restore.
 *
 * Both screens' main control is an icon + label pair, so they read as the same
 * kind of thing rather than two buttons that happen to sit in the same app
 * (iOS parity: `BackupActionIcon`).
 *
 * The spinner replaces the ICON, not the label. Swapping the label for a
 * status string — which is what Start Restore used to do — re-measures the row
 * the moment the user commits, so the button moves under the finger that just
 * pressed it. Keeping the label fixed and the spinner inside the icon's slot,
 * at the icon's size, holds the row's height constant across the transition.
 */
@Composable
private fun RowScope.PrimaryActionContent(
    icon: ImageVector,
    label: String,
    busy: Boolean,
    /**
     * [T-android-restore-perf] 0f..1f when a total is known, null otherwise.
     * Drives a determinate arc so the indicator repaints on real progress
     * rather than on every frame — see the busy branch below.
     */
    fraction: Float? = null,
    /**
     * [T-android-restore-ui] Non-null while a restore is running and stoppable.
     * Turns the ring into a tap target with a stop glyph at its centre; null
     * leaves it a plain indicator.
     */
    onStopClick: (() -> Unit)? = null,
) {
    if (busy) {
        // [T-android-restore-perf] A DETERMINATE arc, not the spinning
        // indeterminate one.
        //
        // CircularProgressIndicator's no-argument form animates forever, and
        // an infinite animation drives a redraw every frame for as long as it
        // is on screen. Measured with perfetto during a live restore: 478
        // frames in 10 s (~48 fps) repainting the whole 1080x2340 surface,
        // costing RenderThread 2.49 s of CPU per 10 s wall clock — on a device
        // whose restore was already I/O-bound, spent entirely on a spinner
        // that conveys nothing the counter beside it does not.
        //
        // The determinate form redraws only when `progress` actually changes,
        // which the importer reports every 200 records (~1.7 s apart at the
        // measured rate) rather than every frame.
        val stopActionLabel = stringResource(R.string.restore_stop_action)
        if (onStopClick != null) {
            // [T-android-restore-ui] Ring + stop glyph, in one tap target.
            //
            // The touch area is 36.dp even though the ring is 24.dp: the
            // visible arc is below the 48.dp accessibility minimum and cannot
            // grow without unbalancing the button, so the hit rect is expanded
            // around it instead. It is the ONLY live control while a restore
            // runs — the button behind it is disabled — so a near miss does
            // nothing rather than triggering something else.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onStopClick)
                    .semantics {
                        role = Role.Button
                        contentDescription = stopActionLabel
                    },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { fraction ?: 0f },
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp,
                    color = LocalContentColor.current,
                )
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(LocalContentColor.current),
                )
            }
        } else {
            CircularProgressIndicator(
                progress = { fraction ?: 0f },
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
        }
    } else {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.width(8.dp))
    Text(label)
}

/**
 * [T-android-backup-ui] Backup & Restore — the Android peer of iOS
 * `BackupAndRestoreView`: a segmented [Backup | Restore] container over two
 * sub-forms. Reached from Settings → Storage.
 */
@Composable
fun BackupAndRestoreScreen(
    onBack: () -> Unit,
    onManageDestinations: () -> Unit = {},
    // [T-android-restore-server-list] Open the "Restore from Server" list —
    // the servers you can RESTORE FROM, plus an Add Server entry. Distinct
    // from [onManageDestinations], which is the editable destinations screen
    // (rename/remove/enable) reached from the Backup tab: same servers, a
    // different verb, so they are deliberately different screens.
    onChooseRestoreServer: () -> Unit = {},
    onOpenHistoryRecord: (String) -> Unit = {},
    onBrowseDestination: (String) -> Unit = {},
    // 0 = Backup, 1 = Restore. The onboarding restore link opens on Restore
    // (iOS `BackupAndRestoreView(initialTab: .restore)`); Settings keeps Backup.
    initialTab: Int = 0,
) {
    val vm: BackupViewModel = viewModel()
    // 0 = Backup, 1 = Restore. Saveable, not plain `remember`: navigating to
    // the Add Server form takes this screen off the back stack, and coming
    // back on plain `remember` reset the picker to Backup — dropping a user
    // who cancelled out of "Choose from Server…" onto the wrong tab, one step
    // further from where they started than when they left. A tab index is not
    // a secret, so unlike the passphrases below it is safe to persist.
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // Hoisted out of the tabs so switching tabs does not discard them — see
    // [T-restore-keep-tab-state] below.
    var backupPassphrase by remember { mutableStateOf("") }
    var backupConfirm by remember { mutableStateOf("") }
    var restorePassphrase by remember { mutableStateOf("") }

    SettingsScaffold(title = stringResource(R.string.backup_title), onBack = onBack) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SegmentedButton(
                selected = tab == 0,
                onClick = { tab = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text(stringResource(R.string.backup_tab_backup)) }
            SegmentedButton(
                selected = tab == 1,
                onClick = { tab = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(R.string.backup_tab_restore)) }
        }

        // [T-restore-keep-tab-state] Only ONE tab is composed at a time, so the
        // other leaves the tree and plain `remember` state dies with it. The
        // picked package survives regardless — it lives in the ViewModel — but
        // a half-typed passphrase did not, so glancing at the other tab
        // mid-restore silently emptied the field.
        //
        // Hoisted to the container, which outlives both tabs. NOT
        // `rememberSaveable`: that would write the passphrase into the
        // saved-instance-state bundle, persisting a secret to disk to save the
        // user a few keystrokes.
        if (tab == 0) {
            BackupTab(
                vm, onManageDestinations, onOpenHistoryRecord,
                passphrase = backupPassphrase,
                onPassphraseChange = { backupPassphrase = it },
                confirm = backupConfirm,
                onConfirmChange = { backupConfirm = it },
            )
        } else {
            RestoreTab(
                vm, onBrowseDestination,
                onChooseRestoreServer = onChooseRestoreServer,
                passphrase = restorePassphrase,
                onPassphraseChange = { restorePassphrase = it },
            )
        }
    }
}

/** Rows shown before "Show all" — enough to cover a normal week of backups. */
private const val HISTORY_COLLAPSED_COUNT = 10

// ─── Backup tab ──────────────────────────────────────────────────────────

@Composable
private fun BackupTab(
    vm: BackupViewModel,
    onManageDestinations: () -> Unit = {},
    onOpenHistoryRecord: (String) -> Unit = {},
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    confirm: String,
    onConfirmChange: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val selected by vm.selected.collectAsState()
    val encrypt by vm.encrypt.collectAsState()
    val maxFileSizeMB by vm.maxFileSizeMB.collectAsState()
    // [T-android-backup-run-controller] The backup's own flag, not the
    // restore/backup union: this tab's Stop button must not appear for a
    // restore running on the other tab.
    val running by vm.exportRunning.collectAsState()
    val status by vm.statusText.collectAsState()
    val error by vm.errorText.collectAsState()
    val destinations by vm.destinations.collectAsState()
    val historyRecords by vm.historyRecords.collectAsState()
    val lastResult by vm.lastResult.collectAsState()
    val exportReady by vm.exportReady.collectAsState()
    val saveState by vm.saveState.collectAsState()

    // [T-android-backup-local-export] Save to Device: SAF CreateDocument. The
    // MIME type drives the extension the picker appends (BackupFormat.MIME_TYPE
    // explains why not octet-stream). The copy itself runs on the backup
    // controller's process scope, so it survives leaving this screen.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(com.openminis.app.backup.BackupFormat.MIME_TYPE),
    ) { uri -> uri?.let(vm::saveExportTo) }

    // Re-read destinations every time this tab appears: the user may have just
    // added one via "Manage Destinations…" and navigated back, and a stale
    // empty list would keep the Start button disabled with no way to recover.
    LaunchedEffect(Unit) {
        // [T-android-backup-transient-success] Drop a PREVIOUS visit's success
        // card on the way IN, not on the way out. Clearing on dispose would
        // yank it away the moment the user tapped into the run they were
        // reading about; by the time this fires again, the visit that produced
        // the result has ended, which is exactly when the card stops being
        // useful. The same facts remain in Backup History.
        //
        // Only a settled, fully-delivered run is dropped — a failure is the
        // one result worth coming back to.
        vm.clearSettledSuccess()
        vm.refreshDestinations()
        vm.refreshHistory()
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vm.refreshDestinations()
                vm.refreshHistory()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val passphraseValid = !encrypt || (passphrase.isNotEmpty() && passphrase == confirm)
    val hasFileTree = selected.any { it.carriesFileTree }

    // -- Device Name --
    DeviceNameSection(enabled = !running)

    // -- Include --
    // Footer changes with the Max Per-File Size selection, but only when a
    // file-carrying category is selected — otherwise the cap is meaningless
    // (mirrors iOS BackupSettingsView).
    val includeFooter = when {
        !hasFileTree -> stringResource(R.string.backup_include_footer)
        maxFileSizeMB == MAX_FILE_NO_FILES ->
            stringResource(R.string.backup_file_footer_no_files)
        maxFileSizeMB != MAX_FILE_UNLIMITED ->
            stringResource(R.string.backup_file_footer_limited, maxFileSizeMB)
        else -> stringResource(R.string.backup_file_footer_unlimited)
    }
    SettingsSection(
        header = stringResource(R.string.backup_section_include),
        footer = includeFooter,
    ) {
        val cats = BackupCategory.backupable
        cats.forEachIndexed { i, cat ->
            CategorySwitchRow(
                title = stringResource(categoryNameRes(cat)),
                icon = categoryIcon(cat),
                iconColor = categoryTint(cat),
                checked = cat in selected,
                onCheckedChange = { vm.toggleCategory(cat, it) },
                enabled = !running,
                showDivider = i < cats.lastIndex || hasFileTree,
            )
        }
        // Max Per-File Size picker — only when a file-carrying category is on.
        if (hasFileTree) {
            MaxFileSizeRow(
                current = maxFileSizeMB,
                enabled = !running,
                onSelect = { vm.setMaxFileSizeMB(it) },
            )
        }
    }

    // -- Encryption --
    SettingsSection(
        header = stringResource(R.string.backup_section_encryption),
        footer = if (encrypt) {
            stringResource(R.string.backup_encrypt_footer_on)
        } else if (BackupCategory.PROVIDERS in selected ||
            BackupCategory.ENVIRONMENT_VARIABLES in selected ||
            // [T-backup-credentials-without-encryption] MCP belongs here for a
            // reason that is easy to miss: mcp_servers.json is copied VERBATIM
            // (BackupExporter.exportMcpServers), so a user-set Authorization
            // header travels inside it — a secret, in an unencrypted package,
            // whether or not PROVIDERS was selected. iOS lists the same three.
            BackupCategory.MCP_SERVERS in selected
        ) {
            stringResource(R.string.backup_encrypt_footer_off_creds)
        } else {
            stringResource(R.string.backup_encrypt_footer_off)
        },
    ) {
        CategorySwitchRow(
            title = stringResource(R.string.backup_encrypt_backup),
            icon = Icons.Outlined.Lock,
            iconColor = Color(0xFF34C759),
            checked = encrypt,
            onCheckedChange = { vm.setEncrypt(it) },
            enabled = !running,
            showDivider = encrypt,
        )
        if (encrypt) {
            // [T-android-backup-passphrase-keyboard] (GH#345) One toggle for the
            // pair: the user is typing the same secret twice and needs to
            // compare them, so revealing only one half would defeat the point.
            var revealPassphrase by remember { mutableStateOf(false) }
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = onPassphraseChange,
                    label = { Text(stringResource(R.string.backup_passphrase)) },
                    singleLine = true,
                    enabled = !running,
                    // [T-android-backup-passphrase-keyboard] (GH#345)
                    // `PasswordVisualTransformation` alone only masks the
                    // GLYPHS — the field still declares itself as ordinary
                    // text, so the IME treats it as one: third-party keyboards
                    // offer clipboard history and network-backed prediction,
                    // and learn the passphrase into their personal dictionary.
                    // KeyboardType.Password is what tells the IME this is a
                    // secret, which is what makes a keyboard drop those
                    // features (and what lets the system substitute its own
                    // secure keyboard).
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (revealPassphrase) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { revealPassphrase = !revealPassphrase },
                            enabled = !running,
                        ) {
                            Icon(
                                if (revealPassphrase) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = stringResource(
                                    if (revealPassphrase) R.string.backup_hide_passphrase
                                    else R.string.backup_show_passphrase,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = onConfirmChange,
                    label = { Text(stringResource(R.string.backup_confirm_passphrase)) },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (revealPassphrase) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    isError = confirm.isNotEmpty() && confirm != passphrase,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (confirm.isNotEmpty() && confirm != passphrase) {
                    Text(
                        stringResource(R.string.backup_passphrase_mismatch),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    // -- Destinations --
    // [T-android-backup-destination-gate] Inline list, mirroring iOS's
    // `destinationSection`. Android previously offered only a "Manage
    // Destinations…" button, so the backup screen never showed WHETHER a
    // destination existed — which is how "back up with none configured"
    // stayed invisible.
    DestinationsSection(
        destinations = destinations,
        enabled = !running,
        onManage = onManageDestinations,
        onToggle = vm::setDestinationEnabled,
    )

    // -- Action --
    Column(Modifier.padding(16.dp)) {
        // [T-android-backup-stop] ONE button that toggles. While a backup runs
        // it says "Stop Backup" and stops it — nothing else about the row
        // changes.
        //
        // It used to turn into a disabled spinner captioned with the live
        // status, which put the progress report inside the control: the row a
        // user reaches for moved and greyed out at the very moment they wanted
        // to act, and there was no way to stop a running backup at all.
        // Progress belongs in the run's own entry in Backup History below,
        // which shows the live status line and the whole log; the control
        // stays a control. Same reasoning, and the same shape, as iOS.
        MinisButton(
            onClick = {
                if (running) vm.stopExport()
                else vm.startExport(passphrase.takeIf { encrypt })
            },
            // The start-time requirements gate STARTING only. Applying them
            // while running would disable the button mid-run and leave no way
            // to stop the backup from this screen.
            //
            // [T-android-backup-local-export] A destination is no longer one
            // of them. With none, the run is local-only and ends in Save to
            // Device / Share, and the result card says it is not saved outside
            // the app yet — so the "reads as success" problem the old gate
            // guarded against is answered in words, not by a dead button.
            enabled = running || (selected.isNotEmpty() && passphraseValid),
            colors = if (running) {
                androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                androidx.compose.material3.ButtonDefaults.buttonColors()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Stop is the destructive counterpart, so it gets the stop glyph
            // on the red container rather than the same cloud-upload icon in a
            // different colour.
            PrimaryActionContent(
                icon = if (running) Icons.Outlined.Stop else Icons.Outlined.CloudUpload,
                label = stringResource(
                    if (running) R.string.backup_stop else R.string.backup_start,
                ),
                // The backup button reports progress in Backup History below,
                // not in the control, so it keeps its icon while running.
                busy = false,
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

        // Why the button is disabled. Destination first: it is the requirement
        // a new user is most likely to be missing, since the categories arrive
        // already selected (same ordering and rationale as iOS).
        if (!running) {
            // [T-android-backup-local-export] Real blockers first; "no
            // destination" is now information about where the package will
            // (not) go, not a reason the button is disabled.
            val hint = when {
                selected.isEmpty() -> stringResource(R.string.backup_needs_category)
                encrypt && passphrase.isEmpty() -> stringResource(R.string.backup_needs_passphrase)
                destinations.none { it.enabled } -> stringResource(R.string.backup_no_destination_hint)
                else -> null
            }
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }


    // -- Just finished --
    // Under the button, as on iOS: a transient report of the run the user just
    // watched, gone by their next visit.
    lastResult?.let { r ->
        if (!running) {
            SettingsSection(
                header = stringResource(R.string.backup_ready),
                footer = backupResultFooter(r),
            ) {
                DetailValueRow(
                    label = stringResource(R.string.backup_history_size),
                    value = humanBytes(r.totalBytes),
                    showDivider = r.skippedFiles > 0 || r.destinations.isNotEmpty(),
                )
                if (r.skippedFiles > 0) {
                    DetailValueRow(
                        // "Too large" would be wrong when the user's own cap
                        // said not to include files at all — nothing exceeded
                        // anything then.
                        label = stringResource(
                            if (maxFileSizeMB == MAX_FILE_NO_FILES) R.string.backup_result_files_not_included
                            else R.string.backup_result_excluded_too_large,
                        ),
                        value = stringResource(R.string.backup_history_file_count, r.skippedFiles),
                        showDivider = r.destinations.isNotEmpty(),
                    )
                }
                // Per destination, not one combined status: "2 of 3 saved" is
                // only actionable if the user can see WHICH one failed.
                r.destinations.forEachIndexed { i, d ->
                    ResultDestinationRow(
                        outcome = d,
                        showDivider = i < r.destinations.lastIndex,
                    )
                }
            }
            // [T-android-backup-local-export] The exits for a run that had
            // nowhere to go. Same condition as iOS (`deliveryResults.isEmpty`):
            // what matters is whether THIS run was carried anywhere, not
            // whether a destination is configured now. A run whose delivery
            // partly failed keeps its local copy but does not get these —
            // its per-destination rows already say what to fix.
            val pkg = exportReady?.packageFile
            if (r.localOnly && pkg != null && pkg.exists()) {
                LocalExportActions(
                    saving = saveState is com.openminis.app.backup.BackupRunController.SaveState.Saving,
                    onSave = { saveLauncher.launch(pkg.name) },
                    onShare = { shareBackupPackage(context, pkg) },
                )
                saveStateLine(saveState)?.let { (text, isError) ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
    }

    // -- History --
    BackupHistorySection(
        records = historyRecords,
        onOpen = onOpenHistoryRecord,
    )
}

/**
 * [T-android-backup-device-name-setting] Names this device for backup
 * filenames (`<device>-<date>-<id>.minisbak`) and the manifest's
 * `device_name` — the iOS Backup settings section of the same name.
 *
 * Committed on every change rather than on IME Done: the screen is usually
 * left with Back or by tapping Start Backup, neither of which submits. The
 * draft is local state so the field can sit empty while the user retypes
 * without the placeholder jumping; storing blank clears the override.
 */
@Composable
private fun DeviceNameSection(enabled: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val automatic = remember { DeviceIdentity.automaticName(context) }
    var draft by remember { mutableStateOf(DeviceIdentity.customName(context).orEmpty()) }
    SettingsSection(
        header = stringResource(R.string.backup_device_name),
        footer = stringResource(R.string.backup_device_name_footer, automatic),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(30.dp).background(Color(0xFF007AFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhoneAndroid,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            val textStyle = MaterialTheme.typography.bodyLarge
            androidx.compose.foundation.text.BasicTextField(
                value = draft,
                onValueChange = {
                    draft = it.take(DeviceIdentity.CUSTOM_NAME_MAX_LENGTH)
                    DeviceIdentity.setCustomName(context, draft)
                },
                enabled = enabled,
                singleLine = true,
                textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
                    autoCorrectEnabled = false,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        // The placeholder is the automatic name, so an empty
                        // field shows what will be used instead of a blank.
                        if (draft.isEmpty()) {
                            Text(
                                automatic,
                                style = textStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}

/**
 * [T-android-backup-history] Past runs, newest first. Mirrors iOS
 * `BackupSettingsView.historySection`.
 *
 * The screen previously showed only a transient "Backup ready" card for the
 * last run in the current session, which never said which destinations the
 * package actually reached — a delivery that failed to one server out of
 * three was a single red line that vanished on navigation.
 */
@Composable
private fun BackupHistorySection(
    records: List<BackupHistory.Record>,
    onOpen: (String) -> Unit,
) {
    if (records.isEmpty()) return
    // Cap what is composed. The whole screen sits in a verticalScroll, so a
    // LazyColumn here would fight it for height constraints; the 30-day
    // retention meanwhile puts no ceiling on the count — a user backing up
    // hourly has ~700 records, every one of them composed on every recomposition
    // of this screen. iOS gets virtualisation for free from List and needs no
    // equivalent.
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) records else records.take(HISTORY_COLLAPSED_COUNT)
    SettingsSection(
        header = stringResource(R.string.backup_history),
        footer = stringResource(R.string.backup_history_footer),
    ) {
        shown.forEachIndexed { i, r ->
            BackupHistoryRow(
                record = r,
                showDivider = i < shown.lastIndex || !expanded && records.size > shown.size,
                onClick = { onOpen(r.id) },
            )
        }
        if (!expanded && records.size > shown.size) {
            RestoreSourceRow(
                icon = Icons.Outlined.History,
                iconColor = Color(0xFF8E8E93),
                label = stringResource(
                    R.string.backup_history_show_all,
                    records.size,
                ),
                enabled = true,
                onClick = { expanded = true },
                showDivider = false,
            )
        }
    }
}

@Composable
private fun BackupHistoryRow(
    record: BackupHistory.Record,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val (icon, tint) = when (record.status) {
        BackupHistory.Status.RUNNING -> Icons.Outlined.Sync to Color(0xFF8E8E93)
        BackupHistory.Status.SUCCEEDED -> Icons.Outlined.CheckCircle to Color(0xFF34C759)
        BackupHistory.Status.COMPLETED_WITH_ISSUES -> Icons.Outlined.Warning to Color(0xFFFF9500)
        BackupHistory.Status.FAILED -> Icons.Outlined.ErrorOutline to Color(0xFFFF3B30)
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(30.dp).background(tint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    formatTimestamp(record.startedAt),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    backupHistorySubtitle(record),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Says the row leads somewhere. Without it the row looks like a
            // status readout, and the detail screen behind it goes unfound.
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        if (showDivider) {
            Box(
                Modifier.fillMaxWidth().padding(start = 56.dp)
                    .height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

/** "12.4 MB · 2 of 3 destinations" — the delivery result, up front. */
@Composable
private fun backupHistorySubtitle(record: BackupHistory.Record): String {
    // While running the subtitle is the LIVE progress line; afterwards it is
    // the outcome. Same row either way, so a finishing backup doesn't jump.
    if (record.status == BackupHistory.Status.RUNNING) {
        return record.log.lastOrNull()?.message
            ?: stringResource(R.string.backup_history_running)
    }
    if (record.status == BackupHistory.Status.FAILED) {
        return when (record.errorMessage) {
            BackupHistory.INTERRUPTED_MARKER ->
                stringResource(R.string.backup_history_interrupted)
            BackupViewModel.STOPPED_MARKER ->
                stringResource(R.string.backup_history_stopped)
            null -> stringResource(R.string.backup_history_interrupted)
            else -> record.errorMessage
        }
    }
    val ok = record.destinations.count { it.succeeded }
    val total = record.destinations.size
    val size = humanBytes(record.totalBytes)
    return if (total == 0) size
    else "$size · " + stringResource(R.string.backup_history_delivered, ok, total)
}


/** Local date-time for a history row. Locale-aware via the platform formatter. */
internal fun formatTimestamp(millis: Long): String =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT,
    ).format(java.util.Date(millis))

/** Max Per-File Size dropdown row (iOS: the `doc.zipper` menu Picker). */
@Composable
private fun MaxFileSizeRow(
    current: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (current) {
        MAX_FILE_NO_FILES -> stringResource(R.string.backup_max_file_no_files)
        MAX_FILE_UNLIMITED -> stringResource(R.string.backup_max_file_unlimited)
        else -> stringResource(R.string.backup_max_file_mb, current)
    }
    // Ordered smallest → largest with the two extremes at the ends, matching
    // iOS's option list.
    val options = listOf(
        MAX_FILE_NO_FILES to stringResource(R.string.backup_max_file_no_files),
        1 to stringResource(R.string.backup_max_file_mb, 1),
        2 to stringResource(R.string.backup_max_file_mb, 2),
        5 to stringResource(R.string.backup_max_file_mb, 5),
        10 to stringResource(R.string.backup_max_file_mb, 10),
        50 to stringResource(R.string.backup_max_file_mb, 50),
        100 to stringResource(R.string.backup_max_file_mb, 100),
        500 to stringResource(R.string.backup_max_file_mb, 500),
        MAX_FILE_UNLIMITED to stringResource(R.string.backup_max_file_unlimited),
    )
    Column {
        // Circular gray badge to match iOS's doc.zipper circle (and the other
        // backup rows' circular badges), rather than the shared SettingsRow's
        // rounded square.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (enabled) Modifier.clickable { expanded = true } else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(30.dp).background(Color(0xFF8E8E93), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.FolderZip, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(
                stringResource(R.string.backup_max_file_size),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            // The current value + a compact menu anchored to it (alignEnd), so
            // the popup reads as "expanded from this row" near the value, not a
            // near-fullscreen sheet. Mirrors iOS's tight native Picker.
            Box {
                // Value + an up/down chevron. Without the glyph the row reads
                // as a static label showing a setting, giving nothing away
                // that tapping it opens a picker — the same affordance iOS
                // gets for free from a native Picker's chevron.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Outlined.UnfoldMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                MinisMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    alignEnd = true,
                    offset = DpOffset(0.dp, 4.dp),
                ) {
                    options.forEach { (value, text) ->
                        DropdownMenuItem(
                            text = { Text(text) },
                            trailingIcon = if (value == current) {
                                { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null,
                            onClick = { onSelect(value); expanded = false },
                        )
                    }
                }
            }
        }
    }
}

// ─── Restore tab ─────────────────────────────────────────────────────────

@Composable
private fun RestoreTab(
    vm: BackupViewModel,
    onBrowseDestination: (String) -> Unit = {},
    onChooseRestoreServer: () -> Unit = {},
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
) {
    val running by vm.isRunning.collectAsState()
    val status by vm.statusText.collectAsState()
    val pending by vm.pending.collectAsState()
    val restoreSelected by vm.restoreSelected.collectAsState()
    val report by vm.report.collectAsState()
    val error by vm.errorText.collectAsState()

    // Which destination the user is browsing for a package to restore from.
    var browsing by remember {
        mutableStateOf<com.openminis.app.backup.remote.RcloneRemoteStore.Remote?>(null)
    }
    val destinations by vm.destinations.collectAsState()
    LaunchedEffect(Unit) { vm.refreshDestinations() }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.loadPackage(uri) }

    // Report replaces everything once a restore finishes.
    if (report != null) {
        RestoreReport(report!!, onDone = { vm.dismissReport() })
        return
    }

    if (pending == null) {
        // [T-android-restore-destinations] The configured destinations, listed
        // here rather than hidden behind a picker dialog. iOS shows them on
        // the restore screen for the obvious reason: the backup a user wants
        // is almost always on a server they already set up, so making that the
        // first thing on the screen — instead of a button that opens a list —
        // removes a step from the common path.
        // [T-android-backup-local-folder] Server destinations only. Browsing a
        // destination goes through rclone, which has no backend for a
        // SAF-picked folder — listing one here would hand the user a row that
        // opens an empty browser. Restoring FROM a local folder is already
        // covered, and better, by "Choose Backup File…" below: the system
        // picker reaches every folder on the device, not just the ones that
        // happen to be registered as backup destinations.
        val serverDestinations = destinations.filterNot {
            com.openminis.app.backup.remote.RcloneRemoteStore.isLocalFolder(it.backend)
        }
        if (serverDestinations.isNotEmpty()) {
            SettingsSection(
                header = stringResource(R.string.backup_restore_destinations),
                footer = stringResource(R.string.backup_restore_destinations_footer),
            ) {
                serverDestinations.forEachIndexed { i, r ->
                    RestoreSourceRow(
                        icon = Icons.Outlined.Cloud,
                        iconColor = Color(0xFFAF52DE),
                        label = r.name,
                        subtitle = stringResource(
                            R.string.backup_dest_row_subtitle,
                            r.backend.uppercase(),
                            r.path,
                        ),
                        enabled = !running,
                        onClick = { onBrowseDestination(r.name) },
                        showDivider = i < serverDestinations.lastIndex,
                    )
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.backup_restore_other_sources),
            footer = stringResource(R.string.backup_choose_file_footer),
        ) {
            // Two sources, matching iOS: local file / configured rclone servers.
            // There is deliberately no "Choose from Shared Folders…" row: the
            // system document picker below already reaches those folders (and
            // every other document provider), so a second, narrower entry point
            // was pure redundancy on the restore screen. The underlying shared
            // folder support is untouched — only this duplicate entry is gone.
            RestoreSourceRow(
                icon = Icons.Outlined.Description,
                iconColor = Color(0xFF007AFF),
                label = if (running) status ?: stringResource(R.string.backup_reading)
                else stringResource(R.string.backup_choose_file),
                enabled = !running,
                onClick = { pickLauncher.launch(arrayOf("*/*")) },
                showDivider = true,
            )
            RestoreSourceRow(
                icon = Icons.Outlined.Cloud,
                iconColor = Color(0xFFAF52DE),
                label = stringResource(R.string.backup_choose_server),
                enabled = !running,
                // [T-android-restore-server-list] ALWAYS the same destination:
                // a "Restore from Server" list page. It used to branch on
                // whether any server existed — a dialog when there was one,
                // and with none a dialog whose entire content was "no servers
                // configured" over a lone Cancel: a dead end that named the
                // problem and then refused to solve it, on the one screen
                // where the user had already said what they wanted.
                //
                // One unconditional destination is what makes the empty state
                // stop being special. That page lists the servers with an Add
                // Server entry always at the end, so "none configured" is just
                // the case where the list happens to be empty — the entry the
                // user needs is in the same place either way. Matches iOS.
                onClick = onChooseRestoreServer,
                showDivider = false,
            )
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        // Tapped straight from the destinations list above — same picker, but
        // with the server already chosen.
        browsing?.let { r ->
            ServerPackagePicker(
                vm = vm,
                remote = r,
                onDismiss = { browsing = null; vm.clearServerPackages() },
            )
        }
        return
    }

    val p = pending!!
    // Summary
    SettingsSection(header = stringResource(R.string.backup_section_backup)) {
        Column(Modifier.padding(16.dp)) {
            // The data cut-off, when the package records one. Distinct from
            // "created": a run that took minutes has a snapshot older than its
            // file, and which one you are looking at matters when deciding
            // whether a backup is recent enough to restore from.
            p.manifest.snapshotAt
                ?.takeIf { it.isNotEmpty() && it != p.manifest.createdAt }
                ?.let { InfoLine(stringResource(R.string.backup_info_snapshot), it) }
            InfoLine(
                stringResource(R.string.backup_info_created),
                p.manifest.createdAt.ifEmpty { stringResource(R.string.backup_info_unknown) },
            )
            InfoLine(
                stringResource(R.string.backup_info_from),
                "${p.manifest.deviceName} · ${p.manifest.app.platform} ${p.manifest.app.version}",
            )
            InfoLine(
                stringResource(R.string.backup_info_encrypted),
                if (p.manifest.encryption != null) stringResource(R.string.backup_yes)
                else stringResource(R.string.backup_no),
            )
        }
    }

    // Category selection with per-category counts from the manifest.
    SettingsSection(
        header = stringResource(R.string.backup_section_restore),
        footer = stringResource(R.string.backup_restore_footer),
    ) {
        // Declaration order (BackupCategory.entries), NOT key-alphabetical, so
        // the Restore list matches the Backup tab and iOS's
        // availableCategories(_:) which filters allCases in declaration order.
        val cats = BackupCategory.entries.filter { it in p.availableCategories }
        cats.forEachIndexed { i, cat ->
            val stat = p.manifest.categories[cat.key]
            CategorySwitchRow(
                title = stringResource(categoryNameRes(cat)),
                subtitle = stat?.let { categoryCountLabel(cat, it) },
                icon = categoryIcon(cat),
                iconColor = categoryTint(cat),
                checked = cat in restoreSelected,
                onCheckedChange = { vm.toggleRestoreCategory(cat, it) },
                enabled = !running,
                showDivider = i < cats.lastIndex,
            )
        }
    }

    // Passphrase (only if encrypted).
    if (p.manifest.encryption != null) {
        SettingsSection(
            footer = stringResource(R.string.backup_restore_passphrase_footer),
        ) {
            Column(Modifier.padding(16.dp)) {
                // [T-android-backup-passphrase-keyboard] (GH#345) Same
                // treatment as the backup side — see the comment there for why
                // the keyboard type matters beyond the masking.
                var revealRestorePassphrase by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = onPassphraseChange,
                    label = { Text(stringResource(R.string.backup_passphrase)) },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (revealRestorePassphrase) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { revealRestorePassphrase = !revealRestorePassphrase },
                            enabled = !running,
                        ) {
                            Icon(
                                if (revealRestorePassphrase) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = stringResource(
                                    if (revealRestorePassphrase) R.string.backup_hide_passphrase
                                    else R.string.backup_show_passphrase,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    Column(Modifier.padding(16.dp)) {
        // [T-android-restore-ui] The button and its subtitle live in their own
        // composable so that progress — which ticks every 200 records — only
        // recomposes THEM.
        //
        // They used to read `vm.restoreProgress` from RestoreTab's top level,
        // which put every category switch, text field and card on this screen
        // in the same recomposition scope as a counter updating several times
        // a second. Moving the collect down here is the whole fix; the
        // subtitle reads a separate flow for the same reason.
        RestoreProgressSection(
            vm = vm,
            running = running,
            enabled = !running && restoreSelected.isNotEmpty(),
            statusText = status,
            onStart = { vm.startRestore(passphrase.takeIf { p.manifest.encryption != null }) },
        )
        MinisOutlinedButton(
            onClick = { vm.cancelRestore(); onPassphraseChange("") },
            enabled = !running,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text(stringResource(R.string.backup_choose_different)) }
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

@Composable
private fun RestoreReport(
    report: com.openminis.app.backup.BackupImporter.Report,
    onDone: () -> Unit,
) {
    SettingsSection(header = stringResource(R.string.backup_restore_complete)) {
        Column(Modifier.padding(16.dp)) {
            InfoLine(stringResource(R.string.backup_report_restored), report.totalImported.toString())
            InfoLine(stringResource(R.string.backup_report_updated), report.totalUpdated.toString())
            InfoLine(stringResource(R.string.backup_report_up_to_date), report.totalSkipped.toString())
            if (report.totalMissingBlobs > 0) {
                InfoLine(stringResource(R.string.backup_report_missing_files), report.totalMissingBlobs.toString())
            }
        }
    }

    // Per-category outcomes. The totals above say a restore had issues; these
    // say WHICH data was affected and why, which is the difference between
    // "something went wrong" and knowing whether it matters. Each line names
    // the category, as on iOS.
    //
    // [T-android-restore-report-severity] Split by severity. Everything used
    // to render in the error colour, so a file the SOURCE device deliberately
    // left out — over the size cap, or never downloaded there — read as if the
    // restore had broken. Those are the backup's own policy, recorded as
    // tombstones in the index, and they get their own amber section.
    //
    // Which counter is which severity is decided in restoreIssues(), not here,
    // so the rule is tested as data rather than inferred from colours.
    val issues = restoreIssues(report)
    val lines = issues.map { issue ->
        val name = BackupCategory.fromKey(issue.categoryKey)
            ?.let { stringResource(categoryNameRes(it)) } ?: issue.categoryKey
        issue.severity to when (issue.kind) {
            RestoreIssueKind.FAILED ->
                stringResource(R.string.backup_report_failed, name, issue.detail.orEmpty())
            RestoreIssueKind.MISSING_FROM_PACKAGE ->
                stringResource(R.string.backup_report_missing_from_package, name, issue.count)
            RestoreIssueKind.UNREADABLE ->
                stringResource(R.string.backup_report_unreadable, name, issue.count)
            RestoreIssueKind.SIZE_SKIPPED ->
                stringResource(R.string.backup_report_size_skipped, name, issue.count)
            RestoreIssueKind.NOT_DOWNLOADED ->
                stringResource(R.string.backup_report_not_downloaded, name, issue.count)
        }
    }
    val errors = lines.filter { it.first == RestoreIssueSeverity.ERROR }.map { it.second }
    // report.warnings are advisories (count mismatches and the like), already
    // sentences rather than counts; they read as notices, not failures.
    val notices = lines.filter { it.first == RestoreIssueSeverity.NOTICE }.map { it.second } +
        report.warnings
    if (errors.isNotEmpty()) {
        SettingsSection(header = stringResource(R.string.backup_report_issues)) {
            Column(Modifier.padding(16.dp)) {
                errors.forEach {
                    ReportLine(it, Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (notices.isNotEmpty()) {
        SettingsSection(
            header = stringResource(R.string.backup_report_notices),
            footer = stringResource(R.string.backup_report_notices_footer),
        ) {
            Column(Modifier.padding(16.dp)) {
                val amber = reportNoticeColor()
                notices.forEach { ReportLine(it, Icons.Outlined.Warning, amber) }
            }
        }
    }

    // 3-way credentials message, keyed on the providers category (iOS parity).
    val prov = report.categories.firstOrNull {
        it.category == BackupCategory.PROVIDERS.key
    }
    if (prov != null) {
        val msg = when {
            prov.credentialsRestored > 0 ->
                stringResource(R.string.backup_creds_restored, prov.credentialsRestored)
            prov.credentialsKept > 0 ->
                stringResource(R.string.backup_creds_kept)
            else ->
                stringResource(R.string.backup_creds_none)
        }
        SettingsSection(footer = msg) {}
    }

    Column(Modifier.padding(16.dp)) {
        MinisButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.backup_done))
        }
    }
}

// ─── Restore sources ─────────────────────────────────────────────────────

/**
 * [T-android-backup-destination-gate] The destinations a backup will be
 * delivered to, listed inline on the Backup tab. Mirrors iOS
 * `BackupSettingsView.destinationSection`.
 *
 * Android previously surfaced destinations only behind a "Manage Destinations…"
 * button, so the screen never showed whether any existed — a user with none
 * configured saw a normal-looking Start button, got "Backup ready", and ended
 * up with a package that never left the app sandbox.
 */
@Composable
private fun DestinationsSection(
    destinations: List<com.openminis.app.backup.remote.RcloneRemoteStore.Remote>,
    enabled: Boolean,
    onManage: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    SettingsSection(
        header = stringResource(R.string.backup_section_destinations),
        footer = stringResource(
            if (destinations.isEmpty()) R.string.backup_destinations_empty_footer
            else R.string.backup_destinations_footer,
        ),
    ) {
        destinations.forEach { remote ->
            DestinationRow(
                remote = remote,
                enabled = enabled,
                onToggle = { on -> onToggle(remote.name, on) },
                onClick = onManage,
            )
        }
        // ONE entry point, as on iOS: two buttons ("Add Folder" / "Add Server")
        // would ask the user to know which mechanism they wanted before they
        // knew what either did.
        RestoreSourceRow(
            icon = Icons.Outlined.Add,
            iconColor = Color(0xFF34C759),
            label = stringResource(R.string.backup_manage_destinations),
            enabled = enabled,
            onClick = onManage,
            showDivider = false,
        )
    }
}

/**
 * One destination: name over "BACKEND · /path", with a switch. Mirrors the
 * server rows in iOS `destinationSection`.
 *
 * The switch controls DELIVERY, not existence — disabling keeps the server and
 * its credential, so skipping one destination for a while costs nothing to
 * undo. That is also why disabled rows stay visible: hiding them would leave
 * the user unable to see the destination still existed, let alone switch it
 * back on.
 */
@Composable
private fun DestinationRow(
    remote: com.openminis.app.backup.remote.RcloneRemoteStore.Remote,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    // Material equivalents of the SF Symbols iOS picks per backend.
    val isLocal = com.openminis.app.backup.remote.RcloneRemoteStore
        .isLocalFolder(remote.backend)
    val icon = when {
        // [T-android-backup-local-folder] A folder icon, so the one
        // destination that is NOT a server is distinguishable at a glance
        // from the five that are.
        isLocal -> Icons.Outlined.Folder
        remote.backend == "smb" -> Icons.Outlined.Dns
        remote.backend == "webdav" -> Icons.Outlined.Cloud
        remote.backend == "sftp" -> Icons.Outlined.Terminal
        remote.backend == "s3" -> Icons.Outlined.Storage
        remote.backend == "ftp" -> Icons.Outlined.SwapVert
        else -> Icons.Outlined.Cloud
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                // 12dp, matching every other settings row — 10dp here left this
                // row 4dp shorter than its neighbours.
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(30.dp).background(Color(0xFF007AFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    remote.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        R.string.backup_dest_row_subtitle,
                        if (isLocal) stringResource(R.string.backup_dest_kind_local_folder)
                        else remote.backend.uppercase(),
                        remote.path,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            SettingsSwitch(checked = remote.enabled, onCheckedChange = onToggle, enabled = enabled)
        }
        Box(
            Modifier.fillMaxWidth().padding(start = 56.dp)
                .height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/** Label + right-aligned value, for the just-finished card. */
@Composable
private fun DetailValueRow(label: String, value: String, showDivider: Boolean) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showDivider) {
            Box(
                Modifier.fillMaxWidth().padding(start = 14.dp)
                    .height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

/** One destination's outcome in the just-finished card. */
@Composable
private fun ResultDestinationRow(
    outcome: BackupHistory.DestinationOutcome,
    showDivider: Boolean,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (outcome.succeeded) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                contentDescription = null,
                tint = if (outcome.succeeded) Color(0xFF34C759) else Color(0xFFFF9500),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(outcome.name, style = MaterialTheme.typography.bodyMedium)
                outcome.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (showDivider) {
            Box(
                Modifier.fillMaxWidth().padding(start = 44.dp)
                    .height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

/**
 * [T-android-backup-local-export] Save to Device / Share for a local-only run.
 * Two equal buttons side by side, matching iOS's "Share…" / "Save to Files…".
 */
@Composable
private fun LocalExportActions(
    saving: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        MinisOutlinedButton(
            onClick = onSave,
            enabled = !saving,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.backup_save_to_device))
        }
        MinisOutlinedButton(
            onClick = onShare,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.backup_share_package))
        }
    }
}

/** The Save to Device progress / outcome line, or null when idle. */
@Composable
private fun saveStateLine(
    state: com.openminis.app.backup.BackupRunController.SaveState?,
): Pair<String, Boolean>? = when (state) {
    null -> null
    is com.openminis.app.backup.BackupRunController.SaveState.Saving ->
        stringResource(R.string.backup_saving_to_device, state.percent) to false
    is com.openminis.app.backup.BackupRunController.SaveState.Saved ->
        stringResource(R.string.backup_saved_to_device, state.name) to false
    is com.openminis.app.backup.BackupRunController.SaveState.Failed ->
        stringResource(R.string.backup_save_failed, state.message) to true
}

/**
 * [T-android-backup-local-export] Hand the package to another app through the
 * system share sheet (Drive, a chat app, a file manager…).
 *
 * `application/octet-stream`, not [com.openminis.app.backup.BackupFormat.MIME_TYPE]:
 * a share target filters on the MIME type it declares, and almost none
 * declare `application/x-minisbak`, so the precise type would hide exactly
 * the apps a user wants to send a backup to. The `.minisbak` name travels
 * with the URI regardless. The grant is per-URI and read-only.
 */
private fun shareBackupPackage(context: android.content.Context, file: java.io.File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_TITLE, file.name)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(send, file.name)
        if (context !is android.app.Activity) chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }.onFailure {
        com.openminis.app.logging.AppLogger.error("BackupScreen", "[Backup] share failed: ${it.message}")
        android.widget.Toast.makeText(
            context, context.getString(R.string.backup_share_failed), android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}

/** Where the package ended up, in words. */
@Composable
private fun backupResultFooter(r: com.openminis.app.backup.BackupRunController.RunResult): String {
    // [T-android-backup-local-export] A local-only run is not "saved": say so
    // until the user has actually put it somewhere outside the app.
    if (r.destinations.isEmpty()) {
        return r.savedAs?.let { stringResource(R.string.backup_saved_to_device, it) }
            ?: stringResource(R.string.backup_result_local_pending)
    }
    val ok = r.destinations.count { it.succeeded }
    // Once every destination is verified the local package is deleted, so the
    // footer has to say that rather than claim a copy is still on the device.
    if (r.localCopyRemoved) {
        return stringResource(R.string.backup_result_delivered_cleaned, r.destinations.size)
    }
    // State the local copy explicitly: a user whose NAS was offline needs to
    // see the backup still exists somewhere.
    return stringResource(R.string.backup_result_delivered, ok, r.destinations.size)
}

/** A tappable icon row for one restore source (iOS: circular-icon rows). */
@Composable
// [T-android-restore-server-list] internal so RestoreServersScreen renders
// its rows with the same shape as the restore sources they follow from.
internal fun RestoreSourceRow(
    icon: ImageVector,
    iconColor: Color,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    showDivider: Boolean,
    subtitle: String? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(30.dp).background(iconColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                // Where this destination points, so two servers on the same
                // backend are told apart before the user taps into one.
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(start = 58.dp, end = 14.dp)
                    .height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

/**
 * [T-android-restore-destinations] Packages on ONE already-chosen destination.
 *
 * Picking WHICH server happens before this, on the Restore-from-Server list
 * ([RestoreServersScreen]); by the time this renders the server is known, so
 * asking again would answer a question the user just answered. This is the
 * single package picker for every route in — the destinations list on the
 * restore tab, and the list page's rows.
 */
@Composable
private fun ServerPackagePicker(
    vm: BackupViewModel,
    remote: com.openminis.app.backup.remote.RcloneRemoteStore.Remote,
    onDismiss: () -> Unit,
) {
    val serverPackages by vm.serverPackages.collectAsState()
    val running by vm.isRunning.collectAsState()
    val status by vm.statusText.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(remote.name) },
        text = {
            when {
                running -> Text(status ?: stringResource(R.string.backup_reading))
                serverPackages.isEmpty() ->
                    Text(stringResource(R.string.backup_server_no_packages))
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    serverPackages.forEach { pkg ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        R.string.backup_pkg_row,
                                        pkg.displayName,
                                        humanBytes(pkg.size),
                                    ),
                                )
                            },
                            onClick = { vm.downloadServerPackage(pkg, remote); onDismiss() },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_dest_cancel)) }
        },
    )
}

// ─── Helpers ─────────────────────────────────────────────────────────────

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    }
}

/**
 * A settings switch row whose leading icon is a CIRCLE, not the shared
 * SettingsRow's rounded-square. iOS deliberately uses a circular badge for the
 * backup category list (BackupCategoryIcon.swift), distinct from the
 * rounded-square used elsewhere; this reproduces that without altering the
 * shared SettingsRow that the rest of Android settings depends on. Layout
 * (heights, paddings, divider inset) mirrors SettingsRow so it sits flush with
 * any sibling rows.
 */
@Composable
private fun CategorySwitchRow(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(
                    if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(30.dp).background(iconColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            SettingsSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 58.dp, end = 14.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

/**
 * [T-android-restore-progress-counts] Display name for a raw category KEY.
 *
 * The importer reports keys, not enum cases, so an unknown key from a newer
 * writer falls back to the key itself rather than crashing or showing blank.
 */
@Composable
internal fun categoryLabel(key: String): String =
    BackupCategory.fromKey(key)?.let { stringResource(categoryNameRes(it)) } ?: key

/** Localized display name per category. */
internal fun categoryNameRes(cat: BackupCategory): Int = when (cat) {
    BackupCategory.CHATS -> R.string.backup_category_chats
    BackupCategory.SHARED_FILES -> R.string.backup_category_shared_files
    BackupCategory.SKILLS -> R.string.backup_category_skills
    BackupCategory.MEMORY -> R.string.backup_category_memory
    BackupCategory.PROVIDERS -> R.string.backup_category_providers
    BackupCategory.MCP_SERVERS -> R.string.backup_category_mcp
    BackupCategory.ENVIRONMENT_VARIABLES -> R.string.backup_category_env_vars
    BackupCategory.VOICE_CORRECTIONS -> R.string.backup_category_voice
}

/**
 * Circular colored category icon — the Android counterpart of iOS
 * `BackupCategoryIcon` (same symbol intent + tint per category, using the
 * nearest Compose Material icon).
 */
private fun categoryIcon(cat: BackupCategory): ImageVector = when (cat) {
    BackupCategory.CHATS -> Icons.Outlined.Forum                 // bubble.left.and.bubble.right
    BackupCategory.SHARED_FILES -> Icons.Outlined.Description    // doc.fill
    BackupCategory.SKILLS -> Icons.Outlined.Extension            // puzzlepiece.fill
    BackupCategory.MEMORY -> Icons.Outlined.Psychology           // brain.head.profile
    BackupCategory.PROVIDERS -> Icons.Outlined.Link              // link
    BackupCategory.MCP_SERVERS -> Icons.Outlined.Layers          // square.stack.3d.up.fill
    BackupCategory.ENVIRONMENT_VARIABLES -> Icons.Outlined.Terminal // terminal.fill
    BackupCategory.VOICE_CORRECTIONS -> Icons.Outlined.RecordVoiceOver // waveform
}

/** Tint per category, mirroring iOS BackupCategoryIcon.tint(for:). */
private fun categoryTint(cat: BackupCategory): Color = when (cat) {
    BackupCategory.CHATS -> Color(0xFF007AFF)                 // blue
    BackupCategory.SHARED_FILES -> Color(0xFF5856D6)         // indigo
    BackupCategory.SKILLS -> Color(0xFFFF9500)              // orange
    BackupCategory.MEMORY -> Color(0xFFFF2D55)             // pink
    BackupCategory.PROVIDERS -> Color(0xFF30B0C7)         // teal
    BackupCategory.MCP_SERVERS -> Color(0xFF32ADE6)      // cyan
    BackupCategory.ENVIRONMENT_VARIABLES -> Color(0xFFA2845E) // brown
    BackupCategory.VOICE_CORRECTIONS -> Color(0xFFAF52DE) // purple
}

/**
 * Name the UNIT, not just the number.
 *
 * Every category used to render through `backup_count_items` ("N 项"), so one
 * label covered messages, skills, servers and providers alike and the reader
 * had no way to tell what was being counted — which is how "569 items" for a
 * dozen skills read as corruption.
 *
 * Each branch degrades to the generic label when its detail field is absent,
 * so a package written before those fields existed still renders (with the
 * old, less specific wording) instead of showing a wrong unit.
 */
@Composable
private fun categoryCountLabel(
    cat: BackupCategory,
    stat: com.openminis.app.backup.BackupManifest.CategoryStat,
): String = when {
    cat == BackupCategory.CHATS && stat.messages != null ->
        stringResource(
            R.string.backup_count_chats,
            stat.messages ?: 0, stat.files ?: 0, humanBytes(stat.bytes),
        )

    cat == BackupCategory.SKILLS && stat.files != null ->
        stringResource(
            R.string.backup_count_skills,
            stat.entries, stat.files ?: 0, humanBytes(stat.bytes),
        )

    cat == BackupCategory.MCP_SERVERS ->
        stringResource(R.string.backup_count_servers, stat.entries, humanBytes(stat.bytes))

    cat == BackupCategory.PROVIDERS && (stat.thinkingRules ?: 0) > 0 ->
        stringResource(
            R.string.backup_count_providers_rules,
            stat.entries, stat.thinkingRules ?: 0, humanBytes(stat.bytes),
        )

    cat == BackupCategory.PROVIDERS ->
        stringResource(R.string.backup_count_providers, stat.entries, humanBytes(stat.bytes))

    else -> stringResource(R.string.backup_count_items, stat.entries, humanBytes(stat.bytes))
}

internal fun humanBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1e6)
    bytes >= 1_000 -> String.format("%.0f KB", bytes / 1e3)
    else -> "$bytes B"
}

// Max Per-File Size sentinel tags, matching iOS noFilesTag / unlimitedTag.
internal const val MAX_FILE_NO_FILES = -1
internal const val MAX_FILE_UNLIMITED = 0

/**
 * [T-android-restore-ui] The restore button, its progress ring, and the
 * remaining-time line under it.
 *
 * Exists as its own composable purely to bound recomposition: progress ticks
 * every 200 records, and reading it from RestoreTab's top level put the whole
 * settings form — every category switch, the passphrase field, the cards — in
 * the same scope as a counter that moves several times a second.
 */
@Composable
private fun RestoreProgressSection(
    vm: BackupViewModel,
    running: Boolean,
    enabled: Boolean,
    statusText: String?,
    onStart: () -> Unit,
) {
    val progress by vm.restoreProgress.collectAsState()
    val etaSeconds by vm.restoreEtaSeconds.collectAsState()
    var confirmStop by rememberSaveable { mutableStateOf(false) }

    val p = progress
    val total = p?.total?.takeIf { it > 0 }
    val fraction = if (p != null && total != null) {
        (p.done.toFloat() / total).coerceIn(0f, 1f)
    } else {
        null
    }

    val label = when {
        !running -> stringResource(R.string.backup_restore)
        p == null -> stringResource(R.string.backup_restoring)
        else -> {
            val name = categoryLabel(p.categoryKey)
            when {
                // Percent first: on a package with hundreds of thousands of
                // records the raw pair is too long to read at a glance, and
                // "36%" answers "how far along" without arithmetic.
                total != null && p.done > 0 -> stringResource(
                    R.string.backup_restoring_percent,
                    name,
                    (p.done.toLong() * 100 / total).toInt().coerceIn(0, 100),
                    p.done,
                    total,
                )
                p.done > 0 -> stringResource(R.string.backup_restoring_running, name, p.done)
                else -> stringResource(R.string.backup_restoring_category, name)
            }
        }
    }

    MinisButton(
        onClick = onStart,
        // While a restore runs the button itself is inert — the only live
        // control is the stop target in the middle of the ring, so a stray tap
        // on the bar cannot start or abort anything.
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        PrimaryActionContent(
            icon = Icons.Outlined.CloudDownload,
            label = label,
            busy = running,
            fraction = fraction,
            onStopClick = { confirmStop = true }.takeIf { running },
        )
    }

    if (running) {
        // The step line and the estimate share one row: they answer the same
        // question ("what is happening, and for how much longer") and stacking
        // them would push the layout around as the estimate appears.
        val eta = RestoreEta.bucket(etaSeconds)?.let { bucket ->
            when (bucket) {
                is RestoreEta.EtaBucket.AlmostDone ->
                    stringResource(R.string.restore_eta_almost_done)
                is RestoreEta.EtaBucket.Seconds ->
                    stringResource(R.string.restore_eta_seconds, bucket.seconds)
                is RestoreEta.EtaBucket.MinutesSeconds ->
                    stringResource(R.string.restore_eta_minutes, bucket.minutes, bucket.seconds)
            }
        }
        val line = listOfNotNull(statusText, eta).joinToString(" · ")
        if (line.isNotEmpty()) {
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.restore_stop_title)) },
            text = { Text(stringResource(R.string.restore_stop_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    vm.stopRunningRestore()
                }) {
                    Text(
                        stringResource(R.string.restore_stop_confirm),
                        // Destructive: this abandons work in progress.
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) {
                    Text(stringResource(R.string.restore_stop_cancel))
                }
            },
        )
    }
}

/**
 * [T-android-restore-report-severity] One restore-report line with a leading
 * status icon, both drawn in [tint] so the icon and the words agree on
 * severity.
 *
 * Material icons rather than emoji: an emoji draws in its own fixed colours,
 * so a warning glyph could not follow the amber below into dark mode, and an
 * error glyph would not match the theme's error red.
 */
@Composable
private fun ReportLine(text: String, icon: ImageVector, tint: Color) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            // Nudged down to sit on the first text line rather than
            // centring on a paragraph that may wrap.
            modifier = Modifier.padding(top = 1.dp).size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/**
 * [T-android-restore-report-severity] Amber for "left out on purpose".
 *
 * Two values because no single amber works on both grounds at body-text size.
 * The commonly used #E6A23C measures 2.19:1 against a light surface — well
 * under the 4.5:1 small text needs — so it would be close to unreadable in
 * light mode. #B45309 gives 5.02:1 on light; #FFB74D gives 9.83:1 on dark.
 *
 * Keyed on ChatColors.isDark, the theme the app actually resolved, so it
 * follows the in-app Appearance setting rather than the OS one (GH#187).
 */
@Composable
private fun reportNoticeColor(): Color =
    if (ChatColors.isDark) {
        Color(0xFFFFB74D)
    } else {
        Color(0xFFB45309)
    }
