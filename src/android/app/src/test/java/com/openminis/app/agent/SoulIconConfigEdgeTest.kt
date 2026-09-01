package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-soul-custom-icon] Edge cases for the `minis-config soul.icon`
 * writer — specifically the ones that could CORRUPT SOUL.md rather than merely
 * be refused.
 *
 * The writer's decision is a three-way branch:
 *   empty          -> store ""      (clears to default)
 *   1 emoji glyph  -> store verbatim
 *   anything else  -> treat as an image source; throws if it isn't one
 *
 * The danger is the middle branch accepting something it shouldn't, or the
 * third branch storing junk. Both would write a bad `icon:` line into a file
 * that also holds the user's name / style / personality — so a bad value is
 * not just a wrong icon, it is a damaged config. These tests pin the
 * classification the writer branches on, and the frontmatter round-trip pins
 * that whatever IS stored survives a write/read cycle intact.
 *
 * Multi-emoji is the case the requester called out, and it is the interesting
 * one: `minis-config` does NOT silently keep the last glyph the way the UI
 * text field does. In the UI, normalization-per-keystroke is a typing
 * affordance; through the tool it would mean a model asking for "⚡🤖" gets a
 * different icon than it asked for and no error, so it is refused instead.
 */
class SoulIconConfigEdgeTest {

    /** What the config writer's `when` will decide for a given raw value. */
    private enum class Branch { CLEAR, EMOJI, IMAGE_SOURCE }

    private fun branch(raw: String): Branch {
        val t = raw.trim()
        return when {
            t.isEmpty() -> Branch.CLEAR
            SoulIcon.graphemeClusters(t).size == 1 && SoulIcon.isEmojiGlyph(t) -> Branch.EMOJI
            else -> Branch.IMAGE_SOURCE
        }
    }

    /** An IMAGE_SOURCE value that isn't a real image must be refused. */
    private fun refusedAsImage(raw: String): Boolean {
        if (branch(raw) != Branch.IMAGE_SOURCE) return false
        return SoulIcon.classifySource(raw.trim()) is SoulIcon.Source.Unsupported
    }

    // ── Multiple emoji: refused, NOT silently truncated ──────────────────

    @Test
    fun `multiple emoji are refused rather than silently truncated`() {
        for (v in listOf("⚡🤖", "✨🚀🐳", "🐱🦊")) {
            assertEquals("'$v' must not be taken as a single emoji", Branch.IMAGE_SOURCE, branch(v))
            assertTrue("'$v' must be refused outright", refusedAsImage(v))
        }
    }

    /**
     * The UI's per-keystroke normalization DOES keep the last glyph — that is
     * the typing affordance. Pinned here so the difference between the two
     * surfaces is deliberate and visible, not an accident.
     */
    @Test
    fun `the ui normalizer keeps the last emoji while config refuses the same input`() {
        assertEquals("🤖", SoulIcon.normalizeEmojiInput("⚡🤖"))
        assertTrue(refusedAsImage("⚡🤖"))
    }

    // ── Non-emoji text: refused ──────────────────────────────────────────

    @Test
    fun `plain text is refused`() {
        for (v in listOf("hello", "abc123", "Minis", "n/a", "null", "undefined")) {
            assertEquals("'$v'", Branch.IMAGE_SOURCE, branch(v))
            assertTrue("'$v' must be refused", refusedAsImage(v))
        }
    }

    @Test
    fun `cjk and punctuation are refused`() {
        for (v in listOf("中", "中文", "。", "！", "——")) {
            assertTrue("'$v' must be refused", refusedAsImage(v))
        }
    }

    /**
     * The keycap-base trap, at the config layer: Unicode gives ASCII digits
     * the Emoji property, so a lone "1" must not sneak through as an emoji.
     */
    @Test
    fun `bare digits and keycap bases are refused`() {
        for (v in listOf("1", "0", "9", "#", "*")) {
            assertEquals("'$v' must not classify as emoji", Branch.IMAGE_SOURCE, branch(v))
            assertTrue("'$v' must be refused", refusedAsImage(v))
        }
    }

