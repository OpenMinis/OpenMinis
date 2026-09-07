package com.openminis.app.ui.settings

import android.content.Context
import com.openminis.app.R

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.ModelPricingEntity
import com.openminis.app.data.db.UsageRecord
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderType
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
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

/** Time-range filter on the Usage screen. */
private enum class TimeFilter(val label: String) {
    ALL("全部"),
    TODAY("今天"),
    WEEK("本周"),
    MONTH("本月"),
    LAST30("近30天"),
    CUSTOM("📅 自定义"),
}

/** Per-request token-size bucket filter. */
private enum class TokenBucket(val label: String) {
    ALL("全部"),
    MICRO("微(≤1k)"),
    SMALL("小(1k~10k)"),
    MEDIUM("中(10k~100k)"),
    LARGE("大(>100k)"),
}

/** Secondary sort mode for the provider/model cards. */
private enum class SortMode(val label: String) {
    COST("费用"),
    TOKENS("Token"),
    REQUESTS("请求数"),
}

/** One raw token-usage request, flattened from a UsageRecord for filtering. */
private data class UsageEntry(
    val modelKey: String,
    val displayName: String,
    val provider: String,
    val attribution: Attribution,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val requestTokens: Long,
    val costUsd: Double,
    val createdAt: Long,
    val sessionId: String,
    val hasError: Boolean,
)

private data class ModelStats(
    val modelKey: String,
    val displayName: String,
    val provider: String,
    val attribution: Attribution,
    var requests: Int = 0,
    var inputTokens: Long = 0,
    var outputTokens: Long = 0,
    var cacheReadTokens: Long = 0,
    var cacheWriteTokens: Long = 0,
    var costUsd: Double = 0.0,
    val entries: MutableList<UsageEntry> = mutableListOf(),
    val distinctDays: MutableSet<String> = mutableSetOf(),
    val distinctSessions: MutableSet<String> = mutableSetOf(),
) {
    val totalInput: Long get() = inputTokens + cacheReadTokens + cacheWriteTokens
    val totalTokens: Long get() = totalInput + outputTokens
    val cacheHitRate: Double?
        get() = if (totalInput <= 0 || cacheReadTokens <= 0) null
        else (cacheReadTokens.toDouble() / totalInput) * 100
}

private data class ProviderGroup(
    val name: String,
    val models: List<ModelStats>,
) {
    val totalCost: Double get() = models.sumOf { it.costUsd }
    val totalTokens: Long get() = models.sumOf { it.totalTokens }
    val totalRequests: Int get() = models.sumOf { it.requests }
}

private data class Summary(
    val requests: Int = 0,
    val totalTokens: Long = 0,
    val costUsd: Double = 0.0,
    val cacheHitRate: Double? = null,
    val successRate: Double? = null,
)

/**
 * [T-android-usage-orphan-rows] Bucket for usage rows whose session row is
 * missing, so their (really billed) tokens still appear in the totals. See
 * ChatDao.allUsageRecords.
 */
private const val UNKNOWN_MODEL_KEY = "(unknown model)"

private const val UNKNOWN_PROVIDER = "Unknown"

/** Deterministic color per provider for the filter dots + provider cards. */
private val PROVIDER_COLORS = listOf(
    Color(0xFF3B82F6), Color(0xFF10B981), Color(0xFFF59E0B),
    Color(0xFFEF4444), Color(0xFF8B5CF6), Color(0xFF14B8A6),
    Color(0xFFEC4899), Color(0xFF84CC16), Color(0xFF6366F1),
)

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

// ── Pure helpers (JVM-testable, no Compose) ───────────────────────────────────

private fun buildModelLookup(providerConfig: ProviderConfig?): Map<String, Pair<String, String>> {
    val lookup = mutableMapOf<String, Pair<String, String>>()
    for (m in LLMModel.allModels) lookup[m.id] = m.displayName to m.provider
    providerConfig?.let { config ->
        for (entry in config.modelEntries) {
            if (entry.model.id !in lookup) {
                val instance = config.instances.find { it.id == entry.providerInstanceId }
                val providerName = instance?.providerType?.displayName ?: entry.model.provider
                lookup[entry.model.id] = entry.model.displayName to providerName
            }
        }
    }
    return lookup
}

