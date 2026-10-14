package com.openminis.app.ui.chat

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import com.openminis.app.R
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsValueRow
import kotlinx.coroutines.delay
import com.openminis.app.util.IsoTime
import com.openminis.app.ui.components.MinisTextButton

/**
 * Bottom sheet showing memory state for the current session.
 *
 * Three modes share the [StandardChatSheet] shell:
 *   - List: the default — Section 1 auto-injected + Section 2 op-log
 *   - Detail.AutoFile: read-only / editable view of GLOBAL.md or a daily log
 *   - Detail.Write: per-entry view of a memory_write op-log row, with
 *                   Edit / Save / Revoke
 *   - Detail.Get:   per-entry view of a memory_get op-log row
 *
 * The sheet header swaps its leading slot to a back-arrow when in any detail
 * mode; the trailing close X always dismisses the entire sheet, mirroring iOS
 * `NavigationStack` push behavior inside `SessionMemoryView`.
 */
@Composable
fun SessionMemorySheet(
    memoryRepository: MemoryRepository,
    toolRecords: List<MemoryToolRecord>,
    onDismiss: () -> Unit,
    onRevokeRecord: (MemoryToolRecord) -> MemoryRepository.EntryMutationResult,
    onSaveRecord: (MemoryToolRecord, String) -> MemoryRepository.EntryMutationResult,
    sources: InjectionSources? = null,
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf<MemorySheetMode>(MemorySheetMode.List) }
    val autoItems = remember(memoryRepository, context, sources) {
        buildAutoInjectedItems(context, memoryRepository, sources)
    }

    // Editing state for the active detail screen. Lives at the sheet level so
    // a single Save button in the header can read the latest buffer without
    // threading callbacks through the body composable.
    var isEditing by remember(mode) { mutableStateOf(false) }
    var editedContent by remember(mode) { mutableStateOf("") }
    var savedToastVisible by remember { mutableStateOf(false) }

    // Per-mode dialog state for revoke flow.
    var showRevokeConfirm by remember { mutableStateOf(false) }
    var mutationResult by remember { mutableStateOf<MemoryRepository.EntryMutationResult?>(null) }

    // Auto-dismiss the "Saved" pill after 1.5s (matches iOS).
    LaunchedEffect(savedToastVisible) {
        if (savedToastVisible) {
            delay(1500)
            savedToastVisible = false
        }
    }

    // Reset editing buffer when entering a new detail view.
    LaunchedEffect(mode, isEditing) {
        if (isEditing) {
            editedContent = when (val m = mode) {
                is MemorySheetMode.AutoFile -> m.content
                is MemorySheetMode.Write -> m.record.writtenContent ?: ""
                else -> ""
            }
        }
    }

    val title = when (val m = mode) {
        MemorySheetMode.List -> stringResource(R.string.session_memory_title)
        is MemorySheetMode.AutoFile -> m.name
        is MemorySheetMode.Write -> m.record.title
        is MemorySheetMode.Get -> m.record.title
    }

    StandardChatSheet(
        title = title,
        onDismiss = onDismiss,
        leadingAction = if (mode != MemorySheetMode.List) {
            {
                IconButton(onClick = {
                    if (isEditing) {
                        // Cancel edit, stay on detail.
                        isEditing = false
                    } else {
                        mode = MemorySheetMode.List
                    }
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.memory_action_back),
                    )
                }
            }
        } else null,
    ) {
        when (val m = mode) {
            MemorySheetMode.List -> ListBody(
                autoItems = autoItems,
                toolRecords = toolRecords,
                onAutoItemClick = { item ->
                    mode = MemorySheetMode.AutoFile(
                        name = item.name,
                        fileName = item.fileName,
                        content = item.content,
                        editable = item.editable,
                        saver = item.saver,
                    )
                },
                onWriteClick = { rec -> mode = MemorySheetMode.Write(rec) },
                onGetClick = { rec -> mode = MemorySheetMode.Get(rec) },
            )

            is MemorySheetMode.AutoFile -> Column(modifier = Modifier.fillMaxSize()) {
                DetailToolbar(
                    showEdit = m.editable && !isEditing,
                    showSave = m.editable && isEditing,
                    showRevoke = false,
                    onEdit = { isEditing = true; editedContent = m.content },
                    onSave = {
                        try {
                            val saver = m.saver
                            if (saver != null) {
                                saver(editedContent)
                            } else {
                                memoryRepository.saveFile(m.fileName, editedContent)
                            }
                            // SOUL.md drives [SoulStore.cachedMetadata] which
                            // backs the chat-bubble header name. The raw
                            // saveFile() path here bypasses SoulStore.save(),
                            // so refresh the cache manually to keep readers
                            // in sync after an in-sheet edit.
                            if (m.fileName == "SOUL.md") {
                                com.openminis.app.agent.SoulStore.refreshCache(context)
                            }
                            mode = MemorySheetMode.AutoFile(m.name, m.fileName, editedContent, m.editable, m.saver)
                            isEditing = false
                            savedToastVisible = true
                        } catch (_: Exception) { /* fall through; UI toast omitted on failure */ }
                    },
                    onRevoke = {},
                )
                MemoryFileViewerBody(
                    initialContent = m.content,
                    isEditing = isEditing,
                    editedContent = editedContent,
                    onEditedContentChange = { editedContent = it },
                    showSavedToast = savedToastVisible,
                )
            }

            is MemorySheetMode.Write -> Column(modifier = Modifier.fillMaxSize()) {
                val canEditOrRevoke = m.record.writtenContent != null
                DetailToolbar(
                    showEdit = canEditOrRevoke && !isEditing,
                    showSave = canEditOrRevoke && isEditing,
                    showRevoke = canEditOrRevoke && !isEditing,
                    onEdit = {
                        isEditing = true
                        editedContent = m.record.writtenContent ?: ""
                    },
                    onSave = {
                        val result = onSaveRecord(m.record, editedContent)
                        if (result is MemoryRepository.EntryMutationResult.Success) {
                            // Update the displayed record in-place so a follow-up
                            // revoke targets the new body.
                            mode = MemorySheetMode.Write(
                                m.record.copy(writtenContent = editedContent)
                            )
                            isEditing = false
                            savedToastVisible = true
                        } else {
                            mutationResult = result
                        }
                    },
                    onRevoke = { showRevokeConfirm = true },
                )
                MemoryWriteDetailBody(
                    record = m.record,
                    isEditing = isEditing,
                    editedContent = editedContent,
                    onEditedContentChange = { editedContent = it },
                    showSavedToast = savedToastVisible,
                )
            }

            is MemorySheetMode.Get -> MemoryGetDetailBody(record = m.record)
        }
    }

    if (showRevokeConfirm && mode is MemorySheetMode.Write) {
        val writeMode = mode as MemorySheetMode.Write
        RevokeConfirmDialog(
            onConfirm = {
                showRevokeConfirm = false
                val result = onRevokeRecord(writeMode.record)
                mutationResult = result
                // On success the row is removed from toolRecords by the
                // ViewModel; pop back to list so the user sees the list
                // re-render without the row.
                if (result is MemoryRepository.EntryMutationResult.Success) {
                    mode = MemorySheetMode.List
                }
            },
            onDismiss = { showRevokeConfirm = false },
        )
    }

    val resultSnapshot = mutationResult
    if (resultSnapshot != null) {
        MutationResultDialog(
            result = resultSnapshot,
            onDismiss = { mutationResult = null },
        )
    }
}

