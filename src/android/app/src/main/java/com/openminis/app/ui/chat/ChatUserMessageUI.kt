package com.openminis.app.ui.chat

// [T-android-split-chat] User-message rendering extracted verbatim from
// ChatScreen.kt: UserMessageBubble, UserAttachmentList, FileAttachmentTile,
// fileIconFor, ImageGalleryDialog. Imports trimmed after ChatScreen.kt split.
// Externally-called ones (UserMessageBubble, UserAttachmentList) are internal.

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.openminis.app.R
import com.openminis.app.ui.components.MinisMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.key.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openminis.app.ui.theme.ChatColors

// ─── User Message (right-aligned, iOS: tertiarySystemFill bubble, 18dp radius) ─

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun UserMessageBubble(
    message: ChatMessage,
    onCopy: () -> Unit = {},
    onRetry: (() -> Unit)? = {},
    onEdit: (() -> Unit)? = null,
    // [T-android-delete-from-here] Delete this message and everything after
    // it. Null hides the action (streaming, or a host with no truncation
    // capability).
    onDeleteFromHere: (() -> Unit)? = null,
    onWithdraw: (() -> Unit)? = null,
    onPreviewFile: (Uri, String) -> Unit = { _, _ -> },
) {
    var showMenu by remember { mutableStateOf(false) }
    val isQueued = message.isQueued
    val haptics = LocalHapticFeedback.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth(),
        // [P1-3] Inter-row gaps are owned by the row rhythm (flatItemTopGap
        // in ChatFlatItems) applied as top padding on every list row; this
        // bubble no longer pads itself — stacking paddings here would double
        // the rhythm gap.
    ) {
        // Cap the bubble at 80% of the LazyColumn's available width (mirrors iOS
        // proportional sizing; prevents single-line messages from spanning the
        // full row and losing their "trailing bubble" shape).
        val bubbleMaxWidth = this.maxWidth * 0.8f
    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Spacer(modifier = Modifier.weight(1f).widthIn(min = 60.dp))
        Box(
            modifier = Modifier
                .widthIn(max = bubbleMaxWidth)
                // pointerInput on the OUTER box (= MinisMenu's anchor). Long-press
                // anywhere on the bubble (text or attachments) opens the menu;
                // press coords are stored in this box's coordinate space, which
                // is exactly what DropdownMenu's `offset` parameter expects.
                .pointerInput(message.id) {
                    detectTapGestures(
                        onLongPress = {
                            // Haptic must be fired by hand here. `combinedClickable`
                            // (what the tool pill uses) buzzes on long-press for
                            // free; raw `detectTapGestures` does not, so this
                            // gesture — chosen for its press OFFSET — silently
                            // lost the feedback every other long-press menu has.
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Anchor the menu to the bubble — DropdownMenu
                            // already places itself just below the anchor and
                            // auto-flips above when there's no room. Using
                            // the press y as offset (previous behavior) made
                            // the menu jump halfway down a tall bubble away
                            // from the user's finger, which on multi-line
                            // user messages landed in screen center.
                            showMenu = true
                        }
                    )
                }
        ) {
            // iOS: VStack(alignment: .trailing, spacing: 6) { attachments; text bubble }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Attachments ABOVE the text bubble, right-aligned FlowRow (iOS UserAttachmentList)
                val hasAttachments = message.imageUris.isNotEmpty() || message.attachmentNames.isNotEmpty()
                if (hasAttachments) {
                    UserAttachmentList(
                        imageUris = message.imageUris,
                        allFileNames = message.attachmentNames,
                        nonImageUris = message.attachmentUris,
                        onPreviewFile = onPreviewFile,
                    )
                }

                if (message.content.isNotBlank()) {
                    // Queued: transparent bg + dashed border + dimmed text +
                    // a red withdraw button alongside. Mirrors iOS
                    // AIChatView.swift queued-bubble overlay.
                    val secondaryTextColor = ChatColors.secondaryText
                    val userBubbleColor = ChatColors.userBubble
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val textColor = if (isQueued) secondaryTextColor else MaterialTheme.colorScheme.onSurface
                        val bubbleBg = if (isQueued) Color.Transparent else userBubbleColor
                        val shape = RoundedCornerShape(18.dp)
                        val dashedStroke = if (isQueued) {
                            Modifier.drawBehind {
                                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 1.5.dp.toPx(),
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f
                                    ),
                                )
                                val r = 18.dp.toPx()
                                drawRoundRect(
                                    color = secondaryTextColor.copy(alpha = 0.5f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                                    style = stroke,
                                )
                            }
                        } else Modifier
                        // The whole LazyColumn is wrapped in a SelectionContainer,
                        // which by default starts text-selection on long-press. We
                        // want long-press on a user bubble to open the Copy/Retry
                        // menu instead, with NO selection ever registered against
                        // this Text. If we let selection register and the user
                        // taps "Retry", retryFromMessage truncates the list and
                        // the SelectionManager toolbar update then sorts stale
                        // LayoutCoordinates → IllegalArgumentException
                        // ("layouts are not part of the same hierarchy").
                        // DisableSelection scopes the bubble out of selection.
                        // Long-press is captured on the outer Box (above) so
                        // press coords share a LayoutCoordinates space with the
                        // menu anchor.
                        androidx.compose.foundation.text.selection.DisableSelection {
                            // T167: when queued, leave room for the 44dp cancel
                            // IconButton sibling. Without weight(1f, fill=false)
                            // a wide bubble would consume the full 80% width
                            // cap and squeeze the cancel button off-screen.
                            val bubbleModifier = if (isQueued && onWithdraw != null) {
                                Modifier.weight(1f, fill = false)
                            } else Modifier
                            // T-android-gc-storm-issue17: long user messages (rare,
                            // but legacy sessions can carry pasted log dumps) blow
                            // up text layout the same way large assistant content
                            // does. User bubbles never stream so isStreaming=false.
                            LargeContentGuard(
                                content = message.content,
                                isStreaming = false,
                                stableKey = "user:${message.id}",
                            ) {
                                Text(
                                    text = com.openminis.app.agent.DisplayRegex.apply(
                                        androidx.compose.ui.platform.LocalContext.current,
                                        message.content,
                                        com.openminis.app.agent.DisplayRegex.Scope.USER,
                                    ),
                                    color = textColor,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.5.sp),
                                    modifier = bubbleModifier
                                        .background(bubbleBg, shape)
                                        .clip(shape)
                                        .then(dashedStroke)
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                        if (isQueued && onWithdraw != null) {
                            // T52: bring the withdraw target up to Material's
                            // 44dp recommended minimum so it can be hit
                            // reliably from the soft-IME-keyboard region;
                            // 28dp was below the spec and frustrating on
                            // Pixel-class displays. Icon nudged to 24dp to
                            // stay visually centred in the larger ring.
                            IconButton(
                                onClick = onWithdraw,
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Cancel,
                                    contentDescription = "Withdraw queued message",
                                    tint = Color(0xFFFF3B30),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Long-press context menu — anchored to the bubble itself.
            // Small positive y-offset so the menu doesn't sit flush against
            // the bubble; the alignEnd path mirrors the offset when it
            // auto-flips above (no room below), so the gap is symmetric in
            // both directions. T238: alignEnd pins the menu's right edge to
            // the bubble's right edge — user bubbles are right-aligned, the
            // menu should follow. Mirrors iOS Messages.app context menu.
            // T280: shrink width ~30% (default minWidth 240dp → 168dp,
            // alignEnd-branch max 280dp → 196dp) so the popup feels less
            // chunky on user bubbles, which only host 2-3 short items
            // (Copy / Retry / Edit). Override is local to the user-message
            // call site — other MinisMenu callers keep the default 240dp
            // minimum.
            // [T-android-tool-menu-minwidth] Match the tool-pill long-press
            // menu: width = min(220dp, screen width). Wants 220dp but must never
            // exceed the device width on a narrow screen; cap max to the same
            // value so the widthIn(min,max) range is always valid.
            val userMenuWidthDp = minOf(220, LocalConfiguration.current.screenWidthDp).dp
            MinisMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                offset = androidx.compose.ui.unit.DpOffset(0.dp, 6.dp),
                alignEnd = true,
                modifier = Modifier.widthIn(max = userMenuWidthDp),
                minWidth = userMenuWidthDp,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_longpress_copy)) },
                    onClick = { showMenu = false; onCopy() },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                // T119: while the model is generating, hide every action that
                // would mutate the in-flight turn. Retry truncates history and
                // restarts the stream, which corrupts state if it races a live
                // tool loop. Caller passes null for `onRetry` while streaming.
                if (onRetry != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_longpress_retry)) },
                        onClick = { showMenu = false; onRetry() },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
                // T187: Edit replaces the original turn with edited text. Same
                // gating as retry — caller passes null while streaming.
                if (onEdit != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_longpress_edit)) },
                        onClick = { showMenu = false; onEdit() },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
                // [T-android-delete-from-here] Removes this message and every
                // message after it. Destructive and not undoable, so it sits
                // last (furthest from the thumb's resting position on the
                // items above) and is tinted with the error colour like the
                // Retry affordance. Gated while streaming for the same reason
                // as Retry/Edit — the caller passes null.
                if (onDeleteFromHere != null) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.chat_longpress_delete_from_here),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { showMenu = false; onDeleteFromHere() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
        }
    }
    }
}

