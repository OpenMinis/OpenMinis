package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-agent-toolname-display] The sub agent card showed raw wire names
 * ("browser_use") where iOS shows "Browser use". This pins the formatter, and
 * pins that it is NOT the other tool-name vocabulary in this codebase.
 */
class HelperToolLabelTest {

    @Test
    fun `underscores and hyphens become spaces with a leading capital`() {
        assertEquals("Browser use", helperToolLabel("browser_use"))
        assertEquals("Shell execute", helperToolLabel("shell_execute"))
        assertEquals("File write", helperToolLabel("file_write"))
        // iOS handles both separators; a hyphenated name is equally possible.
        assertEquals("Browser use", helperToolLabel("browser-use"))
    }

    @Test
    fun `single words and odd input survive`() {
        assertEquals("Compact", helperToolLabel("compact"))
        assertEquals("", helperToolLabel(""))
        // Only the FIRST character is touched — an already-capitalised or
        // acronym-bearing name keeps its own shape.
        assertEquals("HTTP fetch", helperToolLabel("HTTP_fetch"))
    }

    /**
     * The trap this change had to avoid: ChatToolFormatting.toolDisplayName is
     * a different vocabulary, serving the "Minis is using X" sentence. Wiring
     * the card to it would have renamed the tool being reported.
     */
    @Test
    fun `it is not the Minis-is-using vocabulary`() {
        assertEquals("browser", toolDisplayName("browser_use"))
        assertEquals("Browser use", helperToolLabel("browser_use"))
        assertEquals("terminal", toolDisplayName("shell_execute"))
        assertEquals("Shell execute", helperToolLabel("shell_execute"))
    }
}
