package com.openminis.app.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.Surface
import androidx.compose.runtime.key
import sh.calvin.reorderable.ReorderableColumn
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.SwipeRowAction
import com.openminis.app.ui.components.SwipeRowActions
import com.openminis.app.logging.AppLogger
import com.openminis.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderListScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onAddProvider: () -> Unit,
    onProviderClick: (String) -> Unit,
    onVoiceServiceClick: (String) -> Unit = {},
) {
    val config by providerRepository.config.collectAsState()
    val instances = config.instances
    val groupedInstances = instances.groupBy { it.providerType }
    val context = LocalContext.current

    var showMenu by remember { mutableStateOf(false) }
    // [T-android-swipe-row-actions] Pending swipe-delete target. Held here
    // rather than per-row so the confirmation survives the row being
    // recomposed/reordered underneath it.
    var instanceToDelete by remember {
        mutableStateOf<com.openminis.app.data.model.ProviderInstance?>(null)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri).orEmpty()
        val name = ProviderImportZip.queryDisplayName(context, uri).orEmpty()
        val looksLikeZip = mime == "application/zip" ||
            mime == "application/x-zip-compressed" ||
            name.lowercase().endsWith(".zip")
        try {
            if (looksLikeZip) {
                val toastFailed = context.getString(R.string.import_zip_extract_failed)
                val toastNoSupported = context.getString(R.string.import_zip_no_supported)
                ProviderImportZip.importFromZip(
                    context = context,
                    uri = uri,
                    onImportSingle = { jsonStr -> providerRepository.importInstanceJSON(jsonStr) },
                    onExtractFailed = { Toast.makeText(context, toastFailed, Toast.LENGTH_SHORT).show() },
                    onNoSupported = { Toast.makeText(context, toastNoSupported, Toast.LENGTH_SHORT).show() },
                    onSummary = { ok, total ->
                        val msg = context.getString(R.string.import_zip_summary, ok, total)
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                )
            } else {
                val jsonStr = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                if (jsonStr != null) {
                    val label = providerRepository.importInstanceJSON(jsonStr)
                    if (label != null) {
                        Toast.makeText(context, "Imported provider \"$label\"", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Invalid provider configuration file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to read file", Toast.LENGTH_SHORT).show()
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.provider_list_providers),
        onBack = onBack,
        actions = {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.provider_list_add_provider))
            }
        },
    ) {
        if (instances.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.VpnKey,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                )
                Text(
                    text = stringResource(R.string.provider_list_no_providers_configured),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.provider_list_add_a_provider_to_get_started),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        } else {
            groupedInstances.forEach { (providerType, typeInstances) ->
                SettingsSection(header = providerType.displayName) {
                    // [T-android-provider-reorder] Long-press a row to drag it
                    // within its provider-type section (mirrors iOS
                    // ProviderInstancesView's .onMove).
                    //
                    // ReorderableColumn — not the LazyColumn variant used by
                    // ModelGroupsScreen — because this screen renders inside
                    // SettingsScaffold's verticalScroll Column, and the lazy
                    // variant needs a LazyListState. Converting the whole screen
                    // to a LazyColumn would churn the empty state and the Voice
                    // Services section for no user-visible gain.
                    //
                    // `localOrder` holds the live order during the drag so the
                    // rows follow the finger; it re-syncs whenever the repository
                    // emits (keyed on the ids) so an external change — an import,
                    // a delete — is not overwritten by a stale local copy.
                    var localOrder by remember(typeInstances.map { it.id }) {
                        mutableStateOf(typeInstances)
                    }
                    ReorderableColumn(
                        list = localOrder,
                        onSettle = { fromIndex, toIndex ->
                            localOrder = localOrder.toMutableList().apply {
                                add(toIndex, removeAt(fromIndex))
                            }
                            // Commit only THIS section's ids: reorderInstances
                            // keeps every unmentioned instance in its existing
                            // relative position, so other provider-type sections
                            // are untouched. This is the Android answer to the
                            // iOS index-mapping bug (246a8a8e) — there is no
                            // section-local→global index arithmetic to get wrong.
                            providerRepository.reorderInstances(localOrder.map { it.id })
                        },
                    ) { index, instance, isDragging ->
                        key(instance.id) {
                            val modelCount = providerRepository.visibleEntries(instance.id).size
                            val apiKey = providerRepository.loadApiKey(instance.id)
                            // Mirrors iOS `isConfigured` on ProviderInstancesView:
                            // for OAuth providers, having a manual bearer token OR
                            // a stored OAuth credential counts as "configured" — not
                            // just the presence of an API key. Without this, OAuth
                            // instances always show the gray dot even after a
                            // successful sign-in or manual token paste.
                            val isConfigured = if (instance.credentialType ==
                                com.openminis.app.data.model.ProviderCredential.oauth) {
                                val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                                mgr?.isAuthenticated() == true
                            } else {
                                // [T-empty-key-compat-endpoints] A keyless
                                // third-party compatible endpoint is
                                // configured-by-definition (mirrors iOS).
                                !apiKey.isNullOrBlank() || instance.allowsEmptyAPIKey
                            }
                            // Lift the dragged row above its neighbours so it
                            // reads as "picked up" (matches ModelGroupsScreen).
                            val elevation by animateDpAsState(
                                targetValue = if (isDragging) 4.dp else 0.dp,
                                label = "provider_drag_elevation",
                            )
                            Surface(
                                shadowElevation = elevation,
                                color = Color.Transparent,
                                modifier = Modifier.longPressDraggableHandle(),
                            ) {
                                // [T-android-swipe-row-actions] Swipe left for
                                // Edit / Delete. Edit reuses the same
                                // onProviderClick the tap already uses, and
                                // Delete routes through the SAME confirmation +
                                // removeInstance() the detail screen's "Delete
                                // Provider" button uses — no second delete path.
                                //
                                // Sits INSIDE longPressDraggableHandle, not
                                // around it: the handle needs to stay attached
                                // to the row the user presses, and the two
                                // gestures separate by axis (see SwipeRowActions).
                                SwipeRowActions(
                                    actions = listOf(
                                        SwipeRowAction(
                                            label = stringResource(R.string.common_edit),
                                            icon = Icons.Filled.Edit,
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                            onClick = { onProviderClick(instance.id) },
                                        ),
                                        SwipeRowAction(
                                            label = stringResource(R.string.common_delete),
                                            icon = Icons.Filled.Delete,
                                            containerColor = MaterialTheme.colorScheme.errorContainer,
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                            onClick = { instanceToDelete = instance },
                                        ),
                                    ),
                                ) {
                                    ProviderInstanceRow(
                                        instance = instance,
                                        modelCount = modelCount,
                                        apiKey = apiKey,
                                        isConfigured = isConfigured,
                                        onClick = { onProviderClick(instance.id) },
                                    )
                                }
                            }
                            if (index < localOrder.size - 1) {
                                val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 38.dp, end = 14.dp)
                                        .height(0.5.dp)
                                        .background(divider),
                                )
                            }
                        }
                    }
                }
            }
        }

        // [T-android-provider-voice] Voice Services: runtime shadow mirror of
        // every enabled instance that owns audio-modality models (mirrors iOS
        // ProviderInstancesView's Voice Services section). Rows are read-only
        // views onto the underlying instance — no stored entity.
        val shadows = remember(config) { providerRepository.shadowVoiceProviders() }
        if (shadows.isNotEmpty()) {
            SettingsSection(
                header = stringResource(R.string.voice_services_section),
                footer = if (providerRepository.hasFoldedShadowDuplicates()) {
                    stringResource(R.string.voice_services_duplicate_hint)
                } else {
                    null
                },
            ) {
                shadows.forEachIndexed { index, shadow ->
                    ShadowVoiceRow(
                        shadow = shadow,
                        onClick = { onVoiceServiceClick(shadow.instanceId) },
                    )
                    if (index < shadows.size - 1) {
                        val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 38.dp, end = 14.dp)
                                .height(0.5.dp)
                                .background(divider),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }

    if (showMenu) {
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showMenu = false
                            onAddProvider()
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.provider_list_add_provider), style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showMenu = false
                            importLauncher.launch(
                                arrayOf(
                                    "application/json",
                                    "application/zip",
                                    "application/x-zip-compressed",
                                ),
                            )
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.provider_list_import_provider), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    // [T-android-swipe-row-actions] Same dialog copy and same repository call as
    // ProviderDetailScreen's "Delete Provider" button, so swipe-delete and
    // detail-delete cannot drift apart.
    instanceToDelete?.let { target ->
        MinisAlertDialog(
            onDismissRequest = { instanceToDelete = null },
            title = stringResource(R.string.provider_detail_delete_provider),
            text = stringResource(
                R.string.provider_detail_delete_provider_confirm,
                target.label,
            ),
            confirmText = stringResource(R.string.common_delete),
            isDestructive = true,
            onConfirm = {
                providerRepository.removeInstance(target.id)
                AppLogger.info(
                    "ProviderList",
                    "Deleted provider instance ${target.id} (${target.label}) via swipe",
                )
                instanceToDelete = null
            },
        )
    }
}

