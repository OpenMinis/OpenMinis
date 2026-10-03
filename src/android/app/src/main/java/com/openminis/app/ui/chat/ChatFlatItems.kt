package com.openminis.app.ui.chat

// [T-android-split-chat] Flat-chat-item data model + flatten/merge transforms
// extracted verbatim from ChatScreen.kt: FlatChatItem (sealed), mergeStreamingOverlay,
// buildFlatChatItems. Full import block copied (unused=warnings); all internal.

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
import com.openminis.app.ui.components.MinisTextButton

internal sealed class FlatChatItem {
    abstract val key: String
    abstract val contentType: String

    /**
     * Cheap-equals — see [AssistantText]. User messages are short and don't
     * stream, but during a streaming overlay rebuild we still re-create the
     * entire FlatChatItem list, and LazyColumn calls equals to decide skip.
     * Compare by id + reference identity of the wrapped ChatMessage.
     */
    /**
     * [T-android-candidate-bubble-gap] `precededByUser` is true when the
     * immediately-preceding flat item is also a user bubble (e.g. two
     * candidate / queued messages sent back to back). Consecutive user
     * bubbles have no intervening AssistantHeader row to create visual
     * separation, and the LazyColumn's `spacedBy(2.dp)` alone is too tight
     * — the two bubbles read as one. When set, UserMessageBubble adds extra
     * top padding so the pair is clearly two distinct messages.
     */
    class UserBubble(
        val message: ChatMessage,
        val precededByUser: Boolean = false,
    ) : FlatChatItem() {
        override val key = "user:${message.id}"
        override val contentType = "user"
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UserBubble) return false
            // ChatMessage is a data class; reuse its equals (cheap for user
            // bubbles which carry short content and small attachment lists).
            return message == other.message && precededByUser == other.precededByUser
        }
        override fun hashCode(): Int = message.hashCode() * 31 + precededByUser.hashCode()
    }

    data class AssistantHeader(val messageId: String) : FlatChatItem() {
        override val key = "header:$messageId"
        override val contentType = "header"
    }

    /**
     * [T-p2-agent-callback] An <agent_callback> user-role message rendered as
     * a compact callback card rather than a right-aligned user bubble. Only
     * emitted when the transcript has NO delegate block for that child
     * session (a scheduled child-of-current run); when the block exists it
     * already carries the final result in place and the callback is hidden.
     */
    /**
     * [T-android-scheduled-task-card] A fired scheduled task. Carries the
     * parsed marker so the card can show the next fire time and offer to
     * cancel, neither of which the raw message text can express.
     */
    class ScheduledTaskCardItem(
        val message: ChatMessage,
        val marker: com.openminis.app.scheduled.ScheduledTaskMarker,
    ) : FlatChatItem() {
        override val key = "scheduled:${message.id}"
        override val contentType = "scheduled"
        override fun equals(other: Any?): Boolean =
            this === other || (other is ScheduledTaskCardItem && message == other.message)
        override fun hashCode(): Int = message.hashCode()
    }

    class AgentCallbackCard(
        val message: ChatMessage,
        val callback: com.openminis.app.agent.jobs.AgentCallback,
    ) : FlatChatItem() {
        override val key = "callback:${message.id}"
        override val contentType = "callback"
        override fun equals(other: Any?): Boolean =
            this === other || (other is AgentCallbackCard && message == other.message)
        override fun hashCode(): Int = message.hashCode()
    }

    /**
     * Equality on this class previously compared every field including
     * `messageMarkdown` — a CONCATENATED markdown of the entire parent
     * assistant message — char-by-char. During streaming, LazyColumn called
     * the autogenerated equals to decide stable-skip per item, and a typical
     * 50-fragment message with 1 KB per fragment ate ~3.7 seconds of main
     * thread (profile #3, `String.charAt` at 91.8% exclusive). The hand-
     * rolled equals below compares cheap stable identity (key fields + the
     * String *reference* of large bodies) instead, falling back to length
     * if the references differ — never a full char-by-char walk.
     */
    class AssistantText(
        val messageId: String,
        val block: AssistantBlock,
        val isStreaming: Boolean,
        /** Joined raw markdown of the parent message, used by the selection toolbar's Copy Markdown action. */
        val messageMarkdown: String,
    ) : FlatChatItem() {
        override val key = "text:$messageId:${block.id}"
        override val contentType = "text"
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AssistantText) return false
            return messageId == other.messageId &&
                block === other.block &&
                isStreaming == other.isStreaming &&
                messageMarkdown.length == other.messageMarkdown.length
        }
        override fun hashCode(): Int {
            var h = messageId.hashCode()
            h = h * 31 + System.identityHashCode(block)
            h = h * 31 + isStreaming.hashCode()
            h = h * 31 + messageMarkdown.length
            return h
        }
    }

    /**
     * One rendered markdown sub-block (paragraph, code block, list, …) of an
     * AssistantText. Pattern A from the streaming-markdown research: each
     * block is its own LazyColumn item so completed blocks are frozen by
     * LazyList's per-item anchor and only the trailing "live" block
     * re-parses on every chunk. Replaces the previous "whole AssistantText
     * is a single LazyColumn item containing an internal Column of blocks"
     * design which caused the user's scroll position to drift mid-stream.
     */
    /**
     * See [AssistantText] for the rationale behind the hand-rolled equals.
     * `rawText` and `messageMarkdown` are both potentially long; we compare
     * by length (cheap proxy for "has content grown") and identity instead
     * of char-by-char.
     */
    class AssistantMarkdownBlock(
        val messageId: String,
        val parentBlockId: String,
        val rawText: String,
        val blockIndex: Int,
        val isLastBlockOfMessage: Boolean,
        val messageIsStreaming: Boolean,
        /** Joined raw markdown of the parent message, used by Copy Markdown. */
        val messageMarkdown: String,
    ) : FlatChatItem() {
        override val key = "mdblock:$messageId:$parentBlockId:$blockIndex"
        override val contentType = "mdblock"
        /** True when this fragment is the streaming tail of a live message. */
        val isStreaming: Boolean get() = messageIsStreaming && isLastBlockOfMessage
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AssistantMarkdownBlock) return false
            return messageId == other.messageId &&
                parentBlockId == other.parentBlockId &&
                blockIndex == other.blockIndex &&
                isLastBlockOfMessage == other.isLastBlockOfMessage &&
                messageIsStreaming == other.messageIsStreaming &&
                rawText.length == other.rawText.length &&
                messageMarkdown.length == other.messageMarkdown.length
        }
        override fun hashCode(): Int {
            var h = messageId.hashCode()
            h = h * 31 + parentBlockId.hashCode()
            h = h * 31 + blockIndex
            h = h * 31 + isLastBlockOfMessage.hashCode()
            h = h * 31 + messageIsStreaming.hashCode()
            h = h * 31 + rawText.length
            h = h * 31 + messageMarkdown.length
            return h
        }
    }

    data class AssistantThinking(
        val messageId: String,
        val block: AssistantBlock,
        val isLast: Boolean,
        val messageIsStreaming: Boolean,
        // T300: thinking level captured at the message's creation. Null
        // for assistant messages restored from DB (legacy / pre-T300) —
        // the renderer falls back to the chat's current level.
        val messageThinkingLevel: com.openminis.app.data.model.ThinkingLevel? = null,
        // [T-android-thinking-auto-collapse] True when this thinking block
        // is the LAST block of any kind in the message (including text /
        // tool_use), not merely the last thinking block. Drives the
        // auto-collapse `isStreaming` signal so a thinking block flips to
        // collapsed the moment a subsequent text or tool block arrives —
        // mirrors iOS ThinkingBlockView, which only sees `isStreaming=true`
        // while it really is the trailing block. Defaults false so DB-
        // restored / legacy items render collapsed (the pre-change
        // behaviour for non-trailing thinking).
        val isLastBlockOverall: Boolean = false,
    ) : FlatChatItem() {
        override val key = "thinking:$messageId:${block.id}"
        override val contentType = "thinking"
    }

    data class AssistantToolUse(
        val messageId: String,
        val block: AssistantBlock,
        val allToolBlocks: List<AssistantBlock>,
        /** True if this is the last cancelled tool in its message — only one Retry button per message. */
        val isLastCancelled: Boolean = false,
    ) : FlatChatItem() {
        override val key = "tool:$messageId:${block.id}"
        override val contentType = "tool"
    }

    data class AssistantInfo(
        val messageId: String,
        val block: AssistantBlock,
    ) : FlatChatItem() {
        override val key = "info:$messageId:${block.id}"
        override val contentType = "info"
    }

    data class AssistantTyping(val messageId: String) : FlatChatItem() {
        override val key = "typing:$messageId"
        override val contentType = "typing"
    }

    data class AssistantError(val messageId: String, val error: String) : FlatChatItem() {
        override val key = "error:$messageId"
        override val contentType = "error"
    }

    /**
     * [T-android-usage-capsule-time] Token usage + finish time for a completed
     * assistant turn. Emitted last so the flat path ends a turn exactly the way
     * AssistantMessageView does, keeping the two renderers in step.
     */
    data class AssistantUsage(
        val messageId: String,
        val usage: ChatTokenUsage,
        val completedAt: Long?,
    ) : FlatChatItem() {
        override val key = "usage:$messageId"
        override val contentType = "usage"
    }

    /**
     * See [AssistantText] — same cheap-equals rationale.
     */
    class AssistantLegacyContent(
        val messageId: String,
        val content: String,
        val isStreaming: Boolean,
        /** Same as content here (no separate text-block markdown for legacy rows). */
        val messageMarkdown: String = content,
    ) : FlatChatItem() {
        override val key = "legacy:$messageId"
        override val contentType = "legacy"
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AssistantLegacyContent) return false
            return messageId == other.messageId &&
                isStreaming == other.isStreaming &&
                content.length == other.content.length &&
                messageMarkdown.length == other.messageMarkdown.length
        }
        override fun hashCode(): Int {
            var h = messageId.hashCode()
            h = h * 31 + isStreaming.hashCode()
            h = h * 31 + content.length
            h = h * 31 + messageMarkdown.length
            return h
        }
    }
}

