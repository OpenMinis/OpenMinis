package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.modelEntryPickerItems
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton

/**
 * T185 — full-screen picker for adding model entries to the agent-loop
 * usable set. Replaces the T182 ModalBottomSheet with the same
 * Scaffold + sectioned multi-select shape `AddModelsToGroupScreen` uses,
 * via the shared [modelEntryPickerItems] in `ui/components/`. Mirrors
 * iOS `AddAgentLoopModelsSheet` (which is a NavigationStack-wrapped
 * sheet content — same sectioned-list visual we land here).
 *
 * Multi-select with a confirm button in the top bar (vs the pre-T185
 * tap-and-add-immediately bottom sheet) so a user adding 5 models from
 * the same provider doesn't bounce in and out of the picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAgentLoopModelsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()

    val pinnedEntries = config.agentLoopModelEntryIds.toSet()
    // Skip entries already reachable through a pinned group — adding a
    // direct pin on top of group membership would be a no-op for the
    // resolved usable set and just clutters the section UI.
    val groupBackedEntries: Set<String> = config.agentLoopGroupIds
        .mapNotNull { gid -> config.modelGroups.find { it.id == gid } }
        .flatMap { it.memberEntryIds }
        .toSet()
    val availableEntries = config.modelEntries.filter {
        !it.isHidden && it.id !in pinnedEntries && it.id !in groupBackedEntries
    }

    val searchQuery = remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val collapsedInstanceIds = remember(config) {
        mutableStateOf(config.instances.map { it.id }.toSet())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.agent_loop_section_add_models_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.model_group_detail_back),
                        )
                    }
                },
                actions = {
                    MinisTextButton(onClick = onBack) { Text(stringResource(R.string.common_cancel)) }
                    MinisButton(
                        onClick = {
                            // Add in stable order so the section renders
                            // pinned items in the order the user saw them
                            // in the picker, not insertion-set order.
                            val orderedToAdd = availableEntries
                                .map { it.id }
                                .filter { it in selectedIds }
                            for (id in orderedToAdd) {
                                providerRepository.addAgentLoopEntry(id)
                            }
                            onBack()
                        },
                        enabled = selectedIds.isNotEmpty(),
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(stringResource(R.string.add_models_to_group_add_count, selectedIds.size))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // [T-picker-search-debounce] Ranked, capped, debounced search.
        val searchSections = com.openminis.app.ui.components.rememberModelEntryPickerSections(
            instances = config.instances,
            availableEntries = availableEntries,
            searchQuery = searchQuery.value,
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            modelEntryPickerItems(
                instances = config.instances,
                availableEntries = availableEntries,
                selectedIds = selectedIds,
                onToggleSelection = { id ->
                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                },
                searchQuery = searchQuery,
                collapsedInstanceIds = collapsedInstanceIds,
                emptyTextRes = R.string.agent_loop_section_no_available_models,
                emptySearchTextRes = R.string.add_models_to_group_no_match,
                searchPlaceholderRes = R.string.add_models_to_group_search_models,
                clearContentDescriptionRes = R.string.add_models_to_group_clear,
                sections = searchSections,
            )
        }
    }
}

/**
 * T185 — full-screen picker for adding model groups to the agent-loop
 * usable set. Groups don't have a per-provider grouping dimension, so
 * this screen is structurally simpler than the entries picker: a flat
 * card-style multi-select list of all groups not already pinned.
 * Visuals (selection circle, surfaceContainer card, divider treatment)
 * mirror the entries picker so both feel like one family.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAgentLoopGroupsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val pinnedGroups = config.agentLoopGroupIds.toSet()
    val available = remember(config, pinnedGroups) {
        config.modelGroups.filter { it.id !in pinnedGroups }
    }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.agent_loop_section_add_groups_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.model_group_detail_back),
                        )
                    }
                },
                actions = {
                    MinisTextButton(onClick = onBack) { Text(stringResource(R.string.common_cancel)) }
                    MinisButton(
                        onClick = {
                            // Same stable-order add policy as the entries
                            // picker so the section ordering matches the
                            // user's mental model.
                            val orderedToAdd = available
                                .map { it.id }
                                .filter { it in selectedIds }
                            for (id in orderedToAdd) {
                                providerRepository.addAgentLoopGroup(id)
                            }
                            onBack()
                        },
                        enabled = selectedIds.isNotEmpty(),
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(stringResource(R.string.add_models_to_group_add_count, selectedIds.size))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (available.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    stringResource(R.string.agent_loop_section_no_available_groups),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainer,
                                RoundedCornerShape(12.dp),
                            ),
                    ) {
                        available.forEachIndexed { index, group ->
                            val isSelected = group.id in selectedIds
                            val rowShape = when {
                                available.size == 1 -> RoundedCornerShape(12.dp)
                                index == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                                index == available.size - 1 -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                                else -> RoundedCornerShape(0.dp)
                            }
                            // Compose a short member-name preview so the
                            // user remembers what the group contains
                            // without tapping into detail. Mirrors iOS
                            // AddAgentLoopGroupsSheet preview row.
                            val memberNames = group.memberEntryIds
                                .mapNotNull { mid ->
                                    config.modelEntries.find { it.id == mid }?.model?.displayName
                                }
                            val preview = when {
                                memberNames.isEmpty() ->
                                    stringResource(R.string.agent_loop_section_empty_group_subtitle)
                                memberNames.size <= 3 -> memberNames.joinToString(", ")
                                else -> memberNames.take(3).joinToString(", ") +
                                    " +${memberNames.size - 3}"
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(rowShape)
                                    .clickable {
                                        selectedIds = if (isSelected) selectedIds - group.id else selectedIds + group.id
                                    }
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (isSelected) Color(0xFF007AFF)
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        group.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        preview,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                }
                            }
                            if (index < available.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 46.dp, end = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
