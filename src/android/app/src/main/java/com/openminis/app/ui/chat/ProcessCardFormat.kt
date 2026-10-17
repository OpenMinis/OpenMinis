package com.openminis.app.ui.chat

/**
 * [T-process-summary-duration] Formats an aggregated process duration for the
 * fold-bar suffix. Mirrors the OmniBot run-group header granularity: whole
 * seconds under a minute, m+s under an hour, h+m beyond. Returns null for
 * non-positive totals so the bar simply omits the suffix (blocks without a
 * recorded duration must not render "0s").
 *
 * [T-arch-ratchet] Extracted from ChatAssistantMessageUI.kt when the merged
 * thinking-block work pushed that file past its baseline ceiling: both
 * helpers here are pure and already pinned by ChatFlatItemsProcessFoldTest, so
 * the file they lived in could shed 60+ lines without moving any behaviour.
 */
internal fun formatProcessDuration(totalMs: Long): String? {
    if (totalMs <= 0L) return null
    val totalSeconds = totalMs / 1000L
    return when {
        totalSeconds < 60L -> "${totalSeconds}s"
        totalSeconds < 3600L -> "${totalSeconds / 60L}m ${totalSeconds % 60L}s"
        else -> "${totalSeconds / 3600L}h ${totalSeconds % 3600L / 60L}m"
    }
}

/**
 * [P-process-card] What the card is doing right now. Pure so the header
 * copy is testable without composition.
 *
 *  - a settled run reads "思考 N · 工具 M · 时长" — the counts stay so a
 *    finished run is still scannable; no ✅ emoji (user direction: the
 *    check mark reads as noise next to the status icon);
 *  - a running run, collapsed, is the fixed "AI 过程" label with the
 *    counts, with the duration ticking;
 *  - a running run, expanded, drops the counts: the steps are on screen,
 *    so the header is only the current state.
 *
 * "输出中" is deliberately absent. Reply text ends the card on arrival, so
 * that state would render for a single frame and then vanish.
 */
internal fun processCardHeader(
    thinkingCount: Int,
    toolCount: Int,
    running: Boolean,
    expanded: Boolean,
    activity: String,
    activityTitle: String,
    duration: String?,
): String {
    val counts = buildString {
        append("思考 ").append(thinkingCount)
        append(" · 工具 ").append(toolCount)
        if (duration != null) append(" · ").append(duration)
    }
    val state = when (activity) {
        "thinking" -> "思考中"
        "tool" -> if (activityTitle.isBlank()) "调用工具" else "调用工具 $activityTitle"
        else -> ""
    }
    return when {
        // Settled (reply text arrived, or a pure-tool/error/timeout round
        // ended): always the done summary.
        !running -> counts
        // Running + expanded: the steps are on screen, so the header shows
        // only the live state; fall back to the generic label when idle.
        expanded -> state.ifEmpty { "AI 过程" }
        // Running + collapsed: fixed label with ticking duration. The live
        // state stays out — that is what the expanded header is for.
        else -> "AI 过程 · $counts"
    }
}
