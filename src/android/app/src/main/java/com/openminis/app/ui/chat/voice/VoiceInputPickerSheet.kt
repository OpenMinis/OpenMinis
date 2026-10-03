package com.openminis.app.ui.chat.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.PickerModalityFilter
import com.openminis.app.ui.components.UnifiedModelPickerSheet

/**
 * [T-android-voice-panel] Single-select voice INPUT engine picker — Android
 * port of iOS UnifiedModelPicker(config: .voiceInput()).
 *
 * [T-android-unified-model-picker] Now a thin config over the shared
 * [UnifiedModelPickerSheet], exactly how iOS's voiceInput() is a config over
 * UnifiedModelPicker. The previous bespoke body (VoicePickerSheetImpl) drew
 * its own flat radio rows and had visibly drifted from the main model
 * pickers' sectioned style; the shared sheet reuses modelEntryPickerItems,
 * so the System Recognition (Online/Offline) rows, modality chips, search,
 * and Quick Test all render identically to every other picker.
 *
 * Selection writes ProviderRepository.voiceInputOverrideEntryId (System
 * composite id / entry id / null = follow the group).
 */
@Composable
fun VoiceInputPickerSheet(
    providerRepository: ProviderRepository,
    onDismiss: () -> Unit,
    onEditProvider: ((instanceId: String) -> Unit)? = null,
) {
    val config by providerRepository.config.collectAsState()
    UnifiedModelPickerSheet(
        providerRepository = providerRepository,
        title = stringResource(R.string.voice_input_picker_title),
        modalityFilter = PickerModalityFilter.AUDIO_INPUT,
        boundGroup = config.voiceInputGroupId?.let { gid ->
            config.modelGroups.find { it.id == gid }
        },
        boundGroupName = providerRepository.voiceInputGroupName(),
        selectedId = providerRepository.voiceInputOverrideEntryId,
        onSelect = { providerRepository.voiceInputOverrideEntryId = it },
        onDismiss = onDismiss,
        onEditProvider = onEditProvider,
    )
}

/**
 * [T-android-tts-capsule] Single-select voice OUTPUT (TTS) model picker — the
 * output twin, mirroring iOS UnifiedModelPicker(config: .voiceOutput()) which
 * the speech-player capsule's model chip opens. Selection writes
 * ProviderRepository.voiceOutputOverrideEntryId — the per-device override
 * slot resolveVoiceOutputChoice() consults first.
 */
@Composable
fun VoiceOutputPickerSheet(
    providerRepository: ProviderRepository,
    onDismiss: () -> Unit,
    onEditProvider: ((instanceId: String) -> Unit)? = null,
) {
    val config by providerRepository.config.collectAsState()
    UnifiedModelPickerSheet(
        providerRepository = providerRepository,
        title = stringResource(R.string.tts_capsule_picker_title),
        modalityFilter = PickerModalityFilter.AUDIO_OUTPUT,
        boundGroup = config.voiceOutputGroupId?.let { gid ->
            config.modelGroups.find { it.id == gid }
        },
        boundGroupName = providerRepository.voiceOutputGroupName(),
        selectedId = providerRepository.voiceOutputOverrideEntryId,
        onSelect = { providerRepository.voiceOutputOverrideEntryId = it },
        onDismiss = onDismiss,
        onEditProvider = onEditProvider,
    )
}
