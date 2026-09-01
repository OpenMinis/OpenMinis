package com.openminis.app.ui.chat

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.openminis.app.R

/**
 * Replaces the system Copy/Cut/Paste/SelectAll toolbar that a
 * [androidx.compose.foundation.text.selection.SelectionContainer] normally
 * invokes. Surfaces three actions, mirroring iOS `SelectableMarkdownTextView`:
 *
 *   - **Copy** — selected substring (delegates to `onCopyRequested`).
 *   - **Copy Markdown** — full raw markdown of the message that owns the
 *     selection. Resolved via the [MessageBoundsRegistry] lookup with the
 *     selection rect; if no message matches (cross-message selection or
 *     out-of-bounds rect) the action is hidden so we never copy the wrong
 *     content.
 *   - **Copy Rich Text** — same lookup, exported as HTML+plain dual ClipData.
 *
 * The toolbar is driven by a [mutableStateOf]-backed state holder; pair it
 * with [MinisMarkdownTextToolbarHost] which renders the floating popup. The
 * split exists because [TextToolbar] is a non-composable platform interface
 * but our UI must live inside the composition.
 */
internal class MinisMarkdownTextToolbar(
    private val context: Context,
    private val registry: MessageBoundsRegistry,
    /**
     * Invoked when the user taps **Add to Chat Input**. Receives the
     * currently-selected substring (resolved via a clipboard round-trip
     * through the Compose-supplied [onCopyRequested] callback — see
     * [addSelectionToInput]); the host is expected to append it to the
     * chat composer's input state. Null disables the action.
     */
    private val onAddToInput: ((String) -> Unit)? = null,
    /**
     * [T-android-selection-readaloud] Invoked when the user taps **Read Aloud**.
     * Receives the currently-selected substring (same clipboard round-trip as
     * [addSelectionToInput]); the host speaks it through ReadAloudPlayer. Null
     * disables the action. The callback owns the player's lifecycle — this
     * toolbar deliberately does not create one it could never dispose.
     */
    private val onReadAloud: ((String) -> Unit)? = null,
    /**
     * [T-android-readaloud-selection-vs-reply] Invoked when the user taps
     * **Read from Start**. Receives the owning message's markdown source (not
     * the selection); the host speaks it through the same reader. Null hides
     * the action — the caller passes null while the reply is still streaming,
     * matching iOS.
     */
    private val onReadFromStart: ((String) -> Unit)? = null,
    /**
     * [T-android-readaloud-selection-vs-reply] Whether a reply is streaming
     * right now. A lambda, not a Boolean: this toolbar is constructed once
     * inside `remember`, so a captured value would freeze at construction time
     * and the gate would answer for the wrong moment.
     */
    private val isStreamingNow: () -> Boolean = { false },
    /**
     * [T-android-markdown-table-copy-actions] The MinisTextKit selection
     * controller. This toolbar is the one Compose's SelectionContainer shows
     * (via LocalTextToolbar), and a table-cell long-press also sets the
     * MinisTextKit selection, so we read the table actions for the selected
     * message off the controller and append "Copy Table" / "Copy Table Image"
     * to THIS bar — keeping all actions in one row instead of a separate popup.
     */
    internal val selectionController: SelectionController? = null,
) : TextToolbar {

    internal var state by mutableStateOf(ToolbarState())
        private set

    internal val canAddToInput: Boolean get() = onAddToInput != null

    override val status: TextToolbarStatus
        get() = if (state.visible) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        state = ToolbarState(
            visible = true,
            rect = rect,
            onCopyRequested = onCopyRequested,
            originatingMarkdown = registry.markdownAt(rect),
            lastShownAtMs = android.os.SystemClock.uptimeMillis(),
        )
    }

    override fun hide() {
        state = state.copy(visible = false)
    }

    /**
     * Extract the selected substring and forward to [onAddToInput] so the host
     * can append it to the chat input. See [withSelection] for how the
     * selection is resolved. No-op when [onAddToInput] isn't wired.
     */
    internal fun addSelectionToInput() {
        val sink = onAddToInput ?: return
        withSelection(sink)
    }

    /**
     * [T-android-selection-readaloud] Speak just the selected substring (not the
     * whole message) through the host-supplied reader.
     */
    internal fun readAloudSelection() {
        val sink = onReadAloud ?: return
        withSelection(sink)
    }

    internal val canReadAloud: Boolean get() = onReadAloud != null

    /**
     * [T-android-readaloud-selection-vs-reply] Speak the WHOLE message that
     * owns the current selection, from its start — iOS's "Read from Start".
     *
     * Unlike [readAloudSelection] this needs no clipboard round-trip: the
     * message's markdown source is already resolved into
     * [ToolbarState.originatingMarkdown] when the bar is shown, and that is
     * exactly the text to read. It is also why the action can only be offered
     * when that field is non-null — a selection whose owning message we cannot
     * identify has no "whole reply" to restart.
     */
    internal fun readFromStart() {
        val sink = onReadFromStart ?: return
        val source = state.originatingMarkdown ?: return
        if (source.isNotBlank()) sink(source)
    }

    internal val canReadFromStart: Boolean
        get() = onReadFromStart != null &&
            state.originatingMarkdown != null &&
            // Suppressed mid-stream, matching iOS: restarting a reply that is
            // still arriving would narrate a text that keeps growing under the
            // narration. Read Selection stays available — a range the user
            // could select already exists.
            !isStreamingNow()

    /**
     * Resolve the current selection and hand it to [sink].
     *
     * Compose's [TextToolbar] interface only exposes the selection indirectly
     * via the supplied `onCopyRequested` callback — there is no public "give me
     * the AnnotatedString selection" API in `foundation` 1.7. We round-trip
     * through the system clipboard:
     *
     *   1. Snapshot the user's current primary clip (so we can restore it — we
     *      don't want a feature that silently overwrites what they had copied).
     *   2. Invoke `onCopyRequested` — Compose writes the selected substring to
     *      the clipboard.
     *   3. Read that text back as the selection.
     *   4. Restore the original clip.
     *   5. Forward the selection to [sink].
     *
     * Shared by [addSelectionToInput] and [readAloudSelection] so both actions
     * use the identical restore-the-user's-clipboard discipline. If anything
     * goes wrong (`onCopyRequested` absent, blank selection) this is a no-op —
     * it never throws and never leaves the clipboard in an unrelated state.
     */
    private fun withSelection(sink: (String) -> Unit) {
        val onCopy = state.onCopyRequested ?: return
        val cm = clipboard()
        val original = runCatching { cm.primaryClip }.getOrNull()
        try {
            onCopy.invoke()
            val selection = runCatching {
                cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            }.getOrNull()?.trim().orEmpty()
            if (selection.isNotEmpty()) sink(selection)
        } finally {
            // Restore the user's prior clipboard so the action stays
            // additive — only the chat input / speech changes, the clipboard
            // is exactly as the user left it.
            if (original != null) runCatching { cm.setPrimaryClip(original) }
        }
    }

    private fun clipboard(): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    internal fun copyMarkdown() {
        val md = state.originatingMarkdown ?: return
        MarkdownClipboard.copyMarkdown(context, md)
        toast(R.string.selection_copied_as_markdown_toast)
    }

    internal fun copyRichText() {
        val md = state.originatingMarkdown ?: return
        MarkdownClipboard.copyRichText(context, md)
        toast(R.string.selection_copied_as_rich_text_toast)
    }

    /**
     * [T-android-copy-full-plain-text] Copy the message as RENDERED plain
     * text: markdown markers resolved to what the user sees, but table cells
     * and math expressions still expanded — dropping those would lose content
     * rather than formatting. Mirrors iOS `1b4353367`.
     */
    internal fun copyPlainText() {
        val md = state.originatingMarkdown ?: return
        MarkdownClipboard.copyPlain(context, md)
        toast(R.string.selection_copied_as_plain_text_toast)
    }

    private fun toast(resId: Int) {
        Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
    }

    internal data class ToolbarState(
        val visible: Boolean = false,
        val rect: Rect = Rect.Zero,
        val onCopyRequested: (() -> Unit)? = null,
        /** Joined markdown of the message that owns [rect], or null if cross-message. */
        val originatingMarkdown: String? = null,
        /**
         * `SystemClock.uptimeMillis()` of the most recent [showMenu] call, or
         * 0 if the toolbar has never been shown. Updated only on `showMenu` —
         * [hide] preserves the previous value so consumers can distinguish a
         * mid-drag hide (recent timestamp) from a stale post-clear state
         * (timestamp many seconds old). Used by ChatScreen's selection
         * auto-scroll loop to bail when no fresh selection activity has been
         * observed for a long time, regardless of the sticky `selectionActive`
         * debounce.
         */
        val lastShownAtMs: Long = 0L,
    )
}