// ─── User Attachment List (iOS: UserAttachmentList — 64dp tiles, FlowRow, trailing) ─

/**
 * Mirrors iOS UserAttachmentList (AIChatView.swift:4416-4518).
 * 64dp uniform tiles in a FlowRow, right-aligned, 6dp gaps.
 * Images render as thumbnail (tap → fullscreen preview dialog).
 * Files render as icon + 2-line filename (tap → system handler).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UserAttachmentList(
    imageUris: List<Uri>,
    allFileNames: List<String>,
    nonImageUris: List<Uri> = emptyList(),
    onPreviewFile: (Uri, String) -> Unit = { _, _ -> },
) {
    var previewImageIndex by remember { mutableStateOf<Int?>(null) }

    // attachmentNames[0..imageUris.size-1] correspond to images; rest are non-image files.
    val imageCount = imageUris.size
    val fileNames = allFileNames.drop(imageCount)

    val tileSize = 64.dp

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        imageUris.forEachIndexed { idx, uri ->
            AsyncImage(
                model = uri,
                contentDescription = "Image attachment",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(tileSize)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ChatColors.secondaryBg)
                    .border(0.5.dp, ChatColors.thumbnailBorder, RoundedCornerShape(8.dp))
                    .clickable { previewImageIndex = idx },
            )
        }

        fileNames.forEachIndexed { idx, name ->
            val uri = nonImageUris.getOrNull(idx)
            FileAttachmentTile(
                fileName = name,
                tileSize = tileSize,
                onClick = {
                    // T150: route into FilePreviewScreen when we have a stable
                    // host URI for this attachment (set via mediaRef on send
                    // and on session restore). Fall back to no-op when the
                    // chip predates T150 persistence.
                    if (uri != null) onPreviewFile(uri, name)
                },
            )
        }
    }

    previewImageIndex?.let { startIdx ->
        // T-imgswipe-4f446d83: route through the shared swipe-able
        // viewer so user-bubble image attachments get the same caption
        // capsule + Copy/Share/Save chrome as composer chip / markdown
        // taps. attachmentNames[0..imageCount-1] are the image filenames
        // in display order so they line up 1:1 with imageUris.
        com.openminis.app.ui.components.ImageGalleryViewer(
            items = imageUris.mapIndexed { i, uri ->
                com.openminis.app.ui.components.ImageGalleryItem(
                    model = uri,
                    caption = allFileNames.getOrNull(i),
                )
            },
            startIndex = startIdx,
            onDismiss = { previewImageIndex = null },
        )
    }
}

@Composable
private fun FileAttachmentTile(
    fileName: String,
    tileSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .size(tileSize)
            .clip(RoundedCornerShape(8.dp))
            .background(ChatColors.secondaryBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = fileIconFor(fileName),
            contentDescription = null,
            tint = ChatColors.secondaryText,
            modifier = Modifier
                .size(20.dp)
                .weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = fileName,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            color = ChatColors.primaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Map a filename extension to a Material icon. Mirrors iOS fileIconName(for:). */
