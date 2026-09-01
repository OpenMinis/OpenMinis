package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.i18n.uppercaseForDisplay

/**
 * Session Token Usage bottom sheet — mirrors iOS `TokenUsageSheet` and uses
 * the standardized chat sheet shell so its header/dismiss behavior matches
 * every other "⋯" menu sheet.
 *
 * Sections (top to bottom):
 *   - Context: Context Used / Context Window / Max Output
 *   - Thinking (only when the model supports reasoning): On/Off / Level / Supported
 *   - Tokens (Session Total): Input (incl. cache) / Output
 *   - Cache (Session Total): Cache Read / Cache Write
 *   - Agent Loop: Total Loops
 *
 * Data loads asynchronously via [ChatViewModel.loadSessionTokenStats] when the
 * sheet appears; we intentionally don't hold a live subscription — token
 * counters change per API call, not per keystroke, so pull-on-open is enough.
 */
@Composable
fun TokenUsageSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    var stats by remember { mutableStateOf<ChatViewModel.SessionTokenStats?>(null) }
    val contextWindow = remember { viewModel.currentModelContextWindow }
    val maxOutput = remember { viewModel.currentModelMaxOutputTokens }
    val thinking = remember { viewModel.thinkingInfo() }

    LaunchedEffect(Unit) {
        stats = viewModel.loadSessionTokenStats()
    }

    StandardChatSheet(
        title = stringResource(R.string.token_usage_sheet_title),
        onDismiss = onDismiss,
        // T148: iOS uses .presentationDetents([.medium]) for the same sheet
        // (AIChatView.swift:508). Match that proportion on Android so the
        // half-screen feel is consistent — the token-usage view holds maybe
        // a screenful of stat rows max and looked overgrown at 90%.
        heightFraction = 0.5f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val s = stats
            val onText = stringResource(R.string.common_on)
            val offText = stringResource(R.string.common_off)
            val yesText = stringResource(R.string.common_yes)
            val noText = stringResource(R.string.common_no)
            StatSection(title = stringResource(R.string.token_usage_section_context)) {
                StatRow(stringResource(R.string.token_usage_context_used), formatTokens(s?.context ?: 0))
                contextWindow?.let { StatRow(stringResource(R.string.token_usage_context_window), formatTokens(it)) }
                maxOutput?.let { StatRow(stringResource(R.string.token_usage_max_output), formatTokens(it)) }
            }

            thinking?.let { t ->
                StatSection(title = stringResource(R.string.token_usage_section_thinking)) {
                    StatRow(stringResource(R.string.token_usage_thinking_label), if (t.enabled) onText else offText)
                    if (t.enabled) StatRow(stringResource(R.string.token_usage_thinking_level), t.level)
                    StatRow(stringResource(R.string.token_usage_thinking_supported), if (t.supported) yesText else noText)
                }
            }

            StatSection(title = stringResource(R.string.token_usage_section_tokens)) {
                val inputTotal = (s?.input ?: 0L) + (s?.cacheRead ?: 0L) + (s?.cacheWrite ?: 0L)
                StatRow(stringResource(R.string.token_usage_input_with_cache), formatTokens(inputTotal))
                StatRow(stringResource(R.string.token_usage_output), formatTokens(s?.output ?: 0L))
            }

            StatSection(title = stringResource(R.string.token_usage_section_cache)) {
                StatRow(stringResource(R.string.token_usage_cache_read), formatTokens(s?.cacheRead ?: 0L))
                StatRow(stringResource(R.string.token_usage_cache_write), formatTokens(s?.cacheWrite ?: 0L))
            }

            StatSection(title = stringResource(R.string.token_usage_section_agent_loop)) {
                StatRow(stringResource(R.string.token_usage_total_loops), (s?.loopCount ?: 0).toString())
            }
        }
    }
}

@Composable
private fun StatSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title.uppercaseForDisplay(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

// iOS formatting: "%.1fM" >= 1M, "%.1fK" >= 1k, raw otherwise.
private fun formatTokens(n: Long): String = when {
    n >= 1_000_000L -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000L -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}

private fun formatTokens(n: Int): String = formatTokens(n.toLong())
