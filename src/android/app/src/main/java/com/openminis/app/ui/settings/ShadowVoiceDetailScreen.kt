package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.hasAudioInput
import com.openminis.app.data.model.hasAudioOutput
import com.openminis.app.data.repository.ProviderRepository

/**
 * [T-android-provider-voice] Read-only detail of a shadow Voice Service —
 * Android port of iOS ShadowVoiceProviderDetailView. Shows where the
 * credential/endpoint come from (the underlying text provider instance), the
 * ASR + TTS model lists, and the per-instance "Show in Voice Services" toggle.
 */
@Composable
fun ShadowVoiceDetailScreen(
    instanceId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val instance = config.instances.find { it.id == instanceId }
    if (instance == null) {
        onBack()
        return
    }
    val entries = config.modelEntries.filter { it.providerInstanceId == instanceId }
    val inputModels = entries.filter { it.model.hasAudioInput }
    val outputModels = entries.filter { it.model.hasAudioOutput }
    val shadowDisabled = providerRepository.isVoiceShadowDisabled(instanceId)
    // Tap a voice model row → modality-matched Quick Test (mirrors iOS Voice
    // Services Quick Test).
    var quickTestEntry by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<ModelEntry?>(null)
    }

    SettingsScaffold(
        title = instance.label,
        onBack = onBack,
    ) {
        // Provenance: this is a mirror of the text provider, not a stored entity.
        SettingsSection(header = stringResource(R.string.voice_service_source)) {
            InfoRow(
                label = stringResource(R.string.voice_service_credential_from),
                value = instance.label,
            )
            InfoRow(
                label = stringResource(R.string.voice_service_endpoint),
                value = instance.customBaseURL ?: instance.providerType.displayName,
            )
        }

        if (inputModels.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.voice_service_speech_to_text)) {
                inputModels.forEach { VoiceModelRow(it, isInput = true, onClick = { quickTestEntry = it }) }
            }
        }
        if (outputModels.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.voice_service_text_to_speech)) {
                outputModels.forEach { VoiceModelRow(it, isInput = false, onClick = { quickTestEntry = it }) }
            }
        }

        SettingsSection(header = null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.voice_service_show_in_voice_services),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                SettingsSwitch(
                    checked = !shadowDisabled,
                    onCheckedChange = { shown ->
                        providerRepository.setVoiceShadowDisabled(instanceId, !shown)
                    },
                )
            }
        }
        Text(
            text = stringResource(R.string.voice_service_shared_credential_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(40.dp))
    }

    quickTestEntry?.let { entry ->
        com.openminis.app.ui.components.QuickTestSheet(
            entry = entry,
            providerRepository = providerRepository,
            onDismiss = { quickTestEntry = null },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun VoiceModelRow(entry: ModelEntry, isInput: Boolean, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isInput) Icons.Outlined.Mic else Icons.AutoMirrored.Outlined.VolumeUp,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = entry.model.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = entry.baseModel.id,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }
}
