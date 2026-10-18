package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.openminis.app.ui.components.MinisCenterTopBar
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.openminis.app.ProjectRepo
import com.openminis.app.BuildConfig
import com.openminis.app.R
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.evolution.EvolutionPrefs
import com.openminis.app.ui.components.openExternalUrl
import com.openminis.app.i18n.uppercaseForDisplay
import com.openminis.app.ui.theme.monetIconTint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onProvidersClick: () -> Unit,
    onModelGroupsClick: () -> Unit,
    onRootfsClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    onEnvVarsClick: () -> Unit = {},
    onSkillsClick: () -> Unit = {},
    onMultiAgentClick: () -> Unit = {},
    onTerminalClick: () -> Unit = {},
    onMemoryClick: () -> Unit = {},
    // [T-mcp-integration-android] MCP Integrations page, listed directly below
    // Memory. Default no-op for callers that haven't wired the route yet.
    onMcpClick: () -> Unit = {},
    onPluginMarketClick: () -> Unit = {},
    // [T-soul-md] Soul settings page lives between Skills and Memory in the
    // Agent Runtime section; default no-op for callers that haven't wired
    // the route yet.
    onSoulClick: () -> Unit = {},
    onEvolutionClick: () -> Unit = {},
    onPermissionsClick: () -> Unit = {},
    // [T-android-system-permissions-entry] SystemPermissionsScreen existed but
    // had no navigation to it anywhere in the app (see Routes.SYSTEM_PERMISSIONS),
    // which also hid the ONLY surface for the voice-correction consent toggle /
    // data wipe and the force-stop accessibility repair row. Default no-op keeps
    // older callers and previews compiling.
    onSystemPermissionsClick: () -> Unit = {},
    onUsageClick: () -> Unit = {},
    onAppearanceClick: () -> Unit = {},
    onLogsClick: () -> Unit = {},
    // T219-2: Mount External Folders entry. Default no-op for any caller
    // that hasn't wired the route yet.
    onMountedFoldersClick: () -> Unit = {},
    // T235: Shared Folders entry (Shared / Skills / Memory). Default no-op
    // for back-compat with callers wired before T235.
    onSharedFoldersClick: () -> Unit = {},
    // T50: Background & Notifications screen (battery optimisation +
    // OEM autostart guidance). Default no-op so older callers/tests
    // don't need to be retrofitted.
    onBackgroundClick: () -> Unit = {},
    onWebSearchClick: () -> Unit = {},
    onCharacterExtrasClick: () -> Unit = {},
    onTranslateClick: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    onBrowserSettings: () -> Unit = {},
    onAboutClick: () -> Unit = {},
) {
    val context = LocalContext.current
    var showFeedbackSheet by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            MinisCenterTopBar(
                title = { Text(stringResource(R.string.settings_title)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // -- LLM Providers --
            SettingsSection(
                title = stringResource(R.string.settings_section_llm_providers),
                footer = stringResource(R.string.settings_section_llm_providers_footer),
            ) {
                SettingsItem(
                    icon = Icons.Outlined.Lock,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_manage_providers),
                    subtitle = stringResource(R.string.settings_manage_providers_subtitle),
                    onClick = onProvidersClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Settings,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_model_groups),
                    subtitle = stringResource(R.string.settings_model_groups_subtitle),
                    onClick = onModelGroupsClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.BarChart,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_token_usage),
                    subtitle = stringResource(R.string.settings_token_usage_subtitle),
                    onClick = onUsageClick,
                    showDivider = false,
                )
            }

            // -- Appearance --
            SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
                SettingsItem(
                    icon = Icons.Outlined.Palette,
                    iconColor = Color(0xFF5856D6),
                    title = stringResource(R.string.settings_section_appearance),
                    subtitle = stringResource(R.string.settings_appearance_subtitle),
                    onClick = onAppearanceClick,
                    showDivider = false,
                )
            }

            // -- Agent Runtime --
            SettingsSection(
                title = stringResource(R.string.settings_section_agent_runtime),
                collapsible = true,
                initiallyExpanded = false,
            ) {
                SettingsItem(
                    icon = Icons.Outlined.Groups,
                    iconColor = Color(0xFF5856D6),
                    title = stringResource(R.string.settings_multi_agent),
                    subtitle = stringResource(R.string.settings_multi_agent_subtitle),
                    onClick = onMultiAgentClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Extension,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_skills),
                    subtitle = stringResource(R.string.settings_skills_subtitle),
                    onClick = onSkillsClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.TravelExplore,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.settings_web_search),
                    subtitle = stringResource(R.string.settings_web_search_subtitle),
                    onClick = onWebSearchClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Translate,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_translate),
                    subtitle = stringResource(R.string.settings_translate_subtitle),
                    onClick = onTranslateClick,
                )
                // [T-soul-md] insertion between Skills and Memory per spec.
                SettingsItem(
                    icon = Icons.Outlined.AutoAwesome,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.settings_soul),
                    subtitle = stringResource(R.string.settings_soul_subtitle),
                    onClick = onSoulClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Psychology,
                    iconColor = Color(0xFF5856D6),
                    title = stringResource(R.string.settings_memory),
                    subtitle = stringResource(R.string.settings_memory_subtitle),
                    onClick = onMemoryClick,
                )
                // Evolution lives inside Memory. Translation lives on the message bubble.
                // [T-mcp-integration-android] MCP Integrations — directly below Memory.
                // [T-android-mcp-icon-distinct] Dashboard (2x2 block grid) instead of
                // Extension so MCP no longer shares the Skills row's puzzle-piece icon —
                // the grid reads as "multiple composed blocks/servers". teal unchanged.
                SettingsItem(
                    icon = Icons.Outlined.Dashboard,
                    iconColor = Color(0xFF30B0C7),
                    title = stringResource(R.string.settings_mcp),
                    subtitle = stringResource(R.string.settings_mcp_subtitle),
                    onClick = onMcpClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Extension,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.plugin_market_title),
                    subtitle = stringResource(R.string.plugin_market_subtitle),
                    onClick = onPluginMarketClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Terminal,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.settings_env_vars),
                    subtitle = stringResource(R.string.settings_env_vars_subtitle),
                    onClick = onEnvVarsClick,
                    showDivider = false,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_tools)) {
                SettingsItem(
                    icon = Icons.Outlined.Terminal,
                    iconColor = Color(0xFF5856D6),
                    title = stringResource(R.string.sessionlist_shell_terminal),
                    subtitle = stringResource(R.string.settings_section_tools),
                    onClick = onTerminalClick,
                )
                // [T-tool-approval-removed] 审批门按 LO 裁决移除。
                SettingsItem(
                    icon = Icons.Outlined.Language,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.sessionlist_open_browser),
                    subtitle = stringResource(R.string.sessionlist_browser_settings),
                    onClick = onOpenBrowser,
                )
                SettingsItem(
                    icon = Icons.Outlined.Settings,
                    iconColor = Color(0xFF8E8E93),
                    title = stringResource(R.string.sessionlist_browser_settings),
                    subtitle = null,
                    onClick = onBrowserSettings,
                    showDivider = false,
                )
            }

            // -- Storage --
            SettingsSection(title = stringResource(R.string.settings_section_storage)) {
                SettingsItem(
                    icon = Icons.Outlined.Inventory2,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_section_storage),
                    subtitle = stringResource(R.string.settings_storage_subtitle),
                    onClick = onRootfsClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Folder,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.settings_shared_folders),
                    subtitle = stringResource(R.string.settings_shared_folders_subtitle),
                    onClick = onSharedFoldersClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.FolderShared,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.settings_mount_external_folders),
                    subtitle = stringResource(R.string.settings_mount_external_folders_subtitle),
                    onClick = onMountedFoldersClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Backup,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.settings_backup_restore),
                    subtitle = stringResource(R.string.settings_backup_restore_subtitle),
                    onClick = onBackupClick,
                    showDivider = false,
                )
            }

            // -- Permissions --
            SettingsSection(title = stringResource(R.string.settings_section_permissions)) {
                SettingsItem(
                    icon = Icons.Outlined.Shield,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_section_permissions),
                    subtitle = stringResource(R.string.settings_permissions_subtitle),
                    onClick = onPermissionsClick,
                    showDivider = true,
                )
                // [T-android-system-permissions-entry] OS-level grants the app
                // cannot toggle itself: accessibility service status + the
                // force-stop repair, OEM keep-alive guidance, and the
                // voice-correction data consent. Subtitle stays null because no
                // string key describes this page on its own and res/values is
                // outside this change.
                SettingsItem(
                    icon = Icons.Outlined.Accessibility,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.system_permissions_title),
                    subtitle = null,
                    onClick = onSystemPermissionsClick,
                    showDivider = false,
                )
            }

            // -- Background & Notifications (T50) --
            SettingsSection(
                title = stringResource(R.string.bg_section_header),
                footer = stringResource(R.string.bg_section_footer),
            ) {
                SettingsItem(
                    icon = Icons.Outlined.BatteryFull,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.bg_section_header),
                    subtitle = stringResource(R.string.bg_section_subtitle),
                    onClick = onBackgroundClick,
                    showDivider = false,
                )
            }

            // -- Logs --
            SettingsSection(title = stringResource(R.string.settings_section_logs)) {
                SettingsItem(
                    icon = Icons.Outlined.Description,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_section_logs),
                    subtitle = stringResource(R.string.settings_logs_subtitle),
                    onClick = onLogsClick,
                    showDivider = false,
                )
            }

            // -- About --
            SettingsSection(title = stringResource(R.string.settings_section_about)) {
                SettingsItem(
                    icon = Icons.Outlined.Info,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_about_minis),
                    subtitle = stringResource(R.string.settings_about_subtitle),
                    onClick = onAboutClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.FrontHand,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_privacy_policy),
                    subtitle = null,
                    // iOS canonical URL — ContentView.swift / AddProviderView.swift
                    onClick = { openExternalUrl(context, "https://openminis.github.io/privacy-policy.html") },
                )
                SettingsItem(
                    icon = Icons.Outlined.Feedback,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_feedback),
                    subtitle = null,
                    onClick = { showFeedbackSheet = true },
                    showDivider = false,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showFeedbackSheet) {
        ModalBottomSheet(onDismissRequest = { showFeedbackSheet = false }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                FeedbackSheetItem(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.settings_submit_github_issues),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, buildBugReportUrl())
                    },
                )
                FeedbackSheetItem(
                    icon = Icons.AutoMirrored.Outlined.Send,
                    title = stringResource(R.string.settings_feedback_telegram),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, "https://t.me/+2NzhOJuzRyI1YmM1")
                    },
                )
                FeedbackSheetItem(
                    icon = Icons.Outlined.Email,
                    title = stringResource(R.string.settings_feedback_email),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, buildFeedbackMailto())
                    },
                )
            }
        }
    }
}

