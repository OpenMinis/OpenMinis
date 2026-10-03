package com.openminis.app.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.R
import com.openminis.app.agent.jobs.AgentCallback
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.DisplayBitmapLimits
import kotlinx.coroutines.delay
import org.json.JSONObject
import com.openminis.app.ui.components.DecorativeSpinner

// [T-p2-agent-series] Dedicated rendering for a `delegate_task` (agent)
// block — the Android port of iOS HelperBlockView.swift / HelperSheet.swift
// at their FINAL state. Surfaces:
//   • HelperToolBlock       — the inline block in the parent transcript
//                              (two fixed rows: no height change across states)
//   • HelperThumbContent    — the floating tool bar's 100×65 preview
//   • HelperDetailContent   — the tool detail sheet's body, stacked cards in
//                              the file/browser tool language
//   • AgentCallbackCard     — the <agent_callback> message (only when the
//                              transcript has no delegate block for it)
//   • AgentTranscriptScreen — full-screen, read-only child conversation
// All read HelperBlockInfo, the single parser for every payload shape.

/** Electric violet, dynamic per appearance (#734DED light / #A385FF dark):
 *  reads as "AI / tech" and stays clear of red (stop/error), green (done),
 *  yellow (timeout) and blue (thinking). */
val HelperAccentLight = Color(0xFF734DED)
val HelperAccentDark = Color(0xFFA385FF)
@Composable
internal fun helperAccent(): Color = if (ChatColors.isDark) HelperAccentDark else HelperAccentLight
/** Static mid-tone for non-composable call sites (tool colour map). */
val HelperAccentStatic = Color(0xFF8B6CF6)

internal val HelperStatusGreen = Color(0xFF34C759)
internal val HelperStatusYellow = Color(0xFFFFCC00)
internal val HelperStatusRed = Color(0xFFFF3B30)

/** What an agent block's content currently says, in structured form. */
internal sealed class HelperPhase {
    object Starting : HelperPhase()
    /** [T-sub-agents-queue] Waiting for a slot; starts by itself when one frees. */
    data class Queued(val behind: Int) : HelperPhase()
    /** [T-sub-agents-queue] Was queued when the app stopped; it can never start now. */
    object NeverStarted : HelperPhase()
    /** [tool] is the child's own tool currently executing. */
    data class Running(val tool: String?, val activity: String, val clock: String) : HelperPhase()
    data class Finished(
        val status: String, val tier: String?, val model: String?, val elapsedSeconds: Int?,
        val summary: String, val escalation: Boolean, val background: Boolean, val childSessionId: String?,
        /**
         * [T-subagent-ui-honesty] The definition pinned a Model Group that
         * could not be routed, so this ran on the parent's model instead.
         *
         * Shown because an ignored pin is otherwise indistinguishable from a
         * working one: the card names a model either way, and the user has no
         * way to tell their setting silently stopped applying.
         */
        val modelGroupUnavailable: Boolean = false,
        /**
         * [T-android-subagent-model-strategy] How this sub agent's model was
         * chosen — the wire value of HelperModelOrigin. The card turns it into
         * a sentence; `tier` (primary/sub) is a stage of our own resolver and
         * says nothing a user can act on.
         */
        val modelOrigin: String? = null,
        /** The Model Group's name, when a group produced the binding. */
        val modelGroupName: String? = null,
        /**
         * [T-android-subagent-error-surface] Why the run failed, in a few
         * words, when the child's error text was recognisable. Null keeps the
         * plain "Failed" label — a wrong attribution is worse than none,
         * because the user acts on it.
         */
        val errorKind: HelperRunner.ErrorKind? = null,
        /** The raw error text, for the detail sheet. */
        val errorDetail: String? = null,
    ) : HelperPhase()
}

/**
 * [T-android-subagent-model-strategy] The model strategy as a sentence.
 *
 * `model_origin` already distinguishes all four cases the resolver can
 * produce; this names them in the user's terms. A group-backed strategy
 * appends the group's own name so it reads as the thing the user configured.
 * Returns null for a payload written before the field existed, so an old
 * transcript simply omits the row rather than showing a blank or a guess.
 */
@Composable
private fun helperModelStrategyLabel(f: HelperPhase.Finished): String? {
    val group = f.modelGroupName
    return when (f.modelOrigin) {
        "inherited" -> stringResource(R.string.agent_strategy_inherited)
        "default_group" -> group?.let { stringResource(R.string.agent_strategy_default_group, it) }
        "sub_group" -> group?.let { stringResource(R.string.agent_strategy_sub_group, it) }
        "pinned" -> group?.let { stringResource(R.string.agent_strategy_pinned, it) }
        else -> null
    }
}

/**
 * [T-android-agent-toolname-display] `shell_execute` -> "Shell execute",
 * `browser-use` -> "Browser use".
 *
 * Port of iOS `HelperBlockInfo.displayName(forTool:)`. DISPLAY ONLY — every
 * lookup (icons, status matching) keeps using the raw wire name; this is the
 * last step before the text reaches a label.
 *
 * Deliberately NOT [toolDisplayName] from ChatToolFormatting: that is a
 * different vocabulary ("browser", "terminal", "file reader") built for the
 * "Minis is using X" sentence, and reusing it here would rename the tool the
 * card is reporting.
 */
internal fun helperToolLabel(name: String): String {
    val spaced = name.replace('_', ' ').replace('-', ' ')
    return spaced.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.US) else it.toString() }
}

internal data class HelperBlockInfo(
    val title: String,
    val phase: HelperPhase,
    val childSessionId: String?,
    val result: String?,
    val runSummary: String?,
    /**
     * [T-android-subagent-badge] The sub agent's own name, when the payload
     * carries one. Null while the run is in progress — see the badge for why
     * that is the correct default rather than a gap to fill.
     */
    val agent: String? = null,
) {
    /**
     * [T-android-neverstarted-not-running] Whether anything is actually in
     * flight for this block.
     *
     * NeverStarted is deliberately NOT running. It means the delegation was
     * sitting in the in-memory queue when the process died, so no child
     * session, no job, and nothing to stop — offering a Stop button there
     * presented a dead card as live work and did nothing when tapped. iOS
     * models the same state as a FINISHED status (`queue_lost`,
     * HelperBlockView.swift:109) for exactly this reason; Android keeps it as
     * its own phase because it drives a Start action, so the exclusion has to
     * be stated here instead.
     */
    val isRunning: Boolean get() = phase !is HelperPhase.Finished && phase !is HelperPhase.NeverStarted
    val finished: HelperPhase.Finished? get() = phase as? HelperPhase.Finished
}

private fun parseJson(content: String): JSONObject? {
    val c = content.trim()
    if (!c.startsWith("{")) return null
    return runCatching { JSONObject(c) }.getOrNull()
}

/**
 * Both payload dialects: the progress JSON the runner writes while the
 * child runs, and the result JSON (wait-mode result, background start /
 * finish, rejection). A persisted `status: running` whose block is no longer
 * RUNNING — an app restart killed the in-memory job — reads as
 * "interrupted", not "running forever".
 */
