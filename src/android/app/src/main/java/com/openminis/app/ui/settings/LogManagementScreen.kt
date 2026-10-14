package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.text.BoundedText
import com.openminis.app.ui.components.MinisTextButton

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.openminis.app.ui.components.MinisCenterTopBar
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Log management screen — adopts the SettingsSection/SettingsRow toolkit
 * shared with the rest of Settings (T81). Sections:
 *   - Logging — Enable Logging switch + footer explaining capture
 *   - Log Files — daily files list (each row → LogDetailScreen)
 *   - Storage — Delete All Logs (only when files exist) + total-size footer
 *
 * Mirrors iOS LogManagementView's grouped-card layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogManagementScreen(
    onBack: () -> Unit,
    onLogFileClick: (fileName: String) -> Unit = {},
) {
    val context = LocalContext.current
    // T-logs-perf: capped, separated daily/crash lists. Pre-T-logs-perf the
    // screen called AppLogger.listLogFiles() + totalSize() synchronously on
    // the main thread inside a LaunchedEffect, AND each row called
    // file.length() on every recomposition. With ~14 daily logs + dozens
    // of crash files in long-running installs the directory scan + per-row
    // stats stalled the screen for noticeable seconds. Now: stat once on
    // Dispatchers.IO, cap each kind at 100, render LogFileMeta directly
    // (no further stat calls).
    val dailyLogs = remember { mutableStateListOf<AppLogger.LogFileMeta>() }
    val crashLogs = remember { mutableStateListOf<AppLogger.LogFileMeta>() }
    var totalSize by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    var loggingEnabled by remember { mutableStateOf(AppLogger.isEnabled(context)) }

    // T-config: Logs / Config Changes segmented control. Default to
    // "logs"; a `?tab=config-audit` deep-link query lands users straight
    // on the audit list. Mirrors iOS LogManagementView.
    val initialTab = remember {
        com.openminis.app.deeplink.DeepLinkCoordinator.consumePendingLogsTab() ?: "logs"
    }
    var tab by remember { mutableStateOf(initialTab) }

    val refreshTrigger = remember { mutableStateOf(0) }
    LaunchedEffect(refreshTrigger.value) {
        loading = true
        // Daily logs are named `minis-YYYY-MM-DD.log`. Crash files are
        // `crash-…` (Java/Kotlin) and `native-crash-…` (NDK signal handler);
        // grouped together as one "crash" list so the user sees one
        // chronological stream regardless of which side trapped the fault.
        // 100 of each is far past any reasonable inspection horizon.
        val (daily, crash, total) = withContext(Dispatchers.IO) {
            val d = AppLogger.listLogFileMetas(prefix = "minis-", limit = 100)
            val cJ = AppLogger.listLogFileMetas(prefix = "crash-", limit = 100)
            val cN = AppLogger.listLogFileMetas(prefix = "native-crash-", limit = 100)
            // Merge Java + native crashes, sort newest-first by name (both
            // share the YYYY-MM-DD_HH-mm-ss suffix that sorts cleanly), cap.
            val merged = (cJ + cN).sortedByDescending { it.name }.take(100)
            // totalSize sums only the displayed rows. Older / over-cap files
            // are still on disk but excluded from the cap-100 view; the
            // footer wording matches what the user actually sees here.
            val sum = (d.sumOf { it.sizeBytes }) + (merged.sumOf { it.sizeBytes })
            Triple(d, merged, sum)
        }
        dailyLogs.clear(); dailyLogs.addAll(daily)
        crashLogs.clear(); crashLogs.addAll(crash)
        totalSize = total
        loading = false
    }
    fun refresh() { refreshTrigger.value++ }

    SettingsScaffold(
        title = stringResource(R.string.log_title),
        onBack = null,
        scrollable = false,
    ) {
        // Segmented selector lives outside the scrolling content so the
        // tabs stay visible as the body scrolls.
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SegmentedButton(
                selected = tab == "logs",
                onClick = { tab = "logs" },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text(stringResource(R.string.log_title)) }
            SegmentedButton(
                selected = tab == "config-audit",
                onClick = { tab = "config-audit" },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(R.string.logs_tab_config_changes)) }
        }

        if (tab == "config-audit") {
            // Audit body. Owns its own scrolling.
            ConfigAuditScreen(modifier = Modifier.fillMaxSize())
        } else {
            // Logs body — original layout. The LazyColumn picks up
            // viewport height since the scaffold gave us a Column slot.
            LogsBody(
                context = context,
                dailyLogs = dailyLogs,
                crashLogs = crashLogs,
                totalSize = totalSize,
                loading = loading,
                loggingEnabled = loggingEnabled,
                onToggleLogging = {
                    loggingEnabled = it
                    AppLogger.setEnabled(context, it)
                },
                onLogFileClick = onLogFileClick,
                onDeleteAll = { showDeleteAllConfirm = true },
            )
        }
    }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text(stringResource(R.string.log_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.log_delete_confirm_text, Formatter.formatFileSize(context, totalSize)))
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    AppLogger.clearLogs()
                    refresh()
                    showDeleteAllConfirm = false
                }) {
                    Text(stringResource(R.string.log_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDeleteAllConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** Original logs-tab body, extracted so the segmented selector can switch into the audit view. */
@Composable
private fun LogsBody(
    context: Context,
    dailyLogs: List<AppLogger.LogFileMeta>,
    crashLogs: List<AppLogger.LogFileMeta>,
    totalSize: Long,
    loading: Boolean,
    loggingEnabled: Boolean,
    onToggleLogging: (Boolean) -> Unit,
    onLogFileClick: (fileName: String) -> Unit,
    onDeleteAll: () -> Unit,
) {
    val anyFiles = dailyLogs.isNotEmpty() || crashLogs.isNotEmpty()
    var showContextSnapshot by remember { mutableStateOf(false) }
    var showAnswerVersions by remember { mutableStateOf(false) }
    var selectedVersionFile by remember { mutableStateOf<java.io.File?>(null) }
    var showStreamTraces by remember { mutableStateOf(false) }
    var selectedTraceFile by remember { mutableStateOf<java.io.File?>(null) }
    var streamTraceEnabled by remember {
        mutableStateOf(com.openminis.app.provider.StreamTraceRecorder.enabled)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        // ── Logging ─────────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.log_section_logging),
            footer = stringResource(R.string.log_section_logging_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.log_enable_switch),
                checked = loggingEnabled,
                onCheckedChange = onToggleLogging,
                showDivider = false,
            )
        }

        // [T-stream-trace-live] 轨迹录制开关与回放入口。
        SettingsSection(
            header = "流式轨迹（回放与排障）",
            footer = "录制后可在下方浏览每次会话的 chunk 帧序列，对照改流解析器前后的差异。",
        ) {
            SettingsSwitchRow(
                title = "录制流式轨迹",
                checked = streamTraceEnabled,
                onCheckedChange = { value ->
                    streamTraceEnabled = value
                    com.openminis.app.provider.StreamTraceRecorder.setEnabled(value, context)
                    if (value) com.openminis.app.provider.StreamTraceRecorder.newSession()
                },
                showDivider = true,
            )
            SettingsRow(
                title = "浏览轨迹回放",
                subtitle = "offloads/stream-traces/ 下的录制文件（每行一个 chunk）",
                onClick = { showStreamTraces = true },
                showDivider = false,
            )
        }

        if (showStreamTraces) {
            // [T-android-sessionlist-prefs-io] listFiles() plus a lastModified()
            // stat for every candidate is disk IO, and it used to run in the
            // composition body the instant the sheet opened. Empty first frame,
            // real list from Dispatchers.IO.
            var traceFiles by remember { mutableStateOf<List<File>>(emptyList()) }
            var traceFilesLoaded by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                traceFiles = withContext(Dispatchers.IO) {
                    val tracesDir = com.openminis.app.provider.StreamTraceRecorder.dir(context)
                    tracesDir.listFiles()
                        ?.sortedByDescending { it.lastModified() }?.take(30)
                        ?: emptyList()
                }
                traceFilesLoaded = true
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showStreamTraces = false },
                title = { Text("轨迹回放（最近 30 条）") },
                text = {
                    if (!traceFilesLoaded) {
                        CircularProgressIndicator()
                    } else if (traceFiles.isEmpty()) {
                        Text("暂无轨迹。打开「录制流式轨迹」后发一次消息即开始录制。")
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            traceFiles.forEach { f ->
                                TextButton(onClick = { selectedTraceFile = f }) {
                                    Text(
                                        f.name,
                                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showStreamTraces = false }) { Text("关闭") }
                },
            )
        }

        selectedTraceFile?.let { f ->
            // [T-android-sessionlist-prefs-io] readLines() + JSON parsing of up
            // to 400 chunk frames per open, previously a `remember(f)` in the
            // dialog body — i.e. on the main thread inside a Compose frame.
            var traceBody by remember(f) { mutableStateOf<String?>(null) }
            LaunchedEffect(f) {
                traceBody = withContext(Dispatchers.IO) {
                    runCatching {
                        f.readLines()
                            .takeLast(400)
                            .joinToString("\n") { line ->
                                runCatching {
                                    val obj = org.json.JSONObject(line)
                                    when (obj.optString("kind")) {
                                        "text" -> "text: ${obj.optString("text").take(120)}"
                                        "thinking" -> "thinking: ${obj.optString("text").take(120)}"
                                        "reasoning" -> "reasoning: ${obj.optString("content").take(120)}"
                                        "tool_use_start" -> "tool_use_start: ${obj.optString("name")}"
                                        "tool_call_complete" -> "tool_call_complete: ${obj.optString("name")}"
                                        "finished" -> "finished: ${obj.optString("stopReason")}"
                                        else -> obj.optString("kind")
                                    }
                                }.getOrDefault(line.take(120))
                            }
                    }.getOrDefault("读取失败")
                }
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { selectedTraceFile = null },
                title = { Text(f.name) },
                text = {
                    val body = traceBody
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (body == null) {
                            CircularProgressIndicator()
                        } else {
                            Text(
                                body,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedTraceFile = null }) { Text("关闭") }
                },
            )
        }

        // [T-context-assembly-preview] 上下文组装快照入口：最近一次发给模型
        // 的 system prompt 构成明细+全文，排障"模型实际收到什么"。
        SettingsSection(header = "上下文组装快照") {
            SettingsRow(
                title = "查看最近一次上下文组装",
                subtitle = "按来源列出大小与全文（offloads/context-assembly/latest.md）",
                onClick = { showContextSnapshot = true },
                showDivider = false,
            )
        }

        // [T-answer-versions] 被重试覆盖的旧回答归档入口：不丢数据的第二份保险。
        SettingsSection(header = "历史回答版本归档") {
            SettingsRow(
                title = "浏览被覆盖的旧回答",
                subtitle = "offloads/answer-versions/ 下重试前归档的上一版回答",
                onClick = { showAnswerVersions = true },
                showDivider = false,
            )
        }

        if (showAnswerVersions) {
            // [T-android-sessionlist-prefs-io] Directory scan off the
            // composition body — same reasoning as the trace list above.
            var versionFiles by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
            var versionFilesLoaded by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                versionFiles = withContext(Dispatchers.IO) {
                    val versionsDir = java.io.File("/var/minis/workspace/offloads/answer-versions")
                    versionsDir.listFiles()
                        ?.sortedByDescending { it.lastModified() }?.take(30)
                        ?: emptyList()
                }
                versionFilesLoaded = true
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showAnswerVersions = false },
                title = { Text("旧回答归档（最近 30 条）") },
                text = {
                    if (!versionFilesLoaded) {
                        CircularProgressIndicator()
                    } else if (versionFiles.isEmpty()) {
                        Text("暂无归档。重试某条回答后，被覆盖的上一版会出现在这里。")
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            versionFiles.forEach { f ->
                                TextButton(onClick = { selectedVersionFile = f }) {
                                    Text(
                                        f.name,
                                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAnswerVersions = false }) { Text("关闭") }
                },
            )
        }

        selectedVersionFile?.let { f ->
            // [T-android-sessionlist-prefs-io] The old code read the file TWICE
            // (once for the JSON probe, once for the fallback) inside a
            // `remember(f)` in the dialog body — a whole-archive readText on the
            // main thread, in composition. Now one read, on IO.
            var versionBody by remember(f) { mutableStateOf<String?>(null) }
            LaunchedEffect(f) {
                versionBody = withContext(Dispatchers.IO) {
                    runCatching {
                        val raw = f.readText()
                        org.json.JSONObject(raw).optString("partsJson", raw).take(3_000)
                    }.getOrDefault("读取失败")
                }
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { selectedVersionFile = null },
                title = { Text(f.name) },
                text = {
                    val body = versionBody
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (body == null) {
                            CircularProgressIndicator()
                        } else {
                            Text(
                                body,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedVersionFile = null }) { Text("关闭") }
                },
            )
        }

        if (showContextSnapshot) {
            val snapshot = com.openminis.app.agent.ContextAssemblySnapshot.latest
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showContextSnapshot = false },
                title = { Text("上下文组装快照") },
                text = {
                    val body = snapshot?.let { s ->
                        buildString {
                            append("总字符：").append(s.totalChars).append("\n\n")
                            s.sections.forEach { (label, len) ->
                                append("• ").append(len).append(" 字符 — ").append(label).append("\n")
                            }
                            append("\n（全文见 offloads/context-assembly/latest.md）")
                        }
                    } ?: "还没有捕获过上下文组装（发一次消息后生成）。"
                    androidx.compose.foundation.rememberScrollState().let { scroll ->
                        Text(
                            text = body,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(scroll),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showContextSnapshot = false }) { Text("关闭") }
                },
            )
        }

        // While the IO load is in flight (single frame in practice, but the
        // dir scan + 100×stat can spike on slow eMMC), show a centered
        // spinner instead of the empty-state row — otherwise the screen
        // briefly reads as "no logs yet" before the real list pops in.
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Column
        }

        // ── Log Files (daily) ───────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.log_section_files),
            footer = stringResource(R.string.log_section_files_footer),
        ) {
            if (dailyLogs.isEmpty()) {
                SettingsValueRow(
                    title = stringResource(R.string.log_no_files),
                    value = "",
                    valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    showDivider = false,
                )
            } else {
                dailyLogs.forEachIndexed { index, meta ->
                    SettingsValueRow(
                        title = meta.name,
                        value = Formatter.formatFileSize(context, meta.sizeBytes),
                        onClick = { onLogFileClick(meta.name) },
                        showDivider = index < dailyLogs.size - 1,
                    )
                }
            }
        }

        // ── Crash Logs (T283) — only show when present ──────────────────
        if (crashLogs.isNotEmpty()) {
            SettingsSection(
                header = stringResource(R.string.log_crash_header),
                footer = stringResource(R.string.log_crash_footer, crashLogs.size),
            ) {
                crashLogs.forEachIndexed { index, meta ->
                    SettingsValueRow(
                        title = meta.name,
                        value = Formatter.formatFileSize(context, meta.sizeBytes),
                        onClick = { onLogFileClick(meta.name) },
                        showDivider = index < crashLogs.size - 1,
                    )
                }
            }
        }

        // ── Storage / Delete All (only when files exist) ───────────────
        if (anyFiles) {
            SettingsSection(
                header = stringResource(R.string.log_section_storage),
                footer = stringResource(R.string.log_section_storage_footer, Formatter.formatFileSize(context, totalSize)),
            ) {
                SettingsRow(
                    title = stringResource(R.string.log_delete_all_title),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = onDeleteAll,
                    showChevron = false,
                    showDivider = false,
                    trailing = {
                        Text(
                            Formatter.formatFileSize(context, totalSize),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─── Log Detail Screen ─────────────────────────────────────────────────────────

/**
 * T176: lazy line-index loader for the log detail viewer. The previous
 * `file.readText()` approach allocated the entire file as a single
 * String + a single `Text` Composable, which ANR'd / OOM'd at the user's
 * 33MB log. Strategy:
 *
 *  1. One pass through the file to record byte-offset of every newline.
 *     Yields a `LongArray` of ~30-50 K entries for a 33MB log
 *     (≈ 4MB heap), well below any practical limit.
 *  2. `readLine(i)` seeks to `offsets[i]` and reads a single line on
 *     demand. Cached `RandomAccessFile` so each lookup is one I/O.
 *  3. `LazyColumn` renders only viewport-visible rows + overscan, so
 *     scroll is bounded by the visible rows count, not the file size.
 *  4. `DisposableEffect.onDispose` closes the RAF when the user leaves
 *     the screen so we don't leak descriptors across navigations.
 *
 * Trade-off: the indexing pass IS a full file read, but it's
 * sequential bytewise and runs on Dispatchers.IO — measured ~200-400 ms
 * for 33MB on Pixel 4a, vs the old `readText()` path which never
 * recovered.
 */
private class LazyLogFile private constructor(
    private val raf: RandomAccessFile,
    val lineOffsets: LongArray,
) {
    val lineCount: Int get() = lineOffsets.size

    @Synchronized
    fun readLine(index: Int): String {
        if (index !in 0 until lineCount) return ""
        return try {
            raf.seek(lineOffsets[index])
            // RAF.readLine reads bytes, so non-ASCII (CJK) gets mangled
            // — log files are ASCII timestamp + UTF-8 message bodies,
            // so read raw bytes up to the next newline and decode UTF-8.
            val buf = ByteArray(8 * 1024)
            val sb = StringBuilder()
            while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                var done = false
                for (i in 0 until n) {
                    if (buf[i] == '\n'.code.toByte()) {
                        sb.append(String(buf, 0, i, Charsets.UTF_8))
                        done = true
                        break
                    }
                }
                if (done) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    fun close() = try { raf.close() } catch (_: Exception) {}

    companion object {
        fun open(file: File): LazyLogFile {
            val raf = RandomAccessFile(file, "r")
            // First-line offset is always 0; subsequent offsets sit
            // immediately after each newline. Dynamic LongArray growth
            // via mutableList is fine — 30-50 K Long boxings is a few MB
            // of churn that the GC handles easily.
            val offsets = mutableListOf(0L)
            val buf = ByteArray(64 * 1024)
            var pos = 0L
            while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                for (i in 0 until n) {
                    if (buf[i] == '\n'.code.toByte()) {
                        offsets.add(pos + i + 1)
                    }
                }
                pos += n
            }
            // Drop a trailing empty offset if the file ends with \n
            // (the last "line" past the final newline is empty, but
            // logs typically end with a newline so this prevents
            // showing a blank ghost row at the bottom).
            val arr = if (offsets.size > 1 && offsets.last() >= raf.length()) {
                offsets.dropLast(1).toLongArray()
            } else {
                offsets.toLongArray()
            }
            // Reset cursor — readLine will seek per call.
            raf.seek(0)
            return LazyLogFile(raf, arr)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogDetailScreen(
    fileName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var lazyLog by remember(fileName) { mutableStateOf<LazyLogFile?>(null) }
    var loadError by remember(fileName) { mutableStateOf<String?>(null) }
    var loading by remember(fileName) { mutableStateOf(true) }

    LaunchedEffect(fileName) {
        loading = true
        loadError = null
        lazyLog = withContext(Dispatchers.IO) {
            try {
                val file = File(File(context.filesDir, "logs"), fileName)
                if (!file.exists()) {
                    loadError = context.getString(R.string.log_not_found)
                    null
                } else {
                    LazyLogFile.open(file)
                }
            } catch (e: Exception) {
                loadError = context.getString(R.string.log_error_reading, e.message ?: "")
                null
            }
        }
        loading = false
    }

    DisposableEffect(fileName) {
        onDispose { lazyLog?.close() }
    }

    Scaffold(
        topBar = {
            MinisCenterTopBar(
                title = { Text(fileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val file = File(File(context.filesDir, "logs"), fileName)
                        if (file.exists()) shareLogFile(context, file)
                    }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.common_share))
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            loadError != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        loadError ?: "",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            lazyLog != null -> {
                val log = lazyLog!!
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    items(log.lineCount) { i ->
                        Text(
                            text = log.readLine(i),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

// ─── Share Utilities ───────────────────────────────────────────────────────────

private fun shareLogFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            // Clip carries the URI in a way that downstream chooser
            // hosts can introspect for the grant when EXTRA_STREAM
            // alone isn't enough (Issue #17 — SecurityException
            // "Permission Denial: opening provider … requires the
            // provider be exported, or grantUriPermission()").
            clipData = android.content.ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.log_share_chooser))
        // The chooser itself starts the receiver; on some OEM builds
        // the grant flag has to live on the outer intent too or the
        // receiver hits SecurityException reading the content://
        // FileProvider URI.
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    } catch (e: Exception) {
        // Log the FileProvider failure — earlier this catch silently dropped
        // the exception and fell back to EXTRA_TEXT, masking config bugs
        // (e.g. a missing files-path root) as "share works but pastes the
        // whole file as text body" UX. Now any future regression is one
        // logcat line away from being root-caused.
        AppLogger.warning(
            "LogShare",
            "FileProvider share failed for ${file.name}: ${e.message} — falling back to EXTRA_TEXT",
        )
        val text = try { BoundedText.readFileTail(file, 100_000) } catch (_: Exception) { return }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.log_share_chooser)))
    }
}

