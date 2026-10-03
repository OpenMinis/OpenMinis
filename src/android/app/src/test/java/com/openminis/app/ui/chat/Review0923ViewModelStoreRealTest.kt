package com.openminis.app.ui.chat

import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Review 2026-09-23 — the bounded ChatViewModel cache, driven through the REAL
 * `ChatViewModelStore` object rather than a re-implemented model.
 *
 * Guards: 478470224 (LRU cap), 552d90538 (memory pressure), cc1606df0 +
 * c2862bf3c (NORMAL / CHILD pools, sticky membership), aa3aab6bb
 * (never evict the key being inserted; isLiveStore for the headless runner).
 *
 * The existing ChatViewModelStore*Test / EvictionGuardTest classes pin source
 * facts and a hand-written model of the eviction arithmetic, on the
 * assumption that the store cannot run on the JVM. It can: the module sets
 * `unitTests.isReturnDefaultValues = true` (android.util.Log is a no-op) and
 * ViewModelStore / ViewModelProvider are plain JVM classes. No ChatViewModel
 * is ever built here, so every store answers "not busy" and only the
 * on-screen and just-inserted carve-outs protect anything — exactly the
 * inputs these cases need.
 *
 * The store is a process singleton, so every test uses fresh UUID keys and
 * releases them in [tearDown].
 */
class Review0923ViewModelStoreRealTest {

    private val created = mutableListOf<String>()

    private fun id(prefix: String): String =
        "$prefix-${UUID.randomUUID()}".also { created += it }

    private fun open(
        sid: String,
        kind: ChatViewModelStore.PoolKind = ChatViewModelStore.PoolKind.NORMAL,
    ): ViewModelStore = ChatViewModelStore.ownerFor(sid, kind).viewModelStore

    private fun live(sid: String, store: ViewModelStore) = ChatViewModelStore.isLiveStore(sid, store)

    @After
    fun tearDown() {
        ChatViewModelStore.setActiveSession(null)
        created.forEach { ChatViewModelStore.release(it) }
    }

    @Test
    fun `the normal pool trims to four, oldest idle first`() {
        val ids = (1..5).map { id("n") }
        val stores = ids.map { open(it) }
        assertFalse("oldest idle session evicted", live(ids[0], stores[0]))
        (1..4).forEach { assertTrue("session $it kept", live(ids[it], stores[it])) }
    }

    @Test
    fun `a re-opened session moves to the most-recently-used end`() {
        val ids = (1..4).map { id("n") }
        val stores = ids.map { open(it) }.toMutableList()
        // Touch the oldest: it must now outlive the second-oldest.
        assertSame(stores[0], open(ids[0]))
        open(id("n"))
        assertTrue("touched session survives", live(ids[0], stores[0]))
        assertFalse("untouched second-oldest evicted instead", live(ids[1], stores[1]))
    }

    @Test
    fun `the store being inserted is never the one evicted, even when everything else is exempt`() {
        val ids = (1..4).map { id("n") }
        val stores = ids.map { open(it) }
        ChatViewModelStore.setActiveSession(ids[0])
        val fresh = id("n")
        val freshStore = open(fresh)
        assertTrue("just-inserted store must stay live (aa3aab6bb)", live(fresh, freshStore))
        assertTrue("on-screen session kept", live(ids[0], stores[0]))
        assertFalse("next-oldest idle session paid instead", live(ids[1], stores[1]))
    }

    @Test
    fun `a child burst never evicts a normal session (real store)`() {
        val normals = (1..4).map { id("n") }
        val normalStores = normals.map { open(it) }
        repeat(9) { open(id("c"), ChatViewModelStore.PoolKind.CHILD) }
        normals.forEachIndexed { i, sid ->
            assertTrue("normal $i survives a child burst", live(sid, normalStores[i]))
        }
    }

