package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton

import android.content.Context
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.pluralStringResource
import com.openminis.app.ui.components.MinisAlertDialog
import android.text.format.Formatter
import android.widget.Toast
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.session.SessionStorage
import com.openminis.app.data.session.SessionTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [T-android-child-session-delete-storage] Size every session, then fold
 * hidden child sessions onto their root parent. Rows are ROOTS only; a child's
 * bytes appear exactly once, under its parent — so the overview total and the
 * list agree, and the user never sees a `Helper · …` session as a separate
 * thing to manage. Pure (given the entity list + a storage) so it is testable.
 */
internal fun aggregateSessionStorage(
    sessions: List<ChatSessionEntity>,
    storage: SessionStorage,
): List<Triple<ChatSessionEntity, Long, Long>> {
    val parentOf: Map<String, String?> = sessions.associate { it.id to it.parentSessionId }
    val minis = sessions.associate { it.id to storage.minisSize(it.id) }
    val media = storage.mediaSizesBySession(sessions.map { it.id }.toSet())
    val minisByRoot = SessionTree.aggregateToRoots(parentOf, minis)
    val mediaByRoot = SessionTree.aggregateToRoots(parentOf, media)
    return sessions
        .filter { SessionTree.rootOf(it.id, parentOf) == it.id }
        .map { Triple(it, minisByRoot[it.id] ?: 0L, mediaByRoot[it.id] ?: 0L) }
}

