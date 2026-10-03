package com.openminis.app.sandbox.offload

import com.openminis.app.sandbox.offload.BrowserCliPools.Resolution
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-browser-cli-own-pool] minis-browser-use runs on the calling
 * chat's own tab pool (iOS BrowserUseOffloadBridge.pool(for:)), not on one
 * process-wide pool that concurrent chats drove at the same time.
 */
class BrowserCliPoolsTest {

    /** Stand-in for a tab pool; identity is what matters. */
    private class Pool(val name: String)

    private val shared = Pool("shared")

    private inner class World(
        val vms: Map<String, Pool> = emptyMap(),
        val sessions: Set<String> = emptySet(),
    ) {
        val fallbacks = mutableMapOf<String, Pool>()
        var created = 0
        fun resolve(caller: String?, mounted: String? = caller): Resolution<Pool> = BrowserCliPools.choose(
            callerSid = caller,
            mountedSid = mounted,
            liveVm = { vms[it] },
            fallback = { fallbacks[it] },
            sessionExists = { it in sessions },
            create = { sid -> created++; Pool("fallback:$sid").also { fallbacks[sid] = it } },
            shared = { shared },
        )
    }

    private fun Resolution<Pool>.pool(): Pool = (this as Resolution.Found).pool
    private fun Resolution<Pool>.via(): String = (this as Resolution.Found).via

    @Test
    fun `two chats get two different pools`() {
        val a = Pool("A"); val b = Pool("B")
        val w = World(vms = mapOf("chatA" to a, "chatB" to b))
        assertSame(a, w.resolve("chatA").pool())
        assertSame(b, w.resolve("chatB").pool())
        assertNotSame(w.resolve("chatA").pool(), w.resolve("chatB").pool())
    }

    @Test
    fun `a chat's shell uses the same pool as its browser_use tool`() {
        val a = Pool("A")
        val r = World(vms = mapOf("chatA" to a)).resolve("chatA")
        assertSame(a, r.pool())
        assertEquals("vm", r.via())
    }

    @Test
    fun `a sub agent whose own view model is gone uses the mounted parent's pool`() {
        val parent = Pool("parent")
        val r = World(vms = mapOf("parent" to parent)).resolve("child", mounted = "parent")
        assertSame(parent, r.pool())
        assertEquals("vm(mounted)", r.via())
    }

    @Test
    fun `no live view model makes one fallback pool per session and reuses it`() {
        val w = World(sessions = setOf("chatA"))
        val first = w.resolve("chatA").pool()
        val again = w.resolve("chatA").pool()
        assertSame(first, again)
        assertEquals(1, w.created)
        assertNotSame(shared, first)
    }

    @Test
    fun `a deleted session is an error, not a new pool`() {
        val w = World()
        val r = w.resolve("gone")
        assertTrue(r is Resolution.SessionGone)
        assertEquals("gone", (r as Resolution.SessionGone).sessionId)
        assertEquals(0, w.created)
    }

    @Test
    fun `only a shell with no chat uses the shared pool`() {
        val r = World().resolve(null)
        assertSame(shared, r.pool())
        assertEquals("shared(no-session)", r.via())
    }

    // ── Wiring ────────────────────────────────────────────────────────────

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun `the handler resolves the caller's pool instead of the shared one`() {
        val h = src("src/main/java/com/openminis/app/sandbox/offload/BrowserUseOffloadHandler.kt")
        assertTrue(h.contains("BrowserCliPools.resolve(app, request.sessionId)"))
        assertTrue(h.contains("pool.execute(input, singleTab = true)"))
        assertFalse(h.contains("sharedBrowserTabPool.execute"))
        assertTrue(
            "files go to the caller's own /var/minis/browser",
            h.contains("SessionMounts.sessionDir(app.filesDir, it, \"browser\")"),
        )
    }

    @Test
    fun `deleting a session releases its fallback pool`() {
        assertTrue(
            src("src/main/java/com/openminis/app/data/session/SessionDeleter.kt")
                .contains("BrowserCliPools.release(id)"),
        )
    }
}
