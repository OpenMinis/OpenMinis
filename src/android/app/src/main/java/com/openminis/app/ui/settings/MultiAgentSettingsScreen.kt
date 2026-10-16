package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.MultiAgentSettings
import com.openminis.app.provider.effectiveMaxThinkingLevel

@Composable
fun MultiAgentSettingsScreen(
    onBack: () -> Unit,
    onOpenToolLimits: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as MinisApp
    val repo = app.multiAgentSettingsRepository
    val providerRepo = app.providerRepository

    val enabled by repo.enabled.collectAsState()
    val maxConcurrent by repo.maxConcurrent.collectAsState()
    // Turn budget is coordinator-assigned (1.27 unclamp); only the retry-attempts
    // stepper still reads a repo StateFlow here.
    val subagentMaxAttempts by repo.subagentMaxAttempts.collectAsState()
    val selectedIds by repo.selectedModelEntryIds.collectAsState()
    val selectedThinkingLevels by repo.selectedThinkingLevels.collectAsState()
    val config by providerRepo.config.collectAsState()
    val configLoaded by providerRepo.configLoaded.collectAsState()

    val instancesById = config.instances.associateBy { it.id }
    val candidates = config.modelEntries.filter { entry ->
        !entry.isHidden && instancesById[entry.providerInstanceId]?.isEnabled == true
    }
    val candidateIds = remember(candidates) { candidates.map { it.id }.toSet() }
    val slots = remember(selectedIds, maxConcurrent) {
        MultiAgentSettings.resizeSlots(selectedIds, maxConcurrent)
    }
    val staleCount = selectedIds.count { it.isNotBlank() && it !in candidateIds }

    LaunchedEffect(configLoaded, candidateIds) {
        if (configLoaded) repo.retainLiveEntries(candidateIds)
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_multi_agent),
        onBack = null,
    ) {
        SettingsSection(
            header = stringResource(R.string.settings_multi_agent_section_dispatch),
            footer = stringResource(R.string.settings_multi_agent_footer_dispatch),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_multi_agent_enable),
                subtitle = stringResource(R.string.settings_multi_agent_enable_subtitle),
                checked = enabled,
                onCheckedChange = { repo.setEnabled(it) },
                icon = Icons.Outlined.Groups,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        stringResource(R.string.settings_multi_agent_max),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.settings_multi_agent_max_subtitle, maxConcurrent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PlusMinusStepper(
                    value = maxConcurrent,
                    min = MultiAgentSettings.MIN_CONCURRENT,
                    max = MultiAgentSettings.MAX_CONCURRENT,
                    onValueChange = { repo.setMaxConcurrent(it) },
                    decreaseContentDescription = stringResource(R.string.settings_multi_agent_decrease),
                    increaseContentDescription = stringResource(R.string.settings_multi_agent_increase),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        stringResource(R.string.settings_multi_agent_attempts),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.settings_multi_agent_attempts_subtitle, subagentMaxAttempts),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PlusMinusStepper(
                    value = subagentMaxAttempts,
                    min = MultiAgentSettings.MIN_SUBAGENT_ATTEMPTS,
                    max = MultiAgentSettings.MAX_SUBAGENT_ATTEMPTS,
                    onValueChange = { repo.setSubagentMaxAttempts(it) },
                    decreaseContentDescription = stringResource(R.string.settings_multi_agent_decrease),
                    increaseContentDescription = stringResource(R.string.settings_multi_agent_increase),
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.settings_multi_agent_section_models),
            footer = stringResource(R.string.settings_multi_agent_footer_models, maxConcurrent),
        ) {
            if (candidates.isEmpty()) {
                Text(
                    stringResource(R.string.settings_multi_agent_no_models),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            if (staleCount > 0) {
                Text(
                    stringResource(R.string.settings_multi_agent_stale_pruned, staleCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            slots.forEachIndexed { index, slotId ->
                SubAgentSlotRow(
                    index = index,
                    selectedId = slotId,
                    thinkingLevel = selectedThinkingLevels.getOrNull(index)?.takeIf { it.isNotBlank() },
                    candidates = candidates,
                    instancesById = instancesById,
                    enabled = enabled && candidates.isNotEmpty(),
                    showDivider = index < slots.lastIndex,
                    onSelect = { repo.setSlotModel(index, it) },
                    onSelectThinking = { repo.setSlotThinkingLevel(index, it.name) },
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.settings_collab_roles_section),
            footer = stringResource(R.string.settings_collab_roles_footer),
        ) {
            var editingRole by remember { mutableStateOf<com.openminis.app.tools.CollabRoles.Role?>(null) }
            var showAddDialog by remember { mutableStateOf(false) }
            var showMindMapDialog by remember { mutableStateOf(false) }
            var showMindMapOverwriteDialog by remember { mutableStateOf(false) }
            var mindMapDraft by remember { mutableStateOf("") }
            var mindMapStatus by remember { mutableStateOf<String?>(null) }
            val importedTeam = remember(mindMapDraft) {
                com.openminis.app.tools.MindMapTeamImport.parse(mindMapDraft)
            }
            val importedRoles = remember(mindMapDraft) {
                com.openminis.app.tools.CollabRoles.importTeamFromMindMap(mindMapDraft)
            }
            val roles = com.openminis.app.tools.CollabRoles.allWithCustom(context)
            roles.forEachIndexed { index, role ->
                SettingsRow(
                    title = role.name + if (role.builtin) "" else " *",
                    subtitle = role.description +
                        stringResource(R.string.ma_role_tools_suffix, role.tools.sorted().take(6).joinToString()),
                    showChevron = !role.builtin,
                    onClick = if (role.builtin) null else ({ editingRole = role }),
                    showDivider = index < roles.lastIndex,
                )
            }
            SettingsRow(
                title = stringResource(R.string.settings_collab_roles_import_mindmap),
                subtitle = stringResource(R.string.settings_collab_roles_import_mindmap_subtitle),
                showChevron = true,
                onClick = {
                    mindMapDraft = ""
                    mindMapStatus = null
                    showMindMapDialog = true
                },
                showDivider = true,
            )
            mindMapStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            SettingsRow(
                title = stringResource(R.string.ma_add_role),
                subtitle = stringResource(R.string.ma_add_role_sub),
                showChevron = false,
                onClick = { showAddDialog = true },
                showDivider = false,
            )
            if (showMindMapDialog && !showMindMapOverwriteDialog) {
                MindMapTeamImportDialog(
                    raw = mindMapDraft,
                    onRawChange = { mindMapDraft = it },
                    team = importedTeam,
                    roles = importedRoles,
                    onDismiss = { showMindMapDialog = false },
                    onRequestImport = {
                        val customNames = com.openminis.app.tools.CollabRoles.loadCustom(context)
                            .map { it.name.trim().lowercase() }
                            .toSet()
                        val conflicts = importedRoles.map { it.name }
                            .filter { it.trim().lowercase() in customNames }
                        if (conflicts.isEmpty()) {
                            val merged = com.openminis.app.tools.CollabRoles.mergeImportedCustom(
                                com.openminis.app.tools.CollabRoles.loadCustom(context),
                                importedRoles,
                            )
                            com.openminis.app.tools.CollabRoles.saveCustom(context, merged)
                            mindMapStatus = context.getString(
                                R.string.settings_collab_roles_import_done,
                                importedRoles.size,
                            )
                            showMindMapDialog = false
                        } else {
                            showMindMapOverwriteDialog = true
                        }
                    },
                )
            }
            if (showMindMapOverwriteDialog) {
                AlertDialog(
                    onDismissRequest = { showMindMapOverwriteDialog = false },
                    title = { Text(stringResource(R.string.settings_collab_roles_import_overwrite_title)) },
                    text = {
                        val customNames = com.openminis.app.tools.CollabRoles.loadCustom(context)
                            .map { it.name.trim().lowercase() }
                            .toSet()
                        val conflicts = importedRoles.map { it.name }
                            .filter { it.trim().lowercase() in customNames }
                            .distinct()
                        Text(
                            stringResource(
                                R.string.settings_collab_roles_import_overwrite_text,
                                conflicts.joinToString(),
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val merged = com.openminis.app.tools.CollabRoles.mergeImportedCustom(
                                com.openminis.app.tools.CollabRoles.loadCustom(context),
                                importedRoles,
                            )
                            com.openminis.app.tools.CollabRoles.saveCustom(context, merged)
                            mindMapStatus = context.getString(
                                R.string.settings_collab_roles_import_done,
                                importedRoles.size,
                            )
                            showMindMapOverwriteDialog = false
                            showMindMapDialog = false
                        }) {
                            Text(stringResource(R.string.settings_collab_roles_import_replace))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showMindMapOverwriteDialog = false }) {
                            Text(stringResource(R.string.settings_collab_roles_import_cancel))
                        }
                    },
                )
            }
            if (editingRole != null || showAddDialog) {
                CollabRoleEditDialog(
                    initial = editingRole,
                    onDismiss = { editingRole = null; showAddDialog = false },
                    onSave = { saved ->
                        val customs = com.openminis.app.tools.CollabRoles
                            .loadCustom(context).filter { it.name != saved.name }
                        com.openminis.app.tools.CollabRoles.saveCustom(context, customs + saved)
                        editingRole = null
                        showAddDialog = false
                    },
                    onDelete = if (editingRole?.builtin == false) ({
                        val target = editingRole!!
                        val customs = com.openminis.app.tools.CollabRoles
                            .loadCustom(context).filter { it.name != target.name }
                        com.openminis.app.tools.CollabRoles.saveCustom(context, customs)
                        editingRole = null
                    }) else null,
                )
            }
        }

        // [T-multiagent-page-cleanup] The "工具权限模式" row used to live
        // here: it displayed the gate's permission mode but jumped to
        // OffloadPermissionScreen (Shizuku/offload), which never edits that
        // mode — the real edit point is the in-chat approval UI
        // (setSessionPermissionMode). A jump whose destination cannot change
        // the displayed value violates this section's own footer contract
        // ("只跳到拥有该设置的那一页"), so the row is gone. The read-only
        // "子代理类型" mirror section is gone too: dispatch_agents' tool
        // description already enumerates the types for the model, and the
        // store has no save() caller — a static catalog is documentation, not
        // a setting.
        SettingsSection(footer = stringResource(R.string.settings_shared_controls_footer)) {
            SettingsRow(
                title = stringResource(R.string.settings_open_tool_limits),
                subtitle = stringResource(R.string.settings_open_tool_limits_sub),
                onClick = onOpenToolLimits,
                showDivider = false,
            )
        }
    }
}

@Composable
private fun MindMapTeamImportDialog(
    raw: String,
    onRawChange: (String) -> Unit,
    team: com.openminis.app.tools.MindMapTeamImport.ImportedTeam?,
    roles: List<com.openminis.app.tools.CollabRoles.Role>,
    onDismiss: () -> Unit,
    onRequestImport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_collab_roles_import_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = raw,
                    onValueChange = onRawChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_collab_roles_import_hint)) },
                    placeholder = {
                        Text(
                            """mindmap
                              root((产品团队))
                                PM[产品经理]
                                QA[测试工程师]""".trimIndent(),
                        )
                    },
                    minLines = 5,
                    maxLines = 8,
                )
                if (roles.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_collab_roles_import_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = buildString {
                            append(stringResource(R.string.settings_collab_roles_import_preview_count, roles.size))
                            team?.roomName?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    team?.blurb?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    roles.forEachIndexed { index, role ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(
                                text = role.name + if (team?.members?.firstOrNull { it.name == role.name }?.isSubTeamLead == true) stringResource(R.string.ma_team_lead_suffix) else "",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = role.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (index < roles.lastIndex) {
                            androidx.compose.material3.HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = roles.isNotEmpty(), onClick = onRequestImport) {
                Text(stringResource(R.string.settings_collab_roles_import_confirm, roles.size))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_collab_roles_import_cancel))
            }
        },
    )
}

