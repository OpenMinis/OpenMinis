package com.openminis.app.ui.settings

import com.openminis.app.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

/**
 * [T-usage-cache-health] One provider's cache picture inside the selected
 * window.
 *
 * The per-model hit rate already lives in the row's expanded details; this
 * aggregate exists because "which VENDOR actually caches" is a purchasing
 * question, not a per-model one — a relay that never reports `cached_tokens`
 * makes every one of its models look uncached while the user pays full price
 * for the whole prompt.
 *
 * [hitRate] is null when there is nothing to divide (no input, or no cached
 * tokens at all). [looksUnreported] marks the specific case worth surfacing:
 * real input exists but the vendor reported zero cached tokens for the whole
 * window — either the provider does not support prompt caching or it does not
 * forward the usage field, and in both cases the hit rate column is a lie the
 * user should not trust.
 */
internal data class ProviderCacheRow(
    val label: String,
    val hitRate: Double?,
    val cacheReadTokens: Long,
    val totalInput: Long,
) {
    val looksUnreported: Boolean get() = totalInput > 0 && cacheReadTokens == 0L
}

/**
 * Groups [rows] by provider identity (the row subtitle: instance label, or the
 * provider type when the row predates instances) and folds their cache numbers.
 * Sorted by input volume so the provider the user actually spends on is first.
 */
internal fun aggregateCacheByProvider(rows: List<UsageRow>): List<ProviderCacheRow> =
    rows.groupBy { it.stats.subtitle }
        .map { (label, group) ->
            val cacheRead = group.sumOf { it.stats.cacheReadTokens }
            val totalInput = group.sumOf { it.stats.totalInput }
            ProviderCacheRow(
                label = label,
                hitRate = if (totalInput > 0 && cacheRead > 0) {
                    cacheRead.toDouble() / totalInput * 100
                } else {
                    null
                },
                cacheReadTokens = cacheRead,
                totalInput = totalInput,
            )
        }
        .sortedByDescending { it.totalInput }

/**
 * [T-usage-cache-health] Cache health: the money card.
 *
 * Everything here answers "am I actually paying cache prices?". The hit rate
 * is one number, but the per-provider rows are the diagnosis — a relay that
 * never reports cached tokens shows up as `looksUnreported` here instead of
 * silently costing full price. The enhanced-cache switch is offered inline
 * because it is a 1-hour TTL that only official Anthropic honors; relays hide
 * it in the chat menu and most users never find it.
 */
@Composable
internal fun CacheHealthCard(
    grandTotal: GrandTotal,
    providerRows: List<ProviderCacheRow>,
    hasAnthropicUsage: Boolean,
) {
    val context = LocalContext.current
    val enhancedOn by com.openminis.app.data.EnhancedCachePrefs.enabled.collectAsState()
    var showEnhancedConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text(
            stringResource(R.string.usage_cache_health_rate),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(5.dp))
        val rate = grandTotal.cacheHitRate
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                rate?.let { String.format("%.1f", it) } ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (rate != null) {
                Text(
                    "%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                )
            }
        }

        if (grandTotal.cacheReadTokens > 0 || grandTotal.cacheCreationTokens > 0) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (grandTotal.cacheReadTokens > 0) {
                    UsagePill(
                        label = stringResource(R.string.usage_label_cache_read),
                        value = formatCount(grandTotal.cacheReadTokens),
                    )
                }
                if (grandTotal.cacheCreationTokens > 0) {
                    UsagePill(
                        label = stringResource(R.string.usage_label_cache_creation),
                        value = formatCount(grandTotal.cacheCreationTokens),
                    )
                }
            }
        }

        // Per-provider rows only when there is more than one vendor in the
        // window: with a single vendor this row would just repeat the number
        // above, and the per-model details already carry the breakdown.
        if (providerRows.size > 1) {
            Spacer(Modifier.height(14.dp))
            providerRows.take(5).forEach { provider ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        provider.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (provider.looksUnreported) {
                        Text(
                            stringResource(R.string.usage_cache_health_unreported),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    } else {
                        Text(
                            provider.hitRate?.let { String.format("%.1f%%", it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        if (hasAnthropicUsage) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.usage_cache_health_enhanced_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.usage_cache_health_enhanced_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enhancedOn,
                    onCheckedChange = { want ->
                        if (want && !com.openminis.app.data.EnhancedCachePrefs.isConfirmed(context)) {
                            showEnhancedConfirm = true
                        } else {
                            com.openminis.app.data.EnhancedCachePrefs.setEnabled(context, want)
                        }
                    },
                )
            }
        }
    }

    if (showEnhancedConfirm) {
        AlertDialog(
            onDismissRequest = { showEnhancedConfirm = false },
            title = { Text(stringResource(R.string.chat_menu_enhanced_cache)) },
            text = { Text(stringResource(R.string.enhanced_cache_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        com.openminis.app.data.EnhancedCachePrefs.setConfirmed(context)
                        com.openminis.app.data.EnhancedCachePrefs.setEnabled(context, true)
                        showEnhancedConfirm = false
                    },
                ) {
                    Text(stringResource(R.string.enhanced_cache_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEnhancedConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
