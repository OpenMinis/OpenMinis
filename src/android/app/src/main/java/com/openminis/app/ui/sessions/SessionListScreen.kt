package com.openminis.app.ui.sessions

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChecklistRtl
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import android.content.Intent
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.openminis.app.service.SessionActivityTracker
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.minisFabColor
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit
import com.openminis.app.ui.components.MinisTextButton

// FAB color — use shared theme values

private data class CategoryStyle(val icon: ImageVector, val color: Color)

// 16 categories matching iOS (ContentView.swift:1897-1916)
private fun categoryStyle(category: String?): CategoryStyle {
    return when (category?.lowercase()) {
        "code"         -> CategoryStyle(Icons.Outlined.Code, Color(0xFFF09A37))
        "writing"      -> CategoryStyle(Icons.Outlined.Description, Color(0xFF3478F6))
        "research"     -> CategoryStyle(Icons.Outlined.Language, Color(0xFF30B0C7))
        "analysis"     -> CategoryStyle(Icons.Outlined.BarChart, Color(0xFF5856D6))
        "creative"     -> CategoryStyle(Icons.Outlined.Brush, Color(0xFFFF2D55))
        "chat"         -> CategoryStyle(Icons.Outlined.Forum, Color(0xFF34C759))
        "math"         -> CategoryStyle(Icons.Outlined.Calculate, Color(0xFF9B59B6))
        "translation"  -> CategoryStyle(Icons.Outlined.Translate, Color(0xFF00BCD4))
        "health"       -> CategoryStyle(Icons.Outlined.Favorite, Color(0xFFFF3B30))
        "finance"      -> CategoryStyle(Icons.Outlined.Payments, Color(0xFF00C7BE))
        "travel"       -> CategoryStyle(Icons.Outlined.Map, Color(0xFFF09A37))
        "education"    -> CategoryStyle(Icons.Outlined.Book, Color(0xFF3478F6))
        "design"       -> CategoryStyle(Icons.Outlined.Palette, Color(0xFFFF2D55))
        "productivity" -> CategoryStyle(Icons.Outlined.CalendarMonth, Color(0xFFFFCC00))
        "support"      -> CategoryStyle(Icons.Outlined.Settings, Color(0xFF8B6914))
        "other"        -> CategoryStyle(Icons.Outlined.GridView, Color(0xFF8E8E93))
        else           -> CategoryStyle(Icons.Outlined.Forum, Color(0xFF8E8E93))
    }
}

// Date period for section grouping (matching iOS)
private enum class DatePeriod(val label: String) {
    PINNED("Pinned"),     // labels are i18n'd at render time via sectionLabelFor
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This Week"),
    THIS_MONTH("This Month"),
    EARLIER("Earlier"),
}

/**
 * Map a session's updatedAt timestamp to its display bucket. Mirrors iOS
 * `ContentView.groupedSessions`:
 *   - Today / Yesterday: calendar-day match
 *   - This Week: within the last 7 days (rolling window, not current week)
 *   - This Month: within the last 30 days (rolling window, NOT current calendar month)
 *   - Earlier: everything else
 */
private fun datePeriod(timestamp: Long): DatePeriod {
    val now = Calendar.getInstance()
    val cal = Calendar.getInstance().apply { time = Date(timestamp) }

    if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    ) return DatePeriod.TODAY

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    ) return DatePeriod.YESTERDAY

    val diffDays = TimeUnit.MILLISECONDS.toDays(now.timeInMillis - timestamp)
    if (diffDays < 7) return DatePeriod.THIS_WEEK

    // iOS uses `monthAgo = now - 1 month` (rolling window). A calendar-month
    // match would push e.g. a March 30 session into "Earlier" on April 2 —
    // iOS still shows it in "This Month" until May 2.
    val monthAgo = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.timeInMillis
    if (timestamp > monthAgo) return DatePeriod.THIS_MONTH

    return DatePeriod.EARLIER
}

/**
 * [T-android-session-grouping] One rendered group block: a user-created group
 * plus the sessions filed into it.
 *
 * Holds session IDS, not session objects — the list differ re-evaluates this on
 * every emission, so the value must stay cheap to compare. (iOS learned the
 * same lesson as `SidebarGroup`; a `List<ChatSessionEntity>` here deep-compares
 * long message strings on every tick.)
 *
 * `ids` is EMPTY while collapsed, but [totalCount] keeps the real number so the
 * card can still say "5 chats".
 */
/**
 * [T-android-group-pause-badge-restamp] How recently a session must have
 * ENTERED its badge state for that badge to pass through to the collapsed
 * group card. 24h, matching iOS's `freshCornerBadgeSessionIds(within: 24*3600)`.
 */
private const val GROUP_BADGE_FRESH_WINDOW_MS = 24L * 60L * 60L * 1000L

data class FolderGroupBlock(
    val folder: FolderEntity,
    val ids: List<String>,
    val totalCount: Int,
    val isCollapsed: Boolean,
    val latestUpdatedAt: Long,
    /** Newest member's title — iOS folderSectionHeader's "N chats · title" summary line. */
    val summaryTitle: String? = null,
    /** Newest member's category — tints the composed folder icon like iOS FolderComposedIcon. */
    val firstCategory: String? = null,
    /**
     * [T-android-group-pause-badge-restamp] Any member carrying a FRESH corner
     * badge (entered within the last 24h). Rendered on the card icon ONLY while
     * the group is collapsed — expanded members carry their own row badges, and
     * a header copy would leave the user guessing which row it refers to.
     * Mirrors iOS `SidebarGroup.anyPaused`.
     *
     * The freshness window is a GROUP-CARD filter only: session rows keep
     * rendering their badge unfiltered at any age. It exists so a pause from
     * days ago stops flagging its whole group forever.
     */
    val anyPaused: Boolean = false,
    /**
     * [T-android-group-running-ring] Any member currently running its agent
     * loop. Drives the collapsed group card's SpinningRing, so a task started
     * inside a group stays visible after the group is folded shut — otherwise
     * collapsing the group hides the only signal that work is in flight.
     * Mirrors iOS `SidebarGroup.anyActive` (ContentView.swift:4771).
     *
     * Unlike [anyPaused] there is no freshness window: "running" is live state
     * that ends on its own, so it can never go stale.
     */
    val anyActive: Boolean = false,
)

/**
 * [T-android-session-grouping] Partition sessions into group blocks + the
 * ungrouped remainder.
 *
 * Ordering rules, ported from iOS `computeGroupedSessionIDs`:
 *  - Input arrives `updated_at DESC`, so first-encounter order over the filed
 *    sessions IS the groups' activity order — no separate sort needed.
 *  - Pinned groups float above unpinned as a STABLE PARTITION, not a re-sort,
 *    so activity order survives inside each half.
 *  - **Group membership outranks pin for PLACEMENT**: a pinned session that is
 *    also filed renders inside its group, not in the Pinned bucket. Otherwise
 *    filing a pinned session looks like a no-op — the write lands but the row
 *    never moves. The pin itself is untouched: pinned members sort first inside
 *    the group and keep their pin glyph.
 *  - A `folder_id` pointing at a group we don't have renders as UNGROUPED
 *    rather than vanishing. There is no FK, so this is a normal state.
 *  - Empty groups still render — a group that disappears when its last session
 *    moves out reads as data loss.
 */
// `internal` so the accordion invariant can be tested for real rather than by
// matching source text — see SessionGroupAccordionTest.
internal fun partitionByFolder(
    sessions: List<ChatSessionEntity>,
    folders: List<FolderEntity>,
    collapsedIds: Set<String>,
    /**
     * Session ids carrying a corner badge entered within the last 24h,
     * snapshotted ONCE by the caller (iOS computes `freshCornerIds` the same
     * way, once per grouping pass, rather than entering the store per member).
     */
    freshBadgedIds: Set<String> = emptySet(),
    /** Ids whose agent loop is running — see [FolderGroupBlock.anyActive]. */
    activeSessionIds: Set<String> = emptySet(),
): Pair<List<FolderGroupBlock>, List<ChatSessionEntity>> {
    if (folders.isEmpty()) return emptyList<FolderGroupBlock>() to sessions

    val byId = folders.associateBy { it.id }
    val members = LinkedHashMap<String, MutableList<ChatSessionEntity>>()
    val ungrouped = mutableListOf<ChatSessionEntity>()

    for (s in sessions) {
        val fid = s.folderId
        // Presence check against the loaded map — never a DB constraint.
        if (fid != null && byId.containsKey(fid)) {
            members.getOrPut(fid) { mutableListOf() }.add(s)
        } else {
            ungrouped.add(s)
        }
    }

    // First-encounter order = activity order. Groups with no members are
    // appended afterwards so they still render.
    val ordered = members.keys.toMutableList()
    for (f in folders) if (f.id !in members) ordered.add(f.id)

    // [T-android-group-accordion] At most ONE group is open at a time.
    //
    // `collapsedIds` stores the inverse (which groups are shut), so an empty
    // set — a fresh install, or a device whose folders all arrived from a
    // restore — means "nothing is collapsed", i.e. everything unfolds at once.
    // That is the state the user reported. The toggle already enforces the
    // accordion; this makes the invariant hold on the way IN as well, so it
    // cannot be violated by a set that no interaction has touched yet.
    //
    // The survivor is the first in `ordered`, which is activity order — the
    // most recently used group is the one worth having open.
    val openId = ordered.firstOrNull { it !in collapsedIds }

    val blocks = ordered.mapNotNull { fid ->
        val folder = byId[fid] ?: return@mapNotNull null
        val m = members[fid].orEmpty()
        val collapsed = fid != openId
        // Pinned members first, stable partition — the pin is a display
        // affordance inside the group, not a reason to leave it.
        val displayOrdered = m.filter { it.pinnedAt != null } + m.filter { it.pinnedAt == null }
        FolderGroupBlock(
            folder = folder,
            ids = if (collapsed) emptyList() else displayOrdered.map { it.id },
            totalCount = m.size,
            isCollapsed = collapsed,
            // Recency order (not display order) — this means "newest activity".
            latestUpdatedAt = m.firstOrNull()?.updatedAt ?: folder.updatedAt,
            summaryTitle = m.firstOrNull()?.title,
            firstCategory = m.firstOrNull()?.category,
            // Early-exiting hash lookups over the pre-built snapshot — never a
            // per-member entry into the badge store.
            anyPaused = freshBadgedIds.isNotEmpty() && m.any { it.id in freshBadgedIds },
            anyActive = activeSessionIds.isNotEmpty() && m.any { it.id in activeSessionIds },
        )
    }

    val pinnedFirst = blocks.filter { it.folder.isPinned } + blocks.filter { !it.folder.isPinned }
    return pinnedFirst to ungrouped
}

private fun groupSessionsByDate(sessions: List<ChatSessionEntity>): List<Pair<DatePeriod, List<ChatSessionEntity>>> {
    val pinned = sessions.filter { it.pinnedAt != null }.sortedByDescending { it.pinnedAt }
    val unpinned = sessions.filter { it.pinnedAt == null }
    val grouped = unpinned.groupBy { datePeriod(it.updatedAt) }
    val result = mutableListOf<Pair<DatePeriod, List<ChatSessionEntity>>>()
    if (pinned.isNotEmpty()) {
        result.add(DatePeriod.PINNED to pinned)
    }
    for (period in DatePeriod.entries) {
        if (period == DatePeriod.PINNED) continue
        grouped[period]?.let { result.add(period to it) }
    }
    return result
}

