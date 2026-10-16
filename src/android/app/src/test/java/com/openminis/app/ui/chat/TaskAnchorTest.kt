package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-task-anchor] Pure checks for the session task anchor. The anchor is
 * re-injected every turn in the dynamic tail so the model can re-read the
 * session's original task after long tool loops; the DB copy wins over the
 * in-memory window, and the cut never splits a surrogate pair.
 */
class TaskAnchorTest {

    @Test
    fun `null when both sources are empty`() {
        assertNull(TaskAnchor.fragment(anchorFromDb = null, firstUserInWindow = null))
    }

    @Test
    fun `firstUserTextFromParts extracts the first text element`() {
        val raw = """[{"type":"text","value":"build the login flow"},{"type":"toolResult","value":{"name":"x","output":"y"}}]"""
        assertEquals("build the login flow", TaskAnchor.firstUserTextFromParts(raw))
    }

    @Test
    fun `firstUserTextFromParts skips toolResult elements`() {
        val raw = """[{"type":"toolResult","value":{"name":"x","output":"y"}},{"type":"text","value":"real user text"}]"""
        assertEquals("real user text", TaskAnchor.firstUserTextFromParts(raw))
    }

    @Test
    fun `firstUserTextFromParts skips blank text elements`() {
        val raw = """[{"type":"text","value":""},{"type":"text","value":"  "},{"type":"text","value":"task"}]"""
        assertEquals("task", TaskAnchor.firstUserTextFromParts(raw))
    }

    @Test
    fun `firstUserTextFromParts null for malformed json`() {
        assertNull(TaskAnchor.firstUserTextFromParts("{not json"))
        assertNull(TaskAnchor.firstUserTextFromParts("""{"type":"text"}"""))
        assertNull(TaskAnchor.firstUserTextFromParts(null))
        assertNull(TaskAnchor.firstUserTextFromParts(""))
        assertNull(TaskAnchor.firstUserTextFromParts("   "))
    }

    @Test
    fun `firstUserTextFromParts keeps json escapes intact`() {
        val raw = """[{"type":"text","value":"fix the \"login\" bug\nline two"}]"""
        assertEquals("fix the \"login\" bug\nline two", TaskAnchor.firstUserTextFromParts(raw))
    }

    @Test
    fun `null when both sources are blank`() {
        assertNull(TaskAnchor.fragment(anchorFromDb = "   \n  ", firstUserInWindow = " \t "))
    }

    @Test
    fun `db copy wins over in-memory window`() {
        val out = TaskAnchor.fragment(
            anchorFromDb = "build the login flow",
            firstUserInWindow = "a later message that is not the task",
        )
        assertTrue(out!!.startsWith(TaskAnchor.HEADER))
        assertTrue(out.contains("build the login flow"))
        assertFalse(out.contains("a later message"))
    }

    @Test
    fun `in-memory window used when db copy is null`() {
        val out = TaskAnchor.fragment(
            anchorFromDb = null,
            firstUserInWindow = "fix the crash on startup",
        )
        assertEquals(TaskAnchor.HEADER + "\nfix the crash on startup", out)
    }

    @Test
    fun `blank db copy falls through to in-memory window`() {
        val out = TaskAnchor.fragment(
            anchorFromDb = "  ",
            firstUserInWindow = "fix the crash on startup",
        )
        assertEquals(TaskAnchor.HEADER + "\nfix the crash on startup", out)
    }

    @Test
    fun `long anchor clipped to cap with ellipsis`() {
        val long = "x".repeat(TaskAnchor.MAX_CHARS + 500)
        val out = TaskAnchor.fragment(anchorFromDb = long, firstUserInWindow = null)!!
        val body = out.removePrefix(TaskAnchor.HEADER + "\n")
        assertTrue(body.endsWith("…"))
        // Body = 1200 code units + the ellipsis, no more.
        assertEquals(TaskAnchor.MAX_CHARS + 1, body.length)
    }

    @Test
    fun `anchor at exactly the cap is not clipped`() {
        val exact = "y".repeat(TaskAnchor.MAX_CHARS)
        val out = TaskAnchor.fragment(anchorFromDb = exact, firstUserInWindow = null)!!
        val body = out.removePrefix(TaskAnchor.HEADER + "\n")
        assertEquals(exact, body)
    }

    @Test
    fun `clip never splits a surrogate pair`() {
        // Leading ASCII shifts the pairs by one: the 1200-unit cut lands
        // between the last pair's halves, so the dangling high is trimmed.
        val emoji = "x" + "😀".repeat(600)
        assertEquals(1201, emoji.length)
        val out = TaskAnchor.fragment(anchorFromDb = emoji, firstUserInWindow = null)!!
        val body = out.removePrefix(TaskAnchor.HEADER + "\n")
        // The dangling high surrogate was trimmed: no lone surrogate remains.
        assertFalse(Character.isHighSurrogate(body[body.length - 2]))
        assertFalse(Character.isLowSurrogate(body.last()))
        // Whole code points only.
        assertTrue(body.dropLast(1).codePointCount(0, body.dropLast(1).length) >= 598)
    }

    @Test
    fun `multi-line anchor keeps its newlines`() {
        val multi = "step one\nstep two\nstep three"
        val out = TaskAnchor.fragment(anchorFromDb = multi, firstUserInWindow = null)
        assertEquals(TaskAnchor.HEADER + "\n" + multi, out)
    }

    @Test
    fun `dynamic tail places anchor before runtime context`() {
        val out = assembleDynamicTail(
            worldBookFragment = "WORLD",
            learnedPrefsFragment = null,
            recalledMemoryFragment = null,
            runtimeContext = "RUNTIME",
            personalityReminder = null,
            taskAnchor = TaskAnchor.fragment("the task", null),
        )
        val anchorIdx = out.indexOf(TaskAnchor.HEADER)
        val runtimeIdx = out.indexOf("RUNTIME")
        assertTrue(anchorIdx in 0 until runtimeIdx)
        assertTrue(out.indexOf("WORLD") < anchorIdx)
    }

    @Test
    fun `dynamic tail without anchor is byte-identical to the legacy shape`() {
        val withParam = assembleDynamicTail(
            worldBookFragment = "WORLD",
            learnedPrefsFragment = "PREFS",
            recalledMemoryFragment = "RECALL",
            runtimeContext = "RUNTIME",
            personalityReminder = "PERSONA",
            taskAnchor = null,
        )
        val legacy = buildString {
            append("WORLD")
            append("\n\n"); append("PREFS")
            append("\n\n"); append("RECALL")
            append("RUNTIME")
            append("\n\n"); append("PERSONA")
        }
        assertEquals(legacy, withParam)
    }
}
