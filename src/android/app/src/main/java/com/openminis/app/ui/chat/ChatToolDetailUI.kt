package com.openminis.app.ui.chat

// [T-android-split-chat] Tool-detail sheet + reveal/editor helpers extracted
// verbatim from ChatScreen.kt: ToolDetailSheet, extractShellCommand,
// extractPartialJsonString, chunkToolOutput, initialRevealChunks,
// LazyRevealToolText, EditorCard. Imports trimmed after ChatScreen.kt split.
// Externally-called ones are internal; cluster-only ones stay private.

import android.content.Intent
import android.graphics.BitmapFactory
import java.io.File
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.Schedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.key.key
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors

/**
 * The page URL a `browser_use` block points at: [block]'s live `browserURL` →
 * its own `url` argument ([argsUrl]) → the nearest PRECEDING `browser_use`
 * block's URL, which is what a `click` / `screenshot` / `scroll` action that
 * carries no url falls back to (mirrors iOS `resolvedBrowserURL`).
 *
 * The sheet header's "open in browser" action and the body's URL bar used to
 * hand-maintain two copies of this walk, and the body's copy was missing the
 * `browser_use` filter on the back-scan — so for the same block the pill could
 * show a file path (any earlier tool's `toolArgs.url`-shaped field) while the
 * header opened something else, even though the header comment promised they
 * resolve "the same way". One helper, one rule.
 */