private fun fileIconFor(fileName: String): androidx.compose.ui.graphics.vector.ImageVector {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "pdf" -> Icons.Default.PictureAsPdf
        "doc", "docx", "pages" -> Icons.AutoMirrored.Filled.Article
        "txt", "log", "csv", "md", "markdown" -> Icons.AutoMirrored.Filled.Article
        "mp4", "mov", "avi", "mkv" -> Icons.Default.VideoFile
        "mp3", "wav", "m4a", "aac" -> Icons.Default.AudioFile
        "zip", "tar", "gz", "7z" -> Icons.Default.FolderZip
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

/** Fullscreen image preview dialog with pager across all image attachments. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageGalleryDialog(
    uris: List<Uri>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val pagerState = androidx.compose.foundation.pager.rememberPagerState(
            initialPage = startIndex.coerceIn(0, (uris.size - 1).coerceAtLeast(0)),
            pageCount = { uris.size },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
        ) {
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                AsyncImage(
                    model = uris[page],
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                )
            }
            if (uris.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${uris.size}",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ─── Assistant Message (left-aligned, no bubble, with "Minis Ultra" header like iOS) ─

// ─── Flattened chat items ────────────────────────────────────────────────────
// Each message is expanded into a sequence of independent LazyColumn items (header,
// text block, tool pill, etc.). This makes scroll-hovering stable during streaming:
// LazyListState anchors on a stable per-item key, so only the trailing streaming item
// changes height while earlier items remain frozen and their scroll positions
// untouched.

