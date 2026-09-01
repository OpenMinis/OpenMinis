package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-minis-url-double-encoding] Decode candidates for a `minis://`
 * path.
 *
 * The field report: `minis://workspace/厚升凭证拆分/厚博-2026-03-935.pdf` was
 * unclickable in chat — tapping did nothing at all — while the same file under
 * an ASCII directory opened fine. A tap that resolves to no file falls through
 * to `ChatLinkAction.Web`, and a web preview of a `minis://` URL renders
 * nothing, so the link simply looks dead.
 *
 * Ported from iOS `MinisURLPathDecoding` (T-fix-double-encoding 2026-06-01),
 * which fixed the same defect there.
 */
class MinisPathCandidatesTest {

    private fun candidates(path: String) = ChatLinkResolver.minisPathCandidates(path)

    private val cjkDir = "%E5%8E%9A%E5%8D%87%E5%87%AD%E8%AF%81%E6%8B%86%E5%88%86"
    private val cjkFile = "%E5%8E%9A%E5%8D%9A-2026-03-935.pdf"

    // ─── The correct, single-encoded case ────────────────────────────────

    @Test
    fun `single-encoded CJK path decodes in one pass`() {
        val out = candidates("workspace/$cjkDir/$cjkFile")
        assertEquals("workspace/厚升凭证拆分/厚博-2026-03-935.pdf", out.first())
    }

    @Test
    fun `a fully-decoded path is returned unchanged`() {
        // The agent sometimes emits raw UTF-8 with no encoding at all.
        val raw = "workspace/厚升凭证拆分/厚博-2026-03-935.pdf"
        assertEquals(raw, candidates(raw).first())
    }

    @Test
    fun `an ASCII path yields exactly one candidate`() {
        // Nothing to decode twice — no speculative second candidate.
        val out = candidates("workspace/houbo-vouchers/report.pdf")
        assertEquals(listOf("workspace/houbo-vouchers/report.pdf"), out)
    }

    // ─── The regression: double-encoded input ────────────────────────────

    @Test
    fun `double-encoded CJK path is recovered by the second candidate`() {
        // %E5 re-encoded to %25E5 by an intermediate sanitize/autolink step.
        // One decode pass leaves a literal "%E5…" that matches no file.
        val doubled = "workspace/" +
            cjkDir.replace("%", "%25") + "/" + cjkFile.replace("%", "%25")
        val out = candidates(doubled)
        assertTrue("must offer a second candidate", out.size >= 2)
        assertEquals(
            "workspace/厚升凭证拆分/厚博-2026-03-935.pdf",
            out[1],
        )
    }

    @Test
    fun `double-encoded ASCII-dir path is also recovered`() {
        // The user's "working" case was only working by luck — an English
        // directory has nothing to encode, so fewer segments could be
        // corrupted. A CJK FILENAME under it is still vulnerable.
        val doubled = "workspace/houbo-vouchers/" +
            "%E5%8E%9A%E5%8D%9A-2026-03-931.pdf".replace("%", "%25")
        val out = candidates(doubled)
        assertTrue(out.size >= 2)
        assertEquals("workspace/houbo-vouchers/厚博-2026-03-931.pdf", out[1])
    }

    @Test
    fun `the single-decoded candidate always comes first`() {
        // Ordering is the safety property: a filename that legitimately
        // contains a '%' must resolve via candidate 0 and never reach the
        // extra decode. Disk existence is the disambiguator in the caller.
        val out = candidates("workspace/100%25-done/report.pdf")
        assertEquals("workspace/100%-done/report.pdf", out.first())
    }

    // ─── `+` must stay a plus, not become a space ────────────────────────

    @Test
    fun `plus in a path segment is not turned into a space`() {
        // java.net.URLDecoder implements form-encoding, where '+' means SPACE.
        // A directory literally named "a+b" is real on disk, and decoding it
        // to "a b" resolves to nothing — the same dead-link symptom.
        val out = candidates("workspace/a+b/file.pdf")
        assertEquals("workspace/a+b/file.pdf", out.first())
        assertTrue(
            "no candidate may contain 'a b'",
            out.none { it.contains("a b") },
        )
    }

    @Test
    fun `encoded space still decodes to a space`() {
        // %20 is a genuine space and must still decode.
        assertEquals("workspace/my dir/file.pdf", candidates("workspace/my%20dir/file.pdf").first())
    }

    // ─── Robustness ──────────────────────────────────────────────────────

    @Test
    fun `a malformed percent escape does not throw`() {
        // A stray '%' that isn't a valid escape must degrade gracefully
        // rather than crash the tap handler.
        val out = candidates("workspace/broken%ZZ/file.pdf")
        assertTrue(out.isNotEmpty())
    }

    @Test
    fun `an empty path yields one empty candidate`() {
        assertEquals(listOf(""), candidates(""))
    }

    @Test
    fun `hash in a filename survives decoding`() {
        // Attachment names legitimately contain '#'; the resolver deliberately
        // does not treat it as a fragment separator.
        val out = candidates("attachments/foo %23China.mp4")
        assertEquals("attachments/foo #China.mp4", out.first())
    }
}