private fun relativeDate(context: Context, timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val seconds = TimeUnit.MILLISECONDS.toSeconds(diff)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)

    if (seconds < 60) return context.getString(R.string.time_just_now)
    if (minutes < 60) return context.getString(R.string.time_minutes_ago, minutes.toInt())
    if (hours < 24) return context.getString(R.string.time_hours_ago, hours.toInt())

    val dateCal = Calendar.getInstance().apply { time = Date(timestamp) }
    val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (dateCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
        dateCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)
    ) {
        return context.getString(R.string.time_yesterday)
    }

    val days = TimeUnit.MILLISECONDS.toDays(diff)
    if (days < 7) {
        // T172: device-locale weekday names via java.text.DateFormatSymbols.
        val dayNames = java.text.DateFormatSymbols(java.util.Locale.getDefault()).weekdays
        return dayNames[dateCal.get(Calendar.DAY_OF_WEEK)]
    }

    val month = dateCal.get(Calendar.MONTH) + 1
    val day = dateCal.get(Calendar.DAY_OF_MONTH)
    return "$month/$day"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    onSessionClick: (String) -> Unit,
    onNewChat: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onAddProviderClick: () -> Unit = {},
    onSelectModelsClick: () -> Unit = {},
    onTerminalClick: () -> Unit = {},
    onRootfsClick: () -> Unit = {},
    // [T-android-scheduled-tasks-design] Entry to the scheduled-tasks list.
    onScheduledTasksClick: () -> Unit = {},
    /**
     * [T-android-tablet-split] The session currently shown in the detail pane,
     * highlighted in the list. Non-null only in two-pane (tablet) mode — in
     * single-pane the list is never on screen next to a chat, so there is
     * nothing to reflect and the parameter stays null, leaving phone rendering
     * byte-identical to before.
     *
     * Deliberately NOT read from the nav back stack: in two-pane mode the
     * detail pane is owned by the ListDetail pane navigator, not by a NavHost
     * entry, so the back stack does not know what the detail is showing.
     *
     * A draft ("__new__…") id never matches a persisted row, so a new chat
     * highlights nothing — which is the intended behaviour (iOS parity: the
     * list has no selection until the draft is saved).
     */
    selectedSessionId: String? = null,
    /**
     * [T-android-draft-placeholder-row] Id of an unsaved draft the detail pane
     * is showing. Renders a synthetic "New Chat / No messages yet" row at the
     * top so the two-pane list always has a row matching the chat on screen.
     *
     * The row is a pure view construct with no database backing: a session is
     * created only by `ChatViewModel.ensureSession()` on the first send, so an
     * abandoned draft simply stops being passed here and the row disappears —
     * nothing to clean up. Mirrors iOS `ContentView.displaySessions`, which
     * prepends the same placeholder in split mode.
     */
    draftPlaceholderId: String? = null,
) {
    val context = LocalContext.current
    // T46: hoist VM ownership to the NavBackStackEntry's ViewModelStore so
    // [searchQuery] / [isSearchActive] survive navigation. The previous
    // `remember {}` scoping tied the VM to the composable's lifetime — pushing
    // to chat detail destroyed it, then pop-back rebuilt a fresh VM with
    // empty search state. Mirrors iOS where ContentView's `@State searchText`
    // survives a NavigationLink push because the parent view never unmounts.
    val viewModel: SessionListViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = SessionListViewModel.factory(chatRepository, providerRepository, context),
    )
    val persistedSessions by viewModel.displayedSessions.collectAsState()
    // [T-android-draft-placeholder-row] Prepend the synthetic draft row. Built
    // here rather than in the ViewModel precisely because it must never reach
    // the database or the repository's flows — it exists for exactly as long
    // as the detail pane holds an unsaved draft, and vanishes on its own when
    // the user switches away without sending. Mirrors iOS displaySessions.
    val sessions = remember(persistedSessions, draftPlaceholderId) {
        val draftId = draftPlaceholderId
        if (draftId == null || persistedSessions.any { it.id == draftId }) {
            persistedSessions
        } else {
            val now = System.currentTimeMillis()
            listOf(
                com.openminis.app.data.db.ChatSessionEntity(
                    id = draftId,
                    // Left null so the row falls through to the same
                    // "New Chat" default a titleless persisted session uses.
                    title = null,
                    modelId = "",
                    createdAt = now,
                    updatedAt = now,
                    folderId = null,
                ),
            ) + persistedSessions
        }
    }
    val isInitialLoadComplete by viewModel.isInitialLoadComplete.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchSnippets by viewModel.searchSnippets.collectAsState()
    val isSelecting by viewModel.isSelecting.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val regeneratingIds by viewModel.regeneratingIds.collectAsState()
    val providerConfig by providerRepository.config.collectAsState()
    val hasProviders = providerConfig.instances.isNotEmpty()
    val hasGroups = providerConfig.modelGroups.isNotEmpty()
    // [T-android-startup-config-stall] Provider config now loads off-thread, so
    // for a brief startup window `providerConfig` is the empty placeholder.
    // Gate the onboarding/list render on this too (alongside the sessions
    // initial-load flag) so an existing user with providers but zero sessions
    // doesn't flash the "add a provider" onboarding before the real config emits.
    val configLoaded by providerRepository.configLoaded.collectAsState()
    val scope = rememberCoroutineScope()
    val isDark = ChatColors.isDark

    // [T-android-search-focus-sticky] When the user opens search but types
    // nothing (or only whitespace) and then navigates into a chat, the
    // VM-backed search state survives the navigation, so on return the search
    // bar is still open and focused — the user has to manually tap the X to
    // close it. Collapse search BEFORE navigating when the query is blank;
    // keep it (query + results) when there's a real query so returning lands
    // back on the same search. Collapsing flips isSearchActive false, which
    // removes the search TextField from composition and releases its focus.
    fun exitSearchIfQueryBlank() {
        if (viewModel.isSearchActive.value && viewModel.searchQuery.value.isBlank()) {
            viewModel.searchQuery.value = ""
            viewModel.isSearchActive.value = false
        }
    }

    // [T-android-new-chat-shortcut] Ctrl/⌘+F, raised at the split scaffold's
    // root so it fires from either pane. Opening the search is all that is
    // needed — the field takes focus on its own when it enters composition
    // (see the LaunchedEffect on the search TextField).
    //
    // `drop(1)` skips the counter's current value: collecting a StateFlow
    // replays it immediately, which would spring the search open every time
    // this screen recomposed into view after any earlier shortcut press.
    val searchRequests = com.openminis.app.ui.navigation.SessionSearchRequest.requests
    LaunchedEffect(Unit) {
        searchRequests.drop(1).collect {
            viewModel.isSearchActive.value = true
        }
    }
    // Wrapped navigation callbacks: run the search-collapse check first, then
    // navigate. Used everywhere a session tap / new-chat creation navigates.
    // [T-session-paused-badge-active-false-positive] Do NOT clear the PAUSED
    // badge on open: merely opening an interrupted session does not resolve it.
    // The badge is now driven by ChatViewModel's canResume flow — it clears only
    // when the interruption is actually resolved (Resume tapped / new message
    // sent / loop completed), and the ChatViewModel re-asserts it on load if the
    // session is still interrupted. Clearing here just caused a flicker.
    val onSessionClickGuarded: (String) -> Unit = { id ->
        exitSearchIfQueryBlank()
        onSessionClick(id)
    }
    val onNewChatGuarded: (String) -> Unit = { id -> exitSearchIfQueryBlank(); onNewChat(id) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteTargetId by remember { mutableStateOf<String?>(null) }
    // [T-android-session-grouping] Group management dialogs.
    var folderToRename by remember { mutableStateOf<FolderEntity?>(null) }
    var folderToDissolve by remember { mutableStateOf<FolderEntity?>(null) }
    // iOS "Delete Group & N Sessions" — pair carries the member count so the
    // confirmation can restate the consequence.
    var folderToDelete by remember { mutableStateOf<Pair<FolderEntity, Int>?>(null) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var editSession by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var showBrowserSheet by remember { mutableStateOf(false) }
    var showBrowserSettings by remember { mutableStateOf(false) }
    val browserTabPool = remember { com.openminis.app.browser.BrowserTabPool(context) }

    // [T-android-session-grouping] Groups are pulled out FIRST; only the
    // leftovers go through date bucketing. Assembly order below is
    // Pinned → group block → date buckets, matching iOS.
    val folders by viewModel.folders.collectAsState()
    val collapsedFolderIds by viewModel.collapsedFolderIds.collectAsState()
    val folderMemberCounts by viewModel.folderMemberCounts.collectAsState()
    val groupPickerRequest by viewModel.groupPickerRequest.collectAsState()
    // While searching, group cards are suppressed: padding a result set with
    // every non-matching group is noise, not structure.
    val showFolderBlock = !isSearchActive || searchQuery.isBlank()
    // [T-android-group-pause-badge-restamp] Snapshot the fresh-badge set ONCE
    // per grouping pass (iOS does the same in computeGroupedSessionIDs). The
    // set itself is the recomposition key: as badges age past the 24h window
    // the set recomputed on the next ambient refresh differs and the cards
    // re-derive — which is exactly the "picked up on the next refresh rather
    // than by a dedicated timer" semantics iOS documents. `sessionBadges` is
    // collected so a push/remove re-enters this block promptly.
    // `revision` is keyed alongside the queue map because a pure RE-STAMP
    // (badge already at the head of its queue) leaves `byId` equals-identical
    // and is therefore conflated away — yet it can flip a card from stale to
    // fresh. See SessionBadgeStore.revision.
    val sessionBadges by com.openminis.app.service.SessionBadgeStore.byId.collectAsState()
    val badgeRevision by com.openminis.app.service.SessionBadgeStore.revision.collectAsState()
    val freshBadgedIds = remember(sessionBadges, badgeRevision) {
        com.openminis.app.service.SessionBadgeStore
            .freshCornerBadgeSessionIds(GROUP_BADGE_FRESH_WINDOW_MS)
    }
    // [T-android-group-running-ring] Live running set, so a collapsed group
    // can show that one of its members is still working. Keyed into the
    // partition memo — without it the blocks would keep a stale snapshot and
    // the ring would never appear or never clear.
    val activeSessionIds by SessionActivityTracker.activeSessions.collectAsState()
    val folderPartition = remember(
        sessions, folders, collapsedFolderIds, showFolderBlock, freshBadgedIds, activeSessionIds,
    ) {
        if (showFolderBlock) {
            partitionByFolder(
                sessions, folders, collapsedFolderIds, freshBadgedIds, activeSessionIds,
            )
        } else emptyList<FolderGroupBlock>() to sessions
    }
    val folderBlocks = folderPartition.first
    val groupedSessions = remember(folderPartition) { groupSessionsByDate(folderPartition.second) }

    // [T-android-folder-accordion-anchor] Assembly order (Pinned → groups →
    // date buckets) hoisted OUT of the LazyColumn body so the scroll-anchor
    // effect and the mini-bar can reconstruct each folder header's flat item
    // index. MUST stay in lockstep with the item builder below — same data,
    // same order, one item per header/row.
    val pinnedFirst = groupedSessions.firstOrNull()?.first == DatePeriod.PINNED
    val leadingDateGroups = if (pinnedFirst) groupedSessions.take(1) else emptyList()
    val trailingDateGroups = if (pinnedFirst) groupedSessions.drop(1) else groupedSessions
    val folderHeaderIndices = remember(leadingDateGroups, folderBlocks) {
        buildMap {
            var idx = 0
            leadingDateGroups.forEach { (_, rows) -> idx += 1 + rows.size }
            if (folderBlocks.isNotEmpty()) {
                idx += 1 // the "分组" section header item
                folderBlocks.forEach { b ->
                    put(b.folder.id, idx)
                    idx += 1 + b.ids.size
                }
            }
        }
    }

    // [T-android-newchat-list-autoscroll] Hoisted scroll state so the VM's
    // new-session signal can drive it. The list is ORDER BY updated_at DESC,
    // so a freshly-used session lands at index 0 (top of the TODAY bucket).
    // But the LazyColumn retains its scroll offset across navigation (open
    // chat → back), so if the user had scrolled down, the new session sits
    // above the viewport and they have to scroll up to find it (Jackson 41429).
    //
    // The new-session DETECTION lives in the VM (retained across navigation)
    // because the list composable is disposed during the chat-detail push — a
    // composable-scoped tracker would reset its baseline on pop-back and miss
    // the new session that appeared while we were in the chat. Here we just
    // collect the one-shot event and scroll, skipping it during search (which
    // reorders the list) and selection mode (so it can't fight #765 multi-select).
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        viewModel.newTopSessionEvent.collect {
            if (!viewModel.isSearchActive.value && !viewModel.isSelecting.value) {
                listState.animateScrollToItem(0)
            }
        }
    }

    // [T-android-folder-accordion-anchor] Port of iOS toggleFolderCollapsed's
    // scroll correction. Opening folder B closes folder A (accordion); when A
    // sat ABOVE B with a long member list, A's rows vanish and B slides up —
    // often clean off the top edge, leaving the user staring at the wrong part
    // of the list. MINIMAL correction, not "scroll to top": after the
    // structural change lands (two frames — same reason iOS defers a runloop
    // turn: measuring now would read pre-collapse geometry), scroll B's header
    // back only if it actually LEFT the viewport. Expand-only — collapsing is
    // self-anchoring (the tapped header stays under the finger).
    var pendingExpandFolderId by remember { mutableStateOf<String?>(null) }
    val densityForAnchor = LocalDensity.current
    LaunchedEffect(pendingExpandFolderId) {
        val fid = pendingExpandFolderId ?: return@LaunchedEffect
        withFrameNanos {}
        withFrameNanos {}
        val key = "folder_$fid"
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.key == key }
        // 8dp slack so a header sitting exactly on the boundary isn't judged
        // off-screen by a sub-pixel rounding difference.
        val slack = with(densityForAnchor) { 8.dp.toPx() }.toInt()
        val offscreen = item == null ||
            item.offset < layout.viewportStartOffset - slack ||
            item.offset + item.size > layout.viewportEndOffset + slack
        if (offscreen) {
            folderHeaderIndices[fid]?.let { listState.animateScrollToItem(it) }
        }
        pendingExpandFolderId = null
    }

    // [T-android-folder-minibar] Port of iOS folderMiniBar: when an EXPANDED
    // group's header scrolls off the TOP, float a capsule with the group's
    // icon + name (tap → jump back to the header) and a circled chevron (tap
    // → collapse). Derived straight from LazyListState — no visibility probes
    // needed: header offscreen-above ⇔ the first visible item's index is past
    // the header's reconstructed index. Suppressed in select mode (iOS guard).
    val miniBarBlock by remember(folderBlocks, folderHeaderIndices, isSelecting) {
        derivedStateOf {
            if (isSelecting) return@derivedStateOf null
            val block = folderBlocks.firstOrNull { !it.isCollapsed && it.ids.isNotEmpty() }
                ?: return@derivedStateOf null
            val headerIdx = folderHeaderIndices[block.folder.id] ?: return@derivedStateOf null
            val infos = listState.layoutInfo.visibleItemsInfo
            when {
                infos.isEmpty() -> null
                infos.any { it.key == "folder_${block.folder.id}" } -> null
                infos.first().index > headerIdx -> block
                else -> null
            }
        }
    }

    // [T-android-scheduled-tasks-full] Live count of scheduled tasks for the
    // toolbar clock-icon badge. Observes the SharedPreferences-backed store so
    // the badge updates when tasks are added / removed without a manual refresh.
    // [T-android-scheduled-badge-enabled-only] Count only enabled tasks so the
    // badge reflects what's actually active — disabled tasks don't contribute,
    // and with none enabled the count is 0 (badge hidden by the >0 gate below).
    val scheduledTaskCount by remember {
        com.openminis.app.scheduled.ScheduledTaskStore(context).observe()
            .map { list -> list.count { it.enabled } }
    }.collectAsState(initial = 0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSelecting) {
                        Text(
                            if (selectedIds.isEmpty())
                                stringResource(R.string.sessionlist_select_title)
                            else
                                stringResource(R.string.sessionlist_n_selected, selectedIds.size),
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        )
                    } else {
                        Text(
                            stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        )
                    }
                },
                navigationIcon = {
                    if (isSelecting) {
                        MinisTextButton(onClick = { viewModel.clearSelection() }) {
                            Text(stringResource(R.string.cancel))
                        }
                    } else {
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.sessionlist_settings))
                        }
                    }
                },
                actions = {
                    if (isSelecting) {
                        MinisTextButton(onClick = { viewModel.selectAll() }) {
                            Text(
                                stringResource(
                                    if (selectedIds.size == sessions.size) R.string.sessionlist_deselect_all
                                    else R.string.sessionlist_select_all
                                )
                            )
                        }
                    } else {
                        // [T-android-scheduled-tasks-design] Scheduled-tasks entry,
                        // sits to the left of the Shell button on the home toolbar.
                        // [T-android-scheduled-tasks-full] Badge shows the count of
                        // scheduled tasks so the user can see at a glance how many
                        // are configured without opening the list.
                        IconButton(onClick = onScheduledTasksClick) {
                            if (scheduledTaskCount > 0) {
                                BadgedBox(badge = { Badge { Text("$scheduledTaskCount") } }) {
                                    Icon(
                                        Icons.Outlined.Schedule,
                                        contentDescription = stringResource(R.string.sessionlist_scheduled_tasks),
                                    )
                                }
                            } else {
                                Icon(
                                    Icons.Outlined.Schedule,
                                    contentDescription = stringResource(R.string.sessionlist_scheduled_tasks),
                                )
                            }
                        }
                        // Shell menu (matching iOS trailing shell button: Terminal, Rootfs, Browser)
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Outlined.Terminal, contentDescription = stringResource(R.string.sessionlist_shell))
                            }
                            MinisMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                                offset = DpOffset(0.dp, 0.dp),
                            ) {
                                if (sessions.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.sessionlist_select_action)) },
                                        onClick = {
                                            showOverflowMenu = false
                                            viewModel.isSelecting.value = true
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.ChecklistRtl, contentDescription = null)
                                        },
                                    )
                                    MinisMenuDivider()
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessionlist_shell_terminal)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onTerminalClick()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Terminal, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessionlist_rootfs_management)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onRootfsClick()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Settings, contentDescription = null)
                                    },
                                )
                                MinisMenuDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessionlist_open_browser)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        browserTabPool.ensureTabForUI()
                                        showBrowserSheet = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Language, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessionlist_browser_settings)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showBrowserSettings = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Settings, contentDescription = null)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        // No default FAB — we draw dual FABs manually at bottom
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Main content — render an empty frame until the first DB
            // emission lands. Otherwise `sessions.isEmpty()` reads true for
            // the brief window before Room delivers real data and the
            // onboarding flashes on top of existing user history. Mirrors
            // iOS `didInitialLoad` on ContentView. The transition is usually
            // sub-200ms, so no spinner.
            if (isInitialLoadComplete) Column(modifier = Modifier.fillMaxSize()) {
                if (sessions.isEmpty()) {
                    if (isSearchActive && searchQuery.isNotBlank()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.search_no_results, searchQuery),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (configLoaded) {
                        // Show the 3-step onboarding whenever there are no sessions —
                        // Step 3 (Start a Conversation) is the call-to-action after the
                        // user finishes Steps 1 and 2, so we must keep the landing
                        // visible even when hasProviders && hasGroups. Mirrors iOS
                        // ContentView.emptyState.
                        // [T-android-startup-config-stall] Gated on configLoaded so
                        // hasProviders/hasGroups reflect the real persisted config —
                        // otherwise a returning user with providers but no sessions
                        // would briefly see the "add a provider" step before the
                        // async config load emits. The list branch (sessions present)
                        // is intentionally NOT gated, so users with history still see
                        // it immediately without waiting on the config decode.
                        OnboardingLanding(
                            hasProviders = hasProviders,
                            hasGroups = hasGroups,
                            onAddProvider = onAddProviderClick,
                            onSelectModels = onSelectModelsClick,
                            onStartConversation = {
                                scope.launch {
                                    val sessionId = viewModel.createNewSession()
                                    if (sessionId != null) onNewChatGuarded(sessionId)
                                }
                            },
                        )
                    }
                } else {
                    LazyColumn(
                        // [T-android-newchat-list-autoscroll] Hoisted state so
                        // the new-session autoscroll effect above can drive it.
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // Leave space for bottom FAB row
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
                    ) {
                        // T25: search-active path used to flatten the list and skip
                        // section headers entirely. Now reuses the same grouped
                        // rendering — `displayedSessions` is already filtered by
                        // the VM when active && query.isNotBlank, so the same
                        // groupSessionsByDate(sessions) computation produces
                        // header buckets over the filtered set.
                        // [T-android-session-grouping] Assembly: Pinned →
                        // groups → date buckets — pinnedFirst / leading /
                        // trailing are hoisted above the LazyColumn so the
                        // accordion anchor + mini-bar share them; keep the
                        // builder and folderHeaderIndices in lockstep.

                        // [T-android-session-grouping] Membership is "points at a
                        // group that exists", matching partitionByFolder. Hoisted
                        // out of the row so it is not rebuilt per item.
                        val existingFolderIds = folders.mapTo(HashSet()) { it.id }

                        fun androidx.compose.foundation.lazy.LazyListScope.renderSessionRows(
                            rows: List<ChatSessionEntity>,
                            // [T-android-folder-card-ios-parity] Folder members
                            // render as MIDDLE/BOTTOM segments of the group's
                            // welded container (iOS FolderMemberRowBackground);
                            // ungrouped rows stay full-bleed.
                            inFolder: Boolean = false,
                        ) {
                            items(rows, key = { it.id }) { session ->
                                val activeQuery =
                                    if (isSearchActive && searchQuery.isNotBlank()) searchQuery else ""
                                val rowModifier = if (inFolder) {
                                    val isLast = session.id == rows.last().id
                                    Modifier
                                        .padding(
                                            start = 6.dp, end = 6.dp,
                                            bottom = if (isLast) 4.dp else 0.dp,
                                        )
                                        .folderSurface(
                                            segment = if (isLast) FolderSegment.BOTTOM
                                            else FolderSegment.MIDDLE,
                                            fill = folderFillColor(),
                                            edge = folderEdgeColor(),
                                        )
                                        // AFTER folderSurface so the drawn
                                        // fill/border stay outside the clip;
                                        // inside it, the row's press ripple is
                                        // shaped to the segment — square for
                                        // middles, bottom-rounded on the last
                                        // row so the highlight can't poke out
                                        // of the container's corners.
                                        .clip(
                                            if (isLast) {
                                                RoundedCornerShape(
                                                    bottomStart = 16.dp, bottomEnd = 16.dp,
                                                )
                                            } else {
                                                RoundedCornerShape(0.dp)
                                            },
                                        )
                                } else {
                                    Modifier
                                }
                                // animateItem gives the accordion its motion:
                                // member rows fade+slide over 250ms instead of
                                // popping — the iOS easeInOut(0.25) equivalent
                                // (monotonic tween on purpose; a spring's
                                // oscillation read as jitter on iOS).
                                Box(
                                    modifier = Modifier
                                        .animateItem(
                                            fadeInSpec = tween(250),
                                            fadeOutSpec = tween(250),
                                            placementSpec = tween(250),
                                        )
                                        .then(rowModifier),
                                ) {
                                SessionItemContent(
                                    session = session,
                                    isSelecting = isSelecting,
                                    selectedIds = selectedIds,
                                    onSessionClick = onSessionClickGuarded,
                                    onToggleSelect = { viewModel.toggleSelect(it) },
                                    onEnterSelect = { viewModel.enterSelection(it) },
                                    onPinToggle = { viewModel.togglePin(it) },
                                    onEditRequest = { editSession = it },
                                    onExportRequest = { s, fmt ->
                                        exportSession(context, s, chatRepository, scope, fmt)
                                    },
                                    onRegenerateTitle = { viewModel.regenerateTitle(it) },
                                    onDuplicate = { viewModel.duplicateSession(it) },
                                    onDeleteRequest = { id ->
                                        deleteTargetId = id
                                        showDeleteDialog = true
                                    },
                                    onMoveToGroup = { viewModel.requestGroupPicker(it) },
                                    isFiled = session.folderId != null &&
                                        session.folderId in existingFolderIds,
                                    isRegenerating = session.id in regeneratingIds,
                                    searchQuery = activeQuery,
                                    searchSnippet = searchSnippets[session.id],
                                    // Transparent so the folder container's
                                    // surface shows through member rows.
                                    //
                                    // [T-android-tablet-split] In two-pane mode
                                    // the row backing the detail pane is tinted,
                                    // giving the list the selected-row look iOS
                                    // gets free from `List(selection:)`.
                                    // primary@12% is the same treatment the
                                    // mention picker uses for its highlighted
                                    // row, so selection reads consistently.
                                    // Ordering matters: the folder-member case
                                    // stays transparent unless it is ALSO the
                                    // selected row, or a selected row inside a
                                    // group would show no highlight at all.
                                    rowBackground = when {
                                        session.id == selectedSessionId ->
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        inFolder -> Color.Transparent
                                        else -> null
                                    },
                                    isFolderMember = inFolder,
                                )
                                }
                            }
                        }

                        leadingDateGroups.forEach { (period, periodSessions) ->
                            item(key = "header_${period.name}") {
                                SectionHeader(title = stringResource(R.string.sessionlist_section_pinned))
                            }
                            renderSessionRows(periodSessions)
                        }

                        if (folderBlocks.isNotEmpty()) {
                            item(key = "header_groups") {
                                SectionHeader(title = stringResource(R.string.group_section_header))
                            }
                            folderBlocks.forEach { block ->
                                item(key = "folder_${block.folder.id}") {
                                    Box(Modifier.animateItem(placementSpec = tween(250))) {
                                    FolderCard(
                                        block = block,
                                        onToggle = {
                                            // Capture BEFORE the toggle — after it
                                            // the block still holds the old state.
                                            val willExpand = block.isCollapsed
                                            viewModel.toggleFolderCollapsed(block.folder.id)
                                            if (willExpand) {
                                                pendingExpandFolderId = block.folder.id
                                            }
                                        },
                                        onTogglePin = { viewModel.toggleFolderPin(block.folder.id) },
                                        onRename = { folderToRename = block.folder },
                                        onDissolve = { folderToDissolve = block.folder },
                                        onNewChatInGroup = {
                                            // iOS newChatInFolder: auto-expand
                                            // first so the new session doesn't
                                            // vanish into a collapsed group.
                                            if (block.isCollapsed) {
                                                viewModel.toggleFolderCollapsed(block.folder.id)
                                            }
                                            val sessionId = viewModel.createNewSession(
                                                folderId = block.folder.id,
                                            )
                                            if (sessionId != null) onNewChatGuarded(sessionId)
                                        },
                                        onDeleteWithSessions = {
                                            folderToDelete = block.folder to block.totalCount
                                        },
                                    )
                                    }
                                }
                                // Collapsed groups contribute no rows; the card
                                // still reports the real member count.
                                renderSessionRows(
                                    block.ids.mapNotNull { id -> sessions.firstOrNull { it.id == id } },
                                    inFolder = true,
                                )
                            }
                        }

                        trailingDateGroups.forEach { (period, periodSessions) ->
                            item(key = "header_${period.name}") {
                                SectionHeader(title = stringResource(when (period) {
                                    DatePeriod.PINNED -> R.string.sessionlist_section_pinned
                                    DatePeriod.TODAY -> R.string.sessionlist_section_today
                                    DatePeriod.YESTERDAY -> R.string.sessionlist_section_yesterday
                                    DatePeriod.THIS_WEEK -> R.string.sessionlist_section_this_week
                                    DatePeriod.THIS_MONTH -> R.string.sessionlist_section_this_month
                                    DatePeriod.EARLIER -> R.string.sessionlist_section_earlier
                                }))
                            }
                            renderSessionRows(periodSessions)
                        }
                    }
                }
            }

            // [T-android-folder-minibar] Floating quick-nav capsule (iOS
            // folderMiniBar): appears when the expanded group's header scrolls
            // off the top. Two interaction zones — icon+name jumps back to the
            // header, the circled chevron collapses the group. Split on
            // purpose: whole-bar-collapses made "where am I" and "close this"
            // the same target.
            run {
                var lastBar by remember { mutableStateOf<FolderGroupBlock?>(null) }
                miniBarBlock?.let { lastBar = it }
                val bar = lastBar
                AnimatedVisibility(
                    visible = miniBarBlock != null,
                    enter = slideInVertically(tween(200)) { -it } + fadeIn(tween(200)),
                    exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    if (bar != null) {
                        val headerIdx = folderHeaderIndices[bar.folder.id]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 8.dp, start = 24.dp, end = 24.dp)
                                .widthIn(max = 320.dp)
                                .height(48.dp)
                                .shadow(8.dp, RoundedCornerShape(24.dp))
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .border(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(24.dp),
                                )
                                .padding(start = 10.dp, end = 8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable(enabled = headerIdx != null) {
                                        scope.launch {
                                            headerIdx?.let { listState.animateScrollToItem(it) }
                                        }
                                    },
                            ) {
                                FolderComposedIcon(
                                    category = bar.firstCategory,
                                    diameter = 30.dp,
                                )
                                // Folder names are user data — verbatim.
                                Text(
                                    bar.folder.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                    )
                                    .clickable {
                                        viewModel.toggleFolderCollapsed(bar.folder.id)
                                    },
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.group_collapse),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Bottom area: dual FABs or selection toolbar (matching iOS fabRow / selectionToolbar)
            if (isSelecting) {
                // Selection toolbar at bottom (matching iOS: Export + Delete)
                SelectionToolbar(
                    selectedCount = selectedIds.size,
                    onExport = { /* TODO: export */ },
                    onMove = { viewModel.requestGroupPickerForSelection() },
                    onDelete = { showBulkDeleteDialog = true },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else if (hasProviders && (sessions.isNotEmpty() || isSearchActive)) {
                // Dual FAB row (matching iOS: New Chat left + Search right, or vice versa).
                // Hidden while the onboarding landing is showing — Step 3 provides the CTA.
                // T46: stay visible while search is active even when the result
                // set is empty, so the user can edit / clear the query without
                // having to rediscover the search FAB after a 0-hit query.
                DualFabRow(
                    isDark = isDark,
                    isSearchActive = isSearchActive,
                    searchQuery = searchQuery,
                    isSearching = isSearching,
                    hasSessions = sessions.isNotEmpty() || isSearchActive,
                    onNewChat = {
                        scope.launch {
                            val sessionId = viewModel.createNewSession()
                            if (sessionId != null) onNewChatGuarded(sessionId)
                        }
                    },
                    onNewChatWithGroup = { groupId ->
                        scope.launch {
                            val sessionId = viewModel.createNewSession(groupId = groupId)
                            if (sessionId != null) onNewChatGuarded(sessionId)
                        }
                    },
                    modelGroups = providerConfig.modelGroups,
                    onSearchToggle = {
                        if (isSearchActive) {
                            viewModel.searchQuery.value = ""
                            viewModel.isSearchActive.value = false
                        } else {
                            viewModel.isSearchActive.value = true
                        }
                    },
                    onSearchQueryChange = { viewModel.searchQuery.value = it },
                    onSearchDismiss = {
                        viewModel.searchQuery.value = ""
                        viewModel.isSearchActive.value = false
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    // Single delete confirmation
    if (showDeleteDialog && deleteTargetId != null) {
        MinisAlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                deleteTargetId = null
            },
            title = stringResource(R.string.sessionlist_delete_one_title),
            text = stringResource(R.string.sessionlist_delete_message),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                deleteTargetId?.let { viewModel.deleteSession(it) }
                showDeleteDialog = false
                deleteTargetId = null
            },
        )
    }

    // Bulk delete confirmation
    if (showBulkDeleteDialog) {
        MinisAlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            title = stringResource(R.string.sessionlist_delete_n_title, selectedIds.size),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                viewModel.deleteSelected()
                showBulkDeleteDialog = false
            },
        )
    }

    // ─── Session groups ────────────────────────────────────────────────────
    // [T-android-session-grouping]

    groupPickerRequest?.let { request ->
        // [T-android-group-ai-suggest] Suggestion state lives on the VM, not
        // in the sheet, so an in-flight request survives recomposition (and
        // the sheet's own remembered state being torn down).
        val suggesting by viewModel.groupSuggesting.collectAsState()
        val suggestFailed by viewModel.groupSuggestFailed.collectAsState()
        val suggestion by viewModel.groupSuggestion.collectAsState()
        GroupPickerSheet(
            folders = folders,
            memberCounts = folderMemberCounts,
            sessionCount = request.sessionIds.size,
            anyFiled = request.anyFiled,
            onChoose = { viewModel.applyGroupChoice(it) },
            onDismiss = { viewModel.dismissGroupPicker() },
            suggesting = suggesting,
            suggestFailed = suggestFailed,
            suggestion = suggestion,
            onSuggest = { viewModel.suggestGroup() },
        )
    }

    folderToRename?.let { folder ->
        // Both fields are SEEDED from the current group. The rename always
        // writes the description through, so an unseeded field would silently
        // wipe a description the user never touched.
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        var desc by remember(folder.id) { mutableStateOf(folder.description.orEmpty()) }
        // A plain AlertDialog rather than MinisAlertDialog: this one needs two
        // text fields, and MinisAlertDialog is a title/text/buttons component.
        // Widening it for a single caller would push layout complexity into
        // every other dialog in the app.
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.group_rename)) },
            text = {
                Column {
                    // SectionTextField is built for settings screens: it draws
                    // NO border and uses horizontal contentPadding = 0, because
                    // there its parent (SettingsCardBlock) supplies both the
                    // 16dp inset and the card surface that bounds it. A dialog
                    // has neither, so used bare the glyphs sat flush against
                    // the fill and the two fields read as one block. Wrap each
                    // one the way a settings card would, plus a hairline border
                    // so the input edge is visible on the dialog's own surface.
                    DialogTextFieldFrame {
                        SectionTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = stringResource(R.string.group_name_hint),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    DialogTextFieldFrame {
                        SectionTextField(
                            value = desc,
                            onValueChange = { desc = it.take(FolderEntity.DESC_MAX_CHARS) },
                            placeholder = stringResource(R.string.group_desc_hint),
                        )
                    }
                }
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    viewModel.renameFolder(folder.id, name, desc)
                    folderToRename = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { folderToRename = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    folderToDissolve?.let { folder ->
        val count = folderMemberCounts[folder.id] ?: 0
        MinisAlertDialog(
            onDismissRequest = { folderToDissolve = null },
            title = stringResource(R.string.group_dissolve_confirm_title),
            // Spells out that nothing is deleted — dissolve is deliberately NOT
            // styled destructive, because it touches no user data.
            text = stringResource(R.string.group_dissolve_confirm_message, count),
            confirmText = stringResource(R.string.group_dissolve),
            onConfirm = {
                viewModel.dissolveFolder(folder.id)
                folderToDissolve = null
            },
        )
    }

    // iOS "Delete Group & N Sessions" confirmation — the one destructive
    // folder action, so isDestructive here where dissolve deliberately isn't.
    folderToDelete?.let { (folder, count) ->
        MinisAlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = stringResource(R.string.group_delete_confirm_title),
            text = stringResource(R.string.group_delete_confirm_message, count),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                viewModel.deleteFolderWithSessions(folder.id)
                folderToDelete = null
            },
        )
    }

    // Edit Title & Category sheet (matching iOS SessionEditSheet)
    editSession?.let { session ->
        // Track the live DB-backed row for this session so a Regenerate-Title
        // run (which writes title/category to the DB) flows back into the sheet
        // without the user reopening it. displayedSessions observes the DB.
        val liveSession = sessions.firstOrNull { it.id == session.id } ?: session
        SessionEditSheet(
            session = session,
            liveSession = liveSession,
            isRegenerating = session.id in regeneratingIds,
            onRegenerate = { viewModel.regenerateTitle(session.id) },
            onDismiss = { editSession = null },
            onSave = { title, category ->
                viewModel.updateTitleAndCategory(session.id, title, category)
                editSession = null
            },
        )
    }

    // Browser sheet
    if (showBrowserSheet) {
        com.openminis.app.ui.browser.BrowserSheet(
            tabPool = browserTabPool,
            onDismiss = { showBrowserSheet = false },
        )
    }

    // Browser Settings sheet
    if (showBrowserSettings) {
        com.openminis.app.ui.browser.BrowserSettingsSheet(
            tabPool = browserTabPool,
            onDismiss = { showBrowserSettings = false },
        )
    }
}