    @Test
    fun `a promoted draft keeps its store and its pool`() {
        val draft = "__new__" + UUID.randomUUID()
        created += draft
        val real = id("c")
        val store = open(draft, ChatViewModelStore.PoolKind.CHILD)
        ChatViewModelStore.rename(draft, real)
        assertTrue(live(real, store))
        assertTrue("the draft key resolves through the alias", live(draft, store))
        assertEquals(real, ChatViewModelStore.resolvePersistedId(draft))
        // Four normals must not push the renamed CHILD out: its tag moved with it.
        val normals = (1..5).map { id("n") }
        normals.forEach { open(it) }
        assertTrue("renamed child stays out of the normal pool", live(real, store))
    }

    @Test
    fun `memory pressure releases idle stores and keeps the on-screen one`() {
        val a = id("n")
        val b = id("n")
        val sa = open(a)
        val sb = open(b)
        ChatViewModelStore.setActiveSession(b)
        val released = ChatViewModelStore.handleMemoryPressure()
        assertTrue("released at least the idle store", released >= 1)
        assertFalse(live(a, sa))
        assertTrue(live(b, sb))
    }

    /**
     * [BUG] Stickiness only lasts as long as the store. Eviction removes the
     * pool tag together with the store, and every path that RE-ACQUIRES a
     * finished child — AgentTranscriptScreen (HelperUi.kt:1231), the delegate
     * detail sheet's image poll (HelperUi.kt:914), the Stop glyph
     * (ChatScreen.kt:4525) — calls `HeadlessChatRunner.viewModelFor(ctx, id)`
     * with the NORMAL default. So opening the transcript of a sub agent whose
     * store was evicted re-creates it in the NORMAL pool and evicts one of the
     * user's own warm chats: the cross-pool interference cc1606df0 removed.
     *
     * Proposed fix: pass PoolKind.CHILD at those three re-acquire sites (the
     * session row's `isChild` is available), or keep `poolKinds` entries on
     * eviction and drop them only on `release`.
     */
    @Test
    fun `re-opening an evicted child transcript must not evict a normal chat`() {
        val child = id("c")
        open(child, ChatViewModelStore.PoolKind.CHILD)
        // Six more children push the first one out of the child pool.
        repeat(6) { open(id("c"), ChatViewModelStore.PoolKind.CHILD) }
        val normals = (1..4).map { id("n") }
        val normalStores = normals.map { open(it) }

        // What AgentTranscriptScreen does: default kind.
        open(child)

        normals.forEachIndexed { i, sid ->
            assertTrue(
                "normal chat $i was evicted by re-opening a finished child's transcript",
                live(sid, normalStores[i]),
            )
        }
    }

    // ---- source invariants for the re-acquire sites --------------------------

    private fun read(path: String): String =
        File(path).also { assertTrue("missing ${it.absolutePath}", it.exists()) }.readText()

    @Test
    fun `sub agent creation sites still claim the CHILD pool`() {
        val vm = read("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
        val claims = Regex("""viewModelFor\(\s*context,\s*[\w.]+,\s*com\.openminis\.app\.ui\.chat\.ChatViewModelStore\.PoolKind\.CHILD""")
            .findAll(vm).count()
        assertTrue("createChildViewModel + delegate_task tag CHILD (found $claims)", claims >= 2)
    }

    /**
     * [RISK] `ChatScreen`'s onDispose clears the on-screen id unconditionally.
     * On a CHAT -> CHAT navigation (notification / deep link while a chat is
     * open, MainActivity.kt:727) the NavHost keeps the outgoing destination
     * composed through its exit transition, so the incoming screen's
     * `setActiveSession(X)` runs first and the outgoing one's
     * `setActiveSession(null)` lands after it. The chat on screen is then
     * unprotected from eviction and `minis-config session.*` reports "No
     * active session". Fix: clear only if the slot still holds this screen's
     * id (compare-and-clear).
     */
    @Test
    fun `ChatScreen clears the on-screen id only if it still owns it`() {
        val src = read("src/main/java/com/openminis/app/ui/chat/ChatScreen.kt")
        val dispose = src.substringAfter("ChatViewModelStore.setActiveSession(sessionId)")
            .substringAfter("onDispose {").take(600)
        assertFalse(
            "onDispose must not blindly null the active session; compare with sessionId first",
            dispose.contains("ChatViewModelStore.setActiveSession(null)") &&
                !dispose.contains("activeSessionId") &&
                !dispose.contains("clearActiveSession"),
        )
    }
}