@Composable
private fun ProviderInstanceRow(
    instance: ProviderInstance,
    modelCount: Int,
    apiKey: String?,
    isConfigured: Boolean,
    onClick: () -> Unit,
) {
    val isActive = isConfigured && instance.isEnabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (isActive) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    shape = CircleShape,
                ),
        )

        Spacer(Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = instance.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.provider_list_api_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Text(
                    // [T-empty-key-compat-endpoints] Keyless compatible endpoint:
                    // say so instead of the alarming "No API key".
                    text = if (!apiKey.isNullOrBlank()) maskKey(apiKey)
                        else if (instance.allowsEmptyAPIKey) stringResource(R.string.provider_no_key_required)
                        else "No API key",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (modelCount > 0) {
                Text(
                    text = stringResource(R.string.provider_list_models_count, modelCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }

        if (!instance.isEnabled) {
            Text(
                text = stringResource(R.string.provider_list_disabled),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(50),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun maskKey(key: String): String {
    if (key.length <= 8) return "****"
    return key.take(6) + "..." + key.takeLast(4)
}

/** One shadow Voice Service row: name + ASR/TTS model counts. */
@Composable
private fun ShadowVoiceRow(
    shadow: ProviderRepository.ShadowVoiceProvider,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.GraphicEq,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = shadow.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val parts = buildList {
                if (shadow.inputModels.isNotEmpty()) {
                    add(stringResource(R.string.voice_services_stt_count, shadow.inputModels.size))
                }
                if (shadow.outputModels.isNotEmpty()) {
                    add(stringResource(R.string.voice_services_tts_count, shadow.outputModels.size))
                }
            }
            if (parts.isNotEmpty()) {
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}
