package com.openminis.app.ui.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-android-math-overflow-wrap] Same break-point rules as iOS MathOverflowWrapTests (7238a39d4). */
class MathLineBreakerTest {

    private fun split(s: String) = MathLineBreaker.splitAtTopLevelRelations(s)

    @Test fun `splits before each top-level relation and rejoins to the original`() {
        val f = """\text{shaded area} = 130 \times \frac{40}{169} = \frac{5200}{169} \approx 30.77"""
        val p = split(f)
        assertEquals(4, p.size)
        assertEquals(f, p.joinToString(""))
        assertTrue(p[1].startsWith("="))
        assertTrue(p[3].startsWith("""\approx"""))
    }

    @Test fun `break-point rules`() {
        assertEquals("no split inside braces", 1, split("""\frac{a=b}{c}""").size)
        assertEquals("no split inside left-right", 2, split("""\left( x = 1 \right) = y""").size)
        assertEquals("le does not match left", 1, split("""\left[ a \right]""").size)
        assertEquals("le as a relation splits", 2, split("""a \le b""").size)
        assertEquals("environments are left whole", 1, split("""\begin{aligned} a &= b \end{aligned}""").size)
        assertEquals("escaped braces keep depth", 2, split("""\{ a \} = b""").size)
        assertEquals("leading relation is not an empty piece", 1, split("= a").size)
    }

    @Test fun `rows pack greedily and a lone oversized piece gets its own row`() = runBlocking {
        val pieces = listOf("aaaa", "=bb", "=cc", "=dddddddddd")
        val rows = MathLineBreaker.packRows(pieces) { it.length <= 10 }
        assertEquals(listOf("aaaa=bb=cc", "=dddddddddd"), rows)
    }

    @Test fun `everything fits - one row`() = runBlocking {
        assertEquals(listOf("a=b=c"), MathLineBreaker.packRows(listOf("a", "=b", "=c")) { true })
    }
}
