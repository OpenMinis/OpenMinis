package com.openminis.app.ui.terminal

import com.openminis.app.R
import androidx.compose.ui.res.stringResource

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.openminis.app.sandbox.TerminalSession
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// iOS-matched palette
private val TerminalBg = Color(0xFF000000)
private val TerminalFg = Color(0xFFD4D4D4)
private val TerminalGreen = Color(0xFF34C759)
private val AccessoryBg = Color(0xFF1F1F1F)
private val AccButtonBg = Color(0xFF404040)
private val AccButtonActive = Color(0xFF007AFF)
private val TopButtonBg = Color(0xFF2C2C2E)

/**
 * Full-screen terminal — now backed by [Termux TerminalView] instead of the
 * hand-rolled emulator. Ctrl and Alt are persistent toggle states that inject
 * into the [TerminalViewClient] so pressing e.g. Ctrl then 'c' sends 0x03
 * exactly like a physical keyboard.
 */
@Composable
fun TerminalScreen(
    terminalSession: TerminalSession,
    onBack: () -> Unit,
    initCommand: String? = null,
    sessionId: String? = null,
) {
    val scope = rememberCoroutineScope()

    // ── Ctrl / Alt persistent toggles ──────────────────────────────────────
    var ctrlDown by remember { mutableStateOf(false) }
    var altDown by remember { mutableStateOf(false) }

    // Track terminal session state so Compose re-executes the
    // AndroidView.update block when the Termux PTY finishes booting.
    val sessionState by terminalSession.state.collectAsStateEffect()

    // ── Lifecycle ──────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        if (!terminalSession.isRunning) terminalSession.start(sessionId = sessionId)
        if (initCommand.isNullOrBlank()) return@LaunchedEffect
        // TERM-1: wait for the PTY state event, not a fixed 500 ms. A cold
        // PRoot boot on a slow device routinely exceeded the old delay and
        // dropped the pre-filled command into a shell that was not reading yet.
        val ready = withTimeoutOrNull(5_000L) {
            terminalSession.state.first {
                it == TerminalSession.State.RUNNING || it == TerminalSession.State.STOPPED
            }
        }
        if (ready != TerminalSession.State.RUNNING) return@LaunchedEffect
        // Strip control characters that would execute commands (CR, LF, ESC, etc.)
        // before sending to the PTY — defense-in-depth alongside DeepLinkHandler's
        // sanitization. The initCommand is meant to pre-fill visible text, not
        // auto-execute.
        val safe = initCommand.filter { it != '\u0000' && (it >= ' ' || it == '\t') && it != '\u007f' }
        if (safe.isNotBlank()) {
            terminalSession.sendText(safe)
        }
    }

    DisposableEffect(Unit) {
        onDispose { terminalSession.stop() }
    }

    // Claim the broker while the fullscreen terminal is up so ChatScreen
    // (still composed underneath this destination's stack) doesn't try to
    // present its own preview sheet on top — mirrors iOS ISHTerminalView.
    DisposableEffect(Unit) {
        MinisOpenUrlBroker.setTerminalVisible(true)
        onDispose { MinisOpenUrlBroker.setTerminalVisible(false) }
    }

    // ── OSC 1337 MinisOpenURL ──────────────────────────────────────────────
    var previewUrl by remember { mutableStateOf<String?>(null) }
    val pendingUrl by MinisOpenUrlBroker.pendingUrl.collectAsStateEffect()
    LaunchedEffect(pendingUrl) {
        val uri = pendingUrl ?: return@LaunchedEffect
        if (MinisOpenUrlBroker.isWebScheme(uri.scheme)) {
            previewUrl = uri.toString()
        }
        MinisOpenUrlBroker.consume()
    }

    val accessoryBarHeightDp = 40.dp

    Box(modifier = Modifier.fillMaxSize().background(TerminalBg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
                .padding(bottom = accessoryBarHeightDp),
        ) {
            Spacer(modifier = Modifier.height(52.dp))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Termux's TerminalView — full VT-100 / xterm emulation with
                // text selection, copy/paste, pinch-to-zoom, and URL detection
                // baked in. The view is created once and survives recomposition.
                AndroidView(
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            setTextSize(24)          // px; ≈ 12 sp at 2x density
                            setTypeface(jetBrainsMonoTypeface(ctx))
                            val view = this
                            setTerminalViewClient(MinisTerminalViewClient(
                                view = view,
                                context = ctx,
                                getControlDown = { ctrlDown },
                                getAltDown = { altDown },
                            ))
                            // TerminalView must be focusable in touch mode so a
                            // tap requests focus and opens the soft keyboard.
                            // Termux's own layout XML sets android:focusable="true";
                            // in Compose we must do it programmatically.
                            isFocusable = true
                            isFocusableInTouchMode = true
                            // Hand the view to the session so PTY output can
                            // redraw it (onTextChanged → onScreenUpdated).
                            terminalSession.attachView(this)
                        }
                    },
                    update = { view ->
                        // [fix: terminal dead-on-entry] This block re-runs when
                        // `isRunning` flips false→true (reading it here
                        // subscribes this update to that State), so attachSession
                        // fires as soon as the Termux PTY finishes booting —
                        // not just on first composition when termuxSession is
                        // still null. Without this, the TerminalView stays blank
                        // and every keypress is dropped (only ✕ worked).
                        val session = terminalSession.termuxSession
                        if (sessionState == TerminalSession.State.RUNNING && session != null && view.mTermSession != session) {
                            view.attachSession(session)
                        }
                        terminalSession.attachView(view)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ── Top bar ────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.systemBars)
                .height(52.dp)
                .background(TerminalBg),
        ) {
            TerminalTopBar(
                onClose = {
                    terminalSession.stop()
                    onBack()
                },
                onClear = {
                    // Kill any half-typed line + full terminal reset.
                    terminalSession.sendRawBytes(byteArrayOf(0x15)) // Ctrl+U
                    terminalSession.clearOutput()
                },
            )
        }

        // ── Keyboard accessory bar ─────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .imePadding()
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            KeyboardAccessoryBar(
                ctrlDown = ctrlDown,
                altDown = altDown,
                onCtrlToggle = { ctrlDown = !ctrlDown },
                onAltToggle = { altDown = !altDown },
                onSendRaw = { bytes ->
                    terminalSession.sendRawBytes(bytes)
                    // Auto-release toggles after use so the next key isn't
                    // accidentally modified.
                    ctrlDown = false
                    altDown = false
                },
            )
        }

        previewUrl?.let { url ->
            com.openminis.app.ui.components.UrlPreviewSheet(
                url = url,
                onDismiss = { previewUrl = null },
            )
        }
    }
}

