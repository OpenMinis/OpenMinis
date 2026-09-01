package com.openminis.app.ui.navigation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowSize
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.openminis.app.R
import java.util.UUID

/**
 * [T-android-tablet-split-resizable] Default list-pane share of the window,
 * used when the user has not dragged the divider yet.
 *
 * A RATIO rather than a fixed dp, because a fixed one does not travel: the
 * previous flat 340dp is 26% of a 1297dp Mate Pad but 41% of an 840dp window,
 * so the same constant read "too wide" on the small end and cramped on the
 * large end. Anchoring the default to the window keeps the visual balance the
 * same across tablet sizes.
 *
 * 28% is picked to land on iPad's own proportions. iOS asks for
 * `ideal: 380` (ContentView), which on Apple's landscape iPads works out to
 * 27.8% at 12.9" (1366dp) and 31.8% at 11" (1194dp) — so ~28-30% IS the iPad
 * default expressed as a ratio, and 28% puts a 1297dp tablet at 363dp, close
 * to iOS's 380 ideal while leaving the chat the clear majority.
 *
 * Still clamped to [LIST_PANE_MIN_WIDTH]/[LIST_PANE_MAX_WIDTH], which is what
 * keeps the ratio sane at the extremes: on a just-barely-two-pane 840dp window
 * 28% would be 235dp, below the readable floor, so the min takes over.
 */
private const val LIST_PANE_DEFAULT_FRACTION = 0.28f

private val LIST_PANE_WIDTH = 340.dp

/**
 * [T-android-empty-state-soul-identity] Upper bound for the empty-state Soul
 * icon. A custom image is capped at its own stored pixel size so it is never
 * enlarged past its real resolution; emoji and the sparkle use this directly.
 */
private val EMPTY_STATE_ICON_SIZE = 64.dp

/**
 * [T-android-tablet-split-resizable] Drag limits for the list pane, matching
 * iOS verbatim: ContentView's session list carries
 * `.navigationSplitViewColumnWidth(min: 340, ideal: 380, max: 500)`, so the
 * two platforms clamp to the same band and a tablet cannot be dragged into a
 * layout the iPad would refuse.
 *
 * The lower bound is what keeps this safe: below ~340dp the session row's
 * title and its trailing relative timestamp start competing for the same line
 * and the title truncates to a few characters, which is the failure mode a
 * free-form drag would otherwise let the user create permanently.
 *
 * iOS's `ideal` has no Compose counterpart — the framework picks a width only
 * when the container is ambiguous, whereas here the width is always an
 * explicit value (default or dragged), so only min/max carry over.
 */
private val LIST_PANE_MIN_WIDTH = 340.dp
private val LIST_PANE_MAX_WIDTH = 500.dp

/**
 * [T-android-tablet-split-resizable] Hit area for the divider drag.
 *
 * The seam itself is 1dp — far under the ~48dp Material touch-target minimum —
 * so the grab region is widened without changing what is painted.
 *
 * The band is centred on the seam, which requires the handle to be an overlay
 * on the scaffold rather than a child of either pane — AnimatedPane clips, so
 * an in-pane handle can never reach left of the line, and that asymmetry made
 * the divider draggable in one direction only.
 */
private val DIVIDER_TOUCH_WIDTH = 24.dp

/**
 * [T-android-new-chat-shortcut] One-shot request to focus the session search,
 * raised by the Ctrl/⌘+F shortcut and consumed by [SessionListScreen].
 *
 * A counter rather than a boolean: pressing the shortcut again while the search
 * is already open must still re-focus the field, and a boolean that is already
 * `true` produces no change for the consumer to observe. Incrementing always
 * does.
 *
 * Object-level rather than threaded through the pane lambdas — those signatures
 * are shared by the single-pane path and two nesting layers, and widening them
 * for a UI event that has exactly one producer and one consumer would be a lot
 * of plumbing for no added clarity.
 */
object SessionSearchRequest {
    val requests = MutableStateFlow(0)
    fun focusSearch() { requests.value += 1 }
}

/** Where the user's chosen pane width is persisted. Shared with other view-level UI state. */
private const val UI_PREFS = "ui_prefs"
private const val KEY_LIST_PANE_WIDTH = "tablet_list_pane_width_dp"
private const val KEY_LIST_COLLAPSED = "tablet_list_pane_collapsed"

