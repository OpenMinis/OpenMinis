package com.openminis.app.browser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-browser-release-all-semantics] `releaseAllTabs()` promised to
 * release every tab and released nothing.
 *
 * Its whole body was:
 *
 *     _tabs.value = _tabs.value.map { it.copy(inUse = false) }
 *     saveState()
 *
 * — a flag flip, with no `releaseTabResources` and therefore no
 * `webView.destroy()`. Two call sites wanted opposite things from it:
 *
 *  - **Clearing a chat** (ChatViewModel) wanted the session's browser
 *    resources gone; its own comment cites the iOS `deletePersistedData` +
 *    `releasePool` pair. Every WebView stayed alive instead — tens of MB per
 *    tab, for a session whose messages had just been deleted and which the
 *    user could no longer reach. Nothing else collected them: idle eviction
 *    only fires at 15 minutes, and the pool outlives the cleared chat.
 *  - **Takeover** (BrowserSheet's "Minis is browsing · Takeover" overlay)
 *    wanted only to drop the agent's hold so the user can drive the page they
 *    are watching. Destroying there would blank the screen at the exact
 *    moment the user asked for control.
 *
 * One name could not serve both, so the method was split. BrowserTabPool needs
 * a Context and real WebViews, so — as with BrowserTabOwnershipTest — the
 * decision is modelled here and the wiring is pinned as source facts.
 */
class BrowserReleaseAllSemanticsTest {

    // ── The two teardown scopes ────────────────────────────────────────────

    private data class Tab(val id: Int, var inUse: Boolean, var destroyed: Boolean = false)

    /** Mirrors `releaseAllTabsToUser()`. */
    private fun releaseToUser(tabs: MutableList<Tab>): List<Tab> {
        for (t in tabs) t.inUse = false
        return tabs
    }

    /** Mirrors `destroyAllTabs()`. */
    private fun destroyAll(
        tabs: MutableList<Tab>,
        owners: MutableMap<Int, String>,
    ): List<Tab> {
        val doomed = tabs.toList()
        tabs.clear()
        for (t in doomed) {
            t.destroyed = true
            owners.remove(t.id)
        }
        return doomed
    }

    // ── Takeover: the page must survive ────────────────────────────────────

    @Test
    fun `takeover releases the agent's hold without destroying the page`() {
        // The user is looking at this page and just asked to drive it.
        val tabs = mutableListOf(Tab(0, inUse = true), Tab(1, inUse = true))
        releaseToUser(tabs)
        assertTrue("both tabs must be handed over", tabs.all { !it.inUse })
        assertTrue("and none may be destroyed", tabs.none { it.destroyed })
        assertEquals("the tabs must still exist", 2, tabs.size)
    }

    @Test
    fun `takeover is idempotent`() {
        val tabs = mutableListOf(Tab(0, inUse = false))
        releaseToUser(tabs)
        releaseToUser(tabs)
        assertEquals(1, tabs.size)
        assertFalse(tabs[0].destroyed)
    }

    // ── Clear chat: the memory must actually come back ─────────────────────

    @Test
    fun `clearing a chat destroys every WebView`() {
        // The reported leak: this used to leave all of them alive.
        val tabs = mutableListOf(Tab(0, inUse = false), Tab(1, inUse = true), Tab(2, inUse = false))
        val owners = mutableMapOf(0 to "chat", 1 to "child-a", 2 to "child-b")
        val doomed = destroyAll(tabs, owners)
        assertTrue("every WebView must be released", doomed.all { it.destroyed })
        assertTrue("the list must be empty afterwards", tabs.isEmpty())
    }

    @Test
    fun `an in-use tab is destroyed too when the chat is cleared`() {
        // The session's messages are already gone; a tab still marked in-use
        // belongs to work that no longer has anywhere to report back to.
        val tabs = mutableListOf(Tab(7, inUse = true))
        val doomed = destroyAll(tabs, mutableMapOf(7 to "child-a"))
        assertTrue(doomed.single().destroyed)
        assertTrue(tabs.isEmpty())
    }

    @Test
    fun `ownership bookkeeping is forgotten with the tab`() {
        // A later tab reusing this id would otherwise inherit a stale owner —
        // the same reason closeTab() clears it.
        val tabs = mutableListOf(Tab(0, inUse = false), Tab(1, inUse = false))
        val owners = mutableMapOf(0 to "chat", 1 to "child-a")
        destroyAll(tabs, owners)
        assertTrue("no owner may survive its tab", owners.isEmpty())
    }

    @Test
    fun `destroying an empty pool is a no-op`() {
        val tabs = mutableListOf<Tab>()
        val owners = mutableMapOf<Int, String>()
        assertTrue(destroyAll(tabs, owners).isEmpty())
        assertTrue(tabs.isEmpty())
    }

    @Test
    fun `the two scopes must not converge`() {
        val a = mutableListOf(Tab(0, inUse = true))
        releaseToUser(a)
        val b = mutableListOf(Tab(0, inUse = true))
        destroyAll(b, mutableMapOf())
        assertFalse("takeover keeps the tab", a.single().destroyed)
        assertTrue("clear-chat does not", b.isEmpty())
    }

    // ── The wiring ─────────────────────────────────────────────────────────

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val poolSrc by lazy { src("src/main/java/com/openminis/app/browser/BrowserTabPool.kt") }

    @Test
    fun `destroyAllTabs actually releases the WebViews`() {
        val body = poolSrc.substringAfter("fun destroyAllTabs()").substringBefore("\n    }")
        assertTrue(
            "it must call releaseTabResources — the flag flip was the whole bug",
            body.contains("releaseTabResources(tab)"),
        )
        assertTrue("and empty the list", body.contains("_tabs.value = emptyList()"))
        assertTrue("and forget ownership", body.contains("tabOwner.remove(tab.id)"))
    }

    @Test
    fun `the list is emptied before the WebViews are torn down`() {
        // Order matters: anything rendering a tab must drop it before its
        // WebView dies, or a composition is left holding a destroyed view.
        val body = poolSrc.substringAfter("fun destroyAllTabs()").substringBefore("\n    }")
        val publish = body.indexOf("_tabs.value = emptyList()")
        val release = body.indexOf("releaseTabResources(tab)")
        assertTrue("both must be present", publish >= 0 && release >= 0)
        assertTrue("publish must come first", publish < release)
    }

    @Test
    fun `takeover keeps the non-destructive path`() {
        val body = poolSrc.substringAfter("fun releaseAllTabsToUser()").substringBefore("\n    }")
        assertFalse(
            "takeover must NOT destroy — the user is watching that page",
            body.contains("releaseTabResources"),
        )
        assertTrue("it only drops the hold", body.contains("copy(inUse = false)"))
    }

    @Test
    fun `each call site uses the method matching its intent`() {
        val vm = src("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
        assertTrue(
            "clearing a chat must destroy",
            vm.contains("_browserTabPoolRef?.destroyAllTabs()"),
        )
        val sheet = src("src/main/java/com/openminis/app/ui/browser/BrowserSheet.kt")
        assertTrue(
            "takeover must not",
            sheet.contains("onTakeover = { tabPool.releaseAllTabsToUser() }"),
        )
        // The ambiguous name must be gone, or the next caller picks blind.
        // `releaseAllTabsToUser(` must not match, so require the paren to
        // follow the old name immediately, and ignore comment lines (the
        // rename is explained in prose at both the definition and the call).
        val code = (vm + sheet + poolSrc).lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        val callers = Regex("""\breleaseAllTabs\(\)""").findAll(code).count()
        assertEquals("no live caller of the old ambiguous name", 0, callers)
    }
}