// ─── Dual FAB Row (matching iOS fabRow) ─────────────────────────────────────

/** Persisted preference key for FAB order swap. */
private const val PREF_FAB_SWAPPED = "fab_swapped"

@Composable
private fun DualFabRow(
    isDark: Boolean,
    isSearchActive: Boolean,
    searchQuery: String,
    isSearching: Boolean,
    hasSessions: Boolean,
    onNewChat: () -> Unit,
    onNewChatWithGroup: (String) -> Unit,
    modelGroups: List<com.openminis.app.data.model.ModelGroup>,
    onSearchToggle: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE) }
    var isSwapped by remember { mutableStateOf(prefs.getBoolean(PREF_FAB_SWAPPED, false)) }

    // T120: focus + IME control for the inline search field. The field appears
    // inside an AnimatedVisibility, so we drive focus from the parent and
    // request it when isSearchActive flips true. Showing the keyboard
    // explicitly via the SoftwareKeyboardController covers devices where
    // requestFocus() alone doesn't trigger the IME (e.g. some Pixel + Gboard
    // combinations under edge-to-edge layouts).
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            // AnimatedVisibility runs a 200ms enter animation; the TextField
            // isn't attached to the composition tree until the first frame of
            // that animation lands. Yield once so requestFocus() targets a
            // composed node rather than throwing IllegalStateException.
            kotlinx.coroutines.delay(50)
            runCatching { searchFocusRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    // Drag offset for the currently-dragged FAB
    var chatDragX by remember { mutableFloatStateOf(0f) }
    var searchDragX by remember { mutableFloatStateOf(0f) }

    // Threshold to trigger swap (half screen width roughly)
    val density = LocalDensity.current
    val swapThreshold = with(density) { 100.dp.toPx() }

    var showGroupMenu by remember { mutableStateOf(false) }
    val topGroups = remember(modelGroups) { modelGroups.take(10) }

    val chatFab: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .offset { IntOffset(chatDragX.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (kotlin.math.abs(chatDragX) > swapThreshold) {
                                isSwapped = !isSwapped
                                prefs.edit().putBoolean(PREF_FAB_SWAPPED, isSwapped).apply()
                            }
                            chatDragX = 0f
                        },
                        onDragCancel = { chatDragX = 0f },
                        onHorizontalDrag = { _, dragAmount -> chatDragX += dragAmount },
                    )
                },
        ) {
            FloatingActionButton(
                onClick = onNewChat,
                shape = CircleShape,
                containerColor = minisFabColor(),
                modifier = Modifier
                    .size(56.dp)
                    // [T-android-fab-square-ripple] Clip BEFORE combinedClickable.
                    // FloatingActionButton's own `shape = CircleShape` only bounds
                    // the ripple it draws internally; this extra clickable layer
                    // (added for the long-press group menu) is a separate
                    // interaction source and draws its own indication, which
                    // without a clip spreads to the square 56dp bounds and shows
                    // as a grey box behind the round button. Same ordering as the
                    // circular voice button in ChatComposerWidgets.
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = onNewChat,
                        onLongClick = {
                            if (topGroups.isNotEmpty()) showGroupMenu = true
                        },
                    )
                    .shadow(8.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.2f)),
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
            ) {
                Icon(Icons.Outlined.Forum, contentDescription = "New Chat", tint = Color.White, modifier = Modifier.size(24.dp))
            }
            DropdownMenu(
                expanded = showGroupMenu,
                onDismissRequest = { showGroupMenu = false },
            ) {
                topGroups.forEach { group ->
                    DropdownMenuItem(
                        text = { Text(group.name) },
                        leadingIcon = { Icon(Icons.Outlined.Forum, contentDescription = null) },
                        onClick = {
                            showGroupMenu = false
                            onNewChatWithGroup(group.id)
                        },
                    )
                }
            }
        }
    }

    val searchFab: @Composable () -> Unit = {
        if (hasSessions) {
            AnimatedVisibility(
                visible = !isSearchActive,
                enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.85f),
                exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.85f),
            ) {
                FloatingActionButton(
                    onClick = onSearchToggle,
                    shape = CircleShape,
                    // iOS: UIColor.secondarySystemBackground = #F2F2F7 (light) / #1C1C1E (dark).
                    // ChatColors.secondaryBg already matches these values across themes.
                    containerColor = ChatColors.secondaryBg,
                    modifier = Modifier
                        .size(56.dp)
                        .offset { IntOffset(searchDragX.roundToInt(), 0) }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (kotlin.math.abs(searchDragX) > swapThreshold) {
                                        isSwapped = !isSwapped
                                        prefs.edit().putBoolean(PREF_FAB_SWAPPED, isSwapped).apply()
                                    }
                                    searchDragX = 0f
                                },
                                onDragCancel = { searchDragX = 0f },
                                onHorizontalDrag = { _, dragAmount -> searchDragX += dragAmount },
                            )
                        }
                        .shadow(6.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.15f)),
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.sessionlist_search_action), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                }
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            // T24: lift the FAB+search row above the IME so the text field
            // remains visible while typing. Compose-managed inset — handles
            // the IME open/close animation in lockstep.
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Render in swapped or normal order
        if (isSwapped) { searchFab(); } else { chatFab() }

        // Middle: Inline search bar (when active)
        AnimatedVisibility(
            visible = isSearchActive,
            enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.85f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.85f),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_chats_placeholder)) },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    // T46: while debounce is in flight, swap the close icon
                    // for an indeterminate progress ring so the user sees the
                    // search is working — avoids the stale-results-then-snap
                    // transition on slow stores. Snaps back to the close
                    // button as soon as results land.
                    if (isSearching) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = onSearchDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.sessionlist_dismiss), modifier = Modifier.size(18.dp))
                        }
                    }
                },
                // T46: full-capsule shape mirrors iOS searchable-field style
                // (see ContentView.fabRow — `.clipShape(Capsule())` over a
                // 56pt-tall HStack). RoundedCornerShape(50) is Compose's
                // canonical "pill" radius — guaranteed circular ends at any
                // height. Pair with a fixed 48dp height so the field aligns
                // with the flanking 56dp FABs without overpowering them.
                shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
                // T10: Material3's default OutlinedTextField containerColor is
                // Color.Transparent, which lets the LazyColumn's session rows
                // bleed through and overlap the typed query text. Set both
                // focused and unfocused container colors to surfaceContainerHigh
                // (matches the grouped-section card background already used
                // throughout settings) so the field reads as a discrete
                // surface above the list. Also drop both border colors —
                // capsule shape with no outline reads more like iOS's filled
                // search bar than the M3 outlined field default.
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    // [T-android-search-height] Left at the component's own
                    // height. Forcing 42dp here clipped the placeholder: a
                    // plain OutlinedTextField keeps its 16dp vertical
                    // contentPadding no matter what the outer frame says, so
                    // shrinking the frame cuts the text. The model picker's
                    // field was rebuilt on BasicTextField + DecorationBox to
                    // get around that; this one is a simpler inline field and
                    // is not worth the same surgery for a few dp.
                    .heightIn(min = 48.dp)
                    .focusRequester(searchFocusRequester),
            )
        }

        if (isSwapped) { chatFab() } else { searchFab() }
    }
}