// ── Termux TerminalViewClient with Ctrl/Alt relay ─────────────────────────────

private class MinisTerminalViewClient(
    private val view: TerminalView,
    private val context: android.content.Context,
    private val getControlDown: () -> Boolean,
    private val getAltDown: () -> Boolean,
) : TerminalViewClient {
    override fun onSingleTapUp(e: MotionEvent) {
        // Termux's own client calls KeyboardUtils.showSoftKeyboard() here.
        // Without it the TerminalView never opens the on-screen keyboard, so
        // the user can see output but cannot type — the "terminal can't be
        // operated" symptom.
        view.requestFocus()
        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
        if (imm != null) {
            imm.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun onScale(scale: Float): Float = scale
    override fun onCodePoint(
        codePoint: Int, ctrlDown: Boolean,
        session: com.termux.terminal.TerminalSession,
    ): Boolean = false
    override fun onKeyDown(
        keyCode: Int, event: KeyEvent,
        session: com.termux.terminal.TerminalSession,
    ): Boolean = false
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = false
    override fun readControlKey(): Boolean = getControlDown()
    override fun readAltKey(): Boolean = getAltDown()
    override fun onEmulatorSet() {}

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    // Some keyboards (mainly Samsung stock) misbehave with TYPE_NULL; forcing a
    // char-based input type makes the soft keyboard reliably produce text.
    // https://github.com/termux/termux-app/issues/686
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false

    // ── JitPack 0.118.0 log callbacks (no-op) ──
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: java.lang.Exception) {}
    override fun logStackTrace(tag: String, e: java.lang.Exception) {}

    @Volatile var clearVersion: Int = 0; private set
    fun bumpClear() { clearVersion++ }
}

// ── Tiny helper: collectAsState for StateFlow ─────────────────────────────────

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateEffect(): androidx.compose.runtime.State<T> {
    val state = remember { androidx.compose.runtime.mutableStateOf(value) }
    LaunchedEffect(this) { collect { state.value = it } }
    return state
}

// ─── Top bar ──────────────────────────────────────────────────────────────────

@Composable
private fun TerminalTopBar(
    onClose: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalBg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularIconButton(
            icon = Icons.Default.Close,
            contentDescription = stringResource(R.string.common_close),
            tint = TerminalFg,
            onClick = onClose,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.terminal_title),
            color = TerminalFg,
            style = TextStyle(
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 16.sp,
            ),
        )
        Spacer(modifier = Modifier.weight(1f))
        CircularIconButton(
            icon = Icons.Default.Brush,
            contentDescription = stringResource(R.string.terminal_clear),
            tint = TerminalGreen,
            onClick = onClear,
        )
    }
}

