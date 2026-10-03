package com.openminis.app.browser

import android.util.Log
import java.lang.ref.WeakReference
import java.util.Date

/**
 * [T-android-browser-global-tab-cap] Process-wide cap on live WebView tabs
 * across every [BrowserTabPool].
 *
 * Why this exists: [T-android-browser-tab-ownership] raised a pool's ceiling
 * from 3 to [BrowserTabPool.MAX_TABS_WITH_AGENTS] = 6 so concurrent sub agents
 * would stop closing each other's tabs. But an Android pool is NOT a singleton
 * the way iOS's effectively is — one is built per chat ViewModel
 * (`ChatViewModel.kt`), one is resident for `minis-browser-use`
 * (`MinisApp.kt`), and the session list builds one of its own
 * (`SessionListScreen.kt`). So the raised ceiling multiplies by the number of
 * live pools: two chats each running two sub agents is a theoretical peak of
 * 2 x 6 = 12 WebViews. Each Android WebView carries its own out-of-process
 * renderer (tens of MB resident, plus a process record the LMK counts), which
 * is heavier than iOS's WKWebView — that peak is a reclaim storm, not a
 * capacity plan.
 *
 * The fix is a cross-pool gate rather than lowering the per-pool ceiling,
 * because the per-pool number is doing a different and still-correct job:
 * bounding how many tabs ONE conversation's agents may fan out to. Lowering it
 * would re-break the incident that raised it (agents closing each other's
 * tabs) on a single-chat device, where nothing is actually over budget. What
 * is over budget is the process total, and only something above the pools can
 * see that.
 *
 * Arbitration is ported verbatim from iOS `BrowserTabPoolRegistry.requestSlot`
 * (`src/ios/Agent/BrowserUse/BrowserTabPool.swift`) — the semantics are
 * already settled there and are deliberately not re-litigated:
 *
 *  1. under [GLOBAL_TAB_CAP] -> admit
 *  2. otherwise evict the least-recently-active IDLE tab in a SIBLING pool
 *  3. otherwise preempt the least-recently-active BUSY tab in a sibling pool
 *  4. no sibling tabs at all -> deny
 *
 * The requester's own pool is excluded from every pass: a pool must never
 * solve its own admission by cannibalising itself, or an agent's two tabs
 * would take turns destroying each other.
 *
 * ## Concurrency
 *
 * **The registry is main-thread-confined and takes no locks of its own.**
 *
 * That is the whole concurrency strategy, and it is what makes the documented
 * hazard — "never hold pool A's lock while reaching for pool B's" —
 * structurally impossible rather than merely avoided by care:
 *
 *  - Every tab-mutating path in [BrowserTabPool] already runs inside
 *    `withContext(Dispatchers.Main)` (`acquireTab`, `newTab`, `closeTab`,
 *    `createTab`'s callers, `evictIdleTabs`). The registry is only ever
 *    entered from those paths, so [pools] and every victim pool's tab list are
 *    touched on one thread. No mutex is needed to protect them, so there is no
 *    registry lock that could participate in a cycle.
 *  - `BrowserTabPool.executeSerialized` DOES hold a per-tab [kotlinx.coroutines.sync.Mutex]
 *    while the call stack below it reaches `createTab` and therefore this
 *    registry. That is the inversion risk, and it is defused by the registry
 *    never acquiring a `tabLocks` mutex — not the requester's and not the
 *    victim's. Eviction reaches the victim pool through
 *    [RegistryPool.evictTabForRegistry] / [RegistryPool.preemptTabForRegistry],
 *    which mutate `_tabs` and tear the WebView down directly. A victim tab
 *    whose serial mutex is held by some other coroutine is preempted anyway;
 *    that coroutine discovers it on its next call and gets a retry hint (see
 *    [RegistryPool.preemptTabForRegistry]). Waiting for that mutex is exactly
 *    the deadlock we are refusing to build.
 *  - Consequently the registry's methods must stay synchronous and non-
 *    suspending. A `suspend` here would invite someone to `withLock` inside it
 *    later, which is the failure this note exists to prevent.
 *
 * ## Single-pool devices see nothing
 *
 * With one chat open and no agents running, `totalLiveTabs()` sits at 1-3
 * against a cap of [GLOBAL_TAB_CAP], so [requestSlot] returns true on its
 * first line without scanning anything. Step 4 also means a lone pool at cap
 * is DENIED rather than made to eat its own tabs — but a lone pool can never
 * reach the global cap in the first place, since its own ceiling (6) is below
 * it. Covered by `BrowserTabPoolRegistryTest`.
 */
object BrowserTabPoolRegistry {

    private const val TAG = "BrowserTabPoolRegistry"

    /**
     * Hard cap on live WebView tabs across the whole process.
     *
     * iOS uses 8. Android keeps 8 as well, deliberately, even though an
     * Android WebView is the heavier of the two: the number has to sit ABOVE a
     * single pool's own ceiling ([BrowserTabPool.MAX_TABS_WITH_AGENTS] = 6) or
     * the registry would start arbitrating the ordinary single-chat case that
     * per-pool ownership already handles correctly — a user with one chat and
     * two sub agents would suddenly see tabs evicted for no reason visible to
     * them. That leaves 7 or 8 as the only real candidates, and 8 gives one
     * fully-loaded agent chat (6) room to coexist with the resident
     * `minis-browser-use` pool (1-2) without either preempting the other,
     * which is the exact pair that is always live. The peak this replaces is
     * 12+, so the reduction is already the bulk of the win; shaving to 7 would
     * buy one WebView at the cost of making that common pair contend.
     */
    const val GLOBAL_TAB_CAP = 8