/**
 * T-streaming-side-channel: overlay any active [StreamingDelta]s on top of
 * the canonical [messages] list, producing the snapshot
 * [buildFlatChatItems] should fold over. The original [messages] list is
 * never mutated; affected entries are replaced via `copy()` so downstream
 * keying / equality stays correct. When [streaming] is empty the input is
 * returned as-is to skip the per-element walk on idle frames.
 */
internal fun mergeStreamingOverlay(
    messages: List<ChatMessage>,
    streaming: Map<String, StreamingDelta>,
): List<ChatMessage> {
    if (streaming.isEmpty()) return messages
    return messages.map { m ->
        val delta = streaming[m.id] ?: return@map m
        m.copy(
            content = delta.content,
            isStreaming = true,
            toolBlocks = delta.toolBlocks,
            isAwaitingModelResponse = delta.isAwaitingModelResponse,
        )
    }
}

/**
 * [T-android-stream-overlay-subset] The streaming-overlaid messages ONLY —
 * every message whose id is in [streaming], with the live delta applied, in
 * their original order. Nothing else is copied.
 *
 * Why this exists. [mergeStreamingOverlay] rebuilds the ENTIRE message list on
 * every streaming emission: `messages.map { it.copy() }`. On a 577-message
 * session with a token delta every ~50 ms that allocated a fresh 577-element
 * list of ChatMessage copies dozens of times a second — measured on a Pixel 6
 * as ~74 MB/s of churn, GC freeing 244 MB every 3.3 s, and the DefaultDispatch
 * + ReferenceQueueD + HeapTaskDaemon trio pinned near 200% of a core while a
 * live shell tile was on screen.
 *
 * Most consumers of the merge do not need the untouched prefix at all — they
 * immediately reduce to `lastOrNull { role == "assistant" }` or "does ANY
 * assistant block have a live status". For those, the streaming subset is
 * sufficient and is at most a handful of messages regardless of history size,
 * so the per-delta allocation stops scaling with the conversation.
 *
 * The full [mergeStreamingOverlay] is kept for the one caller that genuinely
 * rebuilds the flat item list (which needs every message, and already runs off
 * the main thread on a split suffix).
 */