// ─── Selection Toolbar (matching iOS selectionToolbar) ──────────────────────

@Composable
private fun SelectionToolbar(
    selectedCount: Int,
    onExport: () -> Unit,
    /** [T-android-session-grouping] Bulk-file the selection into a group. */
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // Export button (matching iOS)
        MinisTextButton(
            onClick = onExport,
            enabled = selectedCount > 0,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = stringResource(R.string.sessionlist_export),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.sessionlist_export), fontSize = 11.sp)
            }
        }

        // Move to Group button
        MinisTextButton(
            onClick = onMove,
            enabled = selectedCount > 0,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = stringResource(R.string.group_move_action),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.group_move_action), fontSize = 11.sp)
            }
        }

        // Delete button (matching iOS)
        MinisTextButton(
            onClick = onDelete,
            enabled = selectedCount > 0,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = if (selectedCount > 0) MaterialTheme.colorScheme.error else Color.Gray,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.delete),
                    fontSize = 11.sp,
                    color = if (selectedCount > 0) MaterialTheme.colorScheme.error else Color.Gray,
                )
            }
        }
    }
}

// ─── Section Header (matching iOS .subheadline.weight(.semibold)) ───────────

@Composable
private fun SectionHeader(title: String) {
    // T172: title may now be a localized string, so compare against the
    // localized "Pinned" rather than the hardcoded enum label.
    val isPinned = title == stringResource(R.string.sessionlist_section_pinned)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(top = 4.dp),
    ) {
        if (isPinned) {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(14.dp)
                    .padding(end = 0.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─── Session Item (context menu replaces swipe-to-delete, matching iOS) ─────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionItemContent(
    session: ChatSessionEntity,
    isSelecting: Boolean,
    selectedIds: Set<String>,
    onSessionClick: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    // [T-android-sessionlist-longpress-select] Context-menu Select: enters
    // selection mode with this row selected (distinct from onToggleSelect,
    // which only flips set membership while ALREADY selecting).
    onEnterSelect: (String) -> Unit,
    onPinToggle: (String) -> Unit,
    onEditRequest: (ChatSessionEntity) -> Unit,
    onExportRequest: (ChatSessionEntity, String) -> Unit,
    onRegenerateTitle: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onDeleteRequest: (String) -> Unit,
    /** [T-android-session-grouping] Opens the group picker for this session. */
    onMoveToGroup: (String) -> Unit,
    /**
     * [T-android-session-grouping] True only when this session belongs to a group
     * that ACTUALLY EXISTS locally — not merely `folderId != null`.
     *
     * A dangling folder_id renders as ungrouped (see partitionByFolder), so
     * deciding the wording from the raw id alone made the row and its menu
     * disagree: the session sat in the date buckets while its menu offered
     * "更换分组". The caller resolves membership the same way the list does.
     */
    isFiled: Boolean,
    isRegenerating: Boolean = false,
    searchQuery: String = "",
    searchSnippet: String? = null,
    /**
     * [T-android-folder-card-ios-parity] Overrides the row's own surface
     * background. Folder members pass Transparent so the group container's
     * welded fill shows through; null keeps the default surface.
     */
    rowBackground: Color? = null,
    /**
     * [T-android-split-selection-shape] True when this row sits inside a folder
     * container. Kept separate from [rowBackground] because that colour now
     * also carries the two-pane selection tint — see SessionRow.
     */
    isFolderMember: Boolean = false,
) {
    if (isSelecting) {
        val isSelected = session.id in selectedIds
        SessionRow(
            session = session,
            onClick = { onToggleSelect(session.id) },
            onLongClick = null,
            searchQuery = searchQuery,
            searchSnippet = searchSnippet,
            rowBackground = rowBackground,
            isFolderMember = isFolderMember,
            leadingIcon = {
                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            },
        )
    } else {
        var showContextMenu by remember { mutableStateOf(false) }
        var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
        // [T-android-menu-press-side] Which HALF of the row the finger was on.
        // Pressing on the right used to left-anchor the menu at the finger,
        // overflow the window, and get clamped left — so the popup (and its
        // top-LEFT-origin scale animation) visually appeared to the left of
        // the finger. Right-half presses now anchor the menu's RIGHT edge at
        // the press point with a matching top-right animation origin, so the
        // menu hangs off the finger naturally on both sides.
        var menuAlignEnd by remember { mutableStateOf(false) }
        var rowWidthPx by remember { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        val isPinned = session.pinnedAt != null

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowWidthPx = it.width.toFloat() },
        ) {
            SessionRow(
                session = session,
                onClick = { onSessionClick(session.id) },
                searchQuery = searchQuery,
                searchSnippet = searchSnippet,
                rowBackground = rowBackground,
                isFolderMember = isFolderMember,
                onLongClick = { offsetPx ->
                    pressOffset = with(density) {
                        DpOffset(offsetPx.x.toDp(), offsetPx.y.toDp())
                    }
                    menuAlignEnd = rowWidthPx > 0f && offsetPx.x > rowWidthPx / 2f
                    showContextMenu = true
                },
            )
            // Loading overlay when regenerating title
            if (isRegenerating) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            stringResource(R.string.sessionlist_regenerating_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            // Invisible zero-size anchor at the press position — DropdownMenu
            // will open from here so it follows the touch point.
            Box(
                modifier = Modifier
                    .offset(x = pressOffset.x, y = pressOffset.y)
                    .size(1.dp),
            ) {
                MinisMenu(
                    expanded = showContextMenu,
                    onDismissRequest = { showContextMenu = false },
                    alignEnd = menuAlignEnd,
                ) {
                // Pin / Unpin
                DropdownMenuItem(
                    text = { Text(stringResource(if (isPinned) R.string.sessionlist_unpin else R.string.sessionlist_pin)) },
                    onClick = {
                        showContextMenu = false
                        onPinToggle(session.id)
                    },
                    leadingIcon = {
                        Icon(
                            if (isPinned) Icons.Default.Close else Icons.Default.PushPin,
                            contentDescription = null,
                        )
                    },
                )
                // Export submenu (JSON / Plain Text)
                var showExportSub by remember { mutableStateOf(false) }
                DropdownMenuItem(
                    text = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.sessionlist_export))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    },
                    onClick = { showExportSub = !showExportSub },
                    leadingIcon = {
                        Icon(Icons.Default.Share, contentDescription = null)
                    },
                )
                if (showExportSub) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessionlist_export_json), modifier = Modifier.padding(start = 24.dp)) },
                        onClick = {
                            showContextMenu = false
                            onExportRequest(session, "json")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessionlist_export_plain), modifier = Modifier.padding(start = 24.dp)) },
                        onClick = {
                            showContextMenu = false
                            onExportRequest(session, "text")
                        },
                    )
                }
                // Edit Title & Category
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_edit_title_category)) },
                    onClick = {
                        showContextMenu = false
                        onEditRequest(session)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Edit, contentDescription = null)
                    },
                )
                // Regenerate Title
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_regenerate_title)) },
                    onClick = {
                        showContextMenu = false
                        onRegenerateTitle(session.id)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    },
                )
                // Duplicate
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_duplicate)) },
                    onClick = {
                        showContextMenu = false
                        onDuplicate(session.id)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                    },
                )
                // Move to / Change Group
                // [T-android-session-grouping] The wording follows membership:
                // a session already in a group is being MOVED BETWEEN groups,
                // not filed for the first time. Same idiom as Pin/Unpin.
                //
                // A single item opening a sheet, deliberately NOT an inline
                // submenu of group names — the menu body would then cost
                // O(groups) to compose on every open, and the group data would
                // have to be captured into the menu closure.
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (isFiled) R.string.group_change
                                else R.string.group_move_to,
                            ),
                        )
                    },
                    onClick = {
                        showContextMenu = false
                        onMoveToGroup(session.id)
                    },
                    leadingIcon = {
                        Icon(
                            if (isFiled) Icons.Default.DriveFileMove
                            else Icons.Default.Folder,
                            contentDescription = null,
                        )
                    },
                )
                // Select
                // [T-android-sessionlist-longpress-select] Must ENTER
                // selection mode, not just toggle the hidden set —
                // onToggleSelect alone never set isSelecting, so nothing
                // visibly happened and the id sat invisibly pre-selected.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_select_action)) },
                    onClick = {
                        showContextMenu = false
                        onEnterSelect(session.id)
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.ChecklistRtl, contentDescription = null)
                    },
                )
                MinisMenuDivider()
                // Delete
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showContextMenu = false
                        onDeleteRequest(session.id)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                )
                }
            }
        }
    }
}

