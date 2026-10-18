package com.openminis.app.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import com.openminis.app.ui.chat.ModelBrandIcon
import com.openminis.app.ui.chat.ModelBrandResolver
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ModelRefreshResult
import com.openminis.app.data.repository.ProviderRefreshMarks
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.util.bringIntoViewOnFocus
import com.openminis.app.R
import kotlinx.coroutines.launch
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisSmallButton
import com.openminis.app.ui.components.MinisSmallOutlinedButton
import com.openminis.app.ui.components.MinisSmallTextButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.SectionTextField

private const val TAG = "ProviderDetail"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProviderDetailScreen(
    instanceId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onModelEntryClick: (String) -> Unit,
    onAddCustomModel: () -> Unit,
    onVoiceServiceClick: (String) -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val instance = config.instances.find { it.id == instanceId }
    var showDeleteDialog by remember { mutableStateOf(false) }
    // T143: long-press → confirm delete on a single model entry. Built-in
    // entries (entry.isCustom == false) skip the gesture because the repo
    // re-creates them from ProviderType.builtInModels on next refresh anyway —
    // mirrors DebugProviderMutationMethods note "Built-in entries can't be
    // deleted — set isHidden=true instead".
    var entryToDelete by remember { mutableStateOf<com.openminis.app.data.model.ModelEntry?>(null) }
    // [T-android-model-row-hide-action] Id of the entry whose long-press menu is
    // open. Keyed by id rather than holding the ModelEntry so the menu re-reads
    // the CURRENT entry after a hide toggle — holding a stale copy would leave
    // the item labelled "Hide" right after hiding it.
    var menuEntryId by remember { mutableStateOf<String?>(null) }
    var deleted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (instance == null && !deleted) { onBack(); return }
    if (instance == null) return

    var label by remember { mutableStateOf(instance.label) }
    val labelChanged = label != instance.label

    // [T-android-provider-apikey-save-stale] Hold the stored API key in Compose
    // state, NOT as a plain val that re-reads EncryptedSharedPreferences on every
    // recomposition. saveApiKey() writes with .apply() (async); a recomposition
    // right after Save (isEditingKey=false) would re-read prefs before the write
    // flushed and show the OLD key, making Save look like a no-op. Keeping it in
    // remember + updating it synchronously in onSave reflects the just-saved
    // value immediately, independent of the async flush.
    var storedKey by remember(instanceId) { mutableStateOf(providerRepository.loadApiKey(instanceId) ?: "") }
    var isEditingKey by remember { mutableStateOf(false) }
    var editKeyValue by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }

    var isEnabled by remember { mutableStateOf(instance.isEnabled) }
    var customBaseURL by remember { mutableStateOf(instance.customBaseURL ?: "") }
    var appendV1Suffix by remember { mutableStateOf(instance.appendV1Suffix) }
    // [T-provider-custom-user-agent] Per-provider UA override input. Only the
    // OpenAI-/Anthropic-compat custom-base section surfaces it (see gate below).
    var customUserAgent by remember { mutableStateOf(instance.customUserAgent ?: "") }

    val entries = providerRepository.entriesFor(instanceId)
    var isRefreshing by remember { mutableStateOf(false) }

    val exportContext = androidx.compose.ui.platform.LocalContext.current
    var refreshNote by remember { mutableStateOf(ProviderRefreshMarks.get(exportContext, instance.id)) }

    SettingsScaffold(
        title = instance.label,
        onBack = onBack,
        actions = {
            IconButton(onClick = {
                AppLogger.info(TAG, "Export instance ${instance.id} (${instance.label})")
                exportProviderInstance(exportContext, providerRepository, instance)
            }) {
                Icon(Icons.Default.IosShare, contentDescription = stringResource(R.string.provider_detail_export))
            }
        },
    ) {
        // ─── Label ──────────────────────────────────────────────────
        SettingsSection(header = stringResource(R.string.provider_detail_label)) {
            SettingsCardBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTextField(
                        value = label,
                        onValueChange = { label = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        fieldModifier = Modifier.bringIntoViewOnFocus(),
                    )
                    if (labelChanged) {
                        Spacer(modifier = Modifier.width(8.dp))
                        MinisSmallButton(onClick = {
                            providerRepository.updateInstance(instance.copy(label = label))
                            AppLogger.info(TAG, "Updated label for ${instance.id}: '$label'")
                        }) {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                }
            }
        }

        // ─── Credential / API Key ───────────────────────────────────
        val isOAuthProvider =
            instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth
        SettingsSection(
            header = if (isOAuthProvider) stringResource(R.string.add_provider_credential) else stringResource(R.string.provider_list_api_key),
            footer = if (isOAuthProvider) {
                "OAuth tokens are stored securely in encrypted storage."
            } else {
                null
            },
        ) {
            SettingsCardBlock {
                if (isOAuthProvider) {
                    OAuthCredentialBlock(
                        instance = instance,
                        storedKey = storedKey,
                        providerRepository = providerRepository,
                    )
                } else {
                    ApiKeyCredentialBlock(
                        storedKey = storedKey,
                        keyVisible = keyVisible,
                        onToggleVisibility = { keyVisible = !keyVisible },
                        isEditing = isEditingKey,
                        editValue = editKeyValue,
                        onEditValueChange = { editKeyValue = it },
                        onBeginEdit = {
                            isEditingKey = true
                            editKeyValue = storedKey
                        },
                        onCancelEdit = {
                            isEditingKey = false
                            editKeyValue = ""
                            keyVisible = false
                        },
                        onSave = {
                            // [T-key-clear-entry] A blank save now DELETES the key
                            // (the button is enabled whenever a stored key exists).
                            // This is the same UX dead-end aaafd51 fixed for key
                            // pools: save was disabled on blank text, so a
                            // misconfigured non-OAuth credential could never be
                            // cleared, only re-typed.
                            if (editKeyValue.isBlank()) {
                                providerRepository.deleteApiKey(instanceId)
                                storedKey = ""
                                AppLogger.info(TAG, "Cleared API key for ${instance.id}")
                            } else {
                                providerRepository.saveApiKey(instanceId, editKeyValue)
                                // [T-android-provider-apikey-save-stale] Reflect the
                                // just-saved value in UI state immediately rather than
                                // re-reading the async-written prefs on recomposition.
                                storedKey = editKeyValue
                                AppLogger.info(TAG, "Saved API key for ${instance.id}")
                            }
                            isEditingKey = false
                            editKeyValue = ""
                            keyVisible = false
                        },
                    )
                }
            }
        }

        // Manual Bearer Token (OAuth providers only — proxy override)
        if (isOAuthProvider) {
            SettingsSection(
                header = stringResource(R.string.provider_detail_manual_bearer_token),
                footer = stringResource(R.string.provider_detail_use_a_static_bearer_token_instead_of_the) +
                    "Useful with custom proxy endpoints.",
            ) {
                SettingsCardBlock {
                    ManualBearerTokenSection(
                        instance = instance,
                        context = androidx.compose.ui.platform.LocalContext.current,
                    )
                }
            }
        }

        // ─── Custom Base URL ────────────────────────────────────────
        if (instance.providerType != ProviderType.openRouter) {
            SettingsSection(header = stringResource(R.string.provider_detail_custom_api_base)) {
                // URL input row — tighter vertical padding to match T226's
                // SectionTextField height shrink (~-20%).
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SectionTextField(
                        value = customBaseURL,
                        onValueChange = { customBaseURL = it },
                        singleLine = true,
                        placeholder = stringResource(R.string.provider_detail_https_api_example_placeholder),
                        fieldModifier = Modifier.bringIntoViewOnFocus(),
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                // Auto-append /v1 switch row — inline Row with heightIn=44dp
                // so the row matches the URL-input row above and the save
                // action below. SettingsSwitchRow's intrinsic 12dp padding
                // around the 32dp Switch over-reported the height (~60dp),
                // breaking the three-row rhythm in the Custom API base card
                // (T231).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.provider_detail_auto_append_v1),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    SettingsSwitch(
                        checked = appendV1Suffix,
                        onCheckedChange = { appendV1Suffix = it },
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                // [T-provider-custom-user-agent] Custom User-Agent input —
                // only for OpenAI-/Anthropic-compat (relay) protocols. Some
                // gateways reject Minis' default UA and only allow official
                // clients (e.g. Claude Code). Blank → default UA. Official
                // direct OpenAI/Anthropic instances still see the field because
                // protocol == openAI/anthropic; it's harmless there (the value
                // is only injected when this instance has a custom base, and on
                // the official path customUserAgent stays at its default null
                // unless the user typed one — applied verbatim if they do).
                val showUserAgentField = instance.providerType == ProviderType.openAI ||
                    instance.providerType == ProviderType.anthropic
                if (showUserAgentField) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.provider_detail_custom_user_agent),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        SectionTextField(
                            value = customUserAgent,
                            onValueChange = { customUserAgent = it },
                            singleLine = true,
                            placeholder = stringResource(R.string.provider_detail_custom_user_agent_placeholder),
                            fieldModifier = Modifier.bringIntoViewOnFocus(),
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
                // Save action — TextButton presentation so it reads as a list
                // row rather than a floating filled button inside the card.
                MinisSmallTextButton(
                    onClick = {
                        providerRepository.updateInstance(
                            instance.copy(
                                customBaseURL = customBaseURL.ifBlank { null },
                                appendV1Suffix = appendV1Suffix,
                                // [T-provider-custom-user-agent] Blank → null →
                                // default UA. Only the gated providers can edit
                                // this; for others customUserAgent equals the
                                // unchanged instance value, so the copy is inert.
                                customUserAgent = customUserAgent.ifBlank { null },
                            )
                        )
                        AppLogger.info(
                            TAG,
                            "Saved base URL for ${instance.id}: " +
                                "url='${customBaseURL.ifBlank { "<default>" }}', appendV1=$appendV1Suffix, " +
                                "ua='${customUserAgent.ifBlank { "<default>" }}'",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.provider_detail_save_url_settings))
                }
            }
        }

        // ─── API Format (OpenAI API-key only) ───────────────────────
        if (instance.providerType == ProviderType.openAI &&
            instance.credentialType != com.openminis.app.data.model.ProviderCredential.oauth
        ) {
            SettingsSection(
                header = stringResource(R.string.provider_detail_api_format),
                footer = if (instance.useResponsesAPI) {
                    stringResource(R.string.provider_detail_api_format_footer_responses)
                } else {
                    stringResource(R.string.provider_detail_api_format_footer_chat)
                },
            ) {
                SettingsCardBlock {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !instance.useResponsesAPI,
                            onClick = {
                                if (instance.useResponsesAPI) {
                                    providerRepository.updateInstance(instance.copy(useResponsesAPI = false))
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text(stringResource(R.string.provider_detail_chat_completions)) }
                        SegmentedButton(
                            selected = instance.useResponsesAPI,
                            onClick = {
                                if (!instance.useResponsesAPI) {
                                    providerRepository.updateInstance(instance.copy(useResponsesAPI = true))
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text(stringResource(R.string.provider_detail_responses_api)) }
                    }
                }
            }
        }

        // ─── Azure OpenAI ───────────────────────────────────────────
        // [T-android-azure-openai] Orthogonal to the API Format picker above —
        // Azure changes only the auth header (api-key:) and URL style (the user
        // pastes the full Azure endpoint, incl. ?api-version, into Custom API
        // Base). OpenAI API-key instances only. Ports iOS azureModeSection.
        if (instance.supportsAzureMode) {
            SettingsSection(
                header = stringResource(R.string.provider_detail_azure_openai),
                footer = stringResource(R.string.provider_detail_azure_openai_footer),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.provider_detail_azure_openai),
                    checked = instance.azureMode,
                    onCheckedChange = { on ->
                        providerRepository.updateInstance(instance.copy(azureMode = on))
                        AppLogger.info(TAG, "Set azureMode=$on for ${instance.id}")
                    },
                    showDivider = false,
                )
            }
        }


        // ─── Image Generation Endpoint ──────────────────────────────
        // [T-android-image-endpoint-mode] OpenAI-compatible providers only.
        // Picks how minis-model-use routes image-output models:
        // auto-probe / forced Images API / forced Chat Completions. Mirrors
        // iOS ProviderInstanceDetailView.imageEndpointSection.
        if (instance.supportsImageEndpointSetting) {
            val mode = instance.imageEndpointMode
            SettingsSection(
                header = stringResource(R.string.provider_image_gen_header),
                footer = when (mode) {
                    com.openminis.app.data.model.ImageEndpointMode.auto ->
                        if (instance.imageEndpointResolved != null) {
                            val resolved = if (instance.imageEndpointResolved ==
                                com.openminis.app.data.model.ImageEndpointMode.imagesGenerations
                            ) "/v1/images/generations" else "/v1/chat/completions"
                            "Auto: tries /v1/images/generations first, falls back to /v1/chat/completions. Last successful endpoint: $resolved."
                        } else {
                            "Auto: tries /v1/images/generations first, falls back to /v1/chat/completions. Caches the working endpoint after the first call."
                        }
                    com.openminis.app.data.model.ImageEndpointMode.imagesGenerations ->
                        "Always use /v1/images/generations."
                    com.openminis.app.data.model.ImageEndpointMode.chatCompletions ->
                        "Always use /v1/chat/completions (multimodal output)."
                },
            ) {
                SettingsCardBlock {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = mode == com.openminis.app.data.model.ImageEndpointMode.auto,
                            onClick = {
                                if (mode != com.openminis.app.data.model.ImageEndpointMode.auto) {
                                    providerRepository.updateInstance(
                                        instance.copy(
                                            imageEndpointMode = com.openminis.app.data.model.ImageEndpointMode.auto,
                                        ),
                                    )
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        ) { Text(stringResource(R.string.provider_image_auto)) }
                        SegmentedButton(
                            selected = mode == com.openminis.app.data.model.ImageEndpointMode.imagesGenerations,
                            onClick = {
                                if (mode != com.openminis.app.data.model.ImageEndpointMode.imagesGenerations) {
                                    providerRepository.updateInstance(
                                        instance.copy(
                                            imageEndpointMode = com.openminis.app.data.model.ImageEndpointMode.imagesGenerations,
                                            // User-forced mode supersedes any cached probe result.
                                            imageEndpointResolved = null,
                                        ),
                                    )
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        ) { Text(stringResource(R.string.provider_image_images_api)) }
                        SegmentedButton(
                            selected = mode == com.openminis.app.data.model.ImageEndpointMode.chatCompletions,
                            onClick = {
                                if (mode != com.openminis.app.data.model.ImageEndpointMode.chatCompletions) {
                                    providerRepository.updateInstance(
                                        instance.copy(
                                            imageEndpointMode = com.openminis.app.data.model.ImageEndpointMode.chatCompletions,
                                            imageEndpointResolved = null,
                                        ),
                                    )
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        ) { Text(stringResource(R.string.provider_image_chat)) }
                    }
                }
            }
        }

        // ─── Status ─────────────────────────────────────────────────
        SettingsSection(header = stringResource(R.string.provider_detail_status)) {
            SettingsSwitchRow(
                title = stringResource(R.string.provider_detail_enabled),
                checked = isEnabled,
                onCheckedChange = {
                    isEnabled = it
                    providerRepository.updateInstance(instance.copy(isEnabled = it))
                    AppLogger.info(TAG, "Set enabled=$it for ${instance.id}")
                },
                showDivider = false,
            )
        }

        // ─── Voice Service (dual visibility) ────────────────────────
        // [T-android-provider-voice] When this instance owns audio-modality
        // models, link to its shadow Voice Service detail — the only path back
        // to the "Show in Voice Services" toggle once the shadow row is hidden
        // (mirrors iOS ProviderInstanceDetailView's Voice Service link with a
        // "Hidden" badge).
        if (providerRepository.hasVoiceModels(instance.id)) {
            SettingsSection(header = null) {
                SettingsRow(
                    title = stringResource(R.string.voice_services_section),
                    onClick = { onVoiceServiceClick(instance.id) },
                    showDivider = false,
                    trailing = if (providerRepository.isVoiceShadowDisabled(instance.id)) {
                        {
                            Text(
                                text = stringResource(R.string.voice_service_hidden_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        // ─── Thinking Rules [T-android-thinking-rules-phase2 §3] ─────
        ThinkingRulesSection(instance = instance, providerRepository = providerRepository)

        // ─── Models ─────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.provider_detail_models_count_header, entries.size),
        ) {
            // Refresh action sits as the first row, mirroring the iOS
            // tap-to-refresh affordance in the section header area.
            SettingsRow(
                title = stringResource(if (isRefreshing) R.string.provider_detail_refreshing else R.string.provider_detail_refresh_models),
                subtitle = refreshNote?.let { note ->
                    stringResource(when (note) {
                        ModelRefreshResult.NO_KEY -> R.string.provider_sync_mark_nokey
                        ModelRefreshResult.PRESERVED -> R.string.provider_sync_mark_unchanged
                        ModelRefreshResult.FAILURE -> R.string.provider_sync_mark_failed
                        ModelRefreshResult.SUCCESS_API -> R.string.provider_sync_mark_unchanged
                    })
                },
                onClick = if (isRefreshing) {
                    null
                } else {
                    {
                        isRefreshing = true
                        scope.launch {
                            try {
                                val result = providerRepository.refreshModels(instance, forceRefresh = true, clearFirst = true)
                                ProviderRefreshMarks.record(exportContext, instance.id, result)
                                refreshNote = ProviderRefreshMarks.get(exportContext, instance.id)
                                AppLogger.info(TAG, "Refreshed models for ${instance.id}: $result")
                            } finally {
                                isRefreshing = false
                            }
                        }
                    }
                },
                showChevron = false,
                trailing = {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.provider_detail_refresh_models),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                showDivider = entries.isNotEmpty() || true,
            )

            // [T-android-hidden-models-group] Only visible models stay inline;
            // hidden ones move to their own collapsible section below. Rows are
            // rendered by ProviderModelRows.kt (extracted — this file is at its
            // architecture-policy ceiling).
            val visibleEntries = entries.filter { !it.isHidden }
            visibleEntries.forEachIndexed { idx, entry ->
                ProviderModelEntryRow(
                    entry = entry,
                    showDivider = idx != visibleEntries.lastIndex,
                    onOpen = { onModelEntryClick(entry.id) },
                    // Write straight through — no Save step. This is the whole
                    // point of the affordance, and it mirrors iOS, whose eye
                    // button calls store.updateEntry immediately.
                    onToggleHidden = {
                        providerRepository.updateEntry(entry.copy(isHidden = !entry.isHidden))
                        AppLogger.info(
                            TAG,
                            "Model entry ${entry.id} (${entry.model.displayName}) " +
                                "isHidden ${entry.isHidden} -> ${!entry.isHidden}",
                        )
                    },
                    onDelete = { entryToDelete = entry },
                )
            }
        }

        ProviderHiddenModelsSection(
            hidden = entries.filter { it.isHidden },
            onOpen = { onModelEntryClick(it.id) },
            onToggleHidden = { e ->
                providerRepository.updateEntry(e.copy(isHidden = !e.isHidden))
                AppLogger.info(TAG, "Model entry ${e.id} (${e.model.displayName}) isHidden ${e.isHidden} -> ${!e.isHidden}")
            },
            onDelete = { entryToDelete = it },
        )

        // Pure-action rows render as standalone buttons — no Section/Card wrap.
        // Match iOS visual: button sits on the page background with horizontal
        // gutter padding only. The 20dp top padding mirrors SettingsSection's
        // top spacing so the rhythm against the cards above stays consistent.
        MinisOutlinedButton(
            onClick = onAddCustomModel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp),
        ) {
            Text(stringResource(R.string.provider_detail_add_custom_model))
        }

        // [T-android-delete-provider-button-height] The "Delete provider" button
        // uses the same default 48dp MinisButtonHeight as "Add custom model"
        // above it for visual consistency (no explicit .height override). The
        // destructive intent is conveyed by the error container color, not by a
        // taller button.
        MinisButton(
            onClick = { showDeleteDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(stringResource(R.string.provider_detail_delete_provider))
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showDeleteDialog) {
        MinisAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = stringResource(R.string.provider_detail_delete_provider),
            text = stringResource(R.string.provider_detail_delete_provider_confirm, instance.label),
            confirmText = stringResource(R.string.common_delete),
            isDestructive = true,
            onConfirm = {
                deleted = true
                providerRepository.removeInstance(instanceId)
                AppLogger.info(TAG, "Deleted provider instance ${instance.id} (${instance.label})")
                showDeleteDialog = false
                onBack()
            },
        )
    }

    // T143: per-entry delete confirmation. removeEntry also strips the entry
    // from any modelGroups it belongs to (see ProviderRepository L304-306),
    // so the StateFlow update propagates the row removal everywhere.
    entryToDelete?.let { e ->
        MinisAlertDialog(
            onDismissRequest = { entryToDelete = null },
            title = stringResource(R.string.provider_detail_delete_model),
            text = stringResource(R.string.provider_detail_delete_model_confirm, e.model.displayName),
            confirmText = stringResource(R.string.common_delete),
            isDestructive = true,
            onConfirm = {
                providerRepository.removeEntry(e.id)
                AppLogger.info(TAG, "Deleted model entry ${e.id} (${e.model.displayName})")
                entryToDelete = null
            },
        )
    }
}

// ─── Credential blocks ─────────────────────────────────────────────────────────

@Composable
private fun OAuthCredentialBlock(
    instance: com.openminis.app.data.model.ProviderInstance,
    storedKey: String,
    providerRepository: ProviderRepository,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // [T-android-openai-oauth-signout-signin-stuck] Drive the Sign In / Sign Out
    // toggle off LOCAL Compose state instead of the `storedKey` parameter, which
    // is read once per parent composition (a plain val) and never updates after
    // a sign-out/sign-in. Without this the screen kept showing "Sign Out" after a
    // logout (UI never refreshed) AND the Sign In branch was never reached
    // because its gate was the same stale `storedKey.isNotEmpty()` — so a failed
    // sign-out left Sign In dead too. `displayedKey` mirrors the live credential
    // so the masked token / connected dot refresh together. Mirrors iOS
    // ProviderInstanceDetailView's `oauthRefreshTrigger.toggle()`.
    var displayedKey by remember(instance.id) { mutableStateOf(storedKey) }
    // [T-oauth-keep-credentials] Rejected refresh → red dot + hint instead of
    // the green one; cleared automatically when a fresh bundle lands.
    var needsReauth by remember(instance.id) {
        mutableStateOf(com.openminis.app.auth.OAuthManager.needsReauth(context, instance.id))
    }
    var isAuthenticating by remember(instance.id) { mutableStateOf(false) }
    // [T-kimi-oauth] Device-code dialog state for Kimi re-auth (see
    // AddProviderScreen.OAuthConfigSection for the rationale).
    var kimiDeviceAuth by remember(instance.id) {
        mutableStateOf<com.openminis.app.auth.KimiDeviceFlow.DeviceAuthorization?>(null)
    }
    var kimiLoginJob by remember(instance.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    kimiDeviceAuth?.let { auth ->
        KimiDeviceLoginDialog(
            userCode = auth.userCode,
            verificationUrl = auth.openUrl,
            onCancel = {
                kimiLoginJob?.cancel()
                kimiDeviceAuth = null
            },
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "OAuth",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (displayedKey.isNotEmpty()) {
            Text(
                maskedKey(displayedKey),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(8.dp)
                    .background(
                        if (needsReauth) Color(0xFFFF3B30) else Color(0xFF34C759),
                        CircleShape,
                    ),
            )
            if (needsReauth) {
                Text(
                    "Login expired — sign in again",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "Not connected",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    if (displayedKey.isNotEmpty()) {
        MinisSmallButton(
            onClick = {
                // [T-android-openai-oauth-signout-signin-stuck] A real sign-out
                // must clear BOTH credential stores: (1) the persisted OAuth
                // token blob (access + refresh_token + expire_at, plus any manual
                // bearer / account_id) via OAuthManager.logout(), and (2) the
                // apiKey mirror that ProviderFactory.loadApiKey() reads. Before
                // this fix only deleteApiKey() ran, so the refresh_token survived
                // and validAccessToken() silently re-minted an access token —
                // the user stayed effectively logged in. Then flip local state so
                // the UI swaps to the Sign In button immediately.
                com.openminis.app.auth.OAuthManager.forInstance(context, instance)?.logout()
                providerRepository.deleteApiKey(instance.id)
                displayedKey = ""
                AppLogger.info(TAG, "OAuth signed out for ${instance.id} (tokens + apiKey cleared)")
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(stringResource(R.string.provider_detail_sign_out))
        }
    } else {
        MinisSmallButton(
            onClick = {
                if (isAuthenticating) return@MinisSmallButton
                isAuthenticating = true
                kimiLoginJob = scope.launch {
                    try {
                        val token = when (instance.providerType) {
                            ProviderType.kimiCode ->
                                com.openminis.app.auth.KimiOAuthManager.login(
                                    context, instance.id, providerRepository,
                                    onDeviceCode = { auth -> kimiDeviceAuth = auth },
                                ).also { kimiDeviceAuth = null }
                            ProviderType.anthropic ->
                                com.openminis.app.auth.ClaudeOAuthManager.login(
                                    context, instance.id, providerRepository,
                                )
                            ProviderType.openAI ->
                                com.openminis.app.auth.OpenAIOAuthManager.login(
                                    context, instance.id, providerRepository,
                                )
                            ProviderType.openRouter ->
                                com.openminis.app.auth.OpenRouterOAuthManager.login(
                                    context, instance.id, providerRepository,
                                )
                            ProviderType.xAI ->
                                com.openminis.app.auth.XAIOAuthManager.login(
                                    context, instance.id, providerRepository,
                                )
                            else -> null
                        }
                        // login() persists the token via providerRepository.saveApiKey;
                        // reflect it locally so the UI flips to the connected state
                        // without needing the parent to recompose.
                        if (token != null) {
                            displayedKey = providerRepository.loadApiKey(instance.id) ?: token
                            // [T-oauth-keep-credentials] Fresh bundle landed —
                            // clear the re-login flag so the dot turns green.
                            needsReauth = false
                            AppLogger.info(TAG, "OAuth signed in for ${instance.id}")
                        }
                    } catch (e: Exception) {
                        AppLogger.warning(TAG, "OAuth sign-in failed for ${instance.id}: ${e.message}")
                    } finally {
                        isAuthenticating = false
                        kimiDeviceAuth = null
                    }
                }
            },
        ) {
            Text(stringResource(R.string.provider_detail_sign_in))
        }
    }
}

@Composable
private fun ApiKeyCredentialBlock(
    storedKey: String,
    keyVisible: Boolean,
    onToggleVisibility: () -> Unit,
    isEditing: Boolean,
    editValue: String,
    onEditValueChange: (String) -> Unit,
    onBeginEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSave: () -> Unit,
) {
    if (isEditing) {
        SectionTextField(
            value = editValue,
            onValueChange = onEditValueChange,
            singleLine = true,
            visualTransformation = if (keyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = onToggleVisibility) {
                    Icon(
                        if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (keyVisible) "Hide" else "Show",
                    )
                }
            },
            fieldModifier = Modifier.bringIntoViewOnFocus(),
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            // [T-android-settings-ui-md3] #4 + #12 Cancel is the SECONDARY action:
            // a neutral outlined pill (onSurfaceVariant content/border), forming
            // the standard MD3 outlined-vs-filled pair with the filled Save below.
            // Previously a primary-teal text button — indistinguishable from Save.
            MinisSmallOutlinedButton(
                onClick = onCancelEdit,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text(stringResource(R.string.common_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            // [T-key-clear-entry] Enabled when the edit text is non-blank
            // (save) OR a stored key exists (clear with blank text).
            MinisSmallButton(onClick = onSave, enabled = editValue.isNotBlank() || storedKey.isNotBlank()) {
                Text(
                    stringResource(
                        if (editValue.isBlank() && storedKey.isNotBlank()) R.string.common_delete
                        else R.string.provider_detail_save_key
                    )
                )
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (keyVisible) storedKey else maskedKey(storedKey),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (keyVisible) "Hide" else "Show",
                )
            }
            MinisSmallTextButton(onClick = onBeginEdit) {
                Text(stringResource(R.string.common_edit))
            }
        }
    }
}

private fun maskedKey(key: String): String {
    if (key.length <= 10) return key.replace(Regex("."), "•")
    return key.take(6) + "..." + key.takeLast(4)
}

/**
 * Export the provider instance as JSON and hand it to the system share sheet
 * via FileProvider. Mirrors iOS `ProviderShareSheet` — writes a <label>.json
 * file to app-scoped cache and emits ACTION_SEND with a content:// URI.
 */
private fun exportProviderInstance(
    context: android.content.Context,
    providerRepository: ProviderRepository,
    instance: com.openminis.app.data.model.ProviderInstance,
) {
    val json = providerRepository.exportInstanceJSON(instance.id) ?: return
    val safeLabel = instance.label.ifBlank { "provider" }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
    val file = java.io.File(dir, "$safeLabel.json")
    runCatching { file.writeText(json) }.onFailure { return }

    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    } catch (_: Throwable) {
        return
    }

    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "$safeLabel.json")
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = android.content.Intent.createChooser(sendIntent, "Export provider").apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

/**
 * Manual Bearer Token section for OAuth providers. Mirrors iOS
 * `manualOAuthTokenSection` — when set, the static token bypasses the OAuth
 * refresh flow entirely and is sent verbatim as `Authorization: Bearer …`.
 */
@Composable
private fun ManualBearerTokenSection(
    instance: com.openminis.app.data.model.ProviderInstance,
    context: android.content.Context,
) {
    val manager = remember(instance.id) {
        com.openminis.app.auth.OAuthManager.forInstance(context, instance)
    }
    var reloadTick by remember(instance.id) { mutableStateOf(0) }
    val stored = remember(instance.id, reloadTick) { manager?.loadManualBearerToken() }
    val hasToken = !stored.isNullOrEmpty()

    var isEditing by remember(instance.id) { mutableStateOf(false) }
    var draft by remember(instance.id) { mutableStateOf("") }

    if (isEditing) {
        SectionTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = false,
            placeholder = stringResource(R.string.provider_detail_paste_bearer_token),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            fieldModifier = Modifier.bringIntoViewOnFocus(),
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            // [T-android-settings-ui-md3] #4 + #12 neutral outlined Cancel (see
            // the API-key edit pair above) paired with the filled Save.
            MinisSmallOutlinedButton(
                onClick = {
                    isEditing = false
                    draft = ""
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text(stringResource(R.string.common_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            MinisSmallButton(
                onClick = {
                    val cleaned = draft.replace(Regex("\\s+"), "")
                    if (cleaned.isNotEmpty() && manager != null) {
                        manager.saveManualBearerToken(cleaned)
                        AppLogger.info(TAG, "Manual bearer token set for ${instance.id}")
                        draft = ""
                        isEditing = false
                        reloadTick++
                    }
                },
                enabled = draft.isNotBlank(),
            ) {
                Text(stringResource(R.string.provider_detail_save_token))
            }
        }
        return
    }

    if (hasToken) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Configured",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(8.dp)
                    .background(Color(0xFF34C759), CircleShape),
            )
        }
        Row(modifier = Modifier.padding(top = 8.dp)) {
            MinisSmallOutlinedButton(onClick = {
                draft = ""
                isEditing = true
            }) {
                Text(stringResource(R.string.common_change))
            }
            Spacer(modifier = Modifier.width(8.dp))
            MinisSmallOutlinedButton(
                onClick = {
                    manager?.deleteManualBearerToken()
                    AppLogger.info(TAG, "Manual bearer token removed for ${instance.id}")
                    reloadTick++
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.common_remove))
            }
        }
    } else {
        MinisSmallOutlinedButton(onClick = {
            draft = ""
            isEditing = true
        }) {
            Text(stringResource(R.string.provider_detail_set_manual_bearer_token))
        }
    }
}
