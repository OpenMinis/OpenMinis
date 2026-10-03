package com.openminis.app.ui.terminal.emulator

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State

/**
 * Core terminal emulator — connects AnsiParser → TerminalBuffer, handles every CSI/ESC/OSC
 * sequence. Port of iOS TerminalEmulator.swift.
 *
 * Bump [version] for Compose redraws; each [feed] batches a single bump.
 */
class TerminalEmulator(cols: Int = 80, rows: Int = 24) {
    private val _version = mutableStateOf(0L)
    val version: State<Long> = _version

    val primaryBuffer = TerminalBuffer(cols, rows, maxScrollback = 2000)
    val alternateBuffer = TerminalBuffer(cols, rows, maxScrollback = 0).also { it.scrollbackEnabled = false }
    var isAlternateActive: Boolean = false
        private set

    val activeBuffer: TerminalBuffer
        get() = if (isAlternateActive) alternateBuffer else primaryBuffer

    private val parser = AnsiParser()

    var applicationCursorKeys: Boolean = false
        private set
    var autoWrap: Boolean = true
        private set
    var cursorVisible: Boolean = true
        private set
    var bracketedPaste: Boolean = false
        private set
    var applicationKeypad: Boolean = false
        private set
    var originMode: Boolean = false
        private set
    var cursorShape: CursorShape = CursorShape.BLOCK
        private set

    var title: String = ""
        private set

    /** Callback for terminal responses (DSR, DA). */
    var onResponse: ((ByteArray) -> Unit)? = null

    private val cursorStyle = CursorStyle()