private fun costOf(price: ModelPricingEntity?, input: Long, output: Long, cacheCr: Long, cacheRd: Long): Double {
    if (price == null) return 0.0
    return (input / 1_000_000.0 * price.inputPerMillion) +
        (output / 1_000_000.0 * price.outputPerMillion) +
        (cacheRd / 1_000_000.0 * price.cacheReadPerMillion) +
        (cacheCr / 1_000_000.0 * price.cacheWritePerMillion)
}

private fun buildUsageEntries(
    records: List<UsageRecord>,
    pricing: Map<String, ModelPricingEntity>,
    modelLookup: Map<String, Pair<String, String>>,
): List<UsageEntry> {
    val out = ArrayList<UsageEntry>(records.size)
    for (record in records) {
        val usage = try { JSONObject(record.tokenUsage) } catch (_: Exception) { continue }
        val input = usage.optLong("inputTokens", 0)
        val output = usage.optLong("outputTokens", 0)
        val cacheCr = usage.optLong("cacheCreationTokens", usage.optLong("cacheCreationInputTokens", 0))
        val cacheRd = usage.optLong("cacheReadTokens", usage.optLong("cacheReadInputTokens", 0))

        // [T-android-usage-orphan-rows] GH#168: modelId is null for a message
        // whose session row is gone (LEFT JOIN). Those tokens were still
        // billed, so they are counted under a single "unknown" bucket.
        val modelKey = record.modelId ?: UNKNOWN_MODEL_KEY
        val resolved = modelLookup[modelKey]
        val attribution = classifyAttribution(record.modelId, record.hasSnapshot, resolved != null)
        val displayName = record.modelDisplayName?.takeIf { it.isNotBlank() }
            ?: resolved?.first
            ?: modelKey
        val provider = record.providerType?.takeIf { it.isNotBlank() }
            ?.let { raw -> providerDisplayName(raw) }
            ?: resolved?.second
            ?: UNKNOWN_PROVIDER

        out.add(UsageEntry(
            modelKey = modelKey,
            displayName = displayName,
            provider = provider,
            attribution = attribution,
            inputTokens = input,
            outputTokens = output,
            cacheReadTokens = cacheRd,
            cacheWriteTokens = cacheCr,
            requestTokens = input + output + cacheCr + cacheRd,
            costUsd = costOf(pricing[modelKey], input, output, cacheCr, cacheRd),
            createdAt = record.createdAt,
            sessionId = record.sessionId,
            hasError = !record.errorInfo.isNullOrBlank(),
        ))
    }
    return out
}

private fun providerComparator(sortMode: SortMode) = Comparator<ProviderGroup> { a, b ->
    when (sortMode) {
        SortMode.COST -> b.totalCost.compareTo(a.totalCost)
        SortMode.REQUESTS -> b.totalRequests.compareTo(a.totalRequests)
        SortMode.TOKENS -> b.totalTokens.compareTo(a.totalTokens)
    }
}

private fun modelComparator(sortMode: SortMode) = Comparator<ModelStats> { a, b ->
    when (sortMode) {
        SortMode.COST -> b.costUsd.compareTo(a.costUsd)
        SortMode.REQUESTS -> b.requests.compareTo(a.requests)
        SortMode.TOKENS -> b.totalTokens.compareTo(a.totalTokens)
    }
}

private fun buildGroups(entries: List<UsageEntry>, sortMode: SortMode): List<ProviderGroup> {
    val statsMap = mutableMapOf<String, ModelStats>()
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    for (e in entries) {
        val key = "${e.modelKey}#${e.attribution.name}"
        val stats = statsMap.getOrPut(key) {
            ModelStats(e.modelKey, e.displayName, e.provider, e.attribution)
        }
        stats.requests += 1
        stats.inputTokens += e.inputTokens
        stats.outputTokens += e.outputTokens
        stats.cacheReadTokens += e.cacheReadTokens
        stats.cacheWriteTokens += e.cacheWriteTokens
        stats.costUsd += e.costUsd
        stats.entries.add(e)
        stats.distinctDays.add(dateFormat.format(Date(e.createdAt)))
        stats.distinctSessions.add(e.sessionId)
    }
    val comparator = modelComparator(sortMode)
    return statsMap.values
        .groupBy { it.provider }
        .entries
        .map { (name, models) -> ProviderGroup(name, models.sortedWith(comparator)) }
        .sortedWith(providerComparator(sortMode))
}