/**
 * [T-android-session-grouping] The card that heads a group's block.
 *
 * Tapping it collapses/expands (accordion — opening one closes the others).
 * Long-pressing opens the management menu: pin, rename, dissolve. Dissolve is
 * deliberately NOT tinted destructive — it moves sessions back to the main list
 * and deletes nothing, and tinting it red would train the eye to read it as the
 * dangerous item.
 */
@OptIn(ExperimentalFoundationApi::class)
// ─── [T-android-folder-card-ios-parity] Folder container surface ────────────
//
// Port of iOS FolderSurface + FolderSegmentBorder (ContentView.swift): the
// folder group renders as ONE floating rounded container. Collapsed = a lone
// 16dp-radius card; expanded = the card becomes the TOP segment and each
// member row a MIDDLE/BOTTOM segment of the same surface, with the hairline
// border tiled around the outer perimeter only — no horizontal lines at row
// boundaries, so the pieces read as a single welded outline. Rows stay
// independent LazyColumn items (the iOS "never merge rows into one view"
// rule); only the background/border segmentation composes them.

private enum class FolderSegment { LONE, TOP, MIDDLE, BOTTOM }

/**
 * Edge highlight for the folder container: bright hairline in dark mode, a
 * subtle dark line in light mode (white would vanish on the light page) —
 * iOS `folderEdgeHighlight` verbatim.
 */
