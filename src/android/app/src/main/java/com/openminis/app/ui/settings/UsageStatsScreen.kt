package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.ui.chat.brandAccentFor
import com.openminis.app.ui.chat.ModelBrandResolver
import com.openminis.app.ui.chat.ModelBrandIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
    val displayName: String,
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

    /**
     * [T-usage-provider-subtitle] The identity line under the model name.
     *
     * Prefers the provider INSTANCE label (the user's own name for the
     * connection, e.g. "天翼云") over the provider TYPE ("OpenAI"):
     * two relays both configured as OpenAI-compatible would otherwise show
     * the same subtitle, which is exactly the collision the subtitle exists
     * to resolve. Falls back to the type when the row predates the instance
     * column.
     */
    val subtitle: String get() = instanceLabel?.takeIf { it.isNotBlank() } ?: provider
}

internal data class ProviderGroup(val name: String, val models: List<ModelStats>)

/**
 * [T-usage-flat-list] One row of the usage page.
 *
 * The page used to render provider sections with headers. The header repeated
 * the provider that every row already shows as its subtitle, and it cost ~40dp
 * per provider — on a 22-model relay catalogue that is most of a screen. Rows
 * are now one flat, globally sorted list; the provider name in the subtitle is
 * the only place identity is stated, which is also why it is never truncated
 * away while the name has ellipsis.
 *
 * [shareOfWindow] is this row's input as a fraction of the CURRENTLY SELECTED
 * window's total input — it changes when the time filter changes, because a
 * 30-day window dilutes a model that dominated the last 7 days. Computed in
 * the aggregation (not in the composable) so the arithmetic is testable.
 */
