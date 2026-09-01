package com.openminis.app.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.power.PowerOptimizationManager
import com.openminis.app.i18n.uppercaseForDisplay

/**
 * T50 settings screen — surfaces the two pieces of background-keep-alive
 * state the user can fix from the OS but the app cannot:
 *
 *   1. Battery-optimisation exemption (`isIgnoringBatteryOptimizations`).
 *   2. OEM-specific autostart permission (Xiaomi/Huawei/OPPO/Vivo/Samsung).
 *
 * Both rows just deep-link to the right system settings page; the app
 * has no privileged way to flip these directly. The state for (1) is
 * polled on resume — when the user comes back from the system settings
 * dialog, the row updates to "already allowed".
 *
 * Renders without depending on the file-private SettingsSection /
 * SettingsItem helpers in [SettingsScreen]; those don't need to grow
 * a public surface for one consumer. Visual feel is intentionally
 * close to the existing settings rows but the components are local.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity

    // T180-bg-notif: pull the BackgroundSettingsRepository off the
    // Application instance — keeps AppNavigation parameters unchanged
    // (no plumbing churn through the nav graph). MinisApp.onCreate is
    // guaranteed to have run before any composable composes.
    val app = context.applicationContext as MinisApp
    val backgroundRepo = app.backgroundSettingsRepository
    val taskNotificationsEnabled by backgroundRepo.taskNotificationsEnabled.collectAsState()
    val backgroundOverlayEnabled by backgroundRepo.backgroundOverlayEnabled.collectAsState()
    // [T-android-dynamic-island] Live-Updates toggle + device capability.
    val dynamicIslandEnabled by backgroundRepo.dynamicIslandEnabled.collectAsState()

    var ignoringOptimizations by remember {
        mutableStateOf(PowerOptimizationManager.isIgnoringBatteryOptimizations(context))
    }
    var canDrawOverlays by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(context),
        )
    }
    // [T-android-dynamic-island] Re-probed on ON_RESUME (spec §3) so a
    // Live-Updates grant the user toggled in system settings is picked up
    // without an app restart.
    var dynamicIslandCapable by remember {
        mutableStateOf(
            com.openminis.app.service.DynamicIslandSupport.isDynamicIslandCapable(context),
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringOptimizations =
                    PowerOptimizationManager.isIgnoringBatteryOptimizations(context)
                canDrawOverlays =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                        Settings.canDrawOverlays(context)
                dynamicIslandCapable =
                    com.openminis.app.service.DynamicIslandSupport.isDynamicIslandCapable(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    val vendor = remember { PowerOptimizationManager.Vendor.current() }
    val needsOemGuidance = remember { PowerOptimizationManager.needsOemAutostartGuidance() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bg_section_header)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.size(8.dp))
            // T180-bg-notif: Task Notifications toggle. Mirrors iOS
            // EnhancedBackgroundSettingsView's first section (the toggle
            // ships ON to match iOS default — see
            // BackgroundSettingsRepository.DEFAULT_TASK_NOTIFICATIONS).
            BgSectionTitle(stringResource(R.string.settings_section_notifications))
            BgToggleRow(
                icon = Icons.Outlined.NotificationsActive,
                iconColor = Color(0xFF007AFF),
                title = stringResource(R.string.settings_task_notifications),
                checked = taskNotificationsEnabled,
                onCheckedChange = { backgroundRepo.setTaskNotificationsEnabled(it) },
            )
            BgFooter(stringResource(R.string.settings_task_notifications_footer))

            // T-bg-overlay phase 2: floating tool-status overlay toggle.
            // Tapping ON without SYSTEM_ALERT_WINDOW deep-links the user to
            // the system "Display over other apps" screen; canDrawOverlays
            // is re-polled on ON_RESUME so flipping the system grant
            // immediately makes the UI reflect ready-to-use state.
            Spacer(Modifier.size(8.dp))
            BgToggleRow(
                icon = Icons.Outlined.Layers,
                iconColor = Color(0xFF5856D6),
                title = stringResource(R.string.settings_bg_overlay),
                // [T-android-overlay-toggle-reflects-intent] Reflect the user's
                // PERSISTED intent, not the derived ready-state. Previously this
                // was `backgroundOverlayEnabled && canDrawOverlays`, so a user
                // who tapped ON but never granted SYSTEM_ALERT_WINDOW (or later
                // had it revoked) came back to a switch that read OFF — they
                // concluded the toggle "won't stay on" and never connected it to
                // the missing permission, so the overlay silently never showed.
                // Showing ON here surfaces the real state: the switch is on, and
                // the footer below says a permission is still needed, which is
                // the actionable signal. canDrawOverlays still gates whether the
                // overlay actually renders (AgentForegroundService checks the
                // grant independently and posts a permission nudge).
                checked = backgroundOverlayEnabled,
                onCheckedChange = { wanted ->
                    if (wanted && !canDrawOverlays) {
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + context.packageName),
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            context.startActivity(intent)
                        } catch (_: Throwable) {
                            // Fallback: open generic overlay settings screen.
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            try { context.startActivity(intent) } catch (_: Throwable) {}
                        }
                        // Persist the user's intent so when they return
                        // with the grant in place, the overlay turns on
                        // immediately (canDrawOverlays re-polls on
                        // ON_RESUME — see LaunchedEffect above).
                        backgroundRepo.setBackgroundOverlayEnabled(true)
                    } else {
                        backgroundRepo.setBackgroundOverlayEnabled(wanted)
                    }
                },
            )
            BgFooter(
                if (!canDrawOverlays && backgroundOverlayEnabled) {
                    stringResource(R.string.settings_bg_overlay_permission_needed)
                } else {
                    stringResource(R.string.settings_bg_overlay_footer)
                },
            )

            // [T-android-dynamic-island] Live Updates / "dynamic island" toggle.
            // Only interactive when the device is capable (Android 16+ with the
            // per-app Live-Updates grant). When ON it REPLACES the floating
            // overlay — the mutual-exclusion guard lives in
            // AgentForegroundService.applyOverlayState. Disabled + explained on
            // devices/versions that don't support it.
            Spacer(Modifier.size(8.dp))
            BgToggleRow(
                icon = Icons.Outlined.Bolt,
                iconColor = Color(0xFF34C759),
                title = stringResource(R.string.settings_dynamic_island),
                checked = dynamicIslandEnabled && dynamicIslandCapable,
                enabled = dynamicIslandCapable,
                onCheckedChange = { backgroundRepo.setDynamicIslandEnabled(it) },
            )
            BgFooter(
                if (!dynamicIslandCapable) {
                    stringResource(R.string.settings_dynamic_island_unsupported)
                } else {
                    stringResource(R.string.settings_dynamic_island_footer)
                },
            )

            Spacer(Modifier.size(16.dp))
            BgSectionTitle(stringResource(R.string.battery_opt_section_title))
            BgRow(
                icon = Icons.Outlined.BatteryFull,
                iconColor = if (ignoringOptimizations) Color(0xFF34C759) else Color(0xFFFF9500),
                title = stringResource(R.string.battery_opt_row_title),
                subtitle = if (ignoringOptimizations) {
                    stringResource(R.string.battery_opt_already_exempt)
                } else {
                    stringResource(R.string.battery_opt_request_subtitle)
                },
                onClick = {
                    if (!ignoringOptimizations && activity != null) {
                        PowerOptimizationManager.requestBatteryOptimizationExemption(activity)
                    }
                },
            )
            BgFooter(stringResource(R.string.battery_opt_section_footer))

            if (needsOemGuidance) {
                Spacer(Modifier.size(16.dp))
                BgSectionTitle(stringResource(R.string.rom_autostart_section_title))
                BgRow(
                    icon = Icons.Outlined.PhoneAndroid,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.rom_autostart_row_title),
                    subtitle = stringResource(
                        R.string.rom_autostart_row_subtitle,
                        vendor.displayName,
                    ),
                    onClick = {
                        if (activity != null) {
                            val ok = PowerOptimizationManager.openOemAutostartSettings(activity)
                            if (!ok) PowerOptimizationManager.openAppDetailsSettings(activity)
                        }
                    },
                )
                BgFooter(
                    stringResource(R.string.rom_autostart_section_footer, vendor.displayName),
                )
            }

            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun BgSectionTitle(text: String) {
    Text(
        text = text.uppercaseForDisplay(),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun BgFooter(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
    )
}

@Composable
private fun BgToggleRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    // [T-android-dynamic-island] When false the row is greyed out and both the
    // whole-row tap and the Switch are inert (used for the dynamic-island
    // toggle on devices that don't support Live Updates).
    enabled: Boolean = true,
) {
    val rowAlpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(iconColor.copy(alpha = 0.15f * rowAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor.copy(alpha = rowAlpha),
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = rowAlpha),
            modifier = Modifier.weight(1f),
        )
        SettingsSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(),
        )
    }
}

@Composable
private fun BgRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(iconColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.padding(end = 6.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
