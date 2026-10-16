package com.openminis.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.ToolLimitPrefs

@Composable
fun ToolLimitsSettingsScreen(onBack: () -> Unit) {
    val revision by ToolLimitPrefs.revision.collectAsState()
    val shell = remember(revision) { ToolLimitPrefs.shellTimeoutSec() }
    val chars = remember(revision) { ToolLimitPrefs.fileReadMaxChars() }
    val lines = remember(revision) { ToolLimitPrefs.fileReadMaxLines() }
    val turns = remember(revision) { ToolLimitPrefs.subagentMaxTurns() }
    // [T-main-session-turn-cap] The main agent loop's runaway guard, next to
    // the sub-agent cap it used to be confused with.
    val mainTurns = remember(revision) { ToolLimitPrefs.mainSessionMaxTurns() }
    var advanced by remember { mutableStateOf(false) }
    val idleCount = remember(revision) { ToolLimitPrefs.idleShellCount() }
    val idleMinutes = remember(revision) { ToolLimitPrefs.idleShellMinutes() }
    val automatic = remember(revision) { ToolLimitPrefs.autoConcurrency() }
    val heavy = remember(revision) { ToolLimitPrefs.heavyConcurrency() }
    val queue = remember(revision) { ToolLimitPrefs.queueTimeoutSec() }

    // Nested under multi-agent (and the tool-limits deep link), not a
    // first-level settings page. 1.36.16 keeps the back arrow here; 1.36.37
    // only drops it on the settings root and its first-level children.
    SettingsScaffold(title = stringResource(R.string.tool_limits_title), onBack = onBack) {
        SettingsSection(
            header = stringResource(R.string.execution_resources),
            footer = stringResource(R.string.execution_resources_help),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.execution_auto),
                checked = automatic,
                onCheckedChange = ToolLimitPrefs::setAutoConcurrency,
            )
            LimitStepper(stringResource(R.string.execution_heavy), heavy.toString(),
                heavy, 1, 4, 1, ToolLimitPrefs::setHeavyConcurrency, false)
        }
        SettingsSection {
            SettingsRow(title = stringResource(R.string.execution_queue), onClick = { advanced = !advanced })
        }
        if (advanced) SettingsSection(footer = stringResource(R.string.execution_idle_help)) {
            LimitStepper(stringResource(R.string.execution_idle_count), idleCount.toString(),
                idleCount, 0, 8, 1, ToolLimitPrefs::setIdleShellCount, true)
            LimitStepper(stringResource(R.string.execution_idle_minutes),
                stringResource(R.string.execution_minutes_value, idleMinutes),
                idleMinutes, 1, 30, 1, ToolLimitPrefs::setIdleShellMinutes, true)
            LimitStepper(stringResource(R.string.execution_queue_timeout),
                if (queue == 0) stringResource(R.string.tool_limits_file_lines_unlimited) else stringResource(R.string.tool_limits_shell_value, queue),
                queue, 0, 1800, 30, ToolLimitPrefs::setQueueTimeoutSec, false)
        }
        SettingsSection(
            header = stringResource(R.string.tool_limits_header),
            footer = stringResource(R.string.tool_limits_footer),
        ) {
            LimitStepper(
                title = stringResource(R.string.tool_limits_shell),
                valueLabel = stringResource(R.string.tool_limits_shell_value, shell),
                value = shell,
                min = ToolLimitPrefs.MIN_SHELL_TIMEOUT_SEC,
                max = ToolLimitPrefs.MAX_SHELL_TIMEOUT_SEC,
                step = ToolLimitPrefs.SHELL_STEP_SEC,
                onChange = { ToolLimitPrefs.setShellTimeoutSec(it) },
                showDivider = true,
            )
            LimitStepper(
                title = stringResource(R.string.tool_limits_file_chars),
                valueLabel = chars.toString(),
                value = chars,
                min = ToolLimitPrefs.MIN_FILE_READ_MAX_CHARS,
                max = ToolLimitPrefs.MAX_FILE_READ_MAX_CHARS,
                step = ToolLimitPrefs.FILE_CHARS_STEP,
                onChange = { ToolLimitPrefs.setFileReadMaxChars(it) },
                showDivider = true,
            )
            LimitStepper(
                title = stringResource(R.string.tool_limits_file_lines),
                valueLabel = if (lines == 0) {
                    stringResource(R.string.tool_limits_file_lines_unlimited)
                } else {
                    lines.toString()
                },
                value = lines,
                min = 0,
                max = ToolLimitPrefs.MAX_FILE_READ_MAX_LINES,
                step = ToolLimitPrefs.FILE_LINES_STEP,
                onChange = { ToolLimitPrefs.setFileReadMaxLines(it) },
                showDivider = true,
            )
            LimitStepper(
                title = stringResource(R.string.tool_limits_subagent),
                valueLabel = turns.toString(),
                value = turns,
                min = ToolLimitPrefs.MIN_SUBAGENT_MAX_TURNS,
                max = ToolLimitPrefs.MAX_SUBAGENT_MAX_TURNS,
                step = ToolLimitPrefs.TURNS_STEP,
                onChange = { ToolLimitPrefs.setSubagentMaxTurns(it) },
                showDivider = true,
            )
            LimitStepper(
                title = stringResource(R.string.tool_limits_main_session),
                valueLabel = mainTurns.toString(),
                value = mainTurns,
                min = ToolLimitPrefs.MIN_MAIN_MAX_TURNS,
                max = ToolLimitPrefs.MAX_MAIN_MAX_TURNS,
                step = ToolLimitPrefs.MAIN_TURNS_STEP,
                onChange = { ToolLimitPrefs.setMainSessionMaxTurns(it) },
                showDivider = false,
            )
        }
    }
}

@Composable
private fun LimitStepper(
    title: String,
    valueLabel: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
    showDivider: Boolean,
) {
    SettingsRow(
        title = title,
        subtitle = valueLabel,
        showChevron = false,
        showDivider = showDivider,
        trailing = {
            PlusMinusStepper(
                value = value,
                min = min,
                max = max,
                step = step,
                onValueChange = onChange,
                decreaseContentDescription = stringResource(R.string.tool_limits_decrease),
                increaseContentDescription = stringResource(R.string.tool_limits_increase),
            )
        },
    )
}
