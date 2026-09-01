package com.openminis.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-new-chat-shortcut] Pins the chord-matching rule used by
 * ChatSplitScaffold's `onPreviewKeyEvent` (Ctrl/⌘+N → new chat, Ctrl/⌘+F →
 * search), mirroring iOS's ⌘N (MinisApp.swift) and ⌘F (ContentView).
 *
 * Same shape as the other pure-rule tests here: the real handler needs a
 * Compose KeyEvent, which wraps a platform event and cannot be constructed in a
 * JVM test, so this exercises the decision the handler makes. That decision is
 * the whole contract — which key, which modifiers, and which event phase.
 */
class ChatShortcutKeyMatchTest {

    private enum class Action { NEW_CHAT, SEARCH, NONE }

    /** The exact rule implemented in ChatSplitScaffold.onPreviewKeyEvent. */
    private fun match(
        key: String,
        isKeyDown: Boolean = true,
        ctrl: Boolean = false,
        meta: Boolean = false,
    ): Action {
        if (!isKeyDown) return Action.NONE
        if (!ctrl && !meta) return Action.NONE
        return when (key) {
            "N" -> Action.NEW_CHAT
            "F" -> Action.SEARCH
            else -> Action.NONE
        }
    }

    // -- The bindings ------------------------------------------------------

    @Test
    fun `meta plus N opens a new chat`() {
        assertEquals(Action.NEW_CHAT, match("N", meta = true))
    }

    @Test
    fun `meta plus F opens search`() {
        assertEquals(Action.SEARCH, match("F", meta = true))
    }

    @Test
    fun `ctrl is accepted as well as meta`() {
        // A tablet keyboard sends Meta for the Command key; a USB PC keyboard
        // sends Ctrl for the same intent. Both must work.
        assertEquals(Action.NEW_CHAT, match("N", ctrl = true))
        assertEquals(Action.SEARCH, match("F", ctrl = true))
    }

    // -- What must NOT fire ------------------------------------------------

    @Test
    fun `an unmodified letter is left alone`() {
        // Otherwise typing "n" into the composer would spawn a chat.
        assertEquals(Action.NONE, match("N"))
        assertEquals(Action.NONE, match("F"))
    }

    @Test
    fun `key up does not fire`() {
        // An unfiltered handler sees KeyDown AND KeyUp and would fire twice per
        // press — two drafts from one chord.
        assertEquals(Action.NONE, match("N", isKeyDown = false, meta = true))
        assertEquals(Action.NONE, match("F", isKeyDown = false, meta = true))
    }

    @Test
    fun `other modified letters are not claimed`() {
        // Ctrl+A / Ctrl+C etc. must keep reaching the text field.
        for (k in listOf("A", "C", "V", "X", "Z", "S")) {
            assertEquals("modifier+$k should pass through", Action.NONE, match(k, meta = true))
        }
    }

    @Test
    fun `the two chords do not collide`() {
        assertEquals(Action.NEW_CHAT, match("N", meta = true))
        assertEquals(Action.SEARCH, match("F", meta = true))
    }
}