/**
 * [T-android-tablet-split] True when the window should show list and detail
 * side by side.
 *
 * Read from [currentWindowAdaptiveInfo], NOT `LocalConfiguration.screenWidthDp`.
 * The latter measures the physical display, so in split-screen, freeform or
 * one half of a folded device it reports a width the app does not actually
 * have — the app would draw two panes into a phone-width window. The codebase
 * has other `screenWidthDp` reads for unrelated purposes; they are deliberately
 * not the model here.
 *
 * The height clause is not redundant with the width one: a tablet in
 * split-screen can become a short, wide letterbox that satisfies EXPANDED width
 * while leaving too little vertical room for two usable columns.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun shouldUseTwoPane(): Boolean {
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    // Breakpoint predicates rather than the WindowWidthSizeClass/
    // WindowHeightSizeClass enums: those are deprecated ("will not be developed
    // further"), and the predicate form states the rule directly — width is at
    // least EXPANDED (840dp), height is at least MEDIUM (480dp, i.e. NOT
    // compact) — instead of comparing against enum constants.
    val expandedWidth = windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
    )
    val nonCompactHeight = windowSizeClass.isHeightAtLeastBreakpoint(
        WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND,
    )
    return expandedWidth && nonCompactHeight
}

/**
 * [T-android-tablet-split] The scaffold directive that ENFORCES
 * [shouldUseTwoPane].
 *
 * This exists because the first cut of this screen did not work. It computed
 * `shouldUseTwoPane()` and then used the result only for the list pane's width
 * and the row highlight — it never reached the scaffold, so pane COUNT was
 * still decided by the library default, which splits from MEDIUM width
 * (>=600dp) upward and ignores height entirely. Measured on a Pixel 4a with
 * `wm size`: 1745x440dp (EXPANDED width, COMPACT height) rendered two panes,
 * and so did 820dp and even 550dp. Real phone geometry (393dp) happened to
 * come out single-pane, which is why the static checks passed and the bug
 * survived review.
 *
 * The fix takes the library's own computed directive and overrides exactly one
 * field. `maxHorizontalPartitions = 1` is what collapses the layout to a
 * single pane; everything else (spacer sizes, preferred widths, hinge-derived
 * excluded bounds) is left as the library computed it, so folding-device
 * behaviour and Material spacing are not silently re-invented here.
 *
 * The directive is passed to `rememberListDetailPaneScaffoldNavigator`, whose
 * first parameter it is; the navigator hands it to the scaffold. Overriding it
 * at the navigator is what makes the scaffold's own adapt logic agree with the
 * value used for the width and highlight, rather than having two sources of
 * truth that can disagree — which is precisely what the bug was.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun chatPaneScaffoldDirective(twoPane: Boolean, listPaneWidth: Dp): PaneScaffoldDirective {
    val base = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())
        // [T-android-tablet-split-gutters] Drop the framework's default pane
        // margins. They are added OUTSIDE the width we set on the list pane,
        // so a declared 340dp pane measured 384dp on a Mate Pad with the whole
        // 44dp difference landing on the right — the list card sat 6.5dp from
        // the screen edge and 30.7dp from the divider. Zeroing them makes the
        // declared width the real width and lets the panes own their padding.
        .copy(
            horizontalPartitionSpacerSize = 0.dp,
            defaultPanePreferredWidth = listPaneWidth,
        )
    return if (twoPane) base else base.copy(maxHorizontalPartitions = 1)
}

/**
 * [T-android-tablet-split] Hosts the session list and the chat detail in a
 * [NavigableListDetailPaneScaffold].
 *
 * Why this component exists: SESSION_LIST and CHAT used to be two sibling
 * `composable` destinations in the outer NavHost, so a chat was always a
 * full-screen push over the list — a phone idiom that wastes a tablet.
 * Wrapping the pair in one scaffold lets the framework decide between
 * side-by-side and pushed, and collapses the two routes into a single
 * destination whose internal state is the pane navigator's.
 *
 * Selection is a `rememberSaveable` session id here, mirrored into the pane
 * navigator's `currentDestination.contentKey` — not a route argument. It is
 * hoisted rather than read straight back out of the navigator because the
 * navigator is rebuilt when the pane count changes (see the body); holding it
 * one level up is what carries the user's chat across that rebuild, and across
 * configuration changes.
 *
 * NAVIGATION BOUNDARY — deliberate and load-bearing:
 * every other destination (Settings, Terminal, file preview, all secondary
 * setting pages) stays on the OUTER NavHost as a full-screen push, and every
 * ChatScreen callback that leaves the chat ([onOpenTerminal],
 * [onBrowseChatFiles], [onPreviewAttachment], …) is still routed through
 * `navController.safeNavigate`. Sinking those into the detail pane would nest
 * a second navigation stack inside the pane navigator, whose known failure
 * mode is back-key confusion when the window changes size class mid-stack
 * (entries skipped, or back looping). One stack owns cross-screen navigation;
 * the pane navigator owns only list-vs-detail.
 *
 * @param initialSessionId a chat to open immediately (deep link / share
 *   handoff), or null to start on the empty state.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ChatSplitScaffold(
    initialSessionId: String?,
    listPane: @Composable (
        selectedSessionId: String?,
        /**
         * [T-android-draft-placeholder-row] Draft id the detail pane is
         * showing that has NOT been persisted yet, or null. The list renders a
         * synthetic "New Chat / No messages yet" row for it so the pane always
         * has a matching, selected row. Mirrors iOS `displaySessions`, which
         * prepends the same placeholder in split mode.
         *
         * Purely a view construct — the row is never written to the database.
         * A session is created only by `ChatViewModel.ensureSession()` on the
         * first send, so abandoning an untouched draft leaves nothing behind.
         */
        draftPlaceholderId: String?,
        onSessionSelected: (String) -> Unit,
    ) -> Unit,
    detailPane: @Composable (
        sessionId: String,
        onBack: () -> Unit,
        onNewChatInPane: () -> Unit,
        onMoveToInPane: (String) -> Unit,
        /**
         * [T-android-tablet-sidebar-collapse] Show/hide the session list, or
         * null when there is no list to toggle (single-pane, where the same
         * slot carries the back arrow instead).
         */
        onToggleSidebar: (() -> Unit)?,
        /** Whether the list is currently hidden — picks the icon direction. */
        sidebarCollapsed: Boolean,
    ) -> Unit,
) {
    val twoPane = shouldUseTwoPane()

    // [T-android-tablet-split-resizable] User-chosen list-pane width, restored
    // from prefs and re-clamped on read: the stored value predates nothing
    // today, but a future change to the band (or a value written by a build
    // with different limits) must not resurrect an out-of-range pane.
    val context = LocalContext.current
    val uiPrefs = remember(context) {
        context.getSharedPreferences(UI_PREFS, android.content.Context.MODE_PRIVATE)
    }
    // The window's own width, so the default can be a proportion of it rather
    // than a fixed dp that only looks right on one tablet size.
    //
    // From windowAdaptiveInfo's bounds, NOT LocalConfiguration.screenWidthDp —
    // same reason shouldUseTwoPane avoids it: screenWidthDp measures the
    // display, so in split-screen or freeform the default would be a fraction
    // of a window the app does not have.
    val windowWidth = with(LocalDensity.current) { currentWindowSize().width.toDp() }
    var listPaneWidth by remember(windowWidth) {
        val default = (windowWidth * LIST_PANE_DEFAULT_FRACTION)
        mutableStateOf(
            uiPrefs.getFloat(KEY_LIST_PANE_WIDTH, default.value)
                .coerceIn(LIST_PANE_MIN_WIDTH.value, LIST_PANE_MAX_WIDTH.value)
                .dp,
        )
    }

    // Declared before the collapse below, which reads it on first composition.
    // See the long note at its former position further down for why the
    // selection is hoisted here at all.
    var selectedSessionId by rememberSaveable { mutableStateOf(initialSessionId) }

    // [T-android-tablet-sidebar-collapse] Whether the list pane is hidden.
    //
    // Persisted like the width: a user who collapsed the sidebar to give a long
    // chat the whole window should not find it back on the next launch.
    //
    // EXCEPT when the app opens on the empty placeholder. It has nothing to act
    // on, so restoring the collapse there would open the app on a blank screen
    // with no session list — the first thing the user sees would be an
    // interface with nothing in it. Landing expanded puts the sessions in front
    // of them instead, and the corrected value is written back so the stored
    // state and the UI never disagree.
    //
    // In practice that makes the collapse a WITHIN-SESSION state today: a plain
    // cold start always begins on the placeholder (`initialSessionId` is only
    // non-null for a deep link or share hand-off), so the sidebar is always
    // back on next launch. That is the intended behaviour, and the condition is
    // written against `initialSessionId` rather than hardcoded so it keeps
    // holding if the app later restores the last session on launch — then the
    // collapse would survive, and only a genuinely empty start would reset it.
    //
    // Evaluated ONCE, at first composition, not as a live
    // `collapsed && selected != null` guard: a guard would also spring the list
    // open every time the user closes a chat mid-session, overriding a choice
    // they had just made.
    var listCollapsed by remember {
        val stored = uiPrefs.getBoolean(KEY_LIST_COLLAPSED, false)
        val corrected = stored && initialSessionId != null
        if (stored != corrected) {
            uiPrefs.edit().putBoolean(KEY_LIST_COLLAPSED, corrected).apply()
        }
        mutableStateOf(corrected)
    }

    // Collapsing is modelled as "two-pane with a zero-width list", not as
    // dropping to single-pane. Single-pane would rebuild the navigator
    // (`key(directive.maxHorizontalPartitions)` below) and re-run the whole
    // detail pane, which throws away the chat's scroll position and remounts
    // its ViewModel — far too destructive for a UI toggle. Keeping the pane
    // count fixed and animating the width to zero leaves the detail pane
    // untouched.
    val effectiveListWidth by animateDpAsState(
        targetValue = if (listCollapsed) 0.dp else listPaneWidth,
        label = "listPaneWidth",
    )

    val directive = chatPaneScaffoldDirective(twoPane, effectiveListWidth)

    // [T-android-tablet-split] The selected session, held HERE rather than read
    // back out of the navigator.
    //
    // `rememberListDetailPaneScaffoldNavigator` captures `scaffoldDirective`
    // when it first builds the navigator and never re-reads the argument — it
    // is an initial value, not a live binding. Measured on a Pixel 4a: with the
    // directive supplied only at construction, a COLD START at 550dp correctly
    // showed one pane, but RESIZING a running window from 1745dp down to 550dp
    // stayed at two. That is the original bug one layer deeper — a correct
    // decision that never reaches the layout — and it defeats the whole point
    // of this screen, since windows change size at runtime.
    //
    // The supported fix is to rebuild the navigator when the directive changes
    // (`key(...)` below). DefaultThreePaneScaffoldNavigator does expose a
    // settable `scaffoldDirective`, but the class is `internal` and cannot be
    // referenced from here. Rebuilding discards the navigator's own history,
    // so the selection is hoisted into this state and fed back as the new
    // navigator's initial history — the user stays on the same chat across the
    // breakpoint, which is the behaviour being verified.

    key(directive.maxHorizontalPartitions) {
        val navigator = rememberListDetailPaneScaffoldNavigator<String>(
            scaffoldDirective = directive,
            // Seeded from the hoisted selection, so a navigator rebuilt by the
            // key() above resumes on the same chat instead of the empty state.
            // String is Parcelable-compatible via the default saver, which is
            // why the session id is used raw rather than wrapped in a data
            // class that would need its own Parcelize.
            initialDestinationHistory = buildList {
                add(
                    ThreePaneScaffoldDestinationItem(
                        ListDetailPaneScaffoldRole.List,
                        null,
                    ),
                )
                selectedSessionId?.let {
                    add(
                        ThreePaneScaffoldDestinationItem(
                            ListDetailPaneScaffoldRole.Detail,
                            it,
                        ),
                    )
                }
            },
        )
        val scope = rememberCoroutineScope()

        val currentSessionId = navigator.currentDestination
            ?.takeIf { it.pane == ListDetailPaneScaffoldRole.Detail }
            ?.contentKey

        // [T-android-new-chat-shortcut] Open a fresh draft in the detail pane.
        // Hoisted out of the detailPane call below so the keyboard shortcut and
        // the "New Chat" menu item cannot drift into doing different things.
        val openNewDraft: () -> Unit = {
            val draft = newDraftSessionId()
            selectedSessionId = draft
            scope.launch {
                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, draft)
            }
        }

    // [T-android-tablet-split-resizable] The scaffold is wrapped so the drag
    // handle can be OVERLAID on it (below) rather than living inside a pane.
    //
    // Both in-pane attempts failed for the same structural reason: AnimatedPane
    // clips, so a handle hosted in the detail pane can never extend to the LEFT
    // of the seam. Taking real layout width there painted a grey strip beside
    // the divider; overflowing rightward only fixed the strip but left the
    // handle one-directional — grabbing the seam and pulling left never started
    // a drag, which reads as the feature being gone. Both were measured on a
    // Mate Pad, not inferred.
    //
    // As a sibling overlay the handle is clipped by neither pane, so it can sit
    // centred on the seam and drag both ways, while occupying no layout width
    // at all — the panes still meet exactly at the boundary.
    // [T-android-new-chat-shortcut] Hardware-keyboard shortcuts, mirroring the
    // two iOS already carries (MinisApp.swift ⌘N, ContentView ⌘F).
    //
    // Handled HERE, at the root of the list/detail pair, for the same reason
    // iOS attaches ⌘F to the list's view tree: the shortcut must fire wherever
    // focus happens to be — either pane, or nothing — but must not fire on
    // unrelated full-screen destinations pushed over this scaffold (terminal,
    // settings), which have their own key handling and their own meaning for
    // these chords.
    //
    // `onPreviewKeyEvent`, not `onKeyEvent`: preview runs before focus-target
    // handlers, so the composer's existing Enter/Escape handling never gets to
    // swallow the chord while the text field has focus. The KeyDown filter
    // matters too — an unfiltered handler fires twice per press.
    //
    // Ctrl OR Meta: an Android tablet keyboard maps ⌘ to Meta, while a USB PC
    // keyboard sends Ctrl for the same user intent. Accepting both is what makes
    // one binding serve "the standard new/find chord" on either keyboard.
    // Deliberately NOT focusable itself: preview events travel down from the
    // focus owner's ancestors, so this sees the chord without competing for
    // focus with the composer's text field.
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (!event.isCtrlPressed && !event.isMetaPressed) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.N -> { openNewDraft(); true }
                    Key.F -> { SessionSearchRequest.focusSearch(); true }
                    else -> false
                }
            },
    ) {
    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane(
                modifier = if (twoPane) Modifier.width(effectiveListWidth) else Modifier,
            ) {
                listPane(
                    // Only reflect a selection when both panes are visible.
                    // In single-pane the list is never beside a chat, so a
                    // highlight would be a stale mark on a screen the user
                    // navigated back to.
                    //
                    // Resolved through the alias map so a draft that has since
                    // been persisted highlights its REAL row: the pane key
                    // stays `__new__…` for the life of the screen (see
                    // ChatViewModelStore.rename — aliasing rather than
                    // re-keying is what keeps the streaming ViewModel intact),
                    // and no persisted row would ever match that raw key.
                    // [T-android-split-draft-highlight] rememberPersistedId,
                    // not resolvePersistedId: the alias map is written when a
                    // draft is promoted on first send, and a plain lookup gives
                    // Compose nothing to observe, so the highlight stayed
                    // resolved against the dead `__new__` key.
                    if (twoPane && currentSessionId != null) {
                        com.openminis.app.ui.chat.ChatViewModelStore
                            .rememberPersistedId(currentSessionId)
                    } else null,
                    // [T-android-draft-placeholder-row] A draft the detail pane
                    // holds that has not been promoted yet. `rememberPersistedId`
                    // returns the id UNCHANGED while no alias exists, so a still
                    // `__new__…` result is exactly "not yet persisted".
                    if (twoPane && currentSessionId != null &&
                        isDraftSessionId(
                            com.openminis.app.ui.chat.ChatViewModelStore
                                .rememberPersistedId(currentSessionId),
                        )
                    ) currentSessionId else null,
                ) { sessionId ->
                    selectedSessionId = sessionId
                    scope.launch {
                        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, sessionId)
                    }
                }
            }
        },
        detailPane = {
            AnimatedPane {
                // [T-android-tablet-split-gutters] Divider on the detail pane's
                // leading edge. The two panes differ only by background colour
                // (#F2F2F7 vs #FFFFFF) and sat pixel-adjacent with no seam, so
                // in light theme the boundary was nearly invisible. SwiftUI's
                // NavigationSplitView draws its own separator; ListDetailPane-
                // Scaffold does not.
                val sessionId = navigator.currentDestination
                    ?.takeIf { it.pane == ListDetailPaneScaffoldRole.Detail }
                    ?.contentKey
                Row(Modifier.fillMaxSize()) {
                    // [T-android-tablet-sidebar-collapse] No seam to draw when
                    // the list is collapsed — the chat then owns the full width
                    // and a line at its leading edge would be a stray border.
                    if (twoPane && !listCollapsed) {
                        // 1dp, not Dp.Hairline: Hairline is 0dp and rounds
                        // to zero physical pixels, so the line never drew —
                        // verified by pixel-scanning the seam on a Mate Pad.
                        //
                        // Drawn here but DRAGGED from a sibling overlay (see
                        // the Box wrapping this scaffold): a handle hosted in
                        // this pane is clipped by AnimatedPane and could never
                        // extend left of the seam.
                        VerticalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    Box(Modifier.weight(1f)) {
                if (sessionId == null) {
                    // [T-android-tablet-sidebar-collapse] The placeholder gets
                    // the toggle too. It has no app bar of its own, so without
                    // this the control simply vanishes whenever no session is
                    // open — including after closing one with the list already
                    // collapsed, leaving a screen with no way to reach anything.
                    NoConversationSelected(
                        onToggleSidebar = if (twoPane) {
                            {
                                listCollapsed = !listCollapsed
                                uiPrefs.edit()
                                    .putBoolean(KEY_LIST_COLLAPSED, listCollapsed)
                                    .apply()
                            }
                        } else {
                            null
                        },
                        sidebarCollapsed = listCollapsed,
                    )
                } else {
                    detailPane(
                        sessionId,
                        {
                            selectedSessionId = null
                            scope.launch { navigator.navigateBack() }
                        },
                        // "New Chat" swaps the DETAIL pane to a fresh draft and
                        // leaves the list pane standing. Routing this through
                        // the outer NavHost instead would push a whole new
                        // screen over both panes on a tablet — the phone
                        // behaviour, in the one layout that exists to avoid it.
                        openNewDraft,
                        { targetId ->
                            selectedSessionId = targetId
                            scope.launch {
                                navigator.navigateTo(
                                    ListDetailPaneScaffoldRole.Detail,
                                    targetId,
                                )
                            }
                        },
                        // [T-android-tablet-sidebar-collapse] Only offered in
                        // two-pane: in single-pane this slot is the back arrow,
                        // and there is no second pane to collapse anyway.
                        if (twoPane) {
                            {
                                listCollapsed = !listCollapsed
                                uiPrefs.edit()
                                    .putBoolean(KEY_LIST_COLLAPSED, listCollapsed)
                                    .apply()
                            }
                        } else {
                            null
                        },
                        listCollapsed,
                    )
                }
                    }
                }
            }
        },
        )

        // [T-android-tablet-split-resizable] The drag handle, overlaid on the
        // scaffold and positioned at the seam.
        //
        // Sibling-not-child is the whole point: it is clipped by neither pane,
        // so the grab region straddles the divider and the drag works in BOTH
        // directions. It also occupies no layout width, so it cannot push the
        // chat sideways or paint a strip beside the line — the two failure
        // modes of the in-pane attempts.
        // [T-android-tablet-sidebar-collapse] Nothing to drag while
        // collapsed; the handle would otherwise sit at x=0 over the
        // chat's own leading edge.
        if (twoPane && !listCollapsed) {
            val density = LocalDensity.current
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    // Centre the touch band on the seam: the list pane's width
                    // IS the seam's offset, so back off by half the band.
                    .offset(x = effectiveListWidth - DIVIDER_TOUCH_WIDTH / 2)
                    .width(DIVIDER_TOUCH_WIDTH)
                    .pointerHoverIcon(
                        PointerIcon(
                            android.view.PointerIcon.getSystemIcon(
                                context,
                                android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW,
                            ),
                        ),
                    )
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { deltaPx ->
                            // Clamp per-frame rather than at drag end: an
                            // unclamped accumulator would keep integrating past
                            // the limit and the pane would not start moving
                            // again until the pointer came all the way back —
                            // the "sticky edge" feel.
                            listPaneWidth = (
                                listPaneWidth + with(density) { deltaPx.toDp() }
                                ).coerceIn(LIST_PANE_MIN_WIDTH, LIST_PANE_MAX_WIDTH)
                        },
                        // Persist once on release, not on every frame — a drag
                        // emits deltas at display rate and each commit is a
                        // disk write.
                        onDragStopped = {
                            uiPrefs.edit()
                                .putFloat(KEY_LIST_PANE_WIDTH, listPaneWidth.value)
                                .apply()
                        },
                    ),
            )
        }
    }
    }
}

