package com.openminis.app.ui.sandbox

import com.openminis.app.ui.settings.SettingsSwitch
import com.openminis.app.R

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.outlined.Javascript
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

// ─── Data Model ──────────────────────────────────────────────────────────────

enum class MirrorCategory(
    val key: String,
    val displayName: String,
    val configPath: String,
) {
    ALPINE("alpine", "Alpine APK", "etc/apk/repositories"),
    PIP("pip", "Python pip", "etc/pip/pip.conf"),
    NPM("npm", "Node.js npm", "root/.npmrc");

    val icon: ImageVector
        get() = when (this) {
            ALPINE -> Icons.Filled.Terrain
            PIP -> Icons.Filled.Inventory2
            NPM -> Icons.Outlined.Javascript
        }

    val iconColor: Color
        get() = when (this) {
            ALPINE -> Color(0xFF007AFF)
            PIP -> Color(0xFF34C759)
            NPM -> Color(0xFFFF3B30)
        }
}

data class MirrorEntry(
    val id: String,
    val name: String,
    val baseURL: String,
    val testURL: String,
    val category: MirrorCategory,
    val region: String,
    val isOfficial: Boolean = false,
)

data class MirrorTestResult(
    val mirror: MirrorEntry,
    val latencyMs: Int?,   // null = failed/timeout
    val error: String? = null,
) {
    val isSuccess: Boolean get() = latencyMs != null
}

// ─── Mirror Definitions ──────────────────────────────────────────────────────

object MirrorCatalog {
    private const val ALPINE_PROBE = "v3.21/main/aarch64/APKINDEX.tar.gz"

    private fun alpine(id: String, name: String, url: String, region: String, official: Boolean = false) =
        MirrorEntry("alpine.$id", name, url, url + ALPINE_PROBE, MirrorCategory.ALPINE, region, official)

    private fun pip(id: String, name: String, url: String, region: String, official: Boolean = false) =
        MirrorEntry("pip.$id", name, url, url, MirrorCategory.PIP, region, official)

    private fun npm(id: String, name: String, url: String, region: String, official: Boolean = false) =
        MirrorEntry("npm.$id", name, url, url, MirrorCategory.NPM, region, official)

    val alpineMirrors = listOf(
        alpine("official", "Official CDN", "https://dl-cdn.alpinelinux.org/alpine/", "Global", official = true),
        alpine("tuna", "Tsinghua TUNA", "https://mirrors.tuna.tsinghua.edu.cn/alpine/", "China"),
        alpine("aliyun", "Alibaba", "https://mirrors.aliyun.com/alpine/", "China"),
        alpine("ustc", "USTC", "https://mirrors.ustc.edu.cn/alpine/", "China"),
        alpine("huawei", "Huawei", "https://repo.huaweicloud.com/alpine/", "China"),
        alpine("tencent", "Tencent", "https://mirrors.cloud.tencent.com/alpine/", "China"),
        alpine("leaseweb", "LEASEWEB UK", "https://mirror.leaseweb.com/alpine/", "Europe"),
        alpine("rwth", "RWTH Germany", "https://ftp.halifax.rwth-aachen.de/alpine/", "Europe"),
        alpine("jaist", "JAIST Japan", "https://ftp.jaist.ac.jp/pub/Linux/alpine/", "Asia"),
        alpine("kakao", "Kakao Korea", "https://mirror.kakao.com/alpine/", "Asia"),
    )

    val pipMirrors = listOf(
        pip("official", "Official PyPI", "https://pypi.org/simple/", "Global", official = true),
        pip("tuna", "Tsinghua TUNA", "https://pypi.tuna.tsinghua.edu.cn/simple/", "China"),
        pip("aliyun", "Alibaba", "https://mirrors.aliyun.com/pypi/simple/", "China"),
        pip("ustc", "USTC", "https://mirrors.ustc.edu.cn/pypi/web/simple/", "China"),
        pip("huawei", "Huawei", "https://repo.huaweicloud.com/repository/pypi/simple/", "China"),
        pip("tencent", "Tencent", "https://mirrors.cloud.tencent.com/pypi/simple/", "China"),
    )

