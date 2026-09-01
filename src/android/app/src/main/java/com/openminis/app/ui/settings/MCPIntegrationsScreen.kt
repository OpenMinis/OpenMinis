package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.components.MinisTextButton

/**
 * MCP Integrations management screen. Mirrors [SkillsManagementScreen]:
 * a section of server rows (toggle + name + transport summary + transport
 * icon), an add bottom-sheet with Form / Import-JSON tabs, and a delete
 * confirmation dialog. Strings are English-keyed in strings.xml.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MCPIntegrationsScreen(
    mcpRepository: MCPRepository,
    onBack: () -> Unit,
    // [T-mcp-env-var-picker-android] App env vars, for the STDIO env field's
    // "insert app var" picker ($$VAR references resolve at runtime in PRoot).
    // Null when the caller hasn't wired it — the picker affordance hides.
    envVarRepository: com.openminis.app.data.repository.EnvVarRepository? = null,
) {
    val servers by mcpRepository.servers.collectAsState()

    // [T-android-mcp-list-reload-on-appear] MCPRepository reads servers.json
    // only in init() (app launch). A server the agent writes via minis-mcp-cli
    // AFTER launch isn't reflected in the `servers` StateFlow until restart.
    // Re-read the file each time the screen appears (mirrors the Skills screen's
    // reloadFromDisk on entry) so CLI-added servers show without an app restart.
    LaunchedEffect(Unit) {
        mcpRepository.reloadFromDisk()
    }

    var showAddSheet by remember { mutableStateOf(false) }
    // [T-mcp-review-fixes-android] FIX 1: tapping a row opens the form in EDIT
    // mode (matches iOS). null = the sheet is for adding a new server.
    var editServer by remember { mutableStateOf<MCPRepository.MCPServerConfig?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }

    SettingsScaffold(
        title = stringResource(R.string.mcp_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.mcp_add))
            }
        },
    ) {
        SettingsSection(
            header = stringResource(R.string.mcp_section_servers),
            footer = stringResource(R.string.mcp_section_footer),
        ) {
            if (servers.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    )
                    Text(
                        stringResource(R.string.mcp_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.mcp_empty_action),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                servers.forEachIndexed { index, server ->
                    val transportIcon = if (server.isStdio) Icons.Outlined.Terminal else Icons.Outlined.Language
                    SettingsRow(
                        title = server.id,
                        subtitle = server.transportSummary.takeIf { it.isNotBlank() },
                        showChevron = true,
                        showDivider = index < servers.size - 1,
                        // FIX 1: plain tap opens the edit form (was delete-confirm).
                        // Delete stays reachable from inside the edit sheet.
                        onClick = { editServer = server },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    transportIcon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                SettingsSwitch(
                                    checked = server.enabled,
                                    onCheckedChange = { mcpRepository.setEnabled(server.id, it) },
                                )
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showAddSheet) {
        MCPAddSheet(
            mcpRepository = mcpRepository,
            envVarRepository = envVarRepository,
            editServer = null,
            onDismiss = { showAddSheet = false },
            onRequestDelete = {},
        )
    }

    // FIX 1: edit sheet — same form, pre-filled, with a Delete affordance.
    editServer?.let { server ->
        MCPAddSheet(
            mcpRepository = mcpRepository,
            envVarRepository = envVarRepository,
            editServer = server,
            onDismiss = { editServer = null },
            onRequestDelete = {
                editServer = null
                deleteId = server.id
            },
        )
    }

    if (deleteId != null) {
        val id = deleteId!!
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.mcp_delete_title, id)) },
            text = { Text(stringResource(R.string.mcp_delete_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    mcpRepository.delete(id)
                    deleteId = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                MinisTextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

// ─── Add Sheet (Form / Import tabs) ─────────────────────────────────────────

/**
 * Add/Edit bottom sheet. When [editServer] is null this is the add flow
 * (Form / Import-JSON tabs). When it's non-null the sheet is the edit flow:
 * Form only, pre-filled, with a Delete affordance ([onRequestDelete]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MCPAddSheet(
    mcpRepository: MCPRepository,
    envVarRepository: com.openminis.app.data.repository.EnvVarRepository?,
    editServer: MCPRepository.MCPServerConfig?,
    onDismiss: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    // [T-android-mcp-sheet-ime-occlusion] GH#44: skipPartiallyExpanded so the
    // sheet opens full-height — at half-height the soft keyboard left no room
    // for the lower form fields at all.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableIntStateOf(0) }
    val isEdit = editServer != null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // [T-android-mcp-sheet-ime-occlusion] GH#44: verticalScroll + imePadding
        // keep every field reachable while the keyboard is up — imePadding sits
        // after verticalScroll so the inset becomes scrollable bottom space the
        // content can scroll past, instead of the IME clipping the column.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 32.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (isEdit) R.string.mcp_edit else R.string.mcp_add),
                style = MaterialTheme.typography.titleMedium,
            )

            if (!isEdit) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                        Text(stringResource(R.string.mcp_tab_form), modifier = Modifier.padding(12.dp))
                    }
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                        Text(stringResource(R.string.mcp_tab_import), modifier = Modifier.padding(12.dp))
                    }
                }
            }

            if (isEdit || selectedTab == 0) {
                MCPFormTab(
                    mcpRepository = mcpRepository,
                    envVarRepository = envVarRepository,
                    editServer = editServer,
                    onDone = onDismiss,
                    onRequestDelete = onRequestDelete,
                )
            } else {
                MCPImportTab(mcpRepository = mcpRepository, onDone = onDismiss)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MCPFormTab(
    mcpRepository: MCPRepository,
    envVarRepository: com.openminis.app.data.repository.EnvVarRepository?,
    editServer: MCPRepository.MCPServerConfig?,
    onDone: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val context = LocalContext.current
    val isEdit = editServer != null
    // [T-mcp-server-export-android] Clipboard for "Copy JSON".
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // [T-mcp-env-var-picker-android] App env var keys for the STDIO env picker.
    val appEnvEntries by (envVarRepository?.entries
        ?.collectAsState() ?: remember { mutableStateOf(emptyList<com.openminis.app.data.repository.EnvVarRepository.EnvVarEntry>()) })
    val appEnvKeys = remember(appEnvEntries) { appEnvEntries.map { it.key } }
    var showEnvVarPicker by remember { mutableStateOf(false) }
    // [T-mcp-http-headers-env-picker-android] Same $$VAR picker offered on the
    // HTTP/SSE Custom Headers and Server URL value fields.
    var showHeaderVarPicker by remember { mutableStateOf(false) }
    var showUrlVarPicker by remember { mutableStateOf(false) }
    // [T-mcp-review-fixes-android] FIX 4: transport is now 3-way —
    // 0 = HTTP, 1 = SSE, 2 = STDIO. HTTP and SSE both store as a plain `url`
    // entry (no separate storage field — round-trips identically to iOS); only
    // STDIO uses command/args/env. Pre-fill from editServer: STDIO if it has a
    // command, else HTTP (we can't distinguish a persisted SSE from HTTP since
    // both are url-only, so an SSE edit defaults to the HTTP affordance — the
    // saved entry is byte-identical either way).
    var transport by remember { mutableIntStateOf(if (editServer?.isStdio == true) 2 else 0) }
    var name by remember { mutableStateOf(editServer?.id ?: "") }
    var note by remember { mutableStateOf(editServer?.note ?: "") }
    var url by remember { mutableStateOf(editServer?.url ?: "") }
    var headers by remember { mutableStateOf(mapToLines(editServer?.headers, ": ")) }
    var command by remember { mutableStateOf(editServer?.command ?: "") }
    var args by remember { mutableStateOf(editServer?.args?.joinToString(" ") ?: "") }
    var env by remember { mutableStateOf(mapToLines(editServer?.env, "=")) }
    // [T-mcp-startup-timeout] Optional STDIO startup/handshake timeout (seconds).
    // Kept as text so it round-trips; parsed to Int on save; blank = default 60s.
    var startupTimeout by remember { mutableStateOf(editServer?.startupTimeoutSeconds?.toString() ?: "") }
    var errorText by remember { mutableStateOf<String?>(null) }

    // [T-android-mcp-oauth] Static-OAuth fields for a URL transport. Expanded
    // automatically when the edited server already has an oauth block. The
    // client secret is loaded from / saved to MCPOAuthStore (encrypted), never
    // shown pre-filled from JSON (it isn't stored there).
    val oauthScope = androidx.compose.runtime.rememberCoroutineScope()
    var oauthExpanded by remember { mutableStateOf(editServer?.oauth != null) }
    var oauthClientId by remember { mutableStateOf(editServer?.oauth?.clientId ?: "") }
    var oauthClientSecret by remember {
        mutableStateOf(
            editServer?.id?.let {
                com.openminis.app.mcp.oauth.MCPOAuthStore.clientSecret(context, it)
            } ?: "",
        )
    }
    var oauthAuthEndpoint by remember { mutableStateOf(editServer?.oauth?.authorizationEndpoint ?: "") }
    var oauthTokenEndpoint by remember { mutableStateOf(editServer?.oauth?.tokenEndpoint ?: "") }
    var oauthScopes by remember { mutableStateOf(editServer?.oauth?.scopes ?: "") }
    var oauthAuthorized by remember {
        mutableStateOf(
            editServer?.id?.let {
                com.openminis.app.mcp.oauth.MCPOAuthStore.isAuthorized(context, it)
            } ?: false,
        )
    }
    var oauthBusy by remember { mutableStateOf(false) }

    fun currentOAuthConfig(): com.openminis.app.mcp.oauth.MCPOAuthConfig? {
        val cfg = com.openminis.app.mcp.oauth.MCPOAuthConfig(
            clientId = oauthClientId.trim(),
            authorizationEndpoint = oauthAuthEndpoint.trim(),
            tokenEndpoint = oauthTokenEndpoint.trim(),
            scopes = oauthScopes.trim().ifBlank { null },
        )
        return cfg.takeIf { it.isConfigured }
    }
    // [T-mcp-env-var-delete-confirm-android] Drives the confirm dialog before
    // clearing a non-empty env field. (Empty field clears with no confirm.)
    var showClearEnvConfirm by remember { mutableStateOf(false) }

    // True for the url-based transports (HTTP / SSE).
    val isUrlTransport = transport != 2

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = transport == 0,
                onClick = { transport = 0; errorText = null },
                shape = SegmentedButtonDefaults.itemShape(0, 3),
            ) { Text(stringResource(R.string.mcp_transport_http)) }
            SegmentedButton(
                selected = transport == 1,
                onClick = { transport = 1; errorText = null },
                shape = SegmentedButtonDefaults.itemShape(1, 3),
            ) { Text(stringResource(R.string.mcp_transport_sse)) }
            SegmentedButton(
                selected = transport == 2,
                onClick = { transport = 2; errorText = null },
                shape = SegmentedButtonDefaults.itemShape(2, 3),
            ) { Text(stringResource(R.string.mcp_transport_stdio)) }
        }

        FieldLabel(stringResource(R.string.mcp_form_name))
        DialogTextField(
            value = name,
            onValueChange = { name = it; errorText = null },
            placeholder = stringResource(R.string.mcp_form_name_placeholder),
            singleLine = true,
            // Editing keys by name/id; renaming would orphan the row, so lock it.
            enabled = !isEdit,
        )

        if (isUrlTransport) {
            // [T-mcp-http-headers-env-picker-android] Offer the same $$VAR App
            // env-var picker on the URL + Custom Headers value fields (runtime
            // expansion of $$VAR / $VAR in url/headers already works in the
            // shared CLI). Selecting a var appends `$$KEY` to the field so it
            // lands as a value (e.g. after `Authorization: ` in a header line).
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel(stringResource(R.string.mcp_form_url))
                if (envVarRepository != null) {
                    Spacer(Modifier.weight(1f))
                    MinisTextButton(
                        onClick = { showUrlVarPicker = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(stringResource(R.string.mcp_form_env_insert_app_var))
                    }
                }
            }
            DialogTextField(
                value = url,
                onValueChange = { url = it; errorText = null },
                placeholder = stringResource(R.string.mcp_form_url_placeholder),
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel(stringResource(R.string.mcp_form_headers))
                if (envVarRepository != null) {
                    Spacer(Modifier.weight(1f))
                    MinisTextButton(
                        onClick = { showHeaderVarPicker = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(stringResource(R.string.mcp_form_env_insert_app_var))
                    }
                }
            }
            DialogTextField(
                value = headers,
                onValueChange = { headers = it },
                placeholder = stringResource(R.string.mcp_form_headers_placeholder),
                singleLine = false,
                modifier = Modifier.height(72.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )

            // [T-android-mcp-oauth] Static OAuth section (collapsible). When
            // configured, the server authorizes via PKCE and the issued bearer
            // token is attached at connect time (in-guest wiring is a later
            // phase; here the user can configure + complete the auth handshake).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { oauthExpanded = !oauthExpanded },
            ) {
                FieldLabel(stringResource(R.string.mcp_form_oauth_section))
                Spacer(Modifier.weight(1f))
                if (oauthAuthorized) {
                    Text(
                        stringResource(R.string.mcp_form_oauth_authorized),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    imageVector = if (oauthExpanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
            if (oauthExpanded) {
                FieldLabel(stringResource(R.string.mcp_form_oauth_client_id))
                DialogTextField(
                    value = oauthClientId,
                    onValueChange = { oauthClientId = it },
                    placeholder = stringResource(R.string.mcp_form_oauth_client_id_placeholder),
                    singleLine = true,
                )
                FieldLabel(stringResource(R.string.mcp_form_oauth_client_secret))
                DialogTextField(
                    value = oauthClientSecret,
                    onValueChange = { oauthClientSecret = it },
                    placeholder = stringResource(R.string.mcp_form_oauth_client_secret_placeholder),
                    singleLine = true,
                )
                FieldLabel(stringResource(R.string.mcp_form_oauth_auth_endpoint))
                DialogTextField(
                    value = oauthAuthEndpoint,
                    onValueChange = { oauthAuthEndpoint = it },
                    placeholder = stringResource(R.string.mcp_form_oauth_auth_endpoint_placeholder),
                    singleLine = true,
                )
                FieldLabel(stringResource(R.string.mcp_form_oauth_token_endpoint))
                DialogTextField(
                    value = oauthTokenEndpoint,
                    onValueChange = { oauthTokenEndpoint = it },
                    placeholder = stringResource(R.string.mcp_form_oauth_token_endpoint_placeholder),
                    singleLine = true,
                )
                FieldLabel(stringResource(R.string.mcp_form_oauth_scopes))
                DialogTextField(
                    value = oauthScopes,
                    onValueChange = { oauthScopes = it },
                    placeholder = stringResource(R.string.mcp_form_oauth_scopes_placeholder),
                    singleLine = true,
                )
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (oauthAuthorized) {
                        MinisTextButton(
                            enabled = !oauthBusy,
                            onClick = {
                                val sid = name.trim()
                                if (sid.isNotEmpty()) {
                                    com.openminis.app.mcp.oauth.MCPOAuthStore.signOut(context, sid)
                                    oauthAuthorized = false
                                }
                            },
                        ) {
                            Text(
                                stringResource(R.string.mcp_form_oauth_sign_out),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    MinisTextButton(
                        enabled = !oauthBusy,
                        onClick = {
                            val sid = name.trim()
                            val cfg = currentOAuthConfig()
                            when {
                                sid.isEmpty() ->
                                    errorText = context.getString(R.string.mcp_form_error_no_name)
                                url.isBlank() ->
                                    errorText = context.getString(R.string.mcp_form_error_no_transport)
                                cfg == null ->
                                    errorText = context.getString(R.string.mcp_form_oauth_incomplete)
                                else -> {
                                    errorText = null
                                    // Persist the secret so the token exchange can
                                    // read it; harmless if the flow is cancelled.
                                    com.openminis.app.mcp.oauth.MCPOAuthStore.setClientSecret(
                                        context, sid, oauthClientSecret.trim().ifBlank { null },
                                    )
                                    oauthBusy = true
                                    oauthScope.launch {
                                        val result = com.openminis.app.mcp.oauth.MCPOAuthController(context)
                                            .authorize(sid, url.trim(), cfg)
                                        oauthBusy = false
                                        when (result) {
                                            is com.openminis.app.mcp.oauth.MCPOAuthController.Result.Success -> {
                                                oauthAuthorized = true
                                                android.widget.Toast.makeText(
                                                    context,
                                                    context.getString(R.string.mcp_form_oauth_authorized),
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                            is com.openminis.app.mcp.oauth.MCPOAuthController.Result.Cancelled -> {}
                                            is com.openminis.app.mcp.oauth.MCPOAuthController.Result.Failed -> {
                                                errorText = result.message
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                if (oauthAuthorized) R.string.mcp_form_oauth_reauthorize
                                else R.string.mcp_form_oauth_authorize,
                            ),
                        )
                    }
                }
            }
        } else {
            FieldLabel(stringResource(R.string.mcp_form_command))
            DialogTextField(
                value = command,
                onValueChange = { command = it; errorText = null },
                placeholder = stringResource(R.string.mcp_form_command_placeholder),
                singleLine = true,
            )
            FieldLabel(stringResource(R.string.mcp_form_args))
            DialogTextField(
                value = args,
                onValueChange = { args = it },
                placeholder = stringResource(R.string.mcp_form_args_placeholder),
                singleLine = true,
            )
            // [T-mcp-env-var-picker-android] Env label + "insert app var"
            // affordance. Tapping opens a picker of App env var keys; selecting
            // one appends a `KEY=$$KEY` line so the value resolves at runtime
            // from the PRoot guest env (App env vars are injected there). The
            // affordance only shows when an EnvVarRepository was wired in.
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel(stringResource(R.string.mcp_form_env))
                Spacer(Modifier.weight(1f))
                if (envVarRepository != null) {
                    MinisTextButton(
                        onClick = { showEnvVarPicker = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(stringResource(R.string.mcp_form_env_insert_app_var))
                    }
                }
                // [T-mcp-env-var-delete-confirm-android] Explicit clear/delete
                // affordance. Non-empty → confirm dialog before wiping; empty →
                // clears immediately (no-op confirm). Disabled while blank so the
                // affordance reads as inert when there's nothing to delete.
                MinisTextButton(
                    onClick = {
                        if (env.isBlank()) env = "" else showClearEnvConfirm = true
                    },
                    enabled = env.isNotBlank(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp, vertical = 0.dp,
                    ),
                ) {
                    Text(
                        stringResource(R.string.mcp_form_env_clear),
                        color = if (env.isNotBlank()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DialogTextField(
                value = env,
                onValueChange = { env = it },
                placeholder = stringResource(R.string.mcp_form_env_placeholder),
                singleLine = false,
                modifier = Modifier.height(72.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            FieldLabel(stringResource(R.string.mcp_form_startup_timeout))
            DialogTextField(
                value = startupTimeout,
                onValueChange = { startupTimeout = it.filter { c -> c.isDigit() } },
                placeholder = stringResource(R.string.mcp_form_startup_timeout_placeholder),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
            )
        }

        FieldLabel(stringResource(R.string.mcp_form_note))
        DialogTextField(
            value = note,
            onValueChange = { note = it },
            placeholder = stringResource(R.string.mcp_form_note_placeholder),
            singleLine = false,
            modifier = Modifier.height(64.dp),
        )

        if (errorText != null) {
            Text(errorText!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        // [T-mcp-server-export-android] Build a MCPServerConfig from the CURRENT
        // form fields (so Copy/Share export reflects unsaved edits, same
        // construction Save uses). Returns null + sets errorText when invalid.
        // HTTP (0) and SSE (1) both persist as a url-only entry; STDIO (2)
        // persists command/args/env. createdAt preserved on edit.
        fun buildServerFromForm(): MCPRepository.MCPServerConfig? {
            if (name.isBlank()) {
                errorText = context.getString(R.string.mcp_form_error_no_name); return null
            }
            return if (isUrlTransport) {
                if (url.isBlank()) {
                    errorText = context.getString(R.string.mcp_form_error_no_transport); return null
                }
                MCPRepository.MCPServerConfig(
                    id = name.trim(),
                    note = note.trim().ifBlank { null },
                    enabled = editServer?.enabled ?: true,
                    url = url.trim(),
                    headers = parseKeyValueLines(headers, ':'),
                    // [T-android-mcp-oauth] Persist the non-secret OAuth config.
                    oauth = currentOAuthConfig(),
                    createdAt = editServer?.createdAt ?: System.currentTimeMillis(),
                )
            } else {
                if (command.isBlank()) {
                    errorText = context.getString(R.string.mcp_form_error_no_transport); return null
                }
                MCPRepository.MCPServerConfig(
                    id = name.trim(),
                    note = note.trim().ifBlank { null },
                    enabled = editServer?.enabled ?: true,
                    command = command.trim(),
                    args = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() },
                    env = parseKeyValueLines(env, '='),
                    startupTimeoutSeconds = startupTimeout.trim().toIntOrNull(),
                    createdAt = editServer?.createdAt ?: System.currentTimeMillis(),
                )
            }
        }

        // [T-mcp-server-export-android] Export the current config as standard
        // mcpServers JSON — Copy to clipboard + Share intent. Builds from the
        // live form so it includes unsaved edits.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MinisTextButton(onClick = {
                val server = buildServerFromForm() ?: return@MinisTextButton
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(mcpRepository.exportServerJSON(server)))
                android.widget.Toast.makeText(
                    context, context.getString(R.string.mcp_export_copied), android.widget.Toast.LENGTH_SHORT,
                ).show()
            }) { Text(stringResource(R.string.mcp_export_copy)) }
            MinisTextButton(onClick = {
                val server = buildServerFromForm() ?: return@MinisTextButton
                shareMcpServerJson(context, server.id, mcpRepository.exportServerJSON(server))
            }) { Text(stringResource(R.string.mcp_export_share)) }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // FIX 1: Delete affordance lives inside the edit sheet so a plain
            // row tap no longer means delete, without losing the delete path.
            if (isEdit) {
                MinisTextButton(onClick = onRequestDelete) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.weight(1f))
            MinisTextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
            MinisTextButton(onClick = {
                val server = buildServerFromForm() ?: return@MinisTextButton
                // [T-android-mcp-oauth] Store the client secret in the encrypted
                // store (keyed by server id) — it never goes into servers.json.
                // Cleared when the OAuth section is emptied.
                if (isUrlTransport) {
                    com.openminis.app.mcp.oauth.MCPOAuthStore.setClientSecret(
                        context, server.id,
                        if (server.oauth != null) oauthClientSecret.trim().ifBlank { null } else null,
                    )
                }
                if (isEdit) mcpRepository.update(server) else mcpRepository.add(server)
                onDone()
            }) { Text(stringResource(R.string.mcp_form_save)) }
        }
    }

    // [T-mcp-env-var-picker-android] App env var picker. Appends a
    // `KEY=$$KEY` line to the env editor (the $$KEY reference is saved verbatim
    // and expanded at runtime). Manual typing in the editor still works.
    if (showEnvVarPicker) {
        MCPEnvVarPickerSheet(
            keys = appEnvKeys,
            onPick = { key ->
                // [T-mcp-env-picker-insert-android] APPEND, don't replace
                // (reverses #719, aligns with iOS #728 8f90f2a8). Picking a var
                // adds a new KEY=$$KEY line so the user can pick MULTIPLE vars
                // and they accumulate (no dedup/overwrite). The env editor is a
                // multiline KEY=VALUE textarea, so a whole line is the natural
                // unit to append; a blank field seeds the first line.
                val line = "$key=$\$$key"
                env = if (env.isBlank()) line else env.trimEnd('\n') + "\n" + line
                showEnvVarPicker = false
            },
            onDismiss = { showEnvVarPicker = false },
        )
    }

    // [T-mcp-http-headers-env-picker-android] Headers / URL pickers. Unlike the
    // STDIO env textarea (whole KEY=VALUE lines), headers/url are values the
    // var reference drops INTO (e.g. `Authorization: $$KEY`, or `…?token=$$KEY`).
    // So we append just `$$KEY` to the end of the field — with a leading space
    // when the current text doesn't already end in whitespace, so it reads as a
    // value rather than fusing onto the preceding token. Manual editing intact.
    if (showHeaderVarPicker) {
        MCPEnvVarPickerSheet(
            keys = appEnvKeys,
            onPick = { key ->
                val ref = "$\$$key"
                headers = if (headers.isEmpty() || headers.last().isWhitespace()) {
                    headers + ref
                } else {
                    "$headers $ref"
                }
                showHeaderVarPicker = false
            },
            onDismiss = { showHeaderVarPicker = false },
        )
    }
    if (showUrlVarPicker) {
        MCPEnvVarPickerSheet(
            keys = appEnvKeys,
            onPick = { key ->
                val ref = "$\$$key"
                url = if (url.isEmpty() || url.last().isWhitespace()) url + ref else "$url$ref"
                errorText = null
                showUrlVarPicker = false
            },
            onDismiss = { showUrlVarPicker = false },
        )
    }

    // [T-mcp-env-var-delete-confirm-android] Confirm before wiping a non-empty
    // env field. Only reachable when env was non-blank at tap time.
    if (showClearEnvConfirm) {
        AlertDialog(
            onDismissRequest = { showClearEnvConfirm = false },
            title = { Text(stringResource(R.string.mcp_form_env_clear_confirm_title)) },
            text = { Text(stringResource(R.string.mcp_form_env_clear_confirm_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    env = ""
                    showClearEnvConfirm = false
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showClearEnvConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * [T-mcp-env-var-picker-android] Bottom sheet listing App environment variable
 * keys. Picking one inserts a `$$KEY` reference into the MCP server's env (via
 * the caller's [onPick]). Shows an empty-state hint when there are none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MCPEnvVarPickerSheet(
    keys: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.mcp_env_picker_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (keys.isEmpty()) {
                Text(
                    stringResource(R.string.mcp_env_picker_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                for (key in keys) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(key) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "$\$$key",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MCPImportTab(
    mcpRepository: MCPRepository,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    val previewCount = remember(text) { if (text.isBlank()) 0 else mcpRepository.previewImport(text) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.mcp_import_instruction),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DialogTextField(
            value = text,
            onValueChange = { text = it; errorText = null },
            placeholder = stringResource(R.string.mcp_import_placeholder),
            singleLine = false,
            modifier = Modifier.height(200.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        if (text.isNotBlank()) {
            Text(
                stringResource(R.string.mcp_import_found, previewCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (errorText != null) {
            Text(errorText!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            MinisTextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
            MinisTextButton(
                onClick = {
                    val imported = mcpRepository.importJSON(text)
                    if (imported.isEmpty()) errorText = context.getString(R.string.mcp_import_none)
                    else onDone()
                },
                enabled = previewCount > 0,
            ) { Text(stringResource(R.string.mcp_import_submit)) }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Parse multi-line "Key<sep> Value" text into a map. Lines without the
 * separator are skipped. Used for headers (`:`) and env (`=`).
 */
