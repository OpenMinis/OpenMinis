package com.openminis.app.ui

import com.openminis.app.sandbox.PRootKernel
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-image-session-direct] A `minis://` image resolves in its own
 * chat's directory, read directly, whatever other chats are doing — and old
 * links with no session still load.
 */
class MinisImageSessionResolveTest {

    private val filesDir: File = Files.createTempDirectory("minis-img").toFile()

    @After
    fun cleanUp() {
        filesDir.deleteRecursively()
    }

    private fun put(session: String, rel: String, text: String = session): File =
        File(filesDir, "minis-sessions/$session/$rel").apply { parentFile!!.mkdirs(); writeText(text) }

    @Test
    fun `with a session, the file comes from that session even when another chat ran last`() {
        val a = put("A", "attachments/cat.png", "A-cat")
        put("B", "attachments/cat.png", "B-cat")
        PRootKernel.noteLegacySession(filesDir, "B") // B built a shell most recently
        val f = MinisImageFetcher.resolve(filesDir, "minis://attachments/cat.png", "A")
        assertEquals(a.absolutePath, f?.absolutePath)
    }

    @Test
    fun `the session can come from the URI query or the request parameter, query first`() {
        assertEquals("A", MinisImageFetcher.sessionOf("minis://attachments/x.png?session=A", null))
        assertEquals("A", MinisImageFetcher.sessionOf("minis://attachments/x.png?mt=1&sessionId=A", "B"))
        assertEquals("B", MinisImageFetcher.sessionOf("minis://attachments/x.png", "B"))
        assertNull(MinisImageFetcher.sessionOf("minis://attachments/x.png", null))
    }

    @Test
    fun `a session id cannot walk out of minis-sessions`() {
        assertNull(MinisImageFetcher.sessionOf("minis://attachments/x.png?session=..", null))
        assertNull(MinisImageFetcher.sessionOf("minis://attachments/x.png?session=a%2Fb", null))
        assertNull(MinisImageFetcher.sessionOf("minis://attachments/x.png", "../etc"))
        assertNull(MinisImageFetcher.resolve(filesDir, "minis://attachments/../../secret.png", "A"))
    }

    @Test
    fun `an old link with no session still loads (legacy answer)`() {
        val b = put("B", "attachments/old.png")
        PRootKernel.noteLegacySession(filesDir, "B")
        assertEquals(b.absolutePath, MinisImageFetcher.resolve(filesDir, "minis://attachments/old.png", null)?.absolutePath)
    }

    @Test
    fun `an orphan link is found by searching the sessions when the direct lookups miss`() {
        val c = put("C", "attachments/orphan.png")
        PRootKernel.noteLegacySession(filesDir, "B") // points elsewhere
        assertEquals(c.absolutePath, MinisImageFetcher.resolve(filesDir, "minis://attachments/orphan.png", null)?.absolutePath)
        // A link copied from chat C into chat A still loads in A.
        assertEquals(c.absolutePath, MinisImageFetcher.resolve(filesDir, "minis://attachments/orphan.png", "A")?.absolutePath)
    }

    @Test
    fun `a missing file resolves to null rather than throwing`() {
        assertNull(MinisImageFetcher.resolve(filesDir, "minis://attachments/none.png", "A"))
    }

    @Test
    fun `global dirs are read directly and percent-encoding is decoded`() {
        val f = File(filesDir, "minis-global/shared/图 1.png").apply { parentFile!!.mkdirs(); writeText("x") }
        val got = MinisImageFetcher.resolve(filesDir, "minis://shared/%E5%9B%BE%201.png", null)
        assertEquals(f.absolutePath, got?.absolutePath)
    }

    @Test
    fun `the fetcher no longer resolves per-session paths through the global table first`() {
        val src = File("src/main/java/com/openminis/app/ui/MinisImageFetcher.kt").readText()
        val resolve = src.substringAfter("internal fun resolve(filesDir")
        val direct = resolve.indexOf("SessionMounts.sessionDir(")
        val legacy = resolve.indexOf("PRootKernel.resolveHostPath(")
        assertTrue("session-direct lookup comes before the legacy one", direct in 1 until legacy)
        val stream = File("src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt").readText()
        assertTrue(stream.contains("setParameter(com.openminis.app.ui.MinisImageFetcher.SESSION_PARAM, sessionId)"))
    }
}