    var scrollOffset: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, activeBuffer.scrollback.size)
            if (field != clamped) {
                field = clamped
                _version.value = _version.value + 1
            }
        }

    var cols: Int = cols
        private set
    var rows: Int = rows
        private set

    /** Feed raw bytes from the PTY. */
    fun feed(bytes: ByteArray, len: Int = bytes.size) {
        parser.feed(bytes, len) { handleAction(it) }
        _version.value = _version.value + 1
    }

    /** Return exactly [rows] lines to display, applying scrollOffset. */
    fun visibleLines(): List<Array<TerminalCell>> {
        val buf = activeBuffer
        if (scrollOffset == 0) return buf.grid.toList()
        val scrollbackCount = buf.scrollback.size
        val totalLines = scrollbackCount + buf.grid.size
        val viewStart = maxOf(0, totalLines - rows - scrollOffset)
        val result = ArrayList<Array<TerminalCell>>(rows)
        for (i in viewStart until viewStart + rows) {
            when {
                i < scrollbackCount -> {
                    val line = buf.scrollback.elementAt(i)
                    val padded = if (line.size < cols) {
                        Array(cols) { idx -> if (idx < line.size) line[idx] else TerminalCell.BLANK }
                    } else if (line.size > cols) {
                        Array(cols) { idx -> line[idx] }
                    } else line
                    result.add(padded)
                }
                else -> {
                    val gridRow = i - scrollbackCount
                    result.add(
                        if (gridRow < buf.grid.size) buf.grid[gridRow]
                        else Array(cols) { TerminalCell.BLANK }
                    )
                }
            }
        }
        return result
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        if (newCols == cols && newRows == rows) return
        cols = newCols; rows = newRows
        primaryBuffer.resize(newCols, newRows)
        alternateBuffer.resize(newCols, newRows)
        _version.value = _version.value + 1
    }

    /** Current cursor position (in active buffer). */
    fun cursorPos(): Pair<Int, Int> = activeBuffer.cursorCol to activeBuffer.cursorRow

    fun currentCursorStyle(): CursorStyle = cursorStyle.copy()

    // ── Text selection (T194 groundwork) ───────────────────────────────────
    // Coordinates are viewport-relative (0..cols-1 / 0..rows-1) — i.e. they
    // index into [visibleLines]. The TerminalView gesture handler converts
    // pixel positions to (col,row) and feeds them straight in. Mirrors the
    // Termux terminal-view selection model, but reads from our own buffer
    // instead of bringing in com.termux.terminal.TerminalBuffer.
    //
    // We deliberately keep this state inside the emulator (rather than the
    // view) so renderer + selection-text-extract see a consistent snapshot
    // and a redraw triggered by [setSelectionRect] flows through the
    // existing `version` State the Compose canvas already observes.

    /** Active selection rect, or null. (col1,row1) is start, (col2,row2) is end. */
    private val _selectionRect = mutableStateOf<IntArray?>(null)
    val selectionRect: State<IntArray?> = _selectionRect

    fun setSelectionRect(col1: Int, row1: Int, col2: Int, row2: Int) {
        _selectionRect.value = intArrayOf(col1, row1, col2, row2)
        _version.value = _version.value + 1
    }

    fun clearSelectionRect() {
        if (_selectionRect.value != null) {
            _selectionRect.value = null
            _version.value = _version.value + 1
        }
    }

    /**
     * Extract text from the viewport for the given selection rect, in the
     * order start → end. Coordinates are clamped to [0,cols) × [0,rows). If
     * start is after end (in reading order), they are swapped automatically.
     * Trailing spaces on each line are trimmed (what users expect
     * when they grab a URL by long-pressing).
     */
    fun getSelectedText(col1: Int, row1: Int, col2: Int, row2: Int): String {
        val lines = visibleLines()
        if (lines.isEmpty()) return ""

        // Normalize: ensure (sx,sy) ≤ (ex,ey) in reading order.
        val (sx, sy, ex, ey) = if (row1 < row2 || (row1 == row2 && col1 <= col2)) {
            intArrayOf(col1, row1, col2, row2)
        } else {
            intArrayOf(col2, row2, col1, row1)
        }.let { listOf(it[0], it[1], it[2], it[3]) }

        val maxRow = lines.size - 1
        val ry1 = sy.coerceIn(0, maxRow)
        val ry2 = ey.coerceIn(0, maxRow)

        val sb = StringBuilder()
        for (r in ry1..ry2) {
            val row = lines[r]
            val w = row.size
            val cStart = if (r == ry1) sx.coerceIn(0, w) else 0
            val cEnd = if (r == ry2) (ex + 1).coerceIn(0, w) else w
            val lineSb = StringBuilder()
            for (c in cStart until cEnd) {
                val cp = row[c].char
                if (cp != 0) lineSb.appendCodePoint(cp)
            }
            // Trim trailing spaces on each line.
            var end = lineSb.length
            while (end > 0 && lineSb[end - 1] == ' ') end--
            sb.append(lineSb, 0, end)
            if (r < ry2) sb.append('\n')
        }
        return sb.toString()
    }

    // ────────────────────────────────────────────────────────────────────────
    private fun handleAction(action: ParsedAction) {
        when (action) {
            is ParsedAction.Printable -> activeBuffer.writeChar(action.codePoint, cursorStyle, autoWrap)
            is ParsedAction.ControlChar -> handleControlChar(action.byte)
            is ParsedAction.CsiDispatch -> handleCsi(action.params, action.intermediate, action.finalByte)
            is ParsedAction.EscDispatch -> handleEsc(action.char)
            is ParsedAction.OscDispatch -> handleOsc(action.command, action.payload)
        }
    }

    private fun handleControlChar(byte: Int) {
        val buf = activeBuffer
        when (byte) {
            0x07 -> { /* BEL */ }
            0x08 -> buf.moveCursorBackward(1)
            0x09 -> buf.tabForward()
            0x0A, 0x0B, 0x0C -> buf.lineFeed()
            0x0D -> buf.carriageReturn()
            0x0E, 0x0F -> { /* charset shifts ignored */ }
        }
    }

    private fun handleCsi(params: IntArray, intermediate: Char?, finalByte: Char) {
        val buf = activeBuffer
        if (intermediate == '?') {
            handleDecPrivateMode(params, set = (finalByte == 'h'))
            return
        }
        if (intermediate == '>' && finalByte == 'c') {
            onResponse?.invoke("\u001B[>0;0;0c".toByteArray(Charsets.UTF_8))
            return
        }
        if (intermediate == '>') return

        when (finalByte) {
            'A' -> buf.moveCursorUp(maxOf(1, params.getOrElse(0) { 1 }))
            'B' -> buf.moveCursorDown(maxOf(1, params.getOrElse(0) { 1 }))
            'C' -> buf.moveCursorForward(maxOf(1, params.getOrElse(0) { 1 }))
            'D' -> buf.moveCursorBackward(maxOf(1, params.getOrElse(0) { 1 }))
            'E' -> { buf.moveCursorDown(maxOf(1, params.getOrElse(0) { 1 })); buf.carriageReturn() }
            'F' -> { buf.moveCursorUp(maxOf(1, params.getOrElse(0) { 1 })); buf.carriageReturn() }
            'G' -> buf.moveCursorToColumn(maxOf(1, params.getOrElse(0) { 1 }) - 1)
            'H', 'f' -> {
                val row = maxOf(1, params.getOrElse(0) { 1 })
                val col = maxOf(1, params.getOrElse(1) { 1 })
                if (originMode) buf.moveCursorTo(col - 1, buf.scrollTop + row - 1)
                else buf.moveCursorTo(col - 1, row - 1)
            }
            'd' -> {
                val row = maxOf(1, params.getOrElse(0) { 1 })
                buf.moveCursorTo(buf.cursorCol, row - 1)
            }
            'J' -> buf.eraseInDisplay(params.getOrElse(0) { 0 })
            'K' -> buf.eraseInLine(params.getOrElse(0) { 0 })
            'X' -> buf.eraseCharacters(maxOf(1, params.getOrElse(0) { 1 }))
            'L' -> buf.insertLines(maxOf(1, params.getOrElse(0) { 1 }))
            'M' -> buf.deleteLines(maxOf(1, params.getOrElse(0) { 1 }))
            '@' -> buf.insertCharacters(maxOf(1, params.getOrElse(0) { 1 }))
            'P' -> buf.deleteCharacters(maxOf(1, params.getOrElse(0) { 1 }))
            'S' -> buf.scrollUp(maxOf(1, params.getOrElse(0) { 1 }))
            'T' -> buf.scrollDown(maxOf(1, params.getOrElse(0) { 1 }))
            'r' -> {
                val top = params.getOrElse(0) { 1 }
                val bottom = params.getOrElse(1) { rows }
                buf.setScrollRegion(top, bottom)
                if (originMode) buf.moveCursorTo(0, buf.scrollTop) else buf.moveCursorTo(0, 0)
            }
            'm' -> handleSgr(if (params.isEmpty()) intArrayOf(0) else params)
            'n' -> {
                val code = params.getOrElse(0) { 0 }
                when (code) {
                    6 -> {
                        val r = buf.cursorRow + 1
                        val c = buf.cursorCol + 1
                        onResponse?.invoke("\u001B[$r;${c}R".toByteArray(Charsets.UTF_8))
                    }
                    5 -> onResponse?.invoke("\u001B[0n".toByteArray(Charsets.UTF_8))
                }
            }
            'c' -> if ((params.getOrElse(0) { 0 }) == 0) {
                onResponse?.invoke("\u001B[?62;22c".toByteArray(Charsets.UTF_8))
            }
            's' -> buf.saveCursor(cursorStyle)
            'u' -> cursorStyle.copyFrom(buf.restoreCursor())
            'I' -> repeat(maxOf(1, params.getOrElse(0) { 1 })) { buf.tabForward() }
            'q' -> if (intermediate == ' ') {
                when (params.getOrElse(0) { 1 }) {
                    0, 1, 2 -> cursorShape = CursorShape.BLOCK
                    3, 4 -> cursorShape = CursorShape.UNDERLINE
                    5, 6 -> cursorShape = CursorShape.BAR
                }
            }
            else -> {}
        }
    }

    private fun handleDecPrivateMode(params: IntArray, set: Boolean) {
        for (p in params) when (p) {
            1 -> applicationCursorKeys = set
            6 -> {
                originMode = set
                if (set) activeBuffer.moveCursorTo(0, activeBuffer.scrollTop)
                else activeBuffer.moveCursorTo(0, 0)
            }
            7 -> autoWrap = set
            12 -> {}
            25 -> cursorVisible = set
            47, 1047 -> switchBuffer(set)
            1048 -> if (set) activeBuffer.saveCursor(cursorStyle)
                    else cursorStyle.copyFrom(activeBuffer.restoreCursor())
            1049 -> if (set) {
                primaryBuffer.saveCursor(cursorStyle)
                switchBuffer(true)
                activeBuffer.eraseInDisplay(2)
            } else {
                switchBuffer(false)
                cursorStyle.copyFrom(primaryBuffer.restoreCursor())
            }
            2004 -> bracketedPaste = set
        }
    }

    private fun handleSgr(params: IntArray) {
        var i = 0
        while (i < params.size) {
            val p = params[i]
            when (p) {
                0 -> { cursorStyle.foreground = TerminalColor.Default; cursorStyle.background = TerminalColor.Default; cursorStyle.attributes = TextAttributes() }
                1 -> cursorStyle.attributes = cursorStyle.attributes.with(TextAttributes.BOLD)
                2 -> cursorStyle.attributes = cursorStyle.attributes.with(TextAttributes.DIM)
                3 -> cursorStyle.attributes = cursorStyle.attributes.with(TextAttributes.ITALIC)
                4, 21 -> cursorStyle.attributes = cursorStyle.attributes.with(TextAttributes.UNDERLINE)
                5 -> cursorStyle.attributes = cursorStyle.attributes.with(TextAttributes.BLINK)
                7 -> cursorStyle.attributes = cursorStyle.attributes.with(TextAttributes.INVERSE)
                8 -> cursorStyle.attributes = cursorStyle.attributes.with(TextAttributes.HIDDEN)
                9 -> cursorStyle.attributes = cursorStyle.attributes.with(TextAttributes.STRIKETHROUGH)
                22 -> cursorStyle.attributes = cursorStyle.attributes.without(TextAttributes.BOLD).without(TextAttributes.DIM)
                23 -> cursorStyle.attributes = cursorStyle.attributes.without(TextAttributes.ITALIC)
                24 -> cursorStyle.attributes = cursorStyle.attributes.without(TextAttributes.UNDERLINE)
                25 -> cursorStyle.attributes = cursorStyle.attributes.without(TextAttributes.BLINK)
                27 -> cursorStyle.attributes = cursorStyle.attributes.without(TextAttributes.INVERSE)
                28 -> cursorStyle.attributes = cursorStyle.attributes.without(TextAttributes.HIDDEN)
                29 -> cursorStyle.attributes = cursorStyle.attributes.without(TextAttributes.STRIKETHROUGH)
                in 30..37 -> cursorStyle.foreground = TerminalColor.Indexed(p - 30)
                38 -> i = parseSgrColor(params, i, isForeground = true)
                39 -> cursorStyle.foreground = TerminalColor.Default
                in 40..47 -> cursorStyle.background = TerminalColor.Indexed(p - 40)
                48 -> i = parseSgrColor(params, i, isForeground = false)
                49 -> cursorStyle.background = TerminalColor.Default
                in 90..97 -> cursorStyle.foreground = TerminalColor.Indexed(p - 90 + 8)
                in 100..107 -> cursorStyle.background = TerminalColor.Indexed(p - 100 + 8)
            }
            i++
        }
    }

    private fun parseSgrColor(params: IntArray, index: Int, isForeground: Boolean): Int {
        if (index + 1 >= params.size) return index
        return when (params[index + 1]) {
            5 -> {
                if (index + 2 >= params.size) return index + 1
                val c = TerminalColor.Indexed(params[index + 2].coerceIn(0, 255))
                if (isForeground) cursorStyle.foreground = c else cursorStyle.background = c
                index + 2
            }
            2 -> {
                if (index + 4 >= params.size) return index + 1
                val c = TerminalColor.Rgb(
                    params[index + 2].coerceIn(0, 255),
                    params[index + 3].coerceIn(0, 255),
                    params[index + 4].coerceIn(0, 255),
                )
                if (isForeground) cursorStyle.foreground = c else cursorStyle.background = c
                index + 4
            }
            else -> index
        }
    }

    private fun handleEsc(char: Char) {
        when (char) {
            '7' -> activeBuffer.saveCursor(cursorStyle)
            '8' -> cursorStyle.copyFrom(activeBuffer.restoreCursor())
            'D' -> activeBuffer.lineFeed()
            'M' -> activeBuffer.reverseIndex()
            'E' -> { activeBuffer.carriageReturn(); activeBuffer.lineFeed() }
            'c' -> resetTerminal()
            '=' -> applicationKeypad = true
            '>' -> applicationKeypad = false
            else -> {}
        }
    }

    private fun handleOsc(command: Int, payload: String) {
        when (command) {
            0, 2 -> title = payload
            1337 -> handleITermOsc(payload)
            else -> {}
        }
    }

    private fun handleITermOsc(payload: String) {
        val eq = payload.indexOf('=')
        if (eq <= 0 || payload.substring(0, eq) != "MinisOpenURL") return
        val urlString = payload.substring(eq + 1)
        if (urlString.isEmpty()) return
        com.openminis.app.terminal.MinisOpenUrlBroker.offer(urlString)
    }

    private fun switchBuffer(toAlternate: Boolean) {
        if (toAlternate && !isAlternateActive) {
            isAlternateActive = true
            alternateBuffer.resize(cols, rows)
            alternateBuffer.moveCursorTo(0, 0)
        } else if (!toAlternate && isAlternateActive) {
            isAlternateActive = false
        }
    }

    private fun resetTerminal() {
        cursorStyle.foreground = TerminalColor.Default
        cursorStyle.background = TerminalColor.Default
        cursorStyle.attributes = TextAttributes()
        applicationCursorKeys = false
        autoWrap = true
        cursorVisible = true
        bracketedPaste = false
        applicationKeypad = false
        originMode = false
        cursorShape = CursorShape.BLOCK
        primaryBuffer.resize(cols, rows)
        primaryBuffer.eraseInDisplay(3)
        // T310: eraseInDisplay only blanks the grid; without an explicit
        // cursor-home the cursor stays wherever it was before the clear,
        // so the next PTY byte (shell echo / re-prompt) writes mid-screen
        // instead of at the top-left.
        primaryBuffer.moveCursorTo(0, 0)
        alternateBuffer.resize(cols, rows)
        alternateBuffer.eraseInDisplay(3)
        alternateBuffer.moveCursorTo(0, 0)
        isAlternateActive = false
        scrollOffset = 0
    }
}
