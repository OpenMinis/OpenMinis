package com.openminis.app.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-draft-placeholder-row] A draft chat is identifiable from its id
 * alone, and never collides with a persisted one.
 *
 * This underpins the synthetic "New Chat / No messages yet" row: the list
 * shows the placeholder exactly while the detail pane holds an id that has no
 * database row yet. Get the predicate wrong in either direction and the
 * failure is user-visible — a missing row for a real draft, or a ghost row
 * that never clears.
 *
 * The placeholder is deliberately a view-only construct. A session reaches the
 * database only via `ChatViewModel.ensureSession()` on the first send, so an
 * abandoned draft needs no cleanup: it stops being passed to the list and the
 * row disappears.
 */
class DraftSessionIdTest {

    @Test
    fun `a freshly minted draft id is recognised as a draft`() {
        assertTrue(isDraftSessionId(newDraftSessionId()))
    }

    @Test
    fun `a persisted UUID is not a draft`() {
        // Real ids are bare UUIDs, as created by ChatRepository.createSession.
        assertFalse(isDraftSessionId("1a6577c8-9ee6-4183-9cb7-8cd0995eb8d8"))
    }

    @Test
    fun `null is not a draft`() {
        // The detail pane holds null before anything is selected.
        assertFalse(isDraftSessionId(null))
    }

    @Test
    fun `an empty id is not a draft`() {
        assertFalse(isDraftSessionId(""))
    }

    @Test
    fun `each draft id is unique`() {
        // Two New Chats in a row must not share a row or a ViewModel.
        val ids = List(50) { newDraftSessionId() }
        assertEquals50Distinct(ids)
    }

    private fun assertEquals50Distinct(ids: List<String>) {
        assertTrue("draft ids must be unique", ids.toSet().size == ids.size)
    }

    @Test
    fun `a draft id does not look like a persisted id`() {
        // Guards the alias mechanism: ChatViewModelStore.rename maps a draft id
        // onto a real one, so the two spaces must stay disjoint.
        val draft = newDraftSessionId()
        assertNotEquals(draft, draft.removePrefix("__new__"))
        assertFalse(isDraftSessionId(draft.removePrefix("__new__")))
    }

    @Test
    fun `an id merely containing the marker is not a draft`() {
        // Only a PREFIX counts — a persisted session whose id happened to
        // contain the marker must not be mistaken for an unsaved draft and
        // hidden behind a placeholder row.
        assertFalse(isDraftSessionId("abc__new__def"))
    }
}
