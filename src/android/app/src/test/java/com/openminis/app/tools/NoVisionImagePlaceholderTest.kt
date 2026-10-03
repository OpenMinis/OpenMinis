package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-image-path-metadata] The placeholder that stands in for image
 * pixels when the target model has no native vision.
 *
 * The rule under test: the image's sandbox path is surfaced whenever we have
 * one, INDEPENDENT of whether a Vision Group is configured. The Vision Group
 * decides which recourse to name, not whether the model learns where the file
 * is — `shell_execute` is exposed unconditionally and runs in the same PRoot
 * sandbox, so a path is actionable (file / identify / OCR / Pillow) with no
 * Vision Group at all. Withholding it produced a dead end; iOS observed a
 * model trying to route around that dead end by inventing a CLI.
 */
class NoVisionImagePlaceholderTest {

    private val path = "/var/minis/attachments/img_1.png"

    private fun text(path: String?, group: Boolean) =
        VisionGroupResolver.noVisionImagePlaceholder(path, visionGroupConfigured = group)

    @Test
    fun `no vision group still hands over the path`() {
        // The regression this exists for.
        val out = text(path, group = false)
        assertTrue("the path must be in the text", out.contains(path))
        assertTrue("and a usable way to act on it", out.contains("shell_execute"))
    }

    @Test
    fun `no vision group does NOT invite read_image`() {
        // Without a group the tool gate never registers read_image for this
        // model, so naming it would invite a call that cannot resolve.
        assertFalse(text(path, group = false).contains("read_image"))
    }

    @Test
    fun `vision group names read_image and the path`() {
        val out = text(path, group = true)
        assertTrue(out.contains(path))
        assertTrue(out.contains("read_image"))
    }

    @Test
    fun `vision group without a path still names the tool`() {
        // Older history rows predate linuxPath.
        val out = text(null, group = true)
        assertTrue(out.contains("read_image"))
        assertFalse("must not print a null path", out.contains("null"))
    }

    @Test
    fun `no path and no group is the only dead end left`() {
        val out = text(null, group = false)
        assertFalse(out.contains("read_image"))
        assertFalse(out.contains("shell_execute"))
        assertFalse("must not print a null path", out.contains("null"))
    }

    @Test
    fun `a blank path is treated as no path`() {
        for (blank in listOf("", "   ")) {
            assertFalse(text(blank, group = false).contains("shell_execute"))
            assertTrue(text(blank, group = true).contains("read_image"))
        }
    }

    @Test
    fun `every tier names the image so it is never implied to be gone`() {
        for (g in listOf(true, false)) {
            for (p in listOf(path, null)) {
                assertTrue(text(p, g).contains("Image attached"))
            }
        }
    }

    // ---- [T-android-image-path-metadata] Vision models get the path too -----

    private fun note(p: String?) = VisionGroupResolver.visionImagePathNote(p)

    @Test
    fun `a vision model is told where the image it can see lives`() {
        val out = note(path)!!
        assertTrue(out.contains(path))
    }

    @Test
    fun `the note says it is the SAME image, not an extra one`() {
        // The disambiguation is load-bearing: N images + N notes must not read
        // as 2N images, or "how many pictures did I send?" is answered wrongly
        // and read_image gets called believing it fetches something new.
        val out = note(path)!!.lowercase()
        assertTrue("must identify the image as the one already shown", out.contains("same image"))
        assertTrue("and explicitly deny it is additional", out.contains("not an additional one"))
    }

    @Test
    fun `no path means no note at all, never a null string`() {
        // Nothing appended beats appending "null".
        for (p in listOf(null, "", "   ")) {
            assertNull("blank/absent path must yield no note", note(p))
        }
    }

    @Test
    fun `the vision note does not invite read_image as a fetch`() {
        // The model can already see the image; the path is for file-level work
        // (re-read at full res, crop, OCR, metadata), not for fetching it anew.
        val out = note(path)!!
        assertTrue(out.contains("only if you need to work on the file"))
    }
}
