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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
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
import com.openminis.app.data.repository.ProviderRefreshMarks
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.ModelRefreshResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.SwipeRowAction
import com.openminis.app.ui.components.SwipeRowActions
import com.openminis.app.logging.AppLogger
import com.openminis.app.R

/**
 * Result of the file-picked provider import, computed on Dispatchers.IO and
 * turned into a toast back on the main thread — see [ProviderListScreen]'s
 * importLauncher for why the two halves are separated.
 */
private sealed class ProviderImportOutcome {
    data object ExtractFailed : ProviderImportOutcome()
    data object NoSupported : ProviderImportOutcome()
    data object Invalid : ProviderImportOutcome()
    data object ReadFailed : ProviderImportOutcome()
    data class Imported(val label: String) : ProviderImportOutcome()
    data class ZipSummary(val ok: Int, val total: Int) : ProviderImportOutcome()
}

private data class ProviderRowSnapshot(
    val modelCount: Int = 0,
    val apiKey: String? = null,
    val isConfigured: Boolean = false,
    val needsReauth: Boolean = false,
)

@Composable
private fun rememberProviderRowSnapshot(
    instance: ProviderInstance,
    providerRepository: ProviderRepository,
    context: android.content.Context,
): ProviderRowSnapshot {
    val snapshot by produceState(
        initialValue = ProviderRowSnapshot(),
        key1 = instance.id,
        key2 = instance.credentialType,
        key3 = instance.allowsEmptyAPIKey,
    ) {
        value = withContext(Dispatchers.IO) {
            val apiKey = providerRepository.loadApiKey(instance.id)
            val configured = if (instance.credentialType ==
                com.openminis.app.data.model.ProviderCredential.oauth) {
                val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                mgr?.isAuthenticated() == true
            } else {
                !apiKey.isNullOrBlank() || instance.allowsEmptyAPIKey
            }
            ProviderRowSnapshot(
                modelCount = providerRepository.visibleEntries(instance.id).size,
                apiKey = apiKey,
                isConfigured = configured,
                needsReauth = com.openminis.app.auth.OAuthManager.needsReauth(context, instance.id),
            )
        }
    }
    return snapshot
}

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
    var isSyncing by remember { mutableStateOf(false) }
    var syncMarks by remember { mutableStateOf(ProviderRefreshMarks.load(context)) }
    val syncScope = rememberCoroutineScope()

    fun syncSummary(ok: Int, noKey: Int, failed: Int): String =
        if (ok > 0 && noKey == 0 && failed == 0) {
            context.getString(R.string.provider_list_sync_all_success, ok)
        } else {
            context.getString(R.string.provider_list_sync_partial, ok, noKey, failed)
        }

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
        // [T-android-sessionlist-prefs-io] An ActivityResult callback is invoked
        // on the MAIN thread. This body unzipped the picked archive straight
        // into cacheDir (ProviderImportZip.importFromZip is a synchronous
        // stream-and-write, per entry), read the JSON, and ran
        // importInstanceJSON — a non-suspend repository write — all of it on the
        // UI thread, right where a multi-MB provider bundle stalls the frame the
        // picker's resume needs. The work now runs on Dispatchers.IO and only
        // the toasts — which need the main thread's handler — come back to it.
        syncScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val mime = context.contentResolver.getType(uri).orEmpty()
                    val name = ProviderImportZip.queryDisplayName(context, uri).orEmpty()
                    val looksLikeZip = mime == "application/zip" ||
                        mime == "application/x-zip-compressed" ||
                        name.lowercase().endsWith(".zip")
                    if (looksLikeZip) {
                        var extractFailed = false
                        var noSupported = false
                        var ok = 0
                        var total = 0
                        ProviderImportZip.importFromZip(
                            context = context,
                            uri = uri,
                            onImportSingle = { jsonStr ->
                                providerRepository.importInstanceJSON(jsonStr)
                            },
                            onExtractFailed = { extractFailed = true },
                            onNoSupported = { noSupported = true },
                            onSummary = { o, t -> ok = o; total = t },
                        )
                        when {
                            extractFailed -> ProviderImportOutcome.ExtractFailed
                            noSupported -> ProviderImportOutcome.NoSupported
                            else -> ProviderImportOutcome.ZipSummary(ok, total)
                        }
                    } else {
                        val jsonStr = context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.readText()
                        if (jsonStr == null) {
                            ProviderImportOutcome.ReadFailed
                        } else {
                            val label = providerRepository.importInstanceJSON(jsonStr)
                            if (label != null) ProviderImportOutcome.Imported(label)
                            else ProviderImportOutcome.Invalid
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.warning(
                        "ProviderListScreen",
                        "provider import failed: ${e.message}",
                    )
                    ProviderImportOutcome.ReadFailed
                }
            }
            val msg = when (outcome) {
                ProviderImportOutcome.ExtractFailed ->
                    context.getString(R.string.import_zip_extract_failed)
                ProviderImportOutcome.NoSupported ->
                    context.getString(R.string.import_zip_no_supported)
                is ProviderImportOutcome.ZipSummary ->
                    context.getString(R.string.import_zip_summary, outcome.ok, outcome.total)
                ProviderImportOutcome.Invalid ->
                    context.getString(R.string.provider_import_invalid)
                ProviderImportOutcome.ReadFailed ->
                    context.getString(R.string.provider_import_read_failed)
                is ProviderImportOutcome.Imported ->
                    context.getString(R.string.provider_imported, outcome.label)
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.provider_list_providers),
        onBack = null,
        actions = {
            IconButton(
                onClick = {
                    if (isSyncing) return@IconButton
                    isSyncing = true
                    syncScope.launch {
                        val msg = try {
                            val results = withContext(Dispatchers.IO) {
                                providerRepository.refreshAllModelsForce()
                            }
                            ProviderRefreshMarks.recordAll(context, results)
                            syncMarks = ProviderRefreshMarks.load(context)
                            val ok = results.count { it.second == ModelRefreshResult.SUCCESS_API }
                            val noKey = results.count { it.second == ModelRefreshResult.NO_KEY }
                            val failed = results.count {
                                it.second == ModelRefreshResult.FAILURE ||
                                    it.second == ModelRefreshResult.PRESERVED
                            }
                            syncSummary(ok, noKey, failed)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            context.getString(R.string.provider_list_sync_failed_generic)
                        } finally {
                            isSyncing = false
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !isSyncing,
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Filled.Sync,
                        contentDescription = stringResource(R.string.provider_list_sync_all),
                    )
                }
            }
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
            val pinnedInstances = instances.filter { it.pinned }
            if (pinnedInstances.isNotEmpty()) {
                SettingsSection(header = stringResource(R.string.provider_list_favorites)) {
                    pinnedInstances.forEachIndexed { index, instance ->
                        ProviderSwipeableRow(
                            instance = instance,
                            providerRepository = providerRepository,
                            context = context,
                            onProviderClick = onProviderClick,
                            onRequestDelete = { instanceToDelete = instance },
                            syncResult = syncMarks[instance.id],
                        )
                        if (index < pinnedInstances.size - 1) {
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
            groupedInstances.forEach { (providerType, typeInstancesRaw) ->
                val typeInstances = typeInstancesRaw.filter { !it.pinned }
                if (typeInstances.isEmpty()) return@forEach
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
                            val snapshot = rememberProviderRowSnapshot(
                                instance,
                                providerRepository,
                                context,
                            )
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
                                        modelCount = snapshot.modelCount,
                                        apiKey = snapshot.apiKey,
                                        isConfigured = snapshot.isConfigured,
                                        needsReauth = snapshot.needsReauth,
                                        pinned = instance.pinned,
                                        onTogglePinned = {
                                            providerRepository.setInstancePinned(instance.id, !instance.pinned)
                                        },
                                        onClick = { onProviderClick(instance.id) },
                                        syncResult = syncMarks[instance.id],
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
private fun ProviderSwipeableRow(
    instance: ProviderInstance,
    providerRepository: ProviderRepository,
    context: android.content.Context,
    onProviderClick: (String) -> Unit,
    onRequestDelete: () -> Unit,
    syncResult: ModelRefreshResult? = null,
) {
    // Provider rows used to perform model-store, key-store and OAuth reads
    // synchronously during composition. With several instances that made
    // entering Settings block the main thread before the first frame. Keep a
    // small row snapshot and populate it off-main; the row can render its
    // neutral state while the snapshot is being prepared.
    val snapshot = rememberProviderRowSnapshot(instance, providerRepository, context)
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
                onClick = onRequestDelete,
            ),
        ),
    ) {
        ProviderInstanceRow(
            instance = instance,
            modelCount = snapshot.modelCount,
            apiKey = snapshot.apiKey,
            isConfigured = snapshot.isConfigured,
            needsReauth = snapshot.needsReauth,
            pinned = instance.pinned,
            onTogglePinned = {
                providerRepository.setInstancePinned(instance.id, !instance.pinned)
            },
            onClick = { onProviderClick(instance.id) },
            syncResult = syncResult,
        )
    }
}

@Composable
private fun ProviderInstanceRow(
    instance: ProviderInstance,
    modelCount: Int,
    apiKey: String?,
    isConfigured: Boolean,
    needsReauth: Boolean = false,
    pinned: Boolean,
    onTogglePinned: () -> Unit,
    onClick: () -> Unit,
    syncResult: ModelRefreshResult? = null,
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
                    color = when {
                        // [T-oauth-keep-credentials] Rejected bundle awaiting
                        // re-login: red, regardless of enabled state.
                        needsReauth -> Color(0xFFFF3B30)
                        isActive -> Color(0xFF34C759)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                    },
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
                        else stringResource(R.string.provider_list_no_api_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            if (syncResult != null && syncResult != ModelRefreshResult.SUCCESS_API) {
                val mark = when (syncResult) {
                    ModelRefreshResult.NO_KEY -> stringResource(R.string.provider_sync_mark_nokey)
                    ModelRefreshResult.PRESERVED -> stringResource(R.string.provider_sync_mark_unchanged)
                    else -> stringResource(R.string.provider_sync_mark_failed)
                }
                Text(
                    text = mark,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
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

        IconButton(onClick = onTogglePinned) {
            Icon(
                imageVector = if (pinned) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = stringResource(
                    if (pinned) R.string.provider_unset_favorite else R.string.provider_set_favorite,
                ),
                tint = if (pinned) Color(0xFFFFCC00) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
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
