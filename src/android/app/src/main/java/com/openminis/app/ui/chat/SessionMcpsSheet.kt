package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.settings.SettingsSwitch
import com.openminis.app.R
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSection

private const val MCP_NOTE_TRUNC = 200

/**
 * Bottom sheet showing all configured MCP servers with per-session
 * enable/disable toggles. Mirrors [SessionSkillsSheet]: [StandardChatSheet]
 * shell + shared [SettingsSection] / [SettingsRow] primitives. Each row shows
 * the server name + truncated note, with a Switch writing a session override
 * via [MCPRepository.setSessionOverride].
 */
@Composable
fun SessionMcpsSheet(
    sessionId: String,
    mcpRepository: MCPRepository,
    onDismiss: () -> Unit,
) {
    val servers by mcpRepository.servers.collectAsState()

    // [T-mcp-review-fixes-android] Key the seed on `servers` so the override map
    // re-derives when the live server list arrives. A keyless remember{} seeds
    // once from a possibly-empty first-composition list and then never re-runs,
    // leaving every toggle stuck at its default (the review's stale-override race).
    val overrides = remember(servers) {
        mutableStateMapOf<String, Boolean>().apply {
            for (server in servers) {
                put(server.id, mcpRepository.isEnabledForSession(server.id, sessionId))
            }
        }
    }

    StandardChatSheet(
        title = stringResource(R.string.session_mcps_title),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (servers.isEmpty()) {
                EmptyMcpsCard()
            } else {
                SettingsSection(
                    footer = stringResource(R.string.session_mcps_footer),
                ) {
                    servers.forEachIndexed { index, server ->
                        val note = server.note?.trim().orEmpty()
                        val sub = note
                            .ifEmpty { server.transportSummary }
                            .let { if (it.length > MCP_NOTE_TRUNC) it.substring(0, MCP_NOTE_TRUNC) + "…" else it }
                        SettingsRow(
                            title = server.id,
                            subtitle = sub.takeIf { it.isNotBlank() },
                            showChevron = false,
                            showDivider = index < servers.size - 1,
                            trailing = {
                                SettingsSwitch(
                                    checked = overrides[server.id] ?: server.enabled,
                                    onCheckedChange = { enabled ->
                                        overrides[server.id] = enabled
                                        mcpRepository.setSessionOverride(sessionId, server.id, enabled)
                                    },
                                )
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

@Composable
private fun EmptyMcpsCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Extension,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
            Text(
                stringResource(R.string.session_mcps_empty_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.session_mcps_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}
