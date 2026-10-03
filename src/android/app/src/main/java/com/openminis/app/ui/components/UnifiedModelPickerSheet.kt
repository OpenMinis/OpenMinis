package com.openminis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.SystemVoiceEntries
import com.openminis.app.data.model.SystemVoiceIds
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.speech.VoiceOutputState
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-android-unified-model-picker] Android counterpart of iOS
 * UnifiedModelPicker: ONE single-select model picker sheet, parameterized the
 * way iOS parameterizes ModelPickerConfig (voiceInput() / voiceOutput() are
 * just configs over the same view).
 *
 * [T-android-picker-mainsheet-language] Rendered in the MAIN model picker's
 * visual language (ChatModelPickerSheet.ModelPickerSheet): 42dp capsule
 * search directly under the title, section CARDS on surfaceContainerHigh
 * with 14dp corners and EMBEDDED headers, titleMedium SemiBold header
 * typography, expandable bound-group row, per-provider cards with collapse
 * chevrons, provider dots, modality chips and Quick Test bolts. The previous
 * cut borrowed AddModelsToGroup's flat multi-select rows
 * (modelEntryPickerItems), which read as a different app next to the main
 * picker — the exact drift iOS avoids by having one UnifiedModelPicker.
 *
 * Selection semantics (unchanged): tap = select + dismiss; selecting the
 * bound group row clears the override (null = follow the group).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UnifiedModelPickerSheet(
    providerRepository: ProviderRepository,
    title: String,
    modalityFilter: PickerModalityFilter,
    /** The bound group (iOS groupScope .single); null hides the section. */
    boundGroup: ModelGroup?,
    boundGroupName: String?,
    /** Current override entry id; null = follow the bound group. */
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    /**
     * [T-android-picker-mainsheet-language] Optional "Edit" affordance on the
     * group card's header (mirrors the main picker's onEditGroups and iOS's
     * group-section Edit). Null hides the button — callers without a
     * navigation route (the floating capsule) simply don't show it.
     */
    onEditGroups: (() -> Unit)? = null,
    /**
     * [T-android-picker-provider-edit] Edit button on each provider section
     * header, as in the chat picker and iOS UnifiedModelPicker (698cc65c6).
     * Only stored instances have sections here; the built-in System card has
     * no detail page and never gets the button. Null hides it.
     */
    onEditProvider: ((instanceId: String) -> Unit)? = null,
) {
    val config by providerRepository.config.collectAsState()
    var quickTestEntry by remember { mutableStateOf<ModelEntry?>(null) }
    var searchText by remember { mutableStateOf("") }
    var groupExpanded by remember { mutableStateOf(false) }
    // [T-android-system-voice-catalog] Device TTS voice rows (voice-output
    // scenarios only) + the currently-picked voice for selection marks.
    val pickerContext = androidx.compose.ui.platform.LocalContext.current
    var systemVoices by remember {
        mutableStateOf(emptyList<com.openminis.app.speech.SystemTtsVoiceCatalog.SystemVoice>())
    }
    val pickedSystemVoice by com.openminis.app.speech.VoiceOutputState
        .systemVoiceName.collectAsState()
    androidx.compose.runtime.LaunchedEffect(modalityFilter) {
        if (modalityFilter == PickerModalityFilter.AUDIO_OUTPUT) {
            com.openminis.app.speech.VoiceOutputState.init(pickerContext)
            systemVoices = com.openminis.app.speech.SystemTtsVoiceCatalog.voices(pickerContext)
        }
    }
    // Voice scenarios keep provider sections expanded (iOS d4e3798f
    // seedCollapse note: multi-voice vendors would fold all-but-one voice).
    var collapsedInstanceIds by remember { mutableStateOf(setOf<String>()) }

    fun select(id: String?) {
        onSelect(id)
        onDismiss()
    }

    // [T-picker-search-debounce] [T-picker-search-relevance] Same search as
    // the chat picker: debounced, and scored (exact > prefix > word prefix >
    // substring > subsequence) instead of a plain `contains`.
    val query = rememberDebouncedQuery(searchText)
    fun matches(text: String): Boolean = query.isEmpty() || ModelSearch.score(text, query) > 0

    // [T-android-voice-picker-active] Which group member actually serves the
    // next request — recomputed whenever the config changes so the badge can't
    // go stale after an instance is disabled or a member is removed.
    val activeGroupMemberId = remember(config, modalityFilter) {
        when (modalityFilter) {
            PickerModalityFilter.AUDIO_INPUT ->
                providerRepository.activeVoiceGroupMemberId(output = false)
            PickerModalityFilter.AUDIO_OUTPUT ->
                providerRepository.activeVoiceGroupMemberId(output = true)
            else -> null
        }
    }

    val cardColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val cardShape = RoundedCornerShape(14.dp)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Slim drag handle, same as the main picker / StandardChatSheet — the
        // Material default puts ~44dp of whitespace above the title.
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 4.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .background(
                            color = ChatColors.secondaryText.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }
        },
    ) {
        // 0.9f fixed height + nav-bar inset, exactly like the main picker —
        // a content-wrapping column here let the voice pickers (whose provider
        // sections seed expanded) grow to cover the full screen.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .navigationBarsPadding(),
        ) {
            // Title bar — CENTERED title + trailing Done, byte-for-byte the
            // main picker's layout (ChatModelPickerSheet title bar).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    MinisTextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.model_picker_done))
                    }
                }
            }

            // ── Search bar — the main picker's exact OutlinedTextField
            // DecorationBox recipe (ChatModelPickerSheet [T-android-search-height]):
            // outlined 50%-radius container so focus draws the primary ring,
            // trailing ✕ clears. The previous filled-capsule stand-in had no
            // focus feedback and no clear affordance — visibly different the
            // moment the field was tapped.
            val searchInteraction = remember { MutableInteractionSource() }
            BasicTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(42.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                interactionSource = searchInteraction,
                decorationBox = { innerTextField ->
                    OutlinedTextFieldDefaults.DecorationBox(
                        value = searchText,
                        visualTransformation = VisualTransformation.None,
                        innerTextField = innerTextField,
                        placeholder = { Text(stringResource(R.string.model_picker_search_placeholder)) },
                        label = null,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingIcon = {
                            if (searchText.isNotEmpty()) {
                                IconButton(onClick = { searchText = "" }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.model_picker_search_clear),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        enabled = true,
                        isError = false,
                        interactionSource = searchInteraction,
                        colors = OutlinedTextFieldDefaults.colors(),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        container = {
                            OutlinedTextFieldDefaults.Container(
                                enabled = true,
                                isError = false,
                                interactionSource = searchInteraction,
                                colors = OutlinedTextFieldDefaults.colors(),
                                shape = RoundedCornerShape(50),
                            )
                        },
                    )
                },
            )

            // [T-android-picker-scroll-guard] See rememberSheetScrollGuard: fling
            // momentum is absorbed, a deliberate drag still dismisses.
            val unifiedPickerScrollGuard = rememberSheetScrollGuard()

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .nestedScroll(unifiedPickerScrollGuard),
            ) {
                // ── Model Groups card ──
                if (boundGroup != null && matches(boundGroupName ?: "")) {
                    item("group_card") {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .background(cardColor, cardShape)
                                .clip(cardShape),
                        ) {
                            val editTrailing: (@Composable () -> Unit)? =
                                if (onEditGroups == null) null else {
                                    {
                                        MinisTextButton(
                                            onClick = onEditGroups,
                                            modifier = Modifier.padding(end = 8.dp),
                                        ) {
                                            Text(stringResource(R.string.model_picker_groups_edit))
                                        }
                                    }
                                }
                            PickerSectionHeader(
                                stringResource(R.string.model_picker_groups_section),
                                trailing = editTrailing,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { select(null) }
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Group-row anatomy copied from the MAIN picker
                                // (ChatModelPickerSheet:600-762): GREEN check,
                                // blue Layers glyph, icon+text strategy badge,
                                // bodySmall member count, 24dp circled chevron.
                                //
                                // The group stays CHECKED while the effective
                                // choice is one of its own members — group
                                // membership and entry selection are separate
                                // axes, exactly as iOS models them
                                // (isGroupSelected == currentGroupId, tested
                                // independently of the entry's isActive). Keying
                                // this on `selectedId == null` alone made
                                // pinning a member look like leaving the group,
                                // even though the group is still what's bound
                                // and still what the override falls back to.
                                val groupSelected =
                                    selectedId == null ||
                                        boundGroup.memberEntryIds.contains(selectedId)
                                Icon(
                                    if (groupSelected) Icons.Default.CheckCircle
                                    else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (groupSelected) Color(0xFF34C759)
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Icon(
                                    Icons.Default.Layers,
                                    contentDescription = null,
                                    tint = Color(0xFF007AFF),
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            boundGroupName ?: "",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                                    RoundedCornerShape(8.dp),
                                                )
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.ArrowCircleDown,
                                                contentDescription = null,
                                                modifier = Modifier.size(9.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            )
                                            Spacer(Modifier.width(2.dp))
                                            Text(
                                                "FB",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            )
                                        }
                                    }
                                    // Count what the expansion can actually
                                    // RENDER, not the raw member-id count —
                                    // otherwise a member that no longer
                                    // resolves still inflates the number and
                                    // the row promises rows it can't show.
                                    Text(
                                        stringResource(
                                            R.string.voice_input_picker_group_models,
                                            boundGroup.memberEntryIds.count { id ->
                                                config.modelEntries.any { it.id == id } ||
                                                    SystemVoiceEntries.resolve(id) != null
                                            },
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                            CircleShape,
                                        )
                                        .clip(CircleShape)
                                        .clickable { groupExpanded = !groupExpanded },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        if (groupExpanded) Icons.Default.KeyboardArrowUp
                                        else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (groupExpanded) {
                                // Fall back to the VIRTUAL system entries: the
                                // default voice groups are seeded with
                                // "__builtin_system_speech__/…" member ids,
                                // which are synthesized on demand and never
                                // live in config.modelEntries. Resolving only
                                // against modelEntries dropped every member of
                                // those groups, so "2 models" expanded to an
                                // empty list. Same fallback ModelGroupDetail
                                // already uses.
                                val members = boundGroup.memberEntryIds.mapNotNull { id ->
                                    config.modelEntries.find { it.id == id }
                                        ?: SystemVoiceEntries.resolve(id)
                                }
                                members.forEach { entry ->
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    )
                                    val inst = config.instances
                                        .find { it.id == entry.providerInstanceId }
                                    // "In effect" — the member that actually
                                    // serves the next request, from EITHER
                                    // source: an explicit override pointing at
                                    // it, or (group-follow) the member the
                                    // group routes to. iOS fills the member
                                    // checkmark from exactly this
                                    // (UnifiedModelPicker expandedEntryRow's
                                    // isActive), not from the override alone —
                                    // a filled circle here means "this is the
                                    // one", which is the question the
                                    // expansion exists to answer.
                                    val isActive = if (selectedId != null) {
                                        selectedId == entry.id
                                    } else {
                                        entry.id == activeGroupMemberId
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { select(entry.id) }
                                            .padding(
                                                start = 46.dp,
                                                end = 16.dp,
                                                top = 10.dp,
                                                bottom = 10.dp,
                                            ),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        SelectionMark(selected = isActive)
                                        Spacer(Modifier.width(10.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(
                                                    providerDotColor(inst?.providerType),
                                                    CircleShape,
                                                ),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            entry.model.displayName.ifBlank { entry.model.id },
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        // Badge only in the group-follow case:
                                        // it explains WHY a row is checked when
                                        // the user didn't pick it. With an
                                        // explicit override the check is
                                        // self-explanatory and the badge would
                                        // just be noise.
                                        if (isActive && selectedId == null) {
                                            Spacer(Modifier.width(6.dp))
                                            // Main picker's green "Active"
                                            // badge (ChatModelPickerSheet:1178).
                                            Text(
                                                stringResource(R.string.model_picker_active_badge),
                                                fontSize = 9.sp,
                                                lineHeight = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color(0xFF34C759),
                                                modifier = Modifier
                                                    .background(
                                                        Color(0xFF34C759).copy(alpha = 0.1f),
                                                        RoundedCornerShape(50),
                                                    )
                                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                }
                                // The last member's 10dp row padding alone left
                                // it visually crowding the card's bottom edge —
                                // the collapsed row's own 13dp padding sets the
                                // expectation, so pad the expansion to match.
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                    item("group_footer") {
                        Text(
                            stringResource(R.string.voice_input_picker_group_footer),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                        )
                    }
                }

                // ── System card ──
                val systemEntries = modalityFilter.systemEntries()
                    .filter { matches(it.model.displayName) }
                if (systemEntries.isNotEmpty()) {
                    item("system_card") {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .background(cardColor, cardShape)
                                .clip(cardShape),
                        ) {
                            PickerSectionHeader(stringResource(R.string.voice_input_picker_system))
                            systemEntries.forEachIndexed { i, entry ->
                                if (i > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            // Auto row: clear any picked voice.
                                            if (modalityFilter == PickerModalityFilter.AUDIO_OUTPUT) {
                                                VoiceOutputState.setSystemVoice(null, null)
                                            }
                                            select(entry.id)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // In effect from EITHER source, same rule as
                                    // the group members above. The extra TTS
                                    // clause stands: once a specific device
                                    // voice is picked, the mark belongs to that
                                    // voice's row, not to this "Auto" row.
                                    val systemInEffect =
                                        (if (selectedId != null) selectedId == entry.id
                                        else entry.id == activeGroupMemberId) &&
                                            (modalityFilter != PickerModalityFilter.AUDIO_OUTPUT ||
                                                pickedSystemVoice == null)
                                    SelectionMark(selected = systemInEffect)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            entry.model.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            systemEntrySubtitle(entry),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (systemInEffect && selectedId == null) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            stringResource(R.string.model_picker_active_badge),
                                            fontSize = 9.sp,
                                            lineHeight = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF34C759),
                                            modifier = Modifier
                                                .background(
                                                    Color(0xFF34C759).copy(alpha = 0.1f),
                                                    RoundedCornerShape(50),
                                                )
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                            // [T-android-system-voice-catalog] Every device TTS
                            // voice as its own selectable row — what makes the
                            // iOS System section list "Tingting (Chinese)…,
                            // Anna (German)…" individually. The voice choice is
                            // a system-engine detail (VoiceOutputState pref),
                            // layered under the entry-level selection: tapping a
                            // voice both records it and selects the system
                            // engine. Auto (the plain row above) clears it.
                            if (modalityFilter == PickerModalityFilter.AUDIO_OUTPUT) {
                                systemVoices
                                    .filter { matches(it.label) }
                                    .forEach { v ->
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                        )
                                        val sysId = systemEntries.firstOrNull()?.id
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    VoiceOutputState.setSystemVoice(v.name, v.label)
                                                    select(sysId)
                                                }
                                                .padding(horizontal = 16.dp, vertical = 11.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            SelectionMark(
                                                selected = selectedId == sysId &&
                                                    pickedSystemVoice == v.name,
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    v.label,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                                Text(
                                                    v.name,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }

                // ── Per-provider cards ──
                val entriesByInstance = config.instances
                    .filter { it.isEnabled }
                    .mapNotNull { inst ->
                        // [T-model-release-ranking] Provider sections follow the
                        // user's arrangement; models INSIDE a section follow
                        // release date. This list is built from the config
                        // snapshot rather than ProviderRepository.entriesFor(),
                        // so it does not inherit that accessor's sort — without
                        // this call the picker shows models in raw config order
                        // (instance cluster, then id alphabetically) while the
                        // provider-detail list right next to it shows them
                        // newest-first. iOS fixed the same divergence in
                        // UnifiedModelPicker.swift.
                        val sectionEntries = com.openminis.app.data.repository.ModelEntryRanking
                            .sortedByReleaseRank(
                                config.modelEntries.filter { e ->
                                    e.providerInstanceId == inst.id && !e.isHidden &&
                                        modalityFilter.matches(e.model)
                                },
                            )
                        // A provider-name hit shows the whole section (iOS
                        // T-picker-search-provider-name); otherwise matches,
                        // most relevant first — ties keep newest-first.
                        val entries = if (matches(inst.label.ifEmpty { inst.providerType.displayName })) sectionEntries
                            else ModelSearch.rank(sectionEntries, query)
                        if (entries.isEmpty()) null else inst to entries
                    }
                entriesByInstance.forEach { (inst, entries) ->
                    item("p_${inst.id}") {
                        val collapsed = collapsedInstanceIds.contains(inst.id)
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .background(cardColor, cardShape)
                                .clip(cardShape),
                        ) {
                            // Provider header — main picker's embedded header:
                            // start16/end8 top10/bottom8, 24dp circled chevron,
                            // hairline under the name.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val providerName = inst.label.ifEmpty { inst.providerType.displayName }
                                Text(
                                    providerName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (onEditProvider != null) {
                                    // [T-android-picker-provider-edit-text] A text "Edit" link,
                                    // styled like the Model Groups "Edit" (iOS a0acb1c0f replaced its
                                    // icon button with this). Compact rather than a MinisTextButton so
                                    // the header row keeps its height; still its own tap target, so it
                                    // never folds the section.
                                    val editLabel = stringResource(R.string.model_picker_edit_provider, providerName)
                                    Text(
                                        stringResource(R.string.model_picker_groups_edit),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier
                                            .padding(end = 4.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { onEditProvider(inst.id) }
                                            .semantics { contentDescription = editLabel }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                            CircleShape,
                                        )
                                        .clip(CircleShape)
                                        .clickable {
                                            collapsedInstanceIds =
                                                if (collapsed) collapsedInstanceIds - inst.id
                                                else collapsedInstanceIds + inst.id
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        if (collapsed) Icons.Default.KeyboardArrowDown
                                        else Icons.Default.KeyboardArrowUp,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            )
                            if (!collapsed) {
                                entries.forEachIndexed { i, entry ->
                                    if (i > 0) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                        )
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { select(entry.id) }
                                            .padding(
                                                start = 16.dp,
                                                end = 8.dp,
                                                top = 11.dp,
                                                bottom = 11.dp,
                                            ),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        SelectionMark(selected = selectedId == entry.id)
                                        Spacer(Modifier.width(10.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(
                                                    providerDotColor(inst.providerType),
                                                    CircleShape,
                                                ),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                entry.model.displayName.ifBlank { entry.model.id },
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            // FlowRow, NOT Row: with 3-4 chips
                                            // next to a long model id an
                                            // overflowing Row squeezes each
                                            // Text into a vertical letter
                                            // column; here whole chips wrap to
                                            // the next line instead.
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                itemVerticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    entry.baseModel.id,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                modalityBadges(entry.model).forEach { badge ->
                                                    ModalityBadge(badge)
                                                }
                                            }
                                        }
                                        IconButton(
                                            onClick = { quickTestEntry = entry },
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
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
                item("bottom_pad") { Spacer(Modifier.padding(12.dp)) }
            }
        }
    }

    quickTestEntry?.let { entry ->
        QuickTestSheet(
            entry = entry,
            providerRepository = providerRepository,
            onDismiss = { quickTestEntry = null },
        )
    }
}

/** Card-embedded section header, main-picker typography. */
@Composable
private fun PickerSectionHeader(
    label: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
        )
        trailing?.invoke()
    }
}

@Composable
private fun SelectionMark(selected: Boolean) {
    Icon(
        if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
        contentDescription = null,
        tint = if (selected) Color(0xFF007AFF)
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.size(20.dp),
    )
}

/** Subtitle for the injected System virtual rows, keyed on the model id. */
@Composable
private fun systemEntrySubtitle(entry: ModelEntry): String = when (entry.model.id) {
    SystemVoiceIds.SYSTEM_TTS -> stringResource(R.string.voice_system_tts_subtitle)
    SystemVoiceIds.SYSTEM_ASR_ONLINE -> stringResource(R.string.voice_system_online_subtitle)
    SystemVoiceIds.SYSTEM_ASR_OFFLINE -> stringResource(R.string.voice_system_offline_subtitle)
    else -> ""
}
