package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.R
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.SectionTextField
import kotlin.math.roundToInt

/**
 * Detail / edit screen for one ModelGroup. Adopted T79 sectioned style:
 * Name, Routing Strategy (+ optional Fallback Trigger sub-section),
 * Members (reorderable), and Actions. Each section gets a header and an
 * explanatory footer caption, matching the iOS-side detail screens.
 *
 * Uses a manually-rolled Scaffold instead of SettingsScaffold because
 * the members list needs LazyColumn for reordering — SettingsScaffold's
 * verticalScroll wrapper would conflict with the lazy list's infinite-
 * height measure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelGroupDetailScreen(
    groupId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onAddModels: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val group = config.modelGroups.find { it.id == groupId }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (group == null) {
        onBack()
        return
    }

    var name by remember { mutableStateOf(group.name) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var strategy by remember { mutableStateOf(group.strategy) }
    var fallbackStrategy by remember { mutableStateOf(group.fallbackStrategy) }
    // T312: Session Defaults — keyed on group.id so navigating to another
    // group via NavBackStack rebuilds these from the new group's persisted
    // values. Using `group` as key would also re-fire on every config
    // refresh (since `group` is recomputed from the StateFlow each
    // recomposition); id-keyed remember is the stable alternative.
    var defaultThinkingLevel by remember(group.id) {
        mutableStateOf(group.defaultThinkingLevel)
    }
    var contextLimitTokens by remember(group.id) {
        mutableStateOf(group.contextLimitTokens)
    }

    // Local copy of member IDs for live reorder
    var memberIds by remember(group.memberEntryIds) { mutableStateOf(group.memberEntryIds.toList()) }
    var entryToRemove by remember { mutableStateOf<String?>(null) }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // from.index / to.index are LazyColumn global indices (include header items).
        // Use the item key (entryId string) to find the correct position in memberIds.
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        val fromIdx = memberIds.indexOf(fromKey)
        val toIdx = memberIds.indexOf(toKey)
        if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
        memberIds = memberIds.toMutableList().apply {
            add(toIdx, removeAt(fromIdx))
        }
        providerRepository.updateGroup(group.copy(memberEntryIds = memberIds.toMutableList()))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(group.name, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.model_group_detail_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Name section ──────────────────────────────────────────
            item {
                SettingsSection(
                    header = stringResource(R.string.model_group_detail_name),
                    footer = stringResource(R.string.model_group_detail_the_label_shown_in_the_model_picker_save),
                ) {
                    SettingsCardBlock {
                        SectionTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            fieldModifier = Modifier.onFocusChanged { focusState ->
                                if (!focusState.isFocused && name.isNotBlank() && name != group.name) {
                                    providerRepository.updateGroup(group.copy(name = name))
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Name saved")
                                    }
                                }
                            },
                        )
                    }
                }
            }

            // ── Routing Strategy ──────────────────────────────────────
            item {
                SettingsSection(
                    header = stringResource(R.string.model_group_detail_routing_strategy),
                    footer = when (strategy) {
                        RoutingStrategy.fallback -> "Try models in order. If one fails, advance to the next."
                        RoutingStrategy.loadBalance -> "Distribute sessions across models in the group."
                    },
                ) {
                    SettingsChoiceRow(
                        title = stringResource(R.string.model_group_detail_fallback),
                        selected = strategy == RoutingStrategy.fallback,
                        onSelect = {
                            strategy = RoutingStrategy.fallback
                            providerRepository.updateGroup(group.copy(strategy = RoutingStrategy.fallback))
                        },
                    )
                    SettingsChoiceRow(
                        title = stringResource(R.string.model_group_detail_load_balance),
                        selected = strategy == RoutingStrategy.loadBalance,
                        onSelect = {
                            strategy = RoutingStrategy.loadBalance
                            providerRepository.updateGroup(group.copy(strategy = RoutingStrategy.loadBalance))
                        },
                        showDivider = false,
                    )
                }
            }

            // ── Fallback Trigger (only when strategy = fallback) ──────
            if (strategy == RoutingStrategy.fallback) {
                item {
                    SettingsSection(
                        header = stringResource(R.string.model_group_detail_fallback_trigger),
                        footer = when (fallbackStrategy) {
                            FallbackStrategy.default -> "Fall back on rate limits (429) and server errors (5xx) only."
                            FallbackStrategy.always -> "Fall back on any error, including network and auth failures."
                        },
                    ) {
                        SettingsChoiceRow(
                            title = stringResource(R.string.common_default),
                            selected = fallbackStrategy == FallbackStrategy.default,
                            onSelect = {
                                fallbackStrategy = FallbackStrategy.default
                                providerRepository.updateGroup(group.copy(fallbackStrategy = FallbackStrategy.default))
                            },
                        )
                        SettingsChoiceRow(
                            title = stringResource(R.string.model_group_detail_always),
                            selected = fallbackStrategy == FallbackStrategy.always,
                            onSelect = {
                                fallbackStrategy = FallbackStrategy.always
                                providerRepository.updateGroup(group.copy(fallbackStrategy = FallbackStrategy.always))
                            },
                            showDivider = false,
                        )
                    }
                }
            }

            // ── Members section header ───────────────────────────────
            item {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.model_group_detail_models_header_count, memberIds.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
                )
            }

            if (memberIds.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Text(
                            "No models in this group.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            } else {
                itemsIndexed(memberIds, key = { _, id -> id }) { _, entryId ->
                    // [T-android-provider-voice] System voice sentinels aren't
                    // persisted entries — resolve them to virtual entries so the
                    // default Voice Input/Output members render normally instead
                    // of as stale rows (mirrors iOS system virtual entries).
                    val entry = config.modelEntries.find { it.id == entryId }
                        ?: com.openminis.app.data.model.SystemVoiceEntries.resolve(entryId)

                    if (entry == null) {
                        // Stale member: UUID no longer resolves to any ModelEntry
                        ReorderableItem(reorderState, key = entryId) { _ ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        "Model no longer available",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        entryId,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = stringResource(R.string.model_group_detail_unavailable),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = {
                                        val newIds = memberIds.toMutableList().apply { remove(entryId) }
                                        memberIds = newIds
                                        providerRepository.updateGroup(group.copy(memberEntryIds = newIds.toMutableList()))
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.common_remove),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                            )
                        }
                    } else {
                        // Normal member row
                        val instance = config.instances.find { it.id == entry.providerInstanceId }
                        val instanceLabel = instance?.label
                        val providerDisabled = instance?.isEnabled == false
                        var showMenu by remember { mutableStateOf(false) }

                        ReorderableItem(reorderState, key = entryId) { _ ->
                            ListItem(
                                modifier = Modifier.alpha(if (providerDisabled) 0.4f else 1f),
                                headlineContent = { Text(entry.model.displayName) },
                                supportingContent = {
                                    if (providerDisabled) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(instanceLabel ?: "")
                                            Text(
                                                " · Provider disabled",
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    } else {
                                        Text(instanceLabel ?: "")
                                    }
                                },
                                leadingContent = {
                                    IconButton(
                                        modifier = Modifier.draggableHandle(),
                                        onClick = {},
                                    ) {
                                        Icon(Icons.Default.DragHandle, contentDescription = stringResource(R.string.model_group_detail_drag_to_reorder))
                                    }
                                },
                                trailingContent = {
                                    Box {
                                        IconButton(onClick = { showMenu = true }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.model_group_detail_more_options))
                                        }
                                        com.openminis.app.ui.components.MinisMenu(
                                            expanded = showMenu,
                                            onDismissRequest = { showMenu = false },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.model_group_detail_remove_from_group), color = MaterialTheme.colorScheme.error) },
                                                onClick = {
                                                    showMenu = false
                                                    entryToRemove = entryId
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // ── Add models: directly under the member list it acts on ────────
            // Previously this sat at the very bottom, below Session Defaults and
            // immediately above the destructive "Delete group" button. That put
            // an additive action two sections away from the list it appends to,
            // and pair-adjacent to a destructive one — easy to mis-tap and hard
            // to find. Placing it right after the list makes the relationship
            // obvious and leaves "Delete group" alone at the bottom.
            item {
                Spacer(Modifier.height(12.dp))
                MinisOutlinedButton(
                    onClick = onAddModels,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.model_group_detail_add_models))
                }
            }

            // ── Session Defaults (T312, mirrors iOS ModelGroupDetailView) ────
            item {
                SettingsSection(
                    header = stringResource(R.string.model_group_detail_session_defaults),
                    footer = stringResource(R.string.model_group_detail_session_defaults_footer),
                ) {
                    val reasoningEnabled = defaultThinkingLevel != null
                    SettingsSwitchRow(
                        title = stringResource(R.string.model_group_detail_enable_reasoning),
                        checked = reasoningEnabled,
                        onCheckedChange = { on ->
                            // iOS parity: ON defaults to MEDIUM, OFF clears to null.
                            val newLevel = if (on) ThinkingLevel.MEDIUM else null
                            defaultThinkingLevel = newLevel
                            providerRepository.updateGroup(
                                group.copy(defaultThinkingLevel = newLevel)
                            )
                        },
                        icon = Icons.Default.Psychology,
                        iconColor = Color(0xFFAF52DE),
                        showDivider = reasoningEnabled,
                    )
                    if (reasoningEnabled) {
                        // [T-android-thinking-level-arch] The group's thinking
                        // CEILING is the HIGHEST level any reasoning-capable
                        // member supports — max(), not min() (mirrors iOS
                        // e51fef5d/53388728). min() was wrong twice:
                        //   1. A non-reasoning member (e.g. an image model with
                        //      effectiveMaxThinkingLevel == OFF) dragged the
                        //      ceiling to OFF and emptied the Intensity picker,
                        //      leaving "Enable Reasoning" on with no selector.
                        //   2. Even among reasoning members it capped the group at
                        //      the WEAKEST model — a group of [Opus MAX, a HIGH
                        //      model] would only offer up to High, hiding the tiers
                        //      Opus supports.
                        // The per-request clamp (provider layer) still caps to the
                        // ACTUAL model at send time, so offering the true ceiling
                        // here is safe. Exclude OFF; fall back to XHIGH when no
                        // member reasons (only reached with reasoning enabled).
                        val groupMaxThinkingLevel = remember(group.memberEntryIds, config.modelEntries) {
                            group.memberEntryIds
                                .mapNotNull { entryId ->
                                    config.modelEntries.find { it.id == entryId }
                                        ?.effectiveMaxThinkingLevel
                                }
                                .filter { it != ThinkingLevel.OFF }
                                .maxByOrNull { it.rank }
                                ?: ThinkingLevel.XHIGH
                        }
                        val labelFor: @Composable (ThinkingLevel) -> String = { level ->
                            when (level) {
                                ThinkingLevel.LOW -> stringResource(R.string.model_group_detail_thinking_low)
                                ThinkingLevel.MEDIUM -> stringResource(R.string.model_group_detail_thinking_medium)
                                ThinkingLevel.HIGH -> stringResource(R.string.model_group_detail_thinking_high)
                                ThinkingLevel.XHIGH -> stringResource(R.string.model_group_detail_thinking_xhigh)
                                ThinkingLevel.MAX -> stringResource(R.string.model_group_detail_thinking_max)
                                ThinkingLevel.ULTRA -> stringResource(R.string.model_group_detail_thinking_ultra)
                                ThinkingLevel.OFF -> stringResource(R.string.model_group_detail_thinking_low)
                            }
                        }
                        // Steps exclude OFF (the toggle above carries OFF
                        // semantics) and everything above the group ceiling.
                        val cases = ThinkingLevel.entries
                            .filter { it != ThinkingLevel.OFF && it.rank <= groupMaxThinkingLevel.rank }
                            .map { it to labelFor(it) }
                        SettingsCardBlock {
                            Text(
                                text = stringResource(R.string.model_group_detail_intensity),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                cases.forEachIndexed { idx, (level, label) ->
                                    SegmentedButton(
                                        selected = defaultThinkingLevel == level,
                                        onClick = {
                                            defaultThinkingLevel = level
                                            providerRepository.updateGroup(
                                                group.copy(defaultThinkingLevel = level)
                                            )
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = idx,
                                            count = cases.size,
                                        ),
                                    ) { Text(label) }
                                }
                            }
                        }
                    }
                    val contextEnabled = contextLimitTokens != null
                    // T-android-ctx-slider-cap (port iOS fa77f493): the slider
                    // is now a fixed 7-stop ladder decoupled from member-model
                    // metadata. We no longer derive an `unlimitedTokens` cap
                    // from `max(member.contextWindow)`, because that field is
                    // unreliable (image-output / router models report no
                    // window, falling through to the 128K heuristic default)
                    // and silently shrunk the picker. Per-model enforcement
                    // still happens at runtime via the consumer of
                    // `contextLimitTokens` (min(groupLimit, modelLimit)).
                    SettingsSwitchRow(
                        title = stringResource(R.string.model_group_detail_limit_context_window),
                        checked = contextEnabled,
                        onCheckedChange = { on ->
                            if (on) {
                                // Restore last user-selected value verbatim,
                                // defaulting to 128K for first-time activation.
                                val restored = group.lastContextLimitTokens ?: 128_000
                                contextLimitTokens = restored
                                providerRepository.updateGroup(
                                    group.copy(
                                        contextLimitTokens = restored,
                                        lastContextLimitTokens = restored,
                                    )
                                )
                            } else {
                                // Stash the current value into lastContextLimitTokens
                                // before nulling contextLimitTokens, so toggle ON
                                // can restore it (even after navigating away).
                                val stash = contextLimitTokens
                                contextLimitTokens = null
                                providerRepository.updateGroup(
                                    group.copy(
                                        contextLimitTokens = null,
                                        lastContextLimitTokens = stash
                                            ?: group.lastContextLimitTokens,
                                    )
                                )
                            }
                        },
                        icon = Icons.Default.Memory,
                        iconColor = Color(0xFF5856D6),
                        showDivider = contextEnabled,
                    )
                    if (contextEnabled) {
                        ContextLimitSlider(
                            value = contextLimitTokens,
                            unlimitedLabel = stringResource(R.string.model_group_detail_unlimited),
                            maxContextLabel = stringResource(R.string.model_group_detail_max_context),
                            onValueChange = { newTokens ->
                                contextLimitTokens = newTokens
                                providerRepository.updateGroup(
                                    group.copy(
                                        contextLimitTokens = newTokens,
                                        lastContextLimitTokens = newTokens,
                                    )
                                )
                            },
                        )
                    }
                }
            }

            // ── Destructive action: delete group ──────────────────────
            // "Add models" used to share this block; it now sits directly under
            // the member list, so the only thing left at the bottom is the
            // destructive action, with nothing adjacent to mis-tap.
            item {
                Spacer(Modifier.height(20.dp))
                MinisButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.model_group_detail_delete_group))
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // Confirm remove model from group
    if (entryToRemove != null) {
        val removingId = entryToRemove!!
        val removingEntry = config.modelEntries.find { it.id == removingId }
        AlertDialog(
            onDismissRequest = { entryToRemove = null },
            title = { Text(stringResource(R.string.model_group_detail_remove_model)) },
            text = {
                val name = removingEntry?.model?.displayName
                    ?: stringResource(R.string.model_group_detail_remove_this_model_fallback)
                Text(stringResource(R.string.model_group_detail_remove_named_model_confirm, name))
            },
            confirmButton = {
                MinisTextButton(
                    onClick = {
                        val newIds = memberIds.toMutableList().apply { remove(removingId) }
                        memberIds = newIds
                        providerRepository.updateGroup(group.copy(memberEntryIds = newIds.toMutableList()))
                        entryToRemove = null
                    },
                ) {
                    Text(stringResource(R.string.common_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { entryToRemove = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // Confirm delete group
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.model_group_detail_delete_group)) },
            text = { Text(stringResource(R.string.model_group_detail_delete_named_group_confirm, group.name)) },
            confirmButton = {
                MinisTextButton(
                    onClick = {
                        providerRepository.removeGroup(groupId)
                        showDeleteDialog = false
                        onBack()
                    },
                ) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * Discrete-step max-context slider that mirrors iOS `ContextLimitSlider`
 * (ModelGroupDetailView.swift, fa77f493). Step labels:
 * 32K / 64K / 128K / 200K / 400K / 1M / Unlimited.
 *
 * Fixed 7-stop ladder, always shown verbatim regardless of which models (if
 * any) are in the group. The previous behaviour clipped the ladder to the
 * largest member-declared context window, but model metadata is unreliable
 * (e.g. image-output / router models report no window and fall through to
 * the 128K heuristic default). Runtime now treats a finite slider value as
 * THE compact window (iOS `effectiveContextWindow`), so 1M actually raises
 * a 128K heuristic instead of being min()'d away.
 * Keeping the ladder fixed makes the UI a pure user-intent slider —
 * "I want this group to use N tokens of context" — and removes the entire family
 * of bugs where adding/removing a model would silently shrink the picker.
 *
 * The "Unlimited" stop maps to `Int.MAX_VALUE` in the binding; the runtime
 * consumer treats anything `>= Int.MAX_VALUE` as "no override, use the
 * model's native window".
 */