internal fun parseHelperBlock(block: AssistantBlock): HelperBlockInfo {
    val title = block.toolTitle.ifEmpty { block.toolName }
    val o = parseJson(block.content) ?: return HelperBlockInfo(title, HelperPhase.Starting, null, null, null)
    val childId = o.optString("child_session_id", "").ifEmpty { null }
    val summaryLine = o.optString("summary", "").ifEmpty { null }?.removePrefix("Summary: ")
    // [T-android-unknown-status] Absent `status` falls back to EMPTY, not to
    // the word "unknown". A payload legitimately has no status key — a
    // start/progress snapshot never overwritten by the final JSON, which is
    // what control calls (status/wait) tend to leave behind — and a magic word
    // that looks like a real status is precisely how this reached the screen as
    // a red "unknown". An empty string cannot be mistaken for one, and every
    // downstream use is an equality test against a known token, so it falls
    // through to the neutral branch by construction rather than by luck.
    var status = o.optString("status", "")
    val liveRunning = block.toolStatus == ToolBlockStatus.RUNNING || block.toolStatus == ToolBlockStatus.PENDING || block.toolStatus == ToolBlockStatus.STREAMING
    // [T-sub-agents-queue] A delegation waiting for a slot.
    //
    // The queue is in memory only, and deliberately so: unlike an interrupted
    // RUN, a task that never started has no child session and no persisted
    // arguments, so there is nothing on disk to rebuild it from. After a
    // restart the block is therefore reported as never started rather than as a
    // pause that will never lift — which also lets the parent model read it as
    // work that did not happen and delegate it again.
    if (status == "queued") {
        // [T-android-subagent-control-not-lost] `queued` means two unrelated
        // things on two unrelated queues, and the liveness check below only
        // answers for one of them.
        //
        //   delegate — waiting for a CONCURRENCY SLOT in AgentJobRegistry's
        //     in-memory queue. That queue dies with the process and holds the
        //     only copy of the arguments, so after a restart the delegation
        //     genuinely can never start. "Never started" is the right label.
        //
        //   steer    — waiting for the CHILD to read it on its next turn,
        //     parked in the child vm's pendingSteerMessages. Nothing ever puts
        //     it in queuedDelegations, so isQueuedLive is false from the very
        //     first read — not after a restart, immediately.
        //
        // Without this guard every steer was badged yellow "Never started"
        // while its own result text said "Queued. The sub agent reads it at its
        // next turn" — the badge and the body asserting opposite things about
        // the same call. A control call has no delegation queue to be lost
        // from, so the question simply does not apply to it.
        if (HelperRunner.isControlOnly(block.toolArgs.ifEmpty { null }, block.content.ifEmpty { null })) {
            return HelperBlockInfo(title, HelperPhase.Queued(o.optInt("queued_behind", 0)), childId, null, summaryLine)
        }
        return if (HelperRunner.isQueuedLive(block.id)) {
            HelperBlockInfo(title, HelperPhase.Queued(o.optInt("queued_behind", 0)), childId, null, summaryLine)
        } else {
            HelperBlockInfo(title, HelperPhase.NeverStarted, childId, null, summaryLine)
        }
    }
    if (status == "running") {
        if (liveRunning) {
            val tool = o.optString("tool", "").ifEmpty { null }
            val activity = o.optString("activity", "").ifEmpty { o.optString("tool_status", "") }
            val elapsed = o.optLong("elapsed_s", -1L)
            val clock = if (elapsed >= 0 && o.has("elapsed_s")) HelperRunner.formatClock(elapsed * 1000) else ""
            val last = o.optString("last_message", "").ifEmpty { null }
            return HelperBlockInfo(title, HelperPhase.Running(tool, activity, clock), childId, last, summaryLine)
        }
        status = "interrupted"
    }
    val result = o.optString("result", "").ifEmpty { o.optString("detail", "") }
    val firstLine = result.lineSequence().firstOrNull().orEmpty()
    val summary = if (firstLine.length > 140) firstLine.take(140) + "…" else firstLine
    return HelperBlockInfo(
        title,
        HelperPhase.Finished(
            status = status,
            tier = o.optString("tier_used", "").ifEmpty { null },
            model = o.optString("model_used", "").ifEmpty { null },
            modelOrigin = o.optString("model_origin", "").ifEmpty { null },
            modelGroupName = o.optString("model_group_name", "").ifEmpty { null },
            elapsedSeconds = if (o.has("elapsed_s")) o.optInt("elapsed_s") else null,
            summary = summary,
            escalation = o.optBoolean("escalation_requested", false),
            background = o.has("delivered_as"),
            childSessionId = childId,
            modelGroupUnavailable = o.optBoolean("model_group_unavailable", false),
            errorKind = HelperRunner.ErrorKind.fromWire(o.optString("error_kind", "").ifEmpty { null }),
            errorDetail = o.optString("error_detail", "").ifEmpty { null },
        ),
        childId, result.ifEmpty { null }, summaryLine,
        agent = o.optString("agent", "").trim().ifEmpty { null },
    )
}

/**
 * [T-android-subagent-control-hidden] The one quiet line a control call gets
 * instead of a card.
 *
 * status / steer / cancel / resume are the model managing sub agents it already
 * started; the effect lands on the card of the run they acted on, so a full
 * card here would be a second row for work already shown. The line still exists
 * because the turn did something and silence would read as a gap.
 */
@Composable
internal fun HelperControlRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Default.Groups,
            contentDescription = null,
            tint = ChatColors.tertiaryText,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text,
            fontSize = 11.sp,
            color = ChatColors.tertiaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * [T-android-subagent-control-capsule] What the parent SENT with a control
 * call — the steer text, the ids it resumed — for the detail sheet.
 *
 * The result JSON records what came BACK; only the arguments record what went
 * OUT, and for a steer that text is the entire content of the interaction.
 * Without it the sheet could say a correction was sent and never say what it
 * said, which is the one thing a reader opens it for.
 */
internal fun helperControlPayload(block: AssistantBlock): String? {
    if (!HelperRunner.isControlOnly(block.toolArgs.ifEmpty { null }, block.content.ifEmpty { null })) return null
    val args = runCatching { JSONObject(block.toolArgs) }.getOrNull() ?: return null
    args.optString("message").trim().takeIf { it.isNotEmpty() }?.let { return it }
    args.optJSONArray("child_session_ids")?.let { arr ->
        val ids = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotEmpty) }
        if (ids.isNotEmpty()) return ids.joinToString("\n") { it.take(8) }
    }
    args.optString("child_session_id").takeIf { it.isNotEmpty() }?.let { return it.take(8) }
    args.optString("job_id").takeIf { it.isNotEmpty() }?.let { return it.take(8) }
    return null
}

/**
 * [T-android-subagent-control-hidden] The summary line for a control call, or
 * null when this block is a real delegation and should keep its card.
 */
@Composable
internal fun helperControlSummary(block: AssistantBlock): String? {
    val call = remember(block.toolArgs, block.content) {
        HelperRunner.classifyCall(block.toolArgs.ifEmpty { null }, block.content.ifEmpty { null })
    }
    if (call == HelperRunner.SubAgentCall.DELEGATE) return null
    val count = remember(call, block.content) {
        HelperRunner.controlCount(call, block.content.ifEmpty { null })
    }
    return when (call) {
        // The count is what makes the line worth reading — "checked 3" says
        // something "checked sub agents" does not.
        HelperRunner.SubAgentCall.STATUS ->
            if (count != null) stringResource(R.string.agent_control_checked_n, count)
            else stringResource(R.string.agent_control_checked)
        HelperRunner.SubAgentCall.RESUME ->
            if (count != null) stringResource(R.string.agent_control_resumed_n, count)
            else stringResource(R.string.agent_control_resumed)
        HelperRunner.SubAgentCall.STEER -> stringResource(R.string.agent_control_steered)
        HelperRunner.SubAgentCall.CANCEL -> stringResource(R.string.agent_control_stopped)
        else -> stringResource(R.string.agent_control_generic)
    }
}