private fun computeSummary(entries: List<UsageEntry>): Summary {
    var input = 0L; var output = 0L; var cacheRd = 0L; var cacheCr = 0L; var cost = 0.0
    for (e in entries) {
        input += e.inputTokens; output += e.outputTokens
        cacheRd += e.cacheReadTokens; cacheCr += e.cacheWriteTokens; cost += e.costUsd
    }
    val totalInput = input + cacheRd + cacheCr
    val cacheHit = if (totalInput <= 0) null else (cacheRd.toDouble() / totalInput) * 100
    val success = if (entries.isEmpty()) null
        else ((entries.size - entries.count { it.hasError }) * 100.0 / entries.size)
    return Summary(
        requests = entries.size,
        totalTokens = input + output + cacheRd + cacheCr,
        costUsd = cost,
        cacheHitRate = cacheHit,
        successRate = success,
    )
}

private fun startOfDay(now: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun startOfWeek(now: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    cal.firstDayOfWeek = Calendar.MONDAY
    cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun startOfMonth(now: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(Calendar.DAY_OF_MONTH, 1)
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun matchesTime(e: UsageEntry, filter: TimeFilter, customStart: Long?, customEnd: Long?): Boolean {
    val now = System.currentTimeMillis()
    return when (filter) {
        TimeFilter.ALL -> true
        TimeFilter.TODAY -> e.createdAt >= startOfDay(now)
        TimeFilter.WEEK -> e.createdAt >= startOfWeek(now)
        TimeFilter.MONTH -> e.createdAt >= startOfMonth(now)
        TimeFilter.LAST30 -> e.createdAt >= now - 30L * 24 * 3600 * 1000
        TimeFilter.CUSTOM -> customStart != null && customEnd != null && e.createdAt >= customStart && e.createdAt <= customEnd
    }
}

private fun matchesBucket(e: UsageEntry, bucket: TokenBucket): Boolean = when (bucket) {
    TokenBucket.ALL -> true
    TokenBucket.MICRO -> e.requestTokens <= 1_000
    TokenBucket.SMALL -> e.requestTokens in 1_001..10_000
    TokenBucket.MEDIUM -> e.requestTokens in 10_001..100_000
    TokenBucket.LARGE -> e.requestTokens > 100_000
}

private fun providerColor(provider: String): Color {
    val idx = provider.hashCode() and 0x7fffffff
    return PROVIDER_COLORS[idx % PROVIDER_COLORS.size]
}

private fun formatUsd(v: Double): String = if (v <= 0) "—" else String.format("$%.4f", v)

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> {
        val k = n / 1000.0
        if (k == k.toLong().toDouble()) "${k.toLong()}k"
        else String.format("%.1fk", k)
    }
    else -> n.toString()
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageStatsScreen(
    chatDao: ChatDao,
    providerConfig: ProviderConfig? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val modelPricingDao = remember { AppDatabase.getInstance(context).modelPricingDao() }
    val scope = rememberCoroutineScope()

    var isLoaded by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf<List<UsageRecord>>(emptyList()) }
    var pricing by remember { mutableStateOf<Map<String, ModelPricingEntity>>(emptyMap()) }
    val modelLookup = remember(providerConfig) { buildModelLookup(providerConfig) }
    val allEntries = remember(records, pricing, modelLookup) {
        buildUsageEntries(records, pricing, modelLookup)
    }

    // Filters
    var timeFilter by remember { mutableStateOf(TimeFilter.ALL) }
    var selectedProviders by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedModels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tokenBucket by remember { mutableStateOf(TokenBucket.ALL) }
    var top50 by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(SortMode.TOKENS) }
    var showDateRange by remember { mutableStateOf(false) }
    var customStart by remember { mutableStateOf<Long?>(null) }
    var customEnd by remember { mutableStateOf<Long?>(null) }

    // Pricing editor state
    var pricingTarget by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        records = chatDao.allUsageRecords()
        pricing = modelPricingDao.getAll().associateBy { it.modelId }
        isLoaded = true
    }

    val availableProviders = remember(allEntries) { allEntries.map { it.provider }.distinct().sorted() }
    val providerScoped = remember(allEntries, selectedProviders) {
        if (selectedProviders.isEmpty()) allEntries
        else allEntries.filter { it.provider in selectedProviders }
    }
    val availableModels = remember(providerScoped) {
        providerScoped
            .groupBy { it.modelKey }
            .map { (key, es) -> key to (es.first().displayName to es.first().provider) }
            .sortedBy { it.second.first.lowercase() }
    }

    val filteredEntries = remember(
        allEntries, timeFilter, selectedProviders, selectedModels,
        tokenBucket, top50, customStart, customEnd,
    ) {
        var list = allEntries.filter {
            matchesTime(it, timeFilter, customStart, customEnd) &&
                (selectedProviders.isEmpty() || it.provider in selectedProviders) &&
                (selectedModels.isEmpty() || it.modelKey in selectedModels) &&
                matchesBucket(it, tokenBucket)
        }
        if (top50) {
            list = list.sortedByDescending { it.requestTokens }.take(50)
        }
        list
    }

    val groups = remember(filteredEntries, sortMode) { buildGroups(filteredEntries, sortMode) }
    val summary = remember(filteredEntries) { computeSummary(filteredEntries) }

    SettingsScaffold(title = stringResource(R.string.usage_title), onBack = onBack, scrollable = false) {
        if (!isLoaded) return@SettingsScaffold

        FilterBar(
            timeFilter = timeFilter,
            onTimeFilter = { timeFilter = it; if (it == TimeFilter.CUSTOM) showDateRange = true },
            availableProviders = availableProviders,
            selectedProviders = selectedProviders,
            onToggleProvider = { p ->
                if (p == null) selectedProviders = emptySet()
                else selectedProviders = if (p in selectedProviders) selectedProviders - p else selectedProviders + p
            },
            availableModels = availableModels,
            selectedModels = selectedModels,
            onToggleModel = { m ->
                if (m == null) selectedModels = emptySet()
                else selectedModels = if (m in selectedModels) selectedModels - m else selectedModels + m
            },
            tokenBucket = tokenBucket,
            onTokenBucket = { tokenBucket = it },
            top50 = top50,
            onTop50 = { top50 = !top50 },
            sortMode = sortMode,
            onSortMode = { sortMode = it },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            SummarySection(summary)

            for (group in groups) {
                ProviderGroupCard(
                    group = group,
                    sortMode = sortMode,
                    onConfigurePrice = { modelKey -> pricingTarget = modelKey },
                )
            }

            if (groups.isEmpty()) {
                Text(
                    stringResource(R.string.usage_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDateRange) {
        CustomDateRangeDialog(
            onDismiss = {
                showDateRange = false
                if (customStart == null || customEnd == null) timeFilter = TimeFilter.ALL
            },
            onConfirm = { start, end ->
                customStart = start
                customEnd = end
                timeFilter = TimeFilter.CUSTOM
                showDateRange = false
            },
        )
    }

    pricingTarget?.let { modelKey ->
        val current = pricing[modelKey]
        val displayName = allEntries.firstOrNull { it.modelKey == modelKey }?.displayName ?: modelKey
        ModelPricingDialog(
            modelKey = modelKey,
            displayName = displayName,
            current = current,
            onDismiss = { pricingTarget = null },
            onSave = { entity ->
                scope.launch {
                    modelPricingDao.upsert(entity)
                    pricing = modelPricingDao.getAll().associateBy { it.modelId }
                    pricingTarget = null
                }
            },
            onClear = {
                scope.launch {
                    modelPricingDao.deleteByModelId(modelKey)
                    pricing = modelPricingDao.getAll().associateBy { it.modelId }
                    pricingTarget = null
                }
            },
        )
    }
}

// ── Filter bar ────────────────────────────────────────────────────────────────

@Composable
private fun FilterBar(
    timeFilter: TimeFilter,
    onTimeFilter: (TimeFilter) -> Unit,
    availableProviders: List<String>,
    selectedProviders: Set<String>,
    onToggleProvider: (String?) -> Unit,
    availableModels: List<Pair<String, Pair<String, String>>>,
    selectedModels: Set<String>,
    onToggleModel: (String?) -> Unit,
    tokenBucket: TokenBucket,
    onTokenBucket: (TokenBucket) -> Unit,
    top50: Boolean,
    onTop50: () -> Unit,
    sortMode: SortMode,
    onSortMode: (SortMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 4.dp),
    ) {
        FilterRowLabel(stringResource(R.string.usage_filter_time))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
            items(TimeFilter.entries) { t ->
                FilterChip(
                    selected = timeFilter == t,
                    onClick = { onTimeFilter(t) },
                    label = { Text(t.label) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        FilterRowLabel(stringResource(R.string.usage_filter_provider))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
            item {
                FilterChip(
                    selected = selectedProviders.isEmpty(),
                    onClick = { onToggleProvider(null) },
                    label = { Text(stringResource(R.string.usage_filter_all)) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            items(availableProviders) { p ->
                FilterChip(
                    selected = p in selectedProviders,
                    onClick = { onToggleProvider(p) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .width(8.dp)
                                    .height(8.dp)
                                    .background(providerColor(p), androidx.compose.foundation.shape.CircleShape),
                            )
                            Text(p)
                        }
                    },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        FilterRowLabel(stringResource(R.string.usage_filter_model))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
            item {
                FilterChip(
                    selected = selectedModels.isEmpty(),
                    onClick = { onToggleModel(null) },
                    label = { Text(stringResource(R.string.usage_filter_all)) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            items(availableModels) { (key, info) ->
                FilterChip(
                    selected = key in selectedModels,
                    onClick = { onToggleModel(key) },
                    label = { Text(info.first) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        FilterRowLabel(stringResource(R.string.usage_filter_bucket))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
            items(TokenBucket.entries) { b ->
                FilterChip(
                    selected = tokenBucket == b,
                    onClick = { onTokenBucket(b) },
                    label = { Text(b.label) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            item {
                FilterChip(
                    selected = top50,
                    onClick = onTop50,
                    label = { Text(stringResource(R.string.usage_filter_top50)) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        FilterRowLabel(stringResource(R.string.usage_filter_sort))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
            items(SortMode.entries) { s ->
                FilterChip(
                    selected = sortMode == s,
                    onClick = { onSortMode(s) },
                    label = { Text(stringResource(R.string.usage_sort_by) + " " + s.label) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun FilterRowLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

// ── Summary ──────────────────────────────────────────────────────────────────

@Composable
private fun SummarySection(summary: Summary) {
    SettingsSection(header = stringResource(R.string.usage_section_total)) {
        val stats = listOfNotNull(
            stringResource(R.string.usage_label_requests) to summary.requests.toString(),
            stringResource(R.string.usage_label_tokens) to formatCount(summary.totalTokens),
            stringResource(R.string.usage_label_cost) to formatUsd(summary.costUsd),
            summary.cacheHitRate?.let { r ->
                stringResource(R.string.usage_label_cache_hit_rate) to String.format("%.1f%%", r)
            },
            summary.successRate?.let { r ->
                stringResource(R.string.usage_label_success_rate) to String.format("%.1f%%", r)
            },
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
}

// ── Provider group card ───────────────────────────────────────────────────────

@Composable
private fun ProviderGroupCard(
    group: ProviderGroup,
    sortMode: SortMode,
    onConfigurePrice: (String) -> Unit,
) {
    val color = providerColor(group.name)
    SettingsSection(header = group.name) {
        // Header subtotal: cost / tokens / requests + proportion bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .width(10.dp)
                    .height(10.dp)
                    .background(color, androidx.compose.foundation.shape.CircleShape),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${formatCount(group.totalTokens)} · ${group.totalRequests} reqs · ${formatUsd(group.totalCost)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        group.models.forEachIndexed { idx, model ->
            ModelRow(
                model = model,
                showDivider = idx < group.models.size - 1,
                onConfigurePrice = { onConfigurePrice(model.modelKey) },
            )
        }
    }
}

// ── Per-model expandable row ─────────────────────────────────────────────────

@Composable
private fun ModelRow(
    model: ModelStats,
    showDivider: Boolean,
    onConfigurePrice: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val summaryText = "${formatCount(model.totalTokens)} · ${model.requests} reqs · ${formatUsd(model.costUsd)}"

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.displayName, style = MaterialTheme.typography.bodyLarge)
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
                Text(
                    summaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onConfigurePrice) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.usage_pricing_configure))
            }
            Icon(
                if (expanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 32.dp, end = 16.dp, bottom = 8.dp)) {
                DetailRow(stringResource(R.string.usage_detail_input), formatCount(model.inputTokens))
                DetailRow(stringResource(R.string.usage_detail_output), formatCount(model.outputTokens))
                if (model.cacheReadTokens > 0) DetailRow(stringResource(R.string.usage_label_cache_read), formatCount(model.cacheReadTokens))
                if (model.cacheWriteTokens > 0) DetailRow(stringResource(R.string.usage_label_cache_creation), formatCount(model.cacheWriteTokens))
                val modelTotalInput = model.totalInput
                if (modelTotalInput > 0 && model.cacheReadTokens > 0) {
                    val rate = (model.cacheReadTokens.toDouble() / modelTotalInput) * 100
                    DetailRow(stringResource(R.string.usage_label_cache_hit_rate), String.format("%.1f%%", rate))
                }
                DetailRow(stringResource(R.string.usage_label_cost), formatUsd(model.costUsd))
                val days = model.distinctDays.size
                val sessions = model.distinctSessions.size
                if (days > 0) {
                    DetailRow(stringResource(R.string.usage_detail_daily_avg), formatCount(model.totalTokens / days))
                }
                if (sessions > 0) {
                    DetailRow(stringResource(R.string.usage_detail_session_avg), formatCount(model.totalTokens / sessions))
                }
                DetailRow(stringResource(R.string.usage_detail_sessions), sessions.toString())
                DetailRow(stringResource(R.string.usage_detail_active_days), days.toString())

                // Per-request granularity detail.
                Text(
                    stringResource(R.string.usage_section_requests),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                model.entries.sortedByDescending { it.requestTokens }.forEach { entry ->
                    RequestRow(entry)
                }
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
private fun RequestRow(entry: UsageEntry) {
    val time = remember(entry.createdAt) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entry.createdAt))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            time,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${formatCount(entry.requestTokens)} tok · ${formatUsd(entry.costUsd)}" +
                (if (entry.hasError) " · " + stringResource(R.string.usage_label_failed) else ""),
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.hasError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

// ── Custom date range ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomDateRangeDialog(
    onDismiss: () -> Unit,
    onConfirm: (Long, Long) -> Unit,
) {
    val state = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val s = state.selectedStartDateMillis
                val e = state.selectedEndDateMillis
                if (s != null && e != null) onConfirm(dayStartLocal(s), dayEndLocal(e))
            }) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    ) {
        DateRangePicker(state = state)
    }
}

private fun dayStartLocal(utcMidnight: Long): Long {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val cal = Calendar.getInstance()
    cal.time = sdf.parse(sdf.format(Date(utcMidnight)))!!
    return cal.timeInMillis
}

private fun dayEndLocal(utcMidnight: Long): Long = dayStartLocal(utcMidnight) + 24L * 3600 * 1000 - 1

// ── Pricing editor ────────────────────────────────────────────────────────────

@Composable
private fun ModelPricingDialog(
    modelKey: String,
    displayName: String,
    current: ModelPricingEntity?,
    onDismiss: () -> Unit,
    onSave: (ModelPricingEntity) -> Unit,
    onClear: () -> Unit,
) {
    var input by remember { mutableStateOf((current?.inputPerMillion ?: 0.0).toString()) }
    var output by remember { mutableStateOf((current?.outputPerMillion ?: 0.0).toString()) }
    var cacheRead by remember { mutableStateOf((current?.cacheReadPerMillion ?: 0.0).toString()) }
    var cacheWrite by remember { mutableStateOf((current?.cacheWritePerMillion ?: 0.0).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.usage_pricing_title, displayName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PriceField(stringResource(R.string.usage_pricing_input), input) { input = it }
                PriceField(stringResource(R.string.usage_pricing_output), output) { output = it }
                PriceField(stringResource(R.string.usage_pricing_cache_read), cacheRead) { cacheRead = it }
                PriceField(stringResource(R.string.usage_pricing_cache_write), cacheWrite) { cacheWrite = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(ModelPricingEntity(
                    modelId = modelKey,
                    inputPerMillion = input.toDoubleOrNull() ?: 0.0,
                    outputPerMillion = output.toDoubleOrNull() ?: 0.0,
                    cacheReadPerMillion = cacheRead.toDoubleOrNull() ?: 0.0,
                    cacheWritePerMillion = cacheWrite.toDoubleOrNull() ?: 0.0,
                    createdAt = current?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                ))
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onClear) { Text(stringResource(R.string.usage_pricing_clear)) }
        },
    )
}

@Composable
private fun PriceField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}