private const val CONTEXT_LIMIT_UNLIMITED_SENTINEL: Int = Int.MAX_VALUE

private val CONTEXT_LIMIT_PRESETS = listOf(
    32_000,
    64_000,
    128_000,
    200_000,
    400_000,
    1_000_000,
)

private fun formatPresetLabel(tokens: Int): String =
    if (tokens >= 1_000_000) "${tokens / 1_000_000}M" else "${tokens / 1_000}K"

@Composable
private fun ContextLimitSlider(
    value: Int?,
    unlimitedLabel: String,
    maxContextLabel: String,
    onValueChange: (Int) -> Unit,
) {
    data class Step(val label: String, val tokens: Int)
    val steps = remember(unlimitedLabel) {
        CONTEXT_LIMIT_PRESETS.map { Step(formatPresetLabel(it), it) } +
            Step(unlimitedLabel, CONTEXT_LIMIT_UNLIMITED_SENTINEL)
    }
    // Track the selected stop by index. Snap to nearest stop <= value
    // so legacy saved values (e.g. 350K) land on the next stop down (200K).
    var selectedIndex by remember(steps, value) {
        mutableStateOf(
            when {
                value == null -> steps.lastIndex
                value >= CONTEXT_LIMIT_UNLIMITED_SENTINEL -> steps.lastIndex
                else -> steps.indexOfLast { it.tokens <= value }.takeIf { it >= 0 } ?: 0
            }
        )
    }
    val currentLabel: String = run {
        val v = value
        when {
            v == null -> steps.last().label
            v >= CONTEXT_LIMIT_UNLIMITED_SENTINEL -> unlimitedLabel
            else -> steps.firstOrNull { it.tokens == v }?.label ?: "${v / 1000}K"
        }
    }
    SettingsCardBlock {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = maxContextLabel,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = currentLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = selectedIndex.toFloat(),
            onValueChange = { newIndex ->
                val idx = newIndex.roundToInt().coerceIn(0, steps.lastIndex)
                if (idx != selectedIndex) {
                    selectedIndex = idx
                    onValueChange(steps[idx].tokens)
                }
            },
            valueRange = 0f..steps.lastIndex.toFloat().coerceAtLeast(1f),
            steps = (steps.size - 2).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            Text(
                steps.first().label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                unlimitedLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