/**
 * Compact toolbar rendered inside the body (above the main content) for
 * detail screens. Sits inside the body slot so it doesn't compete with the
 * sheet's standard header — keeps the header visually identical across all
 * modes (back-arrow / title / close X).
 */
@Composable
private fun DetailToolbar(
    showEdit: Boolean,
    showSave: Boolean,
    showRevoke: Boolean,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onRevoke: () -> Unit,
) {
    if (!showEdit && !showSave && !showRevoke) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showEdit) {
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.memory_action_edit),
                )
            }
        }
        if (showSave) {
            MinisTextButton(onClick = onSave) {
                Text(stringResource(R.string.memory_action_save))
            }
        }
        if (showRevoke) {
            IconButton(onClick = onRevoke) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(R.string.memory_action_revoke),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ListBody(
    autoItems: List<AutoItem>,
    toolRecords: List<MemoryToolRecord>,
    onAutoItemClick: (AutoItem) -> Unit,
    onWriteClick: (MemoryToolRecord) -> Unit,
    onGetClick: (MemoryToolRecord) -> Unit,
) {
    val sections = remember(autoItems) { groupAutoItems(autoItems) }
    val lineCountTemplate = stringResource(R.string.memory_line_count)
    val emptyLabel = stringResource(R.string.memory_rules_none)
    // Collapsed summary for the rules row, derived from the children so it can
    // never disagree with what expanding reveals.
    val rulesSummaryText = rulesSummary(
        sections.rules,
        { n -> lineCountTemplate.format(n) },
        emptyLabel,
    )
    var rulesExpanded by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // ── Section 1: Auto-injected — 人格 / 规则, two rows ──
        //
        // Everything the injector reads is still reachable from here; it is
        // grouped rather than flattened. The rules row expands to the same
        // app-wide + session GLOBAL.md entries the flat list used to show as
        // top-level siblings.
        if (sections.hasPrimary) {
            item {
                SettingsSection(
                    header = stringResource(R.string.memory_section_auto_injected),
                    footer = stringResource(R.string.memory_section_auto_injected_footer),
                ) {
                    sections.persona?.let { persona ->
                        SettingsValueRow(
                            title = persona.name,
                            value = persona.detail,
                            onClick = { onAutoItemClick(persona) },
                            showDivider = sections.rules.isNotEmpty(),
                        )
                    }
                    if (sections.rules.isNotEmpty()) {
                        SettingsValueRow(
                            title = stringResource(R.string.memory_row_rules),
                            value = if (rulesExpanded) {
                                stringResource(R.string.memory_rules_tap_to_expand)
                            } else {
                                rulesSummaryText
                            },
                            onClick = { rulesExpanded = !rulesExpanded },
                            showDivider = rulesExpanded,
                        )
                        if (rulesExpanded) {
                            sections.rules.forEachIndexed { index, child ->
                                SettingsValueRow(
                                    title = child.scopeLabel.ifBlank { child.name },
                                    value = if (child.lineCount <= 0) {
                                        emptyLabel
                                    } else {
                                        lineCountTemplate.format(child.lineCount)
                                    },
                                    onClick = { onAutoItemClick(child) },
                                    showDivider = index < sections.rules.size - 1,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Section 2: Memory diary (not a setting — injected logs) ──
        if (sections.diary.isNotEmpty()) {
            item {
                SettingsSection(
                    header = stringResource(R.string.memory_section_diary),
                    footer = stringResource(R.string.memory_section_diary_footer),
                ) {
                    sections.diary.forEachIndexed { index, entry ->
                        SettingsValueRow(
                            title = entry.name,
                            value = entry.detail,
                            onClick = { onAutoItemClick(entry) },
                            showDivider = index < sections.diary.size - 1,
                        )
                    }
                }
            }
        }

        // ── Section 3: Diagnostics (ground truth, read-only) ──
        if (sections.diagnostic.isNotEmpty()) {
            item {
                SettingsSection(
                    header = stringResource(R.string.memory_section_diagnostic),
                    footer = stringResource(R.string.memory_section_diagnostic_footer),
                ) {
                    sections.diagnostic.forEachIndexed { index, entry ->
                        SettingsValueRow(
                            title = entry.name,
                            value = entry.detail,
                            onClick = { onAutoItemClick(entry) },
                            showDivider = index < sections.diagnostic.size - 1,
                        )
                    }
                }
            }
        }

        // ── Section 2: Tool Activity (only if non-empty) ──
        if (toolRecords.isNotEmpty()) {
            item {
                SettingsSection(
                    header = stringResource(R.string.memory_section_tool_activity),
                    footer = stringResource(R.string.memory_section_tool_activity_footer),
                ) {
                    toolRecords.forEachIndexed { index, record ->
                        MemoryToolRow(
                            record = record,
                            onClick = {
                                if (record.isWrite) onWriteClick(record) else onGetClick(record)
                            },
                            showDivider = index < toolRecords.size - 1,
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

/**
 * Row for a single memory tool call. Distinct from [SettingsValueRow] because
 * the body needs three lines: title, op-type pill, and a monospace preview of
 * the keywords / content the tool wrote or read.
 */
@Composable
private fun MemoryToolRow(
    record: MemoryToolRecord,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    val opLabel = if (record.isWrite) "memory_write" else "memory_get"
    val opColor = MaterialTheme.colorScheme.primary

    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    record.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .background(
                            color = opColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        opLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = opColor,
                    )
                }
                if (record.preview.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        record.preview,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

/**
 * Record of a memory tool call in the current session.
 */
data class MemoryToolRecord(
    val title: String,
    val isWrite: Boolean,
    val preview: String,
    val output: String,
    val writtenContent: String? = null,
    val keywords: String? = null,
)

/**
 * Sheet navigation state. Held by [SessionMemorySheet] so a back arrow in the
 * standard sheet header can pop without unmounting the bottom sheet host.
 */
private sealed class MemorySheetMode {
    data object List : MemorySheetMode()
    data class AutoFile(
        val name: String,
        val fileName: String,
        val content: String,
        val editable: Boolean,
        val saver: ((String) -> Unit)? = null,
    ) : MemorySheetMode()
    data class Write(val record: MemoryToolRecord) : MemorySheetMode()
    data class Get(val record: MemoryToolRecord) : MemorySheetMode()
}

/**
 * What the system-prompt injector actually reads for this session, so the
 * sheet can show the same sources instead of a file guess.
 *
 * The previous sheet read "SOUL.md" out of the session memory directory — a
 * file the injector never looks at — and a single unnamed GLOBAL.md, while
 * injection concatenates the app-wide standing rules with the session-level
 * ones and resolves the persona per provider instance. A user who configured
 * a per-provider persona therefore saw the default here and had no way to
 * tell whether their selection was live.
 */
data class InjectionSources(
    val appRepo: MemoryRepository?,
    val sessionRepo: MemoryRepository?,
    val providerInstanceId: String?,
    val providerLabel: String?,
    val sessionId: String,
)

internal data class AutoItem(
    val name: String,
    val detail: String,
    /** On-disk filename (e.g. "GLOBAL.md" or "2026-04-26.md") used to load full content. */
    val fileName: String,
    /** Cached full content, snapshot at sheet open — keeps tap responsiveness fast. */
    val content: String,
    val editable: Boolean = true,
    /**
     * Custom save target for items whose file does not live in the sheet's
     * default repository (app-wide vs session GLOBAL.md, persona bodies).
     * Null keeps the legacy saveFile(fileName) path.
     */
    val saver: ((String) -> Unit)? = null,
    /**
     * [T-memory-sheet-simplify] Which top-level row this belongs to. The sheet
     * used to render one row per injected file, so "Auto-Injected" listed six
     * entries (persona, two GLOBAL.md, a snapshot, two daily logs) for what is
     * conceptually two settings. Grouping keeps every file reachable — the
     * injection-faithfulness that `0839a2c` established is the whole point of
     * this sheet — but the primary list reads as 人格 / 规则.
     */
    val group: AutoGroup = AutoGroup.DIAGNOSTIC,
    /**
     * Short scope tag for members of a group, e.g. "App-wide · read-only".
     * Used both as the child row's own label and to compose the collapsed
     * group summary.
     */
    val scopeLabel: String = "",
    /** Line count of [content], for summaries that must not re-split the body. */
    val lineCount: Int = 0,
)

/** Top-level grouping of injected sources. Order here is the display order. */
internal enum class AutoGroup { PERSONA, RULES, DIARY, DIAGNOSTIC }

/**
 * The sheet's primary list: one persona row, one (expandable) rules row, then
 * the diary and diagnostic sections that are not settings.
 */
internal data class MemorySheetSections(
    val persona: AutoItem?,
    val rules: List<AutoItem>,
    val diary: List<AutoItem>,
    val diagnostic: List<AutoItem>,
) {
    /** True when the primary "Auto-Injected" section has anything to show. */
    val hasPrimary: Boolean get() = persona != null || rules.isNotEmpty()
}

/**
 * Pure regrouping of a flat [AutoItem] list. Extracted so the sheet's
 * information architecture is assertable without composing UI: the bug this
 * replaced was structural (six sibling rows), not visual.
 *
 * Order within each group is preserved, so the app-wide GLOBAL.md keeps
 * rendering above the session one — the same order injection concatenates them.
 */
internal fun groupAutoItems(items: List<AutoItem>): MemorySheetSections = MemorySheetSections(
    persona = items.firstOrNull { it.group == AutoGroup.PERSONA },
    rules = items.filter { it.group == AutoGroup.RULES },
    diary = items.filter { it.group == AutoGroup.DIARY },
    diagnostic = items.filter { it.group == AutoGroup.DIAGNOSTIC },
)

/**
 * Collapsed summary for the rules row: "App-wide · read-only 12 lines · This
 * session 3 lines". Deliberately derived from [AutoItem.scopeLabel] and
 * [AutoItem.lineCount] rather than by re-parsing [AutoItem.detail], so the
 * summary cannot drift from the children it stands for.
 */
internal fun rulesSummary(
    rules: List<AutoItem>,
    lineCount: (Int) -> String,
    empty: String,
): String {
    val parts = rules.mapNotNull { item ->
        val label = item.scopeLabel.ifBlank { item.name }
        when {
            item.lineCount <= 0 && item.content.isBlank() -> null // nothing configured: omit
            else -> "$label ${lineCount(item.lineCount)}"
        }
    }
    return if (parts.isEmpty()) empty else parts.joinToString(" · ")
}

private fun buildAutoInjectedItems(
    context: Context,
    memoryRepository: MemoryRepository,
    sources: InjectionSources?,
): List<AutoItem> {
    val items = mutableListOf<AutoItem>()

    // Blank content reports as 0 lines, not 1: `"".lines().size == 1`, which
    // would make an unset file look configured in the collapsed summary.
    fun lineCountOf(body: String): Int = if (body.isBlank()) 0 else body.lines().size
    fun linesOrEmpty(body: String): String =
        if (body.isBlank()) context.getString(R.string.memory_rules_none)
        else context.getString(R.string.memory_line_count, lineCountOf(body))

    // Persona: what the injector RESOLVED for this session, including the
    // per-provider selection. Reading a "SOUL.md" out of the memory directory
    // (the old behaviour) showed a file the injector never looks at, so a
    // per-provider persona never appeared and the sheet looked stuck on the
    // default. Editing writes back through the same library the injector
    // reads, so what you see is what the next turn gets.
    val resolved = com.openminis.app.agent.PersonaPromptLibrary.resolve(
        context,
        sources?.providerInstanceId,
        sources?.sessionId,
    )
    val scopeLabel = when (resolved.scope) {
        com.openminis.app.agent.ResolvedPersonaPrompt.SCOPE_SESSION ->
            context.getString(R.string.memory_persona_scope_session)
        com.openminis.app.agent.ResolvedPersonaPrompt.SCOPE_PROVIDER ->
            context.getString(R.string.memory_persona_scope_provider, sources?.providerLabel ?: resolved.id)
        com.openminis.app.agent.ResolvedPersonaPrompt.SCOPE_BUILTIN ->
            context.getString(R.string.memory_persona_scope_builtin)
        else -> context.getString(R.string.memory_persona_scope_global)
    }
    val personaSessionId = sources?.sessionId
    items.add(
        AutoItem(
            name = context.getString(R.string.memory_row_persona),
            // Scope first, not the filename: "which level won" is the question
            // this row exists to answer. The resolved filename is still what
            // the editor loads.
            detail = "$scopeLabel · ${linesOrEmpty(resolved.body)}",
            fileName = resolved.fileName.ifBlank { "SOUL.md" },
            content = resolved.body,
            group = AutoGroup.PERSONA,
            scopeLabel = scopeLabel,
            lineCount = lineCountOf(resolved.body),
            // Editing here is session-scoped BY DESIGN: it writes the session
            // override (PERSONA.md in this session's memory dir), never the
            // provider-selected or global persona file. Priority is
            // session menu > provider/custom > default, exactly as resolved.
            editable = !personaSessionId.isNullOrBlank(),
            saver = if (personaSessionId.isNullOrBlank()) {
                null
            } else {
                { text ->
                    com.openminis.app.agent.PersonaPromptLibrary.writeSessionOverride(
                        context,
                        personaSessionId,
                        text,
                    )
                }
            },
        ),
    )

    // GLOBAL.md: injection concatenates the app-wide standing rules with the
    // session-level ones. Show both, labelled, so "which GLOBAL.md did the
    // model see?" has an answer.
    val appRepo = sources?.appRepo
    val sessionRepo = sources?.sessionRepo
    if (appRepo != null) {
        val appGlobal = appRepo.loadGlobalMd()
        // [T-memory-sheet-i18n] Scope labels come from resources: they were
        // hardcoded Chinese, which 17 of the 18 shipped locales cannot read.
        val appScope = context.getString(R.string.memory_rules_scope_app) +
            " · " + context.getString(R.string.memory_scope_readonly)
        items.add(
            AutoItem(
                name = "GLOBAL.md",
                detail = "$appScope · ${linesOrEmpty(appGlobal)}",
                fileName = "GLOBAL.md",
                content = appGlobal,
                // Read-only here on purpose: an edit from the session menu must
                // not change other sessions. The session-level item below is
                // the editable one.
                editable = false,
                group = AutoGroup.RULES,
                scopeLabel = appScope,
                lineCount = lineCountOf(appGlobal),
            ),
        )
    }
    if (sessionRepo != null && sessionRepo !== appRepo) {
        val sessionGlobal = sessionRepo.loadGlobalMd()
        val sessionScope = context.getString(R.string.memory_rules_scope_session) +
            " · " + context.getString(R.string.memory_scope_editable)
        items.add(
            AutoItem(
                name = "GLOBAL.md",
                detail = "$sessionScope · ${linesOrEmpty(sessionGlobal)}",
                fileName = "GLOBAL.md",
                content = sessionGlobal,
                saver = { text -> sessionRepo.saveGlobalMd(text) },
                group = AutoGroup.RULES,
                scopeLabel = sessionScope,
                lineCount = lineCountOf(sessionGlobal),
            ),
        )
    }
    if (appRepo == null && sessionRepo == null) {
        val globalContent = memoryRepository.loadGlobalMd()
        val loneScope = context.getString(R.string.memory_rules_scope_app)
        items.add(
            AutoItem(
                name = "GLOBAL.md",
                detail = "$loneScope · ${linesOrEmpty(globalContent)}",
                fileName = "GLOBAL.md",
                content = globalContent,
                group = AutoGroup.RULES,
                scopeLabel = loneScope,
                lineCount = lineCountOf(globalContent),
            ),
        )
    }

    // Ground truth: the exact system prompt this session last sent, captured
    // at assembly time with secrets masked. This is the item that answers
    // "did the injection take effect" without re-deriving anything.
    //
    // [T-memory-sheet-simplify] Diagnostic, not a setting: it moves out of the
    // primary list into its own section so "Auto-Injected" stays two rows.
    if (sources != null) {
        val snapshotFile = java.io.File(
            com.openminis.app.agent.ContextAssemblySnapshot.dir(context, sources.sessionId),
            "latest.md",
        )
        val snapshot = runCatching { snapshotFile.readText() }.getOrNull()
        if (!snapshot.isNullOrBlank()) {
            val stamp = snapshotFile.lastModified().let {
                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))
            }
            items.add(
                AutoItem(
                    name = context.getString(R.string.memory_row_snapshot),
                    detail = context.getString(R.string.memory_line_count, lineCountOf(snapshot)) + " · $stamp",
                    fileName = "context-assembly-latest.md",
                    content = snapshot,
                    editable = false,
                    group = AutoGroup.DIAGNOSTIC,
                    lineCount = lineCountOf(snapshot),
                ),
            )
        }
    }

    // Today + yesterday — from the SESSION repository, because that is the
    // one injection reads daily logs from.
    val dailyRepo = sessionRepo ?: memoryRepository
    val today = IsoTime.formatLocalDate()
    val yesterday = IsoTime.formatLocalDate(System.currentTimeMillis() - 86400_000L)

    for (dateStr in listOf(today, yesterday)) {
        val fileName = "$dateStr.md"
        val label = if (dateStr == today) context.getString(R.string.time_today) else context.getString(R.string.time_yesterday)
        val content = dailyRepo.readFile(fileName)
        if (content.isNotBlank()) {
            val lines = content.lines().size
            val injected = minOf(lines, DIARY_INJECTED_LINE_CAP)
            val detail = if (lines > DIARY_INJECTED_LINE_CAP) {
                context.getString(R.string.memory_diary_lines_capped, injected, lines)
            } else {
                context.getString(R.string.memory_diary_lines_full, lines)
            }
            items.add(
                AutoItem(
                    name = context.getString(R.string.memory_diary_row, label, fileName),
                    detail = detail,
                    fileName = fileName,
                    content = content,
                    saver = { text -> dailyRepo.saveFile(fileName, text) },
                    group = AutoGroup.DIARY,
                    scopeLabel = label,
                    lineCount = lines,
                ),
            )
        }
    }

    return items
}

/**
 * Injection reads at most this many lines of a daily log. Kept next to the row
 * that displays it so the "N/M lines injected" summary cannot disagree with
 * what the injector actually takes.
 */
private const val DIARY_INJECTED_LINE_CAP = 200
