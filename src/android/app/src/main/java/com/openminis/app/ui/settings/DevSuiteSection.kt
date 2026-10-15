package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.openminis.app.plugins.DevBundle
import com.openminis.app.plugins.DevComponent
import com.openminis.app.plugins.DevSuiteCatalog
import com.openminis.app.plugins.DevSuiteInstaller
import com.openminis.app.plugins.DevSuiteStore

/**
 * [T-dev-suite] "开发环境套件" section of the plugin market.
 *
 * Mirrors taixu's Plugin & Tools Center: bundle cards with per-component
 * checkboxes (required ones locked on), an assemble button, live progress
 * from the recipe engine, and standalone online tools below. The heavy
 * payloads never ship in the APK — recipes download them on demand.
 *
 * [T-devsuite-progress-write] The live progress line comes from
 * [DevSuiteStore.states] (`progressLine`, throttled by the store), which is
 * now the only place that fact is kept — the installer used to hold a second
 * copy that the UI read while the stored one had no reader at all.
 */
@Composable
fun DevSuiteSection(
    /**
     * [T-dev-suite-link-consent] Id handed over by a
     * `minis://devsuite/install/<id>` deep link — a bundle OR an online tool
     * (see [DevSuiteCatalog.installTargetById]; resolving only bundles made an
     * online-tool link a silent no-op). It only PRESELECTS the confirmation
     * dialog — the install still starts when the user taps 装配.
     * A chat-emitted (and therefore prompt-injectable) link used to call
     * assembleBundleAsync with every component pre-selected, silently pulling
     * gigabytes on a metered connection.
     */
    pendingInstallBundleId: String? = null,
    onPendingInstallConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val states by DevSuiteStore.states.collectAsState()
    val busy by DevSuiteInstaller.busy.collectAsState()
    var confirmBundle by remember { mutableStateOf<DevBundle?>(null) }
    var preselectEverything by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<DevComponent?>(null) }

    LaunchedEffect(pendingInstallBundleId) {
        val id = pendingInstallBundleId ?: return@LaunchedEffect
        // An online-tool link is a single component the user already chose by
        // name, so it is pre-checked; a bundle keeps the consent dialog strict
        // (required components on, heavy optional ones off).
        preselectEverything = DevSuiteCatalog.isOnlineToolId(id)
        confirmBundle = DevSuiteCatalog.installTargetById(id)
        onPendingInstallConsumed()
    }

    // Crash recovery: a component stuck in INSTALLING means the process died
    // mid-install — flip it to FAILED so the UI offers a retry.
    // [T-dev-suite-recover-scope] Only when nothing is actually running: this
    // section re-composes whenever the user leaves and returns to Settings, and
    // an unconditional pass used to mark the LIVE install FAILED.
    LaunchedEffect(busy) {
        if (!busy) DevSuiteStore.recoverInterrupted(context)
    }

    SettingsSection(
        header = stringResource(R.string.dev_suite_section_header),
        footer = stringResource(R.string.dev_suite_section_footer),
    ) {
        // [T-devsuite-offline-entry] PR24 built the offline import mechanism
        // (CLI manifest + /var/minis/shared/offline/ drop point + offline_pick
        // in every download site) but no UI ever told anyone it exists — a
        // network-restricted user saw only a FAILED row and a dead end. The
        // hint rides the failure state itself: the moment any component fails
        // is the moment the user needs the offline path, and it names both
        // halves of the contract (the CLI that prints the manifest, the
        // directory that feeds it). The static footer below keeps a one-line
        // general mention for the not-yet-failed case.
        if (states.values.any { it.status == DevSuiteStore.Status.FAILED }) {
            DevSuiteOfflineHint()
        }
        DevSuiteCatalog.bundles.forEach { bundle ->
            val readyCount = bundle.components.count {
                states[it.id]?.status == DevSuiteStore.Status.INSTALLED
            }
            val active = bundle.components.firstOrNull {
                states[it.id]?.status == DevSuiteStore.Status.INSTALLING
            }
            val failed = bundle.components.any {
                states[it.id]?.status == DevSuiteStore.Status.FAILED
            }
            SettingsRow(
                title = bundle.name,
                subtitle = buildString {
                    append(bundle.summary)
                    append(" · ")
                    append(stringResource(R.string.dev_suite_progress, readyCount, bundle.components.size))
                    if (active != null) {
                        append(" · ")
                        append(
                            states[active.id]?.progressLine?.take(40)?.ifBlank { null }
                                ?: stringResource(R.string.dev_suite_installing)
                        )
                    } else if (failed) {
                        append(" · ")
                        append(stringResource(R.string.dev_suite_failed))
                    }
                },
                onClick = {
                    preselectEverything = false
                    confirmBundle = bundle
                },
            )
        }
    }

    SettingsSection(
        header = stringResource(R.string.dev_suite_online_header),
        footer = stringResource(R.string.dev_suite_online_footer),
    ) {
        DevSuiteCatalog.onlineTools.forEach { tool ->
            val st = states[tool.id]?.status ?: DevSuiteStore.Status.NOT_INSTALLED
            SettingsRow(
                title = tool.name,
                subtitle = buildString {
                    append(tool.description)
                    append(" · ")
                    append(
                        when (st) {
                            DevSuiteStore.Status.INSTALLED -> stringResource(R.string.dev_suite_ready)
                            DevSuiteStore.Status.INSTALLING ->
                                states[tool.id]?.progressLine?.take(40)?.ifBlank { null }
                                    ?: stringResource(R.string.dev_suite_installing)
                            DevSuiteStore.Status.FAILED ->
                                states[tool.id]?.progressLine?.take(40)?.ifBlank { null }
                                    ?: stringResource(R.string.dev_suite_failed)
                            DevSuiteStore.Status.NOT_INSTALLED -> stringResource(R.string.dev_suite_not_installed)
                        }
                    )
                },
                onClick = {
                    when {
                        st == DevSuiteStore.Status.INSTALLED -> confirmRemove = tool
                        !busy -> DevSuiteInstaller.assembleBundleAsync(
                            context,
                            DevBundle(id = "online", name = tool.name, summary = "", components = listOf(tool)),
                            includeIds = setOf(tool.id),
                        )
                    }
                },
            )
        }
    }

    confirmBundle?.let { bundle ->
        DevSuiteBundleDialog(
            bundle = bundle,
            states = states,
            busy = busy,
            preselectEverything = preselectEverything,
            onDismiss = { confirmBundle = null },
            onAssemble = { selected ->
                confirmBundle = null
                DevSuiteInstaller.assembleBundleAsync(context, bundle, selected)
            },
            onRemove = { confirmRemove = it },
        )
    }

    confirmRemove?.let { component ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(component.name) },
            text = { Text(stringResource(R.string.dev_suite_remove_confirm, component.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = null
                        DevSuiteInstaller.uninstallAsync(context, component)
                    },
                    enabled = !busy,
                ) { Text(stringResource(R.string.common_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun DevSuiteBundleDialog(
    bundle: DevBundle,
    states: Map<String, DevSuiteStore.ComponentState>,
    busy: Boolean,
    preselectEverything: Boolean,
    onDismiss: () -> Unit,
    onAssemble: (Set<String>) -> Unit,
    onRemove: (DevComponent) -> Unit,
) {
    var selected by remember(bundle.id, preselectEverything) {
        mutableStateOf(
            bundle.components
                .filter {
                    it.isRequired || preselectEverything ||
                        states[it.id]?.status == DevSuiteStore.Status.INSTALLED
                }
                .map { it.id }.toSet()
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(bundle.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    bundle.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                bundle.components.forEach { c ->
                    val st = states[c.id]?.status ?: DevSuiteStore.Status.NOT_INSTALLED
                    DevComponentRow(
                        component = c,
                        status = st,
                        progressLine = states[c.id]?.progressLine.orEmpty(),
                        checked = c.id in selected,
                        canRemove = st == DevSuiteStore.Status.INSTALLED &&
                            (c.purgePaths.isNotEmpty() || c.purgeLinks.isNotEmpty()),
                        onChecked = { on ->
                            if (!c.isRequired) {
                                selected = if (on) selected + c.id else selected - c.id
                            }
                        },
                        onRemove = { onRemove(c) },
                    )
                }
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = { onAssemble(selected) },
                    enabled = !busy && selected.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.dev_suite_assemble))
                }
            }
        },
    )
}

@Composable
private fun DevComponentRow(
    component: DevComponent,
    status: DevSuiteStore.Status,
    progressLine: String,
    checked: Boolean,
    canRemove: Boolean,
    onChecked: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChecked,
            enabled = component.isRequired.not() || checked,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                component.name + if (component.isRequired) {
                    " · " + stringResource(R.string.dev_suite_required)
                } else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                // The throttled live line replaces the description while the
                // component is installing, so the card is not frozen on
                // "start install" for a multi-GB download.
                when (status) {
                    DevSuiteStore.Status.INSTALLING ->
                        progressLine.ifBlank { component.description }
                    DevSuiteStore.Status.FAILED ->
                        progressLine.ifBlank { component.description }
                    else -> component.description
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        // [T-devsuite-uninstall] Installed components used to have no way back
        // out of the app — uninstall() existed but nothing called it.
        if (canRemove) {
            TextButton(onClick = onRemove, enabled = true) {
                Text(stringResource(R.string.common_remove), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * [T-devsuite-offline-entry] Failure-state banner pointing at the offline
 * import path (see DevSuiteSection for the rationale). Plain informational
 * row — nothing to tap: the user runs the CLI in a terminal and drops the
 * files into the shared folder from any file manager, then retries assembly
 * (offline_pick makes the retry use the local copies with zero network).
 * Padding mirrors SettingsRow (14dp horizontal inside the section card) so
 * the banner reads as a row of the card it sits in.
 */
@Composable
private fun DevSuiteOfflineHint() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column {
            Text(
                text = stringResource(R.string.dev_suite_offline_hint_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.dev_suite_offline_hint_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
