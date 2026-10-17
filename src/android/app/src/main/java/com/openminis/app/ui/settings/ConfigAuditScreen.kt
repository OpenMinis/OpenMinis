package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.config.audit.ConfigAuditActor
import com.openminis.app.config.audit.ConfigAuditEntry
import com.openminis.app.config.audit.ConfigAuditLog
import com.openminis.app.config.audit.ConfigAuditStatus
import com.openminis.app.config.ConfigBridge
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Logs → Config Changes tab. Lists every config write attempt
 * recorded in [ConfigAuditLog]. Tap an applied entry to revert it.
 * Mirrors iOS `ConfigAuditView`.
 *
 * Reverts go through a local [AlertDialog] (not the gate's confirm
 * dialog) — the gate's dialog can't stack over the Logs sheet on
 * iOS, and the Android version follows the same shape so the audit
 * log is internally consistent across platforms.
 */
@Composable
fun ConfigAuditScreen(modifier: Modifier = Modifier) {
    val log = remember { ConfigAuditLog.get() }
    val revision by log.revision.collectAsState()
    var entries by remember { mutableStateOf<List<ConfigAuditEntry>>(emptyList()) }
    var usage by remember { mutableStateOf(ConfigAuditLog.Usage(0, 1000)) }
    var revertCandidate by remember { mutableStateOf<ConfigAuditEntry?>(null) }
    var revertResult by remember { mutableStateOf<RevertMessage?>(null) }

    // Reload on every audit log mutation.
    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            val recent = log.recent(limit = 200)
            val u = log.usage()
            entries = recent
            usage = u
        }
    }

    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.logs_config_used_count, usage.count, usage.capacity),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // T292: "Clear All" button removed per product decision — config
            // change history is meant to be a permanent audit trail. The
            // ConfigAuditLog.clearAll() API is intentionally left in place
            // for future use (e.g. an admin-only nuke command); only the UI
            // affordance is gone.
        }

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.logs_config_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    AuditRow(
                        entry = entry,
                        onRevert = { revertCandidate = entry },
                    )
                }
            }
        }
    }

    // Confirm-revert dialog.
    revertCandidate?.let { entry ->
        AlertDialog(
            onDismissRequest = { revertCandidate = null },
            title = { Text(stringResource(R.string.logs_config_revert_dialog_title)) },
            text = {
                Column {
                    Text(
                        text = entry.key,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${displayJSON(entry.newValueJSON)} → ${displayJSON(entry.oldValueJSON)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                val successTitle = stringResource(R.string.logs_config_revert_success_title)
                val successBody = stringResource(R.string.logs_config_revert_success_body)
                val failedTitle = stringResource(R.string.logs_config_revert_failed_title)
                val unknownErr = stringResource(R.string.logs_config_revert_unknown_error)
                MinisTextButton(onClick = {
                    val target = entry
                    revertCandidate = null
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            ConfigBridge.auditRevertSuspending(
                                id = target.id,
                                actorRaw = "user-revert",
                                sessionId = null,
                                skipConfirmation = true,
                            )
                        }
                        revertResult = if (res.optBoolean("ok", false)) {
                            RevertMessage(successTitle, successBody)
                        } else {
                            RevertMessage(failedTitle, res.optString("reason", unknownErr))
                        }
                    }
                }) {
                    Text(stringResource(R.string.logs_config_revert), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { revertCandidate = null }) {
                    Text(stringResource(R.string.logs_config_dialog_cancel))
                }
            },
        )
    }

    // Result alert.
    revertResult?.let { msg ->
        AlertDialog(
            onDismissRequest = { revertResult = null },
            title = { Text(msg.title) },
            text = { Text(msg.body) },
            confirmButton = {
                MinisTextButton(onClick = { revertResult = null }) {
                    Text(stringResource(R.string.logs_config_dialog_ok))
                }
            },
        )
    }
}

private data class RevertMessage(val title: String, val body: String)

@Composable
private fun AuditRow(entry: ConfigAuditEntry, onRevert: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(entry.status)
            Spacer(Modifier.width(6.dp))
            Text(
                text = actorLabel(entry.actor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Text(
                text = coarseRelativeTime(ctx, entry.at),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = entry.key,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = displayJSON(entry.oldValueJSON),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = displayJSON(entry.newValueJSON),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (entry.status == ConfigAuditStatus.APPLIED) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
        }
        if (!entry.caption.isNullOrEmpty()) {
            Text(
                text = "📝 ${entry.caption}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (entry.status) {
            ConfigAuditStatus.APPLIED -> {
                AssistChip(
                    onClick = onRevert,
                    label = { Text(stringResource(R.string.logs_config_revert_chip)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
            ConfigAuditStatus.REVERTED -> {
                Text(
                    text = stringResource(R.string.logs_config_reverted_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8E5CD9),
                )
            }
            else -> Unit
        }
    }
}

/**
 * T288: localized label for [ConfigAuditActor]. The enum's `raw` value is
 * the persisted English token ("agent" / "user-revert"); this composable
 * resolves it to the user's locale at render time without changing the
 * underlying audit-log payload.
 */
@Composable
private fun actorLabel(actor: ConfigAuditActor): String = when (actor) {
    ConfigAuditActor.AGENT -> stringResource(R.string.logs_config_source_agent)
    ConfigAuditActor.USER -> stringResource(R.string.logs_config_source_user)
    ConfigAuditActor.AGENT_REVERT -> stringResource(R.string.logs_config_source_agent_revert)
    ConfigAuditActor.USER_REVERT -> stringResource(R.string.logs_config_source_user_revert)
}

@Composable
private fun StatusBadge(status: ConfigAuditStatus) {
    val color = when (status) {
        ConfigAuditStatus.APPLIED -> Color(0xFF388E3C)
        ConfigAuditStatus.REJECTED -> Color(0xFF757575)
        ConfigAuditStatus.TIMEOUT -> Color(0xFFE65100)
        ConfigAuditStatus.REVERTED -> Color(0xFF8E5CD9)
    }
    val label = when (status) {
        ConfigAuditStatus.APPLIED -> stringResource(R.string.logs_config_status_applied)
        ConfigAuditStatus.REJECTED -> stringResource(R.string.logs_config_status_rejected)
        ConfigAuditStatus.TIMEOUT -> stringResource(R.string.logs_config_status_timeout)
        ConfigAuditStatus.REVERTED -> stringResource(R.string.logs_config_status_reverted)
    }
    Box(
        modifier = Modifier
            .background(color, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            ),
            color = Color.White,
        )
    }
}

/**
 * Coarse, static relative time. Computed once per reload; no Timer
 * needed since the audit log's [ConfigAuditLog.revision] re-emits on
 * every mutation. Mirrors iOS `coarseRelativeTime`.
 */
private fun coarseRelativeTime(ctx: android.content.Context, epochMs: Long): String {
    val now = System.currentTimeMillis()
    val secs = ((now - epochMs) / 1000L).coerceAtLeast(0)
    return when {
        secs < 60 -> ctx.getString(R.string.logs_config_time_just_now)
        secs < 3600 -> ctx.getString(R.string.logs_config_time_min_ago, (secs / 60).toInt())
        secs < 86_400 -> ctx.getString(R.string.logs_config_time_hr_ago, (secs / 3600).toInt())
        else -> {
            // Yesterday/earlier — render an absolute short date.
            val sameDay = (now - epochMs) < 2 * 86_400_000L
            if (sameDay) ctx.getString(R.string.logs_config_time_yesterday)
            else java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(epochMs))
        }
    }
}

private fun displayJSON(json: String): String {
    if (json.isEmpty()) return "—"
    if (json.length >= 2 && json.startsWith('"') && json.endsWith('"')) {
        return json.substring(1, json.length - 1)
    }
    return json
}
