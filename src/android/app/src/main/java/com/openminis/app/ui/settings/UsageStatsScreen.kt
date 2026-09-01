package com.openminis.app.ui.settings

import com.openminis.app.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderType
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-token-attribution-snapshot] How trustworthy a row's model attribution is.
 *
 * These must stay visually distinct. Lumping them together is what made the
 * original bug invisible: an estimated row looked exactly like a measured one,
 * so nobody could tell that a session's history had been re-attributed to
 * whichever model it currently pointed at.
 */
internal enum class Attribution {
    /** A. Per-message snapshot — the model that actually served the request. */
    MEASURED,

    /**
     * B. Written before the snapshot columns existed. Attributed to the
     * session's CURRENT model, which may be wrong if the model was ever
     * switched. Shown, but labelled as an estimate.
     */
    ESTIMATED,

    /** C. Orphaned row: no snapshot AND no session to guess from. */
    UNKNOWN_SESSION,

    /**
     * D. Snapshot present, but the model is no longer in the live config
     * (provider deleted / model removed). Renders normally from the snapshot —
     * this is the case that used to collapse into "Unknown".
     */
    MEASURED_REMOVED,
}

private data class ModelStats(
    val modelId: String,
    val displayName: String,
    val provider: String,
    val attribution: Attribution,
    var inputTokens: Long = 0,
    var outputTokens: Long = 0,
    var cacheCreationTokens: Long = 0,
    var cacheReadTokens: Long = 0,
    val distinctDays: MutableSet<String> = mutableSetOf(),
    val distinctSessions: MutableSet<String> = mutableSetOf(),
) {
    val totalInput: Long get() = inputTokens + cacheReadTokens + cacheCreationTokens
}

private data class ProviderGroup(val name: String, val models: List<ModelStats>)

private data class GrandTotal(
    val totalInput: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
) {
    val cacheHitRate: Double?
        get() = if (totalInput <= 0 || cacheReadTokens <= 0) null
        else (cacheReadTokens.toDouble() / totalInput) * 100
}

/**
 * [T-android-usage-orphan-rows] Bucket for usage rows whose session row is
 * missing, so their (really billed) tokens still appear in the totals. See
 * ChatDao.allUsageRecords.
 */
private const val UNKNOWN_MODEL_KEY = "(unknown model)"

private const val UNKNOWN_PROVIDER = "Unknown"

/**
 * [T-token-attribution-snapshot] Map a stored `ProviderType` rawValue back to
 * its display name.
 *
 * Snapshots store the rawValue (`openAI`) rather than the display name
 * (`OpenAI`) precisely so grouping is stable: display names are localized and
 * historically inconsistent — iOS ended up with `Google`, `Gemini` and
 * `Google Gemini` as three separate sections for one provider. An unrecognised
 * rawValue (a provider type removed in a later build) degrades to the raw
 * string rather than vanishing.
 */
internal fun providerDisplayName(rawValue: String): String =
    runCatching { ProviderType.valueOf(rawValue).displayName }.getOrDefault(rawValue)

/**
 * [T-token-attribution-snapshot] Decide how trustworthy a usage row's model
 * attribution is.
 *
 * Extracted from the Composable so it can be unit-tested on the JVM: this is
 * the rule that decides whether a number is presented as fact or as a guess,
 * and getting it wrong in either direction is invisible in a screenshot.
 *
 * @param modelId resolved id (snapshot, else the session's current model),
 *   or null for an orphaned row with no session to fall back to.
 * @param hasSnapshot whether the row carries a per-message snapshot.
 * @param resolvesInConfig whether that id still exists in the live config.
 */
internal fun classifyAttribution(
    modelId: String?,
    hasSnapshot: Boolean,
    resolvesInConfig: Boolean,
): Attribution = when {
    modelId == null -> Attribution.UNKNOWN_SESSION
    !hasSnapshot -> Attribution.ESTIMATED
    !resolvesInConfig -> Attribution.MEASURED_REMOVED
    else -> Attribution.MEASURED
}

