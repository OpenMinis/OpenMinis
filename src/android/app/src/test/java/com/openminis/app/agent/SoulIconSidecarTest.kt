package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-soul-icon-sidecar] The `icon:` frontmatter value, at the disk
 * boundary.
 *
 * A picked avatar is ~20 KB of base64. Held inline it turned a small,
 * human-readable persona file into one whose bulk is a single unreadable line.
 * The bytes now live in a `SOUL.icon.png` sidecar and the frontmatter holds
 * only its name.
 *
 * SOUL.md is a file users carry between builds and platforms, so the value that
 * key can hold is a cross-device contract: this pins the name iOS also writes,
 * and pins that the older inline form is still recognised rather than being
 * mistaken for a filename.
 *
 * Real PNG encode/decode is exercised on device (android.util.Base64 is a stub
 * in JVM unit tests); what is pinned here is the classification that decides
 * WHICH path a stored value takes, which is where a cross-platform mismatch
 * would actually bite.
 */
class SoulIconSidecarTest {

    /**
     * Byte-identical to iOS's `SoulIconImage.sidecarName`. If this ever
     * diverges, an avatar written on one platform silently disappears on the
     * other — the failure this test exists to prevent.
     */
    @Test
    fun `the sidecar filename matches the one iOS writes`() {
        assertEquals("SOUL.icon.png", SoulIcon.SIDECAR_NAME)
    }

    @Test
    fun `a stored sidecar reference is recognised as one`() {
        assertTrue(SoulIcon.isSidecarRef("SOUL.icon.png"))
        // Frontmatter values are unquoted and trimmed by the parser, but a
        // hand-edited file can leave whitespace.
        assertTrue(SoulIcon.isSidecarRef("  SOUL.icon.png  "))
    }

    /**
     * The three other shapes that legitimately reach the resolver must NOT be
     * taken for a filename — each has its own handling and misrouting any of
     * them loses the user's icon.
     */
    @Test
    fun `emoji, inline data URIs and empty values are not sidecar references`() {
        assertFalse("an inline URI is bytes, not a filename",
            SoulIcon.isSidecarRef("data:image/png;base64,iVBORw0KGgo="))
        assertFalse(SoulIcon.isSidecarRef("⚡"))
        assertFalse(SoulIcon.isSidecarRef(""))
        // Near misses: a different sidecar name is not ours to read.
        assertFalse(SoulIcon.isSidecarRef("SOUL.icon.jpg"))
        assertFalse(SoulIcon.isSidecarRef("soul.icon.png"))
    }

    /**
     * Backward and cross-platform compatibility. iOS's own resolver returns an
     * inline data URI as-is, and Android must too: a SOUL.md written by an
     * older build — or by a platform that has not moved yet — still carries
     * its bytes inline, and treating that as anything but bytes loses the icon.
     */
    @Test
    fun `an inline data URI is still recognised as image bytes`() {
        assertTrue(SoulIcon.isDataUri("data:image/png;base64,iVBORw0KGgo="))
        assertFalse(SoulIcon.isDataUri(SoulIcon.SIDECAR_NAME))
    }

    /**
     * The sidecar filename must never be handed to the generic source
     * classifier: it is neither an emoji, a data URI, base64, a minis:// URL
     * nor a /var/minis path, so it would be reported as unsupported. That is
     * exactly what an unported Android did with an iOS-written SOUL.md, and it
     * is why the resolver has to intercept the reference before this point.
     */
    @Test
    fun `the raw filename is unusable as an icon source, which is why it is resolved first`() {
        assertTrue(SoulIcon.classifySource(SoulIcon.SIDECAR_NAME) is SoulIcon.Source.Unsupported)
    }
}