@Composable
private fun SubAgentSlotRow(
    index: Int,
    selectedId: String,
    thinkingLevel: String?,
    candidates: List<ModelEntry>,
    instancesById: Map<String, ProviderInstance>,
    enabled: Boolean,
    showDivider: Boolean,
    onSelect: (String) -> Unit,
    onSelectThinking: (ThinkingLevel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedEntry = candidates.find { it.id == selectedId }
    val value = selectedEntry?.let { slotModelLabel(it, instancesById) }
        ?: stringResource(R.string.settings_multi_agent_slot_main)
    val maxThinking = selectedEntry?.effectiveMaxThinkingLevel ?: ThinkingLevel.OFF
    val selectedThinking = thinkingLevel?.let {
        runCatching { ThinkingLevel.valueOf(it.uppercase()) }.getOrNull()
    }?.let { if (it.rank <= maxThinking.rank) it else maxThinking } ?: maxThinking
    var thinkingExpanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Column {
            SettingsValueRow(
                title = stringResource(R.string.settings_multi_agent_slot, index + 1),
                value = value,
                onClick = if (enabled) ({ expanded = true }) else null,
                showDivider = true,
            )
            SettingsValueRow(
                title = stringResource(R.string.ma_thinking_depth),
                value = selectedThinking.displayName,
                onClick = if (enabled && maxThinking != ThinkingLevel.OFF) ({ thinkingExpanded = true }) else null,
                showDivider = showDivider,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_multi_agent_slot_main)) },
                onClick = {
                    onSelect("")
                    expanded = false
                },
            )
            candidates.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(slotModelLabel(entry, instancesById)) },
                    onClick = {
                        onSelect(entry.id)
                        expanded = false
                    },
                )
            }
        }
        DropdownMenu(
            expanded = thinkingExpanded,
            onDismissRequest = { thinkingExpanded = false },
        ) {
            ThinkingLevel.values()
                .filter { it != ThinkingLevel.OFF && it.rank <= maxThinking.rank }
                .forEach { level ->
                    DropdownMenuItem(
                        text = { Text(level.displayName) },
                        onClick = {
                            onSelectThinking(level)
                            thinkingExpanded = false
                        },
                    )
                }
        }
    }
}