internal fun streamingOverlaySubset(
    messages: List<ChatMessage>,
    streaming: Map<String, StreamingDelta>,
): List<ChatMessage> {
    if (streaming.isEmpty()) return emptyList()
    val out = ArrayList<ChatMessage>(streaming.size)
    for (m in messages) {
        val delta = streaming[m.id] ?: continue
        out.add(
            m.copy(
                content = delta.content,
                isStreaming = true,
                toolBlocks = delta.toolBlocks,
                isAwaitingModelResponse = delta.isAwaitingModelResponse,
            )
        )
    }
    return out
}

/**
 * [T-android-fab-up-target] True when this message renders as a real user
 * bubble — something the user actually typed and can navigate back to.
 *
 * `role == "user"` is NOT that test. Two kinds of system-written message also
 * carry the user role and are rendered as their own cards instead of a bubble:
 * a scheduled task's prompt ([ScheduledTaskCardItem]) and a sub agent callback
 * ([AgentCallbackCard]). Anything walking "the user's turns" has to agree with
 * what [buildFlatChatItems] actually emitted, or it will aim at a turn that has
 * no `user:` row to land on.
 *
 * Kept beside the branch it mirrors so the two cannot drift apart.
 */
internal fun ChatMessage.rendersAsUserBubble(): Boolean {
    if (role != "user") return false
    if (com.openminis.app.scheduled.ScheduledTaskMarker.parse(content) != null) return false
    if (com.openminis.app.agent.jobs.AgentCallback.isCallbackText(content)) return false
    return true
}

