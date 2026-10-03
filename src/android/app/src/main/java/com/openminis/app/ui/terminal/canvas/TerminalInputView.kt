package com.openminis.app.ui.terminal.canvas

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Invisible 1×1 EditText that captures all keyboard input — hardware keys AND
 * IME text commits — and forwards the raw bytes to the PTY master via
 * [onInput]. Port of iOS TerminalInputView (which used a UITextField as a
 * first-responder event sink).
 *
 * Key mappings (mirroring xterm/iSH):
 *   - Backspace       → 0x7F  (DEL — what a real TTY sends)
 *   - Enter           → 0x0D  (CR — ICRNL termios converts to LF)
 *   - Arrow keys      → CSI A/B/C/D  (or SS3 when applicationCursorKeys)
 *   - Tab             → 0x09
 *   - Esc             → 0x1B
 *   - Printable char  → utf-8 bytes
 *   - Ctrl+key        → control code (hardware Ctrl, or the accessory bar's
 *                       sticky Ctrl); see [TerminalKeyMapper]
 *   - F1-F12, Ctrl/Alt/Shift+arrows → xterm sequences
 *
 * Uses a custom BaseInputConnection that refuses to buffer text — every
 * `commitText()` is flushed immediately so the PTY sees keystrokes in the
 * same order the user typed them.
 */
@Composable
fun TerminalInputView(
    onInput: (ByteArray) -> Unit,
    applicationCursorKeys: Boolean,
    modifier: Modifier = Modifier,
    controller: TerminalInputController = rememberTerminalInputController(),
) {
    val context = LocalContext.current
    val editText = remember { createHiddenEditText(context) }

    DisposableEffect(editText) {
        controller.editText = editText
        onDispose { controller.editText = null }
    }

    AndroidView(
        factory = { editText.also { it.bind(onInput) { applicationCursorKeys } } },
        update = { it.bind(onInput) { applicationCursorKeys } },
        modifier = modifier,
    )
}

/** Host-side controller: call show/hide to focus/blur the hidden EditText. */
class TerminalInputController {
    internal var editText: TerminalInputEditText? = null

