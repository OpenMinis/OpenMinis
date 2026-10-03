package com.openminis.app.ui.terminal.canvas

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [OpenMinis#277] Hardware-keyboard Ctrl+key, modified arrows and F-keys in
 * the terminal. Same probe table as iOS PhysicalKeyboardControlKeyTests.
 */
class TerminalKeyMapperTest {

    private fun code(ch: Char) = TerminalKeyMapper.controlCode(ch)?.toInt()

    @Test fun `letters map in both cases`() {
        assertEquals(0x01, code('a')); assertEquals(0x03, code('c'))
        assertEquals(0x04, code('d')); assertEquals(0x1A, code('z'))
        assertEquals(0x05, code('e')); assertEquals(0x0C, code('l'))
        assertEquals(0x01, code('A')); assertEquals(0x03, code('C'))
        assertEquals(0x04, code('D')); assertEquals(0x1A, code('Z'))
    }

    @Test fun `symbols and their xterm digit aliases`() {
        assertEquals(0x00, code('@')); assertEquals(0x00, code('2'))
        assertEquals(0x1B, code('[')); assertEquals(0x1B, code('3'))
        assertEquals(0x1C, code('\\')); assertEquals(0x1C, code('4'))
        assertEquals(0x1D, code(']')); assertEquals(0x1D, code('5'))
        assertEquals(0x1E, code('^')); assertEquals(0x1E, code('6'))
        assertEquals(0x1F, code('_')); assertEquals(0x1F, code('-'))
        assertEquals(0x1F, code('/')); assertEquals(0x1F, code('7'))
        assertEquals(0x7F, code('?')); assertEquals(0x7F, code('8'))
    }

    @Test fun `keys without a control meaning pass through`() {
        for (ch in listOf(' ', '1', '9', '0', '=', ';', 'é', '\t')) assertNull("'$ch'", code(ch))
    }

    private fun arrow(dir: Char, app: Boolean = false, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) =
        String(TerminalKeyMapper.arrow(dir, app, shift, alt, ctrl), Charsets.US_ASCII)

    @Test fun `plain arrows honour application cursor mode`() {
        assertEquals("\u001B[A", arrow('A'))
        assertEquals("\u001BOD", arrow('D', app = true))
    }

    @Test fun `modified arrows use the xterm modifier parameter`() {
        assertEquals("\u001B[1;2A", arrow('A', shift = true))
        assertEquals("\u001B[1;3C", arrow('C', alt = true))
        assertEquals("\u001B[1;5D", arrow('D', ctrl = true))
        assertEquals("\u001B[1;6B", arrow('B', shift = true, ctrl = true))
        // Modifiers win over DECCKM, as in iOS sendModifiedArrow.
        assertEquals("\u001B[1;5C", arrow('C', app = true, ctrl = true))
    }

    @Test fun `function keys`() {
        fun f(n: Int) = TerminalKeyMapper.functionKey(n)?.let { String(it, Charsets.US_ASCII) }
        assertEquals("\u001BOP", f(1)); assertEquals("\u001BOS", f(4))
        assertEquals("\u001B[15~", f(5)); assertEquals("\u001B[17~", f(6))
        assertEquals("\u001B[21~", f(10)); assertEquals("\u001B[23~", f(11))
        assertEquals("\u001B[24~", f(12))
        assertNull(f(0)); assertNull(f(13))
    }

    @Test fun `control code bytes are single raw bytes`() {
        assertArrayEquals(byteArrayOf(0x03), byteArrayOf(TerminalKeyMapper.controlCode('c')!!))
    }
}
