package com.openminis.app.ui.chat

// [T-android-split-chat] Assistant-message + tool-pill + thinking rendering
// extracted verbatim from ChatScreen.kt: AssistantHeader, AssistantMessageView,
// BoundsTrackedBlock, InlineErrorBanner, ToolStopButton,
// formatToolDetailsForClipboard, ToolCallPill, ThinkingBlock.
// Imports trimmed after ChatScreen.kt split; externally-called ones internal.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.ui.settings.autoExpandThinkingEnabled
import com.openminis.app.ui.components.MinisMenu
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.components.MinisTextButton

@Composable
internal fun AssistantHeader(speakerName: String? = null, speakerVendor: String? = null) {
    // [T-soul-md] Identity header = icon + SOUL.md-driven `name`.
    //
    // [T-android-soul-custom-icon] The icon is now the user-settable
    // `SoulMetadata.icon` (emoji or transparent PNG), falling back to the
    // canonical sparkle gradient when unset — so a user who never touches it
    // sees exactly the previous rendering.
    //
    // Deliberately the SAME composable the settings card uses. On iOS these
    // two surfaces were written separately and the chat one silently failed
    // to pick up image icons; sharing the renderer makes that class of
    // divergence impossible rather than merely unlikely.
    val namedSpeaker = speakerName?.takeIf { it.isNotBlank() }
    val soulMeta by com.openminis.app.agent.SoulStore.cachedMetadata.collectAsState()
    val displayName = namedSpeaker ?: soulMeta.name.ifBlank { com.openminis.app.agent.SoulMetadata.DEFAULT.name }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier,
        // [P1-3] Inter-row gaps are owned by the row rhythm
        // (flatItemTopGap): 16dp above this header after a user bubble, 4dp
        // from header to the turn's first content row. No self-padding —
        // stacking paddings would double the rhythm gap.
    ) {
        val sparkleGradient = Brush.linearGradient(
            colors = listOf(SparkleColor1, SparkleColor2),
        )
        // 18.dp, matching the previous Icon exactly: the row height feeds a
        // measured-height estimate in the message list, so the icon stays
        // square and same-sized whichever branch renders.
        if (namedSpeaker != null) {
            VendorMark(
                vendor = speakerVendor?.takeIf { it.isNotBlank() }
                    ?: com.openminis.app.tools.GroupChat.vendorKey(null, namedSpeaker),
                fallbackName = namedSpeaker,
            )
        } else {
        com.openminis.app.ui.settings.SoulIconGlyph(
            icon = soulMeta.icon,
            sizeDp = 18.dp,
            emojiSp = 15.sp,
            sparkleTint = sparkleGradient,
        )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = displayName,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun AssistantMessageView(message: ChatMessage, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        AssistantHeader()

        // Render blocks in original order — text, thinking, and tool calls interleaved
        // exactly as they arrived in the stream (each assistant turn may contain multiple
        // text ↔ tool_use transitions, which must be preserved for coherent reading).
        val toolPillBlocks = message.toolBlocks.filter { it.kind == "tool_use" }
        val lastThinkingId = message.toolBlocks.lastOrNull { it.kind == "thinking" }?.id
        // Backward compat: if there are no text blocks but message.content is non-empty
        // (e.g. legacy sessions saved before the text-block migration), fall back to
        // rendering message.content after all tool blocks.
        val hasAnyTextBlock = message.toolBlocks.any { it.kind == "text" }
        val lastTextBlockIndex = message.toolBlocks.indexOfLast { it.kind == "text" }
        message.toolBlocks.forEachIndexed { index, block ->
            when (block.kind) {
                "thinking" -> {
                    // [T-android-thinking-forced-visible] Same gate as the
                    // FlatChatItem path: render whenever the block carries
                    // content. Forced-reasoning models stream reasoning_
                    // content even with the request's reasoning field unset;
                    // hiding by level swallows that thinking and the user
                    // sees "model isn't thinking".
                    if (block.content.isNotBlank() || message.isStreaming) {
                        // [T-android-thinking-stable-expand] Same complete
                        // signal as the FlatChatItem path (message streaming
                        // AND no reply text after this block) — flips once
                        // per reasoning phase, not once per tool round.
                        ThinkingBlock(
                            block,
                            isStreaming = message.isStreaming && !hasReplyTextAfter(message.toolBlocks, index),
                            isLast = block.id == lastThinkingId,
                        )
                    }
                }
                "info" -> {
                    FallbackInfoBlock(block)
                }
                "text" -> {
                    if (block.content.isNotEmpty()) {
                        val isLastTextBlock = index == lastTextBlockIndex
                        val streaming = message.isStreaming && isLastTextBlock
                        // T-android-gc-storm-issue17: defensive guard on the legacy
                        // pre-FlatChatItem path too.
                        LargeContentGuard(
                            content = block.content,
                            isStreaming = streaming,
                            stableKey = "legacy-text:${message.id}:${block.id}",
                        ) {
                            StreamingMarkdownText(
                                content = block.content,
                                // Only the trailing text block is "still streaming"; earlier
                                // text blocks (before a tool call) are frozen.
                                isStreaming = streaming,
                            )
                        }
                    }
                }
                else -> {
                    // tool_use
                    ToolCallPill(block, allToolBlocks = toolPillBlocks)
                }
            }
        }

        // Typing indicator when streaming with no content yet (info-only blocks don't count)
        val hasRealBlocks = message.toolBlocks.any { it.kind != "info" }
        if (message.isStreaming && message.content.isEmpty() && !hasRealBlocks) {
            TypingIndicator()
        }

        // Legacy fallback: render message.content when no text blocks exist (old sessions).
        if (!hasAnyTextBlock && message.content.isNotEmpty()) {
            LargeContentGuard(
                content = message.content,
                isStreaming = message.isStreaming,
                stableKey = "legacy-fallback:${message.id}",
            ) {
                StreamingMarkdownText(
                    content = message.content,
                    isStreaming = message.isStreaming,
                )
            }
        }

        // Inline error banner (iOS: red exclamation + error text + Retry button)
        if (message.error != null) {
            InlineErrorBanner(error = message.error, onRetry = onRetry)
        }
    }
}

/**
 * Wraps a per-message LazyColumn item, registering its bounds (in window
 * coordinates) into [LocalMessageBoundsRegistry] so the selection toolbar
 * can look up which message a selection rect belongs to. The slot key
 * disambiguates multiple items belonging to the same message id (e.g. a
 * message with several text blocks).
 */
@Composable
internal fun BoundsTrackedBlock(
    messageId: String,
    slotKey: String,
    markdown: String,
    content: @Composable () -> Unit,
) {
    val registry = LocalMessageBoundsRegistry.current
    Box(
        modifier = Modifier.onGloballyPositioned { coords ->
            registry?.put(messageId, slotKey, coords.boundsInWindow(), markdown)
        },
    ) {
        content()
    }
    androidx.compose.runtime.DisposableEffect(messageId, slotKey) {
        onDispose { registry?.remove(messageId, slotKey) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InlineErrorBanner(error: String, onRetry: (() -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(min = 200.dp)
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFF3B30).copy(alpha = 0.12f))
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(error))
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            tint = Color(0xFFFF3B30),
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = error,
            color = Color(0xFFFF3B30),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFFF3B30).copy(alpha = 0.15f))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = Color(0xFFFF3B30),
                    modifier = Modifier.size(10.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.chat_longpress_retry), color = Color(0xFFFF3B30), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * T14: per-card stop affordance shown on running/streaming tool blocks.
 * Mirrors iOS `ToolCapsuleView`'s small red square that appears trailing
 * the tool title when `block.toolStatus == .running`. Tapping it routes to
 * the same global `cancelStream()` callback iOS uses for `onStop?()` —
 * iOS also has no per-tool cancellation API; the per-card button is purely
 * an affordance-discoverability win. Resume banner (T13) makes the global
 * cancel UX recoverable.
 *
 * Visual: 14×14 red rounded square (Color 0xFFFF3B30 = iOS systemRed).
 */
@Composable
private fun ToolStopButton(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.stop_tool)
    // Outer Box keeps the *layout* footprint at 18×18 (unchanged capsule width).
    // The inner clickable Box is 24×24 and overflows the outer bounds equally on
    // all sides (requiredSize ignores the parent's 18dp constraint), enlarging the
    // touch target to 24 while the visual 10×10 red square stays identical.
    Box(
        modifier = modifier.size(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .requiredSize(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(
                    onClickLabel = label,
                    onClick = onStop,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFFF3B30)),
            )
        }
    }
}