/**
 * [T-android-tablet-split] Detail-pane placeholder, mirroring iOS
 * `ContentView.detailView`'s empty state ("No Conversation Selected" /
 * "Select a conversation or start a new one") in Material 3 styling.
 *
 * Only reachable in two-pane mode: in single-pane the detail pane is not
 * composed until a session has been chosen, so the user never sees it.
 */
@Composable
private fun NoConversationSelected(
    /** [T-android-tablet-sidebar-collapse] Show/hide the list; null in single-pane. */
    onToggleSidebar: (() -> Unit)? = null,
    sidebarCollapsed: Boolean = false,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        // The toggle floats over the centred content rather than sitting in a
        // Column above it, so it lands in the same top-left spot as the chat's
        // own — the control must not appear to move when a session opens.
        Box(Modifier.fillMaxSize()) {
        if (onToggleSidebar != null) {
            // Centred inside a band matching the SESSION LIST's TopAppBar (M3's
            // default 64dp), not offset by an eyeballed padding: that is what
            // puts this button on the same baseline as the toolbar across the
            // seam, so it does not appear to jump when a session opens. The
            // chat's Scaffold uses contentWindowInsets = WindowInsets(0), so
            // there is no status-bar inset to account for on either side.
            //
            // [T-android-split-toggle-align] This comment used to say 64dp was
            // "the chat's TopAppBar" height. It is not — the chat's bar is 68dp
            // (ChatScreen expandedHeight, sized for its 3-row title). 64dp was
            // right for the wrong reason, and the mistaken premise is why the
            // chat's own toggle went on sitting 2dp low against the list.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .height(64.dp)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
            IconButton(
                onClick = onToggleSidebar,
            ) {
                Icon(
                    // Same square glyph as the chat's own toggle — see the note
                    // at ChatScreen's navigationIcon for why not `List`.
                    Icons.Filled.Menu,
                    contentDescription = stringResource(
                        if (sidebarCollapsed) {
                            R.string.chat_show_sidebar
                        } else {
                            R.string.chat_hide_sidebar
                        },
                    ),
                    // [T-android-split-toggle-align] Matches the 28dp the chat's
                    // toggle now uses to compensate for Menu's sparse ink. This
                    // band is already 64dp — the list's bar height — so it needs
                    // no counterpart to the chat's -2dp offset; it was the chat
                    // that was off-baseline, not this.
                    modifier = Modifier.size(28.dp),
                )
            }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // [T-android-empty-state-soul-identity] The Soul's own icon and
            // name, not a generic speech bubble — this pane is the first thing
            // a cold start shows, and it should introduce the assistant the
            // user configured rather than the product.
            //
            // Renders through the SAME SoulIconGlyph the chat header and the
            // settings card use, so an emoji / custom image / unset-sparkle all
            // behave identically here (that shared-renderer rule is what keeps
            // image icons from silently failing on one surface).
            val soulMeta by com.openminis.app.agent.SoulStore.cachedMetadata.collectAsState()
            // Never scale a custom image ABOVE its stored resolution: icons are
            // saved at up to SoulIcon.STORED_PIXELS square and deliberately not
            // upscaled on save, so a 48px source blown up to a 64dp box would
            // look soft. Cap the box at the bitmap's real size (in dp) and let
            // a smaller icon simply render smaller and sharp. Emoji and the
            // sparkle have no such limit — they are vectors/text.
            val density = LocalDensity.current
            val iconBitmap = remember(soulMeta.icon) {
                com.openminis.app.agent.SoulIcon.decode(soulMeta.icon)
            }
            val iconSize = remember(iconBitmap, density) {
                if (iconBitmap == null) EMPTY_STATE_ICON_SIZE
                else with(density) {
                    minOf(EMPTY_STATE_ICON_SIZE, iconBitmap.width.toDp())
                }
            }
            com.openminis.app.ui.settings.SoulIconGlyph(
                icon = soulMeta.icon,
                sizeDp = iconSize,
                emojiSp = with(density) { EMPTY_STATE_ICON_SIZE.toSp() * 0.82f },
                sparkleTint = Brush.linearGradient(
                    colors = listOf(
                        com.openminis.app.ui.chat.SparkleColor1,
                        com.openminis.app.ui.chat.SparkleColor2,
                    ),
                ),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = soulMeta.name.ifBlank {
                    com.openminis.app.agent.SoulMetadata.DEFAULT.name
                },
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.split_no_conversation_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        }
    }
}

/**
 * [T-android-tablet-split] A fresh draft-session id.
 *
 * Reuses the existing `__new__<uuid>` convention rather than inventing a
 * parallel one: ChatScreen/ChatViewModel already branch on that prefix, and
 * ChatViewModelStore's alias map rewrites the key to the real id on first
 * send, so the SAME ViewModel survives the draft→persisted transition. In
 * two-pane mode that is what lets the detail pane keep streaming while the
 * list picks up the newly-created row.
 */
fun newDraftSessionId(): String = "__new__${UUID.randomUUID()}"

/**
 * [T-android-draft-placeholder-row] True for an id minted by
 * [newDraftSessionId] — i.e. a chat that exists only on screen and has no
 * database row yet.
 */
fun isDraftSessionId(id: String?): Boolean = id?.startsWith("__new__") == true

/**
 * [T-android-tablet-split] Route-level wiring for [ChatSplitScaffold].
 *
 * Both `Routes.SESSION_LIST` and `Routes.CHAT` render this; they differ only in
 * [initialSessionId]. Every callback that leaves the chat/list pair is routed
 * through [navController] exactly as it was before this change — see the
 * navigation-boundary note on [ChatSplitScaffold].
 */
@Composable
fun ChatSplitScaffoldRoute(
    initialSessionId: String?,
    navController: androidx.navigation.NavHostController,
    chatRepository: com.openminis.app.data.repository.ChatRepository,
    providerRepository: com.openminis.app.data.repository.ProviderRepository,
    memoryRepository: com.openminis.app.data.repository.MemoryRepository?,
    skillRepository: com.openminis.app.data.repository.SkillRepository?,
    mcpRepository: com.openminis.app.data.repository.MCPRepository?,
) {
    ChatSplitScaffold(
        initialSessionId = initialSessionId,
        listPane = { selectedSessionId, draftPlaceholderId, onSessionSelected ->
            com.openminis.app.ui.sessions.SessionListScreen(
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                // Selecting a session drives the PANE navigator, not the
                // NavHost: in two-pane mode a push would replace the whole
                // screen instead of swapping the detail column. In single-pane
                // the pane navigator performs the equivalent push itself, so
                // phone behaviour is unchanged.
                onSessionClick = onSessionSelected,
                onNewChat = onSessionSelected,
                onSettingsClick = { navController.safeNavigate(Routes.SETTINGS) },
                onAddProviderClick = { navController.safeNavigate(Routes.ADD_PROVIDER) },
                onSelectModelsClick = { navController.safeNavigate(Routes.ONBOARDING_MODELS) },
                onTerminalClick = { navController.safeNavigate(Routes.terminal()) },
                onRootfsClick = { navController.safeNavigate(Routes.ROOTFS_MANAGEMENT) },
                onScheduledTasksClick = { navController.safeNavigate(Routes.SCHEDULED_TASKS) },
                selectedSessionId = selectedSessionId,
                // [T-android-draft-placeholder-row] Synthetic "New Chat" row,
                // never persisted — see the listPane param docs.
                draftPlaceholderId = draftPlaceholderId,
            )
        },
        detailPane = { sessionId, onBackInPane, onNewChatInPane, onMoveToInPane,
            onToggleSidebar, sidebarCollapsed ->
            com.openminis.app.ui.chat.ChatScreen(
                // The pane navigator's contentKey IS the session id, so a
                // draft ("__new__…") flows through unchanged and ChatScreen's
                // existing draft handling applies untouched.
                sessionId = sessionId,
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                memoryRepository = memoryRepository,
                skillRepository = skillRepository,
                mcpRepository = mcpRepository,
                // Back inside the pair is the pane navigator's job (it knows
                // whether that means "hide the detail" or "pop to the list").
                onBack = onBackInPane,
                // [T-android-tablet-split] Suppress the detail pane's back
                // arrow while the list is beside it. shouldUseTwoPane() is the
                // same window-size predicate the scaffold itself uses, so the
                // two cannot disagree.
                isTwoPane = shouldUseTwoPane(),
                // [T-android-tablet-sidebar-collapse] Reuses the slot the back
                // arrow vacates in two-pane, which is where the user already
                // looks for "get me back to the list".
                onToggleSidebar = onToggleSidebar,
                sidebarCollapsed = sidebarCollapsed,
                // Draft handling: the pane navigator gets a fresh `__new__…`
                // key, so the list keeps its scroll position and simply stops
                // highlighting a row (no persisted id matches a draft) — the
                // two-pane equivalent of the old popUpTo(SESSION_LIST) push.
                onNewChat = onNewChatInPane,
                // Everything below leaves the list/detail pair entirely and so
                // stays on the OUTER NavHost as a full-screen push.
                onOpenTerminal = {
                    navController.safeNavigate(Routes.terminal(sessionId = sessionId))
                },
                onOpenTerminalWithCommand = { command ->
                    navController.safeNavigate(
                        Routes.terminal(initCommand = command, sessionId = sessionId),
                    )
                },
                // Move-to targets another chat, which is still inside the
                // list/detail pair — so it swaps the detail pane rather than
                // pushing. (The old popUpTo(SESSION_LIST) existed to avoid
                // stacking chats on the back stack; the pane navigator has no
                // such stack to pollute.) The target may be a `__new__…` draft
                // from the Move-to sheet's New Chat row, which works here for
                // the same reason onNewChat does.
                onMoveToSession = { targetId -> onMoveToInPane(targetId) },
                onBrowseChatFiles = { navController.safeNavigate(Routes.chatFiles(sessionId)) },
                onPreviewAttachment = { item ->
                    FilePreviewHolder.currentItem = item
                    navController.safeNavigate(Routes.FILE_PREVIEW)
                },
                onModelGroupsClick = { navController.safeNavigate(Routes.MODEL_GROUPS) },
            )
        },
    )
}
