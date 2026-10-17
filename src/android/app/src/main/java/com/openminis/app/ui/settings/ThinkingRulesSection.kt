package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.thinking.ThinkingRule
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-android-thinking-rules-phase2 §3 / parity with iOS ThinkingRulesSection.swift]
 * Provider-detail "Thinking Rules" section.
 *
 * Behaviour, correct from the start (avoiding the two mistakes iOS shipped then fixed):
 *  1. Built-in rules are collapsed behind a single "Default rules (N)" summary row with a
 *     lock icon — never flat-listed. Tapping one duplicates it as an editable custom rule.
 *  2. Only rules RELEVANT to this provider are shown (relevance-filtered upstream by
 *     [ProviderRepository.builtInThinkingRulesForDisplay]).
 *  3. UI scoping is one-shot: Chat-Completions instances get the full editable list;
 *     Responses-API / Gemini / Anthropic get an explanatory notice (never a hidden
 *     section — silently dropping reads as "feature missing").
 *
 * Reorder is via up/down affordances rather than drag: this section lives inside the
 * provider screen's plain Column (not a LazyColumn), so a drag handle would fight the
 * outer scroll. Up/down is the robust equivalent and keeps sort_order = priority.
 */
@Composable
fun ThinkingRulesSection(
    instance: ProviderInstance,
    providerRepository: ProviderRepository,
) {
    // --- UI scoping: official-protocol providers get a notice, not a list ---
    if (!instance.supportsCustomThinkingRules) {
        SettingsSection(header = stringResource(R.string.thinking_rules_section)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.thinking_rules_official_protocol_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    // reloadKey bumps to force a re-read after any mutation (rules live in Room).
    var reloadKey by remember { mutableStateOf(0) }
    var defaultsExpanded by remember { mutableStateOf(false) }
    var editorRequest by remember { mutableStateOf<ThinkingRuleEditorRequest?>(null) }

    val rulesSnapshot by produceState(
        initialValue = ProviderRepository.ThinkingRulesSnapshot(),
        instance.id,
        reloadKey,
    ) {
        value = withContext(Dispatchers.IO) {
            providerRepository.thinkingRulesSnapshot(instance.id)
        }
    }
    val customRules = rulesSnapshot.customRules
    // The persisted ids parallel the custom rules in stored order (for edit/delete/reorder).
    val customIds = customRules.map { it.id }
    val builtIns = rulesSnapshot.builtIns
    val sampleModelId = rulesSnapshot.sampleModelId

    SettingsSection(
        header = stringResource(R.string.thinking_rules_section),
        footer = stringResource(R.string.thinking_rules_footer),
    ) {
        // --- Custom rules (editable, reorderable) ---
        customRules.forEachIndexed { idx, rule ->
            val ruleId = customIds.getOrNull(idx)
            ThinkingRuleRow(
                title = rule.label,
                subtitle = ruleScopeSummary(rule) + " · " + wireFormatSummary(rule.wireFormat),
                locked = false,
                onClick = {
                    if (ruleId != null) {
                        editorRequest = ThinkingRuleEditorRequest(existingId = ruleId, seed = rule, isNew = false)
                    }
                },
                onMoveUp = if (idx > 0 && ruleId != null) {
                    {
                        val order = customIds.toMutableList()
                        order.add(idx - 1, order.removeAt(idx))
                        providerRepository.reorderThinkingRules(instance.id, order)
                        reloadKey++
                    }
                } else null,
                onMoveDown = if (idx < customRules.lastIndex && ruleId != null) {
                    {
                        val order = customIds.toMutableList()
                        order.add(idx + 1, order.removeAt(idx))
                        providerRepository.reorderThinkingRules(instance.id, order)
                        reloadKey++
                    }
                } else null,
                onDelete = if (ruleId != null) {
                    {
                        providerRepository.deleteThinkingRule(instance.id, ruleId)
                        reloadKey++
                    }
                } else null,
            )
        }

        // --- Default (built-in) rules, collapsed ---
        SettingsRow(
            title = stringResource(R.string.thinking_rules_default_rules_count, builtIns.size),
            icon = Icons.Default.Lock,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = { defaultsExpanded = !defaultsExpanded },
            showChevron = false,
            trailing = {
                Icon(
                    if (defaultsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            showDivider = defaultsExpanded || customRules.isNotEmpty(),
        )
        if (defaultsExpanded) {
            val context = LocalContext.current
            builtIns.forEachIndexed { idx, rule ->
                ThinkingRuleRow(
                    title = rule.label,
                    subtitle = ruleScopeSummary(rule) + " · " + wireFormatSummary(rule.wireFormat),
                    locked = true,
                    // Tapping a built-in seeds a NEW custom rule (duplicate-to-override).
                    onClick = {
                        editorRequest = ThinkingRuleEditorRequest(
                            existingId = null,
                            seed = rule.copy(
                                kind = ThinkingRule.Kind.CUSTOM,
                                label = context.getString(R.string.thinking_rules_copy_prefix, rule.label),
                            ),
                            isNew = true,
                        )
                    },
                    showDivider = idx < builtIns.lastIndex,
                )
            }
        }

        // --- Add rule ---
        SettingsRow(
            title = stringResource(R.string.thinking_rules_add_rule),
            icon = Icons.Default.Add,
            iconColor = MaterialTheme.colorScheme.primary,
            onClick = {
                editorRequest = ThinkingRuleEditorRequest(
                    existingId = null,
                    seed = ThinkingRule(
                        kind = ThinkingRule.Kind.CUSTOM,
                        scope = ThinkingRule.Scope.AllModels,
                        wireFormat = com.openminis.app.provider.thinking.ThinkingWireFormat.ReasoningEffort(null),
                        label = "",
                    ),
                    isNew = true,
                )
            },
            showDivider = false,
        )
    }

    // --- Trace line: which rule the sample model would hit ---
    if (sampleModelId != null) {
        val hit = resolveHitDescription(customRules, builtIns, sampleModelId)
        if (hit != null) {
            Text(
                text = hit,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 4.dp),
            )
        }
    }

    // --- Editor dialog (hoisted here, not inside a row — Dialog has its own window) ---
    editorRequest?.let { req ->
        ThinkingRuleEditorDialog(
            request = req,
            onDismiss = { editorRequest = null },
            onSave = { rule ->
                providerRepository.saveThinkingRule(instance.id, rule, id = req.existingId)
                editorRequest = null
                reloadKey++
            },
        )
    }
}

/** Compact rule row with a lock badge for built-ins and up/down/delete affordances for custom. */
@Composable
private fun ThinkingRuleRow(
    title: String,
    subtitle: String,
    locked: Boolean,
    onClick: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (locked) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title.ifEmpty { "(unnamed rule)" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onMoveUp != null) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.thinking_rules_move_up),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp).clickable(onClick = onMoveUp),
                )
                Spacer(Modifier.width(4.dp))
            }
            if (onMoveDown != null) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.thinking_rules_move_down),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp).clickable(onClick = onMoveDown),
                )
                Spacer(Modifier.width(4.dp))
            }
            if (onDelete != null) {
                Text(
                    text = stringResource(R.string.common_delete),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable(onClick = onDelete).padding(horizontal = 4.dp),
                )
            }
        }
        if (showDivider) SettingsRowInsetDivider()
    }
}

@Composable
private fun SettingsRowInsetDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = 14.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
