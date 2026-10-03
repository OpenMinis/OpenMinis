package com.openminis.app.ui.onboarding

import com.openminis.app.R
import androidx.compose.ui.res.stringResource
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.repository.ProviderRepository

/**
 * Onboarding step 2: pick 1-3 models from configured providers
 * and create a "Default Models" group. Mirrors iOS OnboardingModelSelectionView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingModelSelectionScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    /** [T-onboarding-model-fetch-fallback] Open Add Custom Model for this provider. */
    onAddCustomModel: (instanceId: String) -> Unit = {},
) {
    val config by providerRepository.config.collectAsState()
    val selected = remember { mutableStateListOf<String>() }
    var searchText by remember { mutableStateOf("") }

    val enabledInstances = config.instances.filter { it.isEnabled }
    val enabledInstanceIds = enabledInstances.map { it.id }.toSet()
    val allEntries = config.modelEntries.filter {
        it.providerInstanceId in enabledInstanceIds && !it.isHidden
    }

    // [T-onboarding-model-fetch-fallback] State of the page's own bounded
    // fetch, which runs only while the list is empty. Saveable so a trip to
    // Add Custom Model and back keeps the failure section instead of
    // restarting a 20 s spinner.
    var fetching by remember { mutableStateOf(false) }
    var fetchAttempted by rememberSaveable { mutableStateOf(false) }
    // Non-null = the fetch finished and the page is still empty; the text is
    // the provider's own error, or "" when there is nothing beyond the headline.
    var failureDetail by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val timeoutText = stringResource(
        R.string.onboarding_models_fetch_timeout,
        (OnboardingModelFetch.TIMEOUT_MS / 1000).toInt(),
    )

    fun visibleEntryCount(instanceIds: Set<String>): Int =
        providerRepository.config.value.modelEntries.count { it.providerInstanceId in instanceIds && !it.isHidden }

    fun runBoundedFetch() {
        if (fetching) return // Retry while in flight is a no-op.
        val current = providerRepository.config.value
        val targets = current.instances.filter { inst ->
            inst.isEnabled && current.modelEntries.none { it.providerInstanceId == inst.id && !it.isHidden }
        }
        fetchAttempted = true
        failureDetail = null
        if (targets.isEmpty()) {
            // No enabled provider at all: nothing to wait for.
            if (visibleEntryCount(enabledInstanceIds) == 0) failureDetail = ""
            return
        }
        fetching = true
        scope.launch {
            try {
                val result = OnboardingModelFetch.run(targets) { inst ->
                    var vendorError: String? = null
                    withContext(Dispatchers.IO) {
                        providerRepository.refreshModels(inst, onVendorError = { vendorError = it })
                    }
                    vendorError
                }
                // Failure is a property of the EMPTY page, not of a provider: a
                // partial result simply lists what arrived.
                val ids = providerRepository.config.value.instances.filter { it.isEnabled }.map { it.id }.toSet()
                if (visibleEntryCount(ids) == 0) {
                    failureDetail = if (result.timedOut) timeoutText else result.errors.distinct().joinToString("\n")
                }
            } finally {
                fetching = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (fetchAttempted) return@LaunchedEffect
        if (allEntries.isEmpty()) {
            runBoundedFetch()
            return@LaunchedEffect
        }
        // Normal path: refresh model lists for every enabled instance when the
        // screen appears, so the user sees the live provider catalog (not just
        // the built-in placeholder list seeded by addInstance). Mirrors iOS
        // fetchModelsWithFallback chain.
        for (instance in enabledInstances) {
            // [T-provider-refresh-outlives-screen] (GH#265) Repository scope, so
            // tapping Continue quickly does not cancel the fetch half-way.
            providerRepository.triggerAsyncModelReconcile(instance.id)
        }
    }
    val filteredEntries = if (searchText.isBlank()) allEntries else {
        val q = searchText.lowercase()
        allEntries.filter {
            it.model.displayName.lowercase().contains(q) || it.model.id.lowercase().contains(q)
        }
    }
    val grouped = filteredEntries.groupBy { it.providerInstanceId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_select_models_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    MinisTextButton(onClick = onBack) {
                        Text(stringResource(R.string.common_skip))
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
            Text(
                stringResource(R.string.onboarding_select_models_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = { Text(stringResource(R.string.onboarding_filter_models)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(50),
            )

            Spacer(Modifier.height(8.dp))

            if (allEntries.isEmpty() && !fetching && failureDetail != null) {
                ModelFetchFailure(
                    detail = failureDetail.orEmpty(),
                    instances = enabledInstances.map { it.id to it.label },
                    onRetry = { runBoundedFetch() },
                    onAddCustomModel = onAddCustomModel,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else if (allEntries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.onboarding_loading_models),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for ((instanceId, entries) in grouped) {
                        val instance = config.instances.find { it.id == instanceId }
                        item(key = "header_$instanceId") {
                            Text(
                                instance?.label ?: instanceId,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        items(entries, key = { it.id }) { entry ->
                            val isSelected = entry.id in selected
                            val selectionIndex = selected.indexOf(entry.id)
                            val canSelect = selected.size < 3 || isSelected

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = canSelect) {
                                        if (isSelected) selected.remove(entry.id)
                                        else if (selected.size < 3) selected.add(entry.id)
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Text(
                                            "${selectionIndex + 1}",
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        entry.model.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        entry.model.id,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            MinisButton(
                onClick = {
                    if (selected.isNotEmpty()) {
                        val group = ModelGroup(name = "Default Models")
                        group.memberEntryIds.addAll(selected)
                        providerRepository.addGroup(group)
                        if (config.defaultPrimaryGroupId == null) {
                            providerRepository.defaultPrimaryGroupId = group.id
                        }
                    }
                    onBack()
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            ) {
                Text(stringResource(R.string.onboarding_done_count, selected.size))
            }
        }
    }
}

/**
 * [T-onboarding-model-fetch-fallback] Shown when the bounded fetch finished
 * and the page is still empty: a headline, the provider's own error under it
 * (omitted when it would only repeat the headline), Retry, and Add Model
 * Manually. A custom model belongs to exactly one provider, so with several
 * enabled providers the button opens a menu to pick one; with none (every
 * provider disabled) it hides and Skip remains.
 */
@Composable
private fun ModelFetchFailure(
    detail: String,
    instances: List<Pair<String, String>>,
    onRetry: () -> Unit,
    onAddCustomModel: (instanceId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            val headline = stringResource(R.string.onboarding_models_fetch_failed)
            Text(
                headline,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
            if (detail.isNotBlank() && detail != headline) {
                Spacer(Modifier.height(6.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 6,
                )
            }
            Spacer(Modifier.height(16.dp))
            MinisButton(onClick = onRetry) {
                Text(stringResource(R.string.onboarding_models_retry))
            }
            if (instances.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Box {
                    MinisTextButton(onClick = {
                        if (instances.size == 1) onAddCustomModel(instances.first().first) else pickerOpen = true
                    }) {
                        Text(stringResource(R.string.onboarding_models_add_manually))
                    }
                    DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                        for ((id, label) in instances) {
                            DropdownMenuItem(
                                text = { Text(label.ifBlank { id }) },
                                onClick = {
                                    pickerOpen = false
                                    onAddCustomModel(id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
