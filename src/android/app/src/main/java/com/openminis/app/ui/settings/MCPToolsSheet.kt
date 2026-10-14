package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.openminis.app.ui.components.MinisTextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.openminis.app.data.repository.MCPToolPolicy
import com.openminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class McpToolRow(val name: String, val description: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MCPToolsSheet(
    server: MCPRepository.MCPServerConfig,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tools by remember { mutableStateOf<List<McpToolRow>>(emptyList()) }
    var disabled by remember { mutableStateOf(MCPToolPolicy.disabled(context, server.id)) }

    LaunchedEffect(server.id) {
        busy = true
        error = null
        val quoted = "'" + server.id.replace("'", "'\\''") + "'"
        val result = withContext(Dispatchers.IO) {
            ExecutionCoordinator.execute(
                sessionId = "mcp-tools-ui",
                command = "minis-mcp-cli tools $quoted",
                timeout = 25_000L,
            )
        }
        tools = parseMcpTools(result.output)
        disabled = MCPToolPolicy.disabled(context, server.id)
        if (tools.isEmpty()) {
            error = result.output.ifBlank { "exit ${result.exitCode}" }
        }
        busy = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.mcp_tools_title, server.id),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                stringResource(R.string.mcp_tools_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            when {
                busy -> Text(
                    stringResource(R.string.mcp_tools_loading),
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                tools.isEmpty() -> Text(
                    error ?: stringResource(R.string.mcp_tools_empty),
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> tools.forEach { tool ->
                    val on = tool.name !in disabled
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tool.name, style = MaterialTheme.typography.bodyLarge)
                            if (tool.description.isNotBlank()) {
                                Text(
                                    tool.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(
                            checked = on,
                            onCheckedChange = { enabled ->
                                MCPToolPolicy.setEnabled(context, server.id, tool.name, enabled)
                                disabled = MCPToolPolicy.disabled(context, server.id)
                            },
                        )
                    }
                    HorizontalDivider()
                }
            }
            TextButton(
                onClick = onManage,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.mcp_manage)) }
        }
    }
}

@Composable
fun MCPToolsScreen(
    mcpRepository: MCPRepository,
    serverId: String,
    onBack: () -> Unit,
    onManage: () -> Unit,
) {
    val servers by mcpRepository.servers.collectAsState()
    val server = servers.find { it.id == serverId }
    val context = LocalContext.current
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tools by remember { mutableStateOf<List<McpToolRow>>(emptyList()) }
    var disabled by remember(serverId) { mutableStateOf(MCPToolPolicy.disabled(context, serverId)) }
    var confirmDelete by remember { mutableStateOf(false) }
    // [T-mcp-tools-refresh] The load is re-runnable: the refresh action passes
    // --refresh (the CLI evicts the daemon's live session first, so the list
    // reflects tools the remote server added after we connected) and doubles
    // as the retry path when the first load failed — previously a failure
    // dead-ended with no way to try again short of leaving the screen.
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(serverId, servers) {
        if (servers.isNotEmpty() && server == null) onBack()
    }

    LaunchedEffect(server?.id, refreshTick) {
        val current = server ?: return@LaunchedEffect
        busy = true
        error = null
        val quoted = "'" + current.id.replace("'", "'\\''") + "'"
        val refresh = if (refreshTick > 0) " --refresh" else ""
        val result = withContext(Dispatchers.IO) {
            ExecutionCoordinator.execute(
                sessionId = "mcp-tools-ui",
                command = "minis-mcp-cli tools $quoted$refresh",
                timeout = 25_000L,
            )
        }
        tools = parseMcpTools(result.output)
        disabled = MCPToolPolicy.disabled(context, current.id)
        if (tools.isEmpty()) {
            error = result.output.ifBlank { "exit ${result.exitCode}" }
        }
        busy = false
    }

    SettingsScaffold(
        title = stringResource(R.string.mcp_tools_title, server?.id ?: serverId),
        onBack = onBack,
        actions = {
            IconButton(
                onClick = { refreshTick++ },
                enabled = !busy,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.mcp_tools_refresh),
                    )
                }
            }
        },
    ) {
        Text(
            stringResource(R.string.mcp_tools_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        when {
            busy -> Text(
                stringResource(R.string.mcp_tools_loading),
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            tools.isEmpty() -> Column(Modifier.padding(20.dp)) {
                Text(
                    error ?: stringResource(R.string.mcp_tools_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Retry after a failure — same --refresh path as the top bar.
                TextButton(onClick = { refreshTick++ }) {
                    Text(stringResource(R.string.mcp_tools_refresh))
                }
            }
            else -> tools.forEach { tool ->
                val on = tool.name !in disabled
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tool.name, style = MaterialTheme.typography.bodyLarge)
                        if (tool.description.isNotBlank()) {
                            Text(
                                tool.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = on,
                        onCheckedChange = { enabled ->
                            MCPToolPolicy.setEnabled(context, serverId, tool.name, enabled)
                            disabled = MCPToolPolicy.disabled(context, serverId)
                        },
                    )
                }
                HorizontalDivider()
            }
        }
        // [T-mcp-fix-delete-entry] The old "manage" button just popped back
        // (the edit sheet had lost its entry point, so delete was
        // unreachable). This screen now carries the delete itself.
        TextButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.mcp_delete_server), color = MaterialTheme.colorScheme.error) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.mcp_delete_title, serverId)) },
            text = { Text(stringResource(R.string.mcp_delete_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    mcpRepository.delete(serverId)
                    confirmDelete = false
                    onBack()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                MinisTextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private fun parseMcpTools(output: String): List<McpToolRow> {
    val start = output.indexOf('{')
    val end = output.lastIndexOf('}')
    if (start < 0 || end <= start) return emptyList()
    return try {
        val obj = JSONObject(output.substring(start, end + 1))
        val arr: JSONArray = obj.optJSONArray("tools") ?: return emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                when (val item = arr.get(i)) {
                    is JSONObject -> {
                        val name = item.optString("name")
                        if (name.isNotBlank()) add(McpToolRow(name, item.optString("description")))
                    }
                    is String -> if (item.isNotBlank()) add(McpToolRow(item, ""))
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}
