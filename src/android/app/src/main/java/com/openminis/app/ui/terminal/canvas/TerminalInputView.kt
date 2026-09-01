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
 *   - Ctrl+letter     → control code (via Shift state from the accessory bar)
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return handleKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        val appCursor = getAppCursorMode()
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed

        val bytes: ByteArray? = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER -> byteArrayOf(0x0D)
            KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7F)
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001B[3~".toByteArray()
            KeyEvent.KEYCODE_TAB -> byteArrayOf(0x09)
            KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(0x1B)
            KeyEvent.KEYCODE_DPAD_UP -> arrow('A', appCursor)
            KeyEvent.KEYCODE_DPAD_DOWN -> arrow('B', appCursor)
            KeyEvent.KEYCODE_DPAD_RIGHT -> arrow('C', appCursor)
            KeyEvent.KEYCODE_DPAD_LEFT -> arrow('D', appCursor)
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001B[H".toByteArray()
            KeyEvent.KEYCODE_MOVE_END -> "\u001B[F".toByteArray()
            KeyEvent.KEYCODE_PAGE_UP -> "\u001B[5~".toByteArray()
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001B[6~".toByteArray()
            else -> {
                val unicode = event.unicodeChar
                if (unicode != 0) {
                    val ch = unicode.toChar()
                    when {
                        ctrl && ch.uppercaseChar() in 'A'..'Z' -> {
                            byteArrayOf((ch.uppercaseChar() - 'A' + 1).toByte())
                        }
                        alt -> {
                            // Meta-prefixed: ESC + char (xterm metaSendsEscape)
                            byteArrayOf(0x1B) + ch.toString().toByteArray(Charsets.UTF_8)
                        }
                        else -> ch.toString().toByteArray(Charsets.UTF_8)
                    }
                } else null
            }
        }
        return if (bytes != null) { send(bytes); true } else false
    }

    private fun arrow(dir: Char, applicationCursorKeys: Boolean): ByteArray {
        // applicationCursorKeys (DECCKM) switches CSI [ → SS3 O
        val prefix = if (applicationCursorKeys) byteArrayOf(0x1B, 'O'.code.toByte())
                     else byteArrayOf(0x1B, '['.code.toByte())
        return prefix + byteArrayOf(dir.code.toByte())
    }

    private fun send(bytes: ByteArray) {
        onInputBytes?.invoke(bytes)
    }
}
