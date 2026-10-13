package com.openminis.app.ui.chat

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
import androidx.compose.animation.Crossfade
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.automirrored.filled.Article
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInFull
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
import androidx.compose.material3.ripple
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import com.openminis.app.ui.settings.SettingsSwitch
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.security.InterceptFeedback
import com.openminis.app.security.PermissionMode
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.logging.AppLogger
import com.openminis.app.text.BoundedText
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import com.openminis.app.ui.components.MinisCenterTopBar
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.openminis.app.ui.markdown.LocalCodeBlockRunState
import com.openminis.app.ui.markdown.LocalMarkdownCodeRunner
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Forum
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

// iOS ChatColors equivalent
internal val ToolCheckColor = Color(0xFF34C759) // iOS .green
internal val ToolErrorColor = Color(0xFFFF3B30) // iOS .red
internal val ToolCancelColor = Color(0xFFFFCC00) // iOS .yellow
// Memory tool accent — matches iOS `.pink` on SF Symbols.
internal val ToolMemoryAccent = Color(0xFFFF2D55)
// Sparkle gradient colors (iOS uses linear gradient)
internal val SparkleColor1 = Color(0xFFB8B096) // rgb(0.72, 0.69, 0.59)
internal val SparkleColor2 = Color(0xFF99998C) // rgb(0.6, 0.6, 0.55)

// T129: cap photo/video and file pickers at 50 items per launch. Above this
// count Android's PickMultipleVisualMedia silently truncates anyway, but our
// document picker has no native cap — so we apply the same limit on both
// sides and toast the user when their selection is trimmed. Mirrors iOS
// PHPickerConfiguration.selectionLimit = 50.
private const val ATTACHMENT_PICK_LIMIT = 50

/**
 * [T-android-send-no-autoscroll-behind-preview] Follow-grace window after a
 * user message append: within it the reserve-change pin bypasses the
 * isNearBottom gate (send intent is unambiguous; the freshly-inserted rows
 * make the live anchor transiently read "not at bottom").
 */
private const val SEND_FOLLOW_GRACE_MS = 2_000L


/**
 * [T-slash-picker-fixed-height port from iOS 73f1b94a] Locked popup
 * height for the slash and mention pickers: up to 4 rows are visible,
 * any overflow scrolls. Computed as `rowHeight * visibleRows + 8dp`.
 * [T-android-slash-menu-density] Rows were tightened (vertical padding
 * 10→7dp) so rowHeight ≈ 42dp covers a 14sp title + 11sp subtitle + 7dp
 * vertical padding; 42*4 + 8 ≈ 176dp. Keeps 4 rows visible with no extra
 * blank space at the bottom.
 *
 * This is the height at the DEFAULT font scale only — see
 * [slashPickerHeight], which is what the pickers actually use.
 */
private val SLASH_PICKER_FIXED_HEIGHT: Dp = 176.dp

/**
 * Upper bound for [slashPickerHeight]. At the largest accessibility font
 * sizes an honestly-scaled 4-row band would grow past 300dp and the popup
 * would cover most of a small screen, pushing the composer out of reach.
 * Past this point the band stops growing and the rows scroll instead —
 * which is the same fallback overflow already uses.
 */
private val SLASH_PICKER_MAX_HEIGHT: Dp = 280.dp

/**
 * [T-android-slash-picker-fontscale] The picker band's height, scaled with
 * the system font size.
 *
 * Why this is not a constant any more: the row's text is sized in `sp`, so
 * at fontScale > 1 a 14sp title and 11sp subtitle render TALLER than the
 * 14dp+11dp the fixed 176dp band was budgeted against, while the band
 * itself — being `dp` — does not move, so the last visible row gets
 * clipped mid-glyph. Reported on a Xiaomi 23127PN0CC (Android 16) whose
 * display defaults to a font scale above 1.0.
 *
 * Measured on a Pixel 4a (density 2.75) while fixing this, and worth
 * recording because it contradicts the constant's own arithmetic: the
 * real row pitch is ~61.7dp, not the ~42dp the 176dp figure assumes. So
 * the band only ever showed ~2.8 rows, and the third row's subtitle was
 * already clipped at fontScale 1.0 — font scaling makes an existing bug
 * worse rather than causing it. The 4-row budget is therefore aspirational
 * at every scale; this function keeps the same intent (and the same
 * default-scale pixels) while letting the band grow with the text, and
 * deliberately does NOT re-lock the default-scale height to ~256dp, which
 * would fit 4 real rows but is a visual change nobody asked for.
 *
 * The scaling is applied to the TEXT portion only, because that is the
 * only part that scales: the 7dp vertical padding and the 18dp leading
 * icon are `dp` and stay put. Modelling the row as
 * `max(textHeight, iconHeight) + padding` mirrors the `Row`'s own
 * `CenterVertically` measurement, so the band tracks what is actually
 * drawn instead of a second, independent guess at it.
 *
 * At fontScale 1.0 this returns [SLASH_PICKER_FIXED_HEIGHT] verbatim, so
 * the default-font layout is byte-identical to before — the computed path
 * is only consulted when the user has actually scaled their font up.
 */
/**
 * [T-android-chat-max-content-width] Reading-measure cap for the conversation
 * and the composer on a wide window.
 *
 * 900dp verbatim from iOS `AIChatView.maxContentWidth`, which returns 900 in the
 * regular horizontal size class and nil in compact. The unit maps directly: an
 * iOS point and an Android dp are both 1/160 inch at baseline density, so the
 * two platforms cap at the same physical measure.
 *
 * No explicit compact branch is needed to match "nil when compact": `widthIn`
 * is an upper bound, so on any window narrower than this — every phone, and a
 * tablet's list pane — it is inert and the content still fills the width.
 */
private val CHAT_MAX_CONTENT_WIDTH = 900.dp

@Composable
private fun slashPickerHeight(
    titleSp: TextUnit = 14.sp,
    subtitleSp: TextUnit = 11.sp,
    verticalPadding: Dp = 7.dp,
    iconSize: Dp = 18.dp,
): Dp {
    val fontScale = LocalDensity.current.fontScale
    if (fontScale <= 1f) return SLASH_PICKER_FIXED_HEIGHT

    val density = LocalDensity.current
    return with(density) {
        // Compose applies a ~1.2x line-height multiplier to a bare
        // fontSize; the two Text rows are maxLines=1 so each contributes
        // exactly one line.
        val textHeight = (titleSp.toDp() + subtitleSp.toDp()) * 1.2f
        val rowHeight = maxOf(textHeight, iconSize) + verticalPadding * 2
        (rowHeight * SLASH_PICKER_VISIBLE_ROWS + 8.dp)
            .coerceIn(SLASH_PICKER_FIXED_HEIGHT, SLASH_PICKER_MAX_HEIGHT)
    }
}

/** Rows visible in the picker band before it starts scrolling. */
private const val SLASH_PICKER_VISIBLE_ROWS = 4