    val npmMirrors = listOf(
        npm("official", "Official npm", "https://registry.npmjs.org/", "Global", official = true),
        npm("npmmirror", "npmmirror", "https://registry.npmmirror.com/", "China"),
        npm("huawei", "Huawei", "https://repo.huaweicloud.com/repository/npm/", "China"),
        npm("tencent", "Tencent", "https://mirrors.cloud.tencent.com/npm/", "China"),
    )

    val allMirrors: List<MirrorEntry> = alpineMirrors + pipMirrors + npmMirrors

    fun mirrors(category: MirrorCategory): List<MirrorEntry> = when (category) {
        MirrorCategory.ALPINE -> alpineMirrors
        MirrorCategory.PIP -> pipMirrors
        MirrorCategory.NPM -> npmMirrors
    }

    fun findByBaseURL(baseURL: String): MirrorEntry? =
        allMirrors.firstOrNull { it.baseURL == baseURL }

    fun findById(id: String): MirrorEntry? =
        allMirrors.firstOrNull { it.id == id }

    fun categoryFromKey(key: String): MirrorCategory? =
        MirrorCategory.entries.firstOrNull { it.key == key }
}

// ─── ViewModel (singleton) ───────────────────────────────────────────────────

object MirrorSpeedTestViewModel {
    private const val TAG = "MirrorSpeedTest"
    private const val PREFS = "mirror_settings"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var currentJob: Job? = null

    // Observable state — Compose observes these Snapshot-backed maps.
    val results: SnapshotStateMap<MirrorCategory, List<MirrorTestResult>> = mutableStateMapOf()
    val selectedMirrorId: SnapshotStateMap<MirrorCategory, String> = mutableStateMapOf()
    val useCustomMirror: SnapshotStateMap<MirrorCategory, Boolean> = mutableStateMapOf()

    var isTesting by mutableStateOf(false)
        private set
    var testProgress by mutableStateOf(0f)
        private set

