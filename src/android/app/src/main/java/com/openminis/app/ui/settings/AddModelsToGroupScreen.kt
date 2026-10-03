package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.model.SystemVoiceEntries
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.PickerModalityFilter
import com.openminis.app.ui.components.modelEntryPickerItems
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddModelsToGroupScreen(
    groupId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val group = config.modelGroups.find { it.id == groupId }

    if (group == null) {
        onBack()
        return
    }

    val existingIds = group.memberEntryIds.toSet()
    // [T-android-provider-voice] Voice-scoped picker: when the target group is
    // bound as the Voice Input/Output group, scope the shared picker by
    // modality (ASR = audio-in, TTS = audio-out). Modality filtering + System
    // virtual entry injection live inside modelEntryPickerItems (mirrors iOS
    // UnifiedModelPicker effectivePreferModality / candidateEntries).
    val modalityFilter = when (groupId) {
        config.voiceInputGroupId -> PickerModalityFilter.AUDIO_INPUT
        config.voiceOutputGroupId -> PickerModalityFilter.AUDIO_OUTPUT
        // [T-android-vision-group] Vision Group picker: only image-capable models.
        config.visionGroupId -> PickerModalityFilter.IMAGE_INPUT
        else -> null
    }
    val availableEntries = config.modelEntries.filter {
        !it.isHidden && it.id !in existingIds
    }

    val searchQuery = remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var quickTestEntry by remember { mutableStateOf<com.openminis.app.data.model.ModelEntry?>(null) }
    val collapsedInstanceIds = remember(config) {
        // T185: default-collapsed mirrors pre-refactor behaviour. The
        // shared picker auto-expands when search is non-empty so hits
        // remain visible regardless of section state.
        // [T-android-provider-voice] Voice scenario: DON'T collapse — a
        // multi-voice provider (Azure ~39, MiMo 9) would fold all-but-one
        // voice behind a disclosure (iOS d4e3198f seedCollapse fix).
        mutableStateOf(
            if (modalityFilter != null) emptySet()
            else config.instances.map { it.id }.toSet(),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_group_detail_add_models), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.model_group_detail_back))
                    }
                },
                actions = {
                    MinisTextButton(onClick = onBack) { Text(stringResource(R.string.common_cancel)) }
                    MinisButton(
                        onClick = {
                            val updated = group.copy(
                                memberEntryIds = (group.memberEntryIds + selectedIds).toMutableList()
                            )
                            providerRepository.updateGroup(updated)
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
        // Resolved here (Composable context) — LazyListScope below can't call
        // stringResource. Localizes the injected System provider section label.
        val systemProviderLabel = stringResource(R.string.voice_provider_system)
        // [T-picker-search-debounce] Ranked, capped search, computed here (a
        // composable scope) so typing re-ranks only once the query settles.
        val searchSections = com.openminis.app.ui.components.rememberModelEntryPickerSections(
            instances = config.instances,
            availableEntries = availableEntries,
            searchQuery = searchQuery.value,
            modalityFilter = modalityFilter,
            excludeIds = existingIds,
            systemProviderLabel = systemProviderLabel,
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
                emptyTextRes = R.string.add_models_to_group_all_in_group,
                emptySearchTextRes = R.string.add_models_to_group_no_match,
                searchPlaceholderRes = R.string.add_models_to_group_search_models,
                clearContentDescriptionRes = R.string.add_models_to_group_clear,
                // System virtual entries have no cloud endpoint to smoke-test.
                onQuickTest = { if (!SystemVoiceEntries.isSystemEntryId(it.id)) quickTestEntry = it },
                modalityFilter = modalityFilter,
                excludeIds = existingIds,
                systemProviderLabel = systemProviderLabel,
                sections = searchSections,
            )
        }
    }

    quickTestEntry?.let { entry ->
        com.openminis.app.ui.components.QuickTestSheet(
            entry = entry,
            providerRepository = providerRepository,
            onDismiss = { quickTestEntry = null },
        )
    }
}
