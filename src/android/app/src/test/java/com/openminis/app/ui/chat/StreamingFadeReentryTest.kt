package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-stream-fade-reentry] Text that was already on screen faded in
 * again from alpha 0 — on re-entering a chat whose reply was still streaming,
 * and while the session was still processing.
 *
 * Every such replay is the same event: the streaming text node was composed
 * from scratch (re-entry, the row leaving the viewport and coming back or
 * being handed to another row by LazyColumn's reuse pool, the fragment
 * switching between its frozen and live branches) and its new
 * [FadeController] was born with fade on. Born-with-fade meant "a new
 * paragraph", so all of its first text faded. [FadeShownText] now remembers
 * what each fragment has shown, outside composition, and [fadeBirthPlan]
 * decides from that.
 */
class StreamingFadeReentryTest {

    @After
    fun tearDown() = FadeShownText.clearForTests()

    private fun activeRanges(c: FadeController): List<IntRange> {
        val out = mutableListOf<IntRange>()
        c.forEachActive { s, e, _ -> out += s until e }
        return out
    }

    /** What MdText does with a fresh controller. */
    private fun birth(key: String, fadeFromBirth: Boolean, text: String): FadeController {
        val c = FadeController()
        when (val plan = fadeBirthPlan(fadeFromBirth, FadeShownText.get(key), text)) {
            FadeBirth.SeedAll -> c.seed(text)
            is FadeBirth.SeedPrefix -> { c.seed(plan.shown); c.ingest(text) }
            FadeBirth.FadeAll -> c.ingest(text)
        }
        FadeShownText.put(key, text)
        return c
    }

    // ---- fadeBirthPlan ----------------------------------------------------

    @Test
    fun `a genuinely new paragraph still fades all of its first text`() {
        assertEquals(FadeBirth.FadeAll, fadeBirthPlan(true, emptyList(), "hello world"))
        // An earlier paragraph of the same fragment is not a prefix of it.
        assertEquals(FadeBirth.FadeAll, fadeBirthPlan(true, listOf("First paragraph."), "Second"))
    }

    @Test
    fun `fade switched on after birth keeps the existing seed behaviour`() {
        assertEquals(FadeBirth.SeedAll, fadeBirthPlan(false, emptyList(), "anything"))
    }

    @Test
    fun `recreated with exactly what it showed - nothing fades`() {
        assertEquals(FadeBirth.SeedAll, fadeBirthPlan(true, listOf("hello world"), "hello world"))
    }

    @Test
    fun `recreated after more text arrived - only the new part fades`() {
        assertEquals(
            FadeBirth.SeedPrefix("hello world"),
            fadeBirthPlan(true, listOf("hello world"), "hello world and more"),
        )
    }

    @Test
    fun `the longest matching recorded text wins`() {
        assertEquals(
            FadeBirth.SeedPrefix("Intro, then"),
            fadeBirthPlan(true, listOf("Other block", "Intro", "Intro, then"), "Intro, then more"),
        )
    }

    @Test
    fun `an empty record never counts as shown`() {
        assertEquals(FadeBirth.FadeAll, fadeBirthPlan(true, listOf(""), "text"))
    }

    // ---- the replay, end to end through the real controller --------------

    @Test
    fun `a node rebuilt mid-stream does not re-fade what the user already read`() {
        val key = "assistant_1/mdblock:text_0_0:3"
        val first = birth(key, fadeFromBirth = true, text = "Compose skips")
        assertEquals("the first birth is a real new paragraph", 2, activeRanges(first).size)
        first.ingest("Compose skips unchanged")
        FadeShownText.put(key, "Compose skips unchanged")

        // Row scrolled out and back / chat re-entered / branch rebuilt.
        val rebuilt = birth(key, fadeFromBirth = true, text = "Compose skips unchanged calls")
        val ranges = activeRanges(rebuilt)
        assertEquals("only the word that arrived meanwhile fades", 1, ranges.size)
        assertTrue(ranges.none { 0 in it })
        assertEquals("Compose skips unchanged".length + 1, ranges.single().first)
    }

    @Test
    fun `rebuilt with no new text - nothing fades at all`() {
        val key = "m/text:t"
        birth(key, fadeFromBirth = true, text = "Already on screen")
        val rebuilt = birth(key, fadeFromBirth = true, text = "Already on screen")
        assertFalse(rebuilt.hasActiveRanges)
    }

    @Test
    fun `a sibling paragraph in the same fragment still fades in`() {
        // Several MdTexts of one fragment share its key: a list and the
        // paragraph after it, say. The second one is new and must fade.
        val key = "m/mdblock:b:0"
        birth(key, fadeFromBirth = true, text = "- item one")
        val next = birth(key, fadeFromBirth = true, text = "Then a paragraph")
        assertTrue(next.hasActiveRanges)
    }

    // ---- FadeShownText ----------------------------------------------------

    @Test
    fun `growing text replaces its own entry instead of piling up`() {
        FadeShownText.put("k", "a")
        FadeShownText.put("k", "ab")
        FadeShownText.put("k", "abc")
        assertEquals(listOf("abc"), FadeShownText.get("k"))
        // A stale shorter recomposition does not shrink it.
        FadeShownText.put("k", "ab")
        assertEquals(listOf("abc"), FadeShownText.get("k"))
    }

    @Test
    fun `different texts in one fragment are kept, newest first, bounded`() {
        for (i in 1..10) FadeShownText.put("k", "paragraph $i.")
        val got = FadeShownText.get("k")
        assertEquals("paragraph 10.", got.first())
        assertTrue("bounded per fragment", got.size in 1..6)
    }

    @Test
    fun `fragments are LRU-bounded`() {
        for (i in 0 until 200) FadeShownText.put("m/$i", "t$i")
        assertTrue(FadeShownText.get("m/0").isEmpty())
        assertEquals(listOf("t199"), FadeShownText.get("m/199"))
    }

    @Test
    fun `empty text is never recorded`() {
        FadeShownText.put("k", "")
        assertTrue(FadeShownText.get("k").isEmpty())
    }

    // ---- wiring -----------------------------------------------------------

    @Test
    fun `MdText plans every fading birth and records what it shows under the fragment key`() {
        val src = ProductionSources.read("ui/chat/StreamingMarkdownText.kt")
        val body = src.substringAfter("val fadeFromBirth = remember { fadeEnabled }")
            .substringBefore("FadeFrameDriver(fadeController)")
        // Keyed by the fragment's base shard id, NOT the per-node sub-indexed
        // one: the sub-index is handed out in composition order and can change
        // exactly when the node is rebuilt.
        assertTrue(body.contains("val fadeKey = baseShardId?.let"))
        assertTrue(body.contains("fadeBirthPlan(fadeFromBirth, fadeKey?.let { FadeShownText.get(it) }"))
        assertTrue(body.contains("FadeShownText.put(fadeKey, text.text)"))
        // The only unplanned first ingest would be the replay itself.
        assertFalse(body.contains("if (!fadeFromBirth && !fadeController.hasSeenText)"))
    }
}