@Composable
private fun folderEdgeColor(): Color =
    if (ChatColors.isDark) Color.White.copy(alpha = 0.30f)
    else Color.Black.copy(alpha = 0.08f)

@Composable
private fun folderFillColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow

private fun Modifier.folderSurface(
    segment: FolderSegment,
    fill: Color,
    edge: Color,
): Modifier = drawBehind {
    val r = 16.dp.toPx()
    val w = size.width
    val h = size.height
    val cr = CornerRadius(r, r)

    val fillPath = Path().apply {
        when (segment) {
            FolderSegment.LONE -> addRoundRect(RoundRect(0f, 0f, w, h, cr))
            FolderSegment.TOP -> addRoundRect(
                RoundRect(
                    rect = Rect(0f, 0f, w, h),
                    topLeft = cr, topRight = cr,
                    bottomLeft = CornerRadius.Zero, bottomRight = CornerRadius.Zero,
                ),
            )
            FolderSegment.MIDDLE -> addRect(Rect(0f, 0f, w, h))
            FolderSegment.BOTTOM -> addRoundRect(
                RoundRect(
                    rect = Rect(0f, 0f, w, h),
                    topLeft = CornerRadius.Zero, topRight = CornerRadius.Zero,
                    bottomLeft = cr, bottomRight = cr,
                ),
            )
        }
    }
    drawPath(fillPath, fill)

    // Border tiling (iOS FolderSegmentBorder): lone = full outline; top =
    // left edge up + top arcs + right edge down; middle = the two vertical
    // edges only; bottom = the mirror of top. Open paths — never a line
    // across a row boundary.
    val border = Path().apply {
        when (segment) {
            FolderSegment.LONE -> addRoundRect(RoundRect(0f, 0f, w, h, cr))
            FolderSegment.TOP -> {
                moveTo(0f, h)
                lineTo(0f, r)
                arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
                lineTo(w - r, 0f)
                arcTo(Rect(w - 2 * r, 0f, w, 2 * r), 270f, 90f, false)
                lineTo(w, h)
            }
            FolderSegment.MIDDLE -> {
                moveTo(0f, 0f); lineTo(0f, h)
                moveTo(w, 0f); lineTo(w, h)
            }
            FolderSegment.BOTTOM -> {
                moveTo(0f, 0f)
                lineTo(0f, h - r)
                arcTo(Rect(0f, h - 2 * r, 2 * r, h), 180f, -90f, false)
                lineTo(w - r, h)
                arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 90f, -90f, false)
                lineTo(w, 0f)
            }
        }
    }
    drawPath(border, edge, style = Stroke(width = 0.75.dp.toPx()))
}

/**
 * Port of iOS GroupGlyphShape: the "grouped list" glyph — two rounded-square
 * rings on the left, four list lines on the right — traced from the same
 * 1024-unit SVG. Rings are even-odd so the whole glyph is a single fill.
 */
private fun groupGlyphPath(side: Float): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    val u = side / 1024f

    fun ring(x: Float, y: Float) {
        // Outer 325.8×325.8 with r 93; inner inset by the 46.5 stroke.
        val outer = Rect(x * u, y * u, (x + 325.8f) * u, (y + 325.8f) * u)
        addRoundRect(RoundRect(outer, CornerRadius(93f * u)))
        val inner = Rect(
            outer.left + 46.5f * u, outer.top + 46.5f * u,
            outer.right - 46.5f * u, outer.bottom - 46.5f * u,
        )
        addRoundRect(RoundRect(inner, CornerRadius(46.5f * u)))
    }

    fun line(cy: Float) {
        val rect = Rect(
            558.5f * u, (cy - 23.27f) * u,
            (558.5f + 325.8f) * u, (cy + 23.27f) * u,
        )
        addRoundRect(RoundRect(rect, CornerRadius(23.27f * u)))
    }

    ring(139.6f, 139.6f)
    ring(139.6f, 511.9f)
    line(209.5f)
    line(395.6f)
    line(581.8f)
    line(768.0f)
}

/**
 * Port of iOS FolderComposedIcon: the grouped-list glyph on the SAME circular
 * translucent tint the session rows use, at the same 44dp slot — a group icon
 * and a session icon are the same species at the same size. Tint borrows the
 * newest member's category color (gray when empty); 0.28 vs the session
 * icons' 0.18 so a group circle reads as a different kind of thing.
 */
@Composable
private fun FolderComposedIcon(category: String?, diameter: Dp = 44.dp) {
    val tint = categoryStyle(category).color
    Box(
        modifier = Modifier
            .size(diameter)
            .background(tint.copy(alpha = 0.28f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter * 0.56f)) {
            drawPath(groupGlyphPath(size.width), tint)
        }
    }
}