/**
 * [T-android-subagent-error-surface] The status word, replaced by the specific
 * cause when the run failed and the cause was recognisable.
 *
 * "Failed" alone gives the user nothing to act on — a rate limit, a dead
 * network and a context overflow need three different responses, and the card
 * has room for a label, not a stack trace. An unclassifiable error keeps the
 * plain word rather than inventing a category.
 */
@Composable
internal fun helperStatusLabel(f: HelperPhase.Finished): String =
    f.errorKind?.let { stringResource(it.res) } ?: helperStatusLabel(f.status)

@Composable
internal fun helperStatusLabel(status: String): String = when (status) {
    "completed" -> stringResource(R.string.helper_status_completed)
    "cancelled" -> stringResource(R.string.helper_status_cancelled)
    "timeout" -> stringResource(R.string.helper_status_timeout)
    "failed" -> stringResource(R.string.helper_status_failed)
    "rejected" -> stringResource(R.string.helper_status_rejected)
    "interrupted" -> stringResource(R.string.helper_status_interrupted)
    // [T-android-no-deliverable] "No result", not "Failed": the run was fine,
    // it just handed nothing over.
    HelperRunner.NO_DELIVERABLE -> stringResource(R.string.helper_status_no_deliverable)
    // [T-android-unknown-status] Never render the raw token. `status` is an
    // internal wire word, and when the payload carries no `status` key at all
    // it is the literal "unknown" — which reached the screen verbatim and read
    // as a status the run had actually reported. Mirrors iOS 23496c908.
    else -> stringResource(R.string.helper_status_unknown)
}

// @Composable because the neutral branch reads the theme's secondary text
// colour, which is theme- and dark-mode-dependent. Every call site already sits
// beside helperStatusLabel (also @Composable), so nothing moves out of
// composition.
@Composable
internal fun helperStatusColor(status: String): Color = when (status) {
    "completed" -> HelperStatusGreen
    "cancelled", "timeout", "interrupted", HelperRunner.NO_DELIVERABLE -> HelperStatusYellow
    // [T-android-unknown-status] The real failures stay RED and must be listed
    // explicitly. They used to ride the `else` together with unrecognised and
    // missing states, so neutralising `else` without naming them here would
    // quietly grey out genuine failures — trading one lie for another.
    "failed", "rejected" -> HelperStatusRed
    // Everything else is "the app does not know how this ended", not "this
    // failed". Red asserts a failed run; an unrecognised or absent status says
    // only that the outcome was never recorded, and the transcript is very
    // likely intact. Secondary grey reads as "no information", which is what
    // this actually is. Mirrors iOS 23496c908.
    else -> ChatColors.secondaryText
}

/**
 * [T-android-unknown-status] The glyph for a finished run.
 *
 * Shared by both card sites so the icon can never disagree with the colour.
 * iOS keeps an exclamation mark in its default branch, but on Android that
 * branch is tinted by [helperStatusColor] — leaving it would produce a grey
 * exclamation mark, an icon shouting "problem" in a colour saying "no
 * information". A question mark states the same thing as the label and the
 * colour: the outcome is unknown, not bad.
 */
internal fun helperStatusGlyph(status: String): ImageVector = when (status) {
    "completed" -> Icons.Default.Check
    "failed", "rejected", "cancelled", "timeout", "interrupted",
    HelperRunner.NO_DELIVERABLE -> Icons.Default.PriorityHigh
    else -> Icons.AutoMirrored.Filled.HelpOutline
}

internal fun helperElapsedLabel(s: Int): String = if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"

/** Icon for a child's tool name — the same vocabulary the parent's blocks use. */
internal fun helperToolIcon(name: String?): ImageVector = when (name) {
    null, "", "subagent_task", "delegate_task", "agent_status" -> Icons.Default.Groups
    else -> toolIconFor(name)
}

@Composable
private fun HelperSpinner(size: Int, stroke: Float = 2f) {
    // [T-android-decorative-anim-perf] See DecorativeAnimation.kt.
    DecorativeSpinner(color = helperAccent(), modifier = Modifier.size(size.dp), strokeWidth = stroke.dp)
}

// ─── Inline block ────────────────────────────────────────────────────────

/**
 * The block in the parent transcript. Two rows at a FIXED height whatever
 * the phase: title row (Agent chip + task title) and one status row —
 * starting / tool·activity·clock / status·tier·elapsed. The finished state
 * deliberately shows no result summary; the deliverable lives in the detail
 * sheet, so the block never grows and jolts the list when a run ends.
 */
