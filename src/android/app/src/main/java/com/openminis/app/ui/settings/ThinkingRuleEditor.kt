package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.thinking.ThinkingRule
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.provider.thinking.ThinkingWireFormat
import org.json.JSONObject

/** What the section asks the editor to open with. */
data class ThinkingRuleEditorRequest(
    /** Non-null → editing an existing custom rule (preserve id). Null → creating new. */
    val existingId: String?,
    val seed: ThinkingRule,
    val isNew: Boolean,
)

/**
 * [T-android-thinking-rules-phase2 §3] Full-screen-ish dialog to add/edit a custom rule.
 * A Dialog (own window) — deliberately NOT a sheet hosted by a section, which is the
 * navigation trap iOS hit (the sheet dismissed the whole settings stack).
 *
 * User-selectable wire formats are a deliberate SUBSET (the Chat-Completions family):
 * Gemini/Anthropic formats are model-generation dependent and would only build broken
 * rules on this path, so they are not offered.
 */
@Composable
fun ThinkingRuleEditorDialog(
    request: ThinkingRuleEditorRequest,
    onDismiss: () -> Unit,
    onSave: (ThinkingRule) -> Unit,
) {
    val seed = request.seed
    var label by remember { mutableStateOf(seed.label) }
    var allModels by remember { mutableStateOf(seed.scope is ThinkingRule.Scope.AllModels) }
    var pattern by remember {
        mutableStateOf((seed.scope as? ThinkingRule.Scope.ModelPattern)?.pattern ?: "")
    }
    var choice by remember { mutableStateOf(FormatChoice.from(seed.wireFormat)) }
    // Shared editable params across formats.
    var sendOffValue by remember { mutableStateOf(formatOffValue(seed.wireFormat) != null) }
    var offValue by remember { mutableStateOf(formatOffValue(seed.wireFormat) ?: "") }
    var path by remember { mutableStateOf(formatPath(seed.wireFormat) ?: "") }
    var highValue by remember {
        mutableStateOf((seed.wireFormat as? ThinkingWireFormat.CustomPath)?.values?.get(ThinkingLevel.HIGH) ?: "")
    }
    var formatMenuOpen by remember { mutableStateOf(false) }

    fun buildWireFormat(): ThinkingWireFormat = when (choice) {
        FormatChoice.OMIT -> ThinkingWireFormat.OmitEverything
        FormatChoice.REASONING_EFFORT -> ThinkingWireFormat.ReasoningEffort(if (sendOffValue) offValue.ifBlank { null } else null)
        FormatChoice.REASONING_EFFORT_NESTED -> ThinkingWireFormat.ReasoningEffortNested(if (sendOffValue) offValue.ifBlank { null } else null)
        FormatChoice.BOOLEAN_TOGGLE -> ThinkingWireFormat.BooleanToggle(path.ifBlank { "thinking" })
        FormatChoice.EXTRA_BODY_TOGGLE -> ThinkingWireFormat.ExtraBodyToggle(path.ifBlank { "thinking.enabled" })
        FormatChoice.DEEPSEEK_SIBLING -> ThinkingWireFormat.DeepSeekSibling
        FormatChoice.QWEN_DUAL -> ThinkingWireFormat.QwenDual
        FormatChoice.CUSTOM_PATH -> ThinkingWireFormat.CustomPath(
            path = path,
            values = if (highValue.isNotBlank()) mapOf(ThinkingLevel.HIGH to highValue) else emptyMap(),
            offValue = if (sendOffValue) offValue.ifBlank { null } else null,
        )
    }

    fun buildRule() = ThinkingRule(
        kind = ThinkingRule.Kind.CUSTOM,
        scope = if (allModels) ThinkingRule.Scope.AllModels else ThinkingRule.Scope.ModelPattern(pattern.trim()),
        wireFormat = buildWireFormat(),
        label = label.trim(),
    )

    val isValid = label.isNotBlank() &&
        (allModels || pattern.isNotBlank()) &&
        (choice != FormatChoice.CUSTOM_PATH || path.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (request.isNew) R.string.thinking_rules_new_rule else R.string.thinking_rules_edit_rule,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Name
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.thinking_rules_rule_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(6.dp))

                // Applies to
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.thinking_rules_all_models))
                    SettingsSwitch(checked = allModels, onCheckedChange = { allModels = it })
                }
                if (!allModels) {
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it },
                        label = { Text(stringResource(R.string.thinking_rules_model_pattern)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.thinking_rules_glob_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.padding(6.dp))

                // What to send (format picker)
                Text(
                    stringResource(R.string.thinking_rules_what_to_send),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column {
                    MinisTextButton(onClick = { formatMenuOpen = true }) {
                        Text(stringResource(choice.titleRes))
                    }
                    DropdownMenu(expanded = formatMenuOpen, onDismissRequest = { formatMenuOpen = false }) {
                        FormatChoice.entries.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(stringResource(c.titleRes)) },
                                onClick = { choice = c; formatMenuOpen = false },
                            )
                        }
                    }
                }
                Text(
                    stringResource(choice.explanationRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Per-format fields
                when (choice) {
                    FormatChoice.REASONING_EFFORT, FormatChoice.REASONING_EFFORT_NESTED -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(stringResource(R.string.thinking_rules_send_off_value))
                            SettingsSwitch(checked = sendOffValue, onCheckedChange = { sendOffValue = it })
                        }
                        if (sendOffValue) {
                            OutlinedTextField(
                                value = offValue,
                                onValueChange = { offValue = it },
                                label = { Text(stringResource(R.string.thinking_rules_off_value)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    FormatChoice.BOOLEAN_TOGGLE, FormatChoice.EXTRA_BODY_TOGGLE -> {
                        OutlinedTextField(
                            value = path,
                            onValueChange = { path = it },
                            label = { Text(stringResource(R.string.thinking_rules_field_path)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    FormatChoice.CUSTOM_PATH -> {
                        OutlinedTextField(
                            value = path,
                            onValueChange = { path = it },
                            label = { Text(stringResource(R.string.thinking_rules_dotted_path)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = highValue,
                            onValueChange = { highValue = it },
                            label = { Text(stringResource(R.string.thinking_rules_value_at_high)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> {}
                }

                // Request preview
                Spacer(Modifier.padding(6.dp))
                Text(
                    stringResource(R.string.thinking_rules_request_preview),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = previewJson(buildRule()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            MinisTextButton(onClick = { if (isValid) onSave(buildRule()) }, enabled = isValid) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** The user-selectable wire formats (a subset — Gemini/Anthropic families excluded). */
enum class FormatChoice(val titleRes: Int, val explanationRes: Int) {
    OMIT(R.string.thinking_fmt_omit_title, R.string.thinking_fmt_omit_desc),
    REASONING_EFFORT(R.string.thinking_fmt_effort_title, R.string.thinking_fmt_effort_desc),
    REASONING_EFFORT_NESTED(R.string.thinking_fmt_effort_nested_title, R.string.thinking_fmt_effort_nested_desc),
    BOOLEAN_TOGGLE(R.string.thinking_fmt_bool_title, R.string.thinking_fmt_bool_desc),
    EXTRA_BODY_TOGGLE(R.string.thinking_fmt_extrabody_title, R.string.thinking_fmt_extrabody_desc),
    DEEPSEEK_SIBLING(R.string.thinking_fmt_deepseek_title, R.string.thinking_fmt_deepseek_desc),
    QWEN_DUAL(R.string.thinking_fmt_qwen_title, R.string.thinking_fmt_qwen_desc),
    CUSTOM_PATH(R.string.thinking_fmt_custom_title, R.string.thinking_fmt_custom_desc);

    companion object {
        fun from(fmt: ThinkingWireFormat?): FormatChoice = when (fmt) {
            is ThinkingWireFormat.OmitEverything -> OMIT
            is ThinkingWireFormat.ReasoningEffort -> REASONING_EFFORT
            is ThinkingWireFormat.ReasoningEffortNested -> REASONING_EFFORT_NESTED
            is ThinkingWireFormat.BooleanToggle -> BOOLEAN_TOGGLE
            is ThinkingWireFormat.ExtraBodyToggle -> EXTRA_BODY_TOGGLE
            is ThinkingWireFormat.DeepSeekSibling -> DEEPSEEK_SIBLING
            is ThinkingWireFormat.QwenDual -> QWEN_DUAL
            is ThinkingWireFormat.CustomPath -> CUSTOM_PATH
            else -> REASONING_EFFORT
        }
    }
}

private fun formatOffValue(fmt: ThinkingWireFormat?): String? = when (fmt) {
    is ThinkingWireFormat.ReasoningEffort -> fmt.offValue
    is ThinkingWireFormat.ReasoningEffortNested -> fmt.offValue
    is ThinkingWireFormat.CustomPath -> fmt.offValue
    else -> null
}

private fun formatPath(fmt: ThinkingWireFormat?): String? = when (fmt) {
    is ThinkingWireFormat.BooleanToggle -> fmt.path
    is ThinkingWireFormat.ExtraBodyToggle -> fmt.path
    is ThinkingWireFormat.CustomPath -> fmt.path
    else -> null
}

/** Live preview: run the real resolver with this rule at HIGH against a sample body. */
private fun previewJson(rule: ThinkingRule): String {
    return try {
        val body = JSONObject()
        // Isolate: inject just this one rule as the sole custom rule under a throwaway id.
        val previewInstance = "__preview__"
        ThinkingRuleResolver.setCustomRules(previewInstance, listOf(rule))
        val ctx = ThinkingResolveContext(
            modelId = (rule.scope as? ThinkingRule.Scope.ModelPattern)?.pattern?.replace("*", "x") ?: "sample-model",
            instanceId = previewInstance,
            supportsReasoning = true,
            declaredEffortValues = null,
            level = ThinkingLevel.HIGH,
            maxTokens = 4096,
            isOpenRouter = false,
            usesUnifiedReasoningEffort = false,
            isMistral = false,
            isDashScope = false,
            offEffort = null,
        )
        ThinkingRuleResolver.apply(body, ctx)
        ThinkingRuleResolver.setCustomRules(previewInstance, emptyList())
        if (body.length() == 0) "{ }  (no thinking key sent)" else body.toString(2)
    } catch (e: Exception) {
        "(preview unavailable)"
    }
}

// ---- Summaries shared with the section list ----

fun ruleScopeSummary(rule: ThinkingRule): String = when (val s = rule.scope) {
    is ThinkingRule.Scope.AllModels -> "All models"
    is ThinkingRule.Scope.ModelPattern -> s.pattern
}

fun wireFormatSummary(fmt: ThinkingWireFormat?): String = when (fmt) {
    null -> "no opinion"
    is ThinkingWireFormat.OmitEverything -> "send nothing"
    is ThinkingWireFormat.ReasoningEffort -> "reasoning_effort" + (fmt.offValue?.let { " · off = $it" } ?: "")
    is ThinkingWireFormat.ReasoningEffortNested -> "reasoning.effort" + (fmt.offValue?.let { " · off = $it" } ?: "")
    is ThinkingWireFormat.DeepSeekSibling -> "thinking + reasoning_effort"
    is ThinkingWireFormat.QwenDual -> "enable_thinking + budget"
    is ThinkingWireFormat.AnthropicThinking -> "anthropic thinking"
    is ThinkingWireFormat.GeminiBudget -> "thinkingBudget · floor ${fmt.floor}"
    is ThinkingWireFormat.GeminiThinkingLevel -> "thinkingLevel"
    is ThinkingWireFormat.BooleanToggle -> "boolean toggle · ${fmt.path}"
    is ThinkingWireFormat.ExtraBodyToggle -> "extra_body toggle · ${fmt.path}"
    is ThinkingWireFormat.CustomPath -> "custom path · ${fmt.path}"
}

/** "sample-model → rule #N "label"" — the resolution trace shown under the section. */
fun resolveHitDescription(
    custom: List<ThinkingRule>,
    builtIns: List<ThinkingRule>,
    sampleModelId: String,
): String? {
    val all = custom + builtIns
    val idx = all.indexOfFirst { it.scope.matches(sampleModelId) }
    if (idx < 0) return null
    val rule = all[idx]
    return "$sampleModelId → rule #${idx + 1} “${rule.label}”"
}
