package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-selection-offscreen-order] (GH#296) A long-press selection whose
 * handle is dragged past one screen auto-scrolls its START out of the list.
 * The start shard unregisters; the highlight of every paragraph in between
 * vanished and a copy returned only the tail of the last shard.
 *
 * Exercised through the real SelectionController via its ShardText seam
 * (no Compose TextLayoutResult needed).
 */
class SelectionOffscreenOrderTest {

    // Real chat shard ids: MdText appends "#<subIndex>" to the row's base id.
    private fun md(msg: String, parent: String, index: Int, sub: Int = 0) =
        TextShardId(msg, "mdblock:$parent:$index#$sub")
    private fun rowKey(msg: String, parent: String, index: Int) = "mdblock:$msg:$parent:$index"

    private val m1 = "M1"
    private val m2 = "M2"
    private val a = md(m1, "p1", 0)
    private val b = md(m1, "p1", 1)
    private val c = md(m1, "p1", 2)
    private val d = md(m2, "q1", 0)

    /** Flattened rows, oldest first, as ChatScreen's flatRowIndexByKey holds them. */
    private val rows = mapOf(
        rowKey(m1, "p1", 0) to 1,
        rowKey(m1, "p1", 1) to 2,
        rowKey(m1, "p1", 2) to 3,
        rowKey(m2, "q1", 0) to 6,
    )
    private val text = mapOf(a to "Alpha first paragraph", b to "Bravo middle", c to "Charlie last one", d to "Delta next reply")
    private val y = mutableMapOf(a to 0f, b to 100f, c to 200f, d to 300f)

    private fun controller(withDocOrder: Boolean = true) = SelectionController().apply {
        if (withDocOrder) documentOrder = chatDocumentOrder { rows }
        for (id in listOf(a, b, c, d)) registerText(ShardText(id, text.getValue(id), null) { y[id] })
    }

    private fun SelectionController.select(from: TextPosition, to: TextPosition) {
        beginSelection(from)
        replaceEnd(to)
    }

    // 1. Regression witness ------------------------------------------------------

    @Test
    fun `the old order key could not parse a chat shard id`() {
        // shardOrderKey's rule, verbatim: the int after the last ':'.
        fun oldKey(s: String) = s.substring(s.lastIndexOf(':') + 1).toIntOrNull()
        assertNull("since the #subIndex suffix (958ff9290) the old key is always null", oldKey(a.shardId))
        assertEquals(rowKey(m1, "p1", 0), chatRowKeyForShard(a))
        assertEquals(3, shardSubIndex("mdblock:p1:0#3"))
        assertEquals("text:M1:blk-9", chatRowKeyForShard(TextShardId(m1, "text:blk-9#2")))
        assertEquals("legacy:M1", chatRowKeyForShard(TextShardId(m1, "legacy#0")))
        assertNull(chatRowKeyForShard(TextShardId(m1, "something-else")))
    }

    // 2. Ordering with the start off-screen ------------------------------------

    @Test
    fun `endpoints and middle shards still order after the start scrolled away`() {
        val ctl = controller()
        ctl.select(TextPosition(a, 6), TextPosition(c, 7))
        ctl.unregister(a) // auto-scroll pushed the start out of the list
        val (first, last) = ctl.orderedEndpoints(ctl.selection.value!!)!!
        assertEquals(a, first.shard)
        assertEquals(c, last.shard)
        assertTrue("the middle paragraph stays highlighted", ctl.isShardBetween(first.shard, last.shard, b))
        assertTrue(ctl.selectionContains(TextPosition(b, 3)))
    }

    @Test
    fun `dragging the start handle upward past the end reverses cleanly`() {
        val ctl = controller()
        ctl.select(TextPosition(c, 7), TextPosition(a, 6)) // start below end
        ctl.unregister(c)
        val (first, last) = ctl.orderedEndpoints(ctl.selection.value!!)!!
        assertEquals(a, first.shard)
        assertEquals(c, last.shard)
    }

    // 3. Copy with the start off-screen ------------------------------------------

    @Test
    fun `copy returns the whole span, not only the tail of the last shard`() {
        val ctl = controller()
        ctl.select(TextPosition(a, 6), TextPosition(c, 7))
        ctl.unregister(a)
        assertEquals("first paragraph\nBravo middle\nCharlie", ctl.selectedPlainText())
    }

    @Test
    fun `copy survives BOTH the start and a middle shard scrolling away`() {
        val ctl = controller()
        ctl.select(TextPosition(a, 6), TextPosition(c, 7))
        ctl.unregister(a)
        ctl.unregister(b)
        assertEquals("first paragraph\nBravo middle\nCharlie", ctl.selectedPlainText())
    }

    // 4. Across messages ----------------------------------------------------------

    @Test
    fun `a selection spanning two replies orders and copies across them`() {
        val ctl = controller()
        ctl.select(TextPosition(b, 6), TextPosition(d, 5))
        ctl.unregister(b)
        ctl.unregister(c)
        assertTrue(ctl.isShardBetween(b, d, c))
        assertEquals("middle\nCharlie last one\nDelta", ctl.selectedPlainText())
    }

    // 5. Same message, different parent blocks ----------------------------------

    @Test
    fun `blocks of different parents order by row, not by their own index`() {
        // p2 comes AFTER p1 in the reply but its block index (0) is lower
        // than p1's (5): the old index-only key would have put it first.
        val late = md(m1, "p2", 0)
        val early = md(m1, "p1", 5)
        val ctl = SelectionController().apply {
            documentOrder = chatDocumentOrder { mapOf(rowKey(m1, "p1", 5) to 10, rowKey(m1, "p2", 0) to 12) }
            registerText(ShardText(early, "early", null) { null })
            registerText(ShardText(late, "late", null) { null })
        }
        assertTrue(ctl.compareShards(early, late)!! < 0)
    }

    @Test
    fun `sub-shards of one row order by their sub-index`() {
        val ctl = SelectionController().apply { documentOrder = chatDocumentOrder { mapOf(rowKey(m1, "p1", 0) to 4) } }
        assertTrue(ctl.compareShards(md(m1, "p1", 0, sub = 1), md(m1, "p1", 0, sub = 2))!! < 0)
    }

    // 6. retired lifecycle (no leak) ------------------------------------------

    @Test
    fun `shards are only kept while a selection is active, and are dropped with it`() {
        val ctl = controller()
        ctl.unregister(d)
        assertEquals("no selection: nothing retained", 0, ctl.retiredCount())

        ctl.select(TextPosition(a, 0), TextPosition(c, 3))
        ctl.unregister(a)
        ctl.unregister(b)
        assertEquals(2, ctl.retiredCount())

        ctl.registerText(ShardText(b, text.getValue(b), null) { y[b] }) // scrolled back in
        assertEquals("a re-registered shard leaves the retired set", 1, ctl.retiredCount())

        ctl.clearSelection()
        assertEquals(0, ctl.retiredCount())

        ctl.select(TextPosition(c, 0), TextPosition(c, 3))
        ctl.unregister(c)
        ctl.beginSelection(TextPosition(b, 0)) // a new selection starts clean
        assertEquals(0, ctl.retiredCount())
    }

    // 7. Unknown order never inverts ------------------------------------------

    @Test
    fun `order that cannot be decided is reported as unknown, not guessed`() {
        // No document order, nothing registered, and the shards sit in
        // different messages, so not even the same-block index applies.
        val ctl = SelectionController()
        assertNull(ctl.orderedEndpoints(TextSelection(TextPosition(a, 0), TextPosition(d, 1))))
        assertFalse(ctl.isShardBetween(a, d, b))
    }

    @Test
    fun `without document order, shards of one block still order by their embedded index`() {
        // The shardOrderKey fallback MouseSelectionTest relies on, now #sub-aware.
        val ctl = SelectionController()
        assertTrue(ctl.compareShards(md(m1, "p1", 0, sub = 2), md(m1, "p1", 1, sub = 0))!! < 0)
        assertTrue(ctl.compareShards(md(m1, "p1", 1, sub = 0), md(m1, "p1", 1, sub = 3))!! < 0)
        assertNull("different parents are not comparable by index", sameBlockOrder(md(m1, "p1", 5), md(m1, "p2", 0)))
    }

    @Test
    fun `the highlight fallback keeps the user's direction for cross-shard pairs`() {
        val src = File("src/main/java/com/openminis/app/ui/chat/MinisTextKitSelection.kt").readText()
        val fallback = src.substringAfter("fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelectionForShard(")
            .substringBefore("val (first, last) = ordered")
        assertTrue(fallback.contains("if (a.shard == b.shard && a.charOffset > b.charOffset) b to a else a to b"))
    }

    // 8. Surfaces without document order keep y ordering ---------------------

    @Test
    fun `without document order the y ordering of old still applies`() {
        val ctl = controller(withDocOrder = false)
        ctl.select(TextPosition(c, 7), TextPosition(a, 6))
        val (first, last) = ctl.orderedEndpoints(ctl.selection.value!!)!!
        assertEquals(a, first.shard)
        assertEquals(c, last.shard)
        assertEquals("first paragraph\nBravo middle\nCharlie", ctl.selectedPlainText())
    }

    @Test
    fun `a single-shard selection is still an exact substring`() {
        val ctl = controller()
        ctl.select(TextPosition(b, 0), TextPosition(b, 5))
        assertEquals("Bravo", ctl.selectedPlainText())
    }

    // Row keys are the chat's real ones ---------------------------------------------

    @Test
    fun `row key shapes match FlatChatItem keys`() {
        val flat = File("src/main/java/com/openminis/app/ui/chat/ChatFlatItems.kt").readText()
        assertTrue(flat.contains("override val key = \"text:\$messageId:\${block.id}\""))
        assertTrue(flat.contains("override val key = \"mdblock:\$messageId:\$parentBlockId:\$blockIndex\""))
        assertTrue(flat.contains("override val key = \"legacy:\$messageId\""))
        val screen = File("src/main/java/com/openminis/app/ui/chat/ChatScreen.kt").readText()
        assertTrue(screen.contains("shardId = \"text:\${item.block.id}\""))
        assertTrue(screen.contains("shardId = \"mdblock:\${item.parentBlockId}:\${item.blockIndex}\""))
        assertTrue(screen.contains("selectionController.documentOrder = chatDocumentOrder { flatRowIndexByKey.value }"))
    }
}