    fun requestFocus() {
        editText?.let { et ->
            et.requestFocus()
            val imm = et.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun clearFocus() {
        editText?.let { et ->
            val imm = et.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(et.windowToken, 0)
            et.clearFocus()
        }
    }

    val isFocused: Boolean get() = editText?.isFocused == true
}

@Composable
fun rememberTerminalInputController(): TerminalInputController =
    remember { TerminalInputController() }

private fun createHiddenEditText(context: Context): TerminalInputEditText =
    TerminalInputEditText(context).apply {
        // Make invisible but still first-responder-capable.
        alpha = 0f
        isCursorVisible = false
        setTextIsSelectable(false)
        setSingleLine()
        isFocusable = true
        isFocusableInTouchMode = true
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        // [T-android-terminal-enter-keeps-focus] Kept in step with the
        // onCreateInputConnection override below — see the note there for why
        // the action must be NONE rather than DONE.
        imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        setBackgroundColor(0x00000000)
    }

class TerminalInputEditText @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : EditText(context, attrs) {

    private var onInputBytes: ((ByteArray) -> Unit)? = null
    private var getAppCursorMode: () -> Boolean = { false }

    fun bind(onInput: (ByteArray) -> Unit, appCursorMode: () -> Boolean) {
        this.onInputBytes = onInput
        this.getAppCursorMode = appCursorMode
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        // [T-android-terminal-enter-keeps-focus] IME_ACTION_NONE, not
        // IME_ACTION_DONE.
        //
        // "Done" tells the platform this field is FINISHED once the user
        // commits, and the default handling for it releases focus. In a shell
        // that is exactly wrong: Enter runs a command and the user keeps
        // typing, so every command dropped focus and the terminal had to be
        // tapped again before the next keystroke registered — the reported
        // symptom, and most obvious with a hardware keyboard, where there is no
        // IME to re-summon and nothing on screen to explain why typing stopped
        // working.
        //
        // NONE declares "this field has no completion action", so Enter is just
        // another key: it reaches the key handler below, is translated to CR,
        // and focus stays put.
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE

        return object : BaseInputConnection(this, false) {
            // Most IME keys are routed through commitText. Forward verbatim
            // and immediately clear any buffered composing state so the
            // hidden EditText never accumulates characters.
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text == null || text.isEmpty()) return true
                send(text.toString().toByteArray(Charsets.UTF_8))
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text == null || text.isEmpty()) return true
                send(text.toString().toByteArray(Charsets.UTF_8))
                return true
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                event ?: return false
                if (event.action != KeyEvent.ACTION_DOWN) return true
                return handleKeyEvent(event)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { send(byteArrayOf(0x7F)) }
                return true
            }

            override fun finishComposingText(): Boolean = true
        }
    }

    /**
     * [OpenMinis#277] Key codes whose ACTION_DOWN this view consumed. Their
     * ACTION_UP must be consumed too (iOS 0b249c31f: pressesEnded forwards
     * only presses pressesBegan did not consume). Otherwise the up reaches
     * EditText.onKeyUp, and for a single-line field an Enter key-up is an
     * editor action that moves focus to the next view: after the first
     * hardware Enter every later keystroke went to the Compose root instead
     * of the terminal.
     */
    private val consumedKeyDowns = HashSet<Int>()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleKeyEvent(event)) {
            consumedKeyDowns.add(keyCode)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (consumedKeyDowns.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        val appCursor = getAppCursorMode()
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        val shift = event.isShiftPressed

        val bytes: ByteArray? = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER -> byteArrayOf(0x0D)
            KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7F)
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001B[3~".toByteArray()
            KeyEvent.KEYCODE_TAB -> byteArrayOf(0x09)
            KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(0x1B)
            // [OpenMinis#277] Modified arrows use xterm's ESC [ 1 ; <mod> X,
            // as iOS sendModifiedArrow does (word jumps in readline/vim/tmux).
            KeyEvent.KEYCODE_DPAD_UP -> TerminalKeyMapper.arrow('A', appCursor, shift, alt, ctrl)
            KeyEvent.KEYCODE_DPAD_DOWN -> TerminalKeyMapper.arrow('B', appCursor, shift, alt, ctrl)
            KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalKeyMapper.arrow('C', appCursor, shift, alt, ctrl)
            KeyEvent.KEYCODE_DPAD_LEFT -> TerminalKeyMapper.arrow('D', appCursor, shift, alt, ctrl)
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001B[H".toByteArray()
            KeyEvent.KEYCODE_MOVE_END -> "\u001B[F".toByteArray()
            KeyEvent.KEYCODE_PAGE_UP -> "\u001B[5~".toByteArray()
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001B[6~".toByteArray()
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                TerminalKeyMapper.functionKey(event.keyCode - KeyEvent.KEYCODE_F1 + 1)
            else -> {
                // [OpenMinis#277] Hardware Ctrl+key. With Ctrl in the meta
                // state, KeyCharacterMap finds no mapping for most keys and
                // getUnicodeChar() returns 0, so the old Ctrl branch (nested
                // under `unicode != 0`) never ran and Ctrl+C / Ctrl+D did
                // nothing. Resolve the character with Ctrl and Alt masked out
                // (Shift kept, so Ctrl+Shift+- still reads as '_'), then map it
                // like iOS TerminalControlKeyMapper. Meta (Cmd/Win) combinations
                // are left to the system.
                val controlCode = if (ctrl && !event.isMetaPressed) {
                    val base = event.getUnicodeChar(
                        event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv(),
                    )
                    if (base > 0) TerminalKeyMapper.controlCode(base.toChar()) else null
                } else null
                if (controlCode != null) {
                    // Ctrl+Alt+key → Meta (ESC) prefix + control code.
                    if (alt) byteArrayOf(0x1B, controlCode) else byteArrayOf(controlCode)
                } else {
                    val unicode = event.unicodeChar
                    if (unicode != 0) {
                        val ch = unicode.toChar()
                        when {
                            alt -> {
                                // Meta-prefixed: ESC + char (xterm metaSendsEscape)
                                byteArrayOf(0x1B) + ch.toString().toByteArray(Charsets.UTF_8)
                            }
                            else -> ch.toString().toByteArray(Charsets.UTF_8)
                        }
                    } else null
                }
            }
        }
        return if (bytes != null) { send(bytes); true } else false
    }

    private fun send(bytes: ByteArray) {
        onInputBytes?.invoke(bytes)
    }
}