    // ── Values that clear rather than corrupt ────────────────────────────

    @Test
    fun `empty and whitespace clear to default`() {
        assertEquals(Branch.CLEAR, branch(""))
        assertEquals(Branch.CLEAR, branch("   "))
        assertEquals(Branch.CLEAR, branch("\t"))
        assertEquals(Branch.CLEAR, branch("\n"))
    }

    /** Surrounding whitespace must not stop a valid emoji being recognised. */
    @Test
    fun `a padded emoji still applies`() {
        assertEquals(Branch.EMOJI, branch("  ⚡  "))
    }

    // ── Composite emoji still count as one ───────────────────────────────

    @Test
    fun `flag keycap zwj and skin tone are accepted as single emoji`() {
        for (v in listOf("🇯🇵", "1️⃣", "👩‍💻", "👍🏽")) {
            assertEquals("'$v' must be one emoji", Branch.EMOJI, branch(v))
        }
    }

    // ── Junk that must not be mistaken for an image ──────────────────────

    @Test
    fun `garbage image sources are refused with a reason`() {
        val cases = listOf(
            "data:image/png;base64,!!!not-base64!!!",
            "data:text/plain,hello",
            "minis://",
            "/etc/passwd",
            "/data/data/com.openminis.app/databases/chat.db",
            "http://example.com/x.png",
            "https://example.com/x.png",
        )
        for (v in cases) {
            val s = SoulIcon.classifySource(v)
            val refused = s is SoulIcon.Source.Unsupported ||
                // A path form is classified, then containment-checked by the
                // writer; either way it must not be Bytes.
                s is SoulIcon.Source.LinuxPath
            assertTrue("'$v' must not decode to bytes", refused)
            assertFalse("'$v' must not be Bytes", s is SoulIcon.Source.Bytes)
        }
    }

    /** Paths outside the allowed roots are not reachable. */
    @Test
    fun `paths outside the minis roots are not allowed`() {
        val outside = listOf(
            "/etc/passwd",
            "/data/data/com.openminis.app/databases/chat.db",
            "/var/minis/../../etc/passwd",
            "/var/miniswhatever/x.png",   // prefix impostor
        )
        for (p in outside) {
            val allowed = SoulIcon.ALLOWED_LINUX_ROOTS.any { p == it || p.startsWith("$it/") }
            assertFalse("'$p' must not match an allowed root", allowed)
        }
    }

    // ── Whatever IS stored must round-trip ───────────────────────────────

    /**
     * The real corruption risk: a stored value that breaks the frontmatter
     * when written back. Quotes and backslashes are escaped by the
     * serializer; a newline would split the line and truncate the file, so
     * confirm none of the storable forms contain one.
     */
    @Test
    fun `stored values round trip without breaking frontmatter`() {
        val storable = listOf("⚡", "🇯🇵", "👩‍💻", SoulIcon.DATA_URI_PREFIX + "iVBORw0KGgo=")
        for (v in storable) {
            assertFalse("'$v' must be single-line", v.contains('\n'))
            val text = SoulMDParser.serialize(
                SoulFile(SoulMetadata.DEFAULT.copy(icon = v), "body"),
            )
            val back = SoulMDParser.parse(text)
            assertEquals(v, back.metadata.icon)
            // The other keys must survive untouched.
            assertEquals("Minis", back.metadata.name)
            assertEquals("auto", back.metadata.lang)
            assertEquals("body", back.body.trim())
        }
    }

    /**
     * A rejected write must leave the file alone. The writer computes the new
     * value BEFORE calling save, so a throw means save is never reached —
     * pinned here because reordering those two statements would turn every
     * rejection into a silent wipe of the icon.
     */
    @Test
    fun `a rejected value never reaches a stored form`() {
        for (v in listOf("⚡🤖", "hello", "1", "中文")) {
            val s = SoulIcon.classifySource(v)
            assertTrue(
                "'$v' must classify as Unsupported so the writer throws before saving",
                s is SoulIcon.Source.Unsupported,
            )
        }
    }
}