@Composable
private fun FeedbackSheetItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Build the GitHub Issues "new bug report" URL with the body pre-filled
 * from the existing bug-report template. Platform / OS version / app
 * version / device model are injected so the report arrives ready to
 * triage instead of asking the user to fill in environment details.
 *
 * URL shape:
 *   https://github.com/OpenMinis/OpenMinis/issues/new
 *     ?template=bug_report.md
 *     &title=[Bug]
 *     &body=<percent-encoded markdown>
 *
 * The body is a Markdown template with sections for Problem Summary,
 * Basic Information (table — auto-filled), Steps to Reproduce, Error
 * Details (fenced code block), Expected Behavior, and Additional
 * Information.
 */
private fun buildBugReportUrl(): String {
    val osVersion = android.os.Build.VERSION.RELEASE
    val sdkInt = android.os.Build.VERSION.SDK_INT
    val versionName = BuildConfig.VERSION_NAME
    val versionCode = BuildConfig.VERSION_CODE
    val manufacturer = android.os.Build.MANUFACTURER
    val model = android.os.Build.MODEL

    // Body matches the spec template. Triple-backtick fences are written
    // as "```" — they survive percent-encoding cleanly. Indentation here
    // is significant: trimIndent() removes the common Kotlin indentation
    // but preserves the Markdown structure as-is.
    val body = """
        ## 📝 Problem Summary

        <!-- Briefly describe the issue you encountered -->


        ## 📱 Basic Information

        | Field | Value |
        |-------|-------|
        | Platform | Android |
        | OS Version | Android $osVersion (API $sdkInt) |
        | Minis Version | $versionName (build $versionCode) |
        | Device Model | $manufacturer $model |

        ## 🔁 Steps to Reproduce

        1.
        2.
        3.

        ## ❌ Error Details

        ```
        paste error here
        ```

        ## ✅ Expected Behavior



        ## 🗂️ Additional Information

    """.trimIndent()

    val encodedBody = java.net.URLEncoder.encode(body, "UTF-8")
    // Title carries a "[Bug] " prefix with a trailing space so the cursor
    // lands after it on GitHub's page; encode the space as %20 explicitly
    // since URLEncoder turns spaces into '+' which GitHub also accepts but
    // the spec calls for the literal "[Bug] " form.
    val title = java.net.URLEncoder.encode("[Bug] ", "UTF-8")
    return ProjectRepo.ISSUES_NEW_URL +
        "?template=bug_report.md" +
        "&title=$title" +
        "&body=$encodedBody"
}

