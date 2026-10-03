package com.openminis.app.service

import androidx.annotation.DrawableRes
import com.openminis.app.R
import com.openminis.app.ui.chat.friendlyToolTitleFor
import com.openminis.app.ui.chat.toolIconResFor

/**
 * [T-android-live-update-content] Pure content model for the ongoing agent
 * notification / Android 16 Live Update chip. Everything here is a function
 * of tracker state so it can be unit-tested without a Service or a device,
 * and so the notification and the floating overlay resolve the same
 * phase from the same inputs.
 *
 * The phases mirror what the iOS Dynamic Island and the Android overlay
 * capsule already distinguish:
 *  - [COMPLETED]  the run finished and the notification is resting
 *  - [TOOL]       a tool call is executing (icon/title identify the tool)
 *  - [THINKING]   the model is emitting reasoning, no visible text yet
 *  - [GENERATING] the model is streaming the visible reply
 *  - [IDLE]       service alive with nothing running (presence only)
 */
internal enum class AgentPhase { COMPLETED, TOOL, THINKING, GENERATING, IDLE }

internal fun resolveAgentPhase(
    isCompleted: Boolean,
    toolName: String?,
    isThinking: Boolean,
    hasActiveSessions: Boolean,
): AgentPhase = when {
    isCompleted -> AgentPhase.COMPLETED
    toolName != null -> AgentPhase.TOOL
    !hasActiveSessions -> AgentPhase.IDLE
    isThinking -> AgentPhase.THINKING
    else -> AgentPhase.GENERATING
}

/**
 * Small icon per phase. Notification small icons must be monochrome alpha
 * masks — the system tints them — so these are the app's own `ic_tool_*`
 * vectors (white fill), the same set the overlay capsule draws. The legacy
 * `android.R.drawable.ic_menu_*` bitmaps used before are anti-aliased grey
 * artwork that renders as an indistinct blob at status-bar size, which is
 * what the ColorOS chip in the user's recording showed.
 */
@DrawableRes
internal fun notificationSmallIconFor(phase: AgentPhase, toolName: String?): Int = when (phase) {
    AgentPhase.COMPLETED -> R.drawable.ic_notification_completed
    AgentPhase.TOOL -> toolIconResFor(toolName)
    AgentPhase.THINKING -> R.drawable.ic_tool_psychology
    AgentPhase.GENERATING, AgentPhase.IDLE -> R.drawable.ic_launcher_monochrome
}

/**
 * Title for the [AgentPhase.TOOL] row: the model-supplied `tool_title`
 * ("Open Baidu home page") when there is one, otherwise the per-tool label
 * the overlay uses ("Execute Shell"). Both are far shorter than the old
 * "Minis is using Shell" sentence, which matters on OEM chips that fall
 * back to the content title (see [chipTimerText]).
 */
internal fun toolRowTitle(toolName: String, toolTitle: String?): String =
    toolTitle?.takeIf { it.isNotBlank() } ?: friendlyToolTitleFor(toolName)

/**
 * The tracker's default status line is the raw `"Running: <tool_name>"`.
 * Replace that exact default with the friendly tool label; keep any richer
 * status a tool reported itself (offload handlers write their own).
 */
internal fun humanizeToolStatus(status: String, toolName: String?): String {
    if (toolName == null) return status
    if (status != "Running: $toolName") return status
    return friendlyToolTitleFor(toolName)
}

/**
 * Compact elapsed text for the status-bar chip: `m:ss`, growing to `h:mm:ss`
 * past an hour. Kept ≤ 7 characters — the length `setShortCriticalText` is
 * documented to display without truncation.
 */
internal fun chipTimerText(elapsedMs: Long): String {
    val total = (elapsedMs / 1000).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}
