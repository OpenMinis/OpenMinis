package com.openminis.app.ui.settings

import android.widget.Toast
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.accessibility.AccessibilityRecoveryManager
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.accessibility.RestrictedSettingsManager
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.power.PowerOptimizationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * T323: surfaces OS-level permission states (Accessibility service,
 * etc.) the user must grant via external Settings flows. Each row
 * reports current status and routes the user to the relevant system
 * settings page; we re-poll while the screen is visible so coming
 * back from system Settings refreshes the row automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var a11yEnabled by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    // [T-android-a11y-miui-service-failure] "Service failed" degraded state:
    // the OEM ROM (MIUI etc.) reports the service as enabled in Settings but
    // has killed the process / revoked the binding, so getInstance() is null.
    // On such a device the canonical remediation is autostart-whitelist +
    // battery "no restrictions"; surface those (reusing PowerOptimizationManager)
    // only when the service is degraded AND the vendor is known to enforce it.
    var a11yDegraded by remember { mutableStateOf(false) }
    // [T-android-a11y-force-stop-recovery] Distinct from `a11yDegraded`: the
    // grant itself is gone from Settings.Secure (force-stop stripped it), which
    // is repairable by writing the setting back. `a11yDegraded` is the opposite
    // case — grant present, process killed — where writing would be pointless.
    var a11yRevoked by remember { mutableStateOf(false) }
    var shizukuReady by remember { mutableStateOf(false) }
    var repairing by remember { mutableStateOf(false) }
    var repairFailed by remember { mutableStateOf(false) }
    // [T-android-restricted-settings] Android 13+ flags installs whose
    // installer declared a local/downloaded-file package source and refuses to
    // arm the accessibility toggle until the user allows restricted settings.
    // Only meaningful while the service is still off — once it is enabled the
    // user has clearly already gotten past the gate.
    var a11yRestricted by remember { mutableStateOf(false) }
    var unrestricting by remember { mutableStateOf(false) }
    var unrestrictFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            val inSettings = isAccessibilityEnabled(context)
            val connected = MinisAccessibilityService.getInstance() != null
            a11yEnabled = inSettings || connected
            a11yDegraded = inSettings && !connected
            // Only claim "revoked" once the user has actually granted it at
            // least once — otherwise a first-run user who has never enabled the
            // service would be shown a "repair" prompt for something that was
            // never broken.
            a11yRevoked = !inSettings && !connected &&
                AccessibilityRecoveryManager.hasEverBeenGranted(context)
            shizukuReady = ShizukuManager.isReady()
            // Re-probed each tick so the section disappears by itself the
            // moment the user allows restricted settings in system Settings
            // and comes back.
            a11yRestricted = !a11yEnabled && RestrictedSettingsManager.isRestricted(context)
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.system_permissions_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(
                header = stringResource(R.string.system_permissions_section_a11y),
                footer = stringResource(R.string.system_permissions_a11y_footer),
            ) {
                SettingsRow(
                    icon = Icons.Outlined.Accessibility,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.system_permissions_a11y_row),
                    subtitle = if (a11yEnabled)
                        stringResource(R.string.system_permissions_a11y_enabled)
                    else
                        stringResource(R.string.system_permissions_a11y_disabled),
                    onClick = { openAccessibilitySettings(context) },
                    showDivider = false,
                )
            }

            // [T-android-restricted-settings] Shown ONLY when the OS has
            // actually flagged this install (appop ACCESS_RESTRICTED_SETTINGS
            // == MODE_ERRORED) and the service is still off — i.e. exactly the
            // state where tapping the row above leads to a dead, greyed-out
            // toggle. Without this the user sees "tap to enable in system
            // Settings", follows it, and hits a wall with no explanation.
            //
            // We cannot clear the flag ourselves: it is set on our own uid and
            // clearing needs the signature permission MANAGE_APP_OPS_MODES.
            // That is deliberate — the policy exists to stop a sideloaded app
            // from talking the user into granting it screen-reading power — so
            // the manual route is the primary affordance and stays visible even
            // when the Shizuku shortcut is offered.
            if (a11yRestricted) {
                SettingsSection(
                    header = stringResource(R.string.system_permissions_a11y_restricted_header),
                    footer = stringResource(R.string.system_permissions_a11y_restricted_footer),
                ) {
                    if (shizukuReady) {
                        SettingsRow(
                            icon = Icons.Outlined.LockOpen,
                            iconColor = Color(0xFF34C759),
                            title = stringResource(R.string.system_permissions_a11y_restricted_shizuku),
                            subtitle = when {
                                unrestricting ->
                                    stringResource(R.string.system_permissions_a11y_restricted_working)
                                unrestrictFailed ->
                                    stringResource(R.string.system_permissions_a11y_restricted_failed)
                                else ->
                                    stringResource(R.string.system_permissions_a11y_restricted_shizuku_sub)
                            },
                            onClick = {
                                if (unrestricting) return@SettingsRow
                                unrestricting = true
                                unrestrictFailed = false
                                scope.launch {
                                    val ok = RestrictedSettingsManager.clearWithShizuku(context)
                                    unrestricting = false
                                    unrestrictFailed = !ok
                                    // On success the poll above clears
                                    // a11yRestricted and this section vanishes.
                                }
                            },
                        )
                    }
                    SettingsRow(
                        icon = Icons.Outlined.Info,
                        iconColor = Color(0xFFFF9500),
                        title = stringResource(R.string.system_permissions_a11y_restricted_manual),
                        subtitle = stringResource(R.string.system_permissions_a11y_restricted_manual_sub),
                        // Lands on Minis' own App info page, where the
                        // "Allow restricted settings" item lives in the
                        // overflow menu. Reuses the existing helper, which
                        // already falls back when an OEM hides the page.
                        onClick = {
                            (context as? Activity)?.let {
                                PowerOptimizationManager.openAppDetailsSettings(it)
                            }
                        },
                        showDivider = false,
                    )
                }
            }

            // [T-android-a11y-force-stop-recovery] Repair affordance, shown ONLY
            // when the grant was previously held and is now missing from
            // Settings.Secure — the force-stop signature. Hidden in the healthy
            // case and on a fresh install (see hasEverBeenGranted) so the screen
            // stays clean.
            if (a11yRevoked) {
                SettingsSection(
                    header = stringResource(R.string.a11y_repair_section_header),
                    footer = stringResource(
                        if (shizukuReady) R.string.a11y_repair_footer_shizuku
                        else R.string.a11y_repair_footer_manual
                    ),
                ) {
                    SettingsRow(
                        icon = Icons.Outlined.Build,
                        iconColor = Color(0xFFFF3B30),
                        title = stringResource(
                            if (shizukuReady) R.string.a11y_repair_row_shizuku
                            else R.string.a11y_repair_row_manual
                        ),
                        subtitle = when {
                            repairing -> stringResource(R.string.a11y_repair_in_progress)
                            repairFailed -> stringResource(R.string.a11y_repair_failed)
                            else -> stringResource(R.string.a11y_repair_row_sub)
                        },
                        onClick = {
                            if (!shizukuReady) {
                                openAccessibilitySettings(context)
                                return@SettingsRow
                            }
                            if (repairing) return@SettingsRow
                            repairing = true
                            repairFailed = false
                            scope.launch {
                                val ok = AccessibilityRecoveryManager
                                    .repairWithShizuku(context)
                                repairing = false
                                repairFailed = !ok
                                // On success the polling loop above clears
                                // a11yRevoked within a second and this whole
                                // section disappears on its own.
                            }
                        },
                        showDivider = false,
                    )
                }
            }

            // [T-android-a11y-miui-service-failure] OEM keep-alive guidance. Only
            // shown when the service is degraded ("此服务出现故障" — enabled in
            // Settings but the process was killed by the ROM) AND the vendor is
            // one known to enforce autostart on top of stock Android. Routes the
            // two canonical remediations through the existing
            // PowerOptimizationManager (which already catches a missing Activity
            // and falls back). Stays hidden on Pixel / stock Android (Vendor.OTHER).
            val activity = context as? Activity
            if (a11yDegraded && activity != null && PowerOptimizationManager.needsOemAutostartGuidance()) {
                val vendor = PowerOptimizationManager.Vendor.current().displayName
                SettingsSection(
                    header = stringResource(R.string.system_permissions_a11y_oem_header),
                    footer = stringResource(R.string.system_permissions_a11y_oem_footer, vendor),
                ) {
                    SettingsRow(
                        icon = Icons.Outlined.RestartAlt,
                        iconColor = Color(0xFFFF9500),
                        title = stringResource(R.string.system_permissions_a11y_oem_autostart),
                        subtitle = stringResource(R.string.system_permissions_a11y_oem_autostart_sub),
                        onClick = {
                            if (!PowerOptimizationManager.openOemAutostartSettings(activity)) {
                                PowerOptimizationManager.openAppDetailsSettings(activity)
                            }
                        },
                    )
                    SettingsRow(
                        icon = Icons.Outlined.BatteryAlert,
                        iconColor = Color(0xFFFF9500),
                        title = stringResource(R.string.system_permissions_a11y_oem_battery),
                        subtitle = stringResource(R.string.system_permissions_a11y_oem_battery_sub),
                        onClick = {
                            if (!PowerOptimizationManager.requestBatteryOptimizationExemption(activity)) {
                                PowerOptimizationManager.openAppDetailsSettings(activity)
                            }
                        },
                        showDivider = false,
                    )
                }
            }

            // [T-android-voice-correction] Phase 4: opt-in consent for storing
            // correction learning data, plus the wipe action. Placed here
            // because it is a privacy control, matching iOS's Permissions page.
            var correctionEnabled by remember {
                mutableStateOf(
                    com.openminis.app.speech.correction.VoiceCorrectionConsent.isEnabled(context),
                )
            }
            var showClearCorrectionConfirm by remember { mutableStateOf(false) }

            SettingsSection(
                header = stringResource(R.string.voice_correction_section),
                footer = stringResource(R.string.voice_correction_footer),
            ) {
                SettingsSwitchRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = stringResource(R.string.voice_correction_toggle),
                    checked = correctionEnabled,
                    onCheckedChange = { on ->
                        correctionEnabled = on
                        com.openminis.app.speech.correction.VoiceCorrectionConsent
                            .setEnabled(context, on)
                        // Turning the toggle off counts as an answer, so the
                        // one-time prompt must not reappear afterwards.
                        com.openminis.app.speech.correction.VoiceCorrectionConsent
                            .setPrompted(context, true)
                    },
                )
                SettingsRow(
                    icon = Icons.Outlined.DeleteSweep,
                    iconColor = MaterialTheme.colorScheme.error,
                    title = stringResource(R.string.voice_correction_clear),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { showClearCorrectionConfirm = true },
                    showDivider = false,
                )
            }

            if (showClearCorrectionConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearCorrectionConfirm = false },
                    title = { Text(stringResource(R.string.voice_correction_clear_title)) },
                    confirmButton = {
                        MinisTextButton(onClick = {
                            showClearCorrectionConfirm = false
                            com.openminis.app.speech.correction.VoiceCorrection
                                .clearAllData(context)
                            Toast.makeText(
                                context,
                                context.getString(R.string.voice_correction_cleared),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }) {
                            Text(
                                stringResource(R.string.voice_correction_clear),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    dismissButton = {
                        MinisTextButton(onClick = { showClearCorrectionConfirm = false }) {
                            Text(stringResource(R.string.voice_correction_consent_not_now))
                        }
                    },
                )
            }
        }
    }
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${MinisAccessibilityService::class.java.name}"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun openAccessibilitySettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    } catch (_: Throwable) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Throwable) {}
    }
}
