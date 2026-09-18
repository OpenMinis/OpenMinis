package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.normalizeModalityName
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.RowLabel
import com.openminis.app.ui.components.SectionTextField
import com.openminis.app.R
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton

/**
 * Detail / edit screen for a single ModelEntry. T210: brought to iOS
 * parity with five inset-grouped sections — Identity, Capabilities,
 * Visibility, Input Modality, Output Modality (+ optional Reset). The
 * data layer (`ModelOverrides.contextWindow`, `supportsReasoning`,
 * `inputModalities`, `outputModalities`) was already in place; pre-T210
 * the UI only exposed displayName / maxOutputTokens / hidden, which
 * meant Android users had no way to override the things iOS lets them
 * tweak (context window, thinking flag, per-modality enablement).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelEntryDetailScreen(
    instanceId: String,
    entryId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val entry = config.modelEntries.find { it.id == entryId && it.providerInstanceId == instanceId }
    val instance = config.instances.find { it.id == instanceId }

    if (entry == null) {
        onBack()
        return
    }

    val baseModel = entry.baseModel
    val overrides = entry.overrides

    var modelId by remember { mutableStateOf(baseModel.id) }
    var displayName by remember { mutableStateOf(overrides.displayName ?: baseModel.displayName) }
    var maxOutputTokensText by remember { mutableStateOf(overrides.maxOutputTokens?.toString() ?: "") }
    var contextWindowText by remember {
        mutableStateOf(
            (overrides.contextWindow ?: entry.model.contextWindow)?.toString() ?: ""
        )
    }
    var thinkingEnabled by remember {
        mutableStateOf(overrides.supportsReasoning ?: baseModel.supportsReasoning ?: false)
    }
    var isHidden by remember { mutableStateOf(entry.isHidden) }
    var showQuickTest by remember { mutableStateOf(false) }

    // Modality state — derive from override-or-base so the toggles reflect
    // what the model actually supports today, then let the user flip from
    // there. A null override means "inherit baseModel" (data-layer
    // contract); on save we only persist a non-null list when it diverges
    // from the inherited set.
    // Defensive normalization: pre-T213 SharedPreferences may hold suffixed strings
    // (`image_input`) saved before the parse-boundary fix, which would silently fail
    // the bare-name `"image" in effectiveInput` checks below.
    val effectiveInput = (overrides.inputModalities ?: baseModel.inputModalities ?: emptyList())
        .map { it.normalizeModalityName() }
    val effectiveOutput = (overrides.outputModalities ?: baseModel.outputModalities ?: emptyList())
        .map { it.normalizeModalityName() }
    var imageInput by remember { mutableStateOf("image" in effectiveInput) }
    var pdfInput by remember { mutableStateOf("pdf" in effectiveInput) }
    var audioInput by remember { mutableStateOf("audio" in effectiveInput) }
    var videoInput by remember { mutableStateOf("video" in effectiveInput) }
    var imageOutput by remember { mutableStateOf("image" in effectiveOutput) }
    var audioOutput by remember { mutableStateOf("audio" in effectiveOutput) }

    SettingsScaffold(
        title = stringResource(R.string.model_entry_model_detail),
        // [T-android-modeldetail-savecancel-ios-parity] iOS modal pattern
        // (ProviderInstanceDetailView toolbar): Cancel is a plain leading
        // text action, the title is centered, and Save is the single
        // emphasized trailing action — the filled pill is the MD3 analog of
        // iOS's semibold Save. No back arrow: Cancel and system back both
        // discard + pop.
        onBack = null,
        centerTitle = true,
        navigation = {
            MinisTextButton(
                onClick = onBack,
                modifier = Modifier.padding(start = 8.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text(stringResource(R.string.common_cancel)) }
        },
        actions = {
            MinisButton(
                onClick = {
                    val baseInputs = baseModel.inputModalities ?: emptyList()
                    val baseOutputs = baseModel.outputModalities ?: emptyList()
                    val newInputs = buildList {
                        if (imageInput) add("image")
                        if (pdfInput) add("pdf")
                        if (audioInput) add("audio")
                        if (videoInput) add("video")
                    }
                    val newOutputs = buildList {
                        if (imageOutput) add("image")
                        if (audioOutput) add("audio")
                    }
                     val newOverrides = ModelOverrides(
                         // [T-android-custom-model-name-trailing-space] Match iOS
                         // `T-custom-model-name-trailing-space`: preserve the user's
                         // spacing verbatim in the display NAME. Only a whitespace-only
                         // entry counts as empty (falls back to baseModel displayName).
                         displayName = displayName.trim().let { trimmed ->
                             if (trimmed.isEmpty()) null
                             else if (trimmed != baseModel.displayName) displayName
                             else null
                         },
                        maxOutputTokens = maxOutputTokensText.trim().toIntOrNull()?.takeIf { it > 0 },
                        contextWindow = contextWindowText.trim().toIntOrNull()?.takeIf { it > 0 },
                        // supportsReasoning: persist only when user diverged from base.
                        supportsReasoning = thinkingEnabled.takeIf { it != (baseModel.supportsReasoning ?: false) },
                        // Modality lists: persist only when user-edited set differs
                        // from baseModel's set; otherwise leave null so the entry
                        // tracks future provider updates to the base modalities.
                        inputModalities = if (newInputs.toSet() != baseInputs.toSet()) newInputs else null,
                        outputModalities = if (newOutputs.toSet() != baseOutputs.toSet()) newOutputs else null,
                    )
                    val updated = if (entry.isCustom) {
                        entry.copy(baseModel = baseModel.copy(id = modelId), overrides = newOverrides, isHidden = isHidden)
                    } else {
                        entry.copy(overrides = newOverrides, isHidden = isHidden)
                    }
                    providerRepository.updateEntry(updated)
                    onBack()
                },
                modifier = Modifier.padding(end = 8.dp),
            ) { Text(stringResource(R.string.common_save)) }
        },
    ) {
        // ── Identity ────────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.add_provider_identity),
            footer = if (entry.isCustom) stringResource(R.string.modeldetail_identity_footer_custom)
                     else stringResource(R.string.modeldetail_identity_footer_builtin),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.add_custom_model_model_id))
                SectionTextField(
                    value = modelId,
                    onValueChange = { if (entry.isCustom) modelId = it },
                    singleLine = true,
                    readOnly = !entry.isCustom,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.model_entry_display_name))
                SectionTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    singleLine = true,
                )
            }
            SettingsValueRow(
                title = stringResource(R.string.model_entry_provider),
                value = instance?.label ?: instanceId,
                showDivider = false,
            )
        }

        // ── Capabilities ────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.modeldetail_section_capabilities),
            footer = stringResource(R.string.modeldetail_capabilities_footer),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.modeldetail_context_window))
                SectionTextField(
                    value = contextWindowText,
                    onValueChange = { contextWindowText = it.filter { c -> c.isDigit() } },
                    placeholder = baseModel.contextWindow?.toString()
                        ?: stringResource(R.string.modeldetail_provider_default),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.model_entry_max_output_tokens))
                SectionTextField(
                    value = maxOutputTokensText,
                    onValueChange = { maxOutputTokensText = it.filter { c -> c.isDigit() } },
                    placeholder = baseModel.maxOutputTokens?.toString()
                        ?: stringResource(R.string.modeldetail_provider_default),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                // Inline warning when the value exceeds the provider-reported
                // limit. The save still goes through so power users can probe
                // a higher cap; we just flag it.
                val enteredTokens = maxOutputTokensText.trim().toIntOrNull()
                val baseLimit = baseModel.maxOutputTokens
                if (enteredTokens != null && baseLimit != null && enteredTokens > baseLimit) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.modeldetail_max_tokens_exceeds_warning, baseLimit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    )
                }
            }
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_thinking),
                checked = thinkingEnabled,
                onCheckedChange = { thinkingEnabled = it },
                showDivider = false,
            )
        }

        // ── Visibility ──────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.model_entry_visibility),
            footer = stringResource(R.string.model_entry_hidden_models_won_t_appear_in_the_model_),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.model_entry_hidden),
                checked = isHidden,
                onCheckedChange = { isHidden = it },
                showDivider = false,
            )
        }

        // ── Input Modality ──────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.modeldetail_section_input_modality),
            footer = stringResource(R.string.modeldetail_input_modality_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_image_input),
                checked = imageInput,
                onCheckedChange = { imageInput = it },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_pdf_input),
                checked = pdfInput,
                onCheckedChange = { pdfInput = it },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_audio_input),
                checked = audioInput,
                onCheckedChange = { audioInput = it },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_video_input),
                checked = videoInput,
                onCheckedChange = { videoInput = it },
                showDivider = false,
            )
        }

        // ── Output Modality ─────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.modeldetail_section_output_modality),
            footer = stringResource(R.string.modeldetail_output_modality_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_image_output),
                checked = imageOutput,
                onCheckedChange = { imageOutput = it },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_audio_output),
                checked = audioOutput,
                onCheckedChange = { audioOutput = it },
                showDivider = false,
            )
        }

        // ── Quick Test ──
        SettingsSection(
            footer = stringResource(R.string.quicktest_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.quicktest_button),
                icon = Icons.Outlined.Bolt,
                showChevron = false,
                showDivider = false,
                onClick = { showQuickTest = true },
            )
        }

        // ── Reset (only for built-in models that have been customized) ──
        if (!entry.isCustom && entry.isUserModified) {
            SettingsSection(
                footer = stringResource(R.string.model_entry_restores_the_provider_reported_display_n),
            ) {
                SettingsRow(
                    title = stringResource(R.string.model_entry_reset_to_default),
                    titleColor = MaterialTheme.colorScheme.error,
                    showChevron = false,
                    showDivider = false,
                    onClick = {
                        providerRepository.updateEntry(entry.copy(overrides = ModelOverrides(), isHidden = false))
                        onBack()
                    },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }

    if (showQuickTest) {
        com.openminis.app.ui.components.QuickTestSheet(
            entry = entry,
            providerRepository = providerRepository,
            onDismiss = { showQuickTest = false },
        )
    }
}