// [T-android-tool-autoscroll] Combined signal for the streaming auto-follow
// LaunchedEffect. data class so distinctUntilChanged uses structural equality
// — any field flip propagates a tick. Per-block (id, kind, status, length)
// folded into [blockSig] (FNV-1a 64-bit hash) so a RUNNING→SUCCESS flip on a
// tool block, a new block appearing (id flips), or a kind change all wake the
// collector even when growth/size/awaiting alone would have stayed equal.
private data class ScrollFollowKey(
    val lastIndex: Int,
    val growth: Long,
    val toolBlockCount: Int,
    val awaiting: Boolean,
    val blockSig: Long,
)

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun ChatScreen(
    sessionId: String,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    memoryRepository: MemoryRepository? = null,
    skillRepository: com.openminis.app.data.repository.SkillRepository? = null,
    mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
    onBack: () -> Unit,
    /**
     * [T-android-tablet-split] True when this chat is rendered as the DETAIL
     * pane beside the session list. The back arrow is suppressed then: it means
     * "return to the list", and the list is already on screen, so the arrow has
     * nowhere meaningful to go. SwiftUI's NavigationSplitView omits the
     * detail-column back button for the same reason; ListDetailPaneScaffold
     * does not, so it has to be done by hand.
     */
    isTwoPane: Boolean = false,
    /**
     * [T-android-tablet-sidebar-collapse] Show/hide the session list, or null
     * when there is nothing to toggle.
     *
     * Two-pane frees up the navigation slot (the back arrow means "return to
     * the list", which is pointless while the list is visible), and that slot
     * is exactly where a user looks for the list. So the collapse control
     * takes it over rather than being added somewhere new.
     */
    onToggleSidebar: (() -> Unit)? = null,
    /** Whether the list is currently hidden — decides which way the icon points. */
    sidebarCollapsed: Boolean = false,
    /** [P1-6] Compact-width only: opens the modal session drawer owned by
     *  ChatSplitScaffold (the pane navigator swaps list/detail there, so
     *  switching chats no longer requires popping back to the full list
     *  screen). Null in two-pane — the sidebar toggle already owns this
     *  slot. */
    onOpenSessionDrawer: (() -> Unit)? = null,
    /** [T-new-chat-menu-entry] "New Chat" from the chat "..." menu: caller
     *  navigates to a fresh draft chat (same funnel as the session list's
     *  new-chat button), replacing this chat on the back stack. */
    onNewChat: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    /** [P2-git-panel] "Git 面板" from the chat "..." menu: status/commit/
     *  history/diff for the session workspace. */
    onOpenGitPanel: () -> Unit = {},
    /** Open the in-app terminal with [command] pre-filled at the prompt
     *  (no trailing newline — the user reviews and presses Enter manually).
     *  Wired to the top-right Terminal button on a shell_execute ToolDetailSheet. */
    onOpenTerminalWithCommand: (command: String) -> Unit = {},
    /** "Move to…" capsule (T51): called when the user picks a target session
     *  from MoveToSessionSheet after a share-injected turn. The caller is
     *  responsible for navigating; this screen has already stashed the
     *  pending transfer in [ChatViewModelStore.stashPendingTransfer]. */
    onMoveToSession: (sessionId: String) -> Unit = {},
    onBrowseChatFiles: () -> Unit = {},
    /** T150: open FilePreviewScreen for a non-image attachment in a user bubble. */
    onPreviewAttachment: (com.openminis.app.ui.sandbox.FileItem) -> Unit = {},
    /** [T-android-modelpicker-group-edit] Navigate to the Model Groups
     *  management screen — wired to the "Edit" button on the model picker's
     *  Model Groups section header. */
    onModelGroupsClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Scoped to a process-level per-session ViewModelStore (ChatViewModelStore)
    // so the ViewModel and its viewModelScope survive:
    //   - configuration changes (rotation — NavBackStackEntry still alive)
    //   - leaving the chat screen via popBackStack (NavBackStackEntry destroyed)
    // The VM is released only when the session is deleted (see SessionListViewModel).
    val viewModel: ChatViewModel = viewModel(
        viewModelStoreOwner = ChatViewModelStore.ownerFor(sessionId),
        factory = ChatViewModel.factory(
            sessionId = sessionId,
            chatRepository = chatRepository,
            providerRepository = providerRepository,
            appContext = context.applicationContext,
            memoryRepository = memoryRepository,
            skillRepository = skillRepository,
            mcpRepository = mcpRepository,
        ),
    )
    // The painted list is one contiguous suffix of the session, not a head+tail
    // splice. Opening starts on the newest rows. Older pages prepend. Rows after
    // the loaded cursor are attached automatically, so the tail is not left
    // behind a second control.
    // [T-android-tts-scene-announcements] Bind the announcement sink for as
    // long as this screen is composed. The engine binding lives in
    // ReadAloudPlayer/TextToSpeechManager (UI layer); the ViewModel only sees
    // the (String) -> Unit sink and never the engine itself. Cleared on
    // dispose so a popped screen stops announcing.
    val announcementTts = remember {
        com.openminis.app.speech.TextToSpeechManager()
    }
    DisposableEffect(viewModel, announcementTts) {
        // Engine binding is lazy; speak() before init settles is buffered in
        // the manager's preInitQueue and replayed once the engine is up.
        announcementTts.init(context)
        viewModel.announcementSink = announcementTts::speak
        onDispose {
            viewModel.announcementSink = null
            announcementTts.shutdown()
        }
    }
    val messages by viewModel.uiMessages.collectAsState()
    val hasOlderMessages by viewModel.hasOlderMessages.collectAsState()
    val hasNewerMessages by viewModel.hasNewerMessages.collectAsState()
    val isLoadingHistory by viewModel.isLoadingHistory.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val sessionLoaded by viewModel.sessionLoaded.collectAsState()
    val canResume by viewModel.canResume.collectAsState()
    // [T-android-compact-progress] null when no compaction is running.
    val compactProgress by viewModel.compactProgress.collectAsState()
    val pendingApprovals by viewModel.pendingApprovals.collectAsState()
    val interceptEvents by InterceptFeedback.events.collectAsState()
    // Intercepts are transient notices. Approval requests remain visible
    // until the user decides; only completed denial/rejection notices expire.
    LaunchedEffect(interceptEvents.map { it.id }) {
        interceptEvents.forEach { event ->
            launch {
                val remaining = 8_000L - (System.currentTimeMillis() - event.timestamp)
                if (remaining > 0) kotlinx.coroutines.delay(remaining)
                InterceptFeedback.dismiss(event.id)
            }
        }
    }
    val error by viewModel.error.collectAsState()
    val modelName by viewModel.modelName.collectAsState()
    val sessionTitle by viewModel.sessionTitle.collectAsState()
    val sessionCategory by viewModel.sessionCategory.collectAsState()
    val attachments by viewModel.attachments.collectAsState()
    // [T-android-paste-placeholder] Folded pastes, rendered as chips above the
    // attachment row.
    val pastedTexts by viewModel.pastedTexts.collectAsState()
    val availableGroups by viewModel.availableGroups.collectAsState()
    val selectedGroupId by viewModel.selectedGroupId.collectAsState()
    val showBrowserSheet by viewModel.showBrowserSheet.collectAsState()
    val showMemorySheet by viewModel.showMemorySheet.collectAsState()
    val memoryToolRecords by viewModel.memoryToolRecords.collectAsState()
    val selectedGroupName by viewModel.selectedGroupName.collectAsState()
    val providerName by viewModel.providerName.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // [T-generation-run] 崩溃恢复提示：上次进程死亡时本会话有中断的生成。
    LaunchedEffect(sessionId) {
        val broken = com.openminis.app.agent.GenerationRunStore.recentlyAbandonedFor(context, sessionId)
        if (broken.isNotEmpty()) {
            val r = broken.last()
            val whenStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(r.startedAtMs))
            snackbarHostState.showSnackbar(
                "上次有中断的生成（模型 ${r.modelId}，开始于 $whenStr）——中断前的内容已保留在对话中",
            )
        }
    }

    // [T-android-voice-panel] Shared 3-stage RECORD_AUDIO permission flow
    // (system dialog → post-DENY poll → in-app settings gate). Extracted from
    // the mic button's triggerVoiceInput so the inline voice panel can request
    // the same way. Returns true when granted.
    val ensureMicPermissionFlow: suspend () -> Boolean = ensure@{
        val perm = android.Manifest.permission.RECORD_AUDIO
        val hasPerm: () -> Boolean = {
            androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (hasPerm()) return@ensure true
        var result = com.openminis.app.offload.OffloadPermissionManager
            .requestAndroidPermission(listOf(perm))
        if (result == com.openminis.app.offload.OffloadPermissionManager
                .AndroidPermissionResult.DENIED &&
            com.openminis.app.offload.OffloadPermissionManager.pollForPermissionGrant(hasPerm)
        ) {
            result = com.openminis.app.offload.OffloadPermissionManager
                .AndroidPermissionResult.GRANTED
        }
        if (result == com.openminis.app.offload.OffloadPermissionManager
                .AndroidPermissionResult.DENIED
        ) {
            result = com.openminis.app.offload.OffloadPermissionManager.requestSettingsGate(
                com.openminis.app.offload.OffloadPermissionManager.SettingsGateRequest(
                    id = perm,
                    title = context.getString(R.string.mic_permission_title),
                    message = context.getString(R.string.mic_permission_message),
                    settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    requiresPackageUri = true,
                    positiveLabel = context.getString(R.string.mic_permission_open_settings),
                    negativeLabel = context.getString(R.string.mic_permission_cancel),
                ),
                check = hasPerm,
            )
        }
        result == com.openminis.app.offload.OffloadPermissionManager.AndroidPermissionResult.GRANTED
    }
    // Hoisted to ChatViewModel so it survives ChatScreen disposal/recomposition
    // across forward navigation (file preview, env vars, etc.); see
    // ChatViewModel.listState for the why.
    val listState = viewModel.listState
    // T325: draft persists on the VM so navigation (e.g. push EnvVars and
    // pop back) doesn't wipe what the user has typed. Mirrors iOS
    // `AIChatView` which binds the composer against `vm.inputText`.
    val inputText by viewModel.inputText.collectAsState()

    // ─── T51: Share Injection + Move-to capsule ───────────────────────
    // Drain any pending share buffered by ShareCoordinator (cold start =
    // bufferVersion already non-zero on first composition; warm start =
    // version increments while the user is mid-session). Runs on every
    // bufferVersion bump.
    val shareBufferVersion by com.openminis.app.share.ShareCoordinator.bufferVersion.collectAsState()
    androidx.compose.runtime.LaunchedEffect(shareBufferVersion) {
        if (shareBufferVersion == 0) return@LaunchedEffect
        // [XSessionDiag] sessionId is passed for LOGGING ONLY — consumeBuffer
        // ignores it for every decision (see its KDoc). It records which chat
        // actually drained the global share buffer.
        val pending = com.openminis.app.share.ShareCoordinator.consumeBuffer(
            context,
            diagSessionId = sessionId,
        )
            ?: return@LaunchedEffect
        com.openminis.app.logging.AppLogger.info(
            "ChatScreen",
            "[Share] injecting ${pending.items.size} item(s) into chat session=$sessionId",
        )
        val sharedDir = com.openminis.app.share.SharedShareStore.sharedFileDirectory(context)
        // [T-android-share-buffer-merge] Accumulate locally rather than
        // reading `inputText` inside the loop. `inputText` is captured from
        // composition and does NOT observe the setInputText calls made here,
        // so every text item was appended to the same stale base and only the
        // last one survived — a two-text share landed as just the second one
        // even after the store-level merge delivered both.
        var draft = inputText
        for (item in pending.items) {
            when (item.kind) {
                com.openminis.app.share.PendingShare.Item.Kind.INLINE_TEXT -> {
                    val sep = if (draft.isNotEmpty()) "\n" else ""
                    val needsTrailingSpace = item.value.startsWith("http://") ||
                        item.value.startsWith("https://")
                    draft = draft + sep + item.value +
                        if (needsTrailingSpace) " " else ""
                    viewModel.setInputText(draft)
                }
                com.openminis.app.share.PendingShare.Item.Kind.ATTACHMENT -> {
                    viewModel.addAttachmentFromStagedShare(java.io.File(sharedDir, item.value))
                }
            }
        }
        // [XSessionDiag] Hypothesis 2: the composer state AFTER injection. If the
        // reported corrupted input was assembled here, this line holds it verbatim
        // (head-truncated) together with the session that received it — which is
        // the direct check against a user's "I only typed 你好" report.
        com.openminis.app.logging.AppLogger.info(
            "XSessionDiag",
            "[XSessionDiag] share/injected: session=${sessionId.take(8)} " +
                "items=${pending.items.size} draftLen=${draft.length} " +
                "draftHead=\"${draft.take(200).replace("\n", "\\n")}\"",
        )
        viewModel.markShareInjected()
        com.openminis.app.share.SharedShareStore.cleanSharedFiles(context)
    }

    // T311: publish "this is the active chat" while ChatScreen is composed,
    // so `minis-config session.*` reads/writes target it. Mirrors iOS
    // `AIChatViewModel.activeSessionId` which is updated on appear / disappear.
    // [T-HANG-DIAG] capture the application context so we can read the
    // current hang count from non-composable scopes below. LocalContext is
    // already used elsewhere in this file via `context`, but DisposableEffect
    // is a non-composable scope so we lift the read up here.
    val tHangDiagAppContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    androidx.compose.runtime.DisposableEffect(sessionId) {
        ChatViewModelStore.screenEntered(sessionId)
        // [T-HANG-DIAG] enter / dispose markers around the ChatScreen lifetime
        // so we can correlate "user tapped session X" → loadSession timings
        // and any subsequent hang record. Removable by grepping out
        // `[T-HANG-DIAG]` from this file.
        println(
            "[T-HANG-DIAG] ChatScreen MOUNT session=$sessionId hangCount=" +
                com.openminis.app.diagnostics.HangDetector.currentHangCount(tHangDiagAppContext),
        )
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "chatScreen.mount")
        onDispose {
            println("[T-HANG-DIAG] ChatScreen UNMOUNT session=$sessionId")
            ChatViewModelStore.screenLeft(sessionId)
            // T-android-new-chat-empty-residue: drop sessions materialised by
            // a settings toggle (ensureSession via /memory, /thinking, etc.)
            // but never sent a real message. VM guards on streaming + DB count
            // so an in-flight agent or non-empty session is left alone.
            // Skip cleanup on configuration changes (e.g. rotation) — the
            // composable is about to re-mount with the same session and its
            // pending attachments would be lost if we released the ViewModel.
            val activity = context as? android.app.Activity
            if (activity?.isChangingConfigurations != true) {
                viewModel.cleanupIfEmptyOnExit()
            }
        }
    }

    // Hang-detector quiet-period reset: if the user lands on a chat session
    // and stays for 10s without the watchdog firing again, the previous
    // hang count was a transient blip and the breaker can release. The call
    // itself is cheap — early-returns when the count is already zero.
    androidx.compose.runtime.LaunchedEffect(sessionId) {
        com.openminis.app.diagnostics.RouteFuse.noteAutoEnter(context, sessionId)
        kotlinx.coroutines.delay(10_000)
        com.openminis.app.diagnostics.HangDetector.markHealthyTick()
        kotlinx.coroutines.delay(com.openminis.app.data.body.ResourceLimits.HEALTHY_TICK_MS)
        com.openminis.app.diagnostics.RouteFuse.markHealthy(context, sessionId)
    }

    // Drain any pending Move-to transfer when entering this session — the
    // source ChatScreen stashed (inputText + attachments) into the global
    // ChatViewModelStore.pendingTransfer slot before navigating here.
    androidx.compose.runtime.LaunchedEffect(sessionId) {
        // [T-android-moveto-stash-binding] Pass this screen's session so the
        // store only hands over a stash addressed to it (and drops stale ones).
        val transfer = ChatViewModelStore.consumePendingTransfer(sessionId) ?: return@LaunchedEffect
        com.openminis.app.logging.AppLogger.info(
            "ChatScreen",
            "[MoveTo] draining transfer into session=$sessionId text=${transfer.inputText.length}ch attachments=${transfer.attachments.size}",
        )
        // Clear any stale unsent attachments on the target session before
        // injecting (mirrors iOS injectPendingTransferIfNeeded).
        viewModel.clearAttachments()
        if (transfer.inputText.isNotEmpty()) {
            val sep = if (inputText.isNotEmpty()) "\n" else ""
            viewModel.setInputText(inputText + sep + transfer.inputText)
        }
        for (a in transfer.attachments) viewModel.addAttachment(a)
        viewModel.markShareInjected()
    }

    // Mirrors `inputText` for the BasicTextField but tracks selection so we
    // can position the cursor (e.g. AFTER the leading "/" when the slash
    // button inserts it) — a plain String overload would reset cursor to 0
    // on every external write.
    // [T-update-download-fgs] rememberSaveable: a process death during a
    // background update download used to wipe a half-typed message. The
    // TextFieldValue saver preserves text AND selection across recreation.
    var inputFieldValue by androidx.compose.runtime.saveable.rememberSaveable(
        stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver,
    ) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    }
    var showExpandedEditor by remember { mutableStateOf(false) }
    // T217-2: suppress IME commits arriving briefly after send. clearFocus
    // triggers finishComposingText, which makes voice/Pinyin IMEs commit
    // their pending candidate back through onValueChange even after we
    // cleared inputText. Drop those late commits during a short window.
    var lastSendTimeMs by remember { mutableStateOf(0L) }
    // [T-voice-mode-memory-refine-android] True when a voice recording started
    // since the composer was last cleared. The SEND is what commits the mode:
    // mic-start no longer writes "voice" (an accidental mic tap with no send
    // must not flip the default) — instead this flag is consulted on send.
    var voiceUsedSinceClear by remember { mutableStateOf(false) }
    // Shared by both send paths (send button / Enter): commit the composer
    // mode at send time — "voice" if this composition used voice, otherwise
    // "text" — then reset the tracker for the now-cleared composer.
    val noteSendForInputModePref: () -> Unit = {
        ComposerInputModePrefs.save(context, voice = voiceUsedSinceClear)
        voiceUsedSinceClear = false
        // [T-android-voice-correction] A send is the natural moment to mine
        // typed vocabulary: the message is committed, and the builder's own
        // hourly throttle makes the common case a no-op. Consent-gated and
        // fire-and-forget inside.
        com.openminis.app.speech.correction.VoiceCorrection.mineVocabularyIfNeeded(context)
    }
    // [T-android-send-no-autoscroll-behind-preview] Timestamp of the most
    // recent USER message append, stamped in LE(messages.size) so it covers
    // every origin (send button, Enter, RPC, enqueue-while-streaming). Used
    // as the follow-grace window for the reserve-change pin. Deliberately
    // separate from lastSendTimeMs above — that one also drives the 300ms
    // IME-residue suppression in the composer and must stay UI-send-only.
    var lastUserAppendMs by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(inputText) {
        if (inputFieldValue.text != inputText) {
            // [T-android-slash-menu-align-ios-prepend] Honor a one-shot caret
            // override from the slash flow (prepend "/ " → caret 1; insert
            // "/<skill> " → caret after the prefix). Read-and-clear so it
            // applies exactly once; otherwise default the caret to the end
            // (existing behavior). Coerce into bounds defensively.
            val caret = viewModel.consumePendingCaret()?.coerceIn(0, inputText.length)
                ?: inputText.length
            inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                text = inputText,
                selection = androidx.compose.ui.text.TextRange(caret),
                // T217: explicitly drop any pending IME composing buffer so voice
                // recognition / Pinyin candidates don't get re-committed back into
                // the field after send (mirrors iOS unmarkText in AIChatView.swift
                // updateUIView L5638).
                composition = null,
            )
        }
    }
    val inputFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    // Mirror of iOS `inputFocused` — needed so the swipe-up-on-empty-input
    // gesture only pops the keyboard when it's actually collapsed.
    var inputFocused by remember { mutableStateOf(false) }

    // --- Swipe-up-to-send (parity with iOS AIChatView.swift) ---------------
    // Drag progress 0..1 as fraction of the trigger distance. Drives the
    // floating send-arrow hint + "Release to send" capsule overlay. Only
    // updated while the input has non-empty text.
    var sendSwipeProgress by remember { mutableStateOf(0f) }
    // Live fingertip position inside the input bar (px). Hint floats ~60dp
    // above this point so it isn't hidden under the user's thumb.
    var sendSwipeLocation by remember { mutableStateOf(Offset.Zero) }
    val swipeThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
    // Match iOS: haptic + capsule full-opacity + release-fires-send all
    // engage at this fraction (below 1.0 so user gets earlier confirmation).
    val swipeArmFraction = 0.8f
    val swipeHapticOffsetPx = with(LocalDensity.current) { 60.dp.toPx() }
    val swipeArrowHalfPx = with(LocalDensity.current) { 17.dp.toPx() }
    val swipeHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    val coroutineScope = rememberCoroutineScope()

    var showModelPicker by remember { mutableStateOf(false) }
    // [T-android-modelpicker-stuck-ripple] Interaction source for the navbar
    // model-picker row, owned here so the press can be drained when the picker
    // closes. See the clickable's comment for why the release never arrives on
    // its own.
    // [T-android-modelpicker-stuck-ripple] The row owns an InteractionSource
    // that is REPLACED whenever the picker closes, rather than one whose
    // presses we try to cancel individually.
    //
    // Why replacement: opening the picker puts a modal sheet over this row, so
    // the pointer's UP never reaches the clickable — Compose emits
    // PressInteraction.Press with no matching Release and the ripple stays
    // lit, still visible after the sheet closes as a permanent grey highlight.
    //
    // The obvious fix (collect the interactions, remember the open presses,
    // emit Cancel for each on close) does NOT work reliably, and shipping it
    // was the first attempt: MutableInteractionSource's flow has replay=0 and
    // the collector is started by a LaunchedEffect coroutine, so a Press that
    // lands before that coroutine is dispatched is never observed. The
    // bookkeeping list is then empty, no Cancel is emitted, and the ripple
    // stays exactly as stuck as before — while the ripple's own internal
    // subscriber, registered during composition, did see it.
    //
    // Handing the clickable a brand-new source drops every interaction the old
    // one was holding, with no dependence on collector timing.
    var modelPickerInteractionGeneration by remember { mutableIntStateOf(0) }
    val modelPickerInteraction = remember(modelPickerInteractionGeneration) {
        MutableInteractionSource()
    }
    LaunchedEffect(showModelPicker) {
        if (!showModelPicker) modelPickerInteractionGeneration++
    }
    // [T-android-thinking-badge-navbar] Whether the thinking-level sheet
    // (opened by tapping the navbar thinking badge) is presented. Mirrors iOS
    // AIChatView.showThinkingLevelSheet.
    var showThinkingLevelSheet by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showChatMenu by remember { mutableStateOf(false) }
    var showSkillsSheet by remember { mutableStateOf(false) }
    // [T-mcp-integration-android] MCPs-in-Session sheet visibility.
    var showMcpsSheet by remember { mutableStateOf(false) }
    var showTokenUsageSheet by remember { mutableStateOf(false) }
    // T185: Move-to-session sheet visibility. Hoisted to the top of
    // ChatScreen so the trigger (capsule inside the composer) and the
    // sheet body (rendered later in the layout tree) share the same
    // backing state without needing fragile scope wiring.
    var showMoveSheet by remember { mutableStateOf(false) }
    var showClearChatDialog by remember { mutableStateOf(false) }
    var showCardShareDialog by remember { mutableStateOf(false) }
    // [T-android-delete-from-here] Id of the message a pending "Delete From
    // Here" would cut at, or null when no confirmation is open. Holding the
    // id (rather than a boolean plus a separate field) keeps the dialog and
    // its target impossible to desynchronize.
    var deleteFromHereTargetId by remember { mutableStateOf<String?>(null) }
    // [T-new-chat-menu-entry] Confirmation gate for "New Chat" while the
    // current session is still streaming — stopping the running task needs
    // an explicit confirm; idle sessions skip the dialog entirely.
    var showNewChatStopDialog by remember { mutableStateOf(false) }
    // [T-android-enhanced-cache] First-enable confirmation dialog visibility.
    var showEnhancedCacheDialog by remember { mutableStateOf(false) }

    // Bridge VM's slash-command "/clear" request into local Compose state so
    // the menu and slash-command entry points share a single confirmation
    // dialog instance. ack the VM flag immediately to avoid re-firing on
    // recomposition.
    val clearChatRequested by viewModel.clearChatConfirmRequested.collectAsState()
    LaunchedEffect(clearChatRequested) {
        if (clearChatRequested) {
            showClearChatDialog = true
            viewModel.ackClearChatConfirmRequest()
        }
    }

    // "Choose Photos & Videos" — uses the Photo Picker on Android 13+ via the
    // PickMultipleVisualMedia contract; AndroidX falls back to
    // ACTION_OPEN_DOCUMENT on older versions. Mirrors iOS PHPicker
    // (.imagesAndVideos, selectionLimit=50). T129: switched from single to
    // multi-select with a 50-item cap — picks above 50 are truncated and we
    // toast the user so they aren't silently dropped.
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(
            maxItems = ATTACHMENT_PICK_LIMIT,
        ),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val limited = uris.take(ATTACHMENT_PICK_LIMIT)
        for (uri in limited) {
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val isVideo = mimeType.startsWith("video/")
            val defaultName = if (isVideo) "video.mp4" else "image.jpg"
            val fileName = getFileName(context, uri) ?: defaultName
            viewModel.addAttachment(
                InputAttachment(
                    fileName = fileName,
                    uri = uri,
                    mimeType = mimeType,
                    // Videos are routed as DOCUMENT for now — vision pipeline only
                    // handles images today; videos still upload as raw files so
                    // tools that read them (e.g. ffmpeg) get the bytes.
                    kind = if (isVideo) com.openminis.app.session.InputAttachment.Kind.DOCUMENT else com.openminis.app.session.InputAttachment.Kind.IMAGE,
                ),
            )
        }
        if (uris.size > ATTACHMENT_PICK_LIMIT) {
            android.widget.Toast.makeText(
                context,
                "Only the first $ATTACHMENT_PICK_LIMIT items were attached.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // "Take Photo" — Bug 1 in the MIUI feedback report had this silently
    // drop photos because the default TakePicture contract trusts
    // resultCode, and MIUI's camera occasionally returns CANCELED even
    // after writing the file (or OK with the file flushed late). We use
    // StartActivityForResult directly and trust the filesystem instead:
    // if the staging file has nonzero length, we got a photo.
    // [T-android-camera-rotate-lost-photo] MainActivity has no
    // configChanges="orientation", so capturing in one orientation and
    // returning in another RECREATES the Activity. These pending handles must
    // therefore survive the recreate — `remember` is reset on recomposition
    // after recreation, so the ActivityResult callback would see a null uri
    // and silently drop the just-taken photo (gallery picks are unaffected:
    // their result Uri arrives directly in-callback). `rememberSaveable`
    // persists through savedInstanceState: Uri is Parcelable; the staging
    // File is saved as its absolute path string and rebuilt on read.
    var pendingCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingCameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // [T-android-overlay-hide-camera] Release the overlay-suppress
        // gate as soon as we hear back from the camera Activity (success,
        // cancel, or system kill). Without this the floating overlay
        // would stay suppressed indefinitely after a single capture.
        com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(false)
        val uri = pendingCameraUri
        val file = pendingCameraFilePath?.let { java.io.File(it) }
        pendingCameraUri = null
        pendingCameraFilePath = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        // Don't trust resultCode on MIUI — check the file.
        val ok = file.exists() && file.length() > 0
        if (ok) {
            val fileName = getFileName(context, uri) ?: file.name
            viewModel.addAttachment(
                InputAttachment(
                    fileName = fileName,
                    uri = uri,
                    mimeType = "image/jpeg",
                    kind = com.openminis.app.session.InputAttachment.Kind.IMAGE,
                ),
            )
        } else {
            AppLogger.warning(
                "Camera",
                "capture failed: rc=${result.resultCode}, file=${file.name} len=${file.length()}",
            )
            file.delete()
        }
    }
    val launchCamera: () -> Unit = {
        val (uri, file) = createCameraOutputUri(context)
        pendingCameraUri = uri
        pendingCameraFilePath = file.absolutePath
        val intent = android.content.Intent(
            android.provider.MediaStore.ACTION_IMAGE_CAPTURE,
        ).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // [T-android-overlay-hide-camera] Suppress the floating bg-overlay
        // BEFORE handing off to the system camera. The camera Activity
        // takes foreground, which by #451's rule would otherwise satisfy
        // "Minis backgrounded → show overlay" and the capsule would draw
        // on top of the viewfinder. Cleared in the ActivityResult callback.
        com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(true)
        runCatching { cameraLauncher.launch(intent) }
            .onFailure {
                AppLogger.warning("Camera", "launch failed: ${it.message}")
                // Launch never reached the camera Activity — release the
                // suppress flag here since the result callback won't fire.
                com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(false)
                pendingCameraUri = null
                pendingCameraFilePath = null
                file.delete()
            }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
    }

    // App-icon quick action: when the user launched via
    // `minis://action/camera_chat`, auto-open the camera on first compose.
    // Consumed exactly once so re-entering the chat later does NOT re-trigger.
    // Voice variant lives next to the MicButton because it needs sttAvailable
    // — camera is always available so it can fire from the top-level scope.
    LaunchedEffect(sessionId) {
        val pending = com.openminis.app.deeplink.DeepLinkCoordinator
            .pendingChatAction.value
        if (pending == com.openminis.app.deeplink.DeepLinkCoordinator
                .ChatAction.OPEN_CAMERA
        ) {
            com.openminis.app.deeplink.DeepLinkCoordinator
                .consumePendingChatAction()
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) launchCamera()
            else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    // File picker launcher — T129: multi-select via OpenMultipleDocuments
    // (GetContent has no multi-select equivalent). The launch arg is now a
    // mime-type array; "*/*" stays as the wildcard. Selections above
    // ATTACHMENT_PICK_LIMIT are truncated with a toast so silent drops can't
    // happen. OpenMultipleDocuments returns persistable URIs by default
    // (good — survives process death better than the GetContent stream).
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val limited = uris.take(ATTACHMENT_PICK_LIMIT)
        for (uri in limited) {
            val fileName = getFileName(context, uri) ?: "file"
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val kind = if (mimeType.startsWith("image/")) com.openminis.app.session.InputAttachment.Kind.IMAGE else com.openminis.app.session.InputAttachment.Kind.DOCUMENT
            viewModel.addAttachment(
                InputAttachment(fileName = fileName, uri = uri, mimeType = mimeType, kind = kind)
            )
        }
        if (uris.size > ATTACHMENT_PICK_LIMIT) {
            android.widget.Toast.makeText(
                context,
                "Only the first $ATTACHMENT_PICK_LIMIT files were attached.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // Android system permission launcher for agent tools (e.g. location).
    //
    // The launcher's `results` map can't be trusted alone: on several Android
    // versions `RequestMultiplePermissions` returns an empty map (or `false`
    // entries) for permissions that were already granted and thus didn't need
    // a dialog. Re-query the live permission state via checkSelfPermission to
    // decide success — this is what actually matters to the caller.
    val currentPermissionsRef = remember { mutableStateOf<Array<String>>(emptyArray()) }
    val androidPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val perms = currentPermissionsRef.value
        val grantedNow = perms.isNotEmpty() && perms.any { p ->
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        }
        OffloadPermissionManager.respondToAndroidPermission(grantedNow)
    }
    val pendingAndroidPermission by OffloadPermissionManager.pendingAndroidPermission.collectAsState()
    LaunchedEffect(pendingAndroidPermission) {
        val req = pendingAndroidPermission ?: return@LaunchedEffect
        val perms = req.permissions.toTypedArray()
        // Short-circuit when everything's already granted — some OEM builds
        // launch a no-op dialog that still flashes on screen otherwise.
        val alreadyGranted = perms.isNotEmpty() && perms.any { p ->
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        }
        if (alreadyGranted) {
            OffloadPermissionManager.respondToAndroidPermission(true)
            return@LaunchedEffect
        }
        currentPermissionsRef.value = perms
        androidPermissionLauncher.launch(perms)
    }

    val tagScroll = "ChatScrollFollow"
    // Scroll wrappers used by every code path that mutates the LazyColumn
    // position. Kept as named lambdas so re-enabling per-call telemetry
    // (during a scroll-positioning regression) is a one-line edit here
    // instead of changing 20+ call sites. Currently silent.
    // T-android-jank-profile: gate verbose scroll telemetry behind a constant
    // so every snapshotFlow / derivedStateOf body in this file can cheaply
    // skip the AppLogger.debug call (which builds a long format string and
    // writes a daily log file). Flip locally when debugging scroll behavior.
    // [T-android-scroll-telemetry-gate] Declared BEFORE the traced wrappers so
    // their (previously unconditional) per-call logging can be gated too —
    // the old TEMP ScrollSrc log built a long string on every programmatic
    // scroll in production.
    val verboseScrollLogs = false
    val tracedScrollToItem: suspend (source: String, idx: Int, off: Int) -> Unit = { source, idx, off ->
        if (verboseScrollLogs) {
            AppLogger.debug(
                "ScrollSrc",
                "scrollToItem src=$source idx=$idx off=$off canBwd=${listState.canScrollBackward} firstIdx=${listState.firstVisibleItemIndex} firstOff=${listState.firstVisibleItemScrollOffset} inProgress=${listState.isScrollInProgress}",
            )
        }
        runCatching { listState.scrollToItem(idx, off) }
        Unit
    }
    val tracedScrollBy: suspend (source: String, delta: Float) -> Unit = { source, delta ->
        if (verboseScrollLogs) {
            AppLogger.debug(
                "ScrollSrc",
                "scrollBy src=$source delta=$delta canBwd=${listState.canScrollBackward} firstIdx=${listState.firstVisibleItemIndex} firstOff=${listState.firstVisibleItemScrollOffset}",
            )
        }
        runCatching { listState.scrollBy(delta) }
        Unit
    }

    // [T-android-scroll-policy] Reading-mode anchor = the OLDEST visible row
    // (highest lazy index under reverseLayout, ignoring synthetic __ rows).
    // Captured when the user stops dragging away from the bottom or starts a
    // deliberate browse, so the mode transition records where they went to
    // read. Pure bookkeeping — no scroll is issued from it. Selection goes
    // through ChatHistoryWindow.visibleTopRow — the single visual-top
    // definition (T-android-visual-top).
    fun visibleTopAnchor(): Pair<String, Int>? {
        val rows = listState.layoutInfo.visibleItemsInfo.map {
            ChatHistoryWindow.VisibleRow(it.index, it.key as? String ?: "", it.offset, it.size)
        }
        val top = ChatHistoryWindow.visibleTopRow(rows) ?: return null
        return top.key to top.offset
    }

    // ─── T120: scroll-follow rewrite (supersedes T66 / T92 / T99 / T100 / T101 / T112) ───
    //
    // Five iterations of "fight the LazyColumn" (anchor lock, fling-settle
    // gate, isStreaming/lastToolCount/lastAwaiting force-follow LEs) never
    // truly stopped the streaming jitter. Survey of production Compose
    // chat clients (google-ai-edge/gallery, GetStream/stream-chat-android-ai,
    // lambiengcode/compose-chatgpt-kotlin-android-chatbot, Taewan-P/gpt_mobile)
    // showed a consistent pattern:
    //
    //   1. Trust reverseLayout's native bottom anchor — do not call
    //      scrollToItem(0) on every streaming token.
    //   2. Auto-scroll only on TERMINAL events (user sends, IME opens,
    //      stream finishes) — never per-token.
    //   3. Treat "user scrolled away" as a derived value of the current
    //      list position, not a stateful flag mutated by a gesture flow.
    //   4. Provide a JumpToBottom FAB as the universal escape hatch
    //      (already present in this file).
    //
    // What was removed
    //   - Anchor-lock LaunchedEffect (T92 / T99 / T112) — Compose's
    //     reverseLayout already keeps a fixed item anchored, the lock
    //     was fighting that.
    //   - userScrolledAway state mutation in two snapshotFlow collectors —
    //     replaced by a single derivedStateOf<Boolean>.
    //   - LE(isStreaming) edge force-follow.
    //   - LE(lastToolCount) force-follow.
    //   - LE(lastAwaiting) force-follow.
    //   - LE(bottomReserve) inside the toolbar block.
    //   - LE(messages.size) for assistant/tool/system rows — only the
    //     user-send branch survives, because sending is the one event
    //     where "follow the new turn" is unambiguously the user's intent.
    //
    // What stayed
    //   - User-action scroll calls at the send button, retry buttons,
    //     and the JumpToBottom FAB — those are direct user intent.
    //   - reverseLayout=true on the LazyColumn — handles "stick to
    //     bottom while user is at bottom" natively.

    // T128: tightened from 90 dp (google-ai-edge/gallery) to 32 dp.
    // 90 dp made the JumpToBottom FAB appear well before the user had
    // really left the bottom — users reported the "Quick to bottom" button
    // appearing too often. 32 dp is roughly half the floating tool-bar height, so the
    // visual definition of "at bottom" lines up with what the user sees.
    val nearBottomThresholdPx = with(LocalDensity.current) { 32.dp.toPx() }
    // T138 phase 2 v3: ground-truth bottom test via layoutInfo. If
    // LazyList currently renders the visual-bottom item (data-index 0
    // under reverseLayout) and its bottom edge sits within `threshold`
    // px of the viewport bottom, the user is visually at the bottom.
    // `firstVisibleItemIndex` is unreliable here: when a single message
    // emission expands into N tool / text flat items, firstVisible
    // drifts by N in one frame (logcat showed jumps of 5+ on a
    // multi-tool turn). Anchor on the rendered set instead.
    val isNearBottom = remember(listState, nearBottomThresholdPx) {
        derivedStateOf {
            val info = listState.layoutInfo
            val bottomItem = info.visibleItemsInfo.firstOrNull { it.index == 0 }
            val viewportEnd = info.viewportEndOffset
            val itemBottom = bottomItem?.let { it.offset + it.size } ?: Int.MIN_VALUE
            val gap = viewportEnd - itemBottom
            // T173: when the bottom row is taller than the viewport (e.g. one
            // big assistant message with code blocks), `gap` is permanently
            // hugely negative even when the user is anchored at the bottom —
            // because reverseLayout pins index 0's *bottom* to the viewport
            // bottom, but the item's geometric bottom is below the viewport
            // (it extends downward off-screen in layoutInfo terms). The
            // earlier `gap < threshold` test happened to be true in that
            // case, but it ALSO stayed true as the user scrolled up by
            // thousands of px — settle-after-interaction then snapped them
            // right back. Use the LazyListState anchor instead: under
            // reverseLayout, "at bottom" ⇔ index 0 is the first item AND
            // its scroll offset is within `threshold` px. Any drag upward
            // grows firstVisibleItemScrollOffset past threshold instantly,
            // so the user's intent flips into userScrolledAway.
            val firstIdx = listState.firstVisibleItemIndex
            val firstOff = listState.firstVisibleItemScrollOffset
            // [T-android-scroll-isnearbottom-bug] Anchor authority lives on
            // listState.firstVisibleItem(Index|ScrollOffset). Previous form
            // required `bottomItem != null` too, but during a fresh measure
            // pass visibleItemsInfo can transiently be empty even when the
            // user IS at the bottom (firstIdx=0 / firstOff=0). The empty
            // window read as "not at bottom" and propagated to:
            //   - reserve-change SKIP at the bottom (recovery yank lost)
            //   - scroll-to-bottom FAB shown on a session that's actually
            //     bottom-anchored
            //   - trailing-row pin gated on isNearBottom failing
            // See /tmp/fix_scroll_diagnosis.md. Anchor on firstIdx/firstOff
            // alone — they survive the measure window.
            val result = firstIdx == 0 && firstOff <= nearBottomThresholdPx.toInt()
            // T-android-jank-profile: was logging on every scroll frame (this
            // is a derivedStateOf body — it re-runs when any of
            // listState.layoutInfo / firstVisibleItemIndex /
            // firstVisibleItemScrollOffset / canScrollForward / etc. change,
            // i.e. ~60 times/second during a scroll fling). String-building
            // + file write per frame measurably contributed to scroll jank.
            // Gate behind a debug toggle so the log path stays available for
            // future scroll-debugging sessions but doesn't ship by default.
            if (false) {
                AppLogger.debug(
                    tagScroll,
                    "isNearBottom: bottomVisible=${bottomItem != null} itemBottom=$itemBottom viewportEnd=$viewportEnd gap=$gap threshold=${nearBottomThresholdPx.toInt()} firstVisible=$firstIdx firstOffset=$firstOff canScrollForward=${listState.canScrollForward} canScrollBackward=${listState.canScrollBackward} totalItems=${info.totalItemsCount} visibleItems=${info.visibleItemsInfo.size} isScrollInProgress=${listState.isScrollInProgress} → $result",
                )
            }
            result
        }
    }
    // T170: derived "does content actually overflow the viewport?". Mirrors
    // iOS where `maxOffset > 0` naturally hides the FAB on short sessions.
    // Without this, an IME-driven synthetic drag-stop on a short chat could
    // pin the FAB on screen until the keyboard closed.
    val contentOverflows = remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportSize = info.viewportEndOffset - info.viewportStartOffset
            val canScroll = listState.canScrollForward || listState.canScrollBackward
            val moreItemsThanVisible = info.totalItemsCount > info.visibleItemsInfo.size
            val visibleSum = info.visibleItemsInfo.sumOf { it.size }
            val sumExceedsViewport = visibleSum > viewportSize
            val result = canScroll || moreItemsThanVisible || sumExceedsViewport
            // T-android-jank-profile: gate per-frame derivedStateOf logs.
            if (false) {
                AppLogger.debug(
                    tagScroll,
                    "contentOverflows: canScroll=$canScroll moreItems=$moreItemsThanVisible sumExceeds=$sumExceedsViewport visibleSum=$visibleSum viewport=$viewportSize total=${info.totalItemsCount} visible=${info.visibleItemsInfo.size} → $result",
                )
            }
            result
        }
    }

    // [T-android-scrollbtn-turn-walk] Per-index observed item sizes. These once
    // backed the up-button's isFarFromTop/isFarFromBottom gate (now removed in
    // favour of the shared !isNearBottom condition); they are retained because
    // the streaming-content glide still uses the running average to size its
    // per-frame scroll steps.
    //
    // The list is reverseLayout=true: index 0 is the NEWEST message (visual
    // bottom), the highest index is the OLDEST (visual top).
    // [T-android-scroll-to-first-message] Compose's LazyListLayoutInfo exposes
    // sizes of CURRENTLY visible items only — no contentSize / contentOffset
    // equivalent to iOS's UIScrollView. Earlier estimations (off-screen item
    // count × avg/min visible-item size) misfired badly because one assistant
    // message expands into many FlatChatItems (header, several markdown
    // blocks, tool blocks, typing indicator); the index count balloons out
    // of proportion to actual pixel distance, so the up-button kept popping
    // up right above the input bar when only a tool-block + header lay
    // off-screen.
    //
    // Instead we OBSERVE: every time an item enters the viewport, cache its
    // (index → size). As the user scrolls we accumulate ground truth for
    // every index we've ever seen. Distance to either end then = sum of
    // cached sizes for the off-screen indices we know about, plus the
    // visible items' real partial overhang. Indices we've never seen still
    // contribute zero — that's a strict lower bound, so we can only
    // under-show the button, never flash it near an end.
    val itemSizeByIndex = remember(listState) { mutableStateMapOf<Int, Int>() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .collect { vis ->
                for (it in vis) {
                    val cached = itemSizeByIndex[it.index]
                    if (cached == null || cached != it.size) itemSizeByIndex[it.index] = it.size
                }
            }
    }
    // [T-android-scroll-fab-first-entry] Average observed item size, used to
    // estimate the height of indices we've never had on-screen. The pure
    // cache-only approach (b58e9515) was one-sided: belowSum (already-scrolled-
    // past items, cached) worked, but aboveSum summed indices we hadn't reached
    // yet, which are NEVER cached at the moment they're off-screen ABOVE — so
    // aboveSum stayed 0 forever and the up-button never appeared (logged:
    // aboveSum=0 across an entire top-scroll, even with all 68 items eventually
    // cached). Estimating unknown indices by the running average makes BOTH
    // ends symmetric and direction-independent. The average is a real measured
    // mean (not a wild min/avg-of-visible extrapolation that the commit comment
    // warned against), so it tracks actual pixel distance closely enough for a
    // one-viewport threshold.
    val avgItemSize = remember(listState, itemSizeByIndex) {
        derivedStateOf {
            val sizes = itemSizeByIndex.values
            if (sizes.isEmpty()) 0 else sizes.sum() / sizes.size
        }
    }
    // [T-android-scrollbtn-turn-walk] The isFarFromTop / isFarFromBottom pair
    // that used to live here is gone, mirroring iOS dcdec3c5: the up-button's
    // visibility is now the shared `!isNearBottom` condition, so the separate
    // one-viewport-from-both-ends estimation has no remaining consumer.
    // `itemSizeByIndex` / `avgItemSize` above are deliberately KEPT — the
    // streaming glide (LE(streaming-content)) still uses the running average to
    // size its per-frame steps.

    // [T-android-scroll-policy] Single scroll authority (ChatScrollPolicy KDoc
    // has the full history). Pinned = the viewport follows the newest edge and
    // content-driven scrolls are allowed. Reading = the user is reading
    // history and NO content event may scroll. This replaces the old
    // `userScrolledAway` boolean whose scattered mutation sites (pill never
    // set it, force-scroll cleared it, drag-stop cleared it inside a 1.5 s
    // window, an arm-net set it, an anchor-restore effect re-scrolled to stale
    // snapshots) contradicted each other — the root of the
    // "读历史时新消息到达导致跳屏" bug family.
    val scrollPolicy = remember { ChatScrollPolicy() }
    val scrollMode by scrollPolicy.mode.collectAsState()
    var pinRealLatest by remember(sessionId) { mutableStateOf(false) }
    var entryPinRequested by remember(sessionId) { mutableStateOf(false) }

    // A session-scoped ViewModel can keep streaming while its screen is
    // disposed. On re-entry the retained LazyListState may point into history;
    // the normal stream follower intentionally respects that browsing state.
    // A running session is different: opening it is an explicit request to
    // follow the live output. Wait for the initial DB tail, drain any committed
    // tail rows, then pin after the flattened rows have been published.
    LaunchedEffect(sessionId, sessionLoaded, isStreaming, hasNewerMessages) {
        if (!sessionLoaded || !isStreaming || entryPinRequested) return@LaunchedEffect
        entryPinRequested = true
        scrollPolicy.pin()
        viewModel.ensureSessionTailLoaded()
        // The initial tail load and any queued tail attach are asynchronous.
        // Waiting for the newest-edge flag here is what keeps long sessions
        // from landing on a mid-history restored viewport before this pin.
        viewModel.hasNewerMessages.first { !it }
        withFrameNanos { }
        tracedScrollToItem("SESSION-REENTRY/live-tail", 0, 0)
        kotlinx.coroutines.delay(100)
        tracedScrollToItem("SESSION-REENTRY/live-tail-settle", 0, 0)
    }

    LaunchedEffect(hasNewerMessages, pinRealLatest) {
        if (!pinRealLatest || hasNewerMessages) return@LaunchedEffect
        pinRealLatest = false
        tracedScrollToItem("FAB-DOWN/tail", 0, 0)
        kotlinx.coroutines.delay(100)
        tracedScrollToItem("FAB-DOWN/tail-settle", 0, 0)
    }

    // [T-android-scrollbtn-turn-walk] Up-button turn-walk state, mirroring iOS
    // `lastJumpedUserId` (dcdec3c5). Holds the id of the user message the
    // up-button last jumped to, so a REPEATED tap walks one turn further back
    // instead of re-landing on the same turn. Reset to null whenever the
    // anchoring context changes — manual drag, jump-to-bottom, message-list
    // change, or session switch — so the next tap re-anchors to whatever the
    // user is currently looking at rather than continuing a stale sequence.
    var lastJumpedUserId by remember(sessionId) { mutableStateOf<String?>(null) }
    // [T-android-scrollbtn-turn-walk] The old "clear on messages.size change"
    // reset is gone: it broke the walk chain on EVERY streaming tick / page
    // load, which is how repeated taps landed on the same turn over and over
    // ("点翻页按钮又退回到一定值"). With stable keys and no anchor-restore
    // effect, list growth no longer invalidates the walk — the chain resets
    // only on drag, jump-to-bottom and session switch.
    // [T-android-upbtn-single-flight] One walk in flight; extra taps queue
    // exactly one continuation instead of stacking concurrent scroll jobs.
    var upWalkJob by remember(sessionId) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Backlog of taps that arrived while a walk was in flight, capped at 2:
    // N rapid taps used to collapse to ONE continuation ("按钮吞点击"); two
    // queued walks keep the response responsive without unbounded chasing.
    var upWalkQueuedCount by remember(sessionId) { mutableStateOf(0) }

    // [T-android-scroll-fab-reversed] TEMP diagnostic — capture BOTH FABs'
    // gates so we can verify the matrix (bottom=none, middle=both, top=down
    // only) and why the down-FAB is missing at the top. Remove after fix.
    LaunchedEffect(listState) {
        snapshotFlow {
            val up = !isNearBottom.value && messages.isNotEmpty()
            val down = scrollMode is ScrollMode.Reading && contentOverflows.value && messages.isNotEmpty()
            "FABs up=$up down=$down | nearBottom=${isNearBottom.value} lastJumped=${lastJumpedUserId?.take(8)} mode=$scrollMode overflow=${contentOverflows.value} canFwd=${listState.canScrollForward} canBwd=${listState.canScrollBackward}"
        }.collect { AppLogger.debug("ScrollFAB2", it) }
    }

    // T-drag-send-queue: shared send-or-enqueue handler used by BOTH the
    // send-button tap and the swipe-up-to-send drag. Routes through
    // `viewModel.sendMessage(...)` which internally dispatches to
    // `enqueuePrompt()` when `_isStreaming.value` is true, so the message is
    // queued rather than dropped when the agent loop is mid-flight. Slash-
    // command input short-circuits to the command runner (mirrors the tap
    // path). Caller decides whether to invoke this — gating (canActivate,
    // armFraction, swipedUp) stays at the call site.
    // [T-android-hwkeyboard-keep-focus] With a physical keyboard attached,
    // dropping focus after a send is wrong: there is no on-screen keyboard
    // occupying half the display to reclaim, and the user's hands are already
    // on the keys — they expect to type the next message immediately, the way
    // every desktop chat client behaves. Without this a hardware-keyboard user
    // has to reach up and tap the composer again after every single send.
    //
    // Read from Configuration rather than an input-device scan: `qwerty` +
    // `keysexposed` is exactly the state Android already tracks for this, it
    // updates live when a Bluetooth keyboard connects or a cover folds shut,
    // and it recomposes the caller for free. Verified on a Mate Pad, which
    // reports `keysexposed-qwerty` with its keyboard attached.
    val configuration = LocalConfiguration.current
    val hasHardwareKeyboard = configuration.keyboard ==
        android.content.res.Configuration.KEYBOARD_QWERTY &&
        configuration.hardKeyboardHidden ==
        android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO

    /**
     * [T-android-hwkeyboard-keep-focus] Release the composer after a send —
     * except on a hardware keyboard, where focus is kept so the user can keep
     * typing. Centralised so every send path makes the same choice; there are
     * four of them (button, Enter, slash-command via either) and they had
     * drifted into repeating the same two lines.
     */
    val releaseComposerAfterSend: () -> Unit = {
        if (!hasHardwareKeyboard) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }

    val performSendOrEnqueue: (String) -> Unit = handler@{ rawText ->
        if (viewModel.tryExecuteInputAsSlashCommand(rawText)) {
            viewModel.setInputText("")
            releaseComposerAfterSend()
            return@handler
        }
        lastSendTimeMs = System.currentTimeMillis()
        // [T-android-slash-send-keeps-text] A send always ends the slash session.
        //
        // Tapping the "/" button over existing text puts the composer into
        // "over-content" mode: it prepends "/ " (so "hello" becomes "/ hello")
        // and stashes the original in savedInputBeforeSlash so every exit path
        // can restore it. But SEND was not one of those exit paths — it cleared
        // the text while leaving the stash and the open menu behind, so the
        // just-sent body was restored into the composer and the user saw their
        // message both sent AND still sitting in the input.
        //
        // Clearing the session here, before the text is cleared, makes send a
        // proper terminal exit: nothing is left to restore. The dismiss and
        // command-row paths keep their own restore behaviour untouched.
        viewModel.endSlashSessionForSend()
        viewModel.setInputText("")
        releaseComposerAfterSend()
        viewModel.sendMessage(rawText)
        noteSendForInputModePref()
        scrollPolicy.pin()
        coroutineScope.launch {
            tracedScrollToItem("SEND-PATH/initial", 0, 0)
            kotlinx.coroutines.delay(100)
            tracedScrollToItem("SEND-PATH/settle", 0, 0)
        }
    }
    // T196: timestamp of the last drag-stop. The streaming auto-follow LE
    // below has three stages (initial scroll → re-pin after frame → settle
    // after 220 ms) any of which can fire on the *next* token after a drag
    // ends. When the user drags down while already near the bottom,
    // userScrolledAway never flips true, so without this grace window the
    // three stages all run on the next chunk and the user sees the chat
    // "jump back" three times. 1 s covers a typical fling settle (~500-
    // 800 ms) plus a small buffer; the user can re-engage follow at any
    // time by scrolling all the way to the bottom (LE(isNearBottom) above
    // resets userScrolledAway).
    var lastInterruptMs by remember { mutableStateOf(0L) }
    // [T-android-composer-input-blocked-while-streaming] True only while the
    // user's FINGER is actively dragging the message list. Programmatic scrolls
    // (the streaming auto-follow glide, settle, pin-to-bottom) go through
    // `listState.scroll { }` / `scrollToItem`, which set
    // `listState.isScrollInProgress = true` but emit NO DragInteraction. So a
    // gesture-only signal lets us distinguish "user scrolled the transcript"
    // (should dismiss the keyboard) from "streaming auto-followed" (must NOT
    // touch focus). Without this the keyboard closed itself mid-stream and
    // dropped the in-flight keystroke (the reported "can't type while
    // streaming" bug).
    var isUserDragging by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            // T-android-jank-profile: drag interactions fire on every drag
            // event during a scroll (Press / Cancel / Stop). String-building
            // logs here added measurable load. Gate behind a constant.
            when (interaction) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> {
                    isUserDragging = true
                    // [T-android-scrollbtn-turn-walk] A manual drag breaks the
                    // up-button's turn-walk chain: the next tap should re-anchor
                    // to wherever the user landed, not continue the old sequence.
                    lastJumpedUserId = null
                }
                is androidx.compose.foundation.interaction.DragInteraction.Stop -> {
                    isUserDragging = false
                    lastInterruptMs = System.currentTimeMillis()
                    // [T-android-scroll-policy] Finger lift decides ownership:
                    // still at the bottom → keep following; anywhere else →
                    // Reading anchored where the user stopped.
                    val anchor = visibleTopAnchor()
                    scrollPolicy.onDragStop(isNearBottom.value, anchor?.first, anchor?.second ?: 0)
                }
                is androidx.compose.foundation.interaction.DragInteraction.Cancel ->
                    isUserDragging = false
                else -> Unit
            }
        }
    }
    // [T-android-scroll-policy] The old "re-engage follow when isNearBottom
    // flips true" effect is gone: DragInteraction.Stop already decides
    // ownership at finger lift, FAB-down / send pin explicitly, and the
    // stream-end layout reflow (whose bogus one-frame near-bottom reading this
    // effect used to guard against with a 1.5 s window) no longer owns any
    // transition — mode changes only on gestures and deliberate jumps now.
    // T169 / T170: an IME show/hide animates the LazyColumn's content area,
    // which can briefly register as a synthetic drag-stop. Only force-reset
    // when we're actually back at the bottom — a user reading history who
    // opens the keyboard must not be yanked to the bottom.
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottomPx) {
        if (!scrollPolicy.isPinned && isNearBottom.value) scrollPolicy.pin()
    }
    // [T-android-tool-autoscroll] Start-of-turn edge from ViewModel: resume() /
    // retryLast() / retryFromMessage() / rerunFromToolBlock() emit Unit on
    // forceScrollToBottom because they don't append a new user-message row, so
    // LE(messages.size) below skips them. Without this collector the "Minis is
    // thinking…" placeholder stays parked behind the input bar until the first
    // streamed token finally bumps the auto-follow tuple.
    LaunchedEffect(listState, viewModel) {
        viewModel.forceScrollToBottom.collect {
            // [T-android-scroll-policy] Only a PINNED viewport follows
            // resume/retry/rerun. These emissions arrive unbidden (queued
            // retries, auto-resume while the user is elsewhere) and used to
            // unconditionally clear userScrolledAway + jump to the bottom —
            // one of the three surviving jump paths of the reading-history
            // bug. A reader keeps their place; the down-FAB is the way back.
            if (!scrollPolicy.allowsContentScroll()) return@collect
            tracedScrollToItem("FORCE-SCROLL-TO-BOTTOM(resume/retry/rerun)", 0, 0)
        }
    }
    // Auto-scroll on user-send: explicit "show me the next response"
    // gesture, fires regardless of current scroll position.
    LaunchedEffect(messages.size) {
        val lastMsg = messages.lastOrNull() ?: return@LaunchedEffect
        if (lastMsg.role != "user") return@LaunchedEffect
        // [T-android-scroll-away-retain] 发送按钮回调已显式 pin；此 LE 是
        // 兜底路径（enqueue-while-streaming 等），只更新 lastUserAppendMs
        // 供 sendGrace 窗口使用。用户正在读历史时，排队消息到达不应把
        // 视口弹回底部 —— 只在 Pinned 时吸附。
        lastUserAppendMs = System.currentTimeMillis()
        if (scrollPolicy.isPinned) {
            tracedScrollToItem("LE(messages.size)USER-SEND-SNAP", 0, 0)
        }
    }
    // T128: streaming auto-follow when the user is at the bottom.
    //
    // T120 removed all per-token scroll calls assuming reverseLayout
    // would keep the bottom pinned natively. That's true for *new
    // LazyList items*, but a streaming text block grows by appending
    // characters into the same index-0 message item — its height
    // increases while LazyListState keeps firstVisibleItemIndex=0
    // and offset=0, so the new tokens push out below the viewport
    // (and behind the floating tool thumbnail). Result: users at the
    // bottom watched the FAB pop up, tapped it, and immediately had
    // to tap again as the next chunk landed.
    //
    // T170: align with iOS three-stage pin (initial scroll → wait for
    // layoutIfNeeded → re-pin to catch async self-sizing). After
    // scrollToItem(0) we await one frame then re-pin, which catches the
    // common case of a tool-pill + typing indicator inserted in the same
    // recomposition: the first scroll pins to the pre-grow position, the
    // second pin captures the post-self-sizing height. Suppressed when
    // the user is currently dragging — never compete with active touch.
    // T256: streaming auto-follow via snapshotFlow + conflate + sample.
    // Replaces the per-token `LaunchedEffect(lastAssistantStreamingKey)`
    // that pegged the Pixel 4a UI thread (95p frame 77ms / 29% janky) by
    // restarting the entire 3-stage scroll dance on every token. The new
    // pipeline:
    //   1. snapshotFlow emits a tuple per recomposition rather than the
    //      raw content string — content-length comparison is cheap.
    //   2. conflate() drops intermediate ticks the collector never saw.
    //   3. sample(150L) caps follow rate to ~6.5 Hz, matching iOS's
    //      80ms scroll-coalesce + 100ms layout-flush combined gate.
    // Stage 2 (frame settle) and stage 3 (220ms offset clip) move into a
    // separate edge-triggered LE that fires once per stream END, not per
    // token — async self-sizing / image height settling needs the safety
    // net but not at 50ms cadence.
    LaunchedEffect(listState, scrollMode) {
        // T-streaming-side-channel: combine the canonical messages flow
        // with streamingById so growth signals (content length, toolBlocks
        // count, awaiting flag) reflect the live stream — otherwise the
        // auto-follow scroll-to-bottom stops firing during a turn because
        // messages no longer ticks per token.
        kotlinx.coroutines.flow.combine(
            snapshotFlow { messages },
            viewModel.streamingById,
        ) { msgs, stream ->
            val effective = if (stream.isEmpty()) msgs else mergeStreamingOverlay(msgs, stream)
            val m = effective.lastOrNull { it.role == "assistant" } ?: return@combine null
            // [T-android-tool-autoscroll] Trigger tuple includes a per-block
            // signature (FNV-1a hash over id/kind/status/length) so the
            // collector wakes on RUNNING→SUCCESS transitions, new-block
            // appearances, kind flips, and per-token content growth alike.
            // Without blockSig the previous (lastIdx, growth, size, awaiting)
            // tuple missed several mid-loop transitions and the auto-follow
            // skipped scroll ticks during tool swaps.
            var growth: Long = m.content.length.toLong()
            var blockSig: Long = 1469598103934665603L // FNV-1a 64-bit offset basis
            for (b in m.toolBlocks) {
                growth += b.content.length.toLong()
                blockSig = blockSig xor b.id.hashCode().toLong()
                blockSig *= 1099511628211L
                blockSig = blockSig xor b.kind.hashCode().toLong()
                blockSig *= 1099511628211L
                blockSig = blockSig xor (b.toolStatus?.ordinal?.toLong() ?: -1L)
                blockSig *= 1099511628211L
                blockSig = blockSig xor b.content.length.toLong()
                blockSig *= 1099511628211L
            }
            ScrollFollowKey(
                lastIndex = effective.lastIndex,
                growth = growth,
                toolBlockCount = m.toolBlocks.size,
                awaiting = m.isAwaitingModelResponse,
                blockSig = blockSig,
            )
        }
            .filterNotNull()
            .distinctUntilChanged()
            .conflate()
            // [T-android-stream-grow-anim] Follow the bottom often enough that
            // the viewport never falls more than a fraction of one item behind.
            // Diagnostics with a 350ms sample showed GLIDE starting from
            // fIdx=1..5 — the viewport was whole items behind, and
            // animateScrollToItem across multiple items snaps most of the
            // distance instantly then animates only the last sliver, so it
            // read as "no animation". With the VM-side dual-path flush already
            // pacing content updates to 200–500ms, a 120ms scroll sample keeps
            // the viewport within the SAME item (fIdx=0, small fOff), where
            // animateScrollToItem is a genuine smooth glide. (The "accumulate
            // then glide" the user asked for now lives in the VM flush; here we
            // just keep up smoothly.)
            .sample(120L)
            .collect {
                if (!viewModel.isStreaming.value) return@collect
                // [T-android-scroll-policy] Reading owns the viewport: content
                // growth during a stream is the newest end growing at index 0,
                // and LazyListState's key anchoring holds the reader's row.
                if (!scrollPolicy.isPinned) return@collect
                if (listState.isScrollInProgress) return@collect
                val sinceInterrupt = System.currentTimeMillis() - lastInterruptMs
                if (sinceInterrupt < 1000L) return@collect
                // [T-android-stream-grow-anim] Frame-driven glide to the bottom.
                // animateScrollToItem(0) was the problem: when the viewport had
                // fallen >= 1 item behind (diagnostics showed fIdx=1..5 during
                // fast streams), it snaps most of the distance instantly and
                // animates only the final sliver — reading as "no animation".
                // Instead, scroll toward the bottom a bounded amount per frame
                // inside one scroll session until index 0 is fully pinned
                // (fIdx==0 && fOff==0). Every frame moves, so the whole catch-up
                // is visibly animated regardless of how many items behind we
                // are. We never measure item heights (the source of earlier
                // stutter) — we just step toward the bottom and stop when the
                // pin condition is met. In reverseLayout, the bottom (newest,
                // index 0) is the NEGATIVE scroll direction.
                if (listState.firstVisibleItemIndex != 0 ||
                    listState.firstVisibleItemScrollOffset != 0
                ) {
                    // [T-android-stream-grow-anim review] Cold start: item sizes
                    // not measured yet → avgItemSize==0 → the distance estimate
                    // is bogus and the glide would under-scroll. Snap instead;
                    // by the next sample the cache is warm and glides resume.
                    if (avgItemSize.value <= 0) {
                        tracedScrollToItem("LE(streaming-content)cold", 0, 0)
                        return@collect
                    }
                    // [T-android-stream-grow-anim] Ease-out frame-driven glide
                    // to the bottom. Each frame moves a fraction of the
                    // estimated remaining distance so the motion decelerates as
                    // it lands (curveEaseOut, the shape iOS uses for its 0.2s
                    // contentOffset animate). Two caps keep it smooth on the
                    // matched matters:
                    //   • per-frame step is capped well BELOW a typical
                    //     streaming fragment (~tens of px) so a single frame
                    //     can never leap a whole item — that leap was the
                    //     residual "frames=1 jump" in earlier diagnostics
                    //     (avg-estimated remaining over-shot, step hit the cap,
                    //     one frame crossed an item).
                    //   • a gentler 0.22 fraction + 14px floor stretches even a
                    //     short catch-up across several frames, so it always
                    //     reads as a glide rather than a hop.
                    // Distance uses the running average visible-item height (an
                    // aggregate, not a per-item delta, so no re-block noise).
                    val avg = avgItemSize.value.toFloat().coerceAtLeast(1f)
                    // Step ceiling: ~40% of the average item, so >= ~3 frames
                    // cross any one item. Bounded to a sane absolute window.
                    val stepCeil = (avg * 0.40f).coerceIn(28f, 80f)
                    runCatching {
                        listState.scroll {
                            var guard = 0
                            while (
                                (listState.firstVisibleItemIndex != 0 ||
                                    listState.firstVisibleItemScrollOffset != 0) &&
                                guard < 120
                            ) {
                                guard++
                                val remaining = listState.firstVisibleItemIndex * avg +
                                    listState.firstVisibleItemScrollOffset
                                val step = (remaining * 0.22f).coerceIn(14f, stepCeil)
                                // Negative = toward newest/bottom in reverseLayout.
                                val consumed = withFrameNanos { scrollBy(-step) }
                                if (consumed == 0f) break
                            }
                        }
                    }
                }
            }
    }
    // T256: stage-2/3 settle moved out of the per-token LE — runs once on
    // the stream-end edge (isAwaitingModelResponse stays false but the
    // assistant message growth rate drops to zero). Catches async
    // self-sizing of code blocks / tables / images that finish layout
    // beyond the last sample tick.
    // T-streaming-side-channel: derive streamingNowFlag from isStreaming
    // VM flow directly (turn-level signal) rather than reading per-token
    // assistant content, so this state doesn't tick during a stream.
    //
    // [T-android-scroll-policy] Rewritten: Pinned-only single re-pin. The
    // Reading branch is a HARD NO-OP — versions v6..v17 of cross-key restore
    // all made the symptom worse (commits cd08a334, 9873a3ed), and with
    // stable keys (FlatKeys) LazyColumn holds the reader's position
    // natively. The old LATE-REPIN second stage is gone: it existed to work
    // around the arm-net fights that no longer exist.
    val streamingNowFlag = isStreaming
    LaunchedEffect(streamingNowFlag) {
        if (streamingNowFlag) return@LaunchedEffect  // edge fires only on streaming→idle
        kotlinx.coroutines.delay(220)
        if (listState.isScrollInProgress) return@LaunchedEffect
        if (!scrollPolicy.isPinned) return@LaunchedEffect
        tracedScrollToItem("stream-end/AT-BOTTOM-RE-PIN", 0, 0)
    }
    // T170: when a user-initiated drag or fling settles away from the bottom
    // while still Pinned, flip into Reading. DragInteraction.Stop fires at
    // finger lift — before the fling carries the viewport off-bottom — so
    // the isScrollInProgress falling edge is the authoritative checkpoint.
    //
    // [T-android-scroll-policy] The old settle-after-interaction re-pin and
    // the position-driven arm-net are gone: every programmatic jump now
    // lands Reading/Pinned explicitly at its click site, so "jumped off-
    // bottom and nothing knows" cannot happen; and content growth during a
    // stream is not a gesture, so streaming is excluded here (the glide owns
    // the pinned viewport mid-stream).
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                if (!inProgress && !isNearBottom.value &&
                    !viewModel.isStreaming.value && scrollPolicy.isPinned
                ) {
                    val anchor = visibleTopAnchor()
                    scrollPolicy.rearmIfDraggedAway(anchor?.first, anchor?.second ?: 0)
                }
            }
    }
    // [T-android-scroll-drift-observer] Observation, never intervention. The
    // Reading mode bets the viewport's position stability on Compose's native
    // key anchoring (the stale-snapshot restore effect was deleted as a bug
    // source). This recorder makes that bet falsifiable: with
    // verboseScrollLogs flipped on, any drift between the Reading anchor's
    // recorded offset and the anchor row's live offset leaves a line in the
    // log — evidence on the device instead of a user report of
    // "翻历史时跳屏" with nothing to grep.
    LaunchedEffect(listState) {
        if (!verboseScrollLogs) return@LaunchedEffect
        snapshotFlow {
            val reading = scrollMode as? ScrollMode.Reading ?: return@snapshotFlow null
            val anchorKey = reading.anchorKey ?: return@snapshotFlow null
            val row = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchorKey }
                ?: return@snapshotFlow "anchor=$anchorKey OFF-SCREEN"
            "anchor=$anchorKey drift=${row.offset - reading.anchorOffset}px"
        }.collect { drift ->
            if (drift != null) AppLogger.debug("ScrollDrift", drift)
        }
    }

    // Reply-end focus preference must be declared before the streaming→idle
    // effect reads it. Kept near the focus effects rather than the bulk
    // appearance block below, otherwise Kotlin resolves the later local too
    // late for this effect.
    val appearancePrefsForFocus = remember { com.openminis.app.ui.settings.getAppearancePrefs(context) }
    var autoFocusAfterReply by remember {
        mutableStateOf(
            appearancePrefsForFocus.getBoolean(
                com.openminis.app.ui.settings.KEY_AUTO_FOCUS_AFTER_REPLY,
                true,
            ),
        )
    }

    // Entering or switching a conversation is navigation, not an input action.
    // Do not request composer focus here: doing so opens the IME on every new
    // draft/session visit. The user explicitly focuses the composer when ready.
    LaunchedEffect(sessionId) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    var sawStreamingThisVisit by remember(sessionId) { mutableStateOf(false) }
    // Reply-end focus is opt-in (Appearance → Auto-Focus After Reply). The
    // setting must be real: when enabled, refocus only on a streaming→idle
    // edge observed by this screen; entering an already-running session never
    // opens the IME by itself.
    LaunchedEffect(isStreaming, sessionId) {
        if (isStreaming) {
            sawStreamingThisVisit = true
            return@LaunchedEffect
        }
        if (!sawStreamingThisVisit) return@LaunchedEffect
        sawStreamingThisVisit = false
        if (!autoFocusAfterReply) return@LaunchedEffect
        // Let the final markdown reflow settle before taking focus so the IME
        // doesn't fight the stream-end layout pass.
        kotlinx.coroutines.delay(250)
        runCatching { inputFocusRequester.requestFocus() }
        keyboardController?.show()
    }

    // Show top-level error in snackbar (only for errors without an assistant message)
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // T-imgsize: surface composer-side image-budget actions (compress / drop)
    // via Snackbar. Each event is one user send; we emit at most two short
    // notices (compressed count + dropped count) so the user understands
    // why we touched their attachments before the provider would 413.
    LaunchedEffect(Unit) {
        viewModel.imageBudgetEvent.collect { ev ->
            if (ev.compressedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.image_budget_compressed, ev.compressedCount),
                )
            }
            if (ev.droppedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.image_budget_total_exceeded),
                )
            }
        }
    }

    // T-request-imgsize: surface request-level image-budget elisions
    // (older images compacted into text placeholders to fit the 25MB
    // request cap). Independent flow from the composer-side budget so
    // both can fire on the same turn without racing.
    LaunchedEffect(Unit) {
        viewModel.requestBudgetEvent.collect { plan ->
            if (plan.droppedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(
                        R.string.image_budget_request_elided,
                        plan.droppedCount,
                    ),
                )
            }
        }
    }

    val appearancePrefs = remember { com.openminis.app.ui.settings.getAppearancePrefs(context) }
    var messageFontLevel by remember { mutableStateOf(appearancePrefs.getInt(com.openminis.app.ui.settings.KEY_FONT_MESSAGE, 0)) }
    var chatInputLevel by remember { mutableStateOf(appearancePrefs.getInt(com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT, 0)) }
    var showFloatingToolBar by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_SHOW_FLOATING_TOOL_BAR, true)) }
    var showCompletedToolCards by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_SHOW_COMPLETED_TOOL_CARDS, false)) }
    var foldAiProcess by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_FOLD_AI_PROCESS, com.openminis.app.ui.settings.DEFAULT_FOLD_AI_PROCESS)) }
    // [T-composer-mic-toggle] Composer mic visibility (default OFF).
    var showMicButton by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_SHOW_MIC_BUTTON, com.openminis.app.ui.settings.DEFAULT_SHOW_MIC_BUTTON)) }
    var showSubAgentBar by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_SHOW_SUBAGENT_BAR, true)) }
    // Live roster of running sub-agents. The top bar renders these; the session
    // page hides a sub-agent transcript card only while its run is in this set,
    // so a finished run stays visible as a card instead of vanishing.
    val subAgentMembers by com.openminis.app.service.SubAgentActivityTracker.members.collectAsState()
    val activeSubAgentToolIds = remember(subAgentMembers) {
        subAgentMembers.mapTo(mutableSetOf()) { it.parentToolId }
    }
    // T-chat-title-pill: live-toggled by Settings → Appearance and by
    // `minis-config set appearance.show_chat_title …`. Default ON.
    var showChatTitlePill by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE, true)) }
    // T-chat-title-pill-edit: state for the in-chat edit-title sheet (the
    // exact same SessionEditSheet hosted by the session list home screen,
    // reused via `internal` visibility — no duplicate UI). Populated by an
    // async repo lookup once the user taps the title pill.
    var editingSession by remember { mutableStateOf<com.openminis.app.data.db.ChatSessionEntity?>(null) }
    val appearanceLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(appearancePrefs, appearanceLifecycleOwner) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        fun applyAppearancePrefs(sp: android.content.SharedPreferences) {
            messageFontLevel = sp.getInt(com.openminis.app.ui.settings.KEY_FONT_MESSAGE, 0)
            chatInputLevel = sp.getInt(com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT, 0)
            showFloatingToolBar = sp.getBoolean(com.openminis.app.ui.settings.KEY_SHOW_FLOATING_TOOL_BAR, true)
            showCompletedToolCards = sp.getBoolean(com.openminis.app.ui.settings.KEY_SHOW_COMPLETED_TOOL_CARDS, false)
            foldAiProcess = sp.getBoolean(com.openminis.app.ui.settings.KEY_FOLD_AI_PROCESS, com.openminis.app.ui.settings.DEFAULT_FOLD_AI_PROCESS)
            showSubAgentBar = sp.getBoolean(com.openminis.app.ui.settings.KEY_SHOW_SUBAGENT_BAR, true)
            autoFocusAfterReply = sp.getBoolean(com.openminis.app.ui.settings.KEY_AUTO_FOCUS_AFTER_REPLY, true)
            showChatTitlePill = sp.getBoolean(com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE, true)
        }
        fun onMain(block: () -> Unit) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
            else mainHandler.post(block)
        }
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            onMain {
                when (key) {
                    com.openminis.app.ui.settings.KEY_FONT_MESSAGE -> messageFontLevel = sp.getInt(key, 0)
                    com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT -> chatInputLevel = sp.getInt(key, 0)
                    com.openminis.app.ui.settings.KEY_SHOW_FLOATING_TOOL_BAR -> showFloatingToolBar = sp.getBoolean(key, true)
                    com.openminis.app.ui.settings.KEY_SHOW_COMPLETED_TOOL_CARDS -> showCompletedToolCards = sp.getBoolean(key, false)
                    com.openminis.app.ui.settings.KEY_FOLD_AI_PROCESS -> foldAiProcess = sp.getBoolean(key, com.openminis.app.ui.settings.DEFAULT_FOLD_AI_PROCESS)
                    com.openminis.app.ui.settings.KEY_SHOW_MIC_BUTTON -> showMicButton = sp.getBoolean(key, com.openminis.app.ui.settings.DEFAULT_SHOW_MIC_BUTTON)
                    com.openminis.app.ui.settings.KEY_SHOW_SUBAGENT_BAR -> showSubAgentBar = sp.getBoolean(key, true)
                    com.openminis.app.ui.settings.KEY_AUTO_FOCUS_AFTER_REPLY -> autoFocusAfterReply = sp.getBoolean(key, true)
                    com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE -> showChatTitlePill = sp.getBoolean(key, true)
                    null -> applyAppearancePrefs(sp)
                }
            }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                applyAppearancePrefs(appearancePrefs)
            }
        }
        appearancePrefs.registerOnSharedPreferenceChangeListener(listener)
        appearanceLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            appearancePrefs.unregisterOnSharedPreferenceChangeListener(listener)
            appearanceLifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val markdownFontScale = com.openminis.app.ui.settings.fontScaleForLevel(messageFontLevel)
    val chatInputFontScale = com.openminis.app.ui.settings.fontScaleForLevel(chatInputLevel)

    val previewFileOrBrowser = remember(viewModel, onPreviewAttachment) {
        { item: com.openminis.app.ui.sandbox.FileItem ->
            if (item.isHtmlFile) {
                viewModel.openBrowserSheetForUrl("file://${item.file.absolutePath}")
            } else {
                onPreviewAttachment(item)
            }
        }
    }
    // Pinned-shortcut deep link: minis://session/<id>/<resource-path>
    // consumes here on first composition iff this screen is showing the
    // matching session; opens the HTML in BrowserSheet. Pending state is
    // left untouched when a different chat is on
    // screen so the right ChatScreen instance still consumes it later.
    LaunchedEffect(sessionId) {
        val pending = com.openminis.app.deeplink.DeepLinkCoordinator
            .pendingHtmlPreview.value ?: return@LaunchedEffect
        if (pending.sessionId != sessionId) return@LaunchedEffect
        com.openminis.app.deeplink.DeepLinkCoordinator.consumePendingHtmlPreview()
        val absPath = "/var/minis" + pending.resourcePath
        val file = java.io.File(absPath)
        if (!file.exists()) {
            com.openminis.app.logging.AppLogger.warning(
                "ChatScreen",
                "pinned HTML preview path missing: $absPath",
            )
            return@LaunchedEffect
        }
        viewModel.openBrowserSheetForUrl("file://${file.absolutePath}")
    }
    // T-imgswipe-4f446d83: replace previous single-image preview state with a
    // gallery (list + start index) so callers can pass sibling images (input
    // chip row, message attachments, file-browser dir contents). Single-image
    // taps still work — they pass a 1-item list.
    var previewImageGallery by remember {
        mutableStateOf<Pair<List<com.openminis.app.ui.components.ImageGalleryItem>, Int>?>(null)
    }
    // Video links from chat go through MinisFullscreenVideoPlayer rather than
    // FilePreviewScreen → InlineVideoPlayer. The inline player wraps a bare
    // VideoView with an anchored MediaController and never starts playback,
    // so a tap on an mp4 link rendered as a black surface until the user
    // happened to tap again to surface the controller. The fullscreen player
    // auto-starts on prepared, has a built-in scrubber + play/pause, and an
    // onError listener so failures actually log instead of silently blanking.
    var previewVideoFile by remember { mutableStateOf<java.io.File?>(null) }
    // T-pwa-2: long-press on an HTML attachment chip opens the
    // "Add to Home Screen" sheet for that attachment.
    var webAppSheetTarget by remember { mutableStateOf<InputAttachment?>(null) }
    val urlClickHandler = remember<(String) -> Unit>(viewModel) {
        { url ->
            // Pass the current session id so `minis://attachments/...` resolves
            // against this chat's session directory rather than whichever
            // session booted its PRoot shell most recently (which is what
            // the global bindMounts map would answer).
            when (val action = ChatLinkResolver.resolve(url, viewModel.currentSessionId, context)) {
                is ChatLinkAction.DeepLink -> ChatLinkResolver.dispatchDeepLink(context, url)
                is ChatLinkAction.SandboxFile -> {
                    when {
                        action.item.isImageFile -> {
                            // Single image — caption = filename. Sibling
                            // collection from markdown context is not
                            // plumbed here (iOS does cross-session
                            // assistant images via fingerprint).
                            previewImageGallery = listOf(
                                com.openminis.app.ui.components.ImageGalleryItem(
                                    model = action.item.file,
                                    caption = action.item.name,
                                ),
                            ) to 0
                        }
                        action.item.isVideoFile -> previewVideoFile = action.item.file
                        // Sandbox HTML (snake_game.html etc.) opens in
                        // browser_use / BrowserSheet — same WebView as the agent.
                        action.item.isHtmlFile ->
                            viewModel.openBrowserSheetForUrl("file://${action.item.file.absolutePath}")
                        // T279: route through the NavHost FILE_PREVIEW destination
                        // (same path as user-bubble attachments and "Browse Chat Files")
                        // so FilePreviewScreen inherits the Activity's edge-to-edge
                        // window setup. The previous in-place Dialog wrapper had
                        // its own Window without enableEdgeToEdge, painting the
                        // platform default scrim on the status / nav bars.
                        else -> previewFileOrBrowser(action.item)
                    }
                }
                is ChatLinkAction.ExternalApp ->
                    // [T-android-user-initiated-scheme-dispatch] The user
                    // tapped this link in a chat message — dispatch whatever
                    // scheme it carries.
                    com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                        .handle(
                            context,
                            action.url,
                            com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                                .Origin.USER_INITIATED,
                        )
                // http(s)/about: open in browser_use (BrowserSheet).
                is ChatLinkAction.Web -> viewModel.openBrowserSheetForUrl(action.url)
            }
        }
    }

    // Auto-present the in-app preview when a shell tool's stdout emits an
    // OSC MinisOpenURL marker (via /usr/local/bin/minis-open). The broker is
    // populated by ChatViewModel's shell lineCallback; forwarding the URL
    // into `urlClickHandler` routes it exactly like a chat-link tap —
    // http(s)/about → BrowserSheet, minis:// deep links → DeepLinkHandler,
    // minis://<host>/<path> → in-app file preview by extension.
    val pendingMinisOpenUrl by com.openminis.app.terminal.MinisOpenUrlBroker.pendingUrl
        .collectAsState()
    val minisOpenTerminalVisible by com.openminis.app.terminal.MinisOpenUrlBroker.terminalVisible
        .collectAsState()
    LaunchedEffect(pendingMinisOpenUrl, minisOpenTerminalVisible) {
        val url = pendingMinisOpenUrl ?: return@LaunchedEffect
        // The fullscreen TerminalScreen owns the broker while it's up —
        // let it present its own web preview (mirrors iOS ISHTerminalView)
        // so we don't try to open a sheet on a covered ChatScreen.
        if (minisOpenTerminalVisible) return@LaunchedEffect
        urlClickHandler(url.toString())
        com.openminis.app.terminal.MinisOpenUrlBroker.consume()
    }

    // [T-android-markdown-image-gallery-cross-message] Collect every
    // `![alt](src)` markdown image emitted by any assistant message in the
    // current windowed view, in chronological order, then open the paged
    // ImageGalleryViewer positioned at the tapped image. Mirrors iOS
    // AIChatView.handleMarkdownImageTap (AIChatView.swift:2082). The regex
    // matches the standard inline image form; tool-block content stays
    // untouched (toolBlocks live in a separate AssistantBlock list, not
    // in `content`). Video/audio extensions are filtered out so the gallery
    // only contains still images. Resolution of `minis://` → host File is
    // deferred to the gallery's Coil model — Coil's MinisImageFetcher walks
    // the same session-aware resolver we use for inline rendering.
    val markdownImageTapHandler = remember<(String, String) -> Unit>(messages, sessionId) {
        handler@{ tappedMessageId, tappedUrl ->
            val imageRegex = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")
            data class Ref(val messageId: String, val source: String, val title: String)
            val refs = mutableListOf<Ref>()
            for (msg in messages) {
                if (msg.role != "assistant") continue
                val content = msg.content
                if (content.isEmpty()) continue
                for (m in imageRegex.findAll(content)) {
                    val alt = m.groupValues.getOrNull(1).orEmpty()
                    val src = m.groupValues.getOrNull(2).orEmpty()
                    if (src.isEmpty()) continue
                    val pathPart = src.substringBefore('?').substringBefore('#')
                    val ext = pathPart.substringAfterLast('.', "").lowercase()
                    // Skip non-image media so the gallery stays still-image only,
                    // matching iOS minisVideoExtensions / minisAudioExtensions.
                    if (ext in setOf("mp4", "mov", "avi", "mkv", "webm",
                                     "mp3", "wav", "aac", "flac", "ogg", "m4a")) continue
                    val title = alt.ifEmpty { pathPart.substringAfterLast('/').ifEmpty { src } }
                    refs.add(Ref(msg.id, src, title))
                }
            }
            if (refs.isEmpty()) {
                // Defensive: tap arrived for a URL that isn't in the visible
                // window (compacted away, just deleted, etc.). Fall back to
                // the single-item URL handler so the user still sees the
                // tapped image rather than swallowing the tap silently.
                urlClickHandler(tappedUrl)
                return@handler
            }
            val startIndex = refs.indexOfFirst { it.messageId == tappedMessageId && it.source == tappedUrl }
                .takeIf { it >= 0 }
                ?: refs.indexOfFirst { it.source == tappedUrl }.takeIf { it >= 0 }
                ?: 0
            val items = refs.map { ref ->
                // Resolve minis://... / file:// / /abs → host File so Coil
                // doesn't have to re-walk PRootKernel for every page swipe.
                // Falls back to the raw URL string when resolution misses —
                // AsyncImage will route it through MinisImageFetcher anyway.
                val resolved = resolveMdMediaFile(context, ref.source, sessionId)
                com.openminis.app.ui.components.ImageGalleryItem(
                    model = resolved ?: ref.source,
                    caption = ref.title,
                )
            }
            previewImageGallery = items to startIndex
        }
    }

    CompositionLocalProvider(
        LocalBrowserTabPool provides viewModel.browserTabPool,
        LocalMarkdownFontScale provides markdownFontScale,
        LocalMarkdownUrlClickHandler provides urlClickHandler,
        LocalMarkdownImageTapHandler provides markdownImageTapHandler,
        // Route markdown media resolution through this chat's session so
        // minis://attachments/* lookups don't rely on the global bindMounts
        // map (which is last-writer-wins across sessions).
        LocalMarkdownSessionId provides sessionId,
        // [▶ 运行] 代码块执行链路：动作经 LocalMarkdownCodeRunner 注入
        // （StreamingMarkdownRenderBlock / MarkdownText 的按钮消费），
        // 状态（运行中集合）经 LocalCodeBlockRunState 注入。一次 provide
        // 覆盖聊天流全部渲染路径，免去逐 StreamingMarkdownText 调用点穿参。
        LocalMarkdownCodeRunner provides viewModel::runCodeBlockInline,
        LocalCodeBlockRunState provides viewModel.codeBlockRunState.collectAsState().value,
    ) {
    Scaffold(
        containerColor = ChatColors.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            MinisCenterTopBar(
                title = {
                    // iOS-style centered layout: "Minis Ultra" + group row + provider·model row
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val noFontPad = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                        )
                        // Fallback pulse animation (iOS: 3× red pulse on model switch)
                        val fallbackTrigger by viewModel.fallbackTrigger.collectAsState()
                        val fallbackPulseAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
                        LaunchedEffect(fallbackTrigger) {
                            if (fallbackTrigger == 0) return@LaunchedEffect
                            repeat(3) {
                                fallbackPulseAlpha.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(350))
                                fallbackPulseAlpha.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(350))
                            }
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Red.copy(alpha = 0.35f * fallbackPulseAlpha.value))
                                // [T-android-topbar-shrink] vertical 4dp→2dp.
                                // Combined with the expandedHeight drop below,
                                // closes the dead-space gap between the model
                                // name row and the TopAppBar bottom edge that
                                // T-topbar-model-row-clip's 76dp overshoot left
                                // behind. Horizontal 32dp keeps the fallback
                                // pulse highlight comfortably padded around
                                // the longest title.
                                .padding(horizontal = 32.dp, vertical = 2.dp),
                        ) {
                            // Nav title: current session title when one
                            // exists and the toggle is on, else fall back to
                            // the Soul name (matches the input placeholder
                            // "Message <SoulName>"), then to app_name
                            // ("Minis Ultra") as the terminal fallback.
                            // Tap opens the same SessionEditSheet used from
                            // the session list — drafts return null from
                            // loadSessionEntity so the sheet stays closed.
                            // SoulStore.cachedMetadata is the same source the
                            // input placeholder uses (see ~line 3581), so
                            // soul renames in Soul Settings reflect here live.
                            val topBarSoul by com.openminis.app.agent.SoulStore
                                .cachedMetadata.collectAsState()
                            val displayTitle = when {
                                showChatTitlePill
                                    && sessionTitle.isNotBlank()
                                    && sessionTitle != "New Chat" -> sessionTitle
                                topBarSoul.name.isNotBlank() -> topBarSoul.name
                                else -> stringResource(R.string.app_name)
                            }
                            Text(
                                text = displayTitle,
                                fontSize = 16.sp,
                                lineHeight = 19.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ChatColors.primaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = noFontPad,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        coroutineScope.launch {
                                            editingSession = viewModel.loadSessionEntity()
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                            // Model picker subtitle: green dot + group +
                            // provider/model. Tap opens the model picker —
                            // separated from the title above so tapping the
                            // title rows opens the rename sheet instead.
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    // [T-android-modelpicker-stuck-ripple] Own the
                                    // interaction source so the press can be
                                    // released explicitly. Opening the picker
                                    // sheet puts a modal window over this row, so
                                    // the pointer's UP never reaches the clickable:
                                    // Compose emits PressInteraction.Press with no
                                    // matching Release and the ripple stays lit
                                    // behind the sheet — still there after the
                                    // sheet closes, reading as a permanent grey
                                    // highlight on the title bar.
                                    .clickable(
                                        interactionSource = modelPickerInteraction,
                                        indication = ripple(),
                                    ) { showModelPicker = true }
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                // Line 1: green dot + group name + dropdown arrow (iOS: "● Default ⌄")
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(
                                                if (modelName.isNotEmpty()) Color(0xFF34C759) else Color(0xFFFF9500),
                                                CircleShape,
                                            ),
                                    )
                                    // T-android-topbar-group-name-fallback:
                                    // _selectedGroupName is empty during the
                                    // brief window before loadSession's group
                                    // resolve runs, or whenever a binding
                                    // resolve fails. Falling straight to the
                                    // "Default" badge string masks the
                                    // active group's real name (e.g. the
                                    // onboarding-created "Default Models" or
                                    // any user-renamed group). Insert a real
                                    // fallback chain: collected VM value →
                                    // active/default group name from the live
                                    // config → terminal badge string. Mirrors
                                    // the #476 TopBar title fallback pattern
                                    // (commit b4c88775).
                                    //
                                    // [T-android-group-resolve-skip-uncredentialed]
                                    // ...but only while a group is ACTUALLY
                                    // bound. This chain used to run
                                    // unconditionally, so a session that failed
                                    // to resolve its group — and was therefore
                                    // running on a model from the new-chat
                                    // default chain, unrelated to any group —
                                    // still displayed the default group's name.
                                    // The header then contradicted the model
                                    // line right below it and made a real
                                    // routing failure read as normal operation,
                                    // which is what made that bug hard to spot.
                                    // Mirrors iOS, which keys the group glyph
                                    // off the binding (`isGroupBound`) rather
                                    // than off a name lookup.
                                    val groupNameDisplay = selectedGroupName.ifEmpty {
                                        if (selectedGroupId == null) {
                                            ""
                                        } else {
                                            val defaultGroupId = providerRepository.defaultPrimaryGroupId
                                            availableGroups.firstOrNull { it.id == defaultGroupId }?.name
                                                ?: stringResource(R.string.model_picker_default_badge)
                                        }
                                    }
                                    // Drop the whole affordance when no group is
                                    // bound — an empty label would still leave
                                    // a dangling chevron pointing at nothing.
                                    if (groupNameDisplay.isNotEmpty()) {
                                        Text(
                                            text = groupNameDisplay,
                                            fontSize = 12.sp,
                                            lineHeight = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ChatColors.secondaryText,
                                            maxLines = 1,
                                            style = noFontPad,
                                        )
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = ChatColors.tertiaryText,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                                // Line 2: "provider · model" (iOS: "MiniMax ·
                                // MiniMax-M2.7") + the thinking-level badge laid
                                // out as a Row of two SEPARATE tappable siblings
                                // (mirrors iOS AIChatView row-2 HStack).
                                //
                                // [T-android-thinking-badge-navbar] Gesture
                                // separation: the whole subtitle Column above owns
                                // `clickable { showModelPicker = true }`, so a tap
                                // on the model text still opens the model picker.
                                // The badge declares its OWN `clickable` (see
                                // ThinkingLevelBadge), and in Compose the innermost
                                // clickable consumes the down/up events — so a tap
                                // that lands on the badge opens the thinking sheet
                                // and never bubbles up to the Column's model-picker
                                // handler. Two hit targets, zero gesture conflict,
                                // no pointerInput plumbing needed.
                                //
                                // Sizing: the model text takes `weight(1f, fill =
                                // false)` so it truncates first (Ellipsis) when the
                                // navbar is narrow; the badge has no weight, so it
                                // keeps its intrinsic width and always renders in
                                // full — the level label never gets clipped.
                                if (providerName.isNotEmpty() || modelName.isNotEmpty()) {
                                    val thinkingLevelBadgeState by viewModel.thinkingLevel.collectAsState()
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        // [T-codex-fast-mode] ⚡ badge ahead of the
                                        // resolved model name — small orange circle
                                        // + white bolt, shown only while Fast Mode
                                        // is enabled AND the active model is
                                        // eligible (iOS 9e3c76ef row-3 placement,
                                        // 09944220 9pt sizing).
                                        val fastBadgeEligible by viewModel.showFastModeToggle.collectAsState()
                                        val fastBadgeOn by viewModel.fastModeEnabled.collectAsState()
                                        if (fastBadgeEligible && fastBadgeOn) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size(11.dp)
                                                    .background(Color(0xFFFF9500), CircleShape),
                                            ) {
                                                Icon(
                                                    Icons.Default.Bolt,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(9.dp),
                                                )
                                            }
                                        }
                                        Text(
                                            text = if (providerName.isNotEmpty() && modelName.isNotEmpty()) {
                                                "$providerName · $modelName"
                                            } else {
                                                modelName.ifEmpty { providerName }
                                            },
                                            fontSize = 11.sp,
                                            lineHeight = 13.sp,
                                            color = ChatColors.tertiaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = noFontPad,
                                            // Yield first when space is tight; the
                                            // badge to the right stays intrinsic.
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        // Show the badge whenever thinking is on,
                                        // and ALSO when it's Off but the active
                                        // model supports deep thinking (iOS
                                        // parity, e6bd75efc): the icon + "Off"
                                        // pill is then a discoverable tap target
                                        // for enabling thinking via the level
                                        // sheet. The Off pill is gated on
                                        // currentModelSupportsReasoning so
                                        // non-reasoning models don't grow a dead
                                        // toggle; an enabled level still shows
                                        // unconditionally (user may have opted in
                                        // on an unknown-capability model).
                                        if (viewModel.availableThinkingLevels.isNotEmpty() &&
                                            (
                                                thinkingLevelBadgeState.isEnabled ||
                                                    viewModel.currentModelSupportsReasoning
                                            )
                                        ) {
                                            ThinkingLevelBadge(
                                                level = thinkingLevelBadgeState,
                                                onClick = { showThinkingLevelSheet = true },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    // [T-android-tablet-split] See `isTwoPane`.
                    // [P1-6] Compact width: the slot becomes the session-drawer
                    // button — switching chats no longer requires popping back
                    // to the full list screen. The back arrow survives only for
                    // callers without a drawer; the system back gesture still
                    // pops to the list either way.
                    if (!isTwoPane && onOpenSessionDrawer != null) {
                        IconButton(onClick = onOpenSessionDrawer) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = stringResource(R.string.chat_show_sidebar),
                            )
                        }
                    } else if (!isTwoPane) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    } else if (onToggleSidebar != null) {
                        // [T-android-tablet-sidebar-collapse] The slot the back
                        // arrow vacates in two-pane becomes the sidebar toggle.
                        //
                        // ONE glyph for both states — the list icon, meaning
                        // "the session list", with the action stated in the
                        // content description instead.
                        //
                        // A directional chevron was tried for the expanded
                        // state and is wrong here: this is the slot that used
                        // to hold the back arrow, so a leading chevron reads as
                        // "go back" — precisely the meaning two-pane removed.
                        // Swapping the glyph on toggle also makes the control
                        // look like two different buttons rather than one
                        // switch. A stable icon whose accessible label changes
                        // is both clearer and honest about what it targets.
                        //
                        // `Menu` rather than `List`, which this first used.
                        // List draws a bulleted list — dots plus rules — whose
                        // ink sat high and left in the 24dp box (measured 41x23
                        // px with its mass above centre), so it read as a small
                        // mark floating above the ⋮ at the other end of this
                        // same bar. Menu's three full-width bars fill the box
                        // symmetrically and optically centre against it; both
                        // glyphs now share a baseline to the pixel. Menu is
                        // also the conventional sidebar-toggle icon.
                        //
                        // [T-android-split-toggle-align] Two corrections, both
                        // measured on a Mate Pad against the SESSION LIST's
                        // toolbar rather than this bar's own ⋮ — the toggle sits
                        // hard against the pane seam, so the icons it is read
                        // beside are the list's Schedule/Terminal, not the
                        // kebab at the far end of this bar. Aligned only to the
                        // kebab, it measured 8px shorter and 3.5px lower than
                        // its actual neighbours.
                        //
                        // 1. `offset(y = -2.dp)`: this bar is 68dp (see
                        //    expandedHeight below — sized for the 3-row title
                        //    and NOT reducible without re-triggering
                        //    T-topbar-model-row-clip), while the list's bar is
                        //    M3's default 64dp. A TopAppBar centres its
                        //    navigation icon in its OWN height, so the 4dp
                        //    difference put this glyph 2dp below the list's row.
                        //    Offsetting by half the delta lands it on the list's
                        //    baseline while leaving the taller bar intact.
                        //
                        // 2. `size(28.dp)`: Menu's three bars ink only ~12 of
                        //    their 24dp viewport (bars at y=6/11/16), where the
                        //    circular Schedule and boxy Terminal fill ~20 of
                        //    theirs. At a matched box size Menu therefore reads
                        //    markedly lighter and smaller. Scaling the box to
                        //    28dp brings its ink to ~14dp, closing most of the
                        //    optical gap. The IconButton's 48dp touch target is
                        //    unchanged, so this is purely visual weight.
                        IconButton(
                            onClick = onToggleSidebar,
                            modifier = Modifier.offset(y = (-2).dp),
                        ) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = stringResource(
                                    if (sidebarCollapsed) {
                                        R.string.chat_show_sidebar
                                    } else {
                                        R.string.chat_hide_sidebar
                                    },
                                ),
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                },
                actions = {
                    // [P0-6] High-frequency entries promoted out of the "..."
                    // menu into direct icons when the bar has room (≥600dp —
                    // below that the centered two-line title needs the width;
                    // the menu's 快捷 section carries them on phones). Same
                    // handlers as the menu items — one funnel per action.
                    if (shouldShowTopBarQuickActions()) {
                        IconButton(onClick = onOpenTerminal) {
                            Icon(Icons.Default.Terminal, contentDescription = stringResource(R.string.chat_menu_open_terminal))
                        }
                        IconButton(onClick = { viewModel.toggleBrowserSheet() }) {
                            Icon(Icons.Default.Language, contentDescription = stringResource(R.string.chat_menu_open_browser))
                        }
                        IconButton(onClick = onBrowseChatFiles) {
                            Icon(Icons.Default.Description, contentDescription = stringResource(R.string.chat_menu_browse_chat_files))
                        }
                    }
                    // iOS: "..." circle button → dropdown menu
                    Box {
                        IconButton(onClick = { showChatMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more))
                        }
                        MinisMenu(
                            expanded = showChatMenu,
                            onDismissRequest = { showChatMenu = false },
                        ) {
                            // [T-android-memory-enabled-minisconfig] Gate the
                            // "Memories in Session" item below on the session's
                            // live memoryEnabled — when memory is off the entry
                            // disappears, consistent with the per-session gating
                            // of the memory_get / memory_write tools and the
                            // system-prompt injection.
                            val menuMemoryEnabled by viewModel.memoryEnabled.collectAsState()
                            val sessionPerm by viewModel.permissionMode.collectAsState()
                            val yoyoOn = sessionPerm.isYoyo()
                            // [P0-6] 快捷：三个高频入口置顶。宽窗口（≥600dp）它们
                            // 已是顶栏直接图标（shouldShowTopBarQuickActions），
                            // 菜单里不再重复；窄屏由本节承载。
                            if (!shouldShowTopBarQuickActions()) {
                                MinisMenuSectionHeader(stringResource(R.string.chat_menu_section_quick))
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_menu_open_terminal)) },
                                    onClick = {
                                        showChatMenu = false
                                        onOpenTerminal()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Terminal, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_menu_open_browser)) },
                                    onClick = {
                                        showChatMenu = false
                                        viewModel.toggleBrowserSheet()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Language, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_menu_browse_chat_files)) },
                                    onClick = {
                                        showChatMenu = false
                                        onBrowseChatFiles()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Description, contentDescription = null)
                                    },
                                )
                            }
                            // [P0-6] 会话段：本会话的行为开关。
                            MinisMenuSectionHeader(stringResource(R.string.chat_menu_section_session))
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (yoyoOn) stringResource(R.string.chat_menu_perm_yoyo)
                                        else stringResource(R.string.chat_menu_perm_ask),
                                    )
                                },
                                onClick = {
                                    viewModel.setSessionPermissionMode(
                                        if (yoyoOn) PermissionMode.ASK else PermissionMode.ALLOW_ALL,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (yoyoOn) Icons.Filled.Bolt else Icons.Filled.Shield,
                                        contentDescription = null,
                                    )
                                },
                                trailingIcon = {
                                    SettingsSwitch(
                                        checked = yoyoOn,
                                        onCheckedChange = { on ->
                                            viewModel.setSessionPermissionMode(
                                                if (on) PermissionMode.ALLOW_ALL else PermissionMode.ASK,
                                            )
                                        },
                                    )
                                },
                            )
                            val groupChatOn by viewModel.groupChatEnabled.collectAsState()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_group_chat)) },
                                onClick = { viewModel.setGroupChatEnabled(!groupChatOn) },
                                leadingIcon = { Icon(Icons.Default.Forum, contentDescription = null) },
                                trailingIcon = {
                                    // The row already toggles. A live switch here
                                    // also consumes the tap and flips the flag
                                    // back off, so @ keeps opening skill files.
                                    SettingsSwitch(
                                        checked = groupChatOn,
                                        onCheckedChange = null,
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_end_group_chat)) },
                                enabled = groupChatOn,
                                onClick = {
                                    showChatMenu = false
                                    viewModel.endGroupChat()
                                },
                                leadingIcon = { Icon(Icons.Default.Stop, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_share_card)) },
                                onClick = {
                                    showChatMenu = false
                                    showCardShareDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Share, contentDescription = null)
                                },
                            )
                            // [P0-6] 工具段：会话内的扩展面。
                            MinisMenuSectionHeader(stringResource(R.string.chat_menu_section_tools))
                            // [P2-git-panel] Git panel — desktop-style git UI
                            // over the session workspace.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_git_panel)) },
                                onClick = {
                                    showChatMenu = false
                                    onOpenGitPanel()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.MergeType, contentDescription = null)
                                },
                            )
                            // Session Skills (iOS parity)
                            if (skillRepository != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.session_skills_title)) },
                                    onClick = {
                                        showChatMenu = false
                                        showSkillsSheet = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Build, contentDescription = null)
                                    },
                                )
                            }
                            // [T-mcp-integration-android] MCPs in Session, next to Skills.
                            if (mcpRepository != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.session_mcps_title)) },
                                    onClick = {
                                        showChatMenu = false
                                        showMcpsSheet = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Extension, contentDescription = null)
                                    },
                                )
                            }
                            // Session Memory (iOS parity)
                            if (memoryRepository != null && menuMemoryEnabled) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.session_memory_title)) },
                                    onClick = {
                                        showChatMenu = false
                                        viewModel.toggleMemorySheet()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Psychology, contentDescription = null)
                                    },
                                )
                            }
                            // [P0-6] 设置段：模型调用与会话开销类开关。增强缓存/
                            // 极速模式保留在此而非移入系统设置页：它们的可见性
                            // 由当前会话的模型/提供方决定（showFastModeToggle、
                            // showEnhancedCacheToggle），设置页没有会话上下文。
                            MinisMenuSectionHeader(stringResource(R.string.chat_menu_section_settings))
                            // Token Usage (iOS parity)
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_token_usage)) },
                                onClick = {
                                    showChatMenu = false
                                    showTokenUsageSheet = true
                                },
                                leadingIcon = {
                                    val contextUsage by viewModel.contextUsage.collectAsState()
                                    ContextRing(usage = contextUsage)
                                },
                            )
                            // Enhanced Cache (iOS parity, commit 57aaf122):
                            // 1-hour Anthropic cache TTL. Only shown for the
                            // official Anthropic API (not relays / other
                            // providers) — showEnhancedCacheToggle recomputes on
                            // model/provider switch. First enable prompts a
                            // one-time extra-billing confirmation.
                            val showEnhancedCache by viewModel.showEnhancedCacheToggle.collectAsState()
                            val enhancedCacheOn by viewModel.enhancedCacheEnabled.collectAsState()
                            if (showEnhancedCache) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_menu_enhanced_cache)) },
                                    onClick = {
                                        if (enhancedCacheOn) {
                                            viewModel.setEnhancedCacheEnabled(false)
                                        } else if (viewModel.isEnhancedCacheConfirmed()) {
                                            viewModel.setEnhancedCacheEnabled(true)
                                        } else {
                                            showChatMenu = false
                                            showEnhancedCacheDialog = true
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Bolt, contentDescription = null)
                                    },
                                    trailingIcon = {
                                        SettingsSwitch(
                                            checked = enhancedCacheOn,
                                            onCheckedChange = {
                                                if (enhancedCacheOn) {
                                                    viewModel.setEnhancedCacheEnabled(false)
                                                } else if (viewModel.isEnhancedCacheConfirmed()) {
                                                    viewModel.setEnhancedCacheEnabled(true)
                                                } else {
                                                    showChatMenu = false
                                                    showEnhancedCacheDialog = true
                                                }
                                            },
                                        )
                                    },
                                )
                            }
                            // [T-codex-fast-mode] Fast Mode (iOS parity,
                            // fb671083 + 838ba929): shown when the active model
                            // is a gpt-family model served through the
                            // Responses path (useResponsesAPI instance or Codex
                            // OAuth). App-level persisted toggle; while on, the
                            // Responses body carries service_tier="priority"
                            // (≈1.5x faster at 2x credit burn on the ChatGPT
                            // subscription) and the nav model row shows a ⚡
                            // badge. Sits next to Enhanced Cache — both are
                            // model-invocation controls (iOS 09944220 grouping).
                            val showFastMode by viewModel.showFastModeToggle.collectAsState()
                            val fastModeOn by viewModel.fastModeEnabled.collectAsState()
                            if (showFastMode) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_menu_fast_mode)) },
                                    onClick = { viewModel.setFastModeEnabled(!fastModeOn) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Bolt, contentDescription = null)
                                    },
                                    trailingIcon = {
                                        SettingsSwitch(
                                            checked = fastModeOn,
                                            onCheckedChange = { viewModel.setFastModeEnabled(it) },
                                        )
                                    },
                                )
                            }
                            // Auto Compact: app-level persisted toggle mirroring
                            // iOS `autoCompactEnabled`. On → crossing the
                            // compact threshold before a send compacts silently
                            // and sends; off → the user is asked first. Lives
                            // beside Fast Mode because both are model-invocation
                            // controls the user flips mid-conversation.
                            val autoCompactOn by viewModel.autoCompactEnabled.collectAsState()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_auto_compact)) },
                                onClick = { viewModel.setAutoCompactEnabled(!autoCompactOn) },
                                leadingIcon = {
                                    Icon(Icons.Default.Compress, contentDescription = null)
                                },
                                trailingIcon = {
                                    SettingsSwitch(
                                        checked = autoCompactOn,
                                        onCheckedChange = { viewModel.setAutoCompactEnabled(it) },
                                    )
                                },
                            )
                            // T287: debug-only crash trigger so the user can verify
                            // ACRA/native crash log generation (T283). Throws a
                            // RuntimeException from the click handler — the
                            // uncaught-exception handler catches it and writes
                            // a crash-<stamp>.log under filesDir/logs/.
                            if (BuildConfig.DEBUG) {
                                MinisMenuDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.debug_trigger_crash_menu),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        showChatMenu = false
                                        throw RuntimeException(
                                            "Debug crash triggered by user (T287)",
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.BugReport,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                )
                            }
                        }
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = androidx.compose.material3.TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = ChatColors.background.copy(alpha = 0.92f),
                    scrolledContainerColor = ChatColors.background.copy(alpha = 0.92f),
                ),
                // [T-android-topbar-shrink] 76dp → 68dp. The earlier
                // T-topbar-model-row-clip fix bumped 60dp → 76dp to give the
                // 3-row title (14sp/lh17 + 12sp/lh14 + 11sp/lh13 ≈ 44sp text
                // + 4dp+2dp+1dp vertical padding ≈ 51dp on mdpi, mid-60s on
                // xxhdpi) room to breathe — but overshot, leaving visible
                // dead-space below the model row. This trim pairs with the
                // outer Column's vertical-padding drop (4dp→2dp above):
                // budget is now ~44sp text + 2dp+2dp+1dp ≈ 49dp typical,
                // ~58-62dp at xxhdpi 2.625× rounding. 68dp keeps a 6-10dp
                // safety margin so the model name still fits at any
                // user-configured font scale on xhdpi/xxhdpi without
                // re-clipping (T-topbar-model-row-clip regression check).
                // Font sizes + lineHeights stay untouched per spec.
                expandedHeight = 68.dp,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // [T-tool-approval-removed] 审批门按 LO 裁决移除（智能体直接执行，不设人工门槛）。
    ) { padding ->
        // [T-android-scroll-fab-content-inset] The chat pane's own width, used
        // below to inset the scroll buttons to the capped content column.
        var chatPaneWidthPx by remember { mutableStateOf(0) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .onGloballyPositioned { chatPaneWidthPx = it.size.width },
        ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            if (showSubAgentBar) {
                SubAgentLiveBar(
                    sessionId = sessionId,
                    onStop = com.openminis.app.service.SubAgentActivityTracker::stop,
                )
            }
            ChatGateBanners(
                approvals = pendingApprovals,
                intercepts = interceptEvents,
                onApprove = { viewModel.approvePendingTool(it) },
                onApproveAll = { viewModel.approveAllForSession(it) },
                onDeny = { viewModel.denyPendingTool(it) },
                onDismissIntercept = { InterceptFeedback.dismiss(it) },
            )
            val groupChatOn by viewModel.groupChatEnabled.collectAsState()
            if (groupChatOn) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Forum,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.group_chat_banner),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.TextButton(onClick = { viewModel.endGroupChat() }) {
                            Text(stringResource(R.string.chat_menu_end_group_chat))
                        }
                    }
                }
            }
            // Dismiss keyboard when the USER scrolls the messages. Gated on
            // `isUserDragging` (a real finger drag) rather than
            // `listState.isScrollInProgress` — the latter is also true during
            // the streaming auto-follow's programmatic glide, so the old code
            // hid the keyboard + cleared focus on every streaming tick, which
            // closed the IME mid-stream and dropped the user's in-flight
            // keystroke. [T-android-composer-input-blocked-while-streaming]
            LaunchedEffect(isUserDragging) {
                if (isUserDragging) {
                    // Not releaseComposerAfterSend(): this is scroll-to-dismiss,
                    // a direct request to get the keyboard out of the way, and
                    // it must obey even with a hardware keyboard attached (the
                    // user may be scrolling back to read something).
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }
            }

            // Messages + scroll-to-bottom button
            Box(modifier = Modifier.weight(1f)) {
                var toolBarHeightPx by remember { mutableStateOf(0) }
                val density = LocalDensity.current
                val toolBarHeightDp = with(density) { toolBarHeightPx.toDp() }
                // T166 / T170 / T173: bottomReserve must clear the visible
                // top of the floating activity pill. Layout primitive comes
                // from ToolActivityPill: capsule height 34dp, mounted with
                // 6dp bottom padding above the composer, so its visual top
                // sits 40dp above the input bar's upper edge (which is also
                // the LazyColumn bottom edge under reverseLayout).
                //
                // A measured `onGloballyPositioned` value drifts with wrapper
                // padding and shadow allowance, so the reserve pins to the
                // visual constant instead — the same approach the old 65dp
                // thumbnail reserve used (T166–T174).
                //
                // T174: an earlier version gated reserve on `toolBarHeightPx
                // > 0`, but `onGloballyPositioned` fires asynchronously after
                // the first floating-bar layout pass; for one frame after
                // toolBlocks appeared the reserve evaluated the small
                // default (28 dp) and the just-arrived user bubble landed
                // beneath the bar. Logcat showed the inverse glitch too:
                // `toolBarHeightPx=258 toolBarHeightDp=0 reserve=28` — the
                // px state and the dp/reserve values come from different
                // recomposition snapshots. Drive the reserve directly off
                // the same predicate used for *whether* the floating bar is
                // emitted (`hasFloatingTools` below) so reserve and bar
                // visibility flip on the same frame.
                // [T-android-chat-cannot-scroll-bottom-many-tools]
                // Bug 𝙓𝙄𝙉 TG36286: with 7+ tools the user couldn't scroll the
                // last messages above the floating tool status bar.
                //
                // Asymmetry between the bar's render condition and its
                // bottomReserve gate: [lastToolBlocks] (drives whether to
                // mount FloatingToolStatusBar) merges `messages` with the
                // streaming-side-channel `streamingById`, so during a live
                // turn the in-flight tool's toolStatus shows up there →
                // bar renders. [hasFloatingTools] (drives bottomReserve)
                // only read `messages`, which the streaming architecture
                // intentionally leaves stable during a turn — so the
                // in-flight tool is invisible to this predicate → reserve
                // collapsed to 20dp while a 65dp+6dp floating bar covered
                // the bottom of the LazyColumn. The new arrivals (status
                // pill, "Minis is thinking" indicator, inline retry banner) landed
                // behind the bar with no way to scroll them into view.
                //
                // Fix: also subscribe to streamingById so the predicate
                // matches the bar's actual mount condition. The bar's
                // mount uses `lastToolBlocks.isNotEmpty()` over the merged
                // view; we mirror that semantically by checking the same
                // filter on both sources.
                val streamingById by viewModel.streamingById.collectAsState()
                // [T-android-fold-expanded-duplicate] Hoisted above
                // `hasFloatingTools` — same composable scope, so this is a pure
                // move, not a new state holder. The padding reservation and the
                // overlay it reserves room for have to agree about whether the
                // turn is expanded, otherwise the layout keeps empty space
                // reserved for an overlay that no longer renders.
                var expandedProcessIds by remember(sessionId) { mutableStateOf(emptySet<String>()) }
                // [P0-4] The floating surface is the ToolActivityPill (34dp
                // capsule). Its mount gate and this reserve predicate must be
                // the SAME decision, so both route through
                // floatingOverlayBlocks() on the latest assistant reply:
                //   - fold-ON → empty (the round model owns process display;
                //     single channel);
                //   - showFloatingToolBar off / not streaming → no reserve
                //     (the old predicate missed both, keeping a 79dp dead gap
                //     below the list after a fold-OFF turn ended or while the
                //     pref was disabled);
                //   - latest-reply only: a stuck RUNNING block in an OLD
                //     message used to trip `merged.any` forever even though the
                //     overlay only ever read the latest reply.
                // isStreaming is read here (declared at the top of the screen)
                // so the reserve collapses on the same state flip that
                // unmounts the pill.
                val hasFloatingTools = remember(
                    messages, streamingById, foldAiProcess,
                    showFloatingToolBar, isStreaming, showSubAgentBar, activeSubAgentToolIds,
                ) {
                    if (!showFloatingToolBar || !isStreaming) {
                        false
                    } else {
                        val merged = if (streamingById.isEmpty()) messages
                                     else mergeStreamingOverlay(messages, streamingById)
                        floatingOverlayBlocks(
                            merged.lastOrNull { it.role == "assistant" },
                            foldAiProcess,
                            showSubAgentBar,
                            activeSubAgentToolIds,
                        ).isNotEmpty()
                    }
                }
                val visualOverlayHeight = 34.dp  // ToolActivityPill capsule height
                // Breathing room above the input bar in both states. The
                // pill's 34dp height + 6dp mount padding is what the reserve
                // has to clear.
                // Breathing room above the bottom chrome. 14dp is the same gap the
                // old floating bar used between the last row and the overlay
                // (T245); the no-tools branch stays 20dp (T-bottom-occluded:
                // 14dp left the trailing line clipped against the composer).
                // [P0-4] visualOverlayHeight dropped from the old thumbnail's
                // 65dp to the pill's 34dp, so a streaming turn now reserves
                // 48dp instead of 79dp.
                val bottomReserve =
                    if (hasFloatingTools) visualOverlayHeight + 14.dp else 20.dp
                // T174: when bottomReserve changes (toolbar appearing /
                // disappearing or thumbnail height shift), re-pin to bottom
                // if we are currently following. Without this, the new
                // contentPadding is honoured for layout but reverseLayout
                // won't actively scroll the list — items can wind up below
                // the viewport's new bottom edge until the next streaming
                // delta triggers a follow. iOS does the same in V3:54-68
                // when contentInset.bottom changes.
                LaunchedEffect(bottomReserve) {
                    // [T-android-send-no-autoscroll-behind-preview] Send-grace
                    // bypass: within SEND_FOLLOW_GRACE_MS of a user message
                    // append the isNearBottom gate is waived — the user JUST
                    // sent (the send path pins explicitly), but the
                    // freshly-inserted user/thinking rows leave the live
                    // anchor transiently "not at bottom", which used to skip
                    // this pin and strand the new rows behind the floating
                    // tool bar when the reserve grew. All gates stay in force
                    // outside the grace window, so the C2 stream-end
                    // protections are untouched (a stream end is never
                    // within 2s of the user's send).
                    val sinceSendMs = System.currentTimeMillis() - lastUserAppendMs
                    val sendGrace = lastUserAppendMs > 0L && sinceSendMs in 0..SEND_FOLLOW_GRACE_MS
                    if (scrollPolicy.isPinned && (isNearBottom.value || sendGrace)) {
                        val reason = if (!isNearBottom.value) "send-grace" else "near-bottom"
                        tracedScrollToItem("reserve-change/$reason", 0, 0)
                    }
                }
                // T120: removed three streaming-time LaunchedEffects that
                // each called scrollToItem(0) on every chunk:
                //   - LE(bottomReserve): toolbar resize re-snap
                //   - LE(lastToolCount): per-tool-pill follow
                //   - LE(lastAwaiting):  thinking-indicator follow
                // They fired several times per second during streaming and
                // turned every layout pass into a scroll command, fighting
                // reverseLayout's native bottom-anchor behavior. With
                // reverseLayout=true Compose already keeps the visual
                // bottom pinned when the list is at offset 0; if the user
                // scrolled away, the JumpToBottom FAB is the explicit
                // affordance to return.

                // Flatten each message into multiple LazyColumn items so that older blocks
                // (text / tool pills / thinking) are frozen LazyList items while only the
                // last streaming block changes height. This keeps scroll-hovering stable:
                // LazyListState anchors on a stable item key + pixel offset, and inserting
                // or growing the trailing item never disturbs earlier items.
                //
                // T94: long sessions (hundreds of messages, deep tool chains) made the
                // flatten step expensive enough to stall composition on the main thread —
                // every streaming token recomposed the parent and re-ran the O(N · blocks)
                // walk inside `remember`, producing visible jank and ANRs on slower
                // devices. Run the flatten on Dispatchers.Default and publish the result
                // through a snapshot-state field so the LazyColumn renders the previous
                // frame's list while the next one computes. Keyed on `sessionId` so a
                // chat-switch resets the cache; LaunchedEffect(messages) reruns the
                // computation on every new emission.
                var flatItems by remember(sessionId) {
                    mutableStateOf<List<FlatChatItem>>(emptyList())
                }

                // [T-android-search-jump] Shared row-count prefix: the number
                // of synthetic LazyColumn items declared BEFORE the message
                // rows (compact-progress, resume banner). Both the up-button
                // turn walk and the search-focus jump mirror a flat
                // oldest-first index into a lazy index through
                // ChatHistoryWindow.lazyIndexOfOldestFirstKey, which needs
                // this prefix — it used to live as a private copy inside the
                // up-button lambda; two hand-maintained copies of the same
                // banner bookkeeping is exactly how they drift. Declared
                // before both consumers: local funs are only visible from
                // their declaration point onward.
                fun historyItemsBeforeMessages(): Int {
                    var count = 0
                    if (compactProgress != null) count++
                    val lastAssistantHasError = messages
                        .lastOrNull { it.role == "assistant" }
                        ?.error
                        ?.isNotBlank() == true
                    if (canResume && !isStreaming && error == null && !lastAssistantHasError) count++
                    return count
                }

                // [T-android-search-jump] Search-result → matched-message
                // jump. SessionListScreen stashed the matched message id in
                // DeepLinkCoordinator before opening this session; consume it,
                // page older history until the row is loaded, land it at the
                // visual top and pulse a highlight.
                //
                // Keyed on (sessionId, searchFocusRevision), not just
                // sessionId: tapping a search result for the chat ALREADY in
                // the detail pane changes no contentKey, so no recomposition
                // happens — the revision counter is what re-triggers the
                // effect for a same-session re-tap. The consume itself is
                // session-guarded (see DeepLinkCoordinator.SearchFocus), so a
                // stale ChatScreen composition for another session can never
                // steal this tap's focus.
                var searchFocusMessageId by remember(sessionId) { mutableStateOf<String?>(null) }
                var searchFocusActive by remember(sessionId) { mutableStateOf(false) }
                val searchFocusRevision by com.openminis.app.deeplink.DeepLinkCoordinator
                    .searchFocusRevision
                    .collectAsState()
                LaunchedEffect(sessionId, searchFocusRevision) {
                    val focus = com.openminis.app.deeplink.DeepLinkCoordinator
                        .consumePendingSearchFocus(sessionId) ?: return@LaunchedEffect
                    // Cold open is async — wait for the first message window
                    // to publish before deciding whether paging is needed.
                    // Collect the StateFlow DIRECTLY: snapshotFlow only re-runs
                    // when Compose snapshot state read inside its block
                    // changes, and `uiMessages.value` reads none — a
                    // snapshotFlow here emits once and never again, so on a
                    // cold open (messages still loading) the wait hung to the
                    // 4s timeout and silently bailed: the "tap a search hit,
                    // nothing happens" symptom.
                    withTimeoutOrNull(4000) {
                        viewModel.uiMessages.first { it.isNotEmpty() }
                    } ?: run {
                        AppLogger.warning(
                            "ChatSearchFocus",
                            "message window never published within 4s — staying put",
                        )
                        return@LaunchedEffect
                    }
                    // The matched row may sit ABOVE the loaded window (cold
                    // open is a tail). Page older history until the message is
                    // present — one page per iteration, bounded, the same
                    // contract as the up-button's window-edge walk. A live
                    // stream refuses paging (loadOlderPage checks
                    // isStreaming); the loop then breaks and the fallback
                    // below logs instead of scrolling blind.
                    var pages = 0
                    while (pages < 60) {
                        if (viewModel.uiMessages.value.any { it.id == focus.messageId }) break
                        if (!viewModel.hasOlderMessages.value) break
                        if (!viewModel.loadOlderPage()) break
                        pages++
                    }
                    // The flatten runs on Dispatchers.Default and publishes
                    // between frames — wait for a row of the target message
                    // to exist before resolving its index.
                    withFrameNanos { }
                    withTimeoutOrNull(800) {
                        snapshotFlow { flatItems }.first { list ->
                            list.any { FlatKeys.parse(it.key)?.messageId == focus.messageId }
                        }
                    }
                    val flatIdx = flatItems.indexOfFirst {
                        FlatKeys.parse(it.key)?.messageId == focus.messageId
                    }
                    if (flatIdx < 0) {
                        AppLogger.warning(
                            "ChatSearchFocus",
                            "no flat row for message ${focus.messageId} — staying put",
                        )
                        return@LaunchedEffect
                    }
                    val landIndex = ChatHistoryWindow.lazyIndexOfOldestFirstKey(
                        oldestFirstCount = flatItems.size,
                        keyIndexInOldestFirst = flatIdx,
                        itemsBeforeMessages = historyItemsBeforeMessages(),
                    )
                    searchFocusMessageId = focus.messageId
                    searchFocusActive = true
                    // Two-step calibrated landing, same as the up-button walk:
                    // position once to materialise the row, read its size, then
                    // place the matched row's top edge just under the header.
                    tracedScrollToItem("SEARCH-FOCUS/land", landIndex, 0)
                    val targetKey = flatItems[flatIdx].key
                    val rowSize = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == targetKey }?.size ?: 0
                    val vpH = listState.layoutInfo.viewportSize.height
                    val topOffset = rowSize - vpH + listState.layoutInfo.beforeContentPadding
                    tracedScrollToItem("SEARCH-FOCUS/top", landIndex, topOffset)
                    // The jump landed — the matched row is the reading anchor
                    // now, so the down-FAB shows and no follower moves the
                    // viewport out from under the highlight.
                    scrollPolicy.landReading(targetKey, topOffset)
                    // Hold the pulse, then fade it out. Clearing `active`
                    // (rather than the id) keeps the highlight state reusable:
                    // a same-session re-tap re-enters this effect and
                    // re-lights it without fighting a stale id.
                    kotlinx.coroutines.delay(2400)
                    searchFocusActive = false
                }

    // [T-android-scrollbtn-turn-walk] Up-button action: walk backwards through
    // the conversation one USER turn at a time (iOS `scrollToPreviousUserTurn`).
    //   - First tap: scroll to the user message of the turn the viewport is
    //     currently in (nearest user message at or above the visual top).
    //   - Repeated taps (no intervening drag / jump-to-bottom): each goes one
    //     turn further back.
    //   - Already at the first turn with no older history: stay put.
    //
    // [T-android-upbtn-no-scan] Resolution is DIRECT: the target id comes
    // from the pure decision in ChatHistoryWindow.previousUserTurnTarget and
    // jumps straight to its row key via the flatItems index math
    // (lazyIndexOfOldestFirstKey). The old viewport-by-viewport seek ran one
    // full layout pass per step with no bound — repeated taps stacked those
    // scans into an ANR, and a miss restored the pre-click position (the
    // "点多次向上翻页后视口退回固定位置" bug).
    val scrollToPreviousUserTurn: suspend () -> Unit = scrollToPreviousUserTurn@{
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return@scrollToPreviousUserTurn
        // Oldest → newest snapshot of the loaded window, as (id, isUser).
        var loaded = messages.map { it.id to (it.role == "user") }
        val firstUserIds = loaded.filter { it.second }.map { it.first }
        if (firstUserIds.isEmpty()) {
            // No user turns (rare) — fall back to the oldest item (the HIGHEST
            // index) so the button is never a dead no-op.
            tracedScrollToItem("FAB-UP/no-user-turns", (info.totalItemsCount - 1).coerceAtLeast(0), 0)
            return@scrollToPreviousUserTurn
        }
        // [T-android-visual-top] The visual top of a reverseLayout list is
        // the HIGHEST lazy index (index 0 paints at the bottom). The old
        // minByOrNull selector here — with a "measured on device" comment
        // whose own dump shows offset ascending with index, i.e. away from
        // the bottom start — resolved the first-tap anchor against the
        // NEWEST visible row. Both call sites now share
        // ChatHistoryWindow.visibleTopRow / fullyVisibleUserIds so the two
        // definitions can never diverge again.
        val rowRefs = visible.map {
            ChatHistoryWindow.VisibleRow(it.index, it.key as? String ?: "", it.offset, it.size)
        }
        val topKey = ChatHistoryWindow.visibleTopRow(rowRefs)?.key
        val topMessageId = topKey?.let { FlatKeys.parse(it)?.messageId }
        // Visibility is read from the pre-jump layout on purpose (see
        // previousUserTurnTarget KDoc): the jump mutates the viewport, so
        // anything derived afterwards would describe where the search
        // happened to stop, not where the user was.
        val fullyVisibleUserIds: Set<String> = ChatHistoryWindow.fullyVisibleUserIds(
            rowRefs,
            info.viewportStartOffset,
            info.viewportEndOffset,
        )

        // [T-android-upbtn-window-edge] The walk floor is the oldest LOADED
        // turn. When a tap steps onto that floor and older history exists,
        // load ONE page and re-resolve — the floor moves down and the walk
        // continues past the window edge instead of bouncing off it. The
        // re-resolve chains through lastJumpedUserId, exactly like a second
        // tap on the button. One page per tap keeps each click bounded.
        var jumped = lastJumpedUserId
        var target: String? = null
        var attempt = 0
        while (true) {
            target = ChatHistoryWindow.previousUserTurnTarget(
                loaded = loaded,
                topMessageId = topMessageId,
                lastJumpedUserId = jumped,
                fullyVisibleUserIds = fullyVisibleUserIds,
            )
            val atFloor = target != null && target == loaded.filter { it.second }.map { it.first }.first()
            if (!atFloor || !viewModel.hasOlderMessages.value || attempt >= 1) break
            attempt++
            val added = viewModel.loadOlderPage()
            if (!added) break
            jumped = target
            // `messages` is a composition snapshot. Paging publishes a newer
            // list asynchronously, so re-resolve from the VM after the load.
            loaded = viewModel.uiMessages.value.map { it.id to (it.role == "user") }
        }
        if (target == null) return@scrollToPreviousUserTurn
        val targetKey = FlatKeys.of(FlatKeys.KIND_USER, target)
        // [T-android-upbtn-fresh-state] A page loaded inside the walk above
        // lands in `messages` and `flatItems` ASYNCHRONOUSLY (flatten runs on
        // Dispatchers.Default and publishes between frames). Without this
        // wait, the index resolution below read the stale pre-load flat list,
        // missed the freshly loaded target row, and hopped to the OLD oldest
        // row — the data arrived but the viewport never crossed the window
        // edge in the same tap. One frame + a bounded snapshotFlow wait
        // synchronizes with the publisher; on timeout we fall through to the
        // defensive hop below (self-limiting, as before).
        withFrameNanos { }
        withTimeoutOrNull(500) {
            snapshotFlow { flatItems }.first { list -> list.any { it.key == targetKey } }
        }
        // Re-capture layoutInfo: `info` is from before any page load.
        val jumpInfo = listState.layoutInfo
        // Direct index resolution — no scanning. flatItems is oldest-first;
        // the LazyColumn declares compact/resume items BEFORE the message
        // items and the pill after, so a flat index mirrors into a lazy index
        // via lazyIndexOfOldestFirstKey. The banner prefix comes from the
        // shared historyItemsBeforeMessages() above the two jump sites.
        val flatIdx = flatItems.indexOfFirst { it.key == targetKey }
        val landIndex = if (flatIdx >= 0) {
            ChatHistoryWindow.lazyIndexOfOldestFirstKey(
                oldestFirstCount = flatItems.size,
                keyIndexInOldestFirst = flatIdx,
                itemsBeforeMessages = historyItemsBeforeMessages(),
            )
        } else {
            // Defensive: the target produced no row (upstream anomaly). One
            // bounded hop to the oldest row instead of a scan.
            AppLogger.warning("ChatUpBtn", "target $target has no flat row — hopping to oldest")
            (jumpInfo.totalItemsCount - 1).coerceAtLeast(0)
        }
        lastJumpedUserId = target
        // Land the user bubble's TOP edge just under the header — iOS's
        // `scrollToItem(at: .top)`. Position once to materialise the row,
        // read its size, then place it precisely; the offset math
        // (rowSize - viewportHeight + beforeContentPadding) is the
        // device-calibrated landing from the original implementation.
        tracedScrollToItem("FAB-UP/turn-walk", landIndex, 0)
        val rowSize = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == targetKey }?.size ?: 0
        val vpH = listState.layoutInfo.viewportSize.height
        val headerInset = listState.layoutInfo.beforeContentPadding
        val topOffset = rowSize - vpH + headerInset
        tracedScrollToItem("FAB-UP/turn-walk-top", landIndex, topOffset)
        // [T-android-scroll-policy] The jump landed — the target bubble is
        // the reading anchor now, so the down-FAB shows and no follower can
        // move the viewport.
        scrollPolicy.landReading(targetKey, topOffset)
    }

                // [T-android-coldload-offmain-parse] Composition-snapshot
                // prewarmer (captures the markdown palette) used by the
                // flatten effect below to warm the parse caches for the
                // viewport-candidate fragments off-main.
                val prewarmMarkdown = rememberMarkdownPrewarmer()
                // [T-android-jank-diag-logging] Cold-open one-line summary
                // state: emitted ONCE per session open at the first
                // firstItem.placed; prewarmMs is filled by the parallel
                // prewarm when (if) it has finished by then, else -1.
                var lastColdPrewarmMs by remember(sessionId) { mutableStateOf(-1L) }
                var coldOpenSummaryEmitted by remember(sessionId) { mutableStateOf(false) }
                val screenMountAtMs = remember(sessionId) { System.currentTimeMillis() }
                // T-streaming-side-channel: messages-level changes (new
                // message, retry, etc.) AND streamingById deltas both feed
                // buildFlatChatItems, but we subscribe to streamingById
                // INSIDE LaunchedEffect (not at top-level) so per-token
                // emissions don't recompose the surrounding ChatScreen
                // scope. The flatten still runs per token (cheap-ish; ran
                // before too), but the rebuild stays off the main UI
                // composable's invalidation list.
                LaunchedEffect(messages, sessionId, showCompletedToolCards, foldAiProcess, expandedProcessIds, showSubAgentBar) {
                    // [T-android-stream-pipeline-incremental] Frozen/live split.
                    //
                    // `messages` is CONSTANT within this effect (the effect is
                    // keyed on it and the streaming turn writes high-frequency
                    // fields into the streamingById side-channel, never the
                    // canonical list). So the rows for every message BEFORE the
                    // first streamed one (= the frozen prefix) can be computed
                    // ONCE per effect lifetime and reused by reference on every
                    // tick. Per tick we only rebuild the live suffix (usually a
                    // single message). Pre-split, every 80ms tick re-flattened
                    // ALL messages (1146 rows on the ANR-loop session), re-ran
                    // splitMarkdownIntoBlockTexts over every frozen message,
                    // and allocated the whole row set fresh — the 130–180MB/s
                    // GC storm and the 100s builds in minis-2026-06-10.log.
                    //
                    // Row-for-row equivalence with the old full build holds by
                    // construction: buildFlatChatItems' neighbor lookbacks
                    // (isResumeContinuation) only ever read
                    // EARLIER messages, the live suffix is built against the
                    // full merged list with fromIndex (lookbacks cross the
                    // boundary), and dedupe continuity is preserved via
                    // seedKeys. Frozen rows are the same instances every tick,
                    // so LazyColumn's key+equals skip path sees ZERO change.
                    //
                    // Throttle (unchanged): conflate() + sample(80) keeps UI
                    // publication at ~12fps regardless of token rate.
                    var frozenRows: List<FlatChatItem> = emptyList()
                    var frozenKeys: Set<String> = emptySet()
                    var frozenSplitIdx = -1
                    var streamWasActive = false
                    // [T-android-stream-pipeline-incremental] Flush the perf
                    // turn when this effect is CANCELLED mid-turn: the
                    // turn-end drain emits `_messages` FIRST (restarting this
                    // messages-keyed effect) and clears the side-channel
                    // after, so the cancelled collector never sees the
                    // empty-stream tick that would fire turnEnd — without the
                    // finally, same-session turns accumulate forever and no
                    // [StreamPerf] summary is ever emitted.
                    try {
                    kotlinx.coroutines.flow.combine(
                        kotlinx.coroutines.flow.flowOf(messages),
                        viewModel.streamingById,
                    ) { msgs, stream -> msgs to stream }
                        .conflate()
                        .sample(80L)
                        .collect { (msgs, stream) ->
                            val tickStartNs = System.nanoTime()
                            if (stream.isNotEmpty() && !streamWasActive) {
                                streamWasActive = true
                                com.openminis.app.diagnostics.StreamPerfMonitor.turnStart(sessionId)
                            }
                            // First message carrying a live overlay; everything
                            // before it is frozen. Empty stream → whole list is
                            // frozen (covers cold open and post-drain ticks).
                            val splitIdx = if (stream.isEmpty()) {
                                msgs.size
                            } else {
                                val i = msgs.indexOfFirst { stream.containsKey(it.id) }
                                if (i < 0) msgs.size else i
                            }
                            val frozenReused = splitIdx == frozenSplitIdx
                            if (!frozenReused) {
                                val tBuildStart = System.nanoTime()
                                val wasEmptyPre = flatItems.isEmpty()
                                // [T-android-perf-logging] Mark the start of a
                                // full (first / non-streaming) build so the
                                // gap to buildFlatChatItems.firstBuild bounds
                                // the construction cost in isolation.
                                if (wasEmptyPre && stream.isEmpty()) {
                                    com.openminis.app.diagnostics.PerfLongCtx.step(
                                        sessionId,
                                        "buildFlatChatItems.start",
                                        "msgCount=${msgs.size}",
                                    )
                                }
                                val rows = withContext(Dispatchers.Default) {
                                    // [T-android-flatitems-sublist-cme] Pass a
                                    // SNAPSHOT COPY, not msgs.subList(...). A
                                    // subList is a live VIEW backed by msgs and
                                    // shares its modCount; building off-main
                                    // (Dispatchers.Default) while msgs is
                                    // concurrently replaced — and the nested
                                    // messages.subList(idx+1, …).all{} inside
                                    // buildFlatChatItems iterating that view —
                                    // threw ConcurrentModificationException from
                                    // a later frame's SubList.equals. Copying
                                    // severs the view so it can't comodify.
                                    buildFlatChatItems(
                                        msgs.take(splitIdx),
                                        sessionId,
                                        showCompletedToolCards = showCompletedToolCards,
                                        foldAiProcess = foldAiProcess,
                                        expandedProcessIds = expandedProcessIds,
                                        activeSubAgentToolIds = if (showSubAgentBar) activeSubAgentToolIds else emptySet(),
                                    )
                                }
                                val buildMs = (System.nanoTime() - tBuildStart) / 1_000_000
                                frozenRows = rows
                                frozenKeys = rows.mapTo(HashSet()) { it.key }
                                frozenSplitIdx = splitIdx
                                // [T-android-coldload-offmain-parse] Parallel
                                // viewport prewarm: block-parse + inline-warm
                                // the newest (viewport-candidate) markdown
                                // fragments off-main so the first frame's rows
                                // compose as cache HITs. Deliberately launched
                                // in PARALLEL with the flatItems publish, not
                                // before it — blocking the publish would add
                                // the parse latency to time-to-first-frame,
                                // the exact thing this task removes; rows the
                                // prewarm hasn't reached yet just take the
                                // placeholder-then-swap path in
                                // MarkdownBlockBody. Cold/full builds only
                                // (stream empty) — live ticks never get here.
                                if (stream.isEmpty() && rows.isNotEmpty()) {
                                    val prewarmRowLimit = 16
                                    val prewarmCharBudget = 96_000
                                    val newestFirst = ArrayList<String>()
                                    for (item in rows.asReversed()) {
                                        val raw = (item as? FlatChatItem.AssistantMarkdownBlock)?.rawText ?: continue
                                        newestFirst.add(raw)
                                    }
                                    val raws = BoundedText.selectPrewarmFragments(
                                        newestFirst = newestFirst,
                                        rowLimit = prewarmRowLimit,
                                        charBudget = prewarmCharBudget,
                                    )
                                    if (raws.isNotEmpty()) {
                                        launch(Dispatchers.Default) {
                                            val tPrewarmNs = System.nanoTime()
                                            prewarmMarkdown(raws)
                                            val prewarmMs = (System.nanoTime() - tPrewarmNs) / 1_000_000
                                            lastColdPrewarmMs = prewarmMs
                                            com.openminis.app.diagnostics.PerfLongCtx.step(
                                                sessionId,
                                                "coldPrewarm.done",
                                                "rows=${raws.size} chars=${raws.sumOf { it.length }} prewarmMs=$prewarmMs",
                                            )
                                        }
                                    }
                                }
                                // Only emit on the first non-streaming build per
                                // session (cheap reentry-path marker) or whenever
                                // build takes >50 ms (i.e. real work).
                                if ((wasEmptyPre || buildMs >= 50) && stream.isEmpty()) {
                                    com.openminis.app.diagnostics.PerfLongCtx.step(
                                        sessionId,
                                        if (wasEmptyPre) "buildFlatChatItems.firstBuild"
                                        else "buildFlatChatItems.slow",
                                        "msgCount=${msgs.size} rowCount=${rows.size} buildMs=$buildMs",
                                    )
                                    // [T-android-perf-logging] Low-memory risk
                                    // flag: a very high row count is the single
                                    // biggest contributor to cold-open GC
                                    // pressure.
                                    if (rows.size > 3000) {
                                        com.openminis.app.diagnostics.PerfLongCtx.step(
                                            sessionId,
                                            "buildFlatChatItems.highRowCount",
                                            "rowCount=${rows.size} threshold=3000 msgCount=${msgs.size}",
                                        )
                                    }
                                }
                            }
                            // Live suffix: only the streamed message(s). Built
                            // against the merged FULL list so neighbor lookbacks
                            // across the frozen/live boundary stay correct.
                            // sessionId = null keeps the hot path log-free.
                            val liveRows = if (splitIdx >= msgs.size) {
                                emptyList()
                            } else {
                                withContext(Dispatchers.Default) {
                                    val merged = mergeStreamingOverlay(msgs, stream)
                                    buildFlatChatItems(
                                        merged,
                                        null,
                                        fromIndex = splitIdx,
                                        seedKeys = frozenKeys,
                                        showCompletedToolCards = showCompletedToolCards,
                                        foldAiProcess = foldAiProcess,
                                        expandedProcessIds = expandedProcessIds,
                                        activeSubAgentToolIds = if (showSubAgentBar) activeSubAgentToolIds else emptySet(),
                                    )
                                }
                            }
                            flatItems = if (liveRows.isEmpty()) frozenRows else frozenRows + liveRows
                            com.openminis.app.diagnostics.StreamPerfMonitor.tick(
                                flattenNanos = System.nanoTime() - tickStartNs,
                                frozenReused = frozenReused,
                                frozenRows = frozenRows.size,
                                liveRows = liveRows.size,
                            )
                            if (stream.isEmpty() && streamWasActive) {
                                streamWasActive = false
                                com.openminis.app.diagnostics.StreamPerfMonitor.turnEnd()
                            }
                        }
                    } finally {
                        // Effect cancelled (turn-end drain emit / session
                        // switch / screen dispose) — flush the open turn.
                        if (streamWasActive) {
                            com.openminis.app.diagnostics.StreamPerfMonitor.turnEnd()
                        }
                    }
                }
                // T304: when a new tool-use item appears at the trailing
                // edge (head of flatItems with reverseLayout=true), pin
                // back to the bottom so the just-arrived tool card is
                // visible above the floating Computer overlay + composer.
                //
                // The streaming-content snapshotFlow (LE around L798) does
                // detect `m.toolBlocks.size` growth, but it fires on the
                // raw `messages` model — and the LazyColumn renders the
                // async-flattened `flatItems`. The scroll can run BEFORE
                // flatItems repopulates with the new tool item, so item 0
                // is still the previous trailing item; the new tool block
                // ends up appended below the visible viewport. Pinning
                // again keyed on `flatItems` head fixes the race without
                // disturbing T281/T282 (those still own user-send and
                // resume scroll). The scroll-policy gate honours Reading so
                // users reading history aren't yanked back.
                // [T-android-send-no-autoscroll-behind-preview] Key of the
                // trailing tool/typing row we last pinned for — dedupes the
                // pin to once per new row across flatten publishes.
                var lastTrailingPinKey by remember(sessionId) { mutableStateOf<String?>(null) }
                LaunchedEffect(flatItems) {
                    // Pin once per new trailing tool/typing row, key-deduped,
                    // not on every flatten publish, so streaming tool-arg
                    // ticks don't fight the user. flatItems is oldest-first
                    // (rendered via asReversed()), so the newest row is at
                    // the end.
                    val newest = flatItems.lastOrNull() ?: return@LaunchedEffect
                    // [T-android-queued-bubble-behind-toolbar] UserBubble is in
                    // the accept-list too, for the queued ("candidate") message
                    // the user sends WHILE a turn is streaming.
                    //
                    // enqueuePrompt() appends the bubble to `_messages` without
                    // going through the normal send path, so the only scrolls it
                    // gets are the two position-0 pins (SEND-PATH/* and
                    // LE(messages.size)USER-SEND-SNAP). Those fire — the logs
                    // show all three landing `idx=0 off=0` — but position 0
                    // under reverseLayout is the LazyColumn's own bottom edge,
                    // which the floating tool bar (65dp thumbnail + overhang)
                    // covers. contentPadding.bottom already reserves that space
                    // via bottomReserve, yet the reserve does NOT change here:
                    // a tool bar was already on screen before the enqueue, so
                    // hasFloatingTools stays true and LE(bottomReserve) never
                    // re-fires (verified: zero `reserve-change` events at the
                    // enqueue moment). The bubble is laid out inside the
                    // reserved band and stays half-occluded.
                    //
                    // This effect re-pins AFTER flatItems republishes with the
                    // new row measured, which is exactly the missing step: the
                    // earlier pins ran against a flatItems that did not yet
                    // contain the bubble. Restricting it to isQueued keeps
                    // ordinary user sends (already handled by the send path,
                    // and never appended mid-stream) off this path.
                    val isQueuedBubble =
                        newest is FlatChatItem.UserBubble && newest.message.isQueued
                    if (newest !is FlatChatItem.AssistantToolUse &&
                        newest !is FlatChatItem.AssistantTyping &&
                        !isQueuedBubble
                    ) return@LaunchedEffect
                    if (newest.key == lastTrailingPinKey) return@LaunchedEffect
                    // [T-android-scroll-policy] Pinned-only: a reader must not
                    // be yanked to the bottom by a tool card / typing row.
                    if (!scrollPolicy.isPinned) return@LaunchedEffect
                    if (listState.isScrollInProgress) return@LaunchedEffect
                    val sinceInterrupt = System.currentTimeMillis() - lastInterruptMs
                    if (sinceInterrupt < 1000L) return@LaunchedEffect
                    // Pin fires when EITHER (a) we're inside the send-grace
                    // window (the original "freshly sent, snap the typing
                    // row up" case), OR (b) the agent loop is actively
                    // streaming while the viewport is pinned.
                    val sinceSendForPin = System.currentTimeMillis() - lastUserAppendMs
                    val sendGrace = lastUserAppendMs > 0L && sinceSendForPin <= SEND_FOLLOW_GRACE_MS
                    val streamingActive = viewModel.isStreaming.value
                    if (!sendGrace && !streamingActive) return@LaunchedEffect
                    lastTrailingPinKey = newest.key
                    tracedScrollToItem("trailing-row/${newest.contentType}", 0, 0)
                }
                // [T-android-scroll-policy] The old history anchor-restore
                // machinery (VisibleHistoryAnchor snapshot + restore on every
                // flatItems change) is deleted. It re-scrolled to STALE
                // snapshots whenever content changed while reading and only
                // compared indices, so it fought both the user's drags and
                // LazyColumn's own key anchoring. With stable keys
                // (FlatKeys + buildFlatChatItems) the native key-follow keeps
                // the reading position across appends/prepends — nothing here
                // needs to re-scroll.
                // messageId → isCompactedHistory map. Used to fade entire
                // assistant-row clusters (header + text + tool pills) at
                // render time — mirrors iOS isCompactedHistory opacity(0.5).
                // message.id / flat messageId fields are clean ids (the
                // dedupe suffix lives only in the row key, see FlatKeys), so
                // the lookup is direct.
                val grayedMap = remember(messages) {
                    messages.associate { it.id to it.isCompactedHistory }
                }
                fun FlatChatItem.isCompacted(): Boolean = when (this) {
                    is FlatChatItem.UserBubble -> grayedMap[message.id] == true
                    is FlatChatItem.AssistantHeader -> grayedMap[messageId] == true
                    is FlatChatItem.AssistantText -> grayedMap[messageId] == true
                    is FlatChatItem.AssistantMarkdownBlock -> grayedMap[messageId] == true
                    is FlatChatItem.AssistantThinking -> grayedMap[messageId] == true
                    is FlatChatItem.AssistantProcessSummary -> grayedMap[messageId] == true
                    is FlatChatItem.AssistantToolUse -> grayedMap[messageId] == true
                    is FlatChatItem.AssistantInfo -> false  // system rows never grayed
                    is FlatChatItem.AssistantTyping -> false
                    is FlatChatItem.AssistantError -> grayedMap[messageId] == true
                    is FlatChatItem.AssistantLegacyContent -> grayedMap[messageId] == true
                }
                // SelectionContainer must wrap the WHOLE LazyColumn — placing
                // it per-item breaks long-press because items get disposed
                // when scrolled out and the selection registrar/detector goes
                // with them. One outer SelectionContainer registers each Text
                // child as it enters composition, and the long-press gesture
                // detector lives at this stable scope. Mirrors Compose's
                // recommended LazyColumn + selection pattern.
                //
                // The custom LocalTextToolbar replaces the system Copy bar
                // with a 3-button popup (Copy / Copy Markdown / Copy Rich
                // Text); the latter two read from the bounds registry which
                // each AssistantMessageView updates via onGloballyPositioned.
                val messageBounds = remember { MessageBoundsRegistry() }
                // [T-selection-add-to-input] Toolbar's "Add to Chat Input"
                // action funnels the selected substring back into the
                // composer via the same StateFlow that the TextField is
                // bound to. Capture `viewModel` by reference so the
                // toolbar instance survives recomposition without
                // re-creation.
                // [T-add-to-input-focus] After append, request focus on the
                // composer + pop the soft keyboard so the user can keep
                // typing without an extra tap. Keyboard `show()` is best-effort
                // (controller may be null pre-attach); focus is guarded against
                // FocusRequester-not-attached the same way the auto-focus path
                // elsewhere in this file is.
                // MinisTextKit selection controller — declared BEFORE the
                // markdown toolbar so the toolbar can read table actions off it
                // ([T-android-markdown-table-copy-actions]). Hoisted ABOVE the
                // LazyColumn so item dispose can't kill the selection: when a
                // shard scrolls out of viewport it deregisters its TextShard,
                // but the (messageId, shardId, charOffset) endpoints stay valid;
                // scrolling back in re-registers the shard and the highlight
                // redraws automatically.
                val selectionController = remember { SelectionController() }
                // [T-android-selection-readaloud] Player backing the selection
                // toolbar's "Read Aloud". Screen-scoped and independent of the
                // voice panel's own player (that one only exists while voice
                // mode is active), so reading a selection works any time. Built
                // lazily on first use — an unused ChatScreen never binds a TTS
                // engine — and shut down with the screen.
                val selectionReader = remember { LazyReadAloudPlayer(context) }
                DisposableEffect(selectionReader) {
                    onDispose { selectionReader.shutdown() }
                }
                // [T-android-readaloud-stop-stale] A reply the user asked to be
                // read aloud keeps playing while the next turn is being
                // generated — desirable during thinking, but once the new reply
                // starts producing text the stale audio must yield or the two
                // overlap. The ViewModel signals on the first text delta; the
                // player is screen-scoped, so the stop happens here.
                LaunchedEffect(viewModel, selectionReader) {
                    viewModel.stopStaleReadAloud.collect { selectionReader.stop() }
                }
                val markdownToolbar = remember(context, messageBounds, viewModel, inputFocusRequester, keyboardController, selectionController, selectionReader) {
                    MinisMarkdownTextToolbar(
                        context = context,
                        registry = messageBounds,
                        onAddToInput = { snippet ->
                            viewModel.appendToInputText(snippet)
                            try {
                                inputFocusRequester.requestFocus()
                            } catch (_: IllegalStateException) {
                                // FocusRequester not yet attached — composer
                                // will gain focus on next user tap.
                            }
                            keyboardController?.show()
                        },
                        onReadAloud = { snippet -> selectionReader.speak(snippet) },
                        // [T-android-readaloud-selection-vs-reply] Whole-reply
                        // replay, hidden while streaming via isStreamingNow.
                        onReadFromStart = { fullText -> selectionReader.speak(fullText) },
                        isStreamingNow = { viewModel.isStreaming.value },
                        selectionController = selectionController,
                    )
                }
                // Wrap any callback that truncates / replaces / removes rows from
                // the message list. Hiding the toolbar + clearing focus tears down
                // the SelectionManager's pending toolbar update before the
                // SelectionContainer subtree gets reshuffled — without this,
                // notifySelectionUpdateEnd → updateSelectionToolbar → getContentRect
                // → sort hits stale LayoutCoordinates and crashes with
                // "layouts are not part of the same hierarchy".
                val safeMutate: (() -> Unit) -> Unit = { block ->
                    markdownToolbar.hide()
                    focusManager.clearFocus()
                    block()
                }
                // Hoist slash-menu state up so the LazyColumn pointerInput
                // tap-spy below can react to it. The popup itself, declared
                // further down near the composer, reads viewModel.showSlashMenu
                // again — both subscriptions snap to the same StateFlow.
                val slashMenuOpen by viewModel.showSlashMenu.collectAsState()
                // T4: mirror state hoist for the mention picker so the chat-list
                // tap-spy can dismiss it the same way as the slash popup.
                val mentionMenuOpenForSpy by viewModel.showMentionMenu.collectAsState()
                // Intercept back press to dismiss slash/mention menus before
                // navigating away from the chat screen.
                androidx.activity.compose.BackHandler(
                    enabled = slashMenuOpen || mentionMenuOpenForSpy
                ) {
                    if (slashMenuOpen) {
                        viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                    }
                    if (mentionMenuOpenForSpy) {
                        viewModel.dismissMentionMenu()
                    }
                }
                // (selectionController declared above, before markdownToolbar.)
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalMessageBoundsRegistry provides messageBounds,
                    androidx.compose.ui.platform.LocalTextToolbar provides markdownToolbar,
                    LocalMinisSelectionController provides selectionController,
                ) {
                // Hoisted out of AlwaysStretchOverscrollBox lambda so
                // SelectionDragTracker (which lives outside the lambda) can
                // read the LazyColumn's window-space root coords for edge
                // auto-scroll calculations.
                var listRootCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
                // [Perf][LongCtx] T-android-long-ctx-reentry-perf:
                // fires once per session when the LazyColumn first reports
                // a layout. Combined with `buildFlatChatItems.firstBuild`
                // (above) and `lazyColumn.firstItem.placed` (below) this
                // tells us whether the bottleneck is row-list build,
                // initial list measure, or per-row composition.
                val perfFirstLayoutFired = remember(sessionId) { java.util.concurrent.atomic.AtomicBoolean(false) }
                val perfFirstItemComposeFired = remember(sessionId) { java.util.concurrent.atomic.AtomicBoolean(false) }
                val perfFirstItemPlacedFired = remember(sessionId) { java.util.concurrent.atomic.AtomicBoolean(false) }
                LaunchedEffect(listState) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        val visible = info.visibleItemsInfo
                        HistoryEdgeVisible(
                            olderSentinel = visible.any { it.key == "__load_older__" },
                            // The newer edge is not a sentinel. A gap after the
                            // loaded cursor is attached by the view model.
                            newerSentinel = false,
                            newestEdge = visible.any { it.index == 0 },
                            oldestEdge = visible.any { it.index == info.totalItemsCount - 1 },
                        )
                    }.collect { edge ->
                        val action = ChatHistoryWindow.historyEdgeAction(
                            olderSentinelVisible = edge.olderSentinel,
                            newerSentinelVisible = edge.newerSentinel,
                            newestEdgeVisible = edge.newestEdge,
                            oldestEdgeVisible = edge.oldestEdge,
                            hasOlder = viewModel.hasOlderMessages.value,
                            hasNewer = viewModel.hasNewerMessages.value,
                        )
                        if (action.loadOlder) viewModel.loadOlderMessages()
                    }
                }
                Box {
                AlwaysStretchOverscrollBox { sharedEffect ->
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    // T30: when no tool status bar is rendered, a small bottom
                    // padding keeps the latest message off the composer's
                    // top edge so the conversation breathes. Reuses the same
                    // bottomReserve when the toolbar is present.
                    // Tuned so the visible gap to the composer's outer edge is ~18dp.
                    //
                    // [T-android-chat-first-message-top-padding][P1-3] top is
                    // 8dp: the first row's rhythm gap is 0 (no older
                    // neighbour) and rows no longer self-pad, so the full
                    // breath under the model title bar comes from here.
                    // Bottom padding is the composer-side breathing room.
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = if (bottomReserve == 0.dp) 12.dp else bottomReserve,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        // [T-android-chat-max-content-width] Cap the reading
                        // measure on a wide window, mirroring iOS
                        // AIChatView.maxContentWidth (900pt when the horizontal
                        // size class is regular, uncapped when compact). Without
                        // it, a tablet stretches every bubble and code block the
                        // full pane width and lines get too long to track.
                        // Applied to the LazyColumn rather than to each row so
                        // messages, tool cards and dividers all share one
                        // measure and stay vertically aligned.
                        // `wrapContentWidth` centres the capped column inside
                        // the pane. `align` is unavailable here — the enclosing
                        // AlwaysStretchOverscrollBox passes a plain lambda, not
                        // a BoxScope.
                        .wrapContentWidth(Alignment.CenterHorizontally)
                        .widthIn(max = CHAT_MAX_CONTENT_WIDTH)
                        .padding(horizontal = 16.dp)
                        .onGloballyPositioned {
                            listRootCoords = it
                            if (perfFirstLayoutFired.compareAndSet(false, true)) {
                                val info = listState.layoutInfo
                                com.openminis.app.diagnostics.PerfLongCtx.step(
                                    sessionId,
                                    "lazyColumn.firstLayout",
                                    "totalItems=${info.totalItemsCount} visibleItems=${info.visibleItemsInfo.size} viewport=${info.viewportSize.width}x${info.viewportSize.height}",
                                )
                            }
                        }
                        .minisTextKitSelectionGesture(
                            controller = selectionController,
                            listState = listState,
                            rootCoordinates = { listRootCoords },
                            // Chat LazyColumn is reverseLayout=true; auto-
                            // scroll sign needs to flip so dragging toward
                            // the bottom edge reveals NEWER messages (lower
                            // index) rather than jumping backward.
                            reverseLayout = true,
                        )
                        // T29 dismiss-on-tap spy. Only active while the slash
                        // popup is showing. awaitFirstDown(requireUnconsumed=false,
                        // pass=Initial) lets us see the tap *before* any child
                        // gesture (LazyColumn scroll, message long-press) without
                        // consuming it — the gesture continues to its real
                        // handler. We close the menu on the very first finger
                        // down anywhere inside the chat list, exactly like
                        // tapping outside an iOS popover.
                        .pointerInput(slashMenuOpen, mentionMenuOpenForSpy) {
                            if (!slashMenuOpen && !mentionMenuOpenForSpy) return@pointerInput
                            awaitEachGesture {
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                                )
                                if (slashMenuOpen) {
                                    viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                                }
                                if (mentionMenuOpenForSpy) {
                                    viewModel.dismissMentionMenu()
                                }
                            }
                        },
                    // T303: anchor items to the visual bottom so a newly
                    // streamed tool card / typing indicator that arrives
                    // before older items have shifted up still lands inside
                    // the visible area. Without this, reverseLayout's
                    // default arrangement (anchored to viewportStart) leaves
                    // a gap at the bottom of the list when the bottom item
                    // is just-inserted with offset=0 — the new card renders
                    // behind the composer and `gap = vpEnd - itemBottom`
                    // hits ~1300 px while listState still reports
                    // firstVisible=0, firstOffset=0 (logged as the "tool on
                    // screen but not pushed into view" repro on Pixel 4a).
                    // [P1-3] Inter-row gaps come from the row rhythm
                    // (flatItemTopGap in ChatFlatItems), applied as top
                    // padding on each row below. spacedBy(0) only keeps the
                    // bottom alignment for the not-yet-filled viewport.
                    verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.Bottom),
                    overscrollEffect = sharedEffect,
                ) {
                    // T13 Resume banner — placed BEFORE items() so reverseLayout
                    // renders it at the visual bottom of the list (just below
                    // the last assistant message). Mirrors iOS resumeBanner in
                    // CollectionViewMessageListV3.swift:360. Hidden while
                    // streaming or when an error banner is showing.
                    //
                    // T114: also hide when the last assistant message carries
                    // a message-level error — the inline Retry banner already
                    // covers that turn, and showing both at once is confusing
                    // (Resume on a turn that hit rate-limit would just retrace
                    // into the same failure).
                    val lastAssistantHasError = messages
                        .lastOrNull { it.role == "assistant" }
                        ?.error
                        ?.isNotBlank() == true
                    // [T-android-compact-progress] Live compaction status, so a
                    // long-running compact reads as "working" rather than
                    // "hung". Sits above the resume banner because the two are
                    // mutually exclusive in practice (compaction blocks sends).
                    compactProgress?.let { progress ->
                        item(key = "__compact_progress__", contentType = "compact_progress") {
                            CompactProgressIndicator(
                                progress = progress,
                                onCancel = { viewModel.cancelCompact() },
                            )
                        }
                    }
                    if (canResume && !isStreaming && error == null && !lastAssistantHasError) {
                        item(key = "__resume_banner__", contentType = "resume_banner") {
                            ResumeBanner(onResume = {
                                viewModel.resume()
                                // T282: same dual-scroll trick as the regular
                                // send paths (T281). Resume kicks off a fresh
                                // stream, so the "Minis is thinking" indicator
                                // mounts a frame or two later — pin once now,
                                // then again after 100ms so the indicator
                                // doesn't land below the fold.
                                scrollPolicy.pin()
                                coroutineScope.launch {
                                    tracedScrollToItem("RESUME-BANNER/initial", 0, 0)
                                    kotlinx.coroutines.delay(100)
                                    tracedScrollToItem("RESUME-BANNER/settle", 0, 0)
                                }
                            })
                        }
                    }
                    itemsIndexed(
                        items = flatItems.asReversed(),
                        key = { _, item -> item.key },
                        contentType = { _, item -> item.contentType },
                    ) { index, item ->
                        // [P1-3] Row rhythm: this row's top gap comes from its
                        // OLDER neighbour in flatItems (flatItems is
                        // oldest-first, rendered reversed under reverseLayout,
                        // so the older neighbour sits directly above this
                        // row). Applied as top padding on the row's Box.
                        val rhythmGap = flatItemTopGap(
                            flatItems.getOrNull(flatItems.size - 2 - index),
                            item,
                        )
                        // [Perf][LongCtx] T-android-long-ctx-reentry-perf:
                        // the newest message (index 0 in reverseLayout) is
                        // the first row painted in the viewport — its
                        // onPlaced is the moment the user actually sees
                        // content. SideEffect fires on first composition
                        // (before measure); onPlaced fires after layout.
                        if (item == flatItems.lastOrNull()) {
                            androidx.compose.runtime.SideEffect {
                                if (perfFirstItemComposeFired.compareAndSet(false, true)) {
                                    com.openminis.app.diagnostics.PerfLongCtx.step(
                                        sessionId,
                                        "lazyColumn.firstItem.compose",
                                    )
                                }
                            }
                        }
                        // [Perf][LongCtx] aggregate compose-count tracker.
                        // Each row that enters composition during the reentry
                        // burst increments the per-session counter. When the
                        // 10th and 50th rows hit, emit one line each carrying
                        // the wall-time since `lazyColumn.firstLayout` plus
                        // the row's class — gives a "per-N-rows compose
                        // budget" signal without per-row log spam.
                        com.openminis.app.diagnostics.PerfLongCtx.maybeReportRowComposed(
                            sessionId,
                            item::class.java.simpleName,
                        )
                        // 0.4f matches iOS .opacity(0.5) closely once Compose's
                        // sRGB compositing is factored in. Renders below normal
                        // intensity but the message stays selectable + readable.
                        val rowAlpha = if (item.isCompacted()) 0.4f else 1f
                        // [T-HANG-DIAG] log on first composition of any item
                        // whose content is large enough to be a likely hang
                        // suspect. SideEffect runs after the first successful
                        // composition; if rendering stalls on the way to that
                        // SideEffect, we'll see the LAUNCH-RENDER line for it
                        // immediately followed by the watchdog's HANG dump
                        // and the missing FINISH-RENDER tells us this is the
                        // item that locked up the layout pass. Gated on size
                        // so normal turns don't spam the log.
                        val tHangDiagLen = remember(item.key) {
                            when (item) {
                                is FlatChatItem.UserBubble -> item.message.content.length
                                is FlatChatItem.AssistantText -> item.messageMarkdown.length
                                else -> 0
                            }
                        }
                        if (tHangDiagLen >= 50_000) {
                            androidx.compose.runtime.SideEffect {
                                println(
                                    "[T-HANG-DIAG] LAUNCH-RENDER key=${item.key} " +
                                        "type=${item::class.java.simpleName} len=$tHangDiagLen",
                                )
                            }
                            androidx.compose.runtime.DisposableEffect(item.key) {
                                onDispose {
                                    println("[T-HANG-DIAG] FINISH-RENDER key=${item.key} (composed → disposed)")
                                }
                            }
                        }
                        val isNewestItem = item == flatItems.lastOrNull()
                        // [T-android-search-jump] Pulse highlight for the
                        // search-focus row(s). A message flattens into several
                        // rows (text / mdblock / thinking / tool) — matching on
                        // the parsed messageId lights the whole message, which
                        // reads as one highlighted block. animateFloatAsState
                        // gives a soft 0→0.3→0 fade; the modifier is only
                        // attached while visible so idle rows pay nothing.
                        val searchFocusId = searchFocusMessageId
                        val isSearchFocusRow = searchFocusId != null &&
                            FlatKeys.parse(item.key)?.messageId == searchFocusId
                        val searchFocusAlpha by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (isSearchFocusRow && searchFocusActive) 0.30f else 0f,
                            animationSpec = androidx.compose.animation.core.tween(
                                durationMillis = 600,
                            ),
                            label = "searchFocusHighlight",
                        )
                        Box(
                            modifier = Modifier
                                .padding(top = rhythmGap)
                                .alpha(rowAlpha)
                                .then(
                                    if (searchFocusAlpha > 0.01f) {
                                        Modifier.background(
                                            MaterialTheme.colorScheme.primary.copy(
                                                alpha = searchFocusAlpha,
                                            ),
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .then(
                                    if (isNewestItem) {
                                        Modifier.onPlaced {
                                            if (perfFirstItemPlacedFired.compareAndSet(false, true)) {
                                                com.openminis.app.diagnostics.PerfLongCtx.step(
                                                    sessionId,
                                                    "lazyColumn.firstItem.placed",
                                                    "size=${it.size.width}x${it.size.height}",
                                                )
                                            }
                                            // [T-android-jank-diag-logging]
                                            // One quotable line per session
                                            // open, after the first frame's
                                            // newest row has laid out.
                                            if (!coldOpenSummaryEmitted) {
                                                coldOpenSummaryEmitted = true
                                                val totalChars = messages.sumOf { m -> m.content.length }
                                                val maxChars = messages.maxOfOrNull { m -> m.content.length } ?: 0
                                                AppLogger.info(
                                                    "JankDiag",
                                                    "[JankDiag] coldOpen summary session=$sessionId msgs=${messages.size} rows=${flatItems.size} " +
                                                        "totalChars=$totalChars maxChars=$maxChars prewarmMs=$lastColdPrewarmMs " +
                                                        "sinceMountMs=${System.currentTimeMillis() - screenMountAtMs} " +
                                                        "hangCount=${com.openminis.app.diagnostics.HangDetector.currentHangCount(context)}",
                                                )
                                                // [T-android-content-perf-diag] Per-large-message structural
                                                // fingerprint so a future hang report maps straight to "which
                                                // message, what structure" without re-querying the DB. Gated at
                                                // 5000 chars — small messages never drive a render hang.
                                                messages.forEachIndexed { idx, m ->
                                                    if (m.content.length >= com.openminis.app.diagnostics.CONTENT_DIAG_MIN_CHARS) {
                                                        val s = com.openminis.app.diagnostics.ContentDiag.summarize(m.content)
                                                        AppLogger.info(
                                                            "Perf",
                                                            "[Perf][ContentDiag] session=$sessionId msgIdx=$idx role=${m.role} " +
                                                                "streaming=${m.isStreaming} ${s.asLogFields()}",
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                        when (item) {
                            is FlatChatItem.UserBubble -> {
                                // User bubbles intentionally don't register
                                // MinisTextKit shards — long-press on a user
                                // bubble shows its own action menu (Copy /
                                // Retry / Edit) instead of starting text
                                // selection, matching iOS UX.
                                UserMessageBubble(
                                message = item.message,
                                onCopy = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", item.message.content))
                                },
                                // T119: pass null while a turn is in flight so
                                // the long-press menu hides Retry; once the
                                // stream stops (cancel or natural end) the
                                // option reappears. Gating execution alone
                                // wasn't enough — users still saw a tappable
                                // Retry that silently no-op'd.
                                onRetry = if (isStreaming) null else ({
                                    coroutineScope.launch {
                                        tracedScrollToItem("RETRY-FROM-MSG", 0, 0)
                                    }
                                    safeMutate { viewModel.retryFromMessage(item.message.id) }
                                }),
                                // [T-android-delete-from-here] Gated while
                                // streaming for the same reason as Retry:
                                // truncating rows under a live agent loop
                                // leaves agentHistory describing messages that
                                // no longer exist. Opens a confirmation rather
                                // than cutting straight away — there is no undo.
                                onDeleteFromHere = if (isStreaming) null else ({
                                    deleteFromHereTargetId = item.message.id
                                }),
                                // T187: long-press → Edit pulls the user message
                                // text into the composer; the next send truncates
                                // from this turn (inclusive) before persisting
                                // the edited content. Gated on isStreaming the
                                // same way Retry is.
                                onEdit = if (isStreaming || item.message.isQueued) null else ({
                                    val prefill = viewModel.editMessage(item.message.id)
                                    if (prefill != null) {
                                        viewModel.setInputText(prefill)
                                        coroutineScope.launch {
                                            tracedScrollToItem("EDIT-MSG", 0, 0)
                                        }
                                        inputFocusRequester.requestFocus()
                                    }
                                }),
                                onWithdraw = if (item.message.isQueued) {
                                    { safeMutate { viewModel.withdrawQueuedMessage(item.message.id) } }
                                } else null,
                                onPreviewFile = { uri, name ->
                                    // T150: turn the persisted file:// URI back
                                    // into a FileItem and hand off to the host
                                    // navigator (FilePreviewScreen). Mirrors
                                    // FileBrowser's onPreviewFile contract so
                                    // both entry points share one screen.
                                    val file = uri.path?.let { java.io.File(it) }
                                    if (file != null && file.exists()) {
                                        previewFileOrBrowser(
                                            com.openminis.app.ui.sandbox.FileItem(
                                                file = file,
                                                name = name,
                                                isDirectory = false,
                                                isSymlink = false,
                                                size = file.length(),
                                                modifiedMs = file.lastModified(),
                                            )
                                        )
                                    }
                                },
                            )
                            } // close UserBubble SideEffect + UserMessageBubble block
                            is FlatChatItem.AssistantHeader -> AssistantHeader(item.speakerName, item.speakerVendor)
                            is FlatChatItem.AssistantText -> BoundsTrackedBlock(
                                messageId = item.messageId,
                                slotKey = "text:${item.block.id}",
                                markdown = item.messageMarkdown,
                            ) {
                                // T-android-gc-storm-issue17: collapse oversized frozen
                                // assistant text before feeding the markdown parser, which
                                // is the GC-storm hotspot for legacy sessions.
                                LargeContentGuard(
                                    content = item.block.content,
                                    isStreaming = item.isStreaming,
                                    stableKey = "text:${item.messageId}:${item.block.id}",
                                ) {
                                    SideEffect {
                                        selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                                    }
                                    if (!item.isStreaming && item.block.content.isNotBlank()) {
                                        TranslateCorner(
                                            button = {
                                                AssistantTranslateButton(
                                                    source = item.block.content,
                                                    taskScope = coroutineScope,
                                                    onTranslated = {
                                                        viewModel.replaceAssistantTextBlock(item.messageId, item.block.id, it)
                                                    },
                                                )
                                            },
                                        ) {
                                            StreamingMarkdownText(
                                                content = item.block.content,
                                                isStreaming = item.isStreaming,
                                                shardId = TextShardId(
                                                    messageId = item.messageId,
                                                    shardId = "text:${item.block.id}",
                                                ),
                                            )
                                        }
                                    } else {
                                        StreamingMarkdownText(
                                            content = item.block.content,
                                            isStreaming = item.isStreaming,
                                            shardId = TextShardId(
                                                messageId = item.messageId,
                                                shardId = "text:${item.block.id}",
                                            ),
                                        )
                                    }
                                }
                            }
                            is FlatChatItem.AssistantMarkdownBlock -> BoundsTrackedBlock(
                                messageId = item.messageId,
                                slotKey = "mdblock:${item.parentBlockId}:${item.blockIndex}",
                                markdown = item.messageMarkdown,
                            ) {
                                LargeContentGuard(
                                    content = item.rawText,
                                    isStreaming = item.isStreaming,
                                    stableKey = "mdblock:${item.messageId}:${item.parentBlockId}:${item.blockIndex}",
                                ) {
                                    SideEffect {
                                        selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                                    }
                                    if (item.showTranslate) {
                                        TranslateCorner(
                                            button = {
                                                AssistantTranslateButton(
                                                    source = item.segmentText.ifBlank { item.rawText },
                                                    taskScope = coroutineScope,
                                                    onTranslated = {
                                                        if (item.translateWholeReply) {
                                                            viewModel.replaceAssistantOutput(item.messageId, it)
                                                        } else {
                                                            viewModel.replaceAssistantTextBlock(item.messageId, item.parentBlockId, it)
                                                        }
                                                    },
                                                )
                                            },
                                        ) {
                                            MarkdownBlock(
                                                rawText = item.rawText,
                                                isStreaming = item.isStreaming,
                                                shardId = TextShardId(
                                                    messageId = item.messageId,
                                                    shardId = "mdblock:${item.parentBlockId}:${item.blockIndex}",
                                                ),
                                            )
                                        }
                                    } else {
                                        MarkdownBlock(
                                            rawText = item.rawText,
                                            isStreaming = item.isStreaming,
                                            shardId = TextShardId(
                                                messageId = item.messageId,
                                                shardId = "mdblock:${item.parentBlockId}:${item.blockIndex}",
                                            ),
                                        )
                                    }
                                }
                            }
                            is FlatChatItem.AssistantProcessSummary -> ProcessSummaryBar(
                                thinkingCount = item.thinkingCount,
                                toolCount = item.toolCount,
                                expanded = item.expanded,
                                hasFailure = item.hasFailure,
                                totalMs = item.totalMs,
                                onToggle = {
                                    val id = item.messageId
                                    expandedProcessIds = if (id in expandedProcessIds) {
                                        expandedProcessIds - id
                                    } else {
                                        expandedProcessIds + id
                                    }
                                },
                            )
                            is FlatChatItem.AssistantThinking -> {
                                // [T-android-thinking-forced-visible] Render
                                // the block whenever it carries CONTENT,
                                // regardless of the session's thinking level.
                                // T300 hid OFF-level thinking entirely, but
                                // forced-reasoning models (xAI Grok 4.x,
                                // MiniMax M2, several OpenRouter routes)
                                // stream reasoning_content even when the
                                // request carries no reasoning field — that
                                // thinking was silently swallowed, and the
                                // user saw "model isn't thinking". The level
                                // is a REQUEST knob (do we ask the model to
                                // reason), not a DISPLAY filter; content that
                                // actually arrived is always shown. The OFF
                                // case still gets the collapsed default
                                // (isStreaming=false → ThinkingBlock's own
                                // collapsed presentation), which preserves
                                // T300's intent of not splashing unsolicited
                                // reasoning across the chat.
                                if (item.block.content.isNotBlank() || item.messageIsStreaming) {
                                    // [T-android-thinking-auto-collapse] Use
                                    // `isLastBlockOverall` (not `isLast` =
                                    // last-thinking-only) so the block flips
                                    // to !isStreaming the moment a sibling
                                    // text/tool_use arrives — that's the
                                    // edge ThinkingBlock's LaunchedEffect
                                    // hooks for auto-collapse, matching iOS
                                    // ThinkingBlockView semantics.
                                    ThinkingBlock(
                                        block = item.block,
                                        isStreaming = item.isLastBlockOverall && item.messageIsStreaming,
                                        isLast = item.isLast,
                                    )
                                }
                            }
                            is FlatChatItem.AssistantToolUse -> ToolCallPill(
                                block = item.block,
                                allToolBlocks = item.allToolBlocks,
                                onRetry = if (item.isLastCancelled && !isStreaming && !canResume) ({ safeMutate { viewModel.retryLast() } }) else null,
                                // T14: route per-card stop to the global
                                // cancelStream(). The button only renders
                                // when the block is RUNNING/STREAMING — see
                                // ToolCallPill `isRunning && onStop != null`
                                // — so passing it unconditionally is safe.
                                onStop = { viewModel.cancelStream() },
                                onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                                // T261: route detail open through ViewModel so
                                // the sheet is hoisted out of LazyColumn item
                                // scope (otherwise the sheet snaps shut when
                                // the pill scrolls off-screen and Compose
                                // disposes the item).
                                onOpenDetail = { viewModel.openToolDetail(it) },
                                // [T-android-rerun-from-tool-block-position]
                                // Re-run cuts at THIS tool_use block: keep the
                                // blocks before it in the same turn, drop it +
                                // everything after, then regenerate. The block
                                // id (== tool_use id for a tool_use block) is
                                // the stable anchor. Gated off while streaming
                                // (mutating an in-flight turn corrupts agent
                                // state, same rule as Retry on the user bubble).
                                // safeMutate tears down the selection toolbar
                                // before the truncation reshuffles the list.
                                onRerunFromHere = if (!isStreaming) ({
                                    coroutineScope.launch {
                                        tracedScrollToItem("RERUN-FROM-TOOL", 0, 0)
                                    }
                                    safeMutate { viewModel.rerunFromToolBlock(item.messageId, item.block.id) }
                                }) else null,
                                onCopyDetails = {
                                    val text = formatToolDetailsForClipboard(item.block)
                                    val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cb.setPrimaryClip(android.content.ClipData.newPlainText("tool", text))
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.tool_longpress_copied_toast),
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                            is FlatChatItem.AssistantInfo -> FallbackInfoBlock(
                                block = item.block,
                                // Only the compact-divider info block should
                                // surface a "Revert Compact" button on its
                                // detail sheet — other info rows (slash
                                // notices, fallback notices) have nothing
                                // to revert.
                                onRevert = if (item.block.toolName == "compact") {
                                    { viewModel.revertCompact() }
                                } else null,
                            )
                            is FlatChatItem.AssistantTyping -> TypingIndicator()
                            is FlatChatItem.AssistantError -> InlineErrorBanner(
                                error = item.error,
                                onRetry = {
                                    coroutineScope.launch { tracedScrollToItem("INLINE-RETRY-LAST", 0, 0) }
                                    safeMutate { viewModel.retryLast() }
                                },
                            )
                            is FlatChatItem.AssistantLegacyContent -> BoundsTrackedBlock(
                                messageId = item.messageId,
                                slotKey = "legacy",
                                markdown = item.messageMarkdown,
                            ) {
                                LargeContentGuard(
                                    content = item.content,
                                    isStreaming = item.isStreaming,
                                    stableKey = "legacy:${item.messageId}",
                                ) {
                                    SideEffect {
                                        selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                                    }
                                    StreamingMarkdownText(
                                        content = item.content,
                                        isStreaming = item.isStreaming,
                                        shardId = TextShardId(
                                            messageId = item.messageId,
                                            shardId = "legacy",
                                        ),
                                    )
                                }
                            }
                        }
                        } // Box (alpha wrapper)
                    }
                    if (hasOlderMessages) {
                        item(key = "__load_older__", contentType = "history_edge") {
                            HistoryPageEdge(
                                text = stringResource(
                                    if (isLoadingHistory) R.string.chat_loading_older_messages
                                    else R.string.chat_load_older_messages,
                                ),
                                onClick = {
                                    // [T-android-scroll-policy] Reading from the
                                    // moment the click lands — content followers
                                    // must stay quiet across the prepend. The old
                                    // code never set userScrolledAway here, so a
                                    // pill-paged reader still counted as "pinned"
                                    // and every stream tick yanked them back to
                                    // the bottom. The prepend itself needs no
                                    // scroll: older rows join the END of the
                                    // reversed list, so visible indices don't move
                                    // and key anchoring holds the viewport.
                                    val anchor = visibleTopAnchor()
                                    scrollPolicy.landReading(anchor?.first, anchor?.second ?: 0)
                                    viewModel.loadOlderMessages()
                                },
                            )
                        }
                    }
                }
                } // AlwaysStretchOverscrollBox
                // SelectionDragTracker bridges gesture-published dragIntent
                // with listState scroll observation — that's what keeps the
                // selection extending across newly-scrolled-in shards when
                // the user's finger is stationary in the edge auto-scroll
                // zone (the inline pointer loop can't see those because
                // it only fires on pointer events).
                SelectionDragTracker(
                    controller = selectionController,
                    listState = listState,
                    listRootCoordinates = { listRootCoords },
                    reverseLayout = true,
                )
                MinisMarkdownTextToolbarHost(markdownToolbar)
                // MinisTextKit floating toolbar — driven by selectionController.
                MinisSelectionToolbarHost(
                    controller = selectionController,
                    // Clamp the menu's vertical position inside the
                    // LazyColumn's viewport in window coords, so it can't
                    // float above the chat header or below the composer /
                    // navigation bar. Computed lazily so the menu picks up
                    // re-layout (rotation, IME show/hide, etc.) without us
                    // having to recompose this composable.
                    contentViewportBounds = {
                        val coords = listRootCoords
                        if (coords != null && coords.isAttached) {
                            val origin = coords.positionInWindow()
                            androidx.compose.ui.geometry.Rect(
                                left = origin.x,
                                top = origin.y,
                                right = origin.x + coords.size.width,
                                bottom = origin.y + coords.size.height,
                            )
                        } else null
                    },
                    actions = SelectionToolbarActions(
                        // Resolve the parent message's joined markdown via
                        // the bounds registry — only when the selection sits
                        // within a single message (cross-message selections
                        // return null and the markdown / rich-text buttons
                        // are hidden).
                        resolveSelectionMarkdown = {
                            // Use the controller's own cached
                            // message-markdown — survives both endpoint
                            // shards scrolling off-screen, unlike the rect-
                            // based MessageBoundsRegistry lookup whose
                            // entries are removed on shard dispose.
                            selectionController.selectionMessageMarkdown()
                        },
                        onAddToInput = { snippet ->
                            viewModel.appendToInputText(snippet)
                            try { inputFocusRequester.requestFocus() } catch (_: IllegalStateException) {}
                            keyboardController?.show()
                        },
                        // [T-android-selection-readaloud] Speak the selection
                        // through the same screen-scoped lazy player the
                        // Compose-SelectionContainer toolbar uses.
                        onReadAloud = { snippet -> selectionReader.speak(snippet) },
                        // [T-android-readaloud-selection-vs-reply] Replay the
                        // whole message the selection belongs to.
                        //
                        // Suppressed while a reply is streaming, matching iOS
                        // (CollectionViewMessageListV3.swift:328 — "whole-reply
                        // replay is suppressed while this reply is still
                        // streaming"). Reading a half-arrived answer from the
                        // start would narrate a text that is still growing, and
                        // the user would hear it stop mid-thought. "Read
                        // Selection" stays available throughout, because a
                        // range the user could select is a range that already
                        // exists.
                        onReadFromStart = if (isStreaming) null else {
                            { fullText -> selectionReader.speak(fullText) }
                        },
                    ),
                )
                // iOS-style selection handle dots, one at each endpoint.
                MinisSelectionHandlesHost(
                    controller = selectionController,
                    listState = listState,
                    reverseLayout = true,
                )
                } // Box (selection scope)
                } // CompositionLocalProvider

                // Floating tool status bar — shows only actual tool calls (not text/thinking/info).
                // Matches iOS: filter on toolStatus != nil (text blocks have toolStatus = null).
                //
                // T-streaming-side-channel-tool-blocks: derive lastToolBlocks
                // from a state that combines messages + streamingById INSIDE
                // a LaunchedEffect (not via a top-level collectAsState read),
                // so streaming-tick churn stays off the ChatScreen invalidation
                // list. Without including streamingById, a tool pill clicked
                // mid-turn is missing from lastToolBlocks → ToolDetailSheet
                // never opens (and its sentinel LaunchedEffect immediately
                // closes the detail state because the id "doesn't exist").
                var lastToolBlocks by remember { mutableStateOf<List<AssistantBlock>>(emptyList()) }
                var detailToolBlocks by remember { mutableStateOf<List<AssistantBlock>>(emptyList()) }
                LaunchedEffect(messages, foldAiProcess, showSubAgentBar) {
                    kotlinx.coroutines.flow.combine(
                        kotlinx.coroutines.flow.flowOf(messages),
                        viewModel.streamingById,
                    ) { msgs, stream ->
                        val merged = if (stream.isEmpty()) msgs else mergeStreamingOverlay(msgs, stream)
                        val all = assistantToolUseBlocks(merged)
                        // The floating strip belongs to the latest assistant
                        // reply, not to every tool in the session. Keep the
                        // complete list separately for historical details.
                        val latestReply = merged.lastOrNull { it.role == "assistant" }
                        // [P0-4] Same projection the mount gate uses, so the
                        // pill's data and its bottomReserve cannot disagree.
                        val overlay = floatingOverlayBlocks(
                            latestReply,
                            foldAiProcess,
                            showSubAgentBar,
                            activeSubAgentToolIds,
                        )
                        all to overlay
                    }.collect { (all, overlay) ->
                        detailToolBlocks = all
                        lastToolBlocks = overlay
                    }
                }
                // [P0-4] The pill pins the LATEST tool of the streaming turn —
                // completed OR in-flight. Staying mounted between consecutive
                // tools keeps the bottomReserve stable (no collapse/expand
                // jitter between tools); it unmounts with the stream, which is
                // exactly when hasFloatingTools above drops the reserve.
                val liveToolBlock = lastToolBlocks.lastOrNull()
                if (showFloatingToolBar && isStreaming && liveToolBlock != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            // [P0-4] The pill sizes to its content and centres;
                            // the old bar filled the column. The content-width
                            // cap still applies so a long tool title cannot
                            // outgrow the conversation column on a tablet.
                            .widthIn(max = CHAT_MAX_CONTENT_WIDTH)
                            .onGloballyPositioned { toolBarHeightPx = it.size.height }
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                    ) {
                        // [P0-4] ToolActivityPill: single quiet capsule (see
                        // its header for what replaced the old bar).
                        ToolActivityPill(
                            block = liveToolBlock,
                            // T14 lineage: same global cancel as the in-list
                            // pill's stop button.
                            onStop = { viewModel.cancelStream() },
                            // T261: route detail open through the same VM
                            // state as in-list pills so both surfaces share
                            // one always-mounted sheet instance.
                            onOpenDetail = { viewModel.openToolDetail(it) },
                        )
                    }
                } else {
                    SideEffect { toolBarHeightPx = 0 }
                }

                // [T-android-tts-capsule] Floating speech-player control for
                // "Read replies" TTS — expand/compact capsule with mute, model
                // switch and speed cycling. Mounted LAST in this Box so it
                // draws above the list, the FABs and the floating tool bar
                // (iOS mounts its SpeechPlayerControl at app root; chat-screen
                // scope is the Android first pass).
                // [T-android-tts-capsule-avoid] toolBarHeightPx is the same
                // measurement bottomReserve uses — the capsule lifts above the
                // floating tool bar instead of covering its trailing edge.
                // [T-android-tts-capsule-avoid-fabs] The scroll FABs share the
                // capsule's bottom-end corner and OVERLAPPED it (user report:
                // capsule stacked on the jump-to-user-message / scroll-to-
                // bottom buttons). Mirror their exact placement math — same
                // visibility predicates, same base offsets as the FAB blocks
                // below — so the capsule clears the TOP of whatever part of
                // the FAB stack is currently visible, and drops back when the
                // FABs hide. This is the Android stand-in for iOS's
                // protectedRects: derived from the same layout constants
                // instead of measured rects, which keeps it deterministic.
                val upFabVisible = messages.isNotEmpty() && !isNearBottom.value
                val downFabVisible = messages.isNotEmpty() && (
                    hasNewerMessages || (scrollMode is ScrollMode.Reading && contentOverflows.value)
                    )
                val fabBaseDp = if (hasFloatingTools) 48.dp else 8.dp
                val fabStackTopDp = when {
                    upFabVisible -> fabBaseDp + 46.dp + 36.dp
                    downFabVisible -> fabBaseDp + 36.dp
                    else -> 0.dp
                }
                com.openminis.app.ui.chat.voice.SpeechPlayerCapsule(
                    bottomObstructionPx = toolBarHeightPx,
                    additionalObstructionDp = fabStackTopDp,
                )

                // T261: tool-detail sheet hoisted out of LazyColumn item
                // scope. Visibility driven by ViewModel state so streaming /
                // pill-disposal / new-tool emissions can't snap it shut.
                // existence guard auto-closes the sheet when the underlying
                // block disappears (T258 retry-preserve removes in-flight
                // tools, clearChat, etc.). Uses detailToolBlocks (all tool_use
                // rows, including folded completed ones) so opening a chip
                // after foldAiProcess collapses the overlay still works.
                val selectedToolDetailId by viewModel.selectedToolDetailId.collectAsState()
                LaunchedEffect(selectedToolDetailId, detailToolBlocks) {
                    val id = selectedToolDetailId ?: return@LaunchedEffect
                    if (detailToolBlocks.none { it.id == id }) viewModel.closeToolDetail()
                }
                val selectedToolBlock = selectedToolDetailId?.let { id ->
                    detailToolBlocks.firstOrNull { it.id == id }
                }
                if (selectedToolBlock != null) {
                    val initialIdx = detailToolBlocks
                        .indexOfFirst { it.id == selectedToolBlock.id }
                        .coerceAtLeast(0)
                    ToolDetailSheet(
                        toolBlocks = detailToolBlocks,
                        initialIndex = initialIdx,
                        onDismiss = { viewModel.closeToolDetail() },
                        onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                        onOpenBrowserForUrl = { url ->
                            viewModel.closeToolDetail()
                            viewModel.openBrowserSheetForUrl(url)
                        },
                        onOpenSpillPath = { guest ->
                            val sid = viewModel.realSessionId.ifBlank { sessionId }
                            val file = com.openminis.app.tools.ToolOutputSpill.hostFile(context, sid, guest)
                            if (file != null) {
                                viewModel.closeToolDetail()
                                previewFileOrBrowser(
                                    com.openminis.app.ui.sandbox.FileItem(
                                        file = file,
                                        name = file.name,
                                        isDirectory = false,
                                        isSymlink = false,
                                        size = file.length(),
                                        modifiedMs = file.lastModified(),
                                    )
                                )
                            }
                        },
                    )
                }

                // [T-android-scroll-fab-content-inset] Keep the two scroll
                // buttons on the conversation's right edge, not the pane's.
                //
                // They are BottomEnd-aligned in the same Box as the message
                // list, so a fixed end padding pins them to whatever the pane
                // happens to be. Once the content gained a 900dp cap and
                // centred itself, that left them stranded far outside the text
                // on a tablet. Adding back half the leftover width puts them on
                // the column's edge instead, so they track it at any pane
                // width.
                //
                // Zero on a phone: the pane is narrower than the cap, leftover
                // is 0, and the padding stays the original 12dp.
                val fabEndInset = with(LocalDensity.current) {
                    val leftover = chatPaneWidthPx.toDp() - CHAT_MAX_CONTENT_WIDTH
                    if (leftover > 0.dp) leftover / 2 else 0.dp
                }

                // Scroll-to-bottom FAB (iOS: circle chevron.down, bottom-right)
                // T138 phase 2 v3: show on user-scroll intent, not transient
                // layout state. Otherwise the FAB flickers whenever multi-tool
                // emissions briefly bump the bottom item off-screen during
                // re-anchoring.
                //
                // T170: gate also on `contentOverflows` so short sessions
                // (one Q+A on a tall screen) never flash the FAB if an IME
                // animation produces a synthetic drag-stop. iOS gets this
                // for free via `maxOffset > 0`; Compose needs the explicit
                // check.
                // [T-android-scrollbtn-turn-walk] Floating up-button. Visibility
                // is now the SHARED `!isNearBottom` condition (iOS dcdec3c5),
                // replacing the separate isFarFromTop && isFarFromBottom
                // middle-region gate: both floating buttons now appear together
                // on the same signal, which is what the iOS refactor converged
                // on. Sits ABOVE the scroll-to-bottom button (same BottomEnd
                // anchor, extra bottom padding = down-button height 36dp + 10dp
                // spacing). Tapping walks BACK one user turn at a time rather
                // than jumping to the oldest message.
                if (messages.isNotEmpty() && !isNearBottom.value) {
                    val upBaseBottom = if (hasFloatingTools) 48.dp else 8.dp
                    androidx.compose.material3.FilledIconButton(
                        onClick = {
                            // [T-android-scroll-policy] Land in Reading
                            // immediately: this jump is a browse action, and
                            // the seek below moves the viewport with
                            // scrollToItem (instant) — no isScrollInProgress
                            // edge ever fires, so nothing else can arm the
                            // mode for us. (Old code set userScrolledAway=true
                            // here for the same reason.)
                            val anchor = visibleTopAnchor()
                            scrollPolicy.landReading(anchor?.first, anchor?.second ?: 0)
                            val job = upWalkJob
                            if (job != null && job.isActive) {
                                upWalkQueuedCount = (upWalkQueuedCount + 1).coerceAtMost(2)
                            } else {
                                upWalkJob = coroutineScope.launch {
                                    scrollToPreviousUserTurn()
                                    while (upWalkQueuedCount > 0) {
                                        upWalkQueuedCount--
                                        scrollToPreviousUserTurn()
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp + fabEndInset, bottom = upBaseBottom + 46.dp)
                            .shadow(4.dp, CircleShape)
                            .size(36.dp),
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                            containerColor = ChatColors.inputBg,
                            contentColor = ChatColors.primaryText,
                        ),
                    ) {
                        Icon(
                            // Matches iOS's `arrow.up.to.line` (AIChatView.swift:2501):
                            // an arrow pointing at a top line reads as "jump to a top
                            // anchor" for the turn-walk, and keeps this button visually
                            // distinct from the down button's plain chevron.
                            imageVector = Icons.Default.VerticalAlignTop,
                            contentDescription = stringResource(R.string.cd_scroll_prev),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                if (downFabVisible) {
                    val fabBottomPadding = if (hasFloatingTools) 48.dp else 8.dp
                    androidx.compose.material3.FilledIconButton(
                        onClick = {
                            // [T-android-scroll-fab-down-stuck] Pin the mode
                            // SYNCHRONOUSLY on tap — that alone hides the FAB
                            // when the tail is already loaded. Don't rely on a
                            // position-driven auto-reset: on a long
                            // reverseLayout session scrollToItem(0,0) can
                            // settle on a non-zero firstVisibleItemIndex while
                            // unmeasured items resolve (logged: FAB-DOWN tap left
                            // firstIdx=41/60, canBwd=false), so isNearBottom stays
                            // false and a position check would keep the FAB
                            // stuck visible. The user tapped "go to bottom" — the
                            // intent is unambiguous.
                            scrollPolicy.pin()
                            // The same button reaches the real session tail, not
                            // only the currently painted suffix.
                            pinRealLatest = true
                            viewModel.ensureSessionTailLoaded()
                            // [T-android-scrollbtn-turn-walk] Jumping to the
                            // bottom resets the up-button's turn-walk (iOS does
                            // the same in its forceScrollToBottom handler).
                            lastJumpedUserId = null
                            coroutineScope.launch {
                                tracedScrollToItem("FAB-DOWN", 0, 0)
                                // Second pin after a frame: the first scroll may
                                // land short while late-measuring items shift the
                                // true bottom; re-issue once layout settles.
                                kotlinx.coroutines.delay(100)
                                tracedScrollToItem("FAB-DOWN/settle", 0, 0)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp + fabEndInset, bottom = fabBottomPadding)
                            .shadow(4.dp, CircleShape)
                            .size(36.dp),
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                            containerColor = ChatColors.inputBg,
                            contentColor = ChatColors.primaryText,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.cd_scroll_bottom),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // T51 / T185: the "Move to…" capsule was previously rendered
                // here, on top of the message list. After the user actually
                // sends the share-injected turn, the capsule was overlapping
                // the user-message bubble area and obscuring attachment chips.
                // Moved to the composer's top-right corner — see the Box
                // overlay around the input Column below, mirroring iOS
                // AIChatView.swift:1817 (.overlay(alignment: .topTrailing)).

                // T-chat-title-pill: sticky session title overlay. Sits
                // above the LazyColumn (top-center), animates in once the
            }

            // T-chat-title-pill-edit: reuse SessionEditSheet from the session
            // list (same composable, exposed `internal`) so title + category
            // edits from the in-chat pill are visually + behaviourally
            // identical to the home-screen long-press flow.
            editingSession?.let { session ->
                com.openminis.app.ui.sessions.SessionEditSheet(
                    session = session,
                    onDismiss = { editingSession = null },
                    onSave = { newTitle, newCategory ->
                        viewModel.updateTitleAndCategory(newTitle, newCategory)
                        editingSession = null
                    },
                )
            }

            // [T-android-slash-popup-width] The composer's laid-out width, read
            // back by the slash / mention popups below so they line up with it.
            var composerWidthPx by remember { mutableStateOf(0) }

            // ─── Input area (iOS-style: rounded box with text + buttons below) ───
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // [T-android-chat-max-content-width] Same cap and centring
                    // as the message list, so the composer stays aligned with
                    // the conversation instead of spanning a wide pane on its
                    // own. iOS applies maxContentWidth to both for this reason
                    // (AIChatView: the ComposerSurface and the list share it).
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = CHAT_MAX_CONTENT_WIDTH)
                    // [T-android-slash-popup-width] Publish the composer's real
                    // laid-out width so the slash / mention popups can match it.
                    // A Popup is a separate window: `fillMaxWidth` inside one
                    // fills the WINDOW, not the anchor, so without this the menu
                    // spans the whole tablet — starting under the session list
                    // and running past the composer's right edge.
                    //
                    // MUST come AFTER widthIn/wrapContentWidth. A modifier
                    // measures the node as constrained by the modifiers BEFORE
                    // it, so placed above the cap this reported the full pane
                    // width and the popup faithfully matched the wrong number —
                    // which is exactly how the first attempt at this fix still
                    // looked broken.
                    .onGloballyPositioned { composerWidthPx = it.size.width }
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(top = 2.dp, bottom = 8.dp),
            ) {
                val pendingUserQuestions by viewModel.pendingUserQuestions.collectAsState()
                pendingUserQuestions?.let { qs ->
                    AskUserQuestionsCard(
                        questions = qs,
                        onSubmit = { viewModel.submitUserQuestionAnswers(it) },
                        onSkip = { viewModel.skipUserQuestions() },
                    )
                }

                // T13 banner moved INSIDE the LazyColumn so it renders at the
                // visual end of the message list (mirrors iOS — see the
                // banner item before items() in the LazyColumn block above).

                // Slash-command menu (mirrors iOS slashCommandMenu) — rendered as
                // a Popup so it overlays content (tool status bar, chat list)
                // instead of pushing them up. Anchored above the composer via
                // PopupProperties so its bottom edge sits just above this Column.
                // Tap-outside dismisses via dismissOnClickOutside.
                val showSlashMenu by viewModel.showSlashMenu.collectAsState()
                val filteredSlashCommands = remember(
                    showSlashMenu,
                    viewModel.slashFilter.collectAsState().value,
                    viewModel.memoryEnabled.collectAsState().value,
                    viewModel.thinkingLevel.collectAsState().value,
                ) { viewModel.filteredSlashCommands() }

                if (showSlashMenu && filteredSlashCommands.isNotEmpty()) {
                    val thinkingLevelState by viewModel.thinkingLevel.collectAsState()
                    val thinkingSupported = viewModel.currentModelSupportsReasoning
                    val memoryOnState by viewModel.memoryEnabled.collectAsState()
                    androidx.compose.ui.window.Popup(
                        popupPositionProvider = remember {
                            object : androidx.compose.ui.window.PopupPositionProvider {
                                override fun calculatePosition(
                                    anchorBounds: androidx.compose.ui.unit.IntRect,
                                    windowSize: androidx.compose.ui.unit.IntSize,
                                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                    popupContentSize: androidx.compose.ui.unit.IntSize,
                                ): androidx.compose.ui.unit.IntOffset {
                                    // Anchor: top-edge of the composer column. Place
                                    // the popup so its bottom sits 12dp above that edge.
                                    // T301: bumped from 6dp — at 6dp the panel was
                                    // visually glued to the composer; 12dp gives a
                                    // clear breathing gap matching the iOS spacing.
                                    val gap = 12
                                    val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                    val y = (anchorBounds.top - popupContentSize.height - gap)
                                        .coerceAtLeast(0)
                                    return androidx.compose.ui.unit.IntOffset(x, y)
                                }
                            }
                        },
                        onDismissRequest = {
                            viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                        },
                        properties = androidx.compose.ui.window.PopupProperties(
                            focusable = false,
                            dismissOnBackPress = true,
                            // dismissOnClickOutside=false: with focusable=false the popup
                            // never receives focus, so the system "click outside" detector
                            // can't tell a tap on the BasicTextField below from a tap on
                            // the chat list — flipping this off would dismiss the popup
                            // every time the IME caret was moved. Dismiss is driven from
                            // the chat list / topbar tap-spy below instead, which lets
                            // the input field keep focus while still closing the menu
                            // when the user clearly looks elsewhere.
                            dismissOnClickOutside = false,
                        ),
                    ) {
                        // [T-slash-picker-fixed-height port from iOS 73f1b94a]
                        // Locked popup height = 4 rows × 46dp + 8dp = 192dp.
                        // Short lists show empty space below the last row;
                        // long lists scroll inside the same frame with a
                        // visible scroll indicator. Prevents installed
                        // Skills + built-ins from pushing the menu past
                        // the input bar / off the top of the screen.
                        val slashListState = androidx.compose.foundation.lazy.rememberLazyListState()
                        Box(
                            modifier = Modifier
                                // [T-android-slash-popup-width] Match the
                                // composer, not the window. A Popup is its own
                                // window, so fillMaxWidth here filled the whole
                                // tablet; the menu began under the session list
                                // and overhung the composer's right edge. Fall
                                // back to fillMaxWidth only until the first
                                // measurement arrives (width 0 would collapse
                                // the panel on the very first frame).
                                .then(
                                    if (composerWidthPx > 0) {
                                        Modifier.width(
                                            with(LocalDensity.current) { composerWidthPx.toDp() },
                                        )
                                    } else {
                                        Modifier.fillMaxWidth()
                                    },
                                )
                                .padding(horizontal = 12.dp)
                                // T240: keep a thin visible border instead of the
                                // diffuse 8dp halo that bled out past the panel edge.
                                .shadow(elevation = 3.dp, shape = RoundedCornerShape(10.dp))
                                .background(ChatColors.inputBg, RoundedCornerShape(10.dp))
                                .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp)),
                        ) {
                            androidx.compose.foundation.lazy.LazyColumn(
                                state = slashListState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // [T-android-slash-picker-fontscale] Defaults
                                    // match this list's own row (14sp/11sp text,
                                    // 7dp padding, 18dp icon).
                                    .height(slashPickerHeight())
                                    .verticalScrollbar(slashListState),
                            ) {
                            itemsIndexed(filteredSlashCommands, key = { _, c -> c.id }) { index, cmd ->
                                // Section divider between builtins and
                                // installed Skills (mirrors iOS divider
                                // at the first skill row). Drawn as the
                                // top of the skill row, not between every
                                // row — keeps the menu visually grouped
                                // without splitting every command.
                                if (cmd.isSkill && index > 0 && !filteredSlashCommands[index - 1].isSkill) {
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = ChatColors.toolBorder,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                                val isThinking = cmd.id == "thinking"
                                val isThinkingActive = isThinking && thinkingLevelState.isEnabled && thinkingSupported
                                val titleColor = if (isThinkingActive) ChatColors.sendButton else ChatColors.primaryText
                                val subtitleColor = if (isThinking && !thinkingSupported) {
                                    ChatColors.secondaryText
                                } else if (isThinkingActive) {
                                    ChatColors.sendButton.copy(alpha = 0.7f)
                                } else ChatColors.secondaryText
                                val iconTint = if (isThinkingActive) ChatColors.sendButton else ChatColors.primaryText
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .let {
                                            if (!isThinking) {
                                                it.clickable {
                                                    // [T-android-slash-menu-clears-input] Pass the
                                                    // LIVE input so an action command keeps the
                                                    // user's body text instead of wiping it.
                                                    viewModel.setInputText(viewModel.executeSlashCommand(cmd, inputText))
                                                    // For Skill rows, "/<name> "
                                                    // is a typing aid — the user
                                                    // still needs to type
                                                    // arguments. Bring the IME
                                                    // back up + grab focus so
                                                    // they can keep typing
                                                    // without an extra tap on
                                                    // the composer.
                                                    if (cmd.isSkill) {
                                                        try {
                                                            inputFocusRequester.requestFocus()
                                                        } catch (_: IllegalStateException) {
                                                            // FocusRequester not attached yet.
                                                        }
                                                        keyboardController?.show()
                                                    }
                                                }
                                            } else if (thinkingSupported) {
                                                it.clickable {
                                                    val newLevel = if (thinkingLevelState.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
                                                    viewModel.setThinkingLevel(newLevel)
                                                }
                                            } else it
                                        }
                                        // [T-android-slash-menu-density] Tighter
                                        // vertical padding (10→7) so slash rows
                                        // read as compact as iOS, not sparse.
                                        .padding(horizontal = 14.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = cmd.icon,
                                        contentDescription = null,
                                        tint = iconTint,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "/${cmd.title.lowercase()}",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = titleColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        // Cap to one line + ellipsis (mirrors
                                        // iOS T-slash-picker-product-rules
                                        // 051896e2). Long Skill descriptions
                                        // would otherwise stretch the row,
                                        // breaking the locked 4-row band and
                                        // crowding the menu visually.
                                        Text(
                                            text = cmd.subtitle,
                                            fontSize = 11.sp,
                                            color = subtitleColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (cmd.id == "memory") {
                                        Icon(
                                            imageVector = if (memoryOnState) Icons.Default.CheckCircle else Icons.Default.Block,
                                            contentDescription = null,
                                            tint = if (memoryOnState) ChatColors.sendButton else ChatColors.secondaryText,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    if (isThinking && thinkingSupported) {
                                        ThinkingLevelPicker(
                                            current = thinkingLevelState,
                                            // [T-android-thinking-level-arch] Only
                                            // offer tiers the bound model supports.
                                            availableLevels = viewModel.availableThinkingLevels,
                                            onSelect = { level -> viewModel.setThinkingLevel(level) },
                                        )
                                    }
                                }
                            }
                            }
                        }
                    }
                }

                // T4: @ file-mention picker — same anchoring + tap-spy
                // contract as the slash popup (mutually exclusive in the VM,
                // so they never both render). Reuses Popup so the bar over
                // the composer is consistent and respects IME inset.
                val showMentionMenu by viewModel.showMentionMenu.collectAsState()
                val mentionEntries by viewModel.mentionEntries.collectAsState()
                val isMentionScanning by viewModel.isMentionScanning.collectAsState()
                val mentionSelectedIndex by viewModel.mentionSelectedIndex.collectAsState()
                val groupChatMentioning by viewModel.mentionListsModels.collectAsState()
                val groupMentions by viewModel.groupMentions.collectAsState()
                if (showMentionMenu) {
                    androidx.compose.ui.window.Popup(
                        popupPositionProvider = remember {
                            object : androidx.compose.ui.window.PopupPositionProvider {
                                override fun calculatePosition(
                                    anchorBounds: androidx.compose.ui.unit.IntRect,
                                    windowSize: androidx.compose.ui.unit.IntSize,
                                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                    popupContentSize: androidx.compose.ui.unit.IntSize,
                                ): androidx.compose.ui.unit.IntOffset {
                                    val gap = 6
                                    val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                    val y = (anchorBounds.top - popupContentSize.height - gap)
                                        .coerceAtLeast(0)
                                    return androidx.compose.ui.unit.IntOffset(x, y)
                                }
                            }
                        },
                        onDismissRequest = { viewModel.dismissMentionMenu() },
                        properties = androidx.compose.ui.window.PopupProperties(
                            focusable = false,
                            dismissOnBackPress = true,
                            // Same rationale as the slash popup: dismissOnClickOutside=false
                            // because the input field below the popup sits in the
                            // "outside" region (focusable=false → caret moves still
                            // count as outside). The chat-list tap-spy that drives
                            // dismissSlashMenu also dismisses this menu via
                            // dismissMentionMenu(); see the LazyColumn pointerInput.
                            dismissOnClickOutside = false,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                // [T-android-slash-popup-width] Same as the
                                // slash menu — match the composer, not the
                                // popup's own window.
                                .then(
                                    if (composerWidthPx > 0) {
                                        Modifier.width(
                                            with(LocalDensity.current) { composerWidthPx.toDp() },
                                        )
                                    } else {
                                        Modifier.fillMaxWidth()
                                    },
                                )
                                .padding(horizontal = 12.dp)
                                .shadow(elevation = 8.dp, shape = RoundedCornerShape(10.dp))
                                .background(ChatColors.inputBg, RoundedCornerShape(10.dp))
                                .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp)),
                        ) {
                            if (groupChatMentioning) {
                                Text(
                                    text = stringResource(R.string.mention_model_caption),
                                    fontSize = 11.sp,
                                    color = ChatColors.secondaryText,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                                if (groupMentions.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.mention_no_model),
                                        fontSize = 13.sp,
                                        color = ChatColors.secondaryText,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    )
                                } else {
                                    val modelListState = androidx.compose.foundation.lazy.rememberLazyListState()
                                    LaunchedEffect(mentionSelectedIndex, groupMentions.size) {
                                        val idx = mentionSelectedIndex
                                        if (idx in groupMentions.indices) {
                                            modelListState.animateScrollToItem(idx)
                                        }
                                    }
                                    LazyColumn(
                                        state = modelListState,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(
                                                slashPickerHeight(
                                                    titleSp = 13.sp,
                                                    subtitleSp = 11.sp,
                                                    verticalPadding = 8.dp,
                                                    iconSize = 16.dp,
                                                ),
                                            )
                                            .verticalScrollbar(modelListState),
                                    ) {
                                        itemsIndexed(
                                            groupMentions,
                                            key = { _, e -> "${e.host}|${e.modelId}|${e.name}" },
                                        ) { i, entry ->
                                            val isSelected = i == mentionSelectedIndex
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        if (isSelected) {
                                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                        } else {
                                                            Color.Transparent
                                                        },
                                                    )
                                                    .clickable {
                                                        val (newText, newCaret) = viewModel.selectGroupMention(
                                                            entry,
                                                            currentText = inputFieldValue.text,
                                                            currentCaret = inputFieldValue.selection.end,
                                                        )
                                                        viewModel.setInputText(newText)
                                                        inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                                                            text = newText,
                                                            selection = androidx.compose.ui.text.TextRange(newCaret),
                                                        )
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                VendorMark(
                                                    vendor = entry.vendor,
                                                    fallbackName = entry.name,
                                                    size = 16.dp,
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = entry.name,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = ChatColors.primaryText,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Text(
                                                        text = when {
                                                            entry.host &&
                                                                entry.extra.isNotBlank() &&
                                                                entry.extra != entry.name ->
                                                                entry.extra + " · " + entry.modelId.ifBlank { entry.name }
                                                            entry.modelId.isNotBlank() &&
                                                                entry.modelId != entry.name -> entry.modelId
                                                            else -> stringResource(R.string.mention_model_only)
                                                        },
                                                        fontSize = 11.sp,
                                                        color = ChatColors.secondaryText,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = stringResource(
                                                        if (entry.host) R.string.group_chat_host_suffix
                                                        else R.string.mention_model_badge
                                                    ),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = ChatColors.secondaryText,
                                                    modifier = Modifier
                                                        .background(
                                                            ChatColors.toolCapsuleBg,
                                                            RoundedCornerShape(8.dp),
                                                        )
                                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            } else if (mentionEntries.isEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (isMentionScanning) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 1.5.dp,
                                            color = ChatColors.secondaryText,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = stringResource(
                                            if (isMentionScanning) R.string.mention_scanning
                                            else R.string.mention_no_match
                                        ),
                                        fontSize = 13.sp,
                                        color = ChatColors.secondaryText,
                                    )
                                }
                            } else {
                                // [T-slash-picker-fixed-height port from iOS 73f1b94a]
                                // Mention picker shares the slash picker's
                                // locked 192dp height (4 rows × 46dp + 8dp)
                                // so both popups have the same band on screen.
                                val mentionListState = androidx.compose.foundation.lazy.rememberLazyListState()
                                // Keep the highlighted row visible when the user
                                // navigates with a hardware keyboard. iOS gets this
                                // for free from SwiftUI's List/scrollTo binding;
                                // mimic it explicitly here.
                                LaunchedEffect(mentionSelectedIndex, mentionEntries.size) {
                                    val idx = mentionSelectedIndex
                                    if (idx in mentionEntries.indices) {
                                        mentionListState.animateScrollToItem(idx)
                                    }
                                }
                                LazyColumn(
                                    state = mentionListState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // [T-android-slash-picker-fontscale] Same
                                        // clipping applies here — this list shares
                                        // the band height but has its OWN row
                                        // metrics (13sp/11sp, 8dp padding, 16dp
                                        // icon), so it passes them rather than
                                        // inheriting the slash row's.
                                        .height(
                                            slashPickerHeight(
                                                titleSp = 13.sp,
                                                subtitleSp = 11.sp,
                                                verticalPadding = 8.dp,
                                                iconSize = 16.dp,
                                            ),
                                        )
                                        .verticalScrollbar(mentionListState),
                                ) {
                                    itemsIndexed(mentionEntries, key = { _, e -> e.linuxPath }) { i, entry ->
                                        val isSelected = i == mentionSelectedIndex
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (isSelected) {
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                    } else {
                                                        Color.Transparent
                                                    },
                                                )
                                                .clickable {
                                                    val (newText, newCaret) = viewModel.selectMention(
                                                        entry,
                                                        currentText = inputFieldValue.text,
                                                        currentCaret = inputFieldValue.selection.end,
                                                    )
                                                    viewModel.setInputText(newText)
                                                    inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                                                        text = newText,
                                                        selection = androidx.compose.ui.text.TextRange(newCaret),
                                                    )
                                                }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // Single doc icon for every entry — the scope/mount
                                            // capsule on the right already labels what bucket
                                            // this is (workspace / skills / shared / memory /
                                            // <mountName>). iOS varies the icon per scope but
                                            // we keep it uniform here so the row stays
                                            // visually consistent at small sizes on Pixel 4a.
                                            Icon(
                                                imageVector = Icons.Default.Description,
                                                contentDescription = null,
                                                tint = ChatColors.secondaryText,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = entry.basename,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = ChatColors.primaryText,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = entry.displayPath,
                                                    fontSize = 11.sp,
                                                    color = ChatColors.secondaryText,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            // Scope / mount badge — matches iOS capsule.
                                            Text(
                                                text = entry.mountName ?: entry.scope.displayLabel,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = ChatColors.secondaryText,
                                                modifier = Modifier
                                                    .background(
                                                        ChatColors.toolCapsuleBg,
                                                        RoundedCornerShape(8.dp),
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Input box: iOS-style floating card — no visible border, separated
                // from the backdrop by a symmetric soft shadow painted by hand
                // (Android's Modifier.shadow only casts downward).
                val inputBgArgb = ChatColors.inputBg.toArgb()
                val shadowPaint = remember(inputBgArgb) {
                    android.graphics.Paint().apply {
                        color = inputBgArgb
                        isAntiAlias = true
                    }
                }
                // T185: Move-to-session capsule mirrors iOS
                // AIChatView.swift:1816 (.overlay(alignment: .topTrailing))
                // on the input card. We render it as the first child of the
                // composer Column, right-aligned, so it visually sits inside
                // the input card's top-right corner — Compose doesn't have a
                // free overlay primitive that doesn't need a Box wrapper,
                // and an in-flow Row at the top with Arrangement.End is the
                // cleanest equivalent.
                val showMoveCapsule by viewModel.hasInjectedShareContent.collectAsState()
                // Mirrors iOS swipe-up-to-send: drag the input bar upward —
                // if it holds text, a floating send-arrow + "Release to send"
                // capsule track the finger; releasing past `swipeArmFraction`
                // sends. With empty text + collapsed keyboard, releasing
                // activates the keyboard instead. Box wraps the existing
                // composer Column so the gesture + overlay live in the same
                // coordinate space without disturbing the bar's own layout.
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalDx = 0f
                            var totalDy = 0f
                            var claimed = false
                            var lastPos = down.position
                            verticalDrag(down.id) { change ->
                                val delta = change.positionChange()
                                totalDx += delta.x
                                totalDy += delta.y
                                lastPos = change.position
                                if (!claimed) {
                                    // Wait until a clearly vertical drag of
                                    // at least `slop` px before claiming.
                                    // Below that the TextField / list still
                                    // get the events (taps, text scroll, …).
                                    if (kotlin.math.abs(totalDy) < slop) return@verticalDrag
                                    if (kotlin.math.abs(totalDy) <= kotlin.math.abs(totalDx)) return@verticalDrag
                                    claimed = true
                                }
                                change.consume()
                                if (totalDy < 0) {
                                    // Swiping up. Show hint only when there
                                    // is text to send; otherwise keep the
                                    // overlay hidden and defer keyboard
                                    // activation to onEnd.
                                    val hasText = viewModel.inputText.value.isNotBlank()
                                    if (hasText) {
                                        val newProgress = (-totalDy / swipeThresholdPx).coerceIn(0f, 1f)
                                        if (newProgress >= swipeArmFraction && sendSwipeProgress < swipeArmFraction) {
                                            swipeHaptics.performHapticFeedback(
                                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                            )
                                        }
                                        sendSwipeProgress = newProgress
                                        sendSwipeLocation = lastPos
                                    } else if (sendSwipeProgress != 0f) {
                                        sendSwipeProgress = 0f
                                    }
                                } else if (sendSwipeProgress != 0f) {
                                    // Reversed direction; clear any hint.
                                    sendSwipeProgress = 0f
                                }
                            }
                            // Drag ended (finger up or pointer cancel).
                            val hasText = viewModel.inputText.value.isNotBlank()
                            val swipedUp = claimed && totalDy < 0 &&
                                kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx)
                            if (swipedUp && hasText) {
                                val attachmentsCount = viewModel.attachments.value.size
                                val canSendNow = hasText || attachmentsCount > 0
                                if (sendSwipeProgress >= swipeArmFraction && canSendNow) {
                                    // T-drag-send-queue: route through the
                                    // shared send-or-enqueue handler so a
                                    // drag-to-send during streaming enqueues
                                    // the prompt instead of being dropped —
                                    // matches the send-button tap path which
                                    // already enqueues mid-stream via
                                    // viewModel.sendMessage → enqueuePrompt.
                                    performSendOrEnqueue(viewModel.inputText.value)
                                }
                                sendSwipeProgress = 0f
                            } else if (swipedUp && !hasText && !inputFocused) {
                                // Empty input + collapsed keyboard -> bring
                                // up the keyboard. If the keyboard is
                                // already open, do nothing so a stray drag
                                // doesn't re-trigger anything.
                                inputFocusRequester.requestFocus()
                                keyboardController?.show()
                                sendSwipeProgress = 0f
                            } else {
                                sendSwipeProgress = 0f
                            }
                        }
                    },
                ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            val radiusPx = 20.dp.toPx()
                            val canvas = drawContext.canvas.nativeCanvas
                            // Pass 1: symmetric ambient halo — small blur, low alpha.
                            shadowPaint.setShadowLayer(
                                6.dp.toPx(), 0f, 0f,
                                android.graphics.Color.argb(22, 0, 0, 0),
                            )
                            canvas.drawRoundRect(
                                0f, 0f, size.width, size.height,
                                radiusPx, radiusPx,
                                shadowPaint,
                            )
                            // Pass 2: soft downward shadow (spot light).
                            shadowPaint.setShadowLayer(
                                10.dp.toPx(), 0f, 3.dp.toPx(),
                                android.graphics.Color.argb(24, 0, 0, 0),
                            )
                            canvas.drawRoundRect(
                                0f, 0f, size.width, size.height,
                                radiusPx, radiusPx,
                                shadowPaint,
                            )
                        }
                        .padding(top = if (attachments.isNotEmpty()) 8.dp else 4.dp),
                ) {
                    // T185: Move-to capsule lives INSIDE the composer card,
                    // pinned 8dp from the top-right corner, mirroring iOS
                    // AIChatView.swift:1816 (.overlay(alignment: .topTrailing)
                    // padding(.top, 6).padding(.trailing, 10)). A Popup
                    // keeps it out of the composer's layout flow so the
                    // attachment row + text field still own the full
                    // vertical rhythm.
                    if (showMoveCapsule) {
                        // T185: align Move-to right edge with the
                        // attachment row + button row (both 12dp). The
                        // anchorBounds rect is in px, so convert via
                        // LocalDensity rather than treating the constant
                        // as dp directly.
                        val popupDensity = androidx.compose.ui.platform.LocalDensity.current
                        val rightInsetPx = with(popupDensity) { 12.dp.roundToPx() }
                        val topInsetPx = with(popupDensity) { 6.dp.roundToPx() }
                        androidx.compose.ui.window.Popup(
                            popupPositionProvider = remember(rightInsetPx, topInsetPx) {
                                object : androidx.compose.ui.window.PopupPositionProvider {
                                    override fun calculatePosition(
                                        anchorBounds: androidx.compose.ui.unit.IntRect,
                                        windowSize: androidx.compose.ui.unit.IntSize,
                                        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                        popupContentSize: androidx.compose.ui.unit.IntSize,
                                    ): androidx.compose.ui.unit.IntOffset {
                                        val x = (anchorBounds.right - popupContentSize.width - rightInsetPx)
                                            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                        val y = (anchorBounds.top + topInsetPx).coerceAtLeast(0)
                                        return androidx.compose.ui.unit.IntOffset(x, y)
                                    }
                                }
                            },
                            onDismissRequest = {},
                            properties = androidx.compose.ui.window.PopupProperties(
                                focusable = false,
                                dismissOnBackPress = false,
                                dismissOnClickOutside = false,
                            ),
                        ) {
                            androidx.compose.material3.Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                // Mirrors iOS .ultraThinMaterial — solid-
                                // looking pill against the input bg.
                                // Without a hairline border the capsule
                                // washed out into the input card on the
                                // light theme, which is why it stopped
                                // reading as a pill.
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shadowElevation = 0.dp,
                                tonalElevation = 0.dp,
                                border = androidx.compose.foundation.BorderStroke(
                                    0.5.dp,
                                    ChatColors.thumbnailBorder,
                                ),
                                modifier = Modifier.clickable { showMoveSheet = true },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                                ) {
                                    // arrow.right.circle look-alike: an
                                    // outlined ring around a → glyph.
                                    Box(
                                        modifier = Modifier
                                            .size(15.dp)
                                            .border(
                                                1.dp,
                                                ChatColors.secondaryText,
                                                CircleShape,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier.size(10.dp),
                                            tint = ChatColors.secondaryText,
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "Move to…",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = ChatColors.secondaryText,
                                    )
                                }
                            }
                        }
                    }
                    // Attachment thumbnails inside the box (iOS: 64×64 squares)
                    // [T-android-paste-placeholder] One chip per folded paste,
                    // above the attachment row. Same 12dp gutter so the two
                    // rows share the composer's left edge.
                    if (pastedTexts.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(pastedTexts, key = { it.id }) { pasted ->
                                PastedTextChip(
                                    pasted = pasted,
                                    onRemove = {
                                        // Remove the marker from the composer
                                        // in the SAME edit that drops the
                                        // buffer entry, so the two can never
                                        // disagree. Anchored on the exact
                                        // literal, so neighbouring text and
                                        // other ids are untouched — deleting
                                        // #2 cannot disturb #20, because the
                                        // trailing ']' makes the match exact.
                                        val marker = pasted.placeholder
                                        val cur = inputFieldValue.text
                                        val at = cur.indexOf(marker)
                                        if (at >= 0) {
                                            val stripped =
                                                cur.substring(0, at) +
                                                    cur.substring(at + marker.length)
                                            inputFieldValue =
                                                androidx.compose.ui.text.input.TextFieldValue(
                                                    text = stripped,
                                                    selection =
                                                        androidx.compose.ui.text.TextRange(at),
                                                )
                                            viewModel.setInputText(stripped)
                                        }
                                        viewModel.removePastedText(pasted.id)
                                    },
                                )
                            }
                        }
                    }
                    if (attachments.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                // T185: 12dp horizontal so the row's left
                                // edge lines up with the +/slash button
                                // column and the typed text below.
                                .padding(horizontal = 12.dp),
                            // The chip itself now bakes in 8dp of trailing
                            // visual room for the remove badge that spills
                            // past the top-right; no extra spacedBy needed.
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            items(attachments, key = { it.id }) { attachment ->
                                // T-pwa-2: long-press menu only appears for
                                // .html / .htm attachments. The menu lives in
                                // a Box that anchors to the chip; the sheet
                                // itself is hosted at screen level (see
                                // webAppSheetTarget).
                                val isHtmlAttachment = attachment.fileName
                                    .substringAfterLast('.', "")
                                    .lowercase()
                                    .let { it == "html" || it == "htm" }
                                var webAppMenuExpanded by remember(attachment.id) { mutableStateOf(false) }
                                Box {
                                AttachmentChip(
                                    attachment = attachment,
                                    onRemove = { viewModel.removeAttachment(attachment.id) },
                                    onLongClick = if (isHtmlAttachment) {
                                        { webAppMenuExpanded = true }
                                    } else null,
                                    onClick = {
                                        // Mirror iOS InputAttachmentTile
                                        // (AIChatView.swift:3699) which
                                        // .sheet's an AttachmentPreviewView
                                        // routed by file type. Images go
                                        // through the in-app fullscreen
                                        // viewer; non-image files take the
                                        // in-app FilePreviewScreen when we
                                        // hold a host file path, falling
                                        // back to the system viewer for
                                        // foreign content:// URIs.
                                        if (attachment.isImage) {
                                            // Collect every image chip in
                                            // the composer row so the user
                                            // can swipe through them.
                                            val imageChips = attachments.filter { it.isImage }
                                            val startIdx = imageChips.indexOfFirst { it.id == attachment.id }
                                                .coerceAtLeast(0)
                                            previewImageGallery = imageChips.map { ic ->
                                                com.openminis.app.ui.components.ImageGalleryItem(
                                                    model = ic.uri,
                                                    caption = ic.fileName,
                                                )
                                            } to startIdx
                                        } else {
                                            // T162: shares funnel through
                                            // addAttachmentFromStagedShare,
                                            // which copies the bytes into
                                            // cacheDir/share_inbound/<uuid>-
                                            // <name> and returns a
                                            // Uri.fromFile() URI. Handing
                                            // that file:// URI directly to
                                            // Intent.ACTION_VIEW raises
                                            // FileUriExposedException on
                                            // API 24+ and crashed the app
                                            // on the user's first chip tap.
                                            // Route file:// chips into the
                                            // in-app FilePreviewScreen via
                                            // the host onPreviewAttachment
                                            // callback (same path the user-
                                            // bubble chip uses); leave
                                            // content:// chips on the
                                            // system viewer because we
                                            // don't have a host path for
                                            // those.
                                            val uri = attachment.uri
                                            val asFile = if (uri.scheme == "file") {
                                                uri.path?.let { java.io.File(it) }
                                            } else null
                                            if (asFile != null && asFile.exists()) {
                                                previewFileOrBrowser(
                                                    com.openminis.app.ui.sandbox.FileItem(
                                                        file = asFile,
                                                        name = attachment.fileName,
                                                        isDirectory = false,
                                                        isSymlink = false,
                                                        size = asFile.length(),
                                                        modifiedMs = asFile.lastModified(),
                                                    )
                                                )
                                            } else {
                                                val intent = android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                ).apply {
                                                    setDataAndType(uri, attachment.mimeType)
                                                    addFlags(
                                                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                                    )
                                                }
                                                try {
                                                    context.startActivity(intent)
                                                } catch (_: android.content.ActivityNotFoundException) {
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "No app available to open this attachment.",
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                        }
                                    },
                                )
                                if (isHtmlAttachment) {
                                    com.openminis.app.ui.components.MinisMenu(
                                        expanded = webAppMenuExpanded,
                                        onDismissRequest = { webAppMenuExpanded = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.webapp_add_to_home)) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.AppShortcut,
                                                    contentDescription = null,
                                                )
                                            },
                                            onClick = {
                                                webAppMenuExpanded = false
                                                webAppSheetTarget = attachment
                                            },
                                        )
                                    }
                                }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // While a voice session is active, the TextField is
                    // replaced by a live waveform + partial-transcription
                    // preview (matches iOS `inputFieldOrWaveform`). Recognized
                    // text is already delta-appended into `inputText` by the
                    // mic button's callback, so when recording ends the field
                    // shows the full recognized string automatically.
                    val recSttState by com.openminis.app.speech.SpeechRecognitionManager
                        .state.collectAsState()
                    val recIsRecording = recSttState == com.openminis.app.speech.RecognitionState.RECORDING ||
                        recSttState == com.openminis.app.speech.RecognitionState.STARTING ||
                        recSttState == com.openminis.app.speech.RecognitionState.FINISHING
                    // [T-android-voice-panel] Inline voice mode replaces the text
                    // field with the panel (mirrors iOS inputFieldOrWaveform →
                    // InlineVoiceInputView). The legacy in-composer waveform
                    // branch below only serves captures started OUTSIDE the
                    // panel (none today, kept as a safety net).
                    if (com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive) {
                        com.openminis.app.ui.chat.voice.InlineVoiceInputPanel(
                            providerRepository = providerRepository,
                            inputText = inputText,
                            onInputTextChange = { text ->
                                viewModel.setInputText(text)
                                viewModel.updateSlashMenuState(text)
                            },
                            ensureMicPermission = { ensureMicPermissionFlow() },
                            // [T-android-correction-context-wiring] Feed AI
                            // correction the live conversation context. Reads the
                            // FULL message list (not the windowed uiMessages) so
                            // older turns still contribute rare-term grounding;
                            // evaluated lazily at correction time.
                            conversationContextProvider = {
                                com.openminis.app.speech.correction.VoiceCorrection
                                    .buildConversationContext(context, viewModel.messages.value)
                            },
                        )
                    } else if (recIsRecording) {
                        val levels by com.openminis.app.speech.SpeechRecognitionManager
                            .audioLevels.collectAsState()
                        val partial by com.openminis.app.speech.SpeechRecognitionManager
                            .recognizedText.collectAsState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            AudioWaveformView(
                                levels = levels,
                                barColor = Color.Red.copy(alpha = 0.75f),
                                heightDp = 28,
                            )
                            if (partial.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = partial,
                                    fontSize = 14.sp,
                                    color = ChatColors.secondaryText,
                                    maxLines = 2,
                                )
                            }
                        }
                    } else
                    // Text field (iOS: placeholder "Message Minis", no border)
                    run {
                        val interactionSource = remember { MutableInteractionSource() }
                        // [T-android-composer-placeholder-rotation] Which entry
                        // of the placeholder pool is showing, and whether the
                        // composer has ever been focused in this session.
                        //
                        // rememberSaveable, not remember: a config change
                        // (rotation, dark-mode toggle, font-scale change)
                        // otherwise resets the index to the default AND clears
                        // the first-focus flag, so the next tap would re-run
                        // first-focus logic and the hint the user was reading
                        // would snap back.
                        var placeholderIndex by rememberSaveable {
                            mutableIntStateOf(ComposerPlaceholderRotation.DEFAULT_INDEX)
                        }
                        var composerHasFocusedBefore by rememberSaveable { mutableStateOf(false) }
                        // TalkBack pins the placeholder — see the picker's
                        // accessibility note. Read live rather than cached so
                        // enabling the screen reader mid-session takes effect.
                        val screenReaderEnabled = rememberScreenReaderEnabled()
                        val mergedTextStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.5.sp * chatInputFontScale,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        // [T-android-enter-to-send-broken] Live read of the
                        // "Return key sends" preference. Bound here (not
                        // captured at BasicTextField construction) so a
                        // toggle in Settings reflects on the next IME
                        // commit without recomposing the chat tree.
                        val sendOnEnter = com.openminis.app.ui.settings
                            .returnKeySendsMessage(context)
                        // Shared "Enter pressed → send" body used by BOTH
                        // the hardware-keyboard onKeyEvent path AND the
                        // soft-keyboard KeyboardActions.onSend below.
                        // Pre-fix only the onKeyEvent path existed and
                        // most soft IMEs (Gboard, Sogou, MIUI) never
                        // route an Enter through onKeyEvent under
                        // ImeAction.Default — they just inserted a '\n'
                        // and the preference appeared not to work. We
                        // now flip imeAction to Send when the toggle is
                        // on, so the IME shows the send icon AND fires
                        // onSend; this lambda is the single source of
                        // truth for what "press Enter to send" means.
                        val performEnterSend: () -> Boolean = handler@{
                            if (inputText.isBlank() && attachments.isEmpty()) return@handler false
                            // Intercept slash commands so "/compact" et al.
                            // run locally instead of being sent as a chat
                            // turn. Mirrors iOS performSend().
                            if (viewModel.tryExecuteInputAsSlashCommand(inputText)) {
                                viewModel.setInputText("")
                                releaseComposerAfterSend()
                                return@handler true
                            }
                            // T160: snapshot → clear state + IME →
                            // sendMessage. Same ordering as the send-
                            // button click; finishComposingText fires
                            // when focus drops so any IME composing
                            // buffer is committed/dropped before the
                            // empty inputText becomes visible.
                            val toSend = inputText
                            lastSendTimeMs = System.currentTimeMillis()
                            viewModel.setInputText("")
                            releaseComposerAfterSend()
                            viewModel.sendMessage(toSend)
                            noteSendForInputModePref()
                            scrollPolicy.pin()
                            coroutineScope.launch {
                                tracedScrollToItem("SEND-PATH(keyboard-imeAction)/initial", 0, 0)
                                kotlinx.coroutines.delay(100)
                                tracedScrollToItem("SEND-PATH(keyboard-imeAction)/settle", 0, 0)
                            }
                            true
                        }
                        // [T-composer-clipboard-image] Native image paste:
                        // Compose 1.9's canPaste is clip-ENTRY based, so the
                        // system paste button already appears for image-only
                        // clips. The wrapper intercepts the paste tap — image
                        // in clipboard → attach; otherwise normal text paste.
                        // Replaces the old always-visible paste chip.
                        val composerDefaultToolbar = androidx.compose.ui.platform.LocalTextToolbar.current
                        val imagePasteToolbar = remember(composerDefaultToolbar) {
                            com.openminis.app.ui.chat.ComposerImagePaste.toolbar(composerDefaultToolbar) {
                                com.openminis.app.ui.chat.ClipboardImagePaste.paste(context, viewModel)
                            }
                        }
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.ui.platform.LocalTextToolbar provides imagePasteToolbar,
                        ) {
                        BasicTextField(
                            value = inputFieldValue,
                            onValueChange = { tfv ->
                                // T217-2: drop IME residue commits in 300ms post-send window.
                                // finishComposingText (fired by clearFocus on send) makes
                                // voice/Pinyin IMEs replay their pending candidate through
                                // onValueChange after we cleared inputText.
                                val now = System.currentTimeMillis()
                                if (now - lastSendTimeMs < 300L && tfv.text.isNotEmpty()) {
                                    return@BasicTextField
                                }
                                // [T-android-voice-correction] Capability #3:
                                // learn from select-and-replace edits. When the
                                // PREVIOUS value had a non-empty selection and
                                // this change swapped that span for different
                                // text, the user deliberately replaced something
                                // they had already written — the same shape as
                                // fixing a transcript, so the recorder applies
                                // the identical phonetic admission test and
                                // discards anything that reads as a rewrite.
                                //
                                // Silent background capture: consent-gated,
                                // fire-and-forget, no UI. Deliberately NOT
                                // firing on ordinary typing, which is
                                // append-only and carries no correction signal.
                                captureSelectionReplacement(context, inputFieldValue, tfv)
                                // [T-android-enter-to-send-multiline] Root cause:
                                // the composer is a multi-line BasicTextField
                                // (maxLines=6 ⇒ EditorInfo carries
                                // TYPE_TEXT_FLAG_MULTI_LINE, confirmed inputType
                                // 0x28001 in dumpsys input_method). In multi-line
                                // mode soft IMEs (Gboard/LatinIME, Sogou, MIUI)
                                // render Enter as a newline and IGNORE
                                // IME_ACTION_SEND — so KeyboardActions.onSend
                                // never fires and the "Return key sends" pref
                                // looked inert. The IME commits the Enter as a
                                // plain '\n' through onValueChange (not through
                                // onKeyEvent / a KEYCODE_ENTER), so the only
                                // place to catch it for soft keyboards is here.
                                //
                                // Detect a single '\n' freshly inserted into the
                                // text (one Enter keypress) and convert it to a
                                // send. Guarded to a single added newline so a
                                // paste containing newlines is NOT swallowed —
                                // those increase the count by >1 and fall through
                                // to the normal multi-line edit. Hardware-keyboard
                                // Enter / Shift+Enter still go through onKeyEvent
                                // below (Shift+Enter inserts a newline there and
                                // never reaches the send path).
                                if (sendOnEnter && !showMentionMenu) {
                                    val oldText = inputFieldValue.text
                                    val newText = tfv.text
                                    val addedNewline = newText.length == oldText.length + 1 &&
                                        newText.count { it == '\n' } == oldText.count { it == '\n' } + 1
                                    if (addedNewline) {
                                        val caret = tfv.selection.end
                                        // The inserted char sits just before the
                                        // caret; confirm it is the newline so we
                                        // don't misfire on an unrelated 1-char edit
                                        // that happens to keep newline parity.
                                        if (caret in 1..newText.length &&
                                            newText[caret - 1] == '\n'
                                        ) {
                                            performEnterSend()
                                            return@BasicTextField
                                        }
                                    }
                                }
                                // [T-android-paste-placeholder] Fold a large
                                // single-shot insertion into a `[Pasted#N]`
                                // marker before anything else sees it.
                                //
                                // Detected by length jump rather than by
                                // intercepting paste: Compose's BasicTextField
                                // exposes no paste hook, and the alternative
                                // (swapping in an AndroidView/EditText to
                                // override onTextContextMenuItem) would put the
                                // @-mention and slash-command logic below onto a
                                // different text pipeline. A jump test costs
                                // nothing and covers every source — long-press
                                // Paste, hardware Ctrl+V, and IME bulk commit —
                                // because all three surface as one big value
                                // change.
                                //
                                // Placed BEFORE `inputFieldValue = tfv` and the
                                // mention/slash updates on purpose: those must
                                // see the SHORT text. Letting them run on the
                                // raw paste first would make the @-picker scan a
                                // 50 KB string and could pop the slash menu on a
                                // '/' buried in pasted content.
                                // [T-android-paste-oversize] The size split
                                // lives INSIDE the stash lambda rather than
                                // beside this call, because foldLongPasteIfNeeded
                                // is what locates the inserted run — branching
                                // outside would mean re-deriving that prefix/
                                // suffix diff and keeping two copies of it in
                                // step.
                                //
                                // Returning "" for the file case is what removes
                                // the pasted text from the field entirely: the
                                // fold substitutes the marker for the run, so an
                                // empty marker deletes it. That is the intent —
                                // the content is now a chip in the attachment
                                // row, and leaving a stray literal behind would
                                // send it twice.
                                //
                                // If the write fails the text is stashed as an
                                // ordinary placeholder instead, so an unwritable
                                // cache degrades to the old behaviour rather
                                // than losing what the user pasted.
                                val value = foldLongPasteIfNeeded(
                                    old = inputFieldValue,
                                    new = tfv,
                                    stash = { pasted ->
                                        if (pasted.length > PASTE_AS_FILE_THRESHOLD) {
                                            if (viewModel.stashPastedTextAsFile(pasted) != null) {
                                                ""
                                            } else {
                                                viewModel.stashPastedText(pasted)
                                            }
                                        } else {
                                            viewModel.stashPastedText(pasted)
                                        }
                                    },
                                )
                                inputFieldValue = value
                                if (inputText != value.text) {
                                    viewModel.setInputText(value.text)
                                    viewModel.updateSlashMenuState(value.text)
                                }
                                // Drive the @ mention picker on every keystroke
                                // and selection change — caret position alone
                                // can flip the active token's filter (e.g. user
                                // moves cursor without typing). VM filters out
                                // the slash-menu-priority case and any
                                // non-mention caret state.
                                viewModel.updateMentionMenuState(
                                    text = value.text,
                                    caret = value.selection.end,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 25.dp, max = if (WindowInsets.ime.getBottom(LocalDensity.current) > 0) 120.dp else 220.dp)
                                .focusRequester(inputFocusRequester)
                                .onFocusChanged {
                                    // [T-android-composer-placeholder-rotation]
                                    // Advance the placeholder on the focus-GAIN
                                    // edge only. Guarding on the previous value
                                    // matters: onFocusChanged also fires on
                                    // focus loss and on recompositions that
                                    // re-report the same state, and rotating on
                                    // those would swap the hint while the user
                                    // is typing or dismissing the keyboard.
                                    if (it.isFocused && !inputFocused) {
                                        placeholderIndex = ComposerPlaceholderRotation.nextIndex(
                                            current = placeholderIndex,
                                            hasFocusedBefore = composerHasFocusedBefore,
                                            sessionHasMessages = messages.isNotEmpty(),
                                            screenReaderOn = screenReaderEnabled,
                                            randomIndex = { bound -> kotlin.random.Random.nextInt(bound) },
                                        )
                                        composerHasFocusedBefore = true
                                    }
                                    inputFocused = it.isFocused
                                }
                                .onKeyEvent { event ->
                                    // T-at-filepicker-keyboard: while the @-mention
                                    // menu is open, hardware Up/Down navigates the
                                    // list and Return commits the highlighted entry.
                                    // Falls through to the normal Return-send path
                                    // when there are no mention candidates so the
                                    // user isn't stuck if the menu is empty.
                                    // [T-android-slash-tab-complete] Tab fills in
                                    // the highlighted slash command — the shell
                                    // convention, and the reason a user types
                                    // "/andr" then reaches for Tab rather than
                                    // the mouse.
                                    //
                                    // The slash menu has no selection cursor
                                    // (unlike the mention menu, which tracks one
                                    // for its arrow keys), so "highlighted" is
                                    // the first row — the same one the list is
                                    // already scrolled to and the one the user
                                    // is looking at.
                                    //
                                    // Claimed BEFORE the mention block and the
                                    // Enter handling below so Tab never falls
                                    // through to focus traversal, which would
                                    // move focus out of the composer and leave
                                    // the menu open behind it.
                                    if (showSlashMenu &&
                                        event.type == KeyEventType.KeyDown &&
                                        event.key == Key.Tab
                                    ) {
                                        val first = filteredSlashCommands.firstOrNull()
                                        if (first != null) {
                                            val newText = viewModel.executeSlashCommand(first, inputText)
                                            viewModel.setInputText(newText)
                                            // Caret to the end: executeSlashCommand
                                            // leaves "/<name> " for a Skill, and the
                                            // point of completing is to keep typing
                                            // arguments right after it.
                                            inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                                                text = newText,
                                                selection = androidx.compose.ui.text.TextRange(newText.length),
                                            )
                                            return@onKeyEvent true
                                        }
                                    }
                                    if (showMentionMenu && event.type == KeyEventType.KeyDown) {
                                        when (event.key) {
                                            Key.DirectionUp -> {
                                                viewModel.mentionMenuUp()
                                                return@onKeyEvent true
                                            }
                                            Key.DirectionDown -> {
                                                viewModel.mentionMenuDown()
                                                return@onKeyEvent true
                                            }
                                            // [T-android-slash-tab-complete] Tab
                                            // completes here too, so the two
                                            // menus answer the same key the same
                                            // way. This one HAS a selection
                                            // cursor, so Tab takes the selected
                                            // row rather than the first.
                                            Key.Enter, Key.Tab -> {
                                                val result = viewModel.executeSelectedMention(
                                                    currentText = inputFieldValue.text,
                                                    currentCaret = inputFieldValue.selection.end,
                                                )
                                                if (result != null) {
                                                    val (newText, newCaret) = result
                                                    viewModel.setInputText(newText)
                                                    inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                                                        text = newText,
                                                        selection = androidx.compose.ui.text.TextRange(newCaret),
                                                    )
                                                    return@onKeyEvent true
                                                }
                                                // Menu open but no candidates → fall
                                                // through to Return-send / newline.
                                                //
                                                // [T-android-slash-tab-complete]
                                                // ...but only for Enter. Tab must
                                                // still be swallowed: falling
                                                // through would hand it to the
                                                // Enter handling below and SEND
                                                // the message, which is not
                                                // remotely what Tab means.
                                                if (event.key == Key.Tab) return@onKeyEvent true
                                            }
                                            Key.Escape -> {
                                                viewModel.dismissMentionMenu()
                                                return@onKeyEvent true
                                            }
                                            else -> Unit
                                        }
                                    }
                                    // Return-key behavior is user-configurable
                                    // (Appearance → Return Key, default Newline =
                                    // iOS shipping default). Shift+Enter always
                                    // inserts a newline regardless of the setting,
                                    // mirroring iOS hardware-keyboard semantics.
                                    // [T-android-enter-to-send-broken] Hardware-
                                    // keyboard path. Soft IME route goes through
                                    // KeyboardActions.onSend below.
                                    if (event.type == KeyEventType.KeyDown &&
                                        event.key == Key.Enter &&
                                        !event.isShiftPressed &&
                                        sendOnEnter
                                    ) {
                                        performEnterSend()
                                    } else false
                                },
                            textStyle = mergedTextStyle,
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 6,
                            // [T-android-enter-to-send-broken] When the
                            // user has Return-Key=Send turned on, ask the
                            // IME for the Send action so it (a) shows the
                            // send glyph instead of "Enter" and (b)
                            // actually invokes KeyboardActions.onSend
                            // instead of silently inserting '\n'. With
                            // Default, Gboard / Sogou / MIUI etc. never
                            // routed Enter through onKeyEvent so the
                            // preference appeared inert.
                            keyboardOptions = KeyboardOptions(
                                imeAction = if (sendOnEnter) ImeAction.Send else ImeAction.Default,
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = { performEnterSend() },
                            ),
                            interactionSource = interactionSource,
                            decorationBox = { innerTextField ->
                                OutlinedTextFieldDefaults.DecorationBox(
                                    value = inputText,
                                    innerTextField = innerTextField,
                                    enabled = true,
                                    singleLine = false,
                                    visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                                    interactionSource = interactionSource,
                                    placeholder = {
                                        // [T-android-placeholder-single-line, port iOS 19e1d61f]
                                        // Single-line composer placeholder. The
                                        // parenthetical "(@ to mention files)"
                                        // is folded into the same line as
                                        // "Message <SoulName>" at the same font
                                        // size and color — iOS collapsed the
                                        // two-line variant (#421/#425/#426) into
                                        // a single hint because users read the
                                        // smaller hint row as a separate UI
                                        // element rather than placeholder text.
                                        // SoulStore.cachedMetadata stays the
                                        // source for the Soul-customized name
                                        // so renames in Soul Settings reflect
                                        // here live.
                                        val soulName by com.openminis.app.agent.SoulStore
                                            .cachedMetadata.collectAsState()
                                        val groupChatOn by viewModel.groupChatEnabled.collectAsState()
                                        if (groupChatOn) {
                                            Text(
                                                stringResource(R.string.chat_input_placeholder_group),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                                fontSize = 16.5.sp * chatInputFontScale,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        } else {
                                        // [T-android-composer-placeholder-rotation]
                                        // Crossfade between hints. Reduce-motion
                                        // (animator scale 0) skips the fade and
                                        // swaps instantly — matching the iOS
                                        // Reduce Motion branch.
                                        val fadeMs = if (animationsDisabled(context)) 0 else 220
                                        Crossfade(
                                            targetState = placeholderIndex,
                                            animationSpec = tween(durationMillis = fadeMs),
                                            label = "composerPlaceholder",
                                        ) { idx ->
                                            Text(
                                                composerPlaceholderText(idx, soulName.name),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                                fontSize = 16.5.sp * chatInputFontScale,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                    ),
                                    // T7: vertical 10dp → 7dp (≈ −15%) to slim the
                                    // chat composer. Settings TextFields keep
                                    // Material3 default padding — those are
                                    // 1-shot config inputs, not the daily-
                                    // friction surface the user wants tightened.
                                    // T185: 12dp horizontal lines the
                                    // typed text up with the +/slash and
                                    // mic/send icon-button row below
                                    // (Modifier.padding(horizontal = 12.dp)
                                    // there) and the attachment chip row
                                    // (also 12dp). 16dp left an unaligned
                                    // jog where the text started further
                                    // right than every other composer
                                    // element.
                                    contentPadding = PaddingValues(
                                        horizontal = 12.dp,
                                        vertical = 7.dp,
                                    ),
                                )
                            },
                        )
                        }
                    }

                    // Button row below text field (iOS layout: + / ... mic send)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // T185: 12dp horizontal lines the +/slash and
                            // mic/send icon-button column up with the
                            // attachment row + textfield + Move-to popup.
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Left: + button (iOS: 34×34 circle, secondary bg)
                        Box {
                            InputCircleButton(
                                onClick = { showAttachMenu = true },
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = stringResource(R.string.cd_attach),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            MinisMenu(
                                expanded = showAttachMenu,
                                onDismissRequest = { showAttachMenu = false },
                            ) {
                                // iOS parity: Take Photo / Choose Photos & Videos / Add File
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_take_photo)) },
                                    leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        val granted = ContextCompat.checkSelfPermission(
                                            context,
                                            android.Manifest.permission.CAMERA,
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            launchCamera()
                                        } else {
                                            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_choose_photos_videos)) },
                                    leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        mediaPickerLauncher.launch(
                                            androidx.activity.result.PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                                            ),
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_add_file)) },
                                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        // OpenMultipleDocuments takes a mime-
                                        // type array; "*/*" stays the wildcard.
                                        filePickerLauncher.launch(arrayOf("*/*"))
                                    },
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        if (showExpandedEditor) {
                            LongTextEditorDialog(
                                text = inputText,
                                codeMode = false,
                                onTextChange = { next ->
                                    viewModel.setInputText(next)
                                    inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                                        text = next,
                                        selection = androidx.compose.ui.text.TextRange(next.length),
                                    )
                                },
                                onDismiss = { showExpandedEditor = false },
                            )
                        }
                        InputCircleButton(onClick = { showExpandedEditor = true }) {
                            Icon(
                                Icons.Filled.OpenInFull,
                                contentDescription = stringResource(R.string.composer_expand_editor),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Left: "/" slash command button (iOS: italic /, bold)
                        InputCircleButton(onClick = {
                            if (viewModel.showSlashMenu.value) {
                                viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                            } else {
                                viewModel.setInputText(viewModel.showSlashMenuOverInput(inputText))
                            }
                        }) {
                            Text(
                                "/",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // T187: Exit Edit Mode pill, only while editingMessageId
                        // is non-null. Tap clears the edit flag + composer text
                        // without truncating history. iOS parity:
                        // AIChatView.swift L1586 editExitButton.
                        val editingId by viewModel.editingMessageId.collectAsState()
                        if (editingId != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = ChatColors.inputBg,
                                modifier = Modifier.clickable {
                                    viewModel.cancelEdit()
                                    viewModel.setInputText("")
                                },
                            ) {
                                Text(
                                    text = stringResource(R.string.chat_edit_exit_button),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ChatColors.secondaryText,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Right: Mic button — only renders when a speech engine
                        // is actually available on this device (handles the
                        // AOSP / HarmonyOS / GMS-free case).
                        val sttAvailable by com.openminis.app.speech.SpeechRecognitionManager
                            .isAvailable.collectAsState()
                        val sttState by com.openminis.app.speech.SpeechRecognitionManager
                            .state.collectAsState()
                        val sttLocale by com.openminis.app.speech.SpeechRecognitionManager
                            .locale.collectAsState()
                        var showLangSheet by remember { mutableStateOf(false) }
                        // While recording, a tappable 2-letter language pill
                        // appears to the left of the mic button. Outside a
                        // session the mic button's own badge stays hidden and
                        // the pill is not rendered — matches iOS.
                        if (sttAvailable && !com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive &&
                            (sttState == com.openminis.app.speech.RecognitionState.RECORDING ||
                                sttState == com.openminis.app.speech.RecognitionState.STARTING)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(ChatColors.inputIconBg, CircleShape)
                                    .border(0.5.dp, ChatColors.inputIconBorder, CircleShape)
                                    .clip(CircleShape)
                                    .clickable { showLangSheet = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = sttLocale.language.uppercase().take(2),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ChatColors.primaryText,
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        if (showLangSheet) {
                            SpeechLanguagePickerSheet(onDismiss = { showLangSheet = false })
                        }
                        // Extracted so the app-icon "voice chat" quick action
                        // (DeepLinkCoordinator.ChatAction.START_VOICE) can
                        // fire the same flow on first compose without
                        // duplicating the 3-stage permission dance.
                        val triggerVoiceInput: () -> Unit = lambda@{
                            // [T-android-voice-panel] The mic button now toggles
                            // the INLINE VOICE PANEL (mirrors iOS MicButton →
                            // voiceInputActive). Capture start/stop lives inside
                            // the panel; this button only enters/exits the mode.
                            if (com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive) {
                                // Exit voice → keyboard. Keep the transcript: the
                                // composer mirrors it (iOS keyboard-text-carry).
                                if (com.openminis.app.speech.SpeechRecognitionManager.state.value !=
                                    com.openminis.app.speech.RecognitionState.IDLE
                                ) {
                                    com.openminis.app.speech.SpeechRecognitionManager.stopRecording()
                                }
                                com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive = false
                                ComposerInputModePrefs.save(context, voice = false)
                                voiceUsedSinceClear = false
                            } else {
                                // [T-android-voice-entry-always-available]
                                // Entering voice mode is an explicit retry — give
                                // every engine a fresh start so a past transient
                                // failure (mic was busy, permission since granted,
                                // provider since configured) doesn't keep the
                                // feature dead for the rest of the process.
                                com.openminis.app.speech.SpeechRecognitionManager
                                    .clearDegradationAndRefresh()
                                voiceUsedSinceClear = true
                                com.openminis.app.ui.chat.voice.VoiceModePrefs.enteredFromText = true
                                com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive = true
                            }
                        }

                        // App-icon quick action: when the user launched via
                        // `minis://action/voice_chat`, auto-fire the mic on
                        // first compose. Consumed exactly once so re-entering
                        // the chat later does NOT re-trigger.
                        //
                        // [T-android-voice-entry-always-available] Gated on the
                        // STRUCTURAL check, not the sttAvailable runtime probe.
                        // The probe is false on ROMs without a system speech
                        // service (ColorOS et al.), which made this shortcut a
                        // silent no-op there — while the mic button itself had
                        // already moved to hasMicrophoneHardware. Entering the
                        // panel without a live engine is fine: the panel owns
                        // the "no engine → here's how to configure one" story.
                        LaunchedEffect(Unit) {
                            if (!com.openminis.app.speech.SpeechRecognitionManager
                                    .hasMicrophoneHardware
                            ) {
                                return@LaunchedEffect
                            }
                            val pending = com.openminis.app.deeplink.DeepLinkCoordinator
                                .pendingChatAction.value
                            if (pending == com.openminis.app.deeplink.DeepLinkCoordinator
                                    .ChatAction.START_VOICE
                            ) {
                                com.openminis.app.deeplink.DeepLinkCoordinator
                                    .consumePendingChatAction()
                                triggerVoiceInput()
                            }
                        }

                        // [T-android-remove-auto-enter-voice] Auto-enter-voice on
                        // cold launch / new chat removed (was ec95451a). The
                        // composer now always starts in text mode; voice is only
                        // entered when the user taps the mic button below.
                        // [T-android-voice-panel] "Read replies" TTS toggle —
                        // shown only while the voice panel is active (mirrors
                        // iOS readAloudToolbarToggle, 2-state on Android).
                        // [T-android-edit-readreplies-hide] Hidden while message
                        // edit mode is active: the Exit-Edit pill lives in the
                        // same bottom row, and both capsules plus their spacers
                        // overflow the constrained width and render overlapped
                        // (iOS af9f3d3e parity).
                        if (com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive && editingId == null) {
                            // [T-android-tts-capsule] Source of truth is the
                            // GLOBAL VoiceOutputState (same "readReplies" pref
                            // key as before), shared with the floating
                            // speech-player capsule — so the pill reflects the
                            // capsule's mute/close actions too. Mirrors iOS
                            // readAloudToolbarToggle's three states:
                            //   active → muted → off → active …
                            LaunchedEffect(Unit) {
                                com.openminis.app.speech.VoiceOutputState.init(context)
                            }
                            val ttsEnabled by com.openminis.app.speech.VoiceOutputState
                                .isEnabled.collectAsState()
                            val ttsMuted by com.openminis.app.speech.VoiceOutputState
                                .isMuted.collectAsState()
                            val readReplies = ttsEnabled && !ttsMuted
                            // [T-android-provider-tts-readaloud] Routes each
                            // utterance through the resolved Voice Output
                            // selection (provider TTS, system engine as
                            // fallback) instead of always using the on-device
                            // engine, and sanitizes Markdown before speaking.
                            val replyTts = remember {
                                com.openminis.app.speech.ReadAloudPlayer(context)
                            }
                            // The previous bare TextToSpeechManager() was never
                            // shut down, leaking an engine binding on every
                            // entry into the voice panel.
                            DisposableEffect(replyTts) {
                                onDispose { replyTts.shutdown() }
                            }
                            // [T-android-read-replies-pill-metrics] Sizing mirrors
                            // iOS readAloudToolbarToggle: 10/6 padding around a
                            // 5pt-spaced icon+label, on a capsule that HUGS its
                            // content (iOS pins it with .fixedSize()).
                            //
                            // Two Compose-specific corrections are needed to land
                            // on the same result:
                            //  • wrapContentWidth() + centered arrangement — this
                            //    pill sits between weight(1f) spacers, so without
                            //    hugging it absorbs slack and the un-arranged Row
                            //    packed icon+text against the start edge, which is
                            //    what read as "not horizontally centered".
                            //  • the label's line height is pinned to the font size
                            //    and its font padding disabled. Compose Text
                            //    otherwise reserves the font's full ascent/descent
                            //    leading on top of the 6dp padding, making the pill
                            //    visibly taller than iOS's for the same numbers.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .wrapContentWidth()
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (ttsEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        else ChatColors.secondaryText.copy(alpha = 0.10f),
                                    )
                                    .clickable {
                                        // iOS tap-cycle (readAloudToolbarToggle):
                                        // active → mute (capsule stays visible);
                                        // muted → fully off (capsule hides);
                                        // off → on, un-muted.
                                        val s = com.openminis.app.speech.VoiceOutputState
                                        when {
                                            ttsEnabled && !ttsMuted -> s.setMuted(true)
                                            ttsEnabled && ttsMuted -> s.setEnabled(false)
                                            else -> { s.setMuted(false); s.setEnabled(true) }
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Icon(
                                    if (readReplies) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (ttsEnabled) MaterialTheme.colorScheme.primary else ChatColors.secondaryText,
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    stringResource(R.string.voice_panel_read_replies),
                                    // Metrics live IN the style, not as separate
                                    // Text parameters: passing `style =` replaces
                                    // the merged style, so a lineHeight given
                                    // alongside it can be lost.
                                    //
                                    // includeFontPadding=false drops the font's
                                    // ascent/descent slack that Compose otherwise
                                    // adds on top of the 6dp padding — that slack
                                    // was what made the pill overshoot its
                                    // siblings. lineHeight is pinned to 1.25× the
                                    // font size (a normal text leading) and
                                    // centered, so the label occupies a
                                    // predictable box and the 6dp padding reads
                                    // evenly above and below.
                                    style = LocalTextStyle.current.copy(
                                        fontSize = 13.sp,
                                        lineHeight = 16.25.sp,
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeightStyle = LineHeightStyle(
                                            alignment = LineHeightStyle.Alignment.Center,
                                            trim = LineHeightStyle.Trim.None,
                                        ),
                                    ),
                                    color = if (ttsEnabled) MaterialTheme.colorScheme.primary else ChatColors.secondaryText,
                                )
                            }
                            // [T-android-streaming-readaloud] Speak the reply AS
                            // IT STREAMS. Previously this waited for isStreaming
                            // to flip false and then spoke the whole finished
                            // message, so the user heard nothing until
                            // generation completed — while the sentence-splitting
                            // machinery built for exactly this sat uncalled.
                            //
                            // Now each new chunk of the in-flight assistant
                            // message is fed to the player, which emits complete
                            // sentences immediately and flushes the tail at
                            // stream end (mirrors iOS feedDynamicTTS).
                            //
                            // `spokenUpTo` tracks how much of the current
                            // message has been handed over, so a recomposition
                            // mid-stream doesn't re-speak the prefix. It resets
                            // whenever the target message identity changes.
                            val lastAssistant = messages.lastOrNull { it.role == "assistant" }
                            val lastAssistantId = lastAssistant?.id
                            var spokenUpTo by remember(lastAssistantId) { mutableStateOf(0) }
                            // [T-android-readreplies-sidechannel] Feed the TTS
                            // from the STREAMING SIDE-CHANNEL, not the messages
                            // list. The previous effect keyed on
                            // `lastAssistant?.content` — but under
                            // T-streaming-side-channel the canonical list stays
                            // STATIC during a turn (per-token text rides
                            // streamingById; messages is only rewritten at turn
                            // end). So the effect fired exactly twice per turn:
                            //  1. Turn start (empty placeholder): fell through
                            //     the guard, marked lastSpokenAssistantKey, had
                            //     no text to feed — spokenUpTo stayed 0.
                            //  2. Turn end (final content lands): spokenUpTo was
                            //     still 0, and the key it now compared against
                            //     was the one IT marked in step 1 —
                            //     alreadySeen=true, whole message suppressed.
                            // Net effect: TTS engines bound and initialized on
                            // every panel entry and speak() was never called
                            // once — minis-2026-08-16.log has 5 "suppressed"
                            // lines, 0 "feeding" lines, which is exactly the
                            // reported "朗读回复开了但没有任何声音". The
                            // self-poisoning also explains the paradoxical
                            // `alreadySeen=true streaming=true` entries.
                            //
                            // Keys are (id, toggle) ONLY — the effect survives
                            // the whole turn and collects live deltas inside,
                            // so the history guard runs once per message
                            // identity and can no longer poison itself.
                            LaunchedEffect(lastAssistantId, readReplies) {
                                if (!readReplies || lastAssistantId == null) return@LaunchedEffect
                                val key = lastAssistantId.hashCode()
                                val alreadySeen = com.openminis.app.ui.chat.voice.VoiceModePrefs
                                    .lastSpokenAssistantKey == key
                                val liveAtEntry =
                                    viewModel.streamingById.value.containsKey(lastAssistantId)
                                // History on entry must not be read aloud: only
                                // a message that is live right now (or mid-turn
                                // awaiting its first token) is followed.
                                if (alreadySeen || (!viewModel.isStreaming.value && !liveAtEntry)) {
                                    android.util.Log.i(
                                        "ReadReplies",
                                        "suppressed: alreadySeen=$alreadySeen " +
                                            "streamingNow=${viewModel.isStreaming.value} " +
                                            "(history is never spoken)",
                                    )
                                    com.openminis.app.ui.chat.voice.VoiceModePrefs
                                        .lastSpokenAssistantKey = key
                                    return@LaunchedEffect
                                }
                                com.openminis.app.ui.chat.voice.VoiceModePrefs
                                    .lastSpokenAssistantKey = key
                                // [T-android-tts-scope-align] New reply → stop
                                // the PREVIOUS reply's still-playing speech and
                                // drop its queue, exactly once per followed
                                // message. iOS does this on the first text delta
                                // of a turn (hasClearedTTSForCurrentTurn +
                                // stopSpeechForThisSession); without it the old
                                // reply keeps talking and the new one queues
                                // BEHIND it, minutes late on long replies.
                                replyTts.stop()
                                // [T-android-tts-scope-align] Tool-boundary
                                // flush, from iOS's toolCallStart handler: text
                                // that streamed just before a tool call and
                                // never met a terminator must speak BEFORE the
                                // tool runs, not sit buffered until stream end.
                                var lastToolCount = 0
                                kotlinx.coroutines.flow.combine(
                                    viewModel.streamingById,
                                    viewModel.isStreaming,
                                ) { stream, streamingNow ->
                                    Triple(
                                        stream[lastAssistantId]?.content,
                                        streamingNow,
                                        stream[lastAssistantId]?.toolBlocks ?: emptyList(),
                                    )
                                }.collect { (live, streamingNow, toolBlocks) ->
                                    val toolCount = toolBlocks.size
                                    if (toolCount > lastToolCount) {
                                        // Flush first so the half-sentence that
                                        // preceded the tool call is spoken
                                        // BEFORE the announcement, not after it.
                                        replyTts.flush()
                                        // [T-android-tts-tool-announce] Announce
                                        // each newly-started tool, mirroring iOS
                                        // (makeToolSpeech + speakQueued). Without
                                        // this a listener hears the narration stop
                                        // dead for however long the tool runs,
                                        // with no cue as to why — the screen shows
                                        // a pill, but the whole point of read-aloud
                                        // is not having to look.
                                        //
                                        // Queued, never speak(): that would stop
                                        // playback and cut off the sentence just
                                        // flushed above.
                                        for (i in lastToolCount until toolCount) {
                                            val b = toolBlocks.getOrNull(i) ?: continue
                                            replyTts.speakQueued(
                                                com.openminis.app.speech.ToolSpeech.announcement(
                                                    name = b.toolName,
                                                    argsJson = b.toolArgs,
                                                    title = b.toolTitle.takeIf { it.isNotBlank() },
                                                )
                                            )
                                        }
                                        lastToolCount = toolCount
                                    }
                                    // Turn end drains the side-channel AFTER
                                    // publishing the final list — fall back to
                                    // the canonical message so the tail past the
                                    // last delta still gets spoken.
                                    val text = live
                                        ?: viewModel.messages.value
                                            .lastOrNull { it.id == lastAssistantId }?.content
                                        ?: return@collect
                                    if (text.length > spokenUpTo) {
                                        // [T-android-tts-diag] Kept: a field log
                                        // must show WHY nothing spoke (or that
                                        // feeding did happen and the fault is
                                        // further down, in the player/engine).
                                        android.util.Log.i(
                                            "ReadReplies",
                                            "feeding tts +${text.length - spokenUpTo} chars " +
                                                "(total=${text.length}) live=${live != null} " +
                                                "streaming=$streamingNow",
                                        )
                                        replyTts.appendText(text.substring(spokenUpTo))
                                        spokenUpTo = text.length
                                    }
                                    // Stream over and side-channel drained —
                                    // flush the trailing fragment that never got
                                    // a sentence terminator.
                                    if (!streamingNow && live == null) replyTts.flush()
                                }
                            }
                            // [T-android-read-replies-pill-metrics] Balancing
                            // spacer. There is a weight(1f) spacer BEFORE the
                            // pill but the trailing side only had a fixed 8dp,
                            // so all the row's slack collected on the left and
                            // pushed the pill right of the bar's centre (measured
                            // +59px on a 1080px screen). Matching weights on both
                            // sides centre it between the leading (+, /) and
                            // trailing (keyboard, mic/send) button groups.
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        // [T-android-voice-entry-always-available] The voice /
                        // keyboard toggle is ALWAYS shown. It used to be gated on
                        // `sttAvailable`, a runtime probe — so when the active
                        // engine degraded mid-session the button disappeared while
                        // `isVoiceActive` stayed true, leaving the user inside the
                        // voice panel with no way back to the keyboard (the toggle
                        // IS this button). Gating an escape hatch on the health of
                        // the thing you're escaping from is the bug.
                        //
                        // Existence now depends only on a structural fact —
                        // microphone hardware. "No speech service", "engine
                        // degraded" and "no ASR provider configured" are all
                        // RECOVERABLE states, explained inside the panel with a
                        // link to the relevant settings rather than by silently
                        // removing the control.
                        // [T-composer-mic-toggle] Hidden by default (Appearance
                        // → Chat Interface → "Show mic button"). Voice mode is
                        // the escape hatch: while the voice panel is active the
                        // button ALWAYS renders — it is the only way back to the
                        // keyboard, and gating an exit on a preference would
                        // strand the user in voice mode.
                        val micVisible = com.openminis.app.speech.SpeechRecognitionManager.hasMicrophoneHardware &&
                            (showMicButton || com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive)
                        if (micVisible) {
                            MicButton(
                                isRecording = !com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive &&
                                    (sttState == com.openminis.app.speech.RecognitionState.RECORDING ||
                                        sttState == com.openminis.app.speech.RecognitionState.STARTING),
                                localeBadge = null,
                                onClick = { triggerVoiceInput() },
                                onLongClick = { showLangSheet = true },
                                isVoiceActive = com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive,
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Right: 3-state Send / Enqueue / Stop button (mirrors iOS sendButton).
                        //   • streaming + hasText  → SEND (routes through viewModel.sendMessage,
                        //     which dispatches to enqueuePrompt since _isStreaming is true).
                        //     Visual feedback for the queued prompt comes from the dashed
                        //     bubble that ChatViewModel.enqueuePrompt appends to the message
                        //     list — no extra button badge needed (matches iOS).
                        //   • streaming + !hasText → STOP (cancel current run).
                        //   • !streaming           → SEND (full color when hasText, dimmed
                        //     when empty; same as before).
                        // T180: an attachments-only send (no caption) is a
                        // valid message — mirrors iOS where !attachments.isEmpty
                        // satisfies the composer's send guard. Without this an
                        // image-only "look at this" send is impossible.
                        val hasText = inputText.isNotBlank()
                        val hasContent = hasText || attachments.isNotEmpty()
                        val showStop = isStreaming && !hasContent
                        if (showStop) {
                            // [T-composer-running-ring] Constant 46dp container
                            // with an indeterminate ring while a run is in
                            // flight: the composer row height never changes
                            // between idle and streaming (the ring appears
                            // around the 38dp button instead of resizing it),
                            // so the message list does not jump.
                            Box(
                                modifier = Modifier.size(46.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(46.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFFFF3B30),
                                )
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(Color(0xFFFF3B30), CircleShape)
                                        .clip(CircleShape)
                                        .clickable { viewModel.cancelStream() },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = stringResource(R.string.cd_stop),
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        } else {
                            // Streaming with content → Send-into-queue; Idle with content → Send.
                            // Idle without text or attachments → disabled.
                            val canActivate = hasContent
                            // [T-composer-running-ring] Same constant 46dp
                            // container as the Stop branch so the composer row
                            // height is identical in every state; the ring only
                            // renders while a run is in flight (send-into-queue
                            // mode), and the 38dp button stays fully tappable.
                            Box(
                                modifier = Modifier.size(46.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isStreaming) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(46.dp),
                                        strokeWidth = 2.dp,
                                        color = ChatColors.sendButton,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(
                                            if (canActivate) ChatColors.sendButton
                                            else ChatColors.sendButtonDisabled,
                                            CircleShape,
                                        )
                                        .clip(CircleShape)
                                        .clickable(enabled = canActivate) {
                                            // T-drag-send-queue: route through the
                                            // shared send-or-enqueue handler. Same
                                            // semantics as before: slash short-
                                            // circuit, snapshot text, clear input
                                            // + focus, then sendMessage (which
                                            // routes to enqueuePrompt when
                                            // _isStreaming is true), then re-pin
                                            // the list to index 0 with a 100ms
                                            // re-pin to catch the late-mounting
                                            // "thinking" indicator.
                                            performSendOrEnqueue(inputText)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.ArrowUpward,
                                        contentDescription = stringResource(R.string.cd_send),
                                        tint = if (canActivate) ChatColors.background
                                        else ChatColors.primaryText.copy(alpha = 0.5f),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
                // --- Swipe-to-send floating hint (extracted helper) ---
                SwipeToSendHint(
                    progress = sendSwipeProgress,
                    armFraction = swipeArmFraction,
                    location = sendSwipeLocation,
                    hoverAbovePx = swipeHapticOffsetPx,
                    arrowHalfPx = swipeArrowHalfPx,
                    // While streaming, sendMessage() routes the prompt
                    // through enqueuePrompt() instead — surface that in
                    // the hint so the user knows the gesture still works
                    // mid-stream (mirrors the send-button's send/enqueue
                    // toggle, since on Android there's no separate visual
                    // state for the queued case).
                    isEnqueue = isStreaming,
                )
            } // end swipe-to-send Box wrapping the composer Column

            if (showMoveSheet) {
                MoveToSessionSheet(
                    currentSessionId = sessionId,
                    chatRepository = chatRepository,
                    onDismiss = { showMoveSheet = false },
                    onSelect = { targetId ->
                        ChatViewModelStore.stashPendingTransfer(
                            ChatViewModelStore.PendingTransfer(
                                inputText = inputText,
                                attachments = viewModel.attachments.value,
                                // [T-android-moveto-stash-binding] Bind the stash to
                                // the chosen target so no other session can drain it.
                                targetId = targetId,
                            ),
                        )
                        viewModel.setInputText("")
                        viewModel.clearAttachments()
                        viewModel.clearShareInjectedFlag()
                        showMoveSheet = false
                        onMoveToSession(targetId)
                    },
                )
            }

            // Pre-send context gate (iOS "Context Near Capacity" alert).
            // Raised when the compact threshold is crossed and auto-compact is
            // OFF; with it on the ViewModel compacts silently and never gets
            // here. Three actions, matching iOS:
            //   Send Anyway                  — skip compaction entirely
            //   Compact & Send               — compact this once, pref untouched
            //   Compact & Enable Auto-Compact— compact AND opt in, so the
            //                                  threshold stops prompting from
            //                                  now on (iOS T-chat-auto-compact-opt-in)
            val showCompactBeforeSend by viewModel.showCompactBeforeSendPrompt.collectAsState()
            if (showCompactBeforeSend) {
                MinisAlertDialog(
                    // Back-gesture / scrim dismissal must NOT silently drop the
                    // user's text — cancelCompactBeforeSend puts it back in the
                    // composer.
                    onDismissRequest = { viewModel.cancelCompactBeforeSend() },
                    title = stringResource(R.string.context_near_capacity_title),
                    text = stringResource(R.string.context_near_capacity_message),
                    confirmText = stringResource(R.string.context_compact_and_send),
                    onConfirm = { viewModel.compactAndSendPending() },
                    dismissText = stringResource(R.string.context_send_anyway),
                    onDismiss = { viewModel.sendPendingWithoutCompacting() },
                    neutralText = stringResource(R.string.context_compact_and_enable_auto),
                    onNeutral = {
                        viewModel.compactAndSendPending(alsoEnableAutoCompact = true)
                    },
                )
            }

            // [T-android-foreground-approval] In-app approval gate for sensitive
            // tools (file_write / file_edit). ApprovalGate.requestApproval blocks
            // the tool coroutine in waitFor(id); while the user is IN the chat we
            // surface the request here instead of relying on the notification-bar
            // action (which remains the background fallback). Resolving from here
            // wakes the waiter and cancels the matching notification via the
            // ViewModel helpers. When several are queued the oldest is shown first;
            // the next appears as soon as this one resolves.
            val pendingApprovals by viewModel.pendingApprovals.collectAsState()
            pendingApprovals.values.firstOrNull()?.let { approval ->
                MinisAlertDialog(
                    // Back-gesture / scrim dismissal is treated as a deny — the
                    // safe default, and it still wakes the blocked coroutine.
                    onDismissRequest = { viewModel.denyPendingTool(approval.id) },
                    title = "需要审批: ${approval.toolName}",
                    text = approval.preview.ifBlank { "Agent 请求执行敏感操作" },
                    confirmText = "同意",
                    onConfirm = { viewModel.approvePendingTool(approval.id) },
                    dismissText = "拒绝",
                    onDismiss = { viewModel.denyPendingTool(approval.id) },
                    // [T-session-allow-all] Third action: approve this request
                    // and auto-approve every remaining request this session.
                    neutralText = "本次会话全部允许",
                    onNeutral = { viewModel.approveAllForSession(approval.id) },
                )
            }

            // T137: Clear Chat confirmation. Wipes messages + agent history +
            // compact markers; the session row, workspace files, attachments,
            // and offload payloads are intentionally preserved (iOS parity).
            if (showCardShareDialog) {
                com.openminis.app.share.ConversationCardShareDialog(
                    onDismiss = { showCardShareDialog = false },
                    onShare = { options ->
                        showCardShareDialog = false
                        viewModel.shareConversationCard(options)
                    },
                )
            }
            if (showClearChatDialog) {
                MinisAlertDialog(
                    onDismissRequest = { showClearChatDialog = false },
                    title = stringResource(R.string.chat_menu_clear_chat),
                    text = stringResource(R.string.chat_clear_dialog_body),
                    confirmText = stringResource(R.string.chat_clear_dialog_confirm),
                    isDestructive = true,
                    onConfirm = {
                        viewModel.clearChat()
                        viewModel.setInputText("")
                        showClearChatDialog = false
                    },
                )
            }
            // [T-android-delete-from-here] Confirm before truncating. The cut
            // removes the tapped message AND everything after it with no undo,
            // so the body states how many messages that actually is — "delete
            // from here" alone doesn't convey whether it's one message or
            // forty. Counting from the live list keeps it accurate even if the
            // conversation grew while the menu was open.
            deleteFromHereTargetId?.let { targetId ->
                val affected = remember(targetId, messages) {
                    val idx = messages.indexOfFirst { it.id == targetId }
                    if (idx < 0) 0 else messages.size - idx
                }
                MinisAlertDialog(
                    onDismissRequest = { deleteFromHereTargetId = null },
                    title = stringResource(R.string.chat_longpress_delete_from_here),
                    text = pluralStringResource(
                        R.plurals.chat_delete_from_here_dialog_body,
                        affected,
                        affected,
                    ),
                    confirmText = stringResource(R.string.chat_delete_from_here_confirm),
                    isDestructive = true,
                    onConfirm = {
                        viewModel.deleteFromMessage(targetId)
                        deleteFromHereTargetId = null
                    },
                )
            }
            // [T-new-chat-menu-entry] Streaming guard for the menu's New Chat:
            // confirm → stop the running task, then navigate to a fresh draft;
            // dismiss → stay in the current chat.
            if (showNewChatStopDialog) {
                MinisAlertDialog(
                    onDismissRequest = { showNewChatStopDialog = false },
                    title = stringResource(R.string.chat_menu_new_chat),
                    text = stringResource(R.string.chat_new_chat_stop_dialog_body),
                    confirmText = stringResource(R.string.chat_new_chat_stop_dialog_confirm),
                    isDestructive = true,
                    onConfirm = {
                        showNewChatStopDialog = false
                        viewModel.cancelStream()
                        onNewChat()
                    },
                )
            }
            // [T-android-enhanced-cache] One-time extra-billing confirmation
            // before the first enable. Accepting records the durable ack and
            // turns the toggle on; subsequent enables skip the dialog.
            if (showEnhancedCacheDialog) {
                MinisAlertDialog(
                    onDismissRequest = { showEnhancedCacheDialog = false },
                    title = stringResource(R.string.chat_menu_enhanced_cache),
                    text = stringResource(R.string.enhanced_cache_dialog_body),
                    confirmText = stringResource(R.string.enhanced_cache_dialog_confirm),
                    onConfirm = {
                        viewModel.confirmAndEnableEnhancedCache()
                        showEnhancedCacheDialog = false
                    },
                )
            }
        }
        // Top gradient fade: messages fade into the Scaffold background.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(6.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            ChatColors.background,
                            ChatColors.background.copy(alpha = 0f),
                        ),
                    )
                )
        )
        }
    }

    // Browser bottom sheet
    if (showBrowserSheet) {
        BrowserSheet(
            tabPool = viewModel.browserTabPool,
            onDismiss = { viewModel.dismissBrowserSheet() },
        )
    }

    // Session Token Usage bottom sheet
    if (showTokenUsageSheet) {
        TokenUsageSheet(
            viewModel = viewModel,
            onDismiss = { showTokenUsageSheet = false },
        )
    }

    // Memory bottom sheet
    if (showMemorySheet && memoryRepository != null) {
        SessionMemorySheet(
            memoryRepository = memoryRepository,
            toolRecords = memoryToolRecords,
            onDismiss = { viewModel.dismissMemorySheet() },
            onRevokeRecord = { record -> viewModel.revokeMemoryRecord(record) },
            onSaveRecord = { record, newContent -> viewModel.replaceMemoryRecord(record, newContent) },
            // Show what the injector reads, not a file guess: resolved persona
            // for this session's provider, both GLOBAL.md levels, and the
            // captured assembly snapshot as ground truth.
            sources = InjectionSources(
                appRepo = viewModel.memoryRepository,
                sessionRepo = runCatching { viewModel.sessionMemoryRepo() }.getOrNull(),
                providerInstanceId = viewModel.activeProviderInstanceId(),
                providerLabel = viewModel.activeProviderInstanceLabel(),
                sessionId = viewModel.sessionId,
            ),
        )
    }

    // Session Skills bottom sheet
    if (showSkillsSheet && skillRepository != null) {
        SessionSkillsSheet(
            skillRepository = skillRepository,
            sessionId = sessionId,
            onDismiss = { showSkillsSheet = false },
        )
    }

    // [T-mcp-integration-android] MCPs-in-Session sheet.
    if (showMcpsSheet && mcpRepository != null) {
        SessionMcpsSheet(
            mcpRepository = mcpRepository,
            sessionId = sessionId,
            onDismiss = { showMcpsSheet = false },
        )
    }

    // [T-android-thinking-badge-navbar] Thinking-level sheet opened by tapping
    // the navbar thinking badge. Mirrors iOS ThinkingLevelSheetView: an Off row
    // plus every level the current model supports, each selectable.
    if (showThinkingLevelSheet) {
        val currentThinkingLevel by viewModel.thinkingLevel.collectAsState()
        ThinkingLevelSheet(
            currentLevel = currentThinkingLevel,
            availableLevels = viewModel.availableThinkingLevels,
            onSelect = { level ->
                viewModel.setThinkingLevel(level)
                showThinkingLevelSheet = false
            },
            onDismiss = { showThinkingLevelSheet = false },
        )
    }

    // Model Picker bottom sheet
    if (showModelPicker) {
        val config by providerRepository.config.collectAsState()
        val activeEntryId by viewModel.activeEntryId.collectAsState()

        // When the user picks a model whose output is image/audio/video, defer
        // the actual binding behind a confirmation dialog — those models can't
        // drive an Agent loop, so we steer the user toward a text-output model
        // (or, if they really want it, hint at adding it as a tool inside an
        // Agent loop instead).
        var pendingNonTextSelection by remember {
            mutableStateOf<PendingNonTextSelection?>(null)
        }
        val resolveImageLabel = stringResource(R.string.model_picker_modality_image)
        val resolveAudioLabel = stringResource(R.string.model_picker_modality_audio)
        val resolveVideoLabel = stringResource(R.string.model_picker_modality_video)
        fun nonTextLabelFor(model: LLMModel): String? {
            val mods = model.outputModalities?.map { it.lowercase() } ?: emptyList()
            return when {
                "image" in mods -> resolveImageLabel
                "audio" in mods -> resolveAudioLabel
                "video" in mods -> resolveVideoLabel
                else -> null
            }
        }
        fun entryById(entryId: String): ModelEntry? =
            config.modelEntries.firstOrNull { it.id == entryId }

        ModelPickerSheet(
            groups = availableGroups,
            selectedGroupId = selectedGroupId,
            activeEntryId = activeEntryId,
            defaultPrimaryGroupId = config.defaultPrimaryGroupId,
            config = config,
            providerRepository = providerRepository,
            onSelectGroup = { groupId ->
                val group = availableGroups.firstOrNull { it.id == groupId }
                val firstEntry = group?.memberEntryIds?.firstNotNullOfOrNull(::entryById)
                val label = firstEntry?.model?.let(::nonTextLabelFor)
                if (label != null) {
                    pendingNonTextSelection = PendingNonTextSelection.Group(
                        groupId = groupId,
                        modelDisplayName = firstEntry.model.displayName,
                        modalityLabel = label,
                    )
                } else {
                    viewModel.selectGroup(groupId)
                    showModelPicker = false
                }
            },
            onSelectGroupEntry = { groupId, entryId ->
                val entry = entryById(entryId)
                val label = entry?.model?.let(::nonTextLabelFor)
                if (entry != null && label != null) {
                    pendingNonTextSelection = PendingNonTextSelection.GroupEntry(
                        groupId = groupId,
                        entryId = entryId,
                        modelDisplayName = entry.model.displayName,
                        modalityLabel = label,
                    )
                } else {
                    viewModel.selectGroupEntry(groupId, entryId)
                    showModelPicker = false
                }
            },
            onSelectEntry = { entryId ->
                val entry = entryById(entryId)
                val label = entry?.model?.let(::nonTextLabelFor)
                if (entry != null && label != null) {
                    pendingNonTextSelection = PendingNonTextSelection.Entry(
                        entryId = entryId,
                        modelDisplayName = entry.model.displayName,
                        modalityLabel = label,
                    )
                } else {
                    viewModel.selectEntry(entryId)
                    showModelPicker = false
                }
            },
            onDismiss = { showModelPicker = false },
            // [T-android-modelpicker-group-edit] Close the picker first, then
            // navigate — pushing the management screen on top of an open bottom
            // sheet leaves the sheet lingering behind it on back.
            onEditGroups = {
                showModelPicker = false
                onModelGroupsClick()
            },
        )

        pendingNonTextSelection?.let { pending ->
            MinisAlertDialog(
                onDismissRequest = { pendingNonTextSelection = null },
                title = stringResource(R.string.model_picker_non_text_warning_title),
                text = stringResource(
                    R.string.model_picker_non_text_warning_body,
                    pending.modelDisplayName,
                    pending.modalityLabel,
                    pending.modalityLabel,
                ),
                confirmText = stringResource(R.string.model_picker_non_text_warning_use_anyway),
                dismissText = stringResource(R.string.model_picker_non_text_warning_choose_other),
                onConfirm = {
                    when (val sel = pending) {
                        is PendingNonTextSelection.Group -> viewModel.selectGroup(sel.groupId)
                        is PendingNonTextSelection.GroupEntry ->
                            viewModel.selectGroupEntry(sel.groupId, sel.entryId)
                        is PendingNonTextSelection.Entry -> viewModel.selectEntry(sel.entryId)
                    }
                    pendingNonTextSelection = null
                    showModelPicker = false
                },
            )
        }
    }

    // Offload permission dialog
    OffloadPermissionDialog()

    // T279: sandbox file preview is now routed through the NavHost
    // FILE_PREVIEW destination via onPreviewAttachment (see line ~1103),
    // matching how user-bubble attachments and "Browse Chat Files" already work.
    // The old in-place Dialog wrapper here was the source of the gray
    // status/nav bars — a Compose Dialog creates its own Window that
    // doesn't inherit MainActivity's enableEdgeToEdge, so the platform
    // default scrim painted over the bars regardless of what
    // FilePreviewScreen itself did.

    // Fullscreen image gallery — tapped image link from chat markdown or
    // composer chip. Pager-backed so multi-image messages support iOS-
    // style swipe between images. Single-image case is a 1-item list.
    previewImageGallery?.let { (items, startIdx) ->
        com.openminis.app.ui.components.ImageGalleryViewer(
            items = items,
            startIndex = startIdx,
            onDismiss = { previewImageGallery = null },
        )
    }

    // Fullscreen video player — tapped video link (mp4/mov/m4v/…) from chat
    // markdown. Reuses the same dialog player as the markdown-rendered
    // ![](minis://...) syntax so behaviour is consistent regardless of how
    // the LLM emitted the reference.
    previewVideoFile?.let { file ->
        com.openminis.app.ui.media.MinisFullscreenVideoPlayer(
            file = file,
            onDismiss = { previewVideoFile = null },
        )
    }

    // T-pwa-2: Add-to-Home-Screen sheet, hosted at screen level so it can
    // outlive the chip that triggered it (the chip Box may scroll out of
    // composition while the sheet is up).
    webAppSheetTarget?.let { target ->
        com.openminis.app.webapp.AddToHomeSheet(
            source = com.openminis.app.webapp.WebAppSource.ChatAttachment(
                uri = target.uri,
                fileName = target.fileName,
                sessionId = sessionId,
                sessionTitle = null,
            ),
            onDismiss = { webAppSheetTarget = null },
        )
    }
    } // CompositionLocalProvider
}

private data class HistoryEdgeVisible(
    val olderSentinel: Boolean,
    val newerSentinel: Boolean,
    val newestEdge: Boolean,
    val oldestEdge: Boolean,
)

@Composable
private fun HistoryPageEdge(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

// [T-android-split-chat] UserMessageBubble / UserAttachmentList /
// FileAttachmentTile / fileIconFor / ImageGalleryDialog moved verbatim to
// ChatUserMessageUI.kt.
// [T-android-split-chat] FlatChatItem / mergeStreamingOverlay / buildFlatChatItems
// moved verbatim to ChatFlatItems.kt (now internal).

// [T-android-split-chat] AssistantHeader / AssistantMessageView /
// BoundsTrackedBlock / InlineErrorBanner / ToolStopButton /
// formatToolDetailsForClipboard / ToolCallPill / ThinkingBlock moved verbatim
// to ChatAssistantMessageUI.kt.

// ─── Tool Detail Bottom Sheet (iOS: ToolLiveSheet — nav bar + content + bottom bar) ──

// [T-android-split-chat] ToolDetailSheet + helpers (extractShellCommand,
// extractPartialJsonString, chunkToolOutput, initialRevealChunks,
// LazyRevealToolText, EditorCard) moved verbatim to ChatToolDetailUI.kt.
// [T-android-split-chat] AttachmentChip / InputCircleButton / MicButton /
// ToolPreviewThumbnail / FloatingToolStatusBar / ThinkingLevelPicker moved
// verbatim to ChatComposerWidgets.kt.

// [T-android-split-chat] createCameraOutputUri / getFileName /
// PendingNonTextSelection moved verbatim to ChatScreenHelpers.kt (now internal).


// [T-android-split-chat] fuzzyMatch / ModelPickerSheet / providerDotColor moved
// verbatim to ChatModelPickerSheet.kt (ModelPickerSheet now internal).

// [T-android-split-chat] BorderedMarkdownTable / FallbackInfoBlock /
// CompactSummarySheet / parseInlineMarkdown / rememberBrowserLiveSnapshot /
// ResumeBanner / SwipeToSendHint moved verbatim to ChatMiscViews.kt.
// Sun May 24 11:01:25 CST 2026