@Composable
private fun FolderCard(
    block: FolderGroupBlock,
    onToggle: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDissolve: () -> Unit,
    /** iOS "New Chat in Group": start a chat that files into this folder. */
    onNewChatInGroup: () -> Unit,
    /** iOS "Delete Group & N Sessions": destructive, folder + all members. */
    onDeleteWithSessions: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // [T-android-menu-press-side] Anchor the long-press menu at the FINGER,
    // not the card. combinedClickable gave no press coordinates, so the menu
    // anchored to the whole card Box and always opened at the card's LEFT
    // edge — pressing the right side popped the menu on the left (captured
    // on-device: left-press and right-press produced pixel-identical menu
    // positions). Same press-point + half-side rule as SessionItemContent.
    var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
    var menuAlignEnd by remember { mutableStateOf(false) }
    val headerPressInteractions = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    val expandLabel = stringResource(
        if (block.isCollapsed) R.string.group_expand else R.string.group_collapse,
    )
    // Expanded-with-members: the card is the container's TOP segment and
    // welds onto the first member row (no bottom gap). Collapsed or empty:
    // a lone floating card. Mirrors iOS FolderCardBackground.
    val isExpandedWithRows = !block.isCollapsed && block.ids.isNotEmpty()
    val chevronRotation by animateFloatAsState(
        targetValue = if (block.isCollapsed) -90f else 0f,
        animationSpec = tween(250),
        label = "folderChevron",
    )
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dateText = remember(block.latestUpdatedAt, ctx) {
        relativeDate(ctx, block.latestUpdatedAt)
    }
    val headerHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        modifier = Modifier
            // 6dp outer inset floats the rounded card inside the list width
            // (iOS: the inset frame is what separates it from the full-bleed
            // session rows at a glance). 6 outer + 10 inner = 16 — the folder
            // icon sits exactly on the session rows' alignment grid.
            .padding(
                start = 6.dp, end = 6.dp, top = 4.dp,
                bottom = if (isExpandedWithRows) 0.dp else 4.dp,
            )
            .folderSurface(
                segment = if (isExpandedWithRows) FolderSegment.TOP else FolderSegment.LONE,
                fill = folderFillColor(),
                edge = folderEdgeColor(),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Clip BEFORE the clickable so the press ripple takes the
                // card's own rounded shape — an unclipped ripple paints a
                // square highlight over the rounded surface. Expanded: only
                // the top corners are round (the card is the container's TOP
                // segment), so the ripple must stay square at the weld.
                .clip(
                    if (isExpandedWithRows) {
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    } else {
                        RoundedCornerShape(16.dp)
                    },
                )
                // detectTapGestures instead of combinedClickable so the
                // long-press OFFSET is available for menu anchoring; the
                // ripple is driven by hand through the InteractionSource
                // (same pattern as SessionRow's press indication).
                .indication(headerPressInteractions, LocalIndication.current)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { offset ->
                            val press = PressInteraction.Press(offset)
                            headerPressInteractions.emit(press)
                            val released = tryAwaitRelease()
                            headerPressInteractions.emit(
                                if (released) PressInteraction.Release(press)
                                else PressInteraction.Cancel(press),
                            )
                        },
                        onTap = { onToggle() },
                        onLongPress = { offset ->
                            // detectTapGestures gives no haptic of its own —
                            // see the SessionRow note; fired by hand so the
                            // group header matches every other long-press menu.
                            headerHaptics.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                            )
                            pressOffset = with(density) {
                                DpOffset(offset.x.toDp(), offset.y.toDp())
                            }
                            menuAlignEnd = offset.x > size.width / 2f
                            menuOpen = true
                        },
                    )
                }
                .semantics(mergeDescendants = true) {
                    onClick(label = expandLabel) { onToggle(); true }
                }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // [T-android-group-pause-badge-restamp] Collapsed-only paused
            // badge, in the same bottom-end corner and the same visual
            // language as the session rows' SessionBadgeOverlay — the symbol
            // the user already knows, in the position they know it. Expanded
            // groups show their members' own row badges instead, so a header
            // copy would leave the user guessing which row it refers to.
            // `anyPaused` already carries the 24h freshness filter; rows are
            // deliberately unfiltered.
            Box(modifier = Modifier.size(44.dp)) {
                FolderComposedIcon(category = block.firstCategory)
                // [T-android-group-running-ring] Collapsed-only, same as the
                // paused badge below and for the same reason: an expanded
                // group's members carry their own rings, so a header copy
                // would leave the user guessing which row it refers to.
                if (block.isCollapsed && block.anyActive) {
                    SpinningRing(
                        color = categoryStyle(block.firstCategory).color,
                        modifier = Modifier
                            .size(42.dp)
                            .align(Alignment.Center),
                    )
                }
                if (block.isCollapsed && block.anyPaused) {
                    SessionBadgeOverlay(
                        state = com.openminis.app.service.SessionBadgeStore
                            .SessionBadgeState.PAUSED,
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier.weight(1f),
                // [T-android-group-header-spacing] Match the session row's
                // title→lastMessage gap (SessionRow uses spacedBy(1.dp)). The
                // group name→summary line is the same 16sp-SemiBold → 14sp-
                // onSurfaceVariant pattern, so a wider 4dp gap here made the
                // group header look inconsistently loose next to plain rows.
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                // User data — rendered verbatim, never a string lookup.
                Text(
                    block.folder.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // totalCount, not ids.size — a collapsed group renders no
                    // rows but must still report its real membership. iOS
                    // summary line: "N chats · <newest member title>".
                    when {
                        block.totalCount > 0 && block.summaryTitle != null ->
                            stringResource(R.string.group_n_chats, block.totalCount) +
                                " · " + block.summaryTitle
                        block.totalCount > 0 ->
                            stringResource(R.string.group_n_chats, block.totalCount)
                        else -> stringResource(R.string.group_empty)
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    dateText,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (block.folder.isPinned) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(chevronRotation),
                    )
                }
            }
        }
        // Invisible zero-size anchor at the press position — the menu opens
        // from the finger, right-edge-anchored when the press was on the
        // card's right half (see menuAlignEnd above).
        Box(
            modifier = Modifier
                .offset(x = pressOffset.x, y = pressOffset.y)
                .size(1.dp),
        ) {
            MinisMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                alignEnd = menuAlignEnd,
            ) {
                // [T-android-folder-menu-icons] One icon FAMILY and one frame
                // for every item: Outlined variants in a 20dp box. The old mix
                // (filled PushPin / filled Edit / filled FolderOff at default
                // 24dp) had three different visual weights and optical sizes
                // in a four-item menu.
                val menuIcon: @Composable (androidx.compose.ui.graphics.vector.ImageVector) -> Unit =
                    { image ->
                        Icon(
                            image,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (block.folder.isPinned) R.string.sessionlist_unpin
                                else R.string.sessionlist_pin,
                            ),
                        )
                    },
                    onClick = { menuOpen = false; onTogglePin() },
                    leadingIcon = { menuIcon(Icons.Outlined.PushPin) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_rename)) },
                    onClick = { menuOpen = false; onRename() },
                    leadingIcon = { menuIcon(Icons.Outlined.Edit) },
                )
                // iOS folder menu parity: "New Chat in Group" (plus.bubble)
                // sits between Rename and the divider.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_new_chat_in)) },
                    onClick = { menuOpen = false; onNewChatInGroup() },
                    leadingIcon = { menuIcon(Icons.Outlined.AddComment) },
                )
                MinisMenuDivider()
                // Dissolve is deliberately NOT destructive-tinted (iOS note):
                // it touches no user data — sessions move back to the main
                // list. Tinting it red would train the eye to read it as the
                // deleting item.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_dissolve)) },
                    onClick = { menuOpen = false; onDissolve() },
                    leadingIcon = { menuIcon(Icons.Outlined.FolderOff) },
                )
                MinisMenuDivider()
                // The one destructive item, last, with the count in the title
                // so the consequence is visible in the menu itself, not only
                // in the confirmation dialog (iOS parity).
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                R.string.group_delete_with_sessions, block.totalCount,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = { menuOpen = false; onDeleteWithSessions() },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
            }
        }
    }
}

// ─── Session Row (matching iOS SessionRow) ──────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: ChatSessionEntity,
    onClick: () -> Unit,
    onLongClick: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    searchQuery: String = "",
    searchSnippet: String? = null,
    /** See SessionItemContent — Transparent inside a folder container. */
    rowBackground: Color? = null,
    /**
     * [T-android-split-selection-shape] True when this row is a member of a
     * folder container. Passed explicitly rather than inferred from
     * `rowBackground != null`, which conflated "folder member" with "selected"
     * once the two-pane list started tinting the selected row.
     */
    isFolderMember: Boolean = false,
) {
    val style = remember(session.category) { categoryStyle(session.category) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val timeText = remember(session.updatedAt, ctx) { relativeDate(ctx, session.updatedAt) }
    val rowHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    // [T-android-sessionrow-press-indication] The long-press path uses raw
    // detectTapGestures (it needs the press OFFSET to anchor the context
    // menu), which — unlike clickable — carries no indication, so rows gave
    // zero visual feedback on tap/long-press. Drive the standard ripple by
    // hand: emit Press/Release/Cancel into an InteractionSource from
    // onPress, and mount it with Modifier.indication.
    //
    // Highlight SHAPE mirrors the folder card (user request): ungrouped rows
    // clip the indication to the same 6dp-inset, 16dp-radius rounded rect the
    // group card uses — 6dp outside the clip + 10dp inside keeps the total
    // 16dp content lead, so nothing moves. Folder members skip this: their
    // wrapper Box already clips to the welded container's segment shape
    // (square middles / bottom-rounded last), and a rounded ripple mid-weld
    // would break the one-container illusion.
    val pressInteractions = remember { MutableInteractionSource() }
    // [T-android-split-selection-shape] `inFolder` must be derived from the
    // FOLDER flag, not from "rowBackground is non-null".
    //
    // rowBackground carries two unrelated meanings: Transparent for a folder
    // member (so the welded container shows through) and, since the two-pane
    // list gained a selected row, a primary tint for the selection. The old
    // `rowBackground != null` test could not tell them apart, so a SELECTED
    // ungrouped row was treated as a folder member: it skipped the 6dp inset
    // and the 16dp clip, and the tint was painted edge-to-edge as a square
    // band. That is the reported symptom — the highlight looked taller than it
    // was wide-inset, with hard corners.
    val inFolder = isFolderMember
    // [T-android-split-selection-shape] A SELECTED folder member gets the same
    // rounded-pill treatment as a selected ungrouped row, just sized to sit
    // inside the welded container.
    //
    // Members deliberately skip the outer inset/clip (their wrapper already
    // clips them to the container's segment shape — square middles, rounded
    // last row — and a rounded ripple mid-weld would break the one-container
    // illusion). But that also meant a selected member's tint was painted
    // edge-to-edge with hard corners, so selection looked different depending
    // on whether the chat happened to be in a group.
    //
    // The tint alone is inset and rounded, leaving the container's own fill
    // and borders untouched: 4dp (not 6) because the container already
    // contributes its 6dp, and 12dp radius (not 16) so the pill nests inside
    // the card's corner rather than fighting it.
    val selectionTint = rowBackground?.takeIf { inFolder && it != Color.Transparent }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!inFolder) {
                    Modifier
                        .padding(horizontal = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                } else {
                    Modifier
                }
            )
            // Background AFTER the inset+clip, not before: drawing it first
            // filled the full row width and squared off the corners, so the
            // clip only ever shaped the ripple. Now the tint is the rounded
            // rect itself, matching the folder card's shape and leaving equal
            // 6dp gutters left and right.
            .background(
                if (selectionTint != null) Color.Transparent
                else rowBackground ?: MaterialTheme.colorScheme.surface,
            )
            // [T-android-selection-no-reflow] DRAWN, not padded.
            //
            // The first cut expressed the inset as
            // `.padding(4.dp, 2.dp).clip(...).background(...)`, which is a
            // LAYOUT modifier: it shrank the content box, so selecting a row
            // visibly nudged its icon and text. The row must not move at all —
            // only the tint's own rectangle should inset.
            //
            // `drawBehind` paints inside the row's existing bounds and
            // occupies no space, so the geometry of a selected row is
            // byte-identical to an unselected one. The 4dp/2dp inset and 12dp
            // radius now describe the painted rect alone.
            .then(
                if (selectionTint != null) {
                    Modifier.drawBehind {
                        val insetX = 4.dp.toPx()
                        val insetY = 2.dp.toPx()
                        drawRoundRect(
                            color = selectionTint,
                            topLeft = Offset(insetX, insetY),
                            size = Size(
                                size.width - insetX * 2,
                                size.height - insetY * 2,
                            ),
                            cornerRadius = CornerRadius(12.dp.toPx()),
                        )
                    }
                } else {
                    Modifier
                }
            )
            .then(
                if (onLongClick != null) {
                    Modifier
                        // [T-android-sessionrow-press-shape] Clip the press
                        // ripple to the SAME rounded rect the selection tint
                        // paints, for a folder member.
                        //
                        // Ungrouped rows already got this from the
                        // `.clip(RoundedCornerShape(16.dp))` above, but folder
                        // members deliberately skip that clip (their wrapper
                        // shapes the welded container), so their ripple was
                        // drawn square and edge-to-edge. Under the rounded
                        // selection tint that read as two stacked highlights
                        // with mismatched corners — the reported symptom.
                        //
                        // Radius matches the tint's 12dp so the two agree; the
                        // ungrouped 16dp would not, since the container already
                        // contributes its own 6dp.
                        //
                        // [T-android-selection-no-reflow] Shape only — NO
                        // padding. The tint's 4dp/2dp inset cannot be reproduced
                        // here with `.padding`, because padding is a LAYOUT
                        // modifier: it would shrink the row's content box and
                        // shift every folder member's icon and text, which is
                        // the reflow this row is twice documented as avoiding.
                        // `clip` alone occupies no space, so the ripple is
                        // rounded to the row's full bounds. The 4dp difference
                        // between the ripple's edge and the tint's is not
                        // visible — a ripple is a transient wash, not an edge —
                        // whereas the square corners were.
                        .then(
                            if (inFolder) {
                                Modifier.clip(RoundedCornerShape(12.dp))
                            } else {
                                Modifier
                            },
                        )
                        .indication(pressInteractions, LocalIndication.current)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = { offset ->
                                    val press = PressInteraction.Press(offset)
                                    pressInteractions.emit(press)
                                    val released = tryAwaitRelease()
                                    pressInteractions.emit(
                                        if (released) PressInteraction.Release(press)
                                        else PressInteraction.Cancel(press),
                                    )
                                },
                                onTap = {
                                    com.openminis.app.diagnostics.PerfLongCtx.click(session.id)
                                    onClick()
                                },
                                onLongPress = { offset ->
                                    // Same reason the ripple is driven by hand
                                    // above: raw detectTapGestures carries no
                                    // built-in feedback, so the haptic that
                                    // `combinedClickable` gives for free has to
                                    // be fired explicitly here.
                                    rowHaptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                    )
                                    onLongClick(offset)
                                },
                            )
                        }
                } else {
                    Modifier.clickable {
                        com.openminis.app.diagnostics.PerfLongCtx.click(session.id)
                        onClick()
                    }
                }
            )
            .padding(
                // 10dp in BOTH branches. Ungrouped: 6dp highlight-clip inset
                // + 10 = 16dp lead. In-folder: the wrapper Box already adds
                // the container's 6dp inset, so 16dp here pushed member icons
                // to 22dp — 6dp right of the folder card's own icon (6 outer
                // + 10 inner = 16). 10dp restores one shared 16dp icon grid
                // for the card, its members, and ungrouped rows alike.
                horizontal = 10.dp,
                // [T-android-selection-no-reflow] Back to the original 12dp.
                //
                // This was briefly 16dp and then 10dp while chasing "even
                // padding on the selection pill". Both were the wrong lever:
                // this padding sets the ROW's height, so changing it resized
                // every row in the list — selected or not — to fix how one
                // tinted rectangle looked. Row metrics are left exactly as they
                // were; the pill's proportions are handled by the inset of the
                // drawn tint instead (see the drawBehind above).
                vertical = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingIcon != null) {
            leadingIcon()
        }

        // Category icon in colored circle (18% opacity matching iOS)
        val activeSessions by SessionActivityTracker.activeSessions.collectAsState()
        val isActive = session.id in activeSessions
        // [T-android-session-paused-badge] Head of this session's badge queue
        // — null for the common case. Renders as an overlay in the icon's
        // bottom-right corner, mirroring where iOS's iCloud badge sits so
        // future ICLOUD_SYNCING uses the same anchor.
        val badgeMap by com.openminis.app.service.SessionBadgeStore.byId.collectAsState()
        val badgeHead = badgeMap[session.id]?.firstOrNull()
        Box(
            modifier = Modifier.size(44.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = style.color.copy(alpha = 0.18f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.color,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .align(Alignment.Center),
                    contentAlignment = Alignment.Center,
                ) {
                    SpinningRing(
                        color = style.color,
                        modifier = Modifier.size(42.dp),
                    )
                }
            }
            if (badgeHead != null) {
                SessionBadgeOverlay(
                    state = badgeHead,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }

        // Title + last message (or highlighted snippet during search)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            val titleText = session.title ?: "New Chat"
            if (searchQuery.isNotBlank()) {
                Text(
                    text = highlightedAnnotatedString(titleText, searchQuery),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = titleText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // During an active search, prefer the matched message snippet
            // (content hit) over the generic lastMessage preview. Falls back
            // to lastMessage when match was title-only (snippet is null).
            if (searchQuery.isNotBlank() && searchSnippet != null) {
                Text(
                    text = highlightedAnnotatedString(searchSnippet, searchQuery),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = session.lastMessage ?: "No messages yet",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Relative timestamp
        Text(
            text = timeText,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * Card frame for a [SectionTextField] used inside a dialog.
 *
 * The settings screens get this for free from `SettingsCardBlock`: it supplies
 * the 16dp horizontal inset that SectionTextField deliberately omits (its
 * contentPadding is horizontal = 0 so glyphs align with sibling section rows —
 * T352) and the card surface that gives the input an edge. A dialog has no such
 * parent, so a bare SectionTextField renders as text jammed against its fill
 * with no visible boundary.
 *
 * Reuses the same tokens as the settings cards — [SectionDesign.CardShape] and
 * `cardColor()` — so a dialog input reads as the same control as the one on a
 * settings screen, plus a hairline outline: the dialog's surface sits close in
 * luminance to the card fill, and without the outline the field edge is
 * effectively invisible in dark mode.
 */
@Composable
private fun DialogTextFieldFrame(content: @Composable () -> Unit) {
    Surface(
        shape = SectionDesign.CardShape,
        color = SectionDesign.cardColor(),
        // Full-strength outlineVariant, not a faded one: the dialog's surface
        // and the card fill are close in luminance (both are surfaceContainer
        // shades), so anything dimmer than this reads as no border at all in
        // dark mode — verified on device.
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp)) { content() }
    }
}

/**
 * Spinning arc overlaid on the session icon while the agent loop is active.
 * Mirrors iOS `SpinningRing` (ContentView.swift:2405): 1.5dp stroke at 30%
 * opacity, 30% arc length, full rotation every ~1 second. Uses
 * `withFrameNanos` instead of an `animate*` API so recomposition across
 * onAppear calls does not stack multiple rotation animations.
 */
@Composable
private fun SpinningRing(
    color: Color,
    modifier: Modifier = Modifier,
) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val startNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val elapsedSec = (now - startNanos) / 1_000_000_000f
                angle = (elapsedSec * 360f) % 360f
            }
        }
    }
    Canvas(modifier = modifier.rotate(angle)) {
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        drawArc(
            color = color.copy(alpha = 0.8f),
            startAngle = 0f,
            sweepAngle = 360f * 0.3f,
            useCenter = false,
            size = Size(size.width, size.height),
            style = stroke,
        )
    }
}

/**
 * [T-android-session-paused-badge] Corner overlay for [SessionBadgeStore]
 * states. Anchored bottom-end inside the 44dp icon Box. Mirrors where the
 * iOS iCloud-sync badge sits so future ICLOUD_SYNCING uses the same anchor.
 *
 * Sizing: 14dp circle, ~2/3 the size of the 20dp category icon — visible
 * but doesn't overwhelm the icon glyph. Translated 2dp down/right so the
 * badge sits *on* the icon edge instead of flush with the row padding
 * (matches the visual weight of iOS's badge offset).
 */
@Composable
private fun SessionBadgeOverlay(
    state: com.openminis.app.service.SessionBadgeStore.SessionBadgeState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED -> {
            Box(
                modifier = modifier
                    .offset(x = 2.dp, y = 2.dp)
                    .size(14.dp)
                    .background(
                        // Solid system-orange. Picked over yellow so the
                        // alert reads as "attention" rather than "info".
                        color = Color(0xFFFF9500),
                        shape = CircleShape,
                    )
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.surface,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // Pause glyph (⏸) — mirrors iOS's "pause.fill" badge so the
                // cross-platform "this task was paused" affordance matches.
                Icon(
                    imageVector = Icons.Filled.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(9.dp),
                )
            }
        }
        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.ICLOUD_SYNCING -> {
            // Reserved for the upcoming iCloud-equivalent sync surface;
            // not produced yet. Render nothing rather than a placeholder
            // so a stray persisted entry from a future build doesn't
            // surface a debug-looking icon on the current build.
        }
    }
}