    /**
     * The slice of [BrowserTabPool] the registry drives. Extracted as an
     * interface so the arbitration above is unit-testable: the real pool needs
     * a `Context` and constructs real `WebView`s, neither of which exists in a
     * JVM unit test, and this logic is precisely the part worth testing.
     */
    interface RegistryPool {
        /** Snapshot of this pool's tabs, oldest-activity ordering not assumed. */
        fun registrySnapshot(): List<TabInfo>

        /**
         * Destroy an idle tab on behalf of the registry. The caller has
         * already established `inUse == false`; implementations re-check.
         * Must actually release the WebView, not merely drop the list entry.
         */
        fun evictTabForRegistry(id: Int)

        /**
         * Destroy an in-flight tab on behalf of the registry. The agent
         * driving it must get an intelligible, retryable error rather than a
         * bare exception.
         */
        fun preemptTabForRegistry(id: Int)

        /** Destroy every idle tab; returns how many went. Used by onTrimMemory. */
        fun evictAllIdleForMemoryPressure(): Int
    }

    /** The two fields arbitration needs, lifted out of `BrowserTabPool.Tab`. */
    data class TabInfo(val id: Int, val inUse: Boolean, val lastActivityDate: Date)

    /**
     * Weakly held so a closed chat's pool does not keep its WebViews counted
     * (or alive) after the ViewModel is cleared. Mirrors iOS's `WeakPool`.
     * Identity-keyed: pools are compared by reference, never by equals.
     */
    private val pools = mutableListOf<WeakReference<RegistryPool>>()

    fun register(pool: RegistryPool) {
        compactPools()
        if (pools.none { it.get() === pool }) {
            pools.add(WeakReference(pool))
            Log.i(TAG, "Registered pool (${pools.size} live)")
        }
    }

    fun unregister(pool: RegistryPool) {
        pools.removeAll { it.get() === pool || it.get() == null }
    }

    /** Drop weak refs whose pool has been collected. */
    private fun compactPools() {
        pools.removeAll { it.get() == null }
    }

    /** Live pools, strongly held for the duration of one call. */
    private fun livePools(): List<RegistryPool> {
        compactPools()
        return pools.mapNotNull { it.get() }
    }

    /** Total live tabs across every registered pool. */
    fun totalLiveTabs(): Int = livePools().sumOf { it.registrySnapshot().size }

    /**
     * Ask for room to create one more tab in [requester]. See the class note
     * for the four-step ladder. Returns false only when the cap is reached and
     * no OTHER pool has a tab to give up.
     *
     * Synchronous and main-thread-confined by contract — see **Concurrency**.
     */
    fun requestSlot(requester: RegistryPool): Boolean {
        val live = livePools()
        if (live.sumOf { it.registrySnapshot().size } < GLOBAL_TAB_CAP) return true

        val now = System.currentTimeMillis()
        var idleVictim: Victim? = null
        var busyVictim: Victim? = null

        // One pass collects both candidate classes, as iOS does, so a cap-hit
        // does not walk every pool twice.
        for (pool in live) {
            if (pool === requester) continue
            for (tab in pool.registrySnapshot()) {
                val idleFor = now - tab.lastActivityDate.time
                if (!tab.inUse) {
                    if (idleVictim == null || idleFor > idleVictim.idleForMs) {
                        idleVictim = Victim(pool, tab.id, idleFor)
                    }
                } else {
                    if (busyVictim == null || idleFor > busyVictim.idleForMs) {
                        busyVictim = Victim(pool, tab.id, idleFor)
                    }
                }
            }
        }

        idleVictim?.let { v ->
            Log.i(
                TAG,
                "Global cap ($GLOBAL_TAB_CAP) reached — evicting idle tab ${v.tabId} " +
                    "from a sibling pool (idle ${v.idleForMs / 1000}s)",
            )
            v.pool.evictTabForRegistry(v.tabId)
            return true
        }
        busyVictim?.let { v ->
            Log.i(
                TAG,
                "Global cap ($GLOBAL_TAB_CAP) reached with no idle siblings — preempting " +
                    "in-use tab ${v.tabId} (idle ${v.idleForMs / 1000}s)",
            )
            v.pool.preemptTabForRegistry(v.tabId)
            return true
        }
        Log.i(TAG, "Global cap ($GLOBAL_TAB_CAP) reached and no sibling tabs to reclaim — denying")
        return false
    }

    private data class Victim(val pool: RegistryPool, val tabId: Int, val idleForMs: Long)

    /**
     * Release every idle tab across all pools. Tabs an agent is actively
     * driving are left alone so an in-flight action does not fail halfway —
     * memory pressure is not worth corrupting a running task over, and those
     * tabs are the ones a subsequent [requestSlot] can still preempt if the
     * pressure turns out to be real.
     *
     * Called from `MinisApp.onTrimMemory`, i.e. already on the main thread.
     */
    fun handleMemoryPressure(): Int {
        val pools = livePools()
        val evicted = pools.sumOf { it.evictAllIdleForMemoryPressure() }
        if (evicted > 0) {
            Log.i(TAG, "Memory pressure — evicted $evicted idle tab(s) across ${pools.size} pool(s)")
        }
        return evicted
    }

    /** Test hook: drop all registrations. Not used in production code. */
    internal fun resetForTests() {
        pools.clear()
    }
}
