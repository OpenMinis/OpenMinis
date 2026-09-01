package com.openminis.app.ui.settings

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.R
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import com.openminis.app.ui.components.SwipeRowAction
import com.openminis.app.ui.components.SwipeRowActions
import com.openminis.app.ui.components.SectionCard
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionDivider
import com.openminis.app.ui.components.SectionFooter
import com.openminis.app.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelGroupsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onGroupClick: (String) -> Unit,
    /** T185: navigate to the entries picker for the agent-loop set.
     *  Replaces T182's in-screen ModalBottomSheet so the picker shape
     *  matches AddModelsToGroupScreen (shared composable). */
    onAddAgentLoopModels: () -> Unit = {},
    /** T185: navigate to the groups picker for the agent-loop set. */
    onAddAgentLoopGroups: () -> Unit = {},
) {
    val config by providerRepository.config.collectAsState()
    val groups = config.modelGroups
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    // [T-android-swipe-row-actions] Pending swipe-delete target, hoisted out of
    // the row so the confirmation survives recomposition/reorder.
    var groupToDelete by remember { mutableStateOf<ModelGroup?>(null) }

    // T186: parent LazyListState shared with rememberReorderableLazyListState
    // so each agent-loop pinned row (rendered as its own LazyColumn item)
    // can carry a Modifier.draggableHandle and reorder live. The reorder
    // callback resolves the dragged row by its key (entry id / group id),
    // permutes our local copy, then commits via ProviderRepository.
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        // Two reorder zones share the same lazyListState — entries
        // (key prefix "agent_entry:") and groups ("agent_group:").
        // Refuse cross-zone drags so a user can't accidentally drop an
        // entry into the groups zone (the data shape would reject it
        // anyway, but a no-op is friendlier than a snap-back).
        when {
            fromKey.startsWith("agent_entry:") && toKey.startsWith("agent_entry:") -> {
                val fromId = fromKey.removePrefix("agent_entry:")
                val toId = toKey.removePrefix("agent_entry:")
                val cur = providerRepository.config.value.agentLoopModelEntryIds.toList()
                val fromIdx = cur.indexOf(fromId)
                val toIdx = cur.indexOf(toId)
                if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
                val newOrder = cur.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
                providerRepository.reorderAgentLoopEntries(newOrder)
            }
            fromKey.startsWith("agent_group:") && toKey.startsWith("agent_group:") -> {
                val fromId = fromKey.removePrefix("agent_group:")
                val toId = toKey.removePrefix("agent_group:")
                val cur = providerRepository.config.value.agentLoopGroupIds.toList()
                val fromIdx = cur.indexOf(fromId)
                val toIdx = cur.indexOf(toId)
                if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
                val newOrder = cur.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
                providerRepository.reorderAgentLoopGroups(newOrder)
            }
            // [T-android-modelgroup-reorder] Third zone: the user's Model
            // Groups themselves ("日常/编程/翻译"…). NOTE the prefix check
            // order is safe: "agent_group:" does not start with "group:", so
            // the zones cannot cross-match.
            fromKey.startsWith("group:") && toKey.startsWith("group:") -> {
                val fromId = fromKey.removePrefix("group:")
                val toId = toKey.removePrefix("group:")
                val cur = providerRepository.config.value.modelGroups.map { it.id }
                val fromIdx = cur.indexOf(fromId)
                val toIdx = cur.indexOf(toId)
                if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
                val newOrder = cur.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
                providerRepository.reorderModelGroups(newOrder)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_groups_model_groups)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.model_group_detail_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showNewGroupDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.model_groups_new_group))
                    }
                },
            )
        },
    ) { padding ->
        // T313: page background uses SectionDesign.screenBackgroundColor()
        // (= surfaceContainerLow), so the section cards painted in `surface`
        // visibly stand out as iOS-style inset-grouped panels.
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(SectionDesign.screenBackgroundColor()),
        ) {
            item("top_gap") { Spacer(modifier = Modifier.height(SectionDesign.FirstSectionTopGap)) }

            if (groups.isEmpty()) {
                item("empty_state") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.model_groups_no_model_groups),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.model_groups_groups_let_you_combine_models_for_fallba),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // T313 — Section 1 (Groups).
                //
                // [T-android-modelgroup-reorder] The section used to be ONE
                // LazyColumn item wrapping a SectionCard with a forEach inside
                // — which is exactly why the groups could not be reordered:
                // ReorderableLazyListState only sees direct LazyColumn items.
                // Rows are now individual items keyed "group:<id>" with the
                // per-row cardRow(first/last) treatment, the same composition
                // the agent-loop section below has used since T186. Swipe-to-
                // delete and tap-to-open are unchanged; dragging lives on the
                // explicit leading handle (consistent with AgentLoopRow, and
                // it keeps clickable/swipe gestures conflict-free).
                item("groups_section_header") {
                    SectionHeader(text = stringResource(R.string.agent_loop_models_groups))
                }
                itemsIndexed(groups, key = { _, g -> "group:${g.id}" }) { index, group ->
                    ReorderableItem(
                        state = reorderState,
                        key = "group:${group.id}",
                    ) { _ ->
                        Column {
                            if (index != 0) SectionDividerInsetCard()
                            Box(
                                modifier = Modifier.cardRow(
                                    isFirst = index == 0,
                                    isLast = index == groups.lastIndex,
                                ),
                            ) {
                                // [T-android-swipe-row-actions] Swipe left for
                                // Edit / Delete.
                                //
                                // This REPLACES a SwipeToDismissBox that called
                                // removeGroup() the instant the swipe crossed its
                                // threshold — no confirmation and no undo, so a
                                // stray horizontal gesture silently destroyed a
                                // group (and, via removeGroup, cleared it from
                                // every default slot that referenced it). Delete
                                // now routes through the SAME dialog copy and the
                                // same repository call as ModelGroupDetailScreen's
                                // "Delete Group" button; Edit opens that screen.
                                SwipeRowActions(
                                    actions = listOf(
                                        SwipeRowAction(
                                            label = stringResource(R.string.common_edit),
                                            icon = Icons.Filled.Edit,
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                            onClick = { onGroupClick(group.id) },
                                        ),
                                        SwipeRowAction(
                                            label = stringResource(R.string.common_delete),
                                            icon = Icons.Filled.Delete,
                                            containerColor = MaterialTheme.colorScheme.errorContainer,
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                            onClick = { groupToDelete = group },
                                        ),
                                    ),
                                ) {
                                    GroupRow(
                                        group = group,
                                        config = config,
                                        onClick = { onGroupClick(group.id) },
                                        dragHandleModifier = with(this@ReorderableItem) {
                                            Modifier.draggableHandle()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // T313 — Section 2 (Defaults). Two dropdown rows in one card
                // with SectionDivider between them, footer caption outside.
                item("defaults_section_spacer") {
                    Spacer(modifier = Modifier.height(SectionDesign.SectionTopGap))
                }
                item("defaults_section_header") {
                    SectionHeader(text = "Defaults")
                }
                item("defaults_section_card") {
                    SectionCard {
                        GroupDropdown(
                            label = "Default Primary",
                            groups = groups,
                            selectedId = config.defaultPrimaryGroupId,
                            onSelect = { providerRepository.defaultPrimaryGroupId = it },
                        )
                        SectionDivider()
                        GroupDropdown(
                            label = "Default Sub",
                            groups = groups,
                            selectedId = config.defaultSubGroupId,
                            onSelect = { providerRepository.defaultSubGroupId = it },
                        )
                        // [T-android-provider-voice] Voice group bindings —
                        // mirrors iOS voiceInputGroupId / voiceOutputGroupId.
                        SectionDivider()
                        GroupDropdown(
                            label = stringResource(R.string.model_groups_voice_input),
                            groups = groups,
                            selectedId = config.voiceInputGroupId,
                            onSelect = { providerRepository.voiceInputGroupId = it },
                        )
                        SectionDivider()
                        GroupDropdown(
                            label = stringResource(R.string.model_groups_voice_output),
                            groups = groups,
                            selectedId = config.voiceOutputGroupId,
                            onSelect = { providerRepository.voiceOutputGroupId = it },
                        )
                        // [T-android-vision-group / GH#182] Vision Group — the
                        // group whose vision-capable members read images for a
                        // main model that cannot natively see them.
                        SectionDivider()
                        GroupDropdown(
                            label = stringResource(R.string.model_groups_vision),
                            groups = groups,
                            selectedId = config.visionGroupId,
                            onSelect = { providerRepository.visionGroupId = it },
                        )
                    }
                }
                item("defaults_section_footer") {
                    SectionFooter(
                        text = stringResource(R.string.model_groups_primary_is_used_for_main_agent_tasks_sub),
                    )
                }
            }

            // T182 / T185 / T186: Agent Loop Models section. Mirrors iOS
            // ModelGroupsView.swift L77-79's inline AgentLoopModelsSection
            // — header + curated rows + add-row + footer all directly in
            // this LazyColumn so the rows can participate in the parent's
            // ReorderableLazyListState (T186 drag-to-reorder).
            agentLoopModelsSectionItems(
                providerRepository = providerRepository,
                config = config,
                reorderState = reorderState,
                onAddModelsTap = onAddAgentLoopModels,
                onAddGroupsTap = onAddAgentLoopGroups,
            )
        }
    }

    // T185: the in-screen ModalBottomSheet was replaced by full-screen
    // navigated picker screens (AddAgentLoopModelsScreen /
    // AddAgentLoopGroupsScreen). The "Add Models" / "Add Groups"
    // buttons inside AgentLoopModelsSection now invoke
    // onAddAgentLoopModels / onAddAgentLoopGroups passed from
    // AppNavigation, mirroring how AddModelsToGroupScreen is reached.

    if (showNewGroupDialog) {
        AlertDialog(
            onDismissRequest = {
                showNewGroupDialog = false
                newGroupName = ""
            },
            title = { Text(stringResource(R.string.model_groups_new_group)) },
            text = {
                Column {
                    Text(
                        "Enter a name for the new model group.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        label = { Text(stringResource(R.string.model_groups_group_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                MinisTextButton(
                    onClick = {
                        if (newGroupName.isNotBlank()) {
                            val newGroup = ModelGroup(name = newGroupName.trim())
                            providerRepository.addGroup(newGroup)
                            // Auto-set as primary default if it's the first group
                            if (config.modelGroups.size == 1 && config.defaultPrimaryGroupId == null) {
                                providerRepository.defaultPrimaryGroupId = newGroup.id
                            }
                            newGroupName = ""
                            showNewGroupDialog = false
                        }
                    },
                    enabled = newGroupName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.model_groups_create))
                }
            },
            dismissButton = {
                MinisTextButton(onClick = {
                    showNewGroupDialog = false
                    newGroupName = ""
                }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // [T-android-swipe-row-actions] Swipe-delete confirmation. Same title/body
    // strings and same removeGroup() call as ModelGroupDetailScreen's Delete
    // Group button — the swipe is a shortcut to that flow, not a second one.
    groupToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            title = { Text(stringResource(R.string.model_group_detail_delete_group)) },
            text = {
                Text(
                    stringResource(
                        R.string.model_group_detail_delete_named_group_confirm,
                        target.name,
                    ),
                )
            },
            confirmButton = {
                MinisTextButton(
                    onClick = {
                        providerRepository.removeGroup(target.id)
                        groupToDelete = null
                    },
                ) {
                    Text(
                        stringResource(R.string.common_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { groupToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** Small colored badge label (e.g. "Primary", "Sub"). */
@Composable
private fun BadgeLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDropdown(
    label: String,
    groups: List<ModelGroup>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = groups.find { it.id == selectedId }?.name ?: stringResource(R.string.model_groups_none)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.model_groups_none)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            groups.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group.name) },
                    onClick = {
                        onSelect(group.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * T182 — inline section embedded at the bottom of ModelGroupsScreen.
 * Mirrors iOS `AgentLoopModelsSection` from
 * `src/ios/Views/Providers/AgentLoopModelsView.swift` L1-72. Renders
 * the user's curated agent-loop usable set: pinned groups (rendered
 * first, since picking a group implicitly pins all its members) then
 * pinned individual entries. Each row carries a × icon to remove the
 * pin; the section footer hosts two add-sheet trigger buttons.
 *
 * The picker layouts behind those triggers live in
 * `AgentLoopAddSheets.kt` and use `LazyColumn` so a user with a 369-
 * entry OpenRouter instance doesn't freeze Compose the way the pre-
 * T182 `AgentLoopModelsScreen` did when it materialised every entry
 * in a single `Column`.
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun LazyListScope.agentLoopModelsSectionItems(
    providerRepository: ProviderRepository,
    config: com.openminis.app.data.model.ProviderConfig,
    reorderState: ReorderableLazyListState,
    onAddModelsTap: () -> Unit,
    onAddGroupsTap: () -> Unit,
) {
    // [T-android-agentloop-dup-key-crash] distinctBy id (belt-and-suspenders
    // alongside the data-layer sink dedup). A config that's ALREADY corrupted
    // with a duplicate id (persisted before the sink fix) would otherwise yield
    // two rows sharing the same LazyColumn/Reorderable key and crash on scroll
    // (IllegalArgumentException: Key "..." was already used). Deduping here
    // makes the screen render so the user can even reach a state where the
    // persisted list gets rewritten clean. totalRows / absIndex below derive
    // from these deduped lists, so the row math stays consistent.
    val pinnedGroups = config.agentLoopGroupIds
        .mapNotNull { gid -> config.modelGroups.find { it.id == gid } }
        .distinctBy { it.id }
    val pinnedEntries = config.agentLoopModelEntryIds
        .mapNotNull { eid -> config.modelEntries.find { it.id == eid } }
        .distinctBy { it.id }
    val isEmpty = pinnedGroups.isEmpty() && pinnedEntries.isEmpty()

    // T313 — agent-loop section. Reorder rows MUST stay as top-level
    // LazyColumn items (ReorderableLazyListState only sees direct child
    // items), so the card visual is built per-row via SectionDesign
    // tokens: every row is wrapped with cardRow(isFirst, isLast) which
    // applies horizontal insets, surface fill, and rounded corners only
    // on the first/last row. SectionDivider is emitted as its own item
    // between rows so the inner card reads as one continuous panel.
    item("agent_loop_section_spacer") {
        Spacer(modifier = Modifier.height(SectionDesign.SectionTopGap))
    }
    item("agent_loop_section_header") {
        SectionHeader(text = stringResource(R.string.agent_loop_section_title))
    }

    if (isEmpty) {
        item("agent_loop_section_empty") {
            Box(modifier = Modifier.cardRow(isFirst = true, isLast = true)) {
                Text(
                    text = stringResource(R.string.agent_loop_section_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
    } else {
        val totalRows = pinnedGroups.size + pinnedEntries.size
        itemsIndexed(pinnedGroups, key = { _, g -> "agent_group:${g.id}" }) { index, group ->
            ReorderableItem(
                state = reorderState,
                key = "agent_group:${group.id}",
            ) { _ ->
                val resolvedCount = group.memberEntryIds.count { mid ->
                    config.modelEntries.any { it.id == mid }
                }
                val subtitle = if (resolvedCount == 1) {
                    stringResource(R.string.agent_loop_models_model_count_singular)
                } else {
                    stringResource(R.string.agent_loop_models_model_count_plural, resolvedCount)
                }
                Column {
                    if (index != 0) SectionDividerInsetCard()
                    Box(
                        modifier = Modifier.cardRow(
                            isFirst = index == 0,
                            isLast = index == totalRows - 1,
                        ),
                    ) {
                        AgentLoopRow(
                            title = group.name,
                            subtitle = subtitle,
                            badge = stringResource(R.string.agent_loop_section_group_badge),
                            onRemove = { providerRepository.removeAgentLoopGroup(group.id) },
                            dragHandleModifier = Modifier.then(
                                with(this@ReorderableItem) {
                                    Modifier.draggableHandle()
                                }
                            ),
                        )
                    }
                }
            }
        }

        itemsIndexed(pinnedEntries, key = { _, e -> "agent_entry:${e.id}" }) { index, entry ->
            ReorderableItem(
                state = reorderState,
                key = "agent_entry:${entry.id}",
            ) { _ ->
                val instanceLabel = config.instances
                    .find { it.id == entry.providerInstanceId }
                    ?.label
                val absIndex = pinnedGroups.size + index
                Column {
                    if (absIndex != 0) SectionDividerInsetCard()
                    Box(
                        modifier = Modifier.cardRow(
                            isFirst = absIndex == 0,
                            isLast = absIndex == totalRows - 1,
                        ),
                    ) {
                        AgentLoopRow(
                            title = entry.model.displayName,
                            subtitle = instanceLabel,
                            badge = null,
                            onRemove = { providerRepository.removeAgentLoopEntry(entry.id) },
                            dragHandleModifier = Modifier.then(
                                with(this@ReorderableItem) {
                                    Modifier.draggableHandle()
                                }
                            ),
                        )
                    }
                }
            }
        }
    }

    item("agent_loop_section_add_buttons") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MinisOutlinedButton(
                onClick = onAddModelsTap,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.agent_loop_section_add_models))
            }
            MinisOutlinedButton(
                onClick = onAddGroupsTap,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.agent_loop_section_add_groups))
            }
        }
    }

    item("agent_loop_section_footer") {
        SectionFooter(text = stringResource(R.string.agent_loop_section_footer))
    }

    item("bottom_gap") { Spacer(modifier = Modifier.height(SectionDesign.SectionTopGap)) }
}

/** Single row inside the agent-loop section. Drag handle on the left
 *  (T186), title + optional subtitle + optional "Group" badge in the
 *  middle, × to unpin on the right. The drag-handle modifier is built
 *  by the parent (since ReorderableItemScope.draggableHandle() is
 *  scope-bound) and threaded in here to keep the row composable
 *  scope-agnostic. */
@Composable
private fun AgentLoopRow(
    title: String,
    subtitle: String?,
    badge: String?,
    onRemove: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // T198: drag handle must be wrapped in IconButton — Icon is raw
        // vector graphics with no pointer consumer, so reorderable v2.4.0's
        // draggableHandle() never sees ACTION_DOWN/MOVE on a bare Icon.
        // IconButton is Compose's clickable container and consumes pointer
        // events, letting the dragHandleModifier route gestures correctly.
        IconButton(
            onClick = {},
            modifier = dragHandleModifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = stringResource(R.string.model_group_detail_drag_to_reorder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                if (badge != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    BadgeLabel(badge, MaterialTheme.colorScheme.primary)
                }
            }
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.agent_loop_section_remove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ── T313 LazyList card helpers ────────────────────────────────────────────
//
// SectionCard / SectionDivider from SectionDesign are designed for
// Column-scoped sections, but the agent-loop section needs each row to
// stay a top-level LazyColumn item so ReorderableLazyListState (T186) can
// see it. These helpers paint the SectionDesign card visual on a per-row
// basis: cardRow() applies horizontal insets + surface fill + per-row
// corner clipping; SectionDividerInsetCard() draws the inner divider
// between consecutive rows so the painted run reads as one panel.

@Composable
private fun Modifier.cardRow(isFirst: Boolean, isLast: Boolean): Modifier {
    val shape = when {
        isFirst && isLast -> SectionDesign.CardShape
        isFirst -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        isLast -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
        else -> RectangleShape
    }
    // Match SectionCard's inner Column vertical padding so first/last rows
    // get the same breathing room from the card edge as Sections 1 & 2.
    return this
        .padding(horizontal = SectionDesign.ScreenHorizontalPadding)
        .clip(shape)
        .background(SectionDesign.cardColor())
        .padding(
            top = if (isFirst) SectionDesign.CardInnerVerticalPadding else 0.dp,
            bottom = if (isLast) SectionDesign.CardInnerVerticalPadding else 0.dp,
        )
}

/** Divider between two cardRow rows. Painted on the same surface fill so
 *  it reads as an inset divider inside the card panel rather than a hard
 *  line floating on the page background. */
@Composable
private fun SectionDividerInsetCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SectionDesign.ScreenHorizontalPadding)
            .background(SectionDesign.cardColor()),
    ) {
        HorizontalDivider(
            modifier = Modifier.padding(start = SectionDesign.DividerStartInset),
            thickness = SectionDesign.DividerThickness,
            color = SectionDesign.dividerColor(),
        )
    }
}

/**
 * [T-android-modelgroup-modality-icons] One modality marker shown after a Model
 * Group title. `kind` is the modality string ("image"/"audio"/"video"/"pdf"/
 * "text") and `isOutput` distinguishes a generation output from an accepted
 * input. Ports iOS ModelGroupsView.GroupRow.topModalities /
 * modalityIcon (commits 7caa580a + e0a7cd5f).
 */
private data class GroupModalityMarker(val kind: String, val isOutput: Boolean)

/**
 * Distinctiveness ranking, most distinctive first — mirrors iOS
 * `modalityPriority`. `text` input is intentionally absent (universal noise);
 * `text` output ranks last (implied for every model, only surfaces when a group
 * has nothing more distinctive). Transcription (audio-in) outranks other inputs
 * so Whisper-style groups get flagged even though their output is plain text.
 */
private val GROUP_MODALITY_PRIORITY: List<GroupModalityMarker> = listOf(
    GroupModalityMarker("video", isOutput = true),
    GroupModalityMarker("image", isOutput = true),
    GroupModalityMarker("audio", isOutput = true),
    GroupModalityMarker("audio", isOutput = false),
    GroupModalityMarker("video", isOutput = false),
    GroupModalityMarker("image", isOutput = false),
    GroupModalityMarker("pdf", isOutput = false),
    GroupModalityMarker("text", isOutput = true),
)

/**
 * The group's top-2 most distinctive modalities across every member model's
 * inputs AND outputs, in priority order. Aggregates raw model modality lists
 * (Android convention — no capability inference); a null/empty outputModalities
 * counts as text output (iOS treats text-out as universal). Mirrors iOS
 * `GroupRow.topModalities`.
 */
private fun groupTopModalities(
    group: ModelGroup,
    config: com.openminis.app.data.model.ProviderConfig,
): List<GroupModalityMarker> {
    val inputs = mutableSetOf<String>()
    val outputs = mutableSetOf<String>()
    for (entryId in group.memberEntryIds) {
        val model = config.modelEntries.find { it.id == entryId }?.model ?: continue
        model.inputModalities.orEmpty().forEach { inputs.add(it.lowercase()) }
        val out = model.outputModalities.orEmpty()
        if (out.isEmpty()) outputs.add("text") else out.forEach { outputs.add(it.lowercase()) }
    }
    return GROUP_MODALITY_PRIORITY.filter { marker ->
        if (marker.isOutput) marker.kind in outputs else marker.kind in inputs
    }.take(2)
}

/**
 * Icon + tint + a11y label for one [GroupModalityMarker], reusing the glyph
 * convention from ProviderDetailScreen.ModalityIconsRow: output/generation
 * modalities use the primary tint + "generate"-style glyph; input modalities
 * use the muted onSurfaceVariant tint. Renders nothing for unknown combos.
 */
@Composable
private fun GroupModalityIcon(marker: GroupModalityMarker) {
    val outputTint = MaterialTheme.colorScheme.primary
    val inputTint = MaterialTheme.colorScheme.onSurfaceVariant
    val size = Modifier.size(14.dp)
    val (vector, labelRes, tint) = when {
        marker.isOutput && marker.kind == "video" -> Triple(Icons.Default.MovieCreation, R.string.modeldetail_video_output, outputTint)
        marker.isOutput && marker.kind == "image" -> Triple(Icons.Default.AddPhotoAlternate, R.string.modeldetail_image_output, outputTint)
        marker.isOutput && marker.kind == "audio" -> Triple(Icons.Default.VolumeUp, R.string.modelgroup_speech_output, outputTint)
        marker.isOutput && marker.kind == "text" -> Triple(Icons.AutoMirrored.Filled.Article, R.string.modelgroup_text_generation, outputTint)
        marker.kind == "audio" -> Triple(Icons.Default.Mic, R.string.modelgroup_speech_transcription, inputTint)
        marker.kind == "video" -> Triple(Icons.Default.Videocam, R.string.modeldetail_video_input, inputTint)
        marker.kind == "image" -> Triple(Icons.Default.Image, R.string.modeldetail_image_input, inputTint)
        marker.kind == "pdf" -> Triple(Icons.AutoMirrored.Filled.InsertDriveFile, R.string.modeldetail_pdf_input, inputTint)
        else -> return
    }
    Icon(imageVector = vector, contentDescription = stringResource(labelRes), modifier = size, tint = tint)
}

/** A single group row inside the Groups section card. Built as a plain
 *  Composable (not a ListItem) so it inherits the card's surface color
 *  cleanly without ListItem's container override. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupRow(
    group: ModelGroup,
    config: com.openminis.app.data.model.ProviderConfig,
    onClick: () -> Unit,
    /**
     * [T-android-modelgroup-reorder] When non-null, a leading drag handle is
     * rendered carrying this modifier (ReorderableItem.draggableHandle).
     * Explicit handle rather than long-press-anywhere: the row already owns
     * tap (open detail) and horizontal swipe (delete), and this matches
     * AgentLoopRow's affordance in the section below.
     */
    dragHandleModifier: Modifier? = null,
) {
    val memberNames = group.memberEntryIds.mapNotNull { entryId ->
        config.modelEntries.find { it.id == entryId }?.model?.displayName
    }
    val preview = if (memberNames.size <= 3) {
        memberNames.joinToString(", ")
    } else {
        memberNames.take(3).joinToString(", ") + " +${memberNames.size - 3}"
    }
    val isPrimary = config.defaultPrimaryGroupId == group.id
    val isSub = config.defaultSubGroupId == group.id
    val strategyLabel = when (group.strategy) {
        RoutingStrategy.fallback -> stringResource(R.string.model_group_detail_fallback)
        RoutingStrategy.loadBalance -> stringResource(R.string.model_group_detail_load_balance)
    }
    // [T-disabled-provider-via-group-android] Count members whose provider
    // instance is currently enabled — that's what the runtime resolver
    // will actually consider. When every member sits behind a disabled
    // provider, surface a warning so the user understands why the group
    // appears empty in chat.
    val enabledInstanceIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
    val totalMembers = group.memberEntryIds.size
    val enabledMembers = group.memberEntryIds.count { entryId ->
        config.modelEntries.find { it.id == entryId }?.providerInstanceId in enabledInstanceIds
    }
    val allDisabled = totalMembers > 0 && enabledMembers == 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = SectionDesign.RowHorizontalPadding,
                vertical = SectionDesign.RowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dragHandleModifier != null) {
            // T198 (same as AgentLoopRow): the handle must be an IconButton —
            // a bare Icon has no pointer consumer, so draggableHandle() would
            // never receive ACTION_DOWN/MOVE.
            IconButton(
                onClick = {},
                modifier = dragHandleModifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = stringResource(R.string.model_group_detail_drag_to_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // [T-android-modelgroup-modality-icons] Mark the group's top-2
                // most distinctive modalities (inputs + outputs) right after the
                // title, in priority order, for at-a-glance "what is this group
                // for". Ports iOS ModelGroupsView (7caa580a + e0a7cd5f).
                groupTopModalities(group, config).forEach { marker ->
                    GroupModalityIcon(marker)
                }
                if (isPrimary) BadgeLabel("Primary", MaterialTheme.colorScheme.primary)
                if (isSub) BadgeLabel("Sub", MaterialTheme.colorScheme.tertiary)
                if (allDisabled) {
                    BadgeLabel(
                        stringResource(R.string.model_group_no_usable_models_badge),
                        MaterialTheme.colorScheme.error,
                    )
                }
            }
            // [T-disabled-provider-via-group-android] Surface "M of N
            // disabled" when any member's provider is off so the user
            // doesn't have to drill into the detail page to learn that
            // the group isn't fully usable. Total count stays prominent
            // because it's still the source of truth for what's in the
            // group; the parenthetical reports disabled count.
            val disabledCount = totalMembers - enabledMembers
            val subtitleText = if (disabledCount > 0) {
                "$strategyLabel · $totalMembers models ($disabledCount disabled)"
            } else {
                "$strategyLabel · $totalMembers models"
            }
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall,
                color = if (allDisabled) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (preview.isNotEmpty()) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
