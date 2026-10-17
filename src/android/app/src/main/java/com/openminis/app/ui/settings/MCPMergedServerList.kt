package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.MCPRepository

/**
 * [T-android-mcp-merged-server-list] ONE list for presets and saved servers.
 * They used to be two sections, so an enabled preset showed up twice — a switch
 * under "Presets" and, for the same config, a row under "Servers". Merged, each
 * preset is a single switch that adds the server it represents (or flips an
 * existing entry), and hand-added servers follow below.
 *
 * Presets start OFF: nothing is written to servers.json until the user flips a
 * switch, so a fresh install never ships enabled integrations. Turning a switch
 * off disables the existing entry instead of deleting it, so headers/args the
 * user customised survive a toggle.
 *
 * Extracted from MCPIntegrationsScreen to keep that file under its architecture
 * ceiling; behaviour is unchanged.
 */
@Composable
internal fun MCPMergedServerList(
    servers: List<MCPRepository.MCPServerConfig>,
    mcpRepository: MCPRepository,
    onEditServer: (MCPRepository.MCPServerConfig) -> Unit,
    onOpenTools: (MCPRepository.MCPServerConfig) -> Unit,
) {
    val presetServers = remember(servers) {
        com.openminis.app.plugins.PluginCatalog.MCP_PRESETS.map { preset ->
            preset to servers.firstOrNull { preset.matches(it) }
        }
    }
    val presetServerIds = remember(presetServers) {
        presetServers.mapNotNull { it.second?.id }.toSet()
    }
    val customServers = servers.filterNot { it.id in presetServerIds }

    SettingsSection(
        header = stringResource(R.string.mcp_section_servers),
        footer = stringResource(R.string.mcp_section_footer),
    ) {
        presetServers.forEachIndexed { index, (preset, existing) ->
            val showDivider = index < presetServers.lastIndex || customServers.isNotEmpty()
            val toggle: (Boolean) -> Unit = { checked ->
                if (checked) {
                    if (existing == null) {
                        // Same URL guard the standalone preset list used: a
                        // blocked host never reaches the repo.
                        val blocked = preset.url?.let { url ->
                            com.openminis.app.tools.FetchUrlGuard.blockedReason(url) != null
                        } == true
                        if (!blocked) mcpRepository.add(preset.toServer())
                    } else {
                        mcpRepository.setEnabled(existing.id, true)
                    }
                } else if (existing != null) {
                    mcpRepository.setEnabled(existing.id, false)
                }
            }
            if (existing == null) {
                SettingsSwitchRow(
                    // Localized preset name (Chinese in zh) rather than the
                    // technical id written to servers.json — the id stays
                    // visible in the edit sheet.
                    title = preset.name,
                    subtitle = preset.description,
                    checked = false,
                    onCheckedChange = toggle,
                    showDivider = showDivider,
                )
            } else {
                // Once the preset exists, keep the same entry points the old
                // Servers list gave it: tap to edit/delete, and the wrench for
                // per-tool toggles. Merging the lists must not strand those
                // actions behind a switch.
                SettingsRow(
                    title = preset.name,
                    subtitle = preset.description,
                    showDivider = showDivider,
                    onClick = { onEditServer(existing) },
                    trailing = { ServerTrailing(existing, toggle, { onOpenTools(existing) }) },
                )
            }
        }
        customServers.forEachIndexed { index, server ->
            SettingsRow(
                title = server.id,
                subtitle = server.transportSummary.takeIf { it.isNotBlank() },
                showDivider = index < customServers.size - 1,
                // [T-mcp-fix-delete-entry] Plain tap opens the EDIT form
                // (delete lives there). A previous change rewired this to the
                // tools screen, which left the edit sheet — and with it the
                // delete button — with zero entry points: editServer was never
                // set anywhere.
                onClick = { onEditServer(server) },
                trailing = {
                    ServerTrailing(
                        server = server,
                        onEnabledChange = { mcpRepository.setEnabled(server.id, it) },
                        onOpenTools = { onOpenTools(server) },
                    )
                },
            )
        }
    }
}

@Composable
private fun ServerTrailing(
    server: MCPRepository.MCPServerConfig,
    onEnabledChange: (Boolean) -> Unit,
    onOpenTools: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Tools list (per-tool enable/disable) stays one tap away without
        // stealing the edit entry.
        IconButton(
            onClick = onOpenTools,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Outlined.Construction,
                contentDescription = stringResource(R.string.mcp_tools_title, server.id),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            if (server.isStdio) Icons.Outlined.Terminal else Icons.Outlined.Language,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        SettingsSwitch(
            checked = server.enabled,
            onCheckedChange = onEnabledChange,
        )
    }
}
