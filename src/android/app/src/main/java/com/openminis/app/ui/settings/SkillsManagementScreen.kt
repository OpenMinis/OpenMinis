package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.openminis.app.ui.components.DialogTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.ui.markdown.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.openminis.app.i18n.uppercaseForDisplay

/**
 * Inline state for the three update actions on [SkillDetailScreen]
 * (T154). Drives the status row right under the buttons so the user
 * sees a spinner / stringResource(R.string.skill_detail_updated) / failure reason without leaving the
 * detail screen.
 */
private sealed class UpdateStatus {
    data object Idle : UpdateStatus()
    data class InProgress(val label: String) : UpdateStatus()
    data object Done : UpdateStatus()
    data class Failed(val reason: String) : UpdateStatus()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsManagementScreen(
    skillRepository: SkillRepository,
    onBack: () -> Unit,
    onSkillClick: (String) -> Unit = {},
    onMinisSkillsClick: () -> Unit = {},
) {
    val skills by skillRepository.skills.collectAsState()
    var showImportSheet by remember { mutableStateOf(false) }
    var deleteSkillId by remember { mutableStateOf<String?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current

    // Sort preference — persisted like the file browser's (SharedPreferences,
    // default name ascending). Mirrors iOS skillsList.sortKey/sortAscending.
    val sortPrefs = remember {
        context.getSharedPreferences("skills_prefs", android.content.Context.MODE_PRIVATE)
    }
    var sortByModified by remember { mutableStateOf(sortPrefs.getBoolean("sort_by_modified", false)) }
    var sortAscending by remember { mutableStateOf(sortPrefs.getBoolean("sort_ascending", true)) }

    val filteredSkills by remember(skills) {
        derivedStateOf {
            val q = searchQuery.trim().lowercase()
            val base = if (q.isEmpty()) skills
            else skills.filter {
                it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
            }
            val sorted = if (sortByModified) base.sortedBy { it.updatedAt }
            else base.sortedBy { it.name.lowercase() }
            if (sortAscending) sorted else sorted.reversed()
        }
    }

    // T-skillscan: rescan on screen entry so a skill that was installed in
    // the active chat (e.g. via shell `git clone` into skillsDir, which
    // bypasses the SKILL.md file_write hook) is picked up the moment the
    // user opens this list, instead of requiring a process restart.
    LaunchedEffect(Unit) {
        skillRepository.reloadFromDisk()
    }

    SettingsScaffold(
        title = stringResource(R.string.skill_title),
        onBack = onBack,
        // T75-part1 already moved Add off the FAB onto the top-bar action
        // slot; kept here for visual continuity with the rest of Settings.
        actions = {
            // Sort menu — same structure as the file browser's MoreMenu
            // (sort-key items with check marks, divider, direction toggle).
            Box {
                var sortMenuExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { sortMenuExpanded = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        contentDescription = stringResource(R.string.filebrowser_sort_by),
                    )
                }
                com.openminis.app.ui.components.MinisMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.filebrowser_sort_name)) },
                        leadingIcon = {
                            if (!sortByModified) Icon(Icons.Filled.Check, contentDescription = null)
                            else Spacer(modifier = Modifier.size(24.dp))
                        },
                        onClick = {
                            sortByModified = false
                            sortPrefs.edit().putBoolean("sort_by_modified", false).apply()
                            sortMenuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.filebrowser_sort_modified)) },
                        leadingIcon = {
                            if (sortByModified) Icon(Icons.Filled.Check, contentDescription = null)
                            else Spacer(modifier = Modifier.size(24.dp))
                        },
                        onClick = {
                            sortByModified = true
                            sortPrefs.edit().putBoolean("sort_by_modified", true).apply()
                            sortMenuExpanded = false
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(
                                if (sortAscending) R.string.filebrowser_sort_ascending
                                else R.string.filebrowser_sort_descending
                            ))
                        },
                        leadingIcon = {
                            Icon(
                                if (sortAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            sortAscending = !sortAscending
                            sortPrefs.edit().putBoolean("sort_ascending", sortAscending).apply()
                            sortMenuExpanded = false
                        },
                    )
                }
            }
            IconButton(onClick = { showAddMenu = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.skill_add))
            }
        },
    ) {
        // Search bar — filters by skill name + description. Sits above the
        // section so it's discoverable without scrolling on a long list.
        if (skills.isNotEmpty()) {
            DialogTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = stringResource(R.string.skills_search_placeholder),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp),
            )
        }

        SettingsSection(
            header = stringResource(R.string.skill_section_installed),
            footer = stringResource(R.string.skill_section_footer),
        ) {
            if (skills.isEmpty()) {
                // Centred empty-state inside the section card so the empty
                // state is visually owned by the card.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    )
                    Text(
                        stringResource(R.string.skill_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.skill_empty_action),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (filteredSkills.isEmpty()) {
                Text(
                    text = stringResource(R.string.skills_search_no_match, searchQuery),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                )
            } else {
                filteredSkills.forEachIndexed { index, skill ->
                    val (badgeIcon, badgeColor) = sourceIconAndColor(skill.importSource)
                    SettingsRow(
                        title = skill.name,
                        subtitle = skill.description.takeIf { it.isNotEmpty() }?.let { stripMarkdown(it) },
                        showChevron = true,
                        showDivider = index < filteredSkills.size - 1,
                        onClick = { onSkillClick(skill.id) },
                        trailing = {
                            // Source badge + Switch. Wrapping in a Row keeps
                            // them visually grouped before the row's chevron.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    badgeIcon,
                                    contentDescription = null,
                                    tint = badgeColor,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                SettingsSwitch(
                                    checked = skill.isEnabled,
                                    onCheckedChange = { skillRepository.setEnabled(skill.id, it) },
                                )
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showAddMenu) {
        ModalBottomSheet(
            onDismissRequest = { showAddMenu = false },
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAddMenu = false
                            showImportSheet = true
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.skill_import_modal_title), style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAddMenu = false
                            onMinisSkillsClick()
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Language, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.skill_minis_skills_modal), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    if (showImportSheet) {
        SkillImportSheet(
            skillRepository = skillRepository,
            onDismiss = { showImportSheet = false },
        )
    }

    if (deleteSkillId != null) {
        val skill = skills.find { it.id == deleteSkillId }
        AlertDialog(
            onDismissRequest = { deleteSkillId = null },
            title = { Text("Delete ${skill?.name ?: "skill"}?") },
            text = { Text(stringResource(R.string.skill_delete_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    deleteSkillId?.let { skillRepository.delete(it) }
                    deleteSkillId = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                MinisTextButton(onClick = { deleteSkillId = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

// ─── Import Sheet (3 modes: URL / Paste / File) ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillImportSheet(
    skillRepository: SkillRepository,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var urlText by remember { mutableStateOf("") }
    var pasteContent by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // File picker
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            // [T-android-skill-import-zip] Read raw bytes so we can branch on
            // file magic. The previous code piped the InputStream straight
            // through `bufferedReader().readText()`, which corrupted any
            // .zip / .skill bundle into garbage UTF-8 and surfaced as
            // "Invalid SKILL.md content". Skills shared as bundles
            // (scripts/, references/, assets/) are the common case on iOS;
            // matching here aligns Android with iOS
            // `SkillsManagementView.documentPicker` + `SkillStore.importFromArchive`.
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                errorText = "Failed to read file"
                return@rememberLauncherForActivityResult
            }
            // PK\x03\x04 = standard ZIP local file header; PK\x05\x06 is the
            // empty-archive EOCD; PK\x07\x08 is a spanned/split archive. Accept
            // all three so split exports from less-common zip tools don't fall
            // into the text path. URI may not preserve the filename extension
            // (some DocumentsProviders / share targets strip it), so magic-byte
            // sniffing is more reliable than `.zip` / `.skill` checks.
            val isZip = bytes.size >= 4 &&
                bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
                (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())
            if (isZip) {
                val result = skillRepository.importFromArchive(
                    java.io.ByteArrayInputStream(bytes),
                )
                if (result != null) onDismiss()
                else errorText = "Invalid skill archive — no SKILL.md found at the root or one directory deep"
            } else {
                val content = String(bytes, Charsets.UTF_8)
                val result = skillRepository.importFromContent(content, SkillRepository.ImportSource.FILE)
                if (result != null) onDismiss()
                else errorText = "Invalid SKILL.md content"
            }
        } catch (e: Exception) {
            errorText = "Failed to read file: ${e.message}"
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.skill_import_modal_title), style = MaterialTheme.typography.titleMedium)

            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0; errorText = null }) {
                    Text(stringResource(R.string.skill_import_tab_url), modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1; errorText = null }) {
                    Text(stringResource(R.string.skill_import_tab_paste), modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2; errorText = null }) {
                    Text(stringResource(R.string.skill_import_tab_file), modifier = Modifier.padding(12.dp))
                }
            }

            when (selectedTab) {
                0 -> {
                    Text(
                        stringResource(R.string.skill_import_url_instruction),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    DialogTextField(
                        value = urlText,
                        onValueChange = { urlText = it; errorText = null },
                        placeholder = stringResource(R.string.skill_import_url_placeholder),
                        singleLine = true,
                        isError = errorText != null,
                    )
                }
                1 -> {
                    Text(
                        stringResource(R.string.skill_import_paste_instruction),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    DialogTextField(
                        value = pasteContent,
                        onValueChange = { pasteContent = it; errorText = null },
                        modifier = Modifier.height(200.dp),
                        singleLine = false,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        isError = errorText != null,
                    )
                }
                2 -> {
                    Text(
                        stringResource(R.string.skill_import_file_instruction),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MinisTextButton(
                        onClick = { fileLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.skill_import_file_button)) }
                }
            }

            if (errorText != null) {
                Text(errorText!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (selectedTab < 2) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    MinisTextButton(
                        onClick = {
                            when (selectedTab) {
                                0 -> {
                                    if (urlText.isBlank()) { errorText = context.getString(R.string.skill_import_error_no_url); return@MinisTextButton }
                                    isLoading = true
                                    scope.launch {
                                        try {
                                            // importFromGitHub fetches SKILL.md AND recurses through
                                            // the GitHub Contents API so bundled scripts/references
                                            // land on disk. The old downloadSkillFromUrl +
                                            // importFromContent path only grabbed SKILL.md, leaving
                                            // skills like douyin-downloader non-runnable because the
                                            // sibling python scripts never made it to local storage.
                                            val result = skillRepository.importFromGitHub(urlText.trim())
                                            if (result != null) onDismiss() else errorText = context.getString(R.string.skill_import_error_invalid)
                                        } catch (e: Exception) { errorText = "Error: ${e.message}" }
                                        finally { isLoading = false }
                                    }
                                }
                                1 -> {
                                    val result = skillRepository.importFromContent(pasteContent)
                                    if (result != null) onDismiss()
                                    else errorText = context.getString(R.string.skill_import_error_format)
                                }
                            }
                        },
                        enabled = when (selectedTab) { 0 -> urlText.isNotBlank() && !isLoading; 1 -> pasteContent.isNotBlank(); else -> false },
                    ) { Text(if (isLoading) stringResource(R.string.skill_import_in_progress) else stringResource(R.string.skill_import_submit)) }
                }
            }
        }
    }
}

private fun downloadSkillFromUrl(url: String): String? {
    val rawUrl = if (url.contains("github.com") && url.contains("/blob/")) {
        url.replace("github.com", "raw.githubusercontent.com").replace("/blob/", "/")
    } else if (url.contains("github.com") && !url.contains("raw.githubusercontent.com")) {
        val withFile = if (!url.endsWith("SKILL.md")) "$url/SKILL.md" else url
        withFile.replace("github.com", "raw.githubusercontent.com").replace("/blob/", "/").replace("/tree/", "/")
    } else url

    val connection = java.net.URL(rawUrl).openConnection() as java.net.HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    return try { if (connection.responseCode == 200) connection.inputStream.bufferedReader().readText() else null }
    finally { connection.disconnect() }
}

// ─── Skill Detail Sheet ─────────────────────────────────────────────────────

// ─── Skill Detail Screen (full page, matching iOS) ──────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillDetailScreen(
    skillId: String,
    skillRepository: SkillRepository,
    onBack: () -> Unit,
    onFileClick: (String, String) -> Unit = { _, _ -> },
) {
    val skills by skillRepository.skills.collectAsState()
    val skill = skills.find { it.id == skillId }
    var deleted by remember { mutableStateOf(false) }

    if (skill == null && !deleted) { onBack(); return }
    if (skill == null) return // Already deleted, just stop rendering

    var showDeleteDialog by remember { mutableStateOf(false) }
    // T155: name editing now happens in a modal dialog instead of an inline
    // text field — matches the rest of the app's "edit a single string"
    // affordances and avoids the layout jiggle the inline OutlinedTextField
    // caused on a row that's otherwise quiet.
    var showEditNameDialog by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(skill.name) }

    // T154: replace stub state. Each update action drives `updateStatus` which
    // surfaces inline feedback ("Updating…" / stringResource(R.string.skill_detail_updated) / "Failed: …") next to
    // the button row, clearing itself ~3 s after a terminal result so a slow
    // user can still read it without leaving a permanent banner.
    var updateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Idle) }
    // [T-android-skill-export] Guards the share button while the zip is being
    // built off the main thread, so a double-tap can't spawn two exports.
    var isExporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(updateStatus) {
        if (updateStatus is UpdateStatus.Done || updateStatus is UpdateStatus.Failed) {
            kotlinx.coroutines.delay(3000)
            updateStatus = UpdateStatus.Idle
        }
    }
    val fileUpdateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        updateStatus = UpdateStatus.InProgress(context.getString(R.string.skill_detail_status_reading))
        try {
            val content = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
            if (content.isNullOrBlank()) {
                updateStatus = UpdateStatus.Failed(context.getString(R.string.skill_detail_error_empty))
            } else {
                // Re-parse the supplied SKILL.md so we can push name +
                // description + body into the existing skill record without
                // creating a new entry.
                val parsed = skillRepository.parseSkillMdPublic(content)
                if (parsed == null) {
                    updateStatus = UpdateStatus.Failed(context.getString(R.string.skill_detail_error_invalid))
                } else {
                    val ok = skillRepository.update(
                        id = skill.id,
                        name = parsed.name,
                        description = parsed.description,
                        body = parsed.body,
                    )
                    updateStatus = if (ok) UpdateStatus.Done else UpdateStatus.Failed(context.getString(R.string.skill_detail_error_update_failed))
                }
            }
        } catch (e: Exception) {
            updateStatus = UpdateStatus.Failed("Failed to read file: ${e.message ?: "unknown"}")
        }
    }

    val sourceLabel = when (skill.importSource) {
        SkillRepository.ImportSource.URL -> stringResource(R.string.skill_import_tab_url)
        SkillRepository.ImportSource.FILE -> stringResource(R.string.skill_detail_source_file)
        SkillRepository.ImportSource.BUNDLED -> stringResource(R.string.skill_detail_source_bundled)
        SkillRepository.ImportSource.SESSION -> stringResource(R.string.skill_detail_source_session)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(skill.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // [T-android-skill-export] Export the skill as a .zip and
                    // hand it to the system share sheet — the round-trip
                    // partner of the existing zip import, and the Android
                    // equivalent of iOS's top-bar share button.
                    IconButton(
                        enabled = !isExporting,
                        onClick = {
                            isExporting = true
                            scope.launch {
                                val zip = withContext(Dispatchers.IO) {
                                    skillRepository.exportSkillToZip(skill.id)
                                }
                                isExporting = false
                                if (zip == null) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.skill_export_failed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    shareSkillZip(context, zip)
                                }
                            }
                        },
                    ) {
                        Icon(
                            Icons.Default.IosShare,
                            contentDescription = stringResource(R.string.skill_export_share),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Meta Section ──
            DetailSection {
                // Name (tap pencil → edit dialog, T155)
                DetailRow {
                    Text(stringResource(R.string.skill_detail_label_name), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
                    Spacer(Modifier.weight(1f))
                    Text(skill.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Edit, contentDescription = "Edit name",
                        modifier = Modifier.size(14.dp).clickable {
                            editName = skill.name
                            showEditNameDialog = true
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                DetailDivider()

                // Version
                DetailRow {
                    Text(stringResource(R.string.skill_detail_label_version), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
                    Spacer(Modifier.weight(1f))
                    Text(skill.version, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DetailDivider()

                // Last Modified
                DetailRow {
                    Text(stringResource(R.string.skill_detail_label_modified), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
                    Spacer(Modifier.weight(1f))
                    Text(relativeTime(skill.updatedAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DetailDivider()

                // Source
                DetailRow {
                    Text(stringResource(R.string.skill_detail_label_source), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
                    Spacer(Modifier.weight(1f))
                    Text(sourceLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── Update Actions Section ──
            // T154: every row now drives `updateStatus`. The repository APIs
            // already exist (updateFromURL / update / rescanFromDisk); the
            // previous TODOs and the "always show Done" flag were stubs.
            val isBusy = updateStatus is UpdateStatus.InProgress
            DetailSection {
                if (skill.importSource == SkillRepository.ImportSource.URL) {
                    DetailRow(clickable = !isBusy, onClick = {
                        if (isBusy) return@DetailRow
                        updateStatus = UpdateStatus.InProgress(context.getString(R.string.skill_detail_status_downloading))
                        scope.launch {
                            updateStatus = when (val r = skillRepository.updateFromURL(skill.id)) {
                                is SkillRepository.UpdateResult.Success -> UpdateStatus.Done
                                // T152: SKILL.md updated but sibling-file recursion
                                // failed (e.g. GitHub API 403 rate limit). Surface
                                // the actual cause so the user knows scripts/ may
                                // be incomplete instead of a silent green check.
                                is SkillRepository.UpdateResult.PartialSuccess -> UpdateStatus.Failed("Updated, but: ${r.reason}")
                                is SkillRepository.UpdateResult.Failure -> UpdateStatus.Failed(r.reason)
                            }
                        }
                    }) {
                        SettingsActionIcon(Icons.Default.Refresh, SettingsIconBlue)
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(R.string.skill_detail_update_url), color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        Text(relativeTime(skill.updatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DetailDivider()
                }

                DetailRow(clickable = !isBusy, onClick = {
                    if (isBusy) return@DetailRow
                    fileUpdateLauncher.launch("*/*")
                }) {
                    SettingsActionIcon(Icons.Outlined.Description, SettingsIconBlue)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.skill_detail_update_file), color = MaterialTheme.colorScheme.primary)
                }
                DetailDivider()

                DetailRow(clickable = !isBusy, onClick = {
                    if (isBusy) return@DetailRow
                    updateStatus = UpdateStatus.InProgress(context.getString(R.string.skill_detail_status_rescanning))
                    val refreshed = skillRepository.rescanFromDisk(skill.id)
                    updateStatus = if (refreshed != null) UpdateStatus.Done
                        else UpdateStatus.Failed(context.getString(R.string.skill_detail_error_missing))
                }) {
                    SettingsActionIcon(Icons.Default.Refresh, SettingsIconGreen)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.skill_detail_rescan), color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                }

                // Inline status row — only rendered while there's something to
                // say. Holds for ~3 s on Done/Failed before LaunchedEffect
                // clears it back to Idle.
                when (val s = updateStatus) {
                    is UpdateStatus.InProgress -> {
                        DetailDivider()
                        DetailRow {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(s.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    is UpdateStatus.Done -> {
                        DetailDivider()
                        DetailRow {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF34C759), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.skill_detail_updated), style = MaterialTheme.typography.bodySmall, color = Color(0xFF34C759))
                        }
                    }
                    is UpdateStatus.Failed -> {
                        DetailDivider()
                        DetailRow {
                            Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(s.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    UpdateStatus.Idle -> Unit
                }
            }

            // ── Description Section ──
            val previewLines = skill.body.lineSequence().filter { it.isNotBlank() }.take(5).toList()
            val hasMore = skill.body.lines().count { it.isNotBlank() } > 5
            val preview = previewLines.joinToString("\n") + if (hasMore) "\n…" else ""

            if (preview.isNotEmpty()) {
                DetailSection(header = "Description") {
                    Column(modifier = Modifier.padding(14.dp)) {
                        MarkdownText(
                            markdown = preview,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // ── Files Section ──
            // Re-list whenever the skill record changes (rescan/update bumps
            // updatedAt, so newly-extracted scripts appear without leaving the
            // screen).
            val skillFiles = remember(skill.id, skill.updatedAt) {
                skillRepository.listSkillFiles(skill.id)
                    .ifEmpty { listOf("SKILL.md") }
            }
            DetailSection(header = "Files") {
                skillFiles.forEachIndexed { index, relativePath ->
                    if (index > 0) DetailDivider()
                    DetailRow(clickable = true, onClick = { onFileClick(skill.id, relativePath) }) {
                        Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            relativePath,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // ── Delete ──
            Spacer(Modifier.height(16.dp))
            MinisButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.skill_detail_delete))
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete ${skill.name}?") },
            text = { Text(stringResource(R.string.skill_delete_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    deleted = true
                    skillRepository.delete(skill.id)
                    showDeleteDialog = false
                    onBack()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // T155: rename-skill dialog. Save is disabled while the user has cleared
    // the name or hasn't actually changed it — both would no-op the
    // repository call, so removing the affordance keeps the UX honest.
    if (showEditNameDialog) {
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.skill_detail_edit_name_title)) },
            text = {
                DialogTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                val trimmed = editName.trim()
                val canSave = trimmed.isNotEmpty() && trimmed != skill.name
                MinisTextButton(
                    onClick = {
                        if (canSave) skillRepository.update(skill.id, name = trimmed)
                        showEditNameDialog = false
                    },
                    enabled = canSave,
                ) { Text(stringResource(R.string.skill_file_save)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showEditNameDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/** A grouped card section matching iOS List section style. */
@Composable
private fun DetailSection(
    header: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
    ) {
        if (header != null) {
            Text(
                text = header.uppercaseForDisplay(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            content()
        }
    }
}

@Composable
private fun DetailRow(
    clickable: Boolean = false,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun DetailDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

/**
 * [T-android-skill-export] Hand an exported skill zip to the system share
 * sheet. The file already lives under `cacheDir/share/` — a root declared in
 * `file_provider_paths.xml`, which FileProvider requires; a file outside a
 * declared root makes getUriForFile throw and the user only sees a failure.
 *
 * The zip is deliberately NOT deleted here. Consumers (Save to Files, Drive, a
 * chat app) read the URI asynchronously after the chooser closes, so deleting
 * on dismissal races them — the same trap iOS documented. SkillRepository
 * sweeps exports older than 24h before each new export instead.
 */
private fun shareSkillZip(context: android.content.Context, zip: java.io.File) {
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", zip,
        )
    } catch (t: Throwable) {
        Toast.makeText(context, context.getString(R.string.skill_export_failed), Toast.LENGTH_SHORT).show()
        return
    }
    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, zip.name)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = android.content.Intent.createChooser(
        sendIntent,
        context.getString(R.string.skill_export_share),
    ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(chooser) }.onFailure {
        Toast.makeText(context, context.getString(R.string.skill_export_failed), Toast.LENGTH_SHORT).show()
    }
}

/** [T-android-skill-icon-circular] The same iOS-system-palette values the main
 *  Settings rows use (SettingsScreen): blue for navigational/remote actions,
 *  green for local filesystem operations. */
private val SettingsIconBlue = Color(0xFF007AFF)
private val SettingsIconGreen = Color(0xFF34C759)

/**
 * [T-android-skill-icon-circular] A colored circular badge with a white glyph
 * centered inside, for the skill detail ACTION rows (Update from URL / Update
 * from file / Rescan).
 *
 * Spec is copied VERBATIM from the main Settings page rows
 * (SettingsScreen's row icon: a 30dp circle filled with `iconColor`, holding a
 * 16dp white glyph, followed by a 14dp gap) — keep the two surfaces identical;
 * don't retune one without the other. Mirrors iOS `SettingsActionIcon`
 * (d431cfc7), which copied its own Settings spec the same way.
 *
 * Before this, the action rows used a bare 18dp tinted glyph with no container,
 * so they read as a different visual language from every other settings surface.
 */
@Composable
private fun SettingsActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(color = iconColor, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Strip Markdown syntax for display in compact UI surfaces (subtitle rows
 * etc.) where a single-line plain-text preview is wanted. Handles fenced
 * code blocks, inline code, links, headings, blockquotes, bold, italic,
 * and collapses any remaining whitespace/newlines into single spaces.
 * Output is trimmed and capped at 120 characters.
 */
private fun stripMarkdown(text: String): String {
    return text
        .replace(Regex("```[\\s\\S]*?```"), "")           // fenced code blocks
        .replace(Regex("`([^`]+)`"), "$1")                  // inline code
        .replace(Regex("!?\\[([^\\]]+)\\]\\([^)]+\\)"), "$1") // [text](url) → text
        .replace(Regex("^#+ ", RegexOption.MULTILINE), "") // headings
        .replace(Regex("^> ", RegexOption.MULTILINE), "")  // blockquote markers
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")       // **bold**
        .replace(Regex("__([^_]+)__"), "$1")                // __bold__
        .replace(Regex("\\*([^*]+)\\*"), "$1")             // *italic*
        .replace(Regex("_([^_]+)_"), "$1")                  // _italic_
        .replace(Regex("\\s+"), " ")                       // collapse whitespace + newlines
        .trim()
        .take(120)
}

/** Format millis as relative time string (matching iOS). */
private fun relativeTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hr ago"
        days < 30 -> "$days days ago"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
    }
}

// ─── Skill File Viewer Screen (full page, matching iOS) ─────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillFileViewerScreen(
    skillId: String,
    relativePath: String = "SKILL.md",
    skillRepository: SkillRepository,
    onBack: () -> Unit,
) {
    val skills by skillRepository.skills.collectAsState()
    val skill = skills.find { it.id == skillId }
    if (skill == null) { onBack(); return }

    val isSkillMd = relativePath.equals("SKILL.md", ignoreCase = true)
    val fileName = relativePath.substringAfterLast('/')

    // For SKILL.md, reconstruct from the in-memory record (so frontmatter
    // edits stay in sync with DB metadata). For sibling files, read straight
    // from disk.
    val initialContent = remember(skillId, relativePath, skill.updatedAt) {
        if (isSkillMd) {
            buildString {
                appendLine("---")
                appendLine("name: ${skill.name}")
                appendLine("version: ${skill.version}")
                if (skill.description.isNotEmpty()) {
                    appendLine("description: ${skill.description}")
                }
                appendLine("---")
                appendLine()
                append(skill.body)
            }
        } else {
            skillRepository.readSkillFile(skillId, relativePath) ?: ""
        }
    }

    var isEditing by remember { mutableStateOf(false) }
    var editContent by remember(skillId, relativePath) { mutableStateOf(initialContent) }

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
                    if (isEditing) {
                        MinisTextButton(onClick = {
                            if (isSkillMd) {
                                // SKILL.md edits go through importFromContent so
                                // YAML frontmatter changes flow back into DB metadata.
                                skillRepository.importFromContent(editContent, skill.importSource)
                            } else {
                                skillRepository.writeSkillFile(skillId, relativePath, editContent)
                            }
                            isEditing = false
                            onBack()
                        }) { Text(stringResource(R.string.skill_file_save)) }
                    } else {
                        MinisTextButton(onClick = {
                            editContent = initialContent
                            isEditing = true
                        }) { Text(stringResource(R.string.skill_file_edit)) }
                    }
                },
            )
        },
    ) { padding ->
        if (isEditing) {
            DialogTextField(
                value = editContent,
                onValueChange = { editContent = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 8.dp),
                singleLine = false,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                item {
                    Text(
                        initialContent,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