// ─── Tool Call Capsule (iOS: Capsule(), inline, tool-colored icon + title + duration) ─

/**
 * [T-android-tool-bubble-longpress-menu] Render a tool_use block as a
 * human-readable, paste-back-friendly clipboard string: the tool call
 * (name + id + pretty-printed input JSON) followed by the tool result
 * (char count + status + the result text). Input JSON is pretty-printed
 * when it parses as a JSON object/array; otherwise it's emitted verbatim
 * so a malformed / partial args string still copies usefully.
 */
internal fun formatToolDetailsForClipboard(block: AssistantBlock): String {
    val prettyInput = run {
        val raw = block.toolArgs
        if (raw.isBlank()) return@run "(none)"
        try {
            when (raw.trimStart().firstOrNull()) {
                '{' -> org.json.JSONObject(raw).toString(2)
                '[' -> org.json.JSONArray(raw).toString(2)
                else -> raw
            }
        } catch (_: Exception) {
            raw
        }
    }
    val statusLabel = when (block.toolStatus) {
        ToolBlockStatus.SUCCESS -> "success"
        ToolBlockStatus.FAILED -> "error"
        ToolBlockStatus.TIMEOUT -> "timeout"
        ToolBlockStatus.CANCELLED -> "cancelled"
        ToolBlockStatus.RUNNING, ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING -> "running"
        null -> "unknown"
    }
    val resultText = block.content
    return buildString {
        append("## Tool Call\n")
        append("name: ").append(block.toolName).append('\n')
        append("id: ").append(block.id).append('\n')
        append("input:\n").append(prettyInput).append('\n')
        append('\n')
        append("## Tool Result\n")
        append("(").append(resultText.length).append(" chars, ").append(statusLabel).append(")\n")
        if (resultText.isNotEmpty()) append(resultText)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolCallPill(
    block: AssistantBlock,
    allToolBlocks: List<AssistantBlock> = listOf(block),
    onRetry: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null,
    onOpenTerminalWithCommand: (String) -> Unit = {},
    // T261: detail open routes through ChatViewModel so the sheet survives
    // LazyColumn item disposal. Default no-op for the legacy
    // AssistantMessageView call site (currently dead code).
    onOpenDetail: (String) -> Unit = {},
    // [T-android-tool-bubble-longpress-menu] Long-press actions. Null
    // disables the corresponding menu item (e.g. re-run is null while
    // streaming or when there's no preceding user turn to re-run from).
    onRerunFromHere: (() -> Unit)? = null,
    onCopyDetails: (() -> Unit)? = null,
) {
    // T-android-jank-profile: this log was firing on every ToolCallPill
    // recomposition (every streaming token while a tool call is live),
    // showing up as 1.6% main thread time in profiles. Logs at composable
    // top level multiply with the number of pills × recompose rate. Gate
    // behind BuildConfig.DEBUG so production builds skip the string-build
    // entirely, and the rest of release builds don't pay for it.
    if (com.openminis.app.BuildConfig.DEBUG && false) {
        android.util.Log.d("ToolChain[UI]", "ToolCallPill render: id=${block.id} name=${block.toolName} title=${block.toolTitle} status=${block.toolStatus} contentLen=${block.content.length} argsLen=${block.toolArgs.length}")
    }

    // PENDING shares RUNNING's spinner affordance — tool JSON is received but
    // execution hasn't flipped the block to RUNNING yet (brief gap). TIMEOUT
    // shares FAILED's error styling but the icon mapping distinguishes them.
    val isRunning = block.toolStatus == ToolBlockStatus.RUNNING ||
        block.toolStatus == ToolBlockStatus.STREAMING ||
        block.toolStatus == ToolBlockStatus.PENDING
    val isDone = block.toolStatus == ToolBlockStatus.SUCCESS
    val isFailed = block.toolStatus == ToolBlockStatus.FAILED ||
        block.toolStatus == ToolBlockStatus.TIMEOUT
    val isCancelled = block.toolStatus == ToolBlockStatus.CANCELLED

    val toolAccent = toolAccentColor(block.toolName)
    val toolIcon = toolIconFor(block.toolName)

    // Icon color: tool color when running/done, error/cancel colors on failure
    val iconTint = when {
        isFailed -> ToolErrorColor
        isCancelled -> ToolCancelColor
        isDone -> ToolCheckColor
        else -> toolAccent
    }

    // iOS: always shows tool-type icon, only changes color based on status
    val displayIcon = toolIcon

    // Duration text (iOS: "0.4s" format)
    val durationText = if (block.durationMs > 0 && !isRunning) {
        val seconds = block.durationMs / 1000.0
        if (seconds < 10) String.format("%.1fs", seconds)
        else String.format("%.0fs", seconds)
    } else null

    // T125: drop the spinner that used to replace the tool icon while
    // running. iOS only animates a left→right shimmer sweep across the
    // pill background and keeps the typed icon visible — the spinner
    // both fought the icon for attention and looked stylistically off
    // next to the iOS counterpart. The bottom ToolActivityPill does keep a
    // CircularProgressIndicator — that is the running-tool status surface,
    // where a spinner reads correctly (the bar this row sits in does not).
    // [FloatingToolStatusBar was removed in e6beebb; the pill replaced it.]
    val shimmerTranslate = if (isRunning) {
        val transition = rememberInfiniteTransition(label = "toolPillShimmer")
        transition.animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2800, easing = LinearEasing),
            ),
            label = "toolPillShimmerTranslate",
        )
    } else null

    // [T-android-tool-bubble-longpress-menu] Long-press menu state, scoped
    // to this pill. The DropdownMenu is anchored to the pill via the Box
    // wrapper below so it opens beneath the tapped bubble.
    var showToolMenu by remember { mutableStateOf(false) }

    // Pill stretches up to the full row width so long titles can ellipsize
    // without pushing the duration out of view. Title takes the remaining
    // space via weight(1f), duration stays fixed-width (softWrap=false).
    Row(modifier = Modifier.fillMaxWidth()) {
      // [P1-3] vertical self-padding removed: the row rhythm owns
      // inter-row gaps (2.5dp between consecutive process rows).
      Box(modifier = Modifier.weight(1f, fill = false)) {
        Row(
            modifier = Modifier
                .background(
                    ChatColors.toolCapsuleBg,
                    CircleShape,
                )
                .border(0.5.dp, ChatColors.toolBorder, CircleShape)
                .clip(CircleShape)
                .then(
                    if (shimmerTranslate != null) {
                        Modifier.drawWithContent {
                            drawContent()
                            val w = size.width
                            val band = w * 0.6f
                            val x = shimmerTranslate.value * w
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0f),
                                        Color.White.copy(alpha = 0.18f),
                                        Color.White.copy(alpha = 0f),
                                    ),
                                    start = Offset(x, 0f),
                                    end = Offset(x + band, 0f),
                                ),
                            )
                        }
                    } else Modifier,
                )
                .combinedClickable(
                    onClick = { onOpenDetail(block.id) },
                    onLongClick = if (onRerunFromHere != null || onCopyDetails != null) {
                        { showToolMenu = true }
                    } else null,
                )
                .padding(horizontal = 12.dp)
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status icon — always the typed tool icon. Color shifts to
            // reflect terminal status (success / failed / cancelled); while
            // running it stays in the tool's accent color so the user can
            // still recognize the tool at a glance.
            Icon(
                displayIcon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(14.dp),
            )

            Spacer(modifier = Modifier.width(8.dp))

            // [T-step-timestamp v2 aa8b1128] Inline HH:mm:ss prefix removed
            // — user found it visually noisy on every tool pill. Start
            // time + elapsed duration now live in the tool detail bottom
            // sheet header instead (ToolDetailSheet, this file ~line
            // 5209). formatStepTimestamp() is still defined further down
            // because the detail sheet calls it.

            // Tool title + streaming dots after title (iOS: Text + bouncing "...").
            // weight(1f) lets the title absorb leftover width, ellipsis trims overflow.
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.toolTitle.ifEmpty { block.toolName },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    // [P-process-card] A failed call tints its OWN row's title
                    // (matching the icon) — the reference card colours the
                    // error line and leaves every other row untouched.
                    color = if (isFailed) ToolErrorColor else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isRunning) {
                    // iOS streaming: "..." bouncing dots after title text
                    StreamingDotsText()
                }
            }

            // Duration badge (iOS: monospaced gray text after title) — fixed width,
            // never compressed by the title. The HH:mm:ss start time is
            // surfaced inside the tool detail bottom sheet's bottom bar
            // instead of here — the inline pill list stays clean.
            if (durationText != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = durationText,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    softWrap = false,
                    maxLines = 1,
                )
            }
            // T168: restore per-tool stop button (reverts T117). Renders only
            // for running/streaming/pending blocks so completed pills stay
            // clean. Routes to the same global cancelStream() — there is no
            // per-tool cancellation API on either platform.
            if (isRunning && onStop != null) {
                Spacer(modifier = Modifier.width(8.dp))
                ToolStopButton(onStop = onStop)
            }
        }
        // [T-android-tool-bubble-longpress-menu] Long-press menu anchored to
        // the pill. Items mirror the user-bubble menu's style (MinisMenu +
        // DropdownMenuItem + leading icon). Each item no-ops gracefully if
        // its callback is null (re-run is gated while streaming / when no
        // preceding user turn exists).
        // [T-android-tool-menu-minwidth] Minimum width = min(220dp, screen
        // width) — the menu wants to be 220dp wide, but must never exceed the
        // device width on a narrow screen. screenWidthDp is the usable width in
        // dp; cap max to the same value so the widthIn(min,max) range is always
        // valid (min <= max) even on a sub-220dp display.
        val toolMenuWidthDp = minOf(220, LocalConfiguration.current.screenWidthDp).dp
        MinisMenu(
            expanded = showToolMenu,
            onDismissRequest = { showToolMenu = false },
            offset = androidx.compose.ui.unit.DpOffset(0.dp, 6.dp),
            modifier = Modifier.widthIn(max = toolMenuWidthDp),
            minWidth = toolMenuWidthDp,
        ) {
            if (onRerunFromHere != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tool_longpress_rerun_from_here)) },
                    onClick = { showToolMenu = false; onRerunFromHere() },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (onCopyDetails != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tool_longpress_copy_details)) },
                    onClick = { showToolMenu = false; onCopyDetails() },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
      }
        // T251: removed inline Retry affordance next to cancelled/failed pills —
        // the pill's own status icon (yellow on FAILED, gray on CANCELLED) is
        // already the unified failure tip. The button was visually noisy and
        // redundant. ToolCallPill keeps the `onRetry` parameter so upstream
        // callers don't need to change; the lambda just isn't surfaced inline
        // any more. retryLast() / retryFromMessage() remain reachable from
        // other entry points (long-press menu, etc.).
        // iOS: Spacer(minLength: 0) — pill stays content-width, not full-row-width
    }
}

