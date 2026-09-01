package com.openminis.app.ui.chat

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-mouse-text-selection] Decision logic behind mouse-driven text
 * selection.
 *
 * The gesture handler itself needs real pointer events and so is verified on
 * device. What IS unit-testable is the two decisions it delegates:
 *
 *  - [SelectionController.selectionContains] — whether a right-click lands
 *    inside the existing selection, which decides between "act on what is
 *    highlighted" and "select the word under the cursor first". Getting this
 *    wrong silently destroys a selection the user spent effort making.
 *  - the double-click predicate — whether a press pairs with the previous one.
 *    Mirrored here, because the real one is inline in a restricted-suspend
 *    pointer scope that cannot be entered from a JVM test.
 *
 * Plus the context-menu / mouse-origin state, whose lifetimes are easy to get
 * subtly wrong (a menu that outlives its selection points at nothing).
 */
class MouseSelectionTest {

    private fun shard(message: String, index: Int) =
        TextShardId(message, "mdblock:$message:$index")

    private fun controllerWith(start: TextPosition, end: TextPosition) =
        SelectionController().apply {
            selection.value = TextSelection(start, end)
        }

    // ── selectionContains: single shard ──────────────────────────────────

    @Test
    fun `a point inside a single-shard selection is contained`() {
        val s = shard("m1", 0)
        val c = controllerWith(TextPosition(s, 5), TextPosition(s, 20))
        assertTrue(c.selectionContains(TextPosition(s, 5)))   // inclusive start
        assertTrue(c.selectionContains(TextPosition(s, 12)))
        assertTrue(c.selectionContains(TextPosition(s, 20)))  // inclusive end
    }

    @Test
    fun `a point outside a single-shard selection is not contained`() {
        val s = shard("m1", 0)
        val c = controllerWith(TextPosition(s, 5), TextPosition(s, 20))
        assertFalse(c.selectionContains(TextPosition(s, 4)))
        assertFalse(c.selectionContains(TextPosition(s, 21)))
    }

    @Test
    fun `a point in a DIFFERENT shard is not contained by a single-shard selection`() {
        // Right-clicking another paragraph must not count as "inside", or the
        // menu would act on text the user never highlighted.
        val c = controllerWith(TextPosition(shard("m1", 0), 5), TextPosition(shard("m1", 0), 20))
        assertFalse(c.selectionContains(TextPosition(shard("m1", 1), 10)))
    }

    @Test
    fun `a backwards selection still contains its interior`() {
        // Dragging right-to-left leaves start > end. selectionContains must
        // order the endpoints first, or every backwards selection would report
        // "not contained" and get destroyed by a right-click inside it.
        val s = shard("m1", 0)
        val c = controllerWith(TextPosition(s, 20), TextPosition(s, 5))
        assertTrue(c.selectionContains(TextPosition(s, 12)))
        assertFalse(c.selectionContains(TextPosition(s, 25)))
    }

    // ── selectionContains: across shards ─────────────────────────────────

    @Test
    fun `a multi-shard selection contains the tail of its first shard`() {
        val c = controllerWith(TextPosition(shard("m1", 0), 10), TextPosition(shard("m1", 2), 4))
        assertTrue(c.selectionContains(TextPosition(shard("m1", 0), 30)))
        assertFalse(c.selectionContains(TextPosition(shard("m1", 0), 3)))
    }

    @Test
    fun `a multi-shard selection contains the head of its last shard`() {
        val c = controllerWith(TextPosition(shard("m1", 0), 10), TextPosition(shard("m1", 2), 4))
        assertTrue(c.selectionContains(TextPosition(shard("m1", 2), 1)))
        assertFalse(c.selectionContains(TextPosition(shard("m1", 2), 9)))
    }

    @Test
    fun `a multi-shard selection contains any offset of a shard in between`() {
        // The middle shard is wholly covered, so offset does not matter.
        val c = controllerWith(TextPosition(shard("m1", 0), 10), TextPosition(shard("m1", 2), 4))
        assertTrue(c.selectionContains(TextPosition(shard("m1", 1), 0)))
        assertTrue(c.selectionContains(TextPosition(shard("m1", 1), 999)))
    }