    @Volatile private var loaded = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = prefs(context)
            for (cat in MirrorCategory.entries) {
                val savedUrl = p.getString("mirror.selected.${cat.key}", null)
                if (savedUrl != null) {
                    val entry = MirrorCatalog.findByBaseURL(savedUrl)
                    if (entry != null) selectedMirrorId[cat] = entry.id
                }
                useCustomMirror[cat] = p.getBoolean("mirror.useCustom.${cat.key}", false)
            }
            loaded = true
        }
    }

    private fun persist(context: Context, category: MirrorCategory) {
        val p = prefs(context)
        val editor = p.edit()
        val mirrorId = selectedMirrorId[category]
        val entry = mirrorId?.let { MirrorCatalog.findById(it) }
        if (entry != null) {
            editor.putString("mirror.selected.${category.key}", entry.baseURL)
        }
        editor.putBoolean("mirror.useCustom.${category.key}", useCustomMirror[category] == true)
        editor.apply()
    }

    // -- Speed Test --

    fun runAllTests(context: Context) {
        currentJob?.cancel()
        currentJob = scope.launch {
            withContext(Dispatchers.Main) {
                isTesting = true
                testProgress = 0f
                results.clear()
            }

            val all = MirrorCatalog.allMirrors
            val total = all.size
            var completed = 0
            val perCategory = mutableMapOf<MirrorCategory, MutableList<MirrorTestResult>>()

            val deferred = all.map { mirror ->
                async { testMirror(mirror) }
            }
            for (d in deferred) {
                val result = d.await()
                completed++
                perCategory.getOrPut(result.mirror.category) { mutableListOf() }.add(result)
                val progress = completed.toFloat() / total.toFloat()
                withContext(Dispatchers.Main) { testProgress = progress }
            }

            val sorted = perCategory.mapValues { (_, list) -> list.sortedWith(latencyComparator) }
            withContext(Dispatchers.Main) {
                results.clear()
                results.putAll(sorted)

                // Auto-select fastest for categories without prior selection
                for (cat in MirrorCategory.entries) {
                    if (selectedMirrorId[cat] == null) {
                        val fastest = sorted[cat]?.firstOrNull { it.isSuccess }
                        if (fastest != null) {
                            selectedMirrorId[cat] = fastest.mirror.id
                            persist(context, cat)
                        }
                    }
                }

                isTesting = false
            }
            Log.i(TAG, "Mirror speed test complete: ${sorted.mapValues { it.value.size }}")
        }
    }

    fun runTest(context: Context, category: MirrorCategory) {
        currentJob?.cancel()
        currentJob = scope.launch {
            withContext(Dispatchers.Main) {
                isTesting = true
                testProgress = 0f
            }
            val mirrors = MirrorCatalog.mirrors(category)
            val total = mirrors.size
            var completed = 0
            val list = mutableListOf<MirrorTestResult>()

            val deferred = mirrors.map { mirror -> async { testMirror(mirror) } }
            val results = deferred.awaitAll()
            for (r in results) {
                completed++
                list.add(r)
                val p = completed.toFloat() / total.toFloat()
                withContext(Dispatchers.Main) { testProgress = p }
            }

            val sorted = list.sortedWith(latencyComparator)
            withContext(Dispatchers.Main) {
                this@MirrorSpeedTestViewModel.results[category] = sorted
                isTesting = false
            }
        }
    }

    private val latencyComparator = Comparator<MirrorTestResult> { a, b ->
        when {
            a.latencyMs != null && b.latencyMs != null -> a.latencyMs.compareTo(b.latencyMs)
            a.latencyMs != null && b.latencyMs == null -> -1
            a.latencyMs == null && b.latencyMs != null -> 1
            else -> 0
        }
    }

    private fun testMirror(mirror: MirrorEntry): MirrorTestResult {
        return try {
            val req = Request.Builder()
                .url(mirror.testURL)
                .head()
                .build()
            val start = System.nanoTime()
            httpClient.newCall(req).execute().use { resp ->
                val elapsedMs = ((System.nanoTime() - start) / 1_000_000L).toInt()
                if (resp.code >= 400) {
                    MirrorTestResult(mirror, null, "HTTP ${resp.code}")
                } else {
                    MirrorTestResult(mirror, elapsedMs, null)
                }
            }
        } catch (e: Exception) {
            MirrorTestResult(mirror, null, e.message ?: e.javaClass.simpleName)
        }
    }

    // -- Selection & Apply --

    fun selectMirror(context: Context, mirror: MirrorEntry) {
        selectedMirrorId[mirror.category] = mirror.id
        persist(context, mirror.category)
        if (useCustomMirror[mirror.category] == true) {
            applyMirror(context, mirror.category)
        }
    }

    fun setUseCustom(context: Context, category: MirrorCategory, enabled: Boolean) {
        useCustomMirror[category] = enabled
        persist(context, category)
        if (enabled) {
            applyMirror(context, category)
        } else {
            restoreOfficial(context, category)
        }
    }

    /**
     * Re-apply the user's selected mirror for every category that has
     * stringResource(R.string.mirror_use_mirror_toggle) enabled. Call this after the default_mount overlay runs on
     * boot — the overlay ships stock config files that would otherwise wipe
     * user-chosen mirrors.
     */
    fun applyAllActiveMirrors(context: Context) {
        ensureLoaded(context)
        for (category in MirrorCategory.entries) {
            if (useCustomMirror[category] == true && selectedMirrorId[category] != null) {
                applyMirror(context, category)
            }
        }
    }

    /**
     * On the first boot after a fresh rootfs install, auto-detect the fastest
     * mirror for each category and apply it. The `rootfs.freshInstall` flag is
     * set by RootfsManager.installIfNeeded() and cleared here.
     */
    fun autoDetectOnceIfNeeded(context: Context) {
        ensureLoaded(context)
        val p = prefs(context)
        if (!p.getBoolean("rootfs.freshInstall", false)) return
        p.edit().remove("rootfs.freshInstall").apply()
        Log.i(TAG, "Fresh rootfs detected — running auto mirror detection")

        scope.launch {
            // Reuse the normal test flow, then await its completion via currentJob.
            runAllTests(context)
            currentJob?.join()
            withContext(Dispatchers.Main) {
                for (cat in MirrorCategory.entries) {
                    val fastest = results[cat]?.firstOrNull { it.isSuccess } ?: continue
                    if (fastest.mirror.isOfficial) continue
                    selectedMirrorId[cat] = fastest.mirror.id
                    useCustomMirror[cat] = true
                    persist(context, cat)
                    applyMirror(context, cat)
                }
                Log.i(TAG, "Auto mirror detection applied")
            }
        }
    }

    fun selectedMirror(category: MirrorCategory): MirrorEntry? {
        val id = selectedMirrorId[category] ?: return null
        return MirrorCatalog.findById(id)
    }

    fun fastestResult(category: MirrorCategory): MirrorTestResult? =
        results[category]?.firstOrNull { it.isSuccess }

    fun isActive(category: MirrorCategory): Boolean =
        useCustomMirror[category] == true && selectedMirrorId[category] != null

    private fun rootfsDataDir(context: Context): File =
        File(context.applicationContext.filesDir, "alpine-rootfs")

    private fun applyMirror(context: Context, category: MirrorCategory) {
        val mirror = selectedMirror(category) ?: return
        val dataDir = rootfsDataDir(context)
        val configFile = File(dataDir, category.configPath)
        val bakFile = File(dataDir, category.configPath + ".bak")

        try {
            if (!bakFile.exists() && configFile.exists()) {
                configFile.copyTo(bakFile, overwrite = false)
                Log.i(TAG, "Backed up ${category.configPath} -> .bak")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to backup ${category.configPath}: ${e.message}")
        }

        val content = when (category) {
            MirrorCategory.ALPINE ->
                "${mirror.baseURL}v3.21/main\n${mirror.baseURL}v3.21/community\n"
            MirrorCategory.PIP -> {
                val host = try { URI(mirror.baseURL).host ?: "" } catch (_: Exception) { "" }
                """
                [global]
                break-system-packages = true
                index-url = ${mirror.baseURL}
                trusted-host = $host

                """.trimIndent()
            }
            MirrorCategory.NPM -> "registry=${mirror.baseURL}\n"
        }

        try {
            configFile.parentFile?.mkdirs()
            configFile.writeText(content)
            Log.i(TAG, "Applied mirror ${mirror.name} to ${category.configPath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write ${category.configPath}: ${e.message}")
        }
    }

    private fun restoreOfficial(context: Context, category: MirrorCategory) {
        val dataDir = rootfsDataDir(context)
        val configFile = File(dataDir, category.configPath)
        val bakFile = File(dataDir, category.configPath + ".bak")

        if (!bakFile.exists()) {
            Log.i(TAG, "No .bak for ${category.configPath}, skipping restore")
            return
        }
        try {
            if (configFile.exists()) configFile.delete()
            bakFile.copyTo(configFile, overwrite = true)
            Log.i(TAG, "Restored ${category.configPath} from .bak")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore ${category.configPath}: ${e.message}")
        }
    }
}