private fun slotModelLabel(
    entry: ModelEntry,
    instancesById: Map<String, ProviderInstance>,
): String = buildString {
    append(entry.model.displayName)
    instancesById[entry.providerInstanceId]?.label?.takeIf { it.isNotBlank() }?.let {
        append(" · ").append(it)
    }
}

@Composable
private fun CollabRoleEditDialog(
    initial: com.openminis.app.tools.CollabRoles.Role?,
    onDismiss: () -> Unit,
    onSave: (com.openminis.app.tools.CollabRoles.Role) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val builtin = initial?.builtin == true
    // builtin roles are copied into a custom draft on tap; customs edit in place
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var description by remember(initial) { mutableStateOf(initial?.description ?: "") }
    var prompt by remember(initial) { mutableStateOf(initial?.prompt ?: "") }
    var toolsCsv by remember(initial) { mutableStateOf(initial?.tools?.sorted()?.joinToString() ?: "") }
    val valid = name.isNotBlank() && prompt.isNotBlank()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (builtin) stringResource(R.string.ma_role_dialog_title_builtin) else if (initial == null) stringResource(R.string.ma_role_dialog_title_new) else stringResource(R.string.ma_role_dialog_title_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ma_role_name_label)) },
                    singleLine = true,
                    readOnly = builtin,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.ma_role_desc_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.ma_role_prompt_label)) },
                    minLines = 4,
                    maxLines = 8,
                )
                OutlinedTextField(
                    value = toolsCsv,
                    onValueChange = { toolsCsv = it },
                    label = { Text(stringResource(R.string.ma_role_tools_label)) },
                    minLines = 2,
                    maxLines = 3,
                )
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.ma_role_delete), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val toolSet = toolsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                onSave(
                    com.openminis.app.tools.CollabRoles.Role(
                        name = name.trim(),
                        description = description.trim(),
                        prompt = prompt.trim(),
                        tools = if (toolSet.isEmpty()) com.openminis.app.tools.CollabRoles.ALL.first().tools else toolSet,
                        builtin = false,
                    ),
                )
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
