package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.data.db.UsageRecord

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
import com.openminis.app.util.IsoTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

internal data class ModelStats(
    val modelId: String,
    var displayName: String,
    val provider: String,
    val attribution: Attribution,
    val instanceId: String? = null,
    val instanceLabel: String? = null,
    var inputTokens: Long = 0,
    var outputTokens: Long = 0,
    var cacheCreationTokens: Long = 0,
    var cacheReadTokens: Long = 0,
    val distinctDays: MutableSet<String> = mutableSetOf(),
    val distinctSessions: MutableSet<String> = mutableSetOf(),
) {
    val totalInput: Long get() = inputTokens + cacheReadTokens + cacheCreationTokens
}

internal data class ProviderGroup(val name: String, val models: List<ModelStats>)

internal data class GrandTotal(
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

internal data class UsageAggregation(
    val groups: List<ProviderGroup>,
    val grandTotal: GrandTotal,
)

/**
 * Aggregate raw usage rows into per-model buckets and provider groups.
 *
 * Pure so the bucketing rules are testable. The bucket key is
 * (provider instance, model id, attribution state) — NOT the model id alone.
 * Two provider instances can serve the same model id under the same display
 * name (two relays, or official plus relay), and keying on the model id merged
 * their billed tokens into one row: the Usage page then showed one entry whose
 * numbers silently summed two different accounts. Rows without an instance
 * snapshot (written before the column existed) fall back to the provider
 * type, so they still never merge across providers.
 */
internal fun aggregateUsageRecords(
    records: List<UsageRecord>,
    modelLookup: Map<String, Pair<String, String>>,
    instanceLabels: Map<String, String>,
): UsageAggregation {
    val statsMap = mutableMapOf<String, ModelStats>()
    for (record in records) {
        val usage = try { JSONObject(record.tokenUsage) } catch (_: Exception) { continue }
        val input = usage.optLong("inputTokens", 0)
        val output = usage.optLong("outputTokens", 0)
        val cacheCr = usage.optLong("cacheCreationTokens", usage.optLong("cacheCreationInputTokens", 0))
        val cacheRd = usage.optLong("cacheReadTokens", usage.optLong("cacheReadInputTokens", 0))

        // [T-android-usage-orphan-rows] GH#168: modelId is null for a
        // message whose session row is gone (LEFT JOIN). Those tokens were
        // still billed, so they are counted under a single "unknown" bucket
        // instead of being dropped.
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
        val instanceId = record.providerInstanceId?.takeIf { it.isNotBlank() }
        val instanceKey = instanceId ?: provider

        val stats = statsMap.getOrPut("$instanceKey|$modelKey#${attribution.name}") {
            ModelStats(
                modelId = modelKey,
                displayName = displayName,
                provider = provider,
                attribution = attribution,
                instanceId = instanceId,
                instanceLabel = instanceId?.let { instanceLabels[it] },
            )
        }
        stats.inputTokens += input
        stats.outputTokens += output
        stats.cacheCreationTokens += cacheCr
        stats.cacheReadTokens += cacheRd
        stats.distinctDays.add(IsoTime.formatLocalDate(record.createdAt))
        stats.distinctSessions.add(record.sessionId)
    }

    // The same display name in more than one bucket means the name alone
    // cannot tell the rows apart; suffix the instance label (or the provider
    // when the instance is gone) so the page never shows two identical rows
    // holding different numbers.
    val nameCounts = statsMap.values.groupingBy { it.displayName }.eachCount()
    for (stats in statsMap.values) {
        if ((nameCounts[stats.displayName] ?: 0) > 1) {
            val suffix = stats.instanceLabel ?: stats.provider
            stats.displayName = "${stats.displayName} · $suffix"
        }
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
    return UsageAggregation(
        groups = sortedGroups,
        grandTotal = GrandTotal(
            totalInput = allStats.sumOf { it.totalInput },
            outputTokens = allStats.sumOf { it.outputTokens },
            cacheReadTokens = allStats.sumOf { it.cacheReadTokens },
            cacheCreationTokens = allStats.sumOf { it.cacheCreationTokens },
        ),
    )
}

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
    // [T-usage-time-range] Records + selected window. Aggregation runs on
    // Dispatchers.Default: thousands of rows x per-row JSONObject parsing is
    // a CPU burst, and the project discipline since PR62 keeps CPU bursts
    // off the main thread. The chart series is window-independent (fixed
    // 14-day lookback) so it is derived once per records load, not per
    // filter tap.
    var records by remember { mutableStateOf<List<UsageRecord>>(emptyList()) }
    var timeRange by remember { mutableStateOf(UsageTimeRange.LAST_7) }
    var daily by remember { mutableStateOf<List<DailyUsage>>(emptyList()) }

    LaunchedEffect(Unit) {
        val loaded = chatDao.allUsageRecords()
        val now = System.currentTimeMillis()
        val series = withContext(Dispatchers.Default) { buildDailySeries(loaded, CHART_DAYS, now) }
        records = loaded
        daily = series
        isLoaded = true
        // Initial aggregation happens in the (timeRange, records) effect below
        // — assigning `records` restarts it with isLoaded already true.
    }

    // Re-derive on filter taps without re-querying the DB. The lookup maps
    // are rebuilt identically to the load effect (including the
    // modelEntries fallback) — a partial lookup would misclassify live
    // custom models as MEASURED_REMOVED and silently change attribution.
    LaunchedEffect(timeRange, records) {
        if (!isLoaded) return@LaunchedEffect
        val now = System.currentTimeMillis()
        val modelLookup = mutableMapOf<String, Pair<String, String>>()
        for (m in LLMModel.allModels) modelLookup[m.id] = m.displayName to m.provider
        val instanceLabels = mutableMapOf<String, String>()
        providerConfig?.let { config ->
            for (instance in config.instances) instanceLabels[instance.id] = instance.label
            for (entry in config.modelEntries) {
                if (entry.model.id !in modelLookup) {
                    val instance = config.instances.find { it.id == entry.providerInstanceId }
                    val providerName = instance?.providerType?.displayName ?: entry.model.provider
                    modelLookup[entry.model.id] = entry.model.displayName to providerName
                }
            }
        }
        val summary = withContext(Dispatchers.Default) {
            aggregateUsageRecords(filterUsageByRange(records, timeRange, now), modelLookup, instanceLabels)
        }
        grandTotal = summary.grandTotal
        providerGroups = summary.groups
    }

    SettingsScaffold(title = stringResource(R.string.usage_title), onBack = null) {
        if (!isLoaded) return@SettingsScaffold

        UsageTimeFilterRow(selected = timeRange, onSelect = { timeRange = it })

        SettingsSection(header = stringResource(R.string.usage_chart_title)) {
            UsageTrendChart(daily = daily)
        }

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
                        shareOfTotal = if (grandTotal.totalInput > 0)
                            model.totalInput.toFloat() / grandTotal.totalInput else 0f,
                        showDivider = idx < group.models.size - 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ExpandableModelRow(model: ModelStats, shareOfTotal: Float, showDivider: Boolean) {
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

        // [T-usage-share-bar] This model's input as a share of the selected
        // window's total input — the same number the row shows, made
        // comparable at a glance. Hidden when the window has no input.
        if (shareOfTotal > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 2.dp)
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(shareOfTotal.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
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

internal fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> {
        val k = n / 1000.0
        if (k == k.toLong().toDouble()) "${k.toLong()}k"
        else String.format("%.1fk", k)
    }
    else -> n.toString()
}