/**
 * Serialize a map back into multi-line "Key<joiner>Value" text for pre-filling
 * the edit form. Inverse of [parseKeyValueLines]. Empty/null → "".
 */
/**
 * [T-mcp-server-export-android] Share [json] (a single-server mcpServers
 * config) via ACTION_SEND. Mirrors ProviderDetailScreen.exportProviderInstance:
 * write to cacheDir/shared/<safeName>-mcp.json, expose via FileProvider
 * (${packageName}.fileprovider, configured for cache-path "shared/"), and
 * launch a chooser with type application/json.
 */
private fun shareMcpServerJson(context: android.content.Context, serverId: String, json: String) {
    val safeName = serverId.ifBlank { "mcp-server" }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
    val file = java.io.File(dir, "$safeName-mcp.json")
    runCatching { file.writeText(json) }.onFailure { return }
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
    } catch (_: Throwable) {
        return
    }
    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "$safeName-mcp.json")
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = android.content.Intent.createChooser(sendIntent, "Export MCP server").apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

private fun mapToLines(map: Map<String, String>?, joiner: String): String {
    if (map.isNullOrEmpty()) return ""
    return map.entries.joinToString("\n") { "${it.key}$joiner${it.value}" }
}

private fun parseKeyValueLines(text: String, sep: Char): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val idx = trimmed.indexOf(sep)
        if (idx <= 0) continue
        val key = trimmed.substring(0, idx).trim()
        val value = trimmed.substring(idx + 1).trim()
        if (key.isNotEmpty()) out[key] = value
    }
    return out
}