// iOS-style bouncing dots (3 dots, easeInOut, staggered delay)
// [T-android-split-chat] BouncingDots / StreamingDotsText / TypingIndicator
// moved verbatim to ChatIndicators.kt (same package, now `internal`).

// ─── Thinking Block (iOS: collapsible "Deep Thinking" section, blue tint) ────

@Composable
internal fun ThinkingBlock(block: AssistantBlock, isStreaming: Boolean, isLast: Boolean = true) {
    // Per-block expand state, keyed by block.id so the user's manual toggle on
    // an earlier (finished) thinking block survives recomposition while a
    // later block is still streaming. The previous LaunchedEffect snapped
    // every non-last block back to collapsed on each `isLast` flip, which
    // fought the user's tap and produced a flicker that read as "tapping the
    // earlier block shows the streaming block's content."
    // [T-thinking-auto-expand-toggle] The initial auto-expand of a new
    // streaming block is gated on the Appearance setting (default ON =
    // historical behavior). When the user turned it off, a new streaming block
    // starts collapsed; a manual header tap still expands it (setting
    // userTouched, so neither the stream-end auto-collapse nor anything else
    // fights the user). Read once at mount — mirrors iOS ThinkingBlockView,
    // where the same UserDefaults gate sits at the one-shot auto-expand site.
    val context = LocalContext.current
    val autoExpandThinking = remember { autoExpandThinkingEnabled(context) }
    var expanded by remember(block.id) { mutableStateOf(autoExpandThinking && isLast && isStreaming) }
    var userTouched by remember(block.id) { mutableStateOf(false) }
    var wasStreaming by remember(block.id) { mutableStateOf(isStreaming) }
    LaunchedEffect(block.id, isStreaming) {
        // Collapse only on a real active->finished edge. Historic/non-active
        // thinking blocks can be rendered while their assistant turn streams.
        if (wasStreaming && !isStreaming && !userTouched) {
            delay(500)
            expanded = false
        }
        wasStreaming = isStreaming
    }
    val thinkingBlue = Color(0xFF007AFF)
    val charCount = block.content.length
    val charLabel = when {
        charCount >= 1000 -> "${charCount / 1000}K"
        else -> "$charCount"
    }
    // Keep layout bounded; shorter content renders only the newest tail.
    val thinkingWindowSize = 8000
    val thinkingHardCap = 100_000
    val overHardCap = charCount > thinkingHardCap
    var showFullContent by remember(block.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // [P1-3] vertical self-padding removed: the row rhythm owns
            // inter-row gaps (2.5dp between consecutive process rows).
            .background(thinkingBlue.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .border(0.5.dp, thinkingBlue.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Header row — only the header reacts to taps. Mirrors iOS, where
        // .onTapGesture is on the header HStack, not the whole VStack. With
        // clickable on the outer Column, a release after dragging in the
        // inner scroller registered as a tap and toggled `expanded`.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    userTouched = true
                    // [T-thinking-render-perf-android] Over the hard cap the
                    // inline scroller is bypassed entirely; tapping the header
                    // opens the native full-content viewer instead of toggling
                    // the (never-shown) inline expansion.
                    if (overHardCap) showFullContent = true
                    else expanded = !expanded
                },
        ) {
            if (isStreaming && block.toolStatus != ToolBlockStatus.SUCCESS) {
                // iOS: ProgressView().controlSize(.mini) while streaming
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    color = thinkingBlue,
                    strokeWidth = 1.5.dp,
                )
                Spacer(modifier = Modifier.width(6.dp))
            } else {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = thinkingBlue,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = stringResource(R.string.chat_thinking_header),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = thinkingBlue,
            )
            Spacer(modifier = Modifier.weight(1f))
            // [P-process-card] Thinking duration, settled by the agent loop
            // when the first text/tool call ends the reasoning phase. Same
            // slot family as the char count so the header reads
            // "思考 · 4m 59s · 11k" like the reference card.
            formatProcessDuration(block.durationMs)?.let { dur ->
                Text(
                    text = dur,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = thinkingBlue.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            if (charCount > 0) {
                Text(
                    text = charLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = thinkingBlue.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            if (overHardCap) {
                // [T-thinking-render-perf-android] No expand/collapse chevron —
                // the content is too large for the inline Compose scroller.
                // Offer the native full-content viewer instead.
                Text(
                    text = stringResource(R.string.thinking_view_full),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = thinkingBlue,
                )
            } else {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = thinkingBlue.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // Expanded content. Mirrors iOS ThinkingBlockView (AssistantBlockView.swift:648):
        // an inner scroller capped at 300dp, auto-follow to the bottom while the
        // block is streaming, manual drag at any time, and pause-on-user-scroll
        // so a user reading earlier reasoning isn't yanked back to the tail by
        // the next token.
        AnimatedVisibility(visible = expanded && !overHardCap) {
            val scrollState = rememberScrollState()
            // [T-thinking-render-perf-android] Render only the tail window so
            // Compose lays out at most `thinkingWindowSize` chars. `remember`
            // keyed on the length recomputes the substring on each token, but
            // the cost is O(window) not O(total). Snap the cut to the next
            // newline (within 200 chars) so we don't start mid-line.
            // [T-android-thinking-poll-removal] Read block.content directly —
            // the VM-side UiUpdateBatcher already paces recomposition at
            // 200-500ms, so the old 100ms poll only added lag between the
            // state write and the frame that showed it. ThinkingAutoFollow
            // publishes its SideEffect on recomposition, not on the poll.
            val renderedContent = block.content
            val isTruncated = renderedContent.length > thinkingWindowSize
            val displayContent = visibleThinkingTail(renderedContent, thinkingWindowSize)
            ThinkingAutoFollow(
                blockId = block.id,
                contentVersionValue = block.content.length,
                isStreaming = isStreaming,
                scrollState = scrollState,
            )
            Column(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .heightIn(max = 300.dp)
                    .verticalScroll(scrollState),
            ) {
                if (isTruncated) {
                    Text(
                        text = stringResource(
                            R.string.thinking_truncated_hint,
                            displayContent.length / 1000,
                            charCount / 1000,
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                Text(
                    text = displayContent,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    lineHeight = 19.sp,
                )
            }
        }

        // [T-thinking-render-perf-android] Hard-cap full-content viewer. A
        // native TextView (selectable, scrollable) renders arbitrarily large
        // thinking text without the Compose `Text` measure freeze.
        if (overHardCap && showFullContent) {
            ThinkingFullContentDialog(
                content = block.content,
                onDismiss = { showFullContent = false },
            )
        }
    }
}

@Composable
internal fun ProcessSummaryBar(
    thinkingCount: Int,
    toolCount: Int,
    expanded: Boolean,
    hasFailure: Boolean,
    totalMs: Long = 0L,
    steps: List<ProcessStepRef> = emptyList(),
    running: Boolean = false,
    activity: String = "",
    activityTitle: String = "",
    startedAtMs: Long = 0L,
    onToggle: () -> Unit,
    onOpenStep: (String) -> Unit = {},
) {
    // [P-process-card] Outlined capsule (collapsed) that grows into a card
    // listing every step (expanded). Replaces the centred hairline divider:
    // the reference treats a process run as one tappable object sitting
    // between reply paragraphs, not as a section rule.
    // [P-process-card] Reuse the thinking card's look so enabling folding is
    // not a visual break: same blue, same 12dp radius, same 6%/15% tint and
    // 0.5dp hairline, same 12×8 padding as ThinkingBlock. The old 22dp
    // backgroundless capsule looked nothing like the blocks it replaced.
    val thinkingBlue = Color(0xFF007AFF)
    val shape = RoundedCornerShape(12.dp)
    // [P-process-card] Failure tints ONLY the failed tool rows inside the
    // card (user direction): the header keeps its normal blue title — the
    // Error icon shape alone signals that something in the run failed.
    val iconTint = thinkingBlue
    // A running card ticks once a second so the duration walks. Bounded by
    // the run: the moment it settles, running flips false and the loop ends.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }
    val duration = if (running && startedAtMs > 0L) {
        formatProcessDuration((nowMs - startedAtMs).coerceAtLeast(0L))
    } else {
        formatProcessDuration(totalMs)
    }
    val header = processCardHeader(
        thinkingCount, toolCount, running, expanded, activity, activityTitle, duration,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.5.dp)
            .background(thinkingBlue.copy(alpha = 0.06f), shape)
            .border(0.5.dp, thinkingBlue.copy(alpha = 0.15f), shape)
            .clip(shape)
            .clickable(onClick = onToggle)
            // [P-process-card] Animate the expand/collapse height change:
            // without this the row list appears in one frame and the whole
            // screen visibly jumps (user: "让用户感觉整个屏幕闪一下"). A
            // gentle spring keeps the card growing/shrinking in place while
            // the list reflows around it.
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.5.dp,
                    color = iconTint,
                )
            } else {
                Icon(
                    imageVector = if (hasFailure) Icons.Default.Error else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = header,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = thinkingBlue,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = thinkingBlue.copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp),
            )
        }
        if (expanded && steps.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                thickness = 0.5.dp,
                color = thinkingBlue.copy(alpha = 0.15f),
            )
            // [P-process-card] Compact step rows, NOT the unfolded blocks:
            // the full ThinkingBlock / ToolCallPill each carry their own
            // bubble background and 36dp pill height, so an expanded card
            // was a stack of mismatched bubbles (user: "长短不一、背景颜色
            // 不统一、卡片过大"). Inside the card every step is one tight
            // row — icon (from the SAME toolIconFor/toolAccentColor mapping
            // the unfolded pills use, never invented), title, duration,
            // 查看 for tools, expand chevron for thinking — separated by
            // hairlines, no per-row background. The card is the only bubble.
            steps.forEachIndexed { idx, step ->
                if (idx > 0) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = thinkingBlue.copy(alpha = 0.10f),
                    )
                }
                ProcessStepRow(step = step, onOpen = { onOpenStep(step.id) })
            }
        }
    }
}

/**
 * [P-process-card] One compact row inside an expanded process card.
 *
 * Tool row: the tool's own icon (toolIconFor — the same mapping the
 * unfolded pill uses) in its accent color, title, duration, and a 查看
 * link that opens the shared detail sheet. A FAILED tool tints its icon
 * AND title in the error colour — the only place failure is coloured.
 *
 * Thinking row: the Psychology icon, 思考, char count, duration, and a
 * chevron that unfolds the reasoning text inline (300dp cap, scrollable).
 * A running thinking step auto-expands and settles 500ms after it
 * finishes; a step the user collapsed by hand is never reopened by the
 * card expanding — the two layers are independent, keyed on step.id.
 */
@Composable
private fun ProcessStepRow(step: ProcessStepRef, onOpen: () -> Unit) {
    val thinkingBlue = Color(0xFF007AFF)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val isThinking = step.kind == "thinking"
    val isFailed = !isThinking && step.status == ToolBlockStatus.FAILED
    val block = step.block

    var stepExpanded by remember(step.id) { mutableStateOf(step.running) }
    var userTouched by remember(step.id) { mutableStateOf(false) }
    LaunchedEffect(step.id, step.running) {
        if (!step.running && !userTouched) {
            delay(500)
            stepExpanded = false
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (isThinking) {
                        userTouched = true
                        stepExpanded = !stepExpanded
                    } else {
                        onOpen()
                    }
                }
                .padding(vertical = 5.dp),
        ) {
            if (isThinking) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = thinkingBlue,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Icon(
                    imageVector = toolIconFor(block.toolName),
                    contentDescription = null,
                    tint = if (isFailed) ToolErrorColor else toolAccentColor(block.toolName),
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isThinking) stringResource(R.string.chat_thinking_header)
                    else block.toolTitle.ifEmpty { block.toolName },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (isFailed) ToolErrorColor else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isThinking) {
                val charCount = block.content.length
                if (charCount > 0) {
                    Text(
                        text = if (charCount >= 1000) "${charCount / 1000}K" else "$charCount",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = muted,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }
            formatProcessDuration(step.durationMs)?.let { dur ->
                Text(
                    text = dur,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = muted,
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            if (!isThinking) {
                Text(
                    text = stringResource(R.string.chat_process_step_view),
                    fontSize = 12.sp,
                    color = thinkingBlue,
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Icon(
                imageVector = if (isThinking) {
                    if (stepExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                contentDescription = null,
                tint = if (isThinking) thinkingBlue.copy(alpha = 0.5f) else muted,
                modifier = Modifier.size(14.dp),
            )
        }
        if (isThinking) {
            AnimatedVisibility(visible = stepExpanded && block.content.isNotEmpty()) {
                val scrollState = rememberScrollState()
                val thinkingWindowSize = 8000
                val isTruncated = block.content.length > thinkingWindowSize
                val displayContent = visibleThinkingTail(block.content, thinkingWindowSize)
                ThinkingAutoFollow(
                    blockId = block.id,
                    contentVersionValue = block.content.length,
                    isStreaming = step.running,
                    scrollState = scrollState,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, top = 2.dp, bottom = 6.dp)
                        .heightIn(max = 300.dp)
                        .verticalScroll(scrollState),
                ) {
                    if (isTruncated) {
                        Text(
                            text = stringResource(
                                R.string.thinking_truncated_hint,
                                displayContent.length / 1000,
                                block.content.length / 1000,
                            ),
                            fontSize = 11.sp,
                            color = muted.copy(alpha = 0.75f),
                        )
                    }
                    Text(
                        text = displayContent,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = muted,
                    )
                }
            }
        }
    }
}


/**
 * [T-thinking-render-perf-android] Full-screen viewer for thinking content
 * that exceeds the inline hard cap. Wraps a native Android [android.widget.TextView]
 * (inside a scroller) — it lays out very large strings far more cheaply than
 * Compose `Text`, and stays selectable.
 */
@Composable
private fun ThinkingFullContentDialog(content: String, onDismiss: () -> Unit) {
    val textColor = MaterialTheme.colorScheme.onSurface
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chat_thinking_header),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF007AFF),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    MinisTextButton(onClick = onDismiss) {
                        Text(text = stringResource(android.R.string.ok))
                    }
                }
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        android.widget.ScrollView(ctx).apply {
                            addView(
                                android.widget.TextView(ctx).apply {
                                    textSize = 13f
                                    setTextColor(textColor.toArgb())
                                    setTextIsSelectable(true)
                                    setPadding(36, 24, 36, 48)
                                    text = content
                                }
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}
