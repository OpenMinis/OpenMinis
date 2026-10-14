package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.plugins.DevSuiteCatalog
import com.openminis.app.plugins.LocalToolPluginStore
import com.openminis.app.plugins.OnlinePluginStore
import com.openminis.app.plugins.PluginCatalog
import com.openminis.app.plugins.PluginRegistry
import com.openminis.app.tools.FetchUrlGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PluginMarketScreen(
    mcpRepository: MCPRepository?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var plugins by remember { mutableStateOf(PluginRegistry.cached(context)) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var keyTarget by remember { mutableStateOf<PluginRegistry.RemotePlugin?>(null) }
    var keyDraft by remember { mutableStateOf("") }
    // [T-local-tool-plugins] Local command-template tools (LocalToolPluginStore).
    var localTools by remember { mutableStateOf(LocalToolPluginStore.load(context)) }
    var editTool by remember { mutableStateOf<LocalToolPluginStore.LocalToolDef?>(null) }
    var editToolIsNew by remember { mutableStateOf(false) }
    val fallbackServers = remember {
        kotlinx.coroutines.flow.MutableStateFlow(emptyList<MCPRepository.MCPServerConfig>())
    }
    val servers by (mcpRepository?.servers ?: fallbackServers).collectAsState()

    LaunchedEffect(Unit) {
        mcpRepository?.reloadFromDisk()
        val result = withContext(Dispatchers.IO) { PluginRegistry.fetch(context) }
        plugins = result.first
        loading = false
        status = if (result.second) {
            context.getString(R.string.plugin_market_live, result.first.size)
        } else {
            context.getString(R.string.plugin_market_cached, result.first.size)
        }
    }

    var installedTick by remember { mutableStateOf(0) }
    installedTick // read so toggles recompose

    SettingsScaffold(
        title = stringResource(R.string.plugin_market_title),
        onBack = null,
    ) {
        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }

        // [T-dev-suite] Declarative dev-environment bundles (taixu-style
        // recipes): apt + setup scripts, downloaded on demand — the payloads
        // no longer ship in the APK. Also consumes the pending
        // minis://devsuite/install/<bundleId> deep link the agent can emit.
        var pendingSuite by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            pendingSuite = com.openminis.app.deeplink.DeepLinkCoordinator.consumePendingDevSuiteInstall()
        }
        DevSuiteSection()
        pendingSuite?.let { id ->
            LaunchedEffect(id) {
                DevSuiteCatalog.bundleById(id)?.let { bundle ->
                    val selected = bundle.components.map { it.id }.toSet()
                    com.openminis.app.plugins.DevSuiteInstaller.assembleBundleAsync(
                        context, bundle, selected,
                    )
                }
                pendingSuite = null
            }
        }

        SettingsSection(
            header = stringResource(R.string.plugin_market_section_online),
            footer = stringResource(R.string.plugin_market_footer_online) + "\n" +
                stringResource(R.string.plugin_market_config_trust_note),
        ) {
            if (plugins.isEmpty()) {
                Text(
                    if (loading) stringResource(R.string.plugin_market_loading)
                    else stringResource(R.string.plugin_market_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            plugins.forEachIndexed { index, plugin ->
                val on = OnlinePluginStore.isInstalled(context, plugin)
                SettingsSwitchRow(
                    title = plugin.name,
                    subtitle = buildString {
                        append(plugin.description.take(120).ifBlank { plugin.category })
                        if (plugin.authType == "api_key") append(" · API key")
                        append(" · ").append(plugin.tools.size).append(" tools")
                    },
                    checked = on,
                    onCheckedChange = { checked ->
                        if (checked) {
                            com.openminis.app.plugins.ConnectorNetworkPolicy.configurationBlockedReason(plugin.baseUrl)?.let {
                                status = it
                                return@SettingsSwitchRow
                            }
                            if (plugin.authType == "api_key" &&
                                OnlinePluginStore.apiKey(context, plugin).isNullOrBlank()
                            ) {
                                keyDraft = ""
                                keyTarget = plugin
                                return@SettingsSwitchRow
                            }
                            OnlinePluginStore.setInstalled(context, plugin, true)
                        } else {
                            OnlinePluginStore.setInstalled(context, plugin, false)
                        }
                        installedTick++
                    },
                    showDivider = index < plugins.lastIndex,
                )
                // [T-plugin-market-private-row-removed] No per-connector toggle:
                // private-network targets are allowed by default (cloud
                // metadata hosts remain blocked by ConnectorNetworkPolicy), so
                // a switch that starts on and rarely changes is noise — it
                // rendered once per catalog entry.
            }
        }

        // [T-local-tool-plugins] Locally-authored command-template tools.
        // The store itself landed earlier; this is the management surface
        // that makes them reachable: definitions join the agent tool list
        // (ChatViewModel.agentTools), execution dispatches in
        // ChatViewModelExecuteToolExt.executeTool via the shell pipeline.
        val localDisabledSuffix = stringResource(R.string.plugin_market_local_disabled)
        SettingsSection(
            header = stringResource(R.string.plugin_market_section_local),
            footer = stringResource(R.string.plugin_market_local_footer),
        ) {
            if (localTools.isEmpty()) {
                Text(
                    stringResource(R.string.plugin_market_local_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            localTools.forEachIndexed { index, tool ->
                SettingsRow(
                    title = tool.name,
                    subtitle = buildString {
                        append(tool.description.ifBlank { tool.command.take(60) })
                        append(" · ").append(tool.params.size).append(" params")
                        if (!tool.enabled) append(" · ").append(localDisabledSuffix)
                    },
                    onClick = {
                        editTool = tool
                        editToolIsNew = false
                    },
                    showDivider = index < localTools.lastIndex,
                )
            }
            SettingsRow(
                title = stringResource(R.string.plugin_market_local_add),
                subtitle = stringResource(R.string.plugin_market_local_add_hint),
                onClick = {
                    editTool = LocalToolPluginStore.LocalToolDef(
                        id = "lt-" + java.util.UUID.randomUUID().toString().take(8),
                        name = "",
                        description = "",
                        params = emptyList(),
                        command = "",
                        enabled = true,
                        createdAtMs = System.currentTimeMillis(),
                    )
                    editToolIsNew = true
                },
                showDivider = false,
            )
        }
    }

    val pending = keyTarget
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { keyTarget = null },
            title = { Text(pending.name) },
            text = {
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    label = { Text(stringResource(R.string.plugin_market_api_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = { keyTarget = null }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            if (keyDraft.isBlank()) return@TextButton
                            OnlinePluginStore.setApiKey(context, pending, keyDraft)
                            OnlinePluginStore.setInstalled(context, pending, true)
                            keyTarget = null
                            installedTick++
                        },
                    ) {
                        Text(stringResource(R.string.plugin_market_install))
                    }
                }
            },
        )
    }

    // [T-local-tool-plugins] Editor for a local command-template tool.
    val editing = editTool
    if (editing != null) {
        LocalToolEditorDialog(
            initial = editing,
            isNew = editToolIsNew,
            onDismiss = { editTool = null },
            onSave = { saved ->
                LocalToolPluginStore.upsert(context, saved)
                localTools = LocalToolPluginStore.load(context)
                editTool = null
            },
            onDelete = (if (editToolIsNew) null else {
                {
                    LocalToolPluginStore.remove(context, editing.id)
                    localTools = LocalToolPluginStore.load(context)
                    editTool = null
                }
            }),
        )
    }
}

@Composable
private fun LocalToolEditorDialog(
    initial: LocalToolPluginStore.LocalToolDef,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (LocalToolPluginStore.LocalToolDef) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var description by remember(initial) { mutableStateOf(initial.description) }
    var command by remember(initial) { mutableStateOf(initial.command) }
    var paramsText by remember(initial) {
        mutableStateOf(
            initial.params.joinToString("\n") { p ->
                listOfNotNull(p.name, p.description.ifBlank { null }, p.defaultValue).joinToString("|")
            },
        )
    }
    var enabled by remember(initial) { mutableStateOf(initial.enabled) }
    val nameValid = name.trim().matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))
    val commandValid = command.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isNew) R.string.plugin_market_local_add else R.string.plugin_market_local_edit,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.plugin_market_local_name)) },
                    isError = !nameValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.plugin_market_local_desc)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.plugin_market_local_command)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = paramsText,
                    onValueChange = { paramsText = it },
                    label = { Text(stringResource(R.string.plugin_market_local_params)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.plugin_market_local_params_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Text(
                        stringResource(R.string.plugin_market_local_enabled),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(
                            stringResource(R.string.plugin_market_local_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        if (!nameValid || !commandValid) return@TextButton
                        val params = paramsText.lines().mapNotNull { raw ->
                            val parts = raw.split('|')
                            val pn = parts.getOrNull(0)?.trim().orEmpty()
                            if (!pn.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) null
                            else LocalToolPluginStore.LocalParam(
                                name = pn,
                                description = parts.getOrNull(1)?.trim().orEmpty(),
                                required = parts.getOrNull(2).isNullOrBlank(),
                                defaultValue = parts.getOrNull(2)?.trim()?.ifBlank { null },
                            )
                        }
                        onSave(
                            initial.copy(
                                name = name.trim(),
                                description = description.trim(),
                                command = command.trim(),
                                params = params,
                                enabled = enabled,
                            ),
                        )
                    },
                    enabled = nameValid && commandValid,
                ) {
                    Text(stringResource(R.string.plugin_market_local_save))
                }
            }
        },
    )
}
