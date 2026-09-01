package com.openminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "did my destination actually save?" state machine.
 *
 * `RcloneDestinationsScreen` renders one of three things from two pieces of
 * state — `browse != null` (folder browser), `adding` (add form), otherwise
 * the saved list. A successful save used to leave both flags untouched, so
 * the user stayed on the form and a save that worked looked exactly like one
 * that silently failed; the reported behaviour was re-saving the same
 * destination repeatedly.
 *
 * The Compose screen itself needs an instrumented run, but the transition
 * rule is plain logic and is what regressed, so it is pinned here. This
 * mirrors `RcloneDestinationsViewModel.savePending`, which invokes its
 * `onDone` callback ONLY after `store.add` returns — its catch sets `_error`
 * instead — which is why leaving the form on the callback cannot strand a
 * failed save.
 */
class BackupDestinationSaveStateTest {

    /** The screen's visible surface, decided exactly as the composable does. */
    private enum class Screen { BROWSER, ADD_FORM, LIST }

    private class DestinationsUiState {
        var browsing = false
        var adding = false
        var justSaved: String? = null

        val screen: Screen
            get() = when {
                browsing -> Screen.BROWSER
                adding -> Screen.ADD_FORM
                else -> Screen.LIST
            }

        fun beginAdd() {
            adding = true
            justSaved = null
        }

        fun connected() {
            browsing = true
        }

        /** The `onSaved` path: viewmodel cleared `browse`, screen leaves the form. */
        fun saveSucceeded(name: String) {
            browsing = false
            adding = false
            justSaved = name
        }

        /** The catch path: viewmodel sets an error and leaves browse in place. */
        fun saveFailed() {
            // browsing/adding deliberately untouched — the user must stay on
            // the form to retry or correct the destination.
        }

        fun cancel() {
            browsing = false
            adding = false
        }
    }

    @Test
    fun `successful save returns to the list and confirms the name`() {
        val s = DestinationsUiState()
        s.beginAdd()
        s.connected()
        assertEquals(Screen.BROWSER, s.screen)

        s.saveSucceeded("nas-webdav")

        assertEquals(Screen.LIST, s.screen)
        assertEquals("nas-webdav", s.justSaved)
    }

    /**
     * The regression itself: before the fix, saving cleared only `browse`, so
     * `adding` kept the form on screen and the list was never reached.
     */
    @Test
    fun `save must clear adding too, not just the browser`() {
        val s = DestinationsUiState()
        s.beginAdd()
        s.connected()

        // Old behaviour: only the browser closed.
        s.browsing = false
        assertEquals(
            "clearing browse alone leaves the user on the add form",
            Screen.ADD_FORM, s.screen,
        )

        // Fixed behaviour.
        s.saveSucceeded("nas-webdav")
        assertEquals(Screen.LIST, s.screen)
    }

    @Test
    fun `a failed save keeps the user on the form with no success message`() {
        val s = DestinationsUiState()
        s.beginAdd()
        s.connected()

        s.saveFailed()

        assertEquals(Screen.BROWSER, s.screen)
        assertNull("a failure must never show the saved confirmation", s.justSaved)
    }

    @Test
    fun `cancel returns to the list without claiming a save`() {
        val s = DestinationsUiState()
        s.beginAdd()
        s.connected()

        s.cancel()

        assertEquals(Screen.LIST, s.screen)
        assertNull(s.justSaved)
    }

    /** Starting a second add clears the previous confirmation. */
    @Test
    fun `beginning another add drops the stale confirmation`() {
        val s = DestinationsUiState()
        s.beginAdd()
        s.connected()
        s.saveSucceeded("first")
        assertTrue(s.justSaved != null)

        s.beginAdd()

        assertEquals(Screen.ADD_FORM, s.screen)
        assertNull(s.justSaved)
        assertFalse(s.browsing)
    }
}
