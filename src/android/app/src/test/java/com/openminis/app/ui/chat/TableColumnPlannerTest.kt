package com.openminis.app.ui.chat

import com.openminis.app.ui.chat.TableColumnPlanner.Column
import com.openminis.app.ui.chat.TableColumnPlanner.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * [T-android-table-width-by-content] Port of iOS TableColumnPlannerTests
 * (b3ec29cee). `table_planner_golden_ios.txt` is the output of the SHIPPING
 * iOS planner (compiled out of SelectableMarkdownView.swift) for the iOS
 * test's tables and widths; Android must produce the same widths and mode.
 */
class TableColumnPlannerTest {

    private fun cols(vararg c: Pair<Int, Int>) = c.map { Column(it.first.toFloat(), it.second.toFloat()) }

    private val tables = mapOf(
        "A" to cols(180 to 0, 1000 to 0, 950 to 0),
        "B" to cols(120 to 0, 3400 to 0),
        "C" to cols(78 to 0, 78 to 0, 78 to 0),
        "D" to cols(180 to 120, 900 to 140, 860 to 110),
        "W5" to cols(90 to 0, 400 to 0, 400 to 0, 500 to 0, 500 to 0),
        "OW" to cols(60 to 0, 700 to 0, 700 to 0),
        "SHOT" to cols(232 to 80, 600 to 115),
    )

    private fun streaming(): Map<String, TableColumnPlanner.Plan> {
        val p = TableColumnPlanner
        val s1 = p.plan(cols(90 to 0, 90 to 0, 90 to 0), 381f)
        val s2 = p.applyingStreamingFloor(p.plan(cols(200 to 0, 500 to 0, 300 to 0), 381f), s1.widths, 381f)
        val s3 = p.applyingStreamingFloor(p.plan(cols(200 to 0, 520 to 0, 600 to 0), 381f), s2.widths, 381f)
        val t1 = p.plan(cols(100 to 0, 300 to 0, 90 to 0), 381f)
        val t2 = p.applyingStreamingFloor(p.plan(cols(100 to 0, 300 to 0, 240 to 0), 381f), t1.widths, 381f)
        val u1 = p.plan(cols(500 to 0, 500 to 0, 500 to 0), 381f)
        val u2 = p.applyingStreamingFloor(p.plan(cols(500 to 0, 200 to 0, 900 to 0), 381f), u1.widths, 381f)
        return mapOf("S1" to s1, "S2" to s2, "S3" to s3, "T1" to t1, "T2" to t2, "U1" to u1, "U2" to u2)
    }

    @Test fun `every iOS golden case matches - widths and mode`() {
        val golden = javaClass.classLoader!!.getResource("table_planner_golden_ios.txt")!!.readText()
            .lines().filter { it.isNotBlank() }
        assertTrue(golden.size >= 56)
        val stream = streaming()
        for (line in golden) {
            val (name, widths, table, mode) = line.split("|")
            val plan = if ("@" in name) {
                val (t, w) = name.split("@")
                TableColumnPlanner.plan(tables.getValue(t), w.toFloat())
            } else stream.getValue(name)
            val want = widths.split(",").map { it.toFloat() }
            assertEquals("$name mode", mode.uppercase(), plan.mode.name)
            assertEquals("$name count", want.size, plan.widths.size)
            want.forEachIndexed { i, v -> assertEquals("$name[$i] ${plan.widths.toList()}", v, plan.widths[i], 0.05f) }
            assertEquals("$name table", table.toFloat(), plan.tableWidth, 0.05f)
        }
    }

    @Test fun `the screenshot table fits on a phone with the status column clearly wider`() {
        val p = TableColumnPlanner.plan(tables.getValue("SHOT"), 369f)
        assertTrue(p.mode != Mode.SCROLLS)
        assertTrue(p.widths[1] >= 1.3f * p.widths[0])
        assertTrue(p.widths[0] >= 129f)
    }

