package com.openminis.app.speech.correction

import com.openminis.app.speech.correction.ScreenContextBuilder.Kind
import com.openminis.app.speech.correction.ScreenContextBuilder.Segment
import com.openminis.app.speech.correction.ScreenContextBuilder.Snapshot
import com.openminis.app.speech.correction.ScreenContextBuilder.VisibleItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-voice-viewport-context] The on-screen correction context: rows
 * are taken from the viewport centre outwards over two screen-heights, packed
 * nearest-first into 2000 chars, and the newest reply appears exactly once.
 *
 * Geometry mirrors the chat list: reverseLayout, so the newest row sits at
 * offset 0 and older rows at larger offsets. Viewport is 0..1000 px.
 */
class ScreenContextBuilderTest {

    private val viewport = 1000

    /** 30 rows, chronological; row i belongs to message "m$i". */
    private fun rows(n: Int = 30, text: (Int) -> String = { "第${it}条消息内容" }): List<Segment> =
        (0 until n).map { i ->
            Segment("k$i", "m$i", if (i % 2 == 0) Kind.USER else Kind.ASSISTANT, text(i))
        }

    /**
     * Lay out [visibleIdx] (chronological indices) reverse-style, each row
     * [rowPx] tall, the newest visible row starting at [bottomOffset].
     */
    private fun snapshot(
        segments: List<Segment>,
        visibleIdx: IntRange,
        rowPx: Int = 200,
        bottomOffset: Int = 0,
    ): Snapshot {
        val visible = visibleIdx.reversed().mapIndexed { n, i ->
            VisibleItem("k$i", bottomOffset + n * rowPx, rowPx)
        }
        return Snapshot(segments, visible, 0, viewport, olderAtLargerOffsets = true)
    }

    // ── Window selection ──────────────────────────────────────────────────

    @Test
    fun `the row at the viewport centre comes first, then outward`() {
        val segs = rows()
        // Rows 10..14 fill 0..1000; row 12 spans 400..600, the centre.
        val (picks, _) = ScreenContextBuilder.selectWindow(snapshot(segs, 10..14), null)
        assertEquals(12, picks.first().index)
        val firstFive = picks.take(5).map { it.index }.toSet()
        assertTrue("the two neighbours on each side follow", firstFive.containsAll(listOf(10, 11, 13, 14)))
        // Distances never decrease along the list.
        val d = picks.map { kotlin.math.abs(it.distance) }
        assertEquals(d.sorted(), d)
    }

    @Test
    fun `the window is the viewport plus half a screen each side, no more`() {
        val segs = rows()
        // Visible 10..14 (200 px each). Off-screen rows get estimated heights
        // from the visible px-per-char, so each is ~200 px too: half a screen
        // (500 px) past each edge reaches two to three more rows per side.
        val (picks, _) = ScreenContextBuilder.selectWindow(snapshot(segs, 10..14), null)
        val idx = picks.map { it.index }.toSet()
        assertTrue("rows just past both edges are in", idx.containsAll(listOf(8, 9, 15, 16)))
        assertFalse("rows well past half a screen are out", idx.any { it <= 6 || it >= 18 })
        assertTrue("the window spans about two screens", picks.all { kotlin.math.abs(it.distance) <= viewport })
    }

    @Test
    fun `older rows get negative distances and newer rows positive`() {
        val segs = rows()
        val (picks, _) = ScreenContextBuilder.selectWindow(snapshot(segs, 10..14), null)
        val byIdx = picks.associateBy { it.index }
        assertTrue(byIdx.getValue(10).distance < 0)
        assertTrue(byIdx.getValue(14).distance > 0)
    }

    @Test
    fun `a forward-layout list is read the right way round too`() {
        val segs = rows()
        // Older rows at SMALLER offsets; the flag is deliberately wrong to
        // show the direction is read from the rows themselves.
        val visible = (10..14).mapIndexed { n, i -> VisibleItem("k$i", n * 200, 200) }
        val snap = Snapshot(segs, visible, 0, viewport, olderAtLargerOffsets = true)
        val byIdx = ScreenContextBuilder.selectWindow(snap, null).first.associateBy { it.index }
        assertTrue(byIdx.getValue(10).distance < 0)
        assertTrue(byIdx.getValue(14).distance > 0)
    }