    @Test
    fun `a shard outside the span is not contained`() {
        val c = controllerWith(TextPosition(shard("m1", 1), 0), TextPosition(shard("m1", 2), 4))
        assertFalse(c.selectionContains(TextPosition(shard("m1", 5), 0)))
    }

    @Test
    fun `no selection contains nothing`() {
        assertFalse(SelectionController().selectionContains(TextPosition(shard("m1", 0), 0)))
    }

    @Test
    fun `an unorderable cross-message selection reports not-contained rather than throwing`() {
        // Endpoints in different messages with no registered shards cannot be
        // ordered. The safe answer is false — the caller then selects the word
        // under the cursor, so the menu still has a visible subject.
        val c = controllerWith(TextPosition(shard("m1", 0), 0), TextPosition(shard("m2", 0), 5))
        assertFalse(c.selectionContains(TextPosition(shard("m1", 0), 2)))
    }

    // ── Context-menu state lifetime ──────────────────────────────────────

    @Test
    fun `requesting the context menu records the cursor point`() {
        val c = SelectionController()
        c.requestContextMenu(Offset(120f, 340f))
        assertEquals(Offset(120f, 340f), c.contextMenuRequest.value)
    }

    @Test
    fun `clearing the selection also retires the context menu`() {
        // Otherwise the menu would survive its own subject: every toolbar
        // action ends in clearSelection(), so without this the bar would stay
        // pinned at the cursor with nothing selected.
        val s = shard("m1", 0)
        val c = controllerWith(TextPosition(s, 0), TextPosition(s, 5))
        c.requestContextMenu(Offset(10f, 10f))
        c.clearSelection()
        assertNull(c.contextMenuRequest.value)
        assertNull(c.selection.value)
    }

    @Test
    fun `dismissing the menu leaves the selection intact`() {
        // Clicking away from the menu retires the menu only — the highlight
        // stays, so a second right-click can still act on it.
        val s = shard("m1", 0)
        val c = controllerWith(TextPosition(s, 0), TextPosition(s, 5))
        c.requestContextMenu(Offset(10f, 10f))
        c.dismissContextMenu()
        assertNull(c.contextMenuRequest.value)
        assertEquals(TextSelection(TextPosition(s, 0), TextPosition(s, 5)), c.selection.value)
    }

    @Test
    fun `mouse-origin defaults to false so touch selections keep their handles`() {
        assertFalse(SelectionController().selectionFromMouse.value)
    }

    // ── Double-click predicate ───────────────────────────────────────────
    //
    // Mirrors the inline check in handleMouseGesture. Kept in sync by hand;
    // the real one lives in a restricted-suspend scope a JVM test can't enter.

    private fun isDoubleClick(
        downUptimeMs: Long,
        downPoint: Offset,
        lastClickUptimeMs: Long,
        lastClickPoint: Offset,
        doubleTapTimeoutMs: Long = 300,
        touchSlopPx: Float = 8f,
    ): Boolean = lastClickUptimeMs != 0L &&
        (downUptimeMs - lastClickUptimeMs) <= doubleTapTimeoutMs &&
        (downPoint - lastClickPoint).getDistance() <= touchSlopPx * 2

    @Test
    fun `a prompt second click at the same spot is a double-click`() {
        assertTrue(isDoubleClick(1100, Offset(50f, 50f), 1000, Offset(50f, 50f)))
    }

    @Test
    fun `a slow second click is not a double-click`() {
        assertFalse(isDoubleClick(1400, Offset(50f, 50f), 1000, Offset(50f, 50f)))
    }

    @Test
    fun `a prompt second click far away is not a double-click`() {
        // Two quick clicks on different words are two selections, not one
        // word-select — distance is what separates them.
        assertFalse(isDoubleClick(1100, Offset(300f, 50f), 1000, Offset(50f, 50f)))
    }

    @Test
    fun `a small wobble between clicks still counts as a double-click`() {
        // A real mouse drifts a pixel or two between presses; requiring an
        // exact match would make double-click unreliable.
        assertTrue(isDoubleClick(1100, Offset(56f, 54f), 1000, Offset(50f, 50f)))
    }

    @Test
    fun `there is no previous click to pair with at startup`() {
        // Sentinel 0: the very first click of a session must not pair with
        // "time zero", which a bare timeout comparison would happily accept.
        assertFalse(isDoubleClick(100, Offset(50f, 50f), 0L, Offset.Zero))
    }
}
