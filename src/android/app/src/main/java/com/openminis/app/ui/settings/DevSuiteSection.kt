package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
 */
@Composable
fun DevSuiteSection() {
    val context = LocalContext.current
    val states by DevSuiteStore.states.collectAsState()
    val busy by DevSuiteInstaller.busy.collectAsState()
    val current by DevSuiteInstaller.current.collectAsState()
    var confirmBundle by remember { mutableStateOf<DevBundle?>(null) }

    // Crash recovery: a component stuck in INSTALLING means the process died
    // mid-install — flip it to FAILED so the UI offers a retry.
    LaunchedEffect(Unit) { DevSuiteStore.recoverInterrupted(context) }

    SettingsSection(
        header = stringResource(R.string.dev_suite_section_header),
        footer = stringResource(R.string.dev_suite_section_footer),
    ) {
        DevSuiteCatalog.bundles.forEach { bundle ->
            val readyCount = bundle.components.count {
                states[it.id]?.status == DevSuiteStore.Status.INSTALLED
            }
            val installing = bundle.components.any {
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
                    if (installing) {
                        append(" · ")
                        append(current?.second?.take(40)?.ifBlank { stringResource(R.string.dev_suite_installing) }
                            ?: stringResource(R.string.dev_suite_installing))
                    } else if (failed) {
                        append(" · ")
                        append(stringResource(R.string.dev_suite_failed))
                    }
                },
                onClick = { confirmBundle = bundle },
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
                            DevSuiteStore.Status.INSTALLING -> stringResource(R.string.dev_suite_installing)
                            DevSuiteStore.Status.FAILED -> stringResource(R.string.dev_suite_failed)
                            DevSuiteStore.Status.NOT_INSTALLED -> stringResource(R.string.dev_suite_not_installed)
                        }
                    )
                },
                onClick = {
                    if (!busy) {
                        DevSuiteInstaller.assembleBundleAsync(
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
            onDismiss = { confirmBundle = null },
            onAssemble = { selected ->
                confirmBundle = null
                DevSuiteInstaller.assembleBundleAsync(context, bundle, selected)
            },
        )
    }
}

@Composable
private fun DevSuiteBundleDialog(
    bundle: DevBundle,
    states: Map<String, DevSuiteStore.ComponentState>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAssemble: (Set<String>) -> Unit,
) {
    var selected by remember(bundle.id) {
        mutableStateOf(
            bundle.components.filter { it.isRequired || states[it.id]?.status == DevSuiteStore.Status.INSTALLED }
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
                        checked = c.id in selected,
                        onChecked = { on ->
                            if (!c.isRequired) {
                                selected = if (on) selected + c.id else selected - c.id
                            }
                        },
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
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
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
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                component.name + if (component.isRequired) {
                    " · " + stringResource(R.string.dev_suite_required)
                } else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                component.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}