/**
 * Compose a `mailto:` URL with a prefilled subject and body that include
 * app version, Android version, and device model. Mirrors iOS
 * `ContentView.makeFeedbackEmailURL()`.
 */
private fun buildFeedbackMailto(): String {
    val body = """
        Please describe your feedback:


        ---
        App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
        Android Version: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})
        Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}

        Screenshot (optional): Please attach a screenshot if relevant.
    """.trimIndent()
    val subject = java.net.URLEncoder.encode("Minis Feedback", "UTF-8")
    val encodedBody = java.net.URLEncoder.encode(body, "UTF-8")
    return "mailto:dev@openminis.app?subject=$subject&body=$encodedBody"
}

/**
 * A grouped settings section with header and optional footer, matching iOS grouped List sections.
 *
 * [T-android-settings-collapsible] Sections with many rows (Agent Runtime has
 * 11) can be collapsed by tapping the header. The collapsed state is held in
 * the caller's rememberSaveable so it survives rotation and process death.
 * Sections with ≤2 rows stay always-expanded (collapsing them saves nothing).
 */
@Composable
private fun SettingsSection(
    title: String,
    footer: String? = null,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    ) {
        // Section header — tappable when collapsible
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title.uppercaseForDisplay(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                modifier = Modifier.weight(1f),
            )
            if (collapsible) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Section card — animated collapse
        if (expanded) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            content()
        }

        // Section footer
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                lineHeight = 16.sp,
            )
        }
        }
    }
}

/**
 * A single settings row item with colored icon, title, optional subtitle, and chevron.
 * Styled to match iOS settings rows with SF Symbol-like colored circle icons.
 *
 * [T-android-monet-settings-icon] The colored circle background follows the
 * wallpaper accent in dynamic-color mode — same contract as AppearanceScreen's
 * tile icons. Without this wrap the settings home showed static iOS hues while
 * every other themed surface shifted, producing two visual systems in one app.
 */
@Composable
private fun SettingsItem(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    showDivider: Boolean = true,
) {
    val tileColor = monetIconTint(iconColor)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Colored circle icon (matching iOS settings style)
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        color = tileColor,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }

            Spacer(Modifier.width(14.dp))

            // Title + subtitle
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Chevron
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }

        // Divider between items (inset to match icon alignment)
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 58.dp, end = 14.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

