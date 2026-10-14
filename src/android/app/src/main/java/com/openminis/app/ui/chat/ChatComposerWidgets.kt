package com.openminis.app.ui.chat

// [T-android-split-chat] Composer/input widgets + the floating tool
// activity pill, extracted from ChatScreen.kt: AttachmentChip,
// InputCircleButton, MicButton, ToolActivityPill, ThinkingLevelPicker.
// Imports trimmed after ChatScreen.kt split; externally-called ones internal.

import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.openminis.app.ui.DisplayBitmapLimits.limitDisplaySize
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AttachmentChip(
    attachment: InputAttachment,
    onRemove: () -> Unit,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    // iOS UserAttachmentList parity: 64dp chip + xmark.circle.fill remove
    // badge at the top-right that sits HALF on the chip and HALF outside
    // (a 20dp circle offset by -6dp / -6dp). The outer Box is sized 72dp
    // so the badge isn't clipped; the chip itself stays exactly 64dp,
    // padded into the Box at TopStart.
    val chipShape = RoundedCornerShape(8.dp)
    // Outer Box gives the badge room to "spill out" past the chip's
    // top-right corner without being clipped: chip is 64dp at TopStart,
    // badge is 20dp at TopEnd, so the outer needs 64 + half(badge) ≈ 72dp
    // wide and ≈ 70dp tall.
    Box(modifier = Modifier.size(width = 72.dp, height = 70.dp)) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(64.dp)
                // Tap the chip body (NOT the remove badge — that lives in
                // the outer Box) to preview the attachment, mirroring iOS
                // InputAttachmentTile.onTapGesture in AIChatView.swift:3699.
                .clip(chipShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        ) {
            if (attachment.isImage) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(attachment.uri)
                        .limitDisplaySize()
                        .build(),
                    contentDescription = attachment.fileName,
                    modifier = Modifier
                        .matchParentSize()
                        .clip(chipShape)
                        .border(1.dp, ChatColors.thumbnailBorder, chipShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                // File chip (iOS: icon + filename inside a tinted square)
                Column(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant, chipShape)
                        .border(1.dp, ChatColors.thumbnailBorder, chipShape),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    // [T-android-attachment-chip-filename] Long names used to
                    // wrap at 2 lines in a 64dp chip at 9sp — "awake-error-
                    // ui.xml" rendered as broken fragments ("awake-orra
                    // r-u.xml"). Single line + middle ellipsis keeps the
                    // extension visible, which is what identifies the file.
                    Text(
                        text = attachment.fileName,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        softWrap = false,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
        // Remove badge (iOS: xmark.circle.fill at the chip's top-right
        // corner, sitting half on / half off the thumbnail). Hairline
        // border keeps the badge readable against image content.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .border(0.5.dp, ChatColors.thumbnailBorder, CircleShape)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "移除附件",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

// ─── Input Circle Button (iOS: 34×34 circle, secondary bg + border) ─────────

@Composable
internal fun InputCircleButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(ChatColors.inputIconBg, CircleShape)
            .border(0.5.dp, ChatColors.inputIconBorder, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * Mic button with a two-state appearance — mirrors iOS `MicButton`.
 *
 * Idle: outlined mic, neutral bg.
 * Recording: filled mic, red-tinted bg, optional 2-letter locale badge
 * overlayed on the top-right (e.g. "EN", "ZH").
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MicButton(
    isRecording: Boolean,
    localeBadge: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    // [T-android-voice-panel] While the inline voice panel is active the same
    // slot switches back to text input — keyboard glyph (mirrors iOS "T").
    isVoiceActive: Boolean = false,
) {
    val bg = if (isRecording) Color.Red.copy(alpha = 0.15f)
             else ChatColors.inputIconBg
    val tint = if (isRecording) Color.Red
               else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = if (isRecording) Color.Transparent else ChatColors.inputIconBorder
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(bg, CircleShape)
            .border(0.5.dp, borderColor, CircleShape)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isVoiceActive) Icons.Default.Keyboard else Icons.Default.Mic,
            contentDescription = if (isVoiceActive) "Switch to keyboard"
            else if (isRecording) "Stop recording" else "Voice input",
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        if (!localeBadge.isNullOrEmpty()) {
            Text(
                text = localeBadge,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Red,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .background(Color.White, CircleShape)
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * Inline 5-segment picker rendered on the right side of the `/thinking` row.
 * Mirrors iOS `thinkingLevelPicker`: the active level shows a filled pill;
 * the OFF pill uses a muted background, the others use the accent color.
 */
// ─── Tool Activity Pill (taixu ToolActivityPill pattern) ───────────────────
//
// [P0-4] The floating overlay above the composer is ONE quiet capsule while
// a turn streams in fold-OFF mode: status icon + tool title + elapsed +
// stop. Tap opens the shared ToolDetailSheet. It replaces the old
// FloatingToolStatusBar (38dp full-width bar + 100×65 preview thumbnail +
// chevron paging between tools):
//   - the thumbnail duplicated what the detail sheet shows one tap away,
//     and its 65dp overhang forced a 79dp bottom reserve off the message
//     list; the pill's 34dp caps that reserve at 48dp (see bottomReserve
//     in ChatScreen);
//   - paging was a workaround for pinning N tools at once — the pill pins
//     only the LATEST block, the only one "currently happening";
//   - the stop button returns (T170 removed it from the old bar because a
//     38dp bar and the in-list pill both carried it; the pill is small
//     enough that one trailing stop is not a second loud affordance, and
//     it is the only stop visible when the user has scrolled up mid-run).
// Fold-ON mode never mounts the pill at all (isFloatingProcessTool →
// false): the running round renders every step in-list (single channel).
@Composable
internal fun ToolActivityPill(
    block: AssistantBlock,
    onStop: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    // T261 lineage: detail open routes through ChatViewModel state so this
    // surface shares one always-mounted sheet with the in-list pills.
    onOpenDetail: (String) -> Unit = {},
) {
    val isRunning = isInFlightProcessTool(block)
    val toolAccent = toolAccentColor(block.toolName)
    // 1s elapsed ticker. The pill only mounts while a turn is streaming
    // (mount gate in ChatScreen), so the loop is bounded by the turn's
    // lifetime, not the screen's.
    var nowMs by remember(block.id) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(block.id) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }
    val elapsedText = toolActivityElapsedText(block, nowMs)
    val pillShape = RoundedCornerShape(17.dp)

    Row(
        modifier = modifier
            .shadow(
                elevation = 4.dp,
                shape = pillShape,
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.12f),
            )
            .background(ChatColors.inputBg, pillShape)
            .border(0.5.dp, ChatColors.toolBorder, pillShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onOpenDetail(block.id) }
            .heightIn(min = 34.dp)
            .padding(
                start = 10.dp,
                end = if (isRunning && onStop != null) 2.dp else 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = toolAccent,
                strokeWidth = 1.5.dp,
            )
        } else {
            val (icon, tint) = when (block.toolStatus) {
                ToolBlockStatus.SUCCESS -> Icons.Default.CheckCircle to ToolCheckColor
                ToolBlockStatus.FAILED, ToolBlockStatus.TIMEOUT ->
                    Icons.Default.Error to ToolErrorColor
                ToolBlockStatus.CANCELLED -> Icons.Default.Close to ToolCancelColor
                else -> Icons.Default.Build to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = block.toolTitle.ifEmpty { block.toolName },
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = ChatColors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
        if (elapsedText != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = elapsedText,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isRunning && onStop != null) {
            Icon(
                Icons.Default.Stop,
                contentDescription = stringResource(R.string.cd_stop),
                tint = Color(0xFFFF3B30),
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onStop() },
            )
        }
    }
}

/**
 * Elapsed text for the activity pill: live ticking while the block is
 * in-flight (now − startTimeMs), the recorded duration once it settled.
 * Pure so the formatting is unit-testable without composition.
 */
internal fun toolActivityElapsedText(block: AssistantBlock, nowMs: Long): String? = when {
    isInFlightProcessTool(block) && block.startTimeMs > 0L ->
        formatToolDuration((nowMs - block.startTimeMs).coerceAtLeast(0L))
    !isInFlightProcessTool(block) && block.durationMs > 0L ->
        formatToolDuration(block.durationMs)
    else -> null
}

@Composable
internal fun ThinkingLevelPicker(
    current: ThinkingLevel,
    // [T-android-thinking-level-arch] Levels the CURRENT model actually supports
    // (OFF + everything up to its effectiveMaxThinkingLevel). Passed in so the
    // picker only ever offers reachable tiers; the row scrolls horizontally so
    // the extra GPT-5.6 tiers (MAX/ULTRA) don't overflow a narrow composer.
    availableLevels: List<ThinkingLevel>,
    onSelect: (ThinkingLevel) -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    // [T-android-thinking-level-arch] Clamped state: the stored level is higher
    // than what the current model can reach (e.g. ULTRA persisted, then the user
    // switched to DeepSeek which caps at XHIGH). `current` then isn't in
    // availableLevels, so no capsule would match `level == current` and the row
    // would look entirely unselected — as if thinking were off. Mirror iOS
    // (fb349342): highlight the highest available capsule in orange with an
    // up-arrow, signalling "your setting is higher, this model caps here".
    val maxAvailable = availableLevels.lastOrNull { it != ThinkingLevel.OFF }
    val isClamped = current.isEnabled && maxAvailable != null && current.rank > maxAvailable.rank
    val clampOrange = Color(0xFFFF9500)
    Row(
        modifier = Modifier
            .background(
                ChatColors.secondaryText.copy(alpha = 0.12f),
                RoundedCornerShape(6.dp),
            )
            .clip(RoundedCornerShape(6.dp))
            .horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        availableLevels.forEach { level ->
            val isExactMatch = level == current
            val isClampedHighlight = isClamped && level == maxAvailable
            val isHighlighted = isExactMatch || isClampedHighlight
            val bg = when {
                // [T-android-thinking-picker-ui] Clamped tier → orange; normal
                // selection → blue (ChatColors.thinking, the theme-adaptive
                // system blue 007AFF/0A84FF), matching iOS's Color.blue. The
                // old ChatColors.sendButton was black in light / white in dark,
                // so the selected capsule read as unselected. (OFF no longer
                // appears in availableLevels, so its former branch is gone.)
                isClampedHighlight -> clampOrange.copy(alpha = 0.75f)
                isHighlighted -> ChatColors.thinking
                else -> Color.Transparent
            }
            // White text on both the blue and orange fills (readable in both
            // themes); grey when unselected.
            val fg = if (isHighlighted) Color.White else ChatColors.secondaryText
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(bg)
                    // [T-android-thinking-level-arch] Tapping the already-
                    // highlighted capsule toggles thinking OFF (covers both exact
                    // and clamped highlight); otherwise selects the tapped level.
                    .clickable { onSelect(if (isHighlighted) ThinkingLevel.OFF else level) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(
                    text = level.localizedName(context),
                    fontSize = 11.sp,
                    fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                    color = fg,
                )
                if (isClampedHighlight) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}