private fun resolveBrowserUrl(
    block: AssistantBlock,
    toolBlocks: List<AssistantBlock>,
    argsUrl: String,
): String =
    block.browserURL?.takeIf { it.isNotEmpty() }
        ?: argsUrl.takeIf { it.isNotEmpty() }
        ?: run {
            val idx = toolBlocks.indexOfFirst { it.id == block.id }
            if (idx > 0) {
                (idx - 1 downTo 0).firstNotNullOfOrNull { i ->
                    val prev = toolBlocks[i]
                    if (prev.toolName != "browser_use") return@firstNotNullOfOrNull null
                    prev.browserURL?.takeIf { it.isNotEmpty() }
                        ?: try {
                            org.json.JSONObject(prev.toolArgs)
                                .optString("url", "")
                                .takeIf { it.isNotEmpty() }
                        } catch (_: Exception) { null }
                }
            } else null
        } ?: ""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolDetailSheet(
    toolBlocks: List<AssistantBlock>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onOpenTerminalWithCommand: (String) -> Unit = {},
    onOpenBrowserForUrl: (String) -> Unit = {},
    onOpenSpillPath: (String) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var currentIdx by remember { mutableStateOf(initialIndex.coerceIn(0, toolBlocks.lastIndex.coerceAtLeast(0))) }
    val block = toolBlocks.getOrNull(currentIdx)
    if (block == null) {
        // Dismissing during composition crashes the activity and the foreground
        // service leaves the process alive on the launcher.
        androidx.compose.runtime.LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val isLive = block.toolStatus == ToolBlockStatus.RUNNING ||
        block.toolStatus == ToolBlockStatus.STREAMING ||
        block.toolStatus == ToolBlockStatus.PENDING
    val toolAccent = toolAccentColor(block.toolName)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .background(ChatColors.secondaryBg),
        ) {
            // ── Top Nav Bar (iOS: X button + "Minis Computer" + action button) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ChatColors.sheetHeaderBg)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Close button (iOS: xmark in circle)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(ChatColors.secondaryBg, CircleShape)
                        .border(0.5.dp, ChatColors.inputIconBorder, CircleShape)
                        .clip(CircleShape)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = ChatColors.primaryText,
                        modifier = Modifier.size(16.dp),
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // [T-step-timestamp v3 aa8b1128] Sheet header reverted to a
                // single-line title. The HH:mm:ss + duration line was moved
                // out to the inline ToolCallPill's trailing column (under
                // the elapsed-duration text) so it sits next to where the
                // user is already scanning timing info.
                Text(
                    text = "Minis Computer",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.primaryText,
                )

                Spacer(modifier = Modifier.weight(1f))

                // Action button: dispatches per tool kind.
                //  - shell_execute → opens the in-app terminal pre-filled
                //    with the running command (mirrors iOS ToolLiveSheet
                //    `Button { showTerminal = true }` →
                //    `ISHTerminalView(initCommand: shell command)`).
                //  - browser_use → fires Intent.ACTION_VIEW for the
                //    resolved page URL so the user can jump to the system
                //    browser (or any chooser) and continue from there.
                //  - everything else → still copies the tool output to
                //    clipboard.
                val clipboardManager = LocalClipboardManager.current
                val actionContext = LocalContext.current
                var copyDone by remember { mutableStateOf(false) }
                val isShellTool = block.toolName == "shell_execute"
                val isBrowserTool = block.toolName == "browser_use"
                val toolArgsForAction = remember(block.toolArgs) {
                    try { org.json.JSONObject(block.toolArgs) } catch (_: Exception) { org.json.JSONObject() }
                }
                // Resolve the browser URL through [resolveBrowserUrl], the SAME
                // helper the URL pill below uses, so the top-right action and
                // the pill can never name two different pages.
                val browserActionUrl = remember(block.id, block.browserURL, block.toolArgs, toolBlocks) {
                    if (!isBrowserTool) ""
                    else resolveBrowserUrl(
                        block,
                        toolBlocks,
                        toolArgsForAction.optString("url", ""),
                    )
                }
                val hasBrowserUrl = isBrowserTool && browserActionUrl.isNotEmpty()
                val spillPath = remember(block.content) {
                    com.openminis.app.tools.ToolOutputSpill.parseGuestPath(block.content)
                }
                if (spillPath != null) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(ChatColors.secondaryBg, CircleShape)
                            .border(0.5.dp, ChatColors.inputIconBorder, CircleShape)
                            .clip(CircleShape)
                            .clickable { onOpenSpillPath(spillPath) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = "Open full output",
                            tint = ChatColors.primaryText,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(ChatColors.secondaryBg, CircleShape)
                        .border(0.5.dp, ChatColors.inputIconBorder, CircleShape)
                        .clip(CircleShape)
                        .clickable {
                            if (isShellTool) {
                                val command = extractShellCommand(toolArgsForAction, block)
                                if (command.isNotBlank() && command != "Shell command") {
                                    AppLogger.info(
                                        "ChatScreen",
                                        "opening terminal with prefill: ${command.take(120)}",
                                    )
                                    onDismiss()
                                    onOpenTerminalWithCommand(command)
                                }
                            } else if (hasBrowserUrl) {
                                // Mirror iOS ToolLiveSheet: nav-bar globe
                                // routes back into the Session WebView pool
                                // (BrowserTabPool.selectOrCreateTabForURL)
                                // so an existing browser_use tab for this
                                // URL is reused; otherwise a new tab is
                                // spawned and loaded. Avoids dumping the
                                // user into a system chooser for what is
                                // already a live in-app browser session.
                                AppLogger.info(
                                    "ChatScreen",
                                    "browser_use action → open in session pool: ${browserActionUrl.take(160)}",
                                )
                                onOpenBrowserForUrl(browserActionUrl)
                            } else if (block.content.isNotEmpty()) {
                                clipboardManager.setText(AnnotatedString(block.content))
                                copyDone = true
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when {
                            copyDone -> Icons.Default.Check
                            isShellTool -> Icons.Default.Terminal
                            isBrowserTool -> Icons.Outlined.Public
                            else -> Icons.Default.ContentCopy
                        },
                        contentDescription = when {
                            isShellTool -> "Open in terminal"
                            isBrowserTool -> "Open in session browser"
                            else -> "Copy"
                        },
                        tint = ChatColors.primaryText,
                        modifier = Modifier.size(16.dp),
                    )
                }
                LaunchedEffect(copyDone) {
                    if (copyDone) {
                        kotlinx.coroutines.delay(2000)
                        copyDone = false
                    }
                }
            }

            // Divider
            HorizontalDivider(thickness = 1.dp, color = ChatColors.sheetHeaderBorder)

            // ── Content Area (iOS: tool-specific rendering) ──
            val outputScrollState = rememberScrollState()
            val toolArgsObj = remember(block.toolArgs) {
                try { org.json.JSONObject(block.toolArgs) } catch (_: Exception) { org.json.JSONObject() }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (block.toolName) {
                    // ── Shell: black rounded terminal card (mirrors iOS ToolLiveSheet) ──
                    "shell_execute" -> {
                        val command = extractShellCommand(toolArgsObj, block)
                        // T141: monitor active only while the tool is still running;
                        // sheet may stay open after the run completes (user reading
                        // output), and we don't want a stuck HUD there.
                        val sheetMonitor = rememberSystemResourceMonitor(active = isLive)
                        // BoxWithConstraints lets us mirror the iOS GeometryReader
                        // computation `cardWidth = width - 24; cardMinHeight =
                        // cardWidth * 3/4` so the terminal card stays at a 4:3
                        // floor when output is short (ToolLiveSheet.swift L1490).
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp)
                                .padding(top = 12.dp, bottom = 16.dp),
                        ) {
                            // T177 / T179: card grows from a 4:3 floor and
                            // fills the surrounding weight(1f) Box so the
                            // footer sits directly beneath it. The outer
                            // ModalBottomSheet column is already clamped at
                            // 0.85f viewport, so the card's effective max
                            // height is automatically the sheet body minus
                            // header + footer — no separate cardMaxHeight
                            // cap needed (and a 55%-screen cap was actively
                            // wrong: it left a ~200dp gray gap between the
                            // card's bottom edge and the footer Column on
                            // tall devices, which is the T179 user report).
                            // Internal verticalScroll takes over once the
                            // command + output exceed the card height.
                            val cardMinHeight = maxWidth * 3f / 4f
                            // T141 ribbon's measured height (vertical 2.5dp +
                            // 11sp text ≈ 22dp). Reserve enough bottom padding
                            // inside the scroll content so the last output line
                            // never slides under the HUD when scrolled to end.
                            val hudReservePadding = if (isLive) 28.dp else 0.dp
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight()
                                    .heightIn(min = cardMinHeight)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black)
                                    .border(
                                        width = 0.5.dp,
                                        color = Color(0xFF404040),
                                        shape = RoundedCornerShape(10.dp),
                                    ),
                            ) {
                                // T193 (supersedes T191): nested SelectionContainer
                                // gives shell output its own SelectionRegistrar so a
                                // long-press inside the ToolLiveSheet copy stays within
                                // this subtree (no cross-tree findCommonAncestor crash)
                                // while still letting the user select / copy command
                                // output and the linkified URLs inside it.
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .verticalScroll(outputScrollState)
                                            .padding(14.dp)
                                            .padding(bottom = hudReservePadding),
                                    ) {
                                        Text(
                                            text = "$ $command",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color.White,
                                            lineHeight = 18.sp,
                                        )
                                        if (block.content.isNotEmpty()) {
                                            // T38: linkify http/https URLs in shell output so the user can
                                            // tap them to open the in-app web preview (matches iOS
                                            // TerminalCanvasView.addURLLinks → MinisOpenURLBroker).
                                            val urlClick = LocalMarkdownUrlClickHandler.current
                                            val linkified = remember(block.content, urlClick) {
                                                if (urlClick != null) {
                                                    com.openminis.app.ui.util.linkifyUrls(
                                                        text = block.content,
                                                        onClick = urlClick,
                                                        linkColor = Color(0xFF7CC4FF), // light blue on dark terminal bg
                                                    )
                                                } else androidx.compose.ui.text.AnnotatedString(block.content)
                                            }
                                            Text(
                                                text = linkified,
                                                fontSize = 13.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = Color(0xFF34C759),  // iOS .green
                                                lineHeight = 18.sp,
                                            )
                                        }
                                    }
                                }
                                // T141 / T173 / T177: live CPU/MEM HUD ribbon
                                // overlaid on the card's bottom edge, *outside*
                                // the verticalScroll so it stays pinned even
                                // when the command/output content scrolls.
                                // Mirrors iOS ToolLiveSheet.swift L1523
                                // `.overlay(alignment: .bottom)`.
                                if (isLive) {
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .background(Color(0xFF141414))
                                            .padding(vertical = 2.5.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = sheetMonitor.formattedCpu(),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFF34C759),  // iOS .green
                                        )
                                        Text(
                                            text = sheetMonitor.formattedMem(),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFF34C759),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── File Edit: mock-editor card (iOS ToolLiveSheet.swift L1051-1190) ──
                    // T131-B: replace the previous flat red/green stripes with a
                    // bordered card — title bar with `(size)` label, ribbon-style
                    // diff body (no inter-line gaps), and a `Edited <path> (detail)`
                    // footer once the tool finishes. minHeight=cardWidth × 3/4 keeps
                    // small diffs from collapsing into a thin stripe.
                    "file_edit" -> {
                        val path = toolArgsObj.optString("path", "")
                            .ifEmpty { extractPartialJsonString("path", block.toolArgs) ?: "" }
                        val fileName = if (path.contains("/")) path.substringAfterLast("/") else path
                        val oldStr = toolArgsObj.optString("old_string", "")
                            .ifEmpty { extractPartialJsonString("old_string", block.toolArgs) ?: "" }
                        val newStr = toolArgsObj.optString("new_string", "")
                            .ifEmpty { extractPartialJsonString("new_string", block.toolArgs) ?: "" }

                        // T126-fix: ChatPalette.isDark follows the in-app theme
                        // override, isSystemInDarkTheme() doesn't.
                        val isDark = ChatColors.isDark
                        val cardBg = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF0F0F0)
                        val cardBorder = if (isDark) Color(0xFF404040) else Color(0xFFD1D1D1)
                        val redBg = if (isDark) Color(0xFF4D1414) else Color(0xFFFFE5E5)
                        val redText = if (isDark) Color(0xFFFF6666) else Color(0xFFCC1A1A)
                        val greenBg = if (isDark) Color(0xFF144D14) else Color(0xFFE5FFE5)
                        val greenText = if (isDark) Color(0xFF66FF66) else Color(0xFF1A991A)

                        val bytes = (oldStr.toByteArray(Charsets.UTF_8).size +
                            newStr.toByteArray(Charsets.UTF_8).size)
                        val sizeLabel = when {
                            isLive -> "streaming…"
                            bytes < 1024 -> "$bytes B"
                            else -> "${bytes / 1024} KB"
                        }
                        // Mirrors iOS — pull `(N replacement(s), N bytes)` etc.
                        // out of the tool result to surface alongside the path.
                        val resultDetail = block.content
                            .takeIf { it.isNotEmpty() && !isLive }
                            ?.let { Regex("""\(([^)]+)\)""").find(it)?.value }

                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                        ) {
                            val cardMinHeight = maxWidth * 3f / 4f
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // T260: bound the Column to the parent
                                    // BoxWithConstraints' maxHeight so the inner
                                    // verticalScroll ribbon (Minis Computer sheet
                                    // edit-card body) actually scrolls. Without
                                    // fillMaxHeight the Column's vertical constraint
                                    // is unbounded, the inner Column.verticalScroll
                                    // degenerates (each row laid out at full height
                                    // instead of scrolling), and the overflow
                                    // bleeds past the parent Box(weight=1f) onto
                                    // the footer ("Minis is editing File / 2/3 /
                                    // prev-next" at L5094). Mirrors the shell
                                    // branch (L4489) which already does this.
                                    .fillMaxHeight()
                                    .heightIn(min = cardMinHeight)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(cardBg)
                                    .border(0.5.dp, cardBorder, RoundedCornerShape(10.dp)),
                            ) {
                                // Title bar — filename + (size) label
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(ChatColors.secondaryBg)
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.EditNote,
                                        contentDescription = null,
                                        tint = Color(0xFFFF9500),
                                        modifier = Modifier.size(12.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (fileName.isNotEmpty()) fileName else "(file)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = FontFamily.Monospace,
                                        color = ChatColors.primaryText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "($sizeLabel)",
                                        fontSize = 11.sp,
                                        color = if (isLive) Color(0xCCFF9500) else ChatColors.tertiaryText,
                                    )
                                }
                                HorizontalDivider(thickness = 0.5.dp, color = cardBorder)

                                // Diff ribbon — scrollable body, zero inter-line spacing.
                                // T193 (supersedes T191): nested SelectionContainer scopes
                                // the diff lines into their own SelectionRegistrar so a
                                // long-press inside the ToolLiveSheet copy can't walk back
                                // to the outer chat-list container and trip
                                // findCommonAncestor. Users can select / copy + / - lines.
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .verticalScroll(outputScrollState),
                                        verticalArrangement = Arrangement.spacedBy(0.dp),
                                    ) {
                                        if (oldStr.isNotEmpty()) {
                                            oldStr.lines().forEach { line ->
                                                Text(
                                                    text = "- $line",
                                                    fontSize = 13.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = redText,
                                                    lineHeight = 17.sp,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(redBg)
                                                        .padding(horizontal = 14.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                        if (newStr.isNotEmpty()) {
                                            newStr.lines().forEach { line ->
                                                Text(
                                                    text = "+ $line",
                                                    fontSize = 13.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = greenText,
                                                    lineHeight = 17.sp,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(greenBg)
                                                        .padding(horizontal = 14.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                }

                                // Footer — only after streaming completes and we have
                                // a path or a parenthesized detail to show.
                                if (!isLive && (path.isNotEmpty() || resultDetail != null)) {
                                    Spacer(modifier = Modifier.weight(1f))
                                    HorizontalDivider(thickness = 0.5.dp, color = cardBorder)
                                    Column(
                                        modifier = Modifier.padding(
                                            horizontal = 14.dp,
                                            vertical = 10.dp,
                                        ),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = stringResource(R.string.chat_tool_edited),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = ChatColors.primaryText,
                                            )
                                            if (path.isNotEmpty()) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = path,
                                                    fontSize = 12.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = ChatColors.secondaryText,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f),
                                                )
                                            }
                                        }
                                        if (resultDetail != null) {
                                            Text(
                                                text = resultDetail,
                                                fontSize = 12.sp,
                                                color = ChatColors.tertiaryText,
                                                modifier = Modifier.padding(top = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── File Read / File Write: editor-style with title bar (mirrors iOS fileEditorContent) ──
                    "file_read", "file_write" -> {
                        val path = toolArgsObj.optString("path", "")
                            .ifEmpty { extractPartialJsonString("path", block.toolArgs) ?: "" }
                        val fileName = if (path.contains("/")) path.substringAfterLast("/") else path
                        val displayText = if (block.toolName == "file_write") {
                            toolArgsObj.optString("content", "")
                                .ifEmpty { extractPartialJsonString("content", block.toolArgs) ?: "" }
                                .ifEmpty { block.content }
                        } else {
                            block.content
                        }
                        EditorCard(
                            title = fileName.ifEmpty { "file" },
                            icon = if (block.toolName == "file_read") Icons.Default.Description
                                   else Icons.AutoMirrored.Filled.NoteAdd,
                            iconTint = ChatColors.secondaryText,
                            titleColor = ChatColors.primaryText,
                            sizeColor = ChatColors.tertiaryText,
                            bodyText = displayText,
                            bodyColor = ChatColors.primaryText,
                            isStreaming = isLive,
                            scrollState = outputScrollState,
                            trailingText = if (block.toolName == "file_write" &&
                                block.content.isNotEmpty() && block.content != displayText) block.content else null,
                        )
                    }

                    // ── Browser: action capsule + URL bar + screenshot + result text ──
                    "browser_use" -> {
                        val action = toolArgsObj.optString("action", "")
                        val argsUrl = toolArgsObj.optString("url", "")
                        // [resolveBrowserUrl] — the same resolution the header
                        // action button uses, including the browser_use-only
                        // back-scan this site used to skip.
                        val resolvedUrl = resolveBrowserUrl(block, toolBlocks, argsUrl)
                        // Preview priority (matches iOS): live WebView snapshot while the
                        // tool is running → current block's saved imageFilePath → most
                        // recent preceding browser_use block's screenshot.
                        val liveBitmap = rememberBrowserLiveSnapshot(block)
                        // T285: decode off main thread. Pre-T285 this was a
                        // synchronous BitmapFactory.decodeFile inside `remember{}`
                        // — for a multi-MB browser screenshot it ran a ~150-350ms
                        // blocking decode on the composition thread, stuttering
                        // any concurrent navigation transition (chat → image
                        // preview is the worst case because that recomposition
                        // runs the same frame the destination begins to slide in).
                        // Preserve the look-back-to-prev-browser_use semantics:
                        // try the current block's screenshot first, fall back to
                        // the most recent prior browser_use block's screenshot.
                        val savedBitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
                            initialValue = null,
                            block.imageFilePath,
                            toolBlocks.map { it.imageFilePath },
                        ) {
                            value = withContext(Dispatchers.IO) {
                                block.imageFilePath?.let { path ->
                                    try { android.graphics.BitmapFactory.decodeFile(path) } catch (_: Throwable) { null }
                                } ?: run {
                                    val blockIdx = toolBlocks.indexOfFirst { it.id == block.id }
                                    if (blockIdx <= 0) null
                                    else (blockIdx - 1 downTo 0).firstNotNullOfOrNull { i ->
                                        val prev = toolBlocks[i]
                                        if (prev.toolName == "browser_use" && prev.imageFilePath != null) {
                                            try {
                                                android.graphics.BitmapFactory.decodeFile(prev.imageFilePath)
                                            } catch (_: Throwable) { null }
                                        } else null
                                    }
                                }
                            }
                        }
                        val screenshotBitmap = liveBitmap ?: savedBitmap

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(outputScrollState)
                                .padding(top = 12.dp, bottom = 16.dp),
                        ) {
                            // Action + URL capsules row (iOS: HStack { action capsule, URL capsule })
                            if (action.isNotEmpty() || resolvedUrl.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (action.isNotEmpty()) {
                                        Text(
                                            text = action,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White,
                                            modifier = Modifier
                                                .background(Color(0xFF007AFF), CircleShape)
                                                .padding(horizontal = 10.dp, vertical = 2.dp),
                                        )
                                    }
                                    if (resolvedUrl.isNotEmpty()) {
                                        val clipboardManager = LocalClipboardManager.current
                                        // iOS CopyableURLCapsule: Color(white: 0.85) fill,
                                        // Color(white: 0.35) text — distinct pill against sheet bg.
                                        Text(
                                            text = resolvedUrl,
                                            fontSize = 12.sp,
                                            color = Color(0xFF595959),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .weight(1f)
                                                .background(Color(0xFFD9D9D9), CircleShape)
                                                .clip(CircleShape)
                                                .clickable { clipboardManager.setText(AnnotatedString(resolvedUrl)) }
                                                .padding(horizontal = 10.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }

                            // Screenshot image (iOS: shadowed card)
                            if (screenshotBitmap != null) {
                                Spacer(modifier = Modifier.height(12.dp))
                                // aspectRatio binds the intrinsic bitmap ratio into the
                                // layout; without it, Image on a bitmap source ignores
                                // fillMaxWidth and draws at native px dimensions, causing
                                // wide screenshots to clip on the right.
                                val aspect = screenshotBitmap.width.toFloat() /
                                    screenshotBitmap.height.coerceAtLeast(1)
                                Image(
                                    bitmap = screenshotBitmap.asImageBitmap(),
                                    contentDescription = "Browser screenshot",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp)
                                        .aspectRatio(aspect)
                                        .shadow(8.dp, RoundedCornerShape(10.dp))
                                        .clip(RoundedCornerShape(10.dp)),
                                )
                            } else if (isLive) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp)
                                        .height(200.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(ChatColors.secondaryBg),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                            color = ChatColors.link,
                                        )
                                        Text(stringResource(R.string.loading), fontSize = 12.sp, color = ChatColors.tertiaryText)
                                    }
                                }
                            }

                            // Result content
                            if (block.content.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp)
                                        .background(ChatColors.toolBg, RoundedCornerShape(10.dp))
                                        .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp)),
                                ) {
                                    // Result header
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Default.Language,
                                            contentDescription = null,
                                            tint = ChatColors.tertiaryText,
                                            modifier = Modifier.size(12.dp),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.chat_tool_result_label),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ChatColors.secondaryText,
                                        )
                                    }
                                    HorizontalDivider(thickness = 0.5.dp, color = ChatColors.separator)
                                    Text(
                                        text = block.content,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = ChatColors.primaryText,
                                        lineHeight = 16.sp,
                                        modifier = Modifier.padding(14.dp),
                                    )
                                }
                            }
                        }
                    }

                    // ── Memory: pink editor card (iOS: memoryEditorContent) ──
                    "memory_write", "memory_get" -> {
                        // memory_write: content comes from tool args.
                        // memory_get: content comes from the tool's result text.
                        val memContent = if (block.toolName == "memory_write") {
                            toolArgsObj.optString("content", "")
                                .ifEmpty { extractPartialJsonString("content", block.toolArgs) ?: "" }
                                .ifEmpty { block.content }
                        } else {
                            block.content
                        }
                        val keywords = toolArgsObj.optString("keywords", "")
                            .ifEmpty { extractPartialJsonString("keywords", block.toolArgs) ?: "" }
                        val prefix = if (keywords.isNotEmpty() && block.toolName == "memory_get")
                            "Keywords: $keywords\n\n" else ""
                        EditorCard(
                            title = block.toolName,
                            icon = Icons.Default.Psychology,
                            iconTint = ToolMemoryAccent.copy(alpha = 0.6f),
                            titleColor = ToolMemoryAccent,
                            sizeColor = ToolMemoryAccent.copy(alpha = 0.5f),
                            bodyText = prefix + memContent,
                            bodyColor = ToolMemoryAccent.copy(alpha = 0.85f),
                            isStreaming = isLive,
                            scrollState = outputScrollState,
                        )
                    }

                    // ── read_image: inline image preview + metadata (iOS parity) ──
                    "read_image" -> {
                        val imgPath = block.imageFilePath
                        // T285: async decode (was sync inside remember{}, blocking the
                        // composition thread when the chat list scrolled into view).
                        val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
                            initialValue = null,
                            imgPath,
                        ) {
                            value = if (imgPath == null) null else withContext(Dispatchers.IO) {
                                val f = File(imgPath)
                                if (!f.exists()) null
                                else try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(ChatColors.background)
                                .verticalScroll(outputScrollState)
                                .padding(16.dp),
                        ) {
                            val bmp = bitmap
                            if (bmp != null) {
                                // [T-android-read-image-preview] Bind the bitmap's
                                // intrinsic ratio into the layout. Without
                                // aspectRatio, Image on a bitmap source ignores
                                // fillMaxWidth and lays out at native px height, so
                                // a wide screenshot rendered small/letterboxed at
                                // the top with dead space below (same fix the
                                // browser_use screenshot branch already applies).
                                val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(aspect)
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(0.5.dp, ChatColors.separator, RoundedCornerShape(10.dp)),
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            if (block.content.isNotEmpty()) {
                                Text(
                                    text = block.content,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = ChatColors.primaryText,
                                    lineHeight = 18.sp,
                                )
                            }
                        }
                    }

                    // ── Default: generic text output ──
                    else -> {
                        if (block.content.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(ChatColors.background)
                                    .verticalScroll(outputScrollState)
                                    .padding(16.dp),
                            ) {
                                // T38: linkify URLs in fallback tool-result text so curl /
                                // file_read / generic command output produces tappable links
                                // that route through the in-app web preview.
                                // [T-android-tool-result-lazy-render] Reveal large
                                // generic output incrementally (LazyRevealToolText
                                // handles the linkify + reveal window); wrap in a
                                // SelectionContainer so the revealed text stays
                                // selectable/copyable (it wasn't before).
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    LazyRevealToolText(
                                        bodyText = block.content,
                                        color = ChatColors.primaryText,
                                        scrollState = outputScrollState,
                                        linkify = true,
                                    )
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        toolIconFor(block.toolName),
                                        contentDescription = null,
                                        tint = toolAccent.copy(alpha = 0.3f),
                                        modifier = Modifier.size(40.dp),
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (isLive) "Running..." else "No output",
                                        color = ChatColors.tertiaryText,
                                        fontSize = 14.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Bottom Bar (iOS: tool info row + navigation row) ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ChatColors.background)
                    .navigationBarsPadding(),
            ) {
                HorizontalDivider(thickness = 0.5.dp, color = ChatColors.separator)

                // Tool info row: status icon + title/subtitle + duration
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Status icon (iOS: 18x18)
                    if (isLive) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = toolAccent,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        val (icon, tint) = when (block.toolStatus) {
                            ToolBlockStatus.SUCCESS -> Icons.Default.CheckCircle to ToolCheckColor
                            ToolBlockStatus.FAILED -> Icons.Default.Error to ToolErrorColor
                            ToolBlockStatus.CANCELLED -> Icons.Default.Cancel to ToolCancelColor
                            ToolBlockStatus.TIMEOUT -> Icons.Default.Schedule to ToolErrorColor
                            else -> Icons.Default.CheckCircle to ToolCheckColor
                        }
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Title + subtitle
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = toolTitleLabel(block.toolName),
                            fontSize = 14.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ChatColors.primaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = block.toolTitle.ifEmpty { block.toolName },
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            color = ChatColors.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // [T-step-timestamp v3-fix aa8b1128] Trailing column in
                    // the detail-view bottom bar: duration on top, the
                    // step's HH:mm:ss start time on a smaller / lighter
                    // line right beneath it. This is where the user goes
                    // to inspect a single step, so the timestamp lives
                    // here instead of on every pill in the message list.
                    if ((block.durationMs > 0 && !isLive) || block.startTimeMs > 0L) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            if (block.durationMs > 0 && !isLive) {
                                Text(
                                    text = formatToolDuration(block.durationMs),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = ChatColors.tertiaryText,
                                )
                            }
                            if (block.startTimeMs > 0L) {
                                Text(
                                    text = formatStepTimestamp(block.startTimeMs),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = ChatColors.tertiaryText.copy(alpha = 0.75f),
                                )
                            }
                        }
                    }
                }

                // Navigation row: |< ... 1/N ... >|
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Back button (iOS: backward.end.fill)
                    IconButton(
                        onClick = { if (currentIdx > 0) currentIdx-- },
                        enabled = currentIdx > 0,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "上一个",
                            tint = if (currentIdx > 0) ChatColors.primaryText else ChatColors.disabledText,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Center: Live indicator or pagination
                    if (isLive) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(Color(0xFF34C759), CircleShape),
                            )
                            Text(
                                "Live",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = ChatColors.primaryText,
                            )
                        }
                    } else {
                        Text(
                            "${currentIdx + 1} / ${toolBlocks.size}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            color = ChatColors.secondaryText,
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Forward button (iOS: forward.end.fill)
                    IconButton(
                        onClick = { if (currentIdx < toolBlocks.lastIndex) currentIdx++ },
                        enabled = currentIdx < toolBlocks.lastIndex,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "下一个",
                            tint = if (currentIdx < toolBlocks.lastIndex) ChatColors.primaryText else ChatColors.disabledText,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

// Helper: extract shell command from args or content (mirrors iOS toolDescription logic).
// Also tolerant of *partial* streaming JSON (JSONObject.optString returns "" for
// truncated objects, so fall back to a streaming-safe substring scan).
internal fun extractShellCommand(args: org.json.JSONObject, block: AssistantBlock): String {
    // 1. Try toolArgs "command" field (works once JSON is complete)
    val fromArgs = args.optString("command", "")
    if (fromArgs.isNotEmpty()) return fromArgs
    // 2. Streaming fallback: scan the raw toolArgs buffer for `"command":"…`
    //    which may not yet close. Mirrors iOS extractPartialStringValue.
    val partial = extractPartialJsonString("command", block.toolArgs)
    if (!partial.isNullOrEmpty()) return partial
    // 3. Try parsing "$ <command>" from first line of content (iOS fallback)
    if (block.content.startsWith("$ ")) {
        val firstLine = block.content.lineSequence().firstOrNull() ?: ""
        if (firstLine.length > 2) return firstLine.drop(2)
    }
    // 4. iOS fallback: generic "Shell command"
    return "Shell command"
}

/**
 * Tolerant partial-JSON string extractor — UI-side mirror of
 * ChatViewModel.extractPartialStringValue. Used so detail-sheet renderers
 * (shell command, file path, write content) can show live content while the
 * model is still streaming the tool input JSON.
 */
internal fun extractPartialJsonString(key: String, json: String): String? {
    if (json.isEmpty()) return null
    val patterns = listOf("\"$key\": \"", "\"$key\":\"")
    for (p in patterns) {
        val at = json.indexOf(p)
        if (at < 0) continue
        val after = json.substring(at + p.length)
        var i = 0
        val n = after.length
        while (i < n) {
            val c = after[i]
            if (c == '\\') { i += 2; continue }
            if (c == '"') {
                return after.substring(0, i)
                    .replace("\\n", "\n").replace("\\t", "\t")
                    .replace("\\\"", "\"").replace("\\/", "/")
                    .replace("\\\\", "\\")
            }
            i++
        }
        // No closing quote yet (still streaming) — return what we have.
        return after.replace("\\n", "\n").replace("\\t", "\t")
            .replace("\\\"", "\"").replace("\\/", "/")
            .replace("\\\\", "\\")
    }
    return null
}

/**
 * Shared editor-card layout used by file_read / file_write / memory_* detail
 * views. Mirrors iOS `fileEditorContent` + `memoryEditorContent` from
 * ToolLiveSheet.swift: an inner card with:
 *  - 10dp rounded corners + 0.5dp outline
 *  - header row (icon + title + size label) on a slightly darker strip
 *  - divider
 *  - monospaced body padded to 14dp (horizontal) / 14dp (vertical)
 *  - optional trailing footer text below the card
 *
 * Colors are parameterized so callers can tint the whole card (e.g. memory
 * uses pink everywhere; file uses neutral primary text).
 */

// ─── [T-android-tool-result-lazy-render] ────────────────────────────────────
// Opening a tool-result detail (ToolDetailSheet) with a large payload — a big
// memory_get / file read — janked: the body was a single Text laid out at once
// inside a verticalScroll Column (which, like a ScrollView, does NOT virtualize),
// so the whole 70KB+ string was composed + measured on open. Mirrors iOS
// commit 9d81ba18 (ToolLiveSheet lazy-reveal).
//
// We chunk the body into 40-line groups and reveal an initial window (~200
// lines / 10KB, whichever is fewer), growing the window each time the user
// reaches the bottom (auto-bump on the footer's onGloballyPositioned) or taps
// "Load more" / "Load all". Only the revealed Texts are composed, so open is
// cheap regardless of total size. Still scrollable and still selectable —
// rendered inside the caller's SelectionContainer; the reveal window's Texts
// register with the same plain-Compose SelectionRegistrar (NOT MinisTextKit,
// which is the chat-list markdown layer and isn't involved here).

private const val LAZY_TOOL_CHUNK_LINES = 40
private const val LAZY_TOOL_INITIAL_CHUNKS = 5            // ~200 lines
private const val LAZY_TOOL_BATCH_CHUNKS = 5              // +200 lines per reveal
private const val LAZY_TOOL_INITIAL_BYTE_CAP = 10 * 1024 // clamp first window to ~10KB

/** Split [text] into ordered 40-line chunks. Each chunk keeps its trailing
 *  newline so concatenation round-trips. */
private fun chunkToolOutput(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = text.split("\n")
    val out = ArrayList<String>((lines.size / LAZY_TOOL_CHUNK_LINES) + 1)
    var i = 0
    while (i < lines.size) {
        val end = minOf(i + LAZY_TOOL_CHUNK_LINES, lines.size)
        // Re-join with "\n"; the final chunk omits the trailing separator that
        // split() consumed, which is fine — we never re-concatenate for display.
        out.add(lines.subList(i, end).joinToString("\n"))
        i = end
    }
    return out
}

/** Initial reveal count: up to [LAZY_TOOL_INITIAL_CHUNKS], further clamped so
 *  the first window stays under [LAZY_TOOL_INITIAL_BYTE_CAP] (covers a few very
 *  long lines that fit in < 5 chunks but exceed 10KB). */
private fun initialRevealChunks(chunks: List<String>): Int {
    if (chunks.isEmpty()) return 0
    var count = 0
    var bytes = 0
    for (chunk in chunks.take(LAZY_TOOL_INITIAL_CHUNKS)) {
        bytes += chunk.toByteArray(Charsets.UTF_8).size
        count++
        if (bytes >= LAZY_TOOL_INITIAL_BYTE_CAP) break
    }
    return count.coerceAtLeast(1)
}

/**
 * [T-android-tool-result-lazy-render] Render [bodyText] with incremental reveal.
 * Builds the revealed prefix as ONE AnnotatedString (optionally linkified) so
 * selection/copy spans the whole revealed window as a single contiguous Text,
 * then a "Load more / Load all" footer. Caller supplies the surrounding
 * verticalScroll (passed in as [scrollState]) + SelectionContainer.
 *
 * Auto-reveal is gated on the scroll position reaching the bottom — NOT on the
 * footer being positioned. The body lives inside a `verticalScroll` Column,
 * which composes + positions ALL children (it doesn't virtualize), so a
 * position-based trigger would fire for the off-screen footer immediately and
 * reveal everything at once, defeating the laziness. Watching
 * `scrollState.value` vs `maxValue` instead only grows the window once the user
 * has actually scrolled near the end of what's currently revealed.
 */
@Composable
private fun LazyRevealToolText(
    bodyText: String,
    color: Color,
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
    linkify: Boolean = false,
) {
    val chunks = remember(bodyText) { chunkToolOutput(bodyText) }
    // Reset the reveal window whenever the underlying text changes (e.g. the
    // user pages to a different tool block, which swaps bodyText).
    var revealed by remember(bodyText) { mutableStateOf(initialRevealChunks(chunks)) }
    val total = chunks.size
    val shownText = remember(bodyText, revealed) {
        chunks.take(revealed.coerceIn(1, total.coerceAtLeast(1))).joinToString("\n")
    }
    val urlClick = LocalMarkdownUrlClickHandler.current
    val displayed = remember(shownText, urlClick, linkify) {
        if (linkify && urlClick != null) {
            com.openminis.app.ui.util.linkifyUrls(text = shownText, onClick = urlClick)
        } else androidx.compose.ui.text.AnnotatedString(shownText)
    }

    // Auto-grow the window when the user scrolls within ~600px of the bottom of
    // the currently-revealed content. derivedStateOf keeps the predicate from
    // recomposing on every scroll pixel; it only flips at the threshold.
    val nearBottom by remember {
        derivedStateOf {
            val max = scrollState.maxValue
            max > 0 && max != Int.MAX_VALUE && scrollState.value >= max - 600
        }
    }
    LaunchedEffect(nearBottom, revealed, total) {
        if (nearBottom && revealed < total) {
            revealed = (revealed + LAZY_TOOL_BATCH_CHUNKS).coerceAtMost(total)
        }
    }

    Column(modifier = modifier) {
        Text(
            text = displayed,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
            lineHeight = 18.sp,
            modifier = Modifier.fillMaxWidth(),
        )
        if (revealed < total) {
            val remainingChunks = total - revealed
            val nextLines = minOf(LAZY_TOOL_BATCH_CHUNKS, remainingChunks) * LAZY_TOOL_CHUNK_LINES
            val remainingLines = remainingChunks * LAZY_TOOL_CHUNK_LINES
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.chat_tool_load_more, nextLines),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = color.copy(alpha = 0.9f),
                    modifier = Modifier.clickable {
                        revealed = (revealed + LAZY_TOOL_BATCH_CHUNKS).coerceAtMost(total)
                    },
                )
                Text(
                    text = stringResource(R.string.chat_tool_load_all, remainingLines),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = color.copy(alpha = 0.9f),
                    modifier = Modifier.clickable { revealed = total },
                )
            }
        }
    }
}

