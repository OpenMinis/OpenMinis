package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.ui.components.DialogTextField

/**
 * Settings-level memory file management.
 * Lists GLOBAL.md + daily logs in grouped card style.
 * Tapping a file navigates to a full-page editor.
 * GLOBAL.md cannot be deleted.
 * Mirrors iOS MemoryManagementView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryManagementScreen(
    memoryRepository: MemoryRepository,
    onBack: () -> Unit,
    onFileClick: (fileName: String, isGlobal: Boolean) -> Unit = { _, _ -> },
) {
    var files by remember { mutableStateOf<List<MemoryRepository.MemoryFileInfo>>(emptyList()) }
    var deleteFileName by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // [T-memory-global-toggle-settings-ui-android] Global default for
    // newly-created sessions. Stored separately from per-session
    // memoryEnabled (which lives in the sessions DB row) so toggling
    // here never retroactively rewrites existing chats. Read once on
    // entry; the Switch's onCheckedChange writes back synchronously
    // and updates the local state mirror.
    var globalMemoryOn by remember {
        mutableStateOf(com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(context))
    }

    LaunchedEffect(Unit) {
        files = memoryRepository.listAllFiles()
    }

    SettingsScaffold(title = stringResource(R.string.memory_title), onBack = onBack) {
        // Always-visible global toggle — sits above the file list so the
        // user finds it whether or not any memory files exist yet.
        SettingsSection(
            header = stringResource(R.string.settings_memory_global_header),
            footer = stringResource(R.string.settings_memory_global_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_memory_global_enabled_title),
                subtitle = stringResource(R.string.settings_memory_global_enabled_subtitle),
                checked = globalMemoryOn,
                onCheckedChange = { newValue ->
                    globalMemoryOn = newValue
                    com.openminis.app.data.MemoryGlobalPrefs.setGlobalEnabled(context, newValue)
                },
                showDivider = false,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (files.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.memory_empty_title), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.memory_empty_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            SettingsSection(
                header = stringResource(R.string.memory_section_files),
                footer = stringResource(R.string.memory_section_footer),
            ) {
                files.forEachIndexed { index, file ->
                    MemoryFileRow(
                        file = file,
                        onClick = { onFileClick(file.name, file.isGlobal) },
                        onDelete = if (!file.isGlobal) { { deleteFileName = file.name } } else null,
                    )
                    if (index < files.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Delete confirmation
    if (deleteFileName != null) {
        AlertDialog(
            onDismissRequest = { deleteFileName = null },
            title = { Text(stringResource(R.string.memory_delete_confirm_title, deleteFileName ?: "")) },
            text = { Text(stringResource(R.string.memory_delete_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    deleteFileName?.let {
                        memoryRepository.deleteFile(it)
                        files = memoryRepository.listAllFiles()
                    }
                    deleteFileName = null
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { deleteFileName = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun MemoryFileRow(
    file: MemoryRepository.MemoryFileInfo,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // [T-android-memory-row-date-wrap] The name+size group flexes and
                // truncates; the date never wraps.
                //
                // Previously neither child had a weight, so the name group claimed
                // its full intrinsic width and the date was left with the
                // remainder. A daily-log name is itself a date ("2026-08-16.md"),
                // which is long enough that the 16-char "yyyy-MM-dd HH:mm" stamp no
                // longer fit — it wrapped to two lines and made that one row
                // visibly taller than its neighbours (user-reported).
                //
                // Giving the name group weight(1f, fill = false) lets it shrink
                // only as far as needed, and maxLines/ellipsis on the NAME means
                // the truncation lands on the element that can afford it. softWrap
                // = false on the date is the actual guarantee of a uniform row
                // height — the date is short, fixed-width, and the one thing that
                // must never reflow. Deliberately NOT solved by shortening the
                // format: the time is real information, and dropping it would only
                // move the threshold rather than remove it.
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (file.fileSize.isNotBlank()) {
                        Text(
                            file.fileSize,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    file.modifiedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (file.preview.isNotBlank()) {
                Text(
                    file.preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // [T-memory-file-delete-entrypoint] Daily logs are deletable per the
        // repository contract, but the row previously accepted onDelete and
        // never rendered it — the confirm dialog was unreachable, so users
        // could not delete memory files at all (iOS exposes this via
        // List .onDelete swipe). An always-visible trash button is the
        // Android-convention equivalent; GLOBAL.md passes onDelete = null
        // and keeps its chevron-only layout.
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Full-page memory file editor, matching iOS MemoryFileEditView.
 * Monospaced text. The Save button stays ALWAYS visible.
 *
 * [T-global-memory-save-always-visible] Previously the Save action was
 * gated on a `hasChanges` flag that only flipped true inside the field's
 * onValueChange. Programmatic content changes (paste, IME commit, state
 * restore) don't always route through onValueChange, so Save could fail
 * to appear after a paste until the user typed another key — the exact
 * symptom reported on iOS/macOS (XIN msg 41384). Keeping Save permanently
 * visible removes the dependency entirely; saveFile is idempotent so a
 * no-op Save on unchanged content is harmless.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryFileEditScreen(
    fileName: String,
    isGlobal: Boolean,
    memoryRepository: MemoryRepository,
    onBack: () -> Unit,
) {
    var content by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // [T-memory-save-toast-feedback] Confirm Save actually committed by
    // flashing a toast — previously the Save tap silently closed nothing,
    // showed no state change, and the user had no signal that the edit
    // landed (user-reported confusion). Reuses the existing
    // memory_save_toast string already wired for the per-chat memory
    // detail editor's SavedToast so the wording stays consistent.
    val savedToastText = stringResource(R.string.memory_save_toast)

    LaunchedEffect(fileName) {
        content = memoryRepository.readFile(fileName)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // [T-global-memory-save-always-visible] Always render Save —
                    // no hasChanges gate (see KDoc above).
                    MinisTextButton(onClick = {
                        try {
                            memoryRepository.saveFile(fileName, content)
                            saveError = null
                            android.widget.Toast.makeText(
                                context,
                                savedToastText,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } catch (e: Exception) {
                            saveError = e.message
                        }
                    }) {
                        Text("Save")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Editor
            DialogTextField(
                value = content,
                onValueChange = {
                    content = it
                },
                singleLine = false,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                placeholder = stringResource(R.string.memory_editor_placeholder),
                modifier = Modifier.weight(1f),
            )

            // Footer text
            if (isGlobal) {
                Text(
                    stringResource(R.string.memory_global_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (saveError != null) {
                Text(
                    saveError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