@Composable
private fun CircularIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(TopButtonBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ─── Keyboard accessory bar ───────────────────────────────────────────────────

@Composable
private fun KeyboardAccessoryBar(
    ctrlDown: Boolean,
    altDown: Boolean,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onSendRaw: (ByteArray) -> Unit,
) {
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AccessoryBg),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuickCommandButton("Esc", iconText = "⎋") { onSendRaw(byteArrayOf(0x1B)) }
            QuickCommandButton("Tab", icon = Icons.AutoMirrored.Filled.KeyboardTab) { onSendRaw(byteArrayOf(0x09)) }
            QuickCommandButton("⏎", iconText = "⏎") { onSendRaw(byteArrayOf(0x0D)) }
            // Ctrl and Alt are persistent toggles — tap to enable, tap again
            // to disable. When enabled, the next typed character is sent with
            // the modifier applied by Termux's input pipeline.
            QuickCommandButton("Ctrl", iconText = "^", isActive = ctrlDown, onClick = onCtrlToggle)
            QuickCommandButton("Alt", iconText = "⌥", isActive = altDown, onClick = onAltToggle)
            QuickCommandButton("\u2191", icon = Icons.Default.KeyboardArrowUp) { onSendRaw(byteArrayOf(0x1B, 0x5B, 0x41)) }
            QuickCommandButton("\u2193", icon = Icons.Default.KeyboardArrowDown) { onSendRaw(byteArrayOf(0x1B, 0x5B, 0x42)) }
            QuickCommandButton("\u2190", icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft) { onSendRaw(byteArrayOf(0x1B, 0x5B, 0x44)) }
            QuickCommandButton("\u2192", icon = Icons.AutoMirrored.Filled.KeyboardArrowRight) { onSendRaw(byteArrayOf(0x1B, 0x5B, 0x43)) }
            QuickCommandButton("C-c", icon = Icons.Outlined.Cancel) { onSendRaw(byteArrayOf(0x03)) }
            QuickCommandButton("C-d", icon = Icons.Default.Eject) { onSendRaw(byteArrayOf(0x04)) }
            QuickCommandButton("C-z", icon = Icons.Outlined.PauseCircle) { onSendRaw(byteArrayOf(0x1A)) }
        }
    }
}

@Composable
private fun QuickCommandButton(
    label: String,
    icon: ImageVector? = null,
    iconText: String? = null,
    isActive: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (isActive) AccButtonActive else AccButtonBg
    val fg = if (isActive) Color.White else TerminalGreen
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            icon != null -> Icon(icon, null, tint = fg, modifier = Modifier.size(12.dp))
            iconText != null -> Text(iconText, color = fg, style = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp))
        }
        Text(label, color = fg, style = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp), maxLines = 1)
    }
}
