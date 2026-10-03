package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.SubAgentLimits
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.tools.AgentToolSwitch

/** [T-sub-agents-v1] Sentinel id for a definition being created. */
internal const val DRAFT_AGENT_ID = "__new__"

/**
 * [T-sub-agents-v1] Settings › Sub Agents — the roster the main model delegates
 * to by name.
 *
 * Port of iOS HelperSettingsView. The list is the disclosure order the model
 * sees, and the built-in is always first and cannot be deleted, so the roster
 * can never be empty — `subagent_task.agent` always has at least one valid
 * value.
 */
@Composable
fun SubAgentsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val repo = remember { (context.applicationContext as MinisApp).providerRepository }
    // Re-read on every recomposition rather than caching: an edit made on the
    // detail screen must be visible on the way back, and the roster is bounded
    // at 10 so re-normalising is free.
    var generation by remember { mutableStateOf(0) }
    val roster = remember(generation) { repo.subAgents }

    // [T-android-subagent-settings-parity] This page OWNS the agents switch,
    // matching iOS HelperSettingsView: one place to look, no cross-reference to
    // keep in sync with Settings > Tools.
    var agentsEnabled by remember { mutableStateOf(AgentToolSwitch.AGENTS.isEnabled(context)) }

    SettingsScaffold(title = stringResource(R.string.sub_agents_title), onBack = onBack) {
        SettingsSection(footer = stringResource(R.string.agent_settings_allow_footer)) {
            // Local title row rather than SettingsSwitchRow, because this row
            // carries a SECOND line — the tool's wire name — under the title,
            // and that component's `title` is a plain String with no slot for
            // it. Everything else (metrics, colours, the switch) matches what
            // SettingsRow/SettingsSwitchRow produce, so the card still reads as
            // one of the standard rows.
            //
            // [T-android-subagent-exp-badge] It was originally local to seat an
            // "Exp" badge inline with the title; that badge is gone, but the
            // second line still keeps it local.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        agentsEnabled = !agentsEnabled
                        AgentToolSwitch.AGENTS.setEnabled(context, agentsEnabled)
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    // [T-android-subagent-exp-badge] The title stands alone now.
                    // The Row and its Spacer existed only to seat an "Exp" chip
                    // beside it; sub agents ship on by default, so the chip and
                    // its wrapper are gone rather than left as an empty layer.
                    Text(
                        stringResource(R.string.sub_agents_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // The tool's wire name, as iOS shows it: the reader can
                    // match what they see here against a transcript.
                    Text(
                        SubAgentDefinition.TOOL_NAME,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                SettingsSwitch(
                    checked = agentsEnabled,
                    onCheckedChange = {
                        agentsEnabled = it
                        AgentToolSwitch.AGENTS.setEnabled(context, it)
                    },
                )
            }
        }
        // Dimmed but still editable when the switch is off: off means "not
        // offered to the model", not "cannot be configured" — the same rule the
        // other tool switches follow. Matches iOS's .opacity(enabled ? 1 : 0.5).
        Column(Modifier.alpha(if (agentsEnabled) 1f else 0.5f)) {
        SettingsSection(
            header = stringResource(R.string.sub_agents_list_header),
            footer = stringResource(R.string.sub_agents_list_footer),
        ) {
            roster.forEachIndexed { i, def ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        SubAgentRosterRow(
                            name = def.displayName(context),
                            isBuiltIn = def.isBuiltIn,
                            description = def.displayDescription(context),
                            modelLabel = def.modelGroupId?.let { repo.group(it)?.name }
                                ?: stringResource(R.string.sub_agent_model_auto),
                            onClick = { onOpen(def.id) },
                            showDivider = i < roster.lastIndex,
                        )
                    }
                    // [T-android-subagent-settings-parity] Order IS meaningful:
                    // it is the order the roster is disclosed to the model, so
                    // iOS exposes drag-to-reorder. Up/down buttons instead of a
                    // drag handle because this screen is a plain settings list,
                    // not the reorderable LazyColumn Model Groups is built on —
                    // the capability is what matters, and the repo API
                    // (reorderSubAgents) already existed with no caller.
                    //
                    // The built-in is pinned to index 0 by normalize(), so it
                    // can neither move nor be displaced by the row below it.
                    if (roster.size > 1 && !def.isBuiltIn) {
                        IconButton(
                            onClick = { move(repo, roster, i, i - 1); generation++ },
                            enabled = i > 1,
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.sub_agents_move_up),
                            )
                        }
                        IconButton(
                            onClick = { move(repo, roster, i, i + 1); generation++ },
                            enabled = i < roster.lastIndex,
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.sub_agents_move_down),
                            )
                        }
                    }
                }
            }
        }
        if (roster.size < SubAgentLimits.MAX_COUNT) {
            SettingsSection {
                SettingsValueRow(
                    title = stringResource(R.string.sub_agents_add),
                    value = "",
                    icon = Icons.Default.Add,
                    onClick = {
                        // [T-sub-agents-v1] Nothing is persisted yet: a
                        // nameless definition is dropped by normalize (the name
                        // is the tool schema's enum value), so writing one here
                        // and reading the roster back would lose the row the
                        // editor is about to open. The editor persists on the
                        // first valid save instead.
                        onOpen(DRAFT_AGENT_ID)
                    },
                    showDivider = false,
                )
            }
        } else {
            SettingsSection {
                Text(
                    stringResource(R.string.sub_agents_limit_reached, SubAgentLimits.MAX_COUNT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
        }
    }
}

/**
 * [T-sub-agents-v1] One definition's editor.
 *
 * The built-in's name and description are read-only: they are the tool-schema
 * enum value and the roster text the model reads, and both are canonical
 * English restored on every load. Its model and instructions ARE editable —
 * those are the user's own choices.
 */
@Composable
fun SubAgentDetailScreen(agentId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { (context.applicationContext as MinisApp).providerRepository }
    val isDraft = agentId == DRAFT_AGENT_ID
    val original = remember(agentId) {
        if (isDraft) {
            SubAgentDefinition(name = "", description = "", sortOrder = repo.subAgents.size)
        } else {
            repo.subAgent(agentId)
        }
    }
    if (original == null) {
        // Deleted from another screen while this one was open.
        SettingsScaffold(title = stringResource(R.string.sub_agents_title), onBack = onBack) {}
        return
    }

    var name by remember(agentId) { mutableStateOf(original.name) }
    var description by remember(agentId) { mutableStateOf(original.description) }
    var instructions by remember(agentId) { mutableStateOf(original.instructions) }
    var groupId by remember(agentId) { mutableStateOf(original.modelGroupId) }
    var thinking by remember(agentId) { mutableStateOf(original.thinkingLevelOverride) }
    var error by remember(agentId) { mutableStateOf<String?>(null) }

    val groups = remember { repo.config.value.modelGroups.toList() }
    val nameTaken = stringResource(R.string.sub_agent_name_taken)
    val nameRequired = stringResource(R.string.sub_agent_name_required)

    fun persist() {
        if (original.isBuiltIn) {
            // Name and description are fixed; only the user's own fields move.
            repo.upsertSubAgent(
                original.copy(
                    instructions = instructions,
                    modelGroupId = groupId,
                    thinkingLevelOverride = thinking,
                ),
            )
            error = null
            return
        }
        val trimmed = name.trim()
        if (trimmed.isEmpty()) { error = nameRequired; return }
        // The name is an identifier the model emits, so a duplicate would make
        // resolution ambiguous — first match wins and the user cannot tell why.
        val clash = repo.subAgents.any {
            it.id != original.id && SubAgentRoster.nameKey(it.name) == SubAgentRoster.nameKey(trimmed)
        }
        if (clash) { error = nameTaken; return }
        error = null
        repo.upsertSubAgent(
            original.copy(
                name = trimmed,
                description = description.trim(),
                instructions = instructions,
                modelGroupId = groupId,
                thinkingLevelOverride = thinking,
            ),
        )
    }

    SettingsScaffold(
        title = original.displayName(context).ifEmpty { stringResource(R.string.sub_agents_title) },
        onBack = { persist(); onBack() },
    ) {
        if (original.isBuiltIn) {
            SettingsSection(footer = stringResource(R.string.sub_agent_builtin_note)) {
                SettingsValueRow(
                    title = stringResource(R.string.sub_agent_name),
                    value = original.displayName(context),
                    showDivider = false,
                )
            }
        } else {
            SettingsSection(footer = error) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(SubAgentLimits.NAME_MAX_LENGTH) },
                        label = { Text(stringResource(R.string.sub_agent_name)) },
                        singleLine = true,
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FieldCounter(name.length, SubAgentLimits.NAME_MAX_LENGTH)
                }
            }
            SettingsSection(footer = stringResource(R.string.sub_agent_description_footer)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.take(SubAgentLimits.DESCRIPTION_MAX_LENGTH) },
                        label = { Text(stringResource(R.string.sub_agent_description)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FieldCounter(description.length, SubAgentLimits.DESCRIPTION_MAX_LENGTH)
                }
            }
        }

        // [T-android-subagent-settings-parity] Two footers, as iOS has: the
        // built-in's instructions are the default for EVERY delegation that
        // names no agent, while a custom agent's apply only to itself. Without
        // saying so, a user would reasonably read the built-in's box as a
        // global prefix — it deliberately is not.
        SettingsSection(
            footer = stringResource(
                if (original.isBuiltIn) R.string.sub_agent_instructions_footer_builtin
                else R.string.sub_agent_instructions_footer,
            ),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it.take(SubAgentLimits.INSTRUCTIONS_MAX_LENGTH) },
                    label = { Text(stringResource(R.string.sub_agent_instructions)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldCounter(instructions.length, SubAgentLimits.INSTRUCTIONS_MAX_LENGTH)
            }
        }

        SettingsSection(
            header = stringResource(R.string.sub_agent_model),
            footer = stringResource(R.string.sub_agent_model_footer),
        ) {
            SettingsChoiceRow(
                title = stringResource(R.string.sub_agent_model_auto),
                selected = groupId == null,
                onSelect = { groupId = null; persist() },
            )
            groups.forEachIndexed { i, g ->
                SettingsChoiceRow(
                    title = g.name,
                    selected = groupId == g.id,
                    onSelect = { groupId = g.id; persist() },
                    showDivider = i < groups.lastIndex,
                )
            }
        }

        // [T-subagent-thinking-override] Same shape as a Model Group's Session
        // Defaults: a switch for "set one at all", then an intensity picker.
        SettingsSection(
            header = stringResource(R.string.sub_agent_reasoning),
            footer = stringResource(
                if (thinking == null) R.string.sub_agent_reasoning_footer_off
                else R.string.sub_agent_reasoning_footer_on,
            ),
        ) {
            val on = thinking != null
            SettingsSwitchRow(
                title = stringResource(R.string.sub_agent_reasoning_override),
                checked = on,
                onCheckedChange = { enabled ->
                    thinking = if (enabled) (thinking ?: ThinkingLevel.MEDIUM) else null
                    persist()
                },
                icon = Icons.Default.Psychology,
                iconColor = Color(0xFFAF52DE),
                showDivider = on,
            )
            if (on) {
                // Ceiling from the pinned group's reasoning-capable members,
                // derived as ModelGroupDetailScreen derives it so the two lists
                // agree. On Auto there is no group to ask — the delegating model
                // picks one per task — so every tier is offered and the request
                // path clamps per model as it already does.
                val entries = remember { repo.config.value.modelEntries.toList() }
                val ceiling = remember(groupId, entries) {
                    val members = groupId?.let { gid -> groups.find { it.id == gid } }?.memberEntryIds
                    if (members == null) {
                        ThinkingLevel.ULTRA
                    } else {
                        members.mapNotNull { entryId ->
                            entries.find { it.id == entryId }?.effectiveMaxThinkingLevel
                        }.filter { it != ThinkingLevel.OFF }.maxByOrNull { it.rank }
                            ?: ThinkingLevel.XHIGH
                    }
                }
                val labelFor: @Composable (ThinkingLevel) -> String = { level ->
                    when (level) {
                        ThinkingLevel.LOW -> stringResource(R.string.model_group_detail_thinking_low)
                        ThinkingLevel.MEDIUM -> stringResource(R.string.model_group_detail_thinking_medium)
                        ThinkingLevel.HIGH -> stringResource(R.string.model_group_detail_thinking_high)
                        ThinkingLevel.XHIGH -> stringResource(R.string.model_group_detail_thinking_xhigh)
                        ThinkingLevel.MAX -> stringResource(R.string.model_group_detail_thinking_max)
                        ThinkingLevel.ULTRA -> stringResource(R.string.model_group_detail_thinking_ultra)
                        ThinkingLevel.OFF -> stringResource(R.string.model_group_detail_thinking_low)
                    }
                }
                val cases = ThinkingLevel.entries
                    .filter { it != ThinkingLevel.OFF && it.rank <= ceiling.rank }
                    .map { it to labelFor(it) }
                SettingsCardBlock {
                    Text(
                        text = stringResource(R.string.model_group_detail_intensity),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    // [T-android-thinking-segment-overflow] Six segments do not
                    // fit a phone's width with Material3's defaults: each
                    // SegmentedButton reserves an icon slot for the selected
                    // check plus its own horizontal padding, leaving so little
                    // room for the label that "Medium" wrapped to "Medi/um" and
                    // "XHigh" to "XHig/h" — the labels visibly broke mid-word
                    // and spilled past their segments.
                    //
                    // Two changes, both about giving the text the width:
                    //   - drop the icon slot (`icon = {}`) — the selected
                    //     segment is already filled, so the check was spending
                    //     ~24dp per segment to repeat what the fill says;
                    //   - a 10sp label with maxLines=1 and softWrap=false, so
                    //     the text can never break mid-word again. Six segments
                    //     leave ~55dp each, and labelSmall's 11sp still made
                    //     "Medium" the widest thing in the row.
                    //
                    // SegmentedButton takes no contentPadding in this Material3
                    // version, so the width has to come from the label itself.
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        cases.forEachIndexed { idx, (level, label) ->
                            SegmentedButton(
                                selected = thinking == level,
                                onClick = { thinking = level; persist() },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = idx,
                                    count = cases.size,
                                ),
                                icon = {},
                            ) {
                                Text(
                                    label,
                                    // One size below labelSmall: six segments on a phone leave
                                    // ~55dp each, and labelSmall's 11sp still made
                                    // "Medium" the widest thing in the row.
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!original.isBuiltIn && !isDraft) {
            SettingsSection {
                SettingsValueRow(
                    title = stringResource(R.string.sub_agent_delete),
                    value = "",
                    valueColor = MaterialTheme.colorScheme.error,
                    onClick = { repo.deleteSubAgent(agentId); onBack() },
                    showDivider = false,
                )
            }
        }
    }
}

/**
 * [T-android-subagent-settings-parity] `count/limit` under a bounded field,
 * as iOS shows.
 *
 * These limits are not arbitrary: the roster goes into the main conversation's
 * system prompt on EVERY turn, so description length is a recurring per-request
 * cost. Silently truncating at the limit (which onValueChange already does)
 * leaves the user wondering where their text went; showing the count is what
 * makes the ceiling legible before they hit it.
 */
@Composable
private fun FieldCounter(count: Int, limit: Int) {
    Text(
        "$count/$limit",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
    )
}

/**
 * [T-android-subagent-settings-parity] Swap two roster entries and persist.
 *
 * Guarded rather than trusting the caller's enabled-state: index 0 is the
 * built-in, which normalize() pins to the front, so a move that would displace
 * it is silently a no-op instead of a write the loader would undo on the next
 * read.
 */
private fun move(
    repo: ProviderRepository,
    roster: List<SubAgentDefinition>,
    from: Int,
    to: Int,
) {
    if (from !in roster.indices || to !in roster.indices) return
    if (from == 0 || to == 0) return
    val ids = roster.map { it.id }.toMutableList()
    ids.add(to, ids.removeAt(from))
    repo.reorderSubAgents(ids)
}

/**
 * [T-android-subagent-page-parity] One roster row, laid out like iOS's.
 *
 * `SettingsValueRow` cannot express this: iOS puts a "Built-in" chip INLINE
 * with the name (so the undeletable entry is identifiable at a glance) and
 * stacks the model label as a third line under the description, rather than
 * putting it on the right where a value normally goes. Android was rendering
 * the model as a trailing value and dropping the chip entirely, so the
 * built-in looked like any other row until you opened it and found the name
 * field read-only.
 */
@Composable
private fun SubAgentRosterRow(
    name: String,
    isBuiltIn: Boolean,
    description: String,
    modelLabel: String,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Groups,
                contentDescription = null,
                tint = com.openminis.app.ui.chat.HelperAccentStatic,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isBuiltIn) {
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.sub_agent_builtin_badge),
                    fontSize = 10.sp,
                    // [T-android-settings-chip-height] A chip should hug its
                    // text. `vertical = 1.dp` already said so, but a Compose
                    // Text carries includeFontPadding plus the font's own line
                    // spacing, so a 10sp label occupied noticeably more than
                    // 10sp and the pill read as a button. lineHeight pins the
                    // box to the glyph; the padding is what remains visible.
                    lineHeight = 11.sp,
                    style = LocalTextStyle.current.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                            RoundedCornerShape(50),
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        if (description.isNotEmpty()) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 32.dp, top = 2.dp),
            )
        }
        Text(
            modelLabel,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            modifier = Modifier.padding(start = 32.dp, top = 2.dp),
        )
    }
    if (showDivider) {
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(start = 48.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}