@Composable
internal fun HelperToolBlock(
    block: AssistantBlock,
    onOpenDetail: (String) -> Unit,
    onStop: (() -> Unit)? = null,
    /**
     * [T-android-neverstarted-not-running] Run a delegation that never got off
     * the queue. Only offered for [HelperPhase.NeverStarted], which is the one
     * state where the work is fully described (the task text is in the block's
     * own arguments) but definitively did not happen.
     */
    onStart: (() -> Unit)? = null,
    /**
     * [T-android-subagent-card-resume] Restart an INTERRUPTED run (the app
     * stopped while it was working). Returns whether it was accepted, started
     * or queued: the button stays dimmed and disabled after an accepted tap,
     * and comes back after a refusal so the user can try again. Port of iOS
     * HelperBlockView's resume button.
     */
    onResume: (() -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    val info = remember(block.content, block.toolStatus, block.toolTitle) { parseHelperBlock(block) }
    // [T-android-subagent-card-resume] iOS `resuming`. Once a resume starts, the
    // block flips to Running and the button leaves on its own; a queued one
    // stays interrupted until a slot frees, so the tap must not be repeatable.
    var resuming by remember(block.id) { mutableStateOf(false) }
    val accent = helperAccent()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(accent.copy(alpha = 0.07f))
            .border(0.8.dp, accent.copy(alpha = 0.35f), shape)
            .clickable(onClickLabel = stringResource(R.string.agent_open_detail)) { onOpenDetail(block.id) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(34.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            val f = info.finished
            when {
                // [T-android-subagent-card-metrics] 13, not 15. iOS draws these
                // at 13pt, and an SF Symbol at 13 carries less visual weight
                // than a Material icon at 15 — the two differences compounded,
                // so the 34dp disc read as packed next to iOS's. The disc
                // itself stays 34; only the glyph inside it changes.
                info.isRunning && info.phase is HelperPhase.Running -> HelperSpinner(13)
                f != null -> Icon(
                    helperStatusGlyph(f.status),
                    contentDescription = null, tint = helperStatusColor(f.status), modifier = Modifier.size(13.dp),
                )
                // [T-android-queued-visual] A QUEUED agent is paused, and must
                // not wear the same generic Groups glyph as one that is
                // starting. With a fan-out of five, the two that were waiting
                // for a slot looked identical to the three that were about to
                // run — the only difference was the word in the second line.
                // iOS draws `pause.fill` here (HelperBlockView.swift:589).
                info.phase is HelperPhase.Queued -> Icon(
                    Icons.Default.Pause, contentDescription = null,
                    tint = accent, modifier = Modifier.size(13.dp),
                )
                else -> Icon(Icons.Default.Groups, contentDescription = null, tint = accent, modifier = Modifier.size(13.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // [T-android-subagent-badge] Name the sub agent once the payload
                // carries one; the generic label until then. Several agents in
                // one fan-out all reading "Agent" left the user unable to tell
                // which card was which.
                AgentChip(info.agent ?: stringResource(R.string.helper_label))
                Text(
                    info.title, fontSize = 13.sp, lineHeight = 14.sp,
                    fontWeight = FontWeight.Medium, color = ChatColors.primaryText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            HelperSecondLine(info)
        }
        Spacer(Modifier.width(4.dp))
        if (info.phase is HelperPhase.NeverStarted && onStart != null) {
            // A play triangle, not a stop square: this card's work never ran,
            // and the only useful action is to run it.
            val startLabel = stringResource(R.string.helper_start)
            Box(
                modifier = Modifier.size(24.dp).clip(CircleShape).background(helperAccent())
                    .clickable(onClickLabel = startLabel) { onStart() }
                    .semantics { contentDescription = startLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        } else if (info.finished?.status == "interrupted" && onResume != null) {
            // [T-android-subagent-card-resume] Same slot as Stop, and its
            // counterpart: an accent disc with a restart arrow (iOS
            // arrow.clockwise). Once the run is going again the card turns
            // Running and Stop takes this slot back by itself.
            val resumeLabel = stringResource(R.string.helper_resume)
            Box(
                modifier = Modifier.size(24.dp).clip(CircleShape)
                    .background(helperAccent().copy(alpha = if (resuming) 0.25f else 1f))
                    .clickable(enabled = !resuming, onClickLabel = resumeLabel) { resuming = onResume() }
                    .semantics { contentDescription = resumeLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        } else if (info.isRunning && onStop != null) {
            // Classic stop-recording look: red disc, white rounded square.
            val stopLabel = stringResource(R.string.helper_stop)
            Box(
                modifier = Modifier.size(24.dp).clip(CircleShape).background(HelperStatusRed)
                    .clickable(onClickLabel = stopLabel) { onStop() }
                    .semantics { contentDescription = stopLabel },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(Color.White)) }
            Spacer(Modifier.width(8.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = ChatColors.tertiaryText, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun AgentChip(text: String) {
    val accent = helperAccent()
    Text(
        text, fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.SemiBold, color = accent,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(accent.copy(alpha = 0.15f)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun TierChip(tier: String, tinted: Boolean = false) {
    val accent = helperAccent()
    Text(
        tier, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
        color = if (tinted) accent else ChatColors.secondaryText,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (tinted) accent.copy(alpha = 0.15f) else ChatColors.secondaryBg).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * [T-subagent-thinking-badge] The reasoning level a sub agent run used, as a
 * pill beside its model — the same relationship the main conversation's top
 * bar has between its model and its thinking level.
 *
 * Not interactive: the level was decided when the run started, and changing it
 * now would not affect the run.
 */
@Composable
private fun ThinkingLevelPill(level: ThinkingLevel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(ChatColors.secondaryBg)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Icon(
            Icons.Default.Psychology, contentDescription = null,
            tint = ChatColors.secondaryText, modifier = Modifier.size(9.dp),
        )
        Text(
            level.displayName, fontSize = 9.sp, lineHeight = 10.sp,
            fontWeight = FontWeight.Medium, color = ChatColors.secondaryText,
        )
    }
}

@Composable
private fun HelperSecondLine(info: HelperBlockInfo) {
    val accent = helperAccent()
    when (val p = info.phase) {
        is HelperPhase.Starting -> Text(stringResource(R.string.agent_status_starting), fontSize = 11.sp, lineHeight = 12.sp, color = ChatColors.secondaryText, maxLines = 1)
        // [T-android-queued-visual] Waiting for a slot is not a failure — but it
        // is not nothing either. Muted grey put it in the same register as
        // "starting", so a queued agent read as one that was already under way.
        // The accent says "this is agent work, currently held" without the
        // alarm of a warning colour. iOS uses HelperAccent.color for exactly
        // this status (HelperBlockView.swift:313).
        is HelperPhase.Queued -> Text(
            stringResource(R.string.agent_status_queued), fontSize = 11.sp, lineHeight = 12.sp,
            color = accent, maxLines = 1,
        )
        is HelperPhase.NeverStarted -> Text(
            stringResource(R.string.agent_status_never_started), fontSize = 11.sp, lineHeight = 12.sp,
            color = ChatColors.tertiaryText, maxLines = 1,
        )
        is HelperPhase.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (p.tool != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(helperToolIcon(p.tool), contentDescription = null, tint = accent, modifier = Modifier.size(11.dp))
                    Text(helperToolLabel(p.tool), fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = accent, maxLines = 1)
                }
            }
            Text(
                p.activity.ifEmpty { stringResource(R.string.agent_working) }, fontSize = 11.sp, lineHeight = 12.sp, color = ChatColors.secondaryText,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (p.clock.isNotEmpty()) Text(p.clock, fontSize = 11.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace, color = ChatColors.tertiaryText, maxLines = 1)
        }
        is HelperPhase.Finished -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(helperStatusLabel(p), fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = helperStatusColor(p.status), maxLines = 1)
            // [T-android-card-shows-tier] The model that actually ran, not
            // `tier`. See the note on HelperPhase.Finished.tier: primary/sub is
            // a stage of our own resolver, which A3 already removed from the
            // detail sheet for being meaningless to a reader — the two cards
            // kept showing it.
            if (p.modelGroupUnavailable) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = stringResource(R.string.agent_pinned_group_unavailable),
                    tint = Color(0xFFFFCC00),
                    modifier = Modifier.size(11.dp),
                )
            }
            p.model?.let {
                Text(
                    it, fontSize = 11.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace,
                    color = ChatColors.tertiaryText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            p.elapsedSeconds?.let { Text(helperElapsedLabel(it), fontSize = 11.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace, color = ChatColors.tertiaryText, maxLines = 1) }
            if (p.escalation) Icon(Icons.Default.PriorityHigh, contentDescription = null, tint = Color(0xFFFF9500), modifier = Modifier.size(11.dp))
        }
    }
}

// ─── Floating tool-bar thumbnail (100×65) ────────────────────────────────

/** Never the raw JSON: Agent label, title, and the phase in words. */
@Composable
internal fun HelperThumbContent(block: AssistantBlock) {
    val info = remember(block.content, block.toolStatus, block.toolTitle) { parseHelperBlock(block) }
    val accent = helperAccent()
    // [T-android-agent-thumb-accent-stub] No right-edge accent bar.
    //
    // This used to draw a 3dp full-height accent strip pinned to TopEnd, inside
    // an overlay Box. The 100x65 thumbnail is clipped to RoundedCornerShape(8),
    // so the strip's own top and bottom were sliced into a taper: it read as a
    // stray purple stub hanging off the right edge, not as an accent. iOS's
    // HelperThumbnailView has no such element (it ends at `.padding(5)` over a
    // flat background), and no other tool preview in the floating bar has one
    // either — so it was also the odd one out in a row of thumbnails.
    //
    // Agent identity does not need it: the accent-tinted Groups icon and
    // "Agent" label on the first line already carry it, which is exactly how
    // iOS marks these. Padding goes back to a uniform 5dp to match iOS; the old
    // asymmetric `end = 8.dp` existed only to hold text clear of the strip, and
    // keeping it would just cost 3dp of width on a 100dp preview whose title
    // and model line are already ellipsizing. The overlay Box went with it.
    Column(modifier = Modifier.fillMaxSize().padding(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(Icons.Default.Groups, contentDescription = null, tint = accent, modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.helper_label), fontSize = 6.sp, lineHeight = 7.sp, fontWeight = FontWeight.Bold, color = accent, maxLines = 1)
        }
        Text(info.title, fontSize = 7.sp, lineHeight = 8.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        when (val p = info.phase) {
            is HelperPhase.Starting -> Text(stringResource(R.string.agent_status_starting), fontSize = 6.sp, lineHeight = 7.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
            // [T-android-queued-visual] Accent here too, so the toolbar preview
            // agrees with the card it opens. The thumbnail sits on a dark
            // ground, so this is the accent rather than the card's — same
            // meaning, legible against a different background.
            is HelperPhase.Queued -> Text(stringResource(R.string.agent_status_queued), fontSize = 6.sp, lineHeight = 7.sp, color = accent, maxLines = 1)
            is HelperPhase.NeverStarted -> Text(stringResource(R.string.agent_status_never_started), fontSize = 6.sp, lineHeight = 7.sp, color = Color.White.copy(alpha = 0.55f), maxLines = 1)
            is HelperPhase.Running -> {
                if (p.tool != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(helperToolIcon(p.tool), contentDescription = null, tint = accent, modifier = Modifier.size(7.dp))
                    Text(helperToolLabel(p.tool), fontSize = 6.sp, lineHeight = 7.sp, fontWeight = FontWeight.SemiBold, color = accent, maxLines = 1)
                }
                Text(
                    listOf(p.activity, p.clock).filter { it.isNotEmpty() }.joinToString(" · "),
                    fontSize = 6.sp, lineHeight = 7.sp, color = Color.White.copy(alpha = 0.7f),
                    maxLines = if (p.tool == null) 2 else 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // [T-android-card-shows-tier] Same substitution as the inline
            // card, plus the pinned-group warning iOS shows here: at 6sp
            // there is no room for the sentence, so the triangle carries it
            // and the accessibility label spells it out.
            is HelperPhase.Finished -> {
                val warn = stringResource(R.string.agent_pinned_group_unavailable)
                val line = listOf(
                    helperStatusLabel(p),
                    p.model.orEmpty(),
                    p.elapsedSeconds?.let(::helperElapsedLabel).orEmpty(),
                ).filter { it.isNotEmpty() }.joinToString(" · ")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.semantics {
                        contentDescription = if (p.modelGroupUnavailable) "$line — $warn" else line
                    },
                ) {
                    if (p.modelGroupUnavailable) {
                        Icon(
                            Icons.Default.Warning, contentDescription = null,
                            tint = Color(0xFFFFCC00).copy(alpha = 0.9f),
                            modifier = Modifier.size(6.dp),
                        )
                    }
                    Text(
                        line, fontSize = 6.sp, lineHeight = 7.sp, fontWeight = FontWeight.SemiBold,
                        color = helperStatusColor(p.status), maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
        }
    }
}

// ─── Tool detail sheet body ──────────────────────────────────────────────

/** Card container in the file/browser tool language: title bar with its own
 *  background, divider, body. */
@Composable
internal fun AgentDetailCard(
    title: String,
    icon: ImageVector,
    iconTint: Color = ChatColors.secondaryText,
    trailing: (@Composable () -> Unit)? = null,
    body: (@Composable () -> Unit)? = null,
) {
    val isDark = ChatColors.isDark
    val barBg = if (isDark) Color(0xFF212121) else Color(0xFFEBEBEB)
    val cardBg = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF0F0F0)
    val stroke = if (isDark) Color(0xFF404040) else Color(0xFFD1D1D1)
    val shape = RoundedCornerShape(10.dp)
    Column(modifier = Modifier.fillMaxWidth().clip(shape).background(cardBg).border(0.5.dp, stroke, shape)) {
        Row(modifier = Modifier.fillMaxWidth().background(barBg).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ChatColors.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        if (body != null) {
            HorizontalDivider(color = stroke)
            Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) { body() }
        }
    }
}

/**
 * [T-android-subagent-control-capsule] The detail sheet's body for a CONTROL
 * call (status / steer / cancel / resume).
 *
 * Two cards, because a control call has exactly two halves worth reading and
 * the transcript row can only show one of them:
 *
 *  - "Sent to the sub agent" — the arguments. For a steer this text IS the
 *    interaction; without it the sheet could report that a correction was sent
 *    and never say what it said. Omitted when the call carried no payload
 *    (a bare `status` with no job_id asks about everything).
 *  - "Result" — the JSON that came back, which is what says whether the
 *    operation landed.
 *
 * Deliberately NOT the delegation body: a control call has no child session,
 * no model, no elapsed time and no deliverable, so that layout would render a
 * card of empty rows.
 */
@Composable
private fun HelperControlDetailContent(block: AssistantBlock, summary: String) {
    val accent = helperAccent()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp).padding(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AgentDetailCard(title = summary, icon = Icons.Default.Tune, iconTint = accent)
        helperControlPayload(block)?.let { payload ->
            AgentDetailCard(
                title = stringResource(R.string.agent_detail_sent_to_agent),
                icon = Icons.AutoMirrored.Filled.Send,
                iconTint = accent,
            ) {
                Text(payload, fontSize = 13.sp, color = ChatColors.primaryText)
            }
        }
        block.content.takeIf { it.isNotBlank() }?.let { result ->
            AgentDetailCard(
                title = stringResource(R.string.agent_detail_result),
                icon = Icons.Default.CheckCircle,
            ) {
                Text(
                    result, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = ChatColors.secondaryText,
                )
            }
        }
    }
}

/**
 * The detail sheet's body for an agent block — the SAME frame every other
 * tool gets, and inside it stacked cards: header (title, status · tier ·
 * elapsed; model / tools / turns / tokens rows), current tool (running),
 * latest screenshot (child's most recent browser/read_image capture),
 * result (Markdown, or the "no deliverable" placeholder). The live
 * conversation is reached from the sheet's top-right chat-bubble button.
 */
@Composable
internal fun HelperDetailContent(block: AssistantBlock) {
    // [T-android-subagent-control-capsule] A control call is not a delegation:
    // it has no child session, no model, no elapsed time and no deliverable, so
    // the delegation body below would render a card of empty rows. Show what
    // the interaction actually consisted of instead — what went out, and what
    // came back.
    val controlSummary = helperControlSummary(block)
    if (controlSummary != null) {
        HelperControlDetailContent(block, controlSummary)
        return
    }
    val context = LocalContext.current
    val info = remember(block.content, block.toolStatus, block.toolTitle) { parseHelperBlock(block) }
    val accent = helperAccent()
    var latestImagePath by remember(info.childSessionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(info.childSessionId, info.isRunning) {
        val cid = info.childSessionId ?: return@LaunchedEffect
        while (true) {
            // [T-android-vm-store-child-tag-survives-evict] A delegate's child is a
            // sub agent: re-acquire it in the CHILD pool so an evicted child does
            // not come back as NORMAL and push a user chat out of the cache.
            val vm = runCatching {
                HeadlessChatRunner.viewModelFor(context, cid, ChatViewModelStore.PoolKind.CHILD)
            }.getOrNull()
            latestImagePath = vm?.messages?.value?.asReversed()?.asSequence()
                ?.filter { it.role == "assistant" }
                ?.flatMap { it.toolBlocks.asReversed().asSequence() }
                ?.mapNotNull { it.imageFilePath?.takeIf { p -> p.isNotEmpty() && java.io.File(p).exists() } }
                ?.firstOrNull()
            if (!info.isRunning) break
            delay(5_000L)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. header card
        val f = info.finished
        // [T-subagent-thinking-badge] The level this run actually reasoned at,
        // shown as a pill beside the model rather than as a row of its own —
        // it qualifies the model, the way the main conversation's top bar
        // shows it beside the model name.
        val ranAtThinking = remember(block.content) {
            runCatching { JSONObject(block.content) }.getOrNull()
                ?.optString("thinking_level")?.takeIf { it.isNotEmpty() }
                // iOS writes the raw value ("high"); accept either spelling.
                ?.let { ThinkingLevel.parseOrNull(it) }
                ?.takeIf { it.isEnabled }
        }
        val rows = buildList {
            if (f != null) {
                // [T-android-subagent-model-strategy] Two facts the user can act
                // on — what this sub agent was told to use, and what it ended
                // up running on — instead of `tier_used`, which is a stage of
                // our own resolver (primary/sub) and answers neither.
                helperModelStrategyLabel(f)?.let {
                    add(Triple(stringResource(R.string.agent_detail_model_strategy), it, false))
                }
                f.model?.let { add(Triple(stringResource(R.string.agent_detail_model_actual), it, false)) }
                // [T-android-agent-model-identity] Which GROUP the strategy
                // chose, and which resolver tier ran. iOS shows both; Android
                // showed neither, so "Default 分组" and "primary/sub" — the two
                // things that explain WHY this model and not another — were
                // invisible. Only rendered when the payload carries them: an
                // inherited binding names no group, and an old block has
                // neither key.
                f.modelGroupName?.let {
                    add(Triple(stringResource(R.string.agent_detail_model_group), it, false))
                }
                f.tier?.let {
                    add(Triple(stringResource(R.string.agent_detail_model_tier), it, false))
                }
                if (f.escalation) add(Triple(stringResource(R.string.agent_detail_escalation), stringResource(R.string.agent_detail_escalation_value), false))
                if (f.background) add(Triple(stringResource(R.string.agent_detail_delivery), stringResource(R.string.agent_detail_delivery_value), false))
            }
            // [T-android-agent-detail-rows] Two facts iOS shows and Android did
            // not. Read straight off the block's payload rather than widened
            // into HelperBlockInfo: they are detail-sheet-only, and the parse
            // result is shared with the card, which has no room for them.
            val payload = remember(block.content) {
                runCatching { JSONObject(block.content) }.getOrNull()
            }
            payload?.optString("callback_kind")?.takeIf { it.isNotEmpty() }?.let { kind ->
                val label = when (kind) {
                    "progress" -> stringResource(R.string.agent_detail_interaction_progress)
                    "finished" -> stringResource(R.string.agent_detail_interaction_final)
                    else -> kind
                }
                add(Triple(stringResource(R.string.agent_detail_interaction), label, false))
            }
            payload?.optString("job_id")?.takeIf { it.isNotEmpty() }?.let { job ->
                // Monospace and truncated: the id is for correlating with a log
                // line, so its shape matters more than its tail.
                add(Triple(stringResource(R.string.agent_detail_job), job.take(8), true))
            }
            info.runSummary?.split(" · ")?.forEach { part ->
                val t = part.trim()
                when {
                    t.startsWith("tools ") -> add(Triple(stringResource(R.string.agent_detail_tools), t.removePrefix("tools "), true))
                    t.startsWith("turns ") -> add(Triple(stringResource(R.string.agent_detail_turns), t.removePrefix("turns "), true))
                    t.startsWith("tokens ") -> add(Triple(stringResource(R.string.agent_detail_tokens), t.removePrefix("tokens "), true))
                    t.isNotEmpty() -> add(Triple(stringResource(R.string.agent_detail_summary), t, false))
                }
            }
        }
        AgentDetailCard(
            title = info.title, icon = Icons.Default.Groups, iconTint = accent,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (val p = info.phase) {
                        is HelperPhase.Starting -> Text(stringResource(R.string.agent_status_starting), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = accent)
                        // [T-android-queued-visual] Same accent as the card.
                        is HelperPhase.Queued -> Text(stringResource(R.string.agent_status_queued), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = accent)
                        is HelperPhase.NeverStarted -> Text(stringResource(R.string.agent_status_never_started), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ChatColors.tertiaryText)
                        is HelperPhase.Running -> {
                            HelperSpinner(11, 1.6f)
                            Text(stringResource(R.string.helper_status_running), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = accent)
                            if (p.clock.isNotEmpty()) Text(p.clock, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = ChatColors.tertiaryText)
                        }
                        is HelperPhase.Finished -> {
                            Text(helperStatusLabel(p), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = helperStatusColor(p.status))
                            p.tier?.let { TierChip(it, tinted = true) }
                            // [T-subagent-ui-honesty] Say when the pin did not
                            // apply; the model name alone cannot show it.
                            if (p.modelGroupUnavailable) {
                                Text(
                                    stringResource(R.string.agent_model_group_unavailable),
                                    fontSize = 10.sp,
                                    color = HelperStatusYellow,
                                    maxLines = 2,
                                )
                            }
                            p.elapsedSeconds?.let { Text(helperElapsedLabel(it), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = ChatColors.tertiaryText) }
                        }
                    }
                }
            },
            body = if (rows.isEmpty()) null else ({
                // Matched on the row's LABEL, not its value: the label is what
                // identifies the model row, while two rows could coincidentally
                // hold the same text.
                val modelRowLabel = context.getString(R.string.agent_detail_model_actual)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, value, mono) in rows) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(label, fontSize = 12.sp, color = ChatColors.secondaryText, maxLines = 1, modifier = Modifier.width(84.dp))
                            Text(value, fontSize = 12.sp, color = ChatColors.primaryText, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
                            if (ranAtThinking != null && label == modelRowLabel) {
                                ThinkingLevelPill(ranAtThinking)
                            }
                        }
                    }
                }
            }),
        )
        // 2. current tool (running only)
        (info.phase as? HelperPhase.Running)?.let { p ->
            if (p.tool != null || p.activity.isNotEmpty()) {
                AgentDetailCard(title = stringResource(R.string.agent_detail_current_tool), icon = helperToolIcon(p.tool)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (p.tool != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(helperToolIcon(p.tool), contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
                            Text(helperToolLabel(p.tool), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = accent)
                        }
                        if (p.activity.isNotEmpty()) Text(p.activity, fontSize = 13.sp, color = ChatColors.primaryText)
                    }
                }
            }
        }
        // 3. latest screenshot
        latestImagePath?.let { path ->
            val bmp = remember(path) { DisplayBitmapLimits.decodeFileBounded(path) }
            if (bmp != null) {
                AgentDetailCard(title = stringResource(R.string.agent_detail_latest_screenshot), icon = toolIconFor("read_image")) {
                    Image(
                        bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(0.5.dp, ChatColors.separator.copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
                    )
                }
            }
        }
        // [T-android-subagent-error-surface] The raw error, above the result:
        // when a run failed, "why" is the question the sheet is opened to
        // answer, and the card above can only carry the few-word label.
        (f?.errorDetail)?.let { detail ->
            AgentDetailCard(
                title = stringResource(R.string.agent_detail_error),
                icon = Icons.Default.Error,
                iconTint = HelperStatusRed,
            ) {
                Text(
                    detail, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = ChatColors.primaryText,
                )
            }
        }
        // 4. result / last message — always shown once finished
        val isFinished = f != null
        if (isFinished || !info.result.isNullOrEmpty()) {
            AgentDetailCard(
                title = stringResource(if (isFinished) R.string.agent_detail_result else R.string.agent_detail_last_message),
                icon = if (isFinished) Icons.Default.CheckCircle else Icons.Default.Groups,
            ) {
                val r = info.result
                if (!r.isNullOrEmpty()) StreamingMarkdownText(content = r, isStreaming = false)
                else Text(stringResource(R.string.agent_detail_no_deliverable), fontSize = 13.sp, color = ChatColors.secondaryText)
            }
        }
    }
}

// ─── Callback card ───────────────────────────────────────────────────────

/** A delegate_task-shaped tool block carrying this callback's payload, in
 *  the JSON dialect [parseHelperBlock] already reads — so tapping a callback
 *  card opens the very same detail as the agent block. */
internal fun AgentCallback.syntheticBlock(): AssistantBlock {
    val finished = kind == AgentCallback.Kind.FINISHED
    // [T-android-no-deliverable] The callback envelope carries the run's own
    // body; an empty one means the agent handed nothing over.
    val status = if (!finished) "running"
    else HelperRunner.resolvedStatus(if (this.status == "done") "completed" else this.status, body)
    // [T-android-agent-detail-rows] Record which kind of callback produced this
    // block so the detail sheet can say whether the user is looking at a
    // mid-run progress report or the final result — two things that otherwise
    // render identically.
    val o = JSONObject().put("status", status).put("job_id", jobId)
        .put("callback_kind", kind.wire)
    childSessionId?.let { o.put("child_session_id", it) }
    tier?.let { o.put("tier_used", it) }
    elapsedSeconds?.let { o.put("elapsed_s", it) }
    tool?.let { o.put("tool", it) }
    activity?.let { o.put("activity", it) }
    summary?.let { o.put("summary", "Summary: $it") }
    o.put(if (finished) "result" else "last_message", body)
    if (finished) o.put("delivered_as", "new turn in this conversation")
    return AssistantBlock(
        id = "callback_$jobId", kind = "tool_use", content = o.toString(), toolName = HelperRunner.TOOL_NAME, toolTitle = title,
        // [T-android-orphaned-running-tool-spin] `kind` is a historical fact —
        // a PROGRESS callback stays PROGRESS forever once persisted — so
        // deriving RUNNING from it alone makes every past progress report spin
        // for the rest of the session. The detail sheet animates on `isLive`
        // (ChatToolDetailUI), and an infinite animation invalidates every
        // frame while composed: measured at 67-91 fps and 83-130% of a CPU
        // core on an idle Pixel 6 until the screen was closed. RUNNING is a
        // claim about right now, so it has to be backed by a live job.
        toolStatus = if (!finished) {
            OrphanedRunningToolGate.resolve(
                ToolBlockStatus.RUNNING,
                isLiveRun = false,
                jobIsAlive = com.openminis.app.agent.jobs.AgentJobRegistry.job(jobId)?.isActive == true,
            )
        } else when (this.status) {
            "done" -> ToolBlockStatus.SUCCESS
            "cancelled" -> ToolBlockStatus.CANCELLED
            "timeout" -> ToolBlockStatus.TIMEOUT
            else -> ToolBlockStatus.FAILED
        },
    )
}

/** Compact, left-aligned card for an <agent_callback> message that has no
 *  delegate block in the transcript (a scheduled child-of-current run). */
@Composable
internal fun AgentCallbackCard(callback: AgentCallback, onTap: () -> Unit, modifier: Modifier = Modifier) {
    // [T-android-subagent-callback-card-metrics] Sized and composed like iOS
    // AgentCallbackCellView (cardHeight 56, rowHeight 64): a 30pt status
    // circle, ONE headline line "<kind> · <title>" at 14 semibold, ONE subline
    // "<status> · <elapsed> · …" at 12, spacing 2 between them, 12 horizontal
    // padding, 14 corner, accent 0.25 hairline. The previous card was 64 + 8
    // tall with a 34 circle, a chip + title row and 5dp between rows, which
    // read as a taller component with loose line spacing next to the pill
    // above it.
    val helperAccent = helperAccent()
    val finished = callback.kind == AgentCallback.Kind.FINISHED
    val accent = when (callback.status) {
        "running" -> helperAccent
        "done" -> HelperStatusGreen
        "cancelled", "timeout" -> Color(0xFFFFCC00)
        else -> HelperStatusRed
    }
    val icon = when {
        !finished -> Icons.Default.Sync
        callback.status == "done" -> Icons.Default.CheckCircle
        callback.status == "cancelled" -> Icons.Default.Cancel
        callback.status == "timeout" -> Icons.Default.Schedule
        else -> Icons.Default.Warning
    }
    val kindLabel = stringResource(if (finished) R.string.agent_callback_result else R.string.agent_callback_progress)
    val headline = if (callback.title.isEmpty()) kindLabel else "$kindLabel · ${callback.title}"
    val turnLabel = callback.turn?.takeIf { it > 0 }?.let { stringResource(R.string.agent_callback_turn, it) }
    val subline = buildList {
        add(com.openminis.app.agent.jobs.AgentCallbackLabels.localizedStatus(callback.status))
        callback.elapsed?.takeIf { it.isNotEmpty() }?.let { add(it) }
        if (!finished) {
            callback.tool?.takeIf { it.isNotEmpty() }?.let { add(it) }
            turnLabel?.let { add(it) }
        } else {
            callback.summary?.takeIf { it.isNotEmpty() }?.let { add(it) }
        }
    }.joinToString(" · ")
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(56.dp)
            .clip(shape)
            .background(ChatColors.secondaryBg)
            .border(1.dp, accent.copy(alpha = 0.25f), shape)
            .clickable(onClickLabel = stringResource(R.string.agent_open_detail), onClick = onTap)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(30.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(headline, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = ChatColors.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subline, fontSize = 12.sp, lineHeight = 15.sp, color = ChatColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = ChatColors.tertiaryText, modifier = Modifier.size(16.dp))
    }
}

// ─── Full-screen read-only transcript ────────────────────────────────────

/**
 * Full-screen, read-only mirror of an agent's (child session's) transcript:
 * the main chat's row composables with every interaction callback null and
 * no composer. The bar says what it is — spinner or check, "Agent · task",
 * tier badge, live elapsed while running — and carries a quiet red stop
 * glyph while the child is processing. Replaces the half-sheet (easy to
 * dismiss by accident, hard to scroll).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTranscriptScreen(sessionId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    // [T-android-vm-store-child-tag-survives-evict] This screen only ever shows a
    // sub agent: re-acquire in the CHILD pool, never the NORMAL default.
    val vm = remember(sessionId) {
        HeadlessChatRunner.viewModelFor(context, sessionId, ChatViewModelStore.PoolKind.CHILD)
    }
    val isStreaming by vm.isStreaming.collectAsState()
    val jobs by AgentJobRegistry.jobs.collectAsState()
    val job = jobs.values.firstOrNull { it.runSessionId == sessionId }
    val sessionTitle by vm.sessionTitle.collectAsState()
    val title = remember(sessionTitle) { HelperRunner.stripChildSessionTitlePrefix(context, sessionTitle) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isStreaming) { while (isStreaming) { delay(1000); now = System.currentTimeMillis() } }
    val accent = helperAccent()
    val startedAt = job?.startedAtMs
    // [T-android-transcript-tool-sheet] Synthetic block behind a tapped
    // callback card, mirroring ChatScreen. Held at screen scope so the sheet
    // survives the list disposing the card.
    var callbackDetailBlock by remember { mutableStateOf<AssistantBlock?>(null) }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalMarkdownSessionId provides (vm.helperConfig?.parentSessionId ?: sessionId),
        // [T-android-usage-capsule-blank-tap] Same blank-tap reveal as the main
        // chat — the transcript renders the same text blocks, so without this
        // the capsule would be discoverable on one screen and not the other.
        LocalMarkdownBlankTapHandler provides remember(vm) {
            { msgId: String -> vm.toggleUsageCapsule(msgId.substringBefore('#')) }
        },
    ) {
    Scaffold(
        containerColor = ChatColors.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ChatColors.background),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.helper_close), tint = ChatColors.primaryText) }
                },
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (isStreaming) HelperSpinner(12, 1.8f)
                            else Icon(Icons.Default.CheckCircle, contentDescription = null, tint = HelperStatusGreen, modifier = Modifier.size(14.dp))
                            Text(
                                HelperRunner.childSessionTitle(context, title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                color = ChatColors.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            job?.tierUsed?.let { TierChip(it) }
                            if (isStreaming && startedAt != null) Text(HelperRunner.formatClock(now - startedAt), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = ChatColors.secondaryText)
                        }
                    }
                },
                actions = {
                    if (isStreaming) {
                        IconButton(onClick = {
                            // [T-android-stop-sibling-subagent] Stop the whole
                            // fan-out this run belongs to, matching the card's
                            // Stop; a run with no delegating parent still stops
                            // just itself.
                            job?.let { AgentJobRegistry.cancelSiblings(sessionId, "user-stop-transcript") }
                                ?: vm.cancelStream()
                        }) {
                            Icon(Icons.Outlined.StopCircle, contentDescription = stringResource(R.string.helper_stop), tint = HelperStatusRed)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
            HelperTranscript(
                vm,
                accent,
                onOpenDetail = { vm.openToolDetail(it) },
                onOpenCallback = { callbackDetailBlock = it.syntheticBlock() },
            )
        }

        // [T-android-transcript-tool-sheet] The sheet the tapped capsule opens.
        //
        // Hosted HERE rather than inside the list item, for the same reason as
        // the main chat (T261): a sheet composed inside a LazyColumn item snaps
        // shut the moment the pill scrolls off screen and Compose disposes the
        // item. Selection lives on the child's own ChatViewModel, so it also
        // survives the transcript recomposing while the child is still
        // streaming.
        //
        // `toolBlocks` is the child's own blocks, so prev/next paging inside
        // the sheet walks THIS transcript and cannot wander into the parent's.
        val selectedId by vm.selectedToolDetailId.collectAsState()
        val transcriptMessages by vm.messages.collectAsState()
        val transcriptStreaming by vm.streamingById.collectAsState()
        val transcriptToolBlocks = remember(transcriptMessages, transcriptStreaming) {
            buildFlatChatItems(
                if (transcriptStreaming.isEmpty()) transcriptMessages
                else mergeStreamingOverlay(transcriptMessages, transcriptStreaming)
            ).filterIsInstance<FlatChatItem.AssistantToolUse>().map { it.block }
        }
        // A block can vanish mid-stream (retry-preserve, cancel). Close rather
        // than leaving a sheet bound to an id that no longer resolves.
        LaunchedEffect(selectedId, transcriptToolBlocks) {
            val id = selectedId ?: return@LaunchedEffect
            if (transcriptToolBlocks.none { it.id == id }) vm.closeToolDetail()
        }
        selectedId?.let { id ->
            val idx = transcriptToolBlocks.indexOfFirst { it.id == id }
            if (idx >= 0) {
                ToolDetailSheet(
                    toolBlocks = transcriptToolBlocks,
                    initialIndex = idx,
                    onDismiss = { vm.closeToolDetail() },
                )
            }
        }
        callbackDetailBlock?.let { block ->
            ToolDetailSheet(
                toolBlocks = listOf(block),
                initialIndex = 0,
                onDismiss = { callbackDetailBlock = null },
            )
        }
    }
    }
}

/** The read-only list: `buildFlatChatItems` over the child vm's messages
 *  (+ streaming overlay), rendered with the main list's item composables
 *  and NO interaction callbacks. */
@Composable
internal fun HelperTranscript(
    vm: ChatViewModel,
    accent: Color,
    /**
     * [T-android-transcript-tool-sheet] Tapping a tool capsule here opened
     * NOTHING: this was hard-coded to a no-op lambda while the pill kept its
     * `combinedClickable`, so it rippled under the finger and looked alive.
     * Routed through the caller so the child transcript gets the same
     * ToolDetailSheet the main chat has.
     */
    onOpenDetail: (String) -> Unit = {},
    /**
     * [T-android-transcript-tool-sheet] Same story as [onOpenDetail] for a
     * callback card, which is also `clickable` with an "open detail" label and
     * draws a chevron, yet did nothing here.
     */
    onOpenCallback: (AgentCallback) -> Unit = {},
) {
    val messages by vm.messages.collectAsState()
    val streaming by vm.streamingById.collectAsState()
    // [T-android-usage-capsule-tint] The capsule is tap-to-reveal here too.
    // This renderer showed it unconditionally, so a child transcript carried a
    // permanent stats line under every reply while the main chat kept it
    // hidden — the same easter egg behaving differently depending on which
    // screen you were on.
    val revealedUsage by vm.revealedUsageIds.collectAsState()
    val items = remember(messages, streaming) {
        val merged = if (streaming.isEmpty()) messages else mergeStreamingOverlay(messages, streaming)
        buildFlatChatItems(merged)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        items(items.asReversed(), key = { it.key }, contentType = { it.contentType }) { item ->
            when (item) {
                is FlatChatItem.UserBubble -> UserMessageBubble(message = item.message, precededByUser = item.precededByUser, onRetry = null, onEdit = null, onDeleteFromHere = null)
                is FlatChatItem.AgentCallbackCard -> AgentCallbackCard(item.callback, onTap = { onOpenCallback(item.callback) })
                // [T-android-scheduled-task-card] Same card in the read-only
                // transcript: a scheduled task can fire into a child session.
                is FlatChatItem.ScheduledTaskCardItem -> ScheduledTaskCard(marker = item.marker)
                is FlatChatItem.AssistantHeader -> AssistantHeader()
                is FlatChatItem.AssistantText -> StreamingMarkdownText(content = item.block.content, isStreaming = item.isStreaming)
                is FlatChatItem.AssistantMarkdownBlock -> StreamingMarkdownText(content = item.rawText, isStreaming = item.isStreaming)
                // [T-android-thinking-auto-collapse] Same stream signal as the
                // main chat (ChatScreen.kt) and the legacy renderer: a thinking
                // block counts as streaming only while it is the trailing block
                // of ANY kind. Passing raw `messageIsStreaming` here would keep
                // an earlier thinking block marked streaming after a sibling
                // text/tool block landed, so it would never auto-collapse.
                is FlatChatItem.AssistantThinking -> ThinkingBlock(
                    item.block,
                    isStreaming = item.isLastBlockOverall && item.messageIsStreaming,
                    isLast = item.isLast,
                )
                is FlatChatItem.AssistantToolUse ->
                    if (HelperRunner.isSubAgentToolName(item.block.toolName)) HelperToolBlock(item.block, onOpenDetail = { onOpenDetail(item.block.id) })
                    else ToolCallPill(item.block, allToolBlocks = item.allToolBlocks, onOpenDetail = onOpenDetail)
                is FlatChatItem.AssistantInfo -> FallbackInfoBlock(item.block)
                is FlatChatItem.AssistantTyping -> TypingIndicator()
                is FlatChatItem.AssistantError -> InlineErrorBanner(item.error)
                is FlatChatItem.AssistantUsage -> {
                    // Strip the "#n" the flattener appends to keep duplicate
                    // keys unique (ChatFlatItems dedupe): the toggle is keyed
                    // by MESSAGE, so the suffix would make a re-rendered row
                    // toggle a different id than the one the tap set.
                    val msgId = item.messageId.substringBefore('#')
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Secondary target, matching the main chat: blank
                            // space in the text above is the primary one.
                            .heightIn(min = 12.dp)
                            .pointerInput(msgId) {
                                detectTapGestures { vm.toggleUsageCapsule(msgId) }
                            },
                    ) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = msgId in revealedUsage,
                            enter = androidx.compose.animation.fadeIn(),
                            exit = androidx.compose.animation.fadeOut(),
                        ) {
                            UsageCapsule(item.usage, item.completedAt)
                        }
                    }
                }
                is FlatChatItem.AssistantLegacyContent -> StreamingMarkdownText(content = item.content, isStreaming = item.isStreaming)
            }
        }
    }
}
