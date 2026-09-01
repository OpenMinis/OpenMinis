package com.openminis.app.ui.scheduled

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * [T-android-scheduled-tasks-design / T-android-scheduled-tasks-full]
 * Create or edit a single scheduled task. taskId=null means create-new.
 *
 * Mirrors the iOS Shortcuts option set: target mode (new session / follow-up
 * an existing chat / re-run a chat from a chosen message), optional model
 * override, time (compact input), repeat, and an optional start/end date
 * window.
 */
private enum class TargetKind { NEW, FOLLOW_UP, RERUN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTaskEditScreen(
    taskId: String?,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
) {
    val context = LocalContext.current
    val vm: ScheduledTasksViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = ScheduledTasksViewModel.factory(context),
    )
    val scope = rememberCoroutineScope()
    val isNew = taskId == null

    var label by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var repeatMode by remember { mutableStateOf(ScheduledRepeatMode.DAILY) }
    var customDays by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var hour by remember { mutableStateOf(9) }
    var minute by remember { mutableStateOf(0) }
    var enabled by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(isNew) }
    var createdAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastFiredAt by remember { mutableStateOf<Long?>(null) }
    var lastResultPreview by remember { mutableStateOf<String?>(null) }
    var lastResultSessionId by remember { mutableStateOf<String?>(null) }

    // [T-android-scheduled-tasks-full] target mode + model + date window
    var targetKind by remember { mutableStateOf(TargetKind.NEW) }
    var targetSessionId by remember { mutableStateOf<String?>(null) }
    var targetSessionTitle by remember { mutableStateOf<String?>(null) }
    var targetMessageId by remember { mutableStateOf<String?>(null) }
    var targetMessagePreview by remember { mutableStateOf<String?>(null) }
    // Three-way model selection mirroring ChatSessionEntity.modelBinding:
    //   - modelBinding == null  → "use app default" (resolved at run time)
    //   - {"type":"entry","entryId":"…"} → pinned to that entry
    //   - {"type":"group","groupId":"…"} → bound to that group (load-balance / fallback)
    var modelBinding by remember { mutableStateOf<String?>(null) }
    var modelDisplay by remember { mutableStateOf<String?>(null) }
    var startDateMs by remember { mutableStateOf<Long?>(null) }
    var endDateMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(taskId) {
        if (taskId == null) { loaded = true; return@LaunchedEffect }
        val existing = vm.get(taskId) ?: run { onBack(); return@LaunchedEffect }
        label = existing.label
        prompt = existing.prompt
        repeatMode = existing.repeatMode
        customDays = existing.customDays
        hour = existing.timeOfDayHour
        minute = existing.timeOfDayMinute
        enabled = existing.enabled
        createdAt = existing.createdAt
        lastFiredAt = existing.lastFiredAt
        lastResultPreview = existing.lastResultPreview
        lastResultSessionId = existing.lastResultSessionId
        startDateMs = existing.startDateMs
        endDateMs = existing.endDateMs
        when (val m = existing.targetMode) {
            ScheduledTargetMode.NewSession -> targetKind = TargetKind.NEW
            is ScheduledTargetMode.AppendToSession -> {
                targetKind = TargetKind.FOLLOW_UP
                targetSessionId = m.sessionId
            }
            is ScheduledTargetMode.RerunMessage -> {
                targetKind = TargetKind.RERUN
                targetSessionId = m.sessionId
                targetMessageId = m.messageId
            }
        }
        // Resolve display names for any pre-selected session / model. The
        // binding takes priority (it carries both group and entry shapes); we
        // only fall back to the legacy modelId-only path when no binding is
        // present on the row (tasks created before T-android-scheduled-task-
        // model-binding).
        when {
            existing.modelBinding != null -> {
                modelBinding = existing.modelBinding
                modelDisplay = vm.describeBinding(existing.modelBinding)
            }
            existing.modelId != null -> {
                val entry = vm.listModels().firstOrNull { it.modelId == existing.modelId }
                if (entry != null) {
                    modelBinding = """{"type":"entry","entryId":"${entry.entryId}"}"""
                    modelDisplay = entry.displayName
                }
            }
        }
        targetSessionId?.let { sid ->
            targetSessionTitle = vm.listSessions().firstOrNull { it.id == sid }?.title
        }
        loaded = true
    }

    val runNowState by vm.runNowState.collectAsState()

    fun currentTask(): ScheduledTask = buildTask(
        id = taskId, label = label, prompt = prompt, hour = hour, minute = minute,
        repeatMode = repeatMode, customDays = customDays, enabled = enabled,
        createdAt = createdAt, lastFiredAt = lastFiredAt,
        lastResultPreview = lastResultPreview, lastResultSessionId = lastResultSessionId,
        targetKind = targetKind, targetSessionId = targetSessionId,
        targetMessageId = targetMessageId,
        modelBinding = modelBinding,
        modelEntryIdLookup = { eid ->
            vm.listModels().firstOrNull { it.entryId == eid }?.modelId
        },
        startDateMs = startDateMs, endDateMs = endDateMs,
    )

    // re-run replays the chosen message, so prompt isn't required there.
    val needsPrompt = targetKind != TargetKind.RERUN
    val targetOk = when (targetKind) {
        TargetKind.NEW -> true
        TargetKind.FOLLOW_UP -> targetSessionId != null
        TargetKind.RERUN -> targetSessionId != null && targetMessageId != null
    }
    val canSave = targetOk && (!needsPrompt || prompt.isNotBlank())
    val canRunNow = canSave && runNowState?.status != ScheduledTasksViewModel.RunStatus.RUNNING

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isNew) R.string.scheduled_task_new
                            else R.string.scheduled_task_edit,
                        ),
                        fontWeight = FontWeight.Bold,
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
    ) { padding ->
        if (!loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        EditFormBody(
            padding = padding,
            label = label, onLabelChange = { label = it },
            prompt = prompt, onPromptChange = { prompt = it },
            needsPrompt = needsPrompt,
            hour = hour, minute = minute, onTimeChange = { h, m -> hour = h; minute = m },
            repeatMode = repeatMode, onRepeatModeChange = { repeatMode = it },
            customDays = customDays, onCustomDaysChange = { customDays = it },
            targetKind = targetKind, onTargetKindChange = {
                targetKind = it
                if (it == TargetKind.NEW) { targetSessionId = null; targetSessionTitle = null }
                if (it != TargetKind.RERUN) { targetMessageId = null; targetMessagePreview = null }
            },
            targetSessionTitle = targetSessionTitle,
            onPickSession = { id, title ->
                targetSessionId = id; targetSessionTitle = title
                targetMessageId = null; targetMessagePreview = null
            },
            targetMessagePreview = targetMessagePreview,
            onPickMessage = { id, preview -> targetMessageId = id; targetMessagePreview = preview },
            targetSessionId = targetSessionId,
            modelBindingForPreselect = modelBinding,
            modelDisplay = modelDisplay,
            onPickModel = { binding, display -> modelBinding = binding; modelDisplay = display },
            startDateMs = startDateMs, onStartDateChange = { startDateMs = it },
            endDateMs = endDateMs, onEndDateChange = { endDateMs = it },
            vm = vm,
            isNew = isNew,
            canRunNow = canRunNow,
            canSave = canSave,
            onRunNow = { vm.runNow(currentTask()) },
            onSave = {
                scope.launch { vm.upsert(currentTask(), isNew = isNew); onBack() }
            },
            onDelete = {
                if (taskId != null) scope.launch { vm.delete(taskId); onBack() }
            },
        )
    }

    // [T-android-scheduled-tasks-full] Run-now feedback. RUNNING shows a
    // dismissable loading dialog; the runner returns quickly (async — it does
    // NOT wait for the agent loop), flipping to STARTED ("task started, open
    // the chat") or FAILED. Dismissing during RUNNING just hides the dialog;
    // the background run continues and posts its completion notification.
    val state = runNowState
    if (state != null) {
        when (state.status) {
            ScheduledTasksViewModel.RunStatus.RUNNING -> {
                AlertDialog(
                    onDismissRequest = { vm.clearRunNowState() },
                    title = { Text(stringResource(R.string.scheduled_task_run_now_starting)) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.scheduled_task_run_now_starting_body))
                        }
                    },
                    confirmButton = {
                        MinisTextButton(onClick = { vm.clearRunNowState() }) {
                            Text(stringResource(R.string.scheduled_task_run_now_dismiss))
                        }
                    },
                )
            }
            ScheduledTasksViewModel.RunStatus.STARTED -> {
                AlertDialog(
                    onDismissRequest = { vm.clearRunNowState() },
                    title = { Text(stringResource(R.string.scheduled_task_run_now_started)) },
                    text = { Text(stringResource(R.string.scheduled_task_run_now_started_body)) },
                    confirmButton = {
                        val sid = state.sessionId
                        if (sid != null) {
                            MinisTextButton(onClick = { vm.clearRunNowState(); onOpenSession(sid) }) {
                                Text(stringResource(R.string.scheduled_task_run_now_open_session))
                            }
                        } else {
                            MinisTextButton(onClick = { vm.clearRunNowState() }) {
                                Text(stringResource(R.string.ok))
                            }
                        }
                    },
                    dismissButton = if (state.sessionId != null) {
                        { MinisTextButton(onClick = { vm.clearRunNowState() }) {
                            Text(stringResource(R.string.scheduled_task_run_now_dismiss))
                        } }
                    } else null,
                )
            }
            ScheduledTasksViewModel.RunStatus.FAILED -> {
                AlertDialog(
                    onDismissRequest = { vm.clearRunNowState() },
                    title = { Text(stringResource(R.string.scheduled_task_run_now_failed)) },
                    text = { Text(stringResource(R.string.scheduled_task_run_now_failed_body)) },
                    confirmButton = {
                        MinisTextButton(onClick = { vm.clearRunNowState() }) {
                            Text(stringResource(R.string.ok))
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditFormBody(
    padding: PaddingValues,
    label: String, onLabelChange: (String) -> Unit,
    prompt: String, onPromptChange: (String) -> Unit,
    needsPrompt: Boolean,
    hour: Int, minute: Int, onTimeChange: (Int, Int) -> Unit,
    repeatMode: ScheduledRepeatMode, onRepeatModeChange: (ScheduledRepeatMode) -> Unit,
    customDays: Set<Int>, onCustomDaysChange: (Set<Int>) -> Unit,
    targetKind: TargetKind, onTargetKindChange: (TargetKind) -> Unit,
    targetSessionTitle: String?, onPickSession: (String, String) -> Unit,
    targetMessagePreview: String?, onPickMessage: (String, String) -> Unit,
    targetSessionId: String?,
    modelBindingForPreselect: String?,
    modelDisplay: String?, onPickModel: (binding: String?, display: String?) -> Unit,
    startDateMs: Long?, onStartDateChange: (Long?) -> Unit,
    endDateMs: Long?, onEndDateChange: (Long?) -> Unit,
    vm: ScheduledTasksViewModel,
    isNew: Boolean,
    canRunNow: Boolean,
    canSave: Boolean,
    onRunNow: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    // [T-android-scheduled-tasks-full] Compact time entry (TimeInput keyboard,
    // not the full clock dial — the user asked for the compact mode).
    val timeState = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    LaunchedEffect(timeState.hour, timeState.minute) { onTimeChange(timeState.hour, timeState.minute) }

    var showSessionPicker by remember { mutableStateOf(false) }
    var showMessagePicker by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = onLabelChange,
            label = { Text(stringResource(R.string.scheduled_task_field_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // ── Target mode ──
        Column {
            SectionLabel(stringResource(R.string.scheduled_task_field_target))
            Spacer(Modifier.height(8.dp))
            val targetOpts = listOf(
                TargetKind.NEW to R.string.scheduled_task_target_new,
                TargetKind.FOLLOW_UP to R.string.scheduled_task_target_followup,
                TargetKind.RERUN to R.string.scheduled_task_target_rerun,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                targetOpts.forEachIndexed { idx, (kind, resId) ->
                    SegmentedButton(
                        selected = targetKind == kind,
                        onClick = { onTargetKindChange(kind) },
                        shape = SegmentedButtonDefaults.itemShape(idx, targetOpts.size),
                    ) { Text(stringResource(resId), fontSize = 13.sp) }
                }
            }
            if (targetKind != TargetKind.NEW) {
                Spacer(Modifier.height(8.dp))
                PickerRow(
                    title = stringResource(R.string.scheduled_task_field_session),
                    value = targetSessionTitle ?: stringResource(R.string.scheduled_task_pick_session),
                    onClick = { showSessionPicker = true },
                )
            }
            if (targetKind == TargetKind.RERUN) {
                Spacer(Modifier.height(8.dp))
                PickerRow(
                    title = stringResource(R.string.scheduled_task_field_message),
                    value = targetMessagePreview
                        ?: stringResource(R.string.scheduled_task_pick_message),
                    enabled = targetSessionId != null,
                    onClick = { if (targetSessionId != null) showMessagePicker = true },
                )
            }
        }

        // ── Time (compact) ──
        Column {
            SectionLabel(stringResource(R.string.scheduled_task_field_time))
            Spacer(Modifier.height(8.dp))
            TimeInput(state = timeState)
        }

        // ── Repeat ──
        Column {
            SectionLabel(stringResource(R.string.scheduled_task_field_repeat))
            Spacer(Modifier.height(8.dp))
            val options = listOf(
                ScheduledRepeatMode.ONCE to R.string.scheduled_task_repeat_once,
                ScheduledRepeatMode.DAILY to R.string.scheduled_task_repeat_daily,
                ScheduledRepeatMode.WEEKDAYS to R.string.scheduled_task_repeat_weekdays,
                ScheduledRepeatMode.CUSTOM to R.string.scheduled_task_repeat_custom,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { idx, (mode, resId) ->
                    SegmentedButton(
                        selected = repeatMode == mode,
                        onClick = { onRepeatModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(idx, options.size),
                    ) { Text(stringResource(resId), fontSize = 13.sp) }
                }
            }
            if (repeatMode == ScheduledRepeatMode.CUSTOM) {
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val days = listOf(
                        Calendar.MONDAY to "Mon", Calendar.TUESDAY to "Tue",
                        Calendar.WEDNESDAY to "Wed", Calendar.THURSDAY to "Thu",
                        Calendar.FRIDAY to "Fri", Calendar.SATURDAY to "Sat",
                        Calendar.SUNDAY to "Sun",
                    )
                    days.forEach { (dow, name) ->
                        FilterChip(
                            selected = dow in customDays,
                            onClick = {
                                val next = customDays.toMutableSet().apply {
                                    if (dow in this) remove(dow) else add(dow)
                                }
                                onCustomDaysChange(next)
                            },
                            label = { Text(name, fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // ── Active window (start / end date) ──
        Column {
            SectionLabel(stringResource(R.string.scheduled_task_field_active_window))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    PickerRow(
                        title = stringResource(R.string.scheduled_task_field_start_date),
                        value = startDateMs?.let { dateLabel(it) }
                            ?: stringResource(R.string.scheduled_task_date_any),
                        onClick = { showStartPicker = true },
                        onClear = if (startDateMs != null) ({ onStartDateChange(null) }) else null,
                    )
                }
                Box(Modifier.weight(1f)) {
                    PickerRow(
                        title = stringResource(R.string.scheduled_task_field_end_date),
                        value = endDateMs?.let { dateLabel(it) }
                            ?: stringResource(R.string.scheduled_task_date_any),
                        onClick = { showEndPicker = true },
                        onClear = if (endDateMs != null) ({ onEndDateChange(null) }) else null,
                    )
                }
            }
        }

        // ── Prompt ──
        if (needsPrompt) {
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                label = { Text(stringResource(R.string.scheduled_task_field_prompt)) },
                placeholder = { Text(stringResource(R.string.scheduled_task_field_prompt_hint)) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                stringResource(R.string.scheduled_task_rerun_note),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── Model (optional) ──
        PickerRow(
            title = stringResource(R.string.scheduled_task_field_model),
            value = modelDisplay ?: stringResource(R.string.scheduled_task_model_default),
            onClick = { showModelPicker = true },
            onClear = if (modelDisplay != null) ({ onPickModel(null, null) }) else null,
        )

        HorizontalDivider()

        MinisOutlinedButton(onClick = onRunNow, enabled = canRunNow, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.scheduled_task_run_now))
        }
        MinisButton(onClick = onSave, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.scheduled_task_save))
        }
        if (!isNew) {
            MinisOutlinedButton(
                onClick = onDelete,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.scheduled_task_delete)) }
        }
    }

    if (showSessionPicker) {
        SessionPickerDialog(vm = vm, onDismiss = { showSessionPicker = false }) { id, title ->
            onPickSession(id, title); showSessionPicker = false
        }
    }
    if (showMessagePicker && targetSessionId != null) {
        MessagePickerDialog(vm = vm, sessionId = targetSessionId, onDismiss = { showMessagePicker = false }) { id, preview ->
            onPickMessage(id, preview); showMessagePicker = false
        }
    }
    // [T-android-scheduled-lateinit-crash-156] `vm.providerRepository` is null
    // when the Application is half-built (safe-mode / aborted onCreate); the
    // editor is reachable in exactly that state via a scheduled-task
    // notification tap. Gating the whole block here keeps the picker from
    // reading an unassigned lateinit — the row's label and the "use default
    // model" behaviour are unaffected.
    val providerRepo = vm.providerRepository
    if (showModelPicker && providerRepo != null) {
        // [T-android-scheduled-task-model-binding] Reuse the chat-screen's
        // ModelPickerSheet so groups and individual entries are both
        // selectable; the editor only needs the JSON shape that
        // ChatSessionEntity.modelBinding uses, so we synthesize it from the
        // picker's three callback shapes (group / group+entry / standalone
        // entry). The picker doesn't surface a "default model" option — we
        // model that as null binding via the row's onClear action above.
        val cfg by providerRepo.config.collectAsState()
        val entryById: (String) -> com.openminis.app.data.model.ModelEntry? = { id ->
            cfg.modelEntries.firstOrNull { it.id == id }
        }
        val groupById: (String) -> com.openminis.app.data.model.ModelGroup? = { id ->
            cfg.modelGroups.firstOrNull { it.id == id }
        }
        // Pre-highlight the user's prior pick when re-opening the sheet:
        //   - binding pinned to a group → highlight that group
        //   - binding pinned to an entry → highlight that entry (no group)
        //   - binding == null ("use default") → fall through to the app's
        //     default primary group so the user sees it as the implicit
        //     current selection (instead of an empty radio everywhere).
        val preselectGroupId: String?
        val preselectEntryId: String?
        run {
            val b = modelBindingForPreselect
            val parsed = b?.let { json ->
                runCatching { org.json.JSONObject(json) }.getOrNull()
            }
            val ptype = parsed?.optString("type")
            preselectGroupId = when {
                ptype == "group" -> parsed.optString("groupId").takeIf { it.isNotEmpty() }
                b == null -> cfg.defaultPrimaryGroupId
                else -> null
            }
            preselectEntryId = if (ptype == "entry") {
                parsed.optString("entryId").takeIf { it.isNotEmpty() }
            } else null
        }
        com.openminis.app.ui.chat.ModelPickerSheet(
            groups = cfg.modelGroups,
            selectedGroupId = preselectGroupId,
            activeEntryId = preselectEntryId,
            defaultPrimaryGroupId = cfg.defaultPrimaryGroupId,
            config = cfg,
            providerRepository = providerRepo,
            onSelectGroup = { groupId ->
                val name = groupById(groupId)?.name ?: "Group"
                val json = """{"type":"group","groupId":"$groupId"}"""
                onPickModel(json, name); showModelPicker = false
            },
            onSelectGroupEntry = { _, entryId ->
                // User explicitly picked a single entry inside a group → pin
                // to that entry; matches the chat screen's per-group entry
                // override semantics.
                val entry = entryById(entryId)
                val name = entry?.model?.displayName ?: "Model"
                val json = """{"type":"entry","entryId":"$entryId"}"""
                onPickModel(json, name); showModelPicker = false
            },
            onSelectEntry = { entryId ->
                val entry = entryById(entryId)
                val name = entry?.model?.displayName ?: "Model"
                val json = """{"type":"entry","entryId":"$entryId"}"""
                onPickModel(json, name); showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }
    if (showStartPicker) {
        DateDialog(initialMs = startDateMs, onDismiss = { showStartPicker = false }) {
            onStartDateChange(it); showStartPicker = false
        }
    }
    if (showEndPicker) {
        DateDialog(initialMs = endDateMs, onDismiss = { showEndPicker = false }) {
            onEndDateChange(it); showEndPicker = false
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun PickerRow(
    title: String,
    value: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        enabled = false,
        label = { Text(title) },
        trailingIcon = if (onClear != null) {
            {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(0.dp))
                    Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else null,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionPickerDialog(
    vm: ScheduledTasksViewModel,
    onDismiss: () -> Unit,
    onPick: (id: String, title: String) -> Unit,
) {
    var sessions by remember { mutableStateOf<List<ScheduledTasksViewModel.SessionOption>?>(null) }
    LaunchedEffect(Unit) { sessions = vm.listSessions() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scheduled_task_pick_session)) },
        text = {
            val list = sessions
            if (list == null) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (list.isEmpty()) {
                Text(stringResource(R.string.scheduled_task_no_sessions))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(list, key = { it.id }) { s ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(s.id, s.title) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessagePickerDialog(
    vm: ScheduledTasksViewModel,
    sessionId: String,
    onDismiss: () -> Unit,
    onPick: (id: String, preview: String) -> Unit,
) {
    var messages by remember { mutableStateOf<List<ScheduledTasksViewModel.MessageOption>?>(null) }
    LaunchedEffect(sessionId) { messages = vm.listUserMessages(sessionId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scheduled_task_pick_message)) },
        text = {
            val list = messages
            if (list == null) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (list.isEmpty()) {
                Text(stringResource(R.string.scheduled_task_no_messages_in_session))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(list, key = { it.id }) { m ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(m.id, m.preview) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(m.preview, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initialMs: Long?, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMs)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            MinisTextButton(onClick = {
                state.selectedDateMillis?.let { onPick(startOfLocalDay(it)) }
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DatePicker(state = state)
    }
}

/** DatePicker hands back a UTC-midnight ms; convert to local start-of-day. */
private fun startOfLocalDay(utcMidnightMs: Long): Long {
    // The picker's value is UTC midnight of the picked calendar day. Re-anchor
    // to the local time zone's start-of-day so the active-window comparison in
    // ScheduledTask.nextTriggerMs (which works in local time) matches.
    val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = utcMidnightMs
    }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
}

private fun dateLabel(ms: Long): String =
    SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))

@Suppress("LongParameterList")
private fun buildTask(
    id: String?,
    label: String,
    prompt: String,
    hour: Int,
    minute: Int,
    repeatMode: ScheduledRepeatMode,
    customDays: Set<Int>,
    enabled: Boolean,
    createdAt: Long,
    lastFiredAt: Long?,
    lastResultPreview: String?,
    lastResultSessionId: String?,
    targetKind: TargetKind,
    targetSessionId: String?,
    targetMessageId: String?,
    modelBinding: String?,
    modelEntryIdLookup: (String) -> String?,
    startDateMs: Long?,
    endDateMs: Long?,
): ScheduledTask {
    val targetMode = when (targetKind) {
        TargetKind.NEW -> ScheduledTargetMode.NewSession
        TargetKind.FOLLOW_UP -> targetSessionId?.let { ScheduledTargetMode.AppendToSession(it) }
            ?: ScheduledTargetMode.NewSession
        TargetKind.RERUN ->
            if (targetSessionId != null && targetMessageId != null)
                ScheduledTargetMode.RerunMessage(targetSessionId, targetMessageId)
            else ScheduledTargetMode.NewSession
    }
    // For backcompat: if the binding pins to a specific entry, also fill in
    // legacy modelId so older code paths (and any external readers) still
    // resolve the same concrete model. Group bindings leave modelId null —
    // they resolve to a member entry at run time.
    val derivedModelId: String? = modelBinding?.let { json ->
        runCatching {
            val o = org.json.JSONObject(json)
            if (o.optString("type") == "entry") {
                o.optString("entryId").takeIf { it.isNotEmpty() }?.let(modelEntryIdLookup)
            } else null
        }.getOrNull()
    }
    return ScheduledTask(
        id = id ?: java.util.UUID.randomUUID().toString(),
        label = label.trim(),
        timeOfDayHour = hour,
        timeOfDayMinute = minute,
        repeatMode = repeatMode,
        customDays = if (repeatMode == ScheduledRepeatMode.CUSTOM) customDays else emptySet(),
        prompt = prompt.trim(),
        targetMode = targetMode,
        modelId = derivedModelId,
        modelBinding = modelBinding,
        enabled = enabled,
        createdAt = createdAt,
        startDateMs = startDateMs,
        endDateMs = endDateMs,
        lastFiredAt = lastFiredAt,
        lastResultPreview = lastResultPreview,
        lastResultSessionId = lastResultSessionId,
    )
}
