package com.openminis.app.ui.scheduled

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.settings.SettingsSwitch
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-android-scheduled-tasks-design / T-android-scheduled-tasks-run-records]
 * List + manage scheduled tasks. Entry point from SessionListScreen's
 * TopAppBar; tap a row to edit, FAB to create, switch to enable/disable.
 * Long-press opens a menu: Edit / Run records / Delete (with confirm).
 * The row no longer shows a result preview — execution history lives in
 * the Run records screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTasksScreen(
    onBack: () -> Unit,
    onEditTask: (taskId: String?) -> Unit,
    onViewRuns: (taskId: String) -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
) {
    val context = LocalContext.current
    val vm: ScheduledTasksViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = ScheduledTasksViewModel.factory(context),
    )
    val tasks by vm.tasks.collectAsState()
    var pendingDelete by remember { mutableStateOf<ScheduledTask?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.scheduled_tasks_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEditTask(null) }, shape = CircleShape) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.scheduled_task_new))
            }
        },
    ) { padding ->
        if (tasks.isEmpty()) {
            EmptyState(padding)
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(tasks, key = { it.id }) { task ->
                ScheduledTaskRow(
                    task = task,
                    onClick = { onEditTask(task.id) },
                    onToggle = { vm.setEnabled(task.id, it) },
                    onEdit = { onEditTask(task.id) },
                    onViewRuns = { onViewRuns(task.id) },
                    onDelete = { pendingDelete = task },
                )
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.scheduled_task_delete_title)) },
            text = { Text(stringResource(R.string.scheduled_task_delete_body, toDelete.label)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.delete(toDelete.id)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.scheduled_task_delete_confirm))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.scheduled_tasks_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScheduledTaskRow(
    task: ScheduledTask,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onViewRuns: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(task.id) { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (task.enabled)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = if (task.enabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.label.ifBlank { task.prompt.take(40) },
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatScheduleSummary(task),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            SettingsSwitch(checked = task.enabled, onCheckedChange = onToggle)
        }

        // [T-android-scheduled-tasks-run-records] Long-press menu: Edit / Run
        // records / Delete (delete is confirmed by the caller's dialog).
        com.openminis.app.ui.components.MinisMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.scheduled_task_menu_edit)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = { menuExpanded = false; onEdit() },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.scheduled_task_menu_runs)) },
                leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                onClick = { menuExpanded = false; onViewRuns() },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.scheduled_task_menu_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { menuExpanded = false; onDelete() },
            )
        }
    }
}

internal fun formatScheduleSummary(task: ScheduledTask): String {
    val time = "%02d:%02d".format(task.timeOfDayHour, task.timeOfDayMinute)
    val repeat = when (task.repeatMode) {
        ScheduledRepeatMode.ONCE -> "Once"
        ScheduledRepeatMode.DAILY -> "Daily"
        ScheduledRepeatMode.WEEKDAYS -> "Weekdays"
        ScheduledRepeatMode.CUSTOM -> {
            val days = task.customDays.sorted().joinToString(",") { dowShort(it) }
            if (days.isBlank()) "Custom" else days
        }
    }
    val next = task.nextTriggerMs()?.let {
        val sdf = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
        " · next ${sdf.format(Date(it))}"
    } ?: ""
    return "$repeat $time$next"
}

private fun dowShort(dow: Int): String = when (dow) {
    java.util.Calendar.SUNDAY -> "Sun"
    java.util.Calendar.MONDAY -> "Mon"
    java.util.Calendar.TUESDAY -> "Tue"
    java.util.Calendar.WEDNESDAY -> "Wed"
    java.util.Calendar.THURSDAY -> "Thu"
    java.util.Calendar.FRIDAY -> "Fri"
    java.util.Calendar.SATURDAY -> "Sat"
    else -> ""
}
