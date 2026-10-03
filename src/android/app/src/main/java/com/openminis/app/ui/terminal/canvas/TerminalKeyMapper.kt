package com.openminis.app.ui.terminal.canvas

/**
 * [OpenMinis#277] Pure key → byte mappings for hardware-keyboard input in the
 * terminal. Android-free so it can be unit-tested on the JVM; see
 * `TerminalKeyMapperTest`. Mirrors iOS `TerminalControlKeyMapper` and
 * `TerminalKeyInputView.sendModifiedArrow` / its F1-F12 table.
 */
internal object TerminalKeyMapper {

    /**
     * The byte a terminal expects for [ch] pressed together with Control, or
     * null for keys with no control meaning (left to the normal key path).
     * [ch] is the key's character WITHOUT Ctrl/Alt applied.
     */
    fun controlCode(ch: Char): Byte? = when (ch) {
        // Masking the low five bits maps both cases (a=0x61, A=0x41) to
        // 0x01..0x1A, with no subtraction to underflow.
        in 'a'..'z', in 'A'..'Z' -> (ch.code and 0x1F).toByte()
        // NUL. Deliberately not Ctrl+Space: it is the common input-method
        // switch shortcut on hardware keyboards, and consuming it would strand
        // CJK users in the terminal. Ctrl+@ / Ctrl+2 give NUL, as in xterm.
        '@', '2' -> 0x00
        '[', '3' -> 0x1B // ESC
        '\\', '4' -> 0x1C // FS (SIGQUIT)
        ']', '5' -> 0x1D // GS
        '^', '6' -> 0x1E // RS
        '_', '-', '/', '7' -> 0x1F // US
        '?', '8' -> 0x7F // DEL
        else -> null
    }

    /**
     * Arrow key [dir] ('A' up, 'B' down, 'C' right, 'D' left). Unmodified it
     * honours DECCKM (SS3 when [applicationCursorKeys]); with modifiers it is
     * xterm's `ESC [ 1 ; <mod> <dir>`, mod = 1 + shift(1) + alt(2) + ctrl(4).
     */
    fun arrow(dir: Char, applicationCursorKeys: Boolean, shift: Boolean, alt: Boolean, ctrl: Boolean): ByteArray {
        var mod = 1
        if (shift) mod += 1
        if (alt) mod += 2
        if (ctrl) mod += 4
        if (mod == 1) {
            val intro = if (applicationCursorKeys) 'O' else '['
            return byteArrayOf(0x1B, intro.code.toByte(), dir.code.toByte())
        }
        return "\u001B[1;$mod$dir".toByteArray(Charsets.US_ASCII)
    }

    /** xterm sequence for function key F[n] (1..12), or null outside that range. */
    fun functionKey(n: Int): ByteArray? {
        val seq = when (n) {
            1 -> "\u001BOP"
            2 -> "\u001BOQ"
            3 -> "\u001BOR"
            4 -> "\u001BOS"
            5 -> "\u001B[15~"
            6 -> "\u001B[17~"
            7 -> "\u001B[18~"
            8 -> "\u001B[19~"
            9 -> "\u001B[20~"
            10 -> "\u001B[21~"
            11 -> "\u001B[23~"
            12 -> "\u001B[24~"
            else -> return null
        }
        return seq.toByteArray(Charsets.US_ASCII)
    }
}