    @Test fun `property - 20000 random tables keep the iOS invariants`() {
        var seed = 0x5eedL
        fun rnd(lo: Double, hi: Double): Float {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return (lo + (hi - lo) * (seed ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat()
        }
        var monotonic = 0; var fills = 0; var bounds = 0; var peek = 0; var fit = 0; var scroll = 0
        repeat(20000) {
            val n = rnd(1.0, 7.0).toInt()
            val w = rnd(260.0, 1200.0)
            val c = List(n) {
                val natural = rnd(40.0, if (rnd(0.0, 1.0) < 0.5) 400.0 else 3000.0)
                val word = if (rnd(0.0, 1.0) < 0.3) min(natural, rnd(40.0, 400.0)) else 0f
                Column(natural, word)
            }
            val p = TableColumnPlanner.plan(c, w)
            val nat = c.map { max(min(80f, w), ceil(it.natural)) }
            val hard = c.map { max(min(80f, w), min(ceil(it.minUnbreakable), floor(w * 0.7f))) }
            for (i in 0 until n) for (j in 0 until n) {
                if (nat[i] >= nat[j] && p.widths[i] < p.widths[j] - 0.05f && p.widths[j] > hard[j] + 0.05f) monotonic++
            }
            for (i in 0 until n) {
                if (p.widths[i] < min(nat[i], hard[i]) - 0.05f ||
                    (p.mode == Mode.SCROLLS && p.widths[i] > max(nat[i], hard[i]) + 0.05f)) bounds++
            }
            if (p.mode != Mode.SCROLLS) {
                fit++
                if (abs(p.widths.sum() - w) > 0.05f) fills++
            } else {
                scroll++
                var x = 0f
                for (k in 0 until n) {
                    if (x + p.widths[k] > w + 0.01f) {
                        val comfortPrev = min(nat[(k - 1).coerceAtLeast(0)], max(hard[(k - 1).coerceAtLeast(0)], floor(w * 0.35f)))
                        val lessText = p.widths.indices.filter { it != k - 1 && k > 0 && nat[it] <= nat[k - 1] }
                            .maxOfOrNull { p.widths[it] } ?: 0f
                        if (w - x < 23.99f && k > 0 && p.widths[k - 1] > max(comfortPrev, lessText) + 0.05f) peek++
                        break
                    }
                    x += p.widths[k]
                }
            }
        }
        assertEquals("monotonic", 0, monotonic)
        assertEquals("fills", 0, fills)
        assertEquals("bounds", 0, bounds)
        assertEquals("peek", 0, peek)
        assertTrue("both regimes exercised fit=$fit scroll=$scroll", fit > 1000 && scroll > 1000)
    }

    @Test fun `android hardMin raises a formula column's hard minimum`() {
        // The inline-formula slot (290) cannot wrap; without hardMin the
        // wrapped plan would give this column less than its formula.
        val cols = listOf(Column(90f, 0f), Column(300f, 0f, hardMin = 290f), Column(1000f, 0f))
        val p = TableColumnPlanner.plan(cols, 381f)
        assertTrue(p.widths.toList().toString(), p.widths[1] >= 290f)
        val without = TableColumnPlanner.plan(cols.map { it.copy(hardMin = 0f) }, 381f)
        assertTrue(without.widths[1] < 290f)
    }

    @Test fun `hardMin of zero changes nothing`() {
        val t = tables.getValue("D")
        assertEquals(TableColumnPlanner.plan(t, 398f), TableColumnPlanner.plan(t.map { it.copy(hardMin = 0f) }, 398f))
    }

    @Test fun `longest unbreakable run - whitespace and CJK break`() {
        assertEquals("internationalization", TableColumnPlanner.longestUnbreakableRun("the internationalization step"))
        assertEquals("API", TableColumnPlanner.longestUnbreakableRun("调用API接口"))
        assertNull(TableColumnPlanner.longestUnbreakableRun("中文句子"))
        assertNull(TableColumnPlanner.longestUnbreakableRun("a b"))
    }

    @Test fun `empty input`() {
        assertEquals(0, TableColumnPlanner.plan(emptyList(), 381f).widths.size)
    }
}