// ─── Views ───────────────────────────────────────────────────────────────────

/**
 * Body-only rows (no section header) for embedding inside an outer
 * `SettingsSection(stringResource(R.string.mirror_mirrors_section)) { ... }` in RootfsManagementScreen.
 * T168 moved the stringResource(R.string.mirror_mirrors_section) title up to the SettingsSection wrapper so
 * the iOS-style inset-grouped card frames *just* the rows.
 */
@Composable
fun MirrorsSectionView(onNavigate: (MirrorCategory) -> Unit) {
    val context = LocalContext.current
    MirrorSpeedTestViewModel.ensureLoaded(context)

    val vm = MirrorSpeedTestViewModel

    ListItem(
        headlineContent = { Text(if (vm.isTesting) stringResource(R.string.mirror_test_speed_testing) else stringResource(R.string.mirror_detect_fast_label)) },
        leadingContent = {
            CircleIconBadge(icon = Icons.Filled.Bolt, tint = Color(0xFFFF9500))
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !vm.isTesting) { vm.runAllTests(context) },
    )

    if (vm.isTesting) {
        LinearProgressIndicator(
            progress = { vm.testProgress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    for (category in MirrorCategory.entries) {
        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
        MirrorCategoryRow(category = category, onClick = { onNavigate(category) })
    }
}

@Composable
private fun MirrorCategoryRow(category: MirrorCategory, onClick: () -> Unit) {
    val vm = MirrorSpeedTestViewModel
    val selected = vm.selectedMirror(category)
    val active = vm.isActive(category)
    val fastest = vm.fastestResult(category)

    ListItem(
        headlineContent = { Text(category.displayName) },
        leadingContent = {
            CircleIconBadge(icon = category.icon, tint = category.iconColor)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active && selected != null) {
                    Text(
                        text = selected.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIfShort(),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (fastest?.latencyMs != null) {
                    LatencyBadge(fastest.latencyMs)
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

private fun Modifier.widthIfShort(): Modifier = this

@Composable
private fun CircleIconBadge(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun LatencyBadge(ms: Int) {
    val color = when {
        ms < 200 -> Color(0xFF34C759)
        ms < 500 -> Color(0xFFFF9500)
        else -> Color(0xFFFF3B30)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = "${ms}ms",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MirrorCategoryDetailScreen(
    category: MirrorCategory,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    MirrorSpeedTestViewModel.ensureLoaded(context)
    val vm = MirrorSpeedTestViewModel
    val scope = rememberCoroutineScope()

    val sortedResults = vm.results[category] ?: emptyList()
    val allForCategory = MirrorCatalog.mirrors(category)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(category.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.mirror_back))
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
            // --- Current section ---
            Text(
                text = stringResource(R.string.mirror_current_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            val selected = vm.selectedMirror(category)
            val useCustomEnabled = selected != null
            ListItem(
                headlineContent = { Text(stringResource(R.string.mirror_use_mirror_toggle)) },
                trailingContent = {
                    SettingsSwitch(
                        checked = vm.useCustomMirror[category] == true,
                        onCheckedChange = { checked ->
                            vm.setUseCustom(context, category, checked)
                        },
                        enabled = useCustomEnabled,
                    )
                },
            )

            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            if (vm.isActive(category) && selected != null) {
                ListItem(
                    headlineContent = {
                        Text(
                            selected.name,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    supportingContent = {
                        Text(
                            selected.baseURL,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            } else {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.mirror_official_default)) },
                    colors = ListItemDefaults.colors(
                        headlineColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }

            // --- Mirrors / Results section ---
            HorizontalDivider()
            Text(
                text = if (sortedResults.isEmpty()) stringResource(R.string.mirror_mirrors_section) else stringResource(R.string.mirror_speed_test_results_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (sortedResults.isEmpty()) {
                allForCategory.forEachIndexed { index, mirror ->
                    MirrorRow(
                        mirror = mirror,
                        latencyMs = null,
                        failed = false,
                        isSelected = vm.selectedMirrorId[category] == mirror.id,
                        onClick = { vm.selectMirror(context, mirror) },
                    )
                    if (index < allForCategory.size - 1) {
                        HorizontalDivider(modifier = Modifier.padding(start = 48.dp))
                    }
                }
            } else {
                sortedResults.forEachIndexed { index, result ->
                    MirrorRow(
                        mirror = result.mirror,
                        latencyMs = result.latencyMs,
                        failed = result.latencyMs == null,
                        isSelected = vm.selectedMirrorId[category] == result.mirror.id,
                        onClick = { vm.selectMirror(context, result.mirror) },
                    )
                    if (index < sortedResults.size - 1) {
                        HorizontalDivider(modifier = Modifier.padding(start = 48.dp))
                    }
                }
            }

            // --- Test button ---
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(if (vm.isTesting) stringResource(R.string.mirror_test_speed_testing) else stringResource(R.string.mirror_test_speed_label)) },
                leadingContent = {
                    CircleIconBadge(icon = Icons.Filled.Bolt, tint = Color(0xFFFF9500))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !vm.isTesting) {
                        scope.launch { vm.runTest(context, category) }
                    },
            )
            if (vm.isTesting) {
                LinearProgressIndicator(
                    progress = { vm.testProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MirrorRow(
    mirror: MirrorEntry,
    latencyMs: Int?,
    failed: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mirror.name, style = MaterialTheme.typography.bodyMedium)
                if (mirror.isOfficial) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            stringResource(R.string.mirror_official_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    mirror.region,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Text(
                mirror.baseURL,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (latencyMs != null) {
            LatencyBadge(latencyMs)
        } else if (failed) {
            Text(
                stringResource(R.string.mirror_timeout_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