internal fun buildFlatChatItems(
    messages: List<ChatMessage>,
    // [T-android-perf-logging] Optional — when supplied, emit a progress
    // breadcrumb every 100 messages so a low-memory repro shows which batch
    // drives the heap up. Null (default) skips all progress logging, so the
    // hot per-token streaming rebuild path stays log-free.
    sessionId: String? = null,
    // [T-android-stream-pipeline-incremental] Build rows only for
    // messages[fromIndex, size). Neighbor lookbacks (precededByUser /
    // isResumeContinuation) still read the FULL list, so a suffix built with
    // fromIndex > 0 is row-for-row identical to the same span of a full
    // build — rows never depend on later messages, only earlier ones.
    fromIndex: Int = 0,
    // Dedupe continuity for split builds: pass the key set of the frozen
    // prefix so the defensive key-collision suffixing behaves exactly as a
    // single full build would.
    seedKeys: Set<String> = emptySet(),
): List<FlatChatItem> {
    val out = mutableListOf<FlatChatItem>()
    val usedKeys = if (seedKeys.isEmpty()) mutableSetOf() else seedKeys.toMutableSet()
    fun dedupe(item: FlatChatItem): FlatChatItem {
        // Defensive: duplicated keys crash LazyColumn. If any slip through, suffix
        // a counter until unique. This should never fire if upstream dedup is correct.
        if (usedKeys.add(item.key)) return item
        var n = 2
        while (!usedKeys.add("${item.key}#$n")) n++
        return when (item) {
            is FlatChatItem.UserBubble -> FlatChatItem.UserBubble(item.message.copy(id = "${item.message.id}#$n"), item.precededByUser)
            is FlatChatItem.AgentCallbackCard -> FlatChatItem.AgentCallbackCard(item.message.copy(id = "${item.message.id}#$n"), item.callback)
            is FlatChatItem.ScheduledTaskCardItem -> FlatChatItem.ScheduledTaskCardItem(item.message.copy(id = "${item.message.id}#$n"), item.marker)
            is FlatChatItem.AssistantHeader -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantText -> FlatChatItem.AssistantText(
                messageId = "${item.messageId}#$n",
                block = item.block,
                isStreaming = item.isStreaming,
                messageMarkdown = item.messageMarkdown,
            )
            is FlatChatItem.AssistantMarkdownBlock -> FlatChatItem.AssistantMarkdownBlock(
                messageId = "${item.messageId}#$n",
                parentBlockId = item.parentBlockId,
                rawText = item.rawText,
                blockIndex = item.blockIndex,
                isLastBlockOfMessage = item.isLastBlockOfMessage,
                messageIsStreaming = item.messageIsStreaming,
                messageMarkdown = item.messageMarkdown,
            )
            is FlatChatItem.AssistantThinking -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantToolUse -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantInfo -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantTyping -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantError -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantUsage -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantLegacyContent -> FlatChatItem.AssistantLegacyContent(
                messageId = "${item.messageId}#$n",
                content = item.content,
                isStreaming = item.isStreaming,
                messageMarkdown = item.messageMarkdown,
            )
        }
    }
    for (idx in fromIndex until messages.size) {
        val message = messages[idx]
        // [T-android-perf-logging] Per-100-message progress breadcrumb.
        // `out.size` is the running row count, so a sudden jump between two
        // progress lines localizes the heavy batch. Only fires on the
        // full-build path (sessionId != null), never per streaming token.
        if (sessionId != null && idx > 0 && idx % 100 == 0) {
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "buildFlatChatItems.progress",
                "msgIdx=$idx of=${messages.size} rowsSoFar=${out.size}",
            )
        }
        if (message.role == "user") {
            // [T-android-scheduled-task-card] A task's own prompt, not the
            // user's typing. Checked before the callback branch: the two
            // markers are disjoint, and this keeps the plain-bubble fallthrough
            // in one place.
            val sched = com.openminis.app.scheduled.ScheduledTaskMarker.parse(message.content)
            if (sched != null) {
                out.add(dedupe(FlatChatItem.ScheduledTaskCardItem(message, sched)))
                continue
            }
            // [T-p2-agent-callback] A system-written agent callback renders as
            // its own card — every one of them, progress and final alike.
            //
            // [T-android-subagent-callback-visible] This used to be deduped:
            // a callback was hidden whenever the transcript still held the
            // delegate block for that child, since the block already shows
            // status, tier, elapsed and opens the same detail. Defensible on
            // its own terms, but it left the parent transcript with no record
            // that the sub agent reported back AT ALL — a block quietly
            // changing state is not the same as seeing the hand-off happen
            // where it happened, in order, among the other turns. With the
            // block present (the normal case for a delegate_task run) neither
            // the progress reports nor the final result had any entry in the
            // conversation, which is the report: "子任务汇报消息没有像 iOS
            // 一样落一个 UI 入口在消息中". iOS dropped the same dedup in
            // 8417eb68f (progress) and 7985d161e (final); this is the port.
            // The child-id scan that fed the dedup walked every message and
            // block on each flatten and nothing reads it now, so it goes too.
            val callback = if (com.openminis.app.agent.jobs.AgentCallback.isCallbackText(message.content))
                com.openminis.app.agent.jobs.AgentCallback.parse(message.content) else null
            if (callback != null) {
                out.add(dedupe(FlatChatItem.AgentCallbackCard(message, callback)))
                continue
            }
            // [T-android-candidate-bubble-gap] Flag when the previous message
            // is also a user message so the bubble can add a separating top
            // gap — back-to-back candidate / queued sends otherwise have no
            // AssistantHeader between them and visually merge.
            val prevIsUser = idx > 0 && messages[idx - 1].role == "user"
            out.add(dedupe(FlatChatItem.UserBubble(message, precededByUser = prevIsUser)))
            continue
        }
        // System messages (slash-command notices, compact divider, etc.) render
        // as horizontal-divider rows — no "Minis" attribution, no card. Skip
        // the assistant header so each info block stands on its own. Mirrors
        // iOS systemDividerRow / compactDividerRow.
        val isSystem = message.role == "system"
        val joinedMarkdown = run {
            val parts = message.toolBlocks
                .filter { it.kind == "text" && it.content.isNotEmpty() }
                .joinToString("\n\n") { it.content }
            if (parts.isNotEmpty()) parts else message.content
        }
        // T83: when Resume creates a fresh assistant bubble after the user
        // stopped a streaming turn, the previous (cancelled) assistant
        // message is right before this one in the list. Visually they should
        // read as one continuous turn — suppress the duplicate "Minis"
        // header. Skip system rows when looking back since they render as
        // dividers, not as separate speaker turns. iOS achieves this by
        // reusing the existing ChatMessage in runAgentLoop(resumingAt:);
        // we reach the same end-result at the render layer.
        // [T-assistant-header-dedup] An agent callback is written by the
        // system, not typed by the person, so it does not restart the
        // speaker sequence: the reply to it sits under the (updated) block
        // or the callback card without a second avatar header.
        val prevNonSystem = (idx - 1 downTo 0).asSequence()
            .map { messages[it] }
            .firstOrNull { it.role != "system" && !com.openminis.app.agent.jobs.AgentCallback.isCallbackText(it.content) }
        val isResumeContinuation = prevNonSystem?.role == "assistant"
        if (!isSystem && !isResumeContinuation) {
            out.add(dedupe(FlatChatItem.AssistantHeader(message.id)))
        }

        val blocks = message.toolBlocks
        val toolPillBlocks = blocks.filter { it.kind == "tool_use" }
        val lastThinkingId = blocks.lastOrNull { it.kind == "thinking" }?.id
        // [T-android-thinking-auto-collapse] Id of the last block of ANY
        // kind — used to flip the trailing thinking block's auto-collapse
        // signal the moment a subsequent text / tool_use arrives. See
        // AssistantThinking.isLastBlockOverall KDoc.
        val lastBlockId = blocks.lastOrNull()?.id
        val lastTextIdx = blocks.indexOfLast { it.kind == "text" }
        val hasAnyTextBlock = lastTextIdx >= 0
        // Only the last cancelled tool_use in the message gets the Retry button —
        // retryLast() re-runs the whole turn, so one button is enough.
        val lastCancelledToolId = blocks.lastOrNull { it.kind == "tool_use" && it.toolStatus == ToolBlockStatus.CANCELLED }?.id

        blocks.forEachIndexed { index, block ->
            when (block.kind) {
                "text" -> {
                    if (block.content.isNotEmpty()) {
                        val isLastText = index == lastTextIdx
                        // Pattern A: split this text block's content into
                        // independent markdown fragments so each becomes its
                        // own LazyColumn item. Frozen prefix fragments are
                        // anchored separately by LazyList; only the trailing
                        // live fragment can change height during streaming.
                        //
                        // [T-android-defensive-fragment-merge] For a FROZEN
                        // (non-streaming) message, coalesce adjacent
                        // plain-text fragments so a long reply produces a
                        // handful of rows instead of dozens — cuts cold-open
                        // full-build row count ~8x and eases GC pressure on
                        // low-memory devices. The live streaming tail message
                        // keeps fine-grained fragments so only the trailing
                        // paragraph re-parses per token (Pattern A jank
                        // optimization preserved). Code fences stay standalone
                        // either way.
                        // [T-android-largecontent-guard-position] Apply the
                        // 32K large-content guard HERE, on the whole block,
                        // before it is split.
                        //
                        // The guard (LargeContentGuard / shouldCollapse) runs
                        // per FlatChatItem, but for assistant text the items
                        // are post-split fragments capped at 2000 chars by
                        // coalesceMarkdownFragments — so for ordinary prose
                        // nothing reaching it was ever large enough and the
                        // protection never engaged, no matter how big the
                        // message. (It still fired for one shape: a single
                        // >32K paragraph with no blank line, which coalesce
                        // keeps whole. See LargeContentGuardThresholdTest.)
                        //
                        // Emitting ONE un-split item for an oversized frozen
                        // block is what lets the guard see the real size and
                        // show its collapsed badge, and it also skips the
                        // split/coalesce allocation storm over a 200KB string
                        // that the user has not asked to expand.
                        //
                        // Frozen only: collapsing mid-stream would flicker as
                        // the buffer grows past the threshold, which is the
                        // same reason shouldCollapse() ignores streaming.
                        val blockIsStreaming = message.isStreaming && isLastText
                        if (!blockIsStreaming &&
                            block.content.length > LARGE_MESSAGE_THRESHOLD_CHARS
                        ) {
                            out.add(dedupe(FlatChatItem.AssistantMarkdownBlock(
                                messageId = message.id,
                                parentBlockId = block.id,
                                rawText = block.content,
                                blockIndex = 0,
                                isLastBlockOfMessage = isLastText,
                                messageIsStreaming = false,
                                messageMarkdown = joinedMarkdown,
                            )))
                            return@forEachIndexed
                        }
                        val rawFragments = splitMarkdownIntoBlockTexts(block.content)
                        // [T-android-stream-end-reflow-flicker-v18] Preserve
                        // per-fragment FlatChatItem keys across the
                        // streaming→idle boundary. Previously the trailing
                        // text block kept rawFragments only while
                        // `message.isStreaming==true`; the moment it flipped
                        // false the fragments coalesced into fewer rows, all
                        // mdblock:msgId:parentBlockId:N keys for N >= K
                        // suddenly vanished from the flatItems list. That
                        // wipe-and-rebuild was the "整个页面像被重刷" the
                        // user reported — LazyColumn lost every key it was
                        // using to anchor the viewport, fell back to numeric
                        // firstVisibleItemIndex, and parked the viewport on
                        // whatever row happened to take that numeric slot
                        // (often the previous assistant message).
                        //
                        // Fix: keep the live (== last in the list) text block
                        // on rawFragments regardless of isStreaming. The
                        // boundary that actually warrants coalesce is "a
                        // NEWER message exists below this one" — i.e. a
                        // subsequent user turn pushed this assistant turn
                        // into history. Until then, the same key set the
                        // user was scrolled into stays valid.
                        // [T-android-flatitems-sublist-cme] Index-based scan
                        // instead of messages.subList(idx+1, size).all{} — a
                        // subList is a live view sharing the parent's modCount,
                        // which threw ConcurrentModificationException when the
                        // backing list changed under it. A plain index loop
                        // touches no view.
                        val isLastAssistantTurn = idx == messages.lastIndex ||
                            (idx + 1 until messages.size).all { messages[it].role != "assistant" }
                        val fragments = if (isLastText && isLastAssistantTurn) {
                            rawFragments
                        } else {
                            coalesceMarkdownFragments(rawFragments)
                        }
                        if (fragments.isEmpty()) {
                            // Defensive: if the splitter returns nothing for
                            // a non-empty input (shouldn't happen), fall back
                            // to a single fragment so content isn't dropped.
                            out.add(dedupe(FlatChatItem.AssistantMarkdownBlock(
                                messageId = message.id,
                                parentBlockId = block.id,
                                rawText = block.content,
                                blockIndex = 0,
                                isLastBlockOfMessage = isLastText && message.isStreaming,
                                messageIsStreaming = message.isStreaming && isLastText,
                                messageMarkdown = joinedMarkdown,
                            )))
                        } else {
                            fragments.forEachIndexed { fragIdx, raw ->
                                val isLastFragOfText = fragIdx == fragments.lastIndex
                                out.add(dedupe(FlatChatItem.AssistantMarkdownBlock(
                                    messageId = message.id,
                                    parentBlockId = block.id,
                                    rawText = raw,
                                    blockIndex = fragIdx,
                                    isLastBlockOfMessage = isLastText && isLastFragOfText,
                                    messageIsStreaming = message.isStreaming && isLastText,
                                    messageMarkdown = joinedMarkdown,
                                )))
                            }
                        }
                    }
                }
                "thinking" -> out.add(dedupe(FlatChatItem.AssistantThinking(
                    messageId = message.id,
                    block = block,
                    isLast = block.id == lastThinkingId,
                    messageIsStreaming = message.isStreaming,
                    messageThinkingLevel = message.thinkingLevel,
                    isLastBlockOverall = block.id == lastBlockId,
                )))
                "info" -> out.add(dedupe(FlatChatItem.AssistantInfo(
                    messageId = message.id,
                    block = block,
                )))
                else -> out.add(dedupe(FlatChatItem.AssistantToolUse(
                    messageId = message.id,
                    block = block,
                    allToolBlocks = toolPillBlocks,
                    isLastCancelled = block.id == lastCancelledToolId,
                )))
            }
        }

        // Typing indicator: show while streaming and either (a) no visible
        // content has arrived yet, or (b) we're in the network gap waiting
        // on the model's next response (e.g. after tool results were sent
        // back). Mirrors iOS: `isActiveMessage && (!hasVisibleContent || isAwaitingModelResponse)`.
        //
        // [T-android-typing-hidden-thinking] "Visible" must mean WILL RENDER,
        // not merely "exists". Two blocks used to count as content while
        // drawing nothing, killing the indicator into a blank gap ("thinking
        // 还没等到内容出现就消失了"):
        //  - a "thinking" block when the message's thinking level is OFF —
        //    forced-reasoning models (MiniMax M2, Grok…) still stream
        //    reasoning_content, but ChatScreen suppresses the row when the
        //    snapshot level is disabled (T300), so the user saw nothing;
        //  - a freshly-created "text" block whose content is still empty.
        // A null thinkingLevel snapshot falls back to the chat's CURRENT
        // level in the renderer, which this pure builder can't read — treat
        // it as visible (legacy messages; conservative, old behavior).
        val hasVisibleContent = message.content.isNotEmpty() || blocks.any {
            when (it.kind) {
                "info" -> false
                "thinking" -> message.thinkingLevel?.isEnabled ?: true
                "text" -> it.content.isNotEmpty()
                else -> true // tool_use pills render immediately
            }
        }
        if (message.isStreaming && (!hasVisibleContent || message.isAwaitingModelResponse)) {
            out.add(dedupe(FlatChatItem.AssistantTyping(message.id)))
        }

        // Legacy fallback: pre-migration sessions stored all text in message.content
        // with no text-kind blocks. Render it after blocks in that case only.
        if (!hasAnyTextBlock && message.content.isNotEmpty()) {
            out.add(dedupe(FlatChatItem.AssistantLegacyContent(
                messageId = message.id,
                content = message.content,
                isStreaming = message.isStreaming,
            )))
        }

        // Inline error banner
        message.error?.let {
            out.add(dedupe(FlatChatItem.AssistantError(message.id, it)))
        }

        // [T-android-usage-capsule-time] Usage capsule closes the turn. Gated on
        // !isStreaming as well as on usage being present: a merged bubble whose
        // LAST row is still streaming carries the PREVIOUS turn's usage, and
        // showing that mid-stream would report a finish time for a turn that has
        // not finished.
        message.tokenUsage?.let { u ->
            if (!message.isStreaming) {
                out.add(dedupe(FlatChatItem.AssistantUsage(message.id, u, message.completedAt)))
            }
        }
    }
    return out
}
