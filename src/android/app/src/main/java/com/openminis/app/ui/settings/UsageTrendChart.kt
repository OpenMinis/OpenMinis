package com.openminis.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/**
 * Time-range selector row for the Usage page. Four [FilterChip]s in a
 * horizontally-spaced row; the selected chip drives the aggregation window.
 */
@Composable
internal fun UsageTimeFilterRow(
    selected: UsageTimeRange,
    onSelect: (UsageTimeRange) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        UsageTimeRange.entries.forEach { range ->
            FilterChip(
                selected = selected == range,
                onClick = { onSelect(range) },
                label = { Text(rangeLabel(range)) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                ),
            )
        }
    }
}

@Composable
private fun rangeLabel(range: UsageTimeRange): String = when (range) {
    UsageTimeRange.TODAY -> stringResource(R.string.usage_range_today)
    UsageTimeRange.LAST_7 -> stringResource(R.string.usage_range_7d)
    UsageTimeRange.LAST_30 -> stringResource(R.string.usage_range_30d)
    UsageTimeRange.ALL -> stringResource(R.string.usage_range_all)
}

/**
 * Daily usage bar chart, hand-drawn on [Canvas] (no chart library — the
 * project draws its own visuals). Two bars per day slot: input (primary) and
 * output (secondary). Tapping a slot selects it and shows that day's numbers
 * under the chart; the selected slot is highlighted.
 *
 * [daily] must be a fixed-length, zero-filled series (see
 * [buildDailySeries]) so slot count never changes mid-interaction.
 */
@Composable
internal fun UsageTrendChart(
    daily: List<DailyUsage>,
    modifier: Modifier = Modifier,
) {
    if (daily.isEmpty()) return
    // Tap-select index; coerced on every read instead of being clamped
    // during composition (writing state mid-composition restarts the frame).
    var selected by remember { mutableIntStateOf(daily.lastIndex) }
    val sel = selected.coerceIn(daily.indices)

    val inputColor = MaterialTheme.colorScheme.primary
    val outputColor = MaterialTheme.colorScheme.tertiary
    val selectedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val emptyColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val hasAnyData = daily.any { it.inputTokens > 0 || it.outputTokens > 0 }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(horizontal = 16.dp)
                .pointerInput(daily.size) {
                    detectTapGestures { offset ->
                        val slot = (offset.x / size.width * daily.size).toInt()
                        if (slot in daily.indices) selected = slot
                    }
                },
        ) {
            val slotWidth = size.width / daily.size
            val barWidth = slotWidth * 0.30f
            val gap = slotWidth * 0.06f
            val maxVal = maxOf(
                daily.maxOf { maxOf(it.inputTokens, it.outputTokens) },
                1L,
            ).toFloat()
            val chartHeight = size.height - 4.dp.toPx()
            daily.forEachIndexed { idx, day ->
                val slotLeft = idx * slotWidth
                val inputH = (day.inputTokens / maxVal) * chartHeight
                val outputH = (day.outputTokens / maxVal) * chartHeight
                val inputBar = day.inputTokens > 0
                val outputBar = day.outputTokens > 0
                // Input bar (left half of the slot)
                if (inputBar) {
                    drawRoundRect(
                        color = if (idx == sel) inputColor else inputColor.copy(alpha = 0.75f),
                        topLeft = Offset(slotLeft + slotWidth / 2 - barWidth - gap, size.height - inputH),
                        size = Size(barWidth, inputH),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
                // Output bar (right half)
                if (outputBar) {
                    drawRoundRect(
                        color = if (idx == sel) outputColor else outputColor.copy(alpha = 0.75f),
                        topLeft = Offset(slotLeft + slotWidth / 2 + gap, size.height - outputH),
                        size = Size(barWidth, outputH),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
                // Empty slot baseline so the chart keeps its rhythm on dry days
                if (!inputBar && !outputBar) {
                    drawRoundRect(
                        color = emptyColor,
                        topLeft = Offset(slotLeft + slotWidth / 2 - barWidth, size.height - 2.dp.toPx()),
                        size = Size(barWidth * 2, 2.dp.toPx()),
                        cornerRadius = CornerRadius(1.dp.toPx()),
                    )
                }
                // Selected slot backdrop
                if (idx == sel) {
                    drawRoundRect(
                        color = selectedColor,
                        topLeft = Offset(slotLeft + slotWidth * 0.08f, 0f),
                        size = Size(slotWidth * 0.84f, size.height),
                        cornerRadius = CornerRadius(4.dp.toPx()),
                    )
                }
            }
        }

        // Axis labels: first / middle / last day, compact MM-dd
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val mid = daily[daily.size / 2]
            Text(shortDate(daily.first().dateKey), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(shortDate(mid.dateKey), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(shortDate(daily.last().dateKey), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(6.dp))

        // Selected-day detail
        val day = daily[sel]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(day.dateKey, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hasAnyData) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegendDot(inputColor)
                    Text(
                        formatCount(day.inputTokens),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Default,
                    )
                    Spacer(Modifier.width(10.dp))
                    LegendDot(outputColor)
                    Text(
                        formatCount(day.outputTokens),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Default,
                    )
                }
            } else {
                Text(
                    stringResource(R.string.usage_chart_no_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .width(6.dp)
            .height(6.dp)
            .background(color, MaterialTheme.shapes.small),
    )
}

/** `2026-10-18` → `10-18` for axis labels. */
private fun shortDate(dateKey: String): String =
    if (dateKey.length >= 10) dateKey.substring(5) else dateKey
