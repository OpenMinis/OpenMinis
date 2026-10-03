package com.openminis.app.ui.chat

// [T-android-split-chat] Composer/input widgets + tool preview/status bar
// extracted verbatim from ChatScreen.kt: AttachmentChip, InputCircleButton,
// MicButton, ToolPreviewThumbnail, FloatingToolStatusBar, ThinkingLevelPicker.
// Full import block copied (unused=warnings); externally-called ones internal.

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.automirrored.filled.Article
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openminis.app.offload.OffloadPermissionManager
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.browser.BrowserSheet
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.DisplayBitmapLimits
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.DecorativeSpinner

@OptIn(ExperimentalFoundationApi::class)

/**
 * [T-android-tile-tail-memo] The last [n] lines of [text], scanning backward
 * from the end so the cost is O(length of those lines), not O(whole string).
 *
 * `lines()` / `lineSequence().toList()` both materialize every line of a
 * growing shell log on each call; the live tile only ever shows the tail, so
 * walking back from the end past [n] newlines and substringing once is all
 * that is needed.
 */
private fun lastLinesOf(text: String, n: Int): String {
    if (text.isEmpty() || n <= 0) return ""
    var idx = text.length
    var newlines = 0
    // Ignore a single trailing newline so it does not count as a blank line.
    var end = text.length
    if (text[end - 1] == '\n') end--
    idx = end
    while (idx > 0) {
        val nl = text.lastIndexOf('\n', idx - 1)
        if (nl < 0) { idx = 0; break }
        newlines++
        if (newlines >= n) { idx = nl + 1; break }
        idx = nl
    }
    return text.substring(idx, end)
}

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
                    model = attachment.uri,
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
                    Text(
                        text = attachment.fileName,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
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
                contentDescription = "Remove",
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

// ─── Tool Preview Thumbnail (iOS: ToolPreviewThumbnail — tool-specific preview) ──

/**
 * [T-android-agent-inner-tool-front] The tool a SUB AGENT is running right now,
 * observed from the parent's preview.
 *
 * Android port of iOS `liveChildToolBlock` (ToolLiveSheet.swift:696). The child
 * runs its own ChatViewModel in the shared process store, so the parent can read
 * its transcript directly rather than waiting for the once-a-second mirror —
 * which only ever carried the tool's NAME (HelperRunner.progressJson), never its
 * content. That is why the preview showed "Agent · Shell" instead of the shell
 * output the child was actually producing.
 *
 * Two details are load-bearing:
 *  - `existing()` never CONSTRUCTS a view model. Building one here would both
 *    race the child's own construction and resurrect a finished session.
 *  - the streaming side-channel must be merged in. While the child's turn is in
 *    flight its tool blocks live ONLY in `streamingById`; reading `messages`
 *    alone shows nothing until the turn ends (the same trap documented on
 *    ChatViewModel.childLiveMessages).
 *
 * Returns null when there is no child, no live view model, or the child is
 * between tools — the caller then falls back to the agent card, exactly as iOS
 * does.
 */
@Composable
internal fun rememberChildLiveToolBlock(childSessionId: String?): AssistantBlock? {
    if (childSessionId.isNullOrEmpty()) return null
    val vm = remember(childSessionId) {
        runCatching { ChatViewModelStore.existing(childSessionId) }.getOrNull()
    } ?: return null
    // Plain collectAsState, not the lifecycle-aware variant: this tile only
    // exists while the floating bar is composed, and the module does not
    // already depend on lifecycle-runtime-compose — not worth a new dependency
    // for a subscription whose own composition is the lifetime bound.
    val msgs by vm.messages.collectAsState()
    val streaming by vm.streamingById.collectAsState()
    return remember(msgs, streaming) {
        // [T-android-stream-overlay-subset] The child tile only wants the live
        // tool on the newest assistant turn; the subset carries exactly that
        // without copying the child's whole history each token.
        val merged = if (streaming.isEmpty()) msgs else streamingOverlaySubset(msgs, streaming)
        (merged.lastOrNull { it.role == "assistant" }
            ?: msgs.lastOrNull { it.role == "assistant" })
            ?.toolBlocks
            ?.lastOrNull {
                it.kind == "tool_use" && (
                    it.toolStatus == ToolBlockStatus.RUNNING ||
                        it.toolStatus == ToolBlockStatus.STREAMING ||
                        it.toolStatus == ToolBlockStatus.PENDING
                    )
            }
    }
}

@Composable
private fun ToolPreviewThumbnail(
    block: AssistantBlock,
    toolAccent: Color,
    onClick: () -> Unit,
    // [T-android-browser-preview-thumb] For browser_use blocks whose action
    // does not produce a screenshot (get_readable, get_text, fetch, etc.),
    // fall back to the most-recent prior browser_use screenshot — same
    // semantics the expanded detail sheet uses. Resolved at the call site
    // because toolBlocks isn't in scope here.
    fallbackImagePath: String? = null,
    /**
     * [T-android-agent-inner-tool-front] How many sub-agent levels this tile has
     * already punched through. A sub agent can itself delegate, so without a cap
     * the substitution below would recurse for as long as the chain is deep.
     * iOS is bounded structurally — `liveChildToolBlock` resolves only the
     * DIRECT child — so one level is the behaviour to match.
     */
    punchThroughDepth: Int = 0,
) {
    val args = remember(block.toolArgs) {
        try { org.json.JSONObject(block.toolArgs) } catch (_: Exception) { org.json.JSONObject() }
    }

    // T141: shell_execute live HUD — CPU% + MEM ribbon at the thumbnail
    // bottom while the tool is RUNNING/STREAMING/PENDING. Mirrors iOS
    // ToolLiveSheet.swift:1926 small overlay (5pt monospace, white .8 on
    // black .6).
    val isShellTool = block.toolName == "shell_execute"
    val isLive = block.toolStatus == ToolBlockStatus.RUNNING ||
        block.toolStatus == ToolBlockStatus.STREAMING ||
        block.toolStatus == ToolBlockStatus.PENDING
    val resourceMonitor = rememberSystemResourceMonitor(active = isShellTool && isLive)

    val thumbnailShape = RoundedCornerShape(8.dp)
    // [T-android-agent-thumbnail-accent] A sub agent's tile is marked while
    // the child is inside a tool — the tap goes to work happening one level
    // down, not to the parent's own tool. Same predicate iOS
    // ToolPreviewThumbnail.isSubAgentTool uses ("is this agent inside a tool
    // right now"), read here from the block's live progress payload; an agent
    // that is starting up or has finished needs no marking.
    //
    // [T-android-agent-inner-tool-front] Two ways to be a sub agent's tile now.
    // Once the punch-through above substitutes the CHILD's block, `block` is the
    // child's shell_execute / browser_use — its toolName is no longer the agent
    // tool, so the original predicate would go false exactly when the glow is
    // most warranted. `punchThroughDepth > 0` says "this tile is already showing
    // work one level down", which is the same question the ring is asking.
    val subAgentLive = punchThroughDepth > 0 ||
        (
            block.toolName == com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME &&
                (remember(block.content, block.toolStatus, block.toolTitle) { parseHelperBlock(block) }.phase as? HelperPhase.Running)?.tool != null
            )
    val subAgentGlow = helperAccent()
    Box(
        modifier = Modifier
            .size(width = 100.dp, height = 65.dp)
            .shadow(elevation = 10.dp, shape = thumbnailShape, ambientColor = Color.Black.copy(alpha = 0.15f), spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(thumbnailShape)
            .background(
                when (block.toolName) {
                    "file_read", "file_write" -> ChatColors.secondaryBg
                    "file_edit" -> Color(0xFF1A1A1E)
                    else -> Color(0xFF1A1A1E)
                }
            )
            .border(0.5.dp, ChatColors.thumbnailBorder, thumbnailShape)
            .drawWithContent {
                drawContent()
                if (!subAgentLive) return@drawWithContent
                // An INNER glow: concentric strokes walking inward from the
                // edge, drawn after the content and inside the clip so the
                // preview stays legible and the rounded corners are not
                // smeared square. Hand-drawn because Modifier.blur needs API
                // 31 and minSdk here is lower.
                //
                // [T-android-agent-glow-soften] Reworked from the original
                // port of iOS's 6pt/0.55-alpha inset stroke, which on a 100x65
                // tile read as a hard bright ring hugging the rim rather than
                // a glow. Three changes, all pulling the same direction:
                //
                //   band  10dp -> 22dp   reaches a third of the way into the
                //                        tile's short side, so the colour
                //                        diffuses instead of outlining.
                //   alpha 0.55  -> 0.22  the peak sits at the very edge, where
                //                        it also overlaps the hairline border,
                //                        so it was the most saturated part of
                //                        the whole tile.
                //   falloff (1-t)^2 -> (1-t)^3
                //                        a cubic curve spends less of its
                //                        opacity near the edge and carries a
                //                        faint tail further in, which is what
                //                        makes a wide band read as diffuse
                //                        rather than as a thicker ring.
                //
                // Strokes overlap: each is drawn 2x the spacing. Ring n and
                // ring n+1 sit `step` apart on the axes but `step * sqrt(2)`
                // apart along the diagonals, so equal-width strokes leave a
                // ~0.2dp gap at every corner and the glow bands there. The 10dp
                // band was opaque enough to hide it; a 22dp one is not.
                //
                // Overlapping doubles how much colour each pixel receives, so
                // the peak alpha is halved to 0.11 to pay for it — the tuning
                // target is the INTEGRAL over the band, not the per-stroke
                // value.
                //
                // The corner radius stays `radius - inset`, floored at zero.
                // That is not an approximation to fix: a rounded rect inset by
                // more than its own radius genuinely has square corners, and
                // every ring is concentric with the tile because the offset
                // from each edge is constant. (Scaling the radius
                // proportionally instead was tried and is wrong — it bulges the
                // corners outward by up to 5dp mid-band and draws visible arcs.)
                // [T-android-agent-glow-32dp] Band widened 22dp -> 32dp on
                // request: the visible tint now reaches ~15dp in instead of
                // ~11dp. On the 65dp tile the top and bottom bands meet in the
                // middle (2 x 32 = 64dp), so the tint covers the whole height
                // and fades to near zero at the centre line. Peak alpha and the
                // cubic falloff are unchanged, so the edge is no brighter.
                val band = 32.dp.toPx()
                val step = 0.5.dp.toPx()
                val radius = 8.dp.toPx()
                var inset = 0f
                while (inset < band) {
                    val t = inset / band
                    val fade = (1f - t)
                    val alpha = 0.11f * fade * fade * fade
                    val w = size.width - inset * 2
                    val h = size.height - inset * 2
                    // Stop before the rect degenerates. A 32dp band just clears
                    // the 65dp tile this draws on today, but the size comes from
                    // the layout and is not this draw's to assume.
                    if (w <= 0f || h <= 0f) break
                    drawRoundRect(
                        color = subAgentGlow.copy(alpha = alpha),
                        topLeft = Offset(inset, inset),
                        size = Size(w, h),
                        cornerRadius = CornerRadius((radius - inset).coerceAtLeast(0f)),
                        style = Stroke(width = step * 2f),
                    )
                    inset += step
                }
            }
            .clickable(onClick = onClick),
    ) {
        when (block.toolName) {
            "shell_execute" -> {
                // iOS: command title + last 12 lines of output in green mono
                val command = extractShellCommand(args, block)
                Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
                    Text(
                        text = "$ $command",
                        fontSize = 7.sp,
                        lineHeight = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (block.content.isNotEmpty()) {
                        Text(
                            // [T-android-tile-tail-memo] Split the tail only when
                            // content changes, not on every recompose. During a
                            // streaming shell the tile recomposes per delta, and an
                            // unmemoized lines()+takeLast()+joinToString on a growing
                            // string is O(n) each time — a measurable slice of the
                            // per-token allocation churn.
                            text = remember(block.content) { lastLinesOf(block.content, 12) },
                            fontSize = 5.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = toolAccent.copy(alpha = 0.85f),
                            maxLines = 12,
                            lineHeight = 6.5.sp,
                        )
                    }
                }
            }

            "file_edit" -> {
                // iOS: diff preview — old lines in red, new lines in green
                val oldStr = args.optString("old_string", "")
                    .ifEmpty { extractPartialJsonString("old_string", block.toolArgs) ?: "" }
                val newStr = args.optString("new_string", "")
                    .ifEmpty { extractPartialJsonString("new_string", block.toolArgs) ?: "" }
                Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
                    if (oldStr.isNotEmpty()) {
                        oldStr.lines().takeLast(5).forEach { line ->
                            Text(
                                text = "- $line",
                                fontSize = 5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFFF6B6B),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 6.sp,
                            )
                        }
                    }
                    if (newStr.isNotEmpty()) {
                        newStr.lines().takeLast(5).forEach { line ->
                            Text(
                                text = "+ $line",
                                fontSize = 5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF4ADE80),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 6.sp,
                            )
                        }
                    }
                    if (oldStr.isEmpty() && newStr.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(toolIconFor("file_edit"), contentDescription = null, tint = toolAccent.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            "file_read", "file_write" -> {
                // iOS ToolPreviewThumbnail.textPreview: filename header + last N lines of
                // content. For file_write during streaming, prefer partial `content` from
                // toolArgs JSON (mirrors iOS block.streamingFileContent).
                val path = args.optString("path", "")
                    .ifEmpty { extractPartialJsonString("path", block.toolArgs) ?: "" }
                val rawName = if (path.contains("/")) path.substringAfterLast("/") else path
                val header = when {
                    rawName.isNotEmpty() -> rawName
                    block.toolName == "file_write" -> "Write file"
                    else -> "Read file"
                }
                val displayText = if (block.toolName == "file_write") {
                    args.optString("content", "")
                        .ifEmpty { extractPartialJsonString("content", block.toolArgs) ?: "" }
                        .ifEmpty { block.content }
                } else {
                    block.content
                }
                Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Text(
                        text = header,
                        fontSize = 7.sp,
                        lineHeight = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = ChatColors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (displayText.isNotEmpty()) {
                        val lines = displayText.lines()
                        Text(
                            text = lines.takeLast(12).joinToString("\n"),
                            fontSize = 5.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = toolAccent.copy(alpha = 0.85f),
                            maxLines = 12,
                            lineHeight = 6.5.sp,
                        )
                    }
                }
            }

            "memory_write", "memory_get" -> {
                // iOS ToolPreviewThumbnail: header = action name, body = last
                // lines of memory content. memory_write takes content from
                // toolArgs (the text being saved); memory_get takes it from
                // block.content (the search result), prefixed with the
                // queried keywords. Mirrors ToolDetailSheet's resolution
                // (ChatScreen.kt:4581) so the thumbnail isn't an empty pink
                // icon while the rest of the UI shows real text.
                val memContent = if (block.toolName == "memory_write") {
                    args.optString("content", "")
                        .ifEmpty { extractPartialJsonString("content", block.toolArgs) ?: "" }
                        .ifEmpty { block.content }
                } else {
                    block.content
                }
                val keywords = args.optString("keywords", "")
                    .ifEmpty { extractPartialJsonString("keywords", block.toolArgs) ?: "" }
                val displayText = if (block.toolName == "memory_get" && keywords.isNotEmpty()) {
                    "Keywords: $keywords\n$memContent"
                } else memContent
                Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Text(
                        text = block.toolName,
                        fontSize = 7.sp,
                        lineHeight = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = ToolMemoryAccent.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (displayText.isNotEmpty()) {
                        Text(
                            text = displayText.lines().takeLast(12).joinToString("\n"),
                            fontSize = 5.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = toolAccent.copy(alpha = 0.85f),
                            maxLines = 12,
                            lineHeight = 6.5.sp,
                        )
                    }
                }
            }

            "read_image" -> {
                // [T-android-read-image-preview] Show the read image as a
                // thumbnail (parity with iOS ToolCapsuleView .readImageTool and
                // with the browser_use case below). Pre-fix read_image fell into
                // the generic `else` branch and rendered only its metadata text
                // line ("[/var/.../x.png | WxH | N bytes]") with no thumbnail.
                // Decode off the main thread (T285 rationale).
                val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
                    initialValue = null,
                    block.imageFilePath,
                ) {
                    val path = block.imageFilePath
                    value = if (path == null) null else withContext(Dispatchers.IO) {
                        // [T-android-tool-thumb-fullres-decode] thumbnail-bounded decode.
                        DisplayBitmapLimits.decodeFileBounded(path, DisplayBitmapLimits.MAX_THUMBNAIL_EDGE_PX)
                    }
                }
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Read image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            toolIconFor(block.toolName),
                            contentDescription = null,
                            tint = toolAccent.copy(alpha = 0.6f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            "browser_use" -> {
                // Prefer live WebView snapshot while the tool is running; fall back to
                // any saved imageFilePath, then globe icon.
                // T285: decode the saved screenshot off the main thread —
                // BitmapFactory.decodeFile inside `remember {}` was running
                // synchronously on the composition thread, blocking the
                // chat-tap → preview navigation transition for ~150-350ms
                // on multi-MB browser screenshots.
                val liveBitmap = rememberBrowserLiveSnapshot(block)
                val savedBitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
                    initialValue = null,
                    block.imageFilePath,
                    fallbackImagePath,
                ) {
                    // Prefer this block's own screenshot; if absent (read-only
                    // browser actions like get_readable/get_text/fetch/etc.),
                    // borrow the most recent prior browser_use screenshot so
                    // the thumb doesn't degrade to the globe placeholder.
                    val path = block.imageFilePath ?: fallbackImagePath
                    value = if (path == null) null else withContext(Dispatchers.IO) {
                        // [T-android-tool-thumb-fullres-decode] thumbnail-bounded decode.
                        DisplayBitmapLimits.decodeFileBounded(path, DisplayBitmapLimits.MAX_THUMBNAIL_EDGE_PX)
                    }
                }
                val bitmap = liveBitmap ?: savedBitmap
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Browser screenshot",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Language,
                            contentDescription = null,
                            tint = toolAccent.copy(alpha = 0.6f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            // [T-p2-delegate-render] Helper: title + phase, never the raw JSON.
            // [T-android-agent-inner-tool-front] …except while the child is
            // inside a tool, when the tile shows THAT tool exactly as the
            // parent's own tools are shown — the shell output, the diff, the
            // browser page. Port of iOS ToolLiveSheet.swift:2720.
            //
            // Recursing into ToolPreviewThumbnail is what makes every existing
            // renderer apply to the child for free; the depth cap stops a chain
            // of delegations from nesting without bound.
            com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME -> {
                val childId = remember(block.content, block.toolStatus, block.toolTitle) {
                    parseHelperBlock(block).childSessionId
                }
                val inner = if (punchThroughDepth < 1) {
                    rememberChildLiveToolBlock(childId)
                } else {
                    null
                }
                if (inner != null) {
                    ToolPreviewThumbnail(
                        block = inner,
                        toolAccent = toolAccentColor(inner.toolName),
                        onClick = onClick,
                        punchThroughDepth = punchThroughDepth + 1,
                    )
                } else {
                    HelperThumbContent(block)
                }
            }

            else -> {
                // Generic: icon or content preview
                if (block.content.isNotEmpty()) {
                    Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
                        Text(
                            // [T-android-tile-tail-memo] Split the tail only when
                            // content changes, not on every recompose. During a
                            // streaming shell the tile recomposes per delta, and an
                            // unmemoized lines()+takeLast()+joinToString on a growing
                            // string is O(n) each time — a measurable slice of the
                            // per-token allocation churn.
                            text = remember(block.content) { lastLinesOf(block.content, 12) },
                            fontSize = 5.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = toolAccent.copy(alpha = 0.85f),
                            maxLines = 12,
                            lineHeight = 6.5.sp,
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(toolIconFor(block.toolName), contentDescription = null, tint = toolAccent.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // T141: live CPU/MEM HUD ribbon. Sits at the thumbnail's bottom edge,
        // matching the iOS small-overlay parity (ToolLiveSheet.swift:1926):
        // 5sp monospace, white at 80% alpha, on a black-60% background with
        // bottom-rounded corners that line up with the parent thumbnailShape.
        if (isShellTool && isLive) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = 10.dp)
                    .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "${resourceMonitor.formattedCpu()}  ${resourceMonitor.formattedMem(compact = true)}",
                    fontSize = 5.sp,
                    lineHeight = 8.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

// ─── Floating Tool Status Bar (iOS: thumbnail + status capsule + pagination) ─

// [T-android-tool-pager-longpress] ExperimentalFoundationApi is for
// combinedClickable on the pager chevrons.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun FloatingToolStatusBar(
    toolBlocks: List<AssistantBlock>,
    onStop: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onOpenTerminalWithCommand: (String) -> Unit = {},
    // T261: detail open routes through ChatViewModel state so this bar
    // shares one always-mounted sheet with the in-list pills (no more
    // dueling local-remember sheets, no LaunchedEffect(lastIndex) page
    // jumps when a new tool starts mid-view).
    onOpenDetail: (String) -> Unit = {},
) {
    var currentIndex by remember { mutableStateOf(toolBlocks.lastIndex.coerceAtLeast(0)) }
    val lastIndex = toolBlocks.lastIndex
    // [T-android-tool-bar-stuck-on-finished] The follow rule below is keyed on
    // the DISPLAYED block's status, not on lastIndex alone.
    //
    // Keying on lastIndex meant this only ever re-ran when a tool was ADDED.
    // A background sub agent finishes without adding one: writeDelegateBlock
    // rewrites its existing block in place (blocks.map { it.copy(...) }), so
    // the list length is unchanged and the status flip from RUNNING to
    // FAILED/SUCCESS produced no recomposition key change. The bar stayed on
    // the finished agent until the parent happened to start another tool —
    // and with a background delegation that gap is exactly the period the
    // parent is idle waiting for the callback, so it is long and very visible.
    //
    // Including the status makes the flip itself the trigger. Manual paging is
    // preserved: parking on a still-running block keeps its status stable, so
    // the effect does not re-fire and does not yank the view away.
    val currentStatus = toolBlocks.getOrNull(currentIndex)?.toolStatus
    LaunchedEffect(lastIndex, currentStatus) {
        val isCurrentActive = currentStatus == ToolBlockStatus.RUNNING ||
            currentStatus == ToolBlockStatus.STREAMING ||
            currentStatus == ToolBlockStatus.PENDING
        if (!isCurrentActive) {
            // Prefer the newest STILL-ACTIVE tool; only fall back to the last
            // block when nothing is running, which is the idle case where the
            // most recent result is the right thing to show.
            val newestActive = toolBlocks.indexOfLast {
                it.toolStatus == ToolBlockStatus.RUNNING ||
                    it.toolStatus == ToolBlockStatus.STREAMING ||
                    it.toolStatus == ToolBlockStatus.PENDING
            }
            currentIndex = if (newestActive >= 0) newestActive else lastIndex.coerceAtLeast(0)
        }
    }
    val block = toolBlocks.getOrNull(currentIndex) ?: return
    // T261: sheet is hoisted to ChatScreen top-level; this bar only emits
    // open events. The current-displayed block id is what we want shown
    // when the user opens the sheet from this surface (not necessarily
    // the latest tool — the user may have paged via the chevrons).
    val onOpenCurrentDetail: () -> Unit = { onOpenDetail(block.id) }

    val isDone = block.toolStatus == ToolBlockStatus.SUCCESS
    val isFailed = block.toolStatus == ToolBlockStatus.FAILED ||
        block.toolStatus == ToolBlockStatus.TIMEOUT
    val isCancelled = block.toolStatus == ToolBlockStatus.CANCELLED
    val isRunning = block.toolStatus == ToolBlockStatus.RUNNING ||
        block.toolStatus == ToolBlockStatus.STREAMING ||
        block.toolStatus == ToolBlockStatus.PENDING
    val toolAccent = toolAccentColor(block.toolName)
    val previewEnabled = LocalToolPreviewEnabled.current

    // iOS layout: ZStack(alignment: .bottomLeading)
    // Thumbnail (100×65dp) floats ABOVE the status bar (38dp tall)
    val thumbnailWidth = 100.dp
    val thumbnailHeight = 65.dp
    val barHeight = 38.dp
    val thumbnailOverhang = thumbnailHeight - barHeight  // 14dp above bar
    val thumbnailInset = 10.dp
    val barStartPadding = if (previewEnabled) thumbnailWidth + thumbnailInset + 8.dp else 12.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (previewEnabled) thumbnailOverhang else 0.dp),  // reserve space for overhang
    ) {
        // Layer 1: Status bar (bottom layer, fills from bottom)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .align(Alignment.BottomCenter)
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(10.dp), ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.12f))
                .background(ChatColors.inputBg, RoundedCornerShape(10.dp))
                .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp))
                .padding(start = barStartPadding, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status icon
            if (isRunning) {
                // [T-android-decorative-anim-perf] Clock-driven arc, not
                // Material's spinner. A layer boundary alone still left the
                // screen at 87 fps: Material's private InfiniteTransition asks
                // for every frame regardless of who wraps it. This records the
                // arc once and rotates a layer at ~30 fps.
                DecorativeSpinner(
                    color = toolAccent,
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 1.5.dp,
                )
            } else {
                val (icon, tint) = when {
                    isDone -> Icons.Default.CheckCircle to ToolCheckColor
                    isFailed -> Icons.Default.Error to ToolErrorColor
                    isCancelled -> Icons.Default.Close to ToolCancelColor
                    else -> Icons.Default.Build to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
            }

            Spacer(modifier = Modifier.width(6.dp))

            // [T-step-timestamp v2 aa8b1128] Inline HH:mm:ss prefix
            // removed — see ToolDetailSheet header for the new
            // "HH:mm:ss · 3s" display, surfaced only inside the
            // tapped-open detail sheet so the always-visible status bar
            // stays clean.

            // Tool title
            Text(
                text = block.toolTitle.ifEmpty { block.toolName },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = ChatColors.primaryText,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onOpenCurrentDetail() },
                overflow = TextOverflow.Ellipsis,
            )

            // Pagination
            if (toolBlocks.size > 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    Icon(
                        Icons.Default.ChevronLeft,
                        contentDescription = "Previous",
                        tint = if (currentIndex > 0) MaterialTheme.colorScheme.onSurface
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                        modifier = Modifier
                            .size(18.dp)
                            // [T-android-tool-pager-longpress] Long-press jumps
                            // to the first call. A turn can carry 60+ tool
                            // calls, and stepping back one at a time is the
                            // only way to reach the start otherwise. The
                            // enabled guard already blocks both gestures at
                            // index 0, so neither can move out of range.
                            .combinedClickable(
                                enabled = currentIndex > 0,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onLongClick = { currentIndex = 0 },
                                onClick = { if (currentIndex > 0) currentIndex-- },
                            ),
                    )
                    val positionLabel = stringResource(
                        R.string.tool_pager_position,
                        currentIndex + 1,
                        toolBlocks.size,
                    )
                    Text(
                        "${currentIndex + 1}/${toolBlocks.size}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // [T-android-tool-pager-a11y] Say what this counts.
                        // It sits immediately right of the tool TITLE, so when
                        // that title belongs to a sub agent the bare "3/12"
                        // reads as that agent's progress. It is neither — it
                        // pages the whole conversation's tool calls. Sighted
                        // users can infer this by paging; a screen reader
                        // otherwise announces only two numbers.
                        modifier = Modifier.semantics {
                            contentDescription = positionLabel
                        },
                    )
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Next",
                        tint = if (currentIndex < toolBlocks.lastIndex) MaterialTheme.colorScheme.onSurface
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                        modifier = Modifier
                            .size(18.dp)
                            // [T-android-tool-pager-longpress] Long-press jumps
                            // to the last call — the live one while a turn is
                            // still running, which is the position this bar
                            // resets to on its own.
                            .combinedClickable(
                                enabled = currentIndex < toolBlocks.lastIndex,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onLongClick = { currentIndex = toolBlocks.lastIndex },
                                onClick = { if (currentIndex < toolBlocks.lastIndex) currentIndex++ },
                            ),
                    )
                }
            }
            // T170: stop button removed from the floating bar — only the
            // in-list ToolCallPill keeps the red square. Two stop affordances
            // on the same active tool felt redundant on tight screens.
        }

        // Layer 2: Thumbnail floating above the bar (iOS: ZStack overlay)
        if (previewEnabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = thumbnailInset),
            ) {
                // [T-android-browser-preview-thumb] Resolve the previous
                // browser_use screenshot fallback here (toolBlocks is in
                // scope); ToolPreviewThumbnail uses it for browser_use blocks
                // whose action did not produce a screenshot.
                val fallbackImagePath = remember(block.id, toolBlocks) {
                    if (block.toolName != "browser_use" || block.imageFilePath != null) null
                    else {
                        val blockIdx = toolBlocks.indexOfFirst { it.id == block.id }
                        if (blockIdx <= 0) null
                        else (blockIdx - 1 downTo 0).firstNotNullOfOrNull { i ->
                            val prev = toolBlocks[i]
                            if (prev.toolName == "browser_use") prev.imageFilePath else null
                        }
                    }
                }
                ToolPreviewThumbnail(
                    block = block,
                    toolAccent = toolAccent,
                    onClick = { onOpenCurrentDetail() },
                    fallbackImagePath = fallbackImagePath,
                )
            }
        }
    }
}

/**
 * Inline 5-segment picker rendered on the right side of the `/thinking` row.
 * Mirrors iOS `thinkingLevelPicker`: the active level shows a filled pill;
 * the OFF pill uses a muted background, the others use the accent color.
 */
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