@Composable
fun UsageStatsScreen(
    chatDao: ChatDao,
    providerConfig: ProviderConfig? = null,
    onBack: () -> Unit,
) {
    var grandTotal by remember { mutableStateOf(GrandTotal()) }
    var providerGroups by remember { mutableStateOf<List<ProviderGroup>>(emptyList()) }
    var isLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val records = chatDao.allUsageRecords()

        val modelLookup = mutableMapOf<String, Pair<String, String>>()
        for (m in LLMModel.allModels) modelLookup[m.id] = m.displayName to m.provider
        providerConfig?.let { config ->
            for (entry in config.modelEntries) {
                if (entry.model.id !in modelLookup) {
                    val instance = config.instances.find { it.id == entry.providerInstanceId }
                    val providerName = instance?.providerType?.displayName ?: entry.model.provider
                    modelLookup[entry.model.id] = entry.model.displayName to providerName
                }
            }
        }

        val statsMap = mutableMapOf<String, ModelStats>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        for (record in records) {
            val usage = try { JSONObject(record.tokenUsage) } catch (_: Exception) { continue }
            val input = usage.optLong("inputTokens", 0)
            val output = usage.optLong("outputTokens", 0)
            val cacheCr = usage.optLong("cacheCreationTokens", usage.optLong("cacheCreationInputTokens", 0))
            val cacheRd = usage.optLong("cacheReadTokens", usage.optLong("cacheReadInputTokens", 0))

            // [T-android-usage-orphan-rows] GH#168: modelId is null for a
            // message whose session row is gone (LEFT JOIN). Those tokens were
            // still billed, so they are counted under a single "unknown" bucket
            // instead of being dropped — matching iOS, where an unresolvable id
            // falls back to the raw id and the "Other" provider group.
            val modelKey = record.modelId ?: UNKNOWN_MODEL_KEY
            val resolved = modelLookup[modelKey]

            // [T-token-attribution-snapshot] Four distinct states — see
            // [Attribution]. Bucket key includes the state so an estimated row
            // never merges into a measured one and quietly inherits its
            // credibility.
            val attribution = classifyAttribution(
                modelId = record.modelId,
                hasSnapshot = record.hasSnapshot,
                resolvesInConfig = resolved != null,
            )

            // Prefer the snapshot's own strings: for a removed provider they
            // are the ONLY remaining source of a human-readable name.
            val displayName = record.modelDisplayName?.takeIf { it.isNotBlank() }
                ?: resolved?.first
                ?: modelKey
            val provider = record.providerType?.takeIf { it.isNotBlank() }
                ?.let { raw -> providerDisplayName(raw) }
                ?: resolved?.second
                ?: UNKNOWN_PROVIDER

            val stats = statsMap.getOrPut("$modelKey#${attribution.name}") {
                ModelStats(modelKey, displayName, provider, attribution)
            }
            stats.inputTokens += input
            stats.outputTokens += output
            stats.cacheCreationTokens += cacheCr
            stats.cacheReadTokens += cacheRd
            stats.distinctDays.add(dateFormat.format(Date(record.createdAt)))
            stats.distinctSessions.add(record.sessionId)
        }

        val providerOrder = listOf("OpenAI", "Anthropic", "Google Gemini", "Google", "Antigravity", "Unknown")
        val grouped = statsMap.values.groupBy { it.provider }
        val sortedGroups = grouped.entries.sortedBy { (name, _) ->
            val idx = providerOrder.indexOf(name)
            if (idx >= 0) idx else providerOrder.size
        }.map { (name, models) ->
            ProviderGroup(name, models.sortedByDescending { it.totalInput })
        }

        val allStats = statsMap.values
        grandTotal = GrandTotal(
            totalInput = allStats.sumOf { it.totalInput },
            outputTokens = allStats.sumOf { it.outputTokens },
            cacheReadTokens = allStats.sumOf { it.cacheReadTokens },
            cacheCreationTokens = allStats.sumOf { it.cacheCreationTokens },
        )

        providerGroups = sortedGroups
        isLoaded = true
    }

    SettingsScaffold(title = stringResource(R.string.usage_title), onBack = onBack) {
        if (!isLoaded) return@SettingsScaffold

        SettingsSection(header = stringResource(R.string.usage_section_total)) {
            val stats = listOfNotNull(
                stringResource(R.string.usage_label_total_input) to formatCount(grandTotal.totalInput),
                stringResource(R.string.usage_label_output) to formatCount(grandTotal.outputTokens),
                if (grandTotal.cacheReadTokens > 0) stringResource(R.string.usage_label_cache_read) to formatCount(grandTotal.cacheReadTokens) else null,
                if (grandTotal.cacheCreationTokens > 0) stringResource(R.string.usage_label_cache_creation) to formatCount(grandTotal.cacheCreationTokens) else null,
                grandTotal.cacheHitRate?.let { rate -> stringResource(R.string.usage_label_cache_hit_rate) to String.format("%.1f%%", rate) },
            )
            stats.forEachIndexed { idx, (label, value) ->
                SettingsValueRow(
                    title = label,
                    value = value,
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    showDivider = idx < stats.size - 1,
                )
            }
        }

        for (group in providerGroups) {
            SettingsSection(header = group.name) {
                group.models.forEachIndexed { idx, model ->
                    ExpandableModelRow(
                        model = model,
                        showDivider = idx < group.models.size - 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ExpandableModelRow(model: ModelStats, showDivider: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val summary = "${formatCount(model.totalInput)} / ${formatCount(model.outputTokens)}"

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // [T-token-attribution-snapshot] Name plus, for the two states a
            // user can act on, one short line saying why.
            //
            // ESTIMATED deliberately shows NOTHING. It was explained at first,
            // but it is by far the most common state (every row predating the
            // per-message columns) and repeating the same sentence under
            // almost every row was pure noise — reported as such. The
            // distinction still matters internally: estimated rows stay in
            // their own bucket and never merge into a measured one, so the
            // numbers remain honest even though the label is gone.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    model.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                )
                val caveat = when (model.attribution) {
                    Attribution.MEASURED, Attribution.ESTIMATED -> null
                    Attribution.UNKNOWN_SESSION -> stringResource(R.string.usage_attr_unknown_session)
                    Attribution.MEASURED_REMOVED -> stringResource(R.string.usage_attr_removed)
                }
                if (caveat != null) {
                    Text(
                        caveat,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    if (expanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 32.dp, end = 16.dp, bottom = 8.dp)) {
                DetailRow(stringResource(R.string.usage_detail_input), formatCount(model.inputTokens))
                DetailRow(stringResource(R.string.usage_detail_output), formatCount(model.outputTokens))
                if (model.cacheReadTokens > 0) DetailRow(stringResource(R.string.usage_label_cache_read), formatCount(model.cacheReadTokens))
                if (model.cacheCreationTokens > 0) DetailRow(stringResource(R.string.usage_label_cache_creation), formatCount(model.cacheCreationTokens))
                val modelTotalInput = model.totalInput
                if (modelTotalInput > 0 && model.cacheReadTokens > 0) {
                    val rate = (model.cacheReadTokens.toDouble() / modelTotalInput) * 100
                    DetailRow(stringResource(R.string.usage_label_cache_hit_rate), String.format("%.1f%%", rate))
                }
                val days = model.distinctDays.size
                val sessions = model.distinctSessions.size
                if (days > 0) {
                    DetailRow(stringResource(R.string.usage_detail_daily_avg), formatCount((model.inputTokens + model.outputTokens) / days))
                }
                if (sessions > 0) {
                    DetailRow(stringResource(R.string.usage_detail_session_avg), formatCount((model.inputTokens + model.outputTokens) / sessions))
                }
                DetailRow(stringResource(R.string.usage_detail_sessions), sessions.toString())
                DetailRow(stringResource(R.string.usage_detail_active_days), days.toString())
            }
        }

        if (showDivider) {
            val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 14.dp)
                    .height(0.5.dp)
                    .background(divider),
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Default)
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> {
        val k = n / 1000.0
        if (k == k.toLong().toDouble()) "${k.toLong()}k"
        else String.format("%.1fk", k)
    }
    else -> n.toString()
}