// ─── Onboarding Landing (iOS-style 3-step setup) ───────────────────────────

@Composable
private fun OnboardingLanding(
    hasProviders: Boolean,
    hasGroups: Boolean,
    onAddProvider: () -> Unit,
    onSelectModels: () -> Unit,
    onStartConversation: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Bottom padding ≈ top-bar height so the content visually centers
            // relative to the whole screen, not just the Scaffold inner area.
            .padding(horizontal = 32.dp)
            .padding(bottom = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.sessionlist_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.sessionlist_welcome_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SetupStepCard(
                number = 1,
                title = stringResource(R.string.sessionlist_welcome_step1_title),
                subtitle = if (hasProviders) {
                    stringResource(R.string.sessionlist_welcome_step_done)
                } else {
                    stringResource(R.string.sessionlist_welcome_step1_subtitle)
                },
                isDone = hasProviders,
                isLocked = false,
                onClick = { if (!hasProviders) onAddProvider() },
            )

            SetupStepCard(
                number = 2,
                title = stringResource(R.string.sessionlist_welcome_step2_title),
                subtitle = when {
                    hasGroups -> stringResource(R.string.sessionlist_welcome_step_done)
                    hasProviders -> stringResource(R.string.sessionlist_welcome_step2_subtitle)
                    else -> stringResource(R.string.sessionlist_welcome_step2_locked)
                },
                isDone = hasGroups,
                isLocked = !hasProviders,
                onClick = { if (hasProviders && !hasGroups) onSelectModels() },
            )

            SetupStepCard(
                number = 3,
                title = stringResource(R.string.sessionlist_welcome_step3_title),
                subtitle = if (hasGroups) {
                    stringResource(R.string.sessionlist_welcome_step3_subtitle)
                } else {
                    stringResource(R.string.sessionlist_welcome_step3_locked)
                },
                isDone = false,
                isLocked = !hasGroups,
                onClick = { if (hasGroups) onStartConversation() },
            )
        }
    }
}

@Composable
private fun SetupStepCard(
    number: Int,
    title: String,
    subtitle: String,
    isDone: Boolean,
    isLocked: Boolean,
    onClick: () -> Unit,
) {
    val isEnabled = !isDone && !isLocked

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = isEnabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    color = if (isDone) Color(0xFF34C759) else MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isDone) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    text = "$number",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f).height(56.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!isDone && isEnabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

// ─── Edit Title & Category Sheet (matching iOS SessionEditSheet) ──────────

private val allCategories = listOf(
    "Code", "Writing", "Research", "Analysis",
    "Creative", "Chat", "Math", "Translation",
    "Health", "Finance", "Travel", "Education",
    "Design", "Productivity", "Support", "Other",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionEditSheet(
    session: ChatSessionEntity,
    onDismiss: () -> Unit,
    onSave: (title: String, category: String?) -> Unit,
    // [T-android-sessionedit-regenerate-button] Regenerate-Title support,
    // matching iOS SessionEditSheet. `liveSession` is the DB-backed row that
    // updates when regeneration writes a new title/category; `isRegenerating`
    // drives the button's loading/disabled state; `onRegenerate` reuses the
    // existing SessionListViewModel.regenerateTitle logic. Defaults make the
    // button a no-op when a caller doesn't wire them up.
    liveSession: ChatSessionEntity = session,
    isRegenerating: Boolean = false,
    onRegenerate: () -> Unit = {},
) {
    var title by remember { mutableStateOf(session.title ?: "") }
    var selectedCategory by remember { mutableStateOf(session.category) }

    // [T-android-sessionedit-regenerate-button] When a regeneration run writes a
    // new title/category to the DB, `liveSession` updates — mirror those values
    // into the sheet's local edit state so the Title field and Category grid
    // refresh in place (iOS reads the fresh ChatStore session on completion).
    LaunchedEffect(liveSession.title, liveSession.category) {
        liveSession.title?.let { title = it }
        selectedCategory = liveSession.category
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
        ) {
            // Title bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MinisTextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                Text(
                    "Edit Session",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                MinisTextButton(
                    onClick = { onSave(title.ifBlank { "New Chat" }, selectedCategory) },
                ) { Text("Save") }
            }

            Spacer(Modifier.height(16.dp))

            // Title field
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(20.dp))

            Text(
                "Category",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Category grid (4 columns, matching iOS LazyVGrid)
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(240.dp),
            ) {
                items(allCategories) { cat ->
                    val isSelected = selectedCategory?.equals(cat, ignoreCase = true) == true
                    val style = categoryStyle(cat.lowercase())
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) style.color.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable {
                                selectedCategory = if (isSelected) null else cat.lowercase()
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = style.icon,
                                contentDescription = null,
                                tint = style.color,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                cat,
                                fontSize = 11.sp,
                                color = if (isSelected) style.color
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // [T-android-sessionedit-regenerate-button] Regenerate Title —
            // matches iOS SessionEditSheet's dedicated section below Category.
            // Reuses SessionListViewModel.regenerateTitle; shows a spinner and
            // disables while running (regeneratingIds) to prevent double taps.
            MinisOutlinedButton(
                onClick = onRegenerate,
                enabled = !isRegenerating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isRegenerating) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sessionlist_regenerating_title))
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sessionlist_regenerate_title))
                }
            }
        }
    }
}

// ─── Export Session ────────────────────────────────────────────────────────

/**
 * Long-chat export (T-export-optimize b443b54d, iOS sister c9d1087d).
 *
 * Pre-fix: this loaded every [MessageEntity] for the session at once,
 * built the whole JSON / TXT payload in memory, and shoved it into
 * [Intent.EXTRA_TEXT]. Hundreds of messages caused jank, "ghost" frames
 * and OOM crashes — see linked feedback.
 *
 * Now: hand off to [com.openminis.app.share.ChatExporter] which paginates
 * (50 rows / batch) on [kotlinx.coroutines.Dispatchers.IO], streams to a
 * staging file under `cacheDir/export-staging/`, then zips into
 * `cacheDir/shared/` and hands the resulting [android.net.Uri] to the
 * share sheet as a real file attachment. Peak memory stays bounded by
 * batch size regardless of session length.
 */
private fun exportSession(
    context: Context,
    session: ChatSessionEntity,
    chatRepository: ChatRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    format: String,
) {
    scope.launch {
        try {
            val (uri, _) = com.openminis.app.share.ChatExporter.exportToZip(
                context = context,
                session = session,
                repository = chatRepository,
                format = format,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_SUBJECT, session.title ?: "Conversation")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(
                intent,
                context.getString(R.string.sessionlist_export),
            ).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            context.startActivity(chooser)
        } catch (t: Throwable) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.export_progress_failed),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
}

