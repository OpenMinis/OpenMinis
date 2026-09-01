package com.openminis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.SystemVoiceEntries
import com.openminis.app.data.model.hasAudioInput
import com.openminis.app.data.model.hasAudioOutput
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.normalizeModalities

/**
 * [T-android-provider-voice] First-class modality scoping for the shared
 * picker — Android port of iOS ModelPickerConfig.explicitPreferModality.
 * iOS expresses preference as a ModelModality bitset with a superset match;
 * in practice the only prefs used are the two voice directions, so Android
 * models them directly:
 *
 *  - [AUDIO_INPUT]  — ASR scenario: only audio-consuming entries qualify,
 *    and the System Recognition (Online/Offline) virtual entries lead.
 *  - [AUDIO_OUTPUT] — TTS scenario: only audio-producing entries qualify,
 *    and the System Voice (Auto) virtual entry leads.
 *
 * When a filter is active the picker also injects the matching System
 * virtual entries as their own leading "System" section (iOS
 * candidateEntries appends systemASROnline/Offline / systemTTS). Callers in
 * a voice scenario should keep provider sections expanded (iOS d4e3798f
 * seedCollapse: multi-voice vendors would fold all-but-one voice).
 */
enum class PickerModalityFilter {
    AUDIO_INPUT,
    AUDIO_OUTPUT,
    // [T-android-vision-group] Vision scenario: only image-consuming entries
    // qualify. No System virtual entry — there is no on-device vision engine.
    IMAGE_INPUT;

    fun matches(model: LLMModel): Boolean = when (this) {
        AUDIO_INPUT -> model.hasAudioInput
        AUDIO_OUTPUT -> model.hasAudioOutput
        IMAGE_INPUT -> model.hasImageInput
    }

    /** System virtual entries that serve this direction, in display order. */
    fun systemEntries(): List<ModelEntry> = when (this) {
        AUDIO_INPUT -> listOf(SystemVoiceEntries.asrOnline, SystemVoiceEntries.asrOffline)
        AUDIO_OUTPUT -> listOf(SystemVoiceEntries.tts)
        IMAGE_INPUT -> emptyList()
    }
}

/**
 * T185 — shared multi-select picker for model entries.
 *
 * Pre-T185 the agent-loop add sheet (`AgentLoopAddSheets.kt`) and
 * `AddModelsToGroupScreen` rendered the same conceptual list with
 * different visuals: the former a plain ModalBottomSheet with bare
 * tap-and-add rows, the latter a sectioned multi-select with collapsible
 * per-provider cards, search, and a confirm button. User reported the
 * mismatch (T185) — the iOS counterparts use the same sectioned-list
 * style for both. Extract the AddModelsToGroupScreen layout into a
 * reusable [LazyListScope] extension so both call sites share one
 * implementation.
 *
 * [modelEntryPickerItems] is consumed inside a parent `LazyColumn`
 * rather than being a self-contained Composable so the caller controls
 * the surrounding chrome (Scaffold + TopAppBar + confirm button) and
 * retains access to the LazyListState for reorder / scroll-to.
 */
