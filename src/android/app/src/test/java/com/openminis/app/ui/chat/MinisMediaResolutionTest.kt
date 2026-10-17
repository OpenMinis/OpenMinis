package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-minis-decode-unify] Inline media must resolve a `minis://` URL exactly
 * like a link TAP does.
 *
 * The field report: old messages' inline images rendered broken while the
 * same URL tapped fine, recovering after an app restart or re-entering the
 * session. Root causes found in source (the "preview cache" theory in the
 * report does not exist in code):
 *
 *  - `resolveMdMediaFile` / `MarkdownText.resolveMediaFile` /
 *    `MinisImageFetcher` decoded with `java.net.URLDecoder`, whose
 *    form-urlencoded semantics turn a literal `+` in a filename into a
 *    SPACE — a real filename character — so the file never resolved.
 *  - None of them offered the second, double-decode candidate that
 *    ChatLinkResolver uses for link taps (T-android-minis-url-double-encoding),
 *    so a double-encoded URL broke the image while the tap worked.
 *  - `MarkdownText` (sub-agent bar, skills screen) resolved against the
 *    GLOBAL bindMounts map — last-writer-wins across sessions — so an old
 *    message's image pointed at whichever session booted its shell last.
 *
 * These tests pin the unified behavior: candidate decode with disk
 * existence, `+` preserved, double-encoded recovered, and the basename
 * fallback staying inside the owning session.
 */
class MinisMediaResolutionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun candidates(path: String) = ChatLinkResolver.minisPathCandidates(path)

    // ─── resolveMinisCandidates: candidate walk + disk existence ────────

    @Test
    fun `plus in a filename resolves`() {
        // URLDecoder would ask for "foo bar.png" and miss.
        val f = tmp.newFile()
        val onDisk = mapOf("/var/minis/attachments/foo+bar.png" to f)
        val hit = resolveMinisCandidates("attachments/foo+bar.png") { onDisk[it] }
        assertEquals(f, hit)
    }

    @Test
    fun `double-encoded path resolves via the second candidate`() {
        val f = tmp.newFile()
        val doubled = "attachments/" + "%E5%9B%BE.png".replace("%", "%25")
        val onDisk = mapOf("/var/minis/attachments/图.png" to f)
        val hit = resolveMinisCandidates(doubled) { onDisk[it] }
        assertEquals(f, hit)
    }

    @Test
    fun `a filename with a literal percent resolves on the first candidate`() {
        // "100%25-done.png" decodes once to "100%-done.png"; the speculative
        // second decode must not win when the first exists on disk.
        val f = tmp.newFile()
        val onDisk = mapOf("/var/minis/attachments/100%-done.png" to f)
        val hit = resolveMinisCandidates("attachments/100%25-done.png") { onDisk[it] }
        assertEquals(f, hit)
    }

    @Test
    fun `no candidate existing yields null`() {
        assertNull(resolveMinisCandidates("attachments/missing.png") { null })
    }

    // ─── resolveMdMediaFallback: session-scoped basename fallback ──────

    private fun sessionFile(sessionId: String, name: String): File {
        val dir = File(tmp.root, "minis-sessions/$sessionId/attachments").apply { mkdirs() }
        return File(dir, name).apply { writeText("img") }
    }

    @Test
    fun `fallback finds the own-session file by basename`() {
        sessionFile("sess-a", "chart.png")
        val hit = resolveMdMediaFallback(tmp.root, "attachments/chart.png", "sess-a")
        // invariantSeparatorsPath keeps the assertion portable to Windows
        // (absolutePath uses backslashes there, which is not what this test is
        // about).
        assertTrue(hit?.invariantSeparatorsPath?.endsWith("minis-sessions/sess-a/attachments/chart.png") == true)
    }

    @Test
    fun `fallback preserves a plus in the basename`() {
        sessionFile("sess-a", "foo+bar.png")
        val hit = resolveMdMediaFallback(tmp.root, "attachments/foo+bar.png", "sess-a")
        assertTrue(hit?.name == "foo+bar.png")
    }

    @Test
    fun `fallback recovers a double-encoded basename`() {
        sessionFile("sess-a", "图.png")
        val doubled = "attachments/" + "%E5%9B%BE.png".replace("%", "%25")
        val hit = resolveMdMediaFallback(tmp.root, doubled, "sess-a")
        assertTrue(hit?.name == "图.png")
    }

    @Test
    fun `fallback never crosses into another session`() {
        sessionFile("sess-a", "chart.png")
        // sess-b has no such file: the fallback must NOT answer with
        // sess-a's copy (basename collision across chats shows the wrong
        // file), and must not fall through to any global bucket here.
        assertNull(resolveMdMediaFallback(tmp.root, "attachments/chart.png", "sess-b"))
    }

    @Test
    fun `fallback probes the global bind bucket`() {
        val dir = File(tmp.root, "minis-global/skills").apply { mkdirs() }
        File(dir, "icon.png").writeText("img")
        val hit = resolveMdMediaFallback(tmp.root, "skills/icon.png", "sess-a")
        assertTrue(hit?.invariantSeparatorsPath?.endsWith("minis-global/skills/icon.png") == true)
    }

    // ─── Display names ──────────────────────────────────────────────────

    @Test
    fun `filenameFromMdUrl keeps a literal plus`() {
        assertEquals("foo+bar.png", filenameFromMdUrl("minis://attachments/foo+bar.png"))
    }

    @Test
    fun `filenameFromMdUrl decodes percent escapes`() {
        assertEquals("图.png", filenameFromMdUrl("minis://attachments/%E5%9B%BE.png"))
    }

    @Test
    fun `filenameFromMdUrl keeps hash in the name`() {
        assertEquals("foo #China.mp4", filenameFromMdUrl("minis://attachments/foo %23China.mp4"))
    }

    // ─── The shared decoder itself stays consistent ─────────────────────

    @Test
    fun `candidates never map plus to space`() {
        assertTrue(candidates("attachments/a+b.png").none { it.contains("a b") })
    }
}