internal data class UsageRow(
    val stats: ModelStats,
    val shareOfWindow: Float,
)

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
    /**
     * [T-usage-flat-list] Every row in one list, sorted by input descending.
     * [groups] is kept for the existing aggregation tests and for any caller
     * that still wants provider bucketing; the page renders THIS list.
     */
    val rows: List<UsageRow> = emptyList(),
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

    // [T-usage-provider-subtitle] The display name stays CLEAN. A previous
    // revision suffixed colliding names with the instance label
    // ("GLM-5.3 · 智谱 AI") so two rows could be told apart. The row now carries
    // that identity as its own subtitle, so suffixing would print it twice —
    // and the subtitle is the better carrier: it is the user's own provider
    // instance name, which is unique by construction, whereas the name is
    // whatever the vendor called the model.

    val providerOrder = listOf("OpenAI", "Anthropic", "Google Gemini", "Google", "Antigravity", "Unknown")
    val grouped = statsMap.values.groupBy { it.provider }
    val sortedGroups = grouped.entries.sortedBy { (name, _) ->
        val idx = providerOrder.indexOf(name)
        if (idx >= 0) idx else providerOrder.size
    }.map { (name, models) ->
        ProviderGroup(name, models.sortedByDescending { it.totalInput })
    }

    val allStats = statsMap.values
    val totalInput = allStats.sumOf { it.totalInput }
    // [T-usage-flat-list] Rows sorted by input, with the share computed against
    // the SAME total the caller is displaying. The previous page recomputed the
    // fraction inline as `model.totalInput / grandTotal.totalInput`, which was
    // correct only because both came from one window — moving the division here
    // keeps that coupling explicit and lets a test pin it.
    val flatRows = allStats
        .sortedWith(compareByDescending<ModelStats> { it.totalInput }.thenBy { it.displayName })
        .map { stats ->
            UsageRow(
                stats = stats,
                shareOfWindow = if (totalInput > 0) {
                    (stats.totalInput.toFloat() / totalInput).coerceIn(0f, 1f)
                } else {
                    0f
                },
            )
        }

    return UsageAggregation(
        groups = sortedGroups,
        grandTotal = GrandTotal(
            totalInput = totalInput,
            outputTokens = allStats.sumOf { it.outputTokens },
            cacheReadTokens = allStats.sumOf { it.cacheReadTokens },
            cacheCreationTokens = allStats.sumOf { it.cacheCreationTokens },
        ),
        rows = flatRows,
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
    var rows by remember { mutableStateOf<List<UsageRow>>(emptyList()) }
    var isLoaded by remember { mutableStateOf(false) }
    // [T-usage-time-range] Records + selected window. Aggregation runs on
    // Dispatchers.Default: thousands of rows x per-row JSONObject parsing is
    // a CPU burst, and the project discipline since PR62 keeps CPU bursts
    // off the main thread.
    //
    // [T-usage-window-drives-everything] The time filter drives EVERYTHING
    // below it — totals, the per-model list, the row order, the share bars
    // AND the trend chart. The chart follows the window by slicing
    // pre-parsed day buckets (buildDayBuckets, parsed once on load);
    // sliceDailySeries is pure arithmetic, so a filter tap re-derives the
    // chart without re-parsing a single row.
    var records by remember { mutableStateOf<List<UsageRecord>>(emptyList()) }
    var dayBuckets by remember { mutableStateOf<Map<String, LongArray>>(emptyMap()) }
    var timeRange by remember { mutableStateOf(UsageTimeRange.LAST_7) }
    var daily by remember { mutableStateOf<List<DailyUsage>>(emptyList()) }

    LaunchedEffect(Unit) {
        val loaded = chatDao.allUsageRecords()
        // Parse each row's tokenUsage exactly once, off the main thread.
        val buckets = withContext(Dispatchers.Default) { buildDayBuckets(loaded) }
        dayBuckets = buckets
        records = loaded
        isLoaded = true
        // Initial aggregation + chart slice happen in the (timeRange, records)
        // effect below — assigning `records` restarts it with isLoaded already
        // true and the buckets already in place.
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
        rows = summary.rows
        // [T-usage-window-drives-everything] The chart follows the selected
        // window. Slicing pre-parsed buckets: O(days) map lookups, no JSON.
        daily = sliceDailySeries(dayBuckets, timeRange.days ?: ALL_CHART_DAYS, now)
    }

    SettingsScaffold(title = stringResource(R.string.usage_title), onBack = null) {
        if (!isLoaded) return@SettingsScaffold

        UsageTimeFilterRow(selected = timeRange, onSelect = { timeRange = it })

        // ── Totals ───────────────────────────────────────────────────────────
        SettingsSection(header = stringResource(R.string.usage_section_total)) {
            UsageSummaryCard(grandTotal = grandTotal, timeRange = timeRange)
        }

        // ── Trend chart (follows the selected window) ───────────────────────
        SettingsSection(
            header = stringResource(R.string.usage_chart_title) + " · " + usageRangeLabel(timeRange),
        ) {
            UsageTrendCard(daily = daily)
        }

        // ── Per-model usage ──────────────────────────────────────────────────
        // [T-usage-flat-list] One flat, globally sorted card. The provider name
        // lives in each row's subtitle instead of a section header: it is
        // unique per instance, so it already disambiguates two rows showing the
        // same model name from different providers, and dropping the headers
        // returns ~40dp per provider to the list.
        SettingsSection(header = stringResource(R.string.usage_section_models)) {
            if (rows.isEmpty()) {
                UsageEmptyRow()
            } else {
                rows.forEachIndexed { idx, row ->
                    ExpandableModelRow(
                        model = row.stats,
                        shareOfTotal = row.shareOfWindow,
                        showDivider = idx < rows.size - 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * [T-usage-summary-card] Totals as one glanceable block: two large numbers side
 * by side, then the cache figures as pills. Replaces a run of label/value rows
 * where the only thing the eye could latch onto was the label column.
 */
@Composable
private fun UsageSummaryCard(grandTotal: GrandTotal, timeRange: UsageTimeRange) {
    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.usage_label_total_input),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(5.dp))
                SplitTokenValue(grandTotal.totalInput, MaterialTheme.colorScheme.onSurface)
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 18.dp)
                    .width(1.dp)
                    .height(32.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.usage_label_output),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(5.dp))
                SplitTokenValue(grandTotal.outputTokens, MaterialTheme.colorScheme.onSurface)
            }
        }

        val pills = listOfNotNull(
            grandTotal.cacheHitRate?.let {
                stringResource(R.string.usage_summary_cache_hit) to String.format("%.1f%%", it)
            },
            if (grandTotal.cacheReadTokens > 0) {
                stringResource(R.string.usage_label_cache_read) to formatCount(grandTotal.cacheReadTokens)
            } else null,
            if (grandTotal.cacheCreationTokens > 0) {
                stringResource(R.string.usage_label_cache_creation) to formatCount(grandTotal.cacheCreationTokens)
            } else null,
        )
        if (pills.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pills.forEach { (label, value) ->
                    UsagePill(label = label, value = value)
                }
            }
        }
        // The window is already selected by the chip row above, but a summary
        // that says "Total" with no window is ambiguous once a user scrolls the
        // chips out of view.
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.usage_summary_window, usageRangeLabel(timeRange)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

/**
 * A token count rendered as `1.24M` / `286k`: the number carries the emphasis,
 * the magnitude suffix rides smaller so the leading digits stay aligned.
 */