    @Test
    fun `rows without text and unknown keys are ignored, tool titles are kept`() {
        val segs = listOf(
            Segment("k0", "m0", Kind.USER, "打开设置"),
            Segment("h1", "m1", null, ""), // assistant header
            Segment("k2", "m1", Kind.TOOL, "查找存储设置"),
            Segment("k3", "m1", Kind.ASSISTANT, "已经打开了。"),
        )
        val visible = listOf(
            VisibleItem("k3", 0, 200), VisibleItem("k2", 200, 150),
            VisibleItem("h1", 350, 50), VisibleItem("k0", 400, 200),
            VisibleItem("__resume_banner__", 600, 100), // not a chat row
        )
        val (picks, _) = ScreenContextBuilder.selectWindow(Snapshot(segs, visible, 0, 1000, true), null)
        assertEquals(listOf(0, 2, 3), picks.map { it.index }.sorted())
        assertEquals(Kind.TOOL, picks.first { it.index == 2 }.kind)
    }

    @Test
    fun `nothing laid out means no viewport rows`() {
        val snap = Snapshot(rows(), emptyList(), 0, viewport, true)
        assertTrue(ScreenContextBuilder.selectWindow(snap, null).first.isEmpty())
    }

    // ── Budget ────────────────────────────────────────────────────────────

    @Test
    fun `the viewport block never exceeds 2000 chars and keeps the centre`() {
        // Long rows: each ~900 chars, so only a few fit.
        val segs = rows { i -> "行$i-" + "字".repeat(900) }
        val (picks, _) = ScreenContextBuilder.selectWindow(snapshot(segs, 10..14), null)
        val lines = ScreenContextBuilder.pack(picks, CorrectionContextBudget.SCREEN_VIEWPORT)
        val rendered = lines.joinToString("\n")
        assertTrue("≤ 2000 including separators: ${rendered.length}", rendered.length <= 2000)
        assertTrue("the centre row survives", rendered.contains("行12-"))
    }

    @Test
    fun `a cut row keeps the end nearer the centre`() {
        val long = "开头" + "中".repeat(200) + "结尾"
        val center = ScreenContextBuilder.Picked(2, Kind.USER, "中心", 0)

        // An older row (above the centre) keeps its TAIL, the part next to it.
        val older = ScreenContextBuilder.Picked(1, Kind.USER, long, -300)
        val a = ScreenContextBuilder.pack(listOf(center, older), 150)
        assertEquals(2, a.size)
        assertTrue("older keeps its tail: ${a[0]}", a[0].startsWith("【用户】…") && a[0].endsWith("结尾"))
        assertEquals("【用户】中心", a[1])
        assertTrue(a.joinToString("\n").length <= 150)

        // A newer row (below the centre) keeps its HEAD.
        val newer = ScreenContextBuilder.Picked(3, Kind.ASSISTANT, long, 300)
        val b = ScreenContextBuilder.pack(listOf(center, newer), 150)
        assertEquals("【用户】中心", b[0])
        assertTrue("newer keeps its head: ${b[1]}", b[1].startsWith("【AI】开头") && b[1].endsWith("…"))
        assertTrue(b.joinToString("\n").length <= 150)
    }

    @Test
    fun `a sliver too small to be useful is skipped, not stubbed`() {
        val a = ScreenContextBuilder.Picked(0, Kind.USER, "字".repeat(1990), 0)
        val b = ScreenContextBuilder.Picked(1, Kind.USER, "别的内容", 10)
        val lines = ScreenContextBuilder.pack(listOf(a, b), 2000)
        assertEquals(1, lines.size)
    }

    // ── Latest reply + de-duplication ─────────────────────────────────────

    @Test
    fun `the latest reply on screen appears once, in its own block`() {
        val segs = rows()
        val latest = ScreenContextBuilder.LatestReply("m13", "第13条消息内容")
        val ctx = ScreenContextBuilder.build(snapshot(segs, 10..14), latest)!!
        assertTrue(ctx.latestReplyOnScreen)
        assertFalse("not repeated in the viewport block", ctx.viewportLines.any { it.contains("第13条") })
        assertEquals("第13条消息内容", ctx.latestReply)
        val prompt = LlmCorrectionStrategy.buildPrompt("原文", emptyList(), ConversationContext(screen = ctx))
        assertEquals(1, Regex("第13条消息内容").findAll(prompt).count())
        assertTrue(prompt.contains("此刻也显示在屏幕上"))
    }