@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.modelEntryPickerItems(
    instances: List<ProviderInstance>,
    availableEntries: List<ModelEntry>,
    selectedIds: Set<String>,
    onToggleSelection: (String) -> Unit,
    searchQuery: MutableState<String>,
    collapsedInstanceIds: MutableState<Set<String>>,
    @StringRes emptyTextRes: Int,
    @StringRes emptySearchTextRes: Int,
    @StringRes searchPlaceholderRes: Int,
    @StringRes clearContentDescriptionRes: Int,
    // [T-android-model-quick-test] Optional per-row Quick Test button. When
    // provided, each model row shows a compact bolt icon that opens the shared
    // QuickTestSheet for that entry (the caller owns the sheet + repository).
    onQuickTest: ((ModelEntry) -> Unit)? = null,
    // [T-android-provider-voice] Modality scoping (see PickerModalityFilter):
    // null = no filtering (all entries, no System injection) — the pre-voice
    // behavior every existing caller keeps by default.
    modalityFilter: PickerModalityFilter? = null,
    // Ids to exclude from the injected System entries (e.g. ids already in the
    // target group). Regular entries are excluded by the caller via
    // [availableEntries]; System entries are built here, hence this knob.
    excludeIds: Set<String> = emptySet(),
    // Localized display label for the injected System provider section.
    // Resolved by the Composable caller via stringResource (this extension is
    // not @Composable); mirrors iOS String(localized: "System").
    systemProviderLabel: String = "System",
) {
    val q = searchQuery.value.lowercase()
    val isSearching = searchQuery.value.isNotBlank()

    fun matchesSearch(entry: ModelEntry): Boolean =
        !isSearching ||
            entry.model.displayName.lowercase().contains(q) ||
            entry.model.id.lowercase().contains(q)

    // System section leads when a modality filter is active (iOS: the System
    // synthetic instance is first in entriesByInstance).
    val systemPair: Pair<ProviderInstance, List<ModelEntry>>? = modalityFilter
        ?.systemEntries()
        ?.filter { it.id !in excludeIds && matchesSearch(it) }
        ?.takeIf { it.isNotEmpty() }
        ?.let { SystemVoiceEntries.syntheticInstance(systemProviderLabel) to it }

    val instanceWithEntries = listOfNotNull(systemPair) + instances
        .filter { it.isEnabled && !SystemVoiceEntries.isSystemEntryId(it.id) }
        .mapNotNull { instance ->
            val entries = availableEntries.filter { entry ->
                entry.providerInstanceId == instance.id &&
                    (modalityFilter == null || modalityFilter.matches(entry.model))
            }
            val filtered = entries.filter { matchesSearch(it) }
            if (filtered.isEmpty()) null else instance to filtered
        }

    // Force expand everything while searching so the user actually
    // sees results — collapsed sections during search would hide hits.
    val effectiveCollapsed = if (isSearching) emptySet() else collapsedInstanceIds.value

    item("__search__") {
        OutlinedTextField(
            value = searchQuery.value,
            onValueChange = { searchQuery.value = it },
            placeholder = { Text(stringResource(searchPlaceholderRes)) },
            singleLine = true,
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (searchQuery.value.isNotEmpty()) {
                    IconButton(onClick = { searchQuery.value = "" }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(clearContentDescriptionRes),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
        )
    }

    instanceWithEntries.forEach { (instance, entries) ->
        val isCollapsed = instance.id in effectiveCollapsed
        val dotColor = providerDotColor(instance.providerType)

        item(key = "header_${instance.id}") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    instance.label.ifEmpty { instance.providerType.displayName },
                    // [T-android-model-picker-polish] Section headers outrank
                    // their rows. titleSmall is 14sp Medium while model names
                    // are bodyMedium SemiBold — same size, heavier weight — so
                    // the header read as the weaker of the two. Matches the
                    // chat model picker.
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        // Neutral disclosure control, 24dp — a tinted or
                        // oversized circle competes with the provider name.
                        .size(24.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                            CircleShape,
                        )
                        .clip(CircleShape)
                        .clickable {
                            collapsedInstanceIds.value = if (isCollapsed) {
                                collapsedInstanceIds.value - instance.id
                            } else {
                                collapsedInstanceIds.value + instance.id
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // [T-android-model-picker-polish] Hairline under the provider name.
            // The rows below are models, not more provider chrome; without a
            // rule the header read as the first list item. Matches the chat
            // model picker.
            HorizontalDivider(
                modifier = Modifier.padding(start = 20.dp, end = 16.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            )
        }

        if (isCollapsed) {
            // Collapsed preview: first row + "N models" hint to remind the
            // user how many are inside the section without expanding.
            item(key = "collapsed_${instance.id}") {
                val firstEntry = entries.firstOrNull() ?: return@item
                val isSelected = firstEntry.id in selectedIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainer,
                            RoundedCornerShape(12.dp),
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onToggleSelection(firstEntry.id) }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SelectionDot(isSelected)
                    Spacer(Modifier.width(10.dp))
                    Box(modifier = Modifier.size(6.dp).background(dotColor, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        firstEntry.model.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        // Was the hardcoded English "N models" — the one raw
                        // string in a picker whose every other label is a
                        // resource, so Chinese users saw "413 models".
                        androidx.compose.ui.res.pluralStringResource(
                            R.plurals.model_picker_models_count,
                            entries.size,
                            entries.size,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        } else {
            item(key = "entries_${instance.id}") {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainer,
                            RoundedCornerShape(12.dp),
                        ),
                ) {
                    entries.forEachIndexed { index, entry ->
                        val isSelected = entry.id in selectedIds
                        val rowShape = when {
                            entries.size == 1 -> RoundedCornerShape(12.dp)
                            index == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                            index == entries.size - 1 -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                            else -> RoundedCornerShape(0.dp)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(rowShape)
                                .clickable { onToggleSelection(entry.id) }
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SelectionDot(isSelected)
                            Spacer(Modifier.width(10.dp))
                            Box(modifier = Modifier.size(6.dp).background(dotColor, CircleShape))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    entry.model.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                // [T-android-provider-voice] Modality chips
                                // (iOS entryRow modalityBadges: img / audio /
                                // video / pdf and the -out variants).
                                //
                                // [T-android-modality-chip] FlowRow, NOT Row: this
                                // was the one call site 0e7b742cc missed when it
                                // fixed the same defect in the three picker sheets.
                                // In a plain Row an overflowing chip is squeezed
                                // until Compose wraps it character-by-character —
                                // "img-out" drew as a vertical letter column, and
                                // because the Row is CenterVertically that column
                                // inflated the row height until the model name
                                // floated above the selection dot. The shared
                                // ModalityBadge (maxLines=1 + softWrap=false) stops
                                // a chip breaking internally; FlowRow is what gives
                                // it somewhere to go, wrapping whole chips instead.
                                // Overflow here is about total chip width, not any
                                // single label: a long model id plus 4 chips
                                // overflows the weight(1f) column just as surely.
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    itemVerticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        entry.model.id,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                    modalityBadges(entry.model).forEach { badge ->
                                        ModalityBadge(badge)
                                    }
                                }
                            }
                            // System virtual entries have no cloud endpoint to
                            // smoke-test — no bolt on those rows.
                            if (onQuickTest != null && !SystemVoiceEntries.isSystemEntryId(entry.id)) {
                                IconButton(
                                    onClick = { onQuickTest(entry) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Bolt,
                                        contentDescription = stringResource(R.string.quicktest_button),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                        if (index < entries.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            )
                        }
                    }
                }
            }
        }
    }

    if (instanceWithEntries.isEmpty()) {
        item("__empty__") {
            Text(
                stringResource(if (isSearching) emptySearchTextRes else emptyTextRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun SelectionDot(isSelected: Boolean) {
    Icon(
        if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
        contentDescription = null,
        tint = if (isSelected) Color(0xFF007AFF)
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.size(20.dp),
    )
}

/**
 * [T-android-provider-voice] Non-text modality chips for a model row —
 * mirrors iOS UnifiedModelPicker.modalityBadges (same labels, same order:
 * inputs first, then outputs; text never badged).
 */
fun modalityBadges(model: LLMModel): List<String> {
    val ins = model.inputModalities.normalizeModalities() ?: emptyList()
    val outs = model.outputModalities.normalizeModalities() ?: emptyList()
    val badges = mutableListOf<String>()
    if ("image" in ins) badges.add("img")
    if ("audio" in ins) badges.add("audio")
    if ("video" in ins) badges.add("video")
    if ("pdf" in ins) badges.add("pdf")
    if ("image" in outs) badges.add("img-out")
    if ("audio" in outs) badges.add("audio-out")
    if ("video" in outs) badges.add("video-out")
    return badges
}

/**
 * [T-android-modality-chip] One modality chip, iOS entryRow badge recipe
 * (UnifiedModelPicker.swift:1109): 9pt medium, tertiarySystemFill rounded-3
 * background. Two Android-specific constraints baked in:
 *  - fill is onSurface@8% rather than surfaceContainerHighest — the chips sit
 *    ON surfaceContainerHigh cards, where the container tone is the same
 *    color and the "gray capsule" disappeared entirely;
 *  - maxLines=1 + softWrap=false so a chip can NEVER break internally.
 *    Compose Text in an overflowing Row wraps character-by-character
 *    ("audio-out" rendered as a vertical letter column); hosts must place
 *    chips in a FlowRow so overflow wraps whole chips to the next line.
 */
@Composable
fun ModalityBadge(badge: String) {
    Text(
        badge,
        fontSize = 9.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        softWrap = false,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                RoundedCornerShape(3.dp),
            )
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** Provider color dot — same RGB across ChatScreen, AddModelsToGroup, and
 *  the agent-loop sheets so the visual cue stays consistent everywhere. */
fun providerDotColor(providerType: ProviderType?): Color = when (providerType) {
    ProviderType.anthropic -> Color(0xFFAB47BC)
    ProviderType.gemini -> Color(0xFF42A5F5)
    ProviderType.openAI -> Color(0xFF4CAF50)
    ProviderType.openRouter -> Color(0xFF00BCD4)
    ProviderType.xAI -> Color(0xFFFF7043)
    ProviderType.kimiCode -> Color(0xFF5C6BC0) // indigo — Kimi accent
    // [T-android-provider-type-parity] Responses API instances are
    // OpenAI under the hood — same green dot. Undrivable types share
    // the neutral gray used for "no provider".
    ProviderType.openAIResponses -> Color(0xFF4CAF50)
    ProviderType.antigravity,
    ProviderType.unsupported -> Color(0xFF8E8E93)
    null -> Color(0xFF8E8E93)
}