@Composable
private fun SplitTokenValue(value: Long, color: Color) {
    val text = formatCount(value)
    val split = text.takeWhile { it.isDigit() || it == '.' }
    val suffix = text.removePrefix(split)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            split,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
        if (suffix.isNotEmpty()) {
            Text(
                suffix,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = color.copy(alpha = 0.55f),
                modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun UsagePill(label: String, value: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$label ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun UsageEmptyRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.usage_chart_no_data),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExpandableModelRow(model: ModelStats, shareOfTotal: Float, showDivider: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val summary = "${formatCount(model.totalInput)} / ${formatCount(model.outputTokens)}"
    // [T-brand-accent-color] Brand logo + the brand's own accent for the share
    // bar. Resolves through the single brand entry point, so the row shows the
    // SAME logo the picker and top bar show — and a model with no recognisable
    // brand gets a deterministic hash hue instead of wearing another vendor's
    // colour.
    val brandSlug = remember(model.modelId, model.displayName) {
        ModelBrandResolver.resolveExact(model.modelId, model.displayName)
    }
    val accent = brandAccentFor(model.modelId, model.displayName)

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandAvatar(slug = brandSlug, fallbackName = model.displayName, accent = accent)
            Spacer(Modifier.width(11.dp))
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        model.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    when (model.attribution) {
                        Attribution.MEASURED, Attribution.ESTIMATED -> Unit
                        Attribution.UNKNOWN_SESSION -> AttributionBadge(R.string.usage_attr_unknown_session)
                        Attribution.MEASURED_REMOVED -> AttributionBadge(R.string.usage_attr_removed)
                    }
                }
                // [T-usage-provider-subtitle] The provider name is the ONLY
                // identity line here and it is deliberately never optional:
                // two rows can display the same model name (official vs relay),
                // and the subtitle is what tells them apart once the section
                // headers are gone. It therefore gets its own line rather than
                // competing with the name for space.
                Text(
                    model.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Default,
            )
            Icon(
                if (expanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }

        // [T-usage-share-bar] This model's input as a share of the SELECTED
        // window's total input — so the bar re-scales when the user switches
        // Today / 7d / 30d / All, and a model that dominated the week visibly
        // shrinks in a 30-day view. Hidden when the window has no input.
        if (shareOfTotal > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 53.dp, end = 14.dp, bottom = 10.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(shareOfTotal)
                        .height(3.dp)
                        .background(accent),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 53.dp, end = 14.dp, bottom = 10.dp)) {
                DetailRow(stringResource(R.string.usage_detail_input), formatCount(model.inputTokens))
                DetailRow(stringResource(R.string.usage_detail_output), formatCount(model.outputTokens))
                if (model.cacheReadTokens > 0) DetailRow(stringResource(R.string.usage_label_cache_read), formatCount(model.cacheReadTokens))
                if (model.cacheCreationTokens > 0) DetailRow(stringResource(R.string.usage_label_cache_creation), formatCount(model.cacheCreationTokens))
                val modelTotalInput = model.totalInput
                if (modelTotalInput > 0 && model.cacheReadTokens > 0) {
                    val rate = (model.cacheReadTokens.toDouble() / modelTotalInput) * 100
                    DetailRow(stringResource(R.string.usage_label_cache_hit_rate), String.format("%.1f%%", rate))
                }
                // The window share is shown as a percentage next to the bar so
                // the bar is not the only encoding of the number.
                if (shareOfTotal > 0f) {
                    DetailRow(
                        stringResource(R.string.usage_detail_window_share),
                        String.format("%.1f%%", shareOfTotal * 100),
                    )
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
                    .padding(start = 53.dp, end = 14.dp)
                    .height(0.5.dp)
                    .background(divider),
            )
        }
    }
}

/**
 * [T-brand-avatar-single-source] The row's brand mark: the real lobe-icons
 * asset on a tinted chip, or the model's initial on a deterministic brand
 * accent when no brand resolves. Uses the same resolver entry point as every
 * other brand surface, so a vendor cannot look like one thing here and another
 * in the picker.
 */
@Composable
private fun BrandAvatar(slug: String?, fallbackName: String, accent: Color) {
    val resId = remember(slug) { slug?.let { ModelBrandResolver.iconResId(it) } }
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (resId != null) {
            ModelBrandIcon(slug = slug, sizeDp = 17.dp)
        } else {
            Text(
                text = fallbackName.take(1),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
        }
    }
}

@Composable
private fun AttributionBadge(textRes: Int) {
    Text(
        stringResource(textRes),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
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