    @Test
    fun `a latest reply off screen leaves the viewport block untouched`() {
        val segs = rows()
        val latest = ScreenContextBuilder.LatestReply("m29", "最新的回复")
        val ctx = ScreenContextBuilder.build(snapshot(segs, 10..14), latest)!!
        assertFalse(ctx.latestReplyOnScreen)
        assertTrue(ctx.viewportLines.any { it.contains("第13条") })
        val prompt = LlmCorrectionStrategy.buildPrompt("原文", emptyList(), ConversationContext(screen = ctx))
        assertTrue(prompt.contains("此刻不在屏幕可见范围内"))
    }

    @Test
    fun `only the latest reply's reply text is deduplicated, not its tool titles`() {
        val segs = listOf(
            Segment("k0", "m0", Kind.USER, "查一下天气"),
            Segment("k1", "m1", Kind.TOOL, "查询杭州天气"),
            Segment("k2", "m1", Kind.ASSISTANT, "杭州今天晴。"),
        )
        val visible = listOf(VisibleItem("k2", 0, 300), VisibleItem("k1", 300, 300), VisibleItem("k0", 600, 300))
        val ctx = ScreenContextBuilder.build(
            Snapshot(segs, visible, 0, 1000, true),
            ScreenContextBuilder.LatestReply("m1", "杭州今天晴。"),
        )!!
        assertTrue(ctx.viewportLines.any { it.contains("查询杭州天气") })
        assertFalse(ctx.viewportLines.any { it.contains("杭州今天晴") })
    }

    @Test
    fun `the latest reply is capped at 2000, keeping its start and most of its end`() {
        val text = "开头" + "中".repeat(5000) + "结尾问题？"
        val capped = ScreenContextBuilder.capLatestReply(text, 2000)
        assertEquals(2000, capped.length)
        assertTrue(capped.startsWith("开头"))
        assertTrue(capped.endsWith("结尾问题？"))
        assertEquals("短回复", ScreenContextBuilder.capLatestReply("  短回复 ", 2000))
    }

    @Test
    fun `no screen and no reply means no block`() {
        assertNull(ScreenContextBuilder.build(null, null))
        val ctx = ScreenContextBuilder.build(null, ScreenContextBuilder.LatestReply("m", "回复"))!!
        assertTrue(ctx.viewportLines.isEmpty())
        assertFalse(ctx.latestReplyOnScreen)
    }

    // ── Prompt ────────────────────────────────────────────────────────────

    @Test
    fun `the prompt keeps the history block and adds the screen block after it`() {
        val ctx = ConversationContext(
            recentExcerpts = listOf("【用户·最新】最新一句"),
            screen = ScreenContext(listOf("【用户】屏幕上的话"), null, false),
        )
        val prompt = LlmCorrectionStrategy.buildPrompt("原文", emptyList(), ctx)
        val history = prompt.indexOf("以下是当前对话的最近上下文")
        val screen = prompt.indexOf("以下是用户说话时屏幕上可见的对话内容")
        assertTrue("both blocks present", history >= 0 && screen > history)
        assertTrue(prompt.contains("尤其是用户说话时屏幕上可见的对话内容"))
        assertTrue(prompt.endsWith("原文：原文"))
    }

    @Test
    fun `without a screen block the prompt is unchanged`() {
        val prompt = LlmCorrectionStrategy.buildPrompt(
            "原文", emptyList(), ConversationContext(recentExcerpts = listOf("【AI·最新】好的")),
        )
        assertFalse(prompt.contains("屏幕"))
        // [T-android-voice-mixed-term-restore] The no-screen lead now also frames
        // the transcript as a message to the AI (iOS 8622ea275).
        assertTrue(prompt.contains("请结合以上参考信息和对话上下文，推断用户这句要发给 AI 的话的整体意图，判断并修正"))
    }
}