/**
 * Settings › Storage. [T-android-storage-usage-cache] Everything shown comes
 * from [StorageUsageViewModel], scoped to this destination's back-stack entry:
 * measured once per visit, then updated incrementally. Returning from a
 * session's detail page no longer re-measures anything.
 *
 * Multi-select (like a mail list): the "Select" action in the top bar, or a
 * long-press on a session row, enters it; rows then toggle on tap, the top
 * bar offers Cancel / Select All, and a bottom bar offers Clear Files and
 * Delete for the selection, each behind a confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorageManagementScreen(
    vm: StorageUsageViewModel,
    onBack: () -> Unit,
    onRootfsClick: () -> Unit,
    onSessionClick: (sessionId: String) -> Unit = {},
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val result by vm.result.collectAsState()
    var showClearLogsDialog by remember { mutableStateOf(false) }
    var showBatchClearDialog by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    // Runs each time the list comes back on screen; a no-op unless the rootfs
    // screen was opened in between.
    LaunchedEffect(Unit) { vm.onListShown() }

    // System back leaves selection first, like the home list.
    BackHandler(enabled = state.selecting) { vm.stopSelecting() }

    LaunchedEffect(result) {
        val r = result ?: return@LaunchedEffect
        val res = context.resources
        val message = when (r) {
            is StorageUsageViewModel.BatchResult.Cleared -> {
                val base = res.getQuantityString(
                    R.plurals.storage_batch_clear_result, r.sessions, r.sessions,
                    Formatter.formatFileSize(context, r.freed),
                )
                if (r.failedPaths.isEmpty()) base
                else base + "\n" + context.getString(
                    R.string.storage_clear_partial_failure, r.failedPaths.size, r.failedPaths.first(),
                )
            }
            is StorageUsageViewModel.BatchResult.Deleted -> res.getQuantityString(
                R.plurals.storage_batch_delete_result, r.sessions, r.sessions,
                Formatter.formatFileSize(context, r.freed),
            )
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        vm.consumeResult()
    }

    val allSelected = state.rows.isNotEmpty() && state.selectedIds.containsAll(state.rows.map { it.id })

    SettingsScaffold(
        title = when {
            !state.selecting -> stringResource(R.string.storage_title)
            state.selectedIds.isEmpty() -> stringResource(R.string.sessionlist_select_title)
            else -> stringResource(R.string.sessionlist_n_selected, state.selectedIds.size)
        },
        onBack = onBack,
        navigation = if (state.selecting) {
            { MinisTextButton(onClick = { vm.stopSelecting() }) { Text(stringResource(R.string.cancel)) } }
        } else null,
        actions = {
            if (state.selecting) {
                MinisTextButton(onClick = { vm.toggleSelectAll() }) {
                    Text(
                        stringResource(
                            if (allSelected) R.string.sessionlist_deselect_all else R.string.sessionlist_select_all,
                        ),
                    )
                }
            } else if (!state.isLoading && state.rows.isNotEmpty()) {
                MinisTextButton(onClick = { vm.startSelecting() }) {
                    Text(stringResource(R.string.sessionlist_select_action))
                }
            }
        },
        bottomBar = if (state.selecting) {
            {
                StorageSelectionBar(
                    busy = state.batchBusy,
                    hasSelection = state.selectedIds.isNotEmpty(),
                    canClear = state.selectedSize > 0,
                    onClear = { showBatchClearDialog = true },
                    onDelete = { showBatchDeleteDialog = true },
                )
            }
        } else null,
        // [T-android-storage-usage-cache] The session list can hold thousands
        // of rows (2382 on the test device). As children of the scaffold's
        // scrolling Column they were ALL composed at once: one 7 s frame
        // (Choreographer skipped 418 frames) every time the list appeared,
        // including on the way back from a detail page. A LazyColumn composes
        // only what is on screen, and keyed items make a re-sort a move.
        scrollable = false,
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "overview") {
                SettingsSection(header = stringResource(R.string.storage_section_overview)) {
                    StorageOverviewRow(
                        color = Color(0xFF8E8E93),
                        label = stringResource(R.string.storage_overview_shell),
                        value = Formatter.formatFileSize(context, state.shellSize),
                        onClick = {
                            vm.onRootfsOpened()
                            onRootfsClick()
                        },
                        showDivider = true,
                    )
                    StorageOverviewRow(
                        color = Color(0xFF007AFF),
                        label = stringResource(R.string.storage_overview_database),
                        value = Formatter.formatFileSize(context, state.dbSize),
                        showDivider = true,
                    )
                    StorageOverviewRow(
                        color = Color(0xFF5856D6),
                        label = stringResource(R.string.storage_overview_sessions),
                        value = Formatter.formatFileSize(context, state.totalSessionSize),
                        showDivider = true,
                    )
                    StorageOverviewRow(
                        color = Color(0xFFFF9500),
                        label = stringResource(R.string.storage_overview_logs_caches),
                        value = Formatter.formatFileSize(context, state.logsCachesSize),
                        showDivider = false,
                    )
                }

            }
            item(key = "logs") {
                // [T-storage-clear-logs-caches]
                SettingsSection(footer = stringResource(R.string.storage_clear_logs_caches_footer)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.isClearingLogs) { showClearLogsDialog = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.isClearingLogs) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                stringResource(R.string.storage_clearing_status),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Text(
                                stringResource(R.string.storage_clear_logs_caches_button),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

            }
            item(key = "sessions-header") {
                SettingsSection(header = stringResource(R.string.storage_section_sessions)) {
                    when {
                        state.isLoading -> Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        state.rows.isEmpty() -> Text(
                            stringResource(R.string.storage_no_sessions),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                        // Rows follow as their own lazy items; the card is drawn
                        // per row (rounded at the first and last) so it still reads
                        // as one grouped section.
                        else -> Unit
                    }
                }
            }
            if (!state.isLoading) {
                itemsIndexed(state.rows, key = { _, row -> row.id }) { index, session ->
                    val last = state.rows.size - 1
                    val shape = RoundedCornerShape(
                        topStart = if (index == 0) 14.dp else 0.dp,
                        topEnd = if (index == 0) 14.dp else 0.dp,
                        bottomStart = if (index == last) 14.dp else 0.dp,
                        bottomEnd = if (index == last) 14.dp else 0.dp,
                    )
                    Box(
                        modifier = Modifier
                            .animateItem()
                            .padding(horizontal = 16.dp)
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        StorageSessionRow(
                            title = session.title ?: "Untitled",
                            size = Formatter.formatFileSize(context, session.totalSize),
                            selecting = state.selecting,
                            selected = session.id in state.selectedIds,
                            onClick = {
                                if (state.selecting) vm.toggle(session.id) else onSessionClick(session.id)
                            },
                            onLongClick = {
                                if (state.selecting) vm.toggle(session.id) else vm.startSelecting(session.id)
                            },
                            showDivider = index < last,
                        )
                    }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showClearLogsDialog) {
        AlertDialog(
            onDismissRequest = { showClearLogsDialog = false },
            title = { Text(stringResource(R.string.storage_clear_logs_caches_confirm_title)) },
            text = { Text(stringResource(R.string.storage_clear_logs_caches_confirm_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showClearLogsDialog = false
                    // Only logs & caches change, so only they are re-measured.
                    vm.clearLogsAndCaches()
                }) { Text(stringResource(R.string.storage_clear_logs_caches_confirm)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showClearLogsDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showBatchClearDialog) {
        val n = state.selectedIds.size
        val size = Formatter.formatFileSize(context, state.selectedSize)
        MinisAlertDialog(
            onDismissRequest = { showBatchClearDialog = false },
            title = pluralStringResource(R.plurals.storage_batch_clear_title, n, n),
            text = stringResource(R.string.storage_batch_clear_message, size),
            confirmText = stringResource(R.string.storage_clear_confirm_button, size),
            isDestructive = true,
            onConfirm = {
                showBatchClearDialog = false
                vm.clearSelectedFiles()
            },
        )
    }

    if (showBatchDeleteDialog) {
        val n = state.selectedIds.size
        MinisAlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = stringResource(R.string.sessionlist_delete_n_title, n),
            text = stringResource(
                R.string.storage_batch_delete_message,
                Formatter.formatFileSize(context, state.selectedSize),
            ),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                showBatchDeleteDialog = false
                vm.deleteSelected()
            },
        )
    }
}

/**
 * A session row in the storage list. Outside selection it is the usual value
 * row with a chevron; in selection a leading check mark shows the state and
 * the chevron goes, since tapping toggles instead of navigating.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StorageSessionRow(
    title: String,
    size: String,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    showDivider: Boolean,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Icon(
                    if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                size,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (!selecting) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = if (selecting) 50.dp else 16.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

/** Bottom bar of the selection mode: Clear Files and Delete for the selection. */
@Composable
private fun StorageSelectionBar(
    busy: Boolean,
    hasSelection: Boolean,
    canClear: Boolean,
    onClear: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                Row(
                    modifier = Modifier.weight(1f).padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.storage_clearing_status))
                }
            } else {
                MinisTextButton(
                    onClick = onClear,
                    enabled = hasSelection && canClear,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(R.string.storage_batch_clear_button),
                        color = if (hasSelection && canClear) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
                MinisTextButton(
                    onClick = onDelete,
                    enabled = hasSelection,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(R.string.delete),
                        color = if (hasSelection) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionStorageDetailScreen(
    sessionId: String,
    chatDao: ChatDao,
    onBack: () -> Unit,
    onBrowseFiles: (rootPath: String) -> Unit = {},
    // [T-android-storage-usage-cache] Every measurement this page makes is
    // also the list row's value; handing it back keeps the list current
    // (after a clear, or files deleted in the browser) with no second scan.
    onMeasured: (sessionId: String, minisSize: Long, mediaSize: Long) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var session by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var minisSize by remember { mutableLongStateOf(0L) }
    var mediaSize by remember { mutableLongStateOf(0L) }
    var isClearing by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    // [T-android-child-session-delete-storage] This session plus every hidden
    // child under it: sizes are the subtree's, and "clear" clears them all.
    var memberIds by remember { mutableStateOf(listOf(sessionId)) }

    val storage = remember { SessionStorage(context.filesDir) }
    val sessionsDir = storage.sessionsRoot

    fun reload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                session = chatDao.getSession(sessionId)
                val parentOf = chatDao.listSessions().associate { it.id to it.parentSessionId }
                val members = SessionTree.membersOf(sessionId, parentOf).ifEmpty { listOf(sessionId) }
                memberIds = members
                minisSize = members.sumOf { storage.minisSize(it) }
                mediaSize = storage.mediaSizesBySession(members.toSet()).values.sum()
            }
            onMeasured(sessionId, minisSize, mediaSize)
        }
    }

    LaunchedEffect(Unit) { reload() }

    val totalSize = minisSize + mediaSize
    val hasFiles = totalSize > 0

    SettingsScaffold(title = session?.title ?: "Session", onBack = onBack) {
        SettingsSection(header = stringResource(R.string.storage_section_minis_files)) {
            if (minisSize > 0) {
                SettingsValueRow(
                    title = stringResource(R.string.storage_browse_files),
                    value = Formatter.formatFileSize(context, minisSize),
                    onClick = {
                        onBrowseFiles(File(sessionsDir, sessionId).absolutePath)
                    },
                    valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    showDivider = false,
                )
            } else {
                Text(
                    stringResource(R.string.storage_no_minis_files),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        SettingsSection(header = stringResource(R.string.storage_section_media)) {
            if (mediaSize > 0) {
                SettingsValueRow(
                    title = "Media",
                    value = Formatter.formatFileSize(context, mediaSize),
                    showDivider = false,
                )
            } else {
                Text(
                    stringResource(R.string.storage_no_media_files),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        SettingsSection(
            footer = stringResource(R.string.storage_clear_session_footer),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hasFiles && !isClearing) { showClearDialog = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isClearing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.storage_clearing_status),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        stringResource(R.string.storage_clear_session_button),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (hasFiles) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.weight(1f),
                    )
                    if (hasFiles) {
                        Text(
                            Formatter.formatFileSize(context, totalSize),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.storage_clear_confirm_title)) },
            text = {
                Text("This will delete ${Formatter.formatFileSize(context, totalSize)} of files. This action cannot be undone.")
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    showClearDialog = false
                    isClearing = true
                    scope.launch {
                        // [T-android-storage-delete-feedback] openminis/openminis#375:
                        // this used to discard the result and zero the sizes
                        // unconditionally, so a delete that removed nothing
                        // still looked like it worked. Report what survived,
                        // and re-measure rather than assuming zero.
                        val failed = withContext(Dispatchers.IO) {
                            // Cascade: the root's files and every child's.
                            memberIds.flatMap { storage.deleteFilesDetailed(it).failedPaths }
                        }
                        val remaining = withContext(Dispatchers.IO) {
                            memberIds.sumOf { storage.minisSize(it) } to
                                memberIds.sumOf { storage.mediaSize(it) }
                        }
                        minisSize = remaining.first
                        mediaSize = remaining.second
                        onMeasured(sessionId, remaining.first, remaining.second)
                        isClearing = false
                        if (failed.isNotEmpty()) {
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.storage_clear_partial_failure,
                                    failed.size,
                                    failed.first(),
                                ),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }) {
                    Text(
                        "Clear ${Formatter.formatFileSize(context, totalSize)}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun StorageOverviewRow(
    color: Color,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(21.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showDivider) {
            val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
                    .height(0.5.dp)
                    .background(divider),
            )
        }
    }
}

private fun directorySize(dir: File): Long = SessionStorage.directorySize(dir)

/**
 * [T-storage-clear-logs-caches] What "Logs & Caches" counts and clears.
 *
 * NOT all of cacheDir: this app keeps live state there that must survive —
 * `proot-tmp` (the running shell's /tmp), `pasted_text` (unsent drafts),
 * `share_inbound`, restore/import work dirs, the rootfs backup, rclone config.
 * So the set is an allowlist:
 *   * every log file (daily, crash, exit-info) via AppLogger.clearLogs(),
 *     which also drops the open writer so logging continues;
 *   * regenerable caches, refetched on demand (model lists, models.dev);
 *   * outbound share/export scratch, only entries untouched for an hour, so a
 *     share another app is still reading is not pulled out from under it.
 * The size shown is exactly what clear() removes.
 */
internal object LogsAndCachesCleaner {
    private val REGENERABLE = listOf("models-cache", "models-dev-cache")
    private val TRANSIENT = listOf("share", "shared", "export-staging", "large-messages")
    private const val TRANSIENT_MIN_AGE_MS = 60 * 60 * 1000L

    private fun transientEntries(cacheDir: File, now: Long): List<File> {
        val cutoff = now - TRANSIENT_MIN_AGE_MS
        return TRANSIENT.flatMap { name ->
            File(cacheDir, name).listFiles()?.filter { it.lastModified() < cutoff } ?: emptyList()
        }
    }

    /** Clearable cache bytes under [cacheDir] (logs excluded). */
    fun cachesSize(cacheDir: File, now: Long = System.currentTimeMillis()): Long =
        REGENERABLE.sumOf { directorySize(File(cacheDir, it)) } +
            transientEntries(cacheDir, now).sumOf { if (it.isDirectory) directorySize(it) else it.length() }

    /** Delete the clearable caches under [cacheDir] (logs excluded). */
    fun clearCaches(cacheDir: File, now: Long = System.currentTimeMillis()) {
        REGENERABLE.forEach { File(cacheDir, it).deleteRecursively() }
        transientEntries(cacheDir, now).forEach { it.deleteRecursively() }
    }

    fun size(context: Context): Long =
        com.openminis.app.logging.AppLogger.totalSize() + cachesSize(context.cacheDir)

    fun clear(context: Context) {
        com.openminis.app.logging.AppLogger.clearLogs()
        clearCaches(context.cacheDir)
    }
}
