package com.openminis.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskDetailModel
import com.openminis.app.scheduled.ScheduledTaskDetailModel.FireState
import com.openminis.app.scheduled.ScheduledTaskDetailModel.Status
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledTaskMarker
import com.openminis.app.ui.components.decorativePhase
import com.openminis.app.ui.components.decorativePingPong
import com.openminis.app.ui.components.rememberDecorativeTick
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val StatusGreen = Color(0xFF34C759)
private val StatusRed = Color(0xFFFF3B30)

/**
 * [T-scheduled-task-detail] The page a scheduled-task card opens: the task's
 * parameters, progress, prompt and every fire it has run, in the card's amber.
 *
 * Shared design with iOS. Everything
 * shown is derived by [ScheduledTaskDetailModel]; this file only lays it out.
 * The task is read live from [com.openminis.app.scheduled.ScheduledTaskStore],
 * so a fire completing or a cancel elsewhere updates the page in place.
 *
 * @param hostSessionId the chat the card was tapped in — decides "this chat"
 *   wording and which history rows link to another chat. Null in read-only
 *   transcripts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduledTaskDetailSheet(
    marker: ScheduledTaskMarker,
    hostSessionId: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val manager = remember { ScheduledTaskManager(context.applicationContext) }
    val tasksFlow = remember { manager.store().observe() }
    val tasks by tasksFlow.collectAsState(initial = remember { manager.store().all() })
    val task = tasks.firstOrNull { it.id == marker.taskId }

    // Whether the chat this card lives in is processing right now: with the
    // fire not yet recorded, that means this fire is still running.
    val hostVm = remember(hostSessionId) { hostSessionId?.let { ChatViewModelStore.existing(it) } }
    val hostStreaming = hostVm?.isStreaming?.collectAsState()?.value == true

    // One-second clock for the countdown; only runs while the page is open.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
    }

    val fireRunning = ScheduledTaskDetailModel.isFireRunning(task, marker, hostStreaming)
    val status = ScheduledTaskDetailModel.status(task, fireRunning, now)
    val progress = ScheduledTaskDetailModel.progress(task, marker, fireRunning, now)
    val history = ScheduledTaskDetailModel.history(task, marker, hostSessionId, fireRunning)
    val prompt = ScheduledTaskDetailModel.cleanPrompt(task, marker)
    val name = task?.label?.ifBlank { null } ?: marker.label.ifBlank { null } ?: prompt.take(40)
    val phrases = triggerPhrases()
    val trigger = task?.let {
        ScheduledTaskDetailModel.triggerText(
            it, phrases, ::formatHm,
            formatDate = { ms -> formatDay(context, ms) },
            // [T-android-scheduled-triggers] Name the task an on-completion
            // trigger waits for; an agent job has no row and shows its id.
            upstreamName = { id -> scheduledTaskLabel(context, id) },
        )
    }

    var confirmingCancel by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // A fixed-height sheet with the nav bar pinned above a scrolling body, as
    // ChatToolDetailUI does. Sized to its content, a long page grew to the
    // full window: the sheet slid under the status bar (the title and close
    // button collided with the clock and icons) and the nav bar scrolled away
    // with the cards. iOS pins the bar the same way.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ChatColors.background,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            // Nav bar: close on the left, centred title.
            Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp)) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.scheduled_detail_close), tint = ChatColors.primaryText)
                }
                Text(
                    stringResource(R.string.scheduled_detail_title),
                    modifier = Modifier.align(Alignment.Center),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.primaryText,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HeaderCard(name = name, trigger = trigger, status = status)

                if (status == Status.DELETED) {
                    Text(
                        stringResource(R.string.scheduled_detail_deleted_note),
                        fontSize = 12.sp,
                        color = ChatColors.secondaryText,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                if (progress.visible) ProgressCard(progress, now)

                if (task != null) {
                    ParametersCard(task, name, trigger, hostSessionId, progress.firesSoFar)
                }

                PromptCard(prompt)

                HistoryCard(history)

                // Actions
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (task != null && task.enabled) {
                        TextButton(onClick = { confirmingCancel = true }) {
                            Text(stringResource(R.string.scheduled_card_cancel), color = StatusRed, fontSize = 15.sp)
                        }
                    }
                    TextButton(onClick = { copy(context, prompt) }) {
                        Text(stringResource(R.string.scheduled_detail_copy_prompt), color = ScheduledAccent, fontSize = 15.sp)
                    }
                }
            }
        }
    }

    if (confirmingCancel) {
        AlertDialog(
            onDismissRequest = { confirmingCancel = false },
            title = { Text(stringResource(R.string.scheduled_card_cancel_confirm_title)) },
            text = { Text(stringResource(R.string.scheduled_card_cancel_confirm_body, name)) },
            confirmButton = {
                TextButton(onClick = {
                    // Disable rather than delete, as the card does: the history
                    // stays, and the task can be re-enabled from Settings.
                    manager.setEnabled(marker.taskId, false)
                    confirmingCancel = false
                }) { Text(stringResource(R.string.scheduled_card_cancel_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingCancel = false }) {
                    Text(stringResource(R.string.scheduled_card_cancel_confirm_keep))
                }
            },
        )
    }
}

// ── cards ────────────────────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String? = null, badge: String? = null, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ChatColors.secondaryBg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ChatColors.secondaryText)
                if (badge != null) {
                    Text(
                        badge,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ScheduledAccent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(ScheduledAccent.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
        content()
    }
}

@Composable
private fun HeaderCard(name: String, trigger: String?, status: Status) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(ScheduledAccent.copy(alpha = 0.15f))
                    .border(0.8.dp, ScheduledAccent.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = ScheduledAccent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = ChatColors.primaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (trigger != null) {
                    Text(trigger, fontSize = 12.sp, color = ChatColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(status)
        }
    }
}

@Composable
private fun StatusPill(status: Status) {
    val (label, color) = when (status) {
        Status.RUNNING -> stringResource(R.string.scheduled_status_running) to ScheduledAccent
        Status.WAITING -> stringResource(R.string.scheduled_status_waiting) to ScheduledAccent
        Status.COMPLETED -> stringResource(R.string.scheduled_status_completed) to StatusGreen
        Status.FAILED -> stringResource(R.string.scheduled_status_failed) to StatusRed
        Status.DISABLED -> stringResource(R.string.scheduled_status_disabled) to ChatColors.secondaryText
        Status.DELETED -> stringResource(R.string.scheduled_status_deleted) to ChatColors.secondaryText
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (status == Status.RUNNING) PulsingDot(color) else Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

/** Pulse driven by the shared 30 fps decorative clock, read in the layer. */
@Composable
private fun PulsingDot(color: Color) {
    val tick = rememberDecorativeTick()
    Box(
        Modifier
            .size(6.dp)
            .graphicsLayer { alpha = 0.35f + 0.65f * decorativePingPong(decorativePhase(tick.value, 1400)) }
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun ProgressCard(p: ScheduledTaskDetailModel.Progress, now: Long) {
    val context = LocalContext.current
    SectionCard {
        val idx = p.currentIndex
        if (idx != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    stringResource(R.string.scheduled_detail_run_n, idx),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = ChatColors.primaryText,
                )
                if (p.total != null) {
                    Text(
                        stringResource(R.string.scheduled_detail_of_total, p.total),
                        fontSize = 14.sp,
                        color = ChatColors.secondaryText,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
        }
        val total = p.total
        if (total != null && total > 0) {
            val done = (p.firesSoFar.coerceAtMost(total)).toFloat() / total
            LinearProgressIndicator(
                progress = { done },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = ScheduledAccent,
                trackColor = ScheduledAccent.copy(alpha = 0.15f),
            )
        }
        val next = p.nextFireAt
        LabeledRow(
            stringResource(R.string.scheduled_detail_next_run),
            if (next == null) "—" else "${formatFireTime(context, next, now)} · ${relative(context, next - now)}",
        )
        LabeledRow(
            stringResource(R.string.scheduled_detail_remaining),
            when (val r = p.remaining) {
                null -> stringResource(R.string.scheduled_detail_unlimited)
                else -> stringResource(R.string.scheduled_detail_remaining_n, r)
            },
        )
    }
}

@Composable
private fun ParametersCard(task: ScheduledTask, name: String, trigger: String?, hostSessionId: String?, firesSoFar: Int) {
    val context = LocalContext.current
    SectionCard(title = stringResource(R.string.scheduled_detail_params)) {
        LabeledRow(stringResource(R.string.scheduled_detail_name), name)
        if (trigger != null) LabeledRow(stringResource(R.string.scheduled_detail_trigger), trigger)
        LabeledRow(stringResource(R.string.scheduled_detail_delivery), deliveryText(task, hostSessionId))
        task.prefillToolCall?.shellCommand?.let { cmd ->
            LabeledRow(stringResource(R.string.scheduled_detail_preset), cmd, monospace = true, maxLines = 3)
        }
        LabeledRow(stringResource(R.string.scheduled_detail_fired_count), firesSoFar.toString())
        LabeledRow(stringResource(R.string.scheduled_detail_created), formatFireTime(context, task.createdAt, System.currentTimeMillis()))
        LabeledRow(
            stringResource(R.string.scheduled_detail_task_id),
            task.id.take(8),
            monospace = true,
            onClick = { copy(context, task.id) },
        )
    }
}

@Composable
private fun deliveryText(task: ScheduledTask, hostSessionId: String?): String =
    when (val d = ScheduledTaskDetailModel.delivery(task.targetMode, hostSessionId)) {
        ScheduledTaskDetailModel.Delivery.NewSession -> stringResource(R.string.scheduled_delivery_new)
        ScheduledTaskDetailModel.Delivery.ThisChat -> stringResource(R.string.scheduled_delivery_this_chat)
        is ScheduledTaskDetailModel.Delivery.OtherChat ->
            stringResource(R.string.scheduled_delivery_other_chat, sessionTitle(d.sessionId))
        is ScheduledTaskDetailModel.Delivery.Rerun ->
            if (d.inThisChat) stringResource(R.string.scheduled_delivery_rerun_this)
            else stringResource(R.string.scheduled_delivery_rerun_other, sessionTitle(d.sessionId))
        ScheduledTaskDetailModel.Delivery.ChildOfThisChat -> stringResource(R.string.scheduled_delivery_child_this)
        is ScheduledTaskDetailModel.Delivery.ChildOfOtherChat ->
            stringResource(R.string.scheduled_delivery_child_other, sessionTitle(d.sessionId))
    }

/** A chat's title, loaded off the main thread; its short id until then. */
@Composable
private fun sessionTitle(sessionId: String): String {
    val context = LocalContext.current
    val title by produceState(initialValue = sessionId.take(8), sessionId) {
        val app = context.applicationContext as? com.openminis.app.MinisApp ?: return@produceState
        val loaded = withContext(Dispatchers.IO) {
            runCatching { app.chatRepository.getSession(sessionId)?.title }.getOrNull()
        }
        if (!loaded.isNullOrBlank()) value = loaded
    }
    return title
}

@Composable
private fun PromptCard(prompt: String) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    SectionCard(title = stringResource(R.string.scheduled_detail_prompt)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                prompt,
                fontSize = 14.sp,
                color = ChatColors.primaryText,
                maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { copy(context, prompt) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.scheduled_detail_copy_prompt),
                    tint = ChatColors.secondaryText,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (overflows || expanded) {
            Text(
                stringResource(if (expanded) R.string.scheduled_detail_collapse else R.string.scheduled_detail_expand),
                fontSize = 13.sp,
                color = ScheduledAccent,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun HistoryCard(rows: List<ScheduledTaskDetailModel.FireRow>) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    SectionCard(
        title = stringResource(R.string.scheduled_detail_history),
        badge = rows.size.takeIf { it > 0 }?.toString(),
    ) {
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.scheduled_detail_history_empty),
                fontSize = 13.sp,
                color = ChatColors.secondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            return@SectionCard
        }
        rows.forEach { row ->
            val sid = row.sessionId
            val opens = row.inOtherSession && sid != null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (opens) Modifier.clickable { openSession(context, sid!!) } else Modifier)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (row.state) {
                    FireState.OK -> Icon(Icons.Default.CheckCircle, null, tint = StatusGreen, modifier = Modifier.size(16.dp))
                    FireState.FAILED -> Icon(Icons.Default.Error, null, tint = StatusRed, modifier = Modifier.size(16.dp))
                    FireState.RUNNING -> Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { PulsingDot(ScheduledAccent) }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.scheduled_detail_run_n, row.index),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = ChatColors.primaryText,
                    )
                    Text(formatFireTime(context, row.firedAt, now), fontSize = 12.sp, color = ChatColors.secondaryText)
                    row.preview?.takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 12.sp, color = ChatColors.tertiaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (row.isCurrent) {
                    Text(
                        stringResource(R.string.scheduled_detail_current),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ScheduledAccent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(ScheduledAccent.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                if (opens) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.scheduled_detail_open_chat),
                        tint = ChatColors.secondaryText,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    maxLines: Int = 2,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, fontSize = 13.sp, color = ChatColors.secondaryText, modifier = Modifier.width(96.dp))
        Text(
            value,
            fontSize = 13.sp,
            color = ChatColors.primaryText,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── formatting ───────────────────────────────────────────────────────────

/** [T-android-scheduled-triggers] A scheduled task's label, or null. */
internal fun scheduledTaskLabel(context: Context, id: String): String? =
    runCatching { com.openminis.app.scheduled.ScheduledTaskManager(context).get(id)?.label?.ifBlank { null } }
        .getOrNull()

// internal: the task editor shows the same trigger line for tasks it cannot edit.
@Composable
internal fun triggerPhrases(): ScheduledTaskDetailModel.TriggerPhrases {
    val locale = Locale.getDefault()
    val shortDays = remember(locale) { DateFormatSymbols(locale).shortWeekdays }
    return ScheduledTaskDetailModel.TriggerPhrases(
        once = stringResource(R.string.scheduled_trigger_once),
        daily = stringResource(R.string.scheduled_trigger_daily),
        weekdays = stringResource(R.string.scheduled_trigger_weekdays),
        weekly = stringResource(R.string.scheduled_trigger_weekly),
        daySeparator = stringResource(R.string.scheduled_trigger_day_separator),
        // DateFormatSymbols is 1-based (index 0 is empty); the model wants 0-based.
        dayNames = (Calendar.SUNDAY..Calendar.SATURDAY).map { shortDays[it] },
        from = stringResource(R.string.scheduled_trigger_from),
        until = stringResource(R.string.scheduled_trigger_until),
        window = stringResource(R.string.scheduled_trigger_window),
        interval = stringResource(R.string.scheduled_trigger_interval),
        intervalCount = stringResource(R.string.scheduled_trigger_interval_count),
        onCompletion = stringResource(R.string.scheduled_trigger_on_completion),
    )
}

/** [T-android-scheduled-triggers] HH:mm, shared with the task editor. */
internal fun formatTriggerTime(hour: Int, minute: Int): String = formatHm(hour, minute)

/** [T-android-scheduled-triggers] A day, shared with the task editor. */
internal fun formatTriggerDay(context: Context, ms: Long): String = formatDay(context, ms)

private fun formatHm(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

private fun formatDay(context: Context, ms: Long): String {
    val locale = context.resources.configuration.locales[0]
    val sameYear = Calendar.getInstance().get(Calendar.YEAR) ==
        Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.YEAR)
    val skeleton = if (sameYear) "MMMd" else "yMMMd"
    return SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(ms))
}

/** `HH:mm:ss` today, otherwise the day plus `HH:mm`. */
private fun formatFireTime(context: Context, ms: Long, now: Long): String {
    val locale = context.resources.configuration.locales[0]
    val a = Calendar.getInstance().apply { timeInMillis = ms }
    val b = Calendar.getInstance().apply { timeInMillis = now }
    val today = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return if (today) {
        SimpleDateFormat("HH:mm:ss", locale).format(Date(ms))
    } else {
        "${formatDay(context, ms)} ${SimpleDateFormat("HH:mm", locale).format(Date(ms))}"
    }
}

/** "in 1 h 5 min" — the two largest units, refreshed by the caller's clock. */
private fun relative(context: Context, deltaMs: Long): String {
    if (deltaMs <= 0) return context.getString(R.string.scheduled_detail_now)
    val parts = ScheduledTaskDetailModel.durationParts(deltaMs / 1000).map { (unit, n) ->
        when (unit) {
            ScheduledTaskDetailModel.DurationUnit.DAY -> context.getString(R.string.scheduled_unit_day, n)
            ScheduledTaskDetailModel.DurationUnit.HOUR -> context.getString(R.string.scheduled_unit_hour, n)
            ScheduledTaskDetailModel.DurationUnit.MINUTE -> context.getString(R.string.scheduled_unit_min, n)
            ScheduledTaskDetailModel.DurationUnit.SECOND -> context.getString(R.string.scheduled_unit_sec, n)
        }
    }
    return context.getString(R.string.scheduled_detail_in, parts.joinToString(" "))
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("scheduled task", text))
    Toast.makeText(context, R.string.scheduled_detail_copied, Toast.LENGTH_SHORT).show()
}

/** Same deep link the completion notification uses. */
private fun openSession(context: Context, sessionId: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("minis://session/$sessionId")).apply {
        setPackage(context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