/**
 * Renders the floating toolbar controlled by [MinisMarkdownTextToolbar]. Place
 * exactly one instance under the same `CompositionLocalProvider` that supplies
 * the toolbar so it draws above the selected text.
 */
@Composable
internal fun MinisMarkdownTextToolbarHost(toolbar: MinisMarkdownTextToolbar) {
    val state = toolbar.state
    if (!state.visible) return
    val anchor = remember(state.rect) { state.rect.toIntRectRounded() }
    val positionProvider = remember(anchor) { FloatingToolbarPositionProvider(anchor) }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = { toolbar.hide() },
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        // [T-android-text-toolbar-dark-visibility] In dark mode the bare
        // `surface` colour is nearly the same as the chat background, so the
        // floating menu vanished into the content behind it. Use a distinctly
        // elevated surface (surfaceContainerHigh — a lighter grey in dark, a
        // subtle shade in light) plus a thin outline border and a stronger
        // shadow so the bar reads as a popped-out floating element.
        val barColor = MaterialTheme.colorScheme.surfaceContainerHigh
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = barColor,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            ),
        ) {
            // [T-android-copy-full-reply-scope] Make the action row
            // horizontally scrollable, matching the MinisTextKit selection bar.
            // Buttons never wrap (ToolbarButton is maxLines=1/softWrap=false to
            // avoid the truncation documented there), so without a scroll the
            // trailing actions simply fall off the screen edge and become
            // unreachable. With Copy + Add to Input + Read Aloud + three
            // full-message copies + two table actions this row reaches eight
            // items, and the "Copy Full …" labels are the longest yet.
            val toolbarScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(barColor)
                    .height(40.dp)
                    .horizontalScroll(toolbarScroll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarButton(label = stringResource(R.string.selection_copy)) {
                    state.onCopyRequested?.invoke()
                    toolbar.hide()
                }
                // "Add to Chat Input" — available for any selection so it
                // works for tool result output, user/assistant bubbles,
                // and cross-message selections alike. Hidden when the
                // host didn't wire an onAddToInput callback (e.g. a
                // future screen that uses the same toolbar but has no
                // composer to receive the text).
                if (toolbar.canAddToInput) {
                    ToolbarDivider()
                    ToolbarButton(
                        label = stringResource(R.string.selection_add_to_chat_input),
                    ) {
                        toolbar.addSelectionToInput()
                        toolbar.hide()
                    }
                }
                // [T-android-selection-readaloud] Speak just the selected
                // substring through Minis TTS (provider voice with the system
                // engine as fallback), mirroring iOS's "Read Aloud / Read
                // Selection" selection-menu action. Available for any
                // selection, like Add to Chat Input.
                if (toolbar.canReadAloud) {
                    ToolbarDivider()
                    ToolbarButton(
                        label = stringResource(R.string.selection_read_aloud),
                    ) {
                        toolbar.readAloudSelection()
                        toolbar.hide()
                    }
                }
                // [T-android-readaloud-selection-vs-reply] "Read from Start"
                // beside "Read Selection", mirroring iOS's pair. Flat here
                // rather than grouped: this bar is already a flat scrolling row
                // with no submenu machinery, and the two labels state their own
                // scope.
                if (toolbar.canReadFromStart) {
                    ToolbarDivider()
                    ToolbarButton(
                        label = stringResource(R.string.selection_read_from_start),
                    ) {
                        toolbar.readFromStart()
                        toolbar.hide()
                    }
                }
                // [T-android-copy-full-reply-scope] The full-message copies
                // only appear when the selection sits inside a single known
                // message — otherwise it's ambiguous which message's source to
                // copy.
                //
                // Every label says "Full", because this toolbar is raised BY a
                // selection and sits inches from plain "Copy": without the
                // word, a user who highlighted one sentence and tapped "Copy
                // Markdown" silently got the entire message, with nothing in
                // the UI explaining the gap. Naming the scope is the fix
                // itself (iOS a7b3e5bef).
                if (state.originatingMarkdown != null) {
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.selection_copy_full_markdown)) {
                        toolbar.copyMarkdown()
                        toolbar.hide()
                    }
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.selection_copy_full_rich_text)) {
                        toolbar.copyRichText()
                        toolbar.hide()
                    }
                    // Plain Text copies the RENDERED text — headings, lists and
                    // quotes as the user sees them, with no `#`/`-`/`**`/`>`
                    // left behind. Tables and math still expand: dropping those
                    // would lose content, not formatting (iOS 1b4353367).
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.selection_copy_full_plain_text)) {
                        toolbar.copyPlainText()
                        toolbar.hide()
                    }
                }
                // [T-android-markdown-table-copy-actions] When the selected
                // message contains a table, append Copy Table / Copy Table
                // Image to THIS same row (one menu, all items) instead of the
                // table rendering its own separate popup.
                val tableActions = toolbar.selectionController?.selectionTableActions()
                if (tableActions != null) {
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.markdown_table_copy_table)) {
                        tableActions.copyTableMarkdown()
                        toolbar.hide()
                    }
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.markdown_table_copy_table_image)) {
                        tableActions.copyTableImage()
                        toolbar.hide()
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarButton(label: String, onClick: () -> Unit) {
    // maxLines=1 + softWrap=false is load-bearing: the parent Row has a
    // fixed height(40.dp), so any label that wraps to a second line
    // shows only its first word on screen. Without this guard,
    // "Copy Rich Text" renders as just "Copy" — visually a duplicate of
    // the first toolbar item — and a longer "Add to input box" label collapses to "Add to".
    // See T-android-text-toolbar-dup-copy.
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun ToolbarDivider() {
    Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .width(0.5.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    )
}

private fun Rect.toIntRectRounded(): IntRect = IntRect(
    left = left.toInt(),
    top = top.toInt(),
    right = right.toInt(),
    bottom = bottom.toInt(),
)

/**
 * Positions the floating toolbar above the selection when there's room,
 * otherwise below it. Clamps horizontally to the window bounds.
 */
private class FloatingToolbarPositionProvider(
    private val anchor: IntRect,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val gap = 8
        val centerX = anchor.left + anchor.width / 2
        val x = (centerX - popupContentSize.width / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val aboveY = anchor.top - popupContentSize.height - gap
        val y = if (aboveY >= 0) {
            aboveY
        } else {
            (anchor.bottom + gap)
                .coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        }
        return IntOffset(x, y)
    }
}