@Composable
private fun EditorCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    titleColor: Color,
    sizeColor: Color,
    bodyText: String,
    bodyColor: Color,
    isStreaming: Boolean,
    scrollState: androidx.compose.foundation.ScrollState,
    trailingText: String? = null,
) {
    val bytes = bodyText.toByteArray(Charsets.UTF_8).size
    val sizeLabel = when {
        bodyText.isEmpty() -> null
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
    // Match iOS fileEditorContent (ToolLiveSheet.swift:1350) instead of the
    // generic chat palette: the editor card needs its own dark/light grayscale
    // ramp so the body stands out from the sheet container and the header
    // strip reads as one notch lighter than the body.
    //   iOS dark  : body white:0.10 (#1A1A1A), header white:0.13 (#212121),
    //               border white:0.25 (#404040)
    //   iOS light : body white:0.94 (#F0F0F0), header white:0.92 (#EBEBEB),
    //               border white:0.82 (#D1D1D1)
    // T126-fix: use ChatPalette.isDark so the in-app theme override (Settings →
    // Appearance) wins over the system setting. Otherwise users on Light system
    // + Dark in-app would see white card on black chat.
    val isDark = ChatColors.isDark
    val cardBg = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF0F0F0)
    val headerBg = if (isDark) Color(0xFF212121) else Color(0xFFEBEBEB)
    val cardBorder = if (isDark) Color(0xFF404040) else Color(0xFFD1D1D1)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(cardBg)
                .border(0.5.dp, cardBorder, RoundedCornerShape(10.dp)),
        ) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .padding(vertical = 10.dp, horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(14.dp))
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (sizeLabel != null) {
                    Text(
                        text = if (isStreaming) "($sizeLabel received)" else "($sizeLabel)",
                        fontSize = 11.sp,
                        color = if (isStreaming) Color(0xFFFF9500).copy(alpha = 0.8f) else sizeColor,
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = cardBorder)

            // Body
            if (bodyText.isNotEmpty()) {
                // T193 (supersedes T191): EditorCard renders both inline in
                // the chat LazyColumn (wrapped in SelectionContainer at L1519)
                // AND inside the ToolLiveSheet ModalBottomSheet — two
                // independent Compose subtrees. T191 used DisableSelection to
                // dodge the cross-tree `findCommonAncestor` crash but lost
                // the ability to select text. A nested SelectionContainer
                // creates its own SelectionRegistrar; Texts inside it register
                // there instead of the outer chat-list registrar, so the
                // selection toolbar's coordinate walk stays within this
                // subtree and never tries to span two hierarchies — both
                // instances are crash-safe and individually selectable.
                androidx.compose.foundation.text.selection.SelectionContainer {
                    // [T-android-tool-result-lazy-render] Reveal large bodies
                    // incrementally instead of laying the whole string out on
                    // open (a 70KB memory_get janked the sheet for seconds).
                    LazyRevealToolText(
                        bodyText = bodyText,
                        color = bodyColor,
                        scrollState = scrollState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                    )
                }
            } else if (isStreaming) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = iconTint,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
        if (trailingText != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = trailingText,
                fontSize = 12.sp,
                color = ChatColors.tertiaryText,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
